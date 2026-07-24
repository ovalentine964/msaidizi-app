package com.msaidizi.app.superagent.engine

import com.msaidizi.app.superagent.context.ContextEngine
import com.msaidizi.app.superagent.context.WorkerProfile
import com.msaidizi.app.superagent.context.WorkerProfileStore
import com.msaidizi.app.superagent.flywheel.FlywheelEngine
import com.msaidizi.app.superagent.flywheel.FlywheelModels
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [ReasoningEngine] — the OODA loop.
 *
 * Covers intent classification, module routing, confidence gating,
 * clarification, error handling, and correction flow.
 */
class ReasoningEngineTest {

    // Mocks for all dependencies
    private lateinit var intentClassifier: IntentClassifier
    private lateinit var dialectNormalizer: DialectNormalizer
    private lateinit var dataCompletenessChecker: DataCompletenessChecker
    private lateinit var safetyGuard: SafetyGuard
    private lateinit var autonomyManager: AutonomyManager
    private lateinit var financialModule: CapabilityModule
    private lateinit var creditModule: CapabilityModule
    private lateinit var goalsModule: CapabilityModule
    private lateinit var educationModule: CapabilityModule
    private lateinit var gamificationModule: CapabilityModule
    private lateinit var communicationModule: CommunicationModule
    private lateinit var contextEngine: ContextEngine
    private lateinit var flywheel: FlywheelEngine
    private lateinit var llmEngine: LlmEngine
    private lateinit var workerSignalProvider: WorkerSignalProvider
    private lateinit var marketSignalProvider: MarketSignalProvider
    private lateinit var proactiveSignalProvider: ProactiveSignalProvider

    private lateinit var engine: ReasoningEngine

