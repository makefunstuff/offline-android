package com.lfmpixel.lfm.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import com.lfmpixel.lfm.voice.SpeechLanguage
import com.lfmpixel.lfm.voice.SpeechVoice
import com.lfmpixel.lfm.llm.Backend
import com.lfmpixel.lfm.llm.BackendChoice
import com.lfmpixel.lfm.llm.BackendSpeed
import com.lfmpixel.lfm.llm.Backends

enum class SearchMode(val label: String) { Off("Off"), AskFirst("Ask first"), Automatic("Automatic") }

enum class ToolPolicy { Ask, Do }

/** What the lock covers: nothing, the whole app, or only the journal (spec section 3, "Optional lock"). */
enum class LockMode(val label: String) { Off("Off"), App("Whole app"), Journal("Journal only") }

/** What the assistant gesture (power-button hold, corner swipe) opens. */
enum class AssistOpens(val label: String) { Talk("Talk"), Capture("Voice capture") }

data class Prefs(
    val firstRunDone: Boolean = false,
    /** Typed chats think before answering; each chat can turn it off or on (Think, by the message box). */
    val thinking: Boolean = true,
    /** Tokens it may think for before it must answer: one of [THINK_BUDGETS]. */
    val thinkBudget: Int = DEFAULT_THINK_BUDGET,
    /** Chats whose Think was set by hand, by thread id; the others follow [thinking]. */
    val chatThinking: Map<Long, Boolean> = emptyMap(),
    val searchMode: SearchMode = SearchMode.AskFirst,
    val warnPersonalWords: Boolean = true,
    /** 0 is Auto: see [com.lfmpixel.lfm.llm.Threads.pick]. */
    val threads: Int = 0,
    val contextTokens: Int = 8192,
    /** Minutes idle before models are unloaded; 0 keeps them loaded. */
    val unloadAfterMinutes: Int = 5,
    /** The chat model's file name; empty means the one added last. */
    val chatModel: String = "",
    val toolPolicies: Map<String, ToolPolicy> = emptyMap(),
    /** False is Offline (Settings → Privacy): the network module refuses every request. Kept under its old key, so an update keeps it. */
    val netEnabled: Boolean = true,
    val snippetsOnly: Boolean = false,
    val downloadWifiOnly: Boolean = true,
    /** A small "28 tokens/s · first token 0.4 s" line under each answer. */
    val showSpeed: Boolean = true,
    val speechLanguage: SpeechLanguage = SpeechLanguage.EnglishUK,
    val speechVoice: SpeechVoice = SpeechVoice.Female,
    /** Talk answers out loud; off, it answers in text only (faster, silent). */
    val talkSpoken: Boolean = true,
    /** How fast this phone makes Omni's speech (real-time factor) and how long a spoken answer runs, as last seen. */
    val voiceRtf: Double = 1.0,
    val voiceUsualMs: Double = 6000.0,
    val assistOpens: AssistOpens = AssistOpens.Talk,
    val lockMode: LockMode = LockMode.Off,
    /** CPU, GPU, or Auto: per model, by [backendSpeeds]. */
    val backend: BackendChoice = BackendChoice.Auto,
    /** What the benchmark measured, per model file and backend. */
    val backendSpeeds: Map<String, Map<Backend, BackendSpeed>> = emptyMap(),
    /** Edge mode: chat, pictures and voice run on the owner's own server, through the network module. */
    val edgeOn: Boolean = false,
    /** The server's address on the home network or tailnet, like http://100.124.77.61:18448. */
    val edgeUrl: String = "",
    /** Its API key, encrypted with a key in Android's keystore (see EdgeKey); empty for none. */
    val edgeKeySealed: String = "",
    /** What the server benchmark measured last, "key=value;…" (see EdgeBench). */
    val edgeBench: String = "",
)

/** Thinking budgets offered in Settings, in tokens. */
val THINK_BUDGETS = listOf(256, 512, 1024, 2048)
const val DEFAULT_THINK_BUDGET = 1024

private val Context.store by preferencesDataStore("settings")

