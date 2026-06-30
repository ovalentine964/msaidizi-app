package com.msaidizi.app.ui.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.msaidizi.app.R
import com.msaidizi.app.core.model.Trend
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Dashboard screen — business analytics and charts.
 * Shows sales trends, top items, and business health.
 */
@AndroidEntryPoint
class DashboardFragment : Fragment() {

    private val viewModel: DashboardViewModel by viewModels()

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var cashFlowValue: TextView
    private lateinit var trendText: TextView
    private lateinit var marginText: TextView
    private lateinit var velocityText: TextView
    private lateinit var salesChart: BarChart
    private lateinit var topItemsContainer: ViewGroup

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_dashboard, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupViews(view)
        observeState()
    }

    private fun setupViews(view: View) {
        swipeRefresh = view.findViewById(R.id.swipe_refresh)
        cashFlowValue = view.findViewById(R.id.cash_flow_value)
        trendText = view.findViewById(R.id.trend_text)
        marginText = view.findViewById(R.id.margin_text)
        velocityText = view.findViewById(R.id.velocity_text)
        salesChart = view.findViewById(R.id.sales_chart)
        topItemsContainer = view.findViewById(R.id.top_items_container)

        swipeRefresh.setOnRefreshListener {
            viewModel.refresh()
        }

        setupChart()
    }

    private fun setupChart() {
        salesChart.apply {
            description.isEnabled = false
            setTouchEnabled(true)
            setDrawGridBackground(false)
            legend.isEnabled = false

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                granularity = 1f
            }

            axisLeft.apply {
                setDrawGridLines(true)
                axisMinimum = 0f
            }

            axisRight.isEnabled = false
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    updateUI(state)
                }
            }
        }
    }

    private fun updateUI(state: DashboardUiState) {
        swipeRefresh.isRefreshing = state.isLoading

        // Cash flow
        cashFlowValue.text = "KSh ${"%.0f".format(state.weeklyCashFlow.net)}"
        val cashFlowColor = if (state.weeklyCashFlow.net >= 0) {
            resources.getColor(R.color.profit_positive, null)
        } else {
            resources.getColor(R.color.profit_negative, null)
        }
        cashFlowValue.setTextColor(cashFlowColor)

        // Trend
        val trendIcon = when (state.salesTrend) {
            Trend.RISING -> "📈"
            Trend.FALLING -> "📉"
            Trend.STABLE -> "➡️"
            Trend.INSUFFICIENT_DATA -> "📊"
        }
        trendText.text = "$trendIcon ${state.salesTrend.name}"

        // Margin
        marginText.text = "${state.profitMargin.toInt()}%"

        // Velocity
        velocityText.text = "KSh ${"%.0f".format(state.salesVelocity)}/day"

        // Sales chart
        updateChart(state.dailySalesData)

        // Top items
        updateTopItems(state.topItems)

        // Error
        state.error?.let { error ->
            android.widget.Toast.makeText(requireContext(), error, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateChart(data: List<Pair<String, Double>>) {
        if (data.isEmpty()) return

        val entries = data.mapIndexed { index, (_, value) ->
            BarEntry(index.toFloat(), value.toFloat())
        }

        val dataSet = BarDataSet(entries, "Daily Sales").apply {
            color = resources.getColor(R.color.primary, null)
            setDrawValues(false)
        }

        salesChart.data = BarData(dataSet)
        salesChart.xAxis.valueFormatter = com.github.mikephil.charting.formatter.IndexAxisValueFormatter(
            data.map { it.first.takeLast(5) }  // Show MM-DD
        )
        salesChart.invalidate()
    }

    private fun updateTopItems(items: List<com.msaidizi.app.core.model.ItemRanking>) {
        topItemsContainer.removeAllViews()
        for ((index, item) in items.withIndex()) {
            val itemView = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_top_seller, topItemsContainer, false)
            itemView.findViewById<TextView>(R.id.item_rank).text = "#${index + 1}"
            itemView.findViewById<TextView>(R.id.item_name).text = item.item
            itemView.findViewById<TextView>(R.id.item_revenue).text =
                "KSh ${"%.0f".format(item.totalRevenue)}"
            topItemsContainer.addView(itemView)
        }
    }
}
