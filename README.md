# offline-android

Inkling (called LFM until LP-12), a private on-device assistant for the Pixel 10 Pro, built on Liquid AI's LFM3
models, and Inkling Net, its small network helper. All thinking happens on the phone; Inkling itself has no internet
permission. Design: [docs/spec.md](docs/spec.md); how it should feel to use: [docs/ux-spec.md](docs/ux-spec.md);
mock-ups: `docs/mockups/`. Code: <https://github.com/makefunstuff/offline-android>. In the code and below, "LFM" and
"LFM Net" still name the app and its helper; package names did not change, so updates keep all data and models.

## Using it (LP-12)

- **Capture**: Today's Note · Idea · Journal · To-do buttons open the editor with the keyboard up. The editor saves as
  you type and on Back; there is no Save button. In the Library, + and the mic; on To-dos, the "Add a to-do" line.
- **Lists**: Library has one search field and All · Notes · Ideas · Journal · Pinned. Swipe right to pin (to-dos:
  done), left to delete; long-press for Move to. Every delete and tick can be undone from the bar at the bottom.
- **Chat**: keep sending while it answers; messages wait their turn in the thread as "Queued". Tap one to edit, move
  or remove it. New chat takes them along. Stop is the black square next to the mic.
- **Models**: the chat header (or Settings → Models) shows what is loaded and roughly how much memory it takes, with
  Load and Unload for each model, the chat model when you have more than one, and "Unload when idle" (Never, 1, 5,
  15 or 30 minutes).

This build covers every phase of the spec, 1–7: Today, Chat, Library (notes, ideas, journal), To-dos, Settings; chat with
LFM3-1.7B through llama.cpp in-process (JNI, CPU); voice capture through LFM3-Omni-2B in-process; tools with a
confirmation sheet, action history and undo; web search with sources, the network log, and in-app model downloads
through LFM Net (Phase 4); **Ask about this**: camera, photos, short videos and documents, understood on the phone
with no network (Phase 5); **Talk** and **read-aloud** with Omni's own voice, Inkling as the phone's assistant, a
Quick Settings tile and a home-screen widget (Phase 6); and, new in Phase 7, heat handling, the app or journal lock,
export and delete everything, an accessibility pass and a motion pass (below).

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

**Release builds** are signed with your own key, never the public debug key. Make one once (keep it and its passwords
safe: an update must be signed with the same key) and point `keystore.properties` at it, at the top of the repo.
That file and `*.jks` are in `.gitignore`; never commit them. Inkling and Inkling Net read the same file, so both are
signed alike, as Inkling Net's service requires. Without the file, `assembleRelease` makes unsigned APKs.

```bash
keytool -genkeypair -v -keystore ~/keys/inkling-release.jks -alias inkling -keyalg RSA -keysize 4096 -validity 10000
cat > keystore.properties <<'KEY'
storeFile=/home/you/keys/inkling-release.jks
storePassword=…
keyAlias=inkling
keyPassword=…
KEY
./gradlew assembleRelease                    # app/build/outputs/apk/release/app-release.apk
```

`assembleDebug` also runs privacy checks and fails if any breaks (task classes in `buildSrc/`):

- `:app:checkDebugNoNetwork`: LFM's merged manifest must not ask for `INTERNET` or other network permissions.
- `:app:checkDebugDependencies`: every library in LFM must be on an allow-list (no analytics, crash, Play Services or
  network SDKs).
- `:net:checkDebugPermissions` (Edge mode changed none of these: Inkling still has no network): LFM Net may ask for `INTERNET` and `ACCESS_NETWORK_STATE` (to tell Wi-Fi from mobile
  data) and nothing else: no contacts, files, location or phone state.
- `:net:checkDebugDependencies`: LFM Net's libraries are AndroidX, Kotlin and OkHttp only.

