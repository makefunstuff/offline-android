#include "talk.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <deque>
#include <mutex>
#include <random>
#include <thread>
#include <vector>

namespace talk {
namespace {

using clock_type = std::chrono::steady_clock;

double ms_since(clock_type::time_point t) {
    return std::chrono::duration<double, std::milli>(clock_type::now() - t).count();
}

std::string text_piece(const llama_vocab *vocab, llama_token t) {
    char buf[256];
    // special = false: control tokens such as <|text_end|> print nothing
    int n = llama_token_to_piece(vocab, t, buf, sizeof(buf), 0, false);
    if (n < 0) {
        std::string big(-n, '\0');
        llama_token_to_piece(vocab, t, big.data(), -n, 0, false);
        return big;
    }
    return std::string(buf, n);
}

size_t incomplete_utf8_tail(const std::string &s) {
    size_t n = s.size();
    for (size_t back = 1; back <= 4 && back <= n; back++) {
        auto c = (unsigned char) s[n - back];
        if ((c & 0xC0) == 0x80) continue;
        size_t need = (c & 0x80) == 0 ? 1 : (c & 0xE0) == 0xC0 ? 2 : (c & 0xF0) == 0xE0 ? 3 : (c & 0xF8) == 0xF0 ? 4 : 1;
        return need > back ? back : 0;
    }
    return 0;
}

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text, bool add_special) {
    int n = -llama_tokenize(vocab, text.data(), (int32_t) text.size(), nullptr, 0, add_special, true);
    std::vector<llama_token> out(std::max(n, 0));
    if (n > 0) llama_tokenize(vocab, text.data(), (int32_t) text.size(), out.data(), n, add_special, true);
    return out;
}

llama_token find_special(const llama_vocab *vocab, const char *piece) {
    const int32_t n = llama_vocab_n_tokens(vocab);
    for (llama_token t = 0; t < n; t++) {
        if (std::string(piece) == llama_vocab_get_text(vocab, t)) return t;
    }
    return LLAMA_TOKEN_NULL;
}

// Restores the context's embeddings switch on the way out: speech needs the hidden state, chat does not.
struct embeddings_on {
    llama_context *ctx;
    explicit embeddings_on(llama_context *c) : ctx(c) { llama_set_embeddings(ctx, true); }
    ~embeddings_on() { llama_set_embeddings(ctx, false); }
};

// Text tokens at positions [pos, pos + n), in batches; only the last one gives an output.
bool decode_text(llama_context *lctx, const llama_token *tokens, int n, int pos) {
    const int n_batch = (int) llama_n_batch(lctx);
    llama_batch batch = llama_batch_init(n_batch, 0, 1);
    bool ok = true;
    for (int i = 0; i < n && ok; i += n_batch) {
        const int len = std::min(n_batch, n - i);
        for (int j = 0; j < len; j++) {
            batch.token[j] = tokens[i + j];
            batch.pos[j] = pos + i + j;
            batch.n_seq_id[j] = 1;
            batch.seq_id[j][0] = 0;
            batch.logits[j] = i + j == n - 1;
        }
        batch.n_tokens = len;
        ok = llama_decode(lctx, batch) == 0;
    }
    llama_batch_free(batch);
    return ok;
}

// LFM3-Audio speech out, as llama.cpp's mtmd-helper-gen.cpp does it (lfm3a_gen_audio_pipeline), with two changes:
// each stage is timed, and the codec decodes on a thread of its own while the backbone runs the next step. The
// depthformer and the codec share the projector's compute graph, so they take turns under `clip`; the backbone has
// its own and runs alongside.
class voice {
public:
    voice(llama_context *lctx, mtmd_context *mctx, result &r, const options &opt)
        : lctx(lctx), mctx(mctx), vocab(llama_model_get_vocab(llama_get_model(lctx))),
          n_embd(llama_model_n_embd(llama_get_model(lctx))), r(r), opt(opt) {}

    ~voice() {
        stop_codec();
        if (ext) llama_batch_ext_free(ext);
    }

