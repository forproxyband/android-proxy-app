package com.proxyagent.app.nativeagent.quic.tls

import com.proxyagent.app.nativeagent.quic.crypto.Hkdf
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.XECPublicKey
import java.security.spec.NamedParameterSpec
import java.security.spec.XECPublicKeySpec
import javax.crypto.KeyAgreement

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
 * **Why Android's built-in XDH for X25519, NOT BouncyCastle.**
 * We originally pulled BouncyCastle for X25519. That broke kwik
 * (and would break any other JCA crypto user in the app):
 * Android bundles its own `org.bouncycastle.*` in the platform,
 * and a second full BC under the same package names corrupts the
 * JCA provider chain — empirically confirmed by an isolation test
 * (kwik QUIC went from working to ERR_TIMED_OUT the moment BC was
 * on the classpath, and recovered the moment it was removed).
 *
 * So we use the platform's `XDH` KeyAgreement (Conscrypt) instead.
 * X25519 via `XDH` is available on API 33+ (and on the API 36
 * devices we target). On older devices `KeyPairGenerator`/
 * `KeyAgreement` throw `NoSuchAlgorithmException`, which surfaces
 * as a handshake failure and the agent falls back to TCP or kwik.
 * No third-party dependency, no package conflict.
 */
internal object TlsCrypto {

    private val rng = SecureRandom()

    /** 32 random bytes for the ClientHello.random field. */
    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    // ── X25519 (RFC 7748 §6.1) via the platform XDH provider ───

    /** Holds the raw 32-byte public key (for the ClientHello key
     *  share) and the live [PrivateKey] object (for ECDH — we keep
     *  the object rather than raw scalar bytes because some
     *  providers don't expose the scalar). */
    class X25519KeyPair(val publicKey: ByteArray, val privateKey: PrivateKey) {
        init { require(publicKey.size == 32) { "X25519 public key must be 32 bytes" } }
    }

    private val x25519Params = NamedParameterSpec("X25519")

    /** Generate a fresh X25519 key pair via the platform provider. */
    fun generateX25519KeyPair(): X25519KeyPair {
        val kpg = KeyPairGenerator.getInstance("XDH")
        kpg.initialize(x25519Params)
        val kp = kpg.generateKeyPair()
        val pubRaw = encodeU((kp.public as XECPublicKey).u)
        return X25519KeyPair(pubRaw, kp.private)
    }

    /**
     * X25519 ECDH between our [privateKey] and the peer's raw
     * 32-byte public key. RFC 7748 §6.1 — reject the all-zero
     * shared secret (small-order point attack).
     */
    fun x25519(privateKey: PrivateKey, peerPublicKey: ByteArray): ByteArray {
        require(peerPublicKey.size == 32) { "peer X25519 key must be 32 bytes" }
        val kf = KeyFactory.getInstance("XDH")
        val peerPub = kf.generatePublic(XECPublicKeySpec(x25519Params, decodeU(peerPublicKey)))
        val ka = KeyAgreement.getInstance("XDH")
        ka.init(privateKey)
        ka.doPhase(peerPub, true)
        val out = ka.generateSecret()
        if (out.size != 32 || out.all { it == 0.toByte() }) {
            throw TlsException("x25519 produced invalid shared secret (len=${out.size})")
        }
        return out
    }

    /** Encode an X25519 u-coordinate [BigInteger] as 32 bytes,
     *  little-endian (RFC 7748 wire format). */
    private fun encodeU(u: BigInteger): ByteArray {
        val be = u.toByteArray()  // big-endian, possibly with a leading sign byte
        val out = ByteArray(32)   // little-endian, zero-padded
        var src = be.size - 1     // least-significant byte of the value
        var dst = 0
        while (src >= 0 && dst < 32) {
            out[dst] = be[src]
            src--; dst++
        }
        return out
    }

    /** Decode a 32-byte little-endian X25519 public key into the
     *  u-coordinate [BigInteger] the JCA spec expects. */
    private fun decodeU(raw: ByteArray): BigInteger {
        // Reverse to big-endian, prepend a zero byte to force a
        // positive BigInteger regardless of the high bit.
        val be = ByteArray(raw.size + 1)
        for (i in raw.indices) be[be.size - 1 - i] = raw[i]
        return BigInteger(be)
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