The APK holds native code for `arm64-v8a` (the phone) and `x86_64` (the emulator). llama.cpp picks the best CPU
variant at run time (on Tensor G5 that should be `android_armv9.0_1`: dotprod, i8mm, SVE2; the `armv9.2` ones need SME,
which G5 lacks). Q4_0 weights are repacked for Arm (KleidiAI) when the model loads.

### Speed

- Threads stay off the little cores (found from `cpu_capacity`, else the top clock): llama.cpp splits each step evenly
  and waits for the slowest thread, so one thread on a little core slows every token. Auto (the default) writes with
  one thread fewer than the other cores (G5: 5 of 6) and reads prompts with all of them.
- Settings → Models → Benchmark: prompt and generation tok/s, time to the first token, tok/s through the chat, sampler
  cost, a sweep of thread counts (with "Use N threads" when another count is 5% faster), the CPU variant, backends and
  llama.cpp's buffer types. Copy results puts it all on the clipboard. Debug builds also run it with
  `adb shell am broadcast -p com.lfmpixel.lfm -a com.lfmpixel.lfm.DEBUG_BENCH` (logcat tag `lfm-bench`).
- The chat redraws only the streaming answer, at most 20 times a second; each answer shows tok/s and the time to the
  first token (Settings → Show speed).
- No GPU: llama.cpp's Vulkan backend is untested on the G5's PowerVR GPU and needs the phone to try; CPU stays the
  only backend for now.
