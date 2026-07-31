package com.msaidizi.agent.onboarding

import com.msaidizi.core.model.ArchetypeType
import com.msaidizi.core.model.BusinessType

/**
 * WorkerTypeProfile — Skills files and mission documents per worker type.
 *
 * Implements the "HR System for AI" from the Superagent Blueprint:
 * - Skills file: what the worker type can do
 * - Mission document: what the agent should accomplish
 * - Tool access: which tools are available
 * - Guardrails: per-type thresholds
 * - Contextual questions: per-type onboarding deep dive
 *
 * Now also includes archetype-based classification (12 archetypes)
 * alongside the original BusinessType sub-type system.
 */
data class WorkerTypeProfile(
    val workerType: BusinessType,
    val archetype: ArchetypeType,
    val displayName: String,
    val swahiliName: String,
    val description: String,
    val commonChallenges: List<String>,
    val availableTools: Set<String>,
    val readOnlyTools: Set<String>,
    val mission: String,
    val financialGoals: List<String>,
    val contextualQuestions: List<ContextualQuestion>,
    val guardrails: WorkerTypeGuardrails
)

data class ContextualQuestion(
    val english: String,
    val swahili: String,
    val field: String  // Which OnboardingData field this populates
)

data class WorkerTypeGuardrails(
    val maxSingleTransaction: Double,    // KES
    val spoilageAlertThreshold: Double,  // For inventory workers
    val priceSanityRange: Double,        // ±% of 7-day average
    val fuelCostReasonableness: Double,  // For transport workers
    val maxDebtRatio: Double             // Debt / monthly income
)

/**
 * WorkerTypeRegistry — The "skills file" database.
 *
 * Each worker type has a structured profile that determines:
 * 1. Which tools are available (access control)
 * 2. What the agent's mission is (mission document)
 * 3. What questions to ask during onboarding (contextual questions)
 * 4. What guardrails to apply (per-type thresholds)
 */
object WorkerTypeRegistry {

    private val profiles: Map<BusinessType, WorkerTypeProfile> by lazy {
        buildProfiles()
    }

    /**
     * Get the profile for a worker type.
     * Falls back to a generic profile for unknown types.
     */
    fun getProfile(type: BusinessType): WorkerTypeProfile {
        return profiles[type] ?: genericProfile()
    }

    /**
     * Get all available tools for a worker type.
     * This is what G2 uses to wire WorkerTypeDetector → tool activation.
     */
    fun getAllowedTools(type: BusinessType): Set<String> {
        return getProfile(type).availableTools
    }

    /**
     * Get tools for an archetype (combines archetype registry + worker type registry).
     */
    fun getToolsForArchetype(archetype: ArchetypeType): Set<String> {
        return ArchetypeRegistry.getAllTools(archetype)
    }

    /**
     * Get tools that are read-only for a worker type.
     */
    fun getReadOnlyTools(type: BusinessType): Set<String> {
        return getProfile(type).readOnlyTools
    }

    /**
     * Get the mission document for a worker type.
     */
    fun getMission(type: BusinessType): String {
        return getProfile(type).mission
    }

    /**
     * Get contextual onboarding questions for a worker type.
     */
    fun getContextualQuestions(type: BusinessType): List<ContextualQuestion> {
        return getProfile(type).contextualQuestions
    }

    /**
     * Get guardrails for a worker type.
     */
    fun getGuardrails(type: BusinessType): WorkerTypeGuardrails {
        return getProfile(type).guardrails
    }

    /**
     * Get the archetype for a business type.
     */
    fun getArchetype(type: BusinessType): ArchetypeType {
        return type.archetype
    }

    // ═══════════════════════════════════════════════════════════
    //  PROFILE DEFINITIONS
    // ═══════════════════════════════════════════════════════════

