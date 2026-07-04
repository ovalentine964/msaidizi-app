package com.msaidizi.app.core.language

import timber.log.Timber
import kotlin.math.*

/**
 * Confidence calibration for ASR output — determines when to trust
 * the transcription vs. ask the user "did I hear that right?"
 *
 * Problem: Raw ASR confidence scores are poorly calibrated.
 * A model might say "90% confident" but actually be right only 70% of the time.
 * This is especially severe for low-resource African languages where the model
 * has less training data.
 *
 * Solution: Temperature scaling + Platt scaling + Bayesian calibration.
 *
 * Mathematical Foundation:
 * ─────────────────────────
 * Raw confidence: c_raw ∈ [0, 1] from ASR model (softmax output)
 *
 * 1. Temperature Scaling:
 *    c_calibrated = σ(c_raw / T)
 *    where σ is sigmoid, T is temperature learned on validation set
 *    T > 1 → softens overconfident predictions
 *    T < 1 → sharpens underconfident predictions
 *
 * 2. Platt Scaling:
 *    c_calibrated = σ(a · logit(c_raw) + b)
 *    where a, b are learned parameters
 *
 * 3. Bayesian Language-Aware Calibration:
 *    P(correct | c_raw, lang) ∝ P(c_raw | correct, lang) · P(correct | lang)
 *    Prior P(correct | lang) differs by language resource level:
 *    - English: 0.85 (well-trained)
 *    - Swahili: 0.70 (moderate resources)
 *    - Dholuo: 0.55 (low resources)
 *
 * Decision Thresholds:
 * ─────────────────────
 * > 0.90 → Accept silently (no confirmation needed)
 * 0.70–0.90 → Accept but log for learning
 * 0.50–0.70 → Ask "Niliskia [X], sahihi?" (confirm)
 * < 0.50 → "Sikuelewi vizuri. Tafadhali rudia." (reject, ask to repeat)
 *
 * Battery impact: <0.01ms per calibration — pure arithmetic.
 */
class ConfidenceCalibrator() {

    companion object {
        private const val TAG = "ConfidenceCalibrator"

        // Decision thresholds (tuned for African languages)
        const val THRESHOLD_ACCEPT = 0.90f          // Accept silently
        const val THRESHOLD_ACCEPT_LOG = 0.70f      // Accept + log for learning
        const val THRESHOLD_CONFIRM = 0.50f         // Ask user to confirm
        // Below THRESHOLD_CONFIRM → reject and ask to repeat

        // Default temperature for each language family
        // Higher T = more calibration needed (model is overconfident)
        private val DEFAULT_TEMPERATURES = mapOf(
            "en" to 1.2f,      // English: slight overconfidence
            "sw" to 1.8f,      // Swahili: moderate overconfidence
            "sheng" to 2.0f,   // Sheng: high uncertainty
            "luo" to 2.5f,     // Dholuo: very overconfident (low-resource)
            "ki" to 2.2f,      // Kikuyu: overconfident
            "yo" to 2.3f,      // Yoruba: overconfident
            "ha" to 2.0f,      // Hausa: moderate
            "mixed" to 2.2f,   // Code-mixed: high uncertainty
        )

        // Platt scaling parameters per language (a, b)
        // These would be learned from validation data; defaults are conservative
        private val DEFAULT_PLATT_PARAMS = mapOf(
            "en" to Pair(1.0f, 0.0f),        // Minimal recalibration
            "sw" to Pair(0.8f, -0.3f),       // Moderate correction
            "sheng" to Pair(0.7f, -0.5f),    // Strong correction
            "luo" to Pair(0.6f, -0.7f),      // Heavy correction
            "ki" to Pair(0.65f, -0.6f),
            "yo" to Pair(0.6f, -0.6f),
            "ha" to Pair(0.7f, -0.5f),
            "mixed" to Pair(0.65f, -0.5f),
        )

        // Prior probability of correct transcription per language
        // (based on expected WER from spec)
        private val LANGUAGE_PRIORS = mapOf(
            "en" to 0.85f,     // WER ~15%
            "sw" to 0.75f,     // WER ~25% (fine-tuned)
            "sheng" to 0.65f,  // WER ~35% (no dedicated model)
            "luo" to 0.55f,    // WER ~45% (very low resource)
            "ki" to 0.60f,     // WER ~40%
            "yo" to 0.60f,     // WER ~40%
            "ha" to 0.65f,     // WER ~35%
            "mixed" to 0.60f,  // Code-mixed: hardest
        )
    }

    // ════════════════════════════════════════════════════════════════════
    // CALIBRATION STATE — Updated via on-device learning
    // ════════════════════════════════════════════════════════════════════

