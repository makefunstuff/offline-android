#include "talk.h"

#include <algorithm>
#include <chrono>
#include <vector>

#include "mtmd-helper.h"

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

// Restores the context's embeddings switch on the way out: speech needs the hidden state, chat does not.
struct embeddings_on {
    llama_context *ctx;
    explicit embeddings_on(llama_context *c) : ctx(c) { llama_set_embeddings(ctx, true); }
    ~embeddings_on() { llama_set_embeddings(ctx, false); }
};

// Sends the audio made since the last call. False when the listener asked to stop.
bool drain(mtmd_helper::gen_audio &gen, const on_audio_fn &on_audio, result &r) {
    int32_t rate = 0;
    const char *data = nullptr;
    size_t len = 0;
    int64_t n = 0;
    if (gen.get_output(&rate, &data, &len, &n) != 0) return true;
    r.sample_rate = rate;
    if (len == 0) return true;
    r.n_samples += (int) (len / sizeof(float));
    return on_audio(reinterpret_cast<const float *>(data), len / sizeof(float));
}

std::string bos_text(const llama_vocab *vocab) {
    llama_token bos = llama_vocab_bos(vocab);
    if (bos == LLAMA_TOKEN_NULL) return {};
    char buf[64];
    int n = llama_token_to_piece(vocab, bos, buf, sizeof(buf), 0, true);
    return n > 0 ? std::string(buf, n) : std::string();
}

} // namespace

bool can_speak(mtmd_context *mctx) {
    return mctx && mtmd_gen_audio_get_info(mctx).type == MTMD_GEN_AUDIO_TYPE_LFM3A;
}

result speak(llama_context *lctx, mtmd_context *mctx, const std::string &prompt, int max_frames,
             const on_audio_fn &on_audio, const std::function<bool()> &cancel) {
    result r;
    if (!can_speak(mctx)) { r.rc = -1; return r; }
    const llama_vocab *vocab = llama_model_get_vocab(llama_get_model(lctx));
    llama_memory_clear(llama_get_memory(lctx), true);
    embeddings_on emb(lctx);

    const std::string full = bos_text(vocab) + prompt;
    mtmd_helper::gen_audio gen;
    gen.init(lctx, mctx);
    mtmd_helper_gen_audio_inp inp = mtmd_helper_gen_audio_inp_default();
    inp.stream = true;
    inp.seq_id = 0;
    inp.prompt = full.c_str();
    inp.prompt_len = full.size();
    inp.seed = 42;
    inp.out_type = MTMD_HELPER_GEN_AUDIO_OUTTYPE_PCM;
    if (gen.set_input(&inp) != 0) { r.rc = -3; return r; }

    auto t0 = clock_type::now();
    for (;;) {
        int32_t left = gen.step_prompt((int32_t) llama_n_batch(lctx));
        if (left < 0) { r.rc = -3; return r; }
        if (left == 0) break;
    }
    r.prompt_ms = ms_since(t0);

    t0 = clock_type::now();
    const float *h = llama_get_embeddings_ith(lctx, -1);
    bool stop = false;
    while (!stop && r.n_frames < max_frames) {
        if (cancel()) { r.rc = -6; break; }
        const float *h_next = nullptr;
        if (gen.step_gen(LLAMA_TOKEN_NULL, h, &h_next, &stop) != 0) { r.rc = -3; break; }
        if (!h_next) break;
        h = h_next;
        r.n_frames++;
        if (!drain(gen, on_audio, r)) { r.rc = -6; break; }
    }
    if (r.rc == 0) {
        gen.flush();
        drain(gen, on_audio, r);
    }
    r.gen_ms = ms_since(t0);
    llama_memory_clear(llama_get_memory(lctx), true);
    return r;
}