    // `continuing`: the response follows a conversation and may switch between text and speech; otherwise the
    // prompt is the "tts" template and the response is speech only.
    bool init(bool continuing_, int mode_, uint32_t seed) {
        continuing = continuing_;
        mode = (gen_mode) mode_;
        slots_left = n_text_block;
        tok_audio_start = find_special(vocab, "<|audio_start|>");
        tok_mixed_start = find_special(vocab, "<|mixed_start|>");
        tok_text_end = find_special(vocab, "<|text_end|>");
        tok_im_end = find_special(vocab, "<|im_end|>");
        if (tok_audio_start == LLAMA_TOKEN_NULL || tok_mixed_start == LLAMA_TOKEN_NULL ||
            tok_text_end == LLAMA_TOKEN_NULL || tok_im_end == LLAMA_TOKEN_NULL) return false;
        const mtmd_gen_inp def = mtmd_gen_inp_default(mctx);
        temp = def.temp;
        top_k = def.top_k;
        rep_penalty = def.penalty_repeat;
        rep_window = def.penalty_last_n;
        rng.seed(seed);
        mtmd_gen_state_params sp = mtmd_gen_state_params_default();
        sp.seed = seed;
        state = mtmd_gen_state_init(mctx, sp);
        if (!state) return false;
        r.sample_rate = mtmd_gen_audio_get_info(mctx).sample_rate;
        if (opt.codec_thread) worker = std::thread([this] { codec_loop(); });
        return true;
    }

    bool next_is_text() const { return mode == MODE_TEXT || (mode == MODE_MIXED && !speaking); }
    bool text_only() const { return mode == MODE_TEXT; }

    // One step: a text token from the caller, or an audio frame from the hidden state. Returns false on an error.
    bool step(llama_token sampled, const float *h_in, const float **h_out, bool *stop, int &pos) {
        *h_out = nullptr;
        const bool interleaving = mode == MODE_MIXED;
        if (next_is_text()) {
            // <|im_end|> ends the response in any text slot, <|text_end|> outside an interleaved block too
            if (sampled == tok_im_end || (!interleaving && sampled == tok_text_end)) { *stop = true; return true; }
            if (!interleaving) {
                if (sampled == tok_audio_start) mode = MODE_AUDIO;
                else if (sampled == tok_mixed_start) { mode = MODE_MIXED; slots_left = n_text_block; speaking = false; text_done = false; }
            } else if (sampled == tok_text_end) {
                text_done = true;
                speaking = true;
            }
            auto t0 = clock_type::now();
            bool ok = decode_one(&sampled, nullptr, pos);
            r.text_ms += ms_since(t0);
            if (!ok) return false;
        } else {
            std::vector<int32_t> codes;
            const float *fb = nullptr;
            bool eos = false;
            auto t0 = clock_type::now();
            bool ok = sample_frame(h_in, codes, &fb, &eos);
            r.depth_ms += ms_since(t0);
            if (!ok) return false;
            if (eos) {
                flush_codec();
                if (!continuing) { *stop = true; return true; }
                // the segment ends, the codec tail goes out and the response continues in text
                mode = MODE_TEXT;
            } else {
                hist.insert(hist.end(), codes.begin(), codes.end());
                queue_codes(codes);
                r.n_frames++;
            }
            t0 = clock_type::now();
            ok = decode_one(nullptr, fb, pos);
            r.frame_ms += ms_since(t0);
            if (!ok) return false;
        }
        // a slot emitted while interleaving counts down the block, the switching token itself does not
        if (interleaving && !text_done && --slots_left == 0) {
            speaking = !speaking;
            slots_left = speaking ? n_audio_block : n_text_block;
        }
        const float *he = llama_get_embeddings_ith(lctx, -1);
        if (!he) return false;
        h_buf.assign(he, he + n_embd);
        *h_out = h_buf.data();
        return true;
    }

    // The frames not yet sent to the codec, and the ISTFT's held-back tail. Waits for the codec.
    void flush_codec() {
        if (!pend.empty()) { submit(std::move(pend)); pend.clear(); }
        if (had_audio) { submit({}); had_audio = false; }
        wait_codec();
    }

