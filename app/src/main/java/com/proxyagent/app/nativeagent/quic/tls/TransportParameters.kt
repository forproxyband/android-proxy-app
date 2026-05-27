package com.proxyagent.app.nativeagent.quic.tls

import com.proxyagent.app.nativeagent.quic.Varint
import com.proxyagent.app.nativeagent.quic.wire.QuicWireException
import java.nio.ByteBuffer

/**
 * QUIC transport parameters (RFC 9000 §18), exchanged via a TLS
 * extension (extension type 0x39) inside the ClientHello /
 * EncryptedExtensions handshake messages.
 *
 * Each parameter is encoded as `id (varint) || length (varint) ||
 * value (length bytes)`. IDs are sparse — endpoints MUST ignore
 * unknown IDs (RFC 9000 §7.4.2), so we silently skip anything we
 * don't recognize on decode.
 *
 * We send a curated subset that matches what `proxy-agent-sdk-go`
 * negotiates and parse a slightly larger set so the receiver
 * tolerates the server's full TP set without errors.
 */
internal data class TransportParameters(
    /** RFC 9000 §18.2 — connection-level idle timeout in ms. 0 = disabled. */
    val maxIdleTimeoutMs: Long = 60_000,

    /** §18.2 — max UDP datagram size we will accept. Min 1200 per RFC. */
    val maxUdpPayloadSize: Long = 65527,

    /** §18.2 — initial connection-level flow control credit we grant. */
    val initialMaxData: Long = 12L * 1024 * 1024,

    /** §18.2 — initial per-stream credit, bidi peer-initiated. */
    val initialMaxStreamDataBidiLocal: Long = 8L * 1024 * 1024,
    /** §18.2 — initial per-stream credit, bidi we-initiated. */
    val initialMaxStreamDataBidiRemote: Long = 8L * 1024 * 1024,
    /** §18.2 — initial per-stream credit, uni-directional. */
    val initialMaxStreamDataUni: Long = 8L * 1024 * 1024,

    /** §18.2 — max number of bidi streams peer may open. */
    val initialMaxStreamsBidi: Long = 100,
    /** §18.2 — max number of uni streams peer may open. */
    val initialMaxStreamsUni: Long = 100,

    /** §18.2 — exponent for scaling ACK delay values. RFC default 3. */
    val ackDelayExponent: Int = 3,
    /** §18.2 — max ACK delay in ms we promise. RFC default 25. */
    val maxAckDelayMs: Int = 25,

    /** §18.2 — we disable active connection migration (peer must not
     *  expect us to migrate). Set true to tell the peer we won't. */
    val disableActiveMigration: Boolean = true,

    /** §18.2 — peer's choice of initial connection ID (server-side
     *  only; populated when we decode the server's TP). null when
     *  this is our own outgoing parameters. */
    val initialSourceConnectionId: ByteArray? = null,

    /** §18.2 — original DCID the client used in its first Initial.
     *  Server echoes it in its TP; we verify it matches what we sent
     *  to defend against an off-path attacker injecting a Retry. */
    val originalDestinationConnectionId: ByteArray? = null,

    /** Raw bytes of any extensions we didn't parse. Kept so the
     *  encoder can round-trip a parsed-then-re-emitted TP set
     *  during testing without losing fields. */
    val unknownRaw: List<UnknownParam> = emptyList(),
) {
    data class UnknownParam(val id: Long, val value: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is UnknownParam) return false
            return id == other.id && value.contentEquals(other.value)
        }
        override fun hashCode(): Int = 31 * id.hashCode() + value.contentHashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TransportParameters) return false
        return maxIdleTimeoutMs == other.maxIdleTimeoutMs &&
            maxUdpPayloadSize == other.maxUdpPayloadSize &&
            initialMaxData == other.initialMaxData &&
            initialMaxStreamDataBidiLocal == other.initialMaxStreamDataBidiLocal &&
            initialMaxStreamDataBidiRemote == other.initialMaxStreamDataBidiRemote &&
            initialMaxStreamDataUni == other.initialMaxStreamDataUni &&
            initialMaxStreamsBidi == other.initialMaxStreamsBidi &&
            initialMaxStreamsUni == other.initialMaxStreamsUni &&
            ackDelayExponent == other.ackDelayExponent &&
            maxAckDelayMs == other.maxAckDelayMs &&
            disableActiveMigration == other.disableActiveMigration &&
            (initialSourceConnectionId?.contentEquals(other.initialSourceConnectionId) ?: (other.initialSourceConnectionId == null)) &&
            (originalDestinationConnectionId?.contentEquals(other.originalDestinationConnectionId) ?: (other.originalDestinationConnectionId == null)) &&
            unknownRaw == other.unknownRaw
    }

    override fun hashCode(): Int {
        var h = maxIdleTimeoutMs.hashCode()
        h = 31 * h + maxUdpPayloadSize.hashCode()
        h = 31 * h + initialMaxData.hashCode()
        h = 31 * h + initialMaxStreamDataBidiLocal.hashCode()
        h = 31 * h + initialMaxStreamDataBidiRemote.hashCode()
        h = 31 * h + initialMaxStreamDataUni.hashCode()
        h = 31 * h + initialMaxStreamsBidi.hashCode()
        h = 31 * h + initialMaxStreamsUni.hashCode()
        h = 31 * h + ackDelayExponent
        h = 31 * h + maxAckDelayMs
        h = 31 * h + disableActiveMigration.hashCode()
        h = 31 * h + (initialSourceConnectionId?.contentHashCode() ?: 0)
        h = 31 * h + (originalDestinationConnectionId?.contentHashCode() ?: 0)
        h = 31 * h + unknownRaw.hashCode()
        return h
    }

    companion object {
        // TP IDs from IANA QUIC Transport Parameters registry.
        const val TP_ORIGINAL_DESTINATION_CONNECTION_ID: Long = 0x00
        const val TP_MAX_IDLE_TIMEOUT: Long = 0x01
        const val TP_STATELESS_RESET_TOKEN: Long = 0x02
        const val TP_MAX_UDP_PAYLOAD_SIZE: Long = 0x03
        const val TP_INITIAL_MAX_DATA: Long = 0x04
        const val TP_INITIAL_MAX_STREAM_DATA_BIDI_LOCAL: Long = 0x05
        const val TP_INITIAL_MAX_STREAM_DATA_BIDI_REMOTE: Long = 0x06
        const val TP_INITIAL_MAX_STREAM_DATA_UNI: Long = 0x07
        const val TP_INITIAL_MAX_STREAMS_BIDI: Long = 0x08
        const val TP_INITIAL_MAX_STREAMS_UNI: Long = 0x09
        const val TP_ACK_DELAY_EXPONENT: Long = 0x0a
        const val TP_MAX_ACK_DELAY: Long = 0x0b
        const val TP_DISABLE_ACTIVE_MIGRATION: Long = 0x0c
        const val TP_PREFERRED_ADDRESS: Long = 0x0d
        const val TP_ACTIVE_CONNECTION_ID_LIMIT: Long = 0x0e
        const val TP_INITIAL_SOURCE_CONNECTION_ID: Long = 0x0f
        const val TP_RETRY_SOURCE_CONNECTION_ID: Long = 0x10
    }
}