    /**
     * Per-language calibration parameters.
     * Updated as user provides corrections (online learning).
     */
    private val learnedTemperatures = DEFAULT_TEMPERATURES.toMutableMap()
    private val learnedPlattParams = DEFAULT_PLATT_PARAMS.toMutableMap()
    private val languagePriors = LANGUAGE_PRIORS.toMutableMap()

    /**
     * Per-user correction statistics for adaptive calibration.
     */
    private data class CalibrationStats(
        var totalObservations: Int = 0,
        var correctCount: Int = 0,
        var sumRawConfidence: Double = 0.0,
        var sumCalibratedConfidence: Double = 0.0,
        // For ECE computation
        val binCorrect: IntArray = IntArray(10),    // 10 bins of 0.1 width
        val binTotal: IntArray = IntArray(10),
        val binConfidence: DoubleArray = DoubleArray(10)
    )

    private val stats = mutableMapOf<String, CalibrationStats>()

    // ════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ════════════════════════════════════════════════════════════════════

    /**
     * Calibrate raw ASR confidence score.
     *
     * Pipeline:
     * 1. Temperature scaling: c' = σ(logit(c) / T)
     * 2. Platt scaling: c'' = σ(a · logit(c') + b)
     * 3. Bayesian prior adjustment: c_final = c'' · prior / (c'' · prior + (1-c'') · (1-prior))
     *
     * @param rawConfidence Raw ASR confidence [0, 1]
     * @param language Detected language code
     * @return CalibratedConfidence with score, decision, and metadata
     */
    fun calibrate(rawConfidence: Float, language: String): CalibratedConfidence {
        val lang = language.takeIf { it in DEFAULT_TEMPERATURES } ?: "sw"

        // Step 1: Temperature scaling
        val temperature = learnedTemperatures[lang] ?: 1.5f
        val logit = logitTransform(rawConfidence.coerceIn(0.01f, 0.99f))
        val tempScaled = sigmoid(logit / temperature)

        // Step 2: Platt scaling
        val (a, b) = learnedPlattParams[lang] ?: Pair(1.0f, 0.0f)
        val plattLogit = logitTransform(tempScaled.coerceIn(0.01f, 0.99f))
        val plattScaled = sigmoid(a * plattLogit + b)

        // Step 3: Bayesian prior adjustment
        val prior = languagePriors[lang] ?: 0.70f
        val posterior = bayesianUpdate(plattScaled, prior)

        // Step 4: Determine action
        val action = when {
            posterior >= THRESHOLD_ACCEPT -> CalibrationAction.ACCEPT
            posterior >= THRESHOLD_ACCEPT_LOG -> CalibrationAction.ACCEPT_AND_LOG
            posterior >= THRESHOLD_CONFIRM -> CalibrationAction.CONFIRM
            else -> CalibrationAction.REJECT
        }

        val result = CalibratedConfidence(
            rawConfidence = rawConfidence,
            calibratedConfidence = posterior,
            temperature = temperature,
            language = lang,
            action = action,
            shouldConfirm = action == CalibrationAction.CONFIRM,
            shouldReject = action == CalibrationAction.REJECT
        )

        Timber.tag(TAG).v(
            "Calibrate [%s]: raw=%.3f → calibrated=%.3f → %s",
            lang, rawConfidence, posterior, action
        )

        return result
    }

