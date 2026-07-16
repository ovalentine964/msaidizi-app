package com.msaidizi.app.core.dialect

import com.msaidizi.app.core.model.DialectRegion
import timber.log.Timber

/**
 * Configuration data for a dialect adapter.
 *
 * Each dialect provides its own set of markers, pronunciation variations,
 * business terms, and translation mappings. The base [DialectAdapter] class
 * uses this config to implement the common processing pipeline.
 *
 * This replaces the duplicated code across 14 dialect adapter files with
 * a single data-driven approach.
 */
data class DialectConfig(
    /** Dialect name for logging (e.g., "Sheng", "Amharic", "Dholuo") */
    val name: String,

    /** Language code (e.g., "sw", "am", "yo", "zu") */
    val languageCode: String,

    /** The DialectRegion this adapter handles */
    val region: DialectRegion,

    /**
     * Code-switching markers — words that indicate this dialect is present.
     * Map of marker word → compiled Regex for detection.
     */
    val markers: Map<String, Regex>,

    /**
     * Pronunciation variation patterns.
     * Map of pattern key → Regex for normalization.
     */
    val pronunciationRegexes: Map<String, Regex>,

    /**
     * Pronunciation-to-standard mapping.
     * Map of dialect pronunciation → standard form.
     */
    val pronunciationVariations: Map<String, String>,

    /**
     * Dialect-specific marker words (lowercase).
     * Used for code-switching detection.
     */
    val dialectMarkerWords: Set<String>,

    /**
     * Business vocabulary mapping.
     * Map of dialect term → standard business concept.
     */
    val businessTerms: Map<String, String>,

    /**
     * Dialect-to-standard Swahili translation mapping.
     * Map of dialect term → Swahili equivalent.
     */
    val dialectToSwahili: Map<String, String>,

    /**
     * Code-switching constructions (hybrid words).
     * Map of hybrid construction → standard Swahili translation.
     * e.g., "kudeposit" → "kudepositi", "nimesee" → "nimeona"
     */
    val codeSwitchConstructions: Map<String, String> = emptyMap(),

    /**
     * English nouns with their Swahili noun class agreement.
     * Used for detecting English noun + Swahili possessive patterns.
     * e.g., "account" → "n-class" (account yangu, not account changu)
     */
    val englishNounSwahiliClass: Map<String, String> = emptyMap(),

    /**
     * Regex patterns that indicate code-switching frames.
     * When these match, surrounding words are likely code-switched.
     */
    val codeSwitchFrames: List<Regex> = emptyList()
)
