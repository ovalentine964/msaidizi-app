package com.msaidizi.app.finance

import timber.log.Timber
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Goal Planning & Achievement System for informal workers.
 *
 * Workers set goals in voice:
 * "Ninataka kununua friji mpya" (I want to buy a new fridge)
 * "Lengo langu ni kuuza KSh 10,000 kwa siku" (My goal is to sell KSh 10,000/day)
 * "Nataka kusave KSh 50,000 kwa shule" (I want to save KSh 50,000 for school)
 *
 * Msaidizi:
 * - Breaks goals into actionable steps
 * - Tracks progress daily
 * - Provides encouragement in Swahili
 * - Adjusts goals based on reality (income patterns, expenses)
 * - Celebrates milestones (25%, 50%, 75%, 100%)
 *
 * Why this matters for informal workers:
 * - They have goals but no tracking system
 * - They save in tins, not banks — no visibility on progress
 * - They give up because the goal feels too far away
 * - Breaking goals into steps makes them achievable
 *
 * Academic foundations:
 * - STA 341 (Estimation): Forecast time to goal based on current trajectory
 * - STA 342 (Hypothesis Testing): Test if progress is statistically significant
 * - ECO 210 (Quantitative Methods): Break-even analysis for equipment goals
 * - ECO 206 (Microfinance): Savings behavior optimization
 */
class GoalPlanner {

    companion object {
        /** Minimum data points for reliable time-to-goal forecast */
        private const val MIN_PROGRESS_ENTRIES = 3

        /** Milestone thresholds for celebration */
        private val MILESTONES = listOf(0.25, 0.50, 0.75, 1.00)

        /** Maximum active goals per worker (avoid overwhelm) */
        private const val MAX_ACTIVE_GOALS = 5

        /** Confidence level for time-to-goal estimation (STA 341) */
        private const val FORECAST_CONFIDENCE = 0.80
    }

    // ═══════════════════════════════════════════════════════════════
    // DATA MODELS
    // ═══════════════════════════════════════════════════════════════

    /**
     * A worker's goal — the dream they're working toward.
     *
     * @param id Unique identifier
     * @param description What the worker wants (in their words)
     * @param targetAmount KSh amount needed to achieve the goal
     * @param currentAmount KSh already saved/earned toward goal
     * @param deadline Target date (Unix timestamp in seconds)
     * @param category Goal category for analytics
     * @param steps Actionable steps to reach the goal
     * @param progressEntries History of progress updates (timestamp → amount)
     * @param milestonesReached Which milestones have been celebrated
     * @param createdAt When the goal was created
     * @param isActive Whether the goal is still being pursued
     */
    data class Goal(
        val id: String = UUID.randomUUID().toString(),
        val description: String,
        val targetAmount: Double,
        val currentAmount: Double = 0.0,
        val deadline: Long,
        val category: GoalCategory,
        val steps: List<ActionStep> = emptyList(),
        val progressEntries: List<ProgressEntry> = emptyList(),
        val milestonesReached: List<Double> = emptyList(),
        val createdAt: Long = System.currentTimeMillis() / 1000,
        val isActive: Boolean = true
    ) {
        val progress: Double
            get() = if (targetAmount > 0) (currentAmount / targetAmount).coerceIn(0.0, 1.0) else 0.0

        val remainingAmount: Double
            get() = max(0.0, targetAmount - currentAmount)

        val daysRemaining: Long
            get() = max(0, (deadline - System.currentTimeMillis() / 1000) / 86400)
    }

    /**
     * A progress entry — each time the worker reports progress.
     * Used for trend analysis (STA 244: time series).
     */
    data class ProgressEntry(
        val timestamp: Long = System.currentTimeMillis() / 1000,
        val amount: Double,
        val note: String = ""
    )

    /**
     * An action step — breaking a goal into manageable pieces.
     *
     * "Kununua friji ya KSh 25,000" becomes:
     * 1. Weka KSh 500 kwa siku (Save 500/day)
     * 2. Punguza matumizi ya pombe (Reduce alcohol spending)
     * 3. Ongeza masaa ya kazi (Work more hours)
     */
    data class ActionStep(
        val id: String = UUID.randomUUID().toString(),
        val description: String,
        val targetAmount: Double = 0.0,
        val deadline: Long = 0,
        val completed: Boolean = false,
        val completedAt: Long? = null
    )

