package com.lfmpixel.lfm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lfmpixel.lfm.data.ChatMessage
import com.lfmpixel.lfm.data.Entry
import com.lfmpixel.lfm.data.EntryKind
import com.lfmpixel.lfm.data.FtsQuery
import com.lfmpixel.lfm.data.LfmDatabase
import com.lfmpixel.lfm.data.Role
import com.lfmpixel.lfm.llm.Backend
import com.lfmpixel.lfm.llm.BenchRun
import com.lfmpixel.lfm.llm.GenStats
import com.lfmpixel.lfm.llm.TextSession
import com.lfmpixel.lfm.memory.CorrectionKind
import com.lfmpixel.lfm.memory.GcmSealer
import com.lfmpixel.lfm.memory.MemoryStore
import com.lfmpixel.lfm.memory.Sealer
import com.lfmpixel.lfm.tools.AddEntryTool
import com.lfmpixel.lfm.tools.ToolEnv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * A made-up owner's notebook over four simulated days, against a real Room database: what Inkling learns, that answers'
 * prompts carry it, that a correction sticks (across a restart too), the nightly pass, the switch, and Forget. Timings
 * are printed and written to build/memory-scenario/ with the prompts, for the proof.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class MemoryScenarioTest {
    private lateinit var db: LfmDatabase
    private val zone = ZoneId.of("Europe/London")
    private var clock = ZonedDateTime.of(2026, 11, 2, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
    private var learning = true
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val out = File("build/memory-scenario").apply { mkdirs() }
    private val log = StringBuilder()

    /** AES-GCM with a key in memory, standing in for the keystore's; [forget] makes a new key, as deleting the alias does. */
    private class TestSealer : Sealer {
        var key: SecretKey = newKey()
        override fun seal(plain: String) = GcmSealer.seal(key, plain)
        override fun open(sealed: String) = runCatching { GcmSealer.open(key, sealed) }.getOrNull()
        override fun forget() { key = newKey() }
        companion object { fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
    }

    /** The small model of the nightly pass, answering in its line format. */
    private class ScriptedModel(val answer: String) : TextSession {
        var prompts = mutableListOf<String>()
        override val path = "test"; override val backend = Backend.LiteRtCpu; override val contextTokens = 4096; override val isClosed = false
        override suspend fun countTokens(text: String) = text.length / 4
        override fun generate(prompt: String, maxTokens: Int, temp: Float, topP: Float): Flow<String> { prompts += prompt; return flowOf(answer) }
        override fun stats() = GenStats(0.0, 0, 0.0, 0, 0)
        override suspend fun bench(nPrompt: Int, nGen: Int) = BenchRun(0.0, 0, 0.0, 0.0, 0, 0.0, 0, 0)
        override suspend fun runInfo() = ""
        override suspend fun close() {}
    }

    private val sealer = TestSealer()
    private lateinit var memory: MemoryStore

    private fun store() = MemoryStore(db, sealer, embedder = { null },
        search = { q, n -> FtsQuery.from(q.replace(Regex("""\b(?:what|should|get|for|her|his|my|the|a|is|when|i)\b""", RegexOption.IGNORE_CASE), " "))
            ?.replace(" ", " OR ")?.let { db.entries().search(it, n) }.orEmpty() },
        journalLocked = { false }, learning = { learning }, scope = scope, now = { clock })

    @Before fun setUp() {
        db = LfmDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        memory = store()
    }

    @After fun tearDown() {
        File(out, "timings.txt").writeText(log.toString())
        db.close()
    }

    private fun at(day: Int, hour: Int, minute: Int = 0) =
        ZonedDateTime.of(2026, 11, 1, hour, minute, 0, 0, zone).plusDays(day.toLong()).toInstant().toEpochMilli()

    private fun <T> timed(what: String, f: suspend () -> T): T = runBlocking {
        val t0 = System.nanoTime()
        val r = f()
        val ms = (System.nanoTime() - t0) / 1e6
        "%-58s %7.1f ms".format(what, ms).also { println(it); log.appendLine(it) }
        r
    }

    private suspend fun note(day: Int, hour: Int, kind: EntryKind, title: String, body: String = "", done: Boolean = false, dueDay: Int? = null) {
        clock = at(day, hour)
        db.entries().insert(Entry(kind = kind, title = title, body = body, createdAt = clock, updatedAt = clock, done = done,
            doneAt = if (done) clock else null, dueAt = dueDay?.let { at(it, 9) }))
    }

    /** Day 1: a week's worth of a made-up notebook, written in one go. */
    private suspend fun seed() {
        val notes = listOf(
            EntryKind.Note to ("Family" to "Lunch with my sister Ana on Sunday. Ana's birthday is 14 March."),
            EntryKind.Note to ("Dentist" to "My dentist is Dr Lee, Park Street, Tuesdays."),
            EntryKind.Note to ("About me" to "I live in Leeds. I work at Northwind as a designer."),
            EntryKind.Note to ("Work style" to "I prefer mornings for deep work. I hate phone calls before 10."),
            EntryKind.Note to ("Weekend" to "Every Saturday I swim at the lido."),
            EntryKind.Idea to ("Book" to "I'm working on a children's book about a fox who collects buttons."),
            EntryKind.Note to ("Gift ideas" to "Ana: a pottery class, or the blue scarf from the market."),
            EntryKind.Note to ("Coffee" to "I love oat flat whites from the corner place."),
            EntryKind.Journal to ("Tired" to "Slept badly, too much screen time."),
            EntryKind.Note to ("Garden plan" to "Tulips by the fence, compost bin by the shed."),
            EntryKind.Note to ("Garden plan 2" to "Order tulip bulbs, garden soil."),
            EntryKind.Idea to ("Garden pond" to "What if the garden had a small pond?"),
            EntryKind.Note to ("Boss" to "Tom is my boss; wants the deck by Friday."),
        )
        notes.forEachIndexed { i, (k, tb) -> note(1, 7 + i % 12, k, tb.first, tb.second) }
        // To-dos done mostly in the morning, and one that keeps slipping.
        for (i in 0 until 10) note(1 - (i % 6), if (i < 8) 9 + i % 3 else 20, EntryKind.Todo, "Task $i", done = true)
        clock = at(-20, 10)
        db.entries().insert(Entry(kind = EntryKind.Todo, title = "Do the tax return", createdAt = clock, updatedAt = clock, dueAt = at(-10, 9)))
        repeat(25) { i -> note(1, 12 + i % 6, EntryKind.Note, "Shopping $i", "eggs, bread, milk number $i") }
    }

    @Test fun inklingLearnsOverDaysAndForgets() = runBlocking {
        // Day 1, morning: the notebook fills; the quick pass reads it by rules.
        seed()
        clock = at(1, 20)
        val found = timed("day 1: quick pass over ${db.entries().all().size} entries") { memory.learnNow() }
        val texts = memory.facts.value.map { it.text }
        log.appendLine("day 1: $found facts found, ${texts.size} kept: $texts")
        for (want in listOf("Ana is your sister", "Lee is your dentist", "You live in Leeds", "You work at Northwind",
            "You prefer mornings for deep work", "Every Saturday, you swim at the lido", "Tom is your boss", "Ana's birthday is 14 March"))
            assertTrue("$want in $texts", want in texts)
        // Sealed: nothing readable in the table.
        val rows = db.learned().ofType(MemoryStore.FACT)
        assertEquals(texts.size, rows.size)
        assertFalse(rows.any { r -> texts.any { r.sealed.contains(it) } || r.sealed.contains("sister") || r.sealed.contains("\"kind\"") })

        // An answer's prompt carries what was learned: the profile, and the notes near the question.
        val q = "What should I get my sister for her birthday?"
        val phone = timed("day 1: recall for a question, phone budget (4k)") { memory.context(q, 4096) }
        val server = timed("day 1: recall for a question, server budget (32k)") { memory.context(q, 32_768) }
        assertTrue(phone.system.contains("Ana is your sister"))
        assertTrue(phone.turn.contains("pottery class"))
        assertTrue(phone.system.length + phone.turn.length < 1_400)
        assertTrue(server.system.length >= phone.system.length)
        File(out, "prompt-phone.txt").writeText("SYSTEM+=${phone.system}\n\nUSER: $q${phone.turn}")
        File(out, "prompt-server.txt").writeText("SYSTEM+=${server.system}\n\nUSER: $q${server.turn}")

        // Day 2: the owner tells the chat how they want answers; Talk hears a routine.
        clock = at(2, 9)
        db.messages().insert(ChatMessage(role = Role.User, text = "Please keep answers short.", createdAt = clock))
        memory.heard("I usually walk to work along the canal")
        clock = at(2, 9, 5)
        timed("day 2: quick pass (a chat message, a Talk turn)") { memory.learnNow() }
        assertTrue(memory.facts.value.any { it.text == "You asked: keep answers short" && it.source.ref.startsWith("chat:") })
        assertTrue(memory.facts.value.any { it.text == "You usually walk to work along the canal" && it.source.ref == "talk" })
        assertTrue(memory.context("hello there", 4096).system.contains("keep answers short"))

        // Day 2: corrections. A desk lamp line goes to ideas; printer ink to the Work list.
        val sorted = com.lfmpixel.lfm.assistant.InkLine.guess("buy a new desk lamp")
        assertEquals(com.lfmpixel.lfm.assistant.InkKind.Todo, sorted.kind)
        memory.corrected(CorrectionKind.Kind, "buy a new desk lamp", "Todo", "Idea")
        memory.corrected(CorrectionKind.List, "Buy printer ink", "Shopping", "Work")
        // Day 3: lines like them are sorted the owner's way.
        clock = at(3, 10)
        val kind = timed("day 3: correction lookup on the Ink line") { memory.learnedKind("buy a desk lamp for the study") }
        assertEquals("Idea", kind)
        assertEquals(com.lfmpixel.lfm.assistant.InkKind.Idea, com.lfmpixel.lfm.assistant.InkLine.withLearned("buy a desk lamp for the study",
            com.lfmpixel.lfm.assistant.InkLine.guess("buy a desk lamp for the study"), com.lfmpixel.lfm.assistant.InkKind.valueOf(kind!!)).kind)
        val env = ToolEnv(db, null, null, null, null, null, zone = zone).also { it.learnedList = { t -> memory.learnedList(t) } }
        AddEntryTool(env, EntryKind.Todo).run(mapOf("text" to "buy printer ink and paper", "list" to "Shopping"))
        assertEquals("Work", db.entries().all().maxBy { it.id }.list)
        // A list the owner named in so many words still wins.
        AddEntryTool(env, EntryKind.Todo).run(mapOf("text" to "buy printer ink", "list" to "Home"))
        assertEquals("Home", db.entries().all().maxBy { it.id }.list)
        assertNull(memory.learnedKind("call the plumber"))

        // Day 3: the dentist changed; that note is edited.
        val dentist = db.entries().all().first { it.title == "Dentist" }
        clock = at(3, 18)
        db.entries().update(dentist.copy(body = "My dentist is now Dr Park, Mill Road.", updatedAt = clock))
        memory.learnNow()
        assertTrue(memory.facts.value.any { it.text == "Park is your dentist" })
        assertFalse(memory.facts.value.any { it.text == "Lee is your dentist" })

        // Night 3, charging: the nightly pass with the small model, then merging, pruning and patterns.
        clock = at(3, 23, 30)
        val model = ScriptedModel("person: Ana is your sister [1]\nproject: You're writing a children's book about a fox [6]\nlike: You love gardening [3]\nweather: rainy [2]")
        val report = timed("night 3: nightly pass (scripted model)") { memory.nightly { model } }
        log.appendLine("night 3: $report")
        assertTrue(model.prompts.isNotEmpty())
        assertTrue(memory.facts.value.any { it.text == "You love gardening" })
        assertEquals("duplicates merged", 1, memory.facts.value.count { it.text == "Ana is your sister" })
        val patterns = memory.patterns.value
        log.appendLine("patterns: $patterns")
        assertTrue(patterns.any { it.startsWith("Tends to get to-dos done around") })
        assertTrue(patterns.any { it.contains("Do the tax return") })

        // Day 4: the app restarts; what was learned is still there, and the correction still sticks.
        clock = at(4, 8)
        memory = store()
        timed("day 4: load after a restart (${db.learned().ofType(MemoryStore.FACT).size} sealed facts)") { memory.load() }
        assertTrue(memory.facts.value.any { it.text == "Park is your dentist" })
        assertEquals("Idea", memory.learnedKind("buy another desk lamp"))
        assertTrue(memory.context("What's my dentist's name?", 4096).let { it.system + it.turn }.contains("Park"))

        // The switch: off, nothing is learned and nothing learned is used; notes still are.
        learning = false
        clock = at(4, 9)
        note(4, 9, EntryKind.Note, "Cat", "My cat is called Miso.")
        assertEquals(0, memory.learnNow())
        val off = memory.context("What should I get my sister for her birthday?", 4096)
        assertEquals("", off.system)
        assertTrue("notes are still read", off.turn.contains("Lunch with my sister"))
        assertFalse("the memory no longer widens the search", off.turn.contains("pottery class"))
        assertNull(memory.learnedKind("buy a desk lamp for the study"))
        learning = true

        // Forget: every fact, correction, pattern and the key; the notes stay, and are not read again.
        val before = db.learned().ofType(MemoryStore.FACT).first().sealed
        timed("day 4: forget everything learned") { memory.forget() }
        assertTrue(memory.facts.value.isEmpty())
        assertTrue(memory.corrections.value.isEmpty())
        assertTrue(memory.patterns.value.isEmpty())
        assertTrue(db.learned().ofType(MemoryStore.FACT).isEmpty())
        assertTrue(db.learned().ofType(MemoryStore.CORRECTION).isEmpty())
        assertNull("old rows can't be opened with the new key", sealer.open(before))
        assertNull(memory.learnedKind("buy a desk lamp for the study"))
        assertEquals("", memory.context("What should I get my sister for her birthday?", 4096).system)
        clock = at(4, 10)
        memory.learnNow()
        assertTrue("old notes are not learned again", memory.facts.value.isEmpty())
        assertTrue(db.entries().all().any { it.title == "Family" })
        // A restart after Forget finds nothing either.
        memory = store(); memory.load()
        assertTrue(memory.facts.value.isEmpty())
        // What is written next is learned as usual.
        note(4, 11, EntryKind.Note, "New neighbour", "My neighbour is called Priya.")
        clock = at(4, 11, 1)
        memory.learnNow()
        assertEquals(listOf("Priya is your neighbour"), memory.facts.value.map { it.text })
    }
}
