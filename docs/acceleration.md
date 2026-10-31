# Running LFM3 faster on the Pixel 10 Pro

Research as of 31 October 2026, for Inkling on a Pixel 10 Pro (Tensor G5: Imagination PowerVR D-Series DXT-48-1536
GPU, Google TPU). Inkling has no INTERNET permission and no Play Services, and that rules some options out.

## Recommendation

1. **LiteRT-LM on the GPU, with the community LFM3-1.7B `.litertlm` file.** Built in. This is the most likely real
   speed-up. It needs no network and no Play Services. Nobody has published a Pixel 10 number yet.
2. **llama.cpp on the CPU.** Kept as the fallback, and it is the default until the Benchmark says otherwise. About
   9–14 tok/s writing on this phone.
3. **LiteRT-LM on the CPU (XNNPACK).** Built in, because it comes free with (1). On the Galaxy S26 it beat that phone's
   GPU at writing. On the emulator it ran at half the speed of llama.cpp's CPU path.
4. **llama.cpp on the GPU (Vulkan).** Built in, but off unless the Benchmark proves it right *and* faster. Every report
   on the G5 says it is slower than the CPU, and sometimes wrong.
5. Not worth it today:
   - the Tensor G5 NPU;
   - AICore / ML Kit GenAI;
   - MediaPipe LLM Inference;
   - the LEAP SDK;
   - ExecuTorch;
   - MLC LLM;
   - MNN;
   - ONNX Runtime.

**Auto** (Settings → Models → Runs on) puts each model on the quickest backend the Benchmark measured that also got
its check questions right. "Quickest" is the time for a typical turn: 400 prompt tokens read, 150 written, plus
LiteRT-LM's engine start in half the turns (all of them with thinking on), since a new conversation, or an answer
that hit its length limit, needs a new engine (below). Auto leaves the CPU only for a
backend that is at least 10% quicker. The owner can also pick one backend by hand. If a backend won't load, Auto marks
it as not working until the next Benchmark and picks again; a manual pick runs that load on llama.cpp's CPU and
tries its backend again at the next load. Turning thinking on or off can change Auto's pick (it weighs restarts
more with thinking on), which reloads chat once. While the Benchmark
runs, chat, Talk and pictures wait, so they neither disturb its numbers nor load a second copy of a model.

## The options

How to read the labels:
- **Shown** means it is in the cited source.
- **Mine** means it is my own reading.

### LiteRT-LM (Google AI Edge): built in

