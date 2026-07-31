package com.msaidizi.agent.tools.core

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

/**
 * DataQualityMonitor — Statistical quality control tool (STA 346).
 *
 * Monitors data quality using SPC (Statistical Process Control):
 *   - X-bar chart: monitors process mean
 *   - R-chart: monitors process variability
 *   - Process capability indices (Cp, Cpk)
 *   - Acceptance sampling for data batches
 *   - Western Electric rules for out-of-control detection
 */
@Singleton
class DataQualityMonitor @Inject constructor() : Tool {

    override val name = "data_quality_monitor"
    override val description = "Monitor data quality with control charts (X-bar, R), process capability (Cp, Cpk), and acceptance sampling"

    override val argsSchema = argSchema {
        enum("action", "Quality control action",
            listOf("xbar_chart", "r_chart", "process_capability", "acceptance_sampling", "full_report", "we_rules"),
            required = true)
        string("data", "Comma-separated numeric values to monitor", required = false)
        string("subgroup_data", "Semicolon-separated subgroups, each comma-separated", required = false)
        number("lsl", "Lower specification limit", required = false)
        number("usl", "Upper specification limit", required = false)
        number("lot_size", "Total lot size (for acceptance sampling)", required = false)
        number("sample_size", "Sample size", required = false)
        number("aql", "Acceptable quality level (default 0.01)", required = false)
        number("defect_rate", "Expected defect rate", required = false)
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        return try {
            when (params["action"]) {
                "xbar_chart" -> xbarChart(params)
                "r_chart" -> rChart(params)
                "process_capability" -> processCapability(params)
                "acceptance_sampling" -> acceptanceSampling(params)
                "full_report" -> fullReport(params)
                "we_rules" -> westernElectricRules(params)
                else -> ToolResult.error(name, "Unknown action", "INVALID_ACTION")
            }
        } catch (e: Exception) { Timber.e(e, "QC failed"); ToolResult.error(name, "Failed: ${e.message}", "QC_ERROR") }
    }

    private fun xbarChart(params: Map<String, String>): ToolResult {
        val subgroups = parseSubgroups(params["subgroup_data"]) ?: return ToolResult.error(name, "subgroup_data required", "MISSING_DATA")
        if (subgroups.size < 2) return ToolResult.error(name, "Need at least 2 subgroups", "INSUFFICIENT_DATA")
        val n = subgroups[0].size
        val means = subgroups.map { it.average() }; val ranges = subgroups.map { it.max()!! - it.min()!! }
        val grandMean = means.average(); val meanRange = ranges.average()
        val a2 = a2Constant(n); val ucl = grandMean + a2 * meanRange; val lcl = grandMean - a2 * meanRange
        val ooc = means.mapIndexedNotNull { i, m -> if (m > ucl || m < lcl) i else null }

        val message = buildString {
            append("📊 X-bar Control Chart\n\n")
            append("Grand mean: ${"%.2f".format(grandMean)}, Mean range: ${"%.2f".format(meanRange)}\n")
            append("UCL: ${"%.2f".format(ucl)}, CL: ${"%.2f".format(grandMean)}, LCL: ${"%.2f".format(lcl)}\n\n")
            if (ooc.isEmpty()) append("✅ Process is IN CONTROL") else append("⚠️ OUT OF CONTROL at points: ${ooc.map { it + 1 }}")
        }
        return ToolResult.success(name, mapOf("grand_mean" to grandMean, "ucl" to ucl, "lcl" to lcl, "in_control" to ooc.isEmpty(), "ooc_points" to ooc), message)
    }

    private fun rChart(params: Map<String, String>): ToolResult {
        val subgroups = parseSubgroups(params["subgroup_data"]) ?: return ToolResult.error(name, "subgroup_data required", "MISSING_DATA")
        val n = subgroups[0].size; val ranges = subgroups.map { it.max()!! - it.min()!! }; val meanRange = ranges.average()
        val ucl = d4Constant(n) * meanRange; val lcl = max(0.0, d3Constant(n) * meanRange)
        val ooc = ranges.mapIndexedNotNull { i, r -> if (r > ucl || r < lcl) i else null }

        val message = buildString {
            append("📊 R-Chart\n\nMean range: ${"%.2f".format(meanRange)}\n")
            append("UCL: ${"%.2f".format(ucl)}, LCL: ${"%.2f".format(lcl)}\n\n")
            if (ooc.isEmpty()) append("✅ Variability is STABLE") else append("⚠️ Variability OUT OF CONTROL at: ${ooc.map { it + 1 }}")
        }
        return ToolResult.success(name, mapOf("mean_range" to meanRange, "ucl" to ucl, "lcl" to lcl, "in_control" to ooc.isEmpty()), message)
    }

