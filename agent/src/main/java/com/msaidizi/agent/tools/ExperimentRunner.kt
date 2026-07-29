package com.msaidizi.agent.tools

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*
import kotlin.random.Random

/**
 * ExperimentRunner — Experimental design tool (STA 343).
 *
 * Designs and manages controlled experiments on business strategies.
 * Implements core experimental design principles:
 *   - Random assignment to treatment/control groups
 *   - Blocking by business type (reduces noise)
 *   - Factorial design for multi-factor experiments
 *   - Power analysis for sample size determination
 *   - Permutation-based result analysis
 */
@Singleton
class ExperimentRunner @Inject constructor() : Tool {

    override val name = "experiment_runner"
    override val description = "Design and run controlled experiments: random assignment, blocking, factorial designs, power analysis"

    override val argsSchema = argSchema {
        enum("action", "Experiment action",
            listOf("create_experiment", "randomize", "block_assign", "factorial_design", "power_analysis", "analyze_results", "balance_check"),
            required = true)
        string("experiment_name", "Name of the experiment", required = false)
        string("participants", "Comma-separated participant IDs", required = false)
        string("business_types", "Comma-separated business types for blocking", required = false)
        string("factors", "Comma-separated factor names for factorial design", required = false)
        string("levels", "Comma-separated levels per factor (e.g., '2,3')", required = false)
        number("effect_size", "Expected effect size (Cohen's d)", required = false)
        number("power", "Desired statistical power (default 0.80)", required = false)
        number("alpha", "Significance level (default 0.05)", required = false)
        string("group1_outcomes", "Comma-separated outcomes for control group", required = false)
        string("group2_outcomes", "Comma-separated outcomes for treatment group", required = false)
        string("treatment_name", "Name of treatment condition", required = false)
    }

    private val experiments = mutableMapOf<String, Experiment>()

    data class Experiment(
        val name: String, val createdAt: Long = System.currentTimeMillis(),
        val factors: List<String> = emptyList(),
        val groups: Map<String, List<String>> = emptyMap(),
        val blocks: Map<String, List<String>> = emptyMap(),
        val status: String = "created"
    )

    override suspend fun execute(params: Map<String, String>): ToolResult {
        return try {
            val action = params["action"] ?: return ToolResult.error(name, "Action required", "MISSING_ACTION")
            when (action.lowercase()) {
                "create_experiment" -> createExperiment(params)
                "randomize" -> randomize(params)
                "block_assign" -> blockAssign(params)
                "factorial_design" -> factorialDesign(params)
                "power_analysis" -> powerAnalysis(params)
                "analyze_results" -> analyzeResults(params)
                "balance_check" -> balanceCheck(params)
                else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
            }
        } catch (e: Exception) { Timber.e(e, "Experiment failed"); ToolResult.error(name, "Failed: ${e.message}", "EXPERIMENT_ERROR") }
    }

    private fun createExperiment(params: Map<String, String>): ToolResult {
        val expName = params["experiment_name"] ?: return ToolResult.error(name, "experiment_name required", "MISSING_NAME")
        val factors = params["factors"]?.split(",")?.map { it.trim() } ?: emptyList()
        experiments[expName] = Experiment(name = expName, factors = factors)
        return ToolResult.success(name, mapOf("experiment" to expName, "factors" to factors, "status" to "created"),
            "🧪 Experiment Created: $expName\n${if (factors.isNotEmpty()) "Factors: ${factors.joinToString(", ")}\n" else ""}Ready for randomization.")
    }

    private fun randomize(params: Map<String, String>): ToolResult {
        val participants = params["participants"]?.split(",")?.map { it.trim() }
            ?: return ToolResult.error(name, "participants required", "MISSING_PARTICIPANTS")
        val shuffled = participants.shuffled(Random(participants.hashCode()))
        val mid = shuffled.size / 2
        val control = shuffled.subList(0, mid); val treatment = shuffled.subList(mid, shuffled.size)
        val expName = params["experiment_name"] ?: "unnamed"
        experiments[expName] = Experiment(name = expName, groups = mapOf("control" to control, "treatment" to treatment), status = "randomized")

        val message = buildString {
            append("🎲 Random Assignment Complete\n\n")
            append("Total: ${participants.size}, Control: ${control.size}, Treatment: ${treatment.size}\n\n")
            append("Control: ${control.joinToString(", ")}\n")
            append("Treatment: ${treatment.joinToString(", ")}\n\n")
            append("💡 Run experiment, collect outcomes, use 'analyze_results'.")
        }
        return ToolResult.success(name, mapOf("control" to control, "treatment" to treatment, "n_control" to control.size, "n_treatment" to treatment.size), message)
    }

