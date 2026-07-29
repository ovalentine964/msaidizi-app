package com.msaidizi.agent.tools

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*
import kotlin.random.Random

/**
 * RobustEstimator — Non-parametric methods tool (STA 444).
 *
 * Robust statistical estimates for messy real-world data:
 *   - Median and IQR (robust to outliers)
 *   - Bootstrap confidence intervals (no distributional assumptions)
 *   - Mann-Whitney U test (non-parametric t-test)
 *   - Spearman rank correlation (monotonic relationships)
 *   - Trimmed/winsorized statistics
 *   - MAD-based outlier detection
 */
@Singleton
class RobustEstimator @Inject constructor() : Tool {

    override val name = "robust_estimator"
    override val description = "Robust statistical estimates for messy data: median, IQR, bootstrap CI, Mann-Whitney U, Spearman correlation"

    override val argsSchema = argSchema {
        enum("method", "Robust estimation method",
            listOf("descriptive", "bootstrap_ci", "mann_whitney", "spearman", "trimmed_mean", "winsorized_stats", "robust_zscore", "mad_outliers"),
            required = true)
        string("data", "Comma-separated numeric values", required = false)
        string("data2", "Second sample (for Mann-Whitney, Spearman)", required = false)
        string("x_values", "X values (for Spearman)", required = false)
        string("y_values", "Y values (for Spearman)", required = false)
        number("trim_percent", "Trim percentage (default 0.1)", required = false)
        number("confidence", "Confidence level (default 0.95)", required = false)
        number("n_bootstrap", "Bootstrap resamples (default 5000)", required = false)
        number("threshold", "MAD threshold (default 3.0)", required = false)
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        return try {
            when (params["method"]) {
                "descriptive" -> robustDescriptive(params)
                "bootstrap_ci" -> bootstrapCI(params)
                "mann_whitney" -> mannWhitneyU(params)
                "spearman" -> spearmanCorrelation(params)
                "trimmed_mean" -> trimmedMean(params)
                "winsorized_stats" -> winsorizedStats(params)
                "robust_zscore" -> robustZScore(params)
                "mad_outliers" -> madOutliers(params)
                else -> ToolResult.error(name, "Unknown method", "INVALID_METHOD")
            }
        } catch (e: Exception) { Timber.e(e, "Robust estimation failed"); ToolResult.error(name, "Failed: ${e.message}", "ESTIMATION_ERROR") }
    }

    private fun robustDescriptive(params: Map<String, String>): ToolResult {
        val data = parseDoubleList(params["data"]) ?: return ToolResult.error(name, "data required", "MISSING_DATA")
        val sorted = data.sorted(); val n = data.size
        val median = median(sorted); val q1 = pct(sorted, 25.0); val q3 = pct(sorted, 75.0); val iqr = q3 - q1
        val mad = mad(data); val trimmed = trimmedMeanCalc(data, 0.1)
        val mean = data.average(); val stdDev = sqrt(data.map { (it - mean).pow(2) }.sum() / (n - 1))
        val diff = abs(mean - median); val ratio = if (median != 0.0) diff / abs(median) else 0.0

        val message = buildString {
            append("📊 Robust Descriptive Statistics\n\n")
            append("─ Robust ─\n  Median: ${"%.2f".format(median)}, IQR: ${"%.2f".format(iqr)}, MAD: ${"%.2f".format(mad)}, Trimmed mean: ${"%.2f".format(trimmed)}\n\n")
            append("─ Classic ─\n  Mean: ${"%.2f".format(mean)}, SD: ${"%.2f".format(stdDev)}\n\n")
            append("─ Outlier Impact ─\n  Mean-Median diff: ${"%.0f".format(ratio * 100)}%\n")
            when { ratio > 0.2 -> append("  ⚠️ Heavy skew — USE MEDIAN!"); ratio > 0.05 -> append("  🟡 Moderate skew — median more reliable"); else -> append("  ✅ Roughly symmetric") }
        }
        return ToolResult.success(name, mapOf("median" to median, "iqr" to iqr, "mad" to mad, "trimmed_mean" to trimmed, "mean" to mean, "std_dev" to stdDev), message)
    }

