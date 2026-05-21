# Bundled proxy-agent binaries

These binaries are built from upstream `proxy-agent` and are embedded into the
APK at build time. The files at the repository root are staging copies kept
there for easy refresh; the actual build consumes the copies under `app/`.

## Version: v2.0.17 (updated 2026-05-21)

TCP-first uplink with a QUIC fallback and a sticky transport cache
(`$HOME/.proxyagent_transport`). The TCP path drops yamux: one control
TCP socket plus an on-demand fleet of data TCP sockets (warm pool of 8),
bridged via `io.Copy` between `*net.TCPConn` so the kernel handles the
data path with `splice(2)` — no userspace copies on the hot side. The
QUIC fallback uses `apernet/quic-go` with Brutal congestion control at
100 Mbps over a 32 MiB-buffered UDP socket. After AUTH the agent logs
`uplink connected … transport=quic|tcp` — the Android app reads that to
display the active transport in the status card.

| Artifact | Role in the APK | Path in the repo |
| --- | --- | --- |
| `proxy-agent-linux-arm64` | `libproxyagent.so` for arm64-v8a (native executable launched by `ProxyService` via `Runtime.exec`) | `app/src/main/jniLibs/arm64-v8a/libproxyagent.so` |
| `proxy-agent-linux-x86` | `libproxyagent.so` for x86 | `app/src/main/jniLibs/x86/libproxyagent.so` |
| `proxyagent.aar` | gomobile AAR with the in-process engine (`proxyagent.sdk.agent.Agent`), wired in via the `fileTree` dependency in `app/build.gradle.kts` | `app/libs/proxyagent.aar` |

## How to update

1. Drop the three new files into the repository root (file names must match
   the "Artifact" column).
2. Copy them to the target paths from the table above — the root files are
   not seen by the build; they only serve as a "fresh slot" for the next
   refresh.
3. Bump the version in this file and update the date.

## SDK runtime surface (proxy-agent-sdk-go @ v2.0.17)

What the bundled binaries actually do at runtime — useful when reading
agent logs or extending the integration.

### 1. Entry points

- **CLI binary** (`main.go`) → the Linux ELF we ship as `libproxyagent.so`.
  Parses flags via `config.FromEnvAndFlags()`, runs `supervisor.Start(ctx, cfg)`,
  honors `SIGINT`/`SIGTERM`. No positional args. Minimum invocations:
  - Balancer: `proxy-agent -balancer_host=H -balancer_port=P -agent_key=K`
  - Direct:   `proxy-agent -registrator_host=H -registrator_port=P -agent_key=K`
- **gomobile AAR** built from `pkg/agent` (`make android` →
  `gomobile bind -javapkg=proxyagent.sdk -o proxyagent.aar ./pkg/agent`).
  Java class is `proxyagent.sdk.agent.Agent` with static methods:
  `startAgent()`, `stopAgent()`, `getStatus()` (returns `AgentStatus`
  with `running`, `healthy`, `registratorConnected`, `registratorHost`,
  `registratorPort`, `lastError`, `startedAt`, `uptime`), `setDNSServers`,
  `clearDNSServers`, plus env-shim setters `setBalancer(host, port)`,
  `setAgentKey`, `setFallbackURL`, `setEnableNetAgent`
  (`pkg/agent/bind_config.go`). The shims exist because gomobile callers'
  `android.system.Os.setenv` bypasses Go's cached `runtime.envs` — see
  Android §4 for the consequence.

### 2. Configuration

Precedence in `internal/config/config.go`: **CLI flag > env var > built-in
default**. Env is read first to derive each flag's default, then
`fs.Parse(filterArgs(os.Args[1:]))` overrides. No config file.

| Key | Default | Meaning |
| --- | --- | --- |
| `balancer_host`, `balancer_port`, `balancer_path` | — / — / `/getRegistrator` | HTTP balancer endpoint. |
| `registrator_host` / `REGISTRATOR_HOST`, `registrator_port` / `REGISTRATOR_PORT` | — | Direct registrator (bypasses balancer when both set). |
| `agent_key` / `AGENT_KEY` / `key` / `KEY` | — | Auth key (first non-empty wins; `agent_key` beats `key`). |
| `agent_uuid` / `AGENT_UUID` | falls back to `agent_key` | Identity in AUTH / INFO. |
| `fallback_file_url` | — | JSON list of registrators tried when balancer fails. |
| `SOURCE_IP_LIST` | — | CSV source IPs reported in AUTH. |
| `enable_netagent`, `enable_systeminfo`, `enable_heartbeat` | `true` | Feature toggles. |
| `heartbeat_interval_sec` | `60` | INFO / heartbeat cadence (seconds). |
| `dns_servers` / `DNS_SERVERS` | — | DNS override (CSV / space / `;` separated). |
| `output_ip` | — | Local bind IP for outgoing target dials. |
| `send_queue` | `1024` | Internal queue depth. |
| `http_timeout_ms`, `sender_*_ms` | various | HTTP and legacy sender timeouts. |
| `is_local_websocket_active`, `local_websocket_host`, `local_websocket_port` | `false` / `0.0.0.0` / `10010` | Local WebSocket relay (see §6). |
| `ws_path` | — | Legacy, silently ignored by the v2.0.17 TCP/QUIC uplink. |

