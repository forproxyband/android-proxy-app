package com.proxyagent.app.ota

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.proxyagent.app.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ────────────────────────────────────────────────────────────────────────
// Updates screen. Focus: install the SELECTED CHANNEL's actual (current)
// version — one primary button. Manual re-check + "last checked" line above.
// Downgrade is a separate, collapsed-by-default section (older versions).
//
// Install path adapts to root (probed per reload): with root, everything
// (upgrade / reinstall / downgrade) installs silently via `pm install -r -d`;
// without root, an upgrade uses the system installer and a downgrade is saved
// to Downloads (Android can't downgrade in place). A background auto-update
// toggle (root only) is exposed here and honoured by OtaUpdateWorker.
//
// Channels are dynamic (populated from the manifest at runtime). Network +
// crypto run on plain background threads; UI updated via runOnUiThread.
// ────────────────────────────────────────────────────────────────────────

class UpdatesActivity : AppCompatActivity() {

    private lateinit var tvInstalled: TextView
    private lateinit var spChannel: Spinner
    private lateinit var tvStatus: TextView
    private lateinit var tvLastCheck: TextView
    private lateinit var btnInstall: Button
    private lateinit var btnCheck: Button
    private lateinit var btnDowngrade: Button
    private lateinit var cbAutoUpdate: CheckBox
    private lateinit var svDowngrade: View
    private lateinit var llDowngrade: LinearLayout

    private var selectedChannel: OtaChannel = OtaChannel.STABLE
    private var channels: List<OtaChannel> = OtaChannel.KNOWN
    private var currentRelease: CurrentRelease? = null
    private var downgradeExpanded = false
    // Set from a background root probe on each reload; drives silent install
    // (root handles both upgrade and downgrade with `pm install -r -d`).
    @Volatile private var rootAvailable = false
    // Guards the auto-update checkbox listener during programmatic state changes,
    // and while a root self-test is in flight.
    private var suppressAutoUpdateListener = false
    @Volatile private var autoUpdateTesting = false

    private var suppressSpinner = false
    @Volatile private var busy = false
    private var progressDialog: AlertDialog? = null

    private enum class InstallAction { ROOT_INSTALL, SYSTEM_INSTALL, SAVE_TO_DOWNLOADS }

    private var pendingSave: Pair<HistoryEntry, String?>? = null
    private val storagePermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val p = pendingSave
            pendingSave = null
            when {
                granted && p != null -> perform(p.first, p.second, InstallAction.SAVE_TO_DOWNLOADS)
                !granted -> Toast.makeText(this, "Storage permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_updates)
        title = "Updates"

        tvInstalled = findViewById(R.id.tvInstalled)
        spChannel = findViewById(R.id.spChannel)
        tvStatus = findViewById(R.id.tvStatus)
        tvLastCheck = findViewById(R.id.tvLastCheck)
        btnInstall = findViewById(R.id.btnInstall)
        btnCheck = findViewById(R.id.btnCheck)
        btnDowngrade = findViewById(R.id.btnDowngrade)
        cbAutoUpdate = findViewById(R.id.cbAutoUpdate)
        svDowngrade = findViewById(R.id.svDowngrade)
        llDowngrade = findViewById(R.id.llDowngrade)

        tvInstalled.text =
            "Installed: v${OtaManager.installedVersionName()} (build ${OtaManager.installedBuild()})"

        selectedChannel = OtaConfig.channel(this)
        rebuildSpinner(OtaManifest.discoverChannels(emptyList(), selectedChannel))

        spChannel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressSpinner) return
                val ch = channels.getOrNull(position) ?: return
                if (ch != selectedChannel) {
                    selectedChannel = ch
                    OtaConfig.setChannel(this@UpdatesActivity, ch)
                    downgradeExpanded = false
                    reload()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnCheck.setOnClickListener { reload() }
        btnDowngrade.setOnClickListener { toggleDowngrade() }
        bindAutoUpdateCheckbox()

        reload()
    }

    override fun onDestroy() {
        progressDialog?.dismiss()
        progressDialog = null
        super.onDestroy()
    }

    private fun rebuildSpinner(list: List<OtaChannel>) {
        channels = list
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, list.map { it.label })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        val idx = list.indexOf(selectedChannel).let { if (it >= 0) it else 0 }
        suppressSpinner = true
        spChannel.adapter = adapter
        if (list.isNotEmpty()) spChannel.setSelection(idx)
        spChannel.post { suppressSpinner = false }
    }