    private fun bootstrapCI(params: Map<String, String>): ToolResult {
        val data = parseDoubleList(params["data"]) ?: return ToolResult.error(name, "data required", "MISSING_DATA")
        val confidence = params["confidence"]?.toDoubleOrNull() ?: 0.95; val B = params["n_bootstrap"]?.toIntOrNull() ?: 5000
        val n = data.size; if (n < 5) return ToolResult.error(name, "Need ≥5 observations", "INSUFFICIENT_DATA")
        val alpha = 1 - confidence; val rng = Random(42)

        val bootMedians = DoubleArray(B) { val r = List(n) { data[rng.nextInt(n)] }; median(r.sorted()) }.also { it.sort() }
        val bootMeans = DoubleArray(B) { List(n) { data[rng.nextInt(n)] }.average() }.also { it.sort() }
        val actualMedian = median(data.sorted()); val actualMean = data.average()
        val medCI = Pair(bootMedians[(alpha/2*B).toInt()], bootMedians[((1-alpha/2)*B).toInt()])
        val meanCI = Pair(bootMeans[(alpha/2*B).toInt()], bootMeans[((1-alpha/2)*B).toInt()])

        val message = buildString {
            append("📊 Bootstrap ${"%.0f".format(confidence*100)}% Confidence Intervals\n\n")
            append("Median: ${"%.2f".format(actualMedian)} CI [${"%.2f".format(medCI.first)}, ${"%.2f".format(medCI.second)}]\n")
            append("Mean: ${"%.2f".format(actualMean)} CI [${"%.2f".format(meanCI.first)}, ${"%.2f".format(meanCI.second)}]\n")
            append("\n💡 Distribution-free — no normality assumption needed.")
        }
        return ToolResult.success(name, mapOf("median" to actualMedian, "mean" to actualMean, "median_ci" to mapOf("lower" to medCI.first, "upper" to medCI.second), "mean_ci" to mapOf("lower" to meanCI.first, "upper" to meanCI.second)), message)
    }

    private fun mannWhitneyU(params: Map<String, String>): ToolResult {
        val s1 = parseDoubleList(params["data"]) ?: return ToolResult.error(name, "data required", "MISSING_DATA")
        val s2 = parseDoubleList(params["data2"]) ?: return ToolResult.error(name, "data2 required", "MISSING_DATA")
        val n1 = s1.size; val n2 = s2.size
        if (n1 < 3 || n2 < 3) return ToolResult.error(name, "Need ≥3 per group", "INSUFFICIENT_DATA")

        var u1 = 0.0; for (x in s1) for (y in s2) u1 += when { x > y -> 1.0; x == y -> 0.5; else -> 0.0 }
        val u2 = n1.toDouble() * n2 - u1; val u = min(u1, u2)
        val eu = n1.toDouble() * n2 / 2
        val su = sqrt(n1.toDouble() * n2 * (n1 + n2 + 1) / 12.0)
        val z = if (su > 0) (u - eu) / su else 0.0
        val pValue = 2 * (1 - normalCDF(abs(z)))
        val r = 2 * u1 / (n1 * n2) - 1
        val median1 = median(s1.sorted()); val median2 = median(s2.sorted())
        val significant = pValue < 0.05

        val message = buildString {
            append("📊 Mann-Whitney U Test\n\n")
            append("Group 1: n=$n1, median=${"%.2f".format(median1)}\n")
            append("Group 2: n=$n2, median=${"%.2f".format(median2)}\n\n")
            append("U=${"%.1f".format(u)}, z=${"%.4f".format(z)}, p=${"%.6f".format(pValue)}\n")
            append("Effect size r: ${"%.3f".format(r)}\n\n")
            if (significant) append("✅ Significant difference (non-parametric)") else append("❌ No significant difference")
        }
        return ToolResult.success(name, mapOf("u_statistic" to u, "z_score" to z, "p_value" to pValue, "significant" to significant, "effect_size_r" to r, "median1" to median1, "median2" to median2), message)
    }

