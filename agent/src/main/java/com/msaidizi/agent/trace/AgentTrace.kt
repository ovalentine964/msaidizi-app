package com.msaidizi.agent.trace

import com.msaidizi.agent.harness.IntentType

/**
 * Structured trace of a single agent run.
 *
 * Captures the full pipeline: intent classification → tool selection →
 * tool execution → LLM inference → response generation → user feedback.
 *
 * Used by the FlywheelEngine's Loop 4 (harness improvement) to:
 *   - Identify "tool X selected but user corrects 40% of the time"
 *   - Identify "intent Y maps to wrong tool Z"
 *   - Generate improvement recommendations for IntentRouter weights
 *   - Generate improvement recommendations for ToolGraph edges
 */
data class AgentTrace(
    /** Unique trace ID (UUID) */
    val traceId: String,

    /** Session this trace belongs to */
    val sessionId: String,

    /** When the agent run started */
    val timestamp: Long,

    /** ── Intent Classification ────────────────────────────── */

    /** Raw user input text */
    val rawInput: String,

    /** Classified intent type */
    val intentType: IntentType,

    /** Intent classification confidence (0.0–1.0) */
    val intentConfidence: Float,

    /** Which tier resolved the intent: PATTERN, EMBEDDING, or LLM */
    val intentTier: IntentTier,

    /** ── Tool Selection ───────────────────────────────────── */

    /** Tools selected for execution */
    val toolsSelected: List<String>,

    /** Tool parameters passed */
    val toolParams: Map<String, Map<String, String>>,

    /** ── Tool Execution ───────────────────────────────────── */

    /** Per-tool execution results */
    val toolResults: List<ToolExecutionTrace>,

    /** Number of tools that succeeded */
    val toolsSucceeded: Int,

    /** Number of tools that failed */
    val toolsFailed: Int,

    /** ── LLM Inference ───────────────────────────────────── */

    /** System prompt token count (approximate) */
    val promptTokenCount: Int,

    /** LLM output token count */
    val outputTokenCount: Int,

    /** LLM response text (truncated to 500 chars for storage) */
    val llmResponseSummary: String,

    /** ── Timing ───────────────────────────────────────────── */

    /** Total latency in milliseconds */
    val totalLatencyMs: Long,

    /** Intent routing latency in ms */
    val intentRoutingMs: Long,

    /** Tool execution latency in ms */
    val toolExecutionMs: Long,

    /** LLM inference latency in ms */
    val llmInferenceMs: Long,

    /** ── Quality Signals ──────────────────────────────────── */

    /** Final response confidence from OODA loop */
    val finalConfidence: Float,

    /** Number of OODA iterations used */
    val oodaIterations: Int,

    /** Whether guardrails blocked the output */
    val guardrailBlocked: Boolean,

    /** ── User Feedback (post-hoc) ─────────────────────────── */

    /** User feedback: null = not yet rated, true = positive, false = negative */
    val userFeedback: Boolean? = null,

    /** User correction: if the user rephrased/retried, what was the new input? */
    val userCorrection: String? = null,

    /** Time until user correction (ms), null if no correction */
    val correctionLatencyMs: Long? = null,

    /** ── Context ──────────────────────────────────────────── */

    /** OODA phase when this trace was captured */
    val oodaPhase: String,

    /** Whether the request was voice input */
    val isVoice: Boolean,

    /** Device/anonymized metadata */
    val deviceMetadata: DeviceMetadata? = null
)

/**
 * Which tier of the IntentRouter resolved the intent.
 */
enum class IntentTier {
    /** Tier 1: Pattern matching (keyword/regex) — instant, zero cost */
    PATTERN,
    /** Tier 2: Embedding similarity (hash-trick) — CPU only */
    EMBEDDING,
    /** Tier 3: LLM function calling (Qwen 0.8B) — higher latency */
    LLM,
    /** Unknown / fallback */
    UNKNOWN
}

/**
 * Trace of a single tool execution within an agent run.
 */
data class ToolExecutionTrace(
    /** Tool name */
    val toolName: String,

    /** Whether execution succeeded */
    val success: Boolean,

    /** Execution duration in ms */
    val durationMs: Long,

    /** Error message if failed */
    val error: String? = null,

    /** Output summary (truncated) */
    val outputSummary: String? = null,

    /** Whether a circuit breaker was involved */
    val circuitBreakerTriggered: Boolean = false,

    /** Whether self-correction was needed */
    val selfCorrectionApplied: Boolean = false
)

/**
 * Anonymized device metadata for trace context.
 * No PII — only aggregate signals.
 */
data class DeviceMetadata(
    /** Business type category (e.g., "Trade", "Services") */
    val businessCategory: String,

    /** Region (e.g., "nairobi-eastlands") */
    val region: String? = null,

    /** App version */
    val appVersion: String = "unknown"
)
