package com.proxyagent.app.nativeagent.quic

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * RFC 9000 §16 variable-length integer.
 *
 * Encodes an unsigned integer in 1, 2, 4, or 8 bytes. The top two
 * bits of the first byte select the length:
 *
 * ```
 *   00xxxxxx                                                                                              → 1 byte,  6-bit value, 0..63
 *   01xxxxxx xxxxxxxx                                                                                     → 2 bytes, 14-bit value, 0..16_383
 *   10xxxxxx xxxxxxxx xxxxxxxx xxxxxxxx                                                                   → 4 bytes, 30-bit value, 0..1_073_741_823
 *   11xxxxxx xxxxxxxx xxxxxxxx xxxxxxxx xxxxxxxx xxxxxxxx xxxxxxxx xxxxxxxx                               → 8 bytes, 62-bit value, 0..4_611_686_018_427_387_903
 * ```
 *
 * Used absolutely everywhere in QUIC — packet numbers, frame
 * lengths, stream IDs, ACK ranges, transport parameters. Every
 * other module in this package depends on this one. Get it right.
 *
 * Implementation choices:
 *  - All public surface is `Long`-typed. Even though 1- and 2-byte
 *    forms fit in `Int`, mixing widths in callers is a footgun.
 *  - Encoders MUST pick the shortest form for a given value. RFC
 *    9000 §16 permits non-minimal encodings on the receive path
 *    but mandates minimal on send for endpoints; we err strict on
 *    both sides because the savings on wire are nonzero.
 *  - All I/O is via `ByteBuffer` so callers can compose us into
 *    larger packet builders without extra allocation.
 */
internal object Varint {

    /** Inclusive maximum value representable in the 8-byte form. */
    const val MAX_VALUE: Long = (1L shl 62) - 1L

    // Length thresholds: smallest value that requires the given form.
    private const val MAX_1BYTE: Long = (1L shl 6) - 1L          // 63
    private const val MAX_2BYTE: Long = (1L shl 14) - 1L         // 16_383
    private const val MAX_4BYTE: Long = (1L shl 30) - 1L         // 1_073_741_823

    /** Length-prefix mask (high two bits of the first byte). */
    private const val LEN_MASK: Int = 0xC0  // 0b11000000

    /**
     * Encoded length in bytes for [value]. Useful for sizing a
     * packet buffer before we have somewhere to write to. Returns
     * 1, 2, 4, or 8.
     */
    fun encodedLength(value: Long): Int {
        require(value >= 0L) { "varint cannot be negative: $value" }
        return when {
            value <= MAX_1BYTE -> 1
            value <= MAX_2BYTE -> 2
            value <= MAX_4BYTE -> 4
            value <= MAX_VALUE -> 8
            else -> throw IllegalArgumentException(
                "varint exceeds 62-bit range: $value (max $MAX_VALUE)"
            )
        }
    }

    /**
     * Encode [value] into [dst] at its current position, advancing
     * the position by [encodedLength]. Network byte order. Picks
     * the shortest form.
     *
     * Throws if [value] is negative or exceeds [MAX_VALUE].
     * Throws `BufferOverflowException` if [dst] doesn't have room.
     */
    fun encode(dst: ByteBuffer, value: Long) {
        require(value >= 0L) { "varint cannot be negative: $value" }
        require(value <= MAX_VALUE) {
            "varint exceeds 62-bit range: $value (max $MAX_VALUE)"
        }
        val saved = dst.order()
        dst.order(ByteOrder.BIG_ENDIAN)
        try {
            when {
                value <= MAX_1BYTE -> {
                    // High bits already 00 — just write the byte.
                    dst.put(value.toByte())
                }
                value <= MAX_2BYTE -> {
                    // 14-bit value in low bits; OR the 01 length prefix
                    // into the most significant byte of the 16-bit word.
                    val word = (value.toInt() or (0x40 shl 8)).toShort()
                    dst.putShort(word)
                }
                value <= MAX_4BYTE -> {
                    val word = value.toInt() or (0x80 shl 24)
                    dst.putInt(word)
                }
                else -> {
                    // 62-bit value; OR the 11 prefix into the high byte
                    // of the 64-bit word. Top 2 bits of value are
                    // guaranteed 0 by the MAX_VALUE check above.
                    val word = value or (0xC0L shl 56)
                    dst.putLong(word)
                }
            }
        } finally {
            dst.order(saved)
        }
    }

    /**
     * Decode a varint from [src] at its current position, advancing
     * the position by the decoded length. Returns the value as
     * `Long`. Reads 1, 2, 4, or 8 bytes depending on the length
     * prefix in the first byte.
     *
     * Throws `BufferUnderflowException` if [src] runs out of bytes
     * mid-value (e.g. claims 8-byte form but only 5 bytes remain).
     */
    fun decode(src: ByteBuffer): Long {
        val saved = src.order()
        src.order(ByteOrder.BIG_ENDIAN)
        try {
            val first = src.get().toInt() and 0xFF
            val prefix = first and LEN_MASK
            val firstPayload = (first and 0x3F).toLong()  // low 6 bits

            return when (prefix) {
                0x00 -> firstPayload                                    // 1 byte
                0x40 -> (firstPayload shl 8) or                          // 2 bytes
                        (src.get().toLong() and 0xFFL)
                0x80 -> (firstPayload shl 24) or                         // 4 bytes
                        ((src.get().toLong() and 0xFFL) shl 16) or
                        ((src.get().toLong() and 0xFFL) shl 8) or
                        (src.get().toLong() and 0xFFL)
                0xC0 -> (firstPayload shl 56) or                         // 8 bytes
                        ((src.get().toLong() and 0xFFL) shl 48) or
                        ((src.get().toLong() and 0xFFL) shl 40) or
                        ((src.get().toLong() and 0xFFL) shl 32) or
                        ((src.get().toLong() and 0xFFL) shl 24) or
                        ((src.get().toLong() and 0xFFL) shl 16) or
                        ((src.get().toLong() and 0xFFL) shl 8) or
                        (src.get().toLong() and 0xFFL)
                // Unreachable: prefix is the result of (x and 0xC0),
                // which always lands on one of {0x00, 0x40, 0x80, 0xC0}.
                else -> error("varint prefix masked to $prefix — impossible")
            }
        } finally {
            src.order(saved)
        }
    }

    /**
     * Peek the length-prefix of the varint at [src]'s current
     * position without consuming any bytes. Useful for packet
     * parsers that need to size-check a field before committing.
     * Returns 1, 2, 4, or 8.
     *
     * Throws `BufferUnderflowException` if [src] is empty.
     */
    fun peekLength(src: ByteBuffer): Int {
        val first = src.get(src.position()).toInt() and 0xFF
        return when (first and LEN_MASK) {
            0x00 -> 1
            0x40 -> 2
            0x80 -> 4
            0xC0 -> 8
            else -> error("unreachable")
        }
    }
}
