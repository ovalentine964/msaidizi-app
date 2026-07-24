package com.msaidizi.app.superagent.credit

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [AlamaScoreEngine] — 8-pillar financial readiness assessment.
 *
 * Covers score computation, pillar calculations, confidence, tier assignment,
 * and what-if simulation.
 */
class AlamaScoreEngineTest {

    private lateinit var engine: AlamaScoreEngine

    @BeforeEach
    fun setup() {
        engine = AlamaScoreEngine()
    }

    // ── Helper: generate transactions ──

    private fun generateTransactions(
        count: Int = 30,
        saleAmount: Double = 500.0,
        expenseAmount: Double = 200.0,
        daysSpan: Int = 30,
        now: Long = System.currentTimeMillis() / 1000
    ): List<AlamaTransaction> {
        val txns = mutableListOf<AlamaTransaction>()
        val secondsPerDay = 86_400L
        for (i in 0 until count) {
            val dayOffset = (i * daysSpan / count).toLong()
            val ts = now - (daysSpan - dayOffset) * secondsPerDay

            // Sale
            txns.add(AlamaTransaction(
                type = "SALE",
                amount = saleAmount + (i % 5) * 50.0,
                costBasis = saleAmount * 0.6,
                category = "food",
                item = "mandazi",
                timestamp = ts,
                confidence = 0.9f
            ))
            // Expense every 3rd transaction
            if (i % 3 == 0) {
                txns.add(AlamaTransaction(
                    type = "EXPENSE",
                    amount = expenseAmount,
                    timestamp = ts + 3600,
                    confidence = 0.9f
                ))
            }
        }
        return txns
    }

