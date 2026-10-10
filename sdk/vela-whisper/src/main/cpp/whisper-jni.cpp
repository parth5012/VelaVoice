#include <jni.h>
#include <string>
#include <atomic>
#include <thread>
#include <algorithm>
#include <cstring>
#include "whisper.h"

// ---------------------------------------------------------------------------
// Structs & Helpers
// ---------------------------------------------------------------------------

struct WhisperContextWrapper {
    struct whisper_context *ctx = nullptr;
    std::atomic<bool> aborted{false};
};

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

/**
 * Throw a Java IllegalArgumentException with the given message.
 */
static void throwIllegalArgumentException(JNIEnv *env, const char *msg) {
    jclass cls = env->FindClass("java/lang/IllegalArgumentException");
    if (cls != nullptr) {
        env->ThrowNew(cls, msg);
    }
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
    if (ctx == nullptr) {
        return 0;
    }

    WhisperContextWrapper *wrapper = new WhisperContextWrapper();
    wrapper->ctx = ctx;
    wrapper->aborted.store(false, std::memory_order_relaxed);
    return reinterpret_cast<jlong>(wrapper);
}

// ---------------------------------------------------------------------------
// nativeFree
// ---------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_velavoice_sdk_whisper_WhisperEngine_nativeFree(JNIEnv *env, jobject thiz, jlong context_ptr) {
    if (context_ptr != 0) {
        WhisperContextWrapper *wrapper = reinterpret_cast<WhisperContextWrapper *>(context_ptr);
        try {
            if (wrapper->ctx != nullptr) {
                whisper_free(wrapper->ctx);
            }
        } catch (...) {
            // Best-effort: swallow so the JVM process stays alive.
        }
        delete wrapper;
    }
}

// ---------------------------------------------------------------------------
// nativeCancel
// ---------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_velavoice_sdk_whisper_WhisperEngine_nativeCancel(JNIEnv *env, jobject thiz, jlong context_ptr) {
    if (context_ptr != 0) {
        WhisperContextWrapper *wrapper = reinterpret_cast<WhisperContextWrapper *>(context_ptr);
        wrapper->aborted.store(true, std::memory_order_relaxed);
    }
}

// ---------------------------------------------------------------------------
// nativeTranscribe
// ---------------------------------------------------------------------------
JNIEXPORT jbyteArray JNICALL
Java_com_velavoice_sdk_whisper_WhisperEngine_nativeTranscribe(JNIEnv *env, jobject thiz, jlong context_ptr, jfloatArray audio_data, jstring language, jint threads, jstring initial_prompt) {
    if (context_ptr == 0) {
        throwJavaException(env, "Whisper context is null (already freed or never initialised)");
        return nullptr;
    }

    WhisperContextWrapper *wrapper = reinterpret_cast<WhisperContextWrapper *>(context_ptr);
    if (wrapper->ctx == nullptr) {
        throwJavaException(env, "Whisper context is null (already freed or never initialised)");
        return nullptr;
    }

    // Reset aborted flag for this transcription run
    wrapper->aborted.store(false, std::memory_order_relaxed);

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

    // Language validation: must be >= 0 in whisper_lang_id, or "auto"
    const char *effective_lang = lang != nullptr ? lang : "en";
    if (strcmp(effective_lang, "auto") != 0 && whisper_lang_id(effective_lang) < 0) {
        if (lang != nullptr) {
            env->ReleaseStringUTFChars(language, lang);
        }
        if (prompt != nullptr) {
            env->ReleaseStringUTFChars(initial_prompt, prompt);
        }
        env->ReleaseFloatArrayElements(audio_data, audio, JNI_ABORT);
        char msg[128];
        snprintf(msg, sizeof(msg), "Invalid language: '%s'", effective_lang);
        throwIllegalArgumentException(env, msg);
        return nullptr;
    }

    // Thread clamping: clamp to 1..min(8, hardware_concurrency)
    unsigned int hw = std::thread::hardware_concurrency();
    int max_threads = hw > 0 ? std::min(8, (int)hw) : 4;
    max_threads = std::max(1, max_threads);
    int clamped_threads = std::max(1, std::min((int)threads, max_threads));

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = effective_lang;
    params.n_threads = clamped_threads;
    params.initial_prompt = prompt;

    // Cancellation callback
    params.abort_callback = [](void *user_data) -> bool {
        auto *aborted = static_cast<std::atomic<bool> *>(user_data);
        return aborted != nullptr && aborted->load(std::memory_order_relaxed);
    };
    params.abort_callback_user_data = &wrapper->aborted;

    int full_result = -1;
    try {
        full_result = whisper_full(wrapper->ctx, params, audio, len);
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
        if (wrapper->aborted.load(std::memory_order_relaxed)) {
            // Cancelled - return empty byte array promptly without exception
            jbyteArray empty = env->NewByteArray(0);
            return empty;
        }
        // Surface the error code to the Kotlin layer instead of silently
        // returning an empty string.
        char msg[128];
        snprintf(msg, sizeof(msg),
                 "whisper_full failed with error code %d", full_result);
        throwJavaException(env, msg);
        return nullptr;
    }

    if (wrapper->aborted.load(std::memory_order_relaxed)) {
        env->ReleaseFloatArrayElements(audio_data, audio, JNI_ABORT);
        jbyteArray empty = env->NewByteArray(0);
        return empty;
    }

    std::string result;
    int n_segments = whisper_full_n_segments(wrapper->ctx);
    for (int i = 0; i < n_segments; ++i) {
        const char *text = whisper_full_get_segment_text(wrapper->ctx, i);
        if (text != nullptr) {
            result += text;
        }
    }

    env->ReleaseFloatArrayElements(audio_data, audio, JNI_ABORT);

    jbyteArray byte_array = env->NewByteArray(result.size());
    if (byte_array == nullptr) {
        // Pending OutOfMemoryError already set by JVM
        return nullptr;
    }
    if (!result.empty()) {
        env->SetByteArrayRegion(byte_array, 0, result.size(), reinterpret_cast<const jbyte *>(result.data()));
    }
    return byte_array;
}

}
