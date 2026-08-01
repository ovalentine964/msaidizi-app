package com.msaidizi.core.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Room entity for persisting the offline sync queue.
 * Operations queued while offline survive app restarts and process death.
 */
@Entity(
    tableName = "sync_queue",
    indices = [
        Index(value = ["operationType"]),
        Index(value = ["createdAt"]),
        Index(value = ["status"])
    ]
)
data class SyncQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val operationId: String,
    val operationType: String,
    val payloadJson: String,
    val status: String = "pending",  // pending | processing | failed | completed
    val retryCount: Int = 0,
    val maxRetries: Int = 3,
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * DAO for the offline sync queue.
 */
@Dao
interface SyncQueueDao {
    @Insert
    suspend fun insert(entry: SyncQueueEntity): Long

    @Query("SELECT * FROM sync_queue WHERE status = 'pending' ORDER BY createdAt ASC")
    suspend fun getPending(): List<SyncQueueEntity>

    @Query("SELECT * FROM sync_queue WHERE status = 'pending' ORDER BY createdAt ASC")
    fun getPendingFlow(): Flow<List<SyncQueueEntity>>

    @Query("SELECT COUNT(*) FROM sync_queue WHERE status = 'pending'")
    suspend fun getPendingCount(): Int

    @Query("SELECT COUNT(*) FROM sync_queue WHERE status = 'pending'")
    fun getPendingCountFlow(): Flow<Int>

    @Query("UPDATE sync_queue SET status = :status, updatedAt = :now WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE sync_queue SET status = 'pending', retryCount = retryCount + 1, lastError = :error, updatedAt = :now WHERE id = :id")
    suspend fun recordFailure(id: Long, error: String, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM sync_queue WHERE status = 'completed'")
    suspend fun deleteCompleted()

    @Query("DELETE FROM sync_queue WHERE status = 'failed' AND retryCount >= maxRetries")
    suspend fun deleteExpiredFailures()

    @Query("DELETE FROM sync_queue WHERE createdAt < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM sync_queue")
    suspend fun deleteAll()
}
