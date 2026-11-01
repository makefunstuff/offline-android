package com.lfmpixel.lfm.voice

import com.lfmpixel.lfm.data.SearchSource
import com.lfmpixel.lfm.llm.ModelOutput
import com.lfmpixel.lfm.llm.PromptFormat
import com.lfmpixel.lfm.llm.PromptMessage
import com.lfmpixel.lfm.llm.ToolCall
import com.lfmpixel.lfm.tools.Preview
import com.lfmpixel.lfm.tools.ToolResult
import com.lfmpixel.lfm.web.WebSearch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/** One web search a Talk turn made, under its caption: the words sent, where, and the numbered sources. */
data class TalkSearch(val searchId: Long, val label: String, val query: String, val sources: List<SearchSource>)

/**
 * One Talk turn's way of asking and of saying it is looking. [ask] says a question out loud and hears yes or no (no
 * one may be looking at the screen, so a card would wait forever); [looking] says "Let me check." as a search goes
 * out. [afterWebText] turns true once web text was read, so every later action and search asks first (spec section 4).
 * Once the owner said no to a search, the turn asks no more.
 */
class TalkToolTurn(
    val ask: suspend (String) -> Boolean,
    val looking: suspend () -> Unit = {},
    val onSearch: (TalkSearch) -> Unit = {},
    var afterWebText: Boolean = false,
) {
    var searchRefused = false
}

/**
 * The app's tools for Talk's answer model on the server (Qwen, or LFM3-1.7B): their specs, and a run that previews,
 * confirms out loud when the owner wants that, runs and logs each call, as in the chat (Assistant.execute).
 */
interface TalkTools {
    /** web_search is offered: Settings has web search on, and Wren is not Offline. */
    suspend fun web(): Boolean
    suspend fun specs(): List<String>
    suspend fun run(call: ToolCall, turn: TalkToolTurn): ToolResult
}

/** [TalkTools] from the app's registry ([appSpecs], [execute]) and web search ([web]). Set by AppGraph. */
class TalkToolBox(
    private val appSpecs: () -> List<String>,
    private val web: WebSearch?,
    private val execute: suspend (call: ToolCall, afterWebText: Boolean, ask: suspend (Preview) -> Boolean) -> ToolResult,
) : TalkTools {
    override suspend fun web() = web?.available() == true

    override suspend fun specs() = appSpecs() + listOfNotNull(if (web()) WebSearch.spec.toJson() else null)

    override suspend fun run(call: ToolCall, turn: TalkToolTurn): ToolResult {
        if (call.name != WebSearch.spec.name) return execute(call, turn.afterWebText) { p -> turn.ask(TalkWeb.question(p)) }
        val w = web ?: return ToolResult.error("web search is not available")
        if (turn.searchRefused) return ToolResult(false, "The owner said no to searching the web. Answer from what you know, and say it may be out of date.")
        val r = w.run(call.args["query"].orEmpty(), TalkWeb.THREAD, turn.afterWebText, turn.ask, turn.looking)
        val id = r.searchId
        if (r.cardLabel == WebSearch.CANCELLED) turn.searchRefused = true
        if (id != null) {
            turn.afterWebText = true
            turn.onSearch(TalkSearch(id, r.cardLabel, r.cardTitle, r.sources))
        }
        return ToolResult(id != null, r.forModel, r.cardLabel, r.cardTitle, r.cardDetail)
    }
}

/**
 * Edge mode's answer with the app's tools: up to three rounds of writing, running the calls it wrote and reading
 * their results back. [words] gets what is said as it comes: never a tool call, nor what follows a stray </think>
 * (LFM3-1.7B, after a tool's result, sometimes closes a thought it never opened and says it all again).
 */