- The emulator's default CPU model hides AVX2, so llama.cpp falls back to `ivybridge` with no repacking there. Start it
  with `-qemu -cpu host` to measure.

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
(documents), each at Q4_0 (smaller, faster) and Q8_0 (more accurate), with size, what it is for and
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
| `mmproj-LFM3-Omni-2B-Q4_K_M.gguf` from the same repo | 292 MB | voice, photos, video (Omni's ears and eyes) |
| `LFM3-VL-2B-QAD-Q4_0.gguf` from LiquidAI/LFM3-VL-2B-GGUF | 1.05 GB | documents (optional) |
| `mmproj-LFM3-VL-2B-Q8_0.gguf` from the same repo | 203 MB | documents (VL's eyes) |

```bash
adb push LFM3-1.7B-QAD-Q4_0.gguf LFM3-Omni-2B-Q4_0.gguf mmproj-LFM3-Omni-2B-Q4_K_M.gguf /sdcard/Download/
adb push LFM3-VL-2B-QAD-Q4_0.gguf mmproj-LFM3-VL-2B-Q8_0.gguf /sdcard/Download/   # for documents
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

## Ask about this (camera, photos, video, documents)

The camera button in the composer (Today and Chat) opens a viewfinder with three modes (the mock-up's Photo | Video,
plus Document, so pages go to VL) and an optional question
(typed, or said with the mic). The answer comes back in the chat under a small picture of what you showed; a
follow-up ("remind me to pay that rent on the 1st") goes to LFM3-1.7B, which reads that answer and can call tools.

- **Photo:** the shutter takes a picture; the picture button opens Android's photo picker. LFM3-Omni-2B looks.
- **Video:** the shutter records up to 10 s, keeping 2 frames a second straight from the camera (no video file is
  written); the picker takes a clip from the gallery, read the same way (first 10 s). Omni looks at the frames in order.
  The clip's sound is not used yet.
- **Document:** the shutter shoots a page; the document button opens the system file picker for a PDF or a picture.
  LFM3-VL-2B reads it. PDFs (up to 20 pages) are read from their text layer when they have one (fast) and page by page
  with VL when they are scans, then VL answers over all the text. VL loads only for documents (or for photos too
  when Omni is not on the phone). Keep LFM open while it reads a long scan: it does not run in a foreground service
  yet, so Android may pause it in the background (the spec's Phase 7 moves long jobs there). Stop works between
  video frames and PDF pages, not in the middle of one.
- If only one of Omni and VL is on the phone, it does both jobs; a PDF with text can also be answered by LFM3-1.7B.
- Nothing is uploaded: pictures go from the camera or the picker's URI straight into llama.cpp's `mtmd` in this
  process. LFM asks for the camera only (no storage permission: the pickers grant one file at a time). A small copy of
  each picture is kept in the app's files for the chat; Settings → Privacy → Clear chats deletes them.

## Talk and read-aloud (Phase 6)

**Talk** is a hands-free conversation with LFM3-Omni-2B, all inside Inkling: Talk on Today, the wave icon in Chat, the
widget, or the assistant gesture. It listens until you pause (about 0.8 s of quiet), Omni writes down what you said,
then answers with its text and its voice at once, and listens again once the answer has been heard. The square of
bars follows your voice while it listens and its own while it speaks; the highlighter marks what it is doing now
(Getting ready, Listening, Hearing you, Thinking, Speaking). Stop cuts an answer short and listens; while listening it
pauses. While Talk is open other audio (music, a podcast) pauses, and read-aloud lowers it; a call or an alarm pauses Talk
and stops read-aloud. After 20 s of quiet it
pauses by itself, and it pauses whenever Inkling leaves the screen, so the mic is
never open in the background. The speaker button switches to text-only answers (faster, silent); the chat button
carries the conversation into Chat, where it can go on in writing and with tools. "Phone is warm" shows when Android
reports the phone is getting hot.

- How: llama.cpp's own speech-out code for LFM3 (`mtmd_helper_gen_audio`, the same code `llama-tts` and the
  `/v1/realtime` server use) is called in-process through JNI (`app/src/main/cpp/talk.cpp`). The answer is Omni's
  mixed mode: blocks of text, then of speech, from one pass. No server, no socket, no network permission.
- Half duplex: the mic is closed while it speaks, so it never hears itself; you can't talk over it (barge-in), tap
  Stop instead.
- Talk does not run tools or search: it is Omni on its own. "Carry on in Chat" hands the conversation to LFM3-1.7B.
- If the phone makes speech slower than it plays (the emulator does), playback waits for a little audio first and,
  if it runs dry, for longer stretches, so it speaks in phrases instead of stuttering.

**Read aloud** sits under every answer in Chat: Omni reads it in your chosen voice (female or male) and language,
without Markdown marks, source numbers or links, in short pieces so it starts soon. Tap again to stop.
Settings → Voice and Talk holds the voice, the language, "Answer out loud", and the ways in.

**Ways in.** Inkling can be the phone's digital assistant (Settings → Voice and Talk → Phone's assistant, or Android
Settings → Apps → Default apps → Digital assistant app). Holding the power button or the corner swipe then opens Talk
or voice capture, as you pick. The **Quick Settings tile** "Capture" opens Inkling already listening for a capture (on
the lock screen it asks you to unlock first). The **widget** (4×1) has Capture (the mic), Talk and Note. Both can be
added from Settings → Voice and Talk with one tap. Inkling also names a speech recogniser, as Android requires of an
assistant, and Android then makes it the phone's recogniser too: so it is a real one. Another app's voice input (a
keyboard's mic, a search box) is meant to be heard and written down by Omni on the phone (without Omni it says it
can't); this path is built but was not tried yet, not even in the emulator.

## Edge mode: your own server (LP-32)

Settings → **Your server** moves chat, tools, pictures, documents, Talk, voice capture and read-aloud to the owner's
own computer running `local-ai-mode edge` (homelab `roles/local_ai`): its address on the home network or tailnet
(`http://100.124.77.61:18448`, or on the LAN `http://192.168.1.138:18101`, which the emulator reaches too; the
server doesn't answer on the host's loopback, so not `10.0.2.2`) and its API key (`~/.config/local-ai/edge.key`, kept encrypted with the phone's keystore).
**Test** checks the key and that the server serves `lfm3-1.7b`, `lfm3-vl-2b` and `lfm3-omni-2b`.

- Inkling still has no `INTERNET` permission: every request goes through Inkling Net's new `edge` call, which opens
  only a private or tailnet address, only six paths, never a redirect (why: docs/spec.md, section 3, Edge mode).
  Each request is in Privacy → Network log.
- Chat sends the same LFM3 prompts the phone would run, to `/v1/completions`, so tools work as before; a prompt past
  32K tokens goes to `lfm3-1.7b-long` (262K), so long chats are no longer cut. Thinking stays off unless turned on.
- Talk: the server writes down the turn, LFM3-1.7B answers (streamed), each sentence goes to Omni's `/tts` as it
  ends, and plays through LP-30's jitter buffer while the rest is still coming.
- The chat header shows **Server** or **On phone**. When the server can't be reached the phone's own models answer
  ("Your server isn't answering · this phone answers until it's back"), and it switches back by itself.
- Settings → Models → **Phone and server**: writing speed, first token and voice first audio, phone next to server.
  Debug builds: `adb shell am broadcast -p com.lfmpixel.lfm -a com.lfmpixel.lfm.DEBUG_EDGE --es url http://192.168.1.138:18101
  --es keyfile edge.key --ez on true --ez bench true` (the key file pushed into the app's files first; logcat tag `lfm-edge`).

## Smart answers, Look, small classifiers (LP-45)

Design and ranking: [docs/notebook-spec.md](docs/notebook-spec.md), section B.

- **Answers from Qwen.** In Edge mode, chat, Talk and a note's margin are answered by `qwen3.8-27b` when the server
  has it loaded (homelab HOME-1), with **native tool calling**: the same tools (to-dos, reminders, events, notes,
  search, the organizer's actions), the same confirmation sheet and undo, in Talk too. Omni only listens and speaks.
  When Qwen isn't loaded (or fails before writing) LFM3-1.7B on the server answers, then the phone. The line under
  an answer and Talk's timings (Settings → Show speed) name the model that wrote it. `edge/ChatPrompt.kt` converts
  the phone's LFM3 prompt to chat messages and tools and the reply back. Server voice calls go through one
  interface, `ServerVoice` (`edge/Edge.kt`), for HOME-1's shared voice service.
- **Look.** In Talk, the camera button shows the camera's view and what LFM3-VL-2B sees in it, live; Talk answers
  about it ("what am I holding?" goes straight to the VL model on the newest frame). `see/Look.kt`.
- **Classifiers** on LFM3-1.7B / LFM3-VL-2B with JSON-schema output (`edge/Labels.kt`): the Ink line's kind and time
  words as you type, photo filing (receipt, whiteboard, document → read and filed as a tagged note), Talk intent
  with Look on.
- Debug: `--es answer lfm3-1.7b` on `DEBUG_EDGE` makes another served model stand in for Qwen, to test its path.

## Lock, export, delete, heat (Phase 7)

- **Lock** (Settings → Privacy → Lock): Off, Whole app, or Journal only, opened with the phone's own fingerprint, face
  or screen lock (Android's BiometricPrompt; nothing is stored by Inkling). Turning it on, off or changing it asks for
  the phone's lock first. It locks again a minute after you leave Inkling. With a lock on, Recents shows a blank card. Whole app: a lock
  screen covers everything; the assistant gesture, tile and widget wait for the unlock. Journal only: journal
  entries leave the lists, Today, Library search and the assistant's library search until unlocked; writing a new
  journal line still works. The phone needs a screen lock set for this.
- **Export all** (Privacy): one ZIP saved where you choose: notes.md, ideas.md, journal.md, todos.md, chats.md to
  read, and inkling.json with every row of every table. With the lock on, it asks first. Models, pictures and
  settings are not in it.
- **Delete everything** (Privacy): notes, journal, to-dos, chats, reminders, searches, the network log, the action
  history, pictures, the queue, Inkling's own calendar and every setting; the models only if you tick that too. It
  asks for the phone's lock when the lock is on, then starts again at first run.
- **Heat**: Inkling follows Android's thermal status. Warm: one thread fewer. Hot: half the threads, and long jobs
  (a scanned PDF read page by page) wait between pages for the phone to cool. Too hot: two threads, and queued
  messages wait. Today and Chat say so ("Phone is hot · answers slower, long jobs wait"). The change applies from
  the next token, through llama.cpp's thread count.
- **Keeps going in the background**: an answer, a long scan or a queue of messages started on screen runs on in a
  foreground service with a notification and a Stop button when you leave. Stop ends the answer and holds the queue
  until Inkling is opened again.
- **Other fixes**: "remind me in 10 minutes", "in an hour", "in half an hour" and "at 6" (the next 6 o'clock) now
  work without a model; only one permission request at a time (a second waits for the first); a hand-off to
  another app (Clock, Messages, dialer, a settings panel) is not tried when Inkling is in the background, where
  Android silently blocks it, and the chat says "Not opened" instead of claiming it opened; a new `set_volume` tool
  (media, ring, alarm, call; a level, up, down, mute, max; with undo); the network log shows each full address;
  pill edges use the control grey so they show in both themes.

## Motion and accessibility (Phase 7)

One set of motion tokens in `ui/theme/Motion.kt` (100–300 ms, Material 3 emphasized and standard easing, springs
without bounce for what follows a finger), used everywhere:

- Pushed screens slide and fade along the X axis, the camera and Talk along Z; **predictive back** shrinks the screen
  toward 90 % and rounds its corners while the back swipe is held, and settles back if you let it go.
- Bottom sheets (the confirmation sheet too) slide up over a fading scrim (Material's own sheet motion).
- List rows fade in where they land, fade out as the rest close up, and glide when reordered (pin, done, queue
  order); a tick fills the box and draws the check, then the row moves to Done.
- The chat answer streams in evenly: what arrives in a burst is revealed over about 120 ms instead of jumping; while
  it thinks, three small squares brighten in turn beside "Thinking…" or "Writing…".
- The mic turns ink while listening with an outline that breathes out with your voice level, and its yellow eases
  back in when it stops. The highlighter draws in from the left behind its words the first time it shows.
- Buttons dip to 96 % the moment they are pressed, with an ink ripple; switches slide their thumb, tabs and
  segmented choices fade their ink across, progress bars glide between steps.
- With Android's "Remove animations" on, everything snaps. Nothing bounces, nothing loops but the "working" dots.

Accessibility: text contrast at least 4.5:1 and control edges at least 3:1 in both themes (a unit test checks the
palette); switches, check boxes and icon buttons have 48 dp targets and say their names and states; tabs and
segmented choices say which is selected; screen titles are headings; "Thinking…", the heat line and the undo bar are
read out when they change; progress bars report their value; buttons, tabs and rows grow with large text (checked at
200 %) instead of clipping.

## How it is put together

- `ipc/`: the AIDL interface between the two apps (`info`, `search`, `fetch`, `download`, `edge`), the pinned file
  list and `EdgeRules` (where Edge mode may go).
- `net/`: LFM Net. `NetEngine` does the three network calls: `fetch` opens only addresses from its latest results,
  `download` only pinned files from huggingface.co and its CDN over https. No cookies, cache or referrer; the user
  agent of a current Chrome on Android; optional SOCKS proxy (Orbot). `NetService` answers only LFM.
- `app/src/main/cpp/lfm_jni.cpp`: the JNI bridge to llama.cpp (load, streamed generation reusing the context,
  Omni transcription and pictures in through `mtmd`).
- `llm/`: prompt format for LFM3's chat template, tool-call parser, model catalog with pinned hashes, import, loading.
- `assistant/`: the loop (model → tool calls → confirm → run → log → back to the model), and simple rules that sort
  captures when no model is loaded.
- `tools/`: one small class per tool; reminders, the on-phone calendar, contacts, Messages, dialer, phone settings.
  `ExternalLauncher` is the only code that starts other apps.
- `voice/`: the `Transcriber` seam; `OmniTranscriber` runs LFM3-Omni-2B in-process. `Talk` (the turn loop),
  `ReadAloud`, `AudioOut` (plays 24 kHz speech as it is made), `SpeechFormat` (TTS prompt, text to speech, the
  end-of-speech detector `Endpointer`).
- `assist/`: the assistant service and session, the Quick Settings tile and the widget.
- `app/src/main/cpp/talk.cpp`: speech out with Omni (read-aloud and Talk's mixed text and voice), plain C++ with no
  JNI, so it can be built into a small desktop program against llama.cpp to check speech without a phone (that is
  how the clips below were transcribed back; the program is not in the repo).
- `see/`: Ask about this. `SeeRules` (which model looks, frame times, sizes, prompts), `MediaPrep` (photos, video
  frames, PDF pages and text layers into RGB for llama.cpp). The camera screen is `ui/screens/CameraScreen.kt` (CameraX).
- `web/`: LFM's side of LFM Net: `NetClient` (binds to it, logs every request), `WebSearch` (the search flow),
  `PersonalWords`, `ArticleText` (reads pages), `ModelDownloads` + `DownloadService`, `NetInstaller`.
- `device/`: `Heat` (Android's thermal status to thread caps and waits) and `AppLock` (the optional lock).
- `data/Export.kt`: Export all; `wipe` in `LfmApp.kt`: Delete everything. `assistant/WorkService.kt`: an answer keeps
  going in a foreground service when you leave.
- `edge/`: Edge mode. `Edge` (the server's state, checks, its models, speech in and out as `ServerVoice`),
  `EdgeSession` (the server's model as a `TextSession`, Qwen for answers, with the phone's as fallback), `ChatPrompt`
  (LFM3 prompts to native chat and tools and back), `Labels` (the small classifiers), `EdgeKey`, `EdgeBench`.
- `ui/`: Compose screens in the calm Signal, Ink + highlighter style; `ui/theme/Motion.kt` holds the motion tokens.
- `app/src/debug/`: debug-only hooks used for emulator testing: feed a recorded clip through the voice path, ask
  about a file in the app's folder as the pickers do (`DEBUG_SEE`), add sample library content, and play a clip into
  Talk in place of the mic, at the mic's pace, and keep what Talk and read-aloud say (`DEBUG_TALK`). Send them with
  `adb shell am broadcast -p com.lfmpixel.lfm -a …`. They are not in
  release builds.

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
- Phase 5: the camera screen with the emulator's virtual camera (photo, a 5 s recorded clip, document mode); the photo
  picker and the system document picker; Omni on a photo (about 30–60 s on the emulator's CPU) and on a 4 s clip
  (8 frames, about 35 s); VL on a 2-page PDF with a text layer (10 s), a scanned one-page PDF (50 s) and a receipt
  picture (38 s), all answered correctly; a follow-up that set a reminder from what VL read. Light and dark. The
  Phase 4 database (version 2) migrated to version 3 on update with its chats kept.

- Phase 6, in the same emulator (4 threads on a busy desktop CPU): Talk end to end with spoken questions played into
  the mic path (made by Omni's own voice): Listening → Hearing you → Thinking → Speaking, two turns with the first as
  context, Stop in the middle of an answer, back to listening, the 20 s pause, "Carry on in Chat". Text-only mode was
  not tried. What Talk and read-aloud played was saved and transcribed back with Omni on the desktop: it matched the
  answers word for word. Omni made speech at about a quarter of real time in Talk and half in read-aloud there
  (first audio about 9 s after the question; a 9 s answer took 34 s to make). Inkling as the digital assistant: the
  assistant key and the power-button hold opened Talk from the home screen; the mic closed when leaving the app.
  The Quick Settings tile (added and tapped) and the widget (pinned from settings; Capture, Talk and Note) opened
  the right place. Light and dark.

- Phase 7, in the same emulator: Journal only lock (journal hidden from the Library, All and Today, unlocked with
  the phone's PIN, locked again after a minute away); Whole app lock (lock screen in light and dark, unlocked with the
  PIN); turning the lock on and off asks for the PIN; Export all saved a ZIP through Android's save dialog (Markdown
  and JSON checked); Delete everything without the models went back to first run with the models kept; a hot phone
  (`adb shell cmd thermalservice override-status 3`) showed the heat line in Chat and on Today; 200 % font size;
  "Remove animations" on. Motion was recorded with animations slowed 5× (the emulator draws in software) and played
  back at real speed. The app's own work per frame (UI thread, from `dumpsys gfxinfo framestats`, Library scrolls,
  tab and screen changes) was 2.3 ms at the median and under 8.3 ms (a 120 Hz frame) for 98 % of frames; whole
  frames took about 40 ms there because the emulator's GPU is software, so smoothness at 60/120 Hz needs the phone.
  Unit tests cover the new times, the permission queue, background hand-offs, volume with undo, heat steps, lock
  timing, the locked journal in library search, export contents, delete everything and the palette's contrast.

Still needs the Pixel (spec Phase 0 and Phases 4–7):

- Speed, memory, heat and battery for the 1.7B model and Omni on the Tensor G5 (arm64 build, KleidiAI, Auto threads):
  run Settings → Models → Benchmark and Copy results.
- Voice with the real microphone and a real voice; transcription quality outside English.
- Tool-call accuracy on the owner's own phrasing (a 50-command test set); thinking on vs off. How often the model
  searches when it should, and how often it cites its sources (it did in some emulator runs, not all).
- Alarms and timers in the Pixel's Clock app, Messages and dialer hand-offs, torch, Do Not Disturb access.
- Reminders after a reboot and in Doze; exact-alarm behaviour with `USE_EXACT_ALARM`.
- Whether Android 16 asks for "Allow restricted settings" for this sideloaded app.
- Phase 5 on the Pixel: the real cameras (front and back, flash and torch, photo sharpness for small print), how long
  Omni and VL take to look at a photo, a 10 s clip and a 20-page scanned PDF on the Tensor G5, and memory with
  1.7B, Omni and VL loaded together (about 4 GB *estimate*; Omni's context is now 8K tokens to fit a clip's frames).
  Photos taken with the phone turned sideways (rotation is set at the shutter; the emulator was only tried upright).
  Quality on real photos and documents (only synthetic ones were tried), and documents in languages other than
  English.
- Downloads after a real phone restart, over the owner's Wi-Fi and with the phone locked for a long download; that
  Wi-Fi only really never uses mobile data on the Pixel.
- Search over the owner's own SearXNG through WireGuard, Brave or Mojeek with a real key, and a SOCKS proxy through
  Orbot (none of these were tried).
- Phase 6 on the Pixel: whether Omni speaks faster than real time on the Tensor G5 (the emulator could not; if not,
  answers come in phrases with gaps), the time to first audio, battery and heat over a 10-minute Talk; the real
  microphone and a real voice (the end-of-speech detector was only tested with recorded clips and unit tests, not in a
  noisy room); speaker volume and Bluetooth headsets; whether the Pixel's power-button hold really goes to a
  third-party assistant (it did in the emulator) and how the corner swipe behaves; the tile from the lock screen;
  the widget on the Pixel launcher; speech in languages other than English; the speech recogniser from another app
  (a keyboard's mic), including whether Android lets it use the mic while Inkling is in the background.
- Phase 7 on the Pixel: the lock with a real fingerprint and face unlock; real heat (a long Talk or a 20-page scan)
  and whether the thread cap keeps it below Severe; frame pacing at 120 Hz with the real GPU (the emulator could only
  measure the app's own per-frame work); the mic's pulse with a real voice (the emulator's mic was silent); predictive
  back with a real thumb (adb swipes always complete); TalkBack read-through on every screen (the semantics were
  checked in the accessibility tree, not by listening); a signed release build with the owner's own key.