    private fun blockAssign(params: Map<String, String>): ToolResult {
        val participants = params["participants"]?.split(",")?.map { it.trim() }
            ?: return ToolResult.error(name, "participants required", "MISSING_PARTICIPANTS")
        val businessTypes = params["business_types"]?.split(",")?.map { it.trim() }
            ?: return ToolResult.error(name, "business_types required", "MISSING_TYPES")
        if (participants.size != businessTypes.size) return ToolResult.error(name, "Mismatched counts", "MISMATCH")

        val blocks = participants.zip(businessTypes).groupBy({ it.second }, { it.first })
        val control = mutableListOf<String>(); val treatment = mutableListOf<String>()
        val blockDetails = mutableMapOf<String, Map<String, List<String>>>()

        blocks.forEach { (blockName, members) ->
            val shuffled = members.shuffled(); val mid = shuffled.size / 2
            val bc = shuffled.subList(0, mid); val bt = shuffled.subList(mid, shuffled.size)
            control.addAll(bc); treatment.addAll(bt)
            blockDetails[blockName] = mapOf("control" to bc, "treatment" to bt)
        }

        val message = buildString {
            append("🎲📊 Block Randomization Complete\n\n")
            append("Blocks: ${blocks.size} business types\n")
            blockDetails.forEach { (b, g) -> append("  $b: ${g["control"]?.size} ctrl, ${g["treatment"]?.size} trt\n") }
            append("\nControl: ${control.size}, Treatment: ${treatment.size}\n")
            append("💡 Blocking reduces noise from business-type differences.")
        }
        return ToolResult.success(name, mapOf("control" to control, "treatment" to treatment, "blocks" to blockDetails), message)
    }

    private fun factorialDesign(params: Map<String, String>): ToolResult {
        val factors = params["factors"]?.split(",")?.map { it.trim() }
            ?: return ToolResult.error(name, "factors required", "MISSING_FACTORS")
        val levels = params["levels"]?.split(",")?.map { it.trim().toIntOrNull() ?: 2 } ?: List(factors.size) { 2 }
        if (factors.size != levels.size) return ToolResult.error(name, "Mismatched counts", "MISMATCH")

        val totalCells = levels.reduce { acc, i -> acc * i }
        val combos = generateFactorialCombinations(factors, levels)

        val message = buildString {
            append("🧪 Factorial Design: ${factors.size} factors\n\n")
            factors.forEachIndexed { i, f -> append("  $f: ${levels[i]} levels\n") }
            append("\nTotal combinations: $totalCells\n\n")
            combos.forEachIndexed { idx, combo -> append("  Cell ${idx+1}: ${combo.entries.joinToString(", ") { "${it.key}=${it.value}" }}\n") }
            append("\n💡 Tests ALL combinations. Detect interactions between factors.")
        }
        return ToolResult.success(name, mapOf("factors" to factors, "levels" to levels, "total_cells" to totalCells, "combinations" to combos), message)
    }

    private fun powerAnalysis(params: Map<String, String>): ToolResult {
        val effectSize = params["effect_size"]?.toDoubleOrNull() ?: return ToolResult.error(name, "effect_size required", "MISSING_EFFECT")
        val power = params["power"]?.toDoubleOrNull() ?: 0.80; val alpha = params["alpha"]?.toDoubleOrNull() ?: 0.05
        val zAlpha = normalInverse(1 - alpha / 2); val zBeta = normalInverse(power)
        val n = ceil((zAlpha + zBeta).pow(2) * 2 / effectSize.pow(2)).toInt()

        val message = buildString {
            append("📊 Power Analysis\n\n")
            append("Effect size: ${"%.2f".format(effectSize)}, α=${"%.3f".format(alpha)}, Power=${"%.2f".format(power)}\n\n")
            append("Required per group: $n\nTotal: ${n * 2}\n")
        }
        return ToolResult.success(name, mapOf("n_per_group" to n, "total_n" to (n * 2)), message)
    }

