package com.proxyagent.app.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.proxyagent.app.nativeagent.NativeProxyAgent
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Error-path coverage for the TCP control channel. The testserver issues
 * OPEN(host=10.0.2.2, port=1) — TCP port 1 is reserved/unassigned, so
 * the agent's `Socket.connect` in handleOpenTcp throws "Connection
 * refused", and the agent emits `{"command":"OPEN_FAIL","token":...}`.
 *
 * The mock-side counterpart of production
 * `Registry.deliverDataConn(token, nil)` (see proxy-server-go-sdk
 * tunnel/server.go controlReadLoop) is Hub.failPending — it wakes the
 * API handler with a structured error instead of letting it time out.
 * Without that wiring the test would hang for 60 s before the round-trip
 * deadline fires; with it, failure surfaces in well under a second.
 */
@RunWith(AndroidJUnit4::class)
class TcpOpenFailTest {

    private lateinit var workDir: File
    private lateinit var agent: NativeProxyAgent

    @Before
    fun setUp() {
        workDir = E2EConfig.newWorkDir()
        agent = NativeProxyAgent()
        agent.setLogSink(E2EConfig.newLogSink("TcpOpenFailTest"))
        agent.start(E2EConfig.configFor(workDir, quicFactory = null))
        E2EConfig.waitConnected(agent)
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
    fun unreachable_target_yields_OPEN_FAIL() {
        // target-port=1 is reserved (tcpmux). Nothing listens there in
        // any sane CI environment, so the agent's target dial refuses
        // and OPEN_FAIL flows back.
        val resp = E2EConfig.postJson(
            "/tests/tunnel-roundtrip?bytes=1024&transport=tcp&target-host=10.0.2.2&target-port=1"
        )
        assertFalse("expected failure, got ok=true: $resp", resp.optBoolean("ok"))
        val err = resp.optString("error")
        // Production reason strings vary by OS / dial path; check for any
        // of the recognizable fragments rather than a brittle exact match.
        assertTrue(
            "error should reference OPEN_FAIL or a target-dial failure: $err",
            err.contains("OPEN_FAIL", ignoreCase = true) ||
                err.contains("refused", ignoreCase = true) ||
                err.contains("target dial", ignoreCase = true) ||
                err.contains("connect", ignoreCase = true)
        )
    }
}
