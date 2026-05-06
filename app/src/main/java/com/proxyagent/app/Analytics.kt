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
    val rxBytes: Long,        // bytes received during bucket
    val txBytes: Long,        // bytes sent during bucket
    val opens: Int,           // tunnels opened during bucket
    val closes: Int,          // tunnels closed during bucket
    val peakTunnels: Int,     // max active tunnels at any sample
    val registrator: String,  // best-known registrator at bucket end
    val natIp: String,        // best-known public IP at bucket end
    val transport: String,    // CELLULAR / WIFI / ETHERNET / ...
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
                )
            } catch (_: Throwable) { null }
        }
    }
}

object AnalyticsStore {

    private const val DIR = "analytics"
    private const val DATE_FMT = "yyyy-MM-dd"
    const val BUCKET_MS = 60_000L

    fun dir(ctx: Context): File = File(ctx.filesDir, DIR).apply { mkdirs() }

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
    }
}
