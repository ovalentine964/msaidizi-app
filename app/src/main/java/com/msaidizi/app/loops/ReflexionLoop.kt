package com.msaidizi.app.loops

import timber.log.Timber
import java.util.UUID

/**
 * Reflexion Loop — Self-improvement through self-critique.
 *
 * After producing a response, the agent critiques its own output.
 * If quality is below threshold, it revises and retries with
 * the critique as feedback.
 *
 * This closes the gap between "the agent reflected" and
 * "the reflection actually changed behavior."
 *
 * ## Reflexion Flow
 * ```
 * execute → critique → (revise → execute → critique)* → accept
 * ```
 *
 * ## Theoretical Foundation
 *
 * ### ECO 315 — Econometrics
 * - **Model Diagnostics (§1.4):** Just as econometricians check residuals
 *   for heteroscedasticity and autocorrelation, the Reflexion loop checks
 *   the agent's output for quality issues.
 * - **Iterative Improvement:** Each critique-revise cycle is like an
 *   iteration in a numerical optimization algorithm (e.g., Newton-Raphson),
 *   converging toward the optimal response.
 *
 * ### STA 244 — Applied Statistics
 * - **Goodness of Fit:** The critique score measures how well the response
 *   fits the user's intent and context.
 * - **Hypothesis Testing:** Each critique tests H₀: "The response is good enough"
 *   against H₁: "The response needs improvement."
 *
 * @see ReActLoop for the reasoning trace that Reflexion builds on
 */
data class Critique(
    val critiqueId: String = UUID.randomUUID().toString().take(12),
    val score: Double,              // 0.0 – 1.0 quality score
    val issues: List<String> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val shouldRetry: Boolean = false,
    val revisionPlan: String = ""
) {
    fun toMap(): Map<String, Any> = mapOf(
        "critiqueId" to critiqueId,
        "score" to score,
        "issues" to issues,
        "suggestions" to suggestions,
        "shouldRetry" to shouldRetry,
        "revisionPlan" to revisionPlan
    )
}

/**
 * Result of a Reflexion-enhanced execution.
 */
data class ReflexionResult<T>(
    val result: T,
    val critiques: List<Critique>,
    val attempts: Int,
    val finalScore: Double,
    val success: Boolean
)

/**
 * Reflexion Loop Manager — wraps response generation with self-critique.
 *
 * Usage:
 * ```
 * val result = reflexionLoop.execute(
 *     task = "Generate sale confirmation",
 *     qualityThreshold = 0.7,
 *     maxRetries = 2
 * ) {
 *     // Generate the response
 *     generateSaleResponse(sale)
 * }
 * ```
 *
 * The agent critiques its own output and retries if quality is low,
 * injecting the critique as feedback for the next attempt.
 */
