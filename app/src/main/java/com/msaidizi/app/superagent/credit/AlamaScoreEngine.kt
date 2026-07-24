package com.msaidizi.superagent.credit

import timber.log.Timber
import kotlin.math.*

/**
 * Alama Score Engine — 8-pillar financial readiness assessment.
 *
 * **Alama** (Swahili: "mark/sign") — A real-time financial readiness
 * assessment engine built from voice transaction data for informal workers.
 *
 * ## Core Principles
 * - **Behavior is the score:** No self-reported data. Every data point comes
 *   from actual voice-recorded transactions.
 * - **Worker owns the data:** Score is computed locally on-device.
 * - **Offline-first:** Engine runs entirely on-device. Network is a bonus.
 * - **Transparent:** Worker can always see why their score is what it is.
 * - **Regulatory safe:** This is "financial readiness," not "credit scoring."
 *
 * ## The Eight Pillars
 * 1. **Frequency (15%):** How consistently transactions are recorded
 * 2. **Revenue Trend (15%):** Whether income is growing or declining
 * 3. **Margins (15%):** Is the business actually profitable?
 * 4. **Diversity (10%):** Risk concentration — one product vs many
 * 5. **Regularity (10%):** Predictable operating schedule
 * 6. **Growth (10%):** Is the business improving over time?
 * 7. **Expense Control (10%):** Disciplined spending patterns
 * 8. **Savings (15%):** Consistent saving behavior
 *
 * ## Score Lifecycle
 * - Day 0: No score. "Keep recording to build your Alama Score."
 * - Day 7: Preliminary score. Low confidence (~0.3). For awareness only.
 * - Day 30: Basic score. Medium confidence (~0.6). Can see trends.
 * - Day 60: Reliable score. Good confidence (~0.8). Eligible for products.
 * - Day 90: Full score. High confidence (~0.9). Eligible for all products.
 *
 * ## Academic Foundations
 * - **STA 341 (Estimation):** Confidence intervals, Bayesian updating
 * - **STA 244 (Time Series):** Trend detection, exponential smoothing
 * - **ECO 206 (Microfinance):** Credit scoring for informal workers
 * - **ECO 424 (Econometrics):** Logistic regression for default prediction
 *
 * All math is pure Kotlin — no LLM dependency.
 * Designed for 2GB devices: O(n) time, O(1) memory per calculation.
 *
 * @author Msaidizi Financial Team
 */
class AlamaScoreEngine {

    companion object {
        private const val TAG = "AlamaScoreEngine"

        // Data window sizes
        private const val MAX_WINDOW_DAYS = 90
        private const val MIN_WINDOW_DAYS = 1
        private const val MIN_TRANSACTIONS_FOR_SCORE = 3
        private const val TRANSACTIONS_FOR_FULL_CONFIDENCE = 100

        // Confidence dampening: pull low-confidence scores toward 50
        private const val NEUTRAL_SCORE = 50.0

        // Margin thresholds
        private const val EXCELLENT_GROSS_MARGIN = 0.40
        private const val GOOD_GROSS_MARGIN = 0.25
        private const val ACCEPTABLE_GROSS_MARGIN = 0.15

        // Savings rate thresholds
        private const val EXCELLENT_SAVINGS_RATE = 0.10  // 10%+
        private const val GOOD_SAVINGS_RATE = 0.05       // 5%+

        // Day-of-week patterns for regularity
        private const val SECONDS_PER_DAY = 86_400L
    }

