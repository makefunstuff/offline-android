package com.lfmpixel.lfm

import com.lfmpixel.lfm.assistant.InkKind
import com.lfmpixel.lfm.assistant.InkLine
import com.lfmpixel.lfm.assistant.Margin
import com.lfmpixel.lfm.assistant.MarginAsk
import com.lfmpixel.lfm.assistant.Quotes
import com.lfmpixel.lfm.ui.screens.brief
import com.lfmpixel.lfm.ui.screens.isQuote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class InkLineTest {
    private val today = LocalDate.of(2026, 10, 31)

    @Test fun guessesAsYouType() {
        assertEquals(InkKind.Todo, InkLine.guess("buy oat milk").kind)
        assertEquals(InkKind.Idea, InkLine.guess("what if the shed were a studio").kind)
        assertEquals(InkKind.Idea, InkLine.guess("What if the garden had a pond?").kind)
        assertEquals(InkKind.Journal, InkLine.guess("felt calm after the swim").kind)
        assertEquals(InkKind.Note, InkLine.guess("Garden: tulips by the fence").kind)
        assertEquals(InkKind.Ask, InkLine.guess("best time to plant tulips?").kind)
        assertEquals(InkKind.Ask, InkLine.guess("ask: plan my Sunday").kind)
        assertEquals("plan my Sunday", InkLine.guess("Ask Wren, plan my Sunday").text)
        assertEquals(InkKind.Ask, InkLine.guess("hey wren: what's next").kind)
        assertEquals(InkKind.Ask, InkLine.guess("Inkling, plan my Sunday").kind)
        assertEquals(InkKind.Note, InkLine.guess("Wren, birthday Friday").kind)
        assertEquals("plan my day", InkLine.guess("Hey, Wren, plan my day").text)
        assertNotEquals(InkKind.Ask, InkLine.guess("Ask Wren about the dress").kind)
        assertEquals("Hey Wrenna is coming Friday", InkLine.guess("Hey Wrenna is coming Friday").text)
        assertNotEquals(InkKind.Ask, InkLine.guess("Ask Wrenna about the dress").kind)
        assertEquals("Hey Wren's party is Friday", InkLine.guess("Hey Wren's party is Friday").text)
        assertNotEquals(InkKind.Ask, InkLine.guess("Hey Wren is coming Friday").kind)
        InkLine.guess("remind me at 6 to call Ana").let {
            assertEquals(InkKind.Reminder, it.kind); assertEquals("call Ana", it.text)
            assertEquals("today 18:00", InkLine.whenLabel(it.reminderAt, java.time.LocalDateTime.of(2026, 10, 31, 14, 0)))
        }
        assertEquals(InkKind.Note, InkLine.guess("").kind)
    }

    @Test fun signifierWordsComeOff() {
        assertEquals("A reading nook", InkLine.guess("idea: a reading nook").text)
        assertEquals(mapOf("text" to "A reading nook"), InkLine.call("idea: a reading nook", InkKind.Idea, today)!!.args)
        // Picked by hand as another kind, the line goes in as written.
        assertEquals("idea: a reading nook", InkLine.call("idea: a reading nook", InkKind.Note, today)!!.args["text"])
        assertEquals("plan my Sunday", InkLine.question("ask: plan my Sunday"))
        assertEquals("best time to plant tulips?", InkLine.question("best time to plant tulips?"))
    }

    @Test fun todosAreDueToday() {
        InkLine.call("buy oat milk", InkKind.Todo, today)!!.let {
            assertEquals("add_todo", it.name)
            assertEquals("2026-10-31", it.args["due"])
            assertEquals("Shopping", it.args["list"])
            assertEquals("Buy oat milk", it.args["text"])
        }
        InkLine.call("write to the council", InkKind.Todo, today)!!.let { assertNull(it.args["list"]); assertEquals("Write to the council", it.args["text"]) }
        assertNull(InkLine.call("why is the sky blue?", InkKind.Ask, today))
    }

    @Test fun tapCyclesKindsAndOffersReminderOnlyWithATime() {
        assertFalse(InkKind.Reminder in InkLine.cycle(InkKind.Note))
        assertTrue(InkKind.Reminder in InkLine.cycle(InkKind.Reminder))
        assertEquals(InkKind.Todo, InkLine.next(InkKind.Note, InkKind.Note))
        assertEquals(InkKind.Note, InkLine.next(InkKind.Ask, InkKind.Note))
        assertEquals(InkKind.Reminder, InkLine.next(InkKind.Journal, InkKind.Reminder))
    }
}