class ReflexionLoop(
    private val maxCritiqueHistory: Int = 50
) {
    private val critiqueHistory = mutableListOf<Critique>()

    /**
     * Execute with Reflexion — self-critique and retry loop.
     *
     * @param task Description of the task
     * @param qualityThreshold Minimum acceptable quality score
     * @param maxRetries Maximum number of retry attempts
     * @param critiqueFn Function to evaluate result quality
     * @param executeFn Function to execute (receives critique feedback on retries)
     * @return ReflexionResult with the final output and critique history
     */
    suspend fun <T> execute(
        task: String,
        qualityThreshold: Double = 0.7,
        maxRetries: Int = 2,
        critiqueFn: suspend (T) -> Critique,
        executeFn: suspend (previousCritique: Critique?) -> T
    ): ReflexionResult<T> {
        val critiques = mutableListOf<Critique>()
        var attempt = 0
        var lastResult: T? = null
        var lastCritique: Critique? = null

        while (attempt <= maxRetries) {
            attempt++

            // Execute with optional critique feedback
            val result = executeFn(lastCritique)
            lastResult = result

            // Critique the result
            val critique = critiqueFn(result)
            critiques.add(critique)
            critiqueHistory.add(critique)
            lastCritique = critique

            Timber.d(
                "Reflexion critique: attempt=%d, score=%.2f, shouldRetry=%b",
                attempt, critique.score, critique.shouldRetry
            )

            // If quality is acceptable, stop
            if (critique.score >= qualityThreshold) {
                Timber.d("Reflexion: quality accepted (%.2f >= %.2f)", critique.score, qualityThreshold)
                break
            }

            // If max retries reached, stop
            if (attempt > maxRetries) {
                Timber.w(
                    "Reflexion: max retries reached. Best score: %.2f",
                    critiques.maxOfOrNull { it.score } ?: 0.0
                )
                break
            }

            Timber.d(
                "Reflexion: retrying (score=%.2f < threshold=%.2f). Revision: %s",
                critique.score, qualityThreshold, critique.revisionPlan
            )
        }

        // Trim history
        while (critiqueHistory.size > maxCritiqueHistory) {
            critiqueHistory.removeAt(0)
        }

        @Suppress("UNCHECKED_CAST")
        return ReflexionResult(
            result = lastResult as T,
            critiques = critiques,
            attempts = attempt,
            finalScore = critiques.lastOrNull()?.score ?: 0.0,
            success = (critiques.lastOrNull()?.score ?: 0.0) >= qualityThreshold
        )
    }

    /**
     * Critique a response for quality.
     *
     * Checks for:
     * - Completeness (all expected fields present)
     * - Language correctness
     * - Formatting
     * - Error indicators
     */
    fun critiqueResponse(
        response: String,
        expectedLanguage: String = "sw",
        minLength: Int = 10,
        maxLength: Int = 2000
    ): Critique {
        val issues = mutableListOf<String>()
        val suggestions = mutableListOf<String>()
        var score = 1.0

        // Check for errors
        if (response.contains("⚠️") || response.contains("error", ignoreCase = true)) {
            score -= 0.4
            issues.add("Response contains error indicators")
        }

        // Check length
        if (response.length < minLength) {
            score -= 0.3
            issues.add("Response too short (${response.length} < $minLength)")
            suggestions.add("Provide more detail in the response")
        }
        if (response.length > maxLength) {
            score -= 0.1
            issues.add("Response too long (${response.length} > $maxLength)")
            suggestions.add("Shorten the response for WhatsApp delivery")
        }

        // Check for empty content
        if (response.isBlank()) {
            score -= 0.5
            issues.add("Response is empty")
        }

        // Check for language consistency
        if (expectedLanguage == "sw" && response.contains(Regex("^[A-Za-z\\s]+$"))) {
            // All ASCII — might be English when Swahili was expected
            score -= 0.05
            suggestions.add("Consider using Swahili for Swahili-speaking users")
        }

        score = score.coerceIn(0.0, 1.0)

        return Critique(
            score = score,
            issues = issues,
            suggestions = suggestions,
            shouldRetry = score < 0.7,
            revisionPlan = suggestions.joinToString("; ").ifBlank { "No changes needed" }
        )
    }

    /**
     * Critique a transaction recording for accuracy.
     */
    fun critiqueTransaction(
        item: String?,
        amount: Double?,
        quantity: Double?
    ): Critique {
        val issues = mutableListOf<String>()
        val suggestions = mutableListOf<String>()
        var score = 1.0

        if (item.isNullOrBlank()) {
            score -= 0.3
            issues.add("Missing item name")
            suggestions.add("Ask user to specify the item")
        }

        if (amount == null || amount <= 0) {
            score -= 0.4
            issues.add("Invalid or missing amount")
            suggestions.add("Ask user for the price")
        }

        if (quantity == null || quantity <= 0) {
            score -= 0.1
            issues.add("Missing quantity — defaulting to 1")
        }

        // Check for suspiciously high amounts
        if (amount != null && amount > 1_000_000) {
            score -= 0.2
            issues.add("Unusually high amount: KSh $amount")
            suggestions.add("Confirm the amount with the user")
        }

        score = score.coerceIn(0.0, 1.0)

        return Critique(
            score = score,
            issues = issues,
            suggestions = suggestions,
            shouldRetry = score < 0.7,
            revisionPlan = suggestions.joinToString("; ").ifBlank { "Transaction data acceptable" }
        )
    }

    /**
     * Get recent critiques for analysis.
     */
    fun getCritiqueHistory(n: Int = 10): List<Map<String, Any>> =
        critiqueHistory.takeLast(n).map { it.toMap() }

    /**
     * Get average critique score.
     */
    fun getAverageScore(): Double =
        if (critiqueHistory.isEmpty()) 0.0
        else critiqueHistory.map { it.score }.average()

    /**
     * Get critique history count.
     */
    fun getCritiqueCount(): Int = critiqueHistory.size
}
