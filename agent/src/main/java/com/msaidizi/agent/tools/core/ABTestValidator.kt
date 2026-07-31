package com.msaidizi.agent.tools.core

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

/**
 * ABTestValidator — Hypothesis testing tool (STA 342).
 *
 * Validates whether changes (pricing, advice, strategies) actually improve outcomes.
 * Implements classical hypothesis testing from first principles:
 *   - Two-sample t-test for comparing means
 *   - Chi-square test for comparing proportions
 *   - P-value calculation
 *   - Confidence interval computation
 *   - Sample size determination (power analysis)
 *
 * All computations are pure Kotlin — no external dependencies.
 *
 * Mathematical basis:
 *   t = (x̄₁ - x̄₂) / √(s₁²/n₁ + s₂²/n₂)   (Welch's t-test)
 *   χ² = Σ (O - E)² / E                        (Chi-square)
 *   n = (z_{α/2} + z_β)² × 2σ² / δ²           (Sample size)
 */
@Singleton
class ABTestValidator @Inject constructor() : Tool {

    override val name = "ab_test_validator"
    override val description = "Validate whether changes improve outcomes using hypothesis testing (t-test, chi-square, confidence intervals, sample size)"

    override val argsSchema = argSchema {
        enum("test", "Type of statistical test",
            listOf("t_test", "chi_square", "confidence_interval", "sample_size", "proportion_test"),
            required = true)
        string("group1", "Comma-separated numeric values for control group", required = false)
        string("group2", "Comma-separated numeric values for treatment group", required = false)
        number("mean1", "Mean of group 1 (for sample_size calculation)", required = false)
        number("mean2", "Mean of group 2 (for sample_size calculation)", required = false)
        number("std1", "Standard deviation of group 1", required = false)
        number("std2", "Standard deviation of group 2", required = false)
        number("successes1", "Successes in group 1 (for chi_square/proportion_test)", required = false)
        number("total1", "Total in group 1 (for chi_square/proportion_test)", required = false)
        number("successes2", "Successes in group 2 (for chi_square/proportion_test)", required = false)
        number("total2", "Total in group 2 (for chi_square/proportion_test)", required = false)
        number("confidence", "Confidence level (default 0.95)", required = false)
        number("effect_size", "Minimum effect size to detect (Cohen's d)", required = false)
        number("power", "Statistical power (default 0.80)", required = false)
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        return try {
            val test = params["test"] ?: return ToolResult.error(name, "Test type required", "MISSING_TEST")
            val confidence = params["confidence"]?.toDoubleOrNull() ?: 0.95

            when (test.lowercase()) {
                "t_test" -> runTTest(params, confidence)
                "chi_square" -> runChiSquare(params, confidence)
                "confidence_interval" -> runConfidenceInterval(params, confidence)
                "sample_size" -> calculateSampleSize(params)
                "proportion_test" -> runProportionTest(params, confidence)
                else -> ToolResult.error(name, "Unknown test: $test", "INVALID_TEST")
            }
        } catch (e: Exception) {
            Timber.e(e, "AB test validation failed")
            ToolResult.error(name, "Test failed: ${e.message}", "CALCULATION_ERROR")
        }
    }

