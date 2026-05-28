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
         *  values (including null) collapse to [LOW_100] — the
         *  default for unconfigured devices. Picked because most
         *  mobile/Wi-Fi uplinks fall below 100 Mbps in practice, and
         *  LOW_100's smaller kernel queue bounds worst-case
         *  bufferbloat tighter than the higher profiles. Field tests
         *  (see NETWORK_PROFILE_TUNING.md) confirm LOW_100 still
         *  delivers full multi-flow throughput on gigabit links
         *  through parallel target dials, so users on fast networks
         *  aren't penalised by the safe default. */
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
    /** Per-socket SO_RCVBUF / SO_SNDBUF hint. Bounded to a 1.5–4 MiB
     *  safe zone empirically — see the kdoc on [tuning] for the
     *  three field-test data points (broken at 12 MiB, broken at
     *  1 MiB, clean at 1.5 MiB) that pinned the corners. Within
     *  the safe zone, larger = more per-flow BDP headroom, smaller
     *  = less potential bufferbloat at the target rate. */
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
 * **TCP SO_*BUF lives in a 1.5–4 MiB safe zone**, empirically
 * bounded by two symmetric field-test regressions on Android:
 *  - **12 MiB at HIGH_1000** regressed upload to ~5 Mbps on Samsung
 *    S Fold 7 (q7q, kernel 6.6.98). Hypothesis: above
 *    `net.core.wmem_max` the kernel clamps the request AND disables
 *    `tcp_wmem` auto-tuning, leaving the effective send window
 *    below where auto-tune would have grown it.
 *  - **1 MiB at LOW_100** regressed upload to ~5 Mbps on Pixel 9
 *    Pro XL (komodo, kernel 6.1.145) — identical symptom from the
 *    other direction. Hypothesis: below `tcp_rmem`/`tcp_wmem`
 *    defaults the explicit value also disables auto-tuning, and
 *    `tcp_adv_win_scale` overhead eats too much of the small
 *    buffer for window scaling to keep up under multi-flow load.
 *  - **1.5 MiB at LOW_100** was the bisection retest on the same
 *    Pixel and ran clean (331/299 Mbps with idle ping 84 ms) — so
 *    the lower edge of the safe zone sits between 1.0 and 1.5 MiB.
 *
 * So 4 MiB stays the ceiling (HIGH_1000, matches the pre-profile
 * hardcoded value), 1.5 MiB the floor (LOW_100). The
 * latency-vs-throughput tradeoff at LOW_100 still gets some help
 * from the smaller buffer (~120 ms max bufferbloat at saturation
 * vs ~160 ms at 2 MiB), but the dominant TCP-side knob remains
 * the **bridge buffer**.
 *
 * QUIC scales all three of its dimensions independently — its
 * userspace pacer + UDP socket queue interact differently from
 * TCP's in-kernel cwnd, so the same scaling logic doesn't apply.
 */
fun NetworkProfile.tuning(): ProfileTuning = when (this) {
    NetworkProfile.LOW_100 -> ProfileTuning(
        tcp = TcpTuning(
            socketBufferBytes = 1_536 * 1024,     // 1.5 MiB — confirmed floor on Android; 1 MiB broke upload, 1.5 ran clean
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
            socketBufferBytes = 2 * 1024 * 1024,
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
            socketBufferBytes = 4 * 1024 * 1024,  // anchor: matches the pre-profile hardcoded value
            bridgeBufferBytes = 256 * 1024,       // matches the pre-profile hardcoded value
        ),
        quic = QuicTuning(
            brutalTargetMbps = 1000,
            udpSocketBufBytes = 32 * 1024 * 1024,
            windowUpdateHeadroomRatio = 0.50,     // legacy threshold
        ),
    )
}
