package com.proxyagent.app.e2e

import androidx.test.platform.app.InstrumentationRegistry
import com.proxyagent.app.nativeagent.KwikQuicTransport
import com.proxyagent.app.nativeagent.NativeProxyAgent
import com.proxyagent.app.nativeagent.quic.NativeQuicTransport
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Shared knobs and helpers for the e2e instrumentation tests. The tests
 * connect a real `NativeProxyAgent` to the Go testserver under
 * `e2e/testserver/` and drive byte-perfect round-trips through real
 * tunnels.
 *
 * Defaults assume the standard Android emulator → host loopback alias
 * `10.0.2.2`. Override per-run with instrumentation args, e.g.:
 *
 *   ./gradlew connectedAndroidTest -Pe2e=true \
 *       -Pandroid.testInstrumentationRunnerArguments.testserverHost=192.0.2.10
 */
object E2EConfig {

    /** Host the agent dials. Emulator-to-host loopback by default. */
    val testserverHost: String = arg("testserverHost", "10.0.2.2")

    /** Shared TCP + UDP port the testserver listens on (production
     *  registrators do the same). */
    val testserverPort: Int = arg("testserverPort", "17080").toInt()

    /** HTTP test API port — used by tests to drive tunnel scenarios. */
    val testserverApiPort: Int = arg("testserverApiPort", "17083").toInt()

    /** Expected AUTH key. Must match the testserver's `--auth-key` flag. */
    val authKey: String = arg("authKey", "e2e")

    /** Round-trip payload size. 64 KiB is enough to exercise QUIC stream
     *  flow control + a few segments of TCP, while still finishing quickly
     *  on a software-emulated x86_64 emulator. Override with
     *  `-Pandroid.testInstrumentationRunnerArguments.bytes=1048576`. */
    val payloadBytes: Int = arg("bytes", "65536").toInt()

    /** Timeout for agent.start → connected. Generous so a cold-boot
     *  emulator on CI doesn't flake on the first dial. */
    const val CONNECT_TIMEOUT_MS = 30_000L

    /** Timeout for the round-trip HTTP API call. The server-side caps
     *  at 60s in api.go; matching that here keeps the failure modes
     *  symmetric. */
    const val ROUNDTRIP_TIMEOUT_MS = 60_000

    fun apiUrl(path: String): URL =
        URL("http://$testserverHost:$testserverApiPort$path")

    /** Fresh, isolated workDir per test — guarantees `chooseTransportOrder`
     *  starts from no transport cache and per-instance state doesn't leak. */
    fun newWorkDir(): File {
        val cache = InstrumentationRegistry.getInstrumentation()
            .targetContext.cacheDir
        val dir = File(cache, "e2e-${UUID.randomUUID()}")
        dir.mkdirs()
        return dir
    }

    /** Force the transport order by pre-seeding the agent's transport-cache
     *  file. `chooseTransportOrder()` reads it before each redial — when
     *  set to "quic" with a registered factory, QUIC is tried first. */
    fun seedTransportCache(workDir: File, transport: String) {
        require(transport == "tcp" || transport == "quic")
        File(workDir, ".proxyagent_transport").writeText("$transport\n")
    }

    /** Standard Config — direct registrator only, no balancer probing,
     *  short timeouts so tests don't hang. quicFactory toggles QUIC
     *  capability; pass null for TCP-only behaviour. */
    fun configFor(
        workDir: File,
        quicFactory: com.proxyagent.app.nativeagent.QuicTransport.Factory? = null,
    ): NativeProxyAgent.Config = NativeProxyAgent.Config(
        registratorHost = testserverHost,
        registratorPort = testserverPort,
        agentKey = authKey,
        agentUuid = "e2e-agent",
        workDir = workDir,
        httpTimeoutMs = 3000,
        dialTimeoutMs = 5000,
        heartbeatIntervalSec = 5,
        enableHeartbeat = false, // noisy, irrelevant to round-trip
        quicTransportFactory = quicFactory,
        quicDialTimeoutMs = 4000,
        tcpWarmPoolSize = 4, // smaller pool = fewer test sockets to track
    )

    fun newNativeQuicFactory(): NativeQuicTransport.Factory =
        NativeQuicTransport.Factory()

    fun newKwikFactory(): KwikQuicTransport.Factory =
        KwikQuicTransport.Factory()

    /** Blocks until `agent.getStatus().connected == true` or the timeout
     *  fires. Polls every 100 ms — cheap because reads are off a
     *  @Volatile snapshot. */
    fun waitConnected(agent: NativeProxyAgent, timeoutMs: Long = CONNECT_TIMEOUT_MS) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (agent.getStatus().connected) return
            Thread.sleep(100)
        }
        val s = agent.getStatus()
        throw AssertionError(
            "agent did not connect within ${timeoutMs} ms: " +
                "running=${s.running} transport=${s.transport} lastError=${s.lastError}"
        )
    }

    /** POSTs an empty body to the testserver and parses the JSON response.
     *  Caller adds query params to [path]. */
    fun postJson(path: String, timeoutMs: Int = ROUNDTRIP_TIMEOUT_MS): JSONObject {
        val url = apiUrl(path)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5000
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Length", "0")
        }
        try {
            OutputStreamWriter(conn.outputStream).use { it.write("") }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                .bufferedReader().use { it.readText() }
            if (code !in 200..299) {
                throw AssertionError("testserver $path returned HTTP $code: $body")
            }
            return JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun arg(name: String, default: String): String =
        InstrumentationRegistry.getArguments().getString(name) ?: default
}
