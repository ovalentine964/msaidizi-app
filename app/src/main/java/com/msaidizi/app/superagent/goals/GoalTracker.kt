package com.msaidizi.superagent.goals

import timber.log.Timber
import kotlin.math.*

/**
 * Goal Tracker — savings goals, milestones, and streaks.
 *
 * Workers set goals in voice:
 * - "Ninataka kununua friji mpya" (I want to buy a new fridge)
 * - "Lengo langu ni kuuza KSh 10,000 kwa siku" (My goal is KSh 10,000/day)
 * - "Nataka kusave KSh 50,000 kwa shule" (I want to save KSh 50,000 for school)
 *
 * Msaidizi breaks goals into actionable steps, tracks progress daily,
 * provides encouragement in Swahili, and adjusts goals based on reality.
 *
 * ## Features
 * - **Goal creation:** From voice or structured input
 * - **Progress tracking:** Daily/weekly contributions
 * - **Milestone celebrations:** 25%, 50%, 75%, 100%
 * - **Streak tracking:** Consecutive contribution days
 * - **Forecasting:** When will the goal be reached?
 * - **Adjustment:** Recalculate targets based on income patterns
 *
 * ## Academic Foundations
 * - **STA 341 (Estimation):** Forecast time to goal based on current trajectory
 * - **STA 342 (Hypothesis Testing):** Test if progress is statistically significant
 * - **ECO 210 (Quantitative Methods):** Break-even analysis for equipment goals
 * - **ECO 206 (Microfinance):** Savings behavior optimization
 *
 * @author Msaidizi Financial Team
 */
class GoalTracker {

    companion object {
        private const val TAG = "GoalTracker"

        /** Minimum data points for reliable time-to-goal forecast */
        private const val MIN_PROGRESS_ENTRIES = 3

        /** Milestone thresholds for celebration */
        private val MILESTONES = listOf(0.25, 0.50, 0.75, 1.00)

        /** Maximum active goals per worker (avoid overwhelm) */
        private const val MAX_ACTIVE_GOALS = 5

        /** Seconds in a day */
        private const val SECONDS_PER_DAY = 86_400L
    }

    // ═══════════════════════════════════════════════════════════════
    // GOAL CREATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Create a new goal.
     *
     * @param name Goal name/description
     * @param targetAmount Target amount in KSh
     * @param category Goal category
     * @param deadline Target date (Unix timestamp, 0 = no deadline)
     * @param deeperPurpose Why this goal matters
     * @return New [Goal] instance
     */
    fun createGoal(
        name: String,
        targetAmount: Double,
        category: GoalCategory = GoalCategory.SAVINGS,
        deadline: Long = 0,
        deeperPurpose: String = ""
    ): Goal {
        if (targetAmount <= 0) {
            throw IllegalArgumentException("Target amount must be positive")
        }

        // Calculate daily and weekly targets if deadline is set
        val now = System.currentTimeMillis() / 1000
        val dailyTarget = if (deadline > 0 && deadline > now) {
            val daysRemaining = ((deadline - now) / SECONDS_PER_DAY).toInt().coerceAtLeast(1)
            targetAmount / daysRemaining
        } else 0.0

        val weeklyTarget = dailyTarget * 7

        return Goal(
            name = name,
            targetAmount = targetAmount,
            category = category,
            deadline = deadline,
            dailyTarget = dailyTarget,
            weeklyTarget = weeklyTarget,
            deeperPurpose = deeperPurpose
        )
    }

