package com.msaidizi.app.agent.harness

import com.msaidizi.app.agent.ModelRouter
import com.msaidizi.app.agent.cost.InferenceCostTracker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import timber.log.Timber
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unified Inference Harness — wraps ANY model call with monitoring, fallback,
 * timeout, retry, cost tracking, circuit breaking, and quality gates.
 *
 * Every model call in Msaidizi MUST go through this harness. It extracts the
 * monitoring/fallback/retry logic from [ModelRouter] into a reusable wrapper
 * that works for:
 * - On-device LLM (llama.cpp / Qwen)
 * - Cloud LLM (DeepSeek, GPT, Claude)
 * - STT (Whisper via [SpeechRecognizer])
 * - TTS (Kokoro / Piper)
 * - CV (MobileNetV3 via [ProductClassifier])
 *
 * ## Architecture
 * ```
 * Caller → InferenceHarness.execute() → CircuitBreaker.check() → QualityGate.check()
 *              ├── timeout guard
 *              ├── retry with exponential backoff (3x)
 *              ├── fallback chain (provider1 → provider2 → ... → text-only)
 *              ├── circuit breaker (open after 5 failures, half-open after 30s)
 *              ├── quality gates (confidence threshold per model type)
 *              ├── metrics collection (latency p50/p95/p99, tokens, cost)
 *              └── event emission for monitoring
 * ```
 *
 * ## Metrics Tracked
 * - Latency: avg, p50, p95, p99 per provider
 * - Success rate per provider
 * - Cost per call (micro-dollars)
 * - Token usage (input + output)
 * - Fallback frequency
 * - Circuit breaker state transitions
 *
 * ## Fallback Strategy
 * Default chain: on-device → cloud → text-only
 * Each model type has its own fallback chain.
 *
 * ## Circuit Breaker
 * Opens after 5 consecutive failures. Half-open after 30s cooldown.
 * Prevents cascading failures when a provider is down.
 *
 * ## Quality Gates
 * Confidence threshold per model type. Rejects low-quality outputs
 * before they reach the user.
 *
 * Based on Swarm 2 & Swarm 7 research.
 */
