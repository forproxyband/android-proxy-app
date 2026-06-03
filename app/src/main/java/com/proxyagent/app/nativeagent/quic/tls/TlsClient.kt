package com.proxyagent.app.nativeagent.quic.tls

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal TLS 1.3 client state machine for QUIC (RFC 9001).
 *
 * **Scope.** We negotiate exactly one combination:
 *  - ciphersuite `TLS_AES_128_GCM_SHA256` (0x1301)
 *  - group `x25519` (0x001d)
 *  - signature `ecdsa_secp256r1_sha256` (0x0403) — advertised but
 *    not validated; the app-layer AUTH key is what we actually
 *    trust.
 *  - no PSK / 0-RTT / session resumption
 *  - no client certificates
 *
 * **Why hand-rolled vs BouncyCastle's `TlsClientProtocol`.** BC's
 * TLS API works at the TLS record layer; QUIC bypasses records
 * entirely and carries raw handshake messages in CRYPTO frames.
 * Trying to strip records out of BC's output is hackier than
 * writing the state machine ourselves and using BC only for
 * the X25519 primitive.
 *
 * **Threading.** Single-threaded — the connection layer drives
 * the client from one thread (its receive loop). No internal
 * locking.
 *
 * **State machine.** Linear, no backtracking:
 *
 *   START
 *     │ start() → ClientHello bytes
 *     ▼
 *   WAIT_SH
 *     │ processCryptoData(Initial, ServerHello)
 *     │ → derive handshake secrets; surface (HS, RX) + (HS, TX)
 *     ▼
 *   WAIT_EE
 *     │ processCryptoData(Handshake, EncryptedExtensions)
 *     │ → record peer transport parameters
 *     ▼
 *   WAIT_CERT (or WAIT_SF if cert-less, but quic-go always sends cert)
 *     │ processCryptoData(Handshake, Certificate)
 *     │ → ignore body
 *     ▼
 *   WAIT_CV
 *     │ processCryptoData(Handshake, CertificateVerify)
 *     │ → ignore body
 *     ▼
 *   WAIT_SF
 *     │ processCryptoData(Handshake, Finished)
 *     │ → verify server's Finished MAC, derive application secrets,
 *     │   surface (AP, RX) + (AP, TX), emit our Finished in Handshake
 *     ▼
 *   DONE
 *
 * Any incoming CRYPTO data at the wrong level or with an
 * unexpected message type throws [TlsException].
 *
 * **What this client does NOT do.**
 *  - Validate the server certificate. We're told the peer is
 *    legit by the AUTH key check on the QUIC control stream.
 *  - Validate CertificateVerify signature. Same reason.
 *  - Handle HelloRetryRequest. quic-go does not send one for
 *    our supported_groups list, so we'd close the connection.
 *  - Process NewSessionTicket. quic-go sends these post-
 *    handshake; we parse the message header to skip past them
 *    and otherwise ignore.
 *  - 0-RTT, key updates, post-handshake auth.
 */
