package com.proxyagent.app.nativeagent.quic.wire

import com.proxyagent.app.nativeagent.quic.Varint
import java.nio.ByteBuffer

// File-private constants reused by Frame and its sealed subtypes.
// Top-level `private` is file-scoped in Kotlin, which is what we
// want — keeps these out of the public API of `Frame.Companion`
// while still being reachable by the subtypes declared in this file.
private val SPACES_ALL = setOf(
    PacketNumberSpace.INITIAL,
    PacketNumberSpace.HANDSHAKE,
    PacketNumberSpace.ONE_RTT,
)
private val SPACES_ONE_RTT_ONLY = setOf(PacketNumberSpace.ONE_RTT)

/**
 * QUIC frames (RFC 9000 §19).
 *
 * The packet payload — once removed from AEAD protection — is a
 * concatenation of frames. Each frame starts with a type byte
 * (1-byte for the types we use, though the spec allows varint
 * types for future extensions; v1 only uses single-byte types
 * 0x00–0x1e).
 *
 * Frames we generate ourselves:
 *  - PADDING (in Initial packets to reach MTU)
 *  - PING (for keepalive / PTO probes)
 *  - ACK (in response to ack-eliciting packets)
 *  - CRYPTO (TLS handshake messages)
 *  - STREAM (application data)
 *  - MAX_DATA / MAX_STREAM_DATA / MAX_STREAMS (flow control updates
 *    we owe the peer — emitted on a separate cadence from STREAM
 *    frames; see DESIGN.md)
 *  - CONNECTION_CLOSE (on protocol violation or explicit close)
 *
 * Frames we receive and act on:
 *  - All of the above (reciprocally)
 *  - HANDSHAKE_DONE (server signals 1-RTT keys confirmed)
 *  - NEW_TOKEN (we just store and ignore — no 0-RTT, no token reuse)
 *
 * Frames we receive and skip:
 *  - RESET_STREAM, STOP_SENDING (would matter for full stream
 *    state machine; for now we close the stream defensively)
 *  - DATA_BLOCKED / STREAM_DATA_BLOCKED / STREAMS_BLOCKED (purely
 *    informational, peer telling us they want more credit)
 *  - NEW_CONNECTION_ID / RETIRE_CONNECTION_ID (no migration)
 *  - PATH_CHALLENGE / PATH_RESPONSE (we don't initiate path
 *    validation, but we MUST reply to a challenge — implemented
 *    minimally)
 *
 * RFC 9000 §12.4 mandates that any unknown frame type is a
 * FRAME_ENCODING_ERROR connection error. So `parse()` either
 * returns a known [Frame] subtype or throws [QuicWireException].
 *
 * Frame variants are data classes; equality matters for tests
 * and assertions but NOT for the hot path (we don't compare
 * frames in production).
 */
internal sealed class Frame {

    /** Wire-encoded size in bytes, including the type byte. */
    abstract fun encodedSize(): Int

    /** Encode this frame at [dst]'s current position, advancing
     *  position by exactly [encodedSize] bytes. */
    abstract fun encode(dst: ByteBuffer)

    /** Whether reception of this frame requires an ACK from us.
     *  Per RFC 9000 §13.2.1: ACK, PADDING, CONNECTION_CLOSE are
     *  not ack-eliciting; every other frame type is. */
    open val ackEliciting: Boolean get() = true

    /** Which packet number spaces this frame is allowed in. Per
     *  RFC 9000 §12.5, frames are restricted: most app-layer
     *  frames (STREAM, MAX_*, etc.) appear only in 1-RTT, CRYPTO
     *  appears in all spaces, ACK varies. The connection state
     *  machine uses this to reject frames in wrong spaces. */
    open val allowedIn: Set<PacketNumberSpace> get() = SPACES_ONE_RTT_ONLY

