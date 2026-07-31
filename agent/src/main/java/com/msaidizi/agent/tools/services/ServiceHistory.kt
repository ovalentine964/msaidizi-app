package com.msaidizi.agent.tools.services

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ServiceHistory — Complete service history per customer.
 * Enables personalized service and repeat customer retention.
 */
@Singleton
class ServiceHistory @Inject constructor(private val context: Context) : Tool {
    override val name = "service_history"
    override val description = "Track service history per customer — what was done, when, how much, follow-up reminders."

    override val argsSchema = argSchema {
        enum("action", "Action", listOf("record", "lookup", "frequent", "remind_due"))
        string("customer_phone", "Customer phone", required = false)
        string("customer_name", "Customer name", required = false)
        string("service_type", "Service performed", required = false)
        number("price_charged", "Price in KES", required = false)
        string("notes", "Notes", required = false)
        number("duration_minutes", "Duration", required = false)
    }

    inner class SvcDb(ctx: Context) : SQLiteOpenHelper(ctx, "service_history.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE services (id INTEGER PRIMARY KEY AUTOINCREMENT, customer_phone TEXT, customer_name TEXT, service_type TEXT, price REAL, duration INTEGER, notes TEXT, recorded_at INTEGER)")
            db.execSQL("CREATE INDEX idx_svc_phone ON services(customer_phone)")
        }
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) { db.execSQL("DROP TABLE IF EXISTS services"); onCreate(db) }
    }

    private var db: SvcDb? = null
    private fun getDb(): SQLiteDatabase { if (db == null) db = SvcDb(context); return db!!.writableDatabase }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        return when (params["action"]) {
            "record" -> record(params)
            "lookup" -> lookup(params)
            "frequent" -> frequent(params)
            "remind_due" -> remindDue(params)
            else -> ToolResult.error(name, "Action required", "MISSING_ACTION")
        }
    }

    private fun record(params: Map<String, String>): ToolResult {
        val phone = params["customer_phone"] ?: return ToolResult.error(name, "Phone required", "MISSING_PHONE")
        val service = params["service_type"] ?: return ToolResult.error(name, "Service required", "MISSING_SERVICE")
        val price = params["price_charged"]?.toDoubleOrNull() ?: 0.0
        val name = params["customer_name"] ?: ""
        val duration = params["duration_minutes"]?.toIntOrNull()
        val notes = params["notes"] ?: ""

        val d = getDb()
        val v = ContentValues().apply {
            put("customer_phone", phone); put("customer_name", name); put("service_type", service)
            put("price", price); put("duration", duration); put("notes", notes)
            put("recorded_at", System.currentTimeMillis())
        }
        val id = d.insert("services", null, v)

        // Get visit count
        val cursor = d.rawQuery("SELECT COUNT(*), SUM(price) FROM services WHERE customer_phone = ?", arrayOf(phone))
        var visits = 1; var totalSpent = price
        cursor.use { if (it.moveToFirst()) { visits = it.getInt(0); totalSpent = it.getDouble(1) } }

        return ToolResult.success(name, mapOf("id" to id, "visits" to visits, "total_spent" to totalSpent),
            "✅ Huduma yamerekodwa: $service — KES $price\n👤 $name: ziara $visit, jumla KES $totalSpent")
    }

    private fun lookup(params: Map<String, String>): ToolResult {
        val phone = params["customer_phone"] ?: return ToolResult.error(name, "Phone required", "MISSING_PHONE")
        val d = getDb()

        val profileCursor = d.rawQuery("SELECT customer_name, COUNT(*), SUM(price), MIN(recorded_at), MAX(recorded_at) FROM services WHERE customer_phone = ?", arrayOf(phone))
        var custName = ""; var visits = 0; var totalSpent = 0.0; var firstVisit = 0L; var lastVisit = 0L
        profileCursor.use { if (it.moveToFirst()) { custName = it.getString(0) ?: ""; visits = it.getInt(1); totalSpent = it.getDouble(2); firstVisit = it.getLong(3); lastVisit = it.getLong(4) } }

        if (visits == 0) return ToolResult.success(name, mapOf(), "Hakuna historia ya mteja huyu.")

        val historyCursor = d.rawQuery("SELECT service_type, price, recorded_at FROM services WHERE customer_phone = ? ORDER BY recorded_at DESC LIMIT 5", arrayOf(phone))
        val history = mutableListOf<Triple<String, Double, Long>>()
        historyCursor.use { while (it.moveToNext()) history.add(Triple(it.getString(0), it.getDouble(1), it.getLong(2))) }

        val msg = buildString {
            append("👤 $custName ($phone)\n• Ziara: $visits\n• Jumla: KES $totalSpent\n\n")
            append("Historia ya hivi karibuni:\n")
            history.forEach { (svc, price, ts) -> append("• $svc: KES $price\n") }
        }
        return ToolResult.success(name, mapOf("name" to custName, "visits" to visits, "total" to totalSpent), msg)
    }

    private fun frequent(params: Map<String, String>): ToolResult {
        val d = getDb()
        val cursor = d.rawQuery("SELECT customer_phone, customer_name, COUNT(*) as visits, SUM(price) as total FROM services GROUP BY customer_phone ORDER BY visits DESC LIMIT 10", null)

        val msg = buildString {
            append("⭐ Wateja wa kawaida:\n")
            cursor.use {
                while (it.moveToNext()) {
                    val name = it.getString(1) ?: it.getString(0)
                    append("• $name: ${it.getInt(2)} ziara, KES ${it.getDouble(3)}\n")
                }
            }
        }
        return ToolResult.success(name, mapOf(), msg)
    }

    private fun remindDue(params: Map<String, String>): ToolResult {
        val d = getDb()
        val thirtyDaysAgo = System.currentTimeMillis() - 30 * 86400000L
        val cursor = d.rawQuery("""
            SELECT customer_phone, customer_name, MAX(recorded_at), COUNT(*)
            FROM services GROUP BY customer_phone
            HAVING MAX(recorded_at) < ? AND COUNT(*) >= 2
            ORDER BY MAX(recorded_at) ASC LIMIT 10
        """, arrayOf(thirtyDaysAgo.toString()))

        val msg = buildString {
            append("🔔 Wateja ambao hawajarudi (siku 30+):\n")
            cursor.use {
                while (it.moveToNext()) {
                    val name = it.getString(1) ?: it.getString(0)
                    append("• $name: ziara ${it.getInt(3)}\n")
                }
            }
        }
        return ToolResult.success(name, mapOf(), msg)
    }
}