    // ═══════════════════════════════════════════════════════════════
    // MAIN SCORE COMPUTATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Compute the Alama Score from transaction history.
     *
     * @param transactions All available transactions (oldest first)
     * @param savingsAmount Total savings amount (if tracked separately)
     * @param workPattern Worker's business rhythm (auto-detected if null)
     * @return [AlamaScore] with full breakdown
     */
    fun compute(
        transactions: List<AlamaTransaction>,
        savingsAmount: Double = 0.0,
        workPattern: WorkPattern? = null
    ): AlamaScore {
        val now = System.currentTimeMillis() / 1000

        // Determine data window
        val windowDays = determineWindow(transactions, now)
        val windowStart = now - (windowDays * SECONDS_PER_DAY)
        val windowTransactions = transactions.filter { it.timestamp >= windowStart }

        // Insufficient data
        if (windowTransactions.size < MIN_TRANSACTIONS_FOR_SCORE) {
            return AlamaScore(
                score = 0.0,
                rawScore = 0.0,
                confidence = 0.0,
                confidenceLevel = ConfidenceLevel.INSUFFICIENT,
                tier = AlamaTier.BUILDING,
                pillars = emptyList(),
                transactionCount = windowTransactions.size,
                activeDays = 0,
                windowDays = windowDays,
                computedAt = now,
                workPattern = workPattern ?: WorkPattern.DAILY,
                message = "Bado hakuna data ya kutosha. Rekodi mauzo yako kila siku " +
                    "kujenga Alama yako! Inahitaji angalau miamala $MIN_TRANSACTIONS_FOR_SCORE."
            )
        }

        // Detect work pattern if not provided
        val detectedPattern = workPattern ?: detectWorkPattern(windowTransactions)

        // Compute each pillar
        val pillars = listOf(
            computeFrequency(windowTransactions, windowDays, detectedPattern),
            computeRevenueTrend(windowTransactions, windowDays),
            computeMargins(windowTransactions),
            computeDiversity(windowTransactions),
            computeRegularity(windowTransactions, windowDays),
            computeGrowth(transactions, now),
            computeExpenseControl(windowTransactions),
            computeSavings(windowTransactions, savingsAmount)
        )

        // Weighted composite score
        val rawScore = pillars.sumOf { it.rawValue * it.pillar.weight }

        // Compute confidence
        val confidence = computeConfidence(windowTransactions, windowDays)
        val confidenceLevel = computeConfidenceLevel(confidence)

        // Apply confidence dampening (pull toward neutral for low confidence)
        val adjustedScore = rawScore * confidence + NEUTRAL_SCORE * (1 - confidence)

        // Determine tier
        val tier = AlamaTier.fromScore(adjustedScore.roundToInt())

        // Active days count
        val activeDays = windowTransactions
            .map { it.dayNumber }
            .distinct()
            .size

        // Generate message
        val message = buildScoreMessage(adjustedScore, tier, confidenceLevel, pillars)

        return AlamaScore(
            score = (adjustedScore * 10).roundToInt() / 10.0,
            rawScore = (rawScore * 10).roundToInt() / 10.0,
            confidence = (confidence * 1000).roundToInt() / 1000.0,
            confidenceLevel = confidenceLevel,
            tier = tier,
            pillars = pillars,
            transactionCount = windowTransactions.size,
            activeDays = activeDays,
            windowDays = windowDays,
            computedAt = now,
            workPattern = detectedPattern,
            message = message
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // PILLAR 1: TRANSACTION FREQUENCY (15%)
    // ═══════════════════════════════════════════════════════════════

    /**
     * How consistently the worker records transactions.
     *
     * A worker who records daily has a real business. Sporadic recording = uncertain.
     */
    private fun computeFrequency(
        transactions: List<AlamaTransaction>,
        windowDays: Int,
        pattern: WorkPattern
    ): PillarScore {
        val activeDays = transactions
            .map { it.dayNumber }
            .distinct()
            .size

        // Adjust expected active days based on work pattern
        val expectedActiveDays = when (pattern) {
            WorkPattern.DAILY -> windowDays.toDouble()
            WorkPattern.WEEKLY -> windowDays * 5.0 / 7.0 // 5 days/week
            WorkPattern.SEASONAL -> windowDays * 0.6 // ~60% of days
            WorkPattern.PROJECT_BASED -> windowDays * 0.4 // ~40% of days
            WorkPattern.ON_DEMAND -> windowDays * 0.5 // ~50% of days
        }

        val dailyConsistency = (activeDays / expectedActiveDays).coerceIn(0.0, 1.0)

        // Penalize gaps (consecutive inactive days)
        val maxGap = calculateMaxGap(transactions, windowDays)
        val gapPenalty = max(0.0, 1.0 - (maxGap / 7.0)) // 7+ day gap = 0

        val score = dailyConsistency * gapPenalty * 100

        return PillarScore(
            pillar = Pillar.FREQUENCY,
            rawValue = score.coerceIn(0.0, 100.0),
            contributingData = mapOf(
                "activeDays" to activeDays,
                "expectedDays" to expectedActiveDays.toInt(),
                "maxGap" to maxGap,
                "pattern" to pattern.name
            ),
            message = when {
                score >= 80 -> "Unarekodi mauzo yako kwa uthabiti! Siku $activeDays kati ya ${expectedActiveDays.toInt()}."
                score >= 50 -> "Unarekodi mara nyingi, lakini kuna siku unazokosa. Jaribu kurekodi kila siku."
                else -> "Fanya rekodi za mauzo yako mara nyingi zaidi ili kuongeza Alama yako."
            },
            tip = when {
                score < 50 -> "Rekodi mauzo yako kila siku, hata kama ni machache. Uthabiti ni muhimu!"
                maxGap > 3 -> "Kuna siku ${maxGap} bila rekodi. Jaribu kutoweza siku bila kurekodi."
                else -> "Endelea hivyo! Uthabiti wako ni mzuri."
            }
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // PILLAR 2: REVENUE TREND (15%)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Whether income is growing, stable, or declining.
     *
     * Uses linear regression on daily revenue to detect trends.
     */
    private fun computeRevenueTrend(
        transactions: List<AlamaTransaction>,
        windowDays: Int
    ): PillarScore {
        val sales = transactions.filter { it.isSale }
        if (sales.isEmpty()) {
            return PillarScore(
                pillar = Pillar.REVENUE_TREND,
                rawValue = 50.0, // Neutral for no data
                message = "Hakuna data ya mauzo ya kutosha kutabiri mwenendo.",
                tip = "Rekodi mauzo yako ili uone mwenendo wa mapato yako."
            )
        }

        // Build daily revenue series
        val dailyRevenue = sales
            .groupBy { it.dayNumber }
            .mapValues { (_, txns) -> txns.sumOf { it.amount } }
            .toSortedMap()

        if (dailyRevenue.size < 3) {
            return PillarScore(
                pillar = Pillar.REVENUE_TREND,
                rawValue = 50.0,
                message = "Data ya mauzo ni kidogo sana kutabiri mwenendo.",
                tip = "Endelea kurekodi — baada ya siku 7, nitaweza kuona mwenendo."
            )
        }

        // Linear regression
        val days = dailyRevenue.keys.toList()
        val revenues = dailyRevenue.values.toList()
        val n = days.size

        val xMean = days.average()
        val yMean = revenues.average()

        var ssXY = 0.0
        var ssXX = 0.0
        for (i in days.indices) {
            ssXY += (days[i] - xMean) * (revenues[i] - yMean)
            ssXX += (days[i] - xMean) * (days[i] - xMean)
        }

        val slope = if (ssXX > 0) ssXY / ssXX else 0.0

        // Normalize slope: revenue change per week as % of mean
        val weeklyChangePct = if (yMean > 0) (slope * 7) / yMean else 0.0
        val trendSignal = tanh(weeklyChangePct * 5) // Bounded [-1, 1]

        // Volatility (coefficient of variation)
        val variance = revenues.map { (it - yMean).pow(2) }.average()
        val cv = if (yMean > 0) sqrt(variance) / yMean else 1.0
        val stability = max(0.0, 1.0 - cv)

        val score = ((trendSignal + 1) / 2 * 0.6 + stability * 0.4) * 100

        return PillarScore(
            pillar = Pillar.REVENUE_TREND,
            rawValue = score.coerceIn(0.0, 100.0),
            contributingData = mapOf(
                "weeklyChangePct" to (weeklyChangePct * 100).roundToInt(),
                "stability" to (stability * 100).roundToInt(),
                "avgDailyRevenue" to yMean.roundToInt()
            ),
            message = when {
                trendSignal > 0.3 -> "Mauzo yako yanaongezeka! Mwenendo ni mzuri. 📈"
                trendSignal > -0.3 -> "Mauzo yako yako thabiti. Endelea hivyo. ➡️"
                else -> "Mauzo yako yanapungua. Fikiria kuongeza bidhaa au kubadilisha bei. 📉"
            },
            tip = when {
                trendSignal < -0.3 -> "Jaribu kuongeza bidhaa mpya au kuuza kwa bei nzuri zaidi."
                stability < 0.5 -> "Mauzo yako ni ya kubadilika sana. Fanya kazi ya kuuza kwa uthabiti."
                else -> "Endelea na mwenendo huu mzuri!"
            }
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // PILLAR 3: PROFIT MARGINS (15%)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Is the business actually profitable?
     *
     * Revenue means nothing without profit. A KES 10,000/day business
     * with KES 11,000 in costs is failing.
     */
    private fun computeMargins(transactions: List<AlamaTransaction>): PillarScore {
        val sales = transactions.filter { it.isSale }
        val purchases = transactions.filter { it.isExpense }

        if (sales.isEmpty()) {
            return PillarScore(
                pillar = Pillar.MARGINS,
                rawValue = 50.0,
                message = "Hakuna data ya mauzo ya kutosha kuhesabu faida.",
                tip = "Rekodi mauzo na ununuzi ili uone faida yako."
            )
        }

        val totalRevenue = sales.sumOf { it.amount }
        val totalCOGS = purchases.sumOf { it.amount }
        val totalExpenses = transactions.filter { it.type == "EXPENSE" }.sumOf { it.amount }

        val grossMargin = if (totalRevenue > 0) {
            ((totalRevenue - totalCOGS) / totalRevenue).coerceIn(-1.0, 1.0)
        } else 0.0

        val netMargin = if (totalRevenue > 0) {
            ((totalRevenue - totalCOGS - totalExpenses) / totalRevenue).coerceIn(-1.0, 1.0)
        } else 0.0

        // Score gross margin
        val grossScore = when {
            grossMargin >= EXCELLENT_GROSS_MARGIN -> 100.0
            grossMargin >= GOOD_GROSS_MARGIN -> 75.0
            grossMargin >= ACCEPTABLE_GROSS_MARGIN -> 50.0
            grossMargin > 0 -> 25.0
            else -> max(0.0, grossMargin * 100) // Negative = penalty
        }

        // Score net margin
        val netScore = when {
            netMargin >= 0.20 -> 100.0
            netMargin >= 0.10 -> 75.0
            netMargin >= 0.05 -> 50.0
            netMargin > 0 -> 25.0
            else -> max(-50.0, netMargin * 200) // Negative = stronger penalty
        }

        val score = (grossScore * 0.4 + netScore * 0.6)

        return PillarScore(
            pillar = Pillar.MARGINS,
            rawValue = score.coerceIn(0.0, 100.0),
            contributingData = mapOf(
                "grossMarginPct" to (grossMargin * 100).roundToInt(),
                "netMarginPct" to (netMargin * 100).roundToInt(),
                "totalRevenue" to totalRevenue.roundToInt(),
                "totalCosts" to (totalCOGS + totalExpenses).roundToInt()
            ),
            message = when {
                grossMargin >= GOOD_GROSS_MARGIN -> "Faida yako ni nzuri! Margin ni ${(grossMargin * 100).roundToInt()}%. ✅"
                grossMargin >= ACCEPTABLE_GROSS_MARGIN -> "Faida yako ni ya wastani (${(grossMargin * 100).roundToInt()}%). Inaweza kuboreshwa."
                grossMargin > 0 -> "Faida yako ni ndogo sana. Gharama zako zinakaribia mauzo."
                else -> "⚠️ Una hasara! Gharama ni zaidi ya mauzo. Hatua za haraka zinahitajika."
            },
            tip = when {
                grossMargin < 0.15 -> "Punguza gharama za ununuzi au ongeza bei ya kuuza."
                netMargin < 0.05 -> "Angalia matumizi yako — kuna gharama zinazoweza kupunguzwa?"
                else -> "Endelea kudhibiti gharama zako!"
            }
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // PILLAR 4: PRODUCT DIVERSITY (10%)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Risk concentration — is income from one product or many?
     *
     * A mama mboga selling only tomatoes collapses when tomato prices spike.
     * Diversity = resilience.
     */
    private fun computeDiversity(transactions: List<AlamaTransaction>): PillarScore {
        val sales = transactions.filter { it.isSale }
        if (sales.isEmpty()) {
            return PillarScore(
                pillar = Pillar.DIVERSITY,
                rawValue = 50.0,
                message = "Hakuna data ya mauzo ya kutosha.",
                tip = "Ongeza bidhaa tofauti kupunguza hatari."
            )
        }

        val categoryDistribution = sales
            .groupBy { it.category.ifEmpty { it.item } }
            .mapValues { (_, txns) -> txns.sumOf { it.amount } }

        val totalSales = categoryDistribution.values.sum()
        if (totalSales <= 0) {
            return PillarScore(pillar = Pillar.DIVERSITY, rawValue = 50.0)
        }

        val probabilities = categoryDistribution.values.map { it / totalSales }
        val nCategories = categoryDistribution.size

        // Shannon entropy
        val entropy = -probabilities.sumOf { p ->
            if (p > 0) p * ln(p) else 0.0
        }
        val maxEntropy = if (nCategories > 1) ln(nCategories.toDouble()) else 1.0
        val diversityRatio = if (maxEntropy > 0) (entropy / maxEntropy).coerceIn(0.0, 1.0) else 0.0

        // Concentration penalty (one product > 50% = penalty)
        val maxShare = probabilities.maxOrNull() ?: 0.0
        val concentrationPenalty = max(0.0, 1.0 - (maxShare - 0.5) * 2)

        val score = (diversityRatio * 0.6 + concentrationPenalty * 0.4) * 100

        return PillarScore(
            pillar = Pillar.DIVERSITY,
            rawValue = score.coerceIn(0.0, 100.0),
            contributingData = mapOf(
                "nCategories" to nCategories,
                "topItemShare" to (maxShare * 100).roundToInt(),
                "diversityRatio" to (diversityRatio * 100).roundToInt()
            ),
            message = when {
                nCategories >= 5 -> "Bidhaa zako ni nyingi! Hii inapunguza hatari. ✅"
                nCategories >= 3 -> "Una bidhaa chache. Fikiria kuongeza zaidi."
                else -> "Biashara yako inategemea bidhaa moja sana. Hii ni hatari! ⚠️"
            },
            tip = when {
                nCategories < 3 -> "Ongeza bidhaa 1-2 mpya. Hata kuuza vitunguu pamoja na nyanya inasaidia."
                maxShare > 0.6 -> "Bidhaa moja inachukua ${(maxShare * 100).roundToInt()}% ya mauzo. Ongeza nyingine kupunguza hatari."
                else -> "Diversity yako ni nzuri! Endelea hivyo."
            }
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // PILLAR 5: REGULARITY (10%)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Does the worker operate on a predictable schedule?
     *
     * Predictable behavior signals a real, established business.
     */
    private fun computeRegularity(
        transactions: List<AlamaTransaction>,
        windowDays: Int
    ): PillarScore {
        if (transactions.size < 10) {
            return PillarScore(
                pillar = Pillar.REGULARITY,
                rawValue = 50.0,
                message = "Hakuna data ya kutosha kuhesabu uthabiti wa ratiba.",
                tip = "Endelea kurekodi — baada ya wiki 2, nitaweza kuona ratiba yako."
            )
        }

        // Hour-of-day distribution
        val hourDistribution = IntArray(24)
        transactions.forEach { txn ->
            val hour = ((txn.timestamp % 86400) / 3600).toInt()
            hourDistribution[hour]++
        }
        val hourEntropy = calculateEntropy(hourDistribution.map { it.toDouble() })
        val maxHourEntropy = ln(24.0)
        val hourRegularity = 1.0 - (hourEntropy / maxHourEntropy).coerceIn(0.0, 1.0)

        // Day-of-week distribution
        val dowDistribution = IntArray(7)
        transactions.forEach { txn ->
            val dow = ((txn.timestamp / 86400 + 4) % 7).toInt() // Jan 1 1970 was Thursday
            dowDistribution[dow]++
        }
        val dowEntropy = calculateEntropy(dowDistribution.map { it.toDouble() })
        val maxDowEntropy = ln(7.0)
        val dowRegularity = 1.0 - (dowEntropy / maxDowEntropy).coerceIn(0.0, 1.0)

        val score = (hourRegularity * 0.5 + dowRegularity * 0.5) * 100

        return PillarScore(
            pillar = Pillar.REGULARITY,
            rawValue = score.coerceIn(0.0, 100.0),
            contributingData = mapOf(
                "hourRegularity" to (hourRegularity * 100).roundToInt(),
                "dowRegularity" to (dowRegularity * 100).roundToInt()
            ),
            message = when {
                score >= 70 -> "Una ratiba thabiti ya biashara. Hii ni ishara nzuri! ✅"
                score >= 40 -> "Ratiba yako ni ya wastani. Fanya kazi ya kuuza wakati sawa kila siku."
                else -> "Ratiba yako ni ya kubadilika sana. Jaribu kuuza wakati sawa kila siku."
            },
            tip = "Fungua biashara yako wakati sawa kila siku. Uthabiti wa ratiba unaongeza Alama."
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // PILLAR 6: GROWTH TRAJECTORY (10%)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Is the business improving over time?
     *
     * Compares 30-day vs 90-day rolling averages.
     */
    private fun computeGrowth(
        allTransactions: List<AlamaTransaction>,
        now: Long
    ): PillarScore {
        if (allTransactions.size < 20) {
            return PillarScore(
                pillar = Pillar.GROWTH,
                rawValue = 50.0,
                message = "Hakuna data ya kutosha kuhesabu ukuaji.",
                tip = "Endelea kurekodi — baada ya mwezi 1, nitaweza kuona ukuaji wako."
            )
        }

        val day30Start = now - (30 * SECONDS_PER_DAY)
        val day90Start = now - (90 * SECONDS_PER_DAY)

        val sales30d = allTransactions.filter { it.isSale && it.timestamp >= day30Start }
        val sales90d = allTransactions.filter { it.isSale && it.timestamp >= day90Start }

        // Average daily revenue
        val avgDaily30d = if (sales30d.isNotEmpty()) {
            sales30d.sumOf { it.amount } / 30.0
        } else 0.0

        val days90d = max(1, ((now - (sales90d.minOfOrNull { it.timestamp } ?: now)) / SECONDS_PER_DAY).toInt())
        val avgDaily90d = if (sales90d.isNotEmpty()) {
            sales90d.sumOf { it.amount } / days90d.toDouble()
        } else 0.0

        // Growth metrics
        val revenueGrowth = if (avgDaily90d > 0) {
            (avgDaily30d - avgDaily90d) / avgDaily90d
        } else if (avgDaily30d > 0) 1.0 else 0.0

        // Frequency growth
        val freq30d = sales30d.map { it.dayNumber }.distinct().size / 30.0
        val freq90d = sales90d.map { it.dayNumber }.distinct().size / days90d.toDouble()
        val freqGrowth = if (freq90d > 0) freq30d - freq90d else 0.0

        // Composite growth signal
        val rawGrowth = revenueGrowth * 0.7 + freqGrowth * 0.3
        val score = (1.0 / (1.0 + exp(-rawGrowth * 10))) * 100 // Sigmoid

        return PillarScore(
            pillar = Pillar.GROWTH,
            rawValue = score.coerceIn(0.0, 100.0),
            contributingData = mapOf(
                "revenueGrowthPct" to (revenueGrowth * 100).roundToInt(),
                "avgDaily30d" to avgDaily30d.roundToInt(),
                "avgDaily90d" to avgDaily90d.roundToInt()
            ),
            message = when {
                revenueGrowth > 0.15 -> "Biashara yako inakua kwa kasi! Mauzo ya siku 30 ni bora zaidi. 📈"
                revenueGrowth > 0 -> "Biashara yako inakua polepole. Endelea! 🌱"
                revenueGrowth > -0.15 -> "Biashara yako iko thabiti. ➡️"
                else -> "Biashara yako inapungua. Fikiria mbinu mpya za mauzo. 📉"
            },
            tip = when {
                revenueGrowth < 0 -> "Jaribu kufungua soko jipya au kuongeza bidhaa mpya."
                revenueGrowth < 0.1 -> "Fanya kazi ya kuongeza wateja wapya kila wiki."
                else -> "Ukuaji wako ni mzuri! Endelea hivyo."
            }
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // PILLAR 7: EXPENSE CONTROL (10%)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Is the worker disciplined about spending?
     *
     * Controlled expenses signal financial discipline.
     */
    private fun computeExpenseControl(transactions: List<AlamaTransaction>): PillarScore {
        val sales = transactions.filter { it.isSale }
        val expenses = transactions.filter { it.isExpense }

        if (sales.isEmpty() || expenses.isEmpty()) {
            return PillarScore(
                pillar = Pillar.EXPENSE_CONTROL,
                rawValue = 50.0,
                message = "Hakuna data ya kutosha kuhesabu udhibiti wa matumizi.",
                tip = "Rekodi mauzo na matumizi yako ili uone picha kamili."
            )
        }

        val totalRevenue = sales.sumOf { it.amount }
        val totalExpenses = expenses.sumOf { it.amount }

        // Expense-to-revenue ratio
        val expenseRatio = if (totalRevenue > 0) totalExpenses / totalRevenue else 1.0

        // Daily expense stability
        val dailyExpenses = expenses
            .groupBy { it.dayNumber }
            .values
            .map { it.sumOf { e -> e.amount } }

        val avgDailyExpense = dailyExpenses.average()
        val expenseVariance = dailyExpenses.map { (it - avgDailyExpense).pow(2) }.average()
        val expenseCV = if (avgDailyExpense > 0) sqrt(expenseVariance) / avgDailyExpense else 1.0
        val stability = max(0.0, 1.0 - expenseCV)

        // Score: lower expense ratio = better, more stable = better
        val ratioScore = when {
            expenseRatio < 0.3 -> 100.0
            expenseRatio < 0.5 -> 75.0
            expenseRatio < 0.7 -> 50.0
            expenseRatio < 0.9 -> 25.0
            else -> 0.0
        }

        val score = ratioScore * 0.6 + stability * 100 * 0.4

        return PillarScore(
            pillar = Pillar.EXPENSE_CONTROL,
            rawValue = score.coerceIn(0.0, 100.0),
            contributingData = mapOf(
                "expenseRatioPct" to (expenseRatio * 100).roundToInt(),
                "stabilityPct" to (stability * 100).roundToInt(),
                "totalExpenses" to totalExpenses.roundToInt()
            ),
            message = when {
                expenseRatio < 0.5 -> "Matumizi yako ni ya chini kuliko mauzo. Nzuri! ✅"
                expenseRatio < 0.7 -> "Matumizi ni ya wastani. Angalia kama kuna gharama zinazoweza kupunguzwa."
                else -> "Matumizi yako ni ya juu! Gharama zinakaribia mauzo. ⚠️"
            },
            tip = when {
                expenseRatio > 0.7 -> "Angalia matumizi yako — kuna gharama zisizo za lazima?"
                stability < 0.5 -> "Matumizi yako ni ya kubadilika sana. Weka bajeti na uifuate."
                else -> "Udhibiti wako wa matumizi ni mzuri!"
            }
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // PILLAR 8: SAVINGS BEHAVIOR (15%)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Does the worker save? How consistently?
     *
     * M-KOPA proved: the single best predictor of repayment is savings behavior.
     */
    private fun computeSavings(
        transactions: List<AlamaTransaction>,
        totalSaved: Double
    ): PillarScore {
        val sales = transactions.filter { it.isSale }
        val totalRevenue = sales.sumOf { it.amount }

        if (totalRevenue <= 0) {
            return PillarScore(
                pillar = Pillar.SAVINGS,
                rawValue = if (totalSaved > 0) 60.0 else 0.0,
                message = "Hakuna data ya mauzo ya kutosha.",
                tip = "Anza kuweka akiba — hata KSh 50 kwa siku inasaidia."
            )
        }

        // Savings rate
        val savingsRate = if (totalRevenue > 0) totalSaved / totalRevenue else 0.0
        val rateScore = (savingsRate / EXCELLENT_SAVINGS_RATE).coerceIn(0.0, 1.0) * 100

        // Consistency: check for deposit transactions
        val depositDays = transactions
            .filter { it.type == "DEPOSIT" }
            .map { it.dayNumber }
            .distinct()
            .size
        val activeDays = sales.map { it.dayNumber }.distinct().size
        val consistency = if (activeDays > 0) depositDays.toDouble() / activeDays else 0.0

        val score = rateScore * 0.6 + consistency * 100 * 0.4

        return PillarScore(
            pillar = Pillar.SAVINGS,
            rawValue = score.coerceIn(0.0, 100.0),
            contributingData = mapOf(
                "savingsRatePct" to (savingsRate * 100).roundToInt(),
                "totalSaved" to totalSaved.roundToInt(),
                "consistencyPct" to (consistency * 100).roundToInt()
            ),
            message = when {
                savingsRate >= EXCELLENT_SAVINGS_RATE -> "Akiba yako ni nzuri! Unaweka ${(savingsRate * 100).roundToInt()}% ya mauzo. ✅"
                savingsRate >= GOOD_SAVINGS_RATE -> "Una akiba, lakini inaweza kuboreshwa. Jaribu kuongeza kidogo."
                totalSaved > 0 -> "Una akiba ndogo. Ongeza — hata KSh 20 zaidi kwa siku inasaidia."
                else -> "Huna akiba bado. Anza leo — hata KSh 50 kwa siku!"
            },
            tip = when {
                savingsRate < GOOD_SAVINGS_RATE -> "Weka angalau 5% ya mauzo yako kwenye akiba kila siku."
                consistency < 0.3 -> "Jaribu kuweka akiba kila siku, si tu mara moja."
                else -> "Akiba yako ni nzuri! Endelea hivyo."
            }
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // CONFIDENCE CALCULATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Compute confidence in the score.
     *
     * Confidence = data_quantity × data_quality × consistency
     */
    private fun computeConfidence(
        transactions: List<AlamaTransaction>,
        windowDays: Int
    ): Double {
        // Data quantity factor
        val quantity = min(1.0, transactions.size.toDouble() / TRANSACTIONS_FOR_FULL_CONFIDENCE)

        // Data quality factor (average ASR confidence)
        val avgConfidence = transactions.map { it.confidence.toDouble() }.average()
        val quality = if (avgConfidence.isNaN()) 0.5 else avgConfidence

        // Consistency factor
        val activeDays = transactions.map { it.dayNumber }.distinct().size
        val consistency = activeDays.toDouble() / max(1, windowDays)

        return (quantity * quality * consistency).coerceIn(0.0, 1.0)
    }

    /**
     * Determine categorical confidence level.
     */
    private fun computeConfidenceLevel(confidence: Double): ConfidenceLevel {
        return when {
            confidence < 0.25 -> ConfidenceLevel.INSUFFICIENT
            confidence < 0.50 -> ConfidenceLevel.PRELIMINARY
            confidence < 0.75 -> ConfidenceLevel.MODERATE
            else -> ConfidenceLevel.HIGH
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // WORK PATTERN DETECTION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Auto-detect work pattern from transaction history.
     */
    private fun detectWorkPattern(transactions: List<AlamaTransaction>): WorkPattern {
        if (transactions.size < 14) return WorkPattern.DAILY

        // Count active days per week
        val activeDaysPerWeek = transactions
            .groupBy { it.dayNumber / 7 }
            .values
            .map { it.map { t -> t.dayNumber }.distinct().size }

        val avgDaysPerWeek = activeDaysPerWeek.average()

        // Check for specific day-of-week patterns
        val dowCounts = IntArray(7)
        transactions.forEach { txn ->
            val dow = ((txn.timestamp / 86400 + 4) % 7).toInt()
            dowCounts[dow]++
        }
        val activeDows = dowCounts.count { it > 0 }
        val maxDowCount = dowCounts.maxOrNull() ?: 0
        val totalTxns = dowCounts.sum()

        return when {
            avgDaysPerWeek >= 5.5 -> WorkPattern.DAILY
            avgDaysPerWeek >= 3.5 && activeDows <= 5 -> WorkPattern.WEEKLY
            maxDowCount > totalTxns * 0.4 && activeDows <= 3 -> WorkPattern.PROJECT_BASED
            activeDows <= 4 -> WorkPattern.WEEKLY
            else -> WorkPattern.ON_DEMAND
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // WHAT-IF SIMULATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Simulate how the score would change with specific improvements.
     *
     * @param currentScore Current Alama Score
     * @param changes Simulated changes to pillar values
     * @return [WhatIfResult] with projected score
     */
    fun simulate(
        currentScore: AlamaScore,
        changes: Map<Pillar, Double> // Pillar → new raw value (0-100)
    ): WhatIfResult {
        val simulatedPillars = currentScore.pillars.map { pillar ->
            val newValue = changes[pillar.pillar] ?: pillar.rawValue
            pillar.copy(rawValue = newValue)
        }

        val simulatedRaw = simulatedPillars.sumOf { it.rawValue * it.pillar.weight }
        val simulatedAdjusted = simulatedRaw * currentScore.confidence +
            NEUTRAL_SCORE * (1 - currentScore.confidence)

        val simulatedTier = AlamaTier.fromScore(simulatedAdjusted.roundToInt())

        val simulatedChanges = changes.map { (pillar, newValue) ->
            val current = currentScore.pillars.find { it.pillar == pillar }
            SimulatedChange(
                pillar = pillar,
                description = "Badilisha ${pillar.displayName} kutoka ${current?.rawValue?.roundToInt()} hadi ${newValue.roundToInt()}",
                currentValue = current?.rawValue ?: 0.0,
                simulatedValue = newValue,
                scoreImpact = (newValue - (current?.rawValue ?: 0.0)) * pillar.weight
            )
        }

        val simulatedScore = currentScore.copy(
            score = (simulatedAdjusted * 10).roundToInt() / 10.0,
            rawScore = (simulatedRaw * 10).roundToInt() / 10.0,
            tier = simulatedTier,
            pillars = simulatedPillars
        )

        val message = buildString {
            val scoreDiff = simulatedScore.score - currentScore.score
            if (scoreDiff > 0) {
                append("📈 Ukifanya mabadiliko haya, Alama yako ingepanda ")
                append("kutoka ${currentScore.score} hadi ${simulatedScore.score} ")
                append("(+${(scoreDiff * 10).roundToInt() / 10.0})!")
            } else if (scoreDiff < 0) {
                append("📉 Mabadiliko haya yangepunguza Alama yako.")
            } else {
                append("Mabadiliko haya hayangeathiri Alama yako sana.")
            }
        }

        return WhatIfResult(
            currentScore = currentScore,
            simulatedScore = simulatedScore,
            changes = simulatedChanges,
            message = message
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // UTILITY FUNCTIONS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Determine the data window based on available data.
     */
    private fun determineWindow(transactions: List<AlamaTransaction>, now: Long): Int {
        if (transactions.isEmpty()) return MIN_WINDOW_DAYS
        val oldest = transactions.minOfOrNull { it.timestamp } ?: now
        val daysAvailable = ((now - oldest) / SECONDS_PER_DAY).toInt()
        return daysAvailable.coerceIn(MIN_WINDOW_DAYS, MAX_WINDOW_DAYS)
    }

    /**
     * Calculate maximum consecutive inactive days.
     */
    private fun calculateMaxGap(transactions: List<AlamaTransaction>, windowDays: Int): Int {
        val activeDays = transactions
            .map { it.dayNumber }
            .distinct()
            .toSortedSet()

        if (activeDays.isEmpty()) return windowDays

        var maxGap = 0
        var previous = activeDays.first()

        for (day in activeDays.drop(1)) {
            val gap = (day - previous - 1).toInt()
            maxGap = max(maxGap, gap)
            previous = day
        }

        return maxGap
    }

    /**
     * Calculate Shannon entropy of a distribution.
     */
    private fun calculateEntropy(counts: List<Double>): Double {
        val total = counts.sum()
        if (total <= 0) return 0.0
        return -counts.sumOf { count ->
            val p = count / total
            if (p > 0) p * ln(p) else 0.0
        }
    }

    /**
     * Build a human-readable score message in Swahili.
     */
    private fun buildScoreMessage(
        score: Double,
        tier: AlamaTier,
        confidence: ConfidenceLevel,
        pillars: List<PillarScore>
    ): String {
        return buildString {
            append("📊 Alama yako: ${score.roundToInt()}/100\n")
            append("Kiwango: ${tier.emoji} ${tier.displayName}\n\n")

            when (confidence) {
                ConfidenceLevel.INSUFFICIENT ->
                    append("Data bado ni kidogo. Endelea kurekodi mauzo yako!\n")
                ConfidenceLevel.PRELIMINARY ->
                    append("Alama ya awali — data bado inakusanywa.\n")
                ConfidenceLevel.MODERATE ->
                    append("Alama ya wastani — endelea kurekodi ili kuboresha.\n")
                ConfidenceLevel.HIGH ->
                    append("Alama ya juu! Data yako ni nzuri.\n")
            }

            // Show top 3 pillars
            append("\nVipengele vikuu:\n")
            pillars.sortedByDescending { it.rawValue }.take(3).forEach { pillar ->
                val emoji = if (pillar.rawValue >= 70) "✅" else if (pillar.rawValue >= 40) "➖" else "📈"
                append("$emoji ${pillar.pillar.displayName}: ${pillar.rawValue.roundToInt()}/100\n")
            }
        }
    }
}
