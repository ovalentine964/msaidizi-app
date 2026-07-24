package com.msaidizi.app.agent.cost

/**
 * Stub: Inference cost tracker for monitoring AI usage costs.
 */
class InferenceCostTracker {
    fun trackCost(model: String, tokens: Int, cost: Double) {}
    fun getTotalCost(): Double = 0.0
    fun getCostByModel(): Map<String, Double> = emptyMap()
}
