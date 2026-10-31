// JNI bridge between the app and llama.cpp, in-process, CPU first.
// One Session holds a model, its context, a sampler and, for Omni, the mtmd projector.
// Calls on one session must not overlap; the Kotlin side runs them on one thread.

#include <android/log.h>
#include <jni.h>
#include <link.h>
#include <sched.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <climits>
#include <cstdio>
#include <cstdlib>
#include <map>
#include <mutex>
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
    int n_threads = 4;       // generation, one token at a time: bound by memory, not compute
    int n_threads_batch = 4; // reading the prompt in: bound by compute
    std::string load_notes;  // what llama.cpp said while loading (buffer types, flash attention)
    // stats of the last call
    double prefill_ms = 0, decode_ms = 0, first_ms = 0;
    int n_prefill = 0, n_decode = 0, n_reused = 0;
    std::chrono::steady_clock::time_point started; // when the call came in, for the time to the first token
};

using clock_type = std::chrono::steady_clock;

// Bumped by Stop. Reading pictures in checks it between pictures; no session handle is touched, so it is safe
// to call at any time, even while a session is being freed.
std::atomic<int> g_cancel{0};

double ms_since(clock_type::time_point t) {
    return std::chrono::duration<double, std::milli>(clock_type::now() - t).count();
}

// While a model loads, llama.cpp's info lines that say how it runs are kept for the benchmark.
std::mutex g_notes_mutex;
std::string *g_notes = nullptr;

void log_cb(ggml_log_level level, const char *text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) {
        __android_log_print(level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN, TAG, "%s", text);
    } else if (level == GGML_LOG_LEVEL_INFO) {
        std::string line(text);
        // Buffer lines only from 1 MiB: llama.cpp also logs one per tensor for some layers.
        auto mib = line.find(" MiB");
        bool big_buffer = line.find("buffer size") != std::string::npos && mib != std::string::npos &&
                          atof(line.c_str() + line.rfind('=', mib) + 1) >= 1.0;
        if (big_buffer || line.find(" enabled") != std::string::npos ||
            line.find(" disabled") != std::string::npos || line.find("repack") != std::string::npos ||
            line.find("KleidiAI") != std::string::npos || line.find("kleidiai") != std::string::npos) {
            __android_log_print(ANDROID_LOG_INFO, TAG, "%s", text);
            std::lock_guard<std::mutex> lock(g_notes_mutex);
            if (g_notes) *g_notes += line;
        }
    }
}

// --- CPU cores --------------------------------------------------------------------------------------------------
// Tensor G5 has 1 big, 5 mid and 2 little cores. llama.cpp splits each step evenly over its threads and waits for
// the slowest, so one thread on a little core slows every token. Threads are kept off the little cores.

long read_long(const std::string &path) {
    FILE *f = fopen(path.c_str(), "r");
    if (!f) return -1;
    long v = -1;
    if (fscanf(f, "%ld", &v) != 1) v = -1;
    fclose(f);
    return v;
}

struct Cores {
    std::vector<long> khz;  // max frequency per core, -1 if unknown
    std::vector<int> fast;  // cores that are not little
    int n = 0;
};

const Cores &cores() {
    static Cores c = [] {
        Cores c;
        c.n = (int) sysconf(_SC_NPROCESSORS_CONF);
        std::vector<long> cap(c.n);
        long max_cap = 0, max_khz = 0, min_cap = LONG_MAX, min_khz = LONG_MAX;
        for (int i = 0; i < c.n; i++) {
            std::string dir = "/sys/devices/system/cpu/cpu" + std::to_string(i);
            cap[i] = read_long(dir + "/cpu_capacity");
            c.khz.push_back(read_long(dir + "/cpufreq/cpuinfo_max_freq"));
            max_cap = std::max(max_cap, cap[i]);
            max_khz = std::max(max_khz, c.khz[i]);
            if (cap[i] > 0) min_cap = std::min(min_cap, cap[i]);
            if (c.khz[i] > 0) min_khz = std::min(min_khz, c.khz[i]);
        }
        for (int i = 0; i < c.n; i++) {
            // Little cores are the slowest group only, and only when well under half the big core's capacity (G5:
            // about a quarter), or, without capacity numbers, a clearly lower top clock. Mid cores are always kept.
            bool little = max_cap > 0 ? cap[i] == min_cap && min_cap * 2 < max_cap
                                      : max_khz > 0 && c.khz[i] == min_khz && min_khz * 10 < max_khz * 7;
            if (!little) c.fast.push_back(i);
        }
        if (c.fast.empty()) for (int i = 0; i < c.n; i++) c.fast.push_back(i);
        return c;
    }();
    return c;
}

