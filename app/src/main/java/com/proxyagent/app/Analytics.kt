package com.proxyagent.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// One-minute aggregation bucket. Persisted as a single JSONL line in
// filesDir/analytics/yyyy-MM-dd.jsonl. Per-day files keep prune cheap (delete
// whole files) and crash-tolerant (only the last partial line is ever lost).
data class AnalyticsBucket(
    val tMs: Long,            // bucket start
    val rxBytes: Long,        // total bytes received (UID-wide via TrafficStats)
    val txBytes: Long,        // total bytes sent (UID-wide via TrafficStats)
    val opens: Int,           // tunnels opened during bucket
    val closes: Int,          // tunnels closed during bucket
    val peakTunnels: Int,     // max active tunnels at any sample
    val registrator: String,  // best-known registrator at bucket end
    val natIp: String,        // best-known public IP at bucket end
    val transport: String,    // CELLULAR / WIFI / ETHERNET / ...
    // Wi-Fi return split tracking. Optional (default 0). Written only
    // when the relay was alive at tick() time — i.e. wifi_return is
    // opt-in and these fields stay zero in every bucket while the
    // feature is off. fromJsonLine fills them as 0 for pre-feature
    // buckets too (forward-compat). When the relay is alive:
    //   wifi* = bytes that traversed the relay's upstream socket
    //           while bound to Wi-Fi (the real mobile-data savings)
    //   cell* = bytes that flowed through cellular: native agent
    //           target dials (always cellular when process bind is
    //           on) PLUS relay fallback bytes (when wifiNet was null
    //           and the relay routed through the default route)
    // Sum (wifi+cell) ≈ rx/tx with a small gap = TCP/QUIC headers,
    // AUTH handshake, heartbeat bytes our counters don't track.
    // When wifi_return is off, per-UID TrafficStats (rx/tx) still
    // gives the total, but we deliberately don't try to guess the
    // split — without bindProcessToNetwork(cellular) we don't know
    // which side of the wire any byte traversed, and pretending we
    // do would put garbage in the analytics.
    val wifiRxBytes: Long = 0L,
    val wifiTxBytes: Long = 0L,
    val cellRxBytes: Long = 0L,
    val cellTxBytes: Long = 0L,
) {
    fun toJsonLine(): String {
        val o = JSONObject()
        o.put("t", tMs)
        if (rxBytes != 0L) o.put("rx", rxBytes)
        if (txBytes != 0L) o.put("tx", txBytes)
        if (opens != 0) o.put("op", opens)
        if (closes != 0) o.put("cl", closes)
        if (peakTunnels != 0) o.put("pk", peakTunnels)
        if (registrator.isNotEmpty()) o.put("reg", registrator)
        if (natIp.isNotEmpty()) o.put("nat", natIp)
        if (transport.isNotEmpty()) o.put("tr", transport)
        if (wifiRxBytes != 0L) o.put("wrx", wifiRxBytes)
        if (wifiTxBytes != 0L) o.put("wtx", wifiTxBytes)
        if (cellRxBytes != 0L) o.put("crx", cellRxBytes)
        if (cellTxBytes != 0L) o.put("ctx", cellTxBytes)
        return o.toString()
    }

    companion object {
        fun fromJsonLine(line: String): AnalyticsBucket? {
            return try {
                val o = JSONObject(line)
                AnalyticsBucket(
                    tMs = o.optLong("t", 0L),
                    rxBytes = o.optLong("rx", 0L),
                    txBytes = o.optLong("tx", 0L),
                    opens = o.optInt("op", 0),
                    closes = o.optInt("cl", 0),
                    peakTunnels = o.optInt("pk", 0),
                    registrator = o.optString("reg", ""),
                    natIp = o.optString("nat", ""),
                    transport = o.optString("tr", ""),
                    wifiRxBytes = o.optLong("wrx", 0L),
                    wifiTxBytes = o.optLong("wtx", 0L),
                    cellRxBytes = o.optLong("crx", 0L),
                    cellTxBytes = o.optLong("ctx", 0L),
                )
            } catch (_: Throwable) { null }
        }
    }
}

