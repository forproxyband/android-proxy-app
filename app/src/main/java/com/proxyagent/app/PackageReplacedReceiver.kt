package com.proxyagent.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File

// Auto-restarts ProxyService after an app upgrade. PACKAGE_REPLACED hard-kills
// both :main and :proxy without giving anyone a chance to clean up, so any
// active modem-tunnel session vanishes the moment the new APK lands. Without
// this receiver the user would have to notice the broken session and re-open
// the app to press START. Many users won't — devices auto-update silently at
// night, and downstream customers learn about the outage before the operator.
//
// Trigger: the system delivers ACTION_MY_PACKAGE_REPLACED to receivers in the
// app whose package was just updated. It's a directed broadcast, so we don't
// need a data filter or scheme — anything reaching this receiver IS about us.
//
// Restart gating: we only auto-start when the previous session was running
// (the "was_running" flag, written by ProxyService.onStartCommand and cleared
// by doStop()). Without this, users who'd intentionally stopped the agent
// before the update would see it pop back up after install — confusing and
// hostile.
//
// Caveats:
//  - OEM auto-start managers (MIUI / EMUI / OneUI) can block this broadcast
//    even when stock Android allows it. There's no app-side workaround; the
//    user has to whitelist us in vendor settings. ADMIN_GUIDE.md documents
//    the per-OEM toggles.
//  - Android 12+ permits startForegroundService from BroadcastReceiver only
//    for a short grace period after onReceive returns. We dispatch the start
//    synchronously to fit the window — no Handler.post, no Thread.
//  - On Android 14+ the FGS-type "specialUse" path is still allowed from
//    package-replaced exemptions, but if Google tightens this further the
//    auto-restart will start throwing ForegroundServiceStartNotAllowed —
//    catch and log so the broadcast doesn't crash the receiver. In that
//    case we surface a fallback notification so the admin still gets a
//    visible signal that the agent needs a manual restart (otherwise the
//    only sign would be the missing FGS notification in the shade, which
//    is easy to overlook on a phone that's been sitting idle overnight).
//  - If credentials are missing (first-run upgrade, or user wiped settings
//    via Storage), we can't reconstruct the start Intent — skip silently.
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        // Stamp the system log first so post-mortem analysis can confirm
        // the broadcast actually arrived (vs. blocked by OEM autostart).
        Log.i(TAG, "MY_PACKAGE_REPLACED received")

        val wasRunning = File(context.filesDir, "was_running").exists()
        if (!wasRunning) {
            Log.i(TAG, "skip auto-restart — previous session was stopped")
            return
        }

        val prefs = context.getSharedPreferences("cfg", 0)
        val host = prefs.getString("h", "")?.trim().orEmpty()
        val port = prefs.getString("p", "")?.trim().orEmpty()
        val key  = prefs.getString("k", "")?.trim().orEmpty()
        if (host.isEmpty() || port.isEmpty() || key.isEmpty()) {
            // Should be unreachable — was_running can only be set if the
            // service started, which requires the same credentials. But if
            // SharedPreferences got wiped between sessions (Storage clear,
            // OEM "smart cleanup"), bail rather than crash.
            Log.w(TAG, "skip auto-restart — credentials missing despite was_running flag")
            return
        }
        val id = prefs.getString("id", "")?.trim().orEmpty()
        val dns = prefs.getString("dns", "")?.trim().orEmpty()
        val engine = prefs.getString("engine", "native") ?: "native"
        val mode = prefs.getString("mode", "modem") ?: "modem"
        val networkProfile = prefs.getString("network_profile", "HIGH_1000") ?: "HIGH_1000"

        val svc = Intent(context, ProxyService::class.java).apply {
            putExtra("host", host); putExtra("port", port); putExtra("key", key)
            putExtra("id", id); putExtra("dns", dns)
            putExtra("engine", engine); putExtra("mode", mode)
            putExtra("network_profile", networkProfile)
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
            Log.i(TAG, "auto-restarted ProxyService (engine=$engine mode=$mode)")
            // Successful path: ProxyService.onStartCommand will call
            // startForeground within a few hundred ms, putting the live
            // status notification (id=1) back in the shade. No fallback
            // notification needed.
        } catch (t: Throwable) {
            // Most likely cause on Android 14+: ForegroundServiceStartNot
            // AllowedException because the system tightened the exemption.
            // Nothing we can do from here — the user will have to open the
            // app and press START. Don't rethrow; a broadcast-receiver crash
            // is a noisy "Process has died" dialog with no upside.
            Log.w(TAG, "auto-restart failed: ${t.javaClass.simpleName}: ${t.message}")
            postAutoRestartFailedNotification(context, t)
        }
    }

    // Visible signal that the agent was running before the update and
    // needs the user to come back and tap START. Without this, the only
    // post-update difference the admin sees is "the persistent notification
    // is gone" — easy to miss when the phone is sitting in a rack
    // overnight. Tapping the notification opens MainActivity, where the
    // staleness check has already wiped the old conn_info so the START
    // button is correctly enabled.
    //
    // Notification id is distinct from the FGS notification (1) and the
    // auto-stop notification (2) so they don't clobber each other. AutoCancel
    // is on — once tapped (or dismissed by the user) it goes away. Not
    // sticky/ongoing, so a swipe-clear from the shade also dismisses it.
    private fun postAutoRestartFailedNotification(context: Context, cause: Throwable) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as? NotificationManager ?: return

            // The "proxy" channel is created by ProxyService.onCreate in the
            // :proxy process. After PACKAGE_REPLACED that process hasn't been
            // started yet (startForegroundService failed above), so the
            // channel may not exist on the system-wide notification surface.
            // createNotificationChannel is idempotent — safe to call from
            // any process, no-op when the channel is already known.
            if (Build.VERSION.SDK_INT >= 26) {
                val ch = NotificationChannel(
                    "proxy", "Proxy Agent",
                    NotificationManager.IMPORTANCE_LOW,
                )
                nm.createNotificationChannel(ch)
            }

            val openApp = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                PendingIntent.FLAG_IMMUTABLE,
            )
            val n = NotificationCompat.Builder(context, "proxy")
                .setContentTitle("Proxy Agent — auto-restart blocked")
                .setContentText("Tap to open the app and press START. " +
                    "Reason: ${cause.javaClass.simpleName}")
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    "Couldn't auto-resume the previous session after the app " +
                    "update — usually because the device's autostart manager " +
                    "blocked the broadcast (Xiaomi/Huawei/Samsung/etc. — see " +
                    "ADMIN_GUIDE §7.7). Tap to open the app and press START.\n\n" +
                    "Error: ${cause.javaClass.simpleName}: ${cause.message ?: "—"}"
                ))
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .setOngoing(false)
                .build()
            nm.notify(NOTIF_ID_AUTO_RESTART_FAILED, n)
        } catch (t: Throwable) {
            // Already in the receiver's error path — don't let notification
            // posting itself crash the receiver.
            Log.w(TAG, "fallback notification failed: ${t.message}")
        }
    }

    private companion object {
        private const val TAG = "ProxyAgent.PkgReplaced"
        // 1 = FGS status notification (ProxyService).
        // 2 = auto-stop reason notification (ProxyService.doStop).
        // 3 = auto-restart-blocked fallback (this receiver).
        private const val NOTIF_ID_AUTO_RESTART_FAILED = 3
    }
}
