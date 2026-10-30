# LFM3 on Pixel: spec

A private, fully offline Android app for the owner's Pixel 10 Pro, built on Liquid AI's LFM3 models. This is the
design for review; no app code is written yet.

Research date: 30 October 2026, three days after LFM3 came out. Almost every LFM3 figure below is Liquid's own; no
independent tests exist yet. Figures marked *estimate* are mine, to be measured in Phase 0.

## 1. What LFM3 really is

Liquid AI released LFM3 on 27 October 2026: six open-weight models of about 2B parameters, all built from one base,
LFM3-1.7B-Base, which is published too [1][7]. LFM3 is real, and it is a good fit for a phone. The family is small, though: there is no larger
LFM3 model to fall back on.

| Model | Size | In | Out | Context | Notes |
|---|---|---|---|---|---|
| **LFM3-1.7B** (text) | 1.73B | text | text | 256K | Reasoning ("thinking") on by default, and turning it off "can be inconsistent"; tool calls; 16 languages [2] |
| **LFM3-VL-2B** (vision) | 1.92B (1.73B LM + 164M vision encoder) | text, image, video | text | 128K | DocVQA 96.7, OCRBench v1 86.3, ChartQA 92.7; video at 2 fps; "not recommended for … multi-turn conversations" [3] |
| **LFM3-Audio-2B** | 2B | text, 16 kHz audio | text, 24 kHz speech | 32K | ASR: 6.23 WER (English), 6.03 (FLEURS, 9 languages); live ASR and voice chat are English only [4] |
| **LFM3-Omni-2B** | 2.17B | text, image, video, audio | text, speech, both mixed | 32K | Non-reasoning; ASR 6.36 WER (English), 6.72 (FLEURS); female or male voice; speech in 19 languages/locales; translation best to or from English [5] |
| d1-2B, d1-Omni-2B | ~2B | (as VL / Omni) | typed answers with probabilities | 128K (d1-2B) | Built for routing and tagging; not needed here [6] |

- **Formats.** Liquid publishes GGUF for all four main models: Q4_0, Q4_K_M, Q8_0, F16 and BF16 for each, plus
  Q5_K_M and Q6_K for the text and VL models. There are ONNX and MLX builds of all four, and
  FP8 and NVFP4 for text and VL [7]. Q4_0 of the text and VL models is a quantization-aware checkpoint trained to
  recover what 4-bit loses [8][10].
- **Sizes on disk (Q4).** Text 1.05–1.1 GB. VL 1.05–1.1 GB plus a 203 MB image projector (`mmproj`, Q8_0). Omni
  1.1 GB plus a 292 MB projector that holds its vision, audio-in and speech-out parts (Q4_K_M). Audio 1.1 GB plus a
  172 MB projector [8][9][10][11].
- **A LiteRT build** of the text model exists (int4 and int8, 2K or 4K context only) [12]. I found no ExecuTorch or
  MediaPipe build on Hugging Face.
