package com.msaidizi.app.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.msaidizi.app.core.util.DeviceTier
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import timber.log.Timber
import java.io.File
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Text-to-Speech engine.
 * Uses Piper TTS (ONNX) when available, falls back to Android built-in TTS.
 *
 * Piper model: piper-swahili.onnx (~25MB)
 * Latency: ~0.8s for 5s audio on Helio G25
 * RAM: ~25MB when loaded
 */
@Singleton
class TextToSpeechEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var piperEngine: PiperEngine? = null
    private var androidTts: TextToSpeech? = null
    private var isPiperAvailable = false
    private var isAndroidTtsReady = false

    private val _ttsState = MutableSharedFlow<TtsState>(extraBufferCapacity = 4)
    val ttsState: SharedFlow<TtsState> = _ttsState

    private var currentLanguage = Locale("sw")  // Swahili

    /**
     * Initialize TTS engine.
     * Tries Piper first, falls back to Android TTS.
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        // Try loading Piper TTS
        try {
            val modelFile = File(context.filesDir, "models/piper-swahili.onnx")
            if (modelFile.exists()) {
                piperEngine = PiperEngine(context)
                val loaded = piperEngine!!.loadModel(modelFile.absolutePath)
                if (loaded) {
                    isPiperAvailable = true
                    Timber.i("Piper TTS loaded successfully")
                    return@withContext true
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Piper TTS not available, falling back to Android TTS")
        }

        // Fall back to Android built-in TTS
        initializeAndroidTts()
    }

    /**
     * Initialize Android's built-in TTS as fallback.
     */
    private suspend fun initializeAndroidTts() = withContext(Dispatchers.Main) {
        androidTts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isAndroidTtsReady = true

                // Try setting Swahili
                val result = androidTts?.setLanguage(currentLanguage)
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    Timber.w("Swahili TTS not supported, using English")
                    androidTts?.setLanguage(Locale.ENGLISH)
                }

                // Configure for natural speech
                androidTts?.setSpeechRate(0.9f)  // Slightly slower for clarity
                androidTts?.setPitch(1.0f)

                // Set up progress listener
                androidTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        CoroutineScope(Dispatchers.Main).launch {
                            _ttsState.emit(TtsState.SPEAKING)
                        }
                    }

                    override fun onDone(utteranceId: String?) {
                        CoroutineScope(Dispatchers.Main).launch {
                            _ttsState.emit(TtsState.IDLE)
                        }
                    }

                    @Deprecated("Deprecated in API")
                    override fun onError(utteranceId: String?) {
                        CoroutineScope(Dispatchers.Main).launch {
                            _ttsState.emit(TtsState.ERROR)
                        }
                    }
                })

                Timber.i("Android TTS initialized")
            } else {
                Timber.e("Android TTS initialization failed: $status")
            }
        }
    }

    /**
     * Speak text aloud.
     * @param text Text to speak
     * @param language Language code (sw, en)
     * @param queueMode Whether to queue or interrupt current speech
     */
    suspend fun speak(
        text: String,
        language: String = "sw",
        queueMode: QueueMode = QueueMode.QUEUE
    ) {
        if (text.isBlank()) return

        _ttsState.emit(TtsState.PREPARING)

        when {
            isPiperAvailable -> speakWithPiper(text, language, queueMode)
            isAndroidTtsReady -> speakWithAndroid(text, language, queueMode)
            else -> {
                Timber.w("No TTS engine available")
                _ttsState.emit(TtsState.ERROR)
            }
        }
    }

    /**
     * Speak using Piper TTS (higher quality).
     */
    private suspend fun speakWithPiper(text: String, language: String, queueMode: QueueMode) {
        try {
            if (queueMode == QueueMode.INTERRUPT) {
                piperEngine?.stop()
            }
            piperEngine?.speak(text, language)
            _ttsState.emit(TtsState.SPEAKING)
        } catch (e: Exception) {
            Timber.e(e, "Piper TTS error, falling back to Android TTS")
            isPiperAvailable = false
            speakWithAndroid(text, language, queueMode)
        }
    }

    /**
     * Speak using Android's built-in TTS (fallback).
     */
    private suspend fun speakWithAndroid(text: String, language: String, queueMode: QueueMode) {
        withContext(Dispatchers.Main) {
            val locale = when (language) {
                "sw" -> Locale("sw")
                "en" -> Locale.ENGLISH
                else -> Locale("sw")
            }
            androidTts?.setLanguage(locale)

            val androidQueueMode = when (queueMode) {
                QueueMode.QUEUE -> TextToSpeech.QUEUE_ADD
                QueueMode.INTERRUPT -> TextToSpeech.QUEUE_FLUSH
            }

            val utteranceId = UUID.randomUUID().toString()
            androidTts?.speak(text, androidQueueMode, null, utteranceId)
        }
    }

    /**
     * Stop speaking.
     */
    fun stop() {
        piperEngine?.stop()
        androidTts?.stop()
        CoroutineScope(Dispatchers.Main).launch {
            _ttsState.emit(TtsState.IDLE)
        }
    }

    /**
     * Check if currently speaking.
     */
    fun isSpeaking(): Boolean {
        return piperEngine?.isSpeaking() == true || androidTts?.isSpeaking == true
    }

    /**
     * Set speech rate.
     * @param rate 0.5 (slow) to 2.0 (fast), 1.0 = normal
     */
    fun setSpeechRate(rate: Float) {
        androidTts?.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
        piperEngine?.setSpeed(rate)
    }

    /**
     * Set language.
     */
    fun setLanguage(language: String) {
        currentLanguage = when (language) {
            "sw" -> Locale("sw")
            "en" -> Locale.ENGLISH
            else -> Locale("sw")
        }
        androidTts?.setLanguage(currentLanguage)
    }

    /**
     * Release all resources.
     */
    fun release() {
        piperEngine?.release()
        piperEngine = null
        androidTts?.stop()
        androidTts?.shutdown()
        androidTts = null
        isPiperAvailable = false
        isAndroidTtsReady = false
    }
}

