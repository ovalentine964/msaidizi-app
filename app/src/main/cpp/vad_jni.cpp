/**
 * vad_jni.cpp — JNI bridge for Voice Activity Detection (VAD).
 *
 * Uses sherpa-onnx's Silero VAD model for robust, on-device
 * voice activity detection. Replaces the naive amplitude-based
 * VAD in the original VoicePipeline.
 *
 * JNI functions (VadEngine):
 *   nativeCreateVad(modelPath, threshold, minSilence, minSpeech) → long
 *   nativeProcessAudio(handle, audioData) → boolean (speech detected)
 *   nativeIsSpeech(handle) → boolean
 *   nativeReset(handle)
 *   nativeDestroyVad(handle)
 */

#include <jni.h>
#include <android/log.h>

#include <string>
#include <vector>
#include <mutex>
#include <unordered_map>
#include <memory>
#include <cstring>
#include <cmath>

#define TAG "vad_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ── sherpa-onnx VAD C API (conditionally available) ─────────
#if __has_include("sherpa-onnx/c-api/c-api.h")
#include "sherpa-onnx/c-api/c-api.h"
#define HAVE_SHERPA 1
#else
#define HAVE_SHERPA 0
// Stub types
typedef void* SherpaOnnxVoiceActivityDetector;
#endif

// ─────────────────────────────────────────────────────────────
// VAD handle
// ─────────────────────────────────────────────────────────────

struct VadHandle {
#if HAVE_SHERPA
    SherpaOnnxVoiceActivityDetector* vad = nullptr;
#endif
    std::mutex mu;
    float sample_rate = 16000.0f;
    bool valid = false;
    bool speech_detected = false;

    ~VadHandle() {
#if HAVE_SHERPA
        if (vad) SherpaOnnxVoiceActivityDetectorDestroy(vad);
#endif
    }
};

// ── Registry ─────────────────────────────────────────────────
static std::mutex g_vad_mu;
static std::unordered_map<jlong, std::unique_ptr<VadHandle>> g_vads;
static jlong g_vad_next = 1;

