# Inkling: UX spec (v1)

The app that was called LFM is now **Inkling**: the owner's private, on-device assistant for the Pixel 10 Pro. This
spec says how it should feel to use, screen by screen, and is the yardstick for the UI and UX work in LP-12. The look
stays as in [spec.md](spec.md) section 7 (calm Signal, Ink + highlighter); this file covers structure, flows, states,
editing, motion and accessibility. Where the two disagree on behaviour, this file wins.

Written 31 October 2026 after walking every flow of the Phase 1–5 build in the emulator. The analysis, with
screenshots, is in the LP-12 artifact; the friction list is summed up in section 1.

## 1. What was wrong (analysis)

The owner's words: "Notes and other stuff too clunky, also there is no message enqueue mechanism, no possibility to
load/unload models, and also UX is lacking." Walking each flow confirmed that and found more.

| # | Where | Friction | Seen in | Best-in-class does |
|---|---|---|---|---|
| 1 | Note editor | A bottom-sheet form with Save; **Back or a swipe down throws away what you typed** | Keep, Bear, Apple Notes | Full-screen editor, autosave, Back saves |
| 2 | Note editor | The text field isn't focused: open, then tap again before typing | Keep, Things | Keyboard up at once |
| 3 | Capture | Four kinds, four places; a new to-do is a form with a typed date ("e.g. tomorrow 17:00") | Things Quick Entry, Todoist | One quick-add line; date chips |
| 4 | Lists | No swipe actions, no pinning, delete has no undo | Keep, Gmail, Things | Swipe to done/delete, pin, Undo snackbar |
| 5 | Library search | Search icon → field appears unfocused → tap again; no filters | Keep, Bear | Search field always there, filter chips |
| 6 | Chat | **Sending while it answers does nothing**; text just sits there, no sign why | Claude, ChatGPT (Stop), Claude Code (queue) | Queue the message, show it queued |
| 7 | Chat | "Stop" is a small underlined word under the composer | Claude, ChatGPT | A clear Stop button where Send is |
| 8 | Chat | New chat silently drops anything waiting | — | Waiting messages move to the new chat |
| 9 | Chat | No sign of which model is loaded, or whether the first answer will wait for a load | LM Studio, PocketPal AI | Model and its state in the header |
| 10 | Models | **No load or unload**, no memory use, no choice of chat model; auto-unload is fixed at 5 min and hidden | LM Studio (eject, TTL), PocketPal (load/offload, RAM) | Per-model Load/Unload with memory, idle timer, default pick |
| 11 | Models | Delete is a bare trash icon next to the row, no confirm, for a 1 GB file | Any file manager | Confirm what is lost |
| 12 | First run | Nine Download buttons to choose from; the recommended set is three separate taps | PocketPal, AI Edge Gallery | One "Get the recommended models" |
| 13 | Settings | "Battery & heat" looks tappable but does nothing; Voice opens Models | — | Every row does what it says |
| 14 | Everywhere | The emulator's slow CPU shows long waits with only "…" | — | A named state: "Loading LFM3-1.7B…", "Thinking…" |
| 15 | Icon and name | A white L in a black square; "LFM" is the model's name, not the app's | — | A name and mark of its own |

## 2. Name and icon

Four options were drawn, each as an adaptive icon (ink background, white mark, the highlighter in yellow) with a
themed monochrome version:

| Name | Idea | Mark |
|---|---|---|
| **Inkling** (chosen) | A first hint of an idea; ink, as in the palette | A white lower-case *i* whose dot is a yellow highlighter stroke |
| Margin | Where you scribble notes | Three text lines with a yellow bar in the margin |
| Kept | Kept on this phone | A white K over a yellow underline |
| Hush | Quiet and private | A speech bubble with a yellow stroke inside |

**Inkling** wins: it says notes and ideas, it fits Ink + highlighter, it reads well as an assistant's name ("Ask
Inkling"), and its mark survives at 48 px and as a monochrome themed icon. The helper app becomes **Inkling Net**.
Package names, the repo name (offline-android) and the model names (LFM3-…) stay as they are, so an update keeps all
data and models.

## 3. Navigation model

Four places in a bottom bar, as before; everything else is pushed on top and Back returns.

```
Today ─ Chat ─ Library ─ To-dos         (bottom bar)
  │       │       │         │
  │       │       │         └─ Editor (to-do)
  │       │       └─ Editor (note, idea, journal)
  │       └─ Search page · Camera · Model sheet · Queued message sheet
  └─ Settings ─ Models · Web search · Tools · Action history · Privacy
```

- **Chat** is full screen (no bottom bar), like a messaging app; its header has Back, the model status and New chat.
- **The editor** is full screen, one for all four kinds.
- **Sheets** are only for short decisions: confirm a tool, a queued message, the model sheet, delete a model.
- **Back** never loses anything: the editor saves, the composer keeps its draft, sheets close.
- **Undo** follows every change you can't see coming back: a snackbar at the bottom for 5 s.

## 4. Key flows and their step counts

Steps are taps (typing counts as none). Before → after.

