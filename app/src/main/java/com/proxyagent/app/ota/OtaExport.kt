package com.proxyagent.app.ota

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException

// ────────────────────────────────────────────────────────────────────────
// Saves a prepared (decrypted, verified) APK into the public Downloads folder
// for MANUAL installation. This is the downgrade path: Android refuses to
// install a lower versionCode over an installed app, so instead of a failing
// in-place install we drop the file where the user can install it after
// uninstalling the app.
//
// API 29+ uses MediaStore (no storage permission). API 23-28 writes the legacy
// public Downloads dir and needs WRITE_EXTERNAL_STORAGE (caller grants it).
// ────────────────────────────────────────────────────────────────────────

object OtaExport {

    private const val APK_MIME = "application/vnd.android.package-archive"

    /** True on API < 29, where writing public Downloads needs WRITE_EXTERNAL_STORAGE. */
    fun needsLegacyStoragePermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    fun downloadsFileName(version: String, build: Long): String {
        // `version` comes from the manifest — sanitize so it can't inject a path
        // separator (e.g. "../") into the legacy File(dir, name) save path.
        val safeVersion = version.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "unknown" }
        return "proxy-agent-v$safeVersion-build$build.apk"
    }

    /** Copy [apk] into public Downloads as [displayName]; returns a user-facing location. */
    fun saveToDownloads(ctx: Context, apk: File, displayName: String): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveViaMediaStore(ctx, apk, displayName)
        else saveLegacy(apk, displayName)

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveViaMediaStore(ctx: Context, apk: File, displayName: String): String {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, APK_MIME)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                apk.inputStream().use { it.copyTo(out) }
            } ?: throw IOException("could not open Downloads output stream")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            // Don't leave an orphaned IS_PENDING entry in the user's Downloads.
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
        return "Downloads/$displayName"
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(apk: File, displayName: String): String {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        dir.mkdirs()
        val dst = File(dir, displayName)
        apk.copyTo(dst, overwrite = true)
        return dst.absolutePath
    }
}
