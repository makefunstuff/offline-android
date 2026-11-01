# Wren: UI/UX research, a warmer companion (LFMP-12)

The owner: "Current design is pretty meh and generic. We need some inspiration from movies like Her, or from modern
non-boring interfaces, to actually improve it and make it helpful every single day, and smart enough for everyday
use." This file sums up the research. The screenshots, the three directions drawn as phone mockups and the
storyboard are in the LFMP-12 artifact. Nothing here is built yet; each change below is one follow-up task.

Written 1 November 2026, after walking the current build (LFMP-6) in the emulator with fake notes.

**Decision: Daylight.** The owner chose direction C, Daylight, over the recommended Hearth. Its spec is
[ux-spec.md](ux-spec.md); LFMP-13 builds its first release. The worry below, that Daylight "hides too much to be the
daily habit", is answered there: the sky is the top of Today, not a screen in front of it, so everything stays one tap
or one scroll away. **Update (LFMP-20):** the owner still found Daylight generic and cluttered; the look is now **Horizon Almanac**
(Daylight's sky with Almanac's precision, no boxes, a blue order of importance), specified in [ux-spec.md](ux-spec.md).

## 1. Verdict on today's app

Clean, calm and honest, but generic: it looks like a well-made settings screen.

- **No presence.** Talk is a box of level bars (with no model it only points to Models) and Today is a stack of cards. Nothing shows that Wren is there,
  listening or thinking.
