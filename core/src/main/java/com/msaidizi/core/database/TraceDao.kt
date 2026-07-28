package com.msaidizi.core.database

import androidx.room.*
import com.msaidizi.core.model.TraceEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for agent trace persistence and querying.
 *
 * Supports:
 *   - Inserting new traces after each agent run
 *   - Updating user feedback post-hoc
 *   - Querying traces for local analysis
 *   - Batch sync to backend (get unsynced, mark synced)
 *   - Aggregate queries for local harness improvement
 */
@Dao
interface TraceDao {

    // ── Write ─────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(trace: TraceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(traces: List<TraceEntity>)

    // ── Update Feedback ───────────────────────────────────────

    @Query("UPDATE agent_traces SET userFeedback = :feedback WHERE traceId = :traceId")
    suspend fun updateFeedback(traceId: String, feedback: Int)

    @Query("UPDATE agent_traces SET userCorrection = :correction, correctionLatencyMs = :latencyMs WHERE traceId = :traceId")
    suspend fun updateCorrection(traceId: String, correction: String, latencyMs: Long)

    @Query("UPDATE agent_traces SET needsSync = 0, syncedAt = :syncedAt WHERE traceId = :traceId")
    suspend fun markSynced(traceId: String, syncedAt: Long = System.currentTimeMillis())

    @Query("UPDATE agent_traces SET needsSync = 0, syncedAt = :syncedAt WHERE traceId IN (:traceIds)")
    suspend fun markSyncedBatch(traceIds: List<String>, syncedAt: Long = System.currentTimeMillis())

    // ── Read ──────────────────────────────────────────────────

    @Query("SELECT * FROM agent_traces WHERE traceId = :traceId")
    suspend fun getById(traceId: String): TraceEntity?

    @Query("SELECT * FROM agent_traces ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(limit: Int = 50): Flow<List<TraceEntity>>

    @Query("SELECT * FROM agent_traces WHERE timestamp BETWEEN :start AND :end ORDER BY timestamp DESC")
    fun getBetween(start: Long, end: Long): Flow<List<TraceEntity>>

    @Query("SELECT * FROM agent_traces WHERE intentType = :intentType ORDER BY timestamp DESC LIMIT :limit")
    fun getByIntent(intentType: String, limit: Int = 100): Flow<List<TraceEntity>>

    // ── Sync ──────────────────────────────────────────────────

    @Query("SELECT * FROM agent_traces WHERE needsSync = 1 ORDER BY timestamp ASC LIMIT :batchSize")
    suspend fun getPendingSync(batchSize: Int = 50): List<TraceEntity>

    @Query("SELECT COUNT(*) FROM agent_traces WHERE needsSync = 1")
    suspend fun getPendingSyncCount(): Int

    // ── Aggregate Queries (for local harness improvement) ─────

    /**
     * Success rate per intent type.
     * Returns (intentType, total_count, success_count) where success = toolsFailed == 0.
     */
    @Query("""
        SELECT intentType,
               COUNT(*) as totalCount,
               SUM(CASE WHEN toolsFailed = 0 THEN 1 ELSE 0 END) as successCount
        FROM agent_traces
        WHERE timestamp BETWEEN :start AND :end
        GROUP BY intentType
    """)
    suspend fun getIntentSuccessRates(start: Long, end: Long): List<IntentSuccessRate>

    /**
     * Average confidence per intent type.
     */
    @Query("""
        SELECT intentType, AVG(finalConfidence) as avgConfidence
        FROM agent_traces
        WHERE timestamp BETWEEN :start AND :end
        GROUP BY intentType
    """)
    suspend fun getAvgConfidenceByIntent(start: Long, end: Long): List<IntentAvgConfidence>

    /**
     * Tool failure rates.
     * Returns (intentType, toolName, total_uses, failure_count).
     */
    @Query("""
        SELECT intentType, toolsSelected, toolsFailed
        FROM agent_traces
        WHERE timestamp BETWEEN :start AND :end AND toolsFailed > 0
    """)
    suspend fun getToolFailures(start: Long, end: Long): List<ToolFailureRow>

    /**
     * User correction rate per intent.
     * High correction rate = intent routing is wrong.
     */
    @Query("""
        SELECT intentType,
               COUNT(*) as total,
               SUM(CASE WHEN userCorrection IS NOT NULL THEN 1 ELSE 0 END) as corrections
        FROM agent_traces
        WHERE timestamp BETWEEN :start AND :end
        GROUP BY intentType
    """)
    suspend fun getCorrectionRates(start: Long, end: Long): List<IntentCorrectionRate>

    /**
     * Intent tier distribution — which tier resolves most intents.
     */
    @Query("""
        SELECT intentTier, COUNT(*) as count
        FROM agent_traces
        WHERE timestamp BETWEEN :start AND :end
        GROUP BY intentTier
    """)
    suspend fun getTierDistribution(start: Long, end: Long): List<TierDistribution>

    /**
     * Average latency breakdown by intent type.
     */
    @Query("""
        SELECT intentType,
               AVG(totalLatencyMs) as avgTotal,
               AVG(intentRoutingMs) as avgRouting,
               AVG(toolExecutionMs) as avgToolExec,
               AVG(llmInferenceMs) as avgLlm
        FROM agent_traces
        WHERE timestamp BETWEEN :start AND :end
        GROUP BY intentType
    """)
    suspend fun getLatencyBreakdown(start: Long, end: Long): List<IntentLatencyBreakdown>

    /**
     * User feedback rates per intent.
     */
    @Query("""
        SELECT intentType,
               COUNT(*) as total,
               SUM(CASE WHEN userFeedback = 1 THEN 1 ELSE 0 END) as positive,
               SUM(CASE WHEN userFeedback = 0 THEN 1 ELSE 0 END) as negative
        FROM agent_traces
        WHERE userFeedback IS NOT NULL AND timestamp BETWEEN :start AND :end
        GROUP BY intentType
    """)
    suspend fun getFeedbackRates(start: Long, end: Long): List<IntentFeedbackRate>

    // ── Cleanup ───────────────────────────────────────────────

    @Query("DELETE FROM agent_traces WHERE timestamp < :before AND needsSync = 0")
    suspend fun deleteOldSynced(before: Long)

    @Query("SELECT COUNT(*) FROM agent_traces")
    suspend fun getCount(): Int
}

// ── Aggregate result types ─────────────────────────────────────

data class IntentSuccessRate(
    val intentType: String,
    val totalCount: Int,
    val successCount: Int
) {
    val successRate: Float get() = if (totalCount > 0) successCount.toFloat() / totalCount else 0f
}

data class IntentAvgConfidence(
    val intentType: String,
    val avgConfidence: Float
)

data class ToolFailureRow(
    val intentType: String,
    val toolsSelected: String,   // JSON array
    val toolsFailed: Int
)

data class IntentCorrectionRate(
    val intentType: String,
    val total: Int,
    val corrections: Int
) {
    val correctionRate: Float get() = if (total > 0) corrections.toFloat() / total else 0f
}

data class TierDistribution(
    val intentTier: String,
    val count: Int
)

data class IntentLatencyBreakdown(
    val intentType: String,
    val avgTotal: Long,
    val avgRouting: Long,
    val avgToolExec: Long,
    val avgLlm: Long
)

data class IntentFeedbackRate(
    val intentType: String,
    val total: Int,
    val positive: Int,
    val negative: Int
) {
    val positiveRate: Float get() = if (total > 0) positive.toFloat() / total else 0f
}