| Flow | Before | After |
|---|---|---|
| New note from the Library | + → tap field → type → Save = 3, lost on Back | + → type = 1, Back saves |
| New note, idea, journal line or to-do from Today | none (only via the assistant) | Kind chip → type = 1 |
| New to-do due today | To-dos → + → tap field → type → tap Due → type "today" → Save = 5 | To-dos → type in "Add a to-do" → Enter = 1 |
| Edit a note | open → tap field → edit → Save = 3, lost on Back | open → tap → edit = 2, Back saves |
| Switch a note to a to-do | open → To-do → Save = 3 | long-press → Move to To-dos = 2, Undo |
| Delete a note | open → Delete = 2, no undo | swipe left = 1, Undo |
| Tick a to-do | tap box = 1, no undo | tap box or swipe right = 1, Undo |
| Pin a note | not possible | swipe right = 1 |
| Search the Library | search icon → tap field → type = 2 | tap search field → type = 1 |
| Send two messages in a row | not possible while it answers | type → Send → type → Send |
| Stop an answer | find "Stop" below the composer = 1 | Stop button (where Send is) = 1 |
| Load or unload a model | not possible | header status → Load/Unload = 2 |
| Pick the chat model | not possible (the newest file wins) | chat header → pick it = 2 (with two or more on the phone) |
| First run, recommended models | 3 Download taps + Continue = 4 | Get the recommended models = 1, Continue = 2 |

## 5. Screens

Each screen lists layout, states (empty, loading, error) and gestures. Margins are 16 dp; rows are at least 48 dp tall.

### Today

