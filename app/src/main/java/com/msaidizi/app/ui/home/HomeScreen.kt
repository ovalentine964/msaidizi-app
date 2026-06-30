package com.msaidizi.app.ui.home

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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.card.MaterialCardView
import com.msaidizi.app.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Home screen — shows daily business overview.
 * Displays sales, profit, restock alerts, and top items.
 */
@AndroidEntryPoint
class HomeFragment : Fragment() {

    private val viewModel: HomeViewModel by viewModels()

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var salesValue: TextView
    private lateinit var profitValue: TextView
    private lateinit var transactionCount: TextView
    private lateinit var restockAlertsContainer: ViewGroup
    private lateinit var topItemsContainer: ViewGroup

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupViews(view)
        observeState()
    }

    private fun setupViews(view: View) {
        swipeRefresh = view.findViewById(R.id.swipe_refresh)
        salesValue = view.findViewById(R.id.sales_value)
        profitValue = view.findViewById(R.id.profit_value)
        transactionCount = view.findViewById(R.id.transaction_count)
        restockAlertsContainer = view.findViewById(R.id.restock_alerts_container)
        topItemsContainer = view.findViewById(R.id.top_items_container)

        swipeRefresh.setOnRefreshListener {
            viewModel.refresh()
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

    private fun updateUI(state: HomeUiState) {
        swipeRefresh.isRefreshing = state.isLoading

        salesValue.text = "KSh ${"%.0f".format(state.dailySales)}"
        profitValue.text = "KSh ${"%.0f".format(state.dailyProfit)}"
        transactionCount.text = "${state.transactionCount}"

        // Update profit color
        val profitColor = if (state.dailyProfit >= 0) {
            resources.getColor(R.color.profit_positive, null)
        } else {
            resources.getColor(R.color.profit_negative, null)
        }
        profitValue.setTextColor(profitColor)

        // Update restock alerts
        restockAlertsContainer.removeAllViews()
        if (state.restockAlerts.isEmpty()) {
            restockAlertsContainer.visibility = View.GONE
        } else {
            restockAlertsContainer.visibility = View.VISIBLE
            for (alert in state.restockAlerts.take(3)) {
                val alertView = LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_restock_alert, restockAlertsContainer, false)
                alertView.findViewById<TextView>(R.id.alert_item).text = alert.item
                alertView.findViewById<TextView>(R.id.alert_stock).text =
                    "${alert.currentStock} remaining"
                restockAlertsContainer.addView(alertView)
            }
        }

        // Update top items
        topItemsContainer.removeAllViews()
        for (item in state.topItems) {
            val itemView = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_top_seller, topItemsContainer, false)
            itemView.findViewById<TextView>(R.id.item_name).text = item.item
            itemView.findViewById<TextView>(R.id.item_revenue).text =
                "KSh ${"%.0f".format(item.totalRevenue)}"
            topItemsContainer.addView(itemView)
        }

        // Show error if any
        state.error?.let { error ->
            android.widget.Toast.makeText(requireContext(), error, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
