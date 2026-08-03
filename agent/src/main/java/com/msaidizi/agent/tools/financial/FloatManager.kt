package com.msaidizi.agent.tools.financial

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import timber.log.Timber
import kotlin.math.abs
import javax.inject.Inject
import javax.inject.Singleton
import com.msaidizi.agent.tools.core.*

/**
 * FloatManager — M-Pesa float management for agents.
 *
 * Tracks float balance, alerts on low float, predicts needs, calculates commission.
 *
 * Voice: "Float yangu ni ngapi?" → balance
 *        "Ongeza float elfu hamsini" → top_up
 *        "Nimepata kamisheni ngapi leo?" → commission
 */
@Singleton
class FloatManager @Inject constructor(
    private val context: Context
) : Tool {

    override val name = "float_manager"
    override val description = "M-Pesa float management — track balance, commissions, predict needs, detect anomalies."

    override val argsSchema = argSchema {
        enum("action", "Action", listOf(
            "balance", "top_up", "withdraw", "predict", "commission", "daily_summary", "alert_set"
        ))
        number("amount", "Amount in KES", required = false)
        string("float_type", "mpesa|airtel|tigo", required = false)
        string("transaction_type", "deposit|withdrawal|transfer|airtime", required = false)
    }

    inner class FloatDatabase(context: Context) :
        SQLiteOpenHelper(context, "float_manager.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE float_transactions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    float_type TEXT DEFAULT 'mpesa',
                    tx_type TEXT NOT NULL,
                    amount REAL NOT NULL,
                    commission REAL DEFAULT 0,
                    balance_after REAL,
                    recorded_at INTEGER NOT NULL
                )
            """)
            db.execSQL("CREATE INDEX idx_float_date ON float_transactions(recorded_at)")
        }
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
            db.execSQL("DROP TABLE IF EXISTS float_transactions"); onCreate(db)
        }
    }

    private var dbHelper: FloatDatabase? = null
    private fun getDb(): SQLiteDatabase {
        if (dbHelper == null) dbHelper = FloatDatabase(context)
        return dbHelper!!.writableDatabase
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        return when (params["action"]) {
            "balance" -> getBalance(params)
            "top_up" -> topUp(params)
            "withdraw" -> withdraw(params)
            "commission" -> getCommission(params)
            "daily_summary" -> dailySummary(params)
            "predict" -> predict(params)
            "alert_set" -> setAlert(params)
            else -> ToolResult.error(name, "Action required", "MISSING_ACTION")
        }
    }

    private fun getBalance(params: Map<String, String>): ToolResult {
        val db = getDb()
        val cursor = db.rawQuery("SELECT balance_after FROM float_transactions ORDER BY recorded_at DESC LIMIT 1", null)
        cursor.use {
            if (it.moveToFirst()) {
                val balance = it.getDouble(0)
                return ToolResult.success(name, mapOf("balance" to balance),
                    "💰 Float yako: KES ${formatP(balance)}")
            }
        }
        return ToolResult.success(name, mapOf("balance" to 0.0), "Hakuna data ya float. Tafadhali ongeza float.")
    }

    private fun topUp(params: Map<String, String>): ToolResult {
        val amount = params["amount"]?.toDoubleOrNull()
            ?: return ToolResult.error(name, "Amount required", "MISSING_AMOUNT")
        val db = getDb()
        val currentBalance = getCurrentBalance(db)
        val newBalance = currentBalance + amount

        val values = ContentValues().apply {
            put("float_type", params["float_type"] ?: "mpesa")
            put("tx_type", "top_up")
            put("amount", amount)
            put("balance_after", newBalance)
            put("recorded_at", System.currentTimeMillis())
        }
        db.insert("float_transactions", null, values)

        return ToolResult.success(name, mapOf("amount" to amount, "new_balance" to newBalance),
            "✅ Float yaliongezeka KES ${formatP(amount)}.\n💰 Float mpya: KES ${formatP(newBalance)}")
    }

    private fun withdraw(params: Map<String, String>): ToolResult {
        val amount = params["amount"]?.toDoubleOrNull()
            ?: return ToolResult.error(name, "Amount required", "MISSING_AMOUNT")
        val db = getDb()
        val currentBalance = getCurrentBalance(db)
        val newBalance = currentBalance - amount

        val values = ContentValues().apply {
            put("float_type", params["float_type"] ?: "mpesa")
            put("tx_type", "withdrawal")
            put("amount", -amount)
            put("balance_after", newBalance)
            put("recorded_at", System.currentTimeMillis())
        }
        db.insert("float_transactions", null, values)

        val alert = if (newBalance < 5000) "\n⚠️ Float ni chini! Ongeza haraka." else ""
        return ToolResult.success(name, mapOf("amount" to amount, "new_balance" to newBalance),
            "📤 Umetoa KES ${formatP(amount)}.\n💰 Float iliyobaki: KES ${formatP(newBalance)}$alert")
    }

    private fun getCommission(params: Map<String, String>): ToolResult {
        val db = getDb()
        val today = System.currentTimeMillis() / 86400000 * 86400000
        val cursor = db.rawQuery("""
            SELECT SUM(commission), COUNT(*)
            FROM float_transactions WHERE recorded_at >= ?
        """, arrayOf(today.toString()))

        cursor.use {
            if (it.moveToFirst()) {
                val commission = it.getDouble(0)
                val count = it.getInt(1)
                return ToolResult.success(name, mapOf("commission" to commission, "transactions" to count),
                    "💰 Kamisheni leo: KES ${formatP(commission)} ($count miamala)")
            }
        }
        return ToolResult.success(name, emptyMap<String, Any>(), "Hakuna kamisheni leo.")
    }

    private fun dailySummary(params: Map<String, String>): ToolResult {
        val db = getDb()
        val today = System.currentTimeMillis() / 86400000 * 86400000
        val cursor = db.rawQuery("""
            SELECT tx_type, COUNT(*), SUM(amount), SUM(commission)
            FROM float_transactions WHERE recorded_at >= ?
            GROUP BY tx_type
        """, arrayOf(today.toString()))

        val summary = mutableMapOf<String, Triple<Int, Double, Double>>()
        cursor.use {
            while (it.moveToNext()) {
                summary[it.getString(0)] = Triple(it.getInt(1), it.getDouble(2), it.getDouble(3))
            }
        }

        if (summary.isEmpty()) return ToolResult.success(name, emptyMap<String, Any>(), "Hakuna miamala leo.")

        val msg = buildString {
            append("📊 Muhtasari wa leo:\n")
            summary.forEach { (type, data) ->
                append("• $type: ${data.first} miamala, KES ${formatP(abs(data.second))}")
                if (data.third > 0) append(" (kamisheni: KES ${formatP(data.third)})")
                append("\n")
            }
        }
        return ToolResult.success(name, mapOf("summary" to summary), msg)
    }

    private fun predict(params: Map<String, String>): ToolResult {
        // Simple prediction based on 7-day average
        val db = getDb()
        val weekAgo = System.currentTimeMillis() - 7 * 86400000L
        val cursor = db.rawQuery("""
            SELECT AVG(daily_volume) FROM (
                SELECT SUM(ABS(amount)) as daily_volume
                FROM float_transactions WHERE recorded_at >= ?
                GROUP BY recorded_at / 86400000
            )
        """, arrayOf(weekAgo.toString()))

        cursor.use {
            if (it.moveToFirst()) {
                val predicted = it.getDouble(0) * 1.1 // 10% buffer
                return ToolResult.success(name, mapOf("predicted_need" to predicted),
                    "📈 Float inayohitajika kesho: KES ${formatP(predicted)}")
            }
        }
        return ToolResult.success(name, emptyMap<String, Any>(), "Hakuna data ya kutosha kutabiri.")
    }

    private fun setAlert(params: Map<String, String>): ToolResult {
        val amount = params["amount"]?.toDoubleOrNull() ?: 5000.0
        val prefs = context.getSharedPreferences("float_alerts", Context.MODE_PRIVATE)
        prefs.edit().putFloat("low_float_alert", amount.toFloat()).apply()
        return ToolResult.success(name, mapOf("alert_threshold" to amount),
            "🔔 Ntakujulisha float ikifika chini ya KES ${formatP(amount)}")
    }

    private fun getCurrentBalance(db: SQLiteDatabase): Double {
        val cursor = db.rawQuery("SELECT balance_after FROM float_transactions ORDER BY recorded_at DESC LIMIT 1", null)
        cursor.use {
            if (it.moveToFirst()) return it.getDouble(0)
        }
        return 0.0
    }

    private fun formatP(v: Double): String = if (v == v.toLong().toDouble()) "%,.0f".format(v) else "%,.1f".format(v)
}
