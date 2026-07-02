package com.msaidizi.app.onboarding

import com.msaidizi.app.agent.BusinessAgent
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Aha Moment Flow — drives the critical first 60-second experience
 * that hooks new users on Msaidizi.
 *
 * Flow:
 * 1. First launch → voice prompt: "Karibu Msaidizi! Sema kitu kama 'Nimeuza mandazi kwa 500'"
 * 2. After 3 sales recorded → play audio: "Umefaida KSh 350 leo! Unaendelea vizuri!"
 * 3. Must happen within 60 seconds of first use
 *
 * The "aha moment" is when the user realizes this app actually understands
 * their business. For informal workers who've never tracked finances,
 * hearing their own profit in Swahili is transformative.
 *
 * @param businessAgent For querying sales/profit data
 */
class AhaMomentFlow(
    private val businessAgent: BusinessAgent
) {
    companion object {
        private const val TAG = "AhaMomentFlow"
        private const val AHA_SALES_THRESHOLD = 3
        private const val AHA_TIME_WINDOW_MS = 60_000L // 60 seconds
    }

    // Track state
    private var sessionStartTime: Long = 0L
    private var salesRecordedThisSession: Int = 0
    private var welcomeShown: Boolean = false
    private var ahaMomentTriggered: Boolean = false

    /**
     * Called when the app launches for a new session.
     * Returns the welcome voice prompt if this is the first launch.
     *
     * @param language Preferred language
     * @return AhaPrompt with voice text to speak, or null if not applicable
     */
    fun onSessionStart(language: String = "sw"): AhaPrompt? {
        sessionStartTime = System.currentTimeMillis()
        salesRecordedThisSession = 0
        ahaMomentTriggered = false

        if (!welcomeShown) {
            welcomeShown = true
            Timber.d(TAG, "First launch — generating welcome prompt")

            return AhaPrompt(
                textSw = "Karibu Msaidizi! Sema kitu kama 'Nimeuza mandazi kwa 500'",
                textEn = "Welcome to Msaidizi! Say something like 'I sold mandazi for 500'",
                type = AhaPromptType.WELCOME,
                shouldSpeak = true,
                priority = AhaPriority.CRITICAL
            )
        }

        return null
    }

    /**
     * Called after each sale is recorded.
     * Checks if we should trigger the aha moment.
     *
     * @param profit Today's total profit
     * @param language Preferred language
     * @return AhaPrompt with celebration audio, or null
     */
    suspend fun onSaleRecorded(
        language: String = "sw"
    ): AhaPrompt? {
        salesRecordedThisSession++
        Timber.d(TAG, "Sale recorded this session: %d", salesRecordedThisSession)

        // Check if we should trigger the aha moment
        if (ahaMomentTriggered) return null
        if (salesRecordedThisSession < AHA_SALES_THRESHOLD) return null

        val elapsed = System.currentTimeMillis() - sessionStartTime
        if (elapsed > AHA_TIME_WINDOW_MS) {
            Timber.d(TAG, "Aha window passed (%.1fs)", elapsed / 1000.0)
            return null
        }

        // AHA MOMENT!
        ahaMomentTriggered = true
        val profit = businessAgent.getDailyProfit()

        Timber.d(TAG, "🎉 AHA MOMENT triggered! Profit: KSh %.0f", profit)

        return AhaPrompt(
            textSw = "Umefaida KSh ${"%.0f".format(profit)} leo! Unaendelea vizuri!",
            textEn = "You've made KSh ${"%.0f".format(profit)} today! You're doing great!",
            type = AhaPromptType.AHA_CELEBRATION,
            shouldSpeak = true,
            priority = AhaPriority.CRITICAL,
            data = mapOf(
                "profit" to profit.toString(),
                "salesCount" to salesRecordedThisSession.toString(),
                "elapsedMs" to elapsed.toString()
            )
        )
    }

    /**
     * Called when user successfully records their first ever sale.
     * Returns a motivational nudge even outside the 60-second window.
     */
    fun onFirstSaleEver(language: String = "sw"): AhaPrompt {
        Timber.d(TAG, "First sale ever recorded!")

        return AhaPrompt(
            textSw = "Hongera! 🎉 Umerekodi mauzo yako ya kwanza! Sasa unaweza kuona faida yako.",
            textEn = "Congratulations! 🎉 You recorded your first sale! Now you can see your profit.",
            type = AhaPromptType.FIRST_SALE,
            shouldSpeak = true,
            priority = AhaPriority.HIGH
        )
    }

    /**
     * Get contextual help prompt based on user state.
     * Used when user seems confused or idle.
     */
    fun getIdlePrompt(salesCount: Int, language: String = "sw"): AhaPrompt? {
        return when {
            salesCount == 0 -> AhaPrompt(
                textSw = "Jaribu kusema: 'Nimeuza vitu 5 kwa 200'",
                textEn = "Try saying: 'I sold 5 items for 200'",
                type = AhaPromptType.HINT,
                shouldSpeak = false,
                priority = AhaPriority.LOW
            )
            salesCount == 1 -> AhaPrompt(
                textSw = "Vizuri! Sema tena kurekodi mauzo zaidi. Faida yako itaongezeka!",
                textEn = "Great! Say it again to record more sales. Your profit will grow!",
                type = AhaPromptType.HINT,
                shouldSpeak = false,
                priority = AhaPriority.LOW
            )
            else -> null
        }
    }

    /**
     * Reset state for a new session.
     */
    fun reset() {
        sessionStartTime = 0L
        salesRecordedThisSession = 0
        ahaMomentTriggered = false
    }
}

// ═══════════════════════════════════════════════════════════════
// DATA CLASSES
// ═══════════════════════════════════════════════════════════════

data class AhaPrompt(
    val textSw: String,
    val textEn: String,
    val type: AhaPromptType,
    val shouldSpeak: Boolean,
    val priority: AhaPriority,
    val data: Map<String, String> = emptyMap()
) {
    fun getText(language: String): String = if (language == "sw") textSw else textEn
}

enum class AhaPromptType {
    WELCOME,
    AHA_CELEBRATION,
    FIRST_SALE,
    HINT
}

enum class AhaPriority {
    CRITICAL, // Must speak immediately
    HIGH,     // Speak at next opportunity
    LOW       // Show as text only
}
