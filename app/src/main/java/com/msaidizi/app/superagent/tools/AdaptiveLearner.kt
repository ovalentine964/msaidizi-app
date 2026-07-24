package com.msaidizi.app.superagent.tools

import javax.inject.Inject
import javax.inject.Singleton

data class LearnedWord(val word: String, val language: String, val frequency: Int, val confidence: Double)
data class BusinessPattern(val pattern: String, val type: String, val confidence: Double, val occurrences: Int)

/**
 * AdaptiveLearner — Learn user vocabulary, code-switching patterns, and business patterns.
 */
@Singleton
class AdaptiveLearner @Inject constructor() : Tool {

    override val name = "adaptive_learner"
    override val description = "Learn user vocabulary, code-switching patterns, and business patterns"

    private val vocabulary = mutableMapOf<String, LearnedWord>()
    private val patterns = mutableListOf<BusinessPattern>()

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"] ?: "status"
        return when (action.lowercase()) {
            "learn_word" -> {
                val word = params["word"]
                    ?: return ToolResult.error(name, "Word required", "MISSING_WORD")
                val language = params["language"] ?: "sw"
                learnWord(word, language)
                ToolResult.success(name, mapOf("word" to word, "language" to language), "Learned: $word ($language)")
            }
            "detect_pattern" -> {
                val dataStr = params["data"]
                    ?: return ToolResult.error(name, "Transaction data required", "MISSING_DATA")
                val transactions = dataStr.split(",").mapNotNull { it.trim().toDoubleOrNull() }
                val pattern = detectPattern(transactions, transactions.mapIndexed { i, _ -> System.currentTimeMillis() - (transactions.size - i) * 86400000L })
                if (pattern != null) {
                    ToolResult.success(name, mapOf("pattern" to pattern.pattern, "confidence" to pattern.confidence), "Pattern detected: ${pattern.pattern} (${pattern.occurrences} occurrences)")
                } else {
                    ToolResult.success(name, message = "No pattern detected yet (need 5+ data points)")
                }
            }
            "vocabulary" -> {
                val vocab = getPersonalVocabulary()
                val list = vocab.joinToString("\n") { "  ${it.word} (${it.language}): ${it.frequency}x, ${"%.0f".format(it.confidence * 100)}%" }
                ToolResult.success(name, message = if (list.isEmpty()) "No learned vocabulary yet" else "Learned vocabulary:\n$list")
            }
            "code_switch" -> {
                val text = params["text"]
                    ?: return ToolResult.error(name, "Text required", "MISSING_TEXT")
                val pattern = getCodeSwitchPattern(text)
                ToolResult.success(
                    name,
                    mapOf("swahili_pct" to pattern["swahili"], "english_pct" to pattern["english"]),
                    "Code-switch: Swahili ${"%.0f".format((pattern["swahili"] ?: 0.0) * 100)}%, English ${"%.0f".format((pattern["english"] ?: 0.0) * 100)}%"
                )
            }
            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    fun learnWord(word: String, language: String) {
        val existing = vocabulary[word.lowercase()]
        if (existing != null) {
            vocabulary[word.lowercase()] = existing.copy(frequency = existing.frequency + 1, confidence = minOf(existing.confidence + 0.1, 1.0))
        } else {
            vocabulary[word.lowercase()] = LearnedWord(word.lowercase(), language, 1, 0.5)
        }
    }

    fun detectPattern(transactions: List<Double>, timestamps: List<Long>): BusinessPattern? {
        if (transactions.size < 5) return null
        val avg = transactions.average()
        val isConsistent = transactions.all { Math.abs(it - avg) / avg < 0.3 }
        if (isConsistent) return BusinessPattern("consistent_sales", "revenue", 0.8, transactions.size)
        val trending = transactions.zipWithNext().all { (a, b) -> b > a }
        if (trending) return BusinessPattern("growing_sales", "revenue", 0.7, transactions.size)
        return null
    }

    fun getPersonalVocabulary(): List<LearnedWord> = vocabulary.values.sortedByDescending { it.frequency }

    fun getCodeSwitchPattern(text: String): Map<String, Double> {
        val swahiliWords = text.split(" ").count { isSwahili(it) }
        val englishWords = text.split(" ").count { isEnglish(it) }
        val total = text.split(" ").size.toDouble().coerceAtLeast(1.0)
        return mapOf("swahili" to swahiliWords / total, "english" to englishWords / total)
    }

    private fun isSwahili(word: String): Boolean = word.lowercase() in setOf("na", "ya", "kwa", "ni", "la", "za", "wa", "katika", "kutoka")
    private fun isEnglish(word: String): Boolean = word.lowercase() in setOf("the", "and", "for", "with", "from", "this", "that", "have", "been")
}
