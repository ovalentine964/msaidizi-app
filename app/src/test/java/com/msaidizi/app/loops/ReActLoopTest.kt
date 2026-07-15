package com.msaidizi.app.loops

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for ReActLoop — Reasoning + Acting with explicit trace.
 *
 * Covers:
 * - Trace lifecycle (start → think → act → observe → reflect → complete)
 * - Step recording and phase correctness
 * - Trace history management and pruning
 * - Confidence thresholds
 * - Edge cases (empty strings, max history)
 */
@DisplayName("ReActLoop")
class ReActLoopTest {

    private lateinit var loop: ReActLoop

    @BeforeEach
    fun setUp() {
        loop = ReActLoop(maxTraceHistory = 5)
    }

    // ── Trace Lifecycle ──────────────────────────────────────────

    @Nested
    @DisplayName("Trace Lifecycle")
    inner class TraceLifecycleTests {

        @Test
        fun `startTrace creates a new trace with correct task`() {
            val trace = loop.startTrace("record_sale")

            assertEquals("record_sale", trace.task)
            assertTrue(trace.steps.isEmpty())
            assertNull(trace.endedAt)
            assertFalse(trace.success)
            assertNull(trace.finalResult)
        }

        @Test
        fun `startTrace sets current trace`() {
            val trace = loop.startTrace("test_task")

            assertEquals(trace, loop.getCurrentTrace())
        }

        @Test
        fun `complete marks trace as successful with result`() {
            val trace = loop.startTrace("record_sale")
            loop.think(trace, "Processing sale")
            loop.complete(trace, success = true, result = "Sale recorded")

            assertTrue(trace.success)
            assertEquals("Sale recorded", trace.finalResult)
            assertNotNull(trace.endedAt)
            assertNull(loop.getCurrentTrace())
        }

        @Test
        fun `complete marks trace as failed without result`() {
            val trace = loop.startTrace("record_sale")
            loop.complete(trace, success = false)

            assertFalse(trace.success)
            assertNull(trace.finalResult)
        }

        @Test
        fun `complete adds trace to history`() {
            val trace = loop.startTrace("test")
            loop.complete(trace, success = true)

            assertEquals(1, loop.getTraceCount())
        }

        @Test
        fun `full happy path lifecycle`() {
            val trace = loop.startTrace("process_sale")

            loop.think(trace, "Observing sale of mandazi x10 for KSh 500", confidence = 0.9)
            loop.act(trace, "record_sale", "Recording transaction")
            loop.observe(trace, "Transaction recorded with id=42", confidence = 0.95)
            loop.reflect(trace, "Sale recorded successfully, inventory updated")

            loop.complete(trace, success = true, result = "Sale #42 confirmed")

            assertEquals(4, trace.steps.size)
            assertTrue(trace.success)
            assertEquals("Sale #42 confirmed", trace.finalResult)
            assertEquals(1, loop.getTraceCount())
        }
    }

    // ── Step Recording ───────────────────────────────────────────

