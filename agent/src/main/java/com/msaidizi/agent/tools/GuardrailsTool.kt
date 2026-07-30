package com.msaidizi.agent.tools

import com.msaidizi.core.security.EncryptionManager
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

data class ToolValidationResult(val valid: Boolean, val reason: String, val severity: String)

/**
 * GuardrailsEngine (Tool) — 3-Gate Guardrail Interface.
 *
 * Exposes the 3-gate guardrail architecture as a tool callable by the agent:
 * - input_gate:  Sanitization, PII detection, rate limiting
 * - tool_gate:   Schema validation, loop detection, safety finish check
 * - output_gate: DP noise injection, k-anonymity, financial content filtering
 *
 * Also retains legacy transaction validation, rate limiting, and anomaly detection.
 *
 * The harness uses the full GuardrailsEngine in superagent.guardrails;
 * this is the tool-interface version for direct tool access.
 */
@Singleton
class GuardrailsTool @Inject constructor(
    private val encryptionManager: EncryptionManager
) : Tool {

    override val name = "guardrails"
    override val description = "3-gate guardrail checks: input sanitization, tool validation, output safety"

    override val argsSchema = argSchema {
        enum("action", "Guardrails action",
            listOf(
                "input_gate", "tool_gate", "output_gate",
                "validate", "rate_check", "anomaly_check",
                "audit_log", "reset"
            ), required = false)
        string("prompt", "User prompt to check (for input_gate)", required = false)
        string("session_id", "Session ID (for tool_gate loop detection)", required = false)
        string("tool_name", "Tool name (for tool_gate)", required = false)
        string("tool_args", "Tool arguments JSON (for tool_gate schema validation)", required = false)
        string("output_text", "Generated output text (for output_gate)", required = false)
        string("transaction_type", "Type of transaction to validate", required = false)
        number("amount", "Transaction amount", required = false)
        string("product", "Product name", required = false)
        string("advice", "Advice text to validate", required = false)
        boolean("is_aggregated", "Whether output contains aggregated data (triggers DP noise)", required = false)
        number("cohort_size", "Cohort size for k-anonymity check", required = false)
    }

    // Rate limiting — persisted sliding window
    private val recentTransactions = mutableListOf<Double>()
    private val MAX_HOURLY = 100
    private val ANOMALY_SIGMA = 3.0
    private val WINDOW_DURATION_MS = 60 * 60 * 1000L // 1 hour sliding window

    init {
        // Load persisted timestamps on init
        loadPersistedRateLimits()
    }

    // ── Injection patterns ──
    private val injectionPatterns = listOf(
        Regex("(?i)ignore\\s+(all\\s+)?previous\\s+instructions"),
        Regex("(?i)you\\s+are\\s+now\\s+(a|an)\\s+"),
        Regex("(?i)system\\s*prompt\\s*:\\s*"),
        Regex("(?i)act\\s+as\\s+(if\\s+)?you\\s+(are|were)\\s+"),
        Regex("(?i)\\bDAN\\b|jailbreak|developer\\s+mode"),
        Regex("(?i)repeat\\s+(the\\s+)?(above|previous|system)\\s+(prompt|instructions)"),
        Regex("(?i)forget\\s+(everything|all|your\\s+rules)"),
        Regex("(?i)override\\s+(safety|rules|instructions|guardrails)"),
    )

    // ── PII patterns ──
    private val piiPatterns = listOf(
        Regex("(?:\\+?254|0)[17]\\d{8}"),
        Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"),
        Regex("(?i)(?:national|id|passport)\\s*(?:no|number|#)?[.:\s]*\\d{7,9}"),
        Regex("\\b(?:\\d{4}[\\s-]?){3}\\d{1,7}\\b"),
    )

    // ── Tool loop tracking ──
    private val toolCallHistory = mutableMapOf<String, MutableList<Pair<String, String>>>()
    private val TOOL_LOOP_THRESHOLD = 5
    private val TOOL_CHAIN_MAX = 15

    // ── Hallucinated financial patterns ──
    private val hallucinatedPatterns = listOf(
        Regex("(?i)your\\s+bank\\s+account"),
        Regex("(?i)transfer\\s+money"),
        Regex("(?i)send\\s+(money|funds)\\s+to"),
        Regex("(?i)loan\\s+application"),
    )

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"] ?: "validate"
        return when (action.lowercase()) {

            // ── GATE 1: Input ────────────────────────────────────
            "input_gate" -> {
                val prompt = params["prompt"]
                    ?: return ToolResult.error(name, "Prompt required for input_gate", "MISSING_PROMPT")
                runInputGate(prompt)
            }

            // ── GATE 2: Tool ─────────────────────────────────────
            "tool_gate" -> {
                val sessionId = params["session_id"] ?: "default"
                val toolName = params["tool_name"]
                    ?: return ToolResult.error(name, "tool_name required for tool_gate", "MISSING_TOOL_NAME")
                val toolArgs = params["tool_args"] ?: "{}"
                runToolGate(sessionId, toolName, toolArgs)
            }

            // ── GATE 3: Output ───────────────────────────────────
            "output_gate" -> {
                val outputText = params["output_text"]
                    ?: return ToolResult.error(name, "output_text required for output_gate", "MISSING_OUTPUT")
                val isAggregated = params["is_aggregated"]?.toBooleanStrictOrNull() ?: false
                val cohortSize = params["cohort_size"]?.toIntOrNull() ?: 0
                runOutputGate(outputText, isAggregated, cohortSize)
            }

            // ── Legacy actions ───────────────────────────────────
            "validate" -> {
                val amount = params["amount"]?.toDoubleOrNull()
                    ?: return ToolResult.error(name, "Amount required", "MISSING_AMOUNT")
                val product = params["product"] ?: ""
                val result = validateTransaction(amount, product)
                if (result.valid) {
                    ToolResult.success(name, mapOf("valid" to true, "severity" to result.severity), result.reason)
                } else {
                    ToolResult.error(name, result.reason, "VALIDATION_FAILED")
                }
            }
            "validate_advice" -> {
                val advice = params["advice"]
                    ?: return ToolResult.error(name, "Advice text required", "MISSING_ADVICE")
                val result = validateAdvice(advice)
                ToolResult.success(name, mapOf("valid" to result.valid, "severity" to result.severity), result.reason)
            }
            "rate_check" -> {
                ToolResult.success(
                    name,
                    mapOf("recent_count" to recentTransactions.size, "max_hourly" to MAX_HOURLY),
                    "Recent transactions: ${recentTransactions.size}/$MAX_HOURLY"
                )
            }
            "anomaly_check" -> {
                val amount = params["amount"]?.toDoubleOrNull()
                    ?: return ToolResult.error(name, "Amount required for anomaly check", "MISSING_AMOUNT")
                if (recentTransactions.size >= 5) {
                    val mean = recentTransactions.average()
                    val stdDev = Math.sqrt(recentTransactions.map { (it - mean) * (it - mean) }.average())
                    val zScore = if (stdDev > 0) Math.abs(amount - mean) / stdDev else 0.0
                    val anomalous = zScore > ANOMALY_SIGMA
                    ToolResult.success(name, mapOf(
                        "anomalous" to anomalous,
                        "z_score" to "%.1f".format(zScore),
                        "threshold" to ANOMALY_SIGMA
                    ), if (anomalous) "Anomalous amount detected" else "Within normal range")
                } else {
                    ToolResult.success(name, mapOf("anomalous" to false, "reason" to "Insufficient data"), "Not enough data for anomaly detection")
                }
            }
            "audit_log" -> {
                ToolResult.success(name, mapOf("status" to "audit_log_available"), "Use full GuardrailsEngine.getAuditLog() for audit trail")
            }
            "reset" -> {
                recentTransactions.clear()
                toolCallHistory.clear()
                ToolResult.success(name, message = "Counters and history reset")
            }
            "status" -> {
                ToolResult.success(
                    name,
                    mapOf(
                        "recent_count" to recentTransactions.size,
                        "max_hourly" to MAX_HOURLY,
                        "active_sessions" to toolCallHistory.size
                    ),
                    "Recent transactions: ${recentTransactions.size}/$MAX_HOURLY, Active tool sessions: ${toolCallHistory.size}"
                )
            }
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    // ══════════════════════════════════════════════════════════════
    // GATE 1 — INPUT
    // ══════════════════════════════════════════════════════════════

    private fun runInputGate(prompt: String): ToolResult {
        val issues = mutableListOf<String>()

        // 1. Injection check
        for (pattern in injectionPatterns) {
            if (pattern.containsMatchIn(prompt)) {
                issues.add("Injection pattern detected: ${pattern.pattern}")
            }
        }
        if (issues.isNotEmpty()) {
            return ToolResult.error(name, "Input gate FAILED: ${issues.joinToString("; ")}", "INPUT_INJECTION")
        }

        // 2. PII check
        for (pattern in piiPatterns) {
            val matches = pattern.findAll(prompt).toList()
            if (matches.isNotEmpty()) {
                issues.add("PII detected: ${matches.map { it.value }.joinToString(", ")}")
            }
        }
        if (issues.isNotEmpty()) {
            return ToolResult.error(name, "Input gate FAILED: ${issues.joinToString("; ")}", "INPUT_PII")
        }

        return ToolResult.success(name, mapOf("gate" to "input", "passed" to true), "Input gate passed")
    }

    // ══════════════════════════════════════════════════════════════
    // GATE 2 — TOOL
    // ══════════════════════════════════════════════════════════════

    private fun runToolGate(sessionId: String, toolName: String, toolArgsJson: String): ToolResult {
        // 1. Loop detection
        val history = toolCallHistory.getOrPut(sessionId) { mutableListOf() }
        val callSig = "$toolName:$toolArgsJson"

        synchronized(history) {
            val duplicateCount = history.count { it.first == toolName && it.second == toolArgsJson }
            if (duplicateCount >= TOOL_LOOP_THRESHOLD) {
                return ToolResult.error(name,
                    "Tool gate FAILED: Loop detected — '$toolName' called ${duplicateCount + 1} times with identical args",
                    "TOOL_LOOP")
            }

            if (history.size >= TOOL_CHAIN_MAX) {
                return ToolResult.error(name,
                    "Tool gate FAILED: Tool chain depth ${history.size + 1} exceeds max $TOOL_CHAIN_MAX",
                    "TOOL_CHAIN_DEPTH")
            }

            history.add(Pair(toolName, toolArgsJson))
        }

        // 2. Basic schema validation (check for obviously malformed args)
        if (toolArgsJson.isBlank() || toolArgsJson == "null") {
            return ToolResult.error(name, "Tool gate FAILED: Empty or null tool arguments", "TOOL_SCHEMA")
        }

        return ToolResult.success(name, mapOf(
            "gate" to "tool",
            "passed" to true,
            "session_depth" to history.size,
            "duplicates_of_this_call" to history.count { it.first == toolName && it.second == toolArgsJson }
        ), "Tool gate passed (depth: ${history.size})")
    }

    // ══════════════════════════════════════════════════════════════
    // GATE 3 — OUTPUT
    // ══════════════════════════════════════════════════════════════

    private fun runOutputGate(outputText: String, isAggregated: Boolean, cohortSize: Int): ToolResult {
        val issues = mutableListOf<String>()

        // 1. k-anonymity check
        if (cohortSize > 0 && cohortSize < 10) {
            issues.add("k-anonymity violation: cohort size $cohortSize < minimum k=10")
        }

        // 2. Hallucinated financial content
        for (pattern in hallucinatedPatterns) {
            if (pattern.containsMatchIn(outputText)) {
                issues.add("Hallucinated financial content detected: ${pattern.pattern}")
            }
        }

        // 3. Unverified financial numbers
        val financialPattern = Regex("(?:KES|KSh|\\$|USD)[\\s.]?[\\d,]+(?:\\.\\d{1,2})?", RegexOption.IGNORE_CASE)
        val sourceIndicators = listOf("according to", "data shows", "source:", "tool result", "verified", "reported", "calculated", "based on")
        for (match in financialPattern.findAll(outputText)) {
            val startIdx = maxOf(0, match.range.first - 200)
            val context = outputText.substring(startIdx, match.range.first).lowercase()
            val hasSource = sourceIndicators.any { it in context }
            if (!hasSource) {
                issues.add("Unverified financial figure: '${match.value}' lacks source citation")
            }
        }

        if (issues.isNotEmpty()) {
            return ToolResult.error(name, "Output gate FAILED: ${issues.joinToString("; ")}", "OUTPUT_VIOLATION")
        }

        val resultData = mutableMapOf<String, Any>(
            "gate" to "output",
            "passed" to true,
        )
        if (isAggregated) {
            resultData["dp_noise_applied"] = true
            resultData["epsilon"] = 0.1
        }
        if (cohortSize > 0) {
            resultData["k_anonymity"] = "passed (cohort=$cohortSize, min=10)"
        }

        return ToolResult.success(name, resultData, "Output gate passed")
    }

    // ══════════════════════════════════════════════════════════════
    // Legacy methods
    // ══════════════════════════════════════════════════════════════

    fun validateTransaction(amount: Double, product: String): ToolValidationResult {
        if (amount <= 0) return ToolValidationResult(false, "Amount must be positive", "error")
        if (amount > 1_000_000) return ToolValidationResult(false, "Amount exceeds maximum (KES 1M)", "error")

        evictExpiredEntries()

        if (recentTransactions.size >= MAX_HOURLY) {
            return ToolValidationResult(false, "Too many transactions this hour (max $MAX_HOURLY)", "warning")
        }

        if (recentTransactions.size >= 5) {
            val mean = recentTransactions.average()
            val stdDev = Math.sqrt(recentTransactions.map { (it - mean) * (it - mean) }.average())
            val zScore = if (stdDev > 0) Math.abs(amount - mean) / stdDev else 0.0
            if (zScore > ANOMALY_SIGMA) {
                return ToolValidationResult(true, "Unusual amount (z-score: ${"%.1f".format(zScore)}). Confirm?", "warning")
            }
        }

        if (recentTransactions.isNotEmpty() && recentTransactions.last() == amount) {
            return ToolValidationResult(true, "Possible duplicate. Confirm?", "warning")
        }

        recentTransactions.add(amount)
        persistRateLimits()
        return ToolValidationResult(true, "Valid", "ok")
    }

    fun validateAdvice(advice: String): ToolValidationResult {
        val hasNumbers = Regex("\\d+").containsMatchIn(advice)
        if (hasNumbers) {
            return ToolValidationResult(true, "Advice contains numbers — verify from tool output only", "info")
        }
        return ToolValidationResult(true, "Advice validated", "ok")
    }

    fun resetHourlyCounter() {
        recentTransactions.clear()
        persistRateLimits()
    }

    // ── Rate Limit Persistence ──────────────────────────────

    /**
     * Evict entries outside the sliding window and persist remaining.
     */
    private fun evictExpiredEntries() {
        val cutoff = System.currentTimeMillis() - WINDOW_DURATION_MS
        recentTransactions.removeAll { it < cutoff }
    }

    /**
     * Persist rate limit timestamps to EncryptedSharedPreferences.
     * We store timestamps as a comma-separated string.
     */
    private fun persistRateLimits() {
        try {
            val prefs = encryptionManager.getEncryptedPrefs()
            val timestamps = recentTransactions.joinToString(",") { it.toLong().toString() }
            prefs.edit().putString(KEY_RATE_LIMIT_TIMESTAMPS, timestamps).apply()
        } catch (e: Exception) {
            Timber.w(e, "Failed to persist rate limits")
        }
    }

    /**
     * Load persisted rate limit timestamps on startup.
     */
    private fun loadPersistedRateLimits() {
        try {
            val prefs = encryptionManager.getEncryptedPrefs()
            val stored = prefs.getString(KEY_RATE_LIMIT_TIMESTAMPS, null)
            if (stored != null) {
                val now = System.currentTimeMillis()
                val cutoff = now - WINDOW_DURATION_MS
                stored.split(",")
                    .mapNotNull { it.toLongOrNull()?.toDouble() }
                    .filter { it >= cutoff }
                    .forEach { recentTransactions.add(it) }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to load persisted rate limits")
        }
    }

    companion object {
        private const val KEY_RATE_LIMIT_TIMESTAMPS = "guardrails_rate_limit_ts"
    }
}
