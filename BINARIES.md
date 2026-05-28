# Bundled proxy-agent binaries

These binaries are built from upstream `proxy-agent` and are embedded into the
APK at build time. The files at the repository root are staging copies kept
there for easy refresh; the actual build consumes the copies under `app/`.

> **Status (2026-05-28):** the bundled binary backs the **BINARY** engine,
> which is the **legacy** code path. NATIVE (the pure-Kotlin port at
> `app/src/main/java/com/proxyagent/app/nativeagent/`) is the default
> engine for new installs and the only one Wi-Fi return supports.
> BINARY is kept short-term for testing/comparison and will be removed
> once NATIVE has enough field hours. The AAR engine that previously
> shipped alongside these two was removed entirely on 2026-05-28. See
> [ARCHITECTURE.md] §Agent engines.

For app-level architecture (service lifecycle, `conn_info` schema, settings,
IP rotation algorithm, watchdog) see [ARCHITECTURE.md]. This file stays
strictly about the bundled binary and how it's integrated.

[ARCHITECTURE.md]: ./ARCHITECTURE.md

## Version: v2.0.18 (updated 2026-05-21)

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

## How to update

1. Drop the new file into the repository root (file name must match
   the "Artifact" column).
2. Copy them to the target paths from the table above — the root files are
   not seen by the build; they only serve as a "fresh slot" for the next
   refresh.
3. Bump the version in this file and update the date.

## SDK runtime surface (proxy-agent-sdk-go @ v2.0.18)

What the bundled binaries actually do at runtime — useful when reading
agent logs or extending the integration.

### 1. Entry points

- **CLI binary** (`main.go`) → the Linux ELF we ship as `libproxyagent.so`.
  Parses flags via `config.FromEnvAndFlags()`, runs `supervisor.Start(ctx, cfg)`,
  honors `SIGINT`/`SIGTERM`. No positional args. Minimum invocations:
  - Balancer: `proxy-agent -balancer_host=H -balancer_port=P -agent_key=K`
  - Direct:   `proxy-agent -registrator_host=H -registrator_port=P -agent_key=K`

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
  (`netagent/brutal/`). Both values are baked into the Go binary
  and have no env override — the Android app's `network_profile`
  preset (see [ARCHITECTURE.md] §NetworkProfile-driven tuning)
  applies only to the NATIVE engine; `runBinaryEngine` logs a WARN
  on startup if a non-default profile was selected. Control = first
  opened stream; each server-opened stream carries a JSON
  `tunnelHeader{host, port}` then a byte pipe (no splice on the QUIC
  path).
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

- `app/src/main/jniLibs/arm64-v8a/libproxyagent.so`: AGP's default
  jniLibs source set ships it at `lib/arm64-v8a/libproxyagent.so` in
  the APK; the installer extracts to `applicationInfo.nativeLibraryDir`
  (because of the `lib*.so` name — the file is a standalone Linux ELF,
  never `dlopen()`ed).
- `abiFilters = ["arm64-v8a"]` matches the single prebuilt
  `libproxyagent.so` ABI. Only packaging tweak:
  `packaging.jniLibs.useLegacyPackaging = true`
  (`app/build.gradle.kts:55-59`) keeps `extractNativeLibs="true"` so
  the .so lands on disk for `exec()`.

### 2. Which artifact runs when

User picks. Settings radio (`MainActivity.kt`, `rgEngine`) writes pref
`engine` = `"native"` (default, pure-Kotlin port) or `"binary"`, passed
as Intent extra and dispatched in `ProxyService.onStartCommand`
(`Engine.NATIVE` → `runNativeEngine`, `Engine.BINARY` →
`runBinaryEngine`). No custom `Application`; `MainActivity` only reads
state files.

### 3. Subprocess launch (.so path)

- Call site `ProxyService.kt:541`:
  `ProcessBuilder(binary.absolutePath).redirectErrorStream(true)`. Path
  is `File(applicationInfo.nativeLibraryDir, "libproxyagent.so")` (526);
  `setExecutable(true, false)` defensively at 533.
- **No CLI args.** Env on `pb.environment()` (542-563): always
  `agent_key`, `enable_netagent=true`, `HOME=filesDir`,
  `TMPDIR=cacheDir`, `dns_servers`; plus mode-specific keys
  ([ARCHITECTURE.md] §"Connection modes"). `HOME=filesDir` is where
  the SDK writes `.proxyagent_transport` (sticky TCP/QUIC).
- stderr merged into stdout; cwd is the service default. stdout drained
  line-by-line on `AgentRunner` into `parseAgentLine` + `agent.log` (569-574).
- Lifecycle (537-604): exp backoff capped at 30s on non-stop exit.
  `forceReconnect` (832) calls `agentProcess?.destroy()` and interrupts
  the runner to skip backoff on network swaps. `doStop` (852-862)
  `destroy()`s with 2s grace then `destroyForcibly()`. Service runs in
  `:proxy` (`AndroidManifest.xml:80`), so app death kills the child.

### 4. In-process engine (NATIVE path)

The Kotlin port of the SDK lives in
`com.proxyagent.app.nativeagent.NativeProxyAgent`. Speaks the same TCP
and QUIC wire protocol as the binary, runs entirely in the `:proxy`
process — no subprocess, no Go runtime. Full lifecycle and config layout
are documented in [ARCHITECTURE.md] §Agent engines.

### 5. Log parsing — `parseAgentLine`

We tail the binary's stdout line-by-line and recognise these patterns
to drive app-side state. The reactions (state machine, `conn_info`
schema, status surface) live in [ARCHITECTURE.md]; this section only
catalogues which binary lines we look at and what they signal.

- `tunnel opened` / `opening tunnel` (179) — tunnel counter +1;
  `tunnel closed` (183) — counter -1, clamped at 0.
- `ws connected` / `uplink connected` (187) — uplink up. Transport
  detection (195-204): `transport=quic` → `"QUIC"`, `transport=tcp` →
  `"TCP (splice)"`, `uplink connected` with no key →
  `"TCP+yamux"` (v2.0.10–v2.0.13), `ws connected` → `"WebSocket"`
  (pre-v2.0.10). The old WS line's `url=wss://host` also fills the
  registrator field.
- `selected … registrator host=… port=…` (214) and
  `direct registrator configured …` (220) — fills registrator label.
- Reconnect signals (226-244): any of `ws read error`, `close 1006`,
  `ws close frame`, `uplink dial failed`, `uplink yamux init failed`,
  `uplink control stream open failed`, `uplink AUTH send/reply/denied`,
  `uplink accept loop ended`, `uplink control loop ended`,
  `balancer selection failed`, `no registrator available` — flips
  the state machine to RECONNECTING and clears
  registrator/tunnels/since/transport.
- `ws dialing` / `uplink dialing` / `balancer request` (253) →
  CONNECTING (unless already CONNECTED); `endpoint=host:port` on
  `uplink dialing` also fills registrator pre-AUTH.
- **`REBOOT received from registrator` (271)** — kicks off the
  IP-rotation flow (`triggerAutoIpCycle` → `IpCycle.cycleAndVerify`
  → `forceReconnect`). What the binary outputs is "REBOOT with this
  reason"; what the app does about it is the full
  [ARCHITECTURE.md] §"IP rotation".
