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
