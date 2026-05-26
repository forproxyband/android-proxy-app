package com.proxyagent.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.provider.Settings
import android.telephony.TelephonyManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Probe + remediate for "device may turn cellular off while Wi-Fi is up".
//
// Why this matters for Wi-Fi return
// ─────────────────────────────────
// The Wi-Fi return relay only saves mobile data if both transports stay up
// simultaneously — the agent↔registrator uplink rides Wi-Fi while the
// agent→target dial rides cellular. On stock Android both can be on; but
// many OEMs (and the AOSP default on older builds) shut cellular data down
// when a usable Wi-Fi is connected, leaving the relay with nowhere to send
// the outbound target dials. The result: clients connect through the proxy,
// the agent dials the target… and the dial fails because cellular is gone.
//
// The system setting that controls this is `Settings.Global.mobile_data_
// always_on` (an integer, 0/1). When 1, the modem stays attached and a
// cellular Network keeps existing alongside Wi-Fi. When 0, the OS releases
// the cellular Network as soon as Wi-Fi validates. The constant is part of
// AOSP but isn't exposed as a public field, so we read/write by literal
// string — which is fine because the name has been stable since L.
//
// Permissions
// ───────────
// Reading is unrestricted (any process can `Settings.Global.getInt`).
// Writing requires WRITE_SECURE_SETTINGS, which the manifest already
// declares (granted for the IP-rotation airplane-mode toggle). When the
// permission was granted via `adb shell pm grant <pkg> WRITE_SECURE_SETTINGS`
// we can fix it ourselves; otherwise we surface an instruction the user can
// follow manually (developer-options toggle or adb command).
//
// Probing path
// ────────────
// 1. No SIM / no cellular at all → UNKNOWN, don't bother the user.
// 2. Read the system setting:
//    - = 1 → SUPPORTED (we're done, no UI).
//    - = 0 → BLOCKED (need to nudge the user, OR auto-fix if WRITE
//      permission is granted).
//    - missing/exception → fall through to active probe.
// 3. Active probe (5s budget): call `requestNetwork(TRANSPORT_CELLULAR +
//    INTERNET)`. If `onAvailable` fires within the budget, cellular is up
//    in parallel with whatever the default is → SUPPORTED. Otherwise →
//    BLOCKED. Active probe catches OEMs that ignore the setting and gate
//    cellular-while-on-Wi-Fi through their own logic.
//
// We never block the UI on the probe — caller is expected to invoke it on
// a worker thread and post the result back. CHECK_BUDGET_MS is the upper
// bound on how long check() can take.
object MobileDataAlwaysOnCheck {

    enum class Result {
        SUPPORTED,           // cellular keeps living while Wi-Fi is active
        BLOCKED,             // mobile_data_always_on=0 or active probe failed
        UNKNOWN,             // no SIM / no cellular hardware / probe failed for
                             // a reason that doesn't tell us anything useful
    }

    data class Report(
        val result: Result,
        // Whether we'd be ABLE to flip mobile_data_always_on for the user if
        // they confirmed. True iff WRITE_SECURE_SETTINGS is granted.
        val canAutoFix: Boolean,
        // Short diagnostic string for the log/UI: e.g. "setting=0, write=granted".
        val detail: String,
    )

    private const val SETTING_NAME = "mobile_data_always_on"
    private const val CHECK_BUDGET_MS = 5_000L

