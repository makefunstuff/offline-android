package com.lfmpixel.lfm

import androidx.test.core.app.ApplicationProvider
import com.lfmpixel.lfm.data.EntryKind
import com.lfmpixel.lfm.edge.ChatPrompt
import com.lfmpixel.lfm.llm.PromptMessage
import com.lfmpixel.lfm.llm.ToolCall
import com.lfmpixel.lfm.net.NetInfo
import com.lfmpixel.lfm.net.SearchHit
import com.lfmpixel.lfm.settings.SearchMode
import com.lfmpixel.lfm.settings.ToolPolicy
import com.lfmpixel.lfm.voice.PhoneLookup
import com.lfmpixel.lfm.voice.SpeechFormat
import com.lfmpixel.lfm.voice.SpeechLanguage
import com.lfmpixel.lfm.voice.TalkRounds
import com.lfmpixel.lfm.voice.TalkSearch
import com.lfmpixel.lfm.voice.TalkToolBox
import com.lfmpixel.lfm.voice.TalkToolTurn
import com.lfmpixel.lfm.voice.TalkWeb
import com.lfmpixel.lfm.voice.WebNeed
import com.lfmpixel.lfm.voice.YesNo
import com.lfmpixel.lfm.web.NetClient
import com.lfmpixel.lfm.web.SearchNet
import com.lfmpixel.lfm.web.WebSearch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * LFMP-30: Talk looks things up on the web. A spoken question that needs fresh facts searches (through a fake network)
 * and the answer is written from the results, on each path: the server's answer model with the tools (LFM3-1.7B's and
 * Qwen's way of writing a call), and Omni on the phone, which has none. Ask first is asked out loud; Offline is calm.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = LfmApp::class)
class TalkWebTest {
    private lateinit var g: AppGraph

    /** The search provider: what it was asked, and one result about Leeds' weather. */
    private class FakeNet : SearchNet {
        var offline = false
        val asked = mutableListOf<String>()
        override suspend fun state() = if (offline) NetClient.State.Offline else NetClient.State.On
        override suspend fun info() = NetInfo(ready = true, provider = "your SearXNG", wikipedia = false, proxy = false)
        override suspend fun search(query: String): NetClient.Results {
            asked += query
            return NetClient.Results("your SearXNG", listOf(
                SearchHit("Leeds weather - Met Office", "https://www.metoffice.gov.uk/leeds", "Tomorrow: 14°C, cloudy, light rain after 4pm.", "searxng"),
                SearchHit("Leeds forecast", "https://www.bbc.co.uk/weather/leeds", "Cloudy with a high of 14°C.", "searxng")))
        }
        override suspend fun fetch(url: String, label: String) = error("snippets only")
    }

    private val net = FakeNet()
    private lateinit var web: WebSearch
    private lateinit var box: TalkToolBox

