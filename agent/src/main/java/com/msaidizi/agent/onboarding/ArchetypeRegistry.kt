package com.msaidizi.agent.onboarding

import com.msaidizi.core.model.ArchetypeType
import com.msaidizi.core.model.BusinessType
import com.msaidizi.core.model.SubTypeConfig

/**
 * ArchetypeRegistry — Tool activation, onboarding flows, and guardrails per archetype.
 *
 * Each archetype gets:
 * 1. A set of available tools (archetype-specific + universal)
 * 2. Onboarding conversation flow (branching logic, questions)
 * 3. Guardrails (transaction limits, alerts)
 * 4. Relationships to other archetypes (often combined, upgrades)
 */
object ArchetypeRegistry {

    // ══════════════════════════════════════════════════════════
    //  Universal tools available to ALL archetypes
    // ══════════════════════════════════════════════════════════

    private val universalTools = setOf(
        "record_transaction", "query_sales", "query_expenses", "query_profit",
        "daily_report", "weekly_report", "alama_score", "goal_tracker",
        "gamification_engine", "voice_pipeline", "code_switch_handler",
        "debt_tracker", "net_income_calculator", "profit_by_product",
        "chama_manager", "insurance_matcher", "sos_safety_button",
        "mpesa_parser", "mpesa_auto_logger", "anomaly_detector",
        "business_health_dashboard", "proof_of_income", "loan_comparison",
        "archetype_financial_model", "pricing_engine", "tax_compliance"
    )

    // ══════════════════════════════════════════════════════════
    //  Tool mapping per archetype
    // ══════════════════════════════════════════════════════════

    private val archetypeTools: Map<ArchetypeType, Set<String>> = mapOf(
        ArchetypeType.VENDOR to setOf(
            "inventory_tracker", "pricing_advisor", "spoilage_tracker",
            "restock_predictor", "market_price_broadcaster",
            "auto_restock", "bulk_order_coordinator", "market_pooling",
            "customer_insights", "demand_forecaster", "customer_credit_tracker",
            "receipt_scanner", "receipt_scanner_cv"
        ),
        ArchetypeType.FOOD_SERVICE to setOf(
            "recipe_cost_calculator", "ingredient_tracker", "fuel_cost_tracker",
            "menu_profitability", "food_safety_alerts", "delivery_order_tracker",
            "spoilage_tracker", "inventory_tracker", "pricing_advisor",
            "customer_insights", "demand_forecaster"
        ),
        ArchetypeType.ARTISAN to setOf(
            "job_quotation_builder", "material_cost_tracker", "payment_reminder",
            "tool_inventory", "skill_pricing_advisor", "project_profitability",
            "customer_portfolio", "raw_material_inventory", "batch_tracker",
            "inventory_tracker", "supplier_matcher"
        ),
        ArchetypeType.SERVICE_PROVIDER to setOf(
            "service_menu_builder", "job_card_tracker", "appointment_scheduler",
            "parts_inventory", "pricing_advisor", "client_database",
            "service_history", "customer_retention", "tool_inventory",
            "customer_insights", "competitor_tracker", "rating_system"
        ),
        ArchetypeType.TRANSPORT_OPERATOR to setOf(
            "fare_tracker", "fuel_efficiency_tracker", "maintenance_log",
            "hire_purchase_tracker", "sos_safety_button", "daily_earnings_tracker",
            "route_optimizer", "vehicle_maintenance", "insurance_tracker",
            "matatu_operations", "boda_boda_router", "fare_intelligence"
        ),
        ArchetypeType.CROP_FARMER to setOf(
            "seasonal_budget_planner", "input_cost_tracker", "harvest_tracker",
            "produce_price_broadcaster", "weather_forecast", "post_harvest_loss_tracker",
            "yield_predictor", "harvest_timing_optimizer", "storage_decision_calculator",
            "market_day_planner"
        ),
        ArchetypeType.LIVESTOCK_KEEPER to setOf(
            "production_tracker", "feed_cost_tracker", "animal_health_log",
            "mortality_tracker", "breeding_calendar", "vet_schedule_reminder",
            "profit_per_animal", "market_price_broadcaster"
        ),
        ArchetypeType.FISHER to setOf(
            "catch_tracker", "equipment_maintenance_log", "weather_alerts",
            "market_price_broadcaster", "preservation_tracker",
            "crew_payment_tracker", "seasonal_planner", "fishing_log"
        ),
        ArchetypeType.AGENT_BROKER to setOf(
            "transaction_logger", "float_monitor", "commission_calculator",
            "fraud_detector", "daily_reconciliation", "customer_insights",
            "transaction_analytics"
        ),
        ArchetypeType.DIGITAL_WORKER to setOf(
            "project_tracker", "invoice_manager", "platform_earnings_tracker",
            "client_database", "expense_tracker", "digital_service_pricing"
        ),
        ArchetypeType.CASUAL_LABORER to setOf(
            "daily_wage_tracker", "work_calendar", "payment_reminder",
            "savings_micro", "employer_database", "health_risk_alerts",
            "emergency_fund", "job_matcher"
        ),
        ArchetypeType.COMMUNITY_CARE_WORKER to setOf(
            "booking_manager", "equipment_tracker", "invoice_manager",
            "client_portfolio", "safety_check_in", "expense_tracker",
            "savings_goals"
        )
    )

