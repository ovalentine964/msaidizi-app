package com.msaidizi.app.agent

import com.msaidizi.app.core.database.TransactionDao
import com.msaidizi.app.core.database.PatternDao
import com.msaidizi.app.core.database.InventoryDao
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Unified State Manager — Factor 5: Unify State with the World.
 *
 * Single source of truth for agent state on Android.
 * Bridges Room database (persistent) and agent memory (in-memory)
 * with event sourcing for audit trail and rollback.
 *
 * Mathematical foundation:
 * - Event sourcing: State = f(events). Every change is an append-only event.
 * - Version vector for conflict detection
 * - Optimistic concurrency with retry
 *
 * Designed for Android:
 * - No coroutines in core operations (synchronous where possible)
 * - Bounded event log (prevents memory leaks)
 * - Integrates with existing Room DAOs
 */
class UnifiedStateManager(
    private val agentName: String,
    private val maxEvents: Int = DEFAULT_MAX_EVENTS
) {
    companion object {
        const val DEFAULT_MAX_EVENTS = 500
    }

    // ── State Storage ──────────────────────────────────────────────

    /** In-memory state (canonical, fast access). */
    private val state = ConcurrentHashMap<String, Any>()

    /** Event log (event sourcing). */
    private val events = mutableListOf<StateEvent>()

    /** Current state version (incremented on every change). */
    var version: Int = 0
        private set

    /** Keys that have been modified since last sync. */
    private val dirtyKeys = mutableSetOf<String>()

    /** External state providers (Room DAOs, etc.). */
    private val providers = mutableMapOf<String, () -> Any?>()

    /** Subscribers for state changes. */
    private val subscribers = mutableMapOf<String, MutableList<(String, Any?, StateEvent) -> Unit>>()

    /** Conflict log. */
    private val conflicts = mutableListOf<ConflictInfo>()

    // ═══════════════════════════════════════════════════════════════
    // CORE OPERATIONS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get a value from unified state.
     * Falls back to external provider if not in local state.
     *
     * @param key State key
     * @param default Default value if not found
     * @return The value, or default
     */
    fun <T> get(key: String, default: T? = null): T? {
        @Suppress("UNCHECKED_CAST")
        var value = state[key] as? T

        // Try external provider if not in local state
        if (value == null && key in providers) {
            try {
                value = providers[key]?.invoke() as? T
                if (value != null) {
                    state[key] = value as Any
                }
            } catch (e: Exception) {
                Timber.w("UnifiedState[%s]: Provider error for '%s': %s",
                    agentName, key, e.message)
            }
        }

        return value ?: default
    }

    /**
     * Set a value in unified state.
     * Creates a state event for the event log.
     *
     * @param key State key
     * @param value New value
     * @return The state event
     */
    fun set(key: String, value: Any): StateEvent {
        val previous = state[key]
        state[key] = value
        version++

        val event = StateEvent(
            eventId = UUID.randomUUID().toString().take(12),
            operation = StateOperation.SET,
            key = key,
            value = value,
            previousValue = previous,
            agentName = agentName,
            timestamp = System.currentTimeMillis(),
            version = version
        )

        events.add(event)
        dirtyKeys.add(key)
        trimEvents()

        // Notify subscribers
        notifySubscribers(key, value, event)

        Timber.d("UnifiedState[%s]: SET '%s' (v%d)", agentName, key, version)

        return event
    }

    /**
     * Merge updates into an existing map value.
     *
     * @param key State key (must contain a Map)
     * @param updates Key-value pairs to merge
     * @return The state event
     */
    fun update(key: String, updates: Map<String, Any>): StateEvent {
        val current = get<Map<String, Any>>(key) ?: emptyMap()
        val merged = current.toMutableMap().apply { putAll(updates) }
        return set(key, merged)
    }

    /**
     * Append an item to a list value.
     *
     * @param key State key (must contain a List)
     * @param item Item to append
     * @param maxLength Optional max list length (evicts oldest)
     * @return The state event
     */
    fun append(key: String, item: Any, maxLength: Int? = null): StateEvent {
        val current = get<List<Any>>(key)?.toMutableList() ?: mutableListOf()
        current.add(item)
        if (maxLength != null && current.size > maxLength) {
            val trimmed = current.takeLast(maxLength)
            return set(key, trimmed)
        }
        return set(key, current)
    }

    /**
     * Delete a key from state.
     *
     * @param key State key to delete
     * @return The state event
     */
    fun delete(key: String): StateEvent {
        val previous = state.remove(key)
        version++

        val event = StateEvent(
            eventId = UUID.randomUUID().toString().take(12),
            operation = StateOperation.DELETE,
            key = key,
            value = null,
            previousValue = previous,
            agentName = agentName,
            timestamp = System.currentTimeMillis(),
            version = version
        )

        events.add(event)
        dirtyKeys.remove(key)

        notifySubscribers(key, null, event)

        return event
    }

    // ═══════════════════════════════════════════════════════════════
    // EXTERNAL STATE PROVIDERS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Register an external state provider.
     *
     * The provider is called when a key is not found in local state.
     * Used to bridge Room DAOs into the unified state.
     *
     * @param key State key to provide
     * @param provider Function that returns the value
     */
    fun registerProvider(key: String, provider: () -> Any?) {
        providers[key] = provider
        Timber.d("UnifiedState[%s]: Registered provider for '%s'", agentName, key)
    }

    /**
     * Register Room DAOs as state providers.
     * Convenience method for common DAO integrations.
     */
    fun registerRoomProviders(
        transactionDao: TransactionDao? = null,
        patternDao: PatternDao? = null,
        inventoryDao: InventoryDao? = null
    ) {
        transactionDao?.let { dao ->
            registerProvider("recent_transactions") {
                // This would need to be called from a coroutine scope
                // For now, returns null and expects manual sync
                null
            }
        }
        patternDao?.let { dao ->
            registerProvider("patterns") { null }
        }
        inventoryDao?.let { dao ->
            registerProvider("inventory") { null }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // SUBSCRIPTIONS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Subscribe to state changes for a key.
     *
     * @param key State key to watch
     * @param callback Called with (key, newValue, event) on change
     */
    fun subscribe(key: String, callback: (String, Any?, StateEvent) -> Unit) {
        subscribers.getOrPut(key) { mutableListOf() }.add(callback)
    }

    private fun notifySubscribers(key: String, value: Any?, event: StateEvent) {
        subscribers[key]?.forEach { callback ->
            try {
                callback(key, value, event)
            } catch (e: Exception) {
                Timber.w("UnifiedState[%s]: Subscriber error for '%s': %s",
                    agentName, key, e.message)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // EVENT SOURCING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get state events, optionally filtered.
     *
     * @param sinceVersion Only events after this version
     * @param keyFilter Only events for this key
     * @return List of matching events
     */
    fun getEvents(
        sinceVersion: Int = 0,
        keyFilter: String? = null
    ): List<StateEvent> {
        return events
            .filter { it.version > sinceVersion }
            .let { if (keyFilter != null) it.filter { it.key == keyFilter } else it }
    }

    /**
     * Get a point-in-time snapshot of the current state.
     */
    fun getSnapshot(): StateSnapshot {
        return StateSnapshot(
            agentName = agentName,
            state = state.toMap(),
            version = version,
            timestamp = System.currentTimeMillis(),
            eventCount = events.size
        )
    }

    /**
     * Rollback state to a specific version by replaying events.
     *
     * @param targetVersion Version to rollback to
     * @return True if rollback succeeded
     */
    fun rollback(targetVersion: Int): Boolean {
        if (targetVersion < 0 || targetVersion > version) {
            Timber.w("UnifiedState[%s]: Invalid rollback version %d (current: %d)",
                agentName, targetVersion, version)
            return false
        }

        // Replay events up to target version
        state.clear()
        val replayEvents = events.filter { it.version <= targetVersion }

        for (event in replayEvents) {
            when (event.operation) {
                StateOperation.SET -> state[event.key] = requireNotNull(event.value) { "SET operation requires non-null value for key '${'$'}{event.key}" }
                StateOperation.UPDATE -> {
                    val current = state[event.key]
                    if (current is Map<*, *> && event.value is Map<*, *>) {
                        @Suppress("UNCHECKED_CAST")
                        val merged = (current as Map<String, Any>).toMutableMap()
                        merged.putAll(event.value as Map<String, Any>)
                        state[event.key] = merged
                    }
                }
                StateOperation.DELETE -> state.remove(event.key)
                StateOperation.APPEND -> {
                    val current = state[event.key]
                    if (current is List<*>) {
                        state[event.key] = current + listOf(event.value)
                    }
                }
            }
        }

        val oldVersion = version
        version = targetVersion
        dirtyKeys.clear()
        dirtyKeys.addAll(state.keys)

        Timber.i("UnifiedState[%s]: Rollback from v%d to v%d, restored %d keys",
            agentName, oldVersion, targetVersion, state.size)

        return true
    }

    // ═══════════════════════════════════════════════════════════════
    // CONFLICT DETECTION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Detect if a key has been modified since expectedVersion.
     * Used for optimistic concurrency control.
     *
     * @param key State key
     * @param expectedVersion Expected version
     * @return True if conflict detected
     */
    fun detectConflict(key: String, expectedVersion: Int): Boolean {
        val conflicting = events.filter { it.key == key && it.version > expectedVersion }
        if (conflicting.isNotEmpty()) {
            conflicts.add(ConflictInfo(
                key = key,
                expectedVersion = expectedVersion,
                actualVersion = version,
                conflictingEventIds = conflicting.map { it.eventId },
                detectedAt = System.currentTimeMillis()
            ))
            return true
        }
        return false
    }

    // ═══════════════════════════════════════════════════════════════
    // SYNC HELPERS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get all dirty (modified) keys that need syncing.
     */
    fun getDirtyKeys(): Set<String> = dirtyKeys.toSet()

    /**
     * Mark keys as synced (clean).
     */
    fun markSynced(keys: Set<String>) {
        dirtyKeys.removeAll(keys)
    }

    /**
     * Check if there are unsynced changes.
     */
    fun hasUnsyncedChanges(): Boolean = dirtyKeys.isNotEmpty()

    // ═══════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════

    private fun trimEvents() {
        if (events.size > maxEvents) {
            val trimmed = events.takeLast(maxEvents)
            events.clear()
            events.addAll(trimmed)
        }
    }

    fun getStats(): Map<String, Any> {
        return mapOf(
            "agent" to agentName,
            "version" to version,
            "keys" to state.size,
            "events" to events.size,
            "dirtyKeys" to dirtyKeys.size,
            "conflicts" to conflicts.size,
            "providers" to providers.keys.toList(),
            "subscribers" to subscribers.mapValues { it.value.size }
        )
    }

    fun getAllKeys(): List<String> = state.keys.toList()
}

// ═══════════════════════════════════════════════════════════════
// DATA CLASSES
// ═══════════════════════════════════════════════════════════════

enum class StateOperation {
    SET, UPDATE, DELETE, APPEND
}

data class StateEvent(
    val eventId: String,
    val operation: StateOperation,
    val key: String,
    val value: Any?,
    val previousValue: Any?,
    val agentName: String,
    val timestamp: Long,
    val version: Int,
    val metadata: Map<String, Any> = emptyMap()
)

data class StateSnapshot(
    val agentName: String,
    val state: Map<String, Any>,
    val version: Int,
    val timestamp: Long,
    val eventCount: Int
)

data class ConflictInfo(
    val key: String,
    val expectedVersion: Int,
    val actualVersion: Int,
    val conflictingEventIds: List<String>,
    val detectedAt: Long
)