// "8 cores: 1 × 3.78 GHz, 5 × 3.05 GHz, 2 × 2.25 GHz (little, not used)"
std::string describe_cores() {
    const Cores &c = cores();
    std::map<long, std::pair<int, bool>, std::greater<>> groups; // khz -> (count, little)
    for (int i = 0; i < c.n; i++) {
        bool little = std::find(c.fast.begin(), c.fast.end(), i) == c.fast.end();
        auto &g = groups[c.khz[i]];
        g.first++;
        g.second = little;
    }
    std::string out = std::to_string(c.n) + " cores";
    if (groups.size() == 1 && groups.begin()->first < 100000) return out; // no clock speeds to tell (emulator)
    out += ":";
    bool first = true;
    for (auto &[khz, g] : groups) {
        char buf[96];
        if (khz >= 100000) snprintf(buf, sizeof(buf), "%s %d × %.2f GHz%s", first ? "" : ",", g.first, khz / 1e6, g.second ? " (little, not used)" : "");
        else snprintf(buf, sizeof(buf), "%s %d%s", first ? "" : ",", g.first, g.second ? " little (not used)" : "");
        out += buf;
        first = false;
    }
    return out;
}

// Keeps the calling thread, and the OpenMP worker threads it starts (they inherit its mask), on the cores that are
// not little. With more threads than such cores, all cores are allowed.
void pin_threads(int n_threads) {
    const Cores &c = cores();
    cpu_set_t set;
    CPU_ZERO(&set);
    if (n_threads <= (int) c.fast.size()) {
        for (int i : c.fast) CPU_SET(i, &set);
    } else {
        for (int i = 0; i < c.n; i++) CPU_SET(i, &set);
    }
    if (sched_setaffinity(0, sizeof(set), &set) != 0) LOGE("could not set thread affinity");
}

