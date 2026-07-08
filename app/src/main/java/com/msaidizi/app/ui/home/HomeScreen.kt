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
import com.msaidizi.app.ui.accessibility.AccessibilityTtsHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Home screen — shows daily business overview.
 * Displays sales, profit, restock alerts, and top items.
 *
 * ACCESSIBILITY:
 * - TTS readout of daily summary on load and on button press
 * - All text elements have content descriptions for screen readers
 * - Errors are spoken, not just displayed as Toast
 * - Minimum touch targets 48dp on all interactive elements
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

    // ── Accessibility ──
    private var ttsHelper: AccessibilityTtsHelper? = null
    private lateinit var listenButton: com.google.android.material.button.MaterialButton

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ttsHelper = AccessibilityTtsHelper(requireContext())
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

        // ── Accessibility: Content descriptions ──
        salesValue.contentDescription = "Mauzo ya leo"
        profitValue.contentDescription = "Faida ya leo"
        transactionCount.contentDescription = "Idadi ya miamala ya leo"

        // ── Accessibility: Listen button for audio readout ──
        listenButton = view.findViewById(R.id.listen_button)
            ?: com.google.android.material.button.MaterialButton(requireContext()).apply {
                text = "🔊 Sikiliza Muhtasari wa Leo"
                textSize = 18f
                val minTouch = (48 * resources.displayMetrics.density).toInt()
                minimumHeight = minTouch
                minimumWidth = minTouch
                contentDescription = "Sikiliza muhtasari wa biashara ya leo"
            }
        listenButton.setOnClickListener { speakDailySummary() }

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
                // Accessibility
                alertView.contentDescription =
                    "Onyo: ${alert.item} imesalia ${alert.currentStock}"
                restockAlertsContainer.addView(alertView)
            }
        }

        // Update top items
        topItemsContainer.removeAllViews()
        for ((index, item) in state.topItems.withIndex()) {
            val itemView = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_top_seller, topItemsContainer, false)
            itemView.findViewById<TextView>(R.id.item_name).text = item.item
            itemView.findViewById<TextView>(R.id.item_revenue).text =
                "KSh ${"%.0f".format(item.totalRevenue)}"
            // Accessibility
            itemView.contentDescription =
                "Bidhaa ${index + 1}: ${item.item}, mauzo KSh ${"%.0f".format(item.totalRevenue)}"
            topItemsContainer.addView(itemView)
        }

        // Error — speak it aloud, not just display
        state.error?.let { error ->
            android.widget.Toast.makeText(requireContext(), error, android.widget.Toast.LENGTH_SHORT).show()
            ttsHelper?.speakError(error)
        }
    }

    /**
     * Speak the daily summary via TTS for non-literate/visually impaired users.
     */
    private fun speakDailySummary() {
        val state = viewModel.uiState.value
        val topItemNames = state.topItems.map { it.item }
        val restockNames = state.restockAlerts.map { "${it.item} imesalia ${it.currentStock}" }

        ttsHelper?.speakDashboardSummary(
            sales = state.dailySales,
            profit = state.dailyProfit,
            transactionCount = state.transactionCount,
            restockAlerts = restockNames,
            topItems = topItemNames
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        ttsHelper?.release()
        ttsHelper = null
    }
}
