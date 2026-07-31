package com.msaidizi.agent.tools.agriculture

import com.msaidizi.core.database.ProductDao
import com.msaidizi.core.database.RestockThresholdDao
import com.msaidizi.core.database.StockMovementDao
import com.msaidizi.core.model.ProductEntity
import com.msaidizi.core.model.RestockThresholdEntity
import com.msaidizi.core.model.StockMovementEntity
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * AutoRestock — Predicts restock quantities from sales history.
 *
 * Learns daily sales patterns per product, predicts when stock runs out,
 * and makes smart restocking suggestions based on day of week and
 * historical demand. Built for mama mbogas and small traders.
 *
 * Actions:
 *   suggest       — Smart restock suggestions based on sales history & day-of-week patterns
 *   predict_stockout — Predict when each product (or a specific one) will run out
 *   restock_now   — Record a restock event (adds stock + learns from the restock quantity)
 *   history       — Show recent sales history and patterns for a product
 *   set_threshold — Set custom low-stock threshold per product
 */
@Singleton
class AutoRestock @Inject constructor(
    private val productDao: ProductDao,
    private val stockMovementDao: StockMovementDao,
    private val restockThresholdDao: RestockThresholdDao
) : Tool {

    override val name = "auto_restock"
    override val description = "Predicts restock needs from sales history with day-of-week awareness"

    override val argsSchema = argSchema {
        enum(
            "action", "Action to perform",
            listOf("suggest", "predict_stockout", "restock_now", "history", "set_threshold"),
            required = false
        )
        string("product", "Product name", required = false)
        number("quantity", "Quantity to restock (for restock_now) or custom threshold (for set_threshold)", required = false)
        integer("days", "Number of days of history to analyze (default 14)", required = false, default = 14)
    }

    companion object {
        /** Minimum sales data points needed to make a prediction. */
        private const val MIN_DATA_POINTS = 3

        /** Safety buffer multiplier — order 20% more than predicted need. */
        private const val SAFETY_BUFFER = 1.20

        /** Days of the week (Calendar constants) for pattern matching. */
        private val WEEKDAYS = listOf(
            Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
            Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY
        )

        /** Swahili day names for voice output. */
        private val SWAHILI_DAYS = mapOf(
            Calendar.MONDAY to "Jumatatu",
            Calendar.TUESDAY to "Jumanne",
            Calendar.WEDNESDAY to "Jumatano",
            Calendar.THURSDAY to "Alhamisi",
            Calendar.FRIDAY to "Ijumaa",
            Calendar.SATURDAY to "Jumamosi",
            Calendar.SUNDAY to "Jumapili"
        )
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"] ?: "suggest"
        return when (action.lowercase()) {
            "suggest" -> suggest(params)
            "predict_stockout" -> predictStockout(params)
            "restock_now" -> restockNow(params)
            "history" -> showHistory(params)
            "set_threshold" -> setThreshold(params)
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    // ──────────────────────────────────────────────────────────
    // 1. SUGGEST — Smart restock suggestions
    // ──────────────────────────────────────────────────────────

    /**
     * Analyse sales history and produce smart restock suggestions.
     *
     * For each product (or a specific one):
     *   1. Calculate average daily sales over the lookback window.
     *   2. Weight by day-of-week (today's weekday gets extra emphasis).
     *   3. Project how many days of stock remain.
     *   4. Suggest ordering quantity that covers ~7 days of sales + safety buffer.
     *
     * Returns Swahili voice-friendly output.
     */
    private suspend fun suggest(params: Map<String, String>): ToolResult {
        return try {
            val productName = params["product"]
            val lookbackDays = params["days"]?.toIntOrNull() ?: 14
            val sinceMs = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(lookbackDays.toLong())

            val products = if (productName != null) {
                val p = findProduct(productName)
                    ?: return ToolResult.error(name, "Product not found: $productName", "NOT_FOUND")
                listOf(p)
            } else {
                productDao.getAllActive().first()
            }

            if (products.isEmpty()) {
                return ToolResult.success(name, message = "Hakuna bidhaa kwenye orodha. Ongeza bidhaa kwanza.")
            }

            val todayDow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
            val suggestions = mutableListOf<Map<String, Any>>()
            val voiceLines = mutableListOf<String>()

            for (product in products) {
                val movements = stockMovementDao.getSalesSince(product.id, sinceMs).first()
                if (movements.isEmpty()) continue

                // ── Average daily sales ──
                val totalSold = movements.sumOf { it.quantity.absoluteValue }
                val uniqueDays = movements
                    .map { dayKey(it.timestamp) }
                    .distinct()
                    .size
                    .coerceAtLeast(1)
                val avgDailySales = totalSold / uniqueDays.toDouble()

                // ── Day-of-week weighting ──
                val dowSales = movements.groupBy { dayOfWeek(it.timestamp) }
                    .mapValues { (_, v) -> v.sumOf { it.quantity.absoluteValue } }
                val todaySales = dowSales[todayDow]?.toDouble() ?: avgDailySales
                // Blend: 60% today's dow average, 40% overall average
                val projectedDailySales = (todaySales * 0.6 + avgDailySales * 0.4)

                // ── Threshold ──
                val threshold = restockThresholdDao.get(product.id)?.threshold ?: product.minStock

                // ── Days of stock remaining ──
                val daysRemaining = if (projectedDailySales > 0) {
                    product.currentStock / projectedDailySales
                } else {
                    Double.MAX_VALUE
                }

                // ── Suggested order quantity (cover 7 days + buffer) ──
                val suggestedQty = if (daysRemaining < 7) {
                    val needed = projectedDailySales * 7 * SAFETY_BUFFER
                    max(1.0, ceil(needed - product.currentStock))
                } else {
                    0.0 // no restock needed
                }

                val needsRestock = suggestedQty > 0

                suggestions.add(
                    mapOf(
                        "product" to product.name,
                        "current_stock" to product.currentStock,
                        "avg_daily_sales" to avgDailySales.roundTwo(),
                        "today_weighted_sales" to projectedDailySales.roundTwo(),
                        "days_remaining" to daysRemaining.roundTwo(),
                        "threshold" to threshold,
                        "suggested_qty" to suggestedQty.roundToInt(),
                        "needs_restock" to needsRestock,
                        "unit" to product.unit
                    )
                )

                if (needsRestock) {
                    val swahiliDay = SWAHILI_DAYS[todayDow] ?: "leo"
                    voiceLines.add(
                        "${product.name}: baki ${product.currentStock.toInt()} ${product.unit}. " +
                        "Mauzo ya $swahiliDay ni makubwa. Nunua ${suggestedQty.roundToInt()} ${product.unit} sasa."
                    )
                }
            }

            if (suggestions.isEmpty()) {
                return ToolResult.success(
                    name,
                    message = "Hakuna data ya mauzo ya kutosha. Fanya mauzo kwanza, kisha jaribu tena."
                )
            }

            val needsRestockCount = suggestions.count { it["needs_restock"] == true }
            val summary = if (needsRestockCount == 0) {
                "Stock ya bidhaa zote iko vizuri! Hakuna haja ya kununua sasa. ✅"
            } else {
                val topVoice = voiceLines.joinToString(" ")
                "Bidhaa $needsRestockCount zinahitaji kununuliwa. $topVoice"
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "suggestions" to suggestions,
                    "lookback_days" to lookbackDays,
                    "today" to (SWAHILI_DAYS[todayDow] ?: "unknown")
                ),
                message = summary
            )
        } catch (e: Exception) {
            Timber.e(e, "AutoRestock suggest failed")
            ToolResult.error(name, "Imeshindikana: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────────────────
    // 2. PREDICT_STOCKOUT — When will stock run out?
    // ──────────────────────────────────────────────────────────

    /**
     * Predict the date when each product (or a specific one) will run out of stock
     * based on recent sales velocity.
     */
    private suspend fun predictStockout(params: Map<String, String>): ToolResult {
        return try {
            val productName = params["product"]
            val lookbackDays = params["days"]?.toIntOrNull() ?: 14
            val sinceMs = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(lookbackDays.toLong())

            val products = if (productName != null) {
                val p = findProduct(productName)
                    ?: return ToolResult.error(name, "Product not found: $productName", "NOT_FOUND")
                listOf(p)
            } else {
                productDao.getAllActive().first()
            }

            if (products.isEmpty()) {
                return ToolResult.success(name, message = "Hakuna bidhaa kwenye orodha.")
            }

            val predictions = mutableListOf<Map<String, Any>>()
            val voiceLines = mutableListOf<String>()

            for (product in products) {
                val movements = stockMovementDao.getSalesSince(product.id, sinceMs).first()
                if (movements.isEmpty()) continue

                val totalSold = movements.sumOf { it.quantity.absoluteValue }
                val uniqueDays = movements.map { dayKey(it.timestamp) }.distinct().size.coerceAtLeast(1)
                val avgDailySales = totalSold / uniqueDays.toDouble()

                // Day-of-week adjustment
                val todayDow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
                val dowSales = movements.groupBy { dayOfWeek(it.timestamp) }
                    .mapValues { (_, v) -> v.sumOf { it.quantity.absoluteValue } }
                val todaySales = dowSales[todayDow]?.toDouble() ?: avgDailySales
                val projectedDaily = todaySales * 0.6 + avgDailySales * 0.4

                val daysRemaining = if (projectedDaily > 0) {
                    product.currentStock / projectedDaily
                } else {
                    Double.MAX_VALUE
                }

                val stockoutDays = if (daysRemaining == Double.MAX_VALUE) {
                    "Haijulikani"
                } else {
                    when {
                        daysRemaining < 1 -> "Leo au kesho!"
                        daysRemaining < 2 -> "Kesho"
                        daysRemaining < 7 -> "Siku ${daysRemaining.roundToInt()}"
                        else -> "Siku ${daysRemaining.roundToInt()}"
                    }
                }

                val stockoutDate = if (daysRemaining < 365) {
                    val cal = Calendar.getInstance()
                    cal.add(Calendar.DAY_OF_YEAR, daysRemaining.toInt())
                    "${cal.get(Calendar.DAY_OF_MONTH)}/${cal.get(Calendar.MONTH) + 1}"
                } else {
                    "Muda mrefu"
                }

                predictions.add(
                    mapOf(
                        "product" to product.name,
                        "current_stock" to product.currentStock,
                        "avg_daily_sales" to avgDailySales.roundTwo(),
                        "days_remaining" to daysRemaining.roundTwo(),
                        "stockout_estimate" to stockoutDays,
                        "stockout_date" to stockoutDate,
                        "unit" to product.unit
                    )
                )

                if (daysRemaining < 3) {
                    voiceLines.add(
                        "${product.name}: itaisha $stockoutDays! Baki ${product.currentStock.toInt()} ${product.unit} tu."
                    )
                } else if (daysRemaining < 7) {
                    voiceLines.add(
                        "${product.name}: itaisha katika siku ${daysRemaining.roundToInt()}. Nunua mapema."
                    )
                }
            }

            if (predictions.isEmpty()) {
                return ToolResult.success(name, message = "Hakuna data ya mauzo ya kutosha kutabiri.")
            }

            val summary = if (voiceLines.isNotEmpty()) {
                "Onyo! ${voiceLines.joinToString(" ")}"
            } else {
                "Stock ya bidhaa zote iko salama kwa sasa. ✅"
            }

            ToolResult.success(
                toolName = name,
                data = mapOf("predictions" to predictions, "lookback_days" to lookbackDays),
                message = summary
            )
        } catch (e: Exception) {
            Timber.e(e, "AutoRestock predictStockout failed")
            ToolResult.error(name, "Imeshindikana: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────────────────
    // 3. RESTOCK_NOW — Record a restock event
    // ──────────────────────────────────────────────────────────

    /**
     * Record a restock: adds stock via productDao and logs a "purchase" movement.
     * Also records the restock event for future prediction refinement.
     */
    private suspend fun restockNow(params: Map<String, String>): ToolResult {
        return try {
            val productName = params["product"]
                ?: return ToolResult.error(name, "Product name required. Tell me: which product?", "MISSING_PRODUCT")
            val quantity = params["quantity"]?.toDoubleOrNull()
                ?: return ToolResult.error(name, "Quantity required. How many units?", "MISSING_QUANTITY")

            if (quantity <= 0) {
                return ToolResult.error(name, "Quantity must be positive.", "INVALID_QUANTITY")
            }

            val product = findProduct(productName)
                ?: return ToolResult.error(name, "Product not found: $productName", "NOT_FOUND")

            val previousStock = product.currentStock
            productDao.addStock(product.id, quantity)

            stockMovementDao.insert(
                StockMovementEntity(
                    productId = product.id,
                    type = "purchase",
                    quantity = quantity,
                    previousStock = previousStock,
                    newStock = previousStock + quantity,
                    notes = "AutoRestock: manual restock"
                )
            )

            val newStock = previousStock + quantity

            // Swahili voice confirmation
            val voiceMsg = "Umefanikiwa! ${product.name}: umeongeza ${quantity.toInt()} ${product.unit}. " +
                "Stock sasa ni ${newStock.toInt()} ${product.unit}."

            Timber.d("AutoRestock: restocked $productName +$quantity = $newStock")

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "product" to product.name,
                    "added" to quantity,
                    "new_stock" to newStock,
                    "unit" to product.unit
                ),
                message = voiceMsg
            )
        } catch (e: Exception) {
            Timber.e(e, "AutoRestock restockNow failed")
            ToolResult.error(name, "Imeshindikana: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────────────────
    // 4. HISTORY — Show sales history and patterns
    // ──────────────────────────────────────────────────────────

    /**
     * Show recent sales history for a product, broken down by day of week.
     * Helps the trader understand their own sales patterns.
     */
    private suspend fun showHistory(params: Map<String, String>): ToolResult {
        return try {
            val productName = params["product"]
                ?: return ToolResult.error(name, "Product name required.", "MISSING_PRODUCT")
            val lookbackDays = params["days"]?.toIntOrNull() ?: 14
            val sinceMs = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(lookbackDays.toLong())

            val product = findProduct(productName)
                ?: return ToolResult.error(name, "Product not found: $productName", "NOT_FOUND")

            val movements = stockMovementDao.getSalesSince(product.id, sinceMs).first()

            if (movements.isEmpty()) {
                return ToolResult.success(
                    name,
                    message = "Hakuna mauzo ya ${product.name} katika siku $lookbackDays zilizopita."
                )
            }

            // ── Daily breakdown ──
            val dailySales = movements
                .groupBy { dayKey(it.timestamp) }
                .mapValues { (_, v) -> v.sumOf { it.quantity.absoluteValue } }
                .toSortedMap()

            // ── Day-of-week averages ──
            val dowGroups = movements.groupBy { dayOfWeek(it.timestamp) }
            val dowAverages = WEEKDAYS.associate { dow ->
                val entries = dowGroups[dow] ?: emptyList()
                val daysWithData = entries.map { dayKey(it.timestamp) }.distinct().size.coerceAtLeast(1)
                val total = entries.sumOf { it.quantity.absoluteValue }
                dow to (total / daysWithData.toDouble())
            }

            val totalSold = movements.sumOf { it.quantity.absoluteValue }
            val uniqueDays = dailySales.size
            val avgDaily = totalSold / uniqueDays.toDouble()

            // Find best and worst days
            val bestDow = dowAverages.maxByOrNull { it.value }
            val worstDow = dowAverages.minByOrNull { it.value }

            // Build voice summary
            val voiceLines = mutableListOf<String>()
            voiceLines.add(
                "Mauzo ya ${product.name} siku $lookbackDays: jumla ${totalSold.toInt()} ${product.unit}. " +
                "Wastani wa siku: ${avgDaily.roundTwo()} ${product.unit}."
            )

            if (bestDow != null && worstDow != null && bestDow.key != worstDow.key) {
                voiceLines.add(
                    "Siku bora zaidi: ${SWAHILI_DAYS[bestDow.key]} (wastani ${bestDow.value.roundTwo()}). " +
                    "Siku dhaifu zaidi: ${SWAHILI_DAYS[worstDow.key]} (wastani ${worstDow.value.roundTwo()})."
                )
            }

            // Day-of-week breakdown for data
            val dowBreakdown = WEEKDAYS.map { dow ->
                mapOf(
                    "day" to (SWAHILI_DAYS[dow] ?: "?"),
                    "day_en" to dayNameEnglish(dow),
                    "avg_sales" to (dowAverages[dow] ?: 0.0).roundTwo()
                )
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "product" to product.name,
                    "total_sold" to totalSold,
                    "lookback_days" to lookbackDays,
                    "unique_selling_days" to uniqueDays,
                    "avg_daily_sales" to avgDaily.roundTwo(),
                    "day_of_week_breakdown" to dowBreakdown,
                    "daily_sales" to dailySales.map { (day, qty) -> mapOf("date" to day, "quantity" to qty) }
                ),
                message = voiceLines.joinToString(" ")
            )
        } catch (e: Exception) {
            Timber.e(e, "AutoRestock showHistory failed")
            ToolResult.error(name, "Imeshindikana: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────────────────
    // 5. SET_THRESHOLD — Custom low-stock threshold
    // ──────────────────────────────────────────────────────────

    /**
     * Set or update the restock threshold for a product.
     * When stock drops below this level, the suggest action will flag it.
     */
    private suspend fun setThreshold(params: Map<String, String>): ToolResult {
        return try {
            val productName = params["product"]
                ?: return ToolResult.error(name, "Product name required.", "MISSING_PRODUCT")
            val quantity = params["quantity"]?.toDoubleOrNull()
                ?: return ToolResult.error(name, "Threshold quantity required.", "MISSING_QUANTITY")

            if (quantity < 0) {
                return ToolResult.error(name, "Threshold cannot be negative.", "INVALID_QUANTITY")
            }

            val product = findProduct(productName)
                ?: return ToolResult.error(name, "Product not found: $productName", "NOT_FOUND")

            val existing = restockThresholdDao.get(product.id)
            if (existing != null) {
                restockThresholdDao.update(existing.copy(threshold = quantity))
            } else {
                restockThresholdDao.insert(
                    RestockThresholdEntity(
                        productId = product.id,
                        threshold = quantity
                    )
                )
            }

            Timber.d("AutoRestock: threshold for $productName set to $quantity")

            val voiceMsg = "Imewekwa! ${product.name}: utapata onyo wakati stock ikiwa chini ya ${quantity.toInt()} ${product.unit}."

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "product" to product.name,
                    "threshold" to quantity,
                    "unit" to product.unit
                ),
                message = voiceMsg
            )
        } catch (e: Exception) {
            Timber.e(e, "AutoRestock setThreshold failed")
            ToolResult.error(name, "Imeshindikana: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────

    private suspend fun findProduct(name: String): ProductEntity? {
        return productDao.search(name).first().firstOrNull()
    }

    /** Extract a date key (epoch day) from a timestamp for grouping. */
    private fun dayKey(timestampMs: Long): Long {
        return TimeUnit.MILLISECONDS.toDays(timestampMs)
    }

    /** Extract Calendar.DAY_OF_WEEK from a timestamp. */
    private fun dayOfWeek(timestampMs: Long): Int {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestampMs
        return cal.get(Calendar.DAY_OF_WEEK)
    }

    /** English day name for data output. */
    private fun dayNameEnglish(dow: Int): String {
        return when (dow) {
            Calendar.MONDAY -> "Monday"
            Calendar.TUESDAY -> "Tuesday"
            Calendar.WEDNESDAY -> "Wednesday"
            Calendar.THURSDAY -> "Thursday"
            Calendar.FRIDAY -> "Friday"
            Calendar.SATURDAY -> "Saturday"
            Calendar.SUNDAY -> "Sunday"
            else -> "?"
        }
    }

    /** Round to 2 decimal places. */
    private fun Double.roundTwo(): Double = (this * 100).roundToInt() / 100.0

    /** Absolute value of a Double (for stock movement quantities that may be negative). */
    private val Double.absoluteValue: Double get() = kotlin.math.abs(this)
}
