// JNI bridge between the app and llama.cpp, in-process, CPU first.
// One Session holds a model, its context, a sampler and, for Omni, the mtmd projector.
// Calls on one session must not overlap; the Kotlin side runs them on one thread.

#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <chrono>
#include <string>
#include <vector>

#include "ggml-backend.h"
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define TAG "lfm-native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Session {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    mtmd_context *mtmd = nullptr;
    std::vector<llama_token> cached; // tokens now in the context's memory, in order
    int n_threads = 4;
    // stats of the last call
    double prefill_ms = 0, decode_ms = 0;
    int n_prefill = 0, n_decode = 0, n_reused = 0;
};

using clock_type = std::chrono::steady_clock;

double ms_since(clock_type::time_point t) {
    return std::chrono::duration<double, std::milli>(clock_type::now() - t).count();
}

void log_cb(ggml_log_level level, const char *text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) {
        __android_log_print(level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN, TAG, "%s", text);
    }
}

std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

// Strings come from Kotlin as UTF-8 bytes: JNI's own string calls use modified UTF-8, which breaks emoji.
std::string jbytes(JNIEnv *env, jbyteArray a) {
    if (!a) return {};
    std::string out((size_t) env->GetArrayLength(a), '\0');
    env->GetByteArrayRegion(a, 0, (jsize) out.size(), reinterpret_cast<jbyte *>(out.data()));
    return out;
}

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text, bool add_special) {
    int n = -llama_tokenize(vocab, text.data(), (int32_t) text.size(), nullptr, 0, add_special, true);
    std::vector<llama_token> out(std::max(n, 0));
    if (n > 0) {
        llama_tokenize(vocab, text.data(), (int32_t) text.size(), out.data(), n, add_special, true);
    }
    return out;
}

std::string piece(const llama_vocab *vocab, llama_token t) {
    char buf[256];
    int n = llama_token_to_piece(vocab, t, buf, sizeof(buf), 0, true);
    if (n < 0) {
        std::string big(-n, '\0');
        llama_token_to_piece(vocab, t, big.data(), -n, 0, true);
        return big;
    }
    return std::string(buf, n);
}

// Number of bytes at the end of s that start an unfinished UTF-8 sequence.
size_t incomplete_utf8_tail(const std::string &s) {
    size_t n = s.size();
    for (size_t back = 1; back <= 4 && back <= n; back++) {
        auto c = (unsigned char) s[n - back];
        if ((c & 0xC0) == 0x80) continue; // continuation byte
        size_t need = (c & 0x80) == 0 ? 1 : (c & 0xE0) == 0xC0 ? 2 : (c & 0xF0) == 0xE0 ? 3 : (c & 0xF8) == 0xF0 ? 4 : 1;
        return need > back ? back : 0;
    }
    return 0;
}

llama_sampler *make_sampler(const llama_vocab *vocab, float temp, float top_p, int seed) {
    auto params = llama_sampler_chain_default_params();
    auto *chain = llama_sampler_chain_init(params);
    if (temp <= 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(chain, llama_sampler_init_top_k(50));
        llama_sampler_chain_add(chain, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.05f, 0.0f, 0.0f));
        llama_sampler_chain_add(chain, llama_sampler_init_top_p(top_p, 1));
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temp));
        llama_sampler_chain_add(chain, llama_sampler_init_dist((uint32_t) seed));
    }
    return chain;
}

// Decode tokens in batches; logits only for the last one.
bool decode_all(Session *s, const llama_token *tokens, int n) {
    const int n_batch = (int) llama_n_batch(s->ctx);
    for (int i = 0; i < n; i += n_batch) {
        int len = std::min(n_batch, n - i);
        llama_batch batch = llama_batch_get_one(const_cast<llama_token *>(tokens + i), len);
        if (llama_decode(s->ctx, batch) != 0) return false;
        s->cached.insert(s->cached.end(), tokens + i, tokens + i + len);
    }
    return true;
}

