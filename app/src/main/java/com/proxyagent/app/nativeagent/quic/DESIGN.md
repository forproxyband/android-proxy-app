# Native QUIC client — design

In-house minimal QUIC client. Replaces the kwik dependency for the
native engine's QUIC uplink. Built so we own the threading model:
the kwik regression we hit (ACK/window-update emission gated behind
STREAM-frame draining → receive-side throughput collapsed when the
send-side buffer was lifted) is a direct symptom of kwik's single-
sender-thread architecture. Owning the stack means the fix is a
matter of how we schedule packets, not a Hail-Mary reflection
patch.

This document is the load-bearing reference for anyone touching
the `quic/` package — read it before changing component boundaries.

## Goals

1. **Interop with quic-go** on the proxy-server side, not the wider
   QUIC ecosystem. We only have to be a correct client against one
   server implementation, and we already know that server's exact
   transport parameters and behavior.

2. **Brutal CC parity** with the BINARY engine — ≥ 90 Mbps QUIC
   download on healthy paths, matching `proxy-agent-sdk-go`'s
   `brutalBandwidthMbps = 100` ceiling.

3. **Symmetric throughput.** Upload (we receive on QUIC) must not
   collapse when download (we send on QUIC) is saturated. This is
   what kwik failed at; the threading model below makes it explicit.

4. **Plug-compatible with the existing `QuicTransport` interface**
   (`nativeagent/QuicTransport.kt`) so `NativeProxyAgent` swaps
   factories with no other changes.

## Non-goals

Each of these would add weeks for marginal benefit against our
one known peer:

- **0-RTT / early data.** No latency savings for our reconnect
  pattern (a few seconds), not worth the replay-attack surface.
- **Connection migration.** Mobile path changes tear the QUIC
  connection down today (kwik does); the supervisor reconnects.
  No reason to add it here.
- **Multipath, datagrams, DPLPMTUD.** Not used by quic-go server
  side for our app.
- **Full TLS 1.3 from scratch.** We bridge to BouncyCastle's
  `TlsClientProtocol` via a custom transport — every Android device
  already has BC on the classpath via the platform conscrypt, plus
  the explicit BC dep for the parts conscrypt doesn't expose at the
  raw-handshake level.
- **All cipher suites.** AES-128-GCM only (mandatory baseline,
  hardware-accelerated on every arm64 device we ship to).
- **All QUIC frame types.** PADDING, PING, ACK, CRYPTO, STREAM,
  MAX_DATA, MAX_STREAM_DATA, MAX_STREAMS, CONNECTION_CLOSE,
  HANDSHAKE_DONE. That's it. Anything else, log + ignore (or close
  the connection with protocol violation if it's mandatory).

## Component graph

Bottom-up — each layer depends only on what's below:

```
┌─────────────────────────────────────────────┐
│  QuicTransport adapter (Phase 10)           │ ← matches the
│  NativeQuicTransport: Factory, Stream       │   kwik adapter
└────────────────────┬────────────────────────┘   contract
                     │
┌────────────────────┴────────────────────────┐
│  Connection (Phase 5)                       │ ← state machine,
│  Initial → Handshake → 1-RTT Confirmed      │   sender/receiver
│  ┌──────────────┐  ┌────────────────────┐   │   orchestration
│  │ Sender       │  │ Receiver            │  │
│  │ packet-out   │  │ packet-in           │  │
│  └──────────────┘  └────────────────────┘   │
└────────────────────┬────────────────────────┘
                     │
   ┌─────────────────┼─────────────────────────┐
   │                 │                          │
┌──┴─────────┐  ┌────┴───────┐  ┌──────────────┴───┐
│  Streams   │  │ Recovery   │  │ Flow control      │
│  (Phase 6) │  │ (Phase 7)  │  │ (Phase 8)         │
└──┬─────────┘  └────┬───────┘  └──────────────────┘
   │                 │
   │      ┌──────────┴───────────┐
   │      │  CC — Brutal pacer    │
   │      │  (Phase 9)            │
   │      └──────────────────────┘
   │
┌──┴────────────────────────────────────────────┐
│  TLS 1.3 bridge (Phase 4)                     │
│  BouncyCastle TlsClientProtocol ⇄ CRYPTO frame│
└────────────────────┬──────────────────────────┘
                     │
┌────────────────────┴──────────────────────────┐
│  Crypto (Phase 3)                             │
│  HKDF key derivation, AES-GCM AEAD,           │
│  AES-ECB header protection                    │
└────────────────────┬──────────────────────────┘
                     │
┌────────────────────┴──────────────────────────┐
│  Wire encoding (Phase 2)                      │
│  PacketHeader, Frame parsing/emission         │
└────────────────────┬──────────────────────────┘
                     │
┌────────────────────┴──────────────────────────┐
│  Varint (Phase 1) — RFC 9000 §16              │
└───────────────────────────────────────────────┘
```

