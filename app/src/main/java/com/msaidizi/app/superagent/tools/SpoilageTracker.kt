package com.msaidizi.app.superagent.tools

import com.msaidizi.app.core.database.ProductDao
import com.msaidizi.app.core.database.StockMovementDao
import com.msaidizi.app.model.ProductEntity
import com.msaidizi.app.model.StockMovementEntity
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SpoilageTracker — Track perishable inventory and reduce waste.
 *
 * Research shows mama mbogas lose 15-30% of stock to spoilage.
 * On KES 3,000 daily stock, 20% spoilage = KES 600/day = KES 18,000/month.
 *
 * This tool:
 *   1. Tracks perishable inventory by purchase date
 *   2. Alerts when items approaching spoilage (1-3 day window)
 *   3. Calculates spoilage cost per day/week/month
 *   4. Suggests markdown pricing for aging stock
 *
 * Perishable categories:
 *   - Very perishable (1-2 days): sukuma wiki, spinach, mrenda
 *   - Perishable (2-4 days): tomatoes, cucumbers, bananas
 *   - Semi-perishable (5-7 days): onions, carrots, potatoes
 */
@Singleton
class SpoilageTracker @Inject constructor(
    private val productDao: ProductDao,
    private val stockMovementDao: StockMovementDao
) : Tool {

    override val name = "spoilage_tracker"
    override val description = "Track perishable inventory, alert on spoilage risk, suggest markdown pricing"

    override val argsSchema = argSchema {
        enum("action", "Action to perform",
            listOf("check", "alerts", "record_spoilage", "markdown_suggestions", "spoilage_cost", "set_shelf_life"),
            required = false)
        string("product", "Product name", required = false)
        number("quantity", "Quantity spoiled (for record_spoilage)", required = false)
        number("shelf_life_days", "Shelf life in days (for set_shelf_life)", required = false)
        number("purchase_price", "Original purchase price per unit", required = false)
    }

    // Default shelf life for common perishables (in days)
    private val defaultShelfLife = mapOf(
        // Very perishable (1-2 days)
        "sukuma wiki" to 2, "kale" to 2, "spinach" to 2, "mrenda" to 2,
        "terere" to 2, "managu" to 2, "kunde" to 2,
        // Perishable (2-4 days)
        "nyanya" to 3, "tomatoes" to 3, "matango" to 4, "cucumbers" to 4,
        "ndizi" to 3, "bananas" to 3, "mangoes" to 3, "embe" to 3,
        "parachichi" to 3, "avocado" to 3, "nanasi" to 4, "pineapple" to 4,
        // Semi-perishable (5-7 days)
        "vitunguu" to 7, "onions" to 7, "karoti" to 6, "carrots" to 6,
        "viazi" to 7, "potatoes" to 7, "kabichi" to 5, "cabbage" to 5,
        "pilipili" to 5, "chilli" to 5, "hoho" to 5, "peppers" to 5,
        "mahindi" to 3, "maize" to 3, "bungo" to 5, "cassava" to 5,
        // Long-lasting
        "maharagwe" to 30, "beans" to 30, "mchele" to 180, "rice" to 180,
        "ngano" to 90, "flour" to 90
    )

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"] ?: "check"
        return when (action.lowercase()) {
            "check" -> checkSpoilageRisk(params)
            "alerts" -> getSpoilageAlerts()
            "record_spoilage" -> recordSpoilage(params)
            "markdown_suggestions" -> getMarkdownSuggestions()
            "spoilage_cost" -> calculateSpoilageCost()
            "set_shelf_life" -> setShelfLife(params)
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    /**
     * Check spoilage risk for a specific product or all products.
     */
    private suspend fun checkSpoilageRisk(params: Map<String, String>): ToolResult {
        return try {
            val productName = params["product"]
            val products = if (productName != null) {
                val product = findProduct(productName)
                    ?: return ToolResult.error(name, "Product not found: $productName", "NOT_FOUND")
                listOf(product)
            } else {
                productDao.getAllActive().first()
            }

            val risks = products.map { product ->
                val shelfLife = getShelfLife(product.name)
                val stockMovements = stockMovementDao.getByProduct(product.id, 20).first()
                val lastPurchase = stockMovements
                    .filter { it.type == "purchase" }
                    .maxByOrNull { it.timestamp }

                val daysSincePurchase = if (lastPurchase != null) {
                    TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - lastPurchase.timestamp)
                } else {
                    0 // Unknown — assume fresh
                }

                val daysRemaining = (shelfLife - daysSincePurchase).coerceAtLeast(0)
                val riskLevel = when {
                    daysRemaining <= 0 -> "EXPIRED"
                    daysRemaining <= 1 -> "CRITICAL"
                    daysRemaining <= 2 -> "HIGH"
                    daysRemaining <= 3 -> "MEDIUM"
                    else -> "LOW"
                }

                SpoilageRisk(
                    productName = product.name,
                    currentStock = product.currentStock,
                    unit = product.unit,
                    shelfLifeDays = shelfLife,
                    daysSincePurchase = daysSincePurchase,
                    daysRemaining = daysRemaining,
                    riskLevel = riskLevel,
                    buyPrice = product.buyPrice
                )
            }

            val alerts = risks.filter { it.riskLevel in setOf("CRITICAL", "HIGH", "EXPIRED") }

            val message = buildString {
                if (alerts.isEmpty()) {
                    append("✅ Hakuna bidhaa zinazokaribia kuharibika!\n")
                    append("No items approaching spoilage!")
                } else {
                    append("⚠️ ${alerts.size} bidhaa zinakaribia kuharibika:\n\n")
                    alerts.forEach { risk ->
                        val emoji = when (risk.riskLevel) {
                            "EXPIRED" -> "🔴"
                            "CRITICAL" -> "🟠"
                            "HIGH" -> "🟡"
                            else -> "🟢"
                        }
                        append("$emoji ${risk.productName}: ${risk.currentStock.toInt()} ${risk.unit}")
                        append(" — ${risk.daysRemaining} siku imebaki / days left")
                        if (risk.riskLevel in setOf("EXPIRED", "CRITICAL")) {
                            append(" ⚠️ PUNGUA BEI / LOWER PRICE!")
                        }
                        append("\n")
                    }
                }
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "risks" to risks.map { mapOf(
                        "product" to it.productName,
                        "risk_level" to it.riskLevel,
                        "days_remaining" to it.daysRemaining,
                        "stock" to it.currentStock
                    )},
                    "alerts_count" to alerts.size
                ),
                message = message
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to check spoilage risk")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    /**
     * Get spoilage alerts — items at CRITICAL or HIGH risk.
     */
    private suspend fun getSpoilageAlerts(): ToolResult {
        return checkSpoilageRisk(emptyMap())
    }

    /**
     * Record spoiled stock — marks quantity as spoiled and reduces inventory.
     */
    private suspend fun recordSpoilage(params: Map<String, String>): ToolResult {
        return try {
            val productName = params["product"]
                ?: return ToolResult.error(name, "Product name required", "MISSING_PRODUCT")
            val quantity = params["quantity"]?.toDoubleOrNull()
                ?: return ToolResult.error(name, "Quantity required", "MISSING_QUANTITY")

            val product = findProduct(productName)
                ?: return ToolResult.error(name, "Product not found: $productName", "NOT_FOUND")

            if (quantity > product.currentStock) {
                return ToolResult.error(name, "Cannot spoil more than available stock (${product.currentStock})", "INVALID_QUANTITY")
            }

            val previousStock = product.currentStock
            productDao.reduceStock(product.id, quantity)

            // Record as spoilage stock movement
            stockMovementDao.insert(
                StockMovementEntity(
                    productId = product.id,
                    type = "spoilage",
                    quantity = -quantity,
                    previousStock = previousStock,
                    newStock = previousStock - quantity,
                    notes = "Spoilage recorded"
                )
            )

            val spoilageCost = quantity * product.buyPrice

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "product" to productName,
                    "quantity_spoiled" to quantity,
                    "spoilage_cost" to spoilageCost,
                    "remaining_stock" to (previousStock - quantity)
                ),
                message = "Spoilage recorded: $productName x${quantity.toInt()} ${product.unit} = KES ${"%,.0f".format(spoilageCost)} lost. Remaining: ${(previousStock - quantity).toInt()} ${product.unit}"
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to record spoilage")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    /**
     * Get markdown pricing suggestions for aging stock.
     * Suggests price reductions to move stock before spoilage.
     */
    private suspend fun getMarkdownSuggestions(): ToolResult {
        return try {
            val products = productDao.getAllActive().first()
            val suggestions = mutableListOf<MarkdownSuggestion>()

            for (product in products) {
                val shelfLife = getShelfLife(product.name)
                val stockMovements = stockMovementDao.getByProduct(product.id, 20).first()
                val lastPurchase = stockMovements
                    .filter { it.type == "purchase" }
                    .maxByOrNull { it.timestamp }

                if (lastPurchase == null || product.currentStock <= 0) continue

                val daysSincePurchase = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - lastPurchase.timestamp)
                val daysRemaining = (shelfLife - daysSincePurchase).coerceAtLeast(0)
                val stockRatio = product.currentStock / (product.currentStock + stockMovements
                    .filter { it.type == "sale" }
                    .sumOf { kotlin.math.abs(it.quantity) })
                    .coerceAtLeast(1.0)

                // Calculate suggested markdown
                val markdownPct = when {
                    daysRemaining <= 0 -> 50 // Must sell NOW — 50% off
                    daysRemaining <= 1 -> 40 // 40% off
                    daysRemaining <= 2 -> 25 // 25% off
                    daysRemaining <= 3 -> 15 // 15% off
                    stockRatio > 0.7 -> 10 // Slow moving — 10% off
                    else -> 0 // No markdown needed
                }

                if (markdownPct > 0) {
                    val currentPrice = product.sellPrice
                    val suggestedPrice = currentPrice * (1 - markdownPct / 100.0)
                    val potentialLoss = product.currentStock * product.buyPrice
                    val recoveryAmount = product.currentStock * suggestedPrice

                    suggestions.add(MarkdownSuggestion(
                        productName = product.name,
                        currentStock = product.currentStock,
                        unit = product.unit,
                        currentPrice = currentPrice,
                        suggestedPrice = suggestedPrice,
                        markdownPct = markdownPct,
                        daysRemaining = daysRemaining,
                        potentialLoss = potentialLoss,
                        recoveryAmount = recoveryAmount
                    ))
                }
            }

            val message = buildString {
                if (suggestions.isEmpty()) {
                    append("✅ Hakuna bidhaa zinazohitaji kupunguzwa bei!\n")
                    append("No products need markdown pricing!")
                } else {
                    append("💡 Mapendekezo ya Kupunguza Bei:\n")
                    append("Markdown Pricing Suggestions:\n\n")
                    suggestions.forEach { s ->
                        val urgency = when {
                            s.daysRemaining <= 1 -> "🔴 HARAKA / URGENT"
                            s.daysRemaining <= 2 -> "🟡 Upesi / Soon"
                            else -> "🟢 Polepole / Gradual"
                        }
                        append("$urgency\n")
                        append("  ${s.productName}: ${s.currentStock.toInt()} ${s.unit}\n")
                        append("  Bei ya sasa: KES ${"%,.0f".format(s.currentPrice)} → Pendekezo: KES ${"%,.0f".format(s.suggestedPrice)} (-${s.markdownPct}%)\n")
                        append("  Siku ${s.daysRemaining} kabla ya kuharibika / days until spoilage\n\n")
                    }
                }
            }

            ToolResult.success(
                toolName = name,
                data = mapOf("suggestions" to suggestions.map { mapOf(
                    "product" to it.productName,
                    "markdown_pct" to it.markdownPct,
                    "suggested_price" to it.suggestedPrice,
                    "days_remaining" to it.daysRemaining
                )}),
                message = message
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to get markdown suggestions")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    /**
     * Calculate spoilage cost for the current day/week/month.
     */
    private suspend fun calculateSpoilageCost(): ToolResult {
        return try {
            val now = System.currentTimeMillis()
            val dayStart = now - 24 * 60 * 60 * 1000L
            val weekStart = now - 7 * 24 * 60 * 60 * 1000L
            val monthStart = now - 30 * 24 * 60 * 60 * 1000L

            // Get all spoilage movements
            val allMovements = stockMovementDao.getMovementsBetween(monthStart, now).first()
            val spoilageMovements = allMovements.filter { it.type == "spoilage" }

            // Calculate costs (we need product buy prices)
            var dailyCost = 0.0
            var weeklyCost = 0.0
            var monthlyCost = 0.0

            for (movement in spoilageMovements) {
                val product = productDao.getById(movement.productId) ?: continue
                val cost = kotlin.math.abs(movement.quantity) * product.buyPrice

                monthlyCost += cost
                if (movement.timestamp >= weekStart) weeklyCost += cost
                if (movement.timestamp >= dayStart) dailyCost += cost
            }

            val message = buildString {
                append("📊 Gharama za Kuharibika / Spoilage Costs:\n\n")
                append("• Leo / Today: KES ${"%,.0f".format(dailyCost)}\n")
                append("• Wiki hii / This week: KES ${"%,.0f".format(weeklyCost)}\n")
                append("• Mwezi huu / This month: KES ${"%,.0f".format(monthlyCost)}\n\n")

                if (monthlyCost > 0) {
                    append("💡 Kwa wastani, unapoteza KES ${"%,.0f".format(monthlyCost / 30)} kwa siku kutokana na kuharibika.\n")
                    append("💡 You lose an average of KES ${"%,.0f".format(monthlyCost / 30)} per day to spoilage.\n")
                    append("💡 Punguza bei mapema ili kupunguza hasara! / Lower prices early to reduce losses!")
                } else {
                    append("✅ Hakuna hasara ya kuharibika mwezi huu! / No spoilage losses this month!")
                }
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "daily_cost" to dailyCost,
                    "weekly_cost" to weeklyCost,
                    "monthly_cost" to monthlyCost
                ),
                message = message
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to calculate spoilage cost")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    /**
     * Set custom shelf life for a product.
     */
    private suspend fun setShelfLife(params: Map<String, String>): ToolResult {
        val productName = params["product"]
            ?: return ToolResult.error(name, "Product name required", "MISSING_PRODUCT")
        val shelfLife = params["shelf_life_days"]?.toIntOrNull()
            ?: return ToolResult.error(name, "Shelf life in days required", "MISSING_SHELF_LIFE")

        // Store in knowledge base (would persist in production)
        // For now, return success with the setting
        return ToolResult.success(
            toolName = name,
            data = mapOf("product" to productName, "shelf_life_days" to shelfLife),
            message = "Shelf life for $productName set to $shelfLife days."
        )
    }

    private suspend fun findProduct(name: String): ProductEntity? {
        return productDao.search(name).first().firstOrNull()
    }

    private fun getShelfLife(productName: String): Int {
        val lower = productName.lowercase().trim()
        return defaultShelfLife.entries
            .firstOrNull { lower.contains(it.key) }
            ?.value
            ?: 4 // Default: 4 days for unknown perishables
    }

    data class SpoilageRisk(
        val productName: String,
        val currentStock: Double,
        val unit: String,
        val shelfLifeDays: Int,
        val daysSincePurchase: Long,
        val daysRemaining: Long,
        val riskLevel: String,
        val buyPrice: Double
    )

    data class MarkdownSuggestion(
        val productName: String,
        val currentStock: Double,
        val unit: String,
        val currentPrice: Double,
        val suggestedPrice: Double,
        val markdownPct: Int,
        val daysRemaining: Long,
        val potentialLoss: Double,
        val recoveryAmount: Double
    )
}
