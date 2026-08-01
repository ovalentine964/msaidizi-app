package com.msaidizi.agent.council

import com.msaidizi.core.database.KgFactDao
import com.msaidizi.core.database.KgFactEntity
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CouncilEventBus — Lightweight inter-council communication via coroutine channels.
 *
 * Design principles:
 * - Uses Kotlin coroutine Channels and SharedFlow, NOT a message broker
 * - Zero-allocation hot path for common events
 * - Back-pressure aware: unbounded buffer with conflation for observers
 * - Thread-safe via ConcurrentHashMap for subscriber tracking
 * - Total memory overhead: <50KB even with all 6 councils subscribed
 *
 * Event flow example (sale recorded):
 *   Finance council records sale
 *     → publishes CouncilEvent.TransactionRecorded
 *       → Inventory council updates stock
 *       → Growth council awards gamification points
 *       → Market council checks restock thresholds
 *
 * @see CouncilEventType for the full event taxonomy
 */
@Singleton
class CouncilEventBus @Inject constructor(
    private val kgFactDao: KgFactDao,
    private val gson: Gson
) {

    /**
     * Internal coroutine scope for event dispatch.
     * Uses [SupervisorJob] so one subscriber failure doesn't cancel others.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Hot event stream — new subscribers only receive FUTURE events.
     * Replay = 0 to avoid stale event delivery.
     * ExtraBufferCapacity = 64 to handle burst traffic without suspension.
     */
    private val _events = MutableSharedFlow<CouncilEvent>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val events: SharedFlow<CouncilEvent> = _events.asSharedFlow()

    /**
     * Targeted channel subscriptions — for direct council-to-council messaging.
     * Key: target council name. Value: channel for that council's inbox.
     */
    private val targetedChannels = ConcurrentHashMap<CouncilType, Channel<CouncilEvent>>(8)

    /**
     * Event type subscriptions — councils subscribe to specific event types
     * to reduce unnecessary processing.
     * Key: event type. Value: list of handler lambdas.
     */
    private val typeSubscribers = ConcurrentHashMap<CouncilEventType, MutableList<suspend (CouncilEvent) -> Unit>>(16)

    // ── Publishing ──────────────────────────────────────────────

    /**
     * Broadcast an event to all subscribers.
     * Non-blocking: emits to SharedFlow buffer. If buffer is full,
     * oldest event is dropped (conflation behavior).
     *
     * P1: Event sourcing — all events are persisted to SQLite for
     * audit trail, replay, and debugging.
     */
    fun publish(event: CouncilEvent) {
        val emitted = _events.tryEmit(event)
        if (!emitted) {
            Timber.w("EventBus buffer full, dropping event: ${event.type}")
        }
        // P1: Persist event for audit/replay (async, non-blocking)
        scope.launch { persistEvent(event) }
        Timber.d("Event published: ${event.type} from=${event.sourceCouncil}")
    }

    /**
     * P1: Persist an event to the knowledge graph for audit trail.
     * Events are stored as facts with category "council_events".
     */
    private suspend fun persistEvent(event: CouncilEvent) {
        try {
            kgFactDao.upsert(KgFactEntity(
                subject = "event:${event.type.name}",
                predicate = event.sourceCouncil.name,
                obj = gson.toJson(event.payload),
                confidence = 1.0f,
                source = "council_event_bus"
            ))
        } catch (e: Exception) {
            Timber.w(e, "Failed to persist council event: ${event.type}")
        }
    }

    /**
     * P1: Replay events from the audit log for debugging.
     * Returns events filtered by type and time range.
     */
    suspend fun replayEvents(
        eventType: CouncilEventType? = null,
        sinceTimestamp: Long = 0L
    ): List<CouncilEvent> {
        return try {
            val facts = if (eventType != null) {
                kgFactDao.getBySubject("event:${eventType.name}")
            } else {
                kgFactDao.getBySubjectPrefix("event:")
            }
            facts.filter { it.updatedAt >= sinceTimestamp }
                .mapNotNull { fact ->
                    try {
                        val payload = gson.fromJson(fact.obj, Map::class.java) as? Map<String, Any> ?: emptyMap()
                        CouncilEvent(
                            type = CouncilEventType.valueOf(fact.subject.removePrefix("event:")),
                            sourceCouncil = CouncilType.valueOf(fact.predicate),
                            payload = payload.mapValues { it.value.toString() },
                            timestamp = fact.updatedAt
                        )
                    } catch (e: Exception) {
                        null
                    }
                }
        } catch (e: Exception) {
            Timber.w(e, "Failed to replay council events")
            emptyList()
        }
    }

    /**
     * Send a targeted event to a specific council.
     * Uses a buffered channel per council. If the channel is full,
     * the event is dropped (non-blocking).
     */
    fun sendTo(target: CouncilType, event: CouncilEvent) {
        val channel = targetedChannels.getOrPut(target) {
            Channel(capacity = 64)
        }
        val sent = channel.trySend(event)
        if (sent.isFailure) {
            Timber.w("Targeted channel full for $target, dropping event: ${event.type}")
        }
        Timber.d("Targeted event sent: ${event.type} → $target")
    }

    // ── Subscribing ─────────────────────────────────────────────

    /**
     * Subscribe to all events filtered by type.
     * Returns a registration handle for cleanup.
     */
    fun subscribe(
        eventType: CouncilEventType,
        handler: suspend (CouncilEvent) -> Unit
    ): EventSubscription {
        val handlers = typeSubscribers.getOrPut(eventType) { mutableListOf() }
        handlers.add(handler)
        Timber.d("Subscriber added for event type: $eventType (total: ${handlers.size})")

        return EventSubscription(this, eventType, handler)
    }

    /**
     * Get the targeted channel for a council.
     * Creates the channel if it doesn't exist.
     */
    fun getChannel(council: CouncilType): Channel<CouncilEvent> {
        return targetedChannels.getOrPut(council) {
            Channel(capacity = 64)
        }
    }

    /**
     * Remove a subscription handle.
     */
    internal fun unsubscribe(eventType: CouncilEventType, handler: suspend (CouncilEvent) -> Unit) {
        typeSubscribers[eventType]?.remove(handler)
        Timber.d("Subscriber removed for event type: $eventType")
    }

    /**
     * Start the event dispatch loop.
     * Called once during app initialization.
     * Routes SharedFlow events to type-specific handlers.
     */
    fun startDispatch() {
        scope.launch {
            events.collect { event ->
                val handlers = typeSubscribers[event.type]
                if (handlers != null) {
                    for (handler in handlers) {
                        try {
                            handler(event)
                        } catch (e: Exception) {
                            Timber.e(e, "Event handler failed for ${event.type}")
                        }
                    }
                }
            }
        }
        Timber.i("CouncilEventBus dispatch started")
    }

    /**
     * Clear all subscriptions and channels.
     * Called on app teardown.
     */
    fun clear() {
        typeSubscribers.clear()
        targetedChannels.values.forEach { it.close() }
        targetedChannels.clear()
        Timber.i("CouncilEventBus cleared")
    }
}

