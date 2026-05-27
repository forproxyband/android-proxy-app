package com.proxyagent.app.nativeagent.quic.crypto

import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF (RFC 5869) and the TLS 1.3 HKDF-Expand-Label construction
 * (RFC 8446 §7.1, reused by QUIC v1 per RFC 9001 §5.1).
 *
 * SHA-256 is the only hash we use — QUIC v1 with AES-128-GCM is
 * tied to SHA-256 for key derivation. We do NOT support
 * SHA-384-based suites because we negotiate AES-128-GCM only.
 *
 * Why our own HKDF instead of `javax.crypto.spec.SecretKeySpec` +
 * a third-party library: the JDK exposes HMAC but not HKDF
 * directly (until JEP 478 / Java 25, which Android won't see for
 * years). The construction is small enough that writing it
 * by hand is cleaner than pulling in BouncyCastle solely for
 * this. We do use BC later for TLS 1.3 itself.
 */
internal object Hkdf {

    /** SHA-256 output size in bytes. */
    private const val HASH_LEN = 32

    /** Maximum HKDF-Expand output per RFC 5869 §2.3 (255 * HashLen). */
    private const val MAX_EXPAND_LENGTH = 255 * HASH_LEN

    /**
     * HKDF-Extract (RFC 5869 §2.2): `HMAC-SHA256(salt, ikm)`.
     * If `salt` is empty, a zero-filled `HashLen` block is used,
     * per the RFC's "if not provided, it is set to a string of
     * HashLen zeros" rule.
     */
    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val effectiveSalt = if (salt.isEmpty()) ByteArray(HASH_LEN) else salt
        return hmacSha256(effectiveSalt, ikm)
    }

    /**
     * HKDF-Expand (RFC 5869 §2.3). Produces `length` bytes of
     * output keying material from a pseudo-random key `prk` and
     * an `info` string. Output is `T(1) || T(2) || ...` where
     * `T(i) = HMAC-SHA256(prk, T(i-1) || info || i)`.
     */
    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 0..MAX_EXPAND_LENGTH) {
            "HKDF-Expand length out of range: $length (max $MAX_EXPAND_LENGTH)"
        }
        if (length == 0) return ByteArray(0)
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(prk, "HmacSHA256"))
        }
        val out = ByteArray(length)
        var prev = ByteArray(0)
        var written = 0
        var counter = 1
        while (written < length) {
            mac.reset()
            mac.update(prev)
            mac.update(info)
            mac.update(counter.toByte())
            prev = mac.doFinal()
            val toCopy = minOf(HASH_LEN, length - written)
            System.arraycopy(prev, 0, out, written, toCopy)
            written += toCopy
            counter++
        }
        return out
    }

    /**
     * HKDF-Expand-Label (TLS 1.3 §7.1), per QUIC v1's
     * RFC 9001 §5.1 use of the `"tls13 "` label prefix.
     *
     * Constructs the `HkdfLabel` struct:
     * ```
     *   struct {
     *     uint16 length = Length;
     *     opaque label<7..255> = "tls13 " + Label;
     *     opaque context<0..255> = Context;
     *   } HkdfLabel;
     * ```
     * and feeds it as the `info` argument to HKDF-Expand.
     *
     * For QUIC, [context] is always empty — QUIC's labels
     * (`"client in"`, `"server in"`, `"quic key"`, `"quic iv"`,
     * `"quic hp"`, `"quic ku"`) all derive without a context.
     */
    fun expandLabel(
        secret: ByteArray,
        label: String,
        length: Int,
        context: ByteArray = ByteArray(0),
    ): ByteArray {
        require(length in 0..0xFFFF) { "HKDF-Expand-Label length out of range: $length" }
        val fullLabel = ("tls13 $label").toByteArray(Charsets.US_ASCII)
        require(fullLabel.size in 7..255) {
            "HKDF-Expand-Label full label length out of [7,255]: ${fullLabel.size}"
        }
        require(context.size in 0..255) {
            "HKDF-Expand-Label context length out of [0,255]: ${context.size}"
        }
        val info = ByteBuffer.allocate(2 + 1 + fullLabel.size + 1 + context.size)
        info.putShort(length.toShort())
        info.put(fullLabel.size.toByte())
        info.put(fullLabel)
        info.put(context.size.toByte())
        if (context.isNotEmpty()) info.put(context)
        return expand(secret, info.array(), length)
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}