    private fun reload() {
        if (busy) return
        if (!OtaConfig.isConfigured()) {
            setStatus("OTA is not configured for this build.", "#888888")
            tvLastCheck.text = ""
            btnInstall.visibility = View.GONE
            btnDowngrade.visibility = View.GONE
            cbAutoUpdate.visibility = View.GONE
            svDowngrade.visibility = View.GONE
            btnCheck.isEnabled = false
            spChannel.isEnabled = false
            return
        }
        busy = true
        val channel = selectedChannel
        setStatus("Checking…", "#888888")
        btnCheck.isEnabled = false

        Thread {
            var releases: List<CurrentRelease> = emptyList()
            var history: List<HistoryEntry> = emptyList()
            var error: String? = null
            try {
                releases = OtaManager.fetchCurrentReleases()
                history = OtaManager.history(channel)
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
            }
            if (error == null) OtaConfig.recordCheck(this)
            val root = RootInstaller.isRootAvailable()
            val current = OtaManifest.findChannel(releases, channel)
            val status = OtaManifest.statusFor(current, OtaManager.installedBuild())
            val discovered = OtaManifest.discoverChannels(releases, channel)
            runOnUiThread {
                busy = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                rootAvailable = root
                btnCheck.isEnabled = true
                if (error != null) {
                    setStatus("Check failed: $error", "#FF4444")
                    return@runOnUiThread
                }
                rebuildSpinner(discovered)
                currentRelease = current
                renderStatus(status)
                renderLastCheck()
                bindAutoUpdateCheckbox()
                renderInstallButton(current)
                renderDowngrade(history, current)
            }
        }.apply { isDaemon = true; name = "OtaReload"; start() }
    }

    // Auto-update checkbox is always offered. Enabling it runs a non-destructive
    // root self-test (`su -c id`, no install); if root is absent the toggle is
    // reverted and the user is told it can't be enabled. Disabling needs no test.
    private fun bindAutoUpdateCheckbox() {
        cbAutoUpdate.visibility = View.VISIBLE
        setAutoUpdateChecked(OtaConfig.autoUpdate(this))
        cbAutoUpdate.setOnCheckedChangeListener { _, checked ->
            if (suppressAutoUpdateListener) return@setOnCheckedChangeListener
            if (checked) verifyRootThenEnable() else OtaConfig.setAutoUpdate(this, false)
        }
    }

    /** Set the checkbox state without firing the listener. */
    private fun setAutoUpdateChecked(value: Boolean) {
        suppressAutoUpdateListener = true
        cbAutoUpdate.isChecked = value
        suppressAutoUpdateListener = false
    }

