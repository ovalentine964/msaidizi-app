package com.msaidizi.app.superagent.communication

import timber.log.Timber

/**
 * Communication Module — unified entry point for voice output, briefings,
 * alerts, and reports in the worker's dialect.
 *
 * ## Architecture
 * This module is a capability of the superagent — called directly by the
 * ReasoningEngine to personalize responses and deliver voice content.
 *
 * ## Sub-modules
 * - [VoicePersonality] — warmth, proverbs, cultural flavor
 * - [BriefingEngine] — morning (6:30 AM) and evening briefings
 * - [AlertVoice] — voice-based proactive alerts
 * - [ReportVoice] — voice-based financial reports
 * - [DialectOutput] — output in worker's dialect
 *
 * ## Integration Points
 * - **ReasoningEngine**: calls personalize() on every response
 * - **FinancialModule**: triggers alerts and reports
 * - **GamificationModule**: triggers streak alerts
 * - **EducationModule**: triggers lesson prompts
 *
 * @param voicePersonality For response wrapping with warmth
 * @param briefingEngine For morning/evening briefings
 * @param alertVoice For proactive voice alerts
 * @param reportVoice For financial reports
 * @param dialectOutput For dialect-specific formatting
 */
class CommunicationModule(
    private val voicePersonality: VoicePersonality,
    private val briefingEngine: BriefingEngine,
    private val alertVoice: AlertVoice,
    private val reportVoice: ReportVoice,
    private val dialectOutput: DialectOutput
) {
    companion object {
        private const val TAG = "CommunicationModule"
    }

    /**
     * Personalize a response with personality, warmth, and dialect.
     * This is called on EVERY response from the superagent.
     *
     * @param text The raw response text
     * @param responseType The type of response
     * @param workerName The worker's name
     * @param dialect The worker's dialect
     * @return Personalized response ready for TTS
     */
    fun personalize(
        text: String,
        responseType: ResponseType,
        workerName: String = "",
        dialect: String = "sw"
    ): String {
        // 1. Apply voice personality (warmth, proverbs, encouragement)
        val withPersonality = voicePersonality.wrapResponse(
            text, responseType, workerName, dialect
        )

        // 2. Apply dialect transformation
        return dialectOutput.transform(withPersonality, dialect)
    }

    /**
     * Generate a greeting for the worker.
     *
     * @param workerName The worker's name
     * @param dialect The worker's dialect
     * @return Culturally appropriate greeting
     */
    fun greet(workerName: String, dialect: String = "sw"): String {
        return dialectOutput.getGreeting(workerName, dialect)
    }

    /**
     * Generate a greeting with business context.
     *
     * @param workerName The worker's name
     * @param profit Today's profit
     * @param dialect The worker's dialect
     * @return Greeting with business context
     */
    fun greetWithContext(workerName: String, profit: Double, dialect: String = "sw"): String {
        return voicePersonality.getGreetingWithContext(workerName, profit, dialect)
    }

    /**
     * Get processing feedback phrase (while "thinking").
     */
    fun getProcessingFeedback(dialect: String = "sw"): String {
        return voicePersonality.getProcessingFeedback(dialect)
    }

    // ═══════════════ BRIEFINGS ═══════════════

    /**
     * Generate and deliver morning briefing.
     *
     * @param data Morning briefing data
     * @param dialect The worker's dialect
     * @return Briefing ready for TTS delivery
     */
    fun deliverMorningBriefing(data: MorningBriefingData, dialect: String = "sw"): Briefing {
        return briefingEngine.generateMorningBriefing(data, dialect)
    }

    /**
     * Generate and deliver evening briefing.
     *
     * @param data Evening briefing data
     * @param dialect The worker's dialect
     * @return Briefing ready for TTS delivery
     */
    fun deliverEveningBriefing(data: EveningBriefingData, dialect: String = "sw"): Briefing {
        return briefingEngine.generateEveningBriefing(data, dialect)
    }

    /**
     * Generate quick status briefing (on-demand).
     */
    fun deliverQuickBriefing(data: QuickBriefingData, dialect: String = "sw"): Briefing {
        return briefingEngine.generateQuickBriefing(data, dialect)
    }

    // ═══════════════ ALERTS ═══════════════

    /**
     * Generate a voice alert.
     *
     * @param alert The alert to voice
     * @param dialect The worker's dialect
     * @return Voice-ready alert text
     */
    fun deliverAlert(alert: Alert, dialect: String = "sw"): String {
        return alertVoice.generateAlert(alert, dialect)
    }

    /**
     * Check if an alert should be delivered.
     */
    fun shouldDeliverAlert(alert: Alert, lastAlertTime: Long): Boolean {
        return alertVoice.shouldDeliver(alert, lastAlertTime)
    }

    // ═══════════════ REPORTS ═══════════════

    /**
     * Generate a voice report.
     *
     * @param report The report data
     * @param dialect The worker's dialect
     * @return Voice-ready report text
     */
    fun deliverReport(report: FinancialReport, dialect: String = "sw"): String {
        return reportVoice.generateReport(report, dialect)
    }

    // ═══════════════ FORMATTING ═══════════════

    /**
     * Format a number for voice output.
     */
    fun formatNumber(number: Double): String {
        return dialectOutput.formatNumber(number)
    }

    /**
     * Format currency for voice output.
     */
    fun formatCurrency(amount: Double, dialect: String = "sw"): String {
        return dialectOutput.formatCurrency(amount, dialect)
    }

    /**
     * Transform text for a specific dialect.
     */
    fun transformDialect(text: String, dialect: String): String {
        return dialectOutput.transform(text, dialect)
    }
}
