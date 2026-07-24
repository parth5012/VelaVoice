#include <jni.h>
#include <string>
#include "whisper.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_velavoice_app_WhisperEngine_nativeInit(JNIEnv *env, jobject thiz, jstring model_path) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    struct whisper_context *ctx = whisper_init_from_file(path);
    env->ReleaseStringUTFChars(model_path, path);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_velavoice_app_WhisperEngine_nativeFree(JNIEnv *env, jobject thiz, jlong context_ptr) {
    if (context_ptr != 0) {
        struct whisper_context *ctx = reinterpret_cast<struct whisper_context *>(context_ptr);
        whisper_free(ctx);
    }
}

JNIEXPORT jstring JNICALL
Java_com_velavoice_app_WhisperEngine_nativeTranscribe(JNIEnv *env, jobject thiz, jlong context_ptr, jfloatArray audio_data) {
    if (context_ptr == 0) {
        return env->NewStringUTF("");
    }

    struct whisper_context *ctx = reinterpret_cast<struct whisper_context *>(context_ptr);

    jfloat *audio = env->GetFloatArrayElements(audio_data, nullptr);
    jsize len = env->GetArrayLength(audio_data);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = "en";
    params.n_threads = 4;

    if (whisper_full(ctx, params, audio, len) != 0) {
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
