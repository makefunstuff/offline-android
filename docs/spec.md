# LFM3 on Pixel: spec (v2)

One personal assistant for the owner's Pixel 10 Pro, built on Liquid AI's LFM3 models. It sees, listens, talks,
answers, searches the web, keeps notes, ideas, a journal and to-dos, and does things on the phone (calendar, alarms,
reminders, messages, settings). All thinking happens on the phone. This is the design for review; no app code is
written yet.

Research date: 30 October 2026, three days after LFM3 came out. Almost every LFM3 figure below is Liquid's own; no
independent tests exist yet. Figures marked *estimate* are mine, to be measured in Phase 0.

**What changed from v1.** The owner widened the scope: native tool calls on Android and web search, one assistant
for everything, and a privacy promise of "no server of ours, inference on the phone, no telemetry, no accounts; the
network only for web search and model downloads". So v1's "no network at all" becomes a two-app split (section 3),
and the use cases, screens, flows and build plan are redone. The mock-ups are redrawn as real screens, in dark and
light; their source is in `docs/mockups/`.

## 1. What LFM3 really is

Liquid AI released LFM3 on 27 October 2026: six open-weight models of about 2B parameters, all built from one base,
LFM3-1.7B-Base, which is published too [1][7]. LFM3 is real, and it is a good fit for a phone. The family is small, though: there is no larger
LFM3 model to fall back on.

| Model | Size | In | Out | Context | Notes |
|---|---|---|---|---|---|
| **LFM3-1.7B** (text) | 1.73B | text | text | 256K | Reasoning ("thinking") on by default, and turning it off "can be inconsistent"; trained for tool calls (BFCL V4 71.4); 16 languages [1][2] |
| **LFM3-VL-2B** (vision) | 1.92B (1.73B LM + 164M vision encoder + 25M projector) | text, image, video | text | 128K | DocVQA 96.7, OCRBench v1 86.3, ChartQA 92.7; video at 2 fps; "not recommended for … multi-turn conversations" [3] |
| **LFM3-Audio-2B** | 2B | text, 16 kHz audio | text, 24 kHz speech | 32K | ASR: 6.23 WER (English), 6.03 (FLEURS, 9 languages); live ASR and voice chat are English only [4] |
| **LFM3-Omni-2B** | 2.17B | text, image, video, audio | text, speech, both mixed | 32K | Non-reasoning; ASR 6.36 WER (English), 6.72 (FLEURS); female or male voice; speech in 19 languages/locales; translation best to or from English [5] |
| d1-2B, d1-Omni-2B | ~2B | (as VL / Omni) | typed answers with probabilities | 128K (d1-2B) | Built for routing and tagging; not needed here [6] |

- **Formats.** Liquid publishes GGUF for all four main models: Q4_0, Q4_K_M, Q8_0, F16 and BF16 for each, plus
  Q5_K_M and Q6_K for the text and VL models. There are ONNX and MLX builds of all four, and
  FP8 and NVFP4 for text and VL [7]. Q4_0 of the text and VL models is a quantization-aware distillation (QAD) checkpoint
  trained to recover what 4-bit loses [8][10].
- **Sizes on disk (Q4).** Text 1.05–1.1 GB. VL 1.05–1.1 GB plus a 203 MB image projector (`mmproj`, Q8_0). Omni
  1.1 GB plus a 292 MB projector that holds its vision, audio-in and speech-out parts (Q4_K_M). Audio 1.1 GB plus a
  172 MB projector [8][9][10][11].
- **A LiteRT build** of the text model exists (int4 and int8, 2K or 4K context only) [12]. A third-party ExecuTorch
  export of text, VL and Audio exists (XNNPACK 8da4w, 8K context, built for React Native; no Omni) [21]. I found no
  MediaPipe build.
