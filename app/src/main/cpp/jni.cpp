#include <jni.h>
#include <android/log.h>
#include "whisper.h"

#define LOG_TAG "WhisperJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ── JNI function names must match the Kotlin class hierarchy exactly ────────────
//
//  Kotlin:  com.example.jibberish.managers.WhisperCppService (outer class)
//                └── private object WhisperLib              (nested object → '$' → _00024)
//
//  JNI prefix: Java_com_example_jibberish_managers_WhisperCppService_00024WhisperLib_
//
// ───────────────────────────────────────────────────────────────────────────────

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_jibberish_managers_WhisperCppService_00024WhisperLib_initContext(
        JNIEnv *env, jobject /*thiz*/, jstring model_path_str) {

    const char *model_path = env->GetStringUTFChars(model_path_str, nullptr);
    LOGD("Loading whisper model: %s", model_path);

    whisper_context *ctx = whisper_init_from_file(model_path);

    env->ReleaseStringUTFChars(model_path_str, model_path);

    if (!ctx) {
        LOGE("whisper_init_from_file returned null — check model file integrity");
        return 0L;
    }
    LOGD("Whisper model loaded OK, ctx=%p", ctx);
    return (jlong)(intptr_t)ctx;
}

JNIEXPORT void JNICALL
Java_com_example_jibberish_managers_WhisperCppService_00024WhisperLib_freeContext(
        JNIEnv *env, jobject /*thiz*/, jlong context_ptr) {

    auto *ctx = reinterpret_cast<whisper_context *>(context_ptr);
    if (ctx) {
        whisper_free(ctx);
        LOGD("Whisper context freed");
    }
}

JNIEXPORT jint JNICALL
Java_com_example_jibberish_managers_WhisperCppService_00024WhisperLib_fullTranscribe(
        JNIEnv *env, jobject /*thiz*/, jlong context_ptr,
        jint num_threads, jfloatArray audio_data) {

    auto *ctx = reinterpret_cast<whisper_context *>(context_ptr);
    if (!ctx) {
        LOGE("fullTranscribe: null context");
        return -1;
    }

    jsize   n_samples = env->GetArrayLength(audio_data);
    jfloat *samples   = env->GetFloatArrayElements(audio_data, nullptr);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads        = num_threads;
    params.language         = "en";
    params.print_progress   = false;
    params.print_special    = false;
    params.print_realtime   = false;
    params.print_timestamps = false;
    params.translate        = false;

    LOGD("Transcribing %d samples on %d threads", (int)n_samples, num_threads);

    int rc = whisper_full(ctx, params, samples, (int)n_samples);
    if (rc != 0) {
        LOGE("whisper_full failed: %d", rc);
    } else {
        LOGD("Transcription done: %d segment(s)", whisper_full_n_segments(ctx));
    }

    env->ReleaseFloatArrayElements(audio_data, samples, JNI_ABORT);
    return (jint)rc;
}

JNIEXPORT jint JNICALL
Java_com_example_jibberish_managers_WhisperCppService_00024WhisperLib_getTextSegmentCount(
        JNIEnv *env, jobject /*thiz*/, jlong context_ptr) {

    auto *ctx = reinterpret_cast<whisper_context *>(context_ptr);
    return ctx ? (jint)whisper_full_n_segments(ctx) : 0;
}

JNIEXPORT jstring JNICALL
Java_com_example_jibberish_managers_WhisperCppService_00024WhisperLib_getTextSegment(
        JNIEnv *env, jobject /*thiz*/, jlong context_ptr, jint index) {

    auto *ctx = reinterpret_cast<whisper_context *>(context_ptr);
    if (!ctx) return env->NewStringUTF("");

    const char *text = whisper_full_get_segment_text(ctx, (int)index);
    return env->NewStringUTF(text ? text : "");
}

} // extern "C"
