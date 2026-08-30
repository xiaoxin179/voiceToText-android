#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <string>
#include <thread>

#include "whisper.h"

namespace {

constexpr const char *kLogTag = "voiceToText-whisper";

struct NativeWhisperContext {
    whisper_context *context = nullptr;
};

std::string to_utf8(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

void throw_illegal_state(JNIEnv *env, const char *message) {
    jclass exception = env->FindClass("java/lang/IllegalStateException");
    if (exception != nullptr) env->ThrowNew(exception, message);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_xiaoxin_voicetotext_android_asr_WhisperNative_nativeInit(
        JNIEnv *env,
        jclass,
        jstring model_path) {
    const std::string path = to_utf8(env, model_path);
    if (path.empty()) {
        throw_illegal_state(env, "Whisper model path is empty");
        return 0;
    }

    whisper_context_params context_params = whisper_context_default_params();
    context_params.use_gpu = false;
    auto *context = whisper_init_from_file_with_params(path.c_str(), context_params);
    if (context == nullptr) {
        throw_illegal_state(env, "Whisper model could not be loaded");
        return 0;
    }

    auto *native_context = new NativeWhisperContext();
    native_context->context = context;
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "loaded whisper model: %s", path.c_str());
    return reinterpret_cast<jlong>(native_context);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_xiaoxin_voicetotext_android_asr_WhisperNative_nativeTranscribe(
        JNIEnv *env,
        jclass,
        jlong handle,
        jfloatArray samples_array,
        jstring language) {
    auto *native_context = reinterpret_cast<NativeWhisperContext *>(handle);
    if (native_context == nullptr || native_context->context == nullptr) {
        throw_illegal_state(env, "Whisper context is not initialized");
        return nullptr;
    }
    if (samples_array == nullptr) {
        throw_illegal_state(env, "Whisper audio samples are missing");
        return nullptr;
    }

    const jsize sample_count = env->GetArrayLength(samples_array);
    if (sample_count <= 0) return env->NewStringUTF("");
    jfloat *samples = env->GetFloatArrayElements(samples_array, nullptr);
    if (samples == nullptr) return nullptr;

    std::string language_value = to_utf8(env, language);
    if (language_value.empty() || language_value == "auto") language_value = "zh";

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.no_timestamps = true;
    params.single_segment = true;
    params.no_context = true;
    params.language = language_value.c_str();
    params.detect_language = false;
    params.translate = false;
    params.temperature = 0.0f;
    params.suppress_blank = true;
    params.suppress_nst = true;
    params.n_threads = std::max(1, std::min(8, static_cast<int>(std::thread::hardware_concurrency())));

    const int status = whisper_full(
            native_context->context,
            params,
            samples,
            static_cast<int>(sample_count));
    env->ReleaseFloatArrayElements(samples_array, samples, JNI_ABORT);
    if (status != 0) {
        throw_illegal_state(env, "Whisper inference failed");
        return nullptr;
    }

    std::string text;
    const int segment_count = whisper_full_n_segments(native_context->context);
    for (int index = 0; index < segment_count; ++index) {
        const char *segment = whisper_full_get_segment_text(native_context->context, index);
        if (segment != nullptr) text += segment;
    }
    return env->NewStringUTF(text.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_xiaoxin_voicetotext_android_asr_WhisperNative_nativeFree(
        JNIEnv *,
        jclass,
        jlong handle) {
    auto *native_context = reinterpret_cast<NativeWhisperContext *>(handle);
    if (native_context == nullptr) return;
    if (native_context->context != nullptr) whisper_free(native_context->context);
    delete native_context;
}
