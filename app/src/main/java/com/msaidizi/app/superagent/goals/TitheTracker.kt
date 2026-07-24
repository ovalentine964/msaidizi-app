package com.msaidizi.app.superagent.goals

import timber.log.Timber
import java.util.Calendar
import kotlin.math.*

/**
 * Tithe & Giving Tracker for informal workers.
 *
 * Many informal workers in Kenya tithe (10% of income) to their church
 * but never track it. This tracker helps workers:
 * - See exactly how much they've given over time
 * - Track the "abundance pattern" — income after giving periods
 * - Set giving goals and maintain consistency
 * - Understand the importance of consistent giving through data
 *
 * ## Academic Foundations
 * - **ECO 206 (Microfinance):** Savings behavior, financial discipline,
 *   commitment devices — tithing acts as a forced savings mechanism
 * - **STA 341 (Estimation):** Bayesian updating of giving patterns
 * - **PSY 200 (Behavioral Economics):** Mental accounting, pro-social
 *   spending and wellbeing, warm-glow giving
 *
 * @author Msaidizi Financial Team
 */
class TitheTracker {

    companion object {
        private const val TAG = "TitheTracker"

        /** Standard tithe rate */
        private const val TITHE_RATE = 0.10 // 10%

        /** Seconds in a day */
        private const val SECONDS_PER_DAY = 86_400L

        /** Seconds in a month (approximate) */
        private const val SECONDS_PER_MONTH = 30 * SECONDS_PER_DAY
    }

    // ═══════════════════════════════════════════════════════════════
    // GIVING RECORDING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Record a giving entry.
     *
     * @param type Type of giving (tithe, offering, etc.)
     * @param amount Amount given in KSh
     * @param recipient Who received it
     * @param incomeAtTime Income at time of giving
     * @param notes Optional notes
     * @return New [GivingRecord]
     */
    fun recordGiving(
        type: GivingType,
        amount: Double,
        recipient: String = "",
        incomeAtTime: Double = 0.0,
        notes: String = ""
    ): GivingRecord {
        return GivingRecord(
            type = type,
            amount = amount,
            recipient = recipient,
            incomeAtTime = incomeAtTime,
            notes = notes
        )
    }

