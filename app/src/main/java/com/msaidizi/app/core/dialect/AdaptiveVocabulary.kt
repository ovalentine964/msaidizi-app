package com.msaidizi.app.core.dialect

import com.msaidizi.app.core.database.VocabularyLearningDao
import com.msaidizi.app.core.model.UserVocabularyDao
import com.msaidizi.app.core.model.DialectRegion
import com.msaidizi.app.core.model.LearnedWord
import com.msaidizi.app.core.model.UserVocabulary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Adaptive vocabulary learning system for Msaidizi.
 *
 * Tracks words the user says that aren't in the dictionary,
 * learns new products/items the user sells, and applies
 * learned vocabulary to future transcriptions.
 *
 * Architecture:
 * - VocabularyLearningDao: tracks raw unknown words (LearnedWord)
 * - UserVocabularyDao: confirmed vocabulary with price tracking (UserVocabulary)
 * - This class bridges the two: tracks → promotes → applies
 *
 * Design principles:
 * - On-device only — no data leaves the phone
 * - Memory-efficient — prunes low-frequency unknown words
 * - Battery-aware — defers heavy work to idle/charging
 * - User-controlled — can confirm/reject learned mappings
 *
 * Learning flow:
 * 1. User speaks → SwahiliParser finds unknown word
 * 2. AdaptiveVocabulary tracks the unknown word (LearnedWord)
 * 3. After 3+ occurrences, word is promoted with inferred mapping
 * 4. User confirms or corrects the mapping
 * 5. Confirmed mappings stored in UserVocabulary (overrides built-in dictionaries)
 *
 * @param learningDao DAO for tracking unknown words
 * @param userVocabDao DAO for confirmed user vocabulary
 */
