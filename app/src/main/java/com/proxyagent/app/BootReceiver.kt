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

// Auto-restarts ProxyService after a device reboot. A phone restart hard-kills
// both :main and :proxy the same way an app upgrade does (see
// PackageReplacedReceiver), so any active modem-tunnel session vanishes on
// power-cycle. Without this receiver the operator would have to physically
// pick up the phone, open the app and press START after every reboot — a
// non-starter for rack-mounted / headless fleet devices that reboot on OTA,
// power blips, or scheduled maintenance.
//
// Trigger: ACTION_BOOT_COMPLETED is delivered once the user has unlocked the
// device for the first time after boot (we are NOT directBootAware, so we
// only run once credential-encrypted storage — filesDir + SharedPreferences —
// is available; that's exactly what we need to read the stored credentials
// and the was_running flag). We also register the OEM-specific
// QUICKBOOT_POWERON action some HTC/Samsung builds send instead of / in
// addition to BOOT_COMPLETED on "fast boot".
//
// Restart gating: identical to PackageReplacedReceiver — only auto-start when
// the previous session was running (the "was_running" flag, written by
// ProxyService.onStartCommand, cleared by doStop()). A user who deliberately
// pressed STOP before powering the phone down does NOT want the agent to come
// back on its own after reboot. The flag survives reboot because it lives in
// filesDir, which persists across power-cycles.
//
// Caveats (same as PackageReplacedReceiver):
//  - OEM auto-start managers (MIUI / EMUI / OneUI) can silently drop
//    BOOT_COMPLETED even when stock Android delivers it. The user must
//    whitelist the app in vendor "Autostart" settings. ADMIN_GUIDE.md
//    documents the per-OEM toggles.
//  - Android 12+ restricts background FGS starts, but BOOT_COMPLETED is one
//    of the documented exemptions, so startForegroundService is allowed from
//    here. We dispatch synchronously (no Handler.post / Thread) to stay
//    inside the receiver's grace window.
//  - If Google ever tightens the specialUse FGS-from-boot path, the start
//    throws; we catch, log, and post a fallback notification so the operator
//    gets a visible "needs manual START" signal instead of a silent outage.
//  - If credentials are missing (settings wiped between reboots), we can't
//    reconstruct the start Intent — skip silently.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != ACTION_QUICKBOOT_POWERON) return

        // Stamp the system log first so post-mortem analysis can confirm the
        // broadcast actually arrived (vs. blocked by OEM autostart manager).
        Log.i(TAG, "boot broadcast received: $action")

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
            // was_running implies the service once started, which requires the
            // same credentials — so this is only reachable if SharedPreferences
            // got wiped between sessions. Bail rather than crash.
            Log.w(TAG, "skip auto-restart — credentials missing despite was_running flag")
            return
        }
        val id = prefs.getString("id", "")?.trim().orEmpty()
        val dns = prefs.getString("dns", "")?.trim().orEmpty()
        val engine = prefs.getString("engine", "native") ?: "native"
        val mode = prefs.getString("mode", "modem") ?: "modem"
        val networkProfile = prefs.getString("network_profile", "LOW_100") ?: "LOW_100"

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
            Log.i(TAG, "auto-restarted ProxyService after boot (engine=$engine mode=$mode)")
        } catch (t: Throwable) {
            // Most likely ForegroundServiceStartNotAllowedException if the boot
            // exemption ever stops applying. Don't rethrow — a receiver crash
            // is a noisy "Process has died" dialog with no upside.
            Log.w(TAG, "boot auto-restart failed: ${t.javaClass.simpleName}: ${t.message}")
            postAutoRestartFailedNotification(context, t)
        }
    }

    // Visible signal that the agent was running before the reboot and needs the
    // user to come back and tap START. Mirrors PackageReplacedReceiver's
    // fallback so a blocked boot-restart isn't a silent outage. Uses the same
    // distinct notification id so the FGS status notification (1) and auto-stop
    // notification (2) aren't clobbered.
    private fun postAutoRestartFailedNotification(context: Context, cause: Throwable) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as? NotificationManager ?: return

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
                    "Couldn't auto-resume the previous session after the device " +
                    "rebooted — usually because the device's autostart manager " +
                    "blocked the boot broadcast (Xiaomi/Huawei/Samsung/etc. — see " +
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
            Log.w(TAG, "fallback notification failed: ${t.message}")
        }
    }

    private companion object {
        private const val TAG = "ProxyAgent.BootReceiver"
        // Non-standard action some OEM "fast boot" builds send instead of the
        // stock BOOT_COMPLETED. Harmless to listen for on devices that don't.
        private const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
        // 1 = FGS status notification (ProxyService).
        // 2 = auto-stop reason notification (ProxyService.doStop).
        // 3 = auto-restart-blocked fallback (shared with PackageReplacedReceiver).
        private const val NOTIF_ID_AUTO_RESTART_FAILED = 3
    }
}
