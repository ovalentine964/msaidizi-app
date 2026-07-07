package com.msaidizi.app.voice.emotion

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Voice emotion/sentiment detector for Msaidizi.
 *
 * Analyzes vocal prosody features to detect the user's emotional state.
 * Critical for building trust with informal economy users who need to
 * feel comfortable with AI-mediated financial services.
 *
 * Detected emotions:
 * - NEUTRAL: Calm, conversational tone
 * - HAPPY: Positive, upbeat (e.g., after a good sale)
 * - FRUSTRATED: Annoyed, impatient (e.g., system errors, slow response)
 * - CONFUSED: Uncertain, questioning (e.g., didn't understand)
 * - ANXIOUS: Worried, stressed (e.g., financial concerns)
 * - URGENT: Pressed for time, needs quick answer
 *
 * Analysis approach (no ML model required):
 * 1. Pitch analysis: High pitch + variance = emotional arousal
 * 2. Speaking rate: Fast = urgency/frustration, slow = confusion/sadness
 * 3. Energy/volume: Loud = frustration/urgency, quiet = anxiety/sadness
 * 4. Pause patterns: Long pauses = thinking/confusion, short = urgency
 * 5. Pitch contour: Rising = questioning, falling = certainty
 *
 * Latency: <1ms (pure signal processing, no ML inference)
 *
 * Usage in voice pipeline:
 * - Adjust TTS tone based on detected emotion
 * - Trigger empathy responses for frustrated users
 * - Speed up responses for urgent users
 * - Provide clarification for confused users
 * - Log emotional patterns for UX improvement
 *
 * @see VoiceEmotion for the emotion enum
 * @see EmotionAnalysis for the full analysis result
 */
@Singleton
class VoiceEmotionDetector @Inject constructor() {

    companion object {
        private const val TAG = "EmotionDetect"

        // Pitch thresholds (Hz)
        private const val PITCH_LOW = 80f
        private const val PITCH_HIGH = 250f
        private const val PITCH_VARIANCE_HIGH = 80f

        // Speaking rate thresholds (words/second)
        private const val RATE_SLOW = 2.0f
        private const val RATE_FAST = 4.5f

        // Energy thresholds (RMS amplitude, normalized)
        private const val ENERGY_LOW = 0.1f
        private const val ENERGY_HIGH = 0.6f

        // Pause thresholds (ms)
        private const val PAUSE_LONG = 1500L
        private const val PAUSE_SHORT = 200L

        // Pitch contour analysis window
        private const val CONTOUR_WINDOW_MS = 500L
    }

    // Emotion history for temporal smoothing
    private val emotionHistory = mutableListOf<EmotionAnalysis>()
    private val maxHistorySize = 10

    // ────────────────────── Detection API ──────────────────────

    /**
     * Detect emotion from audio features.
     *
     * @param features Extracted audio features
     * @return EmotionAnalysis with detected emotion and confidence
     */
    fun detect(features: AudioEmotionFeatures): EmotionAnalysis {
        val scores = mutableMapOf<VoiceEmotion, Float>()

        // Score each emotion based on feature combinations
        scores[VoiceEmotion.NEUTRAL] = scoreNeutral(features)
        scores[VoiceEmotion.HAPPY] = scoreHappy(features)
        scores[VoiceEmotion.FRUSTRATED] = scoreFrustrated(features)
        scores[VoiceEmotion.CONFUSED] = scoreConfused(features)
        scores[VoiceEmotion.ANXIOUS] = scoreAnxious(features)
        scores[VoiceEmotion.URGENT] = scoreUrgent(features)

        // Normalize scores to sum to 1.0
        val total = scores.values.sum().coerceAtLeast(0.01f)
        val normalizedScores = scores.mapValues { it.value / total }

        // Select top emotion
        val topEmotion = normalizedScores.maxByOrNull { it.value }

        val analysis = EmotionAnalysis(
            primaryEmotion = topEmotion?.key ?: VoiceEmotion.NEUTRAL,
            confidence = topEmotion?.value ?: 0.5f,
            emotionScores = normalizedScores,
            arousal = calculateArousal(features),      // 0.0 = calm, 1.0 = excited
            valence = calculateValence(features),      // 0.0 = negative, 1.0 = positive
            features = features
        )

        // Add to history for temporal smoothing
        emotionHistory.add(analysis)
        if (emotionHistory.size > maxHistorySize) {
            emotionHistory.removeAt(0)
        }

        Timber.tag(TAG).d(
            "Emotion: %s (conf: %.2f, arousal: %.2f, valence: %.2f)",
            analysis.primaryEmotion, analysis.confidence, analysis.arousal, analysis.valence
        )

        return analysis
    }

    /**
     * Detect emotion with temporal smoothing.
     * Uses the last N detections to smooth out noise.
     */
    fun detectSmoothed(features: AudioEmotionFeatures): EmotionAnalysis {
        val current = detect(features)

        if (emotionHistory.size < 3) return current

        // Weighted average of recent emotions (more recent = higher weight)
        val weights = emotionHistory.indices.map { (it + 1).toFloat() }
        val totalWeight = weights.sum()

        val smoothedScores = mutableMapOf<VoiceEmotion, Float>()
        for (emotion in VoiceEmotion.entries) {
            var weightedSum = 0f
            for (i in emotionHistory.indices) {
                weightedSum += (emotionHistory[i].emotionScores[emotion] ?: 0f) * weights[i]
            }
            smoothedScores[emotion] = weightedSum / totalWeight
        }

        val topEmotion = smoothedScores.maxByOrNull { it.value }

        return current.copy(
            primaryEmotion = topEmotion?.key ?: current.primaryEmotion,
            confidence = topEmotion?.value ?: current.confidence,
            emotionScores = smoothedScores
        )
    }

    /**
     * Get the recommended response tone based on detected emotion.
     * Used to adjust TTS voice parameters and response style.
     */
    fun getRecommendedTone(emotion: VoiceEmotion): ResponseTone {
        return when (emotion) {
            VoiceEmotion.NEUTRAL -> ResponseTone(
                speed = 1.0f,
                warmth = 0.6f,
                verbosity = Verbosity.NORMAL,
                preamble = null
            )
            VoiceEmotion.HAPPY -> ResponseTone(
                speed = 1.1f,      // Slightly faster, energetic
                warmth = 0.8f,     // Warm, matching user's mood
                verbosity = Verbosity.NORMAL,
                preamble = "Sawa! "  // "Good!"
            )
            VoiceEmotion.FRUSTRATED -> ResponseTone(
                speed = 0.9f,      // Slower, calmer
                warmth = 0.9f,     // Very warm, empathetic
                verbosity = Verbosity.CONCISE,
                preamble = "Pole. "  // "Sorry."
            )
            VoiceEmotion.CONFUSED -> ResponseTone(
                speed = 0.8f,      // Slower, clearer
                warmth = 0.7f,
                verbosity = Verbosity.DETAILED,  // More explanation
                preamble = "Sikiliza. "  // "Listen."
            )
            VoiceEmotion.ANXIOUS -> ResponseTone(
                speed = 0.85f,     // Calm pace
                warmth = 0.9f,     // Very reassuring
                verbosity = Verbosity.CONCISE,
                preamble = "Usijali. "  // "Don't worry."
            )
            VoiceEmotion.URGENT -> ResponseTone(
                speed = 1.2f,      // Fast, to the point
                warmth = 0.5f,     // Less warm, more direct
                verbosity = Verbosity.MINIMAL,
                preamble = null
            )
        }
    }

    /**
     * Clear emotion history (e.g., at start of new conversation).
     */
    fun reset() {
        emotionHistory.clear()
    }

    // ────────────────────── Scoring Functions ──────────────────────

    /**
     * Score for NEUTRAL emotion.
     * Neutral = moderate pitch, normal rate, moderate energy, few long pauses.
     */
    private fun scoreNeutral(f: AudioEmotionFeatures): Float {
        var score = 0.5f  // Baseline

        // Pitch in normal range
        if (f.averagePitchHz in PITCH_LOW..PITCH_HIGH) score += 0.2f
        if (f.pitchVariance < PITCH_VARIANCE_HIGH * 0.5f) score += 0.1f

        // Normal speaking rate
        if (f.speakingRate in RATE_SLOW..RATE_FAST) score += 0.2f

        // Moderate energy
        if (f.energyRms in ENERGY_LOW..ENERGY_HIGH) score += 0.1f

        // Few long pauses
        if (f.longPauseCount < 2) score += 0.1f

        return score
    }

    /**
     * Score for HAPPY emotion.
     * Happy = higher pitch, faster rate, higher energy, rising pitch contour.
     */
    private fun scoreHappy(f: AudioEmotionFeatures): Float {
        var score = 0f

        // Higher pitch
        if (f.averagePitchHz > PITCH_HIGH * 0.8f) score += 0.25f

        // Higher pitch variance (expressive)
        if (f.pitchVariance > PITCH_VARIANCE_HIGH * 0.7f) score += 0.2f

        // Slightly faster rate
        if (f.speakingRate > RATE_FAST * 0.8f) score += 0.15f

        // Higher energy
        if (f.energyRms > ENERGY_HIGH * 0.7f) score += 0.15f

        // Rising pitch contour (upbeat)
        if (f.pitchContourSlope > 0.1f) score += 0.15f

        // Few pauses (flowing speech)
        if (f.longPauseCount < 1) score += 0.1f

        return score
    }

    /**
     * Score for FRUSTRATED emotion.
     * Frustrated = high pitch variance, fast rate, high energy, stressed pauses.
     */
    private fun scoreFrustrated(f: AudioEmotionFeatures): Float {
        var score = 0f

        // High pitch variance (irritated)
        if (f.pitchVariance > PITCH_VARIANCE_HIGH) score += 0.3f

        // Fast speaking rate
        if (f.speakingRate > RATE_FAST) score += 0.2f

        // High energy (loud)
        if (f.energyRms > ENERGY_HIGH) score += 0.25f

        // Stressed pauses (short bursts of speech)
        if (f.shortPauseCount > 3) score += 0.15f

        // Falling pitch contour (emphatic)
        if (f.pitchContourSlope < -0.1f) score += 0.1f

        return score
    }

    /**
     * Score for CONFUSED emotion.
     * Confused = lower pitch, slower rate, long pauses, rising pitch (questioning).
     */
    private fun scoreConfused(f: AudioEmotionFeatures): Float {
        var score = 0f

        // Lower pitch
        if (f.averagePitchHz < PITCH_HIGH * 0.6f) score += 0.15f

        // Slower rate (hesitant)
        if (f.speakingRate < RATE_SLOW * 1.2f) score += 0.25f

        // Long pauses (thinking)
        if (f.longPauseCount > 2) score += 0.3f

        // Rising pitch contour (questioning)
        if (f.pitchContourSlope > 0.15f) score += 0.2f

        // Lower energy (uncertain)
        if (f.energyRms < ENERGY_LOW * 2f) score += 0.1f

        return score
    }

    /**
     * Score for ANXIOUS emotion.
     * Anxious = variable pitch, moderate-fast rate, lower energy, frequent pauses.
     */
    private fun scoreAnxious(f: AudioEmotionFeatures): Float {
        var score = 0f

        // Variable pitch (nervous)
        if (f.pitchVariance > PITCH_VARIANCE_HIGH * 0.6f) score += 0.2f

        // Moderate to fast rate
        if (f.speakingRate > RATE_SLOW * 1.3f) score += 0.15f

        // Lower energy (quiet, worried)
        if (f.energyRms < ENERGY_LOW * 2.5f) score += 0.25f

        // Frequent pauses (uncertain)
        if (f.shortPauseCount > 2) score += 0.2f

        // High pitch (tension)
        if (f.averagePitchHz > PITCH_HIGH * 0.7f) score += 0.2f

        return score
    }

    /**
     * Score for URGENT emotion.
     * Urgent = high rate, high energy, short pauses, emphatic pitch.
     */
    private fun scoreUrgent(f: AudioEmotionFeatures): Float {
        var score = 0f

        // Very fast rate
        if (f.speakingRate > RATE_FAST * 1.1f) score += 0.35f

        // High energy (commanding)
        if (f.energyRms > ENERGY_HIGH * 0.8f) score += 0.25f

        // Few pauses (rushing)
        if (f.longPauseCount == 0 && f.shortPauseCount < 2) score += 0.2f

        // High pitch (pressing)
        if (f.averagePitchHz > PITCH_HIGH * 0.7f) score += 0.1f

        // Falling contour (commanding)
        if (f.pitchContourSlope < -0.05f) score += 0.1f

        return score
    }

    // ────────────────────── Feature Analysis ──────────────────────

    /**
     * Calculate arousal level (calm ↔ excited).
     */
    private fun calculateArousal(f: AudioEmotionFeatures): Float {
        val pitchArousal = (f.pitchVariance / (PITCH_VARIANCE_HIGH * 2)).coerceIn(0f, 1f)
        val rateArousal = ((f.speakingRate - RATE_SLOW) / (RATE_FAST - RATE_SLOW)).coerceIn(0f, 1f)
        val energyArousal = (f.energyRms / ENERGY_HIGH).coerceIn(0f, 1f)

        return (pitchArousal * 0.3f + rateArousal * 0.3f + energyArousal * 0.4f)
    }

    /**
     * Calculate valence level (negative ↔ positive).
     */
    private fun calculateValence(f: AudioEmotionFeatures): Float {
        // Rising pitch = positive (happy, engaged)
        // Falling pitch = negative (frustrated, sad)
        val pitchValence = (f.pitchContourSlope + 0.5f).coerceIn(0f, 1f)

        // Higher energy = more positive (engaged vs withdrawn)
        val energyValence = (f.energyRms / ENERGY_HIGH).coerceIn(0f, 1f)

        // Normal rate = positive (comfortable vs rushed/hesitant)
        val rateDistance = abs(f.speakingRate - (RATE_SLOW + RATE_FAST) / 2)
        val maxRateDistance = (RATE_FAST - RATE_SLOW) / 2
        val rateValence = 1f - (rateDistance / maxRateDistance).coerceIn(0f, 1f)

        return (pitchValence * 0.4f + energyValence * 0.3f + rateValence * 0.3f)
    }
}

// ════════════════════════════════════════════════════════════════════
// DATA CLASSES
// ════════════════════════════════════════════════════════════════════

/**
 * Detected voice emotions.
 */
enum class VoiceEmotion {
    NEUTRAL,
    HAPPY,
    FRUSTRATED,
    CONFUSED,
    ANXIOUS,
    URGENT
}

/**
 * Audio features extracted for emotion analysis.
 */
data class AudioEmotionFeatures(
    val averagePitchHz: Float,       // Average fundamental frequency (F0)
    val pitchVariance: Float,        // Pitch variation (jitter)
    val pitchContourSlope: Float,    // Pitch trend: positive = rising, negative = falling
    val speakingRate: Float,         // Words per second
    val energyRms: Float,            // Root mean square energy (normalized)
    val longPauseCount: Int,         // Number of pauses > 1.5s
    val shortPauseCount: Int,        // Number of pauses 0.2-1.5s
    val zeroCrossingRate: Float,     // Signal complexity indicator
    val spectralCentroid: Float      // Brightness of voice
)

/**
 * Full emotion analysis result.
 */
data class EmotionAnalysis(
    val primaryEmotion: VoiceEmotion,
    val confidence: Float,
    val emotionScores: Map<VoiceEmotion, Float>,
    val arousal: Float,              // 0.0 = calm, 1.0 = excited
    val valence: Float,              // 0.0 = negative, 1.0 = positive
    val features: AudioEmotionFeatures
)

/**
 * Recommended response tone based on detected emotion.
 */
data class ResponseTone(
    val speed: Float,                // TTS speed multiplier (0.5-2.0)
    val warmth: Float,               // Voice warmth (0.0-1.0)
    val verbosity: Verbosity,        // How much detail to include
    val preamble: String?            // Opening phrase (e.g., "Pole" for empathy)
)

enum class Verbosity {
    MINIMAL,   // Just the answer
    CONCISE,   // Answer + brief context
    NORMAL,    // Answer + context + confirmation
    DETAILED   // Full explanation
}