    companion object {
        // Frame type constants (RFC 9000 §19).
        const val TYPE_PADDING = 0x00
        const val TYPE_PING = 0x01
        const val TYPE_ACK = 0x02
        const val TYPE_ACK_ECN = 0x03
        const val TYPE_RESET_STREAM = 0x04
        const val TYPE_STOP_SENDING = 0x05
        const val TYPE_CRYPTO = 0x06
        const val TYPE_NEW_TOKEN = 0x07
        // STREAM occupies 0x08..0x0F (8 variants, see STREAM frame doc).
        const val TYPE_MAX_DATA = 0x10
        const val TYPE_MAX_STREAM_DATA = 0x11
        const val TYPE_MAX_STREAMS_BIDI = 0x12
        const val TYPE_MAX_STREAMS_UNI = 0x13
        const val TYPE_DATA_BLOCKED = 0x14
        const val TYPE_STREAM_DATA_BLOCKED = 0x15
        const val TYPE_STREAMS_BLOCKED_BIDI = 0x16
        const val TYPE_STREAMS_BLOCKED_UNI = 0x17
        const val TYPE_NEW_CONNECTION_ID = 0x18
        const val TYPE_RETIRE_CONNECTION_ID = 0x19
        const val TYPE_PATH_CHALLENGE = 0x1a
        const val TYPE_PATH_RESPONSE = 0x1b
        const val TYPE_CONNECTION_CLOSE_QUIC = 0x1c
        const val TYPE_CONNECTION_CLOSE_APP = 0x1d
        const val TYPE_HANDSHAKE_DONE = 0x1e

        // STREAM type flag bits (low 3 bits of the type byte;
        // bit 3 is the STREAM prefix 0x08).
        const val STREAM_FIN_BIT = 0x01
        const val STREAM_LEN_BIT = 0x02
        const val STREAM_OFF_BIT = 0x04

        /**
         * Parse exactly one frame from [src] starting at the
         * current position; advances position past the frame.
         * Throws [QuicWireException] on unknown frame types or
         * malformed length fields.
         */
        fun parse(src: ByteBuffer): Frame {
            val type = src.get().toInt() and 0xFF
            return when {
                type == TYPE_PADDING -> Padding.parseAfterType(src)
                type == TYPE_PING -> Ping
                type == TYPE_ACK -> Ack.parseAfterType(src, ecn = false)
                type == TYPE_ACK_ECN -> Ack.parseAfterType(src, ecn = true)
                type == TYPE_RESET_STREAM -> ResetStream.parseAfterType(src)
                type == TYPE_STOP_SENDING -> StopSending.parseAfterType(src)
                type == TYPE_CRYPTO -> Crypto.parseAfterType(src)
                type == TYPE_NEW_TOKEN -> NewToken.parseAfterType(src)
                type in 0x08..0x0F -> Stream.parseAfterType(src, type)
                type == TYPE_MAX_DATA -> MaxData.parseAfterType(src)
                type == TYPE_MAX_STREAM_DATA -> MaxStreamData.parseAfterType(src)
                type == TYPE_MAX_STREAMS_BIDI -> MaxStreams.parseAfterType(src, bidi = true)
                type == TYPE_MAX_STREAMS_UNI -> MaxStreams.parseAfterType(src, bidi = false)
                type == TYPE_DATA_BLOCKED -> DataBlocked.parseAfterType(src)
                type == TYPE_STREAM_DATA_BLOCKED -> StreamDataBlocked.parseAfterType(src)
                type == TYPE_STREAMS_BLOCKED_BIDI -> StreamsBlocked.parseAfterType(src, bidi = true)
                type == TYPE_STREAMS_BLOCKED_UNI -> StreamsBlocked.parseAfterType(src, bidi = false)
                type == TYPE_NEW_CONNECTION_ID -> NewConnectionId.parseAfterType(src)
                type == TYPE_RETIRE_CONNECTION_ID -> RetireConnectionId.parseAfterType(src)
                type == TYPE_PATH_CHALLENGE -> PathChallenge.parseAfterType(src)
                type == TYPE_PATH_RESPONSE -> PathResponse.parseAfterType(src)
                type == TYPE_CONNECTION_CLOSE_QUIC -> ConnectionClose.parseAfterType(src, app = false)
                type == TYPE_CONNECTION_CLOSE_APP -> ConnectionClose.parseAfterType(src, app = true)
                type == TYPE_HANDSHAKE_DONE -> HandshakeDone
                else -> throw QuicWireException(
                    "unknown frame type: 0x${type.toString(16)} at offset ${src.position() - 1}"
                )
            }
        }

        /** Parse frames until [src] is exhausted. */
        fun parseAll(src: ByteBuffer): List<Frame> {
            val out = ArrayList<Frame>()
            while (src.hasRemaining()) out.add(parse(src))
            return out
        }
    }
}

/** Thrown on any frame-encoding violation. Caller should map to
 *  CONNECTION_CLOSE with FRAME_ENCODING_ERROR (0x07). */
internal class QuicWireException(message: String) : RuntimeException(message)

/** Per-space packet number tracking. RFC 9000 §12.3 — each space
 *  has its own packet number sequence and ACK ranges. */
internal enum class PacketNumberSpace { INITIAL, HANDSHAKE, ONE_RTT }