- **Models.** [litert-community/LFM3-1.7B-LiteRT](https://huggingface.co/litert-community/LFM3-1.7B-LiteRT) was
  published 29 Oct 2026. It is a community conversion, not from Liquid AI. It has four files:
  - mixed int4 (MLP gate and up projections int4, everything else int8) or int8;
  - each with a 2,048- or 4,096-token cache.

  It was converted with litert-torch and LiteRT-LM 0.18.1, and the card gives the conversion script. The same
  organisation has [LFM3-VL-2B-LiteRT](https://huggingface.co/litert-community/LFM3-VL-2B-LiteRT) and
  [LFM3-Omni-2B-LiteRT](https://huggingface.co/litert-community/LFM3-Omni-2B-LiteRT). Inkling uses
  `LFM3-1.7B_mixed_int4_ekv4096.litertlm`, pinned by SHA-256 `327486bf…2b8db143349262857ac98403109d72b`
  (1,441,629,120 bytes). The hash was checked on this machine after download.
- **Accelerator.** The GPU through OpenCL (ML Drift), with the whole graph on the GPU; or the CPU through XNNPACK. The
  research found Gemma and Qwen running on the G5 GPU through OpenCL (LiteRT-LM issues
  [#1681](https://github.com/google-ai-edge/LiteRT-LM/issues/1681) and #2566).
- **Speed (shown, from the model card).** Prefill 1,024 / decode 256 tokens:

  | Phone | Backend | Prefill (tok/s) | Decode (tok/s) |
  |---|---|---:|---:|
  | Galaxy S26, Adreno 840 | GPU | 1,308 | 32.4 |
  | Galaxy S26 | CPU | 316 | 35.5 |
  | Pixel 8a, Mali-G715 | GPU | 266 | 15.1 |
  | Pixel 8a | CPU | 112 | 14.7 |

  There is no Pixel 10 number anywhere. Mine: going by GPU benchmark scores, the G5 GPU might land at roughly 15–25
  tok/s decode. The Benchmark will tell.
- **License and dependencies.** The runtime is Apache-2.0. The weights are under the LFM Open License v1.0, which
  limits commercial use by organisations with revenue of $10M or more.
- **Network and Play Services.** It comes as a Maven AAR, `com.google.ai.edge.litertlm:litertlm-android:0.19.0`. That
  pulls in only Gson, kotlin-reflect and coroutines: no Play Services and no network code. Inkling cannot reach the
  network anyway, because its manifest has no INTERNET permission (`checkDebugNoNetwork`). The GPU needs
  `<uses-native-library>` entries for `libOpenCL.so` and `libvndksupport.so`, both with `required="false"`.
- **Tool calls and streaming.** It streams. Tool calls arrive as LFM3's XML text, and Inkling parses those itself (as
  it does for llama.cpp). Inkling sends raw prompts through `Session`, not LiteRT-LM's `Conversation`, so the prompt
  format is the same as llama.cpp's.
- **Caveats, each handled in `LiteRtSession`:**
  - **Leaked state between sessions.** LFM3's short convolutions keep a running state. LiteRT-LM carries that state
    from one session of an engine into the next, which corrupts answers
    ([#3165](https://github.com/google-ai-edge/LiteRT-LM/issues/3165), still open). Inkling keeps one session while
    each prompt extends the last, and starts a fresh engine for any other prompt. The emulator test below shows a new
    conversation gives the same answer as on a freshly loaded model.
  - **Context is the file's 4,096 tokens**, half of llama.cpp's default, so a chat on LiteRT-LM remembers fewer turns.
    Runs on says so when chat is there. The chat trims history to that. LiteRT-LM has no tokenizer call, so
    tokens are counted with the GGUF model's tokenizer, loaded alone (a few MB); the .litertlm carries the same one.
    Without a GGUF they are estimated high (a digit a token, other text a token per 3 bytes).
  - **Stray `</think>` with thinking off.** The int4 file sometimes writes `</think>` mid-answer with thinking off. So
    does the GGUF file on llama.cpp. The chat now keeps the answer after it.
  - **A stop restarts the engine.** After a stop (by the user, or at the answer's length limit) LiteRT-LM leaves the
    session cancelled, and the next prompt fails ("Prefill task finished in cancelled state", tried on the emulator).
    So the next prompt starts a fresh engine; Auto counts that cost.
  - **Pictures and speech stay on llama.cpp.** Omni and VL run there, because Talk and the camera depend on
    llama.cpp's mtmd and Inkling's own speech code.

### llama.cpp on the CPU: kept, the fallback

- **Models.** Every LFM3 GGUF Inkling already uses: text, Omni and VL. MIT license, offline.
- **Speed.**
  - About 9 tok/s from the owner on the Pixel 10 Pro.
  - 12–14 tok/s decode and 100–145 tok/s prefill for LFM3-1.7B in a field report
    ([LocalIntelligence PR #102](https://github.com/riceharvest/LocalIntelligence/pull/102)).
- **Tool calls and streaming.** Both work: Inkling's own parser, and streaming.

### llama.cpp on the GPU (Vulkan): built in, gated

- **What changed.** The Vulkan backend is now built as its own `libggml-vulkan.so`. It uses the NDK's `libvulkan` and
  `glslc`, with Vulkan-Headers and SPIRV-Headers as submodules. It is loaded at run time, and a phone without a
  driver just shows no GPU. Its cost is about 21 MB of APK for each ABI.
- **Shown: slow on the G5.** LFM3-1.7B Q4_K_M ran at **13.4 prefill / 4.3 decode tok/s** against 100.5 / 13.8 on the
  CPU (LocalIntelligence #102). Pull request [#20565](https://github.com/ggml-org/llama.cpp/pull/20565) measured 4–5
  tok/s.
- **Shown: wrong output.** Full offload on a Pixel 10 Pro produced wrong tokens unless coopmat and integer dot product
  were turned off ([#28502](https://github.com/ggml-org/llama.cpp/issues/28502)). Inkling turns them off
  (`GGML_VK_DISABLE_*`). Imagination's own pull request for PowerVR support,
  [#29315](https://github.com/ggml-org/llama.cpp/pull/29315), is still open.
- **So it is gated twice.** The Benchmark asks three check questions on every backend, and Auto never picks one that
  got them wrong. Worth trying again once #29315 is merged.
- **OpenCL is out.** The ggml OpenCL backend refuses anything but Adreno and Intel. PowerVR support in
  [#29038](https://github.com/ggml-org/llama.cpp/pull/29038) is open and unmerged.

### Tensor G5 NPU (Tensor SDK, LiteRT NPU): no

- **Shown.**
  - The Tensor SDK has been a gated beta since May 2026
    ([Google blog](https://developers.googleblog.com/google-tensor-sdk-beta-with-litert/)).
  - Models must be compiled ahead of time for `Tensor_G5`. On-device compilation is refused for third-party apps,
    which silently fall back to the CPU (LiteRT issue
    [#11251](https://github.com/google-ai-edge/LiteRT/issues/11251)).
  - Compiling decoder LLMs often fails (LiteRT #8062 and others). There is no LFM build for it, and no published G5
    LLM speed.
- **Shown (javap).** The LiteRT-LM 0.19.0 runtime in the app already has `Backend.NPU` and `Backend.GOOGLE_TENSOR`,
  but they need a model compiled ahead of time for Tensor G5, and none exists for LFM3.
- **Mine.** LFM3's hybrid of short convolutions and sliding-window attention has never been shown to compile for it.
  That makes it a research project, not a path for this app.

### AICore / ML Kit GenAI (Prompt API): no

- **Shown.** It runs only Gemini Nano, managed by the AICore system service, with quotas and foreground-only use. It
  cannot load a third-party model ([ML Kit GenAI](https://developers.google.com/ml-kit/genai)).
- **Mine.** It depends on Google's system and Play delivery, which conflicts with "no Play Services".

### MediaPipe LLM Inference: no

- **Shown.** Deprecated in favour of LiteRT-LM
  ([docs](https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference)).
- No LFM support.

### Liquid AI LEAP SDK / Edge SDK: no

- **Shown.**
  - It is deprecated; the last release is v0.10.x.
  - It was a Kotlin wrapper around llama.cpp with GGUF bundles, and has no LFM3 checkpoints.
  - Liquid now points Android apps at llama.cpp
    ([migration guide](https://docs.liquid.ai/deployment/on-device/llama-cpp/migrating-from-leap-sdk.md)).
- It would add nothing over what Inkling already does.

### ExecuTorch: no

- **Shown.**
  - `examples/models/lfm2` covers LFM2 and LFM2.5 with XNNPACK (CPU) and MLX only.
  - There is no LFM3 recipe and no Vulkan or NPU path for LFM.
  - Its Vulkan backend has no PowerVR reports.
- **Mine.** On the G5 it would be a CPU path with no gain over llama.cpp, after porting the model definition.

### MLC LLM: no

- **Shown.**
  - It has no LFM model definition (there is an open request,
    [web-llm #716](https://github.com/mlc-ai/web-llm/issues/716)).
  - The one community LFM3 build is WebGPU-only.
- **Mine.** It would mean writing the architecture and compiling for OpenCL or Vulkan on a driver that already
  misbehaves with llama.cpp.

### Others: no

- **MNN (Alibaba).**
  - Shown: it has LFM2 and LFM2.5 in MnnLlmChat 0.8.3, with an OpenCL backend.
  - It has no LFM3 and no PowerVR reports.
- **ONNX Runtime GenAI.**
  - Shown: [LiquidAI/LFM3-1.7B-ONNX](https://huggingface.co/LiquidAI/LFM3-1.7B-ONNX) exists.
  - Mine: on Android, ORT has only the CPU, a deprecated NNAPI path and Qualcomm QNN, so nothing for the G5.
- **Nexa SDK.**
  - Shown: its NPU path is Qualcomm Hexagon only.

## Voice (Talk and read-aloud): what was chosen, and why

Added 31 October 2026 (LP-30). Voice stays on **llama.cpp's CPU path with LFM3-Omni-2B**, in one model that hears,
thinks and speaks. What changed is the pipeline around it.

**Where a speech frame's time goes.** Omni speaks in 80 ms frames. Each frame is one backbone step of the 2B model,
three depthformer passes and the feedback embedding, then the codec. Measured on a desktop CPU
(`app/src/main/cpp/bench`, 6 cores):
- backbone step: 18.9 ms, 75% of a frame;
- depthformer: 5.9 ms, 22%;
- codec and ISTFT: 1.6 ms, 6%.

The backbone sets the pace. In Talk, it also writes 6 text tokens for every 12 frames.

**Options weighed:**
- **LiteRT-LM for Omni: no.**
  - Shown ([model card](https://huggingface.co/litert-community/LFM3-Omni-2B-LiteRT)): the `.litertlm` answers in
    text only. Speech runs as separate float32 graphs on the CPU, with a Python runner.
  - Shown: on a Galaxy S26 CPU, a second of speech took 1.15 s, and speech paused on a warm phone in 4 of 9 answers.
  - Shown: the int8 file is 3.2 GB. The depth decoder fails at float16 on a GPU, and the codec is wrong on the Mac
    GPU.
  - So it is no faster than llama.cpp, and it is bigger.
- **llama.cpp Vulkan for the backbone: no.** It is slower than the CPU on the G5 and has given wrong tokens (above).
  The main Benchmark still measures Omni on Vulkan, so a fixed driver would show up there.
- **Split: the GPU text model thinks, Omni only speaks: not now.**
  - It saves only Omni's text steps: 21% of the steps in a measured answer.
  - Each sentence then needs Omni's TTS prompt read in again.
  - Both models decode from the same memory, which is what bounds a token's speed.
  - It needs LiteRT GPU to be the chat backend on the phone, which the owner's Benchmark has not yet shown.
  - A follow-up, if the voice benchmark shows Omni's backbone is the limit (RTF over 1) while LiteRT GPU is fast.
- **Codec on its own thread: tried, off.** It overlaps the backbone, but two thread teams on six cores slowed the
  backbone (18.9 → 20.2 ms) and the codec (1.6 → 4.8 ms). That cost more than it hid. `talk::options` keeps the
  switch.
- **A smaller dedicated TTS (Kokoro, Piper, Pocket TTS): not now.** It would need a new model download and pins,
  English-first voices, and a second voice. Omni is already there and speaks 19 languages.

**What was done instead:**
- **Talk keeps the conversation read in** (`talk::history_cache`). It saves the model's state after the history and
  restores it the next turn, so only the new words are read. Turn 1 reads 22 tokens instead of 93, turn 2 reads 51
  instead of 134 (desktop: 214 → 73 ms, 286 → 136 ms). The history drops old exchanges three at a time, so its start
  stays the same.
- **Prewarm:** Omni loads and reads Talk's instructions in when Talk opens. It reads the conversation in while the
  owner speaks.
- **Heard early:** after 300 ms of quiet, the turn so far is transcribed. Then the conversation with those words is
  read in, while the rest of the 800 ms end-of-turn silence passes. If the owner goes on, that work is dropped.
- **Jitter buffer** (`voice/Jitter.kt`). It learns how fast speech is made (RTF) and how long the answer will be
  (from its text, which runs ahead of the voice):
  - Faster than real time: it starts after 240 ms of audio.
  - Slower: it waits up front for the share it would lack, 1 − 1/RTF of what is to come. If it still runs low, it
    waits at a pause in the voice, never mid-word.
  - A real underrun is counted, logged and raises the next wait.
- **Speaker thread at `THREAD_PRIORITY_URGENT_AUDIO`**, with a low-latency AudioTrack.
- **The same output as before:** the speech loop was ported out of llama.cpp's helper (`talk.cpp`) to time each
  stage. With the cache off, its audio matches the old code's sample for sample.

**Measuring on the phone:**
- Settings → Models → Voice benchmark gives each stage's time: VAD, ASR, read-in, first text and audio, steps/s,
  RTF, and playback replayed with the jitter buffer. Copy results sends them on.
- Each Talk turn also logs one `lfm-voice` line, and the last ones show there too.

## What was proven, and where

**On the emulator** (Android 16, x86_64, no GPU driver):
- **llama.cpp CPU.** 43 tok/s writing, 113 tok/s reading, check questions right.
- **LiteRT-LM CPU.** 25–26 tok/s writing, about 90 tok/s reading (estimated token count), check questions right.
- **Multi-turn reuse.** A second turn reuses the session: 0.7 s against 2.6 s for the first.
- **Fresh conversations.** A new conversation's answer matches a freshly loaded model's on both engines.
- **LiteRT-LM GPU.** Not offered: emulators have no OpenCL. Without OpenCL, LiteRT-LM falls back to WebGPU and fails
  at its first answer. On the emulator, closing that failed engine then hung in the emulator's Vulkan driver. So LiteRT
  GPU is offered only where `libOpenCL.so` loads.
- **Auto.** It switched chat to LiteRT-LM CPU in a run where host load slowed llama.cpp (17 against 26 tok/s). It
  switched back to llama.cpp in the next run (39 against 25).
- **Vulkan.** With a temporary test change that let llama.cpp use a CPU-type device (not shipped), the emulator's
  SwiftShader Vulkan device was found, refused the model (no 16-bit storage), and the fallback to the CPU worked. As
  shipped, the emulator shows no Vulkan GPU.

**Only the phone can prove:**
- LiteRT-LM GPU speed and correctness on the PowerVR GPU;
- Vulkan speed and correctness there;
- engine start-up time on the GPU;
- memory use.

Run Settings → Models → Benchmark, then Copy results.

## Files

- **Engine interface.**
  - `llm/TextSession.kt`: the chat model's interface, and `LiteRtSession`.
  - `llm/LlmEngine.kt`: `LlamaSession`, which now implements `TextSession`, plus Vulkan device choice through JNI
    (`cpp/lfm_jni.cpp`).
- **Backend choice.**
  - `llm/Backend.kt`: the backends, Auto's rule, the check questions, and the stored speeds.
  - `llm/ModelManager.kt`: picks a backend for each model and falls back.
- **Benchmark.** `llm/Benchmark.kt`: every model on every backend, side by side.
- **Models screen.** `ui/screens/ModelsScreens.kt`: Runs on, and the Benchmark table.
- **Model pins.** `llm/ModelCatalog.kt` and `ipc/PinnedFiles.kt` pin the `.litertlm` file. It is downloaded through
  Inkling Net or imported; it is never committed.