    // Stopped or failed: drops the frames still waiting and waits for the one being decoded.
    void drop_codec() {
        if (!worker.joinable()) return;
        std::unique_lock<std::mutex> lock(m);
        busy -= (int) jobs.size();
        jobs.clear();
        done_cv.wait(lock, [this] { return busy == 0; });
    }

    // Audio decoded since the last call. False on a codec error.
    bool take_audio(std::vector<float> &out) {
        std::lock_guard<std::mutex> lock(m);
        out.swap(pcm);
        pcm.clear();
        return !codec_failed;
    }

    std::vector<llama_logit_bias> text_only_bias() const {
        return {{tok_audio_start, -INFINITY}, {tok_mixed_start, -INFINITY}};
    }
    llama_token mixed_start() const { return tok_mixed_start; }
    llama_token audio_start() const { return tok_audio_start; }

private:
    enum gen_mode { MODE_TEXT = 0, MODE_AUDIO = 1, MODE_MIXED = 2 };
    static constexpr int n_cb = 12;
    static constexpr int schedule[] = {4, 4, 4}; // codebooks revealed per depthformer pass
    static constexpr int n_text_block = 6;       // interleaved_n_text
    static constexpr int n_audio_block = 12;     // interleaved_n_audio

    // One text token or one frame's feedback embedding at `pos`, with its output (as the helper feeds them).
    bool decode_one(const llama_token *tok, const float *embd, int &pos) {
        int rc;
        if (embd) {
            if (!ext) ext = llama_batch_ext_init(lctx);
            llama_batch_ext_clear(ext);
            const int32_t idx = llama_batch_ext_add_embd(ext, 0, llama_embd{embd, 1, (size_t) n_embd});
            if (idx < 0) return false;
            llama_pos p[4] = {pos, 0, 0, 0};
            llama_batch_ext_set_pos(ext, idx, p);
            llama_batch_ext_set_output_logits(ext, idx, true);
            rc = llama_process(lctx, LLAMA_PROCESS_TYPE_DECODE, ext);
        } else {
            llama_batch b = llama_batch_init(1, 0, 1);
            b.token[0] = *tok;
            b.pos[0] = pos;
            b.n_seq_id[0] = 1;
            b.seq_id[0][0] = 0;
            b.logits[0] = 1;
            b.n_tokens = 1;
            rc = llama_decode(lctx, b);
            llama_batch_free(b);
        }
        if (rc != 0) return false;
        pos++;
        return true;
    }

    // A random order of the codebooks, revealed by the schedule; fb is the feedback embedding of the codes. At the
    // end of audio every code is the end code; its feedback embedding is only built when continuing.
    bool sample_frame(const float *h, std::vector<int32_t> &codes, const float **fb, bool *is_eos) {
        int order[n_cb];
        for (int i = 0; i < n_cb; i++) order[i] = i;
        std::shuffle(order, order + n_cb, rng);
        codes.assign(n_cb, -1);
        mtmd_gen_inp inp = mtmd_gen_inp_default(mctx);
        inp.type = MTMD_GEN_PROCESS_TYPE_GEN_CODE;
        inp.embd = const_cast<float *>(h);
        inp.codes = codes.data();
        inp.n_codes = codes.size();
        mtmd_gen_out out{};
        std::unique_lock<std::mutex> lock(clip);
        int k = 0;
        for (int n_pick : schedule) {
            if (mtmd_gen_audio_process(mctx, state, &inp, &out) != 0 || out.n_feats == 0 || out.n_feats % n_cb != 0) return false;
            const size_t n_vocab = out.n_feats / n_cb;
            logits.assign(out.feats, out.feats + out.n_feats);
            for (int j = 0; j < n_pick; j++, k++) {
                const int i = order[k];
                float *l = logits.data() + (size_t) i * n_vocab;
                rep_pen(l, n_vocab, i);
                codes[i] = sample(l, n_vocab);
            }
        }
        const int32_t eos = (int32_t) (logits.size() / n_cb) - 1;
        *is_eos = std::find(codes.begin(), codes.end(), eos) != codes.end();
        if (*is_eos) {
            codes.assign(n_cb, eos);
            if (!continuing) { *fb = nullptr; return true; }
        }
        if (mtmd_gen_audio_process(mctx, state, &inp, &out) != 0) return false;
        fb_buf.assign(out.embd, out.embd + n_embd);
        *fb = fb_buf.data();
        return true;
    }

