package com.proxyagent.app.nativeagent.quic.crypto

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AEAD packet payload protection for AES-128-GCM (RFC 9001 §5.3).
 *
 * One instance per direction (separate keys for client→server
 * and server→client). Stateless except for the immutable [keys].
 *
 * **AAD construction.** The Authenticated Associated Data for
 * a QUIC packet is the **unprotected** packet header — the
 * bytes the receiver sees before AEAD verification, including:
 *  - the first byte (BEFORE header protection is applied)
 *  - all the explicit header fields (version, DCID, SCID, token,
 *    length, etc. for long headers; DCID for short headers)
 *  - the raw packet number bytes (BEFORE header protection)
 *
 * The packet number in the AAD is the **truncated** form used
 * on the wire, not the full 62-bit value. The full value is
 * only used to build the per-packet nonce.
 *
 * **Nonce construction.** RFC 9001 §5.3: the per-packet nonce
 * is the [DirectionalKeys.iv] XOR'd with the full 62-bit packet
 * number, big-endian, right-aligned into the 12-byte IV. So
 * the PN goes into nonce bytes [4..11].
 */
internal class PacketProtection(private val keys: DirectionalKeys) {

    private val aeadKey = SecretKeySpec(keys.key, "AES")

    /**
     * Encrypt [payload] (plaintext frames) under the AEAD with
     * the given full 62-bit [packetNumber]. Returns
     * `ciphertext || 16-byte AEAD tag` concatenated — same
     * layout we put on the wire.
     *
     * [aad] must be the bytes of the unprotected header from
     * the first byte through the raw (unprotected) PN bytes.
     */
    fun encrypt(packetNumber: Long, payload: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, aeadKey, GCMParameterSpec(128, nonce(packetNumber)))
            updateAAD(aad)
        }
        return cipher.doFinal(payload)
    }

    /**
     * Decrypt [ciphertextWithTag] (last 16 bytes are the AEAD
     * tag) using [packetNumber] and [aad]. Throws
     * `javax.crypto.AEADBadTagException` on authentication
     * failure — caller MUST drop the packet silently
     * (RFC 9001 §5.5 — never reveal AEAD failures to the
     * peer). Other crypto exceptions indicate a bug.
     */
    fun decrypt(packetNumber: Long, ciphertextWithTag: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, aeadKey, GCMParameterSpec(128, nonce(packetNumber)))
            updateAAD(aad)
        }
        return cipher.doFinal(ciphertextWithTag)
    }

    /** Build the per-packet AEAD nonce (RFC 9001 §5.3 step 2). */
    internal fun nonce(packetNumber: Long): ByteArray {
        // Start from a copy of the IV so we don't mutate keys.iv.
        val nonce = keys.iv.copyOf()
        // PN occupies nonce bytes [4..11], big-endian. We XOR
        // (not assign) so any non-zero IV bits are preserved.
        // Bit position 0 of the PN ends up at nonce[11], bit 7
        // at nonce[11]'s msb, bit 56 at nonce[4]'s msb.
        for (i in 0 until 8) {
            val byteIdx = 11 - i  // i=0 → byte 11 (LSB of PN)
            val pnByte = ((packetNumber ushr (i * 8)) and 0xFFL).toInt()
            nonce[byteIdx] = (nonce[byteIdx].toInt() xor pnByte).toByte()
        }
        return nonce
    }
}
