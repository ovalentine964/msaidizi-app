// Stub JNI library for 32-bit ARM devices.
// llama.cpp requires ARMv8 NEON FP16 intrinsics (vld1q_f16, vld1_f16)
// which don't exist on 32-bit ARM. 32-bit devices use cloud LLM fallback.
//
// See: DeviceCapability.is32BitDevice() — routes to cloud API instead.

#include <jni.h>

// Empty JNI_OnLoad — just return success
jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)vm;
    (void)reserved;
    return JNI_VERSION_1_6;
}
