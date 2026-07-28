package com.msaidizi.agent.flywheel

import com.msaidizi.core.database.KnowledgeDao
import com.msaidizi.core.database.TraceDao
import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * ABTestEngine — A/B testing framework for harness improvements.
 *
 * Before applying a harness change to all users, test it on a subset:
 *   - Group A (control): current harness configuration
 *   - Group B (treatment): proposed change (different weights, different tools, etc.)
 *
 * Measures:
 *   - Success rate (primary metric)
 *   - User satisfaction (feedback rate)
 *   - Task completion (tools succeeded)
 *   - Latency
 *   - Correction rate
 *
 * Decision: After sufficient samples, apply if treatment > control on
 * success rate by a statistically significant margin (p < 0.05).
 *
 * This prevents "improvements" that actually make things worse.
 */
@Singleton
class ABTestEngine @Inject constructor(
    private val traceDao: TraceDao,
    private val knowledgeDao: KnowledgeDao,
    private val gson: Gson
) {
    companion object {
        private const val CATEGORY_AB_TESTS = "ab_tests"
        private const val CATEGORY_AB_RESULTS = "ab_results"

        /** Minimum samples per group before making a decision. */
        private const val MIN_SAMPLES_PER_GROUP = 30

        /** Minimum improvement for treatment to be considered better. */
        private const val MIN_IMPROVEMENT = 0.03f  // 3%

        /** Z-score for 95% confidence (two-tailed). */
        private const val Z_SCORE_95 = 1.96f
    }

    // Active experiments
    private val activeExperiments = ConcurrentHashMap<String, ABExperiment>()

    /**
     * Create a new A/B test experiment.
     *
     * @param name Human-readable name
     * @param description What is being tested
     * @param experimentType Type of change being tested
     * @param treatmentConfig The proposed change configuration
     * @return Experiment ID
     */
    suspend fun createExperiment(
        name: String,
        description: String,
        experimentType: ExperimentType,
        treatmentConfig: Map<String, Any>
    ): String {
        val experimentId = UUID.randomUUID().toString()
        val experiment = ABExperiment(
            id = experimentId,
            name = name,
            description = description,
            type = experimentType,
            treatmentConfig = treatmentConfig,
            status = ExperimentStatus.RUNNING,
            createdAt = System.currentTimeMillis(),
            controlGroup = ExperimentGroup(),
            treatmentGroup = ExperimentGroup()
        )

        activeExperiments[experimentId] = experiment
        persistExperiment(experiment)

        Timber.i("ABTestEngine: created experiment '%s' (%s)", name, experimentId)
        return experimentId
    }

    /**
     * Assign a user to a group for an experiment.
     * Uses deterministic hashing so the same user always gets the same group.
     *
     * @param experimentId The experiment ID
     * @param userId The user/session ID (used for deterministic assignment)
     * @return The assigned group (CONTROL or TREATMENT)
     */
    fun assignGroup(experimentId: String, userId: String): ExperimentGroupType {
        val experiment = activeExperiments[experimentId] ?: return ExperimentGroupType.CONTROL

        // Deterministic assignment: hash(userId + experimentId)
        val hash = (userId + experimentId).hashCode()
        val bucket = (hash and 0x7FFFFFFF) % 100

        // 50/50 split
        return if (bucket < 50) ExperimentGroupType.CONTROL else ExperimentGroupType.TREATMENT
    }

    /**
     * Check if a user should receive the treatment for an active experiment.
     *
     * @param experimentType The type of experiment to check
     * @param userId The user/session ID
     * @return The treatment config if user is in treatment group, null otherwise
     */
    suspend fun getTreatmentConfig(
        experimentType: ExperimentType,
        userId: String
    ): Map<String, Any>? {
        val experiment = activeExperiments.values
            .find { it.type == experimentType && it.status == ExperimentStatus.RUNNING }
            ?: return null

        return when (assignGroup(experiment.id, userId)) {
            ExperimentGroupType.TREATMENT -> experiment.treatmentConfig
            ExperimentGroupType.CONTROL -> null
        }
    }

    /**
     * Record a trace result for an experiment.
     * Automatically determines which group the trace belongs to.
     */
    suspend fun recordTrace(
        experimentId: String,
        userId: String,
        success: Boolean,
        confidence: Float,
        latencyMs: Long,
        userFeedback: Boolean? = null,
        correctionMade: Boolean = false
    ) {
        val experiment = activeExperiments[experimentId] ?: return
        val group = assignGroup(experimentId, userId)

        val sample = TraceSample(
            userId = userId,
            success = success,
            confidence = confidence,
            latencyMs = latencyMs,
            userFeedback = userFeedback,
            correctionMade = correctionMade,
            timestamp = System.currentTimeMillis()
        )

        when (group) {
            ExperimentGroupType.CONTROL -> experiment.controlGroup.samples.add(sample)
            ExperimentGroupType.TREATMENT -> experiment.treatmentGroup.samples.add(sample)
        }

        // Update group statistics
        updateGroupStats(if (group == ExperimentGroupType.CONTROL) experiment.controlGroup else experiment.treatmentGroup)

        // Check if we have enough data to make a decision
        if (experiment.controlGroup.sampleCount >= MIN_SAMPLES_PER_GROUP &&
            experiment.treatmentGroup.sampleCount >= MIN_SAMPLES_PER_GROUP) {
            evaluateExperiment(experiment)
        }

        persistExperiment(experiment)
    }

    /**
     * Evaluate an experiment and decide if treatment is better.
     */
    private suspend fun evaluateExperiment(experiment: ABExperiment) {
        val control = experiment.controlGroup
        val treatment = experiment.treatmentGroup

        // Statistical test: is treatment success rate significantly higher?
        val zTest = computeZTest(
            control.successRate, control.sampleCount,
            treatment.successRate, treatment.sampleCount
        )

        val isSignificant = zTest > Z_SCORE_95
        val improvement = treatment.successRate - control.successRate
        val isBetter = improvement > MIN_IMPROVEMENT

        if (isSignificant && isBetter) {
            experiment.status = ExperimentStatus.COMPLETED_SUCCESS
            experiment.result = ExperimentResult(
                winner = ExperimentGroupType.TREATMENT,
                improvement = improvement,
                zScore = zTest,
                confidence = 0.95f,
                controlStats = GroupStats(
                    sampleCount = control.sampleCount,
                    successRate = control.successRate,
                    avgConfidence = control.avgConfidence,
                    avgLatencyMs = control.avgLatencyMs,
                    feedbackRate = control.feedbackRate,
                    correctionRate = control.correctionRate
                ),
                treatmentStats = GroupStats(
                    sampleCount = treatment.sampleCount,
                    successRate = treatment.successRate,
                    avgConfidence = treatment.avgConfidence,
                    avgLatencyMs = treatment.avgLatencyMs,
                    feedbackRate = treatment.feedbackRate,
                    correctionRate = treatment.correctionRate
                ),
                recommendation = "Apply treatment: ${"%.1f".format(improvement * 100)}% improvement (p < 0.05)"
            )

            Timber.i("ABTestEngine: experiment '%s' COMPLETED — treatment wins by %.1f%% (z=%.2f)",
                experiment.name, improvement * 100, zTest)
        } else if (isSignificant && improvement < -MIN_IMPROVEMENT) {
            // Treatment is worse!
            experiment.status = ExperimentStatus.COMPLETED_FAILURE
            experiment.result = ExperimentResult(
                winner = ExperimentGroupType.CONTROL,
                improvement = improvement,
                zScore = zTest,
                confidence = 0.95f,
                controlStats = GroupStats(
                    sampleCount = control.sampleCount,
                    successRate = control.successRate,
                    avgConfidence = control.avgConfidence,
                    avgLatencyMs = control.avgLatencyMs,
                    feedbackRate = control.feedbackRate,
                    correctionRate = control.correctionRate
                ),
                treatmentStats = GroupStats(
                    sampleCount = treatment.sampleCount,
                    successRate = treatment.successRate,
                    avgConfidence = treatment.avgConfidence,
                    avgLatencyMs = treatment.avgLatencyMs,
                    feedbackRate = treatment.feedbackRate,
                    correctionRate = treatment.correctionRate
                ),
                recommendation = "Reject treatment: ${"%.1f".format(-improvement * 100)}% regression (p < 0.05)"
            )

            Timber.w("ABTestEngine: experiment '%s' COMPLETED — treatment is WORSE by %.1f%%",
                experiment.name, -improvement * 100)
        }
        // else: not enough evidence yet, keep running
    }

    /**
     * Get the status of an experiment.
     */
    fun getExperiment(experimentId: String): ABExperiment? {
        return activeExperiments[experimentId]
    }

    /**
     * Get all active experiments.
     */
    fun getActiveExperiments(): List<ABExperiment> {
        return activeExperiments.values.filter { it.status == ExperimentStatus.RUNNING }
    }

    /**
     * Get completed experiment results.
     */
    suspend fun getCompletedExperiments(): List<ABExperiment> {
        return try {
            knowledgeDao.getByCategory(CATEGORY_AB_RESULTS).first()
                .mapNotNull { entry ->
                    try {
                        gson.fromJson(entry.value, ABExperiment::class.java)
                    } catch (e: Exception) {
                        null
                    }
                }
                .filter { it.status != ExperimentStatus.RUNNING }
        } catch (e: Exception) {
            Timber.w(e, "ABTestEngine: failed to load completed experiments")
            emptyList()
        }
    }

    // ── Statistical Helpers ────────────────────────────────────────────

    /**
     * Two-proportion z-test.
     * Tests if treatment proportion is significantly different from control.
     */
    private fun computeZTest(
        pControl: Float, nControl: Int,
        pTreatment: Float, nTreatment: Int
    ): Float {
        if (nControl == 0 || nTreatment == 0) return 0f

        val pPooled = (pControl * nControl + pTreatment * nTreatment) / (nControl + nTreatment)
        if (pPooled == 0f || pPooled == 1f) return 0f

        val se = sqrt(pPooled * (1 - pPooled) * (1.0f / nControl + 1.0f / nTreatment))
        if (se == 0f) return 0f

        return (pTreatment - pControl) / se
    }

    private fun updateGroupStats(group: ExperimentGroup) {
        val samples = group.samples
        group.sampleCount = samples.size
        group.successRate = if (samples.isNotEmpty()) {
            samples.count { it.success }.toFloat() / samples.size
        } else 0f
        group.avgConfidence = if (samples.isNotEmpty()) {
            samples.map { it.confidence }.average().toFloat()
        } else 0f
        group.avgLatencyMs = if (samples.isNotEmpty()) {
            samples.map { it.latencyMs }.average().toLong()
        } else 0
        group.feedbackRate = if (samples.isNotEmpty()) {
            val rated = samples.filter { it.userFeedback != null }
            if (rated.isNotEmpty()) rated.count { it.userFeedback == true }.toFloat() / rated.size else 0f
        } else 0f
        group.correctionRate = if (samples.isNotEmpty()) {
            samples.count { it.correctionMade }.toFloat() / samples.size
        } else 0f
    }

    // ── Persistence ────────────────────────────────────────────────────

    private suspend fun persistExperiment(experiment: ABExperiment) {
        try {
            val existing = knowledgeDao.getEntry(CATEGORY_AB_TESTS, experiment.id)
            val value = gson.toJson(experiment)
            if (existing != null) {
                knowledgeDao.update(existing.copy(
                    value = value,
                    updatedAt = System.currentTimeMillis()
                ))
            } else {
                knowledgeDao.insert(com.msaidizi.core.model.KnowledgeEntity(
                    category = CATEGORY_AB_TESTS,
                    key = experiment.id,
                    value = value,
                    confidence = 1.0f,
                    usageCount = 0
                ))
            }
        } catch (e: Exception) {
            Timber.w(e, "ABTestEngine: failed to persist experiment")
        }
    }
}

