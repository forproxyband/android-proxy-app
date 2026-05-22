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

## `conn_info` schema

One pipe-delimited line written by `writeConnInfo`
(`ProxyService.kt:126-138`). Eight fields (0-indexed):

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

Both paths read `CycleConfig` from SharedPreferences `cfg` (`apn_swap`,
`imei_rotate`, `imei_method`, `imei_cmd`) and call
`IpCycle.cycleAndVerify(context, knownIp, log, config)`. Returns
`CycleResult(oldIp, newIp, changed, attempts, totalMs, reason)` —
`reason` is one of `ok` / `ok_no_baseline` / `ip_unchanged` /
`no_toggle_method` / `interrupted`.

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