// Which libggml-cpu-<variant>.so llama.cpp picked for this CPU.
std::string cpu_variant() {
    std::string found;
    dl_iterate_phdr([](dl_phdr_info *info, size_t, void *data) {
        std::string name = info->dlpi_name ? info->dlpi_name : "";
        auto pos = name.find("libggml-cpu-");
        if (pos != std::string::npos) {
            std::string v = name.substr(pos + 12);
            if (v.size() > 3 && v.compare(v.size() - 3, 3, ".so") == 0) v.resize(v.size() - 3);
            *static_cast<std::string *>(data) = v;
        }
        return 0;
    }, &found);
    return found.empty() ? "built in" : found;
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
        if (n == 0) s->first_ms = ms_since(s->started);
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

// Cores that are not little: the most threads worth using.
JNIEXPORT jint JNICALL
Java_com_lfmpixel_lfm_llm_LlamaNative_fastCores(JNIEnv *, jobject) {
    return (jint) cores().fast.size();
}

JNIEXPORT jstring JNICALL
Java_com_lfmpixel_lfm_llm_LlamaNative_systemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

JNIEXPORT jlong JNICALL
Java_com_lfmpixel_lfm_llm_LlamaNative_load(JNIEnv *env, jobject, jstring jpath, jint n_ctx, jint n_threads,
                                           jint n_threads_batch) {
    std::string path = jstr(env, jpath);
    // Before anything starts OpenMP's threads: they keep the mask they start with, so the first load's mask holds
    // until the app restarts. On G5 every count offered (at most 6) fits the 6 fast cores, so it is the same mask.
    pin_threads(std::max(n_threads, n_threads_batch));
    std::string notes;
    {
        std::lock_guard<std::mutex> lock(g_notes_mutex);
        g_notes = &notes;
    }
    auto mparams = llama_model_default_params(); // mmap on, mlock off, Q4_0 repacked for Arm (KleidiAI) on load
    llama_model *model = llama_model_load_from_file(path.c_str(), mparams);
    llama_context *ctx = nullptr;
    if (model) {
        auto cparams = llama_context_default_params();
        cparams.n_ctx = (uint32_t) n_ctx;
        cparams.n_batch = 512;
        cparams.n_ubatch = 512;
        cparams.n_threads = n_threads;
        cparams.n_threads_batch = n_threads_batch;
        cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
        cparams.no_perf = true;
        ctx = llama_init_from_model(model, cparams);
    }
    {
        std::lock_guard<std::mutex> lock(g_notes_mutex);
        g_notes = nullptr;
    }
    if (!model) {
        LOGE("could not load %s", path.c_str());
        return 0;
    }
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
    s->n_threads_batch = n_threads_batch;
    s->load_notes = notes;
    LOGI("loaded %s, n_ctx %d, %d threads (%d for prompts), CPU variant %s, %s", path.c_str(), n_ctx, n_threads,
         n_threads_batch, cpu_variant().c_str(), describe_cores().c_str());
    return reinterpret_cast<jlong>(s);
}

// Returns what the projector can take in: 1 audio, 2 pictures (both: 3), or -1 if it did not load.
JNIEXPORT jint JNICALL
Java_com_lfmpixel_lfm_llm_LlamaNative_loadProjector(JNIEnv *env, jobject, jlong handle, jstring jpath) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (!s) return -1;
    std::string path = jstr(env, jpath);
    auto params = mtmd_context_params_default();
    params.use_gpu = false;
    params.n_threads = s->n_threads_batch;
    params.print_timings = false;
    params.warmup = false;
    s->mtmd = mtmd_init_from_file(path.c_str(), s->model, params);
    if (!s->mtmd) {
        LOGE("could not load projector %s", path.c_str());
        return -1;
    }
    return (mtmd_support_audio(s->mtmd) ? 1 : 0) | (mtmd_support_vision(s->mtmd) ? 2 : 0);
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
    s->started = clock_type::now();
    s->first_ms = 0;
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
    s->started = clock_type::now();
    s->first_ms = 0;
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

// Pictures in, text out (VL or Omni): each picture is packed RGB, one <__media__> marker per picture in the prompt.
// Video is a few frames sent as pictures, in order.
JNIEXPORT jint JNICALL
Java_com_lfmpixel_lfm_llm_LlamaNative_see(JNIEnv *env, jobject, jlong handle, jobjectArray jimages, jintArray jsizes,
                                          jbyteArray jprompt, jint max_tokens, jfloat temp, jobject listener) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (!s || !s->mtmd || !mtmd_support_vision(s->mtmd)) return -1;
    s->started = clock_type::now();
    s->first_ms = 0;
    const int n_images = env->GetArrayLength(jimages);
    if (env->GetArrayLength(jsizes) != n_images * 2) return -5;
    std::vector<jint> sizes(n_images * 2);
    env->GetIntArrayRegion(jsizes, 0, n_images * 2, sizes.data());
    std::vector<mtmd_bitmap *> bitmaps;
    for (int i = 0; i < n_images; i++) {
        auto arr = (jbyteArray) env->GetObjectArrayElement(jimages, i);
        const int nx = sizes[i * 2], ny = sizes[i * 2 + 1];
        if (env->GetArrayLength(arr) != nx * ny * 3) {
            env->DeleteLocalRef(arr);
            for (auto *b : bitmaps) mtmd_bitmap_free(b);
            return -5;
        }
        jbyte *px = env->GetByteArrayElements(arr, nullptr);
        if (!px) {
            env->DeleteLocalRef(arr);
            for (auto *b : bitmaps) mtmd_bitmap_free(b);
            return -5;
        }
        bitmaps.push_back(mtmd_bitmap_init((uint32_t) nx, (uint32_t) ny, reinterpret_cast<const unsigned char *>(px)));
        env->ReleaseByteArrayElements(arr, px, JNI_ABORT);
        env->DeleteLocalRef(arr);
    }
    std::string prompt = jbytes(env, jprompt);
    mtmd_input_chunks *chunks = mtmd_input_chunks_init();
    mtmd_input_text text{prompt.c_str(), prompt.size(), true, true};
    std::vector<const mtmd_bitmap *> cbitmaps(bitmaps.begin(), bitmaps.end());
    int rc = mtmd_tokenize(s->mtmd, chunks, &text, cbitmaps.data(), cbitmaps.size());
    for (auto *b : bitmaps) mtmd_bitmap_free(b);
    if (rc != 0) {
        mtmd_input_chunks_free(chunks);
        return -4;
    }
    if ((int) mtmd_helper_get_n_pos(chunks) + 8 >= (int) llama_n_ctx(s->ctx)) {
        mtmd_input_chunks_free(chunks);
        return -2;
    }

    llama_memory_clear(llama_get_memory(s->ctx), true);
    s->cached.clear();
    auto t0 = clock_type::now();
    llama_pos n_past = 0;
    const int gen = g_cancel.load();
    const size_t n_chunks = mtmd_input_chunks_size(chunks);
    for (size_t i = 0; i < n_chunks && rc == 0; i++) {
        if (g_cancel.load() != gen) { rc = -6; break; }
        rc = mtmd_helper_eval_chunk_single(s->mtmd, s->ctx, mtmd_input_chunks_get(chunks, i), n_past, 0,
                                           (int32_t) llama_n_batch(s->ctx), i == n_chunks - 1, &n_past);
    }
    mtmd_input_chunks_free(chunks);
    if (rc != 0) {
        llama_memory_clear(llama_get_memory(s->ctx), true);
        return rc == -6 ? -6 : -3;
    }
    s->prefill_ms = ms_since(t0);
    s->n_prefill = (int) n_past;
    s->n_reused = 0;
    s->cached.assign((size_t) n_past, (llama_token) -1);

    llama_sampler *smpl = make_sampler(s->vocab, temp, 0.9f, 42);
    int n = sample_loop(env, s, smpl, max_tokens, listener);
    llama_sampler_free(smpl);
    return n;
}

