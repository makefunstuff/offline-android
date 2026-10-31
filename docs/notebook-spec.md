# Inkling: private notebook (LP-35)

The owner: "I would like a private personal notebook with LLM assistance. We won't use it for programming. It's mostly
for ideation, reading, privacy stuff, scheduling. The UI is too generic right now: find something unique and
convenient, so I use it daily and privately." And: "The results should be proper suggestions to user experience."

So Inkling stops being a chat app with a notebook bolted on and becomes **a notebook with an assistant in it**. This
file holds the ranked UX proposals, then the screen rules the build follows. [ux-spec.md](ux-spec.md) (LP-12) still
holds for everything not named here: palette, editing rules, motion, accessibility. Where they disagree, this file wins.

Written 31 October 2026. The mockups (dark and light) are in the LP-35 artifact.

## 0. What is generic today

Walking the current build:

- **Home is a dashboard.** Today stacks a chat box, five capture chips, four status pills, an agenda and "Just
  captured". It answers "what can this app do", not "what is my day". Nothing on it is *yours* except three cards.
- **Writing is a form.** To jot one thought you pick a kind first (Note, Idea, Journal, To-do), then a full-screen
  editor opens. Every good notebook lets you write first and sort later.
- **The assistant lives in a separate room.** To get help with a note you leave it, open Chat and paste. Chat sits in
  the bottom bar as one of four equal places, so the app reads as a chat app.
- **Days don't exist.** Entries are a flat list by kind; there is no "what did I think on Tuesday", no carrying
  yesterday's unfinished to-dos forward, no rhythm that brings you back each morning.
- **Reading has no way in.** Highlighting a line in a browser or e-book and keeping it means copy, open Inkling, pick
  Note, paste.
- **Privacy is a pill.** "On this phone" is one grey chip among four; nothing shows what did or didn't leave.

Coding: nothing in the app is aimed at programming (no code blocks, no code tools; the system prompt has no coding
role), so nothing had to be dropped. The model card's own advice ("not for agentic coding") stays true by design.

## 1. The proposals, ranked

Ranked by how much daily use they unlock per unit of effort. 1–6 are built in LP-35; 7–8 are follow-ups.

### 1. The Daily Page is home *(signature)*

- **Daily problem.** Opening the app shows a dashboard; there is no single place where today's thoughts, plans and
  reading live together, and no reason to come back tomorrow.