/**
 * TTS engine state.
 */
enum class TtsState {
    IDLE,
    PREPARING,
    SPEAKING,
    ERROR
}

/**
 * Queue mode for TTS.
 */
enum class QueueMode {
    QUEUE,      // Add to queue
    INTERRUPT   // Stop current and speak immediately
}

/**
 * Piper TTS engine wrapper.
 * Uses ONNX Runtime for inference.
 */
class PiperEngine(private val context: Context) {
    private var ortSession: ai.onnxruntime.OrtSession? = null
    private var ortEnvironment: ai.onnxruntime.OrtEnvironment? = null
    private var isModelLoaded = false
    private var isCurrentlySpeaking = false
    private var speed = 1.0f

    suspend fun loadModel(modelPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            ortEnvironment = ai.onnxruntime.OrtEnvironment.getEnvironment()
            val options = ai.onnxruntime.OrtSession.SessionOptions()
            options.setIntraOpNumThreads(DeviceTier.getInferenceThreads())
            ortSession = ortEnvironment!!.createSession(modelPath, options)
            isModelLoaded = true
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to load Piper model")
            false
        }
    }

    suspend fun speak(text: String, language: String) = withContext(Dispatchers.Default) {
        if (!isModelLoaded) return@withContext
        isCurrentlySpeaking = true

        try {
            // TODO: Implement Piper inference
            // This requires:
            // 1. Text to phoneme conversion
            // 2. Phoneme to mel spectrogram
            // 3. Mel to audio waveform
            // For now, fall back to Android TTS
            Timber.d("Piper speak: $text (not yet implemented)")
        } catch (e: Exception) {
            Timber.e(e, "Piper inference error")
        } finally {
            isCurrentlySpeaking = false
        }
    }

    fun stop() {
        isCurrentlySpeaking = false
    }

    fun isSpeaking(): Boolean = isCurrentlySpeaking

    fun setSpeed(speed: Float) {
        this.speed = speed.coerceIn(0.5f, 2.0f)
    }

    fun release() {
        stop()
        ortSession?.close()
        ortSession = null
        isModelLoaded = false
    }
}
