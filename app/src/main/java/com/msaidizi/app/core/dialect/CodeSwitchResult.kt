package com.msaidizi.app.core.dialect

/**
 * Result of code-switching detection between a dialect and standard Swahili.
 *
 * Indicates whether the speaker is mixing languages, which language is primary,
 * and provides lists of dialect vs. Swahili words detected.
 */
data class CodeSwitchResult(
    val hasCodeSwitching: Boolean,
    val primaryLanguage: String,
    val dialectWords: List<String>,
    val swahiliWords: List<String>,
    val confidence: Float
)