    private fun runTTest(params: Map<String, String>, confidence: Double): ToolResult {
        val group1 = parseDoubleList(params["group1"])
            ?: return ToolResult.error(name, "group1 data required (comma-separated numbers)", "MISSING_DATA")
        val group2 = parseDoubleList(params["group2"])
            ?: return ToolResult.error(name, "group2 data required (comma-separated numbers)", "MISSING_DATA")

        if (group1.size < 2 || group2.size < 2) {
            return ToolResult.error(name, "Need at least 2 observations per group", "INSUFFICIENT_DATA")
        }

        val n1 = group1.size; val n2 = group2.size
        val mean1 = group1.average(); val mean2 = group2.average()
        val var1 = group1.map { (it - mean1).pow(2) }.sum() / (n1 - 1)
        val var2 = group2.map { (it - mean2).pow(2) }.sum() / (n2 - 1)

        val se = sqrt(var1 / n1 + var2 / n2)
        val tStat = if (se > 0) (mean1 - mean2) / se else 0.0
        val df = (var1 / n1 + var2 / n2).pow(2) /
            ((var1 / n1).pow(2) / (n1 - 1) + (var2 / n2).pow(2) / (n2 - 1))
        val pValue = twoTailedPValueFromT(tStat, df)

        val pooledSD = sqrt(((n1 - 1) * var1 + (n2 - 1) * var2) / (n1 + n2 - 2))
        val cohensD = if (pooledSD > 0) (mean1 - mean2) / pooledSD else 0.0
        val tCrit = tInverse(1 - (1 - confidence) / 2, df)
        val diffCI = tCrit * se
        val diff = mean1 - mean2
        val significant = pValue < (1 - confidence)
        val effectLabel = interpretEffectSize(abs(cohensD))

        val message = buildString {
            append("📊 T-Test Results (Welch's):\n\n")
            append("Group 1: n=$n1, mean=${"%.2f".format(mean1)}, SD=${"%.2f".format(sqrt(var1))}\n")
            append("Group 2: n=$n2, mean=${"%.2f".format(mean2)}, SD=${"%.2f".format(sqrt(var2))}\n\n")
            append("t-statistic: ${"%.4f".format(tStat)}\n")
            append("Degrees of freedom: ${"%.1f".format(df)}\n")
            append("p-value: ${"%.6f".format(pValue)}\n")
            append("Significant at ${(confidence * 100).toInt()}% level: ${if (significant) "✅ YES" else "❌ NO"}\n\n")
            append("Effect size (Cohen's d): ${"%.3f".format(cohensD)} ($effectLabel)\n")
            append("Mean difference: ${"%.2f".format(diff)}\n")
            append("${(confidence * 100).toInt()}% CI: [${"%.2f".format(diff - diffCI)}, ${"%.2f".format(diff + diffCI)}]\n\n")
            if (significant) append("💡 The difference IS statistically significant.")
            else append("💡 The difference is NOT statistically significant.")
        }

        return ToolResult.success(name, mapOf(
            "test" to "welch_t_test", "t_statistic" to tStat, "p_value" to pValue,
            "degrees_of_freedom" to df, "significant" to significant,
            "cohens_d" to cohensD, "effect_size_label" to effectLabel,
            "mean_difference" to diff, "ci_lower" to (diff - diffCI), "ci_upper" to (diff + diffCI),
            "n1" to n1, "n2" to n2, "mean1" to mean1, "mean2" to mean2
        ), message)
    }

    private fun runChiSquare(params: Map<String, String>, confidence: Double): ToolResult {
        val successes1 = params["successes1"]?.toDoubleOrNull() ?: return ToolResult.error(name, "successes1 required", "MISSING_DATA")
        val total1 = params["total1"]?.toDoubleOrNull() ?: return ToolResult.error(name, "total1 required", "MISSING_DATA")
        val successes2 = params["successes2"]?.toDoubleOrNull() ?: return ToolResult.error(name, "successes2 required", "MISSING_DATA")
        val total2 = params["total2"]?.toDoubleOrNull() ?: return ToolResult.error(name, "total2 required", "MISSING_DATA")

        val failures1 = total1 - successes1; val failures2 = total2 - successes2
        val N = total1 + total2
        val pPooled = (successes1 + successes2) / N
        val eSuccess1 = total1 * pPooled; val eFail1 = total1 * (1 - pPooled)
        val eSuccess2 = total2 * pPooled; val eFail2 = total2 * (1 - pPooled)
        val chiSq = (successes1 - eSuccess1).pow(2) / eSuccess1 + (failures1 - eFail1).pow(2) / eFail1 +
            (successes2 - eSuccess2).pow(2) / eSuccess2 + (failures2 - eFail2).pow(2) / eFail2
        val pValue = chiSquarePValue(chiSq, 1)
        val p1 = successes1 / total1; val p2 = successes2 / total2; val diff = p2 - p1
        val riskRatio = if (p1 > 0) p2 / p1 else Double.POSITIVE_INFINITY
        val significant = pValue < (1 - confidence)

        val message = buildString {
            append("📊 Chi-Square Test Results:\n\n")
            append("Group 1: ${successes1.toInt()}/${total1.toInt()} = ${"%.1f".format(p1 * 100)}%\n")
            append("Group 2: ${successes2.toInt()}/${total2.toInt()} = ${"%.1f".format(p2 * 100)}%\n\n")
            append("χ² statistic: ${"%.4f".format(chiSq)}\n")
            append("p-value: ${"%.6f".format(pValue)}\n")
            append("Significant: ${if (significant) "✅ YES" else "❌ NO"}\n\n")
            append("Difference: ${"%.1f".format(diff * 100)} pp\n")
            append("Risk ratio: ${"%.3f".format(riskRatio)}\n")
        }

        return ToolResult.success(name, mapOf(
            "chi_square_statistic" to chiSq, "p_value" to pValue, "significant" to significant,
            "proportion1" to p1, "proportion2" to p2, "difference" to diff, "risk_ratio" to riskRatio
        ), message)
    }

