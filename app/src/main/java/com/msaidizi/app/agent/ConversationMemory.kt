package com.msaidizi.app.agent

import com.msaidizi.app.core.model.IntentResult
import timber.log.Timber

/**
 * Tracks conversation context for multi-turn interactions.
 *
 * Enables natural follow-up questions like:
 *
 * Worker: "How much did I sell today?"
 * Msaidizi: "KSh 3,200"
 * Worker: "And those from yesterday?"
 * Msaidizi: "Jana uliuza KSh 2,800" (remembers context)
 *
 * Worker: "Nimeuza nyanya kwa 500"
 * Msaidizi: "✅ Recorded: nyanya, KSh 500"
 * Worker: "Na zile za jana?"  ← "And those from yesterday?" — context resolved
 *
 * Stores the last N turns in memory (not DB — too slow for real-time).
 * Passed to intent classification for pronoun/reference resolution.
 *
 * Mathematical foundation:
 * - STA 443 §1.2 (Probability Spaces): Each conversation turn is a
 *   conditional event. P(Intent_t | Turn_{t-1}, Turn_{t-2}, ...) gives
 *   the prior probability distribution for the current intent based on
 *   conversation history.
 * - Bayesian updating: The conversation history provides the prior,
 *   the current utterance provides the likelihood, and the posterior
 *   is the resolved intent.
 */
class ConversationMemory(
    private val maxTurns: Int = DEFAULT_MAX_TURNS
) {
    companion object {
        /** Default number of turns to remember */
        const val DEFAULT_MAX_TURNS = 10

        /** Time window for context relevance (30 minutes) */
        private const val CONTEXT_WINDOW_MS = 30 * 60 * 1000L
    }

    /** Conversation history — FIFO queue of recent turns */
    private val turns = mutableListOf<ConversationTurn>()

    /** Cached context for quick access */
    private var cachedContext: ConversationContext? = null

    // ═══════════════════════════════════════════════════════════════
    // TURN RECORDING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Add a conversation turn to memory.
     *
     * @param speaker Who said it ("worker" or "msaidizi")
     * @param text The raw text
     * @param intent The classified intent (null for system responses)
     * @param extractedData Data extracted from the intent
     */
    fun addTurn(
        speaker: String,
        text: String,
        intent: IntentResult? = null,
        extractedData: Map<String, String> = emptyMap()
    ) {
        val turn = ConversationTurn(
            speaker = speaker,
            text = text,
            intent = intent,
            extractedData = extractedData,
            timestamp = System.currentTimeMillis()
        )

        turns.add(turn)

        // Evict old turns beyond max
        while (turns.size > maxTurns) {
            turns.removeAt(0)
        }

        // Invalidate cached context
        cachedContext = null

        Timber.d("ConversationMemory: Added turn #%d [%s]: '%s' → %s",
            turns.size, speaker, text.take(50),
            intent?.intent?.name ?: "null")
    }

    // ═══════════════════════════════════════════════════════════════
    // CONTEXT RETRIEVAL
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get the current conversation context.
     * Cached — only recomputed when turns change.
     */
    fun getContext(): ConversationContext {
        cachedContext?.let { return it }

        val now = System.currentTimeMillis()
        val recentTurns = turns.filter { now - it.timestamp < CONTEXT_WINDOW_MS }

        val context = ConversationContext(
            turns = recentTurns.toList(),
            lastWorkerTurn = recentTurns.lastOrNull { it.speaker == "worker" },
            lastAgentTurn = recentTurns.lastOrNull { it.speaker == "msaidizi" },
            lastIntent = recentTurns.lastOrNull { it.intent != null }?.intent,
            lastItem = resolveLastItem(recentTurns),
            lastAmount = resolveLastAmount(recentTurns),
            topicChain = buildTopicChain(recentTurns)
        )

        cachedContext = context
        return context
    }

    /**
     * Resolve references in the current input using conversation context.
     *
     * Handles pronouns and implicit references:
     * - "zile za jana" → resolves "zile" to last mentioned item
     * - "na bei yake?" → resolves to last item's price
     * - "pia hizo" → resolves to last items
     *
     * @param text Current user input
     * @param currentIntent Current intent classification
     * @return Enhanced intent with resolved references
     */
    fun resolveReferences(text: String, currentIntent: IntentResult): IntentResult {
        val ctx = getContext()
        if (ctx.turns.isEmpty()) return currentIntent

        val lower = text.lowercase()
        val enhancedData = currentIntent.extractedData.toMutableMap()

        // Resolve implicit item reference
        // "na zile za jana?" — "zile" refers to last mentioned item
        if (!enhancedData.containsKey("item") && ctx.lastItem != null) {
            val referencePatterns = listOf(
                "\\bzile\\b", "\\bhizo\\b", "\\bile\\b", "\\biyo\\b",
                "\\byake\\b", "\\bzake\\b", "\\bza\\b.*\\bjana\\b",
                "\\bpia\\b", "\\bna\\b.*\\bza\\b"
            )
            if (referencePatterns.any { Regex(it).containsMatchIn(lower) }) {
                enhancedData["item"] = ctx.lastItem
                Timber.d("ConversationMemory: Resolved 'item' → '%s'", ctx.lastItem)
            }
        }

        // Resolve implicit time reference
        // "na faida?" without specifying time → context from last query
        if (!enhancedData.containsKey("timeframe") && ctx.lastIntent != null) {
            val timePatterns = listOf(
                "\\bjana\\b" to "yesterday",
                "\\bleo\\b" to "today",
                "\\bwiki\\b" to "week",
                "\\bmwezi\\b" to "month"
            )
            for ((pattern, value) in timePatterns) {
                if (Regex(pattern).containsMatchIn(lower)) {
                    enhancedData["timeframe"] = value
                    break
                }
            }
        }

        // If no data was enhanced, return original
        if (enhancedData == currentIntent.extractedData) return currentIntent

        return currentIntent.copy(extractedData = enhancedData)
    }

    // ═══════════════════════════════════════════════════════════════
    // CONTEXT QUERIES
    // ═══════════════════════════════════════════════════════════════

    /**
     * Check if the current input is likely a follow-up to a previous turn.
     * Used to decide whether to apply context resolution.
     */
    fun isFollowUp(text: String): Boolean {
        val ctx = getContext()
        if (ctx.turns.isEmpty()) return false

        val lower = text.lowercase()

        // Explicit follow-up markers
        val followUpPatterns = listOf(
            "\\bna\\b", "\\bpia\\b", "\\bhizo\\b", "\\bzile\\b",
            "\\bile\\b", "\\byake\\b", "\\bzake\\b", "\\bjana\\b",
            "\\bza\\b.*\\bhapo\\b", "\\btena\\b", "\\bkingine\\b"
        )

        return followUpPatterns.any { Regex(it).containsMatchIn(lower) }
    }

    /**
     * Get the number of turns in memory.
     */
    fun turnCount(): Int = turns.size

    /**
     * Check if memory is empty.
     */
    fun isEmpty(): Boolean = turns.isEmpty()

    /**
     * Clear all conversation memory.
     */
    fun clear() {
        turns.clear()
        cachedContext = null
        Timber.d("ConversationMemory: Cleared")
    }

    // ═══════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Resolve the last mentioned item from conversation history.
     */
    private fun resolveLastItem(turns: List<ConversationTurn>): String? {
        // Look for the most recent item in extracted data
        for (turn in turns.reversed()) {
            turn.extractedData["item"]?.let { return it }
            turn.intent?.extractedData?.get("item")?.let { return it }
        }
        return null
    }

    /**
     * Resolve the last mentioned amount from conversation history.
     */
    private fun resolveLastAmount(turns: List<ConversationTurn>): Double? {
        for (turn in turns.reversed()) {
            turn.extractedData["amount"]?.toDoubleOrNull()?.let { return it }
            turn.intent?.extractedData?.get("amount")?.toDoubleOrNull()?.let { return it }
        }
        return null
    }

    /**
     * Build a topic chain from conversation history.
     * Used for context-aware intent classification.
     */
    private fun buildTopicChain(turns: List<ConversationTurn>): List<String> {
        return turns.mapNotNull { turn ->
            turn.intent?.intent?.name ?: when {
                turn.text.contains(Regex("(?i)(nimeuza|sold|nauza)")) -> "SALE"
                turn.text.contains(Regex("(?i)(nimenunua|bought)")) -> "PURCHASE"
                turn.text.contains(Regex("(?i)(nimetumia|spent)")) -> "EXPENSE"
                turn.text.contains(Regex("(?i)(faida|profit)")) -> "PROFIT"
                turn.text.contains(Regex("(?i)(salio|balance)")) -> "BALANCE"
                else -> null
            }
        }.distinct()
    }
}

