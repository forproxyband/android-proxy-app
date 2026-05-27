package com.proxyagent.app.nativeagent.quic.wire

import com.proxyagent.app.nativeagent.quic.Varint
import java.nio.ByteBuffer

/**
 * QUIC packet headers (RFC 9000 §17).
 *
 * Two header forms:
 *  - **Long header** (first byte bit 7 = 1): used during the
 *    handshake. Carries the version field, both DCID and SCID
 *    explicitly with lengths, and a packet-type field encoding
 *    Initial / 0-RTT / Handshake / Retry.
 *  - **Short header** (first byte bit 7 = 0): 1-RTT packets only.
 *    No version, no SCID, only DCID — and DCID length is known
 *    out-of-band from the handshake (server's chosen length).
 *
 * **Header protection.** RFC 9001 §5.4 protects part of the
 * first byte (low 4 or 5 bits) and the packet number bytes via
 * an XOR mask derived from a sample of the ciphertext payload.
 * Anything we expose here is the **unprotected portion**:
 *  - Long header: high 4 bits of the first byte are unprotected
 *    (form, fixed, type), so we can identify the packet type
 *    before unmasking; everything after the first byte's low 4
 *    bits (and the PN bytes) is unprotected too — version,
 *    connection IDs, length varint.
 *  - Short header: the high bit (form) is unprotected; we know
 *    the DCID length, so we can locate the PN.
 *
 * This means parsing has two phases:
 *  1. Parse everything before the PN — this file's [parseLongHeader]
 *     / [parseShortHeader] does that.
 *  2. Phase 3 crypto removes header protection, reveals the PN
 *     length (from the first byte's low 2 bits), reads the PN,
 *     and decrypts the payload via AEAD.
 *
 * Symmetric on encode: this file produces the unprotected header
 * bytes; Phase 3 adds the AEAD ciphertext and header protection.
 *
 * **Version.** We support only QUIC v1 (`0x00000001`). Any other
 * version is a protocol error (which manifests as the server
 * sending a Version Negotiation packet that we'd need to handle
 * separately — for now we just close the connection).
 */
internal object PacketWire {

    /** RFC 9000 §15: QUIC v1 version number. */
    const val QUIC_V1: Int = 0x00000001

    /** RFC 9000 §17.2: maximum length of a Connection ID. */
    const val MAX_CID_LENGTH: Int = 20

    // First-byte bit positions.
    const val HEADER_FORM_LONG: Int = 0x80   // 1 = long header
    const val FIXED_BIT: Int = 0x40          // must be 1 (RFC 9000 §17.2/§17.3)
    const val LONG_TYPE_MASK: Int = 0x30     // bits 5-4 select packet type
    const val LONG_TYPE_SHIFT: Int = 4
    /** Bits in the first byte that are protected by header protection.
     *  RFC 9001 §5.4.1: low 4 bits for long header, low 5 bits for short. */
    const val HP_LONG_MASK: Int = 0x0F
    const val HP_SHORT_MASK: Int = 0x1F
}

/** Long header packet types (RFC 9000 §17.2). The numeric value
 *  matches the (type byte shifted right) representation. */
internal enum class LongPacketType(val wireValue: Int) {
    INITIAL(0x0), ZERO_RTT(0x1), HANDSHAKE(0x2), RETRY(0x3);

    fun toPacketNumberSpace(): PacketNumberSpace = when (this) {
        INITIAL -> PacketNumberSpace.INITIAL
        HANDSHAKE -> PacketNumberSpace.HANDSHAKE
        ZERO_RTT, RETRY -> PacketNumberSpace.ONE_RTT
        // Retry has no PN space really; map to ONE_RTT as a non-fatal default.
    }

    companion object {
        fun fromWireValue(v: Int): LongPacketType = when (v) {
            0x0 -> INITIAL
            0x1 -> ZERO_RTT
            0x2 -> HANDSHAKE
            0x3 -> RETRY
            else -> throw QuicWireException("invalid long header type: $v")
        }
    }
}

