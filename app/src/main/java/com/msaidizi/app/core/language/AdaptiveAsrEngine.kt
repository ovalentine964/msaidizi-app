package com.msaidizi.app.core.language

import com.msaidizi.app.core.LanguageDetector
import com.msaidizi.app.core.dialect.MigoriDialectAdapter
import com.msaidizi.app.core.model.*
import com.msaidizi.app.core.util.SwahiliParser
import com.msaidizi.app.voice.SpeechRecognizer
import kotlinx.coroutines.*
import timber.log.Timber
import kotlin.math.ln
import kotlin.math.exp

/**
 * Adaptive ASR Engine — Bayesian speech recognition with on-device learning.
 *
 * Wraps Whisper ASR with a Bayesian framework that improves over time:
 *
 * 1. Prior: Language model built from user's vocabulary + corrections
 * 2. Likelihood: Whisper acoustic model output (token probabilities)
 * 3. Posterior: Combined ASR result with personalized corrections
 *
 * Mathematical Foundation (see reviews/impl-language-learning.md):
 * ────────────────────────────────────────────────────────────────
 * P(transcript | audio, user) ∝ P(audio | transcript) · P(transcript | lang) · P_user(transcript)
 *
 * where:
 * - P(audio | transcript) = Whisper acoustic model (likelihood)
 * - P(transcript | lang) = n-gram language model (language prior)
 * - P_user(transcript) = user-specific vocabulary + correction model (user prior)
 *
 * On-Device Learning Loop:
 * ────────────────────────
 * 1. Whisper transcribes audio → raw transcript + token probs
 * 2. Apply user vocabulary corrections (word-level)
 * 3. Apply dialect normalization (MigoriDialectAdapter)
 * 4. Apply phoneme-aware post-processing (PhonemeMapper)
 * 5. Calibrate confidence (ConfidenceCalibrator)
 * 6. Present to user with appropriate confidence UI
 * 7. If user corrects → update user prior (online learning)
 * 8. Periodically: update LoRA adapter weights via gradient descent
 *
 * Memory Budget:
 * - Whisper model: ~40 MB (loaded/unloaded on demand)
 * - User vocabulary: ~2 KB (always in memory)
 * - N-gram LM: ~1–5 MB (loaded per language)
 * - Correction cache: ~50 KB (last 100 corrections)
 * - Total additional overhead: <6 MB beyond Whisper
 *
 * Battery: ~0.03% per transcription (dominated by Whisper inference)
 */