    private fun processCapability(params: Map<String, String>): ToolResult {
        val data = parseDoubleList(params["data"]) ?: return ToolResult.error(name, "data required", "MISSING_DATA")
        val usl = params["usl"]?.toDoubleOrNull(); val lsl = params["lsl"]?.toDoubleOrNull()
        if (usl == null && lsl == null) return ToolResult.error(name, "At least one spec limit required", "MISSING_SPECS")
        val mean = data.average(); val sigma = sqrt(data.map { (it - mean).pow(2) }.sum() / (data.size - 1))
        val cp: Double; val cpk: Double
        if (usl != null && lsl != null) { cp = (usl - lsl) / (6 * sigma); cpk = min((usl - mean) / (3 * sigma), (mean - lsl) / (3 * sigma)) }
        else if (usl != null) { cp = (usl - mean) / (3 * sigma); cpk = cp }
        else { cp = (mean - lsl!!) / (3 * sigma); cpk = cp }
        val capability = when { cpk >= 2.0 -> "World Class"; cpk >= 1.33 -> "Capable"; cpk >= 1.0 -> "Marginal"; else -> "Not Capable" }

        val message = buildString {
            append("📊 Process Capability\n\n")
            append("Mean: ${"%.2f".format(mean)}, σ: ${"%.2f".format(sigma)}\n")
            append("Cp: ${"%.3f".format(cp)}, Cpk: ${"%.3f".format(cpk)}\n")
            append("Assessment: $capability\n\n")
            when { cpk >= 1.33 -> append("✅ Data quality is good"); cpk >= 1.0 -> append("⚠️ Monitor closely"); else -> append("❌ Quality issues likely") }
        }
        return ToolResult.success(name, mapOf("cp" to cp, "cpk" to cpk, "capability" to capability, "mean" to mean, "sigma" to sigma), message)
    }

    private fun acceptanceSampling(params: Map<String, String>): ToolResult {
        val lotSize = params["lot_size"]?.toIntOrNull() ?: return ToolResult.error(name, "lot_size required", "MISSING_LOT")
        val sampleSize = params["sample_size"]?.toIntOrNull() ?: return ToolResult.error(name, "sample_size required", "MISSING_SAMPLE")
        val aql = params["aql"]?.toDoubleOrNull() ?: 0.01; val defectRate = params["defect_rate"]?.toDoubleOrNull() ?: aql
        val expected = sampleSize * aql; val c = max(0, (expected + 2 * sqrt(expected * (1 - aql))).toInt())
        val pa = binomialCDF(c, sampleSize, defectRate)

        val message = buildString {
            append("📊 Acceptance Sampling Plan\n\n")
            append("Lot: $lotSize, Sample: $sampleSize, AQL: ${"%.2f".format(aql * 100)}%\n")
            append("Accept if ≤ $c defects\n")
            append("P(acceptance): ${"%.1f".format(pa * 100)}%\n")
            when { pa > 0.95 -> append("✅ High acceptance probability"); pa > 0.5 -> append("⚠️ Moderate"); else -> append("❌ Most batches rejected") }
        }
        return ToolResult.success(name, mapOf("accept_number" to c, "probability_acceptance" to pa), message)
    }

    private fun westernElectricRules(params: Map<String, String>): ToolResult {
        val data = parseDoubleList(params["data"]) ?: return ToolResult.error(name, "data required", "MISSING_DATA")
        if (data.size < 8) return ToolResult.error(name, "Need ≥8 points", "INSUFFICIENT_DATA")
        val mean = data.average(); val sigma = sqrt(data.map { (it - mean).pow(2) }.average())
        val violations = mutableListOf<String>()

        data.forEachIndexed { i, v -> if (abs(v - mean) > 3 * sigma) violations.add("Rule 1: Point ${i+1} beyond 3σ") }
        for (i in 0..data.size - 3) { val w = data.subList(i, i + 3); if (w.count { it > mean + 2 * sigma } >= 2 || w.count { it < mean - 2 * sigma } >= 2) violations.add("Rule 2: Points ${i+1}-${i+3}") }
        for (i in 0..data.size - 8) { val w = data.subList(i, i + 8); if (w.all { it > mean } || w.all { it < mean }) violations.add("Rule 4: Points ${i+1}-${i+8} same side") }

        val message = buildString {
            append("📊 Western Electric Rules\n\nMean: ${"%.2f".format(mean)}, σ: ${"%.2f".format(sigma)}\n\n")
            if (violations.isEmpty()) append("✅ No violations. Process in control.")
            else { append("⚠️ ${violations.size} violation(s):\n"); violations.forEach { append("  • $it\n") } }
        }
        return ToolResult.success(name, mapOf("in_control" to violations.isEmpty(), "violations" to violations, "violation_count" to violations.size), message)
    }

