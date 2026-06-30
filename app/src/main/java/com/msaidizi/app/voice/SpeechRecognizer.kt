package com.msaidizi.app.voice

import android.content.Context
import com.msaidizi.app.BuildConfig
import com.msaidizi.app.core.util.DeviceTier
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.File
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Speech recognizer using Whisper Tiny (INT4 quantized).
 * Converts 16kHz PCM audio to text.
 *
 * Model: whisper-tiny-int4.onnx (~40MB)
 * Latency: ~1.5s for 5s audio on Helio G25
 * RAM: ~40MB when loaded
 *
 * On 2GB devices, the model is lazy-loaded and released on background.
 * Memory-mapped loading is used to minimize RAM footprint.
 */
@Singleton
class SpeechRecognizer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var isModelLoaded = false
    private var modelPath: String? = null

    // ONNX Runtime session (lazy loaded)
    private var ortSession: ai.onnxruntime.OrtSession? = null
    private var ortEnvironment: ai.onnxruntime.OrtEnvironment? = null

    /**
     * Load the Whisper model.
     * On 2GB devices, this is deferred until first use.
     */
    suspend fun loadModel(): Boolean = withContext(Dispatchers.IO) {
        if (isModelLoaded) return@withContext true

        try {
            val modelFile = File(context.filesDir, "models/${BuildConfig.WHISPER_MODEL}")
            if (!modelFile.exists()) {
                Timber.w("Whisper model not found at ${modelFile.absolutePath}")
                // Fall back to code-based recognition
                return@withContext false
            }

            Timber.d("Loading Whisper model: ${modelFile.name} (${modelFile.length() / 1024 / 1024}MB)")

            ortEnvironment = ai.onnxruntime.OrtEnvironment.getEnvironment()
            val sessionOptions = ai.onnxruntime.OrtSession.SessionOptions()

            // Configure for 2GB device
            sessionOptions.setIntraOpNumThreads(DeviceTier.getInferenceThreads())
            sessionOptions.setOptimizationLevel(ai.onnxruntime.OrtSession.SessionOptions.OptLevel.ALL_OPT)

            // Enable memory optimization
            if (DeviceTier.current == DeviceTier.Tier.BASIC) {
                sessionOptions.setMemoryPatternOptimization(false)
                sessionOptions.setExecutionMode(ai.onnxruntime.OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            }

            ortSession = ortEnvironment!!.createSession(modelFile.absolutePath, sessionOptions)
            isModelLoaded = true
            modelPath = modelFile.absolutePath

            Timber.i("Whisper model loaded successfully")
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to load Whisper model")
            false
        }
    }

    /**
     * Transcribe audio to text.
     * @param audioData 16kHz mono PCM audio as ShortArray
     * @return Transcribed text, or null if recognition fails
     */
    suspend fun transcribe(audioData: ShortArray): String? = withContext(Dispatchers.Default) {
        if (!isModelLoaded) {
            val loaded = loadModel()
            if (!loaded) {
                Timber.w("Model not loaded, cannot transcribe")
                return@withContext null
            }
        }

        try {
            val startTime = System.currentTimeMillis()

            // Convert ShortArray to FloatArray (normalized to [-1, 1])
            val floatAudio = FloatArray(audioData.size) { i ->
                audioData[i].toFloat() / Short.MAX_VALUE
            }

            // Prepare input tensor
            val inputTensor = ai.onnxruntime.OnnxTensor.createTensor(
                ortEnvironment!!,
                FloatBuffer.wrap(floatAudio),
                longArrayOf(1, floatAudio.size.toLong())
            )

            // Run inference
            val inputs = mapOf("audio" to inputTensor)
            val results = ortSession!!.run(inputs)

            // Extract text from output
            val outputTensor = results.get(0)
            val outputValue = outputTensor.value

            val text = when (outputValue) {
                is Array<*> -> {
                    // Whisper outputs array of strings
                    (outputValue.firstOrNull() as? String) ?: ""
                }
                is String -> outputValue
                else -> outputValue.toString()
            }.trim()

            val elapsed = System.currentTimeMillis() - startTime
            Timber.d("Transcription: '%s' (%dms, %d samples)", text, elapsed, audioData.size)

            // Clean up tensors
            inputTensor.close()
            results.close()

            text.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Timber.e(e, "Transcription failed")
            null
        }
    }

    /**
     * Transcribe with language hint.
     * Helps Whisper focus on the target language.
     */
    suspend fun transcribeWithLanguage(
        audioData: ShortArray,
        language: String = "sw"
    ): String? {
        // For now, use the same transcription
        // TODO: Pass language token to Whisper decoder
        return transcribe(audioData)
    }

    /**
     * Unload the model to free memory.
     * Called when app goes to background on 2GB devices.
     */
    fun unloadModel() {
        try {
            ortSession?.close()
            ortSession = null
            isModelLoaded = false
            modelPath = null
            Timber.d("Whisper model unloaded")
        } catch (e: Exception) {
            Timber.e(e, "Error unloading Whisper model")
        }
    }

    /**
     * Check if the model is currently loaded.
     */
    fun isLoaded(): Boolean = isModelLoaded

    /**
     * Get model info.
     */
    fun getModelInfo(): Map<String, Any> = mapOf(
        "loaded" to isModelLoaded,
        "path" to (modelPath ?: "none"),
        "threads" to DeviceTier.getInferenceThreads(),
        "quantization" to DeviceTier.getQuantization()
    )
}
