package com.lfmpixel.lfm.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface EntryDao {
    @Insert suspend fun insert(e: Entry): Long
    @Update suspend fun update(e: Entry)
    @Query("DELETE FROM entries WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM entries WHERE id = :id") suspend fun get(id: Long): Entry?
    @Query("SELECT * FROM entries WHERE id = :id") fun watch(id: Long): Flow<Entry?>

    @Query("SELECT * FROM entries WHERE kind = :kind ORDER BY pinned DESC, createdAt DESC")
    fun byKind(kind: EntryKind): Flow<List<Entry>>

    /** Notes, ideas and journal: pinned first, then the newest. */
    @Query("SELECT * FROM entries WHERE kind != 'Todo' ORDER BY pinned DESC, createdAt DESC")
    fun library(): Flow<List<Entry>>

    @Query("SELECT * FROM entries WHERE kind != 'Todo' OR done = 0 ORDER BY createdAt DESC LIMIT :n")
    fun latest(n: Int): Flow<List<Entry>>

    @Query("SELECT * FROM entries WHERE kind = 'Todo' ORDER BY done, CASE WHEN dueAt IS NULL THEN 1 ELSE 0 END, dueAt, createdAt DESC")
    fun todos(): Flow<List<Entry>>

    /** [match] is an FTS4 MATCH expression; see [FtsQuery]. */
    @Query("SELECT entries.* FROM entries JOIN entries_fts ON entries.id = entries_fts.rowid WHERE entries_fts MATCH :match ORDER BY entries.createdAt DESC LIMIT :limit")
    suspend fun search(match: String, limit: Int = 50): List<Entry>
}

@Dao
interface MessageDao {
    @Insert suspend fun insert(m: ChatMessage): Long
    @Update suspend fun update(m: ChatMessage)
    @Query("SELECT * FROM messages WHERE threadId = :thread ORDER BY id") fun thread(thread: Long): Flow<List<ChatMessage>>
    @Query("SELECT * FROM messages WHERE threadId = :thread ORDER BY id") suspend fun threadNow(thread: Long): List<ChatMessage>
    @Query("SELECT COALESCE(MAX(threadId), 1) FROM messages") suspend fun lastThread(): Long
    @Query("DELETE FROM messages") suspend fun deleteAll()
}

@Dao
interface ActionDao {
    @Insert suspend fun insert(a: ActionRecord): Long
    @Update suspend fun update(a: ActionRecord)
    @Query("SELECT * FROM actions WHERE id = :id") suspend fun get(id: Long): ActionRecord?
    @Query("SELECT * FROM actions ORDER BY at DESC LIMIT 200") fun recent(): Flow<List<ActionRecord>>
    @Query("SELECT * FROM actions WHERE id = :id") fun watch(id: Long): Flow<ActionRecord?>
}

@Dao
interface ReminderDao {
    @Insert suspend fun insert(r: Reminder): Long
    @Update suspend fun update(r: Reminder)
    @Query("SELECT * FROM reminders WHERE id = :id") suspend fun get(id: Long): Reminder?
    @Query("SELECT * FROM reminders WHERE cancelled = 0 AND fired = 0 ORDER BY at") suspend fun pending(): List<Reminder>
    @Query("SELECT * FROM reminders WHERE cancelled = 0 AND at >= :from AND at < :to ORDER BY at") fun between(from: Long, to: Long): Flow<List<Reminder>>
}

@Dao
interface NetLogDao {
    @Insert suspend fun insertAll(e: List<NetLogEntry>)
    @Insert suspend fun insert(e: NetLogEntry): Long
    @Query("DELETE FROM netlog WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM netlog WHERE at >= :from ORDER BY at DESC, id DESC") fun since(from: Long): Flow<List<NetLogEntry>>
    @Query("SELECT * FROM netlog ORDER BY at DESC, id DESC") suspend fun all(): List<NetLogEntry>
    @Query("DELETE FROM netlog") suspend fun deleteAll()
}

@Dao
interface SearchDao {
    @Insert suspend fun insert(s: SearchRecord): Long
    @Update suspend fun update(s: SearchRecord)
    @Query("SELECT * FROM searches WHERE id = :id") suspend fun get(id: Long): SearchRecord?
    @Query("SELECT * FROM searches WHERE id = :id") fun watch(id: Long): Flow<SearchRecord?>
    @Query("SELECT * FROM searches WHERE threadId = :thread") fun inThread(thread: Long): Flow<List<SearchRecord>>
    @Query("DELETE FROM searches") suspend fun deleteAll()
}

class Converters {
    @TypeConverter fun kind(k: EntryKind) = k.name
    @TypeConverter fun kind(s: String) = EntryKind.valueOf(s)
    @TypeConverter fun role(r: Role) = r.name
    @TypeConverter fun toRole(s: String) = Role.valueOf(s)
    @TypeConverter fun status(s: ActionStatus) = s.name
    @TypeConverter fun toStatus(s: String) = ActionStatus.valueOf(s)
}

@Database(entities = [Entry::class, EntryFts::class, ChatMessage::class, ActionRecord::class, Reminder::class, NetLogEntry::class,
    SearchRecord::class], version = 4, exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4)])
@TypeConverters(Converters::class)
abstract class LfmDatabase : RoomDatabase() {
    abstract fun entries(): EntryDao
    abstract fun messages(): MessageDao
    abstract fun actions(): ActionDao
    abstract fun reminders(): ReminderDao
    abstract fun netLog(): NetLogDao
    abstract fun searches(): SearchDao

    companion object {
        fun open(context: Context): LfmDatabase =
            Room.databaseBuilder(context, LfmDatabase::class.java, "lfm.db").build()

        fun inMemory(context: Context): LfmDatabase =
            Room.inMemoryDatabaseBuilder(context, LfmDatabase::class.java).allowMainThreadQueries().build()
    }
}

/** Turns what someone types into a safe FTS4 query: each word as a prefix, all words required. */
object FtsQuery {
    fun from(text: String): String? {
        val words = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }.take(8)
        if (words.isEmpty()) return null
        return words.joinToString(" ") { "$it*" }
    }
}
