// Speech out with LFM3-Omni-2B, in-process: read-aloud (text to speech) and Talk (a spoken answer with its text).
// Plain C++ with no JNI, so the same code runs in the app and in a desktop test (bench/talk_bench.cpp).

#pragma once

#include <cstdint>
#include <functional>
#include <string>
#include <vector>

#include "llama.h"
#include "mtmd.h"

namespace talk {

// New audio, 24 kHz mono float PCM. Return false to stop.
using on_audio_fn = std::function<bool(const float *pcm, size_t n)>;
// A finished UTF-8 piece of the spoken answer's text. Return false to stop.
using on_text_fn = std::function<bool(const std::string &piece)>;

struct result {
    int rc = 0;          // 0 done, <0 error (-1 no speech-out part, -2 too long, -3 decode failed, -6 stopped)
    int n_frames = 0;    // audio frames generated (each 80 ms)
    int n_text = 0;      // text tokens generated
    int n_samples = 0;   // PCM samples sent
    int sample_rate = 0;
    double prompt_ms = 0, gen_ms = 0;
    // Where the time went, for the voice benchmark and the log.
    int n_prompt = 0;          // prompt tokens read in by this call
    int n_reused = 0;          // prompt tokens restored from the conversation cache instead
    double first_text_ms = 0;  // from the call to the first text piece
    double first_audio_ms = 0; // from the call to the first audio sent
    double text_ms = 0;        // backbone steps that made text tokens (sampling included)
    double frame_ms = 0;       // backbone steps that read an audio frame back in
    double depth_ms = 0;       // depthformer passes (the frame's 12 codes) and the feedback embedding
    double codec_ms = 0;       // codec decoder and ISTFT (on its own thread, it overlaps the backbone)
    double codec_wait_ms = 0;  // the backbone waited this long for the codec thread
};

// The conversation read in so far and the model's state after it, so the next Talk turn reads in only what is new.
// Two snapshots: the latest, and the one it grew from. A turn heard early that turned out wrong (the owner went on)
// then still finds the conversation before it.
struct history_cache {
    struct snap {
        std::vector<llama_token> tokens;
        std::vector<uint8_t> state;
    };
    std::vector<snap> snaps; // at most 2; each earlier one is a prefix of the later
    void clear() { snaps.clear(); }
};

struct options {
    // The codec decodes on its own thread while the backbone works on the next step (it shares no compute with it).
    // Off: the codec is about 6% of a frame, and a second thread team slows the backbone more than that (bench/).
    bool codec_thread = false;
    // Frames per codec call: fewer calls cost less overhead, each frame waits a little longer to be heard.
    int codec_batch = 1;
};

// True if the projector has Omni's speech-out part.
bool can_speak(mtmd_context *mctx);

// Speaks `prompt`, which is the model's "tts" chat template already filled in (see SpeechFormat.kt).
// Clears the context first. `cancel` is polled between frames.
result speak(llama_context *lctx, mtmd_context *mctx, const std::string &prompt, int max_frames,
             const on_audio_fn &on_audio, const std::function<bool()> &cancel, const options &opt = {});

// One Talk turn: `history` is the conversation so far in the chat format, up to and including the new user turn;
// `reply_prefix` opens the assistant turn ("<|im_start|>assistant\n<think></think>"). The model answers with its
// text and speech interleaved; with `spoken` false, text only (faster). With a `cache`, the part of `history` read in
// by the last turn (or by prewarm) is restored instead of read again, and the new history is kept there.
result reply(llama_context *lctx, mtmd_context *mctx, history_cache *cache, const std::string &history,
             const std::string &reply_prefix, int max_steps, float temp, bool spoken, const on_text_fn &on_text,
             const on_audio_fn &on_audio, const std::function<bool()> &cancel, const options &opt = {});

// Reads `history` (the start of the next turn's: Talk's instructions) in and keeps it in `cache`, so the first turn
// starts sooner. Leaves the context clear.
result prewarm(llama_context *lctx, history_cache *cache, const std::string &history);

} // namespace talk
