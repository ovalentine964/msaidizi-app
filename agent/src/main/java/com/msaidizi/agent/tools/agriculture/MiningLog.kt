package com.msaidizi.agent.tools.agriculture

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import timber.log.Timber
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext
import com.msaidizi.agent.tools.core.*

/**
 * MiningLog — Specialized tracking for artisanal and small-scale miners.
 *
 * Problem: Artisanal miners in Kenya (gold in Migori/Homa Bay, gemstones in Taita Taveta,
 * soda ash in Magadi) lose 80-90% of mineral value to middlemen because they lack
 * price visibility, have no production records, and can't prove output for better deals.
 * Miners also face severe safety risks with no incident tracking.
 *
 * This tool provides voice-first mineral extraction logging, equipment tracking,
 * safety incident recording, and buyer price comparison.
 *
 * Voice examples:
 *   "Nimepata dhahabu gramu 5"              → Logged: gold, 5g
 *   "Almasi uzito wa pointi 3"              → Logged: diamond, 0.3ct
 *   "Bei ya dhahabu kwa Mnunuzi A"          → Price comparison
 *   "Kifaa cha uchimbaji kimevunjika"       → Equipment issue logged
 *   "Ajali imetokea shaharini"              → Safety incident logged
 */
@Singleton
class MiningLog @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val name = "mining_log"
    override val description = "Track mineral extraction by type/weight, log equipment usage and maintenance, record safety incidents, and compare prices at different buyers."

    override val argsSchema = argSchema {
        enum(
            "action", "Action to perform",
            listOf(
                "record_extraction",  // Log mineral extraction
                "record_equipment",   // Log equipment status/usage
                "record_incident",    // Log safety incident
                "record_price",       // Record mineral price from a buyer
                "compare_buyers",     // Compare prices across buyers
                "extraction_history", // Show extraction history
                "equipment_list",     // List equipment with status
                "incident_history",   // Show safety incidents
                "earnings",           // Calculate earnings for a period
                "safety_tips",        // Safety guidance
                "voice_parse"         // Parse Swahili voice input
            ),
            required = true
        )
        string("mineral", "Mineral type (e.g. 'dhahabu', 'almasi', 'ruby')", required = false)
        number("weight", "Weight extracted (grams for gold, carats for gems)", required = false)
        string("unit", "Unit: grams/carats/kg", required = false)
        string("location", "Mining site/shaft name", required = false)
        string("method", "Extraction method: shaft/tunnel/surface/panning", required = false)
        string("equipment", "Equipment name or type", required = false)
        string("equipment_status", "Status: working/broken/maintenance/lost", required = false)
        string("incident_type", "Incident type: collapse/flood/gas/injury/equipment", required = false)
        string("severity", "Severity: minor/moderate/severe/fatal", required = false)
        string("description", "Description of incident or notes", required = false)
        string("buyer", "Buyer name", required = false)
        number("price", "Price offered per unit", required = false)
        string("period", "Time period: week/month/season", required = false)
        boolean("voice", "Format for voice output", required = false)
    }

    // ──────────────────────────────────────────────
    // SQLite Database
    // ──────────────────────────────────────────────

    inner class MiningDatabase(context: Context) :
        SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            // Extraction records
            db.execSQL("""
                CREATE TABLE $TABLE_EXTRACTIONS (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    mineral TEXT NOT NULL,
                    weight REAL NOT NULL,
                    unit TEXT NOT NULL DEFAULT 'grams',
                    quality TEXT,
                    location TEXT,
                    method TEXT,
                    notes TEXT,
                    recorded_at INTEGER NOT NULL
                )
            """)

            // Equipment tracking
            db.execSQL("""
                CREATE TABLE $TABLE_EQUIPMENT (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    type TEXT,
                    status TEXT NOT NULL DEFAULT 'working',
                    purchase_cost REAL,
                    purchase_date INTEGER,
                    last_maintenance INTEGER,
                    next_maintenance INTEGER,
                    notes TEXT,
                    recorded_at INTEGER NOT NULL
                )
            """)

            // Safety incidents
            db.execSQL("""
                CREATE TABLE $TABLE_INCIDENTS (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    incident_type TEXT NOT NULL,
                    severity TEXT NOT NULL DEFAULT 'minor',
                    location TEXT,
                    description TEXT,
                    injuries INTEGER DEFAULT 0,
                    recorded_at INTEGER NOT NULL
                )
            """)

            // Buyer prices
            db.execSQL("""
                CREATE TABLE $TABLE_PRICES (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    mineral TEXT NOT NULL,
                    buyer TEXT NOT NULL,
                    price_per_unit REAL NOT NULL,
                    unit TEXT NOT NULL DEFAULT 'grams',
                    location TEXT,
                    recorded_at INTEGER NOT NULL
                )
            """)

            db.execSQL("CREATE INDEX idx_ext_mineral ON $TABLE_EXTRACTIONS(mineral)")
            db.execSQL("CREATE INDEX idx_ext_date ON $TABLE_EXTRACTIONS(recorded_at)")
            db.execSQL("CREATE INDEX idx_eq_status ON $TABLE_EQUIPMENT(status)")
            db.execSQL("CREATE INDEX idx_inc_type ON $TABLE_INCIDENTS(incident_type)")
            db.execSQL("CREATE INDEX idx_prices_mineral ON $TABLE_PRICES(mineral, buyer)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_EXTRACTIONS")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_EQUIPMENT")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_INCIDENTS")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_PRICES")
            onCreate(db)
        }
    }

    companion object {
        private const val DB_NAME = "mining_log.db"
        private const val DB_VERSION = 1
        private const val TABLE_EXTRACTIONS = "extractions"
        private const val TABLE_EQUIPMENT = "equipment"
        private const val TABLE_INCIDENTS = "incidents"
        private const val TABLE_PRICES = "mineral_prices"

        // Mineral aliases (Swahili → English)
        val MINERAL_ALIASES = mapOf(
            "dhahabu" to "gold", "gold" to "gold",
            "almasi" to "diamond", "diamond" to "diamond",
            "ruby" to "ruby", "rubini" to "ruby",
            "zamaradi" to "emerald", "emerald" to "emerald",
            "tanzanite" to "tanzanite", "tanzanaiti" to "tanzanite",
            "sapphire" to "sapphire", "safiri" to "sapphire",
            "gypsum" to "gypsum", "jisamu" to "gypsum",
            "soda ash" to "soda_ash", "chumvuu ya soda" to "soda_ash",
            "limestone" to "limestone", "chokaa" to "limestone",
            "mable" to "marble", "marumaru" to "marble",
            "graphite" to "graphite", "grafaiti" to "graphite",
            "titanium" to "titanium", "titani" to "titanium",
            "coltan" to "coltan", "koltani" to "coltan",
            "magnetite" to "magnetite", "magnetiti" to "magnetite"
        )

        // Typical prices (KES) — very approximate, for estimation
        val TYPICAL_PRICES = mapOf(
            "gold" to 9500.0,       // per gram
            "diamond" to 50000.0,   // per carat (low quality)
            "ruby" to 30000.0,      // per carat
            "emerald" to 40000.0,   // per carat
            "tanzanite" to 25000.0, // per carat
            "sapphire" to 20000.0,  // per carat
            "gypsum" to 50.0,       // per kg
            "soda_ash" to 30.0,     // per kg
            "limestone" to 20.0,    // per kg
            "marble" to 100.0,      // per kg
            "coltan" to 8000.0      // per gram
        )

        // Unit per mineral
        val MINERAL_UNITS = mapOf(
            "gold" to "grams", "diamond" to "carats", "ruby" to "carats",
            "emerald" to "carats", "tanzanite" to "carats", "sapphire" to "carats",
            "gypsum" to "kg", "soda_ash" to "kg", "limestone" to "kg",
            "marble" to "kg", "coltan" to "grams"
        )
    }

    private var dbHelper: MiningDatabase? = null

    private fun getDb(): SQLiteDatabase {
        if (dbHelper == null) dbHelper = MiningDatabase(context)
        return dbHelper!!.writableDatabase
    }

    // ──────────────────────────────────────────────
    // Tool Execute
    // ──────────────────────────────────────────────

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"]
            ?: return ToolResult.error(name, "Action required", "MISSING_ACTION")

        return when (action.lowercase()) {
            "record_extraction" -> recordExtraction(params)
            "record_equipment" -> recordEquipment(params)
            "record_incident" -> recordIncident(params)
            "record_price" -> recordPrice(params)
            "compare_buyers" -> compareBuyers(params)
            "extraction_history" -> extractionHistory(params)
            "equipment_list" -> equipmentList(params)
            "incident_history" -> incidentHistory(params)
            "earnings" -> earnings(params)
            "safety_tips" -> safetyTips(params)
            "voice_parse" -> voiceParse(params)
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    // ──────────────────────────────────────────────
    // ACTION: record_extraction — Log mineral extraction
    // Voice: "Nimepata dhahabu gramu 5"
    // ──────────────────────────────────────────────

    private fun recordExtraction(params: Map<String, String>): ToolResult {
        val rawMineral = params["mineral"]
            ?: return ToolResult.error(name, "Mineral type required (e.g. 'dhahabu', 'almasi', 'ruby')", "MISSING_MINERAL")
        val mineral = normalizeMineral(rawMineral)
        val weight = params["weight"]?.toDoubleOrNull()
            ?: return ToolResult.error(name, "Weight required (grams for gold, carats for gems)", "MISSING_WEIGHT")
        val unit = params["unit"] ?: MINERAL_UNITS[mineral] ?: "grams"
        val quality = params["quality"]
        val location = params["location"]
        val method = params["method"]
        val notes = params["notes"]
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val now = System.currentTimeMillis()

        val db = getDb()
        val values = ContentValues().apply {
            put("mineral", mineral)
            put("weight", weight)
            put("unit", unit)
            put("quality", quality)
            put("location", location)
            put("method", method)
            put("notes", notes)
            put("recorded_at", now)
        }
        val extractionId = db.insert(TABLE_EXTRACTIONS, null, values)

        val price = TYPICAL_PRICES[mineral] ?: 0.0
        val estimatedValue = weight * price

        Timber.d("Recorded extraction: $mineral ${weight}$unit (id=$extractionId)")

        val message = if (voice) {
            buildString {
                append("⛏️ Uchimbaji umerekodwa: $mineral ${formatQty(weight)} $unit")
                quality?.let { append(", ubora: $it") }
                location?.let { append(", hapa $it") }
                append("\n💰 Thamani ya makadirio: KES ${formatPrice(estimatedValue)}")
                append("\nId: $extractionId")
            }
        } else {
            "Extraction recorded: $mineral ${formatQty(weight)} $unit (id: $extractionId) | Est. value: KES ${formatPrice(estimatedValue)}"
        }

        return ToolResult.success(name, mapOf(
            "extraction_id" to extractionId, "mineral" to mineral, "weight" to weight,
            "unit" to unit, "quality" to quality, "location" to location,
            "estimated_value" to estimatedValue
        ), message)
    }

    // ──────────────────────────────────────────────
    // ACTION: record_equipment — Log equipment status
    // ──────────────────────────────────────────────

    private fun recordEquipment(params: Map<String, String>): ToolResult {
        val equipment = params["equipment"]
            ?: return ToolResult.error(name, "Equipment name required", "MISSING_EQUIPMENT")
        val status = params["equipment_status"] ?: "working"
        val type = params["type"]
        val purchaseCost = params["purchase_cost"]?.toDoubleOrNull()
        val notes = params["notes"]
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val now = System.currentTimeMillis()

        val db = getDb()

        // Check if equipment already exists
        val existing = db.query(TABLE_EQUIPMENT, arrayOf("id"), "name = ?", arrayOf(equipment), null, null, null)
        val isUpdate = existing.use { it.moveToFirst() }

        if (isUpdate) {
            val values = ContentValues().apply {
                put("status", status)
                put("notes", notes)
                if (status == "maintenance") put("last_maintenance", now)
            }
            db.update(TABLE_EQUIPMENT, values, "name = ?", arrayOf(equipment))
        } else {
            val values = ContentValues().apply {
                put("name", equipment)
                put("type", type)
                put("status", status)
                put("purchase_cost", purchaseCost)
                put("purchase_date", now)
                put("recorded_at", now)
            }
            db.insert(TABLE_EQUIPMENT, null, values)
        }

        val statusEmoji = when (status) {
            "working" -> "🟢"
            "maintenance" -> "🟡"
            "broken" -> "🔴"
            "lost" -> "⚫"
            else -> "⚪"
        }

        val message = if (voice) {
            "$statusEmoji Kifaa: $equipment — $status${notes?.let { " ($it)" } ?: ""}"
        } else {
            "Equipment ${if (isUpdate) "updated" else "recorded"}: $equipment — $status"
        }

        return ToolResult.success(name, mapOf(
            "equipment" to equipment, "status" to status, "is_update" to isUpdate
        ), message)
    }

    // ──────────────────────────────────────────────
    // ACTION: record_incident — Log safety incident
    // ──────────────────────────────────────────────

    private fun recordIncident(params: Map<String, String>): ToolResult {
        val incidentType = params["incident_type"]
            ?: return ToolResult.error(name, "Incident type required: collapse/flood/gas/injury/equipment", "MISSING_TYPE")
        val severity = params["severity"] ?: "minor"
        val location = params["location"]
        val description = params["description"]
        val injuries = params["injuries"]?.toIntOrNull() ?: 0
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val now = System.currentTimeMillis()

        val db = getDb()
        val values = ContentValues().apply {
            put("incident_type", incidentType)
            put("severity", severity)
            put("location", location)
            put("description", description)
            put("injuries", injuries)
            put("recorded_at", now)
        }
        val incidentId = db.insert(TABLE_INCIDENTS, null, values)

        val severityEmoji = when (severity) {
            "minor" -> "⚠️"
            "moderate" -> "🟡"
            "severe" -> "🔴"
            "fatal" -> "💀"
            else -> "⚠️"
        }

        val message = if (voice) {
            buildString {
                append("$severityEmoji Ajali yamerekodwa: $incidentType")
                append(" ($severity)")
                location?.let { append(" hapa $it") }
                if (injuries > 0) append("\n🩹 Majeruhi: $injuries")
                description?.let { append("\nMaelezo: $it") }
                if (severity == "severe" || severity == "fatal") {
                    append("\n\n🚨 TAARIFA: Ripoti ajali hii kwa mamlaka haraka!")
                }
            }
        } else {
            "Incident recorded: $incidentType ($severity) — id: $incidentId"
        }

        return ToolResult.success(name, mapOf(
            "incident_id" to incidentId, "type" to incidentType,
            "severity" to severity, "location" to location, "injuries" to injuries
        ), message)
    }

    // ──────────────────────────────────────────────
    // ACTION: record_price — Record mineral price from buyer
    // ──────────────────────────────────────────────

    private fun recordPrice(params: Map<String, String>): ToolResult {
        val rawMineral = params["mineral"]
            ?: return ToolResult.error(name, "Mineral type required", "MISSING_MINERAL")
        val mineral = normalizeMineral(rawMineral)
        val buyer = params["buyer"]
            ?: return ToolResult.error(name, "Buyer name required", "MISSING_BUYER")
        val price = params["price"]?.toDoubleOrNull()
            ?: return ToolResult.error(name, "Price per unit required (KES)", "MISSING_PRICE")
        val unit = params["unit"] ?: MINERAL_UNITS[mineral] ?: "grams"
        val location = params["location"]
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val db = getDb()
        val values = ContentValues().apply {
            put("mineral", mineral)
            put("buyer", buyer)
            put("price_per_unit", price)
            put("unit", unit)
            put("location", location)
            put("recorded_at", System.currentTimeMillis())
        }
        db.insert(TABLE_PRICES, null, values)

        return ToolResult.success(
            name, mapOf("mineral" to mineral, "buyer" to buyer, "price" to price, "unit" to unit),
            if (voice) "✅ Bei yamerekodwa: $mineral hapa $buyer — KES ${formatPrice(price)} kwa $unit. Asante!"
            else "Price recorded: $mineral @ $buyer = KES ${formatPrice(price)}/$unit"
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: compare_buyers — Compare prices across buyers
    // ──────────────────────────────────────────────

    private fun compareBuyers(params: Map<String, String>): ToolResult {
        val rawMineral = params["mineral"]
            ?: return ToolResult.error(name, "Mineral type required", "MISSING_MINERAL")
        val mineral = normalizeMineral(rawMineral)
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val db = getDb()
        val now = System.currentTimeMillis()
        val monthAgo = now - 30 * 24 * 60 * 60 * 1000L

        val prices = mutableListOf<Triple<String, Double, String>>() // buyer, price, location
        val cursor = db.rawQuery("""
            SELECT buyer, AVG(price_per_unit), location
            FROM $TABLE_PRICES WHERE mineral = ? AND recorded_at >= ?
            GROUP BY buyer ORDER BY AVG(price_per_unit) DESC
        """.trimIndent(), arrayOf(mineral, monthAgo.toString()))
        cursor.use { while (it.moveToNext()) prices.add(Triple(it.getString(0), it.getDouble(1), it.getString(2) ?: "")) }

        if (prices.isEmpty()) {
            val typicalPrice = TYPICAL_PRICES[mineral] ?: 0.0
            return ToolResult.success(
                name, mapOf("mineral" to mineral, "buyers" to emptyList<Any>(), "typical_price" to typicalPrice),
                if (voice) "Hakuna data ya bei ya $mineral. Bei ya kawaida: KES ${formatPrice(typicalPrice)}/${MINERAL_UNITS[mineral] ?: "unit"}.\nRekodda bei kwa 'record_price'!"
                else "No price data for $mineral. Typical: KES ${formatPrice(typicalPrice)}/${MINERAL_UNITS[mineral] ?: "unit"}."
            )
        }

        val best = prices.first()
        val worst = prices.last()
        val unit = MINERAL_UNITS[mineral] ?: "grams"

        val message = if (voice) {
            buildString {
                append("💰 Bei ya $mineral kwa wanunuzi:\n")
                prices.forEach { (buyer, price, loc) ->
                    append("• $buyer: KES ${formatPrice(price)} kwa $unit")
                    if (loc.isNotEmpty()) append(" ($loc)")
                    append("\n")
                }
                append("\n🥇 Bei bora: ${best.first} (KES ${formatPrice(best.second)})")
                if (prices.size > 1) {
                    append("\n🥈 Bei ya chini: ${worst.first} (KES ${formatPrice(worst.second)})")
                    val spread = ((best.second - worst.second) / worst.second * 100).toInt()
                    if (spread > 10) append("\n💰 Tofauti: $spread% — nunuzi hapa ${best.first}!")
                }
            }
        } else {
            buildString {
                append("$mineral buyer comparison:\n")
                prices.forEach { (b, p, l) -> append("• $b: KES ${formatPrice(p)}/$unit${if (l.isNotEmpty()) " ($l)" else ""}\n") }
                append("\nBest: ${best.first} @ KES ${formatPrice(best.second)}/$unit")
            }
        }

        return ToolResult.success(name, mapOf(
            "mineral" to mineral, "buyers" to prices.map { mapOf("buyer" to it.first, "price" to it.second, "location" to it.third) },
            "best_buyer" to best.first, "best_price" to best.second
        ), message)
    }

    // ──────────────────────────────────────────────
    // ACTION: extraction_history — Show extraction history
    // ──────────────────────────────────────────────

    private fun extractionHistory(params: Map<String, String>): ToolResult {
        val mineral = params["mineral"]?.let { normalizeMineral(it) }
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val db = getDb()
        val selection = mineral?.let { "mineral = ?" }
        val selectionArgs = mineral?.let { arrayOf(it) }

        val extractions = mutableListOf<Map<String, Any?>>()
        val cursor = db.query(TABLE_EXTRACTIONS, null, selection, selectionArgs, null, null, "recorded_at DESC", "20")
        cursor.use {
            while (it.moveToNext()) {
                extractions.add(mapOf(
                    "id" to it.getLong(it.getColumnIndexOrThrow("id")),
                    "mineral" to it.getString(it.getColumnIndexOrThrow("mineral")),
                    "weight" to it.getDouble(it.getColumnIndexOrThrow("weight")),
                    "unit" to it.getString(it.getColumnIndexOrThrow("unit")),
                    "quality" to it.getString(it.getColumnIndexOrThrow("quality")),
                    "location" to it.getString(it.getColumnIndexOrThrow("location")),
                    "recorded_at" to it.getLong(it.getColumnIndexOrThrow("recorded_at"))
                ))
            }
        }

        if (extractions.isEmpty()) {
            return ToolResult.success(name, mapOf("extractions" to emptyList<Any>()), "Hakuna uchimbaji${mineral?.let { " wa $it" } ?: ""} uliorekodwa.")
        }

        val message = if (voice) {
            buildString {
                append("⛏️ Historia ya uchimbaji:\n")
                extractions.take(10).forEach { e ->
                    val date = formatDate(e["recorded_at"] as Long)
                    append("• $date: ${e["mineral"]} ${formatQty(e["weight"] as Double)} ${e["unit"]}")
                    (e["quality"] as? String)?.let { append(" ($it)") }
                    append("\n")
                }
            }
        } else {
            buildString {
                append("Extraction history:\n")
                extractions.take(10).forEach { e ->
                    append("${formatDate(e["recorded_at"] as Long)}: ${e["mineral"]} ${formatQty(e["weight"] as Double)} ${e["unit"]}\n")
                }
            }
        }

        return ToolResult.success(name, mapOf("extractions" to extractions, "count" to extractions.size), message)
    }

    // ──────────────────────────────────────────────
    // ACTION: equipment_list — List equipment with status
    // ──────────────────────────────────────────────

    private fun equipmentList(params: Map<String, String>): ToolResult {
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val db = getDb()

        val equipment = mutableListOf<Map<String, Any?>>()
        val cursor = db.query(TABLE_EQUIPMENT, null, null, null, null, null, "status ASC")
        cursor.use {
            while (it.moveToNext()) {
                equipment.add(mapOf(
                    "name" to it.getString(it.getColumnIndexOrThrow("name")),
                    "type" to it.getString(it.getColumnIndexOrThrow("type")),
                    "status" to it.getString(it.getColumnIndexOrThrow("status")),
                    "purchase_cost" to it.getDoubleOrNull(it.getColumnIndexOrThrow("purchase_cost")),
                    "last_maintenance" to it.getLongOrNull(it.getColumnIndexOrThrow("last_maintenance"))
                ))
            }
        }

        if (equipment.isEmpty()) {
            return ToolResult.success(name, mapOf("equipment" to emptyList<Any>()), "Hakuna vifaa vilivyorekodwa. Tumia 'record_equipment' kuongeza.")
        }

        val statusEmoji = mapOf("working" to "🟢", "maintenance" to "🟡", "broken" to "🔴", "lost" to "⚫")

        val message = if (voice) {
            buildString {
                append("🔧 Vifaa vya uchimbaji:\n")
                equipment.forEach { eq ->
                    val emoji = statusEmoji[eq["status"]] ?: "⚪"
                    append("$emoji ${eq["name"]}: ${eq["status"]}")
                    (eq["type"] as? String)?.let { append(" ($it)") }
                    append("\n")
                }
                val broken = equipment.count { it["status"] == "broken" }
                if (broken > 0) append("\n⚠️ Vifaa $broken vimevunjika — vinahitaji matengenezo!")
            }
        } else {
            buildString {
                append("Equipment:\n")
                equipment.forEach { eq ->
                    append("${statusEmoji[eq["status"]] ?: "⚪"} ${eq["name"]}: ${eq["status"]}\n")
                }
            }
        }

        return ToolResult.success(name, mapOf("equipment" to equipment, "count" to equipment.size), message)
    }

    // ──────────────────────────────────────────────
    // ACTION: incident_history — Show safety incidents
    // ──────────────────────────────────────────────

    private fun incidentHistory(params: Map<String, String>): ToolResult {
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val db = getDb()

        val incidents = mutableListOf<Map<String, Any?>>()
        val cursor = db.query(TABLE_INCIDENTS, null, null, null, null, null, "recorded_at DESC", "20")
        cursor.use {
            while (it.moveToNext()) {
                incidents.add(mapOf(
                    "id" to it.getLong(it.getColumnIndexOrThrow("id")),
                    "type" to it.getString(it.getColumnIndexOrThrow("incident_type")),
                    "severity" to it.getString(it.getColumnIndexOrThrow("severity")),
                    "location" to it.getString(it.getColumnIndexOrThrow("location")),
                    "injuries" to it.getInt(it.getColumnIndexOrThrow("injuries")),
                    "description" to it.getString(it.getColumnIndexOrThrow("description")),
                    "recorded_at" to it.getLong(it.getColumnIndexOrThrow("recorded_at"))
                ))
            }
        }

        if (incidents.isEmpty()) {
            return ToolResult.success(name, mapOf("incidents" to emptyList<Any>()), "Hakuna ajali zilizorekodwa. Bwana! ✅")
        }

        val severeCount = incidents.count { (it["severity"] as String) in listOf("severe", "fatal") }
        val totalInjuries = incidents.sumOf { it["injuries"] as Int }

        val message = if (voice) {
            buildString {
                append("🚨 Historia ya ajali (${incidents.size}):\n")
                incidents.take(10).forEach { i ->
                    val emoji = when (i["severity"]) { "minor" -> "⚠️"; "moderate" -> "🟡"; "severe" -> "🔴"; "fatal" -> "💀"; else -> "⚠️" }
                    append("$emoji ${formatDate(i["recorded_at"] as Long)}: ${i["type"]} (${i["severity"]})")
                    val inj = i["injuries"] as Int; if (inj > 0) append(", majeruhi: ${i["injuries"]}")
                    append("\n")
                }
                if (severeCount > 0) append("\n🔴 Ajali $severeCount mbaya zimetokea!")
                if (totalInjuries > 0) append("\n🩹 Majeruhi jumla: $totalInjuries")
            }
        } else {
            buildString {
                append("Safety incidents (${incidents.size}):\n")
                incidents.take(10).forEach { i ->
                    append("${formatDate(i["recorded_at"] as Long)}: ${i["type"]} (${i["severity"]})")
                    val inj = i["injuries"] as Int; if (inj > 0) append(" | ${i["injuries"]} injured")
                    append("\n")
                }
            }
        }

        return ToolResult.success(name, mapOf(
            "incidents" to incidents, "total" to incidents.size,
            "severe_count" to severeCount, "total_injuries" to totalInjuries
        ), message)
    }

    // ──────────────────────────────────────────────
    // ACTION: earnings — Calculate earnings for a period
    // ──────────────────────────────────────────────

    private fun earnings(params: Map<String, String>): ToolResult {
        val period = params["period"] ?: "month"
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val db = getDb()
        val now = System.currentTimeMillis()
        val cutoff = when (period.lowercase()) {
            "week" -> now - 7 * 24 * 60 * 60 * 1000L
            "month" -> now - 30 * 24 * 60 * 60 * 1000L
            else -> now - 30 * 24 * 60 * 60 * 1000L
        }

        val cursor = db.rawQuery("""
            SELECT mineral, SUM(weight), unit, COUNT(*)
            FROM $TABLE_EXTRACTIONS WHERE recorded_at >= ?
            GROUP BY mineral, unit ORDER BY SUM(weight) DESC
        """.trimIndent(), arrayOf(cutoff.toString()))

        var totalValue = 0.0
        val mineralEarnings = mutableListOf<Triple<String, Double, Double>>() // mineral, weight, value

        cursor.use {
            while (it.moveToNext()) {
                val mineral = it.getString(0)
                val weight = it.getDouble(1)
                val price = TYPICAL_PRICES[mineral] ?: 0.0
                val value = weight * price
                totalValue += value
                mineralEarnings.add(Triple(mineral, weight, value))
            }
        }

        if (mineralEarnings.isEmpty()) {
            return ToolResult.success(name, mapOf("earnings" to emptyList<Any>()), "Hakuna uchimbaji kwa $period iliyopita.")
        }

        val message = if (voice) {
            buildString {
                append("💰 Mapato ya uchimbaji ($period):\n\n")
                mineralEarnings.forEach { (mineral, weight, value) ->
                    val unit = MINERAL_UNITS[mineral] ?: "grams"
                    val price = TYPICAL_PRICES[mineral] ?: 0.0
                    append("• $mineral: ${formatQty(weight)} $unit × KES ${formatPrice(price)} = KES ${formatPrice(value)}\n")
                }
                append("\n📈 Jumla: KES ${formatPrice(totalValue)}")
                append("\n⚠️ Hii ni thamani ya makadirio. Bei halisi inategemea mnunuzi.")
            }
        } else {
            buildString {
                append("Mining earnings ($period):\n")
                mineralEarnings.forEach { (m, w, v) -> append("• $m: ${formatQty(w)} ${MINERAL_UNITS[m]} = KES ${formatPrice(v)}\n") }
                append("\nTotal: KES ${formatPrice(totalValue)}")
            }
        }

        return ToolResult.success(name, mapOf(
            "total_value" to totalValue,
            "minerals" to mineralEarnings.map { mapOf("mineral" to it.first, "weight" to it.second, "value" to it.third) }
        ), message)
    }

    // ──────────────────────────────────────────────
    // ACTION: safety_tips — Safety guidance
    // ──────────────────────────────────────────────

    private fun safetyTips(params: Map<String, String>): ToolResult {
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val message = if (voice) {
            """
🛡️ Vidokezo vya usalama wa uchimbaji:

1. ⛑️ Daima vaa kofia ngumu na viatu vya usalama
2. 💨 Hakikisha hewa inatosha kabla ya kuingia shimoni
3. 🔦 Boba taa za ziada na betri
4. 👥 Usichimbe peke yako — daima kuwa na mtu wa karibu
5. 🪢 Tumia kamba za usalama kwa kina kirefu
6. ⚡ Epuka kuchimba karibu na nyaya za umeme
7. 🏗️ Hakikisha ukuta wa shimoni ni imara kabla ya kuingia
8. 📱 Boba simu au njia ya kuwasiliana na nje
9. 🩹 Boba vifaa vya kwanza (first aid kit)
10. 🚫 Usitumie vilevi au madawa kabla au wakati wa kuchimba

🚨 Ikiwa ajali imetokea:
• Ondoa watu salama kwanza
• Piga simu 999 au 112
• Usijaribu kuokoa bila vifaa vya usalama
            """.trimIndent()
        } else {
            """
Safety tips for artisanal miners:

1. Always wear hard hats and safety boots
2. Ensure adequate ventilation before entering shafts
3. Carry backup lights and batteries
4. Never mine alone — always have someone nearby
5. Use safety ropes for deep excavations
6. Avoid mining near power lines
7. Check shaft walls are stable before entry
8. Carry a phone or communication device
9. Carry a first aid kit
10. Never use alcohol or drugs before/during mining

Emergency: Call 999 or 112
            """.trimIndent()
        }

        return ToolResult.success(name, mapOf("tips_count" to 10), message)
    }

    // ──────────────────────────────────────────────
    // ACTION: voice_parse — Parse Swahili voice input
    // ──────────────────────────────────────────────

    private fun voiceParse(params: Map<String, String>): ToolResult {
        val text = params["notes"] ?: params["mineral"]
            ?: return ToolResult.error(name, "Voice text required", "MISSING_TEXT")
        val parsed = parseSwahiliMiningVoice(text)
        return ToolResult.success(name, parsed, "Parsed: $parsed")
    }

    fun parseSwahiliMiningVoice(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val lower = text.lowercase().trim()

        // Detect action
        when {
            lower.contains(Regex("nimepata|nimetoka|nimechimba|nimetega|extraction|found")) -> result["action"] = "record_extraction"
            lower.contains(Regex("kifaa|equipment|machine|jembe|pick|shovel")) -> result["action"] = "record_equipment"
            lower.contains(Regex("ajali|accident|incident|imevunjika|imeanguka")) -> result["action"] = "record_incident"
            lower.contains(Regex("bei|price|mnunuzi|buyer")) -> result["action"] = if (lower.contains(Regex("compare|linganisha"))) "compare_buyers" else "record_price"
            lower.contains(Regex("mapato|earnings|pesa")) -> result["action"] = "earnings"
            lower.contains(Regex("usalama|safety|tips")) -> result["action"] = "safety_tips"
        }

        // Extract mineral
        for ((alias, canonical) in MINERAL_ALIASES.entries.sortedByDescending { it.key.length }) {
            if (lower.contains(alias)) {
                result["mineral"] = canonical
                break
            }
        }

        // Extract weight: "gramu 5", "grams 5", "pointi 3" (carats)
        val weightPatterns = listOf(
            Regex("""(\d+\.?\d*)\s*(?:gramu|grams?|g)\b"""),
            Regex("""(?:gramu|grams?|g)\s*(\d+\.?\d*)"""),
            Regex("""(\d+\.?\d*)\s*(?:carats?|pointi|ct)\b"""),
            Regex("""(?:carats?|pointi|ct)\s*(\d+\.?\d*)"""),
            Regex("""(\d+\.?\d*)\s*(?:kilo|kg)\b""")
        )
        for (pattern in weightPatterns) {
            pattern.find(lower)?.let { match ->
                result["weight"] = match.groupValues[1]
                // Infer unit from pattern
                val matched = match.value.lowercase()
                when {
                    matched.contains(Regex("carat|pointi|ct")) -> result["unit"] = "carats"
                    matched.contains(Regex("kilo|kg")) -> result["unit"] = "kg"
                    else -> result["unit"] = "grams"
                }
            }
        }

        // Extract location
        val locationPatterns = listOf(
            Regex("""shahara\s+(?:ya\s+)?(\w+)"""),
            Regex("""mgodi\s+(?:wa\s+)?(\w+)"""),
            Regex("""hapa\s+(\w+)""")
        )
        for (pattern in locationPatterns) {
            pattern.find(lower)?.let { result["location"] = it.groupValues[1] }
        }

        // Extract incident type
        when {
            lower.contains(Regex("collapse|anguka|kufunguka")) -> { result["incident_type"] = "collapse"; result["action"] = "record_incident" }
            lower.contains(Regex("flood|maji|majimaji")) -> { result["incident_type"] = "flood"; result["action"] = "record_incident" }
            lower.contains(Regex("gas|hewa|sumu")) -> { result["incident_type"] = "gas"; result["action"] = "record_incident" }
            lower.contains(Regex("injury|jeruhi|kujeruhi")) -> { result["incident_type"] = "injury"; result["action"] = "record_incident" }
        }

        return result
    }

    // ──────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────

    private fun normalizeMineral(raw: String): String {
        return MINERAL_ALIASES[raw.trim().lowercase()] ?: raw.trim().lowercase()
    }

    private fun formatQty(qty: Double): String = if (qty == qty.toLong().toDouble()) "%,.0f".format(qty) else "%,.1f".format(qty)
    private fun formatPrice(price: Double): String = if (price == price.toLong().toDouble()) "%,.0f".format(price) else "%,.1f".format(price)
    private fun formatDate(ts: Long): String = java.text.SimpleDateFormat("dd MMM", java.util.Locale.getDefault()).format(java.util.Date(ts))
}

// Cursor extension for nullable types
private fun android.database.Cursor.getDoubleOrNull(columnIndex: Int): Double? = if (isNull(columnIndex)) null else getDouble(columnIndex)
private fun android.database.Cursor.getLongOrNull(columnIndex: Int): Long? = if (isNull(columnIndex)) null else getLong(columnIndex)