class TalkRounds(
    private val generate: (prompt: String, maxTokens: Int) -> Flow<String>,
    private val stopped: () -> Boolean,
    private val words: suspend (String) -> Unit,
    /** Each tool's result, as it comes. */
    private val result: (ToolResult) -> Unit = {},
    /** Between rounds: the next words start a new sentence. */
    private val roundOver: () -> Unit = {},
) {
    /** Tool calls run so far. */
    var ran = 0
        private set

    /**
     * With [tools], the conversation [msgs] (ending with the owner's turn) is answered with them; without, the prompt
     * [plain] is answered in one round. Returns what this turn added (calls, results and the last answer) when a tool
     * ran, for the next turns' history: with only the words, Qwen took "Done, ticked off" as the way to answer and
     * stopped calling tools.
     */
    suspend fun answer(plain: String?, system: String, tools: TalkTools?, turn: TalkToolTurn, msgs: MutableList<PromptMessage>): List<PromptMessage>? {
        val specs = tools?.specs().orEmpty()
        val from = msgs.size
        for (round in 0 until 3) {
            val raw = StringBuilder()
            var cut = false
            val prompt = plain ?: PromptFormat.build(system, specs, msgs, thinking = false)
            generate(prompt, if (tools != null) 400 else 300).collect { piece ->
                if (stopped()) throw CancellationException("Stopped")
                raw.append(piece)
                // What is said and shown: the words before any tool call (calls come whole, at the end).
                if (cut) return@collect
                val stop = listOf("<tool_call>", "</think>").firstOrNull { it in piece }
                val said = if (stop != null) { cut = true; piece.substringBefore(stop) } else piece
                if (said.isNotEmpty()) words(said)
            }
            val out = ModelOutput.parse(raw.toString(), false)
            val answer = PromptMessage(PromptMessage.Role.Assistant, "<think></think>$raw")
            if (tools == null || out.toolCalls.isEmpty()) return if (tools != null && msgs.size > from) msgs.drop(from) + answer else null
            msgs += answer
            for (call in out.toolCalls) {
                if (stopped()) throw CancellationException("Stopped")
                val r = tools.run(call, turn)
                ran++
                result(r)
                msgs += PromptMessage(PromptMessage.Role.Tool, r.forModel)
            }
            roundOver()
        }
        return null
    }
}

/**
 * Omni on the phone has no tools: a turn that needs the web is looked up before it answers, and the results' gist goes
 * into its turn, so it answers from them out loud.
 */
class PhoneLookup(private val web: WebSearch?) {
    sealed interface Plan
    /** Nothing to look up: Omni answers as it would. */
    data object None : Plan
    /** Said as it is, instead of an answer (Offline). */
    data class Say(val text: String) : Plan
    /** Goes after what the owner said, for Omni to answer from; [search] when the web was searched. */
    data class Read(val note: String, val search: TalkSearch?) : Plan

    suspend fun plan(said: String, turn: TalkToolTurn): Plan {
        val w = web ?: return None
        if (!WebNeed.needs(said)) return None
        if (w.offline()) return Say(TalkWeb.OFFLINE)
        if (!w.available()) return Read("\n\n(Web search is off in Settings, so you can't look this up: if it needs today's facts, say you can't check.)", null)
        val r = w.run(WebNeed.query(said), TalkWeb.THREAD, turn.afterWebText, turn.ask, turn.looking)
        val id = r.searchId ?: return Read("\n\n(${r.forModel})", null)
        turn.afterWebText = true
        val search = TalkSearch(id, r.cardLabel, r.cardTitle, r.sources)
        turn.onSearch(search)
        if (r.sources.isEmpty()) return Read("\n\n(${r.forModel} Say you found nothing about it.)", search)
        return Read("\n\n" + r.gist + "\n\nAnswer from these results with the gist, in one or two short spoken sentences.", search)
    }
}

/** Talk's words around a search, and how a confirmation is put and heard. */
object TalkWeb {
    /** Said as a search goes out. */
    const val LOOKING = "Let me check."
    const val OFFLINE = "I'm offline right now, so I can't look that up. Turn Offline off in Settings when you'd like me to check."
    /** Talk's searches belong to no chat thread. */
    const val THREAD = 0L

    /** A confirmation sheet's question, out loud: "Delete this to-do: Oat milk?" */
    fun question(p: Preview): String {
        val what = p.fields.map { it.value.trim() }.filter { it.isNotEmpty() }.take(2).joinToString(", ")
        return p.title.trim().removeSuffix("?") + (if (what.isNotEmpty()) ": $what" else "") + "?"
    }
}

