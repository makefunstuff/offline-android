# offline-android

LFM, a private on-device assistant for the Pixel 10 Pro, built on Liquid AI's LFM3 models, and LFM Net, its small
network helper. All thinking happens on the phone; LFM itself has no internet permission. Design:
[docs/spec.md](docs/spec.md); mock-ups: `docs/mockups/`. Code: <https://github.com/makefunstuff/offline-android>.

This build covers spec Phases 1–4: Today, Chat, Library (notes, ideas, journal), To-dos, Settings; chat with
LFM3-1.7B through llama.cpp in-process (JNI, CPU); voice capture through LFM3-Omni-2B in-process; tools with a
confirmation sheet, action history and undo; and, new in Phase 4, web search with sources, the network log, and
in-app model downloads through LFM Net.

## Build

Needs JDK 21, the Android SDK (platform 37, build-tools 36.1, NDK 29.0.14206865, CMake 3.31.6). On this machine they
live on `/data`; `env.sh` sets the paths:

```bash
git clone https://github.com/makefunstuff/offline-android.git && cd offline-android
git submodule update --init --depth 1        # llama.cpp, pinned at b12015
. ./env.sh
./gradlew assembleDebug                      # app/build/outputs/apk/debug/app-debug.apk (LFM, with LFM Net inside)
                                             # net/build/outputs/apk/debug/net-debug.apk (LFM Net on its own)
./gradlew testDebugUnitTest                  # unit tests
```

`assembleDebug` also runs privacy checks and fails if any breaks (task classes in `buildSrc/`):

- `:app:checkDebugNoNetwork`: LFM's merged manifest must not ask for `INTERNET` or other network permissions.
- `:app:checkDebugDependencies`: every library in LFM must be on an allow-list (no analytics, crash, Play Services or
  network SDKs).
- `:net:checkDebugPermissions`: LFM Net may ask for `INTERNET` and `ACCESS_NETWORK_STATE` (to tell Wi-Fi from mobile
  data) and nothing else: no contacts, files, location or phone state.
- `:net:checkDebugDependencies`: LFM Net's libraries are AndroidX, Kotlin and OkHttp only.

The APK holds native code for `arm64-v8a` (the phone) and `x86_64` (the emulator). llama.cpp picks the best CPU
variant at run time.

## Install on the phone