    private fun runConfidenceInterval(params: Map<String, String>, confidence: Double): ToolResult {
        val data = parseDoubleList(params["group1"]) ?: return ToolResult.error(name, "Data required", "MISSING_DATA")
        if (data.size < 2) return ToolResult.error(name, "Need at least 2 observations", "INSUFFICIENT_DATA")
        val n = data.size; val mean = data.average()
        val variance = data.map { (it - mean).pow(2) }.sum() / (n - 1)
        val se = sqrt(variance / n); val df = (n - 1).toDouble()
        val tCrit = tInverse(1 - (1 - confidence) / 2, df); val margin = tCrit * se

        val message = buildString {
            append("📊 ${"%.0f".format(confidence * 100)}% Confidence Interval:\n\n")
            append("n=$n, Mean=${"%.2f".format(mean)}, SE=${"%.2f".format(se)}\n")
            append("CI: [${"%.2f".format(mean - margin)}, ${"%.2f".format(mean + margin)}]\n")
            append("Margin of error: ±${"%.2f".format(margin)}\n")
        }
        return ToolResult.success(name, mapOf("mean" to mean, "ci_lower" to (mean - margin), "ci_upper" to (mean + margin), "margin_of_error" to margin, "n" to n), message)
    }

    private fun calculateSampleSize(params: Map<String, String>): ToolResult {
        val effectSize = params["effect_size"]?.toDoubleOrNull() ?: return ToolResult.error(name, "effect_size required", "MISSING_EFFECT")
        val power = params["power"]?.toDoubleOrNull() ?: 0.80
        val confidence = params["confidence"]?.toDoubleOrNull() ?: 0.95
        val alpha = 1 - confidence
        val zAlpha = normalInverse(1 - alpha / 2); val zBeta = normalInverse(power)
        val n = ceil((zAlpha + zBeta).pow(2) * 2 / effectSize.pow(2)).toInt()
        val effectLabel = interpretEffectSize(abs(effectSize))

        val message = buildString {
            append("📊 Sample Size Calculation:\n\n")
            append("Effect size (Cohen's d): ${"%.2f".format(effectSize)} ($effectLabel)\n")
            append("α=${"%.3f".format(alpha)}, Power=${"%.2f".format(power)}\n\n")
            append("Required per group: $n\nTotal: ${n * 2}\n")
        }
        return ToolResult.success(name, mapOf("n_per_group" to n, "total_n" to (n * 2), "effect_size" to effectSize, "alpha" to alpha, "power" to power), message)
    }

