package com.msaidizi.app.agent

import timber.log.Timber
import com.msaidizi.app.core.model.IntentResult
import com.msaidizi.app.core.model.IntentType

/**
 * Routes domain-specific intents to specialized handlers.
 *
 * Handles transport, farming, digital/gig, and service intents
 * that don't fit neatly into sale/purchase/expense categories.
 *
 * Extracted from Orchestrator for Single Responsibility.
 */
class DomainRouter(
    private val businessAgent: BusinessAgent,
    private val advisorAgent: AdvisorAgent
) {
    /**
     * Handle domain-specific intents (transport, farming, digital, service).
     * Treats informational queries via advisorAgent and recordable transactions
     * via businessAgent.
     */
    suspend fun handleDomainIntent(intentResult: IntentResult, language: String): AgentResponse {
        return try {
            when (intentResult.intent) {
                IntentType.TRANSPORT_TRIP,
                IntentType.FARMING_ACTIVITY,
                IntentType.DIGITAL_TRANSACTION,
                IntentType.SERVICE_CLIENT -> {
                    val text = advisorAgent.getDomainAdvice(intentResult, language)
                    AgentResponse(text = text, type = ResponseType.INFORMATION)
                }
                IntentType.TRANSPORT_EXPENSE,
                IntentType.FARMING_INPUT,
                IntentType.DIGITAL_COMMISSION,
                IntentType.SERVICE_JOB -> {
                    val item = intentResult.extractedData["item"] ?: "service"
                    val amount = intentResult.extractedData["amount"]?.toDoubleOrNull() ?: 0.0
                    if (amount > 0) {
                        businessAgent.recordSale(item, 1.0, amount, language)
                        AgentResponse(
                            text = if (language == "sw") "✅ Umerekodi: $item, KSh ${"%.0f".format(amount)}"
                            else "✅ Recorded: $item, KSh ${"%.0f".format(amount)}",
                            type = ResponseType.CONFIRMATION
                        )
                    } else {
                        AgentResponse(
                            text = if (language == "sw") "Bei ni ngapi?" else "What price?",
                            type = ResponseType.CLARIFICATION
                        )
                    }
                }
                else -> AgentResponse(
                    text = if (language == "sw") "Sijaelewa." else "I didn't understand.",
                    type = ResponseType.UNKNOWN
                )
            }
        } catch (e: Throwable) {
            Timber.e(e, "Error handling domain intent: %s", intentResult.intent)
            AgentResponse(
                text = if (language == "sw") "⚠️ Kuna tatizo. Jaribu tena." else "⚠️ Something went wrong. Try again.",
                type = ResponseType.ERROR
            )
        }
    }
}
