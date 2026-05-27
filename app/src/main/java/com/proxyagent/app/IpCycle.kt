package com.proxyagent.app

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

// Cellular IP-cycling primitives shared by the manual button (MainActivity)
// and the server-triggered REBOOT auto-cycle (ProxyService).
//
// `cycleAndVerify` runs two "nuclear" passes: airplane on + restart rild +
// flip RAT to GSM-only → sleep 10s (then 60s if the first try didn't move
// the IP) → airplane off → reattach in 2G/3G → flip RAT back to original →
// reattach in LTE → fetch public IP via ipify → compare. Light data-toggle
// passes are skipped on purpose: on operators that hold the PDP context,
// they almost never win, so we'd just burn budget. Base budget ~180s.
//
// If basic nuclear doesn't move the IP and the caller passes a CycleConfig
// with optional fallbacks enabled, two extra steps fire:
//   - APN swap: toggles preferred APN between SIM-configured entries to
//     force a fresh PDP context with the operator (extra ~50s).
//   - IMEI rotation: runs a user-supplied root command (custom shell, or
//     a preset like resetprop / magisk-imei) to change device identity
//     before re-attaching (extra ~50s). Requires Magisk + identity-changer
//     module installed; the app only invokes the command.
//
// The caller decides what to do with the subprocess; this only manipulates
// radio + APN + identity, then verifies IP.
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

    data class CycleConfig(
        val apnSwap: Boolean = false,
        val imeiRotation: Boolean = false,
        // "custom" → run imeiCustomCmd as-is via su
        // "props"  → resetprop a random IMEI (needs MagiskHidePropsConfig)
        // "magisk-imei" → magisk-imei --random (needs the module)
        val imeiMethod: String = "custom",
        val imeiCustomCmd: String = "",
        // Wi-Fi return relay (Modem mode only): when true, ProxyService spins
        // up a loopback TCP relay and points the SDK at it; the relay binds
        // its outgoing sockets to a Wi-Fi Network so the agent↔registrator
        // uplink rides Wi-Fi while the agent↔target dial still goes through
        // cellular (preserving the mobile exit IP). Falls back transparently
        // to the default network (cellular) when Wi-Fi is unavailable, so
        // losing Wi-Fi never drops the agent — it just stops the savings.
        // See ARCHITECTURE.md §"Wi-Fi return relay" for the full flow.
        val wifiReturn: Boolean = false,
        // Method selector for the wifi_return feature. Currently only
        // "local_relay" is implemented (a loopback bridge in :proxy). The
        // field is stored even though there's no UI for it yet — kept as a
        // forward-compat slot so future methods (e.g. SO_MARK + iproute,
        // VpnService-based split tunnel) can be added without re-shaping the
        // JSON schema.
        val wifiReturnMethod: String = "local_relay",
    )

    // ── Cross-process config storage ────────────────────────────────────
    // SharedPreferences are NOT multi-process safe — each process keeps its
    // own in-memory cache, and writes in :main aren't visible in :proxy
    // until that process restarts (MODE_MULTI_PROCESS is deprecated and
    // unreliable). For settings the ProxyService needs to see when it acts
    // on a REBOOT log line, we round-trip through a JSON file in filesDir
    // that both processes touch directly. MainActivity writes it on every
    // settings save (and on app launch as a back-fill); callers from both
    // processes read it via loadConfigFromFile.

    private const val CFG_FILE_NAME = "cycle_cfg.json"

    // Marker file present for the duration of a cycleAndVerify call. The
    // wrapper writes it on entry and deletes it in finally. If a process
    // dies mid-cycle (PACKAGE_REPLACED kill, force-stop, OOM) the marker
    // sticks around and recoverInterruptedCycle() — invoked on the next
    // :main launch — uses it to detect "we were toggling airplane_mode
    // when we were killed" and flip it back off so the user isn't left
    // without any cellular path.
    private const val IN_PROGRESS_MARKER = "ip_cycle_in_progress"

    fun loadConfigFromFile(context: Context): CycleConfig {
        return try {
            val f = File(context.filesDir, CFG_FILE_NAME)
            if (!f.exists()) return CycleConfig()
            val o = JSONObject(f.readText())
            CycleConfig(
                apnSwap = o.optBoolean("apn_swap", false),
                imeiRotation = o.optBoolean("imei_rotate", false),
                imeiMethod = o.optString("imei_method", "custom").ifEmpty { "custom" },
                imeiCustomCmd = o.optString("imei_cmd", ""),
                wifiReturn = o.optBoolean("wifi_return", false),
                wifiReturnMethod = o.optString("wifi_return_method", "local_relay")
                    .ifEmpty { "local_relay" },
            )
        } catch (_: Throwable) { CycleConfig() }
    }

    fun saveConfigToFile(context: Context, config: CycleConfig) {
        try {
            val o = JSONObject()
            o.put("apn_swap", config.apnSwap)
            o.put("imei_rotate", config.imeiRotation)
            o.put("imei_method", config.imeiMethod)
            o.put("imei_cmd", config.imeiCustomCmd)
            o.put("wifi_return", config.wifiReturn)
            o.put("wifi_return_method", config.wifiReturnMethod)
            File(context.filesDir, CFG_FILE_NAME).writeText(o.toString())
        } catch (_: Throwable) {}
    }

    private data class ApnInfo(val id: Int, val name: String, val apn: String)

    private data class Step(val sleepMs: Long, val ratSwitch: Boolean)

    // Both steps go full-nuclear. We're not escalating mechanism, just dwell
    // time: 10s catches the fast operators; 60s the stubborn ones. Past 60s
    // the IP is usually pinned for minutes — waiting longer doesn't help.
    private val LADDER = listOf(
        Step(10_000, true),
        Step(60_000, true),
    )
    private const val TOTAL_BUDGET_MS = 180_000L
    private const val REATTACH_WAIT_MS = 20_000L
    private const val POST_REATTACH_GRACE_MS = 1_500L
    // Extra reattach window after we flip RAT back; LTE re-acquisition can
    // take longer than a normal reattach because the modem is mid-handover.
    private const val RAT_RESTORE_REATTACH_MS = 15_000L

    // Marker name for APN rows we created ourselves as rotation duplicates.
    // Used both to identify our rows for cleanup and to filter them out of
    // the "real alternate" search (so we don't double-swap our own dup).
    private const val DUP_APN_NAME = "ProxyAgent-rotation-tmp"

    // Public entry point. Drops an IN_PROGRESS_MARKER file before delegating
    // and removes it in finally, so an aborted run (process kill, app update,
    // OOM) leaves the marker behind. The next :main launch picks it up via
    // recoverInterruptedCycle() and turns airplane_mode back off — otherwise
    // a crash between airplaneOn() and the inner finally would strand the
    // device with no cellular path.
    fun cycleAndVerify(
        context: Context,
        knownIp: String,
        log: (String) -> Unit = {},
        config: CycleConfig = CycleConfig(),
    ): CycleResult {
        val marker = File(context.filesDir, IN_PROGRESS_MARKER)
        try { marker.writeText(System.currentTimeMillis().toString()) } catch (_: Throwable) {}
        try {
            return cycleAndVerifyInner(context, knownIp, log, config)
        } finally {
            try { marker.delete() } catch (_: Throwable) {}
        }
    }

    private fun cycleAndVerifyInner(
        context: Context,
        knownIp: String,
        log: (String) -> Unit,
        config: CycleConfig,
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

        // If the user enabled an aggressive fallback (APN swap / IMEI rotate),
        // the basic 2-step ladder is a waste of budget — they already know
        // basic doesn't move this operator's IP, otherwise they wouldn't have
        // ticked the fallback. Skip straight to the heavy steps.
        val skipBasic = config.apnSwap || config.imeiRotation
        if (skipBasic) {
            log("aggressive fallback enabled — skipping basic 10s+60s ladder")
            // We still need toggleEverWorked to be true so the final "reason"
            // isn't "no_toggle_method" — set it implicitly since the fallback
            // steps will toggle the radio themselves.
            toggleEverWorked = true
        }

        for (step in if (skipBasic) emptyList<Step>() else LADDER) {
            val waitMs = step.sleepMs
            val doRatSwitch = step.ratSwitch && rootAvailable
            val remaining = deadline - System.currentTimeMillis()
            // Need ~waitMs + REATTACH_WAIT_MS + fetch overhead. If we can't
            // fit a meaningful attempt, bail rather than start a partial one.
            val needed = waitMs + 5_000 + (if (doRatSwitch) RAT_RESTORE_REATTACH_MS else 0)
            if (remaining < needed) {
                log("budget exhausted (${remaining}ms left, need ${needed}ms); skipping remaining steps")
                break
            }

            attempts++
            val extras = buildString {
                if (rootAvailable) append(" + restart ril")
                if (doRatSwitch) append(" + RAT→GSM")
            }
            log("attempt $attempts: airplane on$extras, sleeping ${waitMs / 1000}s")

            // Save & switch RAT before we kill the radio so the modem comes
            // back in GSM/2G on airplane-off. We restore after the re-attach.
            val savedRat: String? = if (doRatSwitch) saveAndSetGsmOnly(log) else null

            if (!airplaneOn(context, rootAvailable)) {
                log("attempt $attempts: airplane on failed")
                if (savedRat != null) restoreRat(savedRat)
                continue
            }
            toggleEverWorked = true
            if (rootAvailable) runRoot("setprop ctl.restart ril-daemon")

            try { Thread.sleep(waitMs) } catch (_: InterruptedException) {
                airplaneOff(context, rootAvailable)
                if (savedRat != null) restoreRat(savedRat)
                return CycleResult(
                    oldIp, lastNewIp, false, attempts,
                    System.currentTimeMillis() - start, "interrupted",
                )
            }

            if (!airplaneOff(context, rootAvailable)) {
                log("attempt $attempts: airplane off failed")
                if (savedRat != null) restoreRat(savedRat)
                continue
            }

            log("attempt $attempts: waiting for cellular reattach")
            val reattachDeadline = minOf(System.currentTimeMillis() + REATTACH_WAIT_MS, deadline)
            while (System.currentTimeMillis() < reattachDeadline) {
                if (hasCellularInternet(context)) break
                try { Thread.sleep(500) } catch (_: InterruptedException) {
                    if (savedRat != null) restoreRat(savedRat)
                    return CycleResult(
                        oldIp, lastNewIp, false, attempts,
                        System.currentTimeMillis() - start, "interrupted",
                    )
                }
            }
            // Routes/DNS settle window before we hit ipify.
            try { Thread.sleep(POST_REATTACH_GRACE_MS) } catch (_: InterruptedException) {}

            // Now restore RAT so the modem switches back to LTE-preferred.
            // The RAT change itself often triggers a fresh PDP context — which
            // is half the reason we did the GSM detour in the first place.
            if (savedRat != null) {
                log("attempt $attempts: restoring RAT to $savedRat, waiting for LTE")
                restoreRat(savedRat)
                val ratDeadline = minOf(System.currentTimeMillis() + RAT_RESTORE_REATTACH_MS, deadline)
                while (System.currentTimeMillis() < ratDeadline) {
                    if (hasCellularInternet(context)) break
                    try { Thread.sleep(500) } catch (_: InterruptedException) {
                        return CycleResult(
                            oldIp, lastNewIp, false, attempts,
                            System.currentTimeMillis() - start, "interrupted",
                        )
                    }
                }
                try { Thread.sleep(POST_REATTACH_GRACE_MS) } catch (_: InterruptedException) {}
            }

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

        // ── Fallback step: APN swap ─────────────────────────────────────
        // Only useful if oldIp is known (we need to compare), and only
        // possible with root (writing to telephony content provider).
        if (config.apnSwap && rootAvailable && oldIp.isNotEmpty()) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining < 30_000) {
                log("APN swap skipped — only ${remaining}ms left in budget")
            } else {
                attempts++
                val swapIp = runApnSwapStep(context, log, deadline, attempts)
                if (swapIp != null) lastNewIp = swapIp
                if (swapIp != null && swapIp != oldIp) {
                    log("APN swap: success — $oldIp -> $swapIp")
                    return CycleResult(
                        oldIp, swapIp, true, attempts,
                        System.currentTimeMillis() - start, "ok",
                    )
                }
                log("APN swap: IP still ${swapIp ?: "unknown"}; falling through")
            }
        }

        // ── Fallback step: IMEI rotation ────────────────────────────────
        if (config.imeiRotation && rootAvailable && oldIp.isNotEmpty()) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining < 30_000) {
                log("IMEI rotation skipped — only ${remaining}ms left in budget")
            } else {
                attempts++
                val imeiIp = runImeiRotateStep(context, config, log, deadline, attempts)
                if (imeiIp != null) lastNewIp = imeiIp
                if (imeiIp != null && imeiIp != oldIp) {
                    log("IMEI rotation: success — $oldIp -> $imeiIp")
                    return CycleResult(
                        oldIp, imeiIp, true, attempts,
                        System.currentTimeMillis() - start, "ok",
                    )
                }
                log("IMEI rotation: IP still ${imeiIp ?: "unknown"}")
            }
        }

        val reason = if (!toggleEverWorked) "no_toggle_method" else "ip_unchanged"
        return CycleResult(
            oldIp, lastNewIp, false, attempts,
            System.currentTimeMillis() - start, reason,
        )
    }

    // Best-effort recovery from a rotation interrupted by process kill (app
    // update via PACKAGE_REPLACED, low-memory kill, force-stop, native crash).
    // The wrapper writes IN_PROGRESS_MARKER on entry to cycleAndVerify and
    // deletes it in finally, so a leftover marker means we died between
    // airplaneOn() and the matching airplaneOff() — likely with airplane mode
    // still enabled, which would otherwise strand the device.
    //
    // Called from MainActivity.onCreate on a background thread. Idempotent:
    // no marker → returns false without touching anything; marker present but
    // airplane already off → just deletes the marker.
    //
    // Returns true iff a recovery was attempted (marker existed). The caller
    // doesn't need the result, but it's useful for logs and tests.
    fun recoverInterruptedCycle(
        context: Context,
        log: (String) -> Unit = {},
    ): Boolean {
        val marker = File(context.filesDir, IN_PROGRESS_MARKER)
        if (!marker.exists()) return false
        val airplaneOn = try {
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.AIRPLANE_MODE_ON,
                0,
            ) == 1
        } catch (_: Throwable) { false }
        log("recovery: ip_cycle marker found, airplane_mode=$airplaneOn")
        if (airplaneOn) {
            // root path tried first since the cycle that died was likely
            // using it (cleaner exit, broadcasts the AIRPLANE_MODE_CHANGED
            // intent that some carrier services need); falls back to
            // WRITE_SECURE_SETTINGS if root isn't available anymore.
            val rootAvailable = runRoot("true")
            val ok = airplaneOff(context, rootAvailable)
            log("recovery: airplane_off ${if (ok) "ok" else "failed"} " +
                "(root=$rootAvailable)")
        }
        try { marker.delete() } catch (_: Throwable) {}
        return true
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

    // maxBytes caps how much output we'll buffer. Defensive against commands
    // that accidentally return huge results (e.g. `content query` against
    // telephony/carriers without a --where filter — the global APN DB is
    // tens of MB and will OOM us before LMK kills us). 256 KB is plenty for
    // any sane filtered query.
    private fun runRootOutput(cmd: String, maxBytes: Int = 256 * 1024): String? {
        return try {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            val sb = StringBuilder()
            val t = Thread {
                try {
                    p.inputStream.bufferedReader().use { reader ->
                        val buf = CharArray(4096)
                        while (true) {
                            val n = reader.read(buf)
                            if (n < 0) break
                            if (sb.length + n > maxBytes) {
                                sb.append(buf, 0, maxBytes - sb.length)
                                sb.append("\n[OUTPUT TRUNCATED]")
                                break
                            }
                            sb.append(buf, 0, n)
                        }
                    }
                } catch (_: Throwable) {}
            }.apply { isDaemon = true; start() }
            val finished = p.waitFor(5, TimeUnit.SECONDS)
            if (!finished) { p.destroy(); return null }
            if (p.exitValue() != 0) return null
            try { t.join(1000) } catch (_: InterruptedException) {}
            sb.toString().trim()
        } catch (_: Throwable) { null }
    }

    // Reads `settings get global preferred_network_mode` (RAT preference) and
    // switches to GSM-only (mode 1). Returns the original numeric mode for
    // restoreRat to put back, or null if we couldn't read/write.
    private fun saveAndSetGsmOnly(log: (String) -> Unit): String? {
        val original = runRootOutput("settings get global preferred_network_mode")
        if (original == null || !original.matches(Regex("""\d+"""))) {
            log("RAT switch skipped — couldn't read current mode (got \"${original ?: "null"}\")")
            return null
        }
        if (original == "1") {
            log("RAT switch skipped — already GSM-only")
            return null
        }
        if (!runRoot("settings put global preferred_network_mode 1")) {
            log("RAT switch skipped — write failed")
            return null
        }
        return original
    }

    private fun restoreRat(originalMode: String) {
        runRoot("settings put global preferred_network_mode $originalMode")
    }

    // ── Shared inner cycle for fallback steps ──────────────────────────
    // Strips the RAT-switch dance (kept only in the basic ladder where the
    // 80-second variant relies on it). Fallback steps already have a strong
    // identity/route change of their own, so airplane + rild restart is
    // enough — no need to double the budget with two RAT toggles.
    private fun innerCycle(
        context: Context,
        waitMs: Long,
        rootAvailable: Boolean,
        log: (String) -> Unit,
        deadline: Long,
        label: String,
    ): Boolean {
        log("$label: airplane on${if (rootAvailable) " + restart ril" else ""}, sleeping ${waitMs / 1000}s")
        if (!airplaneOn(context, rootAvailable)) {
            log("$label: airplane on failed")
            return false
        }
        if (rootAvailable) runRoot("setprop ctl.restart ril-daemon")
        try { Thread.sleep(waitMs) } catch (_: InterruptedException) { return false }
        if (!airplaneOff(context, rootAvailable)) {
            log("$label: airplane off failed")
            return false
        }
        log("$label: waiting for cellular reattach")
        val reattachDeadline = minOf(System.currentTimeMillis() + REATTACH_WAIT_MS, deadline)
        while (System.currentTimeMillis() < reattachDeadline) {
            if (hasCellularInternet(context)) break
            try { Thread.sleep(500) } catch (_: InterruptedException) { return false }
        }
        try { Thread.sleep(POST_REATTACH_GRACE_MS) } catch (_: InterruptedException) {}
        return true
    }

    // ── APN swap step ───────────────────────────────────────────────────
    // Switches the preferred APN to an alternate entry on the SIM, cycles
    // the radio so the modem attaches under the new APN (fresh PDP), then
    // swaps back and cycles again. The double swap forces two PDP context
    // establishments with different APNs — usually enough to break a
    // pinned IP, IF the SIM has more than one APN configured.
    private fun runApnSwapStep(
        context: Context,
        log: (String) -> Unit,
        deadline: Long,
        attempt: Int,
    ): String? {
        val tag = "attempt $attempt (APN swap)"
        // try/finally with bare `return` inside the try works in Kotlin: the
        // finally still runs before the value propagates out. We can't use
        // `return@try` — try-expressions aren't labellable, only lambdas are.
        try {
            val currentApn = readPreferredApn(log) ?: run {
                log("$tag: aborted — couldn't read preferapn")
                return null
            }
            val currentId = currentApn["_id"]?.toIntOrNull() ?: run {
                log("$tag: aborted — preferapn row has no _id")
                return null
            }
            val altApn = findOrCreateAlternateApn(currentApn, log) ?: run {
                log("$tag: aborted — no alternate APN and couldn't create duplicate")
                return null
            }
            log("$tag: preferapn $currentId -> ${altApn.id} (${altApn.name}/${altApn.apn})")
            if (!setPreferredApn(altApn.id)) {
                log("$tag: aborted — preferapn write failed")
                return null
            }
            if (!innerCycle(context, 10_000, true, log, deadline, "$tag (alt)")) {
                setPreferredApn(currentId)   // best-effort restore
                return null
            }
            log("$tag: restoring preferapn -> $currentId")
            setPreferredApn(currentId)
            innerCycle(context, 5_000, true, log, deadline, "$tag (restore)")
            return fetchPublicIp().ifEmpty { null }
        } finally {
            // Always remove our rotation duplicate (if any), regardless of
            // how this step exited. Real APNs are untouched — cleanup only
            // matches rows named DUP_APN_NAME.
            cleanupRotationDuplicates(log)
        }
    }

    // Reads the preferred APN's full row in one go. On most ROMs (Xiaomi
    // included) `content query --uri .../preferapn` returns the joined row
    // with all carrier fields; on a few stock builds it returns only
    // `apn_id=N`, in which case we follow up with a filtered query for that
    // specific _id (small response — no risk of pulling the global APN DB).
    private fun readPreferredApn(log: (String) -> Unit): Map<String, String>? {
        val output = runRootOutput("content query --uri content://telephony/carriers/preferapn")
            ?: return null
        log("APN: preferapn raw=\"${output.take(200).replace("\n", " ")}\"")
        val rowRe = Regex("""Row:\s*\d+\s+([^\n]+)""")
        val match = rowRe.find(output)
        if (match != null) {
            val fields = parseRowFields(match.groupValues[1])
            if (fields["apn"] != null && fields["_id"] != null) {
                log("APN: preferapn id=${fields["_id"]} numeric=${fields["numeric"]} apn=${fields["apn"]}")
                return fields
            }
            // ROM only gave us apn_id — fall through to a targeted second query.
            val idStr = fields["apn_id"] ?: fields["_id"]
            val id = idStr?.toIntOrNull() ?: return null
            return queryApnById(id, log)
        }
        // No "Row:" prefix — try to extract just an id and refetch.
        val idMatch = Regex("""(?:apn_id|_id)=(\d+)""").find(output) ?: return null
        val id = idMatch.groupValues[1].toIntOrNull() ?: return null
        return queryApnById(id, log)
    }

    private fun queryApnById(id: Int, log: (String) -> Unit): Map<String, String>? {
        val output = runRootOutput(
            "content query --uri content://telephony/carriers --where \"_id=$id\""
        ) ?: return null
        val rowRe = Regex("""Row:\s*\d+\s+([^\n]+)""")
        val match = rowRe.find(output) ?: run {
            log("APN: queryApnById($id) returned no rows")
            return null
        }
        val fields = parseRowFields(match.groupValues[1])
        log("APN: queryApnById($id) → numeric=${fields["numeric"]} apn=${fields["apn"]}")
        return fields
    }

    private fun parseRowFields(rowContent: String): Map<String, String> {
        val fields = HashMap<String, String>()
        for (part in rowContent.split(", ")) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            fields[part.substring(0, eq).trim()] = part.substring(eq + 1).trim()
        }
        return fields
    }

    // Tries to find a real alternate APN for the SAME SIM operator (filtered
    // by `numeric` = MCC+MNC). If there isn't one, duplicates the current APN
    // into a new row (same `apn` string, same MCC/MNC/protocol/etc., different
    // `name` and `_id`) so we have something to swap to. The duplicate is
    // tagged with DUP_APN_NAME so cleanupRotationDuplicates() can remove it
    // after the swap.
    //
    // CRITICAL: the query MUST be filtered. Without `--where`, on many ROMs
    // this returns the entire global APN database (thousands of rows, tens of
    // MB), which on a memory-pressed device triggers the LowMemoryKiller and
    // gets our own foreground app SIGKILL'd ~10 seconds later.
    private fun findOrCreateAlternateApn(
        currentApn: Map<String, String>,
        log: (String) -> Unit,
    ): ApnInfo? {
        val currentId = currentApn["_id"]?.toIntOrNull() ?: return null
        val numeric = currentApn["numeric"]?.takeIf { it.isNotEmpty() && it != "NULL" }
        val output = queryCarriersForNumeric(numeric) ?: return null
        val apns = parseApns(output)
        log("APN: ${apns.size} APN(s) for numeric=${numeric ?: "<unfiltered>"} (current id=$currentId)")
        val realAlt = apns.firstOrNull {
            it.id != currentId && it.apn.isNotEmpty() && it.name != DUP_APN_NAME
        }
        if (realAlt != null) {
            log("APN: using existing alternate id=${realAlt.id} name=${realAlt.name} apn=${realAlt.apn}")
            return realAlt
        }
        log("APN: no real alternate — will duplicate current APN")
        cleanupRotationDuplicates(log)
        if (!insertDuplicateApn(currentApn, log)) return null
        val refresh = queryCarriersForNumeric(numeric) ?: return null
        val created = parseApns(refresh).firstOrNull { it.name == DUP_APN_NAME }
        if (created == null) {
            log("APN: inserted duplicate but it doesn't appear in re-query")
            return null
        }
        log("APN: duplicate created id=${created.id} name=${created.name} apn=${created.apn}")
        return created
    }

    private fun queryCarriersForNumeric(numeric: String?): String? {
        return if (!numeric.isNullOrEmpty()) {
            runRootOutput(
                "content query --uri content://telephony/carriers --where \"numeric='$numeric'\""
            )
        } else {
            // No numeric available (very rare) — at least try, but with a much
            // smaller output cap so a global query can't OOM us.
            runRootOutput("content query --uri content://telephony/carriers", maxBytes = 64 * 1024)
        }
    }

    // Builds a `content insert` that copies a curated subset of fields from
    // the source row. We restrict to fields that are documented in Android's
    // Telephony.Carriers and skip anything that contains shell-unsafe chars
    // — APN values in practice never contain quotes, but we still defend.
    private fun insertDuplicateApn(fields: Map<String, String>, log: (String) -> Unit): Boolean {
        val safeKeys = listOf(
            "apn", "type", "mcc", "mnc", "numeric", "protocol", "roaming_protocol",
            "user", "password", "server", "proxy", "port",
            "mmsc", "mmsproxy", "mmsport",
            "authtype", "bearer", "mvno_type", "mvno_match_data",
            "carrier_enabled",
        )
        val sb = StringBuilder("content insert --uri content://telephony/carriers")
        sb.append(" --bind name:s:'$DUP_APN_NAME'")
        var copied = 0
        for (k in safeKeys) {
            val v = fields[k] ?: continue
            if (v.isEmpty() || v == "NULL") continue
            if (v.contains('\'') || v.contains('"') || v.contains('\n') || v.contains('\\')) continue
            sb.append(" --bind $k:s:'$v'")
            copied++
        }
        val ok = runRoot(sb.toString())
        log("APN: insert duplicate ${if (ok) "ok" else "failed"} (copied $copied fields, apn=${fields["apn"]})")
        return ok
    }

    private fun cleanupRotationDuplicates(log: (String) -> Unit) {
        // Android 9+: WHERE clause delete works directly.
        if (runRoot("content delete --uri content://telephony/carriers --where \"name='$DUP_APN_NAME'\"")) {
            log("APN: cleaned up duplicates via --where")
            return
        }
        // Older Android fallback: query filtered by our marker name (output is
        // tiny, max a few rows), then delete each by row URI. Never query the
        // unfiltered carriers table — it pulls the entire global APN database
        // and was the original cause of the OOM/LMK crash.
        val output = runRootOutput(
            "content query --uri content://telephony/carriers --where \"name='$DUP_APN_NAME'\""
        ) ?: return
        val dups = parseApns(output).filter { it.name == DUP_APN_NAME }
        for (dup in dups) {
            runRoot("content delete --uri content://telephony/carriers/${dup.id}")
        }
        if (dups.isNotEmpty()) log("APN: cleaned up ${dups.size} duplicate(s) by id")
    }

    private fun parseApns(output: String): List<ApnInfo> {
        val rowRe = Regex("""Row:\s*\d+\s+([^\n]+)""")
        return rowRe.findAll(output).mapNotNull { match ->
            val fields = HashMap<String, String>()
            for (part in match.groupValues[1].split(", ")) {
                val eq = part.indexOf('=')
                if (eq <= 0) continue
                fields[part.substring(0, eq).trim()] = part.substring(eq + 1).trim()
            }
            val id = fields["_id"]?.toIntOrNull() ?: return@mapNotNull null
            val name = fields["name"]?.takeIf { it != "NULL" } ?: ""
            val apn = fields["apn"]?.takeIf { it != "NULL" } ?: ""
            ApnInfo(id, name, apn)
        }.toList()
    }

    private fun setPreferredApn(apnId: Int): Boolean {
        // preferapn behaves as INSERT on older Android, UPDATE on newer. Try
        // both — only one will succeed; the other is a no-op error.
        return runRoot("content insert --uri content://telephony/carriers/preferapn --bind apn_id:i:$apnId") ||
            runRoot("content update --uri content://telephony/carriers/preferapn --bind apn_id:i:$apnId")
    }

    // ── IMEI rotation step ──────────────────────────────────────────────
    // Runs the user-supplied identity-change command (custom shell, or a
    // preset). We don't change IMEI ourselves — we just invoke whatever
    // module the user has installed. After the command runs, a radio
    // cycle re-presents the device to the operator under the new identity.
    private fun runImeiRotateStep(
        context: Context,
        config: CycleConfig,
        log: (String) -> Unit,
        deadline: Long,
        attempt: Int,
    ): String? {
        val tag = "attempt $attempt (IMEI rotate)"
        val cmd = resolveImeiCommand(config, log) ?: run {
            log("$tag: aborted — no command for method '${config.imeiMethod}'")
            return null
        }
        log("$tag: running root cmd: $cmd")
        if (!runRoot(cmd)) {
            log("$tag: command exited non-zero")
            return null
        }
        if (!innerCycle(context, 10_000, true, log, deadline, tag)) {
            return null
        }
        return fetchPublicIp().ifEmpty { null }
    }

    private fun resolveImeiCommand(config: CycleConfig, log: (String) -> Unit): String? {
        return when (config.imeiMethod) {
            "custom" -> config.imeiCustomCmd.trim().takeIf { it.isNotEmpty() }
            "props" -> "resetprop -n -p ro.ril.imei0 ${randomImei()}"
            "magisk-imei" -> "magisk-imei --random"
            else -> {
                log("IMEI rotation: unknown method '${config.imeiMethod}'")
                null
            }
        }
    }

    private fun randomImei(): String {
        val sb = StringBuilder(15)
        repeat(15) { sb.append((0..9).random()) }
        return sb.toString()
    }
}
