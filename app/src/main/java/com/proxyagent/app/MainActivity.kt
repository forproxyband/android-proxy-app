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
        val rbEngineBinary = view.findViewById<RadioButton>(R.id.rbEngineBinary)
        val rbEngineAar = view.findViewById<RadioButton>(R.id.rbEngineAar)
        val rgMode = view.findViewById<RadioGroup>(R.id.rgMode)
        val rbModeModem = view.findViewById<RadioButton>(R.id.rbModeModem)
        val rbModeBalancer = view.findViewById<RadioButton>(R.id.rbModeBalancer)
        val btnImport = view.findViewById<Button>(R.id.btnImport)
        val btnExport = view.findViewById<Button>(R.id.btnExport)
        val btnScanQr = view.findViewById<Button>(R.id.btnScanQr)
        val tvScanQrHint = view.findViewById<TextView>(R.id.tvScanQrHint)
        val prefs = getSharedPreferences("cfg", 0)

        fun applyModeVisibility(modemMode: Boolean) {
            etId.visibility = if (modemMode) View.VISIBLE else View.GONE
            btnScanQr.visibility = if (modemMode) View.VISIBLE else View.GONE
            tvScanQrHint.visibility = if (modemMode) View.VISIBLE else View.GONE
        }

        val retentionLabels = arrayOf("Day (1)", "Week (7)", "Month (30)")
        val retentionDays = intArrayOf(1, 7, 30)
        run {
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, retentionLabels)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spRetention.adapter = adapter
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
            if (prefs.getString("engine", "binary") == "aar") rbEngineAar.isChecked = true
            else rbEngineBinary.isChecked = true
            val modemMode = prefs.getString("mode", "modem") == "modem"
            if (modemMode) rbModeModem.isChecked = true else rbModeBalancer.isChecked = true
            applyModeVisibility(modemMode)
        }
        loadFromPrefs()

        rgMode.setOnCheckedChangeListener { _, checkedId ->
            applyModeVisibility(checkedId == R.id.rbModeModem)
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
                val newEngine = if (rgEngine.checkedRadioButtonId == R.id.rbEngineAar) "aar" else "binary"
                val newMode = if (rgMode.checkedRadioButtonId == R.id.rbModeBalancer) "balancer" else "modem"
                val engineChanged = prefs.getString("engine", "binary") != newEngine
                val modeChanged = prefs.getString("mode", "modem") != newMode
                prefs.edit()
                    .putString("h", h).putString("p", p).putString("k", k)
                    .putString("id", id).putString("dns", d)
                    .putBoolean("speed_bytes", speedBytes)
                    .putInt("analytics_retention_days", newRetention)
                    .putString("engine", newEngine)
                    .putString("mode", newMode)
                    .apply()
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

                val auto = IpCycle.toggleMobileNetworkViaRoot() ||
                    IpCycle.toggleMobileNetworkViaSecureSettings(this)
                if (!auto) {
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "Auto-cycle unavailable. Grant: adb shell pm grant $packageName " +
                                "android.permission.WRITE_SECURE_SETTINGS",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    // Restart proxy if it was running so we don't leave it stopped.
                    if (wasRunning) runOnUiThread { startProxyService() }
                    return@Thread
                }

                runOnUiThread { tvStatus.text = "WAITING FOR NET…" }
                val netDeadline = System.currentTimeMillis() + 30_000
                while (System.currentTimeMillis() < netDeadline) {
                    if (currentTransport() == "CELLULAR") break
                    Thread.sleep(500)
                }
                // Small grace for routes/DNS to settle before the agent dials.
                Thread.sleep(1500)

                publicIp = ""
                refreshPublicIp()

                if (wasRunning) {
                    stage = "restarting proxy"
                    runOnUiThread { startProxyService() }
                }

                runOnUiThread {
                    Toast.makeText(this, "IP cycle complete", Toast.LENGTH_SHORT).show()
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

        val engine = prefs.getString("engine", "binary") ?: "binary"
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
            registratorPanel.visibility = View.VISIBLE
            pagerRefs.tvRegistrator?.text = registrator
            pagerRefs.tvUptime?.text = if (connectedSinceMs > 0)
                "up ${formatDuration(System.currentTimeMillis() - connectedSinceMs)}"
            else ""
            pagerRefs.tvActivity?.text = when {
                tunnels == 0 -> "◦ idle — no connections"
                else -> "⚡ $tunnels ${if (tunnels == 1) "connection" else "connections"} · " +
                    "↓${humanRate(rxRate)} ↑${humanRate(txRate)}"
            }
            // Refresh charts at most every 30s — they cover 24h, sub-minute updates
            // are visually pointless and re-reading the JSONL on every tick wastes IO.
            val nowMs = System.currentTimeMillis()
            if (registratorPager.currentItem != 0 &&
                nowMs - lastPanelDataAtMs > 30_000L) {
                lastPanelDataAtMs = nowMs
                refreshPanelCharts()
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

    private fun updatePagerDots(active: Int) {
        val dots = arrayOf(dot0, dot1, dot2)
        for (i in dots.indices) {
            dots[i].setBackgroundColor(if (i == active) 0xFF88FFAA.toInt() else 0x33FFFFFF.toInt())
        }
    }

    // Reads the last 24h of buckets and feeds them to the two mini charts.
    // Done off the UI thread because IO can be slow on cold cache; results
    // are pushed back via runOnUiThread.
    private fun refreshPanelCharts() {
        Thread {
            try {
                val now = System.currentTimeMillis()
                val from = now - 24 * 60 * 60_000L
                val buckets = AnalyticsStore.load(this, from, now)
                val (trafficSeries, trafficStartMs, trafficStepMs, trafficTotalBytes) =
                    aggregateForPanel(buckets, from, now, AnalyticsStore.BUCKET_MS * 10) { it.rxBytes + it.txBytes }
                val (connSeries, connStartMs, connStepMs, connTotalEvents) =
                    aggregateForPanel(buckets, from, now, AnalyticsStore.BUCKET_MS * 10) {
                        (it.opens + it.closes).toLong()
                    }
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
                }
            } catch (_: Throwable) {}
        }.apply { isDaemon = true; name = "PanelChartLoad"; start() }
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