    @Before fun setUp() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<LfmApp>()
        g = app.graph
        g.settings.setNetEnabled(true)
        g.settings.setSearchMode(SearchMode.Automatic)
        g.settings.setSnippetsOnly(true)
        g.settings.setWarnPersonal(false)
        web = WebSearch(g.db, g.settings, net, null)
        box = TalkToolBox({ g.tools.specsJson() }, web) { call, after, ask -> g.assistant.execute(call, "voice", after, ask) }
    }

    /** A server model: it calls web_search the first time, then answers from what the tool gave it. */
    private class ServerModel(val call: String) {
        val prompts = mutableListOf<String>()
        fun generate(prompt: String, max: Int): Flow<String> {
            prompts += prompt
            val result = Regex("""Tomorrow: (14°C, cloudy)""").find(prompt)?.groupValues?.get(1)
            return if (result == null) flowOf(call) else flowOf("Tomorrow it's ", "$result in Leeds [1].")
        }
    }

    private fun serverTurn(model: ServerModel, said: String, asks: MutableList<String> = mutableListOf(), yes: Boolean = true): Triple<String, List<TalkSearch>, TalkRounds> = runBlocking {
        val words = StringBuilder()
        val found = mutableListOf<TalkSearch>()
        val turn = TalkToolTurn(ask = { q -> asks += q; yes }, looking = { if (words.isBlank()) words.append(TalkWeb.LOOKING + " ") }, onSearch = { found += it })
        val rounds = TalkRounds(model::generate, { false }, { words.append(it) })
        val msgs = mutableListOf(PromptMessage(PromptMessage.Role.User, SpeechFormat.talkUser(said, null, needsWeb = box.web() && WebNeed.needs(said))))
        rounds.answer(null, SpeechFormat.talkToolSystem(SpeechLanguage.EnglishUK, web = box.web()), box, turn, msgs)
        Triple(words.toString(), found, rounds)
    }

    @Test fun serverLfmSearchesAndAnswersFromTheResult() {
        val model = ServerModel(ChatPrompt.rawCall("web_search", mapOf("query" to "Leeds weather tomorrow")))
        val (said, found, rounds) = serverTurn(model, "What's the weather in Leeds tomorrow?")
        assertTrue("web_search is offered", model.prompts[0].contains("web_search"))
        assertTrue("the turn is nudged to look it up", model.prompts[0].contains("look them up with web_search"))
        assertEquals(listOf("Leeds weather tomorrow"), net.asked)
        assertEquals("Let me check. Tomorrow it's 14°C, cloudy in Leeds [1].", said)
        assertEquals("Tomorrow it's 14°C, cloudy in Leeds.", SpeechFormat.clean(said.removePrefix(TalkWeb.LOOKING).trim()))
        assertEquals(1, rounds.ran)
        assertTrue("asked for a spoken answer", model.prompts[1].contains("Say the gist out loud"))
        val s = found.single()
        assertEquals("Leeds weather tomorrow", s.query)
        assertEquals(listOf("metoffice.gov.uk", "bbc.co.uk"), s.sources.map { it.host })
        runBlocking { assertEquals("Leeds weather tomorrow", g.db.searches().get(s.searchId)!!.query) }
    }

    @Test fun serverQwenAsksFirstOutLoudThenSearches() = runBlocking {
        g.settings.setSearchMode(SearchMode.AskFirst)
        // Qwen's calls come through the gateway's chat stream as OpenAI tool_calls; ChatStream hands them over in LFM's form.
        val stream = com.lfmpixel.lfm.edge.ChatStream(thinking = false)
        fun ev(delta: String) = org.json.JSONObject("""{"choices":[{"index":0,"delta":$delta}]}""")
        val raw = stream.feed(ev("""{"role":"assistant","content":null}""")) +
            stream.feed(ev("""{"tool_calls":[{"index":0,"id":"c1","type":"function","function":{"name":"web_search","arguments":"{\"query\":"}}]}""")) +
            stream.feed(ev("""{"tool_calls":[{"index":0,"function":{"arguments":"\"Leeds weather tomorrow\"}"}}]}""")) + stream.finish()
        val model = ServerModel(raw)
        val asks = mutableListOf<String>()
        val (said, found, _) = serverTurn(model, "Will it rain in Leeds tomorrow?", asks)
        assertEquals(listOf("Shall I look up “Leeds weather tomorrow” on the web?"), asks)
        assertNull("no card waits in the chat", web.pending.value)
        assertEquals(listOf("Leeds weather tomorrow"), net.asked)
        assertTrue(said, said.endsWith("Tomorrow it's 14°C, cloudy in Leeds [1]."))
        assertEquals(1, found.size)
    }

    @Test fun aSpokenNoSendsNothing() = runBlocking {
        g.settings.setSearchMode(SearchMode.AskFirst)
        val model = ServerModel(ChatPrompt.rawCall("web_search", mapOf("query" to "Leeds weather tomorrow")))
        val asks = mutableListOf<String>()
        val (_, found, _) = serverTurn(model, "What's the weather in Leeds tomorrow?", asks, yes = false)
        assertEquals(1, asks.size)
        assertTrue("nothing was sent", net.asked.isEmpty())
        assertTrue(found.isEmpty())
        assertTrue(model.prompts.last().contains("The owner chose not to search."))
    }

    @Test fun phoneOmniLooksItUpFirstAndReadsTheResult() = runBlocking {
        val lookup = PhoneLookup(web)
        var looked = 0
        val found = mutableListOf<TalkSearch>()
        val turn = TalkToolTurn(ask = { error("Automatic: no question") }, looking = { looked++ }, onSearch = { found += it })
        val said = "Can you tell me what the weather's like in Leeds tomorrow?"
        val plan = lookup.plan(said, turn) as PhoneLookup.Read
        assertEquals("“Let me check.” once, as it searched", 1, looked)
        assertEquals(listOf("what the weather's like in Leeds tomorrow"), net.asked)
        assertTrue(turn.afterWebText)
        assertEquals(found.single(), plan.search)
        // What Omni reads for its answer: the turn, then the results' gist.
        val history = SpeechFormat.talkHistory("You are Wren.", emptyList(), said + plan.note)
        assertTrue(history, history.contains("Tomorrow: 14°C, cloudy, light rain after 4pm."))
        assertTrue(history.contains("Answer from these results"))
        // Something it knows, or the owner's own things: no search.
        assertEquals(PhoneLookup.None, lookup.plan("Tell me a joke", turn))
        assertEquals(PhoneLookup.None, lookup.plan("Remind me to buy milk tomorrow", turn))
        assertEquals(1, net.asked.size)
    }

    @Test fun offlineIsACalmAnswerOnEachPath() = runBlocking {
        net.offline = true
        var looked = 0
        val turn = TalkToolTurn(ask = { error("nothing to ask") }, looking = { looked++ })
        val plan = PhoneLookup(web).plan("What's the weather in Leeds tomorrow?", turn)
        assertEquals(PhoneLookup.Say(TalkWeb.OFFLINE), plan)
        assertTrue(TalkWeb.OFFLINE.startsWith("I'm offline"))
        // The server's model is not offered the web; asked anyway, it is told Wren is offline.
        assertFalse(box.web())
        assertFalse(box.specs().any { it.contains("\"web_search\"") })
        val r = box.run(ToolCall("web_search", mapOf("query" to "Leeds weather")), turn)
        assertFalse(r.ok)
        assertTrue(r.forModel, r.forModel.contains("offline"))
        assertEquals(0, looked)
        assertTrue(net.asked.isEmpty())
    }

    @Test fun askPolicyIsASpokenYesOrNo() = runBlocking {
        g.settings.setToolPolicy("add_note", ToolPolicy.Ask)
        val asks = mutableListOf<String>()
        var yes = false
        val turn = TalkToolTurn(ask = { q -> asks += q; yes })
        val call = ToolCall("add_note", mapOf("text" to "Bring the umbrella"))
        val no = box.run(call, turn)
        assertEquals("Cancelled", no.cardLabel)
        assertNull("no sheet waits for a tap", g.assistant.pendingConfirm.value)
        assertEquals(listOf("Add note: Bring the umbrella?"), asks)
        yes = true
        assertTrue(box.run(call, turn).ok)
        assertTrue(g.db.entries().byKind(EntryKind.Note).first().any { it.body.contains("Bring the umbrella") || it.title.contains("Bring the umbrella") })
        g.settings.setToolPolicy("add_note", ToolPolicy.Do)
        Unit
    }

    @Test fun afterWebTextActionsAskOutLoud() = runBlocking {
        val asks = mutableListOf<String>()
        val turn = TalkToolTurn(ask = { q -> asks += q; true })
        box.run(ToolCall("web_search", mapOf("query" to "Leeds weather")), turn)
        assertTrue(turn.afterWebText)
        assertTrue(asks.isEmpty())
        box.run(ToolCall("add_todo", mapOf("text" to "Buy an umbrella")), turn)
        assertEquals(listOf("Add to-do: Buy an umbrella?"), asks)
    }

    @Test fun whatNeedsTheWeb() {
        listOf("What's the weather in Leeds tomorrow?", "Is the Co-op on Kirkgate open now?", "Who won the match last night?",
            "What's the latest on the election?", "How much does a flight to Rome cost", "Who is the current prime minister?",
            "What are the opening hours of the library", "Any news today?", "Can you check the weather?", "Tell me the news",
            "Look up the weather in Leeds", "Search for flights to Rome", "Find out if the Co-op is open", "What's the weather near me?",
            "Who won the US Open?", "What time does the sun set today?", "When's the release date of the new phone?").forEach { assertTrue(it, WebNeed.needs(it)) }
        listOf("Remind me to buy milk tomorrow", "What's on my calendar today?", "How are you today?", "Tell me a joke",
            "What time is it?", "What's 12 times 7", "Add oat milk to the shopping list", "What's the date today?",
            // Statements, commands and the owner's own things never go to a search provider.
            "I have news, I got the job", "Text Sam my flight is delayed", "What's my flight number?", "The price was too high").forEach { assertFalse(it, WebNeed.needs(it)) }
        assertEquals("what's the weather in Leeds", WebNeed.query("Hey Wren, can you tell me what's the weather in Leeds?"))
    }

    @Test fun yesOrNo() {
        assertEquals(true, YesNo.parse("Yes please"))
        assertEquals(true, YesNo.parse("Yeah, go ahead."))
        assertEquals(false, YesNo.parse("No thanks"))
        assertEquals(false, YesNo.parse("Don't."))
        assertEquals(false, YesNo.parse("OK, actually no"))
        assertEquals(false, YesNo.parse("I'm not sure"))
        assertEquals(false, YesNo.parse("Not right now"))
        assertEquals(false, YesNo.parse("Okay, wait"))
        assertEquals(true, YesNo.parse("Sure, no problem"))
        assertEquals(true, YesNo.parse("Yeah, why not"))
        assertEquals(true, YesNo.parse("Yes, I don't mind"))
        assertNull(YesNo.parse("Hmm"))
        assertNull(YesNo.parse(""))
    }
}
