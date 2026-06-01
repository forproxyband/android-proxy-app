package com.proxyagent.app.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.proxyagent.app.nativeagent.NativeProxyAgent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * QUIC tunnel-open error path. The QUIC wire-protocol has no OPEN_FAIL
 * command (unlike TCP) — when the agent's target dial fails in
 * handleQuicTunnelStream, it just closes the stream. The mock's pump
 * picks this up as an EOF on the stream read, and the round-trip API
 * reports the failure to the test.
 */
@RunWith(AndroidJUnit4::class)
class QuicErrorPathTest {

    private lateinit var workDir: File
    private lateinit var agent: NativeProxyAgent

    @Before
    fun setUp() {
        workDir = E2EConfig.newWorkDir()
        E2EConfig.seedTransportCache(workDir, "quic")
        agent = NativeProxyAgent()
        agent.setLogSink(E2EConfig.newLogSink("QuicErrorPathTest"))
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
    fun unreachable_target_closes_stream() {
        val resp = E2EConfig.postJson(
            "/tests/tunnel-roundtrip?bytes=1024&transport=quic&target-host=10.0.2.2&target-port=1"
        )
        assertFalse("expected failure, got ok=true: $resp", resp.optBoolean("ok"))
    }
}