internal class TlsClient(
    private val serverName: String,
    private val alpn: String,
    private val ourTransportParameters: TransportParameters,
) {

    /** TLS encryption levels — map 1:1 to QUIC packet number spaces. */
    enum class Level { INITIAL, HANDSHAKE, APPLICATION }
    enum class Direction { TX, RX }

    /** A traffic secret made available by a state transition.
     *  The QUIC layer derives AEAD + HP keys from these via
     *  `InitialKeys.deriveAeadKeys` and installs them in the
     *  appropriate packet-number-space context. */
    data class NewSecret(val level: Level, val direction: Direction, val trafficSecret: ByteArray)

    /** Per-call return — what TLS wants the QUIC layer to do next. */
    data class HandshakeStep(
        /** CRYPTO frame payload to send, keyed by level. */
        val outgoing: Map<Level, ByteArray> = emptyMap(),
        /** New traffic secrets to install. */
        val newSecrets: List<NewSecret> = emptyList(),
        /** Handshake just completed this step. */
        val handshakeComplete: Boolean = false,
        /** Set when EncryptedExtensions has been parsed. */
        val peerTransportParameters: TransportParameters? = null,
    )

    // ── State ─────────────────────────────────────────────────

    private enum class State { START, WAIT_SH, WAIT_EE, WAIT_CERT, WAIT_CV, WAIT_SF, DONE, FAILED }

    private var state: State = State.START
    private var keyPair: TlsCrypto.X25519KeyPair? = null
    private val transcript = TlsCrypto.TranscriptHash()
    private val keySchedule = TlsCrypto.KeySchedule()
    private val random = TlsCrypto.randomBytes(32)
    private val legacySessionId = TlsCrypto.randomBytes(32)
    private var sharedSecret: ByteArray? = null
    /** Re-assembly buffers for fragmented handshake messages, per level. */
    private val rxBuffers: MutableMap<Level, ByteBuffer> = mutableMapOf()

    // ── Public API ────────────────────────────────────────────

    /** Build and emit the ClientHello. Returns its bytes to be
     *  written into a CRYPTO frame in the Initial packet space. */
    fun start(): HandshakeStep {
        check(state == State.START) { "TlsClient already started (state=$state)" }
        keyPair = TlsCrypto.generateX25519KeyPair()
        val ch = encodeClientHello(
            random = random,
            legacySessionId = legacySessionId,
            keyShare = keyPair!!.publicKey,
            alpn = alpn,
            serverName = serverName,
            transportParameters = ourTransportParameters,
        )
        transcript.update(ch)
        state = State.WAIT_SH
        return HandshakeStep(outgoing = mapOf(Level.INITIAL to ch))
    }

    /**
     * Feed bytes received in a CRYPTO frame at the given
     * encryption level. The bytes may contain a partial
     * message, a single message, or multiple coalesced
     * messages; we re-assemble across calls.
     */
    fun processCryptoData(level: Level, data: ByteArray): HandshakeStep {
        // RFC 9001 §4.1.3 + RFC 9000 §17.2.2.1: legitimately late CRYPTO
        // frames at the Initial / Handshake level after our TLS state
        // machine has already moved to DONE are retransmits the server
        // sent before our ACKs reached it. The packet's other frames
        // (NEW_CONNECTION_ID, NEW_TOKEN, ACK, etc.) are still processed
        // and the containing packet is still ACKed by the QUIC layer —
        // it's the CRYPTO payload alone we must silently drop here.
        //
        // For APPLICATION-level CRYPTO (NewSessionTicket, post-handshake
        // auth in TLS 1.3) we also no-op: this client doesn't resume
        // sessions, so there's nothing to do with the bytes either way.
        //
        // FAILED stays a hard skip because a failed TLS state machine
        // must not be coerced into reprocessing input.
        //
        // Pre-fix this method `check`ed and threw IllegalStateException,
        // which propagated out of `processLongPacket` in the recv loop
        // and stalled the rest of the connection — the e2e tests caught
        // this by way of QUIC handshake-timeout fallback to TCP under
        // moderate loopback-burst retransmits (see
        // logcat-...-ConcurrentTunnelsTest, run #6).
        if (state == State.DONE || state == State.FAILED) {
            return HandshakeStep()
        }
        val buf = rxBuffers.getOrPut(level) { ByteBuffer.allocate(64 * 1024).order(ByteOrder.BIG_ENDIAN) }
        // Compact if we'd overflow, growing if needed.
        if (buf.remaining() < data.size) growBuffer(level, data.size)
        rxBuffers[level]!!.put(data)

        var step = HandshakeStep()
        // Process as many complete messages as we have buffered.
        while (true) {
            val active = rxBuffers[level]!!
            val pos = active.position()
            if (pos < 4) break  // need at least the 1+3 byte handshake header
            // Peek the message length without consuming.
            val msgLen = ((active.get(1).toInt() and 0xFF) shl 16) or
                         ((active.get(2).toInt() and 0xFF) shl 8) or
                         (active.get(3).toInt() and 0xFF)
            if (pos < 4 + msgLen) break  // not enough buffered yet
            // Extract one complete message.
            val msgBytes = ByteArray(4 + msgLen)
            active.flip()
            active.get(msgBytes)
            active.compact()
            val nextStep = consumeMessage(level, msgBytes)
            step = step.merge(nextStep)
            if (state == State.DONE || state == State.FAILED) break
        }
        return step
    }

    // ── Message dispatch ─────────────────────────────────────

    private fun consumeMessage(level: Level, msg: ByteArray): HandshakeStep {
        val type = msg[0].toInt() and 0xFF
        return when (type) {
            HS_TYPE_SERVER_HELLO -> handleServerHello(level, msg)
            HS_TYPE_ENCRYPTED_EXTENSIONS -> handleEncryptedExtensions(level, msg)
            HS_TYPE_CERTIFICATE -> handleCertificate(level, msg)
            HS_TYPE_CERTIFICATE_VERIFY -> handleCertificateVerify(level, msg)
            HS_TYPE_FINISHED -> handleFinished(level, msg)
            HS_TYPE_NEW_SESSION_TICKET -> handleNewSessionTicket(level, msg)
            else -> {
                state = State.FAILED
                throw TlsException("unexpected handshake type $type in state $state")
            }
        }
    }

    private fun handleServerHello(level: Level, msg: ByteArray): HandshakeStep {
        require(state == State.WAIT_SH) { "ServerHello in state $state" }
        require(level == Level.INITIAL) { "ServerHello must be at Initial level" }
        val sh = decodeServerHello(msg)
        require(sh.cipherSuite == CIPHER_TLS_AES_128_GCM_SHA256) {
            "server selected unsupported cipher 0x${sh.cipherSuite.toString(16)}"
        }
        require(sh.selectedVersion == TLS_VERSION_1_3) {
            "server selected non-TLS-1.3 version 0x${sh.selectedVersion.toString(16)}"
        }
        require(sh.keyShareGroup == GROUP_X25519) {
            "server selected unsupported group 0x${sh.keyShareGroup.toString(16)}"
        }
        require(sh.keyShare.size == 32) { "server x25519 public key wrong size: ${sh.keyShare.size}" }

        // ECDHE.
        val shared = TlsCrypto.x25519(keyPair!!.privateKey, sh.keyShare)
        sharedSecret = shared

        // Transcript = ClientHello || ServerHello.
        transcript.update(msg)
        val thAfterSh = transcript.snapshot()
        keySchedule.deriveHandshakeSecrets(shared, thAfterSh)
        state = State.WAIT_EE

        return HandshakeStep(
            newSecrets = listOf(
                NewSecret(Level.HANDSHAKE, Direction.TX, keySchedule.clientHandshakeTrafficSecret),
                NewSecret(Level.HANDSHAKE, Direction.RX, keySchedule.serverHandshakeTrafficSecret),
            ),
        )
    }

    private fun handleEncryptedExtensions(level: Level, msg: ByteArray): HandshakeStep {
        require(state == State.WAIT_EE) { "EncryptedExtensions in state $state" }
        require(level == Level.HANDSHAKE) { "EncryptedExtensions must be at Handshake level" }
        val ee = decodeEncryptedExtensions(msg)
        // Validate ALPN negotiated as expected (peer may omit, but if
        // present must match our advertised value).
        if (ee.alpn != null && ee.alpn != alpn) {
            throw TlsException("ALPN mismatch: wanted '$alpn', got '${ee.alpn}'")
        }
        val peerTp = ee.transportParameters
            ?: throw TlsException("server omitted quic_transport_parameters extension")
        transcript.update(msg)
        state = State.WAIT_CERT
        return HandshakeStep(peerTransportParameters = peerTp)
    }

    private fun handleCertificate(level: Level, msg: ByteArray): HandshakeStep {
        require(state == State.WAIT_CERT) { "Certificate in state $state" }
        require(level == Level.HANDSHAKE)
        // We intentionally don't parse / validate the cert chain.
        // Just feed it into the transcript so our Finished comes out
        // right. Trust comes from the app-layer AUTH key.
        transcript.update(msg)
        state = State.WAIT_CV
        return HandshakeStep()
    }

    private fun handleCertificateVerify(level: Level, msg: ByteArray): HandshakeStep {
        require(state == State.WAIT_CV) { "CertificateVerify in state $state" }
        require(level == Level.HANDSHAKE)
        // Same posture as Certificate — feed transcript, skip body.
        transcript.update(msg)
        state = State.WAIT_SF
        return HandshakeStep()
    }

    private fun handleFinished(level: Level, msg: ByteArray): HandshakeStep {
        require(state == State.WAIT_SF) { "Finished in state $state" }
        require(level == Level.HANDSHAKE)
        // Verify server's Finished: HMAC(finished_key,
        // transcript_hash_up_to_but_not_including_this_msg).
        val thBeforeFinished = transcript.snapshot()
        val expected = TlsCrypto.computeFinishedVerifyData(
            keySchedule.serverHandshakeTrafficSecret, thBeforeFinished
        )
        val verifyData = msg.copyOfRange(4, msg.size)  // strip 4-byte header
        if (!TlsCrypto.constantTimeEquals(expected, verifyData)) {
            state = State.FAILED
            throw TlsException("server Finished MAC verification failed")
        }
        // Include server's Finished in the transcript before
        // deriving application secrets and our own Finished.
        transcript.update(msg)
        val thAfterServerFinished = transcript.snapshot()
        keySchedule.deriveApplicationSecrets(thAfterServerFinished)

        // Build our Finished using c_hs_traffic + transcript so far.
        val ourVerifyData = TlsCrypto.computeFinishedVerifyData(
            keySchedule.clientHandshakeTrafficSecret, thAfterServerFinished
        )
        val ourFinishedMsg = encodeFinished(ourVerifyData)
        transcript.update(ourFinishedMsg)

        state = State.DONE
        return HandshakeStep(
            outgoing = mapOf(Level.HANDSHAKE to ourFinishedMsg),
            newSecrets = listOf(
                NewSecret(Level.APPLICATION, Direction.TX, keySchedule.clientApplicationTrafficSecret),
                NewSecret(Level.APPLICATION, Direction.RX, keySchedule.serverApplicationTrafficSecret),
            ),
            handshakeComplete = true,
        )
    }

    private fun handleNewSessionTicket(level: Level, msg: ByteArray): HandshakeStep {
        // Post-handshake message at application level. We don't
        // resume sessions, so just discard. (We must be in DONE
        // state to receive these.)
        require(state == State.DONE) { "NewSessionTicket before handshake done" }
        require(level == Level.APPLICATION)
        return HandshakeStep()
    }

    private fun HandshakeStep.merge(other: HandshakeStep): HandshakeStep {
        // Merge two HandshakeSteps; outgoing maps coalesce per level.
        val merged = LinkedHashMap<Level, ByteArray>(outgoing)
        for ((lvl, bytes) in other.outgoing) {
            val existing = merged[lvl]
            merged[lvl] = if (existing == null) bytes
                          else existing + bytes
        }
        return HandshakeStep(
            outgoing = merged,
            newSecrets = newSecrets + other.newSecrets,
            handshakeComplete = handshakeComplete || other.handshakeComplete,
            peerTransportParameters = other.peerTransportParameters ?: peerTransportParameters,
        )
    }

    private fun growBuffer(level: Level, needed: Int) {
        val cur = rxBuffers[level]!!
        val newCap = ((cur.position() + needed) * 2).coerceAtLeast(cur.capacity())
        val grown = ByteBuffer.allocate(newCap).order(ByteOrder.BIG_ENDIAN)
        cur.flip()
        grown.put(cur)
        rxBuffers[level] = grown
    }

    companion object {
        // TLS handshake message types (RFC 8446 §B.3).
        const val HS_TYPE_CLIENT_HELLO = 0x01
        const val HS_TYPE_SERVER_HELLO = 0x02
        const val HS_TYPE_NEW_SESSION_TICKET = 0x04
        const val HS_TYPE_ENCRYPTED_EXTENSIONS = 0x08
        const val HS_TYPE_CERTIFICATE = 0x0b
        const val HS_TYPE_CERTIFICATE_VERIFY = 0x0f
        const val HS_TYPE_FINISHED = 0x14

        // Cipher suites and groups (IANA registries).
        const val CIPHER_TLS_AES_128_GCM_SHA256 = 0x1301
        const val GROUP_X25519 = 0x001d
        const val SIGALG_ECDSA_SECP256R1_SHA256 = 0x0403
        const val SIGALG_RSA_PSS_RSAE_SHA256 = 0x0804  // also advertise; common server cert algo

        const val TLS_VERSION_1_2_LEGACY = 0x0303
        const val TLS_VERSION_1_3 = 0x0304

        // Extension types (RFC 8446 §4.2).
        const val EXT_SERVER_NAME = 0x00
        const val EXT_SUPPORTED_GROUPS = 0x0a
        const val EXT_SIGNATURE_ALGORITHMS = 0x0d
        const val EXT_ALPN = 0x10
        const val EXT_SUPPORTED_VERSIONS = 0x2b
        const val EXT_PSK_KEY_EXCHANGE_MODES = 0x2d
        const val EXT_KEY_SHARE = 0x33
        const val EXT_QUIC_TRANSPORT_PARAMETERS = 0x39
    }
}

