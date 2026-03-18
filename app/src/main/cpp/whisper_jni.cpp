/**
 * whisper_jni.cpp — Puente JNI para whisper.cpp v1.4.0
 *
 * API de v1.4.0 — diferente a versiones más recientes:
 *   - whisper_init_from_file()         (no whisper_init_from_file_with_params)
 *   - whisper_full_default_params()    (igual)
 *   - whisper_full()                   (igual)
 *   - whisper_free()                   (igual)
 *   - NO existe whisper_context_params ni whisper_context_default_params
 */

#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "whisper.h"

#define LOG_TAG "WHISPER_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

static std::string jstring_to_string(JNIEnv* env, jstring jstr) {
    if (jstr == nullptr) return "";
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return result;
}

extern "C" {

/**
 * whisperInitFromFile — Carga el modelo usando la API de v1.4.0
 */
JNIEXPORT jlong JNICALL
Java_com_example_myapplication_core_WhisperTranscriber_whisperInitFromFile(
        JNIEnv* env,
        jobject,
        jstring modelPath
) {
    std::string path = jstring_to_string(env, modelPath);
    LOGI("Cargando modelo: %s", path.c_str());

    // v1.4.0 usa whisper_init_from_file directamente, sin params
    whisper_context* ctx = whisper_init_from_file(path.c_str());

    if (ctx == nullptr) {
        LOGE("No se pudo cargar el modelo: %s", path.c_str());
        return 0;
    }

    LOGI("Modelo cargado correctamente");
    return reinterpret_cast<jlong>(ctx);
}

/**
 * whisperTranscribe — Transcribe audio usando la API de v1.4.0
 */
JNIEXPORT jstring JNICALL
Java_com_example_myapplication_core_WhisperTranscriber_whisperTranscribe(
        JNIEnv* env,
        jobject,
        jlong ctx,
        jfloatArray audioData,
        jint numSamples,
        jstring language,
        jboolean translate
) {
    whisper_context* whisper_ctx = reinterpret_cast<whisper_context*>(ctx);

    if (whisper_ctx == nullptr) {
        LOGE("Contexto nulo");
        return env->NewStringUTF("");
    }

    jfloat* samples = env->GetFloatArrayElements(audioData, nullptr);
    if (samples == nullptr) {
        LOGE("Array de audio nulo");
        return env->NewStringUTF("");
    }

    // v1.4.0: whisper_full_default_params con WHISPER_SAMPLING_GREEDY
    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);

    std::string lang = jstring_to_string(env, language);
    params.language        = lang.c_str();
    params.translate       = (bool)translate;
    params.print_progress  = false;
    params.print_timestamps = false;
    params.print_realtime  = false;
    params.print_special   = false;
    params.n_threads       = 4;

    LOGD("Transcribiendo %d samples (~%.1fs)...", numSamples, (float)numSamples / 16000.0f);

    int result = whisper_full(whisper_ctx, params, samples, numSamples);

    env->ReleaseFloatArrayElements(audioData, samples, JNI_ABORT);

    if (result != 0) {
        LOGE("whisper_full falló: %d", result);
        return env->NewStringUTF("");
    }

    int n_segments = whisper_full_n_segments(whisper_ctx);
    std::string full_text;

    for (int i = 0; i < n_segments; i++) {
        const char* text = whisper_full_get_segment_text(whisper_ctx, i);
        if (text != nullptr) {
            full_text += text;
            if (i < n_segments - 1 && !full_text.empty() && full_text.back() != ' ') {
                full_text += " ";
            }
        }
    }

    // Trim espacios
    size_t start = full_text.find_first_not_of(" \t\n");
    size_t end   = full_text.find_last_not_of(" \t\n");
    if (start != std::string::npos) {
        full_text = full_text.substr(start, end - start + 1);
    }

    LOGI("Transcripcion: \"%s\"", full_text.c_str());
    return env->NewStringUTF(full_text.c_str());
}

/**
 * whisperFree — Libera el modelo
 */
JNIEXPORT void JNICALL
Java_com_example_myapplication_core_WhisperTranscriber_whisperFree(
        JNIEnv*,
jobject,
jlong ctx
) {
whisper_context* whisper_ctx = reinterpret_cast<whisper_context*>(ctx);
if (whisper_ctx != nullptr) {
whisper_free(whisper_ctx);
LOGI("Modelo liberado");
}
}

} // extern "C"