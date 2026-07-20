package com.k2fsa.sherpa.onnx

/**
 * Sherpa-ONNX JNI bridge — native library loader and shared types.
 *
 * This file loads the prebuilt sherpa-onnx JNI libraries that are placed
 * in app/src/main/jniLibs/{arm64-v8a,armeabi-v7a}/ by scripts/setup-sherpa-onnx.sh.
 *
 * The Android build system automatically selects the correct .so for the
 * device's primary ABI (arm64-v8a on 64-bit, armeabi-v7a on 32-bit).
 * Voice features (ASR/TTS/VAD) work on both architectures.
 *
 * Libraries loaded:
 * - libonnxruntime.so  (~15MB) — ONNX Runtime for ARM64
 * - libsherpa-onnx-jni.so (~4MB) — sherpa-onnx JNI bindings
 */
object SherpaOnnxLoader {
    /** Whether the native library loaded successfully. */
    @Volatile
    var isAvailable: Boolean = false
        private set

    init {
        try {
            System.loadLibrary("sherpa-onnx-jni")
            isAvailable = true
        } catch (e: UnsatisfiedLinkError) {
            isAvailable = false
            // Graceful degradation: voice features will be disabled,
            // app continues in text-only mode instead of crashing.
            android.util.Log.e("SherpaOnnxLoader",
                "Failed to load sherpa-onnx JNI — voice features disabled", e)
        } catch (e: OutOfMemoryError) {
            isAvailable = false
            android.util.Log.e("SherpaOnnxLoader",
                "OOM loading sherpa-onnx JNI — voice features disabled", e)
            System.gc()
        }
    }

    /**
     * Check if the native library is loaded.
     * @return true if sherpa-onnx JNI is available
     */
    fun checkLoaded(): Boolean {
        return isAvailable
    }
}

/**
 * Audio feature configuration for ASR models.
 */
data class FeatureConfig(
    val sampleRate: Int = 16000,
    val featureDim: Int = 80,
)

/**
 * Whisper model configuration.
 */
data class WhisperModelConfig(
    val encoder: String,
    val decoder: String,
    val language: String = "sw",
    val task: String = "transcribe",
    val tailPaddings: Int = -1,
)

/**
 * Online (streaming) transducer model configuration.
 */
data class OnlineTransducerModelConfig(
    val encoder: String = "",
    val decoder: String = "",
    val joiner: String = "",
)

/**
 * Online (streaming) model configuration.
 */
data class OnlineModelConfig(
    val transducer: OnlineTransducerModelConfig = OnlineTransducerModelConfig(),
    val tokens: String,
    val numThreads: Int = 2,
    val debug: Boolean = false,
    val provider: String = "cpu",
    val modelType: String = "",
    val modelingUnit: String = "",
    val bpeVocab: String = "",
)

/**
 * Online recognizer configuration.
 */
data class OnlineRecognizerConfig(
    val featConfig: FeatureConfig = FeatureConfig(),
    val modelConfig: OnlineModelConfig,
    val enableEndpoint: Boolean = true,
    val rule1MinTrailingSilence: Float = 2.4f,
    val rule2MinTrailingSilence: Float = 1.2f,
    val rule3MinUtteranceLength: Float = 20.0f,
    val hotwordsFile: String = "",
    val hotwordsScore: Float = 1.5f,
    val blankPenalty: Float = 0.0f,
)

/**
 * Offline (non-streaming) model configuration.
 */
data class OfflineModelConfig(
    val whisper: WhisperModelConfig? = null,
    val tokens: String,
    val numThreads: Int = 2,
    val debug: Boolean = false,
    val provider: String = "cpu",
    val modelType: String = "",
    val modelingUnit: String = "",
    val bpeVocab: String = "",
)

/**
 * Offline recognizer configuration.
 */
data class OfflineRecognizerConfig(
    val featConfig: FeatureConfig = FeatureConfig(),
    val modelConfig: OfflineModelConfig,
    val hotwordsFile: String = "",
    val hotwordsScore: Float = 1.5f,
    val blankPenalty: Float = 0.0f,
)

/**
 * VAD model configuration (Silero VAD).
 */
data class VadModelConfig(
    val sileroVad: SileroVadModelConfig,
    val numThreads: Int = 1,
    val debug: Boolean = false,
    val provider: String = "cpu",
)

/**
 * Silero VAD model configuration.
 */
data class SileroVadModelConfig(
    val model: String,
    val threshold: Float = 0.5f,
    val minSilenceDuration: Float = 0.5f,
    val minSpeechDuration: Float = 0.25f,
    val maxSpeechDuration: Float = 30.0f,
    val speechPadLeft: Float = 0.0f,
    val speechPadRight: Float = 0.0f,
)

/**
 * TTS model configuration.
 */
data class TtsModelConfig(
    val vits: VitsModelConfig,
    val numThreads: Int = 2,
    val debug: Boolean = false,
    val provider: String = "cpu",
)

/**
 * VITS model configuration (used by Piper and similar TTS models).
 */
data class VitsModelConfig(
    val model: String,
    val tokens: String,
    val dataDir: String = "",
    val dictDir: String = "",
    val lexicon: String = "",
)

/**
 * TTS configuration.
 */
data class TtsConfig(
    val model: TtsModelConfig,
    val maxNumSentences: Int = 1,
)

/**
 * Result from offline (non-streaming) recognition.
 */
data class OfflineRecognitionResult(
    val text: String,
    val tokens: Array<String> = emptyArray(),
    val timestamps: FloatArray = floatArrayOf(),
    val lang: String = "",
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OfflineRecognitionResult) return false
        return text == other.text
    }
    override fun hashCode(): Int = text.hashCode()
}

/**
 * Result from online (streaming) recognition.
 */
data class OnlineRecognitionResult(
    val text: String,
    val tokens: Array<String> = emptyArray(),
    val timestamps: FloatArray = floatArrayOf(),
    val isEndpoint: Boolean = false,
    val lang: String = "",
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OnlineRecognitionResult) return false
        return text == other.text && isEndpoint == other.isEndpoint
    }
    override fun hashCode(): Int = text.hashCode() * 31 + isEndpoint.hashCode()
}

/**
 * Speech segment from VAD.
 */
data class SpeechSegment(
    val start: Int,
    val samples: FloatArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SpeechSegment) return false
        return start == other.start && samples.contentEquals(other.samples)
    }
    override fun hashCode(): Int = start * 31 + samples.contentHashCode()
}
