package com.msaidizi.agent.tools.transport

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import com.msaidizi.agent.tools.core.*

/**
 * DailyEarningsTracker — Per-trip earnings tracking for all transport types.
 *
 * Tracks earnings by route, time, passenger count for:
 * Boda Boda, Tuk-tuk, Matatu, Taxi, Delivery riders
 *
 * Voice examples:
 *   "Nimepata mia tatu Town hadi Westlands" → record_trip
 *   "Leo nimepata ngapi?" → daily_summary
 *   "Route gani inalipa zaidi?" → by_route
 */
@Singleton
class DailyEarningsTracker @Inject constructor(
    private val context: Context
) : Tool {

    override val name = "daily_earnings_tracker"
    override val description = "Track per-trip earnings for transport workers — fares, routes, fuel costs, daily summaries."

    override val argsSchema = argSchema {
        enum("action", "Action to perform", listOf(
            "record_trip", "daily_summary", "weekly", "by_route", "by_time", "target"
        ))
        string("vehicle_type", "Vehicle type: boda_boda|tuk_tuk|matatu|taxi", required = false)
        number("fare_amount", "Fare collected in KES", required = false)
        string("route", "Route description e.g. 'Town → Westlands'", required = false)
        number("passenger_count", "Number of passengers", required = false)
        number("trip_duration_minutes", "Trip duration in minutes", required = false)
        string("payment_method", "cash|mpesa", required = false)
        number("fuel_cost", "Fuel cost for this trip in KES", required = false)
        number("target", "Daily earnings target in KES", required = false)
        string("period", "Period: day|week|month", required = false)
    }

    inner class EarningsDatabase(context: Context) :
        SQLiteOpenHelper(context, "daily_earnings.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE trips (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    vehicle_type TEXT,
                    fare_amount REAL NOT NULL,
                    route_from TEXT,
                    route_to TEXT,
                    passenger_count INTEGER DEFAULT 1,
                    duration_minutes INTEGER,
                    fuel_cost REAL DEFAULT 0,
                    payment_method TEXT DEFAULT 'cash',
                    recorded_at INTEGER NOT NULL
                )
            """)
            db.execSQL("CREATE INDEX idx_trips_date ON trips(recorded_at)")
            db.execSQL("CREATE INDEX idx_trips_route ON trips(route_from, route_to)")
        }
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
            db.execSQL("DROP TABLE IF EXISTS trips"); onCreate(db)
        }
    }

    private var dbHelper: EarningsDatabase? = null
    private fun getDb(): SQLiteDatabase {
        if (dbHelper == null) dbHelper = EarningsDatabase(context)
        return dbHelper!!.writableDatabase
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        return when (params["action"]) {
            "record_trip" -> recordTrip(params)
            "daily_summary" -> dailySummary(params)
            "weekly" -> weeklySummary(params)
            "by_route" -> byRoute(params)
            "by_time" -> byTime(params)
            "target" -> setTarget(params)
            else -> ToolResult.error(name, "Action required", "MISSING_ACTION")
        }
    }

    private fun recordTrip(params: Map<String, String>): ToolResult {
        val fare = params["fare_amount"]?.toDoubleOrNull()
            ?: return ToolResult.error(name, "Fare amount required", "MISSING_FARE")
        val route = params["route"] ?: ""
        val parts = route.split("→", "->", "-").map { it.trim() }
        val routeFrom = parts.getOrNull(0) ?: ""
        val routeTo = parts.getOrNull(1) ?: ""
        val passengers = params["passenger_count"]?.toIntOrNull() ?: 1
        val duration = params["trip_duration_minutes"]?.toIntOrNull()
        val fuel = params["fuel_cost"]?.toDoubleOrNull() ?: 0.0
        val payment = params["payment_method"] ?: "cash"
        val vehicle = params["vehicle_type"] ?: ""

        val db = getDb()
        val values = ContentValues().apply {
            put("vehicle_type", vehicle)
            put("fare_amount", fare)
            put("route_from", routeFrom)
            put("route_to", routeTo)
            put("passenger_count", passengers)
            put("duration_minutes", duration)
            put("fuel_cost", fuel)
            put("payment_method", payment)
            put("recorded_at", System.currentTimeMillis())
        }
        val tripId = db.insert("trips", null, values)

        val net = fare - fuel
        val msg = buildString {
            append("✅ Safari yamerekodwa (Id: $tripId)\n")
            append("• Nauli: KES ${formatP(fare)}")
            if (route.isNotBlank()) append(" | $route")
            append("\n• Mafuta: KES ${formatP(fuel)}")
            append("\n• Faida: KES ${formatP(net)}")
            if (passengers > 1) append("\n• Abiria: $passengers")
        }
        return ToolResult.success(name, mapOf(
            "trip_id" to tripId, "fare" to fare, "fuel" to fuel, "net" to net,
            "route" to route, "passengers" to passengers
        ), msg)
    }

    private fun dailySummary(params: Map<String, String>): ToolResult {
        val db = getDb()
        val today = System.currentTimeMillis() / 86400000 * 86400000
        val cursor = db.rawQuery("""
            SELECT COUNT(*), SUM(fare_amount), SUM(fuel_cost), SUM(passenger_count)
            FROM trips WHERE recorded_at >= ?
        """, arrayOf(today.toString()))

        cursor.use {
            if (it.moveToFirst()) {
                val trips = it.getInt(0)
                val totalFare = it.getDouble(1)
                val totalFuel = it.getDouble(2)
                val totalPassengers = it.getInt(3)
                val net = totalFare - totalFuel

                val msg = "📊 Leo:\n• Safari: $trips\n• Nauli: KES ${formatP(totalFare)}\n• Mafuta: KES ${formatP(totalFuel)}\n• Faida: KES ${formatP(net)}\n• Abiria: $totalPassengers"
                return ToolResult.success(name, mapOf(
                    "trips" to trips, "total_fare" to totalFare,
                    "total_fuel" to totalFuel, "net" to net
                ), msg)
            }
        }
        return ToolResult.success(name, mapOf("trips" to 0), "Hakuna safari leo.")
    }

    private fun weeklySummary(params: Map<String, String>): ToolResult {
        val db = getDb()
        val weekAgo = System.currentTimeMillis() - 7 * 86400000L
        val cursor = db.rawQuery("""
            SELECT COUNT(*), SUM(fare_amount), SUM(fuel_cost)
            FROM trips WHERE recorded_at >= ?
        """, arrayOf(weekAgo.toString()))

        cursor.use {
            if (it.moveToFirst()) {
                val trips = it.getInt(0)
                val fare = it.getDouble(1)
                val fuel = it.getDouble(2)
                val msg = "📊 Wiki hii:\n• Safari: $trips\n• Nauli: KES ${formatP(fare)}\n• Mafuta: KES ${formatP(fuel)}\n• Faida: KES ${formatP(fare - fuel)}"
                return ToolResult.success(name, mapOf("trips" to trips, "fare" to fare, "fuel" to fuel), msg)
            }
        }
        return ToolResult.success(name, mapOf(), "Hakuna data ya wiki.")
    }

    private fun byRoute(params: Map<String, String>): ToolResult {
        val db = getDb()
        val weekAgo = System.currentTimeMillis() - 7 * 86400000L
        val cursor = db.rawQuery("""
            SELECT route_from, route_to, COUNT(*), AVG(fare_amount), SUM(fare_amount)
            FROM trips WHERE recorded_at >= ? AND route_from != ''
            GROUP BY route_from, route_to ORDER BY SUM(fare_amount) DESC LIMIT 10
        """, arrayOf(weekAgo.toString()))

        val routes = mutableListOf<Triple<String, Int, Double>>()
        cursor.use {
            while (it.moveToNext()) {
                val route = "${it.getString(0)} → ${it.getString(1)}"
                routes.add(Triple(route, it.getInt(2), it.getDouble(4)))
            }
        }

        if (routes.isEmpty()) return ToolResult.success(name, mapOf(), "Hakuna data ya routes.")

        val msg = buildString {
            append("🗺️ Routes bora wiki hii:\n")
            routes.forEach { (route, count, total) ->
                append("• $route: $count safari, KES ${formatP(total)}\n")
            }
        }
        return ToolResult.success(name, mapOf("routes" to routes.size), msg)
    }

    private fun byTime(params: Map<String, String>): ToolResult {
        val db = getDb()
        val weekAgo = System.currentTimeMillis() - 7 * 86400000L
        val cursor = db.rawQuery("""
            SELECT strftime('%H', recorded_at / 1000, 'unixepoch') as hour,
                   COUNT(*), SUM(fare_amount)
            FROM trips WHERE recorded_at >= ?
            GROUP BY hour ORDER BY SUM(fare_amount) DESC
        """, arrayOf(weekAgo.toString()))

        val hours = mutableListOf<Pair<String, Double>>()
        cursor.use {
            while (it.moveToNext()) {
                hours.add(Pair("${it.getString(0)}:00", it.getDouble(2)))
            }
        }

        if (hours.isEmpty()) return ToolResult.success(name, mapOf(), "Hakuna data ya saa.")

        val msg = buildString {
            append("⏰ Saa bora wiki hii:\n")
            hours.take(5).forEach { (hour, total) ->
                append("• $hour: KES ${formatP(total)}\n")
            }
        }
        return ToolResult.success(name, mapOf("peak_hours" to hours.take(3).map { it.first }), msg)
    }

    private fun setTarget(params: Map<String, String>): ToolResult {
        val target = params["target"]?.toDoubleOrNull()
            ?: return ToolResult.error(name, "Target amount required", "MISSING_TARGET")
        // Store target in SharedPreferences
        val prefs = context.getSharedPreferences("earnings_target", Context.MODE_PRIVATE)
        prefs.edit().putFloat("daily_target", target.toFloat()).apply()
        return ToolResult.success(name, mapOf("target" to target),
            "🎯 Target ya siku: KES ${formatP(target)}. Nitakujulisha ukifika!")
    }

    private fun formatP(v: Double): String = if (v == v.toLong().toDouble()) "%,.0f".format(v) else "%,.1f".format(v)
}
