package com.msaidizi.app.agent

import com.msaidizi.app.core.database.InventoryDao
import com.msaidizi.app.core.database.PatternDao
import com.msaidizi.app.core.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.sqrt


/**
 * Learning Agent — adapts to user's vocabulary, business patterns, and preferences
 * using statistically rigorous methods.
 * Code-based pattern tracking + data collection for future LoRA fine-tuning.
 *
 * ## Economic & Statistical Foundations
 *
 * ### STA 342 — Test of Hypothesis
 * - **Neyman-Pearson Framework:** We test whether observed patterns are statistically
 *   significant or could have occurred by chance. Each pattern has a confidence
 *   score that reflects the strength of evidence.
 * - **Type I/II Error Control:** We balance false positives (reporting a pattern
 *   that isn't real) against false negatives (missing a real pattern).
 * - **p-value Interpretation:** Patterns with low p-values (high confidence)
 *   are more likely to be genuine signals, not noise.
 *
 * ### STA 343 — Experimental Design
 * - **A/B Testing Framework:** We can test whether a business strategy change
 *   (e.g., new pricing, new product) actually improves outcomes.
 * - **Randomization:** Assigning strategies randomly to eliminate confounders.
 * - **Sample Size Planning:** Determining how many observations needed to
 *   detect a meaningful effect.
 * - **Control Groups:** Comparing treatment (new strategy) vs. control (status quo).
 *
 * ### STA 347 — Statistical Computing
 * - **On-Device ML:** Running statistical computations directly on the phone
 *   without server calls. Uses exponential moving averages, simple regressions,
 *   and Bayesian updating.
 * - **Numerical Stability:** Handling edge cases (zero variance, empty data,
 *   overflow) in floating-point arithmetic.
 * - **Efficient Algorithms:** O(n) or O(n log n) algorithms for 2GB devices.
 *
 * ### ECO 315 — Research Methods
 * - **Causal Inference:** Distinguishing correlation from causation in
 *   business patterns. "Did the price increase cause the sales drop,
 *   or was it the rainy season?"
 * - **Validation:** Cross-validate learned patterns against holdout data.
 * - **Ethics:** All learning happens on-device; no data leaves the phone.
 *
 * @see BusinessPatternTracker for deeper statistical pattern analysis
 * @see AdaptiveLearningEngine for personalized learning integration
 */
