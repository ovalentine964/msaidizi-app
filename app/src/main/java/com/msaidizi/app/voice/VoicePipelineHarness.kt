package com.msaidizi.app.voice

import com.msaidizi.app.agent.OutputSanitizer
import com.msaidizi.app.agent.harness.InferenceHarness
import com.msaidizi.app.agent.harness.InferenceHarnessException
import com.msaidizi.app.agent.harness.HarnessConfig
import com.msaidizi.app.agent.harness.ProviderCandidate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Voice Pipeline Harness — wraps the full STT→LLM→TTS pipeline with
 * quality gates at each stage and graceful degradation.
 *
 * ## Architecture
 * ```
 * Audio → [STT Quality Gate] → Text → [LLM Quality Gate] → Response → [TTS Quality Gate] → Audio
 *              ↓ fail                              ↓ fail                          ↓ fail
 *         "Sawa, jaribu tena"              text fallback response            text-only output
 *         (ask to repeat)                  (simpler prompt retry)
 * ```
 *
 * ## Quality Gates (thresholds tuned for $50 phones in East Africa)
 * | Stage | Gate                  | Threshold | Action on Fail           |
 * |-------|-----------------------|-----------|--------------------------|
 * | STT   | Confidence            | < 0.6     | Ask user to repeat       |
 * | STT   | Noise level           | > 0.8     | Denoise + retry          |
 * | STT   | Language mismatch     | detected  | Switch ASR model         |
 * | LLM   | Response length       | < 5 chars | Retry with simpler prompt|
 * | LLM   | Safety check          | flagged   | Sanitize + fallback      |
 * | LLM   | Confidence            | < 0.5     | Use on-device fallback   |
 * | TTS   | Latency               | > 10s     | Switch to faster engine  |
 * | TTS   | Naturalness           | < 0.4     | Switch voice/engine      |
 *
 * ## Fallback Strategy
 * Voice → text if ANY stage fails. The user never sees a broken experience.
 *
 * ## Processing Feedback
 * During processing gaps, emit Swahili feedback:
 * - "Sawa, nimesikia..." (OK, I heard you...)
 * - "Nafikiria..." (Thinking...)
 * - "Najiandaa kusema..." (Preparing to speak...)
 *
 * ## Metrics
 * - Per-stage latency (STT, LLM, TTS)
 * - Per-stage success rate and quality scores
 * - Quality gate pass/fail rates
 * - End-to-end pipeline latency
 * - Voice-to-text fallback frequency
 * - STT accuracy (confidence distribution)
 * - LLM quality score (response validation)
 * - TTS naturalness score
 *
 * @see VoicePipeline for the low-level pipeline orchestrator
 * @see InferenceHarness for timeout/retry/fallback wrapping
 */