// ────────────────────────────────────────────────────────────────────
// PADDING (0x00) — RFC 9000 §19.1
// ────────────────────────────────────────────────────────────────────

/**
 * One or more consecutive PADDING bytes. The parser collapses
 * runs of `0x00` into a single Padding instance with [length]
 * counting all bytes (including the byte that triggered
 * dispatch). Encoded form is just N zero bytes.
 *
 * Used to inflate Initial packets to the 1200-byte UDP datagram
 * minimum (RFC 9000 §14.1) and to pad PTO probes.
 */
internal data class Padding(val length: Int) : Frame() {
    init { require(length > 0) { "Padding length must be > 0, got $length" } }
    override val ackEliciting: Boolean get() = false
    override val allowedIn: Set<PacketNumberSpace> get() = SPACES_ALL
    override fun encodedSize(): Int = length
    override fun encode(dst: ByteBuffer) {
        // Zero-fill — bulk put of a small zero buffer would be
        // faster for very long padding runs, but Initial-pad is
        // bounded at ~1200 bytes and PTO padding is single-digit;
        // the loop is fine.
        repeat(length) { dst.put(0) }
    }
    companion object {
        // The 0x00 type byte was already consumed by Frame.parse.
        // Walk forward as long as we see more zeros, coalescing.
        internal fun parseAfterType(src: ByteBuffer): Padding {
            var count = 1
            while (src.hasRemaining() && src.get(src.position()).toInt() == 0) {
                src.get()  // consume
                count++
            }
            return Padding(count)
        }
    }
}

// ────────────────────────────────────────────────────────────────────
// PING (0x01) — RFC 9000 §19.2
// ────────────────────────────────────────────────────────────────────

/** Single byte. Ack-eliciting — used as a PTO probe and as a
 *  keepalive heartbeat. */
internal object Ping : Frame() {
    override val allowedIn: Set<PacketNumberSpace> get() = SPACES_ALL
    override fun encodedSize(): Int = 1
    override fun encode(dst: ByteBuffer) { dst.put(Frame.TYPE_PING.toByte()) }
}

// ────────────────────────────────────────────────────────────────────
// ACK / ACK_ECN (0x02, 0x03) — RFC 9000 §19.3
// ────────────────────────────────────────────────────────────────────

/**
 * Acknowledges a (potentially non-contiguous) set of packet
 * numbers in the same packet number space the ACK frame appears
 * in. Ranges are stored in **descending** order — `ranges[0]`
 * contains [largestAcked], `ranges[1]` is the next-older range,
 * and so on.
 *
 * The wire encoding is differential — see RFC 9000 §19.3.1.
 * We hold ranges as plain `LongRange`s here and translate at
 * encode/decode time; callers shouldn't have to think about
 * gap-and-length-minus-one encoding.
 *
 * [ackDelayScaled] is the raw wire value; the connection layer
 * interprets it using the peer's negotiated `ack_delay_exponent`
 * transport parameter. We don't apply the exponent here because
 * Frame has no access to transport params.
 */
