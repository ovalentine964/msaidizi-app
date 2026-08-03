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
 * FundiJobQuoter — Job quotation builder for artisans.
 * Materials + labor + margin = quote. Track job profitability.
 */
@Singleton
class FundiJobQuoter @Inject constructor(@ApplicationContext private val context: Context) : Tool {
    override val name = "fundi_job_quoter"
    override val description = "Build job quotes for artisans — materials + labor + margin. Track actual vs. quoted profit."

    override val argsSchema = argSchema {
        enum("action", "Action", listOf("new_quote", "record_job", "profitability", "compare"))
        string("job_description", "Job description", required = false)
        number("materials_cost", "Materials cost in KES", required = false)
        number("labor_hours", "Labor hours", required = false)
        number("labor_rate", "Labor rate per hour in KES", required = false)
        number("profit_margin", "Profit margin % (default 30)", required = false)
        string("client", "Client name", required = false)
        number("quoted_price", "Quoted price in KES", required = false)
        number("actual_price", "Actual price received in KES", required = false)
    }

    inner class JobDb(ctx: Context) : SQLiteOpenHelper(ctx, "fundi_jobs.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE jobs (id INTEGER PRIMARY KEY AUTOINCREMENT, description TEXT, materials REAL, labor_hours REAL, labor_rate REAL, margin_pct REAL, client TEXT, quoted_price REAL, actual_price REAL, status TEXT DEFAULT 'quoted', recorded_at INTEGER)")
        }
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) { db.execSQL("DROP TABLE IF EXISTS jobs"); onCreate(db) }
    }

    private var db: JobDb? = null
    private fun getDb(): SQLiteDatabase { if (db == null) db = JobDb(context); return db!!.writableDatabase }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        return when (params["action"]) {
            "new_quote" -> newQuote(params)
            "record_job" -> recordJob(params)
            "profitability" -> profitability(params)
            "compare" -> compare(params)
            else -> ToolResult.error(name, "Action required", "MISSING_ACTION")
        }
    }

    private fun newQuote(params: Map<String, String>): ToolResult {
        val materials = params["materials_cost"]?.toDoubleOrNull() ?: 0.0
        val hours = params["labor_hours"]?.toDoubleOrNull() ?: 0.0
        val rate = params["labor_rate"]?.toDoubleOrNull() ?: 250.0
        val marginPct = params["profit_margin"]?.toDoubleOrNull() ?: 30.0
        val desc = params["job_description"] ?: "Kazi"

        val labor = hours * rate
        val subtotal = materials + labor
        val margin = subtotal * (marginPct / 100.0)
        val suggestedQuote = subtotal + margin

        val msg = buildString {
            append("📋 Nukuu ya $desc:\n")
            append("• Vifaa: KES $materials\n")
            append("• Kazi: $hours saa × KES $rate = KES $labor\n")
            append("• Jumla: KES $subtotal\n")
            append("• Faida ($marginPct%): KES $margin\n")
            append("💡 Nukuu inayopendekezwa: KES $suggestedQuote")
        }
        return ToolResult.success(name, mapOf(
            "materials" to materials, "labor" to labor, "subtotal" to subtotal,
            "margin" to margin, "suggested_quote" to suggestedQuote
        ), msg)
    }

    private fun recordJob(params: Map<String, String>): ToolResult {
        val desc = params["job_description"] ?: "Kazi"
        val materials = params["materials_cost"]?.toDoubleOrNull() ?: 0.0
        val hours = params["labor_hours"]?.toDoubleOrNull() ?: 0.0
        val rate = params["labor_rate"]?.toDoubleOrNull() ?: 250.0
        val marginPct = params["profit_margin"]?.toDoubleOrNull() ?: 30.0
        val client = params["client"] ?: ""
        val quoted = params["quoted_price"]?.toDoubleOrNull() ?: 0.0
        val actual = params["actual_price"]?.toDoubleOrNull() ?: quoted

        val d = getDb()
        val v = ContentValues().apply {
            put("description", desc); put("materials", materials); put("labor_hours", hours)
            put("labor_rate", rate); put("margin_pct", marginPct); put("client", client)
            put("quoted_price", quoted); put("actual_price", actual); put("status", "completed")
            put("recorded_at", System.currentTimeMillis())
        }
        val id = d.insert("jobs", null, v)

        val cost = materials + (hours * rate)
        val profit = actual - cost
        val profitPct = if (cost > 0) (profit / cost * 100) else 0.0

        val msg = buildString {
            append("✅ Kazi yamerekodwa (Id: $id)\n")
            append("• $desc kwa $client\n")
            append("• Gharama: KES $cost\n")
            append("• Mapato: KES $actual\n")
            append("• Faida: KES $profit ($profitPct%)")
        }
        return ToolResult.success(name, mapOf("id" to id, "cost" to cost, "profit" to profit, "margin" to profitPct), msg)
    }

    private fun profitability(params: Map<String, String>): ToolResult {
        val d = getDb()
        val cursor = d.rawQuery("""
            SELECT description, client, materials + labor_hours * labor_rate as cost, actual_price,
                   actual_price - (materials + labor_hours * labor_rate) as profit
            FROM jobs WHERE status = 'completed' ORDER BY profit DESC LIMIT 10
        """, null)

        val msg = buildString {
            append("💰 Faida ya kazi:\n")
            cursor.use {
                while (it.moveToNext()) {
                    val desc = it.getString(0)
                    val client = it.getString(1) ?: ""
                    val profit = it.getDouble(4)
                    append("• $desc: KES $profit\n")
                }
            }
        }
        return ToolResult.success(name, emptyMap<String, Any>(), msg)
    }

    private fun compare(params: Map<String, String>): ToolResult {
        val d = getDb()
        val cursor = d.rawQuery("SELECT AVG(actual_price - (materials + labor_hours * labor_rate)) / AVG(materials + labor_hours * labor_rate) * 100, COUNT(*) FROM jobs WHERE status = 'completed'", null)
        cursor.use {
            if (it.moveToFirst()) {
                val avgMargin = it.getDouble(0)
                val count = it.getInt(1)
                return ToolResult.success(name, mapOf("avg_margin" to avgMargin, "jobs" to count),
                    "📊 Wastani wa faida: $avgMargin% ($count kazi)")
            }
        }
        return ToolResult.success(name, emptyMap<String, Any>(), "Hakuna data ya kazi.")
    }
}
