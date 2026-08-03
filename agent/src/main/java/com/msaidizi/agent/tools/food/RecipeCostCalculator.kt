package com.msaidizi.agent.tools.food

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
 * RecipeCostCalculator — Cost per plate/portion for food vendors.
 * Track ingredients, fuel, labor cost per menu item.
 */
@Singleton
class RecipeCostCalculator @Inject constructor(@ApplicationContext private val context: Context) : Tool {
    override val name = "recipe_cost_calculator"
    override val description = "Calculate cost per plate — ingredients + fuel + labor. Track menu profitability."

    override val argsSchema = argSchema {
        enum("action", "Action", listOf("calculate", "record_recipe", "menu_analysis", "suggest_price"))
        string("menu_item", "Menu item name", required = false)
        string("ingredients", "Comma-separated ingredients with costs", required = false)
        number("fuel_cost_per_day", "Daily fuel cost in KES", required = false)
        number("portions_per_day", "Number of portions per day", required = false)
        number("labor_cost_per_day", "Daily labor cost in KES", required = false)
        number("selling_price", "Current selling price in KES", required = false)
    }

    inner class RecipeDb(ctx: Context) : SQLiteOpenHelper(ctx, "recipes.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE recipes (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, ingredients TEXT, ingredient_cost REAL, fuel_per_portion REAL, labor_per_portion REAL, selling_price REAL, portions_per_day INTEGER, recorded_at INTEGER)")
        }
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) { db.execSQL("DROP TABLE IF EXISTS recipes"); onCreate(db) }
    }

    private var db: RecipeDb? = null
    private fun getDb(): SQLiteDatabase { if (db == null) db = RecipeDb(context); return db!!.writableDatabase }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        return when (params["action"]) {
            "calculate" -> calculate(params)
            "record_recipe" -> recordRecipe(params)
            "menu_analysis" -> menuAnalysis(params)
            "suggest_price" -> suggestPrice(params)
            else -> ToolResult.error(name, "Action required", "MISSING_ACTION")
        }
    }

    private fun calculate(params: Map<String, String>): ToolResult {
        val item = params["menu_item"] ?: "Chakula"
        val ingredientCost = params["ingredients"]?.split(",")?.mapNotNull {
            it.trim().split(":").getOrNull(1)?.trim()?.toDoubleOrNull()
        }?.sum() ?: 0.0
        val fuelPerDay = params["fuel_cost_per_day"]?.toDoubleOrNull() ?: 0.0
        val portions = params["portions_per_day"]?.toIntOrNull() ?: 1
        val laborPerDay = params["labor_cost_per_day"]?.toDoubleOrNull() ?: 0.0
        val sellingPrice = params["selling_price"]?.toDoubleOrNull() ?: 0.0

        val fuelPerPortion = fuelPerDay / portions
        val laborPerPortion = laborPerDay / portions
        val totalCost = ingredientCost + fuelPerPortion + laborPerPortion
        val profit = sellingPrice - totalCost
        val margin = if (totalCost > 0) (profit / totalCost * 100) else 0.0

        val msg = buildString {
            append("🍽️ Gharama ya $item:\n")
            append("• Vifaa: KES $ingredientCost\n")
            append("• Mafuta: KES $fuelPerPortion\n")
            append("• Kazi: KES $laborPerPortion\n")
            append("• Jumla: KES $totalCost\n")
            if (sellingPrice > 0) {
                append("• Bei: KES $sellingPrice\n")
                append("• Faida: KES $profit ($margin%)")
                if (margin < 20) append("\n⚠️ Faida ni ndogo! Ongeza bei au punguza gharama.")
            }
        }
        return ToolResult.success(name, mapOf(
            "ingredient_cost" to ingredientCost, "fuel_per_portion" to fuelPerPortion,
            "labor_per_portion" to laborPerPortion, "total_cost" to totalCost,
            "profit" to profit, "margin" to margin
        ), msg)
    }

    private fun recordRecipe(params: Map<String, String>): ToolResult {
        val name = params["menu_item"] ?: return ToolResult.error(name, "Menu item required", "MISSING_ITEM")
        val ingredients = params["ingredients"] ?: ""
        val ingredientCost = ingredients.split(",").mapNotNull {
            it.trim().split(":").getOrNull(1)?.trim()?.toDoubleOrNull()
        }.sum()
        val fuelPerDay = params["fuel_cost_per_day"]?.toDoubleOrNull() ?: 0.0
        val portions = params["portions_per_day"]?.toIntOrNull() ?: 1
        val laborPerDay = params["labor_cost_per_day"]?.toDoubleOrNull() ?: 0.0
        val sellingPrice = params["selling_price"]?.toDoubleOrNull() ?: 0.0

        val d = getDb()
        val v = ContentValues().apply {
            put("name", name); put("ingredients", ingredients); put("ingredient_cost", ingredientCost)
            put("fuel_per_portion", fuelPerDay / portions); put("labor_per_portion", laborPerDay / portions)
            put("selling_price", sellingPrice); put("portions_per_day", portions)
            put("recorded_at", System.currentTimeMillis())
        }
        d.insert("recipes", null, v)
        return ToolResult.success(name, mapOf("name" to name, "cost" to ingredientCost),
            "✅ Recipe yamerekodwa: $name — gharama KES $ingredientCost")
    }

    private fun menuAnalysis(params: Map<String, String>): ToolResult {
        val d = getDb()
        val cursor = d.rawQuery("SELECT name, ingredient_cost, selling_price, selling_price - ingredient_cost - fuel_per_portion - labor_per_portion as profit FROM recipes ORDER BY profit DESC", null)

        val msg = buildString {
            append("📊 Uchambuzi wa menu:\n")
            cursor.use {
                while (it.moveToNext()) {
                    val name = it.getString(0)
                    val cost = it.getDouble(1)
                    val price = it.getDouble(2)
                    val profit = it.getDouble(3)
                    val margin = if (cost > 0) (profit / cost * 100) else 0.0
                    append("• $name: gharama KES $cost, bei KES $price, faida KES $profit ($margin%)\n")
                }
            }
        }
        return ToolResult.success(name, emptyMap<String, Any>(), msg)
    }

    private fun suggestPrice(params: Map<String, String>): ToolResult {
        val item = params["menu_item"] ?: return ToolResult.error(name, "Item required", "MISSING_ITEM")
        val ingredientCost = params["ingredients"]?.split(",")?.mapNotNull {
            it.trim().split(":").getOrNull(1)?.trim()?.toDoubleOrNull()
        }?.sum() ?: 0.0

        val minPrice = ingredientCost * 1.5  // 50% margin minimum
        val optimalPrice = ingredientCost * 2.0  // 100% margin
        val maxPrice = ingredientCost * 3.0  // 200% margin

        val msg = buildString {
            append("💡 Bei inayopendekezwa kwa $item:\n")
            append("• Chini: KES $minPrice (faida 50%)\n")
            append("• Bora: KES $optimalPrice (faida 100%)\n")
            append("• Juu: KES $maxPrice (faida 200%)")
        }
        return ToolResult.success(name, mapOf("min" to minPrice, "optimal" to optimalPrice, "max" to maxPrice), msg)
    }
}
