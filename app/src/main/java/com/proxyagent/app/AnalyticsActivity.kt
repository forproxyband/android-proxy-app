package com.proxyagent.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Analytics screen — periodic totals + line/bar charts over the bucket store.
// Filter dropdown narrows results by registrator / NAT IP / transport. Save
// button exports the currently-displayed period as CSV via the share sheet.
class AnalyticsActivity : AppCompatActivity() {

    private lateinit var rgPeriod: RadioGroup
    private lateinit var spFilterType: Spinner
    private lateinit var spFilterValue: Spinner
    private lateinit var tvTotal: TextView
    private lateinit var tvIn: TextView
    private lateinit var tvOut: TextView
    private lateinit var tvConnTotal: TextView
    private lateinit var chartTraffic: MiniLineChart
    private lateinit var chartConn: MiniLineChart
    private lateinit var tvRotTotal: TextView
    private lateinit var tvRotManual: TextView
    private lateinit var tvRotAuto: TextView
    private lateinit var tvRotRate: TextView
    private lateinit var tvRotLast: TextView
    private lateinit var chartRot: MiniLineChart
    private lateinit var tvRotList: TextView
    private lateinit var tvRetentionHint: TextView
    private lateinit var btnExport: Button

    private val filterTypes = arrayOf("All", "Registrator IP", "NAT IP", "Transport")
    private var currentFilterType = 0
    private var currentFilterValue: String = ""
    private var currentBuckets: List<AnalyticsBucket> = emptyList()
    private var currentCycleEvents: List<CycleEvent> = emptyList()
    private var currentPeriodMs = 24L * 3600_000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_analytics)
        title = "Analytics"

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        rgPeriod = findViewById(R.id.rgPeriod)
        spFilterType = findViewById(R.id.spFilterType)
        spFilterValue = findViewById(R.id.spFilterValue)
        tvTotal = findViewById(R.id.tvTotal)
        tvIn = findViewById(R.id.tvIn)
        tvOut = findViewById(R.id.tvOut)
        tvConnTotal = findViewById(R.id.tvConnTotal)
        chartTraffic = findViewById(R.id.chartTraffic)
        chartConn = findViewById(R.id.chartConn)
        tvRotTotal = findViewById(R.id.tvRotTotal)
        tvRotManual = findViewById(R.id.tvRotManual)
        tvRotAuto = findViewById(R.id.tvRotAuto)
        tvRotRate = findViewById(R.id.tvRotRate)
        tvRotLast = findViewById(R.id.tvRotLast)
        chartRot = findViewById(R.id.chartRot)
        tvRotList = findViewById(R.id.tvRotList)
        tvRetentionHint = findViewById(R.id.tvRetentionHint)
        btnExport = findViewById(R.id.btnExportAnalytics)

        chartTraffic.setColors(0xFF00FF41.toInt(), 0x3300FF41)
        chartTraffic.setStyle(MiniLineChart.Style.LINE)
        chartTraffic.setEmptyText("no traffic in this period")
        chartTraffic.setYLabelFormatter { v -> humanBytesShort(v.toLong()) }

        chartConn.setColors(0xFFFFCC66.toInt(), 0x33FFCC66.toInt())
        chartConn.setStyle(MiniLineChart.Style.BARS)
        chartConn.setEmptyText("no connections in this period")
        chartConn.setYLabelFormatter { v -> "%.0f".format(v) }

        // Bottom = manual (cyan), stacked top = auto (magenta). Same hue
        // mapping as the swipe-panel mini chart so the colors carry across
        // the two surfaces.
        chartRot.setColors(0xFF66E0FF.toInt(), 0x3366E0FF.toInt())
        chartRot.setStackedTopColor(0xFFFF66CC.toInt())
        chartRot.setStyle(MiniLineChart.Style.STACKED_BARS)
        chartRot.setEmptyText("no rotations in this period")
        chartRot.setYLabelFormatter { v -> "%.0f".format(v) }

        rgPeriod.setOnCheckedChangeListener { _, _ -> reload() }

        ArrayAdapter(this, android.R.layout.simple_spinner_item, filterTypes).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spFilterType.adapter = it
        }
        spFilterType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                currentFilterType = position
                refreshFilterValues()
                applyFilter()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        spFilterValue.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                @Suppress("UNCHECKED_CAST")
                val items = (spFilterValue.adapter as? ArrayAdapter<String>) ?: return
                currentFilterValue = items.getItem(position) ?: ""
                applyFilter()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        btnExport.setOnClickListener { exportCsv() }

        val days = AnalyticsStore.retentionDays(this)
        tvRetentionHint.text = "Retention: ${days}d (settings → app)"

        reload()
    }

    private fun selectedPeriodMs(): Long = when (rgPeriod.checkedRadioButtonId) {
        R.id.rbPeriodWeek -> 7L * 86_400_000L
        R.id.rbPeriodMonth -> 30L * 86_400_000L
        else -> 24L * 3600_000L
    }

    private fun reload() {
        currentPeriodMs = selectedPeriodMs()
        val to = System.currentTimeMillis()
        val from = to - currentPeriodMs
        Thread {
            val buckets = try { AnalyticsStore.load(this, from, to) } catch (_: Throwable) { emptyList() }
            val events = try { AnalyticsStore.loadCycleEvents(this, from, to) } catch (_: Throwable) { emptyList() }
            runOnUiThread {
                currentBuckets = buckets
                currentCycleEvents = events
                refreshFilterValues()
                applyFilter()
            }
        }.apply { isDaemon = true; name = "AnalyticsLoad"; start() }
    }

    private fun refreshFilterValues() {
        val keyOf: (AnalyticsBucket) -> String = when (currentFilterType) {
            1 -> { b -> b.registrator }
            2 -> { b -> b.natIp }
            3 -> { b -> b.transport }
            else -> { _ -> "" }
        }
        if (currentFilterType == 0) {
            spFilterValue.visibility = View.GONE
            currentFilterValue = ""
            return
        }
        val seen = LinkedHashSet<String>()
        for (b in currentBuckets) {
            val k = keyOf(b)
            if (k.isNotEmpty()) seen.add(k)
        }
        val items = if (seen.isEmpty()) listOf("(none)") else seen.toList()
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spFilterValue.adapter = it
        }
        spFilterValue.visibility = View.VISIBLE
        currentFilterValue = items.first()
        if (currentFilterValue == "(none)") currentFilterValue = ""
    }

    private fun bucketMatchesFilter(b: AnalyticsBucket): Boolean {
        return when (currentFilterType) {
            1 -> b.registrator == currentFilterValue
            2 -> b.natIp == currentFilterValue
            3 -> b.transport == currentFilterValue
            else -> true
        }
    }

    private fun applyFilter() {
        val to = System.currentTimeMillis()
        val from = to - currentPeriodMs
        val filtered = if (currentFilterType == 0) currentBuckets
        else currentBuckets.filter { bucketMatchesFilter(it) }

        var rxTotal = 0L
        var txTotal = 0L
        var openTotal = 0L
        for (b in filtered) {
            rxTotal += b.rxBytes
            txTotal += b.txBytes
            openTotal += b.opens
        }
        tvIn.text = "↓ IN: ${humanBytes(rxTotal)}"
        tvOut.text = "↑ OUT: ${humanBytes(txTotal)}"
        tvTotal.text = humanBytes(rxTotal + txTotal)
        tvConnTotal.text = "$openTotal connection${if (openTotal == 1L) "" else "s"} opened"

        // Pick a chart bin width that gives ~120 cells across the period —
        // dense enough to show shape, sparse enough to keep redraw fast.
        val binMs = pickBinMs(currentPeriodMs)
        val n = ((currentPeriodMs / binMs)).toInt().coerceAtLeast(1)
        val traffic = DoubleArray(n)
        val conns = DoubleArray(n)
        for (b in filtered) {
            val idx = ((b.tMs - from) / binMs).toInt()
            if (idx < 0 || idx >= n) continue
            traffic[idx] += (b.rxBytes + b.txBytes).toDouble()
            conns[idx] += b.opens.toDouble()
        }
        chartTraffic.setSeries(traffic, from, binMs)
        chartConn.setSeries(conns, from, binMs)

        // Rotations: events have no registrator/natIp/transport association,
        // so the bucket filter doesn't apply — we always show all events in
        // the period (the heading next to the chart says "filter not applied").
        val rotManual = DoubleArray(n)
        val rotAuto = DoubleArray(n)
        var manualTotal = 0
        var autoTotal = 0
        var changedTotal = 0
        var lastEvent: CycleEvent? = null
        for (e in currentCycleEvents) {
            val idx = ((e.tMs - from) / binMs).toInt()
            if (idx < 0 || idx >= n) continue
            when (e.kind) {
                AnalyticsStore.CYCLE_MANUAL -> { rotManual[idx] += 1.0; manualTotal++ }
                AnalyticsStore.CYCLE_AUTO -> { rotAuto[idx] += 1.0; autoTotal++ }
            }
            if (e.changed) changedTotal++
            if (lastEvent == null || e.tMs > lastEvent.tMs) lastEvent = e
        }
        val rotTotal = manualTotal + autoTotal
        tvRotTotal.text = rotTotal.toString()
        tvRotManual.text = "↻ MANUAL: $manualTotal"
        tvRotAuto.text = "◉ AUTO: $autoTotal"
        // Average per day. Periods are 1d/7d/30d so this is the natural unit.
        val periodDays = (currentPeriodMs / 86_400_000.0).coerceAtLeast(1.0 / 24.0)
        val avgPerDay = rotTotal / periodDays
        tvRotRate.text = "avg ${"%.1f".format(avgPerDay)}/day · $changedTotal IP change${if (changedTotal == 1) "" else "s"}"
        tvRotLast.text = lastEvent?.let { e ->
            val ago = humanAgo(System.currentTimeMillis() - e.tMs)
            val ipPart = when {
                e.changed && e.newIp.isNotEmpty() && e.oldIp.isNotEmpty() ->
                    " · ${e.oldIp} → ${e.newIp}"
                e.newIp.isNotEmpty() && e.oldIp.isNotEmpty() && e.newIp == e.oldIp ->
                    " · unchanged ${e.newIp}"
                else -> ""
            }
            "last rotation: $ago ago$ipPart"
        } ?: "no rotations recorded"
        chartRot.setStackedSeries(rotManual, rotAuto, from, binMs)

        // Recent rotations list — most-recent first, capped at 10 rows so we
        // don't blow up the screen. Each row: HH:mm · kind · old→new (or
        // unchanged) · attempts/duration. Matches the CSV format roughly.
        val recent = currentCycleEvents.asReversed().take(10)
        tvRotList.text = if (recent.isEmpty()) {
            "no rotations recorded in this period"
        } else {
            val tsFmt = SimpleDateFormat("HH:mm", Locale.US)
            recent.joinToString("\n") { e -> formatRotationRow(e, tsFmt) }
        }
    }

    private fun formatRotationRow(e: CycleEvent, tsFmt: SimpleDateFormat): String {
        val time = tsFmt.format(Date(e.tMs))
        val kindLabel = if (e.kind == AnalyticsStore.CYCLE_MANUAL) "↻ man " else "◉ auto"
        val ipPart = when {
            e.changed && e.oldIp.isNotEmpty() && e.newIp.isNotEmpty() ->
                "${e.oldIp} → ${e.newIp}"
            e.oldIp.isNotEmpty() && e.newIp.isNotEmpty() && e.newIp == e.oldIp ->
                "unchanged ${e.newIp}"
            e.oldIp.isNotEmpty() ->
                "from ${e.oldIp} (no result)"
            e.reason == "no_toggle_method" -> "no toggle available"
            e.reason.isNotEmpty() -> e.reason
            else -> "(no detail)"
        }
        val meta = buildString {
            if (e.attempts > 0) append(" · ${e.attempts}× try")
            if (e.durationMs > 0) append(" / ${e.durationMs / 1000}s")
        }
        return "$time  $kindLabel  $ipPart$meta"
    }

    // Compact "N units ago" — used for "last rotation". Not exact (floors),
    // just enough granularity for the eye to glance and move on.
    private fun humanAgo(deltaMs: Long): String {
        val d = if (deltaMs < 0) 0L else deltaMs
        return when {
            d < 60_000L -> "${d / 1000}s"
            d < 3600_000L -> "${d / 60_000L}m"
            d < 86_400_000L -> "${d / 3600_000L}h"
            else -> "${d / 86_400_000L}d"
        }
    }

    private fun pickBinMs(periodMs: Long): Long {
        // Aim for ~120 cells across the period, clamped to 1-min buckets.
        val target = (periodMs / 120L).coerceAtLeast(AnalyticsStore.BUCKET_MS)
        // Round to a multiple of BUCKET_MS so each bin maps cleanly to whole minutes.
        return (target / AnalyticsStore.BUCKET_MS) * AnalyticsStore.BUCKET_MS
    }

    private fun exportCsv() {
        try {
            val to = System.currentTimeMillis()
            val from = to - currentPeriodMs
            val buckets = currentBuckets.filter { bucketMatchesFilter(it) }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val periodLabel = when (rgPeriod.checkedRadioButtonId) {
                R.id.rbPeriodWeek -> "week"
                R.id.rbPeriodMonth -> "month"
                else -> "day"
            }
            val filterLabel = when (currentFilterType) {
                0 -> "all"
                1 -> "registrator-${sanitize(currentFilterValue)}"
                2 -> "natip-${sanitize(currentFilterValue)}"
                3 -> "transport-${sanitize(currentFilterValue)}"
                else -> "all"
            }
            val exportDir = File(filesDir, "exports").apply { mkdirs() }
            val out = File(exportDir, "proxy-agent-analytics-$periodLabel-$filterLabel-$stamp.csv")
            val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            val manualCount = currentCycleEvents.count { it.kind == AnalyticsStore.CYCLE_MANUAL }
            val autoCount = currentCycleEvents.count { it.kind == AnalyticsStore.CYCLE_AUTO }
            out.bufferedWriter().use { w ->
                w.append("# Proxy Agent · Analytics export\n")
                w.append("# Generated: $stamp\n")
                w.append("# Period: $periodLabel (${tsFmt.format(Date(from))} → ${tsFmt.format(Date(to))})\n")
                w.append("# Filter: $filterLabel\n")
                w.append("# Totals: rxBytes=").append(buckets.sumOf { it.rxBytes }.toString())
                w.append(" txBytes=").append(buckets.sumOf { it.txBytes }.toString())
                w.append(" opens=").append(buckets.sumOf { it.opens }.toString()).append("\n")
                w.append("# Rotations: total=").append((manualCount + autoCount).toString())
                w.append(" manual=").append(manualCount.toString())
                w.append(" auto=").append(autoCount.toString())
                w.append(" (filter not applied — events have no registrator/transport association)\n")
                w.append("timestamp,unix_ms,rx_bytes,tx_bytes,opens,closes,peak_tunnels,registrator,nat_ip,transport\n")
                for (b in buckets) {
                    w.append(tsFmt.format(Date(b.tMs))).append(',')
                    w.append(b.tMs.toString()).append(',')
                    w.append(b.rxBytes.toString()).append(',')
                    w.append(b.txBytes.toString()).append(',')
                    w.append(b.opens.toString()).append(',')
                    w.append(b.closes.toString()).append(',')
                    w.append(b.peakTunnels.toString()).append(',')
                    w.append(csvEscape(b.registrator)).append(',')
                    w.append(csvEscape(b.natIp)).append(',')
                    w.append(csvEscape(b.transport)).append('\n')
                }
                // Second logical table after a blank-line separator. Lets the
                // user grep / awk just the rotation events without touching
                // the bucket rows. Header re-stated for clarity.
                if (currentCycleEvents.isNotEmpty()) {
                    w.append('\n')
                    w.append("# Cycle events (one row per rotation; filter does not apply)\n")
                    w.append("timestamp,unix_ms,kind,old_ip,new_ip,changed,reason,attempts,duration_ms\n")
                    for (e in currentCycleEvents) {
                        w.append(tsFmt.format(Date(e.tMs))).append(',')
                        w.append(e.tMs.toString()).append(',')
                        w.append(if (e.kind == AnalyticsStore.CYCLE_MANUAL) "manual" else "auto").append(',')
                        w.append(csvEscape(e.oldIp)).append(',')
                        w.append(csvEscape(e.newIp)).append(',')
                        w.append(if (e.changed) "true" else "false").append(',')
                        w.append(csvEscape(e.reason)).append(',')
                        w.append(e.attempts.toString()).append(',')
                        w.append(e.durationMs.toString()).append('\n')
                    }
                }
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", out)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Proxy Agent analytics $stamp")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "Save / share analytics"))
        } catch (e: Throwable) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun csvEscape(s: String): String {
        if (s.isEmpty()) return ""
        val needsQuote = s.contains(',') || s.contains('"') || s.contains('\n')
        return if (needsQuote) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    private fun sanitize(s: String): String =
        s.replace(Regex("""[^A-Za-z0-9._-]"""), "_").take(40).ifEmpty { "x" }

    private fun humanBytes(b: Long): String {
        val abs = if (b < 0) 0L else b
        return when {
            abs < 1024 -> "$abs B"
            abs < 1024L * 1024 -> "%.1f KB".format(abs / 1024.0)
            abs < 1024L * 1024 * 1024 -> "%.1f MB".format(abs / 1024.0 / 1024.0)
            else -> "%.2f GB".format(abs / 1024.0 / 1024.0 / 1024.0)
        }
    }

    private fun humanBytesShort(b: Long): String = when {
        b >= 1024L * 1024 * 1024 -> "%.1fG".format(b / 1024.0 / 1024.0 / 1024.0)
        b >= 1024L * 1024 -> "%.1fM".format(b / 1024.0 / 1024.0)
        b >= 1024 -> "%.0fK".format(b / 1024.0)
        else -> "$b"
    }
}