- **No voice of its own.** The copy is clear, but it reads like the system talking ("Nothing next. Write a line
  below"), with no character.
- **Counts, not sentences.** "1 thing at a set time · 2 to-dos" is a dashboard line.
- **It rarely starts.** Apart from the Now and Where was I cards, plan, steps, recap and Look wait behind a button.
- **Same at 7 am and 11 pm.** There is no morning, no evening and no sense of the day passing.
- **Voice is a separate room.** Talk, Chat and the Ink line feel like three apps.

## 2. Principles borrowed (not looks)

| From | Principle | For Wren |
| --- | --- | --- |
| *Her* | Presence without a screen: a warm, brief voice and a tiny device, speaking first at the right moment | Voice first, one small living mark, warm colour |
| *Her* (the warning) | Don't fake intimacy | Warm but honest; says what it remembered and why |
| *Interstellar* | Personality settings | "Say less / more", "plain / a little wry" |
| *Moon* | A simple face is enough | One mark with four states |
| *Iron Man* | Anticipate, then get out of the way | One-line offers at the moment they matter |
| *2001* (anti-pattern) | The closed eye that decides alone | Every offer shows why and can be refused for good |
| *Arrival*, *After Yang* | Time as shape; memory as moments | A visible day arc; memory as small moments |
| Calm technology (Weiser and Brown; Case) | Live in the periphery | Lock screen, widget, a glow before a notification |
| Voice companions | The mark must tell the truth | The mark follows the real audio level and state |
| ADHD planners | Warm, short, guilt-free | Keep LP-37's rules, give them a voice |
| AI-native organizers | Draft, don't decide | Morning and evening drafts, one tap each |
| Playful hardware | Tactile joy | Haptic detents and small sounds, used sparingly |

## 3. Three directions

- **A · Hearth (recommended).** A warm companion with one living mark, the *ember*, at the top of every screen: tap
  to talk, hold to look. Warm paper by day, warm dark by evening; the highlighter yellow (`#ffd400`, then the dot of the old *i* icon) becomes a warmer lamplight amber, on purpose. Wren's
  words in a soft serif (Literata), yours in Roboto. The ember breathes and follows the voice; soft felt-piano
  sounds. Voice: warm, brief, a little wry, says "I don't know" and why it offers something.
- **B · Dial.** A pocket instrument with a dot-matrix face, the day as a dial, colour blocks per kind, chunky buttons
  and clicks; personality meters (its own palette of coral, cobalt and cream, not any hardware brand's look). Most fun, but loud for a private notebook and the novelty may wear off.
- **C · Daylight.** Calm and ambient: home is a sky that follows the hour, plans sit on a horizon and the sun walks
  toward them; glanced at on the lock screen. Best for time blindness, but hides too much to be the daily habit.

## 4. A day with Hearth

| Time | Wren | How |
| --- | --- | --- |
| 7:10 | "Morning. A light day: two small things, and the dentist at 3." | Lock-screen glance; calendar, to-dos and last night's draft |
| 7:40 (morning ritual) | "Want me to keep three for today? About 50 minutes." | Plan my day; the hard one first, as memory says mornings are best |
| 8:30 | (spoken) a reminder and a thing to bring | Hold the ember; sorted on the phone, each with Undo |
| 10:00 | "I'll sit with you. 25 minutes on the report." | Focus with a quiet presence |
| 12:30 | "Filed: lunch receipt, 14.20, under receipts." | Look; safe, undoable, so no question |
| 14:35 (offer 1) | "Dentist in 25 minutes. The insurance card is in the blue folder, you said on Tuesday." | Calendar plus memory, one nudge at the point of performance |
| 16:10 | "You were on the report, 12 of 25 minutes. Pick it up?" | Where was I, as a sentence |
| 18:00 (offer 2) | "You wanted to ask Sam about seeds." | An old note matched to the calendar event by on-device embeddings |
| 21:30 | "One line about today?" | Journal line; tomorrow drafted; the privacy seal |
| Night | Silent | Memory learns from the day's notes while charging |

**No-nag rules.** Rituals the owner turned on (morning, evening, Where was I, Focus) are not offers. Beyond them, at most three unasked offers a day (this day uses two); never during Focus, Do Not Disturb or after 22:00. Each offer
says why. "Not this" stops that kind of offer and is written into *What I know*. Nothing red, nothing counted, nothing
repeated.

## 5. Changes, ranked

Effort: S under a day, M 1–3 days, L about a week.

Quick wins:

1. **Warm theme** (S): paper and warm-dark palettes; amber highlighter.
2. **The ember** (S): one presence mark with four states driven by the real audio level; replaces Talk's bars.
3. **A sentence, not counts** (S): Today opens with one line of the day.
4. **One voice** (S): a short personality prompt for Talk and chat; copy rewritten to match.
5. **Talk as captions** (S): large captions over the ember; Undo and Show under tool results.
6. **Serif for Wren's words** (S): bundle Literata, used only where Wren speaks.
7. **Sound and haptics kit** (S): four soft sounds and matching haptics.
8. **Morning and evening tint** (S): the theme warms through the day and dims after 21:00.

Bigger bets:

1. **Offers, with a budget** (L): one engine for unasked offers, at most three a day, each with a reason.
2. **Morning and evening rituals** (M).
3. **Hold the ember anywhere** (M): Talk and Look from every screen and the lock screen (fits LFMP-9).
4. **What I know** (M): the readable, correctable memory page for LFMP-11.
5. **Point-of-performance nudges** (M): leave-by and bring-this from calendar plus memory.
6. **Remembers in the margin** (M): related old notes via on-device embeddings (LFMP-10).
7. **Now on the lock screen and widget** (M).
8. **Focus with company** (S, but it needs the ember first): a quiet body-double presence.
9. **The day arc** (M): Daylight's horizon as a strip on Today.

## 6. Recommendation and the owner's pick

**The owner picked Daylight** (1 November 2026). Follow-up tasks build Daylight; the change list above still holds
where it isn't Hearth-specific (one voice, sound and haptics, offers with a budget, rituals, What I know, nudges, lock
screen and widget, the day arc becomes home).

The research recommended:

Hearth, with Daylight's day arc. Quick wins 1–5 first (about a week), then offers with a budget and the rituals.
LiteRT on the phone (LFMP-8) keeps answers fast offline, on-device embeddings (LFMP-10) find old notes, the memory
(LFMP-11) fills *What I know*, and one app (LFMP-9) lets the ember live on every screen and the lock screen.
