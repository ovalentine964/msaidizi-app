// Stub JNI library for 32-bit ARM devices.
// llama.cpp requires ARMv8 NEON FP16 intrinsics (vld1q_f16, vld1_f16)
// which don't exist on 32-bit ARM. 32-bit devices use cloud LLM fallback.
//
// This stub exports all JNI methods so the app never crashes with
// UnsatisfiedLinkError — instead, methods return safe defaults (0L / "")
// and log clear error messages.
//
// See: DeviceCapability.is32BitDevice() — routes to cloud API instead.

#include <jni.h>
#include <android/log.h>

#define LOG_TAG "llama_jni_stub"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// JNI_OnLoad — return success so the library loads without error
jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)vm;
    (void)reserved;
    __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
        "llama_jni loaded as STUB (32-bit armeabi-v7a). "
        "On-device LLM inference is not available on this device. "
        "The app will use cloud-based inference instead.");
    return JNI_VERSION_1_6;
}

// Stub: nativeLoadModel — return 0 (failure) with clear error
extern "C"
JNIEXPORT jlong JNICALL
Java_com_msaidizi_app_voice_LlamaCppEngine_nativeLoadModel(
        JNIEnv *env, jobject /* thiz */,
        jstring /* jPath */, jint /* nCtx */,
        jint /* nThreads */, jboolean /* useKvCacheQ4 */) {
    LOGE("nativeLoadModel called on 32-bit stub — "
         "llama.cpp requires 64-bit ARM (arm64-v8a). "
         "On-device LLM is not supported on this device. "
         "Use DeviceCapability.is32BitDevice() to check before calling.");
    return 0;
}

// Stub: nativeGenerate — return empty string
extern "C"
JNIEXPORT jstring JNICALL
Java_com_msaidizi_app_voice_LlamaCppEngine_nativeGenerate(
        JNIEnv *env, jobject /* thiz */,
        jlong /* handle */, jstring /* jPrompt */,
        jint /* maxTokens */, jfloat /* temperature */) {
    LOGE("nativeGenerate called on 32-bit stub — "
         "llama.cpp requires 64-bit ARM (arm64-v8a). "
         "Returning empty response.");
    return env->NewStringUTF("");
}

// Stub: nativeFreeModel — no-op
extern "C"
JNIEXPORT void JNICALL
Java_com_msaidizi_app_voice_LlamaCppEngine_nativeFreeModel(
        JNIEnv *env, jobject /* thiz */,
        jlong /* handle */) {
    // No-op: nothing to free on 32-bit stub
}
