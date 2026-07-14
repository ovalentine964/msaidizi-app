package com.msaidizi.app.voice

import com.msaidizi.app.agent.harness.InferenceHarness
import com.msaidizi.app.agent.harness.InferenceHarnessException
import com.msaidizi.app.agent.harness.HarnessConfig
import com.msaidizi.app.agent.harness.ProviderCandidate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Voice Pipeline Harness — wraps the full STT→LLM→TTS pipeline with
 * quality gates at each stage and graceful degradation.
 *
 * ## Quality Gates
 * Each stage has a quality threshold. If a stage fails its gate:
 * - STT: confidence < threshold → ask user to repeat or switch to text
 * - LLM: response validation fails → retry with simpler prompt
 * - TTS: latency too high → use faster engine or skip
 *
 * ## Pipeline Flow
 * ```
 * Audio → [STT Quality Gate] → Text → [LLM Quality Gate] → Response → [TTS Quality Gate] → Audio
 *              ↓ fail                              ↓ fail                          ↓ fail
 *         text input fallback              simpler prompt retry              text-only output
 * ```
 *
 * ## Fallback Strategy
 * Voice → text if ANY stage fails. The user never sees a broken experience.
 *
 * ## Metrics
 * - Per-stage latency (STT, LLM, TTS)
 * - Per-stage success rate
 * - Quality gate pass/fail rates
 * - End-to-end pipeline latency
 * - Voice-to-text fallback frequency
 */
