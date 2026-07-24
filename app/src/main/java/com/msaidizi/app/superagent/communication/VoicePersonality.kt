package com.msaidizi.app.superagent.communication

import timber.log.Timber
import java.time.LocalTime
import java.util.concurrent.ThreadLocalRandom

/**
 * Voice Personality — makes Msaidizi sound like a warm friend, not a bank SMS.
 *
 * ## Cultural Design Principles
 *
 * ### Ubuntu Philosophy
 * "I am because we are" — responses acknowledge the worker's community context,
 * not just their individual transactions.
 *
 * ### Oral Tradition
 * Swahili culture values proverbs (methali) as condensed wisdom.
 * A well-placed proverb builds trust faster than a spreadsheet.
 *
 * ### Respect Hierarchy
 * Greetings are not optional in East Africa — they're the foundation of trust.
 * Every conversation must begin with a culturally appropriate greeting.
 *
 * ## Features
 * - Time-appropriate cultural greetings
 * - Swahili proverbs relevant to business context
 * - Worker's name for personal touch
 * - Encouragement phrases tailored to response type
 * - Processing feedback phrases ("Sawa, nimesikia...")
 *
 * @see CommunicationModule for how this integrates with the superagent
 */
class VoicePersonality {

    companion object {
        /** How often to sprinkle proverbs (1 in N responses) */
        private const val PROVERB_FREQUENCY = 4

        /** How often to add encouragement (1 in N responses) */
        private const val ENCOURAGEMENT_FREQUENCY = 3
    }

    // ═══════════════ SWAHILI PROVERBS DATABASE ═══════════════

    data class Proverb(
        val swahili: String,
        val english: String,
        val context: ProverbContext
    )

    enum class ProverbContext {
        EFFORT, CAUTION, SAVINGS, GROWTH, DEBT, PLANNING,
        CONSISTENCY, VIGILANCE, DIVERSIFICATION, RESILIENCE
    }

    private val proverbs = listOf(
        Proverb("Jitihadi ni muhimu", "Effort is important", ProverbContext.EFFORT),
        Proverb("Maji yakimwagika hayazoleki", "Spilt water can't be gathered", ProverbContext.CAUTION),
        Proverb("Haraka haraka haina baraka", "Haste has no blessing", ProverbContext.CAUTION),
        Proverb("Akiba haiozi", "Savings never rot", ProverbContext.SAVINGS),
        Proverb("Mti haupandi bila maji", "A tree doesn't grow without water", ProverbContext.GROWTH),
        Proverb("Deni ni kama nyoka, ukilibeba unakutaga sumu", "Debt is like a snake — carry it and it will poison you", ProverbContext.DEBT),
        Proverb("Kila ndege huruka na mbawa zake", "Every bird flies with its own wings", ProverbContext.PLANNING),
        Proverb("Siku njema huonekana asubuhi", "A good day is seen from the morning", ProverbContext.CONSISTENCY),
        Proverb("Penye nia pana njia", "Where there's a will, there's a way", ProverbContext.RESILIENCE),
        Proverb("Mchezea mwana kulea, mchezea mwali huteketeza", "Play with a child and raise them, play with fire and get burned", ProverbContext.VIGILANCE)
    )

    // ═══════════════ CONTEXTUAL GREETINGS ═══════════════

    enum class TimeOfDay {
        EARLY_MORNING, MORNING, MIDDAY, AFTERNOON, EVENING, NIGHT
    }

