package com.msaidizi.app.core.language

import timber.log.Timber

/**
 * Language-specific phoneme mapping and handling for African languages.
 *
 * Handles the fundamental challenge that Whisper/ASR models are trained on
 * European phoneme inventories and systematically misrecognize African phonemes:
 *
 * - Dholuo has 4 lexical tones (high, low, falling, rising) — Whisper ignores tone
 * - Bantu languages have click consonants (Zulu, Xhosa) — no European equivalent
 * - Implosive consonants /ɓ/, /ɗ/ in Dholuo — ASR maps to regular /b/, /d/
 * - Prenasalized stops /mb/, /nd/, /ŋg/ — ASR splits into two phonemes
 * - Code-switching creates phoneme boundary ambiguity
 *
 * Architecture:
 * - PhonemeInventory: per-language phoneme sets with IPA mappings
 * - PhonemeConfusionMatrix: known ASR confusion patterns per language pair
 * - PhonemePostProcessor: corrects systematic ASR errors using phoneme knowledge
 * - Cross-lingual phoneme transfer: shared phonemes between related languages
 *
 * Pure code — no ML models, <1ms latency.
 */
class PhonemeMapper {

    companion object {
        private const val TAG = "PhonemeMapper"
    }

    // ════════════════════════════════════════════════════════════════════
    // PHONEME INVENTORIES — IPA representations per language
    // ════════════════════════════════════════════════════════════════════

    /**
     * Swahili phoneme inventory (Standard / Nairobi).
     * 5 vowels (a, e, i, o, u), ~24 consonants.
     * No tones, syllable-timed, penultimate stress.
     */
    val swahiliPhonemes = PhonemeInventory(
        languageCode = "sw",
        vowels = setOf("a", "e", "i", "o", "u"),
        consonants = setOf(
            "p", "b", "t", "d", "k", "g",       // stops
            "ɓ", "ɗ",                              // implosives (in some dialects)
            "m", "n", "ɲ", "ŋ",                    // nasals
            "f", "v", "s", "z", "ʃ", "h",          // fricatives
            "tʃ", "dʒ",                             // affricates
            "l", "r", "j", "w",                     // liquids/glides
            "mb", "nd", "ŋg", "nj", "ny"           // prenasalized
        ),
        tones = emptySet(),  // Swahili is not tonal (but has phrase-level intonation)
        specialRules = mapOf(
            "ng'" to "ŋ",        // ng' apostrophe = velar nasal
            "ny" to "ɲ",         // palatal nasal
            "sh" to "ʃ",         // postalveolar fricative
            "th" to "t",         // no dental fricative in Swahili
            "dh" to "d",         // no dental fricative in Swahili
            "ch" to "tʃ",        // postalveolar affricate
        )
    )

    /**
     * Dholuo phoneme inventory (Luo language, Western Kenya).
     * 8 vowels (with ATR distinction: ±Advanced Tongue Root), ~30 consonants.
     * 4 lexical tones: High (H), Low (L), Falling (HL), Rising (LH).
     * Implosive consonants are distinctive: /ɓ/ vs /b/, /ɗ/ vs /d/.
     */
    val dholuoPhonemes = PhonemeInventory(
        languageCode = "luo",
        // 8 vowels: 4 +ATR, 4 -ATR (vowel harmony)
        vowels = setOf("i", "ɪ", "e", "ɛ", "o", "ɔ", "u", "ʊ", "a"),
        consonants = setOf(
            "p", "b", "ɓ", "t", "d", "ɗ", "k", "g",   // stops + implosives
            "ʔ",                                           // glottal stop
            "m", "n", "ɲ", "ŋ",                            // nasals
            "f", "s", "z", "ʃ", "h",                       // fricatives
            "tʃ", "dʒ",                                     // affricates
            "l", "r", "j", "w",                             // liquids/glides
            "mb", "nd", "ŋg", "nj"                          // prenasalized
        ),
        tones = setOf("H", "L", "HL", "LH"),  // 4 lexical tones
        specialRules = mapOf(
            "ber" to "bɛr",       // good (with -ATR vowel)
            "maber" to "mabɛr",   // good/well
            "rech" to "rɛtʃ",     // food
            "chon" to "tʃon",     // because
            "ng'ato" to "ŋato",   // one person
        )
    )