// Calls listener.onToken(byte[]) for each finished UTF-8 piece; stops when it returns false.
// Returns the number of generated tokens, or a negative error.
int sample_loop(JNIEnv *env, Session *s, llama_sampler *smpl, int max_tokens, jobject listener) {
    jclass cls = env->GetObjectClass(listener);
    jmethodID on_token = env->GetMethodID(cls, "onToken", "([B)Z");
    std::string pending;
    int n = 0;
    auto t0 = clock_type::now();
    const int n_ctx = (int) llama_n_ctx(s->ctx);
    for (; n < max_tokens; n++) {
        if ((int) s->cached.size() + 1 >= n_ctx) break;
        llama_token id = llama_sampler_sample(smpl, s->ctx, -1);
        if (llama_vocab_is_eog(s->vocab, id)) break;
        pending += piece(s->vocab, id);
        size_t tail = incomplete_utf8_tail(pending);
        bool keep_going = true;
        if (pending.size() > tail) {
            size_t len = pending.size() - tail;
            jbyteArray arr = env->NewByteArray((jsize) len);
            env->SetByteArrayRegion(arr, 0, (jsize) len, reinterpret_cast<const jbyte *>(pending.data()));
            keep_going = env->CallBooleanMethod(listener, on_token, arr);
            env->DeleteLocalRef(arr);
            if (env->ExceptionCheck()) keep_going = false;
            pending.erase(0, len);
        }
        llama_batch batch = llama_batch_get_one(&id, 1);
        if (llama_decode(s->ctx, batch) != 0) { n = -3; break; }
        s->cached.push_back(id);
        if (!keep_going) { n++; break; }
    }
    s->decode_ms = ms_since(t0);
    s->n_decode = std::max(n, 0);
    return n;
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_lfmpixel_lfm_llm_LlamaNative_backendInit(JNIEnv *env, jobject, jstring lib_dir) {
    llama_log_set(log_cb, nullptr);
    std::string dir = jstr(env, lib_dir);
    ggml_backend_load_all_from_path(dir.c_str());
    llama_backend_init();
}

JNIEXPORT jstring JNICALL
Java_com_lfmpixel_lfm_llm_LlamaNative_systemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

JNIEXPORT jlong JNICALL
Java_com_lfmpixel_lfm_llm_LlamaNative_load(JNIEnv *env, jobject, jstring jpath, jint n_ctx, jint n_threads) {
    std::string path = jstr(env, jpath);
    auto mparams = llama_model_default_params();
    llama_model *model = llama_model_load_from_file(path.c_str(), mparams);
    if (!model) {
        LOGE("could not load %s", path.c_str());
        return 0;
    }
    auto cparams = llama_context_default_params();
    cparams.n_ctx = (uint32_t) n_ctx;
    cparams.n_batch = 512;
    cparams.n_ubatch = 512;
    cparams.n_threads = n_threads;
    cparams.n_threads_batch = n_threads;
    llama_context *ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        llama_model_free(model);
        LOGE("could not create a context for %s", path.c_str());
        return 0;
    }
    auto *s = new Session();
    s->model = model;
    s->ctx = ctx;
    s->vocab = llama_model_get_vocab(model);
    s->n_threads = n_threads;
    LOGI("loaded %s, n_ctx %d, %d threads", path.c_str(), n_ctx, n_threads);
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT jboolean JNICALL
Java_com_lfmpixel_lfm_llm_LlamaNative_loadProjector(JNIEnv *env, jobject, jlong handle, jstring jpath) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (!s) return JNI_FALSE;
    std::string path = jstr(env, jpath);
    auto params = mtmd_context_params_default();
    params.use_gpu = false;
    params.n_threads = s->n_threads;
    params.print_timings = false;
    params.warmup = false;
    s->mtmd = mtmd_init_from_file(path.c_str(), s->model, params);
    if (!s->mtmd) {
        LOGE("could not load projector %s", path.c_str());
        return JNI_FALSE;
    }
    return mtmd_support_audio(s->mtmd) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_lfmpixel_lfm_llm_LlamaNative_free(JNIEnv *, jobject, jlong handle) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (!s) return;
    if (s->mtmd) mtmd_free(s->mtmd);
    llama_free(s->ctx);
    llama_model_free(s->model);
    delete s;
}

JNIEXPORT jint JNICALL
Java_com_lfmpixel_lfm_llm_LlamaNative_countTokens(JNIEnv *env, jobject, jlong handle, jbyteArray jtext) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (!s) return -1;
    return (jint) tokenize(s->vocab, jbytes(env, jtext), false).size();
}