    void rep_pen(float *l, size_t n_vocab, int cb) {
        if (rep_penalty <= 1.0f || rep_window <= 0) return;
        const size_t n_hist = hist.size() / n_cb;
        const size_t n_win = std::min(n_hist, (size_t) rep_window);
        seen.assign(n_vocab, 0);
        for (size_t f = n_hist - n_win; f < n_hist; f++) {
            const int32_t c = hist[f * n_cb + (size_t) cb];
            if (c >= 0 && (size_t) c < n_vocab && !seen[(size_t) c]) {
                seen[(size_t) c] = 1;
                l[c] = l[c] > 0.0f ? l[c] / rep_penalty : l[c] * rep_penalty;
            }
        }
    }

    int32_t sample(const float *l, size_t n_vocab) {
        if (temp <= 0.0f) return (int32_t) (std::max_element(l, l + n_vocab) - l);
        // top-k keeps the codes scoring at least the k-th best, ties included
        float lmin = -INFINITY;
        if (top_k > 0 && (size_t) top_k < n_vocab) {
            sorted.assign(l, l + n_vocab);
            std::nth_element(sorted.begin(), sorted.begin() + (top_k - 1), sorted.end(), std::greater<float>());
            lmin = sorted[(size_t) top_k - 1];
        }
        const float lmax = *std::max_element(l, l + n_vocab);
        prob.resize(n_vocab);
        double sum = 0.0;
        for (size_t v = 0; v < n_vocab; v++) {
            prob[v] = l[v] < lmin ? 0.0 : std::exp((double) (l[v] - lmax) / temp);
            sum += prob[v];
        }
        double x = std::uniform_real_distribution<double>(0.0, 1.0)(rng) * sum;
        for (size_t v = 0; v < n_vocab; v++) {
            x -= prob[v];
            if (x <= 0.0) return (int32_t) v;
        }
        return (int32_t) n_vocab - 1;
    }

    // --- the codec ---------------------------------------------------------------------------------------------

    void queue_codes(const std::vector<int32_t> &codes) {
        pend.insert(pend.end(), codes.begin(), codes.end());
        had_audio = true;
        // The first frame goes alone, so the voice starts as soon as it can; then a few at a time.
        const int batch = first_sent ? std::max(1, opt.codec_batch) : 1;
        if ((int) (pend.size() / n_cb) >= batch) {
            submit(std::move(pend));
            pend.clear();
            first_sent = true;
        }
    }

    // Frames for the codec; an empty job flushes the ISTFT tail. Runs here without a codec thread.
    void submit(std::vector<int32_t> job) {
        if (!worker.joinable()) { run_codec(job); return; }
        std::lock_guard<std::mutex> lock(m);
        jobs.push_back(std::move(job));
        busy++;
        cv.notify_all();
    }

    void wait_codec() {
        if (!worker.joinable()) return;
        auto t0 = clock_type::now();
        std::unique_lock<std::mutex> lock(m);
        cv.notify_all();
        done_cv.wait(lock, [this] { return busy == 0; });
        r.codec_wait_ms += ms_since(t0);
    }

    void codec_loop() {
        for (;;) {
            std::vector<int32_t> job;
            {
                std::unique_lock<std::mutex> lock(m);
                cv.wait(lock, [this] { return quit || !jobs.empty(); });
                if (jobs.empty()) return;
                job = std::move(jobs.front());
                jobs.pop_front();
            }
            run_codec(job);
            std::lock_guard<std::mutex> lock(m);
            busy--;
            done_cv.notify_all();
        }
    }

