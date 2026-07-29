package com.msaidizi.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.Closeable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Riva NIM client engine — stub for offline-first builds.
 *
 * gRPC dependencies (io.grpc.*) are not included in offline builds.
 * This stub always reports as unavailable so SpeechEngineRouter
 * falls back to on-device SherpaOnnxEngine.
 *
 * To enable cloud Riva:
 * 1. Add gRPC dependencies to voice/build.gradle.kts
 * 2. Generate Riva gRPC stubs from proto definitions
 * 3. Replace this stub with the full gRPC implementation
 */
@Singleton
class RivaClientEngine @Inject constructor(
    private val config: RivaConfig
) : SpeechEngine, Closeable {

    override val engineName = "Riva NIM (stub)"

    override suspend fun isAvailable(): Boolean = false

    override suspend fun recognize(audioData: FloatArray, sampleRate: Int): String {
        Timber.w("Riva NIM not available in offline build")
        return ""
    }

    override suspend fun recognizeFromPcm16(pcmData: ByteArray, sampleRate: Int): String {
        Timber.w("Riva NIM not available in offline build")
        return ""
    }

    override suspend fun synthesize(text: String, language: String, speed: Float): ByteArray {
        Timber.w("Riva NIM not available in offline build")
        return ByteArray(0)
    }

    override fun release() = close()

    override fun close() {
        Timber.i("Riva client stub closed")
    }

    fun getDiagnostics(): Map<String, Any> = mapOf(
        "engine" to engineName,
        "available" to false,
        "reason" to "offline build — gRPC not included"
    )
}
