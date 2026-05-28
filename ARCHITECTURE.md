# App architecture

How `ProxyService` + `MainActivity` orchestrate the proxy-agent runtime
and expose state to the user. Two engines are supported (see
[Agent engines](#agent-engines) below): **NATIVE** (default, pure-Kotlin
port at `app/src/main/java/com/proxyagent/app/nativeagent/`) and
**BINARY** (bundled `.so` subprocess — see [BINARIES.md]). Both speak
the same wire protocol to the registrator infrastructure; the choice
trades off startup cost, subprocess isolation, and Wi-Fi-return
compatibility.

[BINARIES.md]: ./BINARIES.md

## Process / activity model

- `ProxyService` runs in its own `:proxy` process
  (`AndroidManifest.xml:80`). When the host app dies, the subprocess
  dies with it.
- `MainActivity` does **not** bind to the service. Communication is
  one-way and file-based: service → UI writes state files in
  `filesDir`; UI → service uses `startService` with Intent extras
  and `action=STOP`. No Binder, no LocalBroadcastManager, no shared
  Application.
- Reconfig = stop-and-restart. The BINARY engine forks a subprocess
  so any env change requires a fresh exec; the NATIVE engine could in
  principle be reconfigured in-place (`NativeProxyAgent.start(Config)`
  accepts a fresh config) but we keep the same contract so the UI
  layer doesn't have to fork per engine.

## State files in `filesDir`

| File | Owner | Format | Purpose |
| --- | --- | --- | --- |
| `proxy_state` | service | text | One of `starting`/`running`/`stopped`/`auto_stopped`/`error`. Written on every transition. Terminal values (`stopped`/`auto_stopped`/`error`) are sticky across `conn_info` stale-detection — see [Surviving an app update](#surviving-an-app-update). |
| `conn_info` | service | pipe-delimited | Connection state, traffic rates, current rotation stage, wall-clock heartbeat. Written every 1s + on transitions. Schema below. |
| `stop_reason` | service | text | Human-readable reason; non-empty only when `proxy_state=auto_stopped`. |
| `agent.log` | service | text | Timestamped tail of binary stdout. Rotated 30 → 25 MiB on overflow. Each new `:proxy` process stamps a `=== app vX.Y.Z (build N) pid=… ===` line on entry so post-mortem analysis can tell which app version produced which log segment after upgrades. |
| `nat_ip` | service | text | Last-known public IP (best-effort, refreshed every 5 min and after successful rotation). |
| `battery_threshold` | UI | int | Auto-stop threshold in percent (0 disables). |
| `speed_units` | UI | text | `bits`/`bytes` for rate display. |
| `cycle_cfg.json` | UI writes, both read | JSON | Cross-process mirror of the cycle settings (`apn_swap`, `imei_rotate`, `imei_method`, `imei_cmd`). SharedPreferences are per-process — the REBOOT auto-cycle in `:proxy` would otherwise miss toggle changes made in `:main`. Rewritten on every settings save and re-mirrored on app launch as a back-fill. |
| `analytics/cycle_events.jsonl` | both write | JSONL | One row per rotation attempt with old/new IP, success flag, attempts and duration. Read by the swipe-panel chart, the analytics screen, and the CSV export. Pruned by the same retention policy as bucket files. |
| `analytics/<yyyy-MM-dd>.jsonl` | service writes | JSONL | One per-minute `AnalyticsBucket` row per active minute. Keys: `t` (start ms), `rx/tx` (UID-wide TrafficStats deltas — always present and correct regardless of wifi_return state), `op/cl/pk` (tunnel events), `reg/nat/tr` (last-known registrator / public IP / default transport). When Wi-Fi return is **opt-in active** (relay alive at tick time), four extra keys appear: `wrx/wtx` (relay's Wi-Fi-bound upstream bytes) and `crx/ctx` (native agent target-dial bytes + relay fallback bytes — both cellular by construction). The `wrx+wtx` sum across buckets ≈ "mobile data saved" for the period. When Wi-Fi return is **off**, those four keys stay zero (we don't have per-interface visibility without the relay's bind, and refuse to guess); `rx/tx` totals remain the source of truth. Optional keys are emitted only when non-zero; readers use `optLong("...", 0L)` for forward/backward compat. Pruned by retention policy at app launch. |
| `wifi_info.json` | service | JSON | Latest Wi-Fi return split-routing self-test result + Wi-Fi link snapshot. Keys: `public_ip_wifi`, `public_ip_cell`, `public_ip_default` (process default route, used to detect target-dial leak), `link_speed_mbps`, `frequency_mhz`, `band` (`"2.4 GHz"`/`"5 GHz"`/`"6 GHz"`), `standard` (`"Wi-Fi 5 (802.11ac)"` etc.), `wifi_attached`, `test_result` (`SUCCESS`/`SAME_IP`/`LEAK_DETECTED`/`WIFI_PROBE_FAILED`/`CELL_PROBE_FAILED`/`BOTH_FAILED`), `test_detail`, `test_duration_ms`, `tested_at_ms`. Written by ProxyService after each self-test; read by MainActivity for the widget two-IP block and the log-export header. |
| `was_running` | service | text (`"1"`) | Auto-restart breadcrumb. Written by `ProxyService.onStartCommand` after `startForeground` and **deleted by `doStop`**. Present-after-kill means the previous session ended unexpectedly (PACKAGE_REPLACED, low-memory kill); `PackageReplacedReceiver` gates auto-restart on this file so an intentional STOP doesn't get resurrected. See [Surviving an app update](#surviving-an-app-update). |
| `ip_cycle_in_progress` | service | epoch ms (informational) | Crash-recovery breadcrumb for `IpCycle.cycleAndVerify`. Written on entry, deleted in `finally`. A leftover file at app launch means the rotation was killed mid-sequence and `recoverInterruptedCycle` should flip `airplane_mode_on` back to 0. See [IP rotation — interrupted-cycle recovery](#interrupted-cycle-recovery). |
| `ip_cycle_rat_saved` | service | int (RAT mode) | Holds the pre-rotation `preferred_network_mode` value across the GSM-only RAT switch. Written by `saveAndSetGsmOnly` before the put, deleted by `restoreRat`. Read by `recoverInterruptedCycle` to put the RAT back if we died between save and restore — otherwise the device stays pinned to 2G/3G until the user fixes it manually through system Settings. |

## `conn_info` schema

One pipe-delimited line written by `writeConnInfo`
(`ProxyService.kt:198-227`). Eighteen fields (0-indexed):

| # | Field | Type | Meaning |
| --- | --- | --- | --- |
| 0 | `connStatus.name` | enum string | `STARTING` / `CONNECTING` / `CONNECTED` / `RECONNECTING` / `ERROR` / `STOPPED` |
| 1 | `rxRate` | long | Bytes/sec downlink (`TrafficStats.getUidRxBytes`). |
| 2 | `txRate` | long | Bytes/sec uplink. |
| 3 | `currentRegistrator` | string | `host:port` of the selected registrator. |
| 4 | `activeTunnels` | int | Currently open tunnel count. |
| 5 | `connectedSinceMs` | long | Epoch ms of last successful AUTH; 0 when not CONNECTED. |
| 6 | `currentUplinkTransport` | string | One of: `QUIC` (both engines) / `TCP (splice)` (BINARY always, NATIVE when the kernel zero-copy shim activated) / `TCP (NIO)` (NATIVE when splice couldn't be used and the bridge fell back to NIO + DirectByteBuffer) / `TCP` (NATIVE momentary state between `uplink connected` and the first splice/fallback decision — usually invisible thanks to `SpliceShim.warmup()` resolving it before the supervisor dials) / `TCP+yamux` / `WebSocket` (legacy pre-2.0.14 SDKs). Added v2.0.14-quic; splice/NIO distinction added with the NATIVE engine. |
| 7 | `cycleStage` | string | Non-empty only during REBOOT auto-cycle. UI shows `ROTATING · <stage>`. Added with `IpCycle.cycleAndVerify` rework. |
| 8 | `wifiReturnStatus` | string | `""` (relay off) / `"wifi"` (uplink on Wi-Fi, split routing verified) / `"wifi_fallback"` (relay up, no Wi-Fi held — flowing through cellular) / `"leak_known"` (BINARY engine: relay running for uplink savings, but target dials leak Wi-Fi IP — expected on BINARY) / `"split_failed"` (sticky: self-test rejected the relay on an in-process engine, relay disabled). UI maps to cyan / amber / amber-warning / red respectively. Added with Wi-Fi return relay. |
| 9 | `heartbeatMs` | long | Wall-clock epoch ms of the most recent `writeConnInfo` call. Refreshed by the 1Hz status updater + on every transition. `MainActivity.readLiveProxyFiles` treats the file as stale when `now - heartbeatMs > STALE_CONN_INFO_MS` (5 s) — the writer is dead and the file is wiped so the UI doesn't keep showing fake CONNECTED + accumulating uptime. See [Surviving an app update](#surviving-an-app-update). |
| 10 | `pid` | int | `android.os.Process.myPid()` of the writer. Debug aid: a pid in `conn_info` that doesn't match any live `ProxyService` process is direct proof the file is a leftover from a previous incarnation. Not currently consumed by code — readers gate on the heartbeat alone. |
| 11 | `schemaVersion` | int | Layout version. v1 = + heartbeat/pid/schema (fields 9–11). v2 = + Wi-Fi return session byte counters (fields 12–17). Not gated on (readers use positional `getOrNull(N)` for forward/backward compat); recorded so future readers could branch on layout if/when fields get dropped or reordered. |
| 12 | `wifiUpBytes` | long | Session-lifetime bytes sent through the relay's upstream socket **while bound to Wi-Fi** (the actual savings). Source: `WifiReturnRelay.wifiUpBytes()`. |
| 13 | `wifiDownBytes` | long | Bytes received from the relay's upstream socket while bound to Wi-Fi (the "обратный трафик" — response data flowing back to clients). |
| 14 | `fallbackUpBytes` | long | Bytes through the relay's upstream socket **while wifiNet was null** (relay accepted but routed through process default — typically cellular when process bind is on). No savings. |
| 15 | `fallbackDownBytes` | long | Inbound counterpart of fallback. |
| 16 | `targetUpBytes` | long | Bytes the native agent dialed *to* target hosts (cellular when process bind is active). From `NativeProxyAgent.targetUpBytes()`. Accurate on both fast paths: the NIO fallback ticks per `SocketChannel.read`, and `SpliceShim.copy` invokes a per-call `onBytes` callback with the kernel-reported total once the splice loop completes. So Wi-Fi return widget numbers are honest even when zero-copy is fully engaged. |
| 17 | `targetDownBytes` | long | Inbound counterpart. Same accounting path. |

`MainActivity.refresh()` polls every 3s, reads with `getOrNull(N)` so
forward-compat survives older downgrades that write fewer fields. `|`
characters inside `cycleStage` are escaped to `/` so a stray pipe in a
log line can't shift the field count. Pre-heartbeat snapshots (any
build before this field set was introduced) get `heartbeatMs=0L` from
`getOrNull(9)` and are treated as stale on first read — wiped before
they can poison the UI.

## Status badge mapping (`MainActivity.refresh`)

Before any of the rules below run, `readLiveProxyFiles()` checks the
`conn_info` heartbeat (field 9). If `now - heartbeatMs > 5s`, the
writer is dead — `conn_info` is wiped, and `proxy_state` is wiped
**only if it doesn't hold a terminal value**. `stopped`/`auto_stopped`/
`error` stay so the UI keeps showing `AUTO-STOPPED · Battery 5%` or
`ERROR` after the process has long exited; `running`/`starting` are
treated as a live-state lie and wiped. Without this gate, a kill mid-
session (PACKAGE_REPLACED, OOM) would leave the UI stuck on
`CONNECTED · up Xh` forever.

Priority order — first match wins:

1. **`cycleStage` non-empty** → orange `ROTATING · <stage>`. Beats
   everything else because the WS read error from killed cellular
   would otherwise paint RECONNECTING…, which tells the user nothing
   useful about the rotation actually in progress.
2. Pending UI action `stop` → orange `STOPPING…`.
3. Not running & not configured → orange `NOT CONFIGURED · TAP START
   TO IMPORT`.
4. `proxyState=error` → red `ERROR`.
5. `proxyState=auto_stopped` → orange `AUTO-STOPPED · <reason>`.
6. `connStatus`-based: CONNECTED → green with rates;
   CONNECTING/RECONNECTING/STARTING → orange.
7. Fallbacks: `running` → green `RUNNING`; pending `start` → orange;
   else red `DISCONNECTED`.

## Settings (SharedPreferences `cfg`)

Backed by the dialog at `MainActivity.kt:278-408` (XML
`res/layout/dialog_settings.xml`):

| Key | Type | Default | Used for |
| --- | --- | --- | --- |
| `mode` | string | `"modem"` | Connection mode dispatch — see below. |
| `h`, `p`, `k`, `id`, `dns` | strings | — | Host, port, key, agent UUID, DNS overrides. |
| `engine` | string | `"native"` | `"native"` (in-process Kotlin port — default) or `"binary"` (subprocess `.so`). See [Agent engines](#agent-engines). |
| `speed_bytes` | bool | false | Rate display unit. |
| `analytics_retention_days` | int | 30 | Older analytics buckets are pruned on app launch. |
| `apn_swap` | bool | false | Enable APN swap fallback during cycle (see IP rotation §). |
| `imei_rotate` | bool | false | Enable IMEI rotation fallback. |
| `imei_method` | string | `"custom"` | `"custom"` / `"props"` / `"magisk-imei"`. |
| `imei_cmd` | string | — | Root shell command when `imei_method="custom"`. |
| `wifi_return` | bool | false | Route the agent↔registrator uplink over Wi-Fi via a loopback relay; target dials still ride cellular. Modem mode only — auto-clamped to false on save when `mode="balancer"`. See "Wi-Fi return relay" below. |
| `wifi_return_method` | string | `"local_relay"` | Slot for future methods (SO_MARK, VpnService split tunnel). No UI yet — only `local_relay` is implemented; anything else falls back to direct dial with a log line. |
| `network_profile` | string | `"LOW_100"` | Network optimization preset — `"LOW_100"` / `"MID_500"` / `"HIGH_1000"`. Scales TCP socket / bridge buffers AND QUIC Brutal CC target / UDP socket buffer / flow-control refresh cadence to match the expected link ceiling. Default `LOW_100` matches the common-case mobile/Wi-Fi link (≤100 Mbps) and bounds bufferbloat tighter; field tests show it still delivers full multi-flow throughput on gigabit links through parallel target dials. NATIVE engine only; BINARY ignores it (`libproxyagent.so` has no env hooks for these values — logged as a WARN at runBinaryEngine start). Applies on the next stop/start; changing it mid-session shows a Toast and waits for a manual restart. See [NetworkProfile-driven tuning](#networkprofile-driven-tuning). |

## Agent engines

Two runtimes for the proxy-agent client live side-by-side in the
APK, but **only NATIVE is production-viable**. BINARY is kept
short-term for testing/comparison and will be removed once NATIVE
has enough field hours. Both speak the same wire protocol (TCP/QUIC
uplink with the `TUNL` magic-header framing, JSON-line control
channel, 32-hex-byte per-stream tokens — see [BINARIES.md] §3 for
the byte-level details) so the registrator infrastructure pairs
with either interchangeably. `engine` pref selects which one
`runX-Engine()` in `ProxyService.onStartCommand` dispatches to.

**Why only NATIVE works for the full feature set:**
- **BINARY** cannot support Wi-Fi return. `bindProcessToNetwork(cellular)`
  doesn't survive `fork+exec`, so SDK target dials in the subprocess
  always go through the default route (Wi-Fi on dual-transport
  devices) and leak the Wi-Fi public IP to targets. The self-test
  catches this as `LEAK_DETECTED`. Acceptable as a baseline modem
  client without Wi-Fi return, but the whole split-routing feature
  is off-limits.
- **NATIVE** runs in `:proxy` so process bind sticks for target
  dials; no Go runtime, no subprocess pipes. Uses a tiny optional
  JNI shim (`libagentsplice.so`, ~15 KiB per ABI) for the TCP
  zero-copy fast path; the agent runs fine without it too. Can be
  dropped into third-party apps as a small Kotlin module — see
  [Drop-in for third-party apps](#drop-in-for-third-party-apps).

| Engine | Where it lives | Process | Notes |
| --- | --- | --- | --- |
| `NATIVE` (default) | `app/src/main/java/com/proxyagent/app/nativeagent/` + `app/src/main/cpp/` | In-process (`:proxy`) | Pure-Kotlin port of the Go SDK. No subprocess, no Go runtime. Optional ~15 KiB JNI shim for kernel `splice(2)` zero-copy on the TCP fast path — automatic NIO fallback if the .so isn't present or fd extraction fails. Drop-in reusable in third-party apps — see [Drop-in for third-party apps](#drop-in-for-third-party-apps) below. |
| `BINARY` | `proxy-agent-linux-arm64` packed as `libproxyagent.so` (see [BINARIES.md]) | Subprocess via `ProcessBuilder` | Forked `:proxy` child runs the Go SDK as an unmanaged ELF; we parse its stdout. Wi-Fi return cannot bind it to cellular (`bindProcessToNetwork` doesn't survive `fork+exec`), so Wi-Fi-return target dials leak — UI shows `leak_known`. |

### NATIVE engine — internal layout

`NativeProxyAgent` (in `nativeagent/NativeProxyAgent.kt`) is the only
public class consumers touch. Everything else in the package is
internal helpers laid out to mirror the Go SDK:

| Kotlin | Go equivalent | Role |
| --- | --- | --- |
| `NativeProxyAgent.supervisorMain` | `internal/supervisor/supervisor.go` | Top-level loop: registrator discovery → uplink session → reconnect backoff → repeat. Owns the daemon supervisor thread. |
| `BalancerClient` | `internal/registrator/balancer.go` | `GET /getRegistrator` with `Authorization: Bearer <key>`; JSON-decodes host/port/health_check_port. |
| `FallbackSelector` | `internal/registrator/fallback.go` | Downloads the fallback list, probes each `/health`, picks the best via the same comparator (ready > free_sockets > cpu > ram > agent_count). |
| `ExponentialJitterBackoff` | `internal/backoff/backoff.go` | 250ms → 10s with full jitter. |
| `DnsConfig` + `MiniDnsClient` | `internal/dnsconfig/dnsconfig.go` | DNS override (`Config.dnsServers`) — falls back to the JVM resolver when no override is set, otherwise issues UDP A-record queries directly to the configured servers. |
| `Uplink` (TCP path) | `internal/netagent/uplink.go` startTCP + control loop | Magic `TUNL` + version + connType handshake, AUTH/AUTH_OK on a newline-JSON control channel, warm pool of 8 pre-handshaked data sockets, `OPEN`/`OPEN_FAIL`/`REBOOT` dispatch, two-thread bridge with half-close on EOF. |
| `Uplink.DataPool` | `internal/netagent/pool.go` | Refills to 8 sockets on a 5s tick; `take()` pulls one and wakes the refiller. Stores `SocketChannel` rather than `Socket` so the hot bridge path feeds straight into NIO without a wrap/unwrap step. |
| `Uplink.copyChannel` + `SpliceShim` | Go stdlib `io.Copy` splice fast path | Kernel zero-copy bridge between two TCP `SocketChannel`s — see [NATIVE engine TCP fast path](#native-engine-tcp-fast-path) below for the splice → NIO ladder. |
| `Uplink` (QUIC path) + `QuicTransport` SPI | `internal/netagent/uplink.go` startQUIC + accept loop | Opens the first stream as control, accepts server-initiated streams in parallel via a separate thread, reads JSON tunnel header (`{host,port}\n`) and bridges to a fresh TCP target dial. QUIC bridges stay in userspace — splice doesn't apply because QUIC streams aren't kernel fds. |
| Transport cache file | `internal/netagent/transport_cache.go` | `Config.workDir/.proxyagent_transport` remembers `tcp` / `quic` so reconnects skip the failing probe. |
| `MiniJson` | (stdlib `encoding/json`) | Tiny parser/emitter for objects/arrays/strings/numbers/booleans — just enough for balancer responses + control envelopes. No third-party deps. |

QUIC support is **pluggable** via the `QuicTransport.Factory` interface
in the same package. `KwikQuicTransport` is the bundled implementation,
backed by `tech.kwik:kwik:0.10.10`. Tuning mirrors
`proxy-agent-sdk-go/internal/netagent/quic_tuning.go` where the kwik
builder exposes the equivalent:

- `defaultStreamReceiveBufferSize(16 MiB)` — per-stream receive window.
  Side-effect (kwik internals): also raises the connection-level cap
  to 10× this value (160 MiB) — enough headroom for several parallel
  upload tunnels.
- `maxOpenPeerInitiatedBidirectionalStreams(1024)` — peer-initiated
  stream cap.
- `socketFactory` builds a `DatagramSocket` with `receiveBufferSize` /
  `sendBufferSize = 32 MB` (fixed — kwik adapter does not honor the
  `network_profile` preset; only the in-house `NativeQuicTransport`
  does). Android usually clamps to `net.core.rmem_max` (4–8 MiB); we
  request 32 MB and take whatever the OS gives.
- `keepAlive(20)` matches the Go config's `KeepAlivePeriod`.

**Congestion controller swap — `SenderImpl.congestionController`.**
kwik 0.10.x's public Builder exposes no CC plugin point. kwik's
default `NewRenoCongestionController` caps QUIC send-side throughput
around 40 Mbps on healthy ~86 ms RTT paths (slow-start +
multiplicative decrease on spurious loss). The factory replaces it
with `tech.kwik.core.cc.FixedWindowCongestionController` at a 16 MiB
fixed window (≈ 1.5 Gbps ceiling at 86 ms RTT) via reflection on
private fields. `FixedWindowCC` inherits all bytes-in-flight
accounting from `AbstractCongestionController` but never modifies
the window — same "trust the link" behavior as Brutal CC without
re-implementing the algorithm. Path traversed:
`QuicClientConnectionImpl.sender → SenderImpl.congestionController`.
`bytesInFlight` is migrated from old to new CC so handshake ACKs
don't underflow the new counter. Outcome surfaces in `agent.log`
as `uplink: QUIC congestion control state=swapped` (or
`skipped:<reason>`). Best-effort: any reflection failure leaves
NewReno in place. Pin kwik or re-verify
`KwikQuicTransport.Companion.swapToFixedWindowCC` on dependency
bumps (field names `sender`, `congestionController`, `bytesInFlight`,
`log` must still exist).

**Empirical ceiling and a do-not-revisit knob.** Even with the CC
swap, single-stream QUIC download tops out around 40-45 Mbps on the
healthy ~86 ms paths we measure (vs 90+ Mbps for the BINARY
engine's quic-go + Brutal). The next obvious lever — kwik's tiny
50 KB per-stream `SendBuffer.maxBufferSize` — was tried in build 75
(reflection bumping both `SendBuffer.maxBufferSize` and
`StreamOutputStreamImpl.maxBufferSize` to 4 MiB on every stream)
and reverted. Effect: download direction unchanged (~37 Mbps), but
the **receive** direction collapsed from 96 → 23 Mbps in speedtest.
Hypothesis: kwik's single sender thread spent its wall time draining
the larger STREAM-frame backlog, starving the connection's
`MAX_STREAM_DATA` / `MAX_DATA` window-update emissions to the peer.
With a stale flow-control window the peer's stack throttled its
own sends, gating the direction we were *receiving* on. The 50 KB
default doubles as implicit fairness between the sender's STREAM-
frame work and its ACK/window-update work. Lifting it cleanly
requires either splitting that thread or batching window updates
on a separate cadence — a non-trivial kwik-internals change. Until
then, do not re-patch `SendBuffer.maxBufferSize`; the speed cost
of higher-throughput download is too much receive-side throughput.

ALPN is `proxy-tunnel/1`, certificate validation is disabled
(`noServerCertificateCheck()` — same posture as the Go SDK's
`InsecureSkipVerify: true`; identity is verified by the AUTH key on
the control channel, not the cert chain).

When `Config.quicTransportFactory` is `null`, the QUIC half is
skipped entirely — the agent runs TCP-only and `chooseTransportOrder`
returns `["tcp"]`. This is the path third-party integrators who
don't want the kwik dependency take.

### In-house QUIC (`NativeQuicTransport`) — send-path regression guards

The second `QuicTransport.Factory`, `NativeQuicTransport`
(`nativeagent/quic/`), is our hand-rolled QUIC v1 client and is the
**default** path now. The kwik adapter is still compiled in as a
safety-net manual override but no longer exposed through the
Settings UI — the dialog picker was removed once the in-house path
became stable. To flip back temporarily, send the start Intent with
extra `quic_impl=kwik` from `MainActivity.startService(...)`; with
no extra, `ProxyService` defaults `quicImpl="native"`. The in-house
stack exists so we can ship Brutal CC and prioritise control-frame
emission — the two things kwik couldn't give us (see the
do-not-revisit note above). Full design is in
`nativeagent/quic/DESIGN.md`; the load-bearing invariants that are
easy to regress:

- **Send pacing is a wall-clock token bucket, NOT per-packet sleeps.**
  `Connection.drainStreams` drains continuously, charging wire bytes
  against `paceBudgetBytes` (refilled from elapsed-time × the CC target
  rate, capped at a 64 KB burst). The earlier version gated each packet
  on Brutal CC's `nextSendTimeNanos` plus `Thread.sleep(delayNanos/1e6)`
  — which on Android rounds an 88 µs pacing delay to `sleep(0)` and so
  sent **one frame per 20 ms sender tick (~440 Kbps for the entire
  connection)**. That — not a throughput "gap" — is why the in-house
  path appeared to "load nothing" while kwik worked. Do not reintroduce
  per-packet sleeping; Android's sleep granularity makes it unusable.
  The bucket measures elapsed time, so it is immune to sleep precision
  and mirrors quic-go's `pacer.Budget()`.

- **The sender loop must be woken on writes and window grants.** It
  otherwise idle-polls at 20 ms, adding latency to every response.
  `Stream.sendWakeup` (installed by `Connection` on both stream-creation
  paths) offers a `Tick` when the bridge writes; receiving
  `MAX_DATA` / `MAX_STREAM_DATA` also offers one so a flow-blocked
  sender resumes at once. While data remains the loop polls at 2 ms.

- **Never `pollSendFrame` then drop the frame.** `pollSendFrame`
  dequeues from the send buffer and advances `sendOffset`; declining to
  send it afterwards (e.g. on short connection credit) punches a hole in
  the stream's byte sequence and corrupts the transfer. Budget the poll
  by `flow.sendCredit()` up front instead — budget 0 still lets a
  closing stream emit a zero-length FIN.

- **Window updates ride the control-priority tick, keyed off CONSUMED
  bytes.** `ReceiveBuffer.maybeExtendWindow()` re-grants a full window
  when `consumedOffset` (advanced only in `readBlocking` — bytes the
  bridge actually drained, not bytes merely received) crosses the
  half-window mark. This bounds buffering and gives correct
  backpressure: a slow downstream stops the bridge reading, which stops
  credit extension. Connection-level `MAX_DATA` uses a coarser
  received-bytes heuristic (nothing tracked consumed bytes there). The
  peer is bounded by the MIN of the connection and per-stream windows,
  so BOTH must be extended or a download stalls once one fills.

- **Key updates (RFC 9001 §6) must be followed.** quic-go rotates
  1-RTT keys mid-connection by flipping the short-header Key Phase
  bit. `processShortPacket` compares that bit to `CryptoSpace.keyPhase`;
  on a flip it trial-decrypts with `nextReceiveProtection()` (next
  generation via `HKDF-Expand-Label(secret, "quic ku")`, HP keys
  unchanged) and on success `commitKeyUpdate()` advances BOTH
  directions and flips the phase. Without this, every packet after the
  peer's update silently fails AEAD and is dropped — the connection
  goes half-dead (build 92: `recv_pn` frozen, `decrypt_fails` climbing,
  `datagrams` flooding with un-decryptable retransmits). The handshake
  and pre-update data path are untouched because the new code is inert
  until the phase bit actually differs.

- **No `BigInteger.TWO` — use the `BI_TWO` polyfill in `TlsCrypto`.**
  `BigInteger.TWO` is Java 9 / Android API 31+ only. The hand-rolled
  X25519 Montgomery ladder uses it once in `scalarMult` for the Fermat
  modular inverse `z^(p-2) mod p`; on every Android < 11 device (e.g.
  Xiaomi Redmi Note 5 on API 28) accessing it throws
  `NoSuchFieldError: No field TWO of type Ljava/math/BigInteger;` and
  the QUIC handshake fails every single time — supervisor loops dial
  failures forever (build 100). A private `BI_TWO = BigInteger.valueOf(2L)`
  constant fixes it. Do not "tidy" this back to the JDK constant. Same
  rule applies if anyone later adds another `BigInteger.TWO` site —
  reuse `BI_TWO` (or audit `minSdk` before adding the JDK reference).

- **Per-packet wire logs are gated behind `Connection.verboseWire`
  (default off).** At line rate the per-send / per-ACK / per-STREAM
  logs fire ~10k×/s, flooding logcat and throttling the sender thread.
  The 5-second `stats:` line is the always-on diagnostic (mirrored into
  the exportable agent log via `Connection.externalStatLog`); flip
  `verboseWire` on only for deep wire debugging.

- **Loss recovery: retransmit frames VERBATIM, and keep the PTO.**
  Two bugs here stalled every transfer right after the handshake (build
  91: `stream_frames` frozen, `cc.in_flight` never draining):
  1. *Retransmit must preserve stream offsets.* A lost STREAM frame is
     re-queued onto `pendingRetransmit` and re-emitted exactly as sent.
     Do NOT push its bytes back through `SendBuffer` — `pollSendFrame`
     stamps a fresh `offset = sendOffset` (already advanced past it),
     punching a hole in the byte stream so the receiver waits forever.
  2. *PTO is mandatory.* Packet-threshold loss detection needs 3 *later*
     packets ACKed, so a packet lost at the tail of a burst is never
     detected. The `senderLoop` PTO retransmits the oldest unacked
     ack-eliciting packet when no ACK has progressed within
     `max(3·sRTT, 250 ms) << backoff`, reset on ACK progress. Without
     it, one tail-lost packet kills the connection (and lost
     ClientHello/Finished caused the multi-retry handshake). Also
     `CryptoSpace.largestAckedSentPn` must be updated from incoming ACKs
     — it anchors the sent-PN encoding length (RFC 9000 §A.2).

- **RTT samples come from the largest-acked packet ONLY (RFC 9002 §5).**
  Sampling every packet in a batched ACK feeds seconds-old `sentTime`
  values into the EWMA — under heavy burst this drifted `smoothedRtt`
  to multiple seconds and ballooned `cc.congestionWindow` to 100+ MiB
  (build 93 stats). `Connection.dispatchFrames`' Ack handler passes
  `rttNanos>0` only for the largest acked packet; `BrutalCongestionControl.onPacketAcked`
  skips the EWMA update when `rttNanos<=0`. Don't sample per-packet.

- **Send-side stall self-heal (proxy-server-go MAX_DATA quirk).** After
  a heavy download, `flow.send_credit` can pin at 0 indefinitely:
  proxy-server-go (quic-go) extends MAX_DATA strictly off its own
  consume position, and once the test client stops draining the
  download payload the consume halts and our send window never reopens.
  Build-97 confirmed empirically — **224 DATA_BLOCKED frames over 3m40s
  produced zero MAX_DATA responses**. DATA_BLOCKED is correct per RFC
  9000 §19.12 (we still emit it, periodically, in case a future
  proxy reacts) but quic-go on the server side does not treat it as a
  trigger. So the connection layer self-heals: if `workRemains &&
  flow.sendCredit()<=0` persists for `stallTimeoutNanos` (5 s),
  `Connection.close()` tears down the QUIC connection and the supervisor
  redials a fresh one — the user's own observation was that fresh
  connections always work. Two correctness invariants make the close
  propagate:
  1. **`Connection.acceptStream` MUST be poll-based, not blocking
     `.take()`.** It checks `closed.get()` between 500 ms polls so a
     parked accept caller unblocks promptly; `NativeQuicTransport`
     turns the `null` into `IOException` so `quicAcceptLoop` exits and
     the supervisor reconnects. The old `incomingServerStreams.take()`
     wedged forever after a force-close (build 98 logged STALL TIMEOUT
     but the agent stayed "CONNECTED" because the accept caller was
     parked, the bridge threads were parked, and nothing surfaced the
     disconnect upward).
  2. **`Connection.close()` MUST signal EOF on every stream's
     `recvBuffer`.** Each `Stream.closeOnConnectionTermination()` flips
     `eofReached` and `signalAll`s the not-empty condition so bridge
     threads parked in `Stream.input.read` unblock, drop out of
     `copyStream`, and let the tunnels tear down cleanly. Without this
     they leak indefinitely.

  Diagnostic counters surfaced in the 5-second `stats:` line:
  `db_sent` (DATA_BLOCKED frames sent), `md_recv` (MAX_DATA frames
  received), `stall_reconnects` (times the self-heal fired). A run
  where `db_sent` climbs but `md_recv` stays flat is exactly the
  quic-go behavior described above.

- **Wi-Fi-return uplink binding must precede `socket.connect`.** When
  the user has wifi_return enabled, the in-house QUIC UDP uplink socket
  needs to live on the Wi-Fi network (uplink is free) while target
  dials use cellular (the public exit IP we sell). The kwik path gets
  this via its `socketFactory`; the native path takes an optional
  `uplinkSocketBinder: ((DatagramSocket) -> Unit)?` through
  `NativeQuicTransport.Factory` → `Connection` and invokes it inside
  `Connection.connect()` BEFORE `socket.connect(resolvedAddress)`.
  Android silently no-ops `Network.bindSocket()` once a socket has a
  peer, so the order matters. ProxyService passes a closure that reads
  `WifiReturnRelay.currentWifiNetwork()` at each call — so stall
  self-heal redials and mid-session Wi-Fi handovers pick up the
  current `Network` reference without manual wiring.

### NetworkProfile-driven tuning

The agent's TCP and QUIC stacks each have their own knobs — socket
buffers, bridge buffer, pacer rate, flow-control cadence — that
interact: fat buffers + slow pacer = bufferbloat; slim buffers +
fast pacer = under-utilization. Field operators can't be expected
to tune five constants per stack to fit their link, so all of
them are scaled together by a single preset
(`nativeagent/quic/NetworkProfile.kt`).

The numeric values below are the result of an in-apartment field
test on 2026-05-28 (server + agent + test client all in
Kremenchuk, agent on Wi-Fi, gigabit test client) that uncovered
the 1.5–4 MiB SO_*BUF safe zone on Android. **Before changing
any value in `NetworkProfile.tuning()`, read
[NETWORK_PROFILE_TUNING.md](NETWORK_PROFILE_TUNING.md) — it
documents every test run with raw numbers and the agent.log line
that confirmed the applied values, so the same regressions don't
get re-discovered.**

| Profile | Brutal CC | QUIC UDP buf | FC headroom | TCP SO_*BUF | TCP bridge buf |
| --- | --- | --- | --- | --- | --- |
| `LOW_100` (default) | 100 Mbps | 4 MiB | 0.75 | 1.5 MiB | 64 KiB |
| `MID_500` | 500 Mbps | 16 MiB | 0.60 | 2 MiB | 128 KiB |
| `HIGH_1000` | 1 Gbps | 32 MiB | 0.50 | 4 MiB | 256 KiB |

- **Brutal CC target** rates the QUIC pacer's bytes/second; the
  cwnd ceiling is `2 × BDP` at this rate.
- **QUIC UDP socket buffer** caps kernel-queue depth so the pacer
  is what bounds latency, not the queue. At LOW_100 the prior
  32 MiB buffer over 100 Mbps was up to 2.5 s of bufferbloat —
  the dominant cause of "buffers slow / not smooth" reports.
- **FC headroom ratio** is the fraction of `initialMaxData` left
  as buffer before a `MAX_DATA` refresh fires (see
  `ConnectionFlowControl.shouldAdvertiseMaxData`). Higher =
  refresh sooner = less HoL wait on the receive direction at the
  cost of more control-frame overhead.
- **TCP SO_RCVBUF / SO_SNDBUF** lives in a 1.5–4 MiB safe zone,
  bounded by two symmetric field-test regressions and a follow-up
  bisection:
    - *Above the ceiling*: 12 MiB at HIGH_1000 on Samsung S Fold 7
      (q7q, kernel 6.6.98) collapsed upload to ~5 Mbps. Setting
      `SO_SNDBUF` manually disables `tcp_wmem` auto-tuning, and a
      value above `net.core.wmem_max` (~4–8 MiB on Android)
      leaves the effective send window below where auto-tune
      would have grown it.
    - *Below the floor*: 1 MiB at LOW_100 on Pixel 9 Pro XL
      (komodo, kernel 6.1.145) reproduced exactly the same
      ~5 Mbps upload symptom from the other direction. Below
      `tcp_rmem` / `tcp_wmem` defaults the explicit value also
      seems to disable auto-tuning, and `tcp_adv_win_scale`
      overhead eats too much of the small buffer for window
      scaling to keep up under multi-flow load.
    - *Floor bisection*: 1.5 MiB on the same Pixel ran clean
      (331/299 Mbps, idle ping 84 ms) — so the lower edge of the
      safe zone sits between 1.0 and 1.5 MiB. We pin LOW_100 at
      1.5 to get the small bufferbloat improvement (~120 ms at
      saturation vs ~160 ms at 2 MiB) while staying safely above
      the regression threshold.

  So 4 MiB stays the ceiling (HIGH_1000, matches the pre-profile
  hardcoded value), 1.5 MiB the floor (LOW_100). The
  latency-vs-throughput tradeoff for TCP at the lower profiles
  lives mostly in the bridge buffer, with a smaller assist from
  SO_*BUF at LOW_100.
- **TCP bridge buffer** is the userspace bridge ByteBuffer in
  `Uplink.bridge()` / `Uplink.copyStream()`. Smaller = more
  frequent flushes = lower latency; larger = fewer syscalls per
  byte. This is the only TCP-side dimension that actually varies
  across profiles.

**Intentionally NOT scaled: QUIC `initialMaxData` (160 MiB) and
`initialMaxStreamData` (16 MiB).** Build-93 traced a 9-minute
upload stall to a 12-MiB connection-level MAX_DATA — quic-go on
the server side stopped refreshing once the window filled. The
160 MiB / 16 MiB values are production-validated and stay
constant across profiles. The pacer is the real rate limit, so
generous windows cost nothing.

**The preset wiring chain:**

1. UI Spinner writes `network_profile` to SharedPreferences `cfg`.
2. `MainActivity.buildAndSendStartIntent` reads it and puts it as
   an Intent extra (cross-process SharedPreferences caching makes
   in-memory reads in `:proxy` unreliable).
3. `ProxyService.onStartCommand` parses it via
   `NetworkProfile.fromPrefValue` into `@Volatile networkProfile`.
4. `runNativeEngine` passes it as `Config.networkProfile` AND as
   `NativeQuicTransport.Factory(networkProfile = …)`. The Config
   value drives TCP buffers via `cfg.networkProfile.tuning().tcp.*`
   at every socket open in `NativeProxyAgent`; the Factory value
   drives QUIC via `Connection(..., udpSocketBufBytes = ...,
   windowUpdateHeadroomRatio = ..., ccTargetMbps = ...)`.
5. `NativeProxyAgent.supervisorMain` logs the resolved profile and
   numeric values once per supervisor lifetime via
   `agent.logInfo("network profile", ...)` — the line lands in
   `agent.log` so the export shows both the user's choice and what
   actually got applied.

**Live-apply NOT supported.** QUIC transport parameters ride the
TLS handshake — they can't be renegotiated mid-connection — and
TCP socket buffer hints take effect only before `connect()`. So
the UI's "Save" persists the choice but takes effect on the next
manual stop/start. The Toast in `MainActivity` reflects this
(`"Saved — restart agent to apply"`).

**Binary engine ignores the preset.** `libproxyagent.so` reads
only env vars and the SDK has no env hook for the QUIC Brutal
target or buffers (BINARIES.md §2 lists all 16 keys, none
bandwidth-related). `runBinaryEngine` logs a `WARN: network_profile=
… ignored` line on every start when the preset differs from
default so the disparity surfaces in operators' log exports.

### NATIVE engine TCP fast path

The TCP bridge between a registrator-side data socket and the dialed
target socket is the agent's hot path — every byte of tunneled
traffic flows through it. To match the Go SDK's splice-based
zero-copy, the NATIVE engine bridges in three tiers, falling through
to the next if the previous can't be used:

1. **`splice(2)` via `libagentsplice.so`** (JNI shim, `app/src/main/cpp/`).
   Kernel-level zero-copy: bytes never enter userspace. Implementation
   uses a temporary pipe and two `splice()` syscalls per chunk — the
   standard Linux idiom because the kernel doesn't allow direct
   socket-to-socket splice. CPU cost at 50 Mbps drops to ~1-3% of one
   core, vs ~10-15% for the userspace paths below.

2. **NIO + DirectByteBuffer** (Kotlin `SocketChannel.read/write` with
   off-heap buffer). Two userspace copies per chunk (kernel → off-heap
   buffer → kernel) but no JVM heap allocation and no GC pressure.
   Used when splice can't be (see fd extraction below). At 50 Mbps
   costs ~10-15% of one core — acceptable for typical mobile uplinks
   where the radio is the limiter anyway. **Also the mandatory path on
   Android < 11 (API < 30):** `SpliceShim.ensureLoaded()` refuses to
   load `libagentsplice.so` there. Reason — on Xiaomi Redmi Note 5
   (Android 9, kernel 4.4.153) the JNI `SocketChannelImpl.fdVal`
   strategy returns the JVM NIO subsystem's own fd (not a dup'd one);
   modern Android's hidden-API blocklist would have forced PFD instead,
   but older Android still permits the read and kernel-4.4 splice can't
   share an fd with concurrent JVM reads. Field-observed symptom: native
   TCP collapsed to 7 Mbps with mid-run speedtest crashes while native
   QUIC on the same device (userspace tier #3) sustained 32 Mbps. Don't
   try to be cleverer here — the API-30 gate is a hard kill, not a
   per-strategy nuance, because the warmup probe (unconnected channel)
   misleadingly succeeds and we can't tell from inside the JVM whether
   the fd is actually shared with NIO until data corruption shows up.

3. **InputStream/OutputStream + byte[]** — only used as the QUIC
   bridge's target-side path, since kwik exposes streams not
   channels. Same number of userspace copies as #2 but with JVM
   heap byte[] (~6 MB/s allocated at 50 Mbps, GC pressure on long
   sessions).

#### Why splice needs a workaround for Android

`splice(2)` between two TCP sockets requires their POSIX int fds.
Java NIO doesn't expose those — `SocketChannel.socket().getFileDescriptor$()`
is hidden API, and modern Android (API 28+, hard block on API 30+
targets) blocks reflection/JNI access to it. The NATIVE engine ships
its own `SpliceShim` (see `nativeagent/SpliceShim.kt`) that tries
five strategies in order, returning the first that yields a working
int fd:

| ID | Strategy | Path | Hidden-API status |
| --- | --- | --- | --- |
| 0 | `ParcelFileDescriptor.fromSocket(socket).detachFd()` | Public API — framework calls hidden `getFileDescriptor$()` internally, where the hidden-API check uses the framework caller (allowed) instead of our app | **Public, always allowed** |
| 1 | `SocketChannelImpl.fdVal: int` | JNI `GetFieldID` direct read | Subject to hidden-API enforcement on API 30+ |
| 2 | `FileDescriptor.fd: int` | JNI `GetObjectField` → `GetIntField` | Same |
| 3 | `FileDescriptor.descriptor: int` (older AOSP libcore name) | Same as 2 | Same |
| 4 | `FileDescriptor.getInt$(): int` (Android `@hide` accessor) | JNI `GetMethodID` + `CallIntMethod` | Same |

**Order tried on the hot path**: 1 → 2 → 3 → 4 → 0. JNI strategies
go first because they only *read* fields (no side effects). PFD goes
last because `pfd.detachFd()` → `IoUtils.acquireRawFd()` calls
`fd.setInt$(-1)` on the SOURCE Socket's `FileDescriptor`, invalidating
the field for any concurrent caller — see the regression guard below.
PFD remains in the chain as a safety net: it's the only path that
survives a hypothetical future Android where even JNI field reads
of `FileDescriptor` are blocked.

Strategy IDs in the table are stable telemetry identifiers (the
`strategy=` field in the `splice: kernel zero-copy active` log line),
NOT the trial order — `winningStrategy` reflects whichever path
actually yielded the fd.

#### Pre-flight warmup (`SpliceShim.warmup()`)

`NativeProxyAgent.start()` calls `SpliceShim.warmup()` synchronously,
BEFORE spawning the supervisor thread. The warmup:

1. Loads `libagentsplice.so` (one-time `dlopen`, ~10-50 ms — biggest
   cold-start cost).
2. Opens a throwaway `SocketChannel`, attempts fd extraction via the
   strategies above to detect which one works on this device.
3. Caches the result (`winningStrategy`) and pre-emits the canonical
   `splice: kernel zero-copy active` or `splice: NIO fallback engaged`
   log line, so the widget badge refines from generic `TCP` to
   `TCP (splice)` / `TCP (NIO)` *before* the uplink dials.

Without warmup, every reconnect after a server-side restart or app
update would race a dozen-plus simultaneous OPEN-driven bridge
threads through the cold splice path (`System.loadLibrary` serialised
across them, strategy probing redone on each call). Adding ~10-50 ms
to `start()` is a fair trade for a clean first burst.

#### TCP socket tuning

Every TCP socket the NATIVE engine opens — control, data pool,
target dials — gets `SO_RCVBUF` and `SO_SNDBUF` set to
`cfg.networkProfile.tuning().tcp.socketBufferBytes` BEFORE
`connect()`. The bridge ByteBuffer in `Uplink.bridge()` /
`Uplink.copyStream()` uses
`cfg.networkProfile.tuning().tcp.bridgeBufferBytes` from the same
profile (`NetworkProfile.kt` table — see
[NetworkProfile-driven tuning](#networkprofile-driven-tuning) for
the per-profile values).

- **Why before connect**: on Linux the TCP window scaling is decided
  during the SYN handshake based on the receive buffer size at that
  moment. Setting it after connect is a no-op for the connection's
  effective window.
- **Why 1.5–4 MiB, not wider**: both extremes broke the upload
  direction in field tests (12 MiB on Samsung, 1 MiB on Pixel);
  a follow-up bisection found 1.5 MiB still clean. Inside the
  safe zone, all three profiles work duplex; the bridge buffer
  carries most of the TCP latency tradeoff and SO_*BUF differs
  meaningfully only at LOW_100 (1.5) vs HIGH_1000 (4). The 4 MiB
  upper anchor matches the pre-profile hardcoded value and works
  on every device we've measured. See the
  [NetworkProfile-driven tuning](#networkprofile-driven-tuning)
  section for the field-test details.

This was originally the root cause of an early NATIVE-engine bug
where upload throughput hit ~7 Mbps on high-RTT paths — Go's BINARY
engine happened to use socket sizes that triggered different
kernel auto-tuning behaviour. The explicit hint normalises
behaviour.

#### Native build

The JNI shim is built by AGP's CMake integration. Relevant files:

- `app/src/main/cpp/splice_shim.c` — the C source (`extractFd` +
  `spliceLoop` JNI functions). ~30 lines of business logic.
- `app/src/main/cpp/CMakeLists.txt` — minimal CMake project. C11,
  `-O2 -ffunction-sections`, `-Wl,--gc-sections` to keep the output
  ≤ 15 KiB per ABI.
- `app/build.gradle.kts` — `android.externalNativeBuild.cmake` block
  points at the CMakeLists; `defaultConfig.ndk.abiFilters` is
  `["arm64-v8a"]` to match the pre-built `libproxyagent.so`
  in `app/src/main/jniLibs/`. `ndkVersion = "26.3.11579264"`,
  `cmake.version = "3.22.1"` — both pinned and matched in
  `.github/workflows/build.yml` so CI uses the same toolchain.

If the user runs on an ABI without a matching `.so` (e.g. armv7
device on this build), `System.loadLibrary` fails, warmup logs
`splice: libagentsplice.so unavailable, NIO fallback only`, and
every tunnel goes through NIO. No crash, just lost optimisation.

#### Regression guards (read this before touching the bridge)

Three subtle invariants are load-bearing for the splice fast path.
All three were violated at various points in development and produced
the same observable symptom — fast download, broken upload — so they
share a guard section here. Reference: `48c22d0..fea8b2d`.

1. **NEVER set `SPLICE_F_MORE` on the destination `splice()` call**
   (`app/src/main/cpp/splice_shim.c`). `SPLICE_F_MORE` is documented
   as a TCP_CORK-style "more data coming" hint, but the kernel honors
   it across consecutive calls — every `splice()` we issue with MORE
   set re-arms the cork, and the cork releases only on a call WITHOUT
   the flag or after the 200 ms ceiling. For a continuously-flowing
   stream this caps the destination at `pipe_buf / 200 ms ≈ 320 KB/s`
   (≈ 2.5 Mbps), which is exactly what we observed before removal.
   Use `SPLICE_F_MOVE` and nothing else — matches Go's stdlib path
   in `runtime/internal/poll/splice_linux.go`. The MORE flag has NO
   effect on the source-side `splice()` (its `fd_out` is a pipe, not
   a socket), so adding it there is harmless but pointless.

2. **Latch arity is transport-specific — `(2)` for TCP, `(1)` for QUIC.**
   - `NativeProxyAgent.bridge()` (TCP) uses `CountDownLatch(2)`. Each
     `copyChannel` calls `shutdownOutput()` on completion, propagating
     FIN to the peer so the OTHER direction's read returns EOF
     naturally. Waiting for both gives a clean graceful close. Latch
     of 1 here cuts the still-draining direction down mid-flight when
     the quiet direction half-closes first. Matches Go's
     `Uplink.bridge` in `internal/netagent/uplink.go` which reads
     from `done` twice before letting `defer Close()` run.
   - `NativeProxyAgent.bridgeStreams()` (QUIC) uses `CountDownLatch(1)`.
     QUIC streams have no half-close that propagates across this
     bridge: closing kwik's `QuicStream` output does not unblock a
     paired read on the TCP target socket, and we can't half-close
     one direction of a QUIC stream without tearing the whole stream
     down. So we wait for ONE direction to finish, then the
     unconditional `sock.close() + output.close()` pair below force-
     terminates the other thread's blocking read. Matches Go's
     `pipeQUIC` (uplink.go:622-636) which does `<-done` once and
     relies on each goroutine's internal `Close` on the opposite
     side. Latch of 2 here hangs the bridge forever — neither direction
     receives a FIN equivalent, so the second `countDown` never fires.

3. **fd extraction MUST try JNI before PFD** (`SpliceShim.copy()` and
   `SpliceShim.warmup()`). `pfd.detachFd()` invalidates the source
   `FileDescriptor.fd → -1` via `IoUtils.acquireRawFd()`. The bridge
   spawns the two direction-copies in parallel, both call
   `SpliceShim.copy(src, dst)`, and they call `fdViaPfd()` on the
   SAME socket pair. Whichever thread loses the race sees an
   invalidated FD and falls back to NIO with `BRIDGE_BUFFER_BYTES`
   (256 KiB) — yielding the asymmetric "one direction at full splice
   speed, other at 3-4 Mbps over NIO" failure mode. Since JNI field
   reads are pure observers, trying them first lets both threads
   race safely; PFD's side effect is paid only when JNI is fully
   blocked (no Android version in production triggers this).

If you're touching `splice_shim.c` or the bridge in `NativeProxyAgent.kt`:
run a Speedtest in Multi-Connection mode (≥ 4 streams) BEFORE and
AFTER — single-stream tests can mask all three of these bugs because
the half-duplex symmetry hides corking, the latch race depends on
direction-specific FIN timing, and the fd race needs concurrent
bridge threads to trigger. The canonical regression check is
`upload ≥ 0.7 × download` on a same-city test path.

### Splice telemetry

Every splice event is logged through `NativeProxyAgent`'s log sink,
so it lands in `agent.log` alongside the rest of the native-engine
output. Each event fires at most once per process via
`AtomicBoolean.compareAndSet`, so the log isn't chatty:

| Log line | When |
| --- | --- |
| `splice: libagentsplice.so loaded` | First successful `System.loadLibrary`. |
| `splice: libagentsplice.so unavailable, NIO fallback only` | `loadLibrary` threw (ABI mismatch / stripped APK). |
| `splice: kernel zero-copy active` (with `strategy=...`, `via=warmup`) | Warmup probe succeeded — fd extraction works on this device. |
| `splice: NIO fallback engaged` (with `reason=...`) | All fd-extraction strategies refused; subsequent tunnels will use the NIO bridge. The `reason` field tells you exactly which step failed (`library_not_loaded`, `fd_extraction_failed_src:hidden_api_blocked`, `fd_extraction_failed_src:fd_field_not_found`, `fd_extraction_failed_src:fd_object_null`, `spliceLoop_threw:<ExceptionClass>`, `native_setup_failed`). |
| `splice: session summary` (on supervisor stop) | `strategy=<name|none>`, `tunnels_spliced=N`, `tunnels_fallback=N`, `bytes_spliced=N`, `mib_spliced=N.NN`. Counters are process-wide across agent restarts within the same `:proxy` lifetime — a feature for diagnosing uptime-level throughput without per-restart noise. |

`ProxyService.parseAgentLine` watches the `kernel zero-copy active`
and `NIO fallback engaged` lines and refines the widget transport
badge accordingly (see [`conn_info` schema](#conn_info-schema) field
6). The badge is sticky: once `TCP (splice)` is shown, a later
fallback log line doesn't downgrade it — the assumption being that
some splice activity is better than none.

### REBOOT path differences

Server-initiated REBOOT arrives as a `{"command":"REBOOT","reason":"..."}`
JSON line on the control channel in both engines. The UI auto-cycle
hook (`triggerAutoIpCycle`) is reachable from either.

- **BINARY**: REBOOT is detected by parsing the SDK's
  `"REBOOT received from registrator reason=..."` log line in
  `parseAgentLine`. The same line also drops out of the Go SDK's own
  log when it tears down the tunnel session for reconnect.
- **NATIVE**: `Uplink.controlReadLoop` parses the JSON, dispatches to
  `handleReboot(reason)`, which emits the *identical* log string into
  the `LogSink` (so `parseAgentLine` matches) AND fires the typed
  `RebootListener`, then tears down the transport so the supervisor
  reconnects. `ProxyService.runNativeEngine` wires only the LogSink
  path through to `triggerAutoIpCycle` and intentionally does **not**
  set a `RebootListener` — both paths would fire for one REBOOT, and
  although `triggerAutoIpCycle` is idempotent (`autoCycling` flag),
  the duplicate call adds a noisy "Auto IP-cycle already in progress"
  line to every rotation. The typed listener remains on
  `NativeProxyAgent`'s public API for third-party integrators who
  don't plumb a LogSink.

### Wi-Fi return engine gate

`maybeStartWifiRelay` requires the NATIVE in-process engine because
`ConnectivityManager.bindProcessToNetwork(cellular)` does not survive
`fork+exec` into the BINARY subprocess. The settings dialog
auto-disables `rbEngineBinary` when the Wi-Fi-return checkbox is on
and clamps `engine="native"` on save if the user somehow lands with
`wifi_return=true && engine=binary` (e.g. stale dialog state or
direct pref tampering). See the trade-off matrix below.

### Drop-in for third-party apps

The NATIVE engine ships as a small set of files designed to be
copy-pasted into another Android (or plain JVM) project. Three
tiers, pick what you want:

| Tier | Files to copy | What you get | Extra deps |
| --- | --- | --- | --- |
| **Minimum** | `nativeagent/NativeProxyAgent.kt` + `nativeagent/QuicTransport.kt` | TCP-only agent with NIO + DirectByteBuffer bridge. Works on any Android / JVM. | None — stdlib only. |
| **+ QUIC** | Above + `nativeagent/KwikQuicTransport.kt` | Adds QUIC uplink as an auto-negotiated fallback to TCP. | `tech.kwik:kwik:0.10.10` and core library desugaring for `java.time.Duration` (this app's `minSdk=23..25`; integrators targeting API 26+ can skip the desugaring). Wire `quicTransportFactory = KwikQuicTransport.Factory()`. |
| **+ splice zero-copy** | Above + `nativeagent/SpliceShim.kt` + `cpp/splice_shim.c` + `cpp/CMakeLists.txt` | TCP fast path becomes kernel `splice(2)` when supported (Linux, including Android). Userspace NIO is the automatic fallback. | AGP `externalNativeBuild` + NDK; see `app/build.gradle.kts` for the exact config. Adds ~15 KiB per ABI to the APK. |

Usage:

```kotlin
val agent = NativeProxyAgent()
agent.setLogSink { level, msg, fields ->
    Log.d("Agent", "$level $msg $fields")
}
agent.setRebootListener { reason ->
    // your own reaction — IP cycle, service restart, etc.
}
agent.start(NativeProxyAgent.Config(
    registratorHost = "registrator.example.com",
    registratorPort = 8443,
    agentKey = "your-key",
    workDir = filesDir,                                  // for transport cache
    quicTransportFactory = KwikQuicTransport.Factory(),  // optional
))
// ...
agent.stop()
```

Behaviour notes for integrators:

- Splice attempts loading `libagentsplice.so` lazily on first
  bridge call, or eagerly via `SpliceShim.warmup()` which we
  recommend calling once at start. Either way, if the .so isn't
  present (because you didn't bundle it, or no ABI match), the
  NATIVE engine silently uses the NIO bridge. No crash, no
  configuration needed.
- `SpliceShim.setLogger { level, msg, fields -> ... }` lets you
  route splice diagnostic lines into your own log infrastructure.
  Defaults to silent.
- `Config.dnsServers` (CSV: `"8.8.8.8,1.1.1.1"`) and
  `setDnsServers(...)` at runtime let you override DNS — useful
  when `/etc/resolv.conf` is unreachable from a sandboxed app, or
  when egress should go through a specific resolver. When empty,
  the JVM's default resolver is used.
- `setRebootListener` is the typed alternative to parsing the
  `"REBOOT received from registrator"` log line. Use whichever fits
  your IPC story; don't use both unless you handle idempotency
  yourself (the line is also emitted to the log sink).

## Connection modes

`rgMode` radio in the dialog (`rbModeModem`/`rbModeBalancer`). Pref
`mode` → Intent extra → `Mode` enum at `ProxyService.kt:439`. QR
import force-selects Modem (`MainActivity.kt:430, 438`).

- **Modem (direct):** env `registrator_host`, `registrator_port`,
  optional `agent_uuid` for BINARY (subprocess reads its environment).
  NATIVE receives the same fields as typed `Config` constructor args
  (`registratorHost`, `registratorPort`, `agentUuid`).
- **Balancer:** env `balancer_host`, `balancer_port`,
  `fallback_file_url` for BINARY. NATIVE takes the same as `Config`
  fields (`balancerHost`, `balancerPort`, `fallbackFileUrl`).

Fallback URL hard-coded across both engines:
`https://s3.eu-central-1.amazonaws.com/cactusneedles/registrators.json`.

## App → agent commands

There aren't any out-of-band channels for BINARY — the subprocess is
read-only from our side (we only call `readLine()` on its stdout,
never write to `outputStream`), and we don't run a local WebSocket
client to consume the SDK's `LocalBroadcaster` REBOOT relay either.
REBOOT is detected solely by parsing the binary's stdout (see
[BINARIES.md] §5).

The NATIVE engine adds typed in-process callbacks alongside the same
log-string interface — `setRebootListener` for REBOOT and
`setLogSink` for everything else (`ProxyService` consumes the log
sink and runs the lines through `parseAgentLine` so the UI behaviour
stays identical across engines).

Reconfig = stop-and-restart on both engines.

## IP rotation — `IpCycle.cycleAndVerify`

Cellular operators rarely hand out a new IP from a plain
`svc data disable/enable` — they hold the PDP context for tens of
seconds to minutes. `IpCycle.kt` drives a more aggressive sequence
that breaks PDP stickiness on most operators (those that don't pin
IP at the IMSI level — see "What doesn't work" below).

### Trigger points

- **Server-side `REBOOT`** log line from the SDK →
  `ProxyService.triggerAutoIpCycle(reason)`
  (`ProxyService.kt:284-358`). Subprocess stays alive; the SDK sees
  its WS read error during airplane-on and enters its own backoff
  loop. We deliberately don't kill it pre-emptively because that
  would race our own runner's backoff sleep. `autoCycling=true` is
  set, stages are pushed into `cycleStage` for the UI, and
  `forceReconnect` is called at the end to fast-track the new-IP
  attach.
- **Manual ↻ button** → `MainActivity.cycleMobileIp()`
  (`MainActivity.kt:769-819`). Stops the subprocess first (8s grace)
  and restarts after — no `autoCycling` flag needed since the
  watchdog thread is dead while the service is stopped. Stages are
  surfaced via `runOnUiThread { tvStatus.text = … }` directly from
  the log callback.

Both paths read `CycleConfig` via `IpCycle.loadConfigFromFile`, which
parses `filesDir/cycle_cfg.json` (written by `MainActivity` on every
settings save and on launch back-fill). The file exists specifically
because SharedPreferences are not multi-process safe — the REBOOT
auto-cycle runs in `:proxy` and `SharedPreferences("cfg")` there sees
a stale in-memory cache that doesn't reflect toggle changes made in
`:main`. Manual ↻ reads the same file too so both paths are
guaranteed to behave identically. `IpCycle.cycleAndVerify(context,
knownIp, log, config)` returns `CycleResult(oldIp, newIp, changed,
attempts, totalMs, reason)` — `reason` is one of `ok` /
`ok_no_baseline` / `ip_unchanged` / `no_toggle_method` /
`interrupted`.

### Algorithm

Total budget 180s base; ~50s extra per enabled fallback.

1. **Probe root** (`runRoot("true")`) — picks between `su`-driven
   shell commands and the `WRITE_SECURE_SETTINGS`-via-`Settings.Global`
   fallback. Root path is strictly more capable: it can also restart
   rild, flip RAT, and write to `telephony/carriers`.
2. **Skip-basic gate.** If `config.apnSwap || config.imeiRotation` is
   set, the basic ladder is skipped — the user explicitly enabled a
   heavy fallback, so we save ~75s by going straight to it.
3. **Basic 2-step nuclear ladder** —
   `LADDER = [Step(10s, ratSwitch=true), Step(60s, true)]`. Each step:
   - Save current `preferred_network_mode`, force GSM-only (mode 1).
     The modem re-attaches in 2G/3G first, which dislodges PDP
     contexts that an LTE-only re-attach can't shake.
   - `airplane_mode_on=1` + protected `ACTION_AIRPLANE_MODE_CHANGED`
     broadcast.
   - `setprop ctl.restart ril-daemon` to bounce the radio stack.
   - Sleep N seconds (10s, then 60s if first didn't change IP).
   - `airplane_mode_on=0`, wait up to 20s for `TRANSPORT_CELLULAR +
     NET_CAPABILITY_INTERNET`.
   - Restore original RAT, wait another ~15s for LTE re-acquisition.
   - Fetch public IP (`api.ipify.org`, fallback `icanhazip.com`).
   - If different from baseline → return success.
4. **APN swap fallback** (when `config.apnSwap=true`) —
   `IpCycle.runApnSwapStep`:
   - Read preferred APN with
     `content query --uri content://telephony/carriers/preferapn` —
     most ROMs (incl. Xiaomi MIUI) return the full joined row in one
     shot, so we get `_id`, `numeric`, `apn`, etc. without a second
     query.
   - Find a real alternate APN for the SAME `numeric` (MCC+MNC) on
     the SIM, or duplicate the current row with
     `name='ProxyAgent-rotation-tmp'`, copying a curated whitelist of
     fields (`apn`, `mcc`, `mnc`, `numeric`, `protocol`, `type`,
     `user`, `password`, `authtype`, `bearer`, `mvno_*`,
     `carrier_enabled`, etc.).
   - Swap `preferapn` → alternate, airplane cycle (10s), swap back,
     airplane cycle (5s), fetch IP. Two PDP context establishments
     with different APN paths usually break operator-side stickiness.
   - `finally` block always runs
     `content delete --where "name='ProxyAgent-rotation-tmp'"` so we
     never leave the duplicate behind.
   - **CRITICAL: `content query` calls against `carriers` MUST be
     filtered by `numeric`.** An unfiltered query returns the entire
     global APN database (10+ MB on most devices) which on a
     memory-pressed device overflows the 2 MB CursorWindow, allocates
     16+ MB of LOS objects in the app heap, and triggers the
     LowMemoryKiller — which then SIGKILLs the foreground app ~10s
     later (this was the original cause of the crash bug fixed in the
     `numeric`-filter refactor). `runRootOutput` also caps output at
     256 KB defensively.
5. **IMEI rotation fallback** (when `config.imeiRotation=true`) —
   `IpCycle.runImeiRotateStep`:
   - Resolves the command from `config.imeiMethod`:
     - `"custom"` → `config.imeiCustomCmd` verbatim
     - `"props"` → `resetprop -n -p ro.ril.imei0 <random15digits>`
       (needs MagiskHidePropsConfig; only an Android-API-level spoof —
       the modem still reports the real IMEI to the operator)
     - `"magisk-imei"` → `magisk-imei --random` (needs that module
       installed on PATH)
   - Runs via `su -c`, then airplane cycle (10s), fetch IP.
   - Effectiveness depends entirely on chipset (Qualcomm vs MediaTek
     vs Exynos all differ in what actually changes the modem-side
     IMEI). The app only invokes the command — it doesn't ship its
     own IMEI changer.

### Interrupted-cycle recovery

`cycleAndVerify` is wrapped in a `try/finally` that drops an
`ip_cycle_in_progress` marker file on entry and removes it in
`finally` (`IpCycle.kt:154-167`). `saveAndSetGsmOnly` writes the
pre-rotation `preferred_network_mode` to `ip_cycle_rat_saved`
*before* the GSM-only put; `restoreRat` deletes the file after the
restore put. These two breadcrumbs let a process kill mid-rotation
(PACKAGE_REPLACED app update, low-memory kill, force-stop, native
crash) leave just enough state on disk for the next `:main` launch
to clean up.

`IpCycle.recoverInterruptedCycle(context, log)` runs on a background
thread from `MainActivity.onCreate` (no UI delay, no preconditions —
it's idempotent and a no-op when no markers are present). When called:

1. If neither marker file exists → bail. Common case.
2. Probe root once via `runRoot("true")` — root is needed for the RAT
   restore branch, and `airplaneOff` works through both root and
   `WRITE_SECURE_SETTINGS` paths.
3. Read `Settings.Global.AIRPLANE_MODE_ON`. If it's `1`, call
   `airplaneOff(context, rootAvailable)` to put the device back online.
   Otherwise log "nothing to do" — the cycle may have died after
   airplane-off but before file cleanup.
4. If `ip_cycle_rat_saved` exists:
   - Validate it parses as a positive integer.
   - Compare against current `preferred_network_mode`. If they match
     (cycle died after restore but before file delete), just clean up.
   - If they differ and we have root, `settings put global
     preferred_network_mode <saved>` to restore it.
   - If we have **no** root, leave the file behind and skip the put —
     a future launch with root can still recover. Realistically the
     user lost root permanently between sessions only if Magisk was
     uninstalled, and they'll fix RAT through system Settings either
     way.
5. Delete both marker files.

What this does **not** recover:
- **APN swap mid-rotation.** `runApnSwapStep` has its own
  `try/finally` that always runs `cleanupRotationDuplicates` (removes
  any `name='ProxyAgent-rotation-tmp'` rows) and `setPreferredApn` to
  restore the original `_id`, but those run only if the process is
  still alive. A kill between `setPreferredApn(altApn.id)` and the
  restore leaves the SIM pointed at the alternate (or our duplicate)
  APN until the user changes APN through system Settings or re-runs
  the rotation. This is a known gap; persisting `preferapn` the way
  we now persist RAT would close it.
- **IMEI rotation.** By design — the user's custom command made a
  permanent identity change that we don't have an inverse for.
  Re-running the rotation is the only "recovery."
- **Subprocess kill in the BINARY engine.** Handled by Android's
  process group cleanup, not us.

### What doesn't work — and why

Operators that pin IP at the **IMSI** level (Lifecell UA is one
observed example) won't release the IP under any of these tricks —
no airplane toggle, no RAT switch, no APN swap, no Android-side IMEI
spoof, not even a full device reboot. The IMSI is read from the SIM
chip directly by the modem; only a physical SIM swap or a modem-side
IMSI change (which no Magisk module reliably does cross-chipset)
breaks the binding.

**Diagnostic test**: if `adb reboot` doesn't change the IP, this is
an IMSI/SIM bind and no software fix exists.

### UI surface during rotation

`cycleStage` (`conn_info` field 7) overrides the connection-status
badge with `ROTATING · <stage>` in orange. Stage strings come
straight from the `IpCycle` log callback — e.g. `attempt 1: airplane
on + restart ril + RAT→GSM, sleeping 10s`, `attempt 3 (APN swap):
preferapn 724 → 725 (life:) MMS/mms)`. The manual ↻ path writes the
same callback output to `tvStatus` directly via `runOnUiThread`.

## Wi-Fi return relay — `WifiReturnRelay`

Optional split-routing layer added when `cycle_cfg.json.wifi_return=true`,
the connection mode is Modem, **and the engine is NATIVE** (not BINARY,
because BINARY's subprocess can't inherit the process-wide cellular bind).
Lets the agent↔registrator uplink ride Wi-Fi while the agent→target dial
keeps using cellular — preserving the mobile exit IP that clients see at
the target, while removing the uplink relay traffic from the mobile data
bill.

### Why process binding is required (and why NATIVE only)

The original v1 design relied on Android's default routing for "target
dials should use cellular", with explicit `wifiNet.bindSocket` only for
the relay's upstream. **This is broken**: on a dual-transport device,
Android's default route is Wi-Fi (priority Wi-Fi > Ethernet > Cellular).
So SDK target dials — which never call `bindSocket` — leaked through
Wi-Fi, exposing the Wi-Fi public IP to targets instead of the mobile
exit IP. Catastrophic for anti-fraud.

The fix is to force the process default to cellular via
`ConnectivityManager.bindProcessToNetwork(cellularNet)` before starting
the relay. After that:

- All new sockets in `:proxy` default to cellular (target dials → cell).
- The relay's outbound upstream sockets override per-socket via
  `wifiNet.bindSocket(socket)` (uplink → Wi-Fi).
- Loopback sockets (SDK → 127.0.0.1 → relay) don't egress the device
  and ignore network binding entirely.

`bindProcessToNetwork` only affects the current Linux process —
**subprocesses started via `ProcessBuilder.start()` do NOT inherit it**
(Android-level state, not propagated through `fork+exec`). That's why
BINARY engine cannot safely use Wi-Fi return: its subprocess would
always egress through Wi-Fi for target dials. UI auto-disables the
BINARY radio when the user ticks the Wi-Fi-return box (and selects
NATIVE), the save handler clamps `engine="native"` if the user
somehow saves with `engine="binary" && wifi_return=true`, and
`maybeStartWifiRelay` has a final guard that bails out if it ever
sees `engine == BINARY && wifi_return`.

### Shape of the relay

The agent has two distinct connection types and each must ride a
different transport:

1. **Uplink** (agent ↔ registrator): a control TCP/QUIC socket plus a
   pool of data sockets. *All* tunneled client bytes traverse this, in
   both directions — and this is the leg we want on Wi-Fi.
2. **Outbound dial** (agent → target): the actual TCP connection to the
   end host. *This is the one that must use cellular* — the IP the
   target observes is the local-bound IP of that socket.

We solve it with a process-wide `bindProcessToNetwork(cellular)` (so
all unmarked sockets — target dials included — default to cellular)
plus a loopback relay in front of the uplink that overrides
per-socket via `wifiNet.bindSocket` to push *only* the uplink onto
Wi-Fi. Shape:

```
SDK ── connect(127.0.0.1:<localPort>) ──► WifiReturnRelay
                                              │
                                              ├─► dial(realRegistrator)
                                              │   socket bound to Wi-Fi
                                              │   when Wi-Fi is up
                                              │   (else default route)
                                              │
                                              └─► io.Copy in both directions
```

A pure per-socket `Network.bindSocket(fd)` approach would have been
nicer for the SDK target dials too, but that API is JVM-only — the
BINARY engine's Go subprocess is a plain Linux ELF with no JNI and
can't reach it. The loopback-relay-plus-process-bind combo works for
NATIVE in-process and at least lets BINARY enjoy uplink savings
(at the cost of leaking Wi-Fi IP to targets — see the trade-off
matrix below).

### Lifecycle

- **Construction**: `maybeStartWifiRelay(host, port)` in `ProxyService`
  reads `cycle_cfg.json` and returns either `(realHost, realPort)`
  (relay disabled) or `("127.0.0.1", localPort)` (relay up). Called
  once per engine launch from `runNativeEngine` / `runBinaryEngine`
  before config/env is composed. Gating order:
  1. `mode == Modem` (Balancer not supported).
  2. `cfg.wifi_return && cfg.wifi_return_method == "local_relay"`.
  3. If `engine == NATIVE` →
     `bindProcessToCellularBlocking()` requests
     `TRANSPORT_CELLULAR + INTERNET`, awaits with a 10s
     `CountDownLatch`, calls `cm.bindProcessToNetwork(cellular)` on
     success and keeps the NetworkCallback alive for re-bind on
     cellular reattach. On timeout / failure → bails (no relay) so
     target dials don't silently leak.
  4. If `engine == BINARY` → skip the process-bind step (it's
     meaningless for a subprocess) and start the relay anyway for
     uplink savings — the self-test will surface the leak as
     `wifiReturnStatus = "leak_known"`.
  5. Start the relay's `accept()` listener.
- **Per-session**: a single `accept()` thread spawns two daemon pipe
  threads per accepted connection. Each session captures the current
  `wifiNet` at dial time — a later network change doesn't disturb the
  existing socket (TCP can't be moved between interfaces). New sessions
  pick up the new `wifiNet`.
- **Wi-Fi acquisition**: `requestNetwork` with
  `TRANSPORT_WIFI + NET_CAPABILITY_INTERNET + NET_CAPABILITY_VALIDATED`.
  `VALIDATED` skips captive-portal Wi-Fi that would silently funnel
  uplink into a dead network. `onAvailable` / `onLost` /
  `onCapabilitiesChanged` keep `wifiNet` in sync.
- **DNS**: resolved through `wifiNet.getAllByName(host)` when a Wi-Fi
  network is held, otherwise plain `InetAddress.getAllByName`. Stops
  the relay from accidentally leaking lookups to the cellular resolver
  while the socket itself is bound to Wi-Fi (manifests as "host
  unknown" on some captive Wi-Fi).
- **Teardown**: `stopWifiRelayIfRunning` in `doStop` closes the
  `ServerSocket` (kills `accept`), unregisters the relay's Wi-Fi
  callback, unregisters the cellular callback registered for process
  binding, calls `bindProcessToNetwork(null)` to restore default
  routing, and lets in-flight pipe threads drain naturally on EOF.
  Stopping the NATIVE agent releases its sockets cleanly; on BINARY
  the subprocess teardown in `doStop` makes the SDK side hang up.

### Cellular network lifecycle — `bindProcessToCellularBlocking`

Registered via `cm.requestNetwork(TRANSPORT_CELLULAR + INTERNET, cb)`.
Two events drive its behaviour:

- **`onAvailable(network)`** — saves the Network in `cellularNet` and
  calls `cm.bindProcessToNetwork(network)`. Fires once at startup (the
  blocking call's `CountDownLatch` unblocks here) and again on every
  reattach — e.g. after an IP-rotation airplane cycle, the OS hands us
  a fresh Network object with the new cellular interface, and we
  re-bind to it automatically.
- **`onLost(network)`** — deliberately does NOT call
  `bindProcessToNetwork(null)`. Leaving the process bound to a dead
  Network makes new sockets fail with `ENETUNREACH`, which is the
  correct behaviour: we'd rather have SDK target dials fail loudly
  during a cellular outage than silently leak Wi-Fi IPs to targets.
  When cellular reattaches, `onAvailable` re-binds to the fresh
  Network and dials resume.

This pattern is what makes the relay safe across IP rotations: airplane
mode kills cellular, target dials fail (correct), airplane off brings
cellular back, `onAvailable` re-binds, dials resume — all without ever
silently dropping to Wi-Fi.

### Fallback behaviour

When `wifiNet == null` (Wi-Fi gone, validation pending, captive
portal), the relay still accepts the local connection and dials the
upstream **without** `bindSocket` — the resulting socket rides the
default route, which is cellular. The agent stays up; bandwidth
savings stop until Wi-Fi recovers. We deliberately don't kill existing
sessions on `onLost` — TCP can't be moved between interfaces anyway,
and re-establishing on every Wi-Fi flap would just thrash the
registrator's connection counter.

### Engine-vs-Wi-Fi-return trade-off matrix (current SDK)

| Engine | Uplink (relay → registrator) | Target dials (SDK → target) | Verdict |
|---|---|---|---|
| **NATIVE + wifi_return** (default) | Wi-Fi (relay binds) | Cellular (process bind inherited) | Works correctly — native agent owns all socket creation in `:proxy`, so `bindProcessToNetwork(cellular)` sticks for target dials. Default for new installs. |
| NATIVE (no wifi_return) | n/a | Cellular (single default route when no Wi-Fi held, else inherits default — usually Wi-Fi) | Baseline native path. No mobile-data savings; no IP leak as long as `wifi_return=false`. |
| BINARY + wifi_return | Wi-Fi (via relay's explicit `bindSocket`) | **Wi-Fi (default route)** — subprocess doesn't inherit `bindProcessToNetwork(cellular)` | **Leaks Wi-Fi IP** to targets. Self-test reports `LEAK_DETECTED`; widget shows `leak_known` (amber); relay stays running for uplink mobile-data savings. UI nudges users toward NATIVE. |
| BINARY (no wifi_return) | n/a | Cellular (single default route) | Legacy modem path. No mobile-data savings; no IP leak. |

`maybeStartWifiRelay` policy:
- `engine == BINARY` → relay still starts (for uplink savings) but we skip `bindProcessToCellularBlocking` (process bind is meaningless when the consumer is a subprocess). The self-test's `LEAK_DETECTED` verdict translates to `wifiReturnStatus = "leak_known"`, not a hard disable.
- `engine == NATIVE` → `bindProcessToCellularBlocking` must succeed before the relay starts. If it can't acquire cellular within 10s, we bail. The agent runs in-process so the bind sticks; NATIVE is the default and recommended path.

### Split-routing self-test — `SplitRoutingSelfTest`

After the relay starts (and every time Wi-Fi changes via a dedicated
NetworkCallback in ProxyService), we run a hard verification that the
OS is actually segregating the two transports — otherwise the relay's
`bindSocket(wifiNet)` calls would silently fail to split traffic and
the target would see the Wi-Fi public IP instead of the mobile exit IP.

Test procedure (`SplitRoutingSelfTest.runTest`, overall budget 18s):

1. **Wi-Fi probe**: `requestNetwork(TRANSPORT_WIFI + INTERNET)`, await
   onAvailable (≤6s). Call `wifiNet.openConnection("https://api.ipify.org")`
   and read the public IP.
2. **Cellular probe**: same via `TRANSPORT_CELLULAR`.
3. **Default-route probe**: plain `URL.openConnection()` with no
   `Network` binding. This is what an unprotected outbound dial — SDK
   target dial, NAT IP fetch — actually uses. With process bound to
   cellular this equals the cellular IP; without binding (or in a
   BINARY subprocess that doesn't inherit) it equals the default
   route IP, typically Wi-Fi.

Verdict:
- `wifi != cell && default == cell` → `SUCCESS`. Split routing
  confirmed AND target dials really go through cellular.
- `wifi != cell && default == wifi` → `LEAK_DETECTED`. The OS splits
  transports but the process isn't routing target dials through
  cellular — clients would see Wi-Fi IP. Relay must be disabled
  (same treatment as SAME_IP).
- `wifi != cell && default mismatched both` → `LEAK_DETECTED` as well
  (safer default — something unusual is going on, don't trust it).
- `wifi != cell && default probe failed` → `SUCCESS` (can't verify
  leak; next retest will catch it).
- `wifi == cell` → `SAME_IP`. OS suppresses one transport entirely.
- Either WIFI/CELL probe times out → `WIFI_PROBE_FAILED` /
  `CELL_PROBE_FAILED`. Keep the relay running; retest fires on next
  network change.
- Both timeout — context-dependent:
  - On the **initial** test (`initialSelfTestDone=false`) → treat
    as verification failure and disable the relay. Both probes
    failing at startup almost always means the system rejected our
    `requestNetwork` calls — historically the cause was missing
    `CHANGE_NETWORK_STATE` in the manifest (fixed in 1.0.56), but
    could also be a hostile ROM that throttles transport requests.
    Either way, running blind would leak traffic — better to fail.
  - On **subsequent** retests → keep the relay; transient probe
    outage doesn't invalidate the initial verification.

Result is persisted to `wifi_info.json` along with a
`WifiInfoProbe.snapshot` of the Wi-Fi link (speed, frequency, band,
standard — no SSID, so no `ACCESS_FINE_LOCATION` needed).

### On SAME_IP / LEAK_DETECTED failure — relay rollback

Both verdicts mean "do not let traffic flow through this relay" — the
user explicitly chose "fail loud" over "claim to work when it
doesn't". `LEAK_DETECTED` is treated identically to `SAME_IP`
(originally introduced for the case OS suppresses transports; now also
catches the bindProcessToNetwork-doesn't-cover-subprocess case for any
future engine experiments). Behaviour:

- `wifiReturnSplitFailed` flag set (sticky until next service start).
- `wifiReturnStatus = "split_failed"` written to `conn_info` field 8;
  the status updater preserves this (doesn't clobber to `""`).
- `stopWifiRelayIfRunning` closes the ServerSocket and unregisters
  network callbacks.
- **NATIVE engine**: in-place rollback. `effectiveHost`/`effectivePort`
  flip back to the real upstream, `nativeAgent.stop()` makes the
  in-process supervisor return, and the outer respawn loop in
  `runNativeEngine` picks up the new effective host/port on its next
  iteration. No service restart needed.
- **BINARY engine**: in-place rollback. `effectiveHost`/`effectivePort`
  (declared as `var` for this purpose) flip back to the real upstream;
  `agentProcess.destroy()` trips the runner's readLine EOF, which
  loops back to `ProcessBuilder` with the updated env. Subprocess
  reconnects directly to the registrator, no service restart needed.

The flag clears on next `doStop` / new service start — a fresh test
runs on the new session.

### Re-test triggers

A second `NetworkCallback` (`wifiReturnRetestCallback`) is registered
in ProxyService for `TRANSPORT_WIFI + INTERNET` requests. On
`onAvailable` of a new Wi-Fi network (debounced 2s — Android often
fires multiple times in quick handover), the self-test reruns after a
3s settle delay. `selfTestInFlight` guards against stacking when
events fire while a previous test is still running.

This callback is separate from the one in `WifiReturnRelay` itself:
the relay's callback drives socket binding (`onAvailable` →
`wifiNet = network`), while this one drives re-verification. Both
are unregistered in `stopWifiRelayIfRunning`.

### Interaction with IP rotation

Default Android airplane-mode behaviour kills **both** Wi-Fi and
cellular, which means every REBOOT auto-cycle would naturally fire a
`wifiReturnRetestCallback.onAvailable` when Wi-Fi reattaches at the
end of the cycle. Per-rotation retests are wasteful:

- The split-routing property hasn't changed — same physical interfaces,
  same OS behaviour. Only the cellular public IP has changed (that's
  the rotation's whole purpose).
- Each retest burns ~6s and one cellular HTTP request to ipify.
- On ROMs where Wi-Fi reattaches before cellular settles, the
  cellular probe fails mid-rotation and we'd cache a misleading
  `CELL_PROBE_FAILED` result.

Two guards keep this clean:

1. **Suppress retests while `autoCycling=true`.** Both
   `wifiReturnRetestCallback.onAvailable` and `runWifiReturnSelfTestNow`
   early-return when the flag is set. The auto-cycle thread holds
   `autoCycling=true` from the moment `triggerAutoIpCycle` starts
   until the `finally` block clears it.
2. **Schedule one deliberate post-rotation test.** In the
   `triggerAutoIpCycle` `finally` block, after `autoCycling=false`,
   `schedulePostRotationSelfTest()` queues a single self-test 5s
   later. That refreshes the cached cellular IP in `wifi_info.json`
   so the widget shows the new exit IP. Skipped when
   `wifiReturnSplitFailed` is true (relay already disabled — no point
   re-probing) or `stopRequested` (service is going away).

The relay object itself is **not** recreated by rotation. ServerSocket
stays open, `wifiNet` inside the relay updates via its own
NetworkCallback when Wi-Fi reattaches, and the SDK reconnects through
the existing loopback port automatically. The only state that has to
be refreshed externally is the cached IP pair in `wifi_info.json`,
which is exactly what the single post-rotation test does.

Manual `↻` rotation (`MainActivity.cycleMobileIp`) doesn't need this
treatment — it stops the service entirely, runs the cycle, then
starts the service again, so the whole `wifi_return` lifecycle goes
through `onStartCommand` and gets a fresh initial self-test for free.

### UI surfaces

- **Status widget (page 0)** — two TextViews:
  - `tvUplinkVia` (primary line): shows current state with link
    characteristics from `wifi_info.json`:
    - `wifi` (cyan): `↺ uplink: Wi-Fi · 433 Mbps · 5 GHz · Wi-Fi 5`
    - `wifi_fallback` (amber): `↺ uplink via cellular · Wi-Fi return
      enabled but no Wi-Fi held`
    - `split_failed` (red): `✗ Wi-Fi return DISABLED · split routing
      not confirmed`
    - `""` (relay off): GONE.
  - `tvUplinkDetail` (two-line IP block): shown only when
    `wifi_info.json` has either IP. Format:
    ```
      ↓ exit:   203.0.113.10 (cellular)
      ↑ uplink: 198.51.100.20 (Wi-Fi)
    ```
    On `split_failed` an extra warning line appears beneath:
    `  ⚠ both IPs equal — OS suppresses cellular while Wi-Fi up`.
- **Log export header** (`MainActivity.buildDeviceInfoHeader`):
  - `Wi-Fi-Return: <state>` in `[CONNECTION STATE]` section
    (`disabled` / `enabled (uplink via Wi-Fi)` / `enabled (fallback to
    cellular — no Wi-Fi held)` / `enabled (proxy not running)`).
  - Full `[WI-FI RETURN]` section when `wifi_info.json` exists:
    cellular & Wi-Fi public IPs, link speed, frequency, band,
    standard, split-routing verdict, self-test timestamp.

### Widget registrator display

The SDK logs whatever it actually dials, which when the Wi-Fi return
relay is active is `127.0.0.1:<localPort>` — true on the wire, useless
in the UI. `ProxyService.applyCurrentRegistrator` is the single setter
for `currentRegistrator` (conn_info field 3) and rewrites any value
starting with `127.0.0.1:` / `localhost:` / `[::1]:` / `::1:` to
`originalHost:originalPort` when the relay is active. All four parse
branches (`wsUrlRe`, `regSelectedRe`, `directRegRe`, `endpointRe`)
funnel through it. Clear-to-empty assignments in reconnect signals
stay as direct `currentRegistrator = ""` because the setter is a
no-op on empty values.

### Hard gate — refuseDueToCachedSplitFail

The checkbox in the settings dialog won't stay on if the device can't
actually split traffic. Three rejection paths in `MainActivity`:

1. **Cached fail**. Before running preflight,
   `refuseDueToCachedSplitFail` reads `wifi_info.json` and rejects the
   tick if `test_result=SAME_IP` within the last 24h. Dialog explains
   the cause and links to instructions; checkbox flips off
   immediately so a double-tap can't race.
2. **Preflight `BLOCKED`, no auto-fix**. The dialog offers only "Show
   instructions" / "Ignore"; both uncheck the box and surface a Toast
   explaining why.
3. **Preflight `BLOCKED`, auto-fix tried but failed**. ROM rejected
   `Settings.Global.putInt` despite WRITE_SECURE_SETTINGS being
   granted (some MIUI / EMUI builds). Box flips off + instructions
   open.

Only path that keeps the checkbox on: successful `tryEnable()` with a
read-back of `mobile_data_always_on=1`. Preflight `SUPPORTED` /
`UNKNOWN` results don't touch the checkbox (UNKNOWN includes Wi-Fi-only
tablets where the user may legitimately want to pre-configure the
toggle for a future SIM insertion).

The 24h freshness window on the cached fail check lets the user
re-test after fixing the system setting without a manual override:
beyond 24h we run preflight again rather than holding a permanent
grudge.

### Preflight — `MobileDataAlwaysOnCheck`

When the user ticks `cbWifiReturn` in the settings dialog,
`MainActivity.runMobileDataAlwaysOnPreflight` fires an async probe
(`Thread`, ~5s budget) that determines whether the device will keep
cellular attached alongside Wi-Fi. Without that, the relay is
basically useless: every time Wi-Fi connects, the OS would release the
cellular Network, and the relay's "Wi-Fi for uplink, cellular for
target dial" promise breaks.

Probe logic (`MobileDataAlwaysOnCheck.check`):

1. No cellular hardware (`TelephonyManager.phoneType == PHONE_TYPE_NONE`)
   → `UNKNOWN`. Silent — Wi-Fi-only tablets don't need a warning.
2. Read `Settings.Global.mobile_data_always_on`:
   - `1` → `SUPPORTED`. Silent.
   - `0` → `BLOCKED`. Dialog fires.
   - missing/exception → fall through to active probe.
3. Active probe: `requestNetwork(TRANSPORT_CELLULAR + INTERNET)` with
   a 5s `CountDownLatch`. `onAvailable` within budget → `SUPPORTED`;
   timeout → `BLOCKED`; systemic failure → `UNKNOWN`.

`canAutoFix` is true when `WRITE_SECURE_SETTINGS` was granted (same
permission already used for IP-rotation airplane toggle). When true,
the dialog offers an `Enable` button that calls
`Settings.Global.putInt("mobile_data_always_on", 1)` and read-backs
the value. When false (or when the write didn't stick), the dialog
falls back to manual instructions: developer-options toggle,
`adb shell settings put global mobile_data_always_on 1`, or
`adb shell pm grant <pkg> WRITE_SECURE_SETTINGS` to unlock the auto-fix.

The user can dismiss the dialog and save with the box ticked anyway —
the warning is informational, not blocking. If cellular ends up
genuinely unavailable while Wi-Fi is up, the relay still works in
fallback mode (everything via Wi-Fi), it just doesn't give us the
mobile exit IP we wanted.

### What's NOT supported in this iteration

- **Balancer mode**: the SDK GETs a JSON descriptor from the balancer
  and then dials the chosen registrator directly, bypassing the env
  override. Only the balancer GET would go through the relay; the
  actual uplink wouldn't. UI disables the checkbox when `mode=balancer`
  and the save handler clamps `wifi_return=false` regardless of the
  in-memory checkbox state, so flipping the mode toggle in either
  direction lands in a sane place.
- **Traffic-stats split**: `TrafficStats.getUidRxBytes/TxBytes` lumps
  Wi-Fi and cellular together by UID. The status card's "↓ ↑" rates
  thus include both. Switch to `NetworkStatsManager` if per-transport
  accounting is needed later.

## Auto-stop watchdog (`ProxyService.kt:486-528`)

A 1-second poll loop driven by the same thread that updates traffic
stats and refreshes the notification. Two stop triggers:

- **Battery threshold** — if `battery_threshold` file holds a non-zero
  percent and the device drops to or below it,
  `doStop("Battery N% ≤ T% — auto-stopped")`. Active during cycles too
  (low battery is a valid stop reason no matter what we're doing).
- **No-internet grace** — 30 seconds. Resets to 0 the moment
  `systemSaysInternetUp()` returns true again. Stops with reason
  `No internet (Xs) — auto-stopped` if the grace expires.

**Suppressed while `autoCycling=true`** — the 60s basic nuclear sleep
intentionally has cellular down for the full duration, which would
otherwise trip the 30s grace and stop the agent right as it's about to
come back with a fresh IP. The suppression resets `noInternetSince=0L`
each tick so the timer doesn't accumulate during the cycle. Manual ↻
doesn't hit this because the activity stops the service before
cycling.

Both stop reasons land in `stop_reason` (file) and switch
`proxy_state` to `auto_stopped`. `MainActivity` surfaces the reason in
the status badge.

## Surviving an app update

`PACKAGE_REPLACED` is a hard kill from the OS — both `:main` and
`:proxy` are terminated before any `onDestroy` / `doStop` can run, so
sockets, uplink, JNI splice fds, the WakeLock, and the FGS
notification all disappear with the processes. There's no
Android-supported way to keep a session alive across a package
upgrade. What we **can** do is detect that we were killed and make
sure the post-update world doesn't lie about it.

### Three layers of defense

1. **Stale-state detection in the UI** — covered above
   ([`conn_info` heartbeat](#conn_info-schema) + [Status badge
   mapping](#status-badge-mapping-mainactivityrefresh)). The
   `conn_info` file from the killed process still says `CONNECTED |
   … | <old timestamp>` after the kill; without the heartbeat check,
   the new `MainActivity` would compute uptime from that old
   timestamp and accumulate forever. The 5 s staleness threshold
   wipes `conn_info` and live `proxy_state` values on the first
   `refresh()` so the UI snaps to `DISCONNECTED`. Terminal
   `proxy_state` values stay so `AUTO-STOPPED · Battery 5%` and
   `ERROR` don't get lost.

2. **IP-rotation recovery** — covered above ([Interrupted-cycle
   recovery](#interrupted-cycle-recovery)). Marker files
   (`ip_cycle_in_progress`, `ip_cycle_rat_saved`) survive the kill;
   `recoverInterruptedCycle` runs on a background thread from
   `MainActivity.onCreate` and flips `airplane_mode` back off + puts
   the saved RAT back. Without this, a kill during the nuclear
   ladder strands the device with no cellular path or pinned to 2G.

3. **Auto-restart of the proxy service** —
   `PackageReplacedReceiver` registered on
   `android.intent.action.MY_PACKAGE_REPLACED`
   (`AndroidManifest.xml:103-117`). When the broadcast fires, the
   receiver:

   - Checks for `filesDir/was_running`. The file is written by
     `ProxyService.onStartCommand` right after `startForeground` and
     **deleted by `doStop`**. Its presence post-kill is direct proof
     the previous session was alive when the update landed; absence
     means the user (or auto-stop) had already stopped the agent and
     we must not resurrect it.
   - Reads `host`/`port`/`key`/`id`/`dns`/`engine`/`mode` from
     `SharedPreferences("cfg")` — same keys `MainActivity.toggle()`
     uses. If any of host/port/key is missing (Storage wiped,
     first-run upgrade), bails with a log line.
   - Calls `startForegroundService` with an Intent matching the one
     the manual START button would build, so the new `:proxy`
     process boots into the same engine and mode.
   - Catches all throwables and only logs — a broadcast receiver
     that throws shows the user a "Process has died" dialog with no
     upside. The most likely throw is
     `ForegroundServiceStartNotAllowedException` if Android tightens
     the FGS-from-broadcast exemption further in a future release.
     On catch, the receiver posts a fallback notification
     (`NOTIF_ID_AUTO_RESTART_FAILED = 3`) with a `BigTextStyle`
     explanation and a tap-target into `MainActivity`. Otherwise the
     admin would see no visible signal that anything went wrong — the
     FGS notification just disappears.

   After auto-restart, `ProxyService.onStartCommand` runs its normal
   path: writes a fresh `proxy_state="starting"`, fresh `conn_info`
   with a current heartbeat (clearing any stale snapshot the UI
   might have read first), and the agent dials uplink. On
   `uplink connected`, `connectedSinceMs = System.currentTimeMillis()`
   — so uptime really does start at zero for the new session, not
   continue from the old one. The FGS notification (id=1) reappears
   within a few hundred ms of the `startForeground` call in
   `onStartCommand`; the ~1–3 s blank window between the OS killing
   the old process and the new process attaching its FGS is
   unavoidable Android behaviour.

### Notification IDs in the shade

| ID | Source | Lifecycle |
| --- | --- | --- |
| 1 | `ProxyService.startForeground` / 1Hz refresh in the status updater | Sticky / ongoing while `:proxy` is alive. Removed by `stopForeground(STOP_FOREGROUND_REMOVE)` in `doStop`. Dies with the process on `PACKAGE_REPLACED` kill; OS removes it from the shade. |
| 2 | `ProxyService.doStop` when `autoStopReason` is non-empty (battery / no-internet) | Non-sticky (`setOngoing(false)`, `setAutoCancel(true)`). Survives `PACKAGE_REPLACED` because it's not bound to the FGS lifecycle — the `PendingIntent` is immutable so the tap-into-MainActivity action still works after the update. |
| 3 | `PackageReplacedReceiver.postAutoRestartFailedNotification` when `startForegroundService` throws | Non-sticky, auto-cancel. Posted **only** when auto-restart fails (OEM block, Android FGS-restriction tightening). Channel `"proxy"` is created here too — it would otherwise only exist if `:proxy.onCreate` had already run, which it hasn't if the start just failed. |

### Failure modes worth knowing about

- **OEM auto-start blockers.** Xiaomi MIUI's "Autostart" toggle,
  Huawei EMUI's "Manage app launch", Samsung OneUI's "Sleeping
  apps", OnePlus OxygenOS battery optimization — any of these can
  drop our `MY_PACKAGE_REPLACED` broadcast. There's no
  app-side workaround; the per-OEM whitelist procedure is documented
  in `ADMIN_GUIDE.md`.
- **Android 14+ tightening.** `specialUse` FGS-type currently
  qualifies for the broadcast-receiver exemption that lets us call
  `startForegroundService` from `onReceive`. If Google revokes that
  for `specialUse` later, the receiver will start throwing and the
  user will need to open the app and press START manually. The
  user-visible behavior degrades gracefully — no crash, just no
  auto-restart.
- **Credentials wiped between sessions.** "Clear Storage" in system
  settings deletes `filesDir/*` including `was_running`, and clears
  `SharedPreferences`. The receiver bails cleanly; the next
  app-launch will land on `NOT CONFIGURED · TAP START TO IMPORT`
  because `hasConnectionConfig()` returns false.
- **In-flight IP rotation at the time of update.** The
  `ip_cycle_in_progress` marker survives the kill, and
  `recoverInterruptedCycle` flips `airplane_mode` and RAT back on the
  next `:main` launch (which is also when the auto-restart Receiver
  fires). `was_running` is still set, so the receiver kicks off a
  fresh `:proxy` while `:main` separately runs the cycle recovery —
  both threads share `filesDir` but touch disjoint files, no race.
- **APN swap interruption.** As called out in
  [Interrupted-cycle recovery](#interrupted-cycle-recovery), if the
  kill lands between `setPreferredApn(altApn.id)` and the restore
  put, the SIM stays on the alternate APN. The auto-restart will
  succeed (cellular works on whichever APN is selected) but the new
  session may exit through a different APN than intended.
- **Subprocess in BINARY engine.** Killed with the parent process by
  the OS; the new `:proxy` re-execs `libproxyagent.so` from the
  newly-installed APK's `nativeLibraryDir`. No special handling
  needed.

### Logs you'll see in `agent.log` after a successful auto-restart

```
HH:MM:SS === app v1.0.347 (build 347) pid=12345 ===
HH:MM:SS Engine: NATIVE  Mode: MODEM
HH:MM:SS Native engine: host=…
…
HH:MM:SS recovery: marker=true rat_file=false airplane_mode=false root=true
HH:MM:SS recovery: airplane_off ok            ← only if airplane was on
```

The `=== app v… ===` marker is one-shot per `:proxy` process; the
`recovery:` lines come from `IpCycle.recoverInterruptedCycle` via
`MainActivity`'s background thread. The receiver itself logs
through `android.util.Log` with tag `ProxyAgent.PkgReplaced` —
visible via `adb logcat` but not in `agent.log` (the receiver runs in
`:main` and doesn't share the log appender).
