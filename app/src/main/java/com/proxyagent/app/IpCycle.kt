package com.proxyagent.app

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

// Cellular IP-cycling primitives shared by the manual button (MainActivity)
// and the server-triggered REBOOT auto-cycle (ProxyService).
//
// `cycleAndVerify` runs an escalating ladder: airplane on (+ restart radio under
// root) → sleep 10/20/40s → airplane off → wait for cellular reattach → fetch
// public IP via ipify → compare with baseline. If unchanged it bumps the sleep
// and tries again, up to a 120-second total budget. The caller decides what to
// do with the subprocess; this only manipulates radio + verifies IP.
object IpCycle {

    data class CycleResult(
        val oldIp: String,
        val newIp: String,
        val changed: Boolean,
        val attempts: Int,
        val totalMs: Long,
        // "ok", "ok_no_baseline", "ip_unchanged", "no_toggle_method",
        // "interrupted"
        val reason: String,
    )

    private val WAIT_LADDER_MS = longArrayOf(10_000, 20_000, 40_000)
    private const val TOTAL_BUDGET_MS = 120_000L
    private const val REATTACH_WAIT_MS = 20_000L
    private const val POST_REATTACH_GRACE_MS = 1_500L

    fun cycleAndVerify(
        context: Context,
        knownIp: String,
        log: (String) -> Unit = {},
    ): CycleResult {
        val start = System.currentTimeMillis()
        val deadline = start + TOTAL_BUDGET_MS
        val rootAvailable = runRoot("true")
        log(if (rootAvailable) "root ok — using svc + setprop ctl.restart ril-daemon"
            else "no root — secure-settings airplane only (no rild restart)")

        val oldIp = if (knownIp.isNotBlank()) knownIp.trim() else {
            log("fetching baseline IP")
            fetchPublicIp().also {
                if (it.isEmpty()) log("baseline IP unknown (fetch failed)")
                else log("baseline IP: $it")
            }
        }

        var lastNewIp = ""
        var attempts = 0
        var toggleEverWorked = false

        for (waitMs in WAIT_LADDER_MS) {
            val remaining = deadline - System.currentTimeMillis()
            // Need ~waitMs + REATTACH_WAIT_MS + fetch overhead. If we can't
            // fit a meaningful attempt, bail rather than start a partial one.
            if (remaining < waitMs + 5_000) {
                log("budget exhausted (${remaining}ms left); skipping remaining steps")
                break
            }

            attempts++
            log("attempt $attempts: airplane on${if (rootAvailable) " + restart ril" else ""}, sleeping ${waitMs / 1000}s")

            if (!airplaneOn(context, rootAvailable)) {
                log("attempt $attempts: airplane on failed")
                continue
            }
            toggleEverWorked = true
            if (rootAvailable) runRoot("setprop ctl.restart ril-daemon")

            try { Thread.sleep(waitMs) } catch (_: InterruptedException) {
                airplaneOff(context, rootAvailable)
                return CycleResult(
                    oldIp, lastNewIp, false, attempts,
                    System.currentTimeMillis() - start, "interrupted",
                )
            }

            if (!airplaneOff(context, rootAvailable)) {
                log("attempt $attempts: airplane off failed")
                continue
            }

            log("attempt $attempts: waiting for cellular reattach")
            val reattachDeadline = minOf(System.currentTimeMillis() + REATTACH_WAIT_MS, deadline)
            while (System.currentTimeMillis() < reattachDeadline) {
                if (hasCellularInternet(context)) break
                try { Thread.sleep(500) } catch (_: InterruptedException) {
                    return CycleResult(
                        oldIp, lastNewIp, false, attempts,
                        System.currentTimeMillis() - start, "interrupted",
                    )
                }
            }
            // Routes/DNS settle window before we hit ipify.
            try { Thread.sleep(POST_REATTACH_GRACE_MS) } catch (_: InterruptedException) {}

            log("attempt $attempts: fetching public IP")
            val newIp = fetchPublicIp()
            if (newIp.isNotEmpty()) lastNewIp = newIp
            if (newIp.isEmpty()) {
                log("attempt $attempts: IP fetch failed; escalating")
                continue
            }
            if (oldIp.isEmpty()) {
                log("attempt $attempts: got IP $newIp (no baseline to compare)")
                return CycleResult(
                    oldIp, newIp, false, attempts,
                    System.currentTimeMillis() - start, "ok_no_baseline",
                )
            }
            if (newIp != oldIp) {
                log("attempt $attempts: success — $oldIp -> $newIp")
                return CycleResult(
                    oldIp, newIp, true, attempts,
                    System.currentTimeMillis() - start, "ok",
                )
            }
            log("attempt $attempts: IP unchanged ($newIp); escalating")
        }

        val reason = if (!toggleEverWorked) "no_toggle_method" else "ip_unchanged"
        return CycleResult(
            oldIp, lastNewIp, false, attempts,
            System.currentTimeMillis() - start, reason,
        )
    }

    private fun airplaneOn(context: Context, preferRoot: Boolean): Boolean {
        if (preferRoot) {
            if (runRoot("settings put global airplane_mode_on 1") &&
                runRoot("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true")
            ) return true
        }
        return setAirplaneViaSecureSettings(context, true)
    }

    private fun airplaneOff(context: Context, preferRoot: Boolean): Boolean {
        if (preferRoot) {
            if (runRoot("settings put global airplane_mode_on 0") &&
                runRoot("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false")
            ) return true
        }
        return setAirplaneViaSecureSettings(context, false)
    }

    // Throws SecurityException if WRITE_SECURE_SETTINGS hasn't been granted via
    // `adb shell pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS`.
    private fun setAirplaneViaSecureSettings(context: Context, on: Boolean): Boolean {
        return try {
            Settings.Global.putInt(
                context.contentResolver,
                Settings.Global.AIRPLANE_MODE_ON,
                if (on) 1 else 0,
            )
            // Protected broadcast — rejected for non-system apps on most ROMs,
            // but some OEMs need it on top of the setting change.
            try {
                context.sendBroadcast(
                    Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", on),
                )
            } catch (_: Throwable) {}
            true
        } catch (_: SecurityException) { false } catch (_: Throwable) { false }
    }

    private fun hasCellularInternet(context: Context): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val nw = cm.activeNetwork
        val caps = nw?.let { cm.getNetworkCapabilities(it) }
        caps != null &&
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (_: Throwable) { false }

    private fun fetchPublicIp(): String {
        for (url in listOf("https://api.ipify.org", "https://icanhazip.com")) {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "ProxyAgent-Android")
                }
                val ip = conn.inputStream.bufferedReader().use { it.readText().trim() }
                conn.disconnect()
                if (ip.isNotEmpty() && ip.length < 40 &&
                    (ip.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) || ip.contains(":"))
                ) return ip
            } catch (_: Throwable) {}
        }
        return ""
    }

    fun runRoot(cmd: String): Boolean {
        return try {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            // Drain stdout so the child doesn't block on a full pipe.
            Thread { try { p.inputStream.bufferedReader().use { it.readText() } } catch (_: Throwable) {} }
                .apply { isDaemon = true; start() }
            val finished = p.waitFor(5, TimeUnit.SECONDS)
            if (!finished) { p.destroy(); return false }
            p.exitValue() == 0
        } catch (_: Throwable) { false }
    }
}
