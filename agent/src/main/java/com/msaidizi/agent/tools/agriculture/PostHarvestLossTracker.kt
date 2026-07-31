package com.msaidizi.agent.tools.agriculture

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import com.msaidizi.agent.tools.core.*

/**
 * PostHarvestLossTracker — Track, analyze, and reduce post-harvest losses.
 *
 * Problem: Sub-Saharan Africa loses 30-40% of perishable produce after harvest.
 * For a farmer harvesting 10 bags of tomatoes, 3-4 bags rot before reaching market.
 * This tool logs every loss event, calculates loss percentages, identifies patterns,
 * and provides actionable storage/handling advice in Swahili.
 *
 * Voice examples:
 *   "Nimepoteza nyanya kilo 5 kwa kuoza"     → Logged: tomatoes, 5kg, spoilage
 *   "Hasara yangu ya mwezi huu"              → Monthly loss summary
 *   "Jinsi ya kuhifadhi mahindi"             → Storage advice
 *   "Kiasi gani nimepoteza?"                 → Total loss report
 */
@Singleton
class PostHarvestLossTracker @Inject constructor(
    private val context: Context
) : Tool {

    override val name = "post_harvest_loss_tracker"
    override val description = "Track post-harvest losses (spoilage, pests, transport damage), calculate loss percentages, and get storage improvement advice."

    override val argsSchema = argSchema {
        enum(
            "action", "Action to perform",
            listOf(
                "record_loss",       // Log a loss event
                "loss_report",       // Show loss summary by product/period
                "loss_breakdown",    // Breakdown by loss type
                "storage_advice",    // Get storage/handling tips
                "alerts",            // High loss rate alerts
                "compare_periods",   // Compare loss rates over time
                "tips",              // General loss reduction tips
                "set_threshold"      // Set alert threshold for high loss
            ),
            required = true
        )
        string("product", "Product/crop name", required = false)
        number("quantity", "Quantity lost", required = false)
        string("unit", "Unit (kg/gunia/litre)", required = false)
        string("loss_type", "Type: spoilage/pests/transport/weather/theft/quality", required = false)
        string("cause", "Specific cause of loss", required = false)
        string("period", "Time period: week/month/season/year", required = false)
        number("threshold_pct", "Loss percentage threshold for alerts", required = false)
        string("storage_method", "Current storage method for advice", required = false)
        boolean("voice", "Format for voice output", required = false)
    }

    // ──────────────────────────────────────────────
    // SQLite Database
    // ──────────────────────────────────────────────

    inner class LossDatabase(context: Context) :
        SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            // Loss events — one row per loss incident
            db.execSQL("""
                CREATE TABLE $TABLE_LOSSES (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    product TEXT NOT NULL,
                    quantity REAL NOT NULL,
                    unit TEXT NOT NULL DEFAULT 'kg',
                    loss_type TEXT NOT NULL,
                    cause TEXT,
                    harvest_id INTEGER,
                    notes TEXT,
                    recorded_at INTEGER NOT NULL
                )
            """)

            // Loss thresholds — alerts when loss rate exceeds threshold
            db.execSQL("""
                CREATE TABLE $TABLE_THRESHOLDS (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    product TEXT NOT NULL UNIQUE,
                    threshold_pct REAL NOT NULL DEFAULT 20.0,
                    is_active INTEGER NOT NULL DEFAULT 1,
                    created_at INTEGER NOT NULL
                )
            """)

            // Storage recommendations cache
            db.execSQL("""
                CREATE TABLE $TABLE_STORAGE_TIPS (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    product TEXT NOT NULL,
                    loss_type TEXT NOT NULL,
                    tip_sw TEXT NOT NULL,
                    tip_en TEXT NOT NULL,
                    effectiveness REAL DEFAULT 0.5
                )
            """)

            db.execSQL("CREATE INDEX idx_losses_product ON $TABLE_LOSSES(product)")
            db.execSQL("CREATE INDEX idx_losses_date ON $TABLE_LOSSES(recorded_at)")
            db.execSQL("CREATE INDEX idx_losses_type ON $TABLE_LOSSES(loss_type)")
            db.execSQL("CREATE INDEX idx_losses_product_date ON $TABLE_LOSSES(product, recorded_at)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_LOSSES")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_THRESHOLDS")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_STORAGE_TIPS")
            onCreate(db)
        }
    }

    companion object {
        private const val DB_NAME = "post_harvest_losses.db"
        private const val DB_VERSION = 1
        private const val TABLE_LOSSES = "losses"
        private const val TABLE_THRESHOLDS = "loss_thresholds"
        private const val TABLE_STORAGE_TIPS = "storage_tips"

        // Loss type categories with Swahili names
        val LOSS_TYPES = mapOf(
            "spoilage" to "Kuoza/Kuharibika",
            "pests" to "Wadudu/Wanyama",
            "transport" to "Usafirishaji",
            "weather" to "Hali ya Hewa",
            "theft" to "Wizi",
            "quality" to "Ubora duni",
            "drying" to "Kukausha",
            "handling" to "Kushughulikia"
        )

        // Storage tips database — Swahili-first
        val STORAGE_ADVICE = mapOf(
            "maize" to listOf(
                StorageTip("spoilage", "Kausha mahindi kabla ya kuhifadhi. Unyevu unapaswa kuwa chini ya 13%.", "Dry maize before storage. Moisture should be below 13%."),
                StorageTip("spoilage", "Hifadhi kwenye mifuko ya kavu, si plastiki. Plastiki inazuia hewa.", "Store in dry bags, not plastic. Plastic blocks air circulation."),
                StorageTip("pests", "Tumia mchanganyiko wa majivu na mahindi (1:20) au dawa ya asili.", "Use ash-maize mix (1:20 ratio) or natural pesticides."),
                StorageTip("pests", "Hifadhi kwenye pipa za chuma au mapipa yaliyosafishwa.", "Store in metal silos or cleaned drums."),
                StorageTip("weather", "Hakikisha ghorofa ni kavu kabla ya kuhifadhi.", "Ensure the granary is dry before storing."),
                StorageTip("transport", "Funga mifuko vizuri. Usiweke zaidi ya mifuko 5 juu ya kila mwingine.", "Tie bags properly. Don't stack more than 5 bags high.")
            ),
            "tomatoes" to listOf(
                StorageTip("spoilage", "Vuna wakati wa baridi (asubuhi). Jua linaharibika haraka.", "Harvest during cool hours (morning). Heat spoils quickly."),
                StorageTip("spoilage", "Usiweke jikoni au mahali pa jua. Hifadhi mahali pa baridi na kivuli.", "Don't store in kitchen or sun. Store in cool, shaded place."),
                StorageTip("spoilage", "Tenganisha nyanya zilizoza na nzima. Moja mbaya inaharibu zote.", "Separate rotten from fresh tomatoes. One bad one spoils all."),
                StorageTip("handling", "Usiweke juu ya kila nyingine — nyanya ni nyepesi kuharibika.", "Don't stack tomatoes — they bruise easily."),
                StorageTip("transport", "Safirisha kwenye vikapu, si mifuko. Hewa inahitajika.", "Transport in baskets, not bags. Air circulation needed.")
            ),
            "beans" to listOf(
                StorageTip("spoilage", "Kausha maharagwe kabisa kabla ya kuhifadhi. Haraka ya unyevu < 12%.", "Dry beans completely before storage. Moisture < 12%."),
                StorageTip("pests", "Weka karoti kavu kwenye mifuko — inawavutia wadudu badala ya maharagwe.", "Put dried carrot in bags — it attracts pests away from beans."),
                StorageTip("pests", "Hifadhi kwenye mapipa ya kavu na kuyafunika vizuri.", "Store in dry drums with tight lids.")
            ),
            "fish" to listOf(
                StorageTip("spoilage", "Barafu samaki mara moja baada ya kuvua. Samaki haina baridi = haribika 2-4 saa.", "Chill fish immediately after catch. Fish without ice spoils in 2-4 hours."),
                StorageTip("spoilage", "Tumia barafu au maji baridi. Kila saa bila baridi = hasara 10%.", "Use ice or cold water. Every hour without ice = 10% loss."),
                StorageTip("spoilage", "Ondoa matumbo kabla ya kuhifadhi — ndio sehemu inayooza kwanza.", "Gut fish before storing — innards spoil first."),
                StorageTip("transport", "Safirisha kwenye masanduku ya barafu, si mifuko ya plastiki.", "Transport in ice boxes, not plastic bags."),
                StorageTip("handling", "Usiweke samaki juu ya kila nyingine bila barafu kati.", "Don't stack fish without ice between layers.")
            ),
            "potatoes" to listOf(
                StorageTip("spoilage", "Hifadhi mahali pa baridi, kivuli, na kavu. Jua linazalisha sumu (solanine).", "Store in cool, dark, dry place. Sunlight produces toxin (solanine)."),
                StorageTip("spoilage", "Usiweke pamoja na vitunguu — gesi ya vitunguu inaharisha viazi.", "Don't store with onions — onion gas spoils potatoes."),
                StorageTip("sprouting", "Weka na jani la mtama — linazuia kuchipua.", "Store with sorghum leaves — prevents sprouting.")
            ),
            "kale" to listOf(
                StorageTip("spoilage", "Sukuma wiki huharibika ndani ya siku 1-2 bila baridi. Uza haraka!", "Kale spoils in 1-2 days without refrigeration. Sell fast!"),
                StorageTip("spoilage", "Ondoa majani yaliyooza kila siku. Moja mbaya inaharibu wote.", "Remove wilted leaves daily. One bad leaf spoils the bunch."),
                StorageTip("handling", "Weka maji kwenye chini ya kikapu — unyevu unaweka kijani.", "Put water at basket base — moisture keeps greens fresh.")
            ),
            "avocado" to listOf(
                StorageTip("spoilage", "Vuna parachichi kabla ya kukomaa kabisa. Zitakomaa njiani.", "Harvest avocado before full ripeness. They ripen in transit."),
                StorageTip("handling", "Usiweke juu ya kila nyingine — ngozi nyepesi kuharibika.", "Don't stack avocados — skin bruises easily."),
                StorageTip("spoilage", "Tenganisha zilizokomaa na zisizokomaa. Ethylene kutoka zinazokomaa inaharisha zingine.", "Separate ripe from unripe. Ethylene from ripe ones spoils others.")
            )
        )
    }

    private var dbHelper: LossDatabase? = null

    private fun getDb(): SQLiteDatabase {
        if (dbHelper == null) dbHelper = LossDatabase(context)
        return dbHelper!!.writableDatabase
    }

    private data class StorageTip(
        val lossType: String,
        val tipSw: String,
        val tipEn: String
    )

    // ──────────────────────────────────────────────
    // Tool Execute
    // ──────────────────────────────────────────────

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"]
            ?: return ToolResult.error(name, "Action required", "MISSING_ACTION")

        return when (action.lowercase()) {
            "record_loss" -> recordLoss(params)
            "loss_report" -> lossReport(params)
            "loss_breakdown" -> lossBreakdown(params)
            "storage_advice" -> storageAdvice(params)
            "alerts" -> checkAlerts(params)
            "compare_periods" -> comparePeriods(params)
            "tips" -> generalTips(params)
            "set_threshold" -> setThreshold(params)
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    // ──────────────────────────────────────────────
    // ACTION: record_loss — Log a loss event
    // ──────────────────────────────────────────────

    private fun recordLoss(params: Map<String, String>): ToolResult {
        val rawProduct = params["product"]
            ?: return ToolResult.error(name, "Product name required", "MISSING_PRODUCT")
        val product = normalizeProduct(rawProduct)
        val quantity = params["quantity"]?.toDoubleOrNull()
            ?: return ToolResult.error(name, "Quantity lost required", "MISSING_QUANTITY")
        val unit = params["unit"] ?: "kg"
        val lossType = normalizeLossType(params["loss_type"] ?: "spoilage")
        val cause = params["cause"]
        val notes = params["notes"]
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val now = System.currentTimeMillis()

        val db = getDb()
        val values = ContentValues().apply {
            put("product", product)
            put("quantity", quantity)
            put("unit", unit)
            put("loss_type", lossType)
            put("cause", cause)
            put("notes", notes)
            put("recorded_at", now)
        }
        val lossId = db.insert(TABLE_LOSSES, null, values)

        // Check if this triggers a high-loss alert
        val alertMsg = checkLossThreshold(db, product, now)

        Timber.d("Recorded loss: $product -$quantity $unit ($lossType)")

        val lossTypeDisplay = LOSS_TYPES[lossType] ?: lossType
        val message = if (voice) {
            buildString {
                append("⚠️ Hasara yamerekodwa: $product ${formatQty(quantity)} $unit\n")
                append("Sababu: $lossTypeDisplay")
                cause?.let { append(" ($it)") }
                alertMsg?.let { append("\n$it") }
            }
        } else {
            buildString {
                append("Loss recorded: $product ${formatQty(quantity)} $unit ($lossTypeDisplay)")
                cause?.let { append(" — $it") }
                alertMsg?.let { append("\n$it") }
            }
        }

        return ToolResult.success(name, mapOf(
            "loss_id" to lossId, "product" to product, "quantity" to quantity,
            "unit" to unit, "loss_type" to lossType, "cause" to cause
        ), message)
    }

    // ──────────────────────────────────────────────
    // ACTION: loss_report — Show loss summary
    // ──────────────────────────────────────────────

    private fun lossReport(params: Map<String, String>): ToolResult {
        val product = params["product"]?.let { normalizeProduct(it) }
        val period = params["period"] ?: "month"
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val db = getDb()
        val now = System.currentTimeMillis()
        val cutoff = when (period.lowercase()) {
            "week" -> now - 7 * 24 * 60 * 60 * 1000L
            "month" -> now - 30 * 24 * 60 * 60 * 1000L
            "season" -> now - 90 * 24 * 60 * 60 * 1000L
            "year" -> now - 365 * 24 * 60 * 60 * 1000L
            else -> now - 30 * 24 * 60 * 60 * 1000L
        }

        val selection = StringBuilder("recorded_at >= ?")
        val args = mutableListOf(cutoff.toString())
        product?.let { selection.append(" AND product = ?"); args.add(it) }

        val cursor = db.rawQuery("""
            SELECT product, SUM(quantity) as total_loss, unit,
                   COUNT(*) as incident_count,
                   loss_type
            FROM $TABLE_LOSSES
            WHERE $selection
            GROUP BY product, unit, loss_type
            ORDER BY total_loss DESC
        """.trimIndent(), args.toTypedArray())

        val losses = mutableListOf<LossSummary>()
        cursor.use {
            while (it.moveToNext()) {
                losses.add(LossSummary(
                    product = it.getString(0),
                    totalLoss = it.getDouble(1),
                    unit = it.getString(2),
                    incidentCount = it.getInt(3),
                    lossType = it.getString(4)
                ))
            }
        }

        if (losses.isEmpty()) {
            return ToolResult.success(
                name, mapOf("losses" to emptyList<Any>()),
                if (voice) "Hakuna hasara iliyorekodwa kwa $period iliyopita. Nzuri! ✅"
                else "No losses recorded in the last $period."
            )
        }

        val totalLoss = losses.sumOf { it.totalLoss }
        val totalIncidents = losses.sumOf { it.incidentCount }
        val byProduct = losses.groupBy { it.product }.mapValues { (_, v) -> v.sumOf { it.totalLoss } }
        val worstProduct = byProduct.maxByOrNull { it.value }

        val message = if (voice) {
            buildString {
                append("📊 Ripoti ya hasara ($period):\n\n")
                append("Jumla: ${formatQty(totalLoss)} (${totalIncidents} matukio)\n\n")
                byProduct.entries.sortedByDescending { it.value }.forEach { (prod, loss) ->
                    val pct = if (totalLoss > 0) (loss / totalLoss * 100).toInt() else 0
                    append("• $prod: ${formatQty(loss)} ($pct%)\n")
                }
                worstProduct?.let {
                    append("\n🔴 Bidhaa mbaya zaidi: ${it.key} (${formatQty(it.value)})")
                }
                append("\n\nTumia 'storage_advice' kwa ushauri wa kuhifadhi.")
            }
        } else {
            buildString {
                append("Loss report ($period):\n")
                append("Total: ${formatQty(totalLoss)} across $totalIncidents incidents\n\n")
                byProduct.entries.sortedByDescending { it.value }.forEach { (prod, loss) ->
                    append("• $prod: ${formatQty(loss)}\n")
                }
                worstProduct?.let { append("\nWorst: ${it.key} (${formatQty(it.value)})") }
            }
        }

        return ToolResult.success(name, mapOf(
            "total_loss" to totalLoss, "total_incidents" to totalIncidents,
            "by_product" to byProduct, "worst_product" to worstProduct?.key,
            "period" to period
        ), message)
    }

    // ──────────────────────────────────────────────
    // ACTION: loss_breakdown — Breakdown by loss type
    // ──────────────────────────────────────────────

    private fun lossBreakdown(params: Map<String, String>): ToolResult {
        val product = params["product"]?.let { normalizeProduct(it) }
        val period = params["period"] ?: "month"
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val db = getDb()
        val now = System.currentTimeMillis()
        val cutoff = when (period.lowercase()) {
            "week" -> now - 7 * 24 * 60 * 60 * 1000L
            "month" -> now - 30 * 24 * 60 * 60 * 1000L
            else -> now - 30 * 24 * 60 * 60 * 1000L
        }

        val selection = StringBuilder("recorded_at >= ?")
        val args = mutableListOf(cutoff.toString())
        product?.let { selection.append(" AND product = ?"); args.add(it) }

        val cursor = db.rawQuery("""
            SELECT loss_type, SUM(quantity) as total, COUNT(*) as count
            FROM $TABLE_LOSSES WHERE $selection
            GROUP BY loss_type ORDER BY total DESC
        """.trimIndent(), args.toTypedArray())

        val breakdown = mutableListOf<Triple<String, Double, Int>>()
        cursor.use { while (it.moveToNext()) breakdown.add(Triple(it.getString(0), it.getDouble(1), it.getInt(2))) }

        if (breakdown.isEmpty()) {
            return ToolResult.success(name, mapOf("breakdown" to emptyList<Any>()), "Hakuna hasara kwa $period.")
        }

        val total = breakdown.sumOf { it.second }

        val message = if (voice) {
            buildString {
                append("📊 Aina za hasara ($period):\n\n")
                breakdown.forEach { (type, qty, count) ->
                    val pct = if (total > 0) (qty / total * 100).toInt() else 0
                    val typeName = LOSS_TYPES[type] ?: type
                    append("• $typeName: ${formatQty(qty)} ($pct%, matukio $count)\n")
                }
                val worst = breakdown.first()
                append("\n🔴 Tatizo kubwa: ${LOSS_TYPES[worst.first] ?: worst.first}")
                append("\nTumia 'storage_advice' kwa suluhisho.")
            }
        } else {
            buildString {
                append("Loss breakdown ($period):\n")
                breakdown.forEach { (type, qty, count) ->
                    val pct = if (total > 0) (qty / total * 100).toInt() else 0
                    append("• $type: ${formatQty(qty)} ($pct%, $count incidents)\n")
                }
            }
        }

        return ToolResult.success(name, mapOf(
            "breakdown" to breakdown.map { mapOf("type" to it.first, "quantity" to it.second, "count" to it.third, "pct" to if (total > 0) it.second / total * 100 else 0.0) },
            "total" to total
        ), message)
    }

    // ──────────────────────────────────────────────
    // ACTION: storage_advice — Get storage tips
    // ──────────────────────────────────────────────

    private fun storageAdvice(params: Map<String, String>): ToolResult {
        val rawProduct = params["product"]
            ?: return ToolResult.error(name, "Product name required for storage advice", "MISSING_PRODUCT")
        val product = normalizeProduct(rawProduct)
        val lossType = params["loss_type"]?.let { normalizeLossType(it) }
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        // First check if we have loss data for this product to give targeted advice
        val db = getDb()
        val recentLosses = mutableMapOf<String, Double>()
        val cursor = db.rawQuery("""
            SELECT loss_type, SUM(quantity) FROM $TABLE_LOSSES
            WHERE product = ? AND recorded_at >= ?
            GROUP BY loss_type ORDER BY SUM(quantity) DESC
        """.trimIndent(), arrayOf(product, (System.currentTimeMillis() - 30 * 24 * 60 * 60 * 1000L).toString()))
        cursor.use {
            while (it.moveToNext()) recentLosses[it.getString(0)] = it.getDouble(1)
        }

        val tips = STORAGE_ADVICE[product]
        if (tips == null) {
            return ToolResult.success(
                name, mapOf("product" to product, "tips" to emptyList<Any>()),
                if (voice) "Hakuna ushauri maalum wa $product. Tumia 'tips' kwa ushauri wa jumla."
                else "No specific advice for $product. Use 'tips' for general guidance."
            )
        }

        // Filter tips by loss type if specified, or by most common loss type
        val targetType = lossType ?: recentLosses.maxByOrNull { it.value }?.key
        val filteredTips = if (targetType != null) {
            tips.filter { it.lossType == targetType }.ifEmpty { tips }
        } else tips

        val message = if (voice) {
            buildString {
                append("💡 Ushauri wa kuhifadhi $product:\n\n")
                if (recentLosses.isNotEmpty()) {
                    append("Hasara yako ya hivi karibuni:\n")
                    recentLosses.forEach { (type, qty) ->
                        append("• ${LOSS_TYPES[type] ?: type}: ${formatQty(qty)}\n")
                    }
                    append("\n")
                }
                filteredTips.forEachIndexed { i, tip ->
                    append("${i + 1}. ${tip.tipSw}\n")
                }
            }
        } else {
            buildString {
                append("Storage advice for $product:\n\n")
                filteredTips.forEachIndexed { i, tip ->
                    append("${i + 1}. ${tip.tipEn}\n")
                }
            }
        }

        return ToolResult.success(name, mapOf(
            "product" to product, "tips" to filteredTips.map { mapOf("type" to it.lossType, "sw" to it.tipSw, "en" to it.tipEn) },
            "recent_losses" to recentLosses
        ), message)
    }

    // ──────────────────────────────────────────────
    // ACTION: alerts — Check for high loss rates
    // ──────────────────────────────────────────────

    private fun checkAlerts(params: Map<String, String>): ToolResult {
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val db = getDb()
        val now = System.currentTimeMillis()
        val monthAgo = now - 30 * 24 * 60 * 60 * 1000L

        // Get loss totals per product for the month
        val cursor = db.rawQuery("""
            SELECT product, SUM(quantity) as total_loss
            FROM $TABLE_LOSSES WHERE recorded_at >= ?
            GROUP BY product
        """.trimIndent(), arrayOf(monthAgo.toString()))

        val alerts = mutableListOf<Pair<String, Double>>()
        cursor.use {
            while (it.moveToNext()) {
                val product = it.getString(0)
                val totalLoss = it.getDouble(1)
                // Check threshold
                val thresholdCursor = db.query(TABLE_THRESHOLDS, arrayOf("threshold_pct"), "product = ? AND is_active = 1", arrayOf(product), null, null, null)
                thresholdCursor.use { tc ->
                    val threshold = if (tc.moveToFirst()) tc.getDouble(0) else 20.0
                    // Simple threshold: if monthly loss > threshold % of typical monthly harvest
                    // For now, just flag if total loss is significant
                    if (totalLoss > 0) {
                        alerts.add(Pair(product, totalLoss))
                    }
                }
            }
        }

        if (alerts.isEmpty()) {
            return ToolResult.success(name, mapOf("alerts" to emptyList<Any>()), "Hakuna arifa za hasara. Hasara zote chini ya kiwango. ✅")
        }

        val message = if (voice) {
            buildString {
                append("⚠️ Arifa za hasara:\n")
                alerts.sortedByDescending { it.second }.forEach { (product, loss) ->
                    append("• $product: ${formatQty(loss)} imepoteza mwezi huu\n")
                }
                append("\nTumia 'storage_advice' kwa suluhisho.")
            }
        } else {
            buildString {
                append("Loss alerts (${alerts.size} products):\n")
                alerts.sortedByDescending { it.second }.forEach { (product, loss) ->
                    append("⚠️ $product: ${formatQty(loss)} lost this month\n")
                }
            }
        }

        return ToolResult.success(name, mapOf("alerts" to alerts.map { mapOf("product" to it.first, "loss" to it.second) }), message)
    }

    // ──────────────────────────────────────────────
    // ACTION: compare_periods — Compare loss rates
    // ──────────────────────────────────────────────

    private fun comparePeriods(params: Map<String, String>): ToolResult {
        val product = params["product"]?.let { normalizeProduct(it) }
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val db = getDb()
        val now = System.currentTimeMillis()
        val monthMs = 30L * 24 * 60 * 60 * 1000L

        val thisMonth = getLossTotal(db, product, now - monthMs, now)
        val lastMonth = getLossTotal(db, product, now - 2 * monthMs, now - monthMs)
        val changePct = if (lastMonth > 0) ((thisMonth - lastMonth) / lastMonth * 100) else if (thisMonth > 0) 100.0 else 0.0

        val message = if (voice) {
            buildString {
                append("📊 Linganisha hasara:\n")
                append("• Mwezi huu: ${formatQty(thisMonth)}\n")
                append("• Mwezi jana: ${formatQty(lastMonth)}\n")
                when {
                    changePct > 20 -> append("⚠️ Hasara imeongezeka sana! (+${changePct.toInt()}%)")
                    changePct < -20 -> append("✅ Hasara imepungua! (${changePct.toInt()}%)")
                    else -> append("→ Hasara ni sawa.")
                }
            }
        } else {
            "This month: ${formatQty(thisMonth)} | Last month: ${formatQty(lastMonth)} | Change: ${changePct.toInt()}%"
        }

        return ToolResult.success(name, mapOf(
            "this_month" to thisMonth, "last_month" to lastMonth, "change_pct" to changePct
        ), message)
    }

    // ──────────────────────────────────────────────
    // ACTION: tips — General loss reduction tips
    // ──────────────────────────────────────────────

    private fun generalTips(params: Map<String, String>): ToolResult {
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val message = if (voice) {
            """
💡 Vidokezo vya kupunguza hasara baada ya kuvuna:

1. 🔥 Kausha mazao kabisa kabla ya kuhifadhi
2. 🏠 Hifadhi mahali kavu, kivuli, na baridi
3. 📦 Tenganisha mazao mazuri na mabaya
4. 🚚 Safirisha kwa uangalifu — epuka mshtuko
5. ⏰ Uza mazao ya haraka (sukuma wiki, nyanya) ndani ya siku 1-2
6. 🧹 Safisha mahali pa kuhifadhi kabla ya kutumia
7. 🐀 Tumia mbinu za kuzuia wadudu (majivu, mafuta ya nazi)
8. 📊 Rekodda kila hasara — utajua tatizo ni wapi
            """.trimIndent()
        } else {
            """
General post-harvest loss reduction tips:

1. Dry produce completely before storage
2. Store in cool, dry, shaded locations
3. Separate good produce from damaged ones
4. Transport carefully — avoid bruising
5. Sell perishables (kale, tomatoes) within 1-2 days
6. Clean storage areas before use
7. Use pest prevention methods (ash, neem oil)
8. Track every loss — data reveals patterns
            """.trimIndent()
        }

        return ToolResult.success(name, mapOf("tips_count" to 8), message)
    }

    // ──────────────────────────────────────────────
    // ACTION: set_threshold — Set loss alert threshold
    // ──────────────────────────────────────────────

    private fun setThreshold(params: Map<String, String>): ToolResult {
        val rawProduct = params["product"]
            ?: return ToolResult.error(name, "Product name required", "MISSING_PRODUCT")
        val product = normalizeProduct(rawProduct)
        val thresholdPct = params["threshold_pct"]?.toDoubleOrNull() ?: 20.0
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val db = getDb()
        val values = ContentValues().apply {
            put("product", product)
            put("threshold_pct", thresholdPct)
            put("is_active", 1)
            put("created_at", System.currentTimeMillis())
        }
        db.insertWithOnConflict(TABLE_THRESHOLDS, null, values, SQLiteDatabase.CONFLICT_REPLACE)

        return ToolResult.success(
            name, mapOf("product" to product, "threshold_pct" to thresholdPct),
            if (voice) "🔔 Kiwango cha hasara kwa $product: ${thresholdPct.toInt()}%. Utaarifuwa ikizidi."
            else "Loss threshold for $product: ${thresholdPct}%. Alert when exceeded."
        )
    }

    // ──────────────────────────────────────────────
    // Internal Helpers
    // ──────────────────────────────────────────────

    private fun getLossTotal(db: SQLiteDatabase, product: String?, from: Long, to: Long): Double {
        val selection = StringBuilder("recorded_at >= ? AND recorded_at <= ?")
        val args = mutableListOf(from.toString(), to.toString())
        product?.let { selection.append(" AND product = ?"); args.add(it) }

        val cursor = db.rawQuery("SELECT COALESCE(SUM(quantity), 0) FROM $TABLE_LOSSES WHERE $selection", args.toTypedArray())
        cursor.use { return if (it.moveToFirst()) it.getDouble(0) else 0.0 }
    }

    private fun checkLossThreshold(db: SQLiteDatabase, product: String, now: Long): String? {
        val monthAgo = now - 30 * 24 * 60 * 60 * 1000L
        val totalLoss = getLossTotal(db, product, monthAgo, now)

        val cursor = db.query(TABLE_THRESHOLDS, arrayOf("threshold_pct"), "product = ? AND is_active = 1", arrayOf(product), null, null, null)
        cursor.use {
            if (it.moveToFirst()) {
                val threshold = it.getDouble(0)
                if (totalLoss > threshold) {
                    return "🔴 ONYO: Hasara ya $product imezidi kiwango! (${formatQty(totalLoss)} > ${threshold.toInt()}%)"
                }
            }
        }
        return null
    }

    private fun normalizeProduct(raw: String): String {
        val aliases = mapOf(
            "mahindi" to "maize", "maharagwe" to "beans", "viazi" to "potatoes",
            "nyanya" to "tomatoes", "sukuma wiki" to "kale", "kabichi" to "cabbage",
            "vitunguu" to "onions", "parachichi" to "avocado", "embe" to "mangoes",
            "ndizi" to "bananas", "mchele" to "rice", "samaki" to "fish",
            "muhogo" to "cassava", "viazi vitamu" to "sweet_potatoes"
        )
        return aliases[raw.trim().lowercase()] ?: raw.trim().lowercase()
    }

    private fun normalizeLossType(raw: String): String {
        val lower = raw.trim().lowercase()
        return when {
            lower.contains(Regex("spoil|haribika|oza|busha|kuharibika")) -> "spoilage"
            lower.contains(Regex("pest|wadudu|kunguni|panya|ndege|funza")) -> "pests"
            lower.contains(Regex("transport|safiri|njia|damage|mshtuko")) -> "transport"
            lower.contains(Regex("weather|mvua|jua|drought|baridi|majira")) -> "weather"
            lower.contains(Regex("theft|wizi|iba|nyang'anywa")) -> "theft"
            lower.contains(Regex("quality|ubora|duni")) -> "quality"
            lower.contains(Regex("dry|kausha|unyevu")) -> "drying"
            lower.contains(Regex("handle|kushughulikia|kubeba")) -> "handling"
            else -> lower.ifEmpty { "unknown" }
        }
    }

    private fun formatQty(qty: Double): String {
        return if (qty == qty.toLong().toDouble()) "%,.0f".format(qty) else "%,.1f".format(qty)
    }

    private data class LossSummary(
        val product: String,
        val totalLoss: Double,
        val unit: String,
        val incidentCount: Int,
        val lossType: String
    )
}
