/**
 * sherpa_jni.cpp — JNI bridge for sherpa-onnx ASR & TTS.
 *
 * Provides on-device speech recognition (Whisper ONNX) and
 * text-to-speech (Piper ONNX) for the Msaidizi voice pipeline.
 *
 * JNI functions (SherpaOnnxEngine):
 *   nativeCreateRecognizer(configJson) → long
 *   nativeRecognize(handle, audioData, sampleRate) → String
 *   nativeDestroyRecognizer(handle)
 *
 *   nativeCreateSynthesizer(configJson) → long
 *   nativeSynthesize(handle, text) → float[] (PCM 16-bit LE)
 *   nativeDestroySynthesizer(handle)
 *
 * Config is passed as JSON for flexibility:
 *   {
 *     "encoder":  "/path/to/encoder.onnx",
 *     "decoder":  "/path/to/decoder.onnx",
 *     "tokens":   "/path/to/tokens.txt",
 *     "language": "sw",
 *     ...
 *   }
 *
 * Compatible with sherpa-onnx v1.13.4 C API.
 */

#include <jni.h>
#include <android/log.h>

#include <string>
#include <vector>
#include <mutex>
#include <unordered_map>
#include <memory>
#include <cstring>
#include <cstdlib>
#include <sstream>

#define TAG "sherpa_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ── sherpa-onnx C API (conditionally available) ─────────────
#if __has_include("sherpa-onnx/c-api/c-api.h")
#include "sherpa-onnx/c-api/c-api.h"
#define HAVE_SHERPA 1
#define HAVE_SHERPA_STREAMING 1
#else
#define HAVE_SHERPA 0
#define HAVE_SHERPA_STREAMING 0
// Stub types so the file compiles without the library
typedef void SherpaOnnxOfflineRecognizer;
typedef void SherpaOnnxOfflineTts;
typedef void SherpaOnnxOfflineStream;
typedef void SherpaOnnxOnlineRecognizer;
typedef void SherpaOnnxOnlineStream;
struct SherpaOnnxGeneratedAudio {
    const float* samples;
    int32_t n;
    int32_t sample_rate;
};
#endif

// ─────────────────────────────────────────────────────────────
// Minimal JSON field extractor (no external JSON lib needed)
// ─────────────────────────────────────────────────────────────

/// Extract a string value for a given key from a flat JSON object.
/// Returns empty string if key not found. Handles escaped quotes.
static std::string json_str(const char* json, const char* key) {
    if (!json || !key) return "";
    std::string needle = std::string("\"") + key + "\"";
    const char* pos = strstr(json, needle.c_str());
    if (!pos) return "";

    // Skip past "key"
    pos += needle.size();

    // Skip whitespace and colon
    while (*pos == ' ' || *pos == '\t' || *pos == ':') ++pos;

    if (*pos != '"') return "";

    ++pos; // skip opening quote
    std::string value;
    while (*pos && *pos != '"') {
        if (*pos == '\\' && *(pos + 1)) {
            ++pos;
            switch (*pos) {
                case '"':  value += '"';  break;
                case '\\': value += '\\'; break;
                case 'n':  value += '\n'; break;
                case 'r':  value += '\r'; break;
                case 't':  value += '\t'; break;
                default:   value += *pos; break;
            }
        } else {
            value += *pos;
        }
        ++pos;
    }
    return value;
}

/// Extract an integer value for a given key from a flat JSON object.
/// Returns default_val if key not found.
static int json_int(const char* json, const char* key, int default_val) {
    if (!json || !key) return default_val;
    std::string needle = std::string("\"") + key + "\"";
    const char* pos = strstr(json, needle.c_str());
    if (!pos) return default_val;
    pos += needle.size();
    while (*pos == ' ' || *pos == '\t' || *pos == ':') ++pos;
    return static_cast<int>(strtol(pos, nullptr, 10));
}

/// Extract a float value for a given key from a flat JSON object.
/// Returns default_val if key not found.
static float json_float(const char* json, const char* key, float default_val) {
    if (!json || !key) return default_val;
    std::string needle = std::string("\"") + key + "\"";
    const char* pos = strstr(json, needle.c_str());
    if (!pos) return default_val;
    pos += needle.size();
    while (*pos == ' ' || *pos == '\t' || *pos == ':') ++pos;
    return strtof(pos, nullptr);
}

