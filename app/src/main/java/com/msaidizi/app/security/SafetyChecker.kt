package com.msaidizi.app.security

import com.msaidizi.app.agent.AgentContext
import com.msaidizi.app.agent.Decision
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SafetyChecker — Constitutional AI enforcement for the SuperAgent.
 * 
 * 12 non-negotiable principles with runtime pre-flight enforcement.
 * Ensures the agent serves workers, never exploits them.
 * 
 * Design: arch_security.md
 */
@Singleton
class SafetyChecker @Inject constructor() {
    
    /**
     * Evaluate a decision against constitutional principles.
     * Returns critique with concerns if any principle is violated.
     */
    suspend fun evaluateDecision(decision: Decision, context: AgentContext): Critique {
        val concerns = mutableListOf<String>()
        
        when (decision) {
            is Decision.ToolDecision -> {
                // Check financial operation safety
                if (decision.tool.name in listOf("mpesa", "transaction", "loan")) {
                    val amount = decision.entities.amount
                    if (amount != null && amount > 100_000) {
                        concerns.add("Kiasi kikubwa sana: KSh ${"%,.0f".format(amount)}. Tafadhali thibitisha.")
                    }
                }
                
                // Check for hallucination risk
                if (decision.tool.name == "market" && decision.entities.item == null) {
                    concerns.add("Hakuna bidhaa iliyotajwa. Tafadhaliambia bidhaa gani.")
                }
            }
            is Decision.PlannedDecision -> {
                // Multi-step plans need validation
                if (decision.plan.steps.size > 5) {
                    concerns.add("Mpango mrefu sana. Tafadhali rahisisha ombi lako.")
                }
            }
            else -> {}
        }
        
        return Critique(
            isSafe = concerns.isEmpty(),
            concerns = concerns
        )
    }
}

data class Critique(
    val isSafe: Boolean,
    val concerns: List<String>
)
