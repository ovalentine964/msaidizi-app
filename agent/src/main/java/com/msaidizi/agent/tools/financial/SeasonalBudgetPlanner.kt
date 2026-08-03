package com.msaidizi.agent.tools.financial

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import timber.log.Timber
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext
import com.msaidizi.agent.tools.core.*

/**
 * SeasonalBudgetPlanner — Fix 1: Seasonal budgeting mode for farmers.
 *
 * Problem: CFOEngine's daily cash flow prediction is meaningless for farmers.
 * A farmer has 0 income for 3 months then one harvest sale of KES 50,000.
 * Daily predictions ("next Tuesday you'll need cash") don't match farming reality.
 *
 * Solution: Replace daily cash flow with seasonal cycles:
 *   - Planting season: expenses high, income zero
 *   - Growing season: expenses low, income zero
 *   - Harvest season: income high, expenses low
 *   - Post-harvest: savings deplete through school fees, food
 *
 * Voice examples:
 *   "Bajeti yangu ya msimu"                    → Seasonal budget overview
 *   "Ninahitaji pesa ngapi kupanda?"            → Planting cost estimate
 *   "Pesa zitatosheka hadi kuvuna?"             → Cash runway analysis
 *   "Nitarudisha pesa lini?"                    → Recovery timeline
 */
