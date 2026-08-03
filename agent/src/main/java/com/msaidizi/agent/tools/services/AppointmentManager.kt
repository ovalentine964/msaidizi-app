package com.msaidizi.agent.tools.services

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext
import com.msaidizi.agent.tools.core.*

/**
 * AppointmentManager — Full appointment lifecycle for service workers.
 * Extends BookingScheduler with reminders, no-show recovery, walk-in tracking.
 */
@Singleton
class AppointmentManager @Inject constructor(@ApplicationContext private val context: Context) : Tool {
    override val name = "appointment_manager"
    override val description = "Manage appointments — book, cancel, track no-shows, walk-ins, daily summary."

    override val argsSchema = argSchema {
        enum("action", "Action", listOf("book", "cancel", "today", "no_show", "walk_in", "complete"))
        string("customer_phone", "Customer phone", required = false)
        string("service_type", "Service type", required = false)
        string("appointment_time", "ISO datetime", required = false)
        number("duration_minutes", "Duration in minutes", required = false)
        string("notes", "Notes", required = false)
        number("deposit_amount", "Deposit in KES", required = false)
    }

    inner class ApptDb(ctx: Context) : SQLiteOpenHelper(ctx, "appointments.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE appointments (id INTEGER PRIMARY KEY AUTOINCREMENT, customer_phone TEXT, service_type TEXT, scheduled_time INTEGER, duration INTEGER, status TEXT DEFAULT 'booked', deposit REAL DEFAULT 0, notes TEXT, created_at INTEGER)")
        }
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) { db.execSQL("DROP TABLE IF EXISTS appointments"); onCreate(db) }
    }

    private var db: ApptDb? = null
    private fun getDb(): SQLiteDatabase { if (db == null) db = ApptDb(context); return db!!.writableDatabase }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        return when (params["action"]) {
            "book" -> book(params)
            "today" -> today(params)
            "no_show" -> noShow(params)
            "walk_in" -> walkIn(params)
            "complete" -> complete(params)
            "cancel" -> cancel(params)
            else -> ToolResult.error(name, "Action required", "MISSING_ACTION")
        }
    }

    private fun book(params: Map<String, String>): ToolResult {
        val phone = params["customer_phone"] ?: return ToolResult.error(name, "Phone required", "MISSING_PHONE")
        val service = params["service_type"] ?: "general"
        val time = params["appointment_time"] ?: ""
        val duration = params["duration_minutes"]?.toIntOrNull() ?: 30
        val deposit = params["deposit_amount"]?.toDoubleOrNull() ?: 0.0
        val notes = params["notes"] ?: ""

        val d = getDb()
        val v = ContentValues().apply {
            put("customer_phone", phone); put("service_type", service)
            put("scheduled_time", System.currentTimeMillis()); put("duration", duration)
            put("deposit", deposit); put("notes", notes); put("created_at", System.currentTimeMillis())
        }
        val id = d.insert("appointments", null, v)
        return ToolResult.success(name, mapOf("appointment_id" to id, "service" to service),
            "✅ Appointment yamerekodwa (Id: $id)\n• Huduma: $service\n• Simu: $phone")
    }

    private fun today(params: Map<String, String>): ToolResult {
        val d = getDb()
        val today = System.currentTimeMillis() / 86400000 * 86400000
        val cursor = d.rawQuery("SELECT status, COUNT(*) FROM appointments WHERE created_at >= ? GROUP BY status", arrayOf(today.toString()))
        val counts = mutableMapOf<String, Int>()
        cursor.use { while (it.moveToNext()) counts[it.getString(0)] = it.getInt(1) }

        val booked = counts["booked"] ?: 0
        val completed = counts["completed"] ?: 0
        val noShows = counts["no_show"] ?: 0
        val msg = "📅 Leo:\n• Zilizobokeka: $booked\n• Zilizokamilika: $completed\n• Hazikuja: $noShows"
        return ToolResult.success(name, mapOf("booked" to booked, "completed" to completed, "no_shows" to noShows), msg)
    }

    private fun noShow(params: Map<String, String>): ToolResult {
        val id = params["appointment_id"] ?: return ToolResult.error(name, "ID required", "MISSING_ID")
        getDb().execSQL("UPDATE appointments SET status = 'no_show' WHERE id = $id")
        return ToolResult.success(name, mapOf("id" to id), "❌ Appointment $id imepita bila mteja.")
    }

    private fun walkIn(params: Map<String, String>): ToolResult {
        val service = params["service_type"] ?: "general"
        val d = getDb()
        val v = ContentValues().apply {
            put("customer_phone", "walk_in"); put("service_type", service)
            put("status", "completed"); put("created_at", System.currentTimeMillis())
        }
        val id = d.insert("appointments", null, v)
        return ToolResult.success(name, mapOf("id" to id), "✅ Walk-in yamerekodwa: $service")
    }

    private fun complete(params: Map<String, String>): ToolResult {
        val id = params["appointment_id"] ?: return ToolResult.error(name, "ID required", "MISSING_ID")
        getDb().execSQL("UPDATE appointments SET status = 'completed' WHERE id = $id")
        return ToolResult.success(name, mapOf("id" to id), "✅ Appointment $id imekamilika!")
    }

    private fun cancel(params: Map<String, String>): ToolResult {
        val id = params["appointment_id"] ?: return ToolResult.error(name, "ID required", "MISSING_ID")
        getDb().execSQL("UPDATE appointments SET status = 'cancelled' WHERE id = $id")
        return ToolResult.success(name, mapOf("id" to id), "❌ Appointment $id imefutwa.")
    }
}
