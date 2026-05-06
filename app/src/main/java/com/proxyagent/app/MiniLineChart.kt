package com.proxyagent.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

// Lightweight chart View — no external deps. Holds time-bucketed numeric
// series and renders either a filled area-line (style=LINE) or thin bars
// (style=BARS). Designed for the swipe-panel mini graphs and the analytics
// screen's larger charts; sizing is driven entirely by layout params.
class MiniLineChart @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    enum class Style { LINE, BARS }

    private var seriesValues: DoubleArray = DoubleArray(0)
    private var seriesStartMs: Long = 0L
    private var seriesStepMs: Long = AnalyticsStore.BUCKET_MS
    private var chartStyle: Style = Style.LINE
    private var lineColor = 0xFF00FF41.toInt()
    private var fillColor = 0x3300FF41
    private var gridColor = 0x33FFFFFF.toInt()
    private var labelColor = 0x99FFFFFF.toInt()
    private var emptyText: String = "no data"

    private val pathLine = Path()
    private val pathFill = Path()
    private val paintLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isDither = true
    }
    private val paintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val paintBar = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val paintGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val paintLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9f * resources.displayMetrics.scaledDensity
        isFakeBoldText = false
    }

    fun setStyle(s: Style) { chartStyle = s; invalidate() }

    fun setColors(line: Int, fill: Int) {
        lineColor = line
        fillColor = fill
        invalidate()
    }

    fun setEmptyText(s: String) { emptyText = s; invalidate() }

    fun setSeries(values: DoubleArray, startMs: Long, stepMs: Long) {
        this.seriesValues = values
        this.seriesStartMs = startMs
        this.seriesStepMs = stepMs
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val padL = 4f * resources.displayMetrics.density
        val padR = 4f * resources.displayMetrics.density
        val padT = 4f * resources.displayMetrics.density
        val padB = 14f * resources.displayMetrics.density
        val plotW = w - padL - padR
        val plotH = h - padT - padB

        // Empty state.
        if (seriesValues.isEmpty() || seriesValues.all { it <= 0.0 }) {
            paintLabel.color = labelColor
            paintLabel.textAlign = Paint.Align.CENTER
            canvas.drawText(emptyText, w / 2f, h / 2f, paintLabel)
            return
        }

        var maxV = 0.0
        for (v in seriesValues) if (v > maxV) maxV = v
        if (maxV <= 0) maxV = 1.0
        val ceiling = niceCeiling(maxV)

        // Two horizontal grid lines for visual scale.
        paintGrid.color = gridColor
        for (i in 1..2) {
            val y = padT + plotH * i / 3f
            canvas.drawLine(padL, y, padL + plotW, y, paintGrid)
        }

        val n = seriesValues.size
        if (chartStyle == Style.LINE) {
            pathLine.reset()
            pathFill.reset()
            for (i in 0 until n) {
                val x = padL + plotW * (i.toFloat() / max(1, n - 1).toFloat())
                val v = seriesValues[i].coerceAtLeast(0.0)
                val y = padT + plotH - (plotH * (v / ceiling)).toFloat()
                if (i == 0) {
                    pathLine.moveTo(x, y)
                    pathFill.moveTo(x, padT + plotH)
                    pathFill.lineTo(x, y)
                } else {
                    pathLine.lineTo(x, y)
                    pathFill.lineTo(x, y)
                }
            }
            pathFill.lineTo(padL + plotW, padT + plotH)
            pathFill.close()
            paintFill.color = fillColor
            canvas.drawPath(pathFill, paintFill)
            paintLine.color = lineColor
            canvas.drawPath(pathLine, paintLine)
        } else {
            // Bars: each bucket is one slim column with a 1px gap.
            paintBar.color = lineColor
            val barW = (plotW / n.toFloat()).coerceAtLeast(1f)
            val gap = if (barW > 3f) 1f else 0f
            for (i in 0 until n) {
                val v = seriesValues[i].coerceAtLeast(0.0)
                if (v <= 0) continue
                val x0 = padL + plotW * (i.toFloat() / n.toFloat())
                val x1 = (x0 + barW - gap).coerceAtMost(padL + plotW)
                val y = padT + plotH - (plotH * (v / ceiling)).toFloat()
                canvas.drawRect(x0, y, x1, padT + plotH, paintBar)
            }
        }

        // Y-axis upper-bound label (e.g. peak rate / peak count).
        paintLabel.color = labelColor
        paintLabel.textAlign = Paint.Align.LEFT
        canvas.drawText(formatYLabel(ceiling), padL + 2f, padT + paintLabel.textSize, paintLabel)

        // X-axis time-range label spans the bottom.
        if (n >= 2) {
            val endMs = seriesStartMs + seriesStepMs * (n - 1)
            paintLabel.textAlign = Paint.Align.LEFT
            canvas.drawText(formatTime(seriesStartMs), padL, h - 2f, paintLabel)
            paintLabel.textAlign = Paint.Align.RIGHT
            canvas.drawText(formatTime(endMs), padL + plotW, h - 2f, paintLabel)
        }
    }

    private fun niceCeiling(v: Double): Double {
        if (v <= 0) return 1.0
        val mag = Math.pow(10.0, Math.floor(Math.log10(v)))
        val frac = v / mag
        val niced = when {
            frac <= 1 -> 1.0
            frac <= 2 -> 2.0
            frac <= 5 -> 5.0
            else -> 10.0
        }
        return niced * mag
    }

    private var yLabelFormatter: ((Double) -> String)? = null
    fun setYLabelFormatter(f: (Double) -> String) { yLabelFormatter = f; invalidate() }

    private fun formatYLabel(v: Double): String {
        yLabelFormatter?.let { return it(v) }
        return when {
            v >= 1_000_000_000 -> "%.1fG".format(v / 1_000_000_000.0)
            v >= 1_000_000 -> "%.1fM".format(v / 1_000_000.0)
            v >= 1_000 -> "%.1fK".format(v / 1_000.0)
            else -> "%.0f".format(v)
        }
    }

    private fun formatTime(ms: Long): String {
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        return sdf.format(java.util.Date(ms))
    }
}