    // ══════════════════════════════════════════════════════════
    //  Archetype relationships
    // ══════════════════════════════════════════════════════════

    val oftenCombinedWith: Map<ArchetypeType, List<ArchetypeType>> = mapOf(
        ArchetypeType.VENDOR to listOf(ArchetypeType.FOOD_SERVICE, ArchetypeType.AGENT_BROKER),
        ArchetypeType.FOOD_SERVICE to listOf(ArchetypeType.VENDOR),
        ArchetypeType.ARTISAN to listOf(ArchetypeType.SERVICE_PROVIDER),
        ArchetypeType.SERVICE_PROVIDER to listOf(ArchetypeType.ARTISAN),
        ArchetypeType.TRANSPORT_OPERATOR to listOf(ArchetypeType.VENDOR),
        ArchetypeType.CROP_FARMER to listOf(ArchetypeType.LIVESTOCK_KEEPER, ArchetypeType.VENDOR),
        ArchetypeType.LIVESTOCK_KEEPER to listOf(ArchetypeType.CROP_FARMER),
        ArchetypeType.FISHER to listOf(ArchetypeType.VENDOR),
        ArchetypeType.AGENT_BROKER to listOf(ArchetypeType.VENDOR),
        ArchetypeType.DIGITAL_WORKER to listOf(ArchetypeType.SERVICE_PROVIDER),
        ArchetypeType.CASUAL_LABORER to listOf(ArchetypeType.CROP_FARMER),
        ArchetypeType.COMMUNITY_CARE_WORKER to listOf(ArchetypeType.SERVICE_PROVIDER)
    )

    val upgradesTo: Map<ArchetypeType, List<ArchetypeType>> = mapOf(
        ArchetypeType.VENDOR to listOf(ArchetypeType.AGENT_BROKER),
        ArchetypeType.CASUAL_LABORER to listOf(ArchetypeType.ARTISAN, ArchetypeType.SERVICE_PROVIDER),
        ArchetypeType.FISHER to listOf(ArchetypeType.VENDOR)
    )

    // ══════════════════════════════════════════════════════════
    //  Public API
    // ══════════════════════════════════════════════════════════

    /**
     * Get all available tools for a worker based on their archetype profile.
     * Combines universal tools + primary archetype tools + secondary archetype tools.
     */
    fun getTools(profile: com.msaidizi.core.model.WorkerArchetypeProfile): Set<String> {
        val baseTools = universalTools.toMutableSet()
        baseTools.addAll(getArchetypeTools(profile.primaryArchetype))
        profile.secondaryArchetypes.forEach { baseTools.addAll(getArchetypeTools(it)) }
        return baseTools
    }

    /**
     * Get tools specific to a single archetype (without universal tools).
     */
    fun getArchetypeTools(archetype: ArchetypeType): Set<String> {
        return archetypeTools[archetype] ?: emptySet()
    }

