package com.proxyagent.app.nativeagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

// The AUTH line the agent writes: with a configured APK it carries
// "buildId":"apk-sha256:<hex of that file>" and the "sdkBuild" as configured;
// without them both fields are left out, not sent empty.
class AuthFrameTest {

    private val workDir = File(System.getProperty("java.io.tmpdir") ?: ".")
    private val info = SystemInfo(os = "android", arch = "arm64", cpuCount = 8,
        hostname = "phone", ips = listOf("10.0.2.16"))

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    @Test fun authCarriesTheApkBuildIdAndSdkBuild() {
        val bytes = "auth-frame-apk".toByteArray()
        val apk = File.createTempFile("auth", ".apk").apply { deleteOnExit(); writeBytes(bytes) }
        val cfg = NativeProxyAgent.Config(agentKey = "partner-key", agentUuid = "device-1",
            apkPath = apk.path, sdkBuild = "2.0(7)", workDir = workDir)

        val line = Uplink.authJson(cfg, "partner-key", info)
        val obj = MiniJson.parseObject(line)

        assertEquals("AUTH", obj.string("command"))
        assertEquals("partner-key", obj.string("key"))
        assertEquals("device-1", obj.string("uuid"))
        assertEquals("phone", obj.string("host"))
        assertTrue(line, line.contains("\"sourceIPList\":[\"10.0.2.16\"]"))
        assertEquals("apk-sha256:" + sha256Hex(bytes), obj.string("buildId"))
        assertEquals("2.0(7)", obj.string("sdkBuild"))
    }

    @Test fun authWithoutBuildIdentityLeavesTheFieldsOut() {
        val cfg = NativeProxyAgent.Config(agentKey = "partner-key", workDir = workDir)

        val line = Uplink.authJson(cfg, "partner-key", info)
        val obj = MiniJson.parseObject(line)

        assertEquals("partner-key", obj.string("uuid")) // no uuid → the key
        assertFalse(line, obj.has("buildId"))
        assertFalse(line, obj.has("sdkBuild"))
    }

    // An APK that cannot be read and a blank sdkBuild are left out the same way.
    @Test fun unreadableApkAndBlankSdkBuildAreLeftOut() {
        val dir = File.createTempFile("auth", "").apply { delete(); mkdir(); deleteOnExit() }
        val cfg = NativeProxyAgent.Config(agentKey = "partner-key",
            apkPath = File(dir, "absent.apk").path, sdkBuild = "  ", workDir = workDir)

        val line = Uplink.authJson(cfg, "partner-key", info)
        val obj = MiniJson.parseObject(line)

        assertEquals("AUTH", obj.string("command"))
        assertFalse(line, obj.has("buildId"))
        assertFalse(line, obj.has("sdkBuild"))
    }
}
