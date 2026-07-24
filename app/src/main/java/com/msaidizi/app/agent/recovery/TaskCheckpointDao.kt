package com.msaidizi.app.agent.recovery

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * DAO for agent task checkpoints.
 */
@Dao
interface TaskCheckpointDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(checkpoint: AgentTaskCheckpoint)

    @Query("SELECT * FROM agent_task_checkpoints WHERE task_id = :taskId")
    suspend fun getCheckpoint(taskId: String): AgentTaskCheckpoint?

    @Query("SELECT * FROM agent_task_checkpoints WHERE state != 'COMPLETED' ORDER BY updated_at DESC")
    suspend fun getIncompleteTasks(): List<AgentTaskCheckpoint>

    @Query("DELETE FROM agent_task_checkpoints WHERE state = 'COMPLETED' AND updated_at < :cutoff")
    suspend fun cleanupOlderThan(cutoff: Long)
}

/**
 * DAO for agent recovery traces.
 */
@Dao
interface AgentTraceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(trace: AgentRecoveryTrace)

    @Query("SELECT * FROM agent_recovery_traces WHERE task_id = :taskId ORDER BY created_at ASC")
    suspend fun getTracesForTask(taskId: String): List<AgentRecoveryTrace>

    @Query("DELETE FROM agent_recovery_traces WHERE created_at < :cutoff")
    suspend fun cleanupOlderThan(cutoff: Long)
}
