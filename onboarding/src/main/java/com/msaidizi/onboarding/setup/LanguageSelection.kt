package com.msaidizi.onboarding.setup

import com.msaidizi.core.common.dialect.DialectAdapter
import com.msaidizi.core.common.dialect.DialectProfile

/**
 * Language and dialect selection for onboarding.
 *
 * Supports 15+ African languages/dialects. The selection can happen
 * explicitly (worker chooses) or passively (detected from first
 * voice input during bootstrap).
 *
 * ## Passive Detection (Preferred)
 * During bootstrap, the worker speaks naturally. The system detects:
 * - Language from word patterns
 * - Dialect from Sheng/coastal/regional markers
 * - Code-switching patterns (mixing languages)
 *
 * This is invisible to the worker — no explicit language step needed.
 */
class LanguageSelection {

    /**
     * Get all supported languages for explicit selection.
     * Used when passive detection fails or worker wants to change.
     */
    fun getSupportedLanguages(): List<LanguageOption> {
        return listOf(
            LanguageOption("sw", "Kiswahili", "Swahili"),
            LanguageOption("en", "English", "English"),
            LanguageOption("ki", "Gĩkũyũ", "Kikuyu"),
            LanguageOption("luo", "Dholuo", "Luo"),
            LanguageOption("kln", "Kalenjin", "Kalenjin"),
            LanguageOption("kam", "Kamba", "Kamba"),
            LanguageOption("luy", "Luhya", "Luhya"),
            LanguageOption("mer", "Kimeru", "Meru"),
            LanguageOption("gus", "Ekegusii", "Gusii"),
            LanguageOption("mas", "Maa", "Maasai"),
            LanguageOption("so", "Soomaali", "Somali"),
            LanguageOption("lg", "Luganda", "Luganda"),
            LanguageOption("rn", "Runyankole", "Runyankole"),
            LanguageOption("fr", "Français", "French"),
            LanguageOption("am", "አማርኛ", "Amharic")
        )
    }

    /**
     * Get dialect variants for a language.
     */
    fun getDialects(languageCode: String): List<DialectOption> {
        return when (languageCode) {
            "sw" -> listOf(
                DialectOption("sw", "Kiswahili Sanifu", "Standard Swahili"),
                DialectOption("sw-sheng", "Sheng", "Urban slang-influenced"),
                DialectOption("sw-coast", "Kiswahili cha Pwani", "Coastal Swahili")
            )
            "en" -> listOf(
                DialectOption("en", "Standard English", "Standard"),
                DialectOption("en-ke", "Kenyan English", "Kenyan variant")
            )
            else -> listOf(
                DialectOption(languageCode, "Standard", "Standard dialect")
            )
        }
    }

    /**
     * Detect language from first voice input.
     * Returns the most likely language code.
     */
    fun detectLanguage(text: String): String {
        val lower = text.lowercase()

        // Swahili detection
        val swahiliWords = listOf(
            "nimeuziwa", "nimenunua", "nimetumia", "habari", "sawa",
            "asante", "tafadhali", "karibu", "kwaheri", "leo", "jana",
            "kesho", "sasa", "hapa", "pale", "nini", "ngapi", "gani"
        )
        val swahiliScore = swahiliWords.count { lower.contains(it) }

        // English detection
        val englishWords = listOf(
            "the", "and", "is", "was", "have", "sold", "bought",
            "spent", "today", "yesterday", "how much", "what"
        )
        val englishScore = englishWords.count { lower.contains(it) }

        // Sheng detection
        val shengWords = listOf(
            "sasa", "niaje", "poa", "bwana", "msee", "ndege", "guoko"
        )
        val shengScore = shengWords.count { lower.contains(it) }

        return when {
            shengScore > 0 -> "sw-sheng"
            swahiliScore > englishScore -> "sw"
            englishScore > swahiliScore -> "en"
            else -> "sw" // Default to Swahili
        }
    }

    /**
     * Get greeting for a language and time of day.
     */
    fun getGreeting(languageCode: String, timeOfDay: String): String {
        val profile = DialectAdapter.getProfile(languageCode)
        return DialectAdapter.getGreeting(profile, timeOfDay)
    }
}

/**
 * Language option for UI display.
 */
data class LanguageOption(
    val code: String,
    val nativeName: String,  // "Kiswahili", "English"
    val englishName: String  // "Swahili", "English"
)

/**
 * Dialect option for UI display.
 */
data class DialectOption(
    val code: String,
    val nativeName: String,
    val englishName: String
)