// One IP-rotation trigger. "m" = manual ↻ from the UI, "a" = automatic
// from a server-side REBOOT. Stored separately from traffic buckets because
// events are sparse (handful per hour at most) and naturally not aligned to
// minute boundaries — folding them into AnalyticsBucket would either inflate
// the per-minute file with mostly-zero rows or race the recorder's flush.
//
// `tMs` is the moment the cycle FINISHED (not when it was triggered) so the
// timestamp reflects when the new IP became effective. Older log lines that
// pre-date the extended schema may have only {t, k} — fromJsonLine fills
// missing fields with defaults so they still render in the chart, just
// without the IP-change detail.
data class CycleEvent(
    val tMs: Long,
    val kind: String,         // "m" / "a"
    val oldIp: String = "",   // IP before cycle (empty if unknown)
    val newIp: String = "",   // IP after cycle (empty if cycle never observed one)
    val changed: Boolean = false,
    val reason: String = "",  // "ok" / "ok_no_baseline" / "ip_unchanged" / "no_toggle_method" / "interrupted"
    val attempts: Int = 0,
    val durationMs: Long = 0,
) {
    fun toJsonLine(): String {
        val o = JSONObject()
        o.put("t", tMs)
        o.put("k", kind)
        if (oldIp.isNotEmpty()) o.put("old", oldIp)
        if (newIp.isNotEmpty()) o.put("new", newIp)
        if (changed) o.put("ok", true)
        if (reason.isNotEmpty()) o.put("r", reason)
        if (attempts > 0) o.put("a", attempts)
        if (durationMs > 0) o.put("d", durationMs)
        return o.toString()
    }

    companion object {
        fun fromJsonLine(line: String): CycleEvent? = try {
            val o = JSONObject(line)
            val t = o.optLong("t", 0L)
            val k = o.optString("k", "")
            if (t > 0L && k.isNotEmpty()) {
                CycleEvent(
                    tMs = t,
                    kind = k,
                    oldIp = o.optString("old", ""),
                    newIp = o.optString("new", ""),
                    changed = o.optBoolean("ok", false),
                    reason = o.optString("r", ""),
                    attempts = o.optInt("a", 0),
                    durationMs = o.optLong("d", 0L),
                )
            } else null
        } catch (_: Throwable) { null }
    }
}

object AnalyticsStore {

    private const val DIR = "analytics"
    private const val DATE_FMT = "yyyy-MM-dd"
    private const val CYCLE_EVENTS_FILE = "cycle_events.jsonl"
    const val BUCKET_MS = 60_000L

    const val CYCLE_MANUAL = "m"
    const val CYCLE_AUTO = "a"

    fun dir(ctx: Context): File = File(ctx.filesDir, DIR).apply { mkdirs() }

    private fun cycleEventsFile(ctx: Context): File = File(dir(ctx), CYCLE_EVENTS_FILE)

    // Public from both processes (MainActivity in :main, ProxyService in :proxy).
    // appendText opens-write-close-flushes atomically per call, and lines are
    // self-delimited so a torn write at most loses the trailing event — never
    // corrupts earlier rows.
    @Synchronized
    fun recordCycleEvent(ctx: Context, event: CycleEvent) {
        try {
            cycleEventsFile(ctx).appendText(event.toJsonLine() + "\n")
        } catch (_: Throwable) {}
    }

    // Convenience overload for the "minimal" case — used as a last-resort
    // fallback when we don't have the cycle result (e.g. an early abort).
    // Prefer the (ctx, CycleEvent) form so the analytics row carries
    // old/new IP and the outcome.
    fun recordCycleEvent(ctx: Context, kind: String) {
        recordCycleEvent(ctx, CycleEvent(System.currentTimeMillis(), kind))
    }