    private fun analyzeResults(params: Map<String, String>): ToolResult {
        val group1 = parseDoubleList(params["group1_outcomes"]) ?: return ToolResult.error(name, "group1_outcomes required", "MISSING_DATA")
        val group2 = parseDoubleList(params["group2_outcomes"]) ?: return ToolResult.error(name, "group2_outcomes required", "MISSING_DATA")
        val treatmentName = params["treatment_name"] ?: "treatment"
        val n1 = group1.size; val n2 = group2.size
        val mean1 = group1.average(); val mean2 = group2.average(); val diff = mean2 - mean1

        // Permutation test
        val combined = group1 + group2; val rng = Random(42); var countMore = 0; val obsDiff = abs(diff)
        repeat(10000) { val perm = combined.shuffled(rng); val d = abs(perm.subList(0, n1).average() - perm.subList(n1, combined.size).average()); if (d >= obsDiff) countMore++ }
        val pValue = countMore.toDouble() / 10000

        // Bootstrap CI
        val bootDiffs = (0 until 5000).map { List(n1) { group1[Random.nextInt(n1)] }.average() - List(n2) { group2[Random.nextInt(n2)] }.average() }.sorted()
        val ciLower = bootDiffs[125]; val ciUpper = bootDiffs[4875]
        val significant = pValue < 0.05

        val message = buildString {
            append("📊 Experiment Results: $treatmentName\n\n")
            append("Control: n=$n1, mean=${"%.2f".format(mean1)}\n")
            append("Treatment: n=$n2, mean=${"%.2f".format(mean2)}\n")
            append("Difference: ${"%.2f".format(diff)}\n")
            append("Permutation p-value: ${"%.4f".format(pValue)}\n")
            append("95% Bootstrap CI: [${"%.2f".format(ciLower)}, ${"%.2f".format(ciUpper)}]\n\n")
            if (significant) append("✅ Significant effect detected.")
            else append("❌ No significant effect. Consider larger sample or stronger treatment.")
        }
        return ToolResult.success(name, mapOf("control_mean" to mean1, "treatment_mean" to mean2, "difference" to diff, "p_value" to pValue, "significant" to significant, "ci_lower" to ciLower, "ci_upper" to ciUpper), message)
    }

    private fun balanceCheck(params: Map<String, String>): ToolResult {
        val group1 = parseDoubleList(params["group1_outcomes"]) ?: return ToolResult.error(name, "group1_outcomes required", "MISSING_DATA")
        val group2 = parseDoubleList(params["group2_outcomes"]) ?: return ToolResult.error(name, "group2_outcomes required", "MISSING_DATA")
        val mean1 = group1.average(); val mean2 = group2.average()
        val var1 = group1.map { (it - mean1).pow(2) }.average(); val var2 = group2.map { (it - mean2).pow(2) }.average()
        val pooledSD = sqrt((var1 + var2) / 2); val smd = if (pooledSD > 0) abs(mean1 - mean2) / pooledSD else 0.0
        val balanced = smd < 0.1

        val message = buildString {
            append("📊 Balance Check\n\n")
            append("SMD: ${"%.4f".format(smd)}\n")
            if (balanced) append("✅ Groups are well-balanced (SMD < 0.1)")
            else append("⚠️ Groups may be imbalanced. Consider re-randomization or blocking.")
        }
        return ToolResult.success(name, mapOf("smd" to smd, "balanced" to balanced), message)
    }

    private fun generateFactorialCombinations(factors: List<String>, levels: List<Int>): List<Map<String, String>> {
        if (factors.isEmpty()) return listOf(emptyMap())
        val result = mutableListOf<Map<String, String>>()
        val firstLevels = (1..levels[0]).map { "L$it" }
        val rest = generateFactorialCombinations(factors.drop(1), levels.drop(1))
        for (level in firstLevels) for (combo in rest) result.add(mapOf(factors[0] to level) + combo)
        return result
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

    private fun parseDoubleList(s: String?): List<Double>? {
        if (s.isNullOrBlank()) return null
        return try { s.split(",").map { it.trim().toDouble() } } catch (e: NumberFormatException) { null }
    }
}
