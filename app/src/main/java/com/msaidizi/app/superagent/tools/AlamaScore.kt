package com.msaidizi.app.superagent.tools

import com.msaidizi.app.core.database.DailySummaryDao
import com.msaidizi.app.core.database.SaleDao
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

data class AlamaScoreResult(
    val score: Int,          // 300-850
    val level: String,       // "New", "Building", "Good", "Strong", "Excellent"
    val factors: List<String>,
    val creditReady: Boolean
)

/**
 * AlamaScore — Credit scoring tool built from the worker's actual business data.
 *
 * Computes a score (300–850) from:
 *   1. Transaction consistency over the last 90 days
 *   2. Transaction volume
 *   3. Business growth (first month vs last month)
 *   4. Savings behavior (from memory)
 *   5. M-Pesa usage
 */
@Singleton
class AlamaScore @Inject constructor(
    private val saleDao: SaleDao,
    private val dailySummaryDao: DailySummaryDao,
    private val memoryManager: MemoryManager
) : Tool {

    override val name = "alama_score"
    override val description = "Build credit score from business data"

    override suspend fun execute(params: Map<String, String>): ToolResult {
        return try {
            val result = calculateScore()
            ToolResult.success(
                toolName = name,
                data = mapOf(
                    "score" to result.score,
                    "level" to result.level,
                    "factors" to result.factors,
                    "creditReady" to result.creditReady
                ),
                message = "Alama Score: ${result.score} (${result.level})" +
                    if (result.creditReady) " — Credit ready!" else ""
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to calculate Alama Score")
            ToolResult.error(name, "Failed to calculate score: ${e.message}", "CALCULATION_ERROR")
        }
    }

    suspend fun calculateScore(): AlamaScoreResult {
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val today = now.atZone(zone).toLocalDate()
        val ninetyDaysAgo = today.minusDays(90)

        val startMillis = ninetyDaysAgo.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillis = now.toEpochMilli()

        // Fetch data from DAOs
        val sales = saleDao.getSalesBetween(startMillis, endMillis).first()
        val dailySummaries = dailySummaryDao.getSummariesBetween(
            ninetyDaysAgo.format(DateTimeFormatter.ISO_LOCAL_DATE),
            today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        ).first()

        var score = 300 // Base score
        val factors = mutableListOf<String>()

        // Factor 1: Transaction consistency (0-150 points)
        // Count days with recorded sales activity
        val activeDays = dailySummaries.count { it.totalSales > 0 }
        val consistency = activeDays / 90.0
        val consistencyPoints = (consistency * 150).toInt()
        score += consistencyPoints
        if (consistencyPoints > 100) factors.add("Consistent daily transactions (+$consistencyPoints)")

        // Factor 2: Transaction volume (0-100 points)
        val totalTransactions = dailySummaries.sumOf { it.transactionCount }.coerceAtLeast(sales.size)
        val volumePoints = minOf(totalTransactions, 500) / 5
        score += volumePoints
        if (volumePoints > 50) factors.add("Strong transaction volume (+$volumePoints)")

        // Factor 3: Business growth (0-100 points)
        val firstMonth = dailySummaries.drop(60).sumOf { it.totalSales }   // days 61-90 ago (older month)
        val lastMonth = dailySummaries.take(30).sumOf { it.totalSales }     // days 1-30 (recent month)
        if (firstMonth > 0) {
            val growth = (lastMonth - firstMonth) / firstMonth
            val growthPoints = minOf((growth * 100).toInt(), 100).coerceAtLeast(0)
            score += growthPoints
            if (growthPoints > 0) factors.add("Business growing (+$growthPoints)")
        }

        // Factor 4: Savings behavior (0-50 points)
        val savingsGoals = memoryManager.retrieve("savings_goals")
        if (savingsGoals.isNotBlank()) {
            score += 50
            factors.add("Active savings goals (+50)")
        }

        // Factor 5: M-Pesa usage (0-50 points)
        val mpesaTransactions = sales.count { it.paymentMethod == "mpesa" }
        if (mpesaTransactions > 10) {
            score += 50
            factors.add("Regular M-Pesa usage (+50)")
        }

        score = score.coerceIn(300, 850)

        val level = when {
            score < 400 -> "New"
            score < 500 -> "Building"
            score < 650 -> "Good"
            score < 750 -> "Strong"
            else -> "Excellent"
        }

        return AlamaScoreResult(
            score = score,
            level = level,
            factors = factors,
            creditReady = score >= 500
        )
    }
}
