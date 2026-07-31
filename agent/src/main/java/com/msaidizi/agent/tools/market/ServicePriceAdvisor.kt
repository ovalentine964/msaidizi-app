package com.msaidizi.agent.tools.market

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import javax.inject.Inject
import javax.inject.Singleton
import com.msaidizi.agent.tools.core.*

/**
 * ServicePriceAdvisor — Suggests optimal pricing for services.
 *
 * Extends PricingAdvisor (which handles products) to cover the SERVICE economy:
 * transport fares, construction wages, salon prices, repair costs, entertainment fees.
 *
 * Problem: 40%+ of informal workers sell SERVICES, not products. They have no
 * price discovery mechanism — they either undercharge or lose customers to
 * competitors with better pricing information.
 *
 * Features:
 * - Route-based transport pricing (boda boda, tuk-tuk, matatu)
 * - Skill-based labor rate cards (fundis, masons, electricians)
 * - Service-type beauty pricing (braiding, weaving, cut, color)
 * - Device-type repair pricing (phone, electronics, mechanics)
 * - Time-of-day and surge pricing awareness
 * - Location-based pricing (Nairobi vs Migori)
 * - Offline-first with SQLite cache
 */
@Singleton
class ServicePriceAdvisor @Inject constructor(
    private val context: Context
) : Tool {

    override val name = "service_price_advisor"
    override val description = "Suggest optimal pricing for services: transport fares, construction wages, salon prices, repair costs. Solves underpricing for service workers."

    override val argsSchema = argSchema {
        enum(
            "action", "Action to perform",
            listOf(
                "advise",              // Get pricing advice for a service
                "check_rate",          // Check market rate for a service type
                "compare_regions",     // Compare service prices across regions
                "surge_check",         // Check if surge pricing applies
                "set_my_rate",         // Record worker's current rate
                "list_services",       // List all tracked service types
                "sync_rates"           // Force sync from backend
            ),
            required = true
        )
        enum(
            "category", "Service category",
            listOf("transport", "construction", "beauty", "repair", "entertainment"),
            required = false
        )
        string("service_type", "Specific service type (e.g. 'boda_boda_ride', 'hair_braiding', 'phone_screen_repair')", required = false)
        string("region", "Region/location (e.g. 'Nairobi', 'Migori', 'Kisumu')", required = false)
        number("current_price", "Worker's current price in KES", required = false)
        string("unit", "Pricing unit (e.g. 'per_trip', 'per_day', 'per_hour', 'per_piece')", required = false)
        string("skill_level", "Skill/experience level for labor: apprentice, junior, intermediate, senior, master", required = false)
        string("project_type", "Construction project type (e.g. 'foundation', 'roofing', 'plumbing')", required = false)
        string("device_type", "Device type for repairs (e.g. 'Samsung Galaxy', 'iPhone', 'TV')", required = false)
        string("complexity", "Repair complexity: simple, moderate, complex, expert", required = false)
        boolean("voice", "Format response for voice output (Swahili)", required = false)
    }

    // ──────────────────────────────────────────────
    // SQLite Database for Offline Service Price Cache
    // ──────────────────────────────────────────────

    inner class ServicePriceDatabase(context: Context) :
        SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            // Service rates table — aggregated market rates per service/region
            db.execSQL("""
                CREATE TABLE $TABLE_RATES (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    category TEXT NOT NULL,
                    service_type TEXT NOT NULL,
                    region TEXT NOT NULL,
                    price_avg REAL NOT NULL,
                    price_min REAL NOT NULL,
                    price_max REAL NOT NULL,
                    unit TEXT NOT NULL,
                    sample_size INTEGER NOT NULL DEFAULT 0,
                    recorded_at INTEGER NOT NULL,
                    synced_at INTEGER NOT NULL,
                    UNIQUE(category, service_type, region)
                )
            """)

            // Worker's own rates — for comparison
            db.execSQL("""
                CREATE TABLE $TABLE_MY_RATES (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    category TEXT NOT NULL,
                    service_type TEXT NOT NULL,
                    region TEXT NOT NULL,
                    my_rate REAL NOT NULL,
                    unit TEXT NOT NULL,
                    updated_at INTEGER NOT NULL,
                    UNIQUE(category, service_type, region)
                )
            """)

            // Surge pricing events
            db.execSQL("""
                CREATE TABLE $TABLE_SURGE (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    region TEXT NOT NULL,
                    transport_type TEXT,
                    reason TEXT NOT NULL,
                    multiplier REAL NOT NULL DEFAULT 1.0,
                    started_at INTEGER NOT NULL,
                    expected_end INTEGER,
                    is_active INTEGER NOT NULL DEFAULT 1
                )
            """)

            // Regional wage indices
            db.execSQL("""
                CREATE TABLE $TABLE_WAGE_INDEX (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    skill_type TEXT NOT NULL,
                    region TEXT NOT NULL,
                    daily_wage_avg REAL NOT NULL,
                    daily_wage_median REAL,
                    daily_wage_p25 REAL,
                    daily_wage_p75 REAL,
                    worker_count INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL,
                    UNIQUE(skill_type, region)
                )
            """)

            // Indexes
            db.execSQL("CREATE INDEX idx_rates_category ON $TABLE_RATES(category)")
            db.execSQL("CREATE INDEX idx_rates_region ON $TABLE_RATES(region)")
            db.execSQL("CREATE INDEX idx_rates_service ON $TABLE_RATES(service_type)")
            db.execSQL("CREATE INDEX idx_surge_region ON $TABLE_SURGE(region, is_active)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_RATES")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_MY_RATES")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_SURGE")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_WAGE_INDEX")
            onCreate(db)
        }
    }

    companion object {
        private const val DB_NAME = "service_prices.db"
        private const val DB_VERSION = 1
        private const val TABLE_RATES = "service_rates"
        private const val TABLE_MY_RATES = "my_service_rates"
        private const val TABLE_SURGE = "surge_events"
        private const val TABLE_WAGE_INDEX = "wage_index"
        private const val STALE_THRESHOLD_MS = 24 * 60 * 60 * 1000L
    }

    private var dbHelper: ServicePriceDatabase? = null

    private fun getDb(): SQLiteDatabase {
        if (dbHelper == null) dbHelper = ServicePriceDatabase(context)
        return dbHelper!!.writableDatabase
    }

    // ──────────────────────────────────────────────
    // Service type catalog (Swahili-aware)
    // ──────────────────────────────────────────────

    private val serviceCatalog = mapOf(
        "transport" to mapOf(
            "boda_boda" to ServiceInfo("boda_boda_ride", "per_trip", "Boda boda fare"),
            "boda" to ServiceInfo("boda_boda_ride", "per_trip", "Boda boda fare"),
            "tuk_tuk" to ServiceInfo("tuk_tuk_ride", "per_trip", "Tuk-tuk fare"),
            "tuktuk" to ServiceInfo("tuk_tuk_ride", "per_trip", "Tuk-tuk fare"),
            "matatu" to ServiceInfo("matatu_fare", "per_trip", "Matatu fare"),
            "taxi" to ServiceInfo("taxi_ride", "per_trip", "Taxi fare"),
        ),
        "construction" to mapOf(
            "mason" to ServiceInfo("mason", "per_day", "Masonry work"),
            "mjengo" to ServiceInfo("mason", "per_day", "Masonry work"),
            "plumber" to ServiceInfo("plumber", "per_day", "Plumbing work"),
            "fundi_maji" to ServiceInfo("plumber", "per_day", "Plumbing work"),
            "electrician" to ServiceInfo("electrician", "per_day", "Electrical work"),
            "fundi_umeme" to ServiceInfo("electrician", "per_day", "Electrical work"),
            "carpenter" to ServiceInfo("carpenter", "per_day", "Carpentry work"),
            "fundi_seremala" to ServiceInfo("carpenter", "per_day", "Carpentry work"),
            "painter" to ServiceInfo("painter", "per_day", "Painting work"),
            "welder" to ServiceInfo("welder", "per_day", "Welding work"),
            "roofer" to ServiceInfo("roofer", "per_day", "Roofing work"),
            "tiler" to ServiceInfo("tiler", "per_day", "Tiling work"),
            "laborer" to ServiceInfo("general_laborer", "per_day", "General labor"),
            "mtu_wa_kazi" to ServiceInfo("general_laborer", "per_day", "General labor"),
        ),
        "beauty" to mapOf(
            "braiding" to ServiceInfo("hair_braiding", "per_head", "Hair braiding"),
            "kunyoa" to ServiceInfo("hair_braiding", "per_head", "Hair braiding"),
            "weaving" to ServiceInfo("hair_weaving", "per_head", "Hair weaving"),
            "cut" to ServiceInfo("haircut", "per_head", "Haircut"),
            "nywele" to ServiceInfo("haircut", "per_head", "Haircut"),
            "color" to ServiceInfo("hair_coloring", "per_head", "Hair coloring"),
            "relaxer" to ServiceInfo("hair_relaxing", "per_head", "Hair relaxing"),
            "dreadlocks" to ServiceInfo("dreadlocks", "per_head", "Dreadlocks"),
            "locs" to ServiceInfo("dreadlocks", "per_head", "Dreadlocks"),
            "manicure" to ServiceInfo("manicure", "per_set", "Manicure"),
            "pedicure" to ServiceInfo("pedicure", "per_set", "Pedicure"),
            "makeup" to ServiceInfo("makeup", "per_session", "Makeup"),
            "shave" to ServiceInfo("shave", "per_head", "Shave/beard trim"),
            "beard" to ServiceInfo("beard_trim", "per_head", "Beard trim"),
        ),
        "repair" to mapOf(
            "screen" to ServiceInfo("phone_screen_repair", "per_piece", "Phone screen repair"),
            "simu" to ServiceInfo("phone_screen_repair", "per_piece", "Phone screen repair"),
            "battery" to ServiceInfo("phone_battery_repair", "per_piece", "Phone battery replacement"),
            "tv" to ServiceInfo("tv_repair", "per_piece", "TV repair"),
            "radio" to ServiceInfo("radio_repair", "per_piece", "Radio/audio repair"),
            "pikipiki" to ServiceInfo("motorcycle_repair", "per_job", "Motorcycle repair"),
            "gari" to ServiceInfo("vehicle_repair", "per_job", "Vehicle repair"),
            "baisikeli" to ServiceInfo("bicycle_repair", "per_job", "Bicycle repair"),
        ),
        "entertainment" to mapOf(
            "dj" to ServiceInfo("dj", "per_hour", "DJ services"),
            "mc" to ServiceInfo("mc", "per_event", "MC services"),
            "band" to ServiceInfo("live_band", "per_event", "Live band"),
            "musician" to ServiceInfo("solo_musician", "per_event", "Solo musician"),
            "photographer" to ServiceInfo("photographer", "per_event", "Photography"),
            "picha" to ServiceInfo("photographer", "per_event", "Photography"),
            "videographer" to ServiceInfo("videographer", "per_event", "Videography"),
            "sound" to ServiceInfo("sound_system", "per_event", "Sound system rental"),
        )
    )

    private data class ServiceInfo(
        val serviceType: String,
        val defaultUnit: String,
        val description: String
    )

    // ──────────────────────────────────────────────
    // Skill level multipliers
    // ──────────────────────────────────────────────

    private val skillMultipliers = mapOf(
        "apprentice" to 0.6,
        "junior" to 0.8,
        "intermediate" to 1.0,
        "senior" to 1.3,
        "master" to 1.6
    )

    // ──────────────────────────────────────────────
    // Execute
    // ──────────────────────────────────────────────

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"]
            ?: return ToolResult.error(name, "Action required", "MISSING_ACTION")

        return when (action.lowercase()) {
            "advise" -> advise(params)
            "check_rate" -> checkRate(params)
            "compare_regions" -> compareRegions(params)
            "surge_check" -> surgeCheck(params)
            "set_my_rate" -> setMyRate(params)
            "list_services" -> listServices(params)
            "sync_rates" -> syncRates(params)
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    // ──────────────────────────────────────────────
    // ACTION: advise — Full pricing advice
    // ──────────────────────────────────────────────

    private fun advise(params: Map<String, String>): ToolResult {
        val category = params["category"]
            ?: return ToolResult.error(name, "Category required (transport, construction, beauty, repair, entertainment)", "MISSING_CATEGORY")
        val serviceTypeRaw = params["service_type"]
            ?: return ToolResult.error(name, "Service type required. Example: 'boda_boda', 'braiding', 'screen'", "MISSING_SERVICE_TYPE")
        val region = params["region"] ?: "nairobi"
        val currentPrice = params["current_price"]?.toDoubleOrNull()
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val skillLevel = params["skill_level"]

        val serviceInfo = resolveService(category, serviceTypeRaw)
            ?: return ToolResult.error(name, "Unknown service '$serviceTypeRaw' in $category. Use list_services to see options.", "UNKNOWN_SERVICE")

        val db = getDb()
        val marketRate = queryRate(db, category, serviceInfo.serviceType, region)

        if (marketRate == null) {
            return if (voice) {
                ToolResult.success(
                    name,
                    mapOf("found" to false, "category" to category, "service" to serviceInfo.serviceType),
                    "Sikujua bei ya ${serviceInfo.description} hapa $region. Jaribu baada ya sync."
                )
            } else {
                ToolResult.success(
                    name,
                    mapOf("found" to false),
                    "No rate data for ${serviceInfo.serviceType} in $region. Try sync_rates."
                )
            }
        }

        // Apply skill multiplier for construction/labor
        var adjustedAvg = marketRate.priceAvg
        var skillNote = ""
        if (category == "construction" && skillLevel != null) {
            val multiplier = skillMultipliers[skillLevel.lowercase()] ?: 1.0
            adjustedAvg *= multiplier
            skillNote = " (kiwango: $skillLevel, multiplier: ${multiplier}x)"
        }

        // Check for surge
        val surge = queryActiveSurge(db, region)
        var surgeNote = ""
        if (surge != null && category == "transport") {
            adjustedAvg *= surge.multiplier
            surgeNote = " ⚡ Surge: ${surge.reason} (${surge.multiplier}x)"
        }

        val unit = serviceInfo.defaultUnit

        // Compare with worker's current price
        val comparisonNote = if (currentPrice != null) {
            val diff = ((currentPrice - adjustedAvg) / adjustedAvg * 100)
            when {
                diff > 20 -> "\n⚠️ Bei yako ni ya juu sana (${diff.toInt()}% zaidi). Wateja wanaweza kukimbia."
                diff < -20 -> "\n💰 Bei yako ni ya chini sana (${Math.abs(diff).toInt()}% chini). Unaweza kuongeza hadi KES ${formatPrice(adjustedAvg)}."
                else -> "\n✅ Bei yako ni ya ushindani."
            }
        } else ""

        val resultData = mapOf(
            "category" to category,
            "service_type" to serviceInfo.serviceType,
            "region" to region,
            "market_avg" to marketRate.priceAvg,
            "market_min" to marketRate.priceMin,
            "market_max" to marketRate.priceMax,
            "adjusted_avg" to adjustedAvg,
            "unit" to unit,
            "sample_size" to marketRate.sampleSize,
            "surge_active" to (surge != null),
            "found" to true
        )

        val message = if (voice) {
            buildString {
                append("Bei ya ${serviceInfo.description} hapa $region:\n")
                append("• Wastani: KES ${formatPrice(adjustedAvg)} $unit$skillNote\n")
                append("• Safu: KES ${formatPrice(marketRate.priceMin)}-${formatPrice(marketRate.priceMax)} $unit\n")
                append(surgeNote)
                append(comparisonNote)
                if (marketRate.sampleSize < 50) {
                    append("\n⚠️ Data ni kidogo (samples: ${marketRate.sampleSize})")
                }
            }
        } else {
            buildString {
                append("${serviceInfo.description} in $region:\n")
                append("• Average: KES ${formatPrice(adjustedAvg)}/$unit$skillNote\n")
                append("• Range: KES ${formatPrice(marketRate.priceMin)}-${formatPrice(marketRate.priceMax)}/$unit\n")
                append("• Samples: ${marketRate.sampleSize}")
                append(surgeNote)
                append(comparisonNote)
            }
        }

        return ToolResult.success(name, resultData, message)
    }

    // ──────────────────────────────────────────────
    // ACTION: check_rate — Quick rate lookup
    // ──────────────────────────────────────────────

    private fun checkRate(params: Map<String, String>): ToolResult {
        val category = params["category"]
            ?: return ToolResult.error(name, "Category required", "MISSING_CATEGORY")
        val serviceTypeRaw = params["service_type"]
            ?: return ToolResult.error(name, "Service type required", "MISSING_SERVICE_TYPE")
        val region = params["region"] ?: "nairobi"
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val serviceInfo = resolveService(category, serviceTypeRaw) ?: return ToolResult.error(name, "Unknown service", "UNKNOWN_SERVICE")
        val db = getDb()
        val rate = queryRate(db, category, serviceInfo.serviceType, region)

        if (rate == null) {
            return ToolResult.success(name, mapOf("found" to false), if (voice) "Hakuna data ya bei." else "No rate data available.")
        }

        val message = if (voice) {
            "${serviceInfo.description}: KES ${formatPrice(rate.priceMin)}-${formatPrice(rate.priceMax)} kwa ${serviceInfo.defaultUnit} hapa $region"
        } else {
            "${serviceInfo.serviceType}: KES ${formatPrice(rate.priceMin)}-${formatPrice(rate.priceMax)}/${serviceInfo.defaultUnit} in $region"
        }

        return ToolResult.success(name, mapOf("found" to true, "min" to rate.priceMin, "max" to rate.priceMax, "avg" to rate.priceAvg), message)
    }

    // ──────────────────────────────────────────────
    // ACTION: compare_regions — Cross-region comparison
    // ──────────────────────────────────────────────

    private fun compareRegions(params: Map<String, String>): ToolResult {
        val category = params["category"]
            ?: return ToolResult.error(name, "Category required", "MISSING_CATEGORY")
        val serviceTypeRaw = params["service_type"]
            ?: return ToolResult.error(name, "Service type required", "MISSING_SERVICE_TYPE")
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val serviceInfo = resolveService(category, serviceTypeRaw) ?: return ToolResult.error(name, "Unknown service", "UNKNOWN_SERVICE")
        val db = getDb()

        val rates = queryAllRegions(db, category, serviceInfo.serviceType)
        if (rates.isEmpty()) {
            return ToolResult.success(name, mapOf("found" to false), if (voice) "Hakuna data ya kulinganisha mikoa." else "No cross-region data available.")
        }

        val sorted = rates.sortedBy { it.priceAvg }
        val cheapest = sorted.first()
        val mostExpensive = sorted.last()
        val spread = if (cheapest.priceAvg > 0) ((mostExpensive.priceAvg - cheapest.priceAvg) / cheapest.priceAvg * 100) else 0.0

        val message = if (voice) {
            buildString {
                append("Bei ya ${serviceInfo.description} kwa mikoa:\n")
                sorted.forEach { r ->
                    append("• ${r.region}: KES ${formatPrice(r.priceAvg)} (samples: ${r.sampleSize})\n")
                }
                append("\nBei rahisi: ${cheapest.region}")
                if (spread > 15) append("\nTofauti ni ${spread.toInt()}% — angalia soko la bei rahisi!")
            }
        } else {
            buildString {
                append("${serviceInfo.serviceType} across regions:\n")
                sorted.forEach { r ->
                    append("• ${r.region}: KES ${formatPrice(r.priceAvg)} (n=${r.sampleSize})\n")
                }
                append("\nCheapest: ${cheapest.region} @ KES ${formatPrice(cheapest.priceAvg)}")
                append("\nSpread: ${spread.toInt()}%")
            }
        }

        return ToolResult.success(name, mapOf("comparisons" to sorted.map { mapOf("region" to it.region, "avg" to it.priceAvg, "samples" to it.sampleSize) }, "spread_pct" to spread), message)
    }

    // ──────────────────────────────────────────────
    // ACTION: surge_check — Check for surge pricing
    // ──────────────────────────────────────────────

    private fun surgeCheck(params: Map<String, String>): ToolResult {
        val region = params["region"] ?: return ToolResult.error(name, "Region required", "MISSING_REGION")
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val db = getDb()
        val surge = queryActiveSurge(db, region)

        if (surge == null) {
            return ToolResult.success(
                name,
                mapOf("surge_active" to false, "region" to region),
                if (voice) "Hakuna surge ya bei hapa $region sasa." else "No active surge in $region."
            )
        }

        val message = if (voice) {
            "⚡ SURGE hapa $region! Sababu: ${surge.reason}. Bei ni ${surge.multiplier}x ya kawaida."
        } else {
            "⚡ SURGE active in $region: ${surge.reason} (${surge.multiplier}x multiplier)"
        }

        return ToolResult.success(name, mapOf("surge_active" to true, "region" to region, "reason" to surge.reason, "multiplier" to surge.multiplier), message)
    }

    // ──────────────────────────────────────────────
    // ACTION: set_my_rate — Record worker's rate
    // ──────────────────────────────────────────────

    private fun setMyRate(params: Map<String, String>): ToolResult {
        val category = params["category"] ?: return ToolResult.error(name, "Category required", "MISSING_CATEGORY")
        val serviceTypeRaw = params["service_type"] ?: return ToolResult.error(name, "Service type required", "MISSING_SERVICE_TYPE")
        val region = params["region"] ?: "nairobi"
        val currentPrice = params["current_price"]?.toDoubleOrNull() ?: return ToolResult.error(name, "Current price required", "MISSING_PRICE")
        val unit = params["unit"]

        val serviceInfo = resolveService(category, serviceTypeRaw) ?: return ToolResult.error(name, "Unknown service", "UNKNOWN_SERVICE")
        val db = getDb()

        val values = ContentValues().apply {
            put("category", category)
            put("service_type", serviceInfo.serviceType)
            put("region", region)
            put("my_rate", currentPrice)
            put("unit", unit ?: serviceInfo.defaultUnit)
            put("updated_at", System.currentTimeMillis())
        }
        db.insertWithOnConflict(TABLE_MY_RATES, null, values, SQLiteDatabase.CONFLICT_REPLACE)

        return ToolResult.success(
            name,
            mapOf("category" to category, "service_type" to serviceInfo.serviceType, "region" to region, "rate" to currentPrice),
            "Rate yako imesajiliwa: KES ${formatPrice(currentPrice)} kwa ${unit ?: serviceInfo.defaultUnit}"
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: list_services — Show all tracked services
    // ──────────────────────────────────────────────

    private fun listServices(params: Map<String, String>): ToolResult {
        val category = params["category"]
        val categories = if (category != null) listOf(category) else serviceCatalog.keys.toList()

        val message = buildString {
            categories.forEach { cat ->
                val services = serviceCatalog[cat]
                if (services != null) {
                    append("📋 ${cat.uppercase()}:\n")
                    services.values.distinctBy { it.serviceType }.forEach { info ->
                        append("• ${info.description} (${info.serviceType}) — ${info.defaultUnit}\n")
                    }
                    append("\n")
                }
            }
        }

        return ToolResult.success(name, mapOf("categories" to categories), message)
    }

    // ──────────────────────────────────────────────
    // ACTION: sync_rates — Sync from backend
    // ──────────────────────────────────────────────

    private fun syncRates(params: Map<String, String>): ToolResult {
        val category = params["category"]
        val region = params["region"]
        val db = getDb()
        val count = countCachedRates(db, category, region)

        return ToolResult.success(
            name,
            mapOf("cached_rates" to count, "needs_network" to true),
            "🔄 Sync requested. Cached rates: $count. Backend sync requires network."
        )
    }

    // ──────────────────────────────────────────────
    // Internal Helpers
    // ──────────────────────────────────────────────

    private fun resolveService(category: String, raw: String): ServiceInfo? {
        val catServices = serviceCatalog[category.lowercase()] ?: return null
        val lower = raw.trim().lowercase()
        return catServices[lower] ?: catServices.values.find { it.serviceType == lower }
    }

    private fun queryRate(db: SQLiteDatabase, category: String, serviceType: String, region: String): RateData? {
        val cursor = db.query(
            TABLE_RATES, null,
            "category = ? AND service_type = ? AND region = ?",
            arrayOf(category, serviceType, region),
            null, null, null
        )
        cursor.use {
            return if (it.moveToFirst()) {
                RateData(
                    priceAvg = it.getDouble(it.getColumnIndexOrThrow("price_avg")),
                    priceMin = it.getDouble(it.getColumnIndexOrThrow("price_min")),
                    priceMax = it.getDouble(it.getColumnIndexOrThrow("price_max")),
                    unit = it.getString(it.getColumnIndexOrThrow("unit")),
                    sampleSize = it.getInt(it.getColumnIndexOrThrow("sample_size")),
                    region = region
                )
            } else null
        }
    }

    private fun queryAllRegions(db: SQLiteDatabase, category: String, serviceType: String): List<RateData> {
        val results = mutableListOf<RateData>()
        val cursor = db.query(
            TABLE_RATES, null,
            "category = ? AND service_type = ?",
            arrayOf(category, serviceType),
            null, null, "price_avg ASC"
        )
        cursor.use {
            while (it.moveToNext()) {
                results.add(RateData(
                    priceAvg = it.getDouble(it.getColumnIndexOrThrow("price_avg")),
                    priceMin = it.getDouble(it.getColumnIndexOrThrow("price_min")),
                    priceMax = it.getDouble(it.getColumnIndexOrThrow("price_max")),
                    unit = it.getString(it.getColumnIndexOrThrow("unit")),
                    sampleSize = it.getInt(it.getColumnIndexOrThrow("sample_size")),
                    region = it.getString(it.getColumnIndexOrThrow("region"))
                ))
            }
        }
        return results
    }

    private fun queryActiveSurge(db: SQLiteDatabase, region: String): SurgeData? {
        val cursor = db.query(
            TABLE_SURGE, null,
            "region = ? AND is_active = 1",
            arrayOf(region),
            null, null, "started_at DESC", "1"
        )
        cursor.use {
            return if (it.moveToFirst()) {
                SurgeData(
                    reason = it.getString(it.getColumnIndexOrThrow("reason")),
                    multiplier = it.getDouble(it.getColumnIndexOrThrow("multiplier"))
                )
            } else null
        }
    }

    private fun countCachedRates(db: SQLiteDatabase, category: String?, region: String?): Int {
        val selection = mutableListOf<String>()
        val args = mutableListOf<String>()
        category?.let { selection.add("category = ?"); args.add(it) }
        region?.let { selection.add("region = ?"); args.add(it) }
        val where = if (selection.isNotEmpty()) selection.joinToString(" AND ") else null
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_RATES${if (where != null) " WHERE $where" else ""}", args.toTypedArray())
        cursor.use { return if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    /**
     * Insert a service rate into the local cache.
     * Called by SyncEngine when backend data arrives.
     */
    fun insertRate(category: String, serviceType: String, region: String, priceAvg: Double, priceMin: Double, priceMax: Double, unit: String, sampleSize: Int) {
        val db = getDb()
        val values = ContentValues().apply {
            put("category", category)
            put("service_type", serviceType)
            put("region", region)
            put("price_avg", priceAvg)
            put("price_min", priceMin)
            put("price_max", priceMax)
            put("unit", unit)
            put("sample_size", sampleSize)
            put("recorded_at", System.currentTimeMillis())
            put("synced_at", System.currentTimeMillis())
        }
        db.insertWithOnConflict(TABLE_RATES, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun formatPrice(price: Double): String {
        return if (price == price.toLong().toDouble()) "%,.0f".format(price) else "%,.1f".format(price)
    }

    // ──────────────────────────────────────────────
    // Data Classes
    // ──────────────────────────────────────────────

    private data class RateData(
        val priceAvg: Double,
        val priceMin: Double,
        val priceMax: Double,
        val unit: String,
        val sampleSize: Int,
        val region: String
    )

    private data class SurgeData(
        val reason: String,
        val multiplier: Double
    )
}
