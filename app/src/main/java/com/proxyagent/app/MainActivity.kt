package com.proxyagent.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var etHost: EditText
    private lateinit var etPort: EditText
    private lateinit var etKey: EditText
    private lateinit var btnStart: Button
    private lateinit var btnBattery: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvLogs: TextView
    private lateinit var svLogs: ScrollView

    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etHost = findViewById(R.id.etHost)
        etPort = findViewById(R.id.etPort)
        etKey = findViewById(R.id.etKey)
        btnStart = findViewById(R.id.btnToggle)
        btnBattery = findViewById(R.id.btnBattery)
        tvStatus = findViewById(R.id.tvStatus)
        tvLogs = findViewById(R.id.tvLogs)
        svLogs = findViewById(R.id.svLogs)

        val p = getSharedPreferences("cfg", 0)
        etHost.setText(p.getString("h", "77.42.29.86"))
        etPort.setText(p.getString("p", "1005"))
        etKey.setText(p.getString("k", "ebf7ece7fd38ad9ce7d550d0934ca60b9979396e2663127de4642f4633823f04"))

        btnStart.setOnClickListener { toggle() }
        btnBattery.setOnClickListener { requestBatteryWhitelist() }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        updateBatteryButton()
        handler.removeCallbacks(refresher)
        handler.postDelayed(refresher, 3000)
    }

    private fun updateBatteryButton() {
        // Doze / battery optimization was introduced in API 23. On older devices
        // there's nothing to whitelist — hide the button.
        if (Build.VERSION.SDK_INT < 23) {
            btnBattery.visibility = View.GONE; return
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        btnBattery.visibility =
            if (pm.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE
    }

    @Suppress("BatteryLife")
    private fun requestBatteryWhitelist() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        } catch (_: Throwable) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            catch (_: Throwable) {}
        }
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private fun toggle() {
        val st = readFile("proxy_state")
        if (st == "running" || st == "starting") {
            try { startService(Intent(this, ProxyService::class.java).apply { action = "STOP" }) }
            catch (_: Throwable) {}
            return
        }

        val h = etHost.text.toString().trim()
        val po = etPort.text.toString().trim()
        val k = etKey.text.toString().trim()
        if (h.isEmpty() || po.isEmpty() || k.isEmpty()) {
            tvStatus.text = "FILL ALL FIELDS"; return
        }

        getSharedPreferences("cfg", 0).edit()
            .putString("h", h).putString("p", po).putString("k", k).apply()
        File(filesDir, "agent.log").delete()
        File(filesDir, "proxy_state").delete()

        try {
            val svc = Intent(this, ProxyService::class.java).apply {
                putExtra("host", h); putExtra("port", po); putExtra("key", k)
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
        } catch (e: Throwable) {
            tvStatus.text = "Error: ${e.message}"
            return
        }

        tvStatus.text = "STARTING..."
        tvStatus.setTextColor(0xFFFFAA00.toInt())
    }

    private fun humanRate(bps: Long): String = when {
        bps < 0 -> "—"
        bps < 1024 -> "${bps}B/s"
        bps < 1024 * 1024 -> "${bps / 1024}KB/s"
        bps < 1024L * 1024 * 1024 -> "%.1fMB/s".format(bps / 1024.0 / 1024.0)
        else -> "%.1fGB/s".format(bps / 1024.0 / 1024.0 / 1024.0)
    }

    private fun refresh() {
        val proxyState = readFile("proxy_state")
        val connInfo = readFile("conn_info").split("|")
        val connStatus = connInfo.getOrNull(0) ?: ""
        val rxRate = connInfo.getOrNull(1)?.toLongOrNull() ?: -1L
        val txRate = connInfo.getOrNull(2)?.toLongOrNull() ?: -1L

        val running = proxyState == "running" || proxyState == "starting"
        btnStart.text = if (running) "STOP" else "START"
        etHost.isEnabled = !running; etPort.isEnabled = !running; etKey.isEnabled = !running

        val (label, color) = when {
            proxyState == "error" -> "ERROR" to 0xFFFF4444.toInt()
            connStatus == "CONNECTED" ->
                "CONNECTED · ↓${humanRate(rxRate)} ↑${humanRate(txRate)}" to 0xFF00CC00.toInt()
            connStatus == "CONNECTING" -> "CONNECTING…" to 0xFFFFAA00.toInt()
            connStatus == "RECONNECTING" -> "RECONNECTING…" to 0xFFFFAA00.toInt()
            connStatus == "STARTING" || proxyState == "starting" -> "STARTING…" to 0xFFFFAA00.toInt()
            running -> "RUNNING" to 0xFF00CC00.toInt()
            else -> "DISCONNECTED" to 0xFFFF4444.toInt()
        }
        tvStatus.text = label
        tvStatus.setTextColor(color)

        val logFile = File(filesDir, "agent.log")
        if (logFile.exists()) {
            val lines = logFile.readText().lines()
            tvLogs.text = lines.takeLast(200).joinToString("\n")
            svLogs.post { svLogs.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun readFile(name: String): String {
        return try { File(filesDir, name).readText().trim() } catch (_: Throwable) { "" }
    }
}