internal data class Ack(
    val largestAcked: Long,
    val ackDelayScaled: Long,
    /** Descending, non-overlapping, non-adjacent. ranges[0] ends at largestAcked. */
    val ranges: List<LongRange>,
    val ecn: EcnCounts? = null,
) : Frame() {
    init {
        require(ranges.isNotEmpty()) { "ACK frame needs at least one range" }
        require(ranges[0].last == largestAcked) {
            "first ack range must end at largestAcked ($largestAcked), got ${ranges[0].last}"
        }
        // Validate descending and non-adjacent (must have a gap of ≥1).
        for (i in 1 until ranges.size) {
            require(ranges[i].last < ranges[i - 1].first - 1) {
                "ack ranges must be strictly descending with gaps: " +
                    "ranges[$i]=${ranges[i]} not strictly below ranges[${i - 1}]=${ranges[i - 1]}"
            }
        }
    }

    override val ackEliciting: Boolean get() = false
    override val allowedIn: Set<PacketNumberSpace> get() = SPACES_ALL

    data class EcnCounts(val ect0: Long, val ect1: Long, val ce: Long)

    override fun encodedSize(): Int {
        var size = 1  // type byte
        size += Varint.encodedLength(largestAcked)
        size += Varint.encodedLength(ackDelayScaled)
        size += Varint.encodedLength((ranges.size - 1).toLong())  // ACK Range Count
        // First ACK Range: largestAcked - smallest of first range.
        size += Varint.encodedLength(largestAcked - ranges[0].first)
        // Subsequent: gap + length pairs.
        var prevSmallest = ranges[0].first
        for (i in 1 until ranges.size) {
            val r = ranges[i]
            // Gap = (prevSmallest - 1) - r.last - 1   = prevSmallest - r.last - 2
            val gap = prevSmallest - r.last - 2
            val len = r.last - r.first
            size += Varint.encodedLength(gap)
            size += Varint.encodedLength(len)
            prevSmallest = r.first
        }
        if (ecn != null) {
            size += Varint.encodedLength(ecn.ect0)
            size += Varint.encodedLength(ecn.ect1)
            size += Varint.encodedLength(ecn.ce)
        }
        return size
    }

    override fun encode(dst: ByteBuffer) {
        dst.put((if (ecn != null) Frame.TYPE_ACK_ECN else Frame.TYPE_ACK).toByte())
        Varint.encode(dst, largestAcked)
        Varint.encode(dst, ackDelayScaled)
        Varint.encode(dst, (ranges.size - 1).toLong())
        Varint.encode(dst, largestAcked - ranges[0].first)
        var prevSmallest = ranges[0].first
        for (i in 1 until ranges.size) {
            val r = ranges[i]
            val gap = prevSmallest - r.last - 2
            val len = r.last - r.first
            Varint.encode(dst, gap)
            Varint.encode(dst, len)
            prevSmallest = r.first
        }
        if (ecn != null) {
            Varint.encode(dst, ecn.ect0)
            Varint.encode(dst, ecn.ect1)
            Varint.encode(dst, ecn.ce)
        }
    }

    companion object {
        internal fun parseAfterType(src: ByteBuffer, ecn: Boolean): Ack {
            val largest = Varint.decode(src)
            val delay = Varint.decode(src)
            val rangeCount = Varint.decode(src)
            val firstRange = Varint.decode(src)
            // First range covers [largest - firstRange, largest].
            if (firstRange > largest) {
                throw QuicWireException("ACK firstRange $firstRange > largestAcked $largest")
            }
            val ranges = ArrayList<LongRange>((rangeCount + 1).toInt())
            ranges.add((largest - firstRange)..largest)
            var prevSmallest = largest - firstRange
            for (i in 0 until rangeCount) {
                val gap = Varint.decode(src)
                val len = Varint.decode(src)
                // newLargest = prevSmallest - gap - 2 (RFC 9000 §19.3.1)
                val newLargest = prevSmallest - gap - 2
                val newSmallest = newLargest - len
                if (newSmallest < 0 || newLargest < newSmallest) {
                    throw QuicWireException(
                        "ACK range $i underflow: gap=$gap len=$len prevSmallest=$prevSmallest"
                    )
                }
                ranges.add(newSmallest..newLargest)
                prevSmallest = newSmallest
            }
            val ecnCounts = if (ecn) {
                EcnCounts(Varint.decode(src), Varint.decode(src), Varint.decode(src))
            } else null
            return Ack(largest, delay, ranges, ecnCounts)
        }
    }
}

// ────────────────────────────────────────────────────────────────────
// RESET_STREAM (0x04) — RFC 9000 §19.4
// ────────────────────────────────────────────────────────────────────

/** Peer aborts the SEND side of a stream. We treat receipt as a
 *  hard close of the receive buffer. */
internal data class ResetStream(
    val streamId: Long,
    val applicationProtocolErrorCode: Long,
    val finalSize: Long,
) : Frame() {
    override fun encodedSize(): Int = 1 +
        Varint.encodedLength(streamId) +
        Varint.encodedLength(applicationProtocolErrorCode) +
        Varint.encodedLength(finalSize)

    override fun encode(dst: ByteBuffer) {
        dst.put(Frame.TYPE_RESET_STREAM.toByte())
        Varint.encode(dst, streamId)
        Varint.encode(dst, applicationProtocolErrorCode)
        Varint.encode(dst, finalSize)
    }

    companion object {
        internal fun parseAfterType(src: ByteBuffer) =
            ResetStream(Varint.decode(src), Varint.decode(src), Varint.decode(src))
    }
}

// ────────────────────────────────────────────────────────────────────
// STOP_SENDING (0x05) — RFC 9000 §19.5
// ────────────────────────────────────────────────────────────────────

/** Peer asks us to stop sending on a stream. */
internal data class StopSending(
    val streamId: Long,
    val applicationProtocolErrorCode: Long,
) : Frame() {
    override fun encodedSize(): Int = 1 +
        Varint.encodedLength(streamId) +
        Varint.encodedLength(applicationProtocolErrorCode)

    override fun encode(dst: ByteBuffer) {
        dst.put(Frame.TYPE_STOP_SENDING.toByte())
        Varint.encode(dst, streamId)
        Varint.encode(dst, applicationProtocolErrorCode)
    }

    companion object {
        internal fun parseAfterType(src: ByteBuffer) =
            StopSending(Varint.decode(src), Varint.decode(src))
    }
}

