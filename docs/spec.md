# LFM3 on Pixel: spec

A private, fully offline Android app for the owner's Pixel 10 Pro, built on Liquid AI's LFM3 models. This is the
design for review; no app code is written yet.

Research date: 30 October 2026, three days after LFM3 came out. Almost every LFM3 figure below is Liquid's own; no
independent tests exist yet.

## 1. What LFM3 really is

Liquid AI released LFM3 on 27 October 2026: six open-weight models of about 2B parameters, all built from one base,
LFM3-1.7B-Base [1][2]. LFM3 is real, and it is a good fit for a phone. The family is small, though: there is no
larger LFM3 model to fall back on.

| Model | Size | In | Out | Context | Notes |
|---|---|---|---|---|---|
| **LFM3-1.7B** (text) | 1.73B | text | text | 256K | Reasoning on by default; tool calls; 16 languages [3] |
| **LFM3-VL-2B** (vision) | 1.92B (1.73B LM + 164M vision encoder) | text, image, video | text | 128K | DocVQA 96.7, OCRBench v1 86.3, ChartQA 92.7; video at 2 fps [4] |
| **LFM3-Audio-2B** | 2B | text, 16 kHz audio | text, 24 kHz speech | 32K | ASR: 6.23 WER (English), 6.03 (FLEURS, 9 languages); live ASR and voice chat are English only [5] |
| **LFM3-Omni-2B** | 2.17B | text, image, video, audio | text, speech, both mixed | 32K | Non-reasoning; female or male voice; speech in 19 languages/locales; translation best to or from English [6] |
| d1-2B, d1-Omni-2B | ~2B | (as VL / Omni) | structured output | | Built for routing and tagging; not needed here [1][7] |

- **Formats.** Liquid publishes GGUF (Q4_0, Q4_K_M, Q5_K_M, Q6_K, Q8_0, BF16), ONNX, MLX, FP8 and NVFP4 builds for
  each model [8]. The text model's Q4_K_M is 1.1 GB; Q4_0 (1.05 GB) is a quantization-aware checkpoint trained to
  recover what 4-bit loses [9]. The Omni and VL GGUFs are the same sizes, plus a projector (`mmproj`) file for the
  image and audio parts [10][11]. A LiteRT build of the text model exists (int4 and int8, 2K or 4K context only)
  [12]. There is no ExecuTorch build.
- **Phone fit.** At 4-bit the text model takes about 1 GB [2]. Liquid reports 43.6 tokens/s decode on an iPhone 17
  Pro with llama.cpp Q4_0 at 32K context, using 1.9 GB [13]. The KV cache at 128K context is 1.6 GB [2].
- **Licence.** LFM Open License v1.0. Free for personal and research use; commercial use is not licensed for an
  entity above US$10M yearly revenue [14]. A private app for the owner is fine; selling the app later needs a check.
- **Honest limits.** The model cards say these models are "not recommended for agentic coding or complex multi-step
  reasoning" and that knowledge-heavy questions want retrieval [3][5]. It trails Qwen3.5-9B on knowledge (GPQA
  63.4 vs 80.4) and long-context recall (RULER 128K 65.6 vs 87.1) [2]. Early users report Q4 errors that Q8 does not
  make [13]. Treat it as a quick, private helper, not an oracle.

## 2. Running it on Android

| Runtime | LFM3 support | Pixel 10 Pro acceleration | Verdict |
|---|---|---|---|
| **llama.cpp** (NDK + JNI) | All four models as GGUF; vision and audio through `mtmd` and the projector file; Omni speech output and a realtime voice API in recent builds (b11960+) [9][10][11] | CPU (arm64, runtime feature detection). Vulkan and OpenCL backends exist but need per-device testing; OpenCL targets Adreno, and the Pixel's GPU is an Imagination PowerVR DXT-48-1536 [15][16] | **Use this.** Liquid's own docs now point here [17] |
| Liquid LEAP SDK | Deprecated, frozen at v0.10.x, not updated for new checkpoints [18] | (a wrapper around llama.cpp) | No |
| LiteRT-LM | Text model only, 2K/4K context, CPU and GPU; no NPU build [12] | GPU yes. Tensor TPU access is in beta (May 2026) but only Gemma 3 1B is named as an LLM [19] | Later option for faster text-only chat |
| ExecuTorch | No LFM3 export published | | No |
| ONNX Runtime | ONNX builds of all four [8] | CPU; NNAPI is deprecated | No |

**Speed and memory estimates for the Pixel 10 Pro** (Tensor G5: 1× Cortex-X4, 5× Cortex-A725, 2× A520; 16 GB RAM
[16]). Nobody has published LFM3 numbers on this chip. Nearby points: LiteRT LFM3-1.7B on a Pixel 8a gives 12–15
tokens/s and on a Galaxy S26 30–35 tokens/s [12]; llama.cpp on an iPhone 17 Pro gives 43.6 [13]. My estimate for
llama.cpp Q4 on the G5's CPU: **about 20–30 tokens/s decode** and a few hundred tokens/s prefill, with about 2–3 GB
used for the Omni model and its projector at a few thousand tokens of context. Sustained runs will throttle;
reviews call the G5's GPU weak [20], so the GPU is not counted on. Phase 0 measures this for real with `llama-bench`.