    /**
     * Get a culturally appropriate greeting based on time of day.
     *
     * @param workerName The worker's name (empty string if unknown)
     * @param language Language code ("sw" or "en")
     * @return A warm, time-appropriate greeting
     */
    fun getGreeting(workerName: String, language: String = "sw"): String {
        val timeOfDay = getTimeOfDay()
        val name = if (workerName.isNotBlank()) " $workerName" else ""

        return if (language == "sw") {
            when (timeOfDay) {
                TimeOfDay.EARLY_MORNING -> "Habari za asubuhi$name! Umepumzika vizuri? Leo ni siku mpya ya biashara! 🌅"
                TimeOfDay.MORNING -> "Habari$name! Soko linaanza vizuri? Niko hapa kukusaidia leo. ☀️"
                TimeOfDay.MIDDAY -> "Habari ya mchana$name! Umefanya mauzo gani mpaka sasa? 🌞"
                TimeOfDay.AFTERNOON -> "Habari$name! Soko bado linaendelea? Niko hapa. 🌤️"
                TimeOfDay.EVENING -> "Habari za jioni$name! Umefanya kazi nzuri leo. Hebu tuangalie faida yako. 🌆"
                TimeOfDay.NIGHT -> "Habari$name! Bado unafanya kazi? Kumbuka kupumzika. Mimi niko hapa ukirudi. 🌙"
            }
        } else {
            when (timeOfDay) {
                TimeOfDay.EARLY_MORNING -> "Good morning$name! Did you rest well? A new business day awaits! 🌅"
                TimeOfDay.MORNING -> "Hey$name! Market starting well? I'm here to help today. ☀️"
                TimeOfDay.MIDDAY -> "Good afternoon$name! What sales have you made so far? 🌞"
                TimeOfDay.AFTERNOON -> "Hey$name! Market still going? I'm right here. 🌤️"
                TimeOfDay.EVENING -> "Good evening$name! You did great work today. Let's check your profit. 🌆"
                TimeOfDay.NIGHT -> "Hey$name! Still working? Remember to rest. I'll be here when you're back. 🌙"
            }
        }
    }

    /**
     * Get a greeting with business context.
     */
    fun getGreetingWithContext(workerName: String, profit: Double, language: String = "sw"): String {
        val baseGreeting = getGreeting(workerName, language)
        val name = if (workerName.isNotBlank()) workerName else if (language == "sw") "Rafiki" else "Friend"

        return if (language == "sw") {
            when {
                profit > 0 -> "$baseGreeting\n\nFaida yako leo ni KSh ${"%.0f".format(profit)}. Vizuri, $name! 💪"
                profit < 0 -> "$baseGreeting\n\nLeo bado ni mapema. Faida itakuja! Nenda polepole. 🤝"
                else -> baseGreeting
            }
        } else {
            when {
                profit > 0 -> "$baseGreeting\n\nYour profit today is KSh ${"%.0f".format(profit)}. Great job, $name! 💪"
                profit < 0 -> "$baseGreeting\n\nIt's still early. Profit will come! Take it step by step. 🤝"
                else -> baseGreeting
            }
        }
    }

    // ═══════════════ PROCESSING FEEDBACK ═══════════════

    /**
     * Get a processing feedback phrase (while "thinking").
     */
    fun getProcessingFeedback(language: String = "sw"): String {
        val phrases = if (language == "sw") {
            listOf("Sawa, nimesikia...", "Hebu nifikirie...", "Ndio, ninafikiria...", "Sawa, sekunde moja...", "Najielewa, subiri kidogo...")
        } else {
            listOf("Got it, let me think...", "Yes, I'm working on it...", "One moment...", "I hear you, just a sec...", "Thinking about it...")
        }
        return phrases[ThreadLocalRandom.current().nextInt(phrases.size)]
    }

    // ═══════════════ ENCOURAGEMENT PHRASES ═══════════════

    /**
     * Get an encouragement phrase appropriate for the response type.
     */
    fun getEncouragement(responseType: ResponseType, language: String = "sw"): String {
        if (ThreadLocalRandom.current().nextInt(ENCOURAGEMENT_FREQUENCY) != 0) return ""

        return if (language == "sw") {
            when (responseType) {
                ResponseType.CONFIRMATION -> listOf("Vizuri sana! 👏", "Umefanya vizuri! 🎉", "Hongera! Biashara yako inakua. 📈").random()
                ResponseType.ADVICE -> listOf("Najua unaweza! 💪", "Kila hatua ni muhimu. 🚶", "Wewe ni mfanyabiashara mzuri! 🌟").random()
                ResponseType.INFORMATION -> listOf("Ukweli ni nguvu! 📊", "Sasa unajua — tumia hii kwa faida! 💡").random()
                ResponseType.ERROR -> listOf("Usijali, tutashinda hii pamoja! 🤝", "Jaribu tena, mimi niko hapa! 🔄").random()
                else -> ""
            }
        } else {
            when (responseType) {
                ResponseType.CONFIRMATION -> listOf("Well done! 👏", "Great job! 🎉", "Your business is growing! 📈").random()
                ResponseType.ADVICE -> listOf("You've got this! 💪", "Every step counts. 🚶", "You're a great business person! 🌟").random()
                ResponseType.INFORMATION -> listOf("Knowledge is power! 📊", "Now you know — use it for profit! 💡").random()
                ResponseType.ERROR -> listOf("Don't worry, we'll get through this together! 🤝", "Try again, I'm right here! 🔄").random()
                else -> ""
            }
        }
    }