// ────────────────────────────────────────────────────────────────────
// CRYPTO (0x06) — RFC 9000 §19.6
// ────────────────────────────────────────────────────────────────────

/**
 * TLS handshake bytes. The QUIC packet number space and TLS
 * encryption level are paired (Initial CRYPTO carries Initial-
 * level TLS records, Handshake CRYPTO carries Handshake-level
 * records, 1-RTT CRYPTO carries post-handshake TLS like
 * NewSessionTicket).
 *
 * Has an explicit offset because the TLS handshake stream can be
 * fragmented across packets and may arrive out of order; the
 * connection-level CRYPTO reassembler needs to know where each
 * fragment fits.
 */
internal data class Crypto(
    val offset: Long,
    val data: ByteArray,
) : Frame() {
    override val allowedIn: Set<PacketNumberSpace> get() = SPACES_ALL

    override fun encodedSize(): Int = 1 +
        Varint.encodedLength(offset) +
        Varint.encodedLength(data.size.toLong()) +
        data.size

    override fun encode(dst: ByteBuffer) {
        dst.put(Frame.TYPE_CRYPTO.toByte())
        Varint.encode(dst, offset)
        Varint.encode(dst, data.size.toLong())
        dst.put(data)
    }

    // ByteArray equality is reference-based by default; override
    // for data-class semantics (matters for unit tests, never
    // called on the hot path).
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Crypto) return false
        return offset == other.offset && data.contentEquals(other.data)
    }
    override fun hashCode(): Int = 31 * offset.hashCode() + data.contentHashCode()

    companion object {
        internal fun parseAfterType(src: ByteBuffer): Crypto {
            val offset = Varint.decode(src)
            val len = Varint.decode(src)
            if (len < 0 || len > Int.MAX_VALUE) {
                throw QuicWireException("CRYPTO length out of range: $len")
            }
            val data = ByteArray(len.toInt())
            src.get(data)
            return Crypto(offset, data)
        }
    }
}

// ────────────────────────────────────────────────────────────────────
// NEW_TOKEN (0x07) — RFC 9000 §19.7
// ────────────────────────────────────────────────────────────────────

/** Server hands us a token to use on subsequent connection
 *  attempts (for address validation). We don't reuse tokens
 *  (no 0-RTT), so this is purely informational. */
internal data class NewToken(val token: ByteArray) : Frame() {
    override fun encodedSize(): Int = 1 +
        Varint.encodedLength(token.size.toLong()) +
        token.size

    override fun encode(dst: ByteBuffer) {
        dst.put(Frame.TYPE_NEW_TOKEN.toByte())
        Varint.encode(dst, token.size.toLong())
        dst.put(token)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NewToken) return false
        return token.contentEquals(other.token)
    }
    override fun hashCode(): Int = token.contentHashCode()

    companion object {
        internal fun parseAfterType(src: ByteBuffer): NewToken {
            val len = Varint.decode(src)
            if (len <= 0 || len > Int.MAX_VALUE) {
                throw QuicWireException("NEW_TOKEN length invalid: $len")
            }
            val token = ByteArray(len.toInt())
            src.get(token)
            return NewToken(token)
        }
    }
}

// ────────────────────────────────────────────────────────────────────
// STREAM (0x08–0x0F) — RFC 9000 §19.8
// ────────────────────────────────────────────────────────────────────

/**
 * Application data on a stream. Eight wire variants (0x08–0x0F)
 * encode three optional fields via flag bits in the type byte:
 *  - bit 0 (FIN): this frame's data ends the stream's send side
 *  - bit 1 (LEN): an explicit length field is present
 *  - bit 2 (OFF): an explicit offset field is present
 *
 * Frames without an offset implicitly start at offset 0. Frames
 * without a length extend to the end of the packet payload — the
 * parser figures this out by treating "no LEN" as "data is rest
 * of buffer".
 *
 * On encode we always set LEN when packing more than one frame
 * into a packet, and clear LEN only when the STREAM frame is the
 * last frame in a packet (saving a varint at the cost of being
 * unable to pack anything after it).
 */
