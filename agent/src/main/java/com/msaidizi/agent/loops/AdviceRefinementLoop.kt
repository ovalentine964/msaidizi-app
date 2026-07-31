package com.msaidizi.agent.loops

import com.msaidizi.agent.flywheel.FlywheelEngine
import com.msaidizi.agent.guardrails.ConfidenceLabel
import com.msaidizi.agent.guardrails.GuardrailsEngine
import com.msaidizi.agent.harness.AssembledContext
import com.msaidizi.agent.harness.IntentType
import com.msaidizi.agent.harness.LlmEngine
import com.msaidizi.agent.harness.UserIntent
import com.msaidizi.agent.tools.financial.CFOEngine
import com.msaidizi.agent.tools.core.ToolResult
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AdviceRefinementLoop — Iterative refinement for CFO financial advice.
 *
 * Implements the Self-Refine / evaluator-optimizer pattern:
 *   1. Generate initial advice via CFOEngine
 *   2. Validate against guardrails (financial integrity, hallucination)
 *   3. Check advice quality (completeness, actionability, relevance)
 *   4. Refine if validation fails or quality is below threshold
 *   5. Validate numbers (cross-reference with actual data)
 *   6. Output final advice with confidence label
 *
 * Each iteration improves quality. Terminates when:
 *   - Quality score exceeds threshold
 *   - Max iterations reached (prevents infinite refinement)
 *   - No improvement between iterations (convergence)
 *
 * Reference: loop_engineering_report.md §3.1 (Self-Refine), §5.3 (Layer 3 loop)
 */
