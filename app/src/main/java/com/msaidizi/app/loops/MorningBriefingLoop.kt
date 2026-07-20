package com.msaidizi.app.loops
import com.msaidizi.app.agent.BusinessPatternTracker

import com.msaidizi.app.agent.BusinessAgent
import com.msaidizi.app.agent.WorkerType
import com.msaidizi.app.cfo.BriefingDelivery
import com.msaidizi.app.cfo.BriefingResult
import com.msaidizi.app.cfo.BriefingType
import com.msaidizi.app.cfo.CFOEngine
import com.msaidizi.app.core.database.BriefingDeliveryDao
import com.msaidizi.app.core.database.TransactionDao
import com.msaidizi.app.core.model.BriefingDeliveryEntity
import com.msaidizi.app.core.model.Transaction
import com.msaidizi.app.core.model.TransactionType
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneId

/**
 * Morning Briefing Loop — the core CFO-to-worker feedback cycle.
 *
 * Loop flow:
 *   7AM Cron → CFOEngine generates briefing → Voice push notification →
 *   Worker acts → Track action → Adjust next briefing → Better briefing
 *
 * This loop closes the gap between CFOEngine (which generates brilliant
 * insights) and the worker (who needs to actually receive and act on them).
 *
 * The feedback cycle:
 * 1. Generate briefing from yesterday's data + trends
 * 2. Deliver via notification (voice-first for low-literacy workers)
 * 3. Track whether worker opened and acted on the briefing
 * 4. Compare predicted vs actual outcomes
 * 5. Adjust future briefings based on what the worker actually acts on
 *
 * Mathematical foundations:
 * - STA 341 (Estimation): Briefing predictions use moving averages
 * - STA 342 (Hypothesis Testing): Outcome scores test prediction accuracy
 * - ECO 201 (Micro): Profit-maximizing restocking recommendations
 *
 * @param cfoEngine Generates the financial insights
 * @param briefingDelivery Delivers briefings to the worker
 * @param businessAgent Access to transaction data
 * @param transactionDao Direct transaction queries for outcome tracking
 * @param briefingDeliveryDao Tracks briefing delivery and outcomes
 */
