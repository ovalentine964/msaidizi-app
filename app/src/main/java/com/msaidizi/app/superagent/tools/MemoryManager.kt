package com.msaidizi.app.superagent.tools
import javax.inject.Inject

data class MemoryEntry(val layer: String, val key: String, val value: String, val timestamp: Long, val relevance: Double)

class MemoryManager @Inject constructor() {
    private val workingMemory = mutableMapOf<String, String>() // L1: RAM, session-scoped
    private val conversationBuffer = mutableListOf<MemoryEntry>() // L2: Last 20 turns
    private val dailySummaries = mutableListOf<MemoryEntry>() // L3: Daily summaries
    private val longTermPatterns = mutableListOf<MemoryEntry>() // L4: Learned patterns
    private val knowledgeBase = mutableMapOf<String, String>() // L5: Financial knowledge

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
