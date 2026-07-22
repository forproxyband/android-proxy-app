package com.proxyagent.app.ota

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

// ────────────────────────────────────────────────────────────────────────
// "Update available" notification posted by the background worker. Tapping it
// opens UpdatesActivity. Uses its own channel (separate from the proxy
// foreground-service channel) so the user can mute updates independently.
// ────────────────────────────────────────────────────────────────────────

object OtaNotifications {

    private const val CHANNEL_ID = "ota_updates"
    private const val NOTIF_ID = 4711

    fun showUpdateAvailable(ctx: Context, release: CurrentRelease) {
        if (!canPost(ctx)) return
        ensureChannel(ctx)

        val intent = Intent(ctx, UpdatesActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            ctx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Update available (${release.channel.label})")
            .setContentText("${release.version} (build ${release.build}) — tap to install")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        NotificationManagerCompat.from(ctx).notify(NOTIF_ID, n)
    }

    fun cancel(ctx: Context) {
        NotificationManagerCompat.from(ctx).cancel(NOTIF_ID)
    }

    private fun canPost(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }
}
