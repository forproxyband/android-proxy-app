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
 * Same round-trip as [NativeQuicRoundTripTest] but routed through the
 * kwik fallback QUIC implementation. Kwik is the safety-net override
 * kept compiled in (see app/build.gradle.kts comment on
 * tech.kwik:kwik) while the in-house stack accumulates field hours —
 * this test guards the fallback so removing kwik later is a deliberate
 * call, not an accidental regression.
 */
@RunWith(AndroidJUnit4::class)
class KwikQuicRoundTripTest {

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
        agent.start(E2EConfig.configFor(workDir, quicFactory = E2EConfig.newKwikFactory()))
        E2EConfig.waitConnected(agent)
        assertEquals("quic", agent.getStatus().transport)
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
