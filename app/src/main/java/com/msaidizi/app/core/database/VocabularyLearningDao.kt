package com.msaidizi.app.core.database

import androidx.room.*
import com.msaidizi.app.core.model.LearnedWord
import kotlinx.coroutines.flow.Flow

/**
 * DAO for adaptive vocabulary learning — tracks unknown words.
 *
 * This is complementary to UserVocabularyDao:
 * - VocabularyLearningDao: raw unknown words pending classification (LearnedWord)
 * - UserVocabularyDao: confirmed vocabulary with price tracking (UserVocabulary)
 *
 * Optimized for 2GB devices:
 * - Minimal indices
 * - Integer timestamps
 * - Compact string storage
 */
@Dao
interface VocabularyLearningDao {

    // ────────────────────── Learned Words ──────────────────────

    /**
     * Record or increment a word the user spoke that wasn't in the dictionary.
     * If the word exists, increments frequency and updates lastSeenAt.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLearnedWord(word: LearnedWord)

    /**
     * Get a specific learned word.
     */
    @Query("SELECT * FROM learned_words WHERE word = :word LIMIT 1")
    suspend fun getLearnedWord(word: String): LearnedWord?

    /**
     * Get all learned words, most frequent first.
     */
    @Query("SELECT * FROM learned_words ORDER BY frequency DESC")
    fun getAllLearnedWords(): Flow<List<LearnedWord>>

    /**
     * Get learned words that haven't been mapped to a canonical form yet.
     * These need user confirmation or automatic inference.
     */
    @Query("SELECT * FROM learned_words WHERE canonicalForm IS NULL ORDER BY frequency DESC LIMIT :limit")
    suspend fun getUnmappedWords(limit: Int = 20): List<LearnedWord>

    /**
     * Get learned words above a frequency threshold.
     * High-frequency unknown words are likely real products/terms.
     */
    @Query("SELECT * FROM learned_words WHERE frequency >= :minFrequency ORDER BY frequency DESC")
    suspend fun getSignificantWords(minFrequency: Int = 3): List<LearnedWord>

    /**
     * Update the canonical form mapping for a learned word.
     * Called when user confirms what a word means, or when auto-inferred.
     */
    @Query("UPDATE learned_words SET canonicalForm = :canonicalForm, mappedAt = :now WHERE word = :word")
    suspend fun mapWordToCanonical(word: String, canonicalForm: String, now: Long = System.currentTimeMillis() / 1000)

    /**
     * Get count of unmapped words (for UI badge).
     */
    @Query("SELECT COUNT(*) FROM learned_words WHERE canonicalForm IS NULL")
    fun getUnmappedWordCount(): Flow<Int>

    /**
     * Delete old learned words that were never confirmed (low frequency, old).
     */
    @Query("DELETE FROM learned_words WHERE frequency < 2 AND lastSeenAt < :before AND canonicalForm IS NULL")
    suspend fun pruneOldWords(before: Long)

    /**
     * Get total count of learned words.
     */
    @Query("SELECT COUNT(*) FROM learned_words")
    fun getLearnedWordCount(): Flow<Int>

    /**
     * Get recently learned words (last N days).
     */
    @Query("SELECT * FROM learned_words WHERE firstSeenAt >= :since ORDER BY frequency DESC")
    suspend fun getRecentLearnedWords(since: Long): List<LearnedWord>

    /**
     * Delete a specific learned word.
     */
    @Query("DELETE FROM learned_words WHERE word = :word")
    suspend fun deleteLearnedWord(word: String)
}