class MorningBriefingLoop(
    private val cfoEngine: CFOEngine,
    private val briefingDelivery: BriefingDelivery,
    private val businessAgent: BusinessAgent,
    private val transactionDao: TransactionDao,
    private val briefingDeliveryDao: BriefingDeliveryDao,
    private val patternTracker: BusinessPatternTracker? = null
) {
    companion object {
        private const val TAG = "MorningBriefingLoop"

        /** How many recent briefings to analyze for personalization */
        private const val BRIEFING_HISTORY_WINDOW = 14

        /** Minimum briefings before we start personalizing */
        private const val MIN_BRIEFINGS_FOR_PERSONALIZATION = 5
    }

    // ═══════════════════════════════════════════════════════════════
    // LOOP STEP 1: GENERATE & DELIVER MORNING BRIEFING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Execute the morning briefing loop.
     *
     * Called by WorkManager at 7 AM daily. Generates a personalized
     * briefing, delivers it, and records the delivery for outcome tracking.
     *
     * @param workerName Worker's name for personalization
     * @param assistantName What worker calls Msaidizi
     * @param workerType Classified worker type for tailored advice
     * @param language Language preference (sw/en)
     * @return BriefingResult with the generated message
     */
    suspend fun executeMorningBriefing(
        workerName: String,
        assistantName: String,
        workerType: WorkerType,
        language: String = "sw"
    ): BriefingResult {
        Timber.tag(TAG).d("Executing morning briefing loop for %s", workerName)

        // Gather transaction data
        val now = System.currentTimeMillis() / 1000
        val todayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
        val yesterdayStart = todayStart - 86400
        val weekAgoStart = todayStart - 7 * 86400

        val todayTransactions = businessAgent.getTransactionsForDate(todayStart, now)
        val yesterdayTransactions = businessAgent.getTransactionsForDate(yesterdayStart, todayStart)
        val recentTransactions = businessAgent.getTransactionsForDate(weekAgoStart, now)

        // Generate and deliver briefing
        val result = briefingDelivery.deliverMorningBriefing(
            workerName = workerName,
            assistantName = assistantName,
            workerType = workerType,
            todayTransactions = todayTransactions,
            yesterdayTransactions = yesterdayTransactions,
            recentTransactions = recentTransactions
        )

        // Enrich briefing with pattern tracker insights
        val enrichedMessage = enrichBriefingWithInsights(result.message)
        val enrichedResult = result.copy(message = enrichedMessage)

        // Record delivery for outcome tracking
        val entityId = briefingDeliveryDao.insert(
            BriefingDeliveryEntity(
                briefingType = BriefingType.MORNING.name,
                briefingText = enrichedResult.message,
                predictedSales = enrichedResult.data["todaySales"]?.toDoubleOrNull() ?: 0.0,
                predictedProfit = enrichedResult.data["todayProfit"]?.toDoubleOrNull() ?: 0.0,
                keyAdvice = extractKeyAdvice(enrichedResult.message)
            )
        )

        Timber.tag(TAG).d("Morning briefing delivered and recorded (id=%d)", entityId)

        // Personalize: adjust briefing based on what worker historically acts on
        personalizeNextBriefing()

        return enrichedResult
    }

    // ═══════════════════════════════════════════════════════════════
    // LOOP STEP 2: TRACK WORKER ACTIONS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Called when worker records a transaction after a briefing was delivered.
     * Closes the loop: did the worker act on the briefing?
     *
     * @param transaction The transaction the worker just recorded
     */
    suspend fun onTransactionAfterBriefing(transaction: Transaction) {
        val pendingBriefing = briefingDeliveryDao.getLatestPendingBriefing() ?: return

        // Mark briefing as acted on
        val actualSales = calculateActualSalesSince(pendingBriefing.deliveredAt)
        val actualProfit = calculateActualProfitSince(pendingBriefing.deliveredAt)
        val predictedSales = pendingBriefing.predictedSales
        val predictedProfit = pendingBriefing.predictedProfit

        // Outcome score: how close was prediction to actual? (-1.0 to 1.0)
        val outcomeScore = calculateOutcomeScore(predictedSales, actualSales, predictedProfit, actualProfit)

        // Check if advice was followed (e.g., restocked recommended item)
        val adviceFollowed = checkAdviceFollowed(pendingBriefing.keyAdvice, transaction)

        briefingDeliveryDao.markActedOn(
            id = pendingBriefing.id,
            actualSales = actualSales,
            actualProfit = actualProfit,
            outcomeScore = outcomeScore,
            adviceFollowed = adviceFollowed
        )

        Timber.tag(TAG).d(
            "Briefing outcome tracked: predicted=%.0f actual=%.0f score=%.2f adviceFollowed=%b",
            predictedSales, actualSales, outcomeScore, adviceFollowed
        )
    }

    /**
     * Mark the latest pending briefing as opened (when worker views it).
     */
    suspend fun onBriefingOpened() {
        val pending = briefingDeliveryDao.getLatestPendingBriefing()
        if (pending != null) {
            briefingDeliveryDao.markOpened(pending.id)
            Timber.tag(TAG).d("Briefing %d marked as opened", pending.id)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // LOOP STEP 3: PERSONALIZE FUTURE BRIEFINGS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Analyze past briefing outcomes to personalize future briefings.
     *
     * If the worker consistently acts on restock advice but ignores
     * savings advice, future briefings should emphasize restocking.
     *
     * STA 342 (Hypothesis Testing): We test whether certain advice types
     * have significantly higher follow-through rates.
     */
    private suspend fun personalizeNextBriefing() {
        val recentBriefings = briefingDeliveryDao.getRecent(BRIEFING_HISTORY_WINDOW)

        if (recentBriefings.size < MIN_BRIEFINGS_FOR_PERSONALIZATION) {
            Timber.tag(TAG).d("Not enough briefings for personalization (%d)", recentBriefings.size)
            return
        }

        val actedOnCount = recentBriefings.count { it.actedOn }
        val actThroughRate = actedOnCount.toDouble() / recentBriefings.size

        Timber.tag(TAG).d(
            "Briefing personalization: %d/%d acted on (%.1f%%)",
            actedOnCount, recentBriefings.size, actThroughRate * 100
        )

        // Analyze which advice types get followed most
        val adviceKeywords = listOf("stock", "akiba", "bei", "mauzo")
        for (keyword in adviceKeywords) {
            val total = briefingDeliveryDao.getAdviceTotalCount(keyword)
            val followed = briefingDeliveryDao.getAdviceFollowCount(keyword)
            if (total > 0) {
                val followRate = followed.toDouble() / total
                Timber.tag(TAG).d("Advice '%s': follow rate %.1f%% (%d/%d)", keyword, followRate * 100, followed, total)
            }
        }
    }

    /**
     * Get briefing effectiveness stats for the worker.
     * Useful for showing the worker how well Msaidizi's predictions perform.
     *
     * @return Map of stat name to value
     */
    suspend fun getBriefingStats(): Map<String, Any> {
        val recentBriefings = briefingDeliveryDao.getRecent(BRIEFING_HISTORY_WINDOW)
        if (recentBriefings.isEmpty()) return emptyMap()

        val delivered = recentBriefings.size
        val opened = recentBriefings.count { it.opened }
        val actedOn = recentBriefings.count { it.actedOn }
        val avgOutcome = recentBriefings.filter { it.actedOn }.map { it.outcomeScore }.average()

        return mapOf(
            "totalDelivered" to delivered,
            "totalOpened" to opened,
            "totalActedOn" to actedOn,
            "openRate" to if (delivered > 0) opened.toDouble() / delivered else 0.0,
            "actThroughRate" to if (delivered > 0) actedOn.toDouble() / delivered else 0.0,
            "averageOutcomeScore" to avgOutcome
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Calculate actual sales since a given timestamp.
     */
    private suspend fun calculateActualSalesSince(sinceTimestamp: Long): Double {
        val transactions = transactionDao.getTransactionsInRangeSuspend(
            sinceTimestamp, System.currentTimeMillis() / 1000
        )
        return transactions
            .filter { it.type == TransactionType.SALE }
            .sumOf { it.totalAmount }
    }

    /**
     * Calculate actual profit since a given timestamp.
     */
    private suspend fun calculateActualProfitSince(sinceTimestamp: Long): Double {
        val transactions = transactionDao.getTransactionsInRangeSuspend(
            sinceTimestamp, System.currentTimeMillis() / 1000
        )
        val sales = transactions.filter { it.type == TransactionType.SALE }.sumOf { it.totalAmount }
        val costs = transactions.filter {
            it.type == TransactionType.PURCHASE || it.type == TransactionType.EXPENSE
        }.sumOf { it.totalAmount }
        return sales - costs
    }

    /**
     * Calculate outcome score: how well did prediction match actual?
     *
     * Score range: -1.0 (terrible prediction) to 1.0 (perfect prediction)
     * Formula: 1.0 - |predicted - actual| / max(predicted, actual)
     * Clamped to [-1.0, 1.0]
     */
    private fun calculateOutcomeScore(
        predictedSales: Double,
        actualSales: Double,
        predictedProfit: Double,
        actualProfit: Double
    ): Double {
        val salesScore = if (predictedSales > 0 || actualSales > 0) {
            val maxVal = maxOf(predictedSales, actualSales, 1.0)
            1.0 - Math.abs(predictedSales - actualSales) / maxVal
        } else 1.0

        val profitScore = if (predictedProfit > 0 || actualProfit > 0) {
            val maxVal = maxOf(predictedProfit, actualProfit, 1.0)
            1.0 - Math.abs(predictedProfit - actualProfit) / maxVal
        } else 1.0

        return ((salesScore + profitScore) / 2.0).coerceIn(-1.0, 1.0)
    }

    /**
     * Check if the worker followed the advice given in the briefing.
     * E.g., if advice was "restock nyanya" and worker purchased nyanya.
     */
    private fun checkAdviceFollowed(keyAdvice: String, transaction: Transaction): Boolean? {
        if (keyAdvice.isBlank()) return null

        val adviceLower = keyAdvice.lowercase()
        val itemLower = transaction.item.lowercase()

        // Check if the transaction matches the advice
        return when {
            adviceLower.contains("nunua") && transaction.type == TransactionType.PURCHASE -> {
                adviceLower.contains(itemLower)
            }
            adviceLower.contains("rekodi") && transaction.type == TransactionType.SALE -> true
            else -> null
        }
    }

    /**
     * Extract key advice from briefing message for tracking.
     * Pulls out the actionable part (e.g., "restock nyanya", "save KSh 200").
     */
    private fun extractKeyAdvice(message: String): String {
        val lines = message.lines()
        // Look for lines with advice indicators
        val adviceLine = lines.find { line ->
            line.contains("Ushauri") || line.contains("📦") || line.contains("💡") ||
            line.contains("nunua") || line.contains("weka") || line.contains("Angalia")
        }
        return adviceLine?.take(200) ?: ""
    }

    /**
     * Get worker's transactions for a date range (helper).
     */
    private suspend fun BusinessAgent.getTransactionsForDate(
        startTimestamp: Long,
        endTimestamp: Long
    ): List<Transaction> {
        // Use the existing transactionDao through the business agent's data
        return transactionDao.getTransactionsInRangeSuspend(startTimestamp, endTimestamp)
    }

    // ═══════════════ PATTERN TRACKER ENRICHMENT ═══════════════

    /**
     * Enrich briefing message with BusinessPatternTracker insights.
     * Adds trend data, peak day info, and product insights
     * that the CFOEngine might not have access to.
     */
    private suspend fun enrichBriefingWithInsights(message: String): String {
        val tracker = patternTracker ?: return message

        val insights = StringBuilder()
        insights.appendLine()
        insights.appendLine("📊 Insights from your patterns:")

        try {
            // Weekly trend
            val trend = tracker.detectWeeklyTrend()
            if (trend.direction != com.msaidizi.app.core.model.Trend.INSUFFICIENT_DATA) {
                val trendEmoji = when (trend.direction) {
                    com.msaidizi.app.core.model.Trend.RISING -> "📈"
                    com.msaidizi.app.core.model.Trend.FALLING -> "📉"
                    com.msaidizi.app.core.model.Trend.STABLE -> "➡️"
                    else -> ""
                }
                insights.appendLine("$trendEmoji Sales trend: ${trend.direction.name.lowercase()} (${trend.changePercent.toInt()}% vs last week)")
            }

            // Top product insight
            val products = tracker.analyzeProductPerformance(14)
            val topProduct = products.firstOrNull()
            if (topProduct != null) {
                insights.appendLine("⭐ Top product: ${topProduct.item} (margin ${topProduct.profitMargin.toInt()}%)")
            }

            // Peak hours
            val peakHours = tracker.analyzePeakHours(7)
            val peaks = peakHours.filter { it.isPeakHour }.take(2)
            if (peaks.isNotEmpty()) {
                insights.appendLine("⏰ Busiest hours: ${peaks.joinToString(", ") { "${it.hour}:00" }}")
            }

            // Business health
            val health = tracker.calculateBusinessHealthScore()
            if (health.totalScore < 50) {
                insights.appendLine("⚠️ Business health: ${health.totalScore.toInt()}/100 — let's work on improving this!")
            }
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "Failed to enrich briefing with pattern insights")
            return message
        }

        return message + insights.toString()
    }
}
