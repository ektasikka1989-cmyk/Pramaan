// Pramaan on-device Llama runtime: a small JNI bridge over llama.cpp (pinned tag b11799).
//
// What it does:
//   * self-contained: ggml is linked statically and hidden (the app's whisper module has its own)
//   * loads any GGUF chat model (Llama 3.2 3B / 1B Instruct Q4_K_M are the targets)
//   * applies the model's own chat template (falls back to the llama3 template)
//   * reuses the KV cache for the longest shared prompt prefix, so the fixed system prompt and
//     unchanged history are not re-processed on every turn (time-to-first-token drops sharply)
//   * streams UTF-8-safe byte pieces to Kotlin (Devanagari is 3 bytes per character; a token may
//     end in the middle of a character, so incomplete tails are held back)
//   * cancellation from any thread, during prompt processing (abort callback) and generation
//   * reports real timings: prompt tokens, reused tokens, generated tokens, TTFT, total time
//
// Return codes of nativeGenerate (mirrored in LlamaNative.kt):
//    0 eos, 1 max_tokens, 2 cancelled, 3 context_full, 4 stopped_by_client
//   -1 template_failed, -2 tokenize_failed, -3 prompt_too_long, -4 decode_failed, -5 bad_handle

#include <jni.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"
#include "ggml-backend.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOG_TAG "PramaanLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#include <cstdio>
#define LOGI(...) do { fprintf(stderr, "[PramaanLlama] " __VA_ARGS__); fputc('\n', stderr); } while (0)
#define LOGE(...) do { fprintf(stderr, "[PramaanLlama:E] " __VA_ARGS__); fputc('\n', stderr); } while (0)
#endif

namespace {

constexpr int RC_EOS = 0;
constexpr int RC_MAX_TOKENS = 1;
constexpr int RC_CANCELLED = 2;
constexpr int RC_CONTEXT_FULL = 3;
constexpr int RC_STOPPED_BY_CLIENT = 4;
constexpr int RC_TEMPLATE_FAILED = -1;
constexpr int RC_TOKENIZE_FAILED = -2;
constexpr int RC_PROMPT_TOO_LONG = -3;
constexpr int RC_DECODE_FAILED = -4;
constexpr int RC_BAD_HANDLE = -5;

struct Session {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    std::vector<llama_token> cached;  // tokens currently held in the KV cache (sequence 0)
    std::atomic<bool> cancel{false};
    std::mutex generation;            // one generation at a time per session
    int n_ctx = 0;
    int n_batch = 0;
};

std::once_flag g_backend_once;
std::mutex g_error_mutex;
std::string g_last_error;

void set_error(const std::string &message) {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    g_last_error = message;
    LOGE("%s", message.c_str());
}

// llama.cpp's own log, kept so a failed load can say WHY (truncated file, unknown
// architecture, out of memory ...) instead of just "could not load".
std::mutex g_log_mutex;
std::vector<std::string> g_recent_log;

void llama_log_to_logcat(ggml_log_level level, const char *text, void *) {
    if (text == nullptr) return;
    std::string line(text);
    while (!line.empty() && (line.back() == '\n' || line.back() == '\r')) line.pop_back();
    if (line.empty()) return;
    if (level == GGML_LOG_LEVEL_ERROR || level == GGML_LOG_LEVEL_WARN) {
        LOGE("llama: %s", line.c_str());
        std::lock_guard<std::mutex> lock(g_log_mutex);
        g_recent_log.push_back(line);
        if (g_recent_log.size() > 6) g_recent_log.erase(g_recent_log.begin());
    } else if (level == GGML_LOG_LEVEL_INFO) {
        LOGI("llama: %s", line.c_str());
    }
}

std::string recent_llama_errors() {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    std::string out;
    for (const auto &l : g_recent_log) {
        if (!out.empty()) out += " | ";
        out += l;
    }
    return out;
}

void clear_llama_errors() {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    g_recent_log.clear();
}

int64_t now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
               std::chrono::steady_clock::now().time_since_epoch()).count();
}

bool abort_requested(void *data) {
    auto *session = static_cast<Session *>(data);
    return session != nullptr && session->cancel.load();
}

