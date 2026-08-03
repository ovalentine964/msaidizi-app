package com.msaidizi.agent.tools.agriculture

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import timber.log.Timber
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext
import com.msaidizi.agent.tools.core.*

/**
 * HarvestTracker — On-device harvest logging for farmers, fishermen, and producers.
 *
 * Problem: Producers (farmers, fishermen, miners) have no tools to log what they
 * harvest, track quality grades, predict future yields, or connect harvest to sale.
 * This tool fills that gap with voice-first, offline-first harvest recording.
 *
 * Voice examples:
 *   "Nimevuna mahindi gunia 5"           → Logged: maize, 5 bags
 *   "Nimevua samaki kilo 20"             → Logged: fish, 20 kg
 *   "Mavuno ya jana"                     → Shows yesterday's harvest
 *   "Bei ya soko la mahindi"             → Delegates to ProducePriceTracker
 *   "Kiasi gani nimevuna mwezi huu?"     → Monthly harvest summary
 *
 * Integrates with TransactionRecorder: harvest → sale flow.
 * Harvest is the "production" side; TransactionRecorder handles the "sale" side.
 */
@Singleton
class HarvestTracker @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val name = "harvest_tracker"
    override val description = "Log harvests by voice, track quantities and quality, predict future yields, and monitor post-harvest losses. For farmers, fishermen, and all producers."

    override val argsSchema = argSchema {
        enum(
            "action", "Action to perform",
            listOf(
                "record",           // Log a new harvest: "Nimevuna mahindi gunia 5"
                "history",          // Show recent harvests
                "summary",          // Monthly/weekly/seasonal summary
                "quality",          // Record quality grade for a harvest
                "loss",             // Record post-harvest loss
                "predict",          // Predict next harvest based on history
                "list_crops",       // List all tracked crops/products
                "current_stock",    // Show harvested but unsold stock
                "seasonal_report",  // Full seasonal analysis
                "voice_parse"       // Parse Swahili voice input
            ),
            required = true
        )
        string("product", "Crop/product name (e.g. 'mahindi', 'maharagwe', 'samaki')", required = false)
        string("quantity", "Harvest quantity (e.g. '5', '20')", required = false)
        string("unit", "Unit of measurement (gunia/kg/litre/ndoo/mkoba)", required = false)
        string("quality", "Quality grade: A (nzuri sana), B (nzuri), C (ya kawaida), D (mbaya)", required = false)
        string("location", "Farm/field/location name", required = false)
        string("notes", "Additional notes about the harvest", required = false)
        string("loss_type", "Type of loss: spoilage/pests/transport/weather/theft", required = false)
        string("loss_quantity", "Quantity lost", required = false)
        string("period", "Time period for summary: week/month/season/year", required = false)
        integer("harvest_id", "Harvest ID for quality/loss updates", required = false)
        boolean("voice", "Format response for voice output (Swahili)", required = false)
    }

    // ──────────────────────────────────────────────
    // SQLite Database for Offline Harvest Storage
    // ──────────────────────────────────────────────

    inner class HarvestDatabase(context: Context) :
        SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            // Harvest records — one row per harvest event
            db.execSQL("""
                CREATE TABLE $TABLE_HARVESTS (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    product TEXT NOT NULL,
                    quantity REAL NOT NULL,
                    unit TEXT NOT NULL DEFAULT 'kg',
                    quality_grade TEXT,
                    quality_notes TEXT,
                    location TEXT,
                    loss_quantity REAL DEFAULT 0,
                    loss_type TEXT,
                    loss_notes TEXT,
                    notes TEXT,
                    harvested_at INTEGER NOT NULL,
                    created_at INTEGER NOT NULL,
                    synced_at INTEGER,
                    sold_quantity REAL DEFAULT 0,
                    is_sold INTEGER DEFAULT 0
                )
            """)

            // Crop tracking — aggregated stats per crop
            db.execSQL("""
                CREATE TABLE $TABLE_CROPS (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    product TEXT NOT NULL UNIQUE,
                    total_harvested REAL DEFAULT 0,
                    total_lost REAL DEFAULT 0,
                    total_sold REAL DEFAULT 0,
                    harvest_count INTEGER DEFAULT 0,
                    avg_quality REAL DEFAULT 0,
                    best_yield REAL DEFAULT 0,
                    worst_yield REAL DEFAULT 0,
                    last_harvest_at INTEGER,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
            """)

            // Post-harvest losses — detailed loss tracking
            db.execSQL("""
                CREATE TABLE $TABLE_LOSSES (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    harvest_id INTEGER,
                    product TEXT NOT NULL,
                    quantity REAL NOT NULL,
                    unit TEXT NOT NULL DEFAULT 'kg',
                    loss_type TEXT NOT NULL,
                    loss_pct REAL,
                    notes TEXT,
                    recorded_at INTEGER NOT NULL,
                    FOREIGN KEY (harvest_id) REFERENCES $TABLE_HARVESTS(id)
                )
            """)

            // Indexes for fast lookups
            db.execSQL("CREATE INDEX idx_harvests_product ON $TABLE_HARVESTS(product)")
            db.execSQL("CREATE INDEX idx_harvests_date ON $TABLE_HARVESTS(harvested_at)")
            db.execSQL("CREATE INDEX idx_harvests_product_date ON $TABLE_HARVESTS(product, harvested_at)")
            db.execSQL("CREATE INDEX idx_crops_product ON $TABLE_CROPS(product)")
            db.execSQL("CREATE INDEX idx_losses_product ON $TABLE_LOSSES(product)")
            db.execSQL("CREATE INDEX idx_losses_harvest ON $TABLE_LOSSES(harvest_id)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_LOSSES")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_HARVESTS")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_CROPS")
            onCreate(db)
        }
    }

    companion object {
        private const val DB_NAME = "harvest_tracker.db"
        private const val DB_VERSION = 1
        private const val TABLE_HARVESTS = "harvests"
        private const val TABLE_CROPS = "crops"
        private const val TABLE_LOSSES = "losses"

        // Quality grade mapping
        private val QUALITY_GRADES = mapOf(
            "A" to 1.0, "nzuri sana" to 1.0, "bora" to 1.0, "best" to 1.0,
            "B" to 0.8, "nzuri" to 0.8, "good" to 0.8,
            "C" to 0.6, "ya kawaida" to 0.6, "average" to 0.6, "normal" to 0.6,
            "D" to 0.4, "mbaya" to 0.4, "poor" to 0.4, "baya" to 0.4
        )

        // Common units with Swahili names
        private val UNIT_ALIASES = mapOf(
            "gunia" to "gunia", "gunia 1" to "gunia", "bag" to "gunia", "bags" to "gunia",
            "kilo" to "kg", "kg" to "kg", "kilogram" to "kg",
            "lita" to "litre", "litre" to "litre", "liter" to "litre",
            "ndoo" to "ndoo", "bucket" to "ndoo",
            "mkoba" to "mkoba", "sack" to "mkoba",
            "mchele" to "mchele", "tin" to "mchele",
            "kanda" to "kanda", "cluster" to "kanda",
            "jani" to "jani", "leaf" to "jani",
            "kuni" to "kuni", "piece" to "kuni", "pcs" to "kuni"
        )

        // Common crop/product Swahili aliases
        private val PRODUCT_ALIASES = mapOf(
            "mahindi" to "maize", "mahindi" to "maize", "corn" to "maize",
            "maharagwe" to "beans", "bean" to "beans",
            "viazi" to "potatoes", "potato" to "potatoes",
            "nyanya" to "tomatoes", "tomato" to "tomatoes",
            "sukuma wiki" to "kale", "kale" to "kale",
            "kabichi" to "cabbage", "cabbage" to "cabbage",
            "vitunguu" to "onions", "onion" to "onions",
            "parachichi" to "avocado", "avocado" to "avocado",
            "embe" to "mangoes", "mango" to "mangoes",
            "ndizi" to "bananas", "banana" to "bananas",
            "mchele" to "rice", "rice" to "rice",
            "ngano" to "wheat", "wheat" to "wheat",
            "mtama" to "sorghum", "sorghum" to "sorghum",
            "wimbi" to "millet", "millet" to "millet",
            "njugu" to "groundnuts", "groundnuts" to "groundnuts",
            "miwa" to "sugarcane", "sugarcane" to "sugarcane",
            "chai" to "tea", "tea" to "tea",
            "kahawa" to "coffee", "coffee" to "coffee",
            "pamba" to "cotton", "cotton" to "cotton",
            "samaki" to "fish", "fish" to "fish",
            "ng'ombe" to "cattle", "cattle" to "cattle",
            "kuku" to "chicken", "chicken" to "chicken",
            "maziwa" to "milk", "milk" to "milk",
            "mayai" to "eggs", "eggs" to "eggs",
            "tikiti" to "watermelon", "tikiti maji" to "watermelon",
            "nanasi" to "pineapple", "pineapple" to "pineapple",
            "pilipili" to "chilli", "chilli" to "chilli",
            "tabasco" to "chilli",
            "bungo" to "cassava", "muhogo" to "cassava", "cassava" to "cassava",
            "viazi vitamu" to "sweet_potatoes", "sweet potatoes" to "sweet_potatoes",
            "njahi" to "lablab", "lablab" to "lablab",
            "choroko" to "cowpeas", "cowpeas" to "cowpeas",
            "dengu" to "lentils", "lentils" to "lentils",
            "soya" to "soybeans", "soybeans" to "soybeans"
        )
    }

    private var dbHelper: HarvestDatabase? = null

    private fun getDb(): SQLiteDatabase {
        if (dbHelper == null) {
            dbHelper = HarvestDatabase(context)
        }
        return dbHelper!!.writableDatabase
    }

    // ──────────────────────────────────────────────
    // Tool Execute — Main Entry Point
    // ──────────────────────────────────────────────

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"]
            ?: return ToolResult.error(
                name,
                "Action required. Use: record, history, summary, quality, loss, predict, list_crops, current_stock, seasonal_report, voice_parse",
                "MISSING_ACTION"
            )

        return when (action.lowercase()) {
            "record" -> recordHarvest(params)
            "history" -> showHistory(params)
            "summary" -> showSummary(params)
            "quality" -> recordQuality(params)
            "loss" -> recordLoss(params)
            "predict" -> predictYield(params)
            "list_crops" -> listCrops()
            "current_stock" -> currentStock(params)
            "seasonal_report" -> seasonalReport(params)
            "voice_parse" -> voiceParse(params)
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    // ──────────────────────────────────────────────
    // ACTION: record — Log a new harvest
    // Voice: "Nimevuna mahindi gunia 5"
    // ──────────────────────────────────────────────

    private fun recordHarvest(params: Map<String, String>): ToolResult {
        val rawProduct = params["product"]
            ?: return ToolResult.error(name, "Product/crop name required. Example: 'mahindi', 'maharagwe'", "MISSING_PRODUCT")
        val product = normalizeProduct(rawProduct)
        val quantity = params["quantity"]?.toDoubleOrNull()
            ?: return ToolResult.error(name, "Quantity required. Example: '5' (gunia), '20' (kilo)", "MISSING_QUANTITY")

        if (quantity <= 0) return ToolResult.error(name, "Quantity must be positive", "INVALID_QUANTITY")

        val rawUnit = params["unit"] ?: inferUnit(rawProduct)
        val unit = normalizeUnit(rawUnit)
        val quality = params["quality"]
        val location = params["location"]
        val notes = params["notes"]
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val now = System.currentTimeMillis()

        val db = getDb()

        // Insert harvest record
        val harvestValues = ContentValues().apply {
            put("product", product)
            put("quantity", quantity)
            put("unit", unit)
            put("quality_grade", quality)
            put("location", location)
            put("notes", notes)
            put("harvested_at", now)
            put("created_at", now)
        }
        val harvestId = db.insert(TABLE_HARVESTS, null, harvestValues)

        // Update crop aggregate stats
        updateCropStats(db, product, quantity, unit, quality, now)

        Timber.d("Recorded harvest: $product x$quantity $unit (id=$harvestId)")

        val qualityStr = quality?.let { " (gredi: $it)" } ?: ""
        val locationStr = location?.let { " hapa $it" } ?: ""

        val message = if (voice) {
            "✅ Mavuno yamerekodwa: $product ${formatQty(quantity)} $unit$qualityStr$locationStr\n" +
            "Id: $harvestId — tumia 'quality' kuongeza gredi au 'loss' kurekodda hasara."
        } else {
            "Harvest recorded: $product ${formatQty(quantity)} $unit$qualityStr$locationStr (id: $harvestId)"
        }

        return ToolResult.success(
            name,
            mapOf(
                "harvest_id" to harvestId,
                "product" to product,
                "quantity" to quantity,
                "unit" to unit,
                "quality" to quality,
                "location" to location,
                "timestamp" to now
            ),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: history — Show recent harvests
    // ──────────────────────────────────────────────

    private fun showHistory(params: Map<String, String>): ToolResult {
        val product = params["product"]?.let { normalizeProduct(it) }
        val limit = params["period"]?.let {
            when (it.lowercase()) {
                "week" -> 50; "month" -> 100; "year" -> 500; else -> 20
            }
        } ?: 20
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val db = getDb()
        val selection = if (product != null) "product = ?" else null
        val selectionArgs = if (product != null) arrayOf(product) else null

        val harvests = mutableListOf<HarvestRecord>()
        val cursor = db.query(
            TABLE_HARVESTS, null, selection, selectionArgs,
            null, null, "harvested_at DESC", limit.toString()
        )
        cursor.use {
            while (it.moveToNext()) {
                harvests.add(cursorToHarvest(it))
            }
        }

        if (harvests.isEmpty()) {
            return ToolResult.success(
                name, mapOf("harvests" to emptyList<Any>()),
                if (voice) "Hakuna mavuno yaliyorekodwa${product?.let { " ya $it" } ?: ""}."
                else "No harvests recorded${product?.let { " for $it" } ?: ""}."
            )
        }

        val message = if (voice) {
            buildString {
                append("Mavuno ya hivi karibuni${product?.let { " ($it)" } ?: ""}:\n")
                harvests.take(10).forEach { h ->
                    val date = formatDate(h.harvestedAt)
                    val quality = h.qualityGrade?.let { " [gredi: $it]" } ?: ""
                    append("• $date: ${h.product} ${formatQty(h.quantity)} ${h.unit}$quality\n")
                }
                if (harvests.size > 10) append("... na ${harvests.size - 10} zaidi")
            }
        } else {
            buildString {
                append("Recent harvests${product?.let { " ($it)" } ?: ""} (${harvests.size}):\n")
                harvests.take(10).forEach { h ->
                    val date = formatDate(h.harvestedAt)
                    append("• $date: ${h.product} ${formatQty(h.quantity)} ${h.unit}")
                    h.qualityGrade?.let { q -> append(" [grade: $q]") }
                    h.location?.let { l -> append(" @ $l") }
                    append("\n")
                }
            }
        }

        return ToolResult.success(
            name,
            mapOf("harvests" to harvests.map { h ->
                mapOf(
                    "id" to h.id, "product" to h.product, "quantity" to h.quantity,
                    "unit" to h.unit, "quality" to h.qualityGrade, "location" to h.location,
                    "harvested_at" to h.harvestedAt, "sold_quantity" to h.soldQuantity
                )
            }, "count" to harvests.size),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: summary — Monthly/weekly/seasonal summary
    // ──────────────────────────────────────────────

    private fun showSummary(params: Map<String, String>): ToolResult {
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

        val selection = StringBuilder("harvested_at >= ?")
        val args = mutableListOf(cutoff.toString())
        product?.let {
            selection.append(" AND product = ?")
            args.add(it)
        }

        val cursor = db.rawQuery("""
            SELECT product, SUM(quantity) as total_qty, unit, COUNT(*) as harvest_count,
                   SUM(loss_quantity) as total_loss, AVG(CASE WHEN quality_grade = 'A' THEN 1.0
                   WHEN quality_grade = 'B' THEN 0.8 WHEN quality_grade = 'C' THEN 0.6
                   WHEN quality_grade = 'D' THEN 0.4 ELSE 0.7 END) as avg_quality
            FROM $TABLE_HARVESTS
            WHERE $selection
            GROUP BY product, unit
            ORDER BY total_qty DESC
        """.trimIndent(), args.toTypedArray())

        val summaries = mutableListOf<CropSummary>()
        cursor.use {
            while (it.moveToNext()) {
                summaries.add(CropSummary(
                    product = it.getString(0),
                    totalQuantity = it.getDouble(1),
                    unit = it.getString(2),
                    harvestCount = it.getInt(3),
                    totalLoss = it.getDouble(4),
                    avgQuality = it.getDouble(5)
                ))
            }
        }

        if (summaries.isEmpty()) {
            return ToolResult.success(
                name, mapOf("summaries" to emptyList<Any>()),
                if (voice) "Hakuna mavuno kwa $period iliyopita."
                else "No harvests in the last $period."
            )
        }

        val totalHarvested = summaries.sumOf { it.totalQuantity }
        val totalLoss = summaries.sumOf { it.totalLoss }
        val lossPct = if (totalHarvested > 0) (totalLoss / (totalHarvested + totalLoss) * 100) else 0.0

        val message = if (voice) {
            buildString {
                append("Muhtasari wa mavuno ($period):\n")
                summaries.forEach { s ->
                    append("• ${s.product}: ${formatQty(s.totalQuantity)} ${s.unit}")
                    append(" (${s.harvestCount} mavuno)\n")
                }
                append("\nJumla: ${formatQty(totalHarvested)}")
                if (totalLoss > 0) {
                    append("\nHasara: ${formatQty(totalLoss)} (${lossPct.toInt()}%)")
                }
            }
        } else {
            buildString {
                append("Harvest summary ($period):\n")
                summaries.forEach { s ->
                    append("• ${s.product}: ${formatQty(s.totalQuantity)} ${s.unit}")
                    append(" (${s.harvestCount} harvests, avg quality: ${(s.avgQuality * 100).toInt()}%)")
                    if (s.totalLoss > 0) append(" | loss: ${formatQty(s.totalLoss)}")
                    append("\n")
                }
                append("\nTotal: ${formatQty(totalHarvested)} | Loss: ${formatQty(totalLoss)} (${lossPct.toInt()}%)")
            }
        }

        return ToolResult.success(
            name,
            mapOf(
                "summaries" to summaries.map { s ->
                    mapOf("product" to s.product, "total" to s.totalQuantity, "unit" to s.unit,
                        "count" to s.harvestCount, "loss" to s.totalLoss, "quality" to s.avgQuality)
                },
                "total_harvested" to totalHarvested,
                "total_loss" to totalLoss,
                "loss_pct" to lossPct,
                "period" to period
            ),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: quality — Record quality grade for a harvest
    // ──────────────────────────────────────────────

    private fun recordQuality(params: Map<String, String>): ToolResult {
        val harvestId = params["harvest_id"]?.toLongOrNull()
            ?: return ToolResult.error(name, "harvest_id required. Use 'history' to find IDs.", "MISSING_HARVEST_ID")
        val quality = params["quality"]
            ?: return ToolResult.error(name, "Quality grade required: A (nzuri sana), B (nzuri), C (ya kawaida), D (mbaya)", "MISSING_QUALITY")
        val notes = params["notes"]
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val normalizedQuality = normalizeQuality(quality)
            ?: return ToolResult.error(name, "Unknown quality grade: $quality. Use A/B/C/D or Swahili equivalents.", "INVALID_QUALITY")

        val db = getDb()
        val values = ContentValues().apply {
            put("quality_grade", normalizedQuality)
            put("quality_notes", notes)
        }
        val updated = db.update(TABLE_HARVESTS, values, "id = ?", arrayOf(harvestId.toString()))

        if (updated == 0) {
            return ToolResult.error(name, "Harvest ID $harvestId not found", "HARVEST_NOT_FOUND")
        }

        // Update crop average quality
        val cursor = db.query(TABLE_HARVESTS, arrayOf("product"), "id = ?", arrayOf(harvestId.toString()), null, null, null)
        cursor.use {
            if (it.moveToFirst()) {
                val product = it.getString(0)
                updateCropAverageQuality(db, product)
            }
        }

        return ToolResult.success(
            name,
            mapOf("harvest_id" to harvestId, "quality" to normalizedQuality, "notes" to notes),
            if (voice) "✅ Gredi ya mavuno $harvestId imewekwa: $normalizedQuality"
            else "Quality grade updated for harvest $harvestId: $normalizedQuality"
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: loss — Record post-harvest loss
    // ──────────────────────────────────────────────

    private fun recordLoss(params: Map<String, String>): ToolResult {
        val harvestId = params["harvest_id"]?.toLongOrNull()
        val rawProduct = params["product"]
            ?: return ToolResult.error(name, "Product name required for loss recording", "MISSING_PRODUCT")
        val product = normalizeProduct(rawProduct)
        val lossQty = params["loss_quantity"]?.toDoubleOrNull()
            ?: return ToolResult.error(name, "Loss quantity required", "MISSING_LOSS_QUANTITY")
        val lossType = params["loss_type"] ?: "unknown"
        val notes = params["notes"]
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val now = System.currentTimeMillis()

        val db = getDb()

        // Get unit from harvest if available, else infer
        val unit = if (harvestId != null) {
            val cursor = db.query(TABLE_HARVESTS, arrayOf("unit", "quantity"), "id = ?", arrayOf(harvestId.toString()), null, null, null)
            cursor.use {
                if (it.moveToFirst()) it.getString(0) else "kg"
            }
        } else "kg"

        // Calculate loss percentage
        val lossPct = if (harvestId != null) {
            val cursor = db.query(TABLE_HARVESTS, arrayOf("quantity"), "id = ?", arrayOf(harvestId.toString()), null, null, null)
            cursor.use {
                if (it.moveToFirst()) (lossQty / it.getDouble(0) * 100) else null
            }
        } else null

        // Insert loss record
        val lossValues = ContentValues().apply {
            put("harvest_id", harvestId)
            put("product", product)
            put("quantity", lossQty)
            put("unit", unit)
            put("loss_type", normalizeLossType(lossType))
            put("loss_pct", lossPct)
            put("notes", notes)
            put("recorded_at", now)
        }
        val lossId = db.insert(TABLE_LOSSES, null, lossValues)

        // Update harvest loss fields
        if (harvestId != null) {
            val updateValues = ContentValues().apply {
                put("loss_quantity", lossQty)
                put("loss_type", normalizeLossType(lossType))
                put("loss_notes", notes)
            }
            db.update(TABLE_HARVESTS, updateValues, "id = ?", arrayOf(harvestId.toString()))
        }

        // Update crop total losses
        updateCropLosses(db, product, lossQty)

        Timber.d("Recorded loss: $product -$lossQty $unit ($lossType)")

        val lossTypeDisplay = normalizeLossType(lossType)
        val message = if (voice) {
            buildString {
                append("⚠️ Hasara yamerekodwa: $product ${formatQty(lossQty)} $unit")
                append("\nSababu: $lossTypeDisplay")
                lossPct?.let { append(" (${it.toInt()}% ya mavuno)") }
                append("\nDokezo: ")
                append(when (lossTypeDisplay) {
                    "spoilage" -> "Hifadhi mahindi sehemu kavu na baridi."
                    "pests" -> "Tumia mifuko ya kuzuia wadudu au hifadhi kwenye pipa."
                    "transport" -> "Funga vizuri kabla ya kusafirisha."
                    "weather" -> "Vuna wakati wa hali ya hewa nzuri."
                    "theft" -> "Hifadhi mahindi sehemu salama."
                    else -> "Rekodda kila hasara ili kuboresha baadaye."
                })
            }
        } else {
            "Loss recorded: $product ${formatQty(lossQty)} $unit ($lossTypeDisplay)${lossPct?.let { " — ${it.toInt()}% of harvest" } ?: ""}"
        }

        return ToolResult.success(
            name,
            mapOf(
                "loss_id" to lossId, "harvest_id" to harvestId, "product" to product,
                "quantity" to lossQty, "unit" to unit, "loss_type" to lossTypeDisplay,
                "loss_pct" to lossPct
            ),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: predict — Predict next harvest based on history
    // ──────────────────────────────────────────────

    private fun predictYield(params: Map<String, String>): ToolResult {
        val rawProduct = params["product"]
            ?: return ToolResult.error(name, "Product name required for prediction", "MISSING_PRODUCT")
        val product = normalizeProduct(rawProduct)
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val db = getDb()

        // Get historical harvest data for this product
        val harvests = mutableListOf<Pair<Long, Double>>() // timestamp, quantity
        val cursor = db.query(
            TABLE_HARVESTS, arrayOf("harvested_at", "quantity"),
            "product = ?", arrayOf(product),
            null, null, "harvested_at ASC"
        )
        cursor.use {
            while (it.moveToNext()) {
                harvests.add(Pair(it.getLong(0), it.getDouble(1)))
            }
        }

        if (harvests.size < 2) {
            return ToolResult.success(
                name,
                mapOf("product" to product, "prediction" to null, "data_points" to harvests.size),
                if (voice) "Hakuna data ya kutosha ya $product kufanya utabiri. Rekodda mavuno zaidi kwanza."
                else "Insufficient data for $product prediction. Need at least 2 harvests."
            )
        }

        // Simple moving average prediction
        val recentHarvests = harvests.takeLast(10)
        val avgQuantity = recentHarvests.map { it.second }.average()

        // Calculate average interval between harvests
        val intervals = mutableListOf<Long>()
        for (i in 1 until harvests.size) {
            intervals.add(harvests[i].first - harvests[i - 1].first)
        }
        val avgIntervalMs = intervals.average().toLong()
        val avgIntervalDays = avgIntervalMs / (24 * 60 * 60 * 1000L)

        // Seasonal adjustment: look at same-month historical data
        val calendar = java.util.Calendar.getInstance()
        val currentMonth = calendar.get(java.util.Calendar.MONTH)
        val sameMonthHarvests = harvests.filter {
            calendar.timeInMillis = it.first
            calendar.get(java.util.Calendar.MONTH) == currentMonth
        }
        val seasonalMultiplier = if (sameMonthHarvests.isNotEmpty()) {
            val sameMonthAvg = sameMonthHarvests.map { it.second }.average()
            if (avgQuantity > 0) sameMonthAvg / avgQuantity else 1.0
        } else 1.0

        val predictedQuantity = avgQuantity * seasonalMultiplier
        val lastHarvestTime = harvests.last().first
        val predictedNextHarvest = lastHarvestTime + avgIntervalMs

        // Trend analysis: compare last 3 harvests to previous 3
        val recent3 = harvests.takeLast(3).map { it.second }.average()
        val previous3 = if (harvests.size >= 6) {
            harvests.subList(harvests.size - 6, harvests.size - 3).map { it.second }.average()
        } else recent3
        val trendPct = if (previous3 > 0) ((recent3 - previous3) / previous3 * 100) else 0.0
        val trendDir = when {
            trendPct > 10 -> "inapanda ↑"
            trendPct < -10 -> "inashuka ↓"
            else -> "imara →"
        }

        val unit = db.query(TABLE_HARVESTS, arrayOf("unit"), "product = ?", arrayOf(product), null, null, "harvested_at DESC", "1").use {
            if (it.moveToFirst()) it.getString(0) else "kg"
        }

        val message = if (voice) {
            buildString {
                append("🔮 Utabiri wa mavuno ya $product:\n")
                append("• Mavuno ya baadaye: ~${formatQty(predictedQuantity)} $unit\n")
                append("• Mwelekeo: $trendDir (${trendPct.toInt()}%)\n")
                append("• Muda wa mavuno: siku ~${avgIntervalDays}\n")
                if (seasonalMultiplier > 1.1) append("• Msimu huu ni mzuri kwa $product ↑\n")
                if (seasonalMultiplier < 0.9) append("• Msimu huu si mzuri kwa $product ↓\n")
                append("\n(Data kutoka mavuno ${harvests.size} ya nyuma)")
            }
        } else {
            buildString {
                append("$product yield prediction:\n")
                append("• Expected next harvest: ~${formatQty(predictedQuantity)} $unit\n")
                append("• Trend: $trendDir (${String.format("%.1f", trendPct)}%)\n")
                append("• Avg harvest interval: ~${avgIntervalDays} days\n")
                append("• Seasonal factor: ${String.format("%.2f", seasonalMultiplier)}x\n")
                append("• Based on ${harvests.size} historical harvests")
            }
        }

        return ToolResult.success(
            name,
            mapOf(
                "product" to product,
                "predicted_quantity" to predictedQuantity,
                "unit" to unit,
                "avg_interval_days" to avgIntervalDays,
                "seasonal_multiplier" to seasonalMultiplier,
                "trend_pct" to trendPct,
                "data_points" to harvests.size,
                "predicted_next_harvest_ms" to predictedNextHarvest
            ),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: list_crops — List all tracked crops/products
    // ──────────────────────────────────────────────

    private fun listCrops(): ToolResult {
        val db = getDb()
        val crops = mutableListOf<CropSummary>()
        val cursor = db.query(
            TABLE_CROPS, null, null, null, null, null, "total_harvested DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                crops.add(CropSummary(
                    product = it.getString(it.getColumnIndexOrThrow("product")),
                    totalQuantity = it.getDouble(it.getColumnIndexOrThrow("total_harvested")),
                    totalLoss = it.getDouble(it.getColumnIndexOrThrow("total_lost")),
                    harvestCount = it.getInt(it.getColumnIndexOrThrow("harvest_count")),
                    avgQuality = it.getDouble(it.getColumnIndexOrThrow("avg_quality")),
                    unit = "kg" // Will be resolved from latest harvest
                ))
            }
        }

        if (crops.isEmpty()) {
            return ToolResult.success(name, mapOf("crops" to emptyList<Any>()), "Hakuna mazao yaliyorekodwa. Anza na 'record'.")
        }

        val message = buildString {
            append("Mazao ${crops.size} yanafuatiliwa:\n")
            crops.forEach { c ->
                val lossPct = if (c.totalQuantity > 0) (c.totalLoss / (c.totalQuantity + c.totalLoss) * 100).toInt() else 0
                append("• ${c.product}: ${formatQty(c.totalQuantity)} (mavuno ${c.harvestCount})")
                if (c.totalLoss > 0) append(" | hasara: ${lossPct}%")
                append("\n")
            }
        }

        return ToolResult.success(
            name,
            mapOf("crops" to crops.map { mapOf("product" to it.product, "total" to it.totalQuantity, "count" to it.harvestCount, "loss" to it.totalLoss) }),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: current_stock — Show harvested but unsold stock
    // ──────────────────────────────────────────────

    private fun currentStock(params: Map<String, String>): ToolResult {
        val product = params["product"]?.let { normalizeProduct(it) }
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val db = getDb()
        val selection = StringBuilder("is_sold = 0")
        val args = mutableListOf<String>()
        product?.let {
            selection.append(" AND product = ?")
            args.add(it)
        }

        val cursor = db.rawQuery("""
            SELECT product, SUM(quantity - COALESCE(loss_quantity, 0) - COALESCE(sold_quantity, 0)) as available,
                   unit, COUNT(*) as batch_count
            FROM $TABLE_HARVESTS
            WHERE $selection
            GROUP BY product, unit
            HAVING available > 0
            ORDER BY available DESC
        """.trimIndent(), args.toTypedArray())

        val stock = mutableListOf<Map<String, Any?>>()
        cursor.use {
            while (it.moveToNext()) {
                stock.add(mapOf(
                    "product" to it.getString(0),
                    "available" to it.getDouble(1),
                    "unit" to it.getString(2),
                    "batch_count" to it.getInt(3)
                ))
            }
        }

        if (stock.isEmpty()) {
            return ToolResult.success(
                name, mapOf("stock" to emptyList<Any>()),
                if (voice) "Hakuna mazao kwenye stock. Mazao yote yameuzwa au yameharibika."
                else "No unsold harvest stock available."
            )
        }

        val message = if (voice) {
            buildString {
                append("📦 Stock ya mazao:\n")
                stock.forEach { s ->
                    append("• ${s["product"]}: ${formatQty(s["available"] as Double)} ${s["unit"]}\n")
                }
            }
        } else {
            buildString {
                append("Available harvest stock:\n")
                stock.forEach { s ->
                    append("• ${s["product"]}: ${formatQty(s["available"] as Double)} ${s["unit"]} (${s["batch_count"]} batches)\n")
                }
            }
        }

        return ToolResult.success(name, mapOf("stock" to stock), message)
    }

    // ──────────────────────────────────────────────
    // ACTION: seasonal_report — Full seasonal analysis
    // ──────────────────────────────────────────────

    private fun seasonalReport(params: Map<String, String>): ToolResult {
        val product = params["product"]?.let { normalizeProduct(it) }
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val db = getDb()
        val now = System.currentTimeMillis()
        val yearAgo = now - 365L * 24 * 60 * 60 * 1000L

        val selection = StringBuilder("harvested_at >= ?")
        val args = mutableListOf(yearAgo.toString())
        product?.let {
            selection.append(" AND product = ?")
            args.add(it)
        }

        // Get monthly breakdown
        val cursor = db.rawQuery("""
            SELECT product,
                   strftime('%m', harvested_at / 1000, 'unixepoch') as month,
                   SUM(quantity) as total_qty,
                   COUNT(*) as count,
                   SUM(loss_quantity) as total_loss
            FROM $TABLE_HARVESTS
            WHERE $selection
            GROUP BY product, month
            ORDER BY product, month
        """.trimIndent(), args.toTypedArray())

        val monthlyData = mutableMapOf<String, MutableList<MonthlyData>>()
        cursor.use {
            while (it.moveToNext()) {
                val prod = it.getString(0)
                val month = it.getString(1).toInt()
                monthlyData.getOrPut(prod) { mutableListOf() }.add(
                    MonthlyData(month, it.getDouble(2), it.getInt(3), it.getDouble(4))
                )
            }
        }

        if (monthlyData.isEmpty()) {
            return ToolResult.success(
                name, mapOf("report" to emptyMap<String, Any>()),
                if (voice) "Hakuna data ya msimu wa mwaka uliopita."
                else "No seasonal data available for the past year."
            )
        }

        val monthNames = arrayOf("", "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        val swahiliMonths = arrayOf("", "Januari", "Februari", "Machi", "Aprili", "Mei", "Juni", "Julai", "Agosti", "Septemba", "Oktoba", "Novemba", "Desemba")

        val message = if (voice) {
            buildString {
                append("📊 Ripoti ya msimu (mwaka 1):\n\n")
                monthlyData.forEach { (prod, months) ->
                    append("🌱 $prod:\n")
                    months.forEach { m ->
                        append("  ${swahiliMonths[m.month]}: ${formatQty(m.totalQuantity)} (${m.harvestCount}x)")
                        if (m.totalLoss > 0) append(" ⚠️ hasara: ${formatQty(m.totalLoss)}")
                        append("\n")
                    }
                    val bestMonth = months.maxByOrNull { it.totalQuantity }
                    bestMonth?.let {
                        append("  ➡️ Mwezi bora: ${swahiliMonths[it.month]}\n")
                    }
                    append("\n")
                }
            }
        } else {
            buildString {
                append("Seasonal report (past year):\n\n")
                monthlyData.forEach { (prod, months) ->
                    append("$prod:\n")
                    months.forEach { m ->
                        append("  ${monthNames[m.month]}: ${formatQty(m.totalQuantity)} (${m.harvestCount} harvests)")
                        if (m.totalLoss > 0) append(" | loss: ${formatQty(m.totalLoss)}")
                        append("\n")
                    }
                    val bestMonth = months.maxByOrNull { it.totalQuantity }
                    bestMonth?.let { append("  → Best month: ${monthNames[it.month]}\n") }
                    append("\n")
                }
            }
        }

        return ToolResult.success(
            name,
            mapOf("monthly_data" to monthlyData.map { (prod, months) ->
                mapOf("product" to prod, "months" to months.map { mapOf("month" to it.month, "quantity" to it.totalQuantity, "count" to it.harvestCount, "loss" to it.totalLoss) })
            }),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: voice_parse — Parse Swahili voice input
    // ──────────────────────────────────────────────

    private fun voiceParse(params: Map<String, String>): ToolResult {
        val text = params["notes"] ?: params["product"]
            ?: return ToolResult.error(name, "Voice text required. Provide via 'notes' or 'product' param.", "MISSING_TEXT")

        val parsed = parseSwahiliHarvestVoice(text)
        return ToolResult.success(name, parsed, "Parsed: ${parsed["product"]} ${parsed["quantity"]} ${parsed["unit"]}")
    }

    // ──────────────────────────────────────────────
    // Swahili Voice Parsing — Harvest Commands
    // ──────────────────────────────────────────────

    /**
     * Parse Swahili voice input for harvest recording.
     * Supports:
     *   "Nimevuna mahindi gunia 5"
     *   "Nimevua samaki kilo 20"
     *   "Mavuno ya maharagwe ni kilo 50"
     *   "Nimepanda viazi gunia 3"
     */
    fun parseSwahiliHarvestVoice(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val lower = text.lowercase().trim()

        // Detect harvest action
        when {
            lower.contains(Regex("nimevuna|nimelima|nimekuna|nimechuma|nimepanda|nimevunja|nimechuma")) -> {
                result["action"] = "record"
            }
            lower.contains(Regex("nimevua|nimetega|nimetega")) -> {
                result["action"] = "record"
                result["product_hint"] = "fish"
            }
            lower.contains(Regex("mavuno|harvest|record")) -> {
                result["action"] = "record"
            }
            lower.contains(Regex("historia|history|record|zilizopita")) -> {
                result["action"] = "history"
            }
            lower.contains(Regex("utabiri|predict|next|kesho|wiki ijayo")) -> {
                result["action"] = "predict"
            }
            lower.contains(Regex("hasara|loss|spoilage|haribika")) -> {
                result["action"] = "loss"
            }
            lower.contains(Regex("stock|kipimo|kiasi")) -> {
                result["action"] = "current_stock"
            }
            lower.contains(Regex("muhtasari|summary|jumla")) -> {
                result["action"] = "summary"
            }
        }

        // Extract product name
        val productMatch = extractProductFromVoice(lower)
        if (productMatch != null) {
            result["product"] = productMatch
        }

        // Extract quantity + unit pattern: "gunia 5", "kilo 20", "5 gunia"
        val qtyUnitPatterns = listOf(
            Regex("""(\d+\.?\d*)\s*(gunia|kilo|kg|lita|ndoo|mkoba|mchele|kanda|jani|kuni)"""),
            Regex("""(gunia|kilo|kg|lita|ndoo|mkoba|mchele|kanda|jani|kuni)\s*(\d+\.?\d*)"""),
            Regex("""(\d+\.?\d*)\s*(pieces?|pcs?)""", RegexOption.IGNORE_CASE)
        )

        for (pattern in qtyUnitPatterns) {
            pattern.find(lower)?.let { match ->
                val groups = match.groupValues
                val qty = groups[1].toDoubleOrNull() ?: groups[2].toDoubleOrNull()
                val unit = groups[2].takeIf { it.isNotEmpty() && !it.matches(Regex("\\d+\\.?\\d*")) }
                    ?: groups[1].takeIf { !it.matches(Regex("\\d+\\.?\\d*")) }
                qty?.let { result["quantity"] = it.toString() }
                unit?.let { result["unit"] = normalizeUnit(it) }
            }
        }

        // Fallback: plain number extraction if no unit pattern found
        if ("quantity" !in result) {
            Regex("""(\d+\.?\d*)""").find(lower)?.let {
                result["quantity"] = it.groupValues[1]
            }
        }

        // Extract quality grade
        for ((keyword, grade) in QUALITY_GRADES) {
            if (lower.contains(keyword)) {
                result["quality"] = grade.toString()
                break
            }
        }

        // Extract location hints
        val locationPatterns = listOf(
            Regex("""shamba\s+(\w+)"""),
            Regex("""kwa\s+(\w+)"""),
            Regex("""hapa\s+(\w+)""")
        )
        for (pattern in locationPatterns) {
            pattern.find(lower)?.let {
                result["location"] = it.groupValues[1]
            }
        }

        return result
    }

    /**
     * Extract product name from voice input by checking known aliases.
     */
    private fun extractProductFromVoice(text: String): String? {
        // Check longer aliases first (e.g., "sukuma wiki" before "sukuma")
        val sortedAliases = PRODUCT_ALIASES.entries.sortedByDescending { it.key.length }
        for ((alias, canonical) in sortedAliases) {
            if (text.contains(alias)) {
                return canonical
            }
        }
        return null
    }

    // ──────────────────────────────────────────────
    // Internal Helpers
    // ──────────────────────────────────────────────

    private fun updateCropStats(db: SQLiteDatabase, product: String, quantity: Double, unit: String, quality: String?, timestamp: Long) {
        val existing = db.query(TABLE_CROPS, null, "product = ?", arrayOf(product), null, null, null)
        existing.use {
            if (it.moveToFirst()) {
                // Update existing crop
                val totalHarvested = it.getDouble(it.getColumnIndexOrThrow("total_harvested")) + quantity
                val harvestCount = it.getInt(it.getColumnIndexOrThrow("harvest_count")) + 1
                val bestYield = maxOf(it.getDouble(it.getColumnIndexOrThrow("best_yield")), quantity)
                val worstYield = minOf(it.getDouble(it.getColumnIndexOrThrow("worst_yield")), quantity)

                val values = ContentValues().apply {
                    put("total_harvested", totalHarvested)
                    put("harvest_count", harvestCount)
                    put("best_yield", bestYield)
                    put("worst_yield", worstYield)
                    put("last_harvest_at", timestamp)
                    put("updated_at", System.currentTimeMillis())
                }
                db.update(TABLE_CROPS, values, "product = ?", arrayOf(product))
            } else {
                // Insert new crop
                val values = ContentValues().apply {
                    put("product", product)
                    put("total_harvested", quantity)
                    put("harvest_count", 1)
                    put("best_yield", quantity)
                    put("worst_yield", quantity)
                    put("last_harvest_at", timestamp)
                    put("created_at", System.currentTimeMillis())
                    put("updated_at", System.currentTimeMillis())
                }
                db.insert(TABLE_CROPS, null, values)
            }
        }
    }

    private fun updateCropLosses(db: SQLiteDatabase, product: String, lossQty: Double) {
        val cursor = db.query(TABLE_CROPS, arrayOf("total_lost"), "product = ?", arrayOf(product), null, null, null)
        cursor.use {
            if (it.moveToFirst()) {
                val newTotal = it.getDouble(0) + lossQty
                val values = ContentValues().apply {
                    put("total_lost", newTotal)
                    put("updated_at", System.currentTimeMillis())
                }
                db.update(TABLE_CROPS, values, "product = ?", arrayOf(product))
            }
        }
    }

    private fun updateCropAverageQuality(db: SQLiteDatabase, product: String) {
        val cursor = db.rawQuery("""
            SELECT AVG(CASE WHEN quality_grade = 'A' THEN 1.0
                WHEN quality_grade = 'B' THEN 0.8 WHEN quality_grade = 'C' THEN 0.6
                WHEN quality_grade = 'D' THEN 0.4 ELSE NULL END)
            FROM $TABLE_HARVESTS WHERE product = ? AND quality_grade IS NOT NULL
        """.trimIndent(), arrayOf(product))
        cursor.use {
            if (it.moveToFirst() && !it.isNull(0)) {
                val values = ContentValues().apply {
                    put("avg_quality", it.getDouble(0))
                    put("updated_at", System.currentTimeMillis())
                }
                db.update(TABLE_CROPS, values, "product = ?", arrayOf(product))
            }
        }
    }

    private fun cursorToHarvest(cursor: Cursor): HarvestRecord {
        return HarvestRecord(
            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
            product = cursor.getString(cursor.getColumnIndexOrThrow("product")),
            quantity = cursor.getDouble(cursor.getColumnIndexOrThrow("quantity")),
            unit = cursor.getString(cursor.getColumnIndexOrThrow("unit")),
            qualityGrade = cursor.getString(cursor.getColumnIndexOrThrow("quality_grade")),
            qualityNotes = cursor.getString(cursor.getColumnIndexOrThrow("quality_notes")),
            location = cursor.getString(cursor.getColumnIndexOrThrow("location")),
            lossQuantity = cursor.getDouble(cursor.getColumnIndexOrThrow("loss_quantity")),
            lossType = cursor.getString(cursor.getColumnIndexOrThrow("loss_type")),
            notes = cursor.getString(cursor.getColumnIndexOrThrow("notes")),
            harvestedAt = cursor.getLong(cursor.getColumnIndexOrThrow("harvested_at")),
            soldQuantity = cursor.getDouble(cursor.getColumnIndexOrThrow("sold_quantity"))
        )
    }

    private fun normalizeProduct(raw: String): String {
        val lower = raw.trim().lowercase()
        return PRODUCT_ALIASES[lower] ?: lower
    }

    private fun normalizeUnit(raw: String): String {
        val lower = raw.trim().lowercase()
        return UNIT_ALIASES[lower] ?: lower
    }

    private fun normalizeQuality(raw: String): String? {
        val lower = raw.trim().lowercase()
        return when {
            QUALITY_GRADES.containsKey(raw.trim().uppercase()) -> raw.trim().uppercase()
            QUALITY_GRADES.containsKey(lower) -> when (lower) {
                "nzuri sana", "bora", "best" -> "A"
                "nzuri", "good" -> "B"
                "ya kawaida", "average", "normal" -> "C"
                "mbaya", "poor", "baya" -> "D"
                else -> null
            }
            else -> null
        }
    }

    private fun normalizeLossType(raw: String): String {
        val lower = raw.trim().lowercase()
        return when {
            lower.contains(Regex("spoil|haribika|oza|busha")) -> "spoilage"
            lower.contains(Regex("pest|wadudu|kunguni|panya|ndege")) -> "pests"
            lower.contains(Regex("transport|safiri|haribika.*njia|damage")) -> "transport"
            lower.contains(Regex("weather|mvua|jua|drought|baridi")) -> "weather"
            lower.contains(Regex("theft|wizi|iba|nyang'anywa")) -> "theft"
            else -> lower.ifEmpty { "unknown" }
        }
    }

    private fun inferUnit(product: String): String {
        val lower = product.lowercase()
        return when {
            lower.contains(Regex("samaki|fish")) -> "kg"
            lower.contains(Regex("maziwa|milk")) -> "litre"
            lower.contains(Regex("ndizi|banana")) -> "kanda"
            lower.contains(Regex("kuku|chicken")) -> "kuni"
            lower.contains(Regex("mayai|eggs")) -> "kuni"
            lower.contains(Regex("sukuma|kale|mboga")) -> "kanda"
            else -> "kg"
        }
    }

    private fun formatQty(qty: Double): String {
        return if (qty == qty.toLong().toDouble()) "%,.0f".format(qty) else "%,.1f".format(qty)
    }

    private fun formatDate(timestamp: Long): String {
        val sdf = java.text.SimpleDateFormat("dd MMM", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(timestamp))
    }

    // ──────────────────────────────────────────────
    // Data Classes
    // ──────────────────────────────────────────────

    data class HarvestRecord(
        val id: Long,
        val product: String,
        val quantity: Double,
        val unit: String,
        val qualityGrade: String?,
        val qualityNotes: String?,
        val location: String?,
        val lossQuantity: Double,
        val lossType: String?,
        val notes: String?,
        val harvestedAt: Long,
        val soldQuantity: Double
    )

    data class CropSummary(
        val product: String,
        val totalQuantity: Double,
        val unit: String,
        val harvestCount: Int,
        val totalLoss: Double,
        val avgQuality: Double
    )

    data class MonthlyData(
        val month: Int,
        val totalQuantity: Double,
        val harvestCount: Int,
        val totalLoss: Double
    )
}
