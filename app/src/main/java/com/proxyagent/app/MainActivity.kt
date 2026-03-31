package com.proxyagent.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
        tvStatus = findViewById(R.id.tvStatus)
        tvLogs = findViewById(R.id.tvLogs)
        svLogs = findViewById(R.id.svLogs)

        val p = getSharedPreferences("cfg", 0)
        etHost.setText(p.getString("h", "77.42.29.86"))
        etPort.setText(p.getString("p", "1005"))
        etKey.setText(p.getString("k", "ebf7ece7fd38ad9ce7d550d0934ca60b9979396e2663127de4642f4633823f04"))

        btnStart.setOnClickListener { toggle() }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        handler.removeCallbacks(refresher)
        handler.postDelayed(refresher, 3000)
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
            startForegroundService(Intent(this, ProxyService::class.java).apply {
                putExtra("host", h); putExtra("port", po); putExtra("key", k)
            })
        } catch (e: Throwable) {
            tvStatus.text = "Error: ${e.message}"
            return
        }

        tvStatus.text = "STARTING..."
        tvStatus.setTextColor(0xFFFFAA00.toInt())
    }

    private fun refresh() {
        when (readFile("proxy_state")) {
            "running" -> {
                tvStatus.text = "CONNECTED"
                tvStatus.setTextColor(0xFF00CC00.toInt())
                btnStart.text = "STOP"
                etHost.isEnabled = false; etPort.isEnabled = false; etKey.isEnabled = false
            }
            "starting" -> {
                tvStatus.text = "STARTING..."
                tvStatus.setTextColor(0xFFFFAA00.toInt())
            }
            "error" -> {
                tvStatus.text = "ERROR"
                tvStatus.setTextColor(0xFFFF4444.toInt())
                btnStart.text = "START"
                etHost.isEnabled = true; etPort.isEnabled = true; etKey.isEnabled = true
            }
            else -> {
                tvStatus.text = "DISCONNECTED"
                tvStatus.setTextColor(0xFFFF4444.toInt())
                btnStart.text = "START"
                etHost.isEnabled = true; etPort.isEnabled = true; etKey.isEnabled = true
            }
        }

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
