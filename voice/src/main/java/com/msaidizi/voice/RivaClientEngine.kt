package com.msaidizi.voice

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import io.grpc.stub.MetadataUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.Closeable
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Riva NIM client engine — connects to a remote Riva server for ASR and TTS.
 *
 * Supports both cloud-hosted (build.nvidia.com / NVCF) and self-hosted Riva NIMs.
 * Implements [SpeechEngine] for transparent routing with [SpeechEngineRouter].
 *
 * Protocol: gRPC (primary for streaming + offline).
 * Fallback: HTTP REST can be added if gRPC is unavailable for a specific cloud NIM.
 *
 * Usage:
 * ```kotlin
 * val config = RivaConfig(context)
 * config.update(host = "grpc.nvcf.nvidia.com", port = 443, useSsl = true, apiKey = "...")
 * val engine = RivaClientEngine(config)
 * if (engine.isAvailable()) {
 *     val text = engine.recognize(audioSamples, 16000)
 *     val audio = engine.synthesize("Habari yako?", "sw")
 * }
 * engine.release()
 * ```
 *
 * Note: This class uses placeholder gRPC stubs. After generating stubs from
 * Riva proto definitions, replace the placeholder calls with actual gRPC calls.
 */
@Singleton
class RivaClientEngine @Inject constructor(
    private val config: RivaConfig
) : SpeechEngine, Closeable {

    override val engineName = "Riva NIM"

    private var channel: ManagedChannel? = null
    private var connected = false

    // ── SpeechEngine interface ───────────────────────────────

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!config.isEnabled) return@withContext false
            ensureConnected()
            channel?.let { !it.isShutdown && !it.isTerminated } ?: false
        } catch (e: Exception) {
            Timber.w(e, "Riva server not available")
            connected = false
            false
        }
    }

    override suspend fun recognize(audioData: FloatArray, sampleRate: Int): String =
        withContext(Dispatchers.IO) {
            try {
                if (!ensureConnected()) return@withContext ""

                val pcmBytes = SherpaOnnxEngine.AudioUtils.floatToPcm16(audioData)
                recognizeViaGrpc(pcmBytes, sampleRate)
            } catch (e: Exception) {
                Timber.e(e, "Riva ASR failed")
                ""
            }
        }

    override suspend fun recognizeFromPcm16(pcmData: ByteArray, sampleRate: Int): String =
        withContext(Dispatchers.IO) {
            try {
                if (!ensureConnected()) return@withContext ""
                recognizeViaGrpc(pcmData, sampleRate)
            } catch (e: Exception) {
                Timber.e(e, "Riva ASR failed")
                ""
            }
        }

    override suspend fun synthesize(text: String, language: String, speed: Float): ByteArray =
        withContext(Dispatchers.IO) {
            try {
                if (!ensureConnected()) return@withContext ByteArray(0)

                val langCode = when (language) {
                    "sw" -> "sw-KE"
                    "en" -> "en-US"
                    else -> "sw-KE"
                }

                synthesizeViaGrpc(text, langCode)
            } catch (e: Exception) {
                Timber.e(e, "Riva TTS failed")
                ByteArray(0)
            }
        }

    override fun release() {
        close()
    }

    // ── Closeable ────────────────────────────────────────────

    override fun close() {
        try {
            channel?.shutdown()?.awaitTermination(2, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Timber.w(e, "Error closing Riva channel")
        }
        channel = null
        connected = false
        Timber.i("Riva client closed")
    }

    // ── Connection management ────────────────────────────────

    /**
     * Ensure gRPC channel is connected. Returns true if connected.
     */
    @Synchronized
    private fun ensureConnected(): Boolean {
        if (connected && channel != null && !channel!!.isShutdown) return true

        return try {
            val builder = ManagedChannelBuilder
                .forAddress(config.serverHost, config.serverPort)
                .apply {
                    if (config.useSsl) {
                        useTransportSecurity()
                    } else {
                        usePlaintext()
                    }
                    // Keep-alive for streaming
                    keepAliveTime(30, TimeUnit.SECONDS)
                    keepAliveTimeout(10, TimeUnit.SECONDS)
                }

            channel = builder.build()

            // Attach auth metadata
            val metadata = Metadata()
            if (config.apiKey.isNotEmpty()) {
                metadata.put(
                    Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                    "Bearer ${config.apiKey}"
                )
            }

            connected = true
            Timber.i("Connected to Riva: %s:%d (ssl=%b)",
                config.serverHost, config.serverPort, config.useSsl)
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to connect to Riva server")
            connected = false
            false
        }
    }

    // ── gRPC ASR implementation ──────────────────────────────

    /**
     * Recognize speech via gRPC streaming.
     *
     * TODO: Replace placeholder implementation with actual Riva gRPC stubs
     * after generating from proto definitions:
     *   - RivaAsrServiceGrpc.RivaAsrServiceStub
     *   - RivaAsrProto.StreamingRecognizeRequest
     *   - RivaAsrProto.RecognitionConfig
     *
     * Proto source: https://github.com/nvidia-riva/riva-clients
     */
    private fun recognizeViaGrpc(pcmBytes: ByteArray, sampleRate: Int): String {
        // Placeholder — implement with generated gRPC stubs
        //
        // Expected flow:
        // 1. Build RecognitionConfig (language, sample rate, encoding)
        // 2. Build StreamingRecognitionConfig
        // 3. Create streaming request flow:
        //    - First message: streaming config
        //    - Subsequent messages: audio chunks (1-second chunks)
        // 4. Collect StreamingRecognizeResponse
        // 5. Return final transcript(s)
        //
        // Example (after stub generation):
        //
        // val stub = RivaAsrServiceGrpc.newStub(channel)
        //     .withInterceptors(authInterceptor)
        //
        // val config = RivaAsrProto.RecognitionConfig.newBuilder()
        //     .setLanguageCode(config.asrLanguageCode)
        //     .setSampleRateHertz(sampleRate)
        //     .setAudioChannelCount(1)
        //     .setEncoding(RivaAsrProto.AudioEncoding.LINEAR_PCM)
        //     .setEnableAutomaticPunctuation(true)
        //     .build()
        //
        // val streamingConfig = RivaAsrProto.StreamingRecognitionConfig.newBuilder()
        //     .setConfig(config)
        //     .setInterimResults(false)
        //     .build()
        //
        // val requestFlow = flow {
        //     emit(RivaAsrProto.StreamingRecognizeRequest.newBuilder()
        //         .setStreamingConfig(streamingConfig).build())
        //     for (i in pcmBytes.indices step sampleRate * 2) {
        //         val end = minOf(i + sampleRate * 2, pcmBytes.size)
        //         emit(RivaAsrProto.StreamingRecognizeRequest.newBuilder()
        //             .setAudioContent(ByteString.copyFrom(pcmBytes, i, end - i))
        //             .build())
        //     }
        // }
        //
        // val results = mutableListOf<String>()
        // stub.streamingRecognize(requestFlow).collect { response ->
        //     for (result in response.resultsList) {
        //         if (result.isFinal && result.alternativesCount > 0) {
        //             results.add(result.getAlternatives(0).transcript)
        //         }
        //     }
        // }
        // return results.joinToString(" ")

        Timber.w("Riva ASR gRPC stubs not yet generated — returning empty result")
        return ""
    }

    // ── gRPC TTS implementation ──────────────────────────────

    /**
     * Synthesize speech via gRPC.
     *
     * TODO: Replace placeholder implementation with actual Riva gRPC stubs
     * after generating from proto definitions:
     *   - RivaTtsServiceGrpc.RivaTtsServiceStub
     *   - RivaTtsProto.SynthesizeSpeechRequest
     */
    private fun synthesizeViaGrpc(text: String, languageCode: String): ByteArray {
        // Placeholder — implement with generated gRPC stubs
        //
        // Expected flow:
        // 1. Build SynthesizeSpeechRequest (text, language, voice, encoding)
        // 2. Call stub.synthesize(request)
        // 3. Return response.audio.toByteArray()
        //
        // Example (after stub generation):
        //
        // val stub = RivaTtsServiceGrpc.newStub(channel)
        //     .withInterceptors(authInterceptor)
        //
        // val request = RivaTtsProto.SynthesizeSpeechRequest.newBuilder()
        //     .setText(text)
        //     .setLanguageCode(languageCode)
        //     .setVoiceName(config.ttsVoiceName)
        //     .setEncoding(RivaTtsProto.AudioEncoding.LINEAR_PCM)
        //     .setSampleRateHz(22050)
        //     .build()
        //
        // val response = stub.synthesize(request)
        // return response.audio.toByteArray()

        Timber.w("Riva TTS gRPC stubs not yet generated — returning empty audio")
        return ByteArray(0)
    }

    // ── Diagnostics ──────────────────────────────────────────

    /**
     * Get engine status for diagnostics.
     */
    fun getDiagnostics(): Map<String, Any> = mapOf(
        "engine" to engineName,
        "connected" to connected,
        "channel_shutdown" to (channel?.isShutdown ?: true),
        "config" to config.toSummaryMap()
    )
}
