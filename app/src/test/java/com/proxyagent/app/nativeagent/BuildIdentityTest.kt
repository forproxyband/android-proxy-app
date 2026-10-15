package com.proxyagent.app.nativeagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

// The AUTH build identity: "apk-sha256:<64 lowercase hex>" of the APK file,
// the format the Go SDK sends, and "<versionName>(<versionCode>)" for sdkBuild.
class BuildIdentityTest {

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun tempFile(bytes: ByteArray): File =
        File.createTempFile("build", ".apk").apply { deleteOnExit(); writeBytes(bytes) }

    @Test fun sha256HexMatchesKnownVectors() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            BuildIdentity.sha256Hex(ByteArrayInputStream("abc".toByteArray())))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            BuildIdentity.sha256Hex(ByteArrayInputStream(ByteArray(0))))
    }

    // An APK is tens of MB: it is hashed in bounded chunks, whatever sizes the
    // stream hands back, and the result is still the hash of the whole file.
    @Test fun sha256HexStreamsInBoundedChunks() {
        val data = ByteArray(3 * 64 * 1024 + 17) { ((it * 31 + 7) % 256).toByte() }
        var largestRequest = 0
        var reads = 0
        val stingy = object : InputStream() {
            private var pos = 0
            override fun read(): Int = if (pos < data.size) data[pos++].toInt() and 0xff else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                largestRequest = maxOf(largestRequest, len)
                reads++
                if (pos >= data.size) return -1
                val n = minOf(len, 1000, data.size - pos) // short reads
                System.arraycopy(data, pos, b, off, n)
                pos += n
                return n
            }
        }
        assertEquals(sha256Hex(data), BuildIdentity.sha256Hex(stingy))
        assertTrue("asked for $largestRequest bytes at once", largestRequest <= 64 * 1024)
        assertTrue(reads > data.size / 1000)
    }

    @Test fun measureIsTheApkKindAndTheFileHash() {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        val id = BuildIdentity.measure(tempFile(bytes).path)
        assertEquals("apk-sha256:" + sha256Hex(bytes), id)
        assertTrue(id!!.matches(Regex("apk-sha256:[0-9a-f]{64}")))
    }

    @Test fun oneChangedByteChangesTheBuildId() {
        val a = ByteArray(4096) { 1 }
        val b = a.copyOf().also { it[2048] = 2 }
        assertNotEquals(BuildIdentity.measure(tempFile(a).path), BuildIdentity.measure(tempFile(b).path))
    }

    @Test fun unreadableApkGivesNoBuildId() {
        val dir = File.createTempFile("build", "").apply { delete(); mkdir(); deleteOnExit() }
        assertNull(BuildIdentity.measure(File(dir, "absent.apk").path))
        assertNull(BuildIdentity.measure(dir.path)) // a directory is not a file to hash
        assertNull(BuildIdentity.buildIdFor(File(dir, "absent.apk").path))
    }

    @Test fun noConfiguredPathGivesNoBuildId() {
        assertNull(BuildIdentity.buildIdFor(null))
        assertNull(BuildIdentity.buildIdFor(""))
        assertNull(BuildIdentity.buildIdFor("   "))
    }

    // Measured once per process: reconnects reuse the first answer even if the
    // file changes later; a different path is measured on its own.
    @Test fun apkBuildIdIsMeasuredOncePerPath() {
        val first = tempFile("build-1".toByteArray())
        val want = "apk-sha256:" + sha256Hex("build-1".toByteArray())
        assertEquals(want, BuildIdentity.buildIdFor(first.path))
        first.writeBytes("build-2".toByteArray())
        assertEquals(want, BuildIdentity.apkBuildId(first.path))

        val other = tempFile("other".toByteArray())
        assertEquals("apk-sha256:" + sha256Hex("other".toByteArray()), BuildIdentity.apkBuildId(other.path))
    }

    @Test fun sdkBuildIsVersionNameAndCode() {
        assertEquals("2.0.0-dev(1)", BuildIdentity.sdkBuild("2.0.0-dev", 1))
        assertEquals("2.0.417(417)", BuildIdentity.sdkBuild(" 2.0.417 ", 417))
        assertEquals("3.1(4294967296)", BuildIdentity.sdkBuild("3.1", 4_294_967_296L))
        assertNull(BuildIdentity.sdkBuild(null, 1))
        assertNull(BuildIdentity.sdkBuild("  ", 1))
    }
}
