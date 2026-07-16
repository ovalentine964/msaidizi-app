package com.msaidizi.app.agent.recovery

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.msaidizi.app.agent.loops.OodaCycleResult
import com.msaidizi.app.loops.ReActTrace
import kotlinx.coroutines.*
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.math.pow

/**
 * Manages task checkpointing and crash recovery for the agent system.
 *
 * ## Architecture
 *
 * This class sits as a layer ON TOP of the existing OODA loop, ReAct loop,
 * and Plan-Execute loop. It does NOT restructure their core logic — it
 * wraps them with persistence hooks.
 *
 *   ┌─────────────────────────────────────────────┐
 *   │            TaskCheckpointManager             │
 *   │  (persistence layer on top of loops)         │
 *   ├──────────┬──────────┬───────────────────────┤
 *   │ OODA     │ ReAct    │ Plan-Execute          │
 *   │ Loop     │ Loop     │ Loop                  │
 *   └──────────┴──────────┴───────────────────────┘
 *
 * ## Crash Recovery Flow
 *
 * On app startup:
 * 1. Query for tasks in PENDING / IN_PROGRESS / CHECKPOINTED state
 * 2. For each incomplete task, check if the original context is still valid
 * 3. Resume from last checkpoint (not from scratch)
 * 4. Apply exponential backoff if the task has been retried before
 * 5. If context is gone, mark as FAILED and log for user notification
 *
 * ## Checkpoint Lifecycle
 *
 *   processInput() called
 *        │
 *        ▼
 *   createCheckpoint() → state=PENDING
 *        │
 *        ▼
 *   OODA: Observe complete → updateCheckpoint() → state=IN_PROGRESS, phase=OBSERVE
 *        │
 *        ▼
 *   OODA: Orient complete → updateCheckpoint() → phase=ORIENT
 *        │
 *        ▼
 *   OODA: Decide complete → updateCheckpoint() → phase=DECIDE
 *        │
 *        ▼
 *   OODA: Act complete → markCompleted() → state=COMPLETED
 *
 * If crash at any point → task is CHECKPOINTED → resume on restart
 *
 * @param checkpointDao Room DAO for task checkpoints
 * @param traceDao Room DAO for traces
 * @param gson Gson for JSON serialization
 */
