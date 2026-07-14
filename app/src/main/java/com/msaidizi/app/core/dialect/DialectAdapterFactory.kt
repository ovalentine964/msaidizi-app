package com.msaidizi.app.core.dialect

/**
 * Factory for creating dialect adapters based on language code.
 *
 * Used by OnboardingViewModel and other components to get the
 * appropriate dialect adapter for a given language.
 *
 * Usage:
 * ```kotlin
 * val adapter = DialectAdapterFactory.create("sw")  // Returns KiswahiliDialectAdapter
 * val adapter = DialectAdapterFactory.create("am")  // Returns AmharicDialectAdapter
 * ```
 */
object DialectAdapterFactory {

    /**
     * Create a dialect adapter for the given language code.
     *
     * @param languageCode ISO language code (e.g., "sw", "en", "am", "ha", "yo", "zu")
     * @return The appropriate [DialectAdapter] for the language
     */
    fun create(languageCode: String): IDialectAdapter {
        return when (languageCode.lowercase()) {
            "sw" -> KiswahiliDialectAdapter()
            "sheng" -> ShengDialectAdapter
            "luo", "dholuo" -> DholuoDialectAdapter
            "ki", "kikuyu" -> KikuyuDialectAdapter
            "kln", "kalenjin" -> KalenjinDialectAdapter
            "luy", "luhya" -> LuhyaDialectAdapter
            "mas", "maasai" -> MaasaiDialectAdapter
            "migori" -> MigoriDialectAdapter
            "so", "somali" -> SomaliDialectAdapter
            "am", "amharic" -> AmharicDialectAdapter
            "ha", "hausa" -> HausaDialectAdapter
            "ig", "igbo" -> IgboDialectAdapter
            "yo", "yoruba" -> YorubaDialectAdapter
            "xh", "xhosa" -> XhosaDialectAdapter
            "zu", "zulu" -> ZuluDialectAdapter
            else -> KiswahiliDialectAdapter() // Default to Kiswahili
        }
    }
}
