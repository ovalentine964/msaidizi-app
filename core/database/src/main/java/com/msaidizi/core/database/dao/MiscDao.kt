package com.msaidizi.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.msaidizi.core.database.entity.AgentTraceEntity
import com.msaidizi.core.database.entity.GamificationEntity
import com.msaidizi.core.database.entity.ProofPointEntity
import com.msaidizi.core.database.entity.SessionEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for proof points (M-KOPA model).
 */
@Dao
interface ProofPointDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(proof: ProofPointEntity): Long

    @Query("SELECT * FROM proof_points ORDER BY timestamp DESC")
    fun getAll(): Flow<List<ProofPointEntity>>

    @Query("SELECT * FROM proof_points ORDER BY timestamp DESC")
    suspend fun getAllProofPoints(): List<ProofPointEntity>

    @Query("SELECT * FROM proof_points WHERE type = :type ORDER BY timestamp DESC")
    suspend fun getByType(type: String): List<ProofPointEntity>

    @Query("SELECT * FROM proof_points WHERE timestamp >= :since ORDER BY timestamp DESC")
    suspend fun getSince(since: Long): List<ProofPointEntity>

    @Query("SELECT COUNT(*) FROM proof_points")
    suspend fun getTotalCount(): Int

    @Query("SELECT COALESCE(SUM(weight), 0.0) FROM proof_points")
    suspend fun getTotalWeight(): Double

    @Query("SELECT COUNT(DISTINCT date(timestamp / 1000, 'unixepoch', 'localtime')) FROM proof_points")
    suspend fun getActiveDays(): Int

    @Query("SELECT * FROM proof_points WHERE is_synced = 0 ORDER BY timestamp ASC LIMIT :limit")
    suspend fun getUnsynced(limit: Int = 100): List<ProofPointEntity>

    @Query("UPDATE proof_points SET is_synced = 1, synced_at = :syncedAt WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>, syncedAt: Long)
}

/**
 * DAO for agent traces (debugging/audit trail).
 */
@Dao
interface AgentTraceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(trace: AgentTraceEntity): Long

    @Query("SELECT * FROM agent_traces WHERE session_id = :sessionId ORDER BY created_at DESC")
    suspend fun getBySession(sessionId: String): List<AgentTraceEntity>

    @Query("SELECT * FROM agent_traces ORDER BY created_at DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<AgentTraceEntity>

    @Query("SELECT * FROM agent_traces WHERE created_at >= :since ORDER BY created_at DESC")
    suspend fun getSince(since: Long): List<AgentTraceEntity>

    @Query("DELETE FROM agent_traces WHERE created_at < :before")
    suspend fun deleteOlderThan(before: Long)
}

/**
 * DAO for gamification data.
 */
@Dao
interface GamificationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(gamification: GamificationEntity): Long

    @Query("SELECT * FROM gamification LIMIT 1")
    suspend fun getGamification(): GamificationEntity?

    @Query("SELECT * FROM gamification LIMIT 1")
    fun getGamificationFlow(): Flow<GamificationEntity?>

    @Query("UPDATE gamification SET total_points = total_points + :points, updated_at = :now WHERE id = :id")
    suspend fun addPoints(id: Long, points: Int, now: Long = System.currentTimeMillis())

    @Query("UPDATE gamification SET current_level = :level, updated_at = :now WHERE id = :id")
    suspend fun updateLevel(id: Long, level: Int, now: Long = System.currentTimeMillis())

    @Query("UPDATE gamification SET current_streak = :streak, longest_streak = MAX(longest_streak, :streak), last_activity_at = :now, updated_at = :now WHERE id = :id")
    suspend fun updateStreak(id: Long, streak: Int, now: Long = System.currentTimeMillis())

    @Query("UPDATE gamification SET badges_json = :badgesJson, updated_at = :now WHERE id = :id")
    suspend fun updateBadges(id: Long, badgesJson: String, now: Long = System.currentTimeMillis())
}

/**
 * DAO for session management.
 */
@Dao
interface SessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE sessionId = :sessionId")
    suspend fun getById(sessionId: String): SessionEntity?

    @Query("SELECT * FROM sessions ORDER BY started_at DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 10): List<SessionEntity>

    @Query("UPDATE sessions SET ended_at = :endedAt, turn_count = :turnCount, summary = :summary WHERE sessionId = :sessionId")
    suspend fun endSession(sessionId: String, endedAt: Long, turnCount: Int, summary: String)

    @Query("UPDATE sessions SET turn_count = turn_count + 1 WHERE sessionId = :sessionId")
    suspend fun incrementTurnCount(sessionId: String)
}
