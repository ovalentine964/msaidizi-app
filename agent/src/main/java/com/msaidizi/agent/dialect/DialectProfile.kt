package com.msaidizi.agent.dialect

import javax.inject.Inject
import javax.inject.Singleton

/**
 * DialectProfile — On-device representation of a worker's dialect features.
 *
 * Evolves through four phases:
 * - COLD_START (Day 0-3): Default to regional dialect
 * - DETECTION (Day 3-14): Identify specific dialect variant
 * - COLLECTION (Day 14-30): Accumulate data for LoRA training
 * - PERSONALIZED (Day 30+): Personal LoRA adapter active
 *
 * Each profile holds phonetic, lexical, grammatical, and prosodic features
 * extracted from voice interactions. Never stores raw audio.
 */
@Singleton
class DialectProfileManager @Inject constructor(
    private val featureExtractor: DialectFeatureExtractor
) {
    private val profiles = mutableMapOf<String, DialectProfile>()

    /**
     * Get or create a dialect profile for a worker.
     */
    fun getOrCreateProfile(workerId: String, region: String = "KE-Nairobi"): DialectProfile {
        return profiles.getOrPut(workerId) {
            DialectProfile(
                workerId = workerId,
                primaryLanguage = guessLanguageFromRegion(region),
                region = region,
                dialectCode = regionToDialect(region),
                phase = DialectPhase.COLD_START,
                confidence = 0.1f
            )
        }
    }

    /**
     * Process a voice interaction and update the dialect profile.
     */
    fun processInteraction(
        workerId: String,
        audioFeatures: FloatArray?,
        transcript: String,
        intent: String?,
        asrConfidence: Float
    ) {
        val profile = getOrCreateProfile(workerId)

        // Extract features from this interaction
        val dfv = featureExtractor.extract(
            transcript = transcript,
            audioFeatures = audioFeatures,
            asrConfidence = asrConfidence,
            intent = intent
        )

        // Update profile with new features
        profile.phoneticProfile = featureExtractor.updatePhonetic(profile.phoneticProfile, dfv)
        profile.vocabulary = featureExtractor.updateVocabulary(profile.vocabulary, dfv)
        profile.grammarProfile = featureExtractor.updateGrammar(profile.grammarProfile, dfv)
        profile.prosodicProfile = featureExtractor.updateProsody(profile.prosodicProfile, dfv)

        // Update interaction counts
        profile.totalInteractions++
        if (asrConfidence > 0.8f && intent != null) {
            profile.highConfidenceInteractions++
        }

        // Phase transitions
        updatePhase(profile)

        // Update confidence
        profile.confidence = computeConfidence(profile)
        profile.lastUpdated = System.currentTimeMillis()

        profiles[workerId] = profile
    }

    /**
     * Get the current dialect code for a worker.
     */
    fun getDialectCode(workerId: String): String? {
        return profiles[workerId]?.dialectCode
    }

    /**
     * Get the confidence score for a worker's dialect profile.
     */
    fun getConfidence(workerId: String): Float {
        return profiles[workerId]?.confidence ?: 0f
    }

    /**
     * Check if the worker has a personalized LoRA adapter ready.
     */
    fun hasPersonalAdapter(workerId: String): Boolean {
        val profile = profiles[workerId] ?: return false
        return profile.phase == DialectPhase.PERSONALIZED && profile.loraAdapter != null
    }

    /**
     * Get profile summary for backend sync (no raw data).
     */
    fun getSyncPayload(workerId: String): DialectSyncPayload? {
        val profile = profiles[workerId] ?: return null
        return DialectSyncPayload(
            dialectCode = profile.dialectCode,
            region = profile.region,
            vocabularyDeltas = profile.vocabulary.getNewTerms(),
            phoneticSummary = profile.phoneticProfile.toSummary(),
            grammarSummary = profile.grammarProfile.toSummary(),
            codeSwitchRatio = profile.vocabulary.codeSwitchRatio,
            totalInteractions = profile.totalInteractions,
            asrAccuracy = profile.recentAsrAccuracy,
            confidence = profile.confidence
        )
    }

    // ── Private helpers ──────────────────────────────────────

    private fun updatePhase(profile: DialectProfile) {
        when (profile.phase) {
            DialectPhase.COLD_START -> {
                if (profile.totalInteractions >= 10) {
                    profile.phase = DialectPhase.DETECTION
                }
            }
            DialectPhase.DETECTION -> {
                if (profile.totalInteractions >= 50 && profile.confidence > 0.3f) {
                    profile.phase = DialectPhase.COLLECTION
                }
            }
            DialectPhase.COLLECTION -> {
                if (profile.highConfidenceInteractions >= 50 && profile.totalInteractions >= 150) {
                    // Ready for LoRA training
                    profile.phase = DialectPhase.PERSONALIZED
                    profile.loraAdapter = ByteArray(0) // Placeholder for actual LoRA weights
                }
            }
            DialectPhase.PERSONALIZED -> {
                // Incremental updates every 50 interactions
                if (profile.totalInteractions % 50 == 0) {
                    profile.loraVersion++
                }
            }
        }
    }

    private fun computeConfidence(profile: DialectProfile): Float {
        val dataQuantity = (profile.totalInteractions / 300f).coerceIn(0f, 1f)
        val asrQuality = profile.recentAsrAccuracy
        val intentQuality = profile.recentIntentMatchRate
        val vocabCoverage = profile.vocabulary.coverage()
        val loraBoost = if (profile.loraAdapter != null) 0.3f else 0f

        return (dataQuantity * 0.20f +
                asrQuality * 0.25f +
                intentQuality * 0.25f +
                vocabCoverage * 0.15f +
                loraBoost * 0.15f).coerceIn(0f, 1f)
    }

    private fun guessLanguageFromRegion(region: String): String = when {
        region.startsWith("KE") -> "sw"
        region.startsWith("TZ") -> "sw"
        region.startsWith("UG") -> "sw"
        region.startsWith("ET") -> "amh"
        region.startsWith("NG") -> "yo"
        else -> "sw"
    }

    private fun regionToDialect(region: String): String = when (region) {
        "KE-Nairobi" -> "sw-KE-urban"
        "KE-Mombasa" -> "sw-KE-coastal"
        "TZ-Dar" -> "sw-TZ-dar"
        "UG-Kampala" -> "sw-UG"
        else -> "sw-KE-urban"
    }
}

