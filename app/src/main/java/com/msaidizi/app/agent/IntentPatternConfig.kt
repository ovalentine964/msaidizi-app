package com.msaidizi.app.agent

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads and caches intent classification patterns from assets/intent_patterns.json.
 *
 * Supports:
 * - Hot-reload from JSON (startup + OTA update)
 * - In-memory caching with compiled Regex objects
 * - Sheng vocabulary normalization
 * - Per-intent metadata (keywords, response hints, confidence)
 *
 * OTA Update Flow:
 * 1. Backend pushes updated intent_patterns.json
 * 2. App downloads to internal storage
 * 3. IntentPatternConfig.reload() reads new file
 * 4. Patterns hot-swap without app restart
 */
class IntentPatternConfig(private val context: Context) {

    // ═══ Cached State ═══
    @Volatile private var cachedConfig: ParsedConfig? = null
    private val compiledPatterns = ConcurrentHashMap<String, List<Regex>>()

    // ═══ JSON Parser ═══
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Get the parsed config, loading from assets if not cached.
     */
    fun getConfig(): ParsedConfig {
        cachedConfig?.let { return it }
        return loadFromAssets().also { cachedConfig = it }
    }

    /**
     * Get compiled regex patterns for a specific intent.
     * Patterns are compiled lazily and cached.
     */
    fun getPatternsForIntent(intentKey: String): List<Regex> {
        compiledPatterns[intentKey]?.let { return it }

        val config = getConfig()
        val intentConfig = config.intents[intentKey] ?: return emptyList()
        val compiled = intentConfig.patterns.mapNotNull { pattern ->
            try {
                Regex(pattern)
            } catch (e: Throwable) {
                Timber.e(e, "Invalid regex in intent '$intentKey': $pattern")
                null
            }
        }
        compiledPatterns[intentKey] = compiled
        return compiled
    }

    /**
     * Get all intent keys ordered by priority.
     */
    fun getIntentKeysByPriority(): List<String> {
        val config = getConfig()
        return config.intents.entries
            .sortedBy { it.value.priority }
            .map { it.key }
    }

    /**
     * Get sheng vocabulary map for normalization.
     */
    fun getShengVocabulary(): Map<String, String> {
        val config = getConfig()
        val vocab = config.shengVocabulary
        return buildMap {
            putAll(vocab.verbs)
            putAll(vocab.money)
            putAll(vocab.items)
            putAll(vocab.transport)
            putAll(vocab.digital)
            putAll(vocab.places)
            putAll(vocab.descriptors)
        }
    }

    /**
     * Get sheng amount map for numeric resolution.
     */
    fun getShengAmounts(): Map<String, Int> {
        return getConfig().shengVocabulary.amounts
    }

    /**
     * Get giving type keyword mapping.
     */
    fun getGivingTypeKeywords(): Map<String, String> {
        return getConfig().intents["GIVING_RECORD"]?.givingTypeKeywords ?: emptyMap()
    }

    /**
     * Reload config from assets (or OTA-updated file in internal storage).
     */
    fun reload() {
        cachedConfig = null
        compiledPatterns.clear()

        // Try OTA-updated file first, fall back to assets
        val otaFile = context.getFileStreamPath("intent_patterns.json")
        if (otaFile.exists()) {
            try {
                val text = otaFile.readText()
                cachedConfig = json.decodeFromString<IntentPatternsFile>(text).toParsedConfig()
                Timber.i("IntentPatternConfig: Loaded OTA-updated patterns (v${cachedConfig?.version})")
                return
            } catch (e: Throwable) {
                Timber.w(e, "IntentPatternConfig: OTA file corrupted, falling back to assets")
            }
        }

        loadFromAssets()
        Timber.i("IntentPatternConfig: Loaded default patterns from assets (v${cachedConfig?.version})")
    }

