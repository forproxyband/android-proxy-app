# App architecture

How `ProxyService` + `MainActivity` orchestrate the proxy-agent runtime
and expose state to the user. Three engines are supported (see
[Agent engines](#agent-engines) below): **NATIVE** (default, pure-Kotlin
port at `app/src/main/java/com/proxyagent/app/nativeagent/`), **BINARY**
(bundled `.so` subprocess — see [BINARIES.md]), and **AAR** (gomobile
in-process). The three speak the same wire protocol to the registrator
infrastructure; the choice trades off startup cost, subprocess
isolation, and Wi-Fi-return compatibility.

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
- Reconfig = stop-and-restart (`MainActivity.kt:374-379`). The Go
  runtime in the AAR engine caches env at `JNI_OnLoad` and can't be
  cleanly re-initialised in-process, which forces the same
  stop-and-restart contract on the binary and native engines for
  consistency. (The native engine could in principle be reconfigured
  in-place — `NativeProxyAgent.start(Config)` accepts a fresh config —
  but we keep the same contract so the UI layer doesn't have to
  fork per engine.)

## State files in `filesDir`

| File | Owner | Format | Purpose |
| --- | --- | --- | --- |
| `proxy_state` | service | text | One of `starting`/`running`/`stopped`/`auto_stopped`/`error`. Written on every transition. |
| `conn_info` | service | pipe-delimited | Connection state, traffic rates, current rotation stage. Written every 1s + on transitions. Schema below. |
| `stop_reason` | service | text | Human-readable reason; non-empty only when `proxy_state=auto_stopped`. |
| `agent.log` | service | text | Timestamped tail of binary stdout. Rotated 30 → 25 MiB on overflow. |
| `nat_ip` | service | text | Last-known public IP (best-effort, refreshed every 5 min and after successful rotation). |
| `battery_threshold` | UI | int | Auto-stop threshold in percent (0 disables). |
| `speed_units` | UI | text | `bits`/`bytes` for rate display. |
| `cycle_cfg.json` | UI writes, both read | JSON | Cross-process mirror of the cycle settings (`apn_swap`, `imei_rotate`, `imei_method`, `imei_cmd`). SharedPreferences are per-process — the REBOOT auto-cycle in `:proxy` would otherwise miss toggle changes made in `:main`. Rewritten on every settings save and re-mirrored on app launch as a back-fill. |
| `analytics/cycle_events.jsonl` | both write | JSONL | One row per rotation attempt with old/new IP, success flag, attempts and duration. Read by the swipe-panel chart, the analytics screen, and the CSV export. Pruned by the same retention policy as bucket files. |
| `wifi_info.json` | service | JSON | Latest Wi-Fi return split-routing self-test result + Wi-Fi link snapshot. Keys: `public_ip_wifi`, `public_ip_cell`, `public_ip_default` (process default route, used to detect target-dial leak), `link_speed_mbps`, `frequency_mhz`, `band` (`"2.4 GHz"`/`"5 GHz"`/`"6 GHz"`), `standard` (`"Wi-Fi 5 (802.11ac)"` etc.), `wifi_attached`, `test_result` (`SUCCESS`/`SAME_IP`/`LEAK_DETECTED`/`WIFI_PROBE_FAILED`/`CELL_PROBE_FAILED`/`BOTH_FAILED`), `test_detail`, `test_duration_ms`, `tested_at_ms`. Written by ProxyService after each self-test; read by MainActivity for the widget two-IP block and the log-export header. |

## `conn_info` schema

One pipe-delimited line written by `writeConnInfo`
(`ProxyService.kt:126-138`). Nine fields (0-indexed):

| # | Field | Type | Meaning |
| --- | --- | --- | --- |
| 0 | `connStatus.name` | enum string | `STARTING` / `CONNECTING` / `CONNECTED` / `RECONNECTING` / `ERROR` / `STOPPED` |
| 1 | `rxRate` | long | Bytes/sec downlink (`TrafficStats.getUidRxBytes`). |
| 2 | `txRate` | long | Bytes/sec uplink. |
| 3 | `currentRegistrator` | string | `host:port` of the selected registrator. |
| 4 | `activeTunnels` | int | Currently open tunnel count. |
| 5 | `connectedSinceMs` | long | Epoch ms of last successful AUTH; 0 when not CONNECTED. |
| 6 | `currentUplinkTransport` | string | One of: `QUIC` (all engines) / `TCP (splice)` (BINARY/AAR always, NATIVE when the kernel zero-copy shim activated) / `TCP (NIO)` (NATIVE when splice couldn't be used and the bridge fell back to NIO + DirectByteBuffer) / `TCP` (NATIVE momentary state between `uplink connected` and the first splice/fallback decision — usually invisible thanks to `SpliceShim.warmup()` resolving it before the supervisor dials) / `TCP+yamux` / `WebSocket` (legacy pre-2.0.14 SDKs). Added v2.0.14-quic; splice/NIO distinction added with the NATIVE engine. |
| 7 | `cycleStage` | string | Non-empty only during REBOOT auto-cycle. UI shows `ROTATING · <stage>`. Added with `IpCycle.cycleAndVerify` rework. |
| 8 | `wifiReturnStatus` | string | `""` (relay off) / `"wifi"` (uplink on Wi-Fi, split routing verified) / `"wifi_fallback"` (relay up, no Wi-Fi held — flowing through cellular) / `"leak_known"` (BINARY engine: relay running for uplink savings, but target dials leak Wi-Fi IP — expected on BINARY) / `"split_failed"` (sticky: self-test rejected the relay on an in-process engine, relay disabled). UI maps to cyan / amber / amber-warning / red respectively. Added with Wi-Fi return relay. |

`MainActivity.refresh()` polls every 3s, reads with `getOrNull(N)` so
forward-compat survives older downgrades that write fewer fields. `|`
characters inside `cycleStage` are escaped to `/` so a stray pipe in a
log line can't shift the field count.

## Status badge mapping (`MainActivity.refresh`)

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
| `engine` | string | `"native"` | `"native"` (in-process Kotlin port — default), `"binary"` (subprocess `.so`), or `"aar"` (in-process gomobile). See [Agent engines](#agent-engines). |
| `speed_bytes` | bool | false | Rate display unit. |
| `analytics_retention_days` | int | 30 | Older analytics buckets are pruned on app launch. |
| `apn_swap` | bool | false | Enable APN swap fallback during cycle (see IP rotation §). |
| `imei_rotate` | bool | false | Enable IMEI rotation fallback. |
| `imei_method` | string | `"custom"` | `"custom"` / `"props"` / `"magisk-imei"`. |
| `imei_cmd` | string | — | Root shell command when `imei_method="custom"`. |
| `wifi_return` | bool | false | Route the agent↔registrator uplink over Wi-Fi via a loopback relay; target dials still ride cellular. Modem mode only — auto-clamped to false on save when `mode="balancer"`. See "Wi-Fi return relay" below. |
| `wifi_return_method` | string | `"local_relay"` | Slot for future methods (SO_MARK, VpnService split tunnel). No UI yet — only `local_relay` is implemented; anything else falls back to direct dial with a log line. |

## Agent engines

Three runtimes for the proxy-agent client live side-by-side in the
APK, but **only NATIVE is production-viable**. BINARY and AAR are
kept short-term for testing/comparison and will be removed once
NATIVE has enough field hours. All three speak the same wire protocol
(TCP/QUIC uplink with the `TUNL` magic-header framing, JSON-line
control channel, 32-hex-byte per-stream tokens — see [BINARIES.md] §3
for the byte-level details) so the registrator infrastructure pairs
with any of them interchangeably. `engine` pref selects which one
`runX-Engine()` in `ProxyService.onStartCommand` dispatches to.

**Why only NATIVE works for the full feature set:**
- **BINARY** cannot support Wi-Fi return. `bindProcessToNetwork(cellular)`
  doesn't survive `fork+exec`, so SDK target dials in the subprocess
  always go through the default route (Wi-Fi on dual-transport
  devices) and leak the Wi-Fi public IP to targets. The self-test
  catches this as `LEAK_DETECTED`. Acceptable as a baseline modem
  client without Wi-Fi return, but the whole split-routing feature
  is off-limits.
- **AAR** has a deeper reliability issue: Modem mode in this SDK
  build doesn't even reach AUTH — log shows `no registrator
  available; backing off` despite a verified `libc env check`. Root
  cause is under investigation. Until that's resolved, AAR shouldn't
  be assumed to work end-to-end in any mode.
- **NATIVE** runs in `:proxy` so process bind sticks for target
  dials; has no Go runtime to wrestle with (vs AAR's `JNI_OnLoad`
  env caching, vs BINARY's subprocess pipes). Uses a tiny optional
  JNI shim (`libagentsplice.so`, ~15 KiB per ABI) for the TCP
  zero-copy fast path; the agent runs fine without it too. Can be
  dropped into third-party apps as a small Kotlin module — see
  [Drop-in for third-party apps](#drop-in-for-third-party-apps).

| Engine | Where it lives | Process | Notes |
| --- | --- | --- | --- |
| `NATIVE` (default) | `app/src/main/java/com/proxyagent/app/nativeagent/` + `app/src/main/cpp/` | In-process (`:proxy`) | Pure-Kotlin port of the Go SDK. No subprocess, no Go runtime. Optional ~15 KiB JNI shim for kernel `splice(2)` zero-copy on the TCP fast path — automatic NIO fallback if the .so isn't present or fd extraction fails. Drop-in reusable in third-party apps — see [Drop-in for third-party apps](#drop-in-for-third-party-apps) below. |
| `BINARY` | `proxy-agent-linux-{arm64,x86}` packed as `libproxyagent.so` (see [BINARIES.md]) | Subprocess via `ProcessBuilder` | Forked `:proxy` child runs the Go SDK as an unmanaged ELF; we parse its stdout. Wi-Fi return cannot bind it to cellular (`bindProcessToNetwork` doesn't survive `fork+exec`), so Wi-Fi-return target dials leak — UI shows `leak_known`. |
| `AAR` | `proxyagent.aar` (gomobile-built) at repo root | In-process (`:proxy`) | Loaded via `Class.forName("proxyagent.sdk.agent.Agent")` after `Os.setenv` so the Go runtime sees our config at `JNI_OnLoad`. Cannot be cleanly re-initialised in-process — service kills `:proxy` on stop so the next start gets a fresh Go runtime. **Modem-mode reliability issue currently observed** — see the trade-off matrix at [Engine-vs-Wi-Fi-return trade-off matrix](#engine-vs-wi-fi-return-trade-off-matrix-current-sdk). |

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

- `defaultStreamReceiveBufferSize(8 MB)` — initial per-stream window.
- `maxOpenPeerInitiatedBidirectionalStreams(1024)` — peer-initiated
  stream cap.
- `socketFactory` builds a `DatagramSocket` with `receiveBufferSize` /
  `sendBufferSize = 32 MB`. Android usually clamps to
  `net.core.rmem_max` (4–8 MiB); we request 32 MB and take whatever
  the OS gives.
- `keepAlive(20)` matches the Go config's `KeepAlivePeriod`.

Brutal congestion control is **not portable** — kwik exposes no CC
plugin point, so the QUIC path uses kwik's NewReno-based controller.
Server-side Brutal still operates if configured. On very lossy
uplinks expect lower upload throughput than the Go agent.

ALPN is `proxy-tunnel/1`, certificate validation is disabled
(`noServerCertificateCheck()` — same posture as the Go SDK's
`InsecureSkipVerify: true`; identity is verified by the AUTH key on
the control channel, not the cert chain).

When `Config.quicTransportFactory` is `null`, the QUIC half is
skipped entirely — the agent runs TCP-only and `chooseTransportOrder`
returns `["tcp"]`. This is the path third-party integrators who
don't want the kwik dependency take.

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
   where the radio is the limiter anyway.

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

Strategy 0 (`ParcelFileDescriptor`) is the supported path on Android
14+ — it works irrespective of `targetSdkVersion` or non-SDK
enforcement state because every call we make is to public API.
Strategies 1-4 are kept as fallbacks for older Android versions and
the unlikely case where PFD refuses (e.g. a SocketAdaptor that
doesn't expose its FD).

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
target dials — gets a 4 MiB hint applied to `SO_RCVBUF` and
`SO_SNDBUF` BEFORE `connect()`:

- **Why before connect**: on Linux the TCP window scaling is decided
  during the SYN handshake based on the receive buffer size at that
  moment. Setting it after connect is a no-op for the connection's
  effective window.
- **Why 4 MiB**: bandwidth × RTT defines max in-flight bytes. At
  RTT = 200 ms (cellular or trans-continental Wi-Fi path to a
  datacenter registrator), the Android default receive buffer of
  ~208 KiB caps a single TCP flow at ~8 Mbps. 4 MiB raises the cap
  to ~160 Mbps per flow.
- **Why ask for more than Android typically grants**: the kernel may
  clamp to `net.core.rmem_max` / `wmem_max` (often 4–8 MiB on modern
  ROMs). Asking for 4 MiB and accepting whatever the OS gives is
  cheaper than per-device tuning.

This was the root cause of an early NATIVE-engine bug where upload
throughput hit ~7 Mbps on high-RTT paths — Go's BINARY engine
happened to use socket sizes that triggered different kernel
auto-tuning behaviour. The explicit hint normalises behaviour.

#### Native build

The JNI shim is built by AGP's CMake integration. Relevant files:

- `app/src/main/cpp/splice_shim.c` — the C source (`extractFd` +
  `spliceLoop` JNI functions). ~30 lines of business logic.
- `app/src/main/cpp/CMakeLists.txt` — minimal CMake project. C11,
  `-O2 -ffunction-sections`, `-Wl,--gc-sections` to keep the output
  ≤ 15 KiB per ABI.
- `app/build.gradle.kts` — `android.externalNativeBuild.cmake` block
  points at the CMakeLists; `defaultConfig.ndk.abiFilters` is
  `["arm64-v8a", "x86"]` to match the pre-built `libproxyagent.so`
  in `app/src/main/jniLibs/`. `ndkVersion = "26.3.11579264"`,
  `cmake.version = "3.22.1"` — both pinned and matched in
  `.github/workflows/build.yml` so CI uses the same toolchain.

If the user runs on an ABI without a matching `.so` (e.g. armv7
device on this build), `System.loadLibrary` fails, warmup logs
`splice: libagentsplice.so unavailable, NIO fallback only`, and
every tunnel goes through NIO. No crash, just lost optimisation.

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
JSON line on the control channel in all three engines. The UI
auto-cycle hook (`triggerAutoIpCycle`) is reachable from all three.

- **BINARY / AAR**: REBOOT is detected by parsing the SDK's
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

`maybeStartWifiRelay` requires an in-process engine — NATIVE or AAR —
because `ConnectivityManager.bindProcessToNetwork(cellular)` does not
survive `fork+exec` into the BINARY subprocess. The settings dialog
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
| **+ QUIC** | Above + `nativeagent/KwikQuicTransport.kt` | Adds QUIC uplink as an auto-negotiated fallback to TCP. | `tech.kwik:kwik:0.10.10` and core library desugaring for `java.time.Duration` (Android minSdk 21..25). Wire `quicTransportFactory = KwikQuicTransport.Factory()`. |
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
  (`registratorHost`, `registratorPort`, `agentUuid`). AAR has no Java
  setter for the modem path; modem config is env-only at the current
  SDK version.
- **Balancer:** env `balancer_host`, `balancer_port`,
  `fallback_file_url` for BINARY. NATIVE takes the same as `Config`
  fields (`balancerHost`, `balancerPort`, `fallbackFileUrl`). AAR
  also calls `Agent.setBalancer(host, port)` + `Agent.setFallbackURL`
  to satisfy the explicit Java init contract.

Fallback URL hard-coded across all three engines:
`https://s3.eu-central-1.amazonaws.com/cactusneedles/registrators.json`.

## App → agent commands

There aren't any out-of-band channels for BINARY / AAR — the
subprocess is read-only from our side (we only call `readLine()` on
its stdout, never write to `outputStream`), and we don't run a local
WebSocket client to consume the SDK's `LocalBroadcaster` REBOOT relay
either. REBOOT is detected solely by parsing the binary's stdout (see
[BINARIES.md] §5).

The NATIVE engine adds typed in-process callbacks alongside the same
log-string interface — `setRebootListener` for REBOOT and
`setLogSink` for everything else (`ProxyService` consumes the log
sink and runs the lines through `parseAgentLine` so the UI behaviour
stays identical across engines).

Reconfig = stop-and-restart on all three engines.

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
the connection mode is Modem, **and the engine is in-process** (NATIVE
or AAR — not BINARY). Lets the agent↔registrator uplink ride Wi-Fi
while the agent→target dial keeps using cellular — preserving the
mobile exit IP that clients see at the target, while removing the
uplink relay traffic from the mobile data bill.

### Why process binding is required (and why in-process engines only)

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
NATIVE if neither in-process engine is currently picked), the save
handler clamps `engine="native"` if the user somehow saves with
`engine="binary" && wifi_return=true`, and `maybeStartWifiRelay`
has a final guard that bails out if it ever sees
`engine == BINARY && wifi_return`.

### Lifecycle

- **Construction**: `maybeStartWifiRelay(host, port)` in `ProxyService`
  reads `cycle_cfg.json`, returns either `(realHost, realPort)` (relay
  disabled) or `("127.0.0.1", localPort)` (relay up). Engine + cellular-
  availability checks happen before relay startup:
  1. `mode == Modem` (Balancer not supported).
  2. `cfg.wifi_return && cfg.wifi_return_method == "local_relay"`.
  3. `engine != BINARY` (NATIVE or AAR — process binding doesn't
     reach subprocesses).
  4. `bindProcessToCellularBlocking()` — requests
     `TRANSPORT_CELLULAR + INTERNET` Network, awaits with 10s
     `CountDownLatch`, calls `cm.bindProcessToNetwork(cellular)` on
     success. Keeps the NetworkCallback alive for re-bind on cellular
     reattach. On timeout / failure → bails (no relay) so target dials
     don't silently leak.
  5. Start the actual relay listener.

- **Per-session**: a single `accept()` thread spawns two daemon pipe

### Why it's not a one-liner

The agent has two distinct connection types:

1. **Uplink** (agent ↔ registrator): a control TCP/QUIC socket plus a
   pool of data sockets. *All* tunneled client bytes traverse this, in
   both directions.
2. **Outbound dial** (agent → target): the actual TCP connection to the
   end host. *This is the one that must use cellular* — the IP the
   target observes is the local-bound IP of that socket.

A process-wide `ConnectivityManager.bindProcessToNetwork` would push (2)
onto Wi-Fi too and lose the mobile exit IP. Per-socket binding via
`Network.bindSocket(fd)` is a Java-only API, unreachable from the Go
binary (which is a plain Linux ELF with no JNI). So we put a loopback
relay in the middle:

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

The SDK still dials the target directly (untouched by the relay) — those
sockets ride the default route, which is cellular when both transports
are up *and the proxy's UID isn't bound to a specific network*.

### Lifecycle

- **Construction**: `maybeStartWifiRelay(host, port)` in `ProxyService`
  reads `cycle_cfg.json`, returns either `(realHost, realPort)` (relay
  disabled) or `("127.0.0.1", localPort)` (relay up). Called once per
  engine launch from `runNativeEngine`, `runBinaryEngine`, and
  `runAarEngine` before config/env is composed.
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
  On the AAR path the subsequent process kill cleans up anything
  wedged. NATIVE doesn't need the process kill — stopping the agent
  releases its sockets cleanly because there's no captured Go env.

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
| BINARY + wifi_return | Wi-Fi (via relay's explicit `bindSocket`) | **Wi-Fi (default route)** — subprocess doesn't inherit `bindProcessToNetwork(cellular)` | **Leaks Wi-Fi IP** to targets. Self-test reports `LEAK_DETECTED`; widget shows `leak_known` (amber); relay stays running for uplink mobile-data savings. UI nudges users toward NATIVE / AAR. |
| BINARY (no wifi_return) | n/a | Cellular (single default route) | Legacy modem path. No mobile-data savings; no IP leak. |
| AAR + anything | — | — | **Non-functional in this SDK build.** A reproduced symptom on Modem mode (Xiaomi Redmi Note 5 / Android 9) is `no registrator available; backing off` despite a verified `libc env check`. Cause is suspected to be deeper than the Modem-specific `setRegistrator` gap originally hypothesised — under investigation. Do not assume AAR works in any mode until verified end-to-end. |

`maybeStartWifiRelay` policy:
- `engine == BINARY` → relay still starts (for uplink savings) but we skip `bindProcessToCellularBlocking` (process bind is meaningless when the consumer is a subprocess). The self-test's `LEAK_DETECTED` verdict translates to `wifiReturnStatus = "leak_known"`, not a hard disable.
- `engine == NATIVE` or `AAR` → `bindProcessToCellularBlocking` must succeed before the relay starts. If it can't acquire cellular within 10s, we bail. Both engines run in-process so the bind sticks; NATIVE is the default and recommended path.
- Note: there is **no** in-service guard against `engine=AAR + mode=Modem` despite that combination being broken — the SDK's backoff loop already surfaces the failure in logs, and adding a guard here would mask the diagnostic.

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
  iteration. No service restart needed; no Go runtime to worry about.
- **BINARY engine**: in-place rollback. `effectiveHost`/`effectivePort`
  (declared as `var` for this purpose) flip back to the real upstream;
  `agentProcess.destroy()` trips the runner's readLine EOF, which
  loops back to `ProcessBuilder` with the updated env. Subprocess
  reconnects directly to the registrator, no service restart needed.
- **AAR engine**: can't roll back in-process (Go env is cached at
  JNI_OnLoad). Calls `doStop("Wi-Fi return: split routing not
  confirmed — disable the checkbox to use cellular directly")`. User
  sees an auto-stopped notification with the reason, unticks the
  checkbox, and starts again.

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
