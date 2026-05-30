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
| `--echo-port`    | 17082   | TCP echo target — full-duplex (default for `mode=echo`)|
| `--sink-port`    | 17084   | TCP sink target — reads + discards (for `mode=upload`)|
| `--source-port`  | 17085   | TCP source target — streams random bytes (`mode=download`)|
| `--api-port`     | 17083   | HTTP test API (`POST /tests/...`)                     |
| `--auth-key`     | `e2e`   | Expected `key` in client's AUTH JSON. Empty = accept  |

TCP and QUIC share port 17080 by default — that's how production
registrators listen (`startTcp` and `startQuic` in NativeProxyAgent.kt
both pass `creds.port`). TCP and UDP are independent sockets, no
conflict.

The sink + source ports exist specifically to catch direction-specific
hangs that a full-duplex echo would mask (e.g. QUIC upload stalls
while download works — a real production bug history). `mode=upload`
routes the agent's target dial to the sink so no reverse traffic
refreshes flow-control credit; `mode=download` routes it to the
source so the agent only reads from the target socket.

## Test API

`POST /tests/tunnel-roundtrip?bytes=N&transport=tcp|quic&mode=echo|upload|download&target-host=...&target-port=...`
— opens one tunnel from server → client and runs one of three I/O
patterns through it:

- `mode=echo` (default) — pushes N bytes, expects them back verbatim
  via the echo target. Tests byte parity in both directions.
- `mode=upload` — writes N bytes into the sink target, no return read.
  Catches upload-side stalls in isolation.
- `mode=download` — reads N bytes from the source target, no write.
  Catches download-side stalls in isolation.

Returns `{"ok":bool,"bytes":N,"sent_bytes":S,"recv_bytes":R,"mode":...,"hash_sent":...,"hash_recv":...}`.
`target-host`/`target-port` override the routing — used by error-path
tests (e.g. unreachable port → OPEN_FAIL).

`POST /tests/tunnel-roundtrip-concurrent?count=K&bytes=N&transport=...&mode=...`
— same scenario but fan-out across K tunnels in parallel. Returns
per-tunnel breakdown + aggregate throughput metrics (`wall_ms`,
`agg_mbps`, `agg_mbps_duplex`).

`GET /healthz` — `200 ok` once both TCP + QUIC accept loops are live.

## Run locally

```bash
cd e2e/testserver
go run . --tcp-port 17080 --quic-port 17081 --echo-port 17082 --api-port 17083
```

Run from anywhere reachable by the Android device under test. For the CI
emulator, the server runs on the host machine and the agent dials
`10.0.2.2:<port>`.
