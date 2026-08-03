package com.msaidizi.agent.tools.market

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
 * ProducePriceTracker — Upstream price discovery for producers (farmers, fishermen, miners).
 *
 * Problem: Producers sell to middlemen at 40-60% of retail price because they lack
 * visibility on wholesale/market prices. A farmer selling maize at the farmgate gets
 * KES 30/kg while the same maize sells for KES 70/kg at Marikiti market 50km away.
 * This tool caches wholesale prices, shows trends, compares markets, and alerts
 * producers when prices are favorable for selling.
 *
 * Key difference from MarketPriceBroadcaster:
 * - MarketPriceBroadcaster = retail prices for vendors (mama mboga)
 * - ProducePriceTracker = wholesale/farmgate/landing-site prices for PRODUCERS
 *
 * Voice examples:
 *   "Bei ya mahindi sokoni ni ngapi?"       → Wholesale prices
 *   "Soko gani la bei nzuri ya samaki?"     → Best landing site
 *   "Bei ya maharagwe imepanda au kushuka?" → Price trend
 *   "Niarifu bei ikipanda"                  → Set price alert
 */
@Singleton
class ProducePriceTracker @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val name = "produce_price_tracker"
    override val description = "Wholesale/market price discovery for producers. Shows upstream prices, trends, and helps find the best market to sell produce."

    override val argsSchema = argSchema {
        enum(
            "action", "Action to perform",
            listOf(
                "check_price",       // Check current wholesale/market price
                "compare_markets",   // Compare prices across markets/landing sites
                "price_trend",       // Show weekly/monthly/seasonal price trend
                "set_alert",         // Alert when price is favorable
                "list_alerts",       // List active price alerts
                "remove_alert",      // Remove a price alert
                "best_time_to_sell", // When should I sell? Price + timing advice
                "record_price",      // Record a price observation (farmer-sourced)
                "sync_prices",       // Sync from backend
                "list_products"      // List tracked produce
            ),
            required = true
        )
        string("product", "Produce name (e.g. 'mahindi', 'samaki', 'nyanya')", required = false)
        string("market", "Market/landing site/buyer name", required = false)
        string("region", "Region/county", required = false)
        number("price", "Price in KES per unit (for recording)", required = false)
        string("unit", "Unit (kg/litre/gunia/ndoo)", required = false)
        string("period", "Time period: week/month/season", required = false)
        number("alert_price", "Price threshold for alert (KES)", required = false)
        enum("alert_direction", "Alert when price goes above or below",
            listOf("above", "below"), required = false)
        boolean("voice", "Format for voice output (Swahili)", required = false)
    }

    // ──────────────────────────────────────────────
    // SQLite Database for Offline Price Cache
    // ──────────────────────────────────────────────

    inner class ProducePriceDatabase(context: Context) :
        SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            // Wholesale/market prices — one row per product per market per day
            db.execSQL("""
                CREATE TABLE $TABLE_PRICES (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    product TEXT NOT NULL,
                    market TEXT NOT NULL,
                    market_type TEXT NOT NULL DEFAULT 'wholesale',
                    region TEXT NOT NULL,
                    price REAL NOT NULL,
                    price_min REAL,
                    price_max REAL,
                    unit TEXT NOT NULL DEFAULT 'kg',
                    source TEXT NOT NULL DEFAULT 'observation',
                    recorded_at INTEGER NOT NULL,
                    synced_at INTEGER NOT NULL,
                    UNIQUE(product, market, recorded_at)
                )
            """)

            // Price alerts — producers set these for favorable selling prices
            db.execSQL("""
                CREATE TABLE $TABLE_ALERTS (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    product TEXT NOT NULL,
                    market TEXT,
                    alert_price REAL NOT NULL,
                    direction TEXT NOT NULL DEFAULT 'above',
                    is_active INTEGER NOT NULL DEFAULT 1,
                    created_at INTEGER NOT NULL,
                    last_triggered_at INTEGER
                )
            """)

            // Price observations from producers (crowdsourced)
            db.execSQL("""
                CREATE TABLE $TABLE_OBSERVATIONS (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    product TEXT NOT NULL,
                    market TEXT NOT NULL,
                    price REAL NOT NULL,
                    unit TEXT NOT NULL DEFAULT 'kg',
                    observer_notes TEXT,
                    recorded_at INTEGER NOT NULL
                )
            """)

            // Sync metadata
            db.execSQL("""
                CREATE TABLE $TABLE_SYNC_META (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    sync_key TEXT NOT NULL UNIQUE,
                    last_sync_at INTEGER NOT NULL,
                    last_sync_status TEXT NOT NULL DEFAULT 'ok'
                )
            """)

            db.execSQL("CREATE INDEX idx_pp_product ON $TABLE_PRICES(product)")
            db.execSQL("CREATE INDEX idx_pp_market ON $TABLE_PRICES(market)")
            db.execSQL("CREATE INDEX idx_pp_date ON $TABLE_PRICES(recorded_at)")
            db.execSQL("CREATE INDEX idx_pp_product_market ON $TABLE_PRICES(product, market)")
            db.execSQL("CREATE INDEX idx_pp_alerts_product ON $TABLE_ALERTS(product, is_active)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_OBSERVATIONS")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_PRICES")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_ALERTS")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_SYNC_META")
            onCreate(db)
        }
    }

    companion object {
        private const val DB_NAME = "produce_prices.db"
        private const val DB_VERSION = 1
        private const val TABLE_PRICES = "produce_prices"
        private const val TABLE_ALERTS = "produce_alerts"
        private const val TABLE_OBSERVATIONS = "price_observations"
        private const val TABLE_SYNC_META = "produce_sync_meta"

        // Stale threshold — warn if price data is older than this
        private const val STALE_THRESHOLD_MS = 48 * 60 * 60 * 1000L // 48 hours

        // Significant price change threshold
        private const val SIGNIFICANT_CHANGE_PCT = 10.0

        // Market types for producers
        val MARKET_TYPES = mapOf(
            "wholesale" to "Soko la jumla",
            "farmgate" to "Shambani",
            "landing_site" to "Bandarini",  // for fishermen
            "collection_point" to "Kituo cha kukusanyia",
            "factory" to "Kiwandani",       // for tea/coffee factories
            "cooperative" to "Ushirikani",
            "miner_buyer" to "Mnunuzi wa madini"
        )
    }

    private var dbHelper: ProducePriceDatabase? = null

    private fun getDb(): SQLiteDatabase {
        if (dbHelper == null) {
            dbHelper = ProducePriceDatabase(context)
        }
        return dbHelper!!.writableDatabase
    }

    // ──────────────────────────────────────────────
    // Common produce Swahili aliases
    // ──────────────────────────────────────────────

    private val productAliases = mapOf(
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
        "omena" to "omena", "dagaa" to "omena", "sardine" to "omena",
        "nguruwe" to "pork", "pork" to "pork",
        "mbuzi" to "goat", "goat" to "goat",
        "ng'ombe" to "cattle", "cattle" to "cattle",
        "kuku" to "chicken", "chicken" to "chicken",
        "maziwa" to "milk", "milk" to "milk",
        "mayai" to "eggs", "eggs" to "eggs",
        "tikiti" to "watermelon", "tikiti maji" to "watermelon",
        "nanasi" to "pineapple", "pineapple" to "pineapple",
        "pilipili" to "chilli", "chilli" to "chilli",
        "bungo" to "cassava", "muhogo" to "cassava", "cassava" to "cassava",
        "madini" to "minerals", "minerals" to "minerals",
        "dhahabu" to "gold", "gold" to "gold",
        "almasi" to "diamond", "diamond" to "diamond",
        "ruby" to "ruby", "zamaradi" to "emerald", "emerald" to "emerald"
    )

    private val unitMap = mapOf(
        "gunia" to "gunia", "bag" to "gunia",
        "kilo" to "kg", "kg" to "kg",
        "lita" to "litre", "litre" to "litre",
        "ndoo" to "ndoo", "bucket" to "ndoo",
        "mkoba" to "mkoba", "sack" to "mkoba",
        "gramu" to "gram", "gram" to "gram"
    )

    // ──────────────────────────────────────────────
    // Tool Execute
    // ──────────────────────────────────────────────

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"]
            ?: return ToolResult.error(name, "Action required", "MISSING_ACTION")

        return when (action.lowercase()) {
            "check_price" -> checkPrice(params)
            "compare_markets" -> compareMarkets(params)
            "price_trend" -> priceTrend(params)
            "set_alert" -> setAlert(params)
            "list_alerts" -> listAlerts()
            "remove_alert" -> removeAlert(params)
            "best_time_to_sell" -> bestTimeToSell(params)
            "record_price" -> recordPrice(params)
            "sync_prices" -> syncPrices(params)
            "list_products" -> listProducts()
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    // ──────────────────────────────────────────────
    // ACTION: check_price — Check wholesale/market price
    // ──────────────────────────────────────────────

    private fun checkPrice(params: Map<String, String>): ToolResult {
        val rawProduct = params["product"]
            ?: return ToolResult.error(name, "Product name required", "MISSING_PRODUCT")
        val product = normalizeProduct(rawProduct)
        val market = params["market"]
        val region = params["region"]
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val db = getDb()
        val prices = queryLatestPrices(db, product, market, region)

        if (prices.isEmpty()) {
            return ToolResult.success(
                name, mapOf("product" to product, "found" to false),
                if (voice) "Sikujua bei ya $product sokoni kwa sasa. Rekodda bei au subiri sync."
                else "No price data for $product. Record a price observation or sync."
            )
        }

        val avgPrice = prices.map { it.price }.average()
        val minPrice = prices.minOf { it.priceMin ?: it.price }
        val maxPrice = prices.maxOf { it.priceMax ?: it.price }
        val unit = prices.first().unit
        val marketNames = prices.map { it.market }.distinct().joinToString(", ")
        val dataAge = System.currentTimeMillis() - prices.maxOf { it.recordedAt }
        val isStale = dataAge > STALE_THRESHOLD_MS

        val message = if (voice) {
            buildString {
                append("Bei ya ${product} sokoni:\n")
                append("• Wastani: KES ${formatPrice(avgPrice)} kwa $unit\n")
                append("• Chini: KES ${formatPrice(minPrice)} | Juu: KES ${formatPrice(maxPrice)}\n")
                append("• Masoko: $marketNames")
                if (isStale) append("\n⚠️ Bei hii ni ya zamani — thibitisha kabla ya kuuza.")
            }
        } else {
            buildString {
                append("$product wholesale prices:\n")
                append("• Average: KES ${formatPrice(avgPrice)}/$unit\n")
                append("• Range: KES ${formatPrice(minPrice)}-${formatPrice(maxPrice)}/$unit\n")
                append("• Markets: $marketNames")
                if (isStale) append("\n⚠️ Data may be outdated")
            }
        }

        // Check alerts
        checkAlerts(db, product, avgPrice)

        return ToolResult.success(
            name,
            mapOf(
                "product" to product, "avg_price" to avgPrice,
                "min_price" to minPrice, "max_price" to maxPrice,
                "unit" to unit, "markets" to marketNames, "is_stale" to isStale,
                "found" to true
            ),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: compare_markets — Compare prices across markets
    // ──────────────────────────────────────────────

    private fun compareMarkets(params: Map<String, String>): ToolResult {
        val rawProduct = params["product"]
            ?: return ToolResult.error(name, "Product name required", "MISSING_PRODUCT")
        val product = normalizeProduct(rawProduct)
        val region = params["region"]
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val db = getDb()
        val prices = queryLatestPrices(db, product, market = null, region = region)

        if (prices.isEmpty()) {
            return ToolResult.success(
                name, mapOf("product" to product, "comparisons" to emptyList<Any>()),
                if (voice) "Hakuna data ya bei ya $product masoko mbalimbali."
                else "No price data for $product across markets."
            )
        }

        val byMarket = prices.groupBy { it.market }
            .map { (market, entries) ->
                MarketPriceInfo(
                    market = market,
                    marketType = entries.first().marketType,
                    region = entries.first().region,
                    price = entries.map { it.price }.average(),
                    priceMin = entries.minOf { it.priceMin ?: it.price },
                    priceMax = entries.maxOf { it.priceMax ?: it.price },
                    unit = entries.first().unit
                )
            }
            .sortedByDescending { it.price } // Highest price first = best for seller

        val best = byMarket.first()
        val worst = byMarket.last()
        val spreadPct = if (worst.price > 0) ((best.price - worst.price) / worst.price * 100) else 0.0

        val unit = best.unit

        val message = if (voice) {
            buildString {
                append("Bei ya $product masoko mbalimbali:\n")
                byMarket.forEach { m ->
                    val type = MARKET_TYPES[m.marketType] ?: m.marketType
                    append("• ${m.market} ($type): KES ${formatPrice(m.price)} kwa $unit\n")
                }
                if (byMarket.size > 1) {
                    append("\n🥇 Soko bora: ${best.market} (KES ${formatPrice(best.price)})")
                    append("\n🥈 Soko la chini: ${worst.market} (KES ${formatPrice(worst.price)})")
                    if (spreadPct > 10) {
                        append("\n💰 Tofauti: ${spreadPct.toInt()}% — uza hapa ${best.market}!")
                    }
                }
            }
        } else {
            buildString {
                append("$product price comparison:\n")
                byMarket.forEach { m ->
                    append("• ${m.market} (${m.marketType}): KES ${formatPrice(m.price)}/$unit\n")
                }
                if (byMarket.size > 1) {
                    append("\nBest: ${best.market} @ KES ${formatPrice(best.price)}/$unit")
                    append("\nSpread: ${String.format("%.1f", spreadPct)}%")
                }
            }
        }

        return ToolResult.success(
            name,
            mapOf(
                "product" to product,
                "comparisons" to byMarket.map { mapOf("market" to it.market, "price" to it.price, "type" to it.marketType) },
                "best_market" to best.market, "best_price" to best.price,
                "spread_pct" to spreadPct
            ),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: price_trend — Show price trends over time
    // ──────────────────────────────────────────────

    private fun priceTrend(params: Map<String, String>): ToolResult {
        val rawProduct = params["product"]
            ?: return ToolResult.error(name, "Product name required", "MISSING_PRODUCT")
        val product = normalizeProduct(rawProduct)
        val period = params["period"] ?: "month"
        val market = params["market"]
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val db = getDb()
        val now = System.currentTimeMillis()
        val cutoff = when (period.lowercase()) {
            "week" -> now - 7 * 24 * 60 * 60 * 1000L
            "month" -> now - 30 * 24 * 60 * 60 * 1000L
            "season" -> now - 90 * 24 * 60 * 60 * 1000L
            else -> now - 30 * 24 * 60 * 60 * 1000L
        }

        val selection = StringBuilder("product = ? AND recorded_at >= ?")
        val args = mutableListOf(product, cutoff.toString())
        market?.let { selection.append(" AND market = ?"); args.add(it) }

        val prices = mutableListOf<Triple<Long, Double, String>>() // timestamp, price, market
        val cursor = db.query(
            TABLE_PRICES, arrayOf("recorded_at", "price", "market"),
            selection.toString(), args.toTypedArray(),
            null, null, "recorded_at ASC"
        )
        cursor.use {
            while (it.moveToNext()) {
                prices.add(Triple(it.getLong(0), it.getDouble(1), it.getString(2)))
            }
        }

        if (prices.size < 2) {
            return ToolResult.success(
                name, mapOf("product" to product, "trend" to null, "data_points" to prices.size),
                if (voice) "Hakuna data ya kutosha ya bei ya $product kwa $period."
                else "Insufficient price data for $product over $period."
            )
        }

        val oldest = prices.first().second
        val newest = prices.last().second
        val change = newest - oldest
        val changePct = if (oldest > 0) (change / oldest * 100) else 0.0
        val direction = when {
            changePct > SIGNIFICANT_CHANGE_PCT -> "inapanda ↑"
            changePct < -SIGNIFICANT_CHANGE_PCT -> "inashuka ↓"
            else -> "imara →"
        }

        // Weekly averages for longer periods
        val weeklyAvgs = if (prices.size >= 7) {
            prices.chunked(7).map { chunk ->
                chunk.map { it.second }.average()
            }
        } else emptyList()

        val unit = "kg" // Default; would come from DB in full implementation

        val message = if (voice) {
            buildString {
                append("📈 Mwelekeo wa bei ya $product ($period):\n")
                append("• Awali: KES ${formatPrice(oldest)} kwa $unit\n")
                append("• Sasa: KES ${formatPrice(newest)} kwa $unit\n")
                append("• Mwelekeo: $direction (${changePct.toInt()}%)\n")
                when {
                    changePct > 20 -> append("\n💡 Bei imepanda sana! Huenda ukafaa kuuza sasa.")
                    changePct > 10 -> append("\n💡 Bei inapanda. Subiri kidogo au uza sasa.")
                    changePct < -20 -> append("\n⚠️ Bei imeshuka sana. Huenda ukafaa kusubiri.")
                    changePct < -10 -> append("\n⚠️ Bei inashuka. Fikiria kuuza haraka.")
                    else -> append("\n💡 Bei ni imara. Uza wakati uko tayari.")
                }
            }
        } else {
            buildString {
                append("$product price trend ($period, ${prices.size} data points):\n")
                append("• Start: KES ${formatPrice(oldest)}/$unit\n")
                append("• Current: KES ${formatPrice(newest)}/$unit\n")
                append("• Change: KES ${formatPrice(change)} (${changePct.toInt()}%) $direction")
                if (weeklyAvgs.isNotEmpty()) {
                    append("\n• Weekly averages: ${weeklyAvgs.joinToString(", ") { "KES ${formatPrice(it)}" }}")
                }
            }
        }

        return ToolResult.success(
            name,
            mapOf(
                "product" to product, "period" to period,
                "oldest_price" to oldest, "newest_price" to newest,
                "change_pct" to changePct, "direction" to direction,
                "data_points" to prices.size
            ),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: set_alert — Set price alert
    // ──────────────────────────────────────────────

    private fun setAlert(params: Map<String, String>): ToolResult {
        val rawProduct = params["product"]
            ?: return ToolResult.error(name, "Product name required", "MISSING_PRODUCT")
        val product = normalizeProduct(rawProduct)
        val market = params["market"]
        val alertPrice = params["alert_price"]?.toDoubleOrNull()
            ?: return ToolResult.error(name, "Alert price required (KES)", "MISSING_ALERT_PRICE")
        val direction = params["alert_direction"] ?: "above"
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val db = getDb()
        val values = ContentValues().apply {
            put("product", product)
            put("market", market)
            put("alert_price", alertPrice)
            put("direction", direction)
            put("is_active", 1)
            put("created_at", System.currentTimeMillis())
        }
        val id = db.insert(TABLE_ALERTS, null, values)

        val dirText = if (direction == "above") "ikipanda juu ya" else "ikishuka chini ya"
        val message = if (voice) {
            "🔔 Arifa imewekwa: $product $dirText KES ${formatPrice(alertPrice)}. Utaarifiwa bei ikifika hivyo."
        } else {
            "Alert set: $product $direction KES ${formatPrice(alertPrice)}"
        }

        return ToolResult.success(
            name,
            mapOf("alert_id" to id, "product" to product, "price" to alertPrice, "direction" to direction),
            message
        )
    }

    private fun listAlerts(): ToolResult {
        val db = getDb()
        val alerts = mutableListOf<Map<String, Any?>>()
        val cursor = db.query(TABLE_ALERTS, null, "is_active = 1", null, null, null, "created_at DESC")
        cursor.use {
            while (it.moveToNext()) {
                alerts.add(mapOf(
                    "id" to it.getLong(it.getColumnIndexOrThrow("id")),
                    "product" to it.getString(it.getColumnIndexOrThrow("product")),
                    "market" to it.getString(it.getColumnIndexOrThrow("market")),
                    "alert_price" to it.getDouble(it.getColumnIndexOrThrow("alert_price")),
                    "direction" to it.getString(it.getColumnIndexOrThrow("direction"))
                ))
            }
        }

        if (alerts.isEmpty()) {
            return ToolResult.success(name, mapOf("alerts" to emptyList<Any>()), "Hakuna arifa za bei. Tumia set_alert kuunda.")
        }

        val message = buildString {
            append("🔔 Arifa za bei (${alerts.size}):\n")
            alerts.forEach { a ->
                val dir = if (a["direction"] == "above") "↑" else "↓"
                append("• ${a["product"]}: $dir KES ${formatPrice(a["alert_price"] as Double)}")
                (a["market"] as? String)?.let { append(" ($it)") }
                append("\n")
            }
        }

        return ToolResult.success(name, mapOf("alerts" to alerts), message)
    }

    private fun removeAlert(params: Map<String, String>): ToolResult {
        val alertId = params["alert_id"]?.toLongOrNull()
            ?: return ToolResult.error(name, "alert_id required", "MISSING_ALERT_ID")
        val db = getDb()
        val deleted = db.delete(TABLE_ALERTS, "id = ?", arrayOf(alertId.toString()))
        return if (deleted > 0) {
            ToolResult.success(name, mapOf("removed" to true), "Arifa ya bei imeondolewa.")
        } else {
            ToolResult.error(name, "Alert ID $alertId not found", "ALERT_NOT_FOUND")
        }
    }

    // ──────────────────────────────────────────────
    // ACTION: best_time_to_sell — When should I sell?
    // ──────────────────────────────────────────────

    private fun bestTimeToSell(params: Map<String, String>): ToolResult {
        val rawProduct = params["product"]
            ?: return ToolResult.error(name, "Product name required", "MISSING_PRODUCT")
        val product = normalizeProduct(rawProduct)
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val db = getDb()
        val now = System.currentTimeMillis()
        val yearAgo = now - 365L * 24 * 60 * 60 * 1000L

        // Get monthly price averages for the past year
        val cursor = db.rawQuery("""
            SELECT strftime('%m', recorded_at / 1000, 'unixepoch') as month,
                   AVG(price) as avg_price, COUNT(*) as data_points
            FROM $TABLE_PRICES
            WHERE product = ? AND recorded_at >= ?
            GROUP BY month
            ORDER BY avg_price DESC
        """.trimIndent(), arrayOf(product, yearAgo.toString()))

        val monthlyPrices = mutableListOf<Pair<Int, Double>>() // month, avg_price
        cursor.use {
            while (it.moveToNext()) {
                monthlyPrices.add(Pair(it.getInt(0), it.getDouble(1)))
            }
        }

        if (monthlyPrices.isEmpty()) {
            return ToolResult.success(
                name, mapOf("product" to product, "advice" to null),
                if (voice) "Hakuna data ya bei ya $product ya kutoa ushauri. Rekodda bei zaidi."
                else "Insufficient price history for $product selling advice."
            )
        }

        val bestMonth = monthlyPrices.first()
        val worstMonth = monthlyPrices.last()
        val currentMonth = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1
        val currentPrice = monthlyPrices.find { it.first == currentMonth }?.second

        val swahiliMonths = arrayOf("", "Januari", "Februari", "Machi", "Aprili", "Mei", "Juni",
            "Julai", "Agosti", "Septemba", "Oktoba", "Novemba", "Desemba")

        val message = if (voice) {
            buildString {
                append("📅 Ushauri wa kuuza $product:\n\n")
                append("🟢 Mwezi bora wa kuuza: ${swahiliMonths[bestMonth.first]}")
                append(" (wastani: KES ${formatPrice(bestMonth.second)}/kg)\n")
                append("🔴 Mwezi mbaya: ${swahiliMonths[worstMonth.first]}")
                append(" (wastani: KES ${formatPrice(worstMonth.second)}/kg)\n")

                currentPrice?.let {
                    append("\n📍 Bei ya sasa: KES ${formatPrice(it)}/kg\n")
                    val diffPct = ((it - bestMonth.second) / bestMonth.second * 100).toInt()
                    when {
                        diffPct > -10 -> append("💡 Bei ni nzuri karibu na bora! Fikiria kuuza sasa.")
                        diffPct > -30 -> append("💡 Bei ni wastani. Unaweza kusubiri au kuuza.")
                        else -> append("⚠️ Bei ni ya chini. Ikiwezekana, subiri ${swahiliMonths[bestMonth.first]}.")
                    }
                }
                if (currentPrice == null) {
                    append("\n💡 Jaribu kuuza mwezi ${swahiliMonths[bestMonth.first]} kwa bei bora.")
                }
            }
        } else {
            buildString {
                append("$product selling advice:\n")
                append("• Best month: ${swahiliMonths[bestMonth.first]} (avg KES ${formatPrice(bestMonth.second)}/kg)\n")
                append("• Worst month: ${swahiliMonths[worstMonth.first]} (avg KES ${formatPrice(worstMonth.second)}/kg)\n")
                currentPrice?.let { append("• Current price: KES ${formatPrice(it)}/kg\n") }
                append("• Price spread: ${((bestMonth.second - worstMonth.second) / worstMonth.second * 100).toInt()}%")
            }
        }

        return ToolResult.success(
            name,
            mapOf(
                "product" to product,
                "best_month" to bestMonth.first, "best_price" to bestMonth.second,
                "worst_month" to worstMonth.first, "worst_price" to worstMonth.second,
                "current_month" to currentMonth, "current_price" to currentPrice,
                "monthly_data" to monthlyPrices.map { mapOf("month" to it.first, "avg_price" to it.second) }
            ),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: record_price — Record a price observation
    // ──────────────────────────────────────────────

    private fun recordPrice(params: Map<String, String>): ToolResult {
        val rawProduct = params["product"]
            ?: return ToolResult.error(name, "Product name required", "MISSING_PRODUCT")
        val product = normalizeProduct(rawProduct)
        val market = params["market"]
            ?: return ToolResult.error(name, "Market name required", "MISSING_MARKET")
        val price = params["price"]?.toDoubleOrNull()
            ?: return ToolResult.error(name, "Price required (KES)", "MISSING_PRICE")
        val unit = params["unit"]?.let { unitMap[it.lowercase()] ?: it } ?: "kg"
        val notes = params["notes"]
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val now = System.currentTimeMillis()

        val db = getDb()

        // Insert into observations
        val obsValues = ContentValues().apply {
            put("product", product)
            put("market", market)
            put("price", price)
            put("unit", unit)
            put("observer_notes", notes)
            put("recorded_at", now)
        }
        db.insert(TABLE_OBSERVATIONS, null, obsValues)

        // Also insert into main prices table
        val priceValues = ContentValues().apply {
            put("product", product)
            put("market", market)
            put("market_type", "observation")
            put("region", params["region"] ?: "unknown")
            put("price", price)
            put("price_min", price)
            put("price_max", price)
            put("unit", unit)
            put("source", "producer_observation")
            put("recorded_at", now)
            put("synced_at", now)
        }
        db.insertWithOnConflict(TABLE_PRICES, null, priceValues, SQLiteDatabase.CONFLICT_REPLACE)

        Timber.d("Recorded price observation: $product @ $market = KES $price/$unit")

        val message = if (voice) {
            "✅ Bei yamerekodwa: $product hapa $market — KES ${formatPrice(price)} kwa $unit. Asante kwa kushiriki!"
        } else {
            "Price recorded: $product @ $market = KES ${formatPrice(price)}/$unit"
        }

        return ToolResult.success(
            name,
            mapOf("product" to product, "market" to market, "price" to price, "unit" to unit),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: sync_prices — Sync from backend
    // ──────────────────────────────────────────────

    private fun syncPrices(params: Map<String, String>): ToolResult {
        val product = params["product"]?.let { normalizeProduct(it) }
        val db = getDb()
        val now = System.currentTimeMillis()
        val syncKey = "${product ?: "all"}_produce"

        val values = ContentValues().apply {
            put("sync_key", syncKey)
            put("last_sync_at", now)
            put("last_sync_status", "attempted")
        }
        db.insertWithOnConflict(TABLE_SYNC_META, null, values, SQLiteDatabase.CONFLICT_REPLACE)

        val count = db.rawQuery("SELECT COUNT(*) FROM $TABLE_PRICES${product?.let { " WHERE product = '$it'" } ?: ""}", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

        return ToolResult.success(
            name,
            mapOf("cached_prices" to count, "needs_network" to true),
            "🔄 Sync requested. $count cached prices available offline."
        )
    }

    private fun listProducts(): ToolResult {
        val db = getDb()
        val products = mutableSetOf<String>()
        val cursor = db.query(true, TABLE_PRICES, arrayOf("product"), null, null, null, null, "product ASC", null)
        cursor.use { while (it.moveToNext()) products.add(it.getString(0)) }

        if (products.isEmpty()) {
            return ToolResult.success(name, mapOf("products" to emptyList<Any>()), "Hakuna bidhaa. Rekodda bei au subiri sync.")
        }

        return ToolResult.success(
            name,
            mapOf("products" to products.toList(), "count" to products.size),
            "Bidhaa ${products.size} zinapatikana:\n${products.joinToString("\n") { "• $it" }}"
        )
    }

    // ──────────────────────────────────────────────
    // Internal Helpers
    // ──────────────────────────────────────────────

    private fun queryLatestPrices(db: SQLiteDatabase, product: String, market: String?, region: String?): List<PriceEntry> {
        val results = mutableListOf<PriceEntry>()
        val selection = StringBuilder("product = ?")
        val args = mutableListOf(product)
        market?.let { selection.append(" AND market = ?"); args.add(it) }
        region?.let { selection.append(" AND region = ?"); args.add(it) }

        val weekAgo = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
        selection.append(" AND recorded_at >= ?")
        args.add(weekAgo.toString())

        val cursor = db.query(TABLE_PRICES, null, selection.toString(), args.toTypedArray(), null, null, "recorded_at DESC")
        val seenMarkets = mutableSetOf<String>()
        cursor.use {
            while (it.moveToNext()) {
                val mkt = it.getString(it.getColumnIndexOrThrow("market"))
                if (mkt !in seenMarkets) {
                    seenMarkets.add(mkt)
                    results.add(PriceEntry(
                        market = mkt,
                        marketType = it.getString(it.getColumnIndexOrThrow("market_type")),
                        region = it.getString(it.getColumnIndexOrThrow("region")),
                        price = it.getDouble(it.getColumnIndexOrThrow("price")),
                        priceMin = it.getDouble(it.getColumnIndexOrThrow("price_min")),
                        priceMax = it.getDouble(it.getColumnIndexOrThrow("price_max")),
                        unit = it.getString(it.getColumnIndexOrThrow("unit")),
                        recordedAt = it.getLong(it.getColumnIndexOrThrow("recorded_at"))
                    ))
                }
            }
        }
        return results
    }

    private fun checkAlerts(db: SQLiteDatabase, product: String, currentPrice: Double) {
        val cursor = db.query(
            TABLE_ALERTS, null, "product = ? AND is_active = 1", arrayOf(product), null, null, null
        )
        cursor.use {
            while (it.moveToNext()) {
                val alertPrice = it.getDouble(it.getColumnIndexOrThrow("alert_price"))
                val direction = it.getString(it.getColumnIndexOrThrow("direction"))
                val triggered = when (direction) {
                    "above" -> currentPrice >= alertPrice
                    "below" -> currentPrice <= alertPrice
                    else -> false
                }
                if (triggered) {
                    val alertId = it.getLong(it.getColumnIndexOrThrow("id"))
                    val updateValues = ContentValues().apply { put("last_triggered_at", System.currentTimeMillis()) }
                    db.update(TABLE_ALERTS, updateValues, "id = ?", arrayOf(alertId.toString()))
                }
            }
        }
    }

    private fun normalizeProduct(raw: String): String {
        return productAliases[raw.trim().lowercase()] ?: raw.trim().lowercase()
    }

    private fun formatPrice(price: Double): String {
        return if (price == price.toLong().toDouble()) "%,.0f".format(price) else "%,.1f".format(price)
    }

    // ──────────────────────────────────────────────
    // Data Classes
    // ──────────────────────────────────────────────

    data class PriceEntry(
        val market: String,
        val marketType: String,
        val region: String,
        val price: Double,
        val priceMin: Double?,
        val priceMax: Double?,
        val unit: String,
        val recordedAt: Long
    )

    data class MarketPriceInfo(
        val market: String,
        val marketType: String,
        val region: String,
        val price: Double,
        val priceMin: Double,
        val priceMax: Double,
        val unit: String
    )
}
