package com.lfmpixel.lfm

import android.app.Application
import android.content.Context
import com.lfmpixel.lfm.assistant.Assistant
import com.lfmpixel.lfm.data.LfmDatabase
import com.lfmpixel.lfm.llm.ModelManager
import com.lfmpixel.lfm.llm.ModelStore
import com.lfmpixel.lfm.settings.Settings
import com.lfmpixel.lfm.tools.Contacts
import com.lfmpixel.lfm.tools.ExternalLauncher
import com.lfmpixel.lfm.tools.LocalCalendar
import com.lfmpixel.lfm.tools.PhoneControls
import com.lfmpixel.lfm.tools.Reminders
import com.lfmpixel.lfm.tools.ToolEnv
import com.lfmpixel.lfm.tools.ToolRegistry
import com.lfmpixel.lfm.tools.defaultTools
import com.lfmpixel.lfm.see.MediaPrep
import com.lfmpixel.lfm.voice.OmniTranscriber
import com.lfmpixel.lfm.web.ModelDownloads
import com.lfmpixel.lfm.web.NetClient
import com.lfmpixel.lfm.web.WebSearch
import com.lfmpixel.lfm.voice.VoiceRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Everything the app is made of, wired by hand. */
class AppGraph(context: Context) {
    // A job that fails here is written to the crash log instead of closing the app (Crashes.keepRunning).
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + com.lfmpixel.lfm.crash.Crashes.keepRunning)
    val heat = com.lfmpixel.lfm.device.HeatWatch(context)
    val db = LfmDatabase.open(context)
    val settings = Settings(context)
    val models = ModelStore(context)
    val engine = ModelManager(models, settings, scope, java.io.File(context.cacheDir, "litert").apply { mkdirs() }, heat.heat)
    val reminders = Reminders(context)
    val focus = com.lfmpixel.lfm.assistant.Focus(context)
    val calendar = LocalCalendar(context)
    val contacts = Contacts(context)
    val controls = PhoneControls(context)
    val launcher = ExternalLauncher(context)
    // Late: the lock is made below; the journal is left out of library searches while it is locked.
    private val journalHidden: () -> Boolean = { lock.locks(com.lfmpixel.lfm.settings.LockMode.Journal) }
    /** Search by meaning: EmbeddingGemma 2's vectors of every entry, kept up to date in the background. */
    val semantic = com.lfmpixel.lfm.data.SemanticIndex(db, { engine.embedder() }, { models.embedder()?.file?.name }, heat.heat, scope)
        .also { it.start(models.installed) }
    val toolEnv = ToolEnv(db, reminders, calendar, contacts, controls, launcher, focus, hideJournal = { journalHidden() },
        search = { q, n -> semantic.search(q, n) })
    val tools = ToolRegistry(defaultTools(toolEnv), toolEnv)
    val net = NetClient(context, settings, db, scope)
    /** Edge mode: the owner's server, through the network module. */
    val edge = com.lfmpixel.lfm.edge.Edge(net, settings, scope).also { engine.edge = it }
    val web = WebSearch(db, settings, net, contacts)
    val downloads = ModelDownloads(context, models, net, settings, engine, scope)
    val assistant = Assistant(context, db, engine, settings, tools, reminders, calendar, controls, launcher, scope, web, heat.heat)
    val voice = VoiceRecorder(OmniTranscriber(engine))
    val talk = com.lfmpixel.lfm.voice.Talk(context, engine, settings, scope)
    val readAloud = com.lfmpixel.lfm.voice.ReadAloud(context, engine, settings, scope)
    val prep = MediaPrep(context)
    /** The small classifiers: on the server, else d1 on the phone; and Look (the camera described live). */
    val labels = com.lfmpixel.lfm.edge.Labels(edge, local = { engine.decide() })
    val look = com.lfmpixel.lfm.see.Look(edge, scope).also { l ->
        talk.seeing = { l.seeing() }
        talk.lookAnswer = { said ->
            if (!l.active) null
            else labels.intent(said)?.takeIf { it.intent == com.lfmpixel.lfm.edge.IntentLabel.LOOK }?.let { l.answer(said) }
        }
        // The same tools as the chat, web_search too; whatever must ask first is asked out loud.
        talk.tools = com.lfmpixel.lfm.voice.TalkToolBox({ tools.specsJson() }, web) { call, afterWebText, ask ->
            val t0 = System.nanoTime()
            assistant.execute(call, "voice", afterWebText, ask).also { r ->
                android.util.Log.i("lfm-talk", "tool %s %s in %.0f ms: %s".format(java.util.Locale.ENGLISH, call.name,
                    if (r.ok) "ok" else "failed", (System.nanoTime() - t0) / 1e6, r.cardLabel))
            }
        }
        talk.phoneLookup = com.lfmpixel.lfm.voice.PhoneLookup(web)
        assistant.seeing = { l.seeing() }
        assistant.labels = labels
    }
    val lock = com.lfmpixel.lfm.device.AppLock(context).also { l ->
        scope.launch {
            var first = true
            settings.prefs.collect { p -> if (first) { first = false; l.start(p.lockMode) } else l.mode = p.lockMode }
        }
    }
    /** Daylight's sounds and haptics; Settings → Soft sounds turns the sounds off. */
    val feel = com.lfmpixel.lfm.daylight.Feel(context, scope).also { f ->
        scope.launch { settings.prefs.collect { f.soundsOn = it.softSounds } }
    }
    /** What Wren learned (notebook-spec section 6): sealed in the notes' database, read into answers. */
    val memory = com.lfmpixel.lfm.memory.MemoryStore(db, com.lfmpixel.lfm.memory.KeystoreSealer, { engine.embedder() },
        { q, n -> semantic.search(q, n) }, journalLocked = { journalHidden() }, learning = { settings.now().learning }, scope = scope,
        // A finished conversation is read at once only on the owner's server; on the phone it waits for the night (charging).
        // Its own session, with no phone model behind it: if the server goes away, the conversation waits.
        server = { edge.takeIf { it.usable() }?.let { com.lfmpixel.lfm.edge.EdgeSession(it, com.lfmpixel.lfm.edge.EdgeModels.TEXT, null) } }).also { m ->
        m.start(db.messages().lastId())
        assistant.memory = m
        talk.memory = m
        labels.corrections = { if (settings.now().learning) m.corrections.value else emptyList() }
        toolEnv.learnedList = { title -> m.learnedList(title) }
        // Each Talk turn the owner said is kept, sealed, for the next pass; when Talk pauses or closes, the conversation
        // so far becomes an episode.
        scope.launch {
            val seen = HashSet<Long>()
            val conversation = mutableListOf<Pair<String, String>>()
            talk.state.collect { st ->
                st.turns.filter { it.done && it.id !in seen }.forEach {
                    seen += it.id; m.heard(it.you)
                    // Said while learning was off: never kept, even if it is turned on before Talk ends.
                    if (settings.now().learning) conversation += it.you to it.it
                }
                val over = st.phase == com.lfmpixel.lfm.voice.TalkPhase.Paused || st.phase == com.lfmpixel.lfm.voice.TalkPhase.Off ||
                    st.phase == com.lfmpixel.lfm.voice.TalkPhase.Failed || st.turns.isEmpty()
                if (over && conversation.isNotEmpty()) {
                    val turns = conversation.toList()
                    conversation.clear()
                    m.talkEnded(turns)
                }
            }
        }
        scope.launch { settings.prefs.map { it.learning }.distinctUntilChanged().collect { com.lfmpixel.lfm.memory.LearnJob.schedule(context, it) } }
    }
    val bench = com.lfmpixel.lfm.llm.Benchmark(engine, models, settings, scope)
    val edgeBench = com.lfmpixel.lfm.edge.EdgeBench(edge, settings, scope)
    val voiceBench = com.lfmpixel.lfm.voice.VoiceBench(engine, settings, talk, readAloud, scope)
}

