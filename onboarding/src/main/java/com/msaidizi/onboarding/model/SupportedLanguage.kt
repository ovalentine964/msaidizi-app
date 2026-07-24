package com.msaidizi.onboarding.model

/**
 * Supported language definition for onboarding.
 * Maps language codes to display names and voice model requirements.
 */
data class SupportedLanguage(
    val code: String,
    val nativeName: String,
    val englishName: String,
    val sttModel: String,      // Speech-to-text model name
    val ttsModel: String,      // Text-to-speech model name
    val isStable: Boolean,     // Whether voice models are production-ready
    val region: String         // Primary region of use
)

/**
 * Pre-defined supported languages with model requirements.
 */
object SupportedLanguages {
    val ALL = listOf(
        SupportedLanguage("sw", "Kiswahili", "Swahili",
            "whisper-small-sw", "piper-swahili", true, "East Africa"),
        SupportedLanguage("en", "English", "English",
            "whisper-small-en", "piper-english", true, "Global"),
        SupportedLanguage("ki", "Gĩkũyũ", "Kikuyu",
            "whisper-small-sw", "piper-swahili", false, "Kenya"),
        SupportedLanguage("luo", "Dholuo", "Luo",
            "whisper-small-sw", "piper-swahili", false, "Kenya"),
        SupportedLanguage("kln", "Kalenjin", "Kalenjin",
            "whisper-small-sw", "piper-swahili", false, "Kenya"),
        SupportedLanguage("kam", "Kamba", "Kamba",
            "whisper-small-sw", "piper-swahili", false, "Kenya"),
        SupportedLanguage("luy", "Luhya", "Luhya",
            "whisper-small-sw", "piper-swahili", false, "Kenya"),
        SupportedLanguage("so", "Soomaali", "Somali",
            "whisper-small-so", "piper-somali", false, "Somalia/Kenya"),
        SupportedLanguage("lg", "Luganda", "Luganda",
            "whisper-small-sw", "piper-swahili", false, "Uganda"),
        SupportedLanguage("fr", "Français", "French",
            "whisper-small-fr", "piper-french", true, "DRC/Burundi")
    )

    fun getByCode(code: String): SupportedLanguage? {
        return ALL.firstOrNull { it.code == code }
    }

    fun getStableLanguages(): List<SupportedLanguage> {
        return ALL.filter { it.isStable }
    }
}
