package com.proxyagent.app.nativeagent.quic.tls

import com.proxyagent.app.nativeagent.quic.crypto.Hkdf
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * TLS 1.3 crypto primitives for our hand-rolled handshake.
 *
 * **Scope.** We negotiate exactly one ciphersuite —
 * `TLS_AES_128_GCM_SHA256` (0x1301) — and exactly one key
 * exchange group — `x25519` (0x001d). Everything else is out
 * of scope: no AES-256, no ChaCha20, no P-256/P-384, no RSA/
 * ECDSA cert validation (we use the app-layer AUTH key for
 * trust). This keeps the code small and removes huge swaths
 * of TLS complexity.
 *
 * **X25519 is hand-rolled (RFC 7748), NOT a provider.** Two failed
 * approaches before this:
 *  1. BouncyCastle — broke kwik and every other JCA crypto user in
 *     the app (Android's bundled `org.bouncycastle.*` collides with
 *     a second full BC; confirmed by isolation test).
 *  2. Platform `XDH` KeyAgreement — works on Pixel but throws
 *     `No AlgorithmParameterSpec classes are supported` on Samsung's
 *     Conscrypt. Android's XDH support is inconsistent across
 *     vendors and API levels.
 *
 * So we compute X25519 ourselves with `BigInteger` modular math
 * over GF(2^255 - 19) and the Montgomery ladder (RFC 7748 §5).
 * Deterministic, identical on every device, zero provider deps.
 * NOT constant-time — acceptable here because the shared secret
 * is a one-shot QUIC handshake value, not a long-lived key, and
 * the agent runs on the user's own device (no remote timing
 * oracle). Everything else (HKDF, AES-GCM, SHA-256) stays on the
 * JDK, which is uniform since API 21.
 */
internal object TlsCrypto {

    private val rng = SecureRandom()

    /** 32 random bytes for the ClientHello.random field. */
    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    // ── X25519 (RFC 7748 §5) — hand-rolled BigInteger ladder ───

    /** Raw 32-byte public and private (scalar) keys, both little-endian. */
    class X25519KeyPair(val publicKey: ByteArray, val privateKey: ByteArray) {
        init {
            require(publicKey.size == 32) { "X25519 public key must be 32 bytes" }
            require(privateKey.size == 32) { "X25519 private key must be 32 bytes" }
        }
    }

    /** Field prime 2^255 - 19. */
    private val P25519: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    /** Montgomery curve constant (A-2)/4 = 121665. */
    private val A24: BigInteger = BigInteger.valueOf(121665L)
    /** Base point u-coordinate = 9 (little-endian: byte 0 = 9, rest 0). */
    private val BASE_POINT: ByteArray = ByteArray(32).also { it[0] = 9 }

    /** Generate a key pair: random 32-byte scalar, public =
     *  X25519(scalar, basepoint). */
    fun generateX25519KeyPair(): X25519KeyPair {
        val priv = randomBytes(32)
        val pub = scalarMult(priv, BASE_POINT)
        return X25519KeyPair(pub, priv)
    }

    /**
     * X25519 ECDH: X25519(our_scalar, peer_u). RFC 7748 §6.1 —
     * reject the all-zero output (small-order point attack).
     */
    fun x25519(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        require(privateKey.size == 32 && peerPublicKey.size == 32)
        val out = scalarMult(privateKey, peerPublicKey)
        if (out.all { it == 0.toByte() }) {
            throw TlsException("x25519 yielded all-zero shared secret (peer used small-order point)")
        }
        return out
    }

