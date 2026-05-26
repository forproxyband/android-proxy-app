package com.proxyagent.app.nativeagent

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Self-contained native Kotlin port of the Go proxy-agent SDK.
 *
 * Single drop-in class — instantiate, set listeners, call [start] with a
 * [Config]. Use [stop] to tear down. Thread-safe; not coroutine-based on
 * purpose so it can run in any JVM context (Service, plain thread, etc.).
 *
 * Compatible with the Go SDK wire protocol:
 *  - TCP uplink: magic "TUNL" + version 1 + connType byte (control/data),
 *    JSON-line control channel, 32-hex-byte per-stream tokens.
 *  - QUIC uplink: optional, requires [QuicTransport.Factory] registered
 *    via [setQuicTransportFactory]. Without it the agent runs TCP-only.
 *
 * Drop into any Android (or JVM) project: only stdlib + the optional
 * QUIC SPI implementation. No third-party deps in this file.
 */
class NativeProxyAgent {

    // ────────────────────────────────────────────────────────────────────
    // Public surface
    // ────────────────────────────────────────────────────────────────────

    /**
     * Connection configuration. Mirrors the Go SDK's env/flag knobs but
     * stripped down to what we actually need on Android. Build with
     * the supplied [Builder] or just `Config(...)` directly.
     */
    data class Config(
        // Direct registrator — when both are set, balancer/fallback are skipped.
        val registratorHost: String? = null,
        val registratorPort: Int = 0,
        // Balancer — queried when direct registrator is not configured.
        val balancerHost: String? = null,
        val balancerPort: Int = 0,
        val balancerPath: String = "/getRegistrator",
        // Fallback file — list of registrators probed if balancer fails.
        val fallbackFileUrl: String? = null,
        // Auth
        val agentKey: String,
        val agentUuid: String? = null,
        // DNS override (CSV: "8.8.8.8,1.1.1.1"). Empty → system resolver.
        val dnsServers: String = "",
        // Working directory for sticky-state files (transport cache, etc.).
        // On Android pass context.filesDir.
        val workDir: File,
        // Network features.
        val httpTimeoutMs: Int = 5000,
        val dialTimeoutMs: Int = 5000,
        val heartbeatIntervalSec: Int = 60,
        val enableHeartbeat: Boolean = true,
        // QUIC: when null, the agent runs TCP-only.
        val quicTransportFactory: QuicTransport.Factory? = null,
        // QUIC handshake budget before falling back to TCP.
        val quicDialTimeoutMs: Int = 3000,
        // Per-stream warm pool size for TCP mode (matches Go SDK default).
        val tcpWarmPoolSize: Int = 8,
    ) {
        fun hasDirectRegistrator(): Boolean =
            !registratorHost.isNullOrBlank() && registratorPort > 0

        fun hasBalancer(): Boolean =
            !balancerHost.isNullOrBlank() && balancerPort > 0

        fun hasFallback(): Boolean =
            !fallbackFileUrl.isNullOrBlank()
    }

    /** Sink for human-readable log lines. Matches the Go SDK's format
     *  ("level=INFO msg=\"…\" key=value") so existing log parsers keep
     *  working. */
    fun interface LogSink {
        fun log(level: String, msg: String, fields: Map<String, Any?>)
    }

    /** Notification of REBOOT commands received from the registrator.
     *  Equivalent to the Go SDK's local-WS broadcast — wire in your own
     *  reaction (IP cycle, service restart, etc.). */
    fun interface RebootListener {
        fun onReboot(reason: String)
    }

    /** Snapshot of the agent's runtime state. */
    data class Status(
        val running: Boolean,
        val connected: Boolean,
        val registratorHost: String?,
        val registratorPort: Int,
        val transport: String?,           // "tcp" | "quic" | null
        val activeTunnels: Int,
        val lastError: String?,
        val startedAtMs: Long,
    )

    // ────────────────────────────────────────────────────────────────────
    // State
    // ────────────────────────────────────────────────────────────────────

    private val running = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    @Volatile private var supervisorThread: Thread? = null
    @Volatile private var currentUplink: Uplink? = null
    @Volatile private var logSink: LogSink? = null
    @Volatile private var rebootListener: RebootListener? = null

    // Status tracking. Reads off this snapshot are cheap and lock-free.
    @Volatile private var statusSnapshot: Status = Status(
        running = false,
        connected = false,
        registratorHost = null,
        registratorPort = 0,
        transport = null,
        activeTunnels = 0,
        lastError = null,
        startedAtMs = 0L,
    )
    private val activeTunnels = AtomicInteger(0)

    // Process-wide DNS override; thread-safe.
    private val dnsConfig = DnsConfig()

    // ────────────────────────────────────────────────────────────────────
    // Public API
    // ────────────────────────────────────────────────────────────────────

    /** Replace the log sink. Pass null to silence the agent. */
    fun setLogSink(sink: LogSink?) {
        logSink = sink
    }

    /** Subscribe to REBOOT commands. The previous listener is replaced. */
    fun setRebootListener(listener: RebootListener?) {
        rebootListener = listener
    }

    /** Override the DNS servers used for outbound resolution. Empty/blank
     *  string restores the system resolver. Safe to call any time. */
    fun setDnsServers(csv: String) {
        dnsConfig.setFromString(csv)
    }

    /** Return a point-in-time snapshot of the agent's state. */
    fun getStatus(): Status = statusSnapshot.copy(activeTunnels = activeTunnels.get())

    /**
     * Start the agent. Idempotent — second call is a no-op while running.
     * Returns true if the call actually started the agent, false otherwise.
     *
     * Blocking work happens on a dedicated supervisor thread; this method
     * returns immediately. Use [getStatus] to observe progress.
     */
    fun start(config: Config): Boolean {
        if (!running.compareAndSet(false, true)) return false
        stopRequested.set(false)
        activeTunnels.set(0)
        statusSnapshot = statusSnapshot.copy(
            running = true,
            connected = false,
            registratorHost = null,
            registratorPort = 0,
            transport = null,
            activeTunnels = 0,
            lastError = null,
            startedAtMs = System.currentTimeMillis(),
        )
        if (config.dnsServers.isNotBlank()) {
            dnsConfig.setFromString(config.dnsServers)
        }
        val t = Thread({ supervisorMain(config) }, "NativeProxyAgent-Supervisor").apply {
            isDaemon = true
        }
        supervisorThread = t
        t.start()
        return true
    }

