# lfm-pixel

LFM, a private on-device assistant for the Pixel 10 Pro, built on Liquid AI's LFM3 models. All thinking happens
on the phone; the app has no internet permission. Design: [docs/spec.md](docs/spec.md); mock-ups: `docs/mockups/`.

This build covers spec Phases 1–3: Today, Chat, Library (notes, ideas, journal), To-dos, Settings; model import with
SHA-256 checks; chat with LFM3-1.7B through llama.cpp in-process (JNI, CPU); voice capture through LFM3-Omni-2B
in-process; tools with a confirmation sheet, action history and undo. Phase 4 (LFM Net, web search, downloads) is not built.

## Build

Needs JDK 21, the Android SDK (platform 37, build-tools 36.1, NDK 29.0.14206865, CMake 3.31.6). On this machine they
live on `/data`; `env.sh` sets the paths:

```bash
git submodule update --init --depth 1        # llama.cpp, pinned at b12015
. ./env.sh
./gradlew assembleDebug                      # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest                  # unit tests
```

`assembleDebug` also runs two privacy checks and fails if either breaks:

- `checkDebugNoNetwork`: the merged manifest must not ask for `INTERNET` or other network permissions.
- `checkDebugDependencies`: every library must be on an allow-list (no analytics, crash, Play Services or network SDKs).

The APK holds native code for `arm64-v8a` (the phone) and `x86_64` (the emulator). llama.cpp picks the best CPU
variant at run time.

## Install on the phone

Turn on Developer options and USB debugging on the Pixel, connect it, then:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Import the models

Download the files once on a computer from Hugging Face (LiquidAI, no login), copy them to the phone, then import:

| File | Size | Needed for |
|---|---|---|
| `LFM3-1.7B-QAD-Q4_0.gguf` from LiquidAI/LFM3-1.7B-GGUF | 1.05 GB | chat and tools |
| `LFM3-Omni-2B-Q4_0.gguf` from LiquidAI/LFM3-Omni-2B-GGUF | 1.05 GB | voice |
| `mmproj-LFM3-Omni-2B-Q4_K_M.gguf` from the same repo | 292 MB | voice (Omni's audio input) |

```bash
adb push LFM3-1.7B-QAD-Q4_0.gguf LFM3-Omni-2B-Q4_0.gguf mmproj-LFM3-Omni-2B-Q4_K_M.gguf /sdcard/Download/
```

In the app: Settings → Models → Import a file, pick the files (long-press to pick several). Each is copied into the
app while its SHA-256 is computed, then checked against the hash pinned in `ModelCatalog.kt`; a file that is unknown,
the wrong size or the wrong hash is not kept. The text model then answers a test prompt. Other quantisations
(Q4_K_M, Q5_K_M, Q6_K, Q8_0) are pinned too.

## How it is put together

- `app/src/main/cpp/lfm_jni.cpp`: the JNI bridge to llama.cpp (load, streamed generation reusing the context,
  Omni transcription through `mtmd`).
- `llm/`: prompt format for LFM3's chat template, tool-call parser, model catalog with pinned hashes, import, loading.
- `assistant/`: the loop (model → tool calls → confirm → run → log → back to the model), and simple rules that sort
  captures when no model is loaded.
- `tools/`: one small class per tool; reminders, the on-phone calendar, contacts, Messages, dialer, phone settings.
  `ExternalLauncher` is the only code that starts other apps.
- `voice/`: the `Transcriber` seam; `OmniTranscriber` runs LFM3-Omni-2B in-process.
- `ui/`: Compose screens in the calm Signal, Ink + highlighter style.
- `app/src/debug/`: debug-only hooks used for emulator testing: feed a recorded clip through the voice path, and add
  sample library content. They are not in release builds.

## Tested so far, and what still needs the real phone

Tested in an Android 16 emulator (x86_64, Pixel 10 Pro profile, software rendering): import and hash checks of all
three files; chat and tool calls with LFM3-1.7B (about 15–26 tokens/s on the emulator's CPU); reminder, to-do and
calendar tools with the confirmation sheet and undo; Omni transcription of a synthetic speech clip.

Still needs the Pixel (spec Phase 0):

- Speed, memory, heat and battery for the 1.7B model and Omni on the Tensor G5 (arm64 build, KleidiAI, 6 threads).
- Voice with the real microphone and a real voice; transcription quality outside English.
- Tool-call accuracy on the owner's own phrasing (a 50-command test set); thinking on vs off.
- Alarms and timers in the Pixel's Clock app, Messages and dialer hand-offs, torch, Do Not Disturb access.
- Reminders after a reboot and in Doze; exact-alarm behaviour with `USE_EXACT_ALARM`.
- Whether Android 16 asks for "Allow restricted settings" for this sideloaded app.