    @Nested
    @DisplayName("Step Recording")
    inner class StepRecordingTests {

        @Test
        fun `think creates THINK phase step`() {
            val trace = loop.startTrace("test")
            val step = loop.think(trace, "Analyzing the situation", confidence = 0.8)

            assertEquals(ReasoningStep.Phase.THINK, step.phase)
            assertEquals("Analyzing the situation", step.reasoning)
            assertEquals(0.8, step.confidence)
        }

        @Test
        fun `think records metadata`() {
            val trace = loop.startTrace("test")
            val metadata = mapOf("source" to "voice", "language" to "sw")
            val step = loop.think(trace, "Processing", metadata = metadata)

            assertEquals(metadata, step.metadata)
        }

        @Test
        fun `act creates ACT phase step`() {
            val trace = loop.startTrace("test")
            val step = loop.act(trace, "record_sale")

            assertEquals(ReasoningStep.Phase.ACT, step.phase)
            assertEquals("record_sale", step.action)
        }

        @Test
        fun `act with blank reasoning generates default reasoning`() {
            val trace = loop.startTrace("test")
            val step = loop.act(trace, "send_notification")

            assertEquals("Executing: send_notification", step.reasoning)
        }

        @Test
        fun `act with custom reasoning uses provided reasoning`() {
            val trace = loop.startTrace("test")
            val step = loop.act(trace, "notify", reasoning = "Sending WhatsApp notification")

            assertEquals("Sending WhatsApp notification", step.reasoning)
        }

        @Test
        fun `observe creates OBSERVE phase step`() {
            val trace = loop.startTrace("test")
            val step = loop.observe(trace, "Sale recorded successfully", confidence = 1.0)

            assertEquals(ReasoningStep.Phase.OBSERVE, step.phase)
            assertEquals("Sale recorded successfully", step.observation)
            assertEquals("Observed: Sale recorded successfully", step.reasoning)
        }

        @Test
        fun `reflect creates REFLECT phase step`() {
            val trace = loop.startTrace("test")
            val step = loop.reflect(trace, "Learned that mandazi sells well on Mondays", confidence = 0.7)

            assertEquals(ReasoningStep.Phase.REFLECT, step.phase)
            assertEquals("Learned that mandazi sells well on Mondays", step.reasoning)
        }

        @Test
        fun `multiple steps accumulate in trace`() {
            val trace = loop.startTrace("test")
            loop.think(trace, "Step 1")
            loop.act(trace, "action_1")
            loop.observe(trace, "Result 1")
            loop.reflect(trace, "Learning 1")

            assertEquals(4, trace.steps.size)
            assertEquals(
                listOf(
                    ReasoningStep.Phase.THINK,
                    ReasoningStep.Phase.ACT,
                    ReasoningStep.Phase.OBSERVE,
                    ReasoningStep.Phase.REFLECT
                ),
                trace.steps.map { it.phase }
            )
        }
    }

    // ── Reasoning Chain ──────────────────────────────────────────

    @Nested
    @DisplayName("Reasoning Chain")
    inner class ReasoningChainTests {

        @Test
        fun `getReasoningChain filters blank reasoning`() {
            val trace = loop.startTrace("test")
            loop.think(trace, "First thought")
            trace.addStep(ReasoningStep(phase = ReasoningStep.Phase.ACT, reasoning = ""))
            loop.reflect(trace, "Final reflection")

            val chain = trace.getReasoningChain()
            assertEquals(2, chain.size)
            assertEquals("First thought", chain[0])
            assertEquals("Final reflection", chain[1])
        }

        @Test
        fun `getReasoningChain returns empty for trace with no steps`() {
            val trace = loop.startTrace("test")
            assertTrue(trace.getReasoningChain().isEmpty())
        }
    }

    // ── Duration ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Duration Tracking")
    inner class DurationTests {

        @Test
        fun `durationMs returns positive value after completion`() {
            val trace = loop.startTrace("test")
            Thread.sleep(10)
            loop.complete(trace, success = true)

            assertTrue(trace.durationMs() >= 10)
        }

        @Test
        fun `durationMs uses current time if not completed`() {
            val trace = loop.startTrace("test")
            // Not completed — uses System.currentTimeMillis()
            val duration = trace.durationMs()
            assertTrue(duration >= 0)
        }
    }

    // ── History Management ───────────────────────────────────────

