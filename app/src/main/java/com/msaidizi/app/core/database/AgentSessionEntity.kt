package com.msaidizi.app.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for agent sessions (Hermes protocol).
 *
 * Stores session state so that worker sessions survive app kills.
 * On 2GB phones the OS frequently kills background processes;
 * this table lets us resume sessions with full context.
 *
 * @see com.msaidizi.app.agent.hermes.HermesSessionState
 */
@Entity(
    tableName = "agent_sessions",
    indices = [
        Index(value = ["worker_id"], name = "index_agent_sessions_worker_id"),
        Index(value = ["last_active"], name = "index_agent_sessions_last_active"),
        Index(value = ["worker_id", "last_active"], name = "index_agent_sessions_worker_active")
    ]
)
data class AgentSessionEntity(
    @PrimaryKey
    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "worker_id")
    val workerId: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "last_active")
    val lastActive: Long,

    @ColumnInfo(name = "last_channel")
    val lastChannel: String = "app",

    /** Serialized context window (JSON array of ContextEntry) */
    @ColumnInfo(name = "context_window_json")
    val contextWindowJson: String = "[]",

    /** Active trace ID, if any */
    @ColumnInfo(name = "active_trace_id")
    val activeTraceId: String? = null,

    /** Active skill IDs (JSON array) */
    @ColumnInfo(name = "active_skill_ids_json")
    val activeSkillIdsJson: String = "[]",

    @ColumnInfo(name = "last_skill_query")
    val lastSkillQuery: String? = null
)
