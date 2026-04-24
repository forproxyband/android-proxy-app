package com.proxyagent.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProxyService : Service() {

    enum class ConnStatus { STARTING, CONNECTING, CONNECTED, RECONNECTING, ERROR, STOPPED }

    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var stopRequested = false
    @Volatile private var agentProcess: Process? = null
    @Volatile private var runnerThread: Thread? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile private var connStatus: ConnStatus = ConnStatus.STARTING
    @Volatile private var rxRate = 0L
    @Volatile private var txRate = 0L
    @Volatile private var currentRegistrator = ""
    @Volatile private var activeTunnels = 0
    @Volatile private var connectedSinceMs = 0L
    private var lastRx = 0L
    private var lastTx = 0L
    private var lastStatsAt = 0L

    private val regSelectedRe = Regex("""host=(\S+) port=(\d+)""")
    private val wsUrlRe = Regex("""url=wss?://([^/\s"]+)""")

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
            File(filesDir, "conn_info").writeText(
                "${connStatus.name}|$rxRate|$txRate|$currentRegistrator|$activeTunnels|$connectedSinceMs"
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

    private fun parseAgentLine(line: String) {
        when {
            line.contains("tunnel opened") -> activeTunnels++
            line.contains("tunnel closed") -> activeTunnels = (activeTunnels - 1).coerceAtLeast(0)
            line.contains("ws connected") -> {
                connStatus = ConnStatus.CONNECTED
                connectedSinceMs = System.currentTimeMillis()
                wsUrlRe.find(line)?.let { currentRegistrator = it.groupValues[1] }
            }
            line.contains("selected") && line.contains("registrator") -> {
                regSelectedRe.find(line)?.let {
                    currentRegistrator = "${it.groupValues[1]}:${it.groupValues[2]}"
                }
            }
            line.contains("ws read error") ||
                line.contains("close 1006") ||
                line.contains("ws close frame") -> {
                connStatus = ConnStatus.RECONNECTING
                currentRegistrator = ""
                activeTunnels = 0
                connectedSinceMs = 0L
            }
            line.contains("balancer selection failed") ||
                line.contains("no registrator available") -> {
                connStatus = ConnStatus.RECONNECTING
                currentRegistrator = ""
                activeTunnels = 0
                connectedSinceMs = 0L
            }
            line.contains("ws dialing") ||
                line.contains("balancer request") -> {
                if (connStatus != ConnStatus.CONNECTED) connStatus = ConnStatus.CONNECTING
            }
        }
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
        val dnsRaw = intent.getStringExtra("dns")?.trim().orEmpty()
        val dns = dnsRaw.ifEmpty { "1.1.1.1,8.8.8.8" }
        if (host.isEmpty()) { stopSelf(); return START_NOT_STICKY }

        connStatus = ConnStatus.STARTING
        startForeground(1, buildNotification(statusText()))
        state("starting")
        writeConnInfo()

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

        Thread {
            val binary = File(applicationInfo.nativeLibraryDir, "libproxyagent.so")
            if (!binary.exists()) {
                log("ERROR: libproxyagent.so missing at ${binary.absolutePath}")
                connStatus = ConnStatus.ERROR
                state("error"); writeConnInfo()
                return@Thread
            }
            try { binary.setExecutable(true, false) } catch (_: Throwable) {}
            log("Binary: ${binary.absolutePath} size=${binary.length()}")

            var backoffMs = 1000L
            while (!stopRequested) {
                try {
                    log("Launching subprocess: host=$host port=$port key=${mask(key)} dns=$dns")
                    connStatus = ConnStatus.CONNECTING
                    val pb = ProcessBuilder(binary.absolutePath).redirectErrorStream(true)
                    pb.environment().apply {
                        put("balancer_host", host)
                        put("balancer_port", port)
                        put("agent_key", key)
                        put("enable_netagent", "true")
                        put("fallback_file_url",
                            "https://s3.eu-central-1.amazonaws.com/cactusneedles/registrators.json")
                        put("HOME", filesDir.absolutePath)
                        put("TMPDIR", cacheDir.absolutePath)
                        put("dns_servers", dns)
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
                    backoffMs = 1000L
                } catch (e: Throwable) {
                    val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                    log("Subprocess error: $sw")
                    if (stopRequested) break
                    connStatus = ConnStatus.RECONNECTING
                    currentRegistrator = ""
                    activeTunnels = 0
                    connectedSinceMs = 0L
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
        }.also {
            it.name = "AgentRunner"; it.isDaemon = true
            runnerThread = it
            it.start()
        }

        registerNetworkCallback()
        return START_REDELIVER_INTENT
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
        try { agentProcess?.destroy() } catch (_: Throwable) {}
        runnerThread?.interrupt()
    }

    private fun doStop(autoStopReason: String = "") {
        if (stopRequested) return
        stopRequested = true
        unregisterNetworkCallback()
        try {
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
        agentProcess = null
        connStatus = ConnStatus.STOPPED
        currentRegistrator = ""
        activeTunnels = 0
        connectedSinceMs = 0L
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
    }

    override fun onDestroy() { doStop(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
