package com.msaidizi.app.agent

import com.msaidizi.app.core.model.DialectRegion
import com.msaidizi.app.core.model.Transaction
import com.msaidizi.app.core.model.TransactionType
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Value Delivery System — ensures workers get value from Day 1.
 *
 * ## Value-First Principle
 * Workers must get 5-10x more value from Msaidizi than their data
 * is worth to Biashara Intelligence. If a worker's data generates
 * KSh 500/month in intelligence revenue, the worker must save or
 * earn at least KSh 2,500-5,000/month from using Msaidizi.
 *
 * ## Value Timeline
 *
 * ### Day 1: "Msaidizi is your business brain"
 * - Voice bookkeeping (saves 5+ hrs/week vs. paper notebooks)
 * - Daily profit report ("Faida yako leo ni KSh 850")
 * - Restock alerts ("Nyanya zimebaki 5 — nunua kesho")
 * - Price check ("Nyanya ni KSh 80-100 sokoni")
 * **Estimated value: KSh 3,000-8,000/month**
 *
 * ### Week 1: "This is actually helping my business"
 * - Price intelligence ("Supplier wako anauza 8% above average")
 * - Supplier comparison
 * - Business health score ("Afya ya biashara: 65/100")
 * - Customer pattern insights ("Jumamosi ni siku bora 2x")
 * **Estimated incremental value: KSh 2,000-5,000/month**
 *
 * ### Month 1: "I can't run my business without this"
 * - Credit readiness ("Biashara yako ina alama 65 — mkopo unawezekana")
 * - Formalization pathway
 * - Financial products access
 * - Growth recommendations
 * **Estimated incremental value: KSh 5,000-15,000/month**
 *
 * @see BusinessAgent for transaction data
 * @see AnalysisAgent for statistical analysis
 * @see BiasharaSync for backend intelligence
 */
object ValueDelivery {

    // ═══════════════════════════════════════════════════════════════
    // DAY 1 VALUE: Immediate utility from first use
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get Day 1 value items for a worker.
     *
     * These are available IMMEDIATELY after the first few transactions.
     * No waiting period, no data accumulation needed.
     *
     * @param worker Worker context with recent transactions
     * @return List of value items the worker can use today
     */
    fun getDay1Value(worker: Worker): List<ValueItem> {
        val items = mutableListOf<ValueItem>()

        // 1. Voice bookkeeping — always available
        items.add(
            ValueItem(
                id = "day1_voice_bookkeeping",
                title = "Voice Bookkeeping",
                titleSw = "Kuhifadhi kwa Sauti",
                description = "Speak your sales — Msaidizi records them instantly",
                descriptionSw = "Ongeza mauzo yako — Msaidizi yanarekodi mara moja",
                category = ValueCategory.BOOKKEEPING,
                estimatedValueKsh = 3000,  // 5 hrs/week × KSh 150/hr
                priority = 1,
                available = true
            )
        )

        // 2. Daily profit report
        if (worker.todaySales > 0) {
            items.add(
                ValueItem(
                    id = "day1_profit_report",
                    title = "Daily Profit Report",
                    titleSw = "Ripoti ya Faida ya Leo",
                    description = "Faida yako leo ni KSh ${worker.todayProfit.toInt()} (margin ${worker.todayMargin.toInt()}%)",
                    descriptionSw = "Faida yako leo ni KSh ${worker.todayProfit.toInt()} (margin ${worker.todayMargin.toInt()}%)",
                    category = ValueCategory.ANALYTICS,
                    estimatedValueKsh = 2000,
                    priority = 2,
                    available = true
                )
            )
        }

        // 3. Restock alerts
        if (worker.lowStockItems.isNotEmpty()) {
            val itemNames = worker.lowStockItems.joinToString(", ")
            items.add(
                ValueItem(
                    id = "day1_restock_alert",
                    title = "Restock Alert",
                    titleSw = "Tahadhari ya Kununua Zaidi",
                    description = "Items running low: $itemNames",
                    descriptionSw = "Bidhaa zinazokaribia kuisha: $itemNames",
                    category = ValueCategory.INVENTORY,
                    estimatedValueKsh = 1500,
                    priority = 3,
                    available = true
                )
            )
        }

        // 4. Expense tracking
        if (worker.todayExpenses > 0) {
            items.add(
                ValueItem(
                    id = "day1_expense_tracking",
                    title = "Expense Tracking",
                    titleSw = "Kufuatilia Matumizi",
                    description = "Today's expenses: KSh ${worker.todayExpenses.toInt()} tracked automatically",
                    descriptionSw = "Matumizi ya leo: KSh ${worker.todayExpenses.toInt()} yamefuatiliwa",
                    category = ValueCategory.BOOKKEEPING,
                    estimatedValueKsh = 1000,
                    priority = 4,
                    available = true
                )
            )
        }

        return items.sortedBy { it.priority }
    }