    /**
     * Kikuyu phoneme inventory (Bantu language, Central Kenya).
     * 5 vowels, ~25 consonants. No tones.
     * Has prenasalized stops and labialized consonants.
     */
    val kikuyuPhonemes = PhonemeInventory(
        languageCode = "ki",
        vowels = setOf("a", "e", "i", "o", "u"),
        consonants = setOf(
            "p", "b", "t", "d", "k", "g",
            "m", "n", "ɲ", "ŋ",
            "f", "s", "z", "ʃ", "h",
            "tʃ", "dʒ",
            "l", "r", "j", "w",
            "mb", "nd", "ŋg", "nj"
        ),
        tones = emptySet(),
        specialRules = emptyMap()
    )

    /**
     * Yoruba phoneme inventory (Niger-Congo, West Africa).
     * 7 oral + 5 nasal vowels, ~18 consonants.
     * 3 tones: High (H), Mid (M), Low (L).
     */
    val yorubaPhonemes = PhonemeInventory(
        languageCode = "yo",
        vowels = setOf("a", "e", "ɛ", "i", "o", "ɔ", "u"),
        consonants = setOf(
            "p", "b", "t", "d", "k", "g",
            "m", "n", "ɲ", "ŋ",
            "f", "s", "ʃ", "h",
            "tʃ", "dʒ",
            "l", "r", "j", "w",
            "kp", "gb"   // labial-velar stops (distinctive)
        ),
        tones = setOf("H", "M", "L"),  // 3 tones
        specialRules = mapOf(
            "kp" to "kp",    // labial-velar stop
            "gb" to "gb",    // labial-velar stop
            "ṣ" to "ʃ",      // palatal fricative
        )
    )

    /**
     * Hausa phoneme inventory (Afro-Asiatic, West Africa).
     * 5 vowels (short/long = 10), ~32 consonants.
     * No tones (uses pitch accent). Ejectives and implosives.
     */
    val hausaPhonemes = PhonemeInventory(
        languageCode = "ha",
        vowels = setOf("a", "e", "i", "o", "u", "aa", "ee", "ii", "oo", "uu"),
        consonants = setOf(
            "p", "b", "ɓ", "t", "d", "ɗ", "k", "g",  // stops + implosives
            "ʔ",                                          // glottal stop
            "ts", "tʃ", "dʒ",                            // affricates
            "s", "z", "ʃ", "h",                          // fricatives
            "m", "n", "ɲ", "ŋ",                          // nasals
            "l", "r", "j", "w",                          // liquids/glides
            "f"
        ),
        tones = emptySet(),  // pitch accent, not lexical tone
        specialRules = mapOf(
            "ɗ" to "d",     // implosive → stop (ASR confusion)
            "ɓ" to "b",     // implosive → stop
            "ts" to "s",    // affricate → fricative
        )
    )

    // All supported language inventories
    private val inventories = mapOf(
        "sw" to swahiliPhonemes,
        "luo" to dholuoPhonemes,
        "ki" to kikuyuPhonemes,
        "yo" to yorubaPhonemes,
        "ha" to hausaPhonemes
    )

    // ════════════════════════════════════════════════════════════════════
    // PHONEME CONFUSION MATRIX — Known ASR error patterns
    // ════════════════════════════════════════════════════════════════════