/**
 * Encode [tp] into a byte array suitable for the value of the
 * QUIC transport parameters TLS extension.
 *
 * Encoding format per RFC 9000 §18.1: a sequence of
 * `id (varint) || length (varint) || value (length bytes)` triples.
 */
internal fun encodeTransportParameters(tp: TransportParameters): ByteArray {
    // We size the buffer generously; the encoder writes only as
    // many bytes as it needs, and we return the trimmed slice.
    val buf = ByteBuffer.allocate(1024)

    fun putVarintParam(id: Long, value: Long) {
        Varint.encode(buf, id)
        val valueLen = Varint.encodedLength(value)
        Varint.encode(buf, valueLen.toLong())
        Varint.encode(buf, value)
    }

    fun putEmptyParam(id: Long) {
        Varint.encode(buf, id)
        Varint.encode(buf, 0L)
    }

    fun putBytesParam(id: Long, value: ByteArray) {
        Varint.encode(buf, id)
        Varint.encode(buf, value.size.toLong())
        buf.put(value)
    }

    putVarintParam(TransportParameters.TP_MAX_IDLE_TIMEOUT, tp.maxIdleTimeoutMs)
    putVarintParam(TransportParameters.TP_MAX_UDP_PAYLOAD_SIZE, tp.maxUdpPayloadSize)
    putVarintParam(TransportParameters.TP_INITIAL_MAX_DATA, tp.initialMaxData)
    putVarintParam(TransportParameters.TP_INITIAL_MAX_STREAM_DATA_BIDI_LOCAL, tp.initialMaxStreamDataBidiLocal)
    putVarintParam(TransportParameters.TP_INITIAL_MAX_STREAM_DATA_BIDI_REMOTE, tp.initialMaxStreamDataBidiRemote)
    putVarintParam(TransportParameters.TP_INITIAL_MAX_STREAM_DATA_UNI, tp.initialMaxStreamDataUni)
    putVarintParam(TransportParameters.TP_INITIAL_MAX_STREAMS_BIDI, tp.initialMaxStreamsBidi)
    putVarintParam(TransportParameters.TP_INITIAL_MAX_STREAMS_UNI, tp.initialMaxStreamsUni)
    putVarintParam(TransportParameters.TP_ACK_DELAY_EXPONENT, tp.ackDelayExponent.toLong())
    putVarintParam(TransportParameters.TP_MAX_ACK_DELAY, tp.maxAckDelayMs.toLong())
    if (tp.disableActiveMigration) putEmptyParam(TransportParameters.TP_DISABLE_ACTIVE_MIGRATION)
    if (tp.initialSourceConnectionId != null) {
        putBytesParam(TransportParameters.TP_INITIAL_SOURCE_CONNECTION_ID, tp.initialSourceConnectionId)
    }
    if (tp.originalDestinationConnectionId != null) {
        putBytesParam(TransportParameters.TP_ORIGINAL_DESTINATION_CONNECTION_ID, tp.originalDestinationConnectionId)
    }
    for (u in tp.unknownRaw) putBytesParam(u.id, u.value)

    val out = ByteArray(buf.position())
    System.arraycopy(buf.array(), 0, out, 0, out.size)
    return out
}