/**
 * Parsed unprotected portion of a long header packet.
 *
 * The buffer's position after [parseLongHeader] returns is the
 * start of the protected packet number — Phase 3 picks up there.
 *
 * For [LongPacketType.RETRY]: there is no Length / PN / payload —
 * the rest of the datagram is `retry_integrity_tag` (16 bytes)
 * plus opaque retry token bytes preceding it. The parser
 * surfaces [retryTokenLength] / [retryIntegrityTagOffset] for
 * the handshake layer to reconstruct.
 *
 * @property firstByte the actual byte read from wire — Phase 3
 *   needs it to apply / verify header protection. Bits 4-7 are
 *   unprotected (form, fixed, type, reserved); bits 0-3 are
 *   header-protected (reserved 0,1 + PN length 2-3).
 * @property payloadLength varint value read from the Length
 *   field. INCLUDES the packet number bytes AND the AEAD-
 *   encrypted payload (incl. the 16-byte authentication tag).
 * @property headerStartOffset offset (in the original buffer
 *   passed to the parser) of the first byte of the header.
 * @property packetNumberOffset offset of the start of the PN
 *   field. Length of the PN is encoded in the protected low 2
 *   bits of [firstByte] and only known after header protection
 *   is removed.
 */
internal data class LongHeaderInfo(
    val firstByte: Byte,
    val type: LongPacketType,
    val version: Int,
    val dcid: ByteArray,
    val scid: ByteArray,
    /** Initial only; empty for other long header types. */
    val initialToken: ByteArray,
    val payloadLength: Long,
    val headerStartOffset: Int,
    val packetNumberOffset: Int,
    /** Retry only: offset of the 16-byte retry integrity tag at
     *  the END of the packet, computed as (datagramEnd - 16). */
    val retryIntegrityTagOffset: Int = -1,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LongHeaderInfo) return false
        return firstByte == other.firstByte &&
            type == other.type &&
            version == other.version &&
            dcid.contentEquals(other.dcid) &&
            scid.contentEquals(other.scid) &&
            initialToken.contentEquals(other.initialToken) &&
            payloadLength == other.payloadLength &&
            headerStartOffset == other.headerStartOffset &&
            packetNumberOffset == other.packetNumberOffset &&
            retryIntegrityTagOffset == other.retryIntegrityTagOffset
    }
    override fun hashCode(): Int {
        var h = firstByte.hashCode()
        h = 31 * h + type.hashCode()
        h = 31 * h + version
        h = 31 * h + dcid.contentHashCode()
        h = 31 * h + scid.contentHashCode()
        h = 31 * h + initialToken.contentHashCode()
        h = 31 * h + payloadLength.hashCode()
        h = 31 * h + headerStartOffset
        h = 31 * h + packetNumberOffset
        h = 31 * h + retryIntegrityTagOffset
        return h
    }
}

/**
 * Parsed unprotected portion of a short (1-RTT) header packet.
 *
 * Short headers are simpler: no version, no SCID, no Length
 * (the entire datagram following DCID+PN is payload until the
 * end-of-datagram tag). Caller provides the expected DCID
 * length out-of-band (negotiated during handshake).
 *
 * Buffer position after parse is at the start of the protected
 * packet number.
 */
internal data class ShortHeaderInfo(
    val firstByte: Byte,
    val dcid: ByteArray,
    val headerStartOffset: Int,
    val packetNumberOffset: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ShortHeaderInfo) return false
        return firstByte == other.firstByte &&
            dcid.contentEquals(other.dcid) &&
            headerStartOffset == other.headerStartOffset &&
            packetNumberOffset == other.packetNumberOffset
    }
    override fun hashCode(): Int {
        var h = firstByte.hashCode()
        h = 31 * h + dcid.contentHashCode()
        h = 31 * h + headerStartOffset
        h = 31 * h + packetNumberOffset
        return h
    }
}