    /**
     * Calibrate word-level confidences for partial transcription feedback.
     * Returns per-word calibrated scores for fine-grained control.
     */
    fun calibrateWords(
        words: List<Pair<String, Float>>,  // (word, raw_confidence)
        language: String
    ): List<WordConfidence> {
        return words.map { (word, rawConf) ->
            val calibrated = calibrate(rawConf, language)
            WordConfidence(
                word = word,
                rawConfidence = rawConf,
                calibratedConfidence = calibrated.calibratedConfidence,
                action = calibrated.action
            )
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // ONLINE LEARNING — Update calibration from user corrections
    // ════════════════════════════════════════════════════════════════════

    /**
     * Record a correction outcome for online calibration learning.
     *
     * When the user confirms or rejects a transcription, we use that signal
     * to update the calibration parameters via gradient descent:
     *
     * Temperature update (Platt-style):
     *   T_new = T_old - η · ∂L/∂T
     *   where L = -[y·log(c) + (1-y)·log(1-c)]  (binary cross-entropy)
     *   and c = σ(logit(raw) / T)
     *
     * @param rawConfidence The original raw ASR confidence
     * @param language Language of the transcription
     * @param wasCorrect Whether the transcription was actually correct (user confirmed/rejected)
     */
    fun recordOutcome(rawConfidence: Float, language: String, wasCorrect: Boolean) {
        val lang = language.takeIf { it in DEFAULT_TEMPERATURES } ?: "sw"
        val stat = stats.getOrPut(lang) { CalibrationStats() }

        stat.totalObservations++
        if (wasCorrect) stat.correctCount++
        stat.sumRawConfidence += rawConfidence

        // Update bin statistics for ECE
        val bin = (rawConfidence * 10).toInt().coerceIn(0, 9)
        stat.binTotal[bin]++
        if (wasCorrect) stat.binCorrect[bin]++
        stat.binConfidence[bin] += rawConfidence

        // Online gradient descent on temperature (every 10 observations)
        if (stat.totalObservations % 10 == 0) {
            updateTemperature(lang, stat)
            updateLanguagePrior(lang, stat)
        }

        Timber.tag(TAG).d(
            "RecordOutcome [%s]: raw=%.3f, correct=%b, stats=%d/%d",
            lang, rawConfidence, wasCorrect, stat.correctCount, stat.totalObservations
        )
    }

    /**
     * Get calibration quality metrics for a language.
     * Used for monitoring and debugging.
     */
    fun getCalibrationMetrics(language: String): CalibrationMetrics {
        val stat = stats[language] ?: return CalibrationMetrics(
            language = language,
            expectedCalibrationError = 0.0f,
            sampleCount = 0,
            actualAccuracy = LANGUAGE_PRIORS[language] ?: 0.7f,
            averageConfidence = 0.0f,
            temperature = learnedTemperatures[language] ?: 1.5f
        )

        val ece = computeECE(stat)
        val avgConf = if (stat.totalObservations > 0)
            stat.sumRawConfidence / stat.totalObservations else 0.0

        return CalibrationMetrics(
            language = language,
            expectedCalibrationError = ece.toFloat(),
            sampleCount = stat.totalObservations,
            actualAccuracy = if (stat.totalObservations > 0)
                stat.correctCount.toFloat() / stat.totalObservations else 0f,
            averageConfidence = avgConf.toFloat(),
            temperature = learnedTemperatures[language] ?: 1.5f
        )
    }

    /**
     * Get the current temperature for a language.
     * Used by FederatedLearningClient to share calibration parameters.
     */
    fun getTemperature(language: String): Float {
        return learnedTemperatures[language] ?: DEFAULT_TEMPERATURES[language] ?: 1.5f
    }

    /**
     * Set calibration parameters from federated learning aggregation.
     * Called when receiving updated parameters from the cloud.
     */
    fun setCalibrationParams(
        language: String,
        temperature: Float,
        plattA: Float,
        plattB: Float,
        prior: Float
    ) {
        learnedTemperatures[language] = temperature
        learnedPlattParams[language] = Pair(plattA, plattB)
        languagePriors[language] = prior
        Timber.tag(TAG).i(
            "Updated calibration [%s]: T=%.2f, a=%.2f, b=%.2f, prior=%.2f",
            language, temperature, plattA, plattB, prior
        )
    }

    // ════════════════════════════════════════════════════════════════════
    // MATH HELPERS
    // ════════════════════════════════════════════════════════════════════

    /**
     * Sigmoid function: σ(x) = 1 / (1 + e^(-x))
     */
    private fun sigmoid(x: Float): Float {
        return (1.0 / (1.0 + exp(-x.toDouble()))).toFloat()
    }

    /**
     * Logit (inverse sigmoid): logit(p) = log(p / (1-p))
     */
    private fun logitTransform(p: Float): Float {
        val clipped = p.coerceIn(0.001f, 0.999f)
        return ln(clipped / (1.0f - clipped))
    }

    /**
     * Bayesian posterior update:
     *   P(correct | c, prior) = c·prior / (c·prior + (1-c)·(1-prior))
     *
     * This adjusts the calibrated confidence by the language-specific prior.
     * For low-resource languages with low prior, the posterior will be lower
     * than the raw confidence, reflecting higher uncertainty.
     */
    private fun bayesianUpdate(confidence: Float, prior: Float): Float {
        val numerator = confidence * prior
        val denominator = confidence * prior + (1 - confidence) * (1 - prior)
        return if (denominator > 0) (numerator / denominator).coerceIn(0f, 1f) else confidence
    }

    /**
     * Update temperature via online gradient descent.
     *
     * Minimizes negative log-likelihood:
     *   L(T) = -Σ [y_i · log(σ(logit(c_i)/T)) + (1-y_i) · log(1 - σ(logit(c_i)/T))]
     *
     * Gradient:
     *   ∂L/∂T = Σ (σ_i - y_i) · logit(c_i) / T²
     *
     * Update: T_new = T_old - η · ∂L/∂T
     */
    private fun updateTemperature(language: String, stat: CalibrationStats) {
        val currentT = learnedTemperatures[language] ?: 1.5f
        val learningRate = 0.01f

        // Compute gradient from all observations in bins
        var gradient = 0.0
        for (bin in 0 until 10) {
            if (stat.binTotal[bin] == 0) continue
            val avgConf = stat.binConfidence[bin] / stat.binTotal[bin]
            val actualRate = stat.binCorrect[bin].toDouble() / stat.binTotal[bin]
            val predicted = sigmoid(logitTransform(avgConf.coerceIn(0.01, 0.99)) / currentT)
            val logit = logitTransform(avgConf.coerceIn(0.01, 0.99)).toDouble()

            // ∂L/∂T contribution from this bin
            gradient += (predicted - actualRate) * logit / (currentT * currentT)
        }

        // Gradient descent step
        val newT = (currentT - learningRate * gradient).toFloat().coerceIn(0.5f, 5.0f)
        learnedTemperatures[language] = newT

        Timber.tag(TAG).d("Temperature update [%s]: %.3f → %.3f", language, currentT, newT)
    }

    /**
     * Update language prior based on observed accuracy.
     *
     * Prior update: prior_new = (correct_count + α) / (total + α + β)
     * where α=1, β=1 is Beta(1,1) = Uniform prior on the prior itself.
     */
    private fun updateLanguagePrior(language: String, stat: CalibrationStats) {
        val alpha = 1.0  // Beta prior pseudo-count for success
        val beta = 1.0   // Beta prior pseudo-count for failure
        val newPrior = ((stat.correctCount + alpha) / (stat.totalObservations + alpha + beta)).toFloat()
        languagePriors[language] = newPrior.coerceIn(0.1f, 0.99f)
    }

    /**
     * Compute Expected Calibration Error (ECE).
     *
     * ECE = Σ (|B_m| / n) · |acc(B_m) - conf(B_m)|
     *
     * where B_m is the set of predictions with confidence in bin m,
     * acc(B_m) is the fraction of correct predictions in B_m,
     * conf(B_m) is the average confidence in B_m.
     */
    private fun computeECE(stat: CalibrationStats): Double {
        if (stat.totalObservations == 0) return 0.0

        var ece = 0.0
        for (bin in 0 until 10) {
            if (stat.binTotal[bin] == 0) continue
            val weight = stat.binTotal[bin].toDouble() / stat.totalObservations
            val accuracy = stat.binCorrect[bin].toDouble() / stat.binTotal[bin]
            val avgConfidence = stat.binConfidence[bin] / stat.binTotal[bin]
            ece += weight * abs(accuracy - avgConfidence)
        }
        return ece
    }
}

// ════════════════════════════════════════════════════════════════════
// DATA CLASSES
// ════════════════════════════════════════════════════════════════════

/**
 * Calibrated confidence result with decision action.
 */
data class CalibratedConfidence(
    val rawConfidence: Float,
    val calibratedConfidence: Float,
    val temperature: Float,
    val language: String,
    val action: CalibrationAction,
    val shouldConfirm: Boolean,
    val shouldReject: Boolean
) {
    /** Is this transcription reliable enough to use? */
    val isReliable: Boolean get() = action == CalibrationAction.ACCEPT || action == CalibrationAction.ACCEPT_AND_LOG

    /** User-facing Swahili prompt for confirmation */
    fun toConfirmationPrompt(transcribedText: String): String? = when (action) {
        CalibrationAction.CONFIRM -> "Niliskia \"$transcribedText\", sahihi?"
        CalibrationAction.REJECT -> "Sikuelewi vizuri. Tafadhali rudia."
        else -> null
    }
}

enum class CalibrationAction {
    /** Confidence > 0.90: accept silently */
    ACCEPT,
    /** Confidence 0.70–0.90: accept but log for learning */
    ACCEPT_AND_LOG,
    /** Confidence 0.50–0.70: ask user to confirm */
    CONFIRM,
    /** Confidence < 0.50: reject, ask to repeat */
    REJECT
}

/**
 * Per-word confidence for fine-grained transcription feedback.
 */
data class WordConfidence(
    val word: String,
    val rawConfidence: Float,
    val calibratedConfidence: Float,
    val action: CalibrationAction
)

/**
 * Calibration quality metrics for monitoring.
 */
data class CalibrationMetrics(
    val language: String,
    val expectedCalibrationError: Float,
    val sampleCount: Int,
    val actualAccuracy: Float,
    val averageConfidence: Float,
    val temperature: Float
)
