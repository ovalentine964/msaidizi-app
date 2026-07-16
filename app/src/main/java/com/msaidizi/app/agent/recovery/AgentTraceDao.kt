package com.msaidizi.app.agent.recovery

import androidx.room.*

/**
 * DAO for persisting agent traces (OODA cycles, ReAct reasoning chains).
 *
 * Traces are written to disk so they survive crashes and can be
 * queried for debugging agent behavior.
 */
@Dao
interface AgentTraceDao {

    @Insert
    suspend fun insert(trace: AgentRecoveryTrace): Long

    @Query("SELECT * FROM agent_recovery_traces WHERE taskId = :taskId ORDER BY timestamp ASC")
    suspend fun getByTaskId(taskId: String): List<AgentRecoveryTrace>

    @Query("SELECT * FROM agent_recovery_traces WHERE traceType = :type ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getByType(type: TraceType, limit: Int = 50): List<AgentRecoveryTrace>

    @Query("SELECT * FROM agent_recovery_traces ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<AgentRecoveryTrace>

    @Query("SELECT * FROM agent_recovery_traces WHERE success = 0 ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getFailed(limit: Int = 20): List<AgentRecoveryTrace>

    @Query("SELECT * FROM agent_recovery_traces WHERE timestamp >= :since ORDER BY timestamp ASC")
    suspend fun getSince(since: Long): List<AgentRecoveryTrace>

    @Query("DELETE FROM agent_recovery_traces WHERE timestamp < :cutoff")
    suspend fun cleanup(cutoff: Long)

    @Query("DELETE FROM agent_recovery_traces")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM agent_recovery_traces")
    suspend fun count(): Int
}
