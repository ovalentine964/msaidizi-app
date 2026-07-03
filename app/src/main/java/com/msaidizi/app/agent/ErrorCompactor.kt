package com.msaidizi.app.agent

import timber.log.Timber
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Error Compactor — Factor 9: Compact Errors into Context.
 *
 * Captures errors with full context, summarizes patterns, and injects
 * them into agent context for learning. Errors become training signal,
 * not just log noise.
 *
 * Designed for Android:
 * - Bounded memory (max errors capped)
 * - No background threads (synchronous operations)
 * - Fingerprinting for deduplication
 * - Frequency tracking with temporal decay
 */
class ErrorCompactor(
    private val agentName: String,
    private val maxErrors: Int = DEFAULT_MAX_ERRORS
) {
    companion object {
        const val DEFAULT_MAX_ERRORS = 50
        private const val RECENCY_HALF_LIFE_MS = 10 * 60 * 1000L // 10 minutes
    }

    // ── Error Storage ──────────────────────────────────────────────

    /** Active errors by fingerprint. */
    private val errors = ConcurrentHashMap<String, CompactedError>()

    /** Detected error patterns. */
    private val patterns = mutableListOf<ErrorPattern>()

    /** Recent fingerprints for burst detection. */
    private val recentFingerprints = mutableListOf<Pair<String, Long>>()

    /** Total errors captured (including deduplicated). */
    private var totalCaptured = 0

    /** Total deduplicated errors. */
    private var totalDeduplicated = 0

    // ═══════════════════════════════════════════════════════════════
    // CAPTURE
    // ═══════════════════════════════════════════════════════════════

    /**
     * Capture an error with full context.
     * If a matching fingerprint exists, increments occurrence count.
     *
     * @param errorType Exception class name or error category
     * @param message Error message
     * @param severity Error severity level
     * @param context Additional context (action, params, state)
     * @param stackTrace Optional stack trace
     * @return The captured or updated CompactedError
     */
    fun capture(
        errorType: String,
        message: String,
        severity: ErrorSeverity = ErrorSeverity.MEDIUM,
        context: Map<String, Any> = emptyMap(),
        stackTrace: String? = null,
        action: String? = null,
        tags: List<String> = emptyList()
    ): CompactedError {
        val fingerprint = computeFingerprint(errorType, message, action)

        val existing = errors[fingerprint]
        val error = if (existing != null) {
            // Deduplicate: update existing error
            existing.apply {
                occurrenceCount++
                lastSeen = System.currentTimeMillis()
                this.context.putAll(context)
            }
            totalDeduplicated++
            Timber.d("ErrorCompactor[%s]: Deduplicated '%s', count=%d",
                agentName, fingerprint.take(8), existing.occurrenceCount)
            existing
        } else {
            // New error
            val newError = CompactedError(
                errorType = errorType,
                message = message,
                severity = severity,
                fingerprint = fingerprint,
                context = context.toMutableMap(),
                stackTrace = stackTrace,
                agentName = agentName,
                action = action,
                tags = tags
            )
            errors[fingerprint] = newError
            totalCaptured++
            Timber.i("ErrorCompactor[%s]: Captured '%s' (%s)",
                agentName, errorType, severity.name)
            newError
        }

        // Track for pattern detection
        recentFingerprints.add(Pair(fingerprint, System.currentTimeMillis()))
        trimRecent()

        // Detect patterns
        detectPatterns()

        // Evict if over limit
        evictIfNeeded()

        return error
    }

    /**
     * Capture an error from a Kotlin/Java exception.
     */
    fun captureException(
        exception: Throwable,
        severity: ErrorSeverity = ErrorSeverity.MEDIUM,
        context: Map<String, Any> = emptyMap(),
        action: String? = null
    ): CompactedError {
        return capture(
            errorType = exception.javaClass.simpleName,
            message = exception.message ?: "Unknown error",
            severity = severity,
            context = context,
            stackTrace = exception.stackTraceToString(),
            action = action
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // CONTEXT INJECTION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get compacted errors for injection into agent context.
     * Returns the most important errors, sorted by importance.
     *
     * @param maxItems Maximum errors to return
     * @return List of compact error dicts for the think phase
     */
    fun getContextErrors(maxItems: Int = 5): List<Map<String, Any>> {
        return errors.values
            .filter { it.importanceScore >= 0.1 }
            .sortedByDescending { it.importanceScore }
            .take(maxItems)
            .map { it.toContextDict() }
    }

    /**
     * Get detected error patterns for agent context.
     */
    fun getContextPatterns(): List<Map<String, Any>> {
        return patterns.takeLast(5).map { pattern ->
            mapOf(
                "pattern" to pattern.patternType,
                "description" to pattern.description,
                "frequency" to pattern.frequency,
                "suggestedAction" to (pattern.suggestedAction ?: "")
            )
        }
    }

    /**
     * Get full error context for the agent's think phase.
     */
    fun getFullContext(): Map<String, Any> {
        return mapOf(
            "recentErrors" to getContextErrors(5),
            "errorPatterns" to getContextPatterns(),
            "stats" to getStats()
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // RESOLUTION TRACKING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Mark an error as resolved with a resolution description.
     * Resolutions are used for agent learning (what fixed what).
     *
     * @param fingerprint Error fingerprint
     * @param resolution Description of how the error was resolved
     * @return True if the error was found and marked resolved
     */
    fun resolve(fingerprint: String, resolution: String): Boolean {
        val error = errors[fingerprint] ?: return false
        error.resolution = resolution
        Timber.i("ErrorCompactor[%s]: Resolved '%s': %s",
            agentName, fingerprint.take(8), resolution)
        return true
    }

    /**
     * Get all resolved errors with their resolutions.
     * Used for building the agent's error recovery knowledge base.
     */
    fun getResolutions(): List<Map<String, Any>> {
        return errors.values
            .filter { it.resolution != null }
            .map { error ->
                mapOf(
                    "errorType" to error.errorType,
                    "fingerprint" to error.fingerprint.take(8),
                    "resolution" to requireNotNull(error.resolution) { "Resolution should be non-null after filter" },
                    "occurrences" to error.occurrenceCount
                )
            }
    }

    // ═══════════════════════════════════════════════════════════════
    // PATTERN DETECTION
    // ═══════════════════════════════════════════════════════════════

    private fun detectPatterns() {
        val now = System.currentTimeMillis()
        val windowMs = 60_000L // 1 minute

        val recent = recentFingerprints
            .filter { now - it.second < windowMs }
            .map { it.first }

        if (recent.size < 3) return

        // Burst detection
        if (recent.size >= 5) {
            addPattern(ErrorPattern(
                patternType = "error_burst",
                description = "${recent.size} errors in ${windowMs / 1000}s window",
                errorFingerprints = recent.distinct(),
                frequency = recent.size,
                timeWindowMs = windowMs,
                suggestedAction = "Check for upstream service degradation"
            ))
        }

        // Repeated same error
        val counts = recent.groupingBy { it }.eachCount()
        for ((fp, count) in counts) {
            if (count >= 3) {
                val error = errors[fp]
                addPattern(ErrorPattern(
                    patternType = "repeated_error",
                    description = "'${error?.errorType ?: fp}' repeated $count times",
                    errorFingerprints = listOf(fp),
                    frequency = count,
                    timeWindowMs = windowMs,
                    suggestedAction = "Investigate root cause of ${error?.errorType ?: fp}"
                ))
            }
        }
    }

    private fun addPattern(pattern: ErrorPattern) {
        // Deduplicate by type + first fingerprint
        val key = "${pattern.patternType}:${pattern.errorFingerprints.firstOrNull()}"
        val existingKeys = patterns.map { "${it.patternType}:${it.errorFingerprints.firstOrNull()}" }

        if (key !in existingKeys) {
            patterns.add(pattern)
            // Keep only last 20 patterns
            if (patterns.size > 20) {
                patterns.removeAt(0)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════

    private fun computeFingerprint(
        errorType: String,
        message: String,
        action: String?
    ): String {
        // Normalize: strip numbers, hex strings
        val normalized = message
            .replace(Regex("\\d+"), "N")
            .replace(Regex("[a-f0-9]{8,}"), "H")
        val raw = "$errorType:$normalized:${action ?: ""}"

        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun trimRecent() {
        if (recentFingerprints.size > 100) {
            val trimmed = recentFingerprints.takeLast(100)
            recentFingerprints.clear()
            recentFingerprints.addAll(trimmed)
        }
    }

    private fun evictIfNeeded() {
        if (errors.size <= maxErrors) return

        // Evict lowest importance
        val sorted = errors.values.sortedBy { it.importanceScore }
        val toEvict = errors.size - maxErrors
        for (error in sorted.take(toEvict)) {
            errors.remove(error.fingerprint)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // STATS
    // ═══════════════════════════════════════════════════════════════

    fun getStats(): Map<String, Any> {
        val severityCounts = errors.values.groupingBy { it.severity.name }.eachCount()
        return mapOf(
            "agent" to agentName,
            "activeErrors" to errors.size,
            "totalCaptured" to totalCaptured,
            "totalDeduplicated" to totalDeduplicated,
            "patternsDetected" to patterns.size,
            "severityDistribution" to severityCounts,
            "resolvedCount" to errors.values.count { it.resolution != null }
        )
    }
}

// ═══════════════════════════════════════════════════════════════
// DATA CLASSES
// ═══════════════════════════════════════════════════════════════

enum class ErrorSeverity {
    LOW,      // Recoverable, expected
    MEDIUM,   // Degraded functionality
    HIGH,     // Significant failure
    CRITICAL  // System-threatening
}

data class CompactedError(
    val errorType: String,
    val message: String,
    val severity: ErrorSeverity,
    val fingerprint: String,
    val context: MutableMap<String, Any> = mutableMapOf(),
    val stackTrace: String? = null,
    val agentName: String? = null,
    val action: String? = null,
    val tags: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
) {
    var occurrenceCount: Int = 1
    var firstSeen: Long = createdAt
    var lastSeen: Long = createdAt
    var resolution: String? = null

    val ageMs: Long get() = System.currentTimeMillis() - firstSeen

    /** Higher frequency = higher score. Capped at 1.0. */
    val frequencyScore: Double
        get() = min(1.0, occurrenceCount.toDouble() / 10.0)

    /** Exponential decay based on last occurrence. */
    val recencyScore: Double
        get() {
            val halfLife = 10 * 60 * 1000.0 // 10 minutes
            val age = (System.currentTimeMillis() - lastSeen).toDouble()
            return Math.pow(2.0, -age / halfLife)
        }

    /** Combined severity + frequency + recency importance score. */
    val importanceScore: Double
        get() {
            val severityWeight = when (severity) {
                ErrorSeverity.LOW -> 0.25
                ErrorSeverity.MEDIUM -> 0.5
                ErrorSeverity.HIGH -> 0.75
                ErrorSeverity.CRITICAL -> 1.0
            }
            return (0.4 * severityWeight) + (0.35 * frequencyScore) + (0.25 * recencyScore)
        }

    fun toContextDict(): Map<String, Any> {
        return mapOf(
            "errorFingerprint" to fingerprint.take(8),
            "type" to errorType,
            "message" to message.take(200),
            "severity" to severity.name,
            "occurrences" to occurrenceCount,
            "action" to (action ?: ""),
            "resolution" to (resolution ?: ""),
            "ageMinutes" to (ageMs / 60_000.0)
        )
    }
}

data class ErrorPattern(
    val patternType: String,
    val description: String,
    val errorFingerprints: List<String>,
    val frequency: Int,
    val timeWindowMs: Long,
    val suggestedAction: String? = null,
    val detectedAt: Long = System.currentTimeMillis()
)
