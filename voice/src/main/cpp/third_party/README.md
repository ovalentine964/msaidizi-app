# third_party — Native Dependencies

This directory holds the native libraries required by Msaidizi's JNI bridge.

## Required Libraries

### 1. llama.cpp (LLM inference)

**Option A — Source build (recommended for CI):**
```
git clone --depth 1 https://github.com/ggerganov/llama.cpp.git
```

**Option B — Pre-built static library:**
```
third_party/llama.cpp/
  include/       # llama.h, ggml.h, …
  lib/
    arm64-v8a/libllama.a
    armeabi-v7a/libllama.a
```

### 2. sherpa-onnx (ASR / TTS / VAD)

**Option A — Source build:**
```
git clone --depth 1 https://github.com/k2-fsa/sherpa-onnx.git
```

**Option B — Pre-built shared library:**
```
third_party/sherpa-onnx/
  include/
    sherpa-onnx/c-api/c-api.h
  lib/
    arm64-v8a/libsherpa-onnx-c-api.so
    armeabi-v7a/libsherpa-onnx-c-api.so
```

## Stub Mode

If neither library is present, the build will:
1. Print a CMake warning
2. Compile JNI bridge files in **stub mode** (no-op implementations)
3. The app will compile and install, but AI inference (LLM, ASR, TTS) will return placeholder results

This is useful for UI development and testing without large model files.
