package com.proxyagent.app.ota

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

// ────────────────────────────────────────────────────────────────────────
// Schedules the periodic OTA check. Idempotent (KEEP) — safe to call on every
// app launch. WorkManager is initialized by its default androidx.startup
// provider, so no custom Configuration is needed.
// ────────────────────────────────────────────────────────────────────────

object OtaScheduler {

    private const val WORK_NAME = "ota-update-check"
    private const val WORK_NAME_ONCE = "ota-update-check-once"
    private const val INTERVAL_HOURS = 6L

    fun schedule(ctx: Context) {
        val request = PeriodicWorkRequestBuilder<OtaUpdateWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        // UPDATE (not KEEP): if the interval/constraints change in a later
        // release, already-installed clients pick up the new schedule instead
        // of keeping the original one forever.
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /**
     * Run the check once, right now (subject to network). Used by the long-press
     * test trigger on the main-screen widget; forces a notification even if one
     * was already shown for the current build.
     */
    fun runOnceNow(ctx: Context) {
        val request = OneTimeWorkRequestBuilder<OtaUpdateWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            // notify_only: the long-press test triggers a check + notification,
            // never a silent reinstall. force: bypass the notify dedup marker.
            .setInputData(
                workDataOf(
                    OtaUpdateWorker.KEY_FORCE to true,
                    OtaUpdateWorker.KEY_NOTIFY_ONLY to true,
                )
            )
            .build()
        // Unique + KEEP: rapid long-presses don't spawn concurrent workers that
        // would race on the OTA cache / duplicate notifications.
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            WORK_NAME_ONCE, ExistingWorkPolicy.KEEP, request,
        )
    }
}

