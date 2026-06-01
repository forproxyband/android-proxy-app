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
 * Speedtest-style multi-connection throughput, QUIC transport (in-house
 * NativeQuicTransport). 16 parallel server-initiated streams on one
 * QUIC connection × 4 MiB full-duplex echo per stream — stresses the
 * connection-level flow-control window, the stream scheduler, and
 * Brutal CC's rate target under sustained multiplexing.
 *
 * No hard Mbps threshold (CI x86_64 software-emulated QUIC speed
 * varies); the test prints a metrics banner and asserts byte parity.
 * Compare with [ThroughputTcpTest] in the same run to see the TCP vs
 * QUIC delta on identical CI hardware.
 */
@RunWith(AndroidJUnit4::class)
class ThroughputQuicTest {

    private lateinit var workDir: File
    private lateinit var agent: NativeProxyAgent

    @Before
    fun setUp() {
        workDir = E2EConfig.newWorkDir()
        E2EConfig.seedTransportCache(workDir, "quic")
        agent = NativeProxyAgent()
        agent.setLogSink { _, _, _ -> /* quiet */ }
        agent.start(E2EConfig.configFor(workDir, quicFactory = E2EConfig.newNativeQuicFactory()))
        E2EConfig.waitConnected(agent)
        E2EConfig.assertConnectedVia(agent, "quic", workDir, "NativeQuicTransport.Factory")
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
    fun echo_sixteen_streams_four_megabytes_each() {
        runConcurrent(mode = "echo", label = "QUIC echo 16x4MiB")
    }

    /**
     * Pure upload — production bug we want to catch: QUIC download worked
     * fine but upload hung. Echo would mask this because reverse traffic
     * keeps refreshing flow-control credit on the broken side. Here the
     * mock writes 4 MiB into each of 16 streams, target reads + discards,
     * agent never reads anything from its target socket. If the upload
     * direction stalls under multiplexing, the test hits its 5-min
     * timeout — far above the ~10 s a healthy run takes.
     */
    @Test
    fun upload_sixteen_streams_four_megabytes_each() {
        runConcurrent(mode = "upload", label = "QUIC upload 16x4MiB")
    }

    /**
     * Pure download — symmetric to upload. Source target streams random
     * bytes, mock reads exactly 4 MiB and closes; agent's bridge
     * forwards target→stream only. If a regression breaks the download
     * direction (rare on this stack but cheap to guard), this catches
     * it without confusing it with an upload stall.
     */
    @Test
    fun download_sixteen_streams_four_megabytes_each() {
        runConcurrent(mode = "download", label = "QUIC download 16x4MiB")
    }

    private fun runConcurrent(mode: String, label: String) {
        val count = 16
        val bytesPer = 4 * 1024 * 1024
        val resp = E2EConfig.postJson(
            "/tests/tunnel-roundtrip-concurrent?count=$count&bytes=$bytesPer&transport=quic&mode=$mode",
            timeoutMs = 300_000
        )
        E2EConfig.printThroughputBanner(label, resp)
        val summary = E2EConfig.summarizeResults(resp)
        assertEquals(
            "$label: only ${resp.optInt("succeeded")}/${resp.optInt("total")} streams succeeded.\n$summary",
            count, resp.optInt("succeeded")
        )
        assertTrue("$label: aggregate ok=false\n$summary", resp.optBoolean("ok"))
    }
}
