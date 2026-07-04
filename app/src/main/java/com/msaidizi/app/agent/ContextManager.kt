package com.msaidizi.app.agent

import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.math.max
import kotlin.math.min

/**
 * Context Manager — Factor 3: Own Your Context Window.
 *
 * Proactively manages agent context on Android to stay within
 * memory and token limits. Prioritizes recent and important context,
 * summarizes old interactions, and compresses repeated patterns.
 *
 * Mathematical foundation:
 * - Sliding window with priority-weighted eviction
 * - Exponential decay for recency scoring: score = 2^(-age / halfLife)
 * - Frequency-based importance for pattern compression
 *
 * Designed for 2GB devices: bounded memory, lazy eviction,
 * no background threads.
 */
class ContextManager(
    private val agentName: String,
    private val maxItems: Int = DEFAULT_MAX_ITEMS,
    private val compressionThreshold: Float = 0.8f
) {
    companion object {
        const val DEFAULT_MAX_ITEMS = 50
        private const val RECENCY_HALF_LIFE_MS = 5 * 60 * 1000L // 5 minutes
        private const val PATTERN_COMPRESS_THRESHOLD = 3
    }

    // ── Context Storage ────────────────────────────────────────────

    /** Items in the context window, ordered by insertion. */
    private val items = ConcurrentLinkedDeque<ContextItem>()

    /** Compressed summaries of evicted items. */
    private val summaries = mutableListOf<ContextSummary>()

    /** Pattern frequency counter for compression. */
    private val patternCounts = mutableMapOf<String, Int>()

    /** Pattern → list of items (for compression). */
    private val patternItems = mutableMapOf<String, MutableList<ContextItem>>()

    /** Total eviction count for metrics. */
    private var evictionCount = 0

    // ═══════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════

    /**
     * Add an item to the context window.
     * Triggers compression if approaching the limit.
     *
     * @param content The context data (event, result, error, etc.)
     * @param priority Priority level (higher = retained longer)
     * @param tags Optional tags for filtering
     * @return The created ContextItem
     */
    fun add(
        content: Map<String, Any>,
        priority: ContextPriority = ContextPriority.NORMAL,
        tags: List<String> = emptyList()
    ): ContextItem {
        val item = ContextItem(
            content = content,
            priority = priority,
            tags = tags,
            createdAt = System.currentTimeMillis()
        )

        // Track patterns
        extractPatternKey(content)?.let { key ->
            patternCounts[key] = (patternCounts[key] ?: 0) + 1
            patternItems.getOrPut(key) { mutableListOf() }.add(item)
        }

        items.addLast(item)

        // Proactive compression
        if (items.size > (maxItems * compressionThreshold).toInt()) {
            compress()
        }

        Timber.d("ContextManager[%s]: Added item, total=%d/%d",
            agentName, items.size, maxItems)

        return item
    }

    /**
     * Get the current context, fitting within the item budget.
     * Items are sorted by importance (highest first).
     *
     * @param maxItems Override max items to return
     * @param filterTags Only include items with these tags
     * @return Context snapshot for the think phase
     */
    fun getContext(
        maxItems: Int = this.maxItems,
        filterTags: List<String>? = null
    ): ContextSnapshot {
        var filtered = items.toList()

        // Apply tag filter
        if (filterTags != null) {
            filtered = filtered.filter { item ->
                item.tags.any { it in filterTags }
            }
        }

        // Sort by importance (highest first)
        val sorted = filtered.sortedByDescending { it.importanceScore }

        // Fit within budget
        val fitted = sorted.take(maxItems)

        // Update access counts
        fitted.forEach { it.accessCount++ }

        return ContextSnapshot(
            items = fitted.map { it.content },
            summaries = summaries.takeLast(3).map { it.summaryText },
            itemCount = items.size,
            summaryCount = summaries.size,
            utilization = items.size.toFloat() / this.maxItems
        )
    }

    /**
     * Get context optimized for the agent's think phase.
     * Includes items, summaries, and detected patterns.
     */
    fun getContextForThink(): ThinkContext {
        val snapshot = getContext()
        val patterns = detectPatterns()

        return ThinkContext(
            items = snapshot.items,
            summaries = snapshot.summaries,
            patterns = patterns,
            itemCount = snapshot.itemCount,
            utilization = snapshot.utilization
        )
    }

    /**
     * Get current token/item usage statistics.
     */
    fun getUsage(): ContextUsage {
        return ContextUsage(
            agentName = agentName,
            currentItems = items.size,
            maxItems = maxItems,
            utilization = items.size.toFloat() / maxItems,
            summaryCount = summaries.size,
            evictionCount = evictionCount,
            topPatterns = patternCounts.entries
                .sortedByDescending { it.value }
                .take(5)
                .map { (k, v) -> PatternInfo(k, v) }
        )
    }

    /**
     * Clear all context items and summaries.
     */
    fun clear() {
        items.clear()
        summaries.clear()
        patternCounts.clear()
        patternItems.clear()
        evictionCount = 0
        Timber.d("ContextManager[%s]: Cleared", agentName)
    }

    // ═══════════════════════════════════════════════════════════════
    // COMPRESSION ENGINE
    // ═══════════════════════════════════════════════════════════════

    /**
     * Compress context when approaching the item limit.
     *
     * Strategy:
     * 1. Compress repeated patterns into single summary items
     * 2. Summarize oldest low-priority items
     * 3. Evict lowest-importance items if still over budget
     */
    private fun compress() {
        Timber.i("ContextManager[%s]: Compression triggered, items=%d/%d",
            agentName, items.size, maxItems)

        // Step 1: Compress repeated patterns
        compressPatterns()

        // Step 2: Summarize old items if still over threshold
        if (items.size > (maxItems * compressionThreshold).toInt()) {
            summarizeOldItems()
        }

        // Step 3: Hard eviction if still over limit
        if (items.size > maxItems) {
            evictLowestImportance()
        }
    }

    /**
     * Compress repeated event patterns into single summary items.
     */
    private fun compressPatterns() {
        val toCompress = patternCounts.filter { (_, count) -> count >= PATTERN_COMPRESS_THRESHOLD }

        for ((patternKey, count) in toCompress) {
            val patternItemList = patternItems[patternKey] ?: continue
            if (patternItemList.size < PATTERN_COMPRESS_THRESHOLD) continue

            // Create summary
            val summary = ContextSummary(
                originalIds = patternItemList.map { it.itemId },
                summaryText = "Pattern '$patternKey' repeated $count times. " +
                        "Latest: ${patternItemList.last().content}",
                itemCount = patternItemList.size,
                timeRange = Pair(
                    patternItemList.first().createdAt,
                    patternItemList.last().createdAt
                )
            )

            // Remove original items
            val idsToRemove = patternItemList.map { it.itemId }.toSet()
            val toRemove = items.filter { it.itemId in idsToRemove }
            items.removeAll(toRemove)
            evictionCount += toRemove.size

            summaries.add(summary)

            // Clear pattern tracking
            patternItems[patternKey] = mutableListOf()
            patternCounts[patternKey] = 0

            Timber.i("ContextManager[%s]: Compressed pattern '%s', removed %d items",
                agentName, patternKey, toRemove.size)
        }
    }

    /**
     * Summarize the oldest, lowest-priority items.
     */
    private fun summarizeOldItems() {
        val sorted = items.sortedWith(
            compareBy<ContextItem> { it.createdAt }
                .thenBy { it.priority.value }
        )

        val count = max(1, items.size / 5) // Bottom 20%
        val toSummarize = sorted.take(count)

        if (toSummarize.isEmpty()) return

        val summary = ContextSummary(
            originalIds = toSummarize.map { it.itemId },
            summaryText = summarizeItems(toSummarize),
            itemCount = toSummarize.size,
            timeRange = Pair(
                toSummarize.first().createdAt,
                toSummarize.last().createdAt
            )
        )

        val idsToRemove = toSummarize.map { it.itemId }.toSet()
        val toRemove = items.filter { it.itemId in idsToRemove }
        items.removeAll(toRemove)
        evictionCount += toRemove.size

        summaries.add(summary)

        Timber.i("ContextManager[%s]: Summarized %d items", agentName, toSummarize.size)
    }

    /**
     * Hard-evict lowest-importance items until under budget.
     */
    private fun evictLowestImportance() {
        val sorted = items.sortedBy { it.importanceScore }

        var removed = 0
        for (item in sorted) {
            if (items.size <= maxItems) break
            items.remove(item)
            removed++
        }
        evictionCount += removed

        if (removed > 0) {
            Timber.w("ContextManager[%s]: Hard evicted %d items", agentName, removed)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PATTERN DETECTION
    // ═══════════════════════════════════════════════════════════════

    private fun extractPatternKey(content: Map<String, Any>): String? {
        val eventType = content["event_type"] ?: content["type"] ?: return null
        val source = content["source"] ?: ""
        return "$eventType:$source"
    }

    private fun detectPatterns(): List<PatternInfo> {
        return patternCounts.entries
            .filter { it.value >= 2 }
            .sortedByDescending { it.value }
            .take(5)
            .map { (key, count) -> PatternInfo(key, count) }
    }

    // ═══════════════════════════════════════════════════════════════
    // DEFAULT SUMMARIZER
    // ═══════════════════════════════════════════════════════════════

    private fun summarizeItems(items: List<ContextItem>): String {
        if (items.isEmpty()) return ""

        val byType = items.groupBy {
            it.content["event_type"] ?: it.content["type"] ?: "unknown"
        }

        val parts = byType.map { (type, list) -> "${list.size}x $type" }
        val timeSpan = (items.last().createdAt - items.first().createdAt) / 1000

        return "Summary of ${items.size} items over ${timeSpan}s: ${parts.joinToString(", ")}"
    }
}

// ═══════════════════════════════════════════════════════════════
// DATA CLASSES
// ═══════════════════════════════════════════════════════════════

enum class ContextPriority(val value: Int) {
    LOW(1),
    NORMAL(2),
    HIGH(3),
    CRITICAL(4) // Errors, strategy adjustments
}

data class ContextItem(
    val content: Map<String, Any>,
    val priority: ContextPriority = ContextPriority.NORMAL,
    val tags: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val itemId: String = generateId(content)
) {
    var accessCount: Int = 0

    val ageMs: Long get() = System.currentTimeMillis() - createdAt

    /** Exponential decay score. Fresh = 1.0, old → 0. */
    val recencyScore: Double
        get() {
            val halfLife = 5 * 60 * 1000.0 // 5 minutes
            return Math.pow(2.0, -ageMs.toDouble() / halfLife)
        }

    /** Combined priority + recency + frequency importance score. */
    val importanceScore: Double
        get() {
            val priorityWeight = priority.value.toDouble() / ContextPriority.CRITICAL.value
            val recencyWeight = recencyScore
            val frequencyWeight = min(1.0, accessCount.toDouble() / 5.0)
            return (0.4 * priorityWeight) + (0.4 * recencyWeight) + (0.2 * frequencyWeight)
        }

    companion object {
        private fun generateId(content: Map<String, Any>): String {
            return content.hashCode().toString(16).take(12)
        }
    }
}

data class ContextSummary(
    val originalIds: List<String>,
    val summaryText: String,
    val itemCount: Int,
    val timeRange: Pair<Long, Long>,
    val createdAt: Long = System.currentTimeMillis()
)

data class ContextSnapshot(
    val items: List<Map<String, Any>>,
    val summaries: List<String>,
    val itemCount: Int,
    val summaryCount: Int,
    val utilization: Float
)

data class ThinkContext(
    val items: List<Map<String, Any>>,
    val summaries: List<String>,
    val patterns: List<PatternInfo>,
    val itemCount: Int,
    val utilization: Float
)

data class ContextUsage(
    val agentName: String,
    val currentItems: Int,
    val maxItems: Int,
    val utilization: Float,
    val summaryCount: Int,
    val evictionCount: Int,
    val topPatterns: List<PatternInfo>
)

data class PatternInfo(
    val pattern: String,
    val count: Int
)