    // Non-destructive root check (`su -c id`) — NO reinstall. On success, enable
    // auto-update; on failure, revert the checkbox and explain.
    private fun verifyRootThenEnable() {
        if (autoUpdateTesting) return
        autoUpdateTesting = true
        cbAutoUpdate.isEnabled = false
        Toast.makeText(this, "Checking root access…", Toast.LENGTH_SHORT).show()
        Thread {
            val root = RootInstaller.isRootAvailable()
            runOnUiThread {
                autoUpdateTesting = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                cbAutoUpdate.isEnabled = true
                rootAvailable = root
                if (root) {
                    OtaConfig.setAutoUpdate(this, true)
                    Toast.makeText(this, "Auto-update enabled", Toast.LENGTH_SHORT).show()
                } else {
                    OtaConfig.setAutoUpdate(this, false)
                    setAutoUpdateChecked(false)
                    Toast.makeText(
                        this, "Can't enable: no root access on this device.", Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }.apply { isDaemon = true; name = "OtaRootSelfTest"; start() }
    }

    private fun renderStatus(status: UpdateStatus) {
        when (status) {
            is UpdateStatus.Available ->
                setStatus(
                    "Update available: ${status.release.version} (build ${status.release.build})",
                    "#FFCC66",
                )
            is UpdateStatus.UpToDate ->
                setStatus(
                    "You're on the latest: ${status.release.version} (build ${status.release.build})",
                    "#88ffaa",
                )
            UpdateStatus.NoRelease ->
                setStatus("No release published in this channel.", "#888888")
        }
    }

    private fun renderLastCheck() {
        val ts = OtaConfig.lastCheckMs(this)
        tvLastCheck.text = if (ts <= 0L) {
            "Last checked: never"
        } else {
            val now = System.currentTimeMillis()
            val rel = if (now - ts < DateUtils.MINUTE_IN_MILLIS) "just now"
            else DateUtils.getRelativeTimeSpanString(ts, now, DateUtils.MINUTE_IN_MILLIS).toString()
            val abs = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ts))
            "Last checked: $rel ($abs)"
        }
    }

    // Primary button installs the channel's actual (current) version. If that
    // version is older than installed (a published rollback), it's treated as a
    // downgrade (root: silent; no root: save-to-Downloads).
    private fun renderInstallButton(current: CurrentRelease?) {
        if (current == null) {
            btnInstall.visibility = View.GONE
            return
        }
        val installed = OtaManager.installedBuild()
        btnInstall.visibility = View.VISIBLE
        btnInstall.text = when {
            current.build > installed -> "INSTALL ${current.version}"
            current.build == installed -> "REINSTALL ${current.version}"
            else -> "INSTALL ${current.version} (older)"
        }
        btnInstall.setOnClickListener {
            val entry = HistoryEntry("current", current.version, current.build, current.fileName)
            confirmInstall(entry, current.sha256, isDowngrade = current.build < installed)
        }
    }

    // Downgrade section: older-than-installed versions that are NOT the current
    // one (that's the primary button). Collapsed by default; each entry only
    // offers "save to Downloads" for a manual uninstall+install.
    private fun renderDowngrade(history: List<HistoryEntry>, current: CurrentRelease?) {
        val installed = OtaManager.installedBuild()
        val candidates = history.filter {
            it.build < installed && it.fileName != current?.fileName
        }
        llDowngrade.removeAllViews()
        if (candidates.isEmpty()) {
            btnDowngrade.visibility = View.GONE
            svDowngrade.visibility = View.GONE
            downgradeExpanded = false
            return
        }
        btnDowngrade.visibility = View.VISIBLE
        for (entry in candidates) llDowngrade.addView(downgradeRow(entry))
        applyDowngradeExpanded()
    }

    private fun toggleDowngrade() {
        downgradeExpanded = !downgradeExpanded
        applyDowngradeExpanded()
    }

    private fun applyDowngradeExpanded() {
        svDowngrade.visibility = if (downgradeExpanded) View.VISIBLE else View.GONE
        btnDowngrade.text = if (downgradeExpanded) "DOWNGRADE ▲" else "DOWNGRADE ▼"
    }

    private fun downgradeRow(entry: HistoryEntry): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(8), dp(6), dp(8))
        }
        val label = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            text = "${entry.version}  (build ${entry.build})"
            setTextColor(0xFFdddddd.toInt())
            textSize = 13f
        }
        val btn = Button(this).apply {
            text = if (rootAvailable) "Install" else "To Downloads"
            textSize = 11f
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(12), 0, dp(12), 0)
            setTextColor(0xFFFFCC66.toInt())
            setBackgroundColor(0xFF16213e.toInt())
            setOnClickListener { confirmInstall(entry, null, isDowngrade = true) }
        }
        row.addView(label)
        row.addView(btn)
        return row
    }

    // Single confirm for install / reinstall / downgrade. The messaging and the
    // action adapt to root availability: with root, everything (incl. downgrade)
    // installs silently via `pm install -r -d`; without root, an upgrade goes
    // through the system installer and a downgrade is saved to Downloads.
    private fun confirmInstall(entry: HistoryEntry, sha: String?, isDowngrade: Boolean) {
        if (busy) return
        val installed = OtaManager.installedBuild()
        val sb = StringBuilder()
        sb.append("Version ${entry.version} (build ${entry.build}).\n\n")
        if (sha == null) {
            sb.append("Not the channel's current release — integrity hash isn't published; ")
            sb.append("only the APK signature is verified.\n\n")
        }
        if (isDowngrade) {
            if (rootAvailable) {
                sb.append("Older than the installed build ($installed); root installs it directly.\n\n")
            } else {
                sb.append("Older than the installed build ($installed). Android can't downgrade ")
                sb.append("in place, so the APK will be saved to Downloads — uninstall the app, ")
                sb.append("then install it manually.\n\n")
            }
        }
        sb.append(if (rootAvailable) "Install now (silent, root)?" else "Proceed?")

        val positive = when {
            rootAvailable -> "Install"
            isDowngrade -> "Save to Downloads"
            else -> "Install"
        }
        AlertDialog.Builder(this)
            .setTitle(if (isDowngrade) "Downgrade to ${entry.version}" else "Install ${entry.version}?")
            .setMessage(sb.toString())
            .setNegativeButton("Cancel", null)
            .setPositiveButton(positive) { _, _ -> startInstall(entry, sha, isDowngrade) }
            .show()
    }

    private fun startInstall(entry: HistoryEntry, sha: String?, isDowngrade: Boolean) {
        when {
            rootAvailable -> perform(entry, sha, InstallAction.ROOT_INSTALL)
            isDowngrade -> startSaveToDownloads(entry, sha)
            else -> ensurePermissionThenInstall(entry, sha)
        }
    }

    private fun ensurePermissionThenInstall(entry: HistoryEntry, sha: String?) {
        if (!ApkInstaller.canInstall(this)) {
            AlertDialog.Builder(this)
                .setTitle("Allow installs")
                .setMessage(
                    "To install updates, allow \"install unknown apps\" for Proxy Agent, " +
                        "then tap Install again."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open settings") { _, _ -> ApkInstaller.requestInstallPermission(this) }
                .show()
            return
        }
        perform(entry, sha, InstallAction.SYSTEM_INSTALL)
    }

    private fun startSaveToDownloads(entry: HistoryEntry, sha: String?) {
        if (busy) return
        if (OtaExport.needsLegacyStoragePermission() &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingSave = entry to sha
            storagePermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        perform(entry, sha, InstallAction.SAVE_TO_DOWNLOADS)
    }

    // Shared pipeline: download → decrypt → verify (with progress), then either
    // launch the installer or save the APK to Downloads.
    private fun perform(entry: HistoryEntry, sha: String?, action: InstallAction) {
        if (busy) return
        busy = true

        val progressText = TextView(this).apply {
            text = "Starting…"
            setTextColor(0xFFdddddd.toInt())
            textSize = 13f
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(4))
            addView(progressText)
            addView(bar)
        }
        val title = if (action == InstallAction.SAVE_TO_DOWNLOADS) "Preparing ${entry.version}"
        else "Installing ${entry.version}"
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(container)
            .setCancelable(false)
            .create()
        dialog.show()
        progressDialog = dialog

        Thread {
            var apk: File? = null
            var savedLocation: String? = null
            var rootInstalled: Boolean? = null
            var error: String? = null
            try {
                apk = OtaManager.prepare(this, entry.fileName, sha) { phase, soFar, total ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        when (phase) {
                            OtaManager.Phase.DOWNLOAD -> {
                                if (total > 0) {
                                    bar.isIndeterminate = false
                                    bar.progress = ((soFar * 100) / total).toInt().coerceIn(0, 100)
                                    progressText.text = "Downloading… ${humanMb(soFar)} / ${humanMb(total)}"
                                } else {
                                    bar.isIndeterminate = true
                                    progressText.text = "Downloading… ${humanMb(soFar)}"
                                }
                            }
                            OtaManager.Phase.VERIFY -> {
                                bar.isIndeterminate = true
                                progressText.text = "Decrypting & verifying…"
                            }
                        }
                    }
                }
                if (apk != null) when (action) {
                    InstallAction.ROOT_INSTALL -> {
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) progressText.text = "Installing (root)…"
                        }
                        // Replaces this app → our process is killed at commit; the
                        // return value may never be observed (that's fine).
                        rootInstalled = RootInstaller.installSilently(apk!!)
                    }
                    InstallAction.SAVE_TO_DOWNLOADS -> {
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) progressText.text = "Saving to Downloads…"
                        }
                        savedLocation = OtaExport.saveToDownloads(
                            this, apk!!, OtaExport.downloadsFileName(entry.version, entry.build),
                        )
                    }
                    InstallAction.SYSTEM_INSTALL -> Unit // launched on the UI thread below
                }
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
            }
            val readyApk = apk
            val location = savedLocation
            val rootOk = rootInstalled
            runOnUiThread {
                busy = false
                dialog.dismiss()
                progressDialog = null
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (error != null || readyApk == null) {
                    Toast.makeText(this, "Failed: ${error ?: "unknown error"}", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                when (action) {
                    InstallAction.ROOT_INSTALL -> Toast.makeText(
                        this,
                        if (rootOk == true) "Installed ${entry.version}"
                        else "Root install failed — check su/Magisk grant",
                        Toast.LENGTH_LONG,
                    ).show()
                    InstallAction.SYSTEM_INSTALL -> try {
                        ApkInstaller.install(this, readyApk)
                    } catch (t: Throwable) {
                        Toast.makeText(this, "Install failed: ${t.message}", Toast.LENGTH_LONG).show()
                    }
                    InstallAction.SAVE_TO_DOWNLOADS -> AlertDialog.Builder(this)
                        .setTitle("Saved to Downloads")
                        .setMessage(
                            "Saved: $location\n\nTo downgrade: uninstall Proxy Agent, then open " +
                                "this file (Files app / Downloads) and install it."
                        )
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }.apply { isDaemon = true; name = "OtaPerform"; start() }
    }

    private fun setStatus(text: String, colorHex: String) {
        tvStatus.text = text
        tvStatus.setTextColor(android.graphics.Color.parseColor(colorHex))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun humanMb(bytes: Long): String =
        String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}