## Threading model — explicitly designed around the kwik regression

kwik runs one sender thread. That thread:
1. Walks all open streams, drains their SendBuffers into STREAM
   frames, packs into packets, emits.
2. Generates ACK frames from received packets.
3. Emits MAX_STREAM_DATA / MAX_DATA frames when our receive
   windows open.

When (1) has a deep backlog (which is the desired state for high
download throughput), it monopolizes the sender thread and (2)+(3)
suffer. The peer sees stale flow-control windows and throttles its
own send — which is *our receive direction*. Hence the upload
collapse we hit on the SendBuffer patch.

Our model splits frame emission into **two cadences**, with each
running on its own thread, racing each other only for the UDP
socket write:

- **High-priority "control" emitter.** Wakes on (a) every received
  packet (to schedule an ACK), (b) flow-control window changes
  (MAX_STREAM_DATA delta crosses 1/8 of window) (c) idle timeout
  half-elapsed (PING). Packs ACK/MAX_*/PING into a 1-RTT packet,
  pads to MTU, emits. Should always preempt the data emitter
  because its work is tiny per cycle.

- **Bulk "data" emitter.** Walks streams round-robin, packs STREAM
  frames into packets up to cwnd / pacing budget, emits. Yields
  to the control emitter via a fair lock around the UDP write.

For receive there's a third thread — the UDP receive loop — that
decrypts, dispatches frames to the appropriate handlers, and
signals both emitters as needed.

The cost: three threads instead of one per connection. Worth it.

## TLS strategy

**Hand-rolled TLS 1.3 client, NOT BouncyCastle.** The original plan
was to bridge BouncyCastle's `TlsClientProtocol`. We abandoned it
for two reasons:

1. BC's API is TLS-record-oriented; QUIC carries raw handshake
   messages in CRYPTO frames. Stripping records out of BC was
   hackier than writing the state machine ourselves.
2. **BC must not be on the classpath at all.** Android bundles its
   own `org.bouncycastle.*` in the platform; a second full BC under
   the same package names corrupts the JCA provider chain and broke
   kwik QUIC entirely (confirmed by isolation test — kwik went from
   working to `ERR_TIMED_OUT` the moment `bcprov`/`bctls` were added,
   and recovered the moment they were removed). Any JCA crypto user
   in the process is collateral damage.

So `TlsClient.kt` is a minimal hand-rolled TLS 1.3 client (see its
header for the supported subset) and `TlsCrypto.kt` provides the
primitives:

- **X25519** via the platform `XDH` KeyAgreement (Conscrypt) — NOT
  BC. Available on API 33+ / our API 36 targets; older devices throw
  `NoSuchAlgorithmException` and fall back to kwik / TCP.
- **HKDF-Expand-Label, AES-128-GCM, SHA-256, HMAC** via the standard
  JDK `Cipher` / `Mac` / `MessageDigest` — on every Android since 21.
- ALPN `proxy-tunnel/1`, QUIC transport-parameter extension (we
  encode/decode it ourselves), server-cert verification disabled
  (identity is proven by the AUTH key on the control channel).
- Traffic secrets for Initial / Handshake / 1-RTT derived through
  the TLS 1.3 key schedule in `TlsCrypto.KeySchedule` (RFC 9001 §7).

## CC strategy — Brutal

`proxy-agent-sdk-go/internal/netagent/brutal/` implements the
Brutal algorithm in Go. We mirror it in Kotlin:

- Send at a configured target rate (default 100 Mbps).
- Pace packet emission via a leaky bucket on timestamps — emit
  next packet at `last_emit + packet_size / target_rate`.
- Ignore loss feedback beyond updating RTT estimates. Loss recovery
  still retransmits, but cwnd doesn't shrink.
- Window-size (bytes-in-flight cap) is `target_rate × max(RTT, 100ms)
  × 2` — generous, never the bottleneck on a clean link.

## Out-of-scope error handling

Any condition we don't handle cleanly closes the connection with
`CONNECTION_CLOSE` and an `INTERNAL_ERROR` code. The supervisor
reconnects. Don't paper over protocol errors silently — we'd never
catch the next bug.

## Anti-goals worth naming

- Reflection on internals. The whole point of writing this is to
  not have to reach into a third party's privates. If a future
  diff is tempted to reflect on something here, the design has a
  hole and the right fix is to widen the API.
- "Generic" QUIC. We are not building a library. We are building
  one client against one specific server. Resist generality.
- Premature optimization. Write straightforward code first; profile
  before tuning. The single thing we *are* eagerly optimizing is the
  ACK/window-update emission cadence — see the threading section.
