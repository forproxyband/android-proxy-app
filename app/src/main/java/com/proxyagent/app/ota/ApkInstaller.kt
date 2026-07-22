package com.proxyagent.app.ota

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

// ────────────────────────────────────────────────────────────────────────
// Hands a decrypted APK to the system package installer. The APK must be
// signed with the same key as the installed app (Android rejects a differently
// -signed update) — the OTA build is the same CI release artifact, so this
// holds. On API 26+ the app needs the per-app "install unknown apps" grant.
// ────────────────────────────────────────────────────────────────────────

object ApkInstaller {

    private const val APK_MIME = "application/vnd.android.package-archive"

    /** True when the OS will let this app launch an install (always true < API 26). */
    fun canInstall(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return ctx.packageManager.canRequestPackageInstalls()
    }

    /**
     * Open the system screen where the user grants "install unknown apps" to
     * this app. No-op below API 26. Returns true if a screen was launched.
     */
    fun requestInstallPermission(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${ctx.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(intent) }.isSuccess
    }

    /**
     * Launch the system installer for [apk]. Requires [canInstall]; callers
     * should route through the permission flow first.
     */
    fun install(ctx: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(
            ctx,
            "${ctx.packageName}.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }
}