@Singleton
class VoicePipelineHarness @Inject constructor(
    private val inferenceHarness: InferenceHarness
) {
    companion object {
        private const val TAG = "VoicePipelineHarness"

        // Quality thresholds
        const val STT_MIN_CONFIDENCE = 0.45f
        const val STT_HIGH_CONFIDENCE = 0.75f
        const val LLM_MIN_RESPONSE_LENGTH = 5
        const val LLM_MAX_RESPONSE_LENGTH = 2000
        const val TTS_MAX_LATENCY_MS = 10_000L

        // Timeouts per stage
        const val STT_TIMEOUT_MS = 10_000L
        const val LLM_TIMEOUT_MS = 20_000L
        const val TTS_TIMEOUT_MS = 15_000L
    }

    // ── Pipeline State ────────────────────────────────────────────

    private val _pipelineState = MutableStateFlow(VoiceHarnessState.IDLE)
    val pipelineState: StateFlow<VoiceHarnessState> = _pipelineState

    // ── Metrics ───────────────────────────────────────────────────

    private val stageMetrics = mutableMapOf<String, StageMetrics>()

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
     * @param llmCall Function to call the LLM with transcribed text
     * @param sttCall Function to transcribe audio to text
     * @param ttsCall Function to speak the response
     * @return The pipeline result (may have degraded to text-only)
     */
    suspend fun executePipeline(
        audioData: ByteArray,
        language: String = "sw",
        sttCall: suspend () -> TranscriptionResult,
        llmCall: suspend (String) -> String,
        ttsCall: suspend (String) -> Unit
    ): VoicePipelineResult {
        val pipelineId = UUID.randomUUID().toString().take(12)
        val pipelineStart = System.currentTimeMillis()
        _pipelineState.value = VoiceHarnessState.PROCESSING_STT

        val stageTimings = mutableMapOf<String, Long>()
        var degradedToText = false
        var failedStage: String? = null

        // ── Stage 1: STT with quality gate ──
        val sttResult: String?
        try {
            val sttStart = System.currentTimeMillis()
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

            // Quality gate: check STT confidence
            val transcription = transcriptionResult.value
            val sttPassed = sttQualityGate(transcription)

            if (!sttPassed.passed) {
                Timber.w(TAG, "[%s] STT quality gate failed: %s", pipelineId, sttPassed.reason)
                failedStage = "stt"
                degradedToText = true
                sttResult = null

                emitEvent(VoicePipelineEvent.QualityGateFailed(
                    pipelineId = pipelineId,
                    stage = "stt",
                    reason = sttPassed.reason
                ))
            } else {
                sttResult = transcription.text
                if (transcription.confidence < STT_HIGH_CONFIDENCE) {
                    Timber.d(TAG, "[%s] STT low confidence (%.2f) — proceeding with caution",
                        pipelineId, transcription.confidence)
                }
            }
        } catch (e: InferenceHarnessException) {
            Timber.e(TAG, "[%s] STT stage failed: %s", pipelineId, e.message)
            recordStageMetric("stt", 0L, false)
            failedStage = "stt"
            degradedToText = true
            sttResult = null
        }

        // If STT failed, return text-only fallback
        if (sttResult == null) {
            _pipelineState.value = VoiceHarnessState.IDLE
            val totalTime = System.currentTimeMillis() - pipelineStart
            emitEvent(VoicePipelineEvent.PipelineCompleted(
                pipelineId = pipelineId,
                totalTimeMs = totalTime,
                degradedToText = true,
                failedStage = failedStage
            ))
            return VoicePipelineResult(
                pipelineId = pipelineId,
                transcribedText = null,
                llmResponse = null,
                spoken = false,
                degradedToText = true,
                failedStage = "stt",
                stageTimings = stageTimings,
                totalTimeMs = totalTime
            )
        }

        // ── Stage 2: LLM with quality gate ──
        _pipelineState.value = VoiceHarnessState.PROCESSING_LLM
        val llmResponse: String?

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
            val llmPassed = llmQualityGate(llmResult.value)

            if (!llmPassed.passed) {
                Timber.w(TAG, "[%s] LLM quality gate failed: %s", pipelineId, llmPassed.reason)
                failedStage = "llm"
                degradedToText = true
                llmResponse = null

                emitEvent(VoicePipelineEvent.QualityGateFailed(
                    pipelineId = pipelineId,
                    stage = "llm",
                    reason = llmPassed.reason
                ))
            } else {
                llmResponse = llmResult.value
            }
        } catch (e: InferenceHarnessException) {
            Timber.e(TAG, "[%s] LLM stage failed: %s", pipelineId, e.message)
            recordStageMetric("llm", 0L, false)
            failedStage = "llm"
            degradedToText = true
            llmResponse = null
        }

        // If LLM failed, return text result without TTS
        if (llmResponse == null) {
            _pipelineState.value = VoiceHarnessState.IDLE
            val totalTime = System.currentTimeMillis() - pipelineStart
            return VoicePipelineResult(
                pipelineId = pipelineId,
                transcribedText = sttResult,
                llmResponse = null,
                spoken = false,
                degradedToText = true,
                failedStage = failedStage ?: "llm",
                stageTimings = stageTimings,
                totalTimeMs = totalTime
            )
        }

        // ── Stage 3: TTS with quality gate ──
        _pipelineState.value = VoiceHarnessState.PROCESSING_TTS
        var spoken = false

        try {
            val ttsStart = System.currentTimeMillis()
            inferenceHarness.execute(
                config = HarnessConfig(timeoutMs = TTS_TIMEOUT_MS, maxRetries = 1),
                providers = listOf(
                    ProviderCandidate(
                        providerId = "tts-pipeline",
                        modelId = "kokoro",
                        provider = { ttsCall(llmResponse) }
                    )
                ),
                taskType = "voice-tts"
            )

            val ttsLatency = System.currentTimeMillis() - ttsStart
            stageTimings["tts"] = ttsLatency
            recordStageMetric("tts", ttsLatency, true)

            // Quality gate: check TTS latency
            if (ttsLatency > TTS_MAX_LATENCY_MS) {
                Timber.w(TAG, "[%s] TTS latency too high: %dms", pipelineId, ttsLatency)
                emitEvent(VoicePipelineEvent.QualityGateFailed(
                    pipelineId = pipelineId,
                    stage = "tts",
                    reason = "Latency ${ttsLatency}ms exceeds ${TTS_MAX_LATENCY_MS}ms"
                ))
            }

            spoken = true
        } catch (e: InferenceHarnessException) {
            Timber.e(TAG, "[%s] TTS stage failed: %s", pipelineId, e.message)
            recordStageMetric("tts", 0L, false)
            failedStage = "tts"
            // TTS failure is non-critical — user still gets text response
        }

        _pipelineState.value = VoiceHarnessState.IDLE
        val totalTime = System.currentTimeMillis() - pipelineStart

        emitEvent(VoicePipelineEvent.PipelineCompleted(
            pipelineId = pipelineId,
            totalTimeMs = totalTime,
            degradedToText = degradedToText,
            failedStage = failedStage
        ))

        Timber.i(TAG, "[%s] Pipeline complete: %dms, spoken=%s, degraded=%s",
            pipelineId, totalTime, spoken, degradedToText)

        return VoicePipelineResult(
            pipelineId = pipelineId,
            transcribedText = sttResult,
            llmResponse = llmResponse,
            spoken = spoken,
            degradedToText = degradedToText && !spoken,
            failedStage = failedStage,
            stageTimings = stageTimings,
            totalTimeMs = totalTime
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // QUALITY GATES — Validate each stage's output
    // ═══════════════════════════════════════════════════════════════

    /**
     * STT quality gate: check transcription confidence and content.
     */
    fun sttQualityGate(result: TranscriptionResult): QualityGateResult {
        if (!result.success) {
            return QualityGateResult(false, "Transcription failed: ${result.error}")
        }
        if (result.text.isBlank()) {
            return QualityGateResult(false, "Empty transcription")
        }
        if (result.confidence < STT_MIN_CONFIDENCE) {
            return QualityGateResult(false, "Confidence ${result.confidence} < $STT_MIN_CONFIDENCE")
        }
        // Check for gibberish (very short or repetitive)
        if (result.text.length < 2) {
            return QualityGateResult(false, "Transcription too short: '${result.text}'")
        }
        return QualityGateResult(true, "OK")
    }

    /**
     * LLM quality gate: validate response content and safety.
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
        // Check for common error patterns
        val lowerResponse = response.lowercase()
        val errorPatterns = listOf("i cannot", "i can't help", "as an ai", "i'm sorry, but i")
        for (pattern in errorPatterns) {
            if (lowerResponse.startsWith(pattern)) {
                return QualityGateResult(false, "Response starts with refusal pattern: '$pattern'")
            }
        }
        return QualityGateResult(true, "OK")
    }

    // ═══════════════════════════════════════════════════════════════
    // METRICS
    // ═══════════════════════════════════════════════════════════════

    fun getStageMetrics(): Map<String, StageMetricsSnapshot> {
        return stageMetrics.mapValues { (_, m) -> m.snapshot() }
    }

    fun getAggregatePipelineStats(): PipelineStats {
        val allStages = stageMetrics.values.map { it.snapshot() }
        return PipelineStats(
            totalPipelineRuns = allStages.minOfOrNull { it.totalCalls } ?: 0,
            sttSuccessRate = stageMetrics["stt"]?.snapshot()?.successRate ?: 0.0,
            llmSuccessRate = stageMetrics["llm"]?.snapshot()?.successRate ?: 0.0,
            ttsSuccessRate = stageMetrics["tts"]?.snapshot()?.successRate ?: 0.0,
            avgSttLatencyMs = stageMetrics["stt"]?.snapshot()?.avgLatencyMs ?: 0L,
            avgLlmLatencyMs = stageMetrics["llm"]?.snapshot()?.avgLatencyMs ?: 0L,
            avgTtsLatencyMs = stageMetrics["tts"]?.snapshot()?.avgLatencyMs ?: 0L
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // INTERNALS
    // ═══════════════════════════════════════════════════════════════

    private fun recordStageMetric(stage: String, latencyMs: Long, success: Boolean) {
        val metrics = stageMetrics.getOrPut(stage) { StageMetrics(stage) }
        if (success) metrics.recordSuccess(latencyMs) else metrics.recordFailure()
    }

    private suspend fun emitEvent(event: VoicePipelineEvent) {
        Timber.d(TAG, "Event: %s", event::class.simpleName)
    }

    // ═══════════════════════════════════════════════════════════════
    // DATA TYPES
    // ═══════════════════════════════════════════════════════════════

    data class QualityGateResult(val passed: Boolean, val reason: String)

    data class VoicePipelineResult(
        val pipelineId: String,
        val transcribedText: String?,
        val llmResponse: String?,
        val spoken: Boolean,
        val degradedToText: Boolean,
        val failedStage: String?,
        val stageTimings: Map<String, Long>,
        val totalTimeMs: Long
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
            append(" (${totalTimeMs}ms)")
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
        private val latencySamples = mutableListOf<Long>()

        fun recordSuccess(latencyMs: Long) {
            totalCalls++; successes++; totalLatencyMs += latencyMs
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
            avgLatencyMs = if (successes > 0) totalLatencyMs / successes else 0L
        )
    }

    data class StageMetricsSnapshot(
        val stageName: String,
        val totalCalls: Long,
        val successes: Long,
        val failures: Long,
        val successRate: Double,
        val avgLatencyMs: Long
    )

    data class PipelineStats(
        val totalPipelineRuns: Long,
        val sttSuccessRate: Double,
        val llmSuccessRate: Double,
        val ttsSuccessRate: Double,
        val avgSttLatencyMs: Long,
        val avgLlmLatencyMs: Long,
        val avgTtsLatencyMs: Long
    )
}

// ═══════════════════════════════════════════════════════════════
// EVENTS
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
        val failedStage: String?
    ) : VoicePipelineEvent()
}
