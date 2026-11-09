// Small JNI surface over the pinned llama.cpp C API. Kotlin owns prompts,
// streaming, tool parsing, and lifecycle; this file only loads one model and
// runs one CPU generation at a time.
#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "RivetLlama"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
struct RivetModel {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    const llama_vocab* vocab = nullptr;
    int n_ctx = 0;
    std::mutex mutex;
    std::atomic<bool> stop{false};
};

std::once_flag backend_once;

void ensureBackend() {
    std::call_once(backend_once, [] { llama_backend_init(); });
}

std::string toString(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

jstring toJString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

RivetModel* fromHandle(jlong handle) {
    return reinterpret_cast<RivetModel*>(static_cast<intptr_t>(handle));
}

bool decodePrompt(RivetModel* state, const std::vector<llama_token>& tokens) {
    const int n_batch = 512;
    llama_batch batch = llama_batch_init(n_batch, 0, 1);
    if (!batch.token || !batch.pos || !batch.n_seq_id || !batch.seq_id || !batch.logits) {
        llama_batch_free(batch);
        return false;
    }
    bool ok = true;
    const int total = static_cast<int>(tokens.size());
    for (int start = 0; start < total && ok; start += n_batch) {
        const int count = std::min(n_batch, total - start);
        for (int i = 0; i < count; ++i) {
            const int position = start + i;
            batch.token[i] = tokens[position];
            batch.pos[i] = position;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i] = position == total - 1 ? 1 : 0;
        }
        batch.n_tokens = count;
        if (llama_decode(state->ctx, batch) != 0) ok = false;
    }
    llama_batch_free(batch);
    return ok;
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_kaiser_rivet_local_LlamaBridge_nativeLoadModel(
    JNIEnv* env, jobject, jstring path, jint n_ctx, jint n_threads) {
    ensureBackend();
    const std::string file = toString(env, path);
    if (file.empty() || n_ctx < 512) return 0;
    auto* state = new RivetModel();
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    state->model = llama_model_load_from_file(file.c_str(), model_params);
    if (!state->model) {
        delete state;
        return 0;
    }
    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = static_cast<uint32_t>(n_ctx);
    context_params.n_batch = static_cast<uint32_t>(std::min(n_ctx, 512));
    context_params.n_threads = static_cast<uint32_t>(n_threads > 0 ? n_threads : 4);
    context_params.n_threads_batch = context_params.n_threads;
    state->ctx = llama_init_from_model(state->model, context_params);
    state->vocab = llama_model_get_vocab(state->model);
    state->n_ctx = n_ctx;
    if (!state->ctx || !state->vocab) {
        if (state->ctx) llama_free(state->ctx);
        llama_model_free(state->model);
        delete state;
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(state));
}

extern "C" JNIEXPORT void JNICALL
Java_com_kaiser_rivet_local_LlamaBridge_nativeFreeModel(JNIEnv*, jobject, jlong handle) {
    RivetModel* state = fromHandle(handle);
    if (!state) return;
    state->stop = true;
    std::lock_guard<std::mutex> lock(state->mutex);
    if (state->ctx) llama_free(state->ctx);
    if (state->model) llama_model_free(state->model);
    delete state;
}

extern "C" JNIEXPORT void JNICALL
Java_com_kaiser_rivet_local_LlamaBridge_nativeStop(JNIEnv*, jobject, jlong handle) {
    RivetModel* state = fromHandle(handle);
    if (state) state->stop = true;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_kaiser_rivet_local_LlamaBridge_nativeChatTemplate(JNIEnv* env, jobject, jlong handle) {
    RivetModel* state = fromHandle(handle);
    if (!state || !state->model) return toJString(env, "");
    const char* tmpl = llama_model_chat_template(state->model, nullptr);
    return toJString(env, tmpl ? tmpl : "");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_kaiser_rivet_local_LlamaBridge_nativeApplyTemplate(
    JNIEnv* env, jobject, jlong handle, jobjectArray roles, jobjectArray contents,
    jboolean add_assistant) {
    RivetModel* state = fromHandle(handle);
    if (!state || !state->model || !roles || !contents) return toJString(env, "");
    const jsize count = env->GetArrayLength(roles);
    if (count != env->GetArrayLength(contents)) return toJString(env, "");
    const char* tmpl = llama_model_chat_template(state->model, nullptr);
    if (!tmpl) return toJString(env, "");

    std::vector<std::string> role_storage;
    std::vector<std::string> content_storage;
    role_storage.reserve(count);
    content_storage.reserve(count);
    std::vector<llama_chat_message> messages;
    messages.reserve(count);
    for (jsize i = 0; i < count; ++i) {
        auto role = (jstring) env->GetObjectArrayElement(roles, i);
        auto content = (jstring) env->GetObjectArrayElement(contents, i);
        role_storage.push_back(toString(env, role));
        content_storage.push_back(toString(env, content));
        env->DeleteLocalRef(role);
        env->DeleteLocalRef(content);
    }
    for (jsize i = 0; i < count; ++i) {
        messages.push_back({role_storage[i].c_str(), content_storage[i].c_str()});
    }
    int needed = llama_chat_apply_template(tmpl, messages.data(), messages.size(),
                                           add_assistant == JNI_TRUE, nullptr, 0);
    if (needed <= 0) return toJString(env, "");
    std::vector<char> buffer(static_cast<size_t>(needed) + 1);
    int written = llama_chat_apply_template(tmpl, messages.data(), messages.size(),
                                            add_assistant == JNI_TRUE, buffer.data(),
                                            static_cast<int>(buffer.size()));
    if (written <= 0) return toJString(env, "");
    return env->NewStringUTF(std::string(buffer.data(), static_cast<size_t>(written)).c_str());
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_kaiser_rivet_local_LlamaBridge_nativeTokenize(
    JNIEnv* env, jobject, jlong handle, jstring text, jboolean add_special) {
    RivetModel* state = fromHandle(handle);
    if (!state || !state->vocab || !text) return env->NewIntArray(0);
    const std::string value = toString(env, text);
    std::vector<llama_token> tokens(value.size() + 32);
    int count = llama_tokenize(state->vocab, value.data(), static_cast<int>(value.size()),
                               tokens.data(), static_cast<int>(tokens.size()),
                               add_special == JNI_TRUE, true);
    if (count < 0) {
        tokens.resize(static_cast<size_t>(-count));
        count = llama_tokenize(state->vocab, value.data(), static_cast<int>(value.size()),
                               tokens.data(), static_cast<int>(tokens.size()),
                               add_special == JNI_TRUE, true);
    }
    if (count <= 0) return env->NewIntArray(0);
    jintArray result = env->NewIntArray(count);
    if (result) env->SetIntArrayRegion(result, 0, count, tokens.data());
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_kaiser_rivet_local_LlamaBridge_nativeGenerate(
    JNIEnv* env, jobject, jlong handle, jstring prompt, jint max_tokens,
    jfloat temperature, jfloat top_p, jstring grammar, jobject callback) {
    RivetModel* state = fromHandle(handle);
    if (!state || !state->ctx || !state->vocab) return toJString(env, "");
    std::lock_guard<std::mutex> lock(state->mutex);
    state->stop = false;
    const std::string prompt_text = toString(env, prompt);
    const std::string grammar_text = toString(env, grammar);
    if (prompt_text.empty()) return toJString(env, "");

    llama_memory_t memory = llama_get_memory(state->ctx);
    if (memory) llama_memory_clear(memory, true);

    std::vector<llama_token> tokens(prompt_text.size() + 32);
    int n_tokens = llama_tokenize(state->vocab, prompt_text.data(),
                                  static_cast<int>(prompt_text.size()), tokens.data(),
                                  static_cast<int>(tokens.size()), true, true);
    if (n_tokens < 0) {
        tokens.resize(static_cast<size_t>(-n_tokens));
        n_tokens = llama_tokenize(state->vocab, prompt_text.data(),
                                  static_cast<int>(prompt_text.size()), tokens.data(),
                                  static_cast<int>(tokens.size()), true, true);
    }
    if (n_tokens <= 0) return toJString(env, "");
    if (n_tokens > state->n_ctx) {
        tokens.erase(tokens.begin(), tokens.end() - state->n_ctx);
        n_tokens = state->n_ctx;
    }
    tokens.resize(n_tokens);
    if (!decodePrompt(state, tokens)) return toJString(env, "");

    llama_sampler_chain_params sampler_params = llama_sampler_chain_default_params();
    llama_sampler* sampler = llama_sampler_chain_init(sampler_params);
    if (!grammar_text.empty()) {
        llama_sampler_chain_add(sampler,
            llama_sampler_init_grammar(state->vocab, grammar_text.c_str(), "root"));
    }
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(top_p, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    jclass callback_class = callback ? env->GetObjectClass(callback) : nullptr;
    jmethodID on_token = callback_class
        ? env->GetMethodID(callback_class, "onToken", "(Ljava/lang/String;)V") : nullptr;

    std::string result;
    const int limit = max_tokens > 0 ? max_tokens : 512;
    for (int i = 0; i < limit && !state->stop; ++i) {
        const llama_token token = llama_sampler_sample(sampler, state->ctx, -1);
        llama_sampler_accept(sampler, token);
        if (llama_vocab_is_eog(state->vocab, token)) break;
        char piece[512];
        int piece_len = llama_token_to_piece(state->vocab, token, piece,
                                             static_cast<int>(sizeof(piece)), 0, false);
        if (piece_len <= 0) continue;
        const std::string text(piece, static_cast<size_t>(piece_len));
        result += text;
        if (on_token && callback) {
            jstring value = env->NewStringUTF(text.c_str());
            env->CallVoidMethod(callback, on_token, value);
            env->DeleteLocalRef(value);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                break;
            }
        }
        llama_batch batch = llama_batch_get_one(const_cast<llama_token*>(&token), 1);
        if (llama_decode(state->ctx, batch) != 0) break;
    }
    if (callback_class) env->DeleteLocalRef(callback_class);
    llama_sampler_free(sampler);
    return toJString(env, result);
}
