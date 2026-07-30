package com.msaidizi.agent.graph

import com.msaidizi.agent.tools.Tool
import com.msaidizi.agent.tools.ToolRegistry
import com.msaidizi.agent.tools.ToolResult
import kotlinx.coroutines.*
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ToolGraph — Directed graph of tool dependencies replacing the flat ToolRegistry.
 *
 * Tools declare edges: DEPENDENCY (must run before), TRIGGER (runs after),
 * FEEDS_INTO (passes output as input). The graph enables:
 *   - Topological sort for execution ordering
 *   - Parallel execution of independent tools
 *   - Conditional routing based on intermediate results
 *   - Cycle detection to prevent infinite loops
 *
 * Backward-compatible: wraps existing ToolRegistry and delegates execution.
 *
 * Memory footprint: O(V + E) adjacency lists, ~2KB for 40 tools + 80 edges.
 * All operations are O(V + E), no O(V²) algorithms.
 */
@Singleton
class ToolGraph @Inject constructor(
    private val toolRegistry: ToolRegistry
) {
    // ── Graph storage: adjacency lists ──────────────────────────────

    /** Node = tool name. Adjacency list of outgoing edges. */
    private val edges = ConcurrentHashMap<String, MutableList<ToolEdge>>()

    /** Reverse adjacency: who depends on me? */
    private val reverseEdges = ConcurrentHashMap<String, MutableList<ToolEdge>>()

    /** Tool metadata: execution hints, concurrency group. */
    private val toolMeta = ConcurrentHashMap<String, ToolNodeMeta>()

    // ═══════════════════════════════════════════════════════════════
    //  GRAPH CONSTRUCTION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Register a tool as a node in the graph.
     * Backward-compatible with flat ToolRegistry.
     *
     * If the tool is already registered, only metadata is updated (no overwrite).
     */
    fun registerNode(tool: Tool, meta: ToolNodeMeta = ToolNodeMeta()) {
        if (!toolRegistry.hasTool(tool.name)) {
            toolRegistry.register(tool)
        } else {
            Timber.d("ToolGraph: tool '${tool.name}' already registered, skipping re-register")
        }
        edges.computeIfAbsent(tool.name) { mutableListOf() }
        reverseEdges.computeIfAbsent(tool.name) { mutableListOf() }
        toolMeta[tool.name] = meta
        Timber.d("ToolGraph: node registered '${tool.name}' (group=${meta.concurrencyGroup})")
    }

    /**
     * Set metadata for an already-registered tool node without overwriting the tool.
     * Use when the tool is registered elsewhere and you only need to set concurrency/group hints.
     */
    fun setNodeMeta(toolName: String, meta: ToolNodeMeta) {
        edges.computeIfAbsent(toolName) { mutableListOf() }
        reverseEdges.computeIfAbsent(toolName) { mutableListOf() }
        toolMeta[toolName] = meta
        Timber.d("ToolGraph: metadata set for '$toolName' (group=${meta.concurrencyGroup})")
    }

    /**
     * Add a directed edge between two tools.
     * Both tools must already be registered.
     */
    fun addEdge(from: String, to: String, type: EdgeType, condition: String? = null) {
        require(toolRegistry.hasTool(from)) { "Tool '$from' not registered" }
        require(toolRegistry.hasTool(to)) { "Tool '$to' not registered" }

        val edge = ToolEdge(from, to, type, condition)
        edges.getOrPut(from) { mutableListOf() }.add(edge)
        reverseEdges.getOrPut(to) { mutableListOf() }.add(ToolEdge(to, from, type.opposite(), condition))

        Timber.d("ToolGraph: edge $from --[$type]--> $to")
    }

    // ═══════════════════════════════════════════════════════════════
    //  TOPOLOGICAL SORT — Execution Order
    // ═══════════════════════════════════════════════════════════════

    /**
     * Compute execution order for a set of tools using Kahn's algorithm.
     * Returns layers: tools in the same layer can run in parallel.
     * O(V + E) time complexity.
     *
     * @param toolNames The tools that need to be executed.
     * @return List of execution layers (each layer = parallel-executable tools).
     * @throws IllegalStateException if a cycle is detected.
     */
    fun topologicalLayers(toolNames: Set<String>): List<List<String>> {
        // Build subgraph induced by toolNames
        val subgraph = mutableMapOf<String, MutableSet<String>>()
        val inDegree = mutableMapOf<String, Int>()

        for (name in toolNames) {
            subgraph.getOrPut(name) { mutableSetOf() }
            inDegree.getOrPut(name) { 0 }
        }

        // Only include edges where both endpoints are in toolNames
        for (name in toolNames) {
            for (edge in edges[name].orEmpty()) {
                if (edge.to in toolNames && edge.type == EdgeType.DEPENDENCY) {
                    subgraph.getOrPut(edge.from) { mutableSetOf() }.add(edge.to)
                    inDegree[edge.to] = (inDegree[edge.to] ?: 0) + 1
                }
            }
        }

        // Kahn's algorithm: BFS from zero-indegree nodes
        val queue = ArrayDeque<String>()
        for ((node, deg) in inDegree) {
            if (deg == 0) queue.add(node)
        }

        val layers = mutableListOf<List<String>>()
        val visited = mutableSetOf<String>()

        while (queue.isNotEmpty()) {
            val layerSize = queue.size
            val layer = mutableListOf<String>()

            for (i in 0 until layerSize) {
                val node = queue.removeFirst()
                layer.add(node)
                visited.add(node)

                for (neighbor in subgraph[node].orEmpty()) {
                    val newDeg = (inDegree[neighbor] ?: 1) - 1
                    inDegree[neighbor] = newDeg
                    if (newDeg == 0) queue.add(neighbor)
                }
            }

            if (layer.isNotEmpty()) layers.add(layer)
        }

        // Cycle detection
        if (visited.size != toolNames.size) {
            val cycle = toolNames - visited
            throw IllegalStateException("Cycle detected among tools: $cycle")
        }

        return layers
    }

    // ═══════════════════════════════════════════════════════════════
    //  GRAPH EXECUTION — Parallel with Dependency Resolution
    // ═══════════════════════════════════════════════════════════════

    /**
     * Execute a set of tools respecting dependency order.
     * Independent tools in the same layer run in parallel via coroutines.
     *
     * @param toolNames Tools to execute.
     * @param initialParams Per-tool parameters.
     * @param scope Coroutine scope for parallel execution.
     * @return Map of tool name → result. Failed tools get error results, don't abort siblings.
     */
    suspend fun executeGraph(
        toolNames: Set<String>,
        initialParams: Map<String, Map<String, String>> = emptyMap(),
        scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
    ): Map<String, ToolResult> {
        if (toolNames.isEmpty()) return emptyMap()

        val layers = try {
            topologicalLayers(toolNames)
        } catch (e: IllegalStateException) {
            Timber.e(e, "ToolGraph: cycle detected, falling back to sequential")
            return executeSequential(toolNames, initialParams)
        }

        val results = ConcurrentHashMap<String, ToolResult>()
        // Accumulate params: tools can receive outputs from dependencies
        val accumulatedParams = ConcurrentHashMap<String, MutableMap<String, String>>()

        // Initialize params
        for (name in toolNames) {
            accumulatedParams[name] = initialParams[name]?.toMutableMap() ?: mutableMapOf()
        }

        for ((layerIdx, layer) in layers.withIndex()) {
            Timber.d("ToolGraph: executing layer %d with %d tools: %s", layerIdx, layer.size, layer)

            // All tools in this layer can run in parallel
            val jobs = layer.map { toolName ->
                scope.async {
                    // Merge dependency outputs into params
                    val params = accumulatedParams[toolName] ?: mutableMapOf()
                    for (dep in getDependencies(toolName)) {
                        val depResult = results[dep]
                        if (depResult != null && depResult.success) {
                            // Pass dependency output as input param
                            params["${dep}_result"] = depResult.message
                            (depResult.data as? Map<*, *>)?.forEach { (k, v) ->
                                params["${dep}_$k"] = v.toString()
                            }
                        }
                    }

                    // Concurrency group: serialize tools in same group
                    val meta = toolMeta[toolName]
                    if (meta?.concurrencyGroup != null) {
                        getConcurrencyLock(meta.concurrencyGroup).lock()
                    }

                    try {
                        val result = toolRegistry.execute(toolName, params)
                        if (result != null) {
                            results[toolName] = result

                            // Check conditional routing
                            if (result.success) {
                                handleConditionalRouting(toolName, result, results, accumulatedParams)
                            }
                        } else {
                            results[toolName] = ToolResult.error(toolName, "Tool not found", "TOOL_NOT_FOUND")
                        }
                    } finally {
                        meta?.concurrencyGroup?.let { getConcurrencyLock(it).unlock() }
                    }
                }
            }

            // Wait for all tools in this layer
            jobs.awaitAll()

            // Short-circuit: if any REQUIRED tool failed, abort remaining layers
            val requiredFailures = layer.filter { name ->
                toolMeta[name]?.required == true && results[name]?.success == false
            }
            if (requiredFailures.isNotEmpty()) {
                Timber.w("ToolGraph: required tools failed: $requiredFailures — aborting remaining layers")
                break
            }
        }

        return results
    }

    /**
     * Execute a single tool by name (backward-compatible with flat registry).
     */
    suspend fun executeSingle(toolName: String, params: Map<String, String>): ToolResult? {
        return toolRegistry.execute(toolName, params)
    }

    // ═══════════════════════════════════════════════════════════════
    //  GRAPH QUERIES
    // ═══════════════════════════════════════════════════════════════

    /** Get all tools that [toolName] depends on (direct + transitive). */
    fun getDependencies(toolName: String): Set<String> {
        val result = mutableSetOf<String>()
        val queue = ArrayDeque<String>()

        for (edge in reverseEdges[toolName].orEmpty()) {
            if (edge.type == EdgeType.DEPENDENCY) {
                queue.add(edge.from)
                result.add(edge.from)
            }
        }

        // BFS for transitive dependencies
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (edge in reverseEdges[current].orEmpty()) {
                if (edge.type == EdgeType.DEPENDENCY && edge.from !in result) {
                    result.add(edge.from)
                    queue.add(edge.from)
                }
            }
        }
        return result
    }

    /** Get all tools triggered by [toolName] (direct). */
    fun getTriggers(toolName: String): List<String> {
        return edges[toolName].orEmpty()
            .filter { it.type == EdgeType.TRIGGER }
            .map { it.to }
    }

    /** Get all tools that feed into [toolName]. */
    fun getFeedsInto(toolName: String): List<String> {
        return reverseEdges[toolName].orEmpty()
            .filter { it.type == EdgeType.FEEDS_INTO }
            .map { it.from }
    }

    /** Check if adding an edge would create a cycle. Uses DFS. */
    fun wouldCreateCycle(from: String, to: String): Boolean {
        if (from == to) return true
        val visited = mutableSetOf<String>()
        val stack = ArrayDeque<String>()
        stack.add(to)

        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            if (current == from) return true
            if (current in visited) continue
            visited.add(current)

            for (edge in edges[current].orEmpty()) {
                if (edge.type == EdgeType.DEPENDENCY) {
                    stack.add(edge.to)
                }
            }
        }
        return false
    }

    /** Get graph stats for diagnostics. */
    fun getStats(): GraphStats {
        val nodeCount = edges.size
        val edgeCount = edges.values.sumOf { it.size }
        val groups = toolMeta.values.mapNotNull { it.concurrencyGroup }.toSet()
        return GraphStats(nodeCount, edgeCount, groups.size)
    }

    // ═══════════════════════════════════════════════════════════════
    //  PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════════

    /** Fallback: execute tools one by one when topological sort fails. */
    private suspend fun executeSequential(
        toolNames: Set<String>,
        params: Map<String, Map<String, String>>
    ): Map<String, ToolResult> {
        val results = mutableMapOf<String, ToolResult>()
        for (name in toolNames) {
            val result = toolRegistry.execute(name, params[name] ?: emptyMap())
            results[name] = result ?: ToolResult.error(name, "Tool not found", "TOOL_NOT_FOUND")
        }
        return results
    }

    /** Handle conditional edges: if result matches condition, add dependent tools. */
    private suspend fun handleConditionalRouting(
        toolName: String,
        result: ToolResult,
        results: ConcurrentHashMap<String, ToolResult>,
        accumulatedParams: ConcurrentHashMap<String, MutableMap<String, String>>
    ) {
        for (edge in edges[toolName].orEmpty()) {
            if (edge.type == EdgeType.CONDITIONAL && edge.condition != null) {
                val shouldTrigger = evaluateCondition(edge.condition, result)
                if (shouldTrigger) {
                    Timber.d("ToolGraph: conditional routing %s → %s (condition: %s)", toolName, edge.to, edge.condition)
                    // Execute the conditional target
                    val params = accumulatedParams[edge.to] ?: mutableMapOf()
                    params["${toolName}_result"] = result.message
                    val conditionalResult = toolRegistry.execute(edge.to, params)
                    if (conditionalResult != null) {
                        results[edge.to] = conditionalResult
                    }
                }
            }
        }
    }

    /** Simple condition evaluator for conditional edges. */
    private fun evaluateCondition(condition: String, result: ToolResult): Boolean {
        return when {
            condition == "success" -> result.success
            condition == "failure" -> !result.success
            condition.startsWith("contains:") -> {
                val target = condition.removePrefix("contains:")
                result.message.contains(target, ignoreCase = true)
            }
            condition.startsWith("data_has:") -> {
                val key = condition.removePrefix("data_has:")
                (result.data as? Map<*, *>)?.containsKey(key) == true
            }
            else -> {
                Timber.w("ToolGraph: unknown condition: $condition")
                false
            }
        }
    }

    /** Concurrency group locks (prevents parallel writes to same resource). */
    private val concurrencyLocks = ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock>()

    private fun getConcurrencyLock(group: String): java.util.concurrent.locks.ReentrantLock {
        return concurrencyLocks.computeIfAbsent(group) { java.util.concurrent.locks.ReentrantLock() }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  DATA CLASSES
// ═══════════════════════════════════════════════════════════════════

/**
 * Types of edges in the tool graph.
 */
enum class EdgeType {
    /** Tool A must complete before Tool B can run. */
    DEPENDENCY,
    /** Tool A's output triggers Tool B (fire-and-forget side effect). */
    TRIGGER,
    /** Tool A's output feeds into Tool B as input. */
    FEEDS_INTO,
    /** Tool A's result determines whether Tool B runs. */
    CONDITIONAL;

    fun opposite(): EdgeType = when (this) {
        DEPENDENCY -> TRIGGER
        TRIGGER -> DEPENDENCY
        FEEDS_INTO -> FEEDS_INTO
        CONDITIONAL -> CONDITIONAL
    }
}

/**
 * A directed edge in the tool graph.
 */
data class ToolEdge(
    val from: String,
    val to: String,
    val type: EdgeType,
    val condition: String? = null
)

/**
 * Metadata for a tool node in the graph.
 */
data class ToolNodeMeta(
    /** If true, failure of this tool aborts the entire graph execution. */
    val required: Boolean = false,
    /** Tools in the same concurrency group are serialized (not parallel). */
    val concurrencyGroup: String? = null,
    /** Estimated execution time in ms (for scheduling hints). */
    val estimatedMs: Long = 0,
    /** Whether this tool writes to the database. */
    val writesData: Boolean = false
)

/**
 * Diagnostic stats for the tool graph.
 */
data class GraphStats(
    val nodeCount: Int,
    val edgeCount: Int,
    val concurrencyGroups: Int
)
