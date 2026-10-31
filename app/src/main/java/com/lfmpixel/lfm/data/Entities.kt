package com.lfmpixel.lfm.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

enum class EntryKind(val label: String) { Note("Note"), Idea("Idea"), Journal("Journal"), Todo("To-do") }

/** Everything captured: notes, ideas, journal lines and to-dos share one table, so one search covers all. */
@Entity(tableName = "entries", indices = [Index("kind"), Index("createdAt"), Index("dueAt")])
data class Entry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: EntryKind,
    val title: String,
    val body: String = "",
    val tags: String = "",          // space separated
    val topic: String = "",         // ideas are grouped by topic
    val list: String = "",          // to-dos: "Shopping", "Work"...
    val source: String = "typed",   // typed, voice, chat
    val mood: Int = 0,              // journal: 0 none, 1 good, 2 low
    val dueAt: Long? = null,        // to-dos, epoch millis
    val dueHasTime: Boolean = false,
    val done: Boolean = false,
    val doneAt: Long? = null,
    val voiceSeconds: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Fts4(contentEntity = Entry::class)
@Entity(tableName = "entries_fts")
data class EntryFts(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Long,
    val title: String,
    val body: String,
    val tags: String,
    val topic: String,
)

enum class Role { User, Assistant, Tool, Notice }

/** One line of the chat. Assistant lines keep the model's raw output so the next prompt matches the context. */
@Entity(tableName = "messages", indices = [Index("threadId")])
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val threadId: Long = 1,
    val role: Role,
    val text: String,               // what the owner sees
    val raw: String = "",           // assistant: model output; tool: the tool_response body
    val cardLabel: String = "",     // tool cards: "Reminder set"
    val cardTitle: String = "",
    val cardDetail: String = "",
    val actionId: Long? = null,     // ActionRecord for undo
    val voiceSeconds: Int = 0,
    val meta: String = "",          // "LFM3-1.7B · on this phone"
    val createdAt: Long = System.currentTimeMillis(),
    val searchId: Long? = null,     // a web search card (-1: no search was made), or an answer written from one
    @ColumnInfo(defaultValue = "") val media: String = "",      // what the owner showed: Photo, Video, Document (SeeKind)
    @ColumnInfo(defaultValue = "") val mediaPath: String = "",  // its small JPEG in the app's files
    @ColumnInfo(defaultValue = "") val mediaLabel: String = "", // "Video · 0:06", "Lease.pdf · 3 pages"
)

enum class ActionStatus { Done, Undone, Cancelled, Failed, Opened }

/** The action history: every tool run, kept on the phone. */
@Entity(tableName = "actions", indices = [Index("at")])
data class ActionRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tool: String,
    val title: String,
    val detail: String,
    val argsJson: String,
    val status: ActionStatus,
    val undo: String = "",          // how to undo: "entry:12", "reminder:3", "event:44", "torch:false", "ringer:2"
    val askedFirst: Boolean = false,
    val at: Long = System.currentTimeMillis(),
)

@Entity(tableName = "reminders", indices = [Index("at")])
data class Reminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val at: Long,
    val fired: Boolean = false,
    val cancelled: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

/** One request LFM Net made for LFM: the network log under Settings → Privacy. */
@Entity(tableName = "netlog", indices = [Index("at")])
data class NetLogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val host: String,
    val url: String,                // full address (API keys blanked by LFM Net)
    val label: String,              // "“plumber call-out fee”", "Opened source 1", "Model: LFM3-VL-2B"
    val sent: String,               // what went out beyond the address: the search words, a file name
    val bytes: Long,                // received
    val ok: Boolean,
)

/** One web search: the words sent, where, what came back and which pages were read. */
@Entity(tableName = "searches")
data class SearchRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val threadId: Long,
    val query: String,
    val provider: String,           // "your SearXNG + Wikipedia"
    val sources: String,            // SearchSource.encode
    val personalSent: String = "",  // personal words the owner chose to send anyway
    val answer: String = "",        // the answer written from it
    val opened: Int = 0,            // result pages LFM Net was asked to open (some may not have answered)
    val at: Long = System.currentTimeMillis(),
)

/** A numbered source of a search answer. [read] is whether its page was opened, not only its snippet used. */
data class SearchSource(val n: Int, val title: String, val url: String, val snippet: String, val read: Boolean) {
    val host get() = url.substringAfter("://").substringBefore('/').substringBefore(':').removePrefix("www.")

    companion object {
        private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')
        fun encode(list: List<SearchSource>) = list.joinToString("\n") { listOf(it.n.toString(), clean(it.title), clean(it.url), clean(it.snippet), if (it.read) "1" else "0").joinToString("\t") }
        fun decode(s: String) = s.lines().mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 5) null else SearchSource(f[0].toIntOrNull() ?: return@mapNotNull null, f[1], f[2], f[3], f[4] == "1")
        }
    }
}
