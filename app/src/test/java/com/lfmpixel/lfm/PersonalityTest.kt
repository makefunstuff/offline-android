package com.lfmpixel.lfm

import androidx.test.core.app.ApplicationProvider
import com.lfmpixel.lfm.assistant.OfferFilter
import com.lfmpixel.lfm.assistant.Offers
import com.lfmpixel.lfm.assistant.Persona
import com.lfmpixel.lfm.edge.ChatPrompt
import com.lfmpixel.lfm.edge.EdgeModels
import com.lfmpixel.lfm.llm.LiteRtSession
import com.lfmpixel.lfm.llm.PromptMessage
import com.lfmpixel.lfm.memory.Recall
import com.lfmpixel.lfm.voice.SpeechChunker
import com.lfmpixel.lfm.voice.SpeechFormat
import com.lfmpixel.lfm.voice.SpeechLanguage
import com.lfmpixel.lfm.voice.Talk
import com.lfmpixel.lfm.voice.TalkRounds
import com.lfmpixel.lfm.voice.TalkToolBox
import com.lfmpixel.lfm.voice.TalkToolTurn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * LFMP-33: Wren talks like a friend, not a reminder machine. One persona in chat, Ask and Talk; tools only when asked;
 * an unasked "want me to set a reminder?" is never said; spoken answers carry no Markdown; the prompt and the memory
 * fit each model. The scripted model below plays the old habit (an offer after every answer), so the Talk pipeline is
 * what is tested; the exchanges go to build/personality/exchanges.txt for the PR.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = LfmApp::class)
