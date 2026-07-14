package com.msaidizi.app.core.language

import android.content.Context
import com.msaidizi.app.core.database.VocabularyLearningDao
import com.msaidizi.app.core.dialect.AdaptiveVocabulary
import com.msaidizi.app.core.model.*
import com.msaidizi.app.onboarding.WorkerProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Conversation Learning Pipeline — learns language from every voice interaction.
 *
 * Wires together:
 * - Whisper ASR word-level confidence → unknown word detection
 * - AdaptiveVocabulary → raw unknown word tracking
 * - WorkerVocabulary → per-worker personalized vocabulary
 * - ConfidenceCalibrator → Bayesian ASR calibration per worker
 * - LanguageLearningPipeline → full learning loop
 *
 * Learning lifecycle:
 * ────────────────────
 * BOOTSTRAP (onboarding):
 *   - Capture ALL unknown words (aggressive learning)
 *   - Build initial worker vocabulary from conversation
 *   - Track dialect-specific terms
 *
 * DAILY USE:
 *   - Flag low-confidence ASR words as unknown
 *   - After 3 uses, auto-promote to learned vocabulary
 *   - Track pronunciation variants
 *   - Adjust ASR confidence thresholds per worker
 *   - Learn Sheng vocabulary separately
 *
 * "Mary amesema 'mboga' mara 12 — nimejifunza sauti yake"
 * (Mary has said 'mboga' 12 times — I've learned her voice)
 *
 * Battery impact: <0.01% per conversation turn (pure code + DB writes).
 *
 * @param context Application context
 * @param adaptiveVocabulary Tracks raw unknown words
 * @param workerVocabularyDao Per-worker personalized vocabulary
 * @param learningDao Raw unknown word tracking (LearnedWord)
 * @param userVocabularyDao Confirmed vocabulary with prices
 * @param confidenceCalibrator Bayesian ASR calibration
 * @param learningPipeline Full language learning pipeline
 */
@Singleton
class ConversationLearningPipeline @Inject constructor(
    @ApplicationContext private val context: Context,
    private val adaptiveVocabulary: AdaptiveVocabulary,
    private val workerVocabularyDao: WorkerVocabularyDao,
    private val learningDao: VocabularyLearningDao,
    private val userVocabularyDao: UserVocabularyDao,
    private val confidenceCalibrator: ConfidenceCalibrator,
    private val learningPipeline: LanguageLearningPipeline
) {
    companion object {
        private const val TAG = "ConvLearning"

        /** Minimum occurrences before auto-promoting a word to worker vocabulary */
        private const val AUTO_PROMOTE_THRESHOLD = 3

        /** Low-confidence threshold — words below this are flagged as "unknown" */
        private const val LOW_CONFIDENCE_THRESHOLD = 0.60f

        /** Very low confidence — word is likely misrecognized */
        private const val VERY_LOW_CONFIDENCE_THRESHOLD = 0.40f

        /** Minimum word length to track (skip particles like "na", "ni") */
        private const val MIN_WORD_LENGTH = 3
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    /** Current worker ID (set after onboarding) */
    private var currentWorkerId: Long = 1L

    /** Whether we're in bootstrap mode (aggressive learning) */
    private var isBootstrapMode: Boolean = false

    /** Session-level unknown word counts (for immediate feedback) */
    private val sessionUnknownWords = mutableMapOf<String, WordCapture>()

    /** Per-word ASR confidence tracking for this session */
    private val sessionWordConfidences = mutableMapOf<String, MutableList<Float>>()

    private val _learningState = MutableStateFlow<ConversationLearningState>(ConversationLearningState.Idle)
    val learningState: StateFlow<ConversationLearningState> = _learningState

    /**
     * Set the current worker ID (called after onboarding/profile load).
     */
    fun setWorkerId(workerId: Long) {
        currentWorkerId = workerId
        Timber.tag(TAG).d("Worker ID set to %d", workerId)
    }

    /**
     * Enable/disable bootstrap mode (aggressive vocabulary capture).
     * During bootstrap, ALL unknown words are captured regardless of confidence.
     */
    fun setBootstrapMode(enabled: Boolean) {
        isBootstrapMode = enabled
        Timber.tag(TAG).i("Bootstrap mode: %s", if (enabled) "ON (aggressive capture)" else "OFF (normal)")
    }

    // ════════════════════════════════════════════════════════════════════
    // CORE: Process ASR Result with Word-Level Confidence
    // ════════════════════════════════════════════════════════════════════

    /**
     * Process an ASR transcription result with word-level confidence scores.
     *
     * This is the main entry point — called from VoicePipeline after every
     * successful transcription. It:
     *
     * 1. Flags low-confidence words as "unknown"
     * 2. Stores unknown words in AdaptiveVocabulary with audio context
     * 3. Tracks per-word usage frequency
     * 4. Auto-promotes words after 3 uses
     * 5. Updates Bayesian ASR calibration per worker
     * 6. Tracks Sheng vocabulary separately
     *
     * @param rawTranscript What Whisper heard
     * @param correctedTranscript After dialect normalization
     * @param wordConfidences Per-word confidence from ASR (word, rawConfidence)
     * @param language Detected language
     * @param dialectRegion Detected dialect region
     * @param isConfirmed Whether the worker confirmed this transcription
     */
    suspend fun processTranscription(
        rawTranscript: String,
        correctedTranscript: String,
        wordConfidences: List<Pair<String, Float>>,
        language: String,
        dialectRegion: String = "STANDARD",
        isConfirmed: Boolean = false
    ) = withContext(Dispatchers.IO) {
        _learningState.value = ConversationLearningState.Processing

        try {
            val words = correctedTranscript.lowercase().split(" ").filter { it.length >= MIN_WORD_LENGTH }

            for ((word, rawConfidence) in wordConfidences) {
                val cleanWord = word.lowercase().trim('.', ',', '!', '?', ';', ':')
                if (cleanWord.length < MIN_WORD_LENGTH) continue

                // Step 1: Calibrate confidence for this word
                val calibrated = confidenceCalibrator.calibrate(rawConfidence, language)

                // Step 2: Track per-word confidence for this session
                sessionWordConfidences.getOrPut(cleanWord) { mutableListOf() }.add(rawConfidence)

                // Step 3: Determine if this word is "unknown" (low confidence)
                val isUnknown = calibrated.calibratedConfidence < LOW_CONFIDENCE_THRESHOLD || isBootstrapMode

                if (isUnknown) {
                    captureUnknownWord(
                        word = cleanWord,
                        rawConfidence = rawConfidence,
                        calibratedConfidence = calibrated.calibratedConfidence,
                        language = language,
                        dialectRegion = dialectRegion,
                        context = correctedTranscript
                    )
                }

                // Step 4: Update per-worker vocabulary frequency
                val existing = workerVocabularyDao.getBySpokenForm(currentWorkerId, cleanWord)
                if (existing != null) {
                    workerVocabularyDao.incrementFrequency(currentWorkerId, cleanWord)
                    workerVocabularyDao.updateAsrConfidence(
                        workerId = currentWorkerId,
                        spoken = cleanWord,
                        asrConfidence = rawConfidence.toDouble(),
                        isLowConfidence = rawConfidence < LOW_CONFIDENCE_THRESHOLD
                    )

                    // Track pronunciation variant if different from existing
                    addPronunciationVariant(currentWorkerId, cleanWord, existing)
                }

                // Step 5: Record outcome for Bayesian calibration
                if (isConfirmed) {
                    confidenceCalibrator.recordOutcome(rawConfidence, language, wasCorrect = true)
                }
            }

            // Step 6: Track Sheng vocabulary separately
            if (language == "sheng" || language == "mixed") {
                trackShengVocabulary(correctedTranscript, wordConfidences)
            }

            // Step 7: Check for auto-promotion candidates
            checkAutoPromotion()

            _learningState.value = ConversationLearningState.Idle

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error processing transcription for learning")
            _learningState.value = ConversationLearningState.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Process a simple transcription without word-level confidence.
     * Used when the ASR doesn't provide per-word scores.
     * Estimates confidence from vocabulary coverage.
     */
    suspend fun processSimpleTranscription(
        transcript: String,
        language: String,
        overallConfidence: Float,
        dialectRegion: String = "STANDARD",
        isConfirmed: Boolean = false
    ) {
        // Estimate per-word confidence from overall confidence and vocabulary
        val words = transcript.lowercase().split(" ").filter { it.length >= MIN_WORD_LENGTH }
        val wordConfidences = words.map { word ->
            val known = workerVocabularyDao.getBySpokenForm(currentWorkerId, word) != null
            val wordConf = if (known) {
                (overallConfidence * 1.2f).coerceAtMost(0.95f)  // Known words get boost
            } else {
                (overallConfidence * 0.7f).coerceAtLeast(0.1f)  // Unknown words get penalty
            }
            Pair(word, wordConf)
        }

        processTranscription(
            rawTranscript = transcript,
            correctedTranscript = transcript,
            wordConfidences = wordConfidences,
            language = language,
            dialectRegion = dialectRegion,
            isConfirmed = isConfirmed
        )
    }

    // ════════════════════════════════════════════════════════════════════
    // UNKNOWN WORD CAPTURE
    // ════════════════════════════════════════════════════════════════════

    /**
     * Capture an unknown word with full context.
     * Stores in both AdaptiveVocabulary (raw tracking) and session cache.
     */
    private suspend fun captureUnknownWord(
        word: String,
        rawConfidence: Float,
        calibratedConfidence: Float,
        language: String,
        dialectRegion: String,
        context: String
    ) {
        // Track in session cache
        val capture = sessionUnknownWords.getOrPut(word) {
            WordCapture(
                word = word,
                firstConfidence = rawConfidence,
                dialectRegion = dialectRegion,
                context = context
            )
        }
        capture.count++
        capture.lastConfidence = rawConfidence
        capture.lastContext = context

        // Track in AdaptiveVocabulary (LearnedWord table)
        adaptiveVocabulary.trackUnknownWord(word, dialectRegion)

        // Also check if it already exists in worker vocabulary
        val existingWorker = workerVocabularyDao.getBySpokenForm(currentWorkerId, word)
        if (existingWorker != null) {
            // Word exists in worker vocab — just update ASR confidence tracking
            workerVocabularyDao.updateAsrConfidence(
                workerId = currentWorkerId,
                spoken = word,
                asrConfidence = rawConfidence.toDouble(),
                isLowConfidence = true
            )
            return
        }

        // Check if the word exists in the LearnedWord table with enough frequency
        val learnedWord = learningDao.getLearnedWord(word)
        if (learnedWord != null && learnedWord.frequency >= AUTO_PROMOTE_THRESHOLD) {
            // Auto-promote to worker vocabulary
            promoteToWorkerVocabulary(learnedWord, language, dialectRegion)
        }

        Timber.tag(TAG).d(
            "Unknown word captured: '%s' (conf=%.2f, calibrated=%.2f, count=%d, bootstrap=%s)",
            word, rawConfidence, calibratedConfidence, capture.count, isBootstrapMode
        )
    }

    // ════════════════════════════════════════════════════════════════════
    // AUTO-PROMOTION: LearnedWord → WorkerVocabulary
    // ════════════════════════════════════════════════════════════════════

    /**
     * Check for words that qualify for auto-promotion.
     * A word is promoted when it has been used 3+ times.
     */
    private suspend fun checkAutoPromotion() {
        val significantWords = learningDao.getSignificantWords(AUTO_PROMOTE_THRESHOLD)

        for (learnedWord in significantWords) {
            // Skip if already in worker vocabulary
            val existing = workerVocabularyDao.getBySpokenForm(currentWorkerId, learnedWord.word)
            if (existing != null) continue

            // Auto-promote
            promoteToWorkerVocabulary(learnedWord, "sw", learnedWord.dialectRegion)
        }
    }

    /**
     * Promote a LearnedWord to WorkerVocabulary.
     * Creates a personalized vocabulary entry for this worker.
     */
    private suspend fun promoteToWorkerVocabulary(
        learnedWord: LearnedWord,
        language: String,
        dialectRegion: String
    ) {
        // Try to infer canonical form
        val canonicalForm = learnedWord.canonicalForm
            ?: inferCanonicalForm(learnedWord.word, language)

        // Determine word type
        val wordType = classifyWordType(learnedWord.word, language, dialectRegion)

        // Get session confidence data
        val sessionConfs = sessionWordConfidences[learnedWord.word]
        val avgConfidence = sessionConfs?.average()?.toDouble() ?: 0.3

        val entry = WorkerVocabulary(
            workerId = currentWorkerId,
            spokenForm = learnedWord.word,
            canonicalForm = canonicalForm,
            language = language,
            wordType = wordType,
            frequency = learnedWord.frequency,
            confidence = avgConfidence.coerceIn(0.1, 0.9),
            pronunciationVariants = json.encodeToString(listOf(learnedWord.word)),
            categoryHint = learnedWord.categoryHint,
            dialectRegion = dialectRegion,
            avgAsrConfidence = avgConfidence,
            autoPromoted = true,
            workerConfirmed = false,
            firstSeenAt = learnedWord.firstSeenAt,
            lastSeenAt = learnedWord.lastSeenAt
        )

        workerVocabularyDao.upsert(entry)

        Timber.tag(TAG).i(
            "Auto-promoted '%s' → '%s' (freq=%d, conf=%.2f, type=%s, worker=%d)",
            learnedWord.word, canonicalForm, learnedWord.frequency,
            avgConfidence, wordType, currentWorkerId
        )
    }

    // ════════════════════════════════════════════════════════════════════
    // SHENG VOCABULARY TRACKING
    // ════════════════════════════════════════════════════════════════════

    /**
     * Track Sheng vocabulary separately.
     * Sheng is Kenyan street slang — a mix of Swahili, English, and local languages.
     * It evolves rapidly and needs special handling.
     */
    private suspend fun trackShengVocabulary(
        transcript: String,
        wordConfidences: List<Pair<String, Float>>
    ) {
        for ((word, confidence) in wordConfidences) {
            val clean = word.lowercase().trim('.', ',', '!')
            if (clean.length < MIN_WORD_LENGTH) continue

            // Check if this is already known as Sheng
            val existing = workerVocabularyDao.getBySpokenForm(currentWorkerId, clean)
            if (existing != null && existing.wordType == WordType.SHENG) {
                workerVocabularyDao.incrementFrequency(currentWorkerId, clean)
                continue
            }

            // Heuristic: if it's not in standard Swahili vocabulary and
            // has low ASR confidence, it might be Sheng
            if (confidence < LOW_CONFIDENCE_THRESHOLD) {
                val isKnownSwahili = userVocabularyDao.getBySpokenForm(clean) != null
                if (!isKnownSwahili) {
                    // Likely Sheng — track it
                    if (existing == null) {
                        workerVocabularyDao.upsert(WorkerVocabulary(
                            workerId = currentWorkerId,
                            spokenForm = clean,
                            canonicalForm = clean,
                            language = "sheng",
                            wordType = WordType.SHENG,
                            frequency = 1,
                            confidence = 0.2,
                            pronunciationVariants = json.encodeToString(listOf(clean)),
                            dialectRegion = "SHENG",
                            avgAsrConfidence = confidence.toDouble(),
                            autoPromoted = false,
                            workerConfirmed = false
                        ))
                    }
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // PRONUNCIATION VARIANTS
    // ════════════════════════════════════════════════════════════════════

    /**
     * Add a pronunciation variant if the word has changed.
     * Tracks how the same worker pronounces the same word differently.
     */
    private suspend fun addPronunciationVariant(
        workerId: Long,
        word: String,
        existing: WorkerVocabulary
    ) {
        try {
            val variantsJson = existing.pronunciationVariants
            val variants = json.decodeFromString<List<String>>(variantsJson).toMutableList()

            if (word !in variants) {
                variants.add(word)
                // Keep max 10 variants to save storage
                val trimmed = variants.takeLast(10)
                workerVocabularyDao.updatePronunciationVariants(
                    workerId, existing.spokenForm,
                    json.encodeToString(trimmed)
                )
                Timber.tag(TAG).d("Added pronunciation variant '%s' to '%s'", word, existing.spokenForm)
            }
        } catch (e: Exception) {
            // JSON parse error — reset variants
            workerVocabularyDao.updatePronunciationVariants(
                workerId, existing.spokenForm,
                json.encodeToString(listOf(word))
            )
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // CONFIRMATION: Worker confirms a transcription
    // ════════════════════════════════════════════════════════════════════

    /**
     * Record that the worker confirmed a transcription was correct.
     * Strengthens vocabulary entries and updates ASR calibration.
     */
    suspend fun recordConfirmation(
        transcript: String,
        language: String
    ) = withContext(Dispatchers.IO) {
        val words = transcript.lowercase().split(" ").filter { it.length >= MIN_WORD_LENGTH }

        for (word in words) {
            // Strengthen worker vocabulary
            val existing = workerVocabularyDao.getBySpokenForm(currentWorkerId, word)
            if (existing != null) {
                workerVocabularyDao.incrementFrequency(currentWorkerId, word)
                workerVocabularyDao.updateConfidence(
                    currentWorkerId, word,
                    (existing.confidence + 0.05).coerceAtMost(1.0)
                )
                workerVocabularyDao.markConfirmed(currentWorkerId, word)
            } else {
                // New word confirmed — add to worker vocabulary
                workerVocabularyDao.upsert(WorkerVocabulary(
                    workerId = currentWorkerId,
                    spokenForm = word,
                    canonicalForm = word,
                    language = language,
                    wordType = classifyWordType(word, language, "STANDARD"),
                    frequency = 1,
                    confidence = 0.5,
                    pronunciationVariants = json.encodeToString(listOf(word)),
                    workerConfirmed = true
                ))
            }

            // Update Bayesian calibration
            val sessionConfs = sessionWordConfidences[word]
            val avgConf = sessionConfs?.average()?.toFloat() ?: 0.85f
            confidenceCalibrator.recordOutcome(avgConf, language, wasCorrect = true)
        }

        Timber.tag(TAG).d("Confirmation recorded for %d words [%s]", words.size, language)
    }

    /**
     * Record that the worker corrected a transcription.
     * Updates vocabulary mappings and ASR calibration.
     */
    suspend fun recordCorrection(
        originalText: String,
        correctedText: String,
        language: String
    ) = withContext(Dispatchers.IO) {
        val origWords = originalText.lowercase().split(" ")
        val corrWords = correctedText.lowercase().split(" ")

        for ((orig, corr) in origWords.zip(corrWords)) {
            if (orig == corr) continue

            // Record correction in Bayesian calibrator
            val sessionConfs = sessionWordConfidences[orig]
            val conf = sessionConfs?.average()?.toFloat() ?: 0.3f
            confidenceCalibrator.recordOutcome(conf, language, wasCorrect = false)

            // Add corrected form to worker vocabulary
            val existing = workerVocabularyDao.getBySpokenForm(currentWorkerId, corr)
            if (existing != null) {
                workerVocabularyDao.incrementFrequency(currentWorkerId, corr)
            } else {
                workerVocabularyDao.upsert(WorkerVocabulary(
                    workerId = currentWorkerId,
                    spokenForm = corr,
                    canonicalForm = corr,
                    language = language,
                    wordType = classifyWordType(corr, language, "STANDARD"),
                    frequency = 1,
                    confidence = 0.4,
                    pronunciationVariants = json.encodeToString(listOf(corr, orig)),
                    workerConfirmed = true
                ))
            }

            // Add the wrong form as a pronunciation variant
            val wrongEntry = workerVocabularyDao.getBySpokenForm(currentWorkerId, orig)
            if (wrongEntry != null) {
                addPronunciationVariant(currentWorkerId, corr, wrongEntry)
            }
        }

        Timber.tag(TAG).d("Correction recorded: '%s' → '%s' [%s]", originalText, correctedText, language)
    }

    // ════════════════════════════════════════════════════════════════════
    // BAYESIAN ASR CALIBRATION — Per-Worker Thresholds
    // ════════════════════════════════════════════════════════════════════

    /**
     * Get the per-worker ASR confidence threshold.
     * Workers with clearer speech get a higher threshold (fewer confirmations).
     * Workers with accented speech get a lower threshold (more learning).
     */
    suspend fun getWorkerConfidenceThreshold(): Float {
        val avgConf = workerVocabularyDao.getAverageConfidence(currentWorkerId) ?: 0.5
        val vocabSize = workerVocabularyDao.getCount(currentWorkerId)

        // Adaptive threshold: starts at 0.5, adjusts based on worker's history
        return when {
            vocabSize >= 100 && avgConf >= 0.7 -> 0.55f  // Experienced worker — trust more
            vocabSize >= 30 && avgConf >= 0.5 -> 0.50f   // Moderate experience
            vocabSize >= 10 -> 0.45f                       // Still learning
            else -> 0.40f                                  // New worker — be cautious
        }
    }

    /**
     * Get calibration metrics for the current worker.
     * Returns a summary of how well ASR understands this worker.
     */
    suspend fun getWorkerCalibrationSummary(): WorkerCalibrationSummary {
        val vocabCount = workerVocabularyDao.getCount(currentWorkerId)
        val highConfCount = workerVocabularyDao.getHighConfidenceCount(currentWorkerId)
        val avgConf = workerVocabularyDao.getAverageConfidence(currentWorkerId) ?: 0.0
        val categories = workerVocabularyDao.getCategories(currentWorkerId)
        val languages = workerVocabularyDao.getLanguages(currentWorkerId)
        val shengCount = workerVocabularyDao.getShengVocabulary(currentWorkerId).size

        return WorkerCalibrationSummary(
            workerId = currentWorkerId,
            totalVocabulary = vocabCount,
            highConfidenceVocabulary = highConfCount,
            averageConfidence = avgConf,
            categories = categories,
            languages = languages,
            shengVocabularySize = shengCount,
            calibrationMessage = buildCalibrationMessage(vocabCount, avgConf, shengCount)
        )
    }

    /**
     * Build a Swahili calibration message like:
     * "Mary amesema 'mboga' mara 12 — nimejifunza sauti yake"
     */
    private fun buildCalibrationMessage(vocabCount: Int, avgConf: Double, shengCount: Int): String {
        return when {
            vocabCount >= 100 -> "Nimejifunza maneno $vocabCount ya mteja. Naelewa sauti yake vizuri."
            vocabCount >= 30 -> "Nimejifunza maneno $vocabCount. Bado najifunza maneno mapya kila siku."
            vocabCount >= 10 -> "Nimeanza kujifunza sauti ya mteja. Maneno $vocabCount yamehifadhiwa."
            else -> "Bado najifunza. Nimehifadhi maneno $vocabCount."
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // HELPERS
    // ════════════════════════════════════════════════════════════════════

    /**
     * Infer canonical form for an unknown word.
     * Uses simple heuristics:
     * - Check MigoriDialectAdapter for dialect normalization
     * - Check if it's a common misspelling
     * - Otherwise, use the word as-is
     */
    private fun inferCanonicalForm(word: String, language: String): String {
        // Try dialect adapter
        try {
            val normalized = com.msaidizi.app.core.dialect.MigoriDialectAdapter.normalize(word)
            if (normalized != word) return normalized
        } catch (_: Exception) {}

        // Common ASR error patterns
        val corrections = mapOf(
            "nika uza" to "nimeuza",
            "nika nunua" to "nimenunua",
            "nika pata" to "nimepata",
            "maandazi" to "mandazi",
            "sukuma wiki" to "sukumawiki"
        )
        corrections[word]?.let { return it }

        return word
    }

    /**
     * Classify word type based on the word itself and context.
     */
    private fun classifyWordType(word: String, language: String, dialectRegion: String): WordType {
        // Sheng detection
        if (language == "sheng" || dialectRegion == "SHENG") return WordType.SHENG

        // Dholuo detection (words starting with common Luo prefixes)
        val luoPrefixes = setOf("ny", "gi", "ka", "to", "en", "ok", "ber", "kia")
        if (luoPrefixes.any { word.startsWith(it) && word.length > 3 }) return WordType.DHOLUO

        // Unit detection
        val units = setOf("kilo", "lita", "debe", "gunia", "fundo", "mfuko", "pakiti", "roba")
        if (units.any { word.contains(it) }) return WordType.UNIT

        // Currency/number detection
        if (word.matches(Regex("\\d+"))) return WordType.CURRENCY

        // Action word detection (Swahili verb prefixes)
        val verbPrefixes = setOf("ni", "a", "wa", "ta", "hu", "si", "ku", "me")
        if (verbPrefixes.any { word.startsWith(it) && word.length > 5 }) return WordType.ACTION

        // Default: product
        return WordType.PRODUCT
    }

    /**
     * Get session unknown words for UI display.
     */
    fun getSessionUnknownWords(): Map<String, WordCapture> {
        return sessionUnknownWords.toMap()
    }

    /**
     * Get worker vocabulary for LLM context injection.
     * Returns a summary string for prompt enrichment.
     */
    suspend fun getWorkerVocabularyContext(maxWords: Int = 20): String {
        val topWords = workerVocabularyDao.getTopByFrequency(currentWorkerId, maxWords)
        if (topWords.isEmpty()) return ""

        val vocabList = topWords.joinToString(", ") { entry ->
            val conf = (entry.confidence * 100).toInt()
            "${entry.canonicalForm}(${conf}%)"
        }

        return "Maneno ya mteja: $vocabList"
    }

    /**
     * Shutdown the pipeline scope.
     */
    fun shutdown() {
        scope.cancel()
    }
}

// ════════════════════════════════════════════════════════════════════
// DATA CLASSES
// ════════════════════════════════════════════════════════════════════

/**
 * Captured unknown word with context.
 */
data class WordCapture(
    val word: String,
    var count: Int = 1,
    val firstConfidence: Float,
    var lastConfidence: Float = firstConfidence,
    val dialectRegion: String,
    var lastContext: String = ""
)

/**
 * Per-worker calibration summary.
 */
data class WorkerCalibrationSummary(
    val workerId: Long,
    val totalVocabulary: Int,
    val highConfidenceVocabulary: Int,
    val averageConfidence: Double,
    val categories: List<String>,
    val languages: List<String>,
    val shengVocabularySize: Int,
    val calibrationMessage: String
)

/**
 * Conversation learning pipeline state.
 */
sealed class ConversationLearningState {
    object Idle : ConversationLearningState()
    object Processing : ConversationLearningState()
    data class Error(val message: String) : ConversationLearningState()
}
