package com.lfmpixel.lfm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lfmpixel.lfm.data.ActionStatus
import com.lfmpixel.lfm.data.EntryKind
import com.lfmpixel.lfm.data.FtsQuery
import com.lfmpixel.lfm.data.Role
import com.lfmpixel.lfm.llm.ToolCall
import com.lfmpixel.lfm.settings.ToolPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The assistant's tool path end to end, against a real Room database, without a model. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = LfmApp::class)
class AssistantTest {
    private lateinit var g: AppGraph

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<LfmApp>()
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        g = app.graph
        g.assistant.onShown()
    }

    private fun waitIdle() = runBlocking {
        withTimeout(10_000) { while (g.assistant.busy.value) kotlinx.coroutines.delay(20) }
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
    }

    @Test fun captureWithoutModelIsSortedAndUndoable() = runBlocking {
        g.assistant.send("Buy oat milk")
        repeat(50) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(20) }
        waitIdle()
        val todos = g.db.entries().todos().first()
        assertEquals(1, todos.size)
        assertEquals("Buy oat milk", todos[0].title)
        assertEquals("Shopping", todos[0].list)
        val msgs = g.db.messages().threadNow(g.assistant.threadId.value)
        assertEquals(listOf(Role.User, Role.Tool, Role.Notice), msgs.map { it.role })
        val card = msgs[1]
        assertEquals("Added to Shopping", card.cardLabel)
        val actionId = assertNotNull(card.actionId).let { card.actionId!! }
        assertTrue(g.assistant.undo(actionId))
        assertTrue(g.db.entries().todos().first().isEmpty())
        assertEquals(ActionStatus.Undone, g.db.actions().get(actionId)!!.status)
        assertFalse("undo twice does nothing", g.assistant.undo(actionId))
    }

    @Test fun askFirstToolsWaitForTheSheetAndCanBeCancelled() = runBlocking {
        val tool = g.tools["set_alarm"]!!
        assertEquals(ToolPolicy.Ask, tool.defaultPolicy)
        var result: com.lfmpixel.lfm.tools.ToolResult? = null
        g.assistant.runDirect(ToolCall("set_alarm", mapOf("time" to "07:30", "label" to "Run")), "chat") { result = it }
        repeat(50) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(10); if (g.assistant.pendingConfirm.value != null) return@repeat }
        val req = assertNotNull(g.assistant.pendingConfirm.value).let { g.assistant.pendingConfirm.value!! }
        assertEquals("Set an alarm?", req.preview.title)
        assertEquals("07:30", req.preview.fields.first { it.key == "time" }.value)
        req.cancel()
        repeat(50) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(10) }
        assertEquals("Cancelled", result?.cardLabel)
        assertEquals(ActionStatus.Cancelled, g.db.actions().get(result!!.actionId!!)!!.status)
    }

    @Test fun webTextAlwaysAsksEvenForDirectTools() {
        val t = g.tools["add_todo"]!!
        assertFalse(g.tools.mustAsk(t, emptyMap(), afterWebText = false))
        assertTrue(g.tools.mustAsk(t, emptyMap(), afterWebText = true))
        assertTrue(g.tools.mustAsk(t, mapOf("add_todo" to ToolPolicy.Ask), afterWebText = false))
        assertFalse(g.tools.mustAsk(g.tools["search_library"]!!, mapOf("search_library" to ToolPolicy.Ask), afterWebText = true))
    }

    @Test fun reminderIsStoredScheduledAndCancelledByUndo() = runBlocking {
        var result: com.lfmpixel.lfm.tools.ToolResult? = null
        g.assistant.runDirect(ToolCall("set_reminder", mapOf("text" to "call the plumber", "at" to "in 30 minutes")), "chat") { result = it }
        repeat(100) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(10); if (result != null) return@repeat }
        val r = result!!
        assertTrue(r.forModel, r.ok)
        assertEquals("Reminder set", r.cardLabel)
        assertEquals("Call the plumber", r.cardTitle)
        val pending = g.db.reminders().pending()
        assertEquals(1, pending.size)
        val alarms = org.robolectric.Shadows.shadowOf(ApplicationProvider.getApplicationContext<Context>().getSystemService(android.app.AlarmManager::class.java))
        assertNotNull(alarms.nextScheduledAlarm)
        assertTrue(g.assistant.undo(r.actionId!!))
        assertTrue(g.db.reminders().pending().isEmpty())
        assertNull(alarms.nextScheduledAlarm)
    }

    @Test fun fullTextSearchFindsWordStartsAcrossKinds() = runBlocking {
        g.db.entries().insert(com.lfmpixel.lfm.data.Entry(kind = EntryKind.Note, title = "Boiler service", body = "Pressure drops below 1 bar overnight", tags = "home"))
        g.db.entries().insert(com.lfmpixel.lfm.data.Entry(kind = EntryKind.Idea, title = "Shelf above the radiator"))
        g.db.entries().insert(com.lfmpixel.lfm.data.Entry(kind = EntryKind.Todo, title = "Call boiler engineer"))
        assertEquals(setOf("Boiler service", "Call boiler engineer"), g.db.entries().search(FtsQuery.from("boil")!!).map { it.title }.toSet())
        assertEquals(listOf("Boiler service"), g.db.entries().search(FtsQuery.from("pressure overnight")!!).map { it.title })
        assertEquals(listOf("Boiler service"), g.db.entries().search(FtsQuery.from("home")!!).map { it.title })
        val r = g.tools["search_library"]!!.run(mapOf("query" to "radiator"))
        assertTrue(r.forModel, r.forModel.contains("Shelf above the radiator"))
    }

    @Test fun badArgumentsBecomeAFailedCardNotACrash() = runBlocking {
        var result: com.lfmpixel.lfm.tools.ToolResult? = null
        g.assistant.runDirect(ToolCall("set_reminder", mapOf("text" to "x", "at" to "whenever")), "chat") { result = it }
        repeat(100) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(10); if (result != null) return@repeat }
        assertFalse(result!!.ok)
        assertEquals("Could not do it", result!!.cardLabel)
    }

    @Test fun messagesSentWhileBusyAreQueuedNotDropped() = runBlocking {
        g.assistant.send("Buy oat milk")
        g.assistant.send("Buy bread")
        g.assistant.send("Idea: herb box on the balcony")
        repeat(100) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(20) }
        waitIdle()
        assertEquals(setOf("Buy oat milk", "Buy bread"), g.db.entries().todos().first().map { it.title }.toSet())
        assertEquals(1, g.db.entries().byKind(EntryKind.Idea).first().size)
        val users = g.db.messages().threadNow(g.assistant.threadId.value).filter { it.role == Role.User }.map { it.text }
        assertEquals(listOf("Buy oat milk", "Buy bread", "Idea: herb box on the balcony"), users)
    }

    @Test fun undoDoesNotDeleteAnEntryTheOwnerChanged() = runBlocking {
        var result: com.lfmpixel.lfm.tools.ToolResult? = null
        g.assistant.runDirect(ToolCall("add_todo", mapOf("text" to "Renew passport")), "chat") { result = it }
        repeat(100) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(10); if (result != null) return@repeat }
        val e = g.db.entries().todos().first().single()
        g.db.entries().update(e.copy(title = "Renew passport by Friday", updatedAt = e.createdAt + 60_000))
        assertFalse(g.assistant.undo(result!!.actionId!!))
        assertEquals("Renew passport by Friday", g.db.entries().todos().first().single().title)
        assertEquals(ActionStatus.Done, g.db.actions().get(result!!.actionId!!)!!.status)
    }

    @Test fun queuedMessagesCanBeEditedMovedAndRemoved() {
        // Hold the queue still: nothing runs until the main looper is idled.
        g.assistant.send("first")
        g.assistant.send("second")
        g.assistant.send("third")
        val q = g.assistant.queue.value
        assertEquals(listOf("first", "second", "third"), q.map { it.text })
        g.assistant.editQueued(q[1].id, "  second, edited ")
        g.assistant.moveQueued(q[2].id, -1)
        assertEquals(listOf("first", "third", "second, edited"), g.assistant.queue.value.map { it.text })
        g.assistant.sendNext(q[1].id)
        assertEquals("second, edited", g.assistant.queue.value.first().text)
        g.assistant.removeQueued(q[0].id)
        g.assistant.editQueued(q[2].id, "   ")
        assertEquals(listOf("second, edited"), g.assistant.queue.value.map { it.text })
        assertTrue(g.assistant.busy.value)
        g.assistant.removeQueued(q[1].id)
    }

    @Test fun typedQueuedMessagesAreKeptOnDisk() {
        val f = java.io.File.createTempFile("queue", ".json")
        com.lfmpixel.lfm.assistant.QueueStore.write(f, listOf(com.lfmpixel.lfm.assistant.Queued(4, "Buy \"oat\" milk\nand bread", 3, "voice")))
        val back = com.lfmpixel.lfm.assistant.QueueStore.read(f)
        assertEquals(listOf(com.lfmpixel.lfm.assistant.Queued(4, "Buy \"oat\" milk\nand bread", 3, "voice")), back)
        f.writeText("not json")
        assertTrue(com.lfmpixel.lfm.assistant.QueueStore.read(f).isEmpty())
    }

    @Test fun newChatKeepsQueuedMessagesAndRunsThemThere() = runBlocking {
        g.assistant.send("Buy oat milk")
        g.assistant.send("Buy bread")
        g.assistant.newChat()
        repeat(100) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(20) }
        waitIdle()
        val titles = g.db.entries().todos().first().map { it.title }
        assertTrue(titles.containsAll(listOf("Buy oat milk", "Buy bread")))
        assertEquals(2, g.db.messages().threadNow(g.assistant.threadId.value).count { it.role == Role.User })
        assertTrue(g.assistant.queue.value.isEmpty())
    }

    @Test fun editorNeverCutsALongNote() = runBlocking {
        val long = "Dictated in one go: " + "the boiler pressure drops overnight and the plumber wants photos first ".repeat(4)
        val id = g.db.entries().insert(com.lfmpixel.lfm.data.Entry(kind = EntryKind.Note, title = long.take(60) + "…", body = long, source = "voice"))
        // Opened and left with no change: nothing is written.
        val d = com.lfmpixel.lfm.ui.screens.Draft(g, id, EntryKind.Note)
        d.load(g.db.entries().get(id)!!)
        assertNull(d.save(leaving = true))
        assertEquals(long, g.db.entries().get(id)!!.body)
        // Edited: the whole first line survives, in the body, and loads back the same.
        d.text = androidx.compose.ui.text.input.TextFieldValue(long + " More.")
        d.changed()
        d.save(leaving = true)
        val saved = g.db.entries().get(id)!!
        assertTrue(saved.title.endsWith("…") && saved.title.length <= 120)
        val again = com.lfmpixel.lfm.ui.screens.Draft(g, id, EntryKind.Note).also { it.load(saved) }
        assertEquals(long + " More.", again.text.text)
        // Emptied and left: deleted, and what it was comes back for Undo.
        again.text = androidx.compose.ui.text.input.TextFieldValue("")
        again.changed()
        assertEquals(id, again.save(leaving = true)?.id)
        assertNull(g.db.entries().get(id))
    }

    @Test fun editorKeepsAFirstLineThatRepeats() = runBlocking {
        val d = com.lfmpixel.lfm.ui.screens.Draft(g, null, EntryKind.Note)
        d.text = androidx.compose.ui.text.input.TextFieldValue("Milk\nmilk")
        d.changed()
        d.save()
        val back = com.lfmpixel.lfm.ui.screens.Draft(g, d.id, EntryKind.Note).also { it.load(g.db.entries().get(d.id!!)!!) }
        assertEquals("Milk\nmilk", back.text.text)
    }

    private fun runTool(call: ToolCall): com.lfmpixel.lfm.tools.ToolResult {
        var result: com.lfmpixel.lfm.tools.ToolResult? = null
        g.assistant.runDirect(call, "chat") { result = it }
        repeat(200) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(10); if (result != null) return result!! }
        return result!!
    }

    /** Android blocks a hand-off from the background without an error: it must not be logged as opened. */
    @Test fun handOffFromTheBackgroundIsNotLoggedAsOpened() = runBlocking {
        g.settings.setToolPolicy("set_timer", ToolPolicy.Do)
        g.launcher.onScreen = false
        val blocked = runTool(ToolCall("set_timer", mapOf("duration" to "10 min")))
        assertFalse(blocked.ok)
        assertEquals("Not opened", blocked.cardLabel)
        assertEquals(ActionStatus.Failed, g.db.actions().get(blocked.actionId!!)!!.status)
        assertNull("nothing was started", org.robolectric.Shadows.shadowOf(g.launcher.let { ApplicationProvider.getApplicationContext<LfmApp>() }).nextStartedActivity)

        g.launcher.onScreen = true
        val opened = runTool(ToolCall("set_timer", mapOf("duration" to "10 min")))
        assertTrue(opened.forModel, opened.ok)
        assertEquals(ActionStatus.Opened, g.db.actions().get(opened.actionId!!)!!.status)
        assertEquals(android.provider.AlarmClock.ACTION_SET_TIMER,
            org.robolectric.Shadows.shadowOf(ApplicationProvider.getApplicationContext<LfmApp>()).nextStartedActivity.action)
    }

    @Test fun volumeIsSetAndUndone() = runBlocking {
        val audio = ApplicationProvider.getApplicationContext<LfmApp>().getSystemService(android.media.AudioManager::class.java)
        val stream = android.media.AudioManager.STREAM_MUSIC
        audio.setStreamVolume(stream, audio.getStreamMaxVolume(stream) / 5, 0)
        val before = g.controls.volumePercent(stream)
        val r = runTool(ToolCall("set_volume", mapOf("level" to "80", "stream" to "media")))
        assertTrue(r.forModel, r.ok)
        assertEquals(80.0, g.controls.volumePercent(stream).toDouble(), 7.0)
        assertTrue(g.assistant.undo(r.actionId!!))
        assertEquals(before, g.controls.volumePercent(stream))
    }

    /** Journal only lock: the assistant's library search can't read the journal until it is unlocked. */
    @Test fun lockedJournalIsLeftOutOfLibrarySearch() = runBlocking {
        g.db.entries().insert(com.lfmpixel.lfm.data.Entry(kind = EntryKind.Journal, title = "Felt anxious about the dentist"))
        g.db.entries().insert(com.lfmpixel.lfm.data.Entry(kind = EntryKind.Note, title = "Dentist is on Elm Street"))
        g.lock.start(com.lfmpixel.lfm.settings.LockMode.Journal)
        val locked = runTool(ToolCall("search_library", mapOf("query" to "dentist")))
        assertTrue(locked.forModel.contains("Elm Street"))
        assertFalse(locked.forModel.contains("anxious"))
        g.lock.start(com.lfmpixel.lfm.settings.LockMode.Off)
        assertTrue(runTool(ToolCall("search_library", mapOf("query" to "dentist"))).forModel.contains("anxious"))
        // Opened, then away from the screen past the grace time: a turn still running in the background can't read it.
        g.lock.start(com.lfmpixel.lfm.settings.LockMode.Journal)
        g.lock.prompt = { true }
        assertTrue(g.lock.unlock("test"))
        assertFalse(g.lock.locks(com.lfmpixel.lfm.settings.LockMode.Journal))
        g.lock.onHidden(now = 1_000)
        assertFalse(g.lock.locks(com.lfmpixel.lfm.settings.LockMode.Journal, now = 30_000))
        assertTrue(g.lock.locks(com.lfmpixel.lfm.settings.LockMode.Journal, now = 61_000))
        g.lock.prompt = null
        g.lock.start(com.lfmpixel.lfm.settings.LockMode.Off)
    }

    /** Stop from the notification holds the queue only until Inkling is on screen again. */
    @Test fun stopAllHoldsTheQueueUntilShownAgain() = runBlocking {
        g.assistant.stopAll()
        assertFalse("held is not busy", g.assistant.busy.value)
        g.assistant.send("Buy oat milk")
        repeat(30) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(10) }
        assertEquals(1, g.assistant.queue.value.size)
        assertFalse(g.assistant.busy.value)
        g.assistant.onShown()
        repeat(100) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(10); if (g.assistant.queue.value.isEmpty() && !g.assistant.busy.value) return@repeat }
        waitIdle()
        assertTrue(g.assistant.queue.value.isEmpty())
        assertEquals("Buy oat milk", g.db.entries().todos().first().single().title)
    }

    @Test fun exportHasEveryKindAndEveryTable() = runBlocking {
        g.db.entries().insert(com.lfmpixel.lfm.data.Entry(kind = EntryKind.Note, title = "Boiler", body = "Pressure 1.2 bar", tags = "home"))
        g.db.entries().insert(com.lfmpixel.lfm.data.Entry(kind = EntryKind.Todo, title = "Buy oat milk", list = "Shopping", done = true))
        g.db.entries().insert(com.lfmpixel.lfm.data.Entry(kind = EntryKind.Journal, title = "A calm day", mood = 1))
        g.db.messages().insert(com.lfmpixel.lfm.data.ChatMessage(role = Role.User, text = "Hello there"))
        val out = java.io.ByteArrayOutputStream()
        com.lfmpixel.lfm.data.Export.write(g.db, out, java.time.ZoneId.of("UTC"))
        val files = mutableMapOf<String, String>()
        java.util.zip.ZipInputStream(out.toByteArray().inputStream()).use { z ->
            while (true) { val e = z.nextEntry ?: break; files[e.name] = z.readBytes().toString(Charsets.UTF_8) }
        }
        assertEquals(setOf("README.txt", "notes.md", "ideas.md", "journal.md", "todos.md", "chats.md", "inkling.json"), files.keys)
        assertTrue(files["notes.md"]!!.contains("## Boiler") && files["notes.md"]!!.contains("Pressure 1.2 bar") && files["notes.md"]!!.contains("#home"))
        assertTrue(files["todos.md"]!!.contains("## [x] Buy oat milk"))
        assertTrue(files["journal.md"]!!.contains("good day"))
        assertTrue(files["chats.md"]!!.contains("**You:** Hello there"))
        val json = org.json.JSONObject(files["inkling.json"]!!)
        assertEquals(3, json.getJSONArray("entries").length())
        assertEquals("Pressure 1.2 bar", json.getJSONArray("entries").getJSONObject(0).getString("body"))
        com.lfmpixel.lfm.data.Export.TABLES.forEach { assertTrue(it, json.has(it)) }
    }

    @Test fun wipeLeavesNothingBehindButTheModels() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<LfmApp>()
        g.db.entries().insert(com.lfmpixel.lfm.data.Entry(kind = EntryKind.Note, title = "Secret"))
        g.db.messages().insert(com.lfmpixel.lfm.data.ChatMessage(role = Role.User, text = "Hello"))
        g.settings.setFirstRunDone(); g.settings.setThinking(true)
        g.assistant.hold(-1) // keep the queue still while it is filled
        g.assistant.send("still waiting")
        val model = java.io.File(g.models.dir, "LFM3-1.7B-QAD-Q4_0.gguf").apply { writeText("x") }
        g.wipe(app, models = false)
        assertTrue(g.db.entries().all().isEmpty())
        assertTrue(g.db.messages().all().isEmpty())
        assertTrue(g.assistant.queue.value.isEmpty())
        assertFalse(java.io.File(app.filesDir, "queue.json").let { it.exists() && it.readText() != "[]" })
        assertFalse(g.settings.now().firstRunDone)
        assertFalse(g.settings.now().thinking)
        assertTrue("models stay unless asked", model.exists())
        g.wipe(app, models = true)
        assertFalse(model.exists())
        g.assistant.hold(null)
    }
}
