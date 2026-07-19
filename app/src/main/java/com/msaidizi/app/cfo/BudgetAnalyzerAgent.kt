package com.msaidizi.app.agent.coach

import com.msaidizi.app.core.database.TransactionDao
import com.msaidizi.app.core.model.Transaction
import com.msaidizi.app.core.model.TransactionType
import com.msaidizi.app.core.model.Trend
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Budget Analyzer Agent — First stage of the Financial Coach pipeline.
 *
 * Categorizes transactions, identifies spending patterns, detects anomalies,
 * and produces actionable budget insights for informal workers.
 *
 * ## Pipeline Position
 *   User Input → IntentRouter → [BudgetAnalyzer] → SavingsStrategist → DebtAdvisor → Response
 *
 * ## Academic Foundations
 *
 * ### ECO 201 §2.1 — Consumer Theory
 * - Budget constraint: Σ(p_i × q_i) ≤ Income
 * - We classify spending into categories and check if the worker
 *   is within their implicit budget constraint.
 *
 * ### STA 341 §4 — Estimation & Pattern Detection
 * - Moving averages for spending trend detection
 * - Z-score anomaly detection for unusual spending
 *
 * ### ECO 206 §3 — Microfinance Savings Behavior
 * - Income smoothing: informal workers have irregular income
 * - We detect "good months" vs "bad months" to calibrate advice
 *
 * ## Design Decisions
 * - Pure code, no LLM — runs on-device with 0 RAM overhead
 * - 90% of analysis uses simple arithmetic (mama mbogas need speed)
 * - Categories are Kenyan informal economy specific
 *
 * @param transactionDao Room DAO for transaction data access
 */