JNIEXPORT void JNICALL
Java_com_lfmpixel_lfm_llm_LlamaNative_cancelPictures(JNIEnv *, jobject) {
    g_cancel.fetch_add(1);
}

// [prefill ms, prefill tokens, decode ms, decode tokens, reused tokens, first token ms]
JNIEXPORT jdoubleArray JNICALL
Java_com_lfmpixel_lfm_llm_LlamaNative_lastStats(JNIEnv *env, jobject, jlong handle) {
    auto *s = reinterpret_cast<Session *>(handle);
    jdoubleArray out = env->NewDoubleArray(6);
    if (!s) return out;
    double v[6] = {s->prefill_ms, (double) s->n_prefill, s->decode_ms, (double) s->n_decode, (double) s->n_reused,
                   s->first_ms};
    env->SetDoubleArrayRegion(out, 0, 6, v);
    return out;
}

// The speed test (Settings → Models → Benchmark). Reads in n_prompt tokens of plain text, then writes n_gen tokens
// with the chat's own sampler and no callbacks, with the given thread counts (0: the session's own). The context is
// left empty. Returns [prompt ms, prompt tokens, first token ms, gen ms, gen tokens, sampler ms, threads,
// prompt threads], or an empty array on failure.
JNIEXPORT jdoubleArray JNICALL
Java_com_lfmpixel_lfm_llm_LlamaNative_bench(JNIEnv *env, jobject, jlong handle, jint n_prompt, jint n_gen,
                                            jint n_threads, jint n_threads_batch) {
    auto *s = reinterpret_cast<Session *>(handle);
    jdoubleArray out = env->NewDoubleArray(0);
    if (!s || n_prompt < 1 || n_gen < 1) return out;
    const int nt = n_threads > 0 ? n_threads : s->n_threads;
    const int ntb = n_threads_batch > 0 ? n_threads_batch : s->n_threads_batch;
    llama_set_n_threads(s->ctx, nt, ntb);

    std::string text;
    std::vector<llama_token> tokens;
    while ((int) tokens.size() < n_prompt) {
        text += "The quick brown fox jumps over the lazy dog while the old clock on the wall ticks the afternoon away. ";
        tokens = tokenize(s->vocab, text, true);
    }
    tokens.resize(n_prompt);
    const int n_ctx = (int) llama_n_ctx(s->ctx);
    n_gen = std::min(n_gen, n_ctx - n_prompt - 2);

    llama_memory_t mem = llama_get_memory(s->ctx);
    llama_memory_clear(mem, true);
    s->cached.clear();
    double v[8] = {0, (double) n_prompt, 0, 0, 0, 0, (double) nt, (double) ntb};
    bool ok = n_gen > 0;
    auto t0 = clock_type::now();
    if (ok) ok = decode_all(s, tokens.data(), (int) tokens.size());
    v[0] = ms_since(t0);
    llama_sampler *smpl = make_sampler(s->vocab, 0.3f, 0.9f, 42);
    double sample_ms = 0;
    int n = 0;
    auto t1 = clock_type::now();
    while (ok && n < n_gen) {
        auto ts = clock_type::now();
        llama_token id = llama_sampler_sample(smpl, s->ctx, -1);
        sample_ms += ms_since(ts);
        if (n == 0) v[2] = v[0] + ms_since(t1);
        llama_batch batch = llama_batch_get_one(&id, 1);
        if (llama_decode(s->ctx, batch) != 0) ok = false;
        else n++;
    }
    v[3] = ms_since(t1);
    v[4] = n;
    v[5] = sample_ms;
    llama_sampler_free(smpl);
    llama_memory_clear(mem, true);
    s->cached.clear();
    llama_set_n_threads(s->ctx, s->n_threads, s->n_threads_batch);
    if (!ok) return out;
    env->DeleteLocalRef(out);
    out = env->NewDoubleArray(8);
    env->SetDoubleArrayRegion(out, 0, 8, v);
    return out;
}

