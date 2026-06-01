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
     *  capability; pass null for TCP-only behaviour. `tcpWarmPool`
     *  defaults to 4 (enough for single-tunnel tests); throughput /
     *  concurrent tests bump it so the agent never runs out of pre-
     *  dialed sockets under load. */
    fun configFor(
        workDir: File,
        quicFactory: com.proxyagent.app.nativeagent.QuicTransport.Factory? = null,
        tcpWarmPool: Int = 4,
    ): NativeProxyAgent.Config = NativeProxyAgent.Config(
        registratorHost = testserverHost,
        registratorPort = testserverPort,
        agentKey = authKey,
        agentUuid = "e2e-agent",
        workDir = workDir,
        httpTimeoutMs = 5000,
        // Both dial timeouts bumped for the CI x86_64 emulator. The first
        // run had LargePayloadTest fall back to TCP because QUIC handshake
        // didn't finish inside 4 s on a software-emulated stack under
        // load from earlier tests. Generous bounds here are cheap — the
        // happy path completes in well under a second.
        dialTimeoutMs = 10000,
        heartbeatIntervalSec = 5,
        enableHeartbeat = false, // noisy, irrelevant to round-trip
        quicTransportFactory = quicFactory,
        quicDialTimeoutMs = 15000,
        tcpWarmPoolSize = tcpWarmPool,
    )

    /** Build a multi-line per-tunnel summary suitable for embedding in
     *  an assertion failure message. Makes the gradle console output
     *  enough to diagnose which streams failed and why without having
     *  to download the androidTest artifact. */
    fun summarizeResults(resp: org.json.JSONObject): String {
        val arr = resp.optJSONArray("results") ?: return "(no per-tunnel results)"
        val sb = StringBuilder("per-tunnel (${arr.length()}):\n")
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            sb.append("  [${r.optInt("index")}] ok=${r.optBoolean("ok")}")
            sb.append(" sent=${r.optInt("sent_bytes")} recv=${r.optInt("recv_bytes")}")
            sb.append(" dur=${r.optLong("duration_ms")}ms")
            sb.append(" mbps=${"%.2f".format(r.optDouble("mbps"))}")
            val err = r.optString("error")
            if (err.isNotEmpty()) sb.append(" err=\"$err\"")
            sb.append("\n")
        }
        sb.append("wall=${resp.optLong("wall_ms")}ms agg_mbps=${"%.2f".format(resp.optDouble("agg_mbps"))}")
        return sb.toString()
    }

    /** Assertion that calls out QUIC fallback to TCP with the lastError
     *  field from the agent's status — usually carries the dial reason
     *  the agent's supervisor logged before giving up on QUIC. */
    fun assertConnectedVia(agent: NativeProxyAgent, expected: String) {
        val s = agent.getStatus()
        if (s.transport != expected) {
            throw AssertionError(
                "agent connected via ${s.transport}, expected $expected. " +
                    "running=${s.running} connected=${s.connected} " +
                    "registrator=${s.registratorHost}:${s.registratorPort} " +
                    "activeTunnels=${s.activeTunnels} lastError=${s.lastError}"
            )
        }
    }

    /** Pretty-prints the throughput-API response into a banner block in
     *  the instrumentation log — easy to grep in GitHub Actions output.
     *  Format kept deliberately ASCII-only / single column. */
    fun printThroughputBanner(label: String, resp: org.json.JSONObject) {
        val ok = resp.optBoolean("ok")
        val succ = resp.optInt("succeeded")
        val total = resp.optInt("total")
        val wallMs = resp.optLong("wall_ms")
        val bytesPer = resp.optInt("bytes")
        val aggBytes = resp.optInt("agg_bytes")
        val mbps = resp.optDouble("agg_mbps")
        val mbpsDup = resp.optDouble("agg_mbps_duplex")
        println("============================================================")
        println("THROUGHPUT [$label]")
        println("  status      : ok=$ok  $succ/$total tunnels succeeded")
        println("  per-tunnel  : ${bytesPer / 1024} KiB")
        println("  total bytes : ${aggBytes / 1024} KiB  (one-way)")
        println("  wall time   : ${wallMs} ms")
        println("  one-way Mbps: ${"%.2f".format(mbps)}")
        println("  duplex Mbps : ${"%.2f".format(mbpsDup)}  (counts return path)")
        // Per-tunnel breakdown — helps spot a single stragger that drags
        // the aggregate down (the classic HoL or pool-starvation symptom).
        val arr = resp.optJSONArray("results") ?: return
        println("  per-tunnel  :")
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            println("    [${r.optInt("index")}] ok=${r.optBoolean("ok")} " +
                "${r.optInt("bytes") / 1024} KiB in ${r.optLong("duration_ms")} ms " +
                "(${"%.2f".format(r.optDouble("mbps"))} Mbps)" +
                (if (r.has("error")) " err=${r.optString("error")}" else ""))
        }
        println("============================================================")
    }

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
