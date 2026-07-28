package com.msaidizi.agent.loops

import com.msaidizi.agent.tools.ToolResult
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CircuitBreaker — Prevent cascading failures from external dependencies.
 *
 * Implements the standard circuit breaker pattern from distributed systems:
 *
 *   CLOSED → (failures exceed threshold) → OPEN
 *   OPEN → (cooldown expires) → HALF_OPEN
 *   HALF_OPEN → (success) → CLOSED
 *   HALF_OPEN → (failure) → OPEN
 *
 * Protects external dependencies:
 *   - SyncEngine (cloud sync)
 *   - WhatsAppReporter (WhatsApp Business API)
 *   - ModelDownloader (HuggingFace/GitHub downloads)
 *
 * Configuration per tool is tunable. Defaults:
 *   - 5 failures → open circuit
 *   - 30s cooldown → half-open
 *   - 1 success in half-open → close circuit
 *
 * Reference: loop_engineering_report.md §4.2
 */
@Singleton
class CircuitBreaker @Inject constructor() {

    companion object {
        /** Default failure threshold to open the circuit. */
        const val DEFAULT_FAILURE_THRESHOLD = 5

        /** Default cooldown period before half-open (ms). */
        const val DEFAULT_COOLDOWN_MS = 30_000L

        /** Failures in half-open state before re-opening. */
        const val HALF_OPEN_FAILURE_THRESHOLD = 1
    }

    /**
     * Per-tool circuit breaker configuration.
     * Tools not listed here use defaults.
     */
    private val toolConfigs = mapOf(
        "sync_engine" to CircuitConfig(
            failureThreshold = 5,
            cooldownMs = 30_000L,
            description = "Cloud sync — requires WiFi, battery > 20%"
        ),
        "whatsapp_reporter" to CircuitConfig(
            failureThreshold = 3,
            cooldownMs = 60_000L,  // WhatsApp API: longer cooldown
            description = "WhatsApp Business API — rate limited"
        ),
        "model_downloader" to CircuitConfig(
            failureThreshold = 3,
            cooldownMs = 120_000L,  // Downloads: longer cooldown
            description = "Model download — large files, network sensitive"
        )
    )

    /** Per-tool circuit state. */
    private val circuits = ConcurrentHashMap<String, CircuitState>()

    /**
     * Execute a tool call through the circuit breaker.
     *
     * If the circuit is OPEN, returns a fallback result immediately without
     * calling the tool. If HALF_OPEN, allows one request through to test
     * if the dependency has recovered.
     *
     * @param toolName Name of the tool (must be in [toolConfigs] for protection)
     * @param block    Suspend lambda that executes the actual tool call
     * @return ToolResult from the tool, or a fallback if circuit is open
     */
    suspend fun executeWithBreaker(
        toolName: String,
        block: suspend () -> ToolResult?
    ): ToolResult {
        val config = toolConfigs[toolName]
        if (config == null) {
            // No circuit breaker configured — execute directly
            return block() ?: ToolResult.error(toolName, "Tool returned null", "NULL_RESULT")
        }

        val state = circuits.getOrPut(toolName) { CircuitState() }

        // ── Check circuit state ──────────────────────────────────
        when (state.status) {
            CircuitStatus.OPEN -> {
                // Check if cooldown has expired
                val elapsed = System.currentTimeMillis() - state.lastFailureTime
                if (elapsed >= config.cooldownMs) {
                    // Transition to HALF_OPEN
                    state.status = CircuitStatus.HALF_OPEN
                    state.halfOpenAttempts = 0
                    Timber.i("CircuitBreaker [%s]: OPEN → HALF_OPEN (cooldown expired after %dms)",
                        toolName, elapsed)
                } else {
                    // Circuit still open — fail fast
                    val remainingMs = config.cooldownMs - elapsed
                    Timber.d("CircuitBreaker [%s]: OPEN — failing fast (%dms remaining)",
                        toolName, remainingMs)
                    return ToolResult.error(
                        toolName,
                        "Service temporarily unavailable. Retrying in ${remainingMs / 1000}s.",
                        "CIRCUIT_OPEN"
                    )
                }
            }
            CircuitStatus.HALF_OPEN -> {
                Timber.d("CircuitBreaker [%s]: HALF_OPEN — allowing test request", toolName)
            }
            CircuitStatus.CLOSED -> {
                // Normal operation — proceed
            }
        }

        // ── Execute the tool call ────────────────────────────────
        return try {
            val result = block()

            if (result != null && result.success) {
                // ── Success ──────────────────────────────────────
                onSuccess(toolName, state)
                result
            } else {
                // ── Tool returned failure ────────────────────────
                onFailure(toolName, state, config, result?.errorCode ?: "TOOL_FAILURE")
                result ?: ToolResult.error(toolName, "Tool returned null", "NULL_RESULT")
            }
        } catch (e: Exception) {
            // ── Exception during execution ───────────────────────
            onFailure(toolName, state, config, "EXCEPTION")
            Timber.e(e, "CircuitBreaker [%s]: Exception during execution", toolName)
            ToolResult.error(toolName, "Execution failed: ${e.message}", "EXCEPTION")
        }
    }

    /**
     * Handle a successful tool execution.
     */
    private fun onSuccess(toolName: String, state: CircuitState) {
        when (state.status) {
            CircuitStatus.HALF_OPEN -> {
                // Success in half-open → close the circuit
                state.status = CircuitStatus.CLOSED
                state.consecutiveFailures = 0
                state.halfOpenAttempts = 0
                Timber.i("CircuitBreaker [%s]: HALF_OPEN → CLOSED (recovered)", toolName)
            }
            CircuitStatus.CLOSED -> {
                // Reset failure counter
                state.consecutiveFailures = 0
            }
            CircuitStatus.OPEN -> {
                // Shouldn't happen (we don't execute in OPEN), but handle gracefully
                state.status = CircuitStatus.CLOSED
                state.consecutiveFailures = 0
            }
        }
    }