@Singleton
class SeasonalBudgetPlanner @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val name = "seasonal_budget_planner"
    override val description = "Seasonal budgeting for farmers. Shows planting-growing-harvest cycles, cash runway, and recovery timelines instead of daily cash flow."

    override val argsSchema = argSchema {
        enum(
            "action", "Action to perform",
            listOf(
                "overview",          // Full seasonal budget overview
                "planting_costs",    // Estimate planting season expenses
                "cash_runway",       // How long until money runs out?
                "recovery_timeline", // When will harvest income cover expenses?
                "set_crop",          // Set primary crop for seasonal calendar
                "set_expenses",      // Set seasonal expense profile
                "record_harvest_income", // Record actual harvest sale
                "current_season",    // What season am I in?
                "plan_next_season"   // Forward-looking budget plan
            ),
            required = true
        )
        string("crop", "Primary crop (mahindi, maharagwe, nyanya, etc.)", required = false)
        number("land_acres", "Farm size in acres", required = false)
        number("harvest_income", "Actual harvest income in KES", required = false)
        number("current_savings", "Current savings available in KES", required = false)
        string("expenses_profile", "Expense profile: low/medium/high", required = false)
        boolean("voice", "Format for voice output (Swahili)", required = false)
    }

    // ──────────────────────────────────────────────
    // Seasonal Calendar Database
    // ──────────────────────────────────────────────

    inner class SeasonalDatabase(context: Context) :
        SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            // Farmer profile — crop, acreage, location
            db.execSQL("""
                CREATE TABLE $TABLE_PROFILE (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    crop TEXT NOT NULL,
                    land_acres REAL NOT NULL DEFAULT 1.0,
                    county TEXT,
                    updated_at INTEGER NOT NULL
                )
            """)

            // Seasonal expenses — per-phase cost estimates
            db.execSQL("""
                CREATE TABLE $TABLE_EXPENSES (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    crop TEXT NOT NULL,
                    season TEXT NOT NULL,
                    category TEXT NOT NULL,
                    estimated_cost REAL NOT NULL,
                    actual_cost REAL,
                    notes TEXT,
                    recorded_at INTEGER NOT NULL
                )
            """)

            // Harvest income records
            db.execSQL("""
                CREATE TABLE $TABLE_INCOME (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    crop TEXT NOT NULL,
                    amount REAL NOT NULL,
                    bags_sold REAL,
                    price_per_bag REAL,
                    buyer TEXT,
                    market TEXT,
                    recorded_at INTEGER NOT NULL
                )
            """)

            // Savings balance tracking
            db.execSQL("""
                CREATE TABLE $TABLE_SAVINGS (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    balance REAL NOT NULL,
                    source TEXT,
                    recorded_at INTEGER NOT NULL
                )
            """)

            db.execSQL("CREATE INDEX idx_expenses_crop ON $TABLE_EXPENSES(crop, season)")
            db.execSQL("CREATE INDEX idx_income_crop ON $TABLE_INCOME(crop)")
            db.execSQL("CREATE INDEX idx_income_date ON $TABLE_INCOME(recorded_at)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_EXPENSES")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_INCOME")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_SAVINGS")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_PROFILE")
            onCreate(db)
        }
    }

    companion object {
        private const val DB_NAME = "seasonal_budget.db"
        private const val DB_VERSION = 1
        private const val TABLE_PROFILE = "farmer_profile"
        private const val TABLE_EXPENSES = "seasonal_expenses"
        private const val TABLE_INCOME = "harvest_income"
        private const val TABLE_SAVINGS = "savings_balance"

        // Kenya seasonal calendar for major crops (month 1-12)
        // Each crop has: planting months, growing months, harvest months
        val CROP_CALENDARS = mapOf(
            "maize" to CropCalendar(
                crop = "maize",
                displayName = "Mahindi",
                plantingMonths = listOf(3, 4, 10),      // Mar-Apr (long rains), Oct (short rains)
                growingMonths = listOf(5, 6, 7, 11, 12), // May-Jul, Nov-Dec
                harvestMonths = listOf(8, 9, 1, 2),      // Aug-Sep, Jan-Feb
                avgPlantingCostPerAcre = 15_000.0,       // KES per acre
                avgGrowingCostPerAcre = 5_000.0,
                avgHarvestCostPerAcre = 3_000.0,
                avgYieldBagsPerAcre = 20.0,               // 90kg bags
                avgPricePerBag = 3_500.0
            ),
            "beans" to CropCalendar(
                crop = "beans",
                displayName = "Maharagwe",
                plantingMonths = listOf(3, 4, 10),
                growingMonths = listOf(5, 6, 11, 12),
                harvestMonths = listOf(7, 8, 1, 2),
                avgPlantingCostPerAcre = 10_000.0,
                avgGrowingCostPerAcre = 3_000.0,
                avgHarvestCostPerAcre = 2_000.0,
                avgYieldBagsPerAcre = 10.0,
                avgPricePerBag = 6_000.0
            ),
            "tomatoes" to CropCalendar(
                crop = "tomatoes",
                displayName = "Nyanya",
                plantingMonths = listOf(1, 2, 7, 8),     // Year-round with irrigation
                growingMonths = listOf(3, 4, 9, 10),
                harvestMonths = listOf(5, 6, 11, 12),
                avgPlantingCostPerAcre = 25_000.0,
                avgGrowingCostPerAcre = 15_000.0,
                avgHarvestCostPerAcre = 5_000.0,
                avgYieldBagsPerAcre = 100.0,              // 20kg crates
                avgPricePerBag = 800.0
            ),
            "tea" to CropCalendar(
                crop = "tea",
                displayName = "Chai",
                plantingMonths = listOf(4, 5),            // One-time planting
                growingMonths = listOf(1, 2, 3, 6, 7, 8, 9, 10, 11, 12),
                harvestMonths = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12), // Year-round picking
                avgPlantingCostPerAcre = 50_000.0,
                avgGrowingCostPerAcre = 2_000.0,
                avgHarvestCostPerAcre = 3_000.0,
                avgYieldBagsPerAcre = 8.0,
                avgPricePerBag = 4_000.0
            ),
            "coffee" to CropCalendar(
                crop = "coffee",
                displayName = "Kahawa",
                plantingMonths = listOf(4, 5),
                growingMonths = listOf(1, 2, 3, 6, 7, 8, 9, 10, 11, 12),
                harvestMonths = listOf(10, 11, 12, 1, 2), // Main: Oct-Feb
                avgPlantingCostPerAcre = 40_000.0,
                avgGrowingCostPerAcre = 5_000.0,
                avgHarvestCostPerAcre = 4_000.0,
                avgYieldBagsPerAcre = 5.0,
                avgPricePerBag = 8_000.0
            ),
            "rice" to CropCalendar(
                crop = "rice",
                displayName = "Mchele",
                plantingMonths = listOf(4, 5, 11),
                growingMonths = listOf(6, 7, 8, 12, 1),
                harvestMonths = listOf(9, 10, 2, 3),
                avgPlantingCostPerAcre = 20_000.0,
                avgGrowingCostPerAcre = 8_000.0,
                avgHarvestCostPerAcre = 5_000.0,
                avgYieldBagsPerAcre = 30.0,
                avgPricePerBag = 4_500.0
            )
        )

        // Season names in Swahili
        val SEASON_NAMES_SW = mapOf(
            "planting" to "Kupanda",
            "growing" to "Kukuza",
            "harvest" to "Kuvuna",
            "post_harvest" to "Baada ya kuvuna",
            "fallow" to "Kupumzika"
        )

        // Expense categories for farmers
        val EXPENSE_CATEGORIES = mapOf(
            "seeds" to "Mbegu",
            "fertilizer" to "Mbolea",
            "pesticide" to "Dawa",
            "labor" to "Kazi",
            "transport" to "Usafiri",
            "storage" to "Uhifadhi",
            "school_fees" to "Ada za shule",
            "food" to "Chakula",
            "medical" to "Matibabu"
        )
    }

    private var dbHelper: SeasonalDatabase? = null

    private fun getDb(): SQLiteDatabase {
        if (dbHelper == null) dbHelper = SeasonalDatabase(context)
        return dbHelper!!.writableDatabase
    }

    // ──────────────────────────────────────────────
    // Tool Execute
    // ──────────────────────────────────────────────

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"]
            ?: return ToolResult.error(name, "Action required", "MISSING_ACTION")

        return when (action.lowercase()) {
            "overview" -> seasonalOverview(params)
            "planting_costs" -> plantingCosts(params)
            "cash_runway" -> cashRunway(params)
            "recovery_timeline" -> recoveryTimeline(params)
            "set_crop" -> setCrop(params)
            "set_expenses" -> setExpenses(params)
            "record_harvest_income" -> recordHarvestIncome(params)
            "current_season" -> currentSeason(params)
            "plan_next_season" -> planNextSeason(params)
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    // ──────────────────────────────────────────────
    // ACTION: overview — Full seasonal budget overview
    // ──────────────────────────────────────────────

    private fun seasonalOverview(params: Map<String, String>): ToolResult {
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val db = getDb()

        // Get farmer profile
        val profile = getProfile(db) ?: return ToolResult.success(
            name, mapOf("setup_required" to true),
            if (voice) "Hakuna profili ya mkulima. Anza na 'set_crop' kuchagua mazao yako."
            else "No farmer profile. Start with 'set_crop' to configure your crops."
        )

        val calendar = CROP_CALENDARS[profile.crop]
            ?: return ToolResult.error(name, "Crop '${profile.crop}' not in calendar", "UNKNOWN_CROP")

        val currentMonth = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1
        val currentSeason = getSeasonForMonth(calendar, currentMonth)

        // Get actual income data
        val totalIncome = getTotalIncome(db, profile.crop)
        val totalExpenses = getTotalExpenses(db, profile.crop)
        val lastHarvestIncome = getLastHarvestIncome(db, profile.crop)

        // Calculate seasonal budget
        val plantingCost = calendar.avgPlantingCostPerAcre * profile.landAcres
        val growingCost = calendar.avgGrowingCostPerAcre * profile.landAcres
        val harvestCost = calendar.avgHarvestCostPerAcre * profile.landAcres
        val totalSeasonCost = plantingCost + growingCost + harvestCost

        // Expected income
        val expectedYield = calendar.avgYieldBagsPerAcre * profile.landAcres
        val expectedIncome = expectedYield * calendar.avgPricePerBag

        // Savings balance
        val currentSavings = getLatestSavings(db)

        // Cash runway: how many months can savings cover expenses?
        val monthlyPostHarvestExpenses = 15_000.0 // school fees, food, medical
        val runwayMonths = if (monthlyPostHarvestExpenses > 0) {
            (currentSavings / monthlyPostHarvestExpenses).toInt()
        } else 0

        val swahiliMonths = arrayOf("", "Januari", "Februari", "Machi", "Aprili", "Mei", "Juni",
            "Julai", "Agosti", "Septemba", "Oktoba", "Novemba", "Desemba")

        val message = if (voice) {
            buildString {
                appendLine("📊 *Bajeti ya Msimu — ${calendar.displayName}*")
                appendLine("📏 Eka: ${profile.landAcres}")
                appendLine()
                appendLine("📅 Msimu wa sasa: ${SEASON_NAMES_SW[currentSeason]} ($currentMonth)")
                appendLine()

                // Seasonal breakdown
                appendLine("🌱 *Kupanda (${calendar.plantingMonths.joinToString(", ") { swahiliMonths[it] }}):*")
                appendLine("   Gharama: KES ${formatKES(plantingCost)}")
                appendLine("   (Mbegu, mbolea, kazi)")
                appendLine()

                appendLine("🌿 *Kukuza (${calendar.growingMonths.take(3).joinToString(", ") { swahiliMonths[it] }}...):*")
                appendLine("   Gharama: KES ${formatKES(growingCost)}")
                appendLine("   (Kupalilia, dawa)")
                appendLine()

                appendLine("🌾 *Kuvuna (${calendar.harvestMonths.joinToString(", ") { swahiliMonths[it] }}):*")
                appendLine("   Gharama: KES ${formatKES(harvestCost)}")
                appendLine("   Mapato: ~KES ${formatKES(expectedIncome)}")
                appendLine("   (${expectedYield.toInt()} gunia × KES ${formatKES(calendar.avgPricePerBag)})")
                appendLine()

                appendLine("💰 *Muhtasari:*")
                appendLine("   Jumla gharama: KES ${formatKES(totalSeasonCost)}")
                appendLine("   Mapato ya matarajio: KES ${formatKES(expectedIncome)}")
                appendLine("   Faida ya matarajio: KES ${formatKES(expectedIncome - totalSeasonCost)}")

                if (lastHarvestIncome > 0) {
                    appendLine()
                    appendLine("📊 Mavuno ya mwisho: KES ${formatKES(lastHarvestIncome)}")
                }

                if (currentSavings > 0) {
                    appendLine()
                    appendLine("💵 Akiba ya sasa: KES ${formatKES(currentSavings)}")
                    if (runwayMonths > 0) {
                        appendLine("   Inatosheka kwa miezi $runwayMonths")
                    }
                }

                // Actionable advice based on current season
                appendLine()
                appendLine("💡 *Ushauri:*")
                when (currentSeason) {
                    "planting" -> {
                        appendLine("   Unahitaji KES ${formatKES(plantingCost)} kupanda.")
                        if (currentSavings < plantingCost) {
                            val gap = plantingCost - currentSavings
                            appendLine("   ⚠️ Pengo: KES ${formatKES(gap)} — fikiria mkopo au kuuza mali.")
                        } else {
                            appendLine("   ✅ Akiba inatosheka kupanda!")
                        }
                    }
                    "growing" -> {
                        appendLine("   Mazao yako yanakua. Gharama ni ndogo sasa.")
                        appendLine("   Hifadhi pesa kwa mavuno na ada za shule.")
                    }
                    "harvest" -> {
                        val recoveryMonths = 3 // typical
                        appendLine("   Kuna KES ${formatKES(expectedIncome)} kutoka kwa mavuno.")
                        appendLine("   Kulingana na mavuno ya mwisho, utarudisha KES ${formatKES(plantingCost)} ndani ya miezi $recoveryMonths.")
                    }
                    "post_harvest" -> {
                        appendLine("   Weka akiba ya kupanda msimu ujao.")
                        appendLine("   Lengo: KES ${formatKES(plantingCost)} kabla ya ${swahiliMonths[calendar.plantingMonths.first()]}.")
                    }
                }
            }
        } else {
            buildString {
                appendLine("Seasonal Budget — ${calendar.displayName} (${profile.landAcres} acres)")
                appendLine()
                appendLine("Planting (${calendar.plantingMonths.joinToString(", ")}): KES ${formatKES(plantingCost)}")
                appendLine("Growing (${calendar.growingMonths.take(3).joinToString(", ")}...): KES ${formatKES(growingCost)}")
                appendLine("Harvest (${calendar.harvestMonths.joinToString(", ")}): KES ${formatKES(harvestCost)} costs, ~KES ${formatKES(expectedIncome)} income")
                appendLine()
                appendLine("Total costs: KES ${formatKES(totalSeasonCost)}")
                appendLine("Expected income: KES ${formatKES(expectedIncome)}")
                appendLine("Expected profit: KES ${formatKES(expectedIncome - totalSeasonCost)}")
                appendLine("Current savings: KES ${formatKES(currentSavings)}")
                appendLine("Runway: $runwayMonths months")
            }
        }

        return ToolResult.success(
            name,
            mapOf(
                "crop" to profile.crop,
                "land_acres" to profile.landAcres,
                "current_season" to currentSeason,
                "planting_cost" to plantingCost,
                "growing_cost" to growingCost,
                "harvest_cost" to harvestCost,
                "total_season_cost" to totalSeasonCost,
                "expected_income" to expectedIncome,
                "expected_profit" to (expectedIncome - totalSeasonCost),
                "current_savings" to currentSavings,
                "runway_months" to runwayMonths,
                "last_harvest_income" to lastHarvestIncome
            ),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: planting_costs — Estimate planting expenses
    // ──────────────────────────────────────────────

    private fun plantingCosts(params: Map<String, String>): ToolResult {
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val db = getDb()
        val profile = getProfile(db) ?: return ToolResult.error(name, "Set crop first", "NO_PROFILE")

        val calendar = CROP_CALENDARS[profile.crop] ?: return ToolResult.error(name, "Unknown crop", "UNKNOWN_CROP")
        val acres = profile.landAcres

        val seedCost = calendar.avgPlantingCostPerAcre * acres * 0.3  // 30% is seeds
        val fertilizerCost = calendar.avgPlantingCostPerAcre * acres * 0.35
        val laborCost = calendar.avgPlantingCostPerAcre * acres * 0.25
        val otherCost = calendar.avgPlantingCostPerAcre * acres * 0.1
        val totalPlanting = calendar.avgPlantingCostPerAcre * acres

        val currentSavings = getLatestSavings(db)
        val gap = totalPlanting - currentSavings

        val message = if (voice) {
            buildString {
                appendLine("🌱 *Gharama za Kupanda — ${calendar.displayName}*")
                appendLine("📏 Eka: $acres")
                appendLine()
                appendLine("Mbegu: KES ${formatKES(seedCost)}")
                appendLine("Mbolea: KES ${formatKES(fertilizerCost)}")
                appendLine("Kazi: KES ${formatKES(laborCost)}")
                appendLine("Nyingine: KES ${formatKES(otherCost)}")
                appendLine()
                appendLine("💰 Jumla: KES ${formatKES(totalPlanting)}")
                appendLine("💵 Akiba: KES ${formatKES(currentSavings)}")

                if (gap > 0) {
                    appendLine("⚠️ Pengo: KES ${formatKES(gap)}")
                    appendLine()
                    appendLine("💡 *Jinsi ya kujaza pengo:*")
                    appendLine("   1. Mkopo wa Chama (riba ndogo)")
                    appendLine("   2. Mkopo wa M-Shwari/KCB M-Pesa")
                    appendLine("   3. Kuuza mali au mifugo")
                    appendLine("   4. Kupanda kwa awamu —anza na nusu eka")
                } else {
                    appendLine("✅ Akiba inatosheka kupanda!")
                    appendLine("   Baki: KES ${formatKES(currentSavings - totalPlanting)}")
                }
            }
        } else {
            buildString {
                appendLine("Planting costs — ${calendar.displayName} ($acres acres):")
                appendLine("Seeds: KES ${formatKES(seedCost)}")
                appendLine("Fertilizer: KES ${formatKES(fertilizerCost)}")
                appendLine("Labor: KES ${formatKES(laborCost)}")
                appendLine("Other: KES ${formatKES(otherCost)}")
                appendLine("Total: KES ${formatKES(totalPlanting)}")
                appendLine("Savings: KES ${formatKES(currentSavings)}")
                if (gap > 0) appendLine("Gap: KES ${formatKES(gap)} — need financing")
                else appendLine("Surplus: KES ${formatKES(currentSavings - totalPlanting)}")
            }
        }

        return ToolResult.success(
            name,
            mapOf(
                "seed_cost" to seedCost, "fertilizer_cost" to fertilizerCost,
                "labor_cost" to laborCost, "other_cost" to otherCost,
                "total_planting_cost" to totalPlanting,
                "current_savings" to currentSavings, "gap" to gap
            ),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: cash_runway — How long until money runs out?
    // ──────────────────────────────────────────────

    private fun cashRunway(params: Map<String, String>): ToolResult {
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val db = getDb()
        val profile = getProfile(db) ?: return ToolResult.error(name, "Set crop first", "NO_PROFILE")

        val calendar = CROP_CALENDARS[profile.crop] ?: return ToolResult.error(name, "Unknown crop", "UNKNOWN_CROP")
        val currentSavings = getLatestSavings(db)
        val currentMonth = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1
        val currentSeason = getSeasonForMonth(calendar, currentMonth)

        // Monthly expenses vary by season
        val monthlyExpenses = when (currentSeason) {
            "planting" -> calendar.avgPlantingCostPerAcre * profile.landAcres / 2.0 // 2 months planting
            "growing" -> calendar.avgGrowingCostPerAcre * profile.landAcres / 3.0   // 3 months growing
            "harvest" -> calendar.avgHarvestCostPerAcre * profile.landAcres / 2.0    // 2 months harvest
            "post_harvest" -> 15_000.0 // school fees, food, medical
            else -> 10_000.0
        }

        val runwayMonths = if (monthlyExpenses > 0) (currentSavings / monthlyExpenses).toInt() else 99
        val monthsUntilHarvest = monthsUntilNextHarvest(calendar, currentMonth)
        val willSurvive = runwayMonths >= monthsUntilHarvest

        val swahiliMonths = arrayOf("", "Januari", "Februari", "Machi", "Aprili", "Mei", "Juni",
            "Julai", "Agosti", "Septemba", "Oktoba", "Novemba", "Desemba")

        val message = if (voice) {
            buildString {
                appendLine("⏱️ *Uchunguzi wa Pesa*")
                appendLine()
                appendLine("💵 Akiba ya sasa: KES ${formatKES(currentSavings)}")
                appendLine("💸 Gharama za mwezi: KES ${formatKES(monthlyExpenses)}")
                appendLine("📅 Msimu: ${SEASON_NAMES_SW[currentSeason]}")
                appendLine()
                appendLine("🕐 Pesa zitatosheka kwa: miezi ${runwayMonths.toInt()}")
                appendLine("🌾 Mavuno yajayo: miezi $monthsUntilHarvest")

                if (willSurvive) {
                    val surplus = currentSavings - (monthlyExpenses * monthsUntilHarvest)
                    appendLine()
                    appendLine("✅ Pesa zitatosheka hadi kuvuna!")
                    appendLine("   Baki: KES ${formatKES(surplus)}")
                } else {
                    val shortfall = (monthlyExpenses * monthsUntilHarvest) - currentSavings
                    appendLine()
                    appendLine("🚨 *Hatari!* Pesa zitaisha kabla ya kuvuna!")
                    appendLine("   Pengo: KES ${formatKES(shortfall)}")
                    appendLine()
                    appendLine("💡 *Hatua za haraka:*")
                    appendLine("   1. Punguza gharama — epuka ziada")
                    appendLine("   2. Tafuta mkopo wa dharura")
                    appendLine("   3. Uza mazao ya mapema (kama yapo)")
                    appendLine("   4. Omba msaada wa Chama")
                }
            }
        } else {
            buildString {
                appendLine("Cash Runway Analysis:")
                appendLine("Savings: KES ${formatKES(currentSavings)}")
                appendLine("Monthly burn: KES ${formatKES(monthlyExpenses)}")
                appendLine("Runway: ${runwayMonths.toInt()} months")
                appendLine("Months until harvest: $monthsUntilHarvest")
                appendLine(if (willSurvive) "✅ Will survive until harvest" else "🚨 SHORTFALL — will run out before harvest")
            }
        }

        return ToolResult.success(
            name,
            mapOf(
                "current_savings" to currentSavings,
                "monthly_expenses" to monthlyExpenses,
                "runway_months" to runwayMonths.toInt(),
                "months_until_harvest" to monthsUntilHarvest,
                "will_survive" to willSurvive,
                "current_season" to currentSeason
            ),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: recovery_timeline — When will harvest cover costs?
    // ──────────────────────────────────────────────

    private fun recoveryTimeline(params: Map<String, String>): ToolResult {
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val db = getDb()
        val profile = getProfile(db) ?: return ToolResult.error(name, "Set crop first", "NO_PROFILE")

        val calendar = CROP_CALENDARS[profile.crop] ?: return ToolResult.error(name, "Unknown crop", "UNKNOWN_CROP")
        val lastHarvestIncome = getLastHarvestIncome(db, profile.crop)
        val plantingCost = calendar.avgPlantingCostPerAcre * profile.landAcres

        // Recovery = months for harvest income to cover planting costs
        // Assume monthly living expenses come out of harvest income
        val monthlyLiving = 15_000.0
        val netMonthlyRecovery = if (lastHarvestIncome > 0) {
            (lastHarvestIncome - plantingCost) / monthlyLiving
        } else {
            val expectedIncome = calendar.avgYieldBagsPerAcre * profile.landAcres * calendar.avgPricePerBag
            (expectedIncome - plantingCost) / monthlyLiving
        }

        val recoveryMonths = maxOf(1, netMonthlyRecovery.toInt())
        val swahiliMonths = arrayOf("", "Januari", "Februari", "Machi", "Aprili", "Mei", "Juni",
            "Julai", "Agosti", "Septemba", "Oktoba", "Novemba", "Desemba")
        val currentMonth = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1
        val harvestMonth = calendar.harvestMonths.firstOrNull() ?: 8
        val recoveryMonth = ((harvestMonth - 1 + recoveryMonths) % 12) + 1

        val message = if (voice) {
            buildString {
                appendLine("📅 *Muda wa Kurejesha Pesa*")
                appendLine()
                appendLine("🌱 Gharama za kupanda: KES ${formatKES(plantingCost)}")
                if (lastHarvestIncome > 0) {
                    appendLine("🌾 Mavuno ya mwisho: KES ${formatKES(lastHarvestIncome)}")
                } else {
                    val expected = calendar.avgYieldBagsPerAcre * profile.landAcres * calendar.avgPricePerBag
                    appendLine("🌾 Matarajio ya mavuno: KES ${formatKES(expected)}")
                }
                appendLine()
                appendLine("💡 Kulingana na mavuno ya mwisho, utarudisha KES ${formatKES(plantingCost)} ndani ya miezi $recoveryMonths.")
                appendLine("📅 Mwezi wa kurejesha: ~${swahiliMonths[recoveryMonth]}")
                appendLine()
                if (netMonthlyRecovery > 0) {
                    appendLine("✅ Mavuno yanatosheka kugharamia msimu ujao na kuishi.")
                } else {
                    appendLine("⚠️ Mavuno hayatosheki — fikiria kupunguza gharama au kuongeza eka.")
                }
            }
        } else {
            buildString {
                appendLine("Recovery Timeline:")
                appendLine("Planting cost: KES ${formatKES(plantingCost)}")
                appendLine("Expected recovery: $recoveryMonths months after harvest")
                appendLine("Recovery month: ~${swahiliMonths[recoveryMonth]}")
            }
        }

        return ToolResult.success(
            name,
            mapOf(
                "planting_cost" to plantingCost,
                "last_harvest_income" to lastHarvestIncome,
                "recovery_months" to recoveryMonths,
                "recovery_month" to recoveryMonth,
                "net_monthly_recovery" to netMonthlyRecovery
            ),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: set_crop — Set primary crop
    // ──────────────────────────────────────────────

    private fun setCrop(params: Map<String, String>): ToolResult {
        val rawCrop = params["crop"]
            ?: return ToolResult.error(name, "Crop name required. Options: mahindi, maharagwe, nyanya, chai, kahawa, mchele", "MISSING_CROP")
        val crop = normalizeCrop(rawCrop)
        val calendar = CROP_CALENDARS[crop]
            ?: return ToolResult.error(name, "Unknown crop: $rawCrop. Options: mahindi, maharagwe, nyanya, chai, kahawa, mchele", "UNKNOWN_CROP")
        val landAcres = params["land_acres"]?.toDoubleOrNull() ?: 1.0
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val db = getDb()

        // Upsert profile
        db.delete(TABLE_PROFILE, null, null)
        val values = ContentValues().apply {
            put("crop", crop)
            put("land_acres", landAcres)
            put("updated_at", System.currentTimeMillis())
        }
        db.insert(TABLE_PROFILE, null, values)

        val message = if (voice) {
            "✅ Profili imewekwa: ${calendar.displayName}, eka $landAcres.\n" +
            "Sasa naweza kukupa bajeti ya msimu, gharama za kupanda, na ushauri wa pesa."
        } else {
            "Profile set: ${calendar.displayName}, $landAcres acres. Seasonal budget planning is now active."
        }

        return ToolResult.success(
            name,
            mapOf("crop" to crop, "land_acres" to landAcres, "calendar" to calendar.crop),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: set_expenses — Set seasonal expense profile
    // ──────────────────────────────────────────────

    private fun setExpenses(params: Map<String, String>): ToolResult {
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val db = getDb()
        val profile = getProfile(db) ?: return ToolResult.error(name, "Set crop first", "NO_PROFILE")
        val now = System.currentTimeMillis()

        // Record custom expenses for current season
        val calendar = CROP_CALENDARS[profile.crop] ?: return ToolResult.error(name, "Unknown crop", "UNKNOWN_CROP")
        val currentMonth = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1
        val season = getSeasonForMonth(calendar, currentMonth)

        val values = ContentValues().apply {
            put("crop", profile.crop)
            put("season", season)
            put("category", "custom")
            put("estimated_cost", params["current_savings"]?.toDoubleOrNull() ?: 0.0)
            put("recorded_at", now)
        }
        db.insert(TABLE_EXPENSES, null, values)

        // Record savings
        params["current_savings"]?.toDoubleOrNull()?.let { savings ->
            val savingsValues = ContentValues().apply {
                put("balance", savings)
                put("source", "user_input")
                put("recorded_at", now)
            }
            db.insert(TABLE_SAVINGS, null, savingsValues)
        }

        return ToolResult.success(
            name,
            mapOf("season" to season, "crop" to profile.crop),
            if (voice) "✅ Gharama zamerekodwa kwa msimu wa ${SEASON_NAMES_SW[season]}."
            else "Expenses recorded for $season season."
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: record_harvest_income — Record actual harvest sale
    // ──────────────────────────────────────────────

    private fun recordHarvestIncome(params: Map<String, String>): ToolResult {
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val db = getDb()
        val profile = getProfile(db) ?: return ToolResult.error(name, "Set crop first", "NO_PROFILE")
        val amount = params["harvest_income"]?.toDoubleOrNull()
            ?: return ToolResult.error(name, "harvest_income required (KES)", "MISSING_INCOME")
        val now = System.currentTimeMillis()

        val values = ContentValues().apply {
            put("crop", profile.crop)
            put("amount", amount)
            put("recorded_at", now)
        }
        db.insert(TABLE_INCOME, null, values)

        // Update savings with harvest income
        val savingsValues = ContentValues().apply {
            put("balance", amount)
            put("source", "harvest_${profile.crop}")
            put("recorded_at", now)
        }
        db.insert(TABLE_SAVINGS, null, savingsValues)

        val calendar = CROP_CALENDARS[profile.crop]
        val plantingCost = (calendar?.avgPlantingCostPerAcre ?: 0.0) * profile.landAcres
        val recoveryMonths = if (amount > plantingCost && plantingCost > 0) {
            ((amount - plantingCost) / 15_000.0).toInt()
        } else 0

        val message = if (voice) {
            buildString {
                appendLine("✅ Mapato ya mavuno yamerekodwa: KES ${formatKES(amount)}")
                if (recoveryMonths > 0) {
                    appendLine("💡 Baada ya gharama za kupanda (KES ${formatKES(plantingCost)}),")
                    appendLine("   pesa hizi zitatosheka kwa miezi $recoveryMonths ya maisha.")
                }
                appendLine("📊 Sasa naweza kukupa bajeti bora ya msimu ujao.")
            }
        } else {
            "Harvest income recorded: KES ${formatKES(amount)}. Recovery: $recoveryMonths months after planting costs."
        }

        return ToolResult.success(
            name,
            mapOf("amount" to amount, "recovery_months" to recoveryMonths, "crop" to profile.crop),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: current_season — What season am I in?
    // ──────────────────────────────────────────────

    private fun currentSeason(params: Map<String, String>): ToolResult {
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val db = getDb()
        val profile = getProfile(db)

        val crop = params["crop"]?.let { normalizeCrop(it) } ?: profile?.crop ?: "maize"
        val calendar = CROP_CALENDARS[crop] ?: return ToolResult.error(name, "Unknown crop", "UNKNOWN_CROP")

        val currentMonth = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1
        val season = getSeasonForMonth(calendar, currentMonth)

        val swahiliMonths = arrayOf("", "Januari", "Februari", "Machi", "Aprili", "Mei", "Juni",
            "Julai", "Agosti", "Septemba", "Oktoba", "Novemba", "Desemba")

        val message = if (voice) {
            buildString {
                appendLine("📅 Msimu wa sasa: ${SEASON_NAMES_SW[season]}")
                appendLine("🌱 Mazao: ${calendar.displayName}")
                appendLine("📆 Mwezi: ${swahiliMonths[currentMonth]}")
                appendLine()
                when (season) {
                    "planting" -> {
                        appendLine("💡 Sasa ni wakati wa kupanda!")
                        appendLine("   Hakikisha una mbegu, mbolea, na kazi tayari.")
                    }
                    "growing" -> {
                        appendLine("💡 Mazao yako yanakua.")
                        appendLine("   Kazi kuu: kupalilia na kuzuia wadudu.")
                    }
                    "harvest" -> {
                        appendLine("💡 Ni wakati wa kuvuna!")
                        appendLine("   Fikiria bei ya soko kabla ya kuuza.")
                    }
                    "post_harvest" -> {
                        appendLine("💡 Baada ya kuvuna — weka akiba!")
                        appendLine("   Anza kupanga msimu ujao.")
                    }
                }
            }
        } else {
            "Current season: ${SEASON_NAMES_SW[season]} ($crop, month $currentMonth)"
        }

        return ToolResult.success(
            name,
            mapOf("season" to season, "crop" to crop, "month" to currentMonth),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: plan_next_season — Forward-looking budget
    // ──────────────────────────────────────────────

    private fun planNextSeason(params: Map<String, String>): ToolResult {
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true
        val db = getDb()
        val profile = getProfile(db) ?: return ToolResult.error(name, "Set crop first", "NO_PROFILE")

        val calendar = CROP_CALENDARS[profile.crop] ?: return ToolResult.error(name, "Unknown crop", "UNKNOWN_CROP")
        val currentSavings = getLatestSavings(db)
        val lastHarvest = getLastHarvestIncome(db, profile.crop)

        val plantingCost = calendar.avgPlantingCostPerAcre * profile.landAcres
        val growingCost = calendar.avgGrowingCostPerAcre * profile.landAcres
        val harvestCost = calendar.avgHarvestCostPerAcre * profile.landAcres
        val totalNeeded = plantingCost + growingCost + harvestCost

        val expectedIncome = calendar.avgYieldBagsPerAcre * profile.landAcres * calendar.avgPricePerBag
        val expectedProfit = expectedIncome - totalNeeded

        val swahiliMonths = arrayOf("", "Januari", "Februari", "Machi", "Aprili", "Mei", "Juni",
            "Julai", "Agosti", "Septemba", "Oktoba", "Novemba", "Desemba")

        val nextPlantingMonth = calendar.plantingMonths.first()
        val monthsUntilPlanting = ((nextPlantingMonth - java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) - 1 + 12) % 12) + 1

        val message = if (voice) {
            buildString {
                appendLine("📋 *Mpango wa Msimu Ujao — ${calendar.displayName}*")
                appendLine()
                appendLine("🌱 Kupanda: ${swahiliMonths[nextPlantingMonth]} (miezi $monthsUntilPlanting kutoka sasa)")
                appendLine("   Gharama: KES ${formatKES(plantingCost)}")
                appendLine()
                appendLine("🌿 Kukuza: KES ${formatKES(growingCost)}")
                appendLine("🌾 Kuvuna: KES ${formatKES(harvestCost)}")
                appendLine()
                appendLine("💰 Jumla gharama: KES ${formatKES(totalNeeded)}")
                appendLine("📊 Matarajio ya mapato: KES ${formatKES(expectedIncome)}")
                appendLine("✅ Faida ya matarajio: KES ${formatKES(expectedProfit)}")
                appendLine()
                appendLine("💵 Akiba ya sasa: KES ${formatKES(currentSavings)}")

                val gap = totalNeeded - currentSavings
                if (gap > 0) {
                    appendLine("⚠️ Unahitaji KES ${formatKES(gap)} zaidi")
                    appendLine()
                    appendLine("💡 *Mpango wa kujaza pengo:*")
                    val monthlySaving = gap / monthsUntilPlanting
                    appendLine("   Hifadhi KES ${formatKES(monthlySaving)} kwa mwezi")
                    appendLine("   kwa miezi $monthsUntilPlanting kabla ya kupanda.")
                } else {
                    appendLine("✅ Tayari kupanda! Baki: KES ${formatKES(currentSavings - totalNeeded)}")
                }

                if (lastHarvest > 0) {
                    appendLine()
                    appendLine("📊 Mavuno ya mwisho: KES ${formatKES(lastHarvest)}")
                    if (lastHarvest >= totalNeeded) {
                        appendLine("   ✅ Mavuno yalitosheka kugharamia msimu mzima!")
                    }
                }
            }
        } else {
            buildString {
                appendLine("Next Season Plan — ${calendar.displayName}:")
                appendLine("Planting (${swahiliMonths[nextPlantingMonth]}): KES ${formatKES(plantingCost)}")
                appendLine("Growing: KES ${formatKES(growingCost)}")
                appendLine("Harvest: KES ${formatKES(harvestCost)}")
                appendLine("Total needed: KES ${formatKES(totalNeeded)}")
                appendLine("Expected income: KES ${formatKES(expectedIncome)}")
                appendLine("Expected profit: KES ${formatKES(expectedProfit)}")
                appendLine("Current savings: KES ${formatKES(currentSavings)}")
                appendLine("Months until planting: $monthsUntilPlanting")
            }
        }

        return ToolResult.success(
            name,
            mapOf(
                "crop" to profile.crop, "total_needed" to totalNeeded,
                "expected_income" to expectedIncome, "expected_profit" to expectedProfit,
                "current_savings" to currentSavings, "months_until_planting" to monthsUntilPlanting,
                "gap" to (totalNeeded - currentSavings)
            ),
            message
        )
    }

    // ──────────────────────────────────────────────
    // Internal Helpers
    // ──────────────────────────────────────────────

    private fun getProfile(db: SQLiteDatabase): FarmerProfile? {
        val cursor = db.query(TABLE_PROFILE, null, null, null, null, null, "updated_at DESC", "1")
        cursor.use {
            return if (it.moveToFirst()) {
                FarmerProfile(
                    crop = it.getString(it.getColumnIndexOrThrow("crop")),
                    landAcres = it.getDouble(it.getColumnIndexOrThrow("land_acres")),
                    county = it.getString(it.getColumnIndexOrThrow("county"))
                )
            } else null
        }
    }

    private fun getTotalIncome(db: SQLiteDatabase, crop: String): Double {
        val cursor = db.rawQuery("SELECT COALESCE(SUM(amount), 0) FROM $TABLE_INCOME WHERE crop = ?", arrayOf(crop))
        cursor.use { return if (it.moveToFirst()) it.getDouble(0) else 0.0 }
    }

    private fun getTotalExpenses(db: SQLiteDatabase, crop: String): Double {
        val cursor = db.rawQuery("SELECT COALESCE(SUM(COALESCE(actual_cost, estimated_cost)), 0) FROM $TABLE_EXPENSES WHERE crop = ?", arrayOf(crop))
        cursor.use { return if (it.moveToFirst()) it.getDouble(0) else 0.0 }
    }

    private fun getLastHarvestIncome(db: SQLiteDatabase, crop: String): Double {
        val cursor = db.rawQuery("SELECT amount FROM $TABLE_INCOME WHERE crop = ? ORDER BY recorded_at DESC LIMIT 1", arrayOf(crop))
        cursor.use { return if (it.moveToFirst()) it.getDouble(0) else 0.0 }
    }

    private fun getLatestSavings(db: SQLiteDatabase): Double {
        val cursor = db.query(TABLE_SAVINGS, arrayOf("balance"), null, null, null, null, "recorded_at DESC", "1")
        cursor.use { return if (it.moveToFirst()) it.getDouble(0) else 0.0 }
    }

    private fun getSeasonForMonth(calendar: CropCalendar, month: Int): String {
        return when {
            calendar.plantingMonths.contains(month) -> "planting"
            calendar.growingMonths.contains(month) -> "growing"
            calendar.harvestMonths.contains(month) -> "harvest"
            else -> "post_harvest"
        }
    }

    private fun monthsUntilNextHarvest(calendar: CropCalendar, currentMonth: Int): Int {
        for (i in 0..12) {
            val checkMonth = ((currentMonth - 1 + i) % 12) + 1
            if (calendar.harvestMonths.contains(checkMonth)) return i
        }
        return 6 // fallback
    }

    private fun normalizeCrop(raw: String): String {
        val aliases = mapOf(
            "mahindi" to "maize", "maize" to "maize", "corn" to "maize",
            "maharagwe" to "beans", "beans" to "beans",
            "nyanya" to "tomatoes", "tomatoes" to "tomatoes", "tomato" to "tomatoes",
            "chai" to "tea", "tea" to "tea",
            "kahawa" to "coffee", "coffee" to "coffee",
            "mchele" to "rice", "rice" to "rice",
            "ngano" to "wheat", "wheat" to "wheat",
            "mtama" to "sorghum", "sorghum" to "sorghum"
        )
        return aliases[raw.trim().lowercase()] ?: raw.trim().lowercase()
    }

    private fun formatKES(amount: Double): String {
        return if (amount == amount.toLong().toDouble()) "%,d".format(amount.toLong()) else "%,.0f".format(amount)
    }

    // ──────────────────────────────────────────────
    // Data Classes
    // ──────────────────────────────────────────────

    data class FarmerProfile(
        val crop: String,
        val landAcres: Double,
        val county: String?
    )

    data class CropCalendar(
        val crop: String,
        val displayName: String,
        val plantingMonths: List<Int>,
        val growingMonths: List<Int>,
        val harvestMonths: List<Int>,
        val avgPlantingCostPerAcre: Double,
        val avgGrowingCostPerAcre: Double,
        val avgHarvestCostPerAcre: Double,
        val avgYieldBagsPerAcre: Double,
        val avgPricePerBag: Double
    )
}
