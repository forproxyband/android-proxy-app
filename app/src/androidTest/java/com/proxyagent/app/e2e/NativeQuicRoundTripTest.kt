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
 * QUIC round-trip via the in-house QUIC stack (`NativeQuicTransport`),
 * the production default since the Settings picker was removed. We seed
 * the transport cache with "quic" so the agent tries QUIC first instead
 * of TCP — otherwise `chooseTransportOrder` favours TCP for the splice
 * fast path. Once connected via QUIC, the testserver opens a
 * server-initiated stream with a JSON header carrying our echo target,
 * the agent bridges the stream bytes to that TCP target, and we verify
 * byte parity.
 */
@RunWith(AndroidJUnit4::class)
class NativeQuicRoundTripTest {

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
    fun roundtrip_bytesMatch() {
        val n = E2EConfig.payloadBytes
        val resp = E2EConfig.postJson("/tests/tunnel-roundtrip?bytes=$n&transport=quic")
        assertTrue("server reported failure: $resp", resp.optBoolean("ok"))
        assertEquals(n, resp.getInt("bytes"))
        assertEquals(n, resp.getInt("recv_bytes"))
        assertEquals(resp.getString("hash_sent"), resp.getString("hash_recv"))
    }
}