- **How it works.** Home is today's page, like a paper journal opened at today. At the top: the date, a one-line
  brief ("3 events · 2 to-dos · 1 carried over"), and today's timed things (events, reminders, timed to-dos) as a
  slim timeline. Below: **everything you wrote today, in order, as lines on the page**: a to-do is a line with a box,
  an idea a line with a spark, a quote a highlighted line, a journal line in a softer weight. Tap a line to open it;
  tick a box in place; long-press a line to pin it, make it another kind or delete it (Undo). **Swipe the page left or
  right** (or tap the arrows by the date) to turn to yesterday and back; past days show what you wrote and finished
  that day. (Lines don't take sideways swipes, so a swipe anywhere on the page turns it.)
- **Why it beats the generic UI.** One place answers "what's today" and "what was I thinking". The page fills up as
  the day goes, which is the habit loop journals rely on. No chips, pills or kind pickers on the way in.
- **Borrowed.** Daily notes as home: Logseq, Roam, Obsidian Daily Notes, Reflect. Day-by-day pages: Day One, Bullet
  Journal. **New here:** the page mixes every kind (to-do, idea, quote, journal) as lines, sorted for you (proposal
  2), and stays private by construction.

### 2. The Ink line: write first, it sorts as you type *(signature)*

- **Daily problem.** Picking "Note / Idea / Journal / To-do" before writing is a decision you shouldn't have to make
  thirty times a day, and the current chat box sends everything to the model and the Chat screen.
- **How it works.** One line is always at the bottom of the Daily Page, in thumb reach: "Write a line…". As you type,
  a **glyph** at its left shows where the line will go, guessed on the phone, without the model, in under a
  millisecond: "buy oat milk" shows a box (to-do), "what if the garden had…" a spark (idea), "felt tired after the
  call" a journal mark, "remind me at 6 to call Ana" a bell (reminder at 18:00), a line ending in "?" a speech mark
  (ask Inkling). **Tap the glyph to change it** (it cycles; long-press lists them). Enter puts the line on the page
  and keeps the keyboard up for the next one. A line saved as a note can be opened and grown into a full note.
- **Why it beats the generic UI.** Capture becomes typing, nothing else: zero taps for the common case, one tap to
  correct. The glyph makes the guess visible *before* it acts, so it never surprises.
- **Borrowed.** Bullet Journal's rapid logging and signifiers; Things' Quick Entry; Drafts' "text first, act later".
  **New here:** the signifier is chosen live by an on-phone sorter and shown before you commit; questions go to the
  assistant from the same line.

### 3. Ink in the margin: the assistant inside a note *(signature)*

- **Daily problem.** Help with a note (sum it up, pull out the to-dos, push an idea further) means copying it into
  Chat and back.
- **How it works.** In the editor's top bar, an **Ink** button (the highlighter mark). It opens a short sheet: *Sum
  it up · Pull out to-dos · Questions to think about · Keep going*, plus a line to ask anything about the note. The
  answer is written **in the margin of the note itself**: a block under your text with a yellow highlighter edge,
  labelled "Inkling · on this phone", streaming in. When it ends: **Add to note** (moves it into your text, marked as
  Inkling's), **Add 3 to-dos** (for pulled-out to-dos; they land on today's page), or **Dismiss**. Nothing touches your
  text unless you tap Add.
- **Why it beats the generic UI.** The assistant comes to the text, not the other way round; your words and its words
  stay visibly separate (yellow edge), which matters in a private notebook.
- **Borrowed.** Notion AI and Apple Writing Tools (actions on the current text); iA Writer's separation of
  authored and pasted text; Readwise's "review" questions. **New here:** it runs on the phone, the margin block is a
  proposal you accept, and "Pull out to-dos" turns a note into tomorrow's plan in one tap.

### 4. Carry over: yesterday's unfinished to-dos, one tap each

- **Daily problem.** To-dos past their day silently pile up under "Today" in a list, or get forgotten.
- **How it works.** On the Daily Page, above today's lines, a **Carried over** block lists open to-dos from earlier
  days. Each has three actions: tick it, **Today** (moves it to today), **Later** (no date, to Someday). "All to
  today" for the lot. The block is gone once it's empty.
- **Why.** Deciding about leftovers is a 5-second morning ritual instead of a growing guilt list.
- **Borrowed.** Bullet Journal "migration"; Things' "This Evening / Later". **New here:** it lives on the same page
  as the rest of the day, not in a separate task app.

### 5. Reading: share any text to Inkling, it lands as a highlight

- **Daily problem.** Keeping a line from an article, e-book or message takes four steps and loses where it came from.
- **How it works.** Select text in any app → Share → **Inkling**. It lands on today's page at once as a **highlighted
  quote** (yellow marker under the text), with where it came from (the page title or link the app passed) and the tag
  *reading*. Inkling opens on today's page with "Saved to today's page · Undo". The Notebook gets a **Reading**
  filter that gathers every quote. Ink in the margin (3) then works on them ("Questions to think about").
- **Why.** Reading notes are the backbone of ideation; one share is all it should take, and the highlight looks like
  what you did in the book.
- **Borrowed.** Readwise, Apple Notes' share extension, Kindle highlights. **New here:** the quote never leaves the
  phone, and it sits in the day you read it, next to what you thought about it.

### 6. The seal: privacy you can see at a glance

- **Daily problem.** "Private" is a claim; the owner has to trust it without seeing it.
- **How it works.** Under the date on the Daily Page, one quiet line with a shield: **"Sealed · nothing left this
  phone today"**, or "Sealed · 2 searches sent today" (from the network log), or "Sealed · Journal locked". Tap it
  for Settings → Privacy (the full log, the lock, Delete everything). The assistant's margin blocks say "on this
  phone" too.
- **Why.** A daily, honest count is more reassuring than a badge, and it costs a glance.
- **Borrowed.** iOS's App Privacy Report and the mic/camera indicators; Signal's "sealed sender" wording. **New here:**
  it counts what actually left (the helper app's log), per day, on the home page.

### 7. Three places, not four (built)

The bottom bar becomes **Today · Notebook · Plans**. Chat leaves the bar: it is reached from the Ink line (a "?"
line), the **Ask** button by the date, the assistant gesture, and Talk, as before. Library is renamed Notebook (it
gains the Reading filter), To-dos is renamed Plans. The app now reads as a notebook first.

### 8. Follow-ups (not built here)

- **Evening close.** After 20:00 the Ink line's hint becomes "How was today?" and a journal line written then gets a
  gentle prompt; a weekly review page every Sunday (Day One's "On this day", Stoic's prompts).
- **Resurface.** "A week ago / a month ago" lines at the foot of the Daily Page.
- **Ink in the margin, automatic.** Suggestions that appear on their own after a pause (a faint yellow dot in the
  margin) once the model is fast enough on the Tensor G5 (LP-25, LP-32's server).

## 2. Screens (build rules)

### Today (the Daily Page)

- **Header.** Date as the title ("Saturday 31", month small above), arrows ‹ › for the day before and after (› hidden
  on today), **Ask** (chat) and Settings buttons. Under it the seal line (6) and the brief.
- **Timeline.** Today only: events, reminders and timed to-dos, slim rows (time · title), the next one marked.
- **Carried over.** Today only, when not empty (4).
- **The page.** Lines of the day, oldest first, so the page reads top to bottom like a journal; the newest is
  nearest the Ink line. A line belongs to a day when it was written that day, a to-do also when it is due or was
  ticked off that day. Each line: glyph, title, one muted line of body if any. Quotes: marker under the text, source
  muted. Done to-dos: struck through, muted. Empty page: "A blank page. Write a line below — it sorts itself."
- **Ink line.** Pinned at the bottom (today only; past days show "Back to today" there). Glyph button, text field,
  mic, send. While typing, a hint above it says where the line goes ("To-do · today", "Reminder · today 18:00"). The
  mic fills the line with what it heard, to be read before it goes on the page. A to-do written here is due today. Keyboard rules from ux-spec section 6; the page keeps the last line in view as lines are added.
- **Day swipe.** Horizontal swipe past a third of the width turns the page; the new day slides in on the shared X
  axis (ux-spec section 7, 300 ms). Days with nothing still open (a blank page).
- **States.** No model: the Ink line still works (the sorter is rule-based); "?" lines go to Chat, which says how to
  get a model.

### Editor

- Unchanged (LP-24's keyboard handling stays), plus the **Ink** button in the top bar and the margin block at the
  end of the page (3). One Ink request at a time; leaving the editor stops it.

### Share target

- `ACTION_SEND` with `text/plain` on the main activity. Title: the shared subject, else the first words. Body: the
  quote; source line: subject and link if given. Kind Note, tag `reading`, source `share`. Locked app: saved after
  unlock.

### Notebook (was Library)

- Filter chips All · Notes · Ideas · Journal · **Reading** · Pinned. Reading rows show the quote with the marker.

## 3. Data

No schema change: the Daily Page reads entries by `createdAt` (indexed) and to-dos by `doneAt` and `dueAt`; quotes
are notes tagged `reading` with source `share`. Every existing note, idea, journal line and to-do shows on the day it
was written, so the owner's data carries over as is. The Room schema stays at version 5.

## 4. Privacy and permissions

No new permission. The share target receives text only; nothing is fetched from a shared link. The main app keeps no
`INTERNET` permission; `checkDebugNoNetwork` and `checkDebugDependencies` still guard it.