    // ═══════════════════════════════════════════════════════════════
    // WEEK 1 VALUE: Patterns emerge, insights deepen
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get Week 1 value items.
     *
     * Available after 5-7 days of consistent usage.
     * Requires enough data to detect patterns.
     *
     * @param worker Worker context with weekly transaction history
     * @return List of value items for the first week
     */
    fun getWeek1Value(worker: Worker): List<ValueItem> {
        val items = mutableListOf<ValueItem>()

        // 1. Price intelligence
        if (worker.weeklyTransactions >= 10) {
            items.add(
                ValueItem(
                    id = "week1_price_intel",
                    title = "Price Intelligence",
                    titleSw = "Bei ya Sokoni",
                    description = "Compare your prices with market averages",
                    descriptionSw = "Linganisha bei yako na wastani wa soko",
                    category = ValueCategory.MARKET_INTEL,
                    estimatedValueKsh = 2000,
                    priority = 1,
                    available = true
                )
            )
        }

        // 2. Supplier comparison
        if (worker.supplierCount >= 2) {
            items.add(
                ValueItem(
                    id = "week1_supplier_compare",
                    title = "Supplier Comparison",
                    titleSw = "Kulinganisha Wauzaji",
                    description = "Your cheapest vs. most expensive supplier",
                    descriptionSw = "Mnunuzi wa bei nafuu dhidi ya wa bei ghali",
                    category = ValueCategory.MARKET_INTEL,
                    estimatedValueKsh = 1500,
                    priority = 2,
                    available = true
                )
            )
        }

        // 3. Business health score
        items.add(
            ValueItem(
                id = "week1_health_score",
                title = "Business Health Score",
                titleSw = "Alama ya Afya ya Biashara",
                description = "Your business health: ${worker.healthScore.toInt()}/100",
                descriptionSw = "Afya ya biashara yako: ${worker.healthScore.toInt()}/100",
                category = ValueCategory.ANALYTICS,
                estimatedValueKsh = 1000,
                priority = 3,
                available = worker.weeklyTransactions >= 5
            )
        )

        // 4. Day-of-week patterns
        if (worker.bestDayOfWeek.isNotEmpty()) {
            items.add(
                ValueItem(
                    id = "week1_day_pattern",
                    title = "Best Selling Days",
                    titleSw = "Siku Bora za Kuuza",
                    description = "Your best day: ${worker.bestDayOfWeek}. Stock up!",
                    descriptionSw = "Siku yako bora: ${worker.bestDayOfWeek}. Jaza stock!",
                    category = ValueCategory.ANALYTICS,
                    estimatedValueKsh = 1500,
                    priority = 4,
                    available = true
                )
            )
        }

        // 5. ABC analysis — which products drive profit
        if (worker.topProduct.isNotEmpty()) {
            items.add(
                ValueItem(
                    id = "week1_top_product",
                    title = "Top Profit Driver",
                    titleSw = "Bidhaa Inayoleta Faida Zaidi",
                    description = "${worker.topProduct} drives most of your profit",
                    descriptionSw = "${worker.topProduct} ndiyo inaleta faida zaidi",
                    category = ValueCategory.ANALYTICS,
                    estimatedValueKsh = 1000,
                    priority = 5,
                    available = true
                )
            )
        }

        return items.sortedBy { it.priority }
    }