## 3. Getting models on while the app stays offline

The app ships **without the `INTERNET` permission**. Android then refuses every socket the app opens, so no code
in it (ours or a library's) can reach the network. This is enforced by the operating system, not by a promise.

Models arrive one of two ways, both owner-driven:

1. **Import from the phone.** The owner downloads a GGUF in the phone's browser (from the Hugging Face links the app
   shows as plain text) or copies it over USB. In the app, Models → Import opens Android's file picker; the app copies
   the file into its private storage.
2. **Sideload for development.** `adb push` into the app's import folder.

Each known model has a pinned SHA-256 and size in the app. An imported file is checked before it can be used; an
unknown file can still be used, labelled "unverified". Models and all user data live in app-private storage
(encrypted at rest by Android's file-based encryption).

## 4. Use cases, ranked

| # | Use case | Model | Honest limits |
|---|---|---|---|
| 1 | **Ask about this**: point the camera, or share a photo or screenshot, and ask | Omni (default); VL for detail | Reads text and scenes well; facts about what it sees may be wrong; no web lookups |
| 2 | **Voice notes to summaries**: record, get a transcript, summary and to-dos | Omni (ASR + summary); Audio for live captions | 32K context means long recordings are cut into parts and summarised in steps; live captions English only |
| 3 | **Read a document**: screenshots, photos of pages, PDFs → extract, summarise, ask, translate | VL (best OCR, 128K) | Page images cost many tokens; a 20-page PDF is slow (minutes); handwriting untested |
| 4 | **Private chat and journal**: talk it through, write entries, ask "what did I say about…" | Text 1.7B (256K, reasoning); Omni for short chats until it is imported | Long-context recall weakens past ~32K; reading in a large journal takes minutes; plain search finds entries first |
| 5 | **Talk hands-free**: voice in, spoken answer out | Omni | Voice chat is English only; full speech-out on Android is unproven (Phase 0 spike); it drains battery |
| 6 | **Translate**: text, photo or speech | Omni | Best to or from English; no guarantee of quality for legal or medical text |

The default install is **one model, LFM3-Omni-2B (~1.1 GB + projector)**, which covers cases 1, 2, 5 and 6. The
text and VL models are optional add-ons for cases 3 and 4.

## 5. App design

### Screens and flows

1. **Home.** Five large modes: Ask, Notes, Read, Chat, Talk. Below: recent items. A small "Offline" badge always on.
2. **Ask (camera).** Full-screen CameraX view, a shutter, and a question field (type or hold to speak). Shared images
   from other apps (share sheet → "Ask LFM") open here with the picture already in.
3. **Answer / chat thread.** Streaming answer, the picture as a thumbnail, follow-up field, stop button, a speaker
   icon to read the answer aloud (Omni speech). Each thread shows which model answered.
4. **Notes.** List of voice notes; a big record button. Recording works with the screen off (foreground service).
   After recording: transcript, summary, to-dos, each editable.
5. **Read.** Pick a PDF or images, or share a screenshot. Shows page thumbnails, extracted text, and a question field.
6. **Chat / Journal.** Plain chat with the text model; journal entries are chats marked "journal", searchable.
7. **Talk.** One big circle: tap to talk, tap to stop. Live transcript under it. English only.
8. **Models.** Installed models with size, quantisation, verified mark, "in use"; Import, Delete, and Set default per
   mode. Shows storage used and free.
9. **Privacy.** States the guarantees below and shows proof: "Network permission: not granted" (read from the app's
   own package info), data size, Export (to a file the owner picks), Delete everything.
10. **Settings.** Threads, context size, answer length, thermal behaviour, app lock (biometric).

### Model management

- Import (above), with a progress bar for the copy and hash check.
- One model loaded at a time; switching mode to one that needs another model unloads and loads (a few seconds,
  shown). Weights are memory-mapped, so Android treats them as file pages, not app memory [21].
- Delete removes the file and frees space at once. Using a mode whose model is missing shows "Import model" instead.

### Privacy guarantees and how they are enforced

| Guarantee | Enforced by |
|---|---|
| No network at runtime, ever | No `INTERNET` permission in the merged manifest; a build check fails if any dependency adds it |
| No telemetry, no crash reporting, no cloud fallback | No Firebase, Play Services, analytics or crash SDKs; dependency allow-list checked in the build |
| Data stays on the phone | `allowBackup="false"`, data-extraction rules exclude everything; app-private storage only |
| Only what you ask is seen | Camera and microphone used only while their screen is open or a recording is visibly running |
| You can see and wipe it all | Privacy screen: sizes, export, delete everything |
| Optional lock | Biometric lock on open |

### Battery and heat

- Inference runs on the performance cores only (6 threads), never the A520s.
- The app listens to Android's thermal status and headroom (`PowerManager`). At moderate heat it lowers threads and
  says so; at severe it pauses background jobs (long transcriptions, PDF reading) and resumes when cool.
- Long jobs run in a foreground service with a notification and a stop button; optionally only while charging.
- The model unloads after a few idle minutes to free memory.

### Tech stack

- Kotlin, Jetpack Compose, Material 3 with a flat custom theme (no elevation shadows, no gradients).
- llama.cpp as a git submodule, built with the NDK and CMake for `arm64-v8a`, `LLAMA_BUILD_MTMD=ON`, behind a small
  JNI layer exposing load, generate (streamed), image and audio input, and speech output [21].
- CameraX for the camera; `AudioRecord` at 16 kHz mono for input; `AudioTrack` for 24 kHz speech; `PdfRenderer` for
  PDFs; video frames (if ever) via `MediaMetadataRetriever` rather than ffmpeg.
- Room for threads, notes and journal; DataStore for settings.
- minSdk 36 (Android 16), since it targets one phone. Sideloaded APK, not the Play Store.

## 6. Build plan (follow-up tasks)

1. **Phase 0, spike.** A bare app with llama.cpp + `mtmd` on the Pixel: measure decode/prefill/memory/heat for the
   three models at Q4_0, Q4_K_M and Q8_0; test the Vulkan backend on the PowerVR GPU; prove Omni speech in and out
   in-process. Output: numbers and a go/no-go for Talk.
2. **Phase 1, core.** App shell, no-`INTERNET` build check, model import and verify, Models screen, chat with the
   text model.
3. **Phase 2, Ask.** Camera and share-sheet image questions with Omni.
4. **Phase 3, Notes.** Recording service, transcription, chunked summaries, to-dos.
5. **Phase 4, Read.** PDFs and screenshots with VL.
6. **Phase 5, Talk + Translate.** Voice in, speech out (if Phase 0 says go).
7. **Phase 6, polish.** Thermal handling, app lock, export and wipe, Journal search.

## 7. Open questions for the owner

1. Is "no network permission; you import model files yourself" acceptable, or do you want a separate download
   helper app (it would have network; this app still would not)?
2. Default model: Omni only (1.1 GB, covers most), or all three from the start (~3.4 GB)?
3. Q4 (smaller, faster, some errors) or Q8 (1.85 GB each, more accurate) as the default?
4. Which languages matter to you? Voice chat and live captions are English only.
5. Is Talk (hands-free voice) a must, or fine to drop if Phase 0 shows it is slow?
6. Journal: do you want an app lock and a separate encrypted store, or is the phone's own lock enough?
7. Sideloaded APK only, or should it be ready for the Play Store some day (affects the licence check)?

## Sources

1. Liquid AI, "Introducing LFM3: Every Modality for Specialized Intelligence", 27 Oct 2026 — https://www.liquid.ai/blog/lfm3
2. Liquid AI blog (benchmarks, KV cache, footprint) — https://www.liquid.ai/blog/lfm3
3. LFM3-1.7B model card — https://huggingface.co/LiquidAI/LFM3-1.7B
4. LFM3-VL-2B model card — https://huggingface.co/LiquidAI/LFM3-VL-2B
5. LFM3-Audio-2B model card — https://huggingface.co/LiquidAI/LFM3-Audio-2B
6. LFM3-Omni-2B model card — https://huggingface.co/LiquidAI/LFM3-Omni-2B
7. d1 models on Hugging Face — https://huggingface.co/LiquidAI/d1-2B
8. LiquidAI LFM3 model list — https://huggingface.co/models?author=LiquidAI&search=LFM3
9. LFM3-1.7B-GGUF — https://huggingface.co/LiquidAI/LFM3-1.7B-GGUF
10. LFM3-Omni-2B-GGUF — https://huggingface.co/LiquidAI/LFM3-Omni-2B-GGUF
11. LFM3-VL-2B-GGUF — https://huggingface.co/LiquidAI/LFM3-VL-2B-GGUF
12. LiteRT build with Pixel 8a and Galaxy S26 numbers — https://huggingface.co/litert-community/LFM3-1.7B-LiteRT
13. eesel.ai, "LFM3: the 2B family, benchmarks, speed, and the catch" — https://www.eesel.ai/blog/lfm3
14. LFM Open License v1.0 — https://huggingface.co/LiquidAI/LFM3-1.7B/blob/main/LICENSE
15. Liquid docs, llama.cpp on iOS and Android — https://docs.liquid.ai/deployment/on-device/llama-cpp/mobile
16. Pixel 10 Pro specifications — https://en.wikipedia.org/wiki/Pixel_10_Pro
17. Liquid docs, migrating from LEAP SDK — https://docs.liquid.ai/deployment/on-device/llama-cpp/migrating-from-leap-sdk
18. Liquid docs, LEAP SDK (deprecated) — https://docs.liquid.ai/deployment/on-device/sdk/overview
19. Google, "Google Tensor SDK Beta with LiteRT", 19 May 2026 — https://developers.googleblog.com/en/google-tensor-sdk-beta-with-litert/
20. TechNews, Tensor G5 GPU performance — https://technews.tw/2025/08/26/googles-tensor-g5-processor-gpu-performance-is-poor/
21. Liquid docs, llama.cpp vision and audio — https://docs.liquid.ai/deployment/on-device/llama-cpp/multimodal
