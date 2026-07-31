package com.msaidizi.agent.tools.transport

import com.msaidizi.core.database.*
import com.msaidizi.core.model.*
import com.msaidizi.core.util.DateTimeUtil
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

// ══════════════════════════════════════════════
// FIX 4: FARE INTELLIGENCE — P1
// ══════════════════════════════════════════════
// Tracks average fares by route, time of day,
// and weather conditions. Suggests best times
// and routes for maximum income.
//
// "Airport route pays KES 500 more during rain"
// "Town → Westlands at 7am = KES 300 avg"
// ══════════════════════════════════════════════

// Entities and DAOs moved to core module:
// com.msaidizi.core.model.BodaEntities
// com.msaidizi.core.database.BodaDaos

// ──────────────────────────────────────────────
// FARE INTELLIGENCE TOOL
// ──────────────────────────────────────────────

/**
 * Fare Intelligence for boda boda riders.
 *
 * Tracks and analyzes fares by route, time, and weather.
 * Suggests best routes and times for maximum income.
 *
 * Actions:
 *  - record:    Record a fare (route, amount, time, weather)
 *  - routes:    Show fare summary by route
 *  - best_time: Show best times to work
 *  - weather:   Show weather impact on fares
 *  - suggest:   Get AI-powered suggestions for today
 *  - history:   View fare records
 *
 * Voice (Swahili):
 *  - "Nimepata mia tatu Town → Westlands" → record
 *  - "Route gani inalipa zaidi?" → routes
 *  - "Saa ngapi ni bora?" → best_time
 *  - "Mvua inaongeza bei?" → weather
 *  - "Nifanye nini leo?" → suggest
 */
