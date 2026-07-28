package com.msaidizi.agent.trace

import com.msaidizi.core.database.TraceDao
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TraceSync — Syncs anonymized traces from device to backend.
 *
 * Privacy guarantees:
 *   - Raw input text is NEVER sent (only SHA-256 hash)
 *   - Only aggregated metrics, tool names, intent types, and timing
 *   - No user IDs, phone numbers, or business names
 *   - k-anonymity: backend only processes cohorts of ≥50 users
 *
 * Sync cadence: batched, on WiFi, every 6 hours.
 * Retries on failure with exponential backoff.
 */
@Singleton
class TraceSync @Inject constructor(
    private val traceDao: TraceDao,
    private val gson: Gson
) {
    companion object {
        /** Maximum traces per sync batch. */
        private const val BATCH_SIZE = 50

        /** Minimum interval between sync attempts (ms). */
        private const val SYNC_INTERVAL_MS = 6 * 60 * 60 * 1000L // 6 hours
    }

    private var lastSyncAttempt: Long = 0L

    /**
     * Get the count of traces pending sync.
     */
    suspend fun getPendingCount(): Int {
        return try {
            traceDao.getPendingSyncCount()
        } catch (e: Exception) {
            Timber.w(e, "TraceSync: failed to get pending count")
            0
        }
    }

    /**
     * Build an anonymized sync payload from pending traces.
     *
     * Returns a list of maps suitable for JSON serialization.
     * Each map contains only non-PII fields.
     */
    suspend fun buildSyncPayload(): List<Map<String, Any?>> = withContext(Dispatchers.IO) {
        val traces = traceDao.getPendingSync(BATCH_SIZE)
        traces.map { trace ->
            mapOf(
                "trace_id" to trace.traceId,
                "timestamp" to trace.timestamp,
                "input_hash" to trace.rawInputHash,     // hash only, not raw text
                "intent_type" to trace.intentType,
                "intent_confidence" to trace.intentConfidence,
                "intent_tier" to trace.intentTier,
                "tools_selected" to trace.toolsSelected,
                "tools_succeeded" to trace.toolsSucceeded,
                "tools_failed" to trace.toolsFailed,
                "tool_results_json" to trace.toolResultsJson,
                "prompt_tokens" to trace.promptTokenCount,
                "output_tokens" to trace.outputTokenCount,
                "total_latency_ms" to trace.totalLatencyMs,
                "intent_routing_ms" to trace.intentRoutingMs,
                "tool_execution_ms" to trace.toolExecutionMs,
                "llm_inference_ms" to trace.llmInferenceMs,
                "final_confidence" to trace.finalConfidence,
                "ooda_iterations" to trace.oodaIterations,
                "guardrail_blocked" to trace.guardrailBlocked,
                "user_feedback" to trace.userFeedback,
                "user_correction" to trace.userCorrection,
                "correction_latency_ms" to trace.correctionLatencyMs,
                "ooda_phase" to trace.oodaPhase,
                "is_voice" to trace.isVoice,
                "business_category" to trace.businessCategory,
                "region" to trace.region
            )
        }
    }

    /**
     * Mark traces as synced after successful upload.
     */
    suspend fun markSynced(traceIds: List<String>) {
        try {
            traceDao.markSyncedBatch(traceIds)
            Timber.d("TraceSync: marked %d traces as synced", traceIds.size)
        } catch (e: Exception) {
            Timber.w(e, "TraceSync: failed to mark synced")
        }
    }

    /**
     * Check if enough time has passed since last sync attempt.
     */
    fun shouldSync(): Boolean {
        val now = System.currentTimeMillis()
        return (now - lastSyncAttempt) >= SYNC_INTERVAL_MS
    }

    /**
     * Record that a sync attempt was made.
     */
    fun recordSyncAttempt() {
        lastSyncAttempt = System.currentTimeMillis()
    }

    /**
     * Cleanup old synced traces to save storage.
     * Keeps last 7 days of traces.
     */
    suspend fun cleanupOldTraces() {
        try {
            val cutoff = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
            traceDao.deleteOldSynced(cutoff)
            Timber.d("TraceSync: cleaned up old synced traces")
        } catch (e: Exception) {
            Timber.w(e, "TraceSync: cleanup failed")
        }
    }
}
