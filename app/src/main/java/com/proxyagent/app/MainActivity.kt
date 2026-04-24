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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var btnStart: Button
    private lateinit var btnSettings: Button
    private lateinit var btnBattery: Button
    private lateinit var spBatteryThreshold: Spinner
    private lateinit var tvStatus: TextView
    private lateinit var tvRegistrator: TextView
    private lateinit var tvActivity: TextView
    private lateinit var registratorPanel: View
    private lateinit var tvLogs: TextView
    private lateinit var svLogs: ScrollView
    private lateinit var logsHeader: View
    private lateinit var tvLogsChevron: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 3000)
        }
    }

    private val defaultHost = "77.42.29.86"
    private val defaultPort = "1005"
    private val defaultKey = "ebf7ece7fd38ad9ce7d550d0934ca60b9979396e2663127de4642f4633823f04"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStart = findViewById(R.id.btnToggle)
        btnSettings = findViewById(R.id.btnSettings)
        btnBattery = findViewById(R.id.btnBattery)
        spBatteryThreshold = findViewById(R.id.spBatteryThreshold)
        tvStatus = findViewById(R.id.tvStatus)
        tvRegistrator = findViewById(R.id.tvRegistrator)
        tvActivity = findViewById(R.id.tvActivity)
        registratorPanel = findViewById(R.id.registratorPanel)
        tvLogs = findViewById(R.id.tvLogs)
        svLogs = findViewById(R.id.svLogs)
        logsHeader = findViewById(R.id.logsHeader)
        tvLogsChevron = findViewById(R.id.tvLogsChevron)

        findViewById<TextView>(R.id.tvVersion).text =
            "v${BuildConfig.VERSION_NAME}  build ${BuildConfig.VERSION_CODE}"

        val prefs = getSharedPreferences("cfg", 0)
        // Seed defaults on first run so the settings dialog shows sensible values.
        if (!prefs.contains("h")) {
            prefs.edit()
                .putString("h", defaultHost)
                .putString("p", defaultPort)
                .putString("k", defaultKey)
                .apply()
        }

        setupBatteryThresholdSpinner(prefs)
        btnStart.setOnClickListener { toggle() }
        btnSettings.setOnClickListener { showSettingsDialog() }
        btnBattery.setOnClickListener { requestBatteryWhitelist() }

        setLogsExpanded(prefs.getBoolean("logs_expanded", false))
        logsHeader.setOnClickListener {
            val expanded = svLogs.visibility != View.VISIBLE
            setLogsExpanded(expanded)
            prefs.edit().putBoolean("logs_expanded", expanded).apply()
        }

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

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val etHost = view.findViewById<EditText>(R.id.etHost)
        val etPort = view.findViewById<EditText>(R.id.etPort)
        val etKey = view.findViewById<EditText>(R.id.etKey)
        val prefs = getSharedPreferences("cfg", 0)
        etHost.setText(prefs.getString("h", defaultHost))
        etPort.setText(prefs.getString("p", defaultPort))
        etKey.setText(prefs.getString("k", defaultKey))

        AlertDialog.Builder(this)
            .setTitle("Connection settings")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val h = etHost.text.toString().trim()
                val p = etPort.text.toString().trim()
                val k = etKey.text.toString().trim()
                if (h.isEmpty() || p.isEmpty() || k.isEmpty()) {
                    Toast.makeText(this, "All fields are required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                prefs.edit().putString("h", h).putString("p", p).putString("k", k).apply()
                if (readFile("proxy_state").let { it == "running" || it == "starting" }) {
                    Toast.makeText(this, "Saved — restart agent to apply", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateBatteryButton() {
        if (Build.VERSION.SDK_INT < 23) {
            btnBattery.visibility = View.GONE; return
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        btnBattery.visibility =
            if (pm.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE
    }

    private val batteryThresholds = intArrayOf(0, 5, 10, 15, 20, 30)
    private val batteryLabels = arrayOf("Off", "5%", "10%", "15%", "20%", "30%")

    private fun setupBatteryThresholdSpinner(prefs: android.content.SharedPreferences) {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, batteryLabels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spBatteryThreshold.adapter = adapter
        val saved = prefs.getInt("bat_threshold", 0)
        val pos = batteryThresholds.indexOf(saved).coerceAtLeast(0)
        spBatteryThreshold.setSelection(pos)
        spBatteryThreshold.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                val value = batteryThresholds[position]
                prefs.edit().putInt("bat_threshold", value).apply()
                try { File(filesDir, "battery_threshold").writeText(value.toString()) }
                catch (_: Throwable) {}
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
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

    private fun toggle() {
        val st = readFile("proxy_state")
        if (st == "running" || st == "starting") {
            try { startService(Intent(this, ProxyService::class.java).apply { action = "STOP" }) }
            catch (_: Throwable) {}
            return
        }

        val prefs = getSharedPreferences("cfg", 0)
        val h = prefs.getString("h", "")?.trim().orEmpty()
        val po = prefs.getString("p", "")?.trim().orEmpty()
        val k = prefs.getString("k", "")?.trim().orEmpty()
        if (h.isEmpty() || po.isEmpty() || k.isEmpty()) {
            tvStatus.text = "CONFIGURE FIRST (⚙)"
            tvStatus.setTextColor(0xFFFF4444.toInt())
            showSettingsDialog()
            return
        }

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

    private fun setLogsExpanded(expanded: Boolean) {
        svLogs.visibility = if (expanded) View.VISIBLE else View.GONE
        tvLogsChevron.text = if (expanded) "▲" else "▼"
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
        val registrator = connInfo.getOrNull(3).orEmpty()
        val tunnels = connInfo.getOrNull(4)?.toIntOrNull() ?: 0

        val running = proxyState == "running" || proxyState == "starting"
        btnStart.text = if (running) "STOP" else "START"

        val (label, color) = when {
            proxyState == "error" -> "ERROR" to 0xFFFF4444.toInt()
            proxyState == "auto_stopped" -> "AUTO-STOPPED (LOW BATTERY)" to 0xFFFFAA00.toInt()
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

        if (connStatus == "CONNECTED" && registrator.isNotEmpty()) {
            registratorPanel.visibility = View.VISIBLE
            tvRegistrator.text = registrator
            tvActivity.text = when {
                tunnels == 0 -> "◦ idle — no clients"
                else -> "⚡ $tunnels ${if (tunnels == 1) "client" else "clients"} · " +
                    "↓${humanRate(rxRate)} ↑${humanRate(txRate)}"
            }
        } else {
            registratorPanel.visibility = View.GONE
        }

        if (svLogs.visibility == View.VISIBLE) {
            val logFile = File(filesDir, "agent.log")
            if (logFile.exists()) {
                val lines = logFile.readText().lines()
                tvLogs.text = lines.takeLast(200).joinToString("\n")
                svLogs.post { svLogs.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
    }

    private fun readFile(name: String): String {
        return try { File(filesDir, name).readText().trim() } catch (_: Throwable) { "" }
    }
}