- **Phone fit.** At 4-bit the text model takes about 1 GB [1]. Liquid reports 43.6 tokens/s decode on an iPhone 17
  Pro with llama.cpp Q4_0 at 32K context, using 1.9 GB (Liquid's chart, as relayed by [13]). The KV cache at 128K
  context is 1.6 GB [1].
- **Licence.** LFM Open License v1.0. Free for personal, research and commercial use, except that an entity with US$10M or more in
  yearly revenue is not licensed for commercial use [14]. A private app for the owner is fine; selling it later needs a check.
- **Honest limits.** The model cards say the text and audio models are "not recommended for agentic coding or complex
  multi-step reasoning" and that knowledge-heavy questions want retrieval [2][4]. The text model trails Qwen3.5-9B on
  knowledge (GPQA 63.4 vs 80.4) and long-context recall (RULER 128K 65.6 vs 87.1) [1]. Treat it as a quick, private
  helper, not an oracle.

## 2. Running it on Android

| Runtime | LFM3 support | Pixel 10 Pro acceleration | Verdict |
|---|---|---|---|
| **llama.cpp** (NDK + JNI) | All four models as GGUF (b11960+); images and audio in through `mtmd` and the projector file [8][9][10]. Omni speech out through the `llama-tts` tool (language and voice options), and live voice conversation through the `/v1/realtime` WebSocket of `llama serve` [9] | CPU (arm64, runtime feature detection). Vulkan and OpenCL backends exist but "need device-specific testing"; OpenCL targets Adreno, and the Pixel's GPU is an Imagination PowerVR DXT-48-1536 [15][16]. No Tensor TPU backend | **Use this.** Liquid's own docs now point here [17] |
| Liquid LEAP SDK | Deprecated, frozen at v0.10.x, not updated for new checkpoints [18] | | No |
| LiteRT-LM | Text model only, 2K/4K context, CPU and GPU; no NPU build [12] | GPU yes. Tensor TPU access is in beta (May 2026) but the only language models it names are Gemma 3 1B and FunctionGemma (plus EmbeddingGemma) [19] | Later option for faster text-only chat |
| MediaPipe LLM Inference | No LFM3 build found | | No |
| ExecuTorch | Third-party export of text, VL and Audio, 8K context, no Omni [21] | CPU (XNNPACK) | No: no Omni, short context, no gain over llama.cpp |
| ONNX Runtime | ONNX builds of all four [7] | CPU | No: no gain over llama.cpp |

**Speech out without a network.** The assistant app has no `INTERNET` permission (section 3). An app without the `INTERNET` permission cannot open network sockets, not even to
itself on `localhost`, so the `llama serve` endpoints cannot be used as they are. Read-aloud is low risk: `llama-tts`
is a standalone tool whose code can be called in-process through JNI. Liquid's own `llama-tts` example uses the Q8_0 projector (507 MB), so Phase 0 also checks speech quality with the
smaller Q4_K_M one. Live Talk is the real risk: its streaming
turn-taking lives in the server's `/v1/realtime` code, which would have to be driven in-process too. Phase 0 proves
both. Fallback for read-aloud only: Android's system text-to-speech with an offline voice. That hands the answer text
to the system speech app (a separate app that does have network), so it would be an opt-in setting, off by default.

**Speed and memory estimates for the Pixel 10 Pro** (Tensor G5: 1× Cortex-X4, 5× Cortex-A725, 2× A520; 16 GB RAM
[16]). Nobody has published LFM3 numbers on this chip. Nearby points: LiteRT LFM3-1.7B on a Pixel 8a gives 12–15
tokens/s and on a Galaxy S26 27–36 tokens/s, CPU and GPU runs alike, at 2K–4K context [12]; llama.cpp on an iPhone 17 Pro gives 43.6, backend not stated (likely its GPU) [13]. *Estimate* for
llama.cpp Q4 on the G5's CPU: about 20–30 tokens/s decode and a few hundred tokens/s prefill, using about 2–3 GB of
memory for Omni plus its projector at a few thousand tokens of context. Sustained runs will throttle. The GPU is
untested for this: the only data point is one unconfirmed 2025 Vulkan score, well below the Pixel 9 Pro's and possibly
a driver issue [20]. So the plan starts on the CPU and treats the GPU as a bonus. Phase 0 measures all of this with
`llama-bench`.

## 3. Network and privacy: two apps

The new promise: **no server of ours, all inference and features on the phone, no telemetry, no accounts. The
network is used only for web search, opening pages the search found, and model downloads.**

v1 made "no network" an operating-system fact by leaving out the `INTERNET` permission. v2 keeps that fact for
everything personal by splitting the product into two apps, signed with the same key:

| | **LFM** (the assistant) | **LFM Net** (a small helper) |
|---|---|---|
| Holds | Models, chats, notes, ideas, journal, to-dos, contacts and calendar access, the network log | No personal data: only its settings (provider address, key, proxy) and its latest result addresses, to check `fetch`. Answers go straight back to LFM; a download is streamed to LFM through a file descriptor (a pipe), not stored |
| `INTERNET` permission | **No.** Android refuses every network socket it opens | Yes |
| What it can do | Everything except the network | Exactly three calls: `search(query)`, `fetch(url)` (only an address from its own latest search results), `download(model)` (only a pinned model from Hugging Face, which serves files from huggingface.co and its download CDN) |
| Who can call it | | Only LFM: a bound service guarded by a signature-level permission |
| Can be removed | | Yes. Uninstall it and LFM is fully offline, as in v1 |

Why two apps rather than one app with a network allow-list: with one app, any bug or library could send the journal
out; the allow-list is a promise. With the split, the app holding your data still cannot reach the network at all,
and the only things that ever cross to LFM Net are a search query, a result address, or a model name, each shown in
a network log. The one remaining channel is the search query itself, which is why queries are checked and, by
default, shown to you before they go (section 4). No code of ours runs on any server; there is no account with us.

How each guarantee is enforced:

| Guarantee | Enforced by |
|---|---|
| Personal data never reaches the network directly | LFM has no `INTERNET` permission; a build check fails if any dependency adds it |
| Only queries, result addresses and model names leave | LFM Net's whole interface is those three calls; `fetch` refuses any address that was not in its own latest results (so a page cannot make the model send text out inside a made-up URL); `download` takes only pinned model names; it has no access to LFM's storage |
| Links and maps never leak silently | Opening a link in the browser or a place in Maps hands it to another app, so it is always a tap on a card that shows the full address |
| No telemetry, crash reports or accounts | Neither app includes Firebase, Play Services, analytics or crash SDKs; dependency allow-list checked in the build |
| You see every request | LFM Net reports each request (time, host, what was sent, size) back to LFM, which keeps the log under Settings → Privacy |
| You control search | Search is Off, Ask first (default) or Automatic, for Wikipedia and web alike; each query can be edited before it goes; any query written after reading web text always asks |
| Offline any time | One switch turns LFM Net off; uninstalling it is the hard version |
| Data stays on the phone | `allowBackup="false"` and data-extraction rules that exclude everything from cloud backup and device-to-device transfer; data leaves LFM only when you tap Export, Share or Copy, or when a tool writes into another app (below) |
| Tool writes are not hidden syncs | An event, contact or alarm written by a tool belongs to that app: Google Calendar or Contacts sync it to your Google account if you use one. So LFM writes events to its own **on-phone calendar** (a local calendar, never synced) unless you pick another calendar, and the confirmation card names the calendar |
| Optional lock | Biometric lock for the whole app or only the Journal |

**Model downloads.** LFM Net can download the GGUF files from Hugging Face (huggingface.co, no login) and hand them
to LFM, which checks each against a pinned SHA-256 before use. Manual import through the file picker stays, for a
fully offline phone.

## 4. Web search, kept private

What leaves the phone for one search: **the search words**, written by the model from your question and shown to you
first (in "Ask first" mode), **plus your API key** if the provider needs one. No chat history, no names from your
contacts, no journal text, no location, no cookies, no device ID; a fixed user agent naming only the app ("LFM-Net/1.0"), the same for everyone. The model has no
location tool: a place is in a query only if you said it. Opening a result sends that page's address to that site, like a browser
does. Your IP address is visible to the search provider and to opened sites; a VPN or Orbot hides it (optional
setting: route LFM Net through a SOCKS proxy).

Before a query goes out, LFM checks it on the phone for personal data (names in your contacts, phone numbers,
e-mail addresses, addresses and places from your notes, and names of people and places mentioned in your journal) and warns you if
any is in it.

**An API key is an identity.** Brave and Mojeek need an account with a payment card [25][26], so every query sent
with that key is tied to you and, as reported, kept up to 90 days [25][27]. Only your own SearXNG and Wikipedia avoid that.

Search providers (the owner picks one in Settings; all keys stay on the phone):

| Provider | Account | Cost | Privacy | Fit |
|---|---|---|---|---|
| **Your own SearXNG** | None with anyone | Free (runs on your own computer or server) | No account, but Google, Bing and the others still see each query, coming from your home IP; you control your server's logs. JSON output must be on in its settings [24] | Best privacy, if you have a machine to run it |
| **Brave Search API** | Your own Brave key, card required | $5 per 1,000 searches. The $5 monthly credit needs public attribution on a project website, which a private app does not have, so count on paying from the first query; no spending cap is listed [26] | Own independent index. Reported: query logs kept up to 90 days; zero retention only for enterprise customers [27] (not confirmed on Brave's own pages) | Best results without a server |
| **Mojeek API** | Your own Mojeek key, card required | £2 per 1,000; free starter credit [25] | Own index in Europe; 90-day retention, zero retention on request [25] | Independent, EU-based |
| **Wikipedia** | None | Free | Wikimedia's normal logs | Facts only, no general web; follows the same search mode |
| Public SearXNG servers | None | Free | Unknown operators; many turn JSON off [24] | Not recommended |

Recommendation: **your own SearXNG plus Wikipedia**, which keeps the "no accounts" promise. It is your server, not
ours, but it is a server: it needs a home computer or small server you run. The phone reaches it over your own
WireGuard VPN (no account; the server is not exposed to the internet), or over HTTPS with a password if you
prefer. If you would rather not run one, Brave gives the best results, at the cost of an
account tied to your card.

**Reading pages.** LFM Net fetches a result page as text (only addresses from its latest results); LFM strips it to the article on the phone and the model answers
with numbered sources. Option: "Snippets only" never opens pages.

**Prompt injection.** A web page can contain text written to steer the model ("send this to…"). So: any action the
model proposes while web text is in the conversation always needs your tap, even for actions you have set to
"just do it", and the confirmation says "suggested after reading web pages".

## 5. Doing things on the phone (tool calls)

**What LFM3 brings.** LFM3-1.7B was post-trained for tool use and agent harnesses: BFCL V4 71.4 and ToolSandbox
80.4, ahead of Qwen3.5-9B on both [1][2]. Tools are passed through its chat template and calls come back as
`<tool_call>` blocks [2]; llama.cpp supports grammar-constrained sampling, which can force valid arguments [17]; its built-in parser is documented for LFM2 and LFM2.5, so
whether it already reads LFM3's new format is checked in Phase 0 (if not, LFM parses the `<tool_call>` block itself). Omni's GGUF
supports tool calls during live voice conversations [9], but Omni scores lower on Liquid's internal tool eval (52.2 with audio input [5]; Audio-2B 58.5 [4]), and there is no
public benchmark for either. So the **text model plans and calls tools**; Omni hears and sees.

**What Android lets a sideloaded app do** (each tool is a small Kotlin function the model can call):

| Area | Tool | How on Android | Permission | Confirm? |
|---|---|---|---|---|
| Own data | Add/find/edit notes, ideas, journal, to-dos | LFM's own database | none | No |
| Reminders | Remind me at a time (the default for "remind me"; Clock alarms only when you say "alarm") | LFM's own notification via `AlarmManager` exact alarm | notifications, exact alarm | No (shown in chat; undo) |
| Alarms, timers | Set alarm or timer in the Clock app | `AlarmClock.ACTION_SET_ALARM` / `ACTION_SET_TIMER` with `EXTRA_SKIP_UI` [23] | `SET_ALARM` (normal) [23] | **Yes** (Android has no call to delete an alarm, so no undo; you remove it in Clock) |
| Calendar | Read today and upcoming | `CalendarContract` provider | Calendar (runtime) | No |
| Calendar | Add or move an event (no guests: adding a guest makes Google e-mail them) | Write through `CalendarContract`, or open Calendar prefilled (`ACTION_INSERT`) [23] | Calendar (runtime) | **Yes** |
| Contacts | Look up a person, number, birthday | `ContactsContract` | Contacts (runtime) [23] | No |
| Messages | Text someone | Open Messages with recipient and text filled (`ACTION_SENDTO`); you press Send. Sending silently needs `SEND_SMS`, a *hard-restricted* permission: it is granted only if the installer allow-lists it, which `adb install` does by default; on Android 15+ sideloaded apps may also need "Allow restricted settings" [28][29]. Phase 0 tests an adb install | none | **Yes** (you send it) |
| Calls | Call someone | Open the dialer with the number (`ACTION_DIAL`) | none | **Yes** |
| Files, photos | Find a photo, read a file, save an export | Photo picker, Storage Access Framework folders you grant | none / per folder | No (read); **Yes** (save) |
| Settings | Torch, ring/vibrate/silent, volume, Do Not Disturb | `CameraManager.setTorchMode`, `AudioManager`, notification-policy access | DND access (granted once in Settings) | No (undo) |
| Settings | Wi-Fi, Bluetooth, mobile data | Apps cannot switch these; LFM opens the system panel (Internet, Wi-Fi) or the Bluetooth settings screen | none | (you tap) |
| Maps | Directions to a place | `geo:` / navigation intent (hands the place to Maps) | none | **Yes** |
| Web | Search, open a page | Through LFM Net | (LFM Net) | Per search mode |

Not in this plan, said plainly: reading your SMS (hard-restricted, as above) or other apps' notifications (needs
notification access, which a sideloaded app only gets after "Allow restricted settings" by hand; possible, but a
big privacy door), sending WhatsApp/Signal messages without their screen, and calling other apps' functions. Android 16's **AppFunctions** is the future route for that, but it is experimental and "only a
limited number of apps and system agents can access the entire pipeline" [22]; it is a follow-up, not in this plan.

**Being the phone's assistant.** LFM can be set as the default digital assistant app, so holding the power button
(or a corner swipe) opens it straight into voice. A Quick Settings tile and a home-screen widget add "Capture" in
one tap.

**Safety rules for tools.** Each action shows a confirmation card with the exact fields (who, what, when) and Edit,
Cancel and a confirm button named for the action (Add, Send, Call). Per tool, the owner picks Always ask / Just do it; sending messages, calling, adding events, alarms
and timers, maps and saving files start as Always ask (alarms and timers because they cannot be undone). A history of every action is kept on the phone.

## 6. Use cases, ranked

Ranked by how often the owner would use it, then by how proven the pieces are on a phone.

| # | Use case | Models | Honest limits |
|---|---|---|---|
| 1 | **Capture anything**: say or type a thought; it becomes a note, idea, to-do, reminder or journal line, sorted for you | Omni (voice) → 1.7B (sorts, tags) | Sorting can be wrong; one tap moves it. Voice transcripts are not perfect (6.4% WER in English) [5] |
| 2 | **Ask anything, with the web when needed**: answers from the model, or from a search with numbered sources | 1.7B + search | A 2B model knows little on its own; good answers need search; search costs a query and shows on the provider's logs |
| 3 | **Do it for me**: "remind me at 6 to call Mum", "dentist Tuesday 3 pm", "10-minute timer", "text Sam I'm late" | 1.7B tools | BFCL 71 means some calls will be wrong; every risky action is confirmed. No silent SMS |
| 4 | **Ask about this**: camera, photo, screenshot, short video, or a document | Omni (photos, video, speech); VL (documents, OCR) | Facts about what it sees can be wrong; video sampled at 2 fps; a 20-page PDF takes minutes (*estimate*) |
| 5 | **Journal and look back**: daily prompt, voice entries, "what was on my mind last month?" | 1.7B over local search | Recall over months relies on on-phone keyword search first; the model reads only what is found |
| 6 | **Talk**: hands-free conversation, answers spoken | Omni | In-process live voice is unproven (Phase 0); quality outside English unstated; drains battery |

**Models kept in memory.** While the app is in use, Omni (about 1.4 GB with projector) and the text model (about
1.05 GB) both stay loaded, so a voice capture can go straight to a tool call without a reload (both unload after a
few idle minutes); VL loads only for documents. With KV caches this
is *estimate* about 4 GB of the phone's 16 GB.

## 7. App design

### Look and feel

Minimal and calm: one neutral surface, one accent (blue), the phone's system font, big touch targets,
plain line icons, no gradients, no glows, no shadows; layers are told apart by tone and a 1 px line. Light and dark
themes follow the phone; Material You colours are off by default so the look is stable. Status-bar safe areas and the
gesture bar are respected.

### Structure

A bottom bar with four places. The composer (type, mic, camera) sits on Today and Chat; holding the power button
opens voice from anywhere:

- **Today**: a composer ("Ask, capture or do…") with mic and camera; below it today's agenda (calendar, reminders,
  to-dos due) and the latest captures.