/**
 * Delete everything (Settings → Privacy): every note, chat, reminder, search, log line and setting, pictures kept for
 * the chat, the queue, Wren's own calendar, and, if [models] is set, the model files. The app is then as on first
 * run. Nothing outside Wren is touched (alarms set in the Clock app stay; Android has no call to remove them).
 */
suspend fun AppGraph.wipe(context: Context, models: Boolean) {
    assistant.stop()
    assistant.clearQueue()
    talk.close()
    readAloud.stop()
    kotlinx.coroutines.withContext(Dispatchers.IO) {
        db.reminders().pending().forEach { reminders.cancel(it.id) }
        focus.stop()
        context.getSystemService(android.app.NotificationManager::class.java)?.cancelAll()
        runCatching { calendar.deleteOwnCalendar() }
        db.clearAllTables()
        memory.forget()
        prep.deleteAll()
        java.io.File(context.filesDir, "queue.json").delete()
        com.lfmpixel.lfm.crash.Crashes.log?.clear()
        com.lfmpixel.lfm.crash.Crashes.unseen.value = null
        context.cacheDir.listFiles().orEmpty().forEach { it.deleteRecursively() }
    }
    if (models) {
        downloads.items.value.values.forEach { downloads.cancel(it.model) }
        engine.unloadAll()
        kotlinx.coroutines.withContext(Dispatchers.IO) { this@wipe.models.dir.listFiles().orEmpty().forEach { it.deleteRecursively() } }
        this.models.refresh()
    }
    settings.clear()
    com.lfmpixel.lfm.edge.EdgeKey.forget()
    assistant.newChat()
}

class LfmApp : Application() {
    lateinit var graph: AppGraph
        private set

    companion object {
        /** Edge mode is set up, for screens that only need to say where things run. */
        val edgeOn: Boolean get() = instance?.graph?.edge?.configured?.value == true
        @Volatile private var instance: LfmApp? = null
    }

    override fun onCreate() {
        super.onCreate()
        com.lfmpixel.lfm.crash.Crashes.install(this)
        graph = AppGraph(this)
        instance = this
        Reminders.createChannel(this)
        com.lfmpixel.lfm.assistant.Focus.createChannel(this)
    }
}