// ────────────────────────────────────────────────────────────────────
// Handshake message encoders / decoders
// ────────────────────────────────────────────────────────────────────

/**
 * Encode a TLS 1.3 ClientHello message (RFC 8446 §4.1.2).
 *
 * The 4-byte handshake header (type + 3-byte length) is included
 * — TLS feeds whole handshake messages to the transcript hash,
 * and QUIC's CRYPTO frame carries the message verbatim.
 */
internal fun encodeClientHello(
    random: ByteArray,
    legacySessionId: ByteArray,
    keyShare: ByteArray,
    alpn: String,
    serverName: String,
    transportParameters: TransportParameters,
): ByteArray {
    require(random.size == 32)
    require(legacySessionId.size in 0..32)
    require(keyShare.size == 32)
    val body = ByteBuffer.allocate(2048).order(ByteOrder.BIG_ENDIAN)
    body.putShort(TlsClient.TLS_VERSION_1_2_LEGACY.toShort())  // legacy_version
    body.put(random)
    body.put(legacySessionId.size.toByte())                     // session id length
    body.put(legacySessionId)
    body.putShort(2.toShort())                                  // cipher_suites length (2 = one suite)
    body.putShort(TlsClient.CIPHER_TLS_AES_128_GCM_SHA256.toShort())
    body.put(1.toByte())                                        // compression_methods length
    body.put(0.toByte())                                        // null compression

    // Extensions — length-prefixed, we patch in the size at the end.
    val extLenPos = body.position()
    body.putShort(0)                                            // placeholder
    val extStart = body.position()

    // supported_versions: 1-byte list length + 2-byte version.
    writeExt(body, TlsClient.EXT_SUPPORTED_VERSIONS) {
        it.put(2.toByte())
        it.putShort(TlsClient.TLS_VERSION_1_3.toShort())
    }
    // supported_groups: 2-byte list length + N * 2-byte groups.
    writeExt(body, TlsClient.EXT_SUPPORTED_GROUPS) {
        it.putShort(2.toShort())
        it.putShort(TlsClient.GROUP_X25519.toShort())
    }
    // signature_algorithms (peers usually require this in CH even
    // if we don't validate; advertise a couple of common ones).
    writeExt(body, TlsClient.EXT_SIGNATURE_ALGORITHMS) {
        it.putShort(4.toShort())
        it.putShort(TlsClient.SIGALG_ECDSA_SECP256R1_SHA256.toShort())
        it.putShort(TlsClient.SIGALG_RSA_PSS_RSAE_SHA256.toShort())
    }
    // key_share: 2-byte client_shares length + entries (each 2 byte group + 2 byte length + key).
    writeExt(body, TlsClient.EXT_KEY_SHARE) {
        it.putShort((2 + 2 + 32).toShort())  // shares list length
        it.putShort(TlsClient.GROUP_X25519.toShort())
        it.putShort(32.toShort())
        it.put(keyShare)
    }
    // ALPN: 2-byte protocols length + entries (1-byte length + utf-8 name).
    val alpnBytes = alpn.toByteArray(Charsets.US_ASCII)
    writeExt(body, TlsClient.EXT_ALPN) {
        it.putShort((1 + alpnBytes.size).toShort())
        it.put(alpnBytes.size.toByte())
        it.put(alpnBytes)
    }
    // Server Name Indication: 2-byte list length + (1 byte name type + 2 byte length + name).
    if (serverName.isNotEmpty()) {
        val snBytes = serverName.toByteArray(Charsets.US_ASCII)
        writeExt(body, TlsClient.EXT_SERVER_NAME) {
            it.putShort((1 + 2 + snBytes.size).toShort())  // list length
            it.put(0.toByte())                              // host_name
            it.putShort(snBytes.size.toShort())
            it.put(snBytes)
        }
    }
    // QUIC transport parameters: opaque bytes.
    val tpBytes = encodeTransportParameters(transportParameters)
    writeExt(body, TlsClient.EXT_QUIC_TRANSPORT_PARAMETERS) {
        it.put(tpBytes)
    }

    // Patch the extensions length.
    val extLen = body.position() - extStart
    body.putShort(extLenPos, extLen.toShort())

    val bodyLen = body.position()
    val out = ByteBuffer.allocate(4 + bodyLen).order(ByteOrder.BIG_ENDIAN)
    out.put(TlsClient.HS_TYPE_CLIENT_HELLO.toByte())
    out.put((bodyLen shr 16).toByte())
    out.put((bodyLen shr 8).toByte())
    out.put(bodyLen.toByte())
    out.put(body.array(), 0, bodyLen)
    return out.array()
}

