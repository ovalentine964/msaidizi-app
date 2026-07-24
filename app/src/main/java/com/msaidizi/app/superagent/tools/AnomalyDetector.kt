package com.msaidizi.app.superagent.tools

import javax.inject.Inject
import javax.inject.Singleton

/**
 * AnomalyDetector — Detect anomalous transaction amounts using z-score analysis.
 */
@Singleton
class AnomalyDetector @Inject constructor() : Tool {

    override val name = "anomaly_detector"
    override val description = "Detect anomalous transaction amounts using statistical analysis"

    private val history = mutableListOf<Double>()

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"] ?: "detect"
        return when (action.lowercase()) {
            "detect" -> {
                val amount = params["amount"]?.toDoubleOrNull()
                    ?: return ToolResult.error(name, "Amount required", "MISSING_AMOUNT")
                detect(amount)
            }
            "reset" -> {
                history.clear()
                ToolResult.success(name, message = "History reset")
            }
            "status" -> {
                ToolResult.success(
                    name,
                    mapOf("history_size" to history.size, "baseline_ready" to (history.size >= 5)),
                    "History: ${history.size} entries. Baseline ${if (history.size >= 5) "ready" else "building"}"
                )
            }
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    fun detect(amount: Double): ToolResult {
        if (history.size < 5) {
            history.add(amount)
            return ToolResult.success(name, mapOf("amount" to amount, "baseline" to false), "Normal - building baseline")
        }
        val mean = history.average()
        val stdDev = Math.sqrt(history.map { (it - mean) * (it - mean) }.average())
        val zScore = if (stdDev > 0) (amount - mean) / stdDev else 0.0
        history.add(amount)
        return if (Math.abs(zScore) > 3.0) {
            ToolResult.error(name, "ANOMALY: z-score=${"%.2f".format(zScore)}, amount=$amount, mean=${"%.2f".format(mean)}", "ANOMALY_DETECTED")
        } else {
            ToolResult.success(name, mapOf("z_score" to zScore, "amount" to amount, "mean" to mean), "Normal: z-score=${"%.2f".format(zScore)}")
        }
    }
}
