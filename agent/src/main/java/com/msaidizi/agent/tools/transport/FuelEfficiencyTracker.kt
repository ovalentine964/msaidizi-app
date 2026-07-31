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
import com.msaidizi.agent.tools.core.*

// ══════════════════════════════════════════════
// FIX 3: FUEL EFFICIENCY TRACKER — P0
// ══════════════════════════════════════════════
// Fuel is 40-60% of a boda boda rider's earnings.
// This tool tracks liters purchased vs km ridden,
// calculates cost per kilometer, and alerts on
// sudden efficiency drops (possible fuel theft).
// ══════════════════════════════════════════════

// Entities and DAOs moved to core module:
// com.msaidizi.core.model.BodaEntities
// com.msaidizi.core.database.BodaDaos

// ──────────────────────────────────────────────
// FUEL EFFICIENCY TRACKER TOOL
// ──────────────────────────────────────────────

/**
 * Fuel Efficiency Tracker for boda boda riders.
 *
 * Tracks liters purchased vs kilometers ridden.
 * Calculates cost per kilometer.
 * Alerts on fuel theft (sudden efficiency drops).
 * Compares petrol stations for best prices.
 *
 * Actions:
 *  - add_fuel:    Record fuel purchase (liters, cost, station)
 *  - add_km:      Record kilometers ridden (km, route)
 *  - efficiency:  Show current fuel efficiency stats
 *  - compare:     Compare petrol stations
 *  - alert:       Check for fuel theft / efficiency anomalies
 *  - history:     View fuel purchase history
 *
 * Voice (Swahili):
 *  - "Nimejaza lita mbili mia tatu" → add_fuel 2 liters, 300 KES
 *  - "Nimepanda kilomita hamsini" → add_km 50
 *  - "Mafuta yangu inakwenda vipi?" → efficiency
 */