/** Helper: write a TLS extension whose body is filled by [writeBody].
 *  Allocates a scratch buffer, calls the writer, then emits
 *  `ext_type || ext_length || body`. */
private fun writeExt(dst: ByteBuffer, type: Int, writeBody: (ByteBuffer) -> Unit) {
    dst.putShort(type.toShort())
    val lenPos = dst.position()
    dst.putShort(0)
    val start = dst.position()
    writeBody(dst)
    val len = dst.position() - start
    dst.putShort(lenPos, len.toShort())
}

internal data class ServerHelloFields(
    val random: ByteArray,
    val cipherSuite: Int,
    val selectedVersion: Int,
    val keyShareGroup: Int,
    val keyShare: ByteArray,
)

/** Decode a TLS 1.3 ServerHello — strict on our supported subset. */
internal fun decodeServerHello(msg: ByteArray): ServerHelloFields {
    val buf = ByteBuffer.wrap(msg).order(ByteOrder.BIG_ENDIAN)
    require((buf.get().toInt() and 0xFF) == TlsClient.HS_TYPE_SERVER_HELLO) {
        "not a ServerHello"
    }
    val bodyLen = ((buf.get().toInt() and 0xFF) shl 16) or
                  ((buf.get().toInt() and 0xFF) shl 8) or
                  (buf.get().toInt() and 0xFF)
    require(bodyLen + 4 == msg.size) { "ServerHello body length mismatch" }

    val legacyVer = buf.short.toInt() and 0xFFFF
    if (legacyVer != TlsClient.TLS_VERSION_1_2_LEGACY) {
        throw TlsException("ServerHello legacy_version != 0x0303 ($legacyVer)")
    }
    val random = ByteArray(32).also { buf.get(it) }
    val sidLen = buf.get().toInt() and 0xFF
    require(sidLen in 0..32) { "ServerHello legacy_session_id length $sidLen out of range" }
    buf.position(buf.position() + sidLen)  // ignore the echoed session id
    val cipher = buf.short.toInt() and 0xFFFF
    val compMethod = buf.get().toInt() and 0xFF
    if (compMethod != 0) throw TlsException("ServerHello compression_method != 0 ($compMethod)")

    val extLen = buf.short.toInt() and 0xFFFF
    require(extLen == buf.remaining()) { "ServerHello extensions length mismatch" }
    var selectedVersion = 0
    var keyShareGroup = -1
    var keyShare = ByteArray(0)
    while (buf.hasRemaining()) {
        val extType = buf.short.toInt() and 0xFFFF
        val l = buf.short.toInt() and 0xFFFF
        val payloadEnd = buf.position() + l
        when (extType) {
            TlsClient.EXT_SUPPORTED_VERSIONS -> {
                selectedVersion = buf.short.toInt() and 0xFFFF
            }
            TlsClient.EXT_KEY_SHARE -> {
                keyShareGroup = buf.short.toInt() and 0xFFFF
                val keyLen = buf.short.toInt() and 0xFFFF
                keyShare = ByteArray(keyLen).also { buf.get(it) }
            }
            // Ignore others.
        }
        buf.position(payloadEnd)
    }
    return ServerHelloFields(random, cipher, selectedVersion, keyShareGroup, keyShare)
}