    private fun runProportionTest(params: Map<String, String>, confidence: Double): ToolResult {
        val successes1 = params["successes1"]?.toDoubleOrNull() ?: return ToolResult.error(name, "successes1 required", "MISSING_DATA")
        val total1 = params["total1"]?.toDoubleOrNull() ?: return ToolResult.error(name, "total1 required", "MISSING_DATA")
        val successes2 = params["successes2"]?.toDoubleOrNull() ?: return ToolResult.error(name, "successes2 required", "MISSING_DATA")
        val total2 = params["total2"]?.toDoubleOrNull() ?: return ToolResult.error(name, "total2 required", "MISSING_DATA")
        val p1 = successes1 / total1; val p2 = successes2 / total2
        val pPooled = (successes1 + successes2) / (total1 + total2)
        val se = sqrt(pPooled * (1 - pPooled) * (1 / total1 + 1 / total2))
        val zStat = if (se > 0) (p1 - p2) / se else 0.0
        val pValue = 2 * (1 - normalCDF(abs(zStat)))
        val alpha = 1 - confidence; val zCrit = normalInverse(1 - alpha / 2)
        val seDiff = sqrt(p1 * (1 - p1) / total1 + p2 * (1 - p2) / total2)
        val diff = p1 - p2; val margin = zCrit * seDiff
        val significant = pValue < alpha

        val message = buildString {
            append("📊 Two-Proportion Z-Test:\n\n")
            append("P1=${"%.1f".format(p1 * 100)}%, P2=${"%.1f".format(p2 * 100)}%\n")
            append("z=${"%.4f".format(zStat)}, p=${"%.6f".format(pValue)}\n")
            append("Significant: ${if (significant) "✅ YES" else "❌ NO"}\n")
            append("CI: [${"%.1f".format((diff - margin) * 100)}%, ${"%.1f".format((diff + margin) * 100)}%]\n")
        }
        return ToolResult.success(name, mapOf("z_statistic" to zStat, "p_value" to pValue, "significant" to significant, "proportion1" to p1, "proportion2" to p2, "difference" to diff), message)
    }

    // ── Statistical Distribution Functions ──

    private fun normalCDF(x: Double): Double {
        val a1 = 0.254829592; val a2 = -0.284496736; val a3 = 1.421413741
        val a4 = -1.453152027; val a5 = 1.061405429; val p = 0.3275911
        val sign = if (x < 0) -1.0 else 1.0; val absX = abs(x) / sqrt(2.0)
        val t = 1.0 / (1.0 + p * absX)
        val y = 1.0 - (((((a5 * t + a4) * t) + a3) * t + a2) * t + a1) * t * exp(-absX * absX)
        return 0.5 * (1.0 + sign * y)
    }

    private fun normalInverse(p: Double): Double {
        val a = doubleArrayOf(-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02, 1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00)
        val b = doubleArrayOf(-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02, 6.680131188771972e+01, -1.328068155288572e+01)
        val c = doubleArrayOf(-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00, -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00)
        val d = doubleArrayOf(7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00)
        val pLow = 0.02425; val pHigh = 1 - pLow
        return when {
            p < pLow -> { val q = sqrt(-2 * ln(p)); ((((c[0]*q+c[1])*q+c[2])*q+c[3])*q+c[4])*q+c[5]/(((d[0]*q+d[1])*q+d[2])*q+d[3])*q+1 }
            p <= pHigh -> { val q = p - 0.5; val r = q * q; ((((a[0]*r+a[1])*r+a[2])*r+a[3])*r+a[4])*r+a[5]*q/((((b[0]*r+b[1])*r+b[2])*r+b[3])*r+b[4])*r+1 }
            else -> { val q = sqrt(-2 * ln(1 - p)); -(((((c[0]*q+c[1])*q+c[2])*q+c[3])*q+c[4])*q+c[5]/(((d[0]*q+d[1])*q+d[2])*q+d[3])*q+1) }
        }
    }

    private fun twoTailedPValueFromT(t: Double, df: Double): Double {
        val x = df / (df + t * t)
        return regularizedIncompleteBeta(df / 2.0, 0.5, x).coerceIn(0.0, 1.0)
    }

