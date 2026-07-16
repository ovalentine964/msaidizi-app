package com.msaidizi.app.agent.recovery

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for persisting OODA cycle and ReAct reasoning traces to disk.
 *
 * Traces are saved so they survive app crashes and can be queried for
 * debugging agent behavior on low-memory devices.
 */
@Entity(
    tableName = "agent_recovery_traces",
    indices = [
        Index(value = ["taskId"]),
        Index(value = ["traceType"]),
        Index(value = ["timestamp"]),
        Index(value = ["success"])
    ]
)
data class AgentRecoveryTrace(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** The task this trace belongs to. */
    val taskId: String,

    /** Type of trace: OODA_CYCLE, REACT_TRACE, PLAN_STEP. */
    val traceType: TraceType,

    /** JSON-serialized trace data (steps, observations, decisions, etc.). */
    val traceJson: String,

    /** Whether the traced operation succeeded. */
    val success: Boolean,

    /** Duration in milliseconds. */
    val durationMs: Long,

    /** When the trace was recorded. */
    val timestamp: Long = System.currentTimeMillis(),

    /** Short summary for quick querying. */
    val summary: String = ""
)

/**
 * Types of persisted traces.
 */
enum class TraceType {
    /** A complete OODA cycle (Observe-Orient-Decide-Act). */
    OODA_CYCLE,

    /** A ReAct reasoning chain (think-act-observe-reflect). */
    REACT_TRACE,

    /** An individual plan step execution. */
    PLAN_STEP,

    /** A feedback loop signal. */
    FEEDBACK_SIGNAL
}