internal data class Stream(
    val streamId: Long,
    val offset: Long,
    val data: ByteArray,
    val fin: Boolean,
    /** Whether the encoded form should include an explicit length.
     *  Caller decides based on whether the frame is the last in its
     *  packet; defaults to true (always-explicit, safer to pack). */
    val explicitLength: Boolean = true,
) : Frame() {

    override fun encodedSize(): Int {
        var size = 1  // type byte
        size += Varint.encodedLength(streamId)
        if (offset > 0) size += Varint.encodedLength(offset)
        if (explicitLength) size += Varint.encodedLength(data.size.toLong())
        size += data.size
        return size
    }

    override fun encode(dst: ByteBuffer) {
        var typeByte = 0x08
        if (offset > 0) typeByte = typeByte or Frame.STREAM_OFF_BIT
        if (explicitLength) typeByte = typeByte or Frame.STREAM_LEN_BIT
        if (fin) typeByte = typeByte or Frame.STREAM_FIN_BIT
        dst.put(typeByte.toByte())
        Varint.encode(dst, streamId)
        if (offset > 0) Varint.encode(dst, offset)
        if (explicitLength) Varint.encode(dst, data.size.toLong())
        dst.put(data)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Stream) return false
        return streamId == other.streamId &&
            offset == other.offset &&
            fin == other.fin &&
            data.contentEquals(other.data)
    }
    override fun hashCode(): Int {
        var h = streamId.hashCode()
        h = 31 * h + offset.hashCode()
        h = 31 * h + fin.hashCode()
        h = 31 * h + data.contentHashCode()
        return h
    }

    companion object {
        internal fun parseAfterType(src: ByteBuffer, typeByte: Int): Stream {
            val hasOffset = (typeByte and Frame.STREAM_OFF_BIT) != 0
            val hasLength = (typeByte and Frame.STREAM_LEN_BIT) != 0
            val fin = (typeByte and Frame.STREAM_FIN_BIT) != 0
            val streamId = Varint.decode(src)
            val offset = if (hasOffset) Varint.decode(src) else 0L
            val dataLen: Int = if (hasLength) {
                val n = Varint.decode(src)
                if (n < 0 || n > Int.MAX_VALUE) {
                    throw QuicWireException("STREAM length out of range: $n")
                }
                n.toInt()
            } else {
                // No length — data extends to end of buffer.
                src.remaining()
            }
            val data = ByteArray(dataLen)
            src.get(data)
            return Stream(streamId, offset, data, fin, explicitLength = hasLength)
        }
    }
}

// ────────────────────────────────────────────────────────────────────
// MAX_DATA / MAX_STREAM_DATA / MAX_STREAMS — RFC 9000 §19.9–§19.11
// ────────────────────────────────────────────────────────────────────

/** Connection-level flow control update from peer (or to peer). */
internal data class MaxData(val maxData: Long) : Frame() {
    override fun encodedSize(): Int = 1 + Varint.encodedLength(maxData)
    override fun encode(dst: ByteBuffer) {
        dst.put(Frame.TYPE_MAX_DATA.toByte())
        Varint.encode(dst, maxData)
    }
    companion object {
        internal fun parseAfterType(src: ByteBuffer) = MaxData(Varint.decode(src))
    }
}

/** Per-stream flow control update. */
internal data class MaxStreamData(
    val streamId: Long,
    val maxStreamData: Long,
) : Frame() {
    override fun encodedSize(): Int = 1 +
        Varint.encodedLength(streamId) +
        Varint.encodedLength(maxStreamData)

    override fun encode(dst: ByteBuffer) {
        dst.put(Frame.TYPE_MAX_STREAM_DATA.toByte())
        Varint.encode(dst, streamId)
        Varint.encode(dst, maxStreamData)
    }
    companion object {
        internal fun parseAfterType(src: ByteBuffer) =
            MaxStreamData(Varint.decode(src), Varint.decode(src))
    }
}

/** Permitted-stream-count update. */
internal data class MaxStreams(val bidi: Boolean, val maxStreams: Long) : Frame() {
    override fun encodedSize(): Int = 1 + Varint.encodedLength(maxStreams)
    override fun encode(dst: ByteBuffer) {
        dst.put((if (bidi) Frame.TYPE_MAX_STREAMS_BIDI else Frame.TYPE_MAX_STREAMS_UNI).toByte())
        Varint.encode(dst, maxStreams)
    }
    companion object {
        internal fun parseAfterType(src: ByteBuffer, bidi: Boolean) =
            MaxStreams(bidi, Varint.decode(src))
    }
}

