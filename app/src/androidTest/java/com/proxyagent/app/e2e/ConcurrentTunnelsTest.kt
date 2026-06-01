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
 * Multi-tunnel round-trip — proves stream multiplexing works on a single
 * QUIC connection. The testserver fans out N parallel tunnels (one
 * server-initiated stream each), the agent dials N TCP targets and
 * bridges them; if any stream blocks any other (flow-control deadlock,
 * stream-table corruption, head-of-line blocking through a shared
 * resource) `succeeded < total` and the assert fails with a per-tunnel
 * breakdown in the JSON.
 *
 * Production-side equivalent: the agent connection serves all proxy
 * exit tunnels for that partner through one QUIC conn — under load the
 * server opens hundreds of streams against it concurrently.
 */
@RunWith(AndroidJUnit4::class)
class ConcurrentTunnelsTest {

    private lateinit var workDir: File
    private lateinit var agent: NativeProxyAgent

    @Before
    fun setUp() {
        workDir = E2EConfig.newWorkDir()
        E2EConfig.seedTransportCache(workDir, "quic")
        agent = NativeProxyAgent()
        agent.setLogSink { level, msg, fields ->
            println("[agent $level] $msg ${fields.entries.joinToString { "${it.key}=${it.value}" }}")
        }
        agent.start(E2EConfig.configFor(workDir, quicFactory = E2EConfig.newNativeQuicFactory()))
        E2EConfig.waitConnected(agent)
        E2EConfig.assertConnectedVia(agent, "quic")
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
    fun eight_tunnels_in_parallel() {
        val count = 8
        val bytesPer = 32 * 1024
        val resp = E2EConfig.postJson(
            "/tests/tunnel-roundtrip-concurrent?count=$count&bytes=$bytesPer&transport=quic",
            timeoutMs = 180_000
        )
        val summary = E2EConfig.summarizeResults(resp)
        assertEquals(count, resp.optInt("total"))
        assertEquals(
            "only ${resp.optInt("succeeded")}/${resp.optInt("total")} tunnels succeeded.\n$summary",
            count, resp.optInt("succeeded")
        )
        assertTrue(resp.optBoolean("ok"))
    }
}
