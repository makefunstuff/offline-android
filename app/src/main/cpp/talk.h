// Speech out with LFM3-Omni-2B, in-process: read-aloud (text to speech) and Talk (a spoken answer with its text).
// Plain C++ with no JNI, so the same code runs in the app and in a desktop test.

#pragma once

#include <functional>
#include <string>

#include "llama.h"
#include "mtmd.h"

namespace talk {

// New audio, 24 kHz mono float PCM. Return false to stop.
using on_audio_fn = std::function<bool(const float *pcm, size_t n)>;
// A finished UTF-8 piece of the spoken answer's text. Return false to stop.
using on_text_fn = std::function<bool(const std::string &piece)>;

struct result {
    int rc = 0;          // 0 done, <0 error (-1 no speech-out part, -2 too long, -3 decode failed, -6 stopped)
    int n_frames = 0;    // audio frames generated (each about 80 ms)
    int n_text = 0;      // text tokens generated
    int n_samples = 0;   // PCM samples sent
    int sample_rate = 0;
    double prompt_ms = 0, gen_ms = 0;
};

// True if the projector has Omni's speech-out part.
bool can_speak(mtmd_context *mctx);

// Speaks `prompt`, which is the model's "tts" chat template already filled in (see SpeechFormat.kt).
// Clears the context first. `cancel` is polled between frames.
result speak(llama_context *lctx, mtmd_context *mctx, const std::string &prompt, int max_frames,
             const on_audio_fn &on_audio, const std::function<bool()> &cancel);

// One Talk turn: `history` is the conversation so far in the chat format, up to and including the new user turn;
// `reply_prefix` opens the assistant turn ("<|im_start|>assistant\n<think></think>"). The model answers with its
// text and speech interleaved; with `spoken` false, text only (faster). Clears the context first.
result reply(llama_context *lctx, mtmd_context *mctx, const std::string &history, const std::string &reply_prefix,
             int max_steps, float temp, bool spoken, const on_text_fn &on_text, const on_audio_fn &on_audio,
             const std::function<bool()> &cancel);

} // namespace talk
