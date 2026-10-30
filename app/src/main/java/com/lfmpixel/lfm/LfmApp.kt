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
import com.lfmpixel.lfm.voice.OmniTranscriber
import com.lfmpixel.lfm.voice.VoiceRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Everything the app is made of, wired by hand. */
class AppGraph(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val db = LfmDatabase.open(context)
    val settings = Settings(context)
    val models = ModelStore(context)
    val engine = ModelManager(context.applicationInfo.nativeLibraryDir.orEmpty(), models, settings, scope)
    val reminders = Reminders(context)
    val calendar = LocalCalendar(context)
    val contacts = Contacts(context)
    val controls = PhoneControls(context)
    val launcher = ExternalLauncher(context)
    val tools = ToolRegistry(defaultTools(ToolEnv(db, reminders, calendar, contacts, controls, launcher)))
    val assistant = Assistant(context, db, engine, settings, tools, reminders, calendar, controls, launcher, scope)
    val voice = VoiceRecorder(OmniTranscriber(engine))
}

class LfmApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        Reminders.createChannel(this)
    }
}