/**
 * Long header packet parser. Reads all unprotected fields and
 * positions [src] at the start of the protected packet number.
 *
 * Throws [QuicWireException] for any malformed field. The
 * connection layer should map this to a protocol error.
 *
 * @param src buffer positioned at the start of the QUIC packet
 *   (first byte). On success, position advances past the
 *   Length field; the buffer remaining is the protected packet
 *   number bytes followed by the AEAD-protected payload.
 * @param datagramEnd absolute end position of the UDP datagram
 *   within [src]. Used for Retry packets to locate the
 *   integrity tag at the end.
 */
internal fun parseLongHeader(src: ByteBuffer, datagramEnd: Int): LongHeaderInfo {
    val headerStart = src.position()
    if (src.remaining() < 7) {
        // Minimum: 1 (first byte) + 4 (version) + 1 (DCID len) + 0 + 1 (SCID len) + 0 = 7
        throw QuicWireException("long header truncated: ${src.remaining()} bytes")
    }
    val firstByte = src.get()
    val firstByteUnsigned = firstByte.toInt() and 0xFF
    if ((firstByteUnsigned and PacketWire.HEADER_FORM_LONG) == 0) {
        throw QuicWireException("not a long header: first byte=0x${firstByteUnsigned.toString(16)}")
    }
    if ((firstByteUnsigned and PacketWire.FIXED_BIT) == 0) {
        throw QuicWireException("fixed bit not set: first byte=0x${firstByteUnsigned.toString(16)}")
    }
    val typeWire = (firstByteUnsigned and PacketWire.LONG_TYPE_MASK) shr PacketWire.LONG_TYPE_SHIFT
    val type = LongPacketType.fromWireValue(typeWire)

    val version = src.int  // big-endian by default
    if (version == 0) {
        // Version 0 = Version Negotiation packet (RFC 9000 §17.2.1).
        // The connection layer handles those separately; refuse here.
        throw QuicWireException("version negotiation packet (version=0)")
    }

    val dcidLen = src.get().toInt() and 0xFF
    if (dcidLen > PacketWire.MAX_CID_LENGTH) {
        throw QuicWireException("DCID length $dcidLen > max ${PacketWire.MAX_CID_LENGTH}")
    }
    val dcid = ByteArray(dcidLen).also { src.get(it) }

    val scidLen = src.get().toInt() and 0xFF
    if (scidLen > PacketWire.MAX_CID_LENGTH) {
        throw QuicWireException("SCID length $scidLen > max ${PacketWire.MAX_CID_LENGTH}")
    }
    val scid = ByteArray(scidLen).also { src.get(it) }

    // Type-specific tail.
    return when (type) {
        LongPacketType.INITIAL -> {
            val tokenLen = Varint.decode(src)
            if (tokenLen < 0 || tokenLen > Int.MAX_VALUE) {
                throw QuicWireException("Initial token length out of range: $tokenLen")
            }
            val token = ByteArray(tokenLen.toInt()).also { src.get(it) }
            val payloadLen = Varint.decode(src)
            if (payloadLen < 0 || payloadLen > Int.MAX_VALUE) {
                throw QuicWireException("Initial payload length out of range: $payloadLen")
            }
            LongHeaderInfo(
                firstByte = firstByte,
                type = type,
                version = version,
                dcid = dcid,
                scid = scid,
                initialToken = token,
                payloadLength = payloadLen,
                headerStartOffset = headerStart,
                packetNumberOffset = src.position(),
            )
        }
        LongPacketType.HANDSHAKE, LongPacketType.ZERO_RTT -> {
            val payloadLen = Varint.decode(src)
            if (payloadLen < 0 || payloadLen > Int.MAX_VALUE) {
                throw QuicWireException("$type payload length out of range: $payloadLen")
            }
            LongHeaderInfo(
                firstByte = firstByte,
                type = type,
                version = version,
                dcid = dcid,
                scid = scid,
                initialToken = ByteArray(0),
                payloadLength = payloadLen,
                headerStartOffset = headerStart,
                packetNumberOffset = src.position(),
            )
        }
        LongPacketType.RETRY -> {
            // Retry has no Length / PN. Everything from here to
            // (datagramEnd - 16) is the retry token; the last 16
            // bytes are the integrity tag.
            val tagOffset = datagramEnd - 16
            if (tagOffset < src.position()) {
                throw QuicWireException("Retry packet too short for integrity tag")
            }
            LongHeaderInfo(
                firstByte = firstByte,
                type = type,
                version = version,
                dcid = dcid,
                scid = scid,
                // Retry "token" = bytes between current position and tag.
                initialToken = ByteArray(tagOffset - src.position()).also { src.get(it) },
                payloadLength = 0,
                headerStartOffset = headerStart,
                packetNumberOffset = -1,  // No PN in Retry.
                retryIntegrityTagOffset = tagOffset,
            )
        }
    }
}

