package com.msaidizi.agent.tools.financial

import com.msaidizi.core.model.ArchetypeType
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

// ════════════════════════════════════════════════════════════
// PRICING ENGINE — Msaidizi subscription pricing per archetype
// ════════════════════════════════════════════════════════════
// KES 10–50/day per archetype (KES 300–1,500/month)
// Affordability target: <5% of median archetype income
// Freemium funnel: Free → Basic → Standard → Premium
// ════════════════════════════════════════════════════════════

/**
 * Pricing tier definition.
 */
data class PricingTier(
    val id: String,
    val name: String,
    val nameSwahili: String,
    val dailyPrice: Double,        // KES per day
    val monthlyPrice: Double,      // KES per month (30 days)
    val features: List<String>,
    val featuresSwahili: List<String>
)

/**
 * Archetype pricing configuration.
 */
data class ArchetypePricing(
    val archetype: ArchetypeType,
    val monthlyIncomeRange: Pair<Double, Double>,  // KES min–max
    val affordabilityCeiling: Double,               // 5% of median income
    val recommendedModel: String,                   // "freemium", "tiered", "transaction", "subscription"
    val tiers: List<PricingTier>,
    val conversionTrigger: String,
    val conversionTriggerSwahili: String
)

/**
 * PricingEngine — Per-archetype Msaidizi subscription pricing.
 *
 * Determines what each worker archetype can afford and maps
 * features to pricing tiers. KES 10-50/day range.
 */
@Singleton
class PricingEngine @Inject constructor() : Tool {

    override val name = "pricing_engine"
    override val description = "Msaidizi subscription pricing per archetype. KES 10-50/day. " +
            "Shows available plans, recommends tier, explains features."