@Singleton
class FuelEfficiencyTracker @Inject constructor(
    private val fuelPurchaseDao: FuelPurchaseDao,
    private val tripKmDao: TripKilometersDao
) : Tool {

    override val name = "fuel_efficiency"
    override val description = "Fuel efficiency tracker for boda boda riders. " +
            "Track liters purchased vs km ridden. Calculate cost per kilometer. " +
            "Alert on fuel theft (sudden efficiency drop). Compare petrol stations. " +
            "Fuel is 40-60% of earnings — optimizing it directly increases your profit."

    override val argsSchema = argSchema {
        enum("action", "Action to perform",
            listOf("add_fuel", "add_km", "efficiency", "compare", "alert", "history"))

        // ── add_fuel ──
        number("liters", "Liters of fuel purchased", required = false)
        number("cost_per_liter", "Price per liter in KES (default: ~185)", required = false)
        number("total_cost", "Total cost in KES (auto-calculated if liters + cost_per_liter given)", required = false)
        string("station", "Petrol station name", required = false)
        number("odometer", "Odometer reading in km (optional)", required = false)

        // ── add_km ──
        number("kilometers", "Kilometers ridden", required = false)
        string("route", "Route description", required = false)

        // ── history ──
        integer("limit", "Number of entries to return", required = false)
        string("date", "Specific date (YYYY-MM-DD)", required = false)

        // ── period ──
        string("start_date", "Start date for range queries", required = false)
        string("end_date", "End date for range queries", required = false)

        // ── Voice input ──
        string("voice_text", "Raw Swahili voice input", required = false)
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val effectiveParams = if (params.containsKey("voice_text") && !params.containsKey("action")) {
            parseVoiceInput(params["voice_text"]!!).toMutableMap().apply { putAll(params) }
        } else {
            params
        }

        val action = effectiveParams["action"] ?: "efficiency"
        return when (action.lowercase()) {
            "add_fuel" -> addFuel(effectiveParams)
            "add_km" -> addKm(effectiveParams)
            "efficiency" -> showEfficiency(effectiveParams)
            "compare" -> compareStations()
            "alert" -> checkAlerts()
            "history" -> viewHistory(effectiveParams)
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    // ──────────────────────────────────────────────
    // ADD FUEL PURCHASE
    // ──────────────────────────────────────────────

    private suspend fun addFuel(params: Map<String, String>): ToolResult {
        return try {
            val liters = params["liters"]?.toDoubleOrNull()
                ?: return ToolResult.error(name, "liters required. Sema: lita ngapi?", "MISSING_LITERS")

            if (liters <= 0) {
                return ToolResult.error(name, "Liters lazima iwe zaidi ya 0", "INVALID_LITERS")
            }

            val costPerLiter = params["cost_per_liter"]?.toDoubleOrNull() ?: DEFAULT_COST_PER_LITER
            val totalCost = params["total_cost"]?.toDoubleOrNull() ?: (liters * costPerLiter)
            val station = params["station"] ?: ""
            val odometer = params["odometer"]?.toDoubleOrNull()
            val date = params["date"] ?: DateTimeUtil.today()

            val purchase = FuelPurchaseEntity(
                liters = liters,
                costPerLiter = costPerLiter,
                totalCost = totalCost,
                stationName = station,
                odometer = odometer,
                date = date
            )
            val id = fuelPurchaseDao.insert(purchase)

            // Get today's totals
            val todayLiters = fuelPurchaseDao.getTotalLitersForDate(date)
            val todayCost = fuelPurchaseDao.getTotalCostForDate(date)
            val todayKm = tripKmDao.getTotalKmForDate(date)

            val costPerKm = if (todayKm > 0) todayCost / todayKm else null

            val report = buildString {
                appendLine("⛽ Mafuta imeongezwa!")
                appendLine("━━━━━━━━━━━━━━━━━━━━")
                appendLine("📏 Lita: ${"%.1f".format(liters)}")
                appendLine("💰 Bei: KES ${"%.0f".format(costPerLiter)}/lita")
                appendLine("💸 Jumla: KES ${"%.0f".format(totalCost)}")
                if (station.isNotEmpty()) appendLine("🏪 Station: $station")
                odometer?.let { appendLine("🔧 Odometer: ${"%.0f".format(it)} km") }
                appendLine()
                appendLine("📊 Leo jumla:")
                appendLine("   ⛽ Lita: ${"%.1f".format(todayLiters)}")
                appendLine("   💸 Gharama: KES ${"%.0f".format(todayCost)}")
                appendLine("   🛣️ Km: ${"%.0f".format(todayKm)}")
                if (costPerKm != null) {
                    appendLine("   📐 Gharama/km: KES ${"%.1f".format(costPerKm)}")
                    when {
                        costPerKm < 5 -> appendLine("   ✅ Nzuri sana!")
                        costPerKm < 8 -> appendLine("   👍 Inapita")
                        costPerKm < 12 -> appendLine("   ⚠️ Juu kidogo")
                        else -> appendLine("   🔴 Juu sana! Angalia injini na tairi.")
                    }
                }
            }

            ToolResult.success(
                name,
                data = mapOf(
                    "entry_id" to id,
                    "liters" to liters,
                    "cost_per_liter" to costPerLiter,
                    "total_cost" to totalCost,
                    "station" to station,
                    "today_liters" to todayLiters,
                    "today_cost" to todayCost,
                    "today_km" to todayKm,
                    "cost_per_km" to costPerKm
                ),
                message = report
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to add fuel")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // ADD KILOMETERS
    // ──────────────────────────────────────────────

    private suspend fun addKm(params: Map<String, String>): ToolResult {
        return try {
            val km = params["kilometers"]?.toDoubleOrNull()
                ?: return ToolResult.error(name, "kilometers required. Sema: umepanda km ngapi?", "MISSING_KM")

            if (km <= 0) {
                return ToolResult.error(name, "Kilometers lazima iwe zaidi ya 0", "INVALID_KM")
            }

            val route = params["route"] ?: ""
            val date = params["date"] ?: DateTimeUtil.today()

            val trip = TripKilometersEntity(
                kilometers = km,
                route = route,
                date = date
            )
            val id = tripKmDao.insert(trip)

            // Get today's totals
            val todayKm = tripKmDao.getTotalKmForDate(date)
            val todayFuelCost = fuelPurchaseDao.getTotalCostForDate(date)
            val todayLiters = fuelPurchaseDao.getTotalLitersForDate(date)

            val costPerKm = if (todayKm > 0) todayFuelCost / todayKm else null
            val kmPerLiter = if (todayLiters > 0) todayKm / todayLiters else null

            val report = buildString {
                appendLine("🛣️ Km imeongezwa!")
                appendLine("━━━━━━━━━━━━━━━━━━━━")
                appendLine("📏 Kilomita: ${"%.0f".format(km)}")
                if (route.isNotEmpty()) appendLine("📍 Route: $route")
                appendLine()
                appendLine("📊 Leo jumla:")
                appendLine("   🛣️ Km: ${"%.0f".format(todayKm)}")
                appendLine("   ⛽ Lita: ${"%.1f".format(todayLiters)}")
                if (costPerKm != null) {
                    appendLine("   📐 Gharama/km: KES ${"%.1f".format(costPerKm)}")
                }
                if (kmPerLiter != null) {
                    appendLine("   ⚡ Km/lita: ${"%.1f".format(kmPerLiter)}")
                    when {
                        kmPerLiter > 40 -> appendLine("   ✅ Injini yako ni nzuri!")
                        kmPerLiter > 30 -> appendLine("   👍 Kawaida")
                        kmPerLiter > 20 -> appendLine("   ⚠️ Chini — angalia tairi pressure na injini")
                        else -> appendLine("   🔴 Hatari! Labda mafuta inavuja au injini ina shida.")
                    }
                }
            }

            ToolResult.success(
                name,
                data = mapOf(
                    "entry_id" to id,
                    "kilometers" to km,
                    "today_km" to todayKm,
                    "today_liters" to todayLiters,
                    "cost_per_km" to costPerKm,
                    "km_per_liter" to kmPerLiter
                ),
                message = report
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to add km")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // SHOW EFFICIENCY
    // ──────────────────────────────────────────────

    private suspend fun showEfficiency(params: Map<String, String>): ToolResult {
        return try {
            val today = DateTimeUtil.today()
            val weekStart = weekStartDate()
            val monthStart = monthStartDate()

            // Today
            val todayFuelCost = fuelPurchaseDao.getTotalCostForDate(today)
            val todayLiters = fuelPurchaseDao.getTotalLitersForDate(today)
            val todayKm = tripKmDao.getTotalKmForDate(today)

            // This week
            val weekFuelCost = fuelPurchaseDao.getTotalCostBetween(weekStart, today)
            val weekLiters = fuelPurchaseDao.getTotalLitersBetween(weekStart, today)
            val weekKm = tripKmDao.getTotalKmBetween(weekStart, today)

            // This month
            val monthFuelCost = fuelPurchaseDao.getTotalCostBetween(monthStart, today)
            val monthLiters = fuelPurchaseDao.getTotalLitersBetween(monthStart, today)
            val monthKm = tripKmDao.getTotalKmBetween(monthStart, today)
            val avgCostPerLiter = fuelPurchaseDao.getAvgCostPerLiterBetween(monthStart, today)

            // Efficiency calculations
            val todayCostPerKm = if (todayKm > 0) todayFuelCost / todayKm else null
            val weekCostPerKm = if (weekKm > 0) weekFuelCost / weekKm else null
            val monthCostPerKm = if (monthKm > 0) monthFuelCost / monthKm else null
            val weekKmPerLiter = if (weekLiters > 0) weekKm / weekLiters else null
            val monthKmPerLiter = if (monthLiters > 0) monthKm / monthLiters else null

            val report = buildString {
                appendLine("⛽ *Ripoti ya Ufanisi wa Mafuta*")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()

                appendLine("📅 *Leo:*")
                appendLine("   ⛽ KES ${"%,.0f".format(todayFuelCost)} (${todayLiters}L)")
                appendLine("   🛣️ ${"%.0f".format(todayKm)} km")
                todayCostPerKm?.let { appendLine("   📐 KES ${"%.1f".format(it)}/km") }
                appendLine()

                appendLine("📅 *Wiki hii:*")
                appendLine("   ⛽ KES ${"%,.0f".format(weekFuelCost)} (${weekLiters}L)")
                appendLine("   🛣️ ${"%.0f".format(weekKm)} km")
                weekCostPerKm?.let { appendLine("   📐 KES ${"%.1f".format(it)}/km") }
                weekKmPerLiter?.let { appendLine("   ⚡ ${"%.1f".format(it)} km/lita") }
                appendLine()

                appendLine("📅 *Mwezi huu:*")
                appendLine("   ⛽ KES ${"%,.0f".format(monthFuelCost)} (${monthLiters}L)")
                appendLine("   🛣️ ${"%.0f".format(monthKm)} km")
                monthCostPerKm?.let { appendLine("   📐 KES ${"%.1f".format(it)}/km") }
                monthKmPerLiter?.let { appendLine("   ⚡ ${"%.1f".format(it)} km/lita") }
                avgCostPerLiter?.let { appendLine("   💰 Bei ya wastani: KES ${"%.1f".format(it)}/lita") }

                // Insights
                appendLine()
                appendLine("── Ushauri ──")
                if (monthKmPerLiter != null) {
                    when {
                        monthKmPerLiter > 40 -> appendLine("✅ Injini yako ni nzuri — endelea hivyo!")
                        monthKmPerLiter > 30 -> appendLine("👍 Ufanisi wa kawaida. Angalia tairi pressure.")
                        monthKmPerLiter > 20 -> {
                            appendLine("⚠️ Ufanisi wa chini. Angalia:")
                            appendLine("   • Tairi pressure (baridi = wastani)")
                            appendLine("   • Mafuta ya injini (badilisha kila km 3000)")
                            appendLine("   • Air filter (safisha kila wiki)")
                        }
                        else -> {
                            appendLine("🔴 Ufanisi mbaya! Hatari ya mafuta kuvuja au injini kuharibika.")
                            appendLine("   Pita garage haraka!")
                        }
                    }
                }

                if (monthCostPerKm != null && monthCostPerKm > 8) {
                    appendLine()
                    appendLine("💡 Gharama yako ni KES ${"%.1f".format(monthCostPerKm)}/km.")
                    appendLine("   Riders bora wanatumia KES 4-6/km.")
                    appendLine("   Okoa: panda polepole, epuka kusimama mara kwa mara.")
                }
            }

            ToolResult.success(
                name,
                data = mapOf(
                    "today" to mapOf("cost" to todayFuelCost, "liters" to todayLiters, "km" to todayKm, "cost_per_km" to todayCostPerKm),
                    "week" to mapOf("cost" to weekFuelCost, "liters" to weekLiters, "km" to weekKm, "cost_per_km" to weekCostPerKm),
                    "month" to mapOf("cost" to monthFuelCost, "liters" to monthLiters, "km" to monthKm, "cost_per_km" to monthCostPerKm, "avg_cost_per_liter" to avgCostPerLiter)
                ),
                message = report
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to show efficiency")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // COMPARE STATIONS
    // ──────────────────────────────────────────────

    private suspend fun compareStations(): ToolResult {
        return try {
            val monthStart = monthStartDate()
            val today = DateTimeUtil.today()
            val stations = fuelPurchaseDao.getStationComparison(monthStart, today)

            if (stations.isEmpty()) {
                return ToolResult.success(
                    name,
                    message = "🏪 Hakuna data za petrol station bado.\n" +
                            "Weka jina la station unapojaza mafuta — nitakulinganishia bei."
                )
            }

            val cheapest = stations.minByOrNull { it.avgPrice }
            val mostExpensive = stations.maxByOrNull { it.avgPrice }

            val report = buildString {
                appendLine("🏪 *Linganisho la Petrol Stations — Mwezi huu*")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()
                stations.forEachIndexed { i, s ->
                    val indicator = when (s) {
                        cheapest -> " ✅ (bei ndogo!)"
                        mostExpensive -> " ❌ (bei kubwa)"
                        else -> ""
                    }
                    appendLine("  ${i + 1}. ${s.stationName.ifEmpty { "Station ${i + 1}" }}")
                    appendLine("     💰 Bei: KES ${"%.1f".format(s.avgPrice)}/lita$indicator")
                    appendLine("     📊 Ziara: ${s.visits} | Jumla: KES ${"%,.0f".format(s.totalSpent)}")
                    appendLine()
                }

                if (cheapest != null && mostExpensive != null && cheapest != mostExpensive) {
                    val savings = (mostExpensive.avgPrice - cheapest.avgPrice) * 2 // per 2 liters
                    appendLine("💡 Jaza kwenye ${cheapest.stationName.ifEmpty { "station ya bei ndogo" }}:")
                    appendLine("   Okoa KES ${"%.0f".format(savings)} kila jaza (kwa lita 2)")
                    appendLine("   Okoa KES ${"%,.0f".format(savings * 15)} kwa mwezi (jaza mara 15)")
                }
            }

            ToolResult.success(
                name,
                data = mapOf("stations" to stations, "cheapest" to cheapest, "most_expensive" to mostExpensive),
                message = report
            )
        } catch (e: Exception) {
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // CHECK ALERTS (Fuel Theft Detection)
    // ──────────────────────────────────────────────

    private suspend fun checkAlerts(): ToolResult {
        return try {
            val today = DateTimeUtil.today()
            val weekStart = weekStartDate()
            val twoWeeksAgo = daysAgo(14)

            // Current week efficiency
            val weekLiters = fuelPurchaseDao.getTotalLitersBetween(weekStart, today)
            val weekKm = tripKmDao.getTotalKmBetween(weekStart, today)
            val weekKmPerLiter = if (weekLiters > 0) weekKm / weekLiters else null

            // Previous week efficiency
            val prevWeekStart = daysAgo(14)
            val prevWeekEnd = daysAgo(7)
            val prevWeekLiters = fuelPurchaseDao.getTotalLitersBetween(prevWeekStart, prevWeekEnd)
            val prevWeekKm = tripKmDao.getTotalKmBetween(prevWeekStart, prevWeekEnd)
            val prevWeekKmPerLiter = if (prevWeekLiters > 0) prevWeekKm / prevWeekLiters else null

            val alerts = mutableListOf<String>()

            // Check for efficiency drop (>20% decrease)
            if (weekKmPerLiter != null && prevWeekKmPerLiter != null) {
                val change = ((weekKmPerLiter - prevWeekKmPerLiter) / prevWeekKmPerLiter * 100)
                if (change < -20) {
                    alerts.add("🚨🚨🚨 KUPUNGUA KWA UFANISI WA MAFUTA!")
                    alerts.add("Wiki iliyopita: ${"%.1f".format(prevWeekKmPerLiter)} km/lita")
                    alerts.add("Wiki hii: ${"%.1f".format(weekKmPerLiter)} km/lita")
                    alerts.add("Kupungua: ${"%.0f".format(Math.abs(change))}%")
                    alerts.add("")
                    alerts.add("Sababu zinazowezekana:")
                    alerts.add("  • Mafuta inavuja (angalia tanki na bomba)")
                    alerts.add("  • Mtu anachukua mafuta (fuel theft)")
                    alerts.add("  • Injini ina shida (pita garage)")
                    alerts.add("  • Tairi zimelegea (angalia pressure)")
                } else if (change < -10) {
                    alerts.add("⚠️ Ufanisi wa mafuta umepungua ${"%.0f".format(Math.abs(change))}%")
                    alerts.add("   Wiki iliyopita: ${"%.1f".format(prevWeekKmPerLiter)} km/lita → Wiki hii: ${"%.1f".format(weekKmPerLiter)} km/lita")
                    alerts.add("   Angalia tairi pressure na injini.")
                }
            }

            // Check if fuel cost is too high relative to earnings
            val weekFuelCost = fuelPurchaseDao.getTotalCostBetween(weekStart, today)
            // We can't directly get income here, but we can flag high absolute cost
            if (weekFuelCost > 3000) {
                alerts.add("💰 Umefanya KES ${"%,.0f".format(weekFuelCost)} kwa wiki hii tu!")
                alerts.add("   Hiyo ni KES ${"%,.0f".format(weekFuelCost / 7)}/siku.")
                alerts.add("   Jaribu: panda polepole, epuka kusimama, jaza asubuhi.")
            }

            if (alerts.isEmpty()) {
                alerts.add("✅ Hakuna dalili za matatizo ya mafuta.")
                alerts.add("   Ufanisi wako uko sawa. Endelea hivyo!")
                if (weekKmPerLiter != null) {
                    alerts.add("   Wiki hii: ${"%.1f".format(weekKmPerLiter)} km/lita")
                }
            }

            val report = buildString {
                appendLine("🔍 *Uchunguzi wa Mafuta*")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()
                alerts.forEach { appendLine(it) }
            }

            ToolResult.success(
                name,
                data = mapOf(
                    "alerts" to alerts,
                    "week_km_per_liter" to weekKmPerLiter,
                    "prev_week_km_per_liter" to prevWeekKmPerLiter
                ),
                message = report
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to check alerts")
            ToolResult.error(name, "Failed: ${e.message}", "DB_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // VIEW HISTORY
    // ──────────────────────────────────────────────

    private suspend fun viewHistory(params: Map<String, String>): ToolResult {
        return try {
            val limit = params["limit"]?.toIntOrNull() ?: 20
            val purchases = fuelPurchaseDao.getRecent(limit).first()

            if (purchases.isEmpty()) {
                return ToolResult.success(
                    name,
                    message = "📜 Hakuna mafuta yaliyorekodiwa bado.\n" +
                            "Sema: 'Nimejaza lita mbili mia tatu' kuanza."
                )
            }

            val dateFormat = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
            val report = buildString {
                appendLine("📜 *Historia ya Mafuta*")
                appendLine("━━━━━━━━━━━━━━━━━━━━")
                purchases.forEach { p ->
                    appendLine("  ⛽ ${dateFormat.format(Date(p.timestamp))}")
                    appendLine("     ${"%.1f".format(p.liters)}L × KES ${"%.0f".format(p.costPerLiter)} = KES ${"%.0f".format(p.totalCost)}")
                    if (p.stationName.isNotEmpty()) appendLine("     🏪 ${p.stationName}")
                    p.odometer?.let { appendLine("     🔧 Odo: ${"%.0f".format(it)} km") }
                }
            }

            ToolResult.success(name, data = mapOf("purchases" to purchases), message = report)
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
            // Fuel purchase patterns
            lower.contains(Regex("jaza|niliweka|nimejaza|petroli|mafuta.*lita|fuel.*liter")) -> {
                params["action"] = "add_fuel"
                extractFuelDetails(lower, params)
            }
            // Kilometers patterns
            lower.contains(Regex("km|kilomita|kilometre|panda.*km|nimepanda")) -> {
                params["action"] = "add_km"
                extractKmDetails(lower, params)
            }
            // Efficiency query
            lower.contains(Regex("fanisi|efficiency|mpira|mangu|inakwenda vipi|status|ripoti.*mafuta")) -> {
                params["action"] = "efficiency"
            }
            // Compare stations
            lower.contains(Regex("linganisha|compare|station|bei.*petrol|wapi.*cheap")) -> {
                params["action"] = "compare"
            }
            // Alert check
            lower.contains(Regex("alert|hatari|vuja|theft|shida.*mafuta|angalia")) -> {
                params["action"] = "alert"
            }
            // History
            lower.contains(Regex("historia|history|record|zangu.*mafuta")) -> {
                params["action"] = "history"
            }
        }

        return params
    }

    private fun extractFuelDetails(text: String, params: MutableMap<String, String>) {
        // Extract liters: "lita mbili", "2 liters", "lita 2"
        val swahiliNumbers = mapOf(
            "moja" to 1.0, "mbili" to 2.0, "tatu" to 3.0, "nne" to 4.0, "tano" to 5.0,
            "sita" to 6.0, "saba" to 7.0, "nane" to 8.0, "tisa" to 9.0, "kumi" to 10.0,
            "nusu" to 0.5
        )

        // "lita X" pattern
        for ((word, value) in swahiliNumbers) {
            if (text.contains("lita $word")) {
                params["liters"] = value.toString()
                break
            }
        }
        if (!params.containsKey("liters")) {
            Regex("lita\\s*(\\d+\\.?\\d*)").find(text)?.let {
                params["liters"] = it.groupValues[1]
            }
        }

        // Extract cost: "mia tatu" = 300, "300", "ksh 300"
        val costMatch = Regex("""(?:mia|ksh|kes|pesa)\s*(\d+\.?\d*)""").find(text)
        costMatch?.let { params["total_cost"] = it.groupValues[1] }

        // Try Swahili amounts
        for ((word, value) in swahiliNumbers) {
            if (text.contains("mia $word")) {
                params["total_cost"] = (value * 100).toInt().toString()
                break
            }
        }
        if (!params.containsKey("total_cost")) {
            Regex("""(\d+\.?\d*)""").find(text)?.let {
                val num = it.groupValues[1].toDoubleOrNull()
                if (num != null && num > 50) { // likely a cost, not liters
                    params["total_cost"] = num.toInt().toString()
                }
            }
        }

        // Extract station name (simple: word after "station" or "kwa")
        Regex("""(?:station|kwa)\s+(\w+)""", RegexOption.IGNORE_CASE).find(text)?.let {
            params["station"] = it.groupValues[1].replaceFirstChar { c -> c.uppercase() }
        }
    }

    private fun extractKmDetails(text: String, params: MutableMap<String, String>) {
        val swahiliNumbers = mapOf(
            "moja" to 1.0, "mbili" to 2.0, "tatu" to 3.0, "nne" to 4.0, "tano" to 5.0,
            "sita" to 6.0, "saba" to 7.0, "nane" to 8.0, "tisa" to 9.0, "kumi" to 10.0,
            "ishirini" to 20.0, "thelathini" to 30.0, "arobaini" to 40.0, "hamsini" to 50.0,
            "sitini" to 60.0, "sabini" to 70.0, "themanini" to 80.0, "tisini" to 90.0,
            "mia" to 100.0
        )

        for ((word, value) in swahiliNumbers) {
            if (text.contains(word)) {
                params["kilometers"] = value.toString()
                break
            }
        }
        if (!params.containsKey("kilometers")) {
            Regex("""(\d+\.?\d*)\s*(?:km|kilomita)""").find(text)?.let {
                params["kilometers"] = it.groupValues[1]
            }
        }
        if (!params.containsKey("kilometers")) {
            Regex("""(?:km|kilomita)\s*(\d+\.?\d*)""").find(text)?.let {
                params["kilometers"] = it.groupValues[1]
            }
        }
    }

    // ──────────────────────────────────────────────
    // HELPERS
    // ──────────────────────────────────────────────

    private fun weekStartDate(): String {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)
    }

    private fun monthStartDate(): String {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)
    }

    private fun daysAgo(days: Int): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_MONTH, -days)
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)
    }

    companion object {
        const val DEFAULT_COST_PER_LITER = 185.0 // KES, approximate 2026 price
    }
}
