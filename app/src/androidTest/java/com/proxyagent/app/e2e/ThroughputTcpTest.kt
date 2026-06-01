package com.proxyagent.app.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.proxyagent.app.nativeagent.NativeProxyAgent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Speedtest-style multi-connection throughput, TCP transport. 16 parallel
 * tunnels × 4 MiB full-duplex echo through the warm pool — the same
 * pattern speedtest.net uses to estimate a link's actual capacity.
 *
 * No hard Mbps threshold is asserted (CI emulator throughput depends on
 * runner CPU and varies run-to-run); instead the metrics are printed to
 * the instrumentation log and the assert just checks that every tunnel
 * completed with byte parity. Use the printed banner in GitHub Actions
 * output to track the current baseline and watch for regressions.
 *
 * For the warm-pool size to keep up with 16 concurrent OPEN commands,
 * the agent is configured with `tcpWarmPool=24` (8 headroom over count).
 * Smaller pools force the OPEN handler to wait for a refill, which
 * shows up as a fat tail in per-tunnel duration.
 */
@RunWith(AndroidJUnit4::class)
class ThroughputTcpTest {

    private lateinit var workDir: File
    private lateinit var agent: NativeProxyAgent

    @Before
    fun setUp() {
        workDir = E2EConfig.newWorkDir()
        agent = NativeProxyAgent()
        agent.setLogSink(E2EConfig.newLogSink("ThroughputTcpTest"))
        agent.start(E2EConfig.configFor(workDir, quicFactory = null, tcpWarmPool = 24))
        E2EConfig.waitConnected(agent)
        E2EConfig.assertConnectedVia(agent, "tcp")
    }

    @After
    fun tearDown() {
        try {
            agent.stop()
        } catch (_: Throwable) {
        }
        workDir.deleteRecursively()
    }

    @Test
    fun echo_sixteen_tunnels_four_megabytes_each() {
        runConcurrent(mode = "echo", label = "TCP echo 16x4MiB")
    }

    /**
     * Pure upload — symmetric to the QUIC version. Catches direction-
     * specific stalls that an echo test would mask. On the TCP path
     * this primarily stresses the warm pool + splice forward path
     * without reverse traffic feeding the kernel buffer drains.
     */
    @Test
    fun upload_sixteen_tunnels_four_megabytes_each() {
        runConcurrent(mode = "upload", label = "TCP upload 16x4MiB")
    }

    /** Pure download — bytes flow target→agent→mock only. */
    @Test
    fun download_sixteen_tunnels_four_megabytes_each() {
        runConcurrent(mode = "download", label = "TCP download 16x4MiB")
    }

    private fun runConcurrent(mode: String, label: String) {
        val count = 16
        val bytesPer = 4 * 1024 * 1024
        val resp = E2EConfig.postJson(
            "/tests/tunnel-roundtrip-concurrent?count=$count&bytes=$bytesPer&transport=tcp&mode=$mode",
            timeoutMs = 300_000
        )
        E2EConfig.printThroughputBanner(label, resp)
        val summary = E2EConfig.summarizeResults(resp)
        assertEquals(
            "$label: only ${resp.optInt("succeeded")}/${resp.optInt("total")} tunnels succeeded.\n$summary",
            count, resp.optInt("succeeded")
        )
        assertTrue("$label: aggregate ok=false\n$summary", resp.optBoolean("ok"))
    }
}
