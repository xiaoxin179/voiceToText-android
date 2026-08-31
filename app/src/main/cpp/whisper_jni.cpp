#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>

#include "ggml-backend.h"
#include "whisper.h"

namespace {

constexpr const char *kLogTag = "voiceToText-whisper";

struct NativeWhisperContext {
    whisper_context *context = nullptr;
    std::string backend = "CPU";
};

std::string available_gpu_backend_name() {
    for (size_t index = 0; index < ggml_backend_dev_count(); ++index) {
        ggml_backend_dev_t device = ggml_backend_dev_get(index);
        const enum ggml_backend_dev_type type = ggml_backend_dev_type(device);
        if (type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU) {
            ggml_backend_t probe = ggml_backend_dev_init(device, nullptr);
            if (probe == nullptr) continue;
            ggml_backend_free(probe);
            const char *description = ggml_backend_dev_description(device);
            return std::string("Vulkan · ") + (description == nullptr ? "GPU" : description);
        }
    }
    return {};
}

void load_accelerated_backend() {
#ifdef VTT_VULKAN_ENABLED
    static std::once_flag once;
    std::call_once(once, [] {
        if (ggml_backend_load("libggml-vulkan.so") == nullptr) {
            __android_log_print(ANDROID_LOG_WARN, kLogTag, "Vulkan backend is unavailable");
        }
    });
#endif
}

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

    load_accelerated_backend();
    std::string backend = available_gpu_backend_name();
    whisper_context_params context_params = whisper_context_default_params();
    context_params.use_gpu = !backend.empty();
    context_params.flash_attn = context_params.use_gpu;
    auto *context = whisper_init_from_file_with_params(path.c_str(), context_params);
    if (context == nullptr && !backend.empty()) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "Vulkan model init failed; retrying on CPU");
        context_params.use_gpu = false;
        context_params.flash_attn = false;
        context = whisper_init_from_file_with_params(path.c_str(), context_params);
        backend = "CPU 回退";
    }
    if (context == nullptr) {
        throw_illegal_state(env, "Whisper model could not be loaded");
        return 0;
    }

    auto *native_context = new NativeWhisperContext();
    native_context->context = context;
    native_context->backend = backend.empty() ? "CPU 回退" : backend;
    __android_log_print(
            ANDROID_LOG_INFO,
            kLogTag,
            "loaded whisper model: %s, backend: %s",
            path.c_str(),
            native_context->backend.c_str());
    return reinterpret_cast<jlong>(native_context);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_xiaoxin_voicetotext_android_asr_WhisperNative_nativeGetBackend(
        JNIEnv *env,
        jclass,
        jlong handle) {
    auto *native_context = reinterpret_cast<NativeWhisperContext *>(handle);
    if (native_context == nullptr) return env->NewStringUTF("未知");
    return env->NewStringUTF(native_context->backend.c_str());
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
    const int hardware_threads = std::max(1, static_cast<int>(std::thread::hardware_concurrency()));
    const int available_threads = hardware_threads > 2 ? hardware_threads - 2 : hardware_threads;
    params.n_threads = std::max(1, std::min(6, available_threads));

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
