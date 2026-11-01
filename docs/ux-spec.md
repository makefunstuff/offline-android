# Inkling: Daylight (UX spec v2)

The owner found the v1 look "pretty meh and generic" and asked for something inspired by films like *Her*, "helpful
every single day". LFMP-12 researched three directions ([ux-research.md](ux-research.md)); the owner picked
**Daylight**: calm and ambient, home is a sky that follows the hour, plans sit on a horizon and the sun walks toward
them, time is always visible, lock screen first. This file is Daylight's design spec. The v1 rules for flows, editing,
motion timing and accessibility ([ux-spec-v1.md](ux-spec-v1.md), sections 3–8) still hold where this file is silent.

Written 1 November 2026 (LFMP-13, first release).

## 1. The one problem to solve: calm, but one tap from everything

The research's worry was that Daylight "hides too much to be the daily habit". Daylight answers it with three rules:

1. **The sky is the top of Today, not a screen in front of it.** Under the sky sit Talk, Look and Ask, then the whole
   page as before: Now, the plan, the timeline, the page's lines and the Ink line. Glance, or scroll one thumb's
   length.
2. **Every mark on the sky does something.** A plan on the horizon opens its line, with Open; the time and the
   sentence are read out by TalkBack; the day before, brain dump and settings sit in the sky's corner.
3. **The sky never replaces a word.** The sentence says what the picture shows, so nothing depends on reading colour.

## 2. Palette by hour

The sky is blended minute by minute between four looks (`daylight/Sky.kt`). Text on it uses one of two inks, picked so
it reads at 4.5:1 against both ends of the sky at every minute, in light and dark theme (unit-tested).

| Part | Reached | Sky top → horizon | Sun | Ink |
| --- | --- | --- | --- | --- |
| Night | 20:30 – 05:00 | `#0E1630` → `#26305A` | moon `#DCE3F5`, a crescent | light `#F6F3EE` |
| Dawn | 06:30 | `#9FB4D9` → `#F8CDB0` (peach) | `#FF9D5C` | dark `#0B1A2B` |
| Day | 08:30 – 16:30 | `#8EC5F0` → `#E9F4FC` | `#FFD400`, the icon's highlighter | dark |
| Dusk | 18:30 | `#3E3A78` (indigo) → `#F09A6B` (apricot) | `#FF7A45` | light |

- **Dark theme** keeps the hour but dims the sky toward `#05070D` (45 % at the top, 50 % at the horizon).
- Between a light and a dark look the sky leans a touch darker until the light ink reads; the ink turns twice a day,
  at first and last light.
- **The sky strip.** Every screen has the hour's sky under the status bar, and the status icons take its ink, so
  Library or Settings still say what time of day it is. The camera keeps its black.
- The page below the sky keeps v1's paper and ink, and the highlighter yellow stays the one accent.

## 3. Type

- **Inkling's own words** (the day's sentence, Talk's captions, the widget's line) are in the phone's serif (Noto
  Serif via `FontFamily.Serif`). Nothing is bundled, so there is no font licence to track.
- **Your words and the controls** stay in Roboto.
- **The time** is large and light: Roboto Light 64 sp with even (tabular) figures. Daylight shows the time on Today,
  in the widget, and under the status bar's sky.
- Sizes: sentence 21/28 sp; Talk caption 26/34 sp, earlier captions 21 sp at 60 % opacity.

## 4. Motion

- **Nothing moves by itself.** The sky, the sun and the horizon are redrawn when the minute changes and no more
  often (`rememberNow`): no idle animation, no wake-ups, nothing for the battery.
- The only loops: Talk's presence mark breathes while it thinks (1.8 s, ease in-out), and that stops with "Remove
  animations" on.
- The presence mark follows the real audio level with a no-bounce spring; with animations off it holds still and a
  ring shows the state.
- A picked plan's line fades through in 150 ms. Everything else keeps v1's motion tokens (`ui/theme/Motion.kt`).

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

From the top: the sky (340 dp), then the ways in, then the page.

- **In the sky:** "Today · Sunday 1 November" with the day before, brain dump and settings; the time; **one sentence
  of the day** (`DaySentence`), never counts. It leads with the next timed thing ("Dentist at 15:00, in 25 minutes")
  when it is within 90 minutes, otherwise the day's weight ("A light day: …"), otherwise "The day is yours."
- **The horizon:** today's events, reminders and timed to-dos as dots on the horizon line, the waking day 07:00–22:00
  left to right (stretched to fit early or late plans and now). The sun stands on its arc at now; the stretch it still
  has to walk to the next plan is drawn in the sun's colour, which makes time visible for time blindness. Past plans
  fade; the next one has a ring.
- **Under the horizon:** the next plan ("Next · 15:00 · in 25 minutes", its title), or the one tapped, with **Open**
  for to-dos.
- **The ways in:** Talk, Look, Ask, one tap each.
- **Below:** the seal line, Where was I, Now, the plan, the timeline, carried to-dos, the page and the Ink line, as in
  v1. Other days keep v1's plain header and line of counts.

### Talk

Captions over the sky of the hour (top-to-horizon gradient), the header and controls in the sky's ink.

- **The presence mark** is the hour's sun: it swells with your voice while listening (with a thin ring: the ear is
  open), with its own voice while speaking, breathes while thinking, and is a quiet ring when paused.
- **Captions:** your words small, Inkling's answer large in the serif; earlier exchanges smaller and faded above.
- **Under a result:** "✓ Reminder set · Dentist at 15:00" with **Undo** (from the action log) and **Show** (the note or
  to-do in the editor; reminders and events on Today; anything else in Action history).

### Look

The camera stays full-bleed black (the one screen without the sky strip); Look's live description keeps its own tile
inside Talk. Its look changes come in a later task.

### Library, Plans, Settings

Paper and ink as in v1, under the hour's sky strip. Settings gains **Soft sounds**. A full Daylight pass of these
screens (sky-tinted headers, the serif for Inkling's margin notes) is a follow-up.

### Lock screen and widget

**Inkling · Today**, a 4×2 widget (`daylight/HorizonWidget.kt`), declared for the home screen and the keyguard:

- the hour's sky with the horizon, today's plans and the sun, drawn by the same painter as the app;
- the time as a live clock;
- "Next · 15:00 Dentist" (said with its time, never "in 20 minutes", so a picture up to 30 minutes old still tells the
  truth), or the day's sentence;
- a tap opens Today.

It is redrawn every 30 minutes and whenever Inkling is left. Android phones show keyguard widgets only where the
launcher offers lock-screen widgets; a true lock-screen surface (Talk and Look from the lock screen), now possible
with LFMP-9's single app, is not in this release.

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
7. **Daylight for Library, Plans and Settings**, and Focus with a quiet presence.