    @BeforeEach
    fun setup() {
        intentClassifier = mockk(relaxed = true)
        dialectNormalizer = mockk(relaxed = true)
        dataCompletenessChecker = mockk(relaxed = true)
        safetyGuard = mockk(relaxed = true)
        autonomyManager = mockk(relaxed = true)
        financialModule = mockk(relaxed = true)
        creditModule = mockk(relaxed = true)
        goalsModule = mockk(relaxed = true)
        educationModule = mockk(relaxed = true)
        gamificationModule = mockk(relaxed = true)
        communicationModule = mockk(relaxed = true)
        contextEngine = mockk(relaxed = true)
        flywheel = mockk(relaxed = true)
        llmEngine = mockk(relaxed = true)
        workerSignalProvider = mockk(relaxed = true)
        marketSignalProvider = mockk(relaxed = true)
        proactiveSignalProvider = mockk(relaxed = true)

        // Default: dialect normalizer returns input unchanged
        every { dialectNormalizer.normalize(any(), any()) } answers { firstArg() }

        // Default: data completeness is satisfied
        every { dataCompletenessChecker.check(any()) } returns CompletenessResult(isComplete = true)

        // Default: safety guard passes through
        every { safetyGuard.check(any(), any(), any()) } answers { firstArg() }

        // Default: autonomy manager passes through
        every { autonomyManager.check(any(), any()) } answers { firstArg() }

        // Default: communication module passes through
        every { communicationModule.personalize(any(), any()) } answers { firstArg() }

        // Default: worker signal
        coEvery { workerSignalProvider.observe(any()) } returns WorkerSignal.empty()
        every { workerSignalProvider.maxLatencyMs } returns 1000

        // Default: market signal
        coEvery { marketSignalProvider.observe(any()) } returns MarketSignal.empty()
        every { marketSignalProvider.maxLatencyMs } returns 1000

        // Default: proactive signal
        coEvery { proactiveSignalProvider.observe(any()) } returns null
        every { proactiveSignalProvider.maxLatencyMs } returns 1000

        // Default: supported intents
        every { financialModule.supportedIntents } returns setOf("SALE", "PURCHASE", "EXPENSE")
        every { creditModule.supportedIntents } returns setOf("LOAN_RECORD", "CREDIT_SCORE")

        engine = ReasoningEngine(
            intentClassifier = intentClassifier,
            dialectNormalizer = dialectNormalizer,
            dataCompletenessChecker = dataCompletenessChecker,
            safetyGuard = safetyGuard,
            autonomyManager = autonomyManager,
            financialModule = financialModule,
            creditModule = creditModule,
            goalsModule = goalsModule,
            educationModule = educationModule,
            gamificationModule = gamificationModule,
            communicationModule = communicationModule,
            contextEngine = contextEngine,
            flywheel = flywheel,
            llmEngine = llmEngine,
            workerSignalProvider = workerSignalProvider,
            marketSignalProvider = marketSignalProvider,
            proactiveSignalProvider = proactiveSignalProvider
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // HIGH CONFIDENCE PATH
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class HighConfidencePath {

        @Test
        fun `high confidence sale routes to financial module`() = runTest {
            coEvery { intentClassifier.classify(any()) } returns ParseResult(
                intent = "SALE",
                extractedData = mapOf("amount" to 500.0, "item" to "mandazi"),
                confidence = 0.95f,
                method = ParseMethod.PATTERN
            )
            coEvery { financialModule.handle(any()) } returns AgentResponse(
                text = "Sawa, nimeandika mauzo ya mandazi KSh 500.",
                type = ResponseType.TRANSACTION_CONFIRMATION
            )

            val result = engine.processText("Nimeuziwa mandazi mia tano")

            assertEquals(ResponseType.TRANSACTION_CONFIRMATION, result.response.type)
            coVerify { financialModule.handle(any()) }
        }

        @Test
        fun `high confidence loan routes to credit module`() = runTest {
            coEvery { intentClassifier.classify(any()) } returns ParseResult(
                intent = "LOAN_RECORD",
                extractedData = mapOf("amount" to 2000.0),
                confidence = 0.90f,
                method = ParseMethod.PATTERN
            )
            coEvery { creditModule.handle(any()) } returns AgentResponse(
                text = "Sawa, nimeandika mkopo.",
                type = ResponseType.TRANSACTION_CONFIRMATION
            )

            val result = engine.processText("Nimechukua mkopo elfu mbili")

            coVerify { creditModule.handle(any()) }
        }

        @Test
        fun `transaction intents generate proof points`() = runTest {
            coEvery { intentClassifier.classify(any()) } returns ParseResult(
                intent = "SALE",
                extractedData = mapOf("amount" to 500.0),
                confidence = 0.95f,
                method = ParseMethod.PATTERN
            )
            coEvery { financialModule.handle(any()) } returns AgentResponse(
                text = "Sawa",
                type = ResponseType.TRANSACTION_CONFIRMATION
            )

            val result = engine.processText("Nimeuziwa mandazi")

            assertNotNull(result.proofPoint)
            assertEquals(FlywheelModels.ProofType.TRANSACTION, result.proofPoint!!.type)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // LOW CONFIDENCE / CLARIFICATION
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class Clarification {

        @Test
        fun `low confidence triggers clarification`() = runTest {
            coEvery { intentClassifier.classify(any()) } returns ParseResult(
                intent = "UNKNOWN",
                confidence = 0.2f,
                method = ParseMethod.PATTERN
            )
            coEvery { intentClassifier.fuzzyMatch(any()) } returns ParseResult(
                intent = "UNKNOWN",
                confidence = 0.2f,
                method = ParseMethod.FUZZY
            )

            val result = engine.processText("asdfghjkl")

            assertEquals(ActionType.CLARIFICATION, result.actionType)
            assertEquals(ResponseType.CLARIFICATION, result.response.type)
            assertTrue(result.response.text.contains("Sema tena") ||
                result.response.text.contains("Could you say"))
        }

        @Test
        fun `incomplete data triggers follow-up question`() = runTest {
            every { dataCompletenessChecker.check(any()) } returns CompletenessResult(
                isComplete = false,
                missingFields = listOf("amount"),
                followUpQuestion = "Bei ngapi?"
            )

            val result = engine.processText("Nimeuziwa mandazi")

            assertEquals(ActionType.CLARIFICATION, result.actionType)
            assertTrue(result.response.text.contains("Bei ngapi"))
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // MEDIUM CONFIDENCE → FUZZY MATCH
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class FuzzyMatch {

        @Test
        fun `low pattern confidence falls through to fuzzy match`() = runTest {
            coEvery { intentClassifier.classify(any()) } returns ParseResult(
                intent = "SALE",
                confidence = 0.5f,
                method = ParseMethod.PATTERN
            )
            coEvery { intentClassifier.fuzzyMatch(any()) } returns ParseResult(
                intent = "SALE",
                extractedData = mapOf("amount" to 200.0),
                confidence = 0.75f,
                method = ParseMethod.FUZZY
            )
            coEvery { financialModule.handle(any()) } returns AgentResponse(
                text = "Sawa",
                type = ResponseType.TRANSACTION_CONFIRMATION
            )

            val result = engine.processText("nimuza mandazi")

            coVerify { financialModule.handle(any()) }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // LLM ESCALATION
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class LlmEscalation {

        @Test
        fun `unknown module escalates to LLM`() = runTest {
            coEvery { intentClassifier.classify(any()) } returns ParseResult(
                intent = "UNKNOWN_THING",
                confidence = 0.9f,
                method = ParseMethod.PATTERN
            )
            coEvery { llmEngine.classifyAndRespond(any()) } returns LlmResult(
                intent = "ASK_ADVICE",
                confidence = 0.6f,
                responseText = "Habari! Ninawezaje kukusaidia?"
            )
            coEvery { educationModule.handle(any()) } returns AgentResponse(
                text = "Habari!",
                type = ResponseType.INFORMATION
            )

            val result = engine.processText("How are you today?")

            // Should have escalated and then resolved
            assertNotNull(result)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ERROR HANDLING
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class ErrorHandling {

        @Test
        fun `module execution failure returns error response`() = runTest {
            coEvery { intentClassifier.classify(any()) } returns ParseResult(
                intent = "SALE",
                extractedData = mapOf("amount" to 500.0),
                confidence = 0.95f,
                method = ParseMethod.PATTERN
            )
            coEvery { financialModule.handle(any()) } throws RuntimeException("Database error")

            // Should not throw — should return error response
            val result = engine.processText("Nimeuziwa mandazi")

            assertEquals(ResponseType.ERROR, result.response.type)
        }

        @Test
        fun `general exception returns fallback response`() = runTest {
            coEvery { intentClassifier.classify(any()) } throws RuntimeException("Unexpected")

            val result = engine.processText("test")

            assertEquals(ResponseType.ERROR, result.response.type)
            assertTrue(result.response.text.contains("Pole") || result.response.text.contains("Sorry"))
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // LEARNING SIGNALS
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class LearningSignals {

        @Test
        fun `every interaction produces a learning signal`() = runTest {
            coEvery { intentClassifier.classify(any()) } returns ParseResult(
                intent = "SALE",
                extractedData = mapOf("amount" to 500.0),
                confidence = 0.95f,
                method = ParseMethod.PATTERN
            )
            coEvery { financialModule.handle(any()) } returns AgentResponse(
                text = "Sawa",
                type = ResponseType.TRANSACTION_CONFIRMATION
            )

            val result = engine.processText("Nimeuziwa mandazi")

            assertNotNull(result.learningSignal)
            assertEquals("SALE", result.learningSignal!!.intent)
            assertEquals(0.95f, result.learningSignal!!.confidence)
            assertEquals(ParseMethod.PATTERN, result.learningSignal!!.parseMethod)
        }

        @Test
        fun `learning signal is recorded in flywheel`() = runTest {
            coEvery { intentClassifier.classify(any()) } returns ParseResult(
                intent = "SALE",
                extractedData = mapOf("amount" to 500.0),
                confidence = 0.95f,
                method = ParseMethod.PATTERN
            )
            coEvery { financialModule.handle(any()) } returns AgentResponse(
                text = "Sawa",
                type = ResponseType.TRANSACTION_CONFIRMATION
            )

            engine.processText("Nimeuziwa mandazi")

            verify { flywheel.recordLearningSignal(any()) }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // CONTEXT STORAGE
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class ContextStorage {

        @Test
        fun `interaction stored in context engine`() = runTest {
            coEvery { intentClassifier.classify(any()) } returns ParseResult(
                intent = "SALE",
                extractedData = mapOf("amount" to 500.0),
                confidence = 0.95f,
                method = ParseMethod.PATTERN
            )
            coEvery { financialModule.handle(any()) } returns AgentResponse(
                text = "Sawa",
                type = ResponseType.TRANSACTION_CONFIRMATION
            )

            engine.processText("Nimeuziwa mandazi")

            verify {
                contextEngine.storeInteraction(
                    input = "Nimeuziwa mandazi",
                    intent = "SALE",
                    response = "Sawa"
                )
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PROACTIVE PROCESSING
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class ProactiveProcessing {

        @Test
        fun `proactive trigger processes without user input`() = runTest {
            coEvery { educationModule.handle(any()) } returns AgentResponse(
                text = "Streak yako ni siku 5!",
                type = ResponseType.INFORMATION
            )

            val result = engine.processProactive(
                trigger = ProactiveTrigger.STREAK_RISK,
                relatedData = mapOf("days" to 5)
            )

            assertNotNull(result)
            assertEquals(ResponseType.INFORMATION, result.response.type)
        }
    }
}
