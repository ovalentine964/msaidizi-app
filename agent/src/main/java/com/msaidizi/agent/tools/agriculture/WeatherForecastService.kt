package com.msaidizi.agent.tools.agriculture

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext
import com.msaidizi.agent.tools.core.*

/**
 * WeatherForecastService — Earth2Studio-powered weather forecasting for Kenyan agriculture.
 *
 * Calls the Earth2Studio backend API to get AI-powered weather forecasts,
 * then caches results locally for offline access.
 *
 * Integrates with:
 * - WeatherCacheManager: provides real forecast data to the cache
 * - HarvestTracker: weather-informed harvest timing
 * - YieldPredictor: weather-adjusted yield predictions
 * - HarvestTimingOptimizer: weather risk for sell/store decisions
 *
 * Architecture:
 *   Android App → HTTP → Earth2Studio Backend (GPU server) → Earth2Studio → GFS/ERA5
 *                                          ↓
 *                                    Local SQLite Cache (7+ days offline)
 *
 * Voice examples:
 *   "Tabiri ya hali ya hewa"              → 7-day AI forecast
 *   "Mvua inakuja lini Migori?"           → County-specific rain forecast
 *   "Hali ya hewa ya kilimo Nairobi"      → Agricultural weather advisory
 */
@Singleton
class WeatherForecastService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val weatherCacheManager: WeatherCacheManager
) : Tool {

    override val name = "weather_forecast_service"
    override val description = "AI-powered weather forecasting using Earth2Studio. Provides 7-day deterministic forecasts, county-level agricultural weather, and offline-cached predictions for Kenyan farmers."

    override val argsSchema = argSchema {
        enum(
            "action", "Action to perform",
            listOf(
                "forecast",          // Full 7-day AI forecast
                "county_forecast",   // County-specific agricultural forecast
                "rain_prediction",   // When will rain come?
                "agri_weather",      // Agricultural weather advisory
                "frost_risk",        // Frost/cold risk assessment
                "heat_stress",       // Heat stress risk for crops/livestock
                "sync_forecasts",    // Sync forecasts from backend to local cache
                "backend_health",    // Check backend API health
                "set_backend_url"    // Configure backend API URL
            ),
            required = true
        )
        string("location", "Location name or county", required = false)
        number("latitude", "GPS latitude", required = false)
        number("longitude", "GPS longitude", required = false)
        string("county", "Kenya county name", required = false)
        string("crop", "Crop for specific advisory", required = false)
        string("activity", "Farming activity: harvest/dry/plant/spray", required = false)
        integer("forecast_days", "Number of forecast days (1-7)", required = false)
        string("backend_url", "Earth2Studio backend URL", required = false)
        boolean("voice", "Format for voice output (Swahili)", required = false)
    }

    companion object {
        private const val DB_NAME = "forecast_service.db"
        private const val DB_VERSION = 1
        private const val TABLE_CACHE = "forecast_cache"
        private const val TABLE_CONFIG = "service_config"

        // Default backend URL (configurable)
        private const val DEFAULT_BACKEND_URL = "https://msaidizi-weather.fly.dev"

        // Cache duration: 24 hours for AI forecasts
        private const val CACHE_DURATION_MS = 24 * 60 * 60 * 1000L

        // Network timeout
        private const val CONNECT_TIMEOUT_MS = 15000
        private const val READ_TIMEOUT_MS = 30000

        // Kenya counties with coordinates
        val KENYA_COUNTIES = mapOf(
            "migori" to CountyInfo("Migori", -1.0634, 34.4731),
            "kisumu" to CountyInfo("Kisumu", -0.1022, 34.7617),
            "nairobi" to CountyInfo("Nairobi", -1.2921, 36.8219),
            "nakuru" to CountyInfo("Nakuru", -0.3031, 36.0800),
            "nyeri" to CountyInfo("Nyeri", -0.4167, 36.9500),
            "meru" to CountyInfo("Meru", 0.0500, 37.6500),
            "kisii" to CountyInfo("Kisii", -0.6833, 34.7667),
            "bungoma" to CountyInfo("Bungoma", 0.5667, 34.5667),
            "kakamega" to CountyInfo("Kakamega", 0.2833, 34.7500),
            "uasin_gishu" to CountyInfo("Uasin Gishu", 0.5167, 35.2833),
            "trans_nzoia" to CountyInfo("Trans Nzoia", 1.0167, 35.0000),
            "nandi" to CountyInfo("Nandi", 0.1833, 35.1333),
            "kericho" to CountyInfo("Kericho", -0.3667, 35.2833),
            "bomet" to CountyInfo("Bomet", -0.7833, 35.3333),
            "kilifi" to CountyInfo("Kilifi", -3.6333, 39.8500),
            "mombasa" to CountyInfo("Mombasa", -4.0500, 39.6667),
            "machakos" to CountyInfo("Machakos", -1.5167, 37.2667),
            "kitui" to CountyInfo("Kitui", -1.3667, 38.0167),
            "embu" to CountyInfo("Embu", -0.5333, 37.4500),
            "laikipia" to CountyInfo("Laikipia", 0.0167, 36.7833),
            "nyandarua" to CountyInfo("Nyandarua", -0.3833, 36.5667),
            "kirinyaga" to CountyInfo("Kirinyaga", -0.5000, 37.2833),
            "turkana" to CountyInfo("Turkana", 3.1167, 35.5833),
            "marsabit" to CountyInfo("Marsabit", 2.3333, 37.9833),
            "isiolo" to CountyInfo("Isiolo", 0.3500, 37.5833),
            "garissa" to CountyInfo("Garissa", -0.4536, 39.6401),
            "taita_taveta" to CountyInfo("Taita Taveta", -3.3167, 38.3500),
            "kwale" to CountyInfo("Kwale", -4.1737, 39.4521),
        )

        // Crop-specific weather thresholds
        val CROP_THRESHOLDS = mapOf(
            "maize" to CropThresholds(
                min_temp = 10.0, max_temp = 40.0, optimal_temp_low = 21.0, optimal_temp_high = 30.0,
                min_rain_week_mm = 25.0, max_rain_week_mm = 100.0,
                frost_sensitivity = "high", heat_sensitivity = "moderate"
            ),
            "beans" to CropThresholds(
                min_temp = 8.0, max_temp = 35.0, optimal_temp_low = 18.0, optimal_temp_high = 27.0,
                min_rain_week_mm = 20.0, max_rain_week_mm = 80.0,
                frost_sensitivity = "high", heat_sensitivity = "high"
            ),
            "wheat" to CropThresholds(
                min_temp = 5.0, max_temp = 35.0, optimal_temp_low = 15.0, optimal_temp_high = 25.0,
                min_rain_week_mm = 15.0, max_rain_week_mm = 60.0,
                frost_sensitivity = "moderate", heat_sensitivity = "moderate"
            ),
            "tea" to CropThresholds(
                min_temp = 13.0, max_temp = 30.0, optimal_temp_low = 18.0, optimal_temp_high = 25.0,
                min_rain_week_mm = 40.0, max_rain_week_mm = 150.0,
                frost_sensitivity = "high", heat_sensitivity = "high"
            ),
            "coffee" to CropThresholds(
                min_temp = 15.0, max_temp = 30.0, optimal_temp_low = 18.0, optimal_temp_high = 26.0,
                min_rain_week_mm = 30.0, max_rain_week_mm = 120.0,
                frost_sensitivity = "high", heat_sensitivity = "moderate"
            ),
        )

        // Swahili weather conditions
        val CONDITIONS_SW = mapOf(
            "sunny" to "Jua",
            "clear" to "Wazi",
            "partly_cloudy" to "Mawingu machache",
            "cloudy" to "Mawingu mengi",
            "overcast" to "Mawingu yote",
            "light_rain" to "Mvua nyepesi",
            "rain" to "Mvua",
            "heavy_rain" to "Mvua kubwa",
            "thunderstorm" to "Dhoruba",
            "drizzle" to "Mvua ndogo",
            "fog" to "Ukungu",
            "windy" to "Upepo mkali",
            "hot" to "Joto kali",
            "cold" to "Baridi"
        )
    }

    data class CountyInfo(val name: String, val lat: Double, val lon: Double)

    data class CropThresholds(
        val min_temp: Double, val max_temp: Double,
        val optimal_temp_low: Double, val optimal_temp_high: Double,
        val min_rain_week_mm: Double, val max_rain_week_mm: Double,
        val frost_sensitivity: String, val heat_sensitivity: String
    )

    data class ForecastDayData(
        val date: String,
        val tempHigh: Double,
        val tempLow: Double,
        val humidity: Double,
        val rainfallMm: Double,
        val rainProbability: Double,
        val windSpeedKmh: Double,
        val windDirection: String,
        val cloudCoverPct: Double,
        val uvIndex: Double,
        val condition: String,
        val conditionDetail: String
    )

    // ── Database ──────────────────────────────────────────────────

    inner class ForecastDatabase(context: Context) :
        SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE $TABLE_CACHE (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    location TEXT NOT NULL,
                    latitude REAL,
                    longitude REAL,
                    county TEXT,
                    forecast_json TEXT NOT NULL,
                    advisory_text TEXT,
                    model TEXT,
                    source TEXT DEFAULT 'earth2studio',
                    cached_at INTEGER NOT NULL,
                    expires_at INTEGER NOT NULL,
                    UNIQUE(location, cached_at)
                )
            """)

            db.execSQL("""
                CREATE TABLE $TABLE_CONFIG (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL,
                    updated_at INTEGER NOT NULL
                )
            """)

            db.execSQL("CREATE INDEX idx_cache_loc ON $TABLE_CACHE(location, expires_at)")
            db.execSQL("CREATE INDEX idx_cache_expiry ON $TABLE_CACHE(expires_at)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_CACHE")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_CONFIG")
            onCreate(db)
        }
    }

    private var dbHelper: ForecastDatabase? = null
    private val gson = Gson()

    private fun getDb(): SQLiteDatabase {
        if (dbHelper == null) dbHelper = ForecastDatabase(context)
        return dbHelper!!.writableDatabase
    }

    private fun getBackendUrl(): String {
        val db = getDb()
        val cursor = db.query(TABLE_CONFIG, arrayOf("value"), "key = ?", arrayOf("backend_url"), null, null, null)
        cursor.use {
            return if (it.moveToFirst()) it.getString(0) else DEFAULT_BACKEND_URL
        }
    }

    // ── Tool Execute ──────────────────────────────────────────────

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"]
            ?: return ToolResult.error(name, "Action required", "MISSING_ACTION")

        return when (action.lowercase()) {
            "forecast" -> getForecast(params)
            "county_forecast" -> getCountyForecast(params)
            "rain_prediction" -> getRainPrediction(params)
            "agri_weather" -> getAgriWeather(params)
            "frost_risk" -> getFrostRisk(params)
            "heat_stress" -> getHeatStress(params)
            "sync_forecasts" -> syncForecasts(params)
            "backend_health" -> checkBackendHealth(params)
            "set_backend_url" -> setBackendUrl(params)
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    // ── ACTION: forecast ──────────────────────────────────────────

    private suspend fun getForecast(params: Map<String, String>): ToolResult {
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val location = resolveLocation(params)
        val forecastDays = params["forecast_days"]?.toIntOrNull() ?: 7

        // Check cache first
        val cached = getCachedForecast(location.name, forecastDays)
        if (cached != null) {
            return formatForecastResponse(cached, location, voice, params["crop"], params["activity"])
        }

        // Call backend API
        return try {
            val response = callForecastApi(location, forecastDays)
            cacheForecast(location, response)
            formatForecastResponse(response, location, voice, params["crop"], params["activity"])
        } catch (e: Exception) {
            Timber.e(e, "Forecast API call failed for ${location.name}")
            // Try stale cache
            val stale = getStaleCachedForecast(location.name)
            if (stale != null) {
                formatForecastResponse(stale, location, voice, params["crop"], params["activity"], isStale = true)
            } else {
                ToolResult.error(name, "Forecast unavailable. Check network or backend.", "FORECAST_FAILED")
            }
        }
    }

    // ── ACTION: county_forecast ───────────────────────────────────

    private suspend fun getCountyForecast(params: Map<String, String>): ToolResult {
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val countyName = params["county"]
            ?: return ToolResult.error(name, "County name required", "MISSING_COUNTY")

        val county = KENYA_COUNTIES[countyName.lowercase().replace(" ", "_").replace("-", "_")]
            ?: return ToolResult.error(name, "Unknown county: $countyName. Available: ${KENYA_COUNTIES.keys.joinToString()}", "UNKNOWN_COUNTY")

        val crop = params["crop"]
        val activity = params["activity"]

        // Check cache
        val cacheKey = "county_${countyName.lowercase()}"
        val cached = getCachedForecast(cacheKey, 7)
        if (cached != null) {
            return formatCountyResponse(cached, county, voice, crop, activity)
        }

        // Call backend
        return try {
            val response = callCountyForecastApi(county, crop, activity)
            cacheForecast(
                LocationInfo(cacheKey, county.lat, county.lon, countyName),
                response
            )
            formatCountyResponse(response, county, voice, crop, activity)
        } catch (e: Exception) {
            Timber.e(e, "County forecast failed for ${county.name}")
            ToolResult.error(name, "County forecast unavailable: ${e.message}", "COUNTY_FORECAST_FAILED")
        }
    }

    // ── ACTION: rain_prediction ───────────────────────────────────

    private suspend fun getRainPrediction(params: Map<String, String>): ToolResult {
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val location = resolveLocation(params)

        val forecast = getCachedOrFetchForecast(location, 7)
            ?: return ToolResult.error(name, "No forecast data available", "NO_DATA")

        val rainyDays = forecast.filter { it.rainfallMm > 2.0 }
        val heavyDays = forecast.filter { it.rainfallMm > 15.0 }
        val dryDays = forecast.filter { it.rainfallMm < 1.0 }

        val message = if (voice) {
            buildString {
                appendLine("🌧️ *Utabiri wa Mvua — ${location.name}*")
                appendLine()

                if (heavyDays.isNotEmpty()) {
                    appendLine("🚨 *Mvua kubwa inakuja:*")
                    heavyDays.forEach { d ->
                        appendLine("   🌧️ ${formatDateSw(d.date)}: ${d.rainfallMm.toInt()}mm — ${d.conditionDetail}")
                    }
                    appendLine()
                    appendLine("⚠️ *Hatua za haraka:*")
                    appendLine("   • Vuna mazao yaliyo tayari")
                    appendLine("   • Hifadhi mazao mahali pa kavu")
                } else if (rainyDays.isNotEmpty()) {
                    appendLine("🌧️ *Mvua inakuja:*")
                    rainyDays.forEach { d ->
                        appendLine("   🌧️ ${formatDateSw(d.date)}: ${d.rainfallMm.toInt()}mm")
                    }
                } else {
                    appendLine("☀️ Hakuna mvua wiki hii.")
                }

                if (dryDays.isNotEmpty()) {
                    appendLine()
                    appendLine("📅 *Siku za kavu (nzuri kwa kazi):*")
                    dryDays.forEach { d ->
                        appendLine("   ☀️ ${formatDateSw(d.date)}: ${CONDITIONS_SW[d.condition] ?: d.condition}")
                    }
                }
            }
        } else {
            buildString {
                appendLine("Rain prediction — ${location.name}:")
                forecast.forEach { d ->
                    appendLine("${d.date}: ${d.condition}, ${d.rainfallMm.toInt()}mm rain, ${d.tempHigh.toInt()}°C")
                }
            }
        }

        return ToolResult.success(
            name,
            mapOf(
                "location" to location.name,
                "rainy_days" to rainyDays.map { it.date },
                "heavy_rain_days" to heavyDays.map { it.date },
                "dry_days" to dryDays.map { it.date },
                "next_rain" to rainyDays.firstOrNull()?.date
            ),
            message
        )
    }

    // ── ACTION: agri_weather ──────────────────────────────────────

    private suspend fun getAgriWeather(params: Map<String, String>): ToolResult {
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val location = resolveLocation(params)
        val crop = params["crop"] ?: "maize"
        val activity = params["activity"]

        val forecast = getCachedOrFetchForecast(location, 7)
            ?: return ToolResult.error(name, "No forecast data available", "NO_DATA")

        val totalRain = forecast.sumOf { it.rainfallMm }
        val avgTemp = forecast.map { (it.tempHigh + it.tempLow) / 2 }.average()
        val avgHumidity = forecast.map { it.humidity }.average()

        val cropThresholds = CROP_THRESHOLDS[crop.lowercase()]
        val message = if (voice) {
            buildString {
                appendLine("🌾 *Hali ya Hewa ya Kilimo — ${location.name}*")
                appendLine("🌽 Mazao: $crop")
                appendLine()
                appendLine("📊 *Muhtasari wa wiki:*")
                appendLine("   Jumla mvua: ${totalRain.toInt()}mm")
                appendLine("   Joto wastani: ${avgTemp.toInt()}°C")
                appendLine("   Unyevu wastani: ${avgHumidity.toInt()}%")
                appendLine()

                // Temperature stress check
                cropThresholds?.let { ct ->
                    val coldDays = forecast.count { it.tempLow < ct.min_temp }
                    val hotDays = forecast.count { it.tempHigh > ct.max_temp }
                    val optimalDays = forecast.count { it.tempHigh in ct.optimal_temp_low..ct.optimal_temp_high }

                    if (coldDays > 0) {
                        appendLine("❄️ *Tahadhari ya baridi:* Siku $coldDays chini ya ${ct.min_temp}°C")
                    }
                    if (hotDays > 0) {
                        appendLine("🔥 *Tahadhari ya joto:* Siku $hotDays juu ya ${ct.max_temp}°C")
                    }
                    appendLine("✅ Siku $optimalDays za joto la $crop")
                    appendLine()
                }

                // Activity-specific advice
                val targetActivity = activity ?: detectActivity(crop, totalRain, avgTemp)
                when (targetActivity) {
                    "harvest" -> {
                        val dryDays = forecast.count { it.rainfallMm < 5 }
                        if (dryDays >= 3) {
                            appendLine("🌾 ✅ Wiki nzuri ya kuvuna! Siku $dryDays za kavu.")
                        } else {
                            appendLine("🌾 ⚠️ Mvua mingi. Vuna siku za kavu tu.")
                        }
                    }
                    "dry" -> {
                        val sunnyDays = forecast.count { it.rainfallMm < 0.5 && it.humidity < 60 }
                        if (sunnyDays >= 3) {
                            appendLine("☀️ ✅ Wiki nzuri ya kukausha!")
                        } else {
                            appendLine("☀️ ⚠️ Unyevu mwingi. Tumia jiko la kukaushia.")
                        }
                    }
                    "plant" -> {
                        val goodPlantDays = forecast.count { it.rainfallMm in 5.0..30.0 }
                        if (goodPlantDays >= 2) {
                            appendLine("🌱 ✅ Siku $goodPlantDays nzuri za kupanda.")
                        } else if (totalRain < 10) {
                            appendLine("🌱 ⚠️ Kavu sana. Subiri mvua au tumia umwagiliaji.")
                        } else {
                            appendLine("🌱 ⚠️ Mvua nyingi sana. Subiri ikomeshe.")
                        }
                    }
                    "spray" -> {
                        val sprayDays = forecast.count { it.rainfallMm < 2 && it.windSpeedKmh < 20 }
                        if (sprayDays >= 2) {
                            appendLine("🧴 ✅ Siku $sprayDays nzuri za kupulizia.")
                        } else {
                            appendLine("🧴 ⚠️ Mvua au upepo. Dawa itaoshwa.")
                        }
                    }
                    else -> {
                        when {
                            totalRain > 50 -> appendLine("💡 Mvua nyingi — epuka kazi za nje.")
                            totalRain < 10 -> appendLine("💡 Wiki kavu — nzuri kwa kukausha na kuvuna.")
                            else -> appendLine("💡 Wiki ya mchanganyiko — panga kulingana na siku.")
                        }
                    }
                }

                // Day-by-day breakdown
                appendLine()
                appendLine("📅 *Siku kwa siku:*")
                forecast.forEach { d ->
                    val cond = CONDITIONS_SW[d.condition] ?: d.condition
                    val rain = if (d.rainfallMm > 0) " 🌧️${d.rainfallMm.toInt()}mm" else ""
                    appendLine("   ${formatDateSw(d.date)}: $cond ${d.tempHigh.toInt()}°C$rain")
                }
            }
        } else {
            buildString {
                appendLine("Agricultural weather — ${location.name} ($crop):")
                appendLine("Total rain: ${totalRain.toInt()}mm | Avg temp: ${avgTemp.toInt()}°C | Humidity: ${avgHumidity.toInt()}%")
                forecast.forEach { d ->
                    appendLine("${d.date}: ${d.condition} ${d.tempHigh.toInt()}°C, ${d.rainfallMm.toInt()}mm rain")
                }
            }
        }

        return ToolResult.success(
            name,
            mapOf(
                "location" to location.name,
                "crop" to crop,
                "total_rain_mm" to totalRain,
                "avg_temp" to avgTemp,
                "avg_humidity" to avgHumidity,
                "forecast_days" to forecast.size
            ),
            message
        )
    }

    // ── ACTION: frost_risk ────────────────────────────────────────

    private suspend fun getFrostRisk(params: Map<String, String>): ToolResult {
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val location = resolveLocation(params)
        val crop = params["crop"] ?: "maize"

        val forecast = getCachedOrFetchForecast(location, 7)
            ?: return ToolResult.error(name, "No forecast data available", "NO_DATA")

        val frostDays = forecast.filter { it.tempLow < 5.0 }
        val nearFrostDays = forecast.filter { it.tempLow in 5.0..10.0 }
        val cropThresholds = CROP_THRESHOLDS[crop.lowercase()]

        val message = if (voice) {
            buildString {
                appendLine("❄️ *Tahadhari ya Baridi — ${location.name}*")
                appendLine("🌽 Mazao: $crop")
                appendLine()

                if (frostDays.isNotEmpty()) {
                    appendLine("🚨 *Hatari ya baridi!*")
                    frostDays.forEach { d ->
                        appendLine("   ❄️ ${formatDateSw(d.date)}: ${d.tempLow.toInt()}°C — ${d.conditionDetail}")
                    }
                    appendLine()
                    appendLine("⚠️ *Hatua za kuchukua:*")
                    appendLine("   • Funika mazao usiku kwa matandazo")
                    appendLine("   • Mwagilia udongo kabla ya baridi (hutoa joto)")
                    appendLine("   • Tumia mulch kuzuia baridi ya udongo")
                    appendLine("   • Epuka kupanda mazao nyeti wiki hii")
                } else if (nearFrostDays.isNotEmpty()) {
                    appendLine("⚠️ *Baridi karibu:*")
                    nearFrostDays.forEach { d ->
                        appendLine("   🌡️ ${formatDateSw(d.date)}: ${d.tempLow.toInt()}°C")
                    }
                    appendLine("   Tahadhari: fuatilia hali ya hewa usiku.")
                } else {
                    appendLine("✅ Hakuna hatari ya baridi wiki hii.")
                    appendLine("   Joto la chini: ${forecast.minOf { it.tempLow }.toInt()}°C")
                }
            }
        } else {
            buildString {
                appendLine("Frost risk — ${location.name}:")
                if (frostDays.isEmpty()) appendLine("No frost risk this week")
                frostDays.forEach { appendLine("FROST: ${it.date} ${it.tempLow}°C") }
            }
        }

        return ToolResult.success(
            name,
            mapOf(
                "location" to location.name,
                "frost_days" to frostDays.map { it.date },
                "near_frost_days" to nearFrostDays.map { it.date },
                "min_temp" to forecast.minOfOrNull { it.tempLow }
            ),
            message
        )
    }

    // ── ACTION: heat_stress ───────────────────────────────────────

    private suspend fun getHeatStress(params: Map<String, String>): ToolResult {
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val location = resolveLocation(params)
        val crop = params["crop"] ?: "maize"

        val forecast = getCachedOrFetchForecast(location, 7)
            ?: return ToolResult.error(name, "No forecast data available", "NO_DATA")

        val hotDays = forecast.filter { it.tempHigh > 35.0 }
        val extremeDays = forecast.filter { it.tempHigh > 40.0 }
        val heatIndex = forecast.map { d ->
            // Simplified heat index
            val hi = d.tempHigh + 0.5 * (d.humidity - 40)
            Pair(d.date, hi)
        }

        val message = if (voice) {
            buildString {
                appendLine("🔥 *Tahadhari ya Joto — ${location.name}*")
                appendLine("🌽 Mazao: $crop")
                appendLine()

                if (extremeDays.isNotEmpty()) {
                    appendLine("🚨 *Joto kali sana!*")
                    extremeDays.forEach { d ->
                        appendLine("   🔥 ${formatDateSw(d.date)}: ${d.tempHigh.toInt()}°C")
                    }
                    appendLine()
                    appendLine("⚠️ *Hatua za kuchukua:*")
                    appendLine("   • Mwagilia mazao asubuhi au jioni")
                    appendLine("   • Tumia mulch kuhifadhi unyevu")
                    appendLine("   • Epuka kufanya kazi za nje katikati ya jua")
                    appendLine("   • Hakikisha mifugo ina maji ya kutosha")
                } else if (hotDays.isNotEmpty()) {
                    appendLine("⚠️ *Joto kali:*")
                    hotDays.forEach { d ->
                        appendLine("   🌡️ ${formatDateSw(d.date)}: ${d.tempHigh.toInt()}°C")
                    }
                } else {
                    appendLine("✅ Joto ni la kawaida wiki hii.")
                    appendLine("   Joto la juu: ${forecast.maxOf { it.tempHigh }.toInt()}°C")
                }
            }
        } else {
            buildString {
                appendLine("Heat stress — ${location.name}:")
                if (hotDays.isEmpty()) appendLine("No heat stress risk")
                hotDays.forEach { appendLine("HOT: ${it.date} ${it.tempHigh}°C") }
            }
        }

        return ToolResult.success(
            name,
            mapOf(
                "location" to location.name,
                "hot_days" to hotDays.map { it.date },
                "extreme_days" to extremeDays.map { it.date },
                "max_temp" to forecast.maxOfOrNull { it.tempHigh },
                "heat_index" to heatIndex.toMap()
            ),
            message
        )
    }

    // ── ACTION: sync_forecasts ────────────────────────────────────

    private suspend fun syncForecasts(params: Map<String, String>): ToolResult {
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val location = resolveLocation(params)

        return try {
            val response = callForecastApi(location, 7)
            cacheForecast(location, response)

            val message = if (voice) {
                "✅ Data ya hali ya hewa imepakuliwa: ${location.name}\n" +
                "Siku ${response.size} za tabiri zimehifadhiwa kwa matumizi bila mtandao."
            } else {
                "Forecast synced: ${location.name} — ${response.size} days cached"
            }

            ToolResult.success(
                name,
                mapOf("location" to location.name, "days_cached" to response.size, "synced" to true),
                message
            )
        } catch (e: Exception) {
            ToolResult.error(name, "Sync failed: ${e.message}", "SYNC_FAILED")
        }
    }

    // ── ACTION: backend_health ────────────────────────────────────

    private suspend fun checkBackendHealth(params: Map<String, String>): ToolResult {
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val url = getBackendUrl()

        return try {
            val response = httpGet("$url/health")
            val json = JsonParser.parseString(response).asJsonObject
            val status = json.get("status")?.asString ?: "unknown"
            val gpuAvailable = json.get("gpu_available")?.asBoolean ?: false
            val gpuName = json.get("gpu_name")?.asString
            val earth2Available = json.get("earth2_available")?.asBoolean ?: false

            val message = if (voice) {
                buildString {
                    appendLine("🖥️ *Hali ya Server ya Hali ya Hewa*")
                    appendLine("   Status: $status")
                    appendLine("   Earth2Studio: ${if (earth2Available) "✅ Inapatikana" else "❌ Haipatikani"}")
                    appendLine("   GPU: ${if (gpuAvailable) "✅ ${gpuName ?: "Inapatikana"}" else "❌ Haipatikani"}")
                    appendLine("   URL: $url")
                }
            } else {
                "Backend health: status=$status, earth2=$earth2Available, gpu=$gpuAvailable ($gpuName)"
            }

            ToolResult.success(
                name,
                mapOf("status" to status, "earth2" to earth2Available, "gpu" to gpuAvailable, "gpu_name" to gpuName),
                message
            )
        } catch (e: Exception) {
            val message = if (voice) {
                "❌ Server ya hali ya hewa haipatikani.\nURL: $url\nKosa: ${e.message}"
            } else {
                "Backend unreachable: $url — ${e.message}"
            }
            ToolResult.error(name, message, "BACKEND_UNREACHABLE")
        }
    }

    // ── ACTION: set_backend_url ───────────────────────────────────

    private fun setBackendUrl(params: Map<String, String>): ToolResult {
        val url = params["backend_url"]
            ?: return ToolResult.error(name, "backend_url required", "MISSING_URL")

        val db = getDb()
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("key", "backend_url")
            put("value", url)
            put("updated_at", now)
        }
        db.insertWithOnConflict(TABLE_CONFIG, null, values, SQLiteDatabase.CONFLICT_REPLACE)

        return ToolResult.success(
            name,
            mapOf("backend_url" to url),
            "✅ Backend URL imewekwa: $url"
        )
    }

    // ── HTTP Client ───────────────────────────────────────────────

    private suspend fun httpGet(url: String): String = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("Accept", "application/json")

        try {
            if (connection.responseCode != 200) {
                throw RuntimeException("HTTP ${connection.responseCode}: ${connection.responseMessage}")
            }
            BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun httpPost(url: String, body: String): String = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json")
        connection.doOutput = true

        try {
            OutputStreamWriter(connection.outputStream).use { it.write(body) }
            if (connection.responseCode != 200) {
                val errorBody = BufferedReader(InputStreamReader(connection.errorStream ?: connection.inputStream)).use { it.readText() }
                throw RuntimeException("HTTP ${connection.responseCode}: $errorBody")
            }
            BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    // ── API Callers ───────────────────────────────────────────────

    private suspend fun callForecastApi(
        location: LocationInfo,
        days: Int
    ): List<ForecastDayData> {
        val url = "${getBackendUrl()}/forecast/deterministic"
        val body = gson.toJson(mapOf(
            "latitude" to location.lat,
            "longitude" to location.lon,
            "location_name" to location.name,
            "forecast_hours" to (days * 24),
            "model_name" to "fourcastnet"
        ))

        val response = httpPost(url, body)
        return parseForecastResponse(response)
    }

    private suspend fun callCountyForecastApi(
        county: CountyInfo,
        crop: String?,
        activity: String?
    ): List<ForecastDayData> {
        val url = "${getBackendUrl()}/forecast/county"
        val body = gson.toJson(buildMap {
            put("county", county.name.lowercase().replace(" ", "_"))
            put("forecast_hours", 168)
            crop?.let { put("crop", it) }
            activity?.let { put("activity", it) }
        })

        val response = httpPost(url, body)
        return parseForecastResponse(response)
    }

    private fun parseForecastResponse(json: String): List<ForecastDayData> {
        val obj = JsonParser.parseString(json).asJsonObject
        val daysArray = obj.getAsJsonArray("forecast_days")

        return daysArray.map { element ->
            val day = element.asJsonObject
            ForecastDayData(
                date = day.get("date").asString,
                tempHigh = day.get("temp_high").asDouble,
                tempLow = day.get("temp_low").asDouble,
                humidity = day.get("humidity").asDouble,
                rainfallMm = day.get("rainfall_mm").asDouble,
                rainProbability = day.get("rain_probability").asDouble,
                windSpeedKmh = day.get("wind_speed_kmh").asDouble,
                windDirection = day.get("wind_direction").asString,
                cloudCoverPct = day.get("cloud_cover_pct").asDouble,
                uvIndex = day.get("uv_index").asDouble,
                condition = day.get("condition").asString,
                conditionDetail = day.get("condition_detail").asString
            )
        }
    }

    // ── Cache Operations ──────────────────────────────────────────

    private fun cacheForecast(location: LocationInfo, days: List<ForecastDayData>) {
        val db = getDb()
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("location", location.name)
            put("latitude", location.lat)
            put("longitude", location.lon)
            put("county", location.county)
            put("forecast_json", gson.toJson(days))
            put("model", "earth2studio")
            put("source", "earth2studio")
            put("cached_at", now)
            put("expires_at", now + CACHE_DURATION_MS)
        }
        db.insert(TABLE_CACHE, null, values)

        // Also update WeatherCacheManager's cache
        updateWeatherCacheManager(location, days)

        Timber.d("Cached forecast for ${location.name}: ${days.size} days")
    }

    private fun getCachedForecast(location: String, days: Int): List<ForecastDayData>? {
        val db = getDb()
        val now = System.currentTimeMillis()
        val cursor = db.query(
            TABLE_CACHE, arrayOf("forecast_json"),
            "location = ? AND expires_at > ?",
            arrayOf(location, now.toString()),
            null, null, "cached_at DESC", "1"
        )
        cursor.use {
            if (it.moveToFirst()) {
                val json = it.getString(0)
                val type = object : TypeToken<List<ForecastDayData>>() {}.type
                return gson.fromJson(json, type)
            }
        }
        return null
    }

    private fun getStaleCachedForecast(location: String): List<ForecastDayData>? {
        val db = getDb()
        val cursor = db.query(
            TABLE_CACHE, arrayOf("forecast_json"),
            "location = ?",
            arrayOf(location),
            null, null, "cached_at DESC", "1"
        )
        cursor.use {
            if (it.moveToFirst()) {
                val json = it.getString(0)
                val type = object : TypeToken<List<ForecastDayData>>() {}.type
                return gson.fromJson(json, type)
            }
        }
        return null
    }

    private suspend fun getCachedOrFetchForecast(
        location: LocationInfo,
        days: Int
    ): List<ForecastDayData>? {
        // Try fresh cache
        getCachedForecast(location.name, days)?.let { return it }

        // Try fetching
        return try {
            val response = callForecastApi(location, days)
            cacheForecast(location, response)
            response
        } catch (e: Exception) {
            // Try stale cache
            getStaleCachedForecast(location.name)
        }
    }

    // ── WeatherCacheManager Integration ───────────────────────────

    private fun updateWeatherCacheManager(location: LocationInfo, days: List<ForecastDayData>) {
        try {
            // Get WeatherCacheManager's database
            val field = weatherCacheManager.javaClass.getDeclaredField("dbHelper")
            field.isAccessible = true
            val dbHelper = field.get(weatherCacheManager) as? android.database.sqlite.SQLiteOpenHelper
                ?: return
            val db = dbHelper.writableDatabase

            val now = System.currentTimeMillis()
            days.forEach { day ->
                val values = ContentValues().apply {
                    put("location", location.name)
                    put("latitude", location.lat)
                    put("longitude", location.lon)
                    put("forecast_date", day.date)
                    put("temp_high", day.tempHigh)
                    put("temp_low", day.tempLow)
                    put("humidity", day.humidity)
                    put("rainfall_mm", day.rainfallMm)
                    put("rain_probability", day.rainProbability)
                    put("wind_speed_kmh", day.windSpeedKmh)
                    put("wind_direction", day.windDirection)
                    put("cloud_cover_pct", day.cloudCoverPct)
                    put("uv_index", day.uvIndex)
                    put("condition", day.condition)
                    put("condition_detail", day.conditionDetail)
                    put("cached_at", now)
                    put("expires_at", now + CACHE_DURATION_MS)
                    put("source", "earth2studio")
                }
                db.insertWithOnConflict("forecasts", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            Timber.d("Updated WeatherCacheManager with ${days.size} days for ${location.name}")
        } catch (e: Exception) {
            Timber.w(e, "Failed to update WeatherCacheManager cache")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────

    data class LocationInfo(
        val name: String,
        val lat: Double,
        val lon: Double,
        val county: String? = null
    )

    private fun resolveLocation(params: Map<String, String>): LocationInfo {
        // GPS coordinates
        val lat = params["latitude"]?.toDoubleOrNull()
        val lon = params["longitude"]?.toDoubleOrNull()
        if (lat != null && lon != null) {
            return LocationInfo(params["location"] ?: "Shamba", lat, lon)
        }

        // County name
        params["county"]?.let { countyName ->
            KENYA_COUNTIES[countyName.lowercase().replace(" ", "_").replace("-", "_")]?.let {
                return LocationInfo(it.name, it.lat, it.lon, countyName)
            }
        }

        // Location name
        params["location"]?.let { loc ->
            KENYA_COUNTIES[loc.lowercase().replace(" ", "_").replace("-", "_")]?.let {
                return LocationInfo(it.name, it.lat, it.lon, loc)
            }
        }

        // Default: Nakuru
        return LocationInfo("Nakuru", -0.3031, 36.0800, "nakuru")
    }

    private fun detectActivity(crop: String, totalRain: Double, avgTemp: Double): String {
        val month = Calendar.getInstance().get(Calendar.MONTH) + 1
        return when {
            totalRain < 10 -> "dry"
            totalRain > 40 -> "harvest"
            month in listOf(3, 4, 10) -> "plant"
            else -> "general"
        }
    }

    private fun formatDateSw(dateStr: String): String {
        return try {
            val input = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val output = SimpleDateFormat("EEEE dd MMM", Locale("sw"))
            output.format(input.parse(dateStr)!!)
        } catch (e: Exception) {
            dateStr
        }
    }

    // ── Response Formatters ───────────────────────────────────────

    private fun formatForecastResponse(
        days: List<ForecastDayData>,
        location: LocationInfo,
        voice: Boolean,
        crop: String?,
        activity: String?,
        isStale: Boolean = false
    ): ToolResult {
        val message = if (voice) {
            buildString {
                if (isStale) appendLine("⚠️ *Data ya zamani — pakua data mpya ukiwa na mtandao.*")
                appendLine("🌤️ *Tabiri ya Hali ya Hewa — ${location.name}*")
                appendLine("📅 Siku ${days.size}")
                appendLine()
                days.forEach { d ->
                    val cond = CONDITIONS_SW[d.condition] ?: d.condition
                    val rain = if (d.rainfallMm > 0) " 🌧️${d.rainfallMm.toInt()}mm" else ""
                    appendLine("${formatDateSw(d.date)}: $cond ${d.tempHigh.toInt()}°C/${
                        d.tempLow.toInt()
                    }°C$rain")
                }
            }
        } else {
            buildString {
                appendLine("Forecast — ${location.name} (${days.size} days):")
                days.forEach { d ->
                    appendLine("${d.date}: ${d.condition} ${d.tempHigh}°C/${d.tempLow}°C, rain ${d.rainfallMm}mm")
                }
            }
        }

        return ToolResult.success(
            name,
            mapOf(
                "location" to location.name,
                "latitude" to location.lat,
                "longitude" to location.lon,
                "forecast_days" to days.size,
                "stale" to isStale
            ),
            message
        )
    }

    private fun formatCountyResponse(
        days: List<ForecastDayData>,
        county: CountyInfo,
        voice: Boolean,
        crop: String?,
        activity: String?
    ): ToolResult {
        val advisory = generateLocalAdvisory(days, crop, activity)

        val message = if (voice) {
            buildString {
                appendLine("🌾 *Hali ya Hewa ya Kilimo — ${county.name}*")
                crop?.let { appendLine("🌽 Mazao: $it") }
                appendLine()
                days.forEach { d ->
                    val cond = CONDITIONS_SW[d.condition] ?: d.condition
                    val rain = if (d.rainfallMm > 0) " 🌧️${d.rainfallMm.toInt()}mm" else ""
                    appendLine("${formatDateSw(d.date)}: $cond ${d.tempHigh.toInt()}°C$rain")
                }
                appendLine()
                appendLine(advisory)
            }
        } else {
            buildString {
                appendLine("County forecast — ${county.name}:")
                days.forEach { d ->
                    appendLine("${d.date}: ${d.condition} ${d.tempHigh}°C, ${d.rainfallMm}mm")
                }
                appendLine()
                appendLine(advisory)
            }
        }

        return ToolResult.success(
            name,
            mapOf(
                "county" to county.name,
                "latitude" to county.lat,
                "longitude" to county.lon,
                "forecast_days" to days.size,
                "crop" to crop
            ),
            message
        )
    }

    private fun generateLocalAdvisory(
        days: List<ForecastDayData>,
        crop: String?,
        activity: String?
    ): String {
        val totalRain = days.sumOf { it.rainfallMm }
        val rainyDays = days.count { it.rainfallMm > 5 }

        val targetActivity = activity ?: detectActivity(crop ?: "maize", totalRain, 25.0)

        return when (targetActivity) {
            "harvest" -> {
                val dryDays = days.count { it.rainfallMm < 5 }
                if (dryDays >= 3) "🌾 ✅ Wiki nzuri ya kuvuna! Siku $dryDays za kavu."
                else "🌾 ⚠️ Mvua mingi. Vuna siku za kavu tu."
            }
            "dry" -> {
                val sunnyDays = days.count { it.rainfallMm < 0.5 && it.humidity < 60 }
                if (sunnyDays >= 3) "☀️ ✅ Wiki nzuri ya kukausha mazao!"
                else "☀️ ⚠️ Unyevu mwingi. Tumia jiko la kukaushia."
            }
            "plant" -> {
                val goodDays = days.count { it.rainfallMm in 5.0..30.0 }
                if (goodDays >= 2) "🌱 ✅ Siku $goodDays nzuri za kupanda."
                else if (totalRain < 10) "🌱 ⚠️ Kavu sana. Subiri mvua."
                else "🌱 ⚠️ Mvua nyingi. Subiri ikomeshe."
            }
            "spray" -> {
                val sprayDays = days.count { it.rainfallMm < 2 && it.windSpeedKmh < 20 }
                if (sprayDays >= 2) "🧴 ✅ Siku $sprayDays nzuri za kupulizia."
                else "🧴 ⚠️ Mvua au upepo. Dawa itaoshwa."
            }
            else -> when {
                totalRain > 50 -> "💡 Mvua nyingi — epuka kazi za nje."
                totalRain < 10 -> "💡 Wiki kavu — nzuri kwa kukausha na kuvuna."
                else -> "💡 Wiki ya mchanganyiko — panga kulingana na siku."
            }
        }
    }
}