    fun loadCycleEvents(ctx: Context, fromMs: Long, toMs: Long): List<CycleEvent> {
        val f = cycleEventsFile(ctx)
        if (!f.exists()) return emptyList()
        val out = ArrayList<CycleEvent>(64)
        try {
            f.useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    val e = CycleEvent.fromJsonLine(line) ?: continue
                    if (e.tMs in fromMs..toMs) out.add(e)
                }
            }
        } catch (_: Throwable) {}
        out.sortBy { it.tMs }
        return out
    }

    // Rewrites cycle_events.jsonl with only the rows on or after cutoffMs.
    // Called from pruneToRetention alongside the per-day bucket cleanup.
    @Synchronized
    private fun pruneCycleEventsBefore(ctx: Context, cutoffMs: Long) {
        val f = cycleEventsFile(ctx)
        if (!f.exists()) return
        try {
            val kept = ArrayList<String>(64)
            f.useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    val e = CycleEvent.fromJsonLine(line) ?: continue
                    if (e.tMs >= cutoffMs) kept.add(line)
                }
            }
            // Write back; if we end up with zero kept events, just delete.
            if (kept.isEmpty()) {
                f.delete()
            } else {
                f.writeText(kept.joinToString("\n") + "\n")
            }
        } catch (_: Throwable) {}
    }

    private fun fileForDay(ctx: Context, dayStartMs: Long): File {
        val sdf = SimpleDateFormat(DATE_FMT, Locale.US)
        return File(dir(ctx), "${sdf.format(Date(dayStartMs))}.jsonl")
    }

    @Synchronized
    fun appendBucket(ctx: Context, bucket: AnalyticsBucket) {
        try {
            fileForDay(ctx, bucket.tMs).appendText(bucket.toJsonLine() + "\n")
        } catch (_: Throwable) {}
    }

    // Returns buckets in [fromMs, toMs] inclusive, sorted by time.
    fun load(ctx: Context, fromMs: Long, toMs: Long): List<AnalyticsBucket> {
        if (toMs < fromMs) return emptyList()
        val out = ArrayList<AnalyticsBucket>(1024)
        val sdf = SimpleDateFormat(DATE_FMT, Locale.US)
        val cal = Calendar.getInstance().apply {
            timeZone = TimeZone.getDefault()
            timeInMillis = fromMs
            // Floor to local-day start so we cover the file containing fromMs.
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val end = toMs
        while (cal.timeInMillis <= end) {
            val f = File(dir(ctx), "${sdf.format(cal.time)}.jsonl")
            if (f.exists()) {
                try {
                    f.useLines { lines ->
                        for (line in lines) {
                            if (line.isBlank()) continue
                            val b = AnalyticsBucket.fromJsonLine(line) ?: continue
                            if (b.tMs in fromMs..toMs) out.add(b)
                        }
                    }
                } catch (_: Throwable) {}
            }
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        out.sortBy { it.tMs }
        return out
    }

    // Delete day-files whose date is strictly before cutoffMs's local day.
    fun pruneBefore(ctx: Context, cutoffMs: Long): Int {
        val cal = Calendar.getInstance().apply {
            timeInMillis = cutoffMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val cutoffDayMs = cal.timeInMillis
        val sdf = SimpleDateFormat(DATE_FMT, Locale.US)
        var deleted = 0
        dir(ctx).listFiles()?.forEach { f ->
            val name = f.name
            if (!name.endsWith(".jsonl")) return@forEach
            val datePart = name.removeSuffix(".jsonl")
            val parsed = try { sdf.parse(datePart) } catch (_: Throwable) { null }
            if (parsed != null && parsed.time < cutoffDayMs) {
                if (f.delete()) deleted++
            }
        }
        return deleted
    }

    fun retentionDays(ctx: Context): Int {
        val v = ctx.getSharedPreferences("cfg", 0).getInt("analytics_retention_days", 30)
        return v.coerceIn(1, 365)
    }

    fun pruneToRetention(ctx: Context) {
        val days = retentionDays(ctx).toLong()
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        pruneBefore(ctx, cutoff)
        pruneCycleEventsBefore(ctx, cutoff)
    }
}