    // ═══════════════════════════════════════════════════════════════
    // MONTH 1 VALUE: Credit, formalization, growth
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get Month 1 value items.
     *
     * Available after 30 days of consistent usage.
     * Requires enough history for credit scoring and formalization.
     *
     * @param worker Worker context with monthly transaction history
     * @return List of value items for the first month
     */
    fun getMonth1Value(worker: Worker): List<ValueItem> {
        val items = mutableListOf<ValueItem>()

        // 1. Credit readiness (Alama Score)
        if (worker.activeDays >= 20) {
            val score = worker.alamaScore
            val readiness = when {
                score >= 70 -> "high"
                score >= 50 -> "medium"
                else -> "low"
            }
            items.add(
                ValueItem(
                    id = "month1_credit_readiness",
                    title = "Credit Readiness Score",
                    titleSw = "Alama ya Utayari wa Mkopo",
                    description = "Your Alama Score: $score/100 ($readiness readiness)",
                    descriptionSw = "Alama yako: $score/100 (utayari: $readiness)",
                    category = ValueCategory.FINANCIAL,
                    estimatedValueKsh = 5000,
                    priority = 1,
                    available = true
                )
            )
        }

        // 2. Formalization pathway
        if (worker.formalizationScore >= 50) {
            items.add(
                ValueItem(
                    id = "month1_formalization",
                    title = "Formalization Pathway",
                    titleSw = "Njia ya Kusajili Biashara",
                    description = "Your records qualify you for single business permit",
                    descriptionSw = "Rekodi zako zinakufanya ufaulu kibali cha biashara",
                    category = ValueCategory.FORMALIZATION,
                    estimatedValueKsh = 3000,
                    priority = 2,
                    available = true
                )
            )
        }

        // 3. Financial products access
        if (worker.alamaScore >= 50) {
            items.add(
                ValueItem(
                    id = "month1_financial_products",
                    title = "Financial Products Access",
                    titleSw = "Upatikanaji wa Bidhaa za Kifedha",
                    description = "Based on your score, you may qualify for micro-loans",
                    descriptionSw = "Kulingana na alama yako, unaweza kustahili mkopo",
                    category = ValueCategory.FINANCIAL,
                    estimatedValueKsh = 10000,
                    priority = 3,
                    available = true
                )
            )
        }

        // 4. Growth recommendations
        if (worker.monthlyTransactions >= 50) {
            items.add(
                ValueItem(
                    id = "month1_growth",
                    title = "Growth Recommendations",
                    titleSw = "Mapendekezo ya Ukuaji",
                    description = "Personalized tips to grow your business",
                    descriptionSw = "Vidokezo vya kukuza biashara yako",
                    category = ValueCategory.GROWTH,
                    estimatedValueKsh = 5000,
                    priority = 4,
                    available = true
                )
            )
        }

        // 5. Community benchmarking
        if (worker.workerType != WorkerType.UNKNOWN) {
            items.add(
                ValueItem(
                    id = "month1_benchmark",
                    title = "Peer Benchmarking",
                    titleSw = "Kulinganisha na Wenzako",
                    description = "See how you compare to similar workers in your area",
                    descriptionSw = "Jione ukilinganishwa na wafanyakazi wenzako",
                    category = ValueCategory.MARKET_INTEL,
                    estimatedValueKsh = 2000,
                    priority = 5,
                    available = true
                )
            )
        }

        return items.sortedBy { it.priority }
    }

    // ═══════════════════════════════════════════════════════════════
    // VALUE DELIVERY STATUS: Track what's been delivered
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get the current value delivery status for a worker.
     *
     * Tracks which value items have been delivered, which are pending,
     * and the total estimated value delivered so far.
     *
     * @param worker Worker context
     * @return ValueDeliveryStatus with delivery metrics
     */
    fun getValueStatus(worker: Worker): ValueDeliveryStatus {
        val day1 = getDay1Value(worker)
        val week1 = getWeek1Value(worker)
        val month1 = getMonth1Value(worker)

        val allItems = day1 + week1 + month1
        val availableItems = allItems.filter { it.available }
        val totalEstimatedValue = availableItems.sumOf { it.estimatedValueKsh }

        return ValueDeliveryStatus(
            day1ItemsAvailable = day1.count { it.available },
            day1ItemsTotal = day1.size,
            week1ItemsAvailable = week1.count { it.available },
            week1ItemsTotal = week1.size,
            month1ItemsAvailable = month1.count { it.available },
            month1ItemsTotal = month1.size,
            totalEstimatedValueKsh = totalEstimatedValue,
            currentPhase = when {
                worker.activeDays >= 30 -> ValuePhase.MONTH_1
                worker.activeDays >= 7 -> ValuePhase.WEEK_1
                else -> ValuePhase.DAY_1
            }
        )
    }