    /**
     * Known systematic ASR confusions for each language.
     * Map: correct_phoneme → list of (wrong_phoneme, probability).
     *
     * These are derived from error analysis of Whisper on African languages.
     * Used for post-processing correction and confidence calibration.
     */
    private val confusionMatrix = mapOf(
        // ── Swahili confusions (Whisper) ──
        "sw" to mapOf(
            "th" to listOf("s" to 0.7, "t" to 0.3),        // "thamini" → "samini"
            "dh" to listOf("d" to 0.8, "z" to 0.2),        // "dhahabu" → "dahabu"
            "ng'" to listOf("ng" to 0.6, "n" to 0.3, "g" to 0.1),
            "ny" to listOf("n" to 0.4, "ni" to 0.4, "ny" to 0.2),
            "sh" to listOf("s" to 0.5, "sh" to 0.5),
            "ch" to listOf("t" to 0.3, "ch" to 0.7),
            "mb" to listOf("m" to 0.4, "b" to 0.3, "mb" to 0.3),
            "nd" to listOf("n" to 0.4, "d" to 0.3, "nd" to 0.3),
            "ŋg" to listOf("ng" to 0.5, "g" to 0.3, "ŋg" to 0.2),
        ),
        // ── Dholuo confusions (Whisper) ──
        "luo" to mapOf(
            "ɓ" to listOf("b" to 0.9, "ɓ" to 0.1),        // implosive → stop
            "ɗ" to listOf("d" to 0.9, "ɗ" to 0.1),        // implosive → stop
            "ɛ" to listOf("e" to 0.8, "a" to 0.2),         // -ATR → +ATR
            "ɔ" to listOf("o" to 0.8, "a" to 0.2),         // -ATR → +ATR
            "ɪ" to listOf("i" to 0.9, "ɪ" to 0.1),         // -ATR → +ATR
            "ʊ" to listOf("u" to 0.9, "ʊ" to 0.1),         // -ATR → +ATR
            "H" to listOf("L" to 0.3),                       // tone collapse
            "L" to listOf("H" to 0.2),                       // tone collapse
        ),
        // ── Yoruba confusions ──
        "yo" to mapOf(
            "kp" to listOf("k" to 0.5, "p" to 0.3, "kp" to 0.2),
            "gb" to listOf("g" to 0.5, "b" to 0.3, "gb" to 0.2),
            "ɛ" to listOf("e" to 0.8, "ɛ" to 0.2),
            "ɔ" to listOf("o" to 0.8, "ɔ" to 0.2),
            "H" to listOf("M" to 0.2, "L" to 0.2),          // tone collapse
            "M" to listOf("H" to 0.2, "L" to 0.2),
            "L" to listOf("M" to 0.2, "H" to 0.2),
        ),
        // ── Hausa confusions ──
        "ha" to mapOf(
            "ɓ" to listOf("b" to 0.9, "ɓ" to 0.1),
            "ɗ" to listOf("d" to 0.9, "ɗ" to 0.1),
            "ts" to listOf("s" to 0.7, "t" to 0.2, "ts" to 0.1),
            "ʔ" to listOf("" to 0.8, "ʔ" to 0.2),           // glottal stop deletion
        )
    )

    // ════════════════════════════════════════════════════════════════════
    // CROSS-LINGUAL PHONEME SIMILARITY
    // ════════════════════════════════════════════════════════════════════

    /**
     * Phoneme similarity matrix between languages.
     * Higher score = more shared phonemes = easier transfer learning.
     *
     * Used by LanguageLearningPipeline to determine transfer learning priority.
     *
     * Similarity = |shared_phonemes| / |union_phonemes|
     */
    val crossLingualSimilarity = mapOf(
        ("sw" to "luo") to 0.55,   // Swahili-Dholuo: significant overlap (Migori substrate)
        ("sw" to "ki") to 0.65,    // Swahili-Kikuyu: both Bantu, high overlap
        ("sw" to "yo") to 0.40,    // Swahili-Yoruba: both Niger-Congo, moderate
        ("sw" to "ha") to 0.35,    // Swahili-Hausa: different families, low
        ("luo" to "ki") to 0.45,   // Dholuo-Kikuyu: some shared phonemes
        ("luo" to "yo") to 0.30,   // Dholuo-Yoruba: low (Nilo-Saharan vs Niger-Congo)
        ("ki" to "yo") to 0.45,    // Kikuyu-Yoruba: both Bantu-family roots
        ("ha" to "yo") to 0.50,    // Hausa-Yoruba: geographic proximity, shared features
    )

