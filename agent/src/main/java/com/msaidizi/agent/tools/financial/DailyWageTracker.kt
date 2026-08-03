package com.msaidizi.agent.tools.financial

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext
import com.msaidizi.agent.tools.core.*

/**
 * DailyWageTracker — Track daily wages for casual laborers.
 * Record each day's work: employer, rate, hours, payment status.
 */
@Singleton
class DailyWageTracker @Inject constructor(@ApplicationContext private val context: Context) : Tool {
    override val name = "daily_wage_tracker"
    override val description = "Track daily wages for casual laborers — employer, rate, hours, payment status, weekly patterns."

    override val argsSchema = argSchema {
        enum("action", "Action", listOf("record", "today", "weekly", "unpaid", "patterns"))
        string("employer", "Employer name", required = false)
        string("work_type", "Type of work", required = false)
        number("daily_rate", "Daily rate in KES", required = false)
        number("hours_worked", "Hours worked", required = false)
        string("payment_method", "cash|mpesa", required = false)
        boolean("paid", "Whether already paid", required = false)
        string("location", "Work location", required = false)
    }

    inner class WageDb(ctx: Context) : SQLiteOpenHelper(ctx, "daily_wages.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE wages (id INTEGER PRIMARY KEY AUTOINCREMENT, employer TEXT, work_type TEXT, daily_rate REAL, hours REAL, payment_method TEXT, paid INTEGER DEFAULT 0, location TEXT, recorded_at INTEGER)")
        }
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) { db.execSQL("DROP TABLE IF EXISTS wages"); onCreate(db) }
    }

    private var db: WageDb? = null
    private fun getDb(): SQLiteDatabase { if (db == null) db = WageDb(context); return db!!.writableDatabase }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        return when (params["action"]) {
            "record" -> record(params)
            "today" -> today(params)
            "weekly" -> weekly(params)
            "unpaid" -> unpaid(params)
            "patterns" -> patterns(params)
            else -> ToolResult.error(name, "Action required", "MISSING_ACTION")
        }
    }

    private fun record(params: Map<String, String>): ToolResult {
        val employer = params["employer"] ?: ""
        val workType = params["work_type"] ?: "general"
        val rate = params["daily_rate"]?.toDoubleOrNull() ?: 0.0
        val hours = params["hours_worked"]?.toDoubleOrNull() ?: 8.0
        val paid = params["paid"]?.toBooleanStrictOrNull() ?: false
        val location = params["location"] ?: ""

        val d = getDb()
        val v = ContentValues().apply {
            put("employer", employer); put("work_type", workType); put("daily_rate", rate)
            put("hours", hours); put("payment_method", params["payment_method"] ?: "cash")
            put("paid", if (paid) 1 else 0); put("location", location)
            put("recorded_at", System.currentTimeMillis())
        }
        d.insert("wages", null, v)

        val msg = buildString {
            append("✅ Kazi yamerekodwa:\n")
            append("• $workType")
            if (employer.isNotBlank()) append(" kwa $employer")
            append("\n• Kiasi: KES $rate")
            append("\n• Masaa: $hours")
            if (!paid) append("\n⚠️ Bado hujalipwa!")
        }
        return ToolResult.success(name, mapOf("rate" to rate, "hours" to hours, "paid" to paid), msg)
    }

    private fun today(params: Map<String, String>): ToolResult {
        val d = getDb()
        val today = System.currentTimeMillis() / 86400000 * 86400000
        val cursor = d.rawQuery("SELECT employer, work_type, daily_rate, paid FROM wages WHERE recorded_at >= ?", arrayOf(today.toString()))
        cursor.use {
            if (it.moveToFirst()) {
                val rate = it.getDouble(2)
                val paid = it.getInt(3) == 1
                return ToolResult.success(name, mapOf("rate" to rate, "paid" to paid),
                    "📅 Leo: ${it.getString(1)} — KES $rate ${if (paid) "✅" else "⏳"}")
            }
        }
        return ToolResult.success(name, emptyMap<String, Any>(), "Hakuna kazi leo.")
    }

    private fun weekly(params: Map<String, String>): ToolResult {
        val d = getDb()
        val weekAgo = System.currentTimeMillis() - 7 * 86400000L
        val cursor = d.rawQuery("SELECT COUNT(*), SUM(daily_rate), SUM(CASE WHEN paid = 1 THEN daily_rate ELSE 0 END) FROM wages WHERE recorded_at >= ?", arrayOf(weekAgo.toString()))
        cursor.use {
            if (it.moveToFirst()) {
                val days = it.getInt(0)
                val total = it.getDouble(1)
                val paid = it.getDouble(2)
                return ToolResult.success(name, mapOf("days" to days, "total" to total, "paid" to paid),
                    "📊 Wiki hii:\n• Siku za kazi: $days\n• Jumla: KES $total\n• Zilizolipwa: KES $paid\n• Bado: KES ${total - paid}")
            }
        }
        return ToolResult.success(name, emptyMap<String, Any>(), "Hakuna data ya wiki.")
    }

    private fun unpaid(params: Map<String, String>): ToolResult {
        val d = getDb()
        val cursor = d.rawQuery("SELECT employer, work_type, daily_rate, recorded_at FROM wages WHERE paid = 0 ORDER BY recorded_at DESC", null)

        val msg = buildString {
            append("⚠️ Malipo ambayo bado:\n")
            cursor.use {
                while (it.moveToNext()) {
                    val date = java.text.SimpleDateFormat("dd MMM", java.util.Locale.getDefault())
                        .format(java.util.Date(it.getLong(3)))
                    append("• $date: ${it.getString(1)} — KES ${it.getDouble(2)}")
                    val emp = it.getString(0)
                    if (emp.isNotBlank()) append(" ($emp)")
                    append("\n")
                }
            }
        }
        return ToolResult.success(name, emptyMap<String, Any>(), msg)
    }

    private fun patterns(params: Map<String, String>): ToolResult {
        val d = getDb()
        val monthAgo = System.currentTimeMillis() - 30 * 86400000L
        val cursor = d.rawQuery("""
            SELECT strftime('%w', recorded_at / 1000, 'unixepoch') as dow, COUNT(*), AVG(daily_rate)
            FROM wages WHERE recorded_at >= ? GROUP BY dow ORDER BY AVG(daily_rate) DESC
        """, arrayOf(monthAgo.toString()))

        val days = arrayOf("Jumapili", "Jumatatu", "Jumanne", "Jumatano", "Alhamisi", "Ijumaa", "Jumamosi")
        val msg = buildString {
            append("📊 Mifumo ya wiki:\n")
            cursor.use {
                while (it.moveToNext()) {
                    append("• ${days[it.getInt(0)]}: ${it.getInt(1)} siku, wastani KES ${it.getDouble(2)}\n")
                }
            }
        }
        return ToolResult.success(name, emptyMap<String, Any>(), msg)
    }
}