std::string jstring_to_utf8(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    // GetStringUTFChars returns *modified* UTF-8 (wrong for emoji / supplementary characters),
    // so the Java side encodes to real UTF-8 bytes instead.
    jclass string_class = env->GetObjectClass(value);
    jmethodID get_bytes = env->GetMethodID(string_class, "getBytes", "(Ljava/lang/String;)[B");
    jstring charset = env->NewStringUTF("UTF-8");
    auto bytes = (jbyteArray) env->CallObjectMethod(value, get_bytes, charset);
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(string_class);
    if (env->ExceptionCheck() || bytes == nullptr) {
        env->ExceptionClear();
        return {};
    }
    const jsize length = env->GetArrayLength(bytes);
    std::string out(static_cast<size_t>(length), '\0');
    if (length > 0) env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte *>(&out[0]));
    env->DeleteLocalRef(bytes);
    return out;
}

// Length of the longest prefix of [text] that does not end inside a multi-byte UTF-8 character.
size_t utf8_complete_prefix(const std::string &text) {
    const size_t n = text.size();
    size_t i = n;
    int looked_back = 0;
    while (i > 0 && looked_back < 4) {
        const auto c = static_cast<unsigned char>(text[i - 1]);
        if ((c & 0xC0) != 0x80) {  // ASCII or a lead byte
            size_t need = 1;
            if ((c & 0xE0) == 0xC0) need = 2;
            else if ((c & 0xF0) == 0xE0) need = 3;
            else if ((c & 0xF8) == 0xF0) need = 4;
            const size_t have = n - (i - 1);
            return have >= need ? n : i - 1;
        }
        --i;
        ++looked_back;
    }
    return n;  // only continuation bytes: malformed, let the Java decoder replace them
}

bool tokenize(const llama_vocab *vocab, const std::string &text, bool add_special,
              std::vector<llama_token> &out) {
    int n = -llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()), nullptr, 0,
                            add_special, true);
    if (n <= 0) {
        out.clear();
        return n == 0;
    }
    out.resize(static_cast<size_t>(n));
    const int written = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()),
                                       out.data(), n, add_special, true);
    if (written < 0) return false;
    out.resize(static_cast<size_t>(written));
    // A template that already starts with <|begin_of_text|> plus add_special gives two BOS tokens.
    const llama_token bos = llama_vocab_bos(vocab);
    if (out.size() >= 2 && out[0] == bos && out[1] == bos) out.erase(out.begin());
    return true;
}

bool apply_template(const Session &session, const std::vector<std::string> &roles,
                    const std::vector<std::string> &contents, std::string &prompt) {
    std::vector<llama_chat_message> messages;
    messages.reserve(roles.size());
    size_t total = 0;
    for (size_t i = 0; i < roles.size(); ++i) {
        messages.push_back({roles[i].c_str(), contents[i].c_str()});
        total += contents[i].size() + 64;
    }
    const char *model_template = llama_model_chat_template(session.model, nullptr);
    const char *candidates[] = {model_template, "llama3", "chatml"};
    for (const char *tmpl : candidates) {
        if (tmpl == nullptr) continue;
        std::vector<char> buffer(total * 2 + 256);
        int n = llama_chat_apply_template(tmpl, messages.data(), messages.size(), true,
                                          buffer.data(), static_cast<int32_t>(buffer.size()));
        if (n > static_cast<int>(buffer.size())) {
            buffer.resize(static_cast<size_t>(n) + 1);
            n = llama_chat_apply_template(tmpl, messages.data(), messages.size(), true,
                                          buffer.data(), static_cast<int32_t>(buffer.size()));
        }
        if (n > 0) {
            prompt.assign(buffer.data(), static_cast<size_t>(n));
            return true;
        }
    }
    return false;
}

llama_sampler *make_sampler(const llama_vocab *vocab, float temperature, float top_p, int top_k,
                            float repeat_penalty, uint32_t seed) {
    llama_sampler *chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (repeat_penalty > 1.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_penalties(
                                           llama_vocab_n_tokens(vocab), 64, repeat_penalty, 0.0f, 0.0f));
    }
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    } else {
        if (top_k > 0) llama_sampler_chain_add(chain, llama_sampler_init_top_k(top_k));
        if (top_p > 0.0f && top_p < 1.0f) llama_sampler_chain_add(chain, llama_sampler_init_top_p(top_p, 1));
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(chain, llama_sampler_init_dist(seed));
    }
    return chain;
}