    /**
     * Get all tools for an archetype including universal tools.
     */
    fun getAllTools(archetype: ArchetypeType): Set<String> {
        return universalTools + getArchetypeTools(archetype)
    }

    /**
     * Map a BusinessType (sub-type) to its primary archetype.
     */
    fun businessTypeToArchetype(type: BusinessType): ArchetypeType {
        return type.archetype
    }

    /**
     * Get all BusinessTypes (sub-types) belonging to an archetype.
     */
    fun getSubTypes(archetype: ArchetypeType): List<BusinessType> {
        return BusinessType.entries.filter { it.archetype == archetype }
    }

    /**
     * Get the onboarding question flow for an archetype.
     * Returns a list of questions in order, with Swahili + English.
     */
    fun getOnboardingQuestions(archetype: ArchetypeType): List<OnboardingQuestion> {
        return when (archetype) {
            ArchetypeType.VENDOR -> vendorQuestions
            ArchetypeType.FOOD_SERVICE -> foodServiceQuestions
            ArchetypeType.ARTISAN -> artisanQuestions
            ArchetypeType.SERVICE_PROVIDER -> serviceProviderQuestions
            ArchetypeType.TRANSPORT_OPERATOR -> transportOperatorQuestions
            ArchetypeType.CROP_FARMER -> cropFarmerQuestions
            ArchetypeType.LIVESTOCK_KEEPER -> livestockKeeperQuestions
            ArchetypeType.FISHER -> fisherQuestions
            ArchetypeType.AGENT_BROKER -> agentBrokerQuestions
            ArchetypeType.DIGITAL_WORKER -> digitalWorkerQuestions
            ArchetypeType.CASUAL_LABORER -> casualLaborerQuestions
            ArchetypeType.COMMUNITY_CARE_WORKER -> communityCareQuestions
        }
    }

    /**
     * Get the visual card choices for sub-type selection within an archetype.
     */
    fun getSubTypeChoices(archetype: ArchetypeType): List<SubTypeChoice> {
        return getSubTypes(archetype).map { bt ->
            SubTypeChoice(
                id = bt.name,
                label = bt.displayName,
                swahiliLabel = bt.swahiliName,
                icon = getSubTypeIcon(bt)
            )
        }
    }