class Settings(private val context: Context) {
    private object K {
        val firstRun = booleanPreferencesKey("first_run_done")
        val thinking = booleanPreferencesKey("thinking")
        val thinkBudget = intPreferencesKey("think_budget")
        val chatThinking = stringPreferencesKey("chat_thinking") // "12=1;13=0"
        val search = stringPreferencesKey("search_mode")
        val warn = booleanPreferencesKey("warn_personal")
        // "threads" held 2, 4 or 6 from before Auto: a picked 2 or 4 is kept for writing; 6, the old default, becomes
        // Auto, which keeps a core free for the screen.
        val oldThreads = intPreferencesKey("threads")
        val threads = intPreferencesKey("threads_v2")
        val ctx = intPreferencesKey("context_tokens")
        val unload = intPreferencesKey("unload_minutes")
        val policies = stringPreferencesKey("tool_policies") // "name=Ask;name2=Do"
        val net = booleanPreferencesKey("net_enabled")
        val snippets = booleanPreferencesKey("snippets_only")
        val wifiOnly = booleanPreferencesKey("download_wifi_only")
        val chatModel = stringPreferencesKey("chat_model")
        val speed = booleanPreferencesKey("show_speed")
        val speechLanguage = stringPreferencesKey("speech_language")
        val speechVoice = stringPreferencesKey("speech_voice")
        val talkSpoken = booleanPreferencesKey("talk_spoken")
        val voiceRtf = doublePreferencesKey("voice_rtf")
        val voiceUsualMs = doublePreferencesKey("voice_usual_ms")
        val assistOpens = stringPreferencesKey("assist_opens")
        val lock = stringPreferencesKey("lock_mode")
        val backend = stringPreferencesKey("backend")
        val backendSpeeds = stringPreferencesKey("backend_speeds")
        val edgeOn = booleanPreferencesKey("edge_on")
        val edgeUrl = stringPreferencesKey("edge_url")
        val edgeKey = stringPreferencesKey("edge_key_sealed")
        val edgeBench = stringPreferencesKey("edge_bench")
    }

    val prefs: Flow<Prefs> = context.store.data.map { it.toPrefs() }

    suspend fun now(): Prefs = prefs.first()

    private fun Preferences.toPrefs() = Prefs(
        firstRunDone = this[K.firstRun] ?: false,
        thinking = this[K.thinking] ?: true,
        thinkBudget = this[K.thinkBudget] ?: DEFAULT_THINK_BUDGET,
        chatThinking = (this[K.chatThinking] ?: "").split(';').mapNotNull { p ->
            val (k, v) = p.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
            k.toLongOrNull()?.let { it to (v == "1") }
        }.toMap(),
        searchMode = this[K.search]?.let { runCatching { SearchMode.valueOf(it) }.getOrNull() } ?: SearchMode.AskFirst,
        warnPersonalWords = this[K.warn] ?: true,
        threads = this[K.threads] ?: this[K.oldThreads]?.takeIf { it in 1..5 } ?: 0,
        contextTokens = this[K.ctx] ?: 8192,
        unloadAfterMinutes = this[K.unload] ?: 5,
        toolPolicies = (this[K.policies] ?: "").split(';').mapNotNull { p ->
            val (k, v) = p.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
            runCatching { k to ToolPolicy.valueOf(v) }.getOrNull()
        }.toMap(),
        netEnabled = this[K.net] ?: true,
        snippetsOnly = this[K.snippets] ?: false,
        downloadWifiOnly = this[K.wifiOnly] ?: true,
        chatModel = this[K.chatModel] ?: "",
        showSpeed = this[K.speed] ?: true,
        speechLanguage = this[K.speechLanguage]?.let { runCatching { SpeechLanguage.valueOf(it) }.getOrNull() } ?: SpeechLanguage.EnglishUK,
        speechVoice = this[K.speechVoice]?.let { runCatching { SpeechVoice.valueOf(it) }.getOrNull() } ?: SpeechVoice.Female,
        talkSpoken = this[K.talkSpoken] ?: true,
        voiceRtf = this[K.voiceRtf] ?: 1.0,
        voiceUsualMs = this[K.voiceUsualMs] ?: 6000.0,
        assistOpens = this[K.assistOpens]?.let { runCatching { AssistOpens.valueOf(it) }.getOrNull() } ?: AssistOpens.Talk,
        lockMode = this[K.lock]?.let { runCatching { LockMode.valueOf(it) }.getOrNull() } ?: LockMode.Off,
        backend = this[K.backend]?.let { runCatching { BackendChoice.valueOf(it) }.getOrNull() } ?: BackendChoice.Auto,
        backendSpeeds = Backends.decode(this[K.backendSpeeds] ?: ""),
        edgeOn = this[K.edgeOn] ?: false,
        edgeUrl = this[K.edgeUrl] ?: "",
        edgeKeySealed = this[K.edgeKey] ?: "",
        edgeBench = this[K.edgeBench] ?: "",
    )

