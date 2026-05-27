package com.proxyagent.app.nativeagent.quic.tls

import com.proxyagent.app.nativeagent.quic.crypto.Hkdf
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
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
 * **Why BouncyCastle for X25519 and JDK for everything else.**
 * Android API 30+ has `KeyPairGenerator.getInstance("X25519")`,
 * but our minSdk is 21 — BC is the portable choice. Everything
 * else (SHA-256, HMAC, AES-GCM) is on every Android since 21.
 */
internal object TlsCrypto {

    private val rng = SecureRandom()

    /** 32 random bytes for the ClientHello.random field. */
    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    // ── X25519 (RFC 7748 §6.1) ────────────────────────────────

    data class X25519KeyPair(val publicKey: ByteArray, val privateKey: ByteArray) {
        init {
            require(publicKey.size == 32) { "X25519 public key must be 32 bytes" }
            require(privateKey.size == 32) { "X25519 private key must be 32 bytes" }
        }
    }

    /** Generate a fresh X25519 key pair. */
    fun generateX25519KeyPair(): X25519KeyPair {
        val priv = X25519PrivateKeyParameters(rng)
        val pub = priv.generatePublicKey()
        return X25519KeyPair(
            publicKey = pub.encoded,
            privateKey = priv.encoded,
        )
    }

    /**
     * X25519 ECDH: given our private key and peer's public key,
     * compute the 32-byte shared secret. RFC 7748 §6.1 says to
     * reject the all-zero output (would mean peer chose a
     * malicious public key forcing a known shared secret) — we
     * follow that, throwing on contributory-behavior failure.
     */
    fun x25519(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        require(privateKey.size == 32 && peerPublicKey.size == 32)
        val priv = X25519PrivateKeyParameters(privateKey, 0)
        val pub = X25519PublicKeyParameters(peerPublicKey, 0)
        val out = ByteArray(32)
        priv.generateSecret(pub, out, 0)
        if (out.all { it == 0.toByte() }) {
            throw TlsException("x25519 yielded all-zero shared secret (peer used small-order point)")
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