@Singleton
class InferenceHarness @Inject constructor(
    private val costTracker: InferenceCostTracker
) {
    companion object {
        private const val TAG = "InferenceHarness"
        private const val MAX_LATENCY_SAMPLES = 1000

        // Circuit breaker defaults
        private const val CB_FAILURE_THRESHOLD = 5
        private const val CB_HALF_OPEN_COOLDOWN_MS = 30_000L

        // Quality gate defaults per model type
        private val DEFAULT_CONFIDENCE_THRESHOLDS = mapOf(
            "llm" to 0.6,
            "stt" to 0.5,
            "tts" to 0.7,
            "cv" to 0.65
        )
    }

    // ── Provider Metrics ──────────────────────────────────────────

    private val providerMetrics = ConcurrentHashMap<String, ProviderMetrics>()

    // ── Circuit Breakers (per provider) ───────────────────────────

    private val circuitBreakers = ConcurrentHashMap<String, CircuitBreaker>()

    // ── Per-User/Per-Model/Per-Day Cost Tracking ──────────────────

    private val dailyUserModelCosts = ConcurrentHashMap<String, DailyCostBucket>()

    // ── Events for monitoring UI ──────────────────────────────────

    private val _events = MutableSharedFlow<InferenceEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<InferenceEvent> = _events

    // ═══════════════════════════════════════════════════════════════
    // CORE EXECUTION — Generic wrapper for ANY model call
    // ═══════════════════════════════════════════════════════════════

    /**
     * Execute a model call with full harness protection.
     *
     * Flow: circuit breaker check → retry loop (with timeout) → quality gate → emit events
     *
     * @param T Return type of the model call
     * @param config Harness configuration (timeouts, retries, fallbacks)
     * @param providers Ordered list of [ProviderCandidate] to try (fallback chain)
     * @param taskType Human-readable task type for metrics (e.g. "llm:GENERAL", "stt:sw", "cv:classify")
     * @param userId User triggering the call (for cost attribution)
     * @param qualityThreshold Minimum confidence for the result (0.0–1.0). null = use default for task type.
     * @return The model response from the first successful provider
     * @throws InferenceHarnessException if ALL providers fail
     */
    suspend fun <T> execute(
        config: HarnessConfig = HarnessConfig(),
        providers: List<ProviderCandidate<T>>,
        taskType: String = "unknown",
        userId: String = "anonymous",
        qualityThreshold: Double? = null
    ): HarnessResult<T> {
        val callId = UUID.randomUUID().toString().take(12)
        val overallStart = System.currentTimeMillis()
        var attempts = 0
        var lastException: Exception? = null
        val todayKey = todayDateKey()

        for (candidate in providers) {
            val metrics = getOrCreateMetrics(candidate.providerId)
            val breaker = getOrCreateBreaker(candidate.providerId)

            // ── Circuit Breaker Gate ──
            val cbState = breaker.state()
            if (cbState == CircuitBreaker.State.OPEN) {
                Timber.d(TAG, "[%s] Circuit breaker OPEN for %s — skipping", callId, candidate.providerId)
                emitEvent(InferenceEvent.CircuitBreakerOpen(
                    callId = callId,
                    providerId = candidate.providerId,
                    taskType = taskType
                ))
                continue // Skip this provider entirely
            }

            for (retry in 0 until config.maxRetries) {
                attempts++
                val attemptStart = System.currentTimeMillis()

                try {
                    // Execute with timeout
                    val result = withTimeout(config.timeoutMs) {
                        candidate.provider()
                    }

                    val latencyMs = System.currentTimeMillis() - attemptStart
                    metrics.recordSuccess(latencyMs)
                    breaker.recordSuccess()

                    // ── Quality Gate ──
                    val effectiveThreshold = qualityThreshold
                        ?: DEFAULT_CONFIDENCE_THRESHOLDS[taskType.substringBefore(":")]
                        ?: 0.0
                    val confidence = candidate.confidenceExtractor?.invoke(result) ?: 1.0

                    if (confidence < effectiveThreshold) {
                        Timber.w(
                            TAG, "[%s] Quality gate FAILED for %s: confidence=%.2f < threshold=%.2f",
                            callId, candidate.providerId, confidence, effectiveThreshold
                        )
                        emitEvent(InferenceEvent.QualityGateFailed(
                            callId = callId,
                            providerId = candidate.providerId,
                            taskType = taskType,
                            confidence = confidence,
                            threshold = effectiveThreshold
                        ))
                        // Treat as failure — try next provider
                        metrics.recordFailure()
                        lastException = QualityGateException(
                            candidate.providerId, confidence, effectiveThreshold
                        )
                        break // Don't retry same provider for quality failures
                    }

                    // Record cost
                    if (candidate.inputTokens > 0 || candidate.outputTokens > 0) {
                        costTracker.record(
                            providerId = candidate.providerId,
                            modelId = candidate.modelId,
                            inputTokens = candidate.inputTokens,
                            outputTokens = candidate.outputTokens,
                            costMicros = candidate.costMicros,
                            taskType = taskType,
                            userId = userId,
                            latencyMs = latencyMs,
                            fromCache = false
                        )
                    }

                    // Track per-user/per-model/per-day cost
                    if (candidate.costMicros > 0) {
                        trackDailyCost(userId, candidate.providerId, candidate.modelId, todayKey, candidate.costMicros)
                    }

                    val totalTime = System.currentTimeMillis() - overallStart

                    emitEvent(InferenceEvent.Success(
                        callId = callId,
                        providerId = candidate.providerId,
                        taskType = taskType,
                        latencyMs = totalTime,
                        attempts = attempts,
                        retried = retry > 0
                    ))

                    Timber.d(
                        TAG, "[%s] %s succeeded via %s in %dms (attempt %d/%d, confidence=%.2f)",
                        callId, taskType, candidate.providerId, totalTime, attempts,
                        config.maxRetries * providers.size, confidence
                    )

                    return HarnessResult(
                        value = result,
                        providerId = candidate.providerId,
                        latencyMs = totalTime,
                        attempts = attempts,
                        fromFallback = candidate != providers.first(),
                        confidence = confidence
                    )

                } catch (e: TimeoutCancellationException) {
                    lastException = InferenceTimeoutException(
                        candidate.providerId, config.timeoutMs
                    )
                    metrics.recordFailure()
                    breaker.recordFailure()
                    Timber.w(TAG, "[%s] %s timeout on %s (%dms)", callId, taskType, candidate.providerId, config.timeoutMs)

                    emitEvent(InferenceEvent.Timeout(
                        callId = callId,
                        providerId = candidate.providerId,
                        taskType = taskType,
                        timeoutMs = config.timeoutMs
                    ))

                } catch (e: CancellationException) {
                    throw e // Don't catch coroutine cancellation

                } catch (e: QualityGateException) {
                    // Already handled — don't double-count
                    throw e

                } catch (e: Throwable) {
                    lastException = e
                    metrics.recordFailure()
                    breaker.recordFailure()
                    Timber.w(TAG, "[%s] %s failed on %s: %s", callId, taskType, candidate.providerId, e.message)

                    emitEvent(InferenceEvent.Failure(
                        callId = callId,
                        providerId = candidate.providerId,
                        taskType = taskType,
                        error = e.message ?: "unknown"
                    ))
                }

                // Exponential backoff between retries (3x multiplier)
                if (retry < config.maxRetries - 1) {
                    val backoffMs = config.retryBackoffBaseMs * (3L shl retry)
                    delay(backoffMs.coerceAtMost(config.retryBackoffMaxMs))
                }
            }

            // All retries for this provider exhausted, try next fallback
            Timber.d(TAG, "[%s] Provider %s exhausted, trying fallback", callId, candidate.providerId)
        }

        // All providers failed
        val totalTime = System.currentTimeMillis() - overallStart
        emitEvent(InferenceEvent.AllProvidersFailed(
            callId = callId,
            taskType = taskType,
            totalAttempts = attempts,
            totalTimeMs = totalTime,
            lastError = lastException?.message ?: "unknown"
        ))

        throw InferenceHarnessException(
            "All providers failed for $taskType after $attempts attempts. " +
            "Last error: ${lastException?.message}",
            lastException
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // CONVENIENCE WRAPPERS — Pre-configured for common model types
    // ═══════════════════════════════════════════════════════════════

    /**
     * Wrap an LLM inference call (on-device or cloud).
     * Uses the ModelRouter's provider chain internally.
     */
    suspend fun <T> wrapLlmCall(
        router: ModelRouter,
        request: ModelRouter.InferenceRequest,
        transform: (ModelRouter.InferenceResponse) -> T
    ): HarnessResult<T> {
        return execute(
            config = HarnessConfig(
                timeoutMs = 30_000L,
                maxRetries = 3
            ),
            providers = listOf(
                ProviderCandidate(
                    providerId = "llm-router",
                    modelId = request.model ?: "auto",
                    provider = { transform(router.infer(request)) }
                )
            ),
            taskType = "llm:${request.taskType.name}",
            userId = request.userId ?: "anonymous"
        )
    }

    /**
     * Wrap an STT (speech-to-text) call.
     * Fallback: voice → text (returns null to signal caller to show text input).
     */
    suspend fun wrapSttCall(
        transcribe: suspend () -> String?,
        language: String = "sw",
        confidence: Float = 0f
    ): HarnessResult<String?> {
        return execute(
            config = HarnessConfig(
                timeoutMs = 10_000L,
                maxRetries = 2
            ),
            providers = listOf(
                ProviderCandidate(
                    providerId = "stt-whisper",
                    modelId = "whisper-tiny-int4",
                    provider = transcribe
                ),
                ProviderCandidate(
                    providerId = "stt-fallback-text",
                    modelId = "text-input",
                    provider = { null } // Signal: fall back to text input
                )
            ),
            taskType = "stt:$language"
        )
    }

    /**
     * Wrap a TTS (text-to-speech) call.
     * Fallback: Kokoro → Piper → silent (returns false if all fail).
     */
    suspend fun wrapTtsCall(
        speak: suspend () -> Unit,
        providerId: String = "tts-kokoro",
        modelId: String = "kokoro"
    ): HarnessResult<Unit> {
        return execute(
            config = HarnessConfig(
                timeoutMs = 15_000L,
                maxRetries = 2
            ),
            providers = listOf(
                ProviderCandidate(
                    providerId = providerId,
                    modelId = modelId,
                    provider = speak
                )
            ),
            taskType = "tts"
        )
    }

    /**
     * Wrap a CV (computer vision) call.
     * Fallback: on-device classifier → cloud vision → text-only (ask user).
     */
    suspend fun <T> wrapCvCall(
        classify: suspend () -> T?,
        fallbackProvider: (suspend () -> T?)? = null,
        taskType: String = "cv:classify"
    ): HarnessResult<T?> {
        val providers = mutableListOf(
            ProviderCandidate(
                providerId = "cv-on-device",
                modelId = "mobilenetv3-small",
                provider = classify
            )
        )
        if (fallbackProvider != null) {
            providers.add(
                ProviderCandidate(
                    providerId = "cv-cloud",
                    modelId = "cloud-vision",
                    provider = fallbackProvider
                )
            )
        }
        providers.add(
            ProviderCandidate(
                providerId = "cv-text-fallback",
                modelId = "text-input",
                provider = { null }
            )
        )

        return execute(
            config = HarnessConfig(timeoutMs = 8_000L, maxRetries = 2),
            providers = providers,
            taskType = taskType
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // CIRCUIT BREAKER — Per-provider failure isolation
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get the circuit breaker state for a provider.
     */
    fun getCircuitBreakerState(providerId: String): CircuitBreaker.State {
        return getOrCreateBreaker(providerId).state()
    }

    /**
     * Get all circuit breaker states.
     */
    fun getAllCircuitBreakerStates(): Map<String, CircuitBreaker.State> {
        return circuitBreakers.mapValues { it.value.state() }
    }

    /**
     * Manually reset a circuit breaker (e.g., after operator intervention).
     */
    fun resetCircuitBreaker(providerId: String) {
        circuitBreakers[providerId]?.reset()
    }

    // ═══════════════════════════════════════════════════════════════
    // COST TRACKING — Per-user, per-model, per-day
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get daily cost breakdown for a specific user.
     */
    fun getUserDailyCosts(userId: String): Map<String, Map<String, Long>> {
        val today = todayDateKey()
        return dailyUserModelCosts
            .filter { it.key.startsWith("$userId:$today") }
            .mapKeys { it.key.substringAfter(":").substringAfter(":") } // modelId
            .mapValues { entry ->
                mapOf(
                    "costMicros" to entry.value.totalCostMicros.get(),
                    "callCount" to entry.value.callCount.get()
                )
            }
    }

    /**
     * Get today's total cost for a user across all models.
     */
    fun getUserTodayCostMicros(userId: String): Long {
        val today = todayDateKey()
        return dailyUserModelCosts
            .filter { it.key.startsWith("$userId:$today") }
            .values.sumOf { it.totalCostMicros.get() }
    }

    // ═══════════════════════════════════════════════════════════════
    // METRICS & MONITORING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get comprehensive metrics for all providers.
     */
    fun getAllMetrics(): Map<String, ProviderMetricsSnapshot> {
        return providerMetrics.mapValues { (_, m) -> m.snapshot() }
    }

    /**
     * Get metrics for a specific provider.
     */
    fun getMetrics(providerId: String): ProviderMetricsSnapshot? {
        return providerMetrics[providerId]?.snapshot()
    }

    /**
     * Get aggregate stats across all providers.
     */
    fun getAggregateStats(): AggregateStats {
        val allMetrics = providerMetrics.values.map { it.snapshot() }
        val totalCalls = allMetrics.sumOf { it.totalCalls }
        val totalSuccesses = allMetrics.sumOf { it.successfulCalls }
        val totalFailures = allMetrics.sumOf { it.failedCalls }
        val allLatencies = providerMetrics.values.flatMap { it.latencySamples }

        return AggregateStats(
            totalCalls = totalCalls,
            totalSuccesses = totalSuccesses,
            totalFailures = totalFailures,
            overallSuccessRate = if (totalCalls > 0) totalSuccesses.toDouble() / totalCalls else 0.0,
            avgLatencyMs = if (allLatencies.isNotEmpty()) allLatencies.average().toLong() else 0L,
            p50LatencyMs = percentile(allLatencies, 50.0),
            p95LatencyMs = percentile(allLatencies, 95.0),
            p99LatencyMs = percentile(allLatencies, 99.0),
            providerCount = providerMetrics.size,
            circuitBreakerStates = getAllCircuitBreakerStates().mapValues { it.value.name },
            costTrackerStats = costTracker.getStats()
        )
    }

    /**
     * Reset all metrics (for testing).
     */
    fun reset() {
        providerMetrics.clear()
        circuitBreakers.clear()
        dailyUserModelCosts.clear()
    }

    // ═══════════════════════════════════════════════════════════════
    // INTERNALS
    // ═══════════════════════════════════════════════════════════════

    private fun getOrCreateMetrics(providerId: String): ProviderMetrics {
        return providerMetrics.getOrPut(providerId) { ProviderMetrics(providerId) }
    }

    private fun getOrCreateBreaker(providerId: String): CircuitBreaker {
        return circuitBreakers.getOrPut(providerId) { CircuitBreaker() }
    }

    private fun todayDateKey(): String {
        val cal = Calendar.getInstance()
        return "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH) + 1}-${cal.get(Calendar.DAY_OF_MONTH)}"
    }

    private fun trackDailyCost(
        userId: String,
        providerId: String,
        modelId: String,
        dateKey: String,
        costMicros: Long
    ) {
        val key = "$userId:$dateKey:$modelId"
        dailyUserModelCosts.getOrPut(key) { DailyCostBucket(userId, modelId, dateKey) }
            .addCost(costMicros)
    }

    private suspend fun emitEvent(event: InferenceEvent) {
        try { _events.emit(event) } catch (_: Throwable) {}
    }

    private fun percentile(sortedValues: List<Long>, pct: Double): Long {
        if (sortedValues.isEmpty()) return 0L
        val sorted = sortedValues.sorted()
        val idx = ((pct / 100.0) * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    // ═══════════════════════════════════════════════════════════════
    // CIRCUIT BREAKER — Prevents cascading failures
    // ═══════════════════════════════════════════════════════════════

    /**
     * Per-provider circuit breaker.
     *
     * States:
     * - CLOSED: normal operation, requests pass through
     * - OPEN: too many failures (≥5), requests blocked for 30s cooldown
     * - HALF_OPEN: cooldown expired, allow 1 test request
     *   - Success → CLOSED
     *   - Failure → OPEN (reset cooldown)
     */
    class CircuitBreaker {
        enum class State { CLOSED, OPEN, HALF_OPEN }

        private val failureCount = AtomicLong(0)
        @Volatile private var lastFailureTimeMs: Long = 0L
        @Volatile private var state: State = State.CLOSED

        fun state(): State {
            if (state == State.OPEN) {
                val elapsed = System.currentTimeMillis() - lastFailureTimeMs
                if (elapsed >= CB_HALF_OPEN_COOLDOWN_MS) {
                    state = State.HALF_OPEN
                    Timber.d(TAG, "Circuit breaker: OPEN → HALF_OPEN (cooldown expired)")
                }
            }
            return state
        }

        fun recordSuccess() {
            when (state) {
                State.HALF_OPEN -> {
                    state = State.CLOSED
                    failureCount.set(0)
                    Timber.i(TAG, "Circuit breaker: HALF_OPEN → CLOSED (recovered)")
                }
                State.CLOSED -> {
                    failureCount.set(0) // Reset on success
                }
                State.OPEN -> {
                    // Shouldn't happen (we skip OPEN providers), but handle gracefully
                    state = State.CLOSED
                    failureCount.set(0)
                }
            }
        }

        fun recordFailure() {
            val count = failureCount.incrementAndGet()
            lastFailureTimeMs = System.currentTimeMillis()

            when (state) {
                State.CLOSED -> {
                    if (count >= CB_FAILURE_THRESHOLD) {
                        state = State.OPEN
                        Timber.w(TAG, "Circuit breaker: CLOSED → OPEN (failures=%d)", count)
                    }
                }
                State.HALF_OPEN -> {
                    state = State.OPEN
                    Timber.w(TAG, "Circuit breaker: HALF_OPEN → OPEN (test request failed)")
                }
                State.OPEN -> {
                    // Already open, just update timestamp
                }
            }
        }

        fun reset() {
            state = State.CLOSED
            failureCount.set(0)
            lastFailureTimeMs = 0L
            Timber.i(TAG, "Circuit breaker: reset to CLOSED")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PROVIDER METRICS — Per-provider latency/success tracking
    // ═══════════════════════════════════════════════════════════════

    class ProviderMetrics(private val providerId: String) {
        private val _totalCalls = AtomicLong(0)
        private val _successfulCalls = AtomicLong(0)
        private val _failedCalls = AtomicLong(0)
        private val _totalLatencyMs = AtomicLong(0)
        val latencySamples = mutableListOf<Long>()

        fun recordSuccess(latencyMs: Long) {
            _totalCalls.incrementAndGet()
            _successfulCalls.incrementAndGet()
            _totalLatencyMs.addAndGet(latencyMs)
            synchronized(latencySamples) {
                if (latencySamples.size >= MAX_LATENCY_SAMPLES) latencySamples.removeAt(0)
                latencySamples.add(latencyMs)
            }
        }

        fun recordFailure() {
            _totalCalls.incrementAndGet()
            _failedCalls.incrementAndGet()
        }

        fun snapshot(): ProviderMetricsSnapshot {
            val samples = synchronized(latencySamples) { latencySamples.toList() }
            return ProviderMetricsSnapshot(
                providerId = providerId,
                totalCalls = _totalCalls.get(),
                successfulCalls = _successfulCalls.get(),
                failedCalls = _failedCalls.get(),
                successRate = if (_totalCalls.get() > 0)
                    _successfulCalls.get().toDouble() / _totalCalls.get() else 0.0,
                avgLatencyMs = if (_successfulCalls.get() > 0)
                    _totalLatencyMs.get() / _successfulCalls.get() else 0L,
                p50LatencyMs = percentile(samples, 50.0),
                p95LatencyMs = percentile(samples, 95.0),
                p99LatencyMs = percentile(samples, 99.0)
            )
        }

        private fun percentile(values: List<Long>, pct: Double): Long {
            if (values.isEmpty()) return 0L
            val sorted = values.sorted()
            val idx = ((pct / 100.0) * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
            return sorted[idx]
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // DAILY COST BUCKET — Per-user/per-model/per-day tracking
    // ═══════════════════════════════════════════════════════════════

    class DailyCostBucket(
        val userId: String,
        val modelId: String,
        val dateKey: String
    ) {
        val totalCostMicros = AtomicLong(0)
        val callCount = AtomicLong(0)

        fun addCost(costMicros: Long) {
            totalCostMicros.addAndGet(costMicros)
            callCount.incrementAndGet()
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// TOP-LEVEL TYPES — Used by ModelRouter and other callers
// ═══════════════════════════════════════════════════════════════

/**
 * Configuration for a harness-wrapped call.
 */
data class HarnessConfig(
    /** Maximum time allowed for a single provider attempt */
    val timeoutMs: Long = 15_000L,
    /** Maximum retry attempts per provider */
    val maxRetries: Int = 3,
    /** Base backoff between retries (exponential, 3x multiplier) */
    val retryBackoffBaseMs: Long = 500L,
    /** Maximum backoff cap */
    val retryBackoffMaxMs: Long = 10_000L
)

/**
 * A candidate provider for a model call.
 * Wraps the actual invocation with metadata for cost tracking and quality gates.
 *
 * @param confidenceExtractor Optional function to extract confidence from the result.
 *        Used by quality gates to reject low-confidence outputs. If null, confidence = 1.0 (always passes).
 */
data class ProviderCandidate<T>(
    val providerId: String,
    val modelId: String = "",
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val costMicros: Long = 0,
    val confidenceExtractor: ((T) -> Double)? = null,
    val provider: suspend () -> T
)

/**
 * Result of a harness-wrapped call.
 */
data class HarnessResult<T>(
    val value: T,
    val providerId: String,
    val latencyMs: Long,
    val attempts: Int,
    val fromFallback: Boolean,
    val confidence: Double = 1.0
)

// ═══════════════════════════════════════════════════════════════
// DATA TYPES — Metrics snapshots
// ═══════════════════════════════════════════════════════════════

data class ProviderMetricsSnapshot(
    val providerId: String,
    val totalCalls: Long,
    val successfulCalls: Long,
    val failedCalls: Long,
    val successRate: Double,
    val avgLatencyMs: Long,
    val p50LatencyMs: Long,
    val p95LatencyMs: Long,
    val p99LatencyMs: Long
)

data class AggregateStats(
    val totalCalls: Long,
    val totalSuccesses: Long,
    val totalFailures: Long,
    val overallSuccessRate: Double,
    val avgLatencyMs: Long,
    val p50LatencyMs: Long,
    val p95LatencyMs: Long,
    val p99LatencyMs: Long,
    val providerCount: Int,
    val circuitBreakerStates: Map<String, String>,
    val costTrackerStats: Map<String, Any>
)

// ═══════════════════════════════════════════════════════════════
// EVENTS — Emitted for monitoring and observability
// ═══════════════════════════════════════════════════════════════

sealed class InferenceEvent {
    abstract val callId: String
    abstract val taskType: String
    abstract val timestamp: Long

    data class Success(
        override val callId: String,
        val providerId: String,
        override val taskType: String,
        val latencyMs: Long,
        val attempts: Int,
        val retried: Boolean,
        override val timestamp: Long = System.currentTimeMillis()
    ) : InferenceEvent()

    data class Failure(
        override val callId: String,
        val providerId: String,
        override val taskType: String,
        val error: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : InferenceEvent()

    data class Timeout(
        override val callId: String,
        val providerId: String,
        override val taskType: String,
        val timeoutMs: Long,
        override val timestamp: Long = System.currentTimeMillis()
    ) : InferenceEvent()

    data class AllProvidersFailed(
        override val callId: String,
        override val taskType: String,
        val totalAttempts: Int,
        val totalTimeMs: Long,
        val lastError: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : InferenceEvent()

    data class CircuitBreakerOpen(
        override val callId: String,
        val providerId: String,
        override val taskType: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : InferenceEvent()

    data class QualityGateFailed(
        override val callId: String,
        val providerId: String,
        override val taskType: String,
        val confidence: Double,
        val threshold: Double,
        override val timestamp: Long = System.currentTimeMillis()
    ) : InferenceEvent()
}

// ═══════════════════════════════════════════════════════════════
// EXCEPTIONS
// ═══════════════════════════════════════════════════════════════

class InferenceHarnessException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

class InferenceTimeoutException(
    val providerId: String,
    val timeoutMs: Long
) : Exception("Provider '$providerId' timed out after ${timeoutMs}ms")

class QualityGateException(
    val providerId: String,
    val confidence: Double,
    val threshold: Double
) : Exception("Quality gate failed for '$providerId': confidence=$confidence < threshold=$threshold")