- **Phone fit.** At 4-bit the text model takes about 1 GB [1]. Liquid reports 43.6 tokens/s decode on an iPhone 17
  Pro with llama.cpp Q4_0 at 32K context, using 1.9 GB (Liquid's chart, as relayed by [13]). The KV cache at 128K
  context is 1.6 GB [1].
- **Licence.** LFM Open License v1.0. Free for personal and research use; an entity with US$10M or more in yearly
  revenue is not licensed for commercial use [14]. A private app for the owner is fine; selling it later needs a check.
- **Honest limits.** The model cards say the text and audio models are "not recommended for agentic coding or complex
  multi-step reasoning" and that knowledge-heavy questions want retrieval [2][4]. The text model trails Qwen3.5-9B on
  knowledge (GPQA 63.4 vs 80.4) and long-context recall (RULER 128K 65.6 vs 87.1) [1]. Treat it as a quick, private
  helper, not an oracle.

## 2. Running it on Android

| Runtime | LFM3 support | Pixel 10 Pro acceleration | Verdict |
|---|---|---|---|
| **llama.cpp** (NDK + JNI) | All four models as GGUF (b11960+); images and audio in through `mtmd` and the projector file [8][9][10]. Omni speech out through the `llama-tts` tool (language and voice options), and live voice conversation through the `/v1/realtime` WebSocket of `llama serve` [9] | CPU (arm64, runtime feature detection). Vulkan and OpenCL backends exist but "need device-specific testing"; OpenCL targets Adreno, and the Pixel's GPU is an Imagination PowerVR DXT-48-1536 [15][16]. No Tensor TPU backend | **Use this.** Liquid's own docs now point here [17] |
| Liquid LEAP SDK | Deprecated, frozen at v0.10.x, not updated for new checkpoints [18] | | No |
| LiteRT-LM | Text model only, 2K/4K context, CPU and GPU; no NPU build [12] | GPU yes. Tensor TPU access is in beta (May 2026) but the only language models it names are Gemma 3 1B and FunctionGemma [19] | Later option for faster text-only chat |
| MediaPipe LLM Inference | No LFM3 build found | | No |
| ExecuTorch | No LFM3 export found | | No |
| ONNX Runtime | ONNX builds of all four [7] | CPU | No: no gain over llama.cpp |

**Speech out without a network.** An app without the `INTERNET` permission cannot open network sockets, not even to
itself on `localhost`, so the `llama serve` endpoints cannot be used as they are. Read-aloud is low risk: `llama-tts`
is a standalone tool whose code can be called in-process through JNI. Live Talk is the real risk: its streaming
turn-taking lives in the server's `/v1/realtime` code, which would have to be driven in-process too. Phase 0 proves
both. Fallback for read-aloud only: Android's system text-to-speech with an offline voice. That hands the answer text
to the system speech app (a separate app that does have network), so it would be an opt-in setting, off by default.

**Speed and memory estimates for the Pixel 10 Pro** (Tensor G5: 1× Cortex-X4, 5× Cortex-A725, 2× A520; 16 GB RAM
[16]). Nobody has published LFM3 numbers on this chip. Nearby points: LiteRT LFM3-1.7B on a Pixel 8a gives 12–15
tokens/s and on a Galaxy S26 27–36 tokens/s [12]; llama.cpp on an iPhone 17 Pro gives 43.6 [13]. *Estimate* for
llama.cpp Q4 on the G5's CPU: about 20–30 tokens/s decode and a few hundred tokens/s prefill, using about 2–3 GB of
memory for Omni plus its projector at a few thousand tokens of context. Sustained runs will throttle. The GPU is
untested for this: the only data point is one unconfirmed 2025 Vulkan score, well below the Pixel 9 Pro's and possibly
a driver issue [20]. So the plan starts on the CPU and treats the GPU as a bonus. Phase 0 measures all of this with
`llama-bench`.

## 3. Getting models on while the app stays offline

The app ships **without the `INTERNET` permission**. Android then refuses every network socket the app opens, so no
code in it (ours or a library's) can talk to the network directly. This is enforced by the operating system, not by a
promise. The one way out left is handing data to another app (an intent or a service call); the app does that only
when the owner taps Export or Share, exports no services, and shows web links as text to copy, never as tappable links.

Models arrive one of two ways, both owner-driven:

1. **Import from the phone.** The owner downloads the GGUF files in the phone's browser (the app shows the Hugging
   Face links as plain text to copy) or copies them over USB. In the app, Models → Import opens Android's file picker;
   the app copies the files into its private storage, then offers to delete the downloaded originals so they don't take space twice.
2. **Sideload for development.** `adb push` into the app's import folder.

Each known model has a pinned SHA-256 and size in the app. An imported file is checked before it can be used; an
unknown file can still be used, labelled "unverified". Models and all user data live in app-private storage
(encrypted at rest by Android's file-based encryption).

## 4. Use cases, ranked

Ranked by everyday use offline, then by how proven the pieces are on a phone (speech out, the least proven, last but one).

| # | Use case | Model | Honest limits |
|---|---|---|---|
| 1 | **Ask about this**: point the camera, or share a photo or screenshot, and ask | Omni | Reads text and scenes well; facts about what it sees may be wrong; no web lookups |
| 2 | **Voice notes to summaries**: record, get a transcript, summary and to-dos | Omni; Audio for live captions (optional) | 32K context means long recordings are cut into parts and summarised in steps; live captions English only |
| 3 | **Read a document**: screenshots, photos of pages, PDFs → extract text, summarise, translate | VL extracts the text (best OCR, 128K); questions about it go to the text model or Omni | VL is not meant for back-and-forth, so it does one pass per page. *Estimate*: a 20-page PDF takes minutes. Handwriting untested |
| 4 | **Private chat and journal**: talk it through, write entries, ask "what did I say about…" | Text 1.7B (256K, reasoning); Omni for short chats until it is imported | Reasoning makes answers slower; RULER 128K is 65.6, so recall over very long journals is weak; plain search finds entries first |
| 5 | **Talk hands-free**: voice in, spoken answer out | Omni | Omni lists 19 speech languages, but voice chat quality outside English is unstated; in-process speech out is unproven (Phase 0); drains battery |
| 6 | **Translate**: text, photo or speech | Omni | Best to or from English; not for legal or medical text |

The default install is **one model, LFM3-Omni-2B (about 1.4 GB with its projector at Q4_K_M)**, which covers cases
1, 2, 5 and 6. The text and VL models are optional add-ons for cases 3 and 4.

## 5. App design

### Screens and flows

1. **Home.** Five large modes: Ask, Notes, Read, Chat, Talk. Below: recent items. A small "Offline" badge always on.
2. **Ask (camera).** Full-screen CameraX view, a shutter, and a question field (type or hold to speak). Shared images
   from other apps (share sheet → "Ask LFM") open here with the picture already in.
3. **Answer / chat thread.** Streaming answer, the picture as a thumbnail, follow-up field, stop button, a speaker
   icon to read the answer aloud. Each answer shows which model gave it.
4. **Notes.** List of voice notes; a big record button. Recording works with the screen off (foreground service).
   After recording: transcript, summary, to-dos, each editable.
5. **Read.** Pick a PDF or images, or share a screenshot. Shows page thumbnails, extracted text, and a question field.
6. **Chat / Journal.** Plain chat with the text model; journal entries are chats marked "journal", searchable.
7. **Talk.** One big circle: tap to talk, tap to stop. Live transcript under it.
8. **Models.** Installed models with size, quantisation, verified mark, "in use"; Import, Delete, and Set default per
   mode. Shows storage used and free.
9. **Privacy.** States the guarantees below and shows proof: "Network permission: not granted" (read from the app's
   own package info), data size, Export (to a file the owner picks), Delete everything.
10. **Settings.** Threads, context size, answer length, thinking on/off, thermal behaviour, app lock (biometric).

The numbers in the mock-ups (speeds, sizes in use, counts) are illustrative, not measured.

### Model management

- Import (above), with a progress bar for the copy and hash check. A model and its projector import as one unit.
- One model loaded at a time; switching to a mode that needs another model unloads and loads (a few seconds,
  shown). Weights are memory-mapped, which Android's memory manager treats more leniently than regular app memory [15].
- Delete removes the files and frees space at once. Using a mode whose model is missing shows "Import model" instead.

### Privacy guarantees and how they are enforced

| Guarantee | Enforced by |
|---|---|
| No network at runtime, ever | No `INTERNET` permission in the merged manifest; a build check fails if any dependency adds it |
| No telemetry, no crash reporting, no cloud fallback | No Firebase, Play Services, analytics or crash SDKs; dependency allow-list checked in the build |
| Data stays on the phone | `allowBackup="false"`, data-extraction rules exclude everything; app-private storage only. Data leaves only when the owner taps Export or Share, which hand a file to an app the owner picks |
| Camera and microphone only when asked | A design rule, checked in code review: camera only on the Ask screen, microphone only while a recording or Talk is visibly running. Android's own privacy dots show both |
| You can see and wipe it all | Privacy screen: sizes, export, delete everything |
| Optional lock | Biometric lock on open |

### Battery and heat

- Inference uses 6 threads, aimed at the performance cores rather than the A520s (Android has the last word on scheduling).
- The app listens to Android's thermal status and headroom (`PowerManager`). At moderate heat it lowers threads and
  says so; at severe it pauses background jobs (long transcriptions, PDF reading) and resumes when cool.
- Long jobs run in a foreground service with a notification and a stop button; optionally only while charging.
- The model unloads after a few idle minutes to free memory.

### Tech stack

- Kotlin, Jetpack Compose, Material 3 with a flat custom theme (no elevation shadows, no gradients).
- llama.cpp as a git submodule, built with the NDK and CMake for `arm64-v8a`, `LLAMA_BUILD_MTMD=ON`, behind a small
  JNI layer exposing load, generate (streamed), image and audio input, and speech output [15].
- CameraX for the camera; `AudioRecord` at 16 kHz mono for input; `AudioTrack` for 24 kHz speech; `PdfRenderer` for
  PDFs.
- Room for threads, notes and journal; DataStore for settings.
- minSdk 36 (Android 16), since it targets one phone. Sideloaded APK, not the Play Store.

## 6. Build plan (follow-up tasks)

1. **Phase 0, spike.** A bare app with llama.cpp + `mtmd` on the Pixel: measure decode, prefill, memory and heat for
   text, VL and Omni at Q4_0, Q4_K_M and Q8_0; test the Vulkan backend on the PowerVR GPU; prove Omni audio in and
   speech out in-process (no sockets); battery drain per answer and per minute of Talk. Output: numbers and a
   go/no-go for Talk and read-aloud.
2. **Phase 1, core.** App shell, no-`INTERNET` build check, model import and verify, Models screen, chat with Omni and
   the text model.
3. **Phase 2, Ask + Translate.** Camera and share-sheet image questions with Omni; text and photo translation.
4. **Phase 3, Notes.** Recording service, transcription, chunked summaries, to-dos. Optional live captions with
   Audio-2B.
5. **Phase 4, Read.** PDFs and screenshots with VL.
6. **Phase 5, Talk.** Voice in, speech out, spoken translation (if Phase 0 says go).
7. **Phase 6, polish.** Thermal handling, app lock, export and wipe, Journal search.

## 7. Open questions for the owner

1. Is "no network permission; you import model files yourself" acceptable, or do you want a separate download
   helper app (it would have network; this app still would not)?
2. Default model: Omni only (about 1.4 GB), or Omni, VL and text from the start (about 3.7 GB at Q4)?
3. Q4 (smaller, faster) or Q8 (likely more accurate; Omni about 2.4 GB and VL about 2.1 GB with projectors) as the default?
4. Which languages matter to you? Live captions are English only, and voice quality outside English is unknown.
5. Is live Talk (hands-free voice) a must, or fine to drop if Phase 0 shows it is slow or cannot run in-process?
   Read-aloud would stay.
6. Journal: do you want an app lock and a separate encrypted store, or is the phone's own lock enough?
7. Sideloaded APK only, or should it be ready for the Play Store some day (affects the licence check)?

## Sources

1. Liquid AI, "Introducing LFM3: Every Modality for Specialized Intelligence", 27 Oct 2026 — https://www.liquid.ai/blog/lfm3
2. LFM3-1.7B model card — https://huggingface.co/LiquidAI/LFM3-1.7B
3. LFM3-VL-2B model card — https://huggingface.co/LiquidAI/LFM3-VL-2B
4. LFM3-Audio-2B model card — https://huggingface.co/LiquidAI/LFM3-Audio-2B
5. LFM3-Omni-2B model card — https://huggingface.co/LiquidAI/LFM3-Omni-2B
6. d1-2B model card — https://huggingface.co/LiquidAI/d1-2B
7. LiquidAI LFM3 model list — https://huggingface.co/models?author=LiquidAI&search=LFM3
8. LFM3-1.7B-GGUF — https://huggingface.co/LiquidAI/LFM3-1.7B-GGUF
9. LFM3-Omni-2B-GGUF (files) — https://huggingface.co/LiquidAI/LFM3-Omni-2B-GGUF/tree/main
10. LFM3-VL-2B-GGUF (files) — https://huggingface.co/LiquidAI/LFM3-VL-2B-GGUF/tree/main
11. LFM3-Audio-2B-GGUF (files) — https://huggingface.co/LiquidAI/LFM3-Audio-2B-GGUF/tree/main
12. LiteRT build with Pixel 8a and Galaxy S26 numbers — https://huggingface.co/litert-community/LFM3-1.7B-LiteRT
13. eesel.ai, "LFM3: the 2B family, benchmarks, speed, and the catch" (relays Liquid's device numbers) — https://www.eesel.ai/blog/lfm3
14. LFM Open License v1.0 — https://huggingface.co/LiquidAI/LFM3-1.7B/blob/main/LICENSE
15. Liquid docs, llama.cpp on iOS and Android — https://docs.liquid.ai/deployment/on-device/llama-cpp/mobile
16. Pixel 10 Pro specifications — https://en.wikipedia.org/wiki/Pixel_10_Pro
17. Liquid docs, migrating from LEAP SDK — https://docs.liquid.ai/deployment/on-device/llama-cpp/migrating-from-leap-sdk
18. Liquid docs, LEAP SDK (deprecated) — https://docs.liquid.ai/deployment/on-device/sdk/overview
19. Google, "Google Tensor SDK Beta with LiteRT", 19 May 2026 — https://developers.googleblog.com/en/google-tensor-sdk-beta-with-litert/
20. TechNews, Tensor G5 GPU Vulkan score — https://technews.tw/2025/08/26/googles-tensor-g5-processor-gpu-performance-is-poor/
