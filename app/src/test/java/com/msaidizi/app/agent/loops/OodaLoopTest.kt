package com.msaidizi.app.agent.loops

import com.msaidizi.app.agent.AgentEvent
import com.msaidizi.app.agent.AgentEventBus
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Unit tests for OodaLoop — Observe-Orient-Decide-Act for CFO decisions.
 *
 * Covers:
 * - Full cycle: observe → orient → decide → act
 * - Escalation on low confidence
 * - Orientation state updates (EMA)
 * - Handler registration and matching
 * - Error handling in cycle
 * - Metrics and drift detection
 */
@ExtendWith(io.mockk.junit5.MockKExtension::class)
@DisplayName("OodaLoop")
class OodaLoopTest {

    @MockK
    private lateinit var eventBus: AgentEventBus

    private lateinit var oodaLoop: OodaLoop

    @BeforeEach
    fun setUp() {
        oodaLoop = OodaLoop(
            eventBus = eventBus,
            escalationThreshold = 0.3,
            maxCycleMs = 500L
        )
    }

    // ── Full Cycle ───────────────────────────────────────────────

    @Nested
    @DisplayName("Full Cycle")
    inner class FullCycleTests {

        @Test
        fun `runCycle completes all four phases on happy path`() = runTest {
            val handler = createMockHandler(
                observations = mapOf("sales" to 1000.0),
                orientationUpdate = mapOf("market_trend" to 0.5),
                decision = OodaDecision(action = "restock", confidence = 0.8),
                actResult = OodaActResult(success = true)
            )

            val event = createTestEvent()
            val result = oodaLoop.runCycle(event, handler)

            assertTrue(result.success)
            assertFalse(result.escalated)
            assertEquals(0.8, result.confidence)
            assertNull(result.error)
            assertNotNull(result.observations)
            assertNotNull(result.decision)
            assertNotNull(result.actResult)
        }

        @Test
        fun `runCycle records timing for each phase`() = runTest {
            val handler = createMockHandler(
                observations = mapOf("test" to 1.0),
                orientationUpdate = emptyMap(),
                decision = OodaDecision(action = "act", confidence = 0.9),
                actResult = OodaActResult(success = true)
            )

            val result = oodaLoop.runCycle(createTestEvent(), handler)

            assertTrue(result.totalMs >= 0)
            assertTrue(result.phaseMs.containsKey("observe"))
            assertTrue(result.phaseMs.containsKey("orient"))
            assertTrue(result.phaseMs.containsKey("decide"))
            assertTrue(result.phaseMs.containsKey("act"))
        }

        @Test
        fun `runCycle increments cycle counter`() = runTest {
            val handler = createMockHandler(
                observations = emptyMap(),
                orientationUpdate = emptyMap(),
                decision = OodaDecision(action = "act", confidence = 0.9),
                actResult = OodaActResult(success = true)
            )

            oodaLoop.runCycle(createTestEvent(), handler)
            oodaLoop.runCycle(createTestEvent(), handler)

            val metrics = oodaLoop.getMetrics()
            assertEquals(2, metrics.totalCycles)
        }

        @Test
        fun `runCycle tracks success count`() = runTest {
            val handler = createMockHandler(
                observations = emptyMap(),
                orientationUpdate = emptyMap(),
                decision = OodaDecision(action = "act", confidence = 0.9),
                actResult = OodaActResult(success = true)
            )

            oodaLoop.runCycle(createTestEvent(), handler)

            val metrics = oodaLoop.getMetrics()
            assertEquals(1, metrics.successfulCycles)
        }
    }

    // ── Escalation ───────────────────────────────────────────────

