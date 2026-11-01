package com.lfmpixel.lfm

import androidx.test.core.app.ApplicationProvider
import com.lfmpixel.lfm.data.ActionStatus
import com.lfmpixel.lfm.data.Entry
import com.lfmpixel.lfm.data.EntryKind
import com.lfmpixel.lfm.data.Reminder
import com.lfmpixel.lfm.llm.ToolCall
import com.lfmpixel.lfm.settings.ToolPolicy
import com.lfmpixel.lfm.tools.Calc
import com.lfmpixel.lfm.tools.ItemMatch
import com.lfmpixel.lfm.tools.ToolArgsException
import com.lfmpixel.lfm.tools.ToolResult
import com.lfmpixel.lfm.tools.undoItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pure parts of LFMP-6's tools: the item finder and the calculator. */
class ItemMatchAndCalcTest {
    private data class T(val title: String, val at: Long)
    private fun pick(q: String, vararg titles: String) = ItemMatch.pick(q, titles.mapIndexed { i, t -> T(t, i.toLong()) }, { it.title }, { it.at })

    @Test fun findsOneByAWordOrAWordStart() {
        assertEquals(ItemMatch.Pick.One(T("Buy oat milk", 0)), pick("milk", "Buy oat milk", "Call the dentist"))
        assertEquals(ItemMatch.Pick.One(T("Call the dentist", 1)), pick("the dentist one", "Buy oat milk", "Call the dentist"))
        assertEquals(ItemMatch.Pick.One(T("Dentist's letter", 0)), pick("dentist", "Dentist's letter"))
    }

    @Test fun twoThatFitAsWellAreAskedBackUnlessOneIsExact() {
        assertTrue(pick("milk", "Oat milk", "Milk chocolate") is ItemMatch.Pick.Several)
        assertEquals(ItemMatch.Pick.One(T("Oat milk", 0)), pick("oat milk", "Oat milk", "Oat milk chocolate"))
        assertEquals(ItemMatch.Pick.One(T("Renew passport", 0)), pick("renew passport", "Renew passport", "Renew passport", "Passport renewal portal"))
        assertEquals(ItemMatch.Pick.One(T("Book list from Mia", 0)), pick("the book list", "Book list from Mia", "Book list from Mia"))
    }

    @Test fun lastMeansTheNewestAndNothingMatchesIsNone() {
        assertEquals(ItemMatch.Pick.One(T("Second", 1)), pick("last", "First", "Second"))
        assertEquals(ItemMatch.Pick.One(T("Second", 1)), pick("the one I just added", "First", "Second"))
        assertEquals(ItemMatch.Pick.None, pick("taxes", "Buy oat milk"))
        assertEquals("a shared verb is not enough", ItemMatch.Pick.None, pick("call the bank", "Call mom", "Buy oat milk"))
        assertEquals(ItemMatch.Pick.None, pick("milk"))
    }

    @Test fun mathWorksOutExactly() {
        assertEquals("15% of 80 = 12", Calc.answer("15% of 80"))
        assertEquals("(12+8)*3 = 60", Calc.answer("(12+8)*3"))
        assertEquals("2^10 = 1024", Calc.answer("2^10"))
        assertEquals("3 times 4 plus 2 = 14", Calc.answer("3 times 4 plus 2"))
        assertEquals("1,200 / 7 = 171.4286", Calc.answer("1,200 / 7"))
        assertEquals("5", Calc.answer("what is 10 - 2 - 3?").substringAfter("= "))
        assertEquals("0.1+0.2 = 0.3", Calc.answer("0.1+0.2"))
        assertEquals("80 + 20% = 80.2", Calc.answer("80 + 20%"))
    }

    @Test fun unitsConvert() {
        assertEquals("5 km = 3.1069 miles", Calc.answer("5 km to miles"))
        assertEquals("100 c = 212 f", Calc.answer("100 c in f"))
        assertEquals("350 fahrenheit = 176.6667 celsius", Calc.answer("350 fahrenheit to celsius"))
        assertEquals("2 cups = 480 ml", Calc.answer("2 cups to ml"))
        assertEquals("70 kg = 154.3236 pounds", Calc.answer("convert 70 kg to pounds"))
        assertEquals("90 minutes = 1.5 hours", Calc.answer("90 minutes in hours"))
    }

