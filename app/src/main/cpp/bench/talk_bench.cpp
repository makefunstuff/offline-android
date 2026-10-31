// Desktop check of talk.cpp: speaks a question, hears it back (ASR), then answers it twice in a conversation, with
// each stage's time. Not part of the app. Build: see bench/README.md.
//   talk_bench <model.gguf> <mmproj.gguf> <threads> <out-prefix> [codec_thread 0|1] [codec_batch]

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include "talk.h"

using clk = std::chrono::steady_clock;
static double ms(clk::time_point t) { return std::chrono::duration<double, std::milli>(clk::now() - t).count(); }

static const char *SYS = "<|im_start|>system\nYou are Inkling, a private assistant that runs on the owner's phone. You are "
    "talking out loud with the owner. Answer in British English, in one to three short spoken sentences, without lists, "
    "Markdown or web links. If you do not know, say so. Today is Saturday 31 October 2026.<|im_end|>\n";
static const char *PREFIX = "<|im_start|>assistant\n<think></think>";

static void save(const std::string &path, const std::vector<float> &pcm) {
    FILE *f = fopen(path.c_str(), "wb");
    fwrite(pcm.data(), 4, pcm.size(), f);
    fclose(f);
}

static void report(const char *what, const talk::result &r) {
    const double audio_ms = r.n_samples * 1000.0 / (r.sample_rate ? r.sample_rate : 24000);
    printf("%s: rc %d, prompt %d tok (+%d reused) %.0f ms, first text %.0f ms, first audio %.0f ms, %d text + %d frames "
           "in %.0f ms; audio %.1f s, RTF %.2f\n  text %.0f ms (%.1f ms/tok), frame %.0f ms (%.1f/fr), depth %.0f ms "
           "(%.1f/fr), codec %.0f ms (%.1f/fr), waited for codec %.0f ms\n",
           what, r.rc, r.n_prompt, r.n_reused, r.prompt_ms, r.first_text_ms, r.first_audio_ms, r.n_text, r.n_frames,
           r.gen_ms, audio_ms / 1000, audio_ms > 0 ? r.gen_ms / audio_ms : 0, r.text_ms,
           r.n_text ? r.text_ms / r.n_text : 0, r.frame_ms, r.n_frames ? r.frame_ms / r.n_frames : 0, r.depth_ms,
           r.n_frames ? r.depth_ms / r.n_frames : 0, r.codec_ms, r.n_frames ? r.codec_ms / r.n_frames : 0,
           r.codec_wait_ms);
}

