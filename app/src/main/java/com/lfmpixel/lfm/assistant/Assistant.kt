package com.lfmpixel.lfm.assistant

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.lfmpixel.lfm.data.ActionRecord
import com.lfmpixel.lfm.data.ActionStatus
import com.lfmpixel.lfm.data.ChatMessage
import com.lfmpixel.lfm.data.LfmDatabase
import com.lfmpixel.lfm.data.Role
import com.lfmpixel.lfm.llm.Json
import com.lfmpixel.lfm.llm.LlmException
import com.lfmpixel.lfm.llm.ModelManager
import com.lfmpixel.lfm.llm.ModelOutput
import com.lfmpixel.lfm.llm.PromptFormat
import com.lfmpixel.lfm.llm.PromptMessage
import com.lfmpixel.lfm.llm.ToolCall
import com.lfmpixel.lfm.settings.Settings
import com.lfmpixel.lfm.tools.ExternalLauncher
import com.lfmpixel.lfm.tools.LocalCalendar
import com.lfmpixel.lfm.tools.PermissionGate
import com.lfmpixel.lfm.tools.PhoneControls
import com.lfmpixel.lfm.tools.Preview
import com.lfmpixel.lfm.tools.Reminders
import com.lfmpixel.lfm.tools.Tool
import com.lfmpixel.lfm.tools.ToolArgsException
import com.lfmpixel.lfm.tools.ToolRegistry
import com.lfmpixel.lfm.tools.ToolResult
import com.lfmpixel.lfm.see.SeeKind
import com.lfmpixel.lfm.see.SeeModel
import com.lfmpixel.lfm.see.SeeRequest
import com.lfmpixel.lfm.see.SeeRules
import com.lfmpixel.lfm.web.WebSearch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The answer being written right now. */
data class Streaming(val text: String, val thinking: Boolean, val note: String = "")

/** A message waiting its turn in the chat. [see] is set for a camera question (those are not kept on disk). */
data class Queued(val id: Long, val text: String, val voiceSeconds: Int = 0, val source: String = "typed", val see: SeeRequest? = null)

/** Typed queued messages in a small JSON file, so they survive the app being closed. */
object QueueStore {
    fun read(f: java.io.File): List<Queued> = runCatching {
        val a = org.json.JSONArray(f.readText())
        (0 until a.length()).map { i -> a.getJSONObject(i).let { Queued(it.getLong("id"), it.getString("text"), it.optInt("voice"), it.optString("source", "typed")) } }
    }.getOrDefault(emptyList())

    fun write(f: java.io.File, list: List<Queued>) {
        val a = org.json.JSONArray()
        list.filter { it.see == null }.forEach { a.put(org.json.JSONObject().put("id", it.id).put("text", it.text).put("voice", it.voiceSeconds).put("source", it.source)) }
        runCatching { f.writeText(a.toString()) }
    }
}

/** An action waiting for the owner's tap on the confirmation sheet. */
class ConfirmRequest(
    val tool: Tool,
    val preview: Preview,
    val args: Map<String, String>,
    val afterWebText: Boolean,
    internal val answer: CompletableDeferred<Map<String, String>?>,
) {
    fun confirm(edited: Map<String, String> = args) = answer.complete(edited)
    fun cancel() = answer.complete(null)
}

/**
 * The assistant loop: the 1.7B model reads the chat and the tool list, writes an answer or tool calls; each
 * call is previewed, confirmed when the owner wants that, run, logged in the action history and fed back.
 */