Set automatically by the SDK itself: `QUIC_GO_DISABLE_ECN=true`
(`uplink.go:119`, workaround for the gomobile sendmsg ECN bug). The
transport cache reads `TMPDIR` as the last-resort fallback when
`os.UserHomeDir()` fails (`transport_cache.go:63`).

### 3. Connection modes

Picked by `supervisor.go:97-145`:

- **Direct** — both `registrator_host` and `registrator_port` set.
  Balancer and fallback are skipped. Logs
  `direct registrator configured; balancer and fallback selection disabled`.
- **Balancer-discovered** (default) — `balancer_host`+`balancer_port` set.
  Does `GET http://<balancer>/<balancer_path>` with
  `Authorization: Bearer <agent_key>`; expects JSON
  `{host, port, health_check_port}`. A returned `host=="0.0.0.0"` is
  rewritten to the balancer host.
- **Fallback** — when neither yields credentials and `fallback_file_url`
  is set. List is fetched once, each entry probed at
  `http://<host>:<health_check_port>/health`, ranked by
  `DefaultComparator` (Ready, FreeSockets, CPULoad, RAMLoad, AgentCount).

The SDK has no separate "modem" mode — the Android app's "modem" maps
onto direct here.

### 4. Wire protocol

**Registrator selection** is plain HTTP — `GET` against the balancer's
`/getRegistrator` and against each fallback's `/health`, both with
`Authorization: Bearer <agent_key>`. No gRPC.

**Uplink** (`internal/netagent/`):

- **TCP transport** (`uplink.go`, `wire.go`, `pool.go`) — every socket
  opens with a 6-byte handshake: magic `"TUNL"` + version `0x01` +
  connType (`0x01`=control, `0x02`=data). One control socket per
  session, a warm pool of 8 pre-handshaked data sockets behind it.
- **QUIC transport** — single connection, ALPN `proxy-tunnel/1`, TLS
  with `InsecureSkipVerify=true`, custom `quic.Transport` over a
  32 MiB-buffered UDP socket, Brutal CC at 100 Mbps
  (`netagent/brutal/`). Control = first opened stream; each
  server-opened stream carries a JSON `tunnelHeader{host, port}` then a
  byte pipe (no splice on the QUIC path).
- **Try order**: `TCP, QUIC` by default; the sticky cache flips it if
  QUIC won last time (`uplink.go:127-133`).

Control channel speaks **newline-delimited JSON**:

| Direction | Command | Payload |
| --- | --- | --- |
| agent → server | `AUTH` | `{key, uuid, host, sourceIPList}` |
| agent → server | `OPEN_FAIL` | `{token, reason}` (best-effort) |
| server → agent | `AUTH_OK` | `{}` |
| server → agent | `OPEN` | `{token, host, port}` |
| server → agent | `REBOOT` | `{reason}` |

On auth failure the server closes the channel without replying — the
agent surfaces that EOF as denial (30 s timeout). On `OPEN` the agent
picks a warm TCP data socket, writes the 32-hex-char token, then
`io.Copy`s between the data socket and the dialed target — both are
`*net.TCPConn`, so Go's stdlib uses `splice(2)` in the kernel.

### 5. Sticky transport cache

File `$HOME/.proxyagent_transport` (or `$TMPDIR/.proxyagent_transport`
as fallback). Plain text body — just `"tcp\n"` or `"quic\n"`. Mode
`0o600`. Read at the start of `Uplink.Start`, written after AUTH_OK,
errors swallowed. On this Android app, `HOME` is set to `filesDir`
(Android §3), so the cache lives in app-private storage.

### 6. Local WebSocket relay

`internal/localws/`. Listens on
`local_websocket_host:local_websocket_port` (default `0.0.0.0:10010`)
when `is_local_websocket_active=true`. No TLS, no auth, any path
accepted. Pushes `{"command":"REBOOT","message":"..."}` JSON text
frames to relay registrator REBOOTs. **This Android app does not
enable it** — REBOOT is observed by parsing the agent's stdout log
line instead (Android §5).

### 7. Key stdout log lines