int main(int argc, char **argv) {
    if (argc < 5) { fprintf(stderr, "usage: talk_bench model mmproj threads out-prefix [codec_thread] [codec_batch]\n"); return 1; }
    const int threads = atoi(argv[3]);
    const std::string out = argv[4];
    talk::options opt;
    if (argc > 5) opt.codec_thread = atoi(argv[5]) != 0;
    if (argc > 6) opt.codec_batch = atoi(argv[6]);
    llama_log_set([](ggml_log_level l, const char *t, void *) { if (l >= GGML_LOG_LEVEL_WARN) fputs(t, stderr); }, nullptr);
    mtmd_helper_log_set([](ggml_log_level l, const char *t, void *) { if (l >= GGML_LOG_LEVEL_WARN) fputs(t, stderr); }, nullptr);
    llama_backend_init();
    auto mp = llama_model_default_params();
    llama_model *model = llama_model_load_from_file(argv[1], mp);
    auto cp = llama_context_default_params();
    cp.n_ctx = 4096;
    cp.n_batch = 512;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    llama_context *ctx = llama_init_from_model(model, cp);
    auto pp = mtmd_context_params_default();
    pp.use_gpu = false;
    pp.n_threads = threads;
    pp.print_timings = false;
    pp.warmup = false;
    mtmd_context *mctx = mtmd_init_from_file(argv[2], model, pp);
    if (!ctx || !mctx) { fprintf(stderr, "load failed\n"); return 1; }
    printf("threads %d, codec thread %d, codec batch %d\n", threads, opt.codec_thread, opt.codec_batch);
    auto never = [] { return false; };

    // 1. The question, spoken by Omni itself.
    std::vector<float> q24;
    std::string tts = "<|im_start|>system\nPerform TTS in British English using a female voice.<|im_end|>\n<|im_start|>user\n"
        "What is a nice thing to cook for dinner tonight?<|im_end|>\n<|im_start|>assistant\n<think></think><|audio_start|>";
    talk::result r = talk::speak(ctx, mctx, tts, 200, [&](const float *p, size_t n) { q24.insert(q24.end(), p, p + n); return true; }, never, opt);
    report("tts", r);
    save(out + "-question.f32", q24);

    // 2. Heard back: 24 kHz to 16 kHz, then Omni's ASR as the app does it.
    std::vector<float> q16(q24.size() * 2 / 3);
    for (size_t i = 0; i < q16.size(); i++) {
        double x = i * 1.5; size_t k = (size_t) x; double f = x - k;
        q16[i] = (float) (q24[k] * (1 - f) + (k + 1 < q24.size() ? q24[k + 1] : 0) * f);
    }
    {
        auto t0 = clk::now();
        std::string prompt = "<|im_start|>system\nPerform ASR in English.<|im_end|>\n<|im_start|>user\n<__media__><|im_end|>\n<|im_start|>assistant\n<think></think>";
        mtmd_bitmap *bmp = mtmd_bitmap_init_from_audio(q16.size(), q16.data());
        mtmd_input_chunks *chunks = mtmd_input_chunks_init();
        mtmd_input_text text{prompt.c_str(), prompt.size(), true, true};
        const mtmd_bitmap *bm[] = {bmp};
        mtmd_tokenize(mctx, chunks, &text, bm, 1);
        llama_memory_clear(llama_get_memory(ctx), true);
        llama_pos n_past = 0;
        mtmd_helper_eval_chunks(mctx, ctx, chunks, 0, 0, 512, true, &n_past);
        double enc = ms(t0);
        auto t1 = clk::now();
        llama_sampler *s = llama_sampler_init_greedy();
        const llama_vocab *vocab = llama_model_get_vocab(model);
        std::string said;
        int n = 0;
        for (; n < 100; n++) {
            llama_token t = llama_sampler_sample(s, ctx, -1);
            if (llama_vocab_is_eog(vocab, t)) break;
            char buf[128]; int k = llama_token_to_piece(vocab, t, buf, sizeof buf, 0, false); said.append(buf, k > 0 ? k : 0);
            llama_batch b = llama_batch_get_one(&t, 1);
            llama_decode(ctx, b);
        }
        llama_sampler_free(s);
        mtmd_bitmap_free(bmp);
        mtmd_input_chunks_free(chunks);
        printf("asr: %.1f s of audio, audio in + prompt %.0f ms (%d positions), %d tokens in %.0f ms: %s\n", q16.size() / 16000.0, enc,
               (int) n_past, n, ms(t1), said.c_str());
    }

    // 3. Two turns of conversation; the second reuses the first's history.
    talk::history_cache cache;
    talk::result w = talk::prewarm(ctx, &cache, SYS);
    printf("prewarm: %d tokens in %.0f ms, state %zu KB\n", w.n_prompt, w.prompt_ms, (cache.snaps.empty() ? 0 : cache.snaps.back().state.size()) / 1024);
    // Heard early, then the owner went on: a wrong turn read in must not cost the conversation before it.
    w = talk::prewarm(ctx, &cache, std::string(SYS) + "<|im_start|>user\nWhat is a nice<|im_end|>\n");
    printf("early (wrong) turn: %d tokens (+%d reused)\n", w.n_prompt, w.n_reused);
    std::string hist = std::string(SYS) + "<|im_start|>user\nWhat is a nice thing to cook for dinner tonight?<|im_end|>\n";
    for (int turn = 1; turn <= 2; turn++) {
        std::vector<float> a;
        std::string text;
        // NOCACHE=1: every turn reads the whole conversation, as before (for a sample-exact comparison).
        r = talk::reply(ctx, mctx, getenv("NOCACHE") ? nullptr : &cache, hist, PREFIX, 1200, 0.3f, true,
                        [&](const std::string &p) { text += p; return true; },
                        [&](const float *p, size_t n) { a.insert(a.end(), p, p + n); return true; }, never, opt);
        char name[32]; snprintf(name, sizeof name, "turn %d", turn);
        report(name, r);
        printf("  text: %s\n", text.c_str());
        save(out + "-turn" + std::to_string(turn) + ".f32", a);
        hist += std::string(PREFIX) + text + "<|im_end|>\n<|im_start|>user\nAnd how long does that take?<|im_end|>\n";
    }
    mtmd_free(mctx);
    llama_free(ctx);
    llama_model_free(model);
    return 0;
}