    /**
     * Get the CFO summary for an archetype (what Msaidizi will help with).
     */
    fun getCfoSummary(archetype: ArchetypeType): CfoSummary {
        return when (archetype) {
            ArchetypeType.VENDOR -> CfoSummary(
                english = "I'll help you know your real profit every day, track inventory freshness, find the best market prices, and save without pressure.",
                swahili = "Nitakusaidia kujua faida yako halisi kila siku, kufuatilia ubora wa stock, kupata bei bora za soko, na kuweka akiba bila pressure."
            )
            ArchetypeType.FOOD_SERVICE -> CfoSummary(
                english = "I'll help you know the true cost per plate, track ingredients, reduce food waste, and see which menu items are most profitable.",
                swahili = "Nitakusaidia kujua gharama halisi ya kila sahani, kufuatilia vifungashio, kupunguza upotevu, na kuona vyakula vinavyofaa zaidi."
            )
            ArchetypeType.ARTISAN -> CfoSummary(
                english = "I'll help you price jobs fairly, track material costs, remind you about payments, and know which jobs earn you the most.",
                swahili = "Nitakusaidia kupanga bei vizuri, kufuatilia gharama za vifaa, kukumbusha malipo, na kujua kazi zipi zinalipa zaidi."
            )
            ArchetypeType.SERVICE_PROVIDER -> CfoSummary(
                english = "I'll help you manage appointments, track service history, price your services right, and keep your customers coming back.",
                swahili = "Nitakusaidia kudhibiti miadi, kufuatilia historia ya huduma, kupanga bei sahihi, na kuwafanya wateja warudi."
            )
            ArchetypeType.TRANSPORT_OPERATOR -> CfoSummary(
                english = "I'll help you track every trip and fare, monitor fuel efficiency, manage vehicle payments, and find the best routes and times.",
                swahili = "Nitakusaidia kufuatilia kila safari na nauli, kudhibiti mafuta, kusimamia malipo ya gari, na kupata njia bora."
            )
            ArchetypeType.CROP_FARMER -> CfoSummary(
                english = "I'll help you plan seasonal budgets, track input costs, time your harvest for best prices, and reduce post-harvest losses.",
                swahili = "Nitakusaidia kupanga bajeti ya msimu, kufuatilia gharama, kupanga mavuno kwa bei bora, na kupunguza hasara."
            )
            ArchetypeType.LIVESTOCK_KEEPER -> CfoSummary(
                english = "I'll help you track daily production, manage feed costs, monitor animal health, and know your true profit per animal.",
                swahili = "Nitakusaidia kufuatilia uzalishaji wa kila siku, kudhibiti gharama za chakula, kufuatilia afya ya mifugo, na kujua faida halisi."
            )
            ArchetypeType.FISHER -> CfoSummary(
                english = "I'll help you track every catch and trip, compare prices at landing sites, monitor equipment, and predict the best fishing days.",
                swahili = "Nitakusaidia kufuatilia kila uvuvo na safari, kulinganisha bei bandarini, kudhibiti vifaa, na kutabiri siku bora za kuvua."
            )
            ArchetypeType.AGENT_BROKER -> CfoSummary(
                english = "I'll help you manage float, track commissions, detect fraud, reconcile daily, and optimize transaction patterns.",
                swahili = "Nitakusaidia kudhibiti float, kufuatilia kamisheni, kugundua udanganyifu, kurekebisha kila siku, na kuboresha miamala."
            )
            ArchetypeType.DIGITAL_WORKER -> CfoSummary(
                english = "I'll help you track projects, manage invoices, monitor platform earnings, and plan for irregular income.",
                swahili = "Nitakusaidia kufuatilia miradi, kudhibiti ankara, kufuatilia mapato ya platform, na kupanga mapato yasiyo ya kawaida."
            )
            ArchetypeType.CASUAL_LABORER -> CfoSummary(
                english = "I'll help you track every day's work, know your weekly patterns, remind employers about payment, and save for idle days.",
                swahili = "Nitakusaidia kufuatilia kazi ya kila siku, kujua mifumo ya wiki, kukumbusha malipo, na kuweka akiba ya siku za kukaa."
            )
            ArchetypeType.COMMUNITY_CARE_WORKER -> CfoSummary(
                english = "I'll help you manage bookings, track equipment, send invoices, and plan for irregular event-based income.",
                swahili = "Nitakusaidia kudhibiti bookings, kufuatilia vifaa, kutuma ankara, na kupanga mapato ya matukio."
            )
        }
    }

    // ══════════════════════════════════════════════════════════
    //  Private: Sub-type icons
    // ══════════════════════════════════════════════════════════