// ─────────────────────────────────────────────────────────────
// Handle registry (recognisers & synthesisers)
// ─────────────────────────────────────────────────────────────

struct RecognizerHandle {
#if HAVE_SHERPA
    const SherpaOnnxOfflineRecognizer* recognizer = nullptr;
#endif
    std::mutex mu;
    bool valid = false;
    ~RecognizerHandle() {
#if HAVE_SHERPA
        if (recognizer) SherpaOnnxDestroyOfflineRecognizer(recognizer);
#endif
    }
};

struct SynthesizerHandle {
#if HAVE_SHERPA
    const SherpaOnnxOfflineTts* tts = nullptr;
    int32_t sample_rate = 22050;
#endif
    std::mutex mu;
    bool valid = false;
    ~SynthesizerHandle() {
#if HAVE_SHERPA
        if (tts) SherpaOnnxDestroyOfflineTts(tts);
#endif
    }
};

// Separate registries for recogniser and synthesiser
static std::mutex g_recog_mu;
static std::unordered_map<jlong, std::unique_ptr<RecognizerHandle>> g_recognizers;
static jlong g_recog_next = 1;

static std::mutex g_tts_mu;
static std::unordered_map<jlong, std::unique_ptr<SynthesizerHandle>> g_synthesizers;
static jlong g_tts_next = 1;

// ── Streaming recognizer handles ────────────────────────────
struct StreamingRecognizerHandle {
#if HAVE_SHERPA_STREAMING
    const SherpaOnnxOnlineRecognizer* recognizer = nullptr;
    const SherpaOnnxOnlineStream* stream = nullptr;
#endif
    std::mutex mu;
    bool valid = false;
    ~StreamingRecognizerHandle() {
#if HAVE_SHERPA_STREAMING
        if (stream) SherpaOnnxDestroyOnlineStream(stream);
        if (recognizer) SherpaOnnxDestroyOnlineRecognizer(recognizer);
#endif
    }
};

static std::mutex g_stream_mu;
static std::unordered_map<jlong, std::unique_ptr<StreamingRecognizerHandle>> g_stream_recognizers;
static jlong g_stream_next = 1;

static jlong reg_stream(std::unique_ptr<StreamingRecognizerHandle> h) {
    std::lock_guard<std::mutex> lock(g_stream_mu);
    jlong id = g_stream_next++;
    g_stream_recognizers[id] = std::move(h);
    return id;
}
static StreamingRecognizerHandle* get_stream(jlong h) {
    std::lock_guard<std::mutex> lock(g_stream_mu);
    auto it = g_stream_recognizers.find(h);
    return it != g_stream_recognizers.end() ? it->second.get() : nullptr;
}
static void del_stream(jlong h) {
    std::lock_guard<std::mutex> lock(g_stream_mu);
    g_stream_recognizers.erase(h);
}

static jlong reg_recog(std::unique_ptr<RecognizerHandle> h) {
    std::lock_guard<std::mutex> lock(g_recog_mu);
    jlong id = g_recog_next++;
    g_recognizers[id] = std::move(h);
    return id;
}
static RecognizerHandle* get_recog(jlong h) {
    std::lock_guard<std::mutex> lock(g_recog_mu);
    auto it = g_recognizers.find(h);
    return it != g_recognizers.end() ? it->second.get() : nullptr;
}
static void del_recog(jlong h) {
    std::lock_guard<std::mutex> lock(g_recog_mu);
    g_recognizers.erase(h);
}

static jlong reg_tts(std::unique_ptr<SynthesizerHandle> h) {
    std::lock_guard<std::mutex> lock(g_tts_mu);
    jlong id = g_tts_next++;
    g_synthesizers[id] = std::move(h);
    return id;
}
static SynthesizerHandle* get_tts(jlong h) {
    std::lock_guard<std::mutex> lock(g_tts_mu);
    auto it = g_synthesizers.find(h);
    return it != g_synthesizers.end() ? it->second.get() : nullptr;
}
static void del_tts(jlong h) {
    std::lock_guard<std::mutex> lock(g_tts_mu);
    g_synthesizers.erase(h);
}

// ─────────────────────────────────────────────────────────────
// JNI helpers
// ─────────────────────────────────────────────────────────────

static std::string jstr(JNIEnv* env, jstring js) {
    if (!js) return "";
    const char* raw = env->GetStringUTFChars(js, nullptr);
    std::string s(raw ? raw : "");
    if (raw) env->ReleaseStringUTFChars(js, raw);
    return s;
}

