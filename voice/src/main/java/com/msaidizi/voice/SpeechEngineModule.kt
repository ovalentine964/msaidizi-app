package com.msaidizi.voice

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for speech engine bindings.
 *
 * Provides the [SpeechEngineRouter] as the primary entry point for
 * ASR/TTS operations, with automatic routing between on-device
 * (sherpa-onnx) and cloud (Riva NIM) engines.
 */
@Module
@InstallIn(SingletonComponent::class)
object SpeechEngineModule {

    /**
     * Provide SherpaOnnxEngine as a SpeechEngine for local on-device ASR/TTS.
     */
    @Provides
    @Singleton
    fun provideLocalSpeechEngine(sherpaEngine: SherpaOnnxEngine): LocalSpeechEngine {
        return LocalSpeechEngine(sherpaEngine)
    }
}

/**
 * Wrapper that adapts [SherpaOnnxEngine] to the [SpeechEngine] interface.
 */
class LocalSpeechEngine(
    private val sherpaEngine: SherpaOnnxEngine
) : SpeechEngine {

    override val engineName = "sherpa-onnx (on-device)"

    override suspend fun isAvailable(): Boolean {
        // sherpa-onnx is always available if the native library loaded
        return try {
            sherpaEngine.getStatus()["recognizerCreated"] as? Boolean ?: false ||
            sherpaEngine.getStatus()["synthesizerCreated"] as? Boolean ?: false
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun recognize(audioData: FloatArray, sampleRate: Int): String {
        return sherpaEngine.recognize(audioData, sampleRate)
    }

    override suspend fun recognizeFromPcm16(pcmData: ByteArray, sampleRate: Int): String {
        return sherpaEngine.recognizeFromPcm16(pcmData, sampleRate)
    }

    override suspend fun synthesize(text: String, language: String, speed: Float): ByteArray {
        return sherpaEngine.synthesizeToPcm16(text, sid = 0, speed = speed)
    }

    override fun release() {
        sherpaEngine.release()
    }
}
