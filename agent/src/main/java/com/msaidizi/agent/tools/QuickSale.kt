package com.msaidizi.agent.tools

import com.msaidizi.core.database.ProductDao
import com.msaidizi.core.database.SaleDao
import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * QuickSale — One-tap sale recording for informal workers.
 *
 * Design philosophy: amount → done. No voice, no forms, no friction.
 * Targets mama mbogas, small traders, and market vendors who lose
 * 25–55 minutes/day on record-keeping alone.
 *
 * Features:
 * - One-tap sale: just enter amount, everything else has defaults
 * - Smart defaults: product from last sale, payment = M-Pesa
 * - Time-based auto-categorization (morning=vegetables, evening=cooked food)
 * - Batch mode: record multiple sales in rapid sequence
 * - Undo: reverse the last quick sale
 * - Summary: today's sales at a glance
 *
 * Integrates with [TransactionRecorder] for actual DB persistence.
 */
@Singleton
class QuickSale @Inject constructor(
    private val transactionRecorder: TransactionRecorder,
    private val saleDao: SaleDao,
    private val productDao: ProductDao,
    private val gson: Gson
) : Tool {

    override val name = "quick_sale"
    override val description = """One-tap sale recording. Just say the amount and it's done. 
        |Actions: quick_sale (default, record one sale), batch (multiple sales), 
        |undo (reverse last sale), summary (today's totals).""".trimMargin()

    override val argsSchema = argSchema {
        enum(
            "action",
            "Action: quick_sale (default), batch, undo, summary",
            listOf("quick_sale", "batch", "undo", "summary"),
            required = false,
            default = "quick_sale"
        )
        number("amount", "Sale amount in KES", required = false)
        string("product", "Product name (auto-detected from last sale or time of day)", required = false)
        enum(
            "payment_method",
            "Payment method (default: mpesa)",
            listOf("mpesa", "cash"),
            required = false,
            default = "mpesa"
        )
        // Batch mode: comma-separated amounts e.g. "150,200,350"
        string("amounts", "Comma-separated amounts for batch mode (e.g. '150,200,350')", required = false)
    }

    // ── In-memory state for undo (last sale recorded this session) ──
    private var lastSaleId: Long? = null
    private var lastSaleDescription: String? = null

    // ── Session batch accumulator ──
    private val sessionSales = mutableListOf<BatchEntry>()

    data class BatchEntry(
        val saleId: Long,
        val product: String,
        val amount: Double,
        val paymentMethod: String,
        val timestamp: Long
    )

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"]?.lowercase() ?: "quick_sale"
        return when (action) {
            "quick_sale" -> handleQuickSale(params)
            "batch" -> handleBatch(params)
            "undo" -> handleUndo()
            "summary" -> handleSummary()
            else -> ToolResult.error(name, "Unknown action: $action. Use: quick_sale, batch, undo, summary")
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // QUICK SALE — The core one-tap experience
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private suspend fun handleQuickSale(params: Map<String, String>): ToolResult {
        val amount = params["amount"]?.toDoubleOrNull()
            ?: return ToolResult.error(name, "Amount is required. Example: quick_sale amount=200")

        // Validate
        val validation = transactionRecorder.validateAmount(amount)
        if (!validation.valid) {
            return ToolResult.error(name, validation.message, "INVALID_AMOUNT")
        }

        // Resolve product: explicit → last sale → time-of-day default
        val product = params["product"]
            ?: getLastSaleProduct()
            ?: timeOfDayDefault()

        // Resolve payment method: explicit → M-Pesa (most common in Kenya)
        val paymentMethod = params["payment_method"] ?: "mpesa"

        // Delegate to TransactionRecorder for actual DB write
        val saleParams = mapOf(
            "type" to "sale",
            "amount" to amount.toString(),
            "product" to product,
            "quantity" to "1",
            "payment_method" to paymentMethod
        )

        val result = transactionRecorder.recordSale(saleParams)

        if (result.success) {
            // Track for undo
            val saleId = (result.data as? Map<*, *>)?.get("sale_id") as? Long
            lastSaleId = saleId
            lastSaleDescription = "$product - Ksh ${formatKes(amount)} ($paymentMethod)"

            sessionSales.add(
                BatchEntry(
                    saleId = saleId ?: 0,
                    product = product,
                    amount = amount,
                    paymentMethod = paymentMethod,
                    timestamp = System.currentTimeMillis()
                )
            )

            Timber.d("QuickSale: recorded $product Ksh $amount ($paymentMethod)")
        }

        return result
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // BATCH MODE — Record multiple sales fast
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private suspend fun handleBatch(params: Map<String, String>): ToolResult {
        val amountsRaw = params["amounts"]
            ?: return ToolResult.error(
                name,
                "Provide comma-separated amounts. Example: batch amounts=150,200,350"
            )

        val amounts = amountsRaw.split(",").mapNotNull { it.trim().toDoubleOrNull() }
        if (amounts.isEmpty()) {
            return ToolResult.error(name, "No valid amounts found in: $amountsRaw")
        }

        val product = params["product"]
            ?: getLastSaleProduct()
            ?: timeOfDayDefault()
        val paymentMethod = params["payment_method"] ?: "mpesa"

        val results = mutableListOf<Map<String, Any?>>()
        var totalBatch = 0.0
        var successCount = 0
        var failCount = 0

        for (amount in amounts) {
            val validation = transactionRecorder.validateAmount(amount)
            if (!validation.valid) {
                results.add(mapOf("amount" to amount, "status" to "error", "reason" to validation.message))
                failCount++
                continue
            }

            val saleParams = mapOf(
                "type" to "sale",
                "amount" to amount.toString(),
                "product" to product,
                "quantity" to "1",
                "payment_method" to paymentMethod
            )

            val result = transactionRecorder.recordSale(saleParams)
            if (result.success) {
                val saleId = (result.data as? Map<*, *>)?.get("sale_id") as? Long
                sessionSales.add(
                    BatchEntry(
                        saleId = saleId ?: 0,
                        product = product,
                        amount = amount,
                        paymentMethod = paymentMethod,
                        timestamp = System.currentTimeMillis()
                    )
                )
                // Track last for undo
                lastSaleId = saleId
                lastSaleDescription = "$product - Ksh ${formatKes(amount)} ($paymentMethod)"

                results.add(mapOf("amount" to amount, "status" to "recorded", "sale_id" to saleId))
                totalBatch += amount
                successCount++
            } else {
                results.add(mapOf("amount" to amount, "status" to "error", "reason" to result.message))
                failCount++
            }
        }

        val summary = buildString {
            append("Batch complete: $successCount sales recorded")
            if (failCount > 0) append(", $failCount failed")
            append(" | Total: Ksh ${formatKes(totalBatch)}")
            append(" | Product: $product, Payment: $paymentMethod")
        }

        Timber.d("QuickSale batch: $successCount/$amounts.size sales, total Ksh $totalBatch")

        return ToolResult.success(
            toolName = name,
            data = mapOf(
                "batch_results" to results,
                "total" to totalBatch,
                "count" to successCount,
                "failed" to failCount,
                "product" to product,
                "payment_method" to paymentMethod
            ),
            message = summary
        )
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // UNDO — Reverse the last quick sale
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private suspend fun handleUndo(): ToolResult {
        val saleId = lastSaleId
            ?: return ToolResult.error(name, "Nothing to undo. No quick sales recorded yet this session.", "NO_UNDO")

        // Fetch the sale to delete it
        val recentSales = saleDao.getRecentSales(1).first()
        val lastSale = recentSales.firstOrNull { it.id == saleId }
            ?: return ToolResult.error(name, "Could not find sale #$saleId to undo.", "SALE_NOT_FOUND")

        // Restore product stock
        productDao.addStock(lastSale.productId, lastSale.quantity)

        // Delete the sale
        saleDao.delete(lastSale)

        val description = lastSaleDescription ?: "Sale #$saleId"
        lastSaleId = null
        lastSaleDescription = null

        // Remove from session tracking
        sessionSales.removeAll { it.saleId == saleId }

        Timber.d("QuickSale undo: deleted sale #$saleId ($description)")

        return ToolResult.success(
            toolName = name,
            data = mapOf("undone_sale_id" to saleId, "amount_refunded" to lastSale.totalPrice),
            message = "Undone: $description — Ksh ${formatKes(lastSale.totalPrice)} reversed"
        )
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // SUMMARY — Today's sales at a glance
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private suspend fun handleSummary(): ToolResult {
        val now = System.currentTimeMillis()
        val startOfDay = getStartOfDay()

        val totalSales = saleDao.getTotalSalesBetween(startOfDay, now).first() ?: 0.0
        val txCount = saleDao.getTransactionCountBetween(startOfDay, now).first()
        val topProducts = saleDao.getTopProducts(startOfDay, now, 5).first()
        val mpesaTotal = saleDao.getMpesaSalesBetween(startOfDay, now).first() ?: 0.0
        val cashTotal = totalSales - mpesaTotal // approximate

        // Session stats
        val sessionTotal = sessionSales.sumOf { it.amount }
        val sessionCount = sessionSales.size

        val summary = buildString {
            appendLine("📊 Today's Sales Summary")
            appendLine("━━━━━━━━━━━━━━━━━━━━━")
            appendLine("💰 Total: Ksh ${formatKes(totalSales)}")
            appendLine("🧾 Transactions: $txCount")
            if (txCount > 0) {
                appendLine("📈 Average: Ksh ${formatKes(totalSales / txCount)}")
            }
            appendLine("📱 M-Pesa: Ksh ${formatKes(mpesaTotal)}")
            appendLine("💵 Cash: Ksh ${formatKes(cashTotal)}")

            if (topProducts.isNotEmpty()) {
                appendLine()
                appendLine("🏆 Top products:")
                topProducts.forEachIndexed { i, p ->
                    appendLine("  ${i + 1}. ${p.productName} — Ksh ${formatKes(p.totalRevenue)} (${p.totalQty.toInt()} sold)")
                }
            }

            if (sessionCount > 0) {
                appendLine()
                appendLine("⚡ This session: $sessionCount sales, Ksh ${formatKes(sessionTotal)}")
            }
        }

        return ToolResult.success(
            toolName = name,
            data = mapOf(
                "total_sales" to totalSales,
                "transaction_count" to txCount,
                "mpesa_total" to mpesaTotal,
                "cash_total" to cashTotal,
                "top_products" to topProducts.map { mapOf("name" to it.productName, "revenue" to it.totalRevenue, "qty" to it.totalQty) },
                "session_sales" to sessionCount,
                "session_total" to sessionTotal
            ),
            message = summary.toString().trimEnd()
        )
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // SMART DEFAULTS
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * Get the product name from the most recent sale.
     * Returns null if no sales exist yet.
     */
    private suspend fun getLastSaleProduct(): String? {
        return try {
            saleDao.getRecentSales(1).first().firstOrNull()?.productName
        } catch (e: Exception) {
            Timber.w(e, "QuickSale: could not fetch last sale product")
            null
        }
    }

    /**
     * Auto-categorize based on time of day.
     * Maps selling hours to typical product categories for Kenyan informal workers:
     *
     * - 05:00–10:59 → "Mboga" (vegetables — morning market rush)
     * - 11:00–14:59 → "Mazao" (produce/general — midday trading)
     * - 15:00–21:59 → "Chakula" (cooked food — evening meal prep)
     * - 22:00–04:59 → "Bidhaa" (general goods — late night)
     */
    private fun timeOfDayDefault(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..10 -> "Mboga"      // Morning: vegetables
            in 11..14 -> "Mazao"     // Midday: produce/general
            in 15..21 -> "Chakula"   // Evening: cooked food
            else -> "Bidhaa"         // Late night: general
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // HELPERS
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private fun getStartOfDay(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun formatKes(amount: Double): String {
        return if (amount == amount.toLong().toDouble()) {
            "%,d".format(amount.toLong())
        } else {
            "%,.0f".format(amount)
        }
    }
}
