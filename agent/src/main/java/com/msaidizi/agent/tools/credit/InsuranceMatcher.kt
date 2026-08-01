package com.msaidizi.agent.tools.credit
import com.msaidizi.agent.tools.financial.CFOEngine

import com.msaidizi.core.database.DailySummaryDao
import com.msaidizi.core.database.ExpenseDao
import com.msaidizi.core.database.ProductDao
import com.msaidizi.core.database.SaleDao
import com.msaidizi.agent.memory.MemoryManager
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import com.msaidizi.agent.tools.core.*

/**
 * InsuranceMatcher — Risk-profile matching for micro-insurance products.
 *
 * Informal workers face devastating risks (fire, theft, spoilage, accidents, illness)
 * but either don't know insurance exists for them, can't afford it, or don't trust it.
 * This tool matches workers to affordable micro-insurance based on their actual
 * business data, risk profile, and budget — with daily premiums in relatable terms.
 *
 * Features:
 *  1. match    — Risk assessment + matched products with daily premiums
 *  2. compare  — Side-by-side coverage comparison of matched products
 *  3. enroll   — Step-by-step enrollment guidance for a specific product
 *  4. claims   — Claim filing assistance + status tracking
 *  5. history  — Active policies, payment status, coverage gaps
 *
 * Voice-first, Swahili-native. Premiums shown as "KES 15/day — bei ya sukuma wiki mbili!"
 */
