package com.lfmpixel.lfm.data

import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Everything Wren keeps, in one ZIP the owner saves where they choose (spec section 3: data leaves only when you
 * tap Export). Markdown to read, and `wren.json` with every row of every table, to keep or move. Models,
 * pictures and settings are not in it.
 */
object Export {
    /** The tables, in the order they are written; the full-text index is left out (it is rebuilt from entries). */
    val TABLES = listOf("entries", "messages", "actions", "reminders", "searches", "netlog")

    suspend fun write(db: LfmDatabase, out: OutputStream, zone: ZoneId = ZoneId.systemDefault(), now: Instant = Instant.now()) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { writeNow(db, out, zone, now) }

    private suspend fun writeNow(db: LfmDatabase, out: OutputStream, zone: ZoneId, now: Instant) {
        val entries = db.entries().all()
        val messages = db.messages().all()
        val day = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm", Locale.ENGLISH).withZone(zone)
        ZipOutputStream(out).use { zip ->
            fun file(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            file("README.txt", "Exported from Wren on ${day.format(now)}.\n\n" +
                "notes.md, ideas.md, journal.md, todos.md and chats.md are for reading.\n" +
                "wren.json holds every row of every table (${TABLES.joinToString(", ")}), with times in milliseconds since 1970.\n" +
                "Models, pictures and settings are not included.\n")
            for (kind in EntryKind.entries) {
                val list = entries.filter { it.kind == kind }
                file(kind.fileName(), entriesMarkdown(kind, list, day))
            }
            file("chats.md", chatsMarkdown(messages, day))
            file("wren.json", tablesJson(db))
        }
    }

    private fun EntryKind.fileName() = when (this) {
        EntryKind.Note -> "notes.md"; EntryKind.Idea -> "ideas.md"; EntryKind.Journal -> "journal.md"; EntryKind.Todo -> "todos.md"
    }

    fun entriesMarkdown(kind: EntryKind, list: List<Entry>, day: DateTimeFormatter): String = buildString {
        append("# ").append(when (kind) { EntryKind.Note -> "Notes"; EntryKind.Idea -> "Ideas"; EntryKind.Journal -> "Journal"; EntryKind.Todo -> "To-dos" }).append("\n")
        if (list.isEmpty()) append("\nNothing yet.\n")
        for (e in list) {
            append("\n## ")
            if (kind == EntryKind.Todo) append(if (e.done) "[x] " else "[ ] ")
            append(e.title).append("\n\n")
            val facts = listOfNotNull(
                day.format(Instant.ofEpochMilli(e.createdAt)),
                e.dueAt?.let { "due " + day.format(Instant.ofEpochMilli(it)) },
                e.list.ifEmpty { null }, e.topic.ifEmpty { null },
                e.tags.ifBlank { null }?.split(' ')?.joinToString(" ") { "#$it" },
                when (e.mood) { 1 -> "good day"; 2 -> "low day"; else -> null },
                if (e.pinned) "pinned" else null,
            )
            append("_").append(facts.joinToString(" · ")).append("_\n")
            if (e.body.isNotBlank() && e.body != e.title) append("\n").append(e.body.trim()).append("\n")
        }
    }

    fun chatsMarkdown(messages: List<ChatMessage>, day: DateTimeFormatter): String = buildString {
        append("# Chats\n")
        if (messages.isEmpty()) append("\nNothing yet.\n")
        messages.groupBy { it.threadId }.forEach { (_, thread) ->
            append("\n## ").append(day.format(Instant.ofEpochMilli(thread.first().createdAt))).append("\n")
            for (m in thread) {
                val line = when (m.role) {
                    Role.User -> "**You:** " + (if (m.media.isNotEmpty()) "[${m.mediaLabel.ifEmpty { m.media }}] " else "") + m.text
                    Role.Assistant -> "**Wren:** " + m.text
                    Role.Tool -> "_" + listOf(m.cardLabel, m.cardTitle, m.cardDetail).filter { it.isNotEmpty() }.joinToString(" · ") + "_"
                    Role.Notice -> "_" + m.text + "_"
                }
                if (line.isNotBlank()) append("\n").append(line.trim()).append("\n")
            }
        }
    }

    /** Every table, row by row, column names as they are in the database. */
    private fun tablesJson(db: LfmDatabase): String {
        val root = org.json.JSONObject().put("app", "Wren").put("version", db.openHelper.readableDatabase.version)
        val sql = db.openHelper.readableDatabase
        for (t in TABLES) {
            val rows = org.json.JSONArray()
            sql.query("SELECT * FROM $t").use { c ->
                while (c.moveToNext()) {
                    val o = org.json.JSONObject()
                    for (i in 0 until c.columnCount) {
                        when (c.getType(i)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> o.put(c.getColumnName(i), org.json.JSONObject.NULL)
                            android.database.Cursor.FIELD_TYPE_INTEGER -> o.put(c.getColumnName(i), c.getLong(i))
                            android.database.Cursor.FIELD_TYPE_FLOAT -> o.put(c.getColumnName(i), c.getDouble(i))
                            else -> o.put(c.getColumnName(i), c.getString(i))
                        }
                    }
                    rows.put(o)
                }
            }
            root.put(t, rows)
        }
        return root.toString(1)
    }
}