    /**
     * Save OTA-updated patterns to internal storage.
     * Called when backend pushes new pattern config.
     */
    fun saveOtaUpdate(jsonContent: String): Boolean {
        return try {
            // Validate JSON before saving
            json.decodeFromString<IntentPatternsFile>(jsonContent)
            context.openFileOutput("intent_patterns.json", Context.MODE_PRIVATE).use {
                it.write(jsonContent.toByteArray())
            }
            reload() // Hot-swap
            Timber.i("IntentPatternConfig: OTA update saved and applied")
            true
        } catch (e: Throwable) {
            Timber.e(e, "IntentPatternConfig: Failed to save OTA update")
            false
        }
    }

    /**
     * Get the config version (for OTA update checks).
     */
    fun getVersion(): String {
        return getConfig().version
    }

    // ═══ Private ═══

    private fun loadFromAssets(): ParsedConfig {
        return try {
            val text = context.assets.open("intent_patterns.json").bufferedReader().readText()
            val file = json.decodeFromString<IntentPatternsFile>(text)
            file.toParsedConfig().also { cachedConfig = it }
        } catch (e: Throwable) {
            Timber.e(e, "IntentPatternConfig: Failed to load from assets")
            ParsedConfig.empty()
        }
    }

    // ═══ Data Classes ═══

    @Serializable
    data class IntentPatternsFile(
        val _meta: Meta? = null,
        val shengVocabulary: ShengVocabularyFile = ShengVocabularyFile(),
        val intents: Map<String, IntentFile> = emptyMap()
    ) {
        fun toParsedConfig() = ParsedConfig(
            version = _meta?.version ?: "1.0.0",
            shengVocabulary = shengVocabulary.toShengVocabulary(),
            intents = intents.mapValues { it.value.toIntentConfig() }
        )
    }

    @Serializable
    data class Meta(
        val version: String = "1.0.0",
        val description: String = "",
        val lastUpdated: String = "",
        val author: String = ""
    )

    @Serializable
    data class ShengVocabularyFile(
        val verbs: Map<String, String> = emptyMap(),
        val money: Map<String, String> = emptyMap(),
        val items: Map<String, String> = emptyMap(),
        val transport: Map<String, String> = emptyMap(),
        val digital: Map<String, String> = emptyMap(),
        val places: Map<String, String> = emptyMap(),
        val descriptors: Map<String, String> = emptyMap(),
        val amounts: Map<String, Int> = emptyMap()
    ) {
        fun toShengVocabulary() = ShengVocabulary(
            verbs, money, items, transport, digital, places, descriptors, amounts
        )
    }

    @Serializable
    data class IntentFile(
        val priority: Int = 10,
        val confidence: Double = 0.80,
        val isSheng: Boolean = false,
        val needsLLM: Boolean = false,
        val patterns: List<String> = emptyList(),
        val keywords: List<String> = emptyList(),
        val responseHint: String = "",
        val givingTypeKeywords: Map<String, String> = emptyMap()
    ) {
        fun toIntentConfig() = IntentConfig(
            priority, confidence, isSheng, needsLLM, patterns, keywords, responseHint, givingTypeKeywords
        )
    }

    data class ParsedConfig(
        val version: String,
        val shengVocabulary: ShengVocabulary,
        val intents: Map<String, IntentConfig>
    ) {
        companion object {
            fun empty() = ParsedConfig(
                version = "0.0.0",
                shengVocabulary = ShengVocabulary(),
                intents = emptyMap()
            )
        }
    }

    data class ShengVocabulary(
        val verbs: Map<String, String> = emptyMap(),
        val money: Map<String, String> = emptyMap(),
        val items: Map<String, String> = emptyMap(),
        val transport: Map<String, String> = emptyMap(),
        val digital: Map<String, String> = emptyMap(),
        val places: Map<String, String> = emptyMap(),
        val descriptors: Map<String, String> = emptyMap(),
        val amounts: Map<String, Int> = emptyMap()
    )

    data class IntentConfig(
        val priority: Int,
        val confidence: Double,
        val isSheng: Boolean,
        val needsLLM: Boolean,
        val patterns: List<String>,
        val keywords: List<String>,
        val responseHint: String,
        val givingTypeKeywords: Map<String, String>
    )
}