    /** Stop the agent. Idempotent. Blocks until the supervisor exits or
     *  the optional [timeoutMs] elapses. */
    @JvmOverloads
    fun stop(timeoutMs: Long = 5_000L) {
        if (!running.get()) return
        stopRequested.set(true)
        try { currentUplink?.shutdown() } catch (_: Throwable) {}
        val t = supervisorThread
        try {
            t?.interrupt()
            t?.join(timeoutMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        supervisorThread = null
        currentUplink = null
        running.set(false)
        statusSnapshot = statusSnapshot.copy(running = false, connected = false)
    }

    // ────────────────────────────────────────────────────────────────────
    // Supervisor loop — mirrors internal/supervisor/supervisor.go
    // ────────────────────────────────────────────────────────────────────

    private fun supervisorMain(cfg: Config) {
        logInfo("supervisor start")
        try {
            val backoff = ExponentialJitterBackoff(
                baseMs = 250L,
                maxMs = 10_000L,
                factor = 2.0,
                jitter = 1.0,
            )
            val heartbeatIntervalNs = if (cfg.enableHeartbeat) {
                TimeUnit.SECONDS.toNanos(cfg.heartbeatIntervalSec.toLong().coerceAtLeast(1))
            } else 0L
            val lastHeartbeatNs = java.util.concurrent.atomic.AtomicLong(System.nanoTime())

            val balancer = if (cfg.hasBalancer()) BalancerClient(cfg) else null
            val fallback = if (cfg.hasFallback()) FallbackSelector(cfg) else null
            var fallbackLoaded = false

            while (!stopRequested.get()) {
                // 1. Discover a registrator
                val creds = discoverRegistrator(cfg, balancer, fallback,
                    onFallbackLoaded = { fallbackLoaded = true },
                    fallbackAlreadyLoaded = fallbackLoaded)
                if (creds == null) {
                    val sleep = backoff.nextMs()
                    logWarn("no registrator available; backing off", "sleep_ms" to sleep)
                    if (sleepInterruptible(sleep)) return
                    maybeHeartbeat(heartbeatIntervalNs, lastHeartbeatNs)
                    continue
                }

                statusSnapshot = statusSnapshot.copy(
                    registratorHost = creds.host,
                    registratorPort = creds.port,
                    connected = false,
                    transport = null,
                )

                // 2. Bring up an uplink (QUIC or TCP)
                val info = collectSystemInfo()
                val uplink = Uplink(this, cfg, dnsConfig)
                currentUplink = uplink
                val sessionErr = try {
                    uplink.runOnce(creds, info)
                } catch (t: Throwable) {
                    logWarn("uplink threw", "error" to (t.message ?: t.javaClass.simpleName))
                    t
                } finally {
                    currentUplink = null
                    statusSnapshot = statusSnapshot.copy(connected = false, transport = null)
                }
                if (stopRequested.get()) return
                if (sessionErr != null) {
                    statusSnapshot = statusSnapshot.copy(
                        lastError = sessionErr.message ?: sessionErr.javaClass.simpleName,
                    )
                }

                // 3. Backoff before reconnect
                val delay = backoff.nextMs()
                logInfo("reconnect backoff", "sleep_ms" to delay)
                if (sleepInterruptible(delay)) return
                maybeHeartbeat(heartbeatIntervalNs, lastHeartbeatNs)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (t: Throwable) {
            logError("supervisor exited with error", "error" to (t.message ?: t.javaClass.simpleName))
        } finally {
            running.set(false)
            statusSnapshot = statusSnapshot.copy(running = false, connected = false)
            logInfo("supervisor stop")
        }
    }

    private fun maybeHeartbeat(intervalNs: Long, last: java.util.concurrent.atomic.AtomicLong) {
        if (intervalNs <= 0) return
        // Heartbeat is logged-only — mirrors the Go SDK's heartbeat
        // ticker which also does no wire I/O. Just a periodic log line
        // so log readers know the supervisor is alive between sessions.
        val now = System.nanoTime()
        if (now - last.get() < intervalNs) return
        logInfo("heartbeat", "ts" to System.currentTimeMillis())
        last.set(now)
    }

    private fun discoverRegistrator(
        cfg: Config,
        balancer: BalancerClient?,
        fallback: FallbackSelector?,
        onFallbackLoaded: () -> Unit,
        fallbackAlreadyLoaded: Boolean,
    ): RegistratorCreds? {
        val directHost = cfg.registratorHost
        if (!directHost.isNullOrBlank() && cfg.registratorPort > 0) {
            // Match the Go SDK's "direct registrator configured" log
            // line so external parsers (e.g. ProxyService.parseAgentLine
            // in the Android host app) can populate currentRegistrator.
            val h = directHost.trim()
            logInfo(
                "direct registrator configured; balancer and fallback selection disabled",
                "host" to h, "port" to cfg.registratorPort,
            )
            return RegistratorCreds(host = h, port = cfg.registratorPort, apiKey = cfg.agentKey)
        }
        if (balancer != null) {
            try {
                val creds = balancer.select(cfg.agentKey)
                if (creds != null) {
                    logInfo(
                        "selected registrator via balancer",
                        "host" to creds.host, "port" to creds.port,
                    )
                    return creds
                }
            } catch (t: Throwable) {
                logWarn("balancer selection failed; attempting fallback",
                    "error" to (t.message ?: t.javaClass.simpleName))
            }
        }
        if (fallback != null) {
            if (!fallbackAlreadyLoaded) {
                try {
                    fallback.loadList(cfg.agentKey)
                    onFallbackLoaded()
                } catch (t: Throwable) {
                    logWarn("failed to load fallback list",
                        "error" to (t.message ?: t.javaClass.simpleName))
                }
            }
            try {
                fallback.probeAll()
                val sel = fallback.selected()
                if (sel != null) {
                    logInfo(
                        "selected registrator via fallback",
                        "host" to sel.host, "port" to sel.port,
                    )
                    return sel
                }
            } catch (t: Throwable) {
                logWarn("fallback probe failed",
                    "error" to (t.message ?: t.javaClass.simpleName))
            }
        }
        return null
    }

    // ────────────────────────────────────────────────────────────────────
    // Internal — logging
    // ────────────────────────────────────────────────────────────────────

    internal fun logInfo(msg: String, vararg fields: Pair<String, Any?>) =
        logSink?.log("INFO", msg, fields.toMap())

    internal fun logWarn(msg: String, vararg fields: Pair<String, Any?>) =
        logSink?.log("WARN", msg, fields.toMap())

    internal fun logError(msg: String, vararg fields: Pair<String, Any?>) =
        logSink?.log("ERROR", msg, fields.toMap())

    internal fun logDebug(msg: String, vararg fields: Pair<String, Any?>) =
        logSink?.log("DEBUG", msg, fields.toMap())

    internal fun setConnected(transport: String) {
        statusSnapshot = statusSnapshot.copy(connected = true, transport = transport)
    }

    internal fun incTunnels() = activeTunnels.incrementAndGet()
    internal fun decTunnels() = activeTunnels.updateAndGet { (it - 1).coerceAtLeast(0) }

    internal fun fireReboot(reason: String) {
        val r = reason.ifBlank { "Reboot command from host" }
        try { rebootListener?.onReboot(r) } catch (_: Throwable) {}
    }

    // ────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────

    private fun sleepInterruptible(ms: Long): Boolean {
        if (ms <= 0) return stopRequested.get()
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return true
        }
        return stopRequested.get()
    }

    private fun collectSystemInfo(): SystemInfo {
        val ips = mutableListOf<String>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return SystemInfo(
                os = "android", arch = osArch(), cpuCount = Runtime.getRuntime().availableProcessors(),
                hostname = "", ips = emptyList(),
            )
            for (iface in ifaces.iterator()) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses.iterator()) {
                    if (addr.isLoopbackAddress) continue
                    ips += addr.hostAddress ?: continue
                }
            }
        } catch (_: Throwable) {}
        return SystemInfo(
            os = "android",
            arch = osArch(),
            cpuCount = Runtime.getRuntime().availableProcessors(),
            hostname = try { InetAddress.getLocalHost().hostName ?: "" } catch (_: Throwable) { "" },
            ips = ips,
        )
    }

