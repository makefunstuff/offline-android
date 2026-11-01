package com.lfmpixel.lfm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.lfmpixel.lfm.AppGraph
import com.lfmpixel.lfm.BottomBar
import com.lfmpixel.lfm.LfmApp
import com.lfmpixel.lfm.daylight.DayClock
import com.lfmpixel.lfm.daylight.skyAt
import com.lfmpixel.lfm.data.Entry
import com.lfmpixel.lfm.data.EntryKind
import com.lfmpixel.lfm.tools.PermissionGate
import com.lfmpixel.lfm.ui.screens.*
import com.lfmpixel.lfm.ui.theme.Lfm
import com.lfmpixel.lfm.ui.theme.LfmTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pictures of Inkling's screens, light and dark, on the JVM (Robolectric, no emulator), written to
 * build/outputs/roborazzi/ to look at. Each screen is drawn as the app frames it: the hour's sky strip on top, the
 * bottom bar under the four places. Nothing is compared against a stored picture (dates and the hour change); a
 * screen that fails to draw fails the test. The look itself is guarded by DaylightGuardTest.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = LfmApp::class, qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    private val g: AppGraph get() = ApplicationProvider.getApplicationContext<LfmApp>().graph
    private val gate = object : PermissionGate { override suspend fun ensure(permissions: List<String>) = false }
    private val pickers = Pickers({}, {})

    /** Daylight's clock at [hour]:[minute] today, so the sky is the same on every run. */
    private fun at(hour: Int, minute: Int = 0) {
        val t = LocalDate.now().atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        DayClock.offset.value = t - System.currentTimeMillis()
    }

    @Before fun setUp() {
        at(10)
        // Animations off (as "Remove animations" does), so rows and highlights are drawn where they land, not mid-fade.
        android.provider.Settings.Global.putFloat(ApplicationProvider.getApplicationContext<LfmApp>().contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val today = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        runBlocking {
            val e = g.db.entries()
            e.insert(Entry(kind = EntryKind.Todo, title = "Dentist", dueAt = today + 15 * 3_600_000L, dueHasTime = true))
            e.insert(Entry(kind = EntryKind.Todo, title = "Call the plumber about the boiler", dueAt = today, minutes = 15))
            e.insert(Entry(kind = EntryKind.Todo, title = "Buy oat milk", list = "Shopping"))
            e.insert(Entry(kind = EntryKind.Note, title = "Boiler service", body = "Engineer said the pressure drops overnight; check the valve."))
            e.insert(Entry(kind = EntryKind.Idea, title = "A walking route along the canal", topic = "Weekends"))
            e.insert(Entry(kind = EntryKind.Journal, title = "Slow morning, good coffee.", mood = 1))
        }
    }

    @After fun tearDown() { DayClock.offset.value = 0L }

    private fun shot(name: String, tabs: Boolean = false, route: Route = Route.Today, screen: @Composable (Nav) -> Unit) {
        for (dark in listOf(false, true)) {
            captureRoboImage("build/outputs/roborazzi/${name}_${if (dark) "dark" else "light"}.png") {
                LfmTheme(dark = dark) {
                    val nav = androidx.compose.runtime.remember { Nav(route) }
                    Column(Modifier.fillMaxSize().background(Lfm.c.bg)) {
                        val now by com.lfmpixel.lfm.daylight.rememberNow()
                        Box(Modifier.fillMaxWidth().height(24.dp).background(Color(skyAt(now).top)))
                        Box(Modifier.weight(1f)) { screen(nav) }
                        if (tabs) BottomBar(nav)
                    }
                }
            }
        }
    }

    @Test fun today() = shot("01_today", tabs = true) { TodayScreen(g, it, {}, false) }
    @Test fun library() = shot("02_library", tabs = true, route = Route.Library()) { LibraryScreen(g, it, LibFilter.All, {}, false) }
    @Test fun plans() = shot("03_plans", tabs = true, route = Route.Todos) { TodosScreen(g, it, {}, false) }
    @Test fun chat() = shot("04_chat", route = Route.Chat) { ChatScreen(g, it, {}, false) }
    @Test fun talk() = shot("05_talk", route = Route.Talk) { TalkScreen(g, it, gate) }
    @Test fun settings() = shot("06_settings", route = Route.Settings) { SettingsScreen(g, it) }
    @Test fun models() = shot("07_models", route = Route.Models) { ModelsScreen(g, it, {}) }
    @Test fun server() = shot("08_server", route = Route.Server) { ServerScreen(g, it) }
    @Test fun voice() = shot("09_voice", route = Route.Voice) { VoiceSettingsScreen(g, it) }
    @Test fun knows() = shot("10_knows", route = Route.Knows) { KnowsScreen(g, it) }
    @Test fun privacy() = shot("11_privacy", route = Route.Privacy) { PrivacyScreen(g, it, pickers) }
    @Test fun webSearch() = shot("12_websearch", route = Route.WebSearch) { WebSearchScreen(g, it) }
    @Test fun tools() = shot("13_tools", route = Route.Tools) { ToolsScreen(g, it) }
    @Test fun history() = shot("14_history", route = Route.History) { HistoryScreen(g, it) }
    @Test fun firstRun() = shot("15_firstrun", route = Route.FirstRun) { FirstRunScreen(g, it) {} }
    @Test fun editor() = shot("16_editor", route = Route.Editor(kind = EntryKind.Note)) { EditorScreen(g, it, Route.Editor(kind = EntryKind.Note)) }
    @Test fun dump() = shot("17_dump", route = Route.Dump()) { DumpScreen(g, it, Route.Dump(), {}, false) }
    @Test fun lock() = shot("18_lock") { LockScreen(g) }
    @Test fun focusDone() = shot("20_focus", route = Route.Focus) { FocusScreen(g, it) }
    @Test fun daySheet() = shot("21_daysheet") { com.lfmpixel.lfm.ui.components.DaySheet(LocalDate.of(2026, 11, 12), {}, {}) }
    // The same screens under the dusk and the night sky: the header's ink follows the sky.
    @Test fun chatAtDusk() { at(18, 30); shot("22_chat_dusk", route = Route.Chat) { ChatScreen(g, it, {}, false) } }
    @Test fun libraryAtNight() { at(23); shot("23_library_night", tabs = true, route = Route.Library()) { LibraryScreen(g, it, LibFilter.All, {}, false) } }
    @Test fun todayAtNight() { at(23); shot("24_today_night", tabs = true) { TodayScreen(g, it, {}, false) } }
    @Test fun crashes() {
        com.lfmpixel.lfm.crash.Crashes.log!!.record(IllegalStateException("Engine is closed"), "main", true, com.lfmpixel.lfm.crash.Crashes.build)
        shot("25_crashes", route = Route.Crashes) { CrashesScreen(g, it) }
    }
    @Test fun crashNote() = shot("26_crash_note", tabs = true) {
        Box(Modifier.fillMaxSize()) {
            TodayScreen(g, it, {}, false)
            CrashNote({}, {}, Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(bottom = 64.dp))
        }
    }
    @Test fun voiceInfo() = shot("19_sheet") { VoiceInfoSheet("Voice needs LFM3-Omni-2B and its projector file. Import both in Settings → Models.", {}, {}) }
}
