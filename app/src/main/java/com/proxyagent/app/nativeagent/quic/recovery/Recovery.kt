package com.proxyagent.app.nativeagent.quic.recovery

import com.proxyagent.app.nativeagent.quic.wire.Ack
import com.proxyagent.app.nativeagent.quic.wire.Frame
import com.proxyagent.app.nativeagent.quic.wire.PacketNumberSpace
import java.util.TreeSet
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Loss recovery and ACK generation (RFC 9002), one instance
 * per packet number space.
 *
 * **What's implemented:**
 *  - Sent-packet bookkeeping: PN → frames sent, time sent, byte
 *    size, ack-eliciting flag.
 *  - ACK frame generation from received packet numbers, in
 *    descending contiguous-range form.
 *  - Loss detection by packet threshold (RFC 9002 §6.1.1):
 *    a packet is lost when 3 packets with higher numbers have
 *    been acknowledged.
 *  - RTT sampling from ACK frames.
 *
 * **What's NOT implemented (for our pragmatic minimum):**
 *  - Time-threshold loss detection (we rely on the packet
 *    threshold + the eventual PTO from the connection layer).
 *  - PTO (probe timeout). The connection layer's idle timeout
 *    catches us when nothing's moving.
 *  - ACK ranges with gap encoding for sparse received sets
 *    larger than ~256 packets — we coalesce on insertion so
 *    the working set stays small.
 *
 * **Thread safety.** Internally locked — both the sender thread
 * (calls onPacketSent / pollAcksToSend / detectLost) and the
 * receiver thread (calls onPacketReceived / processAckFrame)
 * access this from different stacks.
 */
internal class SpaceRecovery(val space: PacketNumberSpace) {

    /** A packet we sent and haven't seen acked or declared lost yet. */
    data class SentPacket(
        val packetNumber: Long,
        val sentTimeNanos: Long,
        val sizeBytes: Int,
        val ackEliciting: Boolean,
        val inFlight: Boolean,
        /** Frames the packet carries; on loss we mark each one for
         *  retransmission (RFC 9002 §6.3 — frames, not packets, are
         *  retransmitted). */
        val frames: List<Frame>,
    )

    private val sent = LinkedHashMap<Long, SentPacket>()  // PN-ordered, insertion-order
    private val received = TreeSet<Long>()                // for ACK generation
    private var largestAckedSent: Long = -1L
    private var largestAckedSentTime: Long = -1L
    private var ackElicitingOutstanding: Long = 0L
    /** True when we've received an ack-eliciting packet that hasn't
     *  yet been covered by an ACK frame we sent. The sender consults
     *  (and clears) this via [consumeAndTakeAckDelay] so we emit an
     *  ACK ONLY when there's something new to acknowledge — RFC 9000
     *  §13.2.1. Without this gate we flooded ~50 ACK-only packets/sec
     *  (one per sender-loop tick), which looks like a misbehaving
     *  client to quic-go's flood protection and burned packet-number
     *  space. */
    private var ackPending = false
    /** Largest PN of an ack-eliciting packet that's still waiting to be
     *  ACKed by the sender. -1 = nothing waiting. Drives the wire-format
     *  ack_delay field (RFC 9000 §19.3 defines ack_delay as the time
     *  from receiving *largestAcked* until emission of the ACK frame). */
    private var largestPendingAckPn: Long = -1L
    /** Wall-time when [largestPendingAckPn] was received. */
    private var largestPendingAckTimeNanos: Long = 0L
    /** Count of ack-eliciting packets received since our last ACK
     *  emission. Drives the immediate-ACK threshold (RFC 9000 §13.2.2
     *  recommends generating an ACK frame for at least every other
     *  ack-eliciting packet — i.e. don't let the count grow without
     *  bound or peer's CC starves on ack-clock feedback). When this
     *  counter crosses [IMMEDIATE_ACK_THRESHOLD], [onPacketReceived]
     *  returns true so the receiver thread can wake the sender for
     *  prompt emission instead of waiting out the sender's poll
     *  interval (up to 20 ms idle). */
    private var pendingAckElicitingCount: Int = 0
    private val lock = ReentrantLock()

    /** Result of detectLost: frames to re-send + bytes freed. */
    data class LossOutput(val framesToRetransmit: List<Frame>, val bytesLost: Long)

