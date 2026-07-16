package com.msaidizi.app.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for agent reasoning traces.
 *
 * Stores OODA cycles, ReAct steps, and other reasoning artifacts
 * so the closed learning loop can resume after an app kill.
 * Each trace belongs to a session and records the step-by-step
 * reasoning that led to a response.
 *
 * @see com.msaidizi.app.agent.hermes.TraceStep
 */
@Entity(
    tableName = "agent_traces",
    foreignKeys = [
        ForeignKey(
            entity = AgentSessionEntity::class,
            parentColumns = ["session_id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["session_id"], name = "index_agent_traces_session_id"),
        Index(value = ["trace_id"], name = "index_agent_traces_trace_id"),
        Index(value = ["created_at"], name = "index_agent_traces_created_at"),
        Index(value = ["session_id", "trace_id"], name = "index_agent_traces_session_trace")
    ]
)
data class AgentTraceEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "session_id")
    val sessionId: String,

    /** Groups steps belonging to the same interaction */
    @ColumnInfo(name = "trace_id")
    val traceId: String,

    /** Step index within the trace (0-based) */
    @ColumnInfo(name = "step_index")
    val stepIndex: Int,

    @ColumnInfo(name = "action")
    val action: String,

    @ColumnInfo(name = "tool_used")
    val toolUsed: String? = null,

    @ColumnInfo(name = "success")
    val success: Boolean = true,

    @ColumnInfo(name = "error")
    val error: String? = null,

    @ColumnInfo(name = "duration_ms")
    val durationMs: Long = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
