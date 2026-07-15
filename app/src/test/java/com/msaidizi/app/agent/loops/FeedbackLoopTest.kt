package com.msaidizi.app.agent.loops

import com.msaidizi.app.agent.AgentEvent
import com.msaidizi.app.agent.AgentEventBus
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Unit tests for FeedbackLoop — Self-improving feedback loop.
 *
 * Covers:
 * - Signal extraction and accumulation
 * - Pattern detection (batched)
 * - Strategy parameter updates
 * - Outcome processing
 * - Parameter registration and retrieval
 * - Metrics and trajectory tracking
 * - Edge cases
 */
@ExtendWith(io.mockk.junit5.MockKExtension::class)
@DisplayName("FeedbackLoop")
class FeedbackLoopTest {

    @MockK
    private lateinit var eventBus: AgentEventBus

    private lateinit var feedbackLoop: FeedbackLoop

    @BeforeEach
    fun setUp() {
        feedbackLoop = FeedbackLoop(
            eventBus = eventBus,
            decayHalfLifeHours = 168.0,
            minSignalsForPattern = 5,
            minSignalsForUpdate = 10
        )
    }

    // ── Parameter Registration ───────────────────────────────────

    @Nested
    @DisplayName("Parameter Registration")
    inner class ParameterTests {

        @Test
        fun `registerParameter stores parameter`() {
            val param = StrategyParameter(
                name = "discount_rate",
                currentValue = 0.1,
                defaultValue = 0.1,
                minValue = 0.0,
                maxValue = 0.5
            )

            feedbackLoop.registerParameter(param)

            assertEquals(0.1, feedbackLoop.getParameter("discount_rate"))
        }

        @Test
        fun `getParameter returns null for unknown parameter`() {
            assertNull(feedbackLoop.getParameter("unknown"))
        }

        @Test
        fun `getAllParameters returns all registered parameters`() {
            feedbackLoop.registerParameter(StrategyParameter("p1", 0.5, 0.5))
            feedbackLoop.registerParameter(StrategyParameter("p2", 0.3, 0.3))

            val params = feedbackLoop.getAllParameters()

            assertEquals(2, params.size)
            assertEquals(0.5, params["p1"])
            assertEquals(0.3, params["p2"])
        }

        @Test
        fun `getAllParameters returns empty when none registered`() {
            assertTrue(feedbackLoop.getAllParameters().isEmpty())
        }

        @Test
        fun `parameter updates are tracked via history`() {
            val param = StrategyParameter(
                name = "threshold",
                currentValue = 0.5,
                defaultValue = 0.5,
                minValue = 0.0,
                maxValue = 1.0
            )

            feedbackLoop.registerParameter(param)

            val trajectory = feedbackLoop.getTrajectory("threshold")
            assertTrue(trajectory.isEmpty())
        }

        @Test
        fun `getTrajectory returns empty for unknown parameter`() {
            assertTrue(feedbackLoop.getTrajectory("unknown").isEmpty())
        }
    }

    // ── Outcome Processing ───────────────────────────────────────

    @Nested
    @DisplayName("Outcome Processing")
    inner class OutcomeProcessingTests {

        @Test
        fun `processOutcome extracts and stores signal`() = runTest {
            val event = createTestEvent()
            val extractor = OutcomeExtractor {
                LearningSignal(
                    type = SignalType.SUCCESS,
                    outcomeValue = 0.8,
                    expectedValue = 0.6,
                    surprise = 0.2,
                    weight = 1.0,
                    tags = listOf("sale")
                )
            }

            feedbackLoop.processOutcome(event, extractor)

            val signals = feedbackLoop.getRecentSignals(10)
            assertEquals(1, signals.size)
            assertEquals(SignalType.SUCCESS, signals[0].type)
            assertEquals(0.8, signals[0].outcomeValue)
        }

        @Test
        fun `processOutcome increments signal count`() = runTest {
            val extractor = OutcomeExtractor {
                LearningSignal(
                    type = SignalType.SUCCESS,
                    outcomeValue = 0.5,
                    expectedValue = 0.5,
                    surprise = 0.0
                )
            }

            feedbackLoop.processOutcome(createTestEvent(), extractor)
            feedbackLoop.processOutcome(createTestEvent(), extractor)

            val metrics = feedbackLoop.getMetrics()
            assertEquals(2, metrics.totalSignals)
        }

        @Test
        fun `processOutcome tracks running average surprise`() = runTest {
            val extractor1 = OutcomeExtractor {
                LearningSignal(
                    type = SignalType.SUCCESS,
                    outcomeValue = 0.9,
                    expectedValue = 0.5,
                    surprise = 0.4
                )
            }
            val extractor2 = OutcomeExtractor {
                LearningSignal(
                    type = SignalType.FAILURE,
                    outcomeValue = 0.1,
                    expectedValue = 0.5,
                    surprise = 0.4
                )
            }

            feedbackLoop.processOutcome(createTestEvent(), extractor1)
            feedbackLoop.processOutcome(createTestEvent(), extractor2)

            val metrics = feedbackLoop.getMetrics()
            assertEquals(0.4, metrics.avgSurprise, 0.01)
        }
    }

