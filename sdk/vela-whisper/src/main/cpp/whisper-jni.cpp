#include <jni.h>
#include <string>
#include "whisper.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_velavoice_sdk_whisper_WhisperEngine_nativeInit(JNIEnv *env, jobject thiz, jstring model_path) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    struct whisper_context *ctx = whisper_init_from_file(path);
    env->ReleaseStringUTFChars(model_path, path);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_velavoice_sdk_whisper_WhisperEngine_nativeFree(JNIEnv *env, jobject thiz, jlong context_ptr) {
    if (context_ptr != 0) {
        struct whisper_context *ctx = reinterpret_cast<struct whisper_context *>(context_ptr);
        whisper_free(ctx);
    }
}

JNIEXPORT jstring JNICALL
Java_com_velavoice_sdk_whisper_WhisperEngine_nativeTranscribe(JNIEnv *env, jobject thiz, jlong context_ptr, jfloatArray audio_data, jstring language, jint threads, jstring initial_prompt) {
    if (context_ptr == 0) {
        return env->NewStringUTF("");
    }

    struct whisper_context *ctx = reinterpret_cast<struct whisper_context *>(context_ptr);

    jfloat *audio = env->GetFloatArrayElements(audio_data, nullptr);
    jsize len = env->GetArrayLength(audio_data);

    const char *lang = language != nullptr ? env->GetStringUTFChars(language, nullptr) : nullptr;
    const char *prompt = initial_prompt != nullptr ? env->GetStringUTFChars(initial_prompt, nullptr) : nullptr;

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = lang != nullptr ? lang : "en";
    params.n_threads = threads > 0 ? threads : 4;
    params.initial_prompt = prompt;

    const int full_result = whisper_full(ctx, params, audio, len);
    if (lang != nullptr) {
        env->ReleaseStringUTFChars(language, lang);
    }
    if (prompt != nullptr) {
        env->ReleaseStringUTFChars(initial_prompt, prompt);
    }
    if (full_result != 0) {
        env->ReleaseFloatArrayElements(audio_data, audio, JNI_ABORT);
        return env->NewStringUTF("");
    }

    std::string result = "";
    int n_segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < n_segments; ++i) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        result += text;
    }

    env->ReleaseFloatArrayElements(audio_data, audio, JNI_ABORT);
    return env->NewStringUTF(result.c_str());
}

}
