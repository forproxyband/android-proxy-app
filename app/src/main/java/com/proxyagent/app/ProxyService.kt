package com.proxyagent.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.system.Os
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProxyService : Service() {

    enum class ConnStatus { STARTING, CONNECTING, CONNECTED, RECONNECTING, ERROR, STOPPED }
    enum class Engine { BINARY, AAR }
    enum class Mode { MODEM, BALANCER }

    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var stopRequested = false
    @Volatile private var agentProcess: Process? = null
    @Volatile private var runnerThread: Thread? = null
    @Volatile private var engine: Engine = Engine.BINARY
    @Volatile private var mode: Mode = Mode.MODEM
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var analytics: AnalyticsRecorder? = null
    @Volatile private var lastNatRefreshMs = 0L

    @Volatile private var connStatus: ConnStatus = ConnStatus.STARTING
    @Volatile private var rxRate = 0L
    @Volatile private var txRate = 0L
    @Volatile private var currentRegistrator = ""
    @Volatile private var activeTunnels = 0
    @Volatile private var connectedSinceMs = 0L
    // Human-readable uplink transport label exposed via conn_info so the
    // UI can show "QUIC" / "TCP (splice)" / "TCP+yamux" / "WebSocket".
    // Filled from the SDK's "uplink connected … transport=quic|tcp" log
    // (v2.0.14-quic+); older builds without the key default to "TCP+yamux"
    // or "WebSocket" depending on which "connected" line variant we saw.
    @Volatile private var currentUplinkTransport: String = ""
    private var lastRx = 0L
    private var lastTx = 0L
    private var lastStatsAt = 0L

    private val regSelectedRe = Regex("""host=(\S+) port=(\d+)""")
    private val wsUrlRe = Regex("""url=wss?://([^/\s"]+)""")
    // New yamux-uplink log line: `msg="uplink dialing" endpoint=host:port`.
    // No URL anymore (transport is plain TCP + yamux), so we read the
    // endpoint key directly. Used to fill currentRegistrator in direct/
    // modem mode where there's no preceding "selected registrator" line.
    private val endpointRe = Regex("""endpoint=([^\s"]+):(\d+)""")
    // `transport=quic|tcp` appears on the post-AUTH "uplink connected" log
    // line and on "uplink dialing" / "transport dial failed" diagnostics.
    // We only act on the post-AUTH one to avoid flipping the badge mid-probe.
    private val transportRe = Regex("""\btransport=(\w+)""")
    private val directRegRe = Regex("""direct registrator configured.*?host=(\S+) port=(\d+)""")
    private val rebootReasonRe = Regex("""reason=(.*)$""")
    @Volatile private var autoCycling = false

    @Synchronized
    private fun log(msg: String) {
        try {
            val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            val logFile = File(filesDir, "agent.log")
            logFile.appendText("$ts $msg\n")
            Log.d("ProxyAgent", msg)
            if (logFile.length() > MAX_LOG_BYTES) trimLog(logFile)
        } catch (_: Exception) {}
    }

    // Keep the last KEEP_LOG_BYTES of agent.log; rotate when it grows past MAX_LOG_BYTES.
    // Whole `log()` is synchronized so no writer races with the trim.
    private fun trimLog(logFile: File) {
        try {
            val len = logFile.length()
            if (len <= KEEP_LOG_BYTES) return
            val skip = len - KEEP_LOG_BYTES
            val tail: ByteArray
            logFile.inputStream().use { input ->
                var skipped = 0L
                while (skipped < skip) {
                    val s = input.skip(skip - skipped)
                    if (s <= 0) break
                    skipped += s
                }
                // Advance to next '\n' so we don't start from mid-line
                while (true) {
                    val b = input.read()
                    if (b < 0 || b == '\n'.code) break
                }
                tail = input.readBytes()
            }
            logFile.writeBytes(tail)
        } catch (_: Throwable) {}
    }

    companion object {
        private const val MAX_LOG_BYTES = 30L * 1024 * 1024  // trigger rotation
        private const val KEEP_LOG_BYTES = 25L * 1024 * 1024 // keep this much tail
    }

    private fun state(s: String) {
        try { File(filesDir, "proxy_state").writeText(s) } catch (_: Exception) {}
    }

    private fun writeConnInfo() {
        try {
            // 7th field (currentUplinkTransport) was added in v2.0.14-quic.
            // Readers must use getOrNull(N) for forward compatibility so the
            // field stays optional if a downgrade ever writes a 6-field file.
            File(filesDir, "conn_info").writeText(
                "${connStatus.name}|$rxRate|$txRate|$currentRegistrator|$activeTunnels|$connectedSinceMs|$currentUplinkTransport"
            )
        } catch (_: Exception) {}
    }

    private fun readBatteryThreshold(): Int = try {
        File(filesDir, "battery_threshold").readText().trim().toIntOrNull() ?: 0
    } catch (_: Throwable) { 0 }

    // System-level check: is there a validated, non-suspended internet path right now?
    // Returns false when the OS has marked the active network as "no internet" — e.g.
    // cellular data exhausted, captive portal, or carrier-side suspension.
    private fun systemSaysInternetUp(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return false
            if (Build.VERSION.SDK_INT >= 28 &&
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)) return false
            true
        } catch (_: Throwable) { true }  // be conservative: assume up on error
    }

    private fun mask(s: String): String {
        if (s.isEmpty()) return "<empty>"
        if (s.length <= 6) return "****"
        return s.substring(0, 3) + "****" + s.substring(s.length - 3)
    }

    // SDK ≥ v2.0.10 dropped WebSocket and now uses plain TCP + yamux on the
    // uplink. Log strings changed too, so we match both old and new wording
    // here — keeping the old keys means older binaries (and the AAR before
    // it gets bumped) keep parsing correctly during the rollout.
    //
    //   old WS                          new yamux uplink
    //   "ws connected"                  "uplink connected"
    //   "tunnel opened"                 "opening tunnel"
    //   "ws dialing"                    "uplink dialing"   (endpoint=host:port)
    //   "ws read error"/"close 1006"/   "uplink accept loop ended" /
    //   "ws close frame"                "uplink control loop ended" /
    //                                   "uplink dial failed" /
    //                                   "uplink yamux init failed" /
    //                                   "uplink AUTH …"
    private fun parseAgentLine(line: String) {
        when {
            line.contains("tunnel opened") || line.contains("opening tunnel") -> {
                activeTunnels++
                analytics?.onTunnelOpen()
            }
            line.contains("tunnel closed") -> {
                activeTunnels = (activeTunnels - 1).coerceAtLeast(0)
                analytics?.onTunnelClose()
            }
            line.contains("ws connected") || line.contains("uplink connected") -> {
                connStatus = ConnStatus.CONNECTED
                connectedSinceMs = System.currentTimeMillis()
                // Detect the underlying uplink transport for the status card.
                //   • v2.0.14-quic+ logs `uplink connected … transport=quic|tcp`
                //   • v2.0.10..v2.0.13 logged the same "uplink connected" line
                //     without the key — always TCP+yamux there.
                //   • pre-v2.0.10 logged "ws connected" with no key — WebSocket.
                currentUplinkTransport = if (line.contains("uplink connected")) {
                    when (transportRe.find(line)?.groupValues?.get(1)?.lowercase(Locale.US)) {
                        "quic" -> "QUIC"
                        "tcp" -> "TCP (splice)"
                        null -> "TCP+yamux"
                        else -> transportRe.find(line)?.groupValues?.get(1)?.uppercase(Locale.US).orEmpty()
                    }
                } else {
                    "WebSocket"
                }
                // Old WS log carried `url=wss://host:port/…`; new uplink log
                // has only `uuid=…`. currentRegistrator on the new path is
                // filled earlier by "selected registrator …", "direct
                // registrator configured", or the "uplink dialing" branch.
                wsUrlRe.find(line)?.let {
                    currentRegistrator = it.groupValues[1]
                    analytics?.setRegistrator(currentRegistrator)
                }
            }
            line.contains("selected") && line.contains("registrator") -> {
                regSelectedRe.find(line)?.let {
                    currentRegistrator = "${it.groupValues[1]}:${it.groupValues[2]}"
                    analytics?.setRegistrator(currentRegistrator)
                }
            }
            line.contains("direct registrator configured") -> {
                directRegRe.find(line)?.let {
                    currentRegistrator = "${it.groupValues[1]}:${it.groupValues[2]}"
                    analytics?.setRegistrator(currentRegistrator)
                }
            }
            line.contains("ws read error") ||
                line.contains("close 1006") ||
                line.contains("ws close frame") ||
                line.contains("uplink dial failed") ||
                line.contains("uplink yamux init failed") ||
                line.contains("uplink control stream open failed") ||
                line.contains("uplink AUTH send failed") ||
                line.contains("uplink AUTH reply read failed") ||
                line.contains("uplink AUTH denied") ||
                line.contains("uplink accept loop ended") ||
                line.contains("uplink control loop ended") -> {
                connStatus = ConnStatus.RECONNECTING
                currentRegistrator = ""
                activeTunnels = 0
                connectedSinceMs = 0L
                currentUplinkTransport = ""
                analytics?.resetActiveTunnels()
            }
            line.contains("balancer selection failed") ||
                line.contains("no registrator available") -> {
                connStatus = ConnStatus.RECONNECTING
                currentRegistrator = ""
                activeTunnels = 0
                connectedSinceMs = 0L
                currentUplinkTransport = ""
                analytics?.resetActiveTunnels()
            }
            line.contains("ws dialing") ||
                line.contains("uplink dialing") ||
                line.contains("balancer request") -> {
                if (connStatus != ConnStatus.CONNECTED) connStatus = ConnStatus.CONNECTING
                // "uplink dialing" carries `endpoint=host:port` — surface
                // that as the registrator address so direct/modem mode
                // shows something useful before any tunnel opens.
                endpointRe.find(line)?.let {
                    currentRegistrator = "${it.groupValues[1]}:${it.groupValues[2]}"
                    analytics?.setRegistrator(currentRegistrator)
                }
            }
            // Server-side REBOOT command. The SDK logs this whenever the
            // registrator pushes REBOOT (over the WS in pre-2.0.10 builds,
            // over the yamux control stream in 2.0.10+), regardless of
            // whether the local-WS relay is enabled. Same behavior as the
            // manual ↻ button: toggle cellular to grab a new carrier IP,
            // then reconnect.
            line.contains("REBOOT received from registrator") -> {
                val reason = rebootReasonRe.find(line)?.groupValues?.get(1).orEmpty().trim()
                triggerAutoIpCycle(reason)
            }
        }
    }

    private fun triggerAutoIpCycle(reason: String) {
        if (autoCycling) {
            log("Auto IP-cycle already in progress; ignoring REBOOT (reason=\"$reason\")")
            return
        }
        if (stopRequested) return
        autoCycling = true
        Thread {
            try {
                log("REBOOT auto-cycle: starting (reason=\"$reason\", engine=${engine.name})")
                // Toggle cellular first. The subprocess will see its WS read
                // error and enter the SDK's internal backoff loop on its own —
                // we don't kill it pre-emptively because that would race our
                // own runner's backoff sleep, restarting it mid-toggle.
                val toggled = IpCycle.toggleMobileNetworkViaRoot() ||
                    IpCycle.toggleMobileNetworkViaSecureSettings(this)
                if (!toggled) {
                    log("REBOOT auto-cycle: cellular toggle unavailable (no root, " +
                        "no WRITE_SECURE_SETTINGS); reconnecting on existing IP")
                } else {
                    log("REBOOT auto-cycle: cellular toggled, waiting for re-attach")
                    // Small grace for the radio to come back before we reconnect.
                    try { Thread.sleep(2000) } catch (_: InterruptedException) {}
                }
                if (!stopRequested) {
                    // For BINARY this kills the subprocess + interrupts our
                    // runner so it re-dials with fresh state on the new IP.
                    // For AAR this is a no-op; the in-process SDK reconnects
                    // on its own after the WS read error from the toggle.
                    forceReconnect("REBOOT auto-cycle")
                    log("REBOOT auto-cycle: reconnect kicked")
                }
            } catch (e: Throwable) {
                log("REBOOT auto-cycle error: ${e.message}")
            } finally {
                autoCycling = false
            }
        }.apply { name = "AutoIpCycler"; isDaemon = true; start() }
    }

    private fun readSpeedUnitsBytes(): Boolean = try {
        File(filesDir, "speed_units").readText().trim() == "bytes"
    } catch (_: Throwable) { false }

    private fun humanRate(bytesPerSec: Long): String {
        if (bytesPerSec < 0) return "—"
        return if (readSpeedUnitsBytes()) {
            when {
                bytesPerSec < 1024 -> "${bytesPerSec}B/s"
                bytesPerSec < 1024 * 1024 -> "${bytesPerSec / 1024}KB/s"
                bytesPerSec < 1024L * 1024 * 1024 -> "%.1fMB/s".format(Locale.US, bytesPerSec / 1024.0 / 1024.0)
                else -> "%.1fGB/s".format(Locale.US, bytesPerSec / 1024.0 / 1024.0 / 1024.0)
            }
        } else {
            val bits = bytesPerSec * 8
            when {
                bits < 1000 -> "${bits}b/s"
                bits < 1000 * 1000 -> "${bits / 1000}Kb/s"
                bits < 1000L * 1000 * 1000 -> "%.1fMb/s".format(Locale.US, bits / 1000.0 / 1000.0)
                else -> "%.1fGb/s".format(Locale.US, bits / 1000.0 / 1000.0 / 1000.0)
            }
        }
    }

    private fun statusText(): String {
        val base = when (connStatus) {
            ConnStatus.STARTING -> "Starting…"
            ConnStatus.CONNECTING -> "Connecting…"
            ConnStatus.CONNECTED ->
                if (currentRegistrator.isNotEmpty()) "Connected · $currentRegistrator"
                else "Connected"
            ConnStatus.RECONNECTING -> "Reconnecting…"
            ConnStatus.ERROR -> "Error"
            ConnStatus.STOPPED -> "Stopped"
        }
        return if (connStatus == ConnStatus.CONNECTED)
            "$base · ↓${humanRate(rxRate)} ↑${humanRate(txRate)}"
        else base
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, "proxy")
            .setContentTitle("Proxy Agent")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(0, "Stop", PendingIntent.getService(this, 0,
                Intent(this, ProxyService::class.java).apply { action = "STOP" },
                PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true).build()

    // Public IP refresh runs in-service so analytics buckets always have a
    // best-known NAT value, even if the UI never opened. Throttled to once
    // every 5 minutes — public IP shouldn't change unless we cycle.
    private fun maybeRefreshNatIp() {
        val now = System.currentTimeMillis()
        if (now - lastNatRefreshMs < 5 * 60_000L) return
        lastNatRefreshMs = now
        Thread {
            try {
                for (url in listOf("https://api.ipify.org", "https://icanhazip.com")) {
                    try {
                        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                            connectTimeout = 4000
                            readTimeout = 4000
                            requestMethod = "GET"
                            setRequestProperty("User-Agent", "ProxyAgent-Android")
                        }
                        val ip = conn.inputStream.bufferedReader().use { it.readText().trim() }
                        conn.disconnect()
                        if (ip.isNotEmpty() && ip.length < 40 &&
                            (ip.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) || ip.contains(":"))) {
                            try { File(filesDir, "nat_ip").writeText(ip) } catch (_: Throwable) {}
                            analytics?.setNatIp(ip)
                            return@Thread
                        }
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        }.apply { isDaemon = true; name = "SvcNatIpFetch"; start() }
    }

    private fun refreshTrafficStats() {
        val uid = android.os.Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        if (rx == TrafficStats.UNSUPPORTED.toLong()) {
            rxRate = -1; txRate = -1; return
        }
        val now = System.currentTimeMillis()
        if (lastStatsAt > 0) {
            val dtMs = (now - lastStatsAt).coerceAtLeast(1)
            rxRate = ((rx - lastRx) * 1000 / dtMs).coerceAtLeast(0)
            txRate = ((tx - lastTx) * 1000 / dtMs).coerceAtLeast(0)
        }
        lastRx = rx; lastTx = tx; lastStatsAt = now
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel("proxy", "Proxy Agent", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            doStop(); return START_NOT_STICKY
        }
        if (intent == null) { stopSelf(); return START_NOT_STICKY }

        val host = intent.getStringExtra("host") ?: ""
        val port = intent.getStringExtra("port") ?: ""
        val key = intent.getStringExtra("key") ?: ""
        val agentId = intent.getStringExtra("id")?.trim().orEmpty()
        val dnsRaw = intent.getStringExtra("dns")?.trim().orEmpty()
        val dns = dnsRaw.ifEmpty { "1.1.1.1,8.8.8.8" }
        engine = if (intent.getStringExtra("engine") == "aar") Engine.AAR else Engine.BINARY
        mode = if (intent.getStringExtra("mode") == "balancer") Mode.BALANCER else Mode.MODEM
        if (host.isEmpty()) { stopSelf(); return START_NOT_STICKY }

        connStatus = ConnStatus.STARTING
        // Android 14+ ties FGS time-limits to the type. specialUse has no
        // 6h/24h dataSync cap, but the system silently treats absent type as
        // "unknown" and time-limits it too. Pass type explicitly.
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, buildNotification(statusText()),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, buildNotification(statusText()))
        }
        state("starting")
        writeConnInfo()

        // Spin up the analytics recorder before any agent log lines arrive so
        // tunnel-open/close events are counted from the very first connection.
        analytics = AnalyticsRecorder(this)

        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Proxy::WL")
            wakeLock?.acquire()
        } catch (_: Exception) {}

        // Status + speed updater: polls TrafficStats and battery once per second,
        // refreshes notification + conn_info file, and enforces battery /
        // no-internet auto-stops.
        Thread {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            var noInternetSince = 0L
            val noInternetGraceMs = 30_000L
            while (!stopRequested) {
                try {
                    refreshTrafficStats()
                    nm.notify(1, buildNotification(statusText()))
                    writeConnInfo()
                    analytics?.tick()
                    maybeRefreshNatIp()

                    val threshold = readBatteryThreshold()
                    if (threshold > 0) {
                        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                        if (level in 0..threshold) {
                            log("Auto-stop: battery $level% <= threshold $threshold%")
                            doStop("Battery $level% ≤ $threshold% — auto-stopped")
                            break
                        }
                    }

                    // System-level "no internet" detection. Stops the agent if OS
                    // reports no validated internet for `noInternetGraceMs`, instead
                    // of burning CPU/battery in the subprocess' dial loop.
                    val now = System.currentTimeMillis()
                    if (!systemSaysInternetUp()) {
                        if (noInternetSince == 0L) noInternetSince = now
                        if (now - noInternetSince >= noInternetGraceMs) {
                            val secs = (now - noInternetSince) / 1000
                            log("Auto-stop: system reports no internet for ${secs}s")
                            doStop("No internet (${secs}s) — auto-stopped")
                            break
                        }
                    } else {
                        noInternetSince = 0L
                    }
                } catch (_: Throwable) {}
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            }
        }.apply { name = "StatusUpdater"; isDaemon = true; start() }

        log("Engine: ${engine.name}  Mode: ${mode.name}")
        val runner = when (engine) {
            Engine.BINARY -> Thread { runBinaryEngine(host, port, key, agentId, dns) }
            Engine.AAR -> Thread { runAarEngine(host, port, key, agentId, dns) }
        }
        runner.name = "AgentRunner"
        runner.isDaemon = true
        runnerThread = runner
        runner.start()

        registerNetworkCallback()
        return START_REDELIVER_INTENT
    }

    private fun runBinaryEngine(host: String, port: String, key: String, agentId: String, dns: String) {
        val binary = File(applicationInfo.nativeLibraryDir, "libproxyagent.so")
        if (!binary.exists()) {
            log("ERROR: libproxyagent.so missing at ${binary.absolutePath}")
            connStatus = ConnStatus.ERROR
            state("error"); writeConnInfo()
            return
        }
        try { binary.setExecutable(true, false) } catch (_: Throwable) {}
        log("Binary: ${binary.absolutePath} size=${binary.length()}")

        var backoffMs = 1000L
        while (!stopRequested) {
            try {
                log("Launching subprocess: mode=${mode.name} host=$host port=$port key=${mask(key)} id=${if (agentId.isEmpty()) "<empty>" else mask(agentId)} dns=$dns")
                connStatus = ConnStatus.CONNECTING
                val pb = ProcessBuilder(binary.absolutePath).redirectErrorStream(true)
                pb.environment().apply {
                    put("agent_key", key)
                    put("enable_netagent", "true")
                    put("HOME", filesDir.absolutePath)
                    put("TMPDIR", cacheDir.absolutePath)
                    put("dns_servers", dns)
                    when (mode) {
                        Mode.MODEM -> {
                            // Direct registrator: SDK skips balancer/fallback when both
                            // registrator_host AND registrator_port are set.
                            put("registrator_host", host)
                            put("registrator_port", port)
                            if (agentId.isNotEmpty()) put("agent_uuid", agentId)
                        }
                        Mode.BALANCER -> {
                            put("balancer_host", host)
                            put("balancer_port", port)
                            put("fallback_file_url",
                                "https://s3.eu-central-1.amazonaws.com/cactusneedles/registrators.json")
                        }
                    }
                }
                val proc = pb.start()
                agentProcess = proc
                state("running")
                log("Subprocess started")

                val reader = proc.inputStream.bufferedReader()
                while (true) {
                    val line = reader.readLine() ?: break
                    parseAgentLine(line)
                    log("[agent] $line")
                }
                val code = proc.waitFor()
                agentProcess = null
                log("Subprocess exited code=$code")
                if (stopRequested) break
                connStatus = ConnStatus.RECONNECTING
                currentRegistrator = ""
                activeTunnels = 0
                connectedSinceMs = 0L
                currentUplinkTransport = ""
                backoffMs = 1000L
            } catch (e: Throwable) {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                log("Subprocess error: $sw")
                if (stopRequested) break
                connStatus = ConnStatus.RECONNECTING
                currentRegistrator = ""
                activeTunnels = 0
                connectedSinceMs = 0L
                currentUplinkTransport = ""
            }
            log("Restarting in ${backoffMs}ms")
            try {
                Thread.sleep(backoffMs)
            } catch (_: InterruptedException) {
                // Woken up by forceReconnect (network change). Skip backoff.
                log("Backoff interrupted; retrying now")
                backoffMs = 1000L
            }
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
        log("Runner loop exited")
    }

    // In-process engine using the gomobile AAR (proxyagent.sdk.agent.Agent).
    //
    // Go's runtime caches env into runtime.envs at JNI_OnLoad time and never
    // re-reads it afterwards. libc's setenv() does NOT update that cache, so
    // any config we want Go to see must be in libc's environ BEFORE
    // libgojni.so is loaded.
    //
    // To prevent ART from eagerly resolving go.Seq / Agent during method
    // verification (which would load the .so before our setenv runs), we
    // pull those classes via Class.forName AFTER setenv. This pushes the
    // System.loadLibrary("gojni") call past our environment setup.
    //
    // On stop we kill the :proxy process so the next start re-initializes
    // with fresh env.
    private fun runAarEngine(host: String, port: String, key: String, agentId: String, dns: String) {
        try {
            log("Capturing native stdout/stderr…")
            captureNativeOutput()

            log("Setting environment: mode=${mode.name} host=$host port=$port key=${mask(key)} id=${if (agentId.isEmpty()) "<empty>" else mask(agentId)} dns=$dns")
            // The SDK's Go config helper checks both lowercase ("balancer_host")
            // and SCREAMING_SNAKE ("BALANCER_HOST") names — set both so we
            // don't depend on the SDK's casing convention.
            fun setBoth(name: String, value: String) {
                Os.setenv(name, value, true)
                Os.setenv(name.uppercase(Locale.ROOT), value, true)
            }
            setBoth("agent_key", key)
            setBoth("enable_netagent", "true")
            setBoth("dns_servers", dns)
            Os.setenv("HOME", filesDir.absolutePath, true)
            Os.setenv("TMPDIR", cacheDir.absolutePath, true)

            when (mode) {
                Mode.MODEM -> {
                    // Direct registrator: SDK has no Java setRegistrator helper,
                    // so we rely on env vars (config.FromEnvAndFlags reads
                    // registrator_host/REGISTRATOR_HOST). Set BEFORE Go runtime
                    // initializes via Class.forName("go.Seq") below.
                    setBoth("registrator_host", host)
                    setBoth("registrator_port", port)
                    if (agentId.isNotEmpty()) setBoth("agent_uuid", agentId)
                }
                Mode.BALANCER -> {
                    setBoth("balancer_host", host)
                    setBoth("balancer_port", port)
                    setBoth("fallback_file_url",
                        "https://s3.eu-central-1.amazonaws.com/cactusneedles/registrators.json")
                }
            }

            connStatus = ConnStatus.CONNECTING

            // Diagnostic: dump what libc reports back, to confirm setenv stuck
            // in the current process. Shorter form so the agent_key isn't logged.
            try {
                val k = Os.getenv("agent_key") ?: "<null>"
                val keyMsg = "agent_key=${if (k == "<null>") k else "set(${k.length}b)"}"
                when (mode) {
                    Mode.MODEM -> {
                        val rh = Os.getenv("registrator_host") ?: "<null>"
                        val rp = Os.getenv("registrator_port") ?: "<null>"
                        val uu = Os.getenv("agent_uuid") ?: "<null>"
                        log("libc env check: registrator_host=$rh registrator_port=$rp agent_uuid=${if (uu == "<null>") uu else "set(${uu.length}b)"} $keyMsg")
                    }
                    Mode.BALANCER -> {
                        val h = Os.getenv("balancer_host") ?: "<null>"
                        val p = Os.getenv("balancer_port") ?: "<null>"
                        log("libc env check: balancer_host=$h balancer_port=$p $keyMsg")
                    }
                }
            } catch (_: Throwable) {}

            log("Loading Go runtime via Class.forName(\"go.Seq\")…")
            val seqClass = Class.forName("go.Seq")
            seqClass.getMethod("setContext", android.content.Context::class.java)
                .invoke(null, applicationContext)

            log("Loading Agent class via Class.forName…")
            val agentClass = Class.forName("proxyagent.sdk.agent.Agent")

            // Newer SDKs expose Java setters that call Go's os.Setenv internally
            // — that's the only way to get values into runtime.envs from JNI.
            // Fall back to setenv-only on older AARs that don't have them.
            var sdkSettersOk = true
            fun callSetter(name: String, argTypes: Array<Class<*>>, vararg args: Any?) {
                try {
                    agentClass.getMethod(name, *argTypes).invoke(null, *args)
                } catch (t: NoSuchMethodException) {
                    sdkSettersOk = false
                    log("Agent.$name not found — falling back to libc setenv only")
                } catch (t: Throwable) {
                    sdkSettersOk = false
                    log("Agent.$name error: ${t.message}")
                }
            }
            val portLong = port.toLongOrNull() ?: 0L
            callSetter("setAgentKey",
                arrayOf<Class<*>>(String::class.java), key)
            callSetter("setEnableNetAgent",
                arrayOf<Class<*>>(Boolean::class.javaPrimitiveType!!), true)
            if (mode == Mode.BALANCER) {
                callSetter("setBalancer",
                    arrayOf<Class<*>>(String::class.java, Long::class.javaPrimitiveType!!),
                    host, portLong)
                callSetter("setFallbackURL",
                    arrayOf<Class<*>>(String::class.java),
                    "https://s3.eu-central-1.amazonaws.com/cactusneedles/registrators.json")
            }
            // For modem mode, registrator_host/port/agent_uuid go via env only —
            // the SDK has no Java setRegistrator/setAgentUUID at this AAR version.
            log("SDK setters ${if (sdkSettersOk) "applied" else "partial — some fell back to env"}")

            try {
                agentClass.getMethod("setDNSServers", String::class.java).invoke(null, dns)
                log("Agent.setDNSServers applied")
            } catch (t: Throwable) {
                log("Agent.setDNSServers unavailable: ${t.message}")
            }

            log("Calling Agent.startAgent()")
            agentClass.getMethod("startAgent").invoke(null)
            state("running")
            log("Agent.startAgent returned")
        } catch (e: Throwable) {
            val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
            log("AAR engine error: $sw")
            connStatus = ConnStatus.ERROR
            state("error"); writeConnInfo()
        }
    }

    // Pipe Go's stdout/stderr (fd 1/2) into our log so log parsing works.
    // Also tail logcat for tags gomobile typically writes to.
    private fun captureNativeOutput() {
        try {
            val fds = Os.pipe()
            val readFd = fds[0]
            val writeFd = fds[1]
            Os.dup2(writeFd, 1)
            Os.dup2(writeFd, 2)
            Os.close(writeFd)
            Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(FileInputStream(readFd)))
                    while (true) {
                        val line = reader.readLine() ?: break
                        parseAgentLine(line)
                        log("[go] $line")
                    }
                } catch (_: Throwable) {}
            }.apply { name = "NativeStdoutReader"; isDaemon = true; start() }
        } catch (e: Throwable) {
            log("stdout capture failed: ${e.message}")
        }

        try {
            val proc = ProcessBuilder(
                "logcat", "-T", "1", "-v", "time",
                "GoLog:V", "Go:V", "*:S"
            ).redirectErrorStream(true).start()
            Thread {
                try {
                    val reader = proc.inputStream.bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        parseAgentLine(line)
                        log("[logcat] $line")
                    }
                } catch (_: Throwable) {}
            }.apply { name = "LogcatTailer"; isDaemon = true; start() }
        } catch (e: Throwable) {
            log("logcat tail failed: ${e.message}")
        }
    }

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                private var lastNet: Network? = null
                override fun onAvailable(network: Network) {
                    val prev = lastNet
                    lastNet = network
                    if (prev != null && prev != network) {
                        forceReconnect("network changed: $prev → $network")
                    } else {
                        log("Network available: $network")
                    }
                }
                override fun onLost(network: Network) {
                    log("Network lost: $network")
                    if (lastNet == network) lastNet = null
                }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    // Fires on every small change; we rely on onAvailable for actual switches.
                }
            }
            networkCallback = cb
            if (Build.VERSION.SDK_INT >= 24) {
                cm.registerDefaultNetworkCallback(cb)
            } else {
                val req = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(req, cb)
            }
        } catch (e: Throwable) {
            log("Network callback register failed: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            networkCallback?.let { cm.unregisterNetworkCallback(it) }
        } catch (_: Throwable) {}
        networkCallback = null
    }

    // Immediately tears down the current WS connection and wakes the backoff so a
    // fresh TCP socket is established on the new interface. Called when the default
    // network swaps (WiFi ↔ cellular, or AP change) — the old socket is bound to an
    // IP that's no longer valid.
    private fun forceReconnect(reason: String) {
        log("Force reconnect: $reason")
        when (engine) {
            Engine.BINARY -> {
                try { agentProcess?.destroy() } catch (_: Throwable) {}
                runnerThread?.interrupt()
            }
            Engine.AAR -> {
                // AAR engine handles network changes internally via the Go-side
                // dial loop; nothing to tear down here.
            }
        }
    }

    private fun doStop(autoStopReason: String = "") {
        if (stopRequested) return
        stopRequested = true
        unregisterNetworkCallback()
        try { analytics?.flush() } catch (_: Throwable) {}
        when (engine) {
            Engine.BINARY -> try {
                agentProcess?.let { p ->
                    log("Terminating subprocess")
                    p.destroy()
                    try {
                        if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                            log("Subprocess did not exit gracefully; forcing")
                            p.destroyForcibly()
                        }
                    } catch (_: InterruptedException) { p.destroyForcibly() }
                }
            } catch (t: Throwable) { log("Stop error: ${t.message}") }
            Engine.AAR -> try {
                log("Calling Agent.stopAgent()")
                Class.forName("proxyagent.sdk.agent.Agent")
                    .getMethod("stopAgent").invoke(null)
            } catch (t: Throwable) { log("Agent.stopAgent error: ${t.message}") }
        }
        agentProcess = null
        connStatus = ConnStatus.STOPPED
        currentRegistrator = ""
        activeTunnels = 0
        connectedSinceMs = 0L
        currentUplinkTransport = ""
        state(if (autoStopReason.isNotEmpty()) "auto_stopped" else "stopped")
        try {
            if (autoStopReason.isNotEmpty())
                File(filesDir, "stop_reason").writeText(autoStopReason)
            else
                File(filesDir, "stop_reason").delete()
        } catch (_: Throwable) {}
        writeConnInfo()
        wakeLock?.let { if (it.isHeld) it.release() }
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
        if (autoStopReason.isNotEmpty()) {
            try {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val n = NotificationCompat.Builder(this, "proxy")
                    .setContentTitle("Proxy Agent stopped")
                    .setContentText(autoStopReason)
                    .setSmallIcon(android.R.drawable.ic_menu_share)
                    .setAutoCancel(true)
                    .setOngoing(false)
                    .setContentIntent(PendingIntent.getActivity(this, 0,
                        Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
                    .build()
                nm.notify(2, n)
            } catch (_: Throwable) {}
        }
        stopSelf()

        // libc setenv does not update Go's cached env after runtime init, so the
        // AAR engine cannot be cleanly restarted in the same process. Killing
        // the :proxy process forces the next Start to load a fresh Go runtime
        // with the new env. The binary engine re-execs the subprocess instead,
        // so it doesn't need this.
        if (engine == Engine.AAR) {
            Thread {
                try { Thread.sleep(400) } catch (_: InterruptedException) {}
                android.os.Process.killProcess(android.os.Process.myPid())
            }.apply { name = "AarProcKiller"; isDaemon = true; start() }
        }
    }

    override fun onDestroy() { doStop(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
