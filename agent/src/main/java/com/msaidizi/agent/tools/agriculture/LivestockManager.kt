package com.msaidizi.agent.tools.agriculture

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import javax.inject.Inject
import javax.inject.Singleton
import com.msaidizi.agent.tools.core.*

/**
 * LivestockManager — Production tracking, feed costs, health logs for livestock keepers.
 *
 * Voice: "Leo nimepata maziwa lita tano" → record_production
 *        "Gharama ya chakula ni ngapi?" → feed_costs
 */
@Singleton
class LivestockManager @Inject constructor(private val context: Context) : Tool {
    override val name = "livestock_manager"
    override val description = "Track livestock production (milk, eggs), feed costs, animal health, mortality."

    override val argsSchema = argSchema {
        enum("action", "Action", listOf(
            "record_production", "record_feed", "record_health", "record_mortality",
            "daily_summary", "production_history", "feed_analysis", "profit_per_animal"
        ))
        string("animal_type", "dairy_cattle|poultry|goats|pigs|bees", required = false)
        string("product", "milk|eggs|meat|honey", required = false)
        number("quantity", "Quantity produced", required = false)
        string("unit", "litres|kg|pieces", required = false)
        number("price", "Price per unit in KES", required = false)
        number("feed_cost", "Feed cost in KES", required = false)
        string("health_notes", "Health/vaccination notes", required = false)
        string("animal_id", "Animal identifier", required = false)
        string("period", "day|week|month", required = false)
    }

