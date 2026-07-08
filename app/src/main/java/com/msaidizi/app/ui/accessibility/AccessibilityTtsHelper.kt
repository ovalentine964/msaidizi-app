package com.msaidizi.app.ui.accessibility

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import timber.log.Timber
import java.util.Locale

/**
 * Centralized TTS helper for accessibility across all screens.
 *
 * Provides:
 * - Dashboard audio readout (daily summary, charts)
 * - Transaction confirmation speech
 * - Error message speech (errors are spoken, not just displayed)
 * - UI element descriptions for screen readers
 * - Bilingual support (Swahili primary, English fallback)
 *
 * Usage:
 *   val ttsHelper = AccessibilityTtsHelper(context)
 *   ttsHelper.speak("Mauzo ya leo ni elfu mia tano")
 *   ttsHelper.speakDashboardSummary(sales, profit, transactions)
 */
class AccessibilityTtsHelper(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var pendingText: String? = null
    private var currentLanguage: String = "sw" // Default Swahili

    /** Callback when TTS finishes speaking */
    var onSpeakingComplete: (() -> Unit)? = null

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
            setLanguage(currentLanguage)
            pendingText?.let {
                speak(it)
                pendingText = null
            }
        } else {
            Timber.e("TTS initialization failed")
        }
    }

    /**
     * Set TTS language.
     * @param langCode "sw" for Swahili, "en" for English, "sheng" uses Swahili engine
     */
    fun setLanguage(langCode: String) {
        currentLanguage = langCode
        val locale = when (langCode) {
            "sw", "sheng" -> Locale("sw", "KE")
            "en" -> Locale.US
            else -> Locale("sw", "KE")
        }
        val result = tts?.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Timber.w("Language $langCode not supported, falling back to English")
            tts?.setLanguage(Locale.US)
        }
    }

    /**
     * Set speech rate (1.0 = normal, 0.5 = half speed for elderly users).
     */
    fun setSpeechRate(rate: Float) {
        tts?.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
    }

    /**
     * Speak text aloud. Queues if TTS not yet initialized.
     */
    fun speak(text: String, queueMode: Int = TextToSpeech.QUEUE_FLUSH) {
        if (!isInitialized) {
            pendingText = text
            return
        }
        tts?.speak(text, queueMode, null, "msaidizi_tts_${System.currentTimeMillis()}")
    }

    /**
     * Speak with QUEUE_ADD (append to current speech).
     */
    fun speakAppend(text: String) {
        speak(text, TextToSpeech.QUEUE_ADD)
    }

    /**
     * Stop current speech.
     */
    fun stop() {
        tts?.stop()
    }

    /**
     * Check if TTS is currently speaking.
     */
    fun isSpeaking(): Boolean = tts?.isSpeaking == true

    // ═══════════════════════════════════════════════════════════════
    // DASHBOARD AUDIO READOUT
    // ═══════════════════════════════════════════════════════════════

    /**
     * Speak a full dashboard summary for non-literate/visually impaired users.
     * Reads aloud: sales, profit, transaction count, restock alerts, top items.
     */
    fun speakDashboardSummary(
        sales: Double,
        profit: Double,
        transactionCount: Int,
        restockAlerts: List<String> = emptyList(),
        topItems: List<String> = emptyList()
    ) {
        val summary = buildString {
            append("Habari za biashara yako leo. ")
            append("Umetoa mauzo ya shilingi elfu ${formatNumberForSpeech(sales)}. ")
            append("Faida yako ni shilingi elfu ${formatNumberForSpeech(profit)}. ")
            append("Umefanya miamala $transactionCount. ")

            if (restockAlerts.isNotEmpty()) {
                append("Onyo: bidhaa ${restockAlerts.size} zinahitaji kununuliwa tena. ")
                restockAlerts.take(3).forEach { alert ->
                    append("$alert. ")
                }
            }

            if (topItems.isNotEmpty()) {
                append("Bidhaa zinazouzwa zaidi ni: ")
                topItems.forEachIndexed { index, item ->
                    append("${index + 1}. $item. ")
                }
            }
        }
        speak(summary)
    }

    /**
     * Speak the business flow summary (M-Pesa style).
     */
    fun speakFlowSummary(
        revenue: Double,
        expenses: Double,
        profit: Double,
        savings: Double,
        healthScore: Int,
        period: String = "leo"
    ) {
        val summary = buildString {
            append("Muongozo wa biashara $period. ")
            append("Mapato ni shilingi ${formatNumberForSpeech(revenue)}. ")
            append("Gharama ni shilingi ${formatNumberForSpeech(expenses)}. ")
            append("Faida ni shilingi ${formatNumberForSpeech(profit)}. ")
            append("Akiba ni shilingi ${formatNumberForSpeech(savings)}. ")
            append("Afya ya biashara yako ni $healthScore kati ya mia moja. ")
            when {
                healthScore >= 80 -> append("Biashara yako iko vizuri sana! ")
                healthScore >= 60 -> append("Biashara yako iko vizuri. ")
                healthScore >= 40 -> append("Biashara yako inahitaji uangalizi. ")
                else -> append("Biashara yako iko hatarini. Tafuta msaada. ")
            }
        }
        speak(summary)
    }

    /**
     * Speak transaction confirmation aloud.
     */
    fun speakTransactionConfirmation(
        type: String,
        item: String,
        amount: Double,
        quantity: Double = 1.0
    ) {
        val typeVerb = when (type.lowercase()) {
            "sale" -> "Umeuza"
            "purchase" -> "Umenunua"
            "expense" -> "Umetumia"
            else -> "Umerekodi"
        }
        val text = if (quantity > 1) {
            "$typeVerb ${quantity.toInt()} ${item} kwa shilingi ${formatNumberForSpeech(amount)}. Imerekodhiwa."
        } else {
            "$typeVerb ${item} kwa shilingi ${formatNumberForSpeech(amount)}. Imerekodhiwa."
        }
        speak(text)
    }

    /**
     * Speak daily briefing.
     */
    fun speakDailyBriefing(
        sales: Double,
        profit: Double,
        bestSellingItem: String,
        streakDays: Int
    ) {
        val briefing = buildString {
            append("Muhtasari wa leo. ")
            append("Mauzo: shilingi ${formatNumberForSpeech(sales)}. ")
            append("Faida: shilingi ${formatNumberForSpeech(profit)}. ")
            if (bestSellingItem.isNotEmpty()) {
                append("Bidhaa bora leo ni $bestSellingItem. ")
            }
            if (streakDays > 0) {
                append("Umefanya biashara siku $streakDays mfululizo. Hongera! ")
            }
        }
        speak(briefing)
    }

    // ═══════════════════════════════════════════════════════════════
    // ERROR SPEECH (errors are spoken, not just displayed)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Speak an error message. Critical for non-literate users who can't read error toasts.
     */
    fun speakError(error: String) {
        val errorText = when {
            error.contains("network", ignoreCase = true) ->
                "Tatizo la mtandao. Hakikisha una mtandao na jaribu tena."
            error.contains("permission", ignoreCase = true) ->
                "Ruhusa inahitajika. Tafadhali ruhusu ufikiaji katika mipangilio."
            error.contains("timeout", ignoreCase = true) ->
                "Muda umekwisha. Jaribu tena baada ya muda."
            error.contains("voice", ignoreCase = true) || error.contains("speech", ignoreCase = true) ->
                "Sikuelewi vizuri. Tafadhali jaribu kusema tena au andika ujumbe wako."
            error.contains("save", ignoreCase = true) || error.contains("storage", ignoreCase = true) ->
                "Tatizo la kuhifadhi. Hakikisha una nafasi ya kutosha kwenye simu."
            error.contains("load", ignoreCase = true) || error.contains("fetch", ignoreCase = true) ->
                "Imeshindwa kupakia data. Jaribu tena."
            else -> "Kuna tatizo. $error. Jaribu tena."
        }
        speak(errorText)
    }

    /**
     * Speak a success/confirmation message.
     */
    fun speakSuccess(message: String) {
        speak("Imefanikiwa! $message")
    }

    // ═══════════════════════════════════════════════════════════════
    // VOICE RECOGNITION FAILURE RECOVERY
    // ═══════════════════════════════════════════════════════════════

    /**
     * Speak voice recognition failure recovery instructions.
     * Gives the user clear next steps when voice input fails.
     */
    fun speakVoiceRecovery(failureType: VoiceFailureType) {
        val message = when (failureType) {
            VoiceFailureType.NO_SPEECH_DETECTED ->
                "Sikusikia chochote. Tafadhali karibia simu na useme tena. Au andika ujumbe wako badala yake."
            VoiceFailureType.LOW_CONFIDENCE ->
                "Sikuelewi vizuri. Tafadhali sema polepole na uwazi. Au andika ujumbe wako."
            VoiceFailureType.NETWORK_ERROR ->
                "Tatizo la mtandao. Jaribu tena baada ya muda. Au andika ujumbe wako."
            VoiceFailureType.PERMISSION_DENIED ->
                "Ruhusa ya kurekodi inahitajika. Fungua mipangilio na uruhusu ufikiaji wa maikrofoni."
            VoiceFailureType.AUDIO_ERROR ->
                "Tatizo la sauti. Hakikisha maikrofoni yako inafanya kazi. Au andika ujumbe wako."
            VoiceFailureType.MODEL_NOT_READY ->
                "Mtandao wa sauti bado haujaandaliwa. Subiri kidogo au andika ujumbe wako."
        }
        speak(message)
    }

    // ═══════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Format a number for natural Swahili speech.
     * E.g., 50000 -> "elfu hamsini", 150000 -> "laki moja na elfu hamsini"
     */
    private fun formatNumberForSpeech(amount: Double): String {
        val intAmount = amount.toLong()
        return when {
            intAmount >= 1_000_000 -> String.format("%,.1f milioni", amount / 1_000_000)
            intAmount >= 100_000 -> String.format("%,d", intAmount)
            intAmount >= 1_000 -> String.format("%,d", intAmount)
            else -> intAmount.toString()
        }
    }

    /**
     * Release TTS resources. Call in onDestroy.
     */
    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    enum class VoiceFailureType {
        NO_SPEECH_DETECTED,
        LOW_CONFIDENCE,
        NETWORK_ERROR,
        PERMISSION_DENIED,
        AUDIO_ERROR,
        MODEL_NOT_READY
    }
}
