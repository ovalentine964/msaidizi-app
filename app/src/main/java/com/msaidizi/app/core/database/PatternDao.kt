package com.msaidizi.app.core.database

import androidx.room.*
import com.msaidizi.app.core.model.*
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for business patterns, vocabulary, and daily summaries.
 */
@Dao
interface PatternDao {

    // === PATTERNS ===

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPattern(pattern: BusinessPattern): Long

    @Update
    suspend fun updatePattern(pattern: BusinessPattern)

    @Query("SELECT * FROM patterns WHERE patternType = :type ORDER BY updatedAt DESC")
    suspend fun getPatternsByType(type: PatternType): List<BusinessPattern>

    @Query("SELECT * FROM patterns WHERE patternType = :type AND data LIKE '%' || :key || '%' ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getPatternByKey(type: PatternType, key: String): BusinessPattern?

    @Query("DELETE FROM patterns WHERE id = :id")
    suspend fun deletePattern(id: Long)

    // === VOCABULARY ===

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVocabulary(entry: VocabularyEntry)

    @Query("SELECT * FROM vocabulary WHERE spokenForm = :spoken LIMIT 1")
    suspend fun getVocabularyEntry(spoken: String): VocabularyEntry?

    @Query("SELECT * FROM vocabulary WHERE language = :language ORDER BY frequency DESC")
    suspend fun getVocabularyForLanguage(language: String): List<VocabularyEntry>

    @Query("SELECT canonicalForm FROM vocabulary WHERE spokenForm = :spoken LIMIT 1")
    suspend fun getCanonicalForm(spoken: String): String?

    @Query("""
        UPDATE vocabulary 
        SET frequency = frequency + 1, lastUsedAt = :now 
        WHERE spokenForm = :spoken
    """)
    suspend fun incrementFrequency(spoken: String, now: Long = System.currentTimeMillis() / 1000)

    @Query("SELECT * FROM vocabulary ORDER BY frequency DESC LIMIT :limit")
    suspend fun getTopVocabulary(limit: Int = 50): List<VocabularyEntry>

    // === DAILY SUMMARIES ===

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSummary(summary: DailySummary)

    @Query("SELECT * FROM daily_summaries WHERE date = :date LIMIT 1")
    suspend fun getSummary(date: String): DailySummary?

    @Query("SELECT * FROM daily_summaries ORDER BY date DESC LIMIT :limit")
    fun getRecentSummaries(limit: Int = 7): Flow<List<DailySummary>>

    @Query("SELECT * FROM daily_summaries WHERE date >= :startDate ORDER BY date ASC")
    suspend fun getSummariesInRange(startDate: String): List<DailySummary>

    @Query("DELETE FROM daily_summaries WHERE date < :beforeDate")
    suspend fun deleteOldSummaries(beforeDate: String)
}
