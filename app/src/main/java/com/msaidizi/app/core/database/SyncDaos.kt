package com.msaidizi.app.core.database

import androidx.room.*
import com.msaidizi.app.model.*
import kotlinx.coroutines.flow.Flow

// ──────────────────────────────────────────────
// Anomaly History DAO
// ──────────────────────────────────────────────

@Dao
interface AnomalyHistoryDao {
    @Insert
    suspend fun insert(entry: AnomalyHistoryEntity): Long

    @Query("SELECT * FROM anomaly_history ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 100): List<AnomalyHistoryEntity>

    @Query("SELECT * FROM anomaly_history ORDER BY timestamp DESC")
    fun getAllFlow(): Flow<List<AnomalyHistoryEntity>>

    @Query("SELECT COUNT(*) FROM anomaly_history")
    suspend fun getCount(): Int

    @Query("SELECT AVG(amount) FROM anomaly_history")
    suspend fun getMean(): Double?

    @Query("SELECT * FROM anomaly_history ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getLastN(limit: Int): List<AnomalyHistoryEntity>

    @Query("DELETE FROM anomaly_history WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM anomaly_history")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM anomaly_history WHERE isAnomaly = 1")
    suspend fun getAnomalyCount(): Int
}

// ──────────────────────────────────────────────
// Learned Vocabulary DAO
// ──────────────────────────────────────────────

@Dao
interface LearnedVocabularyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: LearnedVocabularyEntity)

    @Query("SELECT * FROM learned_vocabulary ORDER BY frequency DESC")
    suspend fun getAll(): List<LearnedVocabularyEntity>

    @Query("SELECT * FROM learned_vocabulary ORDER BY frequency DESC")
    fun getAllFlow(): Flow<List<LearnedVocabularyEntity>>

    @Query("SELECT * FROM learned_vocabulary WHERE word = :word")
    suspend fun getWord(word: String): LearnedVocabularyEntity?

    @Query("UPDATE learned_vocabulary SET frequency = frequency + 1, confidence = MIN(confidence + 0.1, 1.0), lastSeenAt = :now WHERE word = :word")
    suspend fun incrementFrequency(word: String, now: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM learned_vocabulary")
    suspend fun getCount(): Int

    @Query("DELETE FROM learned_vocabulary WHERE lastSeenAt < :before")
    suspend fun deleteOlderThan(before: Long)
}

// ──────────────────────────────────────────────
// Business Patterns DAO
// ──────────────────────────────────────────────

@Dao
interface BusinessPatternDao {
    @Insert
    suspend fun insert(pattern: BusinessPatternEntity): Long

    @Query("SELECT * FROM business_patterns ORDER BY lastDetectedAt DESC")
    suspend fun getAll(): List<BusinessPatternEntity>

    @Query("SELECT * FROM business_patterns ORDER BY lastDetectedAt DESC")
    fun getAllFlow(): Flow<List<BusinessPatternEntity>>

    @Query("SELECT * FROM business_patterns WHERE patternType = :type ORDER BY lastDetectedAt DESC LIMIT 1")
    suspend fun getByType(type: String): BusinessPatternEntity?

    @Update
    suspend fun update(pattern: BusinessPatternEntity)

    @Query("UPDATE business_patterns SET occurrenceCount = occurrenceCount + 1, lastDetectedAt = :now WHERE id = :id")
    suspend fun incrementOccurrences(id: Long, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM business_patterns")
    suspend fun deleteAll()
}

// ──────────────────────────────────────────────
// Sync State DAO
// ──────────────────────────────────────────────

@Dao
interface SyncStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: SyncStateEntity)

    @Query("SELECT * FROM sync_state WHERE id = 1")
    suspend fun getState(): SyncStateEntity?

    @Query("SELECT * FROM sync_state WHERE id = 1")
    fun getStateFlow(): Flow<SyncStateEntity?>

    @Query("UPDATE sync_state SET lastSyncTimestamp = :timestamp, lastSyncStatus = :status, consecutiveFailures = 0, lastError = NULL, updatedAt = :now WHERE id = 1")
    suspend fun recordSuccess(timestamp: Long, status: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE sync_state SET lastSyncStatus = :status, lastError = :error, consecutiveFailures = consecutiveFailures + 1, updatedAt = :now WHERE id = 1")
    suspend fun recordFailure(status: String, error: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE sync_state SET pendingTransactionCount = :count, updatedAt = :now WHERE id = 1")
    suspend fun updatePendingCount(count: Int, now: Long = System.currentTimeMillis())
}