    // ═══════════════ PROVERB SELECTION ═══════════════

    /**
     * Get a business-relevant proverb based on context.
     */
    fun getProverb(context: ProverbContext, language: String = "sw"): String {
        if (ThreadLocalRandom.current().nextInt(PROVERB_FREQUENCY) != 0) return ""

        val matchingProverbs = proverbs.filter { it.context == context }
        if (matchingProverbs.isEmpty()) return ""

        val proverb = matchingProverbs.random()
        return if (language == "sw") "📖 \"${proverb.swahili}\""
        else "📖 \"${proverb.swahili}\" — ${proverb.english}"
    }

    /**
     * Get a proverb matching the response type.
     */
    fun getProverbForResponseType(responseType: ResponseType, language: String = "sw"): String {
        val context = when (responseType) {
            ResponseType.CONFIRMATION -> ProverbContext.EFFORT
            ResponseType.ADVICE -> ProverbContext.PLANNING
            ResponseType.INFORMATION -> ProverbContext.CONSISTENCY
            ResponseType.ERROR -> ProverbContext.RESILIENCE
            ResponseType.GREETING -> ProverbContext.GROWTH
            else -> ProverbContext.EFFORT
        }
        return getProverb(context, language)
    }

    // ═══════════════ RESPONSE WRAPPING ═══════════════

    /**
     * Wrap a response with personality — the core transformation.
     *
     * Strategy:
     * - Short responses (< 50 chars): Add encouragement only
     * - Medium responses (50-200 chars): Add encouragement OR proverb
     * - Long responses (> 200 chars): Add proverb at end
     */
    fun wrapResponse(
        text: String,
        responseType: ResponseType,
        workerName: String = "",
        language: String = "sw"
    ): String {
        if (text.isBlank()) return text

        if (responseType == ResponseType.ERROR) return wrapErrorResponse(text, language)
        if (responseType == ResponseType.CLARIFICATION) return text

        return when {
            text.length < 50 -> wrapShortResponse(text, responseType, language)
            text.length < 200 -> wrapMediumResponse(text, responseType, language)
            else -> wrapLongResponse(text, responseType, language)
        }
    }

    // ═══════════════ PRIVATE HELPERS ═══════════════

    private fun wrapShortResponse(text: String, responseType: ResponseType, language: String): String {
        val encouragement = getEncouragement(responseType, language)
        return if (encouragement.isNotBlank()) "$text\n$encouragement" else text
    }

    private fun wrapMediumResponse(text: String, responseType: ResponseType, language: String): String {
        val encouragement = getEncouragement(responseType, language)
        val proverb = getProverbForResponseType(responseType, language)

        val addition = when {
            encouragement.isNotBlank() && proverb.isNotBlank() ->
                if (ThreadLocalRandom.current().nextBoolean()) encouragement else proverb
            encouragement.isNotBlank() -> encouragement
            proverb.isNotBlank() -> proverb
            else -> ""
        }

        return if (addition.isNotBlank()) "$text\n\n$addition" else text
    }

    private fun wrapLongResponse(text: String, responseType: ResponseType, language: String): String {
        val proverb = getProverbForResponseType(responseType, language)
        return if (proverb.isNotBlank()) "$text\n\n$proverb" else text
    }

    private fun wrapErrorResponse(text: String, language: String): String {
        val encouragement = if (language == "sw") {
            listOf("Usijali, tutashinda hii pamoja! 🤝", "Jaribu tena, mimi niko hapa! 🔄", "Kila tatizo na suluhisho lake. 🛠️").random()
        } else {
            listOf("Don't worry, we'll get through this! 🤝", "Try again, I'm right here! 🔄", "Every problem has a solution. 🛠️").random()
        }
        return "$text\n$encouragement"
    }

    private fun getTimeOfDay(): TimeOfDay {
        val hour = LocalTime.now().hour
        return when {
            hour in 5..7 -> TimeOfDay.EARLY_MORNING
            hour in 8..9 -> TimeOfDay.MORNING
            hour in 10..13 -> TimeOfDay.MIDDAY
            hour in 14..16 -> TimeOfDay.AFTERNOON
            hour in 17..19 -> TimeOfDay.EVENING
            else -> TimeOfDay.NIGHT
        }
    }
}

/**
 * Response types for personality wrapping.
 */
enum class ResponseType {
    CONFIRMATION, ADVICE, INFORMATION, ERROR, GREETING, CLARIFICATION
}
