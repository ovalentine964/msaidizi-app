package com.msaidizi.app.agent.recovery

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for agent task checkpoints.
 *
 * Stores checkpoint state so tasks can be resumed after app kills.
 * Part of the crash recovery system.
 */
@Entity(tableName = "agent_task_checkpoints")
data class AgentTaskCheckpoint(
    @PrimaryKey
    @ColumnInfo(name = "task_id")
    val taskId: String,

    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "phase")
    val phase: String,

    @ColumnInfo(name = "input_json")
    val inputJson: String,

    @ColumnInfo(name = "state")
    val state: TaskState = TaskState.PENDING,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Room entity for agent recovery traces.
 *
 * Records reasoning steps for post-mortem analysis and learning.
 */
@Entity(tableName = "agent_recovery_traces")
data class AgentRecoveryTrace(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "task_id")
    val taskId: String,

    @ColumnInfo(name = "trace_type")
    val traceType: TraceType,

    @ColumnInfo(name = "step")
    val step: String,

    @ColumnInfo(name = "detail")
    val detail: String = "",

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)

enum class TaskState {
    PENDING, RUNNING, COMPLETED, FAILED, CANCELLED
}

enum class TraceType {
    OODA_CYCLE, REACT_STEP, ERROR, RECOVERY
}
