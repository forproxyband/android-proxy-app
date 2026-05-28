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
// Page 3: 24h IP-rotation mini chart (stacked bars: manual + auto)
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
        // Wi-Fi return indicator. Stays GONE unless the proxy's
        // wifi_return relay is active. See MainActivity.refresh() for the
        // text/visibility logic.
        var tvUplinkVia: TextView? = null
        // Two-IP detail block (cellular exit + Wi-Fi uplink). Only visible
        // after a successful split-routing self-test (wifi_info.json
        // present with test_result=SUCCESS).
        var tvUplinkDetail: TextView? = null
        // Session-lifetime byte counters split by interface (Wi-Fi via
        // relay vs cellular via target dials). Hidden when relay is off.
        var tvSessionTraffic: TextView? = null

        var trafficRoot: View? = null
        var trafficTitle: TextView? = null
        var trafficTotal: TextView? = null
        var trafficChart: MiniLineChart? = null

        var connRoot: View? = null
        var connTitle: TextView? = null
        var connTotal: TextView? = null
        var connChart: MiniLineChart? = null

        var rotRoot: View? = null
        var rotTitle: TextView? = null
        var rotTotal: TextView? = null
        var rotChart: MiniLineChart? = null
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
                refs.tvUplinkVia = v.findViewById(R.id.tvUplinkVia)
                refs.tvUplinkDetail = v.findViewById(R.id.tvUplinkDetail)
                refs.tvSessionTraffic = v.findViewById(R.id.tvSessionTraffic)
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
            2 -> {
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
            else -> {
                val v = inflater.inflate(R.layout.panel_chart, parent, false)
                refs.rotRoot = v
                refs.rotTitle = v.findViewById(R.id.tvChartTitle)
                refs.rotTotal = v.findViewById(R.id.tvChartTotal)
                refs.rotChart = v.findViewById(R.id.chart)
                refs.rotTitle?.text = "ROTATIONS · LAST 24H · ■ manual  ■ auto"
                // Bottom = manual (cyan), top stacked = auto (magenta). Two
                // visibly distinct hues so a glance at the bar shows the mix.
                refs.rotChart?.setColors(0xFF66E0FF.toInt(), 0x3366E0FF.toInt())
                refs.rotChart?.setStackedTopColor(0xFFFF66CC.toInt())
                refs.rotChart?.setStyle(MiniLineChart.Style.STACKED_BARS)
                refs.rotChart?.setEmptyText("no rotations yet")
                VH(v)
            }
        }
    }

    override fun getItemCount(): Int = 4

    override fun onBindViewHolder(holder: VH, position: Int) { /* no-op; refs are populated on create */ }
}