@Singleton
class FareIntelligence @Inject constructor(
    private val fareRecordDao: FareRecordDao
) : Tool {

    override val name = "fare_intelligence"
    override val description = "Fare intelligence for boda boda riders. " +
            "Track fares by route, time of day, and weather. " +
            "Get suggestions: 'Route gani inalipa zaidi?' 'Saa ngapi ni bora?' " +
            "'Mvua inaongeza bei ya Airport route?'"

    override val argsSchema = argSchema {
        enum("action", "Action to perform",
            listOf("record", "routes", "best_time", "weather", "suggest", "history", "delete"))

        // ── record ──
        number("fare", "Fare amount in KES", required = false)
        string("from_location", "Origin (e.g. 'Town')", required = false)
        string("to_location", "Destination (e.g. 'Westlands')", required = false)
        string("route", "Full route name (e.g. 'Town → Westlands')", required = false)
        number("distance_km", "Estimated distance in km", required = false)
        enum("weather", "Current weather", listOf("clear", "rain", "hot", "cold"), required = false)
        integer("passenger_count", "Number of passengers", required = false)

        // ── period ──
        string("start_date", "Start date for analysis", required = false)
        string("end_date", "End date for analysis", required = false)

        // ── delete ──
        string("entry_id", "Record ID to delete", required = false)

        // ── history ──
        integer("limit", "Number of records to return", required = false)

        // ── Voice input ──
        string("voice_text", "Raw Swahili voice input", required = false)
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val effectiveParams = if (params.containsKey("voice_text") && !params.containsKey("action")) {
            parseVoiceInput(params["voice_text"]!!).toMutableMap().apply { putAll(params) }
        } else {
            params
        }

        val action = effectiveParams["action"] ?: "suggest"
        return when (action.lowercase()) {
            "record" -> recordFare(effectiveParams)
            "routes" -> showRoutes(effectiveParams)
            "best_time" -> showBestTimes(effectiveParams)
            "weather" -> showWeatherImpact(effectiveParams)
            "suggest" -> showSuggestions(effectiveParams)
            "history" -> viewHistory(effectiveParams)
            "delete" -> deleteRecord(effectiveParams)
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    // ──────────────────────────────────────────────
    // RECORD FARE
    // ──────────────────────────────────────────────

    private suspend fun recordFare(params: Map<String, String>): ToolResult {
        return try {
            val fare = params["fare"]?.toDoubleOrNull()
                ?: return ToolResult.error(name, "fare required. Sema: umepata pesa ngapi?", "MISSING_FARE")

            val fromLocation = params["from_location"] ?: ""
            val toLocation = params["to_location"] ?: ""
            val route = params["route"]
                ?: if (fromLocation.isNotEmpty() && toLocation.isNotEmpty()) {
                    "$fromLocation → $toLocation"
                } else ""

            if (route.isEmpty()) {
                return ToolResult.error(name, "route au from_location + to_location required. Sema: kutoka wapi, kwenda wapi?", "MISSING_ROUTE")
            }

            val now = Calendar.getInstance()
            val hourOfDay = now.get(Calendar.HOUR_OF_DAY)
            val dayOfWeek = now.get(Calendar.DAY_OF_WEEK) // 1=Sun, 7=Sat in Java
            // Convert to 1=Mon, 7=Sun
            val adjustedDay = if (dayOfWeek == Calendar.SUNDAY) 7 else dayOfWeek - 1

            val weather = params["weather"] ?: "clear"

            val record = FareRecordEntity(
                fare = fare,
                route = route,
                fromLocation = fromLocation,
                toLocation = toLocation,
                distanceKm = params["distance_km"]?.toDoubleOrNull(),
                hourOfDay = hourOfDay,
                dayOfWeek = adjustedDay,
                weather = weather,
                passengerCount = params["passenger_count"]?.toIntOrNull() ?: 1,
                date = params["date"] ?: DateTimeUtil.today()
            )
            val id = fareRecordDao.insert(record)

            // Get route stats
            val monthStart = monthStartDate()
            val today = DateTimeUtil.today()
            val routeSummary = fareRecordDao.getRouteSummary(monthStart, today)
            val thisRoute = routeSummary.find { it.route == route }

            val report = buildString {
                appendLine("✅ Fare imerekodwa!")
                appendLine("━━━━━━━━━━━━━━━━━━━━")
                appendLine("🛵 $route")
                appendLine("💰 KES ${"%,.0f".format(fare)}")
                appendLine("🕐 Saa: $hourOfDay:00 | Siku: ${dayName(adjustedDay)}")
                if (weather != "clear") appendLine("🌤️ Hali ya hewa: $weather")

                if (thisRoute != null) {
                    appendLine()
                    appendLine("📊 Stats ya route hii (mwezi huu):")
                    appendLine("   Safari: ${thisRoute.tripCount}")
                    appendLine("   Average: KES ${"%,.0f".format(thisRoute.avgFare)}")
                    appendLine("   Min/Max: KES ${"%,.0f".format(thisRoute.minFare)} — ${"%,.0f".format(thisRoute.maxFare)}")
                    if (fare > thisRoute.avgFare) {
                        appendLine("   ✅ Fare yako ni juu ya average!")
                    } else if (fare < thisRoute.avgFare) {
                        appendLine("   ⚠️ Fare yako ni chini ya average. Jaribu kujadili zaidi.")
                    }
                }
            }

            ToolResult.success(
                name,
                data = mapOf(
                    "entry_id" to id,
                    "fare" to fare,
                    "route" to route,
                    "hour" to hourOfDay,
                    "weather" to weather
                ),
                message = report
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to record fare")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // SHOW ROUTES
    // ──────────────────────────────────────────────

    private suspend fun showRoutes(params: Map<String, String>): ToolResult {
        return try {
            val monthStart = monthStartDate()
            val today = DateTimeUtil.today()
            val routes = fareRecordDao.getRouteSummary(monthStart, today)

            if (routes.isEmpty()) {
                return ToolResult.success(
                    name,
                    message = "🛵 Hakuna data za routes bado.\n" +
                            "Rekodi fares zako: 'Nimepata mia tatu Town → Westlands'"
                )
            }

            val bestPaying = routes.maxByOrNull { it.avgFare }
            val mostTrips = routes.maxByOrNull { it.tripCount }

            val report = buildString {
                appendLine("🛵 *Fares kwa Route — Mwezi huu*")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()
                routes.forEachIndexed { i, r ->
                    val indicator = if (r == bestPaying) " 💰" else ""
                    appendLine("  ${i + 1}. ${r.route}$indicator")
                    appendLine("     💰 Average: KES ${"%,.0f".format(r.avgFare)} (${r.tripCount} safari)")
                    appendLine("     📊 Range: KES ${"%,.0f".format(r.minFare)} — ${"%,.0f".format(r.maxFare)}")
                    if (r.tripCount > 1) {
                        val spread = r.maxFare - r.minFare
                        if (spread > r.avgFare * 0.5) {
                            appendLine("     💡 Bei inatofautiana sana! Jadili kwa nguvu zaidi.")
                        }
                    }
                    appendLine()
                }

                if (bestPaying != null) {
                    appendLine("💰 Route bora: '${bestPaying.route}' — KES ${"%,.0f".format(bestPaying.avgFare)} average")
                }
                if (mostTrips != null && mostTrips != bestPaying) {
                    appendLine("📊 Route yenye safari nyingi: '${mostTrips.route}' (${mostTrips.tripCount}x)")
                }
            }

            ToolResult.success(
                name,
                data = mapOf("routes" to routes),
                message = report
            )
        } catch (e: Exception) {
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // BEST TIMES
    // ──────────────────────────────────────────────

    private suspend fun showBestTimes(params: Map<String, String>): ToolResult {
        return try {
            val monthStart = monthStartDate()
            val today = DateTimeUtil.today()

            val hourlyPattern = fareRecordDao.getHourlyFarePattern(monthStart, today)
            val dailyPattern = fareRecordDao.getDayOfWeekPattern(monthStart, today)

            if (hourlyPattern.isEmpty()) {
                return ToolResult.success(
                    name,
                    message = "🕐 Hakuna data za wakati bado.\n" +
                            "Rekodi fares zako kwa wiki 2+ nitakupatia ushauri wa wakati bora."
                )
            }

            // Find peak hours (top 3)
            val peakHours = hourlyPattern.sortedByDescending { it.avgFare }.take(3)
            val deadHours = hourlyPattern.sortedBy { it.avgFare }.take(3)

            // Find best days
            val bestDay = dailyPattern.maxByOrNull { it.avgFare }
            val worstDay = dailyPattern.minByOrNull { it.avgFare }

            val report = buildString {
                appendLine("🕐 *Wakati Bora wa Kufanya Kazi*")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()

                appendLine("⏰ *Saa Bora (average fare kubwa):*")
                peakHours.forEach { h ->
                    appendLine("   ${String.format("%02d:00", h.hourOfDay)} — KES ${"%,.0f".format(h.avgFare)} (${h.count} safari)")
                }
                appendLine()

                appendLine("😴 *Saa Dhaifu (average fare ndogo):*")
                deadHours.forEach { h ->
                    appendLine("   ${String.format("%02d:00", h.hourOfDay)} — KES ${"%,.0f".format(h.avgFare)} (${h.count} safari)")
                }
                appendLine()

                if (bestDay != null) {
                    appendLine("📅 *Siku Bora:* ${dayName(bestDay.dayOfWeek)} — KES ${"%,.0f".format(bestDay.avgFare)} average")
                }
                if (worstDay != null) {
                    appendLine("📅 *Siku Dhaifu:* ${dayName(worstDay.dayOfWeek)} — KES ${"%,.0f".format(worstDay.avgFare)} average")
                }

                // Pattern chart (simple text)
                appendLine()
                appendLine("📊 *Pattern ya Saa:*")
                val maxFare = hourlyPattern.maxOf { it.avgFare }
                hourlyPattern.forEach { h ->
                    val barLength = ((h.avgFare / maxFare) * 20).toInt()
                    val bar = "█".repeat(barLength)
                    appendLine("   ${String.format("%02d", h.hourOfDay)} |$bar| KES ${"%.0f".format(h.avgFare)}")
                }
            }

            ToolResult.success(
                name,
                data = mapOf(
                    "peak_hours" to peakHours,
                    "dead_hours" to deadHours,
                    "best_day" to bestDay,
                    "worst_day" to worstDay,
                    "hourly_pattern" to hourlyPattern
                ),
                message = report
            )
        } catch (e: Exception) {
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // WEATHER IMPACT
    // ──────────────────────────────────────────────

    private suspend fun showWeatherImpact(params: Map<String, String>): ToolResult {
        return try {
            val monthStart = monthStartDate()
            val today = DateTimeUtil.today()

            val overallComparison = fareRecordDao.getOverallWeatherComparison(monthStart, today)

            if (overallComparison.isEmpty()) {
                return ToolResult.success(
                    name,
                    message = "🌤️ Hakuna data za hali ya hewa bado.\n" +
                            "Weka 'weather' unaporekodi fare — nitakulinganishia bei za mvua vs jua."
                )
            }

            val rainFare = overallComparison.find { it.weather == "rain" }
            val clearFare = overallComparison.find { it.weather == "clear" }

            // Get per-route weather comparison for top routes
            val routes = fareRecordDao.getAllRoutes().take(5)
            val routeWeatherData = routes.map { route ->
                route to fareRecordDao.getWeatherFareComparison(route)
            }.filter { it.second.isNotEmpty() }

            val report = buildString {
                appendLine("🌤️ *Athari ya Hali ya Hewa kwenye Fares*")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()

                appendLine("📊 *Jumla:*")
                overallComparison.forEach { w ->
                    val emoji = weatherEmoji(w.weather)
                    appendLine("   $emoji ${weatherLabel(w.weather)}: KES ${"%,.0f".format(w.avgFare)} average (${w.count} safari)")
                }

                if (rainFare != null && clearFare != null) {
                    val diff = rainFare.avgFare - clearFare.avgFare
                    val percentDiff = (diff / clearFare.avgFare * 100)
                    appendLine()
                    if (diff > 0) {
                        appendLine("🌧️ Mvua inaongeza fare kwa KES ${"%,.0f".format(diff)} (${"%.0f".format(percentDiff)}%)!")
                        appendLine("   💡 Wakati wa mvua, panda route ndefu — bei ni bora.")
                    } else {
                        appendLine("☀️ Mvua inapunguza fare kwa KES ${"%,.0f".format(Math.abs(diff))}")
                        appendLine("   Riders wengi wanafanya kazi wakati wa mvua — ushindani ni mkubwa.")
                    }
                }

                if (routeWeatherData.isNotEmpty()) {
                    appendLine()
                    appendLine("── Route kwa Hali ya Hewa ──")
                    routeWeatherData.forEach { (route, data) ->
                        appendLine("  🛵 $route:")
                        data.forEach { w ->
                            val emoji = weatherEmoji(w.weather)
                            appendLine("     $emoji ${weatherLabel(w.weather)}: KES ${"%,.0f".format(w.avgFare)} (${w.count}x)")
                        }
                    }
                }

                // Actionable insight
                if (rainFare != null && clearFare != null && rainFare.avgFare > clearFare.avgFare) {
                    appendLine()
                    appendLine("💡 *Ushauri:* Kesho kutana na mvua, panga kufanya kazi saa za mvua.")
                    appendLine("   Route za umbali mrefu zitalipa zaidi.")
                }
            }

            ToolResult.success(
                name,
                data = mapOf("overall" to overallComparison, "route_weather" to routeWeatherData),
                message = report
            )
        } catch (e: Exception) {
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // SUGGESTIONS (AI-powered)
    // ──────────────────────────────────────────────

    private suspend fun showSuggestions(params: Map<String, String>): ToolResult {
        return try {
            val today = DateTimeUtil.today()
            val monthStart = monthStartDate()
            val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val currentDay = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)

            val routes = fareRecordDao.getRouteSummary(monthStart, today)
            val hourlyPattern = fareRecordDao.getHourlyFarePattern(monthStart, today)
            val weatherData = fareRecordDao.getOverallWeatherComparison(monthStart, today)

            val suggestions = mutableListOf<String>()

            // Best route suggestion
            if (routes.isNotEmpty()) {
                val bestRoute = routes.maxByOrNull { it.avgFare }
                if (bestRoute != null) {
                    suggestions.add("🛵 Route bora ya mwezi: '${bestRoute.route}' — " +
                            "average KES ${"%,.0f".format(bestRoute.avgFare)} (${bestRoute.tripCount} safari)")
                }
            }

            // Time-based suggestion
            val upcomingHours = hourlyPattern.filter {
                it.hourOfDay in currentHour..(currentHour + 4)
            }.sortedByDescending { it.avgFare }

            if (upcomingHours.isNotEmpty()) {
                val bestUpcoming = upcomingHours.first()
                suggestions.add("⏰ Saa bora ijayo: ${String.format("%02d:00", bestUpcoming.hourOfDay)} — " +
                        "average KES ${"%,.0f".format(bestUpcoming.avgFare)}")
            }

            // Day-of-week insight
            val dailyPattern = fareRecordDao.getDayOfWeekPattern(monthStart, today)
            val todayPattern = dailyPattern.find {
                it.dayOfWeek == if (currentDay == Calendar.SUNDAY) 7 else currentDay - 1
            }
            if (todayPattern != null) {
                val bestDay = dailyPattern.maxByOrNull { it.avgFare }
                if (bestDay != null && bestDay.dayOfWeek != todayPattern.dayOfWeek) {
                    suggestions.add("📅 Siku bora ya wiki: ${dayName(bestDay.dayOfWeek)} — " +
                            "average KES ${"%,.0f".format(bestDay.avgFare)}")
                }
            }

            // Weather insight
            val rainData = weatherData.find { it.weather == "rain" }
            val clearData = weatherData.find { it.weather == "clear" }
            if (rainData != null && clearData != null && rainData.avgFare > clearData.avgFare * 1.1) {
                suggestions.add("🌧️ Mvua inaongeza fare kwa ${"%.0f".format((rainData.avgFare - clearData.avgFare) / clearData.avgFare * 100)}%. " +
                        "Wakati wa mvua, fanya kazi route ndefu.")
            }

            // Fare negotiation tip
            if (routes.isNotEmpty()) {
                val highSpreadRoutes = routes.filter { it.maxFare - it.minFare > it.avgFare * 0.4 }
                if (highSpreadRoutes.isNotEmpty()) {
                    suggestions.add("💡 Routes zenye bei tofauti: ${highSpreadRoutes.first().route}. " +
                            "Jadili kwa nguvu — bei inaweza kuwa KES ${"%,.0f".format(highSpreadRoutes.first().maxFare)}!")
                }
            }

            if (suggestions.isEmpty()) {
                suggestions.add("📊 Rekodi fares zako kwa wiki 2+ ili nikupate ushauri bora.")
                suggestions.add("   Sema: 'Nimepata mia tatu Town → Westlands'")
            }

            val report = buildString {
                appendLine("💡 *Ushauri wa Leo — Msaidizi Fare Intelligence*")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()
                suggestions.forEachIndexed { i, s ->
                    appendLine("${i + 1}. $s")
                    appendLine()
                }
            }

            ToolResult.success(
                name,
                data = mapOf("suggestions" to suggestions),
                message = report
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to generate suggestions")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // VIEW HISTORY
    // ──────────────────────────────────────────────

    private suspend fun viewHistory(params: Map<String, String>): ToolResult {
        return try {
            val limit = params["limit"]?.toIntOrNull() ?: 20
            val records = fareRecordDao.getRecent(limit).first()

            if (records.isEmpty()) {
                return ToolResult.success(
                    name,
                    message = "📜 Hakuna fare records bado.\n" +
                            "Sema: 'Nimepata mia tatu Town → Westlands' kuanza."
                )
            }

            val dateFormat = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
            val report = buildString {
                appendLine("📜 *Historia ya Fares*")
                appendLine("━━━━━━━━━━━━━━━━━━━━")
                records.forEach { r ->
                    val weatherEmoji = weatherEmoji(r.weather)
                    appendLine("  🛵 ${r.route}")
                    appendLine("     💰 KES ${"%,.0f".format(r.fare)} | ${dateFormat.format(Date(r.timestamp))}")
                    appendLine("     🕐 ${String.format("%02d:00", r.hourOfDay)} $weatherEmoji")
                }
            }

            ToolResult.success(name, data = mapOf("records" to records), message = report)
        } catch (e: Exception) {
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // DELETE RECORD
    // ──────────────────────────────────────────────

    private suspend fun deleteRecord(params: Map<String, String>): ToolResult {
        return try {
            val entryId = params["entry_id"]?.toLongOrNull()
                ?: return ToolResult.error(name, "entry_id required", "MISSING_ID")

            val records = fareRecordDao.getRecent(100).first()
            val record = records.find { it.id == entryId }
            if (record != null) {
                fareRecordDao.delete(record)
                ToolResult.success(name, message = "✅ Fare ya KES ${"%,.0f".format(record.fare)} (${record.route}) imefutwa.")
            } else {
                ToolResult.error(name, "Record haipatikani", "NOT_FOUND")
            }
        } catch (e: Exception) {
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // VOICE INPUT PARSER
    // ──────────────────────────────────────────────

    private fun parseVoiceInput(text: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        val lower = text.lowercase()

        when {
            // Record fare patterns
            lower.contains(Regex("nimepata|nimelipwa|fare|nimechukua|nilipata")) -> {
                params["action"] = "record"
                extractAmount(lower)?.let { params["fare"] = it }
                extractRoute(text, params)
            }
            // Routes query
            lower.contains(Regex("route gani|route.*bora|njia.*bora|gani.*inalipa|best.*route")) -> {
                params["action"] = "routes"
            }
            // Best time
            lower.contains(Regex("saa.*bora|wakati.*bora|saa.*ngapi|best.*time|peak.*hour")) -> {
                params["action"] = "best_time"
            }
            // Weather
            lower.contains(Regex("mvua|rain|weather|hali.*hewa|jua|baridi")) -> {
                params["action"] = "weather"
            }
            // Suggest
            lower.contains(Regex("ushauri|nifanye.*nini|suggest|advice|tips|leo.*nifanye")) -> {
                params["action"] = "suggest"
            }
            // History
            lower.contains(Regex("historia|history|records|fare.*zangu")) -> {
                params["action"] = "history"
            }
        }

        // Extract weather
        when {
            lower.contains(Regex("mvua|rain|kunyesha")) -> params["weather"] = "rain"
            lower.contains(Regex("jua kali|hot|mototo")) -> params["weather"] = "hot"
            lower.contains(Regex("baridi|cold")) -> params["weather"] = "cold"
        }

        return params
    }

    private fun extractAmount(text: String): String? {
        val swahiliOnes = mapOf(
            "moja" to 1, "mbili" to 2, "tatu" to 3, "nne" to 4, "tano" to 5,
            "sita" to 6, "saba" to 7, "nane" to 8, "tisa" to 9
        )

        var total = 0.0
        for ((word, value) in swahiliOnes) {
            if (text.contains("mia $word")) total += value * 100
            if (text.contains("elfu $word")) total += value * 1000
        }
        Regex("mia\\s*(\\d+)").find(text)?.let { total += it.groupValues[1].toDouble() * 100 }
        Regex("elfu\\s*(\\d+)").find(text)?.let { total += it.groupValues[1].toDouble() * 1000 }

        if (total > 0) return total.toInt().toString()

        Regex("""(\d+\.?\d*)""").find(text)?.let { return it.groupValues[1] }
        return null
    }

    private fun extractRoute(text: String, params: MutableMap<String, String>) {
        // "X → Y" or "X kwenda Y" or "X hadi Y"
        val routePattern = Regex("""(\w[\w\s]*?)\s*(?:→|->|kwenda|hadi|mpaka)\s*(\w[\w\s]*)""", RegexOption.IGNORE_CASE)
        routePattern.find(text)?.let {
            params["from_location"] = it.groupValues[1].trim().replaceFirstChar { c -> c.uppercase() }
            params["to_location"] = it.groupValues[2].trim().replaceFirstChar { c -> c.uppercase() }
        }
    }

    // ──────────────────────────────────────────────
    // HELPERS
    // ──────────────────────────────────────────────

    private fun weatherEmoji(weather: String): String = when (weather) {
        "rain" -> "🌧️"
        "hot" -> "☀️"
        "cold" -> "🥶"
        else -> "🌤️"
    }

    private fun weatherLabel(weather: String): String = when (weather) {
        "rain" -> "Mvua"
        "hot" -> "Jua Kali"
        "cold" -> "Baridi"
        else -> "Wazi (Clear)"
    }

    private fun dayName(dayOfWeek: Int): String = when (dayOfWeek) {
        1 -> "Jumatatu"
        2 -> "Jumanne"
        3 -> "Jumatano"
        4 -> "Alhamisi"
        5 -> "Ijumaa"
        6 -> "Jumamosi"
        7 -> "Jumapili"
        else -> "Siku $dayOfWeek"
    }

    private fun monthStartDate(): String {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)
    }
}
