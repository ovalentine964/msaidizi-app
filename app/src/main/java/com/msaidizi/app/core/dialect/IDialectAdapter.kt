package com.msaidizi.app.core.dialect

/**
 * Common interface for all dialect adapters.
 *
 * Both data-driven adapters (extending [DialectAdapter]) and legacy
 * standalone adapters implement this interface so they can be used
 * interchangeably by [DialectDetectionEngine].
 */
interface IDialectAdapter {

    /** ISO language code for ASR language hint */
    val asrLanguageHint: String

    /** Language code for TTS engine selection */
    val ttsLanguage: String

    /** Detect code-switching between dialect and Swahili */
    fun detectCodeSwitching(text: String): CodeSwitchResult

    /** Normalize pronunciation variations to standard forms */
    fun normalize(text: String): String

    /** Translate a dialect term to standard Swahili */
    fun translateToStandard(term: String): String?

    /** Detect the dialect region from input text */
    fun detectRegion(text: String): com.msaidizi.app.core.model.DialectRegion

    /** Process text through the full dialect pipeline */
    fun process(text: String): ProcessedResult
}