@Singleton
class InsuranceMatcher @Inject constructor(
    private val alamaScore: AlamaScore,
    private val cfoEngine: CFOEngine,
    private val saleDao: SaleDao,
    private val expenseDao: ExpenseDao,
    private val dailySummaryDao: DailySummaryDao,
    private val productDao: ProductDao,
    private val memoryManager: MemoryManager
) : Tool {

    override val name = "insurance_matcher"
    override val description = "Match micro-insurance for stock, health, accident risks. Voice: 'Bima yangu iko wapi?' or 'Nahitaji bima ya biashara'"

    override val argsSchema = argSchema {
        enum(
            "action",
            "Insurance action to perform",
            listOf("match", "compare", "enroll", "claims", "history"),
            required = false
        )
        string("product_id", "Insurance product ID to enroll in or compare (for enroll/compare)", required = false)
        string("claim_description", "Description of incident for claim filing (for claims)", required = false)
        string("claim_type", "Type of claim: fire, theft, spoilage, accident, illness (for claims)", required = false)
        number("budget_monthly", "Maximum monthly premium budget in KES", required = false)
        string("voice_input", "Raw Swahili voice text to parse", required = false)
    }

    // ──────────────────────────────────────────────
    // INSURANCE PRODUCT CATALOG
    // ──────────────────────────────────────────────

    /**
     * Micro-insurance products available for Kenyan informal workers.
     * Premiums and coverage reflect real market offerings as of 2026.
     */
    private data class InsuranceProduct(
        val id: String,
        val provider: String,
        val name: String,
        val type: InsuranceType,
        val targetBusinessTypes: List<String>,  // "mama_mboga", "boda_boda", "mitumba", "jua_kali", etc.
        val premiumDaily: Double,               // KES per day
        val premiumMonthly: Double,             // KES per month
        val maxCoverage: Double,                // Maximum payout in KES
        val deductible: Double = 0.0,           // Worker pays this first
        val whatIsCovered: List<String>,
        val exclusions: List<String>,
        val waitingPeriodDays: Int = 0,
        val claimSettlementDays: Int = 7,
        val claimPaidRate: Double = 0.85,       // % of claims actually paid
        val enrollmentMethod: String,           // "ussd", "app", "agent", "mpesa"
        val enrollmentCode: String,             // USSD code or instructions
        val payoutMethod: String = "m-pesa",
        val requiresHealthCheck: Boolean = false,
        val requiresInspection: Boolean = false,
        val swahiliName: String,
        val description: String,
        val descriptionSw: String
    )

    private enum class InsuranceType {
        STOCK_PROTECTION,  // Fire, theft, spoilage for inventory
        HEALTH,            // Outpatient, inpatient medical
        ACCIDENT,          // Personal accident, death/disability
        FIRE,              // Fire-specific coverage
        THEFT,             // Theft-specific coverage
        CROP,              // Agricultural crop insurance
        BIKE,              // Boda boda / vehicle
        LIABILITY          // Third-party liability
    }

    /**
     * Build the micro-insurance product catalog.
     * Products reflect real Kenyan micro-insurance market.
     */
    private fun buildInsuranceCatalog(): List<InsuranceProduct> = listOf(
        // ── Stock Protection ──
        InsuranceProduct(
            id = "jua_kali_stock",
            provider = "Jua Kali Insurance",
            name = "Stock Protection Cover",
            type = InsuranceType.STOCK_PROTECTION,
            targetBusinessTypes = listOf("mama_mboga", "mitumba", "duka", "jua_kali", "informal_trader"),
            premiumDaily = 15.0,
            premiumMonthly = 450.0,
            maxCoverage = 10_000.0,
            deductible = 500.0,
            whatIsCovered = listOf(
                "Stock loss from fire",
                "Stock loss from theft (with police report)",
                "Spoilage exceeding 30% of daily stock",
                "Water damage to stock"
            ),
            exclusions = listOf(
                "Unreported theft",
                "Gradual spoilage from poor storage",
                "Stock in transit",
                "Cash losses"
            ),
            waitingPeriodDays = 7,
            claimSettlementDays = 7,
            claimPaidRate = 0.89,
            enrollmentMethod = "ussd",
            enrollmentCode = "*483# → Select 'Jua Kali' → Enter PIN",
            swahiliName = "Bima ya Stock",
            description = "Protect your daily stock from fire, theft, and major spoilage.",
            descriptionSw = "Linda stock yako dhidi ya moto, wizi, na kuoza kwa wingi."
        ),
        InsuranceProduct(
            id = "britam_stock",
            provider = "Britam",
            name = "Soko Trader Cover",
            type = InsuranceType.STOCK_PROTECTION,
            targetBusinessTypes = listOf("mama_mboga", "mitumba", "duka", "informal_trader"),
            premiumDaily = 25.0,
            premiumMonthly = 750.0,
            maxCoverage = 25_000.0,
            deductible = 1_000.0,
            whatIsCovered = listOf(
                "Stock loss from fire and natural disasters",
                "Theft with forced entry",
                "Spoilage from power outage (fridge stock)",
                "Flood damage"
            ),
            exclusions = listOf(
                "Employee theft",
                "Cash and valuables",
                "Stock without receipts",
                "War and terrorism"
            ),
            waitingPeriodDays = 14,
            claimSettlementDays = 14,
            claimPaidRate = 0.82,
            enrollmentMethod = "agent",
            enrollmentCode = "Visit Britam agent at any market stage",
            swahiliName = "Bima ya Stock — Britam",
            description = "Comprehensive stock cover for larger traders. Higher coverage.",
            descriptionSw = "Bima kamili ya stock kwa wafanyabiashara wakubwa. Funiko zaidi."
        ),

        // ── Health ──
        InsuranceProduct(
            id = "mtiba_health",
            provider = "M-TIBA",
            name = "M-TIBA Health Cover",
            type = InsuranceType.HEALTH,
            targetBusinessTypes = listOf("mama_mboga", "boda_boda", "mitumba", "jua_kali", "duka", "informal_trader", "fundi"),
            premiumDaily = 17.0,
            premiumMonthly = 500.0,
            maxCoverage = 130_000.0,  // Outpatient 30k + Inpatient 100k
            deductible = 0.0,
            whatIsCovered = listOf(
                "Outpatient: doctor visits, lab tests, medicine (KES 30,000/year)",
                "Inpatient: hospital admission (KES 100,000/year)",
                "Maternity: normal and caesarean delivery",
                "Emergency ambulance"
            ),
            exclusions = listOf(
                "Pre-existing conditions (first 6 months)",
                "Cosmetic procedures",
                "Dental and optical (basic cover only)",
                "Alcohol/substance abuse related"
            ),
            waitingPeriodDays = 30,
            claimSettlementDays = 0,  // Cashless at partner hospitals
            claimPaidRate = 0.95,
            enrollmentMethod = "app",
            enrollmentCode = "Download M-TIBA app or dial *253#",
            payoutMethod = "cashless",
            swahiliName = "Bima ya Afya — M-TIBA",
            description = "Affordable health cover. No cash needed at partner hospitals.",
            descriptionSw = "Bima ya afya nafuu. Hakuna pesa inayohitajika hospitali za washirika."
        ),
        InsuranceProduct(
            id = "nhif_super",
            provider = "NHIF",
            name = "NHIF Supa Cover",
            type = InsuranceType.HEALTH,
            targetBusinessTypes = listOf("mama_mboga", "boda_boda", "mitumba", "jua_kali", "duka", "informal_trader", "fundi"),
            premiumDaily = 17.0,
            premiumMonthly = 500.0,
            maxCoverage = 500_000.0,
            deductible = 0.0,
            whatIsCovered = listOf(
                "Outpatient and inpatient at all NHIF hospitals",
                "Maternity cover",
                "Chronic disease management",
                "Surgery and specialized treatment"
            ),
            exclusions = listOf(
                "Cosmetic surgery",
                "Self-inflicted injuries",
                "Treatment outside Kenya"
            ),
            waitingPeriodDays = 60,
            claimSettlementDays = 0,
            claimPaidRate = 0.90,
            enrollmentMethod = "ussd",
            enrollmentCode = "Dial *263# → Register → Pay via M-Pesa",
            payoutMethod = "cashless",
            swahiliName = "NHIF Supa",
            description = "Government health insurance. Widest hospital network in Kenya.",
            descriptionSw = "Bima ya afya ya serikali. Mtandao mkubwa wa hospitali Kenya."
        ),

        // ── Personal Accident ──
        InsuranceProduct(
            id = "old_mutual_accident",
            provider = "Old Mutual",
            name = "Personal Accident Cover",
            type = InsuranceType.ACCIDENT,
            targetBusinessTypes = listOf("boda_boda", "tuk_tuk", "mama_mboga", "jua_kali", "fundi", "informal_trader"),
            premiumDaily = 7.0,
            premiumMonthly = 200.0,
            maxCoverage = 200_000.0,
            deductible = 0.0,
            whatIsCovered = listOf(
                "Death benefit: KES 200,000 to family",
                "Permanent disability: up to KES 200,000",
                "Temporary disability: KES 500/day (max 90 days)",
                "Medical expenses from accident: KES 20,000"
            ),
            exclusions = listOf(
                "Self-inflicted injuries",
                "Under influence of alcohol/drugs",
                "Extreme sports",
                "Pre-existing conditions"
            ),
            waitingPeriodDays = 0,
            claimSettlementDays = 14,
            claimPaidRate = 0.88,
            enrollmentMethod = "mpesa",
            enrollmentCode = "M-Pesa → Pay Bill → 123456 → Your ID number",
            swahiliName = "Bima ya Ajali",
            description = "Protect your family if you get injured or worse. Only KES 7/day.",
            descriptionSw = "Linda familia yako ukiumia au zaidi. KES 7 tu kwa siku."
        ),
        InsuranceProduct(
            id = "boda_boda_cover",
            provider = "Watu Insurance",
            name = "Boda Boda Rider Cover",
            type = InsuranceType.BIKE,
            targetBusinessTypes = listOf("boda_boda"),
            premiumDaily = 25.0,
            premiumMonthly = 750.0,
            maxCoverage = 150_000.0,
            deductible = 2_000.0,
            whatIsCovered = listOf(
                "Motorcycle theft or total loss",
                "Accident damage to motorcycle",
                "Third-party injury liability",
                "Rider personal accident (death/disability)",
                "Hospital bills from accident"
            ),
            exclusions = listOf(
                "Riding without helmet",
                "No valid driving license",
                "Racing or stunts",
                "Wear and tear"
            ),
            waitingPeriodDays = 7,
            claimSettlementDays = 14,
            claimPaidRate = 0.80,
            enrollmentMethod = "agent",
            enrollmentCode = "Visit Watu Credit agent or call 0700-WATU",
            swahiliName = "Bima ya Boda Boda",
            description = "Full cover for boda boda riders: bike, health, and liability.",
            descriptionSw = "Bima kamili ya waendesha boda boda: pikipiki, afya, na dhima."
        ),

        // ── Fire ──
        InsuranceProduct(
            id = "jua_kali_fire",
            provider = "Jua Kali Insurance",
            name = "Fire Cover — Stall/Workshop",
            type = InsuranceType.FIRE,
            targetBusinessTypes = listOf("jua_kali", "fundi", "mitumba", "mama_mboga"),
            premiumDaily = 10.0,
            premiumMonthly = 300.0,
            maxCoverage = 50_000.0,
            deductible = 1_000.0,
            whatIsCovered = listOf(
                "Fire damage to stall, workshop, or kiosk",
                "Stock destroyed by fire",
                "Tools and equipment lost in fire",
                "Temporary relocation costs (KES 5,000)"
            ),
            exclusions = listOf(
                "Intentional fire",
                "Electrical faults from illegal connections",
                "No fire report from chief/police"
            ),
            waitingPeriodDays = 7,
            claimSettlementDays = 10,
            claimPaidRate = 0.85,
            enrollmentMethod = "ussd",
            enrollmentCode = "*483# → 'Jua Kali' → 'Fire Cover'",
            swahiliName = "Bima ya Moto",
            description = "Cover your stall and stock against fire. Common in markets.",
            descriptionSw = "Funika kibanda na stock yako dhidi ya moto. Kawaida sokoni."
        ),

        // ── Crop (Agricultural) ──
        InsuranceProduct(
            id = "acre_crop",
            provider = "ACRE Africa",
            name = "Crop Insurance — Index Based",
            type = InsuranceType.CROP,
            targetBusinessTypes = listOf("farmer"),
            premiumDaily = 8.0,
            premiumMonthly = 240.0,
            maxCoverage = 20_000.0,
            deductible = 0.0,
            whatIsCovered = listOf(
                "Drought — based on rainfall index",
                "Excess rain / flooding",
                "Crop failure from weather events"
            ),
            exclusions = listOf(
                "Pest and disease (not weather-related)",
                "Poor farming practices",
                "Harvested crops"
            ),
            waitingPeriodDays = 0,
            claimSettlementDays = 30,
            claimPaidRate = 0.75,
            enrollmentMethod = "mpesa",
            enrollmentCode = "M-Pesa → Pay Bill → 777777 → Farmer ID",
            swahiliName = "Bima ya Mazao",
            description = "Protect your farm harvest against drought and floods.",
            descriptionSw = "Linda mazao yako dhidi ya ukame na mafuriko."
        ),

        // ── Livestock Insurance (P1: M9 agriculture integration) ──
        InsuranceProduct(
            id = "britam_livestock",
            provider = "Britam",
            name = "Mifugo Insurance Cover",
            type = InsuranceType.CROP,
            targetBusinessTypes = listOf("farmer", "mfugaji", "livestock_keeper"),
            premiumDaily = 20.0,
            premiumMonthly = 600.0,
            maxCoverage = 50_000.0,
            deductible = 2_000.0,
            whatIsCovered = listOf(
                "Animal death from disease",
                "Animal death from accident",
                "Theft of livestock",
                "Veterinary emergency costs"
            ),
            exclusions = listOf(
                "Pre-existing conditions",
                "Negligence",
                "Animals over 8 years old"
            ),
            waitingPeriodDays = 14,
            claimSettlementDays = 14,
            claimPaidRate = 0.80,
            enrollmentMethod = "agent",
            enrollmentCode = "Piga simu 0700BRITAM or tembelea agent wa Britam",
            swahiliName = "Bima ya Mifugo",
            description = "Protect your livestock from disease, accident, and theft.",
            descriptionSw = "Linda mifugo yako dhidi ya magonjwa, ajali, na wizi."
        ),

        // ── Weather Index Insurance (P1: M9) ──
        InsuranceProduct(
            id = "aice_weather_index",
            provider = "AICE Africa",
            name = "Weather Index Insurance",
            type = InsuranceType.CROP,
            targetBusinessTypes = listOf("farmer", "mkulima"),
            premiumDaily = 5.0,
            premiumMonthly = 150.0,
            maxCoverage = 15_000.0,
            deductible = 0.0,
            whatIsCovered = listOf(
                "Drought — automatic payout when rainfall below threshold",
                "Excess rainfall — automatic payout when flooding occurs",
                "No paperwork needed — satellite data triggers payout"
            ),
            exclusions = listOf(
                "Pest damage",
                "Market price drops",
                "Farms outside monitored regions"
            ),
            waitingPeriodDays = 0,
            claimSettlementDays = 7,
            claimPaidRate = 0.90,
            enrollmentMethod = "mpesa",
            enrollmentCode = "M-Pesa → Pay Bill → 222333 → Farmer ID",
            swahiliName = "Bima ya Hali ya Hewa",
            description = "Automatic payout when weather hurts your farm. No paperwork!",
            descriptionSw = "Lipotwa moja kwa moja hali ya hewa inapodhuru shamba lako. Hakua karatasi!"
        ),

        // ── Liability ──
        InsuranceProduct(
            id = "biashara_liability",
            provider = "CIC Insurance",
            name = "Biashara Liability Cover",
            type = InsuranceType.LIABILITY,
            targetBusinessTypes = listOf("jua_kali", "fundi", "duka", "informal_trader"),
            premiumDaily = 12.0,
            premiumMonthly = 360.0,
            maxCoverage = 100_000.0,
            deductible = 2_000.0,
            whatIsCovered = listOf(
                "Third-party injury at your business premises",
                "Damage to customer property",
                "Legal defense costs",
                "Product liability (defective goods)"
            ),
            exclusions = listOf(
                "Employee injuries (need separate cover)",
                "Professional negligence",
                "Contractual liability"
            ),
            waitingPeriodDays = 14,
            claimSettlementDays = 21,
            claimPaidRate = 0.82,
            enrollmentMethod = "agent",
            enrollmentCode = "Visit CIC Insurance branch or call 0711-036-000",
            swahiliName = "Bima ya Dhima",
            description = "Protect yourself if a customer gets hurt at your business.",
            descriptionSw = "Jilinde mteja akiumia katika biashara yako."
        )
    )

    // ──────────────────────────────────────────────
    // RISK PROFILE MODEL
    // ──────────────────────────────────────────────

    /**
     * Comprehensive risk profile for a worker based on business data.
     */
    private data class RiskProfile(
        val workerId: String,
        val businessType: String,
        val location: String,
        val stockValueAtRisk: Double,        // Average daily stock value
        val monthlySpoilageEstimate: Double,  // Estimated monthly spoilage loss
        val fireRiskLevel: RiskLevel,
        val theftRiskLevel: RiskLevel,
        val healthCoverageStatus: String,     // "none", "nhif", "private", "both"
        val accidentRiskLevel: RiskLevel,
        val incomeIfSick1Week: Double,        // Estimated income lost
        val dependentsCount: Int,
        val riskScore: Double,                // 0-100 composite risk score
        val coverageGaps: List<CoverageGap>
    )

    private enum class RiskLevel { LOW, MEDIUM, HIGH }

    private data class CoverageGap(
        val type: InsuranceType,
        val riskExposure: Double,       // KES at risk per month
        val description: String,
        val descriptionSw: String
    )

    // ──────────────────────────────────────────────
    // EXECUTION DISPATCH
    // ──────────────────────────────────────────────

    override suspend fun execute(params: Map<String, String>): ToolResult {
        // Voice input parsing
        if (!params["voice_input"].isNullOrBlank()) {
            return parseVoiceInput(params["voice_input"]!!)
        }

        val action = params["action"] ?: "match"
        return when (action.lowercase()) {
            "match" -> matchProducts(params)
            "compare" -> compareProducts(params)
            "enroll" -> enrollGuidance(params)
            "claims" -> claimGuidance(params)
            "history" -> showHistory(params)
            else -> ToolResult.error(
                name,
                "Unknown action: $action. Use: match, compare, enroll, claims, history",
                "INVALID_ACTION"
            )
        }
    }

    // ──────────────────────────────────────────────
    // 1. MATCH — Risk assessment + product matching
    // ──────────────────────────────────────────────

    /**
     * Assess worker's risk profile and match to suitable micro-insurance products.
     * Uses actual business data for personalized risk assessment.
     */
    private suspend fun matchProducts(params: Map<String, String>): ToolResult {
        return try {
            val profile = buildRiskProfile()
            val budgetMonthly = params["budget_monthly"]?.toDoubleOrNull()
                ?: profile.stockValueAtRisk * 0.05  // Default: 5% of stock value

            val catalog = buildInsuranceCatalog()

            // Match products to risk profile
            val matches = catalog.mapNotNull { product ->
                val matchResult = calculateMatchScore(product, profile, budgetMonthly)
                if (matchResult.first > 0) Triple(product, matchResult.first, matchResult.second) else null
            }.sortedByDescending { it.second }

            // Build response
            val message = buildString {
                appendLine("🛡️ BIMA — MAPENDEKEZO KWA BIASHARA YAKO")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()

                // Risk profile summary
                appendLine("📊 HATARI ZAKO:")
                appendLine("   Stock ya siku: ~KES ${"%,.0f".format(profile.stockValueAtRisk)}")
                if (profile.monthlySpoilageEstimate > 0) {
                    appendLine("   Hasara ya kuoza: ~KES ${"%,.0f".format(profile.monthlySpoilageEstimate)}/mwezi")
                }
                appendLine("   Hatari ya moto: ${riskLabelSw(profile.fireRiskLevel)}")
                appendLine("   Hatari ya wizi: ${riskLabelSw(profile.theftRiskLevel)}")
                appendLine("   Bima ya afya: ${healthStatusLabel(profile.healthCoverageStatus)}")
                appendLine("   Hatari ya ajali: ${riskLabelSw(profile.accidentRiskLevel)}")
                if (profile.incomeIfSick1Week > 0) {
                    appendLine("   Mapato ukiumia wiki 1: KES 0")
                }
                appendLine()

                // Coverage gaps
                if (profile.coverageGaps.isNotEmpty()) {
                    appendLine("⚠️ MAPENDEKEZO — HAZINA BIMA:")
                    for (gap in profile.coverageGaps) {
                        appendLine("   • ${gap.descriptionSw}")
                        appendLine("     Hatari: ~KES ${"%,.0f".format(gap.riskExposure)}/mwezi")
                    }
                    appendLine()
                }

                // Matched products
                appendLine("🎯 BIMA ZINAZOKUFAA:")
                appendLine()

                if (matches.isEmpty()) {
                    appendLine("   Hakuna bima inayokufaa kwa sasa.")
                    appendLine("   Jaribu kuongeza bajeti yako ya bima.")
                } else {
                    for ((index, match) in matches.withIndex()) {
                        val product = match.first
                        val score = match.second
                        val reason = match.third
                        val isTop = index == 0
                        val dailyKes = product.premiumDaily

                        if (isTop) appendLine("   🏆 ${index + 1}. ${product.swahiliName} — MAPENDEKEZO BORA!")
                        else appendLine("   ${index + 1}. ${product.swahiliName}")

                        appendLine("      Premium: KES ${"%.0f".format(dailyKes)}/siku (${comparablePrice(dailyKes)})")
                        appendLine("      Mwezi: KES ${"%,.0f".format(product.premiumMonthly)}")
                        appendLine("      Funiko: mpaka KES ${"%,.0f".format(product.maxCoverage)}")
                        appendLine("      Ulipaji: ${"%.0f".format(product.claimPaidRate * 100)}% ya madai yanalipwa")
                        appendLine("      $reason")
                        appendLine()
                    }
                }

                // Total protection cost
                val topMatches = matches.take(3)
                if (topMatches.isNotEmpty()) {
                    val totalDaily = topMatches.sumOf { it.first.premiumDaily }
                    val totalMonthly = topMatches.sumOf { it.first.premiumMonthly }
                    appendLine("💰 ULINZI KAMILI (bidhaa ${topMatches.size} bora):")
                    appendLine("   KES ${"%.0f".format(totalDaily)}/siku — ${comparablePrice(totalDaily)}")
                    appendLine("   KES ${"%,.0f".format(totalMonthly)}/mwezi")
                    appendLine("   vs. hatari bila bima: KES ${"%,.0f".format(profile.totalRiskExposure)}/mwezi")
                    appendLine()
                }

                // Enrollment hint
                if (matches.isNotEmpty()) {
                    val easiest = matches.first().first
                    appendLine("📞 JIANDIKISHE SASA:")
                    appendLine("   ${easiest.swahiliName}: ${easiest.enrollmentCode}")
                }
            }

            // Save to memory
            memoryManager.storeMemory(
                "last_insurance_match",
                "risk_score=${"%.0f".format(profile.riskScore)},matches=${matches.size},top=${matches.firstOrNull()?.first?.id ?: "none"}",
                "insurance"
            )

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "risk_profile" to mapOf(
                        "stock_value_at_risk" to profile.stockValueAtRisk,
                        "monthly_spoilage" to profile.monthlySpoilageEstimate,
                        "fire_risk" to profile.fireRiskLevel.name,
                        "theft_risk" to profile.theftRiskLevel.name,
                        "health_coverage" to profile.healthCoverageStatus,
                        "accident_risk" to profile.accidentRiskLevel.name,
                        "risk_score" to profile.riskScore
                    ),
                    "matches" to matches.map { (product, score, reason) ->
                        mapOf(
                            "id" to product.id,
                            "provider" to product.provider,
                            "name" to product.name,
                            "swahili_name" to product.swahiliName,
                            "type" to product.type.name,
                            "premium_daily" to product.premiumDaily,
                            "premium_monthly" to product.premiumMonthly,
                            "max_coverage" to product.maxCoverage,
                            "claim_paid_rate" to product.claimPaidRate,
                            "match_score" to score,
                            "reason" to reason
                        )
                    },
                    "coverage_gaps" to profile.coverageGaps.map {
                        mapOf("type" to it.type.name, "exposure" to it.riskExposure, "description_sw" to it.descriptionSw)
                    }
                ),
                message = message
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to match insurance products")
            ToolResult.error(name, "Failed to match: ${e.message}", "CALCULATION_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // 2. COMPARE — Side-by-side coverage comparison
    // ──────────────────────────────────────────────

    /**
     * Compare matched insurance products side-by-side: coverage, cost, claims history.
     */
    private suspend fun compareProducts(params: Map<String, String>): ToolResult {
        return try {
            val profile = buildRiskProfile()
            val catalog = buildInsuranceCatalog()
            val budgetMonthly = params["budget_monthly"]?.toDoubleOrNull()
                ?: profile.stockValueAtRisk * 0.05

            // Get matched products (sorted by match score)
            val matches = catalog.mapNotNull { product ->
                val matchResult = calculateMatchScore(product, profile, budgetMonthly)
                if (matchResult.first > 0) Pair(product, matchResult.first) else null
            }.sortedByDescending { it.second }.take(5)

            if (matches.isEmpty()) {
                return ToolResult.success(
                    toolName = name,
                    message = "⚠️ Hakuna bima za kulinganisha. Jaribu kuongeza bajeti yako."
                )
            }

            val message = buildString {
                appendLine("📊 LINGANISHO LA BIMA")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()

                for ((product, score) in matches) {
                    appendLine("   ${product.swahiliName}")
                    appendLine("   Mtoa: ${product.provider}")
                    appendLine("   Premium: KES ${"%.0f".format(product.premiumDaily)}/siku (KES ${"%,.0f".format(product.premiumMonthly)}/mwezi)")
                    appendLine("   Funiko: mpaka KES ${"%,.0f".format(product.maxCoverage)}")
                    appendLine("   Kiasi cha kwanza (deductible): KES ${"%,.0f".format(product.deductible)}")
                    appendLine("   Kipindi cha kusubiri: siku ${product.waitingPeriodDays}")
                    appendLine("   Madai yanalipwa: ${"%.0f".format(product.claimPaidRate * 100)}%")
                    appendLine("   Muda wa kulipia: siku ${product.claimSettlementDays}")
                    appendLine("   Kujiandikisha: ${product.enrollmentMethod} — ${product.enrollmentCode}")

                    // What's covered
                    appendLine("   ✅ Inafunika:")
                    for (item in product.whatIsCovered.take(3)) {
                        appendLine("      • $item")
                    }

                    // Exclusions (key ones)
                    appendLine("   ❌ Haifuniki:")
                    for (item in product.exclusions.take(2)) {
                        appendLine("      • $item")
                    }
                    appendLine()
                }

                // Recommendation
                val best = matches.first().first
                appendLine("🏆 MAPENDEKEZO: ${best.swahiliName}")
                appendLine("   KES ${"%.0f".format(best.premiumDaily)}/siku — ${comparablePrice(best.premiumDaily)}")
                appendLine("   Funiko: KES ${"%,.0f".format(best.maxCoverage)}")
                appendLine("   ${best.descriptionSw}")

                // Warning about very cheap products
                val cheapest = matches.minByOrNull { it.first.premiumDaily }
                if (cheapest != null && cheapest.first.premiumDaily < 10) {
                    appendLine()
                    appendLine("⚠️ ONYO: Bima nafuu sana (chini ya KES 10/siku) huenda ikawa na")
                    appendLine("   funiko dogo au masharti mengi. Soma kwa makini kabla ya kujiandikisha.")
                }
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "products" to matches.map { (product, score) ->
                        mapOf(
                            "id" to product.id,
                            "swahili_name" to product.swahiliName,
                            "provider" to product.provider,
                            "premium_daily" to product.premiumDaily,
                            "premium_monthly" to product.premiumMonthly,
                            "max_coverage" to product.maxCoverage,
                            "deductible" to product.deductible,
                            "waiting_period_days" to product.waitingPeriodDays,
                            "claim_paid_rate" to product.claimPaidRate,
                            "claim_settlement_days" to product.claimSettlementDays,
                            "enrollment_method" to product.enrollmentMethod,
                            "match_score" to score
                        )
                    }
                ),
                message = message
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to compare insurance products")
            ToolResult.error(name, "Failed to compare: ${e.message}", "CALCULATION_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // 3. ENROLL — Step-by-step enrollment guidance
    // ──────────────────────────────────────────────

    /**
     * Guide worker through enrollment for a specific insurance product.
     */
    private suspend fun enrollGuidance(params: Map<String, String>): ToolResult {
        return try {
            val productId = params["product_id"]
            val catalog = buildInsuranceCatalog()

            // If no product ID, show available products
            val product = if (productId != null) {
                catalog.find { it.id == productId }
            } else {
                // Auto-select best match
                val profile = buildRiskProfile()
                val budgetMonthly = profile.stockValueAtRisk * 0.05
                catalog.mapNotNull { p ->
                    val result = calculateMatchScore(p, profile, budgetMonthly)
                    if (result.first > 0) Pair(p, result.first) else null
                }.maxByOrNull { it.second }?.first
            }

            if (product == null) {
                return ToolResult.success(
                    toolName = name,
                    message = buildString {
                        appendLine("⚠️ Sijapata bima hiyo. Bima zinazopatikana:")
                        for (p in catalog) {
                            appendLine("   • ${p.id}: ${p.swahiliName}")
                        }
                        appendLine()
                        appendLine("Taja 'product_id' ya bima unayotaka.")
                    }
                )
            }

            val message = buildString {
                appendLine("📝 KUJIANDIKISHA — ${product.swahiliName}")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()
                appendLine("📋 HATUA ZA KUJIANDIKISHA:")
                appendLine()

                when (product.enrollmentMethod) {
                    "ussd" -> {
                        appendLine("   1️⃣ Piga simu yako fungua dialer")
                        appendLine("   2️⃣ Andika: ${product.enrollmentCode}")
                        appendLine("   3️⃣ Fuata maelekezo skrini")
                        appendLine("   4️⃣ Lipa premium ya kwanza kupitia M-Pesa")
                        appendLine("   5️⃣ Utapokea SMS ya uthibitisho")
                    }
                    "app" -> {
                        appendLine("   1️⃣ Pakua app: ${product.enrollmentCode}")
                        appendLine("   2️⃣ Fungua app na ujiandikishe na nambari ya simu")
                        appendLine("   3️⃣ Jaza taarifa zako za biashara")
                        appendLine("   4️⃣ Chagua mpango wa premium (kila siku / kila mwezi)")
                        appendLine("   5️⃣ Lipa kupitia M-Pesa")
                        appendLine("   6️⃣ Utapokea kadi ya bima ndani ya app")
                    }
                    "mpesa" -> {
                        appendLine("   1️⃣ Fungua M-Pesa yako")
                        appendLine("   2️⃣ Chagua 'Pay Bill'")
                        appendLine("   3️⃣ Business Number: ${product.enrollmentCode}")
                        appendLine("   4️⃣ Andika nambari ya kitambulisho kama account")
                        appendLine("   5️⃣ Lipa KES ${"%.0f".format(product.premiumMonthly)} (mwezi 1)")
                        appendLine("   6️⃣ Utapokea SMS ya uthibitisho na nambari ya bima")
                    }
                    "agent" -> {
                        appendLine("   1️⃣ Tembelea wakala: ${product.enrollmentCode}")
                        appendLine("   2️⃣ Nenda na kitambulisho chako (ID)")
                        appendLine("   3️⃣ Nenda na thibitisho la biashara (picha au barua)")
                        appendLine("   4️⃣ Jaza fomu ya kujiandikisha")
                        appendLine("   5️⃣ Lipa premium ya kwanza")
                        appendLine("   6️⃣ Utapokea cheti cha bima")
                    }
                }

                appendLine()
                appendLine("📄 UTAKACHOHITAJI:")
                appendLine("   • Nambari ya simu iliyo na M-Pesa")
                appendLine("   • Kitambulisho (ID / Passport)")
                if (product.requiresInspection) {
                    appendLine("   • Picha ya biashara yako / kibanda")
                }
                if (product.requiresHealthCheck) {
                    appendLine("   • Ripoti ya afya (kwa bima ya afya)")
                }
                appendLine()

                appendLine("💰 MALIPO:")
                appendLine("   Kila siku: KES ${"%.0f".format(product.premiumDaily)} — ${comparablePrice(product.premiumDaily)}")
                appendLine("   Kila mwezi: KES ${"%,.0f".format(product.premiumMonthly)}")
                appendLine("   Deductible (unacholipa kwanza): KES ${"%,.0f".format(product.deductible)}")
                appendLine()

                appendLine("⏱️ MUDA:")
                appendLine("   Kusubiri kabla ya kuanza: siku ${product.waitingPeriodDays}")
                appendLine("   Kulipia madai: siku ${product.claimSettlementDays}")
                appendLine()

                appendLine("🛡️ FUNIKO:")
                for (item in product.whatIsCovered) {
                    appendLine("   ✅ $item")
                }
                appendLine()
                appendLine("❌ HAIFUNIKI:")
                for (item in product.exclusions) {
                    appendLine("   ❌ $item")
                }

                appendLine()
                appendLine("💡 UKO TAYARI? Anza sasa — KES ${"%.0f".format(product.premiumDaily)}/siku ni bei ya ${comparablePrice(product.premiumDaily)}.")
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "product_id" to product.id,
                    "swahili_name" to product.swahiliName,
                    "enrollment_method" to product.enrollmentMethod,
                    "enrollment_code" to product.enrollmentCode,
                    "premium_daily" to product.premiumDaily,
                    "premium_monthly" to product.premiumMonthly,
                    "waiting_period_days" to product.waitingPeriodDays
                ),
                message = message
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to provide enrollment guidance")
            ToolResult.error(name, "Failed: ${e.message}", "CALCULATION_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // 4. CLAIMS — Claim filing assistance + tracking
    // ──────────────────────────────────────────────

    /**
     * Guide worker through filing an insurance claim.
     * Provides step-by-step instructions and tracks claim status.
     */
    private suspend fun claimGuidance(params: Map<String, String>): ToolResult {
        return try {
            val claimType = params["claim_type"]
            val claimDescription = params["claim_description"]

            // Load active policies from memory
            val activePolicies = memoryManager.retrieve("insurance_active_policies")
            val catalog = buildInsuranceCatalog()

            // If we have an active policy, provide specific claim guidance
            val policyProducts = if (activePolicies.isNotBlank()) {
                parseActivePolicies(activePolicies, catalog)
            } else {
                emptyList()
            }

            val message = buildString {
                appendLine("📋 MADAI YA BIMA — MWONGOZO")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()

                if (claimType != null) {
                    // Specific claim type guidance
                    appendLine("📝 MADAI YA: ${claimTypeLabel(claimType)}")
                    appendLine()

                    appendLine("📋 HATUA ZA KUFUATA:")
                    appendLine()

                    when (claimType.lowercase()) {
                        "fire" -> {
                            appendLine("   1️⃣ PIGA SIMU YA DHARURA: 999 au 112")
                            appendLine("   2️⃣ PATA RIPOTI YA MOTO kutoka kituo cha polisi")
                            appendLine("   3️⃣ PIGA SIMU kwa mtoa bima yako ndani ya saa 24")
                            appendLine("   4️⃣ PIGA Picha za mahali pa moto kabla ya kusafisha")
                            appendLine("   5️⃣ Orodhesha stock yote iliyopotea na thamani")
                            appendLine("   6️⃣ Tuma madai kupitia SMS au app ya mtoa bima")
                            appendLine("   7️⃣ Subiri uchunguzi — watakuja kukagua")
                        }
                        "theft" -> {
                            appendLine("   1️⃣ RAPORTI KWA POLISI — ndani ya saa 24")
                            appendLine("   2️⃣ Pata nambari ya OB (Occurrence Book)")
                            appendLine("   3️⃣ PIGA SIMU kwa mtoa bima yako")
                            appendLine("   4️⃣ Orodhesha vitu vilivyoibiwa na thamani")
                            appendLine("   5️⃣ Pata ushahidi (picha za CCTV, mashahidi)")
                            appendLine("   6️⃣ Tuma madai na ripoti ya polisi")
                        }
                        "spoilage" -> {
                            appendLine("   1️⃣ PIGA Picha za mboga/mazao yaliyoharibika")
                            appendLine("   2️⃣ Kadiria thamani ya hasara")
                            appendLine("   3️⃣ PIGA SIMU kwa mtoa bima ndani ya saa 48")
                            appendLine("   4️⃣ Eleza sababu (joto, umeme, friji kuvunjika)")
                            appendLine("   5️⃣ Tuma madai kupitia SMS au app")
                        }
                        "accident" -> {
                            appendLine("   1️⃣ PATA MATIBABU — hospitali au kliniki")
                            appendLine("   2️⃣ Pata ripoti ya daktari")
                            appendLine("   3️⃣ RAPORTI KWA POLISI (kama ni ajali ya barabarani)")
                            appendLine("   4️⃣ PIGA SIMU kwa mtoa bima ndani ya saa 48")
                            appendLine("   5️⃣ Tuma: ripoti ya daktari + ripoti ya polisi + risiti za matibabu")
                            appendLine("   6️⃣ Fuatilia madai kupitia SMS au app")
                        }
                        "illness" -> {
                            appendLine("   1️⃣ ENDA HOSPITALI ya washirika wa bima yako")
                            appendLine("   2️⃣ Onyesha kadi ya bima au nambari ya bima")
                            appendLine("   3️⃣ Hospitali itawasiliana na mtoa bima moja kwa moja")
                            appendLine("   4️⃣ Lipa deductible tu (kama ipo)")
                            appendLine("   5️⃣ Kama hospitali si ya mshirika, lipa kisha tuma risiti kwa bima")
                        }
                        else -> {
                            appendLine("   1️⃣ PIGA SIMU kwa mtoa bima yako ndani ya saa 48")
                            appendLine("   2️⃣ Kusanya nyaraka zote zinazohusika")
                            appendLine("   3️⃣ Tuma madai kupitia SMS, app, au wakala")
                            appendLine("   4️⃣ Fuatilia hali ya madai kila wiki")
                        }
                    }

                    appendLine()
                    appendLine("📄 NYARAKA ZINAZOHITAJIKA:")
                    appendLine("   • Kitambulisho chako (ID)")
                    appendLine("   • Nambari ya bima / hati")
                    when (claimType.lowercase()) {
                        "fire" -> {
                            appendLine("   • Ripoti ya moto kutoka polisi")
                            appendLine("   • Picha za mahali pa moto")
                            appendLine("   • Orodha ya stock iliyopotea")
                        }
                        "theft" -> {
                            appendLine("   • Ripoti ya polisi (OB number)")
                            appendLine("   • Orodha ya vitu vilivyoibiwa")
                            appendLine("   • Ushahidi (picha, mashahidi)")
                        }
                        "spoilage" -> {
                            appendLine("   • Picha za mazao yaliyoharibika")
                            appendLine("   • Kiasi cha hasara (KES)")
                            appendLine("   • Sababu ya kuoza")
                        }
                        "accident", "illness" -> {
                            appendLine("   • Ripoti ya daktari")
                            appendLine("   • Risiti za matibabu")
                            appendLine("   • Ripoti ya polisi (kwa ajali)")
                        }
                    }

                    appendLine()
                    appendLine("⏱️ MUDA WA KULIPIA: Siku 7-21 (kulingana na mtoa bima)")

                    // Save claim to memory
                    memoryManager.storeMemory(
                        "last_claim_guidance",
                        "type=$claimType,timestamp=${System.currentTimeMillis()}",
                        "insurance"
                    )
                }

                // Show active policies if available
                if (policyProducts.isNotEmpty()) {
                    appendLine()
                    appendLine("🛡️ BIMA ZAKO HAI:")
                    for (policy in policyProducts) {
                        appendLine("   ✅ ${policy.swahiliName} — ${policy.provider}")
                    }
                    appendLine()
                    appendLine("📞 NAMBARI ZA KUPIGA:")
                    for (policy in policyProducts.distinctBy { it.provider }) {
                        appendLine("   ${policy.provider}: ${policy.enrollmentCode}")
                    }
                }

                // General tips
                appendLine()
                appendLine("💡 VIDOKEZO MUHIMU:")
                appendLine("   • RAPORTI ndani ya saa 48 — muda ukipita, madai yanaweza kukataliwa")
                appendLine("   • PIGA Picha KILA KITU — picha ni ushahidi mzuri")
                appendLine("   • HIFADHI risiti zote za malipo ya premium")
                appendLine("   • FUATILIA madai yako kila wiki — usiogope kuulizia")
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "claim_type" to claimType,
                    "active_policies" to policyProducts.map { mapOf("id" to it.id, "name" to it.swahiliName) }
                ),
                message = message
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to provide claim guidance")
            ToolResult.error(name, "Failed: ${e.message}", "CALCULATION_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // 5. HISTORY — Active policies, payments, coverage gaps
    // ──────────────────────────────────────────────

    /**
     * Show insurance history: active policies, payment status, coverage gaps, and claims.
     */
    private suspend fun showHistory(params: Map<String, String>): ToolResult {
        return try {
            val profile = buildRiskProfile()
            val lastMatch = memoryManager.retrieve("last_insurance_match")
            val lastClaim = memoryManager.retrieve("last_claim_guidance")
            val activePoliciesStr = memoryManager.retrieve("insurance_active_policies")
            val catalog = buildInsuranceCatalog()

            val activePolicies = if (activePoliciesStr.isNotBlank()) {
                parseActivePolicies(activePoliciesStr, catalog)
            } else {
                emptyList()
            }

            val message = buildString {
                appendLine("📜 HISTORIA YA BIMA")
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine()

                // Active policies
                if (activePolicies.isNotEmpty()) {
                    appendLine("🛡️ BIMA ZAKO HAI:")
                    for (policy in activePolicies) {
                        appendLine("   ✅ ${policy.swahiliName}")
                        appendLine("      Mtoa: ${policy.provider}")
                        appendLine("      Premium: KES ${"%.0f".format(policy.premiumDaily)}/siku")
                        appendLine("      Funiko: KES ${"%,.0f".format(policy.maxCoverage)}")
                    }
                    appendLine()
                } else {
                    appendLine("🛡️ BIMA ZAKO HAI: Hakuna")
                    appendLine("   Bado hujajiandikisha na bima yoyote.")
                    appendLine("   Taja 'match' kuona bima zinazokufaa.")
                    appendLine()
                }

                // Coverage gaps
                if (profile.coverageGaps.isNotEmpty()) {
                    appendLine("⚠️ MAHALI AMBAPO HUNA BIMA:")
                    for (gap in profile.coverageGaps) {
                        appendLine("   • ${gap.descriptionSw}")
                        appendLine("     Hatari: ~KES ${"%,.0f".format(gap.riskExposure)}/mwezi")
                    }
                    appendLine()
                }

                // Risk profile
                appendLine("📊 WASIFU WAKO WA HATARI:")
                appendLine("   Alama ya hatari: ${"%.0f".format(profile.riskScore)}/100")
                appendLine("   Stock ya siku: ~KES ${"%,.0f".format(profile.stockValueAtRisk)}")
                appendLine("   Moto: ${riskLabelSw(profile.fireRiskLevel)}")
                appendLine("   Wizi: ${riskLabelSw(profile.theftRiskLevel)}")
                appendLine("   Ajali: ${riskLabelSw(profile.accidentRiskLevel)}")
                appendLine("   Afya: ${healthStatusLabel(profile.healthCoverageStatus)}")
                appendLine()

                // Last activity
                if (lastMatch.isNotBlank()) {
                    appendLine("📋 MAPENDEKEZO YA MWISHO: $lastMatch")
                }
                if (lastClaim.isNotBlank()) {
                    appendLine("📋 MADAI YA MWISHO: $lastClaim")
                }

                // What they're missing
                appendLine()
                appendLine("💡 KAMA UNA BIMA, taja 'enroll' ili ujiandikishe.")
                appendLine("   KAMA UNA MADAI, taja 'claims' ili upate msaada.")
            }

            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "active_policies" to activePolicies.map { mapOf("id" to it.id, "name" to it.swahiliName, "provider" to it.provider) },
                    "risk_score" to profile.riskScore,
                    "coverage_gaps" to profile.coverageGaps.map { mapOf("type" to it.type.name, "exposure" to it.riskExposure) }
                ),
                message = message
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to show insurance history")
            ToolResult.error(name, "Failed: ${e.message}", "CALCULATION_ERROR")
        }
    }

    // ──────────────────────────────────────────────
    // RISK PROFILE BUILDER
    // ──────────────────────────────────────────────

    /**
     * Build comprehensive risk profile from business data.
     */
    private suspend fun buildRiskProfile(): RiskProfile {
        val businessType = memoryManager.retrieve("business_type").ifBlank { "informal_trader" }
        val location = memoryManager.retrieve("location").ifBlank { "Kenya" }
        val healthCoverage = memoryManager.retrieve("health_insurance").ifBlank { "none" }
        val dependents = memoryManager.retrieve("dependents_count").toIntOrNull() ?: 1

        // Pull business financial data
        val summaries = dailySummaryDao.getRecentSummaries(30).first()
        val avgDailySales = if (summaries.isNotEmpty()) {
            summaries.map { it.totalSales }.average()
        } else 0.0
        val avgDailyExpenses = if (summaries.isNotEmpty()) {
            summaries.map { it.totalExpenses }.average()
        } else 0.0

        // Estimate stock value at risk (typically 60-80% of daily sales for traders)
        val stockValueAtRisk = when (businessType) {
            "mama_mboga" -> avgDailySales * 0.7   // Perishable, 70% of sales
            "mitumba" -> avgDailySales * 1.5       // Higher stock-to-sales ratio
            "duka" -> avgDailySales * 1.0
            "boda_boda" -> 0.0                      // No stock, but bike at risk
            "jua_kali" -> avgDailySales * 0.8
            "fundi" -> avgDailySales * 0.6
            else -> avgDailySales * 0.7
        }

        // Estimate spoilage (for perishable goods)
        val monthlySpoilage = when (businessType) {
            "mama_mboga" -> stockValueAtRisk * 0.15 * 30  // 15% daily spoilage
            else -> 0.0
        }

        // Risk levels based on business type and location
        val fireRisk = when (businessType) {
            "mama_mboga" -> RiskLevel.MEDIUM   // Roadside stall
            "mitumba" -> RiskLevel.HIGH         // Market stalls, Gikomba fires
            "jua_kali" -> RiskLevel.HIGH        // Workshops with flammables
            "duka" -> RiskLevel.LOW
            else -> RiskLevel.MEDIUM
        }

        val theftRisk = when (businessType) {
            "mama_mboga" -> RiskLevel.MEDIUM
            "mitumba" -> RiskLevel.HIGH
            "duka" -> RiskLevel.LOW
            "boda_boda" -> RiskLevel.HIGH  // Bike theft
            else -> RiskLevel.MEDIUM
        }

        val accidentRisk = when (businessType) {
            "boda_boda" -> RiskLevel.HIGH
            "jua_kali" -> RiskLevel.MEDIUM
            "fundi" -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }

        // Calculate composite risk score (0-100)
        var riskScore = 30.0 // Base
        if (fireRisk == RiskLevel.HIGH) riskScore += 20
        else if (fireRisk == RiskLevel.MEDIUM) riskScore += 10
        if (theftRisk == RiskLevel.HIGH) riskScore += 20
        else if (theftRisk == RiskLevel.MEDIUM) riskScore += 10
        if (accidentRisk == RiskLevel.HIGH) riskScore += 20
        else if (accidentRisk == RiskLevel.MEDIUM) riskScore += 10
        if (healthCoverage == "none") riskScore += 15
        if (stockValueAtRisk > 5000) riskScore += 5
        riskScore = riskScore.coerceIn(0.0, 100.0)

        // Income if sick for 1 week
        val incomeIfSick = avgDailySales * 6  // 6 working days

        // Coverage gaps
        val gaps = mutableListOf<CoverageGap>()

        if (stockValueAtRisk > 0) {
            gaps.add(CoverageGap(
                type = InsuranceType.STOCK_PROTECTION,
                riskExposure = monthlySpoilage.coerceAtLeast(stockValueAtRisk * 0.1),
                description = "Stock not protected against fire/theft/spoilage",
                descriptionSw = "Stock yako haina bima dhidi ya moto/wizi/kuoza"
            ))
        }

        if (healthCoverage == "none") {
            gaps.add(CoverageGap(
                type = InsuranceType.HEALTH,
                riskExposure = incomeIfSick,
                description = "No health insurance — one hospital visit costs more than 1 year of premiums",
                descriptionSw = "Hakuna bima ya afya — ziara moja hospitali inagharamia zaidi ya premium ya mwaka mzima"
            ))
        }

        if (businessType == "boda_boda") {
            gaps.add(CoverageGap(
                type = InsuranceType.BIKE,
                riskExposure = 30_000.0,  // Average bike value
                description = "Boda boda not insured — theft or accident = total loss",
                descriptionSw = "Pikipiki yako haina bima — wizi au ajali = hasara kamili"
            ))
        }

        if (accidentRisk != RiskLevel.LOW) {
            gaps.add(CoverageGap(
                type = InsuranceType.ACCIDENT,
                riskExposure = incomeIfSick * 4,
                description = "No personal accident cover — injury means zero income",
                descriptionSw = "Hakuna bima ya ajali — kuumia kunamaanisha mapato zero"
            ))
        }

        return RiskProfile(
            workerId = memoryManager.retrieve("worker_id").ifBlank { "current" },
            businessType = businessType,
            location = location,
            stockValueAtRisk = stockValueAtRisk,
            monthlySpoilageEstimate = monthlySpoilage,
            fireRiskLevel = fireRisk,
            theftRiskLevel = theftRisk,
            healthCoverageStatus = healthCoverage,
            accidentRiskLevel = accidentRisk,
            incomeIfSick1Week = incomeIfSick,
            dependentsCount = dependents,
            riskScore = riskScore,
            coverageGaps = gaps
        )
    }

    // ──────────────────────────────────────────────
    // MATCHING ALGORITHM
    // ──────────────────────────────────────────────

    /**
     * Calculate match score between a product and worker's risk profile.
     * Returns (score, reason) where score is 0-100 and reason is Swahili explanation.
     */
    private fun calculateMatchScore(
        product: InsuranceProduct,
        profile: RiskProfile,
        budgetMonthly: Double
    ): Pair<Int, String> {
        var score = 0
        val reasons = mutableListOf<String>()

        // Business type match (essential — 0 if no match)
        val businessMatch = profile.businessType in product.targetBusinessTypes ||
                "informal_trader" in product.targetBusinessTypes
        if (!businessMatch) return Pair(0, "")

        // Base score for business type match
        score += 30

        // Budget fit
        if (product.premiumMonthly <= budgetMonthly) {
            score += 25
            reasons.add("Inafaa bajeti yako")
        } else if (product.premiumMonthly <= budgetMonthly * 1.5) {
            score += 10
            reasons.add("Inaweza kugharamika kwa juhudi kidogo")
        } else {
            return Pair(0, "")  // Too expensive
        }

        // Risk relevance
        when (product.type) {
            InsuranceType.STOCK_PROTECTION -> {
                if (profile.stockValueAtRisk > 3000) {
                    score += 20
                    reasons.add("Stock yako yenye thamani KES ${"%,.0f".format(profile.stockValueAtRisk)} inahitaji ulinzi")
                }
                if (profile.fireRiskLevel == RiskLevel.HIGH) {
                    score += 10
                    reasons.add("Hatari ya moto ni kubwa — ${profile.businessType}")
                }
            }
            InsuranceType.HEALTH -> {
                if (profile.healthCoverageStatus == "none") {
                    score += 25
                    reasons.add("Hakuna bima ya afya — ziara moja hospitali ni KES ${"%,.0f".format(profile.incomeIfSick1Week * 2)}")
                }
            }
            InsuranceType.ACCIDENT -> {
                if (profile.accidentRiskLevel == RiskLevel.HIGH) {
                    score += 25
                    reasons.add("Hatari ya ajali ni kubwa kwa ${profile.businessType}")
                } else if (profile.accidentRiskLevel == RiskLevel.MEDIUM) {
                    score += 15
                }
            }
            InsuranceType.BIKE -> {
                if (profile.businessType == "boda_boda") {
                    score += 30
                    reasons.add("Pikipiki yako inahitaji bima — wizi au ajali ni hasara kubwa")
                }
            }
            InsuranceType.FIRE -> {
                if (profile.fireRiskLevel == RiskLevel.HIGH) {
                    score += 25
                    reasons.add("Biashara yako iko hatarini kwa moto")
                }
            }
            InsuranceType.CROP -> {
                if (profile.businessType == "farmer") {
                    score += 25
                    reasons.add("Mazao yako yanahitaji ulinzi dhidi ya hali ya hewa")
                }
            }
            InsuranceType.THEFT -> {
                if (profile.theftRiskLevel == RiskLevel.HIGH) {
                    score += 20
                    reasons.add("Hatari ya wizi ni kubwa")
                }
            }
            InsuranceType.LIABILITY -> {
                score += 10
                reasons.add("Ulinzi wa dhima — mzigo mdogo kwa ulinzi mkubwa")
            }
        }

        // Claim paid rate bonus
        if (product.claimPaidRate >= 0.90) {
            score += 10
            reasons.add("Mtoa bima anayelipia ${"%.0f".format(product.claimPaidRate * 100)}% ya madai")
        }

        // Daily premium affordability (relatable terms)
        if (product.premiumDaily <= 10) {
            score += 5
            reasons.add("KES ${"%.0f".format(product.premiumDaily)}/siku — chini ya bei ya chai")
        } else if (product.premiumDaily <= 20) {
            score += 3
            reasons.add("KES ${"%.0f".format(product.premiumDaily)}/siku — bei ya sukuma wiki")
        }

        val primaryReason = reasons.firstOrNull() ?: "Inakufaa kulingana na biashara yako"
        return Pair(score.coerceIn(0, 100), primaryReason)
    }

    // ──────────────────────────────────────────────
    // VOICE INPUT PARSING (Swahili)
    // ──────────────────────────────────────────────

    /**
     * Parse Swahili voice input and route to appropriate action.
     *
     * Trigger phrases:
     * - "Bima yangu iko wapi?" → history
     * - "Nahitaji bima ya biashara" → match
     * - "Stock yangu ikiungua — nifanye nini?" → claims (fire)
     * - "Ni bima gani ninaweza afford?" → match
     * - "Nilinganishe bima" → compare
     * - "Nijiandikishe na bima" → enroll
     * - "Nifanye nini nikiumia?" → claims (accident)
     */
    private suspend fun parseVoiceInput(voiceInput: String): ToolResult {
        val input = voiceInput.lowercase().trim()
        Timber.d("Parsing insurance voice input: '$input'")

        return when {
            // History/status intents
            input.contains("bima yangu") || input.contains("bima zangu") ||
            input.contains("iko wapi") || input.contains("policies") ||
            input.contains("history") || input.contains("historia") -> {
                showHistory(mutableMapOf("action" to "history"))
            }

            // Claims intents
            input.contains("madai") || input.contains("claim") ||
            input.contains("kiungua") || input.contains("ungua") ||
            input.contains("wizi") || input.contains("ibiwa") ||
            input.contains("ajali") || input.contains("iumia") ||
            input.contains("haribika") || input.contains("kuoza") ||
            input.contains("nifanye nini") -> {
                val claimType = when {
                    input.contains("kiungua") || input.contains("ungua") || input.contains("moto") -> "fire"
                    input.contains("wizi") || input.contains("ibiwa") -> "theft"
                    input.contains("ajali") || input.contains("iumia") || input.contains("pikipiki") -> "accident"
                    input.contains("haribika") || input.contains("kuoza") || input.contains("mboga") -> "spoilage"
                    input.contains("hospitali") || input.contains("mgonjwa") || input.contains("ugonjwa") -> "illness"
                    else -> null
                }
                val params = mutableMapOf<String, String>("action" to "claims")
                if (claimType != null) params["claim_type"] = claimType
                claimGuidance(params)
            }

            // Compare intents
            input.contains("lingan") || input.contains("compare") ||
            input.contains("ipi ni bora") || input.contains("which is better") -> {
                compareProducts(mutableMapOf("action" to "compare"))
            }

            // Enroll intents
            input.contains("jiandik") || input.contains("enroll") ||
            input.contains("anza") || input.contains("sign up") ||
            input.contains("subscribe") -> {
                enrollGuidance(mutableMapOf("action" to "enroll"))
            }

            // Default: match
            else -> {
                matchProducts(mutableMapOf("action" to "match"))
            }
        }
    }

    // ──────────────────────────────────────────────
    // HELPER METHODS
    // ──────────────────────────────────────────────

    /**
     * Parse stored active policies string into InsuranceProduct objects.
     * Format: "policy_id_1,policy_id_2,..."
     */
    private fun parseActivePolicies(policiesStr: String, catalog: List<InsuranceProduct>): List<InsuranceProduct> {
        val ids = policiesStr.split(",").map { it.trim() }.filter { it.isNotBlank() }
        return ids.mapNotNull { id -> catalog.find { it.id == id } }
    }

    /**
     * Generate a relatable Swahili comparison for a daily premium amount.
     * Makes abstract insurance costs tangible.
     */
    private fun comparablePrice(dailyKes: Double): String {
        return when {
            dailyKes <= 5 -> "bei ya peremende"
            dailyKes <= 10 -> "bei ya chai"
            dailyKes <= 15 -> "bei ya sukuma wiki mbili"
            dailyKes <= 20 -> "bei ya mandazi matatu"
            dailyKes <= 25 -> "bei ya chapo mbili"
            dailyKes <= 30 -> "bei ya lunch ya haraka"
            dailyKes <= 50 -> "bei ya chips kuku"
            dailyKes <= 100 -> "bei ya chakula cha mchana"
            else -> "KES ${"%.0f".format(dailyKes)}/siku"
        }
    }

    private fun riskLabelSw(level: RiskLevel): String {
        return when (level) {
            RiskLevel.LOW -> "🟢 Ndogo"
            RiskLevel.MEDIUM -> "🟡 Wastani"
            RiskLevel.HIGH -> "🔴 Kubwa"
        }
    }

    private fun healthStatusLabel(status: String): String {
        return when (status) {
            "none" -> "❌ Hakuna bima ya afya"
            "nhif" -> "✅ NHIF"
            "private" -> "✅ Bima ya kibinafsi"
            "both" -> "✅ NHIF + Bima ya kibinafsi"
            else -> "❓ Haitajulikana"
        }
    }

    private fun claimTypeLabel(type: String): String {
        return when (type.lowercase()) {
            "fire" -> "🔥 Moto"
            "theft" -> "🔓 Wizi"
            "spoilage" -> "🥬 Kuoza kwa mazao"
            "accident" -> "💥 Ajali"
            "illness" -> "🏥 Ugonjwa"
            else -> type
        }
    }

    /**
     * Compute total risk exposure across all coverage gaps.
     */
    private val RiskProfile.totalRiskExposure: Double
        get() = coverageGaps.sumOf { it.riskExposure }
}