// ────────────────────────────────────────────────────────────────────
// DATA_BLOCKED / STREAM_DATA_BLOCKED / STREAMS_BLOCKED — informational
// RFC 9000 §19.12–§19.14. Peer telling us they want more credit;
// we already manage credit proactively so these are observational.
// ────────────────────────────────────────────────────────────────────

internal data class DataBlocked(val maxData: Long) : Frame() {
    override fun encodedSize(): Int = 1 + Varint.encodedLength(maxData)
    override fun encode(dst: ByteBuffer) {
        dst.put(Frame.TYPE_DATA_BLOCKED.toByte())
        Varint.encode(dst, maxData)
    }
    companion object {
        internal fun parseAfterType(src: ByteBuffer) = DataBlocked(Varint.decode(src))
    }
}

internal data class StreamDataBlocked(val streamId: Long, val maxStreamData: Long) : Frame() {
    override fun encodedSize(): Int = 1 +
        Varint.encodedLength(streamId) +
        Varint.encodedLength(maxStreamData)
    override fun encode(dst: ByteBuffer) {
        dst.put(Frame.TYPE_STREAM_DATA_BLOCKED.toByte())
        Varint.encode(dst, streamId)
        Varint.encode(dst, maxStreamData)
    }
    companion object {
        internal fun parseAfterType(src: ByteBuffer) =
            StreamDataBlocked(Varint.decode(src), Varint.decode(src))
    }
}

internal data class StreamsBlocked(val bidi: Boolean, val maxStreams: Long) : Frame() {
    override fun encodedSize(): Int = 1 + Varint.encodedLength(maxStreams)
    override fun encode(dst: ByteBuffer) {
        dst.put(
            (if (bidi) Frame.TYPE_STREAMS_BLOCKED_BIDI else Frame.TYPE_STREAMS_BLOCKED_UNI).toByte()
        )
        Varint.encode(dst, maxStreams)
    }
    companion object {
        internal fun parseAfterType(src: ByteBuffer, bidi: Boolean) =
            StreamsBlocked(bidi, Varint.decode(src))
    }
}

// ────────────────────────────────────────────────────────────────────
// NEW_CONNECTION_ID / RETIRE_CONNECTION_ID — RFC 9000 §19.15–§19.16
// We don't migrate; parse but never act on these.
// ────────────────────────────────────────────────────────────────────

internal data class NewConnectionId(
    val sequenceNumber: Long,
    val retirePriorTo: Long,
    val connectionId: ByteArray,
    val statelessResetToken: ByteArray,  // exactly 16 bytes
) : Frame() {
    init { require(statelessResetToken.size == 16) { "stateless reset token must be 16 bytes" } }

    override fun encodedSize(): Int = 1 +
        Varint.encodedLength(sequenceNumber) +
        Varint.encodedLength(retirePriorTo) +
        1 + connectionId.size +
        16

    override fun encode(dst: ByteBuffer) {
        dst.put(Frame.TYPE_NEW_CONNECTION_ID.toByte())
        Varint.encode(dst, sequenceNumber)
        Varint.encode(dst, retirePriorTo)
        dst.put(connectionId.size.toByte())
        dst.put(connectionId)
        dst.put(statelessResetToken)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NewConnectionId) return false
        return sequenceNumber == other.sequenceNumber &&
            retirePriorTo == other.retirePriorTo &&
            connectionId.contentEquals(other.connectionId) &&
            statelessResetToken.contentEquals(other.statelessResetToken)
    }
    override fun hashCode(): Int {
        var h = sequenceNumber.hashCode()
        h = 31 * h + retirePriorTo.hashCode()
        h = 31 * h + connectionId.contentHashCode()
        h = 31 * h + statelessResetToken.contentHashCode()
        return h
    }

    companion object {
        internal fun parseAfterType(src: ByteBuffer): NewConnectionId {
            val seq = Varint.decode(src)
            val retire = Varint.decode(src)
            val cidLen = src.get().toInt() and 0xFF
            if (cidLen < 1 || cidLen > 20) {
                throw QuicWireException("NEW_CONNECTION_ID length out of range: $cidLen")
            }
            val cid = ByteArray(cidLen)
            src.get(cid)
            val token = ByteArray(16)
            src.get(token)
            return NewConnectionId(seq, retire, cid, token)
        }
    }
}

internal data class RetireConnectionId(val sequenceNumber: Long) : Frame() {
    override fun encodedSize(): Int = 1 + Varint.encodedLength(sequenceNumber)
    override fun encode(dst: ByteBuffer) {
        dst.put(Frame.TYPE_RETIRE_CONNECTION_ID.toByte())
        Varint.encode(dst, sequenceNumber)
    }
    companion object {
        internal fun parseAfterType(src: ByteBuffer) = RetireConnectionId(Varint.decode(src))
    }
}

