package com.msaidizi.app.agent

/**
 * Stub: Advisor agent for financial advice.
 */
class AdvisorAgent(
    private val businessAgent: BusinessAgent,
    private val analysisAgent: AnalysisAgent,
    private val voicePersonality: VoicePersonality
) {
    suspend fun getAdvice(topic: String, language: String = "sw"): String = "Ushauri: endelea kurekodi mauzo yako."
}
