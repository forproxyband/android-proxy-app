package com.proxyagent.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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

    @Volatile private var connStatus: ConnStatus = ConnStatus.STARTING
    @Volatile private var rxRate = 0L
    @Volatile private var txRate = 0L
    @Volatile private var currentRegistrator = ""
    @Volatile private var activeTunnels = 0
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
        private const val MAX_LOG_BYTES = 10L * 1024 * 1024  // trigger rotation
        private const val KEEP_LOG_BYTES = 8L * 1024 * 1024  // keep this much tail
    }

    private fun state(s: String) {
        try { File(filesDir, "proxy_state").writeText(s) } catch (_: Exception) {}
    }

    private fun writeConnInfo() {
        try {
            File(filesDir, "conn_info")
                .writeText("${connStatus.name}|$rxRate|$txRate|$currentRegistrator|$activeTunnels")
        } catch (_: Exception) {}
    }

    private fun readBatteryThreshold(): Int = try {
        File(filesDir, "battery_threshold").readText().trim().toIntOrNull() ?: 0
    } catch (_: Throwable) { 0 }

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
            }
            line.contains("balancer selection failed") ||
                line.contains("no registrator available") -> {
                connStatus = ConnStatus.RECONNECTING
                currentRegistrator = ""
                activeTunnels = 0
            }
            line.contains("ws dialing") ||
                line.contains("balancer request") -> {
                if (connStatus != ConnStatus.CONNECTED) connStatus = ConnStatus.CONNECTING
            }
        }
    }

    private fun humanRate(bps: Long): String = when {
        bps < 0 -> "—"
        bps < 1024 -> "${bps}B/s"
        bps < 1024 * 1024 -> "${bps / 1024}KB/s"
        bps < 1024L * 1024 * 1024 -> "%.1fMB/s".format(Locale.US, bps / 1024.0 / 1024.0)
        else -> "%.1fGB/s".format(Locale.US, bps / 1024.0 / 1024.0 / 1024.0)
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
        // refreshes notification + conn_info file, and enforces battery auto-stop.
        Thread {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
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
                    log("Launching subprocess: host=$host port=$port key=${mask(key)}")
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
                    backoffMs = 1000L
                } catch (e: Throwable) {
                    val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                    log("Subprocess error: $sw")
                    if (stopRequested) break
                    connStatus = ConnStatus.RECONNECTING
                    currentRegistrator = ""
                    activeTunnels = 0
                }
                log("Restarting in ${backoffMs}ms")
                try { Thread.sleep(backoffMs) } catch (_: InterruptedException) { break }
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            }
            log("Runner loop exited")
        }.apply { name = "AgentRunner"; isDaemon = true; start() }

        return START_REDELIVER_INTENT
    }

    private fun doStop(autoStopReason: String = "") {
        if (stopRequested) return
        stopRequested = true
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
        state(if (autoStopReason.isNotEmpty()) "auto_stopped" else "stopped")
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
