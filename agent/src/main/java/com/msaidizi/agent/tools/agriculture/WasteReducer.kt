package com.msaidizi.agent.tools.agriculture

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext
import com.msaidizi.agent.tools.core.*

/**
 * WasteReducer — Perishable inventory tracking, spoilage prediction,
 * markdown timing, and bulk buyer directory.
 *
 * Problem: Mama mboga loses KES 150–400/day from perishable goods
 * fire-sales and spoilage (MI-3, IA-10). This tool tracks perishable
 * stock in real-time, predicts spoilage windows, suggests optimal
 * markdown timing, and connects workers with bulk buyers for end-of-day
 * sales.
 *
 * Actions: check_expiry, markdown_suggest, spoilage_risk, donate_connect,
 *          waste_log, summary
 *
 * Offline-first: all logic runs on-device with seed spoilage profiles.
 */
@Singleton
class WasteReducer @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val name = "waste_reducer"
    override val description =
        "Tracks perishable inventory, predicts spoilage, suggests markdowns, and connects to bulk buyers. Reduces daily waste losses."

    override val argsSchema = argSchema {
        enum(
            "action", "Action to perform",
            listOf(
                "check_expiry",      // Check spoilage risk for inventory
                "markdown_suggest",  // Get markdown price recommendation
                "spoilage_risk",     // Full spoilage risk assessment
                "donate_connect",    // Find bulk buyers for end-of-day stock
                "waste_log",         // Record actual waste/spoilage
                "summary"            // Daily waste vs. sales report
            ),
            required = true
        )
        string("product", "Product name (e.g. 'nyanya', 'sukuma wiki')", required = false)
        string("unit", "Unit of measure: kg, bunch, piece, litre", required = false, default = "kg")
        number("quantity", "Quantity purchased or remaining", required = false)
        number("price", "Purchase price total in KES", required = false)
        string("storage", "Storage type: ambient, cool, refrigerated", required = false, default = "ambient")
        string("county", "County for bulk buyer lookup (e.g. 'Nairobi', 'Migori')", required = false)
        string("buyer_type", "Bulk buyer type: restaurant, hotel, church, school, caterer", required = false)
        boolean("voice", "Format response for Swahili voice output", required = false)
    }

    // ──────────────────────────────────────────────
    // SQLite Database
    // ──────────────────────────────────────────────

    inner class WasteDatabase(context: Context) :
        SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(SQL_CREATE_PROFILES)
            db.execSQL(SQL_CREATE_INVENTORY)
            db.execSQL(SQL_CREATE_WASTE_LOG)
            db.execSQL(SQL_CREATE_BULK_BUYERS)
            seedProfiles(db)
            seedBulkBuyers(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
            // Forward-compatible: drop and recreate for dev
            if (old < 2) {
                db.execSQL("DROP TABLE IF EXISTS $TABLE_BULK_BUYERS")
                db.execSQL(SQL_CREATE_BULK_BUYERS)
                seedBulkBuyers(db)
            }
        }
    }

    private val dbHelper by lazy { WasteDatabase(context) }

    // ──────────────────────────────────────────────
    // Date helpers
    // ──────────────────────────────────────────────

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.US)
    private val now get() = System.currentTimeMillis()
    private val today get() = dateFormat.format(Date(now))

    // ──────────────────────────────────────────────
    // Action dispatch
    // ──────────────────────────────────────────────

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"] ?: "check_expiry"
        return when (action.lowercase()) {
            "check_expiry" -> checkExpiry(params)
            "markdown_suggest" -> markdownSuggest(params)
            "spoilage_risk" -> spoilageRisk(params)
            "donate_connect" -> donateConnect(params)
            "waste_log" -> logWaste(params)
            "summary" -> dailySummary(params)
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    // ──────────────────────────────────────────────
    // 1. check_expiry — Add stock or check existing expiry
    // ──────────────────────────────────────────────

    /**
     * If quantity+price provided: records new perishable purchase.
     * Otherwise: lists all active inventory with spoilage countdown.
     *
     * Voice: "Nimepata kilo kumi za nyanya kwa 800"
     * Voice: "Bidhaa zangu zikoje?"
     */
    private fun checkExpiry(params: Map<String, String>): ToolResult {
        return try {
            val product = params["product"]
            val quantity = params["quantity"]?.toDoubleOrNull()
            val price = params["price"]?.toDoubleOrNull()
            val useVoice = params["voice"]?.toBoolean() ?: true

            // If product + quantity + price → add new stock
            if (product != null && quantity != null && price != null) {
                return addStock(product, quantity, price, params)
            }

            // Otherwise, show inventory with expiry countdown
            val db = dbHelper.readableDatabase
            val items = queryActiveInventory(db, product)

            if (items.isEmpty()) {
                val msg = if (useVoice) {
                    "Hakuna bidhaa zilizo kwenye hifadhi. Sema 'Nimepata [idadi] ya [bidhaa] kwa [bei]' kuongeza."
                } else {
                    "No perishable inventory tracked. Add stock first."
                }
                return ToolResult.success(name, message = msg)
            }

            val lines = items.map { row ->
                val productName = row.product
                val remaining = row.quantityRemaining
                val unit = row.unit
                val purchaseDate = row.purchaseDate
                val expectedSpoil = row.expectedSpoilDate ?: "?"
                val status = row.status

                val daysLeft = daysUntil(expectedSpoil)
                val riskEmoji = when {
                    daysLeft <= 0 -> "🔴"
                    daysLeft <= 1 -> "🟡"
                    else -> "🟢"
                }

                if (useVoice) {
                    val dayText = when {
                        daysLeft <= 0 -> "imekaribia kuoza!"
                        daysLeft == 1 -> "siku 1 kabla ya kuoza"
                        else -> "siku $daysLeft kabla ya kuoza"
                    }
                    "$riskEmoji $productName: ${remaining.toInt()} $unit — $dayText (imenunuliwa $purchaseDate)"
                } else {
                    "$riskEmoji $productName: ${remaining.toInt()} $unit | spoil≈$expectedSpoil | $status"
                }
            }

            ToolResult.success(
                toolName = name,
                data = items.map { mapOf(
                    "product" to it.product,
                    "remaining" to it.quantityRemaining,
                    "unit" to it.unit,
                    "purchase_date" to it.purchaseDate,
                    "expected_spoil" to it.expectedSpoilDate,
                    "status" to it.status
                )},
                message = lines.joinToString("\n")
            )
        } catch (e: Exception) {
            Timber.e(e, "checkExpiry failed")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    /**
     * Records a new perishable inventory purchase and calculates
     * expected spoil date from spoilage profiles.
     */
    private fun addStock(
        product: String,
        quantity: Double,
        totalPrice: Double,
        params: Map<String, String>
    ): ToolResult {
        val unit = params["unit"] ?: "kg"
        val storage = params["storage"] ?: "ambient"
        val useVoice = params["voice"]?.toBoolean() ?: true
        val pricePerUnit = totalPrice / quantity

        val db = dbHelper.writableDatabase

        // Look up spoilage profile
        val profile = querySpoilageProfile(db, product)
        val shelfDays = profile?.shelfLifeMax ?: 3.0 // default 3 days
        val markdownCurve = profile?.markdownCurve

        // Calculate expected spoil date
        val cal = Calendar.getInstance()
        cal.time = Date(now)
        cal.add(Calendar.DAY_OF_YEAR, shelfDays.toInt().coerceAtLeast(1))
        val expectedSpoil = dateFormat.format(cal.time)

        // Insert inventory record
        val cv = ContentValues().apply {
            put("product_name", product.lowercase())
            put("quantity_purchased", quantity)
            put("unit", unit)
            put("purchase_price_total", totalPrice)
            put("purchase_price_per_unit", pricePerUnit)
            put("quantity_remaining", quantity)
            put("purchase_date", today)
            put("purchase_time", timeFormat.format(Date(now)))
            put("expected_spoil_date", expectedSpoil)
            put("storage_type", storage)
            put("status", "active")
            put("created_at", now)
            put("updated_at", now)
        }
        val id = db.insert(TABLE_INVENTORY, null, cv)

        // Build voice response
        val pricePerUnitInt = pricePerUnit.toInt()
        val shelfDaysInt = shelfDays.toInt().coerceAtLeast(1)

        // Markdown suggestion from curve
        val markdownAdvice = buildMarkdownAdvice(product, pricePerUnit, shelfDaysInt, markdownCurve, useVoice)

        val msg = if (useVoice) {
            """Sawa. Kilo ${quantity.toInt()} za $product, $pricePerUnitInt kwa $unit.
${emoji("clock")} $product huchukua siku $shelfDaysInt kabla ya kuharibika.
$markdownAdvice
Ukibaki na zaidi ya ${ (quantity * 0.3).toInt() } $unit baada ya siku ${shelfDaysInt - 1}, uza kwa jumla au piga simu kwa hoteli za karibu — nitakupa namba.""".trimIndent()
        } else {
            "Added: $product ${quantity.toInt()} $unit @ $pricePerUnitInt/$unit. Spoil≈$expectedSpoil (${shelfDaysInt}d). $markdownAdvice"
        }

        return ToolResult.success(
            toolName = name,
            data = mapOf(
                "inventory_id" to id,
                "product" to product,
                "quantity" to quantity,
                "unit" to unit,
                "price_per_unit" to pricePerUnit,
                "expected_spoil" to expectedSpoil,
                "shelf_days" to shelfDaysInt
            ),
            message = msg
        )
    }

    // ──────────────────────────────────────────────
    // 2. markdown_suggest — Price recommendation
    // ──────────────────────────────────────────────

    /**
     * Recommends a markdown price for a product based on
     * days since purchase, spoilage curve, and time of day.
     *
     * Voice: "Niuzie bei gani sasa?"
     */
    private fun markdownSuggest(params: Map<String, String>): ToolResult {
        return try {
            val product = params["product"]
            val useVoice = params["voice"]?.toBoolean() ?: true
            val db = dbHelper.readableDatabase

            if (product != null) {
                // Markdown for a specific product
                val inventory = queryActiveInventory(db, product).firstOrNull()
                    ?: return ToolResult.error(name, "Hakuna $product kwenye hifadhi. Ongeza kwanza.", "NOT_IN_INVENTORY")

                val profile = querySpoilageProfile(db, product)
                val pricePerUnit = inventory.purchasePricePerUnit
                val daysSincePurchase = daysSince(inventory.purchaseDate)
                val shelfDays = profile?.shelfLifeMax ?: 3.0
                val curve = profile?.markdownCurve

                val suggestedPrice = calculateMarkdownPrice(
                    pricePerUnit, daysSincePurchase, shelfDays, curve
                )
                val percentage = ((suggestedPrice / pricePerUnit) * 100).toInt()

                val remaining = inventory.quantityRemaining
                val msg = if (useVoice) {
                    val dayText = if (daysSincePurchase == 0) "leo" else "siku $daysSincePurchase baada ya kununua"
                    """${product.replaceFirstChar { it.uppercase() }} — $dayText:
Bei ya kununua: ${pricePerUnit.toInt()} kwa ${inventory.unit}
Pendekezo la bei ya sasa: ${suggestedPrice.toInt()} kwa ${inventory.unit} ($percentage% ya bei ya awali)
${remaining.toInt()} ${inventory.unit} bado kwenye hifadhi.
${urgencyAdvice(daysSincePurchase, shelfDays, useVoice)}""".trimIndent()
                } else {
                    "$product: buy=${pricePerUnit.toInt()}, suggest=${suggestedPrice.toInt()} ($percentage%), remaining=${remaining.toInt()} ${inventory.unit}, day=$daysSincePurchase/${shelfDays.toInt()}"
                }

                return ToolResult.success(
                    toolName = name,
                    data = mapOf(
                        "product" to product,
                        "purchase_price" to pricePerUnit,
                        "suggested_price" to suggestedPrice,
                        "percentage" to percentage,
                        "days_since_purchase" to daysSincePurchase,
                        "remaining" to remaining
                    ),
                    message = msg
                )
            }

            // All products
            val items = queryActiveInventory(db, null)
            if (items.isEmpty()) {
                return ToolResult.success(name, message = if (useVoice)
                    "Hakuna bidhaa kwenye hifadhi." else "No inventory.")
            }

            val lines = items.map { inv ->
                val profile = querySpoilageProfile(db, inv.product)
                val daysSincePurchase = daysSince(inv.purchaseDate)
                val shelfDays = profile?.shelfLifeMax ?: 3.0
                val curve = profile?.markdownCurve
                val suggested = calculateMarkdownPrice(
                    inv.purchasePricePerUnit, daysSincePurchase, shelfDays, curve
                )
                if (useVoice) {
                    "• ${inv.product}: uza kwa ${suggested.toInt()} kwa ${inv.unit} (${inv.quantityRemaining.toInt()} ${inv.unit} imebaki)"
                } else {
                    "${inv.product}: suggest=${suggested.toInt()}/${inv.unit} remaining=${inv.quantityRemaining.toInt()}"
                }
            }

            ToolResult.success(
                toolName = name,
                data = items.map { mapOf("product" to it.product, "remaining" to it.quantityRemaining) },
                message = lines.joinToString("\n")
            )
        } catch (e: Exception) {
            Timber.e(e, "markdownSuggest failed")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // 3. spoilage_risk — Full risk assessment
    // ──────────────────────────────────────────────

    /**
     * Assesses spoilage risk across all active inventory.
     * Returns risk level, days remaining, and recommended action.
     *
     * Voice: "Bidhaa zangu zikoje?"
     */
    private fun spoilageRisk(params: Map<String, String>): ToolResult {
        return try {
            val product = params["product"]
            val useVoice = params["voice"]?.toBoolean() ?: true
            val db = dbHelper.readableDatabase

            val items = queryActiveInventory(db, product)
            if (items.isEmpty()) {
                return ToolResult.success(name, message = if (useVoice)
                    "Hakuna bidhaa kwenye hifadhi. Safi!" else "No inventory to assess.")
            }

            var totalWasteRisk = 0.0
            val lines = items.map { inv ->
                val profile = querySpoilageProfile(db, inv.product)
                val daysSincePurchase = daysSince(inv.purchaseDate)
                val shelfDays = profile?.shelfLifeMax ?: 3.0
                val daysLeft = (shelfDays - daysSincePurchase).coerceAtLeast(0.0)
                val riskPct = (daysSincePurchase / shelfDays).coerceIn(0.0, 1.0)

                val riskLevel = when {
                    riskPct >= 1.0 -> "HIGH"
                    riskPct >= 0.7 -> "MEDIUM"
                    else -> "LOW"
                }
                val emoji = when (riskLevel) {
                    "HIGH" -> "🔴"
                    "MEDIUM" -> "🟡"
                    else -> "🟢"
                }

                val potentialLoss = inv.quantityRemaining * inv.purchasePricePerUnit * riskPct
                totalWasteRisk += potentialLoss

                val action = when {
                    daysLeft <= 0 -> if (useVoice) "UZA SASA — bei yoyote" else "SELL_NOW"
                    daysLeft <= 1 -> if (useVoice) "Punguza bei leo" else "MARKDOWN_TODAY"
                    else -> if (useVoice) "Endelea kuuza bei ya kawaida" else "NORMAL_PRICING"
                }

                if (useVoice) {
                    val dayWord = when {
                        daysLeft <= 0 -> "imepitwa na wakati!"
                        daysLeft == 1.0 -> "siku 1 imebaki"
                        else -> "siku ${daysLeft.toInt()} zimebaki"
                    }
                    "$emoji ${inv.product}: ${inv.quantityRemaining.toInt()} ${inv.unit} — $dayWord. $action"
                } else {
                    "$emoji ${inv.product}: remaining=${inv.quantityRemaining} ${inv.unit}, daysLeft=${daysLeft.toInt()}, risk=$riskLevel, potentialLoss=${potentialLoss.toInt()}"
                }
            }

            val summary = if (useVoice) {
                "\n${emoji("warning")} Jumla ya hatari ya hasara: KES ${totalWasteRisk.toInt()}"
            } else {
                "\nTotal waste risk: KES ${totalWasteRisk.toInt()}"
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "items" to items.map { mapOf(
                        "product" to it.product,
                        "remaining" to it.quantityRemaining,
                        "days_since_purchase" to daysSince(it.purchaseDate)
                    )},
                    "total_waste_risk_kes" to totalWasteRisk
                ),
                message = lines.joinToString("\n") + summary
            )
        } catch (e: Exception) {
            Timber.e(e, "spoilageRisk failed")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // 4. donate_connect — Bulk buyer directory
    // ──────────────────────────────────────────────

    /**
     * Finds bulk buyers (restaurants, hotels, churches) in the
     * worker's county that accept perishable products.
     *
     * Voice: "Nina nyanya mingi, nifanye nini?"
     */
    private fun donateConnect(params: Map<String, String>): ToolResult {
        return try {
            val product = params["product"]
            val county = params["county"] ?: "Nairobi"
            val buyerType = params["buyer_type"]
            val useVoice = params["voice"]?.toBoolean() ?: true
            val db = dbHelper.readableDatabase

            val buyers = queryBulkBuyers(db, county, buyerType, product)

            if (buyers.isEmpty()) {
                val msg = if (useVoice) {
                    "Hakuna wanunuzi wa jumla waliopatikana kaunti ya $county. Jaribu kaunti nyingine au ongeza mwenyewe."
                } else {
                    "No bulk buyers found in $county."
                }
                return ToolResult.success(name, message = msg)
            }

            val lines = if (useVoice) {
                listOf("Wanunuzi wa jumla $county:") + buyers.map { b ->
                    val typeLabel = when (b.buyerType) {
                        "restaurant" -> "Hoteli"
                        "hotel" -> "Hoteli kubwa"
                        "church" -> "Kanisa"
                        "school" -> "Shule"
                        "caterer" -> "Mtu wa upishi"
                        else -> b.buyerType
                    }
                    "• ${b.buyerName} ($typeLabel) — ${b.phone ?: "simu haijulikani"}, wakati: ${b.preferredTime ?: "masaa yote"}, bei: ~${b.typicalPricePct}% ya retail"
                }
            } else {
                buyers.map { b ->
                    "${b.buyerName} | ${b.buyerType} | ${b.phone ?: "N/A"} | ${b.preferredTime ?: "any"} | ${b.typicalPricePct}%"
                }
            }

            // Also show markdown advice for remaining stock
            val productItems = if (product != null) {
                queryActiveInventory(db, product)
            } else {
                queryActiveInventory(db, null)
            }

            val extraAdvice = if (useVoice && productItems.isNotEmpty()) {
                val totalRemaining = productItems.sumOf { it.quantityRemaining }
                "\n${emoji("bulb")} Una $totalRemaining ${productItems.first().unit} bado. Piga simu — wanunuzi hawa wanununua kwa bei ya jumla."
            } else ""

            ToolResult.success(
                toolName = name,
                data = buyers.map { mapOf(
                    "name" to it.buyerName,
                    "type" to it.buyerType,
                    "phone" to it.phone,
                    "preferred_time" to it.preferredTime,
                    "typical_price_pct" to it.typicalPricePct
                )},
                message = lines.joinToString("\n") + extraAdvice
            )
        } catch (e: Exception) {
            Timber.e(e, "donateConnect failed")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // 5. waste_log — Record actual waste
    // ──────────────────────────────────────────────

    /**
     * Records actual spoilage/waste for a product.
     * Updates inventory remaining and logs the waste event.
     *
     * Voice: "Nyanya 2 zimeharibika"
     */
    private fun logWaste(params: Map<String, String>): ToolResult {
        return try {
            val product = params["product"]
                ?: return ToolResult.error(name, "Product name required. Sema: '[bidhaa] [idadi] zimeharibika'", "MISSING_PRODUCT")
            val quantity = params["quantity"]?.toDoubleOrNull()
                ?: return ToolResult.error(name, "Quantity required. Sema: '[bidhaa] [idadi] zimeharibika'", "MISSING_QUANTITY")
            val useVoice = params["voice"]?.toBoolean() ?: true

            val db = dbHelper.writableDatabase

            // Find active inventory for this product
            val inventory = queryActiveInventory(db, product).firstOrNull()
                ?: return ToolResult.error(name, "Hakuna $product kwenye hifadhi.", "NOT_IN_INVENTORY")

            if (quantity > inventory.quantityRemaining) {
                return ToolResult.error(
                    name,
                    "Idadi ni zaidi ya iliyobaki. ${product}: ${inventory.quantityRemaining.toInt()} ${inventory.unit} imebaki.",
                    "EXCEEDS_REMAINING"
                )
            }

            // Update inventory remaining
            val newRemaining = inventory.quantityRemaining - quantity
            val newStatus = if (newRemaining <= 0) "wasted" else inventory.status

            val cv = ContentValues().apply {
                put("quantity_remaining", newRemaining)
                put("status", newStatus)
                put("updated_at", now)
            }
            db.update(TABLE_INVENTORY, cv, "id = ?", arrayOf(inventory.id.toString()))

            // Log waste event
            val wasteCost = quantity * inventory.purchasePricePerUnit
            val wcv = ContentValues().apply {
                put("product_name", product.lowercase())
                put("quantity_wasted", quantity)
                put("unit", inventory.unit)
                put("waste_cost", wasteCost)
                put("purchase_price_per_unit", inventory.purchasePricePerUnit)
                put("reason", "spoilage")
                put("waste_date", today)
                put("created_at", now)
            }
            db.insert(TABLE_WASTE_LOG, null, wcv)

            // Update status to 'wasted' if fully depleted
            if (newRemaining <= 0) {
                val scv = ContentValues().apply { put("status", "wasted") }
                db.update(TABLE_INVENTORY, scv, "id = ?", arrayOf(inventory.id.toString()))
            }

            val msg = if (useVoice) {
                """${emoji("warning")} Imerekodi: ${quantity.toInt()} ${inventory.unit} za $product zimeharibika.
Gharama: KES ${wasteCost.toInt()}
${product.replaceFirstChar { it.uppercase() }} imebaki: ${newRemaining.toInt()} ${inventory.unit}
${if (newRemaining <= 0) "Bidhaa imeisha." else "Endelea kuuza zilizobaki kabla hazijaharibika."}""".trimIndent()
            } else {
                "Waste logged: $product ${quantity.toInt()} ${inventory.unit} = KES ${wasteCost.toInt()}. Remaining: ${newRemaining.toInt()}"
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "product" to product,
                    "quantity_wasted" to quantity,
                    "waste_cost" to wasteCost,
                    "remaining" to newRemaining
                ),
                message = msg
            )
        } catch (e: Exception) {
            Timber.e(e, "logWaste failed")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // 6. summary — Daily waste vs. sales report
    // ──────────────────────────────────────────────

    /**
     * Generates a daily report of waste, markdown savings,
     * and remaining inventory value.
     *
     * Voice: "Ripoti ya leo"
     */
    private fun dailySummary(params: Map<String, String>): ToolResult {
        return try {
            val useVoice = params["voice"]?.toBoolean() ?: true
            val db = dbHelper.readableDatabase

            // Today's waste
            val wasteToday = queryWasteLog(db, today)
            val totalWasteCost = wasteToday.sumOf { it.wasteCost }
            val totalWasteQty = wasteToday.sumOf { it.quantityWasted }

            // Active inventory value
            val activeItems = queryActiveInventory(db, null)
            val totalInventoryValue = activeItems.sumOf { it.quantityRemaining * it.purchasePricePerUnit }
            val totalInventoryItems = activeItems.size

            // Risk items (spoil today or already overdue)
            val riskItems = activeItems.filter { inv ->
                val profile = querySpoilageProfile(db, inv.product)
                val shelfDays = profile?.shelfLifeMax ?: 3.0
                val daysSincePurchase = daysSince(inv.purchaseDate)
                daysSincePurchase >= shelfDays - 1
            }

            // Past 7 days waste trend
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, -7)
            val weekAgo = dateFormat.format(cal.time)
            val weekWaste = queryWasteLogRange(db, weekAgo, today)
            val weekWasteCost = weekWaste.sumOf { it.wasteCost }

            val msg = if (useVoice) {
                val wasteDetails = if (wasteToday.isNotEmpty()) {
                    wasteToday.joinToString("\n") { w ->
                        "  • ${w.productName}: ${w.quantityWasted.toInt()} ${w.unit} — KES ${w.wasteCost.toInt()}"
                    }
                } else {
                    "  Hakuna hasara leo! 🎉"
                }

                val riskWarning = if (riskItems.isNotEmpty()) {
                    "\n${emoji("warning")} Bidhaa hatarini kesho:\n" + riskItems.joinToString("\n") { inv ->
                        "  • ${inv.product}: ${inv.quantityRemaining.toInt()} ${inv.unit} — punguza bei au uza kwa jumla"
                    }
                } else ""

                """${emoji("chart")} Ripoti ya leo — $today:
${emoji("money")} Gharama ya kuoza leo: KES ${totalWasteCost.toInt()}
$wasteDetails
${emoji("package")} Bidhaa kwenye hifadhi: $totalInventoryItems, thamani KES ${totalInventoryValue.toInt()}
${emoji("calendar")} Hasara ya wiki hii: KES ${weekWasteCost.toInt()}
$riskWarning""".trimIndent()
            } else {
                buildString {
                    appendLine("=== Daily Summary $today ===")
                    appendLine("Waste today: KES ${totalWasteCost.toInt()} (${totalWasteQty.toInt()} items)")
                    wasteToday.forEach { w ->
                        appendLine("  ${w.productName}: ${w.quantityWasted.toInt()} ${w.unit} = KES ${w.wasteCost.toInt()}")
                    }
                    appendLine("Active inventory: $totalInventoryItems items, KES ${totalInventoryValue.toInt()}")
                    appendLine("Week waste: KES ${weekWasteCost.toInt()}")
                    if (riskItems.isNotEmpty()) {
                        appendLine("AT RISK:")
                        riskItems.forEach { appendLine("  ${it.product}: ${it.quantityRemaining.toInt()} ${it.unit}") }
                    }
                }.trimEnd()
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "date" to today,
                    "waste_cost_today" to totalWasteCost,
                    "waste_items_today" to wasteToday.size,
                    "active_inventory_value" to totalInventoryValue,
                    "active_inventory_count" to totalInventoryItems,
                    "week_waste_cost" to weekWasteCost,
                    "risk_items" to riskItems.map { it.product }
                ),
                message = msg
            )
        } catch (e: Exception) {
            Timber.e(e, "dailySummary failed")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // Markdown calculation engine
    // ──────────────────────────────────────────────

    /**
     * Calculates the recommended selling price based on days since
     * purchase, shelf life, and the product's markdown curve.
     *
     * Uses a linear interpolation between curve points.
     * If no curve, uses a default: 100% → 75% → 45% → 15%
     */
    private fun calculateMarkdownPrice(
        purchasePricePerUnit: Double,
        daysSincePurchase: Int,
        shelfLifeDays: Double,
        markdownCurve: String?
    ): Double {
        val curve = parseMarkdownCurve(markdownCurve)
            ?: mapOf("day1_pct" to 100, "day2_pct" to 75, "day3_pct" to 45, "day4_pct" to 15)

        val dayKey = "day${daysSincePurchase + 1}_pct"
        val percentage = curve[dayKey] ?: run {
            // Interpolate or extrapolate
            val maxDay = curve.keys.mapNotNull { it.removePrefix("day").removeSuffix("_pct").toIntOrNull() }.maxOrNull() ?: 4
            val lastPct = curve["day${maxDay}_pct"] ?: 15
            when {
                daysSincePurchase + 1 > maxDay -> lastPct
                else -> 100 // default to full price
            }
        }

        return purchasePricePerUnit * (percentage / 100.0)
    }

    /**
     * Builds a markdown advice string from the curve, showing
     * the full schedule for the coming days.
     */
    private fun buildMarkdownAdvice(
        product: String,
        purchasePrice: Double,
        shelfDays: Int,
        markdownCurve: String?,
        useVoice: Boolean
    ): String {
        val curve = parseMarkdownCurve(markdownCurve)
            ?: mapOf("day1_pct" to 100, "day2_pct" to 75, "day3_pct" to 45, "day4_pct" to 15)

        val schedule = (0 until shelfDays.coerceAtMost(5)).map { day ->
            val dayKey = "day${day + 1}_pct"
            val pct = curve[dayKey] ?: 15
            val price = purchasePrice * (pct / 100.0)
            val dayLabel = if (day == 0) "Leo" else "Siku ${day + 1}"
            if (useVoice) {
                "  $dayLabel: ${price.toInt()} kwa unit ($pct%)"
            } else {
                "  day${day + 1}: ${price.toInt()} ($pct%)"
            }
        }

        return if (useVoice) {
            "Mpango wa bei:\n${schedule.joinToString("\n")}"
        } else {
            "Markdown: ${schedule.joinToString(", ")}"
        }
    }

    /**
     * Parses the JSON markdown curve string.
     * Format: {"day1_pct":100,"day2_pct":75,"day3_pct":45}
     * Returns null if parsing fails.
     */
    private fun parseMarkdownCurve(json: String?): Map<String, Int>? {
        if (json.isNullOrBlank()) return null
        return try {
            json.removeSurrounding("{", "}")
                .split(",")
                .mapNotNull { entry ->
                    val parts = entry.split(":")
                    if (parts.size == 2) {
                        val key = parts[0].trim().removeSurrounding("\"")
                        val value = parts[1].trim().removeSurrounding("\"").toIntOrNull()
                        if (value != null) key to value else null
                    } else null
                }
                .toMap()
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse markdown curve: $json")
            null
        }
    }

    // ──────────────────────────────────────────────
    // Urgency / voice helpers
    // ──────────────────────────────────────────────

    private fun urgencyAdvice(daysSincePurchase: Int, shelfDays: Double, useVoice: Boolean): String {
        val daysLeft = (shelfDays - daysSincePurchase).coerceAtLeast(0.0)
        return if (useVoice) {
            when {
                daysLeft <= 0 -> "${emoji("warning")} Imepita wakati! Uza sasa hata kwa bei ya chini."
                daysLeft <= 1 -> "${emoji("warning")} Kesho jioni, punguza bei zaidi. Ukibaki na zaidi, uza kwa jumla."
                daysLeft <= 2 -> "${emoji("clock")} Siku ${daysLeft.toInt()} zimebaki. Weka bei ya kawaida leo, punguza kesho."
                else -> "${emoji("check")} Bado iko fresh. Endelea kuuza bei ya kawaida."
            }
        } else ""
    }

    private fun emoji(key: String): String = when (key) {
        "warning" -> "⚠️"
        "clock" -> "⏰"
        "money" -> "💰"
        "chart" -> "📊"
        "package" -> "📦"
        "calendar" -> "📅"
        "bulb" -> "💡"
        "check" -> "✅"
        else -> ""
    }

    private fun daysSince(dateStr: String): Int {
        return try {
            val purchase = dateFormat.parse(dateStr) ?: return 0
            val diff = now - purchase.time
            TimeUnit.MILLISECONDS.toDays(diff).toInt()
        } catch (e: Exception) { 0 }
    }

    private fun daysUntil(dateStr: String?): Int {
        if (dateStr == null) return -1
        return try {
            val target = dateFormat.parse(dateStr) ?: return -1
            val diff = target.time - now
            TimeUnit.MILLISECONDS.toDays(diff).toInt()
        } catch (e: Exception) { -1 }
    }

    // ──────────────────────────────────────────────
    // Database query helpers
    // ──────────────────────────────────────────────

    private data class InventoryRow(
        val id: Long,
        val product: String,
        val quantityPurchased: Double,
        val unit: String,
        val purchasePriceTotal: Double,
        val purchasePricePerUnit: Double,
        val quantityRemaining: Double,
        val purchaseDate: String,
        val expectedSpoilDate: String?,
        val status: String
    )

    private data class SpoilageProfile(
        val shelfLifeMin: Double,
        val shelfLifeMax: Double,
        val markdownCurve: String?
    )

    private data class WasteLogRow(
        val productName: String,
        val quantityWasted: Double,
        val unit: String,
        val wasteCost: Double,
        val wasteDate: String
    )

    private data class BulkBuyerRow(
        val buyerName: String,
        val buyerType: String,
        val phone: String?,
        val preferredTime: String?,
        val typicalPricePct: Int
    )

    private fun queryActiveInventory(db: SQLiteDatabase, product: String?): List<InventoryRow> {
        val selection = if (product != null) {
            "product_name = ? AND status IN ('active', 'markdown')"
        } else {
            "status IN ('active', 'markdown')"
        }
        val selArgs = if (product != null) arrayOf(product.lowercase()) else null

        val rows = mutableListOf<InventoryRow>()
        val cursor = db.query(TABLE_INVENTORY, null, selection, selArgs, null, null, "expected_spoil_date ASC")
        cursor.use {
            while (it.moveToNext()) {
                rows.add(cursorToInventory(it))
            }
        }
        return rows
    }

    private fun cursorToInventory(c: Cursor): InventoryRow = InventoryRow(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        product = c.getString(c.getColumnIndexOrThrow("product_name")),
        quantityPurchased = c.getDouble(c.getColumnIndexOrThrow("quantity_purchased")),
        unit = c.getString(c.getColumnIndexOrThrow("unit")),
        purchasePriceTotal = c.getDouble(c.getColumnIndexOrThrow("purchase_price_total")),
        purchasePricePerUnit = c.getDouble(c.getColumnIndexOrThrow("purchase_price_per_unit")),
        quantityRemaining = c.getDouble(c.getColumnIndexOrThrow("quantity_remaining")),
        purchaseDate = c.getString(c.getColumnIndexOrThrow("purchase_date")),
        expectedSpoilDate = c.getString(c.getColumnIndexOrThrow("expected_spoil_date")),
        status = c.getString(c.getColumnIndexOrThrow("status"))
    )

    private fun querySpoilageProfile(db: SQLiteDatabase, product: String): SpoilageProfile? {
        val cursor = db.query(
            TABLE_PROFILES, null,
            "product_name = ?", arrayOf(product.lowercase()),
            null, null, null
        )
        cursor.use {
            return if (it.moveToFirst()) {
                SpoilageProfile(
                    shelfLifeMin = it.getDouble(it.getColumnIndexOrThrow("shelf_life_days_min")),
                    shelfLifeMax = it.getDouble(it.getColumnIndexOrThrow("shelf_life_days_max")),
                    markdownCurve = it.getString(it.getColumnIndexOrThrow("markdown_curve"))
                )
            } else null
        }
    }

    private fun queryWasteLog(db: SQLiteDatabase, date: String): List<WasteLogRow> {
        val rows = mutableListOf<WasteLogRow>()
        val cursor = db.query(TABLE_WASTE_LOG, null, "waste_date = ?", arrayOf(date), null, null, "created_at DESC")
        cursor.use {
            while (it.moveToNext()) {
                rows.add(WasteLogRow(
                    productName = it.getString(it.getColumnIndexOrThrow("product_name")),
                    quantityWasted = it.getDouble(it.getColumnIndexOrThrow("quantity_wasted")),
                    unit = it.getString(it.getColumnIndexOrThrow("unit")),
                    wasteCost = it.getDouble(it.getColumnIndexOrThrow("waste_cost")),
                    wasteDate = it.getString(it.getColumnIndexOrThrow("waste_date"))
                ))
            }
        }
        return rows
    }

    private fun queryWasteLogRange(db: SQLiteDatabase, from: String, to: String): List<WasteLogRow> {
        val rows = mutableListOf<WasteLogRow>()
        val cursor = db.query(
            TABLE_WASTE_LOG, null,
            "waste_date BETWEEN ? AND ?", arrayOf(from, to),
            null, null, "waste_date ASC"
        )
        cursor.use {
            while (it.moveToNext()) {
                rows.add(WasteLogRow(
                    productName = it.getString(it.getColumnIndexOrThrow("product_name")),
                    quantityWasted = it.getDouble(it.getColumnIndexOrThrow("quantity_wasted")),
                    unit = it.getString(it.getColumnIndexOrThrow("unit")),
                    wasteCost = it.getDouble(it.getColumnIndexOrThrow("waste_cost")),
                    wasteDate = it.getString(it.getColumnIndexOrThrow("waste_date"))
                ))
            }
        }
        return rows
    }

    private fun queryBulkBuyers(
        db: SQLiteDatabase,
        county: String,
        buyerType: String?,
        product: String?
    ): List<BulkBuyerRow> {
        val selectionParts = mutableListOf("county = ?")
        val args = mutableListOf(county)

        if (buyerType != null) {
            selectionParts.add("buyer_type = ?")
            args.add(buyerType)
        }
        // Product filter uses LIKE on products_accepted JSON column
        if (product != null) {
            selectionParts.add("products_accepted LIKE ?")
            args.add("%${product.lowercase()}%")
        }

        val rows = mutableListOf<BulkBuyerRow>()
        val cursor = db.query(
            TABLE_BULK_BUYERS, null,
            selectionParts.joinToString(" AND "),
            args.toTypedArray(),
            null, null, "rating DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                rows.add(BulkBuyerRow(
                    buyerName = it.getString(it.getColumnIndexOrThrow("buyer_name")),
                    buyerType = it.getString(it.getColumnIndexOrThrow("buyer_type")),
                    phone = it.getString(it.getColumnIndexOrThrow("phone")),
                    preferredTime = it.getString(it.getColumnIndexOrThrow("preferred_time")),
                    typicalPricePct = it.getInt(it.getColumnIndexOrThrow("typical_price_pct"))
                ))
            }
        }
        return rows
    }

    // ──────────────────────────────────────────────
    // Seed data
    // ──────────────────────────────────────────────

    private fun seedProfiles(db: SQLiteDatabase) {
        val seeds = listOf(
            Triple("nyanya", Pair(2.0, 4.0), """{"day1_pct":100,"day2_pct":75,"day3_pct":45,"day4_pct":15}"""),
            Triple("sukuma wiki", Pair(1.0, 3.0), """{"day1_pct":100,"day2_pct":60,"day3_pct":25}"""),
            Triple("machungwa", Pair(5.0, 10.0), """{"day1_pct":100,"day2_pct":95,"day3_pct":85,"day4_pct":75,"day5_pct":60}"""),
            Triple("mango", Pair(3.0, 7.0), """{"day1_pct":100,"day2_pct":90,"day3_pct":75,"day4_pct":55,"day5_pct":35}"""),
            Triple("samaki", Pair(0.5, 1.0), """{"day1_pct":100,"day2_pct":30}"""),
            Triple("nyama", Pair(1.0, 2.0), """{"day1_pct":100,"day2_pct":50,"day3_pct":10}"""),
            Triple("ndizi", Pair(3.0, 6.0), """{"day1_pct":100,"day2_pct":90,"day3_pct":75,"day4_pct":55,"day5_pct":30}"""),
            Triple("vitunguu", Pair(7.0, 14.0), """{"day1_pct":100,"day2_pct":98,"day3_pct":95,"day4_pct":90,"day5_pct":85}"""),
            Triple("piripiri", Pair(2.0, 5.0), """{"day1_pct":100,"day2_pct":80,"day3_pct":55,"day4_pct":30}"""),
            Triple("avocado", Pair(2.0, 5.0), """{"day1_pct":100,"day2_pct":85,"day3_pct":60,"day4_pct":35}"""),
            Triple("maziwa", Pair(1.0, 2.0), """{"day1_pct":100,"day2_pct":50,"day3_pct":10}"""),
            Triple("mayai", Pair(7.0, 14.0), """{"day1_pct":100,"day2_pct":98,"day3_pct":95,"day4_pct":90,"day5_pct":85}""")
        )

        seeds.forEach { (name, shelf, curve) ->
            val cv = ContentValues().apply {
                put("product_name", name)
                put("shelf_life_days_min", shelf.first)
                put("shelf_life_days_max", shelf.second)
                put("markdown_curve", curve)
                put("unit", "kg")
                put("storage_type", "ambient")
                put("created_at", now)
                put("updated_at", now)
            }
            db.insert(TABLE_PROFILES, null, cv)
        }
        Timber.d("Seeded ${seeds.size} spoilage profiles")
    }

    private fun seedBulkBuyers(db: SQLiteDatabase) {
        // name, type, products, location, phone, time, minQty, pricePct, rating, county
        val buyers = listOf(
            arrayOf("Hoteli ya Mama Njeri", "restaurant", """["nyama","samaki","mboga","nyanya"]""", "Kibera", "0712345678", "16:00-18:00", 50, 4.0, 3.5, "Nairobi"),
            arrayOf("Hoteli ya Upendo", "restaurant", """["nyama","samaki","mboga"]""", "Kawangware", "0723456789", "15:00-19:00", 45, 3.5, 3.0, "Nairobi"),
            arrayOf("Kanisa la Amani", "church", """["mboga","nyanya","sukuma wiki"]""", "Eastlands", "0734567890", "08:00-12:00", 60, 4.5, 4.0, "Nairobi"),
            arrayOf("Shule ya Msingi Huruma", "school", """["mboga","nyanya","sukuma wiki","ndizi"]""", "Huruma", "0745678901", "06:00-10:00", 55, 4.0, 3.5, "Nairobi"),
            arrayOf("Bwana Catering", "caterer", """["nyama","samaki","mboga","nyanya","machungwa"]""", "CBD", "0756789012", "14:00-17:00", 40, 3.0, 2.5, "Nairobi"),
            arrayOf("Hoteli ya Bahari", "hotel", """["samaki","nyama","mboga","machungwa"]""", "Mombasa CBD", "0767890123", "15:00-18:00", 50, 4.0, 3.5, "Mombasa"),
            arrayOf("Mama Lishe wa Migori", "restaurant", """["mboga","nyanya","sukuma wiki","ndizi"]""", "Migori Town", "0778901234", "16:00-19:00", 45, 3.5, 3.0, "Migori")
        )

        buyers.forEach { cols ->
            val cv = ContentValues().apply {
                put("buyer_name", cols[0] as String)
                put("buyer_type", cols[1] as String)
                put("products_accepted", cols[2] as String)
                put("location_name", cols[3] as String)
                put("phone", cols[4] as String)
                put("preferred_time", cols[5] as String)
                put("min_quantity", cols[6] as Int)
                put("typical_price_pct", cols[7] as Double)
                put("rating", cols[8] as Double)
                put("county", cols[9] as String)
                put("created_at", now)
                put("updated_at", now)
            }
            db.insert(TABLE_BULK_BUYERS, null, cv)
        }
        Timber.d("Seeded ${buyers.size} bulk buyers")
    }

    // ──────────────────────────────────────────────
    // Schema constants
    // ──────────────────────────────────────────────

    companion object {
        private const val DB_NAME = "waste_reducer.db"
        private const val DB_VERSION = 1

        private const val TABLE_PROFILES = "spoilage_profiles"
        private const val TABLE_INVENTORY = "perishable_inventory"
        private const val TABLE_WASTE_LOG = "waste_log"
        private const val TABLE_BULK_BUYERS = "bulk_buyers"

        private val SQL_CREATE_PROFILES = """
            CREATE TABLE $TABLE_PROFILES (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                product_name TEXT NOT NULL UNIQUE,
                shelf_life_days_min REAL NOT NULL,
                shelf_life_days_max REAL NOT NULL,
                optimal_temp_celsius REAL,
                markdown_curve TEXT,
                unit TEXT DEFAULT 'kg',
                storage_type TEXT DEFAULT 'ambient',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """.trimIndent()

        private val SQL_CREATE_INVENTORY = """
            CREATE TABLE $TABLE_INVENTORY (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                product_name TEXT NOT NULL,
                quantity_purchased REAL NOT NULL,
                unit TEXT DEFAULT 'kg',
                purchase_price_total REAL NOT NULL,
                purchase_price_per_unit REAL NOT NULL,
                quantity_remaining REAL NOT NULL,
                purchase_date TEXT NOT NULL,
                purchase_time TEXT,
                expected_spoil_date TEXT,
                storage_type TEXT DEFAULT 'ambient',
                status TEXT DEFAULT 'active',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """.trimIndent()

        private val SQL_CREATE_WASTE_LOG = """
            CREATE TABLE $TABLE_WASTE_LOG (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                product_name TEXT NOT NULL,
                quantity_wasted REAL NOT NULL,
                unit TEXT DEFAULT 'kg',
                waste_cost REAL NOT NULL,
                purchase_price_per_unit REAL,
                reason TEXT DEFAULT 'spoilage',
                waste_date TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
        """.trimIndent()

        private val SQL_CREATE_BULK_BUYERS = """
            CREATE TABLE $TABLE_BULK_BUYERS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                buyer_name TEXT NOT NULL,
                buyer_type TEXT NOT NULL,
                products_accepted TEXT,
                location_name TEXT,
                latitude REAL,
                longitude REAL,
                phone TEXT,
                preferred_time TEXT,
                min_quantity REAL,
                typical_price_pct REAL DEFAULT 50,
                rating REAL DEFAULT 3.0,
                county TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """.trimIndent()
    }
}
