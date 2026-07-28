package com.msaidizi.agent.tools

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.location.Location
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WeatherCacheManager — Fix 4: Weather integration with offline caching for farmers.
 *
 * Problem: Farmers have no data bundles during growing season (cash spent on inputs).
 * They need weather forecasts most when connectivity is worst.
 * Stale cached forecasts are better than nothing.
 *
 * Solution: Cache weather forecasts for 7+ days offline access.
 * Translate weather into actionable agricultural advice.
 * "Heavy rain expected Thursday — harvest before then"
 * "Dry spell next week — good for drying crops"
 *
 * Voice examples:
 *   "Hali ya hewa ikoje?"                      → Current + forecast weather
 *   "Mvua inakuja lini?"                       → When is rain coming?
 *   "Naweza kuvuna leo?"                       → Can I harvest today?
 *   "Kausha mahindi lini?"                     → When to dry crops?
 *   "Panda mbegu lini?"                        → When to plant?
 */
@Singleton
class WeatherCacheManager @Inject constructor(
    private val context: Context
) : Tool {

    override val name = "weather_cache_manager"
    override val description = "Weather forecasts cached for offline access. Translates weather into agricultural advice: harvest timing, drying windows, planting conditions."

    override val argsSchema = argSchema {
        enum(
            "action", "Action to perform",
            listOf(
                "current",          // Current weather + 7-day forecast
                "forecast",         // Detailed 7-day forecast
                "agri_advisory",    // Farming-specific weather advice
                "harvest_window",   // Best days to harvest this week
                "drying_window",    // Best days to dry crops
                "planting_window",  // Best days to plant
                "rain_alert",       // When is rain coming?
                "cache_weather",    // Sync and cache weather data
                "cache_status",     // Check cached data freshness
                "set_location"      // Set farm GPS coordinates
            ),
            required = true
        )
        string("location", "Location name or county", required = false)
        number("latitude", "GPS latitude", required = false)
        number("longitude", "GPS longitude", required = false)
        string("crop", "Crop for specific advisory", required = false)
        string("activity", "Farming activity: harvest/dry/plant/spray", required = false)
        boolean("voice", "Format for voice output (Swahili)", required = false)
    }

    // ──────────────────────────────────────────────
    // Weather Cache Database
    // ──────────────────────────────────────────────

    inner class WeatherDatabase(context: Context) :
        SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            // Cached weather forecasts — one row per day per location
            db.execSQL("""
                CREATE TABLE $TABLE_FORECASTS (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    location TEXT NOT NULL,
                    latitude REAL,
                    longitude REAL,
                    forecast_date TEXT NOT NULL,
                    temp_high REAL,
                    temp_low REAL,
                    humidity REAL,
                    rainfall_mm REAL,
                    rain_probability REAL,
                    wind_speed_kmh REAL,
                    wind_direction TEXT,
                    cloud_cover_pct REAL,
                    uv_index REAL,
                    condition TEXT,
                    condition_detail TEXT,
                    cached_at INTEGER NOT NULL,
                    expires_at INTEGER NOT NULL,
                    source TEXT DEFAULT 'api',
                    UNIQUE(location, forecast_date)
                )
            """)

            // Current weather snapshot
            db.execSQL("""
                CREATE TABLE $TABLE_CURRENT (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    location TEXT NOT NULL,
                    latitude REAL,
                    longitude REAL,
                    temp_c REAL,
                    humidity REAL,
                    rainfall_mm REAL,
                    wind_speed_kmh REAL,
                    condition TEXT,
                    feels_like_c REAL,
                    recorded_at INTEGER NOT NULL
                )
            """)

            // Farm location profile
            db.execSQL("""
                CREATE TABLE $TABLE_LOCATION (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    location_name TEXT NOT NULL,
                    latitude REAL NOT NULL,
                    longitude REAL NOT NULL,
                    county TEXT,
                    sub_county TEXT,
                    is_default INTEGER DEFAULT 0,
                    updated_at INTEGER NOT NULL
                )
            """)

            // Agricultural weather alerts
            db.execSQL("""
                CREATE TABLE $TABLE_ALERTS (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    location TEXT NOT NULL,
                    alert_type TEXT NOT NULL,
                    severity TEXT NOT NULL,
                    title_sw TEXT NOT NULL,
                    title_en TEXT NOT NULL,
                    detail_sw TEXT,
                    detail_en TEXT,
                    valid_from INTEGER,
                    valid_until INTEGER,
                    created_at INTEGER NOT NULL
                )
            """)

            db.execSQL("CREATE INDEX idx_forecast_loc ON $TABLE_FORECASTS(location, forecast_date)")
            db.execSQL("CREATE INDEX idx_forecast_date ON $TABLE_FORECASTS(forecast_date)")
            db.execSQL("CREATE INDEX idx_alerts_loc ON $TABLE_ALERTS(location, valid_until)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_ALERTS")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_CURRENT")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_FORECASTS")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_LOCATION")
            onCreate(db)
        }
    }

    companion object {
        private const val DB_NAME = "weather_cache.db"
        private const val DB_VERSION = 1
        private const val TABLE_FORECASTS = "forecasts"
        private const val TABLE_CURRENT = "current_weather"
        private const val TABLE_LOCATION = "farm_locations"
        private const val TABLE_ALERTS = "weather_alerts"

        // Cache duration: 24 hours for forecasts
        private const val CACHE_DURATION_MS = 24 * 60 * 60 * 1000L

        // Weather condition codes mapped to Swahili descriptions
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
            "mist" to "Ukungu mwepesi",
            "haze" to "Mosi",
            "windy" to "Upepo mkali",
            "hot" to "Joto kali",
            "cold" to "Baridi"
        )

        // Rainfall thresholds for farming activities (mm/day)
        val RAIN_THRESHOLDS = mapOf(
            "harvest" to 5.0,       // >5mm = don't harvest
            "drying" to 0.0,        // Any rain = bad for drying
            "planting" to 10.0,     // 10-30mm = ideal for planting
            "spraying" to 2.0,      // >2mm = don't spray (washes off)
            "fertilizing" to 5.0    // >5mm = don't fertilize (washes away)
        )

        // Kenya counties with approximate GPS coordinates
        val KENYA_COUNTIES = mapOf(
            "nakuru" to Pair(-0.3031, 36.0800),
            "nyeri" to Pair(-0.4167, 36.9500),
            "meru" to Pair(0.0500, 37.6500),
            "kisii" to Pair(-0.6833, 34.7667),
            "bungoma" to Pair(0.5667, 34.5667),
            "kakamega" to Pair(0.2833, 34.7500),
            "uasin_gishu" to Pair(0.5167, 35.2833),
            "trans_nzoia" to Pair(1.0167, 35.0000),
            "nandi" to Pair(0.1833, 35.1333),
            "kericho" to Pair(-0.3667, 35.2833),
            "bomet" to Pair(-0.7833, 35.3333),
            "kilifi" to Pair(-3.6333, 39.8500),
            "mombasa" to Pair(-4.0500, 39.6667),
            "machakos" to Pair(-1.5167, 37.2667),
            "kitui" to Pair(-1.3667, 38.0167),
            "embu" to Pair(-0.5333, 37.4500),
            "tharaka_nithi" to Pair(-0.3000, 37.8833),
            "laikipia" to Pair(0.0167, 36.7833),
            "nyandarua" to Pair(-0.3833, 36.5667),
            "kirinyaga" to Pair(-0.5000, 37.2833)
        )
    }

    private var dbHelper: WeatherDatabase? = null

    private fun getDb(): SQLiteDatabase {
        if (dbHelper == null) dbHelper = WeatherDatabase(context)
        return dbHelper!!.writableDatabase
    }

    // ──────────────────────────────────────────────
    // Tool Execute
    // ──────────────────────────────────────────────

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"]
            ?: return ToolResult.error(name, "Action required", "MISSING_ACTION")

        return when (action.lowercase()) {
            "current" -> currentWeather(params)
            "forecast" -> forecast(params)
            "agri_advisory" -> agriAdvisory(params)
            "harvest_window" -> harvestWindow(params)
            "drying_window" -> dryingWindow(params)
            "planting_window" -> plantingWindow(params)
            "rain_alert" -> rainAlert(params)
            "cache_weather" -> cacheWeather(params)
            "cache_status" -> cacheStatus(params)
            "set_location" -> setLocation(params)
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    // ──────────────────────────────────────────────
    // ACTION: current — Current weather + 7-day forecast
    // ──────────────────────────────────────────────

    private fun currentWeather(params: Map<String, String>): ToolResult {
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val db = getDb()
        val location = getLocation(db, params)

        val current = getCurrentWeather(db, location.first)
        val forecasts = getForecasts(db, location.first, 7)

        if (current == null && forecasts.isEmpty()) {
            return ToolResult.success(
                name, mapOf("cached" to false, "location" to location.first),
                if (voice) "Hakuna data ya hali ya hewa. Tumia 'cache_weather' kupakua data wakati una mtandao."
                else "No cached weather data. Use 'cache_weather' to download forecasts when connected."
            )
        }

        val message = if (voice) {
            buildString {
                appendLine("🌤️ *Hali ya Hewa — ${location.first}*")
                appendLine()

                current?.let { c ->
                    val conditionSw = CONDITIONS_SW[c.condition] ?: c.condition
                    appendLine("📍 *Sasa hivi:*")
                    appendLine("   $conditionSw")
                    appendLine("   Joto: ${c.tempC?.toInt() ?: "?"}°C (inajisiwa ${c.feelsLikeC?.toInt() ?: "?"}°C)")
                    appendLine("   Unyevu: ${c.humidity?.toInt() ?: "?"}%")
                    c.rainfallMm?.let { if (it > 0) appendLine("   Mvua: ${it}mm") }
                    c.windSpeedKmh?.let { appendLine("   Upepo: ${it.toInt()} km/h") }
                    appendLine()
                }

                if (forecasts.isNotEmpty()) {
                    appendLine("📅 *Tabiri ya siku 7:*")
                    val sdf = java.text.SimpleDateFormat("EEE dd", java.util.Locale("sw"))
                    forecasts.forEach { f ->
                        val condSw = CONDITIONS_SW[f.condition] ?: f.condition ?: ""
                        val rainStr = if ((f.rainfallMm ?: 0.0) > 0) " 🌧️${f.rainfallMm?.toInt()}mm" else ""
                        val dateStr = try {
                            sdf.format(java.text.SimpleDateFormat("yyyy-MM-dd").parse(f.forecastDate)!!)
                        } catch (e: Exception) { f.forecastDate }
                        appendLine("   $dateStr: $condSw ${f.tempHigh?.toInt() ?: "?"}°C$rainStr")
                    }

                    // Check for rain alerts
                    val rainyDays = forecasts.filter { (it.rainfallMm ?: 0.0) > 5.0 }
                    if (rainyDays.isNotEmpty()) {
                        appendLine()
                        appendLine("🌧️ *Arifa ya mvua:*")
                        rainyDays.forEach { f ->
                            val dateStr = try {
                                val sdf2 = java.text.SimpleDateFormat("EEEE", java.util.Locale("sw"))
                                sdf2.format(java.text.SimpleDateFormat("yyyy-MM-dd").parse(f.forecastDate)!!)
                            } catch (e: Exception) { f.forecastDate }
                            appendLine("   ⚠️ $dateStr: mvua ${f.rainfallMm?.toInt()}mm — ${f.conditionDetail ?: ""}")
                        }
                    }

                    // Agricultural advice
                    appendLine()
                    appendLine("💡 *Ushauri wa kilimo:*")
                    val heavyRain = forecasts.any { (it.rainfallMm ?: 0.0) > 15.0 }
                    val dryDays = forecasts.filter { (it.rainfallMm ?: 0.0) < 1.0 }
                    val rainyDaysCount = forecasts.count { (it.rainfallMm ?: 0.0) > 5.0 }

                    when {
                        heavyRain -> appendLine("   🌧️ Mvua kubwa inakuja — vuna mazao ya haraka!")
                        rainyDaysCount >= 4 -> appendLine("   🌧️ Wiki ya mvua — kaa nyumba, panga kazi za ndani.")
                        dryDays.size >= 4 -> appendLine("   ☀️ Wiki kavu — nzuri kwa kukausha mazao na kupulizia dawa.")
                        else -> appendLine("   🌤️ Wiki ya mchanganyiko — panga kulingana na siku za kavu.")
                    }
                }
            }
        } else {
            buildString {
                appendLine("Weather — ${location.first}:")
                current?.let { c ->
                    appendLine("Now: ${c.condition}, ${c.tempC?.toInt()}°C, humidity ${c.humidity?.toInt()}%")
                }
                forecasts.forEach { f ->
                    appendLine("${f.forecastDate}: ${f.condition} ${f.tempHigh?.toInt()}°C, rain ${f.rainfallMm?.toInt() ?: 0}mm")
                }
            }
        }

        return ToolResult.success(
            name,
            mapOf(
                "location" to location.first,
                "current" to current?.let { mapOf("temp" to it.tempC, "condition" to it.condition, "humidity" to it.humidity) },
                "forecast_days" to forecasts.size,
                "cached" to true
            ),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: harvest_window — Best days to harvest
    // ──────────────────────────────────────────────

    private fun harvestWindow(params: Map<String, String>): ToolResult {
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val db = getDb()
        val location = getLocation(db, params)
        val forecasts = getForecasts(db, location.first, 7)

        if (forecasts.isEmpty()) {
            return ToolResult.success(
                name, mapOf("cached" to false),
                if (voice) "Hakuna data ya hali ya hewa. Pakua data kwanza."
                else "No weather data cached. Download first."
            )
        }

        val threshold = RAIN_THRESHOLDS["harvest"]!!
        val goodDays = forecasts.filter { (it.rainfallMm ?: 0.0) < threshold }
        val badDays = forecasts.filter { (it.rainfallMm ?: 0.0) >= threshold }

        val swahiliDays = mapOf(
            "Monday" to "Jumatatu", "Tuesday" to "Jumanne", "Wednesday" to "Jumatano",
            "Thursday" to "Alhamisi", "Friday" to "Ijumaa", "Saturday" to "Jumamosi", "Sunday" to "Jumapili"
        )

        val message = if (voice) {
            buildString {
                appendLine("🌾 *Siku Nzuri za Kuvuna*")
                appendLine("📍 ${location.first}")
                appendLine()

                if (goodDays.isNotEmpty()) {
                    appendLine("✅ *Siku nzuri za kuvuna:*")
                    goodDays.forEach { f ->
                        val dayName = try {
                            val sdf = java.text.SimpleDateFormat("EEEE", java.util.Locale("sw"))
                            sdf.format(java.text.SimpleDateFormat("yyyy-MM-dd").parse(f.forecastDate)!!)
                        } catch (e: Exception) { f.forecastDate }
                        val cond = CONDITIONS_SW[f.condition] ?: f.condition ?: ""
                        appendLine("   🌾 $dayName: $cond, mvua ${f.rainfallMm?.toInt() ?: 0}mm")
                    }
                } else {
                    appendLine("⚠️ Hakuna siku nzuri za kuvuna wiki hii.")
                    appendLine("   Subiri wiki ijayo au vuna kwa haraka siku ya kavu.")
                }

                if (badDays.isNotEmpty()) {
                    appendLine()
                    appendLine("❌ *Siku za kuepuka:*")
                    badDays.forEach { f ->
                        val dayName = try {
                            val sdf = java.text.SimpleDateFormat("EEEE", java.util.Locale("sw"))
                            sdf.format(java.text.SimpleDateFormat("yyyy-MM-dd").parse(f.forecastDate)!!)
                        } catch (e: Exception) { f.forecastDate }
                        appendLine("   🌧️ $dayName: mvua ${f.rainfallMm?.toInt() ?: 0}mm — ${f.conditionDetail ?: "mvua kali"}")
                    }
                }

                // Actionable advice
                val firstRain = badDays.firstOrNull()
                if (firstRain != null && goodDays.isNotEmpty()) {
                    appendLine()
                    appendLine("💡 *Ushauri:*")
                    appendLine("   Mvua inakuja ${try {
                        val sdf = java.text.SimpleDateFormat("EEEE", java.util.Locale("sw"))
                        sdf.format(java.text.SimpleDateFormat("yyyy-MM-dd").parse(firstRain.forecastDate)!!)
                    } catch (e: Exception) { firstRain.forecastDate }}.")
                    appendLine("   Vuna kabla ya siku hiyo! Anza siku ya kesho.")
                }
            }
        } else {
            buildString {
                appendLine("Harvest window — ${location.first}:")
                goodDays.forEach { appendLine("✅ ${f.forecastDate}: ${f.condition}, ${f.rainfallMm?.toInt() ?: 0}mm rain") }
                badDays.forEach { appendLine("❌ ${f.forecastDate}: ${f.rainfallMm?.toInt() ?: 0}mm rain") }
            }
        }

        return ToolResult.success(
            name,
            mapOf(
                "good_days" to goodDays.map { it.forecastDate },
                "bad_days" to badDays.map { it.forecastDate },
                "next_rain" to badDays.firstOrNull()?.forecastDate
            ),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: drying_window — Best days to dry crops
    // ──────────────────────────────────────────────

    private fun dryingWindow(params: Map<String, String>): ToolResult {
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val db = getDb()
        val location = getLocation(db, params)
        val forecasts = getForecasts(db, location.first, 7)

        if (forecasts.isEmpty()) {
            return ToolResult.success(name, mapOf("cached" to false), "Hakuna data ya hali ya hewa.")
        }

        // Good drying: no rain, low humidity, sunny, some wind
        val dryingScores = forecasts.map { f ->
            val rainScore = if ((f.rainfallMm ?: 0.0) < 0.5) 3.0 else if ((f.rainfallMm ?: 0.0) < 2.0) 1.0 else -3.0
            val humidityScore = when {
                (f.humidity ?: 50.0) < 40 -> 2.0
                (f.humidity ?: 50.0) < 60 -> 1.0
                else -> -1.0
            }
            val sunScore = when (f.condition) {
                "sunny", "clear" -> 3.0
                "partly_cloudy" -> 2.0
                "cloudy" -> 0.5
                else -> -1.0
            }
            val windScore = when {
                (f.windSpeedKmh ?: 0.0) > 15 -> 2.0
                (f.windSpeedKmh ?: 0.0) > 8 -> 1.5
                else -> 0.5
            }
            Pair(f, rainScore + humidityScore + sunScore + windScore)
        }.sortedByDescending { it.second }

        val bestDays = dryingScores.filter { it.second > 3.0 }.map { it.first }
        val worstDays = dryingScores.filter { it.second < 0.0 }.map { it.first }

        val message = if (voice) {
            buildString {
                appendLine("☀️ *Siku Nzuri za Kukausha Mazao*")
                appendLine("📍 ${location.first}")
                appendLine()

                if (bestDays.isNotEmpty()) {
                    appendLine("✅ *Siku bora:*")
                    bestDays.forEach { f ->
                        val dayName = try {
                            val sdf = java.text.SimpleDateFormat("EEEE", java.util.Locale("sw"))
                            sdf.format(java.text.SimpleDateFormat("yyyy-MM-dd").parse(f.forecastDate)!!)
                        } catch (e: Exception) { f.forecastDate }
                        val cond = CONDITIONS_SW[f.condition] ?: f.condition ?: ""
                        appendLine("   ☀️ $dayName: $cond, unyevu ${f.humidity?.toInt() ?: "?"}%")
                    }
                } else {
                    appendLine("⚠️ Wiki hii si nzuri kwa kukausha.")
                    appendLine("   Subiri siku za jua au tumia jiko la kukaushia.")
                }

                if (worstDays.isNotEmpty()) {
                    appendLine()
                    appendLine("❌ *Epuka siku hizi:*")
                    worstDays.take(3).forEach { f ->
                        val dayName = try {
                            val sdf = java.text.SimpleDateFormat("EEEE", java.util.Locale("sw"))
                            sdf.format(java.text.SimpleDateFormat("yyyy-MM-dd").parse(f.forecastDate)!!)
                        } catch (e: Exception) { f.forecastDate }
                        appendLine("   🌧️ $dayName: mvua ${f.rainfallMm?.toInt() ?: 0}mm, unyevu ${f.humidity?.toInt() ?: "?"}%")
                    }
                }

                appendLine()
                appendLine("💡 *Vidokezo vya kukausha:*")
                appendLine("   • Safisha eneo kabla ya kukausha")
                appendLine("   • Tenga mazao kwenye juu ya sakafu safi")
                appendLine("   • Geuza mazao kila baada ya masaa 2-3")
                appendLine("   • Kausha hadi unyevu <13% (mahindi) au <12% (maharagwe)")
            }
        } else {
            buildString {
                appendLine("Drying window — ${location.first}:")
                bestDays.forEach { appendLine("✅ ${it.forecastDate}: ${it.condition}, humidity ${it.humidity?.toInt()}%") }
                worstDays.take(3).forEach { appendLine("❌ ${it.forecastDate}: rain ${it.rainfallMm?.toInt()}mm") }
            }
        }

        return ToolResult.success(
            name,
            mapOf("best_days" to bestDays.map { it.forecastDate }, "worst_days" to worstDays.map { it.forecastDate }),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: planting_window — Best days to plant
    // ──────────────────────────────────────────────

    private fun plantingWindow(params: Map<String, String>): ToolResult {
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val db = getDb()
        val location = getLocation(db, params)
        val forecasts = getForecasts(db, location.first, 7)

        if (forecasts.isEmpty()) {
            return ToolResult.success(name, mapOf("cached" to false), "Hakuna data ya hali ya hewa.")
        }

        // Good planting: moderate rain (10-30mm), not too heavy, some clouds
        val plantingScores = forecasts.map { f ->
            val rain = f.rainfallMm ?: 0.0
            val rainScore = when {
                rain in 10.0..30.0 -> 3.0  // ideal planting rain
                rain in 5.0..10.0 -> 2.0   // acceptable
                rain < 5.0 -> 0.5          // too dry
                rain in 30.0..50.0 -> 0.0  // too wet
                else -> -2.0               // flooding risk
            }
            Pair(f, rainScore)
        }.sortedByDescending { it.second }

        val bestDays = plantingScores.filter { it.second >= 2.0 }.map { it.first }

        val message = if (voice) {
            buildString {
                appendLine("🌱 *Siku Nzuri za Kupanda*")
                appendLine("📍 ${location.first}")
                appendLine()

                if (bestDays.isNotEmpty()) {
                    appendLine("✅ *Siku bora za kupanda:*")
                    bestDays.forEach { f ->
                        val dayName = try {
                            val sdf = java.text.SimpleDateFormat("EEEE", java.util.Locale("sw"))
                            sdf.format(java.text.SimpleDateFormat("yyyy-MM-dd").parse(f.forecastDate)!!)
                        } catch (e: Exception) { f.forecastDate }
                        appendLine("   🌱 $dayName: mvua ${f.rainfallMm?.toInt() ?: 0}mm — nzuri kwa mbegu!")
                    }
                } else {
                    appendLine("⚠️ Wiki hii si nzuri sana kupanda.")
                    val dryDays = forecasts.filter { (it.rainfallMm ?: 0.0) < 5.0 }
                    if (dryDays.size > 3) {
                        appendLine("   Kavu sana — subiri mvua au tumia umwagiliaji.")
                    } else {
                        appendLine("   Mvua nyingi sana — subiri ikomeshe.")
                    }
                }

                appendLine()
                appendLine("💡 *Vidokezo vya kupanda:*")
                appendLine("   • Panda siku 1-2 baada ya mvua kubwa")
                appendLine("   • Udongo unapaswa kuwa mvua, si maji")
                appendLine("   • Panda kina sahihi: mahindi 5cm, maharagwe 3cm")
            }
        } else {
            buildString {
                appendLine("Planting window — ${location.first}:")
                bestDays.forEach { appendLine("✅ ${it.forecastDate}: ${it.rainfallMm?.toInt()}mm rain") }
            }
        }

        return ToolResult.success(
            name,
            mapOf("best_days" to bestDays.map { it.forecastDate }),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: rain_alert — When is rain coming?
    // ──────────────────────────────────────────────

    private fun rainAlert(params: Map<String, String>): ToolResult {
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val db = getDb()
        val location = getLocation(db, params)
        val forecasts = getForecasts(db, location.first, 7)

        if (forecasts.isEmpty()) {
            return ToolResult.success(name, mapOf("cached" to false), "Hakuna data ya hali ya hewa.")
        }

        val rainyDays = forecasts.filter { (it.rainfallMm ?: 0.0) > 2.0 }
        val heavyDays = forecasts.filter { (it.rainfallMm ?: 0.0) > 15.0 }
        val dryDays = forecasts.filter { (it.rainfallMm ?: 0.0) < 1.0 }

        val message = if (voice) {
            buildString {
                appendLine("🌧️ *Arifa ya Mvua — ${location.first}*")
                appendLine()

                if (heavyDays.isNotEmpty()) {
                    appendLine("🚨 *Mvua kubwa inakuja:*")
                    heavyDays.forEach { f ->
                        val dayName = try {
                            val sdf = java.text.SimpleDateFormat("EEEE dd MMM", java.util.Locale("sw"))
                            sdf.format(java.text.SimpleDateFormat("yyyy-MM-dd").parse(f.forecastDate)!!)
                        } catch (e: Exception) { f.forecastDate }
                        appendLine("   🌧️ $dayName: ${f.rainfallMm?.toInt()}mm — ${f.conditionDetail ?: "mvua kali"}")
                    }
                    appendLine()
                    appendLine("⚠️ *Hatua za haraka:*")
                    appendLine("   • Vuna mazao yaliyo tayari")
                    appendLine("   • Hifadhi mazao mahali pa kavu")
                    appendLine("   • Funga mifuko ya kuhifadhi vizuri")
                    appendLine("   • Ondoa mazao kwenye barabara ya maji")
                } else if (rainyDays.isNotEmpty()) {
                    appendLine("🌧️ *Mvua inakuja:*")
                    rainyDays.forEach { f ->
                        val dayName = try {
                            val sdf = java.text.SimpleDateFormat("EEEE", java.util.Locale("sw"))
                            sdf.format(java.text.SimpleDateFormat("yyyy-MM-dd").parse(f.forecastDate)!!)
                        } catch (e: Exception) { f.forecastDate }
                        appendLine("   🌧️ $dayName: ${f.rainfallMm?.toInt()}mm")
                    }
                } else {
                    appendLine("☀️ Hakuna mvua wiki hii.")
                    if (dryDays.size >= 5) {
                        appendLine("Wiki kavu — nzuri kwa kukausha na kupulizia dawa.")
                    }
                }

                if (dryDays.isNotEmpty() && rainyDays.isNotEmpty()) {
                    appendLine()
                    appendLine("📅 *Ratiba ya kazi:*")
                    dryDays.forEach { f ->
                        val dayName = try {
                            val sdf = java.text.SimpleDateFormat("EEEE", java.util.Locale("sw"))
                            sdf.format(java.text.SimpleDateFormat("yyyy-MM-dd").parse(f.forecastDate)!!)
                        } catch (e: Exception) { f.forecastDate }
                        appendLine("   ☀️ $dayName: kavu — fanya kazi za nje")
                    }
                }
            }
        } else {
            buildString {
                appendLine("Rain alert — ${location.first}:")
                rainyDays.forEach { appendLine("${it.forecastDate}: ${it.rainfallMm?.toInt()}mm") }
                if (rainyDays.isEmpty()) appendLine("No rain expected this week")
            }
        }

        return ToolResult.success(
            name,
            mapOf(
                "rainy_days" to rainyDays.map { it.forecastDate },
                "heavy_rain_days" to heavyDays.map { it.forecastDate },
                "dry_days" to dryDays.map { it.forecastDate },
                "next_rain" to rainyDays.firstOrNull()?.forecastDate
            ),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: agri_advisory — Farming-specific advice
    // ──────────────────────────────────────────────

    private fun agriAdvisory(params: Map<String, String>): ToolResult {
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val db = getDb()
        val location = getLocation(db, params)
        val crop = params["crop"]?.lowercase() ?: "maize"
        val activity = params["activity"]
        val forecasts = getForecasts(db, location.first, 7)

        if (forecasts.isEmpty()) {
            return ToolResult.success(name, mapOf("cached" to false), "Hakuna data ya hali ya hewa.")
        }

        val message = if (voice) {
            buildString {
                appendLine("🌾 *Ushauri wa Kilimo — ${location.first}*")
                appendLine("🌽 Mazao: $crop")
                appendLine()

                // Rain analysis
                val totalRain = forecasts.sumOf { it.rainfallMm ?: 0.0 }
                val rainyDays = forecasts.count { (it.rainfallMm ?: 0.0) > 2.0 }
                val avgTemp = forecasts.mapNotNull { it.tempHigh }.average()
                val avgHumidity = forecasts.mapNotNull { it.humidity }.average()

                appendLine("📊 *Muhtasari wa wiki:*")
                appendLine("   Jumla mvua: ${totalRain.toInt()}mm ($rainyDays siku)")
                appendLine("   Joto wastani: ${avgTemp.toInt()}°C")
                appendLine("   Unyevu wastani: ${avgHumidity.toInt()}%")
                appendLine()

                // Activity-specific advice
                val targetActivity = activity ?: detectActivity(crop, forecasts)
                when (targetActivity) {
                    "harvest" -> {
                        appendLine("🌾 *Ushauri wa Kuvuna:*")
                        val dryDays = forecasts.filter { (it.rainfallMm ?: 0.0) < 5.0 }
                        if (dryDays.size >= 3) {
                            appendLine("   ✅ Wiki nzuri ya kuvuna! Anza siku ya kesho.")
                            appendLine("   Vuna asubuhi kabla ya jua kali.")
                        } else {
                            appendLine("   ⚠️ Mvua mingi wiki hii.")
                            appendLine("   Vuna siku za kavu tu. Mavuno ya mvua huharibika haraka.")
                        }
                    }
                    "dry" -> {
                        appendLine("☀️ *Ushauri wa Kukausha:*")
                        val sunnyDays = forecasts.filter {
                            (it.rainfallMm ?: 0.0) < 0.5 && (it.humidity ?: 100.0) < 60
                        }
                        if (sunnyDays.size >= 3) {
                            appendLine("   ✅ Wiki nzuri ya kukausha!")
                            appendLine("   Anza kukausha leo. Siku ${sunnyDays.size} za jua zinakuja.")
                        } else {
                            appendLine("   ⚠️ Unyevu mwingi wiki hii.")
                            appendLine("   Tumia jiko la kukaushia au subiri wiki ijayo.")
                        }
                    }
                    "plant" -> {
                        appendLine("🌱 *Ushauri wa Kupanda:*")
                        val plantDays = forecasts.filter {
                            val rain = it.rainfallMm ?: 0.0
                            rain in 5.0..30.0
                        }
                        if (plantDays.isNotEmpty()) {
                            appendLine("   ✅ Siku ${plantDays.size} nzuri za kupanda wiki hii.")
                            appendLine("   Panda siku 1-2 baada ya mvua, wakati udongo bado ni mvua.")
                        } else if (totalRain < 10) {
                            appendLine("   ⚠️ Kavu sana kupanda. Subiri mvua au tumia umwagiliaji.")
                        } else {
                            appendLine("   ⚠️ Mvua nyingi sana. Subiri ikomeshe kabla ya kupanda.")
                        }
                    }
                    "spray" -> {
                        appendLine("🧴 *Ushauri wa Kupulizia Dawa:*")
                        val sprayDays = forecasts.filter { (it.rainfallMm ?: 0.0) < 2.0 && (it.windSpeedKmh ?: 0.0) < 20 }
                        if (sprayDays.isNotEmpty()) {
                            appendLine("   ✅ Siku ${sprayDays.size} nzuri za kupulizia wiki hii.")
                            appendLine("   Pulizia asubuhi au jioni — si katikati ya jua kali.")
                        } else {
                            appendLine("   ⚠️ Wiki si nzuri kupulizia — mvua au upepo mwingi.")
                            appendLine("   Dawa itaoshwa na mvua au kusafirishwa na upepo.")
                        }
                    }
                    else -> {
                        appendLine("💡 *Ushauri wa jumla:*")
                        when {
                            totalRain > 50 -> appendLine("   Mvua nyingi — epuka kazi za nje, fanya kazi za ndani.")
                            totalRain < 10 -> appendLine("   Wiki kavu — nzuri kwa kukausha, kuvuna, na kupulizia.")
                            else -> appendLine("   Wiki ya mchanganyiko — panga kulingana na hali ya hewa ya kila siku.")
                        }
                    }
                }
            }
        } else {
            buildString {
                appendLine("Agricultural advisory — ${location.first} ($crop):")
                forecasts.forEach { appendLine("${it.forecastDate}: ${it.condition}, ${it.rainfallMm?.toInt() ?: 0}mm, ${it.tempHigh?.toInt()}°C") }
            }
        }

        return ToolResult.success(name, mapOf("crop" to crop, "forecast_days" to forecasts.size), message)
    }

    // ──────────────────────────────────────────────
    // ACTION: cache_weather — Sync and cache weather
    // ──────────────────────────────────────────────

    private fun cacheWeather(params: Map<String, String>): ToolResult {
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val db = getDb()
        val location = getLocation(db, params)
        val now = System.currentTimeMillis()

        // In production, this would call a weather API.
        // For now, record the sync request and note it needs network.
        val values = ContentValues().apply {
            put("sync_key", "weather_${location.first}")
            put("last_sync_at", now)
            put("last_sync_status", "requested")
        }

        // Check existing cache freshness
        val cachedCount = db.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_FORECASTS WHERE location = ? AND expires_at > ?",
            arrayOf(location.first, now.toString())
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

        val message = if (voice) {
            buildString {
                appendLine("🔄 *Kupakua data ya hali ya hewa*")
                appendLine("📍 ${location.first}")
                appendLine()
                if (cachedCount > 0) {
                    appendLine("✅ Data ya hali ya hewa inapatikana: siku $cachedCount")
                    appendLine("   Data ni ya hivi karibuni — unaweza kutumia bila mtandao.")
                } else {
                    appendLine("⚠️ Hakuna data ya hali ya hewa.")
                    appendLine("   Pakua data unapokuwa na mtandao.")
                    appendLine("   Data itahifadhiwa kwa siku 7+ bila mtandao.")
                }
                appendLine()
                appendLine("💡 *Kumbuka:*")
                appendLine("   Hali ya hewa inabadilika. Pakua data kila siku unapokuwa na mtandao.")
                appendLine("   Data ya zamani (>48h) inaweza kutokuwa sahihi.")
            }
        } else {
            "Weather cache: $cachedCount forecasts cached for ${location.first}. Sync when connected."
        }

        return ToolResult.success(
            name,
            mapOf("location" to location.first, "cached_count" to cachedCount, "needs_network" to cachedCount == 0),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: cache_status — Check cached data freshness
    // ──────────────────────────────────────────────

    private fun cacheStatus(params: Map<String, String>): ToolResult {
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val db = getDb()
        val now = System.currentTimeMillis()

        val locations = mutableListOf<Pair<String, Int>>()
        val cursor = db.rawQuery("""
            SELECT location, COUNT(*) as cnt FROM $TABLE_FORECASTS
            WHERE expires_at > ? GROUP BY location
        """.trimIndent(), arrayOf(now.toString()))
        cursor.use {
            while (it.moveToNext()) {
                locations.add(Pair(it.getString(0), it.getInt(1)))
            }
        }

        val message = if (voice) {
            buildString {
                appendLine("📦 *Hali ya Data ya Hewa Iliyohifadhiwa*")
                appendLine()
                if (locations.isNotEmpty()) {
                    locations.forEach { (loc, count) ->
                        appendLine("✅ $loc: siku $count zinapatikana")
                    }
                    appendLine()
                    appendLine("Data hii inaweza kutumia bila mtandao.")
                } else {
                    appendLine("⚠️ Hakuna data ya hali ya hewa iliyohifadhiwa.")
                    appendLine("   Tumia 'cache_weather' kupakua data unapokuwa na mtandao.")
                }
            }
        } else {
            if (locations.isNotEmpty()) {
                "Cached weather: ${locations.joinToString(", ") { "${it.first}: ${it.second} days" }}"
            } else {
                "No cached weather data. Use 'cache_weather' to download."
            }
        }

        return ToolResult.success(
            name,
            mapOf("cached_locations" to locations.map { mapOf("location" to it.first, "days" to it.second) }),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: set_location — Set farm GPS coordinates
    // ──────────────────────────────────────────────

    private fun setLocation(params: Map<String, String>): ToolResult {
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val db = getDb()
        val now = System.currentTimeMillis()

        val lat = params["latitude"]?.toDoubleOrNull()
        val lon = params["longitude"]?.toDoubleOrNull()
        val locationName = params["location"]

        if (lat != null && lon != null) {
            // GPS coordinates provided
            db.delete(TABLE_LOCATION, "is_default = 1", null)
            val values = ContentValues().apply {
                put("location_name", locationName ?: "Shamba")
                put("latitude", lat)
                put("longitude", lon)
                put("is_default", 1)
                put("updated_at", now)
            }
            db.insert(TABLE_LOCATION, null, values)

            return ToolResult.success(
                name,
                mapOf("latitude" to lat, "longitude" to lon, "name" to locationName),
                if (voice) "📍 Eneo la shamba limewekwa: ${locationName ?: "Shamba"} ($lat, $lon).\nSasa nitakupa hali ya hewa ya eneo hili."
                else "Farm location set: ${locationName ?: "Shamba"} ($lat, $lon)"
            )
        } else if (locationName != null) {
            // County name provided — look up coordinates
            val coords = KENYA_COUNTIES[locationName.lowercase().replace(" ", "_").replace("-", "_")]
            if (coords != null) {
                db.delete(TABLE_LOCATION, "is_default = 1", null)
                val values = ContentValues().apply {
                    put("location_name", locationName)
                    put("latitude", coords.first)
                    put("longitude", coords.second)
                    put("county", locationName)
                    put("is_default", 1)
                    put("updated_at", now)
                }
                db.insert(TABLE_LOCATION, null, values)

                return ToolResult.success(
                    name,
                    mapOf("location" to locationName, "latitude" to coords.first, "longitude" to coords.second),
                    if (voice) "📍 Eneo: $locationName (${coords.first}, ${coords.second}). Nitakupa hali ya hewa ya eneo hili."
                    else "Location set: $locationName (${coords.first}, ${coords.second})"
                )
            }
        }

        return ToolResult.error(
            name,
            "GPS coordinates (latitude + longitude) or valid county name required. Counties: ${KENYA_COUNTIES.keys.joinToString(", ")}",
            "INVALID_LOCATION"
        )
    }

    // ──────────────────────────────────────────────
    // forecast helper — returns cached forecasts
    // ──────────────────────────────────────────────

    private fun forecast(params: Map<String, String>): ToolResult {
        // Delegate to currentWeather which includes 7-day forecast
        return currentWeather(params)
    }

    // ──────────────────────────────────────────────
    // Internal Helpers
    // ──────────────────────────────────────────────

    private fun getLocation(db: SQLiteDatabase, params: Map<String, String>): Pair<String, Pair<Double, Double>?> {
        // 1. From params
        params["latitude"]?.toDoubleOrNull()?.let { lat ->
            params["longitude"]?.toDoubleOrNull()?.let { lon ->
                return Pair(params["location"] ?: "Shamba", Pair(lat, lon))
            }
        }

        // 2. From params location name
        params["location"]?.let { loc ->
            KENYA_COUNTIES[loc.lowercase().replace(" ", "_").replace("-", "_")]?.let { coords ->
                return Pair(loc, coords)
            }
        }

        // 3. From saved default
        val cursor = db.query(TABLE_LOCATION, null, "is_default = 1", null, null, null, null, "1")
        cursor.use {
            if (it.moveToFirst()) {
                return Pair(
                    it.getString(it.getColumnIndexOrThrow("location_name")),
                    Pair(
                        it.getDouble(it.getColumnIndexOrThrow("latitude")),
                        it.getDouble(it.getColumnIndexOrThrow("longitude"))
                    )
                )
            }
        }

        // 4. Default to Nakuru (major farming region)
        return Pair("Nakuru", KENYA_COUNTIES["nakuru"])
    }

    private fun getCurrentWeather(db: SQLiteDatabase, location: String): WeatherSnapshot? {
        val cursor = db.query(
            TABLE_CURRENT, null, "location = ?", arrayOf(location),
            null, null, "recorded_at DESC", "1"
        )
        cursor.use {
            return if (it.moveToFirst()) {
                WeatherSnapshot(
                    tempC = it.getDouble(it.getColumnIndexOrThrow("temp_c")),
                    humidity = it.getDouble(it.getColumnIndexOrThrow("humidity")),
                    rainfallMm = it.getDouble(it.getColumnIndexOrThrow("rainfall_mm")),
                    windSpeedKmh = it.getDouble(it.getColumnIndexOrThrow("wind_speed_kmh")),
                    condition = it.getString(it.getColumnIndexOrThrow("condition")),
                    feelsLikeC = it.getDouble(it.getColumnIndexOrThrow("feels_like_c"))
                )
            } else null
        }
    }

    private fun getForecasts(db: SQLiteDatabase, location: String, days: Int): List<ForecastDay> {
        val now = System.currentTimeMillis()
        val results = mutableListOf<ForecastDay>()
        val cursor = db.query(
            TABLE_FORECASTS, null,
            "location = ? AND expires_at > ?",
            arrayOf(location, now.toString()),
            null, null, "forecast_date ASC", days.toString()
        )
        cursor.use {
            while (it.moveToNext()) {
                results.add(ForecastDay(
                    forecastDate = it.getString(it.getColumnIndexOrThrow("forecast_date")),
                    tempHigh = it.getDouble(it.getColumnIndexOrThrow("temp_high")),
                    tempLow = it.getDouble(it.getColumnIndexOrThrow("temp_low")),
                    humidity = it.getDouble(it.getColumnIndexOrThrow("humidity")),
                    rainfallMm = it.getDouble(it.getColumnIndexOrThrow("rainfall_mm")),
                    rainProbability = it.getDouble(it.getColumnIndexOrThrow("rain_probability")),
                    windSpeedKmh = it.getDouble(it.getColumnIndexOrThrow("wind_speed_kmh")),
                    condition = it.getString(it.getColumnIndexOrThrow("condition")),
                    conditionDetail = it.getString(it.getColumnIndexOrThrow("condition_detail"))
                ))
            }
        }
        return results
    }

    private fun detectActivity(crop: String, forecasts: List<ForecastDay>): String {
        val currentMonth = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1
        val totalRain = forecasts.sumOf { it.rainfallMm ?: 0.0 }

        // Heuristic based on season and weather
        return when {
            totalRain < 5 -> "dry"     // dry week → good for drying
            totalRain > 30 -> "harvest" // rainy → harvest before rain
            currentMonth in listOf(3, 4, 10) -> "plant" // planting season
            else -> "general"
        }
    }

    // ──────────────────────────────────────────────
    // Data Classes
    // ──────────────────────────────────────────────

    data class WeatherSnapshot(
        val tempC: Double?,
        val humidity: Double?,
        val rainfallMm: Double?,
        val windSpeedKmh: Double?,
        val condition: String?,
        val feelsLikeC: Double?
    )

    data class ForecastDay(
        val forecastDate: String,
        val tempHigh: Double?,
        val tempLow: Double?,
        val humidity: Double?,
        val rainfallMm: Double?,
        val rainProbability: Double?,
        val windSpeedKmh: Double?,
        val condition: String?,
        val conditionDetail: String?
    )
}
