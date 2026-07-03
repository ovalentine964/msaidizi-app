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
    val dialectToSwahili: Map<String, String>
)