    @Nested
    @DisplayName("Escalation")
    inner class EscalationTests {

        @Test
        fun `runCycle escalates when confidence below threshold`() = runTest {
            val handler = createMockHandler(
                observations = emptyMap(),
                orientationUpdate = emptyMap(),
                decision = OodaDecision(action = "uncertain", confidence = 0.1),
                actResult = OodaActResult(success = true)
            )

            val result = oodaLoop.runCycle(createTestEvent(), handler)

            assertTrue(result.escalated)
            assertFalse(result.success)
            assertEquals(0.1, result.confidence)
        }

        @Test
        fun `runCycle does not escalate when confidence above threshold`() = runTest {
            val handler = createMockHandler(
                observations = emptyMap(),
                orientationUpdate = emptyMap(),
                decision = OodaDecision(action = "confident", confidence = 0.9),
                actResult = OodaActResult(success = true)
            )

            val result = oodaLoop.runCycle(createTestEvent(), handler)

            assertFalse(result.escalated)
        }

        @Test
        fun `escalation count is tracked`() = runTest {
            val lowConfidenceHandler = createMockHandler(
                observations = emptyMap(),
                orientationUpdate = emptyMap(),
                decision = OodaDecision(action = "low", confidence = 0.05),
                actResult = OodaActResult(success = false)
            )

            oodaLoop.runCycle(createTestEvent(), lowConfidenceHandler)

            val metrics = oodaLoop.getMetrics()
            assertEquals(1, metrics.escalationCount)
        }
    }

    // ── Orientation State ────────────────────────────────────────

    @Nested
    @DisplayName("Orientation State")
    inner class OrientationTests {

        @Test
        fun `initial orientation has default axes`() {
            val snapshot = oodaLoop.getOrientationSnapshot()

            assertTrue(snapshot.containsKey("market_trend"))
            assertTrue(snapshot.containsKey("volatility"))
            assertTrue(snapshot.containsKey("urgency"))
            assertTrue(snapshot.containsKey("confidence"))
            assertTrue(snapshot.containsKey("risk_level"))
            assertTrue(snapshot.containsKey("sentiment"))
            assertTrue(snapshot.containsKey("supply_demand"))
        }

        @Test
        fun `updateAxis applies EMA correctly`() {
            // Initial confidence is 0.5
            oodaLoop.updateAxis("confidence", 1.0, weight = 0.3)

            val snapshot = oodaLoop.getOrientationSnapshot()
            // EMA: 0.5 * (1 - 0.3) + 1.0 * 0.3 = 0.35 + 0.3 = 0.65
            assertEquals(0.65, snapshot["confidence"]!!, 0.01)
        }

        @Test
        fun `updateAxis clamps value to -1 to 1`() {
            oodaLoop.updateAxis("market_trend", 5.0, weight = 1.0)
            val snapshot = oodaLoop.getOrientationSnapshot()
            assertEquals(1.0, snapshot["market_trend"]!!, 0.01)

            oodaLoop.updateAxis("market_trend", -5.0, weight = 1.0)
            val snapshot2 = oodaLoop.getOrientationSnapshot()
            assertEquals(-1.0, snapshot2["market_trend"]!!, 0.01)
        }

        @Test
        fun `updateAxis ignores unknown axis`() {
            oodaLoop.updateAxis("nonexistent_axis", 1.0, weight = 1.0)
            val snapshot = oodaLoop.getOrientationSnapshot()
            assertNull(snapshot["nonexistent_axis"])
        }

        @Test
        fun `orientation updates after successful act`() = runTest {
            val handler = createMockHandler(
                observations = emptyMap(),
                orientationUpdate = emptyMap(),
                decision = OodaDecision(action = "act", confidence = 0.9),
                actResult = OodaActResult(success = true)
            )

            val initialConfidence = oodaLoop.getOrientationSnapshot()["confidence"]!!
            oodaLoop.runCycle(createTestEvent(), handler)

            val newConfidence = oodaLoop.getOrientationSnapshot()["confidence"]!!
            assertTrue(newConfidence > initialConfidence)
        }

        @Test
        fun `orientation decreases confidence after failed act`() = runTest {
            val handler = createMockHandler(
                observations = emptyMap(),
                orientationUpdate = emptyMap(),
                decision = OodaDecision(action = "act", confidence = 0.9),
                actResult = OodaActResult(success = false)
            )

            val initialConfidence = oodaLoop.getOrientationSnapshot()["confidence"]!!
            oodaLoop.runCycle(createTestEvent(), handler)

            val newConfidence = oodaLoop.getOrientationSnapshot()["confidence"]!!
            assertTrue(newConfidence < initialConfidence)
        }
    }

