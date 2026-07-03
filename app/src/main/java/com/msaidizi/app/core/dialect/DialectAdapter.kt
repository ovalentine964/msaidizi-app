package com.msaidizi.app.core.dialect

import com.msaidizi.app.core.model.DialectRegion
import timber.log.Timber

/**
 * Base class for dialect adapters — data-driven processing pipeline.
 *
 * All dialect adapters follow the same pattern:
 * 1. Detect code-switching between dialect and Swahili
 * 2. Normalize pronunciation variations to standard forms
 * 3. Translate dialect terms to standard Swahili
 * 4. Detect the dialect region
 * 5. Process text through the full pipeline
 *
 * Instead of duplicating this logic across 14 files, each dialect provides
 * a [DialectConfig] with its specific data, and this base class implements
 * the shared pipeline.
 *
 * Usage:
 * ```kotlin
 * object ShengDialectAdapter : DialectAdapter(ShengDialectData.config) {
 *     // No overrides needed — base class handles everything
 * }
 * ```
 *
 * Designed for <1ms latency — pure code, no ML models.
 */
open class DialectAdapter(private val config: DialectConfig) {

    companion object {
        private const val TAG_PREFIX = "Dialect"
    }

    private val tag = "$TAG_PREFIX:${config.name}"

    // ────────────────────── Code-Switching Detection ──────────────────────

    /**
     * Detect code-switching between the dialect and Swahili.
     *
     * Analyzes the input text for dialect markers and Swahili words.
     * Returns a [CodeSwitchResult] indicating whether code-switching was detected,
     * the primary language, and confidence scores.
     */
    fun detectCodeSwitching(text: String): CodeSwitchResult {
        val words = text.lowercase()
            .split(Regex("[^\\p{L}']+"))
            .filter { it.length > 1 }

        if (words.isEmpty()) {
            return CodeSwitchResult(
                hasCodeSwitching = false,
                primaryLanguage = "sw",
                dialectWords = emptyList(),
                swahiliWords = emptyList(),
                confidence = 0.5f
            )
        }

        val dialectFound = mutableListOf<String>()
        val swahiliFound = mutableListOf<String>()

        for (word in words) {
            val clean = word.trim('\'', '"', '.', ',', '!', '?')
            when {
                config.dialectMarkerWords.contains(clean) -> dialectFound.add(clean)
                DialectUtils.isSwahiliWord(clean) -> swahiliFound.add(clean)
                isBusinessTerm(clean) -> swahiliFound.add(clean)
            }
        }

        val totalWords = words.size.coerceAtLeast(1)
        val dialectRatio = dialectFound.size.toFloat() / totalWords
        val hasCodeSwitching = dialectFound.size >= 1 && swahiliFound.size >= 1

        return CodeSwitchResult(
            hasCodeSwitching = hasCodeSwitching,
            primaryLanguage = if (dialectRatio > 0.4f) config.languageCode else "sw",
            dialectWords = dialectFound,
            swahiliWords = swahiliFound,
            confidence = if (hasCodeSwitching) 0.8f else 0.6f
        )
    }

    // ────────────────────── Normalization ──────────────────────

    /**
     * Normalize pronunciation variations to standard forms.
     *
     * Applies all pronunciation regex replacements from the config.
     * Each match is replaced with its standard form from [DialectConfig.pronunciationVariations].
     */
    fun normalize(text: String): String {
        var normalized = text
        for ((key, regex) in config.pronunciationRegexes) {
            normalized = regex.replace(normalized, config.pronunciationVariations[key] ?: key)
        }
        return normalized
    }

    // ────────────────────── Translation ──────────────────────

    /**
     * Translate a dialect term to standard Swahili.
     *
     * Checks business terms first, then pronunciation variations,
     * then the dialect-to-Swahili mapping.
     *
     * @return The standard Swahili term, or null if not found.
     */
    fun translateToStandard(term: String): String? {
        val lower = term.lowercase().trim()

        config.businessTerms[lower]?.let { return it }
        config.pronunciationVariations[lower]?.let { return it }
        config.dialectToSwahili[lower]?.let { return it }

        return null
    }

    // ────────────────────── Region Detection ──────────────────────

    /**
     * Detect the dialect region from input text.
     *
     * Scores the text based on business terms and marker patterns.
     * Returns the configured region if the score exceeds the threshold.
     */
    fun detectRegion(text: String): DialectRegion {
        val lower = text.lowercase()
        var dialectScore = 0

        for (term in config.businessTerms.keys) {
            if (lower.contains(term)) dialectScore += 2
        }
        for ((_, regex) in config.markers) {
            if (regex.containsMatchIn(lower)) dialectScore += 3
        }

        return if (dialectScore > 5) config.region else DialectRegion.STANDARD
    }

    // ────────────────────── Full Processing Pipeline ──────────────────────

    /**
     * Process text through the full dialect pipeline.
     *
     * 1. Detect code-switching
     * 2. Normalize pronunciation
     * 3. Detect region
     * 4. Translate all words
     *
     * @return [ProcessedResult] with all analysis and transformations.
     */
    fun process(text: String): ProcessedResult {
        Timber.tag(tag).d("Processing: '%s'", text)

        val codeSwitch = detectCodeSwitching(text)
        val normalized = normalize(text)
        val region = detectRegion(text)

        val translations = mutableMapOf<String, String>()
        val words = text.lowercase().split(Regex("[^\\p{L}']+"))
        for (word in words) {
            translateToStandard(word.trim())?.let { standard ->
                translations[word.trim()] = standard
            }
        }

        return ProcessedResult(
            originalText = text,
            normalizedText = normalized,
            codeSwitchResult = codeSwitch,
            dialectRegion = region,
            translations = translations,
            confidence = codeSwitch.confidence
        )
    }

    // ────────────────────── Helpers ──────────────────────

    private fun isBusinessTerm(word: String): Boolean {
        return config.businessTerms.containsKey(word) ||
                config.businessTerms.values.any { it == word }
    }
}
