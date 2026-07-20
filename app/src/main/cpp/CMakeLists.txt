cmake_minimum_required(VERSION 3.22)

project(llama_jni C CXX)

# ─────────────────────────────────────────────────────────────────────────────
# Skip native build for 32-bit ARM — llama.cpp requires ARMv8 NEON FP16
# 32-bit devices use cloud LLM fallback (see DeviceCapability.is32BitDevice())
# ─────────────────────────────────────────────────────────────────────────────
if(${ANDROID_ABI} STREQUAL "armeabi-v7a")
    # Create a minimal stub library so the build doesn't fail
    add_library(llama_jni SHARED llama_jni_stub.cpp)
    return()
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ─────────────────────────────────────────────────────────────────────────────
# Fetch llama.cpp from GitHub
# ─────────────────────────────────────────────────────────────────────────────

include(FetchContent)

# Pin to a known stable tag — update manually after testing
set(LLAMA_CPP_TAG "b4651")
FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
    GIT_TAG        ${LLAMA_CPP_TAG}
    GIT_SHALLOW    TRUE
)

# Disable llama.cpp features we don't need on Android
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_METAL          OFF CACHE BOOL "" FORCE)  # No Metal on Android
set(LLAMA_CUDA           OFF CACHE BOOL "" FORCE)  # No CUDA on Android (for now)
set(LLAMA_VULKAN         OFF CACHE BOOL "" FORCE)  # Vulkan support can be added later
set(LLAMA_OPENMP         OFF CACHE BOOL "" FORCE)  # Avoid OpenMP dependency

FetchContent_MakeAvailable(llama_cpp)

# ─────────────────────────────────────────────────────────────────────────────
# JNI bridge library
# ─────────────────────────────────────────────────────────────────────────────

add_library(llama_jni SHARED
    llama_jni.cpp
)

# Include llama.cpp headers
target_include_directories(llama_jni PRIVATE
    ${llama_cpp_SOURCE_DIR}/include
    ${llama_cpp_SOURCE_DIR}/ggml/include
    ${llama_cpp_SOURCE_DIR}/src
)

# Link against llama.cpp and ggml
target_link_libraries(llama_jni
    llama
    ggml
    android     # For ANativeWindow, AAssetManager, etc.
    log         # For __android_log_print
)

# Optimize for ARM
if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    target_compile_options(llama_jni PRIVATE
        -O3
        -ffunction-sections
        -fdata-sections
        -ffast-math
    )
elseif(${ANDROID_ABI} STREQUAL "armeabi-v7a")
    target_compile_options(llama_jni PRIVATE
        -O2
        -ffunction-sections
        -fdata-sections
        -ffast-math
        -mfpu=neon
        -mfloat-abi=softfp
    )
endif()

# Strip symbols in release for smaller binary
target_link_options(llama_jni PRIVATE
    -Wl,--gc-sections
    -Wl,--strip-all
)
