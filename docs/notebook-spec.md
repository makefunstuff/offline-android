# Inkling: private notebook (LP-35) and ADHD organizer (LP-37)

The owner: "I would like a private personal notebook with LLM assistance. We won't use it for programming. It's mostly
for ideation, reading, privacy stuff, scheduling. The UI is too generic right now: find something unique and
convenient, so I use it daily and privately." And: "The results should be proper suggestions to user experience."

So Inkling stops being a chat app with a notebook bolted on and becomes **a notebook with an assistant in it**. This
file holds the ranked UX proposals, then the screen rules the build follows. [ux-spec.md](ux-spec.md) (LP-12) still
holds for everything not named here: palette, editing rules, motion, accessibility. Where they disagree, this file wins.

Written 31 October 2026. The mockups (dark and light) are in the LP-35 artifact.

## A. The ADHD organizer (LP-37)

The owner, next: Inkling should be "an ADHD-friendly organizer with the help of these tiny private LLMs". This part
builds on the notebook below (the Daily Page, the Ink line, Ink in the margin, Carried over, the seal) and changes it
where an ADHD brain needs something else. Where this part and the sections after it disagree, this part wins. The
mockups (dark and light) and the emulator screenshots are in the LP-37 artifact.

Written 31 October 2026.

### A.0 What an ADHD brain needs from a notebook (grounding)

- **Time blindness and the point of performance.** Barkley's model: ADHD is less a problem of knowing what to do
  than of doing it at the moment it matters; time, plans and motivation have to be put *outside* the head, where the
  task happens. So: visible time, one cue at the moment, not a list to remember.
- **Task initiation.** Starting is the hardest part; a big vague task ("sort the flat") stays unstarted. Breaking it
  into a first step small enough to feel silly (2–10 minutes) is the core move of ADHD coaching and of tools like
  Goblin Tools' Magic To-Do. Implementation intentions (Gollwitzer: "when X, I do Y") show that one concrete next
  action beats a goal.
- **Working memory.** Ideas arrive mid-task and vanish; capture must cost a second, and sorting must wait (GTD's inbox,
  Allen; "brain dumps" in ADHD coaching). Every extra decision at capture time loses the thought.
- **Overwhelm and choice paralysis.** A long list reads as a wall; picking the next thing is itself a task. Tools that
  work for ADHD (Llama Life, Tiimo, Structured, Sunsama) show *one thing now* and a short day, not everything.
