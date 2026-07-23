package com.proxyagent.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.io.File

// Remote start/stop/toggle of ProxyService over `adb shell am broadcast`.
//
// ProxyService itself is android:exported="false" and runs in the :proxy
// process, so `am` (running as the shell UID) can't target it directly —
// the same-UID check rejects the call with SecurityException. This receiver
// is the exported control surface: it runs in :main, authenticates the
// request, then reconstructs the exact start Intent MainActivity.startProxyService
// / the STOP path use, so remote control behaves identically to tapping the
// on-screen toggle.
//
// Why a receiver (not the launcher Activity or an exported ProxyService):
//   * Headless — no UI flashes onto the screen on every start/stop, which
//     matters for rack-mounted / fleet devices driven purely over adb.
//   * Keeps ProxyService un-exported — the sensitive component (holds the
//     connection key, opens the tunnel) stays invisible to other apps.
//
// ── Usage ────────────────────────────────────────────────────────────────
//   adb shell am broadcast -n com.proxyagent.app/.RemoteControlReceiver \
//       -a com.proxyagent.app.REMOTE_CONTROL \
//       --es cmd start  --es key <CONNECTION_KEY>
//   adb shell am broadcast -n com.proxyagent.app/.RemoteControlReceiver \
//       -a com.proxyagent.app.REMOTE_CONTROL \
//       --es cmd stop   --es key <CONNECTION_KEY>
//   cmd values: start | stop | toggle | status
//
// ── Auth ─────────────────────────────────────────────────────────────────
// Every command requires `--es key <k>` matching the stored connection key
// (SharedPreferences "cfg" → "k"). The receiver is exported, so without this
// gate any app on the device could flip the proxy. If the app has never been
// configured (no stored key) every command is rejected — configure once via
// the QR/Settings flow first. `status` requires the key too, so a caller
// can't probe running state without it.
//
// ── FGS background-start (Android 12+) ─────────────────────────────────────
// `adb shell am broadcast` places the target app on a temporary power
// allowlist, which is one of the documented exemptions from the background
// foreground-service-start restriction. So startForegroundService from here
// is allowed when driven over adb. As with PackageReplacedReceiver we dispatch
// synchronously (no Handler.post / Thread) to stay inside the receiver's grace
// window, and we swallow ForegroundServiceStartNotAllowedException rather than
// crash the receiver.
class RemoteControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return

        val cmd = intent.getStringExtra("cmd")?.trim()?.lowercase().orEmpty()
        if (cmd.isEmpty()) {
            reply("error: missing cmd (start|stop|toggle|status)")
            return
        }

        val prefs = context.getSharedPreferences("cfg", 0)
        val storedKey = prefs.getString("k", "")?.trim().orEmpty()
        val suppliedKey = intent.getStringExtra("key")?.trim().orEmpty()

        // Auth gate. Reject before doing anything observable (including the
        // status read) so an unauthenticated caller learns nothing.
        if (storedKey.isEmpty()) {
            Log.w(TAG, "rejected '$cmd' — app not configured (no stored key)")
            reply("error: not configured")
            return
        }
        if (suppliedKey.isEmpty() || suppliedKey != storedKey) {
            Log.w(TAG, "rejected '$cmd' — bad or missing key")
            reply("error: unauthorized")
            return
        }

        when (cmd) {
            "start" -> start(context, prefs, intent.getBooleanExtra("boot_gate", false))
            "stop" -> stop(context)
            "toggle" -> if (isRunning(context)) stop(context) else start(context, prefs, false)
            "status" -> {
                val s = readProxyState(context).ifEmpty { "stopped" }
                Log.i(TAG, "status=$s")
                reply("status: $s")
            }
            else -> {
                Log.w(TAG, "unknown cmd '$cmd'")
                reply("error: unknown cmd '$cmd' (start|stop|toggle|status)")
            }
        }
    }

    // bootGate=true is passed by the root boot script (--ez boot_gate true). It
    // makes this start honor the same "was_running" contract BootReceiver and
    // PackageReplacedReceiver use: a session the user intentionally stopped
    // (doStop() deletes the flag) is NOT resurrected on the next boot. Callers
    // over adb pass it false (or omit it) so a manual `cmd start` always starts.
    // Note: on a device with a secure lock screen the was_running file lives in
    // credential-encrypted storage and is unreadable until first unlock — but
    // the key-auth above reads the same CE prefs, so a locked-phase broadcast
    // fails auth long before it reaches this gate; by the time we get here CE is
    // unlocked and the flag is authoritative.
    private fun start(
        context: Context,
        prefs: android.content.SharedPreferences,
        bootGate: Boolean,
    ) {
        val host = prefs.getString("h", "")?.trim().orEmpty()
        val port = prefs.getString("p", "")?.trim().orEmpty()
        val key = prefs.getString("k", "")?.trim().orEmpty()
        if (host.isEmpty() || port.isEmpty() || key.isEmpty()) {
            Log.w(TAG, "start skipped — connection not configured")
            reply("error: not configured")
            return
        }

        if (bootGate && !File(context.filesDir, "was_running").exists()) {
            Log.i(TAG, "start skipped — boot_gate set and no was_running flag " +
                "(session was intentionally stopped before reboot)")
            reply("skip: not was_running")
            return
        }

        // Mirror MainActivity.startProxyService: wipe the previous session's
        // status files so proxy_state/conn_info/agent.log reflect this run and
        // the staleness check in the app doesn't show a leftover session.
        try { File(context.filesDir, "agent.log").delete() } catch (_: Throwable) {}
        try { File(context.filesDir, "proxy_state").delete() } catch (_: Throwable) {}
        try { File(context.filesDir, "conn_info").delete() } catch (_: Throwable) {}

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
            Log.i(TAG, "remote start (engine=$engine mode=$mode)")
            reply("ok: starting")
        } catch (t: Throwable) {
            // Most likely ForegroundServiceStartNotAllowedException if the adb
            // temp-allowlist exemption ever stops applying. Don't crash the
            // receiver — log and report back.
            Log.w(TAG, "remote start failed: ${t.javaClass.simpleName}: ${t.message}")
            reply("error: start failed: ${t.javaClass.simpleName}")
        }
    }

    private fun stop(context: Context) {
        try {
            // STOP path is a plain startService (not foreground) — matches
            // MainActivity.toggle(). ProxyService.onStartCommand short-circuits
            // on action=="STOP" and tears the session down.
            context.startService(Intent(context, ProxyService::class.java).apply {
                action = "STOP"
            })
            Log.i(TAG, "remote stop")
            reply("ok: stopping")
        } catch (t: Throwable) {
            Log.w(TAG, "remote stop failed: ${t.javaClass.simpleName}: ${t.message}")
            reply("error: stop failed: ${t.javaClass.simpleName}")
        }
    }

    private fun isRunning(context: Context): Boolean =
        readProxyState(context).let { it == "running" || it == "starting" }

    private fun readProxyState(context: Context): String =
        try { File(context.filesDir, "proxy_state").readText().trim() }
        catch (_: Throwable) { "" }

    // When `am broadcast` is invoked with a receiver reached synchronously,
    // setResultData is echoed back to the shell as `Broadcast completed:
    // result=0, data="..."`, giving the adb caller machine-readable feedback
    // without needing a separate `run-as` / file read. No-op for a
    // non-ordered dispatch (resultAbort etc. unavailable) — Log covers that.
    private fun reply(msg: String) {
        try { resultData = msg } catch (_: Throwable) {}
    }

    private companion object {
        private const val TAG = "ProxyAgent.RemoteCtl"
    }
}