    /**
     * The X25519 function (RFC 7748 §5): clamp the scalar, decode
     * the u-coordinate, run the Montgomery ladder, return the
     * 32-byte little-endian result.
     */
    private fun scalarMult(scalarBytes: ByteArray, uBytes: ByteArray): ByteArray {
        // Clamp the scalar (RFC 7748 §5).
        val k = scalarBytes.copyOf(32)
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = ((k[31].toInt() and 127) or 64).toByte()
        val kInt = decodeLE(k)

        // Decode u, masking the high bit of the last byte.
        val u = uBytes.copyOf(32)
        u[31] = (u[31].toInt() and 127).toByte()
        val x1 = decodeLE(u).mod(P25519)

        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = 0

        for (t in 254 downTo 0) {
            val kt = if (kInt.testBit(t)) 1 else 0
            swap = swap xor kt
            if (swap == 1) {
                var tmp = x2; x2 = x3; x3 = tmp
                tmp = z2; z2 = z3; z3 = tmp
            }
            swap = kt

            val a = x2.add(z2).mod(P25519)
            val aa = a.multiply(a).mod(P25519)
            val b = x2.subtract(z2).mod(P25519)
            val bb = b.multiply(b).mod(P25519)
            val e = aa.subtract(bb).mod(P25519)
            val c = x3.add(z3).mod(P25519)
            val d = x3.subtract(z3).mod(P25519)
            val da = d.multiply(a).mod(P25519)
            val cb = c.multiply(b).mod(P25519)
            val x3s = da.add(cb).mod(P25519)
            x3 = x3s.multiply(x3s).mod(P25519)
            val z3s = da.subtract(cb).mod(P25519)
            z3 = x1.multiply(z3s.multiply(z3s).mod(P25519)).mod(P25519)
            x2 = aa.multiply(bb).mod(P25519)
            z2 = e.multiply(aa.add(A24.multiply(e).mod(P25519)).mod(P25519)).mod(P25519)
        }
        if (swap == 1) {
            var tmp = x2; x2 = x3; x3 = tmp
            tmp = z2; z2 = z3; z3 = tmp
        }

        // x2 / z2 = x2 * z2^(p-2) mod p (Fermat inverse).
        val zInv = z2.modPow(P25519.subtract(BigInteger.TWO), P25519)
        val result = x2.multiply(zInv).mod(P25519)
        return encodeLE32(result)
    }

    /** 32-byte little-endian → BigInteger (positive). */
    private fun decodeLE(le: ByteArray): BigInteger {
        val be = ByteArray(le.size + 1)  // +1 leading zero forces positive
        for (i in le.indices) be[be.size - 1 - i] = le[i]
        return BigInteger(be)
    }

    /** BigInteger → 32-byte little-endian (truncated / zero-padded). */
    private fun encodeLE32(n: BigInteger): ByteArray {
        val out = ByteArray(32)
        var v = n
        val mask = BigInteger.valueOf(0xFFL)
        for (i in 0 until 32) {
            out[i] = v.and(mask).toInt().toByte()
            v = v.shiftRight(8)
        }
        return out
    }

    // ── SHA-256 ───────────────────────────────────────────────

    /** Hash size in bytes. */
    const val HASH_LEN: Int = 32

    /** SHA-256(data). */
    fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    /** A running transcript hash — fed handshake messages
     *  incrementally as the handshake progresses. */
    class TranscriptHash {
        private val md = MessageDigest.getInstance("SHA-256")
        /** Append [data] (a complete handshake message including
         *  its 4-byte type+length header) to the transcript. */
        fun update(data: ByteArray) { md.update(data) }
        /** Snapshot the transcript hash so far. Repeated calls
         *  yield the same value at the same point in time, but
         *  subsequent [update] calls advance the state. */
        fun snapshot(): ByteArray {
            // MessageDigest does not expose a non-destructive
            // snapshot, so we clone and finalize the clone.
            return (md.clone() as MessageDigest).digest()
        }
    }

    // ── TLS 1.3 key schedule (RFC 8446 §7.1) ─────────────────

    /**
     * TLS 1.3 master / handshake / application secrets derived
     * via the Section 7.1 schedule. We only need the four
     * traffic secrets that QUIC consumes:
     *  - client_handshake_traffic_secret
     *  - server_handshake_traffic_secret
     *  - client_application_traffic_secret_0
     *  - server_application_traffic_secret_0
     *
     * Plus the exporter secret if we ever need 0-RTT — we don't
     * for our use case.
     */
    class KeySchedule {
        private var earlySecret: ByteArray = ByteArray(0)
        private var handshakeSecret: ByteArray = ByteArray(0)
        private var masterSecret: ByteArray = ByteArray(0)