    /** Atomically reports the ACK-emission decision so the sender can
     *  build a correctly-stamped ACK frame in one critical section.
     *  See [consumeAndTakeAckDelay]. */
    data class AckEmission(
        /** True when ack-eliciting packets have been received since
         *  our last ACK and the sender owes one now. */
        val shouldEmit: Boolean,
        /** Nanoseconds between receiving the largest-acked ack-eliciting
         *  packet and this call — what the sender should pass to
         *  [buildAckFrame] so quic-go on the peer subtracts the right
         *  value from its RTT samples (RFC 9000 §19.3). */
        val ackDelayNanos: Long,
    )

    /** Called when a packet has been emitted on the wire. */
    fun onPacketSent(p: SentPacket) = lock.withLock {
        sent[p.packetNumber] = p
        if (p.ackEliciting) ackElicitingOutstanding++
    }

    /** Called when we receive a packet (any packet). Updates the ACK-
     *  generation state and returns true if the receiver thread should
     *  wake the sender for an immediate ACK. The wake is gated by
     *  [IMMEDIATE_ACK_THRESHOLD] (every Nth ack-eliciting packet) so
     *  high-pps inbound flows don't pin the sender CPU on a wake-per-
     *  packet treadmill; in between thresholds the sender's natural
     *  poll cadence handles ACK emission as before. */
    fun onPacketReceived(packetNumber: Long, ackEliciting: Boolean): Boolean = lock.withLock {
        received.add(packetNumber)
        var wake = false
        if (ackEliciting) {
            ackPending = true
            if (packetNumber > largestPendingAckPn) {
                largestPendingAckPn = packetNumber
                largestPendingAckTimeNanos = System.nanoTime()
            }
            pendingAckElicitingCount++
            if (pendingAckElicitingCount >= IMMEDIATE_ACK_THRESHOLD) wake = true
        }
        if (received.size > MAX_RANGES_BUFFERED) {
            // Drop the smallest entries — they're so old the peer
            // has stopped caring. Keeps the ACK-frame size bounded.
            received.pollFirst()
        }
        wake
    }

    /**
     * Single critical section that consumes [ackPending] AND takes the
     * delay measurement for the largest-acked packet. The sender must
     * call this exactly when it is about to emit the ACK frame so the
     * reported delay matches reality.
     *
     * Reset behaviour: clears the pending-count and largest-acked
     * tracking; subsequent ack-eliciting packets start a fresh batch.
     * Note that the [Ack] frame built right after this call will cover
     * ALL packets currently in [received] (including any that arrive
     * between this call and [buildAckFrame]); only the *delay* field
     * is locked to the snapshot taken here. The extra (microsecond-
     * scale) error is below the wire encoding's resolution.
     */
    fun consumeAndTakeAckDelay(now: Long): AckEmission = lock.withLock {
        val emit = ackPending
        val refTime = largestPendingAckTimeNanos
        ackPending = false
        largestPendingAckPn = -1L
        largestPendingAckTimeNanos = 0L
        pendingAckElicitingCount = 0
        val delay = if (refTime == 0L) 0L else (now - refTime).coerceAtLeast(0L)
        AckEmission(emit, delay)
    }

    /**
     * Process an incoming ACK frame for packets we previously
     * sent. Returns the list of acked SentPackets so the caller
     * can update its CC (`onPacketAcked` per packet) and free
     * any flow-control / stream credit tied up.
     */
    fun processAckFrame(ack: Ack, ackDelayExponent: Int): List<SentPacket> = lock.withLock {
        val acked = mutableListOf<SentPacket>()
        // The ACK covers every PN in each range. We iterate the
        // ranges and pluck matching sent packets.
        for (range in ack.ranges) {
            for (pn in range) {
                sent.remove(pn)?.let { p ->
                    if (p.ackEliciting) ackElicitingOutstanding = (ackElicitingOutstanding - 1).coerceAtLeast(0L)
                    acked.add(p)
                    if (p.packetNumber > largestAckedSent) {
                        largestAckedSent = p.packetNumber
                        largestAckedSentTime = p.sentTimeNanos
                    }
                }
            }
        }
        acked
    }

    /**
     * Run the packet-threshold loss detector. Any packet with
     * a PN at least [LOSS_THRESHOLD] less than [largestAckedSent]
     * and still in [sent] is declared lost; its frames are
     * returned for retransmission.
     */
    fun detectLost(): LossOutput = lock.withLock {
        if (largestAckedSent < 0) return@withLock LossOutput(emptyList(), 0L)
        val frames = mutableListOf<Frame>()
        var bytesLost = 0L
        val iter = sent.entries.iterator()
        while (iter.hasNext()) {
            val (pn, p) = iter.next()
            if (largestAckedSent - pn >= LOSS_THRESHOLD) {
                iter.remove()
                if (p.ackEliciting) ackElicitingOutstanding = (ackElicitingOutstanding - 1).coerceAtLeast(0L)
                frames.addAll(p.frames)
                if (p.inFlight) bytesLost += p.sizeBytes
            }
        }
        LossOutput(frames, bytesLost)
    }

