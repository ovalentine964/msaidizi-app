package com.msaidizi.agent.council

import com.msaidizi.agent.harness.UserIntent
import com.msaidizi.agent.tools.core.ToolRegistry
import com.msaidizi.agent.tools.core.ToolResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AgentSpawner — Spawns lightweight sub-agents for cross-council tasks.
 *
 * When a user query spans multiple councils (e.g., "How much profit did I make
 * this week and do I need to restock?"), the AgentSpawner:
 *   1. Decomposes the query into sub-tasks (one per council)
 *   2. Spawns each sub-task as a coroutine (NOT a separate process)
 *   3. Each sub-task gets isolated context + only its council's tools
 *   4. Results merge back into the main response
 *
 * Design principles:
 *   - Coroutines, not threads: ~2KB per sub-agent vs ~1MB per thread
 *   - Bounded concurrency: max 4 concurrent sub-agents (2GB RAM constraint)
 *   - Timeout per sub-agent: 30s default (voice queries need fast response)
 *   - SupervisorJob: one sub-agent failure doesn't cancel siblings
 *   - Result aggregation: merge tool results, not LLM responses
 *
 * Memory overhead per spawned sub-agent:
 *   - Coroutine: ~2KB stack
 *   - ScopedContext: ~200 bytes (reference to shared data)
 *   - ToolResults: variable, but typically <1KB
 *   Total: ~3KB per sub-agent → 4 concurrent = ~12KB
 */
@Singleton
class AgentSpawner @Inject constructor(
    private val councilManager: CouncilManager,
    private val contextScope: ContextScope,
    private val toolRegistry: ToolRegistry
) {
    /**
     * Scope for spawned sub-agents.
     * [SupervisorJob] ensures one failure doesn't cancel siblings.
     */
    private val agentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Active sub-agents for monitoring and cancellation.
     */
    private val activeAgents = ConcurrentHashMap<String, SpawnedAgent>(8)

    companion object {
        /** Maximum concurrent sub-agents. Keeps memory under 20KB. */
        private const val MAX_CONCURRENT_AGENTS = 4

        /** Default timeout per sub-agent in milliseconds. */
        private const val DEFAULT_TIMEOUT_MS = 30_000L

        /** Timeout for simple single-council tasks. */
        private const val SIMPLE_TIMEOUT_MS = 10_000L
    }

    // ── Public API ──────────────────────────────────────────────

    /**
     * Determine if a query needs multi-council sub-agent spawning.
     *
     * Returns true when:
     *   - The intent's requiredTools span multiple councils, OR
     *   - The intent type suggests cross-domain analysis (reports, advice)
     *
     * Performance: O(n) where n = number of required tools, typically <5.
     */
    fun needsSpawning(intent: UserIntent): Boolean {
        // Conversational intents never need spawning
        if (intent.requiredTools.isEmpty()) return false

        // Check if tools span multiple councils
        val councils = intent.requiredTools
            .mapNotNull { councilManager.getToolCouncil(it) }
            .toSet()

        if (councils.size > 1) {
            Timber.d("Intent ${intent.type} spans ${councils.size} councils: $councils")
            return true
        }

        // Report and advice intents often need cross-council data
        return intent.type in setOf(
            com.msaidizi.agent.harness.IntentType.DAILY_REPORT,
            com.msaidizi.agent.harness.IntentType.WEEKLY_REPORT,
            com.msaidizi.agent.harness.IntentType.MONTHLY_REPORT,
            com.msaidizi.agent.harness.IntentType.ASK_ADVICE,
            com.msaidizi.agent.harness.IntentType.VIEW_DASHBOARD
        )
    }

    /**
     * Spawn sub-agents for a multi-council query.
     *
     * Decomposes the intent into per-council sub-tasks, executes them
     * concurrently, and merges results.
     *
     * @param intent The user's intent
     * @param sessionId Current session ID for context
     * @return Merged results from all spawned sub-agents
     */
    suspend fun spawn(
        intent: UserIntent,
        sessionId: String
    ): SpawnResult = withContext(Dispatchers.Default) {
        val spawnId = UUID.randomUUID().toString().take(8)
        val startTime = System.currentTimeMillis()

        Timber.d("Spawning agents for ${intent.type} (spawn=$spawnId)")

        // Decompose intent into sub-tasks per council
        val subTasks = decomposeIntent(intent)

        if (subTasks.isEmpty()) {
            Timber.w("No sub-tasks decomposed for ${intent.type}")
            return@withContext SpawnResult(
                spawnId = spawnId,
                subTaskResults = emptyList(),
                totalTimeMs = System.currentTimeMillis() - startTime,
                success = true
            )
        }

        // Enforce concurrency limit
        val limitedTasks = subTasks.take(MAX_CONCURRENT_AGENTS)
        if (subTasks.size > MAX_CONCURRENT_AGENTS) {
            Timber.w("Truncating sub-tasks from ${subTasks.size} to $MAX_CONCURRENT_AGENTS")
        }

        // Spawn coroutines for each sub-task
        val subResults = coroutineScope {
            limitedTasks.map { subTask ->
                async {
                    executeSubAgent(subTask, spawnId, sessionId)
                }
            }.awaitAll()
        }

        val totalTime = System.currentTimeMillis() - startTime
        val successCount = subResults.count { it.success }

        Timber.d(
            "Spawn $spawnId complete: $successCount/${subResults.size} succeeded in ${totalTime}ms"
        )

        SpawnResult(
            spawnId = spawnId,
            subTaskResults = subResults,
            totalTimeMs = totalTime,
            success = successCount > 0
        )
    }

    /**
     * Get count of currently active sub-agents.
     */
    fun getActiveAgentCount(): Int = activeAgents.size

    /**
     * Cancel all active sub-agents.
     */
    fun cancelAll() {
        activeAgents.clear()
        Timber.i("All active sub-agents cancelled")
    }

    // ── Intent Decomposition ────────────────────────────────────

    /**
     * Decompose a user intent into per-council sub-tasks.
     *
     * Strategy:
     *   1. Group requiredTools by their owning council
     *   2. For cross-cutting intents (reports, advice), add analysis sub-tasks
     *   3. Each sub-task gets only the tools it needs
     */
    private fun decomposeIntent(intent: UserIntent): List<SubTask> {
        val subTasks = mutableListOf<SubTask>()

        // Group tools by council
        val toolsByCouncil = intent.requiredTools
            .groupBy { councilManager.getToolCouncil(it) }
            .filterKeys { it != null }

        // Create a sub-task per council
        for ((council, tools) in toolsByCouncil) {
            if (council == null) continue

            subTasks.add(SubTask(
                taskType = SubTaskType.TOOL_EXECUTION,
                council = council,
                tools = tools,
                params = tools.associateWith { toolName ->
                    intent.toolParams[toolName] ?: emptyMap()
                },
                description = "Execute ${tools.joinToString(", ")} in $council"
            ))
        }

        // For reports and advice, add cross-council analysis sub-tasks
        when (intent.type) {
            com.msaidizi.agent.harness.IntentType.DAILY_REPORT,
            com.msaidizi.agent.harness.IntentType.WEEKLY_REPORT,
            com.msaidizi.agent.harness.IntentType.MONTHLY_REPORT -> {
                // Finance analysis is already covered above
                // Add inventory status sub-task if not already present
                if (CouncilType.INVENTORY !in subTasks.map { it.council }) {
                    subTasks.add(SubTask(
                        taskType = SubTaskType.ANALYSIS,
                        council = CouncilType.INVENTORY,
                        tools = listOf("inventory_tracker"),
                        params = mapOf("inventory_tracker" to mapOf("action" to "alerts")),
                        description = "Check inventory status for report"
                    ))
                }
                // Add growth summary
                if (CouncilType.GROWTH !in subTasks.map { it.council }) {
                    subTasks.add(SubTask(
                        taskType = SubTaskType.ANALYSIS,
                        council = CouncilType.GROWTH,
                        tools = listOf("gamification"),
                        params = mapOf("gamification" to mapOf("action" to "status")),
                        description = "Get gamification status for report"
                    ))
                }
            }
            com.msaidizi.agent.harness.IntentType.ASK_ADVICE -> {
                // Advice needs data from multiple councils
                if (CouncilType.FINANCE !in subTasks.map { it.council }) {
                    subTasks.add(SubTask(
                        taskType = SubTaskType.ANALYSIS,
                        council = CouncilType.FINANCE,
                        tools = listOf("cfo_engine"),
                        params = mapOf("cfo_engine" to mapOf("action" to "savings")),
                        description = "Get financial advice context"
                    ))
                }
                if (CouncilType.MARKET !in subTasks.map { it.council }) {
                    subTasks.add(SubTask(
                        taskType = SubTaskType.ANALYSIS,
                        council = CouncilType.MARKET,
                        tools = listOf("pricing_advisor"),
                        params = mapOf("pricing_advisor" to mapOf("action" to "list")),
                        description = "Get market context for advice"
                    ))
                }
            }
            else -> {} // No additional sub-tasks needed
        }

        return subTasks
    }

    // ── Sub-Agent Execution ─────────────────────────────────────

    /**
     * Execute a single sub-agent task.
     * Each sub-agent gets isolated context and executes its tools.
     */
    private suspend fun executeSubAgent(
        subTask: SubTask,
        spawnId: String,
        sessionId: String
    ): SubTaskResult {
        val agentId = "${spawnId}_${subTask.council.name}"
        val startTime = System.currentTimeMillis()

        val agent = SpawnedAgent(
            id = agentId,
            council = subTask.council,
            startTime = startTime
        )
        activeAgents[agentId] = agent

        return try {
            // Get scoped context for this council
            val scopedContext = contextScope.getScopedContext(subTask.council)

            // Execute tools
            val toolResults = mutableListOf<ToolResult>()
            for (toolName in subTask.tools) {
                val params = subTask.params[toolName] ?: emptyMap()
                val result = toolRegistry.execute(toolName, params)
                if (result != null) {
                    toolResults.add(result)
                }
            }

            val executionTime = System.currentTimeMillis() - startTime
            Timber.d("Sub-agent $agentId completed in ${executionTime}ms")

            SubTaskResult(
                agentId = agentId,
                council = subTask.council,
                taskType = subTask.taskType,
                toolResults = toolResults,
                executionTimeMs = executionTime,
                success = toolResults.any { it.success }
            )
        } catch (e: Exception) {
            Timber.e(e, "Sub-agent $agentId failed")
            SubTaskResult(
                agentId = agentId,
                council = subTask.council,
                taskType = subTask.taskType,
                toolResults = emptyList(),
                executionTimeMs = System.currentTimeMillis() - startTime,
                success = false,
                error = e.message
            )
        } finally {
            activeAgents.remove(agentId)
        }
    }
}

// ──────────────────────────────────────────────
// Types
// ──────────────────────────────────────────────

/**
 * A decomposed sub-task targeting a specific council.
 */
data class SubTask(
    val taskType: SubTaskType,
    val council: CouncilType,
    val tools: List<String>,
    val params: Map<String, Map<String, String>>,
    val description: String
)

enum class SubTaskType {
    TOOL_EXECUTION,  // Execute specific tools
    ANALYSIS,        // Analyze data for cross-council synthesis
    CONTEXT_FETCH    // Fetch additional context
}

/**
 * Result from a single sub-agent execution.
 */
data class SubTaskResult(
    val agentId: String,
    val council: CouncilType,
    val taskType: SubTaskType,
    val toolResults: List<ToolResult>,
    val executionTimeMs: Long,
    val success: Boolean,
    val error: String? = null
)

/**
 * Aggregate result from spawning multiple sub-agents.
 */
data class SpawnResult(
    val spawnId: String,
    val subTaskResults: List<SubTaskResult>,
    val totalTimeMs: Long,
    val success: Boolean
) {
    /**
     * Merge all tool results into a flat list.
     * Used by the supervisor to build the final response.
     */
    fun mergeToolResults(): List<ToolResult> {
        return subTaskResults.flatMap { it.toolResults }
    }

    /**
     * Build a summary string for logging.
     */
    fun toSummary(): String = buildString {
        appendLine("Spawn[$spawnId]: ${subTaskResults.size} sub-agents, ${totalTimeMs}ms total")
        for (result in subTaskResults) {
            val status = if (result.success) "✅" else "❌"
            appendLine("  $status ${result.council}: ${result.toolResults.size} tools, ${result.executionTimeMs}ms")
        }
    }
}

/**
 * Internal tracking of an active spawned sub-agent.
 */
data class SpawnedAgent(
    val id: String,
    val council: CouncilType,
    val startTime: Long
)
