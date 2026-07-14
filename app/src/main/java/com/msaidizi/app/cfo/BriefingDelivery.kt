package com.msaidizi.app.cfo

import com.msaidizi.app.agent.BusinessAgent
import com.msaidizi.app.agent.WorkerType
import com.msaidizi.app.core.database.BriefingDeliveryDao
import com.msaidizi.app.core.model.BriefingDeliveryEntity
import com.msaidizi.app.core.model.Transaction
import com.msaidizi.app.core.model.TransactionType
import com.msaidizi.app.finance.LoanManager
import com.msaidizi.app.gamification.GamificationEngine
import com.msaidizi.app.mindset.MindsetAcademy
import com.msaidizi.app.mindset.RichHabitsScore
import timber.log.Timber
import java.time.LocalTime
import java.time.LocalDate
import java.time.DayOfWeek

/**
 * Delivers CFOEngine briefings to workers proactively.
 *
 * The difference between data and value is delivery.
 * CFOEngine generates brilliant insights — this class makes sure
 * workers actually receive them at the right time, in the right way.
 *
 * Delivery channels:
 * - Android notification (morning briefing at 7 AM)
 * - In-app message (when worker opens the app)
 * - SMS (via Africa's Talking API — for workers without WhatsApp)
 * - WhatsApp (optional — via Business API)
 *
 * Morning briefing at 7 AM:
 * "Habari Maria! Leo utapata KSh 1,800 ikiwa unafuata mpango huu.
 *  Nyanya zinaisha — nunua kesho. Bei ya sukari imepanda 10%."
 *
 * Restock alert when stock is low:
 * "⚠️ Nyanya zimebaki 5 tu! Kwa mwendo huu, zitaisha kesho.
 *  Nunua sasa kutoka kwa supplier wako."
 *
 * Weekly summary on Monday:
 * "📋 Wiki iliyopita: Mauzo KSh 15,000. Faida KSh 4,200.
 *  Wiki hii ongeza mauzo ya nyanya — bei imepanda."
 *
 * Mathematical foundations:
 * - ECO 201 (Micro): Profit-maximizing restocking times
 * - STA 244 (Time Series): Trend-based alerts
 * - STA 341 (Estimation): Forecast-based briefings
 *
 * Delivery channel selection:
 * - SMS: Default for workers without WhatsApp (30% of Kenyan informal workers)
 * - WhatsApp: Optional, for workers who have it and prefer it
 * - Notification: Always available as fallback
 */