static jlong reg_vad(std::unique_ptr<VadHandle> h) {
    std::lock_guard<std::mutex> lock(g_vad_mu);
    jlong id = g_vad_next++;
    g_vads[id] = std::move(h);
    return id;
}
static VadHandle* get_vad(jlong h) {
    std::lock_guard<std::mutex> lock(g_vad_mu);
    auto it = g_vads.find(h);
    return it != g_vads.end() ? it->second.get() : nullptr;
}
static void del_vad(jlong h) {
    std::lock_guard<std::mutex> lock(g_vad_mu);
    g_vads.erase(h);
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
// JNI: nativeCreateVad
// ─────────────────────────────────────────────────────────────

extern "C" JNIEXPORT jlong JNICALL
Java_com_msaidizi_app_voice_VadEngine_nativeCreateVad(
        JNIEnv* env, jobject /* thiz */,
        jstring jModelPath, jfloat threshold,
        jfloat minSilenceDuration, jfloat minSpeechDuration,
        jfloat maxSpeechDuration) {

#if !HAVE_SHERPA
    LOGW("sherpa-onnx not compiled — returning stub VAD");
    auto vh = std::make_unique<VadHandle>();
    vh->valid = false;
    return reg_vad(std::move(vh));
#else
    std::string model_path = jstr(env, jModelPath);
    if (model_path.empty()) {
        throw_rte(env, "VAD model path is empty");
        return 0;
    }

    LOGI("Creating VAD: model=%s threshold=%.2f minSilence=%.2f minSpeech=%.2f maxSpeech=%.2f",
         model_path.c_str(), threshold, minSilenceDuration, minSpeechDuration, maxSpeechDuration);

    auto vh = std::make_unique<VadHandle>();

    SherpaOnnxVadModelConfig vad_config;
    memset(&vad_config, 0, sizeof(vad_config));

    // Silero VAD model config
    strncpy(vad_config.silero_vad.model, model_path.c_str(),
            sizeof(vad_config.silero_vad.model) - 1);
    vad_config.silero_vad.threshold = threshold;
    vad_config.silero_vad.min_silence_duration = minSilenceDuration;
    vad_config.silero_vad.min_speech_duration = minSpeechDuration;
    vad_config.silero_vad.max_speech_duration = maxSpeechDuration;
    vad_config.silero_vad.window_size = 512;  // Silero v4 expects 512 samples

    vad_config.sample_rate = 16000;
    vad_config.num_threads = 2;

    vh->vad = SherpaOnnxVoiceActivityDetectorCreate(&vad_config, 30.0f /* buffer size in seconds */);
    if (!vh->vad) {
        LOGE("Failed to create VAD");
        throw_rte(env, "Failed to create VAD detector");
        return 0;
    }

    vh->sample_rate = 16000.0f;
    vh->valid = true;
    jlong handle = reg_vad(std::move(vh));

    LOGI("VAD created — handle=%lld", (long long)handle);
    return handle;
#endif
}

// ─────────────────────────────────────────────────────────────
// JNI: nativeProcessAudio
// Feed audio samples and return whether speech is detected.
// ─────────────────────────────────────────────────────────────

extern "C" JNIEXPORT jboolean JNICALL
Java_com_msaidizi_app_voice_VadEngine_nativeProcessAudio(
        JNIEnv* env, jobject /* thiz */,
        jlong handle, jfloatArray jAudioData) {

#if !HAVE_SHERPA
    // Stub: return true (speech) for non-silent audio
    return JNI_TRUE;
#else
    VadHandle* vh = get_vad(handle);
    if (!vh || !vh->valid) {
        LOGW("nativeProcessAudio: invalid handle %lld", (long long)handle);
        return JNI_FALSE;
    }

    std::lock_guard<std::mutex> lock(vh->mu);

    jsize len = env->GetArrayLength(jAudioData);
    if (len <= 0) return JNI_FALSE;

    jfloat* samples = env->GetFloatArrayElements(jAudioData, nullptr);
    if (!samples) return JNI_FALSE;

    // Feed audio to VAD
    SherpaOnnxVoiceActivityDetectorAcceptWaveform(vh->vad, samples, len);
    env->ReleaseFloatArrayElements(jAudioData, samples, JNI_ABORT);

    // Check if speech segment is ready
    int is_speech = 0;
    while (!SherpaOnnxVoiceActivityDetectorEmpty(vh->vad)) {
        const SherpaOnnxSpeechSegment* segment =
            SherpaOnnxVoiceActivityDetectorFront(vh->vad);
        if (segment) {
            is_speech = 1;
            SherpaOnnxSpeechSegmentDestroy(segment);
        }
        SherpaOnnxVoiceActivityDetectorPop(vh->vad);
    }

    // Also check the "is_speech" flag for real-time state
    if (!is_speech) {
        is_speech = SherpaOnnxVoiceActivityDetectorIsSpeech(vh->vad) ? 1 : 0;
    }

    vh->speech_detected = (is_speech != 0);
    return is_speech ? JNI_TRUE : JNI_FALSE;
#endif
}

// ─────────────────────────────────────────────────────────────
// JNI: nativeIsSpeech — current VAD state without feeding audio
// ─────────────────────────────────────────────────────────────

extern "C" JNIEXPORT jboolean JNICALL
Java_com_msaidizi_app_voice_VadEngine_nativeIsSpeech(
        JNIEnv* /* env */, jobject /* thiz */,
        jlong handle) {

#if !HAVE_SHERPA
    return JNI_FALSE;
#else
    VadHandle* vh = get_vad(handle);
    if (!vh || !vh->valid) return JNI_FALSE;

    std::lock_guard<std::mutex> lock(vh->mu);
    return SherpaOnnxVoiceActivityDetectorIsSpeech(vh->vad) ? JNI_TRUE : JNI_FALSE;
#endif
}

// ─────────────────────────────────────────────────────────────
// JNI: nativeReset
// ─────────────────────────────────────────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_com_msaidizi_app_voice_VadEngine_nativeReset(
        JNIEnv* /* env */, jobject /* thiz */,
        jlong handle) {

#if !HAVE_SHERPA
    return;
#else
    VadHandle* vh = get_vad(handle);
    if (!vh || !vh->valid) return;

    std::lock_guard<std::mutex> lock(vh->mu);
    SherpaOnnxVoiceActivityDetectorReset(vh->vad);
    vh->speech_detected = false;
    LOGI("VAD reset — handle=%lld", (long long)handle);
#endif
}

// ─────────────────────────────────────────────────────────────
// JNI: nativeDestroyVad
// ─────────────────────────────────────────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_com_msaidizi_app_voice_VadEngine_nativeDestroyVad(
        JNIEnv* /* env */, jobject /* thiz */,
        jlong handle) {

    VadHandle* vh = get_vad(handle);
    if (vh) {
        LOGI("Destroying VAD handle=%lld", (long long)handle);
        del_vad(handle);
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
    LOGI("vad_jni loaded — HAVE_SHERPA=%d", HAVE_SHERPA);
    return JNI_VERSION_1_6;
}