static void throw_rte(JNIEnv* env, const char* msg) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls) env->ThrowNew(cls, msg);
}

// ─────────────────────────────────────────────────────────────
// ── RECOGNIZER (ASR) JNI ────────────────────────────────────
// ─────────────────────────────────────────────────────────────

extern "C" JNIEXPORT jlong JNICALL
Java_com_msaidizi_voice_SherpaOnnxEngine_nativeCreateRecognizer(
        JNIEnv* env, jobject /* thiz */,
        jstring jConfigJson) {

#if !HAVE_SHERPA
    LOGW("sherpa-onnx not compiled — returning stub recogniser");
    auto rh = std::make_unique<RecognizerHandle>();
    rh->valid = false;
    return reg_recog(std::move(rh));
#else
    std::string cfg = jstr(env, jConfigJson);
    LOGI("Creating recogniser with config: %s", cfg.c_str());

    const char* json = cfg.c_str();
    auto rh = std::make_unique<RecognizerHandle>();

    // Build config manually from JSON fields (no ParseFromJson in C API)
    SherpaOnnxOfflineRecognizerConfig config;
    memset(&config, 0, sizeof(config));

    // Feature config defaults
    config.feat_config.sample_rate = json_int(json, "sample_rate", 16000);
    config.feat_config.feature_dim = json_int(json, "feature_dim", 80);

    // Model config — support Whisper encoder/decoder paths from Kotlin layer
    std::string encoder  = json_str(json, "encoder");
    std::string decoder  = json_str(json, "decoder");
    std::string tokens   = json_str(json, "tokens");
    std::string language = json_str(json, "language");
    std::string model_type = json_str(json, "model_type");

    // For Whisper models: encoder → whisper.encoder, decoder → whisper.decoder
    if (!encoder.empty()) config.model_config.whisper.encoder = encoder.c_str();
    if (!decoder.empty()) config.model_config.whisper.decoder = decoder.c_str();
    if (!language.empty()) config.model_config.whisper.language = language.c_str();
    config.model_config.whisper.task = "transcribe";

    // For transducer models: support encoder/decoder/joiner
    std::string joiner = json_str(json, "joiner");
    if (!joiner.empty()) {
        // If joiner is present, treat as transducer model
        config.model_config.transducer.encoder = encoder.c_str();
        config.model_config.transducer.decoder = decoder.c_str();
        config.model_config.transducer.joiner = joiner.c_str();
        // Clear whisper paths
        config.model_config.whisper.encoder = nullptr;
        config.model_config.whisper.decoder = nullptr;
        config.model_config.whisper.language = nullptr;
    }

    if (!tokens.empty()) config.model_config.tokens = tokens.c_str();
    config.model_config.num_threads = json_int(json, "num_threads", 2);
    config.model_config.debug = json_int(json, "debug", 0);
    if (!model_type.empty()) config.model_config.model_type = model_type.c_str();

    // Decoding method
    std::string decoding = json_str(json, "decoding_method");
    if (!decoding.empty()) config.decoding_method = decoding.c_str();

    rh->recognizer = SherpaOnnxCreateOfflineRecognizer(&config);

    if (!rh->recognizer) {
        LOGE("Failed to create recogniser");
        throw_rte(env, "Failed to create sherpa-onnx recogniser");
        return 0;
    }

    rh->valid = true;
    jlong handle = reg_recog(std::move(rh));
    LOGI("Recogniser created — handle=%lld", (long long)handle);
    return handle;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_msaidizi_voice_SherpaOnnxEngine_nativeRecognize(
        JNIEnv* env, jobject /* thiz */,
        jlong handle, jfloatArray jAudioData, jint sampleRate) {

#if !HAVE_SHERPA
    return env->NewStringUTF("[stub] ASR not available");
#else
    RecognizerHandle* rh = get_recog(handle);
    if (!rh || !rh->valid) {
        throw_rte(env, "Invalid recogniser handle");
        return env->NewStringUTF("");
    }

    std::lock_guard<std::mutex> lock(rh->mu);

    jsize len = env->GetArrayLength(jAudioData);
    jfloat* audio = env->GetFloatArrayElements(jAudioData, nullptr);
    if (!audio) {
        throw_rte(env, "Failed to get audio data");
        return env->NewStringUTF("");
    }

    // Create offline stream
    const SherpaOnnxOfflineStream* stream =
        SherpaOnnxCreateOfflineStream(rh->recognizer);

    // Feed waveform
    SherpaOnnxAcceptWaveformOffline(stream, sampleRate, audio, len);
    env->ReleaseFloatArrayElements(jAudioData, audio, JNI_ABORT);

    // Decode
    SherpaOnnxDecodeOfflineStream(rh->recognizer, stream);

    // Get result (returns a new allocation; caller must destroy)
    const SherpaOnnxOfflineRecognizerResult* result =
        SherpaOnnxGetOfflineStreamResult(stream);

    std::string text;
    if (result && result->text) {
        text = result->text;
    }

    // Clean up: destroy result, then stream
    if (result) SherpaOnnxDestroyOfflineRecognizerResult(result);
    SherpaOnnxDestroyOfflineStream(stream);

    LOGI("Recognised: %s (%zu chars)", text.c_str(), text.size());
    return env->NewStringUTF(text.c_str());
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_msaidizi_voice_SherpaOnnxEngine_nativeDestroyRecognizer(
        JNIEnv* /* env */, jobject /* thiz */,
        jlong handle) {
    RecognizerHandle* rh = get_recog(handle);
    if (rh) {
        LOGI("Destroying recogniser handle=%lld", (long long)handle);
        del_recog(handle);
    }
}

// ─────────────────────────────────────────────────────────────
// ── SYNTHESIZER (TTS) JNI ───────────────────────────────────
// ─────────────────────────────────────────────────────────────

extern "C" JNIEXPORT jlong JNICALL
Java_com_msaidizi_voice_SherpaOnnxEngine_nativeCreateSynthesizer(
        JNIEnv* env, jobject /* thiz */,
        jstring jConfigJson) {

#if !HAVE_SHERPA
    LOGW("sherpa-onnx not compiled — returning stub synthesiser");
    auto sh = std::make_unique<SynthesizerHandle>();
    sh->valid = false;
    return reg_tts(std::move(sh));
#else
    std::string cfg = jstr(env, jConfigJson);
    LOGI("Creating synthesiser with config: %s", cfg.c_str());

    const char* json = cfg.c_str();
    auto sh = std::make_unique<SynthesizerHandle>();

    // Build config manually from JSON fields (no ParseFromJson in C API)
    SherpaOnnxOfflineTtsConfig config;
    memset(&config, 0, sizeof(config));

    // Model paths — support Piper/VITS model from Kotlin layer
    std::string model    = json_str(json, "model");
    std::string tokens   = json_str(json, "tokens");
    std::string data_dir = json_str(json, "data_dir");
    std::string lexicon  = json_str(json, "lexicon");
    std::string dict_dir = json_str(json, "dict_dir");

    // Default to VITS model config (used by Piper TTS)
    if (!model.empty())    config.model.vits.model    = model.c_str();
    if (!tokens.empty())   config.model.vits.tokens   = tokens.c_str();
    if (!data_dir.empty()) config.model.vits.data_dir = data_dir.c_str();
    if (!lexicon.empty())  config.model.vits.lexicon  = lexicon.c_str();
    if (!dict_dir.empty()) config.model.vits.dict_dir = dict_dir.c_str();

    config.model.num_threads = json_int(json, "num_threads", 2);
    config.model.debug = json_int(json, "debug", 0);

    // Also support Kokoro model format
    std::string kokoro_model  = json_str(json, "kokoro_model");
    std::string kokoro_voices = json_str(json, "kokoro_voices");
    if (!kokoro_model.empty()) {
        config.model.kokoro.model  = kokoro_model.c_str();
        config.model.kokoro.voices = kokoro_voices.c_str();
        config.model.kokoro.tokens = tokens.c_str();
        config.model.kokoro.data_dir = data_dir.c_str();
        // Clear vits paths
        config.model.vits.model    = nullptr;
        config.model.vits.tokens   = nullptr;
        config.model.vits.data_dir = nullptr;
    }

    config.max_num_sentences = json_int(json, "max_num_sentences", 999);
    config.silence_scale = json_float(json, "silence_scale", 1.0f);

    sh->tts = SherpaOnnxCreateOfflineTts(&config);

    if (!sh->tts) {
        LOGE("Failed to create synthesiser");
        throw_rte(env, "Failed to create sherpa-onnx TTS");
        return 0;
    }

    // Cache the output sample rate
    sh->sample_rate = SherpaOnnxOfflineTtsSampleRate(sh->tts);

    sh->valid = true;
    jlong handle = reg_tts(std::move(sh));
    LOGI("Synthesiser created — handle=%lld, sample_rate=%d",
         (long long)handle, sh->sample_rate);
    return handle;
#endif
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_msaidizi_voice_SherpaOnnxEngine_nativeSynthesize(
        JNIEnv* env, jobject /* thiz */,
        jlong handle, jstring jText, jint sid, jfloat speed) {

#if !HAVE_SHERPA
    // Return silence stub
    jfloatArray empty = env->NewFloatArray(16000);  // 1s of silence
    return empty;
#else
    SynthesizerHandle* sh = get_tts(handle);
    if (!sh || !sh->valid) {
        throw_rte(env, "Invalid synthesiser handle");
        return env->NewFloatArray(0);
    }

    std::lock_guard<std::mutex> lock(sh->mu);

    std::string text = jstr(env, jText);
    if (text.empty()) {
        return env->NewFloatArray(0);
    }

    LOGI("Synthesising: %s (sid=%d, speed=%.2f)", text.c_str(), sid, speed);

    // Use the modern GenerateWithConfig API (non-deprecated)
    SherpaOnnxGenerationConfig gen_cfg;
    memset(&gen_cfg, 0, sizeof(gen_cfg));
    gen_cfg.sid = sid;
    gen_cfg.speed = speed;
    gen_cfg.silence_scale = 1.0f;

    const SherpaOnnxGeneratedAudio* audio =
        SherpaOnnxOfflineTtsGenerateWithConfig(sh->tts, text.c_str(),
                                               &gen_cfg, nullptr, nullptr);

    if (!audio || !audio->samples || audio->n <= 0) {
        LOGE("TTS generation returned no audio");
        if (audio) SherpaOnnxDestroyOfflineTtsGeneratedAudio(audio);
        return env->NewFloatArray(0);
    }

    // Copy to Java float array (samples are float32 at the model's output rate)
    jfloatArray result = env->NewFloatArray(audio->n);
    env->SetFloatArrayRegion(result, 0, audio->n, audio->samples);

    int32_t n_samples = audio->n;
    int32_t sr = audio->sample_rate > 0 ? audio->sample_rate : sh->sample_rate;

    // Free native buffer
    SherpaOnnxDestroyOfflineTtsGeneratedAudio(audio);

    LOGI("Synthesised %d samples (%.2f seconds at %d Hz)",
         n_samples, n_samples / (float)sr, sr);
    return result;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_msaidizi_voice_SherpaOnnxEngine_nativeDestroySynthesizer(
        JNIEnv* /* env */, jobject /* thiz */,
        jlong handle) {
    SynthesizerHandle* sh = get_tts(handle);
    if (sh) {
        LOGI("Destroying synthesiser handle=%lld", (long long)handle);
        del_tts(handle);
    }
}

// ─────────────────────────────────────────────────────────────
// ── STREAMING RECOGNIZER (Online ASR) JNI ──────────────────
// ─────────────────────────────────────────────────────────────

extern "C" JNIEXPORT jlong JNICALL
Java_com_msaidizi_voice_StreamingSttEngine_nativeCreateStreamingRecognizer(
        JNIEnv* env, jobject /* thiz */,
        jstring jConfigJson) {

#if !HAVE_SHERPA_STREAMING
    LOGW("sherpa-onnx streaming not compiled — returning stub");
    auto sh = std::make_unique<StreamingRecognizerHandle>();
    sh->valid = false;
    return reg_stream(std::move(sh));
#else
    std::string cfg = jstr(env, jConfigJson);
    LOGI("Creating streaming recogniser with config: %s", cfg.c_str());

    const char* json = cfg.c_str();
    auto sh = std::make_unique<StreamingRecognizerHandle>();

    SherpaOnnxOnlineRecognizerConfig config;
    memset(&config, 0, sizeof(config));

    // Feature config
    config.feat_config.sample_rate = json_int(json, "sample_rate", 16000);
    config.feat_config.feature_dim = json_int(json, "feature_dim", 80);

    // Model config — support transducer models (encoder/decoder/joiner)
    std::string encoder  = json_str(json, "encoder");
    std::string decoder  = json_str(json, "decoder");
    std::string joiner   = json_str(json, "joiner");
    std::string tokens   = json_str(json, "tokens");
    std::string language = json_str(json, "language");
    std::string model_type = json_str(json, "model_type");

    // Transducer model (streaming uses encoder/decoder/joiner)
    if (!encoder.empty()) config.model_config.transducer.encoder = encoder.c_str();
    if (!decoder.empty()) config.model_config.transducer.decoder = decoder.c_str();
    if (!joiner.empty())  config.model_config.transducer.joiner  = joiner.c_str();

    // Paraformer streaming model
    std::string paraformer_encoder = json_str(json, "paraformer_encoder");
    std::string paraformer_decoder = json_str(json, "paraformer_decoder");
    if (!paraformer_encoder.empty()) {
        config.model_config.paraformer.encoder = paraformer_encoder.c_str();
        config.model_config.paraformer.decoder = paraformer_decoder.c_str();
    }

    // Zipformer2 CTC streaming model
    std::string zipformer2_ctc = json_str(json, "zipformer2_ctc");
    if (!zipformer2_ctc.empty()) {
        config.model_config.zipformer2_ctc.model = zipformer2_ctc.c_str();
    }

    // NeMo CTC streaming model
    std::string nemo_ctc = json_str(json, "nemo_ctc");
    if (!nemo_ctc.empty()) {
        config.model_config.nemo_ctc.model = nemo_ctc.c_str();
    }

    if (!tokens.empty()) config.model_config.tokens = tokens.c_str();
    config.model_config.num_threads = json_int(json, "num_threads", 2);
    config.model_config.debug = json_int(json, "debug", 0);
    if (!model_type.empty()) config.model_config.model_type = model_type.c_str();

    // Decoding method (greedy_search recommended for streaming)
    std::string decoding = json_str(json, "decoding_method");
    config.decoding_method = decoding.empty() ? "greedy_search" : decoding.c_str();

    // Enable endpoint detection (for automatic utterance segmentation)
    config.enable_endpoint = 1;
    config.rule1.min_trailing_silence = json_float(json, "rule1_min_trailing_silence", 2.4f);
    config.rule2.min_trailing_silence = json_float(json, "rule2_min_trailing_silence", 1.2f);
    config.rule3.min_utterance_length = json_float(json, "rule3_min_utterance_length", 20.0f);

    // Hot words (optional)
    config.hotwords_file = nullptr;
    config.hotwords_score = json_float(json, "hotwords_score", 1.5f);

    // Create recognizer
    sh->recognizer = SherpaOnnxCreateOnlineRecognizer(&config);
    if (!sh->recognizer) {
        LOGE("Failed to create streaming recogniser");
        throw_rte(env, "Failed to create sherpa-onnx streaming recogniser");
        return 0;
    }

    // Create stream
    sh->stream = SherpaOnnxCreateOnlineStream(sh->recognizer);
    if (!sh->stream) {
        LOGE("Failed to create online stream");
        SherpaOnnxDestroyOnlineRecognizer(sh->recognizer);
        throw_rte(env, "Failed to create online stream");
        return 0;
    }

    sh->valid = true;
    jlong handle = reg_stream(std::move(sh));
    LOGI("Streaming recogniser created — handle=%lld", (long long)handle);
    return handle;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_msaidizi_voice_StreamingSttEngine_nativeAcceptWaveform(
        JNIEnv* env, jobject /* thiz */,
        jlong handle, jfloatArray jAudioData, jint sampleRate) {

#if !HAVE_SHERPA_STREAMING
    return;
#else
    StreamingRecognizerHandle* sh = get_stream(handle);
    if (!sh || !sh->valid) return;

    std::lock_guard<std::mutex> lock(sh->mu);

    jsize len = env->GetArrayLength(jAudioData);
    jfloat* audio = env->GetFloatArrayElements(jAudioData, nullptr);
    if (!audio) return;

    SherpaOnnxOnlineStreamAcceptWaveform(sh->stream, sampleRate, audio, len);
    env->ReleaseFloatArrayElements(jAudioData, audio, JNI_ABORT);
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_msaidizi_voice_StreamingSttEngine_nativeAcceptWaveformPcm16(
        JNIEnv* env, jobject /* thiz */,
        jlong handle, jbyteArray jPcmData, jint sampleRate) {

#if !HAVE_SHERPA_STREAMING
    return;
#else
    StreamingRecognizerHandle* sh = get_stream(handle);
    if (!sh || !sh->valid) return;

    std::lock_guard<std::mutex> lock(sh->mu);

    jsize len = env->GetArrayLength(jPcmData);
    jbyte* pcm = env->GetByteArrayElements(jPcmData, nullptr);
    if (!pcm) return;

    // Convert PCM16 bytes to float samples
    int nSamples = len / 2;
    std::vector<float> floatSamples(nSamples);
    const int16_t* pcm16 = reinterpret_cast<const int16_t*>(pcm);
    for (int i = 0; i < nSamples; i++) {
        floatSamples[i] = pcm16[i] / 32768.0f;
    }

    SherpaOnnxOnlineStreamAcceptWaveform(sh->stream, sampleRate,
                                          floatSamples.data(), nSamples);
    env->ReleaseByteArrayElements(jPcmData, pcm, JNI_ABORT);
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_msaidizi_voice_StreamingSttEngine_nativeIsReady(
        JNIEnv* /* env */, jobject /* thiz */,
        jlong handle) {

#if !HAVE_SHERPA_STREAMING
    return JNI_FALSE;
#else
    StreamingRecognizerHandle* sh = get_stream(handle);
    if (!sh || !sh->valid) return JNI_FALSE;
    return SherpaOnnxIsOnlineStreamReady(sh->recognizer, sh->stream) ? JNI_TRUE : JNI_FALSE;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_msaidizi_voice_StreamingSttEngine_nativeDecode(
        JNIEnv* /* env */, jobject /* thiz */,
        jlong handle) {

#if !HAVE_SHERPA_STREAMING
    return;
#else
    StreamingRecognizerHandle* sh = get_stream(handle);
    if (!sh || !sh->valid) return;

    std::lock_guard<std::mutex> lock(sh->mu);
    SherpaOnnxOnlineStreamDecode(sh->recognizer, sh->stream);
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_msaidizi_voice_StreamingSttEngine_nativeGetResult(
        JNIEnv* env, jobject /* thiz */,
        jlong handle) {

#if !HAVE_SHERPA_STREAMING
    return env->NewStringUTF("");
#else
    StreamingRecognizerHandle* sh = get_stream(handle);
    if (!sh || !sh->valid) return env->NewStringUTF("");

    std::lock_guard<std::mutex> lock(sh->mu);
    const SherpaOnnxOnlineRecognizerResult* result =
        SherpaOnnxGetOnlineStreamResult(sh->recognizer, sh->stream);

    std::string text;
    if (result && result->text) {
        text = result->text;
    }
    if (result) SherpaOnnxDestroyOnlineRecognizerResult(result);

    return env->NewStringUTF(text.c_str());
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_msaidizi_voice_StreamingSttEngine_nativeIsEndpoint(
        JNIEnv* /* env */, jobject /* thiz */,
        jlong handle) {

#if !HAVE_SHERPA_STREAMING
    return JNI_FALSE;
#else
    StreamingRecognizerHandle* sh = get_stream(handle);
    if (!sh || !sh->valid) return JNI_FALSE;
    return SherpaOnnxOnlineStreamIsEndpoint(sh->stream) ? JNI_TRUE : JNI_FALSE;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_msaidizi_voice_StreamingSttEngine_nativeReset(
        JNIEnv* /* env */, jobject /* thiz */,
        jlong handle) {

#if !HAVE_SHERPA_STREAMING
    return;
#else
    StreamingRecognizerHandle* sh = get_stream(handle);
    if (!sh || !sh->valid) return;

    std::lock_guard<std::mutex> lock(sh->mu);
    SherpaOnnxOnlineStreamReset(sh->stream);
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_msaidizi_voice_StreamingSttEngine_nativeDestroy(
        JNIEnv* /* env */, jobject /* thiz */,
        jlong handle) {
    StreamingRecognizerHandle* sh = get_stream(handle);
    if (sh) {
        LOGI("Destroying streaming recogniser handle=%lld", (long long)handle);
        del_stream(handle);
    }
}

// ─────────────────────────────────────────────────────────────
// JNI_OnLoad
// ─────────────────────────────────────────────────────────────

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* /* reserved */) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    LOGI("sherpa_jni loaded — HAVE_SHERPA=%d, HAVE_SHERPA_STREAMING=%d",
         HAVE_SHERPA, HAVE_SHERPA_STREAMING);
    return JNI_VERSION_1_6;
}
