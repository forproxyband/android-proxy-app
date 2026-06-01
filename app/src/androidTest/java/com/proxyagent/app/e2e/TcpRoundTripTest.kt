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
 * End-to-end TCP round-trip. Connects an agent in TCP-only mode (no QUIC
 * factory → `chooseTransportOrder` returns ["tcp"]), drives a tunnel via
 * the testserver's HTTP API, and asserts byte-perfect echo through:
 *
 *   testserver --HTTP--> /tests/tunnel-roundtrip
 *                  |
 *                  v (writes OPEN to TCP control)
 *   agent --reads control--> sees OPEN(host=10.0.2.2, port=echoPort)
 *                  |
 *                  v (takes data socket from warm pool, writes token)
 *   testserver matches token → bridge agent's data socket <-> http handler
 *                  |
 *                  v (agent TCP-dials 10.0.2.2:echoPort, bridges)
 *   echo target: io.Copy(c, c) loops the bytes back
 */
@RunWith(AndroidJUnit4::class)
class TcpRoundTripTest {

    private lateinit var workDir: File
    private lateinit var agent: NativeProxyAgent

    @Before
    fun setUp() {
        workDir = E2EConfig.newWorkDir()
        agent = NativeProxyAgent()
        agent.setLogSink { level, msg, fields ->
            // Surface agent logs in instrumentation output for postmortem.
            println("[agent $level] $msg ${fields.entries.joinToString { "${it.key}=${it.value}" }}")
        }
        agent.start(E2EConfig.configFor(workDir, quicFactory = null))
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
    fun roundtrip_bytesMatch() {
        val n = E2EConfig.payloadBytes
        val resp = E2EConfig.postJson("/tests/tunnel-roundtrip?bytes=$n&transport=tcp")
        assertTrue("server reported failure: $resp", resp.optBoolean("ok"))
        assertEquals(n, resp.getInt("bytes"))
        assertEquals(n, resp.getInt("recv_bytes"))
        assertEquals(resp.getString("hash_sent"), resp.getString("hash_recv"))
    }
}
