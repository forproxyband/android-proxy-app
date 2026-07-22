package com.proxyagent.app.ota

import java.io.File
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.SecretKeySpec

// ────────────────────────────────────────────────────────────────────────
// Build decryption + integrity. Blowfish/ECB/PKCS5Padding, key = UTF-8 bytes
// of the app's encryption string (no hashing/derivation), streamed so 100+ MB
// builds never load into memory. SHA-256 is computed over the DECRYPTED bytes
// in the same pass and must equal the manifest's `SHA256` before install.
// Validated end-to-end against a real CRM build (OTA_UPDATES_PLAN.md §0).
// ────────────────────────────────────────────────────────────────────────

object OtaCrypto {

    private const val TRANSFORM = "Blowfish/ECB/PKCS5Padding"

    /**
     * Decrypt [encryptedFile] into [targetFile] while computing the SHA-256 of
     * the decrypted output. Returns the lower-case hex digest.
     *
     * @param onProgress optional callback with decrypted bytes written so far.
     */
    fun decryptAndHash(
        encryptedFile: File,
        targetFile: File,
        key: String,
        onProgress: ((Long) -> Unit)? = null,
    ): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key.toByteArray(Charsets.UTF_8), "Blowfish"),
        )
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        CipherInputStream(encryptedFile.inputStream().buffered(), cipher).use { input ->
            targetFile.outputStream().buffered().use { output ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n == -1) break
                    output.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    written += n
                    onProgress?.invoke(written)
                }
                output.flush()
            }
        }
        return digest.digest().toHex()
    }

    /** SHA-256 (lower-case hex) of an arbitrary stream — used for spot checks. */
    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n == -1) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
