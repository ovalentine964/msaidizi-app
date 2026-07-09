package com.msaidizi.app.agent.a2a

import com.msaidizi.app.agent.AgentEvent
import com.msaidizi.app.agent.AgentEventBus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A2A (Agent-to-Agent) Protocol — inter-agent communication layer.
 *
 * Implements a standardized protocol for agents to discover, communicate,
 * delegate tasks, and negotiate capabilities. Built on top of the existing
 * [AgentEventBus] for transport.
 *
 * ## Protocol Overview
 *
 *   ┌─────────┐  A2AMessage  ┌──────────────┐  dispatch  ┌─────────┐
 *   │ Agent A  │─────────────▶│ A2AProtocol  │───────────▶│ Agent B │
 *   └─────────┘              │ (Router)     │◀───────────│         │
 *                             └──────────────┘  response   └─────────┘
 *                                  │
 *                                  │ registry
 *                                  ▼
 *                            ┌──────────────┐
 *                            │ Agent Registry│
 *                            │ (capabilities)│
 *                            └──────────────┘
 *
 * ## Message Types
 * - REQUEST: Agent A asks Agent B to perform a task
 * - RESPONSE: Agent B returns the result
 * - BROADCAST: Agent A sends to all agents
 * - DISCOVERY: Find agents with specific capabilities
 * - NEGOTIATE: Capability negotiation between agents
 * - DELEGATE: Transfer task ownership
 *
 * ## Usage
 * ```kotlin
 * // Register an agent
 * a2a.registerAgent(AgentProfile(
 *     agentId = "business-agent",
 *     capabilities = listOf("transaction_recording", "sales_analysis")
 * ))
 *
 * // Send a request
 * val response = a2a.sendRequest(
 *     from = "orchestrator",
 *     to = "business-agent",
 *     action = "record_sale",
 *     payload = mapOf("item" to "mandazi", "amount" to "500")
 * )
 *
 * // Discover agents
 * val agents = a2a.discoverAgents(capability = "sales_analysis")
 * ```
 *
 * @param eventBus The underlying event bus for transport
 */
