package com.proxyagent.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build

// Snapshot of the currently-attached Wi-Fi link's physical characteristics:
// negotiated link speed, frequency band, and standard (Wi-Fi 4/5/6/...).
//
// Why we collect this
// ───────────────────
// The Wi-Fi return widget shows two public IPs + link details so the
// admin can verify at a glance that:
//   1. The phone is on Wi-Fi (link speed > 0).
//   2. It's on the band they expected (5 GHz vs 2.4 GHz — affects
//      throughput; some users connect to 2.4 GHz by mistake on
//      dual-band routers).
//   3. The standard supports the speeds they need.
//
// Permissions
// ───────────
// We deliberately avoid SSID/BSSID — those require ACCESS_FINE_LOCATION
// at runtime (or NEARBY_WIFI_DEVICES on Android 13+), which means a
// permission dialog and a settings rationale. Link speed / frequency /
// standard are all available with just ACCESS_WIFI_STATE (normal
// permission, no runtime prompt). That gives enough diagnostic info
// without bothering the user.
//
// API split
// ─────────
// - API ≤ 30: WifiManager.connectionInfo (deprecated on 31+ but still
//   returns valid data on older devices that haven't been updated to
//   the new flow).
// - API 31+: NetworkCapabilities.transportInfo as WifiInfo, where the
//   NetworkCapabilities come from the actual Wi-Fi Network we hold.
//   This is the "correct" path going forward — the old getter starts
//   returning REDACTED values on some Android 12+ ROMs to enforce
//   privacy.
object WifiInfoProbe {

    data class Snapshot(
        // Negotiated PHY rate as reported by the modem driver. -1 means
        // we couldn't read it (no Wi-Fi attached, permission denied,
        // ROM returning REDACTED).
        val linkSpeedMbps: Int,
        // Channel center frequency in MHz. -1 means unknown. Used to
        // derive the band string below; we keep the raw number too in
        // case downstream code wants to identify the specific channel.
        val frequencyMhz: Int,
        // Human-readable band: "2.4 GHz" / "5 GHz" / "6 GHz" / "unknown".
        val band: String,
        // Human-readable standard label: "Wi-Fi 4 (802.11n)" / "Wi-Fi 5
        // (802.11ac)" / "Wi-Fi 6 (802.11ax)" / "Wi-Fi 6E" / "Wi-Fi 7
        // (802.11be)" / "legacy" / "unknown". On API < 30 we can't get
        // the standard explicitly, so we fall back to "unknown".
        val standard: String,
        // True iff we got meaningful link data (at least link speed > 0
        // OR frequency known). False means we're not really on Wi-Fi.
        val attached: Boolean,
    ) {
        companion object {
            val EMPTY = Snapshot(
                linkSpeedMbps = -1,
                frequencyMhz = -1,
                band = "unknown",
                standard = "unknown",
                attached = false,
            )
        }
    }

    fun snapshot(context: Context, wifiNetwork: Network? = null): Snapshot {
        // Prefer the explicit Wi-Fi Network the caller hands us (the
        // one our relay is bound to). Falls back to inspecting the
        // current default network if no explicit handle — that's
        // useful from MainActivity which doesn't own a Network ref.
        val info = readWifiInfo(context, wifiNetwork) ?: return Snapshot.EMPTY

        val speed = try { info.linkSpeed } catch (_: Throwable) { -1 }
        val freq = try { info.frequency } catch (_: Throwable) { -1 }

        val band = bandFromFrequency(freq)
        val standard = standardLabel(info)
        val attached = speed > 0 || freq > 0
        return Snapshot(
            linkSpeedMbps = speed,
            frequencyMhz = freq,
            band = band,
            standard = standard,
            attached = attached,
        )
    }

    private fun readWifiInfo(context: Context, wifiNetwork: Network?): WifiInfo? {
        // API 31+ path: pull WifiInfo out of the Network's capabilities.
        // This is the only reliable path on Android 12+ because the
        // legacy WifiManager.connectionInfo started returning REDACTED
        // SSID / MAC values when callers lack location permission, and
        // some ROMs extended the redaction to the whole WifiInfo blob.
        if (Build.VERSION.SDK_INT >= 31 && wifiNetwork != null) {
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                    as? ConnectivityManager
                val caps = cm?.getNetworkCapabilities(wifiNetwork)
                val ti = caps?.transportInfo
                if (ti is WifiInfo) return ti
            } catch (_: Throwable) {}
        }

        // Fallback for everything else (API < 31, or 31+ when we don't
        // have the specific Wi-Fi network handle). Deprecated on 31+
        // but still returns *something* on most ROMs.
        return try {
            @Suppress("DEPRECATION")
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            wm?.connectionInfo
        } catch (_: Throwable) { null }
    }

    // Maps channel center frequency to a band label. Boundaries follow
    // IEEE 802.11 allocations:
    //   - 2.4 GHz band: ~2412–2484 MHz (channels 1–14)
    //   - 5 GHz band:   ~5170–5825 MHz (UNII-1 through UNII-3 + DFS)
    //   - 6 GHz band:   ~5925–7125 MHz (UNII-5 through UNII-8, Wi-Fi 6E)
    // We use generous-but-disjoint bounds rather than exact channel
    // tables — a value just outside an "official" range still tells the
    // admin which band the modem is using.
    private fun bandFromFrequency(freq: Int): String = when {
        freq <= 0 -> "unknown"
        freq in 2300..2700 -> "2.4 GHz"
        freq in 4900..5900 -> "5 GHz"
        freq in 5901..7200 -> "6 GHz"
        else -> "unknown"
    }

    // Best-effort identification of the Wi-Fi standard. WifiInfo
    // exposes wifiStandard from API 30+; older devices have no way to
    // tell us directly. We map the int constants to friendly labels
    // and keep "unknown" / "legacy" as graceful fallbacks.
    private fun standardLabel(info: WifiInfo): String {
        if (Build.VERSION.SDK_INT < 30) return "unknown"
        return try {
            // Use reflection instead of the constants directly — the
            // ScanResult.WIFI_STANDARD_* constants are on ScanResult,
            // and we'd add an import / SDK dependency check for the
            // same effect. Reflection lets us tolerate older devices
            // that have the API surface but not the specific constants.
            val std = info.javaClass.getMethod("getWifiStandard").invoke(info) as? Int
                ?: return "unknown"
            when (std) {
                // android.net.wifi.ScanResult.WIFI_STANDARD_*
                0 -> "unknown"
                1 -> "legacy (802.11a/b/g)"
                4 -> "Wi-Fi 4 (802.11n)"
                5 -> "Wi-Fi 5 (802.11ac)"
                6 -> "Wi-Fi 6 (802.11ax)"
                7 -> "Wi-Fi 6E"
                8 -> "Wi-Fi 7 (802.11be)"
                else -> "code=$std"
            }
        } catch (_: Throwable) { "unknown" }
    }
}
