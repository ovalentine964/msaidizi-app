package com.msaidizi.core.common.dialect

/**
 * Dialect adapter for African languages.
 * Maps language/dialect codes to localized responses, number formats,
 * and cultural patterns.
 *
 * Supports 15+ African languages/dialects used in East Africa:
 * - Swahili (standard, coastal, Sheng-influenced)
 * - English (Kenyan, Ugandan)
 * - Kikuyu, Luo, Kalenjin, Kamba, Luhya, Meru
 * - Somali, Oromo, Amharic
 * - Luganda, Runyankole
 * - French (DRC, Burundi)
 */
data class DialectProfile(
    /** ISO 639-1 language code — "sw", "en", "ki", "luo" */
    val languageCode: String,
    /** Human-readable name — "Swahili", "English", "Kikuyu" */
    val displayName: String,
    /** Specific dialect — "coastal", "sheng-influenced", "standard" */
    val dialect: String = "standard",
    /** Whether this dialect uses Sheng (urban slang) */
    val usesSheng: Boolean = false,
    /** Number system style — "swahili", "english", "local" */
    val numberStyle: String = "swahili",
    /** Greeting style — "formal", "casual", "respectful" */
    val greetingStyle: String = "casual",
    /** Whether to use proverbs in responses */
    val useProverbs: Boolean = true,
    /** Whether to include English code-switching */
    val codeSwitchesEnglish: Boolean = false
)

/**
 * Dialect adapter — provides localized responses for different
 * African languages and dialects.
 */
object DialectAdapter {

    /**
     * Supported dialect profiles.
     */
    val PROFILES = mapOf(
        // Swahili variants
        "sw" to DialectProfile("sw", "Swahili", "standard", numberStyle = "swahili"),
        "sw-sheng" to DialectProfile("sw", "Swahili (Sheng)", "sheng-influenced",
            usesSheng = true, codeSwitchesEnglish = true),
        "sw-coast" to DialectProfile("sw", "Swahili (Coastal)", "coastal",
            greetingStyle = "respectful", useProverbs = true),

        // English variants
        "en" to DialectProfile("en", "English", "standard", numberStyle = "english"),
        "en-ke" to DialectProfile("en", "English (Kenyan)", "kenyan",
            codeSwitchesEnglish = true),

        // Bantu languages
        "ki" to DialectProfile("ki", "Kikuyu", numberStyle = "swahili",
            greetingStyle = "respectful"),
        "kam" to DialectProfile("kam", "Kamba", numberStyle = "swahili"),
        "luo" to DialectProfile("luo", "Luo", numberStyle = "swahili"),
        "luy" to DialectProfile("luy", "Luhya", numberStyle = "swahili"),
        "mer" to DialectProfile("mer", "Meru", numberStyle = "swahili"),
        "kln" to DialectProfile("kln", "Kalenjin", numberStyle = "swahili"),
        "gus" to DialectProfile("gus", "Gusii", numberStyle = "swahili"),

        // Nilotic languages
        "mas" to DialectProfile("mas", "Maasai", numberStyle = "swahili"),
        "teo" to DialectProfile("teo", "Teso", numberStyle = "swahili"),

        // Cushitic languages
        "so" to DialectProfile("so", "Somali", numberStyle = "english",
            greetingStyle = "respectful"),
        "om" to DialectProfile("om", "Oromo", numberStyle = "english"),

        // Great Lakes Bantu
        "lg" to DialectProfile("lg", "Luganda", numberStyle = "swahili"),
        "rn" to DialectProfile("rn", "Runyankole", numberStyle = "swahili"),

        // French
        "fr" to DialectProfile("fr", "French", numberStyle = "english"),

        // Amharic
        "am" to DialectProfile("am", "Amharic", numberStyle = "english")
    )

    /**
     * Get dialect profile by code.
     */
    fun getProfile(code: String): DialectProfile {
        return PROFILES[code] ?: PROFILES["sw"]!!
    }