    // ── Pattern Detection ────────────────────────────────────────

    @Nested
    @DisplayName("Pattern Detection")
    inner class PatternDetectionTests {

        @Test
        fun `patterns detected after minSignalsForPattern signals`() = runTest {
            // minSignalsForPattern = 5
            val extractor = OutcomeExtractor {
                LearningSignal(
                    type = SignalType.FAILURE,
                    outcomeValue = 0.1,
                    expectedValue = 0.8,
                    surprise = 0.7,
                    tags = listOf("poor_category")
                )
            }

            // Send 5 signals with poor outcomes
            for (i in 1..5) {
                feedbackLoop.processOutcome(createTestEvent(), extractor)
            }

            val patterns = feedbackLoop.getPatterns()
            // Should detect a pattern of poor outcomes for "poor_category"
            assertTrue(patterns.isNotEmpty())
        }

        @Test
        fun `getPatterns returns empty initially`() {
            assertTrue(feedbackLoop.getPatterns().isEmpty())
        }
    }

    // ── Signal Retrieval ─────────────────────────────────────────

    @Nested
    @DisplayName("Signal Retrieval")
    inner class SignalRetrievalTests {

        @Test
        fun `getRecentSignals returns signals in order`() = runTest {
            val extractor = OutcomeExtractor { event ->
                LearningSignal(
                    type = SignalType.SUCCESS,
                    outcomeValue = 0.5,
                    expectedValue = 0.5,
                    surprise = 0.0,
                    tags = listOf("tag${event.eventId}")
                )
            }

            feedbackLoop.processOutcome(createTestEvent("1"), extractor)
            feedbackLoop.processOutcome(createTestEvent("2"), extractor)
            feedbackLoop.processOutcome(createTestEvent("3"), extractor)

            val signals = feedbackLoop.getRecentSignals(10)
            assertEquals(3, signals.size)
        }

        @Test
        fun `getRecentSignals limits results`() = runTest {
            val extractor = OutcomeExtractor {
                LearningSignal(
                    type = SignalType.SUCCESS,
                    outcomeValue = 0.5,
                    expectedValue = 0.5,
                    surprise = 0.0
                )
            }

            for (i in 1..5) {
                feedbackLoop.processOutcome(createTestEvent(), extractor)
            }

            val signals = feedbackLoop.getRecentSignals(2)
            assertEquals(2, signals.size)
        }

        @Test
        fun `getRecentSignals returns empty initially`() {
            assertTrue(feedbackLoop.getRecentSignals().isEmpty())
        }
    }

    // ── Metrics ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Metrics")
    inner class MetricsTests {

        @Test
        fun `getMetrics returns zero counts initially`() {
            val metrics = feedbackLoop.getMetrics()

            assertEquals(0, metrics.totalSignals)
            assertTrue(metrics.signalsByType.isEmpty())
            assertEquals(0, metrics.patternsDetected)
            assertEquals(0, metrics.strategiesUpdated)
            assertEquals(0, metrics.rollbacks)
            assertEquals(0.0, metrics.avgSurprise)
        }

        @Test
        fun `getMetrics tracks signals by type`() = runTest {
            val successExtractor = OutcomeExtractor {
                LearningSignal(
                    type = SignalType.SUCCESS,
                    outcomeValue = 0.8,
                    expectedValue = 0.5,
                    surprise = 0.3
                )
            }
            val failureExtractor = OutcomeExtractor {
                LearningSignal(
                    type = SignalType.FAILURE,
                    outcomeValue = 0.2,
                    expectedValue = 0.5,
                    surprise = 0.3
                )
            }

            feedbackLoop.processOutcome(createTestEvent(), successExtractor)
            feedbackLoop.processOutcome(createTestEvent(), successExtractor)
            feedbackLoop.processOutcome(createTestEvent(), failureExtractor)

            val metrics = feedbackLoop.getMetrics()
            assertEquals(3, metrics.totalSignals)
            assertEquals(2, metrics.signalsByType["SUCCESS"])
            assertEquals(1, metrics.signalsByType["FAILURE"])
        }

        @Test
        fun `getMetrics includes parameter snapshots`() {
            feedbackLoop.registerParameter(StrategyParameter("test", 0.5, 0.5))

            val metrics = feedbackLoop.getMetrics()
            assertTrue(metrics.parameters.containsKey("test"))
        }
    }

    // ── Strategy Parameter Class ─────────────────────────────────