class BudgetAnalyzerAgent(
    private val transactionDao: TransactionDao
) {
    companion object {
        private const val TAG = "BudgetAnalyzer"

        /** Spending categories for informal workers */
        val SPENDING_CATEGORIES = mapOf(
            // Business expenses
            "stock" to CategoryType.BUSINESS,
            "bidhaa" to CategoryType.BUSINESS,
            "inventory" to CategoryType.BUSINESS,
            "supplier" to CategoryType.BUSINESS,
            "mzigo" to CategoryType.BUSINESS,

            // Transport
            "fare" to CategoryType.TRANSPORT,
            "matatu" to CategoryType.TRANSPORT,
            "boda" to CategoryType.TRANSPORT,
            "pikipiki" to CategoryType.TRANSPORT,
            "petrol" to CategoryType.TRANSPORT,
            "mafuta" to CategoryType.TRANSPORT,
            "transport" to CategoryType.TRANSPORT,

            // Food & household
            "food" to CategoryType.FOOD,
            "chakula" to CategoryType.FOOD,
            "ugali" to CategoryType.FOOD,
            "nyama" to CategoryType.FOOD,
            "mboga" to CategoryType.FOOD,
            "maziwa" to CategoryType.FOOD,
            "sukari" to CategoryType.FOOD,
            "chai" to CategoryType.FOOD,

            // Rent & utilities
            "rent" to CategoryType.HOUSING,
            "kodi" to CategoryType.HOUSING,
            "stima" to CategoryType.HOUSING,
            "umeme" to CategoryType.HOUSING,
            "majI" to CategoryType.HOUSING,
            "water" to CategoryType.HOUSING,

            // Personal
            "airtime" to CategoryType.PERSONAL,
            "data" to CategoryType.PERSONAL,
            "sabuni" to CategoryType.PERSONAL,
            "nguo" to CategoryType.PERSONAL,
            "salon" to CategoryType.PERSONAL,

            // Education
            "school" to CategoryType.EDUCATION,
            "shule" to CategoryType.EDUCATION,
            "fees" to CategoryType.EDUCATION,
            "kitabu" to CategoryType.EDUCATION,

            // Health
            "dawa" to CategoryType.HEALTH,
            "hospital" to CategoryType.HEALTH,
            "daktari" to CategoryType.HEALTH,

            // Giving
            "sadaka" to CategoryType.GIVING,
            "tithe" to CategoryType.GIVING,
            "zaka" to CategoryType.GIVING,
            "msaada" to CategoryType.GIVING
        )

        /** Anomaly detection: z-score threshold */
        private const val ANOMALY_Z_THRESHOLD = 2.0
    }

    // ═══════════════════════════════════════════════════════════════
    // ECO 201 §2.1 — SPENDING CATEGORIZATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Categorize all expenses for a given period.
     *
     * Maps each transaction item to a spending category using keyword
     * matching against the SPENDING_CATEGORIES map. This is the foundation
     * for all budget analysis.
     *
     * @param days Number of days to analyze
     * @return Map of category → total spending + list of items
     */
    suspend fun categorizeSpending(days: Int = 30): SpendingBreakdown {
        val endDate = LocalDate.now()
        val startDate = endDate.minusDays(days.toLong())
        val startEpoch = startDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val endEpoch = endDate.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)

        // Fetch all expense and purchase transactions
        val expenses = transactionDao.getTransactionsByTypeAndDateRange(
            type = TransactionType.EXPENSE.name,
            startEpoch = startEpoch,
            endEpoch = endEpoch
        )
        val purchases = transactionDao.getTransactionsByTypeAndDateRange(
            type = TransactionType.PURCHASE.name,
            startEpoch = startEpoch,
            endEpoch = endEpoch
        )

        val allSpending = expenses + purchases
        val totalSpending = allSpending.sumOf { it.totalAmount }

        // Categorize each transaction
        val categoryMap = mutableMapOf<CategoryType, MutableList<Transaction>>()
        val uncategorized = mutableListOf<Transaction>()

        for (txn in allSpending) {
            val category = classifyCategory(txn.item.lowercase())
            if (category != null) {
                categoryMap.getOrPut(category) { mutableListOf() }.add(txn)
            } else {
                uncategorized.add(txn)
            }
        }

        // Build category breakdown
        val categories = categoryMap.map { (type, txns) ->
            val total = txns.sumOf { it.totalAmount }
            CategoryBreakdown(
                category = type,
                totalAmount = total,
                percentage = if (totalSpending > 0) (total / totalSpending * 100).roundToInt() else 0,
                transactionCount = txns.size,
                topItems = txns.groupBy { it.item }
                    .mapValues { it.value.sumOf { t -> t.totalAmount } }
                    .entries.sortedByDescending { it.value }
                    .take(3)
                    .map { ItemSpend(it.key, it.value) }
            )
        }.sortedByDescending { it.totalAmount }

        Timber.d(TAG, "Categorized %d transactions into %d categories (total KSh %.0f)",
            allSpending.size, categories.size, totalSpending)

        return SpendingBreakdown(
            totalSpending = totalSpending,
            categories = categories,
            uncategorizedAmount = uncategorized.sumOf { it.totalAmount },
            uncategorizedCount = uncategorized.size,
            periodDays = days
        )
    }

    /**
     * Classify a transaction item into a spending category.
     * Uses keyword matching — no LLM needed.
     */
    private fun classifyCategory(itemLower: String): CategoryType? {
        for ((keyword, category) in SPENDING_CATEGORIES) {
            if (itemLower.contains(keyword)) {
                return category
            }
        }
        return null
    }

    // ═══════════════════════════════════════════════════════════════
    // STA 341 §4 — ANOMALY DETECTION: Unusual spending
    // ═══════════════════════════════════════════════════════════════

    /**
     * Detect anomalous spending using z-score analysis.
     *
     * A z-score measures how many standard deviations a data point
     * is from the mean:
     *   z = (x - μ) / σ
     *
     * Transactions with |z| > 2.0 are flagged as anomalies.
     *
     * @param days Number of days to analyze
     * @return List of anomalous transactions with their z-scores
     */
    suspend fun detectSpendingAnomalies(days: Int = 30): List<SpendingAnomaly> {
        val endDate = LocalDate.now()
        val startDate = endDate.minusDays(days.toLong())
        val startEpoch = startDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val endEpoch = endDate.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)

        val expenses = transactionDao.getTransactionsByTypeAndDateRange(
            type = TransactionType.EXPENSE.name,
            startEpoch = startEpoch,
            endEpoch = endEpoch
        )
        val purchases = transactionDao.getTransactionsByTypeAndDateRange(
            type = TransactionType.PURCHASE.name,
            startEpoch = startEpoch,
            endEpoch = endEpoch
        )

        val allSpending = expenses + purchases
        if (allSpending.size < 5) return emptyList()

        val amounts = allSpending.map { it.totalAmount }
        val mean = amounts.average()
        val stdDev = sqrt(amounts.map { (it - mean).pow(2) }.average())

        if (stdDev == 0.0) return emptyList()

        return allSpending.mapNotNull { txn ->
            val zScore = (txn.totalAmount - mean) / stdDev
            if (abs(zScore) > ANOMALY_Z_THRESHOLD) {
                SpendingAnomaly(
                    transaction = txn,
                    zScore = zScore,
                    severity = when {
                        abs(zScore) > 3.0 -> AnomalySeverity.HIGH
                        abs(zScore) > 2.5 -> AnomalySeverity.MEDIUM
                        else -> AnomalySeverity.LOW
                    },
                    message = if (zScore > 0) {
                        "Hii ni kubwa kuliko kawaida — KSh ${txn.totalAmount.roundToInt()} " +
                        "(wastani ni KSh ${mean.roundToInt()})"
                    } else {
                        "Hii ni ndogo kuliko kawaida — KSh ${txn.totalAmount.roundToInt()}"
                    }
                )
            } else null
        }.sortedByDescending { abs(it.zScore) }
    }

    // ═══════════════════════════════════════════════════════════════
    // STA 244 §10.1 — SPENDING TRENDS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Analyze spending trends over time using moving averages.
     *
     * Compares this week's spending to last week's per category.
     * Identifies which categories are growing or shrinking.
     *
     * @return List of category trends
     */
    suspend fun analyzeSpendingTrends(days: Int = 14): List<CategoryTrend> {
        val endDate = LocalDate.now()
        val midDate = endDate.minusDays((days / 2).toLong())
        val startDate = endDate.minusDays(days.toLong())

        val recentStart = midDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val olderStart = startDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val endEpoch = endDate.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)

        val recentExpenses = transactionDao.getTransactionsByTypeAndDateRange(
            TransactionType.EXPENSE.name, recentStart, endEpoch
        ) + transactionDao.getTransactionsByTypeAndDateRange(
            TransactionType.PURCHASE.name, recentStart, endEpoch
        )

        val olderExpenses = transactionDao.getTransactionsByTypeAndDateRange(
            TransactionType.EXPENSE.name, olderStart, recentStart
        ) + transactionDao.getTransactionsByTypeAndDateRange(
            TransactionType.PURCHASE.name, olderStart, recentStart
        )

        // Group by category
        val recentByCategory = recentExpenses.groupBy { classifyCategory(it.item.lowercase()) ?: CategoryType.OTHER }
        val olderByCategory = olderExpenses.groupBy { classifyCategory(it.item.lowercase()) ?: CategoryType.OTHER }

        val allCategories = (recentByCategory.keys + olderByCategory.keys).toSet()

        return allCategories.map { category ->
            val recentTotal = recentByCategory[category]?.sumOf { it.totalAmount } ?: 0.0
            val olderTotal = olderByCategory[category]?.sumOf { it.totalAmount } ?: 0.0

            val changePercent = if (olderTotal > 0) {
                ((recentTotal - olderTotal) / olderTotal * 100).roundToInt()
            } else if (recentTotal > 0) 100 else 0

            val trend = when {
                changePercent > 20 -> TrendDirection.INCREASING
                changePercent < -20 -> TrendDirection.DECREASING
                else -> TrendDirection.STABLE
            }

            CategoryTrend(
                category = category,
                recentAmount = recentTotal,
                previousAmount = olderTotal,
                changePercent = changePercent,
                direction = trend
            )
        }.sortedByDescending { abs(it.changePercent) }
    }

    // ═══════════════════════════════════════════════════════════════
    // ECO 206 §3 — INCOME PATTERN DETECTION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Detect income patterns — daily, weekly, seasonal.
     *
     * Informal workers have irregular income. Understanding the pattern
     * is essential for realistic savings advice.
     *
     * @param days Number of days to analyze
     * @return IncomePattern with daily averages and variability
     */
    suspend fun detectIncomePattern(days: Int = 30): IncomePattern {
        val endDate = LocalDate.now()
        val startDate = endDate.minusDays(days.toLong())
        val startEpoch = startDate.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val endEpoch = endDate.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)

        val sales = transactionDao.getTransactionsByTypeAndDateRange(
            TransactionType.SALE.name, startEpoch, endEpoch
        )

        // Daily income aggregation
        val dailyIncome = sales
            .groupBy { it.createdAt / 86400 }
            .mapValues { it.value.sumOf { t -> t.totalAmount } }
            .values.toList()

        if (dailyIncome.isEmpty()) {
            return IncomePattern(
                averageDaily = 0.0,
                medianDaily = 0.0,
                variability = 0.0,
                bestDay = "N/A",
                worstDay = "N/A",
                incomeStability = IncomeStability.NO_DATA
            )
        }

        val mean = dailyIncome.average()
        val sorted = dailyIncome.sorted()
        val median = if (sorted.size % 2 == 0) {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        } else {
            sorted[sorted.size / 2]
        }

        val stdDev = sqrt(dailyIncome.map { (it - mean).pow(2) }.average())
        val cv = if (mean > 0) stdDev / mean else 0.0 // Coefficient of variation

        // Day of week analysis
        val dayNames = listOf("Jumatatu", "Jumanne", "Jumatano", "Alhamisi", "Ijumaa", "Jumamosi", "Jumapili")
        val dayBuckets = Array(7) { mutableListOf<Double>() }
        sales.forEach { txn ->
            val date = java.time.Instant.ofEpochSecond(txn.createdAt).atZone(java.time.ZoneOffset.UTC).toLocalDate()
            dayBuckets[date.dayOfWeek.value - 1].add(txn.totalAmount)
        }
        val dayAverages = dayBuckets.map { if (it.isNotEmpty()) it.average() else 0.0 }
        val bestDayIndex = dayAverages.indices.maxByOrNull { dayAverages[it] } ?: 0
        val worstDayIndex = dayAverages.indices.filter { dayAverages[it] > 0 }.minByOrNull { dayAverages[it] } ?: 0

        // Stability classification
        val stability = when {
            cv < 0.3 -> IncomeStability.STABLE
            cv < 0.6 -> IncomeStability.MODERATE
            cv < 1.0 -> IncomeStability.VOLATILE
            else -> IncomeStability.HIGHLY_VOLATILE
        }

        Timber.d(TAG, "Income pattern: avg=KSh %.0f, CV=%.2f, stability=%s", mean, cv, stability)

        return IncomePattern(
            averageDaily = mean,
            medianDaily = median,
            variability = cv,
            bestDay = dayNames[bestDayIndex],
            worstDay = dayNames[worstDayIndex],
            incomeStability = stability,
            dayOfWeekAverages = dayNames.zip(dayAverages).toMap()
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // BUDGET GENERATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Generate a suggested budget based on historical spending and income.
     *
     * Uses the 50/30/20 rule adapted for informal workers:
     * - 50% business expenses (stock, transport)
     * - 30% living expenses (food, rent, personal)
     * - 20% savings + giving
     *
     * @param language Output language
     * @return Budget recommendation in Swahili or English
     */
    suspend fun generateBudgetAdvice(language: String = "sw"): String {
        val breakdown = categorizeSpending(30)
        val incomePattern = detectIncomePattern(30)

        if (incomePattern.averageDaily == 0.0) {
            return if (language == "sw") {
                "📝 Bado sijatosha data. Rekodi mauzo na matumizi yako kwa wiki 2, " +
                "kisha nitakupa bajeti yako."
            } else {
                "📝 Not enough data yet. Record your sales and expenses for 2 weeks, " +
                "then I'll create your budget."
            }
        }

        val monthlyIncome = incomePattern.averageDaily * 30
        val recommendedBusiness = monthlyIncome * 0.50
        val recommendedLiving = monthlyIncome * 0.30
        val recommendedSavings = monthlyIncome * 0.20

        return buildString {
            if (language == "sw") {
                appendLine("📋 Bajeti Yako ya Mwezi:")
                appendLine()
                appendLine("💰 Mapato: KSh ${formatAmount(monthlyIncome)}/mwezi")
                appendLine()
                appendLine("📦 Biashara (50%): KSh ${formatAmount(recommendedBusiness)}")
                appendLine("🏠 Maisha (30%): KSh ${formatAmount(recommendedLiving)}")
                appendLine("🏦 Akiba (20%): KSh ${formatAmount(recommendedSavings)}")

                if (breakdown.totalSpending > monthlyIncome * 0.8) {
                    appendLine()
                    appendLine("⚠️ Matumizi yako ni karibu na mapato. Jaribu kupunguza matumizi ya biashara.")
                }
            } else {
                appendLine("📋 Your Monthly Budget:")
                appendLine()
                appendLine("💰 Income: KSh ${formatAmount(monthlyIncome)}/month")
                appendLine()
                appendLine("📦 Business (50%): KSh ${formatAmount(recommendedBusiness)}")
                appendLine("🏠 Living (30%): KSh ${formatAmount(recommendedLiving)}")
                appendLine("🏦 Savings (20%): KSh ${formatAmount(recommendedSavings)}")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // UTILITY
    // ═══════════════════════════════════════════════════════════════

    private fun formatAmount(amount: Double): String {
        return if (amount >= 1_000_000) {
            String.format("%.1fM", amount / 1_000_000.0)
        } else if (amount >= 1_000) {
            String.format("%,.0f", amount)
        } else {
            String.format("%.0f", amount)
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// DATA CLASSES — Budget Analyzer outputs
// ═══════════════════════════════════════════════════════════════

enum class CategoryType {
    BUSINESS,      // stock, inventory, supplier
    TRANSPORT,     // fare, matatu, boda, petrol
    FOOD,          // chakula, ugali, nyama
    HOUSING,       // rent, kodi, stima
    PERSONAL,      // airtime, data, sabuni
    EDUCATION,     // school, fees
    HEALTH,        // dawa, hospital
    GIVING,        // sadaka, tithe
    OTHER          // uncategorized
}

data class SpendingBreakdown(
    val totalSpending: Double,
    val categories: List<CategoryBreakdown>,
    val uncategorizedAmount: Double,
    val uncategorizedCount: Int,
    val periodDays: Int
)

data class CategoryBreakdown(
    val category: CategoryType,
    val totalAmount: Double,
    val percentage: Int,
    val transactionCount: Int,
    val topItems: List<ItemSpend>
)

data class ItemSpend(
    val item: String,
    val amount: Double
)

data class SpendingAnomaly(
    val transaction: Transaction,
    val zScore: Double,
    val severity: AnomalySeverity,
    val message: String
)

enum class AnomalySeverity {
    LOW, MEDIUM, HIGH
}

data class CategoryTrend(
    val category: CategoryType,
    val recentAmount: Double,
    val previousAmount: Double,
    val changePercent: Int,
    val direction: TrendDirection
)

enum class TrendDirection {
    INCREASING, STABLE, DECREASING
}

data class IncomePattern(
    val averageDaily: Double,
    val medianDaily: Double,
    val variability: Double,        // Coefficient of variation
    val bestDay: String,
    val worstDay: String,
    val incomeStability: IncomeStability,
    val dayOfWeekAverages: Map<String, Double> = emptyMap()
)

enum class IncomeStability {
    NO_DATA, STABLE, MODERATE, VOLATILE, HIGHLY_VOLATILE
}
