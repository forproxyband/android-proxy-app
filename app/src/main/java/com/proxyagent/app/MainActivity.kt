package com.proxyagent.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.viewpager2.widget.ViewPager2
import com.proxyagent.app.ota.OtaConfig
import com.proxyagent.app.ota.OtaManager
import com.proxyagent.app.ota.OtaScheduler
import com.proxyagent.app.ota.UpdateStatus
import com.proxyagent.app.ota.UpdatesActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var btnStart: Button
    private lateinit var btnCycleIp: Button
    private lateinit var btnSettings: Button
    private lateinit var btnAnalytics: Button
    private lateinit var btnBattery: Button
    private lateinit var spBatteryThreshold: Spinner
    private lateinit var tvStatus: TextView
    private lateinit var tvNetwork: TextView
    private lateinit var registratorPanel: View
    private lateinit var registratorPager: ViewPager2
    private lateinit var dot0: View
    private lateinit var dot1: View
    private lateinit var dot2: View
    private lateinit var dot3: View
    private val pagerRefs = StatusPagerAdapter.PageRefs()
    @Volatile private var lastPanelDataAtMs = 0L
    private lateinit var tvLogs: TextView
    private lateinit var svLogs: ScrollView
    private lateinit var logsHeader: View
    private lateinit var tvLogsChevron: TextView
    private lateinit var btnSaveLog: Button
    private lateinit var otaWidget: View
    private lateinit var tvOtaChannel: TextView
    private lateinit var tvOtaStatus: TextView
    @Volatile private var otaChecking = false
    private var lastOtaCheckMs = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, if (pendingAction != null) 250L else 3000L)
        }
    }

    @Volatile private var publicIp = ""
    @Volatile private var cyclingIp = false
    // While a start/stop is in flight the toggle button is locked so taps don't
    // race the state file. Cleared once proxy_state settles or the deadline trips.
    @Volatile private var pendingAction: String? = null
    private var pendingActionDeadlineMs: Long = 0L
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    // Held only for the duration of a split-mode manual rotation: binds :main
    // to a cellular Network so IpCycle's public-IP probes egress cellular even
    // while Wi-Fi is the system default. Released in unbindMainFromCellular.
    private var cycleCellularCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15+ forces edge-to-edge; tell the decor view to reserve space
        // for system bars + camera cutout so title doesn't render under them.
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_main)

        btnStart = findViewById(R.id.btnToggle)
        btnCycleIp = findViewById(R.id.btnCycleIp)
        btnSettings = findViewById(R.id.btnSettings)
        btnAnalytics = findViewById(R.id.btnAnalytics)
        btnBattery = findViewById(R.id.btnBattery)
        spBatteryThreshold = findViewById(R.id.spBatteryThreshold)
        tvStatus = findViewById(R.id.tvStatus)
        tvNetwork = findViewById(R.id.tvNetwork)
        registratorPanel = findViewById(R.id.registratorPanel)
        registratorPager = findViewById(R.id.registratorPager)
        dot0 = findViewById(R.id.dot0)
        dot1 = findViewById(R.id.dot1)
        dot2 = findViewById(R.id.dot2)
        dot3 = findViewById(R.id.dot3)
        registratorPager.adapter = StatusPagerAdapter(pagerRefs)
        registratorPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updatePagerDots(position)
                if (position != 0) refreshPanelCharts()
            }
        })
        tvLogs = findViewById(R.id.tvLogs)
        svLogs = findViewById(R.id.svLogs)
        logsHeader = findViewById(R.id.logsHeader)
        tvLogsChevron = findViewById(R.id.tvLogsChevron)
        btnSaveLog = findViewById(R.id.btnSaveLog)

        findViewById<TextView>(R.id.tvVersion).text =
            "v${BuildConfig.VERSION_NAME}  build ${BuildConfig.VERSION_CODE}"

        otaWidget = findViewById(R.id.otaWidget)
        tvOtaChannel = findViewById(R.id.tvOtaChannel)
        tvOtaStatus = findViewById(R.id.tvOtaStatus)
        otaWidget.setOnClickListener {
            startActivity(Intent(this, UpdatesActivity::class.java))
        }
        // Long-press = run the background check now (test aid: fires the
        // update-available notification without waiting for the periodic run).
        otaWidget.setOnLongClickListener {
            try {
                OtaScheduler.runOnceNow(this)
                Toast.makeText(this, "Update check triggered", Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {}
            true
        }
        refreshOtaWidget(force = true)
        // Periodic background update check (posts a notification when one lands).
        // Skipped when OTA isn't wired for this build type (e.g. debug without
        // a registered CRM app) — the widget hides itself too.
        if (OtaConfig.isConfigured()) {
            try { OtaScheduler.schedule(this) } catch (_: Throwable) {}
        }

        val prefs = getSharedPreferences("cfg", 0)

        setupBatteryThresholdSpinner(prefs)
        btnStart.setOnClickListener { toggle() }
        btnCycleIp.setOnClickListener { cycleMobileIp() }
        btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        btnAnalytics.setOnClickListener {
            startActivity(Intent(this, AnalyticsActivity::class.java))
        }
        btnBattery.setOnClickListener { requestBatteryWhitelist() }

        // Drop day-files older than the user's retention setting (default 30d).
        Thread { try { AnalyticsStore.pruneToRetention(this) } catch (_: Throwable) {} }
            .apply { isDaemon = true; name = "AnalyticsPrune"; start() }

        // Recovery from a cellular rotation interrupted by a process kill
        // (PACKAGE_REPLACED app update, OOM, force-stop). IpCycle drops an
        // in-progress marker file before toggling airplane mode and removes
        // it in finally — so a leftover marker on launch means we may have
        // died between airplaneOn() and airplaneOff(), leaving the device
        // without any cellular path. Background-threaded because the root
        // probe + secure-settings write can take a second or two and we
        // don't want to delay first paint.
        Thread {
            try {
                IpCycle.recoverInterruptedCycle(this) { msg ->
                    Log.i("ProxyAgent", "rotation-recovery: $msg")
                }
            } catch (_: Throwable) {}
        }.apply { isDaemon = true; name = "RotationRecovery"; start() }

        // Mirror current cycle config from SharedPreferences into the
        // cross-process file. Back-fill for users who upgraded but haven't
        // opened Settings again, and a no-op refresh for everyone else.
        // Without this, :proxy reads default CycleConfig (all-false) until
        // the user explicitly re-saves settings (SettingsActivity).
        IpCycle.saveConfigToFile(
            this,
            IpCycle.CycleConfig(
                apnSwap = prefs.getBoolean("apn_swap", false),
                imeiRotation = prefs.getBoolean("imei_rotate", false),
                imeiMethod = prefs.getString("imei_method", "custom") ?: "custom",
                imeiCustomCmd = prefs.getString("imei_cmd", "") ?: "",
                wifiReturn = prefs.getBoolean("wifi_return", false),
                wifiReturnMethod = prefs.getString("wifi_return_method", "local_relay")
                    ?: "local_relay",
                rotationLock = prefs.getBoolean("rotation_lock", true),
                cooldownSeconds = prefs.getInt("rotation_cooldown_s", 10),
            ),
        )

        setLogsExpanded(prefs.getBoolean("logs_expanded", false))
        logsHeader.setOnClickListener {
            val expanded = svLogs.visibility != View.VISIBLE
            setLogsExpanded(expanded)
            prefs.edit().putBoolean("logs_expanded", expanded).apply()
        }
        btnSaveLog.setOnClickListener { saveLog() }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        registerNetCallback()
        refreshPublicIp()
    }

    override fun onDestroy() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            netCallback?.let { cm.unregisterNetworkCallback(it) }
        } catch (_: Throwable) {}
        netCallback = null
        // Release a cellular bind if a manual rotation is still in flight when
        // the activity is torn down — otherwise the callback + process bind
        // would outlive the UI.
        unbindMainFromCellular { }
        super.onDestroy()
    }

    private fun registerNetCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                private var lastNet: Network? = null
                override fun onAvailable(network: Network) {
                    val prev = lastNet
                    lastNet = network
                    if (prev == null || prev != network) {
                        publicIp = ""
                        runOnUiThread { refresh() }
                        refreshPublicIp()
                    }
                }
                override fun onLost(network: Network) {
                    if (lastNet == network) {
                        lastNet = null
                        publicIp = ""
                        runOnUiThread { refresh() }
                    }
                }
            }
            netCallback = cb
            if (Build.VERSION.SDK_INT >= 24) {
                cm.registerDefaultNetworkCallback(cb)
            } else {
                val req = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(req, cb)
            }
        } catch (_: Throwable) {}
    }

    private fun refreshPublicIp() {
        Thread {
            // Let the network finalize routes/DNS after a switch.
            try { Thread.sleep(1200) } catch (_: InterruptedException) { return@Thread }
            val services = listOf("https://api.ipify.org", "https://icanhazip.com")
            for (url in services) {
                try {
                    val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                        connectTimeout = 5000
                        readTimeout = 5000
                        requestMethod = "GET"
                        setRequestProperty("User-Agent", "ProxyAgent-Android")
                    }
                    val ip = conn.inputStream.bufferedReader().use { it.readText().trim() }
                    conn.disconnect()
                    if (ip.isNotEmpty() && ip.length < 40 &&
                        (ip.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) || ip.contains(":"))) {
                        publicIp = ip
                        try { File(filesDir, "nat_ip").writeText(ip) } catch (_: Throwable) {}
                        runOnUiThread { refresh() }
                        return@Thread
                    }
                } catch (_: Throwable) {}
            }
        }.apply { isDaemon = true; name = "PublicIpFetch"; start() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        updateBatteryButton()
        refreshOtaWidget(force = false)
        handler.removeCallbacks(refresher)
        handler.postDelayed(refresher, 3000)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    // Update the main-screen OTA widget: always refresh the tracked-channel
    // label, and (throttled to once a minute unless forced) check the manifest
    // in the background. Never downloads — that only happens from UpdatesActivity.
    private fun refreshOtaWidget(force: Boolean) {
        // No OTA for this build type (blank app id/key) → hide the widget.
        if (!OtaConfig.isConfigured()) {
            otaWidget.visibility = View.GONE
            return
        }
        otaWidget.visibility = View.VISIBLE
        val channel = OtaConfig.channel(this)
        tvOtaChannel.text = "⟳ ${channel.label}"

        if (otaChecking) return
        val now = System.currentTimeMillis()
        if (!force && now - lastOtaCheckMs < 60_000L) return
        otaChecking = true
        lastOtaCheckMs = now
        tvOtaStatus.text = "checking…"
        tvOtaStatus.setTextColor(0xFF888888.toInt())

        Thread {
            var status: UpdateStatus? = null
            var failed = false
            try {
                status = OtaManager.check(this, channel)
            } catch (_: Throwable) {
                failed = true
            }
            if (!failed) OtaConfig.recordCheck(this)
            val s = status
            runOnUiThread {
                otaChecking = false
                when {
                    failed || s == null -> {
                        tvOtaStatus.text = "update check failed"
                        tvOtaStatus.setTextColor(0xFF888888.toInt())
                    }
                    s is UpdateStatus.Available -> {
                        tvOtaStatus.text = "Update available: ${s.release.version}"
                        tvOtaStatus.setTextColor(0xFFFFCC66.toInt())
                    }
                    s is UpdateStatus.UpToDate -> {
                        tvOtaStatus.text = "Version up to date"
                        tvOtaStatus.setTextColor(0xFF88ffaa.toInt())
                    }
                    else -> {
                        tvOtaStatus.text = "no release in channel"
                        tvOtaStatus.setTextColor(0xFF888888.toInt())
                    }
                }
            }
        }.apply { isDaemon = true; name = "OtaWidgetCheck"; start() }
    }

    private fun updateBatteryButton() {
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

    private fun showConfigurePrompt() {
        AlertDialog.Builder(this)
            .setTitle("Connection not configured")
            .setMessage("Host / Port / Key are required. Open settings to scan a tunnel QR, paste it, or fill in manually.")
            .setPositiveButton("Configure") { _, _ ->
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun hasConnectionConfig(): Boolean {
        val p = getSharedPreferences("cfg", 0)
        return !p.getString("h", "").isNullOrBlank() &&
            !p.getString("p", "").isNullOrBlank() &&
            !p.getString("k", "").isNullOrBlank()
    }

    private fun toggle() {
        if (pendingAction != null || cyclingIp) return
        val st = readFile("proxy_state")
        if (st == "running" || st == "starting") {
            pendingAction = "stop"
            pendingActionDeadlineMs = System.currentTimeMillis() + 10_000
            try { startService(Intent(this, ProxyService::class.java).apply { action = "STOP" }) }
            catch (_: Throwable) {}
        } else {
            if (!hasConnectionConfig()) {
                showConfigurePrompt()
                return
            }
            if (!startProxyService()) return
            pendingAction = "start"
            pendingActionDeadlineMs = System.currentTimeMillis() + 15_000
        }
        handler.removeCallbacks(refresher)
        handler.post(refresher)
    }

    // Stops the proxy (if running), cycles cellular so the carrier hands out a
    // new IP, then restarts the proxy.
    //
    // Tries two automated paths in order:
    //   1. root: `svc data disable/enable` (or airplane mode if data toggle fails)
    //   2. WRITE_SECURE_SETTINGS: write Settings.Global.AIRPLANE_MODE_ON directly.
    //      Signature-level permission, granted once via:
    //        adb shell pm grant <pkg> WRITE_SECURE_SETTINGS
    //
    // If both fail, the cycle aborts with a Toast — there is no manual fallback.
    private fun cycleMobileIp() {
        if (cyclingIp) return
        val transport = currentTransport()
        if (!canCycleMobileIp(transport)) {
            // On Wi-Fi with split mode on but no cellular link to rotate vs.
            // plain Wi-Fi (rotating cellular wouldn't change the exit IP).
            val msg = if (transport == "WIFI" && isSplitModeEnabled())
                "No mobile network to rotate — check the SIM / mobile data"
            else
                "Disable WiFi to cycle mobile IP"
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            return
        }

        // Cross-process rotation guard: skip if a rotation is already running
        // (e.g. a server REBOOT auto-cycle in :proxy) or we're still inside the
        // post-rotation cooldown. Same gate the REBOOT path uses, so a manual
        // press and a remote request can never run two concurrent cycles.
        // Toggle + cooldown length live in Settings.
        val gate = IpCycle.checkRotationGate(this, IpCycle.loadConfigFromFile(this))
        if (!gate.allowed) {
            val gateMsg = if (gate.reason == "cooldown")
                "Rotation cooldown — wait ${gate.remainingMs / 1000 + 1}s"
            else "Rotation already in progress"
            Toast.makeText(this, gateMsg, Toast.LENGTH_SHORT).show()
            return
        }

        cyclingIp = true
        btnCycleIp.isEnabled = false
        val originalLabel = btnCycleIp.text
        btnCycleIp.text = "…"
        val wasRunning = readFile("proxy_state").let { it == "running" || it == "starting" }

        Thread {
            var stage = "init"
            try {
                if (wasRunning) {
                    stage = "stopping proxy"
                    runOnUiThread { tvStatus.text = "STOPPING…"; tvStatus.setTextColor(0xFFFFAA00.toInt()) }
                    try {
                        startService(Intent(this, ProxyService::class.java).apply { action = "STOP" })
                    } catch (_: Throwable) {}
                    val stopDeadline = System.currentTimeMillis() + 8_000
                    while (System.currentTimeMillis() < stopDeadline) {
                        val s = readFile("proxy_state")
                        if (s != "running" && s != "starting") break
                        Thread.sleep(200)
                    }
                }

                // Split mode (Wi-Fi default + cellular exit): pin :main's
                // sockets to cellular so the public-IP probes below don't
                // measure the Wi-Fi IP. Done after stopping :proxy so its own
                // cellular requestNetwork is gone and ours holds the radio up.
                if (transport == "WIFI" && isSplitModeEnabled()) {
                    stage = "binding cellular"
                    bindMainToCellular { msg -> Log.i("IpCycle", msg) }
                }

                stage = "cycling network"
                runOnUiThread { tvStatus.text = "CYCLING NETWORK…"; tvStatus.setTextColor(0xFFFFAA00.toInt()) }

                // In split mode publicIp is the Wi-Fi IP (fetched over the
                // default route), so comparing it against the post-cycle
                // cellular IP would always read as "changed". Baseline off the
                // cellular exit instead so the result reflects the mobile IP.
                val baselineIp = if (transport == "WIFI" && isSplitModeEnabled())
                    cellularExitIp().ifEmpty { publicIp }
                else publicIp
                // Read from the cross-process file so manual ↻ and the
                // REBOOT auto-cycle behave identically — same source of
                // truth, no chance of the two paths drifting if SharedPrefs
                // caching ever gets stale within :main itself.
                val cfg = IpCycle.loadConfigFromFile(this)
                val result = IpCycle.cycleAndVerify(
                    context = this,
                    knownIp = baselineIp,
                    log = { msg ->
                        Log.i("IpCycle", msg)
                        runOnUiThread { tvStatus.text = ("CYCLE: $msg").take(64) }
                    },
                    config = cfg,
                )
                // Persist the rotation event with full result detail so the
                // analytics screen can show the IP change + outcome. Recorded
                // immediately so the row exists even if a later UI/restart
                // step fails.
                AnalyticsStore.recordCycleEvent(
                    this,
                    CycleEvent(
                        tMs = System.currentTimeMillis(),
                        kind = AnalyticsStore.CYCLE_MANUAL,
                        oldIp = result.oldIp,
                        newIp = result.newIp,
                        changed = result.changed,
                        reason = result.reason,
                        attempts = result.attempts,
                        durationMs = result.totalMs,
                    ),
                )

                if (result.reason == "no_toggle_method") {
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "Auto-cycle unavailable. Grant: adb shell pm grant $packageName " +
                                "android.permission.WRITE_SECURE_SETTINGS",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    if (wasRunning) runOnUiThread { startProxyService() }
                    return@Thread
                }

                if (result.newIp.isNotEmpty()) {
                    publicIp = result.newIp
                    try { File(filesDir, "nat_ip").writeText(result.newIp) } catch (_: Throwable) {}
                } else {
                    // Couldn't read IP during the cycle (no network yet, or all
                    // fetches failed). Trigger an async refresh so the UI heals
                    // once the network stabilises.
                    publicIp = ""
                    refreshPublicIp()
                }

                if (wasRunning) {
                    stage = "restarting proxy"
                    runOnUiThread { startProxyService() }
                }

                val secs = result.totalMs / 1000
                val toastMsg = when {
                    result.changed ->
                        "IP changed in ${result.attempts} try(s) / ${secs}s: ${result.oldIp} → ${result.newIp}"
                    result.reason == "ok_no_baseline" ->
                        "IP cycle done in ${secs}s; baseline unknown — current: ${result.newIp}"
                    result.reason == "ip_unchanged" ->
                        "IP unchanged after ${result.attempts} try(s) / ${secs}s — try again later"
                    else -> "IP cycle: ${result.reason} (${secs}s)"
                }
                runOnUiThread {
                    Toast.makeText(this, toastMsg, Toast.LENGTH_LONG).show()
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "Cycle failed at $stage: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                // Always release the cellular bind (no-op if we never took it),
                // otherwise :main stays pinned to cellular after the rotation.
                unbindMainFromCellular { msg -> Log.i("IpCycle", msg) }
                cyclingIp = false
                runOnUiThread {
                    btnCycleIp.text = originalLabel
                    refresh()
                }
            }
        }.apply { name = "IpCycler"; isDaemon = true; start() }
    }

    private fun startProxyService(): Boolean {
        val prefs = getSharedPreferences("cfg", 0)
        val h = prefs.getString("h", "")?.trim().orEmpty()
        val po = prefs.getString("p", "")?.trim().orEmpty()
        val k = prefs.getString("k", "")?.trim().orEmpty()
        val id = prefs.getString("id", "")?.trim().orEmpty()
        val d = prefs.getString("dns", "")?.trim().orEmpty()
        if (h.isEmpty() || po.isEmpty() || k.isEmpty()) return false

        File(filesDir, "agent.log").delete()
        File(filesDir, "proxy_state").delete()
        File(filesDir, "conn_info").delete()

        val engine = prefs.getString("engine", "native") ?: "native"
        val mode = prefs.getString("mode", "modem") ?: "modem"
        val networkProfile = prefs.getString("network_profile", "LOW_100") ?: "LOW_100"
        // QUIC implementation chooser was removed from the UI — the
        // in-house stack is the default. We deliberately do NOT pass a
        // `quic_impl` extra so ProxyService applies its own default
        // ("native"), even if the user has a stale "kwik" value lingering
        // in SharedPreferences from before. The kwik adapter is still
        // wired in ProxyService for emergency overrides via adb, but
        // there's no UI path to it any more.
        return try {
            val svc = Intent(this, ProxyService::class.java).apply {
                putExtra("host", h); putExtra("port", po); putExtra("key", k)
                putExtra("id", id); putExtra("dns", d)
                putExtra("engine", engine)
                putExtra("mode", mode)
                putExtra("network_profile", networkProfile)
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
            tvStatus.text = "STARTING..."
            tvStatus.setTextColor(0xFFFFAA00.toInt())
            true
        } catch (e: Throwable) {
            tvStatus.text = "Error: ${e.message}"
            false
        }
    }

    private fun saveLog() {
        val src = File(filesDir, "agent.log")
        if (!src.exists() || src.length() == 0L) {
            Toast.makeText(this, "No log to save yet", Toast.LENGTH_SHORT).show(); return
        }
        try {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val exportDir = File(filesDir, "exports").apply { mkdirs() }
            exportDir.listFiles()?.forEach { it.delete() }
            val snapshot = File(exportDir, "proxy-agent-$stamp.log")

            // Write device-info header, then stream the log body in.
            snapshot.outputStream().use { out ->
                out.write(buildDeviceInfoHeader().toByteArray())
                src.inputStream().use { it.copyTo(out) }
            }

            val uri = FileProvider.getUriForFile(
                this, "$packageName.fileprovider", snapshot
            )
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Proxy Agent log $stamp")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "Save / share log"))
        } catch (e: Throwable) {
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun currentLocalIps(): List<String> {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return emptyList()
            val link = cm.getLinkProperties(net) ?: return emptyList()
            link.linkAddresses.mapNotNull { la ->
                la.address.hostAddress?.takeIf { !la.address.isLoopbackAddress }
            }
        } catch (_: Throwable) { emptyList() }
    }

    private fun currentTransport(): String {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            when {
                caps == null -> "NONE"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "OTHER"
            }
        } catch (_: Throwable) { "?" }
    }

    // Split mode = Wi-Fi return relay. When on, the proxy binds its target
    // dials to cellular (mobile exit IP) while the agent↔registrator uplink
    // rides Wi-Fi. See IpCycle.CycleConfig.wifiReturn / ProxyService relay.
    private fun isSplitModeEnabled(): Boolean =
        getSharedPreferences("cfg", 0).getBoolean("wifi_return", false)

    // Is a cellular network present alongside the system default? Unlike
    // currentTransport(), which only inspects the single default network
    // (Wi-Fi when connected), this scans every held Network — so we can
    // tell there is a mobile link to rotate even while Wi-Fi is the default,
    // which is exactly the split-mode situation.
    private fun hasCellularNetwork(): Boolean = try {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.allNetworks.any { n ->
            cm.getNetworkCapabilities(n)?.let { caps ->
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } == true
        }
    } catch (_: Throwable) { false }

    // Whether the ↻ (cycle mobile IP) button should be usable given the
    // current default transport. Rotation flips the cellular radio, so it
    // only helps when the proxy's exit actually rides cellular:
    //   - cellular / ethernet / none → always meaningful
    //   - Wi-Fi  → normally pointless (traffic egresses Wi-Fi), EXCEPT in
    //     split mode, where target dials still ride cellular. Allow it there
    //     as long as a cellular network is actually present to rotate.
    //   - VPN    → routing is opaque; never allow.
    private fun canCycleMobileIp(transport: String): Boolean = when (transport) {
        "VPN" -> false
        "WIFI" -> isSplitModeEnabled() && hasCellularNetwork()
        else -> true
    }

    // Binds the :main process to a requested cellular Network for the duration
    // of a split-mode manual rotation. Without this, IpCycle (which runs in
    // :main, and — unlike :proxy — is not process-bound to cellular) would
    // measure the public IP over the system default route, which stays Wi-Fi
    // in split mode. The callback re-binds on every onAvailable so we follow
    // the fresh cellular Network that appears after the airplane-mode toggle;
    // onLost deliberately does NOT unbind (mirrors ProxyService) so probes
    // fail closed instead of silently leaking to Wi-Fi mid-cycle.
    //
    // Blocks up to 10s for the first cellular Network. Returns false on
    // timeout / error — the caller proceeds unbound (degraded: may measure
    // the Wi-Fi IP), which is no worse than the pre-fix behaviour.
    private fun bindMainToCellular(log: (String) -> Unit): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val latch = java.util.concurrent.CountDownLatch(1)
        val ref = arrayOf<Network?>(null)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                ref[0] = network
                try {
                    cm.bindProcessToNetwork(network)
                    log("cycle: bound :main to cellular=$network")
                } catch (t: Throwable) {
                    log("cycle: bindProcessToNetwork failed: ${t.message}")
                }
                latch.countDown()
            }
        }
        return try {
            cm.requestNetwork(req, cb)
            cycleCellularCallback = cb
            val ok = latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
            if (!ok || ref[0] == null) {
                log("cycle: cellular requestNetwork timed out — measuring on default route")
                unbindMainFromCellular(log)
                false
            } else true
        } catch (t: Throwable) {
            log("cycle: requestNetwork(CELLULAR) failed: ${t.message}")
            unbindMainFromCellular(log)
            false
        }
    }

    private fun unbindMainFromCellular(log: (String) -> Unit) {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        cycleCellularCallback?.let { cb ->
            try { cm?.unregisterNetworkCallback(cb) } catch (_: Throwable) {}
        }
        cycleCellularCallback = null
        try {
            cm?.bindProcessToNetwork(null)
            log("cycle: :main unbound from cellular (default routing restored)")
        } catch (_: Throwable) {}
    }

    private fun buildDeviceInfoHeader(): String {
        val sb = StringBuilder()
        val line = "=".repeat(64)
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ssZ", Locale.US).format(Date())

        fun kv(k: String, v: Any?) {
            sb.append(k).append(':')
            val pad = (22 - k.length - 1).coerceAtLeast(1)
            repeat(pad) { sb.append(' ') }
            sb.append(v?.toString() ?: "—").append('\n')
        }
        fun section(title: String) { sb.append('\n').append("[").append(title).append("]\n") }

        sb.append(line).append('\n')
        sb.append("Proxy Agent · Log Export\n")
        sb.append(line).append('\n')
        kv("Exported-At", now)

        section("CONNECTION STATE")
        // Same staleness gate as refresh() — if :proxy is dead, the export
        // shows DISCONNECTED instead of a forever-running fake session.
        val (proxyState, info) = readLiveProxyFiles()
        val connStatus = info.getOrNull(0).orEmpty()
        val rxRate = info.getOrNull(1)?.toLongOrNull() ?: -1L
        val txRate = info.getOrNull(2)?.toLongOrNull() ?: -1L
        val registrator = info.getOrNull(3).orEmpty()
        val tunnels = info.getOrNull(4)?.toIntOrNull() ?: 0
        val connSince = info.getOrNull(5)?.toLongOrNull() ?: 0L
        val statusLabel = when {
            proxyState == "error" -> "ERROR"
            proxyState == "auto_stopped" -> "AUTO-STOPPED (LOW BATTERY)"
            connStatus.isNotEmpty() -> connStatus
            proxyState == "running" -> "RUNNING"
            proxyState == "starting" -> "STARTING"
            proxyState == "stopped" -> "STOPPED"
            else -> "DISCONNECTED"
        }
        kv("Status", statusLabel)
        if (connStatus == "CONNECTED") {
            if (registrator.isNotEmpty()) kv("Registrator", registrator)
            if (connSince > 0) kv("Uptime", formatDuration(System.currentTimeMillis() - connSince))
            kv("Active-Tunnels", tunnels)
            if (rxRate >= 0 || txRate >= 0)
                kv("Rate", "↓${humanRate(rxRate)}  ↑${humanRate(txRate)}")
        }
        kv("Transport", currentTransport())
        if (publicIp.isNotEmpty()) kv("Public-IP", publicIp)
        // Wi-Fi return relay state — surfaced here so saved logs make the
        // routing topology obvious to whoever reviews them. Three flavours:
        //   1. disabled — feature off in settings
        //   2. enabled (uplink via Wi-Fi) — relay up and actively on Wi-Fi
        //   3. enabled (fallback to cellular) — relay up, no Wi-Fi held
        // The conn_info field 8 only carries (2)/(3); SP gives us (1).
        val cfgPrefs = getSharedPreferences("cfg", 0)
        val wifiReturnPref = cfgPrefs.getBoolean("wifi_return", false)
        val wifiReturnLabel = when {
            !wifiReturnPref -> "disabled"
            info.getOrNull(8).orEmpty() == "wifi" -> "enabled (uplink via Wi-Fi)"
            info.getOrNull(8).orEmpty() == "wifi_fallback" -> "enabled (fallback to cellular — no Wi-Fi held)"
            // Setting is on but proxy isn't running (or conn_info hasn't been
            // updated yet) — surface the configured state without implying
            // a current routing decision.
            else -> "enabled (proxy not running)"
        }
        kv("Wi-Fi-Return", wifiReturnLabel)

        // Detailed Wi-Fi return diagnostics — only emitted when there's
        // actual data to show (wifi_info.json present from a recent self-
        // test). Lets reviewers see both public IPs + the physical Wi-Fi
        // link characteristics without needing access to the device.
        if (wifiReturnPref) {
            val info = readWifiInfoJson()
            if (info != null) {
                section("WI-FI RETURN")
                val cellIp = info.optString("public_ip_cell", "")
                val wifiIp = info.optString("public_ip_wifi", "")
                if (cellIp.isNotEmpty()) kv("Cellular-Public-IP", cellIp)
                if (wifiIp.isNotEmpty()) kv("Wi-Fi-Public-IP", wifiIp)
                val speed = info.optInt("link_speed_mbps", -1)
                if (speed > 0) kv("Wi-Fi-Link-Speed", "$speed Mbps")
                val freq = info.optInt("frequency_mhz", -1)
                if (freq > 0) kv("Wi-Fi-Frequency", "$freq MHz")
                val band = info.optString("band", "")
                if (band.isNotEmpty()) kv("Wi-Fi-Band", band)
                val std = info.optString("standard", "")
                if (std.isNotEmpty()) kv("Wi-Fi-Standard", std)
                val testResult = info.optString("test_result", "")
                if (testResult.isNotEmpty()) {
                    val verdict = when (testResult) {
                        "SUCCESS" -> "VERIFIED (Wi-Fi IP ≠ cellular IP)"
                        "SAME_IP" -> "FAILED (both transports share IP — OS suppresses cellular)"
                        "WIFI_PROBE_FAILED" -> "PARTIAL (Wi-Fi probe failed; cellular probe ok)"
                        "CELL_PROBE_FAILED" -> "PARTIAL (cellular probe failed; Wi-Fi probe ok)"
                        "BOTH_FAILED" -> "INCONCLUSIVE (both probes failed)"
                        else -> testResult
                    }
                    kv("Split-Routing", verdict)
                }
                val testedAt = info.optLong("tested_at_ms", 0L)
                if (testedAt > 0L) {
                    kv(
                        "Self-Test-At",
                        SimpleDateFormat("yyyy-MM-dd HH:mm:ssZ", Locale.US)
                            .format(Date(testedAt)),
                    )
                }
                val testDetail = info.optString("test_detail", "")
                if (testDetail.isNotEmpty()) kv("Self-Test-Detail", testDetail)
            }

            // Session byte counters from conn_info fields 12-17 (schema v2).
            // Surface them here so log readers can see exactly how much
            // traffic the relay actually moved over each interface — the
            // most useful single number for "is Wi-Fi return doing its
            // job" diagnostics. We pull the live conn_info (already read
            // into `info` above for the cellular-IP fallback) to avoid a
            // double read.
            val connInfoFields = readFile("conn_info").split("|")
            val sWifiUp = connInfoFields.getOrNull(12)?.toLongOrNull() ?: 0L
            val sWifiDown = connInfoFields.getOrNull(13)?.toLongOrNull() ?: 0L
            val sFbUp = connInfoFields.getOrNull(14)?.toLongOrNull() ?: 0L
            val sFbDown = connInfoFields.getOrNull(15)?.toLongOrNull() ?: 0L
            val sTgtUp = connInfoFields.getOrNull(16)?.toLongOrNull() ?: 0L
            val sTgtDown = connInfoFields.getOrNull(17)?.toLongOrNull() ?: 0L
            if (sWifiUp + sWifiDown + sFbUp + sFbDown + sTgtUp + sTgtDown > 0L) {
                // Format with humanBytes() (already in this file for the
                // panel charts) so readers see "12.4 MB" instead of raw
                // byte counts.
                if (sWifiUp > 0L) kv("Wi-Fi-Session-Tx", humanBytes(sWifiUp))
                if (sWifiDown > 0L) kv("Wi-Fi-Session-Rx", humanBytes(sWifiDown))
                if (sWifiUp + sWifiDown > 0L) {
                    kv("Wi-Fi-Session-Saved", humanBytes(sWifiUp + sWifiDown))
                }
                if (sFbUp > 0L) kv("Cellular-Fallback-Tx", humanBytes(sFbUp))
                if (sFbDown > 0L) kv("Cellular-Fallback-Rx", humanBytes(sFbDown))
                if (sTgtUp > 0L) kv("Cellular-Target-Tx", humanBytes(sTgtUp))
                if (sTgtDown > 0L) kv("Cellular-Target-Rx", humanBytes(sTgtDown))
            }
        }

        section("APP")
        kv("Package", packageName)
        kv("Version", "${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")

        section("DEVICE")
        kv("Manufacturer", Build.MANUFACTURER)
        kv("Model", Build.MODEL)
        kv("Device", Build.DEVICE)
        kv("Brand", Build.BRAND)
        kv("Product", Build.PRODUCT)
        kv("Board", Build.BOARD)

        section("ANDROID")
        kv("Release", Build.VERSION.RELEASE)
        kv("SDK", Build.VERSION.SDK_INT)
        kv("Incremental", Build.VERSION.INCREMENTAL)
        kv("Security-Patch", Build.VERSION.SECURITY_PATCH)
        kv("Fingerprint", Build.FINGERPRINT)

        section("ARCH")
        kv("Supported-ABIs", Build.SUPPORTED_ABIS.joinToString(", "))
        kv("Kernel", System.getProperty("os.version"))

        section("PERMISSIONS")
        val perms = listOf(
            "INTERNET", "ACCESS_NETWORK_STATE",
            "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_DATA_SYNC",
            "FOREGROUND_SERVICE_SPECIAL_USE", "WAKE_LOCK",
            "POST_NOTIFICATIONS", "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
        )
        for (p in perms) {
            val granted = try {
                checkSelfPermission("android.permission.$p") == PackageManager.PERMISSION_GRANTED
            } catch (_: Throwable) { false }
            kv(p, if (granted) "GRANTED" else "DENIED")
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        kv("Battery-Whitelist", if (pm.isIgnoringBatteryOptimizations(packageName)) "YES" else "NO")

        section("NETWORK")
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val active = cm.activeNetwork
            val caps = active?.let { cm.getNetworkCapabilities(it) }
            val link = active?.let { cm.getLinkProperties(it) }
            kv("Transport", currentTransport())
            kv("Internet-Capable", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false)
            kv("Validated", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: false)
            val ips = currentLocalIps()
            if (ips.isNotEmpty()) kv("Local-IPs", ips.joinToString(", "))
            if (publicIp.isNotEmpty()) kv("Public-IP", publicIp)
            link?.interfaceName?.let { kv("Interface", it) }
            link?.dnsServers?.takeIf { it.isNotEmpty() }?.let {
                kv("DNS", it.mapNotNull { dns -> dns.hostAddress }.joinToString(", "))
            }
        } catch (e: Throwable) {
            kv("(error)", e.message)
        }

        section("RESOURCES")
        try {
            val freeGB = filesDir.usableSpace / 1024.0 / 1024.0 / 1024.0
            kv("Free-Storage", "%.2f GB (filesDir)".format(freeGB))
        } catch (_: Throwable) {}
        kv("Max-JVM-Heap-MB", Runtime.getRuntime().maxMemory() / 1024 / 1024)
        kv("Locale", Locale.getDefault())
        kv("Timezone", java.util.TimeZone.getDefault().id)

        sb.append('\n').append(line).append('\n')
        sb.append("LOG:\n")
        sb.append(line).append('\n')
        return sb.toString()
    }

    private fun formatDuration(ms: Long): String {
        if (ms < 0) return "—"
        val s = ms / 1000
        val d = s / 86400
        val h = (s % 86400) / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return when {
            d > 0 -> "${d}d ${h}h"
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${sec}s"
            else -> "${sec}s"
        }
    }

    private fun setLogsExpanded(expanded: Boolean) {
        svLogs.visibility = if (expanded) View.VISIBLE else View.GONE
        tvLogsChevron.text = if (expanded) "▲" else "▼"
    }

    private fun humanRate(bytesPerSec: Long): String {
        if (bytesPerSec < 0) return "—"
        val asBytes = getSharedPreferences("cfg", 0).getBoolean("speed_bytes", false)
        return if (asBytes) {
            when {
                bytesPerSec < 1024 -> "${bytesPerSec}B/s"
                bytesPerSec < 1024 * 1024 -> "${bytesPerSec / 1024}KB/s"
                bytesPerSec < 1024L * 1024 * 1024 -> "%.1fMB/s".format(bytesPerSec / 1024.0 / 1024.0)
                else -> "%.1fGB/s".format(bytesPerSec / 1024.0 / 1024.0 / 1024.0)
            }
        } else {
            val bits = bytesPerSec * 8
            when {
                bits < 1000 -> "${bits}b/s"
                bits < 1000 * 1000 -> "${bits / 1000}Kb/s"
                bits < 1000L * 1000 * 1000 -> "%.1fMb/s".format(bits / 1000.0 / 1000.0)
                else -> "%.1fGb/s".format(bits / 1000.0 / 1000.0 / 1000.0)
            }
        }
    }

    private fun refresh() {
        // readLiveProxyFiles wipes proxy_state + conn_info when the writer
        // process is dead (heartbeat in field 9 stale), so the rest of this
        // function naturally falls into the DISCONNECTED branch instead of
        // trusting stale CONNECTED + uptime + traffic-rate fields from a
        // killed :proxy (e.g., after a PACKAGE_REPLACED app update).
        val (proxyState, connInfo) = readLiveProxyFiles()
        val connStatus = connInfo.getOrNull(0) ?: ""
        val rxRate = connInfo.getOrNull(1)?.toLongOrNull() ?: -1L
        val txRate = connInfo.getOrNull(2)?.toLongOrNull() ?: -1L
        val registrator = connInfo.getOrNull(3).orEmpty()
        val tunnels = connInfo.getOrNull(4)?.toIntOrNull() ?: 0
        val connectedSinceMs = connInfo.getOrNull(5)?.toLongOrNull() ?: 0L
        // 7th field (uplink transport label, e.g. "QUIC", "TCP (splice)",
        // "TCP+yamux", "WebSocket") was added in v2.0.14-quic. Stay forward-
        // compatible with older conn_info files that only have 6 fields.
        val uplinkTransport = connInfo.getOrNull(6).orEmpty()
        // 8th field (cycle stage) is non-empty only during REBOOT auto-cycle.
        // When set, it overrides the normal status badge with "ROTATING · …"
        // so users see the rotation in progress instead of a misleading
        // RECONNECTING… that comes from the WS read error mid-cycle.
        val cycleStage = connInfo.getOrNull(7).orEmpty()
        // 9th field — Wi-Fi return relay status. Empty when relay disabled,
        // "wifi" when uplink is actively bound to a Wi-Fi Network,
        // "wifi_fallback" when the relay is up but no Wi-Fi held (sockets
        // fall through to cellular). See ProxyService.writeConnInfo.
        val wifiReturnStatus = connInfo.getOrNull(8).orEmpty()
        // Fields 12-17 — Wi-Fi return session byte counters (schema v2+).
        // wifiUp/wifiDown = bytes traversed the relay upstream socket while
        // bound to Wi-Fi (the savings); fallbackUp/fallbackDown = bytes
        // through the relay when no Wi-Fi was held (no savings); targetUp/
        // targetDown = bytes the native agent dialed to/from target hosts
        // (cellular when process bind is active). getOrNull keeps the read
        // forward-compat against older proxies that wrote fewer fields.
        val sessWifiUp = connInfo.getOrNull(12)?.toLongOrNull() ?: 0L
        val sessWifiDown = connInfo.getOrNull(13)?.toLongOrNull() ?: 0L
        val sessFallbackUp = connInfo.getOrNull(14)?.toLongOrNull() ?: 0L
        val sessFallbackDown = connInfo.getOrNull(15)?.toLongOrNull() ?: 0L
        val sessTargetUp = connInfo.getOrNull(16)?.toLongOrNull() ?: 0L
        val sessTargetDown = connInfo.getOrNull(17)?.toLongOrNull() ?: 0L

        val running = proxyState == "running" || proxyState == "starting"
        val configured = hasConnectionConfig()

        val pa = pendingAction
        if (pa != null) {
            // proxy_state="running" flips as soon as the subprocess starts —
            // before the WS actually dials. Wait for conn_info to advance past
            // STARTING/CONNECTING, or for proxy_state to indicate end-of-life.
            val resolved = when (pa) {
                "start" -> connStatus == "CONNECTED" || connStatus == "RECONNECTING" ||
                    connStatus == "ERROR" || proxyState == "error" ||
                    proxyState == "stopped" || proxyState == "auto_stopped"
                "stop" -> !running
                else -> true
            }
            if (resolved || System.currentTimeMillis() > pendingActionDeadlineMs) {
                pendingAction = null
            }
        }

        val transitioning = pendingAction != null || cyclingIp
        btnStart.text = when (pendingAction) {
            "start" -> "STARTING…"
            "stop" -> "STOPPING…"
            else -> if (running) "STOP" else "START"
        }
        btnStart.isEnabled = !transitioning
        btnStart.alpha = if (transitioning) 0.5f else 1f
        btnStart.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (!running && !configured && pendingAction == null) 0xFF666680.toInt()
            else 0xFFE94560.toInt()
        )

        val transport = currentTransport()
        val cycleEnabled = !cyclingIp && pendingAction == null &&
            canCycleMobileIp(transport)
        btnCycleIp.isEnabled = cycleEnabled
        btnCycleIp.alpha = if (cycleEnabled) 1f else 0.4f

        if (!cyclingIp) {
            val (label, color) = when {
                // REBOOT auto-cycle in flight — show the live stage. Beats
                // RECONNECTING…, which is technically what connStatus shows
                // (the WS dropped when we killed cellular), but tells the
                // user nothing about the rotation that's actually happening.
                cycleStage.isNotEmpty() ->
                    "ROTATING · $cycleStage".take(64) to 0xFFFFAA00.toInt()
                pendingAction == "stop" -> "STOPPING…" to 0xFFFFAA00.toInt()
                !running && !configured ->
                    "NOT CONFIGURED · TAP START TO IMPORT" to 0xFFFFAA00.toInt()
                proxyState == "error" -> "ERROR" to 0xFFFF4444.toInt()
                proxyState == "auto_stopped" -> {
                    val reason = readFile("stop_reason")
                    val text = if (reason.isNotEmpty()) "AUTO-STOPPED · $reason" else "AUTO-STOPPED"
                    text to 0xFFFFAA00.toInt()
                }
                connStatus == "CONNECTED" ->
                    "CONNECTED · ↓${humanRate(rxRate)} ↑${humanRate(txRate)}" to 0xFF00CC00.toInt()
                connStatus == "CONNECTING" -> "CONNECTING…" to 0xFFFFAA00.toInt()
                connStatus == "RECONNECTING" -> "RECONNECTING…" to 0xFFFFAA00.toInt()
                connStatus == "STARTING" || proxyState == "starting" -> "STARTING…" to 0xFFFFAA00.toInt()
                running -> "RUNNING" to 0xFF00CC00.toInt()
                pendingAction == "start" -> "STARTING…" to 0xFFFFAA00.toInt()
                else -> "DISCONNECTED" to 0xFFFF4444.toInt()
            }
            tvStatus.text = label
            tvStatus.setTextColor(color)
        }

        // In split mode the proxy exits through cellular while Wi-Fi is the
        // default route, so publicIp (fetched over the default route) is the
        // Wi-Fi IP and the raw transport says "WIFI" — both misleading for a
        // proxy whose identity is the mobile exit. When the relay is actually
        // up (field 8 = wifi / wifi_fallback), show the cellular exit IP and
        // label it accordingly instead.
        val splitActive = wifiReturnStatus == "wifi" || wifiReturnStatus == "wifi_fallback"
        val displayIp = if (splitActive) cellularExitIp().ifEmpty { publicIp } else publicIp
        val wan = displayIp.ifEmpty { "fetching…" }
        val transportLabel = when (wifiReturnStatus) {
            "wifi" -> "CELLULAR · Wi-Fi return"
            "wifi_fallback" -> "CELLULAR · Wi-Fi return (fallback)"
            else -> transport
        }
        tvNetwork.text = "$wan  ·  $transportLabel"

        if (pendingAction != "stop" && connStatus == "CONNECTED" && registrator.isNotEmpty()) {
            try {
                registratorPanel.visibility = View.VISIBLE
                pagerRefs.tvRegistrator?.text = registrator
                pagerRefs.tvUptime?.text = if (connectedSinceMs > 0)
                    "up ${formatDuration(System.currentTimeMillis() - connectedSinceMs)}"
                else ""
                // Transport prefix sits at the front of the activity line so
                // "QUIC" / "TCP (splice)" / "WebSocket" is always visible
                // while connected. Empty for older agents that don't publish it.
                val transportPrefix = if (uplinkTransport.isNotEmpty()) "$uplinkTransport · " else ""
                pagerRefs.tvActivity?.text = when {
                    tunnels == 0 -> "◦ ${transportPrefix}idle — no connections"
                    else -> "⚡ $transportPrefix$tunnels ${if (tunnels == 1) "connection" else "connections"} · " +
                        "↓${humanRate(rxRate)} ↑${humanRate(txRate)}"
                }
                // Wi-Fi return indicator + two-IP detail block. The primary
                // line (tvUplinkVia) summarises state and link characteristics;
                // the detail line (tvUplinkDetail) shows the two public IPs
                // proving split routing works. Both populate from
                // conn_info field 8 + wifi_info.json (cached on disk by
                // ProxyService).
                updateWifiReturnPanel(
                    wifiReturnStatus,
                    sessWifiUp, sessWifiDown,
                    sessFallbackUp, sessFallbackDown,
                    sessTargetUp, sessTargetDown,
                )
                // Refresh charts at most every 30s — they cover 24h, sub-minute updates
                // are visually pointless and re-reading the JSONL on every tick wastes IO.
                val nowMs = System.currentTimeMillis()
                if (registratorPager.currentItem != 0 &&
                    nowMs - lastPanelDataAtMs > 30_000L) {
                    lastPanelDataAtMs = nowMs
                    refreshPanelCharts()
                }
            } catch (e: Throwable) {
                android.util.Log.e("ProxyAgent", "panel refresh failed", e)
            }
        } else {
            try { registratorPanel.visibility = View.GONE } catch (_: Throwable) {}
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

    private fun updatePagerDots(active: Int) {
        val dots = arrayOf(dot0, dot1, dot2, dot3)
        for (i in dots.indices) {
            dots[i].setBackgroundColor(if (i == active) 0xFF88FFAA.toInt() else 0x33FFFFFF.toInt())
        }
    }

    // Renders the Wi-Fi return widget block based on conn_info field 8
    // (current relay state) and wifi_info.json (cached self-test result +
    // link info). Pure UI; called from refresh() on each tick.
    //
    // States:
    //   ""              — relay disabled in settings, both views GONE.
    //   "wifi"          — relay up and on Wi-Fi. Primary line shows speed/
    //                     band/standard; detail block shows the two IPs.
    //   "wifi_fallback" — relay up but no Wi-Fi held. Amber. Detail block
    //                     hidden because there's nothing useful to show
    //                     (cellular-only flow).
    //   "split_failed"  — self-test rejected the relay. Red, sticky.
    //                     Detail shows last known IPs from wifi_info.json
    //                     when available, with a clear "ignored" note.
    private fun updateWifiReturnPanel(
        status: String,
        wifiUp: Long, wifiDown: Long,
        fallbackUp: Long, fallbackDown: Long,
        targetUp: Long, targetDown: Long,
    ) {
        val viaView = pagerRefs.tvUplinkVia ?: return
        val detailView = pagerRefs.tvUplinkDetail ?: return
        val sessView = pagerRefs.tvSessionTraffic

        if (status.isEmpty()) {
            viaView.visibility = View.GONE
            detailView.visibility = View.GONE
            sessView?.visibility = View.GONE
            return
        }

        val info = readWifiInfoJson()
        when (status) {
            "wifi" -> {
                viaView.visibility = View.VISIBLE
                viaView.setTextColor(0xFF66E0FF.toInt())
                viaView.text = formatWifiLinkLine(info, prefix = "↺ uplink: Wi-Fi")
                val ipBlock = formatTwoIpBlock(info)
                if (ipBlock != null) {
                    detailView.visibility = View.VISIBLE
                    detailView.setTextColor(0xFF88FFAA.toInt())
                    detailView.text = ipBlock
                } else {
                    detailView.visibility = View.GONE
                }
            }
            "wifi_fallback" -> {
                viaView.visibility = View.VISIBLE
                viaView.setTextColor(0xFFFFCC66.toInt())
                viaView.text = "↺ uplink via cellular · Wi-Fi return enabled but no Wi-Fi held"
                detailView.visibility = View.GONE
            }
            "leak_known" -> {
                // BINARY engine: uplink savings work (relay forwards via
                // Wi-Fi), but target dials inside the subprocess leak the
                // Wi-Fi IP. Show the line in amber with the caveat, plus
                // the two-IP block so the user can see exactly which IP
                // each side is exposing.
                viaView.visibility = View.VISIBLE
                viaView.setTextColor(0xFFFFCC66.toInt())
                viaView.text = formatWifiLinkLine(info, prefix = "⚠ uplink: Wi-Fi (target dials leak Wi-Fi IP)")
                val ipBlock = formatTwoIpBlock(info)
                if (ipBlock != null) {
                    detailView.visibility = View.VISIBLE
                    detailView.setTextColor(0xFFFFCC66.toInt())
                    detailView.text = "$ipBlock\n  ⚠ BINARY engine — target sees Wi-Fi IP, not cellular"
                } else {
                    detailView.visibility = View.GONE
                }
            }
            "split_failed" -> {
                viaView.visibility = View.VISIBLE
                viaView.setTextColor(0xFFFF6666.toInt())
                viaView.text = "✗ Wi-Fi return DISABLED · split routing not confirmed"
                val ipBlock = formatTwoIpBlock(info)
                if (ipBlock != null) {
                    detailView.visibility = View.VISIBLE
                    detailView.setTextColor(0xFFFF9999.toInt())
                    detailView.text =
                        "$ipBlock\n  ⚠ both IPs equal — OS suppresses cellular while Wi-Fi up"
                } else {
                    detailView.visibility = View.VISIBLE
                    detailView.setTextColor(0xFFFF9999.toInt())
                    detailView.text =
                        "  ⚠ OS suppresses cellular while Wi-Fi up — relay can't split traffic"
                }
            }
            else -> {
                viaView.visibility = View.GONE
                detailView.visibility = View.GONE
            }
        }

        // Session traffic line. Shown only when the relay is up in any
        // capacity (not "split_failed" — relay is dead there) and at
        // least one counter is non-zero. Format examples:
        //   "Session: Wi-Fi ↑12.4M ↓48.1M · Cell ↑8.2M ↓2.1M · saved 60.5M"
        //   "Session: Cell-fallback ↑5.0M ↓20.1M · target ↑8.2M ↓2.1M"  (relay in fallback)
        //   "Session: target ↑8.2M ↓2.1M"  (split_failed — only target counter meaningful)
        if (sessView != null) {
            val showSession = status != "" && (
                wifiUp + wifiDown + fallbackUp + fallbackDown + targetUp + targetDown > 0L
            )
            if (!showSession) {
                sessView.visibility = View.GONE
            } else {
                sessView.visibility = View.VISIBLE
                sessView.text = formatSessionTrafficLine(
                    status, wifiUp, wifiDown, fallbackUp, fallbackDown, targetUp, targetDown,
                )
                // Match the colour of the main status line so the block reads
                // as one — cyan for healthy, amber for warning, red for split_failed.
                sessView.setTextColor(when (status) {
                    "wifi" -> 0xFF88FFAA.toInt()
                    "wifi_fallback", "leak_known" -> 0xFFFFCC66.toInt()
                    "split_failed" -> 0xFFFF9999.toInt()
                    else -> 0xFF88FFAA.toInt()
                })
            }
        }
    }

    // Builds the session-traffic widget line. Suppresses zero blocks so
    // the line stays compact (e.g. when relay is in fallback the Wi-Fi
    // counters are 0 — we drop them and just show fallback + target).
    private fun formatSessionTrafficLine(
        status: String,
        wifiUp: Long, wifiDown: Long,
        fallbackUp: Long, fallbackDown: Long,
        targetUp: Long, targetDown: Long,
    ): String {
        val parts = mutableListOf<String>()
        if (wifiUp + wifiDown > 0L) {
            parts.add("Wi-Fi ↑${humanBytesCompact(wifiUp)} ↓${humanBytesCompact(wifiDown)}")
        }
        if (fallbackUp + fallbackDown > 0L) {
            parts.add("Cell-fb ↑${humanBytesCompact(fallbackUp)} ↓${humanBytesCompact(fallbackDown)}")
        }
        if (targetUp + targetDown > 0L) {
            parts.add("target ↑${humanBytesCompact(targetUp)} ↓${humanBytesCompact(targetDown)}")
        }
        val main = parts.joinToString(" · ")
        // Only show "saved" when the relay actually rode Wi-Fi for some
        // bytes. In fallback or split_failed there's nothing saved.
        val saved = wifiUp + wifiDown
        val tail = if (saved > 0L && status == "wifi") "  ·  saved ${humanBytesCompact(saved)}" else ""
        return "Session: $main$tail"
    }

    // Compact "12.4M" / "768K" / "503" formatter for the widget line.
    // Keeps each leg short so 4 of them fit on a phone width without wrap.
    private fun humanBytesCompact(n: Long): String {
        if (n < 1024) return n.toString()
        if (n < 1024 * 1024) return "${(n + 512) / 1024}K"
        val mb = n.toDouble() / (1024.0 * 1024.0)
        return if (mb >= 100) "%.0fM".format(Locale.US, mb)
            else "%.1fM".format(Locale.US, mb)
    }

    // Builds the headline link description: "↺ uplink: Wi-Fi · 433 Mbps ·
    // 5 GHz · Wi-Fi 5". Missing fields are dropped gracefully so the line
    // never has empty segments. Returns the prefix alone if no Wi-Fi info
    // is on disk yet (e.g. self-test still running).
    private fun formatWifiLinkLine(info: org.json.JSONObject?, prefix: String): String {
        if (info == null) return prefix
        val parts = mutableListOf(prefix)
        val speed = info.optInt("link_speed_mbps", -1)
        if (speed > 0) parts.add("$speed Mbps")
        val band = info.optString("band", "")
        if (band.isNotEmpty() && band != "unknown") parts.add(band)
        val std = info.optString("standard", "")
        if (std.isNotEmpty() && std != "unknown") parts.add(std)
        return parts.joinToString(" · ")
    }

    // Two-line block showing cellular exit IP (what targets see) + Wi-Fi
    // public IP (what the registrator sees). Null when wifi_info.json is
    // missing or has neither IP — caller decides whether to hide the
    // detail view or substitute a status-specific message.
    private fun formatTwoIpBlock(info: org.json.JSONObject?): String? {
        if (info == null) return null
        val cellIp = info.optString("public_ip_cell", "").ifEmpty {
            // Fallback to the long-lived nat_ip file — that's what targets
            // observe and the IP doesn't change unless we cycle.
            readFile("nat_ip").trim()
        }
        val wifiIp = info.optString("public_ip_wifi", "")
        if (cellIp.isEmpty() && wifiIp.isEmpty()) return null
        val sb = StringBuilder()
        if (cellIp.isNotEmpty()) sb.append("  ↓ exit:   $cellIp (cellular)")
        if (wifiIp.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append("  ↑ uplink: $wifiIp (Wi-Fi)")
        }
        return sb.toString()
    }

    // The mobile exit IP — what targets actually see — for split mode, where
    // the proxy egresses through cellular even though Wi-Fi is the default
    // route. Prefers the last self-test's public_ip_cell, falling back to the
    // durable nat_ip file (written on every cycle + proxy connect). Same
    // ordering as formatTwoIpBlock so the top line and the detail view agree.
    // Empty when neither source is populated yet.
    private fun cellularExitIp(): String {
        val fromJson = readWifiInfoJson()?.optString("public_ip_cell", "").orEmpty()
        if (fromJson.isNotEmpty()) return fromJson
        return readFile("nat_ip").trim()
    }

    private fun readWifiInfoJson(): org.json.JSONObject? {
        return try {
            val f = File(filesDir, "wifi_info.json")
            if (!f.exists()) return null
            org.json.JSONObject(f.readText())
        } catch (_: Throwable) { null }
    }

    // Reads the last 24h of buckets and feeds them to the three mini charts
    // (traffic, connections, rotations). Done off the UI thread because IO
    // can be slow on cold cache; results are pushed back via runOnUiThread.
    private fun refreshPanelCharts() {
        Thread {
            try {
                val now = System.currentTimeMillis()
                val from = now - 24 * 60 * 60_000L
                val binMs = AnalyticsStore.BUCKET_MS * 10
                val buckets = AnalyticsStore.load(this, from, now)
                val (trafficSeries, trafficStartMs, trafficStepMs, trafficTotalBytes) =
                    aggregateForPanel(buckets, from, now, binMs) { it.rxBytes + it.txBytes }
                val (connSeries, connStartMs, connStepMs, connTotalEvents) =
                    aggregateForPanel(buckets, from, now, binMs) {
                        (it.opens + it.closes).toLong()
                    }
                // Rotations: two stacked series from cycle_events.jsonl.
                val cycleEvents = AnalyticsStore.loadCycleEvents(this, from, now)
                val rotSeries = aggregateCyclesForPanel(cycleEvents, from, now, binMs)
                val rotManual = rotSeries.manual
                val rotAuto = rotSeries.auto
                val rotStartMs = rotSeries.startMs
                val rotStepMs = rotSeries.stepMs
                val rotManualTotal = rotSeries.manualTotal
                val rotAutoTotal = rotSeries.autoTotal
                val rotChangedTotal = rotSeries.changedTotal
                runOnUiThread {
                    pagerRefs.trafficChart?.let { ch ->
                        ch.setSeries(trafficSeries, trafficStartMs, trafficStepMs)
                        ch.setYLabelFormatter { v -> formatBytesShort(v.toLong()) }
                    }
                    pagerRefs.trafficTotal?.text = humanBytes(trafficTotalBytes)
                    pagerRefs.connChart?.let { ch ->
                        ch.setSeries(connSeries, connStartMs, connStepMs)
                        ch.setYLabelFormatter { v -> "%.0f".format(v) }
                    }
                    pagerRefs.connTotal?.text = "$connTotalEvents events"
                    pagerRefs.rotChart?.let { ch ->
                        ch.setStackedSeries(rotManual, rotAuto, rotStartMs, rotStepMs)
                        ch.setYLabelFormatter { v -> "%.0f".format(v) }
                    }
                    // "M+A · K→IP" — total attempts (manual + auto) and how
                    // many of them actually moved the IP. Compact enough to fit
                    // next to the chart title even at narrow widths.
                    pagerRefs.rotTotal?.text =
                        "$rotManualTotal m + $rotAutoTotal a · $rotChangedTotal →IP"
                }
            } catch (_: Throwable) {}
        }.apply { isDaemon = true; name = "PanelChartLoad"; start() }
    }

    // Tuple result for aggregateCyclesForPanel — two parallel series (manual
    // and auto counts per bin), the time axis, per-kind totals, plus the
    // count of attempts that actually moved the IP (`changed` flag true).
    private data class CycleSeries(
        val manual: DoubleArray,
        val auto: DoubleArray,
        val startMs: Long,
        val stepMs: Long,
        val manualTotal: Int,
        val autoTotal: Int,
        val changedTotal: Int,
    )

    private fun aggregateCyclesForPanel(
        events: List<CycleEvent>,
        fromMs: Long,
        toMs: Long,
        binMs: Long,
    ): CycleSeries {
        val n = ((toMs - fromMs) / binMs).toInt().coerceAtLeast(1)
        val manual = DoubleArray(n)
        val auto = DoubleArray(n)
        var mTotal = 0
        var aTotal = 0
        var changedTotal = 0
        for (e in events) {
            val idx = ((e.tMs - fromMs) / binMs).toInt()
            if (idx < 0 || idx >= n) continue
            when (e.kind) {
                AnalyticsStore.CYCLE_MANUAL -> { manual[idx] += 1.0; mTotal++ }
                AnalyticsStore.CYCLE_AUTO -> { auto[idx] += 1.0; aTotal++ }
            }
            if (e.changed) changedTotal++
        }
        return CycleSeries(manual, auto, fromMs, binMs, mTotal, aTotal, changedTotal)
    }

    // Shared helper: fold bucket list into a fixed-size series spanning
    // [fromMs, toMs] using `binMs` width per cell, plus the running total.
    private fun aggregateForPanel(
        buckets: List<AnalyticsBucket>,
        fromMs: Long,
        toMs: Long,
        binMs: Long,
        valueOf: (AnalyticsBucket) -> Long,
    ): SeriesResult {
        val n = ((toMs - fromMs) / binMs).toInt().coerceAtLeast(1)
        val arr = DoubleArray(n)
        var total = 0L
        for (b in buckets) {
            val idx = ((b.tMs - fromMs) / binMs).toInt()
            if (idx < 0 || idx >= n) continue
            val v = valueOf(b)
            arr[idx] += v.toDouble()
            total += v
        }
        return SeriesResult(arr, fromMs, binMs, total)
    }

    data class SeriesResult(val series: DoubleArray, val startMs: Long, val stepMs: Long, val total: Long)

    private fun humanBytes(b: Long): String {
        val abs = if (b < 0) 0L else b
        return when {
            abs < 1024 -> "${abs} B"
            abs < 1024L * 1024 -> "%.1f KB".format(abs / 1024.0)
            abs < 1024L * 1024 * 1024 -> "%.1f MB".format(abs / 1024.0 / 1024.0)
            else -> "%.2f GB".format(abs / 1024.0 / 1024.0 / 1024.0)
        }
    }

    private fun formatBytesShort(b: Long): String {
        return when {
            b >= 1024L * 1024 * 1024 -> "%.1fG".format(b / 1024.0 / 1024.0 / 1024.0)
            b >= 1024L * 1024 -> "%.1fM".format(b / 1024.0 / 1024.0)
            b >= 1024 -> "%.0fK".format(b / 1024.0)
            else -> "$b"
        }
    }

    private fun readFile(name: String): String {
        return try { File(filesDir, name).readText().trim() } catch (_: Throwable) { "" }
    }

    // Returns (proxy_state, conn_info parts) only when the :proxy process is
    // actually alive — verified via the wall-clock heartbeat in conn_info
    // field 9. If the heartbeat is missing or older than STALE_CONN_INFO_MS,
    // the writer is dead (typically because PACKAGE_REPLACED killed it
    // mid-session without running doStop) and the file content is no longer
    // trustworthy: connectedSinceMs would still parse fine and produce a
    // monotonically-growing fake "uptime", and the CONNECTED badge would
    // stick forever. Wiping both files here is what flips the UI back to
    // DISCONNECTED and lets toggle()/cycleMobileIp() take their "not
    // running" branches on the next user action.
    private fun readLiveProxyFiles(): Pair<String, List<String>> {
        val proxyState = readFile("proxy_state")
        val raw = readFile("conn_info")
        if (raw.isEmpty()) return proxyState to emptyList()
        val parts = raw.split("|")
        val heartbeatMs = parts.getOrNull(9)?.toLongOrNull() ?: 0L
        val stale = heartbeatMs == 0L ||
            (System.currentTimeMillis() - heartbeatMs) > STALE_CONN_INFO_MS
        if (!stale) return proxyState to parts
        // conn_info is stale → the live fields (connectedSinceMs, rates,
        // tunnel count) no longer match reality, drop them. proxy_state is
        // kept iff it holds a terminal value that the writer set as its
        // last act before dying ("stopped" / "auto_stopped" / "error") —
        // those are sticky on purpose so the UI keeps showing "AUTO-
        // STOPPED · Battery 5%" or "ERROR" after the process has long
        // exited. "running" / "starting" get wiped because they're live-
        // state markers the writer never got to retire (typical case
        // after a PACKAGE_REPLACED kill of an active session).
        try { File(filesDir, "conn_info").delete() } catch (_: Throwable) {}
        val keepState = proxyState in TERMINAL_PROXY_STATES
        if (!keepState) {
            try { File(filesDir, "proxy_state").delete() } catch (_: Throwable) {}
        }
        return (if (keepState) proxyState else "") to emptyList()
    }

    companion object {
        // conn_info field 9 carries a ~1Hz wall-clock heartbeat written by
        // ProxyService's status updater thread. If the latest write is older
        // than this threshold, :proxy is dead and the file is treated as
        // void. Headroom over the 1s tick covers slow devices and UI stalls
        // during long GC pauses; setting it too low would risk wiping a
        // live state on a temporarily-blocked writer.
        private const val STALE_CONN_INFO_MS = 5_000L

        // proxy_state values the writer chose deliberately as its final
        // state. readLiveProxyFiles keeps these even when the heartbeat is
        // stale so the UI can show "AUTO-STOPPED · Battery 5%" / "ERROR"
        // after the process has exited; non-terminal "running"/"starting"
        // get wiped as a live-state lie.
        private val TERMINAL_PROXY_STATES = setOf("stopped", "auto_stopped", "error")
    }
}
