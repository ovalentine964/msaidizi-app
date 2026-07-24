package com.msaidizi.app.agent.recovery

import timber.log.Timber

/**
 * Manages agent task checkpoints for crash recovery.
 *
 * When the app is killed mid-task (common on 2GB devices),
 * this manager saves checkpoint state and restores it on restart.
 */
class TaskCheckpointManager(
    private val checkpointDao: TaskCheckpointDao,
    private val traceDao: AgentTraceDao
) {
    companion object {
        private const val TAG = "TaskCheckpointManager"
    }

    /**
     * Get all incomplete tasks that need recovery.
     */
    suspend fun getIncompleteTasks(): List<AgentTaskCheckpoint> {
        return checkpointDao.getIncompleteTasks()
    }

    /**
     * Clean up old completed checkpoints and traces.
     */
    suspend fun cleanup(olderThanMs: Long = 7 * 24 * 60 * 60 * 1000L) {
        val cutoff = System.currentTimeMillis() - olderThanMs
        checkpointDao.cleanupOlderThan(cutoff)
        traceDao.cleanupOlderThan(cutoff)
    }
}