    private fun osArch(): String = try {
        System.getProperty("os.arch") ?: "arm64"
    } catch (_: Throwable) { "arm64" }

    // ────────────────────────────────────────────────────────────────────
    // Companion: convenient defaults
    // ────────────────────────────────────────────────────────────────────

    companion object {
        // Wire protocol — must match proxy-agent-sdk-go/internal/netagent/wire.go
        internal val WIRE_MAGIC = byteArrayOf('T'.code.toByte(), 'U'.code.toByte(), 'N'.code.toByte(), 'L'.code.toByte())
        internal const val WIRE_VERSION: Byte = 1
        internal const val CONN_TYPE_CONTROL: Byte = 0x01
        internal const val CONN_TYPE_DATA: Byte = 0x02
        internal const val TOKEN_BYTES = 16  // 32 hex chars on the wire

        // QUIC ALPN — must match the server's NextProtos.
        internal const val QUIC_ALPN = "proxy-tunnel/1"

        // Timeouts (mirror Go SDK constants in internal/netagent/uplink.go).
        internal const val AUTH_HANDSHAKE_TIMEOUT_MS = 30_000L
        internal const val TARGET_DIAL_TIMEOUT_MS = 30_000L
        internal const val POOL_DIAL_TIMEOUT_MS = 10_000L
        internal const val POOL_REFILL_IDLE_MS = 5_000L
    }
}

// ────────────────────────────────────────────────────────────────────────
// Small DTOs shared by internal helpers
// ────────────────────────────────────────────────────────────────────────

internal data class RegistratorCreds(
    val host: String,
    val port: Int,
    val healthCheckPort: Int = 1001,
    val apiKey: String,
)

internal data class SystemInfo(
    val os: String,
    val arch: String,
    val cpuCount: Int,
    val hostname: String,
    val ips: List<String>,
)

// ────────────────────────────────────────────────────────────────────────
// Exponential jitter backoff — mirrors internal/backoff/backoff.go
// ────────────────────────────────────────────────────────────────────────

internal class ExponentialJitterBackoff(
    private val baseMs: Long,
    private val maxMs: Long,
    private val factor: Double,
    private val jitter: Double,
) {
    private var attempt = 0
    private val rng = Random(System.nanoTime())

    fun nextMs(): Long {
        val raw = baseMs.toDouble() * factor.pow(attempt)
        var d = min(raw, maxMs.toDouble()).toLong()
        if (jitter > 0) {
            val maxJ = (d * jitter).toLong()
            if (maxJ > 0) {
                val j = rng.nextLong(maxJ + 1)
                d = (d - maxJ + j).coerceAtLeast(0L)
            }
        }
        attempt += 1
        return d
    }

    fun reset() { attempt = 0 }
}

// ────────────────────────────────────────────────────────────────────────
// DNS override — mirrors internal/dnsconfig/dnsconfig.go
// ────────────────────────────────────────────────────────────────────────

internal class DnsConfig {
    @Volatile private var servers: List<String> = emptyList()

    fun setFromString(csv: String) {
        if (csv.isBlank()) {
            servers = emptyList()
            return
        }
        servers = csv
            .split(',', ' ', '\t', '\n', ';')
            .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            .map(::ensureDnsPort)
    }

    private fun ensureDnsPort(s: String): String {
        // IPv6 literal already bracketed → host:port or host alone.
        if (s.startsWith('[')) {
            return if (']' in s && s.lastIndexOf(':') > s.indexOf(']')) s
                   else "$s:53"
        }
        // Hostname or IPv4: a colon means port already present.
        return if (':' in s) s else "$s:53"
    }

    fun list(): List<String> = servers

    fun hasOverride(): Boolean = servers.isNotEmpty()

    /** Resolve [host] to an [InetAddress]. With an override active, queries
     *  the configured servers via UDP DNS; otherwise uses the JVM resolver.
     *  Falls back to JVM if explicit queries all fail. */
    fun resolve(host: String): InetAddress {
        // Literal IPs short-circuit.
        try {
            return InetAddress.getByName(host)
        } catch (_: Throwable) {}
        // Without override → JVM resolver.
        if (!hasOverride()) {
            return InetAddress.getByName(host)
        }
        for (server in servers) {
            try {
                val ip = MiniDnsClient.queryA(host, server)
                if (ip != null) return InetAddress.getByAddress(host, ip)
            } catch (_: Throwable) {}
        }
        // Last resort: JVM resolver. Better a leaked query than no traffic.
        return InetAddress.getByName(host)
    }
}

// Minimal DNS-over-UDP A-record resolver. Used only when an explicit DNS
// override is in place; otherwise we let the JVM handle resolution. Built
// without external deps so the file stays drop-in.
internal object MiniDnsClient {
    fun queryA(name: String, server: String): ByteArray? {
        val (host, port) = parseServer(server)
        val id = (Random.nextInt() and 0xFFFF).toShort()
        val query = buildQuery(id, name)
        java.net.DatagramSocket().use { sock ->
            sock.soTimeout = 3000
            val pkt = java.net.DatagramPacket(query, query.size, InetAddress.getByName(host), port)
            sock.send(pkt)
            val buf = ByteArray(1500)
            val resp = java.net.DatagramPacket(buf, buf.size)
            sock.receive(resp)
            return parseA(buf, resp.length, id)
        }
    }

    private fun parseServer(s: String): Pair<String, Int> {
        // host:port — but watch out for IPv6 literals like [::1]:53.
        if (s.startsWith('[')) {
            val close = s.indexOf(']')
            if (close > 0) {
                val host = s.substring(1, close)
                val portStr = if (s.length > close + 2 && s[close + 1] == ':') s.substring(close + 2) else "53"
                return host to (portStr.toIntOrNull() ?: 53)
            }
        }
        val colon = s.lastIndexOf(':')
        if (colon > 0 && s.indexOf(':') == colon) {
            return s.substring(0, colon) to (s.substring(colon + 1).toIntOrNull() ?: 53)
        }
        return s to 53
    }