internal data class EncryptedExtensionsFields(
    val alpn: String?,
    val transportParameters: TransportParameters?,
)

/** Decode an EncryptedExtensions message — surfaces ALPN and the
 *  QUIC transport parameters extension, ignores the rest. */
internal fun decodeEncryptedExtensions(msg: ByteArray): EncryptedExtensionsFields {
    val buf = ByteBuffer.wrap(msg).order(ByteOrder.BIG_ENDIAN)
    require((buf.get().toInt() and 0xFF) == TlsClient.HS_TYPE_ENCRYPTED_EXTENSIONS)
    // Skip 3-byte length (we trust msg.size).
    buf.position(buf.position() + 3)
    val extLen = buf.short.toInt() and 0xFFFF
    require(extLen == buf.remaining())
    var alpn: String? = null
    var tp: TransportParameters? = null
    while (buf.hasRemaining()) {
        val extType = buf.short.toInt() and 0xFFFF
        val l = buf.short.toInt() and 0xFFFF
        val payloadEnd = buf.position() + l
        when (extType) {
            TlsClient.EXT_ALPN -> {
                val listLen = buf.short.toInt() and 0xFFFF
                val nameLen = buf.get().toInt() and 0xFF
                val nameBytes = ByteArray(nameLen).also { buf.get(it) }
                alpn = String(nameBytes, Charsets.US_ASCII)
                // Skip any further entries (server should send only one).
            }
            TlsClient.EXT_QUIC_TRANSPORT_PARAMETERS -> {
                val tpBytes = ByteArray(l).also { buf.get(it) }
                tp = decodeTransportParameters(tpBytes)
            }
        }
        buf.position(payloadEnd)
    }
    return EncryptedExtensionsFields(alpn, tp)
}

/** Encode a Finished handshake message — just the 4-byte header
 *  plus the 32-byte verify_data (HMAC-SHA256 output). */
internal fun encodeFinished(verifyData: ByteArray): ByteArray {
    val len = verifyData.size
    val out = ByteArray(4 + len)
    out[0] = TlsClient.HS_TYPE_FINISHED.toByte()
    out[1] = ((len shr 16) and 0xFF).toByte()
    out[2] = ((len shr 8) and 0xFF).toByte()
    out[3] = (len and 0xFF).toByte()
    System.arraycopy(verifyData, 0, out, 4, len)
    return out
}