class QuotesTest {
    @Test fun browserSelectionBecomesAQuote() {
        val e = Quotes.fromShare("\"We are what we repeatedly do.\"\nhttps://example.org/durant#:~:text=We%20are", "The Story of Philosophy", now = 1L)!!
        assertEquals("We are what we repeatedly do.", e.title)
        assertEquals("We are what we repeatedly do.", Quotes.words(e))
        assertEquals("The Story of Philosophy · https://example.org/durant", Quotes.source(e))
        assertEquals("reading", e.tags)
        assertTrue(e.isQuote())
    }

    @Test fun plainTextAndEmptyShares() {
        val e = Quotes.fromShare("Attention is the rarest form of generosity.", null)!!
        assertEquals("", Quotes.source(e))
        assertEquals("Attention is the rarest form of generosity.", Quotes.words(e))
        assertNull(Quotes.fromShare("  ", ""))
        // Only a link: kept with its page title as the words.
        assertEquals("A page", Quotes.fromShare("https://example.org/a", "A page")!!.title)
    }

    @Test fun onlyATrailingLinkIsTheSource() {
        val e = Quotes.fromShare("Compare https://a.example and https://b.example", null)!!
        assertEquals("Compare https://a.example and", Quotes.words(e))
        assertEquals("https://b.example", Quotes.source(e))
        val inside = Quotes.fromShare("See https://a.example for more", "Notes")!!
        assertEquals("See https://a.example for more", Quotes.words(inside))
        assertEquals("Notes", Quotes.source(inside))
    }

    @Test fun longQuotesGetAShortTitle() {
        val long = "word ".repeat(40).trim()
        val e = Quotes.fromShare(long, null)!!
        assertTrue(e.title.length <= 80 && e.title.endsWith("…"))
        assertEquals(long, Quotes.words(e))
    }
}

class MarginTest {
    @Test fun pullsOutTodoLines() {
        val answer = "Here you go:\n- Ask Marta about the compost bin\n* Buy tulip bulbs.\n2. Fix the gate hinge\nThat's all."
        assertEquals(listOf("Ask Marta about the compost bin", "Buy tulip bulbs", "Fix the gate hinge"), Margin.todos(answer))
        assertEquals(emptyList<String>(), Margin.todos("None"))
        assertEquals(emptyList<String>(), Margin.todos("- None"))
    }

    @Test fun promptHasTheNoteAndNoThinking() {
        val p = Margin.prompt("Tulips by the fence", MarginAsk.Sum)
        assertTrue(p.contains("Tulips by the fence"))
        assertTrue(p.contains("Sum up this note"))
        assertTrue(p.endsWith("<think></think>"))
        val long = Margin.prompt("x".repeat(Margin.MAX_NOTE_CHARS + 500), MarginAsk.Ask, "What next?")
        assertTrue(long.contains("…") && long.contains("What next?"))
    }

    @Test fun cleansAndMarksWhatGoesIntoTheNote() {
        assertEquals("A short sum.", Margin.clean("**A short sum.**"))
        assertEquals("Wren, summed up:\nA short sum.", Margin.forNote(MarginAsk.Sum, "A short sum."))
        assertEquals("Wren, kept going:\nAnd then it rained.", Margin.forNote(MarginAsk.Continue, " And then it rained. "))
    }
}

class DailyBriefTest {
    @Test fun saysTheDayInALine() {
        assertEquals("2 things at set times · 3 to-dos · 1 carried over", brief(2, 3, 1, 9, isToday = true))
        assertEquals("Nothing planned", brief(0, 0, 0, 0, isToday = true))
        assertEquals("A quiet day", brief(0, 0, 0, 0, isToday = false))
        assertEquals("1 to-do · 4 lines written", brief(0, 1, 0, 4, isToday = false))
    }
}
