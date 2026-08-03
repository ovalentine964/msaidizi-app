package com.msaidizi.agent.tools.services

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import javax.inject.Inject
import javax.inject.Singleton
import com.msaidizi.agent.tools.core.*

/**
 * MaterialCostCalculator — Calculate material costs for artisan jobs.
 * Track prices, suggest alternatives, generate quotes.
 */
@Singleton
class MaterialCostCalculator @Inject constructor(private val context: Context) : Tool {
    override val name = "material_cost_calculator"
    override val description = "Calculate material costs for artisan jobs — track prices, suggest alternatives, waste allowance."

    override val argsSchema = argSchema {
        enum("action", "Action", listOf("calculate", "record_purchase", "check_stock", "price_history"))
        string("job_type", "Type of job", required = false)
        string("materials", "Comma-separated materials with quantities", required = false)
        number("waste_percentage", "Waste allowance %", required = false)
        string("material_name", "Material name", required = false)
        number("quantity", "Quantity", required = false)
        number("unit_price", "Price per unit in KES", required = false)
        string("supplier", "Supplier name", required = false)
    }

    inner class MatDb(ctx: Context) : SQLiteOpenHelper(ctx, "materials.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE materials (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, quantity REAL, unit TEXT, price REAL, supplier TEXT, recorded_at INTEGER)")
            db.execSQL("CREATE TABLE purchases (id INTEGER PRIMARY KEY AUTOINCREMENT, material TEXT, quantity REAL, unit_price REAL, total REAL, supplier TEXT, recorded_at INTEGER)")
        }
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
            db.execSQL("DROP TABLE IF EXISTS materials"); db.execSQL("DROP TABLE IF EXISTS purchases"); onCreate(db)
        }
    }

    private var db: MatDb? = null
    private fun getDb(): SQLiteDatabase { if (db == null) db = MatDb(context); return db!!.writableDatabase }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        return when (params["action"]) {
            "calculate" -> calculate(params)
            "record_purchase" -> recordPurchase(params)
            "check_stock" -> checkStock(params)
            "price_history" -> priceHistory(params)
            else -> ToolResult.error(name, "Action required", "MISSING_ACTION")
        }
    }

    private fun calculate(params: Map<String, String>): ToolResult {
        val jobType = params["job_type"] ?: "Kazi"
        val materialsStr = params["materials"] ?: ""
        val wastePct = params["waste_percentage"]?.toDoubleOrNull() ?: 10.0

        // Parse materials: "mbao:6:350, hinges:3:150, varnish:1:800"
        val materials = materialsStr.split(",").mapNotNull { entry ->
            val parts = entry.trim().split(":")
            if (parts.size >= 3) {
                Triple(parts[0].trim(), parts[1].trim().toDoubleOrNull() ?: 0.0, parts[2].trim().toDoubleOrNull() ?: 0.0)
            } else null
        }

        var subtotal = 0.0
        val msg = buildString {
            append("📋 Gharama za vifaa za $jobType:\n")
            materials.forEach { (name, qty, price) ->
                val total = qty * price
                subtotal += total
                append("• $name: $qty × KES $price = KES $total\n")
            }
            val waste = subtotal * (wastePct / 100.0)
            val total = subtotal + waste
            append("\n• Jumla: KES $subtotal")
            append("\n• Upotevu ($wastePct%): KES $waste")
            append("\n• Gharama ya vifaa: KES $total")
        }
        return ToolResult.success(name, mapOf("subtotal" to subtotal, "waste" to (subtotal * wastePct / 100.0)), msg)
    }

    private fun recordPurchase(params: Map<String, String>): ToolResult {
        val material = params["material_name"] ?: return ToolResult.error(name, "Material required", "MISSING_MATERIAL")
        val qty = params["quantity"]?.toDoubleOrNull() ?: return ToolResult.error(name, "Quantity required", "MISSING_QTY")
        val price = params["unit_price"]?.toDoubleOrNull() ?: return ToolResult.error(name, "Price required", "MISSING_PRICE")
        val supplier = params["supplier"] ?: ""
        val total = qty * price

        val d = getDb()
        val v = ContentValues().apply {
            put("material", material); put("quantity", qty); put("unit_price", price)
            put("total", total); put("supplier", supplier); put("recorded_at", System.currentTimeMillis())
        }
        d.insert("purchases", null, v)
        return ToolResult.success(name, mapOf("material" to material, "total" to total),
            "✅ Ununuzi umerekodwa: $material — $qty × KES $price = KES $total")
    }

    private fun checkStock(params: Map<String, String>): ToolResult {
        val d = getDb()
        val cursor = d.rawQuery("SELECT material, SUM(quantity), AVG(unit_price) FROM purchases GROUP BY material ORDER BY material", null)

        val msg = buildString {
            append("📦 Stock ya vifaa:\n")
            cursor.use {
                while (it.moveToNext()) {
                    append("• ${it.getString(0)}: ${it.getDouble(1)} (wastani KES ${it.getDouble(2)})\n")
                }
            }
        }
        return ToolResult.success(name, emptyMap<String, Any>(), msg)
    }

    private fun priceHistory(params: Map<String, String>): ToolResult {
        val material = params["material_name"] ?: return ToolResult.error(name, "Material required", "MISSING_MATERIAL")
        val d = getDb()
        val cursor = d.rawQuery("SELECT unit_price, recorded_at FROM purchases WHERE material = ? ORDER BY recorded_at DESC LIMIT 10", arrayOf(material))

        val msg = buildString {
            append("📈 Historia ya bei ya $material:\n")
            cursor.use {
                while (it.moveToNext()) {
                    val date = java.text.SimpleDateFormat("dd MMM", java.util.Locale.getDefault())
                        .format(java.util.Date(it.getLong(1)))
                    append("• $date: KES ${it.getDouble(0)}\n")
                }
            }
        }
        return ToolResult.success(name, emptyMap<String, Any>(), msg)
    }
}