    private fun buildQuery(id: Short, name: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write((id.toInt() shr 8) and 0xFF)
        out.write(id.toInt() and 0xFF)
        out.write(0x01); out.write(0x00)   // flags: RD
        out.write(0x00); out.write(0x01)   // qdcount=1
        out.write(0x00); out.write(0x00)   // ancount=0
        out.write(0x00); out.write(0x00)   // nscount=0
        out.write(0x00); out.write(0x00)   // arcount=0
        for (label in name.split('.')) {
            val bytes = label.toByteArray(StandardCharsets.US_ASCII)
            if (bytes.isEmpty()) continue
            out.write(bytes.size and 0xFF)
            out.write(bytes)
        }
        out.write(0)            // null terminator
        out.write(0x00); out.write(0x01)   // qtype = A
        out.write(0x00); out.write(0x01)   // qclass = IN
        return out.toByteArray()
    }

    private fun parseA(buf: ByteArray, len: Int, expectId: Short): ByteArray? {
        if (len < 12) return null
        val id = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
        if (id.toShort() != expectId) return null
        val anCount = ((buf[6].toInt() and 0xFF) shl 8) or (buf[7].toInt() and 0xFF)
        if (anCount == 0) return null
        var pos = 12
        // Skip question section (qname + qtype + qclass)
        pos = skipName(buf, pos, len) ?: return null
        pos += 4
        // Walk answers; return the first A record.
        repeat(anCount) {
            if (pos >= len) return null
            pos = skipName(buf, pos, len) ?: return null
            if (pos + 10 > len) return null
            val type = ((buf[pos].toInt() and 0xFF) shl 8) or (buf[pos + 1].toInt() and 0xFF)
            val rdLen = ((buf[pos + 8].toInt() and 0xFF) shl 8) or (buf[pos + 9].toInt() and 0xFF)
            pos += 10
            if (type == 1 && rdLen == 4 && pos + 4 <= len) {
                return byteArrayOf(buf[pos], buf[pos + 1], buf[pos + 2], buf[pos + 3])
            }
            pos += rdLen
        }
        return null
    }

    private fun skipName(buf: ByteArray, start: Int, len: Int): Int? {
        var p = start
        while (p < len) {
            val b = buf[p].toInt() and 0xFF
            if (b == 0) { return p + 1 }
            if (b and 0xC0 == 0xC0) { return p + 2 }   // compression pointer
            p += 1 + b
        }
        return null
    }
}

// ────────────────────────────────────────────────────────────────────────
// HTTP balancer client — mirrors internal/registrator/balancer.go
// ────────────────────────────────────────────────────────────────────────

internal class BalancerClient(private val cfg: NativeProxyAgent.Config) {
    fun select(apiKey: String): RegistratorCreds? {
        val urlStr = "http://${cfg.balancerHost}:${cfg.balancerPort}${cfg.balancerPath}"
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = cfg.httpTimeoutMs
            readTimeout = cfg.httpTimeoutMs
            requestMethod = "GET"
            setRequestProperty("Content-Type", "application/json")
            if (apiKey.isNotEmpty()) {
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
        }
        try {
            val code = conn.responseCode
            if (code != 200) {
                throw IOException("balancer returned status $code")
            }
            val body = conn.inputStream.use { it.readBytes() }.toString(StandardCharsets.UTF_8)
            val obj = MiniJson.parseObject(body)
            val host = obj.string("host") ?: return null
            val port = obj.int("port") ?: 443
            val healthPort = obj.int("health_check_port") ?: 1001
            val resolvedHost = if (host == "0.0.0.0") cfg.balancerHost!! else host
            return RegistratorCreds(
                host = resolvedHost,
                port = if (port == 0) 443 else port,
                healthCheckPort = if (healthPort == 0) 1001 else healthPort,
                apiKey = apiKey,
            )
        } finally {
            conn.disconnect()
        }
    }
}

// ────────────────────────────────────────────────────────────────────────
// Fallback list + /health probe — mirrors internal/registrator/fallback.go
// ────────────────────────────────────────────────────────────────────────

internal class FallbackSelector(private val cfg: NativeProxyAgent.Config) {
    private val list = mutableListOf<RegistratorCreds>()
    @Volatile private var selected: RegistratorCreds? = null

    fun loadList(apiKey: String) {
        val url = cfg.fallbackFileUrl ?: return
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = cfg.httpTimeoutMs
            readTimeout = cfg.httpTimeoutMs
            requestMethod = "GET"
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            val code = conn.responseCode
            if (code != 200) throw IOException("fallback list status=$code")
            val body = conn.inputStream.use { it.readBytes() }.toString(StandardCharsets.UTF_8)
            val arr = MiniJson.parseArray(body)
            list.clear()
            for (entry in arr.items()) {
                val obj = entry.asObject() ?: continue
                val host = obj.string("host") ?: continue
                val port = obj.int("port") ?: continue
                val hcp = obj.int("health_check_port") ?: 1003
                list.add(RegistratorCreds(host, port, hcp, apiKey))
            }
        } finally {
            conn.disconnect()
        }
    }

    fun probeAll() {
        if (list.isEmpty()) throw IOException("no fallback registrators")
        var best: Triple<RegistratorCreds, Stats, Int>? = null
        for ((idx, r) in list.withIndex()) {
            val s = try { probeOne(r) } catch (_: Throwable) { continue }
            if (best == null || compareStats(s, best.second) > 0) {
                best = Triple(r, s, idx)
            }
        }
        if (best == null) throw IOException("no registrator passed probe")
        selected = best.first
    }

    fun selected(): RegistratorCreds? = selected

    private data class Stats(
        val ready: Boolean,
        val agentCount: Int,
        val freeSockets: Int,
        val cpuLoad: Double,
        val ramLoad: Double,
    )

    private fun compareStats(a: Stats, b: Stats): Int {
        if (a.ready != b.ready) return if (a.ready) 1 else -1
        if (a.freeSockets != b.freeSockets) return a.freeSockets - b.freeSockets
        if (a.cpuLoad != b.cpuLoad) return if (a.cpuLoad < b.cpuLoad) 1 else -1
        if (a.ramLoad != b.ramLoad) return if (a.ramLoad < b.ramLoad) 1 else -1
        if (a.agentCount != b.agentCount) return b.agentCount - a.agentCount
        return 0
    }

    private fun probeOne(r: RegistratorCreds): Stats {
        val url = "http://${r.host}:${r.healthCheckPort}/health"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 5000
            requestMethod = "GET"
            if (r.apiKey.isNotEmpty()) {
                setRequestProperty("Authorization", "Bearer ${r.apiKey}")
            }
        }
        try {
            if (conn.responseCode == 401) throw IOException("unauthorized")
            val body = conn.inputStream.use { it.readBytes() }.toString(StandardCharsets.UTF_8)
            val obj = MiniJson.parseObject(body)
            return Stats(
                ready = obj.bool("ready") ?: false,
                agentCount = obj.int("agentCount") ?: 0,
                freeSockets = obj.int("freeSockets") ?: 0,
                cpuLoad = obj.double("cpuLoad") ?: 0.0,
                ramLoad = obj.double("ramLoad") ?: 0.0,
            )
        } finally {
            conn.disconnect()
        }
    }
}

