package com.proxyagent.app.ota

import java.io.File
import java.util.concurrent.TimeUnit

// ────────────────────────────────────────────────────────────────────────
// Silent APK install on rooted devices via `su -c pm install`. Enables true
// background auto-update (no user prompt) — and, because `pm install -d`
// permits a version-code downgrade, it also makes downgrades work directly.
//
// The APK is streamed to `pm install -S <size>` over stdin, so the file never
// has to be world-readable (pm/PackageManagerService can't read our private
// cacheDir by path). Same `su` invocation style as IpCycle.runRoot.
// ────────────────────────────────────────────────────────────────────────

object RootInstaller {

    /** True if `su` is present and grants root (uid 0). May prompt the su manager once. */
    fun isRootAvailable(): Boolean = try {
        val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = Thread {
            try { p.inputStream.bufferedReader().use { out.append(it.readText()) } } catch (_: Throwable) {}
        }.apply { isDaemon = true; start() }
        // Generous timeout: the FIRST call pops the su-manager (Magisk) grant
        // dialog and blocks until the user taps allow — 8s was too short and
        // spuriously reported "no root" while the prompt was still up.
        val done = p.waitFor(30, TimeUnit.SECONDS)
        if (!done) { p.destroy(); false }
        else { try { reader.join(1000) } catch (_: InterruptedException) {}; p.exitValue() == 0 && out.contains("uid=0") }
    } catch (_: Throwable) {
        false
    }

    /**
     * Install [apk] silently as root. `-r` keeps data; `-d` (only when
     * [allowDowngrade]) permits a version-code downgrade. Auto-update passes
     * false so it can never be tricked into a rollback; the manual downgrade
     * action passes true. Returns true on "Success". Blocking — call off the
     * main thread. Note: updating THIS package kills our process at commit, so
     * a caller may never observe the return value — that's expected.
     */
    fun installSilently(apk: File, allowDowngrade: Boolean = false): Boolean = try {
        val size = apk.length()
        val flags = if (allowDowngrade) "-r -d" else "-r"
        val p = ProcessBuilder("su", "-c", "pm install $flags -S $size")
            .redirectErrorStream(true)
            .start()
        val out = StringBuilder()
        val reader = Thread {
            try { p.inputStream.bufferedReader().use { out.append(it.readText()) } } catch (_: Throwable) {}
        }.apply { isDaemon = true; start() }
        // Stream the APK bytes to pm's stdin.
        try { p.outputStream.use { o -> apk.inputStream().use { it.copyTo(o) } } } catch (_: Throwable) {}
        val done = p.waitFor(180, TimeUnit.SECONDS)
        if (!done) { p.destroy(); false }
        else {
            try { reader.join(2000) } catch (_: InterruptedException) {}
            p.exitValue() == 0 && out.toString().contains("Success", ignoreCase = true)
        }
    } catch (_: Throwable) {
        false
    }
}
