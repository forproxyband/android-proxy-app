# e2e/testserver

Mock proxy upstream for the Android agent's e2e tests. Speaks the same TCP
and QUIC wire protocol that production registrators do (see
`app/src/main/java/com/proxyagent/app/nativeagent/NativeProxyAgent.kt`
companion-object constants), so the unmodified app code under test connects
to it as if it were a real upstream hub.

Not a real proxy — every tunnel the server opens is wired to an internal
echo target instead of a real upstream. The HTTP test API on top lets the
JUnit instrumentation tests trigger tunnels, push bytes, and assert byte
parity.

## Ports

All four listeners bind to the same address (default `0.0.0.0`); pick free
ports via flags. Defaults assume the Android emulator's `10.0.2.2`
loopback-to-host mapping.

| Flag             | Default | Role                                                  |
|------------------|---------|-------------------------------------------------------|
| `--tcp-port`     | 17080   | TCP uplink: TUNL handshake + control + data sockets   |
| `--quic-port`    | 17080   | UDP/QUIC uplink: TLS1.3 + ALPN `proxy-tunnel/1`       |
| `--echo-port`    | 17082   | Internal TCP echo target — tunnels dial here          |
| `--api-port`     | 17083   | HTTP test API (`POST /tests/...`)                     |
| `--auth-key`     | `e2e`   | Expected `key` in client's AUTH JSON. Empty = accept  |

TCP and QUIC share port 17080 by default — that's how production
registrators listen (`startTcp` and `startQuic` in NativeProxyAgent.kt
both pass `creds.port`). TCP and UDP are independent sockets, no
conflict.

## Test API

`POST /tests/tunnel-roundtrip?bytes=N&transport=tcp|quic` — opens one tunnel
from server → client, pushes N random bytes through it, expects them back
verbatim from the agent (which TCP-bridges them into the echo target). On
success returns `{"ok":true,"bytes":N,"hashSent":"...","hashRecv":"..."}`,
otherwise `{"ok":false,"error":"..."}`.

`GET /healthz` — `200 ok` once both TCP + QUIC accept loops are live.

## Run locally

```bash
cd e2e/testserver
go run . --tcp-port 17080 --quic-port 17081 --echo-port 17082 --api-port 17083
```

Run from anywhere reachable by the Android device under test. For the CI
emulator, the server runs on the host machine and the agent dials
`10.0.2.2:<port>`.
