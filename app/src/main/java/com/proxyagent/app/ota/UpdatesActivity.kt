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
// Downgrade is a separate, collapsed-by-default section (older versions), and
// since Android can't downgrade in place it only saves the APK to Downloads.
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
    private lateinit var svDowngrade: View
    private lateinit var llDowngrade: LinearLayout

    private var selectedChannel: OtaChannel = OtaChannel.STABLE
    private var channels: List<OtaChannel> = OtaChannel.KNOWN
    private var currentRelease: CurrentRelease? = null
    private var downgradeExpanded = false

    private var suppressSpinner = false
    @Volatile private var busy = false
    private var progressDialog: AlertDialog? = null

    private enum class InstallAction { INSTALL, SAVE_TO_DOWNLOADS }

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
            val current = OtaManifest.findChannel(releases, channel)
            val status = OtaManifest.statusFor(current, OtaManager.installedBuild())
            val discovered = OtaManifest.discoverChannels(releases, channel)
            runOnUiThread {
                busy = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                btnCheck.isEnabled = true
                if (error != null) {
                    setStatus("Check failed: $error", "#FF4444")
                    return@runOnUiThread
                }
                rebuildSpinner(discovered)
                currentRelease = current
                renderStatus(status)
                renderLastCheck()
                renderInstallButton(current)
                renderDowngrade(history, current)
            }
        }.apply { isDaemon = true; name = "OtaReload"; start() }
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

    // Primary button installs the channel's actual (current) version. When that
    // version is older than what's installed (a published rollback) it routes
    // through the save-to-Downloads path, since Android can't downgrade in place.
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
            if (current.build < installed) confirmDowngrade(entry, current.sha256)
            else confirmInstall(entry, current.sha256)
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
            text = "To Downloads"
            textSize = 11f
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(12), 0, dp(12), 0)
            setTextColor(0xFFFFCC66.toInt())
            setBackgroundColor(0xFF16213e.toInt())
            setOnClickListener { confirmDowngrade(entry, null) }
        }
        row.addView(label)
        row.addView(btn)
        return row
    }

    // Install / reinstall (build >= installed). In-place install via the installer.
    private fun confirmInstall(entry: HistoryEntry, sha: String?) {
        if (busy) return
        val sb = StringBuilder()
        sb.append("Version ${entry.version} (build ${entry.build}).\n\n")
        if (sha == null) {
            sb.append("Note: this build is not the channel's current release, so its ")
            sb.append("integrity hash isn't published — only the APK signature is verified.\n\n")
        }
        sb.append("Download, decrypt and install now?")

        AlertDialog.Builder(this)
            .setTitle("Install ${entry.version}?")
            .setMessage(sb.toString())
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Install") { _, _ -> ensurePermissionThenInstall(entry, sha) }
            .show()
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
        perform(entry, sha, InstallAction.INSTALL)
    }

    // Downgrade (build < installed). Android refuses in-place downgrade, so we
    // save the decrypted APK to Downloads for a manual uninstall+install.
    private fun confirmDowngrade(entry: HistoryEntry, sha: String?) {
        if (busy) return
        val installed = OtaManager.installedBuild()
        val sb = StringBuilder()
        sb.append("Automatic downgrade is not possible — Android refuses to install ")
        sb.append("build ${entry.build} over the newer installed build ($installed) ")
        sb.append("(\"App not installed\").\n\n")
        if (sha == null) {
            sb.append("Its integrity hash isn't published (not the channel's current), so only ")
            sb.append("the APK signature is verified.\n\n")
        }
        sb.append("Instead, the APK will be downloaded, decrypted and saved to your Downloads ")
        sb.append("folder. To apply it: uninstall Proxy Agent, then open the saved file and install it.")

        AlertDialog.Builder(this)
            .setTitle("Downgrade to ${entry.version}")
            .setMessage(sb.toString())
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save to Downloads") { _, _ -> startSaveToDownloads(entry, sha) }
            .show()
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
                if (action == InstallAction.SAVE_TO_DOWNLOADS && apk != null) {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) progressText.text = "Saving to Downloads…"
                    }
                    savedLocation = OtaExport.saveToDownloads(
                        this, apk!!, OtaExport.downloadsFileName(entry.version, entry.build),
                    )
                }
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
            }
            val readyApk = apk
            val location = savedLocation
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
                    InstallAction.INSTALL -> try {
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