    private fun buildProfiles(): Map<BusinessType, WorkerTypeProfile> {
        val commonTools = setOf(
            "record_transaction", "query_sales", "query_expenses", "query_profit",
            "daily_report", "weekly_report", "alama_score", "goal_tracker",
            "gamification_engine", "voice_pipeline", "code_switch_handler"
        )

        return mapOf(
            // ── Mama Mboga (Vegetable Vendor) ──
            BusinessType.MAMA_MBOGA to WorkerTypeProfile(
                workerType = BusinessType.MAMA_MBOGA,
                archetype = ArchetypeType.VENDOR,
                displayName = "Vegetable Vendor",
                swahiliName = "Mama Mboga",
                description = "Sells fresh vegetables at market or roadside stall. Perishable inventory, daily restocking, price-sensitive customers.",
                commonChallenges = listOf(
                    "Spoilage — vegetables go bad in 1-3 days",
                    "Price fluctuations at wholesale markets",
                    "Separating business money from household money",
                    "Tracking many small cash transactions"
                ),
                availableTools = commonTools + setOf(
                    "inventory_tracker", "pricing_advisor", "spoilage_tracker",
                    "restock_predictor", "market_price_broadcaster",
                    "auto_restock", "bulk_order_coordinator", "market_pooling",
                    "customer_insights", "debt_tracker", "demand_forecaster"
                ),
                readOnlyTools = setOf("produce_price_tracker", "weather_forecast_service"),
                mission = "Help the worker run a profitable vegetable business by: " +
                        "1) Recording every sale and expense, " +
                        "2) Tracking inventory freshness and spoilage, " +
                        "3) Finding the best market prices, " +
                        "4) Predicting when to restock.",
                financialGoals = listOf(
                    "Know daily profit (not just revenue)",
                    "Reduce spoilage by 30%",
                    "Build 7-day emergency savings",
                    "Separate business and personal money"
                ),
                contextualQuestions = listOf(
                    ContextualQuestion("Where do you buy your vegetables?", "Unanunua mboga wapi?", "supplier"),
                    ContextualQuestion("How much do you sell per day?", "Unauza kwa siku ngapi?", "dailyRevenueEstimate"),
                    ContextualQuestion("How many regular customers do you have?", "Una wateja wangapi wa kawaida?", "customers"),
                    ContextualQuestion("Do you use M-Pesa or cash?", "Unatumia M-Pesa au pesa taslimu?", "paymentMethod"),
                    ContextualQuestion("Do you keep business money separate?", "Una hela ya biashara peke yake?", "hasSeparateBusinessMoney")
                ),
                guardrails = WorkerTypeGuardrails(
                    maxSingleTransaction = 500_000.0,
                    spoilageAlertThreshold = 0.20,  // 20% inventory age > 3 days
                    priceSanityRange = 0.50,         // ±50% of 7-day average
                    fuelCostReasonableness = 0.0,     // N/A for non-transport
                    maxDebtRatio = 0.30
                )
            ),

            // ── Boda Boda (Motorcycle Taxi) ──
            BusinessType.BODA_BODA to WorkerTypeProfile(
                workerType = BusinessType.BODA_BODA,
                archetype = ArchetypeType.TRANSPORT_OPERATOR,
                displayName = "Motorcycle Taxi",
                swahiliName = "Boda Boda",
                description = "Motorcycle taxi operator. Daily income from fares, fuel costs, hire purchase payments, insurance.",
                commonChallenges = listOf(
                    "Fuel costs eat into earnings",
                    "Hire purchase payments are fixed regardless of income",
                    "Safety risks on the road",
                    "No record of daily earnings vs expenses"
                ),
                availableTools = commonTools + setOf(
                    "boda_boda_router", "fare_intelligence", "fuel_efficiency_tracker",
                    "hire_purchase_tracker", "sos_safety_button", "ride_share",
                    "insurance_matcher", "anomaly_detector"
                ),
                readOnlyTools = setOf("weather_forecast_service"),
                mission = "Help the rider maximize daily earnings by: " +
                        "1) Tracking every trip and fare, " +
                        "2) Monitoring fuel efficiency, " +
                        "3) Managing hire purchase payments, " +
                        "4) Finding the best routes and times.",
                financialGoals = listOf(
                    "Know daily net income (after fuel)",
                    "Track fuel efficiency (KES/km)",
                    "Never miss a hire purchase payment",
                    "Build emergency fund for repairs"
                ),
                contextualQuestions = listOf(
                    ContextualQuestion("How many hours do you work per day?", "Unafanya kazi masaa ngapi kwa siku?", "operatingHours"),
                    ContextualQuestion("Do you pay for your motorcycle?", "Unalipia pikipisi yako?", "hasHirePurchase"),
                    ContextualQuestion("Do you have insurance?", "Una bima?", "hasInsurance"),
                    ContextualQuestion("Do you save for fuel?", "Unaweka akiba ya mafuta?", "savesForFuel")
                ),
                guardrails = WorkerTypeGuardrails(
                    maxSingleTransaction = 100_000.0,
                    spoilageAlertThreshold = 0.0,     // N/A
                    priceSanityRange = 0.30,           // ±30% fare bounds
                    fuelCostReasonableness = 0.40,     // Fuel shouldn't exceed 40% of revenue
                    maxDebtRatio = 0.25
                )
            ),

            // ── Jua Kali (Artisan/Mechanic) ──
            BusinessType.JUA_KALI to WorkerTypeProfile(
                workerType = BusinessType.JUA_KALI,
                archetype = ArchetypeType.ARTISAN,
                displayName = "Jua Kali Artisan",
                swahiliName = "Jua Kali",
                description = "Informal sector artisan — mechanic, carpenter, metalworker, tailor. Project-based income, material costs, skill premiums.",
                commonChallenges = listOf(
                    "Income is irregular (project-based)",
                    "Material costs fluctuate",
                    "Hard to price jobs fairly",
                    "Finding new customers"
                ),
                availableTools = commonTools + setOf(
                    "service_menu", "service_price_advisor", "job_matcher",
                    "wage_calculator", "inventory_tracker", "demand_forecaster",
                    "customer_insights", "competitor_tracker"
                ),
                readOnlyTools = setOf("market_price_broadcaster"),
                mission = "Help the artisan earn more from their skills by: " +
                        "1) Pricing jobs fairly (materials + labour), " +
                        "2) Tracking material costs, " +
                        "3) Finding new customers, " +
                        "4) Building a reputation for reliability.",
                financialGoals = listOf(
                    "Separate material costs from labour profit",
                    "Price jobs with 30%+ profit margin",
                    "Track repeat customers",
                    "Save for tool upgrades"
                ),
                contextualQuestions = listOf(
                    ContextualQuestion("What products do you make?", "Unatengeneza bidhaa gani?", "products"),
                    ContextualQuestion("Do you have employees?", "Una wafanyakazi?", "employees"),
                    ContextualQuestion("Where do you find customers?", "Unapata wateja wapi?", "customerSource"),
                    ContextualQuestion("What are your main material costs?", "Gharama kuu za vifaa ni zipi?", "mainExpenses")
                ),
                guardrails = WorkerTypeGuardrails(
                    maxSingleTransaction = 1_000_000.0,
                    spoilageAlertThreshold = 0.0,
                    priceSanityRange = 0.40,
                    fuelCostReasonableness = 0.0,
                    maxDebtRatio = 0.30
                )
            ),

            // ── Mkulima (Farmer) ──
            BusinessType.MKULIMA to WorkerTypeProfile(
                workerType = BusinessType.MKULIMA,
                archetype = ArchetypeType.CROP_FARMER,
                displayName = "Farmer",
                swahiliName = "Mkulima",
                description = "Smallholder farmer. Seasonal income, crop cycles, input costs, weather dependency.",
                commonChallenges = listOf(
                    "Income is seasonal (harvest-dependent)",
                    "Input costs (seeds, fertilizer) are upfront",
                    "Weather and pest risks",
                    "Post-harvest losses"
                ),
                availableTools = commonTools + setOf(
                    "harvest_tracker", "harvest_timing_optimizer", "produce_price_tracker",
                    "storage_decision_calculator", "seasonal_budget_planner",
                    "weather_forecast_service", "weather_cache_manager",
                    "post_harvest_loss_tracker", "yield_predictor",
                    "demand_forecaster", "insurance_matcher"
                ),
                readOnlyTools = setOf("market_price_broadcaster"),
                mission = "Help the farmer maximize harvest value by: " +
                        "1) Tracking planting to harvest costs, " +
                        "2) Timing harvest for best prices, " +
                        "3) Reducing post-harvest losses, " +
                        "4) Planning seasonal budgets.",
                financialGoals = listOf(
                    "Know true cost per acre",
                    "Sell at peak market prices",
                    "Reduce post-harvest losses by 25%",
                    "Save during harvest for next season"
                ),
                contextualQuestions = listOf(
                    ContextualQuestion("What crops do you grow?", "Unalima mazao gani?", "crops"),
                    ContextualQuestion("How much land do you have?", "Una ekari ngapi?", "landSize"),
                    ContextualQuestion("When is your next harvest?", "Mavuno yajayo ni lini?", "nextHarvest"),
                    ContextualQuestion("Do you have storage?", "Una hifadhi?", "hasStorage")
                ),
                guardrails = WorkerTypeGuardrails(
                    maxSingleTransaction = 2_000_000.0,
                    spoilageAlertThreshold = 0.15,
                    priceSanityRange = 0.60,  // Agricultural prices are more volatile
                    fuelCostReasonableness = 0.0,
                    maxDebtRatio = 0.35
                )
            ),

            // ── M-Pesa Agent ──
            BusinessType.M_PESA to WorkerTypeProfile(
                workerType = BusinessType.M_PESA,
                archetype = ArchetypeType.AGENT_BROKER,
                displayName = "M-Pesa Agent",
                swahiliName = "M-Pesa",
                description = "Mobile money agent. Float management, commission income, high transaction volume.",
                commonChallenges = listOf(
                    "Float management — running out of cash/M-Pesa balance",
                    "Commission rates are thin",
                    "Fraud risk",
                    "High transaction volume makes tracking hard"
                ),
                availableTools = commonTools + setOf(
                    "mpesa_auto_logger", "anomaly_detector", "customer_insights",
                    "float_monitor", "commission_calculator", "daily_reconciliation",
                    "transaction_analytics"
                ),
                readOnlyTools = emptySet(),
                mission = "Help the M-Pesa agent maximize commission income by: " +
                        "1) Tracking every transaction automatically, " +
                        "2) Managing float levels, " +
                        "3) Detecting fraud patterns, " +
                        "4) Optimizing cash/M-Pesa balance.",
                financialGoals = listOf(
                    "Know daily commission income",
                    "Never run out of float",
                    "Detect fraud within 24 hours",
                    "Track net income after float costs"
                ),
                contextualQuestions = listOf(
                    ContextualQuestion("How many transactions per day?", "Unafanya miamala ngapi kwa siku?", "transactionCount"),
                    ContextualQuestion("What's your typical float amount?", "Kiasi chako cha kawaida ni ngapi?", "floatAmount"),
                    ContextualQuestion("Do you also sell airtime?", "Unauza pia airtime?", "sellsAirtime"),
                    ContextualQuestion("What's your busiest time?", "Wako busy zaidi ni wakati gani?", "peakHours")
                ),
                guardrails = WorkerTypeGuardrails(
                    maxSingleTransaction = 500_000.0,
                    spoilageAlertThreshold = 0.0,
                    priceSanityRange = 0.0,
                    fuelCostReasonableness = 0.0,
                    maxDebtRatio = 0.20
                )
            ),

            // ── Mama Lishe (Food Service) ──
            BusinessType.MAMA_LISHE to WorkerTypeProfile(
                workerType = BusinessType.MAMA_LISHE,
                archetype = ArchetypeType.FOOD_SERVICE,
                displayName = "Food Vendor",
                swahiliName = "Mama Lishe",
                description = "Prepares and sells cooked food. Recipe-based costing, fuel costs, food safety.",
                commonChallenges = listOf(
                    "Recipe costing — most don't know cost per plate",
                    "Fuel costs (charcoal/gas) fluctuate",
                    "Food safety and spoilage risk",
                    "Thin margins in competitive market"
                ),
                availableTools = commonTools + setOf(
                    "recipe_cost_calculator", "ingredient_tracker", "fuel_cost_tracker",
                    "menu_profitability", "food_safety_alerts", "delivery_order_tracker",
                    "spoilage_tracker", "inventory_tracker", "pricing_advisor",
                    "customer_insights", "demand_forecaster"
                ),
                readOnlyTools = emptySet(),
                mission = "Help the food vendor maximize profit by: " +
                        "1) Calculating true cost per plate, " +
                        "2) Tracking ingredients and fuel costs, " +
                        "3) Identifying most profitable menu items, " +
                        "4) Reducing food waste.",
                financialGoals = listOf(
                    "Know cost per plate (ingredients + fuel)",
                    "Identify most profitable menu items",
                    "Reduce food waste by 30%",
                    "Track daily profit accurately"
                ),
                contextualQuestions = listOf(
                    ContextualQuestion("What food do you cook?", "Unapika chakula gani?", "menuType"),
                    ContextualQuestion("What fuel do you use?", "Unapika na nini?", "fuelType"),
                    ContextualQuestion("Do you know cost per plate?", "Unajua bei ya chakula kimoja?", "knowsCostPerPlate"),
                    ContextualQuestion("Do you sell via delivery apps?", "Unauza kupitia Glovo au Bolt Food?", "deliveryApps")
                ),
                guardrails = WorkerTypeGuardrails(
                    maxSingleTransaction = 200_000.0,
                    spoilageAlertThreshold = 0.15,
                    priceSanityRange = 0.40,
                    fuelCostReasonableness = 0.15,
                    maxDebtRatio = 0.30
                )
            ),

            // ── Mvuvi (Fisher) ──
            BusinessType.MVUVI to WorkerTypeProfile(
                workerType = BusinessType.MVUVI,
                archetype = ArchetypeType.FISHER,
                displayName = "Fisher",
                swahiliName = "Mvuvi",
                description = "Catches or raises fish. High perishability, seasonal patterns, equipment costs.",
                commonChallenges = listOf(
                    "Catch uncertainty — income is highly variable",
                    "Fish spoils in hours without cold chain",
                    "Equipment (boats, nets) are expensive",
                    "Middleman exploitation at landing sites"
                ),
                availableTools = commonTools + setOf(
                    "fishing_log", "catch_tracker", "equipment_maintenance_log",
                    "weather_alerts", "market_price_broadcaster", "preservation_tracker",
                    "crew_payment_tracker", "seasonal_planner"
                ),
                readOnlyTools = setOf("weather_forecast_service"),
                mission = "Help the fisher maximize catch value by: " +
                        "1) Tracking every catch and trip, " +
                        "2) Comparing prices across landing sites, " +
                        "3) Predicting best fishing days, " +
                        "4) Managing equipment costs.",
                financialGoals = listOf(
                    "Track catch by species, weight, and price",
                    "Find best-priced landing sites",
                    "Reduce fuel cost per kg of catch",
                    "Plan around seasonal bans"
                ),
                contextualQuestions = listOf(
                    ContextualQuestion("Wild catch or fish farming?", "Unavua samaki au unafuga?", "fishingType"),
                    ContextualQuestion("Where do you fish?", "Unavua wapi?", "fishingLocation"),
                    ContextualQuestion("What species?", "Samaki wako wa aina gani?", "species"),
                    ContextualQuestion("Do you own a boat?", "Una boti yako?", "boatOwnership")
                ),
                guardrails = WorkerTypeGuardrails(
                    maxSingleTransaction = 500_000.0,
                    spoilageAlertThreshold = 0.25,
                    priceSanityRange = 0.50,
                    fuelCostReasonableness = 0.30,
                    maxDebtRatio = 0.30
                )
            ),

            // ── Mfugaji (Livestock Keeper) ──
            BusinessType.MFUGAJI to WorkerTypeProfile(
                workerType = BusinessType.MFUGAJI,
                archetype = ArchetypeType.LIVESTOCK_KEEPER,
                displayName = "Livestock Keeper",
                swahiliName = "Mfugaji",
                description = "Raises animals for income — dairy, poultry, goats, cattle. Daily care, feed costs, veterinary needs.",
                commonChallenges = listOf(
                    "Feed costs dominate — 30-60% of revenue",
                    "Disease risk can kill entire flock/herd",
                    "Veterinary access is expensive and scarce",
                    "Production variability with health/feed changes"
                ),
                availableTools = commonTools + setOf(
                    "production_tracker", "feed_cost_tracker", "animal_health_log",
                    "mortality_tracker", "breeding_calendar", "vet_schedule_reminder",
                    "profit_per_animal", "market_price_broadcaster"
                ),
                readOnlyTools = emptySet(),
                mission = "Help the livestock keeper maximize profit by: " +
                        "1) Tracking daily production (milk, eggs), " +
                        "2) Managing feed costs per animal, " +
                        "3) Monitoring animal health and vaccination schedules, " +
                        "4) Tracking mortality and breeding cycles.",
                financialGoals = listOf(
                    "Know daily production income",
                    "Track feed cost per animal",
                    "Never miss a vaccination",
                    "Reduce mortality rates"
                ),
                contextualQuestions = listOf(
                    ContextualQuestion("What animals do you keep?", "Unafuga wanyama gani?", "animals"),
                    ContextualQuestion("How many animals?", "Una wanyama wangapi?", "animalCount"),
                    ContextualQuestion("Do you buy feed or graze?", "Unanunua chakula au unachungisha?", "feedSource"),
                    ContextualQuestion("Do you use a vet?", "Unatumia daktari wa mifugo?", "usesVet")
                ),
                guardrails = WorkerTypeGuardrails(
                    maxSingleTransaction = 1_000_000.0,
                    spoilageAlertThreshold = 0.0,
                    priceSanityRange = 0.40,
                    fuelCostReasonableness = 0.0,
                    maxDebtRatio = 0.30
                )
            ),

            // ── Salon/Barber (Service Provider) ──
            BusinessType.SALON to WorkerTypeProfile(
                workerType = BusinessType.SALON,
                archetype = ArchetypeType.SERVICE_PROVIDER,
                displayName = "Salon Owner",
                swahiliName = "Mwenye Salon",
                description = "Beauty service provider — hair styling, braiding, manicure. Appointment-based, repeat customers.",
                commonChallenges = listOf(
                    "Inconsistent job flow — some days zero clients",
                    "Undercharging from fear of losing clients",
                    "No appointment system — walk-in model",
                    "Product costs for cosmetics and supplies"
                ),
                availableTools = commonTools + setOf(
                    "service_menu", "booking_scheduler", "service_price_advisor",
                    "customer_matcher", "customer_insights", "rating_system",
                    "competitor_tracker", "service_history", "customer_retention",
                    "tool_inventory"
                ),
                readOnlyTools = emptySet(),
                mission = "Help the salon owner grow their business by: " +
                        "1) Managing appointments and reducing no-shows, " +
                        "2) Tracking service history per customer, " +
                        "3) Pricing services fairly, " +
                        "4) Building customer loyalty.",
                financialGoals = listOf(
                    "Fill appointment slots consistently",
                    "Know which services are most profitable",
                    "Build repeat customer base",
                    "Track product costs accurately"
                ),
                contextualQuestions = listOf(
                    ContextualQuestion("What services do you offer?", "Unatoa huduma gani?", "services"),
                    ContextualQuestion("Do you have employees?", "Una wafanyakazi?", "employees"),
                    ContextualQuestion("How do clients find you?", "Unapata wateja wapi?", "clientSource"),
                    ContextualQuestion("Do you use appointments?", "Unatumia miadi?", "usesAppointments")
                ),
                guardrails = WorkerTypeGuardrails(
                    maxSingleTransaction = 100_000.0,
                    spoilageAlertThreshold = 0.0,
                    priceSanityRange = 0.40,
                    fuelCostReasonableness = 0.0,
                    maxDebtRatio = 0.25
                )
            ),

            // ── Mjengo (Casual Laborer) ──
            BusinessType.MJENGO to WorkerTypeProfile(
                workerType = BusinessType.MJENGO,
                archetype = ArchetypeType.CASUAL_LABORER,
                displayName = "Construction Worker",
                swahiliName = "Mjengo",
                description = "Casual construction laborer. Daily wages, irregular work, no contracts, no benefits.",
                commonChallenges = listOf(
                    "Zero income security — no work = no pay",
                    "Payment delays — employers may delay or refuse",
                    "No savings — living hand-to-mouth",
                    "Health risks — accidents, physical strain"
                ),
                availableTools = commonTools + setOf(
                    "daily_wage_tracker", "work_calendar", "payment_reminder",
                    "savings_micro", "employer_database", "health_risk_alerts",
                    "emergency_fund", "job_matcher", "wage_calculator"
                ),
                readOnlyTools = emptySet(),
                mission = "Help the construction worker stabilize income by: " +
                        "1) Tracking every day's work and earnings, " +
                        "2) Identifying work patterns (good weeks vs bad), " +
                        "3) Reminding about unpaid wages, " +
                        "4) Building micro-savings for idle days.",
                financialGoals = listOf(
                    "Track days worked vs idle",
                    "Know weekly earning patterns",
                    "Build emergency fund for idle periods",
                    "Never lose track of unpaid wages"
                ),
                contextualQuestions = listOf(
                    ContextualQuestion("What kind of construction work?", "Unafanya kazi gani ya ujenzi?", "workType"),
                    ContextualQuestion("How do you find work?", "Unapata kazi vipi?", "jobSource"),
                    ContextualQuestion("What's your daily rate?", "Bei yako ya kwa siku ni ngapi?", "dailyRate"),
                    ContextualQuestion("Days worked per week?", "Siku ngapi kwa wiki?", "daysPerWeek")
                ),
                guardrails = WorkerTypeGuardrails(
                    maxSingleTransaction = 50_000.0,
                    spoilageAlertThreshold = 0.0,
                    priceSanityRange = 0.30,
                    fuelCostReasonableness = 0.0,
                    maxDebtRatio = 0.20
                )
            )
        )
    }