class BriefingDelivery(
    private val cfoEngine: CFOEngine,
    private val businessAgent: BusinessAgent,
    private val loanManager: LoanManager = LoanManager(),
    private val gamificationEngine: GamificationEngine? = null,
    private val mindsetAcademy: MindsetAcademy? = null,
    private val richHabitsScore: RichHabitsScore? = null,
    private val briefingDeliveryDao: BriefingDeliveryDao? = null
) {
    companion object {
        private const val TAG = "BriefingDelivery"

        /** Morning briefing hour (7 AM) */
        private const val MORNING_BRIEFING_HOUR = 7

        /** Evening summary hour (7 PM) */
        private const val EVENING_SUMMARY_HOUR = 19

        /** Minimum transactions for a meaningful briefing */
        private const val MIN_TRANSACTIONS_FOR_BRIEFING = 2
    }

    // ═══════════════════════════════════════════════════════════════
    // MORNING BRIEFING — delivered at 7 AM
    // ═══════════════════════════════════════════════════════════════

    /**
     * Deliver morning briefing to the worker.
     *
     * Called by WorkManager or AlarmScheduler at 7 AM daily.
     * Generates a personalized briefing from yesterday's data
     * and today's plan.
     *
     * @param workerName Worker's name
     * @param assistantName What worker calls Msaidizi
     * @param workerType Classified worker type (for tailored advice)
     * @param todayTransactions Today's transactions so far (usually empty at 7 AM)
     * @param yesterdayTransactions Yesterday's transactions
     * @param recentTransactions Last 7 days of transactions
     * @return BriefingResult with message and delivery status
     */
    suspend fun deliverMorningBriefing(
        workerName: String,
        assistantName: String,
        workerType: WorkerType,
        todayTransactions: List<Transaction>,
        yesterdayTransactions: List<Transaction>,
        recentTransactions: List<Transaction>
    ): BriefingResult {
        Timber.tag(TAG).d("Generating morning briefing for %s (%s)", workerName, workerType)

        // Generate the core briefing from CFOEngine
        val briefing = cfoEngine.getDailyBriefing(
            workerName = workerName,
            assistantName = assistantName,
            todayTransactions = todayTransactions,
            yesterdayTransactions = yesterdayTransactions,
            recentTransactions = recentTransactions
        )

        // Add worker-type-specific tips
        val tailoredTip = getWorkerTypeTip(workerType, recentTransactions)

        // Add restock alerts if relevant
        val restockAlert = generateRestockAlert(recentTransactions)

        // Add loan status reminders
        val loanStatus = loanManager.getBriefingLoanStatus()
        val overdueMessages = loanManager.checkOverduePayments()

        // Combine into full morning message
        val fullMessage = buildString {
            append(briefing.message)

            // Loan reminders (critical — shown before other tips)
            if (overdueMessages.isNotEmpty()) {
                append("\n\n🚨 Mikopo:")
                overdueMessages.forEach { append("\n$it") }
            } else if (loanStatus != null) {
                append("\n\n🏦 $loanStatus")
            }

            if (tailoredTip.isNotBlank()) {
                append("\n\n💡 $tailoredTip")
            }

            if (restockAlert.isNotBlank()) {
                append("\n\n$restockAlert")
            }

            // Gamification: streak & level info
            gamificationEngine?.let { ge ->
                try {
                    val streakInfo = ge.getStreakInfo()
                    val levelInfo = ge.getCurrentLevel()
                    append("\n\n🎮 Level: ${levelInfo.emoji} ${levelInfo.nameSw}")
                    if (streakInfo.currentStreak > 0) {
                        append(" | 🔥 Mfululizo: siku ${streakInfo.currentStreak}")
                    }
                } catch (_: Exception) {}
            }

            // Mindset: daily lesson prompt
            mindsetAcademy?.let { ma ->
                try {
                    val lessonPrompt = ma.getDailyLessonPrompt()
                    if (lessonPrompt != null) {
                        append("\n\n$lessonPrompt")
                    }
                } catch (_: Exception) {}
            }
        }

        Timber.tag(TAG).d("Morning briefing generated: %d chars", fullMessage.length)

        val result = BriefingResult(
            message = fullMessage,
            type = BriefingType.MORNING,
            priority = BriefingPriority.NORMAL,
            data = mapOf(
                "todaySales" to briefing.todaySales.toString(),
                "todayProfit" to briefing.todayProfit.toString(),
                "yesterdaySales" to briefing.yesterdaySales.toString(),
                "savingsRecommendation" to briefing.savingsRecommendation.toString()
            )
        )

        // ═══ LOOP CLOSURE: Track briefing delivery for feedback cycle ═══
        try {
            val keyAdvice = when {
                restockAlert.isNotBlank() -> restockAlert
                tailoredTip.isNotBlank() -> tailoredTip
                else -> ""
            }
            val deliveryId = briefingDeliveryDao?.insert(
                BriefingDeliveryEntity(
                    briefingType = BriefingType.MORNING.name,
                    briefingText = fullMessage,
                    predictedSales = briefing.todaySales,
                    predictedProfit = briefing.todayProfit,
                    keyAdvice = keyAdvice
                )
            )
            return result.copy(data = result.data + ("deliveryId" to (deliveryId ?: 0L).toString()))
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to track briefing delivery")
        }

        return result
    }

    // ═══════════════════════════════════════════════════════════════
    // PROACTIVE ALERTS — delivered when conditions are met
    // ═══════════════════════════════════════════════════════════════

    /**
     * Deliver a proactive alert to the worker.
     *
     * Called when CFOEngine detects a condition that needs attention:
     * - Stock running low
     * - Revenue declining
     * - Cash flow warning
     * - Credit readiness milestone
     *
     * @param workerName Worker's name
     * @param alertType Type of alert
     * @param alertMessage Pre-generated alert message
     * @param priority How urgent is this
     * @return BriefingResult with the alert
     */
    suspend fun deliverAlert(
        workerName: String,
        alertType: AlertType,
        alertMessage: String,
        priority: BriefingPriority = BriefingPriority.HIGH
    ): BriefingResult {
        Timber.tag(TAG).d("Delivering alert: %s (priority=%s)", alertType, priority)

        val emoji = when (alertType) {
            AlertType.LOW_STOCK -> "📦"
            AlertType.REVENUE_DECLINE -> "📉"
            AlertType.CASH_FLOW_WARNING -> "⚠️"
            AlertType.CREDIT_READY -> "🎉"
            AlertType.SAVINGS_MILESTONE -> "💰"
            AlertType.RESTOCK_NEEDED -> "🛒"
            AlertType.MARGIN_ALERT -> "📊"
        }

        val fullMessage = "$emoji $alertMessage"

        return BriefingResult(
            message = fullMessage,
            type = BriefingType.ALERT,
            priority = priority,
            data = mapOf(
                "alertType" to alertType.name,
                "workerName" to workerName
            )
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // WEEKLY SUMMARY — delivered every Monday
    // ═══════════════════════════════════════════════════════════════

    /**
     * Deliver weekly summary to the worker.
     *
     * Called every Monday morning by WorkManager.
     * Generates a comprehensive weekly report with trends
     * and recommendations for the coming week.
     *
     * @param workerName Worker's name
     * @param assistantName What worker calls Msaidizi
     * @param thisWeek This week's transactions
     * @param lastWeek Last week's transactions
     * @return BriefingResult with the weekly summary
     */
    suspend fun deliverWeeklySummary(
        workerName: String,
        assistantName: String,
        thisWeek: List<Transaction>,
        lastWeek: List<Transaction>
    ): BriefingResult {
        Timber.tag(TAG).d("Generating weekly summary for %s", workerName)

        val report = cfoEngine.getWeeklyReport(
            workerName = workerName,
            assistantName = assistantName,
            thisWeek = thisWeek,
            lastWeek = lastWeek
        )

        // Add credit readiness check
        val creditMessage = generateCreditUpdate(thisWeek)

        val fullMessage = buildString {
            append(report.message)
            if (creditMessage.isNotBlank()) {
                append("\n\n🏦 $creditMessage")
            }

            // Gamification: weekly level & streak
            gamificationEngine?.let { ge ->
                try {
                    val levelInfo = ge.getCurrentLevel()
                    val streakInfo = ge.getStreakInfo()
                    append("\n\n🎮 Level yako: ${levelInfo.emoji} ${levelInfo.nameSw} (${levelInfo.totalPoints} points)")
                    if (streakInfo.currentStreak > 0) {
                        append(" | 🔥 Mfululizo: siku ${streakInfo.currentStreak}")
                    }
                } catch (_: Exception) {}
            }

            // Mindset: weekly progress
            mindsetAcademy?.let { ma ->
                try {
                    val progress = ma.getProgress()
                    append("\n\n📖 Somo: ${progress.completedLessons}/${progress.totalLessons} zimekamilisha (${progress.progressPercent}%)")
                } catch (_: Exception) {}
            }

            // Rich Habits: weekly average
            richHabitsScore?.let { rhs ->
                try {
                    val avg = rhs.getWeeklyAverage()
                    if (avg > 0) append("\n\n📋 Wastani wa tabia wiki hii: ${"%.0f".format(avg)}/100")
                } catch (_: Exception) {}
            }
        }

        val result = BriefingResult(
            message = fullMessage,
            type = BriefingType.WEEKLY,
            priority = BriefingPriority.NORMAL,
            data = mapOf(
                "totalSales" to report.totalSales.toString(),
                "totalProfit" to report.totalProfit.toString(),
                "salesGrowth" to report.salesGrowthPercent.toString(),
                "topProduct" to (report.topProduct ?: "N/A")
            )
        )

        // Track delivery for feedback loop
        try {
            briefingDeliveryDao?.insert(
                BriefingDeliveryEntity(
                    briefingType = BriefingType.WEEKLY.name,
                    briefingText = fullMessage,
                    predictedSales = report.totalSales,
                    predictedProfit = report.totalProfit
                )
            )
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to track weekly briefing delivery")
        }

        return result
    }

    // ═══════════════════════════════════════════════════════════════
    // EVENING SUMMARY — delivered at 7 PM
    // ═══════════════════════════════════════════════════════════════

    /**
     * Deliver evening summary.
     *
     * Quick recap of the day's performance and savings recommendation.
     *
     * @param workerName Worker's name
     * @param todayTransactions Today's transactions
     * @return BriefingResult with evening summary
     */
    suspend fun deliverEveningSummary(
        workerName: String,
        todayTransactions: List<Transaction>
    ): BriefingResult {
        Timber.tag(TAG).d("Generating evening summary for %s", workerName)

        val sales = todayTransactions
            .filter { it.type == TransactionType.SALE }
            .sumOf { it.totalAmount }
        val expenses = todayTransactions
            .filter { it.type == TransactionType.EXPENSE || it.type == TransactionType.PURCHASE }
            .sumOf { it.totalAmount }
        val profit = sales - expenses
        val txnCount = todayTransactions.size

        // Update gamification streak on evening check
        gamificationEngine?.let { ge ->
            try {
                ge.onDailyActivity()
            } catch (_: Exception) {}
        }

        val message = buildString {
            append("🌆 Habari $workerName! Muhtasari wa leo:\n\n")
            append("📊 Shughuli: $txnCount\n")
            append("💰 Mauzo: KSh ${formatAmount(sales.toInt())}\n")
            append("📈 Faida: KSh ${formatAmount(profit.toInt())}\n")

            if (profit > 0) {
                val savings = (profit * 0.20).toInt()
                append("\n💡 Weka KSh $savings kwenye akiba ya dharura.")
                append(" Umehifadhi KSh $savings. Nzuri!")
            } else if (profit < 0) {
                append("\n💪 Leo haikuwa siku nzuri. Kesho ni nafasi mpya!")
            }

            // Gamification: evening score
            gamificationEngine?.let { ge ->
                try {
                    val levelInfo = ge.getCurrentLevel()
                    val streakInfo = ge.getStreakInfo()
                    append("\n\n🎮 Level: ${levelInfo.emoji} ${levelInfo.nameSw} | Points: ${levelInfo.totalPoints}")
                    if (streakInfo.currentStreak > 0) {
                        append(" | 🔥 Mfululizo: siku ${streakInfo.currentStreak}")
                    }
                } catch (_: Exception) {}
            }

            // Rich Habits: evening score & improvement
            richHabitsScore?.let { rhs ->
                try {
                    val score = rhs.getTodayScore()
                    val improvement = rhs.getImprovementCelebration()
                    append("\n\n📋 Tabia za leo: $score/100")
                    if (improvement != null) {
                        append("\n$improvement")
                    }
                } catch (_: Exception) {}
            }

            append("\n\nUsiku mwema! 🌙")
        }

        val result = BriefingResult(
            message = message,
            type = BriefingType.EVENING,
            priority = BriefingPriority.LOW,
            data = mapOf(
                "sales" to sales.toString(),
                "profit" to profit.toString(),
                "transactionCount" to txnCount.toString()
            )
        )

        // Track delivery for feedback loop
        try {
            briefingDeliveryDao?.insert(
                BriefingDeliveryEntity(
                    briefingType = BriefingType.EVENING.name,
                    briefingText = message,
                    predictedSales = sales,
                    predictedProfit = profit
                )
            )
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to track evening briefing delivery")
        }

        return result
    }

    // ═══════════════════════════════════════════════════════════════
    // FEEDBACK LOOP — Track outcomes and adjust briefings
    // ═══════════════════════════════════════════════════════════════

    /**
     * Mark a briefing as opened when worker views it.
     */
    suspend fun markBriefingOpened(deliveryId: Long) {
        try {
            briefingDeliveryDao?.markOpened(deliveryId)
            Timber.tag(TAG).d("Briefing %d marked as opened", deliveryId)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to mark briefing opened")
        }
    }

    /**
     * Record that the worker acted on a briefing.
     * Compares predicted vs actual and calculates outcome score.
     *
     * @param deliveryId ID of the briefing that was acted on
     * @param actualSales Sales recorded after briefing
     * @param actualProfit Profit recorded after briefing
     * @param adviceFollowed Whether the specific advice was followed
     */
    suspend fun recordBriefingOutcome(
        deliveryId: Long,
        actualSales: Double,
        actualProfit: Double,
        adviceFollowed: Boolean? = null
    ) {
        try {
            val briefings = briefingDeliveryDao?.getRecent(1) ?: return
            val briefing = briefings.find { it.id == deliveryId } ?: return

            val outcomeScore = if (briefing.predictedSales > 0) {
                val salesAccuracy = 1.0 - Math.min(
                    Math.abs(actualSales - briefing.predictedSales) / briefing.predictedSales,
                    2.0
                )
                salesAccuracy.coerceIn(-1.0, 1.0)
            } else if (actualSales > 0) 0.5 else 0.0

            briefingDeliveryDao?.markActedOn(
                id = deliveryId,
                actualSales = actualSales,
                actualProfit = actualProfit,
                outcomeScore = outcomeScore,
                adviceFollowed = adviceFollowed
            )

            Timber.tag(TAG).d(
                "Briefing outcome: predicted=%.0f actual=%.0f score=%.2f",
                briefing.predictedSales, actualSales, outcomeScore
            )
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to record briefing outcome")
        }
    }

    /**
     * Get briefing performance for adaptive adjustment.
     * Returns (averageOutcomeScore, actionRate).
     */
    suspend fun getBriefingPerformance(): Pair<Double, Double> {
        return try {
            val avgScore = briefingDeliveryDao?.getAverageOutcomeScore(BriefingType.MORNING.name) ?: 0.0
            val weekAgo = System.currentTimeMillis() / 1000 - (7 * 86400)
            val delivered = briefingDeliveryDao?.getDeliveredCountSince(weekAgo) ?: 0
            val actedOn = briefingDeliveryDao?.getActedOnCountSince(weekAgo) ?: 0
            val actionRate = if (delivered > 0) actedOn.toDouble() / delivered else 0.0
            Pair(avgScore, actionRate)
        } catch (e: Exception) {
            Pair(0.0, 0.0)
        }
    }

    /**
     * Get the most recent pending briefing (delivered but not acted on).
     */
    suspend fun getLatestPendingBriefing(): BriefingDeliveryEntity? {
        return try {
            briefingDeliveryDao?.getLatestPendingBriefing()
        } catch (e: Exception) {
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get a worker-type-specific tip for the morning briefing.
     */
    private fun getWorkerTypeTip(workerType: WorkerType, recentTransactions: List<Transaction>): String {
        return when (workerType) {
            WorkerType.TRADER -> {
                val topItem = recentTransactions
                    .filter { it.type == TransactionType.SALE }
                    .groupBy { it.item }
                    .maxByOrNull { it.value.sumOf { t -> t.totalAmount } }
                    ?.key
                if (topItem != null) {
                    "Bidhaa yako bora ni $topItem. Hakikisha una stock ya kutosha leo."
                } else {
                    "Rekodi mauzo yako yote leo — hata madogo!"
                }
            }
            WorkerType.TRANSPORT -> {
                "Angalia bei ya mafuta leo. Ikiwa imepanda, fikiria kupunguza safari ndefu."
            }
            WorkerType.FARMER -> {
                val season = LocalDate.now().monthValue
                when {
                    season in 3..5 -> "Msimu wa kupanda uko hapa! Hakikisha una mbegu na mbolea."
                    season in 6..8 -> "Mazao yanakua. Angalia wadudu na hali ya hewa."
                    season in 9..11 -> "Msimu wa kuvuna! Panga bei za mauzo mapema."
                    else -> "Panga mpango wa msimu ujao. Nunua mbegu mapema."
                }
            }
            WorkerType.SERVICE -> {
                "Wateja wako wa kawaida wanaweza kuja leo. Hakikisha umejiandaa!"
            }
            WorkerType.MANUFACTURING -> {
                "Angalia gharama za vifaa. Bei za chuma na mbao zinabadilika kila wiki."
            }
            WorkerType.DIGITAL -> {
                "Angalia salio la float. Hakikisha una enough kwa wateja."
            }
            WorkerType.UNKNOWN -> {
                "Rekodi mauzo yako yote leo — data ndogo inasaidia!"
            }
        }
    }

    /**
     * Generate a restock alert message if stock is low.
     */
    private fun generateRestockAlert(recentTransactions: List<Transaction>): String {
        // Estimate stock from recent sales velocity
        val salesByItem = recentTransactions
            .filter { it.type == TransactionType.SALE }
            .groupBy { it.item.lowercase() }

        if (salesByItem.isEmpty()) return ""

        val lowStockItems = mutableListOf<String>()
        for ((item, sales) in salesByItem) {
            val totalSold = sales.sumOf { it.quantity }
            val dailyVelocity = totalSold / 7.0  // 7 days of data

            // If daily velocity is high, likely needs restocking
            if (dailyVelocity >= 3.0) {
                lowStockItems.add(item.replaceFirstChar { it.uppercase() })
            }
        }

        return if (lowStockItems.isNotEmpty()) {
            "📦 Angalia stock ya: ${lowStockItems.joinToString(", ")}"
        } else ""
    }

    /**
     * Generate a credit readiness update.
     */
    private fun generateCreditUpdate(transactions: List<Transaction>): String {
        val activeDays = transactions
            .groupBy { it.createdAt / 86400 }
            .size

        return when {
            activeDays >= 7 -> "Umerekodi siku $activeDays mfululizo! Alama yako ya mkopo inaongezeka."
            activeDays >= 5 -> "Siku $activeDays za rekodi wiki hii. Endelea hivi!"
            else -> "Rekodi kila siku ili kuongeza alama ya mkopo."
        }
    }

    /**
     * Format amount with thousands separator.
     */
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

// ═══════════════════════════════════════════════════════════════
// DATA CLASSES
// ═══════════════════════════════════════════════════════════════

/**
 * Result of a briefing delivery.
 */
data class BriefingResult(
    val message: String,
    val type: BriefingType,
    val priority: BriefingPriority,
    val data: Map<String, String> = emptyMap()
)

/**
 * Types of briefings.
 */
enum class BriefingType {
    /** Morning briefing at 7 AM */
    MORNING,

    /** Evening summary at 7 PM */
    EVENING,

    /** Weekly report on Monday */
    WEEKLY,

    /** Proactive alert (any time) */
    ALERT
}

/**
 * Alert types that trigger proactive notifications.
 */
enum class AlertType {
    LOW_STOCK,
    REVENUE_DECLINE,
    CASH_FLOW_WARNING,
    CREDIT_READY,
    SAVINGS_MILESTONE,
    RESTOCK_NEEDED,
    MARGIN_ALERT
}

/**
 * Briefing priority levels.
 */
enum class BriefingPriority {
    /** Must deliver immediately (cash flow warning, stock out) */
    HIGH,

    /** Deliver at next opportunity (morning briefing, restock) */
    NORMAL,

    /** Deliver when convenient (evening summary, weekly report) */
    LOW
}
