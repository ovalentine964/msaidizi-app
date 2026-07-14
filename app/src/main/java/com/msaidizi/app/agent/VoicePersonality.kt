package com.msaidizi.app.agent

import timber.log.Timber
import java.time.LocalTime
import java.util.concurrent.ThreadLocalRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Voice Personality Engine — makes Msaidizi sound like a warm friend, not a bank SMS.
 *
 * Every response goes through this engine to add:
 * - Swahili proverbs relevant to business context
 * - Time-appropriate cultural greetings
 * - Worker's name for personal touch
 * - Encouragement phrases tailored to response type
 * - Processing feedback phrases ("Sawa, nimesikia...")
 *
 * The key insight: East African informal workers trust people, not systems.
 * Msaidizi must feel like a knowledgeable friend sitting next to them at the market.
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
 * @see Orchestrator for where this is applied
 */
@Singleton
class VoicePersonality @Inject constructor() {

    companion object {
        /** How often to sprinkle proverbs (1 in N responses) */
        private const val PROVERB_FREQUENCY = 4

        /** How often to add encouragement (1 in N responses) */
        private const val ENCOURAGEMENT_FREQUENCY = 3

        /** Maximum extra characters personality adds (for length budgeting) */
        private const val MAX_PERSONALITY_OVERHEAD = 200
    }

    // ═══════════════ SWAHILI PROVERBS DATABASE ═══════════════
    // Business-relevant methali (proverbs) that resonate with informal workers.
    // Each proverb includes the Swahili original, English translation, and
    // the business context where it applies.

    data class Proverb(
        val swahili: String,
        val english: String,
        val context: ProverbContext
    )

    enum class ProverbContext {
        /** General encouragement about effort and persistence */
        EFFORT,
        /** Warning about hasty decisions */
        CAUTION,
        /** Advice about saving and financial discipline */
        SAVINGS,
        /** Encouragement about growth and patience */
        GROWTH,
        /** Warning about debt and borrowing */
        DEBT,
        /** Advice about planning and preparation */
        PLANNING,
        /** Encouragement about consistency */
        CONSISTENCY,
        /** Warning about complacency */
        VIGILANCE,
        /** Advice about diversification */
        DIVERSIFICATION,
        /** Encouragement about resilience */
        RESILIENCE
    }

    private val proverbs = listOf(
        Proverb(
            swahili = "Jitihadi ni muhimu",
            english = "Effort is important",
            context = ProverbContext.EFFORT
        ),
        Proverb(
            swahili = "Maji yakimwagika hayazoleki",
            english = "Spilt water can't be gathered",
            context = ProverbContext.CAUTION
        ),
        Proverb(
            swahili = "Haraka haraka haina baraka",
            english = "Haste has no blessing",
            context = ProverbContext.CAUTION
        ),
        Proverb(
            swahili = "Akiba haiozi",
            english = "Savings never rot",
            context = ProverbContext.SAVINGS
        ),
        Proverb(
            swahili = "Mti haupandi bila maji",
            english = "A tree doesn't grow without water",
            context = ProverbContext.GROWTH
        ),
        Proverb(
            swahili = "Deni ni kama nyoka, ukilibeba unakutaga sumu",
            english = "Debt is like a snake — carry it and it will poison you",
            context = ProverbContext.DEBT
        ),
        Proverb(
            swahili = "Kila ndege huruka na mbawa zake",
            english = "Every bird flies with its own wings",
            context = ProverbContext.PLANNING
        ),
        Proverb(
            swahili = "Siku njema huonekana asubuhi",
            english = "A good day is seen from the morning",
            context = ProverbContext.CONSISTENCY
        ),
        Proverb(
            swahili = "Penye nia pana njia",
            english = "Where there's a will, there's a way",
            context = ProverbContext.RESILIENCE
        ),
        Proverb(
            swahili = "Mchezea mwana kulea, mchezea mwali huteketeza",
            english = "Play with a child and raise them, play with fire and get burned",
            context = ProverbContext.VIGILANCE
        )
    )

    // ═══════════════ CONTEXTUAL GREETINGS ═══════════════
    // Time-of-day greetings that feel natural, not robotic.
    // Includes the worker's name for personalization.

    enum class TimeOfDay {
        EARLY_MORNING,  // 5-8 AM: Market setup time
        MORNING,        // 8-10 AM: Peak morning sales
        MIDDAY,         // 10 AM-2 PM: Midday rush
        AFTERNOON,      // 2-5 PM: Afternoon lull
        EVENING,        // 5-8 PM: Closing time
        NIGHT           // 8 PM-5 AM: Rest time
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
     * Get a contextual greeting that also mentions business context.
     * Used when greeting + business data are available.
     *
     * @param workerName The worker's name
     * @param profit Today's profit (0 if unknown)
     * @param language Language code
     * @return Greeting with business context
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
    // Short phrases to play while Msaidizi is "thinking".
    // Prevents the worker from thinking the app froze.