    // ═══════════════════════════════════════════════════════════════
    // INSUFFICIENT DATA
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class InsufficientData {

        @Test
        fun `empty transactions returns zero score`() {
            val score = engine.compute(emptyList())
            assertEquals(0.0, score.score)
            assertEquals(0.0, score.confidence)
            assertEquals(ConfidenceLevel.INSUFFICIENT, score.confidenceLevel)
            assertEquals(AlamaTier.BUILDING, score.tier)
            assertTrue(score.message.contains("hakuna data"))
        }

        @Test
        fun `fewer than 3 transactions returns insufficient`() {
            val txns = listOf(
                AlamaTransaction(type = "SALE", amount = 500.0, timestamp = System.currentTimeMillis() / 1000)
            )
            val score = engine.compute(txns)
            assertEquals(0.0, score.score)
            assertEquals(ConfidenceLevel.INSUFFICIENT, score.confidenceLevel)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // SCORE COMPUTATION
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class ScoreComputation {

        @Test
        fun `score is between 0 and 100`() {
            val txns = generateTransactions(count = 50)
            val score = engine.compute(txns)

            assertTrue(score.score in 0.0..100.0,
                "Score should be 0-100, got ${score.score}")
        }

        @Test
        fun `score has 8 pillars`() {
            val txns = generateTransactions(count = 50)
            val score = engine.compute(txns)

            assertEquals(8, score.pillars.size)
        }

        @Test
        fun `pillar weights sum to 1`() {
            val totalWeight = Pillar.entries.sumOf { it.weight }
            assertEquals(1.0, totalWeight, 0.001)
        }

        @Test
        fun `more transactions yield higher confidence`() {
            val fewTxns = generateTransactions(count = 5)
            val manyTxns = generateTransactions(count = 100)

            val fewScore = engine.compute(fewTxns)
            val manyScore = engine.compute(manyTxns)

            assertTrue(manyScore.confidence > fewScore.confidence,
                "More data should yield higher confidence")
        }

        @Test
        fun `active business gets reasonable score`() {
            // 60 transactions over 60 days, good margins
            val txns = generateTransactions(
                count = 60, saleAmount = 1000.0,
                expenseAmount = 300.0, daysSpan = 60
            )
            val score = engine.compute(txns)

            assertTrue(score.score > 30, "Active business should score > 30, got ${score.score}")
            assertTrue(score.activeDays > 10, "Should have many active days")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // TIER ASSIGNMENT
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class TierAssignment {

        @Test
        fun `tier from score mapping`() {
            assertEquals(AlamaTier.BUILDING, AlamaTier.fromScore(10))
            assertEquals(AlamaTier.STARTING, AlamaTier.fromScore(30))
            assertEquals(AlamaTier.GROWING, AlamaTier.fromScore(50))
            assertEquals(AlamaTier.ESTABLISHED, AlamaTier.fromScore(65))
            assertEquals(AlamaTier.THRIVING, AlamaTier.fromScore(80))
            assertEquals(AlamaTier.EXCELLENT, AlamaTier.fromScore(95))
        }

        @Test
        fun `out of range scores clamp to tier`() {
            assertEquals(AlamaTier.BUILDING, AlamaTier.fromScore(-5))
            assertEquals(AlamaTier.EXCELLENT, AlamaTier.fromScore(150))
        }

        @Test
        fun `estimated max loan increases with tier`() {
            assertTrue(AlamaTier.BUILDING.estimatedMaxLoan == 0)
            assertTrue(AlamaTier.EXCELLENT.estimatedMaxLoan > AlamaTier.THRIVING.estimatedMaxLoan)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // CONFIDENCE LEVEL
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class Confidence {

        @Test
        fun `insufficient confidence with few transactions`() {
            val txns = generateTransactions(count = 3, daysSpan = 2)
            val score = engine.compute(txns)
            assertEquals(ConfidenceLevel.INSUFFICIENT, score.confidenceLevel)
        }

        @Test
        fun `high confidence with many transactions`() {
            val txns = generateTransactions(count = 100, daysSpan = 90)
            val score = engine.compute(txns)
            assertTrue(score.confidenceLevel == ConfidenceLevel.HIGH ||
                score.confidenceLevel == ConfidenceLevel.MODERATE,
                "100 txns over 90 days should be moderate+, got ${score.confidenceLevel}")
        }

        @Test
        fun `confidence dampens score toward neutral`() {
            // Low confidence should pull score toward 50
            val txns = generateTransactions(count = 3, daysSpan = 2)
            val score = engine.compute(txns)
            // With very low confidence, adjusted score should be near 50
            if (score.confidence < 0.25) {
                assertTrue(score.score in 20.0..80.0,
                    "Low confidence score should be near neutral")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // WHAT-IF SIMULATION
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class WhatIfSimulation {

        @Test
        fun `improving pillars increases score`() {
            val txns = generateTransactions(count = 50, daysSpan = 60)
            val currentScore = engine.compute(txns)

            val changes = mapOf(
                Pillar.FREQUENCY to 90.0,
                Pillar.REVENUE_TREND to 85.0,
                Pillar.MARGINS to 80.0
            )

            val result = engine.simulate(currentScore, changes)

            assertTrue(result.simulatedScore.score >= currentScore.score,
                "Improving pillars should increase or maintain score")
            assertTrue(result.message.contains("📈") || result.message.contains("panda"),
                "Message should indicate improvement")
        }

        @Test
        fun `simulation preserves confidence`() {
            val txns = generateTransactions(count = 50, daysSpan = 60)
            val currentScore = engine.compute(txns)

            val result = engine.simulate(currentScore, mapOf(Pillar.FREQUENCY to 100.0))

            assertEquals(currentScore.confidence, result.simulatedScore.confidence)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PILLAR-SPECIFIC TESTS
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class Pillars {

        @Test
        fun `frequency pillar exists in result`() {
            val txns = generateTransactions(count = 30)
            val score = engine.compute(txns)

            val frequency = score.pillars.find { it.pillar == Pillar.FREQUENCY }
            assertNotNull(frequency)
            assertTrue(frequency!!.rawValue in 0.0..100.0)
        }

        @Test
        fun `margins pillar penalizes negative margins`() {
            // Expenses > revenue
            val txns = listOf(
                AlamaTransaction(type = "SALE", amount = 100.0, costBasis = 50.0,
                    timestamp = System.currentTimeMillis() / 1000 - 86400),
                AlamaTransaction(type = "SALE", amount = 100.0, costBasis = 50.0,
                    timestamp = System.currentTimeMillis() / 1000),
                AlamaTransaction(type = "EXPENSE", amount = 500.0,
                    timestamp = System.currentTimeMillis() / 1000),
                AlamaTransaction(type = "EXPENSE", amount = 500.0,
                    timestamp = System.currentTimeMillis() / 1000 - 86400),
            )
            val score = engine.compute(txns)

            val margins = score.pillars.find { it.pillar == Pillar.MARGINS }
            assertNotNull(margins)
            assertTrue(margins!!.rawValue < 50, "Negative margins should score low")
        }

        @Test
        fun `diversity pillar rewards multiple categories`() {
            val now = System.currentTimeMillis() / 1000
            val diverse = (0 until 30).map { i ->
                AlamaTransaction(
                    type = "SALE", amount = 500.0,
                    category = "cat_${i % 6}", item = "item_$i",
                    timestamp = now - (30 - i) * 86400
                )
            }
            val single = (0 until 30).map { i ->
                AlamaTransaction(
                    type = "SALE", amount = 500.0,
                    category = "food", item = "mandazi",
                    timestamp = now - (30 - i) * 86400
                )
            }

            val diverseScore = engine.compute(diverse)
            val singleScore = engine.compute(single)

            val diverseDiversity = diverseScore.pillars.find { it.pillar == Pillar.DIVERSITY }!!
            val singleDiversity = singleScore.pillars.find { it.pillar == Pillar.DIVERSITY }!!

            assertTrue(diverseDiversity.rawValue >= singleDiversity.rawValue,
                "More diverse products should score higher")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PRODUCT ELIGIBILITY
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class ProductEligibility {

        @Test
        fun `insufficient data is not eligible`() {
            val score = engine.compute(emptyList())
            assertFalse(score.isEligibleForProducts)
        }

        @Test
        fun `good score with data is eligible`() {
            val txns = generateTransactions(count = 100, daysSpan = 90)
            val score = engine.compute(txns)

            if (score.confidenceLevel == ConfidenceLevel.HIGH ||
                score.confidenceLevel == ConfidenceLevel.MODERATE) {
                assertTrue(score.isEligibleForProducts)
            }
        }
    }
}
