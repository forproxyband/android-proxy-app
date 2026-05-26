package com.proxyagent.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.viewpager2.widget.ViewPager2
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
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

    private val importLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importSettingsFromUri(uri)
        }

    // Holds references to the open settings dialog so the QR-scan result can
    // populate fields without re-creating the dialog. Cleared on dismiss.
    private var dlgEtHost: EditText? = null
    private var dlgEtPort: EditText? = null
    private var dlgEtKey: EditText? = null
    private var dlgEtId: EditText? = null
    private var dlgEtDns: EditText? = null
    private var dlgRbModeModem: RadioButton? = null

    private val qrLauncher: ActivityResultLauncher<ScanOptions> =
        registerForActivityResult(ScanContract()) { result ->
            val text = result?.contents
            if (text.isNullOrBlank()) {
                Toast.makeText(this, "QR scan cancelled", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            applyQrPayload(text)
        }

    // Falls back to picking a QR image from gallery when the live camera scan
    // can't lock on (dense codes, autofocus issues). Decoded via ZXing core.
    private val qrImageLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@registerForActivityResult
            decodeQrFromUri(uri)
        }

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

        val prefs = getSharedPreferences("cfg", 0)

        setupBatteryThresholdSpinner(prefs)
        btnStart.setOnClickListener { toggle() }
        btnCycleIp.setOnClickListener { cycleMobileIp() }
        btnSettings.setOnClickListener { showSettingsDialog() }
        btnAnalytics.setOnClickListener {
            startActivity(Intent(this, AnalyticsActivity::class.java))
        }
        btnBattery.setOnClickListener { requestBatteryWhitelist() }

        // Drop day-files older than the user's retention setting (default 30d).
        Thread { try { AnalyticsStore.pruneToRetention(this) } catch (_: Throwable) {} }
            .apply { isDaemon = true; name = "AnalyticsPrune"; start() }

        // Mirror current cycle config from SharedPreferences into the
        // cross-process file. Back-fill for users who upgraded but haven't
        // opened Settings again, and a no-op refresh for everyone else.
        // Without this, :proxy reads default CycleConfig (all-false) until
        // the user explicitly re-saves the settings dialog.
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
        val etId = view.findViewById<EditText>(R.id.etId)
        val etDns = view.findViewById<EditText>(R.id.etDns)
        val cbSpeedBytes = view.findViewById<CheckBox>(R.id.cbSpeedBytes)
        val spRetention = view.findViewById<Spinner>(R.id.spRetention)
        val rgEngine = view.findViewById<RadioGroup>(R.id.rgEngine)
        val rbEngineNative = view.findViewById<RadioButton>(R.id.rbEngineNative)
        val rbEngineBinary = view.findViewById<RadioButton>(R.id.rbEngineBinary)
        val rbEngineAar = view.findViewById<RadioButton>(R.id.rbEngineAar)
        val rgMode = view.findViewById<RadioGroup>(R.id.rgMode)
        val rbModeModem = view.findViewById<RadioButton>(R.id.rbModeModem)
        val rbModeBalancer = view.findViewById<RadioButton>(R.id.rbModeBalancer)
        val btnImport = view.findViewById<Button>(R.id.btnImport)
        val btnExport = view.findViewById<Button>(R.id.btnExport)
        val btnScanQr = view.findViewById<Button>(R.id.btnScanQr)
        val tvScanQrHint = view.findViewById<TextView>(R.id.tvScanQrHint)
        val cbApnSwap = view.findViewById<CheckBox>(R.id.cbApnSwap)
        val cbImeiRotate = view.findViewById<CheckBox>(R.id.cbImeiRotate)
        val spImeiMethod = view.findViewById<Spinner>(R.id.spImeiMethod)
        val etImeiCustomCmd = view.findViewById<EditText>(R.id.etImeiCustomCmd)
        val cbWifiReturn = view.findViewById<CheckBox>(R.id.cbWifiReturn)
        val tvWifiReturnHint = view.findViewById<TextView>(R.id.tvWifiReturnHint)
        val prefs = getSharedPreferences("cfg", 0)

        // Wi-Fi return is Modem-only on this iteration: the Balancer path
        // dials the registrator picked from the JSON balancer reply *after*
        // env was set, so a loopback relay that only sees the balancer GET
        // wouldn't catch the real uplink. Disable the checkbox + grey out
        // the hint when Balancer is selected; checkbox state is preserved
        // in SharedPreferences either way so toggling back to Modem brings
        // it back.
        fun applyWifiReturnEnabled(modemMode: Boolean) {
            cbWifiReturn.isEnabled = modemMode
            tvWifiReturnHint.alpha = if (modemMode) 1f else 0.5f
            cbWifiReturn.text = if (modemMode) {
                "Return traffic to client over Wi-Fi"
            } else {
                "Return traffic to client over Wi-Fi  (Modem mode only)"
            }
        }

        // Wi-Fi return requires an in-process engine (NATIVE or AAR) —
        // bindProcessToNetwork(cellular) sets a per-process default
        // route that doesn't survive ProcessBuilder fork+exec, so a
        // BINARY subprocess wouldn't inherit it and target dials would
        // leak through Wi-Fi (default route on dual-transport devices).
        // We gate the engine radio accordingly: when the Wi-Fi return
        // checkbox is on, BINARY is disabled and NATIVE is auto-
        // selected if no in-process engine is currently picked.
        fun applyEngineGateForWifiReturn() {
            val wifiOn = cbWifiReturn.isChecked && cbWifiReturn.isEnabled
            rbEngineBinary.isEnabled = !wifiOn
            if (wifiOn && !rbEngineNative.isChecked && !rbEngineAar.isChecked) {
                rbEngineNative.isChecked = true
            }
            rbEngineBinary.text = if (wifiOn) {
                "Binary subprocess  (disabled — needs in-process engine for Wi-Fi return)"
            } else {
                "Binary subprocess  (testing — no Wi-Fi return)"
            }
        }

        fun applyModeVisibility(modemMode: Boolean) {
            etId.visibility = if (modemMode) View.VISIBLE else View.GONE
            btnScanQr.visibility = if (modemMode) View.VISIBLE else View.GONE
            tvScanQrHint.visibility = if (modemMode) View.VISIBLE else View.GONE
            applyWifiReturnEnabled(modemMode)
        }

        val retentionLabels = arrayOf("Day (1)", "Week (7)", "Month (30)")
        val retentionDays = intArrayOf(1, 7, 30)
        run {
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, retentionLabels)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spRetention.adapter = adapter
        }

        val imeiMethodLabels = arrayOf(
            "Custom shell command",
            "resetprop random IMEI (MagiskHide Props)",
            "magisk-imei --random",
        )
        val imeiMethodKeys = arrayOf("custom", "props", "magisk-imei")
        run {
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, imeiMethodLabels)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spImeiMethod.adapter = adapter
        }

        fun applyImeiVisibility() {
            val customSelected = imeiMethodKeys
                .getOrNull(spImeiMethod.selectedItemPosition) == "custom"
            etImeiCustomCmd.visibility =
                if (cbImeiRotate.isChecked && customSelected) View.VISIBLE else View.GONE
            spImeiMethod.isEnabled = cbImeiRotate.isChecked
        }

        fun loadFromPrefs() {
            etHost.setText(prefs.getString("h", ""))
            etPort.setText(prefs.getString("p", ""))
            etKey.setText(prefs.getString("k", ""))
            etId.setText(prefs.getString("id", ""))
            etDns.setText(prefs.getString("dns", ""))
            cbSpeedBytes.isChecked = prefs.getBoolean("speed_bytes", false)
            val savedRet = prefs.getInt("analytics_retention_days", 30)
            val rIdx = retentionDays.indexOf(savedRet).let { if (it < 0) 2 else it }
            spRetention.setSelection(rIdx)
            when (prefs.getString("engine", "native")) {
                "aar" -> rbEngineAar.isChecked = true
                "binary" -> rbEngineBinary.isChecked = true
                else -> rbEngineNative.isChecked = true     // "native" or unset
            }
            val modemMode = prefs.getString("mode", "modem") == "modem"
            if (modemMode) rbModeModem.isChecked = true else rbModeBalancer.isChecked = true
            applyModeVisibility(modemMode)
            cbApnSwap.isChecked = prefs.getBoolean("apn_swap", false)
            cbImeiRotate.isChecked = prefs.getBoolean("imei_rotate", false)
            val savedMethod = prefs.getString("imei_method", "custom") ?: "custom"
            val mIdx = imeiMethodKeys.indexOf(savedMethod).let { if (it < 0) 0 else it }
            spImeiMethod.setSelection(mIdx)
            etImeiCustomCmd.setText(prefs.getString("imei_cmd", ""))
            applyImeiVisibility()
            cbWifiReturn.isChecked = prefs.getBoolean("wifi_return", false)
            applyEngineGateForWifiReturn()
        }
        loadFromPrefs()

        rgMode.setOnCheckedChangeListener { _, checkedId ->
            applyModeVisibility(checkedId == R.id.rbModeModem)
        }
        // Wi-Fi return hard gate: the user can't leave the box ticked on a
        // device that won't actually split traffic. Three rejection paths:
        //   1. wifi_info.json shows a recent (<24h) SAME_IP self-test —
        //      device is known-bad; we don't even run preflight, just
        //      show the "already verified, fix mobile_data_always_on
        //      first" dialog and uncheck.
        //   2. Preflight returns BLOCKED and the user doesn't take the
        //      auto-fix path (Ignore, Show instructions, or cancel) →
        //      uncheck. Only a successful "Enable" keeps it checked.
        //   3. Preflight returns BLOCKED and tryEnable() fails (ROM
        //      rejected the write despite WRITE_SECURE_SETTINGS) → uncheck.
        //
        // We deliberately don't auto-uncheck on UNKNOWN (no SIM /
        // Wi-Fi-only device) — that's a separate concern from "split
        // routing doesn't work", and the user may legitimately want to
        // pre-configure the toggle.
        cbWifiReturn.setOnCheckedChangeListener { _, isChecked ->
            // Whether the checkbox came on or off, the engine gate must
            // refresh — turning the box off should re-enable BINARY, and
            // turning it on must force AAR even before preflight returns.
            applyEngineGateForWifiReturn()
            if (!isChecked || !cbWifiReturn.isEnabled) return@setOnCheckedChangeListener
            // Fast path: known-bad device from a previous self-test.
            // Reading wifi_info.json on the UI thread is cheap (small
            // file, fits in cache), no need to background it.
            if (refuseDueToCachedSplitFail(cbWifiReturn)) return@setOnCheckedChangeListener
            runMobileDataAlwaysOnPreflight(cbWifiReturn)
        }
        cbImeiRotate.setOnCheckedChangeListener { _, _ -> applyImeiVisibility() }
        spImeiMethod.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                applyImeiVisibility()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) { applyImeiVisibility() }
        }

        // Expose the dialog widgets to the QR-result callback.
        dlgEtHost = etHost; dlgEtPort = etPort; dlgEtKey = etKey
        dlgEtId = etId; dlgEtDns = etDns; dlgRbModeModem = rbModeModem

        val dialog = AlertDialog.Builder(this)
            .setTitle("Connection settings")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val h = etHost.text.toString().trim()
                val p = etPort.text.toString().trim()
                val k = etKey.text.toString().trim()
                val id = etId.text.toString().trim()
                val d = etDns.text.toString().trim()
                if (h.isEmpty() || p.isEmpty() || k.isEmpty()) {
                    Toast.makeText(this, "Host / Port / Key are required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val speedBytes = cbSpeedBytes.isChecked
                val newRetention = retentionDays[
                    spRetention.selectedItemPosition.coerceIn(0, retentionDays.size - 1)]
                val retentionChanged = prefs.getInt("analytics_retention_days", 30) != newRetention
                val newEngine = when (rgEngine.checkedRadioButtonId) {
                    R.id.rbEngineAar -> "aar"
                    R.id.rbEngineBinary -> "binary"
                    else -> "native"   // rbEngineNative or nothing checked
                }
                val newMode = if (rgMode.checkedRadioButtonId == R.id.rbModeBalancer) "balancer" else "modem"
                val imeiMethodKey = imeiMethodKeys[
                    spImeiMethod.selectedItemPosition.coerceIn(0, imeiMethodKeys.size - 1)]
                // wifi_return is gated to Modem mode — if the user flipped
                // to Balancer while the box was ticked (the box gets
                // disabled but its in-memory state is still "checked"), we
                // store false anyway so ProxyService doesn't try to spin up
                // a relay that can't intercept the balancer-discovered
                // registrator.
                val effectiveWifiReturn = cbWifiReturn.isChecked && newMode == "modem"
                // Wi-Fi return REQUIRES an in-process engine (NATIVE or
                // AAR) — see runBinaryEngine guard + bindProcessToNetwork
                // comment in ProxyService. If we somehow end up saving
                // with wifi_return=true and engine=binary (e.g. stale
                // dialog state or pref tampering), the ProxyService
                // guard would silently disable the relay, leaving the
                // user confused. Clamp it here to NATIVE.
                val effectiveEngine = if (effectiveWifiReturn && newEngine == "binary") "native" else newEngine
                val engineClampedForWifiReturn = effectiveWifiReturn && newEngine == "binary"
                // engineChanged tracks what actually goes into prefs — i.e.
                // effectiveEngine, not the radio's nominal newEngine. That
                // way the "stop & restart to apply" hint fires even when
                // engine was clamped by the Wi-Fi return gate.
                val engineChanged = prefs.getString("engine", "native") != effectiveEngine
                val modeChanged = prefs.getString("mode", "modem") != newMode
                prefs.edit()
                    .putString("h", h).putString("p", p).putString("k", k)
                    .putString("id", id).putString("dns", d)
                    .putBoolean("speed_bytes", speedBytes)
                    .putInt("analytics_retention_days", newRetention)
                    .putString("engine", effectiveEngine)
                    .putString("mode", newMode)
                    .putBoolean("apn_swap", cbApnSwap.isChecked)
                    .putBoolean("imei_rotate", cbImeiRotate.isChecked)
                    .putString("imei_method", imeiMethodKey)
                    .putString("imei_cmd", etImeiCustomCmd.text.toString().trim())
                    .putBoolean("wifi_return", effectiveWifiReturn)
                    .apply()
                if (engineClampedForWifiReturn) {
                    Toast.makeText(
                        this,
                        "Wi-Fi return requires an in-process engine — switched to Native",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                // Mirror cycle config into the cross-process file so
                // ProxyService (:proxy) can see the toggle changes — its
                // SharedPreferences in-memory cache otherwise stays stale.
                IpCycle.saveConfigToFile(
                    this,
                    IpCycle.CycleConfig(
                        apnSwap = cbApnSwap.isChecked,
                        imeiRotation = cbImeiRotate.isChecked,
                        imeiMethod = imeiMethodKey,
                        imeiCustomCmd = etImeiCustomCmd.text.toString().trim(),
                        wifiReturn = effectiveWifiReturn,
                        wifiReturnMethod = prefs.getString("wifi_return_method", "local_relay")
                            ?: "local_relay",
                    ),
                )
                if (retentionChanged) {
                    Thread { try { AnalyticsStore.pruneToRetention(this) } catch (_: Throwable) {} }
                        .apply { isDaemon = true; name = "AnalyticsPruneOnSave"; start() }
                }
                try { File(filesDir, "speed_units").writeText(if (speedBytes) "bytes" else "bits") }
                catch (_: Throwable) {}
                val running = readFile("proxy_state").let { it == "running" || it == "starting" }
                val msg = when {
                    running && (engineChanged || modeChanged) -> "Saved — stop & restart to apply"
                    running -> "Saved — restart agent to apply"
                    else -> "Saved"
                }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener {
                dlgEtHost = null; dlgEtPort = null; dlgEtKey = null
                dlgEtId = null; dlgEtDns = null; dlgRbModeModem = null
            }
            .create()

        btnExport.setOnClickListener {
            // Export *current dialog state* (user may have edited but not saved).
            val mode = if (rgMode.checkedRadioButtonId == R.id.rbModeBalancer) "balancer" else "modem"
            exportConnectionSettings(
                mode = mode,
                host = etHost.text.toString().trim(),
                port = etPort.text.toString().trim(),
                key = etKey.text.toString().trim(),
                id = etId.text.toString().trim(),
                dns = etDns.text.toString().trim(),
            )
        }
        btnImport.setOnClickListener {
            dialog.dismiss()
            importLauncher.launch(arrayOf("text/plain", "application/octet-stream", "*/*"))
        }
        btnScanQr.setOnClickListener { showQrSourceChooser() }

        dialog.show()
    }

    // Apply a scanned QR payload (key=value lines, same format as export file).
    // QR is treated as a modem-tunnel config: switches mode to modem and fills
    // host/port/key/id/dns into the open dialog. If the dialog is closed (e.g.
    // process died and result resumed), the values are persisted to prefs.
    private fun applyQrPayload(text: String) {
        val map = parseKeyValueLines(text)
        val keys = setOf("host", "port", "key", "id", "dns")
        if (map.keys.intersect(keys).isEmpty()) {
            Toast.makeText(this, "QR: no recognizable fields", Toast.LENGTH_SHORT).show()
            return
        }

        // Update the open dialog if it's still around; otherwise persist directly.
        val host = dlgEtHost
        if (host != null) {
            map["host"]?.let { host.setText(it) }
            map["port"]?.let { dlgEtPort?.setText(it) }
            map["key"]?.let { dlgEtKey?.setText(it) }
            map["id"]?.let { dlgEtId?.setText(it) }
            map["dns"]?.let { dlgEtDns?.setText(it) }
            dlgRbModeModem?.isChecked = true
        } else {
            val ed = getSharedPreferences("cfg", 0).edit()
            map["host"]?.let { ed.putString("h", it) }
            map["port"]?.let { ed.putString("p", it) }
            map["key"]?.let { ed.putString("k", it) }
            map["id"]?.let { ed.putString("id", it) }
            map["dns"]?.let { ed.putString("dns", it) }
            ed.putString("mode", "modem").apply()
        }
        Toast.makeText(this, "QR applied: ${map.keys.intersect(keys).joinToString(", ")}", Toast.LENGTH_SHORT).show()
    }

    private fun parseKeyValueLines(content: String): HashMap<String, String> {
        val map = HashMap<String, String>()
        for (raw in content.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            map[line.substring(0, eq).trim().lowercase(Locale.ROOT)] = line.substring(eq + 1).trim()
        }
        return map
    }

    // Export/import only connection fields — mode/host/port/key/id/dns.
    // Display prefs (speed_bytes, bat_threshold, logs_expanded) stay per-device.
    private fun exportConnectionSettings(
        mode: String, host: String, port: String, key: String, id: String, dns: String,
    ) {
        try {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val content = buildString {
                appendLine("# Proxy Agent — Connection Settings Export")
                appendLine("# Generated: $stamp")
                appendLine("# Import via settings dialog → IMPORT.")
                appendLine("# Only mode/host/port/key/id/dns are ex/imported. Other prefs")
                appendLine("# (speed units, battery threshold) stay local to each device.")
                appendLine()
                appendLine("mode=$mode")
                appendLine("host=$host")
                appendLine("port=$port")
                appendLine("key=$key")
                appendLine("id=$id")
                appendLine("dns=$dns")
            }
            val exportDir = File(filesDir, "exports").apply { mkdirs() }
            val file = File(exportDir, "proxy-agent-settings-$stamp.txt")
            file.writeText(content)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Proxy Agent settings $stamp")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "Save / share settings"))
        } catch (e: Throwable) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun importSettingsFromUri(uri: android.net.Uri) {
        try {
            val content = contentResolver.openInputStream(uri)?.use {
                it.bufferedReader().readText()
            } ?: run {
                Toast.makeText(this, "Import: cannot read file", Toast.LENGTH_SHORT).show(); return
            }
            val map = parseKeyValueLines(content)
            // Only connection keys are honored. Everything else in the file ignored.
            val allowed = setOf("mode", "host", "port", "key", "id", "dns")
            val applied = map.keys.intersect(allowed)
            if (applied.isEmpty()) {
                Toast.makeText(this, "Import: no connection settings in file", Toast.LENGTH_SHORT).show()
                return
            }
            val prefs = getSharedPreferences("cfg", 0)
            val ed = prefs.edit()
            map["host"]?.let { ed.putString("h", it) }
            map["port"]?.let { ed.putString("p", it) }
            map["key"]?.let { ed.putString("k", it) }
            map["id"]?.let { ed.putString("id", it) }
            map["dns"]?.let { ed.putString("dns", it) }
            map["mode"]?.let {
                val m = it.lowercase(Locale.ROOT)
                if (m == "modem" || m == "balancer") ed.putString("mode", m)
            }
            ed.apply()
            Toast.makeText(this, "Imported: ${applied.joinToString(", ")}", Toast.LENGTH_SHORT).show()
            showSettingsDialog()   // reopen with fresh values
        } catch (e: Throwable) {
            Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
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

    private fun showConfigurePrompt() {
        AlertDialog.Builder(this)
            .setTitle("Connection not configured")
            .setMessage("Host / Port / Key are required. Scan a tunnel QR, paste it from clipboard, or fill in manually.")
            .setPositiveButton("Scan QR") { _, _ -> showQrSourceChooser() }
            .setNeutralButton("Configure") { _, _ -> showSettingsDialog() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Three independent ways to ingest a QR config: live camera (Google ML
    // Kit code scanner — way more robust than ZXing on stylized/dense QRs),
    // picking an image from gallery (decoded via ZXing offline), or pasting
    // the plain text payload shown next to the QR in the dashboard.
    private fun showQrSourceChooser() {
        val items = arrayOf("Camera (Google scanner)", "Pick QR image from gallery", "Paste from clipboard")
        AlertDialog.Builder(this)
            .setTitle("Import tunnel QR")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> launchGoogleCodeScanner()
                    1 -> qrImageLauncher.launch("image/*")
                    2 -> applyClipboardQr()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Google ML Kit code scanner — Play-Services-backed, no CAMERA permission
    // needed, handles stylized/dense QRs that ZXing struggles with. Falls back
    // to ZXing when Play Services is missing or the scan client errors.
    private fun launchGoogleCodeScanner() {
        try {
            val options = GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
            val scanner = GmsBarcodeScanning.getClient(this, options)
            scanner.startScan()
                .addOnSuccessListener { barcode ->
                    val text = barcode.rawValue
                    if (text.isNullOrBlank()) {
                        Toast.makeText(this, "QR: empty payload", Toast.LENGTH_SHORT).show()
                    } else {
                        applyQrPayload(text)
                    }
                }
                .addOnCanceledListener {
                    Toast.makeText(this, "QR scan cancelled", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Google scanner failed (${e.message}); falling back to ZXing",
                        Toast.LENGTH_LONG).show()
                    qrLauncher.launch(buildScanOptions())
                }
        } catch (e: Throwable) {
            Toast.makeText(this, "No Play Services scanner; falling back to ZXing",
                Toast.LENGTH_SHORT).show()
            qrLauncher.launch(buildScanOptions())
        }
    }

    // setOrientationLocked(true) + custom portrait CaptureActivity prevents the
    // library's default sensorLandscape activity from showing the "rotate the
    // phone" overlay on portrait-only apps.
    private fun buildScanOptions(): ScanOptions = ScanOptions().apply {
        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        setPrompt("Point camera at the tunnel QR · close-up + good light helps with dense codes")
        setBeepEnabled(false)
        setBarcodeImageEnabled(false)
        setOrientationLocked(true)
        setCaptureActivity(PortraitCaptureActivity::class.java)
    }

    private fun applyClipboardQr() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            val text = if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).coerceToText(this).toString()
            } else ""
            if (text.isBlank()) {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                return
            }
            applyQrPayload(text)
        } catch (e: Throwable) {
            Toast.makeText(this, "Clipboard read failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun decodeQrFromUri(uri: Uri) {
        try {
            val bitmap = contentResolver.openInputStream(uri).use { input ->
                if (input == null) null else BitmapFactory.decodeStream(input)
            } ?: run {
                Toast.makeText(this, "QR: cannot read image", Toast.LENGTH_SHORT).show()
                return
            }
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            val source = RGBLuminanceSource(w, h, pixels)
            val binary = BinaryBitmap(HybridBinarizer(source))
            val reader = MultiFormatReader().apply {
                setHints(mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true,
                ))
            }
            val result = reader.decodeWithState(binary)
            applyQrPayload(result.text)
        } catch (e: Throwable) {
            Toast.makeText(this, "QR: decode failed (${e.javaClass.simpleName})", Toast.LENGTH_LONG).show()
        }
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
        if (transport == "WIFI") {
            Toast.makeText(this, "Disable WiFi to cycle mobile IP", Toast.LENGTH_LONG).show()
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

                stage = "cycling network"
                runOnUiThread { tvStatus.text = "CYCLING NETWORK…"; tvStatus.setTextColor(0xFFFFAA00.toInt()) }

                val baselineIp = publicIp
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
        return try {
            val svc = Intent(this, ProxyService::class.java).apply {
                putExtra("host", h); putExtra("port", po); putExtra("key", k)
                putExtra("id", id); putExtra("dns", d)
                putExtra("engine", engine)
                putExtra("mode", mode)
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

    // Fast pre-check before running the async preflight: if a recent
    // self-test (≤24h) already produced SAME_IP on this device, the
    // checkbox can't be turned on — show a dialog explaining what to do
    // (fix mobile_data_always_on, then re-try), uncheck, and bail.
    // Returns true iff the path was taken (caller should NOT continue
    // with the regular preflight).
    private fun refuseDueToCachedSplitFail(checkbox: CheckBox): Boolean {
        val info = try {
            val f = File(filesDir, "wifi_info.json")
            if (!f.exists()) return false
            org.json.JSONObject(f.readText())
        } catch (_: Throwable) { return false }

        val testResult = info.optString("test_result", "")
        val testedAtMs = info.optLong("tested_at_ms", 0L)
        // 24h freshness window: older results may be stale (user could
        // have fixed the system setting since). Past that we let preflight
        // re-decide rather than holding a permanent grudge.
        val freshnessWindowMs = 24L * 60 * 60 * 1000
        if (testResult != "SAME_IP" ||
            testedAtMs <= 0L ||
            System.currentTimeMillis() - testedAtMs >= freshnessWindowMs) {
            return false
        }

        // Uncheck FIRST so any subsequent UI race (user double-tap) doesn't
        // leave the box stuck on while the dialog is up. The listener
        // re-fires with isChecked=false, which our guard skips.
        checkbox.isChecked = false
        AlertDialog.Builder(this)
            .setTitle("Wi-Fi return: already verified not working")
            .setMessage(
                "A previous self-test on this device showed that the OS " +
                "doesn't split Wi-Fi and cellular — they share the same " +
                "public IP. The Wi-Fi return relay can't work here until " +
                "that's fixed.\n\n" +
                "Last check: ${info.optString("test_detail", "n/a")}\n\n" +
                "Fix it via 'mobile_data_always_on=1', then re-tick this " +
                "checkbox to re-test."
            )
            .setPositiveButton("Show instructions") { _, _ ->
                showMobileDataAlwaysOnInstructions()
            }
            .setNegativeButton("Close", null)
            .show()
        return true
    }

    // Wi-Fi return preflight + HARD GATE: when the user ticks the box,
    // async-check that cellular can stay alive alongside Wi-Fi. If the
    // OS would shut cellular off, we surface a dialog with auto-fix
    // or manual instructions — AND uncheck the box unless the user
    // takes the auto-fix path and it succeeds.
    private fun runMobileDataAlwaysOnPreflight(checkbox: CheckBox) {
        Thread {
            val report = try {
                MobileDataAlwaysOnCheck.check(this)
            } catch (t: Throwable) {
                MobileDataAlwaysOnCheck.Report(
                    MobileDataAlwaysOnCheck.Result.UNKNOWN,
                    canAutoFix = false,
                    detail = "check threw: ${t.message}",
                )
            }
            // SUPPORTED and UNKNOWN both leave the checkbox alone — the
            // user got their wish, and we'll let the self-test decide
            // for real at service start.
            if (report.result != MobileDataAlwaysOnCheck.Result.BLOCKED) return@Thread
            runOnUiThread { showMobileDataAlwaysOnDialog(report, checkbox) }
        }.apply { name = "MobileDataPreflight"; isDaemon = true; start() }
    }

    private fun showMobileDataAlwaysOnDialog(
        report: MobileDataAlwaysOnCheck.Report,
        checkbox: CheckBox,
    ) {
        val baseMsg =
            "Wi-Fi return saves mobile data only when cellular stays attached " +
            "while Wi-Fi is connected. This device currently shuts cellular " +
            "down once Wi-Fi is validated, so the relay would never actually " +
            "split traffic.\n\n" +
            "Diagnostic: ${report.detail}"

        // Helper: every path that doesn't end with a confirmed fix must
        // uncheck the box. Toast tells the user why so it doesn't look
        // like a UI ghost.
        val unCheckWithReason: (String) -> Unit = { reason ->
            checkbox.isChecked = false
            Toast.makeText(this, "Wi-Fi return disabled: $reason", Toast.LENGTH_LONG).show()
        }

        val builder = AlertDialog.Builder(this)
            .setTitle("Cellular drops while on Wi-Fi — can't enable")
            .setCancelable(false)   // force a deliberate choice
            .setNegativeButton("Ignore") { _, _ ->
                unCheckWithReason("device blocks parallel transports")
            }

        if (report.canAutoFix) {
            builder.setMessage(
                "$baseMsg\n\nWRITE_SECURE_SETTINGS is granted — we can flip " +
                "the system toggle for you. Continue?"
            )
            builder.setPositiveButton("Enable") { _, _ ->
                // Off the UI thread because Settings.Global.putInt can do
                // a bit of cross-process IPC, and some ROMs are sluggish.
                Thread {
                    val ok = try { MobileDataAlwaysOnCheck.tryEnable(this) }
                    catch (_: Throwable) { false }
                    runOnUiThread {
                        if (ok) {
                            Toast.makeText(
                                this,
                                "Enabled mobile_data_always_on=1 — Wi-Fi return ready",
                                Toast.LENGTH_LONG,
                            ).show()
                            // Checkbox stays checked — the fix succeeded.
                        } else {
                            unCheckWithReason("ROM rejected the system write")
                            showMobileDataAlwaysOnInstructions()
                        }
                    }
                }.apply { name = "MobileDataEnable"; isDaemon = true; start() }
            }
            builder.setNeutralButton("Show instructions") { _, _ ->
                unCheckWithReason("manual fix required")
                showMobileDataAlwaysOnInstructions()
            }
        } else {
            builder.setMessage(
                "$baseMsg\n\nWe can't change this automatically " +
                "(WRITE_SECURE_SETTINGS not granted)."
            )
            builder.setPositiveButton("Show instructions") { _, _ ->
                unCheckWithReason("manual fix required")
                showMobileDataAlwaysOnInstructions()
            }
        }
        builder.show()
    }

    private fun showMobileDataAlwaysOnInstructions() {
        val text =
            "Pick whichever path works on your device:\n\n" +
            "A) Developer options toggle (no root, no adb):\n" +
            "   Settings → System → Developer options → " +
            "Mobile data always active → ON\n" +
            "   (Enable developer options first via Settings → About " +
            "phone → tap Build number 7 times.)\n\n" +
            "B) adb command (one-time, persists across reboots):\n" +
            "   adb shell settings put global mobile_data_always_on 1\n\n" +
            "C) Grant this app the permission to do it itself:\n" +
            "   adb shell pm grant ${packageName} android.permission.WRITE_SECURE_SETTINGS\n" +
            "   Then re-tick the Wi-Fi return checkbox.\n\n" +
            "Verify it stuck by running:\n" +
            "   adb shell settings get global mobile_data_always_on\n" +
            "Expected output: 1"

        AlertDialog.Builder(this)
            .setTitle("Keep cellular alive on Wi-Fi")
            .setMessage(text)
            .setPositiveButton("Close", null)
            .show()
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
            if (Build.VERSION.SDK_INT >= 23) {
                val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
                when {
                    caps == null -> "NONE"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                    else -> "OTHER"
                }
            } else {
                @Suppress("DEPRECATION") cm.activeNetworkInfo?.typeName ?: "NONE"
            }
        } catch (_: Throwable) { "?" }
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
        val proxyState = readFile("proxy_state")
        val info = readFile("conn_info").split("|")
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
        if (Build.VERSION.SDK_INT >= 23) kv("Security-Patch", Build.VERSION.SECURITY_PATCH)
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
        if (Build.VERSION.SDK_INT >= 23) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            kv("Battery-Whitelist", if (pm.isIgnoringBatteryOptimizations(packageName)) "YES" else "NO")
        }

        section("NETWORK")
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= 23) {
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
            } else {
                @Suppress("DEPRECATION") val info = cm.activeNetworkInfo
                kv("Type", info?.typeName)
                @Suppress("DEPRECATION") kv("Connected", info?.isConnected ?: false)
                val ips = currentLocalIps()
                if (ips.isNotEmpty()) kv("Local-IPs", ips.joinToString(", "))
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
        val proxyState = readFile("proxy_state")
        val connInfo = readFile("conn_info").split("|")
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
            transport != "WIFI" && transport != "VPN"
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

        val wan = publicIp.ifEmpty { "fetching…" }
        tvNetwork.text = "$wan  ·  $transport"

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
                updateWifiReturnPanel(wifiReturnStatus)
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
    private fun updateWifiReturnPanel(status: String) {
        val viaView = pagerRefs.tvUplinkVia ?: return
        val detailView = pagerRefs.tvUplinkDetail ?: return

        if (status.isEmpty()) {
            viaView.visibility = View.GONE
            detailView.visibility = View.GONE
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
}