    /**
     * Get a processing feedback phrase.
     * These are short (1-2 seconds) and reassure the worker that
     * Msaidizi heard them and is working on it.
     *
     * @param language Language code
     * @return A short "thinking" phrase
     */
    fun getProcessingFeedback(language: String = "sw"): String {
        val phrases = if (language == "sw") {
            listOf(
                "Sawa, nimesikia...",
                "Hebu nifikirie...",
                "Ndio, ninafikiria...",
                "Sawa, sekunde moja...",
                "Najielewa, subiri kidogo..."
            )
        } else {
            listOf(
                "Got it, let me think...",
                "Yes, I'm working on it...",
                "One moment...",
                "I hear you, just a sec...",
                "Thinking about it..."
            )
        }
        return phrases[ThreadLocalRandom.current().nextInt(phrases.size)]
    }

    // ═══════════════ ENCOURAGEMENT PHRASES ═══════════════
    // Tailored by response type to add warmth and motivation.

    /**
     * Get an encouragement phrase appropriate for the response type.
     *
     * @param responseType The type of response being generated
     * @param language Language code
     * @return An encouragement phrase, or empty string if not appropriate
     */
    fun getEncouragement(responseType: ResponseType, language: String = "sw"): String {
        // Don't add encouragement to every response — that feels fake
        if (ThreadLocalRandom.current().nextInt(ENCOURAGEMENT_FREQUENCY) != 0) {
            return ""
        }

        return if (language == "sw") {
            when (responseType) {
                ResponseType.CONFIRMATION -> listOf(
                    "Vizuri sana! 👏",
                    "Umefanya vizuri! 🎉",
                    "Hongera! Biashara yako inakua. 📈",
                    "Nimekumbuka! Endelea hivyo. ✅"
                ).random()
                ResponseType.ADVICE -> listOf(
                    "Najua unaweza! 💪",
                    "Kila hatua ni muhimu. 🚶",
                    "Wewe ni mfanyabiashara mzuri! 🌟",
                    "Endelea na jitihadi! 🙌"
                ).random()
                ResponseType.INFORMATION -> listOf(
                    "Ukweli ni nguvu! 📊",
                    "Sasa unajua — tumia hii kwa faida! 💡",
                    "Maarifa ni ufunguo wa biashara! 🔑"
                ).random()
                ResponseType.ERROR -> listOf(
                    "Usijali, tutashinda hii pamoja! 🤝",
                    "Kila mfanyabiashara hukosea — ndio jinsi tunavyojifunza. 📚",
                    "Jaribu tena, mimi niko hapa! 🔄"
                ).random()
                else -> ""
            }
        } else {
            when (responseType) {
                ResponseType.CONFIRMATION -> listOf(
                    "Well done! 👏",
                    "Great job! 🎉",
                    "Your business is growing! 📈",
                    "Noted! Keep going. ✅"
                ).random()
                ResponseType.ADVICE -> listOf(
                    "You've got this! 💪",
                    "Every step counts. 🚶",
                    "You're a great business person! 🌟",
                    "Keep pushing! 🙌"
                ).random()
                ResponseType.INFORMATION -> listOf(
                    "Knowledge is power! 📊",
                    "Now you know — use it for profit! 💡",
                    "Knowledge is the key to business! 🔑"
                ).random()
                ResponseType.ERROR -> listOf(
                    "Don't worry, we'll get through this together! 🤝",
                    "Every business owner makes mistakes — that's how we learn. 📚",
                    "Try again, I'm right here! 🔄"
                ).random()
                else -> ""
            }
        }
    }

    // ═══════════════ PROVERB SELECTION ═══════════════

    /**
     * Get a business-relevant proverb based on context.
     *
     * @param context The business context for proverb selection
     * @param language Language code
     * @return A proverb with translation, or empty string if not appropriate
     */
    fun getProverb(context: ProverbContext, language: String = "sw"): String {
        // Don't add proverb to every response — reserve for meaningful moments
        if (ThreadLocalRandom.current().nextInt(PROVERB_FREQUENCY) != 0) {
            return ""
        }

        val matchingProverbs = proverbs.filter { it.context == context }
        if (matchingProverbs.isEmpty()) return ""

        val proverb = matchingProverbs.random()

        return if (language == "sw") {
            "📖 \"${proverb.swahili}\""
        } else {
            "📖 \"${proverb.swahili}\" — ${proverb.english}"
        }
    }