- **Chat**: the assistant thread; it opens full screen (bottom bar hidden), like a messaging app. Answers, search results with sources, tool cards, pictures, voice.
- **Library**: Notes, Ideas, Journal in one place with tabs, and one search box over all of it.
- **To-dos**: lists, due dates, done.
- Settings from the avatar on Today.

### Screens (all drawn below, dark and light)

1. **Today** — composer, agenda, latest captures, status chips ("On this phone", search mode).
2. **Chat** — thread with a spoken question, a tool result ("Reminder set, 18:00, Undo"), and a follow-up.
3. **Search results** — the query that was sent (editable, provider named), the answer with numbered sources, the
   sources list, "Nothing personal was sent" line.
4. **Tool confirmation** — a bottom sheet over Chat: "Add to calendar", fields, Edit / Cancel / Add; a warning
   line when web text was involved.
5. **Notes** (Library tab) — list with voice-note markers and tags.
6. **Ideas** (Library tab) — short cards, grouped by topic the model suggests, "expand this idea" action.
7. **Journal** (Library tab) — today's prompt, entries by day, mood dot, lock icon.
8. **To-dos** — Today / Upcoming / Someday; checkboxes; source chip ("from voice note").
9. **Ask (camera)** — viewfinder, question, shutter, video switch.
10. **Talk** — listening / speaking states, live transcript, stop.
11. **Settings** — Models, Search (provider, mode, proxy), Tools (per-tool ask/do), Privacy (network log, lock,
    export, delete), Voice, Battery.