// ═══════════════════════════════════════════════════════════════
// DATA CLASSES
// ═══════════════════════════════════════════════════════════════

/**
 * A single conversation turn.
 */
data class ConversationTurn(
    val speaker: String,       // "worker" or "msaidizi"
    val text: String,
    val intent: IntentResult?,
    val extractedData: Map<String, String>,
    val timestamp: Long
)

/**
 * Aggregated conversation context.
 * Passed to intent classification and agent handlers.
 */
data class ConversationContext(
    /** Recent turns within the context window */
    val turns: List<ConversationTurn>,

    /** Last turn from the worker */
    val lastWorkerTurn: ConversationTurn?,

    /** Last turn from Msaidizi */
    val lastAgentTurn: ConversationTurn?,

    /** Last classified intent */
    val lastIntent: IntentResult?,

    /** Last mentioned item (for pronoun resolution) */
    val lastItem: String?,

    /** Last mentioned amount */
    val lastAmount: Double?,

    /** Chain of topics discussed in this conversation */
    val topicChain: List<String>
) {
    /**
     * Check if the conversation has been about a specific topic.
     */
    fun hasDiscussed(topic: String): Boolean = topicChain.contains(topic)

    /**
     * Get the last transaction item for context resolution.
     */
    fun getLastTransactionItem(): String? = lastItem

    /**
     * Check if this is a fresh conversation (no recent turns).
     */
    fun isFresh(): Boolean = turns.isEmpty()
}