// ────────────────────────────────────────────────────────────────────────
// Uplink — control + data path. TCP first, QUIC fallback (or vice versa
// depending on the sticky cache). Mirrors internal/netagent/uplink.go.
// ────────────────────────────────────────────────────────────────────────

internal class Uplink(
    internal val agent: NativeProxyAgent,
    private val cfg: NativeProxyAgent.Config,
    private val dns: DnsConfig,
) {
    @Volatile private var controlSocket: Socket? = null
    @Volatile private var controlInput: BufferedInputStream? = null
    @Volatile private var controlOutput: BufferedOutputStream? = null
    @Volatile private var quic: QuicTransport? = null
    @Volatile private var quicControlStream: QuicTransport.Stream? = null
    @Volatile private var dataPool: DataPool? = null
    @Volatile private var transportLabel: String = ""

    private val writeLock = Any()
    private val shuttingDown = AtomicBoolean(false)

    private val openExecutor = Executors.newCachedThreadPool(daemonFactory("uplink-open"))
    private val bridgeExecutor = Executors.newCachedThreadPool(daemonFactory("uplink-bridge"))

    fun runOnce(creds: RegistratorCreds, info: SystemInfo): Throwable? {
        val endpoint = "${creds.host}:${creds.port}"
        val order = chooseTransportOrder()
        var dialErr: Throwable? = null
        var used = ""
        for (t in order) {
            try {
                agent.logInfo("uplink dialing", "endpoint" to endpoint, "transport" to t)
                when (t) {
                    "tcp" -> startTcp(creds)
                    "quic" -> startQuic(creds)
                }
                used = t
                break
            } catch (e: Throwable) {
                agent.logWarn("uplink: transport dial failed",
                    "transport" to t, "endpoint" to endpoint,
                    "error" to (e.message ?: e.javaClass.simpleName))
                dialErr = e
                cleanupTransport()
            }
        }
        if (used.isEmpty()) {
            return dialErr ?: IOException("uplink: all transports failed")
        }
        transportLabel = used

        // AUTH
        return try {
            doAuth(creds, info)
            agent.setConnected(used)
            agent.logInfo("uplink connected",
                "uuid" to (cfg.agentUuid?.ifBlank { null } ?: creds.apiKey),
                "transport" to used)
            writeTransportCache(used)
            if (used == "tcp") {
                dataPool = DataPool(this, cfg.tcpWarmPoolSize, creds, cfg, dns)
                dataPool!!.start()
            }
            runLoops(used)
            null
        } catch (t: Throwable) {
            t
        } finally {
            shutdown()
        }
    }

    /** Drives the control-read loop, and for QUIC also the accept loop
     *  for server-initiated tunnel streams. Returns when any loop ends. */
    private fun runLoops(transport: String) {
        val controlDone = java.util.concurrent.CountDownLatch(1)
        val controlThread = Thread({
            try { controlReadLoop(transport) }
            finally { controlDone.countDown() }
        }, "uplink-control").apply { isDaemon = true }

        var acceptThread: Thread? = null
        val acceptDone = java.util.concurrent.CountDownLatch(if (transport == "quic") 1 else 0)
        if (transport == "quic") {
            acceptThread = Thread({
                try { quicAcceptLoop() }
                finally { acceptDone.countDown() }
            }, "uplink-quic-accept").apply { isDaemon = true }
        }

        controlThread.start()
        acceptThread?.start()

        // Wait for any loop to exit, then trigger shutdown so the other
        // loop unblocks too.
        try {
            while (!shuttingDown.get()) {
                if (controlDone.await(500, TimeUnit.MILLISECONDS)) break
                if (transport == "quic" && acceptDone.await(0, TimeUnit.MILLISECONDS)) break
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun quicAcceptLoop() {
        val q = quic ?: return
        while (!shuttingDown.get()) {
            val stream = try {
                q.acceptStream()
            } catch (_: Throwable) { return }
            openExecutor.execute { handleQuicTunnelStream(stream) }
        }
    }

    /** Server opened a fresh QUIC stream for one tunnel — read the JSON
     *  header (host/port), dial the target, pipe bytes. Mirrors
     *  internal/netagent/uplink.go handleQUICTunnelStream. */
    private fun handleQuicTunnelStream(stream: QuicTransport.Stream) {
        agent.incTunnels()
        var targetSock: Socket? = null
        try {
            val reader = BufferedInputStream(stream.input)
            val line = StringBuilder()
            val deadline = System.currentTimeMillis() + 15_000
            while (true) {
                if (System.currentTimeMillis() > deadline) {
                    throw IOException("quic tunnel header timeout")
                }
                val b = reader.read()
                if (b < 0) throw EOFException("quic tunnel header EOF")
                if (b == '\n'.code) break
                if (b != '\r'.code) line.append(b.toChar())
                if (line.length > 8192) throw IOException("quic header too long")
            }
            val hdr = MiniJson.parseObject(line.toString())
            val host = hdr.string("host") ?: throw IOException("missing host")
            val port = hdr.int("port") ?: throw IOException("missing port")
            val target = "$host:$port"
            agent.logInfo("opening quic tunnel", "target" to target)
            val sock = Socket()
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress(dns.resolve(host), port),
                NativeProxyAgent.TARGET_DIAL_TIMEOUT_MS.toInt())
            targetSock = sock
            bridgeStreams(reader, stream.output, sock)
            agent.logInfo("quic tunnel closed", "target" to target)
        } catch (t: Throwable) {
            agent.logWarn("quic tunnel failed",
                "error" to (t.message ?: t.javaClass.simpleName))
            try { stream.close() } catch (_: Throwable) {}
            try { targetSock?.close() } catch (_: Throwable) {}
        } finally {
            agent.decTunnels()
        }
    }

    private fun chooseTransportOrder(): List<String> {
        val cached = readTransportCache()
        val quicAvailable = cfg.quicTransportFactory != null
        return when {
            cached == "quic" && quicAvailable -> listOf("quic", "tcp")
            cached == "tcp" -> listOf("tcp", "quic").filter { it != "quic" || quicAvailable }
            quicAvailable -> listOf("tcp", "quic")  // TCP-first by default (splice fast path)
            else -> listOf("tcp")
        }
    }

    private fun startTcp(creds: RegistratorCreds) {
        val sock = Socket()
        sock.tcpNoDelay = true
        sock.keepAlive = true
        try { sock.setSoLinger(false, 0) } catch (_: Throwable) {}
        sock.connect(
            InetSocketAddress(dns.resolve(creds.host), creds.port),
            cfg.dialTimeoutMs,
        )

        val out = BufferedOutputStream(sock.getOutputStream())
        out.write(NativeProxyAgent.WIRE_MAGIC)
        out.write(byteArrayOf(NativeProxyAgent.WIRE_VERSION, NativeProxyAgent.CONN_TYPE_CONTROL))
        out.flush()

        controlSocket = sock
        controlInput = BufferedInputStream(sock.getInputStream())
        controlOutput = out
        agent.logInfo("uplink: TCP control established", "endpoint" to "${creds.host}:${creds.port}")
    }

    private fun startQuic(creds: RegistratorCreds) {
        val factory = cfg.quicTransportFactory
            ?: throw IOException("QUIC factory not registered")
        val transport = factory.connect(
            host = creds.host,
            port = creds.port,
            alpn = NativeProxyAgent.QUIC_ALPN,
            dialTimeoutMs = cfg.quicDialTimeoutMs,
            dns = QuicTransport.DnsAdapter { dns.resolve(it) },
        )
        val stream = transport.openControlStream()
        quic = transport
        quicControlStream = stream
        controlInput = BufferedInputStream(stream.input)
        controlOutput = BufferedOutputStream(stream.output)
        agent.logInfo("uplink: QUIC control established",
            "endpoint" to "${creds.host}:${creds.port}")
    }

    private fun cleanupTransport() {
        try { controlOutput?.close() } catch (_: Throwable) {}
        try { controlInput?.close() } catch (_: Throwable) {}
        try { controlSocket?.close() } catch (_: Throwable) {}
        controlSocket = null
        controlInput = null
        controlOutput = null
        try { quicControlStream?.close() } catch (_: Throwable) {}
        try { quic?.close() } catch (_: Throwable) {}
        quicControlStream = null
        quic = null
    }

    fun shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) return
        cleanupTransport()
        dataPool?.close()
        dataPool = null
        openExecutor.shutdownNow()
        bridgeExecutor.shutdownNow()
    }

    // ── AUTH ────────────────────────────────────────────────────────────

    private fun doAuth(creds: RegistratorCreds, info: SystemInfo) {
        val key = creds.apiKey.trim()
        if (key.isEmpty()) {
            agent.logWarn("uplink missing apiKey; server will reject AUTH")
        }
        val uuid = (cfg.agentUuid?.trim()?.ifEmpty { null }) ?: key
        val json = buildString {
            append('{')
            append("\"command\":\"AUTH\",")
            append("\"key\":"); MiniJson.appendString(this, key); append(',')
            append("\"uuid\":"); MiniJson.appendString(this, uuid)
            if (info.hostname.isNotEmpty()) {
                append(',')
                append("\"host\":"); MiniJson.appendString(this, info.hostname)
            }
            if (info.ips.isNotEmpty()) {
                append(',')
                append("\"sourceIPList\":[")
                for ((i, ip) in info.ips.withIndex()) {
                    if (i > 0) append(',')
                    MiniJson.appendString(this, ip)
                }
                append(']')
            }
            append('}')
        }
        writeControlLine(json)
        val originalTimeout = controlSocket?.soTimeout ?: 0
        controlSocket?.soTimeout = NativeProxyAgent.AUTH_HANDSHAKE_TIMEOUT_MS.toInt()
        try {
            val reply = readControlLine() ?: run {
                agent.logWarn("uplink AUTH denied (control closed before reply)",
                    "error" to "EOF")
                throw IOException("auth denied: EOF")
            }
            val obj = MiniJson.parseObject(reply)
            val cmd = obj.string("command")
            if (cmd != "AUTH_OK") {
                agent.logWarn("uplink AUTH unexpected reply", "command" to (cmd ?: ""))
                throw IOException("auth denied: unexpected reply \"$cmd\"")
            }
        } finally {
            controlSocket?.soTimeout = originalTimeout
        }
    }

    // ── Control read loop ───────────────────────────────────────────────

    private fun controlReadLoop(transport: String) {
        while (!shuttingDown.get()) {
            val line = try {
                readControlLine() ?: break
            } catch (_: EOFException) {
                break
            } catch (_: IOException) {
                break
            }
            val obj = try { MiniJson.parseObject(line) } catch (_: Throwable) {
                agent.logWarn("control bad json", "line" to line)
                continue
            }
            when (val cmd = obj.string("command") ?: "") {
                "REBOOT" -> handleReboot(obj.string("reason") ?: "")
                "OPEN" -> {
                    val token = obj.string("token") ?: continue
                    val host = obj.string("host") ?: continue
                    val port = obj.int("port") ?: continue
                    openExecutor.execute { handleOpen(transport, token, host, port) }
                }
                else -> agent.logDebug("unknown control command", "command" to cmd)
            }
        }
        if (transport == "quic") {
            // QUIC: also wind down the accept loop on control exit.
            agent.logInfo("uplink control loop ended")
        } else {
            agent.logInfo("uplink control loop ended")
        }
    }

    private fun handleReboot(reason: String) {
        agent.logInfo("REBOOT received from registrator", "reason" to reason)
        agent.fireReboot(reason)
        // Tearing down forces the supervisor's reconnect loop to redial.
        agent.logInfo("REBOOT: tearing down tunnel session, reconnect will follow")
        shutdown()
    }

    // ── OPEN handler ────────────────────────────────────────────────────

    private fun handleOpen(transport: String, token: String, host: String, port: Int) {
        val target = "$host:$port"
        agent.logInfo("opening tunnel", "target" to target, "token" to shortToken(token))
        agent.incTunnels()
        try {
            if (transport == "tcp") {
                handleOpenTcp(token, host, port)
            } else {
                // QUIC mode: tunnels arrive as server-initiated streams,
                // not OPEN commands. If the server somehow sent one here,
                // we still honor it via a fresh stream.
                handleOpenQuic(token, host, port)
            }
        } catch (t: Throwable) {
            agent.logWarn("tunnel open failed", "target" to target,
                "error" to (t.message ?: t.javaClass.simpleName))
            reportOpenFail(token, t.message ?: t.javaClass.simpleName)
        } finally {
            agent.decTunnels()
        }
    }

    private fun handleOpenTcp(token: String, host: String, port: Int) {
        val pool = dataPool ?: throw IOException("no data pool (not TCP mode)")
        var dataSock: Socket? = null
        var targetSock: Socket? = null
        try {
            // Dial target + take data conn in parallel — same as Go.
            val futureTarget = bridgeExecutor.submit<Socket> {
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(
                    InetSocketAddress(dns.resolve(host), port),
                    NativeProxyAgent.TARGET_DIAL_TIMEOUT_MS.toInt(),
                )
                s
            }
            val futureData = bridgeExecutor.submit<Socket> { pool.take() }

            val target = try {
                futureTarget.get(NativeProxyAgent.TARGET_DIAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (t: Throwable) {
                futureData.cancel(true)
                throw IOException("target dial: ${t.message ?: t.javaClass.simpleName}", t)
            }
            targetSock = target
            val data = try {
                futureData.get(NativeProxyAgent.TARGET_DIAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (t: Throwable) {
                try { target.close() } catch (_: Throwable) {}
                targetSock = null
                throw IOException("data conn: ${t.message ?: t.javaClass.simpleName}", t)
            }
            dataSock = data

            // Token on the data conn → server pairs with pending waiter.
            try {
                data.soTimeout = 10_000
                val out = data.getOutputStream()
                out.write(token.toByteArray(StandardCharsets.US_ASCII))
                out.flush()
                data.soTimeout = 0
            } catch (t: Throwable) {
                throw IOException("token write: ${t.message ?: t.javaClass.simpleName}", t)
            }

            bridge(data, target)
        } catch (t: Throwable) {
            try { dataSock?.close() } catch (_: Throwable) {}
            try { targetSock?.close() } catch (_: Throwable) {}
            throw t
        }
    }

    private fun handleOpenQuic(token: String, host: String, port: Int) {
        // Rarely used in practice — QUIC mode does its tunnels via server-
        // initiated streams. But if the server emits an OPEN over the
        // control channel anyway, fulfill it by opening a fresh stream.
        val q = quic ?: throw IOException("no quic session")
        val stream = q.openStream()
        val targetSock = Socket().apply {
            tcpNoDelay = true
            connect(InetSocketAddress(dns.resolve(host), port),
                NativeProxyAgent.TARGET_DIAL_TIMEOUT_MS.toInt())
        }
        try {
            stream.output.write(token.toByteArray(StandardCharsets.US_ASCII))
            stream.output.flush()
            bridgeStreams(stream.input, stream.output, targetSock)
        } catch (t: Throwable) {
            try { stream.close() } catch (_: Throwable) {}
            try { targetSock.close() } catch (_: Throwable) {}
            throw t
        }
    }

    private fun reportOpenFail(token: String, reason: String) {
        try {
            val json = buildString {
                append('{')
                append("\"command\":\"OPEN_FAIL\",")
                append("\"token\":"); MiniJson.appendString(this, token); append(',')
                append("\"reason\":"); MiniJson.appendString(this, reason)
                append('}')
            }
            writeControlLine(json)
        } catch (t: Throwable) {
            agent.logWarn("OPEN_FAIL send failed",
                "error" to (t.message ?: t.javaClass.simpleName))
        }
    }

    private fun shortToken(t: String): String = if (t.length < 6) t else t.substring(0, 6)

    // ── Control I/O helpers ─────────────────────────────────────────────

    internal fun writeControlLine(json: String) {
        val out = controlOutput ?: throw IOException("uplink: control channel not initialized")
        synchronized(writeLock) {
            out.write(json.toByteArray(StandardCharsets.UTF_8))
            out.write('\n'.code)
            out.flush()
        }
    }

    private fun readControlLine(): String? {
        val input = controlInput ?: throw IOException("uplink: control channel not initialized")
        val buf = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) {
                return if (buf.isEmpty()) null else buf.toString()
            }
            if (b == '\n'.code) return buf.toString()
            if (b == '\r'.code) continue
            buf.append(b.toChar())
            if (buf.length > 64 * 1024) {
                throw IOException("control line too long")
            }
        }
    }

    // ── Bridging ────────────────────────────────────────────────────────

    private fun bridge(a: Socket, b: Socket) {
        val done = java.util.concurrent.CountDownLatch(2)
        bridgeExecutor.execute {
            try {
                copyStream(a.getInputStream(), b.getOutputStream())
                try { b.shutdownOutput() } catch (_: Throwable) {}
            } catch (_: Throwable) {} finally { done.countDown() }
        }
        bridgeExecutor.execute {
            try {
                copyStream(b.getInputStream(), a.getOutputStream())
                try { a.shutdownOutput() } catch (_: Throwable) {}
            } catch (_: Throwable) {} finally { done.countDown() }
        }
        try { done.await() } catch (_: InterruptedException) {}
        try { a.close() } catch (_: Throwable) {}
        try { b.close() } catch (_: Throwable) {}
        agent.logInfo("tunnel closed")
    }

    private fun bridgeStreams(input: InputStream, output: OutputStream, sock: Socket) {
        val done = java.util.concurrent.CountDownLatch(2)
        bridgeExecutor.execute {
            try { copyStream(input, sock.getOutputStream()) } catch (_: Throwable) {}
            finally { done.countDown() }
        }
        bridgeExecutor.execute {
            try { copyStream(sock.getInputStream(), output) } catch (_: Throwable) {}
            finally { done.countDown() }
        }
        try { done.await() } catch (_: InterruptedException) {}
        try { sock.close() } catch (_: Throwable) {}
        agent.logInfo("tunnel closed")
    }

    private fun copyStream(input: InputStream, output: OutputStream) {
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            output.flush()
        }
    }

    // ── Sticky transport cache (mirrors transport_cache.go) ─────────────

    private fun cacheFile(): File = File(cfg.workDir, ".proxyagent_transport")

    private fun readTransportCache(): String {
        return try {
            val v = cacheFile().readText().trim()
            if (v == "tcp" || v == "quic") v else ""
        } catch (_: Throwable) { "" }
    }

    private fun writeTransportCache(transport: String) {
        try {
            cfg.workDir.mkdirs()
            cacheFile().writeText("$transport\n")
        } catch (_: Throwable) {}
    }

    // ────────────────────────────────────────────────────────────────────
    // Data pool — pre-dialed TCP sockets for fast OPEN response
    // ────────────────────────────────────────────────────────────────────

    internal class DataPool(
        private val uplink: Uplink,
        private val capacity: Int,
        private val creds: RegistratorCreds,
        private val cfg: NativeProxyAgent.Config,
        private val dns: DnsConfig,
    ) {
        private val available = LinkedBlockingDeque<Socket>()
        private val closed = AtomicBoolean(false)
        private var refiller: Thread? = null

        fun start() {
            val t = Thread({ runRefill() }, "uplink-pool-refill").apply { isDaemon = true }
            refiller = t
            t.start()
        }

        fun take(): Socket {
            val cached = available.pollLast()
            if (cached != null) {
                // Wake the refiller — best effort.
                try { refiller?.interrupt() } catch (_: Throwable) {}
                return cached
            }
            // Pool empty → dial on demand (slow path).
            return dialAndHandshake()
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            try { refiller?.interrupt() } catch (_: Throwable) {}
            while (true) {
                val s = available.pollFirst() ?: break
                try { s.close() } catch (_: Throwable) {}
            }
        }

        private fun runRefill() {
            // Pre-fill on start.
            fillOnce()
            while (!closed.get()) {
                try {
                    Thread.sleep(NativeProxyAgent.POOL_REFILL_IDLE_MS)
                } catch (_: InterruptedException) {
                    if (closed.get()) return
                }
                fillOnce()
            }
        }

        private fun fillOnce() {
            while (!closed.get() && available.size < capacity) {
                val s = try { dialAndHandshake() } catch (t: Throwable) {
                    uplink.agent.logDebug("pool refill dial failed",
                        "endpoint" to "${creds.host}:${creds.port}",
                        "error" to (t.message ?: t.javaClass.simpleName))
                    return
                }
                if (closed.get()) {
                    try { s.close() } catch (_: Throwable) {}
                    return
                }
                available.offerLast(s)
            }
        }

        private fun dialAndHandshake(): Socket {
            val s = Socket()
            s.tcpNoDelay = true
            s.keepAlive = true
            try {
                s.connect(
                    InetSocketAddress(dns.resolve(creds.host), creds.port),
                    NativeProxyAgent.POOL_DIAL_TIMEOUT_MS.toInt(),
                )
                s.soTimeout = NativeProxyAgent.POOL_DIAL_TIMEOUT_MS.toInt()
                val out = s.getOutputStream()
                out.write(NativeProxyAgent.WIRE_MAGIC)
                out.write(byteArrayOf(NativeProxyAgent.WIRE_VERSION, NativeProxyAgent.CONN_TYPE_DATA))
                out.flush()
                s.soTimeout = 0
                return s
            } catch (t: Throwable) {
                try { s.close() } catch (_: Throwable) {}
                throw t
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────────
// Daemon thread factory
// ────────────────────────────────────────────────────────────────────────

internal fun daemonFactory(prefix: String): ThreadFactory {
    val counter = AtomicInteger(0)
    return ThreadFactory { r ->
        Thread(r, "$prefix-${counter.incrementAndGet()}").apply { isDaemon = true }
    }
}

// ────────────────────────────────────────────────────────────────────────
// MiniJson — zero-dep JSON for objects/arrays of strings/numbers/booleans.
// Just enough to parse balancer + health responses and emit our control
// messages. NOT a general-purpose JSON library.
// ────────────────────────────────────────────────────────────────────────

internal object MiniJson {

    class JObject(private val map: Map<String, Any?>) {
        fun string(key: String): String? = map[key] as? String
        fun int(key: String): Int? = when (val v = map[key]) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull()
            else -> null
        }
        fun double(key: String): Double? = when (val v = map[key]) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else -> null
        }
        fun bool(key: String): Boolean? = map[key] as? Boolean
        fun has(key: String): Boolean = map.containsKey(key)
    }

    class JArray(private val list: List<JValue>) {
        fun items(): List<JValue> = list
    }

    class JValue(internal val raw: Any?) {
        @Suppress("UNCHECKED_CAST")
        fun asObject(): JObject? = (raw as? Map<String, Any?>)?.let { JObject(it) }
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): JObject {
        val parser = Parser(text)
        parser.skipWhitespace()
        val v = parser.parseValue()
        val map = v as? Map<String, Any?> ?: emptyMap()
        return JObject(map)
    }

    fun parseArray(text: String): JArray {
        val parser = Parser(text)
        parser.skipWhitespace()
        val v = parser.parseValue()
        val list = (v as? List<*>) ?: emptyList<Any?>()
        return JArray(list.map { JValue(it) })
    }

    fun appendString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                else -> if (c.code < 0x20) {
                    sb.append("\\u").append(String.format("%04x", c.code))
                } else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(private val s: String) {
        private var pos = 0

        fun skipWhitespace() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun parseValue(): Any? {
            skipWhitespace()
            if (pos >= s.length) return null
            return when (s[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBool()
                'n' -> parseNull()
                else -> parseNumber()
            }
        }

        private fun parseObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            pos++  // skip {
            skipWhitespace()
            if (pos < s.length && s[pos] == '}') { pos++; return map }
            while (pos < s.length) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                if (pos >= s.length || s[pos] != ':') throw IOException("expected :")
                pos++
                val v = parseValue()
                map[key] = v
                skipWhitespace()
                if (pos < s.length && s[pos] == ',') { pos++; continue }
                if (pos < s.length && s[pos] == '}') { pos++; return map }
                throw IOException("malformed object at $pos")
            }
            throw IOException("unterminated object")
        }

        private fun parseArray(): List<Any?> {
            val list = ArrayList<Any?>()
            pos++  // skip [
            skipWhitespace()
            if (pos < s.length && s[pos] == ']') { pos++; return list }
            while (pos < s.length) {
                list.add(parseValue())
                skipWhitespace()
                if (pos < s.length && s[pos] == ',') { pos++; continue }
                if (pos < s.length && s[pos] == ']') { pos++; return list }
                throw IOException("malformed array at $pos")
            }
            throw IOException("unterminated array")
        }

        private fun parseString(): String {
            if (s[pos] != '"') throw IOException("expected string at $pos")
            pos++
            val sb = StringBuilder()
            while (pos < s.length) {
                val c = s[pos]
                if (c == '"') { pos++; return sb.toString() }
                if (c == '\\') {
                    pos++
                    if (pos >= s.length) throw IOException("truncated escape")
                    when (val e = s[pos]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 5 > s.length) throw IOException("truncated unicode")
                            val hex = s.substring(pos + 1, pos + 5)
                            sb.append(hex.toInt(16).toChar())
                            pos += 4
                        }
                        else -> throw IOException("bad escape \\$e")
                    }
                    pos++
                } else {
                    sb.append(c)
                    pos++
                }
            }
            throw IOException("unterminated string")
        }

        private fun parseBool(): Boolean {
            if (s.startsWith("true", pos)) { pos += 4; return true }
            if (s.startsWith("false", pos)) { pos += 5; return false }
            throw IOException("expected boolean at $pos")
        }

        private fun parseNull(): Any? {
            if (s.startsWith("null", pos)) { pos += 4; return null }
            throw IOException("expected null at $pos")
        }

        private fun parseNumber(): Number {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.' ||
                    s[pos] == 'e' || s[pos] == 'E' || s[pos] == '+' || s[pos] == '-')) {
                pos++
            }
            val token = s.substring(start, pos)
            return if ('.' in token || 'e' in token || 'E' in token) {
                token.toDouble()
            } else {
                token.toLongOrNull() ?: token.toDouble()
            }
        }
    }
}