class TaskCheckpointManager(
    private val checkpointDao: TaskCheckpointDao,
    private val traceDao: AgentTraceDao,
    private val gson: Gson = Gson()
) {
    /** In-memory cache of active task checkpoints for fast access. */
    private val activeTasks = ConcurrentHashMap<String, AgentTaskCheckpoint>()

    /** Maximum retry attempts before giving up. */
    private val maxRetries = 3

    /** Base delay for exponential backoff (ms). */
    private val baseRetryDelayMs = 1000L

    /** How long to keep completed/failed tasks (7 days). */
    private val cleanupAgeMs = 7 * 24 * 60 * 60 * 1000L

    // ═══════════════ CHECKPOINT LIFECYCLE ═══════════════

    /**
     * Create a new checkpoint for an incoming task.
     * Called at the start of processInput().
     *
     * @return The task ID (for tracking throughout the OODA cycle)
     */
    suspend fun createCheckpoint(
        taskType: String,
        input: Any,
        language: String = "sw"
    ): String {
        val taskId = UUID.randomUUID().toString().take(16)
        val checkpoint = AgentTaskCheckpoint(
            taskId = taskId,
            taskType = taskType,
            state = TaskState.PENDING,
            lastPhase = "INIT",
            inputJson = gson.toJson(input),
            language = language
        )
        checkpointDao.saveCheckpoint(checkpoint)
        activeTasks[taskId] = checkpoint
        Timber.d("Checkpoint created: %s (type=%s)", taskId, taskType)
        return taskId
    }

    /**
     * Update checkpoint after an OODA phase completes.
     * Called at each phase boundary (Observe, Orient, Decide, Act).
     */
    suspend fun updateCheckpoint(
        taskId: String,
        phase: String,
        observations: Map<String, Any>? = null,
        orientation: Map<String, Double>? = null,
        decision: Any? = null,
        context: Map<String, Any>? = null
    ) {
        val existing = checkpointDao.getByTaskId(taskId) ?: return
        val updated = existing.copy(
            state = TaskState.IN_PROGRESS,
            lastPhase = phase,
            observationsJson = observations?.let { gson.toJson(it) } ?: existing.observationsJson,
            orientationJson = orientation?.let { gson.toJson(it) } ?: existing.orientationJson,
            decisionJson = decision?.let { gson.toJson(it) } ?: existing.decisionJson,
            contextJson = context?.let { gson.toJson(it) } ?: existing.contextJson,
            updatedAt = System.currentTimeMillis()
        )
        checkpointDao.saveCheckpoint(updated)
        activeTasks[taskId] = updated
        Timber.d("Checkpoint updated: %s → phase=%s", taskId, phase)
    }

    /**
     * Update checkpoint for a plan step (Plan-Execute loop integration).
     */
    suspend fun updatePlanStep(
        taskId: String,
        stepId: String,
        completedSteps: List<Map<String, Any>>
    ) {
        val existing = checkpointDao.getByTaskId(taskId) ?: return
        val updated = existing.copy(
            currentStepId = stepId,
            completedStepsJson = gson.toJson(completedSteps),
            state = TaskState.IN_PROGRESS,
            updatedAt = System.currentTimeMillis()
        )
        checkpointDao.saveCheckpoint(updated)
        activeTasks[taskId] = updated
    }

    /**
     * Mark a task as completed. Removes from active cache.
     */
    suspend fun markCompleted(taskId: String) {
        checkpointDao.markCompleted(taskId)
        activeTasks.remove(taskId)
        Timber.d("Checkpoint completed: %s", taskId)
    }

    /**
     * Mark a task as failed.
     */
    suspend fun markFailed(taskId: String, error: String?) {
        checkpointDao.markFailed(taskId, error)
        activeTasks.remove(taskId)
        Timber.w("Checkpoint failed: %s — %s", taskId, error)
    }

    /**
     * Remove a checkpoint (e.g. after successful recovery or user cancel).
     */
    suspend fun removeCheckpoint(taskId: String) {
        checkpointDao.markCompleted(taskId)
        activeTasks.remove(taskId)
    }

    // ═══════════════ CRASH RECOVERY ═══════════════

    /**
     * Check for incomplete tasks on app startup.
     * Returns tasks that can be resumed.
     *
     * Should be called from MsaidiziApp.onCreate() or MainActivity.onCreate().
     */
    suspend fun recoverIncompleteTasks(): List<RecoveryTask> {
        val incomplete = checkpointDao.getIncompleteTasks()
        if (incomplete.isEmpty()) {
            Timber.d("No incomplete tasks found on startup")
            return emptyList()
        }

        Timber.i("Found %d incomplete tasks on startup", incomplete.size)

        val recoveryTasks = mutableListOf<RecoveryTask>()
        for (task in incomplete) {
            when {
                // Already in progress — was checkpointed mid-cycle
                task.state == TaskState.IN_PROGRESS || task.state == TaskState.CHECKPOINTED -> {
                    if (task.retryCount >= maxRetries) {
                        Timber.w("Task %s exceeded max retries (%d), marking failed",
                            task.taskId, maxRetries)
                        checkpointDao.markFailed(task.taskId, "Exceeded max retries ($maxRetries)")
                        continue
                    }
                    recoveryTasks.add(RecoveryTask(
                        checkpoint = task,
                        action = RecoveryAction.RESUME,
                        delayMs = computeBackoff(task.retryCount)
                    ))
                }
                // Pending — never started
                task.state == TaskState.PENDING -> {
                    // Check if the task is stale (> 30 min old)
                    val ageMs = System.currentTimeMillis() - task.createdAt
                    if (ageMs > 30 * 60 * 1000) {
                        Timber.w("Task %s is stale (%d ms old), marking failed",
                            task.taskId, ageMs)
                        checkpointDao.markFailed(task.taskId, "Stale task (>30min)")
                        continue
                    }
                    recoveryTasks.add(RecoveryTask(
                        checkpoint = task,
                        action = RecoveryAction.RESTART,
                        delayMs = 0
                    ))
                }
            }
        }

        // Increment retry count for tasks we're about to resume
        for (rt in recoveryTasks) {
            checkpointDao.incrementRetry(rt.checkpoint.taskId)
        }

        return recoveryTasks
    }

    /**
     * Compute exponential backoff delay for a retry attempt.
     * delay = baseDelay * 2^retryCount, capped at 30 seconds.
     */
    fun computeBackoff(retryCount: Int): Long {
        return min(
            baseRetryDelayMs * 2.0.pow(retryCount).toLong(),
            30_000L
        )
    }

    /**
     * Check if a task's original context is still valid.
     * Returns false if the context can't be reconstructed.
     */
    fun isContextValid(checkpoint: AgentTaskCheckpoint): Boolean {
        // Basic validity: input must be non-empty
        if (checkpoint.inputJson.isBlank() || checkpoint.inputJson == "{}") {
            return false
        }
        // Task must not be too old (> 1 hour)
        val ageMs = System.currentTimeMillis() - checkpoint.createdAt
        if (ageMs > 60 * 60 * 1000) {
            return false
        }
        return true
    }

    /**
     * Deserialize the saved input from a checkpoint.
     */
    fun <T> deserializeInput(checkpoint: AgentTaskCheckpoint, clazz: Class<T>): T? {
        return try {
            gson.fromJson(checkpoint.inputJson, clazz)
        } catch (e: Exception) {
            Timber.w(e, "Failed to deserialize input for task %s", checkpoint.taskId)
            null
        }
    }

    /**
     * Deserialize the saved observations from a checkpoint.
     */
    fun deserializeObservations(checkpoint: AgentTaskCheckpoint): Map<String, Any> {
        return try {
            val type = object : TypeToken<Map<String, Any>>() {}.type
            gson.fromJson(checkpoint.observationsJson, type) ?: emptyMap()
        } catch (e: Exception) {
            Timber.w(e, "Failed to deserialize observations for task %s", checkpoint.taskId)
            emptyMap()
        }
    }

    /**
     * Deserialize the saved orientation from a checkpoint.
     */
    fun deserializeOrientation(checkpoint: AgentTaskCheckpoint): Map<String, Double> {
        return try {
            val type = object : TypeToken<Map<String, Double>>() {}.type
            gson.fromJson(checkpoint.orientationJson, type) ?: emptyMap()
        } catch (e: Exception) {
            Timber.w(e, "Failed to deserialize orientation for task %s", checkpoint.taskId)
            emptyMap()
        }
    }

    // ═══════════════ TRACE PERSISTENCE ═══════════════

    /**
     * Persist an OODA cycle result as a trace.
     */
    suspend fun persistOodaTrace(taskId: String, result: OodaCycleResult) {
        val entity = AgentRecoveryTrace(
            taskId = taskId,
            traceType = TraceType.OODA_CYCLE,
            traceJson = gson.toJson(result),
            success = result.success,
            durationMs = result.totalMs,
            summary = "OODA cycle #${result.cycleNumber}: " +
                    if (result.escalated) "escalated" else if (result.success) "success" else "failed"
        )
        traceDao.insert(entity)
    }

    /**
     * Persist a ReAct reasoning trace to disk.
     */
    suspend fun persistReActTrace(taskId: String, trace: ReActTrace) {
        val entity = AgentRecoveryTrace(
            taskId = taskId,
            traceType = TraceType.REACT_TRACE,
            traceJson = gson.toJson(trace.toMap()),
            success = trace.success,
            durationMs = trace.durationMs(),
            summary = "${trace.task}: ${trace.steps.size} steps, " +
                    if (trace.success) "success" else "failed"
        )
        traceDao.insert(entity)
    }

    /**
     * Persist a plan step execution trace.
     */
    suspend fun persistPlanStepTrace(
        taskId: String,
        stepId: String,
        success: Boolean,
        result: Map<String, Any>?,
        error: String?,
        durationMs: Long
    ) {
        val entity = AgentRecoveryTrace(
            taskId = taskId,
            traceType = TraceType.PLAN_STEP,
            traceJson = gson.toJson(mapOf(
                "stepId" to stepId,
                "success" to success,
                "result" to (result ?: emptyMap()),
                "error" to (error ?: "")
            )),
            success = success,
            durationMs = durationMs,
            summary = "Step $stepId: ${if (success) "ok" else "fail"} (${durationMs}ms)"
        )
        traceDao.insert(entity)
    }

    /**
     * Query traces for debugging.
     */
    suspend fun getTracesForTask(taskId: String): List<AgentRecoveryTrace> {
        return traceDao.getByTaskId(taskId)
    }

    /**
     * Get recent traces across all tasks.
     */
    suspend fun getRecentTraces(limit: Int = 50): List<AgentRecoveryTrace> {
        return traceDao.getRecent(limit)
    }

    /**
     * Get failed traces for debugging.
     */
    suspend fun getFailedTraces(limit: Int = 20): List<AgentRecoveryTrace> {
        return traceDao.getFailed(limit)
    }

    /**
     * Get traces since a given timestamp.
     */
    suspend fun getTracesSince(since: Long): List<AgentRecoveryTrace> {
        return traceDao.getSince(since)
    }

    // ═══════════════ MAINTENANCE ═══════════════

    /**
     * Clean up old completed/failed checkpoints and traces.
     * Should be called periodically (e.g. during heartbeat or on app start).
     */
    suspend fun cleanup() {
        val cutoff = System.currentTimeMillis() - cleanupAgeMs
        checkpointDao.cleanup(cutoff)
        traceDao.cleanup(cutoff)
        Timber.d("Recovery cleanup: removed records older than %d ms", cleanupAgeMs)
    }

    /**
     * Get health stats for monitoring.
     */
    suspend fun getStats(): RecoveryStats {
        val incomplete = checkpointDao.countIncomplete()
        val traceCount = traceDao.count()
        return RecoveryStats(
            incompleteTasks = incomplete,
            totalTraces = traceCount,
            activeCachedTasks = activeTasks.size
        )
    }
}

/**
 * A task that needs to be recovered on startup.
 */
data class RecoveryTask(
    val checkpoint: AgentTaskCheckpoint,
    val action: RecoveryAction,
    val delayMs: Long
)

/**
 * What to do with a recovered task.
 */
enum class RecoveryAction {
    /** Resume from the last checkpoint. */
    RESUME,

    /** Restart from scratch (task never started). */
    RESTART
}

/**
 * Recovery system health stats.
 */
data class RecoveryStats(
    val incompleteTasks: Int,
    val totalTraces: Int,
    val activeCachedTasks: Int
)
