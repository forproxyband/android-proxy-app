package com.proxyagent.app

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
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

// Connection settings screen. Extracted from MainActivity's former settings
// modal so the (large) settings form gets a full screen with a back control.
// Owns the QR onboarding, import/export, and the Wi-Fi-return preflight that
// used to live in MainActivity. Reached from MainActivity's ⚙ button and the
// "not configured" prompt.
class SettingsActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("cfg", 0) }

    private lateinit var etHost: EditText
    private lateinit var etPort: EditText
    private lateinit var etKey: EditText
    private lateinit var etId: EditText
    private lateinit var etDns: EditText
    private lateinit var cbSpeedBytes: CheckBox
    private lateinit var spRetention: Spinner
    private lateinit var rgEngine: RadioGroup
    private lateinit var rbEngineNative: RadioButton
    private lateinit var rbEngineBinary: RadioButton
    private lateinit var rgMode: RadioGroup
    private lateinit var rbModeModem: RadioButton
    private lateinit var rbModeBalancer: RadioButton
    private lateinit var btnImport: Button
    private lateinit var btnExport: Button
    private lateinit var btnScanQr: Button
    private lateinit var tvScanQrHint: TextView
    private lateinit var cbApnSwap: CheckBox
    private lateinit var cbImeiRotate: CheckBox
    private lateinit var spImeiMethod: Spinner
    private lateinit var etImeiCustomCmd: EditText
    private lateinit var cbWifiReturn: CheckBox
    private lateinit var tvWifiReturnHint: TextView
    private lateinit var cbRotationLock: CheckBox
    private lateinit var etRotationCooldown: EditText
    private lateinit var spNetworkProfile: Spinner
    private lateinit var tvNetworkProfileHint: TextView
    private lateinit var tvAutostartLockWarn: TextView
    private lateinit var tvAutostartStatus: TextView
    private lateinit var btnBatteryOpt: Button
    private lateinit var btnOemAutostart: Button
    private lateinit var btnRootAutostart: Button

    private val retentionDays = intArrayOf(1, 7, 30)
    private val networkProfileKeys = arrayOf("LOW_100", "MID_500", "HIGH_1000")
    private val imeiMethodKeys = arrayOf("custom", "props", "magisk-imei")

    private val importLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importSettingsFromUri(uri)
        }

    private val qrLauncher: ActivityResultLauncher<ScanOptions> =
        registerForActivityResult(ScanContract()) { result ->
            val text = result?.contents
            if (text.isNullOrBlank()) {
                Toast.makeText(this, "QR scan cancelled", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            applyQrPayload(text)
        }

    private val qrImageLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@registerForActivityResult
            decodeQrFromUri(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_settings)
        title = "Settings"

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        etHost = findViewById(R.id.etHost)
        etPort = findViewById(R.id.etPort)
        etKey = findViewById(R.id.etKey)
        etId = findViewById(R.id.etId)
        etDns = findViewById(R.id.etDns)
        cbSpeedBytes = findViewById(R.id.cbSpeedBytes)
        spRetention = findViewById(R.id.spRetention)
        rgEngine = findViewById(R.id.rgEngine)
        rbEngineNative = findViewById(R.id.rbEngineNative)
        rbEngineBinary = findViewById(R.id.rbEngineBinary)
        rgMode = findViewById(R.id.rgMode)
        rbModeModem = findViewById(R.id.rbModeModem)
        rbModeBalancer = findViewById(R.id.rbModeBalancer)
        btnImport = findViewById(R.id.btnImport)
        btnExport = findViewById(R.id.btnExport)
        btnScanQr = findViewById(R.id.btnScanQr)
        tvScanQrHint = findViewById(R.id.tvScanQrHint)
        cbApnSwap = findViewById(R.id.cbApnSwap)
        cbImeiRotate = findViewById(R.id.cbImeiRotate)
        spImeiMethod = findViewById(R.id.spImeiMethod)
        etImeiCustomCmd = findViewById(R.id.etImeiCustomCmd)
        cbWifiReturn = findViewById(R.id.cbWifiReturn)
        tvWifiReturnHint = findViewById(R.id.tvWifiReturnHint)
        cbRotationLock = findViewById(R.id.cbRotationLock)
        etRotationCooldown = findViewById(R.id.etRotationCooldown)
        spNetworkProfile = findViewById(R.id.spNetworkProfile)
        tvNetworkProfileHint = findViewById(R.id.tvNetworkProfileHint)
        tvAutostartLockWarn = findViewById(R.id.tvAutostartLockWarn)
        tvAutostartStatus = findViewById(R.id.tvAutostartStatus)
        btnBatteryOpt = findViewById(R.id.btnBatteryOpt)
        btnOemAutostart = findViewById(R.id.btnOemAutostart)
        btnRootAutostart = findViewById(R.id.btnRootAutostart)

        run {
            val labels = arrayOf("Day (1)", "Week (7)", "Month (30)")
            val a = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
            a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spRetention.adapter = a
        }
        run {
            val labels = arrayOf(
                "100 Mbps — low latency (cellular / Wi-Fi)",
                "500 Mbps — balanced",
                "1 Gbps — max throughput",
            )
            val a = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
            a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spNetworkProfile.adapter = a
        }
        run {
            val labels = arrayOf(
                "Custom shell command",
                "resetprop random IMEI (MagiskHide Props)",
                "magisk-imei --random",
            )
            val a = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
            a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spImeiMethod.adapter = a
        }

        loadFromPrefs()

        rgMode.setOnCheckedChangeListener { _, checkedId ->
            applyModeVisibility(checkedId == R.id.rbModeModem)
        }
        rgEngine.setOnCheckedChangeListener { _, _ ->
            refreshWifiReturnGate()
            applyNetworkProfileEnabled(rgEngine.checkedRadioButtonId == R.id.rbEngineNative)
        }
        cbWifiReturn.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked || !cbWifiReturn.isEnabled) return@setOnCheckedChangeListener
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

        btnExport.setOnClickListener {
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
            importLauncher.launch(arrayOf("text/plain", "application/octet-stream", "*/*"))
        }
        btnScanQr.setOnClickListener { showQrSourceChooser() }
        findViewById<Button>(R.id.btnSaveSettings).setOnClickListener { saveSettings() }

        setupAutostartSection()
    }

    override fun onResume() {
        super.onResume()
        // Battery / lock-screen state can change while the user is away in a
        // system settings screen — re-read on return so the status reflects
        // reality without the user having to leave and re-enter Settings.
        refreshAutostartStatus()
    }

    // ── Auto-reconnect after reboot ─────────────────────────────────────────

    private fun setupAutostartSection() {
        btnBatteryOpt.setOnClickListener {
            if (!AutostartManager.openBatteryWhitelist(this)) {
                Toast.makeText(this, "Couldn't open battery settings", Toast.LENGTH_SHORT).show()
            }
        }
        // Vendor autostart screens only exist on OEMs that ship one. On
        // stock/Pixel the button would just bounce to app details — hide it to
        // avoid confusion.
        btnOemAutostart.visibility =
            if (AutostartManager.hasOemAutostartManager()) View.VISIBLE else View.GONE
        btnOemAutostart.setOnClickListener {
            AutostartManager.openOemAutostartSettings(this)
            Toast.makeText(
                this,
                "Find this app in the list and enable Autostart / allow background",
                Toast.LENGTH_LONG,
            ).show()
        }
        btnRootAutostart.setOnClickListener { onRootAutostartClicked() }
        // Status (incl. the root su probe) is populated from onResume, which
        // always fires right after onCreate — avoids a duplicate concurrent
        // su probe that could trigger two Magisk root prompts.
    }

    // Root probe result, cached for the activity's lifetime. The su probe
    // (isRootAvailable) can trigger a Magisk prompt, so it runs at most once —
    // NOT on every onResume (returning from the battery / OEM deep-link screens
    // would otherwise re-prompt repeatedly). Install-state is refreshed only
    // after the user's own install/remove action.
    private var rootProbed = false
    private var rootAvailable = false
    private var rootScriptInstalled = false

    // Synchronous render from current (non-root) state + the cached root
    // result. Safe to call repeatedly from the main thread.
    private fun renderAutostartStatus() {
        val battery = AutostartManager.isBatteryWhitelisted(this)
        val secure = AutostartManager.isDeviceSecure(this)

        // Lock-screen (Direct Boot) warning — the one case the app can't fix.
        if (secure) {
            tvAutostartLockWarn.visibility = View.VISIBLE
            tvAutostartLockWarn.text =
                "⚠ A screen lock (PIN/pattern/password) is set. After a reboot " +
                "the proxy can only start once someone unlocks the phone the " +
                "first time. For unattended reboots either remove the screen " +
                "lock, or use the root autostart below (it starts the agent as " +
                "soon as storage unlocks)."
        } else {
            tvAutostartLockWarn.visibility = View.GONE
        }

        val sb = StringBuilder()
        sb.append(if (battery) "✓" else "✗").append(" Battery optimization: ")
            .append(if (battery) "exempt" else "ACTIVE (may kill the service)").append('\n')
        sb.append(if (secure) "✗" else "✓").append(" Screen lock: ")
            .append(if (secure) "set (blocks start until unlock)" else "none (starts on boot)")
            .append('\n')
        if (AutostartManager.hasOemAutostartManager()) {
            sb.append("• OEM (").append(android.os.Build.MANUFACTURER)
                .append("): enable Autostart manually").append('\n')
        }
        sb.append(
            when {
                !rootProbed -> "… checking root"
                !rootAvailable -> "✗ Root: not available (using non-root path)"
                rootScriptInstalled -> "✓ Root autostart: INSTALLED (guaranteed)"
                else -> "• Root: available — install autostart below"
            }
        )
        tvAutostartStatus.text = sb.toString()

        btnRootAutostart.visibility = if (rootProbed && rootAvailable) View.VISIBLE else View.GONE
        btnRootAutostart.text =
            if (rootScriptInstalled) "REMOVE ROOT AUTOSTART"
            else "INSTALL ROOT AUTOSTART (GUARANTEED)"
    }

    private fun refreshAutostartStatus() {
        renderAutostartStatus()
        if (rootProbed) return
        Thread {
            val root = AutostartManager.isRootAvailable()
            val installed = if (root) AutostartManager.isRootBootScriptInstalled() else false
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                rootProbed = true
                rootAvailable = root
                rootScriptInstalled = installed
                renderAutostartStatus()
            }
        }.apply { isDaemon = true; name = "AutostartRootProbe"; start() }
    }

    private fun onRootAutostartClicked() {
        val key = prefs.getString("k", "")?.trim().orEmpty()
        // Reject a key the boot script's KEY='...' literal can't carry without
        // desyncing auth — before touching root — so the user gets a clear
        // message instead of a silently-non-starting script.
        if (!rootScriptInstalled && key.isNotEmpty() &&
            !AutostartManager.keyUsableInBootScript(key)) {
            Toast.makeText(this,
                "Connection key contains a quote — can't use it in the root script",
                Toast.LENGTH_LONG).show()
            return
        }
        btnRootAutostart.isEnabled = false
        Thread {
            val installed = AutostartManager.isRootBootScriptInstalled()
            val result: String = if (installed) {
                if (AutostartManager.removeRootBootScript()) "removed" else "remove_failed"
            } else {
                if (key.isEmpty()) "no_key"
                else if (AutostartManager.installRootBootScript(this, key)) "installed"
                else "install_failed"
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                btnRootAutostart.isEnabled = true
                // Reflect the new state in the cache; no extra su probe needed.
                rootProbed = true
                rootAvailable = true
                rootScriptInstalled = when (result) {
                    "installed" -> true
                    "removed" -> false
                    else -> installed
                }
                when (result) {
                    "installed" -> Toast.makeText(this,
                        "Root autostart installed — the agent will start on every boot",
                        Toast.LENGTH_LONG).show()
                    "removed" -> Toast.makeText(this,
                        "Root autostart removed", Toast.LENGTH_SHORT).show()
                    "no_key" -> Toast.makeText(this,
                        "Save the connection (host/port/key) first", Toast.LENGTH_LONG).show()
                    "install_failed" -> Toast.makeText(this,
                        "Install failed — root denied or /data/adb/service.d unavailable",
                        Toast.LENGTH_LONG).show()
                    "remove_failed" -> Toast.makeText(this,
                        "Remove failed — root denied", Toast.LENGTH_LONG).show()
                }
                renderAutostartStatus()
            }
        }.apply { isDaemon = true; name = "AutostartRootOp"; start() }
    }

    // ── Form gating (same rules as the former dialog) ──────────────────────

    private fun applyWifiReturnEnabled(modemMode: Boolean, nativeEngine: Boolean) {
        val allowed = modemMode && nativeEngine
        cbWifiReturn.isEnabled = allowed
        tvWifiReturnHint.alpha = if (allowed) 1f else 0.5f
        cbWifiReturn.text = when {
            allowed -> "Return traffic to client over Wi-Fi"
            !modemMode && !nativeEngine ->
                "Return traffic to client over Wi-Fi  (Modem + NATIVE engine only)"
            !modemMode -> "Return traffic to client over Wi-Fi  (Modem mode only)"
            else -> "Return traffic to client over Wi-Fi  (NATIVE engine only)"
        }
    }

    private fun refreshWifiReturnGate() {
        applyWifiReturnEnabled(
            modemMode = rgMode.checkedRadioButtonId == R.id.rbModeModem,
            nativeEngine = rgEngine.checkedRadioButtonId == R.id.rbEngineNative,
        )
    }

    private fun applyNetworkProfileEnabled(nativeEngine: Boolean) {
        spNetworkProfile.isEnabled = nativeEngine
        tvNetworkProfileHint.alpha = if (nativeEngine) 1f else 0.5f
    }

    private fun applyModeVisibility(modemMode: Boolean) {
        etId.visibility = if (modemMode) View.VISIBLE else View.GONE
        btnScanQr.visibility = if (modemMode) View.VISIBLE else View.GONE
        tvScanQrHint.visibility = if (modemMode) View.VISIBLE else View.GONE
        refreshWifiReturnGate()
    }

    private fun applyImeiVisibility() {
        val customSelected = imeiMethodKeys.getOrNull(spImeiMethod.selectedItemPosition) == "custom"
        etImeiCustomCmd.visibility =
            if (cbImeiRotate.isChecked && customSelected) View.VISIBLE else View.GONE
        spImeiMethod.isEnabled = cbImeiRotate.isChecked
    }

    private fun loadFromPrefs() {
        etHost.setText(prefs.getString("h", ""))
        etPort.setText(prefs.getString("p", ""))
        etKey.setText(prefs.getString("k", ""))
        etId.setText(prefs.getString("id", ""))
        etDns.setText(prefs.getString("dns", ""))
        cbSpeedBytes.isChecked = prefs.getBoolean("speed_bytes", false)
        val savedRet = prefs.getInt("analytics_retention_days", 30)
        spRetention.setSelection(retentionDays.indexOf(savedRet).let { if (it < 0) 2 else it })
        when (prefs.getString("engine", "native")) {
            "binary" -> rbEngineBinary.isChecked = true
            else -> rbEngineNative.isChecked = true
        }
        val savedProfile = prefs.getString("network_profile", "LOW_100") ?: "LOW_100"
        spNetworkProfile.setSelection(
            networkProfileKeys.indexOf(savedProfile)
                .let { if (it < 0) networkProfileKeys.indexOf("LOW_100") else it }
        )
        applyNetworkProfileEnabled(rgEngine.checkedRadioButtonId == R.id.rbEngineNative)
        val modemMode = prefs.getString("mode", "modem") == "modem"
        if (modemMode) rbModeModem.isChecked = true else rbModeBalancer.isChecked = true
        applyModeVisibility(modemMode)
        cbApnSwap.isChecked = prefs.getBoolean("apn_swap", false)
        cbImeiRotate.isChecked = prefs.getBoolean("imei_rotate", false)
        val savedMethod = prefs.getString("imei_method", "custom") ?: "custom"
        spImeiMethod.setSelection(imeiMethodKeys.indexOf(savedMethod).let { if (it < 0) 0 else it })
        etImeiCustomCmd.setText(prefs.getString("imei_cmd", ""))
        applyImeiVisibility()
        cbWifiReturn.isChecked = prefs.getBoolean("wifi_return", false)
        refreshWifiReturnGate()
        cbRotationLock.isChecked = prefs.getBoolean("rotation_lock", true)
        etRotationCooldown.setText(prefs.getInt("rotation_cooldown_s", 10).toString())
    }

    private fun saveSettings() {
        val h = etHost.text.toString().trim()
        val p = etPort.text.toString().trim()
        val k = etKey.text.toString().trim()
        val id = etId.text.toString().trim()
        val d = etDns.text.toString().trim()
        if (h.isEmpty() || p.isEmpty() || k.isEmpty()) {
            Toast.makeText(this, "Host / Port / Key are required", Toast.LENGTH_SHORT).show()
            return
        }
        val speedBytes = cbSpeedBytes.isChecked
        val newRetention = retentionDays[spRetention.selectedItemPosition.coerceIn(0, retentionDays.size - 1)]
        val retentionChanged = prefs.getInt("analytics_retention_days", 30) != newRetention
        val newEngine = when (rgEngine.checkedRadioButtonId) {
            R.id.rbEngineBinary -> "binary"
            else -> "native"
        }
        val newMode = if (rgMode.checkedRadioButtonId == R.id.rbModeBalancer) "balancer" else "modem"
        val imeiMethodKey = imeiMethodKeys[spImeiMethod.selectedItemPosition.coerceIn(0, imeiMethodKeys.size - 1)]
        // Wi-Fi return only persists true when both gates (modem + native) pass —
        // defence in depth against stale UI / racey radio events.
        val effectiveWifiReturn = cbWifiReturn.isChecked && newMode == "modem" && newEngine == "native"
        val engineChanged = prefs.getString("engine", "native") != newEngine
        val modeChanged = prefs.getString("mode", "modem") != newMode
        val newNetworkProfile = networkProfileKeys[
            spNetworkProfile.selectedItemPosition.coerceIn(0, networkProfileKeys.size - 1)]
        val rotationLock = cbRotationLock.isChecked
        // Cooldown is free-form; clamp to a sane range and fall back to the 10s
        // default on empty / non-numeric input.
        val cooldownSeconds = etRotationCooldown.text.toString().trim()
            .toIntOrNull()?.coerceIn(0, 3600) ?: 10
        prefs.edit()
            .putString("h", h).putString("p", p).putString("k", k)
            .putString("id", id).putString("dns", d)
            .putBoolean("speed_bytes", speedBytes)
            .putInt("analytics_retention_days", newRetention)
            .putString("engine", newEngine)
            .putString("mode", newMode)
            .putBoolean("apn_swap", cbApnSwap.isChecked)
            .putBoolean("imei_rotate", cbImeiRotate.isChecked)
            .putString("imei_method", imeiMethodKey)
            .putString("imei_cmd", etImeiCustomCmd.text.toString().trim())
            .putBoolean("wifi_return", effectiveWifiReturn)
            .putString("network_profile", newNetworkProfile)
            .putBoolean("rotation_lock", rotationLock)
            .putInt("rotation_cooldown_s", cooldownSeconds)
            .apply()
        IpCycle.saveConfigToFile(
            this,
            IpCycle.CycleConfig(
                apnSwap = cbApnSwap.isChecked,
                imeiRotation = cbImeiRotate.isChecked,
                imeiMethod = imeiMethodKey,
                imeiCustomCmd = etImeiCustomCmd.text.toString().trim(),
                wifiReturn = effectiveWifiReturn,
                wifiReturnMethod = prefs.getString("wifi_return_method", "local_relay") ?: "local_relay",
                rotationLock = rotationLock,
                cooldownSeconds = cooldownSeconds,
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
        finish()
    }

    // ── QR onboarding ──────────────────────────────────────────────────────

    private fun applyQrPayload(text: String) {
        val map = parseKeyValueLines(text)
        val keys = setOf("host", "port", "key", "id", "dns")
        if (map.keys.intersect(keys).isEmpty()) {
            Toast.makeText(this, "QR: no recognizable fields", Toast.LENGTH_SHORT).show()
            return
        }
        map["host"]?.let { etHost.setText(it) }
        map["port"]?.let { etPort.setText(it) }
        map["key"]?.let { etKey.setText(it) }
        map["id"]?.let { etId.setText(it) }
        map["dns"]?.let { etDns.setText(it) }
        rbModeModem.isChecked = true
        applyModeVisibility(true)
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

    private fun exportConnectionSettings(
        mode: String, host: String, port: String, key: String, id: String, dns: String,
    ) {
        try {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val content = buildString {
                appendLine("# Proxy Agent — Connection Settings Export")
                appendLine("# Generated: $stamp")
                appendLine("# Import via settings → IMPORT.")
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

    private fun importSettingsFromUri(uri: Uri) {
        try {
            val content = contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
                ?: run {
                    Toast.makeText(this, "Import: cannot read file", Toast.LENGTH_SHORT).show(); return
                }
            val map = parseKeyValueLines(content)
            val allowed = setOf("mode", "host", "port", "key", "id", "dns")
            val applied = map.keys.intersect(allowed)
            if (applied.isEmpty()) {
                Toast.makeText(this, "Import: no connection settings in file", Toast.LENGTH_SHORT).show()
                return
            }
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
            loadFromPrefs()   // refresh the form with the imported values
        } catch (e: Throwable) {
            Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

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
            Toast.makeText(this, "No Play Services scanner; falling back to ZXing", Toast.LENGTH_SHORT).show()
            qrLauncher.launch(buildScanOptions())
        }
    }

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

    // ── Wi-Fi return preflight (moved verbatim from MainActivity) ───────────

    private fun refuseDueToCachedSplitFail(checkbox: CheckBox): Boolean {
        val info = try {
            val f = File(filesDir, "wifi_info.json")
            if (!f.exists()) return false
            org.json.JSONObject(f.readText())
        } catch (_: Throwable) { return false }

        val testResult = info.optString("test_result", "")
        val testedAtMs = info.optLong("tested_at_ms", 0L)
        val freshnessWindowMs = 24L * 60 * 60 * 1000
        if (testResult != "SAME_IP" ||
            testedAtMs <= 0L ||
            System.currentTimeMillis() - testedAtMs >= freshnessWindowMs) {
            return false
        }
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
            .setPositiveButton("Show instructions") { _, _ -> showMobileDataAlwaysOnInstructions() }
            .setNegativeButton("Close", null)
            .show()
        return true
    }

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
            if (report.result != MobileDataAlwaysOnCheck.Result.BLOCKED) return@Thread
            // Guard: the check is async; the user may have left (finish()) or
            // rotated the activity meanwhile — showing a dialog on a dead
            // window throws BadTokenException.
            runOnUiThread {
                if (!isFinishing && !isDestroyed) showMobileDataAlwaysOnDialog(report, checkbox)
            }
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

        val unCheckWithReason: (String) -> Unit = { reason ->
            checkbox.isChecked = false
            Toast.makeText(this, "Wi-Fi return disabled: $reason", Toast.LENGTH_LONG).show()
        }

        val builder = AlertDialog.Builder(this)
            .setTitle("Cellular drops while on Wi-Fi — can't enable")
            .setCancelable(false)
            .setNegativeButton("Ignore") { _, _ ->
                unCheckWithReason("device blocks parallel transports")
            }

        if (report.canAutoFix) {
            builder.setMessage(
                "$baseMsg\n\nWRITE_SECURE_SETTINGS is granted — we can flip " +
                "the system toggle for you. Continue?"
            )
            builder.setPositiveButton("Enable") { _, _ ->
                Thread {
                    val ok = try { MobileDataAlwaysOnCheck.tryEnable(this) } catch (_: Throwable) { false }
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (ok) {
                            Toast.makeText(
                                this, "Enabled mobile_data_always_on=1 — Wi-Fi return ready",
                                Toast.LENGTH_LONG,
                            ).show()
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
            "   adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS\n" +
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

    private fun readFile(name: String): String =
        try { File(filesDir, name).readText().trim() } catch (_: Throwable) { "" }
}
