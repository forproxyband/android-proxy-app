package com.proxyagent.app.ota

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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

// ────────────────────────────────────────────────────────────────────────
// Updates screen: pick a channel, see whether the tracked version is current,
// and install any published version — including rolling back to an older one.
//
// Channels are DYNAMIC: the selector is populated from the manifest at runtime
// (baseline stable/beta/dev ∪ channels that currently have a release ∪ the
// tracked one), so channels can be dropped or added CRM-side without a client
// change. Network + crypto run on plain background threads (matching the app).
// ────────────────────────────────────────────────────────────────────────

class UpdatesActivity : AppCompatActivity() {

    private lateinit var tvInstalled: TextView
    private lateinit var spChannel: Spinner
    private lateinit var tvStatus: TextView
    private lateinit var btnCheck: Button
    private lateinit var llVersions: LinearLayout

    private var selectedChannel: OtaChannel = OtaChannel.STABLE
    private var channels: List<OtaChannel> = OtaChannel.KNOWN

    // Guards the spinner listener while we rebuild it programmatically.
    private var suppressSpinner = false

    @Volatile private var busy = false

    // Progress dialog for an in-flight install; dismissed in onDestroy so a
    // rotation / backgrounding mid-download doesn't leak its window.
    private var progressDialog: AlertDialog? = null

    private enum class InstallAction { INSTALL, SAVE_TO_DOWNLOADS }

    // Held while a legacy (API<29) WRITE_EXTERNAL_STORAGE request is in flight.
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
        btnCheck = findViewById(R.id.btnCheck)
        llVersions = findViewById(R.id.llVersions)

        tvInstalled.text =
            "Installed: v${OtaManager.installedVersionName()} (build ${OtaManager.installedBuild()})"

        selectedChannel = OtaConfig.channel(this)
        // Seed the selector before the network answers (baseline ∪ tracked).
        rebuildSpinner(OtaManifest.discoverChannels(emptyList(), selectedChannel))

        spChannel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressSpinner) return
                val ch = channels.getOrNull(position) ?: return
                if (ch != selectedChannel) {
                    selectedChannel = ch
                    OtaConfig.setChannel(this@UpdatesActivity, ch)
                    reload()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnCheck.setOnClickListener { reload() }

        reload()
    }

    override fun onDestroy() {
        progressDialog?.dismiss()
        progressDialog = null
        super.onDestroy()
    }

    /** Rebuild the channel spinner, preserving the current selection. */
    private fun rebuildSpinner(list: List<OtaChannel>) {
        channels = list
        val labels = list.map { it.label }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        val idx = list.indexOf(selectedChannel).let { if (it >= 0) it else 0 }
        suppressSpinner = true
        spChannel.adapter = adapter
        if (list.isNotEmpty()) spChannel.setSelection(idx)
        // Spinner.setSelection dispatches onItemSelected asynchronously; keep the
        // guard up until that has drained so a programmatic rebuild never looks
        // like a user pick (which would flip the tracked channel).
        spChannel.post { suppressSpinner = false }
    }

    /** Fetch current-versions + history for the selected channel and render. */
    private fun reload() {
        if (busy) return
        busy = true
        val channel = selectedChannel
        setStatus("Checking…", "#888888")
        llVersions.removeAllViews()
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
                renderStatus(status)
                renderVersions(history, current)
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

    private fun renderVersions(history: List<HistoryEntry>, current: CurrentRelease?) {
        llVersions.removeAllViews()
        if (history.isEmpty()) {
            val tv = TextView(this).apply {
                text = "No versions published in this channel."
                setTextColor(0xFF888888.toInt())
                textSize = 13f
                setPadding(dp(6), dp(10), dp(6), dp(10))
            }
            llVersions.addView(tv)
            return
        }
        val installed = OtaManager.installedBuild()
        for (entry in history) {
            // Only the channel's current build has a published SHA-256.
            val sha = if (current != null && entry.fileName == current.fileName) current.sha256 else null
            llVersions.addView(versionRow(entry, sha, installed))
        }
    }

    private fun versionRow(entry: HistoryEntry, sha: String?, installed: Long): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(8), dp(6), dp(8))
        }

        val marker = when {
            entry.build == installed -> "  • installed"
            entry.isCurrent -> "  • current"
            else -> ""
        }
        val label = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            text = "${entry.version}  (build ${entry.build})$marker"
            setTextColor(if (entry.build == installed) 0xFF88ffaa.toInt() else 0xFFdddddd.toInt())
            textSize = 13f
        }

        val btn = Button(this).apply {
            val down = entry.build < installed
            text = when {
                entry.build == installed -> "Reinstall"
                down -> "Downgrade"
                else -> "Install"
            }
            textSize = 11f
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(12), 0, dp(12), 0)
            setTextColor(0xFFe94560.toInt())
            setBackgroundColor(0xFF16213e.toInt())
            setOnClickListener {
                if (entry.build < installed) confirmDowngrade(entry, sha)
                else confirmInstall(entry, sha)
            }
        }

        row.addView(label)
        row.addView(btn)
        return row
    }

    // Install / reinstall (build >= installed). In-place upgrade via the installer.
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

    // Downgrade (build < installed). Android refuses an in-place downgrade, so
    // we save the decrypted APK to Downloads for a manual uninstall+install.
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
        String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}
