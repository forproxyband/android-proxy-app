package com.proxyagent.app.nativeagent.quic.crypto

/**
 * One direction's AEAD + header protection material for the
 * AES-128-GCM cipher suite. Both client→server and server→client
 * derive a separate instance from their respective traffic
 * secret.
 *
 * Sizes are fixed by the cipher choice (RFC 9001 §5.1):
 *  - 16-byte AEAD key (AES-128)
 *  - 12-byte AEAD IV (GCM nonce base; the actual per-packet nonce
 *    is `iv XOR padded_packet_number`)
 *  - 16-byte header protection key (also AES-128, used in ECB
 *    mode to encrypt a 16-byte ciphertext sample)
 */
internal data class DirectionalKeys(
    val key: ByteArray,
    val iv: ByteArray,
    val hp: ByteArray,
) {
    init {
        require(key.size == 16) { "AES-128 key must be 16 bytes, got ${key.size}" }
        require(iv.size == 12) { "AEAD IV must be 12 bytes, got ${iv.size}" }
        require(hp.size == 16) { "AES-128 HP key must be 16 bytes, got ${hp.size}" }
    }

    // Deliberately do not implement equals/hashCode — keys should
    // never be compared and we don't want them appearing in maps
    // by accident. The default data-class behavior would compare
    // arrays by reference, which is misleading too; opt out.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
    override fun toString(): String = "DirectionalKeys(<redacted>)"
}

/**
 * QUIC v1 Initial-level key derivation per RFC 9001 §5.2.
 *
 * The Initial secrets are derived from a fixed salt and the
 * **server's chosen connection ID** for the connection — which
 * for the very first Initial packet a client sends is the
 * random DCID the client itself picked (the server adopts it
 * temporarily and echoes it back in its own Initial response).
 *
 * After Handshake-level keys are negotiated through TLS, we
 * derive those separately via [deriveAeadKeys] applied to the
 * TLS-exporter-derived traffic secret.
 */
internal object InitialKeys {

    /** RFC 9001 §5.2 — QUIC v1 fixed initial salt:
     *  `0x38762cf7f55934b34d179ae6a4c80cadccbb7f0a`. */
    private val INITIAL_SALT_V1: ByteArray = byteArrayOf(
        0x38.toByte(), 0x76.toByte(), 0x2c.toByte(), 0xf7.toByte(),
        0xf5.toByte(), 0x59.toByte(), 0x34.toByte(), 0xb3.toByte(),
        0x4d.toByte(), 0x17.toByte(), 0x9a.toByte(), 0xe6.toByte(),
        0xa4.toByte(), 0xc8.toByte(), 0x0c.toByte(), 0xad.toByte(),
        0xcc.toByte(), 0xbb.toByte(), 0x7f.toByte(), 0x0a.toByte(),
    )

    /** Initial keys for both directions of a brand-new connection. */
    data class Pair(val client: DirectionalKeys, val server: DirectionalKeys)

    /**
     * Derive Initial-level keys for both directions, given the
     * server's connection ID (initially picked by the client and
     * echoed by the server).
     */
    fun derive(serverConnectionId: ByteArray): Pair {
        val initialSecret = Hkdf.extract(INITIAL_SALT_V1, serverConnectionId)
        val clientInitialSecret = Hkdf.expandLabel(
            initialSecret, "client in", length = 32
        )
        val serverInitialSecret = Hkdf.expandLabel(
            initialSecret, "server in", length = 32
        )
        return Pair(
            client = deriveAeadKeys(clientInitialSecret),
            server = deriveAeadKeys(serverInitialSecret),
        )
    }

    /**
     * Derive AEAD + HP keys from a per-direction TLS traffic
     * secret. Reused by the Initial path (above) and by the
     * Handshake / 1-RTT paths (driven by Phase 5 once TLS hands
     * over the traffic secrets).
     */
    fun deriveAeadKeys(secret: ByteArray): DirectionalKeys = DirectionalKeys(
        key = Hkdf.expandLabel(secret, "quic key", length = 16),
        iv = Hkdf.expandLabel(secret, "quic iv", length = 12),
        hp = Hkdf.expandLabel(secret, "quic hp", length = 16),
    )
}