    @Test fun nonsenseIsAnErrorNotAGuess() {
        for (q in listOf("5 kg to miles", "1/0", "hello", "(2+3", "")) {
            try { Calc.answer(q); fail("no answer for \"$q\"") } catch (e: ToolArgsException) { }
        }
    }
}

/** LFMP-6's tools against a real Room database, with undo. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = LfmApp::class)
class MoreToolsTest {
    private lateinit var g: AppGraph

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<LfmApp>()
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        g = app.graph
        g.assistant.onShown()
        g.focus.stop()
    }

    private suspend fun run(name: String, vararg args: Pair<String, String>): ToolResult = g.tools[name]!!.run(mapOf(*args))
    private suspend fun undo(r: ToolResult): Boolean {
        val (kind, value) = r.undo.split(':', limit = 2)
        return undoItem(g.toolEnv, kind, value)!!
    }
    private suspend fun todo(title: String, due: Long? = null, kind: EntryKind = EntryKind.Todo) =
        g.db.entries().insert(Entry(kind = kind, title = title, dueAt = due))
    private val now get() = System.currentTimeMillis()

    @Test fun completeTodoTicksItOffAndUndoReopens() = runBlocking {
        val id = todo("Buy oat milk")
        todo("Call the dentist")
        val r = run("complete_todo", "todo" to "milk")
        assertTrue(r.forModel, r.ok)
        assertEquals("Buy oat milk", r.cardTitle)
        assertTrue(g.db.entries().get(id)!!.done)
        assertTrue(undo(r))
        assertFalse(g.db.entries().get(id)!!.done)
    }

    @Test fun completeTodoThroughTheAssistantIsLoggedAndUndone() = runBlocking {
        val id = todo("Post the parcel")
        val r = g.assistant.execute(ToolCall("complete_todo", mapOf("todo" to "parcel")), "voice", afterWebText = false)
        assertEquals(ActionStatus.Done, g.db.actions().get(r.actionId!!)!!.status)
        assertTrue(g.assistant.undo(r.actionId!!))
        assertFalse(g.db.entries().get(id)!!.done)
        assertEquals(ActionStatus.Undone, g.db.actions().get(r.actionId!!)!!.status)
    }

    @Test fun twoThatFitAreAskedBack() = runBlocking {
        todo("Oat milk"); todo("Milk chocolate")
        try { run("complete_todo", "todo" to "milk"); fail() } catch (e: ToolArgsException) {
            assertTrue(e.message, e.message!!.contains("Oat milk") && e.message!!.contains("Milk chocolate"))
        }
    }

    @Test fun rescheduleMovesATodoAndUndoPutsItBack() = runBlocking {
        val was = now + 3_600_000
        val id = todo("Write the report", due = was)
        val r = run("reschedule", "what" to "report", "to" to "tomorrow 10:00")
        assertTrue(r.forModel, r.ok)
        val moved = g.db.entries().get(id)!!
        assertEquals(java.time.LocalDate.now().plusDays(1).atTime(10, 0), java.time.Instant.ofEpochMilli(moved.dueAt!!).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime())
        assertTrue(moved.dueHasTime)
        assertTrue(undo(r))
        assertEquals(was, g.db.entries().get(id)!!.dueAt)
    }

    @Test fun snoozeMovesAReminderEvenOneThatWentOff() = runBlocking {
        val at = now - 60_000
        val id = g.db.reminders().insert(Reminder(text = "Take the pills", at = at, fired = true))
        val r = run("reschedule", "what" to "pills", "to" to "in 10 minutes", "kind" to "reminder")
        assertTrue(r.forModel, r.ok)
        val snoozed = g.db.reminders().get(id)!!
        assertFalse(snoozed.fired)
        assertTrue(snoozed.at in now + 8 * 60_000..now + 11 * 60_000)
        assertNotNull(org.robolectric.Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSystemService(android.app.AlarmManager::class.java)).nextScheduledAlarm)
        assertTrue(undo(r))
        assertEquals(at, g.db.reminders().get(id)!!.at)
        assertTrue(g.db.reminders().get(id)!!.fired)
    }

    @Test fun deleteAsksFirstAndUndoRestoresTheTodoWithItsSteps() = runBlocking {
        val tool = g.tools["delete_item"]!!
        assertEquals(ToolPolicy.Ask, tool.defaultPolicy)
        assertTrue(g.tools.mustAsk(tool, emptyMap(), afterWebText = false))
        val id = todo("Tidy the kitchen")
        g.db.entries().insert(Entry(kind = EntryKind.Todo, title = "Clear the table", parentId = id))
        val p = tool.preview(mapOf("what" to "kitchen"))
        assertEquals("Delete this to-do?", p.title)
        assertEquals("Tidy the kitchen", p.fields.first { it.key == "what" }.value)
        val r = run("delete_item", "what" to "kitchen", "kind" to "todo")
        assertTrue(r.forModel, r.ok)
        assertNull(g.db.entries().get(id))
        assertTrue(g.db.entries().stepsNow(id).isEmpty())
        assertTrue(undo(r))
        assertEquals("Tidy the kitchen", g.db.entries().get(id)!!.title)
        assertEquals(listOf("Clear the table"), g.db.entries().stepsNow(id).map { it.title })
    }

    @Test fun deleteAReminderAndANote() = runBlocking {
        val rid = g.db.reminders().insert(Reminder(text = "Bins out", at = now + 7_200_000))
        val r = run("delete_item", "what" to "bins", "kind" to "reminder")
        assertTrue(g.db.reminders().get(rid)!!.cancelled)
        assertTrue(undo(r))
        assertFalse(g.db.reminders().get(rid)!!.cancelled)
        val nid = todo("Boiler pressure", kind = EntryKind.Note)
        val n = run("delete_item", "what" to "boiler", "kind" to "note")
        assertEquals("Note deleted", n.cardLabel)
        assertNull(g.db.entries().get(nid))
        assertTrue(undo(n))
        assertNotNull(g.db.entries().get(nid))
    }

    @Test fun dailyBriefSaysWhatsOnGently() = runBlocking {
        val today = java.time.LocalDate.now()
        fun at(d: java.time.LocalDate, h: Int) = d.atTime(h, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        todo("Pay the rent", due = at(today.minusDays(2), 9))
        g.db.entries().insert(Entry(kind = EntryKind.Todo, title = "Ring the bank", dueAt = at(today, 23), dueHasTime = true))
        g.db.reminders().insert(Reminder(text = "Stretch", at = now + 60_000))
        val r = run("daily_brief")
        assertTrue(r.forModel, r.forModel.contains("To-do: Ring the bank at 23:00"))
        assertTrue(r.forModel, r.forModel.contains("Still open from") && r.forModel.contains("Pay the rent"))
        assertTrue(r.forModel, r.forModel.contains("Reminder") && r.forModel.contains("Stretch"))
        assertTrue(r.forModel, r.forModel.startsWith("Next: "))
        assertFalse(r.forModel.lowercase().contains("overdue"))
        assertEquals("Nothing planned tomorrow.", run("daily_brief", "day" to "tomorrow").forModel)
    }

    @Test fun readLatestReadsBackTheLastNoteAndKeepsALockedJournalShut() = runBlocking {
        g.db.entries().insert(Entry(kind = EntryKind.Note, title = "Old", createdAt = now - 10_000))
        g.db.entries().insert(Entry(kind = EntryKind.Note, title = "Garden idea", body = "Raised beds by the fence", createdAt = now))
        val r = run("read_latest", "kind" to "note")
        assertTrue(r.forModel, r.forModel.contains("Garden idea: Raised beds by the fence"))
        assertFalse(r.forModel.contains("Old"))
        assertEquals(2, run("read_latest", "kind" to "note", "count" to "2").forModel.lines().size)
        g.db.entries().insert(Entry(kind = EntryKind.Journal, title = "Felt low", createdAt = now + 1))
        val locked = com.lfmpixel.lfm.tools.ReadLatestTool(com.lfmpixel.lfm.tools.ToolEnv(g.db, null, null, null, null, null, hideJournal = { true }))
        assertTrue(locked.run(mapOf("kind" to "journal")).forModel.contains("locked"))
        assertFalse(locked.run(mapOf("kind" to "any", "count" to "5")).forModel.contains("Felt low"))
    }

    @Test fun focusStartsOnATodoOrANewOneAndUndoStopsIt() = runBlocking {
        val id = todo("Sort the post")
        val r = run("focus_timer", "task" to "post", "minutes" to "15")
        assertTrue(r.forModel, r.ok)
        assertEquals(id, g.focus.run.value!!.id)
        assertEquals(15 * 60_000L, g.focus.run.value!!.totalMs)
        assertEquals("Focus stopped", run("focus_timer", "action" to "stop").cardLabel)
        assertNull(g.focus.run.value)

        val r2 = run("focus_timer", "task" to "file the taxes")
        val made = g.focus.run.value!!
        assertEquals("File the taxes", made.title)
        assertNotNull(g.db.entries().get(made.id))
        assertTrue(undo(r2))
        assertNull(g.focus.run.value)
        assertNull("the to-do it made is gone again", g.db.entries().get(made.id))
        assertNotNull(g.db.entries().get(id))

        // A to-do the owner changed since stays, and a later run is not stopped.
        val r3 = run("focus_timer", "task" to "plan the garden")
        val made3 = g.focus.run.value!!
        g.db.entries().update(g.db.entries().get(made3.id)!!.let { it.copy(title = "Plan the garden beds", updatedAt = it.createdAt + 60_000) })
        g.focus.start(id, "Sort the post", 5, now + 5_000)
        assertTrue(undo(r3))
        assertNotNull(g.db.entries().get(made3.id))
        assertEquals(id, g.focus.run.value!!.id)
        g.focus.stop()
    }

    @Test fun deletedTextIsNotKeptInTheActionHistory() = runBlocking {
        g.db.entries().insert(Entry(kind = EntryKind.Note, title = "Bank PIN hint", body = "the dog's birthday"))
        val r = run("delete_item", "what" to "bank pin hint", "kind" to "note")
        assertFalse(r.undo, r.undo.contains("birthday"))
        assertTrue(undo(r))
        assertFalse("undo once only", undoItem(g.toolEnv, "restore", r.undo.substringAfter(':'))!!)
    }

    @Test fun focusWithNothingNamedTakesTheNextThing() = runBlocking {
        todo("Answer Sam's email", due = now - 1000)
        val r = run("focus_timer")
        assertTrue(r.forModel, r.ok)
        assertEquals("Answer Sam's email", g.focus.run.value!!.title)
        g.focus.stop()
    }

    @Test fun phoneStatusMediaAndApps() = runBlocking {
        val s = run("phone_status")
        assertTrue(s.forModel, s.forModel.contains("Sound: ") && s.forModel.contains("Do Not Disturb: "))
        val m = run("media_control", "action" to "pause")
        assertTrue(m.forModel, m.ok)
        assertEquals("media:play", m.undo)
        try { run("media_control", "action" to "louder"); fail() } catch (e: ToolArgsException) { }

        val pm = org.robolectric.Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.content.Context>().packageManager)
        val launch = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val info = android.content.pm.ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply {
                packageName = "org.example.music"; name = "org.example.music.Main"; nonLocalizedLabel = "Tunes"
                applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = "org.example.music" }
            }
            nonLocalizedLabel = "Tunes"
        }
        pm.addResolveInfoForIntent(launch, info)
        assertEquals("Open Tunes?", g.tools["open_app"]!!.preview(mapOf("app" to "tunes")).title)
        try { g.tools["open_app"]!!.preview(mapOf("app" to "Spotify")); fail() } catch (e: ToolArgsException) { }
    }

    @Test fun specsStayShortAndNamesUnique() {
        val names = g.tools.all.map { it.name }
        assertEquals(names.size, names.toSet().size)
        for (n in listOf("complete_todo", "reschedule", "delete_item", "daily_brief", "read_latest", "focus_timer",
            "media_control", "phone_status", "open_app", "calculate")) assertTrue(n, n in names)
        // Small models pick tools from the first words: each description is one short sentence or two.
        for (t in g.tools.all) assertTrue("${t.name}: ${t.spec.description}", t.spec.description.length <= 130)
        assertTrue(g.tools["media_control"]!!.spec.toJson().contains("\"enum\": [\"play\", \"pause\", \"next\", \"previous\"]"))
    }
}
