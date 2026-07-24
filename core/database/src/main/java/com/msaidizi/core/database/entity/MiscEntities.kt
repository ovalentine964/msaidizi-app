package com.msaidizi.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for proof points (M-KOPA model).
 * Every meaningful interaction generates a proof point.
 */
@Entity(
    tableName = "proof_points",
    indices = [
        Index(value = ["type"]),
        Index(value = ["timestamp"]),
        Index(value = ["is_synced"])
    ]
)
data class ProofPointEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "type")
    val type: String,  // TRANSACTION, GOAL_PROGRESS, CONSISTENCY, etc.

    @ColumnInfo(name = "weight", defaultValue = "1.0")
    val weight: Double = 1.0,

    @ColumnInfo(name = "day_number", defaultValue = "0")
    val dayNumber: Int = 0,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "data_json", defaultValue = "{}")
    val dataJson: String = "{}",

    @ColumnInfo(name = "is_synced", defaultValue = "0")
    val isSynced: Boolean = false,

    @ColumnInfo(name = "synced_at")
    val syncedAt: Long? = null
)

/**
 * Room entity for agent trace (debugging/audit trail).
 * Records every reasoning step for post-mortem analysis.
 */
@Entity(
    tableName = "agent_traces",
    indices = [
        Index(value = ["session_id"]),
        Index(value = ["created_at"])
    ]
)
data class AgentTraceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "session_id")
    val sessionId: String = "",

    @ColumnInfo(name = "input_text", defaultValue = "")
    val inputText: String = "",

    @ColumnInfo(name = "intent", defaultValue = "")
    val intent: String = "",

    @ColumnInfo(name = "confidence", defaultValue = "0.0")
    val confidence: Float = 0f,

    @ColumnInfo(name = "domain", defaultValue = "")
    val domain: String = "",

    @ColumnInfo(name = "response_text", defaultValue = "")
    val responseText: String = "",

    @ColumnInfo(name = "response_type", defaultValue = "")
    val responseType: String = "",

    @ColumnInfo(name = "processing_time_ms", defaultValue = "0")
    val processingTimeMs: Long = 0,

    @ColumnInfo(name = "language", defaultValue = "sw")
    val language: String = "sw",

    @ColumnInfo(name = "dialect", defaultValue = "")
    val dialect: String = "",

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Room entity for gamification data (points, badges, levels).
 */
@Entity(tableName = "gamification")
data class GamificationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "total_points", defaultValue = "0")
    val totalPoints: Int = 0,

    @ColumnInfo(name = "current_level", defaultValue = "1")
    val currentLevel: Int = 1,

    @ColumnInfo(name = "badges_json", defaultValue = "[]")
    val badgesJson: String = "[]",

    @ColumnInfo(name = "current_streak", defaultValue = "0")
    val currentStreak: Int = 0,

    @ColumnInfo(name = "longest_streak", defaultValue = "0")
    val longestStreak: Int = 0,

    @ColumnInfo(name = "last_activity_at")
    val lastActivityAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Room entity for session management.
 */
@Entity(
    tableName = "sessions",
    indices = [
        Index(value = ["started_at"])
    ]
)
data class SessionEntity(
    @PrimaryKey
    val sessionId: String,

    @ColumnInfo(name = "started_at")
    val startedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "ended_at")
    val endedAt: Long? = null,

    @ColumnInfo(name = "turn_count", defaultValue = "0")
    val turnCount: Int = 0,

    @ColumnInfo(name = "language", defaultValue = "sw")
    val language: String = "sw",

    @ColumnInfo(name = "dialect", defaultValue = "")
    val dialect: String = "",

    @ColumnInfo(name = "summary", defaultValue = "")
    val summary: String = ""
)
