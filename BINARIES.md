# Bundled proxy-agent binaries

These binaries are built from upstream `proxy-agent` and are embedded into the
APK at build time. The files at the repository root are staging copies kept
there for easy refresh; the actual build consumes the copies under `app/`.

## Version: v2.0.16-quic (updated 2026-05-19)

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