- **Emotional load.** Rejection sensitivity and shame make red "overdue" counts and streaks backfire: people stop
  opening the app. Gentle wording, easy rescheduling and no penalties keep them coming back (Finch, Tiimo's tone).
- **Interruptions.** Resuming after a break has a cost (Altmann and Trafton's memory-for-goals model): a cue of "where
  you were" cuts the time to get going again.

### A.1 The proposals, ranked

Ranked by how much they help on a bad-focus day per unit of effort. 1–7 are built in LP-37; 8 is the rule every LLM
job follows; 9 are follow-ups.

#### 1. Now: one next thing, at the top of today *(signature)*

- **Daily problem.** Opening the app shows a page of lines and to-dos; choosing what to do first is itself the task
  that never starts.
- **How it works.** Above today's page, one card: **Now** and a single action in large type ("Clear the kitchen
  table"), with its time ("about 10 min") and where it comes from ("Step 1 of 4 · Tidy the kitchen", or "1 of 3 in
  today's plan"). Four answers: **Start** (opens Focus, proposal 5), **Done** (the next one slides in), **Not now**
  (shows the next one; it comes back later today) and **⋯** for *Smaller steps* (proposal 2), *Tomorrow*, *Someday*.
  It picks: the first open step of the first unfinished thing in today's plan; else today's plan in order; else what
  is due now (a timed to-do in the next hour); else open to-dos of today and earlier, oldest first. Nothing open: "Nothing
  next. Write a line below, or plan your day."
- **Why it helps an ADHD brain.** It removes the choice. One cue at the point of performance, worded as an action, with
  its size shown so it feels doable.
- **Borrowed.** Llama Life and Things' "Today" focus; GTD's "next action". **New here:** the card walks through the
  steps the model wrote (proposal 2), skipping is free and never counted, and it all runs on the phone.

#### 2. Smaller steps: the model breaks a task down and sizes it *(signature)*

- **Daily problem.** "Do taxes" sits on the list for weeks because nobody knows where it starts.
- **How it works.** *Smaller steps* on the Now card, on a to-do's long-press menu, and in the to-do's editor. The
  model writes 3–6 tiny first steps, each with minutes ("Find last year's tax letter · 5 min"). They show as a
  proposal sheet (the margin's rule: nothing changes until you tap **Add steps**); a step can be removed first.
  Steps belong to the to-do: Now walks through them, the to-do shows "2 of 5 steps", and ticking the last step ticks
  the to-do (Undo). Every to-do also gets a size: the model's, or a quick guess on the phone ("call" 5 min, "write" 30
  min), changed by one tap in the editor (5 · 15 · 30 · 60 min).
- **Why it helps.** The first step becomes small enough to start, and the size makes the time visible before it
  starts (time blindness).
- **Borrowed.** Goblin Tools' Magic To-Do; coaching's "make the first step silly-small". **New here:** private, steps
  are real to-dos the Now card walks through, and the size feeds the day plan.

#### 3. Dump now, sort later: capture from anywhere *(signature)*

- **Daily problem.** Ten things are buzzing at once; writing each in the right place takes too long, so they are lost.
- **How it works.** Three ways in, each one tap: the **Ink line** (as in LP-35: type, Enter; voice by the mic), **Brain
  dump** (the cloud button by the date, or the app icon's long-press shortcut): one big page to type or speak
  everything, saved *as you type*, and the **share sheet** (as in LP-35). Long-pressing the Inkling icon gives *Write a
  line*, *Brain dump* and *Now*. A dump is kept at once as a note tagged *dump*; **Sort it** (in the dump sheet, and on
  the dump's line on the page) has the model split it into small to-dos with sizes, ideas and notes, shown as a list
  to keep or drop line by line, then **Add**. Without a model the same split is done by the phone's rules (one item
  per line or sentence, sorted like the Ink line).
- **Why it helps.** Capture costs one tap and no decisions; the thought is safe before it fades. Sorting happens when
  there is calm (or by the model), not mid-thought.
- **Borrowed.** GTD's inbox; brain dumps from ADHD coaching; Drafts' "text first". **New here:** the model sorts it
  into next actions with sizes, on the phone; the dump stays as written, so nothing is lost if the sort is wrong.

#### 4. A calm plan for today, drafted by the model

- **Daily problem.** Every open to-do looks equally urgent; the day is over-planned by ten, then nothing gets done.
- **How it works.** On today's page, under Now: **Today's plan**. Empty, it offers **Plan my day**. The model gets the
  open to-dos (today's, earlier ones, a few undated) with their sizes and the time left, and picks **at most three**,
  lightest first or what is due, so the plan fits the hours that are left. It shows as a proposal: three lines with
  minutes, "about 50 min · 6 h 40 left today", **Use this plan**, **Try again**, and × on a line to drop it. Used, the
  three are today's plan (Now follows it) and show as a slim list with a ticked count. Without a model, the same pick
  is made by rules (due first, then smallest).
- **Why it helps.** A short plan that fits is a promise you can keep; three is small enough to start and to finish,
  which is the reward loop.
- **Borrowed.** Sunsama's daily planning ritual; the 1-3-5 rule cut to three; Tiimo's AI day plan. **New here:**
  private, sized by the steps' minutes, and it never adds more than fits.

#### 5. Visible time: Focus with a shrinking disc

- **Daily problem.** Ten minutes and an hour feel the same; a task runs over, or never starts because it feels endless.
- **How it works.** **Start** on Now opens **Focus**: the one action, large, and a disc of yellow highlighter that
  shrinks as the minutes go (a Time Timer), with the minutes left in the middle. **Done**, **+5 min**, **Pause** and
  **Stop**. When the time is up the phone gives one soft notification ("Time's up for *Clear the table*. Done, or 5
  more minutes? Both are fine.") and the disc stays empty, no alarm. Done goes straight to the next step ("Next: Wipe
  the counter · 5 min · Start"). The page's plan shows a time bar: what is planned against what is left of the day.
- **Why it helps.** Time becomes a shape you can see shrinking, outside the head. The end is a nudge, not a judgment.
- **Borrowed.** Time Timer's red disc (here yellow, the app's marker); the Pomodoro technique, shortened to the task's
  own size; Tiimo's visual timers. **New here:** the timer is sized by the model's estimate and walks into the next step.

#### 6. Gentle by design: nothing overdue, rescheduling in one tap

- **Daily problem.** Red "Overdue (14)" walls make the app feel like a debt collector; opening it is avoided.
- **How it works.** Nothing in the app is red, and the word "overdue" is gone: earlier to-dos are **Still open** ("from
  Tuesday"), a calm block of at most three with **Today** and **Not today** (Tomorrow, Next week, Someday). Plans says
  "from Tue 27 Oct", not "Overdue". Not now on the Now card is never counted. No streaks, no scores. Ticking off shows a
  quiet line ("Done. 2 of 3 in today's plan"), not confetti.
- **Why it helps.** Shame is what stops ADHD users coming back; rescheduling must be cheaper than avoiding.
- **Borrowed.** Bullet Journal's migration; Finch and Tiimo's gentle tone; Things' "Later". **New here:** one
  vocabulary for the whole app, with no red anywhere.

#### 7. Where was I: a cue when you come back

- **Daily problem.** After a call or a scroll break, the thread is gone; the app opens on a page that doesn't say
  where you were.
- **How it works.** Coming back to Inkling after 20 minutes or more, a card at the top of today: **Where was I** —
  "You were on *Clear the table* (Focus, 6 of 10 min), 40 min ago" or "You were writing *Garden idea* at 14:05", with
  what was captured since. **Resume** opens it (Focus where it stopped, or the note at its place); **Recap** has the
  model sum up in two sentences what you were working on today; × hides it.
- **Why it helps.** A resumption cue cuts the cost of every interruption, and ADHD days are full of them.
- **Borrowed.** Interruption research (memory for goals); Android's "continue where you left off". **New here:** the cue
  knows the task, the timer and the note, and the recap is private.

#### 8. Small model jobs (the rule behind 2, 3, 4 and 7)

The phone runs a 1–2 B model; each job is kept small and fast so it answers in seconds and can run on the edge
server when LP-32 (Edge mode) is there:

- One job, one short instruction, no chat history, thinking off, temperature 0.3, at most 160–300 tokens out.
- Output in plain line formats the phone parses ("- Find the letter (5 min)", "3 · 10 min"), never JSON a small model
  breaks; anything that doesn't parse is dropped, not guessed.
- Every job has a rule-based answer on the phone when there is no model, and its result is a proposal you accept.
- Jobs run one at a time and stop when their sheet closes.

#### 9. Follow-ups (not built here)

- **Body doubling.** A quiet "working with you" presence in Focus (Focusmate's idea, without people).
- **Energy.** Tag to-dos low or high energy and let the plan pick to match how you feel.
- **Morning nudge.** One gentle notification at a chosen time: "Plan your day? About 2 min."
- **Now on the home screen.** The widget shows the Now card, with Done.

### A.2 Build rules

- **Data.** Room schema 5 → 6 by auto-migration: to-dos gain `parentId` (a step's to-do), `minutes` (the size) and
  `planOrder` (place in the plan of the day they are due). Existing rows get no parent, no size and no plan: every
  entry carries over as is. Steps have no date of their own; they follow their to-do and stay off the page, Plans
  and Still open.
- **Now card.** Today only, above the timeline; AnimatedContent on the action's id (fade and an 8 dp rise), no flicker
  while the lists load (it waits for the first read).
- **Focus.** A pushed screen (shared axis X); its state (which to-do, when it started, minutes, paused) is kept in
  preferences so it survives the app being closed; the end notification is an exact alarm on its own channel.
- **Brain dump.** A full-height sheet with one text field; its text is saved into the dump note half a second after
  typing stops and on close. Keyboard rules from ux-spec section 6.
- **Where was I.** When Inkling leaves the screen it writes the time; on return after 20 min the card shows once.
- **Shortcuts.** Static launcher shortcuts for *Write a line*, *Brain dump* and *Now* open MainActivity with
  `EXTRA_OPEN`.
- **No new permissions; no network.** The alarm uses the existing exact-alarm permission.

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
- **Timeline.** Events, reminders and timed to-dos of the day shown, slim rows (time · title); on today the next one
  is marked.
- **Carried over.** Today only, when not empty (4).
- **The page.** Lines of the day, oldest first, so the page reads top to bottom like a journal; the newest is
  nearest the Ink line. A line belongs to a day when it was written that day, a to-do also when it is due or was
  ticked off that day. Each line: glyph, title, one muted line of body if any. Quotes: marker under the text, source
  muted. Done to-dos: struck through, muted. Empty page: "A blank page. Write a line below — it sorts itself."
- **Ink line.** Pinned at the bottom (today only; past days show "Back to today" there). Glyph button, text field,
  mic, send. While typing, a hint above it says where the line goes ("To-do · today", "Reminder · today 18:00"). The
  mic fills the line with what it heard, to be read before it goes on the page. A to-do written here is due today. Keyboard rules from ux-spec section 6; the page keeps the last line in view as lines are added.
- **Day swipe.** A horizontal swipe of 80 dp or more turns the page; the new day slides in on the shared X
  axis (ux-spec section 7, 300 ms). Days with nothing still open (a blank page).
- **States.** No model: the Ink line still works (the sorter is rule-based); a "?" line stays in the line with a note
  that asking needs a chat model (Settings → Models), rather than being filed as a note.

### Editor

- Unchanged (LP-24's keyboard handling stays), plus the **Ink** button in the top bar and the margin block at the
  end of the page (3). One Ink request at a time; leaving the editor stops it.

### Share target

- `ACTION_SEND` with `text/plain` on a screenless `ShareActivity` in its own task: it saves the quote, then brings
  Inkling's own task forward (no second Inkling inside the sharing app). Title: the quote's first words (the page shows
  the whole quote). Body: the quote; source line: the subject and the link at the very end of the text, if given;
  links inside the quote stay in it. Kind Note, tag `reading`, source `share`. It is saved at once, also
  while the app is locked (the owner chose to share it); the page and its Undo show after unlock.

### Notebook (was Library)

- Filter chips All · Notes · Ideas · Journal · **Reading** · Pinned. Reading rows show the quote with the marker.

## 3. Data

No schema change: the Daily Page reads entries by `createdAt` (indexed) and to-dos by `doneAt` and `dueAt`; quotes
are notes tagged `reading` with source `share`. Every existing note, idea, journal line and to-do shows on the day it
was written, so the owner's data carries over as is. The Room schema stays at version 5.

## 4. Privacy and permissions

No new permission. The share target receives text only; nothing is fetched from a shared link. The main app keeps no
`INTERNET` permission; `checkDebugNoNetwork` and `checkDebugDependencies` still guard it.
