package com.msaidizi.agent.tools.market

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import javax.inject.Inject
import javax.inject.Singleton
import com.msaidizi.agent.tools.core.*

/**
 * CompetitorTracker — Competitive intelligence for informal market vendors.
 *
 * Problem: Mama mbogas and market vendors on the same street lose KES 100–250/day
 * from price wars and wrong product mix. This tool crowdsources competitor prices,
 * tracks vendor density, suggests differentiation opportunities, and identifies
 * pricing gaps — all via Swahili voice, fully offline-capable.
 *
 * 5 actions: nearby, saturation, differentiate, pricing_gap, summary
 *
 * Annual savings potential: KES 30,000–75,000 per worker.
 */
@Singleton
class CompetitorTracker @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val name = "competitor_tracker"
    override val description = "Track nearby competitor prices, saturation levels, and find differentiation opportunities. Solves price erosion and wrong product mix."

    override val argsSchema = argSchema {
        enum(
            "action", "Action to perform",
            listOf(
                "nearby",         // "Wauzaji wengine wananunua bei gani?"
                "saturation",     // "Kuna wauzaji wangapi hapa?"
                "differentiate",  // "Niuze nini tofauti?"
                "pricing_gap",    // "Bei yangu ikoje?" — compare worker vs competitor avg
                "summary"         // Full competitive intelligence briefing
            ),
            required = true
        )
        string("product", "Product name to check (e.g. 'nyanya', 'sukuma wiki')", required = false)
        string("area", "Area/location name (e.g. 'Kibra', 'Kawangware')", required = false)
        string("worker_id", "Worker ID for personalized analysis", required = false)
        number("worker_price", "Worker's current selling price in KES for gap analysis", required = false)
        number("latitude", "Worker latitude for proximity search", required = false)
        number("longitude", "Worker longitude for proximity search", required = false)
        number("radius_km", "Search radius in kilometers (default 1.0)", required = false)
        boolean("voice", "Format response for Swahili voice output (default true)", required = false)
    }

    // ──────────────────────────────────────────────
    // SQLite Database — Offline competitor intelligence
    // ──────────────────────────────────────────────

    inner class CompetitorDatabase(context: Context) :
        SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            // Crowdsourced competitor price reports
            db.execSQL("""
                CREATE TABLE $TABLE_REPORTS (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    reporter_id TEXT NOT NULL,
                    competitor_description TEXT,
                    product_name TEXT NOT NULL,
                    price REAL NOT NULL,
                    unit TEXT NOT NULL DEFAULT 'kg',
                    location_name TEXT,
                    latitude REAL,
                    longitude REAL,
                    report_date TEXT NOT NULL,
                    report_time TEXT,
                    quality_note TEXT,
                    created_at INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000)
                )
            """)
            db.execSQL("CREATE INDEX idx_reports_product ON $TABLE_REPORTS(product_name, report_date)")
            db.execSQL("CREATE INDEX idx_reports_location ON $TABLE_REPORTS(location_name, report_date)")
            db.execSQL("CREATE INDEX idx_reports_coords ON $TABLE_REPORTS(latitude, longitude, report_date)")

            // Aggregated competitor intelligence (computed from reports)
            db.execSQL("""
                CREATE TABLE $TABLE_INTELLIGENCE (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    area_name TEXT NOT NULL,
                    product_name TEXT NOT NULL,
                    avg_price REAL NOT NULL,
                    min_price REAL NOT NULL,
                    max_price REAL NOT NULL,
                    vendor_count INTEGER NOT NULL DEFAULT 0,
                    demand_level TEXT NOT NULL DEFAULT 'medium',
                    saturation_level TEXT NOT NULL DEFAULT 'balanced',
                    date_computed TEXT NOT NULL,
                    created_at INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000),
                    UNIQUE(area_name, product_name, date_computed)
                )
            """)
            db.execSQL("CREATE INDEX idx_intel_area ON $TABLE_INTELLIGENCE(area_name, date_computed)")

            // Product opportunities (gaps in local supply)
            db.execSQL("""
                CREATE TABLE $TABLE_OPPORTUNITIES (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    area_name TEXT NOT NULL,
                    product_name TEXT NOT NULL,
                    demand_signal TEXT,
                    current_supply_level TEXT NOT NULL DEFAULT 'none',
                    opportunity_score REAL NOT NULL DEFAULT 0.0,
                    suggested_price REAL,
                    estimated_daily_volume REAL,
                    date_identified TEXT NOT NULL,
                    created_at INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000)
                )
            """)
            db.execSQL("CREATE INDEX idx_opps_area ON $TABLE_OPPORTUNITIES(area_name, opportunity_score DESC)")

            // Vendor locations for saturation mapping
            db.execSQL("""
                CREATE TABLE $TABLE_VENDORS (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    worker_id TEXT NOT NULL,
                    location_name TEXT,
                    latitude REAL,
                    longitude REAL,
                    products_sold TEXT,
                    typical_arrival_time TEXT,
                    typical_departure_time TEXT,
                    date_recorded TEXT NOT NULL,
                    created_at INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000)
                )
            """)
            db.execSQL("CREATE INDEX idx_vendors_coords ON $TABLE_VENDORS(latitude, longitude, date_recorded)")
            db.execSQL("CREATE INDEX idx_vendors_worker ON $TABLE_VENDORS(worker_id, date_recorded)")

            // Seed opportunity data for common gaps
            seedOpportunities(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_REPORTS")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_INTELLIGENCE")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_OPPORTUNITIES")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_VENDORS")
            onCreate(db)
        }
    }

    // ──────────────────────────────────────────────
    // Constants & State
    // ──────────────────────────────────────────────

    companion object {
        private const val DB_NAME = "competitor_tracker.db"
        private const val DB_VERSION = 1
        private const val TABLE_REPORTS = "competitor_reports"
        private const val TABLE_INTELLIGENCE = "competitor_intelligence"
        private const val TABLE_OPPORTUNITIES = "product_opportunities"
        private const val TABLE_VENDORS = "vendor_locations"

        // Saturation thresholds (vendor count per area per product)
        private const val SATURATION_LOW = 2
        private const val SATURATION_HIGH = 5
        private const val OVERSUPPLY_PRICE_DROP_PCT = 10.0

        private const val DEFAULT_RADIUS_KM = 1.0
        private const val STALE_DAYS = 3 // reports older than this are "stale"
    }

    private var dbHelper: CompetitorDatabase? = null

    private fun getDb(): SQLiteDatabase {
        if (dbHelper == null) {
            dbHelper = CompetitorDatabase(context)
        }
        return dbHelper!!.writableDatabase
    }

    // ──────────────────────────────────────────────
    // Product normalization (Swahili ↔ English)
    // ──────────────────────────────────────────────

    private val productAliases = mapOf(
        "nyanya" to "tomatoes", "tomato" to "tomatoes",
        "sukuma wiki" to "sukuma wiki", "kale" to "sukuma wiki",
        "vitunguu" to "onions", "onion" to "onions",
        "viazi" to "potatoes", "potato" to "potatoes",
        "maharagwe" to "beans", "bean" to "beans",
        "ndizi" to "bananas", "banana" to "bananas",
        "embe" to "mangoes", "mango" to "mangoes",
        "parachichi" to "avocado",
        "samaki" to "fish",
        "nyama" to "meat",
        "maziwa" to "milk",
        "mayai" to "eggs", "egg" to "eggs",
        "kuku" to "chicken",
        "mahindi" to "maize", "corn" to "maize",
        "njugu" to "groundnuts",
        "pilau masala" to "pilau masala",
        "kabichi" to "cabbage"
    )

    private fun normalizeProduct(raw: String): String {
        val lower = raw.trim().lowercase()
        return productAliases[lower] ?: lower
    }

    private val productUnits = mapOf(
        "tomatoes" to "kg", "sukuma wiki" to "bunch", "onions" to "kg",
        "potatoes" to "kg", "beans" to "kg", "bananas" to "bunch",
        "mangoes" to "kg", "avocado" to "piece", "fish" to "kg",
        "meat" to "kg", "milk" to "litre", "eggs" to "tray",
        "chicken" to "piece", "maize" to "kg", "groundnuts" to "kg",
        "pilau masala" to "packet", "cabbage" to "head"
    )

    private fun getUnit(product: String): String = productUnits[product] ?: "kg"

    // Common Swahili area names (for location normalization)
    private val areaAliases = mapOf(
        "kibra" to "Kibera", "kibera" to "Kibera",
        "kawangware" to "Kawangware", "kawa" to "Kawangware",
        "korogocho" to "Korogocho", "korogo" to "Korogocho",
        "mathare" to "Mathare", "mathari" to "Mathare",
        "kayole" to "Kayole", "kayol" to "Kayole",
        "dandora" to "Dandora", "dando" to "Dandora",
        "mukuru" to "Mukuru", "mukuru kwa njenga" to "Mukuru Kwa Njenga",
        "gikomba" to "Gikomba", "gikomaba" to "Gikomba",
        "wakulima" to "Wakulima", "marikiti" to "Marikiti",
        "kangemi" to "Kangemi", "buru buru" to "Buru Buru",
        "eastleigh" to "Eastleigh", "roysambu" to "Roysambu",
        "pipeline" to "Pipeline", "utawala" to "Utawala"
    )

    private fun normalizeArea(raw: String?): String? {
        if (raw == null) return null
        val lower = raw.trim().lowercase()
        return areaAliases[lower] ?: raw.trim().replaceFirstChar { it.uppercase() }
    }

    // ──────────────────────────────────────────────
    // Seed data — common product opportunities
    // ──────────────────────────────────────────────

    private fun seedOpportunities(db: SQLiteDatabase) {
        val now = System.currentTimeMillis()
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(now))
        val seeds = listOf(
            Triple("Avocado", "none", 0.9),
            Triple("Passion Fruit", "none", 0.8),
            Triple("Pilau Masala", "none", 0.85),
            Triple("Moringa Leaves", "none", 0.7),
            Triple("Honey", "none", 0.75),
            Triple("Dried Fish", "none", 0.65),
            Triple("Groundnuts (roasted)", "low", 0.7),
            Triple("Sweet Potatoes", "low", 0.75),
            Triple("Tamarind", "none", 0.6),
            Triple("Coconut", "low", 0.7)
        )
        seeds.forEach { (product, supply, score) ->
            val values = ContentValues().apply {
                put("area_name", "general")
                put("product_name", product)
                put("demand_signal", "market_trend")
                put("current_supply_level", supply)
                put("opportunity_score", score)
                put("suggested_price", 0.0) // filled by PricingAdvisor
                put("date_identified", today)
                put("created_at", now)
            }
            db.insertWithOnConflict(TABLE_OPPORTUNITIES, null, values, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    // ──────────────────────────────────────────────
    // Tool Execute — Main Entry Point
    // ──────────────────────────────────────────────

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"]
            ?: return ToolResult.error(name, "Action required: nearby, saturation, differentiate, pricing_gap, summary", "MISSING_ACTION")

        return when (action.lowercase()) {
            "nearby" -> nearbyCompetitors(params)
            "saturation" -> checkSaturation(params)
            "differentiate" -> findDifferentiation(params)
            "pricing_gap" -> pricingGap(params)
            "summary" -> fullSummary(params)
            else -> ToolResult.error(name, "Unknown action: $action. Valid: nearby, saturation, differentiate, pricing_gap, summary", "INVALID_ACTION")
        }
    }

    // ──────────────────────────────────────────────
    // ACTION: nearby — Competitor prices in the area
    // Voice: "Wauzaji wengine wananunua bei gani?"
    // ──────────────────────────────────────────────

    private fun nearbyCompetitors(params: Map<String, String>): ToolResult {
        val rawProduct = params["product"]
        val product = rawProduct?.let { normalizeProduct(it) }
        val area = normalizeArea(params["area"])
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val radiusKm = params["radius_km"]?.toDoubleOrNull() ?: DEFAULT_RADIUS_KM
        val lat = params["latitude"]?.toDoubleOrNull()
        val lng = params["longitude"]?.toDoubleOrNull()

        val db = getDb()
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

        // Query recent competitor reports
        val reports = queryRecentReports(db, product, area, lat, lng, radiusKm, today)

        if (reports.isEmpty()) {
            return if (voice) {
                ToolResult.success(
                    name,
                    mapOf("product" to product, "area" to area, "reports" to emptyList<Any>()),
                    "Hakuna ripoti za wauzaji wengine ${area ?: "hapa"} kwa sasa." +
                            "\nTafadhali ripoti bei za wauzaji wengine — nitakusaidia kufuatilia ushindani."
                )
            } else {
                ToolResult.success(
                    name,
                    mapOf("product" to product, "area" to area, "reports" to emptyList<Any>()),
                    "No competitor reports found for ${product ?: "any product"} in ${area ?: "this area"}."
                )
            }
        }

        // Group by product and compute stats
        val byProduct = reports.groupBy { it.productName }
            .map { (prod, entries) ->
                ProductCompetitorSummary(
                    productName = prod,
                    unit = entries.first().unit,
                    avgPrice = entries.map { it.price }.average(),
                    minPrice = entries.minOf { it.price },
                    maxPrice = entries.maxOf { it.price },
                    vendorCount = entries.map { it.competitorDescription }.distinct().size.coerceAtLeast(entries.size),
                    recentReports = entries.size
                )
            }
            .sortedByDescending { it.vendorCount }

        val message = if (voice) {
            buildString {
                append("${area ?: "Hapa"} leo:\n")
                byProduct.take(8).forEach { ps ->
                    val warning = if (ps.vendorCount >= SATURATION_HIGH) " ⚠️" else ""
                    append("• ${ps.productName}: wauzaji ${ps.vendorCount}, ")
                    append("bei ${formatPrice(ps.minPrice)}-${formatPrice(ps.maxPrice)} ")
                    append("(wastani ${formatPrice(ps.avgPrice)})$warning\n")
                }
                if (byProduct.size > 8) {
                    append("... na bidhaa ${byProduct.size - 8} zaidi.\n")
                }
                // Saturation warning
                val oversupplied = byProduct.filter { it.vendorCount >= SATURATION_HIGH }
                if (oversupplied.isNotEmpty()) {
                    append("\n⚠️ Wauzaji wengi: ${oversupplied.joinToString(", ") { it.productName }}")
                    append("\nFikiria kubadilisha bidhaa — tumia 'differentiate' kupata mapendekezo.")
                }
            }
        } else {
            buildString {
                append("Competitor intelligence for ${area ?: "area"}:\n")
                byProduct.take(8).forEach { ps ->
                    append("• ${ps.productName}: ${ps.vendorCount} vendors, ")
                    append("KES ${formatPrice(ps.minPrice)}-${formatPrice(ps.maxPrice)}/${ps.unit} ")
                    append("(avg KES ${formatPrice(ps.avgPrice)}), ${ps.recentReports} reports\n")
                }
            }
        }

        return ToolResult.success(
            name,
            mapOf(
                "area" to area,
                "products" to byProduct.map {
                    mapOf(
                        "product" to it.productName, "vendor_count" to it.vendorCount,
                        "avg_price" to it.avgPrice, "min_price" to it.minPrice,
                        "max_price" to it.maxPrice, "unit" to it.unit
                    )
                },
                "total_reports" to reports.size
            ),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: saturation — Vendor density check
    // Voice: "Kuna wauzaji wangapi hapa?"
    // ──────────────────────────────────────────────

    private fun checkSaturation(params: Map<String, String>): ToolResult {
        val rawProduct = params["product"]
        val product = rawProduct?.let { normalizeProduct(it) }
        val area = normalizeArea(params["area"])
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val db = getDb()
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

        // Use intelligence table for pre-computed saturation, or compute from reports
        val intel = queryIntelligence(db, product, area, today)

        if (intel.isEmpty()) {
            // Fall back to raw reports
            val reports = queryRecentReports(db, product, area, null, null, DEFAULT_RADIUS_KM, today)
            if (reports.isEmpty()) {
                return ToolResult.success(
                    name,
                    mapOf("area" to area, "product" to product, "saturation" to "unknown"),
                    if (voice) "Hakuna data ya ushindani ${area ?: "hapa"} kwa sasa. Ripoti za wauzaji zinahitajika."
                    else "No saturation data for ${area ?: "this area"}. Competitor reports needed."
                )
            }
            // Compute from raw reports
            val byProduct = reports.groupBy { it.productName }
            val computed = byProduct.map { (prod, entries) ->
                SaturationInfo(
                    productName = prod,
                    vendorCount = entries.map { it.competitorDescription }.distinct().size.coerceAtLeast(entries.size),
                    avgPrice = entries.map { it.price }.average(),
                    saturationLevel = classifySaturation(entries.map { it.competitorDescription }.distinct().size.coerceAtLeast(entries.size))
                )
            }.sortedByDescending { it.vendorCount }

            return buildSaturationResult(computed, area, voice)
        }

        val saturationData = intel.map { info ->
            SaturationInfo(
                productName = info.productName,
                vendorCount = info.vendorCount,
                avgPrice = info.avgPrice,
                saturationLevel = info.saturationLevel
            )
        }.sortedByDescending { it.vendorCount }

        return buildSaturationResult(saturationData, area, voice)
    }

    private fun buildSaturationResult(data: List<SaturationInfo>, area: String?, voice: Boolean): ToolResult {
        val oversupplied = data.filter { it.saturationLevel == "oversupplied" }
        val balanced = data.filter { it.saturationLevel == "balanced" }
        val undersupplied = data.filter { it.saturationLevel == "undersupplied" }

        val message = if (voice) {
            buildString {
                append("Ushindani ${area ?: "hapa"}:\n")
                if (oversupplied.isNotEmpty()) {
                    append("\n🔴 Wauzaji wengi sana:\n")
                    oversupplied.forEach { s ->
                        append("• ${s.productName}: wauzaji ${s.vendorCount} — bei itashuka. Badilisha bidhaa!\n")
                    }
                }
                if (balanced.isNotEmpty()) {
                    append("\n🟡 Usawa:\n")
                    balanced.forEach { s ->
                        append("• ${s.productName}: wauzaji ${s.vendorCount}\n")
                    }
                }
                if (undersupplied.isNotEmpty()) {
                    append("\n🟢 Fursa — wauzaji wachache:\n")
                    undersupplied.forEach { s ->
                        append("• ${s.productName}: wauzaji ${s.vendorCount} — unaweza kuuza hapa!\n")
                    }
                }
                if (oversupplied.size >= 2) {
                    append("\n💡 Pendekezo: Badilisha bidhaa au nenda mahali pengine.")
                }
            }
        } else {
            buildString {
                append("Saturation analysis for ${area ?: "area"}:\n")
                data.forEach { s ->
                    val icon = when (s.saturationLevel) {
                        "oversupplied" -> "🔴"
                        "balanced" -> "🟡"
                        else -> "🟢"
                    }
                    append("$icon ${s.productName}: ${s.vendorCount} vendors (${s.saturationLevel}), avg KES ${formatPrice(s.avgPrice)}\n")
                }
            }
        }

        return ToolResult.success(
            name,
            mapOf(
                "area" to area,
                "oversupplied" to oversupplied.map { mapOf("product" to it.productName, "vendors" to it.vendorCount) },
                "balanced" to balanced.map { mapOf("product" to it.productName, "vendors" to it.vendorCount) },
                "undersupplied" to undersupplied.map { mapOf("product" to it.productName, "vendors" to it.vendorCount) }
            ),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: differentiate — Product suggestions
    // Voice: "Niuze nini tofauti?"
    // ──────────────────────────────────────────────

    private fun findDifferentiation(params: Map<String, String>): ToolResult {
        val area = normalizeArea(params["area"])
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val db = getDb()
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

        // Get current oversupplied products in the area
        val reports = queryRecentReports(db, product = null, area = area, lat = null, lng = null, radiusKm = DEFAULT_RADIUS_KM, today = today)
        val currentProducts = reports.groupBy { it.productName }
            .filter { it.value.size >= SATURATION_HIGH }
            .keys.toSet()

        // Get opportunity products not currently oversupplied
        val opportunities = queryOpportunities(db, area)

        // Filter out products that are already oversupplied
        val suggestions = opportunities
            .filter { it.productName.lowercase() !in currentProducts.map { p -> p.lowercase() } }
            .sortedByDescending { it.opportunityScore }
            .take(5)

        if (suggestions.isEmpty()) {
            return ToolResult.success(
                name,
                mapOf("area" to area, "suggestions" to emptyList<Any>()),
                if (voice) "Sikupata mapendekezo ya bidhaa mpya kwa sasa. Jaribu tena baadaye."
                else "No differentiation suggestions available. Try again later."
            )
        }

        val message = if (voice) {
            buildString {
                append("Mapendekezo ya bidhaa mpya ${area ?: "hapa"}:\n")
                suggestions.forEachIndexed { idx, opp ->
                    val scoreLabel = when {
                        opp.opportunityScore >= 0.85 -> "⭐⭐⭐"
                        opp.opportunityScore >= 0.7 -> "⭐⭐"
                        else -> "⭐"
                    }
                    append("\n${idx + 1}. ${opp.productName} $scoreLabel")
                    append("\n   Fursa: ${formatOpportunityReason(opp)}")
                    if (opp.suggestedPrice > 0) {
                        append("\n   Bei inayopendekezwa: KES ${formatPrice(opp.suggestedPrice)}")
                    }
                    append("\n")
                }
                if (currentProducts.isNotEmpty()) {
                    append("\n⚠️ Epuka: ${currentProducts.joinToString(", ")} — wauzaji wengi sana.")
                }
            }
        } else {
            buildString {
                append("Differentiation opportunities for ${area ?: "area"}:\n")
                suggestions.forEach { opp ->
                    append("• ${opp.productName}: score ${String.format("%.0f", opp.opportunityScore * 100)}%")
                    append(" | supply: ${opp.currentSupplyLevel}")
                    if (opp.suggestedPrice > 0) append(" | suggested: KES ${formatPrice(opp.suggestedPrice)}")
                    append("\n")
                }
            }
        }

        return ToolResult.success(
            name,
            mapOf(
                "area" to area,
                "avoid_products" to currentProducts.toList(),
                "suggestions" to suggestions.map {
                    mapOf(
                        "product" to it.productName,
                        "score" to it.opportunityScore,
                        "supply_level" to it.currentSupplyLevel,
                        "suggested_price" to it.suggestedPrice
                    )
                }
            ),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: pricing_gap — Worker price vs competitor avg
    // Voice: "Bei yangu ikoje?"
    // ──────────────────────────────────────────────

    private fun pricingGap(params: Map<String, String>): ToolResult {
        val rawProduct = params["product"]
            ?: return ToolResult.error(name, "Product name required for pricing gap analysis", "MISSING_PRODUCT")
        val product = normalizeProduct(rawProduct)
        val workerPrice = params["worker_price"]?.toDoubleOrNull()
            ?: return ToolResult.error(name, "Worker price required. Example: worker_price=100", "MISSING_WORKER_PRICE")
        val area = normalizeArea(params["area"])
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val db = getDb()
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

        val reports = queryRecentReports(db, product, area, null, null, DEFAULT_RADIUS_KM, today)

        if (reports.isEmpty()) {
            return ToolResult.success(
                name,
                mapOf("product" to product, "worker_price" to workerPrice, "found" to false),
                if (voice) "Sikujua bei za wauzaji wengine wa $product. Ripoti zinahitajika."
                else "No competitor data for $product. Reports needed."
            )
        }

        val competitorAvg = reports.map { it.price }.average()
        val competitorMin = reports.minOf { it.price }
        val competitorMax = reports.maxOf { it.price }
        val diff = workerPrice - competitorAvg
        val diffPct = if (competitorAvg > 0) (diff / competitorAvg * 100) else 0.0
        val unit = getUnit(product)

        val message = if (voice) {
            buildString {
                append("Bei yako ya $product: KES ${formatPrice(workerPrice)} kwa $unit\n")
                append("Wastani wa wauzaji: KES ${formatPrice(competitorAvg)} kwa $unit\n")
                append("Wauzaji: KES ${formatPrice(competitorMin)}-${formatPrice(competitorMax)}\n\n")
                when {
                    diffPct > 15 -> {
                        append("🔴 Bei yako ni juu kwa ${diffPct.toInt()}%!")
                        append("\nWateja wataenda kwa wauzaji wengine.")
                        append("\nPendekezo: Punguza hadi KES ${formatPrice(competitorAvg + 5)}.")
                        append("\nUtaongeza mauzo bila kupoteza faida nyingi.")
                    }
                    diffPct < -15 -> {
                        append("🟢 Bei yako ni chini kwa ${Math.abs(diffPct).toInt()}%!")
                        append("\nUnaweza ongeza bei hadi KES ${formatPrice(competitorAvg - 5)}.")
                        append("\nWateja bado watanunua — bei yako ni nzuri zaidi.")
                        append("\nFaida ya ziada: KES ${formatPrice(Math.abs(diff) * 20)} kwa kilo 20.")
                    }
                    else -> {
                        append("✅ Bei yako ni sawa na wauzaji wengine.")
                        append("\nTofauti ni ${diffPct.toInt()}% — bei yako ni ushindani.")
                    }
                }
            }
        } else {
            buildString {
                append("Pricing gap for $product:\n")
                append("• Your price: KES ${formatPrice(workerPrice)}/$unit\n")
                append("• Competitor avg: KES ${formatPrice(competitorAvg)}/$unit\n")
                append("• Competitor range: KES ${formatPrice(competitorMin)}-${formatPrice(competitorMax)}/$unit\n")
                append("• Gap: ${diffPct.toInt()}% (${
                    when {
                        diffPct > 15 -> "OVERPRICED — lower to match"
                        diffPct < -15 -> "UNDERPRICED — raise price"
                        else -> "COMPETITIVE"
                    }
                })")
            }
        }

        return ToolResult.success(
            name,
            mapOf(
                "product" to product,
                "worker_price" to workerPrice,
                "competitor_avg" to competitorAvg,
                "competitor_min" to competitorMin,
                "competitor_max" to competitorMax,
                "diff_pct" to diffPct,
                "recommendation" to when {
                    diffPct > 15 -> "lower"
                    diffPct < -15 -> "raise"
                    else -> "competitive"
                }
            ),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: summary — Full competitive briefing
    // Voice: Full intelligence report
    // ──────────────────────────────────────────────

    private fun fullSummary(params: Map<String, String>): ToolResult {
        val area = normalizeArea(params["area"])
        val workerId = params["worker_id"]
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val db = getDb()
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

        // Get all recent reports
        val reports = queryRecentReports(db, null, area, null, null, DEFAULT_RADIUS_KM, today)

        // Get saturation data
        val saturation = if (reports.isNotEmpty()) {
            reports.groupBy { it.productName }.map { (prod, entries) ->
                val vendorCount = entries.map { it.competitorDescription }.distinct().size.coerceAtLeast(entries.size)
                SaturationInfo(
                    productName = prod,
                    vendorCount = vendorCount,
                    avgPrice = entries.map { it.price }.average(),
                    saturationLevel = classifySaturation(vendorCount)
                )
            }.sortedByDescending { it.vendorCount }
        } else emptyList()

        // Get differentiation opportunities
        val opportunities = queryOpportunities(db, area)
            .sortedByDescending { it.opportunityScore }
            .take(3)

        // Count total active vendors
        val uniqueVendors = reports.mapNotNull { it.competitorDescription }.distinct().size
        val uniqueProducts = reports.map { it.productName }.distinct().size

        val message = if (voice) {
            buildString {
                append("📋 Ripoti ya ushindani ${area ?: "hapa"}:\n\n")

                // Overview
                append("📊 Muhtasari: Ripoti ${reports.size}, bidhaa $uniqueProducts, wauzaji ~$uniqueVendors\n\n")

                // Saturation alerts
                val oversupplied = saturation.filter { it.saturationLevel == "oversupplied" }
                if (oversupplied.isNotEmpty()) {
                    append("🔴 Onyo — wauzaji wengi:\n")
                    oversupplied.forEach { s ->
                        append("• ${s.productName}: wauzaji ${s.vendorCount} — bei inashuka!\n")
                    }
                    append("\n")
                }

                // Balanced products
                val balanced = saturation.filter { it.saturationLevel == "balanced" }
                if (balanced.isNotEmpty()) {
                    append("🟡 Bidhaa za kawaida:\n")
                    balanced.forEach { s ->
                        append("• ${s.productName}: wauzaji ${s.vendorCount}, bei KES ${formatPrice(s.avgPrice)}\n")
                    }
                    append("\n")
                }

                // Opportunities
                val undersupplied = saturation.filter { it.saturationLevel == "undersupplied" }
                if (undersupplied.isNotEmpty()) {
                    append("🟢 Fursa — wauzaji wachache:\n")
                    undersupplied.forEach { s ->
                        append("• ${s.productName}: wauzaji ${s.vendorCount} — jaribu kuuza hapa!\n")
                    }
                    append("\n")
                }

                // Differentiation
                if (opportunities.isNotEmpty()) {
                    append("💡 Mapendekezo ya bidhaa mpya:\n")
                    opportunities.forEachIndexed { idx, opp ->
                        append("${idx + 1}. ${opp.productName} — ${formatOpportunityReason(opp)}\n")
                    }
                    append("\n")
                }

                // Actionable advice
                append("📌 Hatua za leo:\n")
                if (oversupplied.isNotEmpty()) {
                    append("1. Epuka kuuza: ${oversupplied.joinToString(", ") { it.productName }}\n")
                }
                if (opportunities.isNotEmpty()) {
                    append("2. Jaribu: ${opportunities.first().productName} — bei nzuri, wauzaji wachache\n")
                }
                if (oversupplied.isEmpty() && opportunities.isEmpty()) {
                    append("Endelea na bidhaa zako — ushindani ni wa kawaida.\n")
                }
            }
        } else {
            buildString {
                append("Competitor Intelligence Summary — ${area ?: "area"}\n")
                append("=" .repeat(50) + "\n")
                append("Reports: ${reports.size} | Products: $uniqueProducts | Vendors: ~$uniqueVendors\n\n")
                saturation.forEach { s ->
                    val icon = when (s.saturationLevel) {
                        "oversupplied" -> "🔴"
                        "balanced" -> "🟡"
                        else -> "🟢"
                    }
                    append("$icon ${s.productName}: ${s.vendorCount} vendors, avg KES ${formatPrice(s.avgPrice)} (${s.saturationLevel})\n")
                }
                if (opportunities.isNotEmpty()) {
                    append("\nTop opportunities:\n")
                    opportunities.forEach { opp ->
                        append("• ${opp.productName}: ${String.format("%.0f", opp.opportunityScore * 100)}% opportunity\n")
                    }
                }
            }
        }

        return ToolResult.success(
            name,
            mapOf(
                "area" to area,
                "total_reports" to reports.size,
                "unique_vendors" to uniqueVendors,
                "unique_products" to uniqueProducts,
                "saturation" to saturation.map {
                    mapOf("product" to it.productName, "vendors" to it.vendorCount,
                        "avg_price" to it.avgPrice, "level" to it.saturationLevel)
                },
                "opportunities" to opportunities.map {
                    mapOf("product" to it.productName, "score" to it.opportunityScore)
                }
            ),
            message
        )
    }

    // ──────────────────────────────────────────────
    // Public API — Data insertion (called by other tools / sync)
    // ──────────────────────────────────────────────

    /**
     * Report a competitor's price. Called from voice input or MarketPriceBroadcaster.
     */
    fun reportCompetitor(
        reporterId: String,
        competitorDescription: String?,
        productName: String,
        price: Double,
        unit: String = "kg",
        locationName: String? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        qualityNote: String? = null,
        reportDate: String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()),
        reportTime: String? = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date())
    ): Long {
        val db = getDb()
        val values = ContentValues().apply {
            put("reporter_id", reporterId)
            put("competitor_description", competitorDescription)
            put("product_name", normalizeProduct(productName))
            put("price", price)
            put("unit", unit)
            put("location_name", locationName)
            put("latitude", latitude)
            put("longitude", longitude)
            put("report_date", reportDate)
            put("report_time", reportTime)
            put("quality_note", qualityNote)
            put("created_at", System.currentTimeMillis())
        }
        return db.insert(TABLE_REPORTS, null, values)
    }

    /**
     * Bulk insert competitor reports from sync.
     */
    fun bulkInsertReports(reports: List<CompetitorReportData>) {
        val db = getDb()
        db.beginTransaction()
        try {
            reports.forEach { r ->
                reportCompetitor(
                    reporterId = r.reporterId,
                    competitorDescription = r.competitorDescription,
                    productName = r.productName,
                    price = r.price,
                    unit = r.unit,
                    locationName = r.locationName,
                    latitude = r.latitude,
                    longitude = r.longitude,
                    qualityNote = r.qualityNote,
                    reportDate = r.reportDate,
                    reportTime = r.reportTime
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Register worker selling location (for saturation mapping).
     */
    fun registerVendorLocation(
        workerId: String,
        locationName: String?,
        latitude: Double,
        longitude: Double,
        productsSold: String,
        arrivalTime: String? = null,
        departureTime: String? = null
    ) {
        val db = getDb()
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val values = ContentValues().apply {
            put("worker_id", workerId)
            put("location_name", locationName)
            put("latitude", latitude)
            put("longitude", longitude)
            put("products_sold", productsSold)
            put("typical_arrival_time", arrivalTime)
            put("typical_departure_time", departureTime)
            put("date_recorded", today)
            put("created_at", System.currentTimeMillis())
        }
        db.insert(TABLE_VENDORS, null, values)
    }

    /**
     * Update aggregated intelligence (called periodically or after new reports).
     */
    fun updateIntelligence(areaName: String, productName: String) {
        val db = getDb()
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val cutoff = today // Only today's reports

        val cursor = db.rawQuery(
            """
            SELECT AVG(price) as avg_p, MIN(price) as min_p, MAX(price) as max_p,
                   COUNT(*) as cnt, COUNT(DISTINCT competitor_description) as vendors
            FROM $TABLE_REPORTS
            WHERE product_name = ? AND location_name = ? AND report_date = ?
            """,
            arrayOf(productName, areaName, cutoff)
        )

        cursor.use {
            if (it.moveToFirst() && it.getInt(it.getColumnIndexOrThrow("cnt")) > 0) {
                val avg = it.getDouble(it.getColumnIndexOrThrow("avg_p"))
                val min = it.getDouble(it.getColumnIndexOrThrow("min_p"))
                val max = it.getDouble(it.getColumnIndexOrThrow("max_p"))
                val vendors = it.getInt(it.getColumnIndexOrThrow("vendors"))

                val values = ContentValues().apply {
                    put("area_name", areaName)
                    put("product_name", productName)
                    put("avg_price", avg)
                    put("min_price", min)
                    put("max_price", max)
                    put("vendor_count", vendors)
                    put("saturation_level", classifySaturation(vendors))
                    put("date_computed", cutoff)
                    put("created_at", System.currentTimeMillis())
                }
                db.insertWithOnConflict(TABLE_INTELLIGENCE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    // ──────────────────────────────────────────────
    // Internal Query Helpers
    // ──────────────────────────────────────────────

    private fun queryRecentReports(
        db: SQLiteDatabase,
        product: String?,
        area: String?,
        lat: Double?,
        lng: Double?,
        radiusKm: Double,
        today: String
    ): List<CompetitorReport> {
        val results = mutableListOf<CompetitorReport>()
        val cutoffDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(
            java.util.Date(System.currentTimeMillis() - STALE_DAYS * 24 * 60 * 60 * 1000L)
        )

        val selection = StringBuilder("report_date >= ?")
        val args = mutableListOf(cutoffDate)

        product?.let {
            selection.append(" AND product_name = ?")
            args.add(it)
        }
        area?.let {
            selection.append(" AND location_name = ?")
            args.add(it)
        }
        // Simple bounding-box filter if lat/lng provided
        if (lat != null && lng != null) {
            val latDelta = radiusKm / 111.0
            val lngDelta = radiusKm / (111.0 * Math.cos(Math.toRadians(lat)))
            selection.append(" AND latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ?")
            args.addAll(listOf(
                (lat - latDelta).toString(), (lat + latDelta).toString(),
                (lng - lngDelta).toString(), (lng + lngDelta).toString()
            ))
        }

        val cursor = db.query(
            TABLE_REPORTS, null,
            selection.toString(), args.toTypedArray(),
            null, null, "report_date DESC, created_at DESC"
        )

        cursor.use {
            while (it.moveToNext()) {
                results.add(CompetitorReport(
                    id = it.getLong(it.getColumnIndexOrThrow("id")),
                    reporterId = it.getString(it.getColumnIndexOrThrow("reporter_id")),
                    competitorDescription = it.getString(it.getColumnIndexOrThrow("competitor_description")),
                    productName = it.getString(it.getColumnIndexOrThrow("product_name")),
                    price = it.getDouble(it.getColumnIndexOrThrow("price")),
                    unit = it.getString(it.getColumnIndexOrThrow("unit")),
                    locationName = it.getString(it.getColumnIndexOrThrow("location_name")),
                    latitude = it.getDoubleOrNull(it.getColumnIndexOrThrow("latitude")),
                    longitude = it.getDoubleOrNull(it.getColumnIndexOrThrow("longitude")),
                    reportDate = it.getString(it.getColumnIndexOrThrow("report_date")),
                    reportTime = it.getString(it.getColumnIndexOrThrow("report_time")),
                    qualityNote = it.getString(it.getColumnIndexOrThrow("quality_note"))
                ))
            }
        }
        return results
    }

    private fun queryIntelligence(
        db: SQLiteDatabase,
        product: String?,
        area: String?,
        today: String
    ): List<IntelEntry> {
        val results = mutableListOf<IntelEntry>()
        val selection = StringBuilder("date_computed = ?")
        val args = mutableListOf(today)
        product?.let { selection.append(" AND product_name = ?"); args.add(it) }
        area?.let { selection.append(" AND area_name = ?"); args.add(it) }

        val cursor = db.query(
            TABLE_INTELLIGENCE, null,
            selection.toString(), args.toTypedArray(),
            null, null, "vendor_count DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                results.add(IntelEntry(
                    areaName = it.getString(it.getColumnIndexOrThrow("area_name")),
                    productName = it.getString(it.getColumnIndexOrThrow("product_name")),
                    avgPrice = it.getDouble(it.getColumnIndexOrThrow("avg_price")),
                    minPrice = it.getDouble(it.getColumnIndexOrThrow("min_price")),
                    maxPrice = it.getDouble(it.getColumnIndexOrThrow("max_price")),
                    vendorCount = it.getInt(it.getColumnIndexOrThrow("vendor_count")),
                    saturationLevel = it.getString(it.getColumnIndexOrThrow("saturation_level"))
                ))
            }
        }
        return results
    }

    private fun queryOpportunities(db: SQLiteDatabase, area: String?): List<OpportunityEntry> {
        val results = mutableListOf<OpportunityEntry>()
        val selection = if (area != null) "area_name IN (?, 'general')" else "area_name = 'general'"
        val args = if (area != null) arrayOf(area, "general") else arrayOf("general")

        val cursor = db.query(
            TABLE_OPPORTUNITIES, null,
            selection, args, null, null, "opportunity_score DESC", "10"
        )
        cursor.use {
            while (it.moveToNext()) {
                results.add(OpportunityEntry(
                    areaName = it.getString(it.getColumnIndexOrThrow("area_name")),
                    productName = it.getString(it.getColumnIndexOrThrow("product_name")),
                    demandSignal = it.getString(it.getColumnIndexOrThrow("demand_signal")),
                    currentSupplyLevel = it.getString(it.getColumnIndexOrThrow("current_supply_level")),
                    opportunityScore = it.getDouble(it.getColumnIndexOrThrow("opportunity_score")),
                    suggestedPrice = it.getDouble(it.getColumnIndexOrThrow("suggested_price")),
                    estimatedDailyVolume = it.getDoubleOrNull(it.getColumnIndexOrThrow("estimated_daily_volume"))
                ))
            }
        }
        return results
    }

    // ──────────────────────────────────────────────
    // Classification Helpers
    // ──────────────────────────────────────────────

    private fun classifySaturation(vendorCount: Int): String = when {
        vendorCount >= SATURATION_HIGH -> "oversupplied"
        vendorCount <= SATURATION_LOW -> "undersupplied"
        else -> "balanced"
    }

    private fun formatOpportunityReason(opp: OpportunityEntry): String = when {
        opp.currentSupplyLevel == "none" && opp.opportunityScore >= 0.85 ->
            "Hakuna mtu anauza — fursa kubwa!"
        opp.currentSupplyLevel == "none" ->
            "Hakuna mtu anauza hapa bado."
        opp.currentSupplyLevel == "low" && opp.opportunityScore >= 0.7 ->
            "Wauzaji wachache — unaweza kuingia."
        opp.demandSignal == "customer_requests" ->
            "Wateja wanaomba — muhitaji upo."
        opp.demandSignal == "market_trend" ->
            "Trend ya soko inaonyesha muhitaji."
        else ->
            "Fursa ya wastani — jaribu ukaguzi."
    }

    private fun formatPrice(price: Double): String {
        return if (price == price.toLong().toDouble()) {
            "%,.0f".format(price)
        } else {
            "%,.1f".format(price)
        }
    }

    // ──────────────────────────────────────────────
    // Data Classes
    // ──────────────────────────────────────────────

    data class CompetitorReport(
        val id: Long,
        val reporterId: String,
        val competitorDescription: String?,
        val productName: String,
        val price: Double,
        val unit: String,
        val locationName: String?,
        val latitude: Double?,
        val longitude: Double?,
        val reportDate: String,
        val reportTime: String?,
        val qualityNote: String?
    )

    data class CompetitorReportData(
        val reporterId: String,
        val competitorDescription: String?,
        val productName: String,
        val price: Double,
        val unit: String = "kg",
        val locationName: String? = null,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val qualityNote: String? = null,
        val reportDate: String,
        val reportTime: String? = null
    )

    data class ProductCompetitorSummary(
        val productName: String,
        val unit: String,
        val avgPrice: Double,
        val minPrice: Double,
        val maxPrice: Double,
        val vendorCount: Int,
        val recentReports: Int
    )

    data class SaturationInfo(
        val productName: String,
        val vendorCount: Int,
        val avgPrice: Double,
        val saturationLevel: String
    )

    data class IntelEntry(
        val areaName: String,
        val productName: String,
        val avgPrice: Double,
        val minPrice: Double,
        val maxPrice: Double,
        val vendorCount: Int,
        val saturationLevel: String
    )

    data class OpportunityEntry(
        val areaName: String,
        val productName: String,
        val demandSignal: String?,
        val currentSupplyLevel: String,
        val opportunityScore: Double,
        val suggestedPrice: Double,
        val estimatedDailyVolume: Double?
    )
}

// ──────────────────────────────────────────────
// Cursor extension for nullable values
// ──────────────────────────────────────────────
private fun Cursor.getDoubleOrNull(columnIndex: Int): Double? {
    return if (isNull(columnIndex)) null else getDouble(columnIndex)
}
