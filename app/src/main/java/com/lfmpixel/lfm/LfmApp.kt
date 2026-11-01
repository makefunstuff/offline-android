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
import com.lfmpixel.lfm.web.NetInstaller
import com.lfmpixel.lfm.web.WebSearch
import com.lfmpixel.lfm.voice.VoiceRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Everything the app is made of, wired by hand. */
class AppGraph(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val heat = com.lfmpixel.lfm.device.HeatWatch(context).also { w ->
        com.lfmpixel.lfm.llm.LlamaNative.heat(w.heat.value)
        w.onChange = { com.lfmpixel.lfm.llm.LlamaNative.heat(it) }
    }
    val db = LfmDatabase.open(context)
    val settings = Settings(context)
    val models = ModelStore(context)
    val engine = ModelManager(context.applicationInfo.nativeLibraryDir.orEmpty(), models, settings, scope,
        java.io.File(context.cacheDir, "litert").apply { mkdirs() })
    val reminders = Reminders(context)
    val focus = com.lfmpixel.lfm.assistant.Focus(context)
    val calendar = LocalCalendar(context)
    val contacts = Contacts(context)
    val controls = PhoneControls(context)
    val launcher = ExternalLauncher(context)
    // Late: the lock is made below; the journal is left out of library searches while it is locked.
    private val journalHidden: () -> Boolean = { lock.locks(com.lfmpixel.lfm.settings.LockMode.Journal) }
    val toolEnv = ToolEnv(db, reminders, calendar, contacts, controls, launcher, focus, hideJournal = { journalHidden() })
    val tools = ToolRegistry(defaultTools(toolEnv), toolEnv)
    val net = NetClient(context, settings, db, scope)
    /** Edge mode: the owner's server, through Inkling Net. */
    val edge = com.lfmpixel.lfm.edge.Edge(net, settings, scope).also { engine.edge = it }
    val web = WebSearch(db, settings, net, contacts)
    val downloads = ModelDownloads(context, models, net, settings, engine, scope)
    val netInstaller = NetInstaller(context)
    val assistant = Assistant(context, db, engine, settings, tools, reminders, calendar, controls, launcher, scope, web, heat.heat)
    val voice = VoiceRecorder(OmniTranscriber(engine))
    val talk = com.lfmpixel.lfm.voice.Talk(context, engine, settings, scope)
    val readAloud = com.lfmpixel.lfm.voice.ReadAloud(context, engine, settings, scope)
    val prep = MediaPrep(context)
    /** The small classifiers on the server, and Look (the camera described live). */
    val labels = com.lfmpixel.lfm.edge.Labels(edge)
    val look = com.lfmpixel.lfm.see.Look(edge, scope).also { l ->
        talk.seeing = { l.seeing() }
        talk.lookAnswer = { said ->
            if (!l.active) null
            else labels.intent(said)?.takeIf { it.intent == com.lfmpixel.lfm.edge.IntentLabel.LOOK }?.let { l.answer(said) }
        }
        talk.tools = object : com.lfmpixel.lfm.voice.TalkTools {
            override fun specs() = tools.specsJson()
            override suspend fun run(call: com.lfmpixel.lfm.llm.ToolCall): com.lfmpixel.lfm.tools.ToolResult {
                val t0 = System.nanoTime()
                return assistant.execute(call, "voice", afterWebText = false).also { r ->
                    android.util.Log.i("lfm-talk", "tool %s %s in %.0f ms: %s".format(java.util.Locale.ENGLISH, call.name,
                        if (r.ok) "ok" else "failed", (System.nanoTime() - t0) / 1e6, r.cardLabel))
                }
            }
        }
        assistant.seeing = { l.seeing() }
        assistant.labels = labels
    }
    val lock = com.lfmpixel.lfm.device.AppLock(context).also { l ->
        scope.launch {
            var first = true
            settings.prefs.collect { p -> if (first) { first = false; l.start(p.lockMode) } else l.mode = p.lockMode }
        }
    }
    val bench = com.lfmpixel.lfm.llm.Benchmark(engine, models, settings, scope)
    val edgeBench = com.lfmpixel.lfm.edge.EdgeBench(edge, settings, scope)
    val voiceBench = com.lfmpixel.lfm.voice.VoiceBench(engine, settings, talk, readAloud, scope)
}

/**
 * Delete everything (Settings → Privacy): every note, chat, reminder, search, log line and setting, pictures kept for
 * the chat, the queue, Inkling's own calendar, and, if [models] is set, the model files. The app is then as on first
 * run. Nothing outside Inkling is touched (alarms set in the Clock app stay; Android has no call to remove them).
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
        prep.deleteAll()
        java.io.File(context.filesDir, "queue.json").delete()
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
        graph = AppGraph(this)
        instance = this
        Reminders.createChannel(this)
        com.lfmpixel.lfm.assistant.Focus.createChannel(this)
    }
}
