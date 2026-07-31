package com.msaidizi.agent.tools.market

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import com.msaidizi.agent.tools.core.*

/**
 * MarketDayPlanner — Optimal market selection tool for informal workers.
 *
 * Tells a mama mboga (or any trader) which market to visit today based on
 * current prices, distance, transport costs, product availability, and
 * market day schedules. Replaces the "go to the same market every day" habit
 * with data-driven routing.
 *
 * Problem solved:
 * - MI-5: Wrong stock purchase decisions (KES 300–700/day loss)
 * - CF-5: Market day oversupply coordination (KES 300–800/day loss)
 * - IA-3: Wrong product mix (KES 300–700/day loss)
 * - IA-5: Supplier price gap (KES 200–500/day loss)
 *
 * Offline-first: market directory + prices cached per county in SQLite.
 */
@Singleton
class MarketDayPlanner @Inject constructor(
    private val context: Context
) : Tool {

    override val name = "market_day_planner"
    override val description = "Which market to go to today based on prices, distance, transport cost, product availability. Helps traders pick the best market for sourcing."

    override val argsSchema = argSchema {
        enum(
            "action", "Action to perform",
            listOf(
                "plan_today",       // "Niende sokoni gani leo?"
                "compare_markets",  // Side-by-side market price comparison
                "best_market",      // Find best single market for products
                "schedule",         // Market day calendar & product suggestions
                "history"           // Past sourcing decisions & outcomes
            ),
            required = true
        )
        string("products", "Comma-separated product names to source (e.g. 'nyanya,sukuma wiki,vitunguu')", required = false)
        string("market", "Specific market name", required = false)
        string("market_b", "Second market name for comparison", required = false)
        string("region", "Region/county filter (e.g. 'Nairobi', 'Migori')", required = false)
        number("latitude", "Worker's current latitude for distance calculation", required = false)
        number("longitude", "Worker's current longitude for distance calculation", required = false)
        number("transport_budget", "Max transport budget in KES", required = false)
        integer("days", "Number of days for history lookup", required = false)
        boolean("voice", "Format response for Swahili voice output", required = false)
    }

    // ──────────────────────────────────────────────
    // SQLite Database
    // ──────────────────────────────────────────────

    inner class PlannerDatabase(context: Context) :
        SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            // Market directory
            db.execSQL("""
                CREATE TABLE $TABLE_MARKETS (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    market_name TEXT NOT NULL,
                    market_type TEXT NOT NULL DEFAULT 'daily',
                    county TEXT NOT NULL,
                    sub_county TEXT,
                    latitude REAL,
                    longitude REAL,
                    operating_days TEXT,
                    operating_hours TEXT,
                    peak_hours TEXT,
                    products_available TEXT,
                    transport_cost_from_center REAL,
                    notes TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    UNIQUE(market_name, county)
                )
            """)

            // Market prices (synced from MarketPriceBroadcaster)
            db.execSQL("""
                CREATE TABLE $TABLE_PRICES (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    market_id INTEGER NOT NULL,
                    product_name TEXT NOT NULL,
                    price_per_unit REAL NOT NULL,
                    unit TEXT NOT NULL DEFAULT 'kg',
                    price_type TEXT NOT NULL DEFAULT 'retail',
                    quality_grade TEXT,
                    availability TEXT NOT NULL DEFAULT 'available',
                    date_recorded TEXT NOT NULL,
                    source TEXT,
                    report_count INTEGER DEFAULT 1,
                    created_at INTEGER NOT NULL,
                    FOREIGN KEY (market_id) REFERENCES $TABLE_MARKETS(id)
                )
            """)

            // Sourcing history (personalized recommendations)
            db.execSQL("""
                CREATE TABLE $TABLE_HISTORY (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    worker_id TEXT NOT NULL,
                    market_id INTEGER NOT NULL,
                    product_name TEXT NOT NULL,
                    quantity_purchased REAL,
                    price_paid REAL,
                    quality_rating INTEGER,
                    sourcing_date TEXT NOT NULL,
                    transport_cost REAL,
                    total_time_hours REAL,
                    created_at INTEGER NOT NULL,
                    FOREIGN KEY (market_id) REFERENCES $TABLE_MARKETS(id)
                )
            """)

            // Market day schedule (special/recurring market days)
            db.execSQL("""
                CREATE TABLE $TABLE_DAYS (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    market_id INTEGER NOT NULL,
                    market_day_date TEXT NOT NULL,
                    day_of_week TEXT,
                    special_event TEXT,
                    expected_crowd_level TEXT DEFAULT 'medium',
                    created_at INTEGER NOT NULL,
                    FOREIGN KEY (market_id) REFERENCES $TABLE_MARKETS(id)
                )
            """)

            // Indexes
            db.execSQL("CREATE INDEX idx_mkt_county ON $TABLE_MARKETS(county)")
            db.execSQL("CREATE INDEX idx_mkt_type ON $TABLE_MARKETS(market_type)")
            db.execSQL("CREATE INDEX idx_prices_market ON $TABLE_PRICES(market_id, date_recorded)")
            db.execSQL("CREATE INDEX idx_prices_product ON $TABLE_PRICES(product_name, date_recorded)")
            db.execSQL("CREATE INDEX idx_history_worker ON $TABLE_HISTORY(worker_id, sourcing_date)")
            db.execSQL("CREATE INDEX idx_days_date ON $TABLE_DAYS(market_day_date)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_DAYS")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_HISTORY")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_PRICES")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_MARKETS")
            onCreate(db)
        }
    }

    // ──────────────────────────────────────────────
    // Constants & State
    // ──────────────────────────────────────────────

    companion object {
        private const val DB_NAME = "market_planner.db"
        private const val DB_VERSION = 1
        private const val TABLE_MARKETS = "markets"
        private const val TABLE_PRICES = "market_prices"
        private const val TABLE_HISTORY = "sourcing_history"
        private const val TABLE_DAYS = "market_days"

        // Stale cache threshold
        private const val STALE_THRESHOLD_MS = 24 * 60 * 60 * 1000L

        // Days of week for market schedule matching
        private val DAY_OF_WEEK = listOf(
            "sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday"
        )
    }

    private var dbHelper: PlannerDatabase? = null

    private fun getDb(): SQLiteDatabase {
        if (dbHelper == null) {
            dbHelper = PlannerDatabase(context)
        }
        return dbHelper!!.writableDatabase
    }

    // ──────────────────────────────────────────────
    // Product normalization (Swahili-aware)
    // ──────────────────────────────────────────────

    private val productAliases = mapOf(
        "nyanya" to "tomatoes", "tomato" to "tomatoes",
        "mahindi" to "maize", "corn" to "maize",
        "parachichi" to "avocado",
        "maharagwe" to "beans", "bean" to "beans",
        "vitunguu" to "onions", "onion" to "onions",
        "viazi" to "potatoes", "potato" to "potatoes",
        "kale" to "sukuma wiki",
        "kabichi" to "cabbage",
        "embe" to "mangoes", "mango" to "mangoes",
        "ndizi" to "bananas", "banana" to "bananas",
        "maziwa" to "milk",
        "mayai" to "eggs", "egg" to "eggs",
        "kuku" to "chicken",
        "samaki" to "fish",
        "mtama" to "sorghum",
        "wimbi" to "millet",
        "njugu" to "groundnuts",
        "miwa" to "sugar cane",
        "tikiti maji" to "watermelon",
        "pilau masala" to "pilau masala",
        "avocado" to "avocado"
    )

    private val productUnits = mapOf(
        "tomatoes" to "kg", "maize" to "kg", "avocado" to "kg",
        "beans" to "kg", "onions" to "kg", "potatoes" to "kg",
        "sukuma wiki" to "bunch", "cabbage" to "head",
        "mangoes" to "kg", "bananas" to "bunch",
        "milk" to "litre", "eggs" to "tray",
        "chicken" to "piece", "fish" to "kg",
        "sorghum" to "kg", "millet" to "kg",
        "groundnuts" to "kg", "sugar cane" to "stalk",
        "watermelon" to "piece", "pilau masala" to "packet"
    )

    private fun normalizeProduct(raw: String): String {
        val lower = raw.trim().lowercase()
        return productAliases[lower] ?: lower
    }

    private fun getUnit(product: String): String {
        val norm = normalizeProduct(product)
        return productUnits[norm] ?: productUnits[product.lowercase()] ?: "kg"
    }

    private fun parseProducts(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(",").map { normalizeProduct(it.trim()) }.filter { it.isNotBlank() }
    }

    private fun todayString(): String {
        val cal = java.util.Calendar.getInstance()
        return "%04d-%02d-%02d".format(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    private fun todayDayOfWeek(): String {
        val cal = java.util.Calendar.getInstance()
        return DAY_OF_WEEK[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1]
    }

    // ──────────────────────────────────────────────
    // Haversine distance (km)
    // ──────────────────────────────────────────────

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0 // Earth radius in km
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }

    // ──────────────────────────────────────────────
    // Market data classes
    // ──────────────────────────────────────────────

    data class MarketInfo(
        val id: Long,
        val name: String,
        val type: String,
        val county: String,
        val subCounty: String?,
        val latitude: Double?,
        val longitude: Double?,
        val operatingDays: List<String>,
        val openHour: String?,
        val closeHour: String?,
        val productsAvailable: List<String>,
        val transportCost: Double?
    )

    data class MarketPrice(
        val marketId: Long,
        val marketName: String,
        val productName: String,
        val pricePerUnit: Double,
        val unit: String,
        val availability: String,
        val dateRecorded: String
    )

    data class MarketScore(
        val market: MarketInfo,
        val totalCost: Double,       // sum of product prices + transport
        val productPrices: Map<String, Double>,
        val transportCost: Double,
        val distanceKm: Double,
        val availableProducts: Int,
        val missingProducts: List<String>,
        val score: Double            // lower is better
    )

    // ──────────────────────────────────────────────
    // Tool Execute
    // ──────────────────────────────────────────────

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"]
            ?: return ToolResult.error(
                name,
                "Action required. Use: plan_today, compare_markets, best_market, schedule, history",
                "MISSING_ACTION"
            )

        return when (action.lowercase()) {
            "plan_today" -> planToday(params)
            "compare_markets" -> compareMarkets(params)
            "best_market" -> bestMarket(params)
            "schedule" -> schedule(params)
            "history" -> history(params)
            else -> ToolResult.error(
                name,
                "Unknown action: $action. Valid: plan_today, compare_markets, best_market, schedule, history",
                "INVALID_ACTION"
            )
        }
    }

    // ──────────────────────────────────────────────
    // ACTION: plan_today — Recommend best market for today
    // "Niende sokoni gani leo kununua mboga?"
    // ──────────────────────────────────────────────

    private fun planToday(params: Map<String, String>): ToolResult {
        val products = parseProducts(params["products"])
        val region = params["region"]
        val lat = params["latitude"]?.toDoubleOrNull()
        val lon = params["longitude"]?.toDoubleOrNull()
        val budget = params["transport_budget"]?.toDoubleOrNull()
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        if (products.isEmpty()) {
            return ToolResult.error(
                name,
                "Taja bidhaa unazotaka kununua. Mfano: products='nyanya,sukuma wiki'",
                "MISSING_PRODUCTS"
            )
        }

        val db = getDb()
        val today = todayString()
        val dow = todayDayOfWeek()

        // Find markets operating today
        val operatingMarkets = getOperatingMarkets(db, region, dow, today)
        if (operatingMarkets.isEmpty()) {
            return ToolResult.success(
                name,
                mapOf("operating_markets" to 0, "products" to products),
                if (voice) "Leo hakuna masoko yanayofungua karibu nawe. Jaribu kesho."
                else "No markets operating today in this region."
            )
        }

        // Score each market
        val scores = operatingMarkets.map { market ->
            scoreMarket(db, market, products, lat, lon, budget)
        }.sortedBy { it.score }

        val best = scores.first()
        val unit = getUnit(products.first())

        // Build comparison data
        val comparisonData = scores.map { s ->
            mapOf(
                "market" to s.market.name,
                "total_cost" to s.totalCost,
                "transport_cost" to s.transportCost,
                "distance_km" to s.distanceKm,
                "available" to s.availableProducts,
                "missing" to s.missingProducts,
                "product_prices" to s.productPrices,
                "score" to s.score
            )
        }

        val message = if (voice) {
            buildPlanTodayVoice(best, scores, products)
        } else {
            buildPlanTodayText(best, scores, products)
        }

        return ToolResult.success(
            name,
            mapOf(
                "recommended_market" to best.market.name,
                "total_cost" to best.totalCost,
                "transport_cost" to best.transportCost,
                "alternatives" to comparisonData,
                "products" to products
            ),
            message
        )
    }

    private fun buildPlanTodayVoice(best: MarketScore, all: List<MarketScore>, products: List<String>): String {
        return buildString {
            append("Leo nenda ${best.market.name}.\n\n")

            // Product prices at recommended market
            append("Bei ya leo:\n")
            best.productPrices.forEach { (product, price) ->
                val unit = getUnit(product)
                val avail = if (product !in best.missingProducts) "" else " (haipatikani)"
                append("• $product: KES ${fmt(price)} kwa $unit$avail\n")
            }

            append("\nUsafiri: KES ${fmt(best.transportCost)}")
            if (best.distanceKm > 0) {
                append(" (km ${fmt1(best.distanceKm)})")
            }
            append("\nJumla gharama: KES ${fmt(best.totalCost)}")

            // Show alternatives
            if (all.size > 1) {
                val alt = all[1]
                val savings = alt.totalCost - best.totalCost
                append("\n\nUkilinganisha na ${alt.market.name}:")
                append("\n• ${alt.market.name} jumla: KES ${fmt(alt.totalCost)}")
                if (savings > 0) {
                    append("\n• Unaokoa KES ${fmt(savings)} kwa ${best.market.name}")
                } else {
                    append("\n• ${best.market.name} ni ghali zaidi kwa KES ${fmt(abs(savings))}")
                    append(" lakini bidhaa zaidi zinapatikana")
                }
            }

            // Missing products warning
            if (best.missingProducts.isNotEmpty()) {
                append("\n\n⚠️ ${best.market.name} haina: ${best.missingProducts.joinToString(", ")}")
                if (all.size > 1) {
                    val altWithMissing = all.firstOrNull { a ->
                        best.missingProducts.all { it !in a.missingProducts }
                    }
                    altWithMissing?.let {
                        append("\nJaribu ${it.market.name} kwa ${best.missingProducts.joinToString(", ")}")
                    }
                }
            }
        }
    }

    private fun buildPlanTodayText(best: MarketScore, all: List<MarketScore>, products: List<String>): String {
        return buildString {
            append("Recommended: ${best.market.name}\n\n")
            append("Product prices:\n")
            best.productPrices.forEach { (product, price) ->
                append("  • $product: KES ${fmt(price)}/${getUnit(product)}\n")
            }
            append("\nTransport: KES ${fmt(best.transportCost)} (${fmt1(best.distanceKm)} km)")
            append("\nTotal cost: KES ${fmt(best.totalCost)}")

            if (all.size > 1) {
                append("\n\nAlternatives:\n")
                all.drop(1).take(3).forEach { alt ->
                    append("  • ${alt.market.name}: KES ${fmt(alt.totalCost)} total")
                    if (alt.missingProducts.isNotEmpty()) {
                        append(" (missing: ${alt.missingProducts.joinToString(", ")})")
                    }
                    append("\n")
                }
            }
        }
    }

    // ──────────────────────────────────────────────
    // ACTION: compare_markets — Side-by-side comparison
    // "Sokoni A na B, bei gani ni bora?"
    // ──────────────────────────────────────────────

    private fun compareMarkets(params: Map<String, String>): ToolResult {
        val products = parseProducts(params["products"])
        val marketA = params["market"]
        val marketB = params["market_b"]
        val region = params["region"]
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        if (products.isEmpty()) {
            return ToolResult.error(name, "Taja bidhaa za kulinganisha. Mfano: products='nyanya,sukuma wiki'", "MISSING_PRODUCTS")
        }

        val db = getDb()
        val today = todayString()

        // Get prices for the requested products across markets
        val allPrices = queryPricesForProducts(db, products, region, today)

        if (allPrices.isEmpty()) {
            return ToolResult.success(
                name,
                mapOf("products" to products, "found" to false),
                if (voice) "Sikujua bei za bidhaa hizi sokoni. Jaribu baada ya sync."
                else "No price data found for these products."
            )
        }

        // Group by market
        val byMarket = allPrices.groupBy { it.marketName }

        // Filter to requested markets if specified
        val targetMarkets = when {
            marketA != null && marketB != null -> {
                byMarket.filterKeys { it.equals(marketA, true) || it.equals(marketB, true) }
            }
            marketA != null -> {
                byMarket.filterKeys { it.equals(marketA, true) }
            }
            else -> byMarket
        }

        if (targetMarkets.isEmpty()) {
            return ToolResult.success(
                name,
                mapOf("products" to products, "found" to false),
                if (voice) "Sikujua bei za bidhaa sokoni ${marketA ?: ""}${marketB?.let { " na $it" } ?: ""}."
                else "No data for specified markets."
            )
        }

        // Build comparison
        val comparison = targetMarkets.map { (mktName, prices) ->
            val productMap = prices.associate { it.productName to it.pricePerUnit }
            val avgPrice = if (productMap.isNotEmpty()) productMap.values.average() else 0.0
            val available = products.count { it in productMap }
            val missing = products.filter { it !in productMap }
            mapOf(
                "market" to mktName,
                "product_prices" to productMap,
                "avg_price" to avgPrice,
                "available_products" to available,
                "missing_products" to missing,
                "total_for_all" to productMap.values.sum()
            )
        }.sortedBy { it["avg_price"] as Double }

        val cheapest = comparison.first()
        val mostExpensive = comparison.last()
        val unit = getUnit(products.first())

        val message = if (voice) {
            buildString {
                append("Linganisha bei:\n\n")
                comparison.forEach { c ->
                    val mkt = c["market"] as String
                    val prices = c["product_prices"] as Map<*, *>
                    val missing = c["missing_products"] as List<*>
                    append("📍 $mkt:\n")
                    prices.forEach { (prod, price) ->
                        append("  • $prod: KES ${fmt(price as Double)} kwa ${getUnit(prod as String)}\n")
                    }
                    if (missing.isNotEmpty()) {
                        append("  ⚠️ Haipatikani: ${missing.joinToString(", ")}\n")
                    }
                    append("\n")
                }

                val cheapMkt = cheapest["market"] as String
                val expensiveMkt = mostExpensive["market"] as String
                val cheapTotal = cheapest["total_for_all"] as Double
                val expensiveTotal = mostExpensive["total_for_all"] as Double
                val diff = expensiveTotal - cheapTotal

                if (comparison.size > 1 && diff > 0) {
                    append("Bei rahisi: $cheapMkt (KES ${fmt(cheapTotal)} jumla)")
                    append("\nGhali zaidi: $expensiveMkt (KES ${fmt(expensiveTotal)} jumla)")
                    append("\nTofauti: KES ${fmt(diff)} — nenda $cheapMkt!")
                }
            }
        } else {
            buildString {
                append("Market price comparison:\n\n")
                comparison.forEach { c ->
                    val mkt = c["market"] as String
                    val prices = c["product_prices"] as Map<*, *>
                    append("[$mkt]\n")
                    prices.forEach { (prod, price) ->
                        append("  $prod: KES ${fmt(price as Double)}/${getUnit(prod as String)}\n")
                    }
                    append("  Total: KES ${fmt(c["total_for_all"] as Double)}\n\n")
                }
                if (comparison.size > 1) {
                    val savings = (mostExpensive["total_for_all"] as Double) - (cheapest["total_for_all"] as Double)
                    append("Best: ${cheapest["market"]} — save KES ${fmt(savings)}")
                }
            }
        }

        return ToolResult.success(
            name,
            mapOf(
                "products" to products,
                "comparisons" to comparison,
                "cheapest_market" to cheapest["market"],
                "found" to true
            ),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: best_market — Single best market for products
    // ──────────────────────────────────────────────

    private fun bestMarket(params: Map<String, String>): ToolResult {
        val rawProduct = params["products"] ?: params["market"]?.let { null }
            ?: return ToolResult.error(name, "Taja bidhaa. Mfano: products='nyanya'", "MISSING_PRODUCT")
        val products = parseProducts(rawProduct)
        val region = params["region"]
        val lat = params["latitude"]?.toDoubleOrNull()
        val lon = params["longitude"]?.toDoubleOrNull()
        val budget = params["transport_budget"]?.toDoubleOrNull()
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        if (products.isEmpty()) {
            return ToolResult.error(name, "Taja bidhaa. Mfano: products='nyanya'", "MISSING_PRODUCT")
        }

        val db = getDb()
        val today = todayString()
        val dow = todayDayOfWeek()

        val operatingMarkets = getOperatingMarkets(db, region, dow, today)
        if (operatingMarkets.isEmpty()) {
            return ToolResult.success(
                name,
                mapOf("best_market" to null, "products" to products),
                if (voice) "Leo hakuna masoko yanayofungua."
                else "No markets operating today."
            )
        }

        val scores = operatingMarkets.map { market ->
            scoreMarket(db, market, products, lat, lon, budget)
        }.sortedBy { it.score }

        val best = scores.first()

        val message = if (voice) {
            buildString {
                append("Soko bora la ${products.joinToString(", ")}: ${best.market.name}\n")
                best.productPrices.forEach { (prod, price) ->
                    append("• $prod: KES ${fmt(price)} kwa ${getUnit(prod)}\n")
                }
                append("\nUsafiri: KES ${fmt(best.transportCost)}")
                append("\nJumla: KES ${fmt(best.totalCost)}")
                if (best.missingProducts.isNotEmpty()) {
                    append("\n⚠️ Haipatikani: ${best.missingProducts.joinToString(", ")}")
                }
            }
        } else {
            buildString {
                append("Best market: ${best.market.name}\n")
                best.productPrices.forEach { (prod, price) ->
                    append("  $prod: KES ${fmt(price)}/${getUnit(prod)}\n")
                }
                append("Transport: KES ${fmt(best.transportCost)} | Total: KES ${fmt(best.totalCost)}")
            }
        }

        return ToolResult.success(
            name,
            mapOf(
                "best_market" to best.market.name,
                "total_cost" to best.totalCost,
                "transport_cost" to best.transportCost,
                "product_prices" to best.productPrices,
                "missing" to best.missingProducts,
                "products" to products
            ),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: schedule — Market day calendar & product suggestions
    // "Leo ni soko gani?" / "Ninunue nini leo?"
    // ──────────────────────────────────────────────

    private fun schedule(params: Map<String, String>): ToolResult {
        val region = params["region"]
        val products = parseProducts(params["products"])
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val db = getDb()
        val today = todayString()
        val dow = todayDayOfWeek()

        // Get all markets in region
        val markets = getAllMarkets(db, region)

        if (markets.isEmpty()) {
            return ToolResult.success(
                name,
                mapOf("markets" to emptyList<Any>()),
                if (voice) "Hakuna masoko yaliyosajiliwa kwenye eneo lako."
                else "No markets registered in your region."
            )
        }

        // Separate into today's markets and upcoming
        val todayMarkets = markets.filter { mkt ->
            mkt.operatingDays.isEmpty() || dow in mkt.operatingDays
        }
        val upcomingMarkets = markets.filter { mkt ->
            mkt.operatingDays.isNotEmpty() && dow !in mkt.operatingDays
        }.take(5)

        // If products specified, find best prices
        val productSuggestions = if (products.isNotEmpty()) {
            val prices = queryPricesForProducts(db, products, region, today)
            products.map { product ->
                val productPrices = prices.filter { it.productName == product }
                if (productPrices.isNotEmpty()) {
                    val cheapest = productPrices.minByOrNull { it.pricePerUnit }!!
                    val mostExpensive = productPrices.maxByOrNull { it.pricePerUnit }!!
                    mapOf(
                        "product" to product,
                        "best_market" to cheapest.marketName,
                        "best_price" to cheapest.pricePerUnit,
                        "worst_market" to mostExpensive.marketName,
                        "worst_price" to mostExpensive.pricePerUnit,
                        "unit" to getUnit(product)
                    )
                } else {
                    mapOf("product" to product, "no_data" to true)
                }
            }
        } else emptyList()

        val message = if (voice) {
            buildString {
                append("Masoko ya leo ($dow):\n")
                if (todayMarkets.isNotEmpty()) {
                    todayMarkets.forEach { mkt ->
                        append("• ${mkt.name}")
                        mkt.openHour?.let { append(" ($it-${mkt.closeHour ?: "?"}") ; append(")") }
                        append("\n")
                    }
                } else {
                    append("  Hakuna masoko maalum leo.\n")
                }

                if (upcomingMarkets.isNotEmpty()) {
                    append("\nMasoko ya karibu:\n")
                    upcomingMarkets.forEach { mkt ->
                        val days = mkt.operatingDays.joinToString(", ")
                        append("• ${mkt.name}: $days\n")
                    }
                }

                if (productSuggestions.isNotEmpty()) {
                    append("\nPendekezo la bidhaa:\n")
                    productSuggestions.forEach { s ->
                        val product = s["product"] as String
                        if (s.containsKey("no_data")) {
                            append("• $product: hakuna bei — thibitisha sokoni\n")
                        } else {
                            val bestMkt = s["best_market"] as String
                            val bestPrice = s["best_price"] as Double
                            val unit = s["unit"] as String
                            append("• $product: bei nzuri $bestMkt (KES ${fmt(bestPrice)} kwa $unit)\n")
                        }
                    }
                }
            }
        } else {
            buildString {
                append("Today's markets ($dow):\n")
                todayMarkets.forEach { mkt ->
                    append("  • ${mkt.name}")
                    mkt.openHour?.let { append(" ($it-${mkt.closeHour ?: "?"}") ; append(")") }
                    append("\n")
                }
                if (productSuggestions.isNotEmpty()) {
                    append("\nProduct suggestions:\n")
                    productSuggestions.forEach { s ->
                        if (!s.containsKey("no_data")) {
                            append("  ${s["product"]}: best at ${s["best_market"]} @ KES ${fmt(s["best_price"] as Double)}\n")
                        }
                    }
                }
            }
        }

        return ToolResult.success(
            name,
            mapOf(
                "today_markets" to todayMarkets.map { it.name },
                "upcoming_markets" to upcomingMarkets.map { it.name },
                "product_suggestions" to productSuggestions,
                "day_of_week" to dow
            ),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: history — Past sourcing decisions
    // ──────────────────────────────────────────────

    private fun history(params: Map<String, String>): ToolResult {
        val products = parseProducts(params["products"])
        val market = params["market"]
        val days = params["days"]?.toIntOrNull() ?: 30
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val db = getDb()
        val cutoffDate = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, -days)
        }.let {
            "%04d-%02d-%02d".format(
                it.get(java.util.Calendar.YEAR),
                it.get(java.util.Calendar.MONTH) + 1,
                it.get(java.util.Calendar.DAY_OF_MONTH)
            )
        }

        val selection = StringBuilder("sourcing_date >= ?")
        val args = mutableListOf(cutoffDate)

        if (products.isNotEmpty()) {
            val placeholders = products.joinToString(",") { "?" }
            selection.append(" AND product_name IN ($placeholders)")
            args.addAll(products)
        }
        market?.let {
            selection.append(" AND market_id = (SELECT id FROM $TABLE_MARKETS WHERE market_name = ? LIMIT 1)")
            args.add(it)
        }

        val entries = mutableListOf<Map<String, Any?>>()
        val cursor = db.query(
            TABLE_HISTORY,
            null,
            selection.toString(),
            args.toTypedArray(),
            null, null,
            "sourcing_date DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                entries.add(mapOf(
                    "market_id" to it.getLong(it.getColumnIndexOrThrow("market_id")),
                    "product_name" to it.getString(it.getColumnIndexOrThrow("product_name")),
                    "quantity" to it.getDoubleOrNull(it.getColumnIndexOrThrow("quantity_purchased")),
                    "price_paid" to it.getDoubleOrNull(it.getColumnIndexOrThrow("price_paid")),
                    "quality_rating" to it.getIntOrNull(it.getColumnIndexOrThrow("quality_rating")),
                    "date" to it.getString(it.getColumnIndexOrThrow("sourcing_date")),
                    "transport_cost" to it.getDoubleOrNull(it.getColumnIndexOrThrow("transport_cost")),
                    "time_hours" to it.getDoubleOrNull(it.getColumnIndexOrThrow("total_time_hours"))
                ))
            }
        }

        if (entries.isEmpty()) {
            return ToolResult.success(
                name,
                mapOf("entries" to emptyList<Any>(), "days" to days),
                if (voice) "Hakuna historia ya ununuzi kwa siku $days zilizopita."
                else "No sourcing history in the last $days days."
            )
        }

        // Aggregate stats
        val totalSpent = entries.mapNotNull { it["price_paid"] as? Double }.sum()
        val totalTransport = entries.mapNotNull { it["transport_cost"] as? Double }.sum()
        val avgQuality = entries.mapNotNull { it["quality_rating"] as? Int }
            .takeIf { it.isNotEmpty() }?.average() ?: 0.0
        val marketVisits = entries.groupBy { it["market_id"] }
            .mapValues { it.value.size }
            .entries
            .sortedByDescending { it.value }

        // Resolve market names
        val marketNames = mutableMapOf<Long, String>()
        marketVisits.forEach { (mktId, _) ->
            val cursor2 = db.query(
                TABLE_MARKETS, arrayOf("market_name"), "id = ?",
                arrayOf(mktId.toString()), null, null, null
            )
            cursor2.use { c ->
                if (c.moveToFirst()) {
                    marketNames[mktId as Long] = c.getString(0)
                }
            }
        }

        val message = if (voice) {
            buildString {
                append("Historia ya ununuzi (siku $days):\n")
                append("• Mara ${entries.size}: KES ${fmt(totalSpent)} jumla\n")
                append("• Usafiri: KES ${fmt(totalTransport)}\n")
                if (avgQuality > 0) {
                    append("• Ubora wastani: ${"%.1f".format(avgQuality)}/5\n")
                }
                append("\nMasoko:\n")
                marketVisits.take(5).forEach { (mktId, count) ->
                    val mktName = marketNames[mktId as Long] ?: "Soko #$mktId"
                    append("• $mktName: mara $count\n")
                }
            }
        } else {
            buildString {
                append("Sourcing history ($days days, ${entries.size} entries):\n")
                append("Total spent: KES ${fmt(totalSpent)}\n")
                append("Transport: KES ${fmt(totalTransport)}\n")
                if (avgQuality > 0) append("Avg quality: ${"%.1f".format(avgQuality)}/5\n")
                append("\nTop markets:\n")
                marketVisits.take(5).forEach { (mktId, count) ->
                    append("  ${marketNames[mktId as Long] ?: "Market #$mktId"}: $count visits\n")
                }
            }
        }

        return ToolResult.success(
            name,
            mapOf(
                "entries" to entries.size,
                "total_spent" to totalSpent,
                "total_transport" to totalTransport,
                "avg_quality" to avgQuality,
                "top_markets" to marketVisits.take(5).map { (id, count) ->
                    mapOf("market" to (marketNames[id as Long] ?: "Unknown"), "visits" to count)
                },
                "days" to days
            ),
            message
        )
    }

    // ──────────────────────────────────────────────
    // Market Scoring Engine
    // ──────────────────────────────────────────────

    private fun scoreMarket(
        db: SQLiteDatabase,
        market: MarketInfo,
        products: List<String>,
        workerLat: Double?,
        workerLon: Double?,
        transportBudget: Double?
    ): MarketScore {
        val today = todayString()

        // Get prices for requested products at this market
        val productPrices = mutableMapOf<String, Double>()
        val missing = mutableListOf<String>()

        products.forEach { product ->
            val price = getLatestPrice(db, market.id, product, today)
            if (price != null) {
                productPrices[product] = price.pricePerUnit
            } else {
                missing.add(product)
            }
        }

        // Calculate distance & transport cost
        val distanceKm = if (workerLat != null && workerLon != null &&
            market.latitude != null && market.longitude != null
        ) {
            haversineKm(workerLat, workerLon, market.latitude, market.longitude)
        } else 0.0

        // Estimate transport cost: use stored value or estimate from distance
        val transportCost = market.transportCost
            ?: if (distanceKm > 0) estimateTransportCost(distanceKm) else 0.0

        // Check budget
        val overBudget = transportBudget != null && transportCost > transportBudget

        // Scoring (lower is better):
        // - Sum of product prices (main factor)
        // - Transport cost (significant)
        // - Penalty for missing products (heavy)
        // - Penalty for exceeding budget
        val priceTotal = productPrices.values.sum()
        val missingPenalty = missing.size * 50.0 // KES 50 penalty per missing product
        val budgetPenalty = if (overBudget) 200.0 else 0.0
        val score = priceTotal + transportCost + missingPenalty + budgetPenalty

        return MarketScore(
            market = market,
            totalCost = priceTotal + transportCost,
            productPrices = productPrices,
            transportCost = transportCost,
            distanceKm = distanceKm,
            availableProducts = productPrices.size,
            missingProducts = missing,
            score = score
        )
    }

    private fun estimateTransportCost(distanceKm: Double): Double {
        // Rough estimate: KES 30 base + KES 15/km
        return 30.0 + (distanceKm * 15.0)
    }

    // ──────────────────────────────────────────────
    // Database Queries
    // ──────────────────────────────────────────────

    private fun getOperatingMarkets(
        db: SQLiteDatabase,
        region: String?,
        dayOfWeek: String,
        today: String
    ): List<MarketInfo> {
        val results = mutableListOf<MarketInfo>()
        val selection = StringBuilder()
        val args = mutableListOf<String>()

        region?.let {
            selection.append("county = ?")
            args.add(it)
        }

        val where = if (selection.isNotEmpty()) selection.toString() else null

        val cursor = db.query(
            TABLE_MARKETS, null, where,
            if (args.isNotEmpty()) args.toTypedArray() else null,
            null, null, null
        )

        cursor.use {
            while (it.moveToNext()) {
                val mkt = cursorToMarket(it)

                // Check if market operates today:
                // 1. Has operating_days and today's day is in the list, OR
                // 2. Has a special market_day entry for today, OR
                // 3. operating_days is empty (daily market — always open)
                val operatesToday = when {
                    mkt.operatingDays.isEmpty() -> true // daily market
                    dayOfWeek in mkt.operatingDays -> true
                    else -> {
                        // Check for special market day
                        hasSpecialMarketDay(db, mkt.id, today)
                    }
                }

                if (operatesToday) {
                    results.add(mkt)
                }
            }
        }

        return results
    }

    private fun hasSpecialMarketDay(db: SQLiteDatabase, marketId: Long, date: String): Boolean {
        val cursor = db.query(
            TABLE_DAYS, arrayOf("id"),
            "market_id = ? AND market_day_date = ?",
            arrayOf(marketId.toString(), date),
            null, null, null
        )
        cursor.use {
            return it.moveToFirst()
        }
    }

    private fun getAllMarkets(db: SQLiteDatabase, region: String?): List<MarketInfo> {
        val results = mutableListOf<MarketInfo>()
        val selection = if (region != null) "county = ?" else null
        val args = if (region != null) arrayOf(region) else null

        val cursor = db.query(TABLE_MARKETS, null, selection, args, null, null, "market_name ASC")
        cursor.use {
            while (it.moveToNext()) {
                results.add(cursorToMarket(it))
            }
        }
        return results
    }

    private fun cursorToMarket(cursor: Cursor): MarketInfo {
        val operatingDaysRaw = cursor.getString(cursor.getColumnIndexOrThrow("operating_days"))
        val productsRaw = cursor.getString(cursor.getColumnIndexOrThrow("products_available"))
        val hoursRaw = cursor.getString(cursor.getColumnIndexOrThrow("operating_hours"))

        val operatingDays = parseJsonArray(operatingDaysRaw)
        val products = parseJsonArray(productsRaw)
        val hours = parseJsonObject(hoursRaw)

        return MarketInfo(
            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
            name = cursor.getString(cursor.getColumnIndexOrThrow("market_name")),
            type = cursor.getString(cursor.getColumnIndexOrThrow("market_type")),
            county = cursor.getString(cursor.getColumnIndexOrThrow("county")),
            subCounty = cursor.getString(cursor.getColumnIndexOrThrow("sub_county")),
            latitude = cursor.getDoubleOrNull(cursor.getColumnIndexOrThrow("latitude")),
            longitude = cursor.getDoubleOrNull(cursor.getColumnIndexOrThrow("longitude")),
            operatingDays = operatingDays,
            openHour = hours["open"],
            closeHour = hours["close"],
            productsAvailable = products,
            transportCost = cursor.getDoubleOrNull(cursor.getColumnIndexOrThrow("transport_cost_from_center"))
        )
    }

    private fun getLatestPrice(db: SQLiteDatabase, marketId: Long, product: String, today: String): MarketPrice? {
        val cursor = db.query(
            TABLE_PRICES, null,
            "market_id = ? AND product_name = ? AND date_recorded >= ?",
            arrayOf(marketId.toString(), product, today),
            null, null,
            "date_recorded DESC, created_at DESC",
            "1"
        )
        cursor.use {
            return if (it.moveToFirst()) {
                MarketPrice(
                    marketId = marketId,
                    marketName = "", // resolved later if needed
                    productName = it.getString(it.getColumnIndexOrThrow("product_name")),
                    pricePerUnit = it.getDouble(it.getColumnIndexOrThrow("price_per_unit")),
                    unit = it.getString(it.getColumnIndexOrThrow("unit")),
                    availability = it.getString(it.getColumnIndexOrThrow("availability")),
                    dateRecorded = it.getString(it.getColumnIndexOrThrow("date_recorded"))
                )
            } else null
        }
    }

    private fun queryPricesForProducts(
        db: SQLiteDatabase,
        products: List<String>,
        region: String?,
        today: String
    ): List<MarketPrice> {
        val results = mutableListOf<MarketPrice>()
        val placeholders = products.joinToString(",") { "?" }
        val args = mutableListOf<String>()
        args.addAll(products)
        args.add(today)

        val regionFilter = if (region != null) {
            args.add(region)
            " AND m.county = ?"
        } else ""

        val cursor = db.rawQuery("""
            SELECT p.*, m.market_name FROM $TABLE_PRICES p
            JOIN $TABLE_MARKETS m ON p.market_id = m.id
            WHERE p.product_name IN ($placeholders)
            AND p.date_recorded >= ?
            $regionFilter
            ORDER BY p.product_name, p.price_per_unit ASC
        """.trimIndent(), args.toTypedArray())

        cursor.use {
            while (it.moveToNext()) {
                results.add(MarketPrice(
                    marketId = it.getLong(it.getColumnIndexOrThrow("market_id")),
                    marketName = it.getString(it.getColumnIndexOrThrow("market_name")),
                    productName = it.getString(it.getColumnIndexOrThrow("product_name")),
                    pricePerUnit = it.getDouble(it.getColumnIndexOrThrow("price_per_unit")),
                    unit = it.getString(it.getColumnIndexOrThrow("unit")),
                    availability = it.getString(it.getColumnIndexOrThrow("availability")),
                    dateRecorded = it.getString(it.getColumnIndexOrThrow("date_recorded"))
                ))
            }
        }
        return results
    }

    // ──────────────────────────────────────────────
    // JSON helpers
    // ──────────────────────────────────────────────

    private fun parseJsonArray(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseJsonObject(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            val obj = JSONObject(raw)
            obj.keys().asSequence().associateWith { obj.getString(it) }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    // ──────────────────────────────────────────────
    // Public API for data insertion (called by SyncEngine)
    // ──────────────────────────────────────────────

    /**
     * Insert or update a market in the directory.
     */
    fun insertMarket(
        name: String,
        type: String = "daily",
        county: String,
        subCounty: String? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        operatingDays: List<String> = emptyList(),
        openHour: String? = null,
        closeHour: String? = null,
        productsAvailable: List<String> = emptyList(),
        transportCostFromCenter: Double? = null,
        notes: String? = null
    ): Long {
        val db = getDb()
        val now = System.currentTimeMillis()
        val hoursJson = if (openHour != null && closeHour != null) {
            JSONObject().apply { put("open", openHour); put("close", closeHour) }.toString()
        } else null

        val values = ContentValues().apply {
            put("market_name", name)
            put("market_type", type)
            put("county", county)
            put("sub_county", subCounty)
            put("latitude", latitude)
            put("longitude", longitude)
            put("operating_days", if (operatingDays.isNotEmpty()) JSONArray(operatingDays).toString() else null)
            put("operating_hours", hoursJson)
            put("products_available", if (productsAvailable.isNotEmpty()) JSONArray(productsAvailable).toString() else null)
            put("transport_cost_from_center", transportCostFromCenter)
            put("notes", notes)
            put("created_at", now)
            put("updated_at", now)
        }
        return db.insertWithOnConflict(TABLE_MARKETS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * Insert a market price record.
     */
    fun insertPrice(
        marketId: Long,
        productName: String,
        pricePerUnit: Double,
        unit: String = "kg",
        priceType: String = "retail",
        qualityGrade: String? = null,
        availability: String = "available",
        dateRecorded: String = todayString(),
        source: String? = null
    ): Long {
        val db = getDb()
        val values = ContentValues().apply {
            put("market_id", marketId)
            put("product_name", normalizeProduct(productName))
            put("price_per_unit", pricePerUnit)
            put("unit", unit)
            put("price_type", priceType)
            put("quality_grade", qualityGrade)
            put("availability", availability)
            put("date_recorded", dateRecorded)
            put("source", source)
            put("created_at", System.currentTimeMillis())
        }
        return db.insert(TABLE_PRICES, null, values)
    }

    /**
     * Record a sourcing decision for history/recommendations.
     */
    fun recordSourcing(
        workerId: String,
        marketId: Long,
        productName: String,
        quantityPurchased: Double? = null,
        pricePaid: Double? = null,
        qualityRating: Int? = null,
        transportCost: Double? = null,
        totalTimeHours: Double? = null,
        sourcingDate: String = todayString()
    ): Long {
        val db = getDb()
        val values = ContentValues().apply {
            put("worker_id", workerId)
            put("market_id", marketId)
            put("product_name", normalizeProduct(productName))
            put("quantity_purchased", quantityPurchased)
            put("price_paid", pricePaid)
            put("quality_rating", qualityRating)
            put("sourcing_date", sourcingDate)
            put("transport_cost", transportCost)
            put("total_time_hours", totalTimeHours)
            put("created_at", System.currentTimeMillis())
        }
        return db.insert(TABLE_HISTORY, null, values)
    }

    /**
     * Add a special market day entry.
     */
    fun addMarketDay(
        marketId: Long,
        date: String,
        dayOfWeek: String? = null,
        specialEvent: String? = null,
        crowdLevel: String = "medium"
    ): Long {
        val db = getDb()
        val values = ContentValues().apply {
            put("market_id", marketId)
            put("market_day_date", date)
            put("day_of_week", dayOfWeek)
            put("special_event", specialEvent)
            put("expected_crowd_level", crowdLevel)
            put("created_at", System.currentTimeMillis())
        }
        return db.insert(TABLE_DAYS, null, values)
    }

    /**
     * Bulk insert prices from a sync response.
     */
    fun bulkInsertPrices(prices: List<Triple<Long, String, Double>>, unit: String = "kg") {
        val db = getDb()
        db.beginTransaction()
        try {
            prices.forEach { (marketId, product, price) ->
                insertPrice(marketId, product, price, unit)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ──────────────────────────────────────────────
    // Formatting helpers
    // ──────────────────────────────────────────────

    private fun fmt(price: Double): String {
        return if (price == price.toLong().toDouble()) {
            "%,.0f".format(price)
        } else {
            "%,.1f".format(price)
        }
    }

    private fun fmt1(value: Double): String {
        return "%.1f".format(value)
    }
}

// ──────────────────────────────────────────────
// Cursor extensions for nullable types
// ──────────────────────────────────────────────
private fun Cursor.getDoubleOrNull(columnIndex: Int): Double? {
    return if (isNull(columnIndex)) null else getDouble(columnIndex)
}

private fun Cursor.getIntOrNull(columnIndex: Int): Int? {
    return if (isNull(columnIndex)) null else getInt(columnIndex)
}
