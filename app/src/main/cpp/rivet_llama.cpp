// Small JNI surface over the pinned llama.cpp C API. Kotlin owns prompts,
// streaming, tool parsing, and lifecycle; this file only loads one model and
// runs one CPU generation at a time.
#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
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

    ~RivetModel() {
        if (ctx) llama_free(ctx);
        if (model) llama_model_free(model);
    }
};

std::mutex g_handles_mutex;
std::unordered_map<jlong, std::shared_ptr<RivetModel>> g_live_handles;

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

bool isContinuationByte(unsigned char b) {
    return (b & 0xC0) == 0x80;
}

int utf8ExpectedLen(unsigned char lead) {
    if (lead < 0x80) return 1;
    if (lead >= 0xC2 && lead <= 0xDF) return 2;
    if (lead >= 0xE0 && lead <= 0xEF) return 3;
    if (lead >= 0xF0 && lead <= 0xF4) return 4;
    return -1;
}

std::string consumeCompleteUtf8(std::string& buffer) {
    if (buffer.empty()) return {};
    size_t i = 0;
    size_t safe_end = 0;
    const size_t n = buffer.size();
    while (i < n) {
        const unsigned char b0 = static_cast<unsigned char>(buffer[i]);
        const int len = utf8ExpectedLen(b0);
        if (len < 0) {
            i += 1;
            safe_end = i;
            continue;
        }
        if (i + static_cast<size_t>(len) > n) {
            bool all_cont = true;
            for (size_t k = i + 1; k < n; ++k) {
                if (!isContinuationByte(static_cast<unsigned char>(buffer[k]))) {
                    all_cont = false;
                    break;
                }
            }
            if (all_cont) break;  // incomplete sequence split across tokens
            i += 1;
            safe_end = i;
            continue;
        }
        i += static_cast<size_t>(len);
        safe_end = i;
    }
    std::string out = buffer.substr(0, safe_end);
    buffer.erase(0, safe_end);
    return out;
}

