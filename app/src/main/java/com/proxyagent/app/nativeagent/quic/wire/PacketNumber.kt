package com.proxyagent.app.nativeagent.quic.wire

import java.nio.ByteBuffer

/**
 * Packet number wire encoding — RFC 9000 §17.1 and §A.2 / §A.3.
 *
 * Packet numbers are conceptually a 62-bit monotonically
 * increasing integer per packet-number space, but the wire
 * format only carries the low 1, 2, 3, or 4 bytes (truncated).
 * The receiver reconstructs the full PN using its current
 * "largest received" anchor in that space.
 *
 * Important: this is **separate** from header protection
 * (RFC 9001 §5.4). The protection is an XOR mask applied
 * on top of the truncated bytes; this object operates on
 * already-unmasked bytes (or, on the encode side, on the
 * raw bytes prior to protection).
 *
 * The truncation length is **encoded in the protected low 2
 * bits of the first byte** as `length - 1`. So:
 *  - 0x00 → 1 byte
 *  - 0x01 → 2 bytes
 *  - 0x02 → 3 bytes
 *  - 0x03 → 4 bytes
 *
 * The sender picks the shortest length such that the receiver
 * can unambiguously decode the PN, given the receiver's
 * largest-acked-by-peer in the same space.
 */
internal object PacketNumber {

    /**
     * Compute the minimum number of bytes (1..4) needed to encode
     * [pn] such that a receiver whose largest-received-PN-in-space
     * is at least [largestAcked] can unambiguously decode it.
     *
     * RFC 9000 §17.1 formula: bits needed = ceil(log2(2 * range))
     * where range = `pn - largestAcked`. We round up to whole bytes
     * and clamp to the protocol minimum/maximum [1, 4].
     *
     * Pass `largestAcked = -1` to indicate the peer hasn't acked
     * anything yet — in that case use the full PN width (no peer
     * anchor, can't safely truncate).
     */
    fun encodingLength(pn: Long, largestAcked: Long): Int {
        require(pn >= 0) { "PN must be non-negative: $pn" }
        if (largestAcked < 0) {
            // Without an anchor, the peer can't expand; use the
            // tightest fit for the absolute value. This is the
            // initial-packet case before we've received any ACKs.
            return byteCountFor(pn)
        }
        val range = pn - largestAcked
        require(range >= 0) { "PN $pn behind largestAcked $largestAcked" }
        if (range == 0L) return 1  // identical to largestAcked, 1 byte suffices
        // 2 * range with +1 to push us into the next byte boundary
        // when range exactly fits (defensive — RFC says "more than
        // twice", strict greater-than).
        val bits = 64 - java.lang.Long.numberOfLeadingZeros(2 * range + 1)
        val bytes = (bits + 7) / 8
        return bytes.coerceIn(1, 4)
    }

    /**
     * Write the low [length] bytes of [pn] into [dst] in
     * network byte order (big-endian). Caller has already
     * determined [length] via [encodingLength].
     */
    fun encode(pn: Long, length: Int, dst: ByteBuffer) {
        require(length in 1..4) { "PN length must be 1..4, got $length" }
        when (length) {
            1 -> dst.put(pn.toByte())
            2 -> dst.putShort(pn.toShort())
            3 -> {
                dst.put((pn ushr 16).toByte())
                dst.put((pn ushr 8).toByte())
                dst.put(pn.toByte())
            }
            4 -> dst.putInt(pn.toInt())
        }
    }

    /**
     * Read [length] bytes from [src] as a truncated PN and
     * expand to the full 62-bit form using [largestRecvd] (the
     * largest PN already received in this packet number space,
     * or -1 if none yet) as the disambiguation anchor.
     *
     * Algorithm from RFC 9000 §A.3: pick the candidate full PN
     * whose low [length] bytes match the truncated value and
     * which is closest to `largestRecvd + 1`.
     */
    fun decode(src: ByteBuffer, length: Int, largestRecvd: Long): Long {
        require(length in 1..4) { "PN length must be 1..4, got $length" }
        val truncated: Long = when (length) {
            1 -> (src.get().toLong() and 0xFFL)
            2 -> (src.short.toLong() and 0xFFFFL)
            3 -> ((src.get().toLong() and 0xFFL) shl 16) or
                 ((src.get().toLong() and 0xFFL) shl 8) or
                 (src.get().toLong() and 0xFFL)
            4 -> (src.int.toLong() and 0xFFFFFFFFL)
            else -> error("unreachable")
        }
        if (largestRecvd < 0) {
            // First packet in the space; truncated value IS the
            // full value (PN starts at 0).
            return truncated
        }
        val pnWindow = 1L shl (length * 8)
        val pnHalfWindow = pnWindow / 2
        val pnMask = pnWindow - 1
        val expected = largestRecvd + 1
        // Snap "expected" to the same low-bits as truncated.
        val candidate = (expected and pnMask.inv()) or truncated
        // Pick the version of candidate (±1 window) closest to expected.
        return when {
            candidate <= expected - pnHalfWindow &&
                candidate < (1L shl 62) - pnWindow ->
                candidate + pnWindow
            candidate > expected + pnHalfWindow && candidate >= pnWindow ->
                candidate - pnWindow
            else -> candidate
        }
    }

    /** Internal helper: minimum byte count to hold [n] unsigned. */
    private fun byteCountFor(n: Long): Int {
        if (n == 0L) return 1
        val bits = 64 - java.lang.Long.numberOfLeadingZeros(n)
        return ((bits + 7) / 8).coerceIn(1, 4)
    }
}