class A2AProtocol(
    private val eventBus: AgentEventBus = AgentEventBus.getInstance()
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    companion object {
        private const val TAG = "A2AProtocol"
        const val DEFAULT_TIMEOUT_MS = 10_000L
        const val BROADCAST_TIMEOUT_MS = 5_000L
    }

    // ── Agent Registry ────────────────────────────────────────────

    /** Registered agents and their capabilities */
    private val registry = ConcurrentHashMap<String, AgentProfile>()

    /** Pending request-response pairs (request ID → deferred response) */
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<A2AMessage>>()

    /** Inbound message stream for agents to subscribe to */
    private val _inboundMessages = MutableSharedFlow<A2AMessage>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val inboundMessages: SharedFlow<A2AMessage> = _inboundMessages.asSharedFlow()

    /** Message history for debugging */
    private val messageHistory = ArrayDeque<A2AMessage>(200)

    init {
        // Listen for A2A events on the event bus
        scope.launch {
            eventBus.filterEvents<AgentEvent.A2AMessageEvent>().collect { event ->
                handleIncomingEvent(event)
            }
        }
    }

    // ═══════════════ AGENT REGISTRATION ═══════════════

    /**
     * Register an agent with its capabilities.
     * Agents must register before they can send/receive A2A messages.
     */
    fun registerAgent(profile: AgentProfile) {
        registry[profile.agentId] = profile
        Timber.d("Agent registered: %s with %d capabilities",
            profile.agentId, profile.capabilities.size)

        // Notify other agents of new registration
        eventBus.publish(AgentEvent.A2AMessageEvent(
            eventId = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            source = "A2AProtocol",
            message = A2AMessage(
                messageId = UUID.randomUUID().toString(),
                from = "system",
                to = "*",
                type = A2AMessageType.BROADCAST,
                action = "agent_registered",
                payload = mapOf(
                    "agentId" to profile.agentId,
                    "capabilities" to profile.capabilities.joinToString(",")
                )
            )
        ))
    }

    /**
     * Unregister an agent.
     */
    fun unregisterAgent(agentId: String) {
        registry.remove(agentId)
        Timber.d("Agent unregistered: %s", agentId)
    }

    /**
     * Update an agent's capabilities.
     */
    fun updateCapabilities(agentId: String, capabilities: List<String>) {
        registry[agentId]?.let { existing ->
            registry[agentId] = existing.copy(capabilities = capabilities)
            Timber.d("Capabilities updated for %s: %s", agentId, capabilities)
        }
    }

    // ═══════════════ MESSAGE SENDING ═══════════════

    /**
     * Send a request to a specific agent and wait for a response.
     *
     * @param from Sender agent ID
     * @param to Target agent ID
     * @param action The action to perform
     * @param payload Action parameters
     * @param timeoutMs Response timeout in milliseconds
     * @return The response from the target agent
     * @throws A2ATimeoutException if the target doesn't respond in time
     * @throws A2AAgentNotFoundException if the target is not registered
     */
    suspend fun sendRequest(
        from: String,
        to: String,
        action: String,
        payload: Map<String, String> = emptyMap(),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): A2AMessage {
        if (registry[to] == null && to != "*") {
            throw A2AAgentNotFoundException("Agent '$to' is not registered")
        }

        val messageId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<A2AMessage>()
        pendingRequests[messageId] = deferred

        val message = A2AMessage(
            messageId = messageId,
            from = from,
            to = to,
            type = A2AMessageType.REQUEST,
            action = action,
            payload = payload,
            correlationId = messageId
        )

        publishMessage(message)

        return try {
            withTimeout(timeoutMs) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            pendingRequests.remove(messageId)
            throw A2ATimeoutException("Agent '$to' did not respond to '$action' within ${timeoutMs}ms")
        }
    }

    /**
     * Send a response to a pending request.
     *
     * @param originalRequest The original request message
     * @param result The response payload
     * @param success Whether the action succeeded
     */
    fun sendResponse(
        originalRequest: A2AMessage,
        result: Map<String, String>,
        success: Boolean = true
    ) {
        val response = A2AMessage(
            messageId = UUID.randomUUID().toString(),
            from = originalRequest.to,
            to = originalRequest.from,
            type = A2AMessageType.RESPONSE,
            action = originalRequest.action,
            payload = result,
            correlationId = originalRequest.correlationId,
            success = success
        )

        publishMessage(response)
    }

    /**
     * Broadcast a message to all registered agents.
     */
    fun broadcast(
        from: String,
        action: String,
        payload: Map<String, String> = emptyMap()
    ) {
        val message = A2AMessage(
            messageId = UUID.randomUUID().toString(),
            from = from,
            to = "*",
            type = A2AMessageType.BROADCAST,
            action = action,
            payload = payload
        )

        publishMessage(message)
    }

    // ═══════════════ AGENT DISCOVERY ═══════════════

    /**
     * Discover agents that have a specific capability.
     *
     * @param capability The capability to search for
     * @return List of agent profiles with the capability
     */
    fun discoverAgents(capability: String): List<AgentProfile> {
        return registry.values.filter { capability in it.capabilities }
    }

    /**
     * Discover all registered agents.
     */
    fun discoverAllAgents(): List<AgentProfile> {
        return registry.values.toList()
    }

    /**
     * Find the best agent for a given task based on capabilities and priority.
     *
     * @param requiredCapabilities Capabilities the agent must have
     * @param preferredAgentId Optional preferred agent
     * @return The best matching agent, or null
     */
    fun findBestAgent(
        requiredCapabilities: List<String>,
        preferredAgentId: String? = null
    ): AgentProfile? {
        // Check preferred agent first
        if (preferredAgentId != null) {
            val preferred = registry[preferredAgentId]
            if (preferred != null && requiredCapabilities.all { it in preferred.capabilities }) {
                return preferred
            }
        }

        // Find agents with all required capabilities, sorted by priority
        return registry.values
            .filter { agent -> requiredCapabilities.all { it in agent.capabilities } }
            .maxByOrNull { it.priority }
    }

    // ═══════════════ TASK DELEGATION ═══════════════

    /**
     * Delegate a task from one agent to another.
     *
     * @param from Delegating agent
     * @param taskDescription What needs to be done
     * @param requiredCapabilities What the receiving agent must be able to do
     * @param payload Task parameters
     * @return The delegation result
     */
    suspend fun delegateTask(
        from: String,
        taskDescription: String,
        requiredCapabilities: List<String>,
        payload: Map<String, String> = emptyMap()
    ): DelegationResult {
        val target = findBestAgent(requiredCapabilities)
            ?: return DelegationResult(
                success = false,
                error = "No agent found with capabilities: $requiredCapabilities"
            )

        return try {
            val response = sendRequest(
                from = from,
                to = target.agentId,
                action = "delegate:$taskDescription",
                payload = payload + mapOf(
                    "delegatedFrom" to from,
                    "requiredCapabilities" to requiredCapabilities.joinToString(",")
                )
            )
            DelegationResult(
                success = response.success,
                delegatedTo = target.agentId,
                result = response.payload
            )
        } catch (e: A2AException) {
            DelegationResult(
                success = false,
                error = e.message ?: "Delegation failed"
            )
        }
    }

    // ═══════════════ CAPABILITY NEGOTIATION ═══════════════

    /**
     * Negotiate capabilities between two agents.
     * Agent A tells Agent B what it needs, Agent B responds with what it can provide.
     *
     * @param from Requesting agent
     * @param to Target agent
     * @param requiredCapabilities What the requester needs
     * @return Negotiation result with agreed capabilities
     */
    suspend fun negotiateCapabilities(
        from: String,
        to: String,
        requiredCapabilities: List<String>
    ): NegotiationResult {
        val target = registry[to]
            ?: return NegotiationResult(
                success = false,
                offeredCapabilities = emptyMap(),
                error = "Agent '$to' not found"
            )

        val offered = requiredCapabilities.associateWith { cap ->
            if (cap in target.capabilities) CapabilityStatus.AVAILABLE
            else CapabilityStatus.UNAVAILABLE
        }

        val allAvailable = offered.values.all { it == CapabilityStatus.AVAILABLE }

        return NegotiationResult(
            success = allAvailable,
            offeredCapabilities = offered,
            error = if (!allAvailable) {
                val unavailable = offered.filter { it.value == CapabilityStatus.UNAVAILABLE }.keys
                "Unavailable capabilities: $unavailable"
            } else null
        )
    }

    // ═══════════════ MESSAGE HANDLING ═══════════════

    private fun publishMessage(message: A2AMessage) {
        // Add to history
        synchronized(messageHistory) {
            if (messageHistory.size >= 200) messageHistory.removeFirst()
            messageHistory.addLast(message)
        }

        // Publish via event bus
        eventBus.publish(AgentEvent.A2AMessageEvent(
            eventId = message.messageId,
            timestamp = message.timestamp,
            source = message.from,
            message = message
        ))

        Timber.d("A2A message: %s → %s [%s] %s",
            message.from, message.to, message.type.name, message.action)
    }

    private fun handleIncomingEvent(event: AgentEvent.A2AMessageEvent) {
        val message = event.message

        // Handle response to pending request
        if (message.type == A2AMessageType.RESPONSE && message.correlationId != null) {
            val deferred = pendingRequests.remove(message.correlationId)
            deferred?.complete(message)
            return
        }

        // Emit to inbound stream for agent subscribers
        scope.launch {
            _inboundMessages.emit(message)
        }
    }

    // ═══════════════ METRICS ═══════════════

    /**
     * Get protocol metrics for monitoring.
     */
    fun getMetrics(): A2AMetrics {
        synchronized(messageHistory) {
            return A2AMetrics(
                registeredAgents = registry.size,
                pendingRequests = pendingRequests.size,
                messagesInHistory = messageHistory.size,
                agentCapabilities = registry.mapValues { it.value.capabilities.size }
            )
        }
    }

    /**
     * Get recent messages for debugging.
     */
    fun getRecentMessages(count: Int = 20): List<A2AMessage> {
        synchronized(messageHistory) {
            return messageHistory.toList().reversed().take(count)
        }
    }
}