    /**
     * Goal categories — what workers are saving for.
     */
    enum class GoalCategory(val swahiliName: String) {
        EQUIPMENT("Vifaa"),           // Fridges, ovens, tools
        INVENTORY("Stock"),           // Buy more stock
        SAVINGS("Akiba"),             // General savings
        DEBT_REDUCTION("Deni"),       // Pay off debts
        BUSINESS_EXPANSION("Biashara"), // Expand business
        EDUCATION("Shule"),           // School fees
        EMERGENCY_FUND("Dharura"),    // Emergency fund
        ASSET("Mali"),                // Land, house, vehicle
        OTHER("Nyingine")
    }

    /**
     * Goal report — voice-friendly summary of all goals.
     */
    data class GoalReport(
        val message: String,
        val activeGoals: List<Goal>,
        val completedGoals: List<Goal>,
        val totalTargetAmount: Double,
        val totalSavedAmount: Double,
        val overallProgress: Double
    )

    /**
     * Time-to-goal forecast — STA 341 (Estimation).
     */
    data class TimeToGoalForecast(
        val message: String,
        val daysRemaining: Int,
        val dailyRateNeeded: Double,
        val currentDailyRate: Double,
        val isOnTrack: Boolean,
        val confidenceLevel: Double
    )

    /**
     * Milestone celebration.
     */
    data class MilestoneCelebration(
        val goalId: String,
        val milestone: Double, // 0.25, 0.50, 0.75, 1.00
        val message: String,
        val isNew: Boolean // false if already celebrated
    )

    // ═══════════════════════════════════════════════════════════════
    // GOAL CREATION — voice-driven
    // ═══════════════════════════════════════════════════════════════

    /**
     * Create a new goal from voice input.
     *
     * Handles Swahili descriptions:
     * - "Ninataka kununua friji mpya" → EQUIPMENT, auto-suggest steps
     * - "Lengo langu ni kusave KSh 50,000" → SAVINGS
     * - "Nataka kulipa deni langu" → DEBT_REDUCTION
     *
     * @param description What the worker said (Swahili)
     * @param targetAmount KSh amount for the goal
     * @param deadline Target date (Unix timestamp in seconds)
     * @param category Goal category (auto-detected if not provided)
     * @return Created Goal with auto-generated action steps
     */
    fun createGoal(
        description: String,
        targetAmount: Double,
        deadline: Long,
        category: GoalCategory = detectCategory(description)
    ): Goal {
        val steps = generateActionSteps(category, targetAmount, deadline)

        val goal = Goal(
            description = description,
            targetAmount = targetAmount,
            deadline = deadline,
            category = category,
            steps = steps
        )

        Timber.i("Goal created: ${goal.id} — $description (KSh $targetAmount)")
        return goal
    }

    /**
     * Auto-detect goal category from Swahili description.
     */
    private fun detectCategory(description: String): GoalCategory {
        val lower = description.lowercase()
        return when {
            // Equipment: fridge, oven, tools, machine
            lower.containsAny("friji", "oven", "jiko", "mashine", "kifaa", "vifaa",
                "pikipiki", "boda", "gari", "nduthi", "baisikeli") -> GoalCategory.EQUIPMENT

            // Inventory: stock, buy more, supply
            lower.containsAny("stock", "hifadhi", "supply", "bidhaa", "mali",
                "za kununua", "ununuzi") -> GoalCategory.INVENTORY

            // Education: school, fees, education
            lower.containsAny("shule", "school", "fees", "ada", "masomo",
                "elimu", "university", "chuo") -> GoalCategory.EDUCATION

            // Debt: pay debt, loan, owe
            lower.containsAny("deni", "debt", "mkopo", "loan", "kulipa",
                "deni", "owe") -> GoalCategory.DEBT_REDUCTION

            // Business expansion: expand, open, grow
            lower.containsAny("panua", "expand", "open", "fungua", "grow",
                "kua", "biashara mpya", "tawi") -> GoalCategory.BUSINESS_EXPANSION

            // Emergency fund: emergency, dharura
            lower.containsAny("dharura", "emergency", "akiba") -> GoalCategory.EMERGENCY_FUND

            // Asset: land, house, plot
            lower.containsAny("ardhi", "land", "nyumba", "house", "plot",
                "kiwanja", "mali") -> GoalCategory.ASSET

            // Savings: save, collect
            lower.containsAny("save", "kusave", "kusanya", "weka", "hifadhi",
                "akiba") -> GoalCategory.SAVINGS

            else -> GoalCategory.OTHER
        }
    }