    /**
     * Get greeting for a dialect.
     */
    fun getGreeting(profile: DialectProfile, timeOfDay: String): String {
        return when (profile.languageCode) {
            "sw" -> when (timeOfDay) {
                "morning" -> "Habari za asubuhi"
                "afternoon" -> "Habari za mchana"
                "evening" -> "Habari za jioni"
                else -> "Habari"
            }
            "en" -> when (timeOfDay) {
                "morning" -> "Good morning"
                "afternoon" -> "Good afternoon"
                "evening" -> "Good evening"
                else -> "Hello"
            }
            "ki" -> when (timeOfDay) {
                "morning" -> "Wĩ mwĩega w mũthĩ"
                else -> "Wĩ mwĩega"
            }
            "luo" -> when (timeOfDay) {
                "morning" -> "Oyawore"
                else -> "Misawa"
            }
            "so" -> when (timeOfDay) {
                "morning" -> "Subax wanaagsan"
                else -> "Iska warran"
            }
            else -> when (timeOfDay) {
                "morning" -> "Habari za asubuhi"
                else -> "Habari"
            }
        }
    }

    /**
     * Get confirmation phrase for a dialect.
     * "Sawa" (Swahili), "Okay" (English), "Ĩĩ" (Kikuyu)
     */
    fun getConfirmation(profile: DialectProfile): String {
        return when (profile.languageCode) {
            "sw" -> "Sawa"
            "en" -> "Okay"
            "ki" -> "Ĩĩ"
            "luo" -> "Ee"
            "so" -> "Haa"
            "fr" -> "D'accord"
            else -> "Sawa"
        }
    }

    /**
     * Get error/clarification phrase.
     * "Samahani, sijasikia vizuri" (Swahili)
     */
    fun getClarificationPrompt(profile: DialectProfile): String {
        return when (profile.languageCode) {
            "sw" -> "Samahani, sijasikia vizuri. Tafadhali rudia."
            "en" -> "Sorry, I didn't catch that. Please repeat."
            "ki" -> "Ndagũkũria, ndĩrahũta.ũngĩũria."
            "luo" -> "Akwayu, ok iwuoyo maber. Erokamano idwogo."
            "so" -> "Waan ka cudur, ma dhahin. Fadlan ku celceli."
            else -> "Samahani, sijasikia vizuri. Tafadhali rudia."
        }
    }

    /**
     * Get transaction confirmation for a dialect.
     * "Umeuza X kwa Y" style.
     */
    fun getTransactionConfirmation(
        profile: DialectProfile,
        type: String,
        item: String,
        quantity: Double,
        amount: Double
    ): String {
        val qty = quantity.toInt()
        return when (profile.languageCode) {
            "sw" -> when (type) {
                "SALE" -> "Umeuza $item $qty kwa KSh $amount"
                "PURCHASE" -> "Umenunua $item $qty kwa KSh $amount"
                "EXPENSE" -> "Umetumia KSh $amount kwa $item"
                else -> "Umerekodi $item $qty kwa KSh $amount"
            }
            "en" -> when (type) {
                "SALE" -> "You sold $qty $item for KSh $amount"
                "PURCHASE" -> "You bought $qty $item for KSh $amount"
                "EXPENSE" -> "You spent KSh $amount on $item"
                else -> "Recorded $qty $item for KSh $amount"
            }
            else -> "Umerekodi $item $qty kwa KSh $amount"
        }
    }

    /**
     * Detect dialect from text patterns.
     * Returns the most likely dialect profile code.
     */
    fun detectDialect(text: String): String {
        val lower = text.lowercase()

        // Sheng detection
        val shengWords = listOf(
            "sasa", "niaje", "poa", "bwana", "msee", "mse",
            "ndege", "guoko", "munde", "vile", "mbogi"
        )
        if (shengWords.any { lower.contains(it) }) return "sw-sheng"

        // Coastal Swahili detection
        val coastalWords = listOf("bwana", "habari yako", "mashallah", "inshallah")
        if (coastalWords.any { lower.contains(it) }) return "sw-coast"

        // English detection
        val englishWords = listOf("the", "and", "is", "was", "have", "sold", "bought")
        if (englishWords.count { lower.contains(it) } >= 2) return "en-ke"

        // Default to standard Swahili
        return "sw"
    }
}