    private fun getSubTypeIcon(bt: BusinessType): String = when (bt) {
        BusinessType.MAMA_MBOGA -> "🥬"
        BusinessType.DUKA -> "🏪"
        BusinessType.MACHINGA -> "🏃"
        BusinessType.MITUMBA -> "👕"
        BusinessType.PHONE_ACCESSORIES -> "📱"
        BusinessType.COSMETICS -> "💄"
        BusinessType.HARDWARE_STORE -> "🔨"
        BusinessType.BODA_BODA -> "🏍️"
        BusinessType.TUK_TUK -> "🛺"
        BusinessType.MATATU -> "🚐"
        BusinessType.CART_PUSHER -> "🛒"
        BusinessType.TRUCK_DRIVER -> "🚛"
        BusinessType.MAMA_LISHE -> "🍲"
        BusinessType.HOTELI -> "🏨"
        BusinessType.CHAPATI_SELLER -> "🫓"
        BusinessType.WATER_SELLER -> "💧"
        BusinessType.TRADITIONAL_BREWER -> "🍺"
        BusinessType.FUNDI -> "🔩"
        BusinessType.SALON -> "💇"
        BusinessType.BARBER -> "✂️"
        BusinessType.MAMA_FUO -> "👕"
        BusinessType.TAILOR -> "🧵"
        BusinessType.SHOE_SHINER -> "👞"
        BusinessType.CAR_WASH -> "🚗"
        BusinessType.MKULIMA -> "🌾"
        BusinessType.MVUVI -> "🐟"
        BusinessType.MFUGAJI -> "🐄"
        BusinessType.PRODUCE_BROKER -> "🤝"
        BusinessType.MJENGO -> "🧱"
        BusinessType.MASON -> "🏗️"
        BusinessType.PLUMBER -> "🔧"
        BusinessType.ELECTRICIAN -> "⚡"
        BusinessType.M_PESA -> "💰"
        BusinessType.CYBER_CAFE -> "💻"
        BusinessType.PHONE_REPAIR -> "📱"
        BusinessType.SOCIAL_MEDIA_RESELLER -> "📲"
        BusinessType.JUA_KALI -> "🔧"
        BusinessType.BASKET_WEAVER -> "🧺"
        BusinessType.POTTER -> "🏺"
        BusinessType.WELDER -> "🔥"
        BusinessType.DELIVERY_RIDER -> "📦"
        BusinessType.AUTO_RICKSHAW -> "🛺"
        BusinessType.MATATU_OWNER -> "🚐"
        BusinessType.INFORMAL_TAXI -> "🚕"
        BusinessType.RIDE_HAIL_DRIVER -> "📱"
        BusinessType.MECHANIC -> "🔩"
        BusinessType.CARPENTER -> "🪚"
        BusinessType.PHOTOGRAPHER -> "📷"
        BusinessType.VIDEOGRAPHER -> "🎬"
        BusinessType.DJ -> "🎧"
        BusinessType.MUSICIAN -> "🎵"
        BusinessType.MC_HOST -> "🎤"
        BusinessType.NIGHT_GUARD -> "🛡️"
        BusinessType.WASTE_PICKER -> "♻️"
        BusinessType.DOMESTIC_WORKER -> "🏠"
        BusinessType.NANNY -> "👶"
        BusinessType.ONLINE_SELLER -> "🛒"
        BusinessType.ONLINE_TUTOR -> "📚"
        BusinessType.GRAPHIC_DESIGNER -> "🎨"
        BusinessType.CONTENT_CREATOR -> "📹"
        else -> "📋"
    }

    // ══════════════════════════════════════════════════════════
    //  Onboarding question flows (per archetype)
    // ══════════════════════════════════════════════════════════

    private val vendorQuestions = listOf(
        OnboardingQuestion("Where do you sell?", "Unauza wapi?", "location", listOf("Market stall", "Roadside", "Shop", "Hawking", "Online")),
        OnboardingQuestion("How long in this business?", "Umekuwa ukifanya biashara hii kwa muda gani?", "yearsInBusiness", listOf("< 6 months", "6-12 months", "1-2 years", "2-5 years", "5+ years")),
        OnboardingQuestion("Where do you buy stock?", "Unanunua stock yako wapi?", "supplier", listOf("Wholesale market", "Direct from farm", "Distributor", "Other")),
        OnboardingQuestion("How often do you restock?", "Unanunua mara ngapi kwa wiki?", "restockFrequency", listOf("Daily", "2-3 times/week", "Weekly", "Less often")),
        OnboardingQuestion("Do your goods spoil quickly?", "Bidhaa zako zinaharibika haraka?", "perishable", listOf("Yes", "No", "Sometimes")),
        OnboardingQuestion("Do you use M-Pesa?", "Unatumia M-Pesa?", "paymentMethod", listOf("M-Pesa only", "Cash only", "Both")),
        OnboardingQuestion("Do you give credit to customers?", "Una wateja wanaokopa?", "hasCredit", listOf("Yes", "No"))
    )