    /**
     * Parse a goal from voice input.
     *
     * Examples:
     * - "Ninataka kununua friji mpya elfu hamsini"
     * - "Lengo langu ni kusave elfu ishirini kwa shule"
     *
     * @param voiceInput Voice text describing the goal
     * @return Parsed [Goal] or null if parsing failed
     */
    fun createFromVoice(voiceInput: String): Goal? {
        val normalized = voiceInput.lowercase().trim()

        // Extract target amount
        val amount = extractAmount(normalized)
        if (amount <= 0) return null

        // Determine category from keywords
        val category = when {
            normalized.contains("friji") || normalized.contains("tv") ||
                normalized.contains("simu") || normalized.contains("gadget") -> GoalCategory.ASSET
            normalized.contains("stock") || normalized.contains("bidhaa") ||
                normalized.contains("inventory") -> GoalCategory.INVENTORY
            normalized.contains("shule") || normalized.contains("school") ||
                normalized.contains("fees") || normalized.contains("elimu") -> GoalCategory.EDUCATION
            normalized.contains("dharura") || normalized.contains("emergency") -> GoalCategory.EMERGENCY
            normalized.contains("biashara") || normalized.contains("business") -> GoalCategory.BUSINESS
            normalized.contains("mkopo") || normalized.contains("deni") ||
                normalized.contains("loan") || normalized.contains("debt") -> GoalCategory.DEBT
            normalized.contains("machine") || normalized.contains("mashine") -> GoalCategory.EQUIPMENT
            else -> GoalCategory.SAVINGS
        }

        // Extract goal name (simplified)
        val name = extractGoalName(normalized, amount)

        return createGoal(
            name = name,
            targetAmount = amount,
            category = category
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // PROGRESS TRACKING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Record a contribution toward a goal.
     *
     * @param goal Current goal state
     * @param amount Amount contributed
     * @param note Optional note about the contribution
     * @return Pair of updated goal and any new milestones reached
     */
    fun recordProgress(
        goal: Goal,
        amount: Double,
        note: String = ""
    ): Pair<Goal, List<GoalMilestone>> {
        if (goal.status != GoalStatus.ACTIVE) {
            return Pair(goal, emptyList())
        }

        val newAmount = goal.currentAmount + amount
        val now = System.currentTimeMillis() / 1000

        // Update streak
        val lastProgressDay = goal.updatedAt / SECONDS_PER_DAY
        val today = now / SECONDS_PER_DAY
        val newStreak = if (today - lastProgressDay <= 1) {
            goal.streak + 1
        } else {
            1 // Reset streak
        }
        val bestStreak = max(goal.bestStreak, newStreak)

        // Check for new milestones
        val previousProgress = goal.progress
        val newProgress = if (goal.targetAmount > 0) newAmount / goal.targetAmount else 0.0
        val newMilestones = MILESTONES
            .filter { it in (previousProgress + 0.001)..newProgress }
            .map { milestone ->
                GoalMilestone(
                    goalId = goal.id,
                    percentage = milestone,
                    reachedAt = now
                )
            }

        val newStatus = if (newAmount >= goal.targetAmount) {
            GoalStatus.COMPLETED
        } else {
            goal.status
        }

        val updatedGoal = goal.copy(
            currentAmount = newAmount,
            streak = newStreak,
            bestStreak = bestStreak,
            status = newStatus,
            updatedAt = now
        )

        return Pair(updatedGoal, newMilestones)
    }

    // ═══════════════════════════════════════════════════════════════
    // FORECASTING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Forecast when a goal will be reached.
     *
     * Uses linear extrapolation from recent contribution history.
     *
     * @param goal The goal to forecast
     * @param progressHistory Recent progress entries
     * @return [GoalForecast] with projected completion
     */
    fun forecast(
        goal: Goal,
        progressHistory: List<GoalProgress>
    ): GoalForecast {
        if (progressHistory.size < MIN_PROGRESS_ENTRIES) {
            return GoalForecast(
                goal = goal,
                projectedCompletionDate = 0,
                daysToCompletion = -1,
                dailyRequired = goal.dailyTarget,
                weeklyRequired = goal.weeklyTarget,
                isOnTrack = false,
                confidenceLevel = 0.0,
                message = "Hakuna data ya kutosha kutabiri. Weka michango ${MIN_PROGRESS_ENTRIES} au zaidi."
            )
        }

        // Calculate daily contribution rate
        val sortedHistory = progressHistory.sortedBy { it.timestamp }
        val firstEntry = sortedHistory.first()
        val lastEntry = sortedHistory.last()
        val daysBetween = max(1, ((lastEntry.timestamp - firstEntry.timestamp) / SECONDS_PER_DAY).toInt())
        val totalContributed = sortedHistory.sumOf { it.amount }
        val dailyRate = totalContributed / daysBetween.toDouble()

        // Project completion
        val remaining = goal.remaining
        val daysToCompletion = if (dailyRate > 0) {
            (remaining / dailyRate).toInt()
        } else {
            Int.MAX_VALUE
        }

        val now = System.currentTimeMillis() / 1000
        val projectedDate = now + (daysToCompletion * SECONDS_PER_DAY)

        // Check if on track for deadline
        val isOnTrack = if (goal.deadline > 0) {
            daysToCompletion <= goal.daysUntilDeadline
        } else true

        // Confidence based on data quantity and consistency
        val confidence = min(1.0, progressHistory.size.toDouble() / 10.0)

        // Calculate required daily/weekly amounts for deadline
        val daysRemaining = if (goal.deadline > 0) {
            goal.daysUntilDeadline.coerceAtLeast(1)
        } else daysToCompletion
        val dailyRequired = if (daysRemaining > 0) remaining / daysRemaining else 0.0
        val weeklyRequired = dailyRequired * 7

        val message = buildForecastMessage(
            goal, daysToCompletion, dailyRate, isOnTrack, dailyRequired
        )

        return GoalForecast(
            goal = goal,
            projectedCompletionDate = projectedDate,
            daysToCompletion = daysToCompletion,
            dailyRequired = dailyRequired,
            weeklyRequired = weeklyRequired,
            isOnTrack = isOnTrack,
            confidenceLevel = confidence,
            message = message
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // GOAL ADJUSTMENT
    // ═══════════════════════════════════════════════════════════════

    /**
     * Adjust goal targets based on actual income patterns.
     *
     * If the worker's income has changed, recalculate daily/weekly targets.
     *
     * @param goal Current goal
     * @param averageDailyIncome Average daily income
     * @return Updated goal with recalculated targets
     */
    fun adjustTargets(goal: Goal, averageDailyIncome: Double): Goal {
        val savingsRate = 0.15 // 15% of income for goals
        val newDailyTarget = averageDailyIncome * savingsRate
        val newWeeklyTarget = newDailyTarget * 7

        // Recalculate deadline if needed
        val newDeadline = if (goal.deadline > 0 && newDailyTarget > 0) {
            val daysNeeded = (goal.remaining / newDailyTarget).toInt()
            val now = System.currentTimeMillis() / 1000
            now + (daysNeeded * SECONDS_PER_DAY)
        } else {
            goal.deadline
        }

        return goal.copy(
            dailyTarget = newDailyTarget,
            weeklyTarget = newWeeklyTarget,
            deadline = newDeadline,
            updatedAt = System.currentTimeMillis() / 1000
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // ENCOURAGEMENT
    // ═══════════════════════════════════════════════════════════════

    /**
     * Generate encouragement message based on goal progress.
     *
     * @param goal Current goal state
     * @param milestones New milestones just reached
     * @return Encouragement message in Swahili
     */
    fun encourage(goal: Goal, milestones: List<GoalMilestone> = emptyList()): String {
        return buildString {
            // Milestone celebrations first
            milestones.forEach { milestone ->
                when (milestone.percentage) {
                    0.25 -> append("🎉 Umefikia 25% ya lengo lako! Uko njiani!\n")
                    0.50 -> append("🎊 Nusu ya lengo lako! Umefikia 50%! Endelea hivyo!\n")
                    0.75 -> append("⭐ 75% imefikiwa! Karibu kufikia lengo lako!\n")
                    1.00 -> append("🏆 HONGERA! Umefikia lengo lako la ${goal.name}! 🎉🎉🎉\n")
                }
            }

            // Streak encouragement
            if (goal.streak >= 7) {
                append("🔥 Streak yako ni siku ${goal.streak}! Uthabiti wako ni wa ajabu!\n")
            } else if (goal.streak >= 3) {
                append("💪 Umefanya michango siku ${goal.streck} mfululizo!\n")
            }

            // Progress-based encouragement
            when {
                goal.progressPercent >= 75 ->
                    append("Umefikia ${goal.progressPercent}%! Karibu sana!")
                goal.progressPercent >= 50 ->
                    append("Umefikia ${goal.progressPercent}%. Njia nzuri!")
                goal.progressPercent >= 25 ->
                    append("Umefikia ${goal.progressPercent}%. Endelea hivyo!")
                goal.progressPercent > 0 ->
                    append("Umefikia ${goal.progressPercent}%. Kila hatua inasaidia!")
                else ->
                    append("Anza leo! Hata KSh 50 inasaidia.")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // VOICE PARSING HELPERS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Extract monetary amount from voice text.
     */
    private fun extractAmount(text: String): Double {
        val swahiliNumbers = mapOf(
            "moja" to 1, "mbili" to 2, "tatu" to 3, "nne" to 4, "tano" to 5,
            "sita" to 6, "saba" to 7, "nane" to 8, "tisa" to 9, "kumi" to 10,
            "ishirini" to 20, "thelathini" to 30, "arobaini" to 40,
            "hamsini" to 50, "sitini" to 60, "sabini" to 70,
            "themanini" to 80, "tisini" to 90
        )

        val magnitudes = mapOf(
            "mia" to 100, "elfu" to 1000, "laki" to 100_000
        )

        val words = text.lowercase().split("\\s+".toRegex())
        var total = 0.0
        var current = 0.0
        var i = 0

        while (i < words.size) {
            val word = words[i]
            val magnitude = magnitudes[word]
            if (magnitude != null) {
                if (i + 1 < words.size) {
                    val nextNum = swahiliNumbers[words[i + 1]]
                    if (nextNum != null) {
                        total += magnitude * nextNum
                        i += 2
                        continue
                    }
                }
                if (current > 0) {
                    total += current * magnitude
                    current = 0.0
                } else {
                    total += magnitude
                }
                i++
                continue
            }

            val num = swahiliNumbers[word] ?: word.toDoubleOrNull()
            if (num != null) {
                current = num.toDouble()
            }
            i++
        }
        total += current

        return total
    }

    /**
     * Extract goal name from voice text.
     */
    private fun extractGoalName(text: String, amount: Double): String {
        // Remove amount-related words and common prefixes
        var name = text
            .replace(Regex("\\d+"), "")
            .replace("elfu", "")
            .replace("mia", "")
            .replace("ninataka", "")
            .replace("nataka", "")
            .replace("lengo langu ni", "")
            .replace("kusave", "")
            .replace("kununua", "")
            .trim()
            .split("\\s+".toRegex())
            .filter { it.isNotBlank() }
            .take(5)
            .joinToString(" ")

        return if (name.isBlank()) "Lengo la KSh ${amount.toInt()}" else name.replaceFirstChar { it.uppercase() }
    }

    // ═══════════════════════════════════════════════════════════════
    // MESSAGE GENERATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Build forecast message in Swahili.
     */
    private fun buildForecastMessage(
        goal: Goal,
        daysToCompletion: Int,
        dailyRate: Double,
        isOnTrack: Boolean,
        dailyRequired: Double
    ): String {
        return buildString {
            append("🔮 Utabiri wa lengo '${goal.name}':\n\n")

            append("Umechangia KSh ${formatAmount(goal.currentAmount)} ")
            append("kati ya KSh ${formatAmount(goal.targetAmount)} ")
            append("(${goal.progressPercent}%)\n")

            if (dailyRate > 0) {
                append("Kasi ya sasa: KSh ${formatAmount(dailyRate)} kwa siku\n")
                append("Siku za kufikia lengo: $daysToCompletion\n\n")
            }

            when {
                goal.status == GoalStatus.COMPLETED ->
                    append("🎉 Lengo limefikiwa! Hongera!")
                isOnTrack ->
                    append("✅ Uko njiani! Endelea hivyo.")
                goal.deadline > 0 ->
                    append("⚠️ Unahitaji KSh ${formatAmount(dailyRequired)} kwa siku ili kufikia muda.")
                else ->
                    append("Endelea kuchangia — kila KSh inasaidia!")
            }
        }
    }

    /**
     * Format amount for display.
     */
    private fun formatAmount(amount: Double): String {
        return when {
            amount >= 1_000_000 -> String.format("%.1fM", amount / 1_000_000.0)
            amount >= 1_000 -> String.format("%,.0f", amount)
            else -> String.format("%.0f", amount)
        }
    }
}
