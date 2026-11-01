# Inkling: Horizon Almanac (UX spec v3)

The owner found Daylight (v2, LFMP-13 and LFMP-17) still "meh", generic, cluttered and not ADHD-friendly. LFMP-20
drew three bolder takes on the same sky (Horizon, Glow, Almanac); the owner asked for something between Horizon and
Almanac, with no boxes, no bordered buttons, a blue order of importance and a pastel talk button. That is **Horizon
Almanac**: Daylight's sky that follows the hour, with Almanac's precision. This file is its spec. The v1 rules for
flows, editing, motion timing and accessibility ([ux-spec-v1.md](ux-spec-v1.md), sections 3–8) still hold where this
file is silent.

Written 1 November 2026 (LFMP-20). Daylight's sky, sounds, voice and no-nag rules carry over unchanged.

## 1. ADHD first: one thing, at a glance

Every screen is built to the same rules:

1. **One next step, obvious.** One task in the biggest type, with one action word in the action blue and the sun's dot
   before it (Start). Everything else is quieter.
2. **Low noise.** No outlined boxes, no bordered buttons, no chips. Space and two type sizes do the grouping; the few
   surfaces left (the message box, settings groups) sit on the pale wash with no edge (`DaylightGuardTest` keeps
   outlines out).
3. **Time you can see.** The day is a half dial on Today's sky, the sun rides it to now, and the stretch to the next
   plan is drawn in the sun's yellow. "Now, for about 15 minutes" and "Next · 15:00 Dentist · in 5 hours" say it in
   words.
4. **Capture in one tap, from anywhere.** The pastel talk button sits in the middle of the bottom bar on every place:
   a tap opens Talk, a long-press a brain dump.
5. **Small wins, seen.** A ticked to-do fills with the sun and pops; nothing counted against you.
6. **Easy resume.** Where was I, after 20 minutes away, with Resume as the one action word.
7. **Forgiving.** Undo on every result, in the snackbar and under Talk's answers.

## 2. Colour

The sky is unchanged from Daylight (`daylight/Sky.kt`, blended minute by minute; its inks read at 4.5:1). The page
under it is one blue in four steps (`ui/theme/Theme.kt`):

| Role | Light | Dark | Use |
| --- | --- | --- | --- |
| `bg` | `#F6F9FC` | `#0A1019` | sky-tinted paper |
| `text` | `#0E2340` navy | `#E8F0F9` | what you read: the time, the task, titles |
| `action` | `#2A66AE` | `#8EC5F0` | the one thing to tap, as a word (Start, Undo, Open) |
| `text2` | `#3F6B98` | `#9DC2E6` | Inkling's voice, labels, the other action words |
| `text3` | `#587393` | `#8098B6` | the quietest: unpicked tabs and places |
| `wash` | `#EAF1F8` | `#121B27` | the few surfaces left |
| `highlight` | `#FFD400` | `#FFD400` | the one warm colour: the sun, time left, the highlighter, the sun's dot, a ticked to-do |
| `mic` | `#FFEB9F` | `#FFEB9F` | the talk button: the highlighter mixed with white, a clean pastel, navy mic (13:1) |

Contrast on `bg`: text 14.9:1, text2 5.3:1, text3 4.6:1, action 5.5:1 in light; all above 6:1 in dark.

## Controls

- **Buttons are words** (`Btn`): the primary one bold in the action blue with the sun's dot before it, the others quiet
  blue words. No box, no edge; the 44 dp target is still there.
- **Tabs and choices are words** (`Tabs`, `Segmented`): the picked one navy and bold with the highlighter's stroke drawn
  in under it.
- **Switches** are round, the action blue when on; **check boxes** are rings that fill with the sun.
- **The mic**: the primary one (Talk's own, the bottom bar's) is the pastel; dictation mics elsewhere are a quiet fill,
  so a screen never has two yellow buttons. The + for a new entry is a navy circle.
- **The highlighter** (`Marked`) is a stroke from just above the baseline to under it, drawn in from the left the
  first time.
- **The signature** (`SunMark`): every header on the sky ends with a small half dial with the sun on it at now (the
  moon resting on the horizon at night).

### Bottom bar

Today · Notebook · (talk) · Plans · Ask, as words; the place you are on is navy and bold with the sun's dot under it.
The talk button in the middle opens Talk on a tap and a brain dump on a long-press. Talk and Ask moved here from
Today's row of ways in; Look moved to the sky's corner.

## 3. Type

Three voices, all the phone's own fonts (nothing bundled, no licence to track):

- **What matters now** in Roboto Condensed Bold (`sans-serif-condensed`): the time (60 sp, even figures), the one
  task (`task`, 30 sp), titles (32–34 sp) and headers (21 sp).