    /**
     * Get a proverb matching the response type.
     * Maps response types to appropriate proverb contexts.
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
    // The main entry point: wraps any response with warmth.

    /**
     * Wrap a response with personality — the core transformation.
     *
     * Strategy:
     * - Short responses (< 50 chars): Add name + encouragement only (don't overwhelm)
     * - Medium responses (50-200 chars): Add encouragement OR proverb
     * - Long responses (> 200 chars): Add proverb at end (preserves data density)
     *
     * The personality layer is ADDITIVE — it never removes or modifies the
     * original response content. It prepends/append warmth.
     *
     * @param text The original response text
     * @param responseType The type of response
     * @param workerName The worker's name
     * @param language Language code
     * @return The response wrapped with personality
     */
    fun wrapResponse(
        text: String,
        responseType: ResponseType,
        workerName: String = "",
        language: String = "sw"
    ): String {
        if (text.isBlank()) return text

        // Don't wrap error messages with proverbs — that feels dismissive
        if (responseType == ResponseType.ERROR) {
            return wrapErrorResponse(text, workerName, language)
        }

        // Don't wrap clarification requests — they need to be direct
        if (responseType == ResponseType.CLARIFICATION) {
            return text
        }

        val name = if (workerName.isNotBlank()) workerName else if (language == "sw") "Rafiki" else "Friend"

        return when {
            text.length < 50 -> wrapShortResponse(text, responseType, name, language)
            text.length < 200 -> wrapMediumResponse(text, responseType, name, language)
            else -> wrapLongResponse(text, responseType, language)
        }
    }

    /**
     * Wrap a short response with name and encouragement.
     * Short responses are already concise — just add warmth.
     */
    private fun wrapShortResponse(
        text: String,
        responseType: ResponseType,
        name: String,
        language: String
    ): String {
        val encouragement = getEncouragement(responseType, language)
        return if (encouragement.isNotBlank()) {
            "$text\n$encouragement"
        } else {
            text
        }
    }

    /**
     * Wrap a medium response with encouragement or proverb.
     * Medium responses have room for one personality touch.
     */
    private fun wrapMediumResponse(
        text: String,
        responseType: ResponseType,
        name: String,
        language: String
    ): String {
        val encouragement = getEncouragement(responseType, language)
        val proverb = getProverbForResponseType(responseType, language)

        // Choose one: encouragement or proverb (not both — that's too much)
        val addition = when {
            encouragement.isNotBlank() && proverb.isNotBlank() ->
                if (ThreadLocalRandom.current().nextBoolean()) encouragement else proverb
            encouragement.isNotBlank() -> encouragement
            proverb.isNotBlank() -> proverb
            else -> ""
        }

        return if (addition.isNotBlank()) {
            "$text\n\n$addition"
        } else {
            text
        }
    }

    /**
     * Wrap a long response with a proverb at the end.
     * Long responses are data-heavy — add wisdom without cluttering.
     */
    private fun wrapLongResponse(
        text: String,
        responseType: ResponseType,
        language: String
    ): String {
        val proverb = getProverbForResponseType(responseType, language)
        return if (proverb.isNotBlank()) {
            "$text\n\n$proverb"
        } else {
            text
        }
    }

    /**
     * Wrap an error response with empathy, not proverbs.
     * Proverbs during errors feel dismissive — use direct encouragement.
     */
    private fun wrapErrorResponse(
        text: String,
        workerName: String,
        language: String
    ): String {
        val encouragement = if (language == "sw") {
            listOf(
                "Usijali, tutashinda hii pamoja! 🤝",
                "Jaribu tena, mimi niko hapa! 🔄",
                "Kila tatizo na suluhisho lake. 🛠️"
            ).random()
        } else {
            listOf(
                "Don't worry, we'll get through this! 🤝",
                "Try again, I'm right here! 🔄",
                "Every problem has a solution. 🛠️"
            ).random()
        }

        return "$text\n$encouragement"
    }

    // ═══════════════ HELPERS ═══════════════

    /**
     * Determine the current time of day for greeting selection.
     */
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

    /**
     * Get a random proverb for display (e.g., in settings or about screen).
     */
    fun getRandomProverb(language: String = "sw"): String {
        val proverb = proverbs.random()
        return if (language == "sw") {
            "📖 \"${proverb.swahili}\""
        } else {
            "📖 \"${proverb.swahili}\" — ${proverb.english}"
        }
    }

    /**
     * Get all proverbs for educational display.
     */
    fun getAllProverbs(language: String = "sw"): List<Pair<String, String>> {
        return proverbs.map { proverb ->
            if (language == "sw") {
                proverb.swahili to proverb.english
            } else {
                proverb.english to proverb.swahili
            }
        }
    }
}
