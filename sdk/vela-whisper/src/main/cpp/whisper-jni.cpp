#include <jni.h>
#include <string>
#include "whisper.h"

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Throw a Java RuntimeException with the given message.
 * After this call the JNI function MUST return immediately; the exception is
 * delivered when control returns to the JVM.
 */
static void throwJavaException(JNIEnv *env, const char *msg) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls != nullptr) {
        env->ThrowNew(cls, msg);
    }
    // If FindClass itself failed a NoClassDefFoundError is already pending.
}

extern "C" {

// ---------------------------------------------------------------------------
// nativeInit
// ---------------------------------------------------------------------------
JNIEXPORT jlong JNICALL
Java_com_velavoice_sdk_whisper_WhisperEngine_nativeInit(JNIEnv *env, jobject thiz, jstring model_path) {
    // NULL check: GetStringUTFChars returns NULL on OOM and posts a pending
    // exception.  Passing NULL to whisper_init_from_file → fopen(NULL) → SIGSEGV.
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    if (path == nullptr) {
        // A pending OutOfMemoryError is already set by the JVM.
        return 0;
    }

    struct whisper_context *ctx = nullptr;
    try {
        ctx = whisper_init_from_file(path);
    } catch (const std::exception &e) {
        env->ReleaseStringUTFChars(model_path, path);
        throwJavaException(env, e.what());
        return 0;
    } catch (...) {
        env->ReleaseStringUTFChars(model_path, path);
        throwJavaException(env, "Unknown C++ exception in whisper_init_from_file");
        return 0;
    }

    env->ReleaseStringUTFChars(model_path, path);
    // ctx == nullptr when the model file is corrupt / truncated — the Kotlin
    // layer checks for 0 and throws RuntimeException.
    return reinterpret_cast<jlong>(ctx);
}

// ---------------------------------------------------------------------------
// nativeFree
// ---------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_velavoice_sdk_whisper_WhisperEngine_nativeFree(JNIEnv *env, jobject thiz, jlong context_ptr) {
    if (context_ptr != 0) {
        struct whisper_context *ctx = reinterpret_cast<struct whisper_context *>(context_ptr);
        try {
            whisper_free(ctx);
        } catch (...) {
            // Best-effort: swallow so the JVM process stays alive.
        }
    }
}

// ---------------------------------------------------------------------------
// nativeTranscribe
// ---------------------------------------------------------------------------
JNIEXPORT jstring JNICALL
Java_com_velavoice_sdk_whisper_WhisperEngine_nativeTranscribe(JNIEnv *env, jobject thiz, jlong context_ptr, jfloatArray audio_data, jstring language, jint threads, jstring initial_prompt) {
    if (context_ptr == 0) {
        throwJavaException(env, "Whisper context is null (already freed or never initialised)");
        return nullptr;
    }

    struct whisper_context *ctx = reinterpret_cast<struct whisper_context *>(context_ptr);

    // NULL check: GetFloatArrayElements returns NULL on OOM and posts a
    // pending exception.  Dereferencing it in whisper_full → SIGSEGV.
    jfloat *audio = env->GetFloatArrayElements(audio_data, nullptr);
    if (audio == nullptr) {
        // A pending OutOfMemoryError is already set by the JVM.
        return nullptr;
    }
    jsize len = env->GetArrayLength(audio_data);

    const char *lang = nullptr;
    if (language != nullptr) {
        lang = env->GetStringUTFChars(language, nullptr);
        if (lang == nullptr) {
            // OOM — pending exception set.  Clean up audio before returning.
            env->ReleaseFloatArrayElements(audio_data, audio, JNI_ABORT);
            return nullptr;
        }
    }

    const char *prompt = nullptr;
    if (initial_prompt != nullptr) {
        prompt = env->GetStringUTFChars(initial_prompt, nullptr);
        if (prompt == nullptr) {
            // OOM — clean up everything acquired so far.
            if (lang != nullptr) {
                env->ReleaseStringUTFChars(language, lang);
            }
            env->ReleaseFloatArrayElements(audio_data, audio, JNI_ABORT);
            return nullptr;
        }
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = lang != nullptr ? lang : "en";
    params.n_threads = threads > 0 ? threads : 4;
    params.initial_prompt = prompt;

    int full_result = -1;
    try {
        full_result = whisper_full(ctx, params, audio, len);
    } catch (const std::exception &e) {
        // Release JNI resources before throwing.
        if (lang != nullptr)  env->ReleaseStringUTFChars(language, lang);
        if (prompt != nullptr) env->ReleaseStringUTFChars(initial_prompt, prompt);
        env->ReleaseFloatArrayElements(audio_data, audio, JNI_ABORT);
        throwJavaException(env, e.what());
        return nullptr;
    } catch (...) {
        if (lang != nullptr)  env->ReleaseStringUTFChars(language, lang);
        if (prompt != nullptr) env->ReleaseStringUTFChars(initial_prompt, prompt);
        env->ReleaseFloatArrayElements(audio_data, audio, JNI_ABORT);
        throwJavaException(env, "Unknown C++ exception in whisper_full");
        return nullptr;
    }

    // Release borrowed string buffers (still needed by params above, so
    // released *after* whisper_full returns).
    if (lang != nullptr) {
        env->ReleaseStringUTFChars(language, lang);
    }
    if (prompt != nullptr) {
        env->ReleaseStringUTFChars(initial_prompt, prompt);
    }

    if (full_result != 0) {
        env->ReleaseFloatArrayElements(audio_data, audio, JNI_ABORT);
        // Surface the error code to the Kotlin layer instead of silently
        // returning an empty string.
        char msg[128];
        snprintf(msg, sizeof(msg),
                 "whisper_full failed with error code %d", full_result);
        throwJavaException(env, msg);
        return nullptr;
    }

    std::string result;
    int n_segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < n_segments; ++i) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        result += text;
    }

    env->ReleaseFloatArrayElements(audio_data, audio, JNI_ABORT);
    return env->NewStringUTF(result.c_str());
}

}
