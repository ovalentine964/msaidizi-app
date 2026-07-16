package com.msaidizi.app.agent.recovery

import androidx.room.*

/**
 * DAO for agent task checkpoint persistence.
 *
 * Supports the crash recovery workflow:
 * 1. saveCheckpoint() — called at each OODA phase boundary
 * 2. getIncompleteTasks() — called on app startup to find tasks to resume
 * 3. markCompleted/Failed() — called when task finishes
 * 4. cleanup() — removes old completed/failed tasks to save disk space
 */
@Dao
interface TaskCheckpointDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCheckpoint(checkpoint: AgentTaskCheckpoint)

    @Update
    suspend fun updateCheckpoint(checkpoint: AgentTaskCheckpoint)

    @Query("SELECT * FROM agent_task_checkpoints WHERE state IN ('PENDING', 'IN_PROGRESS', 'CHECKPOINTED') ORDER BY updatedAt ASC")
    suspend fun getIncompleteTasks(): List<AgentTaskCheckpoint>

    @Query("SELECT * FROM agent_task_checkpoints WHERE taskId = :taskId LIMIT 1")
    suspend fun getByTaskId(taskId: String): AgentTaskCheckpoint?

    @Query("SELECT * FROM agent_task_checkpoints WHERE state = :state ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun getByState(state: TaskState, limit: Int = 50): List<AgentTaskCheckpoint>

    @Query("UPDATE agent_task_checkpoints SET state = 'COMPLETED', updatedAt = :now WHERE taskId = :taskId")
    suspend fun markCompleted(taskId: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE agent_task_checkpoints SET state = 'FAILED', lastError = :error, updatedAt = :now WHERE taskId = :taskId")
    suspend fun markFailed(taskId: String, error: String?, now: Long = System.currentTimeMillis())

    @Query("UPDATE agent_task_checkpoints SET retryCount = retryCount + 1, updatedAt = :now WHERE taskId = :taskId")
    suspend fun incrementRetry(taskId: String, now: Long = System.currentTimeMillis())

    /** Delete completed/failed tasks older than cutoff to reclaim disk space. */
    @Query("DELETE FROM agent_task_checkpoints WHERE state IN ('COMPLETED', 'FAILED') AND updatedAt < :cutoff")
    suspend fun cleanup(cutoff: Long)

    /** Count incomplete tasks (for quick health checks). */
    @Query("SELECT COUNT(*) FROM agent_task_checkpoints WHERE state IN ('PENDING', 'IN_PROGRESS', 'CHECKPOINTED')")
    suspend fun countIncomplete(): Int

    /** Delete all checkpoints (emergency reset). */
    @Query("DELETE FROM agent_task_checkpoints")
    suspend fun deleteAll()
}