    private val foodServiceQuestions = listOf(
        OnboardingQuestion("What food do you cook and sell?", "Unapika na kuuza chakula gani?", "menuType", listOf("Ugali + Nyama", "Chapati", "Chips", "Nyama Choma", "Mandazi", "Mixed menu")),
        OnboardingQuestion("Where do you sell?", "Unauza wapi?", "location", listOf("Stall/kibanda", "Shop", "Mobile", "Home delivery")),
        OnboardingQuestion("What fuel do you use?", "Unapika na nini?", "fuelType", listOf("Charcoal", "Gas", "Firewood", "Electricity")),
        OnboardingQuestion("Do you know cost per plate?", "Unajua bei ya chakula kimoja?", "knowsCostPerPlate", listOf("Yes", "No", "Roughly")),
        OnboardingQuestion("Do you sell via delivery apps?", "Unauza kupitia Glovo au Bolt Food?", "deliveryApps", listOf("Yes", "No")),
        OnboardingQuestion("How many customers per day?", "Wateja wangapi kwa siku?", "dailyCustomers", listOf("< 20", "20-50", "50-100", "100+"))
    )

    private val artisanQuestions = listOf(
        OnboardingQuestion("What products do you make?", "Unatengeneza bidhaa gani?", "products", listOf("Furniture", "Metalwork", "Clothing", "Pottery", "Jewelry", "Other")),
        OnboardingQuestion("Do you have a workshop?", "Una workshop?", "hasWorkshop", listOf("Own workshop", "Shared workshop", "Roadside", "Client's location")),
        OnboardingQuestion("Do you have employees?", "Una wafanyakazi?", "employees", listOf("Alone", "1-2 helpers", "3-5 workers", "5+")),
        OnboardingQuestion("How do you price jobs?", "Unaweka bei vipi?", "pricingMethod", listOf("Materials + labor", "Market rate", "Negotiate", "Guess")),
        OnboardingQuestion("When do you get paid?", "Unalipwa lini?", "paymentTerms", listOf("Upfront", "After delivery", "50/50", "Installments")),
        OnboardingQuestion("How many jobs per week?", "Kazi ngapi kwa wiki?", "jobsPerWeek", listOf("1-2", "3-5", "6-10", "10+"))
    )

    private val serviceProviderQuestions = listOf(
        OnboardingQuestion("What service do you provide?", "Unatoa huduma gani?", "serviceType", listOf("Beauty", "Repair", "Cleaning", "Automotive", "Home services", "Other")),
        OnboardingQuestion("Where do you work?", "Unafanya kazi wapi?", "workLocation", listOf("Own shop", "Client's home", "Roadside", "Mobile")),
        OnboardingQuestion("Do you have a permanent workplace?", "Una sehemu ya kazi ya kudumu?", "hasPermanentLocation", listOf("Yes", "No", "Sometimes")),
        OnboardingQuestion("How do clients find you?", "Unapata wateja wapi?", "clientSource", listOf("Walk-in", "Referrals", "Social media", "Platform")),
        OnboardingQuestion("Do you use appointments?", "Unatumia miadi?", "usesAppointments", listOf("Yes, mostly", "Walk-in mostly", "Mix of both")),
        OnboardingQuestion("Do you sell parts/products too?", "Unauza pia vipuri?", "sellsParts", listOf("Yes", "No"))
    )

    private val transportOperatorQuestions = listOf(
        OnboardingQuestion("What type of transport?", "Unafanya kazi gani ya usafiri?", "vehicleType", listOf("Boda Boda", "Tuk-tuk", "Matatu", "Taxi", "Cart", "Truck")),
        OnboardingQuestion("Do you own the vehicle?", "Una gari yako mwenyewe?", "ownership", listOf("Own", "Hire-purchase", "Renting", "Employed")),
        OnboardingQuestion("What area/route?", "Unafanya kazi wapi?", "route", listOf("CBD", "Residential", "Inter-town", "Market area")),
        OnboardingQuestion("How many hours per day?", "Masaa ngapi kwa siku?", "hoursPerDay", listOf("< 6", "6-8", "8-10", "10+")),
        OnboardingQuestion("Do you have insurance?", "Una bima?", "hasInsurance", listOf("Yes", "No")),
        OnboardingQuestion("Do you pay installments?", "Unalipia pikipisi/gari?", "hasHirePurchase", listOf("Yes", "No", "Already paid off"))
    )