// Decodes [tokens] in n_batch chunks, appending them to the session cache.
int decode_tokens(Session &session, std::vector<llama_token> &tokens, size_t from) {
    for (size_t i = from; i < tokens.size(); i += static_cast<size_t>(session.n_batch)) {
        if (session.cancel.load()) return RC_CANCELLED;
        const int n = static_cast<int>(std::min(static_cast<size_t>(session.n_batch), tokens.size() - i));
        llama_batch batch = llama_batch_get_one(tokens.data() + i, n);
        const int rc = llama_decode(session.ctx, batch);
        if (rc == 2) return RC_CANCELLED;
        if (rc != 0) {
            // Whatever part was processed is unknown: drop the whole cache so it stays consistent.
            llama_memory_clear(llama_get_memory(session.ctx), true);
            session.cached.clear();
            set_error("llama_decode failed rc=" + std::to_string(rc));
            return RC_DECODE_FAILED;
        }
        session.cached.insert(session.cached.end(), tokens.begin() + static_cast<long>(i),
                              tokens.begin() + static_cast<long>(i) + n);
    }
    return 0;
}

Session *as_session(jlong handle) { return reinterpret_cast<Session *>(handle); }

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_example_arfas_LlamaNative_nativeInit(JNIEnv *env, jobject, jstring native_lib_dir) {
    const std::string dir = jstring_to_utf8(env, native_lib_dir);
    (void) dir;
    std::call_once(g_backend_once, []() {
        // ggml is linked statically with its CPU backend built in. Do NOT call
        // ggml_backend_load_all_from_path(): it would dlopen the :whisper module's own
        // libggml-cpu.so from nativeLibraryDir (a different ggml build) into this process.
        llama_log_set(llama_log_to_logcat, nullptr);
        llama_backend_init();
        LOGI("backends=%zu system=%s", ggml_backend_reg_count(), llama_print_system_info());
    });
}

JNIEXPORT jstring JNICALL
Java_com_example_arfas_LlamaNative_nativeSystemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

JNIEXPORT jstring JNICALL
Java_com_example_arfas_LlamaNative_nativeLastError(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    return env->NewStringUTF(g_last_error.c_str());
}

