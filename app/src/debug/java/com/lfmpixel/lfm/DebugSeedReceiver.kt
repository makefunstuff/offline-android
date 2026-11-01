package com.lfmpixel.lfm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lfmpixel.lfm.data.Entry
import com.lfmpixel.lfm.data.EntryKind
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** adb shell am broadcast -a com.lfmpixel.lfm.DEBUG_SEED: sample library content for screenshots (debug builds only). */
class DebugSeedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val g = (context.applicationContext as LfmApp).graph
        val done = goAsync()
        val zone = ZoneId.systemDefault()
        fun day(d: Long, h: Int = 10) = LocalDate.now().minusDays(d).atTime(h, 0).atZone(zone).toInstant().toEpochMilli()
        fun due(d: Long, h: Int? = null) = LocalDate.now().plusDays(d).atTime(h ?: 9, 0).atZone(zone).toInstant().toEpochMilli()
        val e = g.db.entries()
        g.scope.launch {
            try {
                listOf(
                    Entry(kind = EntryKind.Note, title = "Standup, Thursday", body = "Release moves to Monday; payment test fails on older phones. Review on Friday.", tags = "work", source = "voice", voiceSeconds = 252, createdAt = day(1)),
                    Entry(kind = EntryKind.Note, title = "Boiler service", body = "Model Vaillant ecoTEC 832. Last service March. Pressure drops below 1 bar overnight.", tags = "home", createdAt = day(3)),
                    Entry(kind = EntryKind.Note, title = "Book list from Mia", body = "Piranesi; The Overstory; Tomorrow, and Tomorrow, and Tomorrow.", tags = "reading", createdAt = day(5)),
                    Entry(kind = EntryKind.Idea, title = "Weekend market stall for old prints", topic = "Side projects", source = "voice", createdAt = day(0, 11)),
                    Entry(kind = EntryKind.Idea, title = "Map of every bakery I've tried", topic = "Side projects", createdAt = day(9)),
                    Entry(kind = EntryKind.Idea, title = "Shelf above the radiator", topic = "Home", createdAt = day(3)),
                    Entry(kind = EntryKind.Idea, title = "Herb box on the balcony", topic = "Home", createdAt = day(18)),
                    Entry(kind = EntryKind.Idea, title = "Essay: why small models feel more personal", topic = "Writing", createdAt = day(1)),
                    Entry(kind = EntryKind.Journal, title = "Slept well. Felt calmer after the walk; less worried about the review.", mood = 1, createdAt = day(0, 9)),
                    Entry(kind = EntryKind.Journal, title = "Long day. Want to say no to the Saturday shift.", mood = 2, createdAt = day(1, 21)),
                    Entry(kind = EntryKind.Journal, title = "Lunch with Mia, laughed a lot.", mood = 1, createdAt = day(1, 14)),
                    Entry(kind = EntryKind.Todo, title = "Renew passport", dueAt = due(-1), createdAt = day(4)),
                    Entry(kind = EntryKind.Todo, title = "Send invoice to Studio K", list = "Work", dueAt = due(0), createdAt = day(2)),
                    Entry(kind = EntryKind.Todo, title = "Fix payment test on Android 14", list = "Work", source = "chat", createdAt = day(1)),
                    Entry(kind = EntryKind.Todo, title = "Book dentist", dueAt = due(4, 15), dueHasTime = true, createdAt = day(1)),
                    Entry(kind = EntryKind.Todo, title = "Water the herbs", list = "Home", done = true, doneAt = System.currentTimeMillis(), dueAt = due(0), createdAt = day(1)),
                    // Today's horizon (Daylight): a few things at set times.
                    Entry(kind = EntryKind.Todo, title = "Call Sam about the seeds", dueAt = due(0, 9) + 30 * 60_000, dueHasTime = true, createdAt = day(1)),
                    Entry(kind = EntryKind.Todo, title = "Dentist, bring the blue folder", dueAt = due(0, 15), dueHasTime = true, createdAt = day(2)),
                    Entry(kind = EntryKind.Todo, title = "Dinner with Ana", list = "Home", dueAt = due(0, 19), dueHasTime = true, createdAt = day(2)),
                ).forEach { e.insert(it) }
            } finally {
                done.finish()
            }
        }
    }
}
