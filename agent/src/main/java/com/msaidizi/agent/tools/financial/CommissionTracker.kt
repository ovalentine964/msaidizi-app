package com.msaidizi.agent.tools.financial

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext
import com.msaidizi.agent.tools.core.*

/**
 * CommissionTracker — Track commissions across platforms/services.
 * For M-Pesa agents, brokers, delivery riders, freelancers.
 */
@Singleton
class CommissionTracker @Inject constructor(@ApplicationContext private val context: Context) : Tool {
    override val name = "commission_tracker"
    override val description = "Track commissions — by service, by platform, daily/weekly/monthly summaries."

    override val argsSchema = argSchema {
        enum("action", "Action", listOf("record", "summary", "by_service", "by_period"))
        string("service_type", "mpesa_withdrawal|mpesa_deposit|delivery|brokerage", required = false)
        number("transaction_amount", "Transaction amount in KES", required = false)
        number("commission_earned", "Commission in KES", required = false)
        string("platform", "mpesa|glovo|bolt|manual", required = false)
        string("period", "today|week|month", required = false)
    }

    inner class CommDb(ctx: Context) : SQLiteOpenHelper(ctx, "commissions.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE commissions (id INTEGER PRIMARY KEY AUTOINCREMENT, service_type TEXT, tx_amount REAL, commission REAL, platform TEXT, recorded_at INTEGER)")
        }
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) { db.execSQL("DROP TABLE IF EXISTS commissions"); onCreate(db) }
    }

    private var db: CommDb? = null
    private fun getDb(): SQLiteDatabase { if (db == null) db = CommDb(context); return db!!.writableDatabase }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        return when (params["action"]) {
            "record" -> record(params)
            "summary" -> summary(params)
            "by_service" -> byService(params)
            "by_period" -> byPeriod(params)
            else -> ToolResult.error(name, "Action required", "MISSING_ACTION")
        }
    }

    private fun record(params: Map<String, String>): ToolResult {
        val service = params["service_type"] ?: "general"
        val txAmount = params["transaction_amount"]?.toDoubleOrNull() ?: 0.0
        val commission = params["commission_earned"]?.toDoubleOrNull() ?: 0.0
        val platform = params["platform"] ?: "manual"

        val d = getDb()
        val v = ContentValues().apply {
            put("service_type", service); put("tx_amount", txAmount)
            put("commission", commission); put("platform", platform)
            put("recorded_at", System.currentTimeMillis())
        }
        d.insert("commissions", null, v)
        return ToolResult.success(name, mapOf("service" to service, "commission" to commission),
            "✅ Kamisheni yamerekodwa: $service — KES $commission")
    }

    private fun summary(params: Map<String, String>): ToolResult {
        val period = params["period"] ?: "today"
        val d = getDb()
        val cutoff = when (period) {
            "today" -> System.currentTimeMillis() / 86400000 * 86400000
            "week" -> System.currentTimeMillis() - 7 * 86400000L
            "month" -> System.currentTimeMillis() - 30 * 86400000L
            else -> System.currentTimeMillis() / 86400000 * 86400000
        }

        val cursor = d.rawQuery("SELECT SUM(commission), SUM(tx_amount), COUNT(*) FROM commissions WHERE recorded_at >= ?", arrayOf(cutoff.toString()))
        cursor.use {
            if (it.moveToFirst()) {
                val totalComm = it.getDouble(0)
                val totalTx = it.getDouble(1)
                val count = it.getInt(2)
                return ToolResult.success(name, mapOf("commission" to totalComm, "volume" to totalTx, "count" to count),
                    "💰 Kamisheni ($period):\n• Jumla: KES $totalComm\n• Miamala: $count\n• Volume: KES $totalTx")
            }
        }
        return ToolResult.success(name, emptyMap<String, Any>(), "Hakuna data ya kamisheni.")
    }

    private fun byService(params: Map<String, String>): ToolResult {
        val d = getDb()
        val weekAgo = System.currentTimeMillis() - 7 * 86400000L
        val cursor = d.rawQuery("SELECT service_type, COUNT(*), SUM(commission), SUM(tx_amount) FROM commissions WHERE recorded_at >= ? GROUP BY service_type ORDER BY SUM(commission) DESC", arrayOf(weekAgo.toString()))

        val msg = buildString {
            append("📊 Kamisheni kwa huduma (wiki):\n")
            cursor.use {
                while (it.moveToNext()) {
                    append("• ${it.getString(0)}: ${it.getInt(1)} miamala, KES ${it.getDouble(2)}\n")
                }
            }
        }
        return ToolResult.success(name, emptyMap<String, Any>(), msg)
    }

    private fun byPeriod(params: Map<String, String>): ToolResult {
        val d = getDb()
        val monthAgo = System.currentTimeMillis() - 30 * 86400000L
        val cursor = d.rawQuery("SELECT strftime('%Y-%m-%d', recorded_at / 1000, 'unixepoch') as day, SUM(commission), COUNT(*) FROM commissions WHERE recorded_at >= ? GROUP BY day ORDER BY day DESC", arrayOf(monthAgo.toString()))

        val msg = buildString {
            append("📈 Kamisheni ya kila siku:\n")
            cursor.use {
                while (it.moveToNext()) {
                    append("• ${it.getString(0)}: KES ${it.getDouble(1)} (${it.getInt(2)} miamala)\n")
                }
            }
        }
        return ToolResult.success(name, emptyMap<String, Any>(), msg)
    }
}