    private fun genericProfile(): WorkerTypeProfile {
        val commonTools = setOf(
            "record_transaction", "query_sales", "query_expenses", "query_profit",
            "daily_report", "weekly_report", "alama_score", "goal_tracker",
            "gamification_engine", "voice_pipeline", "code_switch_handler"
        )

        return WorkerTypeProfile(
            workerType = BusinessType.OTHER,
            archetype = ArchetypeType.VENDOR,
            displayName = "Business Owner",
            swahiliName = "Mwenye Biashara",
            description = "General business owner. Msaidizi will learn the specific business type through daily interactions.",
            commonChallenges = listOf(
                "Tracking income and expenses",
                "Separating business and personal money",
                "Planning for growth"
            ),
            availableTools = commonTools + setOf(
                "inventory_tracker", "customer_insights", "debt_tracker",
                "demand_forecaster"
            ),
            readOnlyTools = emptySet(),
            mission = "Help the business owner understand their finances by: " +
                    "1) Recording every transaction, " +
                    "2) Tracking profit daily, " +
                    "3) Building savings habits, " +
                    "4) Making informed business decisions.",
            financialGoals = listOf(
                "Know daily profit",
                "Track all expenses",
                "Build emergency savings",
                "Understand business trends"
            ),
            contextualQuestions = listOf(
                ContextualQuestion("Tell me about your business.", "Niambie kuhusu biashara yako.", "description"),
                ContextualQuestion("How much do you earn in a typical week?", "Unapata pesa ngapi kwa wiki ya kawaida?", "weeklyIncomeEstimate"),
                ContextualQuestion("What are your main expenses?", "Gharama kuu ni zipi?", "mainExpenses"),
                ContextualQuestion("Do you save money?", "Unaweka akiba?", "savesMoney")
            ),
            guardrails = WorkerTypeGuardrails(
                maxSingleTransaction = 1_000_000.0,
                spoilageAlertThreshold = 0.0,
                priceSanityRange = 0.50,
                fuelCostReasonableness = 0.0,
                maxDebtRatio = 0.30
            )
        )
    }
}