    private val cropFarmerQuestions = listOf(
        OnboardingQuestion("What crops do you grow?", "Unalima mazao gani?", "crops", listOf("Maize", "Beans", "Rice", "Vegetables", "Coffee", "Tea", "Sugarcane", "Other")),
        OnboardingQuestion("How much land?", "Una ekari/ngazi ngapi?", "landSize", listOf("< 1 acre", "1-2 acres", "2-5 acres", "5+ acres")),
        OnboardingQuestion("Rain-fed or irrigated?", "Unalima kwa mvua au umwagiliaji?", "irrigation", listOf("Rain-fed", "Irrigated", "Both")),
        OnboardingQuestion("Do you use fertilizer?", "Unatumia mbolea?", "usesFertilizer", listOf("Yes", "No", "Sometimes")),
        OnboardingQuestion("Where do you sell produce?", "Unauza wapi mazao yako?", "sellLocation", listOf("Local market", "Broker", "Direct to consumer", "Cooperative")),
        OnboardingQuestion("When is your next harvest?", "Mavuno yajayo ni lini?", "nextHarvest", listOf("< 1 month", "1-3 months", "3-6 months", "6+ months"))
    )

    private val livestockKeeperQuestions = listOf(
        OnboardingQuestion("What animals do you keep?", "Unafuga wanyama gani?", "animals", listOf("Dairy cattle", "Poultry", "Goats/sheep", "Pigs", "Bees", "Other")),
        OnboardingQuestion("How many animals?", "Una wanyama wangapi?", "animalCount", listOf("< 10", "10-50", "50-100", "100+")),
        OnboardingQuestion("Do you buy feed or graze?", "Unanunua chakula au unachungisha?", "feedSource", listOf("Buy feed", "Graze", "Both")),
        OnboardingQuestion("Daily production?", "Unapata maziwa/mai/ngapi kwa siku?", "dailyProduction", listOf("Milk (liters)", "Eggs (count)", "None yet")),
        OnboardingQuestion("Do you use a vet?", "Unatumia daktari wa mifugo?", "usesVet", listOf("Yes, regularly", "Sometimes", "Never")),
        OnboardingQuestion("Any recent animal deaths?", "Una mnyama amekufa hivi karibuni?", "recentMortality", listOf("Yes", "No"))
    )

    private val fisherQuestions = listOf(
        OnboardingQuestion("Wild catch or fish farming?", "Unavua samaki au unafuga?", "fishingType", listOf("Wild catch", "Fish farming", "Both")),
        OnboardingQuestion("Where do you fish?", "Unavua wapi?", "fishingLocation", listOf("Lake Victoria", "Indian Ocean", "Fish pond", "River")),
        OnboardingQuestion("What method?", "Unatumia njia gani?", "method", listOf("Nets", "Hooks/line", "Trap", "Cage")),
        OnboardingQuestion("Do you own a boat?", "Una boti yako?", "boatOwnership", listOf("Own boat", "Rent", "No boat")),
        OnboardingQuestion("What species?", "Samaki wako wa aina gani?", "species", listOf("Nile perch", "Tilapia", "Omena/dagaa", "Tuna", "Mixed")),
        OnboardingQuestion("Do you dry/smoke fish?", "Unaoka au kukausha samaki?", "preserves", listOf("Yes", "No"))
    )

    private val agentBrokerQuestions = listOf(
        OnboardingQuestion("What kind of agent/broker?", "Biashara yako ni ya aina gani?", "agentType", listOf("M-Pesa Agent", "Other mobile money", "Forex bureau", "Money lender", "Produce broker")),
        OnboardingQuestion("Where do you operate?", "Unafanya kazi wapi?", "location", listOf("Shop", "Market", "Street", "Home")),
        OnboardingQuestion("How many transactions per day?", "Miamala ngapi kwa siku?", "dailyTransactions", listOf("< 20", "20-50", "50-100", "100+")),
        OnboardingQuestion("What's your typical float?", "Float yako ya kawaida ni ngapi?", "floatAmount", listOf("< 10K", "10-50K", "50-100K", "100K+")),
        OnboardingQuestion("Do you sell airtime too?", "Unauza pia airtime?", "sellsAirtime", listOf("Yes", "No")),
        OnboardingQuestion("When are you busiest?", "Wakati wako wa busy ni lini?", "peakHours", listOf("Morning", "Midday", "Evening", "All day"))
    )

