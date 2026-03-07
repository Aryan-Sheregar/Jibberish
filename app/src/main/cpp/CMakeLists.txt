cmake_minimum_required(VERSION 3.22.1)
project("jibberish-whisper")

# ── Disable all whisper.cpp/ggml features not available on Android ─────────────
set(GGML_METAL          OFF CACHE BOOL "" FORCE)
set(GGML_CUDA           OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL         OFF CACHE BOOL "" FORCE)
set(GGML_BLAS           OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP         OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE         OFF CACHE BOOL "" FORCE)   # No host-CPU auto-detection when cross-compiling
set(GGML_LLAMAFILE      OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE) # whisper = static, we wrap it in whisperjni.so

# ── Fetch whisper.cpp v1.7.4 source archive from GitHub ────────────────────────
include(FetchContent)
FetchContent_Declare(
    whisper_cpp
    URL https://github.com/ggerganov/whisper.cpp/archive/refs/tags/v1.7.4.tar.gz
)
FetchContent_MakeAvailable(whisper_cpp)

# ── Our JNI shared library — loaded by WhisperCppService via System.loadLibrary ─
add_library(whisperjni SHARED jni.cpp)

# Required for Android 15+ / Google Play: LOAD segments must be 16 KB page-aligned
target_link_options(whisperjni PRIVATE "-Wl,-z,max-page-size=16384")

# whisper target exposes its include dirs as PUBLIC so they propagate to us
target_link_libraries(whisperjni
    PRIVATE
    whisper
    android
    log
)
