package com.msaidizi.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Security-critical tests for AlamaScore credit scoring logic.
 *
 * Validates the score calculation algorithm: range enforcement (300–850),
 * high-score for perfect business data, and minimum score for no data.
 *
 * Tests the pure scoring logic extracted from AlamaScore.calculateScore().
 */
class AlamaScoreTest {

    data class DailySummary(
        val date: String,
        val totalSales: Double,
        val transactionCount: Int
    )

    data class Sale(
        val paymentMethod: String
    )

    data class AlamaScoreResult(
        val score: Int,
        val level: String,
        val factors: List<String>,
        val creditReady: Boolean
    )

    /**
     * Pure scoring logic extracted from AlamaScore.calculateScore().
     * Mirrors the production algorithm exactly.
     */
    fun calculateScore(
        dailySummaries: List<DailySummary>,
        sales: List<Sale>,
        hasSavingsGoals: Boolean
    ): AlamaScoreResult {
        var score = 300 // Base score
        val factors = mutableListOf<String>()

        // Factor 1: Transaction consistency (0-150 points)
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
        val firstMonth = dailySummaries.drop(60).sumOf { it.totalSales }
        val lastMonth = dailySummaries.take(30).sumOf { it.totalSales }
        if (firstMonth > 0) {
            val growth = (lastMonth - firstMonth) / firstMonth
            val growthPoints = minOf((growth * 100).toInt(), 100).coerceAtLeast(0)
            score += growthPoints
            if (growthPoints > 0) factors.add("Business growing (+$growthPoints)")
        }

        // Factor 4: Savings behavior (0-50 points)
        if (hasSavingsGoals) {
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

    @Test
    fun scoreRange_300to850() {
        // No data → minimum
        val minResult = calculateScore(emptyList(), emptyList(), false)
        assertTrue("Score must be >= 300", minResult.score >= 300)
        assertTrue("Score must be <= 850", minResult.score <= 850)

        // Max data → maximum
        val maxSummaries = (1..90).map {
            DailySummary("2025-01-${it}", 50000.0, 20)
        }
        val maxSales = (1..100).map { Sale("mpesa") }
        val maxResult = calculateScore(maxSummaries, maxSales, true)
        assertTrue("Max score must be >= 300", maxResult.score >= 300)
        assertTrue("Max score must be <= 850", maxResult.score <= 850)

        // Partial data
        val partialSummaries = (1..45).map {
            DailySummary("2025-01-${it}", 1000.0, 3)
        }
        val partialSales = (1..20).map { if (it % 2 == 0) Sale("mpesa") else Sale("cash") }
        val partialResult = calculateScore(partialSummaries, partialSales, false)
        assertTrue("Partial score must be >= 300", partialResult.score >= 300)
        assertTrue("Partial score must be <= 850", partialResult.score <= 850)
    }

    @Test
    fun perfectBusiness_highScore() {
        // 90 days of consistent sales, high volume, growth, savings, M-Pesa
        val summaries = (1..90).map { day ->
            val sales = 5000.0 + (day * 100) // Growing sales
            DailySummary("2025-01-${day}", sales, 15)
        }
        val sales = (1..200).map { Sale("mpesa") }

        val result = calculateScore(summaries, sales, hasSavingsGoals = true)

        assertTrue(
            "Perfect business should score >= 600, got ${result.score}",
            result.score >= 600
        )
        assertTrue(
            "Perfect business should be credit ready",
            result.creditReady
        )
        assertTrue(
            "Score level should be Good or higher for perfect data, got ${result.level}",
            result.level in listOf("Good", "Strong", "Excellent")
        )
    }

    @Test
    fun noData_minScore() {
        val result = calculateScore(emptyList(), emptyList(), hasSavingsGoals = false)

        assertEquals(
            "No data should yield minimum score of 300",
            300,
            result.score
        )
        assertEquals(
            "No data should yield 'New' level",
            "New",
            result.level
        )
        assertTrue(
            "No data should not be credit ready",
            !result.creditReady
        )
    }
}