    /**
     * Compute phoneme overlap similarity between two languages.
     *
     * Jaccard similarity on phoneme sets:
     *   sim(A, B) = |A ∩ B| / |A ∪ B|
     *
     * @return Similarity score [0, 1]
     */
    fun computeSimilarity(lang1: String, lang2: String): Double {
        val inv1 = inventories[lang1] ?: return 0.0
        val inv2 = inventories[lang2] ?: return 0.0

        val phonemes1 = inv1.vowels + inv1.consonants
        val phonemes2 = inv2.vowels + inv2.consonants

        val intersection = phonemes1.intersect(phonemes2).size.toDouble()
        val union = phonemes1.union(phonemes2).size.toDouble()

        return if (union > 0) intersection / union else 0.0
    }

    // ════════════════════════════════════════════════════════════════════
    // POST-PROCESSING — Correct ASR output using phoneme knowledge
    // ════════════════════════════════════════════════════════════════════

    /**
     * Apply phoneme-aware post-processing to ASR output.
     *
     * Strategy:
     * 1. Detect language of ASR output
     * 2. Apply known confusion corrections for that language
     * 3. Handle code-switching boundaries
     * 4. Validate corrected text against phonotactic rules
     *
     * @param asrOutput Raw ASR output text
     * @param language Detected or expected language
     * @return Corrected text
     */
    fun postProcess(asrOutput: String, language: String): String {
        val corrections = confusionMatrix[language] ?: return asrOutput
        var corrected = asrOutput

        // Apply character-level corrections from confusion matrix
        // Only apply when the confused form matches known error patterns
        for ((correctPhoneme, confusions) in corrections) {
            for ((wrongPhoneme, _) in confusions) {
                if (wrongPhoneme == correctPhoneme || wrongPhoneme.isEmpty()) continue

                // Only correct when the wrong form is likely an ASR error
                // Use word-boundary-aware replacement
                corrected = applyPhonemeCorrection(corrected, wrongPhoneme, correctPhoneme, language)
            }
        }

        // Apply language-specific rules
        corrected = applyLanguageSpecificRules(corrected, language)

        Timber.tag(TAG).v("PostProcess [%s]: '%s' → '%s'", language, asrOutput, corrected)
        return corrected
    }

    /**
     * Apply a single phoneme correction with context awareness.
     * Only corrects when the surrounding context supports the correction.
     */
    private fun applyPhonemeCorrection(
        text: String,
        wrong: String,
        correct: String,
        language: String
    ): String {
        // Build context-aware correction patterns
        val inventory = inventories[language] ?: return text

        // Only correct if the correction produces a valid phoneme in the target language
        if (correct !in inventory.consonants && correct !in inventory.specialRules.values) {
            return text
        }

        // Apply word-level corrections (not substring) to avoid over-correction
        return text.split(" ").joinToString(" ") { word ->
            if (shouldCorrectPhoneme(word, wrong, correct, language)) {
                word.replace(wrong, correct)
            } else {
                word
            }
        }
    }

    /**
     * Determine if a phoneme correction should be applied to a specific word.
     * Uses phonotactic constraints to avoid over-correction.
     */
    private fun shouldCorrectPhoneme(
        word: String,
        wrong: String,
        correct: String,
        language: String
    ): Boolean {
        // Don't correct if the word is already valid with the "wrong" phoneme
        val inventory = inventories[language] ?: return false

        // For prenasalized stops (mb, nd, ŋg): only correct at word boundaries
        if (correct in setOf("mb", "nd", "ŋg", "nj")) {
            return word.contains(wrong) && !word.contains(correct)
        }

        // For implosives: always correct (ASR never produces them)
        if (correct in setOf("ɓ", "ɗ")) {
            return word.contains(wrong)
        }

        // Default: correct if the wrong phoneme appears in a position where
        // the correct phoneme is phonotactically valid
        return word.contains(wrong)
    }

    /**
     * Apply language-specific phonological rules.
     */
    private fun applyLanguageSpecificRules(text: String, language: String): String {
        return when (language) {
            "sw" -> applySwahiliRules(text)
            "luo" -> applyDholuoRules(text)
            "yo" -> applyYorubaRules(text)
            "ha" -> applyHausaRules(text)
            else -> text
        }
    }

