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
    )

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

    fun cycleAndVerify(
        context: Context,
        knownIp: String,
        log: (String) -> Unit = {},
        config: CycleConfig = CycleConfig(),
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

        for (step in LADDER) {
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

    private fun runRootOutput(cmd: String): String? {
        return try {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            val sb = StringBuilder()
            val t = Thread {
                try { p.inputStream.bufferedReader().use { sb.append(it.readText()) } } catch (_: Throwable) {}
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
            val currentId = readPreferredApnId(log) ?: run {
                log("$tag: aborted — couldn't read preferapn")
                return null
            }
            val altApn = findOrCreateAlternateApn(currentId, log) ?: run {
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

    private fun readPreferredApnId(log: (String) -> Unit): Int? {
        val output = runRootOutput("content query --uri content://telephony/carriers/preferapn")
            ?: return null
        // "Row: 0 apn_id=2"  (Android 10+) or  "Row: 0 _id=2"  (older)
        val match = Regex("""(?:apn_id|_id)=(\d+)""").find(output)
        val id = match?.groupValues?.get(1)?.toIntOrNull()
        log("APN: preferapn raw=\"${output.take(120).replace("\n", " ")}\" parsed_id=$id")
        return id
    }

    // Tries to find a real alternate APN already on the SIM. If there isn't
    // one, duplicates the current APN into a new row (same `apn` string,
    // same MCC/MNC/protocol/etc., different `name` and `_id`) so we have
    // something to swap to. The duplicate is tagged with DUP_APN_NAME so
    // cleanupRotationDuplicates() can remove it after the swap.
    private fun findOrCreateAlternateApn(currentId: Int, log: (String) -> Unit): ApnInfo? {
        val output = runRootOutput("content query --uri content://telephony/carriers") ?: return null
        val apns = parseApns(output)
        log("APN: ${apns.size} APN(s) on device; current id=$currentId")
        // Real alternate first — anything that isn't current and isn't our
        // own leftover dup, and has an actual apn string.
        val realAlt = apns.firstOrNull {
            it.id != currentId && it.apn.isNotEmpty() && it.name != DUP_APN_NAME
        }
        if (realAlt != null) {
            log("APN: using existing alternate id=${realAlt.id} name=${realAlt.name} apn=${realAlt.apn}")
            return realAlt
        }
        log("APN: no real alternate — will duplicate current APN")
        // Clean up any stale dups from a prior interrupted cycle before we
        // create a fresh one, otherwise findOrCreate could pick up an old dup.
        cleanupRotationDuplicates(log)
        val srcFields = queryFullApn(currentId, log) ?: run {
            log("APN: couldn't read full current row for duplication")
            return null
        }
        if (!insertDuplicateApn(srcFields, log)) return null
        // Re-query to get the assigned _id of our new row.
        val newOutput = runRootOutput("content query --uri content://telephony/carriers") ?: return null
        val created = parseApns(newOutput).firstOrNull { it.name == DUP_APN_NAME }
        if (created == null) {
            log("APN: inserted duplicate but it doesn't appear in re-query")
            return null
        }
        log("APN: duplicate created id=${created.id} name=${created.name} apn=${created.apn}")
        return created
    }

    private fun queryFullApn(id: Int, log: (String) -> Unit): Map<String, String>? {
        // `--where` is supported on Android 9+; on older versions we fall back
        // to querying all and filtering in-process.
        val output = runRootOutput("content query --uri content://telephony/carriers --where \"_id=$id\"")
            ?: runRootOutput("content query --uri content://telephony/carriers")
            ?: return null
        val rowRe = Regex("""Row:\s*\d+\s+([^\n]+)""")
        for (match in rowRe.findAll(output)) {
            val fields = HashMap<String, String>()
            for (part in match.groupValues[1].split(", ")) {
                val eq = part.indexOf('=')
                if (eq <= 0) continue
                fields[part.substring(0, eq).trim()] = part.substring(eq + 1).trim()
            }
            if (fields["_id"]?.toIntOrNull() == id) {
                log("APN: read ${fields.size} fields from row id=$id (apn=${fields["apn"]})")
                return fields
            }
        }
        log("APN: row id=$id not found in query output")
        return null
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
        // Older Android: enumerate and delete by row URI.
        val output = runRootOutput("content query --uri content://telephony/carriers") ?: return
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