All logs go to stdout in slog text format
(`<RFC3339> level=<LVL> msg="..." k=v ...`):

- Lifecycle: `agent starting...`, `supervisor start`, `supervisor stop`.
- Feature toggles: `netagent feature enabled` / `... disabled; skipping
  connect loop`, `local websocket relay enabled` / `... disabled
  (is_local_websocket_active=false)`.
- Registrator: `direct registrator configured; ...`, `selected
  registrator via balancer`, `selected registrator via fallback`,
  `balancer selection failed; attempting fallback`, `no registrator
  available; backing off`.
- Uplink dial: `uplink: using cached transport preference`,
  `uplink dialing` (with `endpoint`, `transport`),
  `uplink: TCP control established`, `uplink: QUIC control established`,
  `uplink: transport dial failed`.
- AUTH success: `uplink connected` (with `uuid=…`, `transport=tcp|quic`)
  — the line Android §5 reads for the status badge.
- AUTH failure: `uplink AUTH denied (control closed before reply)`,
  `uplink AUTH unexpected reply`, `uplink AUTH send failed`.
- Tunneling: `opening tunnel` (TCP), `opening quic tunnel`,
  `quic tunnel closed`, `tunnel target dial failed`,
  `tunnel data conn unavailable`.
- REBOOT: `REBOOT received from registrator` (Android consumes this),
  `REBOOT relayed to local clients`, `REBOOT received but local
  websocket is disabled; dropping`.
- Teardown / reconnect: `uplink context cancelled; closing`,
  `uplink control loop ended`, `uplink QUIC accept loop ended`,
  `reconnect backoff` (exp-jitter 250 ms → 10 s).

## Android-side integration

### 1. Build wiring

- `app/src/main/jniLibs/{arm64-v8a,x86}/libproxyagent.so`: AGP's default
  jniLibs source set ships it at `lib/<abi>/libproxyagent.so` in the APK;
  the installer extracts to `applicationInfo.nativeLibraryDir` (because
  of the `lib*.so` name — the file is a standalone Linux ELF, never
  `dlopen()`ed).
- The .aar via a generic fileTree at `app/build.gradle.kts:70` —
  anything in `app/libs/*.aar` is merged:

  ```kotlin
  implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
  ```

- No `abiFilters` (both ABIs ship). Only packaging tweak:
  `packaging.jniLibs.useLegacyPackaging = true`
  (`app/build.gradle.kts:55-59`) keeps `extractNativeLibs="true"` so
  the .so lands on disk for `exec()`.

### 2. Which artifact runs when

User picks. Settings radio (`MainActivity.kt:287-289`, `rgEngine`)
writes pref `engine` = `"binary"` (default) or `"aar"`, passed as
Intent extra (`MainActivity.kt:827`) and dispatched at
`ProxyService.kt:512-519` (`Engine.BINARY` → `runBinaryEngine`,
`Engine.AAR` → `runAarEngine`). No custom `Application`;
`MainActivity` only reads state files.

### 3. Subprocess launch (.so path)

- Call site `ProxyService.kt:541`:
  `ProcessBuilder(binary.absolutePath).redirectErrorStream(true)`. Path
  is `File(applicationInfo.nativeLibraryDir, "libproxyagent.so")` (526);
  `setExecutable(true, false)` defensively at 533.
- **No CLI args.** Env on `pb.environment()` (542-563): always
  `agent_key`, `enable_netagent=true`, `HOME=filesDir`,
  `TMPDIR=cacheDir`, `dns_servers`; plus mode keys (§8). `HOME=filesDir`
  is where the SDK writes `.proxyagent_transport` (sticky TCP/QUIC).
- stderr merged into stdout; cwd is the service default. stdout drained
  line-by-line on `AgentRunner` into `parseAgentLine` + `agent.log` (569-574).
- Lifecycle (537-604): exp backoff capped at 30s on non-stop exit.
  `forceReconnect` (832) calls `agentProcess?.destroy()` and interrupts
  the runner to skip backoff on network swaps. `doStop` (852-862)
  `destroy()`s with 2s grace then `destroyForcibly()`. Service runs in
  `:proxy` (`AndroidManifest.xml:80`), so app death kills the child.

### 4. In-process engine (.aar path)

All in `runAarEngine` (`ProxyService.kt:622-738`):

- Env set via `Os.setenv(..., true)` *before* Go loads, lowercase and
  SCREAMING_SNAKE (`setBoth`, 631-634) — Go caches `runtime.envs` at
  `JNI_OnLoad` and never re-reads.
