package com.proxyagent.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

// Adapter for the swipeable green status panel.
// Page 0: live status text (registrator / tunnels / current rate)
// Page 1: 24h traffic mini chart
// Page 2: 24h connections mini chart
//
// Holders are cached on the activity (refs is populated in onCreateViewHolder)
// so MainActivity can update them in-place from its 1Hz refresh loop without
// going through notifyDataSetChanged (which would rebuild the ViewPager state).
class StatusPagerAdapter(
    private val refs: PageRefs,
) : RecyclerView.Adapter<StatusPagerAdapter.VH>() {

    class PageRefs {
        var statusRoot: View? = null
        var tvRegistrator: TextView? = null
        var tvUptime: TextView? = null
        var tvActivity: TextView? = null

        var trafficRoot: View? = null
        var trafficTitle: TextView? = null
        var trafficTotal: TextView? = null
        var trafficChart: MiniLineChart? = null

        var connRoot: View? = null
        var connTitle: TextView? = null
        var connTotal: TextView? = null
        var connChart: MiniLineChart? = null
    }

    class VH(v: View) : RecyclerView.ViewHolder(v)

    override fun getItemViewType(position: Int): Int = position

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> {
                val v = inflater.inflate(R.layout.panel_status, parent, false)
                refs.statusRoot = v
                refs.tvRegistrator = v.findViewById(R.id.tvRegistrator)
                refs.tvUptime = v.findViewById(R.id.tvUptime)
                refs.tvActivity = v.findViewById(R.id.tvActivity)
                VH(v)
            }
            1 -> {
                val v = inflater.inflate(R.layout.panel_chart, parent, false)
                refs.trafficRoot = v
                refs.trafficTitle = v.findViewById(R.id.tvChartTitle)
                refs.trafficTotal = v.findViewById(R.id.tvChartTotal)
                refs.trafficChart = v.findViewById(R.id.chart)
                refs.trafficTitle?.text = "TRAFFIC · LAST 24H"
                refs.trafficChart?.setColors(0xFF00FF41.toInt(), 0x3300FF41)
                refs.trafficChart?.setStyle(MiniLineChart.Style.LINE)
                VH(v)
            }
            else -> {
                val v = inflater.inflate(R.layout.panel_chart, parent, false)
                refs.connRoot = v
                refs.connTitle = v.findViewById(R.id.tvChartTitle)
                refs.connTotal = v.findViewById(R.id.tvChartTotal)
                refs.connChart = v.findViewById(R.id.chart)
                refs.connTitle?.text = "CONNECTIONS · LAST 24H"
                refs.connChart?.setColors(0xFFFFCC66.toInt(), 0x33FFCC66.toInt())
                refs.connChart?.setStyle(MiniLineChart.Style.BARS)
                VH(v)
            }
        }
    }

    override fun getItemCount(): Int = 3

    override fun onBindViewHolder(holder: VH, position: Int) { /* no-op; refs are populated on create */ }
}