// ── Data Classes ────────────────────────────────────────────────────────

enum class ExperimentType {
    INTENT_WEIGHT,          // Test different IntentRouter weights
    TOOL_SELECTION,         // Test different tool sets for an intent
    CONTEXT_ASSEMBLY,       // Test different context assembly strategies
    PROMPT_TEMPLATE,        // Test different system prompts
    TIER_THRESHOLD,         // Test different tier escalation thresholds
    FULL_HARNESS_CONFIG     // Test a complete harness configuration change
}

enum class ExperimentStatus {
    RUNNING,
    COMPLETED_SUCCESS,
    COMPLETED_FAILURE,
    CANCELLED
}

enum class ExperimentGroupType {
    CONTROL,
    TREATMENT
}

data class ABExperiment(
    val id: String,
    val name: String,
    val description: String,
    val type: ExperimentType,
    val treatmentConfig: Map<String, Any>,
    var status: ExperimentStatus,
    val createdAt: Long,
    val controlGroup: ExperimentGroup,
    val treatmentGroup: ExperimentGroup,
    var result: ExperimentResult? = null
)

data class ExperimentGroup(
    val samples: MutableList<TraceSample> = mutableListOf(),
    var sampleCount: Int = 0,
    var successRate: Float = 0f,
    var avgConfidence: Float = 0f,
    var avgLatencyMs: Long = 0,
    var feedbackRate: Float = 0f,
    var correctionRate: Float = 0f
)

data class TraceSample(
    val userId: String,
    val success: Boolean,
    val confidence: Float,
    val latencyMs: Long,
    val userFeedback: Boolean?,
    val correctionMade: Boolean,
    val timestamp: Long
)

data class ExperimentResult(
    val winner: ExperimentGroupType,
    val improvement: Float,
    val zScore: Float,
    val confidence: Float,
    val controlStats: GroupStats,
    val treatmentStats: GroupStats,
    val recommendation: String
)

data class GroupStats(
    val sampleCount: Int,
    val successRate: Float,
    val avgConfidence: Float,
    val avgLatencyMs: Long,
    val feedbackRate: Float,
    val correctionRate: Float
)