// ═══════════════ DATA CLASSES ═══════════════

/**
 * Standardized A2A message format.
 */
@kotlinx.serialization.Serializable
data class A2AMessage(
    val messageId: String,
    val from: String,
    val to: String,
    val type: A2AMessageType,
    val action: String,
    val payload: Map<String, String> = emptyMap(),
    val correlationId: String? = null,
    val success: Boolean = true,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * A2A message types.
 */
enum class A2AMessageType {
    REQUEST,     // Agent A asks Agent B to do something
    RESPONSE,    // Agent B responds to Agent A
    BROADCAST,   // Agent A sends to all agents
    DISCOVERY,   // Find agents with capabilities
    NEGOTIATE,   // Capability negotiation
    DELEGATE     // Task delegation
}

/**
 * Agent profile for registration.
 */
data class AgentProfile(
    val agentId: String,
    val displayName: String = agentId,
    val capabilities: List<String> = emptyList(),
    val priority: Int = 100,
    val isOnline: Boolean = true,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Result of a task delegation.
 */
data class DelegationResult(
    val success: Boolean,
    val delegatedTo: String? = null,
    val result: Map<String, String> = emptyMap(),
    val error: String? = null
)

/**
 * Result of capability negotiation.
 */
data class NegotiationResult(
    val success: Boolean,
    val offeredCapabilities: Map<String, CapabilityStatus>,
    val error: String? = null
)

enum class CapabilityStatus {
    AVAILABLE,
    UNAVAILABLE,
    PARTIAL
}

/**
 * A2A protocol metrics.
 */
data class A2AMetrics(
    val registeredAgents: Int,
    val pendingRequests: Int,
    val messagesInHistory: Int,
    val agentCapabilities: Map<String, Int>
)

// ═══════════════ EXCEPTIONS ═══════════════

open class A2AException(message: String) : Exception(message)
class A2ATimeoutException(message: String) : A2AException(message)
class A2AAgentNotFoundException(message: String) : A2AException(message)

// ═══════════════ EVENT EXTENSIONS ═══════════════

/**
 * A2A message event for the AgentEventBus.
 */
fun AgentEvent.Companion.a2aMessageEvent(
    message: A2AMessage
): AgentEvent.A2AMessageEvent {
    return AgentEvent.A2AMessageEvent(
        eventId = message.messageId,
        timestamp = message.timestamp,
        source = message.from,
        message = message
    )
}
