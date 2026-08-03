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
 * FishingLog — Specialized catch and trip tracking for fishermen.
 *
 * Problem: Fishermen at Lake Victoria, Indian Ocean, and other water bodies lose
 * 30-50% of their catch value because they can't track what they catch, when,
 * or where. Middlemen at landing sites buy at 30-50% of market price because
 * fishermen have no price visibility. This tool gives fishermen voice-first
 * catch logging, trip tracking, price comparison across landing sites, and
 * best-day predictions based on historical patterns.
 *
 * Voice examples:
 *   "Nimevua samaki kilo 20 ya Nile perch"    → Logged: Nile perch, 20kg
 *   "Safari ya leo ilikuwa saa 4"             → Trip: 4 hours
 *   "Bei ya samaki Bandari ya Kisia"          → Price at Kisia landing site
 *   "Siku bora za kuvua wiki hii"             → Best fishing days
 *   "Nimetumia petroli lita 20"               → Fuel: 20 litres
 */
@Singleton
class FishingLog @Inject constructor(
    private val context: Context
) : Tool {

    override val name = "fishing_log"
    override val description = "Track fishing catch by species/weight/location, log trips with fuel costs, compare prices at landing sites, and predict best fishing days."

    override val argsSchema = argSchema {
        enum(
            "action", "Action to perform",
            listOf(
                "record_catch",      // Log a catch: "Nimevua samaki kilo 20"
                "record_trip",       // Log a fishing trip with duration/fuel
                "catch_history",     // Show recent catches
                "trip_history",      // Show recent trips
                "catch_summary",     // Summary by species/period
                "compare_prices",    // Compare prices at landing sites
                "record_price",      // Record price at a landing site
                "best_days",         // Predict best fishing days
                "earnings",          // Calculate earnings for a period
                "species_list",      // List all caught species
                "fuel_analysis",     // Fuel cost analysis
                "voice_parse"        // Parse Swahili voice input
            ),
            required = true
        )
        string("species", "Fish species (e.g. 'Nile perch', 'tilapia', 'omena')", required = false)
        number("weight", "Catch weight in kg", required = false)
        number("quantity", "Number of fish (if counted, not weighed)", required = false)
        string("location", "Fishing location or landing site", required = false)
        string("method", "Fishing method: net/line/trap/cage", required = false)
        number("duration_hours", "Trip duration in hours", required = false)
        number("fuel_litres", "Fuel used in litres", required = false)
        number("fuel_cost", "Fuel cost in KES", required = false)
        number("price", "Price per kg at landing site", required = false)
        string("market", "Landing site/market name", required = false)
        string("period", "Time period: week/month/season", required = false)
        number("trip_id", "Trip ID for linking catches", required = false)
        string("weather", "Weather conditions: sunny/rainy/windy/calm", required = false)
        string("moon_phase", "Moon phase: new/full/quarter/crescent", required = false)
        boolean("voice", "Format for voice output", required = false)
    }

    // ──────────────────────────────────────────────
    // SQLite Database
    // ──────────────────────────────────────────────

    inner class FishingDatabase(context: Context) :
        SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            // Catch records
            db.execSQL("""
                CREATE TABLE $TABLE_CATCHES (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    species TEXT NOT NULL,
                    weight REAL,
                    quantity INTEGER,
                    unit TEXT NOT NULL DEFAULT 'kg',
                    location TEXT,
                    method TEXT,
                    trip_id INTEGER,
                    quality TEXT,
                    notes TEXT,
                    recorded_at INTEGER NOT NULL
                )
            """)

            // Fishing trips
            db.execSQL("""
                CREATE TABLE $TABLE_TRIPS (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    location TEXT NOT NULL,
                    duration_hours REAL,
                    fuel_litres REAL,
                    fuel_cost REAL,
                    crew_size INTEGER DEFAULT 1,
                    weather TEXT,
                    moon_phase TEXT,
                    total_catch_kg REAL DEFAULT 0,
                    total_earnings REAL DEFAULT 0,
                    started_at INTEGER,
                    recorded_at INTEGER NOT NULL
                )
            """)

            // Price observations at landing sites
            db.execSQL("""
                CREATE TABLE $TABLE_PRICES (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    species TEXT NOT NULL,
                    market TEXT NOT NULL,
                    price_per_kg REAL NOT NULL,
                    recorded_at INTEGER NOT NULL
                )
            """)

            // Best day patterns — aggregated from historical data
            db.execSQL("""
                CREATE TABLE $TABLE_PATTERNS (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    day_of_week INTEGER,
                    moon_phase TEXT,
                    weather TEXT,
                    avg_catch REAL,
                    sample_count INTEGER,
                    updated_at INTEGER
                )
            """)

            db.execSQL("CREATE INDEX idx_catches_species ON $TABLE_CATCHES(species)")
            db.execSQL("CREATE INDEX idx_catches_date ON $TABLE_CATCHES(recorded_at)")
            db.execSQL("CREATE INDEX idx_catches_location ON $TABLE_CATCHES(location)")
            db.execSQL("CREATE INDEX idx_trips_date ON $TABLE_TRIPS(recorded_at)")
            db.execSQL("CREATE INDEX idx_prices_species ON $TABLE_PRICES(species, market)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_CATCHES")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_TRIPS")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_PRICES")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_PATTERNS")
            onCreate(db)
        }
    }

    companion object {
        private const val DB_NAME = "fishing_log.db"
        private const val DB_VERSION = 1
        private const val TABLE_CATCHES = "catches"
        private const val TABLE_TRIPS = "trips"
        private const val TABLE_PRICES = "fish_prices"
        private const val TABLE_PATTERNS = "fishing_patterns"

        // Common fish species in East Africa
        val SPECIES_ALIASES = mapOf(
            "sangara" to "Nile perch", "nguru" to "Nile perch", "nile perch" to "Nile perch",
            "tilapia" to "tilapia", "ngege" to "tilapia",
            "omena" to "omena", "dagaa" to "omena", "sardine" to "omena",
            "kamongo" to "mudfish", "mudfish" to "mudfish",
            "ng'ania" to "catfish", "catfish" to "catfish",
            "changu" to "rabbitfish", "rabbitfish" to "rabbitfish",
            "papa" to "shark", "shark" to "shark",
            "tuna" to "tuna",
            "kolekole" to "red snapper", "red snapper" to "red snapper",
            "jabi" to "mackerel", "mackerel" to "mackerel",
            "nguruwe wa maji" to "hippo fish"
        )

        // Typical prices per kg at major landing sites (KES)
        val TYPICAL_PRICES = mapOf(
            "Nile perch" to 350.0,
            "tilapia" to 400.0,
            "omena" to 200.0,
            "mudfish" to 300.0,
            "catfish" to 350.0,
            "tuna" to 500.0,
            "red snapper" to 450.0,
            "mackerel" to 300.0
        )
    }

    private var dbHelper: FishingDatabase? = null

    private fun getDb(): SQLiteDatabase {
        if (dbHelper == null) dbHelper = FishingDatabase(context)
        return dbHelper!!.writableDatabase
    }

    // ──────────────────────────────────────────────
    // Tool Execute
    // ──────────────────────────────────────────────

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"]
            ?: return ToolResult.error(name, "Action required", "MISSING_ACTION")

        return when (action.lowercase()) {
            "record_catch" -> recordCatch(params)
            "record_trip" -> recordTrip(params)
            "catch_history" -> catchHistory(params)
            "trip_history" -> tripHistory(params)
            "catch_summary" -> catchSummary(params)
            "compare_prices" -> comparePrices(params)
            "record_price" -> recordPrice(params)
            "best_days" -> bestDays(params)
            "earnings" -> earnings(params)
            "species_list" -> speciesList()
            "fuel_analysis" -> fuelAnalysis(params)
            "voice_parse" -> voiceParse(params)
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    // ──────────────────────────────────────────────
    // ACTION: record_catch — Log a catch
    // Voice: "Nimevua samaki kilo 20 ya Nile perch"
    // ──────────────────────────────────────────────

    private fun recordCatch(params: Map<String, String>): ToolResult {
        val rawSpecies = params["species"]
            ?: return ToolResult.error(name, "Fish species required (e.g. 'tilapia', 'sangara', 'omena')", "MISSING_SPECIES")
        val species = normalizeSpecies(rawSpecies)
        val weight = params["weight"]?.toDoubleOrNull()
        val quantity = params["quantity"]?.toIntOrNull()
        val location = params["location"]
        val method = params["method"]
        val tripId = params["trip_id"]?.toLongOrNull()
        val quality = params["quality"]
        val notes = params["notes"]
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val now = System.currentTimeMillis()

        if (weight == null && quantity == null) {
            return ToolResult.error(name, "Weight (kg) or quantity (number of fish) required", "MISSING_MEASUREMENT")
        }

        val db = getDb()
        val values = ContentValues().apply {
            put("species", species)
            put("weight", weight)
            put("quantity", quantity)
            put("unit", "kg")
            put("location", location)
            put("method", method)
            put("trip_id", tripId)
            put("quality", quality)
            put("notes", notes)
            put("recorded_at", now)
        }
        val catchId = db.insert(TABLE_CATCHES, null, values)

        // Update trip total catch if linked
        if (tripId != null && weight != null) {
            db.execSQL("UPDATE $TABLE_TRIPS SET total_catch_kg = total_catch_kg + $weight WHERE id = $tripId")
        }

        Timber.d("Recorded catch: $species ${weight ?: quantity}kg (id=$catchId)")

        val price = TYPICAL_PRICES[species] ?: 300.0
        val estimatedValue = (weight ?: (quantity?.toDouble() ?: 1.0) * 2.0) * price

        val message = if (voice) {
            buildString {
                append("✅ Uvuvo umerekodwa: $species")
                weight?.let { append(" ${formatQty(it)} kg") }
                quantity?.let { append(" samaki $it") }
                location?.let { append(" hapa $it") }
                method?.let { append(" (mbinu: $it)") }
                append("\n💰 Thamani ya makadirio: KES ${formatPrice(estimatedValue)}")
                append("\nId: $catchId")
            }
        } else {
            buildString {
                append("Catch recorded: $species")
                weight?.let { append(" ${formatQty(it)}kg") }
                quantity?.let { append(" ${it} fish") }
                location?.let { append(" @ $it") }
                append(" (id: $catchId)")
            }
        }

        return ToolResult.success(name, mapOf(
            "catch_id" to catchId, "species" to species, "weight" to weight,
            "quantity" to quantity, "location" to location, "method" to method,
            "estimated_value" to estimatedValue
        ), message)
    }

    // ──────────────────────────────────────────────
    // ACTION: record_trip — Log a fishing trip
    // ──────────────────────────────────────────────

    private fun recordTrip(params: Map<String, String>): ToolResult {
        val location = params["location"]
            ?: return ToolResult.error(name, "Fishing location required", "MISSING_LOCATION")
        val durationHours = params["duration_hours"]?.toDoubleOrNull()
        val fuelLitres = params["fuel_litres"]?.toDoubleOrNull()
        val fuelCost = params["fuel_cost"]?.toDoubleOrNull()
        val weather = params["weather"]
        val moonPhase = params["moon_phase"]
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val now = System.currentTimeMillis()

        val db = getDb()
        val values = ContentValues().apply {
            put("location", location)
            put("duration_hours", durationHours)
            put("fuel_litres", fuelLitres)
            put("fuel_cost", fuelCost)
            put("weather", weather)
            put("moon_phase", moonPhase)
            put("started_at", now)
            put("recorded_at", now)
        }
        val tripId = db.insert(TABLE_TRIPS, null, values)

        val message = if (voice) {
            buildString {
                append("⛵ Safari ya uvuvi yamerekodwa (Id: $tripId)\n")
                append("• Mahali: $location\n")
                durationHours?.let { append("• Muda: saa $it\n") }
                fuelLitres?.let { append("• Petroli: lita $it\n") }
                fuelCost?.let { append("• Gharama ya mafuta: KES ${formatPrice(it)}\n") }
                weather?.let { append("• Hali ya hewa: $it\n") }
                moonPhase?.let { append("• Mwezi: $it\n") }
                append("\nRekodda uvuvo kwa 'record_catch' ukitumia trip_id: $tripId")
            }
        } else {
            "Trip recorded: $location (id: $tripId)${durationHours?.let { " | ${it}h" } ?: ""}${fuelLitres?.let { " | ${it}L fuel" } ?: ""}"
        }

        return ToolResult.success(name, mapOf(
            "trip_id" to tripId, "location" to location,
            "duration_hours" to durationHours, "fuel_litres" to fuelLitres,
            "fuel_cost" to fuelCost, "weather" to weather, "moon_phase" to moonPhase
        ), message)
    }

    // ──────────────────────────────────────────────
    // ACTION: catch_history — Show recent catches
    // ──────────────────────────────────────────────

    private fun catchHistory(params: Map<String, String>): ToolResult {
        val species = params["species"]?.let { normalizeSpecies(it) }
        val limit = 20
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val db = getDb()
        val selection = species?.let { "species = ?" }
        val selectionArgs = species?.let { arrayOf(it) }

        val catches = mutableListOf<CatchRecord>()
        val cursor = db.query(TABLE_CATCHES, null, selection, selectionArgs, null, null, "recorded_at DESC", limit.toString())
        cursor.use {
            while (it.moveToNext()) {
                catches.add(CatchRecord(
                    id = it.getLong(it.getColumnIndexOrThrow("id")),
                    species = it.getString(it.getColumnIndexOrThrow("species")),
                    weight = it.getDoubleOrNull(it.getColumnIndexOrThrow("weight")),
                    quantity = it.getIntOrNull(it.getColumnIndexOrThrow("quantity")),
                    location = it.getString(it.getColumnIndexOrThrow("location")),
                    method = it.getString(it.getColumnIndexOrThrow("method")),
                    tripId = it.getLongOrNull(it.getColumnIndexOrThrow("trip_id")),
                    recordedAt = it.getLong(it.getColumnIndexOrThrow("recorded_at"))
                ))
            }
        }

        if (catches.isEmpty()) {
            return ToolResult.success(name, mapOf("catches" to emptyList<Any>()), "Hakuna uvuvo${species?.let { " wa $it" } ?: ""} uliorekodwa.")
        }

        val message = if (voice) {
            buildString {
                append("🎣 Uvuvo wa hivi karibuni:\n")
                catches.take(10).forEach { c ->
                    val date = formatDate(c.recordedAt)
                    append("• $date: ${c.species}")
                    c.weight?.let { append(" ${formatQty(it)}kg") }
                    c.location?.let { append(" ($it)") }
                    append("\n")
                }
            }
        } else {
            buildString {
                append("Recent catches (${catches.size}):\n")
                catches.take(10).forEach { c ->
                    append("${formatDate(c.recordedAt)}: ${c.species}")
                    c.weight?.let { append(" ${formatQty(it)}kg") }
                    c.location?.let { append(" @ $it") }
                    append("\n")
                }
            }
        }

        return ToolResult.success(name, mapOf(
            "catches" to catches.map { mapOf("id" to it.id, "species" to it.species, "weight" to it.weight, "location" to it.location) },
            "count" to catches.size
        ), message)
    }

    // ──────────────────────────────────────────────
    // ACTION: trip_history — Show recent trips
    // ──────────────────────────────────────────────

    private fun tripHistory(params: Map<String, String>): ToolResult {
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val db = getDb()

        val trips = mutableListOf<TripRecord>()
        val cursor = db.query(TABLE_TRIPS, null, null, null, null, null, "recorded_at DESC", "10")
        cursor.use {
            while (it.moveToNext()) {
                trips.add(TripRecord(
                    id = it.getLong(it.getColumnIndexOrThrow("id")),
                    location = it.getString(it.getColumnIndexOrThrow("location")),
                    durationHours = it.getDoubleOrNull(it.getColumnIndexOrThrow("duration_hours")),
                    fuelLitres = it.getDoubleOrNull(it.getColumnIndexOrThrow("fuel_litres")),
                    fuelCost = it.getDoubleOrNull(it.getColumnIndexOrThrow("fuel_cost")),
                    weather = it.getString(it.getColumnIndexOrThrow("weather")),
                    totalCatchKg = it.getDouble(it.getColumnIndexOrThrow("total_catch_kg")),
                    totalEarnings = it.getDouble(it.getColumnIndexOrThrow("total_earnings")),
                    recordedAt = it.getLong(it.getColumnIndexOrThrow("recorded_at"))
                ))
            }
        }

        if (trips.isEmpty()) {
            return ToolResult.success(name, mapOf("trips" to emptyList<Any>()), "Hakuna safari za uvuvi zilizorekodwa.")
        }

        val message = if (voice) {
            buildString {
                append("⛵ Safari za hivi karibuni:\n")
                trips.forEach { t ->
                    val date = formatDate(t.recordedAt)
                    append("• $date: ${t.location}")
                    t.durationHours?.let { append(", saa $it") }
                    t.totalCatchKg?.let { if (it > 0) append(", ${formatQty(it)}kg") }
                    append("\n")
                }
            }
        } else {
            buildString {
                append("Recent trips:\n")
                trips.forEach { t ->
                    append("${formatDate(t.recordedAt)}: ${t.location}")
                    t.durationHours?.let { append(" | ${it}h") }
                    t.fuelLitres?.let { append(" | ${it}L") }
                    t.totalCatchKg.let { if (it > 0) append(" | ${formatQty(it)}kg") }
                    append("\n")
                }
            }
        }

        return ToolResult.success(name, mapOf("trips" to trips.size), message)
    }

    // ──────────────────────────────────────────────
    // ACTION: catch_summary — Summary by species/period
    // ──────────────────────────────────────────────

    private fun catchSummary(params: Map<String, String>): ToolResult {
        val period = params["period"] ?: "month"
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val db = getDb()
        val now = System.currentTimeMillis()
        val cutoff = when (period.lowercase()) {
            "week" -> now - 7 * 24 * 60 * 60 * 1000L
            "month" -> now - 30 * 24 * 60 * 60 * 1000L
            "season" -> now - 90 * 24 * 60 * 60 * 1000L
            else -> now - 30 * 24 * 60 * 60 * 1000L
        }

        val cursor = db.rawQuery("""
            SELECT species, SUM(COALESCE(weight, 0)) as total_weight,
                   SUM(COALESCE(quantity, 0)) as total_qty, COUNT(*) as catch_count
            FROM $TABLE_CATCHES WHERE recorded_at >= ?
            GROUP BY species ORDER BY total_weight DESC
        """.trimIndent(), arrayOf(cutoff.toString()))

        val summaries = mutableListOf<SpeciesSummary>()
        cursor.use {
            while (it.moveToNext()) {
                summaries.add(SpeciesSummary(
                    species = it.getString(0),
                    totalWeight = it.getDouble(1),
                    totalQuantity = it.getInt(2),
                    catchCount = it.getInt(3)
                ))
            }
        }

        if (summaries.isEmpty()) {
            return ToolResult.success(name, mapOf("summaries" to emptyList<Any>()), "Hakuna uvuvo kwa $period iliyopita.")
        }

        val totalWeight = summaries.sumOf { it.totalWeight }
        val totalCatch = summaries.sumOf { it.catchCount }

        val message = if (voice) {
            buildString {
                append("📊 Muhtasari wa uvuvo ($period):\n")
                append("Jumla: ${formatQty(totalWeight)} kg ($totalCatch safari)\n\n")
                summaries.forEach { s ->
                    val pct = if (totalWeight > 0) (s.totalWeight / totalWeight * 100).toInt() else 0
                    val price = TYPICAL_PRICES[s.species] ?: 300.0
                    val value = s.totalWeight * price
                    append("• ${s.species}: ${formatQty(s.totalWeight)} kg ($pct%)")
                    append(" — KES ${formatPrice(value)}\n")
                }
            }
        } else {
            buildString {
                append("Catch summary ($period):\nTotal: ${formatQty(totalWeight)}kg across $catch catches\n\n")
                summaries.forEach { s ->
                    append("• ${s.species}: ${formatQty(s.totalWeight)}kg (${s.catchCount} catches)\n")
                }
            }
        }

        return ToolResult.success(name, mapOf(
            "summaries" to summaries.map { mapOf("species" to it.species, "weight" to it.totalWeight, "count" to it.catchCount) },
            "total_weight" to totalWeight, "total_catches" to totalCatch
        ), message)
    }

    // ──────────────────────────────────────────────
    // ACTION: compare_prices — Compare prices at landing sites
    // ──────────────────────────────────────────────

    private fun comparePrices(params: Map<String, String>): ToolResult {
        val rawSpecies = params["species"]
            ?: return ToolResult.error(name, "Fish species required", "MISSING_SPECIES")
        val species = normalizeSpecies(rawSpecies)
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val db = getDb()
        val now = System.currentTimeMillis()
        val weekAgo = now - 7 * 24 * 60 * 60 * 1000L

        val prices = mutableListOf<Pair<String, Double>>()
        val cursor = db.rawQuery("""
            SELECT market, AVG(price_per_kg) as avg_price
            FROM $TABLE_PRICES WHERE species = ? AND recorded_at >= ?
            GROUP BY market ORDER BY avg_price DESC
        """.trimIndent(), arrayOf(species, weekAgo.toString()))
        cursor.use { while (it.moveToNext()) prices.add(Pair(it.getString(0), it.getDouble(1))) }

        if (prices.isEmpty()) {
            val typicalPrice = TYPICAL_PRICES[species] ?: 300.0
            return ToolResult.success(
                name, mapOf("species" to species, "prices" to emptyList<Any>(), "typical_price" to typicalPrice),
                if (voice) "Hakuna data ya bei ya $species bandarini. Bei ya kawaida: KES ${formatPrice(typicalPrice)}/kg.\nRekodda bei kwa 'record_price' kusaidia wavuvi wengine!"
                else "No price data for $species at landing sites. Typical: KES ${formatPrice(typicalPrice)}/kg."
            )
        }

        val best = prices.maxByOrNull { it.second }
        val worst = prices.minByOrNull { it.second }

        val message = if (voice) {
            buildString {
                append("💰 Bei ya $species bandarini:\n")
                prices.forEach { (market, price) ->
                    append("• $market: KES ${formatPrice(price)}/kg\n")
                }
                best?.let { append("\n🥇 Bei bora: ${it.first} (KES ${formatPrice(it.second)})") }
                worst?.let { append("\n🥈 Bei ya chini: ${it.first} (KES ${formatPrice(it.second)})") }
                if (prices.size > 1 && best != null && worst != null) {
                    val spread = ((best.second - worst.second) / worst.second * 100).toInt()
                    if (spread > 10) append("\n💰 Tofauti: $spread% — uza hapa ${best.first}!")
                }
            }
        } else {
            buildString {
                append("$species prices at landing sites:\n")
                prices.forEach { (m, p) -> append("• $m: KES ${formatPrice(p)}/kg\n") }
            }
        }

        return ToolResult.success(name, mapOf(
            "species" to species,
            "prices" to prices.map { mapOf("market" to it.first, "price" to it.second) },
            "best_market" to best?.first, "best_price" to best?.second
        ), message)
    }

    // ──────────────────────────────────────────────
    // ACTION: record_price — Record price at landing site
    // ──────────────────────────────────────────────

    private fun recordPrice(params: Map<String, String>): ToolResult {
        val rawSpecies = params["species"]
            ?: return ToolResult.error(name, "Fish species required", "MISSING_SPECIES")
        val species = normalizeSpecies(rawSpecies)
        val market = params["market"]
            ?: return ToolResult.error(name, "Landing site/market name required", "MISSING_MARKET")
        val price = params["price"]?.toDoubleOrNull()
            ?: return ToolResult.error(name, "Price per kg required (KES)", "MISSING_PRICE")
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val db = getDb()
        val values = ContentValues().apply {
            put("species", species)
            put("market", market)
            put("price_per_kg", price)
            put("recorded_at", System.currentTimeMillis())
        }
        db.insert(TABLE_PRICES, null, values)

        return ToolResult.success(
            name, mapOf("species" to species, "market" to market, "price" to price),
            if (voice) "✅ Bei yamerekodwa: $species hapa $market — KES ${formatPrice(price)}/kg. Asante!"
            else "Price recorded: $species @ $market = KES ${formatPrice(price)}/kg"
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: best_days — Predict best fishing days
    // ──────────────────────────────────────────────

    private fun bestDays(params: Map<String, String>): ToolResult {
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val db = getDb()
        val now = System.currentTimeMillis()
        val yearAgo = now - 365L * 24 * 60 * 60 * 1000L

        // Analyze by day of week
        val cursor = db.rawQuery("""
            SELECT strftime('%w', recorded_at / 1000, 'unixepoch') as dow,
                   AVG(COALESCE(weight, 0)) as avg_catch, COUNT(*) as trips
            FROM $TABLE_CATCHES WHERE recorded_at >= ?
            GROUP BY dow ORDER BY avg_catch DESC
        """.trimIndent(), arrayOf(yearAgo.toString()))

        val dayStats = mutableListOf<Triple<Int, Double, Int>>() // dow, avg_catch, trips
        cursor.use { while (it.moveToNext()) dayStats.add(Triple(it.getInt(0), it.getDouble(1), it.getInt(2))) }

        if (dayStats.isEmpty()) {
            return ToolResult.success(
                name, mapOf("best_days" to emptyList<Any>()),
                if (voice) "Hakuna data ya kutosha kutabiri siku bora za kuvua. Rekodda safari zaidi!"
                else "Insufficient data for best-day prediction. Record more trips!"
            )
        }

        val dayNames = arrayOf("Jumapili", "Jumatatu", "Jumanne", "Jumatano", "Alhamisi", "Ijumaa", "Jumamosi")
        val bestDays = dayStats.sortedByDescending { it.second }

        val message = if (voice) {
            buildString {
                append("📅 Siku bora za kuvua (kulingana na historia):\n\n")
                bestDays.forEach { (dow, avg, trips) ->
                    val bar = "█".repeat((avg / bestDays.first().second * 15).toInt().coerceIn(1, 15))
                    append("• ${dayNames[dow]}: ${formatQty(avg)} kg $bar ($trips safari)\n")
                }
                append("\n🥇 Siku bora: ${dayNames[bestDays.first().first]}")
                append("\n🔴 Siku mbaya: ${dayNames[bestDays.last().first]}")
            }
        } else {
            buildString {
                append("Best fishing days (based on history):\n")
                bestDays.forEach { (dow, avg, trips) ->
                    append("• ${dayNames[dow]}: avg ${formatQty(avg)}kg ($trips trips)\n")
                }
            }
        }

        return ToolResult.success(name, mapOf(
            "best_days" to bestDays.map { mapOf("day" to dayNames[it.first], "avg_catch" to it.second, "trips" to it.third) }
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

        // Get total catch by species
        val catches = mutableMapOf<String, Double>()
        val catchCursor = db.rawQuery("""
            SELECT species, SUM(COALESCE(weight, 0)) FROM $TABLE_CATCHES
            WHERE recorded_at >= ? GROUP BY species
        """.trimIndent(), arrayOf(cutoff.toString()))
        catchCursor.use { while (it.moveToNext()) catches[it.getString(0)] = it.getDouble(1) }

        // Get total fuel costs
        val fuelCursor = db.rawQuery("""
            SELECT SUM(COALESCE(fuel_cost, 0)), SUM(COALESCE(fuel_litres, 0))
            FROM $TABLE_TRIPS WHERE recorded_at >= ?
        """.trimIndent(), arrayOf(cutoff.toString()))
        var totalFuelCost = 0.0
        var totalFuelLitres = 0.0
        fuelCursor.use { if (it.moveToFirst()) { totalFuelCost = it.getDouble(0); totalFuelLitres = it.getDouble(1) } }

        var totalEarnings = 0.0
        catches.forEach { (species, weight) ->
            val price = TYPICAL_PRICES[species] ?: 300.0
            totalEarnings += weight * price
        }
        val netEarnings = totalEarnings - totalFuelCost

        val message = if (voice) {
            buildString {
                append("💰 Mapato ($period):\n\n")
                catches.forEach { (species, weight) ->
                    val price = TYPICAL_PRICES[species] ?: 300.0
                    val value = weight * price
                    append("• $species: ${formatQty(weight)} kg × KES ${formatPrice(price)} = KES ${formatPrice(value)}\n")
                }
                append("\n📈 Jumla: KES ${formatPrice(totalEarnings)}")
                append("\n⛽ Mafuta: KES ${formatPrice(totalFuelCost)} (${formatQty(totalFuelLitres)} lita)")
                append("\n💵 Mapato halisi: KES ${formatPrice(netEarnings)}")
            }
        } else {
            buildString {
                append("Earnings ($period):\n")
                catches.forEach { (species, weight) ->
                    val price = TYPICAL_PRICES[species] ?: 300.0
                    append("• $species: ${formatQty(weight)}kg × KES ${formatPrice(price)} = KES ${formatPrice(weight * price)}\n")
                }
                append("\nTotal: KES ${formatPrice(totalEarnings)} | Fuel: KES ${formatPrice(totalFuelCost)} | Net: KES ${formatPrice(netEarnings)}")
            }
        }

        return ToolResult.success(name, mapOf(
            "total_earnings" to totalEarnings, "fuel_cost" to totalFuelCost,
            "net_earnings" to netEarnings, "catches" to catches
        ), message)
    }

    // ──────────────────────────────────────────────
    // ACTION: species_list — List all caught species
    // ──────────────────────────────────────────────

    private fun speciesList(): ToolResult {
        val db = getDb()
        val species = mutableSetOf<String>()
        val cursor = db.query(true, TABLE_CATCHES, arrayOf("species"), null, null, null, null, "species ASC", null)
        cursor.use { while (it.moveToNext()) species.add(it.getString(0)) }

        if (species.isEmpty()) {
            return ToolResult.success(name, mapOf("species" to emptyList<Any>()), "Hakuna samaki walio rekodwa.")
        }

        return ToolResult.success(name, mapOf("species" to species.toList()), "Aina za samaki ${species.size}:\n${species.joinToString("\n") { "• $it" }}")
    }

    // ──────────────────────────────────────────────
    // ACTION: fuel_analysis — Fuel cost analysis
    // ──────────────────────────────────────────────

    private fun fuelAnalysis(params: Map<String, String>): ToolResult {
        val period = params["period"] ?: "month"
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val db = getDb()
        val now = System.currentTimeMillis()
        val cutoff = when (period.lowercase()) {
            "week" -> now - 7 * 24 * 60 * 60 * 1000L
            else -> now - 30 * 24 * 60 * 60 * 1000L
        }

        val cursor = db.rawQuery("""
            SELECT SUM(fuel_cost), SUM(fuel_litres), SUM(total_catch_kg), COUNT(*)
            FROM $TABLE_TRIPS WHERE recorded_at >= ?
        """.trimIndent(), arrayOf(cutoff.toString()))

        cursor.use {
            if (it.moveToFirst()) {
                val totalFuel = it.getDouble(0)
                val totalLitres = it.getDouble(1)
                val totalCatch = it.getDouble(2)
                val tripCount = it.getInt(3)
                val costPerKg = if (totalCatch > 0) totalFuel / totalCatch else 0.0
                val costPerTrip = if (tripCount > 0) totalFuel / tripCount else 0.0

                val message = if (voice) {
                    "⛽ Gharama ya mafuta ($period):\n" +
                    "• Jumla: KES ${formatPrice(totalFuel)} (${formatQty(totalLitres)} lita)\n" +
                    "• Kwa safari: KES ${formatPrice(costPerTrip)}\n" +
                    "• Kwa kilo: KES ${formatPrice(costPerKg)}\n" +
                    "• Safari: $tripCount"
                } else "Fuel analysis ($period): KES ${formatPrice(totalFuel)} total | KES ${formatPrice(costPerTrip)}/trip | KES ${formatPrice(costPerKg)}kg"

                return ToolResult.success(name, mapOf(
                    "total_fuel_cost" to totalFuel, "total_litres" to totalLitres,
                    "cost_per_trip" to costPerTrip, "cost_per_kg" to costPerKg, "trips" to tripCount
                ), message)
            }
        }

        return ToolResult.success(name, emptyMap<String, Any>(), "Hakuna data ya mafuta kwa $period.")
    }

    // ──────────────────────────────────────────────
    // ACTION: voice_parse — Parse Swahili voice input
    // ──────────────────────────────────────────────

    private fun voiceParse(params: Map<String, String>): ToolResult {
        val text = params["notes"] ?: params["species"]
            ?: return ToolResult.error(name, "Voice text required", "MISSING_TEXT")

        val parsed = parseSwahiliFishingVoice(text)
        return ToolResult.success(name, parsed, "Parsed: ${parsed}")
    }

    // ──────────────────────────────────────────────
    // Swahili Voice Parsing for Fishing
    // ──────────────────────────────────────────────

    fun parseSwahiliFishingVoice(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val lower = text.lowercase().trim()

        // Detect action
        when {
            lower.contains(Regex("nimevua|nimetega|nimetega|catch")) -> result["action"] = "record_catch"
            lower.contains(Regex("safari|trip|nimeenda|nimetoka")) -> result["action"] = "record_trip"
            lower.contains(Regex("bei|price")) -> result["action"] = "compare_prices"
            lower.contains(Regex("siku bora|best day|lini")) -> result["action"] = "best_days"
            lower.contains(Regex("mapato|earnings|pesa")) -> result["action"] = "earnings"
        }

        // Extract species
        for ((alias, canonical) in SPECIES_ALIASES.entries.sortedByDescending { it.key.length }) {
            if (lower.contains(alias)) {
                result["species"] = canonical
                break
            }
        }

        // Extract weight: "kilo 20", "kg 20", "20 kg"
        val weightPatterns = listOf(
            Regex("""(\d+\.?\d*)\s*(?:kilo|kg|kgs)"""),
            Regex("""(?:kilo|kg)\s*(\d+\.?\d*)""")
        )
        for (pattern in weightPatterns) {
            pattern.find(lower)?.let { result["weight"] = it.groupValues[1] }
        }

        // Extract quantity: "samaki 5", "5 samaki"
        val qtyPatterns = listOf(
            Regex("""samaki\s*(\d+)"""),
            Regex("""(\d+)\s*samaki""")
        )
        for (pattern in qtyPatterns) {
            pattern.find(lower)?.let { result["quantity"] = it.groupValues[1] }
        }

        // Extract duration: "saa 4", "masaa 4"
        val durationPatterns = listOf(
            Regex("""(?:saa|masaa)\s*(\d+\.?\d*)"""),
            Regex("""(\d+\.?\d*)\s*(?:saa|masaa|hours?)""")
        )
        for (pattern in durationPatterns) {
            pattern.find(lower)?.let { result["duration_hours"] = it.groupValues[1] }
        }

        // Extract fuel: "petroli lita 20", "mafuta lita 20"
        val fuelPatterns = listOf(
            Regex("""(?:petroli|mafuta|fuel)\s*(?:lita)?\s*(\d+\.?\d*)"""),
            Regex("""lita\s*(\d+\.?\d*)""")
        )
        for (pattern in fuelPatterns) {
            pattern.find(lower)?.let { result["fuel_litres"] = it.groupValues[1] }
        }

        // Extract location hints
        val locationPatterns = listOf(
            Regex("""bandari\s+(?:ya\s+)?(\w+)"""),
            Regex("""kule\s+(\w+)"""),
            Regex("""hapa\s+(\w+)""")
        )
        for (pattern in locationPatterns) {
            pattern.find(lower)?.let { result["location"] = it.groupValues[1] }
        }

        // Extract method
        when {
            lower.contains(Regex("nyavu|net")) -> result["method"] = "net"
            lower.contains(Regex("line|kamba|uteo")) -> result["method"] = "line"
            lower.contains(Regex("trap|kitega|tego")) -> result["method"] = "trap"
            lower.contains(Regex("cage|ufugaji")) -> result["method"] = "cage"
        }

        return result
    }

    // ──────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────

    private fun normalizeSpecies(raw: String): String {
        return SPECIES_ALIASES[raw.trim().lowercase()] ?: raw.trim().replaceFirstChar { it.uppercase() }
    }

    private fun formatQty(qty: Double): String = if (qty == qty.toLong().toDouble()) "%,.0f".format(qty) else "%,.1f".format(qty)
    private fun formatPrice(price: Double): String = if (price == price.toLong().toDouble()) "%,.0f".format(price) else "%,.1f".format(price)
    private fun formatDate(ts: Long): String = java.text.SimpleDateFormat("dd MMM", java.util.Locale.getDefault()).format(java.util.Date(ts))

    private data class CatchRecord(val id: Long, val species: String, val weight: Double?, val quantity: Int?, val location: String?, val method: String?, val tripId: Long?, val recordedAt: Long)
    private data class TripRecord(val id: Long, val location: String, val durationHours: Double?, val fuelLitres: Double?, val fuelCost: Double?, val weather: String?, val totalCatchKg: Double, val totalEarnings: Double, val recordedAt: Long)
    private data class SpeciesSummary(val species: String, val totalWeight: Double, val totalQuantity: Int, val catchCount: Int)
}

// Cursor extensions for nullable types
private fun android.database.Cursor.getDoubleOrNull(columnIndex: Int): Double? = if (isNull(columnIndex)) null else getDouble(columnIndex)
private fun android.database.Cursor.getIntOrNull(columnIndex: Int): Int? = if (isNull(columnIndex)) null else getInt(columnIndex)
private fun android.database.Cursor.getLongOrNull(columnIndex: Int): Long? = if (isNull(columnIndex)) null else getLong(columnIndex)