    /**
     * Generate a voice-friendly value summary in Swahili.
     *
     * **BCB 108 §1.2:** Clear, concise, concrete.
     * A mama mboga should understand her value in 10 seconds.
     */
    fun generateValueSummary(worker: Worker): String {
        val status = getValueStatus(worker)

        return buildString {
            appendLine("📊 Msaidizi — Thuli ya Biashara Yako")
            appendLine()

            when (status.currentPhase) {
                ValuePhase.DAY_1 -> {
                    appendLine("✅ Leo umerekodi mauzo ${worker.todaySales.toInt()} KSh")
                    if (worker.todayProfit > 0) {
                        appendLine("📈 Faida: ${worker.todayProfit.toInt()} KSh")
                    }
                    appendLine("💡 Okoa muda: saa 5+ kwa wiki (badala ya daftari)")
                }
                ValuePhase.WEEK_1 -> {
                    appendLine("✅ Wiki hii: mauzo ${worker.weeklySales.toInt()} KSh")
                    appendLine("📈 Faida ya wiki: ${worker.weeklyProfit.toInt()} KSh")
                    if (worker.bestDayOfWeek.isNotEmpty()) {
                        appendLine("📅 Siku bora: ${worker.bestDayOfWeek}")
                    }
                }
                ValuePhase.MONTH_1 -> {
                    appendLine("✅ Mwezi huu: mauzo ${worker.monthlySales.toInt()} KSh")
                    appendLine("📈 Faida: ${worker.monthlyProfit.toInt()} KSh")
                    appendLine("⭐ Alama: ${worker.alamaScore}/100")
                }
            }

            appendLine()
            appendLine("💰 Thuli iliyotolewa: ~KSh ${status.totalEstimatedValueKsh}/mwezi")
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// DATA CLASSES
// ═══════════════════════════════════════════════════════════════

/**
 * Worker context for value delivery calculations.
 * Aggregates data from multiple sources for value computation.
 */
data class Worker(
    val id: String = "",
    val name: String = "",              // Worker's name (from bootstrap)
    val assistantName: String = "Msaidizi",  // What worker calls Msaidizi (e.g., "Rafiki")
    val language: String = "sw",
    val dialect: DialectRegion = DialectRegion.STANDARD,
    val onboardedAt: Long = 0L,
    val cfoLevel: CFOLevel = CFOLevel.JUNIOR,  // Based on data maturity
    val activeDays: Int = 0,
    val workerType: WorkerType = WorkerType.UNKNOWN,

    // Day 1 metrics
    val todaySales: Double = 0.0,
    val todayExpenses: Double = 0.0,
    val todayProfit: Double = 0.0,
    val todayMargin: Double = 0.0,
    val lowStockItems: List<String> = emptyList(),

    // Week 1 metrics
    val weeklySales: Double = 0.0,
    val weeklyProfit: Double = 0.0,
    val weeklyTransactions: Int = 0,
    val supplierCount: Int = 0,
    val healthScore: Double = 0.0,
    val bestDayOfWeek: String = "",
    val topProduct: String = "",

    // Month 1 metrics
    val monthlySales: Double = 0.0,
    val monthlyProfit: Double = 0.0,
    val monthlyTransactions: Int = 0,
    val alamaScore: Int = 0,
    val formalizationScore: Double = 0.0
)

/**
 * CFO maturity level — determines how sophisticated the advice is.
 * Starts basic, grows as more data comes in.
 *
 * Like a real CFO joining a new company: they learn the business first,
 * then start providing strategic value.
 */
enum class CFOLevel {
    /** First week — learning the business, recording basics */
    JUNIOR,
    /** Month 1-3 — providing basic insights, daily briefings */
    MID,
    /** Month 3+ — full CFO capabilities, forecasting, credit readiness */
    SENIOR
}

/**
 * A single value item delivered to the worker.
 */
data class ValueItem(
    val id: String,
    val title: String,
    val titleSw: String,
    val description: String,
    val descriptionSw: String,
    val category: ValueCategory,
    val estimatedValueKsh: Int,
    val priority: Int,
    val available: Boolean
)

/**
 * Categories of value delivered to workers.
 */
enum class ValueCategory {
    BOOKKEEPING,     // Voice recording, expense tracking
    ANALYTICS,       // Profit reports, trends, patterns
    INVENTORY,       // Stock management, restock alerts
    MARKET_INTEL,    // Price intelligence, supplier comparison
    FINANCIAL,       // Credit readiness, loan access
    FORMALIZATION,   // Business registration pathway
    GROWTH           // Business expansion recommendations
}

/**
 * Value delivery timeline phases.
 */
enum class ValuePhase {
    DAY_1,      // First day — immediate utility
    WEEK_1,     // First week — patterns emerge
    MONTH_1     // First month — credit & formalization
}

/**
 * Status of value delivery to a worker.
 */
data class ValueDeliveryStatus(
    val day1ItemsAvailable: Int,
    val day1ItemsTotal: Int,
    val week1ItemsAvailable: Int,
    val week1ItemsTotal: Int,
    val month1ItemsAvailable: Int,
    val month1ItemsTotal: Int,
    val totalEstimatedValueKsh: Int,
    val currentPhase: ValuePhase
)
