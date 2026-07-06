package com.msaidizi.app.agent

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for AgentEventBus — the coroutine-based event bus.
 * Tests publishing, subscribing, history, and metrics.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("AgentEventBus")
class AgentEventBusTest {

    private lateinit var eventBus: AgentEventBus

    @BeforeEach
    fun setUp() {
        AgentEventBus.resetInstance()
        eventBus = AgentEventBus(historySize = 50)
    }

    // ── Publishing ──────────────────────────────────────────────

    @Nested
    @DisplayName("Publishing")
    inner class PublishingTests {

        @Test
        fun `publish returns true when event is emitted`() {
            val event = AgentEvent.AgentTaskStarted(
                eventId = "test-1",
                timestamp = System.currentTimeMillis(),
                source = "Test",
                taskType = "test",
                agentName = "TestAgent"
            )

            val result = eventBus.publish(event)

            assertTrue(result)
        }

        @Test
        fun `publish increments total published count`() {
            val event = AgentEvent.AgentTaskStarted(
                eventId = "test-1",
                timestamp = System.currentTimeMillis(),
                source = "Test",
                taskType = "test",
                agentName = "TestAgent"
            )

            eventBus.publish(event)
            eventBus.publish(event)

            val metrics = eventBus.getMetrics()
            assertEquals(2, metrics.totalPublished)
        }

        @Test
        fun `publish adds event to history`() {
            val event = AgentEvent.IntentClassified(
                eventId = "test-2",
                timestamp = System.currentTimeMillis(),
                source = "IntentRouter",
                intent = "SALE",
                confidence = 0.95,
                extractedData = emptyMap(),
                language = "sw",
                rawText = "test"
            )

            eventBus.publish(event)

            val history = eventBus.getRecentEvents(1)
            assertEquals(1, history.size)
            assertTrue(history[0] is AgentEvent.IntentClassified)
        }
    }

    // ── Subscribing ─────────────────────────────────────────────

    @Nested
    @DisplayName("Subscribing")
    inner class SubscribingTests {

        @Test
        fun `filtered subscription receives only matching events`() = runTest {
            val intentEvent = AgentEvent.IntentClassified(
                eventId = "intent-1",
                timestamp = System.currentTimeMillis(),
                source = "IntentRouter",
                intent = "SALE",
                confidence = 0.95,
                extractedData = emptyMap(),
                language = "sw",
                rawText = "test"
            )
            val taskEvent = AgentEvent.AgentTaskStarted(
                eventId = "task-1",
                timestamp = System.currentTimeMillis(),
                source = "Orchestrator",
                taskType = "test",
                agentName = "Test"
            )

            // Publish both events
            eventBus.publish(intentEvent)
            eventBus.publish(taskEvent)

            // Filter for IntentClassified only
            val filtered = eventBus.filterEvents<AgentEvent.IntentClassified>()
            val received = filtered.first()

            assertTrue(received is AgentEvent.IntentClassified)
            assertEquals("intent-1", received.eventId)
        }
    }

    // ── History ─────────────────────────────────────────────────

    @Nested
    @DisplayName("History")
    inner class HistoryTests {

        @Test
        fun `history respects max size`() {
            val smallBus = AgentEventBus(historySize = 3)

            repeat(5) { i ->
                smallBus.publish(AgentEvent.AgentTaskStarted(
                    eventId = "event-$i",
                    timestamp = System.currentTimeMillis(),
                    source = "Test",
                    taskType = "test",
                    agentName = "Test"
                ))
            }

            val history = smallBus.getRecentEvents()
            assertTrue(history.size <= 3, "History should be capped at 3")
        }

        @Test
        fun `clearHistory removes all events`() {
            eventBus.publish(AgentEvent.AgentTaskStarted(
                eventId = "test",
                timestamp = System.currentTimeMillis(),
                source = "Test",
                taskType = "test",
                agentName = "Test"
            ))

            eventBus.clearHistory()

            val history = eventBus.getRecentEvents()
            assertTrue(history.isEmpty())
        }

        @Test
        fun `getRecentEvents returns newest first`() {
            repeat(3) { i ->
                eventBus.publish(AgentEvent.AgentTaskStarted(
                    eventId = "event-$i",
                    timestamp = System.currentTimeMillis() + i,
                    source = "Test",
                    taskType = "test",
                    agentName = "Test"
                ))
            }

            val history = eventBus.getRecentEvents(3)
            assertEquals("event-2", history[0].eventId)
            assertEquals("event-0", history[2].eventId)
        }
    }

    // ── Metrics ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Metrics")
    inner class MetricsTests {

        @Test
        fun `metrics track published and dropped events`() {
            eventBus.publish(AgentEvent.AgentTaskStarted(
                eventId = "test",
                timestamp = System.currentTimeMillis(),
                source = "Test",
                taskType = "test",
                agentName = "Test"
            ))

            val metrics = eventBus.getMetrics()
            assertEquals(1, metrics.totalPublished)
            assertEquals(0, metrics.totalDropped)
            assertEquals(1, metrics.historySize)
        }

        @Test
        fun `resetMetrics clears all counters`() {
            eventBus.publish(AgentEvent.AgentTaskStarted(
                eventId = "test",
                timestamp = System.currentTimeMillis(),
                source = "Test",
                taskType = "test",
                agentName = "Test"
            ))

            eventBus.resetMetrics()

            val metrics = eventBus.getMetrics()
            assertEquals(0, metrics.totalPublished)
            assertEquals(0, metrics.totalDropped)
        }
    }

    // ── Singleton ───────────────────────────────────────────────

    @Nested
    @DisplayName("Singleton")
    inner class SingletonTests {

        @Test
        fun `getInstance returns same instance`() {
            val instance1 = AgentEventBus.getInstance()
            val instance2 = AgentEventBus.getInstance()

            assertSame(instance1, instance2)
        }

        @Test
        fun `resetInstance creates new instance`() {
            val instance1 = AgentEventBus.getInstance()
            AgentEventBus.resetInstance()
            val instance2 = AgentEventBus.getInstance()

            assertNotSame(instance1, instance2)
        }
    }
}
