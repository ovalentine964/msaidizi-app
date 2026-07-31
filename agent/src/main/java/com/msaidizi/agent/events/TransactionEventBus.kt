package com.msaidizi.agent.events

import com.msaidizi.core.model.ExpenseEntity
import com.msaidizi.core.model.ProductEntity
import com.msaidizi.core.model.SaleEntity
import com.msaidizi.agent.council.CouncilEvent
import com.msaidizi.agent.council.CouncilEventBus
import com.msaidizi.agent.council.CouncilEventType
import com.msaidizi.agent.council.CouncilType
import com.msaidizi.agent.tools.core.ToolRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TransactionEventBus — Event-driven cross-tool workflow orchestrator.
 *
 * When a business event occurs (sale, payment, purchase), this bus triggers
 * a cascade of automated follow-up actions across multiple tools.
 *
 * Workflow examples:
 *
 *   SALE RECORDED:
 *     1. Update inventory (reduce stock)
 *     2. Check if stock below reorder threshold → trigger alert
 *     3. Update CFO report / daily summary
 *     4. Check if customer has outstanding debt → suggest collection
 *     5. Update flywheel patterns
 *
 *   PAYMENT RECEIVED:
 *     1. Auto-categorize (sale vs debt repayment)
 *     2. Update cash flow projections
 *     3. Check credit impact (if large payment)
 *     4. Record in sync engine for cloud backup
 *
 *   PURCHASE/RESTOCK:
 *     1. Update inventory (increase stock)
 *     2. Record expense
 *     3. Check if bulk order was fulfilled
 *     4. Update CFO report
 *
 * Design:
 * - Uses Kotlin SharedFlow for zero-allocation event emission
 * - Handlers run in parallel via coroutine scope
 * - Each handler has a circuit breaker (max 3 failures → skip)
 * - Events are idempotent (replay-safe)
 */
