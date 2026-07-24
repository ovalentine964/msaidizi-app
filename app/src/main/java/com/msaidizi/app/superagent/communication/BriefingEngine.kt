package com.msaidizi.app.superagent.communication

import timber.log.Timber

/**
 * Briefing Engine — morning (6:30 AM) and evening briefings.
 *
 * ## Morning Briefing (6:30 AM)
 * - Weather summary for the day
 * - Market conditions and price highlights
 * - Daily lesson preview
 * - Streak status reminder
 * - One business tip for the day
 *
 * ## Evening Briefing
 * - Today's sales summary
 * - Profit calculation
 * - Streak update
 * - Tomorrow's preparation tips
 * - Savings reminder
 *
 * ## Delivery
 * All briefings are voice-first, delivered in the worker's dialect.
 * Text is transformed for natural speech (numbers spelled out, etc.)
 *
 * @param voicePersonality For wrapping briefings with warmth
 * @param dialectOutput For dialect-specific formatting
 */
class BriefingEngine(
    private val voicePersonality: VoicePersonality,
    private val dialectOutput: DialectOutput
) {
    companion object {
        private const val TAG = "BriefingEngine"

        /** Morning briefing time */
        const val MORNING_HOUR = 6
        const val MORNING_MINUTE = 30

        /** Evening briefing time */
        const val EVENING_HOUR = 19
    }

    /**
     * Generate morning briefing content.
     *
     * @param data Morning briefing data
     * @param language "sw" or "en"
     * @return Briefing ready for TTS delivery
     */
    fun generateMorningBriefing(data: MorningBriefingData, language: String = "sw"): Briefing {
        val content = buildString {
            // Greeting
            append(voicePersonality.getGreeting(data.workerName, language))
            append("\n\n")

            // Streak status
            if (data.streakDays > 0) {
                if (language == "sw") {
                    append("🔥 Streak yako: siku ${data.streakDays} mfululizo! ")
                    if (data.streakDays >= 7) append("Wiki ${data.streakDays / 7} imekamilika! ")
                    append("\n\n")
                } else {
                    append("🔥 Your streak: ${data.streakDays} days in a row! ")
                    if (data.streakDays >= 7) append("Week ${data.streakDays / 7} complete! ")
                    append("\n\n")
                }
            }

            // Yesterday's summary
            if (data.yesterdaySales > 0) {
                if (language == "sw") {
                    append("Jana: mauzo ${data.yesterdaySales}, faida KSh ${"%.0f".format(data.yesterdayProfit)}. ")
                    append("\n\n")
                } else {
                    append("Yesterday: ${data.yesterdaySales} sales, profit KSh ${"%.0f".format(data.yesterdayProfit)}. ")
                    append("\n\n")
                }
            }

            // Market conditions
            data.marketTip?.let { tip ->
                if (language == "sw") {
                    append("📊 Soko la leo: $tip")
                } else {
                    append("📊 Today's market: $tip")
                }
                append("\n\n")
            }

            // Daily lesson preview
            data.lessonPreview?.let { lesson ->
                if (language == "sw") {
                    append("📖 Somo la leo: $lesson")
                } else {
                    append("📖 Today's lesson: $lesson")
                }
                append("\n\n")
            }

            // Business tip
            data.businessTip?.let { tip ->
                append(tip)
            }
        }

        val wrappedContent = voicePersonality.wrapResponse(
            content.trim(),
            ResponseType.GREETING,
            data.workerName,
            language
        )

        return Briefing(
            type = BriefingType.MORNING,
            content = wrappedContent,
            dialect = data.dialect,
            estimatedDurationSeconds = estimateDuration(wrappedContent)
        )
    }

    /**
     * Generate evening briefing content.
     *
     * @param data Evening briefing data
     * @param language "sw" or "en"
     * @return Briefing ready for TTS delivery
     */
    fun generateEveningBriefing(data: EveningBriefingData, language: String = "sw"): Briefing {
        val content = buildString {
            // Greeting
            append(voicePersonality.getGreeting(data.workerName, language))
            append("\n\n")

            // Today's summary
            if (language == "sw") {
                append("📊 Muhtasari wa leo:\n")
                append("• Mauzo: ${data.todaySales}\n")
                append("• Mapato: KSh ${"%.0f".format(data.todayRevenue)}\n")
                append("• Gharama: KSh ${"%.0f".format(data.todayExpenses)}\n")
                append("• Faida: KSh ${"%.0f".format(data.todayProfit)}\n\n")
            } else {
                append("📊 Today's summary:\n")
                append("• Sales: ${data.todaySales}\n")
                append("• Revenue: KSh ${"%.0f".format(data.todayRevenue)}\n")
                append("• Expenses: KSh ${"%.0f".format(data.todayExpenses)}\n")
                append("• Profit: KSh ${"%.0f".format(data.todayProfit)}\n\n")
            }

            // Profit insight
            if (data.todayProfit > data.averageDailyProfit * 1.5) {
                if (language == "sw") {
                    append("🚀 Leo umefanya vizuri! Faida yako ni kubwa kuliko kawaida!\n\n")
                } else {
                    append("🚀 Great day! Your profit is above average!\n\n")
                }
            } else if (data.todayProfit < data.averageDailyProfit * 0.5 && data.todayProfit > 0) {
                if (language == "sw") {
                    append("💪 Leo ilikuwa ngumu, lakini bado ulifanya faida. Kesho itakuwa bora!\n\n")
                } else {
                    append("💪 Today was tough, but you still made profit. Tomorrow will be better!\n\n")
                }
            }

            // Streak update
            if (data.streakMaintained) {
                if (language == "sw") {
                    append("🔥 Streak yako imeendelea! Siku ${data.currentStreak} mfululizo.\n\n")
                } else {
                    append("🔥 Streak maintained! ${data.currentStreak} days in a row.\n\n")
                }
            }

            // Tomorrow prep
            data.tomorrowTip?.let { tip ->
                if (language == "sw") {
                    append("📋 Kesho: $tip")
                } else {
                    append("📋 Tomorrow: $tip")
                }
            }
        }

        val wrappedContent = voicePersonality.wrapResponse(
            content.trim(),
            ResponseType.INFORMATION,
            data.workerName,
            language
        )

        return Briefing(
            type = BriefingType.EVENING,
            content = wrappedContent,
            dialect = data.dialect,
            estimatedDurationSeconds = estimateDuration(wrappedContent)
        )
    }

    /**
     * Generate a quick status briefing (on-demand).
     */
    fun generateQuickBriefing(data: QuickBriefingData, language: String = "sw"): Briefing {
        val content = if (language == "sw") {
            buildString {
                append("Leo: mauzo ${data.todaySales}, faida KSh ${"%.0f".format(data.todayProfit)}. ")
                append("Streak: siku ${data.currentStreak}. ")
                append("Level: ${data.levelName} ${data.levelEmoji}.")
            }
        } else {
            buildString {
                append("Today: ${data.todaySales} sales, profit KSh ${"%.0f".format(data.todayProfit)}. ")
                append("Streak: ${data.currentStreak} days. ")
                append("Level: ${data.levelName} ${data.levelEmoji}.")
            }
        }

        return Briefing(
            type = BriefingType.QUICK,
            content = content,
            dialect = data.dialect,
            estimatedDurationSeconds = estimateDuration(content)
        )
    }

    // ═══════════════ PRIVATE HELPERS ═══════════════

    private fun estimateDuration(text: String): Int {
        // Average speaking rate: ~150 words per minute in Swahili
        val wordCount = text.split("\\s+".toRegex()).size
        return ((wordCount / 150.0) * 60).toInt().coerceIn(10, 120)
    }
}

