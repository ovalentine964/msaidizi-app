package com.msaidizi.app.superagent.tools

import javax.inject.Inject
import javax.inject.Singleton

data class MemoryEntry(val layer: String, val key: String, val value: String, val timestamp: Long, val relevance: Double)

/**
 * MemoryManager (Tool) — Multi-layer memory for storing and retrieving context.
 *
 * Layers: working (RAM), conversation (last 20 turns), daily, patterns, knowledge.
 * This is the Tool-interface version. The harness uses the full MemoryManager in superagent.memory.
 */
@Singleton
class MemoryManager @Inject constructor() : Tool {

    override val name = "memory_manager"
    override val description = "Multi-layer memory for storing and retrieving business context"

    private val workingMemory = mutableMapOf<String, String>() // L1: RAM, session-scoped
    private val conversationBuffer = mutableListOf<MemoryEntry>() // L2: Last 20 turns
    private val dailySummaries = mutableListOf<MemoryEntry>() // L3: Daily summaries
    private val longTermPatterns = mutableListOf<MemoryEntry>() // L4: Learned patterns
    private val knowledgeBase = mutableMapOf<String, String>() // L5: Financial knowledge

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"] ?: "retrieve"
        return when (action.lowercase()) {
            "store" -> {
                val key = params["key"]
                    ?: return ToolResult.error(name, "Key required", "MISSING_KEY")
                val value = params["value"]
                    ?: return ToolResult.error(name, "Value required", "MISSING_VALUE")
                val layer = params["layer"] ?: "working"
                store(key, value, layer)
                ToolResult.success(name, mapOf("key" to key, "layer" to layer), "Stored in $layer: $key")
            }
            "retrieve" -> {
                val query = params["query"]
                    ?: return ToolResult.error(name, "Query required", "MISSING_QUERY")
                val maxTokens = params["max_tokens"]?.toIntOrNull() ?: 1500
                val result = retrieve(query, maxTokens)
                ToolResult.success(name, mapOf("query" to query, "result_length" to result.length), result.ifEmpty { "No relevant memory found" })
            }
            "compress" -> {
                compressDaily()
                ToolResult.success(name, message = "Daily memory compressed")
            }
            "status" -> {
                ToolResult.success(
                    name,
                    mapOf(
                        "working" to workingMemory.size,
                        "conversation" to conversationBuffer.size,
                        "daily" to dailySummaries.size,
                        "patterns" to longTermPatterns.size,
                        "knowledge" to knowledgeBase.size
                    ),
                    "Memory: ${workingMemory.size} working, ${conversationBuffer.size} conversation, ${dailySummaries.size} daily, ${longTermPatterns.size} patterns, ${knowledgeBase.size} knowledge"
                )
            }
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    fun store(key: String, value: String, layer: String = "working") {
        when (layer) {
            "working" -> workingMemory[key] = value
            "conversation" -> {
                conversationBuffer.add(MemoryEntry("conversation", key, value, System.currentTimeMillis(), 1.0))
                if (conversationBuffer.size > 20) conversationBuffer.removeFirst()
            }
            "daily" -> dailySummaries.add(MemoryEntry("daily", key, value, System.currentTimeMillis(), 0.8))
            "patterns" -> longTermPatterns.add(MemoryEntry("patterns", key, value, System.currentTimeMillis(), 0.9))
            "knowledge" -> knowledgeBase[key] = value
        }
    }

    fun retrieve(query: String, maxTokens: Int = 1500): String {
        val context = mutableListOf<String>()
        context.addAll(workingMemory.values.take(3))
        context.addAll(conversationBuffer.filter { it.value.contains(query, ignoreCase = true) }.take(3).map { it.value })
        context.addAll(dailySummaries.takeLast(1).map { it.value })
        context.addAll(longTermPatterns.filter { it.value.contains(query, ignoreCase = true) }.take(2).map { it.value })
        return context.joinToString("\n").take(maxTokens)
    }

    fun compressDaily() {
        val todayEntries = dailySummaries.filter { isToday(it.timestamp) }
        if (todayEntries.size > 1) {
            val summary = "Daily: ${todayEntries.size} transactions, total=${todayEntries.sumOf { it.value.length }}"
            dailySummaries.removeAll(todayEntries)
            dailySummaries.add(MemoryEntry("daily", "summary", summary, System.currentTimeMillis(), 0.8))
        }
    }

    private fun isToday(timestamp: Long): Boolean {
        val today = java.time.LocalDate.now()
        val date = java.time.Instant.ofEpochMilli(timestamp).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        return date == today
    }
}
