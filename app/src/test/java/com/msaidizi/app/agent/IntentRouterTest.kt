package com.msaidizi.app.agent

import com.msaidizi.app.core.model.IntentType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * Comprehensive unit tests for IntentRouter — the code-only intent classifier.
 * Tests Swahili, English, and Sheng input patterns with JUnit 5.
 */
@DisplayName("IntentRouter")
class IntentRouterTest {

    private lateinit var router: IntentRouter

    @BeforeEach
    fun setUp() {
        router = IntentRouter()
    }

    // ── Sale Intent ─────────────────────────────────────────────

    @Nested
    @DisplayName("Sale Intent Classification")
    inner class SaleIntent {

        @ParameterizedTest(name = "\"{0}\" → SALE")
        @ValueSource(strings = [
            "Nimeuza mandazi kumi kwa 500",
            "Nimeuza nyanya kwa 300",
            "Sold 10 mandazi for 500",
            "Sale mandazi 500"
        ])
        fun `classifies sale inputs correctly`(input: String) {
            val result = router.classify(input)
            assertEquals(IntentType.SALE, result.intent)
            assertTrue(result.confidence >= 0.5, "Confidence ${result.confidence} should be >= 0.5")
        }

        @Test
        fun `classifies Sheng sale`() {
            val result = router.classify("nikaue mandazi tano mia tano")
            assertEquals(IntentType.SALE, result.intent)
        }

        @Test
        fun `extracts item and amount from Swahili sale`() {
            val result = router.classify("Nimeuza mandazi kumi kwa 500")
            assertEquals(IntentType.SALE, result.intent)
            assertTrue(result.extractedData.isNotEmpty(), "Should extract data")
        }
    }

    // ── Purchase Intent ─────────────────────────────────────────

    @Nested
    @DisplayName("Purchase Intent Classification")
    inner class PurchaseIntent {

        @Test
        fun `classifies Swahili purchase`() {
            val result = router.classify("Nimenunua unga kwa 200")
            assertEquals(IntentType.PURCHASE, result.intent)
        }

        @Test
        fun `classifies English purchase`() {
            val result = router.classify("I bought flour for 200")
            assertEquals(IntentType.PURCHASE, result.intent)
        }

        @Test
        fun `classifies Sheng purchase`() {
            val result = router.classify("nika-buy unga 200")
            assertEquals(IntentType.PURCHASE, result.intent)
        }
    }

    // ── Expense Intent ──────────────────────────────────────────

    @Nested
    @DisplayName("Expense Intent Classification")
    inner class ExpenseIntent {

        @Test
        fun `classifies Swahili expense`() {
            val result = router.classify("Nimetumia 100 kwa usafiri")
            assertEquals(IntentType.EXPENSE, result.intent)
        }

        @Test
        fun `classifies English expense`() {
            val result = router.classify("I spent 100 on transport")
            assertEquals(IntentType.EXPENSE, result.intent)
        }

        @ParameterizedTest(name = "\"{0}\" → EXPENSE")
        @ValueSource(strings = [
            "Fuel 300",
            "Airtime 100",
            "Float 10000",
            "SACCO 200",
            "Delivery 200"
        ])
        fun `classifies expense keywords`(input: String) {
            val result = router.classify(input)
            assertEquals(IntentType.EXPENSE, result.intent)
        }
    }

    // ── Query Intents ───────────────────────────────────────────

    @Nested
    @DisplayName("Query Intent Classification")
    inner class QueryIntent {

        @Test
        fun `classifies balance query`() {
            val result = router.classify("Salio langu ni ngapi")
            assertEquals(IntentType.CHECK_BALANCE, result.intent)
        }

        @Test
        fun `classifies profit query`() {
            val result = router.classify("Faida yangu ni ngapi")
            assertEquals(IntentType.PROFIT_QUERY, result.intent)
        }

        @Test
        fun `classifies daily summary Swahili`() {
            val result = router.classify("Ripoti ya leo")
            assertEquals(IntentType.DAILY_SUMMARY, result.intent)
        }

        @Test
        fun `classifies daily summary English`() {
            val result = router.classify("Today's report")
            assertEquals(IntentType.DAILY_SUMMARY, result.intent)
        }

        @Test
        fun `classifies weekly summary`() {
            val result = router.classify("Report ya wiki")
            assertEquals(IntentType.WEEKLY_SUMMARY, result.intent)
        }
    }

    // ── Social Intents ──────────────────────────────────────────

    @Nested
    @DisplayName("Social Intent Classification")
    inner class SocialIntent {

        @Test
        fun `classifies greeting`() {
            val result = router.classify("Habari")
            assertEquals(IntentType.GREETING, result.intent)
        }

        @Test
        fun `classifies English greeting`() {
            val result = router.classify("Hello")
            assertEquals(IntentType.GREETING, result.intent)
        }

        @Test
        fun `classifies help`() {
            val result = router.classify("Msaada")
            assertEquals(IntentType.HELP, result.intent)
        }

        @Test
        fun `classifies English help`() {
            val result = router.classify("Help me")
            assertEquals(IntentType.HELP, result.intent)
        }
    }

    // ── Domain-Specific Intents ─────────────────────────────────

    @Nested
    @DisplayName("Domain-Specific Intents")
    inner class DomainIntent {

        @Test
        fun `classifies giving record`() {
            val result = router.classify("Nimetolea kanisa 500")
            assertEquals(IntentType.GIVING_RECORD, result.intent)
        }

        @Test
        fun `classifies goal creation`() {
            val result = router.classify("Lengo langu ni kununua friji")
            assertEquals(IntentType.GOAL_CREATE, result.intent)
        }

        @Test
        fun `classifies loan record`() {
            val result = router.classify("Nimechukua mkopo wa 10000")
            assertEquals(IntentType.LOAN_RECORD, result.intent)
        }
    }

    // ── Edge Cases ──────────────────────────────────────────────

    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCases {

        @Test
        fun `empty input returns UNKNOWN`() {
            val result = router.classify("")
            assertEquals(IntentType.UNKNOWN, result.intent)
        }

        @Test
        fun `blank input returns UNKNOWN`() {
            val result = router.classify("   ")
            assertEquals(IntentType.UNKNOWN, result.intent)
        }

        @Test
        fun `gibberish returns UNKNOWN`() {
            val result = router.classify("asdfghjkl random gibberish xyz")
            assertEquals(IntentType.UNKNOWN, result.intent)
        }

        @Test
        fun `single word greeting still classifies`() {
            val result = router.classify("Habari")
            assertEquals(IntentType.GREETING, result.intent)
        }
    }

    // ── Confidence Scores ───────────────────────────────────────

    @Nested
    @DisplayName("Confidence Scores")
    inner class ConfidenceTests {

        @Test
        fun `sale with explicit amount has high confidence`() {
            val result = router.classify("Nimeuza mandazi kumi kwa 500")
            assertTrue(result.confidence >= 0.9, "Confidence ${result.confidence} should be >= 0.9")
        }

        @Test
        fun `greeting has high confidence`() {
            val result = router.classify("Habari")
            assertTrue(result.confidence >= 0.9, "Confidence ${result.confidence} should be >= 0.9")
        }

        @Test
        fun `unknown has zero confidence`() {
            val result = router.classify("asdfghjkl12345")
            assertEquals(0.0, result.confidence)
        }
    }
}
