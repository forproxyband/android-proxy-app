package com.proxyagent.app.ota

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

// ────────────────────────────────────────────────────────────────────────
// Periodic background check of the tracked channel. Never downloads — it only
// posts an "update available" notification (deduped by build number so the
// user isn't re-notified for the same version each run). doWork() runs on a
// WorkManager background thread.
// ────────────────────────────────────────────────────────────────────────

class OtaUpdateWorker(
    ctx: Context,
    params: WorkerParameters,
) : Worker(ctx, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        if (!OtaConfig.isConfigured()) return Result.success()
        return try {
            val channel = OtaConfig.channel(ctx)
            val status = OtaManager.check(ctx, channel)
            OtaConfig.recordCheck(ctx)
            val prefs = ctx.getSharedPreferences("cfg", 0)
            val lastNotified = prefs.getLong(KEY_NOTIFIED_BUILD, -1L)
            // Manual test runs force a notification even if already shown for this build.
            val force = inputData.getBoolean(KEY_FORCE, false)

            if (status is UpdateStatus.Available) {
                // Auto-update: silently install in the background when enabled and
                // root is available. Installing our own update replaces the app and
                // kills this process at commit — PackageReplacedReceiver restarts the
                // service; if the install fails we fall through to a notification.
                if (OtaConfig.autoUpdate(ctx) && RootInstaller.isRootAvailable()) {
                    val apk = runCatching {
                        OtaManager.prepare(ctx, status.release.fileName, status.release.sha256)
                    }.getOrNull()
                    if (apk != null && RootInstaller.installSilently(apk)) {
                        OtaNotifications.cancel(ctx)
                        prefs.edit().remove(KEY_NOTIFIED_BUILD).apply()
                        return Result.success()
                    }
                    // else: install failed — notify so the user can act manually.
                }
                if (force || status.release.build != lastNotified) {
                    OtaNotifications.showUpdateAvailable(ctx, status.release)
                    prefs.edit().putLong(KEY_NOTIFIED_BUILD, status.release.build).apply()
                }
            } else {
                // Up to date / no release: clear dedupe marker + any stale notice.
                prefs.edit().remove(KEY_NOTIFIED_BUILD).apply()
                OtaNotifications.cancel(ctx)
            }
            Result.success()
        } catch (_: Throwable) {
            // Transient (network/offline) — let WorkManager retry with backoff.
            Result.retry()
        }
    }

    companion object {
        const val KEY_FORCE = "force"
        private const val KEY_NOTIFIED_BUILD = "ota_notified_build"
    }
}
