package com.lfmpixel.lfm

import com.lfmpixel.lfm.assistant.InkKind
import com.lfmpixel.lfm.assistant.InkLine
import com.lfmpixel.lfm.data.Entry
import com.lfmpixel.lfm.data.EntryKind
import com.lfmpixel.lfm.edge.Labels
import com.lfmpixel.lfm.memory.Correction
import com.lfmpixel.lfm.memory.CorrectionKind
import com.lfmpixel.lfm.memory.Corrections
import com.lfmpixel.lfm.memory.Fact
import com.lfmpixel.lfm.memory.FactKind
import com.lfmpixel.lfm.memory.FactMerge
import com.lfmpixel.lfm.memory.FactPrompt
import com.lfmpixel.lfm.memory.FactRules
import com.lfmpixel.lfm.memory.Patterns
import com.lfmpixel.lfm.memory.Recall
import com.lfmpixel.lfm.memory.Sensitive
import com.lfmpixel.lfm.memory.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The memory's pure parts (memory/Memory.kt): finding, merging, ranking and recalling facts; corrections; patterns.
 * Robolectric only for org.json.
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [36], application = android.app.Application::class)
class MemoryTest {
    private val day = 86_400_000L
    private val t0 = ZonedDateTime.of(2026, 11, 1, 9, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
    private fun texts(s: String, chat: Boolean = false) = FactRules.find(s, chat).map { it.text }
    private fun fact(text: String, kind: FactKind = FactKind.Other, key: String = "", at: Long = t0, ref: String = "entry:1") =
        Fact(kind = kind, text = text, key = key, source = Source(ref, "Note", at))

    @Test fun rulesFindLastingFacts() {
        assertEquals(listOf("Ana is your sister"), texts("Lunch with my sister Ana on Sunday."))
        assertEquals(listOf("Lee is your dentist"), texts("My dentist is Dr Lee, Park Street."))
        assertEquals(listOf("Tom is your boss"), texts("Tom is my boss and wants the report."))
        assertTrue("You live in Leeds" in texts("I live in Leeds and work from home."))
        assertTrue("You work at Northwind" in texts("I work at Northwind as a designer."))
        assertEquals(listOf("You love oat flat whites"), texts("I love oat flat whites"))
        assertEquals(listOf("You don't like phone calls before 10"), texts("I hate phone calls before 10."))
        assertEquals(listOf("You prefer mornings for hard work"), texts("I prefer mornings for hard work."))
        assertEquals(listOf("Every Saturday, you swim at the lido"), texts("Every Saturday I swim at the lido."))
        assertEquals(listOf("You're working on a children's book"), texts("I'm working on a children's book"))
        assertTrue(texts("Ana's birthday is 14 March.").contains("Ana's birthday is 14 March"))
        // Chat: how the owner wants answers, and "remember that…".
        assertEquals(listOf("You asked: keep answers short"), texts("Please keep answers short.", chat = true))
        assertEquals(listOf("Your bike lock code is in the blue drawer"), texts("Remember that my bike lock code is in the blue drawer", chat = true))
        // Nothing lasting in a task or a question; never a secret.
        assertTrue(texts("buy oat milk").isEmpty())
        assertTrue(texts("what do I like?", chat = true).isEmpty())
        assertTrue(texts("My password is hunter2 and I love cats").isEmpty())
    }

    @Test fun sensitiveWordsAndSecrets() {
        assertTrue(Sensitive.looks("Dr Lee said the prescription is ready"))
        assertTrue(Sensitive.looks("pay the credit card bill"))
        assertFalse(Sensitive.looks("I'm fine, swim at the lido"))
        assertTrue(Sensitive.secret("the wifi password: blue-lemon-42"))
        assertTrue(Sensitive.secret("card 4111 1111 1111 1111"))
        assertFalse(Sensitive.secret("change the password next week"))
    }

    @Test fun aNewerFactOfTheSameSlotReplacesTheOld() {
        var facts = listOf(fact("Lee is your dentist", FactKind.Person, "person:dentist"))
        facts = FactMerge.merge(facts, fact("Lee is your dentist", FactKind.Person, "person:dentist", at = t0 + day, ref = "entry:2"))
        assertEquals(1, facts.size); assertEquals(2, facts[0].mentions)
        facts = FactMerge.merge(facts, fact("Park is your dentist", FactKind.Person, "person:dentist", at = t0 + 2 * day, ref = "entry:3"))
        assertEquals("Park is your dentist", facts.single().text)
        // The owner's own words are never changed by what is found later.
        val edited = facts.map { it.copy(text = "Dr Park (Mondays only) is your dentist", edited = true) }
        assertEquals("Dr Park (Mondays only) is your dentist", FactMerge.merge(edited, fact("Kim is your dentist", FactKind.Person, "person:dentist", at = t0 + 9 * day)).single().text)
    }

    @Test fun dedupeAndPrune() {
        val a = fact("You love oat flat whites", FactKind.Like).copy(id = 1)
        val b = fact("you love oat flat whites", FactKind.Like, at = t0 + day, ref = "chat:4").copy(id = 2)
        assertEquals(1, FactMerge.dedupe(listOf(a, b)).size)
        val now = t0 + 200 * day
        val stale = fact("You like jazz", FactKind.Like, at = t0, ref = "entry:5")
        val deletedSource = fact("Ana is your sister", FactKind.Person, at = now - day, ref = "entry:99")
        val kept = fact("You live in Leeds", FactKind.Place, at = now - day, ref = "entry:5")
        val mine = fact("Call me Jo", FactKind.Preference, at = t0, ref = "owner").copy(edited = true)
        assertEquals(listOf("Call me Jo", "You live in Leeds").sorted(),
            FactMerge.prune(listOf(stale, deletedSource, kept, mine), setOf(5L), now).map { it.text }.sorted())
    }

    @Test fun profileFitsTheBudgetAndLeavesOutSensitiveAndLockedJournal() {
        val facts = (1..60).map { fact("Friend number $it is called Name$it", FactKind.Person, "person:f$it") } +
            fact("You prefer short answers", FactKind.Preference) +
            fact("Dr Lee is your therapist", FactKind.Person) +
            fact("You felt low after the move", FactKind.Other).copy(journal = true)
        val phone = Recall.budget(4096)
        val p = Recall.profile(facts, listOf("Tends to get to-dos done around 09:00–12:00"), phone.profileChars, journalLocked = true, now = t0)
        assertTrue(p.length <= phone.profileChars)
        assertTrue("preferences rank first", p.lines().first().contains("short answers"))
        assertFalse(p.contains("therapist"))
        assertFalse(p.contains("felt low"))
        assertTrue(Recall.profile(facts, emptyList(), Recall.budget(32_000).profileChars, false, t0).length > p.length)
        assertTrue(Recall.profile(facts, emptyList(), 10_000, journalLocked = false, now = t0).contains("felt low"))
    }

    @Test fun relevantFactsAndNotes() {
        val facts = listOf(fact("Ana is your sister", FactKind.Person), fact("Ana's birthday is 14 March", FactKind.Person),
            fact("You live in Leeds", FactKind.Place))
        assertEquals(listOf("Ana's birthday is 14 March"), Recall.relevant(facts, "when is Ana's birthday?", null, false).map { it.text }.take(1))
        val e = Entry(id = 1, kind = EntryKind.Note, title = "Gift ideas", body = "Ana: a pottery class", createdAt = t0)
        val block = Recall.turnBlock(facts.take(1), Recall.notes(listOf(e), 700, ZoneId.of("UTC")))
        assertTrue(block.contains("- Ana is your sister"))
        assertTrue(block.contains("[Note · 1 Nov 2026] Gift ideas: Ana: a pottery class"))
        assertEquals("", Recall.turnBlock(emptyList(), ""))
    }

    @Test fun aCorrectionSticksForLinesLikeIt() {
        val list = listOf(Correction(1, CorrectionKind.Kind, "buy a new desk lamp", "Todo", "Idea", t0))
        assertEquals("Idea", Corrections.match("buy a desk lamp for the study", list, CorrectionKind.Kind)?.to)
        assertNull(Corrections.match("call the plumber", list, CorrectionKind.Kind))
        // The newer of two matching corrections wins.
        val two = list + Correction(2, CorrectionKind.Kind, "buy a new desk lamp", "Idea", "Todo", t0 + day)
        assertEquals("Todo", Corrections.match("buy a new desk lamp", two, CorrectionKind.Kind)?.to)
        // On the Ink line it beats the rules' guess, and the line goes in as written.
        val sorted = InkLine.guess("buy a desk lamp for the study")
        assertEquals(InkKind.Todo, sorted.kind)
        val g = InkLine.withLearned("buy a desk lamp for the study", sorted, InkKind.Idea)
        assertEquals(InkKind.Idea, g.kind)
        assertEquals("buy a desk lamp for the study", InkLine.call("buy a desk lamp for the study", g.kind, java.time.LocalDate.of(2026, 11, 2), g)!!.args["text"])
        // A reminder only when the line names a time.
        assertEquals(InkKind.Todo, InkLine.withLearned("buy a lamp", InkLine.guess("buy a lamp"), InkKind.Reminder).kind)
        // And the server's classifier sees it as an example.
        assertTrue(Labels.learnedExamples("buy a desk lamp", list).contains("\"buy a new desk lamp\" -> kind \"idea\""))
        assertEquals("", Labels.learnedExamples("buy a desk lamp", emptyList()))
    }

    @Test fun patternsNeedEnoughToGoOn() {
        val zone = ZoneId.of("UTC")
        val now = t0 + 30 * day
        val done = (0 until 12).map { i -> Entry(id = i + 1L, kind = EntryKind.Todo, title = "Task $i", done = true,
            doneAt = t0 + i * day + (if (i < 9) 1 else 8) * 3_600_000L, createdAt = t0 + i * day) }
        val slipping = Entry(id = 50, kind = EntryKind.Todo, title = "Do the tax return", dueAt = now - 10 * day, createdAt = now - 20 * day)
        val notes = (0 until 4).map { i -> Entry(id = 60L + i, kind = EntryKind.Note, title = "Garden plan $i", body = "tulips and compost", createdAt = now - i * day) }
        val p = Patterns.find(done + slipping + notes, now, zone)
        assertEquals(9..11, p.focusHours)
        assertEquals(listOf("Do the tax return"), p.slipping)
        assertTrue(p.themes.contains("garden"))
        assertTrue(p.lines().any { it.startsWith("Tends to get to-dos done around 09:00–12:00") })
        val few = Patterns.find(done.take(3), now, zone)
        assertNull(few.focusHours)
        assertTrue(few.lines().isEmpty())
    }

    @Test fun modelLinesAreParsedOrDropped() {
        val out = "person: Ana is your sister [2]\n- like: You love oat flat whites [1].\nnonsense line\nweather: rain [3]\nplace: x [1]\nNone"
        assertEquals(listOf(Triple(FactKind.Person, "Ana is your sister", 2), Triple(FactKind.Like, "You love oat flat whites", 1)), FactPrompt.parse(out))
    }

    @Test fun factsRoundTripThroughJson() {
        val f = fact("Ana is your sister", FactKind.Person, "person:sister").copy(id = 7, mentions = 3, vec = floatArrayOf(0.5f, -0.25f))
        val back = Fact.fromJson(7, f.toJson())!!
        assertEquals(f.copy(vec = null), back.copy(vec = null))
        assertEquals(listOf(0.5f, -0.25f), back.vec!!.toList())
    }
}