    @Nested
    @DisplayName("History Management")
    inner class HistoryTests {

        @Test
        fun `getRecentTraces returns traces in order`() {
            for (i in 1..3) {
                val trace = loop.startTrace("task_$i")
                loop.complete(trace, success = true)
            }

            val traces = loop.getRecentTraces(10)
            assertEquals(3, traces.size)
            assertEquals("task_1", traces[0]["task"])
            assertEquals("task_3", traces[2]["task"])
        }

        @Test
        fun `history prunes oldest when exceeding maxTraceHistory`() {
            // maxTraceHistory = 5
            for (i in 1..7) {
                val trace = loop.startTrace("task_$i")
                loop.complete(trace, success = true)
            }

            assertEquals(5, loop.getTraceCount())
            val traces = loop.getRecentTraces(10)
            assertEquals("task_3", traces[0]["task"]) // oldest kept
        }

        @Test
        fun `getRecentTraces limits results`() {
            for (i in 1..5) {
                val trace = loop.startTrace("task_$i")
                loop.complete(trace, success = true)
            }

            val traces = loop.getRecentTraces(2)
            assertEquals(2, traces.size)
        }

        @Test
        fun `getReasoningExamples returns only successful traces`() {
            val successTrace = loop.startTrace("good_task")
            loop.think(successTrace, "Good reasoning")
            loop.complete(successTrace, success = true)

            val failTrace = loop.startTrace("bad_task")
            loop.complete(failTrace, success = false)

            val examples = loop.getReasoningExamples(10)
            assertEquals(1, examples.size)
            assertEquals("good_task", examples[0]["task"])
        }

        @Test
        fun `getReasoningExamples limits results`() {
            for (i in 1..5) {
                val trace = loop.startTrace("task_$i")
                loop.complete(trace, success = true)
            }

            val examples = loop.getReasoningExamples(3)
            assertEquals(3, examples.size)
        }

        @Test
        fun `getTraceCount starts at zero`() {
            assertEquals(0, loop.getTraceCount())
        }
    }

    // ── Trace toMap ──────────────────────────────────────────────

    @Nested
    @DisplayName("Serialization")
    inner class SerializationTests {

        @Test
        fun `trace toMap contains all expected keys`() {
            val trace = loop.startTrace("test_task")
            loop.think(trace, "Thinking")
            loop.complete(trace, success = true, result = "Done")

            val map = trace.toMap()
            assertTrue(map.containsKey("traceId"))
            assertTrue(map.containsKey("task"))
            assertTrue(map.containsKey("steps"))
            assertTrue(map.containsKey("startedAt"))
            assertTrue(map.containsKey("endedAt"))
            assertTrue(map.containsKey("success"))
            assertTrue(map.containsKey("durationMs"))
            assertTrue(map.containsKey("stepCount"))
            assertTrue(map.containsKey("reasoningChain"))

            assertEquals("test_task", map["task"])
            assertEquals(true, map["success"])
            assertEquals(1, map["stepCount"])
        }

        @Test
        fun `step toMap contains all expected keys`() {
            val step = ReasoningStep(
                phase = ReasoningStep.Phase.THINK,
                reasoning = "Test reasoning",
                confidence = 0.85,
                metadata = mapOf("key" to "value")
            )

            val map = step.toMap()
            assertEquals("THINK", map["phase"])
            assertEquals("Test reasoning", map["reasoning"])
            assertEquals(0.85, map["confidence"])
        }
    }

    // ── Edge Cases ───────────────────────────────────────────────

    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCaseTests {

        @Test
        fun `empty string reasoning is accepted`() {
            val trace = loop.startTrace("test")
            val step = loop.think(trace, "")

            assertEquals("", step.reasoning)
        }

        @Test
        fun `very long reasoning is accepted`() {
            val trace = loop.startTrace("test")
            val longReasoning = "A".repeat(10_000)
            val step = loop.think(trace, longReasoning)

            assertEquals(longReasoning, step.reasoning)
        }

        @Test
        fun `confidence boundary values are accepted`() {
            val trace = loop.startTrace("test")
            val stepLow = loop.think(trace, "Low", confidence = 0.0)
            val stepHigh = loop.think(trace, "High", confidence = 1.0)

            assertEquals(0.0, stepLow.confidence)
            assertEquals(1.0, stepHigh.confidence)
        }

        @Test
        fun `complete without any steps`() {
            val trace = loop.startTrace("empty")
            loop.complete(trace, success = true)

            assertTrue(trace.success)
            assertEquals(0, trace.steps.size)
        }

        @Test
        fun `getCurrentTrace returns null when no active trace`() {
            assertNull(loop.getCurrentTrace())
        }

        @Test
        fun `multiple traces tracked independently`() {
            val trace1 = loop.startTrace("task_1")
            val trace2 = loop.startTrace("task_2")

            // trace2 is now current
            assertEquals(trace2, loop.getCurrentTrace())

            loop.complete(trace1, success = true)
            loop.complete(trace2, success = false)

            assertEquals(2, loop.getTraceCount())
        }
    }
}