    private fun fullReport(params: Map<String, String>): ToolResult {
        val data = parseDoubleList(params["data"]) ?: return ToolResult.error(name, "data required", "MISSING_DATA")
        val n = data.size; val mean = data.average(); val sorted = data.sorted()
        val median = if (n % 2 == 0) (sorted[n/2-1] + sorted[n/2]) / 2 else sorted[n/2]
        val sigma = sqrt(data.map { (it - mean).pow(2) }.sum() / (n - 1))
        val q1 = percentile(sorted, 25.0); val q3 = percentile(sorted, 75.0); val iqr = q3 - q1
        val outlierCount = data.count { it < q1 - 1.5 * iqr || it > q3 + 1.5 * iqr }
        val skewness = data.map { ((it - mean) / sigma).pow(3) }.average()

        val message = buildString {
            append("📊 Data Quality Report\n\n")
            append("n=$n, Mean=${"%.2f".format(mean)}, Median=${"%.2f".format(median)}, σ=${"%.2f".format(sigma)}\n")
            append("Q1=${"%.2f".format(q1)}, Q3=${"%.2f".format(q3)}, IQR=${"%.2f".format(iqr)}\n")
            append("Skewness: ${"%.3f".format(skewness)}\n")
            append("Outliers (IQR): $outlierCount")
            when { outlierCount == 0 -> append(" ✅"); outlierCount < n * 0.05 -> append(" ⚠️"); else -> append(" ❌") }
        }
        return ToolResult.success(name, mapOf("n" to n, "mean" to mean, "median" to median, "sigma" to sigma, "outlier_count" to outlierCount, "skewness" to skewness), message)
    }

    private fun a2Constant(n: Int): Double = when (n) { 2 -> 1.880; 3 -> 1.023; 4 -> 0.729; 5 -> 0.577; 6 -> 0.483; else -> 3.0 / (sqrt(n.toDouble()) * d2Constant(n)) }
    private fun d2Constant(n: Int): Double = when (n) { 2 -> 1.128; 3 -> 1.693; 4 -> 2.059; 5 -> 2.326; 6 -> 2.534; else -> 2.0 }
    private fun d3Constant(n: Int): Double = when (n) { in 2..7 -> 0.0; else -> 0.1 }
    private fun d4Constant(n: Int): Double = when (n) { 2 -> 3.267; 3 -> 2.574; 4 -> 2.282; 5 -> 2.114; 6 -> 2.004; else -> 2.0 }

    private fun binomialCDF(k: Int, n: Int, p: Double): Double {
        var sum = 0.0; for (i in 0..k) sum += binomialPMF(i, n, p); return sum.coerceIn(0.0, 1.0)
    }
    private fun binomialPMF(k: Int, n: Int, p: Double): Double {
        val lnC = lnGamma(n + 1.0) - lnGamma(k + 1.0) - lnGamma(n - k + 1.0)
        return exp(lnC + k * ln(p) + (n - k) * ln(1 - p))
    }
    private fun lnGamma(x: Double): Double {
        val c = doubleArrayOf(76.18009172947146, -86.50532032941677, 24.01409824083091, -1.231739572450155, 0.1208650973866179e-2, -0.5395239384953e-5)
        var y = x; var tmp = x + 5.5; tmp -= (x + 0.5) * ln(tmp); var sum = 1.000000000190015
        for (i in 0..5) { sum += c[i] / ++y }; return -tmp + ln(2.5066282746310005 * sum / x)
    }
    private fun percentile(sorted: List<Double>, p: Double): Double {
        val idx = p / 100 * (sorted.size - 1); val lo = idx.toInt(); val hi = (lo + 1).coerceAtMost(sorted.size - 1)
        return sorted[lo] * (1 - (idx - lo)) + sorted[hi] * (idx - lo)
    }
    private fun parseDoubleList(s: String?): List<Double>? { if (s.isNullOrBlank()) return null; return try { s.split(",").map { it.trim().toDouble() } } catch (e: Exception) { null } }
    private fun parseSubgroups(s: String?): List<List<Double>>? { if (s.isNullOrBlank()) return null; return try { s.split(";").map { g -> g.split(",").map { it.trim().toDouble() } } } catch (e: Exception) { null } }
}
