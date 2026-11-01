package com.lfmpixel.lfm.memory

import com.lfmpixel.lfm.data.Entry
import com.lfmpixel.lfm.data.EntryKind
import com.lfmpixel.lfm.data.LearnedRow
import com.lfmpixel.lfm.data.LfmDatabase
import com.lfmpixel.lfm.llm.EmbedPrompt
import com.lfmpixel.lfm.llm.Embedder
import com.lfmpixel.lfm.llm.PromptFormat
import com.lfmpixel.lfm.llm.PromptMessage
import com.lfmpixel.lfm.llm.TextSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Inkling's memory on the phone (notebook-spec section 6): facts, corrections and patterns, each row sealed by
 * [sealer] in the notes' own database. Rules read every new entry, chat message and Talk turn a moment after it is
 * written; the nightly pass ([nightly], while charging) also asks the small model, merges duplicates, lets stale facts
 * go and finds patterns. [context] puts what fits a question into its prompt.
 *
 * [learning] is the owner's switch: off, nothing is learned and nothing learned is used (notes still are).
 */
class MemoryStore(
    private val db: LfmDatabase,
    private val sealer: Sealer,
    private val embedder: suspend () -> Embedder?,
    /** Library search (keywords and meaning, LFMP-10). */
    private val search: suspend (String, Int) -> List<Entry>,
    private val journalLocked: () -> Boolean,
    private val learning: suspend () -> Boolean,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private var loaded = false
    private val _facts = MutableStateFlow<List<Fact>>(emptyList())
    val facts: StateFlow<List<Fact>> = _facts
    private val _corrections = MutableStateFlow<List<Correction>>(emptyList())
    val corrections: StateFlow<List<Correction>> = _corrections
    private val _patterns = MutableStateFlow<List<String>>(emptyList())
    val patterns: StateFlow<List<String>> = _patterns
    /** What the last nightly pass did and how long it took, for the screen and the log. */
    private val _lastRun = MutableStateFlow("")
    val lastRun: StateFlow<String> = _lastRun
    val working = MutableStateFlow(false)

    /** Watermarks: entries changed and chat messages written after these were not read yet. */
    private var meta = Meta()
    private var metaId = 0L

    private data class Meta(val entriesSeen: Long = 0, val chatSeen: Long = 0, val modelSeen: Long = 0, val lastNight: Long = 0,
                            val patterns: List<String> = emptyList(), val report: String = "") {
        fun json(): String = JSONObject().put("entries", entriesSeen).put("chat", chatSeen).put("model", modelSeen).put("night", lastNight)
            .put("patterns", JSONArray(patterns)).put("report", report).toString()

        companion object {
            fun of(s: String) = runCatching {
                val o = JSONObject(s)
                val p = o.optJSONArray("patterns") ?: JSONArray()
                Meta(o.optLong("entries"), o.optLong("chat"), o.optLong("model"), o.optLong("night"), List(p.length()) { p.getString(it) }, o.optString("report"))
            }.getOrNull()
        }
    }

    /** Reads every sealed row once. Rows that can't be opened (the key is gone) are dropped. */
    suspend fun load() = lock.withLock { loadLocked() }

    private suspend fun loadLocked() {
        if (loaded) return
        val bad = mutableListOf<Long>()
        fun open(r: LearnedRow): String? = sealer.open(r.sealed).also { if (it == null) bad += r.id }
        _facts.value = db.learned().ofType(FACT).mapNotNull { r -> open(r)?.let { Fact.fromJson(r.id, it) } }
        _corrections.value = db.learned().ofType(CORRECTION).mapNotNull { r -> open(r)?.let { Correction.fromJson(r.id, it) } }
        db.learned().ofType(META).firstOrNull()?.let { r -> open(r)?.let(Meta::of)?.let { meta = it; metaId = r.id } }
        _patterns.value = meta.patterns
        _lastRun.value = meta.report
        if (bad.isNotEmpty()) db.learned().delete(bad)
        loaded = true
    }

    /** Learns from what is written, a few seconds after it settles; [messages] changes when the chat does. */
    @OptIn(FlowPreview::class)
    fun start(messages: Flow<*>) {
        scope.launch(Dispatchers.Default) {
            combine(db.entries().changes(), messages) { a, b -> a to b }.debounce(3_000).collect {
                runCatching { learnNow() }.onFailure { android.util.Log.w(TAG, "learn: ${it.message}") }
            }
        }
    }

    /**
     * The quick pass, by rules only: entries changed and chat messages written since the last pass, and Talk turns
     * waiting. Returns the number of facts found.
     */
    suspend fun learnNow(): Int = lock.withLock {
        loadLocked()
        if (!learning()) return@withLock 0
        val t = now()
        var facts = _facts.value
        var found = 0
        val locked = journalLocked()
        val entries = db.entries().all().filter { it.updatedAt > meta.entriesSeen }
        for (e in entries) {
            val journal = e.kind == EntryKind.Journal
            for (f in FactRules.find(listOf(e.title, e.body).filter { it.isNotBlank() }.joinToString(". "))) {
                facts = FactMerge.merge(facts, Fact(kind = f.kind, text = f.text, key = f.key, source = Source("entry:${e.id}", label(e), e.updatedAt), journal = journal))
                found++
            }
        }
        val chat = db.messages().userSince(meta.chatSeen)
        for (m in chat) for (f in FactRules.find(m.text, chat = true)) {
            facts = FactMerge.merge(facts, Fact(kind = f.kind, text = f.text, key = f.key, source = Source("chat:${m.id}", "Chat · “${m.text.take(40)}”", m.createdAt)))
            found++
        }
        val said = db.learned().ofType(SAID)
        for (r in said) {
            val o = sealer.open(r.sealed)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: continue
            val text = o.optString("text"); val at = o.optLong("at")
            for (f in FactRules.find(text, chat = true)) {
                facts = FactMerge.merge(facts, Fact(kind = f.kind, text = f.text, key = f.key, source = Source("talk", "Talk · “${text.take(40)}”", at)))
                found++
            }
        }
        if (said.isNotEmpty()) db.learned().delete(said.map { it.id })
        meta = meta.copy(entriesSeen = maxOf(meta.entriesSeen, entries.maxOfOrNull { it.updatedAt } ?: 0), chatSeen = maxOf(meta.chatSeen, chat.maxOfOrNull { it.createdAt } ?: 0))
        saveFacts(facts)
        saveMeta()
        if (found > 0) android.util.Log.i(TAG, "learned $found by rules in ${now() - t} ms (journal locked: $locked)")
        found
    }

    /** A Talk turn the owner said, kept sealed until the next pass reads it. */
    suspend fun heard(text: String) {
        if (text.isBlank() || !learning()) return
        db.learned().insert(LearnedRow(type = SAID, sealed = sealer.seal(JSONObject().put("text", text).put("at", now()).toString())))
    }

    /**
     * The nightly pass: the quick pass, then the small model reads what changed since its last run (when one can be
     * reached: [session]; on the server, nothing sensitive and nothing from the journal is sent), then duplicates are
     * merged, stale facts let go, missing vectors made and patterns found. Returns what it did, with timings.
     */
    suspend fun nightly(session: (suspend () -> TextSession?)? = null): String {
        if (!learning()) return "Learning is off"
        working.value = true
        try {
            fun ms() = System.nanoTime() / 1_000_000
            val t0 = ms()
            val byRules = learnNow()
            val t1 = ms()
            var byModel = 0
            var read = 0
            val s = session?.let { runCatching { it() }.getOrNull() }
            if (s != null) {
                val server = s is com.lfmpixel.lfm.edge.EdgeSession
                val todo = lock.withLock { db.entries().all() }.filter {
                    it.updatedAt > meta.modelSeen && it.kind != EntryKind.Todo && (it.title + it.body).length >= 20 &&
                        !(server && (it.kind == EntryKind.Journal || Sensitive.looks(it.title + " " + it.body)))
                }.sortedBy { it.updatedAt }.takeLast(MODEL_ENTRIES)
                for (batch in todo.chunked(MODEL_BATCH)) {
                    val lines = batch.mapIndexed { i, e -> (i + 1) to listOf(e.title, e.body).filter { it.isNotBlank() }.joinToString(": ") }
                    val prompt = PromptFormat.build(FactPrompt.SYSTEM, emptyList(), listOf(PromptMessage(PromptMessage.Role.User, FactPrompt.user(lines))), thinking = false)
                    val out = StringBuilder()
                    val ok = runCatching { withTimeoutOrNull(60_000) { s.generate(prompt, 300, temp = 0.2f).collect { out.append(it) } } }.isSuccess
                    if (!ok) break
                    read += batch.size
                    lock.withLock {
                        var facts = _facts.value
                        for ((kind, text, n) in FactPrompt.parse(out.toString().substringAfter("</think>"))) {
                            val e = batch.getOrNull(n - 1) ?: continue
                            facts = FactMerge.merge(facts, Fact(kind = kind, text = text, source = Source("entry:${e.id}", label(e), e.updatedAt), journal = e.kind == EntryKind.Journal))
                            byModel++
                        }
                        saveFacts(facts)
                        meta = meta.copy(modelSeen = maxOf(meta.modelSeen, batch.maxOf { it.updatedAt }))
                        saveMeta()
                    }
                }
            }
            val t2 = ms()
            val (before, after, embedded) = lock.withLock {
                val entries = db.entries().all()
                val before = _facts.value.size
                var facts = FactMerge.prune(FactMerge.dedupe(_facts.value), entries.mapTo(HashSet()) { it.id }, now())
                val e = runCatching { embedder() }.getOrNull()
                var embedded = 0
                if (e != null) {
                    val missing = facts.filter { it.vec == null }
                    if (missing.isNotEmpty()) runCatching {
                        val vecs = e.embed(missing.map { EmbedPrompt.document(it.kind.label, it.text) })
                        val byText = missing.zip(vecs).associate { (f, v) -> f.text to v }
                        facts = facts.map { f -> if (f.vec == null) f.copy(vec = byText[f.text]) else f }
                        embedded = missing.size
                    }
                }
                saveFacts(facts)
                _patterns.value = Patterns.find(entries.filter { !(journalLocked() && it.kind == EntryKind.Journal) }, now()).lines()
                Triple(before, facts.size, embedded)
            }
            val t3 = ms()
            val report = "Read ${read} entries with the model; $byRules facts by rules, $byModel by the model; " +
                "$before → $after facts after merging; $embedded embedded. Took ${t3 - t0} ms (rules ${t1 - t0}, model ${t2 - t1}, tidy ${t3 - t2})."
            lock.withLock {
                meta = meta.copy(lastNight = now(), patterns = _patterns.value, report = report)
                _lastRun.value = report
                saveMeta()
            }
            android.util.Log.i(TAG, "nightly: $report")
            return report
        } finally {
            working.value = false
        }
    }

    /** The blocks for one answer: [system] goes into the system prompt, [turn] after the owner's message. */
    data class Context(val system: String, val turn: String, val ms: Long)

    /**
     * What fits [question], within [contextTokens]'s budget ([Recall.budget]): the profile (when learning is on) and the
     * facts and notes nearest to it. The journal is left out while it is locked. Nothing here leaves the phone except
     * in the prompt of this one answer.
     */
    suspend fun context(question: String, contextTokens: Int, notes: Boolean = true): Context {
        val t0 = now()
        load()
        val b = Recall.budget(contextTokens)
        val locked = journalLocked()
        val on = learning()
        val facts = if (on) _facts.value else emptyList()
        val profile = if (on) Recall.profile(facts, _patterns.value, b.profileChars, locked, now()) else ""
        val q = question.trim()
        if (q.length < 4) return Context(Recall.systemBlock(profile), "", now() - t0)
        val qv = if (on && facts.any { it.vec != null }) runCatching { embedder()?.embed(listOf(EmbedPrompt.query(q)))?.firstOrNull() }.getOrNull() else null
        val inProfile = profile.lines().map { it.removePrefix("- ") }.toSet()
        val near = Recall.relevant(facts, q, qv, locked)
        val relevant = near.filter { it.text !in inProfile }.take(3)
        // The memory helps find notes too: "my sister" also looks for "Ana" when Inkling knows Ana is your sister.
        val names = Recall.names(near.take(2), q)
        val found = if (notes && Words.content(q).isNotEmpty()) runCatching {
            (search(q, b.notes + 2) + names.flatMap { search(it, 2) }).distinctBy { it.id }
        }.getOrDefault(emptyList()).filter { !(locked && it.kind == EntryKind.Journal) }.take(b.notes) else emptyList()
        return Context(Recall.systemBlock(profile), Recall.turnBlock(relevant, Recall.notes(found, b.notesChars)), now() - t0)
    }

    /** Where the owner put a line like [line] last time (an InkKind name), or null. */
    suspend fun learnedKind(line: String): String? =
        if (line.isBlank() || !learning()) null else Corrections.match(line, _corrections.value, CorrectionKind.Kind)?.to

    /** The list the owner moved to-dos like [title] to, or null. */
    suspend fun learnedList(title: String): String? =
        if (!learning()) null else Corrections.match(title, _corrections.value, CorrectionKind.List)?.to

    /** The owner changed [text] from [from] to [to]: remembered, replacing an older correction of the same line. */
    suspend fun corrected(kind: CorrectionKind, text: String, from: String, to: String) {
        if (from == to || text.isBlank() || !learning()) return
        lock.withLock {
            loadLocked()
            val same = _corrections.value.filter { it.kind == kind && Words.overlap(it.text, text) >= 0.9 }
            if (same.isNotEmpty()) db.learned().delete(same.map { it.id })
            val c = Correction(kind = kind, text = text.trim().take(200), from = from, to = to, at = now())
            val id = db.learned().insert(LearnedRow(type = CORRECTION, sealed = sealer.seal(c.toJson())))
            _corrections.value = (_corrections.value - same.toSet() + c.copy(id = id)).takeLast(MAX_CORRECTIONS)
            android.util.Log.i(TAG, "correction: ${kind.name} $from → $to")
        }
    }

    suspend fun deleteCorrection(id: Long) = lock.withLock {
        db.learned().delete(listOf(id))
        _corrections.value = _corrections.value.filter { it.id != id }
    }

    /** The owner's own words for a fact: kept as they wrote them, and nothing automatic changes it again. */
    suspend fun edit(id: Long, text: String) = lock.withLock {
        val t = text.trim()
        if (t.isEmpty()) return@withLock
        saveFacts(_facts.value.map { if (it.id == id) it.copy(text = t, edited = true, vec = null, sensitive = Sensitive.looks(t), lastSeen = now()) else it })
    }

    /** A fact the owner adds by hand. */
    suspend fun add(kind: FactKind, text: String) = lock.withLock {
        loadLocked()
        val t = text.trim()
        if (t.isEmpty() || Sensitive.secret(t)) return@withLock
        saveFacts(_facts.value + Fact(kind = kind, text = t, source = Source("owner", "Added by you", now()), edited = true))
    }

    suspend fun delete(id: Long) = lock.withLock { saveFacts(_facts.value.filter { it.id != id }) }

    /**
     * Forget everything learned: every fact, correction, pattern and waiting Talk line, and the key that sealed them.
     * Notes are untouched. The watermarks go too, so with learning on the notes are read afresh.
     */
    suspend fun forget() = lock.withLock {
        db.learned().deleteAll()
        sealer.forget()
        _facts.value = emptyList(); _corrections.value = emptyList(); _patterns.value = emptyList(); _lastRun.value = ""
        meta = Meta(entriesSeen = now(), chatSeen = now(), modelSeen = now()); metaId = 0
        // Forgetting never fails for want of a new key; the marks are then only in memory until the next save.
        runCatching { saveMeta() }.onFailure { android.util.Log.w(TAG, "forget: marks not saved: ${it.message}") }
        loaded = true
        android.util.Log.i(TAG, "forgot everything learned")
    }

    /** Writes what changed: new facts get ids, changed ones are sealed again, gone ones deleted. */
    private suspend fun saveFacts(next: List<Fact>) {
        val old = _facts.value.associateBy { it.id }
        val keep = next.mapNotNull { it.id.takeIf { id -> id != 0L } }.toSet()
        val gone = old.keys.filter { it !in keep }
        if (gone.isNotEmpty()) db.learned().delete(gone)
        _facts.value = next.map { f ->
            when {
                f.id == 0L -> f.copy(id = db.learned().insert(LearnedRow(type = FACT, sealed = sealer.seal(f.toJson()), updatedAt = now())))
                old[f.id] != f -> f.also { db.learned().update(LearnedRow(f.id, FACT, sealer.seal(f.toJson()), now())) }
                else -> f
            }
        }
    }

    private suspend fun saveMeta() {
        val row = LearnedRow(metaId, META, sealer.seal(meta.json()), now())
        if (metaId == 0L) metaId = db.learned().insert(row) else db.learned().update(row)
    }

    private fun label(e: Entry) = "${e.kind.label} · “${e.title.take(40)}”"

    companion object {
        const val TAG = "lfm-memory"
        const val FACT = "fact"
        const val CORRECTION = "correction"
        const val SAID = "said"
        const val META = "meta"
        const val MAX_CORRECTIONS = 200
        /** The nightly pass reads at most this many changed entries with the model, this many at a time. */
        const val MODEL_ENTRIES = 60
        const val MODEL_BATCH = 8
    }
}
