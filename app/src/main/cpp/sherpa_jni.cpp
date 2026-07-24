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
 */

#include <jni.h>
#include <android/log.h>

#include <string>
#include <vector>
#include <mutex>
#include <unordered_map>
#include <memory>
#include <cstring>
#include <sstream>

#define TAG "sherpa_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ── sherpa-onnx C API (conditionally available) ─────────────
#if __has_include("sherpa-onnx/c-api/c-api.h")
#include "sherpa-onnx/c-api/c-api.h"
#define HAVE_SHERPA 1
#else
#define HAVE_SHERPA 0
// Stub types so the file compiles without the library
typedef void* SherpaOnnxOnlineRecognizer;
typedef void* SherpaOnnxOfflineRecognizer;
typedef void* SherpaOnnxOfflineTts;
struct SherpaOnnxOfflineTtsGeneratedAudio {
    const float* samples;
    int32_t n;
};
#endif

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
        if (recognizer) SherpaOnnxOfflineRecognizerDestroy(recognizer);
#endif
    }
};

struct SynthesizerHandle {
#if HAVE_SHERPA
    const SherpaOnnxOfflineTts* tts = nullptr;
#endif
    std::mutex mu;
    bool valid = false;
    ~SynthesizerHandle() {
#if HAVE_SHERPA
        if (tts) SherpaOnnxOfflineTtsDestroy(tts);
#endif
    }
};

// Generic handle registry


// Separate registries for recogniser and synthesiser
static std::mutex g_recog_mu;
static std::unordered_map<jlong, std::unique_ptr<RecognizerHandle>> g_recognizers;
static jlong g_recog_next = 1;

static std::mutex g_tts_mu;
static std::unordered_map<jlong, std::unique_ptr<SynthesizerHandle>> g_synthesizers;
static jlong g_tts_next = 1;

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
Java_com_msaidizi_app_voice_SherpaOnnxEngine_nativeCreateRecognizer(
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

    // Parse config JSON (minimal parser — avoid pulling in a JSON lib)
    // Expected fields: encoder, decoder, tokens, language, numThreads
    // For production, use a proper JSON parser or pass individual params.

    auto rh = std::make_unique<RecognizerHandle>();

    SherpaOnnxOfflineRecognizerConfig config;
    memset(&config, 0, sizeof(config));

    // Use sensible defaults; actual paths come from the Kotlin layer
    // which already resolved them from the ModelManager.
    // The config JSON is a thin wrapper around sherpa-onnx's own config struct.

    // For now, we accept individual JNI params in a future overload;
    // this JSON path works with sherpa-onnx's built-in config parser.
    SherpaOnnxOfflineRecognizerConfig* parsed =
        SherpaOnnxOfflineRecognizerConfigParseFromJson(cfg.c_str());

    if (parsed) {
        rh->recognizer = SherpaOnnxOfflineRecognizerCreate(parsed);
        SherpaOnnxOfflineRecognizerConfigDestroy(parsed);
    }

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
Java_com_msaidizi_app_voice_SherpaOnnxEngine_nativeRecognize(
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
    SherpaOnnxOfflineStream* stream =
        SherpaOnnxOfflineRecognizerCreateStream(rh->recognizer);

    // Accept waveform
    SherpaOnnxOfflineStreamAcceptWaveform(stream, sampleRate, audio, len);
    env->ReleaseFloatArrayElements(jAudioData, audio, JNI_ABORT);

    // Decode
    SherpaOnnxOfflineRecognizerDecode(rh->recognizer, stream);

    // Get result
    const SherpaOnnxOfflineRecognizerResult* result =
        SherpaOnnxOfflineRecognizerGetResult(rh->recognizer, stream);

    std::string text;
    if (result && result->text) {
        text = result->text;
    }

    if (result) SherpaOnnxOfflineRecognizerDestroyResult(result);
    SherpaOnnxOfflineStreamDestroy(stream);

    LOGI("Recognised: %s (%zu chars)", text.c_str(), text.size());
    return env->NewStringUTF(text.c_str());
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_msaidizi_app_voice_SherpaOnnxEngine_nativeDestroyRecognizer(
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
Java_com_msaidizi_app_voice_SherpaOnnxEngine_nativeCreateSynthesizer(
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

    auto sh = std::make_unique<SynthesizerHandle>();

    SherpaOnnxOfflineTtsConfig* parsed =
        SherpaOnnxOfflineTtsConfigParseFromJson(cfg.c_str());

    if (parsed) {
        sh->tts = SherpaOnnxOfflineTtsCreate(parsed);
        SherpaOnnxOfflineTtsConfigDestroy(parsed);
    }

    if (!sh->tts) {
        LOGE("Failed to create synthesiser");
        throw_rte(env, "Failed to create sherpa-onnx TTS");
        return 0;
    }

    sh->valid = true;
    jlong handle = reg_tts(std::move(sh));
    LOGI("Synthesiser created — handle=%lld", (long long)handle);
    return handle;
#endif
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_msaidizi_app_voice_SherpaOnnxEngine_nativeSynthesize(
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

    SherpaOnnxOfflineTtsGeneratedAudio audio =
        SherpaOnnxOfflineTtsGenerate(sh->tts, text.c_str(), sid, speed);

    if (!audio.samples || audio.n <= 0) {
        LOGE("TTS generation returned no audio");
        return env->NewFloatArray(0);
    }

    // Copy to Java float array (samples are already float32 at 22050 Hz)
    jfloatArray result = env->NewFloatArray(audio.n);
    env->SetFloatArrayRegion(result, 0, audio.n, audio.samples);

    // Free native buffer
    SherpaOnnxOfflineTtsDestroyAudio(&audio);

    LOGI("Synthesised %d samples (%.2f seconds at 22050 Hz)",
         audio.n, audio.n / 22050.0f);
    return result;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_msaidizi_app_voice_SherpaOnnxEngine_nativeDestroySynthesizer(
        JNIEnv* /* env */, jobject /* thiz */,
        jlong handle) {
    SynthesizerHandle* sh = get_tts(handle);
    if (sh) {
        LOGI("Destroying synthesiser handle=%lld", (long long)handle);
        del_tts(handle);
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
    LOGI("sherpa_jni loaded — HAVE_SHERPA=%d", HAVE_SHERPA);
    return JNI_VERSION_1_6;
}