    /**
     * Parse giving from voice input.
     *
     * Examples:
     * - "Nimetoa mia mbili kwa kanisa" (I gave 200 to church)
     * - "Zakat elfu moja" (Zakat 1000)
     * - "Sadaka ya elfu tano" (Offering of 5000)
     *
     * @param voiceInput Voice text describing the giving
     * @return Parsed [GivingRecord] or null if parsing failed
     */
    fun recordFromVoice(voiceInput: String): GivingRecord? {
        val normalized = voiceInput.lowercase().trim()

        // Extract amount
        val amount = extractAmount(normalized)
        if (amount <= 0) return null

        // Determine giving type
        val type = when {
            normalized.contains("zakat") || normalized.contains("zaka") -> GivingType.ZAKAT
            normalized.contains("sadaka") || normalized.contains("sadaqah") -> GivingType.SADAQAH
            normalized.contains("tithe") || normalized.contains("zakat") ||
                normalized.contains("kanisa") || normalized.contains("church") -> GivingType.TITHE
            normalized.contains("offering") || normalized.contains("harambee") -> GivingType.OFFERING
            normalized.contains("mchango") || normalized.contains("charity") -> GivingType.CHARITY
            else -> GivingType.TITHE
        }

        // Extract recipient
        val recipient = extractRecipient(normalized)

        return recordGiving(
            type = type,
            amount = amount,
            recipient = recipient
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // GIVING ANALYSIS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Analyze giving patterns.
     *
     * @param records All giving records
     * @param totalIncome Total income for the period
     * @return [GivingTrackerResult] with analysis
     */
    fun analyze(
        records: List<GivingRecord>,
        totalIncome: Double = 0.0
    ): GivingTrackerResult {
        if (records.isEmpty()) {
            return GivingTrackerResult(
                totalGiving = 0.0,
                givingByType = emptyMap(),
                monthlyAverage = 0.0,
                givingAsIncomePercent = 0.0,
                consistencyScore = 0.0,
                streak = 0,
                message = "Hakuna miamala ya kutoa bado. Anza kurekodi kutoa kwako!"
            )
        }

        val totalGiving = records.sumOf { it.amount }

        // Breakdown by type
        val givingByType = records
            .groupBy { it.type }
            .mapValues { (_, entries) -> entries.sumOf { it.amount } }

        // Monthly average
        val firstRecord = records.minOfOrNull { it.date } ?: System.currentTimeMillis()
        val monthsSpan = max(1, ((System.currentTimeMillis() - firstRecord) / SECONDS_PER_MONTH).toInt())
        val monthlyAverage = totalGiving / monthsSpan

        // Giving as income percentage
        val totalIncomeTracked = records.sumOf { it.incomeAtTime }
        val givingAsIncomePercent = if (totalIncome > 0) {
            totalGiving / totalIncome * 100
        } else if (totalIncomeTracked > 0) {
            totalGiving / totalIncomeTracked * 100
        } else 0.0

        // Consistency score (how regular is giving?)
        val consistencyScore = calculateConsistency(records)

        // Streak (consecutive months with giving)
        val streak = calculateStreak(records)

        val message = buildAnalysisMessage(
            totalGiving, givingByType, monthlyAverage,
            givingAsIncomePercent, consistencyScore, streak
        )

        return GivingTrackerResult(
            totalGiving = totalGiving,
            givingByType = givingByType,
            monthlyAverage = monthlyAverage,
            givingAsIncomePercent = givingAsIncomePercent,
            consistencyScore = consistencyScore,
            streak = streak,
            message = message
        )
    }

    /**
     * Calculate tithe recommendation based on income.
     *
     * @param income Income amount
     * @param givingType Type of giving (default: tithe at 10%)
     * @return Recommended giving amount
     */
    fun recommendTithe(income: Double, givingType: GivingType = GivingType.TITHE): Double {
        return when (givingType) {
            GivingType.TITHE -> income * TITHE_RATE
            GivingType.ZAKAT -> income * 0.025 // 2.5% for Zakat
            else -> income * TITHE_RATE
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // CONSISTENCY CALCULATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Calculate giving consistency score (0-100).
     *
     * Based on how regularly the worker gives (weekly/monthly pattern).
     */
    private fun calculateConsistency(records: List<GivingRecord>): Double {
        if (records.size < 2) return 0.0

        val sorted = records.sortedBy { it.date }
        val intervals = mutableListOf<Long>()

        for (i in 1 until sorted.size) {
            intervals.add(sorted[i].date - sorted[i - 1].date)
        }

        if (intervals.isEmpty()) return 0.0

        val avgInterval = intervals.average()
        val variance = intervals.map { (it - avgInterval).pow(2) }.average()
        val cv = if (avgInterval > 0) sqrt(variance) / avgInterval else 1.0

        // Lower coefficient of variation = more consistent
        return ((1.0 - cv.coerceIn(0.0, 1.0)) * 100).coerceIn(0.0, 100.0)
    }

    /**
     * Calculate giving streak (consecutive months with giving).
     */
    private fun calculateStreak(records: List<GivingRecord>): Int {
        if (records.isEmpty()) return 0

        val sorted = records.sortedByDescending { it.date }
        val cal = Calendar.getInstance()

        // Group by month
        val monthsWithGiving = sorted
            .groupBy { record ->
                cal.timeInMillis = record.date
                "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}"
            }
            .keys
            .sorted()
            .reversed()

        if (monthsWithGiving.isEmpty()) return 0

        // Count consecutive months from most recent
        var streak = 1
        cal.timeInMillis = sorted.first().date

        for (i in 1 until monthsWithGiving.size) {
            cal.add(Calendar.MONTH, -1)
            val expectedMonth = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}"
            if (monthsWithGiving[i] == expectedMonth) {
                streak++
            } else {
                break
            }
        }

        return streak
    }

    // ═══════════════════════════════════════════════════════════════
    // VOICE PARSING HELPERS
    // ═══════════════════════════════════════════════════════════════

    private fun extractAmount(text: String): Double {
        val swahiliNumbers = mapOf(
            "moja" to 1, "mbili" to 2, "tatu" to 3, "nne" to 4, "tano" to 5,
            "sita" to 6, "saba" to 7, "nane" to 8, "tisa" to 9, "kumi" to 10,
            "ishirini" to 20, "thelathini" to 30, "arobaini" to 40,
            "hamsini" to 50, "sitini" to 60, "sabini" to 70,
            "themanini" to 80, "tisini" to 90
        )
        val magnitudes = mapOf("mia" to 100, "elfu" to 1000, "laki" to 100_000)
        val words = text.lowercase().split("\\s+".toRegex())
        var total = 0.0
        var current = 0.0
        var i = 0
        while (i < words.size) {
            val word = words[i]
            val mag = magnitudes[word]
            if (mag != null) {
                if (i + 1 < words.size) {
                    val next = swahiliNumbers[words[i + 1]]
                    if (next != null) { total += mag * next; i += 2; continue }
                }
                if (current > 0) { total += current * mag; current = 0.0 } else { total += mag }
                i++; continue
            }
            val num = swahiliNumbers[word] ?: word.toDoubleOrNull()
            if (num != null) current = num.toDouble()
            i++
        }
        return total + current
    }

    private fun extractRecipient(text: String): String {
        val recipients = listOf(
            "kanisa" to "Kanisa", "church" to "Church",
            "msikiti" to "Msikiti", "mosque" to "Mosque",
            "harambee" to "Harambee", "chama" to "Chama"
        )
        for ((key, name) in recipients) {
            if (text.contains(key)) return name
        }
        return ""
    }

    // ═══════════════════════════════════════════════════════════════
    // MESSAGE GENERATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Build analysis message in Swahili.
     */
    private fun buildAnalysisMessage(
        totalGiving: Double,
        givingByType: Map<GivingType, Double>,
        monthlyAverage: Double,
        givingAsIncomePercent: Double,
        consistencyScore: Double,
        streak: Int
    ): String {
        return buildString {
            append("🙏 Muhtasari wa Kutoa:\n\n")
            append("Jumla: KSh ${formatAmount(totalGiving)}\n")
            append("Wastani wa mwezi: KSh ${formatAmount(monthlyAverage)}\n\n")

            givingByType.forEach { (type, amount) ->
                val emoji = when (type) {
                    GivingType.TITHE -> "⛪"
                    GivingType.OFFERING -> "🕯️"
                    GivingType.ZAKAT -> "🌙"
                    GivingType.SADAQAH -> "💛"
                    GivingType.CHARITY -> "🤝"
                    else -> "🎁"
                }
                append("$emoji ${type.name}: KSh ${formatAmount(amount)}\n")
            }

            if (givingAsIncomePercent > 0) {
                append("\nKutoa ni ${givingAsIncomePercent.toInt()}% ya mapato yako.\n")
            }

            if (streak > 0) {
                append("\n🔥 Streak: mwezi $streak mfululizo wa kutoa!\n")
            }

            when {
                consistencyScore >= 70 ->
                    append("\n✅ Uthabiti wako wa kutoa ni mzuri!")
                consistencyScore >= 40 ->
                    append("\n⚠️ Jaribu kutoa kwa uthabiti zaidi.")
                else ->
                    append("\n💡 Kutoa kwa uthabiti kunasaidia zaidi.")
            }
        }
    }

    private fun formatAmount(amount: Double): String {
        return when {
            amount >= 1_000_000 -> String.format("%.1fM", amount / 1_000_000.0)
            amount >= 1_000 -> String.format("%,.0f", amount)
            else -> String.format("%.0f", amount)
        }
    }
}
