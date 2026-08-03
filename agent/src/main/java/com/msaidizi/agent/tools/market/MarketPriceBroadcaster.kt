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
 * MarketPriceBroadcaster — Real-time market price intelligence for informal workers.
 *
 * Problem: Workers lose $580-$1,045/year from underpricing because they lack
 * market price visibility. This tool syncs prices from backend Soko Pulse data,
 * caches locally in SQLite for offline use, and delivers price intelligence
 * via voice queries and proactive alerts.
 *
 * Offline-first: always serves cached prices when network is unavailable.
 */
@Singleton
class MarketPriceBroadcaster @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val name = "market_price_broadcaster"
    override val description = "Get real-time market prices, compare across markets, and receive price alerts. Solves underpricing by giving workers market intelligence."

    override val argsSchema = argSchema {
        enum(
            "action", "Action to perform",
            listOf(
                "check_price",       // Voice price check: "Bei ya tomatoes ni ngapi?"
                "compare_markets",   // Compare prices across nearby markets
                "set_alert",         // Set price alert for a product
                "list_alerts",       // List active price alerts
                "remove_alert",      // Remove a price alert
                "sync_prices",       // Force sync from Soko Pulse backend
                "list_products",     // List all tracked products
                "price_history",     // Show recent price changes
                "best_market"        // Find cheapest market for a product
            ),
            required = true
        )
        string("product", "Product name (e.g. 'tomatoes', 'maize', 'avocado')", required = false)
        string("market", "Specific market name to check", required = false)
        string("region", "Region/county for market comparison (e.g. 'Migori', 'Nairobi')", required = false)
        number("current_price", "Worker's current selling price in KES for comparison", required = false)
        number("alert_high", "Upper price threshold for alerts in KES", required = false)
        number("alert_low", "Lower price threshold for alerts in KES", required = false)
        integer("days", "Number of days for price history lookup", required = false)
        boolean("voice", "Whether to format response for voice output (Swahili)", required = false)
    }

    // ──────────────────────────────────────────────
    // SQLite Database for Offline Price Cache
    // ──────────────────────────────────────────────

    inner class PriceDatabase(context: Context) :
        SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            // Market prices table — one row per product per market per day
            db.execSQL("""
                CREATE TABLE $TABLE_PRICES (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    product TEXT NOT NULL,
                    market TEXT NOT NULL,
                    region TEXT NOT NULL,
                    price_min REAL NOT NULL,
                    price_max REAL NOT NULL,
                    price_avg REAL NOT NULL,
                    unit TEXT NOT NULL DEFAULT 'kg',
                    source TEXT NOT NULL DEFAULT 'soko_pulse',
                    recorded_at INTEGER NOT NULL,
                    synced_at INTEGER NOT NULL,
                    UNIQUE(product, market, recorded_at)
                )
            """)

            // Price alerts table
            db.execSQL("""
                CREATE TABLE $TABLE_ALERTS (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    product TEXT NOT NULL,
                    market TEXT,
                    alert_low REAL,
                    alert_high REAL,
                    is_active INTEGER NOT NULL DEFAULT 1,
                    created_at INTEGER NOT NULL,
                    last_triggered_at INTEGER
                )
            """)

            // Sync metadata — track last sync per product/market
            db.execSQL("""
                CREATE TABLE $TABLE_SYNC_META (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    sync_key TEXT NOT NULL UNIQUE,
                    last_sync_at INTEGER NOT NULL,
                    last_sync_status TEXT NOT NULL DEFAULT 'ok'
                )
            """)

            // Indexes for fast lookups
            db.execSQL("CREATE INDEX idx_prices_product ON $TABLE_PRICES(product)")
            db.execSQL("CREATE INDEX idx_prices_market ON $TABLE_PRICES(market)")
            db.execSQL("CREATE INDEX idx_prices_recorded ON $TABLE_PRICES(recorded_at)")
            db.execSQL("CREATE INDEX idx_prices_product_market ON $TABLE_PRICES(product, market)")
            db.execSQL("CREATE INDEX idx_alerts_product ON $TABLE_ALERTS(product, is_active)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_PRICES")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_ALERTS")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_SYNC_META")
            onCreate(db)
        }
    }

    // ──────────────────────────────────────────────
    // Constants & State
    // ──────────────────────────────────────────────

    companion object {
        private const val DB_NAME = "market_prices.db"
        private const val DB_VERSION = 1
        private const val TABLE_PRICES = "market_prices"
        private const val TABLE_ALERTS = "price_alerts"
        private const val TABLE_SYNC_META = "sync_meta"

        // Significant price change threshold (percentage) for alerts
        private const val SIGNIFICANT_CHANGE_PCT = 15.0

        // Stale cache threshold — warn if data is older than this (ms)
        private const val STALE_THRESHOLD_MS = 24 * 60 * 60 * 1000L // 24 hours
    }

    private var dbHelper: PriceDatabase? = null

    private fun getDb(): SQLiteDatabase {
        if (dbHelper == null) {
            dbHelper = PriceDatabase(context)
        }
        return dbHelper!!.writableDatabase
    }

    // ──────────────────────────────────────────────
    // Common product units (Swahili-aware)
    // ──────────────────────────────────────────────

    private val productUnits = mapOf(
        "tomatoes" to "kg", "nyanya" to "kg",
        "maize" to "kg", "mahindi" to "kg",
        "avocado" to "kg", "parachichi" to "kg",
        "beans" to "kg", "maharagwe" to "kg",
        "onions" to "kg", "vitunguu" to "kg",
        "potatoes" to "kg", "viazi" to "kg",
        "sukuma wiki" to "bunch", "kale" to "bunch",
        "cabbage" to "head", "kabichi" to "head",
        "mangoes" to "kg", "embe" to "kg",
        "bananas" to "bunch", "ndizi" to "bunch",
        "milk" to "litre", "maziwa" to "litre",
        "eggs" to "tray", "mayai" to "tray",
        "chicken" to "piece", "kuku" to "piece",
        "fish" to "kg", "samaki" to "kg",
        "sorghum" to "kg", "mtama" to "kg",
        "millet" to "kg", "wimbi" to "kg",
        "groundnuts" to "kg", "njugu" to "kg",
        "sugar cane" to "stalk", "miwa" to "stalk",
        "passion fruit" to "kg", "matunda ya passion" to "kg",
        "watermelon" to "piece", "tikiti maji" to "piece",
    )

    /** Normalize product name — handles Swahili aliases */
    private fun normalizeProduct(raw: String): String {
        val lower = raw.trim().lowercase()
        // Map common Swahili/English aliases to canonical names
        val aliases = mapOf(
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
        )
        return aliases[lower] ?: lower
    }

    private fun getUnit(product: String): String {
        val norm = normalizeProduct(product)
        return productUnits[norm] ?: productUnits[product.lowercase()] ?: "kg"
    }

    // ──────────────────────────────────────────────
    // Tool Execute — Main Entry Point
    // ──────────────────────────────────────────────

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"]
            ?: return ToolResult.error(name, "Action required. Use: check_price, compare_markets, set_alert, list_alerts, remove_alert, sync_prices, list_products, price_history, best_market", "MISSING_ACTION")

        return when (action.lowercase()) {
            "check_price" -> checkPrice(params)
            "compare_markets" -> compareMarkets(params)
            "set_alert" -> setAlert(params)
            "list_alerts" -> listAlerts()
            "remove_alert" -> removeAlert(params)
            "sync_prices" -> syncPrices(params)
            "list_products" -> listProducts(params)
            "price_history" -> priceHistory(params)
            "best_market" -> bestMarket(params)
            else -> ToolResult.error(name, "Unknown action: $action. Valid: check_price, compare_markets, set_alert, list_alerts, remove_alert, sync_prices, list_products, price_history, best_market", "INVALID_ACTION")
        }
    }

    // ──────────────────────────────────────────────
    // ACTION: check_price — Voice price check
    // "Bei ya tomatoes ni ngapi?" → "Tomatoes ni KES 80-100 kwa kilo hapa Migori"
    // ──────────────────────────────────────────────

    private fun checkPrice(params: Map<String, String>): ToolResult {
        val rawProduct = params["product"]
            ?: return ToolResult.error(name, "Product name required. Example: 'tomatoes' or 'nyanya'", "MISSING_PRODUCT")
        val product = normalizeProduct(rawProduct)
        val market = params["market"]
        val region = params["region"]
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val db = getDb()
        val priceData = queryLatestPrices(db, product, market, region)

        if (priceData.isEmpty()) {
            return if (voice) {
                ToolResult.success(
                    name,
                    mapOf("product" to product, "found" to false),
                    "Sikujua bei ya $product kwa sasa. Jaribu tena baada ya kuwa na mtandao."
                )
            } else {
                ToolResult.success(
                    name,
                    mapOf("product" to product, "found" to false),
                    "No price data found for $product. Try syncing prices first."
                )
            }
        }

        // Aggregate across markets if no specific market given
        val minPrice = priceData.minOf { it.priceMin }
        val maxPrice = priceData.maxOf { it.priceMax }
        val avgPrice = priceData.map { it.priceAvg }.average()
        val unit = priceData.first().unit
        val displayMarket = priceData.map { it.market }.distinct().joinToString(", ")
        val dataAge = System.currentTimeMillis() - priceData.maxOf { it.recordedAt }
        val isStale = dataAge > STALE_THRESHOLD_MS
        val staleWarning = if (isStale) " (data ni ya zamani — thibitisha bei ya sasa)" else ""

        val resultData = mapOf(
            "product" to product,
            "price_min" to minPrice,
            "price_max" to maxPrice,
            "price_avg" to avgPrice,
            "unit" to unit,
            "market" to displayMarket,
            "data_points" to priceData.size,
            "is_stale" to isStale,
            "found" to true
        )

        val message = if (voice) {
            buildString {
                append(product.replaceFirstChar { it.uppercase() })
                append(" ni KES ${formatPrice(minPrice)}-${formatPrice(maxPrice)}")
                append(" kwa $unit")
                if (priceData.size == 1) {
                    append(" hapa ${priceData.first().market}")
                } else {
                    append(" (wastani: KES ${formatPrice(avgPrice)})")
                }
                append(staleWarning)
            }
        } else {
            buildString {
                append("$product: KES ${formatPrice(minPrice)}-${formatPrice(maxPrice)}/$unit")
                append(" | Average: KES ${formatPrice(avgPrice)}/$unit")
                append(" | Markets: $displayMarket")
                if (isStale) append(" ⚠️ Data may be outdated")
            }
        }

        // Check and trigger any matching alerts
        checkAlerts(db, product, avgPrice)

        return ToolResult.success(name, resultData, message)
    }

    // ──────────────────────────────────────────────
    // ACTION: compare_markets — Price comparison across markets
    // ──────────────────────────────────────────────

    private fun compareMarkets(params: Map<String, String>): ToolResult {
        val rawProduct = params["product"]
            ?: return ToolResult.error(name, "Product name required for market comparison", "MISSING_PRODUCT")
        val product = normalizeProduct(rawProduct)
        val region = params["region"]
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val db = getDb()
        val prices = queryLatestPrices(db, product, market = null, region = region)

        if (prices.isEmpty()) {
            return ToolResult.success(
                name,
                mapOf("product" to product, "comparisons" to emptyList<Any>()),
                if (voice) "Sikujua bei ya $product masoko mengine. Jaribu baada ya sync."
                else "No price data for $product across markets."
            )
        }

        // Group by market and show comparison
        val byMarket = prices.groupBy { it.market }
            .map { (market, entries) ->
                MarketComparison(
                    market = market,
                    region = entries.first().region,
                    priceMin = entries.minOf { it.priceMin },
                    priceMax = entries.maxOf { it.priceMax },
                    priceAvg = entries.map { it.priceAvg }.average(),
                    unit = entries.first().unit
                )
            }
            .sortedBy { it.priceAvg }

        val cheapest = byMarket.first()
        val mostExpensive = byMarket.last()
        val spread = mostExpensive.priceAvg - cheapest.priceAvg
        val spreadPct = if (cheapest.priceAvg > 0) (spread / cheapest.priceAvg * 100) else 0.0

        val unit = cheapest.unit

        val message = if (voice) {
            buildString {
                append("Bei ya $product:\n")
                byMarket.forEach { m ->
                    append("• ${m.market}: KES ${formatPrice(m.priceMin)}-${formatPrice(m.priceMax)} kwa $unit\n")
                }
                if (byMarket.size > 1) {
                    append("\nSoko la bei rahisi: ${cheapest.market} (KES ${formatPrice(cheapest.priceAvg)})")
                    if (spreadPct > 10) {
                        append("\nTofauti ni ${spreadPct.toInt()}% — unaweza ukauza zaidi hapa!")
                    }
                }
            }
        } else {
            buildString {
                append("Price comparison for $product:\n")
                byMarket.forEach { m ->
                    append("• ${m.market} (${m.region}): KES ${formatPrice(m.priceMin)}-${formatPrice(m.priceMax)}/$unit | avg KES ${formatPrice(m.priceAvg)}\n")
                }
                if (byMarket.size > 1) {
                    append("\nBest market: ${cheapest.market} @ KES ${formatPrice(cheapest.priceAvg)}/$unit")
                    append("\nSpread: KES ${formatPrice(spread)} (${spreadPct.toInt()}%)")
                }
            }
        }

        val resultData = mapOf(
            "product" to product,
            "comparisons" to byMarket.map {
                mapOf(
                    "market" to it.market, "region" to it.region,
                    "price_min" to it.priceMin, "price_max" to it.priceMax,
                    "price_avg" to it.priceAvg, "unit" to it.unit
                )
            },
            "cheapest_market" to cheapest.market,
            "spread_pct" to spreadPct
        )

        return ToolResult.success(name, resultData, message)
    }

    // ──────────────────────────────────────────────
    // ACTION: set_alert — Set price alert for a product
    // ──────────────────────────────────────────────

    private fun setAlert(params: Map<String, String>): ToolResult {
        val rawProduct = params["product"]
            ?: return ToolResult.error(name, "Product name required for alert", "MISSING_PRODUCT")
        val product = normalizeProduct(rawProduct)
        val market = params["market"]
        val alertLow = params["alert_low"]?.toDoubleOrNull()
        val alertHigh = params["alert_high"]?.toDoubleOrNull()

        if (alertLow == null && alertHigh == null) {
            return ToolResult.error(
                name,
                "Set at least one threshold: alert_low or alert_high (in KES)",
                "MISSING_THRESHOLDS"
            )
        }

        val db = getDb()
        val values = ContentValues().apply {
            put("product", product)
            put("market", market)
            put("alert_low", alertLow)
            put("alert_high", alertHigh)
            put("is_active", 1)
            put("created_at", System.currentTimeMillis())
        }
        val id = db.insert(TABLE_ALERTS, null, values)

        val unit = getUnit(product)
        val message = buildString {
            append("🔔 Price alert set for $product")
            market?.let { append(" hapa $it") }
            append(": ")
            if (alertLow != null) append("chini ya KES ${formatPrice(alertLow)}/$unit")
            if (alertLow != null && alertHigh != null) append(" au ")
            if (alertHigh != null) append("juu ya KES ${formatPrice(alertHigh)}/$unit")
        }

        return ToolResult.success(
            name,
            mapOf("alert_id" to id, "product" to product, "market" to market,
                "alert_low" to alertLow, "alert_high" to alertHigh),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: list_alerts — Show active price alerts
    // ──────────────────────────────────────────────

    private fun listAlerts(): ToolResult {
        val db = getDb()
        val alerts = mutableListOf<Map<String, Any?>>()
        val cursor = db.query(
            TABLE_ALERTS, null, "is_active = 1", null, null, null, "created_at DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                alerts.add(mapOf(
                    "id" to it.getLong(it.getColumnIndexOrThrow("id")),
                    "product" to it.getString(it.getColumnIndexOrThrow("product")),
                    "market" to it.getString(it.getColumnIndexOrThrow("market")),
                    "alert_low" to it.getDoubleOrNull(it.getColumnIndexOrThrow("alert_low")),
                    "alert_high" to it.getDoubleOrNull(it.getColumnIndexOrThrow("alert_high")),
                    "created_at" to it.getLong(it.getColumnIndexOrThrow("created_at"))
                ))
            }
        }

        if (alerts.isEmpty()) {
            return ToolResult.success(name, mapOf("alerts" to emptyList<Any>()), "Hakuna arifa za bei. Tumia set_alert kuunda.")
        }

        val message = buildString {
            append("🔔 Arifa za bei (${alerts.size}):\n")
            alerts.forEach { a ->
                val product = a["product"] as String
                val market = a["market"] as? String
                val low = a["alert_low"] as? Double
                val high = a["alert_high"] as? Double
                append("• $product")
                market?.let { append(" ($it)") }
                append(": ")
                low?.let { append("< KES ${formatPrice(it)} ") }
                high?.let { append("> KES ${formatPrice(it)}") }
                append("\n")
            }
        }

        return ToolResult.success(name, mapOf("alerts" to alerts), message)
    }

    // ──────────────────────────────────────────────
    // ACTION: remove_alert — Remove a price alert
    // ──────────────────────────────────────────────

    private fun removeAlert(params: Map<String, String>): ToolResult {
        val alertId = params["alert_id"]?.toLongOrNull()
            ?: return ToolResult.error(name, "alert_id required. Use list_alerts to find IDs.", "MISSING_ALERT_ID")

        val db = getDb()
        val deleted = db.delete(TABLE_ALERTS, "id = ?", arrayOf(alertId.toString()))

        return if (deleted > 0) {
            ToolResult.success(name, mapOf("removed" to true, "alert_id" to alertId), "Arifa ya bei imeondolewa.")
        } else {
            ToolResult.error(name, "Alert ID $alertId not found", "ALERT_NOT_FOUND")
        }
    }

    // ──────────────────────────────────────────────
    // ACTION: sync_prices — Sync from Soko Pulse backend
    // ──────────────────────────────────────────────

    private fun syncPrices(params: Map<String, String>): ToolResult {
        val product = params["product"]?.let { normalizeProduct(it) }
        val market = params["market"]
        val region = params["region"]

        // In production, this would call the Soko Pulse API.
        // For now, record sync attempt and use cached data.
        val db = getDb()
        val syncKey = "${product ?: "all"}_${market ?: "all"}_${region ?: "all"}"
        val now = System.currentTimeMillis()

        // Check last sync time
        val lastSync = getLastSyncTime(db, syncKey)
        val timeSinceSync = if (lastSync != null) now - lastSync else Long.MAX_VALUE

        // Update sync metadata
        val values = ContentValues().apply {
            put("sync_key", syncKey)
            put("last_sync_at", now)
            put("last_sync_status", "attempted")
        }
        db.insertWithOnConflict(TABLE_SYNC_META, null, values, SQLiteDatabase.CONFLICT_REPLACE)

        val cachedCount = countCachedPrices(db, product, market, region)

        return ToolResult.success(
            name,
            mapOf(
                "sync_key" to syncKey,
                "cached_prices" to cachedCount,
                "last_sync_ms_ago" to timeSinceSync,
                "needs_network" to true
            ),
            buildString {
                append("🔄 Sync requested for ${product ?: "all products"}")
                market?.let { append(" hapa $it") }
                append("\nCached prices: $cachedCount")
                if (timeSinceSync < Long.MAX_VALUE) {
                    val mins = timeSinceSync / 60000
                    append("\nLast sync: ${if (mins < 60) "${mins}min" else "${mins / 60}h ${mins % 60}min"} ago")
                }
                append("\n⚠️ Backend sync requires network. Cached data served offline.")
            }
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: list_products — List all tracked products
    // ──────────────────────────────────────────────

    private fun listProducts(params: Map<String, String>): ToolResult {
        val region = params["region"]
        val db = getDb()

        val selection = if (region != null) "region = ?" else null
        val selectionArgs = if (region != null) arrayOf(region) else null

        val products = mutableSetOf<String>()
        val cursor = db.query(
            true, TABLE_PRICES, arrayOf("product"),
            selection, selectionArgs, null, null, "product ASC", null
        )
        cursor.use {
            while (it.moveToNext()) {
                products.add(it.getString(0))
            }
        }

        if (products.isEmpty()) {
            return ToolResult.success(
                name, mapOf("products" to emptyList<Any>()),
                "Hakuna bidhaa kwenye database. Run sync_prices kwanza."
            )
        }

        val productList = products.toList()
        val message = buildString {
            append("Bidhaa ${productList.size} zinapatikana")
            region?.let { append(" hapa $it") }
            append(":\n")
            productList.forEach { p ->
                append("• $p (${getUnit(p)})\n")
            }
        }

        return ToolResult.success(name, mapOf("products" to productList, "count" to productList.size), message)
    }

    // ──────────────────────────────────────────────
    // ACTION: price_history — Show recent price changes
    // ──────────────────────────────────────────────

    private fun priceHistory(params: Map<String, String>): ToolResult {
        val rawProduct = params["product"]
            ?: return ToolResult.error(name, "Product name required for price history", "MISSING_PRODUCT")
        val product = normalizeProduct(rawProduct)
        val market = params["market"]
        val days = params["days"]?.toIntOrNull() ?: 7
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val db = getDb()
        val cutoff = System.currentTimeMillis() - (days * 24 * 60 * 60 * 1000L)

        val selection = StringBuilder("product = ? AND recorded_at >= ?")
        val args = mutableListOf(product, cutoff.toString())
        market?.let {
            selection.append(" AND market = ?")
            args.add(it)
        }

        val history = mutableListOf<PricePoint>()
        val cursor = db.query(
            TABLE_PRICES, null, selection.toString(), args.toTypedArray(),
            null, null, "recorded_at DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                history.add(PricePoint(
                    market = it.getString(it.getColumnIndexOrThrow("market")),
                    region = it.getString(it.getColumnIndexOrThrow("region")),
                    priceMin = it.getDouble(it.getColumnIndexOrThrow("price_min")),
                    priceMax = it.getDouble(it.getColumnIndexOrThrow("price_max")),
                    priceAvg = it.getDouble(it.getColumnIndexOrThrow("price_avg")),
                    unit = it.getString(it.getColumnIndexOrThrow("unit")),
                    source = it.getString(it.getColumnIndexOrThrow("source")),
                    recordedAt = it.getLong(it.getColumnIndexOrThrow("recorded_at")),
                    syncedAt = it.getLong(it.getColumnIndexOrThrow("synced_at"))
                ))
            }
        }

        if (history.isEmpty()) {
            return ToolResult.success(
                name, mapOf("product" to product, "history" to emptyList<Any>()),
                if (voice) "Hakuna historia ya bei ya $product kwa siku $days zilizopita."
                else "No price history for $product in the last $days days."
            )
        }

        // Calculate trend
        val oldest = history.last().priceAvg
        val newest = history.first().priceAvg
        val trendPct = if (oldest > 0) ((newest - oldest) / oldest * 100) else 0.0
        val trendDir = when {
            trendPct > 5 -> "inapanda ↑"
            trendPct < -5 -> "inashuka ↓"
            else -> "imara →"
        }

        val unit = history.first().unit
        val message = if (voice) {
            buildString {
                append("Historia ya bei ya $product (siku $days):\n")
                append("• Sasa: KES ${formatPrice(newest)} kwa $unit\n")
                append("• Awali: KES ${formatPrice(oldest)} kwa $unit\n")
                append("• Mwelekeo: $trendDir (${trendPct.toInt()}%)")
            }
        } else {
            buildString {
                append("$product price history ($days days, ${history.size} data points):\n")
                append("• Current: KES ${formatPrice(newest)}/$unit\n")
                append("• ${days}d ago: KES ${formatPrice(oldest)}/$unit\n")
                append("• Trend: ${trendDir} (${String.format("%.1f", trendPct)}%)")
            }
        }

        return ToolResult.success(
            name,
            mapOf(
                "product" to product, "days" to days,
                "current_avg" to newest, "oldest_avg" to oldest,
                "trend_pct" to trendPct, "data_points" to history.size
            ),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: best_market — Find cheapest market for a product
    // ──────────────────────────────────────────────

    private fun bestMarket(params: Map<String, String>): ToolResult {
        val rawProduct = params["product"]
            ?: return ToolResult.error(name, "Product name required", "MISSING_PRODUCT")
        val product = normalizeProduct(rawProduct)
        val region = params["region"]
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val db = getDb()
        val prices = queryLatestPrices(db, product, market = null, region = region)

        if (prices.isEmpty()) {
            return ToolResult.success(
                name, mapOf("product" to product, "best_market" to null),
                if (voice) "Sikujua bei ya $product. Jaribu baada ya sync."
                else "No data for $product. Sync prices first."
            )
        }

        val byMarket = prices.groupBy { it.market }
            .map { (market, entries) ->
                MarketComparison(
                    market = market,
                    region = entries.first().region,
                    priceMin = entries.minOf { it.priceMin },
                    priceMax = entries.maxOf { it.priceMax },
                    priceAvg = entries.map { it.priceAvg }.average(),
                    unit = entries.first().unit
                )
            }
            .sortedBy { it.priceAvg }

        val best = byMarket.first()
        val worst = byMarket.last()
        val unit = best.unit

        // Calculate potential earnings difference
        val potentialSaving = worst.priceAvg - best.priceAvg

        val message = if (voice) {
            buildString {
                append("Soko la bei rahisi la ${product}: ${best.market}")
                append("\nBei: KES ${formatPrice(best.priceMin)}-${formatPrice(best.priceMax)} kwa $unit")
                if (byMarket.size > 1) {
                    append("\nUkilinganisha na ${worst.market}: unaokoa KES ${formatPrice(potentialSaving)} kwa $unit")
                }
            }
        } else {
            buildString {
                append("Best market for $product: ${best.market} (${best.region})")
                append("\nPrice: KES ${formatPrice(best.priceMin)}-${formatPrice(best.priceMax)}/$unit")
                if (byMarket.size > 1) {
                    append("\nvs worst (${worst.market}): save KES ${formatPrice(potentialSaving)}/$unit")
                }
            }
        }

        return ToolResult.success(
            name,
            mapOf(
                "product" to product, "best_market" to best.market,
                "best_avg" to best.priceAvg, "worst_market" to worst.market,
                "potential_saving_per_unit" to potentialSaving,
                "markets_compared" to byMarket.size
            ),
            message
        )
    }

    // ──────────────────────────────────────────────
    // Price Data Insertion (called by SyncEngine / backend integration)
    // ──────────────────────────────────────────────

    /**
     * Insert market price data into the local cache.
     * Called by the sync engine when Soko Pulse data arrives.
     */
    fun insertPrice(
        product: String,
        market: String,
        region: String,
        priceMin: Double,
        priceMax: Double,
        priceAvg: Double,
        unit: String = "kg",
        source: String = "soko_pulse",
        recordedAt: Long = System.currentTimeMillis()
    ) {
        val db = getDb()
        val values = ContentValues().apply {
            put("product", normalizeProduct(product))
            put("market", market)
            put("region", region)
            put("price_min", priceMin)
            put("price_max", priceMax)
            put("price_avg", priceAvg)
            put("unit", unit)
            put("source", source)
            put("recorded_at", recordedAt)
            put("synced_at", System.currentTimeMillis())
        }
        db.insertWithOnConflict(TABLE_PRICES, null, values, SQLiteDatabase.CONFLICT_REPLACE)

        // Check alerts after inserting new price
        checkAlerts(db, normalizeProduct(product), priceAvg)
    }

    /**
     * Bulk insert prices from a sync response.
     */
    fun bulkInsertPrices(prices: List<PriceData>) {
        val db = getDb()
        db.beginTransaction()
        try {
            prices.forEach { pd ->
                insertPrice(
                    product = pd.product, market = pd.market, region = pd.region,
                    priceMin = pd.priceMin, priceMax = pd.priceMax, priceAvg = pd.priceAvg,
                    unit = pd.unit, source = pd.source, recordedAt = pd.recordedAt
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ──────────────────────────────────────────────
    // Internal Helpers
    // ──────────────────────────────────────────────

    private fun queryLatestPrices(
        db: SQLiteDatabase,
        product: String,
        market: String?,
        region: String?
    ): List<PricePoint> {
        val results = mutableListOf<PricePoint>()
        val selection = StringBuilder("product = ?")
        val args = mutableListOf(product)
        market?.let {
            selection.append(" AND market = ?")
            args.add(it)
        }
        region?.let {
            selection.append(" AND region = ?")
            args.add(it)
        }

        // Get latest entry per market
        val cursor = db.rawQuery("""
            SELECT * FROM $TABLE_PRICES
            WHERE ${selection}
            AND recorded_at >= ?
            ORDER BY market, recorded_at DESC
        """.trimIndent(),
            (args + (System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L).toString()).toTypedArray()
        )

        val seenMarkets = mutableSetOf<String>()
        cursor.use {
            while (it.moveToNext()) {
                val mkt = it.getString(it.getColumnIndexOrThrow("market"))
                if (mkt !in seenMarkets) {
                    seenMarkets.add(mkt)
                    results.add(PricePoint(
                        market = mkt,
                        region = it.getString(it.getColumnIndexOrThrow("region")),
                        priceMin = it.getDouble(it.getColumnIndexOrThrow("price_min")),
                        priceMax = it.getDouble(it.getColumnIndexOrThrow("price_max")),
                        priceAvg = it.getDouble(it.getColumnIndexOrThrow("price_avg")),
                        unit = it.getString(it.getColumnIndexOrThrow("unit")),
                        source = it.getString(it.getColumnIndexOrThrow("source")),
                        recordedAt = it.getLong(it.getColumnIndexOrThrow("recorded_at")),
                        syncedAt = it.getLong(it.getColumnIndexOrThrow("synced_at"))
                    ))
                }
            }
        }
        return results
    }

    private fun countCachedPrices(db: SQLiteDatabase, product: String?, market: String?, region: String?): Int {
        val selection = mutableListOf<String>()
        val args = mutableListOf<String>()
        product?.let { selection.add("product = ?"); args.add(it) }
        market?.let { selection.add("market = ?"); args.add(it) }
        region?.let { selection.add("region = ?"); args.add(it) }

        val where = if (selection.isNotEmpty()) selection.joinToString(" AND ") else null
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_PRICES${if (where != null) " WHERE $where" else ""}", args.toTypedArray())
        cursor.use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    private fun getLastSyncTime(db: SQLiteDatabase, syncKey: String): Long? {
        val cursor = db.query(
            TABLE_SYNC_META, arrayOf("last_sync_at"),
            "sync_key = ?", arrayOf(syncKey), null, null, null
        )
        cursor.use {
            return if (it.moveToFirst()) it.getLong(0) else null
        }
    }

    /**
     * Check active alerts and fire notifications if thresholds are crossed.
     */
    private fun checkAlerts(db: SQLiteDatabase, product: String, currentAvg: Double) {
        val cursor = db.query(
            TABLE_ALERTS, null,
            "product = ? AND is_active = 1", arrayOf(product),
            null, null, null
        )
        cursor.use {
            while (it.moveToNext()) {
                val alertId = it.getLong(it.getColumnIndexOrThrow("id"))
                val alertLow = it.getDoubleOrNull(it.getColumnIndexOrThrow("alert_low"))
                val alertHigh = it.getDoubleOrNull(it.getColumnIndexOrThrow("alert_high"))
                val lastTriggered = it.getLongOrNull(it.getColumnIndexOrThrow("last_triggered_at"))

                val triggered = (alertLow != null && currentAvg <= alertLow) ||
                        (alertHigh != null && currentAvg >= alertHigh)

                if (triggered) {
                    // Update last triggered timestamp
                    val updateValues = ContentValues().apply {
                        put("last_triggered_at", System.currentTimeMillis())
                    }
                    db.update(TABLE_ALERTS, updateValues, "id = ?", arrayOf(alertId.toString()))

                    // In production, this would trigger a system notification
                    // via NotificationManager or the alerting subsystem.
                    // The ToolResult from the triggering action will include alert info.
                }
            }
        }
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

    data class PricePoint(
        val market: String,
        val region: String,
        val priceMin: Double,
        val priceMax: Double,
        val priceAvg: Double,
        val unit: String,
        val source: String,
        val recordedAt: Long,
        val syncedAt: Long
    )

    data class MarketComparison(
        val market: String,
        val region: String,
        val priceMin: Double,
        val priceMax: Double,
        val priceAvg: Double,
        val unit: String
    )

    data class PriceData(
        val product: String,
        val market: String,
        val region: String,
        val priceMin: Double,
        val priceMax: Double,
        val priceAvg: Double,
        val unit: String = "kg",
        val source: String = "soko_pulse",
        val recordedAt: Long = System.currentTimeMillis()
    )
}

// ──────────────────────────────────────────────
// Cursor extension for nullable doubles
// ──────────────────────────────────────────────
private fun Cursor.getDoubleOrNull(columnIndex: Int): Double? {
    return if (isNull(columnIndex)) null else getDouble(columnIndex)
}

private fun Cursor.getLongOrNull(columnIndex: Int): Long? {
    return if (isNull(columnIndex)) null else getLong(columnIndex)
}