12. **Privacy and network log** — what is guaranteed, LFM Net switch, every request today, export, delete all.
13. **Import / Models** — installed models; download through LFM Net with progress and hash check; import from a file.

### Key flows

- **Capture by voice:** hold power → "Buy oat milk and remind me to call the plumber at 9" → Omni transcribes → 1.7B
  makes a to-do and a reminder → two small cards, each with Undo.
- **Ask with search:** question → model decides it needs the web → (Ask first) query card "Search for 'plumber
  call-out fee Leeds 2026'" with Edit / Send → LFM Net fetches results and the top three pages → answer with
  numbered sources.
- **Do something risky:** "Add dinner with Ana Friday 8 pm" → confirmation sheet with title, time, calendar → Add
  → event added → "Added. Undo".
- **First run:** pick search mode (Off is fine) → download or import Omni and text models (2.5 GB, with hash checks)
  → grant permissions only when a tool first needs them.

### Model management

Models screen: each model with size, quantisation, verified mark, which modes use it, Delete. Download through LFM Net
(progress, pause, hash check) or import a file (and offer to delete the original). One tap sets which models stay in
memory.

### Battery and heat

As v1: six inference threads aimed at the performance cores; listen to Android's thermal status and slow down or
pause background jobs when hot; long jobs in a foreground service with a stop button; unload models after a few idle
minutes (Omni and text reload in seconds). Search and fetches are small network jobs.