    /** Build an ACK frame covering everything in [received] that
     *  hasn't been acked yet. Returns null if nothing to ACK. */
    fun buildAckFrame(ackDelayNanos: Long, ackDelayExponent: Int): Ack? = lock.withLock {
        if (received.isEmpty()) return@withLock null
        val descending = received.descendingIterator().asSequence().toList()
        val ranges = mutableListOf<LongRange>()
        var rangeStart = descending[0]
        var rangeEnd = descending[0]
        for (i in 1 until descending.size) {
            val pn = descending[i]
            if (pn == rangeStart - 1) {
                rangeStart = pn
            } else {
                ranges.add(rangeStart..rangeEnd)
                rangeStart = pn
                rangeEnd = pn
            }
        }
        ranges.add(rangeStart..rangeEnd)
        // Scale ack delay by 2^exponent.
        val scaled = (ackDelayNanos / 1_000L) shr ackDelayExponent  // microseconds → scaled
        Ack(
            largestAcked = ranges[0].last,
            ackDelayScaled = scaled.coerceAtLeast(0L),
            ranges = ranges,
        )
    }

    fun hasAckEliciting(): Boolean = lock.withLock { ackElicitingOutstanding > 0 }

    /** Number of packets still tracked (sent, not yet acked or
     *  declared lost). Diagnostic + PTO gating. */
    fun outstandingCount(): Int = lock.withLock { sent.size }

    /** What [takeOldestAckElicitingForRetransmit] hands back. */
    data class Retransmittable(val frames: List<Frame>, val sizeBytes: Int, val inFlight: Boolean)

    /**
     * PTO probe support: remove and return the OLDEST still-unacked
     * ack-eliciting packet so the caller can retransmit its frames —
     * with their ORIGINAL stream offsets preserved — in a fresh
     * packet, and free the original's in-flight bytes. Returns null
     * if nothing ack-eliciting is outstanding.
     *
     * We remove it (treat as lost) rather than leave it tracked so
     * in-flight accounting doesn't inflate across repeated PTOs; a
     * late ACK for the original simply won't find it in [sent], which
     * is harmless (the retransmit carries the same bytes/offset).
     */
    fun takeOldestAckElicitingForRetransmit(): Retransmittable? = lock.withLock {
        val it = sent.entries.iterator()
        while (it.hasNext()) {
            val (_, p) = it.next()
            if (p.ackEliciting) {
                it.remove()
                ackElicitingOutstanding = (ackElicitingOutstanding - 1).coerceAtLeast(0L)
                return@withLock Retransmittable(p.frames, p.sizeBytes, p.inFlight)
            }
        }
        null
    }

    fun largestAckedSentTime(): Long = lock.withLock { largestAckedSentTime }
    fun largestAckedSent(): Long = lock.withLock { largestAckedSent }

    companion object {
        /** RFC 9002 §6.1.1 packet threshold. */
        const val LOSS_THRESHOLD: Int = 3
        /** Cap on tracked received-PN set; older entries get dropped. */
        const val MAX_RANGES_BUFFERED: Int = 1024
        /** Every Nth ack-eliciting packet wakes the sender for an
         *  immediate ACK. Picked at 10 because:
         *  - RFC 9000 §13.2.2 strongly recommends ACKing every 2 to
         *    keep peer CC's ack-clock fed.
         *  - At 100 Mbps inbound (≈10 kpps) waking on every packet
         *    pins the sender CPU on wakeup churn; every-10 yields
         *    ~1000 wakes/s and ~1 ms ACK delay — well under the
         *    advertised `max_ack_delay` (25 ms) and the old idle-poll
         *    cadence (20 ms) that left peer's quic-go starved on
         *    ack-clock feedback in duplex tests (build 99: QUIC
         *    upload tanked to single-digit Mbps while TCP duplex
         *    held 300 Mbps both directions).
         *
         *  Lower → faster RX-direction throughput, more CPU on the
         *  sender thread; higher → less CPU, more inflation of peer's
         *  RTT estimate (peer's BBR/CUBIC throttles cwnd growth on
         *  inflated RTT). */
        const val IMMEDIATE_ACK_THRESHOLD: Int = 10
    }
}
