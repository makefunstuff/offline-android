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

    /**
     * One day of the Daily Page, oldest first: what was written that day, to-dos ticked off that day, and to-dos moved
     * to that day (notebook-spec section 2).
     */
    @Query("SELECT * FROM entries WHERE (createdAt >= :from AND createdAt < :to) OR (kind = 'Todo' AND done = 1 AND doneAt >= :from AND doneAt < :to) " +
        "OR (kind = 'Todo' AND done = 0 AND dueAt >= :from AND dueAt < :to) ORDER BY createdAt")
    fun day(from: Long, to: Long): Flow<List<Entry>>

    /** Open to-dos due before [before]: the Daily Page's "Carried over". */
    @Query("SELECT * FROM entries WHERE kind = 'Todo' AND done = 0 AND parentId IS NULL AND dueAt IS NOT NULL AND dueAt < :before ORDER BY dueAt, createdAt")
    fun carried(before: Long): Flow<List<Entry>>

    /** Every open to-do and step: what the Now card and the day plan choose from. */
    @Query("SELECT * FROM entries WHERE kind = 'Todo' AND done = 0 ORDER BY createdAt, id")
    fun open(): Flow<List<Entry>>

    /** The steps of a to-do, done or not, in the order they were written. */
    @Query("SELECT * FROM entries WHERE parentId = :id ORDER BY id")
    fun steps(id: Long): Flow<List<Entry>>
    @Query("SELECT * FROM entries WHERE parentId = :id ORDER BY id") suspend fun stepsNow(id: Long): List<Entry>
    @Query("DELETE FROM entries WHERE parentId = :id") suspend fun deleteSteps(id: Long)
    /** Takes [ids] out of the plan of the day they were in. */
    @Query("UPDATE entries SET planOrder = 0 WHERE planOrder > 0 AND id NOT IN (:keep) AND dueAt >= :from AND dueAt < :to")
    suspend fun unplanOthers(keep: List<Long>, from: Long, to: Long)
    @Query("UPDATE entries SET minutes = :minutes WHERE id = :id") suspend fun setMinutes(id: Long, minutes: Int)
    @Query("SELECT * FROM entries ORDER BY updatedAt DESC LIMIT 1") suspend fun lastEdited(): Entry?
    @Query("SELECT COUNT(*) FROM entries WHERE createdAt >= :since") suspend fun countSince(since: Long): Int

    /** [match] is an FTS4 MATCH expression; see [FtsQuery]. */
    @Query("SELECT entries.* FROM entries JOIN entries_fts ON entries.id = entries_fts.rowid WHERE entries_fts MATCH :match ORDER BY entries.createdAt DESC LIMIT :limit")
    suspend fun search(match: String, limit: Int = 50): List<Entry>
    @Query("SELECT * FROM entries ORDER BY createdAt") suspend fun all(): List<Entry>
    /** Changes whenever an entry is added, edited or deleted: what wakes the search index. */
    @Query("SELECT MAX(updatedAt) + COUNT(*) + SUM(id) FROM entries") fun changes(): Flow<Long?>
}

@Dao
interface VectorDao {
    @androidx.room.Upsert suspend fun upsert(v: List<EntryVector>)
    @Query("SELECT * FROM vectors") suspend fun all(): List<EntryVector>
    @Query("SELECT entryId, hash, model FROM vectors") suspend fun keys(): List<VectorKey>
    @Query("DELETE FROM vectors WHERE entryId IN (:ids)") suspend fun delete(ids: List<Long>)
    @Query("SELECT COUNT(*) FROM vectors") suspend fun count(): Int
}

data class VectorKey(val entryId: Long, val hash: Long, val model: String)

@Dao
interface LearnedDao {
    @Insert suspend fun insert(r: LearnedRow): Long
    @Update suspend fun update(r: LearnedRow)
    @Query("SELECT * FROM learned WHERE type = :type ORDER BY id") suspend fun ofType(type: String): List<LearnedRow>
    @Query("DELETE FROM learned WHERE id IN (:ids)") suspend fun delete(ids: List<Long>)
    @Query("DELETE FROM learned") suspend fun deleteAll()
}

@Dao
interface MessageDao {
    @Insert suspend fun insert(m: ChatMessage): Long
    @Update suspend fun update(m: ChatMessage)
    @Query("SELECT * FROM messages WHERE threadId = :thread ORDER BY id") fun thread(thread: Long): Flow<List<ChatMessage>>
    @Query("SELECT * FROM messages WHERE threadId = :thread ORDER BY id") suspend fun threadNow(thread: Long): List<ChatMessage>
    @Query("SELECT COALESCE(MAX(threadId), 1) FROM messages") suspend fun lastThread(): Long
    /** Changes whenever a message is written: what wakes the memory's quick pass. */
    @Query("SELECT MAX(id) FROM messages") fun lastId(): Flow<Long?>
    @Query("DELETE FROM messages") suspend fun deleteAll()
    @Query("SELECT * FROM messages ORDER BY threadId, id") suspend fun all(): List<ChatMessage>
    /** What the owner wrote in chats since [since], oldest first: what the memory reads. */
    @Query("SELECT * FROM messages WHERE role = 'User' AND createdAt > :since ORDER BY id") suspend fun userSince(since: Long): List<ChatMessage>
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
    SearchRecord::class, EntryVector::class, LearnedRow::class], version = 8, exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7), AutoMigration(from = 7, to = 8)])
@TypeConverters(Converters::class)
abstract class LfmDatabase : RoomDatabase() {
    abstract fun entries(): EntryDao
    abstract fun messages(): MessageDao
    abstract fun actions(): ActionDao
    abstract fun reminders(): ReminderDao
    abstract fun netLog(): NetLogDao
    abstract fun searches(): SearchDao
    abstract fun vectors(): VectorDao
    abstract fun learned(): LearnedDao

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
