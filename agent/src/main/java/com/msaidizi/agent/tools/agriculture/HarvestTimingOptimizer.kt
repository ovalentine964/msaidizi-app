package com.msaidizi.agent.tools.agriculture

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext
import com.msaidizi.agent.tools.core.*

/**
 * HarvestTimingOptimizer — Fix 2: Harvest timing optimization for farmers.
 *
 * Problem: Farmers sell immediately post-harvest when prices are 30-50% lower
 * than lean season prices. They lack tools to decide: sell now vs. store and sell later.
 *
 * Solution: A decision engine that factors in:
 *   - Current price vs. predicted future price
 *   - Storage costs (bags, facility rental)
 *   - Spoilage risk (crop-specific, storage method)
 *   - Price trends (historical seasonal patterns)
 *   - Cash flow needs (school fees, debts, daily expenses)
 *
 * Voice examples:
 *   "Niuze sasa au nihifadhi?"                  → Sell now or store?
 *   "Bei ya mahindi itapanda lini?"             → When will price rise?
 *   "Niuze nusu sasa na nusu baadaye?"          → Split selling strategy
 *   "Gharama ya kuhifadhi ni ngapi?"            → Storage cost estimate
 */
@Singleton
class HarvestTimingOptimizer @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val name = "harvest_timing_optimizer"
    override val description = "Helps farmers decide when to sell harvest: now vs. store for later. Factors in price trends, storage costs, spoilage risk, and cash needs."

    override val argsSchema = argSchema {
        enum(
            "action", "Action to perform",
            listOf(
                "optimize",          // Main decision: sell now or store?
                "price_forecast",    // Price prediction for coming weeks/months
                "storage_cost",      // Calculate storage costs
                "split_strategy",    // Sell X% now, store Y% for later
                "compare_options",   // Compare: sell all now vs. store all vs. split
                "set_cash_need",     // Set urgent cash requirement
                "record_price",      // Record current market price
                "price_history"      // Show historical price patterns
            ),
            required = true
        )
        string("product", "Crop/product (mahindi, maharagwe, nyanya, etc.)", required = false)
        number("quantity_kg", "Total quantity in kg", required = false)
        number("current_price", "Current market price KES/kg", required = false)
        number("storage_cost_per_kg", "Storage cost per kg per month (KES)", required = false)
        number("cash_need", "Urgent cash need in KES", required = false)
        string("storage_method", "Storage method: hermetic/bags/silo/open/none", required = false)
        integer("storage_months", "Planned storage duration in months", required = false)
        boolean("voice", "Format for voice output (Swahili)", required = false)
    }

    // ──────────────────────────────────────────────
    // Price History Database
    // ──────────────────────────────────────────────

    inner class PriceHistoryDatabase(context: Context) :
        SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            // Price records — one row per product per market per observation
            db.execSQL("""
                CREATE TABLE $TABLE_PRICES (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    product TEXT NOT NULL,
                    market TEXT,
                    price_per_kg REAL NOT NULL,
                    source TEXT DEFAULT 'observation',
                    recorded_at INTEGER NOT NULL
                )
            """)

            // Cash needs — farmer's urgent financial requirements
            db.execSQL("""
                CREATE TABLE $TABLE_CASH_NEEDS (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    amount REAL NOT NULL,
                    reason TEXT,
                    deadline INTEGER,
                    recorded_at INTEGER NOT NULL
                )
            """)

            // Storage decisions — track what farmer decided
            db.execSQL("""
                CREATE TABLE $TABLE_DECISIONS (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    product TEXT NOT NULL,
                    total_kg REAL NOT NULL,
                    sold_kg REAL NOT NULL,
                    stored_kg REAL NOT NULL,
                    sell_price REAL NOT NULL,
                    expected_future_price REAL,
                    storage_method TEXT,
                    storage_cost REAL,
                    cash_need REAL,
                    recorded_at INTEGER NOT NULL
                )
            """)

            db.execSQL("CREATE INDEX idx_prices_product ON $TABLE_PRICES(product, recorded_at)")
            db.execSQL("CREATE INDEX idx_decisions_product ON $TABLE_DECISIONS(product)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_PRICES")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_CASH_NEEDS")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_DECISIONS")
            onCreate(db)
        }
    }

    companion object {
        private const val DB_NAME = "harvest_timing.db"
        private const val DB_VERSION = 1
        private const val TABLE_PRICES = "price_history"
        private const val TABLE_CASH_NEEDS = "cash_needs"
        private const val TABLE_DECISIONS = "storage_decisions"

        // Kenya crop-specific spoilage rates (% per month) by storage method
        val SPOILAGE_RATES = mapOf(
            "maize" to mapOf(
                "hermetic" to 1.0,      // PICS bags: ~1% per month
                "silo" to 2.0,          // Metal silo: ~2%
                "bags" to 5.0,          // Regular bags: ~5%
                "open" to 15.0,         // Open storage: ~15%
                "none" to 25.0          // No storage: ~25%
            ),
            "beans" to mapOf(
                "hermetic" to 0.5,
                "silo" to 1.5,
                "bags" to 3.0,
                "open" to 10.0,
                "none" to 20.0
            ),
            "tomatoes" to mapOf(
                "hermetic" to 30.0,     // Tomatoes rot fast even with good storage
                "silo" to 25.0,
                "bags" to 50.0,
                "open" to 70.0,
                "none" to 90.0
            ),
            "rice" to mapOf(
                "hermetic" to 0.5,
                "silo" to 1.0,
                "bags" to 2.0,
                "open" to 8.0,
                "none" to 15.0
            ),
            "coffee" to mapOf(
                "hermetic" to 0.3,
                "silo" to 0.5,
                "bags" to 1.0,
                "open" to 3.0,
                "none" to 5.0
            ),
            "tea" to mapOf(
                "hermetic" to 0.2,
                "silo" to 0.5,
                "bags" to 1.0,
                "open" to 2.0,
                "none" to 3.0
            )
        )

        // Storage costs per kg per month (KES)
        val STORAGE_COSTS = mapOf(
            "hermetic" to 2.0,      // ~KES 200 per 90kg bag = ~2.2/kg
            "silo" to 1.5,          // Metal silo amortized
            "bags" to 0.5,          // Regular polypropylene bags
            "open" to 0.0,          // No cost (but high spoilage)
            "none" to 0.0
        )

        // Kenya seasonal price patterns — multipliers relative to annual average
        // Based on NCPB, KALRO, and World Bank data
        val SEASONAL_PRICE_MULTIPLIERS = mapOf(
            "maize" to mapOf(
                1 to 1.25,   // Jan: lean season, high prices
                2 to 1.30,   // Feb: peak lean
                3 to 1.20,   // Mar: still high
                4 to 1.10,   // Apr: long rains start
                5 to 0.95,   // May: early harvest trickle
                6 to 0.80,   // Jun: main harvest begins
                7 to 0.70,   // Jul: peak harvest
                8 to 0.75,   // Aug: harvest continues
                9 to 0.85,   // Sep: harvest ending
                10 to 0.90,  // Oct: short rains planting
                11 to 1.00,  // Nov: prices normalize
                12 to 1.15   // Dec: prices rising
            ),
            "beans" to mapOf(
                1 to 1.20, 2 to 1.25, 3 to 1.15, 4 to 1.05,
                5 to 0.90, 6 to 0.80, 7 to 0.75, 8 to 0.80,
                9 to 0.90, 10 to 1.00, 11 to 1.05, 12 to 1.15
            ),
            "tomatoes" to mapOf(
                1 to 1.10, 2 to 1.15, 3 to 1.20, 4 to 1.10,
                5 to 0.90, 6 to 0.80, 7 to 0.85, 8 to 0.90,
                9 to 1.00, 10 to 1.10, 11 to 1.15, 12 to 1.05
            ),
            "rice" to mapOf(
                1 to 1.15, 2 to 1.20, 3 to 1.10, 4 to 1.00,
                5 to 0.90, 6 to 0.85, 7 to 0.80, 8 to 0.85,
                9 to 0.90, 10 to 1.00, 11 to 1.05, 12 to 1.10
            ),
            "coffee" to mapOf(
                1 to 1.10, 2 to 1.15, 3 to 1.20, 4 to 1.15,
                5 to 1.05, 6 to 0.95, 7 to 0.90, 8 to 0.85,
                9 to 0.90, 10 to 0.95, 11 to 1.00, 12 to 1.05
            ),
            "tea" to mapOf(
                1 to 1.05, 2 to 1.10, 3 to 1.10, 4 to 1.05,
                5 to 1.00, 6 to 0.95, 7 to 0.90, 8 to 0.95,
                9 to 1.00, 10 to 1.00, 11 to 1.05, 12 to 1.05
            )
        )
    }

    private var dbHelper: PriceHistoryDatabase? = null

    private fun getDb(): SQLiteDatabase {
        if (dbHelper == null) dbHelper = PriceHistoryDatabase(context)
        return dbHelper!!.writableDatabase
    }

    // ──────────────────────────────────────────────
    // Tool Execute
    // ──────────────────────────────────────────────

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"]
            ?: return ToolResult.error(name, "Action required", "MISSING_ACTION")

        return when (action.lowercase()) {
            "optimize" -> optimize(params)
            "price_forecast" -> priceForecast(params)
            "storage_cost" -> storageCostCalc(params)
            "split_strategy" -> splitStrategy(params)
            "compare_options" -> compareOptions(params)
            "set_cash_need" -> setCashNeed(params)
            "record_price" -> recordPrice(params)
            "price_history" -> priceHistory(params)
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    // ──────────────────────────────────────────────
    // ACTION: optimize — Main sell-or-store decision
    // ──────────────────────────────────────────────

    private fun optimize(params: Map<String, String>): ToolResult {
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val db = getDb()

        val rawProduct = params["product"]
            ?: return ToolResult.error(name, "Product required", "MISSING_PRODUCT")
        val product = normalizeProduct(rawProduct)
        val quantityKg = params["quantity_kg"]?.toDoubleOrNull()
            ?: return ToolResult.error(name, "quantity_kg required", "MISSING_QUANTITY")
        val currentPrice = params["current_price"]?.toDoubleOrNull()
            ?: return ToolResult.error(name, "current_price required (KES/kg)", "MISSING_PRICE")
        val storageMethod = params["storage_method"] ?: "hermetic"
        val cashNeed = params["cash_need"]?.toDoubleOrNull() ?: 0.0

        val currentMonth = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1

        // Get seasonal price multiplier for 2-3 months from now
        val multipliers = SEASONAL_PRICE_MULTIPLIERS[product] ?: SEASONAL_PRICE_MULTIPLIERS["maize"]!!
        val currentMultiplier = multipliers[currentMonth] ?: 1.0
        val futureMonth = ((currentMonth + 2 - 1) % 12) + 1  // 3 months ahead
        val futureMultiplier = multipliers[futureMonth] ?: 1.0

        // Estimate future price
        val avgPrice = currentPrice / currentMultiplier  // Derive annual average
        val predictedFuturePrice = avgPrice * futureMultiplier

        // Storage costs
        val storageCostPerKg = STORAGE_COSTS[storageMethod] ?: 0.0
        val totalStorageCost = storageCostPerKg * quantityKg * 3  // 3 months

        // Spoilage risk
        val spoilageRate = SPOILAGE_RATES[product]?.get(storageMethod) ?: 5.0
        val spoilageLossKg = quantityKg * (spoilageRate / 100.0) * 3  // 3 months
        val spoilageLossValue = spoilageLossKg * predictedFuturePrice

        // Net gain from storing
        val sellNowValue = quantityKg * currentPrice
        val storedQuantity = quantityKg - spoilageLossKg
        val storeValue = storedQuantity * predictedFuturePrice - totalStorageCost
        val netGainFromStoring = storeValue - sellNowValue
        val gainPct = if (sellNowValue > 0) (netGainFromStoring / sellNowValue * 100) else 0.0

        // Cash need analysis
        val cashNeedMet = cashNeed <= sellNowValue * 0.5  // Can meet need by selling half

        // Decision
        val recommendation = when {
            cashNeed > sellNowValue * 0.7 -> "sell_now"  // Urgent cash need
            netGainFromStoring > sellNowValue * 0.15 -> "store"     // >15% gain from storing
            netGainFromStoring > 0 -> "split"                        // Some gain, split is safest
            else -> "sell_now"                                        // Storing not worth it
        }

        val message = if (voice) {
            buildString {
                appendLine("🔍 *Uchunguzi: Niuze Sasa au Nihifadhi?*")
                appendLine("🌽 $product: ${formatQty(quantityKg)} kg")
                appendLine()
                appendLine("📊 *Uchambuzi:*")
                appendLine("   Bei ya sasa: KES ${formatPrice(currentPrice)}/kg")
                appendLine("   Bei ya baadaye (miezi 3): KES ${formatPrice(predictedFuturePrice)}/kg")
                val priceChange = ((predictedFuturePrice - currentPrice) / currentPrice * 100).toInt()
                appendLine("   Mabadiliko: ${if (priceChange > 0) "+" else ""}$priceChange%")
                appendLine()
                appendLine("💰 *Kuuza sasa:*")
                appendLine("   Mapato: KES ${formatPrice(sellNowValue)}")
                appendLine()
                appendLine("📦 *Kuhifadhi (miezi 3, $storageMethod):*")
                appendLine("   Gharama ya kuhifadhi: KES ${formatPrice(totalStorageCost)}")
                appendLine("   Hasara ya kuoza: ${formatQty(spoilageLossKg)} kg (${spoilageRate.toInt()}%/mwezi)")
                appendLine("   Mapato baada ya gharama: KES ${formatPrice(storeValue)}")
                appendLine("   Faida ya ziada: KES ${formatPrice(netGainFromStoring)} (${gainPct.toInt()}%)")
                appendLine()

                when (recommendation) {
                    "sell_now" -> {
                        if (cashNeed > sellNowValue * 0.7) {
                            appendLine("🚨 *Ushauri: Uza sasa!*")
                            appendLine("   Unahitaji KES ${formatPrice(cashNeed)} — pesa za haraka ni muhimu zaidi.")
                        } else {
                            appendLine("💡 *Ushauri: Uza sasa.*")
                            appendLine("   Faida ya kuhifadhi ni ndogo — si thamani ya hatari ya kuoza.")
                        }
                    }
                    "store" -> {
                        appendLine("💡 *Ushauri: Hifadhi na uza baadaye!*")
                        appendLine("   Utapata KES ${formatPrice(netGainFromStoring)} zaidi kwa kusubiri miezi 3.")
                        appendLine("   Tumia mifuko ya hermetic kupunguza kuoza.")
                    }
                    "split" -> {
                        val sellNow = quantityKg * 0.5
                        val storeLater = quantityKg * 0.5
                        appendLine("💡 *Ushauri: Kata! Uza nusu sasa, hifadhi nusu.*")
                        appendLine("   Uza ${formatQty(sellNow)} kg sasa: KES ${formatPrice(sellNow * currentPrice)}")
                        appendLine("   Hifadhi ${formatQty(storeLater)} kg kwa miezi 3: ~KES ${formatPrice(storeLater * predictedFuturePrice * 0.95)}")
                        appendLine("   Hii inakupa pesa za sasa + faida ya baadaye.")
                    }
                }

                if (cashNeed > 0 && cashNeed < sellNowValue * 0.5) {
                    appendLine()
                    appendLine("📌 *Kumbuka:* Unahitaji KES ${formatPrice(cashNeed)}.")
                    appendLine("   Uza ${formatQty(cashNeed / currentPrice)} kg sasa kujaza haja, hifadhi iliyobaki.")
                }
            }
        } else {
            buildString {
                appendLine("Sell-or-Store Analysis — $product:")
                appendLine("Current price: KES ${formatPrice(currentPrice)}/kg")
                appendLine("Predicted price (3mo): KES ${formatPrice(predictedFuturePrice)}/kg")
                appendLine("Sell now value: KES ${formatPrice(sellNowValue)}")
                appendLine("Store value (after costs/spoilage): KES ${formatPrice(storeValue)}")
                appendLine("Net gain from storing: KES ${formatPrice(netGainFromStoring)} (${gainPct.toInt()}%)")
                appendLine("Recommendation: $recommendation")
            }
        }

        // Save decision
        val decisionValues = ContentValues().apply {
            put("product", product)
            put("total_kg", quantityKg)
            put("sold_kg", if (recommendation == "sell_now") quantityKg else if (recommendation == "split") quantityKg * 0.5 else 0.0)
            put("stored_kg", if (recommendation == "store") quantityKg else if (recommendation == "split") quantityKg * 0.5 else 0.0)
            put("sell_price", currentPrice)
            put("expected_future_price", predictedFuturePrice)
            put("storage_method", storageMethod)
            put("storage_cost", totalStorageCost)
            put("cash_need", cashNeed)
            put("recorded_at", System.currentTimeMillis())
        }
        db.insert(TABLE_DECISIONS, null, decisionValues)

        return ToolResult.success(
            name,
            mapOf(
                "product" to product, "quantity_kg" to quantityKg,
                "current_price" to currentPrice, "predicted_future_price" to predictedFuturePrice,
                "sell_now_value" to sellNowValue, "store_value" to storeValue,
                "net_gain" to netGainFromStoring, "gain_pct" to gainPct,
                "recommendation" to recommendation,
                "storage_cost" to totalStorageCost, "spoilage_loss_kg" to spoilageLossKg,
                "cash_need" to cashNeed
            ),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: price_forecast — Price prediction
    // ──────────────────────────────────────────────

    private fun priceForecast(params: Map<String, String>): ToolResult {
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val rawProduct = params["product"]
            ?: return ToolResult.error(name, "Product required", "MISSING_PRODUCT")
        val product = normalizeProduct(rawProduct)
        val currentPrice = params["current_price"]?.toDoubleOrNull() ?: run {
            // Try to get from DB
            val db = getDb()
            getLatestPrice(db, product) ?: return ToolResult.error(name, "current_price required", "MISSING_PRICE")
        }

        val currentMonth = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1
        val multipliers = SEASONAL_PRICE_MULTIPLIERS[product] ?: SEASONAL_PRICE_MULTIPLIERS["maize"]!!
        val currentMultiplier = multipliers[currentMonth] ?: 1.0
        val avgPrice = currentPrice / currentMultiplier

        val swahiliMonths = arrayOf("", "Januari", "Februari", "Machi", "Aprili", "Mei", "Juni",
            "Julai", "Agosti", "Septemba", "Oktoba", "Novemba", "Desemba")

        // Generate 6-month forecast
        val forecast = (0..5).map { offset ->
            val month = ((currentMonth - 1 + offset) % 12) + 1
            val mult = multipliers[month] ?: 1.0
            val predictedPrice = avgPrice * mult
            val changePct = ((predictedPrice - currentPrice) / currentPrice * 100).toInt()
            Triple(month, predictedPrice, changePct)
        }

        // Find best selling month
        val bestMonth = forecast.maxByOrNull { it.second }!!
        val worstMonth = forecast.minByOrNull { it.second }!!

        val message = if (voice) {
            buildString {
                appendLine("📈 *Utabiri wa bei ya $product (miezi 6)*")
                appendLine("Bei ya sasa: KES ${formatPrice(currentPrice)}/kg")
                appendLine()
                forecast.forEach { (month, price, change) ->
                    val arrow = if (change > 0) "↑" else if (change < 0) "↓" else "→"
                    val indicator = if (month == bestMonth.first) " 🏆" else if (month == worstMonth.first) " ⚠️" else ""
                    appendLine("• ${swahiliMonths[month]}: KES ${formatPrice(price)}/kg ($arrow ${if (change > 0) "+" else ""}$change%)$indicator")
                }
                appendLine()
                appendLine("🏆 Mwezi bora wa kuuza: ${swahiliMonths[bestMonth.first]} (KES ${formatPrice(bestMonth.second)}/kg)")
                appendLine("⚠️ Mwezi mbaya: ${swahiliMonths[worstMonth.first]} (KES ${formatPrice(worstMonth.second)}/kg)")
                val spread = ((bestMonth.second - worstMonth.second) / worstMonth.second * 100).toInt()
                appendLine("Tofauti: $spread% — kuuza ${swahiliMonths[bestMonth.first]} kunaweza kukupa pesa nyingi zaidi!")
            }
        } else {
            buildString {
                appendLine("$product price forecast (6 months):")
                forecast.forEach { (month, price, change) ->
                    appendLine("${swahiliMonths[month]}: KES ${formatPrice(price)}/kg (${if (change > 0) "+" else ""}$change%)")
                }
                appendLine("Best: ${swahiliMonths[bestMonth.first]} @ KES ${formatPrice(bestMonth.second)}/kg")
            }
        }

        return ToolResult.success(
            name,
            mapOf(
                "product" to product, "current_price" to currentPrice,
                "forecast" to forecast.map { mapOf("month" to it.first, "price" to it.second, "change_pct" to it.third) },
                "best_month" to bestMonth.first, "best_price" to bestMonth.second
            ),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: storage_cost — Calculate storage costs
    // ──────────────────────────────────────────────

    private fun storageCostCalc(params: Map<String, String>): ToolResult {
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val quantityKg = params["quantity_kg"]?.toDoubleOrNull()
            ?: return ToolResult.error(name, "quantity_kg required", "MISSING_QUANTITY")
        val storageMethod = params["storage_method"] ?: "hermetic"
        val months = params["storage_months"]?.toIntOrNull() ?: 3
        val rawProduct = params["product"] ?: "maize"
        val product = normalizeProduct(rawProduct)

        val costPerKg = params["storage_cost_per_kg"]?.toDoubleOrNull() ?: (STORAGE_COSTS[storageMethod] ?: 0.0)
        val totalCost = costPerKg * quantityKg * months

        val spoilageRate = SPOILAGE_RATES[product]?.get(storageMethod) ?: 5.0
        val spoilageLossKg = quantityKg * (spoilageRate / 100.0) * months
        val spoilagePct = spoilageRate * months

        val message = if (voice) {
            buildString {
                appendLine("📦 *Gharama ya Kuhifadhi*")
                appendLine("Mbinu: $storageMethod")
                appendLine("Kiasi: ${formatQty(quantityKg)} kg")
                appendLine("Muda: miezi $months")
                appendLine()
                appendLine("💰 Gharama:")
                appendLine("   KES ${formatPrice(costPerKg)}/kg × ${formatQty(quantityKg)} kg × $months miezi")
                appendLine("   = KES ${formatPrice(totalCost)}")
                appendLine()
                appendLine("⚠️ Hatari ya kuoza:")
                appendLine("   Kiwango: ${spoilageRate.toInt()}%/mwezi")
                appendLine("   Hasara ya miezi $months: ${formatQty(spoilageLossKg)} kg (${spoilagePct.toInt()}%)")
                appendLine()

                // Compare storage methods
                appendLine("📊 *Linganisha mbinu:*")
                SPOILAGE_RATES[product]?.entries?.sortedBy { it.value }?.forEach { (method, rate) ->
                    val cost = (STORAGE_COSTS[method] ?: 0.0) * quantityKg * months
                    val loss = quantityKg * (rate / 100.0) * months
                    val emoji = when (method) {
                        storageMethod -> "➡️"
                        "hermetic" -> "🏆"
                        else -> "  "
                    }
                    appendLine("   $emoji $method: KES ${formatPrice(cost)} gharama, ${rate.toInt()}%/mwezi kuoza")
                }
            }
        } else {
            buildString {
                appendLine("Storage cost ($storageMethod, $months months):")
                appendLine("Cost: KES ${formatPrice(costPerKg)}/kg × ${formatQty(quantityKg)} kg × $months = KES ${formatPrice(totalCost)}")
                appendLine("Spoilage: ${spoilageRate.toInt()}%/month → ${formatQty(spoilageLossKg)} kg lost (${spoilagePct.toInt()}%)")
            }
        }

        return ToolResult.success(
            name,
            mapOf(
                "storage_method" to storageMethod, "quantity_kg" to quantityKg,
                "months" to months, "cost_per_kg" to costPerKg,
                "total_cost" to totalCost, "spoilage_rate" to spoilageRate,
                "spoilage_loss_kg" to spoilageLossKg, "spoilage_pct" to spoilagePct
            ),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: split_strategy — Sell X% now, store Y%
    // ──────────────────────────────────────────────

    private fun splitStrategy(params: Map<String, String>): ToolResult {
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val db = getDb()

        val rawProduct = params["product"]
            ?: return ToolResult.error(name, "Product required", "MISSING_PRODUCT")
        val product = normalizeProduct(rawProduct)
        val quantityKg = params["quantity_kg"]?.toDoubleOrNull()
            ?: return ToolResult.error(name, "quantity_kg required", "MISSING_QUANTITY")
        val currentPrice = params["current_price"]?.toDoubleOrNull()
            ?: return ToolResult.error(name, "current_price required", "MISSING_PRICE")
        val cashNeed = params["cash_need"]?.toDoubleOrNull() ?: 0.0
        val storageMethod = params["storage_method"] ?: "hermetic"

        // Calculate optimal split
        val sellNowValue = quantityKg * currentPrice

        // If cash need exists, sell enough to cover it
        val cashNeedKg = if (cashNeed > 0) {
            minOf(quantityKg, (cashNeed / currentPrice * 1.1).toInt().toDouble()) // 10% buffer
        } else 0.0

        // Default split: 40% sell now, 60% store (conservative)
        val defaultSellPct = if (cashNeed > 0) {
            minOf(0.7, cashNeedKg / quantityKg)  // At least cover cash need
        } else 0.4

        val sellNowKg = quantityKg * defaultSellPct
        val storeKg = quantityKg - sellNowKg

        // Future value of stored portion
        val currentMonth = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1
        val multipliers = SEASONAL_PRICE_MULTIPLIERS[product] ?: SEASONAL_PRICE_MULTIPLIERS["maize"]!!
        val currentMultiplier = multipliers[currentMonth] ?: 1.0
        val avgPrice = currentPrice / currentMultiplier
        val futureMonth = ((currentMonth + 2 - 1) % 12) + 1
        val futurePrice = avgPrice * (multipliers[futureMonth] ?: 1.0)

        val spoilageRate = SPOILAGE_RATES[product]?.get(storageMethod) ?: 5.0
        val spoilageLossKg = storeKg * (spoilageRate / 100.0) * 3
        val netStoredKg = storeKg - spoilageLossKg
        val storageCostPerKg = STORAGE_COSTS[storageMethod] ?: 0.0
        val storageCost = storageCostPerKg * storeKg * 3

        val sellNowIncome = sellNowKg * currentPrice
        val storeIncome = netStoredKg * futurePrice - storageCost
        val totalIncome = sellNowIncome + storeIncome
        val allNowIncome = quantityKg * currentPrice
        val gainVsAllNow = totalIncome - allNowIncome

        val message = if (voice) {
            buildString {
                appendLine("📊 *Mpango wa Kuuza kwa Awamu*")
                appendLine("🌽 $product: ${formatQty(quantityKg)} kg")
                appendLine()
                appendLine("🔹 *Awamu 1 — Uza sasa:*")
                appendLine("   ${formatQty(sellNowKg)} kg × KES ${formatPrice(currentPrice)} = KES ${formatPrice(sellNowIncome)}")
                if (cashNeed > 0) {
                    appendLine("   ✅ Inatosheka kwa haja ya KES ${formatPrice(cashNeed)}")
                }
                appendLine()
                appendLine("🔹 *Awamu 2 — Hifadhi miezi 3:*")
                appendLine("   ${formatQty(storeKg)} kg → baada ya kuoza: ${formatQty(netStoredKg)} kg")
                appendLine("   Bei ya baadaye: KES ${formatPrice(futurePrice)}/kg")
                appendLine("   Mapato: KES ${formatPrice(storeIncome)}")
                appendLine("   Gharama ya kuhifadhi: KES ${formatPrice(storageCost)}")
                appendLine()
                appendLine("💰 *Jumla:*")
                appendLine("   Kuuza kwa awamu: KES ${formatPrice(totalIncome)}")
                appendLine("   Kuuza yote sasa: KES ${formatPrice(allNowIncome)}")
                if (gainVsAllNow > 0) {
                    appendLine("   ✅ Faida ya kuuza kwa awamu: KES ${formatPrice(gainVsAllNow)}")
                } else {
                    appendLine("   ⚠️ Kuuza yote sasa ni bora zaidi")
                }
            }
        } else {
            buildString {
                appendLine("Split Strategy — $product:")
                appendLine("Sell now: ${formatQty(sellNowKg)} kg → KES ${formatPrice(sellNowIncome)}")
                appendLine("Store 3mo: ${formatQty(storeKg)} kg → KES ${formatPrice(storeIncome)}")
                appendLine("Total: KES ${formatPrice(totalIncome)} vs all-now: KES ${formatPrice(allNowIncome)}")
                appendLine("Gain from split: KES ${formatPrice(gainVsAllNow)}")
            }
        }

        return ToolResult.success(
            name,
            mapOf(
                "sell_now_kg" to sellNowKg, "sell_now_income" to sellNowIncome,
                "store_kg" to storeKg, "store_income" to storeIncome,
                "total_income" to totalIncome, "all_now_income" to allNowIncome,
                "gain_vs_all_now" to gainVsAllNow,
                "future_price" to futurePrice, "storage_cost" to storageCost,
                "spoilage_loss_kg" to spoilageLossKg
            ),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: compare_options — Compare all strategies
    // ──────────────────────────────────────────────

    private fun compareOptions(params: Map<String, String>): ToolResult {
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val rawProduct = params["product"]
            ?: return ToolResult.error(name, "Product required", "MISSING_PRODUCT")
        val product = normalizeProduct(rawProduct)
        val quantityKg = params["quantity_kg"]?.toDoubleOrNull()
            ?: return ToolResult.error(name, "quantity_kg required", "MISSING_QUANTITY")
        val currentPrice = params["current_price"]?.toDoubleOrNull()
            ?: return ToolResult.error(name, "current_price required", "MISSING_PRICE")

        val currentMonth = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1
        val multipliers = SEASONAL_PRICE_MULTIPLIERS[product] ?: SEASONAL_PRICE_MULTIPLIERS["maize"]!!
        val currentMultiplier = multipliers[currentMonth] ?: 1.0
        val avgPrice = currentPrice / currentMultiplier
        val futureMonth = ((currentMonth + 2 - 1) % 12) + 1
        val futurePrice = avgPrice * (multipliers[futureMonth] ?: 1.0)

        // Option 1: Sell all now
        val option1 = quantityKg * currentPrice

        // Option 2: Store all (hermetic)
        val spoilage2 = quantityKg * (1.0 / 100.0) * 3  // hermetic: 1%/mo
        val cost2 = 2.0 * quantityKg * 3
        val option2 = (quantityKg - spoilage2) * futurePrice - cost2

        // Option 3: Store all (regular bags)
        val spoilage3 = quantityKg * (5.0 / 100.0) * 3  // bags: 5%/mo
        val cost3 = 0.5 * quantityKg * 3
        val option3 = (quantityKg - spoilage3) * futurePrice - cost3

        // Option 4: Split 50/50 (hermetic)
        val sell50 = quantityKg * 0.5 * currentPrice
        val store50kg = quantityKg * 0.5
        val spoilage4 = store50kg * (1.0 / 100.0) * 3
        val cost4 = 2.0 * store50kg * 3
        val option4 = sell50 + (store50kg - spoilage4) * futurePrice - cost4

        val best = maxOf(option1, option2, option3, option4)
        val bestOption = when (best) {
            option1 -> "sell_all_now"
            option2 -> "store_hermetic"
            option3 -> "store_bags"
            option4 -> "split_50_50"
            else -> "unknown"
        }

        val message = if (voice) {
            buildString {
                appendLine("📊 *Linganisha Chaguo — $product*")
                appendLine("Kiasi: ${formatQty(quantityKg)} kg")
                appendLine("Bei ya sasa: KES ${formatPrice(currentPrice)}/kg")
                appendLine("Bei ya baadaye: KES ${formatPrice(futurePrice)}/kg")
                appendLine()
                appendLine("1️⃣ Uza yote sasa:")
                appendLine("   Mapato: KES ${formatPrice(option1)}")
                appendLine()
                appendLine("2️⃣ Hifadhi yote (mifuko ya hermetic):")
                appendLine("   Mapato: KES ${formatPrice(option2)}")
                appendLine("   Gharama: KES ${formatPrice(cost2)} | Kuoza: 1%/mwezi")
                appendLine()
                appendLine("3️⃣ Hifadhi yote (mifuko ya kawaida):")
                appendLine("   Mapato: KES ${formatPrice(option3)}")
                appendLine("   Gharama: KES ${formatPrice(cost3)} | Kuoza: 5%/mwezi")
                appendLine()
                appendLine("4️⃣ Kata: 50% sasa + 50% baadaye (hermetic):")
                appendLine("   Mapato: KES ${formatPrice(option4)}")
                appendLine()
                val bestName = when (bestOption) {
                    "sell_all_now" -> "Uza yote sasa"
                    "store_hermetic" -> "Hifadhi yote (hermetic)"
                    "store_bags" -> "Hifadhi yote (mifuko ya kawaida)"
                    "split_50_50" -> "Kata: 50% sasa + 50% baadaye"
                    else -> bestOption
                }
                appendLine("🏆 *Bora: $bestName — KES ${formatPrice(best)}*")
            }
        } else {
            buildString {
                appendLine("Compare Options — $product (${formatQty(quantityKg)} kg):")
                appendLine("1. Sell all now: KES ${formatPrice(option1)}")
                appendLine("2. Store hermetic: KES ${formatPrice(option2)}")
                appendLine("3. Store bags: KES ${formatPrice(option3)}")
                appendLine("4. Split 50/50: KES ${formatPrice(option4)}")
                appendLine("Best: $bestOption @ KES ${formatPrice(best)}")
            }
        }

        return ToolResult.success(
            name,
            mapOf(
                "sell_all_now" to option1, "store_hermetic" to option2,
                "store_bags" to option3, "split_50_50" to option4,
                "best_option" to bestOption, "best_value" to best
            ),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: set_cash_need — Set urgent cash requirement
    // ──────────────────────────────────────────────

    private fun setCashNeed(params: Map<String, String>): ToolResult {
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val db = getDb()
        val amount = params["cash_need"]?.toDoubleOrNull()
            ?: return ToolResult.error(name, "cash_need required (KES)", "MISSING_AMOUNT")
        val reason = params["notes"] ?: params["reason"]
        val now = System.currentTimeMillis()

        val values = ContentValues().apply {
            put("amount", amount)
            put("reason", reason)
            put("recorded_at", now)
        }
        db.insert(TABLE_CASH_NEEDS, null, values)

        val message = if (voice) {
            "✅ Haja ya pesa imerekodwa: KES ${formatPrice(amount)}${reason?.let { " ($it)" } ?: ""}.\n" +
            "Sasa nitakupa ushauri wa kuuza unaolenga kukidhi haja hii."
        } else {
            "Cash need recorded: KES ${formatPrice(amount)}${reason?.let { " ($it)" } ?: ""}"
        }

        return ToolResult.success(name, mapOf("amount" to amount, "reason" to reason), message)
    }

    // ──────────────────────────────────────────────
    // ACTION: record_price — Record market price
    // ──────────────────────────────────────────────

    private fun recordPrice(params: Map<String, String>): ToolResult {
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val db = getDb()
        val rawProduct = params["product"]
            ?: return ToolResult.error(name, "Product required", "MISSING_PRODUCT")
        val product = normalizeProduct(rawProduct)
        val price = params["current_price"]?.toDoubleOrNull()
            ?: return ToolResult.error(name, "current_price required", "MISSING_PRICE")
        val market = params["notes"] // reuse notes as market name
        val now = System.currentTimeMillis()

        val values = ContentValues().apply {
            put("product", product)
            put("market", market)
            put("price_per_kg", price)
            put("source", "farmer_observation")
            put("recorded_at", now)
        }
        db.insert(TABLE_PRICES, null, values)

        return ToolResult.success(
            name,
            mapOf("product" to product, "price" to price, "market" to market),
            if (voice) "✅ Bei yamerekodwa: $product KES ${formatPrice(price)}/kg. Asante!"
            else "Price recorded: $product KES ${formatPrice(price)}/kg"
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: price_history — Show historical prices
    // ──────────────────────────────────────────────

    private fun priceHistory(params: Map<String, String>): ToolResult {
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val db = getDb()
        val rawProduct = params["product"]
            ?: return ToolResult.error(name, "Product required", "MISSING_PRODUCT")
        val product = normalizeProduct(rawProduct)

        val prices = mutableListOf<Pair<Long, Double>>()
        val cursor = db.query(
            TABLE_PRICES, arrayOf("recorded_at", "price_per_kg"),
            "product = ?", arrayOf(product),
            null, null, "recorded_at DESC", "20"
        )
        cursor.use {
            while (it.moveToNext()) {
                prices.add(Pair(it.getLong(0), it.getDouble(1)))
            }
        }

        if (prices.isEmpty()) {
            return ToolResult.success(
                name, mapOf("product" to product, "prices" to emptyList<Any>()),
                if (voice) "Hakuna data ya bei ya $product. Anza na 'record_price'."
                else "No price history for $product. Use 'record_price' to start tracking."
            )
        }

        val sdf = java.text.SimpleDateFormat("dd MMM", java.util.Locale.getDefault())

        val message = if (voice) {
            buildString {
                appendLine("📈 Historia ya bei ya $product:")
                prices.take(10).forEach { (ts, price) ->
                    appendLine("• ${sdf.format(java.util.Date(ts))}: KES ${formatPrice(price)}/kg")
                }
            }
        } else {
            buildString {
                appendLine("$product price history:")
                prices.forEach { (ts, price) ->
                    appendLine("${sdf.format(java.util.Date(ts))}: KES ${formatPrice(price)}/kg")
                }
            }
        }

        return ToolResult.success(
            name,
            mapOf("product" to product, "count" to prices.size),
            message
        )
    }

    // ──────────────────────────────────────────────
    // Internal Helpers
    // ──────────────────────────────────────────────

    private fun getLatestPrice(db: SQLiteDatabase, product: String): Double? {
        val cursor = db.query(
            TABLE_PRICES, arrayOf("price_per_kg"),
            "product = ?", arrayOf(product),
            null, null, "recorded_at DESC", "1"
        )
        cursor.use { return if (it.moveToFirst()) it.getDouble(0) else null }
    }

    private fun normalizeProduct(raw: String): String {
        val aliases = mapOf(
            "mahindi" to "maize", "maize" to "maize", "corn" to "maize",
            "maharagwe" to "beans", "beans" to "beans",
            "nyanya" to "tomatoes", "tomatoes" to "tomatoes",
            "mchele" to "rice", "rice" to "rice",
            "kahawa" to "coffee", "coffee" to "coffee",
            "chai" to "tea", "tea" to "tea",
            "ngano" to "wheat", "wheat" to "wheat"
        )
        return aliases[raw.trim().lowercase()] ?: raw.trim().lowercase()
    }

    private fun formatPrice(price: Double): String {
        return if (price == price.toLong().toDouble()) "%,d".format(price.toLong()) else "%,.0f".format(price)
    }

    private fun formatQty(qty: Double): String {
        return if (qty == qty.toLong().toDouble()) "%,d".format(qty.toLong()) else "%,.1f".format(qty)
    }
}