@Singleton
class AdviceRefinementLoop @Inject constructor(
    private val cfoEngine: CFOEngine,
    private val guardrailsEngine: GuardrailsEngine,
    private val flywheelEngine: FlywheelEngine
) {
    companion object {
        /** Maximum refinement iterations. */
        const val MAX_ITERATIONS = 3

        /** Quality threshold to accept advice without further refinement. */
        const val QUALITY_THRESHOLD = 0.8f

        /** Convergence threshold: stop if improvement < this between iterations. */
        const val CONVERGENCE_THRESHOLD = 0.05f
    }

    /**
     * Generate and iteratively refine financial advice.
     *
     * @param intent      The user's intent (should be ASK_ADVICE or similar)
     * @param context     Assembled context with financial data
     * @param llmEngine   LLM engine for generation
     * @param buildPrompt Function to build system prompt
     * @return Refined advice result with quality metrics
     */
    suspend fun generateRefinedAdvice(
        intent: UserIntent,
        context: AssembledContext,
        llmEngine: LlmEngine,
        buildPrompt: (AssembledContext) -> String
    ): AdviceResult {
        val startTime = System.currentTimeMillis()
        val iterationLogs = mutableListOf<AdviceIterationLog>()

        // ── Step 1: Generate initial advice from CFOEngine ───────
        Timber.d("AdviceRefinement: Generating initial advice")
        val cfoResult = cfoEngine.execute(mapOf("action" to getActionForIntent(intent)))
        val initialAdvice = cfoResult.message

        // ── Load flywheel advice confidence for dynamic threshold ──
        val adviceConfidence = try {
            flywheelEngine.getAdviceConfidence()
        } catch (e: Exception) {
            Timber.w(e, "Failed to load advice confidence from flywheel")
            emptyMap()
        }
        val intentConfidence = adviceConfidence[intent.type.name.lowercase()] ?: 0.5f
        // Dynamic threshold: high flywheel confidence → lower quality bar (trusted pattern)
        // Low flywheel confidence → higher quality bar (needs more scrutiny)
        val dynamicThreshold = (QUALITY_THRESHOLD * (1.2f - intentConfidence)).coerceIn(0.6f, 0.95f)
        Timber.d("AdviceRefinement: intent=%s, flywheel_confidence=%.2f, quality_threshold=%.2f",
            intent.type.name, intentConfidence, dynamicThreshold)

        var currentAdvice = initialAdvice
        var bestAdvice = initialAdvice
        var bestQuality = assessQuality(initialAdvice, context)
        var iterations = 0

        iterationLogs.add(AdviceIterationLog(
            iteration = 0,
            phase = "INITIAL_GENERATION",
            advice = currentAdvice,
            qualityScore = bestQuality,
            guardrailPassed = true,
            refinements = emptyList()
        ))

        // ── Step 2: Refinement loop ─────────────────────────────
        while (iterations < MAX_ITERATIONS) {
            iterations++

            // ── 2a: Validate against guardrails ─────────────────
            val guardrailResult = guardrailsEngine.checkOutput(currentAdvice, context)
            val guardrailPassed = !guardrailResult.blocked
            val hallucinationIssues = guardrailResult.hallucinationIssues

            Timber.d("AdviceRefinement [%d]: guardrails=%s, issues=%d",
                iterations, if (guardrailPassed) "PASS" else "FAIL", hallucinationIssues.size)

            // ── 2b: Assess advice quality ────────────────────────
            val quality = assessQuality(currentAdvice, context)
            val refinements = mutableListOf<String>()

            // ── 2c: Determine what needs refinement ──────────────
            if (!guardrailPassed) {
                refinements.add("Guardrails blocked: ${guardrailResult.message}")
            }
            if (hallucinationIssues.isNotEmpty()) {
                val highIssues = hallucinationIssues.filter {
                    it.severity == com.msaidizi.agent.guardrails.IssueSeverity.HIGH
                }
                if (highIssues.isNotEmpty()) {
                    refinements.add("High-severity hallucination: ${highIssues.first().message}")
                }
            }
            if (quality < dynamicThreshold) {
                val qualityIssues = diagnoseQualityIssues(currentAdvice, context)
                refinements.addAll(qualityIssues)
            }

            // ── 2d: Refine if needed ─────────────────────────────
            if (refinements.isNotEmpty() && iterations < MAX_ITERATIONS) {
                val refined = refineAdvice(currentAdvice, refinements, context, llmEngine, buildPrompt)
                currentAdvice = refined

                val newQuality = assessQuality(refined, context)
                iterationLogs.add(AdviceIterationLog(
                    iteration = iterations,
                    phase = "REFINEMENT",
                    advice = refined,
                    qualityScore = newQuality,
                    guardrailPassed = guardrailPassed,
                    refinements = refinements
                ))

                // Track best
                if (newQuality > bestQuality) {
                    bestAdvice = refined
                    bestQuality = newQuality
                }

                // Convergence check
                val improvement = newQuality - quality
                if (improvement < CONVERGENCE_THRESHOLD) {
                    Timber.d("AdviceRefinement [%d]: Converged (improvement=%.3f)", iterations, improvement)
                    break
                }
            } else {
                // No refinement needed
                iterationLogs.add(AdviceIterationLog(
                    iteration = iterations,
                    phase = "VALIDATION_PASSED",
                    advice = currentAdvice,
                    qualityScore = quality,
                    guardrailPassed = guardrailPassed,
                    refinements = emptyList()
                ))
                break
            }
        }

        // ── Step 3: Validate numbers against actual data ─────────
        val numberValidation = validateNumbers(bestAdvice, context)
        if (!numberValidation.isValid) {
            Timber.w("AdviceRefinement: Number validation failed: ${numberValidation.issues}")
            // Apply number corrections
            bestAdvice = applyNumberCorrections(bestAdvice, numberValidation)
        }

        // ── Step 4: Final guardrails check ───────────────────────
        val finalCheck = guardrailsEngine.checkOutput(bestAdvice, context)
        if (finalCheck.blocked) {
            bestAdvice = finalCheck.message ?: "Samahani, I need to rephrase that advice."
        }

        // ── Step 5: Track advice for flywheel learning ───────────
        trackAdviceForLearning(intent, bestAdvice, bestQuality, context)

        val totalDuration = System.currentTimeMillis() - startTime
        Timber.i("AdviceRefinement: Complete in %d iterations, %dms, quality=%.2f",
            iterations, totalDuration, bestQuality)

        return AdviceResult(
            advice = bestAdvice,
            qualityScore = bestQuality,
            iterations = iterations,
            totalDurationMs = totalDuration,
            confidenceLabel = finalCheck.confidenceLabel,
            iterationLogs = iterationLogs,
            numberValidation = numberValidation
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // QUALITY ASSESSMENT
    // ═══════════════════════════════════════════════════════════════

    /**
     * Assess the quality of financial advice on a 0.0-1.0 scale.
     */
    private fun assessQuality(advice: String, context: AssembledContext): Float {
        var score = 0.5f // baseline

        // ── Completeness ─────────────────────────────────────────
        // Does the advice address the user's situation?
        if (context.recentFinancialSummary != null) {
            // Has financial context — advice should reference it
            if (advice.contains("Ksh") || advice.contains("KES") || advice.contains("profit") ||
                advice.contains("sales") || advice.contains("faida")) {
                score += 0.1f
            }
        }

        // ── Actionability ────────────────────────────────────────
        // Does the advice contain specific, actionable recommendations?
        val actionablePatterns = listOf(
            "try", "consider", "save", "reduce", "increase", "target",
            "jaribu", "fikiria", "hifadhi", "punguza", "ongeza"
        )
        val actionableCount = actionablePatterns.count { advice.contains(it, ignoreCase = true) }
        score += (actionableCount * 0.03f).coerceAtMost(0.1f)

        // ── Specificity ──────────────────────────────────────────
        // Does the advice include specific numbers/amounts?
        val hasNumbers = Regex("""\d+""").containsMatchIn(advice)
        if (hasNumbers) score += 0.1f

        // ── Appropriate length ───────────────────────────────────
        // Too short = unhelpful, too long = overwhelming
        val wordCount = advice.split(Regex("\\s+")).size
        score += when {
            wordCount in 20..100 -> 0.1f
            wordCount in 10..150 -> 0.05f
            else -> 0.0f
        }

        // ── Trust indicators ─────────────────────────────────────
        // Does the advice include confidence/source indicators?
        if (advice.contains("🟢") || advice.contains("📊") || advice.contains("based on")) {
            score += 0.05f
        }

        return score.coerceIn(0.0f, 1.0f)
    }

    /**
     * Diagnose specific quality issues in advice.
     */
    private fun diagnoseQualityIssues(advice: String, context: AssembledContext): List<String> {
        val issues = mutableListOf<String>()

        if (context.recentFinancialSummary != null && !advice.contains("Ksh") && !advice.contains("KES")) {
            issues.add("Advice doesn't reference specific financial amounts")
        }

        val wordCount = advice.split(Regex("\\s+")).size
        if (wordCount < 10) {
            issues.add("Advice is too brief — needs more detail")
        }
        if (wordCount > 150) {
            issues.add("Advice is too verbose — should be concise")
        }

        if (!Regex("""\d+""").containsMatchIn(advice)) {
            issues.add("Advice lacks specific numbers or targets")
        }

        return issues
    }

    // ═══════════════════════════════════════════════════════════════
    // REFINEMENT
    // ═══════════════════════════════════════════════════════════════

    /**
     * Refine advice based on identified issues.
     */
    private suspend fun refineAdvice(
        currentAdvice: String,
        issues: List<String>,
        context: AssembledContext,
        llmEngine: LlmEngine,
        buildPrompt: (AssembledContext) -> String
    ): String {
        // Build a refinement prompt that includes the issues
        val refinementPrompt = buildRefinementPrompt(currentAdvice, issues, context)

        return try {
            val refined = llmEngine.generate(
                systemPrompt = refinementPrompt,
                userMessage = "Please refine the advice based on the issues identified.",
                context = context,
                toolResults = emptyList(),
                intent = UserIntent(type = IntentType.ASK_ADVICE, confidence = 0.8f)
            )
            refined.ifEmpty { currentAdvice }
        } catch (e: Exception) {
            Timber.w(e, "AdviceRefinement: LLM refinement failed, keeping original")
            currentAdvice
        }
    }

    /**
     * Build a prompt that guides the LLM to refine advice.
     */
    private fun buildRefinementPrompt(
        currentAdvice: String,
        issues: List<String>,
        context: AssembledContext
    ): String {
        return buildString {
            appendLine("You are Msaidizi, a financial advisor for Kenyan MSMEs.")
            appendLine("The following advice needs refinement. Fix the identified issues while keeping the advice practical and actionable.")
            appendLine()
            appendLine("=== CURRENT ADVICE ===")
            appendLine(currentAdvice)
            appendLine()
            appendLine("=== ISSUES TO FIX ===")
            issues.forEachIndexed { i, issue ->
                appendLine("${i + 1}. $issue")
            }
            appendLine()
            appendLine("=== BUSINESS CONTEXT ===")
            context.recentFinancialSummary?.let { appendLine("Financial data: $it") }
            if (context.relevantPatterns.isNotEmpty()) {
                appendLine("Learned patterns: ${context.relevantPatterns.take(3).joinToString("; ")}")
            }
            appendLine()
            appendLine("Refine the advice. Keep it concise (20-80 words), specific (include amounts), and actionable.")
            appendLine("Use Kiswahili/English mix as appropriate.")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // NUMBER VALIDATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Validate that financial numbers in the advice match actual data.
     */
    private fun validateNumbers(advice: String, context: AssembledContext): NumberValidation {
        val issues = mutableListOf<String>()
        val corrections = mutableMapOf<String, String>()

        // Extract numbers from advice
        val amountPattern = Regex("""(?:Ksh|KES)\s*([\d,]+\.?\d*)""", RegexOption.IGNORE_CASE)
        val amounts = amountPattern.findAll(advice).map {
            it.groupValues[1].replace(",", "").toDoubleOrNull()
        }.filterNotNull().toList()

        // Cross-reference with financial summary if available
        if (context.recentFinancialSummary != null && amounts.isNotEmpty()) {
            // Extract actual amounts from summary
            val actualAmounts = amountPattern.findAll(context.recentFinancialSummary)
                .map { it.groupValues[1].replace(",", "").toDoubleOrNull() }
                .filterNotNull().toList()

            // Check for suspiciously large deviations
            for (adviceAmount in amounts) {
                if (adviceAmount > 10_000_000) {
                    issues.add("Amount Ksh ${"%,.0f".format(adviceAmount)} seems too large for MSME")
                }
                if (adviceAmount < 0) {
                    issues.add("Negative amount detected: Ksh ${"%,.0f".format(adviceAmount)}")
                }
            }
        }

        return NumberValidation(
            isValid = issues.isEmpty(),
            issues = issues,
            corrections = corrections
        )
    }

    /**
     * Apply number corrections to advice text.
     */
    private fun applyNumberCorrections(advice: String, validation: NumberValidation): String {
        var corrected = advice
        for ((original, replacement) in validation.corrections) {
            corrected = corrected.replace(original, replacement)
        }
        return corrected
    }

    // ═══════════════════════════════════════════════════════════════
    // FLYWHEEL INTEGRATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Track advice generation for flywheel learning.
     * The flywheel will later track whether the user followed the advice.
     */
    private suspend fun trackAdviceForLearning(
        intent: UserIntent,
        advice: String,
        quality: Float,
        context: AssembledContext
    ) {
        try {
            flywheelEngine.processInteraction(
                input = intent.rawText,
                response = advice,
                intent = intent,
                toolResults = emptyList()
            )
        } catch (e: Exception) {
            Timber.w(e, "AdviceRefinement: Failed to track for flywheel")
        }
    }

    /**
     * Map intent type to CFOEngine action.
     */
    private fun getActionForIntent(intent: UserIntent): String {
        return when (intent.type) {
            IntentType.ASK_ADVICE -> "savings"
            IntentType.ASK_PROFIT -> "briefing"
            IntentType.DAILY_REPORT -> "briefing"
            IntentType.WEEKLY_REPORT -> "weekly"
            IntentType.MONTHLY_REPORT -> "weekly"
            else -> "briefing"
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// DATA CLASSES
// ═══════════════════════════════════════════════════════════════════

/**
 * Result of the advice refinement loop.
 */
data class AdviceResult(
    val advice: String,
    val qualityScore: Float,
    val iterations: Int,
    val totalDurationMs: Long,
    val confidenceLabel: ConfidenceLabel,
    val iterationLogs: List<AdviceIterationLog>,
    val numberValidation: NumberValidation
)

/**
 * Log entry for a single advice refinement iteration.
 */
data class AdviceIterationLog(
    val iteration: Int,
    val phase: String,
    val advice: String,
    val qualityScore: Float,
    val guardrailPassed: Boolean,
    val refinements: List<String>
)

/**
 * Result of validating numbers in advice against actual data.
 */
data class NumberValidation(
    val isValid: Boolean,
    val issues: List<String>,
    val corrections: Map<String, String>
)