class Assistant(
    private val context: Context,
    private val db: LfmDatabase,
    private val models: ModelManager,
    private val settings: Settings,
    val registry: ToolRegistry,
    private val reminders: Reminders,
    private val calendar: LocalCalendar,
    private val controls: PhoneControls,
    private val launcher: ExternalLauncher,
    private val scope: CoroutineScope,
    val web: WebSearch? = null,
) {
    var permissionGate: PermissionGate? = null
    private val zone get() = ZoneId.systemDefault()

    private val _thread = MutableStateFlow(1L)
    val threadId: StateFlow<Long> = _thread
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val messages: Flow<List<ChatMessage>> = _thread.flatMapLatest { db.messages().thread(it) }

    val streaming = MutableStateFlow<Streaming?>(null)
    /** A turn is running or messages are waiting. */
    val busy = MutableStateFlow(false)
    /** A turn is running right now: what Stop ends. */
    val working = MutableStateFlow(false)
    val pendingConfirm = MutableStateFlow<ConfirmRequest?>(null)
    private var job: Job? = null

    /**
     * Messages waiting their turn, in order. They are shown in the chat as queued, can be edited, moved or removed,
     * run in whichever chat is open when their turn comes (so New chat keeps them), and typed ones are kept on disk.
     */
    val queue = MutableStateFlow<List<Queued>>(emptyList())
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val queueFile = java.io.File(context.filesDir, "queue.json")
    private var nextId = 1L
    private var threadSet = false
    // The queue only runs once the app is on screen: messages kept from an earlier run never run unattended
    // (a reminder or a reboot can start the process with no one looking).
    private val shown = CompletableDeferred<Unit>()
    /** A queued message being edited: it and those after it wait until the edit is done. */
    @Volatile private var held: Long? = null

    init {
        queue.value = QueueStore.read(queueFile).also { list -> nextId = (list.maxOfOrNull { it.id } ?: 0) + 1 }
        scope.launch {
            val last = db.messages().lastThread()
            if (!threadSet) _thread.value = last
            shown.await()
            while (true) {
                var taken: Queued? = null
                mutate(wakeWorker = false) { list -> taken = list.firstOrNull()?.takeIf { it.id != held }; if (taken != null) list.drop(1) else list }
                val next = taken
                if (next == null) {
                    busy.value = queue.value.isNotEmpty()
                    wake.receive()
                    continue
                }
                busy.value = true
                working.value = true
                job = scope.launch { handle(next) }
                job?.join()
                working.value = false
            }
        }
    }

    private fun mutate(wakeWorker: Boolean = true, change: (List<Queued>) -> List<Queued>) = synchronized(queue) {
        val old = queue.value
        val list = change(old)
        if (list == old) return@synchronized
        queue.value = list
        busy.value = working.value || list.isNotEmpty()
        QueueStore.write(queueFile, list)
        if (wakeWorker) wake.trySend(Unit)
    }

    /** The app is on screen: queued messages may run. */
    fun onShown() { shown.complete(Unit) }

    /** Holds a queued message while the owner edits it; null lets the queue go on. */
    fun hold(id: Long?) { held = id; wake.trySend(Unit) }

    /** Clear chats: messages still waiting go too, with their file. */
    fun clearQueue() = mutate { emptyList() }

    fun newChat() {
        stop()
        // Messages still waiting are not dropped: they run in the new chat.
        threadSet = true
        _thread.value = _thread.value + 1
    }

    /** Stops the turn being worked on; queued messages still run after it. */
    fun stop() {
        job?.cancel()
        models.cancelPictures()
        pendingConfirm.value?.cancel()
        web?.pending?.value?.cancel()
    }

    fun send(text: String, voiceSeconds: Int = 0, source: String = "typed") {
        val clean = text.trim()
        if (clean.isEmpty()) return
        mutate { it + Queued(nextId++, clean, voiceSeconds, source) }
    }

    /** Asks about a photo, a clip or a document: the picture goes to Omni or VL, in this process. */
    fun ask(req: SeeRequest) {
        mutate { it + Queued(nextId++, req.question, 0, "camera", req) }
    }

    fun editQueued(id: Long, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) removeQueued(id) else mutate { list -> list.map { if (it.id == id) it.copy(text = clean) else it } }
    }

    fun removeQueued(id: Long) = mutate { list -> list.filter { it.id != id } }

    /** Moves a queued message [by] places (negative is earlier). */
    fun moveQueued(id: Long, by: Int) = mutate { old ->
        val list = old.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) list.add((i + by).coerceIn(0, list.lastIndex), list.removeAt(i))
        list
    }

    fun sendNext(id: Long) = moveQueued(id, -queue.value.size)

    private suspend fun handle(input: Queued) {
        try {
            val see = input.see
            db.messages().insert(ChatMessage(threadId = _thread.value, role = Role.User, text = input.text, voiceSeconds = input.voiceSeconds,
                media = see?.kind?.name.orEmpty(), mediaPath = see?.thumbPath.orEmpty(), mediaLabel = see?.label.orEmpty()))
            when {
                see != null -> look(see)
                !models.hasText() -> sortWithoutModel(input.text, input.source)
                else -> converse(input.source)
            }
        } catch (e: LlmException) {
            notice(e.message ?: "The model stopped")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            notice("Something went wrong: ${e.message}")
        } finally {
            streaming.value = null
            // Idle time counts from the end of a turn, not from when the model was fetched.
            models.keepAlive()
        }
    }

    private suspend fun notice(text: String) {
        db.messages().insert(ChatMessage(threadId = _thread.value, role = Role.Notice, text = text))
    }

    /** No model yet: put the capture where simple rules say, and say so. */
    private suspend fun sortWithoutModel(text: String, source: String) {
        var call = CaptureSorter.sort(text)
        var result = execute(call, source, afterWebText = false)
        if (!result.ok && result.cardLabel != "Cancelled" && call.name != "add_note") {
            call = ToolCall("add_note", mapOf("text" to text))
            result = execute(call, source, afterWebText = false)
        }
        toolCard(result)
        notice("No text model yet, so this was sorted by simple rules. Import LFM3-1.7B in Settings → Models to chat.")
    }

    private suspend fun toolCard(r: ToolResult) {
        db.messages().insert(ChatMessage(threadId = _thread.value, role = Role.Tool, text = "", raw = r.forModel,
            cardLabel = r.cardLabel, cardTitle = r.cardTitle, cardDetail = r.cardDetail, actionId = r.actionId))
    }

    private fun systemPrompt(webOn: Boolean): String {
        val tz = zone.id
        val net = if (webOn) "It can search the web with web_search: use it when a question needs current facts, prices, news or " +
            "anything you are unsure of; answer from your own knowledge otherwise. " else "with no internet access. "
        return "You are Inkling, a private assistant that runs entirely on the owner's phone" + (if (webOn) ". " else ", ") + net +
            "Answer briefly and plainly. When the owner asks you to remember, note, remind, add, set, text, call or change " +
            "something, call a tool. \"Remind me\" means set_reminder; use set_alarm only when they say alarm. Things to buy " +
            "are to-dos on the Shopping list. Work out dates and times from the time given with each message and write them " +
            "as local ISO time like 2026-10-30T18:00. After a tool runs, confirm in a few words. Time zone: $tz."
    }

    private val stamp = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH)

    private fun toPrompt(m: ChatMessage): PromptMessage? = when (m.role) {
        // The text model can't see the picture; it reads what was asked and, next, what Omni or VL answered.
        Role.User -> PromptMessage(PromptMessage.Role.User,
            (if (m.media.isNotEmpty()) "[${m.media.lowercase()} shown: ${m.mediaLabel}] " else "") + m.text + "\n\n(Now: " + Instant.ofEpochMilli(m.createdAt).atZone(zone).format(stamp) + ")")
        Role.Assistant -> PromptMessage(PromptMessage.Role.Assistant, m.raw)
        Role.Tool -> PromptMessage(PromptMessage.Role.Tool, m.raw)
        Role.Notice -> null
    }

    private suspend fun converse(source: String) {
        val prefs = settings.now()
        val webOn = web?.available() == true
        val tools = registry.specsJson() + listOfNotNull(if (webOn) WebSearch.spec.toJson() else null)
        var searchId: Long? = null
        // Thinking gets a budget; past it, the think block is closed and the model must answer.
        val thinkBudget = 384
        val answerBudget = 512
        val maxTokens = if (prefs.thinking) thinkBudget + answerBudget else answerBudget
        repeat(4) { round ->
            val thread = db.messages().threadNow(_thread.value)
            // Once web text is in the conversation, every action and every new query asks first (spec section 4).
            val afterWebText = thread.any { it.role == Role.Tool && (it.searchId ?: 0) > 0 }
            var history = thread.mapNotNull(::toPrompt)
            var prompt = PromptFormat.build(systemPrompt(webOn), tools, history, prefs.thinking)
            // Drop the oldest turns until the prompt leaves room for the answer.
            while (history.size > 1 && models.textSession().countTokens(prompt) > prefs.contextTokens - maxTokens - 64) {
                history = history.drop(1).dropWhile { it.role != PromptMessage.Role.User }.ifEmpty { history.takeLast(1) }
                prompt = PromptFormat.build(systemPrompt(webOn), tools, history, prefs.thinking)
            }
            val raw = StringBuilder()
            streaming.value = Streaming("", prefs.thinking)
            val onPiece: (String) -> Unit = { piece ->
                raw.append(piece)
                val partial = ModelOutput.parse(raw.toString(), prefs.thinking)
                streaming.value = Streaming(partial.answer.substringBefore("<tool_call>").trim(), partial.stillThinking)
            }
            val first = if (prefs.thinking) thinkBudget else answerBudget
            val session = models.textSession()
            session.generate(prompt, first).collect { onPiece(it) }
            if (prefs.thinking && session.stats().decodeTokens >= first) {
                // Out of thinking budget: close the think block if it is still open, then let the answer run.
                if (ModelOutput.parse(raw.toString(), true).stillThinking) raw.append("\n</think>\n")
                session.generate(prompt + raw, answerBudget).collect { onPiece(it) }
            }
            val out = ModelOutput.parse(raw.toString(), prefs.thinking)
            val st = session.stats()
            val meta = "LFM3-1.7B · on this phone · %.0f tokens/s".format(Locale.ENGLISH, st.decodeTps)
            val answerFrom = searchId.takeIf { out.toolCalls.isEmpty() && out.answer.isNotBlank() }
            db.messages().insert(ChatMessage(threadId = _thread.value, role = Role.Assistant, text = out.answer,
                raw = PromptFormat.assistantPrefix(prefs.thinking) + raw,
                meta = if (answerFrom != null) "Written on this phone from the web · %.0f tokens/s".format(Locale.ENGLISH, st.decodeTps) else meta,
                searchId = answerFrom))
            streaming.value = null
            if (answerFrom != null) db.searches().get(answerFrom)?.let { db.searches().update(it.copy(answer = out.answer)) }
            if (out.toolCalls.isEmpty()) {
                if (out.answer.isBlank() && round == 0) notice("The model gave no answer. Try again, or say it another way.")
                return
            }
            for (call in out.toolCalls) {
                if (call.name == WebSearch.spec.name) {
                    val r = web?.run(call.args["query"].orEmpty(), _thread.value, afterWebText)
                        ?: com.lfmpixel.lfm.web.SearchOutcome("Error: web search is not available.", null, "No web search", "", "")
                    if (r.searchId != null) searchId = r.searchId
                    // -1 marks a search card that has no search behind it (off, cancelled, failed).
                    db.messages().insert(ChatMessage(threadId = _thread.value, role = Role.Tool, text = "", raw = r.forModel,
                        cardLabel = r.cardLabel, cardTitle = r.cardTitle, cardDetail = r.cardDetail, searchId = r.searchId ?: -1))
                } else toolCard(execute(call, source, afterWebText))
            }
            if (round == 3) notice("Stopped after four rounds of tool calls.")
        }
    }

    /** Omni or VL looks at the pictures (or VL reads the pages) and answers in the chat. */
    private suspend fun look(req: SeeRequest) {
        val picturesNeeded = req.images.isNotEmpty() || req.pages.any { it.image != null }
        val model = SeeRules.pick(req.kind, models.hasVoice() && models.omniSees, models.hasVision(), models.hasText(), picturesNeeded)
            ?: throw LlmException(if (req.kind == SeeKind.Document) "Documents need LFM3-VL-2B. Get it in Settings → Models."
                else "Photos and video need LFM3-Omni-2B or LFM3-VL-2B. Get one in Settings → Models.")
        val started = System.nanoTime()
        streaming.value = Streaming("", false, if (req.kind == SeeKind.Document) "Reading…" else "Looking…")
        var session = when (model) {
            SeeModel.Omni -> models.omniSession()
            SeeModel.Vision -> models.visionSession()
            SeeModel.Text -> models.textSession()
        }
        // An Omni projector without vision is only known once loaded: then VL looks instead.
        if (model == SeeModel.Omni && !models.omniSees) {
            if (!models.hasVision()) throw LlmException("This Omni projector file has no vision. Get LFM3-VL-2B in Settings → Models.")
            session = models.visionSession()
        }
        val looker = if (session.path == models.visionPath()) SeeModel.Vision else model
        val raw = StringBuilder()
        val onPiece: (String) -> Unit = { raw.append(it); streaming.value = Streaming(SeeRules.clean(raw.toString()), false) }
        if (req.pages.isEmpty()) {
            session.see(req.images, SeeRules.prompt(req.kind, req.question, req.images.size, req.seconds), 512).collect(onPiece)
        } else {
            // Pages without a text layer (scans) are read one at a time, then the question is answered over all the text.
            val texts = req.pages.mapIndexed { i, p ->
                if (p.image == null) p.text else {
                    streaming.value = Streaming("", false, "Reading page ${i + 1} of ${req.pages.size}…")
                    models.keepAlive()
                    val sb = StringBuilder()
                    session.see(listOf(p.image), SeeRules.prompt(SeeKind.Document, SeeRules.READ_PAGE, 1), 1024, temp = 0f)
                        .collect { sb.append(it) }
                    SeeRules.clean(sb.toString())
                }
            }
            streaming.value = Streaming("", false, "Thinking about ${req.pages.size} ${if (req.pages.size == 1) "page" else "pages"}…")
            models.keepAlive()
            val ctx = if (looker == SeeModel.Vision) ModelManager.VISION_CONTEXT else settings.now().contextTokens
            // About 3 characters a token, leaving room for the answer.
            var text = SeeRules.documentText(texts, (ctx - 1200) * 3)
            var prompt = SeeRules.documentPrompt(text, req.question)
            while (session.countTokens(prompt) > ctx - 600 && text.length > 2000) {
                text = SeeRules.documentText(texts, text.length * 3 / 4)
                prompt = SeeRules.documentPrompt(text, req.question)
            }
            session.generate(prompt, 512, temp = 0.2f).collect(onPiece)
        }
        val answer = SeeRules.clean(raw.toString())
        streaming.value = null
        if (answer.isBlank()) { notice("The model gave no answer. Try again, or ask another way."); return }
        val secs = (System.nanoTime() - started) / 1e9
        val more = if (req.pagesSkipped > 0) " · first ${req.pages.size} pages" else ""
        db.messages().insert(ChatMessage(threadId = _thread.value, role = Role.Assistant, text = answer,
            raw = "<think></think>" + answer,
            meta = "%s · on this phone · %.0f s%s".format(Locale.ENGLISH, looker.label, secs, more)))
    }

    /** Previews, confirms if needed, runs and logs one call. */
    suspend fun execute(call: ToolCall, source: String, afterWebText: Boolean): ToolResult {
        val tool = registry[call.name] ?: return ToolResult.error("There is no tool called ${call.name}")
        val args = call.args + ("_source" to source)
        // Arguments are checked before asking for permissions, except where the preview itself
        // needs them (a contact's number): then ask and look again.
        var preview = try { tool.preview(args) } catch (e: ToolArgsException) { null }
        val missing = tool.permissions.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (preview == null && missing.isEmpty()) {
            return log(tool, args, ToolResult.error(argError(tool, args)), ActionStatus.Failed, false)
        }
        if (missing.isNotEmpty() && permissionGate?.ensure(missing) != true) {
            return log(tool, args, ToolResult(false, "The owner did not allow the permission this needs.",
                "Permission needed", tool.label, "Allow it in Android settings to use this"), ActionStatus.Failed, false)
        }
        if (preview == null) preview = try { tool.preview(args) } catch (e: ToolArgsException) {
            return log(tool, args, ToolResult.error(e.message ?: "Bad arguments"), ActionStatus.Failed, false)
        }
        var finalArgs = args
        val ask = registry.mustAsk(tool, settings.now().toolPolicies, afterWebText)
        if (ask) {
            val req = ConfirmRequest(tool, preview, args, afterWebText, CompletableDeferred())
            pendingConfirm.value = req
            val answer = try { req.answer.await() } finally { pendingConfirm.value = null }
            if (answer == null) {
                return log(tool, args, ToolResult(false, "The owner cancelled it.", "Cancelled", preview.title.removeSuffix("?")),
                    ActionStatus.Cancelled, true)
            }
            finalArgs = answer + ("_source" to source)
        }
        val result = try { tool.run(finalArgs) } catch (e: ToolArgsException) { ToolResult.error(e.message ?: "Bad arguments") }
        val status = when { !result.ok -> ActionStatus.Failed; result.opened -> ActionStatus.Opened; else -> ActionStatus.Done }
        return log(tool, finalArgs, result, status, ask)
    }

    private suspend fun argError(tool: Tool, args: Map<String, String>): String =
        try { tool.preview(args); "Bad arguments" } catch (e: ToolArgsException) { e.message ?: "Bad arguments" }

    private suspend fun log(tool: Tool, args: Map<String, String>, r: ToolResult, status: ActionStatus, asked: Boolean): ToolResult {
        if (tool.readOnly && r.ok) return r
        val argsJson = Json.obj(*args.filterKeys { !it.startsWith("_") }.map { it.key to it.value }.toTypedArray())
        val id = db.actions().insert(ActionRecord(tool = tool.name, title = r.cardLabel.ifEmpty { tool.label },
            detail = listOf(r.cardTitle, r.cardDetail).filter { it.isNotEmpty() }.joinToString(" · "),
            argsJson = argsJson, status = status, undo = if (status == ActionStatus.Done) r.undo else "", askedFirst = asked))
        return r.copy(actionId = id)
    }

    /** Puts things back as they were. Alarms and timers can't be undone: the Clock app owns them. */
    suspend fun undo(actionId: Long): Boolean {
        val rec = db.actions().get(actionId) ?: return false
        if (rec.status != ActionStatus.Done || rec.undo.isEmpty()) return false
        val (kind, value) = rec.undo.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val ok = when (kind) {
            "entry" -> {
                // Only undo what is still as the assistant made it: never delete the owner's later edits.
                val e = db.entries().get(value.toLong())
                when {
                    e == null -> true
                    e.done || e.updatedAt > e.createdAt + 1_000 -> { notice("Not undone: “${e.title}” was changed since. Delete it in the Library if you want."); return false }
                    else -> { db.entries().delete(e.id); true }
                }
            }
            "reminder" -> {
                val id = value.toLong()
                reminders.cancel(id)
                db.reminders().get(id)?.let { db.reminders().update(it.copy(cancelled = true)) }
                true
            }
            "event" -> runCatching { calendar.delete(value.toLong()) }.getOrDefault(false)
            "torch" -> controls.setTorch(value.toBoolean())
            "ringer" -> controls.setRingerMode(value.toInt())
            "dnd" -> controls.setDnd(value.toBoolean())
            else -> false
        }
        if (ok) db.actions().update(rec.copy(status = ActionStatus.Undone))
        return ok
    }

    /** Undo for an action the owner did themselves in the UI (no model), e.g. a capture from the Library. */
    fun runDirect(call: ToolCall, source: String, onDone: (ToolResult) -> Unit = {}) {
        scope.launch { onDone(execute(call, source, afterWebText = false)) }
    }
}
