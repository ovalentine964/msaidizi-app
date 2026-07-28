/**
 * Msaidizi — CMake build for native components.
 *
 * Links:
 *   - llama.cpp  (on-device LLM inference)
 *   - sherpa-onnx (on-device ASR / TTS / VAD)
 *
 * Produces three shared libraries loaded via JNI:
 *   libllama_jni.so   — LLM bridge
 *   libsherpa_jni.so  — ASR / TTS bridge
 *   libvad_jni.so     — Voice Activity Detection bridge
 */

cmake_minimum_required(VERSION 3.22.1)
project("msaidizi_native" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

# ── Logging helper ───────────────────────────────────────────
# All JNI libs link against Android log
set(ANDROID_LOG_LIB log)

# ─────────────────────────────────────────────────────────────
# 1) llama.cpp
# ─────────────────────────────────────────────────────────────
# We expect llama.cpp to be built as a static library and placed
# (or symlinked) under third_party/llama.cpp/ before running cmake.
#
# Directory layout:
#   third_party/llama.cpp/
#     ├── include/          (llama.h, ggml.h, …)
#     ├── lib/              (libllama.a per ABI)
#     └── CMakeLists.txt    (optional — for source build)
#
# For CI/source builds we add_subdirectory; for pre-built we use
# IMPORTED targets.

set(LLAMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/llama.cpp")

if(EXISTS "${LLAMA_DIR}/CMakeLists.txt")
    # ── Source build ──────────────────────────────────────────
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(LLAMA_METAL OFF CACHE BOOL "" FORCE)      # Android — no Metal
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)       # Avoid OMP issues on some NDKs
    add_subdirectory("${LLAMA_DIR}" llama_build EXCLUDE_FROM_ALL)
    set(LLAMA_INCLUDE_DIR "${LLAMA_DIR}/include")
    set(LLAMA_LINK_LIBS llama)
    message(STATUS "llama.cpp: building from source")
elseif(EXISTS "${LLAMA_DIR}/include/llama.h")
    # ── Pre-built static library ─────────────────────────────
    set(LLAMA_INCLUDE_DIR "${LLAMA_DIR}/include")
    add_library(llama STATIC IMPORTED)
    set_target_properties(llama PROPERTIES
        IMPORTED_LOCATION "${LLAMA_DIR}/lib/${ANDROID_ABI}/libllama.a"
    )
    set(LLAMA_LINK_LIBS llama)
    message(STATUS "llama.cpp: using pre-built library for ${ANDROID_ABI}")
else()
    message(WARNING "llama.cpp not found at ${LLAMA_DIR} — LLM inference will be stub-only")
    set(LLAMA_INCLUDE_DIR "")
    set(LLAMA_LINK_LIBS "")
endif()

# ─────────────────────────────────────────────────────────────
# 2) sherpa-onnx
# ─────────────────────────────────────────────────────────────
set(SHERPA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/sherpa-onnx")

if(EXISTS "${SHERPA_DIR}/CMakeLists.txt")
    # ── Source build ──────────────────────────────────────────
    set(SHERPA_ENABLE_PYTHON OFF CACHE BOOL "" FORCE)
    set(SHERPA_ENABLE_C_API ON CACHE BOOL "" FORCE)
    set(SHERPA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(SHERPA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    add_subdirectory("${SHERPA_DIR}" sherpa_build EXCLUDE_FROM_ALL)
    set(SHERPA_INCLUDE_DIR "${SHERPA_DIR}/sherpa-onnx/c-api")
    set(SHERPA_LINK_LIBS sherpa-onnx-c-api)
    message(STATUS "sherpa-onnx: building from source")
elseif(EXISTS "${SHERPA_DIR}/include/sherpa-onnx/c-api/c-api.h")
    # ── Pre-built shared library ─────────────────────────────
    set(SHERPA_INCLUDE_DIR "${SHERPA_DIR}/include")
    add_library(sherpa-onnx-c-api SHARED IMPORTED)
    set_target_properties(sherpa-onnx-c-api PROPERTIES
        IMPORTED_LOCATION "${SHERPA_DIR}/lib/${ANDROID_ABI}/libsherpa-onnx-c-api.so"
    )
    set(SHERPA_LINK_LIBS sherpa-onnx-c-api)
    message(STATUS "sherpa-onnx: using pre-built library for ${ANDROID_ABI}")
else()
    message(WARNING "sherpa-onnx not found at ${SHERPA_DIR} — ASR/TTS will be stub-only")
    set(SHERPA_INCLUDE_DIR "")
    set(SHERPA_LINK_LIBS "")
endif()

# ─────────────────────────────────────────────────────────────
# 3) JNI bridge libraries
# ─────────────────────────────────────────────────────────────

# --- llama_jni ------------------------------------------------
add_library(llama_jni SHARED
    llama_jni.cpp
)
target_include_directories(llama_jni PRIVATE
    ${LLAMA_INCLUDE_DIR}
)
target_link_libraries(llama_jni
    ${LLAMA_LINK_LIBS}
    ${ANDROID_LOG_LIB}
    android          # for ANativeWindow, AAssetManager if needed
)

# --- sherpa_jni -----------------------------------------------
add_library(sherpa_jni SHARED
    sherpa_jni.cpp
)
target_include_directories(sherpa_jni PRIVATE
    ${SHERPA_INCLUDE_DIR}
)
target_link_libraries(sherpa_jni
    ${SHERPA_LINK_LIBS}
    ${ANDROID_LOG_LIB}
)

# --- vad_jni --------------------------------------------------
add_library(vad_jni SHARED
    vad_jni.cpp
)
target_include_directories(vad_jni PRIVATE
    ${SHERPA_INCLUDE_DIR}
)
target_link_libraries(vad_jni
    ${SHERPA_LINK_LIBS}
    ${ANDROID_LOG_LIB}
)

# ── Compiler flags ───────────────────────────────────────────
foreach(TARGET llama_jni sherpa_jni vad_jni)
    target_compile_options(${TARGET} PRIVATE
        -Wall
        -Wextra
        -Wpedantic
        -Wno-unused-parameter
        -fvisibility=hidden          # hide non-JNI symbols
    )
    # Release optimisations
    target_compile_options(${TARGET} PRIVATE
        $<$<CONFIG:Release>:-O2 -DNDEBUG>
        $<$<CONFIG:Debug>:-O0 -g>
    )
endforeach()

message(STATUS "Msaidizi native — ABI=${ANDROID_ABI}, API=${ANDROID_PLATFORM}")