    inner class LivestockDb(ctx: Context) : SQLiteOpenHelper(ctx, "livestock.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE production (id INTEGER PRIMARY KEY AUTOINCREMENT, animal_type TEXT, product TEXT, quantity REAL, unit TEXT, price REAL, recorded_at INTEGER)")
            db.execSQL("CREATE TABLE feed_costs (id INTEGER PRIMARY KEY AUTOINCREMENT, animal_type TEXT, feed_type TEXT, amount REAL, cost REAL, recorded_at INTEGER)")
            db.execSQL("CREATE TABLE health_log (id INTEGER PRIMARY KEY AUTOINCREMENT, animal_id TEXT, animal_type TEXT, notes TEXT, recorded_at INTEGER)")
            db.execSQL("CREATE TABLE mortality (id INTEGER PRIMARY KEY AUTOINCREMENT, animal_type TEXT, count INTEGER, cause TEXT, recorded_at INTEGER)")
        }
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
            db.execSQL("DROP TABLE IF EXISTS production"); db.execSQL("DROP TABLE IF EXISTS feed_costs")
            db.execSQL("DROP TABLE IF EXISTS health_log"); db.execSQL("DROP TABLE IF EXISTS mortality")
            onCreate(db)
        }
    }

    private var db: LivestockDb? = null
    private fun getDb(): SQLiteDatabase { if (db == null) db = LivestockDb(context); return db!!.writableDatabase }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        return when (params["action"]) {
            "record_production" -> recordProduction(params)
            "record_feed" -> recordFeed(params)
            "record_health" -> recordHealth(params)
            "record_mortality" -> recordMortality(params)
            "daily_summary" -> dailySummary(params)
            "production_history" -> productionHistory(params)
            "feed_analysis" -> feedAnalysis(params)
            "profit_per_animal" -> profitPerAnimal(params)
            else -> ToolResult.error(name, "Action required", "MISSING_ACTION")
        }
    }

    private fun recordProduction(params: Map<String, String>): ToolResult {
        val product = params["product"] ?: return ToolResult.error(name, "Product required", "MISSING_PRODUCT")
        val qty = params["quantity"]?.toDoubleOrNull() ?: return ToolResult.error(name, "Quantity required", "MISSING_QTY")
        val unit = params["unit"] ?: "litres"
        val price = params["price"]?.toDoubleOrNull() ?: 0.0
        val animalType = params["animal_type"] ?: ""

        val d = getDb()
        val v = ContentValues().apply {
            put("animal_type", animalType); put("product", product)
            put("quantity", qty); put("unit", unit); put("price", price)
            put("recorded_at", System.currentTimeMillis())
        }
        d.insert("production", null, v)

        val value = qty * price
        val msg = "✅ $product yamerekodwa: $qty $unit" +
                if (price > 0) "\n💰 Thamani: KES ${fmt(value)}" else ""
        return ToolResult.success(name, mapOf("product" to product, "quantity" to qty, "value" to value), msg)
    }

    private fun recordFeed(params: Map<String, String>): ToolResult {
        val cost = params["feed_cost"]?.toDoubleOrNull() ?: return ToolResult.error(name, "Feed cost required", "MISSING_COST")
        val d = getDb()
        val v = ContentValues().apply {
            put("animal_type", params["animal_type"] ?: ""); put("feed_type", "general")
            put("amount", 1.0); put("cost", cost); put("recorded_at", System.currentTimeMillis())
        }
        d.insert("feed_costs", null, v)
        return ToolResult.success(name, mapOf("cost" to cost), "✅ Gharama ya chakula: KES ${fmt(cost)}")
    }

    private fun recordHealth(params: Map<String, String>): ToolResult {
        val notes = params["health_notes"] ?: return ToolResult.error(name, "Notes required", "MISSING_NOTES")
        val d = getDb()
        val v = ContentValues().apply {
            put("animal_id", params["animal_id"] ?: ""); put("animal_type", params["animal_type"] ?: "")
            put("notes", notes); put("recorded_at", System.currentTimeMillis())
        }
        d.insert("health_log", null, v)
        return ToolResult.success(name, mapOf("notes" to notes), "✅ Afya yamerekodwa: $notes")
    }

    private fun recordMortality(params: Map<String, String>): ToolResult {
        val d = getDb()
        val v = ContentValues().apply {
            put("animal_type", params["animal_type"] ?: ""); put("count", 1)
            put("cause", params["health_notes"] ?: "unknown"); put("recorded_at", System.currentTimeMillis())
        }
        d.insert("mortality", null, v)
        return ToolResult.success(name, emptyMap<String, Any>(), "⚠️ Kifo kimerikodwa.")
    }

    private fun dailySummary(params: Map<String, String>): ToolResult {
        val d = getDb()
        val today = System.currentTimeMillis() / 86400000 * 86400000

        val prodCursor = d.rawQuery("SELECT product, SUM(quantity), SUM(price * quantity) FROM production WHERE recorded_at >= ? GROUP BY product", arrayOf(today.toString()))
        val productions = mutableListOf<Triple<String, Double, Double>>()
        prodCursor.use { while (it.moveToNext()) productions.add(Triple(it.getString(0), it.getDouble(1), it.getDouble(2))) }

        val feedCursor = d.rawQuery("SELECT SUM(cost) FROM feed_costs WHERE recorded_at >= ?", arrayOf(today.toString()))
        var feedCost = 0.0
        feedCursor.use { if (it.moveToFirst()) feedCost = it.getDouble(0) }

        if (productions.isEmpty() && feedCost == 0.0) return ToolResult.success(name, emptyMap<String, Any>(), "Hakuna data leo.")

        val totalValue = productions.sumOf { it.third }
        val msg = buildString {
            append("🐄 Muhtasari wa leo:\n")
            productions.forEach { (p, q, v) -> append("• $p: $q → KES ${fmt(v)}\n") }
            append("• Chakula: KES ${fmt(feedCost)}\n")
            append("• Faida: KES ${fmt(totalValue - feedCost)}")
        }
        return ToolResult.success(name, mapOf("production_value" to totalValue, "feed_cost" to feedCost), msg)
    }

    private fun productionHistory(params: Map<String, String>): ToolResult {
        val period = params["period"] ?: "week"
        val d = getDb()
        val cutoff = System.currentTimeMillis() - if (period == "month") 30L else 7L * 86400000
        val cursor = d.rawQuery("SELECT product, SUM(quantity), AVG(quantity), COUNT(*) FROM production WHERE recorded_at >= ? GROUP BY product", arrayOf(cutoff.toString()))

        val msg = buildString {
            append("📊 Historia ya uzalishaji ($period):\n")
            cursor.use {
                while (it.moveToNext()) {
                    val product = it.getString(0)
                    val total = it.getDouble(1)
                    val avg = it.getDouble(2)
                    val count = it.getInt(3)
                    append("• $product: jumla $total, wastani $avg kwa siku ($count siku)\n")
                }
            }
        }
        return ToolResult.success(name, emptyMap<String, Any>(), msg)
    }

    private fun feedAnalysis(params: Map<String, String>): ToolResult {
        val d = getDb()
        val monthAgo = System.currentTimeMillis() - 30 * 86400000L
        val cursor = d.rawQuery("SELECT SUM(cost), COUNT(*) FROM feed_costs WHERE recorded_at >= ?", arrayOf(monthAgo.toString()))
        cursor.use {
            if (it.moveToFirst()) {
                val total = it.getDouble(0)
                val count = it.getInt(1)
                return ToolResult.success(name, mapOf("total_feed_cost" to total, "days" to count),
                    "🍽️ Gharama ya chakula (mwezi): KES ${fmt(total)} ($count siku)")
            }
        }
        return ToolResult.success(name, emptyMap<String, Any>(), "Hakuna data ya chakula.")
    }

    private fun profitPerAnimal(params: Map<String, String>): ToolResult {
        val d = getDb()
        val monthAgo = System.currentTimeMillis() - 30 * 86400000L
        val prodCursor = d.rawQuery("SELECT SUM(price * quantity) FROM production WHERE recorded_at >= ?", arrayOf(monthAgo.toString()))
        val feedCursor = d.rawQuery("SELECT SUM(cost) FROM feed_costs WHERE recorded_at >= ?", arrayOf(monthAgo.toString()))

        var revenue = 0.0
        var feed = 0.0
        prodCursor.use { if (it.moveToFirst()) revenue = it.getDouble(0) }
        feedCursor.use { if (it.moveToFirst()) feed = it.getDouble(0) }

        val profit = revenue - feed
        return ToolResult.success(name, mapOf("revenue" to revenue, "feed_cost" to feed, "profit" to profit),
            "💰 Faida ya mwezi:\n• Mapato: KES ${fmt(revenue)}\n• Chakula: KES ${fmt(feed)}\n• Faida: KES ${fmt(profit)}")
    }

    private fun fmt(v: Double): String = if (v == v.toLong().toDouble()) "%,.0f".format(v) else "%,.1f".format(v)
}