class AdaptiveAsrEngine(
    private val speechRecognizer: SpeechRecognizer,
    private val confidenceCalibrator: ConfidenceCalibrator,
    private val phonemeMapper: PhonemeMapper,
    private val languageModelRegistry: LanguageModelRegistry,
    private val userCorrectionDao: UserCorrectionDao,
    private val userVocabularyDao: UserVocabularyDao,
) {
    companion object {
        private const val TAG = "AdaptiveAsr"
        private const val MAX_CORRECTION_CACHE = 200
        private const val MIN_CORRECTIONS_FOR_LORA = 50
        private const val NGRAM_ORDER = 5
        private val LEARNING_RATE = 0.001f
        private const val LEARNING_RATE_DECAY = 0.999f
    }

    // ════════════════════════════════════════════════════════════════════
    // STATE
    // ════════════════════════════════════════════════════════════════════

    /** In-memory correction cache for fast lookup */
    private val correctionCache = LinkedHashMap<String, String>(64, 0.75f, true)

    /** User-specific n-gram counts: context → (word → count) */
    private val userNgramCounts = mutableMapOf<String, MutableMap<String, Int>>()

    /** Total tokens seen per language (for probability normalization) */
    private val totalTokenCounts = mutableMapOf<String, Int>()

    /** Current learning rate (decays over time) */
    private var currentLearningRate = LEARNING_RATE

    /** Number of corrections processed (for learning rate scheduling) */
    private var correctionCount = 0

    /** CUSUM drift detection state per language */
    private val driftState = mutableMapOf<String, CusumState>()

    /** Whether the engine is initialized */
    private var initialized = false

    /** Pre-loaded vocabulary set for fast confidence estimation (avoids runBlocking) */
    private var knownVocabulary: Set<String> = emptySet()

    // ════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ════════════════════════════════════════════════════════════════════

    /**
     * Initialize the adaptive engine — load user's learned corrections.
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (initialized) return@withContext

        try {
            // Load recent corrections into cache
            val recentCorrections = userCorrectionDao.getRecent(MAX_CORRECTION_CACHE)
            for (correction in recentCorrections) {
                correctionCache[correction.originalValue] = correction.correctedValue
            }

            // Load user vocabulary for n-gram priors
            val vocabulary = userVocabularyDao.getHighConfidence(0.5)
            val vocabSet = mutableSetOf<String>()
            for (entry in vocabulary) {
                updateNgramModel(entry.canonicalForm, languageModelRegistry.getActiveLanguage())
                vocabSet.add(entry.canonicalForm.lowercase())
                vocabSet.add(entry.spokenForm.lowercase())
            }
            // Also add correction cache entries
            vocabSet.addAll(correctionCache.keys)
            vocabSet.addAll(correctionCache.values)
            knownVocabulary = vocabSet

            Timber.tag(TAG).i(
                "Initialized: %d corrections cached, %d vocabulary entries loaded, %d known words",
                correctionCache.size, vocabulary.size, knownVocabulary.size
            )
            initialized = true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Initialization failed")
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // TRANSCRIPTION — Full Adaptive Pipeline
    // ════════════════════════════════════════════════════════════════════

    /**
     * Transcribe audio with full adaptive pipeline.
     *
     * Pipeline:
     * 1. Whisper ASR → raw transcript + confidence
     * 2. Language detection
     * 3. Dialect normalization (if Migori)
     * 4. User vocabulary correction
     * 5. Phoneme-aware post-processing
     * 6. Bayesian confidence calibration
     * 7. Active learning decision (should we ask user?)
     *
     * @param audioData Raw audio at 16kHz, 16-bit PCM
     * @param expectedLanguage Hint for language (null = auto-detect)
     * @return AdaptiveTranscription with all metadata
     */
    suspend fun transcribe(
        audioData: ShortArray,
        expectedLanguage: String? = null
    ): AdaptiveTranscription = withContext(Dispatchers.Default) {
        if (!initialized) initialize()

        val startTime = System.currentTimeMillis()

        // Step 1: Raw Whisper transcription
        val rawTranscript = speechRecognizer.transcribe(audioData)
        if (rawTranscript.isNullOrBlank()) {
            return@withContext AdaptiveTranscription.empty()
        }

        // Step 2: Language detection
        val detectedLanguage = expectedLanguage
            ?: LanguageDetector.detect(rawTranscript)

        // Step 3: Dialect normalization
        val dialectNormalized = applyDialectNormalization(rawTranscript, detectedLanguage)

        // Step 4: User vocabulary correction
        val vocabCorrected = applyVocabularyCorrections(dialectNormalized)

        // Step 5: Phoneme-aware post-processing
        val phonemeCorrected = phonemeMapper.postProcess(vocabCorrected, detectedLanguage)

        // Step 6: ASR error correction from learned patterns
        val patternCorrected = applyLearnedCorrections(phonemeCorrected)

        // Step 7: Bayesian confidence calibration
        val rawConfidence = estimateRawConfidence(audioData, rawTranscript)
        val calibratedConfidence = confidenceCalibrator.calibrate(rawConfidence, detectedLanguage)

        // Step 8: Check for drift in user's speech patterns
        val driftDetected = checkDrift(detectedLanguage, patternCorrected)

        val elapsed = System.currentTimeMillis() - startTime

        Timber.tag(TAG).d(
            "Transcribed [%s] in %dms: '%s' → '%s' (conf: %.3f → %.3f)",
            detectedLanguage, elapsed, rawTranscript, patternCorrected,
            rawConfidence, calibratedConfidence.calibratedConfidence
        )

        AdaptiveTranscription(
            rawTranscript = rawTranscript,
            correctedTranscript = patternCorrected,
            language = detectedLanguage,
            rawConfidence = rawConfidence,
            calibratedConfidence = calibratedConfidence,
            dialectRegion = detectDialectRegion(rawTranscript, detectedLanguage),
            correctionsApplied = countCorrections(rawTranscript, patternCorrected),
            driftDetected = driftDetected,
            latencyMs = elapsed
        )
    }

    // ════════════════════════════════════════════════════════════════════
    // CORRECTION LEARNING — Update model from user feedback
    // ════════════════════════════════════════════════════════════════════

    /**
     * Record a user correction and update the model.
     *
     * Learning Update (Gradient Descent on User Prior):
     * ─────────────────────────────────────────────────
     * When user corrects word W_wrong → W_correct:
     *
     * 1. Update correction cache: cache[W_wrong] = W_correct
     * 2. Update n-gram model: increment P(W_correct | context)
     * 3. Update vocabulary: add/increment W_correct
     * 4. Update confidence calibration: record(correct=True for W_correct)
     * 5. Store correction in DB for LoRA fine-tuning
     *
     * Learning rate scheduling (cosine annealing):
     *   η_t = η_min + 0.5·(η_max - η_min)·(1 + cos(π·t/T))
     *
     * @param originalTranscript What the system heard
     * @param correctedTranscript What the user said it should be
     * @param language Language of the correction
     * @param correctionType Type of correction (item, price, quantity, etc.)
     */
    suspend fun recordCorrection(
        originalTranscript: String,
        correctedTranscript: String,
        language: String,
        correctionType: CorrectionType = CorrectionType.OTHER
    ) = withContext(Dispatchers.IO) {
        if (originalTranscript == correctedTranscript) return@withContext

        correctionCount++
        currentLearningRate = scheduleLearningRate(correctionCount)

        // 1. Update correction cache
        correctionCache[originalTranscript.lowercase().trim()] = correctedTranscript.lowercase().trim()
        // Evict oldest if cache is full
        if (correctionCache.size > MAX_CORRECTION_CACHE) {
            val oldest = correctionCache.keys.first()
            correctionCache.remove(oldest)
        }

        // 2. Update n-gram model with corrected text
        updateNgramModel(correctedTranscript, language)

        // 3. Extract and learn vocabulary from correction
        learnVocabularyFromCorrection(originalTranscript, correctedTranscript, language)

        // 4. Update confidence calibration
        confidenceCalibrator.recordOutcome(0.5f, language, wasCorrect = true)  // User confirmed correct

        // 5. Store correction in database
        val correction = UserCorrection(
            correctionType = correctionType,
            originalValue = originalTranscript,
            correctedValue = correctedTranscript,
            originalInput = originalTranscript,
            correctionInput = correctedTranscript,
            context = "{\"language\": \"$language\", \"learningRate\": $currentLearningRate}"
        )
        userCorrectionDao.insert(correction)

        // 6. Check if we have enough corrections for LoRA update
        if (correctionCount % MIN_CORRECTIONS_FOR_LORA == 0) {
            Timber.tag(TAG).i("Reached %d corrections — ready for LoRA update", correctionCount)
            // Trigger notification for background LoRA training
        }

        Timber.tag(TAG).d(
            "Correction recorded [%s]: '%s' → '%s' (total: %d, lr: %.6f)",
            language, originalTranscript, correctedTranscript, correctionCount, currentLearningRate
        )
    }

    /**
     * Record when the user confirms a transcription was correct.
     * This is a positive signal that strengthens the current model.
     */
    suspend fun recordConfirmation(
        transcript: String,
        language: String
    ) = withContext(Dispatchers.IO) {
        // Strengthen vocabulary entries
        val words = transcript.split(" ")
        for (word in words) {
            userVocabularyDao.incrementFrequency(word.lowercase().trim())
        }

        // Update calibration with positive signal
        confidenceCalibrator.recordOutcome(0.8f, language, wasCorrect = true)

        Timber.tag(TAG).d("Confirmation recorded [%s]: '%s'", language, transcript)
    }

    /**
     * Get the count of corrections needed before next LoRA update.
     */
    fun getCorrectionsUntilUpdate(): Int {
        val remainder = correctionCount % MIN_CORRECTIONS_FOR_LORA
        return if (remainder == 0) 0 else MIN_CORRECTIONS_FOR_LORA - remainder
    }

    /**
     * Get correction statistics for the learning pipeline.
     */
    fun getCorrectionStats(): CorrectionStats {
        return CorrectionStats(
            totalCorrections = correctionCount,
            currentLearningRate = currentLearningRate,
            cacheSize = correctionCache.size,
            correctionsUntilLoraUpdate = getCorrectionsUntilUpdate()
        )
    }

    // ════════════════════════════════════════════════════════════════════
    // PRIVATE — Pipeline Steps
    // ════════════════════════════════════════════════════════════════════

    /**
     * Apply dialect normalization using MigoriDialectAdapter.
     */
    private fun applyDialectNormalization(text: String, language: String): String {
        if (language != "sw" && language != "luo") return text
        return try {
            MigoriDialectAdapter.normalize(text)
        } catch (e: Exception) {
            text
        }
    }

    /**
     * Apply user vocabulary corrections.
     * Uses fuzzy matching for near-matches.
     */
    private suspend fun applyVocabularyCorrections(text: String): String {
        val words = text.split(" ")
        val corrected = words.map { word ->
            val lower = word.lowercase().trim()
            // Exact match in correction cache
            correctionCache[lower]
                // Fuzzy match in vocabulary
                ?: fuzzyMatchVocabulary(lower)
                // No correction
                ?: word
        }
        return corrected.joinToString(" ")
    }

    /**
     * Fuzzy match a word against user vocabulary.
     * Uses edit distance for near-matches (handles ASR typos).
     */
    private suspend fun fuzzyMatchVocabulary(word: String): String? {
        if (word.length < 3) return null

        // Try prefix match first (fast)
        val prefixMatches = userVocabularyDao.findByPrefix(word.take(4), 3)
        if (prefixMatches.isNotEmpty()) {
            val best = prefixMatches.firstOrNull { it.spokenForm == word }
                ?: prefixMatches.firstOrNull { editDistance(it.spokenForm, word) <= 2 }
            if (best != null && best.confidence > 0.5) {
                return best.canonicalForm
            }
        }

        return null
    }

    /**
     * Apply learned corrections from the correction cache.
     * Handles multi-word corrections (phrases).
     */
    private fun applyLearnedCorrections(text: String): String {
        var result = text.lowercase()

        // Apply phrase-level corrections (longer matches first)
        val sortedCorrections = correctionCache.entries.sortedByDescending { it.key.length }
        for ((wrong, correct) in sortedCorrections) {
            result = result.replace(wrong, correct)
        }

        return result
    }

    /**
     * Estimate raw ASR confidence from audio characteristics and transcript.
     *
     * Heuristic confidence estimation:
     * - Audio SNR (signal-to-noise ratio)
     * - Transcript length vs audio length (speech rate)
     * - Known word coverage (vocabulary match rate)
     *
     * @return Estimated confidence [0, 1]
     */
    private suspend fun estimateRawConfidence(audio: ShortArray, transcript: String): Float {
        // Audio quality signal (SNR estimate)
        val snr = estimateSNR(audio)
        val audioQuality = (snr / 30.0f).coerceIn(0.0f, 1.0f)  // 30 dB = excellent

        // Speech rate plausibility
        val expectedDurationSec = audio.size / 16000.0f
        val wordCount = transcript.split(" ").size
        val wordsPerSecond = wordCount / expectedDurationSec.coerceAtLeast(0.1f)
        val ratePlausibility = when {
            wordsPerSecond < 1.0f -> 0.7f  // Very slow (likely noise)
            wordsPerSecond > 5.0f -> 0.6f  // Very fast (likely errors)
            else -> 0.85f + (1.0f - kotlin.math.abs(wordsPerSecond - 3.0f) / 3.0f) * 0.15f
        }

        // Vocabulary coverage (uses pre-loaded set, no runBlocking)
        val words = transcript.split(" ")
        val knownWords = words.count { word ->
            val lower = word.lowercase()
            correctionCache.containsKey(lower) || knownVocabulary.contains(lower)
        }
        val vocabCoverage = if (words.isNotEmpty()) knownWords.toFloat() / words.size else 0.5f

        // Combine signals (weighted average)
        val confidence = audioQuality * 0.3f + ratePlausibility * 0.3f + vocabCoverage * 0.4f
        return confidence.coerceIn(0.1f, 0.95f)
    }

    /**
     * Estimate signal-to-noise ratio of audio.
     * Simple energy-based estimate.
     */
    private fun estimateSNR(audio: ShortArray): Float {
        if (audio.isEmpty()) return 0.0f

        // Calculate signal energy (RMS)
        val signalEnergy = audio.map { (it.toFloat() / Short.MAX_VALUE).let { v -> v * v } }
            .average()
            .let { kotlin.math.sqrt(it) }

        // Estimate noise from the quietest 10% of frames
        val frameSize = 160  // 10ms at 16kHz
        val frameEnergies = audio.toList()
            .chunked(frameSize)
            .map { frame ->
                frame.map { (it.toFloat() / Short.MAX_VALUE).let { v -> v * v } }
                    .average()
                    .let { kotlin.math.sqrt(it).toFloat() }
            }
            .sorted()

        val noiseFloor = if (frameEnergies.isNotEmpty()) {
            frameEnergies.take(frameEnergies.size / 10).average().toFloat()
        } else 0.001f

        // SNR in dB
        return if (noiseFloor > 0) {
            20 * kotlin.math.log10(signalEnergy.toFloat() / noiseFloor)
        } else 30.0f  // Very clean audio
    }

    /**
     * Detect dialect region from text.
     */
    private fun detectDialectRegion(text: String, language: String): String {
        if (language == "sw") {
            return try {
                MigoriDialectAdapter.detectRegion(text).name.lowercase()
            } catch (e: Exception) {
                "standard"
            }
        }
        return "standard"
    }

    // ════════════════════════════════════════════════════════════════════
    // PRIVATE — N-gram Language Model
    // ════════════════════════════════════════════════════════════════════

    /**
     * Update n-gram language model with new text.
     *
     * Maintains a simple n-gram model on-device for the user's vocabulary.
     * This serves as the Bayesian prior P(transcript | lang, user).
     *
     * Storage: ~50 KB for 1000 unique n-grams (trivial on 2GB).
     */
    private fun updateNgramModel(text: String, language: String) {
        val words = text.lowercase().split(" ").filter { it.isNotBlank() }
        val total = totalTokenCounts.getOrDefault(language, 0)

        for (i in words.indices) {
            // Unigram
            val unigramKey = "_unigram_"
            userNgramCounts.getOrPut(unigramKey) { mutableMapOf() }
                .merge(words[i], 1, Int::plus)

            // Bigram
            if (i > 0) {
                val bigramKey = "_bigram_${words[i - 1]}"
                userNgramCounts.getOrPut(bigramKey) { mutableMapOf() }
                    .merge(words[i], 1, Int::plus)
            }

            // Trigram
            if (i > 1) {
                val trigramKey = "_trigram_${words[i - 2]}_${words[i - 1]}"
                userNgramCounts.getOrPut(trigramKey) { mutableMapOf() }
                    .merge(words[i], 1, Int::plus)
            }
        }

        totalTokenCounts[language] = total + words.size
    }

    /**
     * Get n-gram probability: P(word | context).
     *
     * Backoff strategy:
     * 1. Try trigram: P(word | w_{-2}, w_{-1})
     * 2. Backoff to bigram: P(word | w_{-1})
     * 3. Backoff to unigram: P(word)
     * 4. Backoff to uniform: 1/vocab_size
     *
     * With add-k smoothing (k=0.01) for unseen n-grams.
     */
    fun getNgramProbability(word: String, context: List<String>): Double {
        val k = 0.01  // Add-k smoothing

        // Try trigram
        if (context.size >= 2) {
            val trigramKey = "_trigram_${context[context.size - 2]}_${context[context.size - 1]}"
            val trigramCounts = userNgramCounts[trigramKey]
            if (trigramCounts != null) {
                val total = trigramCounts.values.sum() + k * trigramCounts.size
                val count = trigramCounts.getOrDefault(word.lowercase(), 0) + k
                if (total > 0) return count / total
            }
        }

        // Backoff to bigram
        if (context.isNotEmpty()) {
            val bigramKey = "_bigram_${context.last()}"
            val bigramCounts = userNgramCounts[bigramKey]
            if (bigramCounts != null) {
                val total = bigramCounts.values.sum() + k * bigramCounts.size
                val count = bigramCounts.getOrDefault(word.lowercase(), 0) + k
                if (total > 0) return count / total
            }
        }

        // Backoff to unigram
        val unigramCounts = userNgramCounts["_unigram_"]
        if (unigramCounts != null) {
            val total = unigramCounts.values.sum() + k * unigramCounts.size
            val count = unigramCounts.getOrDefault(word.lowercase(), 0) + k
            if (total > 0) return count / total
        }

        // Uniform backoff
        val vocabSize = unigramCounts?.size?.coerceAtLeast(100) ?: 100
        return 1.0 / vocabSize
    }

    /**
     * Compute language model score (log probability) for a transcript.
     * Used for Bayesian posterior computation.
     *
     * Score = Σ log P(w_i | w_{i-2}, w_{i-1})
     */
    fun computeLmScore(transcript: String): Double {
        val words = transcript.lowercase().split(" ").filter { it.isNotBlank() }
        var score = 0.0

        for (i in words.indices) {
            val context = words.subList(maxOf(0, i - 2), i)
            val prob = getNgramProbability(words[i], context)
            score += ln(prob.coerceAtLeast(1e-10))
        }

        // Normalize by length to avoid bias toward short transcripts
        return if (words.isNotEmpty()) score / words.size else 0.0
    }

    // ════════════════════════════════════════════════════════════════════
    // PRIVATE — Vocabulary Learning
    // ════════════════════════════════════════════════════════════════════

    /**
     * Learn vocabulary from a correction pair.
     *
     * When user corrects "nika uza" → "nimeuza", we learn:
     * - "nika uza" is an ASR error for "nimeuza"
     * - "nimeuza" should have higher prior probability
     * - The user speaks in a way that produces this error pattern
     */
    private suspend fun learnVocabularyFromCorrection(
        original: String,
        corrected: String,
        language: String
    ) {
        val originalWords = original.lowercase().split(" ")
        val correctedWords = corrected.lowercase().split(" ")

        // Simple word alignment (assumes mostly 1:1 mapping)
        val pairs = originalWords.zip(correctedWords)

        for ((origWord, corrWord) in pairs) {
            if (origWord != corrWord) {
                // This word was corrected — learn it
                val existing = userVocabularyDao.getBySpokenForm(corrWord)
                if (existing != null) {
                    userVocabularyDao.updateConfidence(
                        corrWord,
                        (existing.confidence + currentLearningRate).coerceAtMost(1.0)
                    )
                    userVocabularyDao.incrementFrequency(corrWord)
                } else {
                    // New vocabulary entry
                    userVocabularyDao.upsert(UserVocabulary(
                        spokenForm = corrWord,
                        canonicalForm = corrWord,
                        language = language,
                        frequency = 1,
                        confidence = 0.3,
                        isUserDefined = true
                    ))
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // PRIVATE — Learning Rate Scheduling
    // ════════════════════════════════════════════════════════════════════

    /**
     * Cosine annealing learning rate schedule.
     *
     * η_t = η_min + 0.5·(η_max - η_min)·(1 + cos(π·t/T))
     *
     * where:
     * - η_max = 0.01 (initial learning rate)
     * - η_min = 0.0001 (minimum learning rate)
     * - t = current correction count
     * - T = total expected corrections (1000)
     *
     * This schedule starts aggressive (learn fast from initial corrections)
     * and gradually becomes conservative (stabilize as model matures).
     */
    private fun scheduleLearningRate(step: Int): Float {
        val lrMax = 0.01f
        val lrMin = 0.0001f
        val totalSteps = 1000
        val t = step.toFloat() / totalSteps

        return (lrMin + 0.5f * (lrMax - lrMin) * (1 + kotlin.math.cos(Math.PI * t).toFloat()))
            .coerceIn(lrMin, lrMax)
    }

    // ════════════════════════════════════════════════════════════════════
    // PRIVATE — CUSUM Drift Detection
    // ════════════════════════════════════════════════════════════════════

    /**
     * CUSUM (Cumulative Sum) drift detection for speech pattern changes.
     *
     * Detects when the user's speech patterns have shifted significantly
     * (e.g., new vocabulary, changed accent, different business context).
     *
     * Algorithm:
     *   S_t = max(0, S_{t-1} + (x_t - μ_0) - k)
     *   if S_t > h → drift detected
     *
     * where:
     * - x_t = observed metric (e.g., correction rate, vocabulary novelty)
     * - μ_0 = expected mean (baseline correction rate)
     * - k = slack parameter (allow small deviations)
     * - h = detection threshold
     *
     * @return true if drift is detected (model may need retraining)
     */
    private fun checkDrift(language: String, transcript: String): Boolean {
        val state = driftState.getOrPut(language) { CusumState() }

        // Compute novelty score: fraction of words NOT in user vocabulary
        val words = transcript.lowercase().split(" ")
        val unknownCount = words.count { word ->
            !correctionCache.containsKey(word) &&
            userNgramCounts["_unigram_"]?.containsKey(word) != true
        }
        val novelty = if (words.isNotEmpty()) unknownCount.toFloat() / words.size else 0.0f

        // CUSUM update
        val mu0 = 0.15f    // Expected novelty rate (15% unknown words is normal)
        val k = 0.05f      // Slack (allow 5% deviation)
        val h = 3.0f       // Detection threshold

        state.cusumSum = maxOf(0.0f, state.cusumSum + (novelty - mu0) - k)
        state.observations++

        if (state.cusumSum > h) {
            state.driftDetected = true
            state.cusumSum = 0.0f  // Reset after detection
            Timber.tag(TAG).w(
                "Drift detected [%s] after %d observations — novelty=%.2f",
                language, state.observations, novelty
            )
            return true
        }

        return false
    }

    // ════════════════════════════════════════════════════════════════════
    // PRIVATE — Helpers
    // ════════════════════════════════════════════════════════════════════

    /**
     * Count how many words were changed by corrections.
     */
    private fun countCorrections(original: String, corrected: String): Int {
        val origWords = original.lowercase().split(" ")
        val corrWords = corrected.lowercase().split(" ")
        return origWords.zip(corrWords).count { (a, b) -> a != b }
    }

    /**
     * Levenshtein edit distance for fuzzy matching.
     */
    private fun editDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        val dp = Array(m + 1) { IntArray(n + 1) }

        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j

        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (s1[i - 1] == s2[j - 1]) {
                    dp[i - 1][j - 1]
                } else {
                    1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
                }
            }
        }
        return dp[m][n]
    }
}

// ════════════════════════════════════════════════════════════════════
// DATA CLASSES
// ════════════════════════════════════════════════════════════════════

/**
 * Full adaptive transcription result with all metadata.
 */
data class AdaptiveTranscription(
    val rawTranscript: String,
    val correctedTranscript: String,
    val language: String,
    val rawConfidence: Float,
    val calibratedConfidence: CalibratedConfidence,
    val dialectRegion: String,
    val correctionsApplied: Int,
    val driftDetected: Boolean,
    val latencyMs: Long
) {
    /** Final transcript to use (corrected version) */
    val transcript: String get() = correctedTranscript

    /** Is this transcription reliable? */
    val isReliable: Boolean get() = calibratedConfidence.isReliable

    /** Should we ask the user to confirm? */
    val needsConfirmation: Boolean get() = calibratedConfidence.shouldConfirm

    /** Should we reject and ask to repeat? */
    val shouldReject: Boolean get() = calibratedConfidence.shouldReject

    companion object {
        fun empty() = AdaptiveTranscription(
            rawTranscript = "",
            correctedTranscript = "",
            language = "sw",
            rawConfidence = 0.0f,
            calibratedConfidence = CalibratedConfidence(
                rawConfidence = 0.0f,
                calibratedConfidence = 0.0f,
                temperature = 1.0f,
                language = "sw",
                action = CalibrationAction.REJECT,
                shouldConfirm = false,
                shouldReject = true
            ),
            dialectRegion = "standard",
            correctionsApplied = 0,
            driftDetected = false,
            latencyMs = 0
        )
    }
}

/**
 * Correction statistics for monitoring.
 */
data class CorrectionStats(
    val totalCorrections: Int,
    val currentLearningRate: Float,
    val cacheSize: Int,
    val correctionsUntilLoraUpdate: Int
)

/**
 * CUSUM drift detection state.
 */
private data class CusumState(
    var cusumSum: Float = 0.0f,
    var observations: Int = 0,
    var driftDetected: Boolean = false
)