    void run_codec(const std::vector<int32_t> &codes) {
        mtmd_gen_inp inp = mtmd_gen_inp_default(mctx);
        inp.type = MTMD_GEN_PROCESS_TYPE_GEN_WAV;
        inp.codes = codes.empty() ? nullptr : const_cast<int32_t *>(codes.data());
        inp.n_codes = codes.size();
        mtmd_gen_out out{};
        auto t0 = clock_type::now();
        std::unique_lock<std::mutex> lock(clip);
        const bool ok = mtmd_gen_audio_process(mctx, state, &inp, &out) == 0;
        std::vector<float> got;
        if (ok) got.assign(out.audio, out.audio + out.n_samples);
        lock.unlock();
        const double ms = ms_since(t0);
        std::lock_guard<std::mutex> l2(m);
        r.codec_ms += ms;
        if (!ok) codec_failed = true;
        pcm.insert(pcm.end(), got.begin(), got.end());
    }

    void stop_codec() {
        if (worker.joinable()) {
            {
                std::lock_guard<std::mutex> lock(m);
                quit = true;
                jobs.clear();
                cv.notify_all();
            }
            worker.join();
        }
        if (state) { mtmd_gen_state_free(state); state = nullptr; }
    }

    llama_context *lctx;
    mtmd_context *mctx;
    const llama_vocab *vocab;
    int n_embd;
    result &r;
    options opt;
    mtmd_gen_state *state = nullptr;
    llama_batch_ext *ext = nullptr;

    llama_token tok_audio_start = LLAMA_TOKEN_NULL, tok_mixed_start = LLAMA_TOKEN_NULL;
    llama_token tok_text_end = LLAMA_TOKEN_NULL, tok_im_end = LLAMA_TOKEN_NULL;
    bool continuing = false;
    gen_mode mode = MODE_AUDIO;
    int slots_left = 0;
    bool speaking = false;  // interleaving: the current block is audio
    bool text_done = false; // interleaving: <|text_end|> was emitted, audio until the end-of-audio frame

    float temp = 0.0f, rep_penalty = 1.0f;
    int32_t top_k = 0, rep_window = 0;
    std::mt19937 rng;
    std::vector<float> logits, fb_buf, h_buf, sorted;
    std::vector<double> prob;
    std::vector<char> seen;
    std::vector<int32_t> hist; // frame-major codes of the frames so far

    std::vector<int32_t> pend; // frames not yet handed to the codec
    bool had_audio = false;    // the ISTFT holds a tail to flush
    bool first_sent = false;

