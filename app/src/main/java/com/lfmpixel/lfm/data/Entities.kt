package com.lfmpixel.lfm.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

enum class EntryKind(val label: String) { Note("Note"), Idea("Idea"), Journal("Journal"), Todo("To-do") }

/** Everything captured: notes, ideas, journal lines and to-dos share one table, so one search covers all. */
@Entity(tableName = "entries", indices = [Index("kind"), Index("createdAt"), Index("dueAt"), Index("parentId")])
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
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
    /** A step of another to-do (notebook-spec A.1, proposal 2): it has no date of its own and follows that to-do. */
    @ColumnInfo(defaultValue = "NULL") val parentId: Long? = null,
    /** To-dos: about how long it takes, in minutes; 0 when not known. */
    @ColumnInfo(defaultValue = "0") val minutes: Int = 0,
    /** To-dos: its place in the plan of the day it is due (1, 2, 3); 0 when not in a plan. */
    @ColumnInfo(defaultValue = "0") val planOrder: Int = 0,
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

/**
 * A note's meaning as numbers, for search by meaning (SemanticIndex): [EmbedPrompt.DIM][com.lfmpixel.lfm.llm.EmbedPrompt]
 * little-endian floats from [model], made from the text whose [SemanticIndex.hashOf] is [hash]. Only on the phone, in
 * the notes' own database; never exported or sent.
 */
@Entity(tableName = "vectors")
data class EntryVector(
    @PrimaryKey val entryId: Long,
    val model: String,
    val hash: Long,
    val vec: ByteArray,
)

/**
 * Something Inkling learned (memory/Memory.kt): a fact, a correction, or a line heard in Talk waiting to be read.
 * [type] says which; everything else is in [sealed], AES-GCM under a key in Android's keystore (memory/Sealer.kt), so
 * the table is unreadable without this phone. Forget drops the rows and the key.
 */
@Entity(tableName = "learned", indices = [Index("type")])
data class LearnedRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val sealed: String,
    val updatedAt: Long = System.currentTimeMillis(),
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
    @ColumnInfo(defaultValue = "0") val thoughtMs: Long = 0,    // assistant: how long it thought (its thinking is in raw)
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

/** One request the network module made: the network log under Settings → Privacy. */
@Entity(tableName = "netlog", indices = [Index("at")])
data class NetLogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val host: String,
    val url: String,                // full address (API keys blanked)
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
    val opened: Int = 0,            // result pages asked to open (some may not have answered)
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