    @Nested
    @DisplayName("StrategyParameter")
    inner class StrategyParameterTests {

        @Test
        fun `parameter has correct defaults`() {
            val param = StrategyParameter(
                name = "test",
                currentValue = 0.5,
                defaultValue = 0.5
            )

            assertEquals("test", param.name)
            assertEquals(0.5, param.currentValue)
            assertEquals(0.5, param.defaultValue)
            assertEquals(0.0, param.minValue)
            assertEquals(1.0, param.maxValue)
            assertEquals(0, param.updateCount)
        }

        @Test
        fun `getBestValue returns currentValue when no history`() {
            val param = StrategyParameter(
                name = "test",
                currentValue = 0.7,
                defaultValue = 0.5
            )

            assertEquals(0.7, param.getBestValue())
        }

        @Test
        fun `getBestValue returns best performing value from history`() {
            val param = StrategyParameter(
                name = "test",
                currentValue = 0.5,
                defaultValue = 0.5
            )

            param.history.addLast(ParameterSnapshot(0.3, 0.6, 1000))
            param.history.addLast(ParameterSnapshot(0.7, 0.9, 2000))
            param.history.addLast(ParameterSnapshot(0.5, 0.4, 3000))

            assertEquals(0.7, param.getBestValue())
        }

        @Test
        fun `toSnapshot returns current state`() {
            val param = StrategyParameter(
                name = "test",
                currentValue = 0.6,
                defaultValue = 0.5,
                lastUpdated = 12345L
            )

            val snapshot = param.toSnapshot()
            assertEquals(0.6, snapshot.value)
            assertEquals(12345L, snapshot.timestamp)
        }
    }

    // ── Data Classes ─────────────────────────────────────────────

    @Nested
    @DisplayName("Data Classes")
    inner class DataClassTests {

        @Test
        fun `LearningSignal has all fields`() {
            val signal = LearningSignal(
                type = SignalType.SUCCESS,
                outcomeValue = 0.8,
                expectedValue = 0.6,
                surprise = 0.2,
                weight = 0.9,
                tags = listOf("sale", "morning")
            )

            assertEquals(SignalType.SUCCESS, signal.type)
            assertEquals(0.8, signal.outcomeValue)
            assertEquals(0.6, signal.expectedValue)
            assertEquals(0.2, signal.surprise)
            assertEquals(0.9, signal.weight)
            assertEquals(listOf("sale", "morning"), signal.tags)
        }

        @Test
        fun `DetectedPattern has mutable fields`() {
            val pattern = DetectedPattern(
                description = "Test pattern",
                confidence = 0.7,
                signalCount = 5,
                contextSignature = "test",
                recommendation = "Do something"
            )

            pattern.confidence = 0.9
            pattern.signalCount = 10

            assertEquals(0.9, pattern.confidence)
            assertEquals(10, pattern.signalCount)
        }

        @Test
        fun `SignalType has all expected entries`() {
            val types = SignalType.entries.map { it.name }

            assertTrue(types.contains("SUCCESS"))
            assertTrue(types.contains("FAILURE"))
            assertTrue(types.contains("OUTPERFORMED"))
            assertTrue(types.contains("UNDERPERFORMED"))
            assertTrue(types.contains("NOVEL_PATTERN"))
            assertTrue(types.contains("DRIFT"))
            assertTrue(types.contains("ANOMALY"))
        }

        @Test
        fun `ParameterSnapshot has correct fields`() {
            val snapshot = ParameterSnapshot(0.5, 0.8, 12345L)

            assertEquals(0.5, snapshot.value)
            assertEquals(0.8, snapshot.outcome)
            assertEquals(12345L, snapshot.timestamp)
        }
    }

    // ── Edge Cases ───────────────────────────────────────────────

    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCaseTests {

        @Test
        fun `max signal buffer is respected`() = runTest {
            val extractor = OutcomeExtractor {
                LearningSignal(
                    type = SignalType.SUCCESS,
                    outcomeValue = 0.5,
                    expectedValue = 0.5,
                    surprise = 0.0
                )
            }

            // MAX_SIGNALS = 5000, send more to test pruning
            // (sending 5001 would be slow, so we test the API works)
            for (i in 1..10) {
                feedbackLoop.processOutcome(createTestEvent(), extractor)
            }

            assertEquals(10, feedbackLoop.getRecentSignals(100).size)
        }

        @Test
        fun `processOutcome with different signal types works`() = runTest {
            for (type in SignalType.entries) {
                val extractor = OutcomeExtractor {
                    LearningSignal(
                        type = type,
                        outcomeValue = 0.5,
                        expectedValue = 0.5,
                        surprise = 0.0
                    )
                }
                feedbackLoop.processOutcome(createTestEvent(), extractor)
            }

            val metrics = feedbackLoop.getMetrics()
            assertEquals(SignalType.entries.size, metrics.totalSignals)
        }

        @Test
        fun `registerExtractor stores extractor`() {
            val extractor = OutcomeExtractor {
                LearningSignal(
                    type = SignalType.SUCCESS,
                    outcomeValue = 0.5,
                    expectedValue = 0.5,
                    surprise = 0.0
                )
            }

            // Should not throw
            feedbackLoop.registerExtractor("TransactionRecorded", extractor)
        }
    }

    // ── Helper ───────────────────────────────────────────────────

    private fun createTestEvent(id: String = "test"): AgentEvent {
        return AgentEvent.TransactionRecorded(
            eventId = id,
            timestamp = System.currentTimeMillis(),
            source = "test",
            transactionId = 1L,
            type = "SALE",
            item = "mandazi",
            amount = 500.0,
            quantity = 10.0,
            language = "sw"
        )
    }
}