    fun check(context: Context): Report {
        // Step 1: do we even have cellular hardware? On a Wi-Fi-only tablet
        // or a device with the SIM removed, this question is moot — the
        // user neither needs the warning nor would understand it.
        if (!hasCellularCapability(context)) {
            return Report(Result.UNKNOWN, canAutoFix = false,
                detail = "no cellular hardware / SIM")
        }

        val canWrite = canWriteSecureSettings(context)
        val writeNote = if (canWrite) "write=granted" else "write=denied"

        // Step 2: read the global setting.
        val settingValue = readGlobalIntOrNull(context, SETTING_NAME)
        if (settingValue == 1) {
            return Report(Result.SUPPORTED, canWrite, "setting=1, $writeNote")
        }
        if (settingValue == 0) {
            return Report(Result.BLOCKED, canWrite, "setting=0, $writeNote")
        }

        // Setting not present (older Android or OEM that removed it). Fall
        // back to an active probe.
        val probed = probeCellularInParallel(context)
        return when (probed) {
            true -> Report(Result.SUPPORTED, canWrite, "probe=ok, setting=null, $writeNote")
            false -> Report(Result.BLOCKED, canWrite, "probe=fail, setting=null, $writeNote")
            null -> Report(Result.UNKNOWN, canWrite, "probe=indeterminate, $writeNote")
        }
    }

    // Tries to set mobile_data_always_on=1. Returns true iff the write
    // succeeded AND a subsequent read confirms the new value (some ROMs
    // silently reject writes from non-system UIDs even with the permission).
    // Safe to call even when the report said canAutoFix=false — it'll just
    // return false.
    fun tryEnable(context: Context): Boolean {
        return try {
            val ok = Settings.Global.putInt(context.contentResolver, SETTING_NAME, 1)
            if (!ok) return false
            // Read-back verification: some OEMs accept the put() call but
            // immediately reset the value, or the system reverts it on the
            // next radio-stack rebuild. A successful write that doesn't
            // stick is, for our purposes, a failure.
            readGlobalIntOrNull(context, SETTING_NAME) == 1
        } catch (_: SecurityException) {
            false
        } catch (_: Throwable) {
            false
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────
    private fun hasCellularCapability(context: Context): Boolean {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                ?: return false
            // PHONE_TYPE_NONE on Wi-Fi-only devices. We don't filter on
            // SIM_STATE_READY because some carriers (esim, multi-sim devices
            // mid-switch) report transient non-ready states even when
            // cellular is genuinely usable.
            tm.phoneType != TelephonyManager.PHONE_TYPE_NONE
        } catch (_: Throwable) { false }
    }

    private fun canWriteSecureSettings(context: Context): Boolean {
        // Manifest declares WRITE_SECURE_SETTINGS but the runtime grant has
        // to come via `adb shell pm grant`. checkSelfPermission tells us if
        // it actually went through.
        return try {
            context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) { false }
    }

    private fun readGlobalIntOrNull(context: Context, name: String): Int? {
        return try {
            // getInt(name, default) hides "setting missing" by returning the
            // default. getInt(name) without default throws SettingNotFoundException
            // which is exactly what we want for "fall through to active probe".
            Settings.Global.getInt(context.contentResolver, name)
        } catch (_: Settings.SettingNotFoundException) {
            null
        } catch (_: Throwable) {
            null
        }
    }

    // Tries to obtain a cellular Network in parallel with whatever's
    // currently default. Returns true if onAvailable fires within
    // CHECK_BUDGET_MS, false if it doesn't, null on systemic failure
    // (e.g. ConnectivityManager unavailable, which shouldn't happen but
    // we'd rather not pretend to know the answer).
    private fun probeCellularInParallel(context: Context): Boolean? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return null
        // Short-circuit: if cellular is already the active transport, we
        // don't need to ask the system to bring it up — it's clearly up.
        try {
            val active = cm.activeNetwork
            val caps = active?.let { cm.getNetworkCapabilities(it) }
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                return true
            }
        } catch (_: Throwable) {}

        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val latch = CountDownLatch(1)
        val available = arrayOf(false)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                available[0] = true
                latch.countDown()
            }
        }
        return try {
            cm.requestNetwork(req, callback)
            val fired = latch.await(CHECK_BUDGET_MS, TimeUnit.MILLISECONDS)
            // unregister even if the callback already fired — leaves no
            // dangling request that keeps the modem warm forever.
            try { cm.unregisterNetworkCallback(callback) } catch (_: Throwable) {}
            if (fired) available[0] else false
        } catch (_: SecurityException) {
            null
        } catch (_: Throwable) {
            null
        }
    }
}