    private fun spearmanCorrelation(params: Map<String, String>): ToolResult {
        val xVals = parseDoubleList(params["x_values"]) ?: parseDoubleList(params["data"]) ?: return ToolResult.error(name, "x_values required", "MISSING_DATA")
        val yVals = parseDoubleList(params["y_values"]) ?: parseDoubleList(params["data2"]) ?: return ToolResult.error(name, "y_values required", "MISSING_DATA")
        if (xVals.size != yVals.size) return ToolResult.error(name, "X and Y must have same length", "MISMATCH")
        val n = xVals.size; if (n < 5) return ToolResult.error(name, "Need ≥5 pairs", "INSUFFICIENT_DATA")

        val xRanks = avgRanks(xVals); val yRanks = avgRanks(yVals)
        val rho = pearsonCorr(xRanks, yRanks)
        val rng = Random(42); var count = 0; repeat(5000) { val py = yRanks.toMutableList().also { it.shuffle(rng) }; if (abs(pearsonCorr(xRanks, py)) >= abs(rho)) count++ }
        val pValue = count.toDouble() / 5000
        val strength = when { abs(rho) > 0.7 -> "strong"; abs(rho) > 0.4 -> "moderate"; abs(rho) > 0.2 -> "weak"; else -> "negligible" }

        val message = buildString {
            append("📊 Spearman Rank Correlation\n\n")
            append("ρ=${"%.4f".format(rho)}, p=${"%.4f".format(pValue)}\n")
            append("Strength: $strength ${if (rho > 0) "positive" else "negative"}\n")
            if (pValue < 0.05) append("✅ Significant monotonic relationship") else append("❌ No significant relationship")
        }
        return ToolResult.success(name, mapOf("spearman_rho" to rho, "p_value" to pValue, "significant" to (pValue < 0.05), "strength" to strength), message)
    }

    private fun trimmedMean(params: Map<String, String>): ToolResult {
        val data = parseDoubleList(params["data"]) ?: return ToolResult.error(name, "data required", "MISSING_DATA")
        val trimPct = params["trim_percent"]?.toDoubleOrNull() ?: 0.1
        val trimmed = trimmedMeanCalc(data, trimPct); val mean = data.average(); val median = median(data.sorted())
        val trimCount = (data.size * trimPct).toInt()

        val message = buildString {
            append("📊 Trimmed Mean (${ "%.0f".format(trimPct*100)}%)\n\n")
            append("Trimmed: ${"%.2f".format(trimmed)}, Mean: ${"%.2f".format(mean)}, Median: ${"%.2f".format(median)}\n")
            append("Trimmed $trimCount from each end\n")
        }
        return ToolResult.success(name, mapOf("trimmed_mean" to trimmed, "mean" to mean, "median" to median), message)
    }

    private fun winsorizedStats(params: Map<String, String>): ToolResult {
        val data = parseDoubleList(params["data"]) ?: return ToolResult.error(name, "data required", "MISSING_DATA")
        val trimPct = params["trim_percent"]?.toDoubleOrNull() ?: 0.05
        val sorted = data.sorted(); val lo = pct(sorted, trimPct * 100); val hi = pct(sorted, (1 - trimPct) * 100)
        val wins = data.map { it.coerceIn(lo, hi) }; val wMean = wins.average()
        val origMean = data.average(); val pctDiff = if (origMean != 0.0) abs(wMean - origMean) / abs(origMean) * 100 else 0.0

        val message = buildString {
            append("📊 Winsorized Statistics\n\n")
            append("Bounds: [${"%.2f".format(lo)}, ${"%.2f".format(hi)}]\n")
            append("Winsorized mean: ${"%.2f".format(wMean)} vs Original: ${"%.2f".format(origMean)}\n")
            append("Shift: ${"%.1f".format(pctDiff)}%\n")
            if (pctDiff > 10) append("⚠️ Significant shift — outliers influence the mean") else append("✅ Minimal shift — data is clean")
        }
        return ToolResult.success(name, mapOf("winsorized_mean" to wMean, "original_mean" to origMean, "lower_bound" to lo, "upper_bound" to hi), message)
    }

    private fun robustZScore(params: Map<String, String>): ToolResult {
        val data = parseDoubleList(params["data"]) ?: return ToolResult.error(name, "data required", "MISSING_DATA")
        val median = median(data.sorted()); val mad = mad(data); val scale = 1.4826 * mad
        val zScores = data.map { if (scale > 0) (it - median) / scale else 0.0 }
        val outliers = zScores.mapIndexedNotNull { i, z -> if (abs(z) > 3.0) i else null }

        val message = buildString {
            append("📊 Robust Z-Scores (MAD-based)\n\n")
            append("Median: ${"%.2f".format(median)}, MAD: ${"%.2f".format(mad)}\n\n")
            if (outliers.isEmpty()) append("✅ No outliers (|z| > 3)") else { append("⚠️ ${outliers.size} outlier(s):\n"); outliers.take(10).forEach { append("  [${it}]: ${"%.2f".format(data[it])}, z=${"%.2f".format(zScores[it])}\n") } }
        }
        return ToolResult.success(name, mapOf("median" to median, "mad" to mad, "outlier_count" to outliers.size, "outlier_indices" to outliers), message)
    }

