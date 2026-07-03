package com.msaidizi.app.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight coroutine-based event bus for agent coordination.
 *
 * Uses Kotlin SharedFlow for efficient pub/sub without external
 * dependencies. Events are dispatched on a shared coroutine scope
 * to avoid blocking the main thread.
 *
 * Features:
 * - Type-safe event subscription via sealed classes
 * - Replay capability for late subscribers
 * - Event history for debugging
 * - Filtered subscriptions (by event type or predicate)
 * - Thread-safe with minimal overhead
 *
 * Architecture:
 *   ┌──────────┐  publish()  ┌──────────────┐  emit   ┌──────────────┐
 *   │ Publisher │────────────▶│ AgentEventBus │────────▶│  Subscriber  │
 *   │  Agent    │             │ (SharedFlow)  │         │   Agent      │
 *   └──────────┘             └──────────────┘         └──────────────┘
 *                                  │
 *                                  │ history
 *                                  ▼
 *                            ┌──────────────┐
 *                            │ Event History │
 *                            │ (ring buffer) │
 *                            └──────────────┘
 *
 * Usage:
 *   // Subscribe to all events
 *   eventBus.events.collect { event ->
 *       when (event) {
 *           is AgentEvent.TransactionRecorded -> handleTransaction(event)
 *           is AgentEvent.IntentClassified -> handleIntent(event)
 *           // ... exhaustive when()
 *       }
 *   }
 *
 *   // Subscribe to specific event type
 *   eventBus.filterEvents<AgentEvent.TransactionRecorded>().collect { ... }
 *
 *   // Publish
 *   eventBus.publish(AgentEvent.TransactionRecorded(...))
 */
