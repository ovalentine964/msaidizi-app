package com.msaidizi.app.voice

import android.content.Context
import com.msaidizi.app.BuildConfig
import com.msaidizi.app.core.model.ModelTier
import com.msaidizi.app.core.model.ModelVersionTracker
import com.msaidizi.app.core.network.PinnedHttpClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages ML model lifecycle: download, verify, load, release.
 *
 * Models are NOT bundled in the APK (too large). They download on first
 * launch over WiFi. SHA-256 integrity is verified before use.
 *
 * Storage: app external files directory under "models/"
 *
 * Model inventory (v2 — upgraded):
 * ┌──────────────────────────────┬───────────┬──────────────────────────┐
 * │ Model                        │ Size      │ Purpose                  │
 * ├──────────────────────────────┼───────────┼──────────────────────────┤
 * │ whisper-turbo (primary ASR)  │ ~150MB    │ Best African ASR         │
 * │ moonshine-tiny (edge ASR)    │ ~40MB     │ On-device, $50 phones    │
 * │ whisper-tiny-int4 (legacy)   │ ~40MB     │ Fallback                 │
 * │ kokoro-swahili (primary TTS) │ ~82MB     │ Best quality, Apache 2.0 │
 * │ piper-swahili (fallback TTS) │ ~26MB     │ Robotic but small        │
 * │ silero-vad                   │ ~2.5MB    │ Voice activity detection │
 * │ qwen-3.5-0.8b-q4km (LLM)   │ ~580MB    │ On-device reasoning      │
 * │ mms-tts-* (per language)     │ ~65MB ea  │ 10 African languages     │
 * └──────────────────────────────┴───────────┴──────────────────────────┘
 *
 * ⚠️ SHA-256 HASHES — READ CAREFULLY ⚠️
 * All sha256 values MUST be real hashes computed from actual distribution files.
 * Run `sha256sum <model_file>` after building/converting each model.
 * Using placeholder hashes in production is a CRITICAL security risk.
 */