    std::mutex clip;           // the projector's graph: depthformer or codec, one at a time
    std::mutex m;              // jobs, pcm, busy, the codec's times
    std::condition_variable cv, done_cv;
    std::deque<std::vector<int32_t>> jobs;
    int busy = 0;
    bool quit = false;
    bool codec_failed = false;
    std::vector<float> pcm;
    std::thread worker;
};

// Sends the audio decoded so far. False when the listener asked to stop (or the codec failed).
bool drain(voice &v, const on_audio_fn &on_audio, result &r, clock_type::time_point t_call, std::vector<float> &buf) {
    if (!v.take_audio(buf)) { r.rc = -3; return false; }
    if (buf.empty()) return true;
    if (r.n_samples == 0) r.first_audio_ms = ms_since(t_call);
    r.n_samples += (int) buf.size();
    if (!on_audio(buf.data(), buf.size())) { r.rc = -6; return false; }
    return true;
}

bool same_start(const std::vector<llama_token> &a, const std::vector<llama_token> &prefix) {
    return prefix.size() <= a.size() && std::equal(prefix.begin(), prefix.end(), a.begin());
}

// Reads `tokens` in from the start, restoring the longest cached snapshot they start with. Keeps the model state
// after them in the cache, beside that snapshot. Returns the position after them, or -1.
int read_history(llama_context *lctx, history_cache *cache, const std::vector<llama_token> &tokens, result &r) {
    llama_memory_t mem = llama_get_memory(lctx);
    llama_memory_clear(mem, true);
    size_t from = 0;
    const history_cache::snap *base = nullptr;
    if (cache) {
        for (const auto &sn : cache->snaps) {
            if (sn.tokens.empty() || sn.tokens.size() > tokens.size() || !same_start(tokens, sn.tokens)) continue;
            if (base && sn.tokens.size() <= base->tokens.size()) continue;
            base = &sn;
        }
        if (base) {
            if (llama_state_seq_set_data(lctx, base->state.data(), base->state.size(), 0) == base->state.size()) {
                from = base->tokens.size();
            } else {
                llama_memory_clear(mem, true);
                base = nullptr;
            }
        }
    }
    r.n_reused = (int) from;
    r.n_prompt = (int) (tokens.size() - from);
    if (!decode_text(lctx, tokens.data() + from, (int) (tokens.size() - from), (int) from)) {
        llama_memory_clear(mem, true);
        if (cache) cache->clear();
        return -1;
    }
    if (cache) {
        history_cache::snap now;
        now.tokens = tokens;
        now.state.resize(llama_state_seq_get_size(lctx, 0));
        const bool ok = llama_state_seq_get_data(lctx, now.state.data(), now.state.size(), 0) == now.state.size();
        std::vector<history_cache::snap> keep;
        if (base && base->tokens.size() < tokens.size()) keep.push_back(*base);
        if (ok) keep.push_back(std::move(now));
        cache->snaps = std::move(keep);
    }
    return (int) tokens.size();
}

// The generation loop shared by speak and reply. `smpl` samples text tokens (null: speech only).
void generate(voice &v, llama_context *lctx, llama_sampler *smpl, int max_steps, int pos, const on_text_fn &on_text,
              const on_audio_fn &on_audio, const std::function<bool()> &cancel, result &r,
              clock_type::time_point t_call) {
    const llama_vocab *vocab = llama_model_get_vocab(llama_get_model(lctx));
    auto t0 = clock_type::now();
    const float *h = llama_get_embeddings_ith(lctx, -1);
    std::string pending;
    std::vector<float> buf;
    bool stop = false;
    for (int step = 0; !stop && step < max_steps; step++) {
        if (cancel()) { r.rc = -6; break; }
        llama_token sampled = LLAMA_TOKEN_NULL;
        if (v.next_is_text()) {
            if (!smpl) { r.rc = -3; break; }
            auto ts = clock_type::now();
            sampled = llama_sampler_sample(smpl, lctx, -1);
            llama_sampler_accept(smpl, sampled);
            r.text_ms += ms_since(ts);
            r.n_text++;
            pending += text_piece(vocab, sampled);
            size_t tail = incomplete_utf8_tail(pending);
            if (pending.size() > tail) {
                std::string out = pending.substr(0, pending.size() - tail);
                pending.erase(0, out.size());
                if (r.first_text_ms == 0 && !out.empty()) r.first_text_ms = ms_since(t_call);
                if (!on_text(out)) { r.rc = -6; break; }
            }
        }
        const float *h_next = nullptr;
        if (!v.step(sampled, h, &h_next, &stop, pos)) { r.rc = -3; break; }
        if (!drain(v, on_audio, r, t_call, buf)) break;
        if (!h_next) break;
        h = h_next;
    }
    if (r.rc == 0) {
        v.flush_codec();
        drain(v, on_audio, r, t_call, buf);
    } else {
        v.drop_codec();
    }
    r.gen_ms = ms_since(t0);
}

} // namespace

bool can_speak(mtmd_context *mctx) {
    return mctx && mtmd_gen_audio_get_info(mctx).type == MTMD_GEN_AUDIO_TYPE_LFM3A;
}

