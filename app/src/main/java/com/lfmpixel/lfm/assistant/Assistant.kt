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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
data class Streaming(val text: String, val thinking: Boolean)

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
) {
    var permissionGate: PermissionGate? = null
    private val zone get() = ZoneId.systemDefault()

    private val _thread = MutableStateFlow(1L)
    val threadId: StateFlow<Long> = _thread
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val messages: Flow<List<ChatMessage>> = _thread.flatMapLatest { db.messages().thread(it) }

    val streaming = MutableStateFlow<Streaming?>(null)
    val busy = MutableStateFlow(false)
    val pendingConfirm = MutableStateFlow<ConfirmRequest?>(null)
    private var job: Job? = null

    init {
        scope.launch { _thread.value = db.messages().lastThread() }
    }

    fun newChat() {
        stop()
        _thread.value = _thread.value + 1
    }

    fun stop() {
        job?.cancel()
        pendingConfirm.value?.cancel()
    }

    fun send(text: String, voiceSeconds: Int = 0, source: String = "typed") {
        val clean = text.trim()
        if (clean.isEmpty() || busy.value) return
        busy.value = true
        job = scope.launch {
            try {
                db.messages().insert(ChatMessage(threadId = _thread.value, role = Role.User, text = clean, voiceSeconds = voiceSeconds))
                if (!models.hasText()) sortWithoutModel(clean, source) else converse(source)
            } catch (e: LlmException) {
                notice(e.message ?: "The model stopped")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                notice("Something went wrong: ${e.message}")
            } finally {
                streaming.value = null
                busy.value = false
            }
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

    private fun systemPrompt(): String {
        val tz = zone.id
        return "You are LFM, a private assistant that runs entirely on the owner's phone, with no internet access. " +
            "Answer briefly and plainly. When the owner asks you to remember, note, remind, add, set, text, call or change " +
            "something, call a tool. \"Remind me\" means set_reminder; use set_alarm only when they say alarm. Things to buy " +
            "are to-dos on the Shopping list. Work out dates and times from the time given with each message and write them " +
            "as local ISO time like 2026-10-30T18:00. After a tool runs, confirm in a few words. Time zone: $tz."
    }

    private val stamp = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH)

    private fun toPrompt(m: ChatMessage): PromptMessage? = when (m.role) {
        Role.User -> PromptMessage(PromptMessage.Role.User,
            m.text + "\n\n(Now: " + Instant.ofEpochMilli(m.createdAt).atZone(zone).format(stamp) + ")")
        Role.Assistant -> PromptMessage(PromptMessage.Role.Assistant, m.raw)
        Role.Tool -> PromptMessage(PromptMessage.Role.Tool, m.raw)
        Role.Notice -> null
    }

    private suspend fun converse(source: String) {
        val session = models.textSession()
        val prefs = settings.now()
        val tools = registry.specsJson()
        val maxTokens = if (prefs.thinking) 1536 else 512
        repeat(4) { round ->
            var history = db.messages().threadNow(_thread.value).mapNotNull(::toPrompt)
            var prompt = PromptFormat.build(systemPrompt(), tools, history, prefs.thinking)
            // Drop the oldest turns until the prompt leaves room for the answer.
            while (history.size > 1 && session.countTokens(prompt) > prefs.contextTokens - maxTokens - 64) {
                history = history.drop(1).dropWhile { it.role != PromptMessage.Role.User }.ifEmpty { history.takeLast(1) }
                prompt = PromptFormat.build(systemPrompt(), tools, history, prefs.thinking)
            }
            val raw = StringBuilder()
            streaming.value = Streaming("", prefs.thinking)
            session.generate(prompt, maxTokens).collect { piece ->
                raw.append(piece)
                val partial = ModelOutput.parse(raw.toString(), prefs.thinking)
                streaming.value = Streaming(partial.answer.substringBefore("<tool_call>").trim(), partial.stillThinking)
            }
            val out = ModelOutput.parse(raw.toString(), prefs.thinking)
            val st = session.stats()
            val meta = "LFM3-1.7B · on this phone · %.0f tokens/s".format(Locale.ENGLISH, st.decodeTps)
            db.messages().insert(ChatMessage(threadId = _thread.value, role = Role.Assistant, text = out.answer,
                raw = PromptFormat.assistantPrefix(prefs.thinking) + raw, meta = meta))
            streaming.value = null
            if (out.toolCalls.isEmpty()) return
            for (call in out.toolCalls) toolCard(execute(call, source, afterWebText = false))
            if (round == 3) notice("Stopped after four rounds of tool calls.")
        }
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
            "entry" -> { db.entries().delete(value.toLong()); true }
            "reminder" -> {
                val id = value.toLong()
                reminders.cancel(id)
                db.reminders().get(id)?.let { db.reminders().update(it.copy(cancelled = true)) }
                true
            }
            "event" -> calendar.delete(value.toLong())
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
