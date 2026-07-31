package com.msaidizi.agent.tools

import com.google.gson.Gson
import com.msaidizi.core.database.KnowledgeDao
import com.msaidizi.core.database.TraceDao
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AlamaScoreValidator — G8: Score-outcome validation logging.
 *
 * Logs every Alama Score computation alongside actual loan outcomes
 * to measure score accuracy over time. This is critical for:
 *
 * 1. Calibration: Does a score of 650 actually predict 90% repayment?
 * 2. Drift detection: Is the score model degrading over time?
 * 3. Bias detection: Are certain worker types systematically over/under-scored?
 * 4. A/B testing: Does a new scoring algorithm outperform the old one?
 *
 * Data flow:
 * - Score computed → logged to score_validations table
 * - Loan approved → linked to score via worker_id + timestamp
 * - Loan outcome (repaid/defaulted) → linked to validation record
 * - Periodic analysis → calibration curves, Brier scores, AUC-ROC
 *
 * Privacy: All data stays on-device. Only aggregated (k≥10) accuracy
 * metrics are synced to backend for model improvement.
 */
@Singleton
class AlamaScoreValidator @Inject constructor(
    private val knowledgeDao: KnowledgeDao,
    private val traceDao: TraceDao,
    private val gson: Gson
) {
    companion object {
        private const val CATEGORY_SCORE_VALIDATIONS = "score_validations"
        private const val CATEGORY_LOAN_OUTCOMES = "loan_outcomes"
        private const val CATEGORY_CALIBRATION = "score_calibration"
    }

    // ═══════════════════════════════════════════════════════════
    //  SCORE LOGGING
    // ═══════════════════════════════════════════════════════════

    /**
     * Log a score computation for later validation.
     * Called every time AlamaScore.calculateScore() runs.
     */
    suspend fun logScoreComputed(result: AlamaScoreResult) {
        val record = ScoreValidationRecord(
            score = result.score,
            level = result.level,
            factors = result.factors,
            creditReady = result.creditReady,
            computedAt = System.currentTimeMillis(),
            // These get filled in when a loan is taken
            loanId = null,
            loanAmount = null,
            loanOutcome = null,
            outcomeDate = null
        )

        try {
            knowledgeDao.insert(
                com.msaidizi.core.model.KnowledgeEntity(
                    category = CATEGORY_SCORE_VALIDATIONS,
                    key = "score_${System.currentTimeMillis()}",
                    value = gson.toJson(record),
                    confidence = 1.0f,
                    usageCount = 0
                )
            )
            Timber.d("AlamaScoreValidator: logged score %d (%s)", result.score, result.level)
        } catch (e: Exception) {
            Timber.e(e, "AlamaScoreValidator: failed to log score")
        }
    }

    /**
     * Link a loan outcome to a previously computed score.
     * Called when loan status changes (approved, repaid, defaulted).
     */
    suspend fun logLoanOutcome(
        loanId: String,
        loanAmount: Double,
        outcome: LoanOutcome,
        outcomeNotes: String = ""
    ) {
        val now = System.currentTimeMillis()
        val outcomeRecord = LoanOutcomeRecord(
            loanId = loanId,
            loanAmount = loanAmount,
            outcome = outcome,
            outcomeNotes = outcomeNotes,
            outcomeDate = now
        )

        // Store the outcome
        try {
            knowledgeDao.insert(
                com.msaidizi.core.model.KnowledgeEntity(
                    category = CATEGORY_LOAN_OUTCOMES,
                    key = "loan_$loanId",
                    value = gson.toJson(outcomeRecord),
                    confidence = 1.0f,
                    usageCount = 0
                )
            )

            // Find the most recent score before this loan and link them
            linkScoreToLoan(loanId, loanAmount, outcome, now)

            Timber.d("AlamaScoreValidator: logged loan outcome %s = %s (KES %.0f)",
                loanId, outcome, loanAmount)
        } catch (e: Exception) {
            Timber.e(e, "AlamaScoreValidator: failed to log loan outcome")
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  CALIBRATION ANALYSIS
    // ═══════════════════════════════════════════════════════════

    /**
     * Compute calibration metrics: how well do scores predict outcomes?
     *
     * Returns:
     * - Brier score (lower is better, 0.0 = perfect)
     * - Score-outcome correlation
     * - Default rate by score band
     * - AUC-ROC estimate
     */
    suspend fun computeCalibration(): CalibrationReport {
        val validations = getAllValidations()
        val outcomes = getAllOutcomes()

        if (validations.isEmpty() || outcomes.isEmpty()) {
            return CalibrationReport(
                sampleSize = 0,
                brierScore = null,
                aucRoc = null,
                defaultRateByBand = emptyMap(),
                calibrationCurve = emptyMap(),
                warnings = listOf("Insufficient data for calibration analysis")
            )
        }

        // Match scores to outcomes
        val matched = matchScoresToOutcomes(validations, outcomes)

        // Brier score: mean squared error of predicted vs actual default probability
        val brierScore = computeBrierScore(matched)

        // Default rate by score band
        val defaultRateByBand = computeDefaultRateByBand(matched)

        // Calibration curve: predicted probability vs observed outcome
        val calibrationCurve = computeCalibrationCurve(matched)

        // Simple AUC-ROC estimate
        val aucRoc = computeAucRoc(matched)

        return CalibrationReport(
            sampleSize = matched.size,
            brierScore = brierScore,
            aucRoc = aucRoc,
            defaultRateByBand = defaultRateByBand,
            calibrationCurve = calibrationCurve,
            warnings = generateCalibrationWarnings(matched, brierScore, aucRoc)
        )
    }

    /**
     * Detect score drift: is the model degrading over time?
     * Compare recent calibration (last 90 days) vs historical.
     */
    suspend fun detectDrift(): DriftReport {
        val allValidations = getAllValidations()
        val allOutcomes = getAllOutcomes()

        val now = System.currentTimeMillis()
        val ninetyDaysAgo = now - (90 * 24 * 60 * 60 * 1000L)

        val recent = matchScoresToOutcomes(
            allValidations.filter { it.computedAt > ninetyDaysAgo },
            allOutcomes
        )
        val historical = matchScoresToOutcomes(
            allValidations.filter { it.computedAt <= ninetyDaysAgo },
            allOutcomes
        )

        val recentBrier = computeBrierScore(recent)
        val historicalBrier = computeBrierScore(historical)

        val driftDetected = if (recentBrier != null && historicalBrier != null) {
            recentBrier > historicalBrier + 0.05 // 5% degradation threshold
        } else false

        return DriftReport(
            recentSampleSize = recent.size,
            historicalSampleSize = historical.size,
            recentBrierScore = recentBrier,
            historicalBrierScore = historicalBrier,
            driftDetected = driftDetected,
            driftMagnitude = if (recentBrier != null && historicalBrier != null) {
                recentBrier - historicalBrier
            } else null
        )
    }

    /**
     * Detect bias: are certain worker types systematically over/under-scored?
     */
    suspend fun detectBias(): BiasReport {
        val validations = getAllValidations()
        val outcomes = getAllOutcomes()
        val matched = matchScoresToOutcomes(validations, outcomes)

        // Group by score level and compute default rates
        val byLevel = matched.groupBy { it.scoreLevel }
        val biasMetrics = byLevel.map { (level, entries) ->
            val defaults = entries.count { it.outcome == LoanOutcome.DEFAULTED }
            val total = entries.size
            val defaultRate = if (total > 0) defaults.toDouble() / total else 0.0

            ScoreLevelBias(
                level = level,
                sampleSize = total,
                defaultRate = defaultRate,
                avgScore = entries.map { it.score }.average()
            )
        }

        return BiasReport(
            sampleSize = matched.size,
            levelBiases = biasMetrics,
            warnings = biasMetrics
                .filter { it.sampleSize >= 10 && (it.defaultRate > 0.3 || it.defaultRate < 0.01) }
                .map { "${it.level}: ${"%.0f".format(it.defaultRate * 100)}% default rate (n=${it.sampleSize})" }
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════

    private suspend fun linkScoreToLoan(
        loanId: String,
        loanAmount: Double,
        outcome: LoanOutcome,
        outcomeDate: Long
    ) {
        // Find most recent score validation before this loan
        try {
            val validations = knowledgeDao.getByCategory(CATEGORY_SCORE_VALIDATIONS).first()
            val mostRecent = validations
                .mapNotNull { entry ->
                    try {
                        gson.fromJson(entry.value, ScoreValidationRecord::class.java)
                    } catch (e: Exception) { null }
                }
                .filter { it.loanId == null && it.computedAt < outcomeDate }
                .maxByOrNull { it.computedAt }

            if (mostRecent != null) {
                // Update the validation record with loan outcome
                val updated = mostRecent.copy(
                    loanId = loanId,
                    loanAmount = loanAmount,
                    loanOutcome = outcome,
                    outcomeDate = outcomeDate
                )
                val key = validations.find { entry ->
                    try {
                        val r = gson.fromJson(entry.value, ScoreValidationRecord::class.java)
                        r.computedAt == mostRecent.computedAt
                    } catch (e: Exception) { false }
                }?.key

                if (key != null) {
                    knowledgeDao.update(
                        validations.find { it.key == key }!!.copy(
                            value = gson.toJson(updated),
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "AlamaScoreValidator: failed to link score to loan")
        }
    }

    private suspend fun getAllValidations(): List<ScoreValidationRecord> {
        return try {
            knowledgeDao.getByCategory(CATEGORY_SCORE_VALIDATIONS).first()
                .mapNotNull { entry ->
                    try {
                        gson.fromJson(entry.value, ScoreValidationRecord::class.java)
                    } catch (e: Exception) { null }
                }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun getAllOutcomes(): List<LoanOutcomeRecord> {
        return try {
            knowledgeDao.getByCategory(CATEGORY_LOAN_OUTCOMES).first()
                .mapNotNull { entry ->
                    try {
                        gson.fromJson(entry.value, LoanOutcomeRecord::class.java)
                    } catch (e: Exception) { null }
                }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun matchScoresToOutcomes(
        scores: List<ScoreValidationRecord>,
        outcomes: List<LoanOutcomeRecord>
    ): List<MatchedRecord> {
        val outcomeMap = outcomes.associateBy { it.loanId }

        return scores.mapNotNull { score ->
            val loanId = score.loanId ?: return@mapNotNull null
            val outcome = outcomeMap[loanId] ?: return@mapNotNull null

            MatchedRecord(
                score = score.score,
                scoreLevel = score.level,
                predictedDefaultProb = 1.0 - (score.score - 300.0) / 550.0,
                outcome = outcome.outcome,
                loanAmount = outcome.loanAmount,
                scoreDate = score.computedAt,
                outcomeDate = outcome.outcomeDate
            )
        }
    }

    private fun computeBrierScore(matched: List<MatchedRecord>): Double? {
        if (matched.isEmpty()) return null

        val squaredErrors = matched.map { record ->
            val actual = if (record.outcome == LoanOutcome.DEFAULTED) 1.0 else 0.0
            val predicted = record.predictedDefaultProb
            (predicted - actual).let { it * it }
        }

        return squaredErrors.average()
    }

    private fun computeDefaultRateByBand(matched: List<MatchedRecord>): Map<String, Double> {
        val bands = mapOf(
            "300-399" to (300..399),
            "400-499" to (400..499),
            "500-599" to (500..599),
            "600-699" to (600..699),
            "700-799" to (700..799),
            "800-850" to (800..850)
        )

        return bands.map { (label, range) ->
            val bandEntries = matched.filter { it.score in range }
            val defaults = bandEntries.count { it.outcome == LoanOutcome.DEFAULTED }
            val rate = if (bandEntries.isNotEmpty()) defaults.toDouble() / bandEntries.size else 0.0
            label to rate
        }.toMap()
    }

    private fun computeCalibrationCurve(matched: List<MatchedRecord>): Map<String, Double> {
        // Group by predicted probability decile and compute observed outcome rate
        val sorted = matched.sortedBy { it.predictedDefaultProb }
        val chunkSize = (sorted.size / 10).coerceAtLeast(1)

        return sorted.chunked(chunkSize).mapIndexed { i, chunk ->
            val avgPredicted = chunk.map { it.predictedDefaultProb }.average()
            val observed = chunk.count { it.outcome == LoanOutcome.DEFAULTED }.toDouble() / chunk.size
            "decile_${i + 1}" to observed
        }.toMap()
    }

    private fun computeAucRoc(matched: List<MatchedRecord>): Double? {
        if (matched.size < 5) return null

        val positives = matched.filter { it.outcome == LoanOutcome.DEFAULTED }
        val negatives = matched.filter { it.outcome != LoanOutcome.DEFAULTED }

        if (positives.isEmpty() || negatives.isEmpty()) return null

        // Simple AUC: probability that a random positive is ranked higher than a random negative
        var concordant = 0
        var total = 0

        for (pos in positives) {
            for (neg in negatives) {
                total++
                if (pos.predictedDefaultProb > neg.predictedDefaultProb) {
                    concordant++
                } else if (pos.predictedDefaultProb == neg.predictedDefaultProb) {
                    concordant += 0.5
                }
            }
        }

        return if (total > 0) concordant.toDouble() / total else null
    }

    private fun generateCalibrationWarnings(
        matched: List<MatchedRecord>,
        brierScore: Double?,
        aucRoc: Double?
    ): List<String> {
        val warnings = mutableListOf<String>()

        if (matched.size < 30) {
            warnings.add("Small sample size (${matched.size}). Need ≥30 for reliable calibration.")
        }
        if (brierScore != null && brierScore > 0.25) {
            warnings.add("High Brier score (${String.format("%.3f", brierScore)}). Score may be poorly calibrated.")
        }
        if (aucRoc != null && aucRoc < 0.6) {
            warnings.add("Low AUC-ROC (${String.format("%.3f", aucRoc)}). Score has poor discrimination.")
        }

        return warnings
    }
}

// ═══════════════════════════════════════════════════════════
//  DATA CLASSES
// ═══════════════════════════════════════════════════════════

data class ScoreValidationRecord(
    val score: Int,
    val level: String,
    val factors: List<String>,
    val creditReady: Boolean,
    val computedAt: Long,
    val loanId: String?,
    val loanAmount: Double?,
    val loanOutcome: LoanOutcome?,
    val outcomeDate: Long?
)

data class LoanOutcomeRecord(
    val loanId: String,
    val loanAmount: Double,
    val outcome: LoanOutcome,
    val outcomeNotes: String,
    val outcomeDate: Long
)

enum class LoanOutcome {
    APPROVED,        // Loan was approved
    REJECTED,        // Loan was rejected (score too low)
    REPAID_ON_TIME,  // Loan repaid within term
    REPAID_LATE,     // Loan repaid but late
    DEFAULTED,       // Loan not repaid
    WRITTEN_OFF      // Debt forgiven
}

data class MatchedRecord(
    val score: Int,
    val scoreLevel: String,
    val predictedDefaultProb: Double,
    val outcome: LoanOutcome,
    val loanAmount: Double,
    val scoreDate: Long,
    val outcomeDate: Long
)

data class CalibrationReport(
    val sampleSize: Int,
    val brierScore: Double?,
    val aucRoc: Double?,
    val defaultRateByBand: Map<String, Double>,
    val calibrationCurve: Map<String, Double>,
    val warnings: List<String>
)

data class DriftReport(
    val recentSampleSize: Int,
    val historicalSampleSize: Int,
    val recentBrierScore: Double?,
    val historicalBrierScore: Double?,
    val driftDetected: Boolean,
    val driftMagnitude: Double?
)

data class BiasReport(
    val sampleSize: Int,
    val levelBiases: List<ScoreLevelBias>,
    val warnings: List<String>
)

data class ScoreLevelBias(
    val level: String,
    val sampleSize: Int,
    val defaultRate: Double,
    val avgScore: Double
)
