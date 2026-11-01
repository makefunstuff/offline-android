package com.lfmpixel.lfm.data

import android.os.SystemClock
import com.lfmpixel.lfm.device.Heat
import com.lfmpixel.lfm.llm.EmbedPrompt
import com.lfmpixel.lfm.llm.Embedder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

/**
 * Search by meaning (notebook-spec section 5, docs/embeddings.md): every entry's vector from EmbeddingGemma 2, kept in
 * the notes' own database ([EntryVector]) and in memory, brought up to date in the background a moment after anything
 * is written, edited or deleted. Only what changed is embedded again (by a hash of its text). Nothing leaves the
 * phone. While the phone is hot, indexing waits. Without the model, [search] is keyword search as before.
 *
 * [embedder] gives the loaded model, or null when its file is not here; [installed] says whether it is.
 */
class SemanticIndex(
    private val db: LfmDatabase,
    private val embedder: suspend () -> Embedder?,
    private val modelName: () -> String?,
    private val heat: StateFlow<Heat>,
    private val scope: CoroutineScope,
) {
    data class Status(
        val ready: Boolean = false,
        val entries: Int = 0,
        val indexed: Int = 0,
        val bytes: Long = 0,
        /** Mean time to embed one entry in the last batch, ms; 0 before the first. */
        val msPerEntry: Double = 0.0,
        /** Time of the last search, embedding the query included, ms. */
        val lastSearchMs: Long = 0,
        val working: Boolean = false,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status
    private val lock = Mutex()
    /** entryId → unit vector; loaded from the database on the first sync. A finished copy: [search] reads it unlocked. */
    @Volatile private var vectors: Map<Long, FloatArray>? = null

    @OptIn(FlowPreview::class)
    fun start(models: kotlinx.coroutines.flow.Flow<*>) {
        scope.launch(Dispatchers.Default) {
            combine(db.entries().changes(), heat, models) { _, h, _ -> h }.debounce(1_500).collect { h ->
                if (h < Heat.Hot) runCatching { sync() }.onFailure { android.util.Log.w("lfm-embed", "index: ${it.message}") }
            }
        }
    }

    /** Embeds what is new or changed and forgets what was deleted. */
    suspend fun sync() = lock.withLock {
        val model = modelName() ?: run { _status.value = _status.value.copy(ready = false); return@withLock }
        val entries = db.entries().all()
        val keys = db.vectors().keys().associateBy { it.entryId }
        val plan = plan(entries.map { it.id to hashOf(model, it) }, keys.values.toList(), model)
        if (plan.delete.isNotEmpty()) db.vectors().delete(plan.delete)
        val mem = (vectors ?: db.vectors().all().filter { it.model == model }.associate { it.entryId to HybridSearch.unpack(it.vec) })
            .toMutableMap().apply { val ids = entries.mapTo(HashSet()) { it.id }; this.keys.retainAll(ids) }
        vectors = HashMap(mem)
        publish(entries.size, model)
        if (plan.embed.isEmpty()) return@withLock
        val e = embedder() ?: return@withLock
        val byId = entries.associateBy { it.id }
        _status.value = _status.value.copy(working = true)
        try {
            for (batch in plan.embed.chunked(BATCH)) {
                if (heat.value >= Heat.Hot) break
                val todo = batch.mapNotNull { byId[it] }
                val t0 = SystemClock.elapsedRealtime()
                val out = e.embed(todo.map { textOf(it) })
                val ms = (SystemClock.elapsedRealtime() - t0).toDouble() / todo.size
                db.vectors().upsert(todo.zip(out).map { (en, v) -> EntryVector(en.id, model, hashOf(model, en), HybridSearch.pack(v)) })
                todo.zip(out).forEach { (en, v) -> mem[en.id] = v }
                vectors = HashMap(mem)
                _status.value = _status.value.copy(msPerEntry = ms)
                publish(entries.size, model)
                yield()
            }
        } finally {
            _status.value = _status.value.copy(working = false)
        }
    }

    private fun publish(entries: Int, model: String) {
        val n = vectors?.size ?: 0
        _status.value = _status.value.copy(ready = true, entries = entries, indexed = n, bytes = n.toLong() * EmbedPrompt.DIM * 4)
        android.util.Log.i("lfm-embed", "index $n of $entries entries ($model)")
    }

    /**
     * Keyword hits and meaning matches, fused: an entry found both ways comes first. [limit] is the most returned.
     * With no model or an empty index, keyword search alone.
     */
    suspend fun search(query: String, limit: Int = 50): List<Entry> {
        val t0 = SystemClock.elapsedRealtime()
        val keyword = FtsQuery.from(query)?.let { db.entries().search(it, limit) }.orEmpty()
        val mem = vectors
        if (query.isBlank() || mem.isNullOrEmpty() || modelName() == null) return keyword
        val e = runCatching { embedder() }.getOrNull() ?: return keyword
        val q = runCatching { e.embed(listOf(EmbedPrompt.query(query))).first() }.getOrElse { return keyword }
        val near = HybridSearch.nearest(q, mem, limit).map { it.first }
        val ids = HybridSearch.fuse(keyword.map { it.id }, near).take(limit)
        val known = keyword.associateBy { it.id }
        val found = ids.mapNotNull { known[it] ?: db.entries().get(it) }
        _status.value = _status.value.copy(lastSearchMs = SystemClock.elapsedRealtime() - t0)
        android.util.Log.i("lfm-embed", "search \"${query.take(40)}\": ${keyword.size} by words, ${near.size} by meaning, " +
            "${found.size} in ${_status.value.lastSearchMs} ms")
        return found
    }

    /** What to embed and what to drop, given each entry's id and text hash and what the index holds. */
    data class Plan(val embed: List<Long>, val delete: List<Long>)

    companion object {
        private const val BATCH = 8

        fun textOf(e: Entry): String = EmbedPrompt.document(e.title,
            listOf(e.body, e.topic, e.list, e.tags).filter { it.isNotBlank() }.joinToString("\n"))

        /** 64-bit FNV-1a of the model and the text it is given: a new model or an edit means a new vector. */
        fun hashOf(model: String, e: Entry): Long {
            var h = -0x340d631b7bdddcdbL
            for (c in model + "\u0000" + textOf(e)) { h = h xor c.code.toLong(); h *= 0x100000001b3L }
            return h
        }

        fun plan(entries: List<Pair<Long, Long>>, held: List<VectorKey>, model: String): Plan {
            val have = held.associateBy { it.entryId }
            val ids = entries.mapTo(HashSet()) { it.first }
            val embed = entries.filter { (id, hash) -> have[id]?.let { it.hash != hash || it.model != model } ?: true }.map { it.first }
            val delete = held.filter { it.entryId !in ids }.map { it.entryId }
            return Plan(embed, delete)
        }
    }
}