    /**
     * Handle a failed tool execution.
     */
    private fun onFailure(
        toolName: String,
        state: CircuitState,
        config: CircuitConfig,
        errorCode: String
    ) {
        state.lastFailureTime = System.currentTimeMillis()

        when (state.status) {
            CircuitStatus.HALF_OPEN -> {
                // Failure in half-open → re-open the circuit
                state.status = CircuitStatus.OPEN
                state.halfOpenAttempts = 0
                Timber.w("CircuitBreaker [%s]: HALF_OPEN → OPEN (test request failed: %s)",
                    toolName, errorCode)
            }
            CircuitStatus.CLOSED -> {
                state.consecutiveFailures++
                if (state.consecutiveFailures >= config.failureThreshold) {
                    // Threshold exceeded → open the circuit
                    state.status = CircuitStatus.OPEN
                    Timber.w("CircuitBreaker [%s]: CLOSED → OPEN (%d consecutive failures, threshold=%d)",
                        toolName, state.consecutiveFailures, config.failureThreshold)
                } else {
                    Timber.d("CircuitBreaker [%s]: Failure %d/%d (%s)",
                        toolName, state.consecutiveFailures, config.failureThreshold, errorCode)
                }
            }
            CircuitStatus.OPEN -> {
                // Already open — just update timestamp
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // STATUS & MONITORING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get the current circuit status for a tool.
     */
    fun getCircuitStatus(toolName: String): CircuitStatusInfo {
        val state = circuits[toolName]
        val config = toolConfigs[toolName]

        if (state == null || config == null) {
            return CircuitStatusInfo(
                toolName = toolName,
                status = CircuitStatus.CLOSED,
                isProtected = config != null,
                consecutiveFailures = 0,
                description = config?.description ?: "No circuit breaker configured"
            )
        }

        val remainingCooldownMs = if (state.status == CircuitStatus.OPEN) {
            val elapsed = System.currentTimeMillis() - state.lastFailureTime
            (config.cooldownMs - elapsed).coerceAtLeast(0)
        } else 0

        return CircuitStatusInfo(
            toolName = toolName,
            status = state.status,
            isProtected = true,
            consecutiveFailures = state.consecutiveFailures,
            failureThreshold = config.failureThreshold,
            remainingCooldownMs = remainingCooldownMs,
            description = config.description
        )
    }

    /**
     * Get all circuit statuses. Used for monitoring and debugging.
     */
    fun getAllCircuitStatuses(): List<CircuitStatusInfo> {
        return toolConfigs.keys.map { getCircuitStatus(it) }
    }

    /**
     * Manually reset a circuit (e.g., after operator intervention).
     */
    fun resetCircuit(toolName: String) {
        circuits[toolName]?.let { state ->
            state.status = CircuitStatus.CLOSED
            state.consecutiveFailures = 0
            state.halfOpenAttempts = 0
            Timber.i("CircuitBreaker [%s]: Manually reset to CLOSED", toolName)
        }
    }

    /**
     * Check if a tool's circuit is currently open.
     */
    fun isCircuitOpen(toolName: String): Boolean {
        val state = circuits[toolName] ?: return false
        if (state.status != CircuitStatus.OPEN) return false

        // Check if cooldown expired
        val config = toolConfigs[toolName] ?: return false
        val elapsed = System.currentTimeMillis() - state.lastFailureTime
        return elapsed < config.cooldownMs
    }
}

// ═══════════════════════════════════════════════════════════════════
// DATA CLASSES
// ═══════════════════════════════════════════════════════════════════

/**
 * Circuit breaker states.
 */
enum class CircuitStatus {
    /** Normal operation — requests pass through. */
    CLOSED,
    /** Failing fast — no requests sent, returning fallback. */
    OPEN,
    /** Testing recovery — allowing limited requests through. */
    HALF_OPEN
}

/**
 * Per-tool circuit breaker configuration.
 */
data class CircuitConfig(
    /** Number of consecutive failures before opening the circuit. */
    val failureThreshold: Int = CircuitBreaker.DEFAULT_FAILURE_THRESHOLD,
    /** Time to wait in OPEN state before transitioning to HALF_OPEN (ms). */
    val cooldownMs: Long = CircuitBreaker.DEFAULT_COOLDOWN_MS,
    /** Human-readable description of what this circuit protects. */
    val description: String = ""
)

/**
 * Mutable circuit state for a single tool.
 */
data class CircuitState(
    var status: CircuitStatus = CircuitStatus.CLOSED,
    var consecutiveFailures: Int = 0,
    var lastFailureTime: Long = 0L,
    var halfOpenAttempts: Int = 0
)

/**
 * Read-only circuit status information for monitoring.
 */
data class CircuitStatusInfo(
    val toolName: String,
    val status: CircuitStatus,
    val isProtected: Boolean,
    val consecutiveFailures: Int,
    val failureThreshold: Int = CircuitBreaker.DEFAULT_FAILURE_THRESHOLD,
    val remainingCooldownMs: Long = 0,
    val description: String = ""
) {
    fun toDisplayString(): String {
        val statusEmoji = when (status) {
            CircuitStatus.CLOSED -> "🟢"
            CircuitStatus.OPEN -> "🔴"
            CircuitStatus.HALF_OPEN -> "🟡"
        }
        return "$statusEmoji $toolName: ${status.name} (${consecutiveFailures}/${failureThreshold} failures)"
    }
}
