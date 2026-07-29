package com.msaidizi.core.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for persisting agent traces locally.
 *
 * Traces are stored on-device in the Room database and synced
 * (anonymized) to the backend for aggregate analysis.
 *
 * Index strategy:
 *   - timestamp: for time-range queries during analysis
 *   - intentType: for "which intents have low success rate" queries
 *   - needsSync: for efficient sync polling
 */
@Entity(
    tableName = "agent_traces",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["intentType"]),
        Index(value = ["needsSync"])
    ]
)
data class TraceEntity(
    @PrimaryKey val traceId: String,

    val sessionId: String,
    val timestamp: Long,

    // Intent classification
    val rawInputHash: String,         // SHA-256 hash of raw input (privacy)
    val intentType: String,           // IntentType enum name
    val intentConfidence: Float,
    val intentTier: String,           // IntentTier enum name

    // Tool selection
    val toolsSelected: String,        // JSON array of tool names
    val toolsSucceeded: Int,
    val toolsFailed: Int,

    // Tool execution details
    val toolResultsJson: String,      // JSON array of ToolExecutionTrace

    // LLM inference
    val promptTokenCount: Int,
    val outputTokenCount: Int,
    val llmResponseSummary: String,   // truncated to 500 chars

    // Timing
    val totalLatencyMs: Long,
    val intentRoutingMs: Long,
    val toolExecutionMs: Long,
    val llmInferenceMs: Long,

    // Quality signals
    val finalConfidence: Float,
    val oodaIterations: Int,
    val guardrailBlocked: Boolean,

    // User feedback (post-hoc, updated later)
    val userFeedback: Int? = null,     // null=unrated, 1=positive, 0=negative
    val userCorrection: String? = null,
    val correctionLatencyMs: Long? = null,

    // Context
    val oodaPhase: String,
    val isVoice: Boolean,
    val businessCategory: String? = null,
    val region: String? = null,

    // Sync state
    val needsSync: Boolean = true,
    val syncedAt: Long? = null
)
