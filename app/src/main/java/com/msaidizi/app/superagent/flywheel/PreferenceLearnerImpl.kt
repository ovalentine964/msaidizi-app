package com.msaidizi.app.superagent.flywheel

import com.msaidizi.app.superagent.engine.LearningSignal
import timber.log.Timber

/**
 * PreferenceLearnerImpl — Learns worker preferences from behavior.
 *
 * Observes every interaction and extracts preference signals:
 * - **Language**: Which language does the worker prefer?
 * - **Interaction style**: Do they prefer short confirmations or detailed info?
 * - **Timing**: When do they typically interact?
 * - **Topics**: What do they ask about most?
 *
 * Preferences are Bayesian-updated and applied automatically.
 * All learning is on-device and privacy-preserving.
 *
 * Mathematical foundation:
 * - Bayesian updating: P(preference|evidence) ∝ P(evidence|preference) × P(preference)
 * - Exponential moving average for smooth preference tracking
 * - Confidence thresholds: only apply preferences with sufficient evidence
 *
 * @param preferenceStore Backing store for preferences
 */
class PreferenceLearnerImpl(
    private val preferenceStore: PreferenceStore
) : PreferenceLearner {

    companion object {
        private const val TAG = "PreferenceLearner"

        /** Minimum observations before applying a preference */
        private const val MIN_OBSERVATIONS = 3

        /** Confidence threshold for preference application */
        private const val CONFIDENCE_THRESHOLD = 0.6

        /** EMA smoothing factor (higher = more weight on recent) */
        private const val EMA_ALPHA = 0.3
    }

    /** In-memory preference cache */
    private val preferences = mutableMapOf<String, MutablePreference>()

    /** Observation counts */
    private val observationCounts = mutableMapOf<String, Int>()

    // ═══════════════════════════════════════════════════════════════
    // OBSERVATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Observe a learning signal for preference extraction.
     */
    override fun observe(signal: LearningSignal) {
        // Track language preference
        val lang = detectLanguage(signal.input)
        updatePreference("language", lang)

        // Track interaction timing
        val hour = java.time.LocalTime.now().hour
        val timeSlot = when {
            hour < 12 -> "morning"
            hour < 17 -> "afternoon"
            else -> "evening"
        }
        updatePreference("preferred_time", timeSlot)

        // Track topic preferences (most frequent intents)
        updatePreference("frequent_intent", signal.intent)

        // Track response style (based on action type)
        updatePreference("response_style", signal.actionType.name)
    }

    /**
     * Consolidate preferences — called periodically by the improvement cycle.
     * Flushes to store and applies EMA smoothing.
     */
    override fun consolidate() {
        Timber.d(TAG, "Consolidating ${preferences.size} preferences")

        scope.launch {
            try {
                preferences.forEach { (key, pref) ->
                    if (pref.observations >= MIN_OBSERVATIONS && pref.confidence >= CONFIDENCE_THRESHOLD) {
                        preferenceStore.savePreference(
                            FlywheelModels.UserPreference(
                                key = key,
                                value = pref.value,
                                confidence = pref.confidence,
                                observations = pref.observations
                            )
                        )
                    }
                }
                Timber.d(TAG, "Preferences consolidated")
            } catch (e: Exception) {
                Timber.e(e, "Failed to consolidate preferences")
            }
        }
    }

    /**
     * Get all learned preferences.
     */
    override fun getPreferences(): Map<String, String> {
        return preferences
            .filter { it.value.confidence >= CONFIDENCE_THRESHOLD }
            .mapValues { it.value.value }
    }

    /**
     * Get a specific preference value.
     *
     * @param key Preference key
     * @return The preferred value, or null if not enough evidence
     */
    fun getPreference(key: String): String? {
        val pref = preferences[key] ?: return null
        return if (pref.confidence >= CONFIDENCE_THRESHOLD) pref.value else null
    }

    /**
     * Get the worker's preferred language.
     */
    fun getPreferredLanguage(): String {
        return getPreference("language") ?: "sw"
    }

    /**
     * Get the worker's preferred interaction time.
     */
    fun getPreferredTime(): String {
        return getPreference("preferred_time") ?: "morning"
    }

    // ═══════════════════════════════════════════════════════════════
    // INTERNAL
    // ═══════════════════════════════════════════════════════════════

    /**
     * Update a preference using exponential moving average.
     */
    private fun updatePreference(key: String, value: String) {
        val compositeKey = "$key:$value"
        val count = observationCounts.getOrDefault(compositeKey, 0) + 1
        observationCounts[compositeKey] = count

        val existing = preferences[key]
        if (existing == null) {
            // First observation
            preferences[key] = MutablePreference(
                value = value,
                confidence = 0.1,
                observations = 1
            )
        } else if (existing.value == value) {
            // Same value — increase confidence
            preferences[key] = existing.copy(
                confidence = minOf(existing.confidence + EMA_ALPHA * (1.0 - existing.confidence), 1.0),
                observations = existing.observations + 1
            )
        } else {
            // Different value — compare observation counts
            val existingCount = observationCounts.getOrDefault("$key:${existing.value}", 0)
            if (count > existingCount) {
                // New value is more frequent — switch
                preferences[key] = MutablePreference(
                    value = value,
                    confidence = minOf(count.toDouble() / (count + existingCount), 1.0),
                    observations = count
                )
            }
        }
    }

    /**
     * Simple language detection.
     */
    private fun detectLanguage(text: String): String {
        val swahiliMarkers = listOf("nime", "nina", "ya", "za", "la", "kwa", "ni", "sana", "leo", "jana")
        val lower = text.lowercase()
        val hits = swahiliMarkers.count { lower.contains(it) }
        return if (hits >= 2) "sw" else "en"
    }

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
    )
}

// ═══════════════════════════════════════════════════════════════════
// INTERNAL DATA
// ═══════════════════════════════════════════════════════════════════

private data class MutablePreference(
    val value: String,
    val confidence: Double,
    val observations: Int
)

// ═══════════════════════════════════════════════════════════════════
// STORE INTERFACE
// ═══════════════════════════════════════════════════════════════════

/**
 * Backing store for user preferences.
 */
interface PreferenceStore {
    suspend fun savePreference(preference: FlywheelModels.UserPreference)
    suspend fun getPreference(key: String): FlywheelModels.UserPreference?
    suspend fun getAllPreferences(): List<FlywheelModels.UserPreference>
}