### Tech stack

- Kotlin, Jetpack Compose, Material 3 components with a flat custom theme.
- llama.cpp (git submodule) built with the NDK for `arm64-v8a`, `LLAMA_BUILD_MTMD=ON`, network code off, behind a JNI
  layer: load, generate (streamed), image and audio input, speech output, tool-call parsing with grammars [15][17].
- Room with SQLite full-text search (FTS4 through Room; FTS5 through raw SQL if needed) for notes, ideas, journal, to-dos and chats (on-phone search); DataStore for settings.
- CameraX, `AudioRecord` 16 kHz, `AudioTrack` 24 kHz, `PdfRenderer`, Photo Picker.
- `VoiceInteractionService` to be the default assistant; a Quick Settings tile and a Glance widget.
- LFM Net: a separate tiny app (OkHttp, no other libraries), bound service with a signature permission.
- minSdk 36 (Android 16); sideloaded APKs, not the Play Store.

## 8. Build plan (follow-up tasks)

1. **Phase 0, spike.** On the Pixel: speed, memory, heat and battery drain for text, Omni and VL at Q4/Q8; GPU
   (Vulkan) test; whether LFM3 converts for the Tensor TPU (Tensor ML SDK beta [19]); Omni speech in and out
   in-process; llama.cpp parsing of LFM3 tool calls; tool-call accuracy of 1.7B on a 50-command test set of the
   owner's own phrasing; `SEND_SMS` on an adb-installed APK. Output: numbers, a go/no-go for Talk, and the tool list
   that works well enough.