    private fun regularizedIncompleteBeta(a: Double, b: Double, x: Double): Double {
        if (x < 0 || x > 1) return 0.0; if (x == 0.0 || x == 1.0) return x
        val lnBeta = lnGamma(a) + lnGamma(b) - lnGamma(a + b)
        val front = exp(ln(x) * a + ln(1 - x) * b - lnBeta) / a
        var f = 1.0; var c = 1.0
        var d = 1.0 - (a + b) * x / (a + 1)
        if (abs(d) < 1e-30) d = 1e-30; d = 1.0 / d; f = d
        for (i in 1..200) {
            val m = i.toDouble()
            val num = m * (b - m) * x / ((a + 2 * m - 1) * (a + 2 * m))
            d = 1 + num * d; if (abs(d) < 1e-30) d = 1e-30; d = 1.0 / d
            c = 1 + num / c; if (abs(c) < 1e-30) c = 1e-30; f *= d * c
            val num2 = -(a + m) * (a + b + m) * x / ((a + 2 * m) * (a + 2 * m + 1))
            d = 1 + num2 * d; if (abs(d) < 1e-30) d = 1e-30; d = 1.0 / d
            c = 1 + num2 / c; if (abs(c) < 1e-30) c = 1e-30
            val delta = d * c; f *= delta
            if (abs(delta - 1) < 1e-10) break
        }
        return (1 - front * f).coerceIn(0.0, 1.0)
    }

    private fun tInverse(p: Double, df: Double): Double {
        if (df > 300) return normalInverse(p)
        val z = normalInverse(p)
        val g1 = (z * z * z + z) / (4 * df)
        val g2 = (5 * z.pow(5) + 16 * z.pow(3) + 3 * z) / (96 * df.pow(2))
        val g3 = (3 * z.pow(7) + 19 * z.pow(5) + 17 * z.pow(3) - 15 * z) / (384 * df.pow(3))
        return z + g1 + g2 + g3
    }

    private fun chiSquarePValue(chiSq: Double, df: Int): Double {
        if (df == 1) return 2 * (1 - normalCDF(sqrt(chiSq)))
        return 1 - regularizedIncompleteGamma(df / 2.0, chiSq / 2.0)
    }

    private fun regularizedIncompleteGamma(s: Double, x: Double): Double {
        if (x < 0 || x == 0.0) return 0.0
        if (x < s + 1) {
            var sum = 1.0 / s; var term = 1.0 / s
            for (n in 1..300) { term *= x / (s + n); sum += term; if (abs(term) < abs(sum) * 1e-15) break }
            return sum * exp(-x + s * ln(x) - lnGamma(s))
        }
        var f = 1.0 + x - s; if (abs(f) < 1e-30) f = 1e-30
        var c = 1e30; var d = 1.0 / f
        for (n in 1..300) {
            val a = n.toDouble() * (s - n); val b = (2 * n + 1).toDouble() + x - s
            f = b + a / f; if (abs(f) < 1e-30) f = 1e-30
            d = b + a * d; if (abs(d) < 1e-30) d = 1e-30; d = 1.0 / d
            val delta = f * d; c *= delta; if (abs(delta - 1) < 1e-15) break
        }
        return 1 - exp(-x + s * ln(x) - lnGamma(s)) * d * f
    }

    private fun lnGamma(x: Double): Double {
        val c = doubleArrayOf(76.18009172947146, -86.50532032941677, 24.01409824083091, -1.231739572450155, 0.1208650973866179e-2, -0.5395239384953e-5)
        var y = x; var tmp = x + 5.5; tmp -= (x + 0.5) * ln(tmp)
        var sum = 1.000000000190015; for (i in 0..5) { sum += c[i] / ++y }
        return -tmp + ln(2.5066282746310005 * sum / x)
    }

    private fun interpretEffectSize(d: Double): String = when { d < 0.2 -> "negligible"; d < 0.5 -> "small"; d < 0.8 -> "medium"; else -> "large" }
    private fun parseDoubleList(s: String?): List<Double>? {
        if (s.isNullOrBlank()) return null
        return try { s.split(",").map { it.trim().toDouble() } } catch (e: NumberFormatException) { null }
    }
}
