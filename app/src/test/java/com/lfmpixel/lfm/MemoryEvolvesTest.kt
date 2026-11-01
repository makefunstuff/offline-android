package com.lfmpixel.lfm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lfmpixel.lfm.data.ChatMessage
import com.lfmpixel.lfm.data.Entry
import com.lfmpixel.lfm.data.EntryKind
import com.lfmpixel.lfm.data.LfmDatabase
import com.lfmpixel.lfm.data.Role
import com.lfmpixel.lfm.llm.Backend
import com.lfmpixel.lfm.llm.BenchRun
import com.lfmpixel.lfm.llm.GenStats
import com.lfmpixel.lfm.llm.TextSession
import com.lfmpixel.lfm.memory.Episode
import com.lfmpixel.lfm.memory.EpisodePrompt
import com.lfmpixel.lfm.memory.Episodes
import com.lfmpixel.lfm.memory.Fact
import com.lfmpixel.lfm.memory.FactKind
import com.lfmpixel.lfm.memory.FactMerge
import com.lfmpixel.lfm.memory.FactPrompt
import com.lfmpixel.lfm.memory.FactRules
import com.lfmpixel.lfm.memory.GcmSealer
import com.lfmpixel.lfm.memory.MemoryCommand
import com.lfmpixel.lfm.memory.MemoryStore
import com.lfmpixel.lfm.memory.Recall
import com.lfmpixel.lfm.memory.Sealer
import com.lfmpixel.lfm.memory.Source
import com.lfmpixel.lfm.voice.SpeechFormat
import com.lfmpixel.lfm.voice.SpeechLanguage
import com.lfmpixel.lfm.voice.Talk
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
 * Wren's memory that grows across chats and Talk (LFMP-31): facts found in conversations, merged and updated rather
 * than repeated, recalled by fit, recency and importance, "Remember that…" and "Forget that…", past conversations,
 * the learning switch, and a Talk turn whose prompt carries a remembered fact. Prompts go to build/memory-evolves/.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class MemoryEvolvesTest {
    private val day = 86_400_000L
    private val zone = ZoneId.of("Europe/London")
    private val t0 = ZonedDateTime.of(2026, 11, 2, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
    private var clock = t0
    private var learning = true
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val out = File("build/memory-evolves").apply { mkdirs() }
    private lateinit var db: LfmDatabase
    private val sealer = object : Sealer {
        var key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun seal(plain: String) = GcmSealer.seal(key, plain)
        override fun open(sealed: String) = runCatching { GcmSealer.open(key, sealed) }.getOrNull()
        override fun forget() { key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
    }

    /** The server's model reading a finished conversation, answering in its line format. */
    private class ScriptedModel(val answer: String) : TextSession {
        val prompts = mutableListOf<String>()
        override val path = "test"; override val backend = Backend.LiteRtCpu; override val contextTokens = 4096; override val isClosed = false
        override suspend fun countTokens(text: String) = text.length / 4
        override fun generate(prompt: String, maxTokens: Int, temp: Float, topP: Float): Flow<String> { prompts += prompt; return flowOf(answer) }
        override fun stats() = GenStats(0.0, 0, 0.0, 0, 0)
        override suspend fun bench(nPrompt: Int, nGen: Int) = BenchRun(0.0, 0, 0.0, 0.0, 0, 0.0, 0, 0)
        override suspend fun runInfo() = ""
        override suspend fun close() {}
    }

    private var server: TextSession? = null

    private fun store() = MemoryStore(db, sealer, embedder = { null }, search = { _, _ -> emptyList() }, journalLocked = { false },
        learning = { learning }, scope = scope, now = { clock }, server = { server })

    @Before fun setUp() { db = LfmDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>()) }
    @After fun tearDown() { db.close() }

    private fun fact(text: String, kind: FactKind = FactKind.Other, key: String = "", at: Long = t0, n: Int = 1, edited: Boolean = false) =
        Fact(kind = kind, text = text, key = key, source = Source("chat:1", "Chat", at), mentions = n, edited = edited)

    // Extraction ------------------------------------------------------------------------------------------------

    @Test fun extractsGoalsJobsAndCommands() {
        val found = FactRules.find("I started a new job at Acme Labs. My goal is to run a marathon. I'm saving for a trip to Japan.")
        val texts = found.map { it.text }
        assertTrue(texts.toString(), "You work at Acme Labs" in texts)
        assertTrue(texts.toString(), "You want to run a marathon" in texts)
        assertTrue(texts.toString(), "You're saving for a trip to Japan" in texts)
        assertEquals(FactKind.Goal, found.first { it.text.startsWith("You want") }.kind)

        assertEquals(MemoryCommand.Cmd.Remember("my sister is called Ana"), MemoryCommand.parse("Remember that my sister is called Ana."))
        assertEquals(MemoryCommand.Cmd.Remember("I'm vegetarian"), MemoryCommand.parse("Wren, remember I'm vegetarian"))
        assertNull("a reminder, not memory", MemoryCommand.parse("Remember to buy milk"))
        assertNull(MemoryCommand.parse("What should I remember about the trip?"))
        assertEquals(MemoryCommand.Cmd.Forget("I like coffee"), MemoryCommand.parse("Please forget that I like coffee"))
        assertEquals(MemoryCommand.Cmd.ForgetAll, MemoryCommand.parse("forget everything"))
        assertNull(MemoryCommand.parse("forget it"))
        // Ordinary requests are left to the model: reminders, notes, questions, "forget it, …".
        for (t in listOf("Remember that restaurant we went to in Paris?", "Note: call the plumber on Tuesday", "Note that the meeting moved to 3pm",
            "Don't forget: dentist Tuesday at 3", "Keep in mind that we leave at 6", "Remember I have a meeting at 10 tomorrow",
            "Remember my dentist appointment tomorrow at 9", "remember my keys when I leave", "Can you remember that I need to call mum at 5?",
            "Forget it, let's talk about something else", "Forget the last answer, explain it simpler", "Forget about work, tell me a joke",
            "forget the coffee, I'll have tea", "Forget my previous question"))
            assertNull(t, MemoryCommand.parse(t))
        assertEquals(MemoryCommand.Cmd.Forget("Leeds", explicit = false), MemoryCommand.parse("Forget about Leeds"))
        for (t in listOf("Forget that one", "Forget that last question")) assertNull(t, MemoryCommand.parse(t))
        // Common ways to ask, said or typed.
        for (t in listOf("Can you remember that I'm vegetarian?", "Don't forget that I'm vegetarian", "Remember, I'm vegetarian",
            "I want you to remember that I'm vegetarian"))
            assertEquals(t, MemoryCommand.Cmd.Remember("I'm vegetarian"), MemoryCommand.parse(t))
        assertEquals(MemoryCommand.Cmd.Remember("I take my pills at 9"), MemoryCommand.parse("Remember that I take my pills at 9"))
        assertEquals(MemoryCommand.Cmd.Forget("I like coffee"), MemoryCommand.parse("Can you forget that I like coffee?"))
        assertEquals(MemoryCommand.Cmd.Forget("my address", explicit = false), MemoryCommand.parse("Forget my address"))
        assertEquals(MemoryCommand.Cmd.Forget("Ana"), MemoryCommand.parse("Forget what I said about Ana"))
        for (t in listOf("Remember: call the bank", "Remember, dentist on Friday at 3", "Remember my umbrella", "Remember I parked on level 3",
            "Remember that I'm out on Friday", "Remember that I'm out Friday", "Remember I'm picking up the kids at 3", "Remember that I parked on level 3",
            "Remember that I'm away until Sunday")) assertNull(t, MemoryCommand.parse(t))
        for (t in listOf("Remember I have two cats", "Remember I don't eat pork", "Wren, remember I take the bus", "Remember we have a dog called Rex",
            "Remember, Tom is vegetarian", "Remember, keep your answers short", "Remember I'm learning Japanese", "Remember that the bins go out on Monday"))
            assertTrue(t, MemoryCommand.parse(t) is MemoryCommand.Cmd.Remember)
        assertEquals(MemoryCommand.Cmd.Remember("Ana's birthday is on Friday"), MemoryCommand.parse("Remember that Ana's birthday is on Friday"))
        // Working with someone is not an employer; trying to do something once is not a goal.
        assertTrue(FactRules.find("I work with Tom on the quarterly report.").none { it.key == "place:work" })
        for (t in listOf("Why does it crash when I'm trying to open the camera?", "I'm trying to fix this error in my code", "I plan to call my mum tomorrow"))
            assertTrue(t, FactRules.find(t, chat = true).none { it.kind == FactKind.Goal })
        assertEquals("You want to run a marathon with my brother".replace("my", "your"), FactRules.find("My goal is to run a marathon with my brother").single().text)
        // Reminders never become lasting facts through the rules either.
        for (t in listOf("Remember to buy milk", "Remember that my meeting is at 3pm tomorrow", "Remember that I need to call mum", "Remind me to call mum tonight"))
            assertTrue(t, FactRules.find(t, chat = true).isEmpty())
        // A note that starts with "forget" still teaches what follows.
        assertTrue(FactRules.find("Forget about Leeds. I moved to York.").any { it.text == "You live in York" })
        // A forget is never itself read as a fact.
        assertTrue(FactRules.find("Forget that I like coffee", chat = true).isEmpty())
        assertEquals("Ana is your sister", MemoryCommand.fact("my sister is called Ana").text)
        assertEquals(FactKind.Person, MemoryCommand.fact("my sister is called Ana").kind)
        assertEquals("You're vegetarian", MemoryCommand.fact("I'm vegetarian").text)
        assertEquals(FactKind.Preference, MemoryCommand.fact("I prefer short answers").kind)

        val answer = "person: Ana is your sister\ngoal: You want to learn Japanese\nsummary: Planning Ana's birthday and a Japan trip."
        assertEquals(listOf(FactKind.Person, FactKind.Goal), FactPrompt.parse(answer).map { it.first })
        assertEquals("Planning Ana's birthday and a Japan trip", EpisodePrompt.summary(answer))
    }

    // De-dup and update ---------------------------------------------------------------------------------------

    @Test fun changedFactsKeepWhatTheyWere() {
        val home = fact("You live in Leeds", FactKind.Place, "place:home", t0)
        val moved = FactMerge.merge(listOf(home), fact("You live in York", FactKind.Place, "place:home", t0 + 30 * day)).single()
        assertEquals("You live in York", moved.text)
        assertEquals("You live in Leeds", moved.was)
        assertEquals(t0 + 30 * day, moved.changedAt)
        // Said again in the same words: one fact, seen twice, nothing marked changed.
        val again = FactMerge.merge(listOf(moved), fact("You live in York", FactKind.Place, "place:home", t0 + 31 * day).copy(source = Source("talk", "Talk", t0 + 31 * day))).single()
        assertEquals(2, again.mentions)
        assertEquals("You live in Leeds", again.was)
        // A slot the owner asked Wren to remember still moves when they say it changed; hand-edited words never do.
        val told = fact("You live in Leeds", FactKind.Place, "place:home", t0).copy(source = Source("owner", "You said", t0), edited = true)
        val news = fact("You live in York", FactKind.Place, "place:home", t0 + day)
        assertEquals("You live in York", FactMerge.merge(listOf(told), news).single().text)
        assertEquals("You live in Leeds", FactMerge.merge(listOf(told), news).single().was)
        assertEquals("You live in Leeds", FactMerge.merge(listOf(told.copy(source = Source("entry:1", "Note", t0))), news).single().text)
        // Round trip through the sealed JSON.
        assertEquals("You live in Leeds", Fact.fromJson(1, moved.toJson())!!.was)
    }

    @Test fun nearDuplicatesByMeaningMerge() {
        val v = floatArrayOf(0.6f, 0.8f, 0f)
        val w = floatArrayOf(0.62f, 0.78f, 0.05f).let { a -> val n = kotlin.math.sqrt(a.sumOf { (it * it).toDouble() }).toFloat(); FloatArray(3) { a[it] / n } }
        val a = fact("You love oat flat whites", FactKind.Like, at = t0).copy(id = 1, vec = v)
        val b = fact("You enjoy coffee with oat milk", FactKind.Like, at = t0 + day).copy(id = 2, vec = w)
        val other = fact("You enjoy hiking", FactKind.Like, at = t0).copy(id = 3, vec = floatArrayOf(0f, 0f, 1f))
        val merged = FactMerge.dedupe(listOf(a, b, other))
        assertEquals(2, merged.size)
        assertEquals(2, merged.first { it.id == 1L }.mentions)
        // Alike in meaning but two people: never merged.
        val ana = fact("Ana is your friend", FactKind.Person, "person:friend:ana").copy(id = 4, vec = v)
        val mia = fact("Mia is your friend", FactKind.Person, "person:friend:mia").copy(id = 5, vec = v)
        assertEquals(2, FactMerge.dedupe(listOf(ana, mia)).size)
        assertEquals(2, FactMerge.dedupe(listOf(ana.copy(key = ""), mia.copy(key = ""))).size)
    }

    // Recall ranking ------------------------------------------------------------------------------------------

    @Test fun recallRanksByFitThenRecencyAndImportance() {
        val now = t0 + 200 * day
        val old = fact("Tom from the climbing gym likes coffee", at = t0)
        val recent = fact("Sam from the climbing gym likes coffee", at = now - day, n = 3)
        val loud = fact("You prefer short answers", FactKind.Preference, at = now, n = 9, edited = true)
        val ranked = Recall.relevant(listOf(old, loud, recent), "who at the climbing gym likes coffee?", null, false, now = now)
        assertEquals(listOf(recent.text, old.text), ranked.map { it.text })
        // Importance alone never brings in a fact that doesn't fit.
        assertFalse(ranked.any { it.text == loud.text })
        // Better fit still beats recency.
        val exact = fact("Ana's birthday is 14 March", FactKind.Person, at = t0)
        val vague = fact("Ana likes March", at = now)
        assertEquals(exact.text, Recall.relevant(listOf(vague, exact), "when is Ana's birthday?", null, false, now = now).first().text)
    }

    // Forget --------------------------------------------------------------------------------------------------

    @Test fun forgetFindsWhatItIsAbout() {
        val facts = listOf(fact("You like coffee"), fact("Ana is your sister", FactKind.Person), fact("Ana's birthday is 14 March", FactKind.Person),
            fact("You live in York", FactKind.Place))
        assertEquals(listOf("You like coffee"), FactMerge.matching(facts, FactRules.you("I like coffee")).map { it.text })
        assertEquals(setOf("Ana is your sister", "Ana's birthday is 14 March"), FactMerge.matching(facts, "Ana").map { it.text }.toSet())
        assertTrue(FactMerge.matching(facts, "the weather").isEmpty())
    }

    // Past conversations --------------------------------------------------------------------------------------

    @Test fun pastConversations() {
        assertEquals("the garden", Episodes.asked("What did I say about the garden?"))
        assertEquals("Ana's present", Episodes.asked("what did I tell you about Ana's present"))
        assertNull(Episodes.asked("What's the weather?"))
        val garden = Episodes.of("talk", t0, listOf("I want tulips by the fence in the garden" to "Lovely idea.", "And a small pond" to "Nice."))!!
        val work = Episodes.of("chat", t0 + day, listOf("The deck for Tom is due Friday" to "Noted."))!!
        val found = Episodes.search(listOf(work, garden), "the garden", null)
        assertEquals(listOf(garden), found)
        val block = Episodes.block(found, "the garden", 700, zone)
        assertTrue(block, block.startsWith("[Talk · 2 Nov 2026]") && block.contains("tulips by the fence"))
        assertNull("too little to keep", Episodes.of("chat", t0, listOf("hi" to "Hello!")))
        // Compacting: old, read episodes keep only their summary; at most MAX are kept.
        val old = garden.copy(read = true, summary = "Garden plans")
        val now = t0 + 40 * day
        assertEquals("", Episodes.compact(listOf(old), now).single().lines)
        assertEquals(Episodes.MAX, Episodes.compact(List(Episodes.MAX + 20) { i -> work.copy(at = t0 + i) }, now).size)
        assertEquals("Garden plans", Episode.fromJson(3, old.toJson())!!.summary)
        // Commands are not kept in a conversation; a forgotten thing is scrubbed from it.
        val talk = Episodes.of("talk", t0, listOf("Forget that my sister is Ana" to "Okay.", "My sister Ana loves the garden" to "Nice.", "Tulips by the fence" to "Yes."))!!
        assertFalse(talk.lines.contains("Forget"))
        val scrubbed = Episodes.scrub(talk, listOf("Ana is your sister"))!!
        assertFalse(scrubbed.lines.contains("Ana"))
        assertTrue(scrubbed.lines.contains("Tulips"))
        assertNull(Episodes.scrub(Episodes.of("talk", t0, listOf("My sister Ana is visiting" to "Lovely."))!!, listOf("Ana is your sister")))
    }

    // The store, end to end -----------------------------------------------------------------------------------

    @Test fun rememberForgetAndATalkTurnThatUsesIt() = runBlocking {
        val memory = store()
        // Said in Talk: "Remember that…" is kept at once, in the owner's words.
        assertEquals("Got it, I'll remember: Ana is your sister.", memory.command("Remember that my sister is called Ana"))
        assertEquals("Got it, I'll remember: you prefer short answers.", memory.command("Remember that I prefer short answers"))
        assertTrue(memory.facts.value.any { it.text == "Ana is your sister" && it.edited && it.kind == FactKind.Person })

        // The next Talk turn's prompt carries it without being asked: the profile in the system prompt.
        val said = "What could I get my sister for her birthday?"
        val recall = memory.context(said, Talk.TALK_CONTEXT)
        val prompt = SpeechFormat.talkPrompt(SpeechFormat.talkSystem(SpeechLanguage.entries.first(), "Monday 2 November 2026"), recall, emptyList(), said)
        File(out, "talk-prompt.txt").writeText(prompt)
        assertTrue(prompt, prompt.contains("Ana is your sister"))
        assertTrue(prompt, prompt.contains("You prefer short answers"))
        assertTrue(prompt.indexOf("Ana is your sister") < prompt.indexOf(said))
        // Omni on the phone gets the same facts after what was said (no notes; its context is small).
        assertTrue(memory.context("Ana's birthday plans", 4096, notes = false).let { it.system + it.turn }.contains("Ana is your sister"))

        // Forget that…: gone, and not learned again from a note that says it once more.
        assertEquals("Okay, I've forgotten that Ana is your sister.", memory.command("Forget that my sister is Ana"))
        assertFalse(memory.facts.value.any { it.text == "Ana is your sister" })
        clock += 60_000
        db.entries().insert(Entry(kind = EntryKind.Note, title = "Sunday", body = "Lunch with my sister Ana.", createdAt = clock, updatedAt = clock))
        clock += 60_000
        memory.learnNow()
        assertFalse("forgotten stays forgotten", memory.facts.value.any { it.text == "Ana is your sister" })
        assertFalse(memory.context(said, Talk.TALK_CONTEXT).system.contains("Ana is your sister"))
        assertEquals("I don't have anything about that.", memory.command("forget that I own a boat"))
        // The nightly model pass doesn't bring it back either, even in other words.
        val night = ScriptedModel("person: Your sister is called Ana [1]\nlike: You love lunch on Sundays [1]")
        memory.nightly { night }
        assertTrue(night.prompts.isNotEmpty())
        assertFalse(memory.facts.value.toString(), memory.facts.value.any { it.text.contains("Ana") })
        assertTrue(memory.facts.value.any { it.text == "You love lunch on Sundays" })
        // A forget said before the store read the turn still catches it, and it isn't learned again.
        memory.heard("I like coffee")
        assertEquals("Okay, I've forgotten that you like coffee.", memory.command("Forget that I like coffee"))
        memory.heard("I like coffee")
        clock += 60_000
        memory.learnNow()
        assertFalse(memory.facts.value.any { it.text.contains("coffee") })
        // "Forget about…" with nothing to forget is left to the model, and touches nothing.
        memory.talkEnded(listOf("The weather in Leeds was awful today" to "Sorry to hear."))
        assertNull(memory.command("Forget about the weather"))
        assertTrue(memory.episodes.value.any { it.lines.contains("weather") })
        // Remembering it again by request brings it back.
        memory.command("Remember that my sister is called Ana")
        assertTrue(memory.facts.value.any { it.text == "Ana is your sister" })
        // A restart keeps what was forgotten forgotten, and what was remembered.
        val again = store().also { it.load() }
        assertTrue(again.facts.value.any { it.text == "Ana is your sister" })
    }

    @Test fun conversationsBecomeFactsAndCanBeSearched() = runBlocking {
        val model = ScriptedModel("goal: You want to learn Japanese\nperson: Kenji is your Japanese teacher\nsummary: Learning Japanese with Kenji")
        server = model
        val memory = store()
        memory.talkEnded(listOf("I've started Japanese lessons with Kenji on Thursdays" to "That sounds fun!", "I want to read manga in Japanese" to "Great goal."))
        // Read on the server in the background right after the conversation ended.
        repeat(100) { if (memory.episodes.value.firstOrNull()?.read != true) Thread.sleep(20) }
        val e = memory.episodes.value.single()
        assertTrue(e.read)
        assertEquals("Learning Japanese with Kenji", e.summary)
        assertTrue(model.prompts.single().contains("Japanese lessons with Kenji"))
        assertTrue(memory.facts.value.any { it.text == "You want to learn Japanese" && it.kind == FactKind.Goal })
        assertTrue(memory.facts.value.any { it.text == "Kenji is your Japanese teacher" })

        // A chat that went quiet becomes a conversation too (rules now; the model later).
        server = null
        db.messages().insert(ChatMessage(threadId = 3, role = Role.User, text = "Planning the garden: tulips by the fence", createdAt = clock))
        db.messages().insert(ChatMessage(threadId = 3, role = Role.Assistant, text = "Tulips like sun.", createdAt = clock))
        clock += Episodes.IDLE_MS + 60_000
        memory.learnNow()
        assertEquals(2, memory.episodes.value.size)
        val c = memory.context("What did I say about the garden?", 4096)
        File(out, "past-conversation.txt").writeText(c.turn)
        assertTrue(c.turn, c.turn.contains("From past conversations") && c.turn.contains("tulips by the fence"))

        // Clear chats: what was kept from chats goes too.
        val kept = memory.episodes.value.size
        memory.forgetChats()
        assertEquals(kept - 1, memory.episodes.value.size)
        assertTrue(memory.episodes.value.none { it.kind == "chat" })
        assertFalse(memory.context("What did I say about the garden?", 4096).turn.contains("tulips"))
        // Deleting a conversation: no longer recalled.
        assertTrue(memory.context("What did I say about Kenji?", 4096).turn.contains("Kenji"))
        memory.deleteEpisode(memory.episodes.value.single().id)
        assertFalse(memory.context("What did I say about Kenji?", 4096).turn.contains("From past conversations"))
    }

    @Test fun oldChatsAreNotReadAfterTheUpdate() = runBlocking {
        db.messages().insert(ChatMessage(threadId = 1, role = Role.User, text = "Said long ago, maybe with learning off: I love jazz", createdAt = clock))
        // A store from before conversations were kept: its marks have no "episode".
        store().learnNow()
        val row = db.learned().ofType(MemoryStore.META).single()
        val json = org.json.JSONObject(sealer.open(row.sealed)!!).apply { remove("episode") }.toString()
        db.learned().update(row.copy(sealed = sealer.seal(json)))
        clock += Episodes.IDLE_MS + 60_000
        val memory = store()
        memory.learnNow()
        assertTrue(memory.episodes.value.isEmpty())
    }

    @Test fun learningOffStopsAllExtraction() = runBlocking {
        learning = false
        val model = ScriptedModel("person: Ana is your sister\nsummary: x")
        server = model
        val memory = store()
        memory.talkEnded(listOf("My sister Ana is visiting next week" to "Lovely."))
        memory.heard("My sister Ana is visiting next week")
        db.messages().insert(ChatMessage(threadId = 1, role = Role.User, text = "I live in Leeds and my boss is Tom", createdAt = clock))
        db.entries().insert(Entry(kind = EntryKind.Note, title = "Me", body = "I work at Northwind.", createdAt = clock, updatedAt = clock))
        clock += Episodes.IDLE_MS + 60_000
        assertEquals(0, memory.learnNow())
        memory.chatEnded(1)
        Thread.sleep(200)
        assertEquals("Learning is off", memory.nightly { model })
        assertTrue(memory.command("Remember that I'm vegetarian")!!.startsWith("Learning is off"))
        assertTrue(memory.facts.value.isEmpty())
        assertTrue(memory.episodes.value.isEmpty())
        assertTrue(db.learned().ofType(MemoryStore.EPISODE).isEmpty())
        assertTrue(db.learned().ofType(MemoryStore.SAID).isEmpty())
        assertTrue("no model was asked", model.prompts.isEmpty())
        // Turned on again, what was said while it was off is not learned later.
        learning = true
        clock += 60_000
        memory.learnNow()
        assertTrue(memory.facts.value.isEmpty())
        assertTrue(memory.episodes.value.isEmpty())
    }
}
