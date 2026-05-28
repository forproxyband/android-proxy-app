package com.proxyagent.app.nativeagent.quic.cc

import java.util.concurrent.atomic.AtomicLong

/**
 * Brutal congestion control — mirrors
 * `proxy-agent-sdk-go/internal/netagent/brutal/`. The whole
 * point of writing our own QUIC stack was so we could ship
 * this CC without fighting kwik for it.
 *
 * Algorithm (intentionally simple):
 *  - Send at a fixed target bandwidth (default 100 Mbps),
 *    paced via packet emission timestamps.
 *  - Ignore loss signals. The window only relaxes / tightens
 *    based on smoothed RTT * target rate * 2 (BDP × 2 for
 *    headroom).
 *  - RTT is updated from ACK samples for accurate pacing on
 *    long-RTT cellular paths.
 *
 * This is "trust the link" CC — no slow start, no
 * multiplicative decrease. Suitable when we know the link
 * isn't constrained by traditional loss/queue feedback (which
 * mobile Wi-Fi and 4G/5G usually aren't, despite what TCP
 * tuning literature assumes).
 */
internal class BrutalCongestionControl(
    targetBandwidthMbps: Int = 100,
    initialRttMs: Int = 100,
) {
    /** Bytes per second the pacer aims for. */
    val targetBytesPerSecond: Long = targetBandwidthMbps.toLong() * 125_000L

    /** Bytes in flight (sent, not yet acked). */
    private val _bytesInFlight = AtomicLong(0)
    val bytesInFlight: Long get() = _bytesInFlight.get()

    /** Smoothed RTT, nanoseconds. EWMA with α = 1/8 like TCP. */
    @Volatile var smoothedRttNanos: Long = initialRttMs * 1_000_000L
        private set

    /** Wall-clock-ish nanoTime at which the pacer permits the
     *  next byte to leave. Advanced on every emission. */
    @Volatile private var nextSendTimeNanos: Long = 0L

    /** Congestion window — bytes-in-flight ceiling. Sized as
     *  2 × BDP so we never gate sending purely on cwnd when
     *  the link is healthy; the pacer is the real rate limit. */
    val congestionWindow: Long
        get() = (targetBytesPerSecond * smoothedRttNanos / 1_000_000_000L * 2L).coerceAtLeast(64 * 1024L)

    /** Whether the next packet of [packetSize] bytes is allowed
     *  to leave **right now** (combined pacing + cwnd gate). */
    fun canSendNow(packetSize: Int): Boolean {
        if (bytesInFlight + packetSize > congestionWindow) return false
        return System.nanoTime() >= nextSendTimeNanos
    }

    /** Nanoseconds the caller should wait before re-checking
     *  [canSendNow], or 0 if it can send immediately. Cwnd
     *  exhaustion returns Long.MAX_VALUE — caller waits for an
     *  ACK to free up the in-flight budget. */
    fun waitNanos(packetSize: Int): Long {
        if (bytesInFlight + packetSize > congestionWindow) return Long.MAX_VALUE
        val now = System.nanoTime()
        return (nextSendTimeNanos - now).coerceAtLeast(0L)
    }

    /** Record that we emitted a [packetSize]-byte packet. Updates
     *  the pacer's next-send time and increments in-flight. */
    fun onPacketSent(packetSize: Int) {
        _bytesInFlight.addAndGet(packetSize.toLong())
        val now = System.nanoTime()
        val delayNanos = (packetSize.toLong() * 1_000_000_000L) / targetBytesPerSecond
        nextSendTimeNanos = maxOf(now, nextSendTimeNanos) + delayNanos
    }

    /** Record an ACK for a packet of [packetSize] bytes. Always frees
     *  the in-flight budget. Updates [smoothedRttNanos] (EWMA, α = 1/8)
     *  ONLY when [rttNanos] is positive — RFC 9002 §5 mandates that the
     *  RTT sample come from the **largest** acknowledged packet only,
     *  so callers pass 0 for every other acked packet. Sampling each
     *  acked packet would feed seconds-old `sentTime` values into the
     *  EWMA when a big batch is acked at once (e.g. the 100-pkt cwnd
     *  worth a server delivers in one ACK frame), inflating srtt to
     *  multiple seconds and ballooning [congestionWindow] to 100+ MiB —
     *  exactly what build-93's stats showed after a heavy burst. */
    fun onPacketAcked(packetSize: Int, rttNanos: Long) {
        _bytesInFlight.updateAndGet { (it - packetSize).coerceAtLeast(0L) }
        if (rttNanos > 0L) {
            val clamped = rttNanos.coerceAtLeast(1_000_000L)  // 1 ms floor
            smoothedRttNanos = (smoothedRttNanos * 7 + clamped) / 8
        }
    }

    /** Record that a sent packet was declared lost. Brutal
     *  doesn't shrink cwnd on loss — just frees the in-flight
     *  slot so the next packet can take its place. */
    fun onPacketLost(packetSize: Int) {
        _bytesInFlight.updateAndGet { (it - packetSize).coerceAtLeast(0L) }
    }
}