result speak(llama_context *lctx, mtmd_context *mctx, const std::string &prompt, int max_frames,
             const on_audio_fn &on_audio, const std::function<bool()> &cancel, const options &opt) {
    result r;
    auto t_call = clock_type::now();
    if (!can_speak(mctx)) { r.rc = -1; return r; }
    const llama_vocab *vocab = llama_model_get_vocab(llama_get_model(lctx));
    embeddings_on emb(lctx);
    // The start token, as the reference prompt has it (this vocab does not add it by itself).
    std::vector<llama_token> tokens = tokenize(vocab, prompt, true);
    const llama_token bos = llama_vocab_bos(vocab);
    if (bos != LLAMA_TOKEN_NULL && (tokens.empty() || tokens[0] != bos)) tokens.insert(tokens.begin(), bos);
    if (tokens.empty()) { r.rc = -3; return r; }
    if ((int) tokens.size() + max_frames + 8 >= (int) llama_n_ctx(lctx)) { r.rc = -2; return r; }
    voice v(lctx, mctx, r, opt);
    if (!v.init(false, 1 /* audio */, 42) || tokens.back() != v.audio_start()) { r.rc = -3; return r; }
    auto t0 = clock_type::now();
    const int pos = read_history(lctx, nullptr, tokens, r);
    r.prompt_ms = ms_since(t0);
    if (pos < 0) { r.rc = -3; return r; }
    generate(v, lctx, nullptr, max_frames, pos, [](const std::string &) { return true; }, on_audio, cancel, r, t_call);
    llama_memory_clear(llama_get_memory(lctx), true);
    return r;
}

result reply(llama_context *lctx, mtmd_context *mctx, history_cache *cache, const std::string &history,
             const std::string &reply_prefix, int max_steps, float temp, bool spoken, const on_text_fn &on_text,
             const on_audio_fn &on_audio, const std::function<bool()> &cancel, const options &opt) {
    result r;
    auto t_call = clock_type::now();
    if (!can_speak(mctx)) { r.rc = -1; return r; }
    const llama_vocab *vocab = llama_model_get_vocab(llama_get_model(lctx));
    std::vector<llama_token> hist = tokenize(vocab, history, true);
    std::vector<llama_token> prefix = tokenize(vocab, reply_prefix, false);
    if (hist.empty() || prefix.empty()) { r.rc = -3; return r; }
    if ((int) (hist.size() + prefix.size()) + max_steps + 64 >= (int) llama_n_ctx(lctx)) { r.rc = -2; return r; }

    voice v(lctx, mctx, r, opt);
    // The assistant turn opens in mixed mode: blocks of text, then of speech, until the text ends; then speech.
    // Text only: the switches into speech are ruled out.
    if (!v.init(true, spoken ? 2 /* mixed */ : 0 /* text */, 42)) { r.rc = -3; return r; }
    if (spoken) prefix.push_back(v.mixed_start());

    // The conversation so far, read as plain text (embeddings off: only the last token needs an output).
    auto t0 = clock_type::now();
    int pos = read_history(lctx, cache, hist, r);
    if (pos < 0) { r.rc = -3; return r; }
    embeddings_on emb(lctx);
    if (!decode_text(lctx, prefix.data(), (int) prefix.size(), pos)) {
        llama_memory_clear(llama_get_memory(lctx), true);
        r.rc = -3;
        return r;
    }
    pos += (int) prefix.size();
    r.n_prompt += (int) prefix.size();
    r.prompt_ms = ms_since(t0);

    // Text tokens are sampled here, with the switches into speech ruled out when it is text only.
    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!spoken) {
        auto bias = v.text_only_bias();
        llama_sampler_chain_add(smpl, llama_sampler_init_logit_bias(llama_vocab_n_tokens(vocab), (int32_t) bias.size(), bias.data()));
    }
    if (temp <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(50));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(42));
    }
    generate(v, lctx, smpl, max_steps, pos, on_text, on_audio, cancel, r, t_call);
    llama_sampler_free(smpl);
    llama_memory_clear(llama_get_memory(lctx), true);
    return r;
}

result prewarm(llama_context *lctx, history_cache *cache, const std::string &history) {
    result r;
    auto t0 = clock_type::now();
    const llama_vocab *vocab = llama_model_get_vocab(llama_get_model(lctx));
    std::vector<llama_token> tokens = tokenize(vocab, history, true);
    if (tokens.empty()) { r.rc = -3; return r; }
    if (cache) {
        for (const auto &sn : cache->snaps) if (sn.tokens == tokens) return r; // already in
    }
    if (read_history(lctx, cache, tokens, r) < 0) r.rc = -3;
    llama_memory_clear(llama_get_memory(lctx), true);
    r.prompt_ms = ms_since(t0);
    return r;
}

} // namespace talk