        var clientHandshakeTrafficSecret: ByteArray = ByteArray(0)
            private set
        var serverHandshakeTrafficSecret: ByteArray = ByteArray(0)
            private set
        var clientApplicationTrafficSecret: ByteArray = ByteArray(0)
            private set
        var serverApplicationTrafficSecret: ByteArray = ByteArray(0)
            private set

        /**
         * Run the first stage of the schedule:
         *   early_secret = HKDF-Extract(0, 0)   (no PSK)
         *   handshake_secret = HKDF-Extract(
         *       derive("derived", early_secret, empty_hash),
         *       shared_ecdh_secret)
         *
         * Call after the ECDHE shared secret is known
         * (post-ServerHello key share extraction).
         */
        fun deriveHandshakeSecrets(
            sharedEcdh: ByteArray,
            transcriptHashUpToServerHello: ByteArray,
        ) {
            val zero = ByteArray(HASH_LEN)
            earlySecret = Hkdf.extract(salt = zero, ikm = zero)
            val emptyHash = sha256(ByteArray(0))
            val derived = Hkdf.expandLabel(earlySecret, "derived", HASH_LEN, emptyHash)
            handshakeSecret = Hkdf.extract(salt = derived, ikm = sharedEcdh)
            clientHandshakeTrafficSecret = Hkdf.expandLabel(
                handshakeSecret, "c hs traffic", HASH_LEN, transcriptHashUpToServerHello
            )
            serverHandshakeTrafficSecret = Hkdf.expandLabel(
                handshakeSecret, "s hs traffic", HASH_LEN, transcriptHashUpToServerHello
            )
        }

        /**
         * Run the second stage:
         *   master_secret = HKDF-Extract(
         *       derive("derived", handshake_secret, empty_hash),
         *       0)
         *   application traffic secrets from master_secret and
         *   transcript hash up through server's Finished.
         *
         * Call after the server's Finished message is verified.
         */
        fun deriveApplicationSecrets(transcriptHashUpToServerFinished: ByteArray) {
            check(handshakeSecret.isNotEmpty()) { "deriveHandshakeSecrets must be called first" }
            val emptyHash = sha256(ByteArray(0))
            val derived = Hkdf.expandLabel(handshakeSecret, "derived", HASH_LEN, emptyHash)
            masterSecret = Hkdf.extract(salt = derived, ikm = ByteArray(HASH_LEN))
            clientApplicationTrafficSecret = Hkdf.expandLabel(
                masterSecret, "c ap traffic", HASH_LEN, transcriptHashUpToServerFinished
            )
            serverApplicationTrafficSecret = Hkdf.expandLabel(
                masterSecret, "s ap traffic", HASH_LEN, transcriptHashUpToServerFinished
            )
        }

        /**
         * The Finished MAC key for [secret] (either side's
         * handshake traffic secret), used to compute the
         * Finished verify_data: HMAC-SHA256(finished_key,
         * transcript_hash_up_to_finished).
         */
        fun finishedKey(secret: ByteArray): ByteArray =
            Hkdf.expandLabel(secret, "finished", HASH_LEN)
    }

    // ── Finished verify_data (RFC 8446 §4.4.4) ───────────────

    /**
     * finished_key = HKDF-Expand-Label(base_secret, "finished",
     *                                  "", Hash.length)
     * verify_data  = HMAC-SHA256(finished_key, transcript_hash)
     */
    fun computeFinishedVerifyData(baseSecret: ByteArray, transcriptHash: ByteArray): ByteArray {
        val finishedKey = Hkdf.expandLabel(baseSecret, "finished", HASH_LEN)
        return hmacSha256(finishedKey, transcriptHash)
    }

    /** Constant-time byte-array comparison; for MAC verification. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}

/** Thrown on any TLS handshake violation. Caller (TlsClient
 *  state machine) maps to a CONNECTION_CLOSE with the
 *  appropriate TLS alert / QUIC error code. */
internal class TlsException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