result reply(llama_context *lctx, mtmd_context *mctx, const std::string &history, const std::string &reply_prefix,
             int max_steps, float temp, bool spoken, const on_text_fn &on_text, const on_audio_fn &on_audio,
             const std::function<bool()> &cancel) {
    result r;
    if (!can_speak(mctx)) { r.rc = -1; return r; }
    const llama_vocab *vocab = llama_model_get_vocab(llama_get_model(lctx));
    llama_memory_t mem = llama_get_memory(lctx);
    llama_memory_clear(mem, true);

    // The conversation so far, read as plain text (embeddings off: only the last token needs an output).
    auto t0 = clock_type::now();
    int n = -llama_tokenize(vocab, history.data(), (int32_t) history.size(), nullptr, 0, true, true);
    std::vector<llama_token> tokens(std::max(n, 0));
    if (n > 0) llama_tokenize(vocab, history.data(), (int32_t) history.size(), tokens.data(), n, true, true);
    if (tokens.empty()) { r.rc = -3; return r; }
    if ((int) tokens.size() + max_steps + 64 >= (int) llama_n_ctx(lctx)) { r.rc = -2; return r; }
    const int n_batch = (int) llama_n_batch(lctx);
    for (int i = 0; i < (int) tokens.size(); i += n_batch) {
        int len = std::min(n_batch, (int) tokens.size() - i);
        if (llama_decode(lctx, llama_batch_get_one(tokens.data() + i, len)) != 0) {
            llama_memory_clear(mem, true);
            r.rc = -3;
            return r;
        }
    }

    // The assistant turn opens in mixed mode: blocks of text, then of speech, until the text ends; then speech.
    embeddings_on emb(lctx);
    mtmd_helper::gen_audio gen;
    gen.init(lctx, mctx);
    mtmd_helper_gen_audio_inp inp = mtmd_helper_gen_audio_inp_default();
    inp.stream = true;
    inp.seq_id = 0;
    inp.prompt = reply_prefix.c_str();
    inp.prompt_len = reply_prefix.size();
    inp.n_past = (int32_t) tokens.size();
    inp.out_mode = spoken ? MTMD_HELPER_GEN_AUDIO_OUT_MIXED : MTMD_HELPER_GEN_AUDIO_OUT_TEXT;
    inp.seed = 42;
    inp.out_type = MTMD_HELPER_GEN_AUDIO_OUTTYPE_PCM;
    if (gen.set_input(&inp) != 0) { r.rc = -3; return r; }
    for (;;) {
        int32_t left = gen.step_prompt(n_batch);
        if (left < 0) { r.rc = -3; return r; }
        if (left == 0) break;
    }
    r.prompt_ms = ms_since(t0);

    // Text tokens are sampled here, with the helper's bias (no stray mode switches).
    auto sparams = llama_sampler_chain_default_params();
    llama_sampler *smpl = llama_sampler_chain_init(sparams);
    std::vector<llama_logit_bias> bias = gen.logit_bias();
    if (!bias.empty()) {
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

    t0 = clock_type::now();
    const float *h = llama_get_embeddings_ith(lctx, -1);
    std::string pending;
    bool stop = false;
    for (int step = 0; !stop && step < max_steps; step++) {
        if (cancel()) { r.rc = -6; break; }
        llama_token sampled = LLAMA_TOKEN_NULL;
        if (gen.next_is_text()) {
            sampled = llama_sampler_sample(smpl, lctx, -1);
            llama_sampler_accept(smpl, sampled);
            r.n_text++;
            pending += text_piece(vocab, sampled);
            size_t tail = incomplete_utf8_tail(pending);
            if (pending.size() > tail) {
                std::string out = pending.substr(0, pending.size() - tail);
                pending.erase(0, out.size());
                if (!on_text(out)) { r.rc = -6; break; }
            }
        }
        const float *h_next = nullptr;
        bool was_audio = sampled == LLAMA_TOKEN_NULL;
        if (gen.step_gen(sampled, h, &h_next, &stop) != 0) { r.rc = -3; break; }
        if (was_audio) r.n_frames++;
        if (!drain(gen, on_audio, r)) { r.rc = -6; break; }
        if (!h_next) break;
        h = h_next;
    }
    llama_sampler_free(smpl);
    if (r.rc == 0) {
        gen.flush();
        drain(gen, on_audio, r);
    }
    r.gen_ms = ms_since(t0);
    llama_memory_clear(mem, true);
    return r;
}

} // namespace talk