2. **Phase 1, core.** LFM shell (Today, Chat, Settings), no-`INTERNET` build check, models import, chat with 1.7B.
3. **Phase 2, capture and library.** Notes, ideas, journal, to-dos, FTS search, voice capture with Omni, sorting.
4. **Phase 3, tools.** Tool registry, confirmation sheet, reminders, alarms, calendar, contacts, messages, calls,
   settings; action history and undo.
5. **Phase 4, LFM Net and search.** Helper app, providers, query preview, personal-data check, page reading, sources,
   network log, model downloads.
6. **Phase 5, see.** Camera, photos, video clips, documents (VL).
7. **Phase 6, talk.** Live Talk and read-aloud; default-assistant role; Quick Settings tile and widget.
8. **Phase 7, polish.** Thermal handling, lock, export and wipe, accessibility pass.

## 9. Sources

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
21. Software Mansion, ExecuTorch exports of LFM3 — https://huggingface.co/software-mansion/react-native-executorch-lfm-3
22. Android Developers, AppFunctions overview — https://developer.android.com/ai/appfunctions
23. Android Developers, Common intents (alarms, calendar, contacts) — https://developer.android.com/guide/components/intents-common
24. SearXNG, Search API — https://docs.searxng.org/dev/search_api.html
25. Mojeek, Web Search API — https://www.mojeek.com/services/search/web-search-api/
26. Implicator, "Brave drops free Search API tier", 8 Jun 2026 — https://www.implicator.ai/brave-drops-free-search-api-tier-puts-all-developers-on-metered-billing/
27. AI Agent Index, Brave Search API (retention) — https://theaiagentindex.com/agents/brave-search-api
28. AOSP change marking SMS permissions hard-restricted (mirror) — https://gitlab.e.foundation/e/os/android_frameworks_base/-/commit/091624743720038b7f73138c8247bc0fedc4820c
29. Android Authority, Android 15 restricted settings for sideloaded apps — https://androidauthority.com/android-15-restricted-settings-sideloading-3481098

## 10. Open questions for the owner

1. Is the two-app split (LFM with no network, LFM Net for search and downloads) right, or is one app with a network
   allow-list enough for you?
2. Search provider: your own SearXNG (no account; do you have a computer or server to run it?), or Brave with your
   own key (best results, but an account with your card; queries reportedly kept up to 90 days)?
3. Search mode by default: Ask first (recommended), Automatic, or Off?
4. Which actions may run without asking (with undo)? Proposed: reminders, torch, sound mode, Do Not Disturb.
   Alarms and timers ask, since they cannot be undone.
5. Messages: is "Messages opens with the text ready, you press Send" fine, or should Phase 0 try silent sending
   (restricted for sideloaded apps; may not be possible)?
6. Should LFM be the phone's default assistant (power-button hold), replacing Gemini?
7. Languages you use? Voice quality outside English is unknown.
8. Journal: lock it separately?
9. Q4 (smaller, faster) or Q8 (more accurate; Omni about 2.4 GB) as the default?
