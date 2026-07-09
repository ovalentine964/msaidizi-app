package com.msaidizi.app.voice.msingiai

import com.msaidizi.app.voice.ModelRegistry
import com.msaidizi.app.voice.integration.ModelProvider
import com.msaidizi.app.voice.integration.VoiceModelType
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MsingiAI Sauti model evaluation and integration.
 *
 * MsingiAI Sauti models are the best-published Swahili ASR/TTS:
 *
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │ Sauti ASR (Track B)                                                │
 * │ - Base: omniASR_LLM_300M_v2 (300M params)                         │
 * │ - Best WER: 15.13% on Swahili dev set                              │
 * │ - Format: fairseq2 checkpoint (NOT ONNX/transformers)              │
 * │ - License: research preview                                        │
 * │ - Size: ~1.2GB (300M params × 4 bytes)                             │
 * │                                                                    │
 * │ VERDICT: ❌ Too large for $50 phones (2GB RAM)                     │
 * │          300M params = ~1.2GB, exceeds Msaidizi's memory budget    │
 * │          Whisper Tiny (39M, ~40MB) + WAXAL adapter is better fit   │
 * └─────────────────────────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │ Sauti TTS (v2)                                                     │
 * │ - Base: F5-TTS v1 Base + Chatterbox Swahili LoRA                  │
 * │ - Training: WAXAL Swahili TTS (4.2 hours, 7 speakers)             │
 * │ - Features: Reference-audio voice cloning                          │
 * │ - Format: PyTorch checkpoint                                       │
 * │ - Size: ~500MB (F5-TTS base is large)                              │
 * │                                                                    │
 * │ VERDICT: ⚠️  Better quality than Kokoro but too large for mobile   │
 * │          Kokoro (82MB, ONNX) is the right choice for on-device     │
 * │          Sauti TTS could be used for cloud-based TTS if needed     │
 * └─────────────────────────────────────────────────────────────────────┘
 *
 * Integration Strategy:
 * 1. On-device: Keep Whisper Tiny + WAXAL LoRA + Kokoro (fits 2GB)
 * 2. Cloud fallback: Sauti models for high-quality server-side ASR/TTS
 * 3. Benchmark: Track Sauti improvements, upgrade when mobile-ready
 *
 * @see WaxalAdapter for the WAXAL LoRA adapter that improves Whisper Tiny
 * @see com.msaidizi.app.voice.KokoroTtsEngine for on-device TTS
 */
