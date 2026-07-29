package com.msaidizi.agent.tools

import com.msaidizi.core.database.DailySummaryDao
import com.msaidizi.core.database.SaleDao
import com.msaidizi.agent.memory.MemoryManager
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

data class AlamaScoreResult(
    val score: Int,          // 300-850
    val level: String,       // "New", "Building", "Good", "Strong", "Excellent"
    val factors: List<String>,
    val creditReady: Boolean
)

/**
 * AlamaScore — Credit scoring tool built from the worker's actual business data.
 *
 * Computes a score (300–850) from:
 *   1. Transaction consistency over the last 90 days
 *   2. Transaction volume
 *   3. Business growth (first month vs last month)
 *   4. Savings behavior (from memory)
 *   5. M-Pesa usage
 */
@Singleton
class AlamaScore @Inject constructor(
    private val saleDao: SaleDao,
    private val dailySummaryDao: DailySummaryDao,
    private val memoryManager: MemoryManager
) : Tool {

    override val name = "alama_score"
    override val description = "Build credit score from business data"

    override val argsSchema = argSchema {
        enum("action", "Action to perform",
            listOf("calculate", "pca", "factor_analysis", "cluster_analysis", "full_multivariate"),
            required = false)
        string("features", "Comma-separated feature names for multivariate analysis", required = false)
        integer("n_components", "Number of PCA components to extract", required = false)
        integer("n_clusters", "Number of clusters for segmentation", required = false)
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"] ?: "calculate"
        return try {
            when (action.lowercase()) {
                "calculate" -> {
                    val result = calculateScore()
                    ToolResult.success(
                        toolName = name,
                        data = mapOf("score" to result.score, "level" to result.level, "factors" to result.factors, "creditReady" to result.creditReady),
                        message = "Alama Score: ${result.score} (${result.level})" + if (result.creditReady) " — Credit ready!" else ""
                    )
                }
                "pca" -> performPCA(params)
                "factor_analysis" -> performFactorAnalysis(params)
                "cluster_analysis" -> performClusterAnalysis(params)
                "full_multivariate" -> fullMultivariateAnalysis(params)
                else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
            }
        } catch (e: Exception) { Timber.e(e, "Failed to calculate Alama Score"); ToolResult.error(name, "Failed: ${e.message}", "CALCULATION_ERROR") }
    }

    suspend fun calculateScore(): AlamaScoreResult {
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val today = now.atZone(zone).toLocalDate()
        val ninetyDaysAgo = today.minusDays(90)

        val startMillis = ninetyDaysAgo.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillis = now.toEpochMilli()

        // Fetch data from DAOs
        val sales = saleDao.getSalesBetween(startMillis, endMillis).first()
        val dailySummaries = dailySummaryDao.getSummariesBetween(
            ninetyDaysAgo.format(DateTimeFormatter.ISO_LOCAL_DATE),
            today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        ).first()

        var score = 300 // Base score
        val factors = mutableListOf<String>()

        // Factor 1: Transaction consistency (0-150 points)
        // Count days with recorded sales activity
        val activeDays = dailySummaries.count { it.totalSales > 0 }
        val consistency = activeDays / 90.0
        val consistencyPoints = (consistency * 150).toInt()
        score += consistencyPoints
        if (consistencyPoints > 100) factors.add("Consistent daily transactions (+$consistencyPoints)")

        // Factor 2: Transaction volume (0-100 points)
        val totalTransactions = dailySummaries.sumOf { it.transactionCount }.coerceAtLeast(sales.size)
        val volumePoints = minOf(totalTransactions, 500) / 5
        score += volumePoints
        if (volumePoints > 50) factors.add("Strong transaction volume (+$volumePoints)")

        // Factor 3: Business growth (0-100 points)
        val firstMonth = dailySummaries.drop(60).sumOf { it.totalSales }   // days 61-90 ago (older month)
        val lastMonth = dailySummaries.take(30).sumOf { it.totalSales }     // days 1-30 (recent month)
        if (firstMonth > 0) {
            val growth = (lastMonth - firstMonth) / firstMonth
            val growthPoints = minOf((growth * 100).toInt(), 100).coerceAtLeast(0)
            score += growthPoints
            if (growthPoints > 0) factors.add("Business growing (+$growthPoints)")
        }

        // Factor 4: Savings behavior (0-50 points)
        val savingsGoals = memoryManager.retrieve("savings_goals")
        if (savingsGoals.isNotBlank()) {
            score += 50
            factors.add("Active savings goals (+50)")
        }

        // Factor 5: M-Pesa usage (0-50 points)
        val mpesaTransactions = sales.count { it.paymentMethod == "mpesa" }
        if (mpesaTransactions > 10) {
            score += 50
            factors.add("Regular M-Pesa usage (+50)")
        }

        score = score.coerceIn(300, 850)

        val level = when {
            score < 400 -> "New"
            score < 500 -> "Building"
            score < 650 -> "Good"
            score < 750 -> "Strong"
            else -> "Excellent"
        }

        return AlamaScoreResult(
            score = score,
            level = level,
            factors = factors,
            creditReady = score >= 500
        )
    }

    // ══════════════════════════════════════════════════════════════
    // STA 442: Multivariate Analysis Methods
    // ══════════════════════════════════════════════════════════════

    private suspend fun performPCA(params: Map<String, String>): ToolResult {
        val featureNames = params["features"]?.split(",")?.map { it.trim() } ?: listOf("consistency", "volume", "growth", "mpesa", "savings")
        val nComp = params["n_components"]?.toIntOrNull() ?: 2
        val matrix = buildFeatureMatrix()
        if (matrix.isEmpty()) return ToolResult.error(name, "Not enough data. Need ≥30 days.", "INSUFFICIENT_DATA")
        val n = matrix.size; val p = matrix[0].size

        // Standardize
        val means = DoubleArray(p) { j -> matrix.sumOf { it[j] } / n }
        val stds = DoubleArray(p) { j -> sqrt(matrix.sumOf { (it[j] - means[j]).pow(2) } / (n - 1)).coerceAtLeast(1e-10) }
        val standardized = matrix.map { row -> DoubleArray(p) { j -> (row[j] - means[j]) / stds[j] } }

        // Covariance matrix
        val cov = Array(p) { i -> DoubleArray(p) { j -> standardized.sumOf { it[i] * it[j] } / (n - 1) } }

        // Power iteration for eigenvectors
        val components = mutableListOf<Pair<DoubleArray, Double>>() ; var residual = cov.map { it.copyOf() }
        for (c in 0 until minOf(nComp, p)) { val (vec, val_) = powerIteration(residual); components.add(vec to val_); residual = deflateMatrix(residual, vec, val_) }
        val totalVar = (0 until p).sumOf { cov[it][it] }
        val explained = components.map { it.second / totalVar }
        val cumExplained = explained.runningFold(0.0) { acc, v -> acc + v }.drop(1)

        val loadings = components.mapIndexed { idx, (eigvec, _) -> featureNames.take(p).zip(eigvec.toList()).sortedByDescending { abs(it.second) } }

        val message = buildString {
            append("📊 PCA\n\nFeatures: ${featureNames.take(p).joinToString(", ")}\n")
            append("Observations: $n, Components: ${components.size}\n\n")
            explained.forEachIndexed { i, v -> append("  PC${i+1}: ${"%.1f".format(v*100)}% (cum: ${"%.1f".format(cumExplained[i]*100)}%)\n") }
            append("\nLoadings:\n"); loadings.forEachIndexed { i, l -> append("  PC${i+1}: ${l.joinToString(", ") { "${it.first}(${"%.2f".format(it.second)})" }}\n") }
        }
        return ToolResult.success(name, mapOf("explained_variance" to explained, "cumulative_variance" to cumExplained, "loadings" to loadings, "n_components" to components.size), message)
    }

    private suspend fun performFactorAnalysis(params: Map<String, String>): ToolResult {
        val featureNames = params["features"]?.split(",")?.map { it.trim() } ?: listOf("consistency", "volume", "growth", "mpesa", "savings")
        val nFactors = params["n_components"]?.toIntOrNull() ?: 2
        val matrix = buildFeatureMatrix()
        if (matrix.size < 20) return ToolResult.error(name, "Need ≥20 observations", "INSUFFICIENT_DATA")
        val n = matrix.size; val p = matrix[0].size

        val means = DoubleArray(p) { j -> matrix.sumOf { it[j] } / n }
        val stds = DoubleArray(p) { j -> sqrt(matrix.sumOf { (it[j] - means[j]).pow(2) } / (n - 1)).coerceAtLeast(1e-10) }
        val standardized = matrix.map { row -> DoubleArray(p) { j -> (row[j] - means[j]) / stds[j] } }
        val corr = Array(p) { i -> DoubleArray(p) { j -> standardized.sumOf { it[i] * it[j] } / (n - 1) } }

        val factors = mutableListOf<DoubleArray>(); var residual = corr.map { it.copyOf() }
        for (f in 0 until minOf(nFactors, p)) { val (vec, val_) = powerIteration(residual); factors.add(DoubleArray(p) { i -> vec[i] * sqrt(val_) }); residual = deflateMatrix(residual, vec, val_) }
        val communality = DoubleArray(p) { j -> factors.sumOf { it[j].pow(2) } }

        val interpretations = factors.mapIndexed { idx, loadings ->
            val top = featureNames.take(p).zip(loadings.toList()).sortedByDescending { abs(it.second) }.take(3)
            val interp = when { top.any { it.first == "consistency" } && top.any { it.first == "volume" } -> "Business Activity Level"; top.any { it.first == "growth" } -> "Business Maturity"; else -> "Composite Factor" }
            mapOf("factor" to "Factor ${idx+1}", "top_features" to top.map { "${it.first}(${"%.2f".format(it.second)})" }, "interpretation" to interp)
        }

        val message = buildString {
            append("📊 Factor Analysis\n\n")
            interpretations.forEach { fi -> append("${fi["factor"]}: ${fi["top_features"]} — ${fi["interpretation"]}\n") }
            append("\nCommunalities:\n"); featureNames.take(p).zip(communality.toList()).forEach { (name, c) -> append("  $name: ${"%.1f".format(c*100)}%\n") }
        }
        return ToolResult.success(name, mapOf("factors" to interpretations, "communality" to communality.toList()), message)
    }

    private suspend fun performClusterAnalysis(params: Map<String, String>): ToolResult {
        val nClusters = params["n_clusters"]?.toIntOrNull() ?: 3
        val matrix = buildFeatureMatrix()
        if (matrix.size < 10) return ToolResult.error(name, "Need ≥10 observations", "INSUFFICIENT_DATA")
        val n = matrix.size; val p = matrix[0].size

        val means = DoubleArray(p) { j -> matrix.sumOf { it[j] } / n }
        val stds = DoubleArray(p) { j -> sqrt(matrix.sumOf { (it[j] - means[j]).pow(2) } / (n - 1)).coerceAtLeast(1e-10) }
        val data = matrix.map { row -> DoubleArray(p) { j -> (row[j] - means[j]) / stds[j] } }

        // K-means
        val assignments = IntArray(n)
        val centroids = Array(nClusters) { c -> data[(c * n / nClusters).coerceAtMost(n - 1)].copyOf() }
        repeat(50) {
            var changed = false
            for (i in 0 until n) { var nearest = 0; var minD = Double.MAX_VALUE; for (c in 0 until nClusters) { val d = sqrt(data[i].zip(centroids[c]).sumOf { (a, b) -> (a - b).pow(2) }); if (d < minD) { minD = d; nearest = c } }; if (assignments[i] != nearest) { assignments[i] = nearest; changed = true } }
            if (!changed) return@repeat
            for (c in 0 until nClusters) { val members = data.indices.filter { assignments[it] == c }; if (members.isNotEmpty()) for (j in 0 until p) centroids[c][j] = members.sumOf { data[it][j] } / members.size }
        }

        val featureLabels = listOf("consistency", "volume", "growth", "mpesa", "savings")
        val profiles = (0 until nClusters).map { c ->
            val members = data.indices.filter { assignments[it] == c }
            val profile = featureLabels.take(p).mapIndexed { j, name -> name to "%.2f".format(if (members.isNotEmpty()) members.sumOf { data[it][j] } / members.size * stds[j] + means[j] else 0.0) }.toMap()
            mapOf("cluster" to c, "size" to members.size, "profile" to profile)
        }
        val segNames = profiles.map { p -> val prof = p["profile"] as Map<*,*>; val cons = (prof["consistency"] as? String)?.toDoubleOrNull() ?: 0.0; val vol = (prof["volume"] as? String)?.toDoubleOrNull() ?: 0.0; when { cons > 0.7 && vol > 0.5 -> "High-Volume Consistent"; cons > 0.5 -> "Steady Performer"; vol > 0.5 -> "High-Volume Sporadic"; cons < 0.3 -> "New/Inactive"; else -> "Average" } }

        val message = buildString {
            append("📊 Worker Cluster Analysis (k=$nClusters)\n\n")
            profiles.forEachIndexed { i, prof -> append("Cluster ${i+1}: ${segNames[i]} (${prof["size"]} workers)\n  ${(prof["profile"] as Map<*,*>).entries.joinToString(", ") { "${it.key}=${it.value}" }}\n\n") }
            append("💡 Clusters reveal distinct worker segments for targeted advice.")
        }
        return ToolResult.success(name, mapOf("clusters" to profiles, "segment_names" to segNames, "assignments" to assignments.toList()), message)
    }

    private suspend fun fullMultivariateAnalysis(params: Map<String, String>): ToolResult {
        val scoreResult = calculateScore()
        val pcaRes = performPCA(params); val factorRes = performFactorAnalysis(params); val clusterRes = performClusterAnalysis(params)
        val message = buildString {
            append("📊 Full Multivariate Analysis\n\n")
            append("Score: ${scoreResult.score} (${scoreResult.level})\n\n")
            if (pcaRes.success) append("${pcaRes.message}\n\n")
            if (factorRes.success) append("${factorRes.message}\n\n")
            if (clusterRes.success) append("${clusterRes.message}")
        }
        return ToolResult.success(name, mapOf("score" to scoreResult.score, "pca" to if (pcaRes.success) pcaRes.data else null, "factors" to if (factorRes.success) factorRes.data else null, "clusters" to if (clusterRes.success) clusterRes.data else null), message)
    }

    private suspend fun buildFeatureMatrix(): List<DoubleArray> {
        return try {
            val summaries = dailySummaryDao.getRecentSummaries(90).first()
            if (summaries.size < 14) return emptyList()
            val sorted = summaries.sortedBy { it.date }; val features = mutableListOf<DoubleArray>(); var i = 0
            while (i + 7 <= sorted.size) {
                val week = sorted.subList(i, i + 7)
                val cons = week.count { it.totalSales > 0 } / 7.0; val vol = week.sumOf { it.totalSales }
                val growth = if (i + 14 <= sorted.size) { val prev = sorted.subList(i + 7, i + 14).sumOf { it.totalSales }; if (prev > 0) (vol - prev) / prev else 0.0 } else 0.0
                val mpesa = week.sumOf { it.mpesaSales } / vol.coerceAtLeast(1.0)
                features.add(doubleArrayOf(cons, vol / 1000, growth, mpesa, 0.0)); i += 7
            }
            features
        } catch (e: Exception) { emptyList() }
    }

    private fun powerIteration(matrix: Array<DoubleArray>, maxIter: Int = 200): Pair<DoubleArray, Double> {
        val n = matrix.size; var vec = DoubleArray(n) { kotlin.random.Random.nextDouble() }
        var norm = sqrt(vec.sumOf { it * it }); for (i in 0 until n) vec[i] /= norm; var eigenvalue = 0.0
        repeat(maxIter) {
            val newVec = DoubleArray(n) { i -> matrix[i].zip(vec).sumOf { (a, b) -> a * b } }
            eigenvalue = vec.zip(newVec).sumOf { (a, b) -> a * b }; norm = sqrt(newVec.sumOf { it * it })
            if (norm < 1e-15) return vec to eigenvalue; for (i in 0 until n) newVec[i] /= norm
            val diff = vec.zip(newVec).sumOf { (a, b) -> (a - b).pow(2) }; vec = newVec
            if (diff < 1e-10) return vec to eigenvalue
        }
        return vec to eigenvalue
    }

    private fun deflateMatrix(matrix: Array<DoubleArray>, eigvec: DoubleArray, eigenvalue: Double): Array<DoubleArray> {
        val n = matrix.size; return Array(n) { i -> DoubleArray(n) { j -> matrix[i][j] - eigenvalue * eigvec[i] * eigvec[j] } }
    }
}