/**
 * Short (1-RTT) header parser. Caller must know the DCID length
 * from the handshake (server's chosen length for our local CID).
 *
 * Position after parse points at the protected packet number.
 */
internal fun parseShortHeader(src: ByteBuffer, dcidLength: Int): ShortHeaderInfo {
    require(dcidLength in 0..PacketWire.MAX_CID_LENGTH) {
        "dcidLength $dcidLength out of range"
    }
    val headerStart = src.position()
    if (src.remaining() < 1 + dcidLength) {
        throw QuicWireException(
            "short header truncated: ${src.remaining()} bytes, need ${1 + dcidLength}"
        )
    }
    val firstByte = src.get()
    val firstByteUnsigned = firstByte.toInt() and 0xFF
    if ((firstByteUnsigned and PacketWire.HEADER_FORM_LONG) != 0) {
        throw QuicWireException(
            "expected short header but got long: first byte=0x${firstByteUnsigned.toString(16)}"
        )
    }
    if ((firstByteUnsigned and PacketWire.FIXED_BIT) == 0) {
        throw QuicWireException("fixed bit not set in short header")
    }
    val dcid = ByteArray(dcidLength).also { src.get(it) }
    return ShortHeaderInfo(
        firstByte = firstByte,
        dcid = dcid,
        headerStartOffset = headerStart,
        packetNumberOffset = src.position(),
    )
}

/**
 * Result of encoding a long header's pre-PN portion. Caller
 * appends [packetNumberLength] bytes of PN, then the AEAD-
 * encrypted payload (whose length must match [payloadLength]
 * already baked into the encoded header).
 */
internal data class EncodedLongHeader(
    /** The bytes written, including up to (but not including) the PN. */
    val bytes: ByteArray,
    /** Offset (within `bytes`) of the first byte of the header. Always 0;
     *  surfaced for symmetry with the parser's output. */
    val headerStartOffset: Int = 0,
    /** Offset (within `bytes`) where the PN bytes should be placed when
     *  the caller resumes writing. Equal to `bytes.size`. */
    val packetNumberOffset: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncodedLongHeader) return false
        return bytes.contentEquals(other.bytes) &&
            headerStartOffset == other.headerStartOffset &&
            packetNumberOffset == other.packetNumberOffset
    }
    override fun hashCode(): Int {
        var h = bytes.contentHashCode()
        h = 31 * h + headerStartOffset
        h = 31 * h + packetNumberOffset
        return h
    }
}

/**
 * Encode a long header's unprotected fields up to (but not
 * including) the packet number. Caller must already know:
 *  - [packetNumberLength]: 1..4 bytes (set in the low 2 bits of
 *    the first byte)
 *  - [encryptedPayloadLength]: byte size of (PN + ciphertext +
 *    AEAD tag), which becomes the Length varint. AEAD tag is
 *    always 16 bytes for AES-GCM and ChaCha20-Poly1305.
 *
 * For Initial packets, [token] is the empty array unless the
 * server requested address validation via Retry.
 *
 * For Retry, the encoding shape is different — there is no
 * Length / PN — so this function rejects RETRY. (Clients
 * receive Retry but never send it.)
 */
