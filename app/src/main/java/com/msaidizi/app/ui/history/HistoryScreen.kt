package com.msaidizi.app.ui.history

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.msaidizi.app.R
import com.msaidizi.app.core.model.Transaction
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * History screen — view past transactions by date.
 */
@AndroidEntryPoint
class HistoryFragment : Fragment() {

    private val viewModel: HistoryViewModel by viewModels()

    private lateinit var dateText: TextView
    private lateinit var prevButton: ImageButton
    private lateinit var nextButton: ImageButton
    private lateinit var todayButton: ImageButton
    private lateinit var salesTotal: TextView
    private lateinit var purchasesTotal: TextView
    private lateinit var recyclerView: RecyclerView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_history, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupViews(view)
        observeState()
    }

    private fun setupViews(view: View) {
        dateText = view.findViewById(R.id.date_text)
        prevButton = view.findViewById(R.id.prev_button)
        nextButton = view.findViewById(R.id.next_button)
        todayButton = view.findViewById(R.id.today_button)
        salesTotal = view.findViewById(R.id.sales_total)
        purchasesTotal = view.findViewById(R.id.purchases_total)
        recyclerView = view.findViewById(R.id.transactions_recycler)

        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        prevButton.setOnClickListener { viewModel.previousDay() }
        nextButton.setOnClickListener { viewModel.nextDay() }
        todayButton.setOnClickListener { viewModel.goToToday() }
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

    private fun updateUI(state: HistoryUiState) {
        dateText.text = state.date
        salesTotal.text = "Sales: KSh ${"%.0f".format(state.totalSales)}"
        purchasesTotal.text = "Purchases: KSh ${"%.0f".format(state.totalPurchases)}"

        recyclerView.adapter = TransactionAdapter(state.transactions)
    }
}

/**
 * RecyclerView adapter for transactions.
 */
class TransactionAdapter(
    private val transactions: List<Transaction>
) : RecyclerView.Adapter<TransactionAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val typeIcon: TextView = view.findViewById(R.id.type_icon)
        val itemName: TextView = view.findViewById(R.id.item_name)
        val amount: TextView = view.findViewById(R.id.amount)
        val details: TextView = view.findViewById(R.id.details)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_transaction, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val tx = transactions[position]

        holder.typeIcon.text = when (tx.type.name) {
            "SALE" -> "💰"
            "PURCHASE" -> "🛒"
            "EXPENSE" -> "💸"
            else -> "📝"
        }

        holder.itemName.text = tx.item
        holder.amount.text = "KSh ${"%.0f".format(tx.totalAmount)}"
        holder.details.text = if (tx.quantity > 1) {
            "${tx.quantity.toInt()} × KSh ${"%.0f".format(tx.unitPrice)}"
        } else {
            ""
        }
    }

    override fun getItemCount() = transactions.size
}
