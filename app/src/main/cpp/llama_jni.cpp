// JNI bridge between Kotlin (dev.akhil.freebuff.inference.LlamaNative) and llama.cpp.
//
// One context per handle, one generation at a time. The Kotlin side enforces the
// single-model invariant; this file additionally refuses to start a second
// generation on the same handle.
//
// NOTE: llama.cpp's C API moves between releases. This file targets the API of
// the tag pinned in scripts/fetch-native.sh. If you change that tag, re-check the
// symbols below (llama_model_load_from_file, llama_model_get_vocab,
// llama_sampler_chain_init) against the new llama.h.

#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "FreebuffLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Session {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    int n_ctx = 0;
    std::atomic<bool> stop{false};
    std::atomic<bool> busy{false};
    std::mutex mutex;
    std::string last_error;
};

std::string g_last_error;

Session *as_session(jlong handle) {
    return reinterpret_cast<Session *>(handle);
}

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

jstring string_to_jstring(JNIEnv *env, const std::string &value) {
    return env->NewStringUTF(value.c_str());
}

struct CallbackContext {
    JNIEnv *env = nullptr;
    jobject sink = nullptr;
    jmethodID method = nullptr;
    bool keep_going = true;
};

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_dev_akhil_freebuff_inference_LlamaNative_nativeBackendInfo(JNIEnv *env, jclass) {
    return string_to_jstring(env, "llama.cpp " + std::string(llama_print_system_info()));
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_akhil_freebuff_inference_LlamaNative_nativeLastError(JNIEnv *env, jclass) {
    return string_to_jstring(env, g_last_error);
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_akhil_freebuff_inference_LlamaNative_nativeLoadModel(
    JNIEnv *env, jclass, jstring path, jint context_tokens, jint threads, jboolean use_mmap) {

    const std::string model_path = jstring_to_string(env, path);
    if (model_path.empty()) {
        set_error("Empty model path.");
        return 0;
    }

    llama_backend_init();
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    // Newer llama.cpp replaced the use_mmap/use_mlock booleans with load_mode.
    // mmap keeps RSS low on a 2 GB phone; mlock would pin the file and OOM us.
    model_params.load_mode = use_mmap == JNI_TRUE ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;

    llama_model *model = llama_model_load_from_file(model_path.c_str(), model_params);
    if (model == nullptr) {
        set_error("llama_model_load_from_file failed for " + model_path);
        return 0;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = static_cast<uint32_t>(context_tokens > 0 ? context_tokens : 2048);
    ctx_params.n_batch = 256;
    ctx_params.n_threads = threads > 0 ? threads : 2;
    ctx_params.n_threads_batch = ctx_params.n_threads;

    llama_context *ctx = llama_init_from_model(model, ctx_params);
    if (ctx == nullptr) {
        llama_model_free(model);
        set_error("llama_init_from_model failed (out of memory?)");
        return 0;
    }

    auto *session = new Session();
    session->model = model;
    session->ctx = ctx;
    session->vocab = llama_model_get_vocab(model);
    session->n_ctx = static_cast<int>(ctx_params.n_ctx);
    LOGI("Loaded %s (ctx=%d, threads=%d)", model_path.c_str(), session->n_ctx, ctx_params.n_threads);
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_akhil_freebuff_inference_LlamaNative_nativeFreeModel(JNIEnv *, jclass, jlong handle) {
    Session *session = as_session(handle);
    if (session == nullptr) return;
    session->stop.store(true);
    if (session->ctx) llama_free(session->ctx);
    if (session->model) llama_model_free(session->model);
    delete session;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_akhil_freebuff_inference_LlamaNative_nativeStop(JNIEnv *, jclass, jlong handle) {
    Session *session = as_session(handle);
    if (session) session->stop.store(true);
}

static jint generate_impl(
    JNIEnv *env,
    Session *session,
    const std::string &prompt,
    jint max_tokens,
    jfloat temperature,
    jfloat top_p,
    jint top_k,
    jint seed,
    CallbackContext *callback) {

    if (!session->busy.exchange(true)) {
        // acquired
    } else {
        set_error("A generation is already running on this model.");
        return -1;
    }
    struct BusyGuard {
        Session *s;
        ~BusyGuard() { s->busy.store(false); }
    } guard{session};

    session->stop.store(false);

    const llama_vocab *vocab = session->vocab;
    const bool add_special = true;
    const bool parse_special = true;

    int n_tokens = -llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                                   nullptr, 0, add_special, parse_special);
    if (n_tokens <= 0) {
        set_error("Tokenisation failed.");
        return -1;
    }
    std::vector<llama_token> tokens(n_tokens);
    if (llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                       tokens.data(), n_tokens, add_special, parse_special) < 0) {
        set_error("Tokenisation failed (buffer too small).");
        return -1;
    }

    // Keep the prompt inside the context window, keeping the newest tokens.
    const int budget = session->n_ctx - static_cast<int>(max_tokens) - 8;
    if (budget <= 0) {
        set_error("Context window is too small for the requested reply length.");
        return -1;
    }
    if (static_cast<int>(tokens.size()) > budget) {
        tokens.erase(tokens.begin(), tokens.end() - budget);
    }

    llama_batch batch = llama_batch_init(static_cast<int32_t>(tokens.size()), 0, 1);
    for (size_t i = 0; i < tokens.size(); ++i) {
        batch.token[i] = tokens[i];
        batch.pos[i] = static_cast<llama_pos>(i);
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = (i == tokens.size() - 1) ? 1 : 0;
    }
    batch.n_tokens = static_cast<int32_t>(tokens.size());

    if (llama_decode(session->ctx, batch) != 0) {
        llama_batch_free(batch);
        set_error("Prefill decode failed.");
        return -1;
    }
    llama_batch_free(batch);

    llama_sampler_chain_params sampler_params = llama_sampler_chain_default_params();
    sampler_params.no_perf = true;
    llama_sampler *smpl = llama_sampler_chain_init(sampler_params);
    if (temperature <= 0.01f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
#ifdef LLAMA_DEFAULT_SEED
        const uint32_t seed_value = seed < 0 ? LLAMA_DEFAULT_SEED : static_cast<uint32_t>(seed);
#else
        const uint32_t seed_value = seed < 0 ? 0xFFFFFFFFu : static_cast<uint32_t>(seed);
#endif
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(top_k > 0 ? top_k : 40));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p > 0 ? top_p : 0.95f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(seed_value));
    }

    int generated = 0;
    int n_past = static_cast<int>(tokens.size());
    char piece[256];

    for (int i = 0; i < max_tokens; ++i) {
        if (session->stop.load()) break;

        llama_token next = llama_sampler_sample(smpl, session->ctx, -1);
        if (llama_vocab_is_eog(vocab, next)) break;
        llama_sampler_accept(smpl, next);

        int piece_len = llama_token_to_piece(vocab, next, piece, sizeof(piece), 0, true);
        if (piece_len > 0) {
            std::string text(piece, piece_len);
            jstring emitted = env->NewStringUTF(text.c_str());
            jboolean keep = env->CallBooleanMethod(callback->sink, callback->method, emitted);
            env->DeleteLocalRef(emitted);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                break;
            }
            if (keep == JNI_FALSE) break;
        }
        ++generated;

        llama_batch step = llama_batch_get_one(&next, 1);
        if (llama_decode(session->ctx, step) != 0) {
            set_error("Decode failed during generation.");
            llama_sampler_free(smpl);
            return -1;
        }
        ++n_past;
        if (n_past >= session->n_ctx - 4) break;
    }

    llama_sampler_free(smpl);
    return generated;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_akhil_freebuff_inference_LlamaNative_nativeGenerate(
    JNIEnv *env, jclass, jlong handle, jstring prompt, jint max_tokens,
    jfloat temperature, jfloat top_p, jint top_k, jint seed, jobject callback) {

    Session *session = as_session(handle);
    if (session == nullptr) {
        set_error("No model is loaded.");
        return -1;
    }
    if (callback == nullptr) {
        set_error("No token callback supplied.");
        return -1;
    }
    jclass sink_class = env->GetObjectClass(callback);
    jmethodID on_token = env->GetMethodID(sink_class, "onToken", "(Ljava/lang/String;)Z");
    if (on_token == nullptr) {
        set_error("TokenSink.onToken(String)Z not found.");
        return -1;
    }
    CallbackContext ctx{env, callback, on_token, true};
    const std::string prompt_text = jstring_to_string(env, prompt);
    return generate_impl(env, session, prompt_text, max_tokens, temperature, top_p, top_k, seed, &ctx);
}