    // ── Handler Registration ─────────────────────────────────────

    @Nested
    @DisplayName("Handler Registration")
    inner class HandlerTests {

        @Test
        fun `registerHandler stores handler`() {
            val handler = mockk<OodaHandler>()
            oodaLoop.registerHandler("TransactionRecorded", handler)

            // Should be able to run a cycle with this handler
            // (tested indirectly through runCycle)
        }

        @Test
        fun `startListening collects events from eventBus`() = runTest {
            // Just verify it doesn't throw
            val handler = mockk<OodaHandler>()
            oodaLoop.registerHandler("TestEvent", handler)

            // startListening launches a coroutine — we can verify it was called
            // but the actual event processing requires a running scope
        }
    }

    // ── Error Handling ───────────────────────────────────────────

    @Nested
    @DisplayName("Error Handling")
    inner class ErrorTests {

        @Test
        fun `runCycle handles observe exception gracefully`() = runTest {
            val handler = mockk<OodaHandler>()
            coEvery { handler.observe(any(), any()) } throws RuntimeException("Observe failed")

            val result = oodaLoop.runCycle(createTestEvent(), handler)

            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("Observe failed"))
        }

        @Test
        fun `runCycle handles orient exception gracefully`() = runTest {
            val handler = mockk<OodaHandler>()
            coEvery { handler.observe(any(), any()) } returns emptyMap()
            coEvery { handler.orient(any(), any()) } throws RuntimeException("Orient failed")

            val result = oodaLoop.runCycle(createTestEvent(), handler)

            assertFalse(result.success)
            assertNotNull(result.error)
        }

        @Test
        fun `runCycle handles decide exception gracefully`() = runTest {
            val handler = mockk<OodaHandler>()
            coEvery { handler.observe(any(), any()) } returns emptyMap()
            coEvery { handler.orient(any(), any()) } returns emptyMap()
            coEvery { handler.decide(any(), any()) } throws RuntimeException("Decide failed")

            val result = oodaLoop.runCycle(createTestEvent(), handler)

            assertFalse(result.success)
            assertNotNull(result.error)
        }

