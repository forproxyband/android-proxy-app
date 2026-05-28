package com.proxyagent.app.nativeagent.quic

/**
 * User-selectable preset that scales the agent's network tuning to
 * the phone↔relay link's expected ceiling. Picked from a single
 * Settings dropdown ("Network optimization"); the agent renders each
 * preset into TWO independent parameter sets (TCP and QUIC) because
 * the two transports have different knobs and different failure modes.
 *
 * Why presets and not raw sliders: the latency-vs-throughput tradeoff
 * is multi-dimensional (pacer rate, kernel buffers, app buffers, flow-
 * control cadence) and the dimensions interact — fat buffers + slow
 * pacer = bufferbloat, slim buffers + fast pacer = under-utilization.
 * Field operators pick the link they're on; the agent does the rest.
 *
 * The QUIC initial flow-control windows (initialMaxData /
 * initialMaxStreamData) are intentionally NOT scaled by the profile —
 * see the build-93 comment in NativeQuicTransport.Factory; shrinking
 * them stalled uploads through the upstream proxy. The Brutal CC
 * pacer is the dominant rate limit anyway, so leaving the windows
 * generous costs nothing.
 */
enum class NetworkProfile {
    /** Mobile / Wi-Fi up to ~100 Mbps. Latency-optimized. Default. */
    LOW_100,
    /** ~500 Mbps-class links. Balanced. */
    MID_500,
    /** Gigabit-class uplink. Throughput-optimized. */
    HIGH_1000;

    companion object {
        /** Parse a SharedPreferences / intent-extra string. Unknown
         *  values (including null) collapse to [LOW_100] — the safe
         *  default for unconfigured devices. */
        @JvmStatic
        fun fromPrefValue(raw: String?): NetworkProfile = when (raw) {
            "MID_500" -> MID_500
            "HIGH_1000" -> HIGH_1000
            else -> LOW_100
        }
    }
}

/**
 * TCP-stack knobs. Applied to every TCP socket the agent opens
 * (uplink, target dials, warm-pool members) before connect().
 */
data class TcpTuning(
    /** Per-socket SO_RCVBUF / SO_SNDBUF hint. OS may clamp at
     *  net.core.{r,s}mem_max — we ask, the kernel decides. Sized
     *  for BDP at the profile's target rate × ~100 ms RTT. */
    val socketBufferBytes: Int,
    /** Userspace bridge buffer used by the bidirectional copy
     *  between the tunnel transport and the dialed target. Smaller
     *  = more frequent flushes = lower per-direction latency.
     *  Larger = fewer syscalls per byte = more throughput. */
    val bridgeBufferBytes: Int,
)

/**
 * QUIC-stack knobs. Applied to the in-house native QUIC stack only;
 * the legacy kwik adapter ignores them (kwik is on its way out, see
 * the dialog_settings.xml comment about the picker removal), and the
 * libproxyagent.so binary engine has no way to receive them at all.
 */
data class QuicTuning(
    /** Brutal CC target rate. Gates the pacer's inter-packet delay
     *  AND the cwnd ceiling (cwnd = 2 × BDP at this rate). */
    val brutalTargetMbps: Int,
    /** UDP socket buffer hint (receive + send). Big buffers + slow
     *  pacer = bufferbloat, so this scales with the pacer rate. */
    val udpSocketBufBytes: Int,
    /** Fraction of `initialOurMaxData` (the per-side initial flow
     *  control window) at which remaining headroom drops low enough
     *  to trigger a MAX_DATA update. 0.5 = the legacy "half consumed"
     *  trigger; higher = update sooner = less head-of-line waiting
     *  in the receive direction at the cost of more control-frame
     *  overhead. */
    val windowUpdateHeadroomRatio: Double,
)

/** Concrete tunings for one profile. Picked once at agent startup
 *  and pinned for the lifetime of that supervisor run; toggling the
 *  preset requires a tunnel restart (parameters ride the QUIC
 *  handshake, can't be renegotiated). */
data class ProfileTuning(
    val tcp: TcpTuning,
    val quic: QuicTuning,
)

/**
 * Resolve a profile to its concrete tunings. Pure function, safe to
 * call repeatedly; we still memoize at one call site (the supervisor)
 * to keep the log line's reported values stable across reads.
 *
 * BDP math: 100 ms RTT × target bandwidth, doubled for headroom.
 * 100 Mbps → 1.25 MB, 500 Mbps → 6.25 MB, 1000 Mbps → 12.5 MB.
 * TCP socket buffer floors the doubled value; UDP socket buffer
 * over-sizes it (QUIC bursts can spike higher than TCP's paced
 * fill).
 */
fun NetworkProfile.tuning(): ProfileTuning = when (this) {
    NetworkProfile.LOW_100 -> ProfileTuning(
        tcp = TcpTuning(
            socketBufferBytes = 1_500_000,        // ~1.5 MiB ≈ 100 Mbps × 100 ms × 1.2
            bridgeBufferBytes = 64 * 1024,        // small flushes → low per-hop latency
        ),
        quic = QuicTuning(
            brutalTargetMbps = 100,
            udpSocketBufBytes = 4 * 1024 * 1024,  // ~4 MiB; 32 MiB at 100 Mbps = 2.5s bufferbloat
            windowUpdateHeadroomRatio = 0.75,     // refresh aggressively (at 25% consumed)
        ),
    )
    NetworkProfile.MID_500 -> ProfileTuning(
        tcp = TcpTuning(
            socketBufferBytes = 6 * 1024 * 1024,
            bridgeBufferBytes = 128 * 1024,
        ),
        quic = QuicTuning(
            brutalTargetMbps = 500,
            udpSocketBufBytes = 16 * 1024 * 1024,
            windowUpdateHeadroomRatio = 0.60,
        ),
    )
    NetworkProfile.HIGH_1000 -> ProfileTuning(
        tcp = TcpTuning(
            socketBufferBytes = 12 * 1024 * 1024,
            bridgeBufferBytes = 256 * 1024,
        ),
        quic = QuicTuning(
            brutalTargetMbps = 1000,
            udpSocketBufBytes = 32 * 1024 * 1024,
            windowUpdateHeadroomRatio = 0.50,     // legacy threshold
        ),
    )
}