// Generates after a full prompt (already in the chat format). Reuses the part of the
// context memory that matches the start of the prompt, which is most of it in a chat.
JNIEXPORT jint JNICALL
Java_com_lfmpixel_lfm_llm_LlamaNative_generate(JNIEnv *env, jobject, jlong handle, jbyteArray jprompt,
                                               jint max_tokens, jfloat temp, jfloat top_p, jint seed,
                                               jobject listener) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (!s) return -1;
    std::vector<llama_token> tokens = tokenize(s->vocab, jbytes(env, jprompt), true);
    const int n_ctx = (int) llama_n_ctx(s->ctx);
    if (tokens.empty()) return -1;
    if ((int) tokens.size() + 8 >= n_ctx) return -2; // the caller trims history and tries again

    size_t keep = 0;
    while (keep < s->cached.size() && keep < tokens.size() && s->cached[keep] == tokens[keep]) keep++;
    if (keep == tokens.size()) keep--; // the last prompt token is decoded again for fresh logits
    llama_memory_t mem = llama_get_memory(s->ctx);
    if (keep < s->cached.size()) {
        if (!llama_memory_seq_rm(mem, 0, (llama_pos) keep, -1)) {
            // Recurrent layers cannot drop a partial sequence: start over.
            llama_memory_clear(mem, true);
            keep = 0;
        }
        s->cached.resize(keep);
    }
    s->n_reused = (int) keep;

    auto t0 = clock_type::now();
    if (!decode_all(s, tokens.data() + keep, (int) (tokens.size() - keep))) {
        llama_memory_clear(mem, true);
        s->cached.clear();
        return -3;
    }
    s->prefill_ms = ms_since(t0);
    s->n_prefill = (int) (tokens.size() - keep);

    llama_sampler *smpl = make_sampler(s->vocab, temp, top_p, seed);
    int n = sample_loop(env, s, smpl, max_tokens, listener);
    llama_sampler_free(smpl);
    return n;
}

// Speech to text with Omni: 16 kHz mono float PCM in, text out through the listener.
JNIEXPORT jint JNICALL
Java_com_lfmpixel_lfm_llm_LlamaNative_transcribe(JNIEnv *env, jobject, jlong handle, jfloatArray jpcm,
                                                 jbyteArray jprompt, jint max_tokens, jobject listener) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (!s || !s->mtmd) return -1;
    std::vector<float> pcm(env->GetArrayLength(jpcm));
    env->GetFloatArrayRegion(jpcm, 0, (jsize) pcm.size(), pcm.data());
    std::string prompt = jbytes(env, jprompt);

    mtmd_bitmap *audio = mtmd_bitmap_init_from_audio(pcm.size(), pcm.data());
    mtmd_input_chunks *chunks = mtmd_input_chunks_init();
    mtmd_input_text text{prompt.c_str(), prompt.size(), true, true};
    const mtmd_bitmap *bitmaps[] = {audio};
    int rc = mtmd_tokenize(s->mtmd, chunks, &text, bitmaps, 1);
    mtmd_bitmap_free(audio);
    if (rc != 0) {
        mtmd_input_chunks_free(chunks);
        return -4;
    }

    llama_memory_clear(llama_get_memory(s->ctx), true);
    s->cached.clear();
    auto t0 = clock_type::now();
    llama_pos n_past = 0;
    rc = mtmd_helper_eval_chunks(s->mtmd, s->ctx, chunks, 0, 0, (int32_t) llama_n_batch(s->ctx), true, &n_past);
    mtmd_input_chunks_free(chunks);
    if (rc != 0) return -3;
    s->prefill_ms = ms_since(t0);
    s->n_prefill = (int) n_past;
    s->n_reused = 0;
    // The memory now holds media embeddings, not tokens we can compare: mark it as unknown.
    s->cached.assign((size_t) n_past, (llama_token) -1);

    llama_sampler *smpl = make_sampler(s->vocab, 0.0f, 1.0f, 0);
    int n = sample_loop(env, s, smpl, max_tokens, listener);
    llama_sampler_free(smpl);
    return n;
}

// [prefill ms, prefill tokens, decode ms, decode tokens, reused tokens]
JNIEXPORT jdoubleArray JNICALL
Java_com_lfmpixel_lfm_llm_LlamaNative_lastStats(JNIEnv *env, jobject, jlong handle) {
    auto *s = reinterpret_cast<Session *>(handle);
    jdoubleArray out = env->NewDoubleArray(5);
    if (!s) return out;
    double v[5] = {s->prefill_ms, (double) s->n_prefill, s->decode_ms, (double) s->n_decode, (double) s->n_reused};
    env->SetDoubleArrayRegion(out, 0, 5, v);
    return out;
}

} // extern "C"
