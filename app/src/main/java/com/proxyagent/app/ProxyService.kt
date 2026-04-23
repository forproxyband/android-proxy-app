package com.proxyagent.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
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

    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var stopRequested = false
    @Volatile private var agentProcess: Process? = null

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

    private fun mask(s: String): String {
        if (s.isEmpty()) return "<empty>"
        if (s.length <= 6) return "****"
        return s.substring(0, 3) + "****" + s.substring(s.length - 3)
    }

    override fun onCreate() {
        super.onCreate()
        val ch = NotificationChannel("proxy", "Proxy Agent", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
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

        val notif = NotificationCompat.Builder(this, "proxy")
            .setContentTitle("Proxy Agent")
            .setContentText("Starting...")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(0, "Stop", PendingIntent.getService(this, 0,
                Intent(this, ProxyService::class.java).apply { action = "STOP" },
                PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true).build()

        startForeground(1, notif)
        state("starting")

        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Proxy::WL")
            wakeLock?.acquire()
        } catch (_: Exception) {}

        Thread {
            try {
                val binary = File(applicationInfo.nativeLibraryDir, "libproxyagent.so")
                log("Binary path: ${binary.absolutePath}")
                log("Binary exists=${binary.exists()} executable=${binary.canExecute()} size=${if (binary.exists()) binary.length() else -1}")

                if (!binary.exists()) {
                    log("ERROR: libproxyagent.so missing — check that arm64-v8a lib is bundled and android:extractNativeLibs=true")
                    state("error")
                    return@Thread
                }

                try { binary.setExecutable(true, false) } catch (_: Throwable) {}

                log("Launching subprocess: host=$host port=$port key=${mask(key)}")
                val pb = ProcessBuilder(binary.absolutePath)
                    .redirectErrorStream(true)
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

                val n = NotificationCompat.Builder(this@ProxyService, "proxy")
                    .setContentTitle("Proxy Agent")
                    .setContentText("Running — see logs")
                    .setSmallIcon(android.R.drawable.ic_menu_share)
                    .setContentIntent(PendingIntent.getActivity(this@ProxyService, 0,
                        Intent(this@ProxyService, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
                    .addAction(0, "Stop", PendingIntent.getService(this@ProxyService, 0,
                        Intent(this@ProxyService, ProxyService::class.java).apply { action = "STOP" },
                        PendingIntent.FLAG_IMMUTABLE))
                    .setOngoing(true).build()
                getSystemService(NotificationManager::class.java).notify(1, n)

                val reader = proc.inputStream.bufferedReader()
                while (true) {
                    val line = reader.readLine() ?: break
                    log("[agent] $line")
                }
                val code = proc.waitFor()
                log("Subprocess exited code=$code")
                if (!stopRequested) state("error")
            } catch (e: Throwable) {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                log("ERROR: $sw")
                state("error")
            }
        }.apply { name = "AgentRunner"; isDaemon = true; start() }

        return START_NOT_STICKY
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
        state("stopped")
        wakeLock?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    override fun onDestroy() { doStop(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
