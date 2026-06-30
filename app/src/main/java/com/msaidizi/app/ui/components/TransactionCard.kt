package com.msaidizi.app.ui.components

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import com.msaidizi.app.R
import com.msaidizi.app.core.model.Transaction
import com.msaidizi.app.core.model.TransactionType

/**
 * Transaction card component.
 * Displays a single transaction with type icon, item, and amount.
 */
class TransactionCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val typeIcon: TextView
    private val itemName: TextView
    private val amount: TextView
    private val details: TextView
    private val timestamp: TextView

    init {
        LayoutInflater.from(context).inflate(R.layout.card_transaction, this, true)
        typeIcon = findViewById(R.id.type_icon)
        itemName = findViewById(R.id.item_name)
        amount = findViewById(R.id.amount)
        details = findViewById(R.id.details)
        timestamp = findViewById(R.id.timestamp)
    }

    /**
     * Bind transaction data to the card.
     */
    fun bind(transaction: Transaction) {
        typeIcon.text = when (transaction.type) {
            TransactionType.SALE -> "💰"
            TransactionType.PURCHASE -> "🛒"
            TransactionType.EXPENSE -> "💸"
            TransactionType.OTHER -> "📝"
        }

        itemName.text = transaction.item
        amount.text = "KSh ${"%.0f".format(transaction.totalAmount)}"

        details.text = if (transaction.quantity > 1) {
            "${transaction.quantity.toInt()} × KSh ${"%.0f".format(transaction.unitPrice)}"
        } else {
            transaction.category
        }

        // Format timestamp
        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(transaction.createdAt * 1000))
        timestamp.text = time

        // Color amount based on type
        val amountColor = when (transaction.type) {
            TransactionType.SALE -> context.getColor(R.color.profit_positive)
            TransactionType.PURCHASE -> context.getColor(R.color.profit_negative)
            TransactionType.EXPENSE -> context.getColor(R.color.profit_negative)
            TransactionType.OTHER -> context.getColor(R.color.text_primary)
        }
        amount.setTextColor(amountColor)
    }
}
