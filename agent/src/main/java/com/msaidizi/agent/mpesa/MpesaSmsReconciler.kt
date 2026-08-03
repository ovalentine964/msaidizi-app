package com.msaidizi.agent.mpesa

import com.msaidizi.core.database.*
import com.msaidizi.core.model.*
import com.msaidizi.agent.events.TransactionEventBus
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MpesaSmsReconciler — Auto-reconcile parsed M-Pesa SMS with business records.
 *
 * This is the #1 requested feature across ALL worker segments in Kenya.
 * Workers currently:
 *   1. Receive M-Pesa SMS on their phone
 *   2. Manually open Msaidizi app
 *   3. Manually enter the sale/expense/purchase
 *
 * This eliminates steps 2-3 by:
 *   1. Listening for incoming M-Pesa SMS (via SmsBroadcastReceiver)
 *   2. Parsing with MpesaSmsParser
 *   3. Auto-recording as sale/expense/purchase
 *   4. Matching to existing records (debt payments, expected deliveries)
 *   5. Triggering TransactionEventBus for follow-up workflows
 *
 * Matching strategy:
 *   - Receipt number: exact match to existing records
 *   - Amount + date: fuzzy match for expected payments
 *   - Customer phone: match to known customers
 */
@Singleton
class MpesaSmsReconciler @Inject constructor(
    private val saleDao: SaleDao,
    private val expenseDao: ExpenseDao,
    private val customerDao: CustomerDao,
    private val debtDao: DebtDao,
    private val debtRepaymentDao: DebtRepaymentDao,
    private val productDao: ProductDao,
    private val eventBus: TransactionEventBus,
    private val mpesaDao: MpesaTransactionDao
) {

    /**
     * Process a parsed M-Pesa transaction:
     * 1. Store the raw SMS record
     * 2. Auto-record as sale/expense/purchase
     * 3. Match to existing records
     * 4. Trigger event bus for follow-up workflows
     *
     * @return ReconciliationResult with what was done
     */
    suspend fun reconcile(transaction: MpesaTransaction): ReconciliationResult {
        Timber.i("MpesaSmsReconciler: Processing ${transaction.type} — KES ${transaction.amount} (${transaction.receipt})")

        // Step 1: Store raw SMS record
        val recordId = mpesaDao.insert(
            MpesaTransactionEntity(
                receipt = transaction.receipt,
                type = transaction.type.name,
                amount = transaction.amount,
                counterparty = transaction.counterparty,
                phone = transaction.phone,
                transactionDate = transaction.date,
                balance = transaction.balance,
                category = transaction.category.name,
                confidence = transaction.confidence,
                rawSms = transaction.rawSms,
                isReconciled = false
            )
        )

        // Step 2: Auto-record based on transaction type
        val result = when (transaction.category) {
            TransactionCategory.SALE -> recordAsSale(transaction)
            TransactionCategory.EXPENSE -> recordAsExpense(transaction)
            TransactionCategory.PURCHASE -> recordAsPurchase(transaction)
            TransactionCategory.CASH_DEPOSIT, TransactionCategory.CASH_WITHDRAWAL -> {
                // These don't need business recording
                ReconciliationResult(
                    success = true,
                    action = "recorded",
                    message = "${transaction.type.name} of KES ${transaction.amount} recorded",
                    recordId = recordId
                )
            }
            TransactionCategory.LOAN -> {
                ReconciliationResult(
                    success = true,
                    action = "recorded",
                    message = "Fuliza/loan of KES ${transaction.amount} recorded",
                    recordId = recordId
                )
            }
            TransactionCategory.REVERSAL -> {
                ReconciliationResult(
                    success = true,
                    action = "recorded",
                    message = "Reversal of KES ${transaction.amount} recorded",
                    recordId = recordId
                )
            }
            TransactionCategory.EXPENSE_AIRTIME -> {
                ReconciliationResult(
                    success = true,
                    action = "recorded",
                    message = "Airtime purchase of KES ${transaction.amount} recorded",
                    recordId = recordId
                )
            }
            TransactionCategory.UNKNOWN -> {
                ReconciliationResult(
                    success = true,
                    action = "recorded",
                    message = "Unknown transaction of KES ${transaction.amount} — please categorize",
                    recordId = recordId
                )
            }
        }

        // Step 3: Try to match to existing records
        val matched = matchToExistingRecords(transaction)
        if (matched) {
            mpesaDao.markReconciled(recordId)
        }

        // Step 4: Update confidence on the record
        mpesaDao.updateConfidence(recordId, transaction.confidence)

        Timber.i("MpesaSmsReconciler: ${result.message}")
        return result
    }

    /**
     * Batch reconcile multiple SMS messages.
     * Useful for initial import of historical M-Pesa SMS.
     */
    suspend fun reconcileAll(transactions: List<MpesaTransaction>): List<ReconciliationResult> {
        return transactions.map { reconcile(it) }
    }

    // ═══════════════════════════════════════════════════════════
    //  AUTO-RECORDING
    // ═══════════════════════════════════════════════════════════

    /**
     * Record an incoming payment as a sale.
     * Also checks if this is a debt repayment.
     */
    private suspend fun recordAsSale(tx: MpesaTransaction): ReconciliationResult {
        // Check if this might be a debt repayment
        val matchedDebt = matchToDebt(tx)
        if (matchedDebt != null) {
            // Record as debt repayment, not a new sale
            debtRepaymentDao.insert(
                DebtRepaymentEntity(
                    debtId = matchedDebt.id,
                    amount = tx.amount,
                    paymentMethod = "mpesa",
                    notes = "Auto-reconciled from M-Pesa SMS: ${tx.receipt}"
                )
            )
            debtDao.updateBalance(matchedDebt.id, matchedDebt.outstandingBalance - tx.amount)

            eventBus.onPaymentReceived(
                amount = tx.amount,
                customerName = matchedDebt.customerName,
                paymentMethod = "mpesa",
                debtId = matchedDebt.id
            )

            return ReconciliationResult(
                success = true,
                action = "debt_payment",
                message = "KES ${tx.amount} recorded as debt payment for ${matchedDebt.customerName}",
                recordId = matchedDebt.id
            )
        }

        // Record as a new sale
        // Try to find the product by counterparty name patterns
        val product = guessProduct(tx.counterparty)

        val saleId = saleDao.insert(
            SaleEntity(
                productId = product?.id ?: 0L,
                productName = product?.name ?: tx.counterparty.ifEmpty { "M-Pesa Sale" },
                quantity = 1.0,
                unitPrice = tx.amount,
                totalPrice = tx.amount,
                paymentMethod = "mpesa",
                customerName = tx.counterparty.ifEmpty { null },
                timestamp = System.currentTimeMillis(),
                notes = "Auto-recorded from M-Pesa: ${tx.receipt}"
            )
        )

        // Trigger event bus
        eventBus.onSaleRecorded(
            sale = SaleEntity(
                id = saleId,
                productId = product?.id ?: 0L,
                productName = product?.name ?: tx.counterparty,
                quantity = 1.0,
                unitPrice = tx.amount,
                totalPrice = tx.amount,
                paymentMethod = "mpesa"
            ),
            product = product
        )

        return ReconciliationResult(
            success = true,
            action = "sale_recorded",
            message = "Sale of KES ${tx.amount} auto-recorded from M-Pesa: ${tx.receipt}",
            recordId = saleId
        )
    }

    /**
     * Record an outgoing payment as an expense.
     */
    private suspend fun recordAsExpense(tx: MpesaTransaction): ReconciliationResult {
        val detailedCategory = MpesaSmsParser.suggestDetailedCategory(tx.counterparty, tx.type)
            ?: tx.category.name.lowercase()

        val expenseId = expenseDao.insert(
            ExpenseEntity(
                category = detailedCategory,
                description = "M-Pesa payment to ${tx.counterparty}",
                amount = tx.amount,
                timestamp = System.currentTimeMillis(),
                isRecurring = false
            )
        )

        eventBus.onExpenseRecorded(
            ExpenseEntity(
                id = expenseId,
                category = detailedCategory,
                description = "M-Pesa payment to ${tx.counterparty}",
                amount = tx.amount
            )
        )

        return ReconciliationResult(
            success = true,
            action = "expense_recorded",
            message = "Expense of KES ${tx.amount} to ${tx.counterparty} auto-recorded",
            recordId = expenseId
        )
    }

    /**
     * Record a buy-goods payment as a purchase.
     */
    private suspend fun recordAsPurchase(tx: MpesaTransaction): ReconciliationResult {
        val purchaseId = expenseDao.insert(
            ExpenseEntity(
                category = "stock",
                description = "Stock purchase from ${tx.counterparty}",
                amount = tx.amount,
                timestamp = System.currentTimeMillis(),
                isRecurring = false
            )
        )

        eventBus.onPurchaseRecorded(
            productName = tx.counterparty,
            quantity = 1.0,
            cost = tx.amount,
            productId = null
        )

        return ReconciliationResult(
            success = true,
            action = "purchase_recorded",
            message = "Purchase of KES ${tx.amount} from ${tx.counterparty} auto-recorded",
            recordId = purchaseId
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  MATCHING
    // ═══════════════════════════════════════════════════════════

    /**
     * Try to match transaction to an existing debt (customer repayment).
     * Match by: phone number > customer name > amount pattern
     */
    private suspend fun matchToDebt(tx: MpesaTransaction): DebtEntity? {
        if (tx.type != MpesaTransactionType.RECEIVED) return null

        // Try phone match first
        if (tx.phone.isNotEmpty()) {
            val customer = customerDao.findByPhone(tx.phone)
            if (customer != null) {
                val debts = debtDao.getActiveByCustomer(customer.name).first()
                val matchingDebt = debts.firstOrNull { it.outstandingBalance > 0 }
                if (matchingDebt != null) return matchingDebt
            }
        }

        // Try name match
        if (tx.counterparty.isNotEmpty()) {
            val debts = debtDao.getActiveByCustomer(tx.counterparty).first()
            val matchingDebt = debts.firstOrNull { it.outstandingBalance > 0 }
            if (matchingDebt != null) return matchingDebt
        }

        return null
    }

    /**
     * Try to match transaction to existing records by receipt number.
     */
    private suspend fun matchToExistingRecords(tx: MpesaTransaction): Boolean {
        // Check if we already have this receipt
        val existing = mpesaDao.findByReceipt(tx.receipt)
        return existing != null && existing.isReconciled
    }

    /**
     * Guess product from counterparty name.
     * Common patterns: "JOHN'S SHOP", "WHOLESALE KAMAU", etc.
     */
    private suspend fun guessProduct(counterparty: String): ProductEntity? {
        if (counterparty.isEmpty()) return null

        // Try exact product name match
        val products = productDao.getAll()
        return products.firstOrNull { product ->
            counterparty.lowercase().contains(product.name.lowercase())
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  RESULT TYPE
// ═══════════════════════════════════════════════════════════

data class ReconciliationResult(
    val success: Boolean,
    val action: String,
    val message: String,
    val recordId: Long
)