class AgentEventBus(
    private val historySize: Int = DEFAULT_HISTORY_SIZE,
) {
    // ── Internal state ────────────────────────────────────────────

    private val _events = MutableSharedFlow<AgentEvent>(
        replay = 1,                          // Replay last event for late subscribers
        extraBufferCapacity = 64,            // Buffer to avoid suspending publishers
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Public read-only event stream. */
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    /** Event history ring buffer for debugging. */
    internal val _history = ArrayDeque<AgentEvent>(historySize)

    /** Per-type subscriber count for metrics. */
    internal val _subscriberCounts = ConcurrentHashMap<String, AtomicLong>()

    /** Total events published. */
    private val _totalPublished = AtomicLong(0)

    /** Total events dropped (buffer overflow). */
    private val _totalDropped = AtomicLong(0)

    /** Coroutine scope for async event dispatch. */
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Publishing ────────────────────────────────────────────────

    /**
     * Publish an event to all subscribers.
     *
     * This is a non-blocking operation. If the buffer is full,
     * the oldest event is dropped (DROP_OLDEST policy).
     *
     * @param event The event to publish
     * @return true if the event was emitted to the buffer
     */
    fun publish(event: AgentEvent): Boolean {
        val emitted = _events.tryEmit(event)

        if (emitted) {
            _totalPublished.incrementAndGet()

            // Add to history (thread-safe via synchronized)
            synchronized(_history) {
                if (_history.size >= historySize) {
                    _history.removeFirst()
                }
                _history.addLast(event)
            }

            Timber.d("Event published: %s from %s", event::class.simpleName, event.source)
        } else {
            _totalDropped.incrementAndGet()
            Timber.w("Event dropped (buffer full): %s", event::class.simpleName)
        }

        return emitted
    }

    /**
     * Publish an event asynchronously from a coroutine.
     */
    suspend fun publishAsync(event: AgentEvent) {
        _events.emit(event)
        _totalPublished.incrementAndGet()

        synchronized(_history) {
            if (_history.size >= historySize) {
                _history.removeFirst()
            }
            _history.addLast(event)
        }

        Timber.d("Event async published: %s from %s", event::class.simpleName, event.source)
    }

    // ── Subscribing ───────────────────────────────────────────────

    /**
     * Subscribe to events of a specific type.
     *
     * Returns a SharedFlow that only emits events of type [T].
     *
     * Example:
     *   eventBus.filterEvents<AgentEvent.TransactionRecorded>().collect { txn ->
     *       // Only TransactionRecorded events
     *   }
     */
    inline fun <reified T : AgentEvent> filterEvents(): SharedFlow<T> {
        val typeName = T::class.simpleName ?: "Unknown"
        _subscriberCounts.computeIfAbsent(typeName) { AtomicLong(0) }.incrementAndGet()

        @Suppress("UNCHECKED_CAST")
        return events.filterIsInstance<T>() as SharedFlow<T>
    }

    /**
     * Subscribe to events matching a predicate.
     *
     * Example:
     *   eventBus.filterEvents { it.source == "BusinessAgent" }.collect { ... }
     */
    fun filterEvents(predicate: (AgentEvent) -> Boolean): SharedFlow<AgentEvent> {
        return events.filter(predicate) as SharedFlow<AgentEvent>
    }

    /**
     * Subscribe to events in a new coroutine.
     *
     * Convenience method that launches a coroutine to collect events.
     * The collector runs on Dispatchers.Default.
     *
     * @param collector The suspend function to handle each event
     * @return The launched Job (can be cancelled to unsubscribe)
     */
    fun subscribe(
        collector: suspend (AgentEvent) -> Unit,
    ) = scope.launch {
        events.collect { event ->
            try {
                collector(event)
            } catch (e: Exception) {
                Timber.e(e, "Error in event subscriber for %s", event::class.simpleName)
            }
        }
    }

    /**
     * Subscribe to a specific event type in a new coroutine.
     *
     * @param collector The suspend function to handle each event of type [T]
     * @return The launched Job
     */
    inline fun <reified T : AgentEvent> subscribeTo(
        crossinline collector: suspend (T) -> Unit,
    ) = scope.launch {
        filterEvents<T>().collect { event ->
            try {
                collector(event)
            } catch (e: Exception) {
                Timber.e(e, "Error in typed event subscriber for %s", T::class.simpleName)
            }
        }
    }

    // ── History & Debugging ───────────────────────────────────────

    /**
     * Get recent events from the history buffer.
     *
     * @param count Maximum number of events to return (0 = all)
     * @return List of recent events, newest first
     */
    fun getRecentEvents(count: Int = 0): List<AgentEvent> {
        synchronized(_history) {
            val events = _history.toList().reversed()
            return if (count > 0) events.take(count) else events
        }
    }

    /**
     * Get events of a specific type from history.
     */
    inline fun <reified T : AgentEvent> getRecentEventsOfType(count: Int = 10): List<T> {
        synchronized(_history) {
            return _history.filterIsInstance<T>().reversed().take(count)
        }
    }

    /**
     * Clear event history.
     */
    fun clearHistory() {
        synchronized(_history) {
            _history.clear()
        }
    }

    // ── Metrics ───────────────────────────────────────────────────

    /**
     * Get event bus metrics for monitoring.
     */
    fun getMetrics(): EventBusMetrics {
        synchronized(_history) {
            return EventBusMetrics(
                totalPublished = _totalPublished.get(),
                totalDropped = _totalDropped.get(),
                historySize = _history.size,
                maxHistorySize = historySize,
                subscriberCounts = _subscriberCounts.mapValues { it.value.get() },
            )
        }
    }

    /**
     * Reset all metrics.
     */
    fun resetMetrics() {
        _totalPublished.set(0)
        _totalDropped.set(0)
        _subscriberCounts.clear()
    }

    companion object {
        /** Default history buffer size. */
        const val DEFAULT_HISTORY_SIZE = 200

        /** Singleton instance for app-wide use. */
        @Volatile
        private var INSTANCE: AgentEventBus? = null

        /**
         * Get the singleton AgentEventBus instance.
         *
         * Thread-safe via double-checked locking.
         */
        fun getInstance(): AgentEventBus {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AgentEventBus().also { INSTANCE = it }
            }
        }

        /**
         * Reset the singleton (for testing).
         */
        fun resetInstance() {
            synchronized(this) {
                INSTANCE = null
            }
        }
    }
}

/**
 * Event bus metrics for monitoring and debugging.
 */
data class EventBusMetrics(
    val totalPublished: Long,
    val totalDropped: Long,
    val historySize: Int,
    val maxHistorySize: Int,
    val subscriberCounts: Map<String, Long>,
) {
    val dropRate: Double
        get() = if (totalPublished > 0) totalDropped.toDouble() / totalPublished else 0.0

    fun toMap(): Map<String, Any> = mapOf(
        "total_published" to totalPublished,
        "total_dropped" to totalDropped,
        "history_size" to historySize,
        "max_history_size" to maxHistorySize,
        "drop_rate" to dropRate,
        "subscriber_counts" to subscriberCounts,
    )
}
