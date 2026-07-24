package com.msaidizi.app.superagent.tools
import javax.inject.Inject
class AnomalyDetector @Inject constructor() {
    private val history = mutableListOf<Double>()
    fun detect(amount: Double): ToolResult {
        if (history.size < 5) { history.add(amount); return ToolResult.Success("Normal - building baseline") }
        val mean = history.average()
        val stdDev = Math.sqrt(history.map { (it - mean) * (it - mean) }.average())
        val zScore = if (stdDev > 0) (amount - mean) / stdDev else 0.0
        history.add(amount)
        return if (Math.abs(zScore) > 3.0) ToolResult.Success("ANOMALY: z-score=$zScore, amount=$amount, mean=$mean")
        else ToolResult.Success("Normal: z-score=$zScore")
    }
}
