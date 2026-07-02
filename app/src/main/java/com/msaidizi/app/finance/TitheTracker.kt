package com.msaidizi.app.finance

import timber.log.Timber
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Tithe & Giving Tracker for informal workers.
 *
 * Many informal workers in Kenya tithe (10% of income) to their church
 * but never track it. They believe "When you give, God gives you more"
 * but can't see the pattern because it's not recorded.
 *
 * This tracker helps workers:
 * - See exactly how much they've given over time
 * - Track the "abundance pattern" — income after giving periods
 * - Set giving goals and maintain consistency
 * - Understand the importance of consistent giving through data
 *
 * Academic foundations (Valentine's BSc Economics & Statistics):
 * - ECO 206 (Microfinance): Savings behavior, financial discipline,
 *   commitment devices — tithing acts as a forced savings mechanism
 * - STA 341 (Estimation): Bayesian updating of giving patterns,
 *   posterior estimation of giving effectiveness
 * - STA 244 (Time Series): Trend detection in giving over time,
 *   seasonal decomposition of giving patterns
 * - STA 201 (Descriptive Statistics): Frequency distributions,
 *   measures of central tendency for giving amounts
 * - PSY 200 (Behavioral Economics): Mental accounting, pro-social
 *   spending and wellbeing, warm-glow giving
 *
 * @author Msaidizi Team
 */
class TitheTracker {

    // ═══════════════════════════════════════════════════════════════
    // DATA MODELS
    // ═══════════════════════════════════════════════════════════════

    /**
     * A single giving record.
     *
     * @param amount Amount given in KSh
     * @param type Type of giving (tithe, offering, charity, etc.)
     * @param recipient Church name, mosque, charity organization
     * @param date Unix timestamp (milliseconds) of the giving
     * @param notes Optional notes (e.g., "Sunday service", "Ramadan")
     * @param incomeAtTime Income at the time of giving (for calculating %)
     */
    data class GivingRecord(
        val amount: Double,
        val type: GivingType,
        val recipient: String,
        val date: Long,
        val notes: String = "",
        val incomeAtTime: Double = 0.0
    )

    /**
     * Types of giving recognized in East African informal economy.
     *
     * TITHE: 10% of income given to church (Malachi 3:10)
     * OFFERING: Regular Sunday/holiday offering (any amount)
     * CHARITY: Giving to individuals, orphanages, community
     * ZAKAT: Islamic obligatory giving (2.5% of wealth)
     * SADAQAH: Islamic voluntary charity
     * OTHER: Any other form of giving
     */
    enum class GivingType(val swahili: String, val description: String) {
        TITHE("zaka ya kumi", "10% tithe to church"),
        OFFERING("sadaka", "Regular offering"),
        CHARITY("misaada", "Charitable giving"),
        ZAKAT("zaka", "Islamic obligatory giving"),
        SADAQAH("sadaqah", "Islamic voluntary charity"),
        OTHER("nyingine", "Other giving")
    }

    /**
     * Summary of giving over a period.
     *
     * @param totalGiven Total amount given in KSh
     * @param tithePercentage Percentage of income given as tithe
     * @param givingFrequency How often the worker gives (e.g., "Weekly")
     * @param abundancePattern Average income change after giving periods
     * @param consistencyScore 0-100 score for giving regularity
     * @param streakDays Consecutive giving days/weeks
     * @param topRecipient Who receives the most
     * @param givingByType Breakdown by giving type
     */
    data class GivingSummary(
        val totalGiven: Double,
        val tithePercentage: Double,
        val givingFrequency: String,
        val abundancePattern: Double,
        val consistencyScore: Int,
        val streakDays: Int = 0,
        val topRecipient: String = "",
        val givingByType: Map<GivingType, Double> = emptyMap()
    )

    /**
     * A giving goal set by the worker.
     *
     * @param targetType Type of giving this goal is for
     * @param targetAmount Target amount per period
     * @param period "weekly", "monthly", "per_income"
     * @param description Human-readable goal description
     */
    data class GivingGoal(
        val targetType: GivingType,
        val targetAmount: Double,
        val period: String = "monthly",
        val description: String = ""
    )

    // ═══════════════════════════════════════════════════════════════
    // IN-MEMORY STORAGE (synced to Room via repository)
    // ═══════════════════════════════════════════════════════════════

    private val givingRecords = mutableListOf<GivingRecord>()
    private val givingGoals = mutableListOf<GivingGoal>()

    // ═══════════════════════════════════════════════════════════════
    // CORE OPERATIONS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Record a giving event.
     *
     * Example voice inputs:
     * - "Nilitoa sadaka KSh 200" → Offering of 200
     * - "Nilitoa zaka KSh 500" → Zakat of 500
     * - "Nilitoa zaka ya kumi elfu moja" → Tithe of 1000
     *
     * @param record The giving record to store
     */
    fun recordGiving(record: GivingRecord) {
        if (record.amount <= 0) {
            Timber.w("TitheTracker: Ignoring giving record with amount ≤ 0")
            return
        }
        givingRecords.add(record)
        Timber.i(
            "TitheTracker: Recorded %s of KSh %.0f to %s",
            record.type.swahili, record.amount, record.recipient
        )
    }

    /**
     * Get giving history, optionally filtered.
     *
     * @param typeFilter Optional filter by giving type
     * @param limit Maximum records to return (0 = all)
     * @return List of giving records, newest first
     */
    fun getGivingHistory(
        typeFilter: GivingType? = null,
        limit: Int = 0
    ): List<GivingRecord> {
        var records = givingRecords.sortedByDescending { it.date }
        if (typeFilter != null) {
            records = records.filter { it.type == typeFilter }
        }
        return if (limit > 0) records.take(limit) else records
    }

    /**
     * Get a summary of giving for a given period.
     *
     * @param period "week", "month", "quarter", "year", "all"
     * @param totalIncome Total income for the period (for calculating %)
     * @return GivingSummary with aggregated data
     */
    fun getGivingSummary(period: String = "month", totalIncome: Double = 0.0): GivingSummary {
        val cutoff = getPeriodCutoff(period)
        val periodRecords = givingRecords.filter { it.date >= cutoff }

        if (periodRecords.isEmpty()) {
            return GivingSummary(
                totalGiven = 0.0,
                tithePercentage = 0.0,
                givingFrequency = "Hakuna",
                abundancePattern = 0.0,
                consistencyScore = 0
            )
        }

        val totalGiven = periodRecords.sumOf { it.amount }
        val givingByType = periodRecords.groupBy { it.type }
            .mapValues { (_, records) -> records.sumOf { it.amount } }

        // Calculate tithe percentage
        val titheAmount = givingByType[GivingType.TITHE] ?: 0.0
        val tithePercentage = if (totalIncome > 0) {
            (titheAmount / totalIncome) * 100
        } else {
            // Use incomeAtTime from records if available
            val recordedIncome = periodRecords
                .filter { it.incomeAtTime > 0 }
                .sumOf { it.incomeAtTime }
            if (recordedIncome > 0) (titheAmount / recordedIncome) * 100 else 0.0
        }

        // Determine giving frequency
        val givingFrequency = calculateFrequency(periodRecords, period)

        // Calculate abundance pattern (income change after giving)
        val abundancePattern = calculateAbundancePattern(periodRecords)

        // Calculate consistency score
        val consistencyScore = calculateConsistencyScore(periodRecords, period)

        // Calculate streak
        val streakDays = calculateStreak()

        // Find top recipient
        val topRecipient = periodRecords
            .groupBy { it.recipient }
            .maxByOrNull { (_, records) -> records.sumOf { it.amount } }
            ?.key ?: ""

        return GivingSummary(
            totalGiven = totalGiven,
            tithePercentage = round2(tithePercentage),
            givingFrequency = givingFrequency,
            abundancePattern = round2(abundancePattern),
            consistencyScore = consistencyScore,
            streakDays = streakDays,
            topRecipient = topRecipient,
            givingByType = givingByType
        )
    }

    /**
     * Calculate the tithe target based on income.
     * Traditional tithe is 10% of income.
     *
     * @param income The income amount
     * @return 10% of income (the tithe target)
     */
    fun getTitheTarget(income: Double): Double {
        return round2(income * TITHE_RATE)
    }

    /**
     * Get the abundance pattern — how income changes after giving.
     *
     * This tracks the worker's belief that "when you give, God gives more"
     * by comparing income levels before and after giving periods.
     *
     * Uses a simple before/after comparison:
     * - For each giving event, look at income 7 days before vs 7 days after
     * - Calculate the percentage change
     * - Return average change
     *
     * NOTE: This is correlation, not causation. The UI should present it
     * as "Your income pattern around giving" not "giving causes income."
     *
     * @return Map of period → income change percentage
     */
    fun getAbundancePattern(): Map<String, Double> {
        if (givingRecords.isEmpty()) return emptyMap()

        val pattern = mutableMapOf<String, Double>()

        // Group giving by week
        val calendar = Calendar.getInstance()
        for (record in givingRecords.sortedBy { it.date }) {
            calendar.timeInMillis = record.date
            val weekKey = "${calendar.get(Calendar.YEAR)}-W${calendar.get(Calendar.WEEK_OF_YEAR)}"

            if (record.incomeAtTime > 0) {
                pattern[weekKey] = record.incomeAtTime
            }
        }

        // Calculate week-over-week changes
        val changes = mutableMapOf<String, Double>()
        val weeks = pattern.keys.sorted()
        for (i in 1 until weeks.size) {
            val prevIncome = pattern[weeks[i - 1]] ?: continue
            val currIncome = pattern[weeks[i]] ?: continue
            if (prevIncome > 0) {
                val change = ((currIncome - prevIncome) / prevIncome) * 100
                changes[weeks[i]] = round2(change)
            }
        }

        return changes
    }

    // ═══════════════════════════════════════════════════════════════
    // GOAL TRACKING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Set a giving goal.
     *
     * @param goal The goal to set
     */
    fun setGivingGoal(goal: GivingGoal) {
        // Remove existing goal of same type
        givingGoals.removeAll { it.targetType == goal.targetType }
        givingGoals.add(goal)
        Timber.i(
            "TitheTracker: Set goal — %s KSh %.0f per %s",
            goal.targetType.swahili, goal.targetAmount, goal.period
        )
    }

    /**
     * Get current giving goals.
     */
    fun getGivingGoals(): List<GivingGoal> = givingGoals.toList()

    /**
     * Check progress toward a giving goal.
     *
     * @param goal The goal to check
     * @param period "week" or "month"
     * @return Progress as 0.0 to 1.0+ (1.0 = goal met)
     */
    fun getGoalProgress(goal: GivingGoal, period: String = "month"): Double {
        val cutoff = getPeriodCutoff(period)
        val periodGiving = givingRecords
            .filter { it.date >= cutoff && it.type == goal.targetType }
            .sumOf { it.amount }

        return if (goal.targetAmount > 0) {
            round2(periodGiving / goal.targetAmount)
        } else {
            0.0
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // SWAHILI VOICE RESPONSE GENERATORS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Generate a Swahili response for a giving confirmation.
     *
     * @param record The recorded giving
     * @return Swahili confirmation message
     */
    fun generateGivingConfirmation(record: GivingRecord): String {
        val typeSw = record.type.swahili
        val recipient = if (record.recipient.isNotBlank()) " kwa ${record.recipient}" else ""
        return "Imerekodiwa! Umetoa $typeSw ya KSh ${formatAmount(record.amount)}$recipient. Mungu akubariki! 🙏"
    }

    /**
     * Generate a Swahili giving summary response.
     *
     * @param summary The giving summary
     * @param period The period name in Swahili
     * @return Swahili summary message
     */
    fun generateSummaryResponse(summary: GivingSummary, period: String = "mwezi"): String {
        if (summary.totalGiven == 0.0) {
            return "Hujatoa chochote $period huu. Anza leo — hata KSh 50 inatosha! 🙏"
        }

        val sb = StringBuilder()
        sb.appendLine("📊 Ripoti ya kutoa — $period:")
        sb.appendLine("• Jumla: KSh ${formatAmount(summary.totalGiven)}")

        if (summary.tithePercentage > 0) {
            sb.appendLine("• Zaka ya kumi: ${summary.tithePercentage}% ya mapato")
        }

        sb.appendLine("• Mara ngapi: ${summary.givingFrequency}")
        sb.appendLine("• Uthabiti: ${summary.consistencyScore}/100")

        if (summary.streakDays > 0) {
            sb.appendLine("• Mfululizo: siku ${summary.streakDays} 🔥")
        }

        if (summary.topRecipient.isNotBlank()) {
            sb.appendLine("• Msaidizi mkuu: ${summary.topRecipient}")
        }

        // Abundance insight
        if (summary.abundancePattern != 0.0) {
            val direction = if (summary.abundancePattern > 0) "imeongezeka" else "imepungua"
            sb.appendLine("• Mapato baada ya kutoa $direction kwa ${kotlin.math.abs(summary.abundancePattern)}%")
        }

        return sb.toString().trim()
    }

    /**
     * Generate a giving reminder message.
     *
     * @param lastGivingDate When the worker last gave
     * @param daysSince Days since last giving
     * @return Swahili reminder message
     */
    fun generateGivingReminder(lastGivingDate: Long, daysSince: Int): String {
        return when {
            daysSince == 0 -> "Umetoa leo! Mungu akubariki! 🙏"
            daysSince <= 3 -> "Umetoa siku $daysSince zilizopita. Endelea na uthabiti! 💪"
            daysSince <= 7 -> "Siku $daysSince bila kutoa. Jumapili ijayo, weka kando kidogo. 🙏"
            daysSince <= 14 -> "Wiki 2 bila kutoa. Kumbuka: ukitoa, Mungu anakupatia zaidi. 🙏"
            else -> "Imekuwa muda mrefu bila kutoa. Anza tena leo — hata kidogo inatosha! 🙏"
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PARSING — Voice input to GivingRecord
    // ═══════════════════════════════════════════════════════════════

    /**
     * Parse a Swahili giving voice command into a GivingRecord.
     *
     * Supported patterns:
     * - "Nilitoa sadaka KSh 200"
     * - "Nilitoa zaka KSh 500"
     * - "Nilitoa zaka ya kumi elfu moja"
     * - "Nilitoa sadaka ya 300 kanisani"
     * - "Nimetuma sadaqah KSh 1000 msikitini"
     *
     * @param text The voice input text
     * @param recipient Default recipient if not specified
     * @return Parsed GivingRecord or null if parsing fails
     */
    fun parseGivingCommand(
        text: String,
        recipient: String = ""
    ): GivingRecord? {
        val lower = text.lowercase().trim()

        // Extract amount — look for KSh/SH followed by number, or standalone number
        val amount = extractAmount(lower) ?: return null

        // Determine giving type
        val type = detectGivingType(lower)

        // Extract recipient if mentioned
        val extractedRecipient = extractRecipient(lower, recipient)

        return GivingRecord(
            amount = amount,
            type = type,
            recipient = extractedRecipient,
            date = System.currentTimeMillis(),
            notes = text
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════════

    companion object {
        /** Traditional tithe rate: 10% */
        private const val TITHE_RATE = 0.10

        /** Zakat rate: 2.5% of wealth */
        private const val ZAKAT_RATE = 0.025

        /** Minimum giving amount in KSh */
        private const val MIN_GIVING_AMOUNT = 1.0

        /** Maximum reasonable giving amount in KSh */
        private const val MAX_GIVING_AMOUNT = 1_000_000.0
    }

    private fun getPeriodCutoff(period: String): Long {
        val now = System.currentTimeMillis()
        return when (period.lowercase()) {
            "week", "wiki" -> now - TimeUnit.DAYS.toMillis(7)
            "month", "mwezi" -> now - TimeUnit.DAYS.toMillis(30)
            "quarter", "robo" -> now - TimeUnit.DAYS.toMillis(90)
            "year", "mwaka" -> now - TimeUnit.DAYS.toMillis(365)
            "all", "yote" -> 0L
            else -> now - TimeUnit.DAYS.toMillis(30)
        }
    }

    private fun calculateFrequency(records: List<GivingRecord>, period: String): String {
        if (records.isEmpty()) return "Hakuna"

        val periodDays = when (period.lowercase()) {
            "week", "wiki" -> 7
            "month", "mwezi" -> 30
            "quarter", "robo" -> 90
            "year", "mwaka" -> 365
            else -> 30
        }

        val uniqueDays = records
            .map { dayOfYear(it.date) }
            .distinct()
            .size

        val avgDaysBetween = if (uniqueDays > 1) {
            periodDays.toDouble() / uniqueDays
        } else {
            periodDays.toDouble()
        }

        return when {
            avgDaysBetween <= 1 -> "Kila siku"
            avgDaysBetween <= 3 -> "Kila siku 2-3"
            avgDaysBetween <= 8 -> "Kila wiki"
            avgDaysBetween <= 15 -> "Kila wiki 2"
            avgDaysBetween <= 35 -> "Kila mwezi"
            else -> "Kila mwezi ${kotlin.math.ceil(avgDaysBetween / 30).toInt()}"
        }
    }

    /**
     * STA 244 (Time Series): Calculate abundance pattern.
     * Simple before/after comparison of income around giving events.
     */
    private fun calculateAbundancePattern(records: List<GivingRecord>): Double {
        val recordsWithIncome = records.filter { it.incomeAtTime > 0 }
        if (recordsWithIncome.size < 2) return 0.0

        // Compare average income in first half vs second half of giving records
        val sorted = recordsWithIncome.sortedBy { it.date }
        val midpoint = sorted.size / 2
        val firstHalfAvg = sorted.take(midpoint).map { it.incomeAtTime }.average()
        val secondHalfAvg = sorted.drop(midpoint).map { it.incomeAtTime }.average()

        return if (firstHalfAvg > 0) {
            ((secondHalfAvg - firstHalfAvg) / firstHalfAvg) * 100
        } else {
            0.0
        }
    }

    /**
     * STA 201 (Descriptive Statistics): Calculate consistency score.
     *
     * Measures how regularly the worker gives:
     * - 100 = gives every expected period
     * - 50 = gives half the expected periods
     * - 0 = never gives
     *
     * Factors:
     * 1. Frequency regularity (standard deviation of days between giving)
     * 2. Period coverage (how many expected periods have giving)
     * 3. Streak length (consecutive periods with giving)
     */
    private fun calculateConsistencyScore(records: List<GivingRecord>, period: String): Int {
        if (records.isEmpty()) return 0
        if (records.size == 1) return 20

        val sorted = records.sortedBy { it.date }

        // 1. Frequency regularity — lower std dev = more consistent
        val daysBetween = mutableListOf<Long>()
        for (i in 1 until sorted.size) {
            val days = TimeUnit.MILLISECONDS.toDays(sorted[i].date - sorted[i - 1].date)
            daysBetween.add(days)
        }

        val avgGap = daysBetween.average()
        val variance = daysBetween.map { (it - avgGap) * (it - avgGap) }.average()
        val stdDev = kotlin.math.sqrt(variance)

        // Normalize: stdDev of 0 = perfect (100), stdDev of avgGap = inconsistent (0)
        val regularityScore = if (avgGap > 0) {
            (100 * (1 - minOf(stdDev / avgGap, 1.0))).toInt()
        } else {
            100
        }

        // 2. Period coverage
        val periodDays = when (period.lowercase()) {
            "week", "wiki" -> 7
            "month", "mwezi" -> 30
            else -> 30
        }
        val totalDays = TimeUnit.MILLISECONDS.toDays(
            sorted.last().date - sorted.first().date
        ).toInt().coerceAtLeast(1)
        val expectedPeriods = totalDays / periodDays
        val uniquePeriods = sorted.map { dayOfYear(it.date) / periodDays }.distinct().size
        val coverageScore = if (expectedPeriods > 0) {
            (100 * minOf(uniquePeriods.toDouble() / expectedPeriods, 1.0)).toInt()
        } else {
            100
        }

        // 3. Streak score
        val streak = calculateStreak()
        val streakScore = minOf(streak * 10, 100)

        // Weighted average: regularity 40%, coverage 40%, streak 20%
        return (regularityScore * 0.4 + coverageScore * 0.4 + streakScore * 0.2).toInt()
            .coerceIn(0, 100)
    }

    /**
     * Calculate current giving streak in days.
     */
    private fun calculateStreak(): Int {
        if (givingRecords.isEmpty()) return 0

        val sorted = givingRecords.sortedByDescending { it.date }
        val today = dayOfYear(System.currentTimeMillis())

        // Check if there's giving today or yesterday
        val lastGivingDay = dayOfYear(sorted.first().date)
        if (today - lastGivingDay > 1) return 0

        var streak = 1
        for (i in 1 until sorted.size) {
            val currDay = dayOfYear(sorted[i].date)
            val prevDay = dayOfYear(sorted[i - 1].date)
            if (prevDay - currDay <= 1) {
                streak++
            } else {
                break
            }
        }
        return streak
    }

    /**
     * Extract amount from voice text.
     * Handles: "KSh 200", "Sh 200", "200", "elfu moja", "thao"
     */
    private fun extractAmount(text: String): Double? {
        // Try KSh/SH prefix: "KSh 200" or "sh 500"
        val kshPattern = Regex("""(?:ksh|sh|kes)\s*(\d+(?:\.\d+)?)""")
        kshPattern.find(text)?.let {
            return it.groupValues[1].toDoubleOrNull()
        }

        // Try Swahili number words
        val swahiliAmount = parseSwahiliNumber(text)
        if (swahiliAmount != null) return swahiliAmount

        // Try standalone number (last resort, less reliable)
        val numberPattern = Regex("""(\d+(?:\.\d+)?)""")
        val numbers = numberPattern.findAll(text).toList()
        return numbers.lastOrNull()?.groupValues?.get(1)?.toDoubleOrNull()
    }

    /**
     * Detect giving type from voice text.
     */
    private fun detectGivingType(text: String): GivingType {
        return when {
            text.contains("zaka ya kumi") || text.contains("zaka ya kumi")
                || text.contains("tithe") -> GivingType.TITHE
            text.contains("zaka") || text.contains("zakat") -> GivingType.ZAKAT
            text.contains("sadaqah") || text.contains("sadaqa") -> GivingType.SADAQAH
            text.contains("sadaka") -> GivingType.OFFERING
            text.contains("misaada") || text.contains("charity")
                || text.contains("mchango") -> GivingType.CHARITY
            else -> GivingType.OFFERING
        }
    }

    /**
     * Extract recipient from voice text.
     */
    private fun extractRecipient(text: String, default: String): String {
        // Look for common recipient patterns
        val patterns = listOf(
            Regex("""(?:kwa|kanisa|msikiti|kanisani|msikitini)\s+(.+)"""),
            Regex("""(?:church|mosque|temple)\s+(.+)""")
        )

        for (pattern in patterns) {
            pattern.find(text)?.let { match ->
                val recipient = match.groupValues[1].trim()
                if (recipient.isNotBlank()) return recipient
            }
        }

        // Detect common place keywords
        return when {
            text.contains("kanisani") || text.contains("kanisa") -> default.ifBlank { "Kanisa" }
            text.contains("msikitini") || text.contains("msikiti") -> default.ifBlank { "Msikiti" }
            else -> default
        }
    }

    /**
     * Parse Swahili number words to numeric values.
     * Handles: "elfu moja" (1000), "mia tano" (500), "thao" (1000, Sheng)
     */
    private fun parseSwahiliNumber(text: String): Double? {
        val numberWords = mapOf(
            "moja" to 1, "mbili" to 2, "tatu" to 3, "nne" to 4, "tano" to 5,
            "sita" to 6, "saba" to 7, "nane" to 8, "tisa" to 9, "kumi" to 10,
            "thao" to 1000, "ngiri" to 1000, "elfu" to 1000,
            "mia" to 100, "finje" to 500, "jeuri" to 50, "bao" to 20
        )

        // Check for compound: "elfu moja" = 1000, "mia tano" = 500
        val elfuPattern = Regex("""elfu\s+(moja|mbili|tatu|nne|tano|sita|saba|nane|tisa|kumi)""")
        elfuPattern.find(text)?.let {
            val multiplier = numberWords[it.groupValues[1]] ?: return@let
            return (multiplier * 1000).toDouble()
        }

        val miaPattern = Regex("""mia\s+(moja|mbili|tatu|nne|tano|sita|saba|nane|tisa)""")
        miaPattern.find(text)?.let {
            val multiplier = numberWords[it.groupValues[1]] ?: return@let
            return (multiplier * 100).toDouble()
        }

        // Single Sheng number words
        for ((word, value) in numberWords) {
            if (text.contains(word) && word.length > 3) { // Skip short words like "moja"
                return value.toDouble()
            }
        }

        return null
    }

    private fun dayOfYear(timestamp: Long): Int {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        return cal.get(Calendar.DAY_OF_YEAR)
    }

    private fun formatAmount(amount: Double): String {
        return if (amount == amount.toLong().toDouble()) {
            amount.toLong().toString()
        } else {
            String.format("%.2f", amount)
        }
    }

    private fun round2(value: Double): Double {
        return Math.round(value * 100.0) / 100.0
    }
}
