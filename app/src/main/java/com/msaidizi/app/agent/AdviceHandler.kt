package com.msaidizi.app.agent

import com.msaidizi.app.evolution.SelfEvolutionManager
import timber.log.Timber
import java.util.UUID

/**
 * Handles advice, greeting, help, and correction intents.
 *
 * Extracted from Orchestrator for Single Responsibility.
 * Integrates self-evolution tracking for advice outcome measurement.
 *
 * **ECO 206/209/210/322:** Advice grounded in microfinance, money & banking,
 * quantitative methods, and macroeconomic theory.
 */
class AdviceHandler(
    private val advisorAgent: AdvisorAgent,
    private val adaptiveLearning: AdaptiveLearningEngine,
    private val selfEvolution: SelfEvolutionManager? = null,
    private val preferenceLearner: PreferenceLearner? = null
) {
    /** Handle advice request with personalization and evolution tracking. */
    suspend fun handleAdvice(language: String): AgentResponse {
        val personalizedContext = adaptiveLearning.generatePersonalizedContext(
            maxTokens = 200, language = language
        )
        val preferenceContext = preferenceLearner?.generatePreferenceContext(language) ?: ""

        val baseAdvice = advisorAgent.getAdvice(language)
        val text = buildString {
            append(baseAdvice)
            if (personalizedContext.isNotBlank()) {
                append("\n\n")
                append(if (language == "sw") "📋 Kulingana na biashara yako: " else "📋 Based on your business: ")
                append(personalizedContext)
            }
            if (preferenceContext.isNotBlank()) { append("\n"); append(preferenceContext) }
        }

        val adviceId = "advice_${UUID.randomUUID().toString().take(8)}"
        selfEvolution?.trackAdviceDelivery(
            adviceId = adviceId, adviceType = "business_advice",
            adviceText = text, context = mapOf("language" to language)
        )

        return AgentResponse(text = text, type = ResponseType.ADVICE, data = mapOf("adviceId" to adviceId))
    }

    /** Handle greeting. */
    suspend fun handleGreeting(language: String): AgentResponse {
        val text = advisorAgent.getGreeting(language)
        return AgentResponse(text = text, type = ResponseType.GREETING)
    }

    /** Handle help request. */
    fun handleHelp(language: String): AgentResponse {
        val text = advisorAgent.getHelp(language)
        return AgentResponse(text = text, type = ResponseType.HELP)
    }

    /** Handle correction of last transaction. */
    suspend fun handleCorrection(
        text: String, language: String,
        lastTransaction: com.msaidizi.app.core.model.Transaction?
    ): AgentResponse {
        if (lastTransaction == null) {
            return AgentResponse(
                text = if (language == "sw") "Hakuna shughuli ya kurekebisha." else "No transaction to correct.",
                type = ResponseType.ERROR
            )
        }

        val isCorrection = adaptiveLearning.parseAndRecordCorrection(
            text = text, lastTransaction = lastTransaction, language = language
        )

        if (isCorrection) {
            return AgentResponse(
                text = if (language == "sw") "✅ Nimekumbuka marekebisho! Nitakumbuka kwa mara ijayo."
                else "✅ Correction recorded! I'll remember that for next time.",
                type = ResponseType.CONFIRMATION
            )
        }

        return AgentResponse(
            text = if (language == "sw") "Ni nini kibaya? Sema: 'Bei ni X' au 'Bidhaa ni Y'"
            else "What's wrong? Say: 'Price is X' or 'Item is Y'",
            type = ResponseType.CLARIFICATION
        )
    }

    /** Handle unknown intent. */
    fun handleUnknown(text: String, language: String): AgentResponse {
        return AgentResponse(
            text = if (language == "sw") "Sijaelewa. Sema: 'Nimeuza' kurekodi mauzo, au 'Nisaidie' kwa usaidizi."
            else "I didn't understand. Say: 'I sold' to record a sale, or 'Help me' for assistance.",
            type = ResponseType.UNKNOWN
        )
    }
}