    override val argsSchema = argSchema {
        enum("action", "Pricing action",
            listOf("show_plans", "recommend", "compare", "calculate_revenue"), required = false)
        enum("archetype", "Worker archetype",
            listOf("VENDOR", "ARTISAN", "SERVICE_PROVIDER", "TRANSPORT_OPERATOR",
                "CROP_FARMER", "LIVESTOCK_KEEPER", "FISHER", "AGENT_BROKER",
                "DIGITAL_WORKER", "CASUAL_LABORER", "FOOD_SERVICE", "COMMUNITY_CARE_WORKER"),
            required = false)
        integer("monthly_income", "Worker's monthly income in KES (for recommendation)", required = false)
        integer("user_count", "Total users for revenue projection", required = false, default = "100000")
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"] ?: "show_plans"
        return when (action.lowercase()) {
            "show_plans" -> showPlans(params)
            "recommend" -> recommendTier(params)
            "compare" -> compareTiers(params)
            "calculate_revenue" -> calculateRevenue(params)
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    // ════════════════════════════════════════════════════════════
    //  PRICING DEFINITIONS — KES 10-50/day per archetype
    // ════════════════════════════════════════════════════════════

    private fun getPricing(archetype: ArchetypeType): ArchetypePricing {
        return when (archetype) {
            ArchetypeType.VENDOR -> ArchetypePricing(
                archetype = archetype,
                monthlyIncomeRange = 5_000.0 to 200_000.0,
                affordabilityCeiling = 1_500.0,
                recommendedModel = "tiered",
                tiers = listOf(
                    freeTier(),
                    PricingTier(
                        "basic", "Basic", "Msingi",
                        dailyPrice = 3.3, monthlyPrice = 99.0,
                        features = listOf("Full profit tracking", "Weekly reports", "Spoilage alerts"),
                        featuresSwahili = listOf("Fuatilia faida yako", "Ripoti ya wiki", "Tahadhari za kupotea")
                    ),
                    PricingTier(
                        "standard", "Standard", "Kawaida",
                        dailyPrice = 10.0, monthlyPrice = 299.0,
                        features = listOf("Everything in Basic", "Inventory management",
                            "Restock predictions", "Customer insights", "Demand forecasting"),
                        featuresSwahili = listOf("Kila kitu cha Msingi", "Usimamizi wa stock",
                            "Tabiri za kununua", "Ufahamu wa wateja", "Tabiri za mahitaji")
                    )
                ),
                conversionTrigger = "Want to see your full month's profit? That's Premium.",
                conversionTriggerSwahili = "Unataka kuona faida yako ya mwezi mzima? Hii ni Premium."
            )

            ArchetypeType.ARTISAN -> ArchetypePricing(
                archetype = archetype,
                monthlyIncomeRange = 8_000.0 to 100_000.0,
                affordabilityCeiling = 2_000.0,
                recommendedModel = "tiered",
                tiers = listOf(
                    freeTier(),
                    PricingTier(
                        "basic", "Basic", "Msingi",
                        dailyPrice = 5.0, monthlyPrice = 149.0,
                        features = listOf("Project costing", "Material tracking", "Payment reminders"),
                        featuresSwahili = listOf("Gharama za kazi", "Fuatilia vifaa", "Kumbusho za malipo")
                    ),
                    PricingTier(
                        "premium", "Premium", "Juu",
                        dailyPrice = 13.3, monthlyPrice = 399.0,
                        features = listOf("Everything in Basic", "Job quotation builder",
                            "Client portfolio", "Tool inventory", "Skill pricing advisor"),
                        featuresSwahili = listOf("Kila kitu cha Msingi", "Mjengo wa nukuu",
                            "Wateja wako", "Vifaa vyako", "Bei ya ufundi")
                    )
                ),
                conversionTrigger = "Your loan needs an income report. Premium can generate that.",
                conversionTriggerSwahili = "Mkopo wako unahitaji ripoti ya mapato. Premium inaweza kutoa."
            )

            ArchetypeType.SERVICE_PROVIDER -> ArchetypePricing(
                archetype = archetype,
                monthlyIncomeRange = 8_000.0 to 100_000.0,
                affordabilityCeiling = 2_000.0,
                recommendedModel = "subscription_plus_fee",
                tiers = listOf(
                    freeTier(),
                    PricingTier(
                        "standard", "Standard", "Kawaida",
                        dailyPrice = 5.0, monthlyPrice = 149.0,
                        features = listOf("Appointment scheduler", "Service history",
                            "Customer retention tracking", "Pricing advisor"),
                        featuresSwahili = listOf("Ratiba za miadi", "Historia ya huduma",
                            "Fuatilia wateja", "Bei sahihi")
                    )
                ),
                conversionTrigger = "How many customers haven't returned? Premium shows that.",
                conversionTriggerSwahili = "Wateja wangapi hawajarudi? Premium inaonyesha."
            )

            ArchetypeType.TRANSPORT_OPERATOR -> ArchetypePricing(
                archetype = archetype,
                monthlyIncomeRange = 5_000.0 to 100_000.0,
                affordabilityCeiling = 1_500.0,
                recommendedModel = "transaction",
                tiers = listOf(
                    freeTier(),
                    PricingTier(
                        "per_trip", "Per Trip", "Kwa Safari",
                        dailyPrice = 0.0, monthlyPrice = 0.0,
                        features = listOf("KES 3-5 per logged trip", "Fuel tracking",
                            "Hire-purchase management", "Route optimization"),
                        featuresSwahili = listOf("KES 3-5 kwa safari", "Fuatilia mafuta",
                            "Usimamizi wa kikosi", "Njia bora")
                    )
                ),
                conversionTrigger = "Track every trip to see your true daily profit.",
                conversionTriggerSwahili = "Fuatilia kila safari kuona faida yako ya kweli."
            )

            ArchetypeType.CROP_FARMER -> ArchetypePricing(
                archetype = archetype,
                monthlyIncomeRange = 1_000.0 to 30_000.0,
                affordabilityCeiling = 500.0,
                recommendedModel = "freemium_seasonal",
                tiers = listOf(
                    freeTier(),
                    PricingTier(
                        "seasonal", "Seasonal", "Msimu",
                        dailyPrice = 1.6, monthlyPrice = 49.0,
                        features = listOf("Seasonal budget planner", "Harvest tracker",
                            "Post-harvest loss tracking", "Market price alerts"),
                        featuresSwahili = listOf("Bajeti ya msimu", "Fuatilia mavuno",
                            "Hasara baada ya mavuno", "Bei za soko")
                    )
                ),
                conversionTrigger = "Plan for next season's inputs. Seasonal plan helps.",
                conversionTriggerSwahili = "Panga gharama za msimu ujao. Mpango wa msimu unasaidia."
            )

            ArchetypeType.LIVESTOCK_KEEPER -> ArchetypePricing(
                archetype = archetype,
                monthlyIncomeRange = 2_000.0 to 50_000.0,
                affordabilityCeiling = 1_000.0,
                recommendedModel = "freemium",
                tiers = listOf(
                    freeTier(),
                    PricingTier(
                        "standard", "Standard", "Kawaida",
                        dailyPrice = 3.3, monthlyPrice = 99.0,
                        features = listOf("Production tracking", "Feed cost optimizer",
                            "Animal health log", "Mortality tracking"),
                        featuresSwahili = listOf("Fuatilia uzalishaji", "Gharama za chakula",
                            "Afya ya mifugo", "Kufa kwa mifugo")
                    )
                ),
                conversionTrigger = "Track daily milk/egg production to see true profit per animal.",
                conversionTriggerSwahili = "Fuatilia maziwa/mayai ya kila siku kuona faida halisi."
            )

            ArchetypeType.FISHER -> ArchetypePricing(
                archetype = archetype,
                monthlyIncomeRange = 5_000.0 to 80_000.0,
                affordabilityCeiling = 1_000.0,
                recommendedModel = "freemium",
                tiers = listOf(
                    freeTier(),
                    PricingTier(
                        "standard", "Standard", "Kawaida",
                        dailyPrice = 3.3, monthlyPrice = 99.0,
                        features = listOf("Catch tracking", "Weather-based predictions",
                            "Equipment maintenance", "Landing site prices"),
                        featuresSwahili = listOf("Fuatilia uvuvi", "Tabiri za hali ya hewa",
                            "Matengenezo ya vifaa", "Bei bandarini")
                    )
                ),
                conversionTrigger = "Predict the best fishing days based on weather patterns.",
                conversionTriggerSwahili = "Tabiri siku bora za kuvua kulingana na hali ya hewa."
            )

            ArchetypeType.AGENT_BROKER -> ArchetypePricing(
                archetype = archetype,
                monthlyIncomeRange = 12_000.0 to 200_000.0,
                affordabilityCeiling = 2_500.0,
                recommendedModel = "subscription",
                tiers = listOf(
                    freeTier(),
                    PricingTier(
                        "standard", "Standard", "Kawaida",
                        dailyPrice = 6.6, monthlyPrice = 199.0,
                        features = listOf("Float management", "Commission tracking",
                            "Daily reconciliation", "Transaction analytics"),
                        featuresSwahili = listOf("Usimamizi wa float", "Fuatilia kamisheni",
                            "Reconciliation ya kila siku", "Uchambuzi wa miamala")
                    ),
                    PricingTier(
                        "premium", "Premium", "Juu",
                        dailyPrice = 16.6, monthlyPrice = 499.0,
                        features = listOf("Everything in Standard", "Fraud detection",
                            "Multi-outlet management", "Customer insights"),
                        featuresSwahili = listOf("Kila kitu cha Kawaida", "Gundua udanganyifu",
                            "Maduka mengi", "Ufahamu wa wateja")
                    )
                ),
                conversionTrigger = "Manage float across multiple outlets. Premium unlocks this.",
                conversionTriggerSwahili = "Simamia float kwenye maduka mengi. Premium inafungua hii."
            )

            ArchetypeType.DIGITAL_WORKER -> ArchetypePricing(
                archetype = archetype,
                monthlyIncomeRange = 5_000.0 to 200_000.0,
                affordabilityCeiling = 2_500.0,
                recommendedModel = "freemium",
                tiers = listOf(
                    freeTier(),
                    PricingTier(
                        "standard", "Standard", "Kawaida",
                        dailyPrice = 6.6, monthlyPrice = 199.0,
                        features = listOf("Project tracker", "Invoice manager",
                            "Platform earnings aggregation", "Client database"),
                        featuresSwahili = listOf("Fuatilia miradi", "Meneja wa ankara",
                            "Mapato ya platform", "Wateja wako")
                    ),
                    PricingTier(
                        "premium", "Premium", "Juu",
                        dailyPrice = 16.6, monthlyPrice = 499.0,
                        features = listOf("Everything in Standard", "Multi-platform analytics",
                            "Tax-ready reports", "Content monetization tracking"),
                        featuresSwahili = listOf("Kila kitu cha Kawaida", "Uchambuzi wa platform",
                            "Ripoti za kodi", "Mapato ya maudhui")
                    )
                ),
                conversionTrigger = "Aggregate earnings from all platforms in one view.",
                conversionTriggerSwahili = "Kusanya mapato kutoka platform zote mahali pamoja."
            )

            ArchetypeType.CASUAL_LABORER -> ArchetypePricing(
                archetype = archetype,
                monthlyIncomeRange = 3_000.0 to 15_000.0,
                affordabilityCeiling = 300.0,
                recommendedModel = "freemium",
                tiers = listOf(
                    freeTier(),
                    PricingTier(
                        "micro", "Micro Save", "Akiba Ndogo",
                        dailyPrice = 0.3, monthlyPrice = 10.0,
                        features = listOf("KES 5-10/transaction for premium",
                            "Wage tracking", "Job matching", "Savings micro-goals"),
                        featuresSwahili = listOf("KES 5-10 kwa muamala",
                            "Fuatilia mshahara", "Kazi zinazofaa", "Akiba ndogo")
                    )
                ),
                conversionTrigger = "Save KES 20/day toward your goal. Micro plan helps.",
                conversionTriggerSwahili = "Weka akiba KES 20/siku kwa lengo lako. Mpango wa Akiba Ndogo unasaidia."
            )

            ArchetypeType.FOOD_SERVICE -> ArchetypePricing(
                archetype = archetype,
                monthlyIncomeRange = 5_000.0 to 150_000.0,
                affordabilityCeiling = 1_500.0,
                recommendedModel = "tiered",
                tiers = listOf(
                    freeTier(),
                    PricingTier(
                        "basic", "Basic", "Msingi",
                        dailyPrice = 3.3, monthlyPrice = 99.0,
                        features = listOf("Recipe cost calculator", "Ingredient tracking",
                            "Spoilage alerts", "Menu profitability"),
                        featuresSwahili = listOf("Gharama ya mapishi", "Fuatilia vifungashio",
                            "Tahadhari za kupotea", "Faida ya menyu")
                    ),
                    PricingTier(
                        "standard", "Standard", "Kawaida",
                        dailyPrice = 10.0, monthlyPrice = 299.0,
                        features = listOf("Everything in Basic", "Delivery order tracking",
                            "Customer insights", "Demand forecasting"),
                        featuresSwahili = listOf("Kila kitu cha Msingi", "Fuatilia delivery",
                            "Ufahamu wa wateja", "Tabiri za mahitaji")
                    )
                ),
                conversionTrigger = "Know your true cost per plate. Basic plan shows this.",
                conversionTriggerSwahili = "Jua gharama halisi ya sahani. Mpango wa Msingi unaonyesha."
            )

            ArchetypeType.COMMUNITY_CARE_WORKER -> ArchetypePricing(
                archetype = archetype,
                monthlyIncomeRange = 5_000.0 to 100_000.0,
                affordabilityCeiling = 1_500.0,
                recommendedModel = "freemium",
                tiers = listOf(
                    freeTier(),
                    PricingTier(
                        "standard", "Standard", "Kawaida",
                        dailyPrice = 5.0, monthlyPrice = 149.0,
                        features = listOf("Booking manager", "Equipment tracker",
                            "Invoice manager", "Client portfolio"),
                        featuresSwahili = listOf("Meneja wa booking", "Fuatilia vifaa",
                            "Meneja wa ankara", "Wateja wako")
                    )
                ),
                conversionTrigger = "Manage bookings and send invoices. Standard plan helps.",
                conversionTriggerSwahili = "Simamia bookings na kutuma ankara. Mpango wa Kawaida unasaidia."
            )

            else -> ArchetypePricing(
                archetype = archetype,
                monthlyIncomeRange = 5_000.0 to 100_000.0,
                affordabilityCeiling = 1_500.0,
                recommendedModel = "freemium",
                tiers = listOf(freeTier()),
                conversionTrigger = "Unlock advanced features with a paid plan.",
                conversionTriggerSwahili = "Fungua vipengele vya ziada na mpango wa kulipia."
            )
        }
    }

    private fun freeTier(): PricingTier = PricingTier(
        "free", "Free", "Bure",
        dailyPrice = 0.0, monthlyPrice = 0.0,
        features = listOf("Transaction recording (unlimited)", "Daily profit calculation",
            "M-Pesa auto-parsing", "Basic weekly report", "SOS safety button", "Gamification"),
        featuresSwahili = listOf("Rekodi miamala (bila kikomo)", "Faida ya kila siku",
            "M-Pesa otomatiki", "Ripoti ya wiki", "Kitufe cha SOS", "Mchezo wa alama")
    )

    // ════════════════════════════════════════════════════════════
    //  ACTIONS
    // ════════════════════════════════════════════════════════════

    private fun showPlans(params: Map<String, String>): ToolResult {
        val archetype = params["archetype"]?.let { runCatching { ArchetypeType.valueOf(it) }.getOrNull() }
            ?: ArchetypeType.VENDOR
        val pricing = getPricing(archetype)

        val report = buildString {
            appendLine("💰 MPANGO WA MALIPO — ${archetype.swahiliName}")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("📊 Mapato ya mwezi: KES ${"%,.0f".format(pricing.monthlyIncomeRange.first)}–${"%,.0f".format(pricing.monthlyIncomeRange.second)}")
            appendLine("🎯 Kiwango cha bei: <5% ya mapato")
            appendLine()

            pricing.tiers.forEach { tier ->
                appendLine("▸ ${tier.name} (${tier.nameSwahili})")
                if (tier.dailyPrice > 0) {
                    appendLine("  Bei: KES ${"%.0f".format(tier.dailyPrice)}/siku = KES ${"%.0f".format(tier.monthlyPrice)}/mwezi")
                } else {
                    appendLine("  Bei: BURE")
                }
                appendLine("  Vipengele:")
                tier.featuresSwahili.forEach { f ->
                    appendLine("    ✓ $f")
                }
                appendLine()
            }

            appendLine("💡 ${pricing.conversionTriggerSwahili}")
        }

        return ToolResult.success(
            toolName = name,
            data = mapOf(
                "archetype" to archetype.name,
                "model" to pricing.recommendedModel,
                "tiers" to pricing.tiers.map { mapOf("name" to it.name, "daily" to it.dailyPrice, "monthly" to it.monthlyPrice) }
            ),
            message = report
        )
    }

    private fun recommendTier(params: Map<String, String>): ToolResult {
        val archetype = params["archetype"]?.let { runCatching { ArchetypeType.valueOf(it) }.getOrNull() }
            ?: ArchetypeType.VENDOR
        val income = params["monthly_income"]?.toDoubleOrNull() ?: 20_000.0
        val pricing = getPricing(archetype)

        // Recommend based on income
        val affordableTiers = pricing.tiers.filter { it.monthlyPrice <= income * 0.05 }
        val recommended = affordableTiers.lastOrNull() ?: pricing.tiers.first()

        val report = buildString {
            appendLine("🎯 MAPENDEKEZO — ${archetype.swahiliName}")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("Mapato yako: KES ${"%,.0f".format(income)}/mwezi")
            appendLine("Bei inayofaa: < KES ${"%,.0f".format(income * 0.05)}/mwezi")
            appendLine()
            appendLine("✅ Mapendekezo: ${recommended.name} (${recommended.nameSwahili})")
            if (recommended.dailyPrice > 0) {
                appendLine("   KES ${"%.0f".format(recommended.dailyPrice)}/siku = KES ${"%.0f".format(recommended.monthlyPrice)}/mwezi")
                appendLine("   Hiyo ni ${"%.1f".format(recommended.monthlyPrice / income * 100)}% ya mapato yako")
            } else {
                appendLine("   BURE — hakuna gharama!")
            }
            appendLine()
            appendLine("Vipengele:")
            recommended.featuresSwahili.forEach { appendLine("   ✓ $it") }
        }

        return ToolResult.success(name, data = mapOf("recommended" to recommended.name), message = report)
    }

    private fun compareTiers(params: Map<String, String>): ToolResult {
        val archetype = params["archetype"]?.let { runCatching { ArchetypeType.valueOf(it) }.getOrNull() }
            ?: ArchetypeType.VENDOR
        val pricing = getPricing(archetype)

        val report = buildString {
            appendLine("📊 LINGANISHO LA MPANGO — ${archetype.swahiliName}")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            pricing.tiers.forEach { tier ->
                val price = if (tier.dailyPrice > 0) "KES ${"%.0f".format(tier.monthlyPrice)}/mwezi" else "BURE"
                appendLine("${tier.name}: $price — ${tier.featuresSwahili.firstOrNull() ?: ""}")
            }
        }

        return ToolResult.success(name, message = report)
    }

    private fun calculateRevenue(params: Map<String, String>): ToolResult {
        val userCount = params["user_count"]?.toIntOrNull() ?: 100_000

        // Revenue projection: 70% free, 15% basic, 10% standard, 5% premium
        val freeUsers = (userCount * 0.70).toInt()
        val basicUsers = (userCount * 0.15).toInt()
        val standardUsers = (userCount * 0.10).toInt()
        val premiumUsers = (userCount * 0.05).toInt()

        val basicRevenue = basicUsers * 99.0
        val standardRevenue = standardUsers * 199.0
        val premiumRevenue = premiumUsers * 399.0
        val totalMonthly = basicRevenue + standardRevenue + premiumRevenue
        val arpu = if (userCount > 0) totalMonthly / userCount else 0.0

        val report = buildString {
            appendLine("💰 MAKADIRIO YA MAPATO — Msaidizi")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("Watumiaji: ${"%,d".format(userCount)}")
            appendLine()
            appendLine("Free: ${"%,d".format(freeUsers)} (70%) — KES 0")
            appendLine("Basic (KES 99): ${"%,d".format(basicUsers)} (15%) — KES ${"%,.0f".format(basicRevenue)}/mwezi")
            appendLine("Standard (KES 199): ${"%,d".format(standardUsers)} (10%) — KES ${"%,.0f".format(standardRevenue)}/mwezi")
            appendLine("Premium (KES 399): ${"%,d".format(premiumUsers)} (5%) — KES ${"%,.0f".format(premiumRevenue)}/mwezi")
            appendLine()
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("JUMLA: KES ${"%,.0f".format(totalMonthly)}/mwezi")
            appendLine("ARPU: KES ${"%.0f".format(arpu)}/mwezi")
            appendLine("ARR: KES ${"%,.0f".format(totalMonthly * 12)} (~$${"%,.0f".format(totalMonthly * 12 / 130)} USD)")
        }

        return ToolResult.success(
            name,
            data = mapOf(
                "totalUsers" to userCount, "freeUsers" to freeUsers,
                "basicUsers" to basicUsers, "standardUsers" to standardUsers,
                "premiumUsers" to premiumUsers,
                "monthlyRevenue" to totalMonthly, "arpu" to arpu,
                "annualRevenue" to totalMonthly * 12
            ),
            message = report
        )
    }
}
