# App architecture

How `ProxyService` + `MainActivity` orchestrate the bundled proxy-agent
binary and expose state to the user. Read alongside [BINARIES.md], which
covers what the bundled binary itself does, how it's wired into the
APK, and which of its stdout lines we react to.

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
  stop-and-restart contract on the binary engine for consistency.

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
| `wifi_info.json` | service | JSON | Latest Wi-Fi return split-routing self-test result + Wi-Fi link snapshot. Keys: `public_ip_wifi`, `public_ip_cell`, `link_speed_mbps`, `frequency_mhz`, `band` (`"2.4 GHz"`/`"5 GHz"`/`"6 GHz"`), `standard` (`"Wi-Fi 5 (802.11ac)"` etc.), `wifi_attached`, `test_result` (`SUCCESS`/`SAME_IP`/`WIFI_PROBE_FAILED`/`CELL_PROBE_FAILED`/`BOTH_FAILED`), `test_detail`, `test_duration_ms`, `tested_at_ms`. Written by ProxyService after each self-test; read by MainActivity for the widget two-IP block and the log-export header. |

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
| 6 | `currentUplinkTransport` | string | `QUIC` / `TCP (splice)` / `TCP+yamux` / `WebSocket`. Added v2.0.14-quic. |
| 7 | `cycleStage` | string | Non-empty only during REBOOT auto-cycle. UI shows `ROTATING · <stage>`. Added with `IpCycle.cycleAndVerify` rework. |
| 8 | `wifiReturnStatus` | string | `""` (relay off) / `"wifi"` (uplink on Wi-Fi) / `"wifi_fallback"` (relay up, no Wi-Fi held — flowing through cellular). UI shows "↺ uplink via Wi-Fi" / "↺ uplink via cellular (Wi-Fi return enabled, no Wi-Fi held)". Added with Wi-Fi return relay. |

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
| `engine` | string | `"binary"` | `"binary"` (subprocess `.so`) or `"aar"` (in-process). |
| `speed_bytes` | bool | false | Rate display unit. |
| `analytics_retention_days` | int | 30 | Older analytics buckets are pruned on app launch. |
| `apn_swap` | bool | false | Enable APN swap fallback during cycle (see IP rotation §). |
| `imei_rotate` | bool | false | Enable IMEI rotation fallback. |
| `imei_method` | string | `"custom"` | `"custom"` / `"props"` / `"magisk-imei"`. |
| `imei_cmd` | string | — | Root shell command when `imei_method="custom"`. |
| `wifi_return` | bool | false | Route the agent↔registrator uplink over Wi-Fi via a loopback relay; target dials still ride cellular. Modem mode only — auto-clamped to false on save when `mode="balancer"`. See "Wi-Fi return relay" below. |
| `wifi_return_method` | string | `"local_relay"` | Slot for future methods (SO_MARK, VpnService split tunnel). No UI yet — only `local_relay` is implemented; anything else falls back to direct dial with a log line. |

## Connection modes

`rgMode` radio in the dialog (`rbModeModem`/`rbModeBalancer`). Pref
`mode` → Intent extra → `Mode` enum at `ProxyService.kt:439`. QR
import force-selects Modem (`MainActivity.kt:430, 438`).

- **Modem (direct):** env `registrator_host`, `registrator_port`,
  optional `agent_uuid` in both engines. AAR has no Java setter for
  the modem path; modem config is env-only at the current SDK version.
- **Balancer:** env `balancer_host`, `balancer_port`,
  `fallback_file_url`; AAR also calls `Agent.setBalancer(host, port)`
  + `Agent.setFallbackURL` to satisfy the explicit Java init contract.

Fallback URL hard-coded in both engines (`ProxyService.kt:560`/`654`):
`https://s3.eu-central-1.amazonaws.com/cactusneedles/registrators.json`.

## App → agent commands

There aren't any. The subprocess is read-only from our side — we only
call `readLine()` on its stdout (`ProxyService.kt:569-574`), never
write to `outputStream`. No local WebSocket client either; the SDK's
`LocalBroadcaster` REBOOT relay is not consumed. REBOOT is detected
solely by parsing the binary's stdout (see [BINARIES.md] §5). Reconfig
= stop-and-restart.

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

Optional split-routing layer added when `cycle_cfg.json.wifi_return=true`
and the connection mode is Modem. Lets the agent↔registrator uplink ride
Wi-Fi while the agent→target dial keeps using cellular — preserving the
mobile exit IP that clients see at the target, while removing the uplink
relay traffic from the mobile data bill.

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
  engine launch from `runBinaryEngine` and `runAarEngine` before env
  is composed.
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
  `ServerSocket` (kills `accept`), unregisters the network callback,
  and lets in-flight pipe threads drain naturally on EOF. On the AAR
  path the subsequent process kill cleans up anything wedged.

### Fallback behaviour

When `wifiNet == null` (Wi-Fi gone, validation pending, captive
portal), the relay still accepts the local connection and dials the
upstream **without** `bindSocket` — the resulting socket rides the
default route, which is cellular. The agent stays up; bandwidth
savings stop until Wi-Fi recovers. We deliberately don't kill existing
sessions on `onLost` — TCP can't be moved between interfaces anyway,
and re-establishing on every Wi-Fi flap would just thrash the
registrator's connection counter.

### Split-routing self-test — `SplitRoutingSelfTest`

After the relay starts (and every time Wi-Fi changes via a dedicated
NetworkCallback in ProxyService), we run a hard verification that the
OS is actually segregating the two transports — otherwise the relay's
`bindSocket(wifiNet)` calls would silently fail to split traffic and
the target would see the Wi-Fi public IP instead of the mobile exit IP.

Test procedure (`SplitRoutingSelfTest.runTest`, overall budget 15s):

1. `requestNetwork(TRANSPORT_WIFI + INTERNET)`, await onAvailable
   (≤6s). Call `wifiNet.openConnection("https://api.ipify.org")` and
   read the public IP.
2. Same for `TRANSPORT_CELLULAR`. Returns `cellPublicIp`.
3. Compare:
   - Both ok, IPs differ → `SUCCESS`. Split routing confirmed.
   - Both ok, IPs equal → `SAME_IP`. OS suppresses one transport
     (cellular gets released when Wi-Fi is up). Relay can't help.
   - Either probe times out → `WIFI_PROBE_FAILED` / `CELL_PROBE_FAILED`.
     Keep the relay running — could be a transient captive-portal flap.
     Re-test fires on the next network change.
   - Both timeout → `BOTH_FAILED`. Same — wait for the next retest.

Result is persisted to `wifi_info.json` along with a
`WifiInfoProbe.snapshot` of the Wi-Fi link (speed, frequency, band,
standard — no SSID, so no `ACCESS_FINE_LOCATION` needed).

### On SAME_IP failure — relay rollback

The user explicitly chose "fail loud" over "claim to work when it
doesn't". Behaviour:

- `wifiReturnSplitFailed` flag set (sticky until next service start).
- `wifiReturnStatus = "split_failed"` written to `conn_info` field 8;
  the status updater preserves this (doesn't clobber to `""`).
- `stopWifiRelayIfRunning` closes the ServerSocket and unregisters
  network callbacks.
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
