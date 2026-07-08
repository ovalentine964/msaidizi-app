package com.msaidizi.app.ui.components

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import com.msaidizi.app.R
import com.msaidizi.app.core.model.Transaction
import com.msaidizi.app.core.model.TransactionType
import com.msaidizi.app.ui.theme.AppTypography

/**
 * Transaction card component.
 * Displays a single transaction with type icon, item, and amount.
 *
 * ACCESSIBILITY:
 * - Minimum touch target 48dp
 * - Content description for screen readers (type + item + amount)
 * - Minimum text size 16sp for elderly readability
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

        // ACCESSIBILITY: Minimum touch target
        val minTouch = AppTypography.minTouchTarget(context)
        minimumHeight = minTouch

        // ACCESSIBILITY: Minimum text sizes
        itemName.textSize = AppTypography.MIN_TEXT_SIZE_SP
        amount.textSize = AppTypography.MIN_TEXT_SIZE_SP
        details.textSize = 14f.coerceAtLeast(AppTypography.MIN_TEXT_SIZE_SP)
    }

    /**
     * Bind transaction data to the card.
     * Sets content description for screen reader accessibility.
     */
    fun bind(transaction: Transaction) {
        typeIcon.text = when (transaction.type) {
            TransactionType.SALE -> "💰"
            TransactionType.PURCHASE -> "🛒"
            TransactionType.EXPENSE -> "💸"
            TransactionType.OTHER -> "📝"
            TransactionType.WITHDRAWAL -> "🏧"
            TransactionType.DEPOSIT -> "💵"
            TransactionType.FEE -> "🏷️"
            TransactionType.REFUND -> "↩️"
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
            TransactionType.WITHDRAWAL -> context.getColor(R.color.profit_negative)
            TransactionType.DEPOSIT -> context.getColor(R.color.profit_positive)
            TransactionType.FEE -> context.getColor(R.color.profit_negative)
            TransactionType.REFUND -> context.getColor(R.color.profit_positive)
        }
        amount.setTextColor(amountColor)

        // ACCESSIBILITY: Content description for screen readers
        val typeLabel = when (transaction.type) {
            TransactionType.SALE -> "Mauzo"
            TransactionType.PURCHASE -> "Manunuzi"
            TransactionType.EXPENSE -> "Gharama"
            TransactionType.OTHER -> "Nyingine"
            TransactionType.WITHDRAWAL -> "Kutoa"
            TransactionType.DEPOSIT -> "Kuingiza"
            TransactionType.FEE -> "Ada"
            TransactionType.REFUND -> "Rudishwa"
        }
        contentDescription = "$typeLabel: ${transaction.item}, KSh ${"%.0f".format(transaction.totalAmount)}, saa $time"
    }
}