// How this session runs, one "key: value" a line, for the benchmark report.
JNIEXPORT jstring JNICALL
Java_com_lfmpixel_lfm_llm_LlamaNative_runInfo(JNIEnv *env, jobject, jlong handle) {
    auto *s = reinterpret_cast<Session *>(handle);
    std::string out = "cpu variant: " + cpu_variant() + "\n";
    out += "cores: " + describe_cores() + "\n";
    std::string devs;
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        ggml_backend_dev_t d = ggml_backend_dev_get(i);
        if (!devs.empty()) devs += ", ";
        devs += std::string(ggml_backend_dev_name(d)) + " (" + ggml_backend_dev_description(d) + ")";
    }
    out += "backends: " + devs + "\n";
    if (s) {
        char buf[160];
        snprintf(buf, sizeof(buf), "threads: %d generating, %d reading the prompt\ncontext: %d tokens\n", s->n_threads,
                 s->n_threads_batch, (int) llama_n_ctx(s->ctx));
        out += buf;
        char desc[128];
        llama_model_desc(s->model, desc, sizeof(desc));
        out += std::string("model: ") + desc + "\n";
        // "llama_model_load_from_file_impl: CPU_REPACK model buffer size = 600.00 MiB" -> "CPU_REPACK 600.00 MiB"
        size_t pos = 0;
        const std::string &n = s->load_notes;
        while (pos < n.size()) {
            size_t end = n.find('\n', pos);
            if (end == std::string::npos) end = n.size();
            std::string line = n.substr(pos, end - pos);
            pos = end + 1;
            auto colon = line.find(": ");
            if (colon != std::string::npos) line = line.substr(colon + 2);
            while (!line.empty() && line.front() == ' ') line.erase(0, 1);
            if (!line.empty()) out += "llama.cpp: " + line + "\n";
        }
    }
    out += std::string("system: ") + llama_print_system_info() + "\n";
    return env->NewStringUTF(out.c_str());
}

} // extern "C"