- **Inkling's own words** in the phone's serif (Noto Serif): the day's sentence, Talk's captions, chat answers, empty
  states and welcomes, in the `text2` blue.
- **Your words and the controls** in Roboto; action words bold (`action`, 16 sp).

## 4. Motion

- **The sky never moves by itself**: it is redrawn when the minute changes (`rememberNow`).
- **The sun rises** along Today's dial to now when Today first shows (900 ms, once a run).
- **Talk's presence**: the sun inside a dial of 60 ticks; the ticks light round the dial with the voice level, and
  breathe (1.8 s) while it thinks. The only loop.
- **Small wins**: a ticked to-do fills with the sun and pops with a soft spring.
- **Tabs**: the highlighter's stroke draws in under the picked word (150 ms).
- With "Remove animations" on, everything lands where it ends: no rise, no breath, no pop.

## 5. Sound and haptics

Four cues (`daylight/Feel.kt`), each two soft notes made in code (no sound files to license), quiet (peak 0.16) and
under 300 ms:

| Cue | When | Sound | Haptic |
| --- | --- | --- | --- |
| Listen | Talk opens its ear (not every turn) | E5 → A5, rising | gesture start |
| Done | a Talk turn did something | A5 → C#6 | confirm |
| Undo | something was put back | G5 → D5, falling | reject |
| Tick | a plan on the horizon is picked | none | clock tick |

Sounds play only with **Settings → Soft sounds** on and the ringer on normal (never on vibrate or silent). Haptics go
through the view, so they follow the phone's own touch-feedback setting.

## 6. Voice

One personality for Talk and the chat (`Persona.PROMPT`, added to every prompt that speaks to the owner):

> Warm, calm and brief, like a friend who keeps good time. Plain words; no exclamation marks, flattery, emoji or pep
> talk. Never scold, never count what was missed: offer one next step instead. If you do not know, say so plainly.

The app's copy follows it: first person where Inkling speaks ("I'm listening", "I'll file it"), the day in sentences
("Afternoon. Dentist at 15:00, in 25 minutes. Then one more."), nothing counted against the owner.

## 7. Screens

### Today

From the top: the sky (352 dp), then the page.

- **In the sky:** the date, then the day before, Look, brain dump and settings; the day as a half dial (06:00–22:00,
  stretched for early or late plans) with an hour tick each hour, plans as bold marks, the sun at now and the stretch
  to the next plan in yellow; the time inside the dial.
- **Under the horizon:** Inkling's one sentence of the day (`DaySentence`, never counts), then the next plan, or the
  one tapped on the dial, with **Open** for to-dos.
- **Below:** the seal line; Where was I; **Now**: "Now, for about 15 minutes", the task in the condensed bold with the
  highlighter, then Start · Done · Not now as words; today's plan; the timeline; still open; the page; the Ink line.
  No cards: each block is a label, its words and space.

### Talk

Captions over the sky of the hour. The presence is the sun inside a dial of ticks (see Motion); Inkling's answer large
in the serif; "Reminder · Dentist at 15:00" with Undo and Show as action words. The main control is the pastel mic, or
a navy round Stop while it works.

### A note

The header on the sky with the SunMark; the title and body as before; the kind (Note, Idea, Journal, To-do) as word
tabs; the to-do sizes as soft pills, the picked one on the pastel.

### Look

The camera stays full-bleed black with its own glass controls.

### Notebook, Plans, Settings and the rest

Paper under the hour's sky, each with its header on the sky. Settings groups sit on the wash with no edge; choices are
words. The capture widget and the lock-screen widget keep Daylight's horizon painter (`daylight/HorizonWidget.kt`).

## 8. No-nag rules (kept from the research)

- Rituals the owner turned on are not offers; beyond them, at most three unasked offers a day, never during Focus, Do
  Not Disturb or after 22:00. This release adds **no** offers.
- Each offer says why; "Not this" stops that kind of offer.
- **Nothing red, nothing counted, nothing repeated.** The day's sentence never counts to-dos in digits, never says
  "overdue", "missed" or "behind" (unit-tested), and past plans only fade.
- Sounds are rare and quiet; nothing ever buzzes on its own.

## 9. Later (bigger bets, ranked)

1. **Offers, with a budget**: one engine for unasked offers, at most three a day, each with a reason.
2. **Morning and evening rituals**: a drafted day at the first glance, a one-line evening.
3. **Lock-screen Talk and Look** (one app since LFMP-9, so it can be built now).
4. **What I know**: the readable, correctable memory page (LFMP-11).
5. **Point-of-performance nudges**: leave-by and bring-this from calendar plus memory.
6. **Remembers in the margin**: related old notes via on-device embeddings (LFMP-10).
7. **Focus with a quiet presence** (Library, Plans and Settings have Daylight since LFMP-17).