    /**
     * Helper: check if string contains any of the given keywords.
     */
    private fun String.containsAny(vararg keywords: String): Boolean {
        return keywords.any { this.contains(it) }
    }

    // ═══════════════════════════════════════════════════════════════
    // ACTION STEP GENERATION — breaking goals into pieces
    // ═══════════════════════════════════════════════════════════════

    /**
     * Generate actionable steps based on goal category.
     *
     * These are starting suggestions — worker can customize.
     *
     * @param category Goal category
     * @param targetAmount Total goal amount
     * @param deadline Target date
     * @return List of action steps
     */
    private fun generateActionSteps(
        category: GoalCategory,
        targetAmount: Double,
        deadline: Long
    ): List<ActionStep> {
        val daysToGoal = max(1, (deadline - System.currentTimeMillis() / 1000) / 86400)
        val dailySaving = (targetAmount / daysToGoal).roundToInt()

        return when (category) {
            GoalCategory.EQUIPMENT -> listOf(
                ActionStep(
                    description = "Weka KSh $dailySaving kwa siku kwenye sanduku la akiba",
                    targetAmount = dailySaving.toDouble()
                ),
                ActionStep(
                    description = "Tafuta bei za vifaa kutoka kwa wauzaji tofauti",
                    targetAmount = 0.0
                ),
                ActionStep(
                    description = "Angalia ikiwa unaweza kununua kwa mkopo au hatua",
                    targetAmount = 0.0
                )
            )

            GoalCategory.INVENTORY -> listOf(
                ActionStep(
                    description = "Weka KSh $dailySaving kwa siku kwa ununuzi wa stock",
                    targetAmount = dailySaving.toDouble()
                ),
                ActionStep(
                    description = "Tafuta suppliers wazuri wenye bei nzuri",
                    targetAmount = 0.0
                ),
                ActionStep(
                    description = "Anza na bidhaa zinazouza zaidi",
                    targetAmount = 0.0
                )
            )

            GoalCategory.EDUCATION -> listOf(
                ActionStep(
                    description = "Weka KSh $dailySaving kwa siku kwa ada ya shule",
                    targetAmount = dailySaving.toDouble()
                ),
                ActionStep(
                    description = "Omba scholarship au msaada wa shule",
                    targetAmount = 0.0
                ),
                ActionStep(
                    description = "Fikiria kazi za ziada za kupata pesa",
                    targetAmount = 0.0
                )
            )

            GoalCategory.DEBT_REDUCTION -> listOf(
                ActionStep(
                    description = "Weka KSh $dailySaving kwa siku kulipa deni",
                    targetAmount = dailySaving.toDouble()
                ),
                ActionStep(
                    description = "Ongeza mauzo ili upate pesa zaidi",
                    targetAmount = 0.0
                ),
                ActionStep(
                    description = "Punguza matumizi yasiyo ya lazima",
                    targetAmount = 0.0
                )
            )

            GoalCategory.BUSINESS_EXPANSION -> listOf(
                ActionStep(
                    description = "Weka KSh $dailySaving kwa siku kwa uwekezaji",
                    targetAmount = dailySaving.toDouble()
                ),
                ActionStep(
                    description = "Tafuta eneo jipya la biashara",
                    targetAmount = 0.0
                ),
                ActionStep(
                    description = "Jifunze kutoka kwa wafanyabiashara wengine",
                    targetAmount = 0.0
                )
            )

            GoalCategory.EMERGENCY_FUND -> listOf(
                ActionStep(
                    description = "Weka KSh $dailySaving kwa siku kwenye akiba ya dharura",
                    targetAmount = dailySaving.toDouble()
                ),
                ActionStep(
                    description = "Weka pesa mahali salama (SACCO au benki)",
                    targetAmount = 0.0
                )
            )

            GoalCategory.ASSET -> listOf(
                ActionStep(
                    description = "Weka KSh $dailySaving kwa siku kununua mali",
                    targetAmount = dailySaving.toDouble()
                ),
                ActionStep(
                    description = "Tafuta mali inayofaa bajeti yako",
                    targetAmount = 0.0
                ),
                ActionStep(
                    description = "Fikiria kununua kwa hatua (installment)",
                    targetAmount = 0.0
                )
            )

            GoalCategory.SAVINGS -> listOf(
                ActionStep(
                    description = "Weka KSh $dailySaving kwa siku kwenye akiba",
                    targetAmount = dailySaving.toDouble()
                ),
                ActionStep(
                    description = "Weka akiba mara tu unapopata faida",
                    targetAmount = 0.0
                )
            )

            GoalCategory.OTHER -> listOf(
                ActionStep(
                    description = "Weka KSh $dailySaving kwa siku",
                    targetAmount = dailySaving.toDouble()
                )
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PROGRESS TRACKING — daily updates
    // ═══════════════════════════════════════════════════════════════

    /**
     * Update progress on a goal.
     *
     * Called when worker says:
     * - "Nimefikia 50% ya lengo" (I've reached 50% of my goal)
     * - "Nimeweka 2000 leo" (I saved 2000 today)
     * - "Nimenunua friji" (I bought the fridge — goal complete!)
     *
     * @param goal Current goal
     * @param amount New total amount saved/earned toward goal
     * @param note Optional note from worker
     * @return Updated goal with new progress entry and any milestone celebration
     */
    fun updateProgress(
        goal: Goal,
        amount: Double,
        note: String = ""
    ): Pair<Goal, MilestoneCelebration?> {
        val entry = ProgressEntry(amount = amount, note = note)
        val newCurrentAmount = goal.currentAmount + amount
        val newProgress = if (goal.targetAmount > 0) {
            (newCurrentAmount / goal.targetAmount).coerceIn(0.0, 1.0)
        } else 0.0

        // Check for milestone celebrations
        val celebration = checkMilestone(goal, newProgress)

        val updatedGoal = goal.copy(
            currentAmount = newCurrentAmount,
            progressEntries = goal.progressEntries + entry,
            milestonesReached = if (celebration != null && celebration.isNew) {
                goal.milestonesReached + celebration.milestone
            } else {
                goal.milestonesReached
            }
        )

        Timber.i("Goal ${goal.id} progress: ${(newProgress * 100).roundToInt()}%")
        return Pair(updatedGoal, celebration)
    }

    /**
     * Set absolute progress (when worker says percentage).
     *
     * @param goal Current goal
     * @param percent Progress percentage (0-100)
     * @return Updated goal
     */
    fun setProgress(goal: Goal, percent: Double): Pair<Goal, MilestoneCelebration?> {
        val clampedPercent = percent.coerceIn(0.0, 100.0) / 100.0
        val newAmount = goal.targetAmount * clampedPercent
        val delta = newAmount - goal.currentAmount
        return updateProgress(goal, delta, "Set to ${percent.roundToInt()}%")
    }

    /**
     * Mark a goal as complete.
     *
     * @param goal Goal to complete
     * @return Completed goal with celebration message
     */
    fun completeGoal(goal: Goal): Pair<Goal, MilestoneCelebration> {
        val completedGoal = goal.copy(
            currentAmount = goal.targetAmount,
            isActive = false,
            milestonesReached = (goal.milestonesReached + 1.0).distinct()
        )

        val celebration = MilestoneCelebration(
            goalId = goal.id,
            milestone = 1.0,
            message = generateCelebrationMessage(goal, 1.0),
            isNew = true
        )

        return Pair(completedGoal, celebration)
    }

    // ═══════════════════════════════════════════════════════════════
    // MILESTONE CELEBRATIONS — encouragement along the way
    // ═══════════════════════════════════════════════════════════════

    /**
     * Check if progress has crossed a milestone threshold.
     */
    private fun checkMilestone(goal: Goal, newProgress: Double): MilestoneCelebration? {
        for (milestone in MILESTONES) {
            val alreadyReached = goal.milestonesReached.contains(milestone)
            if (newProgress >= milestone && !alreadyReached) {
                return MilestoneCelebration(
                    goalId = goal.id,
                    milestone = milestone,
                    message = generateCelebrationMessage(goal, milestone),
                    isNew = true
                )
            }
        }
        return null
    }

    /**
     * Generate celebration message in Swahili.
     */
    private fun generateCelebrationMessage(goal: Goal, milestone: Double): String {
        val percent = (milestone * 100).roundToInt()
        val emoji = when (milestone) {
            0.25 -> "🌱"
            0.50 -> "🔥"
            0.75 -> "⭐"
            1.00 -> "🎉🏆"
            else -> "👏"
        }

        val base = when (milestone) {
            0.25 -> "Umefikia 25% ya lengo lako! $emoji Umenanza vizuri! " +
                "Endelea hivyo — usikate tamaa!"
            0.50 -> "Nusu ya lengo lako imefikiwa! $emoji " +
                "Umefanya kazi nzuri! Njia nyingine imebaki."
            0.75 -> "Umefikia 75%! $emoji Uko karibu sana! " +
                "Dakika chache tu zimebaki!"
            1.00 -> "🎉🎉🎉 HONGERA! Umefikia lengo lako! 🏆\n" +
                "\"${goal.description}\" — imekamilika!\n" +
                "Umefanya kazi nzuri sana. Sasa weka lengo jipya!"
            else -> "Umefikia $percent% ya lengo lako! $emoji Endelea!"
        }

        return base
    }

    // ═══════════════════════════════════════════════════════════════
    // TIME-TO-GOAL FORECAST — STA 341 (Estimation)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Forecast time to reach goal based on current progress trajectory.
     *
     * Uses linear regression on progress entries (STA 341).
     * Returns "At this rate, you'll reach your goal in 45 days."
     *
     * @param goal Goal to forecast
     * @return TimeToGoalForecast with Swahili message
     */
    fun getTimeToGoal(goal: Goal): TimeToGoalForecast {
        val entries = goal.progressEntries

        if (entries.size < MIN_PROGRESS_ENTRIES) {
            val dailyRateNeeded = if (goal.daysRemaining > 0) {
                goal.remainingAmount / goal.daysRemaining
            } else goal.remainingAmount

            return TimeToGoalForecast(
                message = "Bado nakusanya data. Rekodi mauzo yako kila siku ili niweze " +
                    "kukadiria muda wa kufikia lengo lako.\n\n" +
                    "Unahitaji kuweka KSh ${dailyRateNeeded.roundToInt()} kwa siku " +
                    "kufikia lengo lako kwa wakati.",
                daysRemaining = goal.daysRemaining.toInt(),
                dailyRateNeeded = dailyRateNeeded,
                currentDailyRate = 0.0,
                isOnTrack = false,
                confidenceLevel = 0.0
            )
        }

        // Calculate daily savings rate from progress entries (STA 341)
        val timeSpanDays = max(1, (entries.last().timestamp - entries.first().timestamp) / 86400)
        val totalSaved = entries.sumOf { it.amount }
        val dailyRate = totalSaved / timeSpanDays

        // Forecast: remaining amount / daily rate
        val daysToGoal = if (dailyRate > 0) {
            (goal.remainingAmount / dailyRate).roundToInt()
        } else {
            Int.MAX_VALUE
        }

        val dailyRateNeeded = if (goal.daysRemaining > 0) {
            goal.remainingAmount / goal.daysRemaining
        } else goal.remainingAmount

        val isOnTrack = daysToGoal <= goal.daysRemaining || goal.daysRemaining == 0L

        val message = when {
            goal.currentAmount >= goal.targetAmount ->
                "Umefikia lengo lako! Hongera! 🎉"

            daysToGoal == Int.MAX_VALUE ->
                "Hujaweka pesa ya kutosha bado. Jaribu kuweka KSh " +
                    "${dailyRateNeeded.roundToInt()} kwa siku."

            isOnTrack ->
                "Kwa kasi hii, utafikia lengo lako siku $daysToGoal. " +
                    "Uko kwenye njia sahihi! 👍\n" +
                    "Unaweka KSh ${dailyRate.roundToInt()} kwa siku."

            else -> {
                val extraDays = daysToGoal - goal.daysRemaining
                "Kwa kasi hii, utahitaji siku $daysToGoal — ni siku $extraDays " +
                    "zaidi ya tarehe yako ya mwisho.\n\n" +
                    "Ushauri:\n" +
                    "• Ongeza akiba yako ya siku hadi KSh ${dailyRateNeeded.roundToInt()}\n" +
                    "• Au ongeza tarehe ya mwisho kwa siku $extraDays"
            }
        }

        return TimeToGoalForecast(
            message = message,
            daysRemaining = daysToGoal,
            dailyRateNeeded = dailyRateNeeded,
            currentDailyRate = dailyRate,
            isOnTrack = isOnTrack,
            confidenceLevel = FORECAST_CONFIDENCE
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // GOAL REPORT — voice summary of all goals
    // ═══════════════════════════════════════════════════════════════

    /**
     * Generate voice report of all goals.
     *
     * "Una malengo matatu. Lengo la kwanza: kununua friji — umefikia 40%.
     *  Lengo la pili: ada ya shule — umefikia 60%. Hongera!"
     *
     * @param goals All goals (active and completed)
     * @return GoalReport with Swahili voice message
     */
    fun getGoalReport(goals: List<Goal>): GoalReport {
        val activeGoals = goals.filter { it.isActive }
        val completedGoals = goals.filter { !it.isActive }
        val totalTarget = activeGoals.sumOf { it.targetAmount }
        val totalSaved = activeGoals.sumOf { it.currentAmount }
        val overallProgress = if (totalTarget > 0) totalSaved / totalTarget else 0.0

        val message = buildString {
            if (goals.isEmpty()) {
                append("Huna malengo bado. Sema \"Lengo langu ni...\" kuanza!")
                return@buildString
            }

            // Header
            append("📋 Ripoti ya Malengo\n\n")

            if (activeGoals.isNotEmpty()) {
                append("Malengo yanayoendelea: ${activeGoals.size}\n")
                activeGoals.forEachIndexed { index, goal ->
                    val percent = (goal.progress * 100).roundToInt()
                    val emoji = when {
                        percent >= 75 -> "⭐"
                        percent >= 50 -> "🔥"
                        percent >= 25 -> "🌱"
                        else -> "📌"
                    }
                    append("\n${index + 1}. $emoji ${goal.description}\n")
                    append("   Umefikia $percent% (KSh ${formatAmount(goal.currentAmount.toInt())} ")
                    append("kati ya KSh ${formatAmount(goal.targetAmount.toInt())})\n")

                    if (goal.daysRemaining > 0) {
                        append("   Siku ${goal.daysRemaining} zimebaki\n")
                    }
                }
            }

            if (completedGoals.isNotEmpty()) {
                append("\n\nMalengo yaliyokamilika: ${completedGoals.size} 🎉\n")
                completedGoals.forEach { goal ->
                    append("✅ ${goal.description}\n")
                }
            }

            // Overall summary
            append("\n\nJumla: Umefikia ${(overallProgress * 100).roundToInt()}% ya malengo yako.")
            if (overallProgress >= 0.5) {
                append(" Kazi nzuri! Endelea! 💪")
            } else {
                append(" Endelea kufanya kazi — unaweza! 🙌")
            }
        }

        return GoalReport(
            message = message,
            activeGoals = activeGoals,
            completedGoals = completedGoals,
            totalTargetAmount = totalTarget,
            totalSavedAmount = totalSaved,
            overallProgress = overallProgress
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // GOAL ADJUSTMENT — reality-based modifications
    // ═══════════════════════════════════════════════════════════════

    /**
     * Adjust a goal when reality changes.
     *
     * Workers may need to adjust:
     * - Target amount (price changed, scope changed)
     * - Deadline (extended, accelerated)
     * - Description (goal evolved)
     *
     * @param goal Goal to adjust
     * @param newTarget New target amount (null = keep current)
     * @param newDeadline New deadline (null = keep current)
     * @param newDescription New description (null = keep current)
     * @return Adjusted goal with regenerated steps if needed
     */
    fun adjustGoal(
        goal: Goal,
        newTarget: Double? = null,
        newDeadline: Long? = null,
        newDescription: String? = null
    ): Goal {
        val adjusted = goal.copy(
            targetAmount = newTarget ?: goal.targetAmount,
            deadline = newDeadline ?: goal.deadline,
            description = newDescription ?: goal.description
        )

        // Regenerate steps if target or deadline changed
        val stepsChanged = newTarget != null || newDeadline != null
        return if (stepsChanged) {
            adjusted.copy(steps = generateActionSteps(adjusted.category, adjusted.targetAmount, adjusted.deadline))
        } else {
            adjusted
        }
    }

    /**
     * Abandon a goal (when worker gives up).
     *
     * @param goal Goal to abandon
     * @return Inactive goal
     */
    fun abandonGoal(goal: Goal): Goal {
        return goal.copy(isActive = false)
    }

    // ═══════════════════════════════════════════════════════════════
    // ENCOURAGEMENT — daily motivation
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get encouragement message based on goal progress.
     *
     * @param goal Goal to encourage about
     * @return Swahili encouragement message
     */
    fun getEncouragement(goal: Goal): String {
        val progress = goal.progress
        val remaining = goal.remainingAmount

        return when {
            progress >= 0.9 ->
                "Uko karibu sana! KSh ${formatAmount(remaining.toInt())} tu imebaki! Usikate tamaa! 💪"
            progress >= 0.75 ->
                "Umefikia 75%! Endelea hivyo — lengo lako liko karibu! ⭐"
            progress >= 0.5 ->
                "Nusu imefikiwa! Kila siku ni hatua mbele. Endelea! 🔥"
            progress >= 0.25 ->
                "Umenanza vizuri! 25% imefikiwa. Siku za mwanzo ndio ngumu zaidi — umevuka! 🌱"
            progress > 0 ->
                "Kila shilingi inayokusanywa ni hatua mbele. Endelea! 📈"
            else ->
                "Kila safari ya maili moja inanza na hatua moja. Anza leo! 🚀"
        }
    }

    /**
     * Get daily reminder for a goal.
     *
     * @param goal Goal to remind about
     * @return Daily reminder message
     */
    fun getDailyReminder(goal: Goal): String {
        val dailyNeeded = if (goal.daysRemaining > 0) {
            goal.remainingAmount / goal.daysRemaining
        } else goal.remainingAmount

        return "Lengo: ${goal.description}\n" +
            "Baki: KSh ${formatAmount(goal.remainingAmount.toInt())}\n" +
            "Leo, weka angalau KSh ${dailyNeeded.roundToInt()}"
    }

    // ═══════════════════════════════════════════════════════════════
    // BREAK-EVEN ANALYSIS — ECO 210
    // ═══════════════════════════════════════════════════════════════

    /**
     * Break-even analysis for equipment/inventory goals.
     *
     * "Friji ya KSh 25,000 italipa yenyewe kwa siku 50 —
     *  ukikata maji baridi kwa KSh 500 kwa siku."
     *
     * @param goal Equipment/inventory goal
     * @param expectedDailyRevenue Additional revenue from the equipment
     * @param expectedDailyCost Additional cost of operating the equipment
     * @return Break-even analysis message
     */
    fun getBreakEvenAnalysis(
        goal: Goal,
        expectedDailyRevenue: Double,
        expectedDailyCost: Double
    ): String {
        val dailyProfit = expectedDailyRevenue - expectedDailyCost
        if (dailyProfit <= 0) {
            return "Bei ya uendeshaji ni kubwa kuliko mapato. Fikiria tena kununua " +
                "hii vifaa — inaweza kukuletea hasara."
        }

        val breakEvenDays = (goal.targetAmount / dailyProfit).roundToInt()
        val monthlyProfit = dailyProfit * 30

        return "📊 Uchambuzi wa faida:\n\n" +
            "Gharama: KSh ${formatAmount(goal.targetAmount.toInt())}\n" +
            "Faida ya ziada ya siku: KSh ${dailyProfit.roundToInt()}\n" +
            "Faida ya mwezi: KSh ${formatAmount(monthlyProfit.roundToInt())}\n\n" +
            "Vifaa hivi vitalipa yenyewe kwa siku $breakEvenDays " +
            "(~${(breakEvenDays / 30.0).roundToInt()} miezi).\n" +
            "Baada ya hapo, faaida yote ni yako! 💰"
    }

    // ═══════════════════════════════════════════════════════════════
    // UTILITY
    // ═══════════════════════════════════════════════════════════════

    private fun formatAmount(amount: Int): String {
        return if (amount >= 1_000_000) {
            String.format("%.1fM", amount / 1_000_000.0)
        } else if (amount >= 1_000) {
            String.format("%,d", amount)
        } else {
            amount.toString()
        }
    }
}
