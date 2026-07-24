package com.msaidizi.app.superagent.tools
import javax.inject.Inject

data class LearnedWord(val word: String, val language: String, val frequency: Int, val confidence: Double)
data class BusinessPattern(val pattern: String, val type: String, val confidence: Double, val occurrences: Int)

class AdaptiveLearner @Inject constructor() {
    private val vocabulary = mutableMapOf<String, LearnedWord>()
    private val patterns = mutableListOf<BusinessPattern>()

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
        val total = text.split(" ").size.toDouble()
        return mapOf("swahili" to swahiliWords / total, "english" to englishWords / total)
    }

    private fun isSwahili(word: String): Boolean = word.lowercase() in setOf("na", "ya", "kwa", "ni", "la", "za", "wa", "katika", "kutoka")
    private fun isEnglish(word: String): Boolean = word.lowercase() in setOf("the", "and", "for", "with", "from", "this", "that", "have", "been")
}