jstring toJString(JNIEnv* env, const std::string& value) {
    if (value.empty()) {
        const jchar empty = 0;
        return env->NewString(&empty, 0);
    }
    std::vector<jchar> utf16;
    utf16.reserve(value.size());
    const size_t n = value.size();
    size_t i = 0;
    while (i < n) {
        const unsigned char b0 = static_cast<unsigned char>(value[i]);
        if (b0 < 0x80) {
            utf16.push_back(static_cast<jchar>(b0));
            i += 1;
            continue;
        }
        const int len = utf8ExpectedLen(b0);
        if (len < 2 || i + static_cast<size_t>(len) > n) {
            utf16.push_back(0xFFFD);
            i += 1;
            continue;
        }
        bool ok = true;
        for (int k = 1; k < len; ++k) {
            if (!isContinuationByte(static_cast<unsigned char>(value[i + k]))) {
                ok = false;
                break;
            }
        }
        if (!ok) {
            utf16.push_back(0xFFFD);
            i += 1;
            continue;
        }
        uint32_t cp = 0;
        if (len == 2) {
            cp = ((b0 & 0x1Fu) << 6) | (static_cast<unsigned char>(value[i + 1]) & 0x3Fu);
        } else if (len == 3) {
            cp = ((b0 & 0x0Fu) << 12) |
                 ((static_cast<unsigned char>(value[i + 1]) & 0x3Fu) << 6) |
                 (static_cast<unsigned char>(value[i + 2]) & 0x3Fu);
            if (cp < 0x800 || (cp >= 0xD800 && cp <= 0xDFFF)) cp = 0xFFFD;
        } else {
            cp = ((b0 & 0x07u) << 18) |
                 ((static_cast<unsigned char>(value[i + 1]) & 0x3Fu) << 12) |
                 ((static_cast<unsigned char>(value[i + 2]) & 0x3Fu) << 6) |
                 (static_cast<unsigned char>(value[i + 3]) & 0x3Fu);
            if (cp < 0x10000 || cp > 0x10FFFF) cp = 0xFFFD;
        }
        if (cp <= 0xFFFF) {
            utf16.push_back(static_cast<jchar>(cp));
        } else {
            cp -= 0x10000;
            utf16.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
            utf16.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
        }
        i += static_cast<size_t>(len);
    }
    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

std::shared_ptr<RivetModel> acquireHandle(jlong handle) {
    if (handle == 0) return nullptr;
    std::lock_guard<std::mutex> lock(g_handles_mutex);
    const auto it = g_live_handles.find(handle);
    if (it == g_live_handles.end()) return nullptr;
    return it->second;
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
    auto state = std::make_shared<RivetModel>();
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    state->model = llama_model_load_from_file(file.c_str(), model_params);
    if (!state->model) {
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
        return 0;
    }
    const jlong handle = static_cast<jlong>(reinterpret_cast<intptr_t>(state.get()));
    {
        std::lock_guard<std::mutex> lock(g_handles_mutex);
        g_live_handles.emplace(handle, state);
    }
    return handle;
}

extern "C" JNIEXPORT void JNICALL
Java_com_kaiser_rivet_local_LlamaBridge_nativeFreeModel(JNIEnv*, jobject, jlong handle) {
    std::shared_ptr<RivetModel> state;
    {
        std::lock_guard<std::mutex> lock(g_handles_mutex);
        const auto it = g_live_handles.find(handle);
        if (it == g_live_handles.end()) return;
        state = std::move(it->second);
        g_live_handles.erase(it);
        if (state) state->stop = true;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_kaiser_rivet_local_LlamaBridge_nativeStop(JNIEnv*, jobject, jlong handle) {
    std::shared_ptr<RivetModel> state = acquireHandle(handle);
    if (state) state->stop = true;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_kaiser_rivet_local_LlamaBridge_nativeChatTemplate(JNIEnv* env, jobject, jlong handle) {
    std::shared_ptr<RivetModel> state = acquireHandle(handle);
    if (!state || !state->model) return toJString(env, "");
    std::lock_guard<std::mutex> lock(state->mutex);
    const char* tmpl = llama_model_chat_template(state->model, nullptr);
    return toJString(env, tmpl ? tmpl : "");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_kaiser_rivet_local_LlamaBridge_nativeApplyTemplate(
    JNIEnv* env, jobject, jlong handle, jobjectArray roles, jobjectArray contents,
    jboolean add_assistant) {
    std::shared_ptr<RivetModel> state = acquireHandle(handle);
    if (!state || !state->model || !roles || !contents) return toJString(env, "");
    std::lock_guard<std::mutex> lock(state->mutex);
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
    return toJString(env, std::string(buffer.data(), static_cast<size_t>(written)));
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_kaiser_rivet_local_LlamaBridge_nativeTokenize(
    JNIEnv* env, jobject, jlong handle, jstring text, jboolean add_special) {
    std::shared_ptr<RivetModel> state = acquireHandle(handle);
    if (!state || !state->vocab || !text) return env->NewIntArray(0);
    std::lock_guard<std::mutex> lock(state->mutex);
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
    std::shared_ptr<RivetModel> state = acquireHandle(handle);
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
    tokens.resize(static_cast<size_t>(n_tokens));
    const int reserve_gen = std::min(64, std::max(1, state->n_ctx / 8));
    const int max_prompt = std::max(1, state->n_ctx - reserve_gen);
    if (n_tokens > max_prompt) {
        // Keep the leading system/prefix tokens and the most recent context tail
        // while preserving KV headroom for at least `reserve_gen` generated tokens.
        const int head = std::max(1, max_prompt / 4);
        const int tail = std::max(0, max_prompt - head);
        std::vector<llama_token> trimmed;
        trimmed.reserve(static_cast<size_t>(head + tail));
        trimmed.insert(trimmed.end(), tokens.begin(), tokens.begin() + head);
        trimmed.insert(trimmed.end(), tokens.end() - tail, tokens.end());
        tokens = std::move(trimmed);
        n_tokens = static_cast<int>(tokens.size());
    }
    if (!decodePrompt(state.get(), tokens)) return toJString(env, "");

    llama_sampler_chain_params sampler_params = llama_sampler_chain_default_params();
    llama_sampler* sampler = llama_sampler_chain_init(sampler_params);
    if (!grammar_text.empty()) {
        llama_sampler* grammar_sampler =
            llama_sampler_init_grammar(state->vocab, grammar_text.c_str(), "root");
        if (grammar_sampler) {
            llama_sampler_chain_add(sampler, grammar_sampler);
        } else {
            LOGE("Failed to initialize grammar sampler; ignoring invalid grammar");
        }
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
    std::string pending_utf8;
    bool callback_threw = false;
    const int limit = max_tokens > 0 ? max_tokens : 512;
    for (int i = 0; i < limit && !state->stop; ++i) {
        const llama_token token = llama_sampler_sample(sampler, state->ctx, -1);
        llama_sampler_accept(sampler, token);
        if (llama_vocab_is_eog(state->vocab, token)) break;
        std::string piece_buf(512, '\0');
        int piece_len = llama_token_to_piece(state->vocab, token, piece_buf.data(),
                                             static_cast<int>(piece_buf.size()), 0, false);
        if (piece_len < 0) {
            piece_buf.resize(static_cast<size_t>(-piece_len));
            piece_len = llama_token_to_piece(state->vocab, token, piece_buf.data(),
                                                 static_cast<int>(piece_buf.size()), 0, false);
        }
        if (piece_len > 0) {
            const std::string text(piece_buf.data(), static_cast<size_t>(piece_len));
            result += text;
            if (on_token && callback) {
                pending_utf8 += text;
                const std::string complete = consumeCompleteUtf8(pending_utf8);
                if (!complete.empty()) {
                    jstring value = toJString(env, complete);
                    if (env->ExceptionCheck()) {
                        callback_threw = true;
                        break;
                    }
                    env->CallVoidMethod(callback, on_token, value);
                    env->DeleteLocalRef(value);
                    if (env->ExceptionCheck()) {
                        callback_threw = true;
                        break;
                    }
                }
            }
        }
        llama_batch batch = llama_batch_get_one(const_cast<llama_token*>(&token), 1);
        if (llama_decode(state->ctx, batch) != 0) break;
    }
    if (!callback_threw && !pending_utf8.empty() && on_token && callback) {
        jstring tail = toJString(env, pending_utf8);
        if (env->ExceptionCheck()) {
            callback_threw = true;
        } else {
            env->CallVoidMethod(callback, on_token, tail);
            env->DeleteLocalRef(tail);
            if (env->ExceptionCheck()) callback_threw = true;
        }
    }
    if (callback_class) env->DeleteLocalRef(callback_class);
    llama_sampler_free(sampler);
    if (callback_threw) return nullptr;
    return toJString(env, result);
}