class AdaptiveVocabulary(
    private val learningDao: VocabularyLearningDao,
    private val userVocabDao: UserVocabularyDao
) {
    companion object {
        private const val TAG = "AdaptiveVocab"

        /** Minimum occurrences before auto-promoting a word */
        private const val PROMOTION_THRESHOLD = 3

        /** Prune words older than this with frequency < 2 (1 week) */
        private const val PRUNE_AGE_SECONDS = 7 * 24 * 60 * 60
    }

    /** In-memory cache of user vocabulary for fast lookup */
    private var userVocabCache: Map<String, String> = emptyMap()

    /** In-memory cache of recently learned words (session-only, no DB overhead) */
    private val sessionWords = mutableMapOf<String, Int>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Load user vocabulary cache on creation
        scope.launch {
            refreshCache()
        }
    }

    // ────────────────────── Public API ──────────────────────

    /**
     * Track a word that the user spoke but wasn't in the dictionary.
     * Called by SwahiliParser when it encounters an unknown word.
     *
     * @param word The unknown word (lowercase, trimmed)
     * @param dialectRegion The dialect region detected for this utterance
     */
    fun trackUnknownWord(word: String, dialectRegion: String = "STANDARD") {
        val clean = word.lowercase().trim()
        if (clean.length < 2) return

        // Track in session (immediate, no DB overhead)
        sessionWords[clean] = (sessionWords[clean] ?: 0) + 1

        Timber.tag(TAG).v("Tracked unknown word: '%s' (session count: %d)", clean, sessionWords[clean])

        // Persist to database asynchronously
        scope.launch {
            try {
                val existing = learningDao.getLearnedWord(clean)
                if (existing != null) {
                    // Increment frequency
                    learningDao.upsertLearnedWord(
                        existing.copy(
                            frequency = existing.frequency + 1,
                            lastSeenAt = System.currentTimeMillis() / 1000
                        )
                    )
                } else {
                    // New word
                    learningDao.upsertLearnedWord(
                        LearnedWord(
                            word = clean,
                            frequency = 1,
                            dialectRegion = dialectRegion,
                            categoryHint = inferCategory(clean)
                        )
                    )
                }

                // Check if word qualifies for promotion
                val updated = learningDao.getLearnedWord(clean)
                if (updated != null &&
                    updated.frequency >= PROMOTION_THRESHOLD &&
                    updated.canonicalForm == null
                ) {
                    promoteWord(updated)
                }

                // Prune old low-frequency words periodically
                if (sessionWords.size % 50 == 0) {
                    pruneOldWords()
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to track word: %s", clean)
            }
        }
    }

    /**
     * Look up the canonical form for a spoken word.
     * Checks user vocabulary first (highest priority), then learned words.
     *
     * @return Canonical form, or null if unknown
     */
    suspend fun lookup(spoken: String): String? {
        val clean = spoken.lowercase().trim()

        // 1. Check user vocabulary cache (user-taught terms take priority)
        userVocabCache[clean]?.let { return it }

        // 2. Check user vocabulary DB directly (in case cache is stale)
        userVocabDao.getBySpokenForm(clean)?.let { entry ->
            return entry.canonicalForm
        }

        // 3. Check learned words with auto-inferred mappings
        learningDao.getLearnedWord(clean)?.canonicalForm?.let { return it }

        return null
    }

    /**
     * Teach the system a new word mapping.
     * Called when user confirms what a word means, or teaches a new product.
     *
     * Example: User says "kiherehere" and confirms it means "small fish"
     */
    suspend fun teachWord(
        spoken: String,
        canonical: String,
        category: String = "product",
        language: String = "migori"
    ) {
        val cleanSpoken = spoken.lowercase().trim()
        Timber.tag(TAG).d("Teaching: '%s' → '%s' (category=%s)", cleanSpoken, canonical, category)

        // Store in UserVocabulary (confirmed, with price tracking)
        userVocabDao.upsert(
            UserVocabulary(
                spokenForm = cleanSpoken,
                canonicalForm = canonical,
                language = language,
                category = category,
                isUserDefined = true,
                confidence = 0.9 // High confidence for user-taught terms
            )
        )

        // Update cache
        refreshCache()

        // Also mark the learned word as mapped
        learningDao.getLearnedWord(cleanSpoken)?.let { _ ->
            learningDao.mapWordToCanonical(cleanSpoken, canonical)
        }
    }

    /**
     * Get all unconfirmed learned words for user review.
     * UI shows these as "New words I heard — what do they mean?"
     */
    suspend fun getPendingWords(limit: Int = 20): List<LearnedWord> {
        return learningDao.getUnmappedWords(limit)
    }

    /**
     * Get count of pending words (for UI badge).
     */
    fun getPendingWordCount(): Flow<Int> {
        return learningDao.getUnmappedWordCount()
    }

    /**
     * Get all user-taught vocabulary.
     */
    suspend fun getAllUserVocabulary(limit: Int = 100): List<UserVocabulary> {
        return userVocabDao.getTopByFrequency(limit)
    }

    /**
     * Search vocabulary by spoken or canonical form.
     */
    suspend fun searchVocabulary(query: String): List<UserVocabulary> {
        return userVocabDao.search(query)
    }

    /**
     * Remove a user vocabulary entry.
     */
    suspend fun removeVocabulary(spoken: String) {
        userVocabDao.delete(spoken)
        refreshCache()
    }

    /**
     * Get the total count of learned vocabulary entries.
     */
    fun getVocabularyCount(): Flow<Int> {
        return learningDao.getLearnedWordCount()
    }

    /**
     * Apply learned vocabulary to a transcription.
     * Replaces known user terms with their canonical forms.
     *
     * @param text The ASR transcription
     * @return Text with user vocabulary applied
     */
    suspend fun applyToTranscription(text: String): String {
        var result = text
        val words = text.split(Regex("""(\s+|[,.!?;:])""")).filter { it.isNotBlank() }

        for (word in words) {
            val clean = word.lowercase().trim('.', ',', '!', '?', ';', ':')
            val canonical = lookup(clean)
            if (canonical != null && canonical != clean) {
                // Replace preserving original casing pattern
                result = result.replace(
                    Regex("""\b${Regex.escape(clean)}\b""", RegexOption.IGNORE_CASE),
                    canonical
                )
                Timber.tag(TAG).v("Applied: '%s' → '%s'", clean, canonical)
            }
        }

        return result
    }

    /**
     * Update price observation for a known product.
     * Called when a transaction is recorded to improve price predictions.
     */
    suspend fun updatePriceObservation(spoken: String, price: Double, quantity: Double = 1.0) {
        val clean = spoken.lowercase().trim()
        userVocabDao.updatePrice(clean, price)
        userVocabDao.updateAvgQuantity(clean, quantity)
        Timber.tag(TAG).v("Price updated: '%s' → %.0f (qty=%.1f)", clean, price, quantity)
    }

    /**
     * Get recently seen unknown words from this session.
     * Useful for immediate UI feedback.
     */
    fun getSessionUnknownWords(): Map<String, Int> {
        return sessionWords.toMap()
    }

    /**
     * Get high-confidence user vocabulary entries.
     * These are terms the system is most sure about.
     */
    suspend fun getHighConfidenceVocabulary(): List<UserVocabulary> {
        return userVocabDao.getHighConfidence(0.7)
    }

    /**
     * Get vocabulary categories the user has taught.
     */
    suspend fun getVocabularyCategories(): List<String> {
        return userVocabDao.getCategories()
    }

    // ────────────────────── Internal Logic ──────────────────────

    /**
     * Promote a learned word by inferring its canonical form.
     * Uses simple heuristics:
     * - If it sounds like a known word variant → map to that
     * - If it's a Dholuo word → map to Swahili equivalent
     * - Otherwise → mark as needing user confirmation
     */
    private suspend fun promoteWord(word: LearnedWord) {
        Timber.tag(TAG).d("Promoting word: '%s' (freq=%d)", word.word, word.frequency)

        // Try Migori dialect adapter for translation
        val translation = MigoriDialectAdapter.translateToStandard(word.word)
        if (translation != null && translation != word.word) {
            // Auto-map to standard form
            learningDao.mapWordToCanonical(word.word, translation)
            Timber.tag(TAG).d("Auto-mapped: '%s' → '%s'", word.word, translation)

            // Also create a UserVocabulary entry (unconfirmed, for review)
            userVocabDao.upsert(
                UserVocabulary(
                    spokenForm = word.word,
                    canonicalForm = translation,
                    language = "migori",
                    category = inferCategory(word.word),
                    isUserDefined = false,
                    confidence = 0.5 // Medium confidence for auto-mapped
                )
            )
            refreshCache()
            return
        }

        // Try to infer category for better user prompting
        val category = inferCategory(word.word)
        learningDao.upsertLearnedWord(
            word.copy(categoryHint = category)
        )

        Timber.tag(TAG).d("Word '%s' needs user confirmation (category hint: %s)", word.word, category)
    }

    /**
     * Infer the category of an unknown word based on context clues.
     */
    private fun inferCategory(word: String): String {
        // Currency-like patterns
        if (word.matches(Regex("""\d+"""))) return "number"

        // Unit suffixes
        val unitSuffixes = setOf("kilo", "lita", "debe", "gunia", "fundo", "mfuko", "pakiti")
        if (unitSuffixes.any { word.contains(it) }) return "unit"

        // Action words (common verb prefixes)
        val verbPrefixes = setOf("ni", "a", "wa", "ta", "hu", "si", "ku")
        if (verbPrefixes.any { word.startsWith(it) && word.length > 4 }) return "action"

        // Default: assume product
        return "product"
    }

    /**
     * Refresh the in-memory user vocabulary cache.
     */
    private suspend fun refreshCache() {
        try {
            val entries = userVocabDao.getForLanguage("sw") +
                    userVocabDao.getForLanguage("migori") +
                    userVocabDao.getForLanguage("luo")
            userVocabCache = entries.associate { it.spokenForm to it.canonicalForm }
            Timber.tag(TAG).d("Cache refreshed: %d entries", userVocabCache.size)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to refresh cache")
        }
    }

    /**
     * Prune old, low-frequency unknown words to save storage.
     */
    private suspend fun pruneOldWords() {
        try {
            val cutoff = System.currentTimeMillis() / 1000 - PRUNE_AGE_SECONDS
            learningDao.pruneOldWords(cutoff)
            Timber.tag(TAG).d("Pruned old words before %d", cutoff)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to prune words")
        }
    }
}
