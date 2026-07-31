package com.msaidizi.agent.tools.agriculture

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import javax.inject.Inject
import javax.inject.Singleton
import com.msaidizi.agent.tools.core.*

/**
 * FishTripPlanner — Trip planning, catch tracking, and earnings for fishers.
 * Extends FishingLog with trip-level planning and cost analysis.
 */
@Singleton
class FishTripPlanner @Inject constructor(private val context: Context) : Tool {
    override val name = "fish_trip_planner"
    override val description = "Plan fishing trips, track catch vs. costs, compare landing site prices, predict best days."

    override val argsSchema = argSchema {
        enum("action", "Action", listOf(
            "plan_trip", "record_catch", "trip_summary", "compare_sites", "best_days", "earnings"
        ))
        string("location", "Fishing location", required = false)
        string("species", "Fish species", required = false)
        number("weight_kg", "Catch weight in kg", required = false)
        number("price_per_kg", "Price per kg at landing site", required = false)
        string("market", "Landing site name", required = false)
        number("fuel_cost", "Fuel cost in KES", required = false)
        number("crew_cost", "Crew payment in KES", required = false)
        string("period", "week|month", required = false)
    }

    inner class FishDb(ctx: Context) : SQLiteOpenHelper(ctx, "fish_trips.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE trips (id INTEGER PRIMARY KEY AUTOINCREMENT, location TEXT, fuel_cost REAL DEFAULT 0, crew_cost REAL DEFAULT 0, total_catch_kg REAL DEFAULT 0, total_earnings REAL DEFAULT 0, recorded_at INTEGER)")
            db.execSQL("CREATE TABLE catches (id INTEGER PRIMARY KEY AUTOINCREMENT, trip_id INTEGER, species TEXT, weight_kg REAL, price_per_kg REAL, market TEXT, recorded_at INTEGER)")
        }
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
            db.execSQL("DROP TABLE IF EXISTS trips"); db.execSQL("DROP TABLE IF EXISTS catches"); onCreate(db)
        }
    }

    private var db: FishDb? = null
    private fun getDb(): SQLiteDatabase { if (db == null) db = FishDb(context); return db!!.writableDatabase }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        return when (params["action"]) {
            "plan_trip" -> planTrip(params)
            "record_catch" -> recordCatch(params)
            "trip_summary" -> tripSummary(params)
            "compare_sites" -> compareSites(params)
            "best_days" -> bestDays(params)
            "earnings" -> earnings(params)
            else -> ToolResult.error(name, "Action required", "MISSING_ACTION")
        }
    }

    private fun planTrip(params: Map<String, String>): ToolResult {
        val location = params["location"] ?: return ToolResult.error(name, "Location required", "MISSING_LOCATION")
        val fuel = params["fuel_cost"]?.toDoubleOrNull() ?: 0.0
        val crew = params["crew_cost"]?.toDoubleOrNull() ?: 0.0

        val d = getDb()
        val v = ContentValues().apply {
            put("location", location); put("fuel_cost", fuel); put("crew_cost", crew)
            put("recorded_at", System.currentTimeMillis())
        }
        val id = d.insert("trips", null, v)
        return ToolResult.success(name, mapOf("trip_id" to id, "location" to location, "total_cost" to (fuel + crew)),
            "⛵ Safari yamepangwa: $location\n• Mafuta: KES $fuel\n• Crew: KES $crew\n• Jumla: KES ${fuel + crew}")
    }

    private fun recordCatch(params: Map<String, String>): ToolResult {
        val species = params["species"] ?: return ToolResult.error(name, "Species required", "MISSING_SPECIES")
        val weight = params["weight_kg"]?.toDoubleOrNull() ?: return ToolResult.error(name, "Weight required", "MISSING_WEIGHT")
        val price = params["price_per_kg"]?.toDoubleOrNull() ?: 300.0
        val market = params["market"] ?: ""

        val d = getDb()
        val v = ContentValues().apply {
            put("species", species); put("weight_kg", weight); put("price_per_kg", price)
            put("market", market); put("recorded_at", System.currentTimeMillis())
        }
        d.insert("catches", null, v)

        val value = weight * price
        return ToolResult.success(name, mapOf("species" to species, "weight" to weight, "value" to value),
            "✅ $species: ${weight}kg × KES $price = KES $value")
    }

    private fun tripSummary(params: Map<String, String>): ToolResult {
        val d = getDb()
        val weekAgo = System.currentTimeMillis() - 7 * 86400000L

        val tripCursor = d.rawQuery("SELECT SUM(fuel_cost), SUM(crew_cost), SUM(total_catch_kg), SUM(total_earnings), COUNT(*) FROM trips WHERE recorded_at >= ?", arrayOf(weekAgo.toString()))
        var fuel = 0.0; var crew = 0.0; var catch = 0.0; var earnings = 0.0; var trips = 0
        tripCursor.use { if (it.moveToFirst()) { fuel = it.getDouble(0); crew = it.getDouble(1); catch = it.getDouble(2); earnings = it.getDouble(3); trips = it.getInt(4) } }

        val catchCursor = d.rawQuery("SELECT species, SUM(weight_kg), SUM(weight_kg * price_per_kg) FROM catches WHERE recorded_at >= ? GROUP BY species", arrayOf(weekAgo.toString()))
        val species = mutableListOf<Triple<String, Double, Double>>()
        catchCursor.use { while (it.moveToNext()) species.add(Triple(it.getString(0), it.getDouble(1), it.getDouble(2))) }

        val msg = buildString {
            append("🐟 Muhtasari wa wiki:\n• Safari: $trips\n")
            species.forEach { (s, w, v) -> append("• $s: ${w}kg = KES $v\n") }
            append("• Mafuta: KES $fuel\n• Crew: KES $crew\n• Faida: KES ${earnings - fuel - crew}")
        }
        return ToolResult.success(name, mapOf("trips" to trips, "earnings" to earnings, "costs" to (fuel + crew)), msg)
    }

    private fun compareSites(params: Map<String, String>): ToolResult {
        val species = params["species"] ?: return ToolResult.error(name, "Species required", "MISSING_SPECIES")
        val d = getDb()
        val weekAgo = System.currentTimeMillis() - 7 * 86400000L
        val cursor = d.rawQuery("SELECT market, AVG(price_per_kg), COUNT(*) FROM catches WHERE species = ? AND recorded_at >= ? AND market != '' GROUP BY market ORDER BY AVG(price_per_kg) DESC", arrayOf(species, weekAgo.toString()))

        val sites = mutableListOf<Triple<String, Double, Int>>()
        cursor.use { while (it.moveToNext()) sites.add(Triple(it.getString(0), it.getDouble(1), it.getInt(2))) }

        if (sites.isEmpty()) return ToolResult.success(name, mapOf(), "Hakuna data ya bei ya $species bandarini.")

        val msg = buildString {
            append("💰 Bei ya $species bandarini:\n")
            sites.forEach { (m, p, c) -> append("• $m: KES $p/kg ($c samples)\n") }
        }
        return ToolResult.success(name, mapOf("sites" to sites.size), msg)
    }

    private fun bestDays(params: Map<String, String>): ToolResult {
        val d = getDb()
        val yearAgo = System.currentTimeMillis() - 365L * 86400000
        val cursor = d.rawQuery("SELECT strftime('%w', recorded_at / 1000, 'unixepoch') as dow, AVG(total_catch_kg), COUNT(*) FROM trips WHERE recorded_at >= ? GROUP BY dow ORDER BY AVG(total_catch_kg) DESC", arrayOf(yearAgo.toString()))

        val days = arrayOf("Jumapili", "Jumatatu", "Jumanne", "Jumatano", "Alhamisi", "Ijumaa", "Jumamosi")
        val stats = mutableListOf<Triple<String, Double, Int>>()
        cursor.use { while (it.moveToNext()) stats.add(Triple(days[it.getInt(0)], it.getDouble(1), it.getInt(2))) }

        if (stats.isEmpty()) return ToolResult.success(name, mapOf(), "Hakuna data ya kutosha.")

        val msg = buildString {
            append("📅 Siku bora za kuvua:\n")
            stats.forEach { (day, avg, count) -> append("• $day: wastani ${avg}kg ($count safari)\n") }
        }
        return ToolResult.success(name, mapOf("best_day" to stats.first().first), msg)
    }

    private fun earnings(params: Map<String, String>): ToolResult {
        val period = params["period"] ?: "month"
        val d = getDb()
        val cutoff = System.currentTimeMillis() - if (period == "month") 30L else 7L * 86400000

        val cursor = d.rawQuery("SELECT species, SUM(weight_kg), SUM(weight_kg * price_per_kg) FROM catches WHERE recorded_at >= ? GROUP BY species", arrayOf(cutoff.toString()))
        var total = 0.0
        val species = mutableListOf<Triple<String, Double, Double>>()
        cursor.use { while (it.moveToNext()) { val v = it.getDouble(2); total += v; species.add(Triple(it.getString(0), it.getDouble(1), v)) } }

        val costCursor = d.rawQuery("SELECT SUM(fuel_cost + crew_cost) FROM trips WHERE recorded_at >= ?", arrayOf(cutoff.toString()))
        var costs = 0.0
        costCursor.use { if (it.moveToFirst()) costs = it.getDouble(0) }

        val msg = buildString {
            append("💰 Mapato ($period):\n")
            species.forEach { (s, w, v) -> append("• $s: ${w}kg = KES $v\n") }
            append("• Jumla: KES $total\n• Gharama: KES $costs\n• Faida: KES ${total - costs}")
        }
        return ToolResult.success(name, mapOf("earnings" to total, "costs" to costs, "profit" to (total - costs)), msg)
    }
}
