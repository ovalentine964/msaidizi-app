package com.msaidizi.app.agent

/**
 * Stub: Voice personality for agent responses.
 */
class VoicePersonality {
    fun getProcessingFeedback(language: String = "sw"): String {
        return if (language == "sw") "Sawa, nimesikia..." else "Got it, let me think..."
    }
    fun wrapResponse(text: String, language: String = "sw"): String = text
    fun getGreeting(hourOfDay: Int, language: String = "sw"): String = "Habari!"
}
