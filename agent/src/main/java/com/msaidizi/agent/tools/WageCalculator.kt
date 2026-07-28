package com.msaidizi.agent.tools

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WageCalculator — Calculates fair wages for labor and service work.
 *
 * Addresses the critical gap where construction workers (fundis, masons,
 * laborers), salon workers, and other service providers have no mechanism
 * to determine fair wages. They either undercharge (losing income) or
 * overcharge (losing customers).
 *
 * Features:
 * - Skill-based rate calculation (apprentice → master)
 * - Project type estimation (foundation, roofing, plumbing, electrical)
 * - Material cost integration
 * - Regional wage index (Nairobi vs Migori vs Kisumu)
 * - Daily, hourly, and project-based rates
 * - Fair wage benchmarking against regional medians
 */
@Singleton
class WageCalculator @Inject constructor(
    private val context: Context
) : Tool {

    override val name = "wage_calculator"
    override val description = "Calculate fair wages for labor and service work. Supports skill-based rates, project estimation, and regional comparisons."

    override val argsSchema = argSchema {
        enum(
            "action", "Action to perform",
            listOf(
                "calculate",        // Calculate fair wage for a job
                "project_estimate", // Full project cost estimate
                "compare_skills",   // Compare wages across skill levels
                "regional_index",   // Show regional wage index
                "set_wage",         // Record a wage observation
                "daily_rate",       // Quick daily rate lookup
                "list_skills"       // List all tracked skill types
            ),
            required = true
        )
        string("skill_type", "Skill type: mason, plumber, electrician, carpenter, painter, welder, roofer, tiler, laborer, fundi, braider, barber, mechanic", required = false)
        string("experience", "Experience level: apprentice, junior, intermediate, senior, master", required = false)
        string("region", "Region (e.g. 'Nairobi', 'Migori', 'Kisumu', 'Mombasa')", required = false)
        string("project_type", "Project type: foundation, walling, roofing, plumbing, electrical, painting, tiling, fencing, renovation", required = false)
        integer("duration_days", "Project duration in days", required = false)
        integer("workers_needed", "Number of workers needed", required = false)
        number("material_cost", "Known material cost in KES (if available)", required = false)
        number("wage_amount", "Wage amount to record or compare (KES)", required = false)
        boolean("include_materials", "Include material cost estimates in project estimate", required = false)
        boolean("voice", "Format for voice output (Swahili)", required = false)
    }

    // ──────────────────────────────────────────────
    // SQLite Database
    // ──────────────────────────────────────────────

    inner class WageDatabase(context: Context) :
        SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            // Wage rate cards
            db.execSQL("""
                CREATE TABLE $TABLE_WAGES (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    skill_type TEXT NOT NULL,
                    experience TEXT NOT NULL,
                    region TEXT NOT NULL,
                    daily_rate REAL NOT NULL,
                    hourly_rate REAL NOT NULL,
                    sample_size INTEGER NOT NULL DEFAULT 0,
                    recorded_at INTEGER NOT NULL,
                    synced_at INTEGER NOT NULL,
                    UNIQUE(skill_type, experience, region)
                )
            """)

            // Project pricing templates
            db.execSQL("""
                CREATE TABLE $TABLE_PROJECTS (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    project_type TEXT NOT NULL,
                    skill_required TEXT NOT NULL,
                    region TEXT NOT NULL,
                    labor_cost_per_day REAL NOT NULL,
                    material_cost_estimate REAL NOT NULL,
                    duration_days INTEGER NOT NULL,
                    workers_needed INTEGER NOT NULL DEFAULT 1,
                    total_estimate REAL NOT NULL,
                    sample_size INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL,
                    UNIQUE(project_type, skill_required, region)
                )
            """)

            // Material prices
            db.execSQL("""
                CREATE TABLE $TABLE_MATERIALS (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    unit TEXT NOT NULL,
                    region TEXT NOT NULL,
                    price_avg REAL NOT NULL,
                    price_min REAL,
                    price_max REAL,
                    updated_at INTEGER NOT NULL,
                    UNIQUE(name, unit, region)
                )
            """)

            // Wage observations (from workers)
            db.execSQL("""
                CREATE TABLE $TABLE_OBSERVATIONS (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    skill_type TEXT NOT NULL,
                    experience TEXT,
                    region TEXT NOT NULL,
                    daily_rate REAL NOT NULL,
                    source TEXT NOT NULL DEFAULT 'worker',
                    recorded_at INTEGER NOT NULL,
                    synced INTEGER NOT NULL DEFAULT 0
                )
            """)

            // Regional cost of living index
            db.execSQL("""
                CREATE TABLE $TABLE_COL_INDEX (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    region TEXT NOT NULL UNIQUE,
                    cost_of_living_index REAL NOT NULL DEFAULT 1.0,
                    avg_daily_wage REAL,
                    median_daily_wage REAL,
                    worker_count INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL
                )
            """)

            db.execSQL("CREATE INDEX idx_wages_skill ON $TABLE_WAGES(skill_type)")
            db.execSQL("CREATE INDEX idx_wages_region ON $TABLE_WAGES(region)")
            db.execSQL("CREATE INDEX idx_projects_type ON $TABLE_PROJECTS(project_type)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_WAGES")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_PROJECTS")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_MATERIALS")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_OBSERVATIONS")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_COL_INDEX")
            onCreate(db)
        }
    }

    companion object {
        private const val DB_NAME = "wages.db"
        private const val DB_VERSION = 1
        private const val TABLE_WAGES = "wage_rates"
        private const val TABLE_PROJECTS = "project_pricing"
        private const val TABLE_MATERIALS = "material_prices"
        private const val TABLE_OBSERVATIONS = "wage_observations"
        private const val TABLE_COL_INDEX = "cost_of_living_index"
    }

    private var dbHelper: WageDatabase? = null

    private fun getDb(): SQLiteDatabase {
        if (dbHelper == null) dbHelper = WageDatabase(context)
        return dbHelper!!.writableDatabase
    }

    // ──────────────────────────────────────────────
    // Skill type aliases (Swahili-aware)
    // ──────────────────────────────────────────────

    private val skillAliases = mapOf(
        // Construction
        "mason" to "mason", "mjengo" to "mason", "masonry" to "mason",
        "plumber" to "plumber", "fundi_maji" to "plumber", "plumbing" to "plumber",
        "electrician" to "electrician", "fundi_umeme" to "electrician", "electrical" to "electrician",
        "carpenter" to "carpenter", "fundi_seremala" to "carpenter", "carpentry" to "carpenter",
        "painter" to "painter", "painting" to "painter",
        "welder" to "welder", "welding" to "welder",
        "roofer" to "roofer", "roofing" to "roofer",
        "tiler" to "tiler", "tiling" to "tiler",
        "laborer" to "general_laborer", "general_laborer" to "general_laborer",
        "mtu_wa_kazi" to "general_laborer", "casual" to "general_laborer",
        "fundi" to "fundi",
        // Beauty
        "braider" to "hair_braider", "hair_braider" to "hair_braider",
        "barber" to "barber", "kinyozi" to "barber",
        "stylist" to "hair_stylist", "hair_stylist" to "hair_stylist",
        // Repair
        "mechanic" to "mechanic", "fundi_gari" to "mechanic",
        "phone_tech" to "phone_technician", "fundi_simu" to "phone_technician",
        "electronics" to "electronics_tech", "fundi_tv" to "electronics_tech",
    )

    // ──────────────────────────────────────────────
    // Experience level multipliers
    // ──────────────────────────────────────────────

    private val experienceMultipliers = mapOf(
        "apprentice" to 0.55,
        "junior" to 0.75,
        "intermediate" to 1.0,
        "senior" to 1.35,
        "master" to 1.7
    )

    // ──────────────────────────────────────────────
    // Default daily rates (KES) — baseline when no data
    // Based on Kenya informal sector research 2024-2026
    // ──────────────────────────────────────────────

    private val defaultDailyRates = mapOf(
        "mason" to 1200.0,
        "plumber" to 1500.0,
        "electrician" to 1800.0,
        "carpenter" to 1300.0,
        "painter" to 1000.0,
        "welder" to 1400.0,
        "roofer" to 1300.0,
        "tiler" to 1200.0,
        "general_laborer" to 700.0,
        "fundi" to 1200.0,
        "hair_braider" to 800.0,
        "barber" to 600.0,
        "hair_stylist" to 1000.0,
        "mechanic" to 1500.0,
        "phone_technician" to 1200.0,
        "electronics_tech" to 1300.0,
    )

    // ──────────────────────────────────────────────
    // Regional cost-of-living multipliers
    // ──────────────────────────────────────────────

    private val regionalMultipliers = mapOf(
        "nairobi" to 1.3,
        "mombasa" to 1.15,
        "kisumu" to 1.05,
        "nakuru" to 1.0,
        "eldoret" to 0.95,
        "thika" to 1.1,
        "migori" to 0.85,
        "kisii" to 0.9,
        "meru" to 0.95,
        "nyeri" to 0.95,
        "machakos" to 1.0,
        "kitale" to 0.85,
        "garissa" to 0.9,
        "malindi" to 0.95,
        "voi" to 0.85,
    )

    // ──────────────────────────────────────────────
    // Project material cost estimates (KES)
    // ──────────────────────────────────────────────

    private val projectDefaults = mapOf(
        "foundation" to ProjectDefaults(1200.0, 15000.0, 5, 3),
        "walling" to ProjectDefaults(1200.0, 25000.0, 10, 4),
        "roofing" to ProjectDefaults(1300.0, 35000.0, 5, 3),
        "plumbing" to ProjectDefaults(1500.0, 20000.0, 3, 2),
        "electrical" to ProjectDefaults(1800.0, 15000.0, 3, 2),
        "painting" to ProjectDefaults(1000.0, 12000.0, 4, 2),
        "tiling" to ProjectDefaults(1200.0, 18000.0, 5, 2),
        "fencing" to ProjectDefaults(1100.0, 20000.0, 5, 3),
        "renovation" to ProjectDefaults(1300.0, 50000.0, 14, 4),
    )

    private data class ProjectDefaults(
        val dailyRate: Double,
        val materialCost: Double,
        val durationDays: Int,
        val workersNeeded: Int
    )

    // ──────────────────────────────────────────────
    // Execute
    // ──────────────────────────────────────────────

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"]
            ?: return ToolResult.error(name, "Action required", "MISSING_ACTION")

        return when (action.lowercase()) {
            "calculate" -> calculate(params)
            "project_estimate" -> projectEstimate(params)
            "compare_skills" -> compareSkills(params)
            "regional_index" -> regionalIndex(params)
            "set_wage" -> setWage(params)
            "daily_rate" -> dailyRate(params)
            "list_skills" -> listSkills()
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    // ──────────────────────────────────────────────
    // ACTION: calculate — Fair wage calculation
    // ──────────────────────────────────────────────

    private fun calculate(params: Map<String, String>): ToolResult {
        val skillRaw = params["skill_type"]
            ?: return ToolResult.error(name, "Skill type required (e.g. 'mason', 'plumber', 'braider')", "MISSING_SKILL")
        val experience = params["experience"] ?: "intermediate"
        val region = params["region"]?.lowercase() ?: "nairobi"
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val skill = skillAliases[skillRaw.lowercase()]
            ?: return ToolResult.error(name, "Unknown skill '$skillRaw'. Use list_skills to see options.", "UNKNOWN_SKILL")

        val db = getDb()

        // Try to get cached rate from database
        val cachedRate = queryWage(db, skill, experience, region)

        // Calculate base rate
        val baseRate = cachedRate?.dailyRate ?: defaultDailyRates[skill] ?: 1000.0
        val expMultiplier = experienceMultipliers[experience.lowercase()] ?: 1.0
        val regionMultiplier = regionalMultipliers[region] ?: 1.0

        val fairDailyWage = baseRate * expMultiplier * regionMultiplier
        val fairHourlyWage = fairDailyWage / 8.0 // 8-hour workday

        val sampleSize = cachedRate?.sampleSize ?: 0

        val message = if (voice) {
            buildString {
                append("Mshahara wa haki kwa ${skillDisplayName(skill)} ($experience) hapa ${region.replaceFirstChar { it.uppercase() }}:\n")
                append("• Kwa siku: KES ${formatPrice(fairDailyWage)}\n")
                append("• Kwa saa: KES ${formatPrice(fairHourlyWage)}\n")
                if (experience != "intermediate") {
                    append("• Kiwango cha msingi: KES ${formatPrice(baseRate)} (intermediate)\n")
                    append("• Multiplier: ${experience} = ${expMultiplier}x\n")
                }
                if (sampleSize > 0) {
                    append("• Data kutoka kwa wafanyakazi $sampleSize")
                } else {
                    append("• ⚠️ Hesabu ni makadirio — data halisi itapatikana baada ya sync")
                }
            }
        } else {
            buildString {
                append("Fair wage for ${skillDisplayName(skill)} ($experience) in ${region.replaceFirstChar { it.uppercase() }}:\n")
                append("• Daily: KES ${formatPrice(fairDailyWage)}\n")
                append("• Hourly: KES ${formatPrice(fairHourlyWage)}\n")
                append("• Base rate: KES ${formatPrice(baseRate)} (intermediate)\n")
                append("• Experience multiplier: $experience = ${expMultiplier}x\n")
                append("• Regional multiplier: $region = ${regionMultiplier}x\n")
                append("• Sample size: $sampleSize")
            }
        }

        return ToolResult.success(
            name,
            mapOf(
                "skill" to skill, "experience" to experience, "region" to region,
                "daily_rate" to fairDailyWage, "hourly_rate" to fairHourlyWage,
                "base_rate" to baseRate, "exp_multiplier" to expMultiplier,
                "region_multiplier" to regionMultiplier, "sample_size" to sampleSize
            ),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: project_estimate — Full project cost
    // ──────────────────────────────────────────────

    private fun projectEstimate(params: Map<String, String>): ToolResult {
        val projectType = params["project_type"]
            ?: return ToolResult.error(name, "Project type required (e.g. 'foundation', 'roofing', 'plumbing')", "MISSING_PROJECT")
        val region = params["region"]?.lowercase() ?: "nairobi"
        val durationDays = params["duration_days"]?.toIntOrNull()
        val workersNeeded = params["workers_needed"]?.toIntOrNull()
        val materialCostParam = params["material_cost"]?.toDoubleOrNull()
        val includeMaterials = params["include_materials"]?.toBooleanStrictOrNull() ?: true
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val defaults = projectDefaults[projectType.lowercase()]
            ?: return ToolResult.error(name, "Unknown project type '$projectType'. Options: ${projectDefaults.keys.joinToString()}", "UNKNOWN_PROJECT")

        val regionMultiplier = regionalMultipliers[region] ?: 1.0

        val dailyRate = defaults.dailyRate * regionMultiplier
        val duration = durationDays ?: defaults.durationDays
        val workers = workersNeeded ?: defaults.workersNeeded
        val materialCost = materialCostParam ?: (defaults.materialCost * regionMultiplier)

        val laborCost = dailyRate * duration * workers
        val totalCost = if (includeMaterials) laborCost + materialCost else laborCost

        val message = if (voice) {
            buildString {
                append("📋 Makadirio ya ${projectDisplayName(projectType)} hapa ${region.replaceFirstChar { it.uppercase() }}:\n")
                append("• Kazi: KES ${formatPrice(laborCost)}\n")
                append("  - Kwa siku: KES ${formatPrice(dailyRate)} × siku $duration × wafanyakazi $workers\n")
                if (includeMaterials) {
                    append("• Vifaa: KES ${formatPrice(materialCost)}\n")
                }
                append("• JUMLA: KES ${formatPrice(totalCost)}\n")
                append("\n💡 Hii ni makadirio. Thibitisha na fundi kabla ya kuanza kazi.")
            }
        } else {
            buildString {
                append("📋 Project estimate: ${projectDisplayName(projectType)} in ${region.replaceFirstChar { it.uppercase() }}\n")
                append("• Labor: KES ${formatPrice(laborCost)}\n")
                append("  - Rate: KES ${formatPrice(dailyRate)}/day × $duration days × $workers workers\n")
                if (includeMaterials) {
                    append("• Materials: KES ${formatPrice(materialCost)}\n")
                }
                append("• TOTAL: KES ${formatPrice(totalCost)}\n")
                append("\n💡 This is an estimate. Confirm with your fundi before starting.")
            }
        }

        return ToolResult.success(
            name,
            mapOf(
                "project_type" to projectType, "region" to region,
                "daily_rate" to dailyRate, "duration_days" to duration,
                "workers" to workers, "labor_cost" to laborCost,
                "material_cost" to if (includeMaterials) materialCost else null,
                "total" to totalCost
            ),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: compare_skills — Compare wages across skills
    // ──────────────────────────────────────────────

    private fun compareSkills(params: Map<String, String>): ToolResult {
        val region = params["region"]?.lowercase() ?: "nairobi"
        val experience = params["experience"] ?: "intermediate"
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val regionMultiplier = regionalMultipliers[region] ?: 1.0
        val expMultiplier = experienceMultipliers[experience.lowercase()] ?: 1.0

        val comparisons = defaultDailyRates.entries
            .map { (skill, baseRate) ->
                val adjusted = baseRate * expMultiplier * regionMultiplier
                skill to adjusted
            }
            .sortedByDescending { it.second }

        val message = if (voice) {
            buildString {
                append("Mishahara ya wafanyakazi hapa ${region.replaceFirstChar { it.uppercase() }} ($experience):\n")
                comparisons.forEach { (skill, rate) ->
                    append("• ${skillDisplayName(skill)}: KES ${formatRate(rate)}/siku\n")
                }
            }
        } else {
            buildString {
                append("Daily wages in ${region.replaceFirstChar { it.uppercase() }} ($experience):\n")
                comparisons.forEach { (skill, rate) ->
                    append("• ${skillDisplayName(skill)}: KES ${formatRate(rate)}/day\n")
                }
            }
        }

        return ToolResult.success(
            name,
            mapOf("region" to region, "experience" to experience, "comparisons" to comparisons.map { mapOf("skill" to it.first, "daily_rate" to it.second) }),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: regional_index — Show regional wage index
    // ──────────────────────────────────────────────

    private fun regionalIndex(params: Map<String, String>): ToolResult {
        val skillRaw = params["skill_type"]
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val skill = skillRaw?.let { skillAliases[it.lowercase()] } ?: "mason"
        val baseRate = defaultDailyRates[skill] ?: 1000.0

        val index = regionalMultipliers.entries
            .map { (region, multiplier) ->
                Triple(region, multiplier, baseRate * multiplier)
            }
            .sortedByDescending { it.third }

        val message = if (voice) {
            buildString {
                append("Mishahara ya ${skillDisplayName(skill)} kwa mikoa:\n")
                index.forEach { (region, mult, rate) ->
                    append("• ${region.replaceFirstChar { it.uppercase() }}: KES ${formatRate(rate)}/siku (${mult}x)\n")
                }
            }
        } else {
            buildString {
                append("${skillDisplayName(skill)} wages by region:\n")
                index.forEach { (region, mult, rate) ->
                    append("• ${region.replaceFirstChar { it.uppercase() }}: KES ${formatRate(rate)}/day (${mult}x)\n")
                }
            }
        }

        return ToolResult.success(
            name,
            mapOf("skill" to skill, "index" to index.map { mapOf("region" to it.first, "multiplier" to it.second, "daily_rate" to it.third) }),
            message
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: set_wage — Record a wage observation
    // ──────────────────────────────────────────────

    private fun setWage(params: Map<String, String>): ToolResult {
        val skillRaw = params["skill_type"]
            ?: return ToolResult.error(name, "Skill type required", "MISSING_SKILL")
        val region = params["region"]?.lowercase() ?: "nairobi"
        val wageAmount = params["wage_amount"]?.toDoubleOrNull()
            ?: return ToolResult.error(name, "Wage amount required in KES", "MISSING_WAGE")
        val experience = params["experience"]

        val skill = skillAliases[skillRaw.lowercase()]
            ?: return ToolResult.error(name, "Unknown skill '$skillRaw'", "UNKNOWN_SKILL")

        val db = getDb()
        val values = ContentValues().apply {
            put("skill_type", skill)
            put("experience", experience)
            put("region", region)
            put("daily_rate", wageAmount)
            put("source", "worker")
            put("recorded_at", System.currentTimeMillis())
            put("synced", 0)
        }
        db.insert(TABLE_OBSERVATIONS, null, values)

        return ToolResult.success(
            name,
            mapOf("skill" to skill, "region" to region, "daily_rate" to wageAmount, "queued" to true),
            "✅ Mshahara umesajiliwa: ${skillDisplayName(skill)} — KES ${formatPrice(wageAmount)}/siku hapa ${region.replaceFirstChar { it.uppercase() }}. Itasyncwa."
        )
    }

    // ──────────────────────────────────────────────
    // ACTION: daily_rate — Quick daily rate lookup
    // ──────────────────────────────────────────────

    private fun dailyRate(params: Map<String, String>): ToolResult {
        val skillRaw = params["skill_type"]
            ?: return ToolResult.error(name, "Skill type required", "MISSING_SKILL")
        val region = params["region"]?.lowercase() ?: "nairobi"
        val experience = params["experience"] ?: "intermediate"
        val voice = params["voice"]?.toBooleanStrictOrNull() ?: true

        val skill = skillAliases[skillRaw.lowercase()]
            ?: return ToolResult.error(name, "Unknown skill '$skillRaw'", "UNKNOWN_SKILL")

        val baseRate = defaultDailyRates[skill] ?: 1000.0
        val rate = baseRate * (experienceMultipliers[experience.lowercase()] ?: 1.0) * (regionalMultipliers[region] ?: 1.0)

        val message = if (voice) {
            "${skillDisplayName(skill)} ($experience) hapa ${region.replaceFirstChar { it.uppercase() }}: KES ${formatRate(rate)} kwa siku"
        } else {
            "${skillDisplayName(skill)} ($experience) in ${region.replaceFirstChar { it.uppercase() }}: KES ${formatRate(rate)}/day"
        }

        return ToolResult.success(name, mapOf("skill" to skill, "daily_rate" to rate), message)
    }

    // ──────────────────────────────────────────────
    // ACTION: list_skills — List all tracked skills
    // ──────────────────────────────────────────────

    private fun listSkills(): ToolResult {
        val message = buildString {
            append("🔧 Skills tracked:\n\n")
            append("🏗️ Construction:\n")
            append("• mason (mjengo) — KES ${formatRate(defaultDailyRates["mason"]!!)}/day\n")
            append("• plumber (fundi_maji) — KES ${formatRate(defaultDailyRates["plumber"]!!)}/day\n")
            append("• electrician (fundi_umeme) — KES ${formatRate(defaultDailyRates["electrician"]!!)}/day\n")
            append("• carpenter (fundi_seremala) — KES ${formatRate(defaultDailyRates["carpenter"]!!)}/day\n")
            append("• painter — KES ${formatRate(defaultDailyRates["painter"]!!)}/day\n")
            append("• welder — KES ${formatRate(defaultDailyRates["welder"]!!)}/day\n")
            append("• roofer — KES ${formatRate(defaultDailyRates["roofer"]!!)}/day\n")
            append("• tiler — KES ${formatRate(defaultDailyRates["tiler"]!!)}/day\n")
            append("• laborer (mtu_wa_kazi) — KES ${formatRate(defaultDailyRates["general_laborer"]!!)}/day\n\n")
            append("💇 Beauty:\n")
            append("• braider — KES ${formatRate(defaultDailyRates["hair_braider"]!!)}/day\n")
            append("• barber (kinyozi) — KES ${formatRate(defaultDailyRates["barber"]!!)}/day\n")
            append("• stylist — KES ${formatRate(defaultDailyRates["hair_stylist"]!!)}/day\n\n")
            append("🔧 Repair:\n")
            append("• mechanic (fundi_gari) — KES ${formatRate(defaultDailyRates["mechanic"]!!)}/day\n")
            append("• phone_tech (fundi_simu) — KES ${formatRate(defaultDailyRates["phone_technician"]!!)}/day\n")
            append("• electronics — KES ${formatRate(defaultDailyRates["electronics_tech"]!!)}/day\n")
        }

        return ToolResult.success(name, mapOf("skills" to defaultDailyRates.keys.toList()), message)
    }

    // ──────────────────────────────────────────────
    // Database Helpers
    // ──────────────────────────────────────────────

    private fun queryWage(db: SQLiteDatabase, skill: String, experience: String, region: String): WageData? {
        val cursor = db.query(
            TABLE_WAGES, null,
            "skill_type = ? AND experience = ? AND region = ?",
            arrayOf(skill, experience, region),
            null, null, null
        )
        cursor.use {
            return if (it.moveToFirst()) {
                WageData(
                    dailyRate = it.getDouble(it.getColumnIndexOrThrow("daily_rate")),
                    hourlyRate = it.getDouble(it.getColumnIndexOrThrow("hourly_rate")),
                    sampleSize = it.getInt(it.getColumnIndexOrThrow("sample_size"))
                )
            } else null
        }
    }

    /**
     * Insert a wage rate into the local cache.
     * Called by SyncEngine when backend data arrives.
     */
    fun insertWageRate(skill: String, experience: String, region: String, dailyRate: Double, hourlyRate: Double, sampleSize: Int) {
        val db = getDb()
        val values = ContentValues().apply {
            put("skill_type", skill)
            put("experience", experience)
            put("region", region)
            put("daily_rate", dailyRate)
            put("hourly_rate", hourlyRate)
            put("sample_size", sampleSize)
            put("recorded_at", System.currentTimeMillis())
            put("synced_at", System.currentTimeMillis())
        }
        db.insertWithOnConflict(TABLE_WAGES, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun skillDisplayName(skill: String): String = when (skill) {
        "mason" -> "Mjengo/Mason"
        "plumber" -> "Fundi maji/Plumber"
        "electrician" -> "Fundi umeme/Electrician"
        "carpenter" -> "Fundi seremala/Carpenter"
        "painter" -> "Painter"
        "welder" -> "Welder"
        "roofer" -> "Roofer"
        "tiler" -> "Tiler"
        "general_laborer" -> "Mtu wa kazi/Laborer"
        "fundi" -> "Fundi"
        "hair_braider" -> "Braider"
        "barber" -> "Kinyozi/Barber"
        "hair_stylist" -> "Hair Stylist"
        "mechanic" -> "Fundi gari/Mechanic"
        "phone_technician" -> "Fundi simu/Phone Tech"
        "electronics_tech" -> "Fundi TV/Electronics"
        else -> skill.replaceFirstChar { it.uppercase() }
    }

    private fun projectDisplayName(project: String): String = when (project.lowercase()) {
        "foundation" -> "msingi/foundation"
        "walling" -> "kuta/walling"
        "roofing" -> "paa/roofing"
        "plumbing" -> "maji/plumbing"
        "electrical" -> "umeme/electrical"
        "painting" -> "rang/painting"
        "tiling" -> "tiles/tiling"
        "fencing" -> "fence/fencing"
        "renovation" -> "ukarabati/renovation"
        else -> project
    }

    private fun formatPrice(price: Double): String {
        return if (price == price.toLong().toDouble()) "%,.0f".format(price) else "%,.1f".format(price)
    }

    private fun formatRate(rate: Double): String = "%,.0f".format(rate)

    // ──────────────────────────────────────────────
    // Data Classes
    // ──────────────────────────────────────────────

    private data class WageData(
        val dailyRate: Double,
        val hourlyRate: Double,
        val sampleSize: Int
    )
}
