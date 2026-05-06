package com.proxyagent.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Build
import java.io.File

// Aggregates traffic + connection events into per-minute buckets and flushes
// them to AnalyticsStore. Used inside ProxyService — single-threaded access
// from the StatusUpdater thread + occasional onTunnel{Open,Close} from the
// agent line parser, so all mutators are @Synchronized for safety.
class AnalyticsRecorder(private val ctx: Context) {

    private val uid = android.os.Process.myUid()

    @Volatile private var bucketStartMs = floorToMinute(System.currentTimeMillis())
    @Volatile private var rxBaseline = currentRx()
    @Volatile private var txBaseline = currentTx()

    private var rxAccum = 0L
    private var txAccum = 0L
    private var opens = 0
    private var closes = 0
    private var peakTunnels = 0
    private var activeTunnels = 0

    @Volatile private var lastRegistrator = ""
    @Volatile private var lastNatIp = ""
    @Volatile private var lastTransport = ""

    @Synchronized
    fun setRegistrator(reg: String) { if (reg.isNotEmpty()) lastRegistrator = reg }

    @Synchronized
    fun setNatIp(ip: String) { if (ip.isNotEmpty()) lastNatIp = ip }

    @Synchronized
    fun setTransport(t: String) { if (t.isNotEmpty()) lastTransport = t }

    @Synchronized
    fun onTunnelOpen() {
        opens++
        activeTunnels++
        if (activeTunnels > peakTunnels) peakTunnels = activeTunnels
    }

    @Synchronized
    fun onTunnelClose() {
        closes++
        activeTunnels = (activeTunnels - 1).coerceAtLeast(0)
    }

    // Resets active-tunnels count without producing a flood of close events.
    // Use when the WS reconnects and tunnels are re-counted from scratch.
    @Synchronized
    fun resetActiveTunnels() {
        activeTunnels = 0
    }

    // Called once per second from ProxyService.StatusUpdater. Accumulates
    // process-wide traffic delta into the open bucket and flushes when the
    // wall clock crosses the next minute boundary.
    @Synchronized
    fun tick() {
        // Refresh transport from the system (cheap; reflects current routing).
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= 23) {
                val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
                val t = when {
                    caps == null -> ""
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                    else -> "OTHER"
                }
                if (t.isNotEmpty()) lastTransport = t
            }
        } catch (_: Throwable) {}

        // Pick up NAT IP from the file the UI process writes when it refreshes.
        try {
            val f = File(ctx.filesDir, "nat_ip")
            if (f.exists()) {
                val ip = f.readText().trim()
                if (ip.isNotEmpty()) lastNatIp = ip
            }
        } catch (_: Throwable) {}

        // Accumulate process traffic delta. TrafficStats.UNSUPPORTED → -1; we
        // skip recording in that case (some emulators / older devices).
        val rxNow = currentRx()
        val txNow = currentTx()
        if (rxNow >= 0 && rxBaseline >= 0) {
            val drx = (rxNow - rxBaseline).coerceAtLeast(0)
            val dtx = (txNow - txBaseline).coerceAtLeast(0)
            rxAccum += drx
            txAccum += dtx
        }
        rxBaseline = rxNow
        txBaseline = txNow

        // Flush whenever wall clock has moved past the open bucket. Handles
        // long sleeps (>1 min) by flushing once and starting a fresh bucket.
        val now = floorToMinute(System.currentTimeMillis())
        if (now > bucketStartMs) {
            flushUnlocked()
            bucketStartMs = now
        }
    }

    @Synchronized
    fun flush() = flushUnlocked()

    private fun flushUnlocked() {
        // Skip empty buckets so the file doesn't bloat with zero-traffic
        // minutes when the user's just sitting on the start screen.
        if (rxAccum == 0L && txAccum == 0L && opens == 0 && closes == 0 &&
            peakTunnels == 0) {
            // Reset window-only counters so next bucket starts clean.
            opens = 0; closes = 0; peakTunnels = activeTunnels
            return
        }
        val bucket = AnalyticsBucket(
            tMs = bucketStartMs,
            rxBytes = rxAccum,
            txBytes = txAccum,
            opens = opens,
            closes = closes,
            peakTunnels = peakTunnels,
            registrator = lastRegistrator,
            natIp = lastNatIp,
            transport = lastTransport,
        )
        AnalyticsStore.appendBucket(ctx, bucket)
        rxAccum = 0; txAccum = 0
        opens = 0; closes = 0
        // Carry active-tunnels forward as the new "peak baseline" so the next
        // minute's peak reflects sustained activity, not a ramp-up artifact.
        peakTunnels = activeTunnels
    }

    private fun currentRx(): Long {
        val v = TrafficStats.getUidRxBytes(uid)
        return if (v == TrafficStats.UNSUPPORTED.toLong()) -1L else v
    }

    private fun currentTx(): Long {
        val v = TrafficStats.getUidTxBytes(uid)
        return if (v == TrafficStats.UNSUPPORTED.toLong()) -1L else v
    }

    private fun floorToMinute(ms: Long): Long = (ms / AnalyticsStore.BUCKET_MS) * AnalyticsStore.BUCKET_MS
}
