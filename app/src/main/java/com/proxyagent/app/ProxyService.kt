package com.proxyagent.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.system.Os
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
                log("Setting environment...")
                Os.setenv("balancer_host", host, true)
                Os.setenv("balancer_port", port, true)
                Os.setenv("agent_key", key, true)
                Os.setenv("enable_netagent", "true", true)
                Os.setenv("log_level", "TRACE", true)
                Os.setenv("fallback_file_url",
                    "https://s3.eu-central-1.amazonaws.com/cactusneedles/registrators.json", true)
                log("Environment OK")

                log("Initializing Go runtime...")
                go.Seq.setContext(applicationContext)
                log("Go runtime OK")

                log("Starting agent...")
                proxyagent.sdk.agent.Agent.startAgent()
                log("CONNECTED to $host:$port")

                state("running")

                // Update notification
                val n = NotificationCompat.Builder(this@ProxyService, "proxy")
                    .setContentTitle("Proxy Agent")
                    .setContentText("Connected to pool")
                    .setSmallIcon(android.R.drawable.ic_menu_share)
                    .setContentIntent(PendingIntent.getActivity(this@ProxyService, 0,
                        Intent(this@ProxyService, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
                    .addAction(0, "Stop", PendingIntent.getService(this@ProxyService, 0,
                        Intent(this@ProxyService, ProxyService::class.java).apply { action = "STOP" },
                        PendingIntent.FLAG_IMMUTABLE))
                    .setOngoing(true).build()
                getSystemService(NotificationManager::class.java).notify(1, n)

            } catch (e: Throwable) {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                log("ERROR: $sw")
                state("error")
            }
        }.apply { name = "GoAgent"; isDaemon = true; start() }

        return START_NOT_STICKY
    }

    private fun doStop() {
        try { proxyagent.sdk.agent.Agent.stopAgent(); log("Stopped") }
        catch (_: Throwable) {}
        state("stopped")
        wakeLock?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    override fun onDestroy() { doStop(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
