package com.msaidizi.app.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

/**
 * Data Access Object for agent session persistence.
 *
 * Enables the Hermes session manager to survive app kills.
 * Sessions are lazy-loaded: only the requested worker's session
 * is hydrated from disk, not the entire session history.
 */
@Dao
interface SessionDao {

    // ═══════════════ SESSIONS ═══════════════

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: AgentSessionEntity)

    @Update
    suspend fun updateSession(session: AgentSessionEntity)

    @Query("SELECT * FROM agent_sessions WHERE worker_id = :workerId ORDER BY last_active DESC LIMIT 1")
    suspend fun getSessionByWorker(workerId: String): AgentSessionEntity?

    @Query("SELECT * FROM agent_sessions WHERE session_id = :sessionId")
    suspend fun getSession(sessionId: String): AgentSessionEntity?

    @Query("SELECT * FROM agent_sessions ORDER BY last_active DESC")
    suspend fun getAllSessions(): List<AgentSessionEntity>

    @Query("SELECT * FROM agent_sessions WHERE last_active > :since ORDER BY last_active DESC")
    suspend fun getRecentSessions(since: Long): List<AgentSessionEntity>

    @Query("DELETE FROM agent_sessions WHERE session_id = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Query("DELETE FROM agent_sessions WHERE last_active < :cutoff")
    suspend fun deleteStaleSessions(cutoff: Long)

    @Query("SELECT COUNT(*) FROM agent_sessions")
    suspend fun getSessionCount(): Int

    // ═══════════════ TRACES ═══════════════

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrace(trace: AgentTraceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTraces(traces: List<AgentTraceEntity>)

    @Query("SELECT * FROM agent_traces WHERE session_id = :sessionId AND trace_id = :traceId ORDER BY step_index ASC")
    suspend fun getTracesForInteraction(sessionId: String, traceId: String): List<AgentTraceEntity>

    @Query("SELECT * FROM agent_traces WHERE session_id = :sessionId ORDER BY created_at DESC LIMIT :limit")
    suspend fun getRecentTraces(sessionId: String, limit: Int = 50): List<AgentTraceEntity>

    @Query("DELETE FROM agent_traces WHERE session_id = :sessionId")
    suspend fun deleteTracesForSession(sessionId: String)

    @Query("DELETE FROM agent_traces WHERE created_at < :cutoff")
    suspend fun deleteOldTraces(cutoff: Long)

    // ═══════════════ TTL CLEANUP ═══════════════

    /**
     * Clean up sessions and traces older than the given TTL.
     * Returns the number of sessions deleted.
     */
    @Transaction
    suspend fun cleanupExpired(ttlMs: Long): Int {
        val cutoff = System.currentTimeMillis() - ttlMs
        val count = getSessionCount()
        deleteOldTraces(cutoff)
        deleteStaleSessions(cutoff)
        return count - getSessionCount()
    }
}