// ═══════════════ DATA CLASSES ═══════════════

/**
 * Briefing types.
 */
enum class BriefingType {
    MORNING, EVENING, QUICK
}

/**
 * A generated briefing.
 */
data class Briefing(
    val type: BriefingType,
    val content: String,
    val dialect: String = "",
    val estimatedDurationSeconds: Int = 30
)

/**
 * Data for morning briefing generation.
 */
data class MorningBriefingData(
    val workerName: String,
    val dialect: String = "",
    val streakDays: Int = 0,
    val yesterdaySales: Int = 0,
    val yesterdayProfit: Double = 0.0,
    val marketTip: String? = null,
    val lessonPreview: String? = null,
    val businessTip: String? = null
)

/**
 * Data for evening briefing generation.
 */
data class EveningBriefingData(
    val workerName: String,
    val dialect: String = "",
    val todaySales: Int = 0,
    val todayRevenue: Double = 0.0,
    val todayExpenses: Double = 0.0,
    val todayProfit: Double = 0.0,
    val averageDailyProfit: Double = 0.0,
    val streakMaintained: Boolean = false,
    val currentStreak: Int = 0,
    val tomorrowTip: String? = null
)

/**
 * Data for quick briefing generation.
 */
data class QuickBriefingData(
    val dialect: String = "",
    val todaySales: Int = 0,
    val todayProfit: Double = 0.0,
    val currentStreak: Int = 0,
    val levelName: String = "",
    val levelEmoji: String = ""
)