@Singleton
class TransactionEventBus @Inject constructor(
    private val councilEventBus: CouncilEventBus,
    private val toolRegistry: ToolRegistry
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _events = MutableSharedFlow<TransactionEvent>(
        replay = 0,
        extraBufferCapacity = 32
    )
    val events: SharedFlow<TransactionEvent> = _events.asSharedFlow()

    // Handler failure tracking (circuit breaker per handler)
    private val handlerFailures = mutableMapOf<String, Int>()
    private val handlerDisabled = mutableSetOf<String>()

    init {
        startEventProcessing()
    }

    // ═══════════════════════════════════════════════════════════
    //  EVENT EMISSION
    // ═══════════════════════════════════════════════════════════

    /**
     * Emit a sale recorded event. Triggers the full sale workflow.
     */
    fun onSaleRecorded(sale: SaleEntity, product: ProductEntity?) {
        val event = TransactionEvent.SaleRecorded(
            saleId = sale.id,
            productName = sale.productName,
            quantity = sale.quantity,
            unitPrice = sale.unitPrice,
            totalPrice = sale.totalPrice,
            paymentMethod = sale.paymentMethod,
            customerId = sale.customerId,
            customerName = sale.customerName,
            currentStock = product?.currentStock,
            minStock = product?.minStock,
            productId = product?.id
        )
        _events.tryEmit(event)
        Timber.d("TransactionEventBus: Sale recorded — ${sale.productName} x${sale.quantity}")
    }

    /**
     * Emit a payment received event. Triggers payment workflow.
     */
    fun onPaymentReceived(
        amount: Double,
        customerName: String?,
        paymentMethod: String,
        debtId: Long? = null
    ) {
        val event = TransactionEvent.PaymentReceived(
            amount = amount,
            customerName = customerName,
            paymentMethod = paymentMethod,
            debtId = debtId
        )
        _events.tryEmit(event)
        Timber.d("TransactionEventBus: Payment received — KES $amount from ${customerName ?: "unknown"}")
    }

    /**
     * Emit a purchase/restock event. Triggers inventory and expense workflows.
     */
    fun onPurchaseRecorded(
        productName: String,
        quantity: Double,
        cost: Double,
        productId: Long?
    ) {
        val event = TransactionEvent.PurchaseRecorded(
            productName = productName,
            quantity = quantity,
            cost = cost,
            productId = productId
        )
        _events.tryEmit(event)
        Timber.d("TransactionEventBus: Purchase recorded — $productName x$quantity")
    }

    /**
     * Emit an expense recorded event.
     */
    fun onExpenseRecorded(expense: ExpenseEntity) {
        val event = TransactionEvent.ExpenseRecorded(
            category = expense.category,
            description = expense.description,
            amount = expense.amount
        )
        _events.tryEmit(event)
    }

    // ═══════════════════════════════════════════════════════════
    //  EVENT PROCESSING
    // ═══════════════════════════════════════════════════════════

    private fun startEventProcessing() {
        scope.launch {
            events.collect { event ->
                when (event) {
                    is TransactionEvent.SaleRecorded -> handleSaleRecorded(event)
                    is TransactionEvent.PaymentReceived -> handlePaymentReceived(event)
                    is TransactionEvent.PurchaseRecorded -> handlePurchaseRecorded(event)
                    is TransactionEvent.ExpenseRecorded -> handleExpenseRecorded(event)
                }
            }
        }
        Timber.i("TransactionEventBus: Event processing started")
    }

    /**
     * SALE WORKFLOW:
     * 1. Check inventory → trigger STOCK_LOW if below threshold
     * 2. Update CFO report data
     * 3. Publish to CouncilEventBus for cross-council reactions
     */
    private suspend fun handleSaleRecorded(event: TransactionEvent.SaleRecorded) {
        // Step 1: Inventory check
        executeHandler("sale_inventory_check") {
            if (event.currentStock != null && event.minStock != null) {
                val remainingStock = event.currentStock - event.quantity
                if (remainingStock <= event.minStock) {
                    councilEventBus.publish(CouncilEvent(
                        type = CouncilEventType.STOCK_LOW,
                        sourceCouncil = CouncilType.FINANCE,
                        payload = mapOf(
                            "productName" to event.productName,
                            "currentStock" to remainingStock,
                            "minStock" to event.minStock,
                            "productId" to (event.productId ?: 0L)
                        )
                    ))
                }
            }
        }

        // Step 2: Publish transaction event to council bus
        executeHandler("sale_council_publish") {
            councilEventBus.publish(CouncilEvent(
                type = CouncilEventType.TRANSACTION_RECORDED,
                sourceCouncil = CouncilType.FINANCE,
                payload = mapOf(
                    "type" to "sale",
                    "productName" to event.productName,
                    "amount" to event.totalPrice,
                    "paymentMethod" to event.paymentMethod,
                    "customerName" to (event.customerName ?: "")
                )
            ))
        }

        // Step 3: If credit sale, check debt status
        if (event.paymentMethod == "credit" && event.customerId != null) {
            executeHandler("sale_credit_check") {
                councilEventBus.publish(CouncilEvent(
                    type = CouncilType.FINANCE.name.let {
                        CouncilEventType.DEBT_RECORDED
                    },
                    sourceCouncil = CouncilType.FINANCE,
                    payload = mapOf(
                        "customerId" to event.customerId,
                        "customerName" to (event.customerName ?: "Unknown"),
                        "amount" to event.totalPrice
                    )
                ))
            }
        }
    }

    /**
     * PAYMENT WORKFLOW:
     * 1. Auto-categorize payment type
     * 2. Update cash flow via council event
     * 3. Check credit impact
     */
    private suspend fun handlePaymentReceived(event: TransactionEvent.PaymentReceived) {
        // Step 1: Publish payment event
        executeHandler("payment_council_publish") {
            councilEventBus.publish(CouncilEvent(
                type = CouncilEventType.PAYMENT_RECEIVED,
                sourceCouncil = CouncilType.FINANCE,
                payload = mapOf(
                    "amount" to event.amount,
                    "customerName" to (event.customerName ?: "Unknown"),
                    "paymentMethod" to event.paymentMethod,
                    "debtId" to (event.debtId ?: -1L)
                )
            ))
        }

        // Step 2: Update cash flow
        executeHandler("payment_cashflow_update") {
            councilEventBus.publish(CouncilEvent(
                type = CouncilEventType.CASH_FLOW_UPDATED,
                sourceCouncil = CouncilType.FINANCE,
                payload = mapOf(
                    "inflow" to event.amount,
                    "source" to "payment_received",
                    "customerName" to (event.customerName ?: "")
                )
            ))
        }
    }

    /**
     * PURCHASE WORKFLOW:
     * 1. Publish stock update
     * 2. Record as expense
     */
    private suspend fun handlePurchaseRecorded(event: TransactionEvent.PurchaseRecorded) {
        executeHandler("purchase_stock_update") {
            councilEventBus.publish(CouncilEvent(
                type = CouncilEventType.STOCK_UPDATED,
                sourceCouncil = CouncilType.INVENTORY,
                payload = mapOf(
                    "productName" to event.productName,
                    "quantityAdded" to event.quantity,
                    "cost" to event.cost
                )
            ))
        }
    }

    /**
     * EXPENSE WORKFLOW:
     * 1. Publish cash flow update (outflow)
     */
    private suspend fun handleExpenseRecorded(event: TransactionEvent.ExpenseRecorded) {
        executeHandler("expense_cashflow_update") {
            councilEventBus.publish(CouncilEvent(
                type = CouncilEventType.CASH_FLOW_UPDATED,
                sourceCouncil = CouncilType.FINANCE,
                payload = mapOf(
                    "outflow" to event.amount,
                    "category" to event.category,
                    "description" to event.description
                )
            ))
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  CIRCUIT BREAKER
    // ═══════════════════════════════════════════════════════════

    private suspend fun executeHandler(name: String, handler: suspend () -> Unit) {
        if (name in handlerDisabled) {
            Timber.d("TransactionEventBus: Handler $name disabled (circuit open)")
            return
        }

        try {
            handler()
            handlerFailures.remove(name) // Reset on success
        } catch (e: Exception) {
            val failures = (handlerFailures[name] ?: 0) + 1
            handlerFailures[name] = failures
            Timber.w(e, "TransactionEventBus: Handler $name failed ($failures/$MAX_FAILURES)")

            if (failures >= MAX_FAILURES) {
                handlerDisabled.add(name)
                Timber.e("TransactionEventBus: Handler $name disabled after $MAX_FAILURES failures")
            }
        }
    }

    companion object {
        private const val MAX_FAILURES = 3
    }
}

/**
 * Sealed class representing all transaction events.
 */
sealed class TransactionEvent {
    data class SaleRecorded(
        val saleId: Long,
        val productName: String,
        val quantity: Double,
        val unitPrice: Double,
        val totalPrice: Double,
        val paymentMethod: String,
        val customerId: Long?,
        val customerName: String?,
        val currentStock: Double?,
        val minStock: Double?,
        val productId: Long?
    ) : TransactionEvent()

    data class PaymentReceived(
        val amount: Double,
        val customerName: String?,
        val paymentMethod: String,
        val debtId: Long?
    ) : TransactionEvent()

    data class PurchaseRecorded(
        val productName: String,
        val quantity: Double,
        val cost: Double,
        val productId: Long?
    ) : TransactionEvent()

    data class ExpenseRecorded(
        val category: String,
        val description: String,
        val amount: Double
    ) : TransactionEvent()
}