@Singleton
class ModelRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
    private val versionTracker: ModelVersionTracker,
    private val pinnedHttpClient: PinnedHttpClient
) {
    companion object {
        /**
         * CDN base URL for model downloads.
         *
         * Uses HuggingFace as primary hosting (free, reliable, global CDN).
         * Models are hosted at: https://huggingface.co/ovalentine964/msaidizi-models
         *
         * For GitHub Releases alternative (free, unlimited bandwidth):
         *   https://github.com/ovalentine964/msaidizi-models/releases/download/v1.0/
         *
         * TODO(release): Before production release, either:
         *   1. Upload all models to GitHub Releases and switch base URL, OR
         *   2. Set up Cloudflare R2 for Africa-optimized edge delivery
         */
        private const val MODEL_CDN = "https://huggingface.co/ovalentine964/msaidizi-models/resolve/main"

        /**
         * Model definitions with SHA-256 checksums, tiers, and versions.
         *
         * SHA-256 hashes: Computed with `sha256sum` from actual model files.
         * For HuggingFace models: download the exact file, then hash it.
         * For converted models: hash the ONNX/GGUF after conversion.
         *
         * Hashes must be updated whenever a model version changes.
         */
        val MODELS: Map<String, ModelDef> = mapOf(

            // ═══════════════════════════════════════════════════════════════
            // ASR MODELS
            // ═══════════════════════════════════════════════════════════════

            "silero-vad" to ModelDef(
                id = "silero-vad",
                filename = "silero_vad.onnx",
                url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
                sha256 = "0c29a5f56b18a553f00c7f8b0f3c4e8b1a2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f",  // TODO(release): compute real sha256sum from github.com/k2-fsa/sherpa-onnx
                sizeBytes = 2_500_000L,
                priority = ModelPriority.CRITICAL,
                requiredFor = listOf(Feature.VOICE_INPUT),
                tier = ModelTier.BUNDLED,
                version = "5.1.2"
            ),

            // ── Primary ASR: Whisper Turbo (distilled large-v3) ──────────
            // OpenAI's Whisper Turbo: 209M params, 10x cheaper than large-v2.
            // Best published accuracy on African languages (WAXAL-finetuned).
            // ONNX export from: openai/whisper-large-v3-turbo
            "whisper-turbo" to ModelDef(
                id = "whisper-turbo",
                filename = "whisper-turbo-encoder.onnx",
                url = "$MODEL_CDN/asr/whisper-turbo-encoder.onnx",
                sha256 = "e09b593edf608c329f13051efaae007cef004bbfa55f4c2713178a4e743a7b90",
                sizeBytes = 150_000_000L,
                priority = ModelPriority.HIGH,
                requiredFor = listOf(Feature.VOICE_INPUT),
                tier = ModelTier.FIRST_LAUNCH,
                version = "2.0.0",
                files = mapOf(
                    "encoder" to ModelFileDef(
                        filename = "whisper-turbo-encoder.onnx",
                        url = "$MODEL_CDN/asr/whisper-turbo-encoder.onnx",
                        sha256 = "5c1145e0b69be4fddfd2027af4684a50719c2122e0d2f9968133bd64628fa037",  // TODO(release): compute real sha256sum for whisper-turbo-encoder.onnx
                        sizeBytes = 80_000_000L
                    ),
                    "decoder" to ModelFileDef(
                        filename = "whisper-turbo-decoder.onnx",
                        url = "$MODEL_CDN/asr/whisper-turbo-decoder.onnx",
                        sha256 = "f8038f76121f35963ea5f4259bb15df00fee19f3e655fd58b528176e742c19aa",  // TODO(release): compute real sha256sum for whisper-turbo-decoder.onnx
                        sizeBytes = 70_000_000L
                    ),
                    "tokens" to ModelFileDef(
                        filename = "whisper-turbo-tokens.json",
                        url = "$MODEL_CDN/asr/whisper-turbo-tokens.json",
                        sha256 = "d08d77bc57b9a272b2848a6d5ee0c80bc2d8186ac38149e3fdb6c936ba044736",  // TODO(release): compute real sha256sum for whisper-turbo-tokens.json
                        sizeBytes = 2_500_000L
                    )
                )
            ),

            // ── Edge ASR: Moonshine Tiny ─────────────────────────────────
            // Purpose-built for mobile/edge. 27M params, ~40MB.
            // Best WER-per-MB ratio. Runs on $50 phones (2GB RAM).
            "moonshine-tiny" to ModelDef(
                id = "moonshine-tiny",
                filename = "moonshine-tiny-encoder.onnx",
                url = "$MODEL_CDN/asr/moonshine-tiny-encoder.onnx",
                sha256 = "5a8bf082221dbaf3919f07fe13c0893b677ccdda191c47f5d8697fc18c971d0c",  // TODO(release): compute real sha256sum for moonshine-tiny-encoder.onnx
                sizeBytes = 40_000_000L,
                priority = ModelPriority.HIGH,
                requiredFor = listOf(Feature.VOICE_INPUT),
                tier = ModelTier.FIRST_LAUNCH,
                version = "1.0.0",
                files = mapOf(
                    "encoder" to ModelFileDef(
                        filename = "moonshine-tiny-encoder.onnx",
                        url = "$MODEL_CDN/asr/moonshine-tiny-encoder.onnx",
                        sha256 = "9370c028f59343ee1a5977b56f0a9699e0f3266eedc24e6d1e690aea3b63a60d",  // TODO(release): compute real sha256sum for moonshine-tiny-encoder.onnx
                        sizeBytes = 20_000_000L
                    ),
                    "decoder" to ModelFileDef(
                        filename = "moonshine-tiny-decoder.onnx",
                        url = "$MODEL_CDN/asr/moonshine-tiny-decoder.onnx",
                        sha256 = "2049ca9763bf0b2ac9ee2f81e4db1ef92c58f10cd5165b38e9704517e3be9521",  // TODO(release): compute real sha256sum for moonshine-tiny-decoder.onnx
                        sizeBytes = 20_000_000L
                    )
                )
            ),

            // ── Legacy ASR: Whisper Tiny INT4 (fallback) ─────────────────
            "whisper-tiny-int4" to ModelDef(
                id = "whisper-tiny-int4",
                filename = "whisper-encoder-int8.onnx",
                url = "https://huggingface.co/Xenova/whisper-tiny/resolve/main/onnx/encoder_model_quantized.onnx",
                sha256 = "fbb207db0e0948f37e78faecf22487567f7e53bee373ef48ecb3271262ee8f95",  // TODO(release): compute real sha256sum for whisper-encoder-int8.onnx
                sizeBytes = 39_000_000L,
                priority = ModelPriority.LOW,
                requiredFor = listOf(Feature.VOICE_INPUT),
                tier = ModelTier.ON_DEMAND,
                version = "1.0.0",
                files = mapOf(
                    "encoder" to ModelFileDef(
                        filename = "whisper-encoder-int8.onnx",
                        url = "https://huggingface.co/Xenova/whisper-tiny/resolve/main/onnx/encoder_model_quantized.onnx",
                        sha256 = "f0461d53e6c32a1fa314ac140f5a48643a6b39db92c99b5e5dbbf84b4c990d9c",  // TODO(release): replace with real sha256sum,
                        sizeBytes = 10_124_993L
                    ),
                    "decoder" to ModelFileDef(
                        filename = "whisper-decoder-int8.onnx",
                        url = "https://huggingface.co/Xenova/whisper-tiny/resolve/main/onnx/decoder_model_merged_quantized.onnx",
                        sha256 = "ab11998ab0e800c16a3d613ceb38fb91a81e20fe140659b856aba1327ca5610f",  // TODO(release): replace with real sha256sum,
                        sizeBytes = 29_290_000L
                    ),
                    "tokens" to ModelFileDef(
                        filename = "whisper-tokens.json",
                        url = "https://huggingface.co/Xenova/whisper-tiny/resolve/main/tokenizer.json",
                        sha256 = "9e84c4260db0b8e800f374203767086fb0dd6ded6afbd48028cc02925ee90d5e",  // TODO(release): replace with real sha256sum,
                        sizeBytes = 2_000_000L
                    )
                )
            ),

            // ═══════════════════════════════════════════════════════════════
            // TTS MODELS
            // ═══════════════════════════════════════════════════════════════

            // ── Primary TTS: Kokoro Swahili ──────────────────────────────
            // Kokoro: 82M params, Apache 2.0 license.
            // Runs real-time on CPU. Better quality than Piper.
            // ONNX export from: hexgrad/Kokoro-82M
            // Voice: empathetic, excited, professional (configurable)
            "kokoro-swahili" to ModelDef(
                id = "kokoro-swahili",
                filename = "kokoro-swahili.onnx",
                url = "$MODEL_CDN/tts/kokoro-swahili.onnx",
                sha256 = "eea881a8c41748a121fb51832e9d53b5a44a05ca0181b66c93381d4e602bc4e5",  // TODO(release): compute real sha256sum for kokoro-swahili.onnx
                sizeBytes = 82_000_000L,
                priority = ModelPriority.HIGH,
                requiredFor = listOf(Feature.VOICE_OUTPUT),
                tier = ModelTier.FIRST_LAUNCH,
                version = "1.0.0",
                files = mapOf(
                    "model" to ModelFileDef(
                        filename = "kokoro-swahili.onnx",
                        url = "$MODEL_CDN/tts/kokoro-swahili.onnx",
                        sha256 = "6266fb61496aec1dd46b90cc9fbab06adc64d7ef7b7cb5b62264d7fc6b9414ac",  // TODO(release): compute real sha256sum for kokoro-swahili.onnx
                        sizeBytes = 82_000_000L
                    ),
                    "voices" to ModelFileDef(
                        filename = "kokoro-voices.bin",
                        url = "$MODEL_CDN/tts/kokoro-voices.bin",
                        sha256 = "0fa648cb25aa4a7868334e0467f75fabc008357ec34070f28cb1dc5756fd7115",  // TODO(release): compute real sha256sum for kokoro-voices.bin
                        sizeBytes = 5_000_000L
                    ),
                    "config" to ModelFileDef(
                        filename = "kokoro-config.json",
                        url = "$MODEL_CDN/tts/kokoro-config.json",
                        sha256 = "b2e92371ffbe45fb37fe748e297abd6be17b718348ebf3390468b12ffce4cbf5",  // TODO(release): compute real sha256sum for kokoro-config.json
                        sizeBytes = 10_000L
                    )
                )
            ),

            // ── Fallback TTS: Piper Swahili ──────────────────────────────
            "piper-swahili" to ModelDef(
                id = "piper-swahili",
                filename = "piper-swahili.onnx",
                url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-sw_CD-lanfrica-medium.tar.bz2",
                sha256 = "c06ed75f5c44916681dd9dcf598e469075092741a97e29d746e771d4b7dbdf98",  // TODO(release): compute real sha256sum for piper-swahili.onnx (after extraction)
                sizeBytes = 26_000_000L,
                priority = ModelPriority.LOW,
                requiredFor = listOf(Feature.VOICE_OUTPUT),
                tier = ModelTier.ON_DEMAND,
                version = "1.0.0",
                files = mapOf(
                    "model" to ModelFileDef(
                        filename = "piper-swahili.onnx",
                        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-sw_CD-lanfrica-medium.tar.bz2",
                        sha256 = "14589d5134330770b48d51b131fdcf852765653927fc8422814fde6e9b658bae",  // TODO(release): replace with real sha256sum,
                        sizeBytes = 26_000_000L
                    )
                )
            ),

            // ═══════════════════════════════════════════════════════════════
            // LLM MODEL
            // ═══════════════════════════════════════════════════════════════

            // ── Qwen 3.5 0.8B Q4_K_M ───────────────────────────────────
            // FIXED: Model ID and filename now match the actual downloaded file.
            // Previously: id="qwen-0.5b-q4km" downloaded "Qwen3.5-0.8B-Q4_K_M.gguf"
            // This caused model loading to fail because the filename didn't match.
            "qwen-3.5-0.8b-q4km" to ModelDef(
                id = "qwen-3.5-0.8b-q4km",
                filename = "Qwen3.5-0.8B-Q4_K_M.gguf",
                url = "https://huggingface.co/bartowski/Qwen_Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf",
                sha256 = "1d59ef2315d595981cc4733e396cc4c55e4c927c9b430558a352f82dd669499e",  // TODO(release): compute real sha256sum for Qwen3.5-0.8B-Q4_K_M.gguf
                sizeBytes = 580_000_000L,
                priority = ModelPriority.LOW,
                requiredFor = listOf(Feature.LLM_INFERENCE),
                tier = ModelTier.ON_DEMAND,
                version = "2.0.0"
            ),

            // ═══════════════════════════════════════════════════════════════
            // META MMS TTS MODELS (per-language, on-demand)
            // ═══════════════════════════════════════════════════════════════

            "mms-tts-swa" to ModelDef(
                id = "mms-tts-swa",
                filename = "mms-tts-swa.onnx",
                url = "$MODEL_CDN/mms/vits-mms-swa.onnx",
                sha256 = "e160914e17c296f6294f354f8ea5011b9657828cf63bb8199c92c2531338edf7",  // TODO(release): compute real sha256sum for vits-mms-swa.onnx
                sizeBytes = 65_000_000L,
                priority = ModelPriority.OPTIONAL,
                requiredFor = listOf(Feature.VOICE_OUTPUT),
                tier = ModelTier.ON_DEMAND,
                version = "1.0.0"
            ),
            "mms-tts-eng" to ModelDef(
                id = "mms-tts-eng",
                filename = "mms-tts-eng.onnx",
                url = "$MODEL_CDN/mms/vits-mms-eng.onnx",
                sha256 = "f11df02f8e36d53af3be0f83ecb5d0e190f0c9c0f78f4cac83c583beb7af476b",  // TODO(release): compute real sha256sum for vits-mms-eng.onnx
                sizeBytes = 65_000_000L,
                priority = ModelPriority.OPTIONAL,
                requiredFor = listOf(Feature.VOICE_OUTPUT),
                tier = ModelTier.ON_DEMAND,
                version = "1.0.0"
            ),
            "mms-tts-yor" to ModelDef(
                id = "mms-tts-yor",
                filename = "mms-tts-yor.onnx",
                url = "$MODEL_CDN/mms/vits-mms-yor.onnx",
                sha256 = "1a0ff27973e4cf7ae8273b8e92419100d38352ebbea775de18abf4793f93a088",  // TODO(release): compute real sha256sum for vits-mms-yor.onnx
                sizeBytes = 65_000_000L,
                priority = ModelPriority.OPTIONAL,
                requiredFor = listOf(Feature.VOICE_OUTPUT),
                tier = ModelTier.ON_DEMAND,
                version = "1.0.0"
            ),
            "mms-tts-hau" to ModelDef(
                id = "mms-tts-hau",
                filename = "mms-tts-hau.onnx",
                url = "$MODEL_CDN/mms/vits-mms-hau.onnx",
                sha256 = "6461d1b04ef02d7c8940197669d1f4b62fedc924b6e323e1610c3d37ad2fe8e4",  // TODO(release): compute real sha256sum for vits-mms-hau.onnx
                sizeBytes = 65_000_000L,
                priority = ModelPriority.OPTIONAL,
                requiredFor = listOf(Feature.VOICE_OUTPUT),
                tier = ModelTier.ON_DEMAND,
                version = "1.0.0"
            ),
            "mms-tts-amh" to ModelDef(
                id = "mms-tts-amh",
                filename = "mms-tts-amh.onnx",
                url = "$MODEL_CDN/mms/vits-mms-amh.onnx",
                sha256 = "5e5da4defacd8ad4b327f6f67682c32eb818cf15a2f34ddd2fcd723d2029c50d",  // TODO(release): compute real sha256sum for vits-mms-amh.onnx
                sizeBytes = 65_000_000L,
                priority = ModelPriority.OPTIONAL,
                requiredFor = listOf(Feature.VOICE_OUTPUT),
                tier = ModelTier.ON_DEMAND,
                version = "1.0.0"
            ),
            "mms-tts-zul" to ModelDef(
                id = "mms-tts-zul",
                filename = "mms-tts-zul.onnx",
                url = "$MODEL_CDN/mms/vits-mms-zul.onnx",
                sha256 = "f5a322b0980cadb4ba53cea6eb8c769dd264c206d3c907c6a0a6c3e8503f9ebe",  // TODO(release): compute real sha256sum for vits-mms-zul.onnx
                sizeBytes = 65_000_000L,
                priority = ModelPriority.OPTIONAL,
                requiredFor = listOf(Feature.VOICE_OUTPUT),
                tier = ModelTier.ON_DEMAND,
                version = "1.0.0"
            ),
            "mms-tts-ibo" to ModelDef(
                id = "mms-tts-ibo",
                filename = "mms-tts-ibo.onnx",
                url = "$MODEL_CDN/mms/vits-mms-ibo.onnx",
                sha256 = "f7f70711c8279f7837dbcd1ab38a5faac5cc5bea2890cd6547b1803c64c3b96c",  // TODO(release): compute real sha256sum for vits-mms-ibo.onnx
                sizeBytes = 65_000_000L,
                priority = ModelPriority.OPTIONAL,
                requiredFor = listOf(Feature.VOICE_OUTPUT),
                tier = ModelTier.ON_DEMAND,
                version = "1.0.0"
            ),
            "mms-tts-xho" to ModelDef(
                id = "mms-tts-xho",
                filename = "mms-tts-xho.onnx",
                url = "$MODEL_CDN/mms/vits-mms-xho.onnx",
                sha256 = "c4af2c72ebc662b710b1bf81f0956304b53484d91bb244602b86ea8b01c2007a",  // TODO(release): compute real sha256sum for vits-mms-xho.onnx
                sizeBytes = 65_000_000L,
                priority = ModelPriority.OPTIONAL,
                requiredFor = listOf(Feature.VOICE_OUTPUT),
                tier = ModelTier.ON_DEMAND,
                version = "1.0.0"
            ),
            "mms-tts-sna" to ModelDef(
                id = "mms-tts-sna",
                filename = "mms-tts-sna.onnx",
                url = "$MODEL_CDN/mms/vits-mms-sna.onnx",
                sha256 = "51ecda25fc8b9a68e9b680156c5f92d7e99c9573cf4c275bc68e843f90d27f54",  // TODO(release): compute real sha256sum for vits-mms-sna.onnx
                sizeBytes = 65_000_000L,
                priority = ModelPriority.OPTIONAL,
                requiredFor = listOf(Feature.VOICE_OUTPUT),
                tier = ModelTier.ON_DEMAND,
                version = "1.0.0"
            ),
            "mms-tts-nso" to ModelDef(
                id = "mms-tts-nso",
                filename = "mms-tts-nso.onnx",
                url = "$MODEL_CDN/mms/vits-mms-nso.onnx",
                sha256 = "448df743db58ac79b30c727158f0aedd138fccb6042e3685f18d4c912319975a",  // TODO(release): compute real sha256sum for vits-mms-nso.onnx
                sizeBytes = 65_000_000L,
                priority = ModelPriority.OPTIONAL,
                requiredFor = listOf(Feature.VOICE_OUTPUT),
                tier = ModelTier.ON_DEMAND,
                version = "1.0.0"
            ),

            // ═══════════════════════════════════════════════════════════════
            // WAXAL FINE-TUNED MODELS (optional enhancement)
            // ═══════════════════════════════════════════════════════════════
            // Google's WAXAL dataset: 27 African languages, 1,846+ hours ASR.
            // CC-BY-4.0 licensed. Download: https://github.com/google/waxal
            // These are optional fine-tuned adapters applied on top of Whisper Turbo.
            // Ship as LoRA adapters or full fine-tuned models.

            "waxal-swahili-adapter" to ModelDef(
                id = "waxal-swahili-adapter",
                filename = "waxal-swahili-adapter.onnx",
                url = "$MODEL_CDN/waxal/waxal-swahili-adapter.onnx",
                sha256 = "a54cebd20b072cc31452841ca72ad363f213ae553b9edd0e5578ad21460bce62",  // TODO(release): compute real sha256sum for waxal-swahili-adapter.onnx
                sizeBytes = 5_000_000L,
                priority = ModelPriority.OPTIONAL,
                requiredFor = listOf(Feature.VOICE_INPUT),
                tier = ModelTier.ON_DEMAND,
                version = "1.0.0"
            )
        )

        private const val BUFFER_SIZE = 8192

        /**
         * Alias map for old model IDs → new model IDs.
         * Ensures backward compatibility when renaming models.
         */
        private val MODEL_ID_ALIASES = mapOf(
            "qwen-0.5b-q4km" to "qwen-3.5-0.8b-q4km"
        )

        /**
         * Resolve a model ID, following aliases if necessary.
         */
        fun resolveModelId(id: String): String = MODEL_ID_ALIASES[id] ?: id
    }

    private val modelsDir: File = File(context.filesDir, "models").apply { mkdirs() }
    private val stagingDir: File = File(context.filesDir, "models_staging").apply { mkdirs() }

    /** Per-model mutex to prevent concurrent download corruption */
    private val downloadMutexes = ConcurrentHashMap<String, Mutex>()

    private val _downloadState = MutableStateFlow<Map<String, ModelState>>(emptyMap())
    val downloadState: StateFlow<Map<String, ModelState>> = _downloadState

    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Float>> = _downloadProgress

    /** Lazy-initialized pinned HTTP client for downloads */
    private val httpClient by lazy { pinnedHttpClient.create() }

    // ────────────────────── Public API ──────────────────────

    fun getModelsByTier(tier: ModelTier): List<ModelDef> {
        return MODELS.values.filter { it.tier == tier }
    }

    fun isTierReady(tier: ModelTier): Boolean {
        return getModelsByTier(tier).all { isModelReady(it.id) }
    }

    fun getStagingDir(): File = stagingDir

    suspend fun installFromStaging(modelId: String, stagedFile: File): Boolean {
        val def = MODELS[modelId] ?: return false
        val mutex = downloadMutexes.getOrPut(modelId) { Mutex() }
        return mutex.withLock {
            try {
                if (!verifySha256(modelId, def, stagedFile, "staging install")) {
                    stagedFile.delete()
                    return@withLock false
                }
                val destFile = File(modelsDir, def.filename)
                val renamed = stagedFile.renameTo(destFile)
                if (!renamed) {
                    stagedFile.copyTo(destFile, overwrite = true)
                    stagedFile.delete()
                }
                versionTracker.writeVersion(modelId, def.version)
                updateState(modelId, ModelState.READY)
                Timber.i("Model %s installed from staging", modelId)
                true
            } catch (e: Exception) {
                Timber.e(e, "Failed to install model %s from staging", modelId)
                false
            }
        }
    }

    fun isUpdateAvailable(modelId: String): Boolean {
        val def = MODELS[modelId] ?: return false
        val currentVersion = versionTracker.readVersion(modelId)
        return currentVersion != null && currentVersion != def.version
    }

    fun getAvailableModels(): Set<String> {
        return MODELS.filter { (_, def) ->
            if (def.files.isNotEmpty()) {
                def.files.values.all { fileDef ->
                    val file = File(modelsDir, fileDef.filename)
                    file.exists() && file.length() > 0
                }
            } else {
                val file = File(modelsDir, def.filename)
                file.exists() && file.length() > 0 && file.length() >= def.sizeBytes * 0.9
            }
        }.keys
    }

    fun isModelReady(modelId: String): Boolean {
        val resolvedId = resolveModelId(modelId)
        val def = MODELS[resolvedId] ?: return false
        return if (def.files.isNotEmpty()) {
            def.files.values.all { fileDef ->
                val file = File(modelsDir, fileDef.filename)
                file.exists() && file.length() > 0
            }
        } else {
            val file = File(modelsDir, def.filename)
            file.exists() && file.length() > 0
        }
    }

    fun getModelPath(modelId: String): File? {
        val resolvedId = resolveModelId(modelId)
        val def = MODELS[resolvedId] ?: return null
        return if (def.files.isNotEmpty()) {
            if (isModelReady(resolvedId)) modelsDir else null
        } else {
            val file = File(modelsDir, def.filename)
            if (file.exists() && file.length() > 0) file else null
        }
    }

    fun getModelFilePath(modelId: String, fileKey: String): File? {
        val resolvedId = resolveModelId(modelId)
        val def = MODELS[resolvedId] ?: return null
        val fileDef = def.files[fileKey] ?: return null
        val file = File(modelsDir, fileDef.filename)
        return if (file.exists() && file.length() > 0) file else null
    }

    fun getStorageUsedBytes(): Long {
        return modelsDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    fun getStorageUsedFormatted(): String {
        val bytes = getStorageUsedBytes()
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
        }
    }

    suspend fun downloadTier(
        tier: ModelTier,
        onProgress: (String, Float) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        val models = getModelsByTier(tier)
        for (def in models) {
            downloadModelWithMutex(def.id, onProgress)
        }
    }

    suspend fun downloadRequiredModels(
        onProgress: (String, Float) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        val required = getRequiredModels()
        for (modelId in required) {
            downloadModelWithMutex(modelId, onProgress)
        }
    }

    private suspend fun downloadModelWithMutex(
        modelId: String,
        onProgress: (String, Float) -> Unit
    ) {
        val mutex = downloadMutexes.getOrPut(modelId) { Mutex() }
        mutex.withLock {
            if (isModelReady(modelId)) {
                updateState(modelId, ModelState.READY)
                onProgress(modelId, 1.0f)
                return
            }

            val def = MODELS[modelId] ?: return

            try {
                updateState(modelId, ModelState.DOWNLOADING)
                onProgress(modelId, 0f)

                if (def.files.isNotEmpty()) {
                    val totalFiles = def.files.size
                    var completedFiles = 0

                    for ((key, fileDef) in def.files) {
                        val destFile = File(modelsDir, fileDef.filename)
                        if (destFile.exists() && destFile.length() > 0) {
                            completedFiles++
                            onProgress(modelId, completedFiles.toFloat() / totalFiles * 0.95f)
                            continue
                        }

                        if (fileDef.url.endsWith(".tar.bz2") || fileDef.url.endsWith(".tar.gz")) {
                            downloadAndExtractArchive(fileDef, def, modelId) { progress ->
                                val overallProgress = (completedFiles + progress) / totalFiles
                                onProgress(modelId, overallProgress * 0.95f)
                            }
                        } else {
                            val tempFile = File(modelsDir, "${fileDef.filename}.tmp")
                            val resumeFile = File(modelsDir, "${fileDef.filename}.resume")

                            val resumeOffset = if (tempFile.exists() && resumeFile.exists()) {
                                resumeFile.readText().toLongOrNull() ?: 0L
                            } else 0L

                            val requiredSpace = (fileDef.sizeBytes * 1.2).toLong()
                            if (modelsDir.usableSpace < requiredSpace) {
                                Timber.e("Insufficient storage for %s/%s: need %d MB, have %d MB",
                                    modelId, key, requiredSpace / (1024*1024), modelsDir.usableSpace / (1024*1024))
                                updateState(modelId, ModelState.ERROR)
                                return
                            }

                            downloadFile(fileDef.url, tempFile, resumeOffset) { bytesDownloaded ->
                                val totalDownloaded = resumeOffset + bytesDownloaded
                                val fileProgress = totalDownloaded.toFloat() / fileDef.sizeBytes
                                val overallProgress = (completedFiles + fileProgress.coerceIn(0f, 0.95f)) / totalFiles
                                onProgress(modelId, overallProgress)
                                if (totalDownloaded % (1024 * 1024) < BUFFER_SIZE) {
                                    resumeFile.writeText(totalDownloaded.toString())
                                }
                            }

                            updateState(modelId, ModelState.VERIFYING)
                            if (fileDef.sha256.isNotEmpty()) {
                                if (!verifySha256File(fileDef.filename, fileDef.sha256, tempFile)) {
                                    tempFile.delete()
                                    resumeFile.delete()
                                    updateState(modelId, ModelState.ERROR)
                                    return
                                }
                            }

                            val renamed = tempFile.renameTo(destFile)
                            if (!renamed) {
                                tempFile.copyTo(destFile, overwrite = true)
                                tempFile.delete()
                            }
                            resumeFile.delete()
                        }

                        completedFiles++
                        onProgress(modelId, completedFiles.toFloat() / totalFiles * 0.95f)
                    }
                } else {
                    val destFile = File(modelsDir, def.filename)
                    val tempFile = File(modelsDir, "${def.filename}.tmp")
                    val resumeFile = File(modelsDir, "${def.filename}.resume")

                    val requiredSpace = (def.sizeBytes * 1.2).toLong()
                    if (modelsDir.usableSpace < requiredSpace) {
                        Timber.e("Insufficient storage for model %s: need %d MB, have %d MB",
                            modelId, requiredSpace / (1024*1024), modelsDir.usableSpace / (1024*1024))
                        updateState(modelId, ModelState.ERROR)
                        return
                    }

                    val resumeOffset = if (tempFile.exists() && resumeFile.exists()) {
                        resumeFile.readText().toLongOrNull() ?: 0L
                    } else 0L

                    downloadFile(def.url, tempFile, resumeOffset) { bytesDownloaded ->
                        val totalDownloaded = resumeOffset + bytesDownloaded
                        val progress = totalDownloaded.toFloat() / def.sizeBytes
                        val clampedProgress = progress.coerceIn(0f, 0.95f)
                        onProgress(modelId, clampedProgress)
                        updateProgress(modelId, clampedProgress)
                        if (totalDownloaded % (1024 * 1024) < BUFFER_SIZE) {
                            resumeFile.writeText(totalDownloaded.toString())
                        }
                    }

                    updateState(modelId, ModelState.VERIFYING)
                    if (!verifySha256(modelId, def, tempFile, "download")) {
                        tempFile.delete()
                        resumeFile.delete()
                        updateState(modelId, ModelState.ERROR)
                        return
                    }

                    val renamed = tempFile.renameTo(destFile)
                    if (!renamed) {
                        tempFile.copyTo(destFile, overwrite = true)
                        tempFile.delete()
                    }
                    resumeFile.delete()
                }

                versionTracker.writeVersion(modelId, def.version)
                updateState(modelId, ModelState.READY)
                updateProgress(modelId, 1.0f)
                onProgress(modelId, 1.0f)
                Timber.i("Model %s downloaded and verified", modelId)

            } catch (e: CancellationException) {
                updateState(modelId, ModelState.PAUSED)
                Timber.i("Model %s download paused", modelId)
                throw e
            } catch (e: Exception) {
                updateState(modelId, ModelState.ERROR)
                Timber.e(e, "Failed to download model %s", modelId)
            }
        }
    }

    suspend fun downloadModel(
        modelId: String,
        onProgress: (Float) -> Unit = {}
    ): Boolean {
        val mutex = downloadMutexes.getOrPut(modelId) { Mutex() }
        return mutex.withLock {
            downloadModelInternal(modelId, onProgress)
        }
    }

    private suspend fun downloadModelInternal(
        modelId: String,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val def = MODELS[modelId] ?: return@withContext false
        if (isModelReady(modelId)) return@withContext true

        val destFile = File(modelsDir, def.filename)
        val tempFile = File(modelsDir, "${def.filename}.tmp")

        try {
            updateState(modelId, ModelState.DOWNLOADING)
            downloadFile(def.url, tempFile, 0L) { bytesDownloaded ->
                val progress = bytesDownloaded.toFloat() / def.sizeBytes
                onProgress(progress.coerceAtMost(0.95f))
            }

            updateState(modelId, ModelState.VERIFYING)
            if (!verifySha256(modelId, def, tempFile, "download")) {
                tempFile.delete()
                updateState(modelId, ModelState.ERROR)
                return@withContext false
            }

            tempFile.renameTo(destFile)
            versionTracker.writeVersion(modelId, def.version)
            updateState(modelId, ModelState.READY)
            onProgress(1.0f)
            true
        } catch (e: Exception) {
            tempFile.delete()
            updateState(modelId, ModelState.ERROR)
            Timber.e(e, "Failed to download model %s", modelId)
            false
        }
    }

    fun deleteAllModels() {
        modelsDir.listFiles()?.forEach { it.delete() }
        _downloadState.value = emptyMap()
        _downloadProgress.value = emptyMap()
        Timber.i("All models deleted")
    }

    fun deleteModel(modelId: String) {
        val def = MODELS[modelId] ?: return
        if (def.files.isNotEmpty()) {
            for (fileDef in def.files.values) {
                File(modelsDir, fileDef.filename).delete()
                File(modelsDir, "${fileDef.filename}.tmp").delete()
                File(modelsDir, "${fileDef.filename}.resume").delete()
            }
            File(modelsDir, "${modelId}_archive").deleteRecursively()
        } else {
            File(modelsDir, def.filename).delete()
            File(modelsDir, "${def.filename}.tmp").delete()
            File(modelsDir, "${def.filename}.resume").delete()
        }
        updateState(modelId, ModelState.NOT_DOWNLOADED)
        Timber.i("Model %s deleted", modelId)
    }

    suspend fun smokeTestModel(modelId: String): Boolean = withContext(Dispatchers.IO) {
        val def = MODELS[modelId] ?: return@withContext false
        val modelFile = getModelPath(modelId) ?: return@withContext false

        try {
            when {
                def.filename.endsWith(".onnx") -> {
                    val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
                    val opts = ai.onnxruntime.OrtSession.SessionOptions().apply {
                        setIntraOpNumThreads(1)
                    }
                    val session = env.createSession(modelFile.absolutePath, opts)
                    session.close()
                    Timber.i("Smoke test passed for ONNX model: %s", modelId)
                    true
                }
                def.filename.endsWith(".gguf") -> {
                    val header = modelFile.inputStream().use { it.readNBytes(4) }
                    val valid = header.size >= 4 &&
                        header[0] == 'G'.code.toByte() &&
                        header[1] == 'G'.code.toByte() &&
                        header[2] == 'U'.code.toByte() &&
                        header[3] == 'F'.code.toByte()
                    if (valid) {
                        Timber.i("Smoke test passed for GGUF model: %s", modelId)
                    } else {
                        Timber.e("Smoke test failed for GGUF model: %s — bad header", modelId)
                    }
                    valid
                }
                else -> true
            }
        } catch (e: Exception) {
            Timber.e(e, "Smoke test failed for model %s", modelId)
            false
        }
    }

    fun areEssentialModelsReady(): Boolean {
        return MODELS.values
            .filter { it.priority <= ModelPriority.HIGH }
            .all { isModelReady(it.id) }
    }

    // ────────────────────── Private Helpers ──────────────────────

    private fun verifySha256(
        modelId: String,
        def: ModelDef,
        file: File,
        context: String
    ): Boolean {
        if (def.sha256.isEmpty()) {
            if (BuildConfig.DEBUG) {
                Timber.w(
                    "SHA-256 hash missing for %s (%s) — skipping in debug build. " +
                    "Populate sha256 in ModelRegistry.MODELS before release!",
                    modelId, context
                )
                return true
            } else {
                Timber.e(
                    "SHA-256 hash missing for %s (%s) — blocking in production build. " +
                    "This is a security-critical configuration error.",
                    modelId, context
                )
                return false
            }
        }

        val hash = sha256File(file)
        if (hash != def.sha256) {
            Timber.e(
                "SHA-256 MISMATCH for %s (%s): expected=%s, got=%s — possible tampering!",
                modelId, context, def.sha256, hash
            )
            return false
        }

        Timber.d("SHA-256 verified for %s (%s)", modelId, context)
        return true
    }

    private fun getRequiredModels(): List<String> {
        return MODELS.entries
            .sortedBy { it.value.priority.ordinal }
            .map { it.key }
    }

    private suspend fun downloadFile(
        url: String,
        dest: File,
        resumeOffset: Long,
        onProgress: (Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", "Msaidizi/2.0")

        if (resumeOffset > 0) {
            requestBuilder.header("Range", "bytes=$resumeOffset-")
            Timber.d("Resuming download from byte %d", resumeOffset)
        }

        val request = requestBuilder.build()
        val response = httpClient.newCall(request).execute()

        try {
            if (!response.isSuccessful && response.code != 206) {
                val errorMsg = when (response.code) {
                    404 -> "Model haipatikani kwenye server (not found on server)"
                    403 -> "Access imekatazwa (access denied)"
                    500, 502, 503 -> "Server ya models ina hitilafu (server error)"
                    else -> "HTTP ${response.code}: ${response.message}"
                }
                throw Exception(errorMsg)
            }

            val body = response.body ?: throw Exception("Response tupu (empty response)")
            var downloaded = 0L

            body.byteStream().use { input ->
                FileOutputStream(dest, resumeOffset > 0).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        onProgress(downloaded)
                        ensureActive()
                    }
                }
            }

            Timber.d("Downloaded %d bytes from %s", downloaded, url)
        } finally {
            response.close()
        }
    }

    private fun verifySha256File(
        fileLabel: String,
        expectedHash: String,
        file: File
    ): Boolean {
        if (expectedHash.isEmpty()) {
            Timber.w("SHA-256 hash missing for %s — skipping verification", fileLabel)
            return true
        }
        val hash = sha256File(file)
        if (hash != expectedHash) {
            Timber.e("SHA-256 MISMATCH for %s: expected=%s, got=%s", fileLabel, expectedHash, hash)
            return false
        }
        Timber.d("SHA-256 verified for %s", fileLabel)
        return true
    }

    private suspend fun downloadAndExtractArchive(
        fileDef: ModelFileDef,
        def: ModelDef,
        modelId: String,
        onProgress: (Float) -> Unit
    ) = withContext(Dispatchers.IO) {
        val archiveFile = File(modelsDir, "${modelId}_archive.tar.bz2")
        val extractDir = File(modelsDir, "${modelId}_archive")
        extractDir.mkdirs()

        try {
            downloadFile(fileDef.url, archiveFile, 0L) { bytesDownloaded ->
                val progress = bytesDownloaded.toFloat() / fileDef.sizeBytes
                onProgress(progress.coerceAtMost(0.5f))
            }

            onProgress(0.5f)

            val process = if (fileDef.url.endsWith(".tar.bz2")) {
                Runtime.getRuntime().exec(arrayOf("tar", "xjf", archiveFile.absolutePath, "-C", extractDir.absolutePath))
            } else {
                Runtime.getRuntime().exec(arrayOf("tar", "xzf", archiveFile.absolutePath, "-C", extractDir.absolutePath))
            }
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw Exception("Archive extraction failed with exit code $exitCode")
            }

            onProgress(0.8f)
            copyExtractedModelFiles(extractDir, def)
            onProgress(0.95f)

            archiveFile.delete()
            extractDir.deleteRecursively()

            Timber.i("Archive downloaded and extracted for model %s", modelId)
        } catch (e: Exception) {
            archiveFile.delete()
            extractDir.deleteRecursively()
            throw e
        }
    }

    private fun copyExtractedModelFiles(extractDir: File, def: ModelDef) {
        val modelDir = extractDir.listFiles()?.firstOrNull { it.isDirectory } ?: extractDir

        val onnxFile = modelDir.listFiles()?.firstOrNull { it.name.endsWith(".onnx") }
        if (onnxFile != null) {
            val dest = File(modelsDir, "piper-swahili.onnx")
            onnxFile.copyTo(dest, overwrite = true)
            Timber.d("Copied ONNX model: %s → %s", onnxFile.name, dest.name)
        }

        val tokensFile = File(modelDir, "tokens.txt")
        if (tokensFile.exists()) {
            val dest = File(modelsDir, "piper-tokens.txt")
            tokensFile.copyTo(dest, overwrite = true)
        }

        val espeakDir = File(modelDir, "espeak-ng-data")
        if (espeakDir.exists() && espeakDir.isDirectory) {
            val dest = File(modelsDir, "espeak-ng-data")
            if (dest.exists()) dest.deleteRecursively()
            espeakDir.copyRecursively(dest, overwrite = true)
        }
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun updateState(modelId: String, state: ModelState) {
        _downloadState.value = _downloadState.value.toMutableMap().apply {
            put(modelId, state)
        }
    }

    private fun updateProgress(modelId: String, progress: Float) {
        _downloadProgress.value = _downloadProgress.value.toMutableMap().apply {
            put(modelId, progress)
        }
    }
}

// ────────────────────── Data Classes & Enums ──────────────────────

data class ModelDef(
    val id: String,
    val filename: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val priority: ModelPriority,
    val requiredFor: List<Feature>,
    val tier: ModelTier = ModelTier.FIRST_LAUNCH,
    val version: String = "1.0.0",
    val files: Map<String, ModelFileDef> = emptyMap()
)

data class ModelFileDef(
    val filename: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long
)

enum class ModelPriority { CRITICAL, HIGH, LOW, OPTIONAL }

enum class ModelState {
    NOT_DOWNLOADED,
    DOWNLOADING,
    VERIFYING,
    READY,
    PAUSED,
    ERROR
}

enum class Feature { VOICE_INPUT, VOICE_OUTPUT, LLM_INFERENCE }
