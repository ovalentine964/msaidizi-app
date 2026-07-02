package com.msaidizi.app.ui.flow

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
import com.google.android.material.tabs.TabLayout
import com.msaidizi.app.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Business Flow screen — like M-Pesa but for business.
 *
 * Shows the worker how money flows through their business:
 * Revenue → Expenses → Profit → Savings → Growth
 *
 * Tab navigation:
 * - Today: Current day flow
 * - Week: Weekly trend
 * - Month: Monthly overview
 * - Year: Annual summary
 *
 * This is the core UX that makes workers understand their business.
 * M-Pesa shows cash flow. Msaidizi shows business flow.
 */
@AndroidEntryPoint
class BusinessFlowFragment : Fragment() {

    private val viewModel: BusinessFlowViewModel by viewModels()

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var periodTabs: TabLayout
    private lateinit var flowView: BusinessFlowView
    private lateinit var emptyState: View
    private lateinit var errorText: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_business_flow, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupViews(view)
        setupTabs()
        observeState()
    }

    private fun setupViews(view: View) {
        swipeRefresh = view.findViewById(R.id.swipe_refresh)
        periodTabs = view.findViewById(R.id.period_tabs)
        flowView = view.findViewById(R.id.business_flow_view)
        emptyState = view.findViewById(R.id.empty_state)
        errorText = view.findViewById(R.id.error_text)

        swipeRefresh.setOnRefreshListener {
            viewModel.refresh()
        }
    }

    private fun setupTabs() {
        FlowPeriod.entries.forEach { period ->
            periodTabs.addTab(
                periodTabs.newTab().setText(period.displayNameSw)
            )
        }

        periodTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val period = FlowPeriod.fromOrdinal(tab.position)
                viewModel.switchPeriod(period)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
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

    private fun updateUI(state: BusinessFlowUiState) {
        swipeRefresh.isRefreshing = state.isLoading

        // Update tab selection
        val tabIndex = state.currentPeriod.ordinal
        if (periodTabs.selectedTabPosition != tabIndex) {
            periodTabs.getTabAt(tabIndex)?.select()
        }

        // Show/hide content
        if (state.flowData != null && !state.isLoading) {
            flowView.visibility = View.VISIBLE
            emptyState.visibility = View.GONE
            errorText.visibility = View.GONE

            // Render based on period
            when (state.currentPeriod) {
                FlowPeriod.TODAY -> flowView.renderTodayFlow(state.flowData)
                FlowPeriod.WEEK -> flowView.renderWeeklyFlow(state.flowData)
                FlowPeriod.MONTH -> flowView.renderMonthlyFlow(state.flowData)
                FlowPeriod.YEAR -> flowView.renderYearlyFlow(state.flowData)
            }
        } else if (!state.isLoading && state.flowData == null) {
            flowView.visibility = View.GONE
            emptyState.visibility = View.VISIBLE
            errorText.visibility = View.GONE
        }

        // Error handling
        state.error?.let { error ->
            errorText.text = error
            errorText.visibility = View.VISIBLE
        } ?: run {
            errorText.visibility = View.GONE
        }
    }
}