- **Layout.** Date and a settings button; greeting; the composer ("Ask, capture or do…") with camera and mic; under it
  **capture chips** Note · Idea · Journal · To-do (one tap opens the editor of that kind with the keyboard up); status
  chips ("On this phone", search mode, the chat model's state); Today's agenda (next item marked); "Just captured" (3).
- **Empty.** "Nothing planned. Say or type what's coming up." No model: a card "No model yet · Get models".
- **Gestures.** Tap an agenda row or capture to open it.

### Chat

- **Layout.** Header: Back, **model status** ("LFM3-1.7B · ready", "loading…", "not loaded", "unloads in 3 min" when
  set), New chat. Thread. Queued messages at the end of the thread, after the answer being written. Composer pinned
  at the bottom.
- **Composer.** Always enabled. While an answer is written: the right side shows **Stop** (ink square) next to the
  mic; once you type, Send appears and queues. The placeholder says "Message… it will wait its turn" while busy.
- **Queued messages.** Shown as your bubbles with a dashed edge and "Queued · 1", "Queued · 2". Tap one for the
  queued-message sheet: edit the text, Move up, Move down, Send next, Remove. They run in order once the current
  answer ends; Stop ends only the current answer. **New chat keeps them**: they move to the new chat and run there.
  Queued text is kept on the phone, so it survives the app being closed.
- **States.** Loading the model: "Loading LFM3-1.7B…" in the thread. Thinking: "Thinking…". Error: a notice line
  with what to do. Empty chat: three example prompts you can tap.
- **Scroll.** Stays at the bottom while you are at the bottom; if you scrolled up, a "Latest ↓" button appears
  instead of jumping.

### Model sheet (from the chat header) and Settings → Models

- **Loaded now**: each model in memory with its memory use (estimate, from file size and context), and **Unload**.
  The phone's free memory is shown under the list.
- **Chat model**: with two or more text models on the phone (say Q4_0 and Q8_0), a radio list picks the one chat
  uses; a loaded one is swapped at once. Projectors are listed with their model, not on their own.
- **Unloading** waits for an answer being written with that model, and says so ("Unloads when the current answer
  ends").
- **Auto-unload**: Never · 1 · 5 · 15 · 30 min idle. Tied to the Battery & heat row in Settings.
- **Download / Import** below, as before; Delete asks first ("Delete LFM3-1.7B Q8_0? Frees 1.85 GB. You'd download
  it again to use it."). A loaded model is unloaded before it is deleted.
- **States.** Loading shows a progress line and disables Load; a failed load shows the reason on that row.

### Library

- **Layout.** Title, then a **search field** that is always there; **filter chips**: All · Notes · Ideas · Journal ·
  Pinned. Pinned items first, then newest first. Ideas keep their topic groups when the Ideas chip is on; Journal keeps
  its day groups and the day's prompt.
- **Row.** Kind icon (in All), title (first line), two lines of body, tags, time; a pin mark when pinned.
- **Gestures.** Tap to open. Swipe right: pin/unpin. Swipe left: delete, with Undo. Long-press: the same actions
  in a menu (for those who don't swipe).
- **Buttons.** Ink **+** (new of the current kind, Note in All) and the yellow mic, bottom right.
- **Empty.** Per filter: "No notes yet. Tap + or the mic." Search with no match: "Nothing matches “x”."

### Editor (note, idea, journal, to-do)

- **Layout.** Full screen. Top bar: Back, "Saved", Pin, Delete; under it the **kind chips** Note · Idea · Journal · To-do. Body: one
  big text field; the first line is the title. Under it, by kind: Tags (note), Topic (idea), Mood (journal), and for a
  to-do **due chips** Today · Tomorrow · Next week · No date · Pick… plus List. A quiet "Saved" or "Saving…" word in
  the top bar. A new entry opens with the keyboard up; an existing one opens to read, and a tap puts the cursor there.
- **Saving.** Every change is saved half a second after you stop typing, and at once on Back, on leaving the app and
  on switching kind. A new entry is created on the first character; an entry left empty is removed. Nothing is lost
  on Back, swipe, rotation or the app being killed.
- **Delete** goes back to the list with an Undo snackbar.

### To-dos

- **Layout.** Title; tabs Today · Upcoming · Someday (counts in brackets); a **quick-add line** at the top of the
  list: "Add a to-do". Enter adds it (due today on Today, tomorrow on Upcoming, no date on Someday) and keeps the
  keyboard up for the next one. Rows: checkbox, title (the most urgent marked), due and list.
- **Gestures.** Tap the box or swipe right: done, with Undo. Swipe left: delete, with Undo. Tap: editor.
- **Empty.** "Nothing due today" with the quick-add line still there.

### Settings

- Rows: Models (what is loaded, the chat model), Web search, Tools, Action history, Privacy, Voice (Omni's state;
  opens Models), **Battery & heat** (threads and auto-unload; opens Models), Thinking.
- No highlighter on Settings (spec.md section 7).

### First run

- One screen, two steps. Step 1: **Get the recommended models** (LFM3-1.7B and Omni with its projector, 2.4 GB) as
  the primary button; "Choose models yourself" opens the full list; "Import files instead". Step 2: search mode.
  Continue. Downloads carry on in the background and show on Today until done.

### Camera, Search page, Privacy, Tools, Action history

Unchanged in structure; they get the shared header and the same states.

## 6. Editing rules (everywhere)

1. Text you typed is never lost: not on Back, not on swipe, not on a crash or the app being killed (drafts are saved
   as you type; the chat composer keeps its draft per app run).
2. No Save buttons for your own text; confirmation sheets for the assistant's actions keep their explicit button.
3. Every destructive action on your content (delete, done, move kind) can be undone for 5 s from a snackbar, and
   deletes of model files ask first because they can't be undone.
4. The keyboard comes up wherever the next thing you do is type (new entry, search, quick add).
5. Enter in a single-line field does the obvious thing (add, search); in the editor it is a new line.

## 7. Motion rules

Motion explains where things came from and went; it never decorates. LP-6 did the full pass; everything follows
these rules. Implemented in `ui/theme/Motion.kt`.

| Use | Duration | Easing | Notes |
|---|---|---|---|
| Small state change (chip, switch, button state) | 100 ms (SHORT) | standard | colour/opacity only |
| Something appears in place (snackbar, queued bubble, saved line) | 150–250 ms | emphasized decelerate | fade + 8 dp rise |
| Something leaves (snackbar, removed row) | 150 ms | emphasized accelerate | fade + shrink height |
| List reorder (pin, done, queue order) | 250 ms (MEDIUM) | emphasized decelerate | `animateItem` placement |
| Push a full screen (editor, chat, sub-screens) | 300 ms (LONG) | emphasized | shared axis X: in from 10 % right with fade; Back reverses |
| Switch places in the bottom bar | none | — | instant, as in Signal: they are places, not steps |
| Full-screen mode change (camera) | 300 ms | emphasized | shared axis Z (as now) |
| Swipe release | spring, no bounce | — | follows the finger; the action colour shows past 40 % |

- Nothing animates for longer than 300 ms, and nothing loops except "is working" indicators.
- Streaming text is revealed evenly (LP-6, the owner asked for it): a burst of tokens is shown over about 120 ms
  rather than jumping in; a "working" indicator of three squares stands in until the first words.
- Predictive back: while the back swipe is held the screen scales toward 90 % and rounds its corners (M3 back preview);
  releasing goes back with the shared-axis slide, cancelling springs back without a bounce.
- The highlighter draws in from the left (300 ms) the first time it shows in a run; the mic's outline follows the
  voice level while listening.
- With Android's "Remove animations" on, every transition snaps (`LfmMotion.reduced()`).

## 8. Accessibility

- Touch targets at least 48 × 48 dp; icon-only buttons have a content description ("Stop", "Pin", "New note").
- Text contrast at least 4.5:1 in both themes (the palette's greys pass); yellow is never text.
- Every swipe action is also a TalkBack custom action; in the Library it is also in the long-press menu, and on
  To-dos the checkbox and the editor do the same.
- States are said, not only shown: "Queued, 2 of 3", "LFM3-1.7B loaded, 1.2 GB".
- Font scaling up to 200 % keeps layouts usable: rows wrap rather than clip; the composer grows to 5 lines.
- Snackbars stay 5 s, longer (10 s) with TalkBack on.

## 9. Out of scope for LP-12

Live Talk, read-aloud, widgets and the Quick Settings tile (Phase 6); the full motion pass (LP-6); lock and export
(Phase 7).
