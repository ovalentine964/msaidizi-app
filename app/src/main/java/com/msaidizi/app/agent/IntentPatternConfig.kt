package com.msaidizi.app.agent

import android.content.Context

/**
 * Stub: Intent pattern configuration for pattern-based classification.
 * Real implementation was in the old agent package.
 */
class IntentPatternConfig(private val context: Context) {
    fun getConfig(): ParsedConfig = ParsedConfig()
    fun getShengVocabulary(): Map<String, String> = emptyMap()
    fun getShengAmounts(): Map<String, String> = emptyMap()
    fun getGivingTypeKeywords(): Map<String, String> = emptyMap()
    fun getIntentKeysByPriority(): List<String> = emptyList()
    fun getPatternsForIntent(key: String): List<Regex> = emptyList()

    data class ParsedConfig(
        val version: String = "1.0.0",
        val intents: Map<String, IntentConfig> = emptyMap(),
        val shengVocabulary: ShengVocabulary = ShengVocabulary()
    )
    data class IntentConfig(
        val priority: Int = 0,
        val confidence: Double = 0.0,
        val isSheng: Boolean = false,
        val needsLLM: Boolean = false,
        val patterns: List<String> = emptyList(),
        val keywords: List<String> = emptyList(),
        val responseHint: String = "",
        val givingTypeKeywords: Map<String, String> = emptyMap()
    )
    data class ShengVocabulary(
        val verbs: Map<String, String> = emptyMap(),
        val money: Map<String, String> = emptyMap()
    )
}