Built APKs will be on the [releases page](https://github.com/makefunstuff/offline-android/releases). To install your
own build, turn on Developer options and USB debugging on the Pixel, connect it, then:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

That is enough: LFM carries LFM Net inside it. Settings → Models (or first run) says when LFM Net is missing and offers
**Install LFM Net**; Android asks you to allow installs from LFM once, then to confirm. You can also install it
directly with `adb install -r net/build/outputs/apk/debug/net-debug.apk`. Both are signed with the same key, which
LFM Net's service requires. Without LFM Net, LFM is fully offline: no search, no downloads, import still works.

## Get the models

**Download in the app.** Settings → Models lists LFM3-1.7B (chat and tools), LFM3-Omni-2B (voice) and LFM3-VL-2B
(documents, for Phase 5), each at Q4_0 (smaller, faster) and Q8_0 (more accurate), with size, what it is for and
whether it is on the phone. **Download** fetches the GGUF from Hugging Face (LiquidAI, no login) through LFM Net, with
the model's projector when it needs one. You see progress, speed and time left; Pause, Resume and Cancel work, and a
download cut off by closing the app or restarting the phone carries on when you open LFM again. Downloads run on Wi-Fi
only unless you switch that off. A finished file is checked against the SHA-256 pinned in `ModelCatalog.kt` and then
loaded; a file that doesn't match is deleted. Delete and storage used/free are on the same screen.

**Or import a file**, fully offline. Download the files once on a computer, copy them to the phone, then import:

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

## Web search

Search goes through LFM Net and a provider you set in LFM Net's own screen (Settings → Web search → the provider row):
your own **SearXNG** (recommended, with **Wikipedia**, which is off until you turn it on: no account with anyone), Brave or Mojeek (an API key, tied to
your card), or Wikipedia alone. Keys stay in LFM Net. SearXNG needs JSON output on in its `settings.yml`
(`search: formats: [html, json]`); reach it over your WireGuard VPN, or over https with a user and password.

- **Modes:** Off, Ask first (default: the search words show in the chat with Edit / Send before they go) or
  Automatic. Any search after web pages were read always asks, and so does every tool action.
- **Personal words:** before a query goes, LFM checks it on the phone for names in your contacts, phone numbers,
  e-mail addresses, street addresses and postcodes, and names of people and places from your journal and notes (LFM asks to read contacts at the first search;
  if you say no, the Search page says contacts weren't checked). If one
  is in it, the card marks it and offers Remove name / Send anyway.
- **Reading pages:** LFM Net opens the top three results (from different sites) as text; LFM finds the article on the
  phone and gives the model numbered sources. "Read the top result pages" off means snippets only.
- **Sources:** the answer cites [1], [2]; the search card opens the Search page with what was sent, to whom, the
  sources (tap one to open it in your browser, address shown first) and what left the phone.
- **Network log:** Settings → Privacy lists every request LFM Net made (time, host, what, size), including ones that
  failed, with Export and Delete all, and a switch that turns LFM Net off.

## How it is put together

- `ipc/`: the AIDL interface between the two apps (`info`, `search`, `fetch`, `download`) and the pinned file list.
- `net/`: LFM Net. `NetEngine` does the three network calls: `fetch` opens only addresses from its latest results,
  `download` only pinned files from huggingface.co and its CDN over https. No cookies, cache or referrer; the user
  agent of a current Chrome on Android; optional SOCKS proxy (Orbot). `NetService` answers only LFM.
- `app/src/main/cpp/lfm_jni.cpp`: the JNI bridge to llama.cpp (load, streamed generation reusing the context,
  Omni transcription through `mtmd`).
- `llm/`: prompt format for LFM3's chat template, tool-call parser, model catalog with pinned hashes, import, loading.
- `assistant/`: the loop (model → tool calls → confirm → run → log → back to the model), and simple rules that sort
  captures when no model is loaded.
- `tools/`: one small class per tool; reminders, the on-phone calendar, contacts, Messages, dialer, phone settings.
  `ExternalLauncher` is the only code that starts other apps.
- `voice/`: the `Transcriber` seam; `OmniTranscriber` runs LFM3-Omni-2B in-process.
- `web/`: LFM's side of LFM Net: `NetClient` (binds to it, logs every request), `WebSearch` (the search flow),
  `PersonalWords`, `ArticleText` (reads pages), `ModelDownloads` + `DownloadService`, `NetInstaller`.
- `ui/`: Compose screens in the calm Signal, Ink + highlighter style.
- `app/src/debug/`: debug-only hooks used for emulator testing: feed a recorded clip through the voice path, and add
  sample library content. They are not in release builds.

## Tested so far, and what still needs the real phone

Tested in an Android 16 emulator (x86_64, Pixel 10 Pro profile, software rendering):

- Phases 1–3: import and hash checks of all three files; chat and tool calls with LFM3-1.7B (about 15–26 tokens/s on
  the emulator's CPU); reminder, to-do and calendar tools with the confirmation sheet and undo; Omni transcription of a
  synthetic speech clip.
- Phase 4, against real servers: installing LFM Net from inside LFM; searches through a real SearXNG (run locally in
  Docker) plus Wikipedia, with LFM3-1.7B deciding to search, the query card, the personal-word warning for a contact's
  name (Remove name and Send anyway), pages read and cited, the Search page, the network log, and a tool action after
  web text asking first. Downloads from Hugging Face of LFM3-VL-2B Q4_0 with its projector and LFM3-1.7B Q8_0: pause,
  resume, resume after force-stopping the app, waiting for Wi-Fi on mobile data, cancel, SHA-256 match, then loaded.
  The Phase 1–3 database migrated to the new version on update.

Still needs the Pixel (spec Phase 0 and Phase 4):

- Speed, memory, heat and battery for the 1.7B model and Omni on the Tensor G5 (arm64 build, KleidiAI, 6 threads).
- Voice with the real microphone and a real voice; transcription quality outside English.
- Tool-call accuracy on the owner's own phrasing (a 50-command test set); thinking on vs off. How often the model
  searches when it should, and how often it cites its sources (it did in some emulator runs, not all).
- Alarms and timers in the Pixel's Clock app, Messages and dialer hand-offs, torch, Do Not Disturb access.
- Reminders after a reboot and in Doze; exact-alarm behaviour with `USE_EXACT_ALARM`.
- Whether Android 16 asks for "Allow restricted settings" for this sideloaded app.
- Downloads after a real phone restart, over the owner's Wi-Fi and with the phone locked for a long download; that
  Wi-Fi only really never uses mobile data on the Pixel.
- Search over the owner's own SearXNG through WireGuard, Brave or Mojeek with a real key, and a SOCKS proxy through
  Orbot (none of these were tried).
