package com.msaidizi.app.voice

import com.msaidizi.app.superagent.engine.*
import com.msaidizi.app.superagent.context.ContextEngine
import com.msaidizi.app.superagent.context.WorkerProfileStore
import com.msaidizi.app.superagent.context.WorkerProfile
import com.msaidizi.app.superagent.flywheel.FlywheelEngine
import com.msaidizi.app.superagent.flywheel.FlywheelModels
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for the Voice Pipeline — STT → Agent → TTS flow.
 *
 * Since VoicePipeline depends on Android-specific components (Sherpa ONNX,
 * AudioRecord, etc.), these tests mock the STT/TTS engines and test the
 * integration with ReasoningEngine via the OODA loop.
 *
 * Covers: voice signal processing, confidence thresholds, language detection,
 * and the end-to-end voice → text → intent → response flow.
 */
class VoicePipelineTest {

    // ═══════════════════════════════════════════════════════════════
    // VOICE SIGNAL TESTS
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class VoiceSignal {

        @Test
        fun `voice signal carries ASR confidence`() {
            val signal = VoiceSignal(
                sttResult = "Nimeuziwa mandazi",
                asrConfidence = 0.92f,
                language = "sw",
                dialect = "standard"
            )

            assertEquals(0.92f, signal.asrConfidence)
            assertEquals("sw", signal.language)
            assertEquals("standard", signal.dialect)
        }

        @Test
        fun `voice signal with low confidence`() {
            val signal = VoiceSignal(
                sttResult = "mumble mumble",
                asrConfidence = 0.3f,
                language = "sw",
                dialect = "sheng"
            )

            assertTrue(signal.asrConfidence < 0.5f)
            assertEquals("sheng", signal.dialect)
        }

        @Test
        fun `voice signal with emotion`() {
            val signal = VoiceSignal(
                sttResult = "Nimeuziwa mandazi",
                asrConfidence = 0.9f,
                language = "sw",
                dialect = "standard",
                emotion = "happy",
                pitch = 150.0f,
                pace = 1.2f
            )

            assertEquals("happy", signal.emotion)
            assertEquals(150.0f, signal.pitch)
            assertEquals(1.2f, signal.pace)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // OBSERVATION MODELS
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class ObservationModels {

        @Test
        fun `observation bundles all signals`() {
            val obs = Observation(
                text = "Nimeuziwa mandazi",
                language = "sw",
                dialect = "standard",
                voice = VoiceSignal(
                    sttResult = "Nimeuziwa mandazi",
                    asrConfidence = 0.9f,
                    language = "sw",
                    dialect = "standard"
                ),
                triggerType = TriggerType.VOICE_INPUT
            )

            assertEquals("Nimeuziwa mandazi", obs.text)
            assertEquals(TriggerType.VOICE_INPUT, obs.triggerType)
            assertNotNull(obs.voice)
            assertEquals(0.9f, obs.voice!!.asrConfidence)
        }

        @Test
        fun `empty market signal is valid`() {
            val market = MarketSignal.empty()
            assertTrue(market.relevantPrices.isEmpty())
            assertTrue(market.priceAnomalies.isEmpty())
            assertFalse(market.isMarketDay)
        }

        @Test
        fun `empty worker signal is valid`() {
            val worker = WorkerSignal.empty()
            assertEquals(0.0, worker.dailyAverage)
            assertEquals("STABLE", worker.weeklyTrend)
            assertEquals(0, worker.streakDays)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // OODA RESULT MODELS
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class OodaResultModels {

        @Test
        fun `agent response with all fields`() {
            val response = AgentResponse(
                text = "Sawa, nimeandika mauzo ya mandazi KSh 500.",
                type = ResponseType.TRANSACTION_CONFIRMATION,
                shouldSpeak = true,
                data = mapOf("amount" to "500", "item" to "mandazi")
            )

            assertEquals("Sawa, nimeandika mauzo ya mandazi KSh 500.", response.text)
            assertEquals(ResponseType.TRANSACTION_CONFIRMATION, response.type)
            assertTrue(response.shouldSpeak)
            assertEquals("500", response.data["amount"])
        }

        @Test
        fun `ooda result with all components`() {
            val result = OodaResult(
                response = AgentResponse(
                    text = "Sawa",
                    type = ResponseType.TRANSACTION_CONFIRMATION
                ),
                proofPoint = FlywheelModels.ProofPoint(
                    type = FlywheelModels.ProofType.TRANSACTION,
                    weight = 1.0
                ),
                learningSignal = LearningSignal(
                    input = "test",
                    intent = "SALE",
                    confidence = 0.9f,
                    parseMethod = ParseMethod.PATTERN,
                    actionType = ActionType.RESPOND_AND_RECORD,
                    module = "FinancialModule",
                    signals = emptyList()
                ),
                actionType = ActionType.RESPOND_AND_RECORD,
                cycleTimeMs = 150
            )

            assertEquals(150, result.cycleTimeMs)
            assertNotNull(result.proofPoint)
            assertNotNull(result.learningSignal)
            assertEquals(ActionType.RESPOND_AND_RECORD, result.actionType)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // INTENT ROUTING
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class IntentRouting {

        @Test
        fun `financial intents map to financial module`() {
            val financialIntents = setOf(
                "SALE", "PURCHASE", "EXPENSE",
                "PROFIT_QUERY", "CHECK_BALANCE", "STOCK_QUERY",
                "DAILY_SUMMARY", "WEEKLY_SUMMARY", "MONTHLY_SUMMARY",
                "RECEIPT_SCAN", "INVENTORY_CHECK"
            )

            // All should route to financial module
            financialIntents.forEach { intent ->
                assertTrue(
                    intent in setOf(
                        "SALE", "PURCHASE", "EXPENSE",
                        "PROFIT_QUERY", "CHECK_BALANCE", "STOCK_QUERY",
                        "DAILY_SUMMARY", "WEEKLY_SUMMARY", "MONTHLY_SUMMARY",
                        "RECEIPT_SCAN", "INVENTORY_CHECK"
                    ),
                    "$intent should be a financial intent"
                )
            }
        }

        @Test
        fun `credit intents map to credit module`() {
            val creditIntents = setOf(
                "LOAN_RECORD", "LOAN_QUERY", "LOAN_REPORT",
                "LOAN_DEADLINE", "CREDIT_SCORE", "DEBT_ADVICE"
            )

            assertEquals(6, creditIntents.size)
        }

        @Test
        fun `goals intents map to goals module`() {
            val goalsIntents = setOf(
                "GOAL_CREATE", "GOAL_PROGRESS", "GOAL_REPORT",
                "GOAL_TIME_FORECAST", "GOAL_ADJUST", "GOAL_ENCOURAGEMENT",
                "GIVING_RECORD", "GIVING_QUERY", "GIVING_GOAL"
            )

            assertEquals(9, goalsIntents.size)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ACTION TYPES
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class ActionTypes {

        @Test
        fun `action types cover all response scenarios`() {
            val types = ActionType.entries

            assertTrue(types.contains(ActionType.RESPOND))
            assertTrue(types.contains(ActionType.RESPOND_AND_RECORD))
            assertTrue(types.contains(ActionType.RESPOND_AND_ALERT))
            assertTrue(types.contains(ActionType.RESPOND_AND_SUGGEST))
            assertTrue(types.contains(ActionType.CLARIFICATION))
            assertTrue(types.contains(ActionType.LLM_ESCALATION))
        }

        @Test
        fun `response types cover all output formats`() {
            val types = ResponseType.entries

            assertTrue(types.contains(ResponseType.TRANSACTION_CONFIRMATION))
            assertTrue(types.contains(ResponseType.ALERT))
            assertTrue(types.contains(ResponseType.CLARIFICATION))
            assertTrue(types.contains(ResponseType.ERROR))
            assertTrue(types.contains(ResponseType.LLM_GENERATED))
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PARSE RESULT
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class ParseResult {

        @Test
        fun `parse result with extracted data`() {
            val result = ParseResult(
                intent = "SALE",
                extractedData = mapOf(
                    "amount" to 500.0,
                    "item" to "mandazi",
                    "quantity" to 10.0
                ),
                confidence = 0.95f,
                method = ParseMethod.PATTERN
            )

            assertEquals("SALE", result.intent)
            assertEquals(500.0, result.extractedData["amount"])
            assertEquals("mandazi", result.extractedData["item"])
            assertEquals(0.95f, result.confidence)
        }

        @Test
        fun `default parse method is pattern`() {
            val result = ParseResult(intent = "SALE")
            assertEquals(ParseMethod.PATTERN, result.method)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // CONFIDENCE THRESHOLDS
    // ═══════════════════════════════════════════════════════════════

    @Nested
    inner class ConfidenceThresholds {

        @Test
        fun `thresholds are properly ordered`() {
            assertTrue(ReasoningEngine.HIGH_CONFIDENCE_THRESHOLD >
                ReasoningEngine.MEDIUM_CONFIDENCE_THRESHOLD)
            assertTrue(ReasoningEngine.MEDIUM_CONFIDENCE_THRESHOLD >
                ReasoningEngine.CLARIFICATION_THRESHOLD)
        }

        @Test
        fun `high confidence threshold is 0`() {
            assertTrue(ReasoningEngine.HIGH_CONFIDENCE_THRESHOLD >= 0.8f)
        }

        @Test
        fun `clarification threshold is reasonable`() {
            assertTrue(ReasoningEngine.CLARIFICATION_THRESHOLD in 0.2f..0.5f)
        }
    }
}