/**
 * Phases of dialect profile evolution.
 */
enum class DialectPhase {
    COLD_START,    // Days 0-3, confidence 0-20%
    DETECTION,     // Days 3-14, confidence 20-50%
    COLLECTION,    // Days 14-30, confidence 50-80%
    PERSONALIZED   // Days 30+, confidence 80-95%
}

/**
 * On-device dialect profile — evolves over 30+ days of interaction.
 */
data class DialectProfile(
    val workerId: String,
    var primaryLanguage: String,
    val region: String,
    var dialectCode: String,
    var phase: DialectPhase = DialectPhase.COLD_START,
    var confidence: Float = 0f,
    var totalInteractions: Int = 0,
    var highConfidenceInteractions: Int = 0,
    var phoneticProfile: PhoneticProfile = PhoneticProfile(),
    var vocabulary: PersonalVocabulary = PersonalVocabulary(),
    var grammarProfile: GrammarProfile = GrammarProfile(),
    var prosodicProfile: ProsodicProfile = ProsodicProfile(),
    var loraAdapter: ByteArray? = null,
    var loraVersion: Int = 0,
    var recentAsrAccuracy: Float = 0.7f,
    var recentIntentMatchRate: Float = 0.7f,
    var lastUpdated: Long = System.currentTimeMillis()
)

/**
 * Phonetic features — how the worker pronounces sounds.
 */
data class PhoneticProfile(
    /** Canonical phoneme → {variant → frequency} */
    val substitutions: MutableMap<String, MutableMap<String, Float>> = mutableMapOf(),
    val sampleCount: Int = 0
) {
    fun toSummary(): Map<String, Any> = mapOf(
        "substitution_count" to substitutions.size,
        "sample_count" to sampleCount
    )
}

/**
 * Personal vocabulary — words the worker uses.
 */
data class PersonalVocabulary(
    val terms: MutableMap<String, VocabEntry> = mutableMapOf(),
    val unknownTerms: MutableMap<String, Int> = mutableMapOf(),
    var totalUniqueTerms: Int = 0,
    var codeSwitchRatio: Float = 0f,
    private val newTermQueue: MutableList<String> = mutableListOf()
) {
    fun coverage(): Float {
        if (totalUniqueTerms == 0) return 0f
        val knownRatio = (totalUniqueTerms - unknownTerms.size).toFloat() / totalUniqueTerms
        return knownRatio.coerceIn(0f, 1f)
    }

    fun getNewTerms(): List<String> {
        val terms = newTermQueue.toList()
        newTermQueue.clear()
        return terms
    }

    fun addTerm(term: String, language: String, category: String) {
        if (term !in terms) {
            terms[term] = VocabEntry(term, language, category)
            totalUniqueTerms = terms.size
            newTermQueue.add(term)
        } else {
            terms[term] = terms[term]!!.copy(frequency = terms[term]!!.frequency + 1)
        }
    }
}

data class VocabEntry(
    val term: String,
    val language: String, // "sw", "en", "sheng"
    val category: String, // "financial", "product", "slang", "grammar"
    val frequency: Int = 1
)

/**
 * Grammar features — sentence structures and verb patterns.
 */
data class GrammarProfile(
    val tensePatterns: MutableMap<String, Float> = mutableMapOf(),
    val codeSwitchTriggers: MutableMap<String, Float> = mutableMapOf(),
    val sampleCount: Int = 0
) {
    fun toSummary(): Map<String, Any> = mapOf(
        "tense_patterns" to tensePatterns,
        "switch_triggers" to codeSwitchTriggers.size,
        "sample_count" to sampleCount
    )
}

/**
 * Prosodic features — rhythm, stress, intonation.
 */
data class ProsodicProfile(
    var meanSyllableRate: Float = 0f,
    var rhythmClass: String = "unknown",
    val pauseFillers: MutableMap<String, Int> = mutableMapOf(),
    val sampleCount: Int = 0
)

/**
 * Payload for backend sync — no raw audio, only aggregated signals.
 */
data class DialectSyncPayload(
    val dialectCode: String,
    val region: String,
    val vocabularyDeltas: List<String>,
    val phoneticSummary: Map<String, Any>,
    val grammarSummary: Map<String, Any>,
    val codeSwitchRatio: Float,
    val totalInteractions: Int,
    val asrAccuracy: Float,
    val confidence: Float
)
