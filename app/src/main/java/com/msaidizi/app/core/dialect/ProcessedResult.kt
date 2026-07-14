package com.msaidizi.app.core.dialect

import com.msaidizi.app.core.model.DialectRegion

/**
 * Result of processing text through the dialect pipeline.
 *
 * Contains the original text, normalized form, code-switching analysis,
 * detected dialect region, and any translations applied.
 */
data class ProcessedResult(
    val originalText: String,
    val normalizedText: String,
    val codeSwitchResult: CodeSwitchResult,
    val dialectRegion: DialectRegion?,
    val translations: Map<String, String>,
    val confidence: Float
)
