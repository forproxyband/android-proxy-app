package com.proxyagent.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
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
//    catch and log so the broadcast doesn't crash the receiver.
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

        val svc = Intent(context, ProxyService::class.java).apply {
            putExtra("host", host); putExtra("port", port); putExtra("key", key)
            putExtra("id", id); putExtra("dns", dns)
            putExtra("engine", engine); putExtra("mode", mode)
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
            Log.i(TAG, "auto-restarted ProxyService (engine=$engine mode=$mode)")
        } catch (t: Throwable) {
            // Most likely cause on Android 14+: ForegroundServiceStartNot
            // AllowedException because the system tightened the exemption.
            // Nothing we can do from here — the user will have to open the
            // app and press START. Don't rethrow; a broadcast-receiver crash
            // is a noisy "Process has died" dialog with no upside.
            Log.w(TAG, "auto-restart failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private companion object {
        private const val TAG = "ProxyAgent.PkgReplaced"
    }
}