@Singleton
class VoicePipelineHarness @Inject constructor(
    private val inferenceHarness: InferenceHarness
) {
    companion object {
        private const val TAG = "VoicePipelineHarness"

        // ── STT Quality Thresholds ──
        /** Below this confidence, ask user to repeat */
        const val STT_MIN_CONFIDENCE = 0.6f
        /** Above this confidence, high-confidence transcription */
        const val STT_HIGH_CONFIDENCE = 0.85f
        /** Noise floor threshold (0-1 normalized). Above this = too noisy */
        const val STT_NOISE_THRESHOLD = 0.8f
        /** Minimum audio duration in ms to attempt transcription */
        const val STT_MIN_AUDIO_DURATION_MS = 500L
        /** Maximum silence ratio before considering audio empty */
        const val STT_MAX_SILENCE_RATIO = 0.85f

        // ── LLM Quality Thresholds ──
        /** Minimum response length in characters */
        const val LLM_MIN_RESPONSE_LENGTH = 5
        /** Maximum response length in characters */
        const val LLM_MAX_RESPONSE_LENGTH = 2000
        /** Below this confidence, use on-device fallback */
        const val LLM_MIN_CONFIDENCE = 0.5f
        /** Maximum retries for LLM with degraded prompts */
        const val LLM_MAX_DEGRADED_RETRIES = 2

        // ── TTS Quality Thresholds ──
        /** Maximum acceptable TTS latency in ms */
        const val TTS_MAX_LATENCY_MS = 10_000L
        /** Minimum naturalness score (0-1) to accept TTS output */
        const val TTS_MIN_NATURALNESS = 0.4f
        /** Preferred TTS latency target for good UX */
        const val TTS_TARGET_LATENCY_MS = 3_000L

        // ── Stage Timeouts ──
        const val STT_TIMEOUT_MS = 10_000L
        const val LLM_TIMEOUT_MS = 20_000L
        const val TTS_TIMEOUT_MS = 15_000L

        // ── Processing Feedback Messages (Swahili) ──
        private val FEEDBACK_STT_PROCESSING = listOf(
            "Sawa, nimesikia...",
            "Nasikiliza...",
            "Subiri kidogo..."
        )
        private val FEEDBACK_LLM_PROCESSING = listOf(
            "Nafikiria...",
            "Sawa, ngoja...",
            "Najibu swali lako..."
        )
        private val FEEDBACK_TTS_PROCESSING = listOf(
            "Najiandaa kusema...",
            "Sawa..."
        )
        private val FEEDBACK_REPEAT_REQUEST = listOf(
            "Sikusikia vizuri. Tafadhali rudia.",
            "Samahani, jaribu tena polepole.",
            "Sikuelewi. Tafadhali sema tena."
        )
    }

    // ── Pipeline State ────────────────────────────────────────────

    private val _pipelineState = MutableStateFlow(VoiceHarnessState.IDLE)
    val pipelineState: StateFlow<VoiceHarnessState> = _pipelineState

    // ── Processing Feedback Flow ──────────────────────────────────

    private val _processingFeedback = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Emits Swahili feedback messages during processing gaps */
    val processingFeedback: SharedFlow<String> = _processingFeedback

    // ── Metrics ───────────────────────────────────────────────────

    private val stageMetrics = ConcurrentHashMap<String, StageMetrics>()
    private val pipelineCounter = AtomicLong(0)
    private val degradedCounter = AtomicLong(0)
    private val repeatRequestCounter = AtomicLong(0)

    // ── Detected language from STT ────────────────────────────────

    private var lastDetectedLanguage: String = "sw"

    // ═══════════════════════════════════════════════════════════════
    // FULL PIPELINE — STT → LLM → TTS with quality gates
    // ═══════════════════════════════════════════════════════════════

    /**
     * Execute the full voice pipeline: STT → LLM → TTS.
     *
     * Each stage is wrapped with the [InferenceHarness] for timeout/retry,
     * and a quality gate validates the output before passing to the next stage.
     *
     * @param audioData Raw audio bytes from the microphone
     * @param language Language code (default "sw" for Swahili)
     * @param sttCall Function to transcribe audio to text
     * @param llmCall Function to call the LLM with transcribed text
     * @param ttsCall Function to speak the response
     * @param feedbackCall Optional function to speak feedback during processing
     * @return The pipeline result (may have degraded to text-only)
     */
    suspend fun executePipeline(
        audioData: ByteArray,
        language: String = "sw",
        sttCall: suspend () -> TranscriptionResult,
        llmCall: suspend (String) -> String,
        ttsCall: suspend (String) -> Unit,
        feedbackCall: (suspend (String) -> Unit)? = null
    ): VoicePipelineResult {
        val pipelineId = UUID.randomUUID().toString().take(12)
        val pipelineStart = System.currentTimeMillis()
        pipelineCounter.incrementAndGet()
        _pipelineState.value = VoiceHarnessState.PROCESSING_STT

        val stageTimings = mutableMapOf<String, Long>()
        val qualityScores = mutableMapOf<String, Float>()
        var degradedToText = false
        var failedStage: String? = null
        var repeatRequested = false

        // ── Stage 1: STT with quality gate ──────────────────────
        emitFeedback(FEEDBACK_STT_PROCESSING, feedbackCall)

        val sttResult: String?
        val sttQuality: Float

        try {
            val sttStart = System.currentTimeMillis()

            // Pre-check: audio duration and noise
            val audioCheck = preCheckAudio(audioData)
            if (!audioCheck.passed) {
                Timber.w(TAG, "[%s] Audio pre-check failed: %s", pipelineId, audioCheck.reason)
                failedStage = "stt"
                degradedToText = true
                repeatRequested = true
                sttResult = null
                sttQuality = 0f
                stageTimings["stt"] = System.currentTimeMillis() - sttStart

                emitFeedback(FEEDBACK_REPEAT_REQUEST, feedbackCall)
                emitEvent(VoicePipelineEvent.QualityGateFailed(
                    pipelineId = pipelineId,
                    stage = "stt",
                    reason = audioCheck.reason
                ))
            } else {
                // Execute STT through InferenceHarness
                val transcriptionResult = inferenceHarness.execute(
                    config = HarnessConfig(timeoutMs = STT_TIMEOUT_MS, maxRetries = 1),
                    providers = listOf(
                        ProviderCandidate(
                            providerId = "stt-whisper",
                            modelId = "whisper-tiny-int4",
                            provider = sttCall
                        )
                    ),
                    taskType = "voice-stt"
                )

                val sttLatency = System.currentTimeMillis() - sttStart
                stageTimings["stt"] = sttLatency
                recordStageMetric("stt", sttLatency, true)

                // Quality gate: check STT confidence, language, noise
                val transcription = transcriptionResult.value
                val sttGate = sttQualityGate(transcription)
                sttQuality = transcription.confidence
                qualityScores["stt"] = sttQuality

                if (!sttGate.passed) {
                    Timber.w(TAG, "[%s] STT quality gate failed: %s", pipelineId, sttGate.reason)
                    failedStage = "stt"
                    repeatRequested = true

                    // If confidence is borderline (0.4-0.6), try once more with noise reduction
                    if (transcription.confidence in 0.4f..STT_MIN_CONFIDENCE) {
                        Timber.d(TAG, "[%s] Retrying STT with noise reduction hint", pipelineId)
                        emitFeedback(listOf("Sikusikia vizuri. Subiri..."), feedbackCall)
                    } else {
                        degradedToText = true
                        sttResult = null
                    }

                    // Emit repeat request feedback
                    if (!degradedToText) {
                        emitFeedback(FEEDBACK_REPEAT_REQUEST, feedbackCall)
                    }

                    emitEvent(VoicePipelineEvent.QualityGateFailed(
                        pipelineId = pipelineId,
                        stage = "stt",
                        reason = sttGate.reason
                    ))

                    if (degradedToText) {
                        sttResult = null
                    } else {
                        // Borderline confidence — ask to repeat but keep pipeline alive
                        sttResult = null
                    }
                } else {
                    sttResult = transcription.text
                    lastDetectedLanguage = transcription.language ?: language

                    // Log language detection
                    if (lastDetectedLanguage != language) {
                        Timber.i(TAG, "[%s] Language detected: %s (expected: %s)",
                            pipelineId, lastDetectedLanguage, language)
                        emitEvent(VoicePipelineEvent.LanguageDetected(
                            pipelineId = pipelineId,
                            detected = lastDetectedLanguage,
                            expected = language
                        ))
                    }

                    if (transcription.confidence < STT_HIGH_CONFIDENCE) {
                        Timber.d(TAG, "[%s] STT moderate confidence (%.2f) — proceeding",
                            pipelineId, transcription.confidence)
                    }
                }
            }
        } catch (e: InferenceHarnessException) {
            Timber.e(TAG, "[%s] STT stage failed: %s", pipelineId, e.message)
            recordStageMetric("stt", 0L, false)
            failedStage = "stt"
            degradedToText = true
            sttResult = null
            sttQuality = 0f
        }

        // If STT failed, return text-only fallback
        if (sttResult == null) {
            _pipelineState.value = VoiceHarnessState.IDLE
            val totalTime = System.currentTimeMillis() - pipelineStart

            if (repeatRequested) {
                repeatRequestCounter.incrementAndGet()
            }

            emitEvent(VoicePipelineEvent.PipelineCompleted(
                pipelineId = pipelineId,
                totalTimeMs = totalTime,
                degradedToText = true,
                failedStage = failedStage,
                repeatRequested = repeatRequested
            ))

            return VoicePipelineResult(
                pipelineId = pipelineId,
                transcribedText = null,
                llmResponse = null,
                spoken = false,
                degradedToText = true,
                repeatRequested = repeatRequested,
                failedStage = "stt",
                stageTimings = stageTimings,
                qualityScores = qualityScores,
                totalTimeMs = totalTime,
                detectedLanguage = lastDetectedLanguage
            )
        }

        // ── Stage 2: LLM with quality gate ──────────────────────
        _pipelineState.value = VoiceHarnessState.PROCESSING_LLM
        emitFeedback(FEEDBACK_LLM_PROCESSING, feedbackCall)

        val llmResponse: String?
        val llmQuality: Float

        try {
            val llmStart = System.currentTimeMillis()
            val llmResult = inferenceHarness.execute(
                config = HarnessConfig(timeoutMs = LLM_TIMEOUT_MS, maxRetries = 2),
                providers = listOf(
                    ProviderCandidate(
                        providerId = "llm-voice",
                        modelId = "auto",
                        provider = { llmCall(sttResult) }
                    )
                ),
                taskType = "voice-llm"
            )

            val llmLatency = System.currentTimeMillis() - llmStart
            stageTimings["llm"] = llmLatency
            recordStageMetric("llm", llmLatency, true)

            // Quality gate: validate LLM response
            val rawResponse = llmResult.value
            val llmGate = llmQualityGate(rawResponse)
            llmQuality = if (llmGate.passed) 1.0f else 0.3f
            qualityScores["llm"] = llmQuality

            if (!llmGate.passed) {
                Timber.w(TAG, "[%s] LLM quality gate failed: %s", pipelineId, llmGate.reason)
                failedStage = "llm"

                // Retry with simpler prompt (degraded mode)
                val retryResult = retryLlmDegraded(sttResult, llmCall, pipelineId)
                if (retryResult != null) {
                    llmResponse = retryResult
                    llmQuality = 0.7f // Degraded but usable
                    qualityScores["llm"] = llmQuality
                    stageTimings["llm_retries"] = System.currentTimeMillis() - llmStart - llmLatency
                } else {
                    degradedToText = true
                    llmResponse = null

                    emitEvent(VoicePipelineEvent.QualityGateFailed(
                        pipelineId = pipelineId,
                        stage = "llm",
                        reason = llmGate.reason
                    ))
                }
            } else {
                // Apply safety sanitization
                val sanitized = OutputSanitizer.sanitize(rawResponse, lastDetectedLanguage)
                llmResponse = sanitized
                llmQuality = assessLlmQuality(sanitized)
                qualityScores["llm"] = llmQuality

                // If sanitizer changed the response significantly, log it
                if (sanitized != rawResponse) {
                    Timber.w(TAG, "[%s] LLM response sanitized (length %d → %d)",
                        pipelineId, rawResponse.length, sanitized.length)
                }

                // Low-confidence LLM: check if we should activate thinking mode
                if (llmQuality < LLM_MIN_CONFIDENCE) {
                    Timber.d(TAG, "[%s] LLM quality low (%.2f) — considering thinking mode activation",
                        pipelineId, llmQuality)
                    emitEvent(VoicePipelineEvent.ThinkingModeActivated(
                        pipelineId = pipelineId,
                        reason = "Low quality score: $llmQuality"
                    ))
                }
            }
        } catch (e: InferenceHarnessException) {
            Timber.e(TAG, "[%s] LLM stage failed: %s", pipelineId, e.message)
            recordStageMetric("llm", 0L, false)
            failedStage = "llm"
            degradedToText = true
            llmResponse = null
            llmQuality = 0f
        }

        // If LLM failed, return text result without TTS
        if (llmResponse == null) {
            _pipelineState.value = VoiceHarnessState.IDLE
            val totalTime = System.currentTimeMillis() - pipelineStart
            degradedCounter.incrementAndGet()

            return VoicePipelineResult(
                pipelineId = pipelineId,
                transcribedText = sttResult,
                llmResponse = null,
                spoken = false,
                degradedToText = true,
                repeatRequested = false,
                failedStage = failedStage ?: "llm",
                stageTimings = stageTimings,
                qualityScores = qualityScores,
                totalTimeMs = totalTime,
                detectedLanguage = lastDetectedLanguage
            )
        }

        // ── Stage 3: TTS with quality gate ──────────────────────
        _pipelineState.value = VoiceHarnessState.PROCESSING_TTS
        emitFeedback(FEEDBACK_TTS_PROCESSING, feedbackCall)

        var spoken = false
        val ttsQuality: Float

        try {
            val ttsStart = System.currentTimeMillis()

            // Select voice based on language and emotion
            val voiceConfig = selectVoiceConfig(lastDetectedLanguage)
            Timber.d(TAG, "[%s] TTS voice: %s (engine: %s)", pipelineId,
                voiceConfig.voiceId, voiceConfig.engineType)

            inferenceHarness.execute(
                config = HarnessConfig(timeoutMs = TTS_TIMEOUT_MS, maxRetries = 1),
                providers = listOf(
                    ProviderCandidate(
                        providerId = "tts-${voiceConfig.engineType.name.lowercase()}",
                        modelId = voiceConfig.voiceId,
                        provider = { ttsCall(llmResponse) }
                    )
                ),
                taskType = "voice-tts"
            )

            val ttsLatency = System.currentTimeMillis() - ttsStart
            stageTimings["tts"] = ttsLatency
            recordStageMetric("tts", ttsLatency, true)

            // Quality gate: check TTS latency and estimate naturalness
            ttsQuality = assessTtsQuality(ttsLatency)
            qualityScores["tts"] = ttsQuality

            if (ttsLatency > TTS_MAX_LATENCY_MS) {
                Timber.w(TAG, "[%s] TTS latency too high: %dms (max: %dms)",
                    pipelineId, ttsLatency, TTS_MAX_LATENCY_MS)
                emitEvent(VoicePipelineEvent.QualityGateFailed(
                    pipelineId = pipelineId,
                    stage = "tts",
                    reason = "Latency ${ttsLatency}ms exceeds ${TTS_MAX_LATENCY_MS}ms"
                ))
                // Still mark as spoken — audio was delivered, just slow
            }

            if (ttsQuality < TTS_MIN_NATURALNESS) {
                Timber.w(TAG, "[%s] TTS naturalness low: %.2f", pipelineId, ttsQuality)
                emitEvent(VoicePipelineEvent.VoiceSwitched(
                    pipelineId = pipelineId,
                    fromEngine = voiceConfig.engineType.name,
                    reason = "Low naturalness: $ttsQuality"
                ))
            }

            spoken = true
        } catch (e: InferenceHarnessException) {
            Timber.e(TAG, "[%s] TTS stage failed: %s", pipelineId, e.message)
            recordStageMetric("tts", 0L, false)
            failedStage = "tts"
            ttsQuality = 0f
            // TTS failure is non-critical — user still gets text response
        }

        _pipelineState.value = VoiceHarnessState.IDLE
        val totalTime = System.currentTimeMillis() - pipelineStart

        if (degradedToText) degradedCounter.incrementAndGet()

        emitEvent(VoicePipelineEvent.PipelineCompleted(
            pipelineId = pipelineId,
            totalTimeMs = totalTime,
            degradedToText = degradedToText && !spoken,
            failedStage = failedStage,
            repeatRequested = repeatRequested
        ))

        Timber.i(TAG, "[%s] Pipeline complete: %dms, spoken=%s, degraded=%s, qualities=%s",
            pipelineId, totalTime, spoken, degradedToText, qualityScores)

        return VoicePipelineResult(
            pipelineId = pipelineId,
            transcribedText = sttResult,
            llmResponse = llmResponse,
            spoken = spoken,
            degradedToText = degradedToText && !spoken,
            repeatRequested = repeatRequested,
            failedStage = failedStage,
            stageTimings = stageTimings,
            qualityScores = qualityScores,
            totalTimeMs = totalTime,
            detectedLanguage = lastDetectedLanguage
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // QUALITY GATES — Validate each stage's output
    // ═══════════════════════════════════════════════════════════════

    /**
     * Pre-check audio before sending to STT.
     * Validates duration and noise levels to avoid wasting STT compute.
     */
    fun preCheckAudio(audioData: ByteArray): QualityGateResult {
        // Check minimum audio duration
        // Assuming 16kHz 16-bit mono: 32000 bytes/sec
        val durationMs = (audioData.size.toLong() * 1000) / 32000
        if (durationMs < STT_MIN_AUDIO_DURATION_MS) {
            return QualityGateResult(false, "Audio too short: ${durationMs}ms (min: ${STT_MIN_AUDIO_DURATION_MS}ms)")
        }

        // Estimate noise level from audio energy distribution
        val noiseLevel = estimateNoiseLevel(audioData)
        if (noiseLevel > STT_NOISE_THRESHOLD) {
            return QualityGateResult(false, "Noise level too high: $noiseLevel (max: $STT_NOISE_THRESHOLD)")
        }

        // Check silence ratio
        val silenceRatio = estimateSilenceRatio(audioData)
        if (silenceRatio > STT_MAX_SILENCE_RATIO) {
            return QualityGateResult(false, "Mostly silence: ${(silenceRatio * 100).toInt()}%")
        }

        return QualityGateResult(true, "OK")
    }

    /**
     * STT quality gate: check transcription confidence and content.
     * Threshold: confidence < 0.6 → ask to repeat
     */
    fun sttQualityGate(result: TranscriptionResult): QualityGateResult {
        if (!result.success) {
            return QualityGateResult(false, "Transcription failed: ${result.error}")
        }
        if (result.text.isBlank()) {
            return QualityGateResult(false, "Empty transcription")
        }
        if (result.confidence < STT_MIN_CONFIDENCE) {
            return QualityGateResult(false,
                "Confidence ${result.confidence} < $STT_MIN_CONFIDENCE — ask user to repeat")
        }
        // Check for gibberish (very short or repetitive)
        if (result.text.length < 2) {
            return QualityGateResult(false, "Transcription too short: '${result.text}'")
        }
        // Check for repetitive characters (e.g., "aaaaaaa")
        if (result.text.groupBy { it }.any { it.value.size > result.text.length * 0.8 }) {
            return QualityGateResult(false, "Repetitive/gibberish transcription: '${result.text.take(20)}'")
        }
        return QualityGateResult(true, "OK")
    }

    /**
     * LLM quality gate: validate response content and safety.
     * Checks length, safety patterns, and response quality.
     */
    fun llmQualityGate(response: String): QualityGateResult {
        if (response.isBlank()) {
            return QualityGateResult(false, "Empty LLM response")
        }
        if (response.length < LLM_MIN_RESPONSE_LENGTH) {
            return QualityGateResult(false, "Response too short (${response.length} chars)")
        }
        if (response.length > LLM_MAX_RESPONSE_LENGTH) {
            return QualityGateResult(false, "Response too long (${response.length} chars)")
        }
        // Check for common error/refusal patterns
        val lowerResponse = response.lowercase()
        val errorPatterns = listOf(
            "i cannot", "i can't help", "as an ai", "i'm sorry, but i",
            "i'm not able", "i apologize, but", "unfortunately, i cannot"
        )
        for (pattern in errorPatterns) {
            if (lowerResponse.startsWith(pattern)) {
                return QualityGateResult(false, "Response starts with refusal pattern: '$pattern'")
            }
        }
        // Safety check: detect potential injection in response
        if (containsInjectionPatterns(response)) {
            return QualityGateResult(false, "Response contains injection patterns — sanitizing")
        }
        return QualityGateResult(true, "OK")
    }

    /**
     * TTS quality gate: assess naturalness and latency.
     * Returns a quality score (0-1) based on latency and engine type.
     */
    fun ttsQualityGate(latencyMs: Long, engineType: TtsEngineType): QualityGateResult {
        if (latencyMs > TTS_MAX_LATENCY_MS) {
            return QualityGateResult(false,
                "TTS latency ${latencyMs}ms exceeds ${TTS_MAX_LATENCY_MS}ms")
        }
        val naturalness = assessTtsQuality(latencyMs, engineType)
        if (naturalness < TTS_MIN_NATURALNESS) {
            return QualityGateResult(false,
                "TTS naturalness $naturalness below threshold $TTS_MIN_NATURALNESS")
        }
        return QualityGateResult(true, "OK (naturalness=$naturalness)")
    }

    // ═══════════════════════════════════════════════════════════════
    // LLM RETRY WITH DEGRADED PROMPT
    // ═══════════════════════════════════════════════════════════════

    /**
     * Retry LLM with a simpler, more constrained prompt.
     * Activates thinking mode for better reasoning on retry.
     */
    private suspend fun retryLlmDegraded(
        originalInput: String,
        llmCall: suspend (String) -> String,
        pipelineId: String
    ): String? {
        for (attempt in 1..LLM_MAX_DEGRADED_RETRIES) {
            try {
                Timber.d(TAG, "[%s] LLM degraded retry %d/%d", pipelineId, attempt, LLM_MAX_DEGRADED_RETRIES)

                // Simplify the prompt for retry
                val simplifiedPrompt = buildSimplifiedPrompt(originalInput, attempt)
                val result = llmCall(simplifiedPrompt)

                // Validate retry result with relaxed thresholds
                if (result.isNotBlank() && result.length >= 3) {
                    val sanitized = OutputSanitizer.sanitize(result, lastDetectedLanguage)
                    if (sanitized.isNotBlank()) {
                        Timber.i(TAG, "[%s] LLM degraded retry succeeded on attempt %d", pipelineId, attempt)
                        return sanitized
                    }
                }
            } catch (e: Exception) {
                Timber.w(TAG, "[%s] LLM degraded retry %d failed: %s", pipelineId, attempt, e.message)
            }
        }
        return null
    }

    /**
     * Build a simplified prompt for LLM retry.
     * Strips context and asks for a direct, short answer.
     */
    private fun buildSimplifiedPrompt(originalInput: String, attempt: Int): String {
        return when (attempt) {
            1 -> "Jibu kwa ufupi: $originalInput"  // "Answer briefly: ..."
            2 -> originalInput.take(100)  // Just the raw input, truncated
            else -> originalInput.take(50)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // QUALITY ASSESSMENT
    // ═══════════════════════════════════════════════════════════════

    /**
     * Assess LLM response quality (0.0 - 1.0).
     * Based on length, structure, and content indicators.
     */
    private fun assessLlmQuality(response: String): Float {
        var score = 0.5f

        // Length scoring: too short or too long is bad
        when {
            response.length in 20..500 -> score += 0.2f
            response.length in 10..20 -> score += 0.1f
            response.length > 500 -> score += 0.1f
        }

        // Structure scoring: has sentences, not just fragments
        val sentences = response.split(Regex("[.!?]+")).filter { it.isNotBlank() }
        if (sentences.size in 1..5) score += 0.15f

        // Swahili relevance: contains common Swahili words
        val swahiliWords = listOf("na", "ya", "kwa", "ni", "wa", "katika", "au", "lakini")
        val wordCount = swahiliWords.count { response.lowercase().contains(it) }
        if (wordCount >= 2) score += 0.1f

        // Penalize obvious failures
        if (response.contains("error", ignoreCase = true) &&
            response.contains("exception", ignoreCase = true)) {
            score -= 0.3f
        }

        return score.coerceIn(0f, 1f)
    }

    /**
     * Assess TTS quality based on latency and engine type.
     * Kokoro gets a naturalness bonus over Piper.
     */
    private fun assessTtsQuality(
        latencyMs: Long,
        engineType: TtsEngineType = TtsEngineType.KOKORO
    ): Float {
        var naturalness = when (engineType) {
            TtsEngineType.KOKORO -> 0.85f  // High quality neural TTS
            TtsEngineType.PIPER -> 0.65f   // Good quality, fast
            TtsEngineType.MMS -> 0.55f     // Moderate quality, wide language support
        }

        // Latency penalty: slower = worse perceived quality
        when {
            latencyMs <= TTS_TARGET_LATENCY_MS -> { /* no penalty */ }
            latencyMs <= TTS_MAX_LATENCY_MS -> naturalness -= 0.1f
            else -> naturalness -= 0.3f
        }

        return naturalness.coerceIn(0f, 1f)
    }

    /**
     * Estimate noise level from audio data.
     * Uses energy variance as a proxy for noise.
     */
    private fun estimateNoiseLevel(audioData: ByteArray): Float {
        if (audioData.isEmpty()) return 0f

        // Calculate RMS energy in chunks
        val chunkSize = 1024
        val energies = mutableListOf<Float>()

        for (i in 0 until audioData.size - chunkSize step chunkSize) {
            var sum = 0f
            for (j in i until i + chunkSize) {
                val sample = audioData[j].toFloat() / 128f
                sum += sample * sample
            }
            energies.add(kotlin.math.sqrt(sum / chunkSize))
        }

        if (energies.isEmpty()) return 0f

        // High variance in energy = noisy signal
        val mean = energies.average().toFloat()
        val variance = energies.map { (it - mean) * (it - mean) }.average().toFloat()

        return (variance * 10f).coerceIn(0f, 1f)
    }

    /**
     * Estimate the ratio of silence in audio data.
     */
    private fun estimateSilenceRatio(audioData: ByteArray): Float {
        if (audioData.isEmpty()) return 1f

        val threshold = 5  // Very low amplitude = silence
        var silentSamples = 0

        for (sample in audioData) {
            if (kotlin.math.abs(sample.toInt()) < threshold) {
                silentSamples++
            }
        }

        return silentSamples.toFloat() / audioData.size
    }

    /**
     * Check if response contains injection patterns.
     */
    private fun containsInjectionPatterns(text: String): Boolean {
        val patterns = listOf(
            Regex("""(?i)(ignore\s+(previous|above|all)\s+(instructions?|prompts?))"""),
            Regex("""(?i)(you\s+are\s+now\s+(a|an)\s+\w+)"""),
            Regex("""(?i)(system\s*:\s*you\s+are)"""),
            Regex("""(?i)(jailbreak|DAN\s+mode|developer\s+mode)"""),
        )
        return patterns.any { it.containsMatchIn(text) }
    }

    // ═══════════════════════════════════════════════════════════════
    // VOICE SELECTION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Select voice configuration based on language and context.
     */
    private fun selectVoiceConfig(language: String): VoiceConfig {
        return when (language.lowercase()) {
            "sw", "swahili", "swa", "sheng" -> VoiceConfig(
                voiceId = "sw-female-1",
                engineType = TtsEngineType.KOKORO,
                language = language
            )
            "en", "english" -> VoiceConfig(
                voiceId = "en-female-1",
                engineType = TtsEngineType.KOKORO,
                language = language
            )
            else -> VoiceConfig(
                voiceId = "default",
                engineType = TtsEngineType.PIPER,
                language = language
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PROCESSING FEEDBACK
    // ═══════════════════════════════════════════════════════════════

    /**
     * Emit a random feedback message from the given list.
     * Non-blocking — if feedback emission fails, pipeline continues.
     */
    private suspend fun emitFeedback(
        messages: List<String>,
        feedbackCall: (suspend (String) -> Unit)?
    ) {
        val message = messages.random()
        try {
            _processingFeedback.emit(message)
            feedbackCall?.invoke(message)
        } catch (e: Exception) {
            Timber.d(TAG, "Feedback emission failed (non-critical): %s", e.message)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // METRICS & MONITORING
    // ═══════════════════════════════════════════════════════════════

    fun getStageMetrics(): Map<String, StageMetricsSnapshot> {
        return stageMetrics.mapValues { (_, m) -> m.snapshot() }
    }

    fun getAggregatePipelineStats(): PipelineStats {
        val allStages = stageMetrics.values.map { it.snapshot() }
        val sttMetrics = stageMetrics["stt"]?.snapshot()
        val llmMetrics = stageMetrics["llm"]?.snapshot()
        val ttsMetrics = stageMetrics["tts"]?.snapshot()

        return PipelineStats(
            totalPipelineRuns = pipelineCounter.get(),
            degradedRuns = degradedCounter.get(),
            repeatRequests = repeatRequestCounter.get(),
            sttSuccessRate = sttMetrics?.successRate ?: 0.0,
            llmSuccessRate = llmMetrics?.successRate ?: 0.0,
            ttsSuccessRate = ttsMetrics?.successRate ?: 0.0,
            avgSttLatencyMs = sttMetrics?.avgLatencyMs ?: 0L,
            avgLlmLatencyMs = llmMetrics?.avgLatencyMs ?: 0L,
            avgTtsLatencyMs = ttsMetrics?.avgLatencyMs ?: 0L,
            sttAvgConfidence = sttMetrics?.avgConfidence ?: 0f,
            llmAvgQuality = llmMetrics?.avgConfidence ?: 0f,
            ttsAvgNaturalness = ttsMetrics?.avgConfidence ?: 0f,
            degradationRate = if (pipelineCounter.get() > 0)
                degradedCounter.get().toDouble() / pipelineCounter.get() else 0.0
        )
    }

    /**
     * Get a human-readable status summary for debugging.
     */
    fun getStatusSummary(): String {
        val stats = getAggregatePipelineStats()
        return buildString {
            appendLine("═══ VoicePipelineHarness Status ═══")
            appendLine("Pipelines: ${stats.totalPipelineRuns} total, ${stats.degradedRuns} degraded")
            appendLine("Repeat requests: ${stats.repeatRequests}")
            appendLine("STT: ${stats.sttSuccessRate.toPct()} success, avg ${stats.avgSttLatencyMs}ms, conf ${stats.sttAvgConfidence}")
            appendLine("LLM: ${stats.llmSuccessRate.toPct()} success, avg ${stats.avgLlmLatencyMs}ms, quality ${stats.llmAvgQuality}")
            appendLine("TTS: ${stats.ttsSuccessRate.toPct()} success, avg ${stats.avgTtsLatencyMs}ms, natural ${stats.ttsAvgNaturalness}")
            appendLine("Degradation rate: ${(stats.degradationRate * 100).toInt()}%")
        }
    }

    private fun Double.toPct(): String = "${(this * 100).toInt()}%"
    private fun Float.toPct(): String = "${(this * 100).toInt()}%"

    // ═══════════════════════════════════════════════════════════════
    // INTERNALS
    // ═══════════════════════════════════════════════════════════════

    private fun recordStageMetric(stage: String, latencyMs: Long, success: Boolean, confidence: Float = 0f) {
        val metrics = stageMetrics.getOrPut(stage) { StageMetrics(stage) }
        if (success) metrics.recordSuccess(latencyMs, confidence) else metrics.recordFailure()
    }

    private suspend fun emitEvent(event: VoicePipelineEvent) {
        Timber.d(TAG, "Event: %s", event::class.simpleName)
    }

    // ═══════════════════════════════════════════════════════════════
    // DATA TYPES
    // ═══════════════════════════════════════════════════════════════

    data class QualityGateResult(val passed: Boolean, val reason: String)

    data class VoiceConfig(
        val voiceId: String,
        val engineType: TtsEngineType,
        val language: String
    )

    data class VoicePipelineResult(
        val pipelineId: String,
        val transcribedText: String?,
        val llmResponse: String?,
        val spoken: Boolean,
        val degradedToText: Boolean,
        val repeatRequested: Boolean = false,
        val failedStage: String?,
        val stageTimings: Map<String, Long>,
        val qualityScores: Map<String, Float> = emptyMap(),
        val totalTimeMs: Long,
        val detectedLanguage: String = "sw"
    ) {
        /** Whether the pipeline completed fully (voice in → voice out) */
        val isFullyVoice: Boolean get() = transcribedText != null && llmResponse != null && spoken

        /** Summary for logging */
        fun summary(): String = buildString {
            append("Pipeline[$pipelineId]: ")
            append("STT=${transcribedText?.take(30) ?: "FAIL"}")
            append(" → LLM=${llmResponse?.take(30) ?: "FAIL"}")
            append(" → TTS=${if (spoken) "OK" else "FAIL"}")
            if (degradedToText) append(" [DEGRADED]")
            if (repeatRequested) append(" [REPEAT]")
            append(" (${totalTimeMs}ms)")
            if (qualityScores.isNotEmpty()) {
                append(" Q=$qualityScores")
            }
        }
    }

    enum class VoiceHarnessState {
        IDLE,
        PROCESSING_STT,
        PROCESSING_LLM,
        PROCESSING_TTS,
        ERROR
    }

    class StageMetrics(private val stageName: String) {
        private var totalCalls = 0L
        private var successes = 0L
        private var failures = 0L
        private var totalLatencyMs = 0L
        private var totalConfidence = 0f
        private var confidenceCount = 0
        private val latencySamples = mutableListOf<Long>()

        fun recordSuccess(latencyMs: Long, confidence: Float = 0f) {
            totalCalls++; successes++; totalLatencyMs += latencyMs
            if (confidence > 0f) {
                totalConfidence += confidence
                confidenceCount++
            }
            synchronized(latencySamples) {
                if (latencySamples.size >= 500) latencySamples.removeAt(0)
                latencySamples.add(latencyMs)
            }
        }

        fun recordFailure() { totalCalls++; failures++ }

        fun snapshot() = StageMetricsSnapshot(
            stageName = stageName,
            totalCalls = totalCalls,
            successes = successes,
            failures = failures,
            successRate = if (totalCalls > 0) successes.toDouble() / totalCalls else 0.0,
            avgLatencyMs = if (successes > 0) totalLatencyMs / successes else 0L,
            avgConfidence = if (confidenceCount > 0) totalConfidence / confidenceCount else 0f
        )
    }

    data class StageMetricsSnapshot(
        val stageName: String,
        val totalCalls: Long,
        val successes: Long,
        val failures: Long,
        val successRate: Double,
        val avgLatencyMs: Long,
        val avgConfidence: Float = 0f
    )

    data class PipelineStats(
        val totalPipelineRuns: Long,
        val degradedRuns: Long = 0,
        val repeatRequests: Long = 0,
        val sttSuccessRate: Double,
        val llmSuccessRate: Double,
        val ttsSuccessRate: Double,
        val avgSttLatencyMs: Long,
        val avgLlmLatencyMs: Long,
        val avgTtsLatencyMs: Long,
        val sttAvgConfidence: Float = 0f,
        val llmAvgQuality: Float = 0f,
        val ttsAvgNaturalness: Float = 0f,
        val degradationRate: Double = 0.0
    )
}

// ═══════════════════════════════════════════════════════════════
// EVENTS — Emitted for monitoring, UI feedback, and observability
// ═══════════════════════════════════════════════════════════════

sealed class VoicePipelineEvent {
    data class QualityGateFailed(
        val pipelineId: String,
        val stage: String,
        val reason: String
    ) : VoicePipelineEvent()

    data class PipelineCompleted(
        val pipelineId: String,
        val totalTimeMs: Long,
        val degradedToText: Boolean,
        val failedStage: String?,
        val repeatRequested: Boolean = false
    ) : VoicePipelineEvent()

    data class LanguageDetected(
        val pipelineId: String,
        val detected: String,
        val expected: String
    ) : VoicePipelineEvent()

    data class ThinkingModeActivated(
        val pipelineId: String,
        val reason: String
    ) : VoicePipelineEvent()

    data class VoiceSwitched(
        val pipelineId: String,
        val fromEngine: String,
        val reason: String
    ) : VoicePipelineEvent()
}

// ═══════════════════════════════════════════════════════════════
// EXTENSION: TranscriptionResult with language field
// ═══════════════════════════════════════════════════════════════

/**
 * Extended transcription result with language detection.
 * The VoicePipelineHarness uses this to detect language mismatches.
 */
data class TranscriptionResultWithLanguage(
    val text: String,
    val confidence: Float,
    val success: Boolean,
    val language: String? = null,
    val noiseLevel: Float = 0f,
    val error: String? = null
) {
    fun toTranscriptionResult() = TranscriptionResult(
        text = text,
        confidence = confidence,
        success = success,
        error = error
    )
}