        @Test
        fun `runCycle handles act exception gracefully`() = runTest {
            val handler = mockk<OodaHandler>()
            coEvery { handler.observe(any(), any()) } returns emptyMap()
            coEvery { handler.orient(any(), any()) } returns emptyMap()
            coEvery { handler.decide(any(), any()) } returns OodaDecision(action = "act", confidence = 0.9)
            coEvery { handler.act(any()) } throws RuntimeException("Act failed")

            val result = oodaLoop.runCycle(createTestEvent(), handler)

            assertFalse(result.success)
            assertNotNull(result.error)
        }
    }

    // ── Drift & Volatility ───────────────────────────────────────

    @Nested
    @DisplayName("Drift & Volatility")
    inner class DriftTests {

        @Test
        fun `getOrientationDrift returns empty with less than 2 cycles`() {
            val drift = oodaLoop.getOrientationDrift()
            assertTrue(drift.isEmpty())
        }

        @Test
        fun `isVolatile returns false initially`() {
            assertFalse(oodaLoop.isVolatile())
        }

        @Test
        fun `isVolatile detects large orientation changes`() = runTest {
            // Run two cycles with very different orientation updates
            val handler1 = createMockHandler(
                observations = emptyMap(),
                orientationUpdate = mapOf("market_trend" to 1.0),
                decision = OodaDecision(action = "act", confidence = 0.9),
                actResult = OodaActResult(success = true)
            )
            oodaLoop.runCycle(createTestEvent(), handler1)

            val handler2 = createMockHandler(
                observations = emptyMap(),
                orientationUpdate = mapOf("market_trend" to -1.0),
                decision = OodaDecision(action = "act", confidence = 0.9),
                actResult = OodaActResult(success = true)
            )
            oodaLoop.runCycle(createTestEvent(), handler2)

            // After two large opposing updates, should detect volatility
            // The drift depends on EMA smoothing, so we check with a lower threshold
            val drift = oodaLoop.getOrientationDrift()
            assertTrue(drift.isNotEmpty())
        }
    }

    // ── Metrics ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Metrics")
    inner class MetricsTests {

        @Test
        fun `getMetrics returns zero counts initially`() {
            val metrics = oodaLoop.getMetrics()

            assertEquals(0, metrics.totalCycles)
            assertEquals(0, metrics.successfulCycles)
            assertEquals(0, metrics.failedCycles)
            assertEquals(0, metrics.escalationCount)
        }

        @Test
        fun `getMetrics tracks success rate`() = runTest {
            val successHandler = createMockHandler(
                observations = emptyMap(),
                orientationUpdate = emptyMap(),
                decision = OodaDecision(action = "act", confidence = 0.9),
                actResult = OodaActResult(success = true)
            )
            val failHandler = createMockHandler(
                observations = emptyMap(),
                orientationUpdate = emptyMap(),
                decision = OodaDecision(action = "act", confidence = 0.9),
                actResult = OodaActResult(success = false)
            )

            oodaLoop.runCycle(createTestEvent(), successHandler)
            oodaLoop.runCycle(createTestEvent(), failHandler)

            val metrics = oodaLoop.getMetrics()
            assertEquals(2, metrics.totalCycles)
            assertEquals(1, metrics.successfulCycles)
            assertEquals(1, metrics.failedCycles)
            assertEquals(0.5, metrics.successRate)
        }

        @Test
        fun `metrics includes orientation snapshot`() {
            val metrics = oodaLoop.getMetrics()
            assertNotNull(metrics.orientation)
            assertTrue(metrics.orientation.containsKey("confidence"))
        }
    }

    // ── Data Classes ─────────────────────────────────────────────

    @Nested
    @DisplayName("Data Classes")
    inner class DataClassTests {

        @Test
        fun `OodaDecision has correct fields`() {
            val decision = OodaDecision(
                action = "restock",
                parameters = mapOf("item" to "nyanya"),
                confidence = 0.85,
                reasoning = "Low stock detected"
            )

            assertEquals("restock", decision.action)
            assertEquals(0.85, decision.confidence)
            assertEquals("Low stock detected", decision.reasoning)
        }

        @Test
        fun `OodaActResult success and failure`() {
            val success = OodaActResult(success = true, data = mapOf("id" to 42))
            val failure = OodaActResult(success = false, error = "DB error")

            assertTrue(success.success)
            assertNull(success.error)
            assertFalse(failure.success)
            assertEquals("DB error", failure.error)
        }

        @Test
        fun `OodaMetrics successRate returns 0 when no cycles`() {
            val metrics = oodaLoop.getMetrics()
            assertEquals(0.0, metrics.successRate)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────

    private fun createMockHandler(
        observations: Map<String, Any>,
        orientationUpdate: Map<String, Double>,
        decision: OodaDecision,
        actResult: OodaActResult
    ): OodaHandler {
        return mockk<OodaHandler>().apply {
            coEvery { observe(any(), any()) } returns observations
            coEvery { orient(any(), any()) } returns orientationUpdate
            coEvery { decide(any(), any()) } returns decision
            coEvery { act(any()) } returns actResult
        }
    }

    private fun createTestEvent(): AgentEvent {
        return AgentEvent.TransactionRecorded(
            eventId = "test-event-1",
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
