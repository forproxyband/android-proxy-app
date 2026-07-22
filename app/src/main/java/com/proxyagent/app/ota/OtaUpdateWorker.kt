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
            val lastAutoInstalled = prefs.getLong(KEY_AUTOINSTALLED_BUILD, -1L)
            // `force` bypasses notify dedup. `notifyOnly` = the manual long-press
            // test trigger: it must only check + notify, never silently reinstall.
            val force = inputData.getBoolean(KEY_FORCE, false)
            val notifyOnly = inputData.getBoolean(KEY_NOTIFY_ONLY, false)

            if (status is UpdateStatus.Available) {
                val build = status.release.build
                // Auto-update: silently install when enabled, root is available,
                // this isn't the notify-only trigger, and we haven't ALREADY tried
                // this exact build. The last guard breaks a reinstall loop when the
                // CRM `build` label doesn't match the APK's real versionCode (the
                // installed build never advances, so it would look "available"
                // forever and reinstall every run).
                if (OtaConfig.autoUpdate(ctx) && !notifyOnly &&
                    build != lastAutoInstalled && RootInstaller.isRootAvailable()) {
                    // Record the attempt BEFORE installing: the commit kills this
                    // process, so post-install code may never run.
                    prefs.edit().putLong(KEY_AUTOINSTALLED_BUILD, build).apply()
                    val apk = runCatching {
                        OtaManager.prepare(ctx, status.release.fileName, status.release.sha256)
                    }.getOrNull()
                    if (apk != null && RootInstaller.installSilently(apk, allowDowngrade = false)) {
                        OtaNotifications.cancel(ctx)
                        prefs.edit().remove(KEY_NOTIFIED_BUILD).apply()
                        return Result.success()
                    }
                    // else: install failed or already-attempted this build (likely a
                    // build/versionCode mismatch) — fall through to notify.
                }
                if (force || build != lastNotified) {
                    // Only mark as notified if the notification actually posted —
                    // otherwise (POST_NOTIFICATIONS denied) we'd suppress the update
                    // silently AND record it as already shown.
                    if (OtaNotifications.showUpdateAvailable(ctx, status.release)) {
                        prefs.edit().putLong(KEY_NOTIFIED_BUILD, build).apply()
                    }
                }
            } else {
                // Up to date / no release: reset both markers so a future
                // (re)published build is handled fresh.
                prefs.edit().remove(KEY_NOTIFIED_BUILD).remove(KEY_AUTOINSTALLED_BUILD).apply()
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
        const val KEY_NOTIFY_ONLY = "notify_only"
        private const val KEY_NOTIFIED_BUILD = "ota_notified_build"
        private const val KEY_AUTOINSTALLED_BUILD = "ota_autoinstalled_build"
    }
}
