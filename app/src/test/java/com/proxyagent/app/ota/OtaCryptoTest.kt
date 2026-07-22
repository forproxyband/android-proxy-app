package com.proxyagent.app.ota

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

// Verifies OtaCrypto against JCA Blowfish encryption: decrypt(encrypt(x)) == x
// and the reported SHA-256 matches the plaintext hash. Mirrors the real
// end-to-end validation done against a live CRM build (OTA_UPDATES_PLAN.md §0).
class OtaCryptoTest {

    private val key = "fCZMilU141ibKg1NbxrXX3Hx"

    private fun blowfishEncrypt(plain: ByteArray, key: String): ByteArray {
        val c = Cipher.getInstance("Blowfish/ECB/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.UTF_8), "Blowfish"))
        return c.doFinal(plain)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    @Test fun decryptRoundTripAndHash() {
        // A payload that is NOT a multiple of the 8-byte block, to exercise padding.
        val plain = ByteArray(5000) { ((it * 31 + 7) % 256).toByte() }
        val enc = blowfishEncrypt(plain, key)

        val encFile = File.createTempFile("ota", ".enc").apply { deleteOnExit() }
        val outFile = File.createTempFile("ota", ".apk").apply { deleteOnExit() }
        encFile.writeBytes(enc)

        val gotSha = OtaCrypto.decryptAndHash(encFile, outFile, key)

        assertArrayEquals(plain, outFile.readBytes())
        assertEquals(sha256Hex(plain), gotSha)
        // Sanity: padding makes ciphertext strictly larger than plaintext.
        assertNotEquals(plain.size.toLong(), encFile.length())
    }

    @Test fun sha256HexMatchesJca() {
        val data = "the quick brown fox".toByteArray()
        val f = File.createTempFile("ota", ".bin").apply { deleteOnExit() }
        f.writeBytes(data)
        assertEquals(sha256Hex(data), OtaCrypto.sha256Hex(f))
    }
}
