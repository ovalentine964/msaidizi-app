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
# Fetch llama.cpp from GitHub (with local fallback)
#
# First build requires internet to download llama.cpp source. The source is
# cached by CMake's FetchContent in the build directory (~/.gradle/caches/...)
# so subsequent builds work offline.
#
# If the download fails (no internet, corporate firewall), you can:
#   1. Place llama.cpp source in app/src/main/cpp/vendor/llama.cpp/
#      (the CMakeLists.txt will use it automatically as fallback)
#   2. Or run: ./scripts/setup-llama-cpp.sh  (pre-downloads the source)
# ─────────────────────────────────────────────────────────────────────────────

include(FetchContent)

# Pin to a known stable tag — update manually after testing
set(LLAMA_CPP_TAG "b4651")

# Check for a local vendored copy first (fallback for offline builds)
set(LLAMA_CPP_LOCAL_DIR "${CMAKE_CURRENT_SOURCE_DIR}/vendor/llama.cpp")
if(EXISTS "${LLAMA_CPP_LOCAL_DIR}/CMakeLists.txt")
    message(STATUS "Using local vendored llama.cpp from ${LLAMA_CPP_LOCAL_DIR}")
    set(llama_cpp_SOURCE_DIR "${LLAMA_CPP_LOCAL_DIR}")
    # Disable features we don't need on Android
    # NOTE: Backend options use GGML_* prefix (not LLAMA_*) since llama.cpp b4000+
    set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
    set(GGML_METAL           OFF CACHE BOOL "" FORCE)  # No Metal on Android
    set(GGML_CUDA            OFF CACHE BOOL "" FORCE)  # No CUDA on Android
    set(GGML_VULKAN          OFF CACHE BOOL "" FORCE)  # Vulkan can be added later
    set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)  # Avoid OpenMP dependency
    add_subdirectory("${LLAMA_CPP_LOCAL_DIR}" llama_cpp_build)
else()
    message(STATUS "Downloading llama.cpp ${LLAMA_CPP_TAG} from GitHub...")
    FetchContent_Declare(
        llama_cpp
        GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
        GIT_TAG        ${LLAMA_CPP_TAG}
        GIT_SHALLOW    TRUE
        # Timeout after 120s — prevents hanging CI builds
        GIT_PROGRESS   TRUE
        TIMEOUT        120
    )

    # Disable llama.cpp features we don't need on Android
    # NOTE: Backend options use GGML_* prefix (not LLAMA_*) since llama.cpp b4000+
    set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
    set(GGML_METAL           OFF CACHE BOOL "" FORCE)  # No Metal on Android
    set(GGML_CUDA            OFF CACHE BOOL "" FORCE)  # No CUDA on Android (for now)
    set(GGML_VULKAN          OFF CACHE BOOL "" FORCE)  # Vulkan support can be added later
    set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)  # Avoid OpenMP dependency

    FetchContent_MakeAvailable(llama_cpp)
endif()

# ─────────────────────────────────────────────────────────────────────────────
# JNI bridge library
# ─────────────────────────────────────────────────────────────────────────────

add_library(llama_jni SHARED
    llama_jni.cpp
)

# Include llama.cpp headers
# Note: most include paths are propagated transitively via the llama and ggml targets.
# We only need to explicitly add paths not covered by the target dependencies.
target_include_directories(llama_jni PRIVATE
    ${llama_cpp_SOURCE_DIR}/include
    ${llama_cpp_SOURCE_DIR}/ggml/include
)

# Link against llama.cpp (transitively brings in ggml and its backends)
target_link_libraries(llama_jni
    llama
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