- `Class.forName("go.Seq")` + `setContext(ctx)` (682-684) triggers
  `System.loadLibrary("gojni")`. `Class.forName("proxyagent.sdk.agent.Agent")`
  (687) then reflective: `setAgentKey`, `setEnableNetAgent`;
  balancer-only `setBalancer(host, port)` + `setFallbackURL`;
  `setDNSServers`; `startAgent()` (729). Modem registrator config is
  env-only at this AAR version (comment 717-718).
- `stopAgent()` via reflection (866-867); `:proxy` suicides 400ms later
  (912-917) because Go's env cache blocks clean in-process restart.
- Native stdout/stderr captured via `Os.pipe()` + `Os.dup2(fd, 1/2)` in
  `captureNativeOutput` (742-762), plus a `logcat … GoLog:V Go:V` tail
  (764-781).

### 5. Log parsing — `parseAgentLine` (`ProxyService.kt:177-276`)

- `tunnel opened` / `opening tunnel` (179) → `activeTunnels++`;
  `tunnel closed` (183) → `activeTunnels--` clamped.
- `ws connected` / `uplink connected` (187) → `CONNECTED`,
  `connectedSinceMs=now`. Transport detection (195-204):
  `transport=quic` → `"QUIC"`, `transport=tcp` → `"TCP (splice)"`,
  `uplink connected` with no key → `"TCP+yamux"` (v2.0.10–v2.0.13),
  `ws connected` → `"WebSocket"` (pre-v2.0.10). Old WS line's
  `url=wss://host` also fills `currentRegistrator`.
- `selected … registrator host=… port=…` (214) and
  `direct registrator configured …` (220) → fill registrator.
- Reconnect triggers (226-244): any of `ws read error`, `close 1006`,
  `ws close frame`, `uplink dial failed`, `uplink yamux init failed`,
  `uplink control stream open failed`, `uplink AUTH send/reply/denied`,
  `uplink accept loop ended`, `uplink control loop ended`,
  `balancer selection failed`, `no registrator available` →
  `RECONNECTING` + clear registrator/tunnels/since/transport.
- `ws dialing` / `uplink dialing` / `balancer request` (253) →
  `CONNECTING` (unless already CONNECTED); `endpoint=host:port` on
  `uplink dialing` fills registrator pre-AUTH.
- `REBOOT received from registrator` (271) →
  `triggerAutoIpCycle(reason)` (cellular toggle + reconnect, 278-316).

### 6. Status surface

`conn_info` at `filesDir/conn_info`, written every 1s by `StatusUpdater`
via `writeConnInfo` (`ProxyService.kt:126-135`). One pipe-delimited
line: `${connStatus.name}|$rxRate|$txRate|$currentRegistrator|$activeTunnels|$connectedSinceMs|$currentUplinkTransport`.
Fields (0-indexed): 0=status enum, 1=rxRate B/s, 2=txRate B/s,
3=`host:port`, 4=tunnels, 5=connectedSinceMs (epoch ms), 6=uplink
transport label (added v2.0.14-quic). `MainActivity.refresh()` polls
every 3s (`MainActivity.kt:1076-1086`) using `getOrNull(N)` for
forward-compat with 6-field downgrades.

Sibling files in `filesDir`: `proxy_state` (`starting`/`running`/
`stopped`/`auto_stopped`/`error`), `stop_reason`, `agent.log` (rotated
30→25 MiB), `nat_ip`, `battery_threshold`, `speed_units`. No
Binder/broadcasts — service→UI is file-based; UI→service is
`startService` Intent extras + `action=STOP`.

### 7. App → agent commands

None. No writes to `proc.outputStream` (only `readLine()`,
`ProxyService.kt:569-574`). No local WebSocket client — the SDK's
`LocalBroadcaster` REBOOT relay is **not** consumed; REBOOT is handled
by parsing the agent's log line (271-274) and cycling locally. Reconfig
= stop-and-restart (`MainActivity.kt:374-379`).

### 8. Connection modes

Settings dialog (`MainActivity.kt:278-408`): one host/port/key/id/dns
block + `rgMode` radio (`rbModeModem`/`rbModeBalancer`). Pref `mode`
(`"modem"`|`"balancer"`) → Intent extra (`MainActivity.kt:828`) →
`Mode` enum at `ProxyService.kt:439`. QR import force-selects Modem
(`MainActivity.kt:430, 438`).

- **Modem (direct):** env `registrator_host`, `registrator_port`,
  optional `agent_uuid` in both engines; AAR has no Java setter, env-only.
- **Balancer:** env `balancer_host`, `balancer_port`, `fallback_file_url`;
  AAR also calls `Agent.setBalancer(host, port)` + `Agent.setFallbackURL`.

Fallback URL hard-coded in both engines (`ProxyService.kt:560`/`654`):
`https://s3.eu-central-1.amazonaws.com/cactusneedles/registrators.json`.