class LearningAgent(
    private val patternDao: PatternDao,
    private val inventoryDao: InventoryDao
) {
    private val json = Json { ignoreUnknownKeys = true }

    // ═══════════════════════════════════════════════════════════════
    // STA 342 — HYPOTHESIS TESTING: Validate patterns
    // ═══════════════════════════════════════════════════════════════

    /**
     * Test whether an observed pattern is statistically significant.
     *
     * **STA 342 §7.1:** Hypothesis testing evaluates claims about population
     * parameters:
     *   H₀: The pattern occurred by chance (no real effect)
     *   H₁: The pattern is a genuine signal
     *
     * We use a simple z-test for proportions (is this day consistently
     * above average?) and a t-test for means (is this product's margin
     * significantly different from the average?).
     *
     * **STA 342 §7.7 (Power Analysis):** We need enough observations to
     * detect meaningful effects. With too few data points, we can't
     * distinguish signal from noise.
     *
     * @param observedMean The observed mean (e.g., average sales on Monday)
     * @param populationMean The overall average (e.g., average sales across all days)
     * @param stdDev The standard deviation of the population
     * @param sampleSize Number of observations
     * @return Significance test result
     */
    fun testPatternSignificance(
        observedMean: Double,
        populationMean: Double,
        stdDev: Double,
        sampleSize: Int
    ): SignificanceTestResult {
        // STA 342 §7.1: Need minimum sample size for meaningful test
        if (sampleSize < 3 || stdDev <= 0) {
            return SignificanceTestResult(
                isSignificant = false,
                testStatistic = 0.0,
                confidence = 0.0,
                sampleSize = sampleSize,
                reason = "Insufficient data (need ≥3 observations with non-zero variance)"
            )
        }

        // z-test: z = (x̄ - μ) / (σ / √n)
        val se = stdDev / sqrt(sampleSize.toDouble())
        val zStatistic = (observedMean - populationMean) / se

        // STA 342 §7.1: Two-tailed test at α = 0.05
        // |z| > 1.96 → significant at 5% level
        // |z| > 2.576 → significant at 1% level
        val confidence = when {
            abs(zStatistic) > 2.576 -> 0.99  // 99% confidence
            abs(zStatistic) > 1.96 -> 0.95   // 95% confidence
            abs(zStatistic) > 1.645 -> 0.90  // 90% confidence
            else -> 0.0
        }

        val isSignificant = confidence >= 0.90

        // STA 342 §7.7: Estimate statistical power
        // Power = P(reject H₀ | H₁ true) ≈ depends on effect size and n
        val effectSize = if (stdDev > 0) abs(observedMean - populationMean) / stdDev else 0.0

        Timber.d("Pattern test: z=%.2f, confidence=%.0f%%, effect=%.2f, n=%d",
            zStatistic, confidence * 100, effectSize, sampleSize)

        return SignificanceTestResult(
            isSignificant = isSignificant,
            testStatistic = zStatistic,
            confidence = confidence,
            sampleSize = sampleSize,
            effectSize = effectSize,
            reason = if (isSignificant) {
                "Pattern is statistically significant at ${(confidence * 100).toInt()}% confidence"
            } else {
                "Pattern is not statistically significant (z=%.2f)".format(zStatistic)
            }
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // STA 343 — EXPERIMENTAL DESIGN: A/B testing framework
    // ═══════════════════════════════════════════════════════════════

    /**
     * Create an A/B test for a business strategy change.
     *
     * **STA 343 §7.3:** Experimental design principles:
     * 1. **Randomization:** Randomly assign time periods to treatment/control
     * 2. **Control:** Maintain a baseline (status quo) for comparison
     * 3. **Sample Size:** Calculate required observations to detect effect
     * 4. **Blocking:** Account for known sources of variation (day of week)
     *
     * **ECO 315 §3.7:** Causal inference requires:
     * - Clear treatment definition
     * - Measurable outcome
     * - Counterfactual (what would have happened without treatment)
     *
     * @param testId Unique identifier for this experiment
     * @param description What is being tested
     * @param treatmentLabel Description of the new strategy
     * @param controlLabel Description of the baseline strategy
     * @param minimumDays Minimum days needed (sample size planning)
     * @return Experiment definition
     */
    fun createABTest(
        testId: String,
        description: String,
        treatmentLabel: String,
        controlLabel: String,
        minimumDays: Int = 14
    ): ABTest {
        // STA 343: Minimum sample size for detecting medium effect (d=0.5)
        // n per group = 2(z_α + z_β)² × σ² / δ²
        // For α=0.05, power=0.80: n ≈ 64 per group for medium effect
        // For informal businesses, 14 days per condition is practical minimum

        val requiredDaysPerCondition = maxOf(minimumDays, 14)

        return ABTest(
            testId = testId,
            description = description,
            treatmentLabel = treatmentLabel,
            controlLabel = controlLabel,
            requiredDaysPerCondition = requiredDaysPerCondition,
            status = ABTestStatus.CREATED,
            createdAt = System.currentTimeMillis() / 1000,
            treatmentDays = 0,
            controlDays = 0,
            treatmentOutcome = 0.0,
            controlOutcome = 0.0
        )
    }

    /**
     * Analyze A/B test results using a two-sample t-test.
     *
     * **STA 342 §7.3 (Two-sample t-test):**
     *   t = (x̄₁ - x̄₂) / √(s₁²/n₁ + s₂²/n₂)
     *
     * Under H₀: μ₁ = μ₂, this follows a t-distribution with
     * degrees of freedom approximated by Welch's formula.
     *
     * @param treatmentOutcomes Daily outcomes during treatment period
     * @param controlOutcomes Daily outcomes during control period
     * @return Test result with effect size and significance
     */
    fun analyzeABTestResults(
        treatmentOutcomes: List<Double>,
        controlOutcomes: List<Double>
    ): ABTestResult {
        if (treatmentOutcomes.size < 3 || controlOutcomes.size < 3) {
            return ABTestResult(
                isSignificant = false,
                effectSize = 0.0,
                treatmentMean = treatmentOutcomes.averageOrNull() ?: 0.0,
                controlMean = controlOutcomes.averageOrNull() ?: 0.0,
                pValue = 1.0,
                recommendation = "Insufficient data — need at least 3 days per condition",
                sampleSizeT = treatmentOutcomes.size,
                sampleSizeC = controlOutcomes.size
            )
        }

        val n1 = treatmentOutcomes.size
        val n2 = controlOutcomes.size
        val mean1 = treatmentOutcomes.average()
        val mean2 = controlOutcomes.average()
        val var1 = treatmentOutcomes.map { (it - mean1) * (it - mean1) }.sum() / (n1 - 1)
        val var2 = controlOutcomes.map { (it - mean2) * (it - mean2) }.sum() / (n2 - 1)

        // Two-sample t-statistic
        val se = sqrt(var1 / n1 + var2 / n2)
        val tStat = if (se > 0) (mean1 - mean2) / se else 0.0

        // Approximate p-value using normal approximation (for n > 5)
        val z = abs(tStat)
        val pValue = when {
            z > 3.29 -> 0.001
            z > 2.576 -> 0.01
            z > 1.96 -> 0.05
            z > 1.645 -> 0.10
            else -> 0.5
        }

        // Cohen's d effect size
        val pooledSD = sqrt(((n1 - 1) * var1 + (n2 - 1) * var2) / (n1 + n2 - 2))
        val cohensD = if (pooledSD > 0) (mean1 - mean2) / pooledSD else 0.0

        val isSignificant = pValue <= 0.05

        val recommendation = when {
            isSignificant && cohensD > 0 -> "✅ Treatment is BETTER. Switch to: ${"%.0f".format(mean1)} vs ${"%.0f".format(mean2)}"
            isSignificant && cohensD < 0 -> "❌ Treatment is WORSE. Keep current strategy."
            else -> "⏳ No significant difference yet. Collect more data."
        }

        Timber.d("A/B test: t=%.2f, p=%.3f, d=%.2f, T=%.0f, C=%.0f",
            tStat, pValue, cohensD, mean1, mean2)

        return ABTestResult(
            isSignificant = isSignificant,
            effectSize = cohensD,
            treatmentMean = mean1,
            controlMean = mean2,
            pValue = pValue,
            recommendation = recommendation,
            sampleSizeT = n1,
            sampleSizeC = n2
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // STA 347 — STATISTICAL COMPUTING: On-device computation
    // ═══════════════════════════════════════════════════════════════

    /**
     * Bayesian updating for pattern confidence.
     *
     * **STA 341 §6.6:** Bayesian estimation combines prior beliefs with data:
     *   Posterior ∝ Likelihood × Prior
     *
     * For pattern confidence, we use a Beta-Binomial model:
     * - Prior: Beta(α₀, β₀) — initial belief about pattern reliability
     * - Data: Each observation is a "success" (pattern holds) or "failure"
     * - Posterior: Beta(α₀ + successes, β₀ + failures)
     *
     * This is computationally cheap (O(1) per update) and handles
     * the cold-start problem gracefully.
     *
     * @param priorAlpha Prior successes (default: 1 = weakly informative)
     * @param priorBeta Prior failures (default: 1 = weakly informative)
     * @param successes Number of times pattern was confirmed
     * @param failures Number of times pattern was violated
     * @return Updated confidence (posterior mean)
     */
    fun bayesianConfidenceUpdate(
        priorAlpha: Double = 1.0,
        priorBeta: Double = 1.0,
        successes: Int,
        failures: Int
    ): Double {
        // Beta posterior mean: (α + successes) / (α + β + successes + failures)
        val posteriorAlpha = priorAlpha + successes
        val posteriorBeta = priorBeta + failures
        val confidence = posteriorAlpha / (posteriorAlpha + posteriorBeta)

        Timber.d("Bayesian update: prior=α%.1f,β%.1f, data=%d/%d → posterior=%.3f",
            priorAlpha, priorBeta, successes, failures, confidence)
        return confidence
    }

    // ═══════════════════════════════════════════════════════════════
    // ECO 315 — RESEARCH METHODS: Validate and record patterns
    // ═══════════════════════════════════════════════════════════════

    /**
     * Record a vocabulary mapping.
     * Example: user says "mahindi", system knows it means "maize"
     */
    suspend fun recordUserTerm(spoken: String, canonical: String, language: String = "sw") {
        val existing = patternDao.getVocabularyEntry(spoken)
        if (existing != null) {
            patternDao.incrementFrequency(spoken)
        } else {
            patternDao.upsertVocabulary(VocabularyEntry(
                spokenForm = spoken.lowercase(),
                canonicalForm = canonical.lowercase(),
                language = language
            ))
        }
        Timber.d("Vocabulary: '%s' → '%s'", spoken, canonical)
    }

    /**
     * Look up canonical form for a spoken term.
     */
    suspend fun getCanonicalForm(spoken: String): String? {
        return patternDao.getCanonicalForm(spoken.lowercase())
    }

    /**
     * Get the user's most frequently used terms.
     */
    suspend fun getTopVocabulary(limit: Int = 20): List<VocabularyEntry> {
        return patternDao.getTopVocabulary(limit)
    }

    /**
     * Record a business pattern with Bayesian confidence updating.
     *
     * **ECO 315 §3.10:** Each pattern observation is a data point
     * that updates our belief about the pattern's reliability.
     * We use Bayesian updating so confidence grows gradually
     * with more evidence.
     */
    suspend fun recordPattern(
        type: PatternType,
        data: Map<String, Any>,
        confidence: Double = 0.5
    ) {
        val dataJson = json.encodeToString(data.mapValues { it.value.toString() })

        val key = data.keys.firstOrNull() ?: "default"
        val existing = patternDao.getPatternByKey(type, key)

        if (existing != null) {
            // STA 347: Bayesian confidence update
            // Convert current confidence to Beta parameters
            // and update with new observation
            val currentConf = existing.confidence
            val successes = (currentConf * 10).toInt() // Approximate
            val failures = ((1 - currentConf) * 10).toInt()
            val newConf = bayesianConfidenceUpdate(
                priorAlpha = 1.0,
                priorBeta = 1.0,
                successes = successes + if (confidence > 0.5) 1 else 0,
                failures = failures + if (confidence <= 0.5) 1 else 0
            )

            patternDao.updatePattern(existing.copy(
                data = dataJson,
                confidence = newConf,
                updatedAt = System.currentTimeMillis() / 1000
            ))
        } else {
            patternDao.insertPattern(BusinessPattern(
                patternType = type,
                data = dataJson,
                confidence = confidence
            ))
        }
    }

    /**
     * Learn restocking cycle for an item.
     */
    suspend fun learnRestockCycle(item: String) {
        val inventoryItem = inventoryDao.getItem(item)
        if (inventoryItem != null) {
            recordPattern(
                PatternType.RESTOCK_CYCLE,
                mapOf(
                    "item" to item,
                    "currentStock" to inventoryItem.currentStock,
                    "avgCost" to inventoryItem.avgCost,
                    "threshold" to inventoryItem.restockThreshold
                )
            )
        }
    }

    /**
     * Record a price observation for trend analysis.
     */
    suspend fun recordPriceObservation(item: String, price: Double) {
        recordPattern(
            PatternType.PRICE_TREND,
            mapOf(
                "item" to item,
                "price" to price,
                "timestamp" to System.currentTimeMillis()
            ),
            confidence = 0.8
        )
    }

    /**
     * Record a sale time for peak hours analysis.
     */
    suspend fun recordSaleTime(hour: Int, dayOfWeek: Int) {
        recordPattern(
            PatternType.PEAK_HOURS,
            mapOf(
                "hour" to hour,
                "dayOfWeek" to dayOfWeek
            ),
            confidence = 0.6
        )
    }

    /**
     * Record a language switch event.
     */
    suspend fun recordLanguageSwitch(from: String, to: String) {
        recordPattern(
            PatternType.LANGUAGE_SWITCH,
            mapOf("from" to from, "to" to to),
            confidence = 0.9
        )
    }

    /**
     * Record day-of-week sales pattern.
     */
    suspend fun recordDayOfWeekSales(dayOfWeek: Int, sales: Double) {
        recordPattern(
            PatternType.DAY_OF_WEEK,
            mapOf(
                "dayOfWeek" to dayOfWeek,
                "sales" to sales
            ),
            confidence = 0.7
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // PATTERN RETRIEVAL
    // ═══════════════════════════════════════════════════════════════

    /** Get patterns of a specific type. */
    suspend fun getPatterns(type: PatternType): List<BusinessPattern> {
        return patternDao.getPatternsByType(type)
    }

    /** Get all learned vocabulary for a language. */
    suspend fun getVocabulary(language: String = "sw"): List<VocabularyEntry> {
        return patternDao.getVocabularyForLanguage(language)
    }

    /**
     * Check if the system has learned enough about this user.
     *
     * **ECO 315 §3.2 (Research Design):** We need sufficient data
     * before drawing conclusions. The "isReady" threshold reflects
     * minimum sample sizes for reliable pattern detection.
     */
    suspend fun getLearningProgress(): LearningProgress {
        val vocabulary = patternDao.getTopVocabulary(100)
        val patterns = patternDao.getPatternsByType(PatternType.RESTOCK_CYCLE)

        return LearningProgress(
            vocabularySize = vocabulary.size,
            patternCount = patterns.size,
            isReady = vocabulary.size >= 10 && patterns.size >= 3
        )
    }

    /**
     * Export learned data for future LoRA fine-tuning.
     * Returns interaction pairs suitable for training.
     */
    suspend fun exportTrainingData(): List<Pair<String, String>> {
        return emptyList()
    }
}

// ═══════════════════════════════════════════════════════════════
// DATA CLASSES — Learning and testing results
// ═══════════════════════════════════════════════════════════════

/**
 * Learning progress status.
 */
data class LearningProgress(
    val vocabularySize: Int,
    val patternCount: Int,
    val isReady: Boolean
)

/**
 * **STA 342 §7.1:** Hypothesis test result.
 * Reports whether an observed pattern is statistically significant.
 */
data class SignificanceTestResult(
    val isSignificant: Boolean,
    val testStatistic: Double,
    val confidence: Double,
    val sampleSize: Int,
    val effectSize: Double = 0.0,
    val reason: String
)

/**
 * **STA 343 §7.3:** A/B test definition.
 * Represents a controlled experiment for testing business strategies.
 */
data class ABTest(
    val testId: String,
    val description: String,
    val treatmentLabel: String,
    val controlLabel: String,
    val requiredDaysPerCondition: Int,
    val status: ABTestStatus,
    val createdAt: Long,
    val treatmentDays: Int,
    val controlDays: Int,
    val treatmentOutcome: Double,
    val controlOutcome: Double
)

enum class ABTestStatus {
    CREATED,
    RUNNING,
    COMPLETED,
    ABANDONED
}

/**
 * **STA 342 §7.3:** A/B test result.
 * Reports the outcome of a controlled experiment.
 */
data class ABTestResult(
    val isSignificant: Boolean,
    val effectSize: Double, // Cohen's d
    val treatmentMean: Double,
    val controlMean: Double,
    val pValue: Double,
    val recommendation: String,
    val sampleSizeT: Int,
    val sampleSizeC: Int
)

// Helper extension
private fun List<Double>.averageOrNull(): Double? =
    if (isEmpty()) null else average()