internal fun encodeLongHeader(
    type: LongPacketType,
    version: Int,
    dcid: ByteArray,
    scid: ByteArray,
    token: ByteArray = ByteArray(0),
    packetNumberLength: Int,
    payloadLengthIncludingPnAndTag: Long,
): EncodedLongHeader {
    require(type != LongPacketType.RETRY) { "clients do not send RETRY packets" }
    require(packetNumberLength in 1..4) { "PN length must be 1..4, got $packetNumberLength" }
    require(dcid.size in 0..PacketWire.MAX_CID_LENGTH) { "DCID length out of range" }
    require(scid.size in 0..PacketWire.MAX_CID_LENGTH) { "SCID length out of range" }
    require(token.size == 0 || type == LongPacketType.INITIAL) {
        "only Initial may carry a token"
    }
    require(payloadLengthIncludingPnAndTag >= 0) { "payload length must be non-negative" }

    // First byte: 1 1 T T R R P P  — where TT is type, RR is
    // reserved (always 0; if non-zero the receiver MUST treat as
    // PROTOCOL_VIOLATION), PP is (PN length - 1).
    val firstByte = (
        PacketWire.HEADER_FORM_LONG
            or PacketWire.FIXED_BIT
            or (type.wireValue shl PacketWire.LONG_TYPE_SHIFT)
            or (packetNumberLength - 1)
        )

    // Size accounting up front so we allocate exactly once.
    var size = 1 + 4 + 1 + dcid.size + 1 + scid.size  // first + version + dcid + scid (with lens)
    if (type == LongPacketType.INITIAL) {
        size += Varint.encodedLength(token.size.toLong()) + token.size
    }
    size += Varint.encodedLength(payloadLengthIncludingPnAndTag)

    val buf = ByteBuffer.allocate(size)
    buf.put(firstByte.toByte())
    buf.putInt(version)
    buf.put(dcid.size.toByte())
    buf.put(dcid)
    buf.put(scid.size.toByte())
    buf.put(scid)
    if (type == LongPacketType.INITIAL) {
        Varint.encode(buf, token.size.toLong())
        if (token.isNotEmpty()) buf.put(token)
    }
    Varint.encode(buf, payloadLengthIncludingPnAndTag)

    val bytes = buf.array()
    return EncodedLongHeader(
        bytes = bytes,
        headerStartOffset = 0,
        packetNumberOffset = bytes.size,
    )
}

/**
 * Encode a short (1-RTT) header's unprotected fields. Caller
 * appends the PN bytes (length encoded in the first byte's low
 * 2 bits) and then the AEAD ciphertext.
 *
 * @param spinBit RFC 9000 §17.4 spin bit; not used for explicit
 *   measurement on our side — set to 0 for safety.
 * @param keyPhase RFC 9000 §17.4 key phase bit. 0 for the
 *   initial 1-RTT keys; toggles on key update.
 */
internal fun encodeShortHeader(
    dcid: ByteArray,
    packetNumberLength: Int,
    spinBit: Boolean = false,
    keyPhase: Boolean = false,
): ByteArray {
    require(packetNumberLength in 1..4) { "PN length must be 1..4, got $packetNumberLength" }
    require(dcid.size in 0..PacketWire.MAX_CID_LENGTH) { "DCID length out of range" }

    // First byte: 0 1 S R R K P P  (form=0, fixed=1, spin, reserved 00,
    // key-phase, PN-length-1).
    var firstByte = PacketWire.FIXED_BIT or (packetNumberLength - 1)
    if (spinBit) firstByte = firstByte or 0x20
    if (keyPhase) firstByte = firstByte or 0x04

    val buf = ByteBuffer.allocate(1 + dcid.size)
    buf.put(firstByte.toByte())
    buf.put(dcid)
    return buf.array()
}
