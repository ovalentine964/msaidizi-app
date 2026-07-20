package com.msaidizi.app.agent

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads intent classification patterns from JSON config.
 *
 * Supports:
 * - Default patterns from assets (bundled with app)
 * - OTA updates: download new patterns from backend, cache locally
 * - A/B testing: switch between pattern sets
 * - Hot reload: update patterns without app restart
 *
 * ## Why externalize patterns?
 * 1. **OTA Updates**: Fix recognition bugs without app update
 * 2. **A/B Testing**: Test new patterns on subset of users
 * 3. **Localization**: Add new dialects/languages dynamically
 * 4. **Analytics**: Track which patterns match most often
 *
 * @see IntentRouter for pattern matching
 */
@Singleton
class IntentPatternLoader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "IntentPatternLoader"
        private const val ASSETS_FILE = "intent_patterns.json"
        private const val CACHE_DIR = "intent_patterns"
        private const val REMOTE_CACHE_FILE = "patterns_remote.json"
        private const val AB_TEST_CACHE_FILE = "patterns_ab_test.json"
    }

    @Serializable
    data class IntentPatternConfig(
        val _metadata: PatternMetadata? = null,
        val sheng_mappings: Map<String, String> = emptyMap(),
        val intents: Map<String, IntentPatterns> = emptyMap()
    )

    @Serializable
    data class PatternMetadata(
        val version: String = "1.0.0",
        val description: String = "",
        val updated_at: String = "",
        val supports_ab_testing: Boolean = false,
        val language: String = "sw"
    )

    @Serializable
    data class IntentPatterns(
        val priority: Int = 50,
        val patterns: List<String> = emptyList(),
        val sheng_patterns: List<String> = emptyList(),
        val contains_patterns: List<String> = emptyList(),
        val confidence: Double = 0.85,
        val needsLLM: Boolean = false
    )

    // Cached parsed config
    private var cachedConfig: IntentPatternConfig? = null

    // Active pattern set source
    enum class PatternSource {
        ASSETS,      // Bundled with app
        REMOTE,      // Downloaded from backend
        AB_TEST      // A/B test variant
    }

    private var activeSource = PatternSource.ASSETS

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Load intent patterns. Tries remote cache first, falls back to assets.
     */
    suspend fun loadPatterns(source: PatternSource? = null): IntentPatternConfig = withContext(Dispatchers.IO) {
        val effectiveSource = source ?: activeSource

        // Return cached if available and source matches
        cachedConfig?.let { return@withContext it }

        val config = when (effectiveSource) {
            PatternSource.REMOTE -> loadFromCache(REMOTE_CACHE_FILE) ?: loadFromAssets()
            PatternSource.AB_TEST -> loadFromCache(AB_TEST_CACHE_FILE) ?: loadFromAssets()
            PatternSource.ASSETS -> loadFromAssets()
        }

        cachedConfig = config
        activeSource = effectiveSource

        Timber.i(TAG, "Loaded patterns: source=%s, intents=%d, version=%s",
            effectiveSource, config.intents.size, config._metadata?.version ?: "unknown")

        config
    }

    /**
     * Update patterns from remote backend (OTA update).
     * Stores in local cache for persistence.
     */
    suspend fun updateFromRemote(patternsJson: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val config = json.decodeFromString<IntentPatternConfig>(patternsJson)

            // Validate: must have at least basic intents
            val requiredIntents = setOf("SALE", "PURCHASE", "EXPENSE")
            if (requiredIntents.any { it !in config.intents }) {
                Timber.e(TAG, "Remote patterns missing required intents: %s", requiredIntents - config.intents.keys)
                return@withContext false
            }

            // Cache to disk
            val cacheDir = File(context.filesDir, CACHE_DIR)
            cacheDir.mkdirs()
            File(cacheDir, REMOTE_CACHE_FILE).writeText(patternsJson)

            // Update active config
            cachedConfig = config
            activeSource = PatternSource.REMOTE

            Timber.i(TAG, "Updated patterns from remote: version=%s, intents=%d",
                config._metadata?.version, config.intents.size)
            true
        } catch (e: Throwable) {
            Timber.e(e, "Failed to update patterns from remote")
            false
        }
    }

    /**
     * Set A/B test pattern variant.
     */
    suspend fun setAbTestVariant(patternsJson: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val config = json.decodeFromString<IntentPatternConfig>(patternsJson)
            val cacheDir = File(context.filesDir, CACHE_DIR)
            cacheDir.mkdirs()
            File(cacheDir, AB_TEST_CACHE_FILE).writeText(patternsJson)
            cachedConfig = config
            activeSource = PatternSource.AB_TEST
            Timber.i(TAG, "Set A/B test variant: version=%s", config._metadata?.version)
            true
        } catch (e: Throwable) {
            Timber.e(e, "Failed to set A/B test variant")
            false
        }
    }

    /**
     * Reset to default bundled patterns.
     */
    fun resetToDefault() {
        cachedConfig = null
        activeSource = PatternSource.ASSETS
        Timber.i(TAG, "Reset to default assets patterns")
    }

    /**
     * Get current pattern source and version info.
     */
    fun getConfigInfo(): Map<String, Any> {
        val config = cachedConfig
        return mapOf(
            "source" to activeSource.name,
            "version" to (config?._metadata?.version ?: "not loaded"),
            "intentCount" to (config?.intents?.size ?: 0),
            "shengMappingCount" to (config?.sheng_mappings?.size ?: 0)
        )
    }

    // ────────────────────── Private ──────────────────────

    private fun loadFromAssets(): IntentPatternConfig {
        return try {
            val jsonText = context.assets.open(ASSETS_FILE).bufferedReader().use { it.readText() }
            json.decodeFromString<IntentPatternConfig>(jsonText)
        } catch (e: Throwable) {
            Timber.e(e, "Failed to load patterns from assets")
            // Return empty config as ultimate fallback
            IntentPatternConfig()
        }
    }

    private fun loadFromCache(fileName: String): IntentPatternConfig? {
        return try {
            val cacheDir = File(context.filesDir, CACHE_DIR)
            val file = File(cacheDir, fileName)
            if (!file.exists()) return null
            val jsonText = file.readText()
            json.decodeFromString<IntentPatternConfig>(jsonText)
        } catch (e: Throwable) {
            Timber.e(e, "Failed to load patterns from cache: %s", fileName)
            null
        }
    }
}
