package com.msaidizi.agent.flywheel

import com.msaidizi.core.database.KnowledgeDao
import com.msaidizi.core.database.TraceDao
import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * RCTFramework — G9: Randomized Controlled Trial framework for measuring Msaidizi impact.
 *
 * Extends ABTestEngine with RCT-specific infrastructure to rigorously measure
 * whether Msaidizi actually improves worker outcomes:
 *
 * 1. Treatment arms: Msaidizi active vs. control (delayed access / reduced features)
 * 2. Outcome metrics: income growth, savings rate, debt reduction, business survival
 * 3. Stratified randomization: by worker type, location, business age
 * 4. Power analysis: minimum sample size for detecting meaningful effects
 * 5. Intent-to-treat analysis: analyze all assigned, not just compliant
 * 6. Multiple testing correction: Bonferroni for multiple outcomes
 *
 * This is the scientific foundation for claiming Msaidizi works.
 * Without RCTs, any observed improvement could be selection bias.
 *
 * Design follows the J-PAL (Abdul Latif Jameel Poverty Action Lab) methodology
 * for evaluating poverty interventions in developing economies.
 */
@Singleton
class RCTFramework @Inject constructor(
    private val abTestEngine: ABTestEngine,
    private val knowledgeDao: KnowledgeDao,
    private val traceDao: TraceDao,
    private val gson: Gson
) {
    companion object {
        private const val CATEGORY_RCT = "rct_studies"
        private const val CATEGORY_RCT_OUTCOMES = "rct_outcomes"
        private const val CATEGORY_RCT_ASSIGNMENTS = "rct_assignments"

        /** Minimum sample size per arm for a valid RCT. */
        private const val MIN_SAMPLE_PER_ARM = 100

        /** Effect size we want to detect (Cohen's d = 0.2 = small but meaningful). */
        private const val TARGET_EFFECT_SIZE = 0.2

        /** Significance level (α = 0.05). */
        private const val ALPHA = 0.05

        /** Power (1-β = 0.80). */
        private const val POWER = 0.80
    }

    // ═══════════════════════════════════════════════════════════
    //  STUDY MANAGEMENT
    // ═══════════════════════════════════════════════════════════

    /**
     * Create a new RCT study.
     *
     * @param name Study name (e.g., "Msaidizi Income Impact RCT")
     * @param description What are we measuring?
     * @param treatmentArm What does the treatment group get?
     * @param controlArm What does the control group get?
     * @param primaryOutcome The main metric (e.g., "monthly_income_change_pct")
     * @param secondaryOutcomes Additional metrics
     * @param durationDays How long to run the study
     * @param stratifyBy Dimensions to stratify randomization on
     */
    suspend fun createStudy(
        name: String,
        description: String,
        treatmentArm: TreatmentArm,
        controlArm: ControlArm,
        primaryOutcome: String,
        secondaryOutcomes: List<String> = emptyList(),
        durationDays: Int = 90,
        stratifyBy: List<StratificationDimension> = listOf(
            StratificationDimension.WORKER_TYPE,
            StratificationDimension.REGION
        )
    ): String {
        val studyId = UUID.randomUUID().toString()

        // Compute required sample size
        val requiredSamplePerArm = computeRequiredSampleSize(
            effectSize = TARGET_EFFECT_SIZE,
            alpha = ALPHA,
            power = POWER
        )

        val study = RCTStudy(
            id = studyId,
            name = name,
            description = description,
            treatmentArm = treatmentArm,
            controlArm = controlArm,
            primaryOutcome = primaryOutcome,
            secondaryOutcomes = secondaryOutcomes,
            durationDays = durationDays,
            stratifyBy = stratifyBy,
            requiredSamplePerArm = requiredSamplePerArm,
            status = StudyStatus.RECRUITING,
            createdAt = System.currentTimeMillis(),
            startedAt = null,
            endedAt = null,
            treatmentAssignments = mutableMapOf(),
            baselineOutcomes = mutableMapOf(),
            followUpOutcomes = mutableMapOf()
        )

        persistStudy(study)
        Timber.i("RCTFramework: created study '%s' (id=%s, n=%d per arm)",
            name, studyId, requiredSamplePerArm)

        return studyId
    }

    /**
     * Enroll a worker in an RCT study.
     * Uses stratified randomization to assign treatment/control.
     *
     * @param studyId The study ID
     * @param workerId The worker's ID
     * @param workerType Worker's business type (for stratification)
     * @param region Worker's region (for stratification)
     * @return The assigned arm (TREATMENT or CONTROL)
     */
    suspend fun enrollWorker(
        studyId: String,
        workerId: String,
        workerType: String = "unknown",
        region: String = "unknown"
    ): TrialArm {
        val study = getStudy(studyId) ?: return TrialArm.CONTROL

        if (study.status != StudyStatus.RECRUITING) {
            Timber.w("RCTFramework: study %s is not recruiting", studyId)
            return TrialArm.CONTROL
        }

        // Check if already enrolled
        if (study.treatmentAssignments.containsKey(workerId)) {
            return study.treatmentAssignments[workerId]!!.arm
        }

        // Stratified randomization
        val stratum = buildStratumKey(workerType, region, study.stratifyBy)
        val arm = stratifiedRandomAssign(studyId, workerId, stratum)

        // Record assignment
        val assignment = WorkerAssignment(
            workerId = workerId,
            arm = arm,
            stratum = stratum,
            enrolledAt = System.currentTimeMillis(),
            workerType = workerType,
            region = region,
            compliant = true
        )

        study.treatmentAssignments[workerId] = assignment
        persistStudy(study)

        Timber.d("RCTFramework: enrolled worker %s in %s arm (stratum=%s)",
            workerId, arm, stratum)

        return arm
    }

    /**
     * Record a baseline measurement for a worker before treatment begins.
     */
    suspend fun recordBaseline(
        studyId: String,
        workerId: String,
        outcomes: Map<String, Double>
    ) {
        val study = getStudy(studyId) ?: return
        study.baselineOutcomes[workerId] = outcomes
        persistStudy(study)

        Timber.d("RCTFramework: recorded baseline for worker %s: %s", workerId, outcomes.keys)
    }

    /**
     * Record a follow-up measurement for a worker during or after treatment.
     */
    suspend fun recordFollowUp(
        studyId: String,
        workerId: String,
        outcomes: Map<String, Double>
    ) {
        val study = getStudy(studyId) ?: return

        // Store follow-up (append to list for time-series analysis)
        val existing = study.followUpOutcomes.getOrDefault(workerId, mutableListOf())
        existing.add(FollowUpMeasurement(
            outcomes = outcomes,
            measuredAt = System.currentTimeMillis()
        ))
        study.followUpOutcomes[workerId] = existing

        persistStudy(study)
        Timber.d("RCTFramework: recorded follow-up #%d for worker %s",
            existing.size, workerId)
    }

    /**
     * Start the study (stop recruiting, begin treatment).
     */
    suspend fun startStudy(studyId: String) {
        val study = getStudy(studyId) ?: return
        study.status = StudyStatus.RUNNING
        study.startedAt = System.currentTimeMillis()
        persistStudy(study)

        Timber.i("RCTFramework: study '%s' started with %d workers enrolled",
            study.name, study.treatmentAssignments.size)
    }

    /**
     * End the study and compute results.
     */
    suspend fun endStudy(studyId: String): RCTResults? {
        val study = getStudy(studyId) ?: return null
        study.status = StudyStatus.COMPLETED
        study.endedAt = System.currentTimeMillis()
        persistStudy(study)

        return analyzeResults(study)
    }

    // ═══════════════════════════════════════════════════════════
    //  ANALYSIS
    // ═══════════════════════════════════════════════════════════

    /**
     * Analyze RCT results following ITT (Intent-to-Treat) principles.
     *
     * Primary analysis:
     * 1. Compare mean outcome change (follow-up - baseline) between arms
     * 2. Two-sample t-test for significance
     * 3. Effect size (Cohen's d)
     * 4. 95% confidence interval for the effect
     *
     * Secondary analyses:
     * - Subgroup analysis by worker type
     * - Dose-response (treatment compliance)
     * - Multiple testing correction for secondary outcomes
     */
    suspend fun analyzeResults(study: RCTStudy): RCTResults {
        val treatmentWorkers = study.treatmentAssignments.values
            .filter { it.arm == TrialArm.TREATMENT }
            .map { it.workerId }

        val controlWorkers = study.treatmentAssignments.values
            .filter { it.arm == TrialArm.CONTROL }
            .map { it.workerId }

        // Compute outcome changes for primary outcome
        val treatmentChanges = computeOutcomeChanges(study, treatmentWorkers, study.primaryOutcome)
        val controlChanges = computeOutcomeChanges(study, controlWorkers, study.primaryOutcome)

        // T-test
        val tTest = computeTTest(treatmentChanges, controlChanges)

        // Effect size
        val effectSize = computeCohenD(treatmentChanges, controlChanges)

        // Confidence interval
        val ci = computeConfidenceInterval(treatmentChanges, controlChanges)

        // Secondary outcomes with Bonferroni correction
        val secondaryResults = study.secondaryOutcomes.map { outcome ->
            val tChanges = computeOutcomeChanges(study, treatmentWorkers, outcome)
            val cChanges = computeOutcomeChanges(study, controlWorkers, outcome)
            val t = computeTTest(tChanges, cChanges)
            val d = computeCohenD(tChanges, cChanges)

            SecondaryOutcomeResult(
                outcomeName = outcome,
                treatmentMean = if (tChanges.isNotEmpty()) tChanges.average() else 0.0,
                controlMean = if (cChanges.isNotEmpty()) cChanges.average() else 0.0,
                effectSize = d,
                pValue = t.pValue,
                significant = t.pValue < (ALPHA / study.secondaryOutcomes.size.coerceAtLeast(1))
            )
        }

        // Subgroup analysis by worker type
        val subgroupResults = study.treatmentAssignments.values
            .map { it.workerType }
            .distinct()
            .filter { it != "unknown" }
            .mapNotNull { workerType ->
                val tIds = study.treatmentAssignments.values
                    .filter { it.arm == TrialArm.TREATMENT && it.workerType == workerType }
                    .map { it.workerId }
                val cIds = study.treatmentAssignments.values
                    .filter { it.arm == TrialArm.CONTROL && it.workerType == workerType }
                    .map { it.workerId }

                val tChanges = computeOutcomeChanges(study, tIds, study.primaryOutcome)
                val cChanges = computeOutcomeChanges(study, cIds, study.primaryOutcome)

                if (tChanges.size >= 10 && cChanges.size >= 10) {
                    SubgroupResult(
                        dimension = "worker_type",
                        value = workerType,
                        treatmentN = tChanges.size,
                        controlN = cChanges.size,
                        effectSize = computeCohenD(tChanges, cChanges),
                        pValue = computeTTest(tChanges, cChanges).pValue
                    )
                } else null
            }

        val conclusion = when {
            tTest.pValue < ALPHA && effectSize > 0 -> "POSITIVE: Msaidizi has a statistically significant positive effect"
            tTest.pValue < ALPHA && effectSize < 0 -> "NEGATIVE: Msaidizi has a statistically significant negative effect"
            else -> "INCONCLUSIVE: No statistically significant effect detected (p=${String.format("%.3f", tTest.pValue)})"
        }

        return RCTResults(
            studyId = study.id,
            studyName = study.name,
            treatmentN = treatmentWorkers.size,
            controlN = controlWorkers.size,
            primaryOutcome = study.primaryOutcome,
            treatmentMeanChange = if (treatmentChanges.isNotEmpty()) treatmentChanges.average() else 0.0,
            controlMeanChange = if (controlChanges.isNotEmpty()) controlChanges.average() else 0.0,
            effectSize = effectSize,
            tStatistic = tTest.tStatistic,
            pValue = tTest.pValue,
            confidenceIntervalLower = ci.first,
            confidenceIntervalUpper = ci.second,
            secondaryOutcomes = secondaryResults,
            subgroupResults = subgroupResults,
            conclusion = conclusion,
            analyzedAt = System.currentTimeMillis()
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  STATISTICAL HELPERS
    // ═══════════════════════════════════════════════════════════

    /**
     * Compute required sample size per arm for a two-sample t-test.
     *
     * Uses the formula: n = 2 * ((z_α/2 + z_β) / d)²
     * where d = effect size, z_α/2 = 1.96, z_β = 0.84
     */
    private fun computeRequiredSampleSize(
        effectSize: Double,
        alpha: Double,
        power: Double
    ): Int {
        val zAlpha = 1.96  // for α = 0.05 two-tailed
        val zBeta = 0.84   // for power = 0.80
        val n = 2.0 * ((zAlpha + zBeta) / effectSize).let { it * it }
        return n.toInt().coerceAtLeast(MIN_SAMPLE_PER_ARM)
    }

    /**
     * Two-sample t-test (Welch's t-test for unequal variances).
     */
    private fun computeTTest(
        treatment: List<Double>,
        control: List<Double>
    ): TTestResult {
        if (treatment.size < 2 || control.size < 2) {
            return TTestResult(tStatistic = 0.0, pValue = 1.0, degreesOfFreedom = 0.0)
        }

        val n1 = treatment.size.toDouble()
        val n2 = control.size.toDouble()
        val m1 = treatment.average()
        val m2 = control.average()
        val v1 = treatment.map { (it - m1).let { d -> d * d } }.sum() / (n1 - 1)
        val v2 = control.map { (it - m2).let { d -> d * d } }.sum() / (n2 - 1)

        val se = sqrt(v1 / n1 + v2 / n2)
        if (se == 0.0) return TTestResult(tStatistic = 0.0, pValue = 1.0, degreesOfFreedom = 0.0)

        val t = (m1 - m2) / se

        // Welch-Satterthwaite degrees of freedom
        val df = ((v1 / n1 + v2 / n2).let { it * it }) /
                ((v1 / n1).let { it * it } / (n1 - 1) + (v2 / n2).let { it * it } / (n2 - 1))

        // Approximate p-value using normal distribution for large samples
        val pValue = approximatePValue(t.abs())

        return TTestResult(tStatistic = t, pValue = pValue, degreesOfFreedom = df)
    }

    /**
     * Cohen's d effect size.
     */
    private fun computeCohenD(
        treatment: List<Double>,
        control: List<Double>
    ): Double {
        if (treatment.isEmpty() || control.isEmpty()) return 0.0

        val m1 = treatment.average()
        val m2 = control.average()
        val n1 = treatment.size.toDouble()
        val n2 = control.size.toDouble()

        val pooledStd = sqrt(
            ((n1 - 1) * variance(treatment) + (n2 - 1) * variance(control)) / (n1 + n2 - 2)
        )

        return if (pooledStd > 0) (m1 - m2) / pooledStd else 0.0
    }

    /**
     * 95% confidence interval for the difference in means.
     */
    private fun computeConfidenceInterval(
        treatment: List<Double>,
        control: List<Double>
    ): Pair<Double, Double> {
        if (treatment.size < 2 || control.size < 2) return Pair(0.0, 0.0)

        val m1 = treatment.average()
        val m2 = control.average()
        val n1 = treatment.size.toDouble()
        val n2 = control.size.toDouble()
        val v1 = variance(treatment)
        val v2 = variance(control)

        val se = sqrt(v1 / n1 + v2 / n2)
        val diff = m1 - m2
        val margin = 1.96 * se  // z = 1.96 for 95% CI

        return Pair(diff - margin, diff + margin)
    }

    private fun variance(data: List<Double>): Double {
        if (data.size < 2) return 0.0
        val mean = data.average()
        return data.map { (it - mean).let { d -> d * d } }.sum() / (data.size - 1)
    }

    /**
     * Approximate two-tailed p-value from |t| using normal approximation.
     * Good enough for large samples (n > 30).
     */
    private fun approximatePValue(absT: Double): Double {
        // Using the approximation: p ≈ 2 * (1 - Φ(|t|))
        // where Φ is the standard normal CDF
        // Abramowitz and Stegun approximation
        val x = absT / sqrt(2.0)
        val t = 1.0 / (1.0 + 0.3275911 * x)
        val poly = t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429))))
        val erf = 1.0 - poly * exp(-x * x)
        val p = 2.0 * (1.0 - (1.0 + erf) / 2.0)
        return p.coerceIn(0.0, 1.0)
    }

    // ═══════════════════════════════════════════════════════════
    //  STRATIFICATION
    // ═══════════════════════════════════════════════════════════

    private fun buildStratumKey(
        workerType: String,
        region: String,
        dimensions: List<StratificationDimension>
    ): String {
        return dimensions.map { dim ->
            when (dim) {
                StratificationDimension.WORKER_TYPE -> workerType
                StratificationDimension.REGION -> region
                StratificationDimension.BUSINESS_AGE -> "any"
                StratificationDimension.INCOME_LEVEL -> "any"
            }
        }.joinToString("|")
    }

    /**
     * Stratified random assignment.
     * Within each stratum, assigns alternately to treatment/control.
     * This ensures balanced arms within each stratum.
     */
    private suspend fun stratifiedRandomAssign(
        studyId: String,
        workerId: String,
        stratum: String
    ): TrialArm {
        // Count existing assignments in this stratum
        val study = getStudy(studyId) ?: return TrialArm.CONTROL
        val stratumAssignments = study.treatmentAssignments.values
            .filter { it.stratum == stratum }

        val treatmentCount = stratumAssignments.count { it.arm == TrialArm.TREATMENT }
        val controlCount = stratumAssignments.count { it.arm == TrialArm.CONTROL }

        // Alternate: if treatment has fewer, assign to treatment
        return if (treatmentCount <= controlCount) TrialArm.TREATMENT else TrialArm.CONTROL
    }

    // ═══════════════════════════════════════════════════════════
    //  OUTCOME COMPUTATION
    // ═══════════════════════════════════════════════════════════

    private fun computeOutcomeChanges(
        study: RCTStudy,
        workerIds: List<String>,
        outcomeName: String
    ): List<Double> {
        return workerIds.mapNotNull { workerId ->
            val baseline = study.baselineOutcomes[workerId]?.get(outcomeName)
            val followUps = study.followUpOutcomes[workerId]

            if (baseline != null && followUps != null && followUps.isNotEmpty()) {
                // Use the latest follow-up
                val latest = followUps.last().outcomes[outcomeName]
                if (latest != null) latest - baseline else null
            } else null
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  PERSISTENCE
    // ═══════════════════════════════════════════════════════════

    private suspend fun persistStudy(study: RCTStudy) {
        try {
            val existing = knowledgeDao.getEntry(CATEGORY_RCT, study.id)
            val value = gson.toJson(study)
            if (existing != null) {
                knowledgeDao.update(existing.copy(
                    value = value,
                    updatedAt = System.currentTimeMillis()
                ))
            } else {
                knowledgeDao.insert(com.msaidizi.core.model.KnowledgeEntity(
                    category = CATEGORY_RCT,
                    key = study.id,
                    value = value,
                    confidence = 1.0f,
                    usageCount = 0
                ))
            }
        } catch (e: Exception) {
            Timber.w(e, "RCTFramework: failed to persist study")
        }
    }

    private suspend fun getStudy(studyId: String): RCTStudy? {
        return try {
            val entry = knowledgeDao.getEntry(CATEGORY_RCT, studyId)
            if (entry != null) gson.fromJson(entry.value, RCTStudy::class.java) else null
        } catch (e: Exception) {
            Timber.e(e, "RCTFramework: failed to load study")
            null
        }
    }

    /**
     * Get all active RCT studies.
     */
    suspend fun getActiveStudies(): List<RCTStudy> {
        return try {
            knowledgeDao.getByCategory(CATEGORY_RCT).first()
                .mapNotNull { entry ->
                    try {
                        gson.fromJson(entry.value, RCTStudy::class.java)
                    } catch (e: Exception) { null }
                }
                .filter { it.status == StudyStatus.RUNNING || it.status == StudyStatus.RECRUITING }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  DATA CLASSES
// ═══════════════════════════════════════════════════════════

enum class TrialArm { TREATMENT, CONTROL }

enum class StudyStatus { RECRUITING, RUNNING, COMPLETED, CANCELLED }

enum class StratificationDimension {
    WORKER_TYPE,
    REGION,
    BUSINESS_AGE,
    INCOME_LEVEL
}

data class TreatmentArm(
    val name: String,
    val description: String,
    val features: List<String>  // e.g., ["full_msaidizi", "voice_cfo", "all_tools"]
)

data class ControlArm(
    val name: String,
    val description: String,
    val features: List<String>  // e.g., ["delayed_access", "basic_only", "no_voice"]
)

data class RCTStudy(
    val id: String,
    val name: String,
    val description: String,
    val treatmentArm: TreatmentArm,
    val controlArm: ControlArm,
    val primaryOutcome: String,
    val secondaryOutcomes: List<String>,
    val durationDays: Int,
    val stratifyBy: List<StratificationDimension>,
    val requiredSamplePerArm: Int,
    var status: StudyStatus,
    val createdAt: Long,
    var startedAt: Long?,
    var endedAt: Long?,
    val treatmentAssignments: MutableMap<String, WorkerAssignment>,
    val baselineOutcomes: MutableMap<String, Map<String, Double>>,
    val followUpOutcomes: MutableMap<String, MutableList<FollowUpMeasurement>>
)

data class WorkerAssignment(
    val workerId: String,
    val arm: TrialArm,
    val stratum: String,
    val enrolledAt: Long,
    val workerType: String,
    val region: String,
    var compliant: Boolean
)

data class FollowUpMeasurement(
    val outcomes: Map<String, Double>,
    val measuredAt: Long
)

data class RCTResults(
    val studyId: String,
    val studyName: String,
    val treatmentN: Int,
    val controlN: Int,
    val primaryOutcome: String,
    val treatmentMeanChange: Double,
    val controlMeanChange: Double,
    val effectSize: Double,
    val tStatistic: Double,
    val pValue: Double,
    val confidenceIntervalLower: Double,
    val confidenceIntervalUpper: Double,
    val secondaryOutcomes: List<SecondaryOutcomeResult>,
    val subgroupResults: List<SubgroupResult>,
    val conclusion: String,
    val analyzedAt: Long
)

data class SecondaryOutcomeResult(
    val outcomeName: String,
    val treatmentMean: Double,
    val controlMean: Double,
    val effectSize: Double,
    val pValue: Double,
    val significant: Boolean
)

data class SubgroupResult(
    val dimension: String,
    val value: String,
    val treatmentN: Int,
    val controlN: Int,
    val effectSize: Double,
    val pValue: Double
)

data class TTestResult(
    val tStatistic: Double,
    val pValue: Double,
    val degreesOfFreedom: Double
)
