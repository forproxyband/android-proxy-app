package com.proxyagent.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.TrafficStats
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
    private var lastRx = 0L
    private var lastTx = 0L
    private var lastStatsAt = 0L

    private fun log(msg: String) {
        try {
            val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            File(filesDir, "agent.log").appendText("$ts $msg\n")
            Log.d("ProxyAgent", msg)
        } catch (_: Exception) {}
    }

    private fun state(s: String) {
        try { File(filesDir, "proxy_state").writeText(s) } catch (_: Exception) {}
    }

    private fun writeConnInfo() {
        try {
            File(filesDir, "conn_info").writeText("${connStatus.name}|$rxRate|$txRate")
        } catch (_: Exception) {}
    }

    private fun mask(s: String): String {
        if (s.isEmpty()) return "<empty>"
        if (s.length <= 6) return "****"
        return s.substring(0, 3) + "****" + s.substring(s.length - 3)
    }

    private fun parseAgentLine(line: String) {
        when {
            line.contains("ws connected") -> connStatus = ConnStatus.CONNECTED
            line.contains("ws read error") ||
                line.contains("close 1006") ||
                line.contains("ws close frame") -> connStatus = ConnStatus.RECONNECTING
            line.contains("balancer selection failed") ||
                line.contains("no registrator available") -> connStatus = ConnStatus.RECONNECTING
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
            ConnStatus.CONNECTED -> "Connected"
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

        // Status + speed updater: polls TrafficStats once per second and refreshes notification + conn_info file.
        Thread {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            while (!stopRequested) {
                try {
                    refreshTrafficStats()
                    nm.notify(1, buildNotification(statusText()))
                    writeConnInfo()
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
                    backoffMs = 1000L
                } catch (e: Throwable) {
                    val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                    log("Subprocess error: $sw")
                    if (stopRequested) break
                    connStatus = ConnStatus.RECONNECTING
                }
                log("Restarting in ${backoffMs}ms")
                try { Thread.sleep(backoffMs) } catch (_: InterruptedException) { break }
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            }
            log("Runner loop exited")
        }.apply { name = "AgentRunner"; isDaemon = true; start() }

        return START_REDELIVER_INTENT
    }

    private fun doStop() {
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
        state("stopped"); writeConnInfo()
        wakeLock?.let { if (it.isHeld) it.release() }
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() { doStop(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