    suspend fun setFirstRunDone() = context.store.edit { it[K.firstRun] = true }
    suspend fun setThinking(on: Boolean) = context.store.edit { it[K.thinking] = on }
    suspend fun setThinkBudget(tokens: Int) = context.store.edit { it[K.thinkBudget] = tokens }
    /** Think on or off for one chat; the last 50 chats set by hand are kept. */
    suspend fun setChatThinking(thread: Long, on: Boolean) = context.store.edit {
        val list = (it.toPrefs().chatThinking - thread).toList().takeLast(49) + (thread to on)
        it[K.chatThinking] = list.joinToString(";") { (k, v) -> "$k=${if (v) 1 else 0}" }
    }
    suspend fun setSearchMode(m: SearchMode) = context.store.edit { it[K.search] = m.name }
    suspend fun setWarnPersonal(on: Boolean) = context.store.edit { it[K.warn] = on }
    suspend fun setThreads(n: Int) = context.store.edit { it[K.threads] = n }
    suspend fun setUnloadMinutes(n: Int) = context.store.edit { it[K.unload] = n }
    suspend fun setNetEnabled(on: Boolean) = context.store.edit { it[K.net] = on }
    suspend fun setSnippetsOnly(on: Boolean) = context.store.edit { it[K.snippets] = on }
    suspend fun setDownloadWifiOnly(on: Boolean) = context.store.edit { it[K.wifiOnly] = on }
    suspend fun setSpeechLanguage(l: SpeechLanguage) = context.store.edit { it[K.speechLanguage] = l.name }
    suspend fun setSpeechVoice(v: SpeechVoice) = context.store.edit { it[K.speechVoice] = v.name }
    suspend fun setTalkSpoken(on: Boolean) = context.store.edit { it[K.talkSpoken] = on }
    suspend fun setVoicePace(rtf: Double, usualMs: Double) = context.store.edit { it[K.voiceRtf] = rtf; it[K.voiceUsualMs] = usualMs }
    suspend fun setAssistOpens(o: AssistOpens) = context.store.edit { it[K.assistOpens] = o.name }
    suspend fun setChatModel(fileName: String) = context.store.edit { it[K.chatModel] = fileName }
    suspend fun setLockMode(m: LockMode) = context.store.edit { it[K.lock] = m.name }
    suspend fun setBackend(b: BackendChoice) = context.store.edit { it[K.backend] = b.name }
    /** Keeps what the benchmark measured for [file]; other models' measurements stay. */
    suspend fun setBackendSpeeds(file: String, speeds: Map<Backend, BackendSpeed>) = context.store.edit {
        it[K.backendSpeeds] = Backends.encode(it.toPrefs().backendSpeeds + (file to speeds))
    }
    /** Chats are cleared: their ids come back, so their Think choices go too. */
    suspend fun clearChatThinking() = context.store.edit { it.remove(K.chatThinking) }
    suspend fun setEdgeOn(on: Boolean) = context.store.edit { it[K.edgeOn] = on }
    /** The server's address and its key, already sealed by EdgeKey. */
    suspend fun setEdgeServer(url: String, keySealed: String) = context.store.edit { it[K.edgeUrl] = url; it[K.edgeKey] = keySealed }
    suspend fun setEdgeBench(text: String) = context.store.edit { it[K.edgeBench] = text }
    /** Wipe: every setting back to how it was on first run. */
    suspend fun clear() = context.store.edit { it.clear() }
    suspend fun setShowSpeed(on: Boolean) = context.store.edit { it[K.speed] = on }
    suspend fun setToolPolicy(tool: String, policy: ToolPolicy) = context.store.edit {
        val map = it.toPrefs().toolPolicies + (tool to policy)
        it[K.policies] = map.entries.joinToString(";") { (k, v) -> "$k=${v.name}" }
    }
}