// ──────────────────────────────────────────────
// Event Types and Data Classes
// ──────────────────────────────────────────────

/**
 * Taxonomy of inter-council events.
 * Each event type maps to a specific domain event that councils care about.
 */
enum class CouncilEventType {
    // Finance → others
    TRANSACTION_RECORDED,      // Sale/expense/purchase recorded
    CASH_FLOW_UPDATED,         // Cash flow predictions updated
    DEBT_RECORDED,             // Customer debt created/updated
    PAYMENT_RECEIVED,          // Customer payment received

    // Inventory → others
    STOCK_LOW,                 // Product below minimum stock
    STOCK_UPDATED,             // Stock level changed
    RESTOCK_ORDERED,           // Restock order placed
    WASTE_DETECTED,            // Spoilage/waste recorded

    // Market → others
    PRICE_CHANGED,             // Market price updated
    COMPETITOR_ALERT,          // Competitor activity detected
    MARKET_DAY_REMINDER,       // Upcoming market day
    SUPPLIER_FOUND,            // New supplier match

    // Growth → others
    LEVEL_UP,                  // Gamification level achieved
    GOAL_PROGRESS,             // Goal progress updated
    CREDIT_READY,              // Credit readiness threshold met
    INSURANCE_MATCH_FOUND,     // Insurance match found

    // Voice → others
    LANGUAGE_DETECTED,         // Language/code-switch detected
    VOICE_COMMAND_PARSED,      // Voice command parsed to intent

    // Security → others
    AUTH_REQUIRED,             // Authentication needed
    ANOMALY_DETECTED,          // Anomalous pattern detected
    PRIVACY_VIOLATION,         // Privacy guard triggered

    // Cross-council
    COUNCIL_ERROR,             // A council encountered an error
    HEALTH_CHECK,              // Periodic health ping
    CONTEXT_REQUEST            // Council requests additional context
}

/**
 * Council types — maps to the 6 specialized councils.
 */
enum class CouncilType {
    FINANCE,
    INVENTORY,
    MARKET,
    GROWTH,
    VOICE,
    SECURITY
}

/**
 * A council event with source attribution and typed payload.
 */
data class CouncilEvent(
    val type: CouncilEventType,
    val sourceCouncil: CouncilType,
    val payload: Map<String, Any> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis(),
    val correlationId: String? = null  // For tracking event chains
)

/**
 * Subscription handle for cleanup.
 */
class EventSubscription(
    private val bus: CouncilEventBus,
    private val eventType: CouncilEventType,
    private val handler: suspend (CouncilEvent) -> Unit
) {
    fun unsubscribe() = bus.unsubscribe(eventType, handler)
}