// ────────────────────────────────────────────────────────────────────
// PATH_CHALLENGE / PATH_RESPONSE — RFC 9000 §19.17–§19.18
// quic-go server rarely sends these to clients, but we must echo
// challenges (RFC 9000 §8.2) or risk timeout.
// ────────────────────────────────────────────────────────────────────

internal data class PathChallenge(val data: ByteArray) : Frame() {
    init { require(data.size == 8) { "PATH_CHALLENGE data must be 8 bytes" } }
    override fun encodedSize(): Int = 1 + 8
    override fun encode(dst: ByteBuffer) {
        dst.put(Frame.TYPE_PATH_CHALLENGE.toByte())
        dst.put(data)
    }
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        return other is PathChallenge && data.contentEquals(other.data)
    }
    override fun hashCode(): Int = data.contentHashCode()
    companion object {
        internal fun parseAfterType(src: ByteBuffer): PathChallenge {
            val data = ByteArray(8); src.get(data); return PathChallenge(data)
        }
    }
}

internal data class PathResponse(val data: ByteArray) : Frame() {
    init { require(data.size == 8) { "PATH_RESPONSE data must be 8 bytes" } }
    override fun encodedSize(): Int = 1 + 8
    override fun encode(dst: ByteBuffer) {
        dst.put(Frame.TYPE_PATH_RESPONSE.toByte())
        dst.put(data)
    }
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        return other is PathResponse && data.contentEquals(other.data)
    }
    override fun hashCode(): Int = data.contentHashCode()
    companion object {
        internal fun parseAfterType(src: ByteBuffer): PathResponse {
            val data = ByteArray(8); src.get(data); return PathResponse(data)
        }
    }
}

// ────────────────────────────────────────────────────────────────────
// CONNECTION_CLOSE — RFC 9000 §19.19
// Two variants: 0x1c carries a QUIC-layer error + the offending
// frame type; 0x1d carries an application-layer error (no frame
// type field).
// ────────────────────────────────────────────────────────────────────

internal data class ConnectionClose(
    val isApplicationError: Boolean,
    val errorCode: Long,
    /** Frame type that triggered the error, or 0 for application-
     *  layer or non-frame errors. Always 0 when [isApplicationError]. */
    val frameType: Long,
    val reasonPhrase: String,
) : Frame() {
    override val ackEliciting: Boolean get() = false
    override val allowedIn: Set<PacketNumberSpace> get() = SPACES_ALL

    override fun encodedSize(): Int {
        val reasonBytes = reasonPhrase.toByteArray(Charsets.UTF_8)
        var size = 1 + Varint.encodedLength(errorCode)
        if (!isApplicationError) size += Varint.encodedLength(frameType)
        size += Varint.encodedLength(reasonBytes.size.toLong()) + reasonBytes.size
        return size
    }

    override fun encode(dst: ByteBuffer) {
        val reasonBytes = reasonPhrase.toByteArray(Charsets.UTF_8)
        dst.put(
            (if (isApplicationError) Frame.TYPE_CONNECTION_CLOSE_APP
            else Frame.TYPE_CONNECTION_CLOSE_QUIC).toByte()
        )
        Varint.encode(dst, errorCode)
        if (!isApplicationError) Varint.encode(dst, frameType)
        Varint.encode(dst, reasonBytes.size.toLong())
        dst.put(reasonBytes)
    }

    companion object {
        internal fun parseAfterType(src: ByteBuffer, app: Boolean): ConnectionClose {
            val errorCode = Varint.decode(src)
            val frameType = if (app) 0L else Varint.decode(src)
            val reasonLen = Varint.decode(src)
            if (reasonLen < 0 || reasonLen > Int.MAX_VALUE) {
                throw QuicWireException("CONNECTION_CLOSE reason length out of range: $reasonLen")
            }
            val reason = ByteArray(reasonLen.toInt())
            src.get(reason)
            return ConnectionClose(app, errorCode, frameType, String(reason, Charsets.UTF_8))
        }
    }
}

// ────────────────────────────────────────────────────────────────────
// HANDSHAKE_DONE (0x1e) — RFC 9000 §19.20
// Server signals 1-RTT keys are confirmed. Client never sends.
// ────────────────────────────────────────────────────────────────────

internal object HandshakeDone : Frame() {
    override fun encodedSize(): Int = 1
    override fun encode(dst: ByteBuffer) { dst.put(Frame.TYPE_HANDSHAKE_DONE.toByte()) }
}
