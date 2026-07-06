package com.msaidizi.app.agent

import com.msaidizi.app.core.model.IntentType
import com.msaidizi.app.core.model.IntentResult
import com.msaidizi.app.core.model.Transaction
import com.msaidizi.app.core.model.TransactionType
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Unit tests for Orchestrator — the main agent coordinator.
 * Tests routing, confidence escalation, error recovery, and agent coordination.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MockKExtension::class)
@DisplayName("Orchestrator")
class OrchestratorTest {

    @MockK private lateinit var intentRouter: IntentRouter
    @MockK private lateinit var businessAgent: BusinessAgent
    @MockK private lateinit var analysisAgent: AnalysisAgent
    @MockK private lateinit var advisorAgent: AdvisorAgent
    @MockK private lateinit var learningAgent: LearningAgent
    @MockK private lateinit var adaptiveLearning: AdaptiveLearningEngine

    private lateinit var orchestrator: Orchestrator

    @BeforeEach
    fun setUp() {
        orchestrator = Orchestrator(
            intentRouter = intentRouter,
            businessAgent = businessAgent,
            analysisAgent = analysisAgent,
            advisorAgent = advisorAgent,
            learningAgent = learningAgent,
            adaptiveLearning = adaptiveLearning
        )
    }

    // ── Sale Processing ─────────────────────────────────────────

    @Nested
    @DisplayName("Sale Processing")
    inner class SaleTests {

        @Test
        fun `processes sale and returns confirmation`() = runTest {
            val intentResult = IntentResult(
                intent = IntentType.SALE,
                confidence = 0.95,
                extractedData = mapOf(
                    "item" to "mandazi",
                    "quantity" to "10",
                    "amount" to "500",
                    "originalText" to "Nimeuza mandazi kumi kwa 500"
                )
            )

            coEvery { intentRouter.classify(any()) } returns intentResult
            coEvery { adaptiveLearning.enhanceIntentWithLearning(any(), any()) } returns intentResult
            coEvery { businessAgent.recordSale(any(), any(), any(), any()) } returns Transaction(
                type = TransactionType.SALE,
                item = "mandazi",
                quantity = 10.0,
                totalAmount = 500.0,
                id = 1
            )
            coEvery { adaptiveLearning.learnFromTransaction(any()) } just Runs
            coEvery { learningAgent.recordPattern(any(), any()) } just Runs
            coEvery { learningAgent.recordSaleTime(any(), any()) } just Runs

            val response = orchestrator.processInput("Nimeuza mandazi kumi kwa 500", "sw")

            assertEquals(ResponseType.CONFIRMATION, response.type)
            assertTrue(response.text.contains("mandazi") || response.text.contains("Umeuza"))
        }
    }

    // ── Balance Query ───────────────────────────────────────────

    @Nested
    @DisplayName("Balance Query")
    inner class BalanceTests {

        @Test
        fun `processes balance query and returns information`() = runTest {
            val intentResult = IntentResult(
                intent = IntentType.CHECK_BALANCE,
                confidence = 0.90
            )

            coEvery { intentRouter.classify(any()) } returns intentResult
            coEvery { adaptiveLearning.enhanceIntentWithLearning(any(), any()) } returns intentResult
            coEvery { businessAgent.getBalance() } returns 5000.0
            coEvery { learningAgent.recordPattern(any(), any()) } just Runs

            val response = orchestrator.processInput("Salio langu ni ngapi", "sw")

            assertEquals(ResponseType.INFORMATION, response.type)
            assertTrue(response.text.contains("5000") || response.text.contains("Salio"))
        }
    }

    // ── Greeting ────────────────────────────────────────────────

    @Nested
    @DisplayName("Greeting")
    inner class GreetingTests {

        @Test
        fun `handles greeting with friendly response`() = runTest {
            val intentResult = IntentResult(
                intent = IntentType.GREETING,
                confidence = 0.95
            )

            coEvery { intentRouter.classify(any()) } returns intentResult
            coEvery { adaptiveLearning.enhanceIntentWithLearning(any(), any()) } returns intentResult
            coEvery { advisorAgent.getGreeting(any()) } returns "Habari! Karibu!"
            coEvery { learningAgent.recordPattern(any(), any()) } just Runs

            val response = orchestrator.processInput("Habari", "sw")

            assertEquals(ResponseType.GREETING, response.type)
        }
    }

    // ── Unknown Intent ──────────────────────────────────────────

    @Nested
    @DisplayName("Unknown Intent")
    inner class UnknownTests {

        @Test
        fun `handles unknown intent gracefully`() = runTest {
            val intentResult = IntentResult(
                intent = IntentType.UNKNOWN,
                confidence = 0.0
            )

            coEvery { intentRouter.classify(any()) } returns intentResult
            coEvery { adaptiveLearning.enhanceIntentWithLearning(any(), any()) } returns intentResult
            coEvery { learningAgent.recordPattern(any(), any()) } just Runs

            val response = orchestrator.processInput("asdfghjkl", "sw")

            assertEquals(ResponseType.UNKNOWN, response.type)
        }
    }

    // ── Error Recovery ──────────────────────────────────────────

    @Nested
    @DisplayName("Error Recovery")
    inner class ErrorRecoveryTests {

        @Test
        fun `returns error response when agent throws exception`() = runTest {
            val intentResult = IntentResult(
                intent = IntentType.SALE,
                confidence = 0.95,
                extractedData = mapOf(
                    "item" to "mandazi",
                    "amount" to "500",
                    "originalText" to "Nimeuza mandazi kwa 500"
                )
            )

            coEvery { intentRouter.classify(any()) } returns intentResult
            coEvery { adaptiveLearning.enhanceIntentWithLearning(any(), any()) } returns intentResult
            coEvery { businessAgent.recordSale(any(), any(), any(), any()) } throws RuntimeException("DB error")
            coEvery { learningAgent.recordPattern(any(), any()) } just Runs

            val response = orchestrator.processInput("Nimeuza mandazi kwa 500", "sw")

            assertEquals(ResponseType.ERROR, response.type)
        }
    }

    // ── Language Handling ───────────────────────────────────────

    @Nested
    @DisplayName("Language Handling")
    inner class LanguageTests {

        @Test
        fun `responds in Swahili when language is sw`() = runTest {
            val intentResult = IntentResult(
                intent = IntentType.UNKNOWN,
                confidence = 0.0
            )

            coEvery { intentRouter.classify(any()) } returns intentResult
            coEvery { adaptiveLearning.enhanceIntentWithLearning(any(), any()) } returns intentResult
            coEvery { learningAgent.recordPattern(any(), any()) } just Runs

            val response = orchestrator.processInput("asdfgh", "sw")

            // Swahili response should contain Swahili words
            assertTrue(
                response.text.contains("Sijaelewa") || response.text.contains("Jaribu") || response.text.contains("Sema"),
                "Should respond in Swahili"
            )
        }

        @Test
        fun `responds in English when language is en`() = runTest {
            val intentResult = IntentResult(
                intent = IntentType.UNKNOWN,
                confidence = 0.0
            )

            coEvery { intentRouter.classify(any()) } returns intentResult
            coEvery { adaptiveLearning.enhanceIntentWithLearning(any(), any()) } returns intentResult
            coEvery { learningAgent.recordPattern(any(), any()) } just Runs

            val response = orchestrator.processInput("asdfgh", "en")

            assertTrue(
                response.text.contains("didn't understand") || response.text.contains("Try again") || response.text.contains("Say"),
                "Should respond in English"
            )
        }
    }
}
