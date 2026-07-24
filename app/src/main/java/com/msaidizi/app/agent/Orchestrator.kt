package com.msaidizi.app.agent

import com.msaidizi.app.agent.recovery.TaskCheckpointManager

/**
 * Stub: Main orchestrator for agent processing.
 *
 * The real orchestration is now handled by [com.msaidizi.app.superagent.engine.ReasoningEngine].
 * This stub maintains backward compatibility with MsaidiziApp and RecordViewModel.
 */
class Orchestrator(
    private val intentRouter: IntentRouter? = null,
    private val businessAgent: BusinessAgent? = null,
    private val advisorAgent: AdvisorAgent? = null,
    private val voicePersonality: VoicePersonality? = null,
    private val checkpointManager: TaskCheckpointManager? = null
) {
    fun initialize() {}

    suspend fun processInput(text: String, language: String = "sw"): AgentResponse {
        val router = intentRouter ?: return AgentResponse(text = "Service unavailable", type = ResponseType.ERROR)
        val result = router.classify(text)
        return AgentResponse(
            text = "Nimesikia: $text",
            type = ResponseType.INFORMATION,
            shouldSpeak = true,
            data = result.extractedData
        )
    }

    suspend fun recoverIncompleteTasks(): List<RecoveryTask> = emptyList()
    suspend fun cleanupRecoveryData() {}
}

data class RecoveryTask(
    val checkpoint: com.msaidizi.app.agent.recovery.AgentTaskCheckpoint,
    val action: String = "retry",
    val delayMs: Long = 0
)