class PersonalityTest {
    private lateinit var g: AppGraph
    private lateinit var box: TalkToolBox
    private val out = File("build/personality").apply { mkdirs() }

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<LfmApp>()
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        g = app.graph
        box = TalkToolBox({ g.tools.specsJson() }, null) { call, after, ask -> g.assistant.execute(call, "voice", after, ask) }
    }

    /**
     * A server model with the old habit: [reply] for what was said, then "Want me to set a reminder…?" tacked on. A
     * "remind me" calls set_reminder first, then confirms.
     */
    private class HabitModel(val reply: String, val call: Pair<String, Map<String, String>>? = null, val offer: String? = "Want me to set a reminder for that?") {
        val prompts = mutableListOf<String>()
        fun generate(prompt: String, max: Int): Flow<String> {
            prompts += prompt
            val ran = prompt.contains("<tool_response>")
            if (call != null && !ran) return flowOf(ChatPrompt.rawCall(call.first, call.second))
            // Streamed in small pieces, as the server sends them.
            return flowOf(*Regex("(?<=\\s)").split(reply + (offer?.let { " $it" } ?: "")).filter { it.isNotEmpty() }.toTypedArray())
        }
    }

    private class Turn(val raw: String, val spoken: List<String>, val shown: String, val rounds: TalkRounds, val prompt: String)

    /** One Talk turn on the server as [Talk] runs it: rounds with the tools, the chunker, the offer filter, the caption. */
    private fun talk(model: HabitModel, said: String): Turn = runBlocking {
        val raw = StringBuilder()
        val chunker = SpeechChunker()
        val offers = OfferFilter(said)
        val spoken = mutableListOf<String>()
        val rounds = TalkRounds(model::generate, { false }, { w -> raw.append(w); spoken += chunker.feed(w).flatMap(offers::feed) })
        val msgs = mutableListOf(PromptMessage(PromptMessage.Role.User, SpeechFormat.talkUser(said, null)))
        rounds.answer(null, SpeechFormat.talkToolSystem(SpeechLanguage.EnglishUK), box, TalkToolTurn(ask = { true }), msgs)
        spoken += chunker.flush().flatMap(offers::feed) + offers.flush()
        Turn(raw.toString(), spoken, Offers.strip(raw.toString().trim(), said), rounds, model.prompts.first())
    }

    @Test fun aCasualTurnCallsNoToolAndOffersNoReminder() = runBlocking {
        val before = g.db.reminders().pending().size
        val t = talk(HabitModel("Long days like that are draining. What wore you out the most?"), "I'm so tired today")
        assertEquals("no tool ran", 0, t.rounds.ran)
        assertEquals(before, g.db.reminders().pending().size)
        val said = t.spoken.joinToString(" ")
        assertEquals("Long days like that are draining. What wore you out the most?", said)
        assertFalse(t.shown, t.shown.contains("remind", ignoreCase = true))
        // The prompt asks for no tool in ordinary talk and no offers; the old "keeps good time" / "offer one next step" is gone.
        assertTrue(t.prompt.contains(Persona.TOOLS))
        listOf("offer one next step", "keeps good time", "notification from this app", "The default for 'remind me'")
            .forEach { assertFalse(it, t.prompt.contains(it)) }
    }

    @Test fun remindMeAtFiveStillSetsOne() = runBlocking {
        val five = LocalDateTime.now().let { n -> n.withHour(17).withMinute(0).withSecond(0).withNano(0).let { if (it.isBefore(n)) it.plusDays(1) else it } }
        val at = five.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
        val t = talk(HabitModel("Done, I'll remind you at five to call mum.", "set_reminder" to mapOf("text" to "call mum", "at" to at), offer = null),
            "Remind me at 5 to call mum")
        assertEquals(1, t.rounds.ran)
        assertTrue(g.db.reminders().pending().any { it.text == "Call mum" })
        assertEquals("Done, I'll remind you at five to call mum.", t.spoken.joinToString(" "))
    }

    @Test fun aTimedTaskMayKeepItsOneOffer() {
        val said = "I have to call the plumber at six"
        val t = talk(HabitModel("Good luck with the plumber.", offer = "Shall I remind you at quarter to?"), said)
        assertEquals(listOf("Good luck with the plumber.", "Shall I remind you at quarter to?"), t.spoken)
        assertEquals(0, t.rounds.ran)
        // Something happening at a time is not a task: no offer for it.
        assertFalse(Offers.timed("My sister's coming over on Saturday"))
        assertTrue(Offers.timed("I need to ring the dentist tomorrow"))
    }

    @Test fun offersAreDroppedButFollowUpsStay() {
        listOf("Want me to set a reminder for that?", "Would you like me to remind you later?", "Shall I add that to your to-dos?",
            "Do you want a reminder?", "Should I set a notification for it?", "Also, want me to put it on your to-do list?",
            "If you like, I can set a reminder?", "Let me know if you'd like me to set a reminder.", "I can set a reminder if you like.",
            "If you want, I can set a reminder?", "Fancy a reminder?", "Want me to note that down?", "Shall I make a note of that?")
            .forEach { assertTrue(it, Offers.isOffer(it)) }
        listOf("What should I cook tonight?", "Should I go to the party tomorrow?", "My mum has to be at the airport at six")
            .forEach { assertFalse(it, Offers.timed(it)) }
        listOf("What wore you out the most?", "Do you want the short version or the long one?", "Shall I look it up?",
            "Would you like to hear a joke?", "Want to tell me about it?", "Should you notify your landlord first?",
            "Do you want to put it in the fridge overnight?", "Would you like to add that to the recipe?",
            "Can you hear the smoke alarm?", "Should I put it in the oven first?", "I can see why that's annoying.").forEach { assertFalse(it, Offers.isOffer(it)) }
        assertEquals("That sounds rough. Want to talk it through?",
            Offers.strip("That sounds rough. Want to talk it through? Would you like me to set a reminder to rest?", "work was awful"))
        // A one-sentence answer stays, whatever it is: there is nothing else to say.
        assertEquals("Want me to set a reminder?", Offers.strip("Want me to set a reminder?", "hmm"))
        // Piece by piece: a held opener that turns out not to be an offer is still said.
        val f = OfferFilter("tell me something")
        assertEquals(emptyList<String>(), f.feed("Would you like to hear about"))
        assertEquals(listOf("Would you like to hear about octopuses?"), f.feed("octopuses?"))
        assertEquals(emptyList<String>(), f.feed("Want me to set a reminder"))
        assertEquals(emptyList<String>(), f.feed("for that?"))
        assertEquals("something was said, so the offer stays dropped", emptyList<String>(), f.flush())
        // An answer that is only an offer is still said: silence would be worse.
        val lone = OfferFilter("I've got the dentist next week")
        assertEquals(emptyList<String>(), lone.feed("Want me to set a reminder for it?"))
        assertEquals(listOf("Want me to set a reminder for it?"), lone.flush())
        // An offer in the middle goes from the caption too, as from the speech.
        assertEquals("Nice. Is it a big one?", Offers.strip("Nice. Want me to set a reminder? Is it a big one?", "party on"))
        // Talk's own question after an unfinished sentence is not swallowed with it.
        val q = OfferFilter("add oat milk")
        assertEquals(emptyList<String>(), q.feed("Can do, adding it"))
        assertEquals(listOf("Can do, adding it"), q.release())
        assertEquals(listOf("Add to Shopping: Oat milk?"), q.feed("Add to Shopping: Oat milk?"))
    }

    @Test fun onePersonaInChatAskAndTalk() {
        assertTrue(SpeechFormat.talkToolSystem(SpeechLanguage.EnglishUK).contains(Persona.CORE))
        assertTrue(SpeechFormat.talkSystem(SpeechLanguage.EnglishUK, "Sunday", full = true).contains(Persona.CORE))
        // Omni on the phone: the short one, and it never promises an action it can't do.
        val phone = SpeechFormat.talkSystem(SpeechLanguage.EnglishUK, "Sunday")
        assertTrue(phone.contains(Persona.SHORT))
        assertTrue(phone.contains(Persona.NO_TOOLS))
        assertEquals(Persona.CORE, Persona.forContext(EdgeModels.ANSWER_CONTEXT))
        assertEquals(Persona.SHORT, Persona.forContext(4096))
        listOf("Great question", "flirty", "follow-up", "opinion", "never naggy").forEach { assertTrue(it, Persona.CORE.contains(it)) }
    }

    @Test fun spokenAnswersHaveNoMarkdown() {
        val written = "**Sure.** Here's the idea:\n\n## Plan\n- Stretch for *five* minutes\n- Then a `glass` of water [1]\n\nSee https://example.com."
        val chunker = SpeechChunker()
        val pieces = Regex("(?<=\\s)").split(written).flatMap { chunker.feed(it) } + chunker.flush()
        val said = pieces.joinToString(" ")
        listOf("*", "#", "`", "[1]", "http", "\n- ").forEach { assertFalse("$it in: $said", said.contains(it)) }
        assertTrue(said, said.contains("Stretch for five minutes."))
        assertTrue(Persona.spoken("British English").contains("No lists, Markdown"))
    }

    /** Persona, tools and the biggest memory each path asks for, against each model's window, with room for the answer. */
    @Test fun thePromptAndMemoryFitEachModel() {
        fun tokens(s: String) = LiteRtSession.estimateTokens(s)
        fun memory(contextTokens: Int) = Recall.budget(contextTokens).let { "x".repeat(it.profileChars + it.notesChars + 400) }
        val specs = (g.tools.specsJson() + com.lfmpixel.lfm.web.WebSearch.spec.toJson()).joinToString("\n")
        // Qwen (or LFM3) on the server in Talk: tools, the Talk memory budget, six exchanges, 400 tokens to answer.
        val server = SpeechFormat.talkToolSystem(SpeechLanguage.EnglishUK, web = true) + specs + memory(Talk.TALK_CONTEXT) + "y".repeat(6 * 400)
        assertTrue("server: ${tokens(server)}", tokens(server) + 400 <= EdgeModels.ANSWER_CONTEXT)
        // Omni on the phone: 4K, the short persona, the small profile, six short exchanges and 1200 steps to speak.
        val phone = SpeechFormat.talkSystem(SpeechLanguage.EnglishUK, "Sunday 1 November 2026") + memory(4096) + "y".repeat(6 * 200)
        assertTrue("phone: ${tokens(phone)}", tokens(phone) + 1200 + 64 < 4096)
        // The chat on the phone (LFM3, LiteRT-LM's 4096): the tool specs are most of it already (estimated high), so the
        // persona and its tool rule stay small.
        val chat = Persona.forContext(4096) + Persona.WRITTEN + Persona.TOOLS
        assertTrue("chat: ${tokens(chat)}", tokens(chat) < 350)
        File(out, "sizes.txt").writeText("server ${tokens(server)} of ${EdgeModels.ANSWER_CONTEXT}\nphone ${tokens(phone)} of 4096\nchat persona and tool rule ${tokens(chat)}\n")
    }

    /** Before and after, for the PR: what the old habit said, and what Talk says now. */
    @Test fun exchanges() {
        val five = LocalDateTime.now().plusDays(1).withHour(17).withMinute(0).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
        val script = listOf(
            "I'm so tired today" to HabitModel("Long days like that are draining. What wore you out the most?"),
            "What do you think of pineapple on pizza?" to HabitModel("Honestly, I'm for it: the sweet and salty thing works. Ham and pineapple or something braver?"),
            "I finally finished the boiler paperwork" to HabitModel("That's a weight off. How does it feel to have it done?", offer = "Would you like me to add a to-do to file it?"),
            "Tell me something interesting" to HabitModel("Octopuses have three hearts, and two of them stop when they swim. Want another one?"),
            "I can't get started on anything" to HabitModel("That stuck feeling is real. Want to pick one tiny thing together, or just talk for a minute?", offer = "Shall I set a reminder to try again in an hour?"),
            "My sister's coming over on Saturday" to HabitModel("Nice, is it a special occasion or just a catch-up?", offer = "Do you want a reminder for Saturday?"),
            "Should I learn Rust or Go?" to HabitModel("Go, if you want to ship something soon; Rust if you want the deeper challenge. What would you build first?"),
            "Remind me at 5 to call mum" to HabitModel("Done, I'll remind you at five to call mum.", "set_reminder" to mapOf("text" to "call mum", "at" to five), offer = null),
            "I need to ring the dentist tomorrow" to HabitModel("Good thinking, Mondays fill up fast.", offer = "Want me to remind you tomorrow morning?"),
        )
        val sb = StringBuilder()
        for ((said, model) in script) {
            val t = talk(model, said)
            sb.append("Owner: ").append(said).append('\n')
            sb.append("Before (model's words, said as they came): ").append(t.raw.trim()).append('\n')
            sb.append("After (said): ").append(t.spoken.joinToString(" "))
            if (t.rounds.ran > 0) sb.append("   [tool ran: set_reminder]")
            sb.append("\n\n")
            assertFalse(said, Offers.isOffer(t.spoken.last()) && !Offers.timed(said))
        }
        File(out, "exchanges.txt").writeText(sb.toString())
    }
}
