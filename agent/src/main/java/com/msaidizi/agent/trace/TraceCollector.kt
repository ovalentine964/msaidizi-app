package com.msaidizi.agent.trace

import com.google.gson.Gson
import com.msaidizi.agent.harness.IntentType
import com.msaidizi.agent.harness.IntentTier
import com.msaidizi.agent.harness.UserIntent
import com.msaidizi.agent.loops.OODAResult
import com.msaidizi.agent.tools.ToolResult
import com.msaidizi.core.database.TraceDao
import com.msaidizi.core.model.TraceEntity
import timber.log.Timber
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TraceCollector — Captures structured traces for every agent run.
 *
 * This is the "sensor" of Loop 4 (harness improvement). Every agent run
 * produces a trace that records:
 *   - What intent was detected and how (pattern/embedding/LLM)
 *   - What tools were selected and whether they succeeded
 *   - How long each phase took
 *   - What the LLM produced
 *   - Whether the user later corrected or rephrased
 *
 * Traces are stored locally in Room and synced (anonymized) to the backend
 * for aggregate analysis by the TraceAnalysisAgent.
 *
 * Privacy: Raw input text is SHA-256 hashed. Only the hash, not the text,
 * leaves the device. Tool names, intent types, and timing are not PII.
 */
@Singleton
class TraceCollector @Inject constructor(
    private val traceDao: TraceDao,
    private val gson: Gson
) {
    // Active trace being built for the current agent run
    private var activeTrace: TraceBuilder? = null

    /**
     * Start a new trace for an agent run.
     * Call this at the beginning of SuperagentHarness.processInput().
     */
    fun startTrace(sessionId: String, rawInput: String, isVoice: Boolean): String {
        val traceId = UUID.randomUUID().toString()
        activeTrace = TraceBuilder(
            traceId = traceId,
            sessionId = sessionId,
            rawInput = rawInput,
            isVoice = isVoice,
            startTime = System.currentTimeMillis()
        )
        Timber.d("TraceCollector: started trace %s", traceId)
        return traceId
    }

    /**
     * Record intent classification result.
     */
    fun recordIntent(intent: UserIntent, tier: IntentTier, routingMs: Long) {
        activeTrace?.let { builder ->
            builder.intentType = intent.type
            builder.intentConfidence = intent.confidence
            builder.intentTier = tier
            builder.intentRoutingMs = routingMs
            Timber.d("TraceCollector: recorded intent %s (%s, %.2f, %dms)",
                intent.type, tier, intent.confidence, routingMs)
        }
    }

    /**
     * Record tool selection.
     */
    fun recordToolSelection(tools: List<String>, params: Map<String, Map<String, String>>) {
        activeTrace?.let { builder ->
            builder.toolsSelected = tools
            builder.toolParams = params
        }
    }

    /**
     * Record tool execution results.
     */
    fun recordToolResults(results: List<ToolResult>, executionMs: Long) {
        activeTrace?.let { builder ->
            builder.toolResults = results.map { result ->
                ToolExecutionTrace(
                    toolName = result.toolName,
                    success = result.success,
                    durationMs = 0, // individual tool timing not available from ToolResult
                    error = result.errorMessage,
                    outputSummary = result.message?.take(200)
                )
            }
            builder.toolsSucceeded = results.count { it.success }
            builder.toolsFailed = results.count { !it.success }
            builder.toolExecutionMs = executionMs
        }
    }

    /**
     * Record LLM inference metrics.
     */
    fun recordLlmInference(promptTokens: Int, outputTokens: Int, responseSummary: String, inferenceMs: Long) {
        activeTrace?.let { builder ->
            builder.promptTokenCount = promptTokens
            builder.outputTokenCount = outputTokens
            builder.llmResponseSummary = responseSummary.take(500)
            builder.llmInferenceMs = inferenceMs
        }
    }

    /**
     * Record OODA loop result.
     */
    fun recordOODAResult(result: OODAResult) {
        activeTrace?.let { builder ->
            builder.finalConfidence = result.confidence
            builder.oodaIterations = result.iterations
            builder.oodaPhase = result.terminatedBy.name
        }
    }

    /**
     * Record guardrails block.
     */
    fun recordGuardrailBlock() {
        activeTrace?.let { builder ->
            builder.guardrailBlocked = true
        }
    }

    /**
     * Finalize and persist the trace.
     * Call this at the end of SuperagentHarness.processInput().
     */
    suspend fun finishTrace(
        businessCategory: String? = null,
        region: String? = null
    ) {
        val builder = activeTrace ?: return
        val totalMs = System.currentTimeMillis() - builder.startTime

        val entity = TraceEntity(
            traceId = builder.traceId,
            sessionId = builder.sessionId,
            timestamp = builder.startTime,
            rawInputHash = sha256(builder.rawInput),
            intentType = builder.intentType?.name ?: "UNKNOWN",
            intentConfidence = builder.intentConfidence,
            intentTier = builder.intentTier?.name ?: "UNKNOWN",
            toolsSelected = gson.toJson(builder.toolsSelected),
            toolsSucceeded = builder.toolsSucceeded,
            toolsFailed = builder.toolsFailed,
            toolResultsJson = gson.toJson(builder.toolResults),
            promptTokenCount = builder.promptTokenCount,
            outputTokenCount = builder.outputTokenCount,
            llmResponseSummary = builder.llmResponseSummary,
            totalLatencyMs = totalMs,
            intentRoutingMs = builder.intentRoutingMs,
            toolExecutionMs = builder.toolExecutionMs,
            llmInferenceMs = builder.llmInferenceMs,
            finalConfidence = builder.finalConfidence,
            oodaIterations = builder.oodaIterations,
            guardrailBlocked = builder.guardrailBlocked,
            oodaPhase = builder.oodaPhase,
            isVoice = builder.isVoice,
            businessCategory = businessCategory,
            region = region,
            needsSync = true
        )

        try {
            traceDao.insert(entity)
            Timber.d("TraceCollector: trace %s saved (%dms, intent=%s, tools=%d/%d)",
                builder.traceId, totalMs, entity.intentType,
                builder.toolsSucceeded, builder.toolsFailed)
        } catch (e: Exception) {
            Timber.w(e, "TraceCollector: failed to save trace %s", builder.traceId)
        }

        activeTrace = null
    }

    /**
     * Record user feedback (positive/negative) for a trace.
     * Called when the user rates the response.
     */
    suspend fun recordFeedback(traceId: String, positive: Boolean) {
        try {
            traceDao.updateFeedback(traceId, if (positive) 1 else 0)
            Timber.d("TraceCollector: feedback recorded for %s: %s", traceId, positive)
        } catch (e: Exception) {
            Timber.w(e, "TraceCollector: failed to record feedback for %s", traceId)
        }
    }

    /**
     * Record user correction (rephrased/retried input).
     * Called when the user sends a follow-up within a short window
     * that suggests the previous response was unsatisfactory.
     */
    suspend fun recordCorrection(traceId: String, correction: String, latencyMs: Long) {
        try {
            traceDao.updateCorrection(traceId, correction, latencyMs)
            Timber.d("TraceCollector: correction recorded for %s", traceId)
        } catch (e: Exception) {
            Timber.w(e, "TraceCollector: failed to record correction for %s", traceId)
        }
    }

    /**
     * Get recent traces for local analysis.
     */
    suspend fun getRecentTraces(limit: Int = 50): List<TraceEntity> {
        return traceDao.getPendingSync(limit) // reuse for now
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Mutable builder for constructing a trace during an agent run.
 */
internal data class TraceBuilder(
    val traceId: String,
    val sessionId: String,
    val rawInput: String,
    val isVoice: Boolean,
    val startTime: Long,
    var intentType: IntentType? = null,
    var intentConfidence: Float = 0f,
    var intentTier: IntentTier? = null,
    var toolsSelected: List<String> = emptyList(),
    var toolParams: Map<String, Map<String, String>> = emptyMap(),
    var toolResults: List<ToolExecutionTrace> = emptyList(),
    var toolsSucceeded: Int = 0,
    var toolsFailed: Int = 0,
    var promptTokenCount: Int = 0,
    var outputTokenCount: Int = 0,
    var llmResponseSummary: String = "",
    var intentRoutingMs: Long = 0,
    var toolExecutionMs: Long = 0,
    var llmInferenceMs: Long = 0,
    var finalConfidence: Float = 0f,
    var oodaIterations: Int = 0,
    var guardrailBlocked: Boolean = false,
    var oodaPhase: String = "OBSERVE"
)
