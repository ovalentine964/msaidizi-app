package com.msaidizi.app.skills

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SkillBridge — Bridge between on-device processing and backend skills.
 *
 * Connects Msaidizi Android app to Angavu Intelligence backend skills.
 * Each skill maps a university course unit into an executable capability.
 *
 * Features:
 * - Load skill definitions from backend
 * - Apply skills to local transaction data
 * - Cache results for offline use
 * - Sync skill metrics back to backend
 *
 * Skills Available:
 * - microfinance_analyzer (ECO 206) — Loan analysis, default risk prediction
 * - time_series_forecaster (STA 244) — Price forecasting, seasonal detection
 * - statistical_estimator (STA 341) — Point/interval estimation, Bayesian
 * - econometric_modeler (ECO 424) — OLS, IV, panel data, ARIMA, VAR
 * - worker_segmenter (STA 442) — PCA, factor analysis, clustering
 * - nonparametric_analyzer (STA 444) — Mann-Whitney, Kruskal-Wallis, KS
 */
@Singleton
class SkillBridge @Inject constructor(
    private val httpClient: HttpClient,
    private val json: Json,
) {
    companion object {
        private const val TAG = "SkillBridge"
        private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes
        private const val SKILLS_PATH = "/api/v1/skills"
    }

    // ── Skill Cache ─────────────────────────────────────────────────

    private val skillCache = ConcurrentHashMap<String, CachedSkill>()
    private val resultCache = ConcurrentHashMap<String, CachedResult>()

    @Serializable
    data class SkillInfo(
        val name: String,
        val courseUnit: String,
        val description: String,
        val version: String,
        val status: String,
        val agentBindings: List<String>,
    )

    @Serializable
    data class SkillResult(
        val success: Boolean,
        val skillName: String,
        val data: Map<String, JsonElement> = emptyMap(),
        val error: String? = null,
        val durationMs: Double = 0.0,
        val confidence: Double = 0.0,
    )

    @Serializable
    data class SkillExecuteRequest(
        val action: String,
        val params: Map<String, JsonElement> = emptyMap(),
    )

    @Serializable
    data class SkillListResponse(
        val skills: List<SkillInfo>,
        val total: Int,
    )

    @Serializable
    data class SkillSummary(
        val totalSkills: Int,
        val activeSkills: Int,
        val totalExecutions: Int,
        val overallSuccessRate: Double,
    )

    private data class CachedSkill(
        val info: SkillInfo,
        val cachedAt: Long = System.currentTimeMillis(),
    ) {
        val isExpired: Boolean get() = System.currentTimeMillis() - cachedAt > CACHE_TTL_MS
    }

    private data class CachedResult(
        val result: SkillResult,
        val cachedAt: Long = System.currentTimeMillis(),
    ) {
        val isExpired: Boolean get() = System.currentTimeMillis() - cachedAt > CACHE_TTL_MS
    }

    // ── Public API ──────────────────────────────────────────────────

    /**
     * Load all available skills from backend.
     * Caches results for offline access.
     */
    suspend fun loadSkills(): List<SkillInfo> = withContext(Dispatchers.IO) {
        try {
            val response: SkillListResponse = httpClient.get(SKILLS_PATH).body()
            val skills = response.skills

            // Cache each skill
            skills.forEach { skill ->
                skillCache[skill.name] = CachedSkill(skill)
            }

            Timber.d(TAG, "Loaded ${skills.size} skills from backend")
            skills
        } catch (e: Exception) {
            Timber.w(TAG, "Failed to load skills from backend, using cache: ${e.message}")
            skillCache.values.map { it.info }
        }
    }

    /**
     * Get skill info (cached or fresh).
     */
    suspend fun getSkill(skillName: String): SkillInfo? {
        val cached = skillCache[skillName]
        if (cached != null && !cached.isExpired) {
            return cached.info
        }

        return try {
            val response: SkillInfo = httpClient.get("$SKILLS_PATH/$skillName").body()
            skillCache[skillName] = CachedSkill(response)
            response
        } catch (e: Exception) {
            Timber.w(TAG, "Failed to get skill $skillName: ${e.message}")
            cached?.info
        }
    }

    /**
     * Execute a skill action with local data.
     * Results are cached for offline use.
     */
    suspend fun executeSkill(
        skillName: String,
        action: String,
        params: Map<String, JsonElement> = emptyMap(),
        useCache: Boolean = true,
    ): SkillResult = withContext(Dispatchers.IO) {
        val cacheKey = "$skillName:$action:${params.hashCode()}"

        // Check cache
        if (useCache) {
            val cached = resultCache[cacheKey]
            if (cached != null && !cached.isExpired) {
                Timber.d(TAG, "Cache hit for $cacheKey")
                return@withContext cached.result
            }
        }

        try {
            val request = SkillExecuteRequest(action = action, params = params)
            val response: SkillResult = httpClient.post("$SKILLS_PATH/$skillName/execute") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body()

            // Cache result
            resultCache[cacheKey] = CachedResult(response)

            Timber.d(
                TAG,
                "Executed $skillName.$action: success=${response.success}, " +
                    "confidence=${response.confidence}, duration=${response.durationMs}ms"
            )

            response
        } catch (e: Exception) {
            Timber.e(TAG, "Failed to execute $skillName.$action: ${e.message}")

            // Return cached result if available (offline mode)
            val cached = resultCache[cacheKey]
            if (cached != null) {
                Timber.d(TAG, "Returning stale cached result for $cacheKey")
                return@withContext cached.result.copy(
                    error = "Offline: using cached result (${e.message})"
                )
            }

            SkillResult(
                success = false,
                skillName = skillName,
                error = "Skill execution failed: ${e.message}"
            )
        }
    }

    // ── Convenience Methods ─────────────────────────────────────────

    /**
     * Analyze loan terms (ECO 206 — Microfinance).
     */
    suspend fun analyzeLoan(
        principal: Double,
        interestRate: Double,
        tenureDays: Int,
        repaymentFrequency: String = "weekly",
    ): SkillResult {
        return executeSkill(
            "microfinance_analyzer",
            "analyze_loan_terms",
            mapOf(
                "principal" to json.encodeToJsonElement(Double.serializer(), principal),
                "interest_rate" to json.encodeToJsonElement(Double.serializer(), interestRate),
                "tenure_days" to json.encodeToJsonElement(Int.serializer(), tenureDays),
                "repayment_frequency" to json.encodeToJsonElement(String.serializer(), repaymentFrequency),
            )
        )
    }

    /**
     * Predict default risk for a loan (ECO 206).
     */
    suspend fun predictDefaultRisk(
        repaymentHistory: List<Map<String, JsonElement>>,
        loanAmount: Double,
        dailyIncomeEstimate: Double,
        daysActive: Int,
        purpose: String = "stock",
    ): SkillResult {
        return executeSkill(
            "microfinance_analyzer",
            "predict_default_risk",
            mapOf(
                "repayment_history" to json.encodeToJsonElement(repaymentHistory),
                "loan_amount" to json.encodeToJsonElement(Double.serializer(), loanAmount),
                "daily_income_estimate" to json.encodeToJsonElement(Double.serializer(), dailyIncomeEstimate),
                "days_active" to json.encodeToJsonElement(Int.serializer(), daysActive),
                "purpose" to json.encodeToJsonElement(String.serializer(), purpose),
            )
        )
    }

    /**
     * Forecast prices from historical data (STA 244 — Time Series).
     */
    suspend fun forecastPrices(
        prices: List<Double>,
        steps: Int = 7,
        method: String = "auto",
    ): SkillResult {
        return executeSkill(
            "time_series_forecaster",
            "forecast_prices",
            mapOf(
                "prices" to json.encodeToJsonElement(prices),
                "steps" to json.encodeToJsonElement(Int.serializer(), steps),
                "method" to json.encodeToJsonElement(String.serializer(), method),
            )
        )
    }

    /**
     * Detect seasonal patterns (STA 244).
     */
    suspend fun detectSeasonality(
        data: List<Double>,
        period: Int = 7,
    ): SkillResult {
        return executeSkill(
            "time_series_forecaster",
            "detect_seasonality",
            mapOf(
                "data" to json.encodeToJsonElement(data),
                "period" to json.encodeToJsonElement(Int.serializer(), period),
            )
        )
    }

    /**
     * Run Bayesian estimation (STA 341).
     */
    suspend fun bayesianEstimate(
        data: List<Double>,
        distribution: String = "bernoulli",
        priorParams: Map<String, Double> = emptyMap(),
    ): SkillResult {
        return executeSkill(
            "statistical_estimator",
            "bayesian_estimate",
            mapOf(
                "data" to json.encodeToJsonElement(data),
                "distribution" to json.encodeToJsonElement(String.serializer(), distribution),
                "prior_params" to json.encodeToJsonElement(priorParams),
            )
        )
    }

    /**
     * Segment workers using clustering (STA 442).
     */
    suspend fun segmentWorkers(
        features: List<List<Double>>,
        featureNames: List<String>,
        maxK: Int = 8,
    ): SkillResult {
        return executeSkill(
            "worker_segmenter",
            "cluster_segment",
            mapOf(
                "X" to json.encodeToJsonElement(features),
                "feature_names" to json.encodeToJsonElement(featureNames),
                "max_k" to json.encodeToJsonElement(Int.serializer(), maxK),
            )
        )
    }

    // ── Cache Management ────────────────────────────────────────────

    /**
     * Clear all cached data.
     */
    fun clearCache() {
        skillCache.clear()
        resultCache.clear()
        Timber.d(TAG, "Cache cleared")
    }

    /**
     * Clear expired cache entries.
     */
    fun pruneCache() {
        val skillBefore = skillCache.size
        skillCache.entries.removeAll { it.value.isExpired }
        resultCache.entries.removeAll { it.value.isExpired }
        Timber.d(
            TAG,
            "Cache pruned: skills $skillBefore→${skillCache.size}, results pruned"
        )
    }

    /**
     * Get cache statistics for diagnostics.
     */
    fun getCacheStats(): Map<String, Any> {
        return mapOf(
            "cached_skills" to skillCache.size,
            "cached_results" to resultCache.size,
            "expired_skills" to skillCache.values.count { it.isExpired },
            "expired_results" to resultCache.values.count { it.isExpired },
        )
    }
}
