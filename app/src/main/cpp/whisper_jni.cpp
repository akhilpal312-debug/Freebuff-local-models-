// JNI bridge between Kotlin (dev.akhil.freebuff.voice.stt.WhisperNative) and whisper.cpp.
//
// Audio arrives as 16 kHz mono float samples in [-1, 1]; the Kotlin recorder
// produces exactly that from AudioRecord PCM16.

#include <jni.h>
#include <android/log.h>
#include <mutex>
#include <string>
#include <vector>

#include "whisper.h"

#define LOG_TAG "FreebuffWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
std::string g_last_error;

void set_error(const std::string &message) {
    g_last_error = message;
    LOGE("%s", message.c_str());
}

std::string jstring_to_string(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}
}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_dev_akhil_freebuff_voice_stt_WhisperNative_nativeLastError(JNIEnv *env, jclass) {
    return env->NewStringUTF(g_last_error.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_akhil_freebuff_voice_stt_WhisperNative_nativeInit(JNIEnv *env, jclass, jstring path) {
    const std::string model_path = jstring_to_string(env, path);
    if (model_path.empty()) {
        set_error("Empty whisper model path.");
        return 0;
    }
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(model_path.c_str(), params);
    if (ctx == nullptr) {
        set_error("whisper_init_from_file_with_params failed for " + model_path);
        return 0;
    }
    LOGI("Loaded whisper model %s", model_path.c_str());
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_akhil_freebuff_voice_stt_WhisperNative_nativeFree(JNIEnv *, jclass, jlong handle) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx) whisper_free(ctx);
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_akhil_freebuff_voice_stt_WhisperNative_nativeTranscribe(
    JNIEnv *env, jclass, jlong handle, jfloatArray samples, jstring language, jint threads) {

    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr) {
        set_error("Speech model is not loaded.");
        return env->NewStringUTF("");
    }
    const jsize count = env->GetArrayLength(samples);
    if (count < 1600) {  // under 100 ms: nothing to transcribe
        return env->NewStringUTF("");
    }
    std::vector<float> pcm(static_cast<size_t>(count));
    env->GetFloatArrayRegion(samples, 0, count, pcm.data());

    const std::string lang = jstring_to_string(env, language);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.n_threads = threads > 0 ? threads : 2;
    params.no_context = true;
    params.single_segment = false;
    params.temperature = 0.0f;
    params.suppress_blank = true;
    // Auto-detect keeps Hinglish usable; pinning a language biases decoding.
    params.language = (lang == "auto" || lang.empty()) ? nullptr : lang.c_str();
    params.detect_language = (lang == "auto" || lang.empty());

    if (whisper_full(ctx, params, pcm.data(), static_cast<int>(pcm.size())) != 0) {
        set_error("whisper_full failed.");
        return env->NewStringUTF("");
    }

    std::string transcript;
    const int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; ++i) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        if (text != nullptr) transcript += text;
    }
    while (!transcript.empty() && (transcript.front() == ' ')) transcript.erase(transcript.begin());
    return env->NewStringUTF(transcript.c_str());
}