    private fun madOutliers(params: Map<String, String>): ToolResult {
        val data = parseDoubleList(params["data"]) ?: return ToolResult.error(name, "data required", "MISSING_DATA")
        val threshold = params["threshold"]?.toDoubleOrNull() ?: 3.0
        val median = median(data.sorted()); val mad = mad(data); val scale = 1.4826 * mad
        val outliers = data.mapIndexedNotNull { i, v -> val z = if (scale > 0) abs(v - median) / scale else 0.0; if (z > threshold) mapOf("index" to i, "value" to v, "z" to z) else null }

        val message = buildString {
            append("📊 MAD Outlier Detection (threshold=${"%.1f".format(threshold)})\n\n")
            if (outliers.isEmpty()) append("✅ No outliers detected") else { append("⚠️ ${outliers.size} outlier(s)\n"); outliers.take(10).forEach { append("  [${it["index"]}]: ${"%.2f".format(it["value"] as Double)} (z=${"%.2f".format(it["z"] as Double)})\n") } }
        }
        return ToolResult.success(name, mapOf("outliers" to outliers, "outlier_count" to outliers.size, "median" to median, "mad" to mad), message)
    }

    // ── Helpers ──
    private fun median(sorted: List<Double>): Double { val n = sorted.size; return if (n % 2 == 0) (sorted[n/2-1] + sorted[n/2]) / 2 else sorted[n/2] }
    private fun pct(sorted: List<Double>, p: Double): Double { val idx = p / 100 * (sorted.size - 1); val lo = idx.toInt(); val hi = (lo + 1).coerceAtMost(sorted.size - 1); return sorted[lo] * (1 - (idx - lo)) + sorted[hi] * (idx - lo) }
    private fun mad(data: List<Double>): Double { val m = median(data.sorted()); return median(data.map { abs(it - m) }.sorted()) }
    private fun trimmedMeanCalc(data: List<Double>, pct: Double): Double { val sorted = data.sorted(); val c = (sorted.size * pct).toInt(); return sorted.subList(c, sorted.size - c).average() }
    private fun avgRanks(vals: List<Double>): List<Double> {
        val indexed = vals.mapIndexed { i, v -> i to v }.sortedBy { it.second }; val ranks = DoubleArray(vals.size)
        var i = 0; while (i < indexed.size) { var j = i; while (j < indexed.size && indexed[j].second == indexed[i].second) j++; val avg = (i + j - 1).toDouble() / 2 + 1; for (k in i until j) ranks[indexed[k].first] = avg; i = j }; return ranks.toList()
    }
    private fun pearsonCorr(x: List<Double>, y: List<Double>): Double { val n = x.size; val mx = x.average(); val my = y.average(); var sxy = 0.0; var sx2 = 0.0; var sy2 = 0.0; for (i in 0 until n) { val dx = x[i]-mx; val dy = y[i]-my; sxy += dx*dy; sx2 += dx*dx; sy2 += dy*dy }; return if (sx2 > 0 && sy2 > 0) sxy / sqrt(sx2 * sy2) else 0.0 }
    private fun normalCDF(x: Double): Double { val a1=0.254829592;val a2=-0.284496736;val a3=1.421413741;val a4=-1.453152027;val a5=1.061405429;val p=0.3275911;val sign=if(x<0)-1.0 else 1.0;val absX=abs(x)/sqrt(2.0);val t=1.0/(1.0+p*absX);val y=1.0-(((((a5*t+a4)*t)+a3)*t+a2)*t+a1)*t*exp(-absX*absX);return 0.5*(1.0+sign*y) }
    private fun parseDoubleList(s: String?): List<Double>? { if (s.isNullOrBlank()) return null; return try { s.split(",").map { it.trim().toDouble() } } catch (e: Exception) { null } }
}
