package com.msaidizi.agent.tools.transport

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import javax.inject.Inject
import javax.inject.Singleton
import com.msaidizi.agent.tools.core.*

/**
 * VehicleMaintenanceTracker — Vehicle maintenance scheduling for all transport types.
 * Oil changes, tire replacement, insurance renewal, inspections.
 */
@Singleton
class VehicleMaintenanceTracker @Inject constructor(private val context: Context) : Tool {
    override val name = "vehicle_maintenance"
    override val description = "Track vehicle maintenance — oil changes, tires, insurance, repairs. Get overdue alerts."

    override val argsSchema = argSchema {
        enum("action", "Action", listOf("add", "record", "overdue", "cost_summary", "insurance"))
        string("vehicle_type", "boda_boda|tuk_tuk|matatu|car", required = false)
        string("maintenance_type", "oil_change|tire|brake|insurance|service", required = false)
        number("cost", "Cost in KES", required = false)
        number("odometer_km", "Current odometer in km", required = false)
        string("mechanic", "Mechanic name", required = false)
        string("notes", "Notes", required = false)
    }

    inner class MaintDb(ctx: Context) : SQLiteOpenHelper(ctx, "vehicle_maint.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE maintenance (id INTEGER PRIMARY KEY AUTOINCREMENT, vehicle_type TEXT, maint_type TEXT, cost REAL, odometer INTEGER, mechanic TEXT, notes TEXT, recorded_at INTEGER)")
            db.execSQL("CREATE INDEX idx_maint_type ON maintenance(maint_type)")
        }
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) { db.execSQL("DROP TABLE IF EXISTS maintenance"); onCreate(db) }
    }

    private var db: MaintDb? = null
    private fun getDb(): SQLiteDatabase { if (db == null) db = MaintDb(context); return db!!.writableDatabase }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        return when (params["action"]) {
            "record" -> record(params)
            "overdue" -> overdue(params)
            "cost_summary" -> costSummary(params)
            "insurance" -> insurance(params)
            "add" -> record(params)
            else -> ToolResult.error(name, "Action required", "MISSING_ACTION")
        }
    }

    private fun record(params: Map<String, String>): ToolResult {
        val type = params["maintenance_type"] ?: return ToolResult.error(name, "Type required", "MISSING_TYPE")
        val cost = params["cost"]?.toDoubleOrNull() ?: 0.0
        val d = getDb()
        val v = ContentValues().apply {
            put("vehicle_type", params["vehicle_type"] ?: ""); put("maint_type", type)
            put("cost", cost); put("odometer", params["odometer_km"]?.toIntOrNull())
            put("mechanic", params["mechanic"] ?: ""); put("notes", params["notes"] ?: "")
            put("recorded_at", System.currentTimeMillis())
        }
        d.insert("maintenance", null, v)
        return ToolResult.success(name, mapOf("type" to type, "cost" to cost),
            "✅ Matengenezo yamerekodwa: $type — KES $cost")
    }

    private fun overdue(params: Map<String, String>): ToolResult {
        val d = getDb()
        val now = System.currentTimeMillis()
        val thirtyDaysAgo = now - 30 * 86400000L

        // Check last maintenance by type
        val cursor = d.rawQuery("""
            SELECT maint_type, MAX(recorded_at), COUNT(*)
            FROM maintenance GROUP BY maint_type
        """, null)

        val overdue = mutableListOf<String>()
        cursor.use {
            while (it.moveToNext()) {
                val type = it.getString(0)
                val lastDone = it.getLong(1)
                val daysSince = (now - lastDone) / 86400000

                val recommendedDays = when (type) {
                    "oil_change" -> 30L
                    "tire" -> 180L
                    "brake" -> 90L
                    "service" -> 90L
                    "insurance" -> 365L
                    else -> 90L
                }

                if (daysSince > recommendedDays) {
                    overdue.add("$type: siku $daysSince (inapaswa kila siku $recommendedDays)")
                }
            }
        }

        if (overdue.isEmpty()) return ToolResult.success(name, mapOf(), "✅ Matengenezo yote yako sawa!")

        val msg = buildString {
            append("⚠️ Matengenezo yanayohitajika:\n")
            overdue.forEach { append("• $it\n") }
        }
        return ToolResult.success(name, mapOf("overdue" to overdue), msg)
    }

    private fun costSummary(params: Map<String, String>): ToolResult {
        val d = getDb()
        val monthAgo = System.currentTimeMillis() - 30 * 86400000L
        val cursor = d.rawQuery("SELECT maint_type, SUM(cost), COUNT(*) FROM maintenance WHERE recorded_at >= ? GROUP BY maint_type", arrayOf(monthAgo.toString()))

        var total = 0.0
        val msg = buildString {
            append("💰 Gharama za matengenezo (mwezi):\n")
            cursor.use {
                while (it.moveToNext()) {
                    val cost = it.getDouble(1)
                    total += cost
                    append("• ${it.getString(0)}: KES $cost (${it.getInt(2)}x)\n")
                }
            }
            append("• Jumla: KES $total")
        }
        return ToolResult.success(name, mapOf("total" to total), msg)
    }

    private fun insurance(params: Map<String, String>): ToolResult {
        val d = getDb()
        val cursor = d.rawQuery("SELECT recorded_at, cost, notes FROM maintenance WHERE maint_type = 'insurance' ORDER BY recorded_at DESC LIMIT 1", null)
        cursor.use {
            if (it.moveToFirst()) {
                val date = it.getLong(0)
                val cost = it.getDouble(1)
                val expiry = date + 365L * 86400000
                val daysRemaining = (expiry - System.currentTimeMillis()) / 86400000
                return ToolResult.success(name, mapOf("cost" to cost, "days_remaining" to daysRemaining),
                    "🛡️ Bima:\n• Gharama: KES $cost\n• Siku zilizobaki: $daysRemaining")
            }
        }
        return ToolResult.success(name, mapOf(), "Hakuna data ya bima.")
    }
}
