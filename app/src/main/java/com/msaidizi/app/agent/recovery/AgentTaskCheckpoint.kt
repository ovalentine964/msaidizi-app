package com.msaidizi.app.agent.recovery

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for persisting in-flight agent task state.
 *
 * Saved at each major step (Observe, Orient, Decide, Act) so that
 * on crash/restart the task can be resumed from the last checkpoint
 * rather than starting from scratch.
 *
 * On 2GB devices, the Android LMK frequently kills background apps.
 * Without checkpointing, all OODA cycles and reasoning traces are lost.
 */
@Entity(
    tableName = "agent_task_checkpoints",
    indices = [
        Index(value = ["state"]),
        Index(value = ["createdAt"]),
        Index(value = ["updatedAt"])
    ]
)
data class AgentTaskCheckpoint(
    @PrimaryKey
    val taskId: String,

    /** What task is being performed (e.g. "process_sale", "daily_summary"). */
    val taskType: String,

    /** Current lifecycle state. */
    val state: TaskState,

    /** The OODA phase the task was in when checkpointed. */
    val lastPhase: String,

    /** JSON-serialized input that triggered this task. */
    val inputJson: String,

    /** JSON-serialized observations collected so far. */
    val observationsJson: String = "{}",

    /** JSON-serialized orientation state snapshot. */
    val orientationJson: String = "{}",

    /** JSON-serialized decision (if decided). */
    val decisionJson: String? = null,

    /** JSON-serialized intermediate results / context. */
    val contextJson: String = "{}",

    /** Which step in a multi-step plan (e.g. "record", "update_stock"). */
    val currentStepId: String? = null,

    /** JSON-serialized completed step results for plan recovery. */
    val completedStepsJson: String = "[]",

    /** Retry count for exponential backoff. */
    val retryCount: Int = 0,

    /** When the task was first created. */
    val createdAt: Long = System.currentTimeMillis(),

    /** When the last checkpoint was saved. */
    val updatedAt: Long = System.currentTimeMillis(),

    /** Error message if the task failed. */
    val lastError: String? = null,

    /** Language the user was speaking. */
    val language: String = "sw"
)

/**
 * Lifecycle states for an agent task.
 */
enum class TaskState {
    /** Task has been created but not yet started processing. */
    PENDING,

    /** Task is actively being processed (mid-OODA cycle). */
    IN_PROGRESS,

    /** Task was saved at a checkpoint and can be resumed. */
    CHECKPOINTED,

    /** Task completed successfully. */
    COMPLETED,

    /** Task failed after exhausting retries. */
    FAILED
}
