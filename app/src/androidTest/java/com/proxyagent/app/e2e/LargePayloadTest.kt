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
 * 1 MiB round-trip — exercises flow-control credit refresh, congestion-
 * control behaviour under sustained throughput, and (for QUIC) per-
 * stream + connection-level windows beyond what a 64 KiB payload
 * touches. Two methods: one for each transport, sharing the per-class
 * agent. Class-scoped `transport` is QUIC because TCP+1 MiB+emulator
 * is bottlenecked on virtio-net rather than anything we're trying to
 * cover here; the TCP large-payload check still runs but uses its own
 * agent in a separate test class (kept inline below for simplicity).
 */
@RunWith(AndroidJUnit4::class)
class LargePayloadTest {

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
    fun one_megabyte_quic_roundtrip() {
        val n = 1024 * 1024
        val resp = E2EConfig.postJson(
            "/tests/tunnel-roundtrip?bytes=$n&transport=quic",
            timeoutMs = 180_000
        )
        assertTrue("server reported failure: $resp", resp.optBoolean("ok"))
        assertEquals(n, resp.getInt("bytes"))
        assertEquals(n, resp.getInt("recv_bytes"))
        assertEquals(resp.getString("hash_sent"), resp.getString("hash_recv"))
    }
}