    private val digitalWorkerQuestions = listOf(
        OnboardingQuestion("What digital work?", "Unafanya kazi gani mtandaoni?", "digitalType", listOf("Graphic design", "Social media", "Content creation", "Data entry", "Web development", "Online selling", "Other")),
        OnboardingQuestion("Where do you work?", "Unafanya kazi wapi?", "workLocation", listOf("Home", "Cyber cafe", "Co-working space", "Cafe")),
        OnboardingQuestion("What devices?", "Unatumia vifaa gani?", "devices", listOf("Phone only", "Laptop", "Phone + Laptop", "Desktop")),
        OnboardingQuestion("How do you find clients?", "Unapata wateja wapi?", "clientSource", listOf("Social media", "Platforms (Fiverr/Upwork)", "Referrals", "Website")),
        OnboardingQuestion("When do you get paid?", "Unalipwa lini?", "paymentTerms", listOf("Per project", "Monthly retainer", "Per hour", "After delivery")),
        OnboardingQuestion("Local or international clients?", "Wateja wa ndani au nje?", "clientLocation", listOf("Local", "International", "Both"))
    )

    private val casualLaborerQuestions = listOf(
        OnboardingQuestion("What kind of work?", "Unafanya kazi gani?", "workType", listOf("Construction", "Farm labor", "Domestic work", "Loading/carrying", "Guard/security", "Other")),
        OnboardingQuestion("How do you find work?", "Unapata kazi vipi?", "jobSource", listOf("Stage/labor market", "Contacts/phone", "Employer calls", "Walking around")),
        OnboardingQuestion("What's your daily rate?", "Bei yako ya kwa siku ni ngapi?", "dailyRate", listOf("< 500", "500-1000", "1000-2000", "2000+")),
        OnboardingQuestion("Days worked per week?", "Siku ngapi kwa wiki?", "daysPerWeek", listOf("1-2", "3-4", "5-6", "Every day")),
        OnboardingQuestion("When do you get paid?", "Unalipwa lini?", "paymentTiming", listOf("End of day", "End of week", "End of month", "Delayed sometimes")),
        OnboardingQuestion("Do you have insurance?", "Una NHIF au bima?", "hasInsurance", listOf("Yes", "No"))
    )

    private val communityCareQuestions = listOf(
        OnboardingQuestion("What community work?", "Unafanya kazi gani ya jamii?", "communityType", listOf("Entertainment (MC/DJ/Music)", "Photography/Video", "Waste management", "Security", "Education/tutoring", "Health work", "Other")),
        OnboardingQuestion("How do you get clients?", "Unapata wateja/makontrakta vipi?", "clientSource", listOf("Word of mouth", "Social media", "Contracts", "Referrals")),
        OnboardingQuestion("Do you own equipment?", "Una vifaa vyako?", "hasEquipment", listOf("Yes, all", "Some", "No, rent")),
        OnboardingQuestion("Events per month?", "Matukio ngapi kwa mwezi?", "eventsPerMonth", listOf("< 2", "2-5", "5-10", "10+")),
        OnboardingQuestion("Typical fee?", "Bei yako ya kawaida ni ngapi?", "typicalFee", listOf("< 1000", "1000-5000", "5000-20000", "20000+")),
        OnboardingQuestion("Permanent or freelance?", "Unafanya kazi ya kudumu au ya muda?", "employmentType", listOf("Permanent", "Freelance", "Both"))
    )
}

// ══════════════════════════════════════════════════════════
//  Data classes for onboarding
// ══════════════════════════════════════════════════════════

data class OnboardingQuestion(
    val english: String,
    val swahili: String,
    val field: String,
    val choices: List<String> = emptyList()
)

data class SubTypeChoice(
    val id: String,
    val label: String,
    val swahiliLabel: String,
    val icon: String
)

data class CfoSummary(
    val english: String,
    val swahili: String
)