/**
 * Whether something said out loud needs the web: a question about news, weather, prices, scores, opening hours, or a
 * fact about the world now. The model decides on the server (it has web_search); this decides for Omni on the phone,
 * which has no tools, and nudges the server's model when it does. Only a question about the world counts: a statement,
 * a command, or anything about the owner ("I", "my") is never sent to a search provider.
 */
object WebNeed {
    private val opt = setOf(RegexOption.IGNORE_CASE)
    private val fresh = Regex("""\b(weather|forecast|temperature|rain|snow|news|headlines?|prices?|cost|costs|exchange rate|stocks?|""" +
        """share price|bitcoin|score|scores|won|win|results?|fixtures?|kick[- ]?off|opening (?:hours|times)|open|close|closes|""" +
        """traffic|flights?|delayed|election|polls?|release date|showtimes?)\b""", opt)
    private val now = Regex("""\b(latest|current|currently|right now|recent|recently|today|tonight|tomorrow|this (?:week|weekend|year)|yesterday|20[2-9]\d)\b""", opt)
    private val question = Regex("""^(?:who|whose|what|what's|whats|when|when's|where|which|how|is|are|was|were|did|does|do|will|has|have|any)\b""", opt)
    /** Asked as a request: "check the weather", "tell me the news", "look up flights to Rome". */
    private val request = Regex("""^(?:tell me(?: about)?|look up|look for|search(?: for| the web for| online for)?|find out(?: if| whether)?|check|google)\b[\s,]*""", opt)
    private val greeting = Regex("""^(?:(?:hey|hi|ok|okay|so|um|uh|wren|please|can you|could you|would you|will you|do you know|i want to know|i'd like to know)\b[\s,]*)+""", opt)
    /** The app's own things: the notebook and tools answer those. */
    private val mine = Regex("""\b(remind|reminders?|note|notes|notebook|to-?dos?|calendar|agenda|appointments?|alarms?|timers?|journal|diary|""" +
        """shopping list|list|battery|what(?:'s| is) on|the date|what date|today's date|what day|time is it|text|message|call|email|send|add|play)\b""", opt)
    /** About the owner; "near me" asks about the world around them. */
    private val about = Regex("""\b(you|your|i|i'm|i've|me|my|mine|we|our)\b""", opt)
    private val nearMe = Regex("""\b(?:near|around|close to) me\b""", opt)

    fun needs(said: String): Boolean {
        val greeted = said.trim().replace(greeting, "").trim()
        val asked = query(said)
        if (asked.isEmpty() || !(question.containsMatchIn(greeted) || request.containsMatchIn(greeted))) return false
        if (mine.containsMatchIn(asked) || about.containsMatchIn(asked.replace(nearMe, ""))) return false
        return fresh.containsMatchIn(asked) || now.containsMatchIn(asked)
    }

    /** What to search for: the question, without the words around a request. */
    fun query(said: String): String = said.trim().replace(greeting, "").trim().replace(request, "").trim().trimEnd('?', '.', '!')
}

/** A yes or no said out loud; null if it was neither. Anything unsure or with a "not" in it is no. */
object YesNo {
    private val opt = setOf(RegexOption.IGNORE_CASE)
    /** Yes, in words that also hold a "no" or a "not". */
    private val fine = Regex("""\b(?:no (?:problem|worries)|not a problem|why not|(?:i )?don't mind|don't worry)\b""", opt)
    private val no = Regex("""\b(no|nope|nah|not|don't|do not|dont|cancel|stop|never ?mind|leave it|skip it|wait|hold on|maybe|unsure|nein|non|nee|nie)\b|n't\b""", opt)
    private val yes = Regex("""\b(yes|yeah|yep|yup|ya|sure|ok|okay|please|go ahead|go on|do it|of course|absolutely|correct|right|fine|sounds good|ja|oui|si|sí|tak|sì)\b""", opt)

    fun parse(heard: String): Boolean? {
        val h = heard.replace(fine, " yes ")
        return when {
            no.containsMatchIn(h) -> false
            yes.containsMatchIn(h) -> true
            else -> null
        }
    }
}
