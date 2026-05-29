package com.proxyagent.app.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Boot-time sanity for the e2e harness. Verifies the instrumentation APK
 * targets the right package and that the production classes the real
 * tests will exercise are loadable on the device. Runs in milliseconds —
 * fails fast if `-Pe2e=true` (or AGP) wired the source set wrong.
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {

    @Test
    fun targetPackageIsApp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.proxyagent.app", ctx.packageName)
    }

    @Test
    fun nativeEngineClassesLoad() {
        // Bare Class.forName — no instantiation, so this only proves the
        // dex tables are wired and the classes exist on the device. Real
        // tests in PR3 build a NativeProxyAgent and connect it to the Go
        // testserver.
        assertNotNull(Class.forName("com.proxyagent.app.nativeagent.NativeProxyAgent"))
        assertNotNull(Class.forName("com.proxyagent.app.nativeagent.SpliceShim"))
        assertNotNull(Class.forName("com.proxyagent.app.nativeagent.quic.NativeQuicTransport"))
    }
}