/**
 * Decode a QUIC transport parameter blob. Unknown IDs are
 * preserved in [TransportParameters.unknownRaw] for round-trip
 * safety. Throws on malformed length / out-of-range values.
 */
internal fun decodeTransportParameters(bytes: ByteArray): TransportParameters {
    val buf = ByteBuffer.wrap(bytes)
    var maxIdleTimeoutMs = 0L
    var maxUdpPayloadSize = 65527L
    var initialMaxData = 0L
    var initialMaxStreamDataBidiLocal = 0L
    var initialMaxStreamDataBidiRemote = 0L
    var initialMaxStreamDataUni = 0L
    var initialMaxStreamsBidi = 0L
    var initialMaxStreamsUni = 0L
    var ackDelayExponent = 3
    var maxAckDelayMs = 25
    var disableActiveMigration = false
    var initialSourceConnectionId: ByteArray? = null
    var originalDestinationConnectionId: ByteArray? = null
    val unknown = mutableListOf<TransportParameters.UnknownParam>()

    fun readVarintValue(buf: ByteBuffer, declaredLen: Long): Long {
        // The wire format is { len, varint } — declaredLen is len.
        // We just decode the varint; declaredLen must match its size.
        val before = buf.position()
        val v = Varint.decode(buf)
        if ((buf.position() - before).toLong() != declaredLen) {
            throw QuicWireException(
                "transport param value length mismatch: declared=$declaredLen actual=${buf.position() - before}"
            )
        }
        return v
    }

    while (buf.hasRemaining()) {
        val id = Varint.decode(buf)
        val len = Varint.decode(buf)
        if (len < 0 || len > buf.remaining()) {
            throw QuicWireException("transport param length out of range: id=$id len=$len")
        }
        when (id) {
            TransportParameters.TP_MAX_IDLE_TIMEOUT -> maxIdleTimeoutMs = readVarintValue(buf, len)
            TransportParameters.TP_MAX_UDP_PAYLOAD_SIZE -> maxUdpPayloadSize = readVarintValue(buf, len)
            TransportParameters.TP_INITIAL_MAX_DATA -> initialMaxData = readVarintValue(buf, len)
            TransportParameters.TP_INITIAL_MAX_STREAM_DATA_BIDI_LOCAL -> initialMaxStreamDataBidiLocal = readVarintValue(buf, len)
            TransportParameters.TP_INITIAL_MAX_STREAM_DATA_BIDI_REMOTE -> initialMaxStreamDataBidiRemote = readVarintValue(buf, len)
            TransportParameters.TP_INITIAL_MAX_STREAM_DATA_UNI -> initialMaxStreamDataUni = readVarintValue(buf, len)
            TransportParameters.TP_INITIAL_MAX_STREAMS_BIDI -> initialMaxStreamsBidi = readVarintValue(buf, len)
            TransportParameters.TP_INITIAL_MAX_STREAMS_UNI -> initialMaxStreamsUni = readVarintValue(buf, len)
            TransportParameters.TP_ACK_DELAY_EXPONENT -> ackDelayExponent = readVarintValue(buf, len).toInt()
            TransportParameters.TP_MAX_ACK_DELAY -> maxAckDelayMs = readVarintValue(buf, len).toInt()
            TransportParameters.TP_DISABLE_ACTIVE_MIGRATION -> {
                if (len != 0L) throw QuicWireException("disable_active_migration must be empty")
                disableActiveMigration = true
            }
            TransportParameters.TP_INITIAL_SOURCE_CONNECTION_ID -> {
                initialSourceConnectionId = ByteArray(len.toInt()).also { buf.get(it) }
            }
            TransportParameters.TP_ORIGINAL_DESTINATION_CONNECTION_ID -> {
                originalDestinationConnectionId = ByteArray(len.toInt()).also { buf.get(it) }
            }
            else -> {
                // Unknown TP — RFC 9000 §7.4.2: "An endpoint MUST
                // ignore transport parameters that it does not
                // support." Keep raw bytes for round-trip.
                val raw = ByteArray(len.toInt()).also { buf.get(it) }
                unknown.add(TransportParameters.UnknownParam(id, raw))
            }
        }
    }

    return TransportParameters(
        maxIdleTimeoutMs = maxIdleTimeoutMs,
        maxUdpPayloadSize = maxUdpPayloadSize,
        initialMaxData = initialMaxData,
        initialMaxStreamDataBidiLocal = initialMaxStreamDataBidiLocal,
        initialMaxStreamDataBidiRemote = initialMaxStreamDataBidiRemote,
        initialMaxStreamDataUni = initialMaxStreamDataUni,
        initialMaxStreamsBidi = initialMaxStreamsBidi,
        initialMaxStreamsUni = initialMaxStreamsUni,
        ackDelayExponent = ackDelayExponent,
        maxAckDelayMs = maxAckDelayMs,
        disableActiveMigration = disableActiveMigration,
        initialSourceConnectionId = initialSourceConnectionId,
        originalDestinationConnectionId = originalDestinationConnectionId,
        unknownRaw = unknown,
    )
}