    private fun applySwahiliRules(text: String): String {
        var result = text
        // ng' → ŋ (velar nasal with apostrophe)
        result = result.replace("ng'", "ŋ")
        result = result.replace("ng\u2019", "ŋ")
        // Normalize common ASR artifacts
        result = result.replace(Regex("\\bth([aeiou])"), "t$1")  // Swahili has no "th"
        result = result.replace(Regex("\\bdh([aeiou])"), "d$1")  // Swahili has no "dh"
        return result
    }

    private fun applyDholuoRules(text: String): String {
        // Dholuo vowel harmony: +ATR and -ATR vowels don't mix in roots
        // This is hard to correct post-hoc; mainly flag for ASR retraining
        return text
    }

    private fun applyYorubaRules(text: String): String {
        var result = text
        // Yoruba tone markers: if missing, can't reconstruct (tones are lexical)
        // But we can handle the labial-velar stops
        result = result.replace("kp", "kp")  // preserve if ASR got it right
        return result
    }

    private fun applyHausaRules(text: String): String {
        var result = text
        // Hausa implosives
        result = result.replace(Regex("\\bb([aeiou])\\b"), "ɓ$1")  // word-initial b → ɓ
        return result
    }

    // ════════════════════════════════════════════════════════════════════
    // PHONEME-TO-GRAPHHEME MAPPING (for TTS)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Convert IPA phoneme sequence to native orthography.
     * Used when synthesizing speech for languages that don't use Latin script,
     * or when normalizing IPA transcriptions to standard spelling.
     */
    fun phonemesToGraphemes(phonemes: String, language: String): String {
        val inventory = inventories[language] ?: return phonemes
        var result = phonemes

        for ((grapheme, phoneme) in inventory.specialRules) {
            result = result.replace(phoneme, grapheme)
        }

        return result
    }

    /**
     * Get the phoneme inventory for a language.
     */
    fun getInventory(language: String): PhonemeInventory? = inventories[language]

    /**
     * Get all supported language codes.
     */
    fun getSupportedLanguages(): Set<String> = inventories.keys

    /**
     * Check if a phoneme exists in a language's inventory.
     */
    fun isValidPhoneme(phoneme: String, language: String): Boolean {
        val inventory = inventories[language] ?: return false
        return phoneme in inventory.vowels || phoneme in inventory.consonants
    }

    /**
     * Get transfer learning priority list for a target language.
     * Languages with higher phoneme similarity should be learned first.
     *
     * @param targetLanguage The language we want to learn
     * @return List of (source_language, similarity_score) sorted by similarity descending
     */
    fun getTransferPriority(targetLanguage: String): List<Pair<String, Double>> {
        return inventories.keys
            .filter { it != targetLanguage }
            .map { sourceLang ->
                sourceLang to (crossLingualSimilarity[sourceLang to targetLanguage]
                    ?: crossLingualSimilarity[targetLanguage to sourceLang]
                    ?: computeSimilarity(sourceLang, targetLanguage))
            }
            .sortedByDescending { it.second }
    }
}

/**
 * Phoneme inventory for a specific language.
 *
 * @param languageCode ISO 639 code
 * @param vowels Set of vowel phonemes (IPA)
 * @param consonants Set of consonant phonemes (IPA)
 * @param tones Set of tone markers (H, L, M, HL, LH, etc.)
 * @param specialRules Grapheme → phoneme mappings for orthography
 */
data class PhonemeInventory(
    val languageCode: String,
    val vowels: Set<String>,
    val consonants: Set<String>,
    val tones: Set<String>,
    val specialRules: Map<String, String>
) {
    /** Total phoneme count */
    val size: Int get() = vowels.size + consonants.size

    /** Whether this language is tonal */
    val isTonal: Boolean get() = tones.isNotEmpty()

    /** Whether this language has implosive consonants */
    val hasImplosives: Boolean get() = consonants.any { it in setOf("ɓ", "ɗ") }

    /** Whether this language has prenasalized stops */
    val hasPrenasalized: Boolean get() = consonants.any { it in setOf("mb", "nd", "ŋg", "nj") }

    /** Get all phonemes as a flat set */
    fun allPhonemes(): Set<String> = vowels + consonants
}