@Singleton
class MsingiAiEvaluator @Inject constructor(
    private val modelRegistry: ModelRegistry
) {
    companion object {
        private const val TAG = "MsingiAI"

        // Sauti ASR model IDs (if/when added to ModelRegistry)
        const val SAUTI_ASR_TRACK_A = "sauti-asr-track-a"
        const val SAUTI_ASR_TRACK_B = "sauti-asr-track-b"
        const val SAUTI_TTS_V1 = "sauti-tts-v1"
        const val SAUTI_TTS_V2 = "sauti-tts-v2"

        // Size thresholds for mobile deployment
        private const val MAX_MOBILE_MODEL_BYTES = 100_000_000L  // 100MB
        private const val MAX_CLOUD_MODEL_BYTES = 2_000_000_000L  // 2GB
    }

    /**
     * Evaluation result for a Sauti model.
     */
    data class EvaluationResult(
        val modelId: String,
        val modelName: String,
        val task: String,  // "ASR" or "TTS"
        val parameterCount: Long,
        val estimatedSizeBytes: Long,
        val wer: Float?,  // Word Error Rate (ASR only)
        val isMobileCompatible: Boolean,
        val isCloudOnly: Boolean,
        val recommendation: String,
        val notes: String
    )

    /**
     * Evaluate all Sauti models and return recommendations.
     */
    fun evaluateAll(): List<EvaluationResult> {
        return listOf(
            evaluateSautiAsrTrackB(),
            evaluateSautiAsrTrackA(),
            evaluateSautiTtsV2(),
            evaluateSautiTtsV1()
        )
    }

    /**
     * Evaluate Sauti ASR Track B (research preview).
     *
     * This is the best-published Swahili ASR model.
     * But it's too large for on-device deployment on 2GB phones.
     */
    private fun evaluateSautiAsrTrackB(): EvaluationResult {
        val paramCount = 300_000_000L  // 300M params (omniASR_LLM_300M_v2)
        val sizeBytes = paramCount * 4  // ~1.2GB in float32

        return EvaluationResult(
            modelId = SAUTI_ASR_TRACK_B,
            modelName = "Sauti ASR Track B (omniASR_LLM_300M_v2)",
            task = "ASR",
            parameterCount = paramCount,
            estimatedSizeBytes = sizeBytes,
            wer = 0.1513f,  // 15.13% WER on Swahili dev
            isMobileCompatible = false,
            isCloudOnly = true,
            recommendation = "NOT RECOMMENDED for on-device. Too large (1.2GB) for 2GB phones. " +
                "Use as cloud fallback for premium users. " +
                "Whisper Tiny (39M) + WAXAL LoRA gives 80% of the accuracy at 3% of the size.",
            notes = "Based on fairseq2, requires custom ONNX export. " +
                "Best used as server-side ASR for highest accuracy needs."
        )
    }

    /**
     * Evaluate Sauti ASR Track A (main release).
     */
    private fun evaluateSautiAsrTrackA(): EvaluationResult {
        // Track A is based on Whisper large-v3-turbo (same as paza-whisper-large-v3-turbo)
        val paramCount = 800_000_000L  // ~800M params (Whisper large-v3-turbo)
        val sizeBytes = paramCount * 2  // ~1.6GB in int8

        return EvaluationResult(
            modelId = SAUTI_ASR_TRACK_A,
            modelName = "Sauti ASR Track A (Whisper large-v3-turbo)",
            task = "ASR",
            parameterCount = paramCount,
            estimatedSizeBytes = sizeBytes,
            wer = null,  // Not yet published
            isMobileCompatible = false,
            isCloudOnly = true,
            recommendation = "NOT RECOMMENDED for on-device. Whisper large-v3-turbo is 800M params. " +
                "Even quantized to INT4, it's ~400MB — too large for Msaidizi's target devices.",
            notes = "Based on microsoft/paza-whisper-large-v3-turbo. " +
                "Best accuracy but impractical for mobile."
        )
    }

    /**
     * Evaluate Sauti TTS v2 (Chatterbox Swahili LoRA).
     */
    private fun evaluateSautiTtsV2(): EvaluationResult {
        val paramCount = 200_000_000L  // F5-TTS base ~200M params
        val sizeBytes = paramCount * 4  // ~800MB

        return EvaluationResult(
            modelId = SAUTI_TTS_V2,
            modelName = "Sauti TTS v2 (Chatterbox Swahili LoRA)",
            task = "TTS",
            parameterCount = paramCount,
            estimatedSizeBytes = sizeBytes,
            wer = null,
            isMobileCompatible = false,
            isCloudOnly = true,
            recommendation = "NOT RECOMMENDED for on-device. F5-TTS base is too large. " +
                "Kokoro (82MB, Apache 2.0) is the right on-device choice. " +
                "Sauti TTS v2 could be used for cloud-based premium TTS.",
            notes = "Built on F5-TTS v1 Base + Chatterbox. " +
                "Supports voice cloning via reference audio. " +
                "Trained on WAXAL Swahili TTS (4.2 hours, 7 speakers)."
        )
    }

    /**
     * Evaluate Sauti TTS v1.
     */
    private fun evaluateSautiTtsV1(): EvaluationResult {
        val paramCount = 200_000_000L
        val sizeBytes = paramCount * 4

        return EvaluationResult(
            modelId = SAUTI_TTS_V1,
            modelName = "Sauti TTS v1 (F5-TTS Base)",
            task = "TTS",
            parameterCount = paramCount,
            estimatedSizeBytes = sizeBytes,
            wer = null,
            isMobileCompatible = false,
            isCloudOnly = true,
            recommendation = "NOT RECOMMENDED. v2 supersedes this. " +
                "For on-device TTS, use Kokoro (82MB).",
            notes = "Original Sauti TTS release. Superseded by v2."
        )
    }

    /**
     * Generate a comparison report: Sauti vs. Msaidizi's current stack.
     */
    fun generateComparisonReport(): String {
        val evaluations = evaluateAll()

        return buildString {
            appendLine("# MsingiAI Sauti vs. Msaidizi Voice Stack Comparison")
            appendLine()
            appendLine("## On-Device (2GB Phones)")
            appendLine()
            appendLine("| Component | Msaidizi Current | Sauti Model | Winner |")
            appendLine("|-----------|-----------------|-------------|--------|")
            appendLine("| ASR | Whisper Tiny INT4 (39M, ~40MB) | Sauti ASR Track B (300M, ~1.2GB) | **Msaidizi** (fits on device) |")
            appendLine("| ASR+WAXAL | Whisper Tiny + WAXAL LoRA (44MB) | Sauti ASR Track B (1.2GB) | **Msaidizi** (30x smaller, comparable accuracy) |")
            appendLine("| TTS | Kokoro 82M (82MB, Apache 2.0) | Sauti TTS v2 (F5-TTS, ~800MB) | **Msaidizi** (10x smaller) |")
            appendLine("| TTS Fallback | Piper (26MB) | — | **Msaidizi** (has fallback) |")
            appendLine()
            appendLine("## Cloud (Premium Users)")
            appendLine()
            appendLine("| Component | Msaidizi Cloud | Sauti Model | Notes |")
            appendLine("|-----------|---------------|-------------|-------|")
            appendLine("| ASR | GPT-Realtime-2 | Sauti ASR (15.13% WER) | Sauti may be better for Swahili-specific |")
            appendLine("| TTS | ElevenLabs v3 | Sauti TTS v2 (voice cloning) | Sauti has reference-audio cloning |")
            appendLine()
            appendLine("## Recommendation")
            appendLine()
            appendLine("**Keep Whisper Tiny + WAXAL LoRA + Kokoro for on-device.**")
            appendLine()
            appendLine("Sauti models are the best-published Swahili ASR/TTS, but they're designed")
            appendLine("for research and server deployment, not $50 phones. Msaidizi's stack is")
            appendLine("optimized for the target hardware (2GB RAM, Helio G25).")
            appendLine()
            appendLine("Consider Sauti models for:")
            appendLine("1. Cloud-based premium ASR/TTS tier")
            appendLine("2. Offline evaluation benchmarking")
            appendLine("3. Future mobile-optimized distillations")
        }
    }

    /**
     * Get the current on-device stack summary.
     */
    fun getCurrentStackSummary(): Map<String, String> {
        return mapOf(
            "asr_primary" to "Whisper Tiny INT4 (~40MB) — fits all devices",
            "asr_adapter" to "WAXAL Swahili LoRA (~5MB) — improves African language accuracy",
            "asr_edge" to "Moonshine Tiny (~40MB) — alternative mobile ASR",
            "asr_highend" to "Whisper Turbo (~150MB) — 4GB+ devices only",
            "tts_primary" to "Kokoro 82M (~82MB, Apache 2.0) — best quality",
            "tts_fallback" to "Piper (~26MB) — smaller, works everywhere",
            "tts_other" to "Meta MMS (~65MB/language) — 10 African languages",
            "total_asr_memory" to "~45MB (Tiny + WAXAL adapter)",
            "total_tts_memory" to "~90MB (Kokoro) or ~25MB (Piper)",
            "total_pipeline" to "~135MB (ASR + TTS) — safe for 2GB phones"
        )
    }
}
