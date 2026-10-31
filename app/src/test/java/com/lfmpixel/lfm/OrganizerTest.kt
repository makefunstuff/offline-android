package com.lfmpixel.lfm

import com.lfmpixel.lfm.assistant.DayPlan
import com.lfmpixel.lfm.assistant.DumpSort
import com.lfmpixel.lfm.assistant.FocusRun
import com.lfmpixel.lfm.assistant.InkKind
import com.lfmpixel.lfm.assistant.NextAction
import com.lfmpixel.lfm.assistant.Organizer
import com.lfmpixel.lfm.assistant.Recap
import com.lfmpixel.lfm.assistant.Sizes
import com.lfmpixel.lfm.assistant.Steps
import com.lfmpixel.lfm.data.Entry
import com.lfmpixel.lfm.data.EntryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrganizerTest {
    private val day = 86_400_000L
    private val start = 100 * day
    private val end = start + day
    private val now = start + 10 * 3_600_000L
    private fun todo(id: Long, title: String, due: Long? = start + 9 * 3_600_000L, plan: Int = 0, parent: Long? = null, created: Long = id, minutes: Int = 0, hasTime: Boolean = false) =
        Entry(id = id, kind = EntryKind.Todo, title = title, dueAt = due, planOrder = plan, parentId = parent, createdAt = created, minutes = minutes, dueHasTime = hasTime)

    @Test fun sizesFromAnswerLines() {
        assertEquals(Organizer.Sized("Find the letter", 5), Organizer.sized("Find the letter (5 min)"))
        assertEquals(Organizer.Sized("Call Ana", 10), Organizer.sized("Call Ana - 10 minutes."))
        assertEquals(Organizer.Sized("Write the draft", 90), Organizer.sized("Write the draft (1.5 h)"))
        assertEquals(Organizer.Sized("Tidy up", 0), Organizer.sized("Tidy up"))
        assertEquals("1 h 30", Sizes.label(90)); assertEquals("15 min", Sizes.label(15)); assertEquals("", Sizes.label(0))
        assertEquals(5, Sizes.guess("Call the dentist")); assertEquals(30, Sizes.guess("Clean the bathroom")); assertEquals(15, Sizes.guess("Groceries"))
    }

    @Test fun stepsParseAndStarter() {
        val steps = Steps.parse("Sure!\n- Find last year's letter (5 min)\n2. Open the tax site - 5 min\n* Fill in income (20 min)\n- None")
        assertEquals(listOf("Find last year's letter", "Open the tax site", "Fill in income"), steps.map { it.text })
        assertEquals(listOf(5, 5, 20), steps.map { it.minutes })
        assertEquals(3, Steps.starter("Do taxes").size)
        assertTrue(Steps.parse("I can't help with that.").isEmpty())
    }

    @Test fun planPicksAtMostThreeFromTheList() {
        val list = listOf(todo(1, "A") to 10, todo(2, "B") to 30, todo(3, "C") to 5, todo(4, "D") to 60)
        assertEquals(listOf(3L, 1L, 2L), DayPlan.parse("3\n1\n1\n9\n2\n4", list).map { it.entry.id })
        // Rules: set times first, then due, then the smallest, within the time left.
        val rules = DayPlan.rules(list + (todo(5, "At 3", hasTime = true) to 15), 40)
        assertEquals(listOf(5L, 3L, 1L), rules.map { it.entry.id })
        // Too little time still gives one thing to start with.
        assertEquals(1, DayPlan.rules(list, 0).size)
    }

    @Test fun planCandidatesSkipStepsAndLaterDays() {
        val open = listOf(todo(1, "Today"), todo(2, "Step", due = null, parent = 1), todo(3, "Next week", due = end + 6 * day), todo(4, "Someday", due = null))
        assertEquals(listOf(1L, 4L), DayPlan.candidates(open, end).map { it.id })
    }

    @Test fun nowFollowsPlanThenStepsThenNotNow() {
        val open = listOf(todo(1, "Old", due = start - day), todo(2, "Planned second", plan = 2), todo(3, "Planned first", plan = 1),
            todo(4, "Step a", due = null, parent = 3), todo(5, "Step b", due = null, parent = 3))
        val n = NextAction.pick(open, now, start, end)!!
        assertEquals("Step a", n.entry.title); assertEquals(3L, n.parent?.id); assertEquals(2, n.stepsLeft); assertEquals(1, n.plan); assertEquals(2, n.planSize)
        // Not now on the first planned thing: the next one, and the skipped one comes back last.
        assertEquals("Planned second", NextAction.pick(open, now, start, end, skipped = listOf(3L))!!.entry.title)
        assertEquals("Old", NextAction.pick(open, now, start, end, skipped = listOf(3L, 2L))!!.entry.title)
        assertEquals("Step a", NextAction.pick(open, now, start, end, skipped = listOf(3L, 2L, 1L))!!.entry.title)
        // Nothing due yet: nothing to do now, and undated things don't push in.
        assertNull(NextAction.pick(listOf(todo(9, "Later", due = end + day), todo(10, "Someday", due = null)), now, start, end))
    }

    @Test fun dumpSortsByModelOrByRules() {
        val m = DumpSort.parse("- todo: Call the bank about the fee (5 min)\n- idea: Herbs on the balcony\n- note: Mum likes tulips\n- banana")
        assertEquals(listOf(InkKind.Todo, InkKind.Idea, InkKind.Note), m.map { it.kind })
        assertEquals(5, m[0].minutes)
        val r = DumpSort.rules("buy oat milk\nwhat if the shed were a studio. call the bank about the fee")
        assertEquals(listOf(InkKind.Todo, InkKind.Idea, InkKind.Todo), r.map { it.kind })
        assertTrue(r.all { it.kind != InkKind.Todo || it.minutes > 0 })
    }

    @Test fun focusTimeMath() {
        val r = FocusRun(1, "Clear the table", 10 * 60_000L, startedAt = 0)
        assertEquals(4 * 60_000L, r.leftMs(6 * 60_000L))
        assertEquals(0.4f, r.fraction(6 * 60_000L), 0.001f)
        val p = r.pause(6 * 60_000L)
        assertEquals(4 * 60_000L, p.leftMs(60 * 60_000L))
        assertEquals(4 * 60_000L, p.resume(20 * 60_000L).leftMs(20 * 60_000L))
        // Five more minutes after the time ran out count from now.
        assertEquals(5 * 60_000L, r.plus(5 * 60_000L, 30 * 60_000L).leftMs(30 * 60_000L))
        assertEquals(9 * 60_000L, r.plus(5 * 60_000L, 6 * 60_000L).leftMs(6 * 60_000L))
    }

    @Test fun recapByRules() {
        val lines = listOf(todo(1, "A").copy(done = true), todo(2, "B"), Entry(id = 3, kind = EntryKind.Note, title = "n"))
        assertEquals("You were on “Clear”. Today so far: 1 done, 1 still open, 1 line written.", Recap.rules("Clear", lines))
        assertEquals("A quiet day so far.", Recap.rules(null, emptyList()))
    }
}
