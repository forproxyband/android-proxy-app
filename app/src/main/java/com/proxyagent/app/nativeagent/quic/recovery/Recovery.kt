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
     *  (and clears) this via [consumeAckPending] so we emit an ACK
     *  ONLY when there's something new to acknowledge — RFC 9000
     *  §13.2.1. Without this gate we flooded ~50 ACK-only packets/sec
     *  (one per sender-loop tick), which looks like a misbehaving
     *  client to quic-go's flood protection and burned packet-number
     *  space. */
    private var ackPending = false
    private val lock = ReentrantLock()

    /** Result of detectLost: frames to re-send + bytes freed. */
    data class LossOutput(val framesToRetransmit: List<Frame>, val bytesLost: Long)

    /** Called when a packet has been emitted on the wire. */
    fun onPacketSent(p: SentPacket) = lock.withLock {
        sent[p.packetNumber] = p
        if (p.ackEliciting) ackElicitingOutstanding++
    }

    /** Called when we receive a packet (any packet). Used to
     *  build the next ACK frame. Sets [ackPending] when the packet
     *  is ack-eliciting so the sender knows to emit an ACK. */
    fun onPacketReceived(packetNumber: Long, ackEliciting: Boolean) = lock.withLock {
        received.add(packetNumber)
        if (ackEliciting) ackPending = true
        if (received.size > MAX_RANGES_BUFFERED) {
            // Drop the smallest entries — they're so old the peer
            // has stopped caring. Keeps the ACK-frame size bounded.
            received.pollFirst()
        }
    }

    /** Returns true (and clears the flag) if we owe the peer an ACK
     *  for ack-eliciting packets received since our last ACK. The
     *  sender calls this once per loop tick; only when it returns
     *  true do we actually build + emit an ACK frame. */
    fun consumeAckPending(): Boolean = lock.withLock {
        val p = ackPending
        ackPending = false
        p
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

    fun largestAckedSentTime(): Long = lock.withLock { largestAckedSentTime }
    fun largestAckedSent(): Long = lock.withLock { largestAckedSent }

    companion object {
        /** RFC 9002 §6.1.1 packet threshold. */
        const val LOSS_THRESHOLD: Int = 3
        /** Cap on tracked received-PN set; older entries get dropped. */
        const val MAX_RANGES_BUFFERED: Int = 1024
    }
}
