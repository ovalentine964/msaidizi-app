package com.msaidizi.app.core.dialect

/**
 * Factory for creating dialect adapters based on language code.
 *
 * Uses a registry pattern — each dialect registers its language codes
 * and config. New dialects just need to add an entry to [dialectRegistry].
 *
 * Usage:
 * ```kotlin
 * val adapter = DialectAdapterFactory.create("sw")  // Returns KiswahiliDialectAdapter
 * val adapter = DialectAdapterFactory.create("am")  // Returns AmharicDialectAdapter
 * ```
 */
object DialectAdapterFactory {

    /**
     * Registry of language codes to dialect config providers.
     * Each entry maps one or more language codes to a DialectConfig.
     * The adapter is created lazily from the config.
     */
    private val dialectRegistry: Map<String, () -> DialectConfig> = mapOf(
        // East African Bantu languages
        "sheng" to { ShengDialectData.config },
        "luo" to { DholuoDialectData.config },
        "dholuo" to { DholuoDialectData.config },
        "ki" to { KikuyuDialectData.config },
        "kikuyu" to { KikuyuDialectData.config },
        "kln" to { KalenjinDialectData.config },
        "kalenjin" to { KalenjinDialectData.config },
        "luy" to { LuhyaDialectData.config },
        "luhya" to { LuhyaDialectData.config },
        "mas" to { MaasaiDialectData.config },
        "maasai" to { MaasaiDialectData.config },
        "migori" to { MigoriDialectData.config },

        // Horn of Africa
        "so" to { SomaliDialectData.config },
        "somali" to { SomaliDialectData.config },
        "am" to { AmharicDialectData.config },
        "amharic" to { AmharicDialectData.config },

        // West African
        "ha" to { HausaDialectData.config },
        "hausa" to { HausaDialectData.config },
        "ig" to { IgboDialectData.config },
        "igbo" to { IgboDialectData.config },
        "yo" to { YorubaDialectData.config },
        "yoruba" to { YorubaDialectData.config },

        // Southern African
        "xh" to { XhosaDialectData.config },
        "xhosa" to { XhosaDialectData.config },
        "zu" to { ZuluDialectData.config },
        "zulu" to { ZuluDialectData.config }
    )

    /** Cache for created adapters (configs are singletons, so adapters can be too) */
    private val adapterCache = mutableMapOf<String, IDialectAdapter>()

    /**
     * Create a dialect adapter for the given language code.
     *
     * @param languageCode ISO language code (e.g., "sw", "en", "am", "ha", "yo", "zu")
     * @return The appropriate [IDialectAdapter] for the language
     */
    fun create(languageCode: String): IDialectAdapter {
        val key = languageCode.lowercase()

        // Kiswahili needs special handling (custom class, not data-driven)
        if (key == "sw" || key == "en") {
            return KiswahiliDialectAdapter()
        }

        return adapterCache.getOrPut(key) {
            val configProvider = dialectRegistry[key]
            if (configProvider != null) {
                DialectAdapter(configProvider())
            } else {
                // Default to Kiswahili for unknown languages
                KiswahiliDialectAdapter()
            }
        }
    }

    /**
     * Get all registered language codes.
     */
    fun getSupportedLanguages(): Set<String> {
        return dialectRegistry.keys + setOf("sw", "en")
    }

    /**
     * Check if a language code is supported.
     */
    fun isSupported(languageCode: String): Boolean {
        val key = languageCode.lowercase()
        return key == "sw" || key == "en" || dialectRegistry.containsKey(key)
    }
}