JNIEXPORT jlong JNICALL
Java_com_example_arfas_LlamaNative_nativeLoad(JNIEnv *env, jobject, jstring jpath, jint n_ctx,
                                               jint n_threads, jint n_batch, jint n_ubatch,
                                               jint flash_attn) {
    const std::string path = jstring_to_utf8(env, jpath);
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    model_params.load_mode = LLAMA_LOAD_MODE_MMAP;  // CPU only; weights memory-mapped (page cache, not heap)
    clear_llama_errors();
    if (ggml_backend_reg_count() == 0) {
        set_error("no ggml CPU backend registered (native build problem)");
        return 0;
    }
    llama_model *model = llama_model_load_from_file(path.c_str(), model_params);
    if (model == nullptr) {
        const std::string why = recent_llama_errors();
        set_error("could not load GGUF model" + (why.empty() ? std::string(": ") + path : std::string(": ") + why));
        return 0;
    }
    auto *session = new Session();
    session->model = model;
    session->vocab = llama_model_get_vocab(model);

    llama_context_params ctx_params = llama_context_default_params();
    const int trained = llama_model_n_ctx_train(model);
    const int wanted = n_ctx > 0 ? n_ctx : 4096;
    ctx_params.n_ctx = static_cast<uint32_t>(trained > 0 ? std::min(wanted, trained) : wanted);
    ctx_params.n_batch = static_cast<uint32_t>(n_batch > 0 ? n_batch : 512);
    ctx_params.n_ubatch = static_cast<uint32_t>(n_ubatch > 0 ? std::min(n_ubatch, static_cast<int>(ctx_params.n_batch)) : ctx_params.n_batch);
    ctx_params.n_threads = n_threads > 0 ? n_threads : 4;
    ctx_params.n_threads_batch = ctx_params.n_threads;
    // -1 keeps llama.cpp AUTO (current behaviour); 0/1 force flash attention off/on.
    if (flash_attn == 0) ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
    else if (flash_attn == 1) ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    ctx_params.abort_callback = abort_requested;
    ctx_params.abort_callback_data = session;
    session->ctx = llama_init_from_model(model, ctx_params);
    if (session->ctx == nullptr) {
        llama_model_free(model);
        delete session;
        const std::string why = recent_llama_errors();
        set_error("could not create llama context (out of memory?)" + (why.empty() ? std::string() : ": " + why));
        return 0;
    }
    session->n_ctx = static_cast<int>(llama_n_ctx(session->ctx));
    session->n_batch = static_cast<int>(llama_n_batch(session->ctx));
    const int ubatch = static_cast<int>(llama_n_ubatch(session->ctx));
    LOGI("loaded %s ctx=%d batch=%d ubatch=%d threads=%d flash_attn=%d", path.c_str(), session->n_ctx,
         session->n_batch, ubatch, ctx_params.n_threads, static_cast<int>(ctx_params.flash_attn_type));
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT jint JNICALL
Java_com_example_arfas_LlamaNative_nativeContextSize(JNIEnv *, jobject, jlong handle) {
    Session *session = as_session(handle);
    return session ? session->n_ctx : 0;
}

JNIEXPORT void JNICALL
Java_com_example_arfas_LlamaNative_nativeCancel(JNIEnv *, jobject, jlong handle) {
    Session *session = as_session(handle);
    if (session) session->cancel.store(true);
}

JNIEXPORT void JNICALL
Java_com_example_arfas_LlamaNative_nativeFree(JNIEnv *, jobject, jlong handle) {
    Session *session = as_session(handle);
    if (!session) return;
    session->cancel.store(true);
    {
        std::lock_guard<std::mutex> lock(session->generation);  // wait for a running generation
        if (session->ctx) llama_free(session->ctx);
        if (session->model) llama_model_free(session->model);
        session->ctx = nullptr;
        session->model = nullptr;
    }
    delete session;
}

JNIEXPORT jint JNICALL
Java_com_example_arfas_LlamaNative_nativeCountTokens(JNIEnv *env, jobject, jlong handle, jstring jtext) {
    Session *session = as_session(handle);
    if (!session) return -1;
    std::vector<llama_token> tokens;
    if (!tokenize(session->vocab, jstring_to_utf8(env, jtext), false, tokens)) return -1;
    return static_cast<jint>(tokens.size());
}

// Tokens of the full templated prompt for [roles]/[contents] (used to fit history/evidence).
JNIEXPORT jint JNICALL
Java_com_example_arfas_LlamaNative_nativeCountPromptTokens(JNIEnv *env, jobject, jlong handle,
                                                            jobjectArray jroles, jobjectArray jcontents) {
    Session *session = as_session(handle);
    if (!session) return -1;
    const jsize n = env->GetArrayLength(jroles);
    std::vector<std::string> roles, contents;
    for (jsize i = 0; i < n; ++i) {
        auto r = (jstring) env->GetObjectArrayElement(jroles, i);
        auto c = (jstring) env->GetObjectArrayElement(jcontents, i);
        roles.push_back(jstring_to_utf8(env, r));
        contents.push_back(jstring_to_utf8(env, c));
        env->DeleteLocalRef(r);
        env->DeleteLocalRef(c);
    }
    std::string prompt;
    if (!apply_template(*session, roles, contents, prompt)) return -1;
    std::vector<llama_token> tokens;
    if (!tokenize(session->vocab, prompt, true, tokens)) return -1;
    return static_cast<jint>(tokens.size());
}

JNIEXPORT jint JNICALL
Java_com_example_arfas_LlamaNative_nativeGenerate(JNIEnv *env, jobject, jlong handle,
                                                   jobjectArray jroles, jobjectArray jcontents,
                                                   jint max_tokens, jfloat temperature, jfloat top_p,
                                                   jint top_k, jfloat repeat_penalty, jint seed,
                                                   jobject sink, jlongArray jstats) {
    Session *session = as_session(handle);
    if (!session || !session->ctx) return RC_BAD_HANDLE;
    std::lock_guard<std::mutex> lock(session->generation);
    session->cancel.store(false);
    const int64_t started = now_ms();
    int64_t stats[6] = {0, 0, 0, -1, 0, 0};  // prompt, reused, generated, ttft, total, prefill
    auto write_stats = [&]() {
        stats[4] = now_ms() - started;
        if (jstats != nullptr && env->GetArrayLength(jstats) >= 6) {
            env->SetLongArrayRegion(jstats, 0, 6, reinterpret_cast<const jlong *>(stats));
        }
    };

    const jsize n_messages = env->GetArrayLength(jroles);
    std::vector<std::string> roles, contents;
    for (jsize i = 0; i < n_messages; ++i) {
        auto r = (jstring) env->GetObjectArrayElement(jroles, i);
        auto c = (jstring) env->GetObjectArrayElement(jcontents, i);
        roles.push_back(jstring_to_utf8(env, r));
        contents.push_back(jstring_to_utf8(env, c));
        env->DeleteLocalRef(r);
        env->DeleteLocalRef(c);
    }
    std::string prompt;
    if (!apply_template(*session, roles, contents, prompt)) {
        set_error("chat template failed");
        write_stats();
        return RC_TEMPLATE_FAILED;
    }
    std::vector<llama_token> tokens;
    if (!tokenize(session->vocab, prompt, true, tokens) || tokens.empty()) {
        set_error("tokenize failed");
        write_stats();
        return RC_TOKENIZE_FAILED;
    }
    stats[0] = static_cast<int64_t>(tokens.size());
    const int budget = std::max(16, static_cast<int>(max_tokens));
    if (static_cast<int>(tokens.size()) + budget > session->n_ctx) {
        set_error("prompt too long: " + std::to_string(tokens.size()) + " + " + std::to_string(budget) +
                  " > " + std::to_string(session->n_ctx));
        write_stats();
        return RC_PROMPT_TOO_LONG;
    }

    // Reuse the KV cache for the longest common prefix (system prompt, unchanged history).
    size_t prefix = 0;
    while (prefix < session->cached.size() && prefix < tokens.size() &&
           session->cached[prefix] == tokens[prefix]) {
        ++prefix;
    }
    if (prefix >= tokens.size()) prefix = tokens.size() - 1;  // at least one token must be decoded for logits
    llama_memory_t memory = llama_get_memory(session->ctx);
    if (prefix < session->cached.size()) {
        if (!llama_memory_seq_rm(memory, 0, static_cast<llama_pos>(prefix), -1)) {
            llama_memory_clear(memory, true);
            prefix = 0;
        }
        session->cached.resize(prefix);
    }
    stats[1] = static_cast<int64_t>(prefix);

    const int prefill_rc = decode_tokens(*session, tokens, prefix);
    stats[5] = now_ms() - started;
    if (prefill_rc != 0) {
        if (prefill_rc == RC_CANCELLED) {
            // A partially processed chunk may be in memory; resync the cache bookkeeping with it.
            const llama_pos max_pos = llama_memory_seq_pos_max(memory, 0);
            if (max_pos + 1 < static_cast<llama_pos>(session->cached.size())) session->cached.resize(max_pos + 1);
            else if (max_pos + 1 > static_cast<llama_pos>(session->cached.size())) {
                llama_memory_seq_rm(memory, 0, static_cast<llama_pos>(session->cached.size()), -1);
            }
        }
        write_stats();
        return prefill_rc;
    }

    jmethodID on_piece = nullptr;
    if (sink != nullptr) {
        jclass sink_class = env->GetObjectClass(sink);
        on_piece = env->GetMethodID(sink_class, "onPiece", "([B)Z");
        env->DeleteLocalRef(sink_class);
        if (on_piece == nullptr) {
            env->ExceptionClear();
            sink = nullptr;
        }
    }

    llama_sampler *sampler = make_sampler(session->vocab, temperature, top_p, top_k, repeat_penalty,
                                          seed < 0 ? LLAMA_DEFAULT_SEED : static_cast<uint32_t>(seed));
    std::string pending;  // bytes not yet sent (incomplete UTF-8 tail)
    int rc = RC_MAX_TOKENS;
    char piece[256];

    auto emit = [&](bool flush) -> bool {
        const size_t ready = flush ? pending.size() : utf8_complete_prefix(pending);
        if (ready == 0 || sink == nullptr) {
            if (sink == nullptr) pending.erase(0, ready);
            return true;
        }
        jbyteArray bytes = env->NewByteArray(static_cast<jsize>(ready));
        env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(ready), reinterpret_cast<const jbyte *>(pending.data()));
        const jboolean keep_going = env->CallBooleanMethod(sink, on_piece, bytes);
        env->DeleteLocalRef(bytes);
        pending.erase(0, ready);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return false;
        }
        return keep_going == JNI_TRUE;
    };

    for (int generated = 0; generated < budget; ++generated) {
        if (session->cancel.load()) { rc = RC_CANCELLED; break; }
        llama_token token = llama_sampler_sample(sampler, session->ctx, -1);
        if (stats[3] < 0) stats[3] = now_ms() - started;
        if (llama_vocab_is_eog(session->vocab, token)) { rc = RC_EOS; break; }
        const int n = llama_token_to_piece(session->vocab, token, piece, sizeof(piece), 0, false);
        if (n > 0) pending.append(piece, static_cast<size_t>(n));
        stats[2] = generated + 1;
        if (!emit(false)) { rc = RC_STOPPED_BY_CLIENT; break; }
        if (static_cast<int>(session->cached.size()) + 1 >= session->n_ctx) { rc = RC_CONTEXT_FULL; break; }
        std::vector<llama_token> next{token};
        const int decode_rc = decode_tokens(*session, next, 0);
        if (decode_rc != 0) { rc = decode_rc; break; }
    }
    if (rc != RC_STOPPED_BY_CLIENT) emit(true);
    llama_sampler_free(sampler);
    write_stats();
    return rc;
}

}  // extern "C"
