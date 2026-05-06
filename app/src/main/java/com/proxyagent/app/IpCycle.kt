package com.proxyagent.app

import android.content.Context
import android.content.Intent
import android.provider.Settings
import java.util.concurrent.TimeUnit

// Cellular IP-cycling primitives shared by the manual button (MainActivity)
// and the server-triggered REBOOT auto-cycle (ProxyService). The actual flow
// (stop proxy → toggle cellular → restart proxy) lives in the callers because
// each has different lifecycle constraints (UI updates vs subprocess control).
object IpCycle {

    // `svc data` keeps voice/SMS up; airplane mode is the fallback when the
    // first command isn't accepted (some ROMs gate svc data behind shell uid).
    fun toggleMobileNetworkViaRoot(): Boolean {
        if (runRoot("svc data disable")) {
            try { Thread.sleep(2500) } catch (_: InterruptedException) { return false }
            if (runRoot("svc data enable")) return true
        }
        val onOk = runRoot("settings put global airplane_mode_on 1") &&
            runRoot("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true")
        if (onOk) {
            try { Thread.sleep(3500) } catch (_: InterruptedException) { return false }
            runRoot("settings put global airplane_mode_on 0")
            runRoot("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false")
            return true
        }
        return false
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

    // Throws SecurityException if WRITE_SECURE_SETTINGS hasn't been granted via
    // `adb shell pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS`.
    // The protected ACTION_AIRPLANE_MODE_CHANGED broadcast is rejected for
    // non-system apps; we attempt it anyway because some OEM ROMs need it on
    // top of the setting change.
    fun toggleMobileNetworkViaSecureSettings(context: Context): Boolean {
        return try {
            val cr = context.contentResolver
            Settings.Global.putInt(cr, Settings.Global.AIRPLANE_MODE_ON, 1)
            try {
                context.sendBroadcast(Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", true))
            } catch (_: Throwable) {}
            try { Thread.sleep(3500) } catch (_: InterruptedException) { return false }
            Settings.Global.putInt(cr, Settings.Global.AIRPLANE_MODE_ON, 0)
            try {
                context.sendBroadcast(Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", false))
            } catch (_: Throwable) {}
            true
        } catch (_: SecurityException) { false }
        catch (_: Throwable) { false }
    }
}
