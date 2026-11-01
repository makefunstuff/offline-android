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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
 * Wren's memory on the phone (notebook-spec section 6): facts, corrections and patterns, each row sealed by
 * [sealer] in the notes' own database. Rules read every new entry, chat message and Talk turn a moment after it is
 * written; the nightly pass ([nightly], while charging) also asks the small model, merges duplicates, lets stale facts
 * go and finds patterns. [context] puts what fits a question into its prompt.
 *
 * Each conversation (a chat gone quiet, a Talk closed) is kept as an [Episode]: the model reads it for facts and a
 * one-line summary, at once on the owner's server when it can be reached ([server]), else in the nightly pass on the
 * phone's own LiteRT model while charging. Episodes answer "what did I say about…".
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
    /** The owner's server, only when it can be reached now: a finished conversation is read there at once. */
    private val server: suspend () -> TextSession? = { null },
) {
    private val lock = Mutex()
    private var loaded = false
    private val _facts = MutableStateFlow<List<Fact>>(emptyList())
    val facts: StateFlow<List<Fact>> = _facts
    private val _corrections = MutableStateFlow<List<Correction>>(emptyList())
    val corrections: StateFlow<List<Correction>> = _corrections
    private val _patterns = MutableStateFlow<List<String>>(emptyList())
    val patterns: StateFlow<List<String>> = _patterns
    private val _episodes = MutableStateFlow<List<Episode>>(emptyList())
    val episodes: StateFlow<List<Episode>> = _episodes
    private var forgotten = emptyList<Forgotten>()
    /** What the last nightly pass did and how long it took, for the screen and the log. */
    private val _lastRun = MutableStateFlow("")
    val lastRun: StateFlow<String> = _lastRun
    val working = MutableStateFlow(false)
    /** Bumped by [forget]: a nightly pass that started before it writes nothing more. */
    @Volatile private var epoch = 0

    /** Watermarks: entries changed and chat messages written after these were not read yet. */
    private var meta = Meta()
    private var metaId = 0L

    private data class Meta(val entriesSeen: Long = 0, val chatSeen: Long = 0, val modelSeen: Long = 0, val lastNight: Long = 0,
                            val patterns: List<String> = emptyList(), val report: String = "", val episodeSeen: Long = 0) {
        fun json(): String = JSONObject().put("entries", entriesSeen).put("chat", chatSeen).put("model", modelSeen).put("night", lastNight)
            .put("patterns", JSONArray(patterns)).put("report", report).put("episode", episodeSeen).toString()

        companion object {
            fun of(s: String) = runCatching {
                val o = JSONObject(s)
                val p = o.optJSONArray("patterns") ?: JSONArray()
                // -1: a store from before conversations were kept; chats up to now are not read (some were written with learning off).
                Meta(o.optLong("entries"), o.optLong("chat"), o.optLong("model"), o.optLong("night"), List(p.length()) { p.getString(it) },
                    o.optString("report"), o.optLong("episode", -1))
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
        _episodes.value = db.learned().ofType(EPISODE).mapNotNull { r -> open(r)?.let { Episode.fromJson(r.id, it) } }
        forgotten = db.learned().ofType(FORGOTTEN).mapNotNull { r -> open(r)?.let { Forgotten.fromJson(r.id, it) } }
        db.learned().ofType(META).firstOrNull()?.let { r -> open(r)?.let(Meta::of)?.let { meta = it; metaId = r.id } }
        if (meta.episodeSeen < 0) meta = meta.copy(episodeSeen = db.messages().maxId() ?: 0)
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
        if (!learning()) {
            // Off means nothing written now is learned later either: the marks move on.
            if (meta.entriesSeen < now()) {
                meta = meta.copy(entriesSeen = now(), chatSeen = now(), modelSeen = now(), episodeSeen = db.messages().maxId() ?: 0)
                runCatching { saveMeta() }
            }
            db.learned().ofType(SAID).takeIf { it.isNotEmpty() }?.let { r -> db.learned().delete(r.map { it.id }) }
            return@withLock 0
        }
        val t = now()
        var facts = _facts.value
        var found = 0
        val locked = journalLocked()
        val entries = db.entries().all().filter { it.updatedAt > meta.entriesSeen }
        fun learn(f: Fact) {
            if (Forgotten.blocks(forgotten, f.text)) return
            facts = FactMerge.merge(facts, f)
            found++
        }
        for (e in entries) {
            val journal = e.kind == EntryKind.Journal
            for (f in FactRules.find(listOf(e.title, e.body).filter { it.isNotBlank() }.joinToString(". ")))
                learn(Fact(kind = f.kind, text = f.text, key = f.key, source = Source("entry:${e.id}", label(e), e.updatedAt), journal = journal))
        }
        // "Remember that…" and "Forget that…" were done when they were said ([command]): not read again here.
        val chat = db.messages().userSince(meta.chatSeen)
        for (m in chat) if (MemoryCommand.parse(m.text) == null) for (f in FactRules.find(m.text, chat = true))
            learn(Fact(kind = f.kind, text = f.text, key = f.key, source = Source("chat:${m.id}", "Chat · “${m.text.take(40)}”", m.createdAt)))
        val said = db.learned().ofType(SAID)
        for (r in said) {
            val o = sealer.open(r.sealed)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: continue
            val text = o.optString("text"); val at = o.optLong("at")
            if (MemoryCommand.parse(text) == null) for (f in FactRules.find(text, chat = true))
                learn(Fact(kind = f.kind, text = f.text, key = f.key, source = Source("talk", "Talk · “${text.take(40)}”", at)))
        }
        chatEpisodes()
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
        bestEffort("heard") { db.learned().insert(LearnedRow(type = SAID, sealed = sealer.seal(JSONObject().put("text", text).put("at", now()).toString()))) }
    }

    /** A Talk conversation ended (paused or closed): kept as an episode, and read by the server's model now if it can be. */
    suspend fun talkEnded(turns: List<Pair<String, String>>) {
        if (turns.isEmpty() || !learning()) return
        val made = Episodes.of("talk", now(), turns) ?: return
        bestEffort("talk episode") { lock.withLock {
            loadLocked()
            // What the owner told Wren to forget in this same Talk stays out of it.
            Episodes.scrub(made, forgotten.map { it.text })?.let { saveEpisodes(_episodes.value + it) }
        } }
        scope.launch(Dispatchers.Default) { soon() }
    }

    /** The owner started a new chat: [thread] (and any before it) is over. */
    fun chatEnded(thread: Long) {
        scope.launch(Dispatchers.Default) {
            runCatching { learnNow(); lock.withLock { chatEpisodes(endedThrough = thread) } }.onFailure { android.util.Log.w(TAG, "chat ended: ${it.message}") }
            soon()
        }
    }

    /** Conversations not read yet go to the owner's server, if it can be reached now; else they wait for the night. */
    private suspend fun soon() {
        if (!learning() || _episodes.value.none { !it.read }) return
        // One reader at a time: a second call while one runs would send the same conversations again.
        if (!reading.tryLock()) return
        try {
            val s = runCatching { server() }.getOrNull() ?: return
            runCatching { readEpisodes(s, SOON_EPISODES) }.onFailure { android.util.Log.w(TAG, "episodes on the server: ${it.message}") }
        } finally { reading.unlock() }
    }
    private val reading = Mutex()

    /**
     * Chat threads that are over (gone quiet for [Episodes.IDLE_MS], a newer thread started, or up to [endedThrough])
     * become episodes; one quiet now is read on the server at once. Called under [lock].
     */
    private suspend fun chatEpisodes(endedThrough: Long = 0) {
        if (!learning()) return
        val msgs = db.messages().after(meta.episodeSeen).filter { it.role == com.lfmpixel.lfm.data.Role.User || it.role == com.lfmpixel.lfm.data.Role.Assistant }
        if (msgs.isEmpty()) return
        val threads = msgs.groupBy { it.threadId }.toSortedMap()
        val newest = threads.lastKey()
        var seen = meta.episodeSeen
        val made = mutableListOf<Episode>()
        for ((thread, list) in threads) {
            val over = thread != newest || thread <= endedThrough || list.maxOf { it.createdAt } < now() - Episodes.IDLE_MS
            if (!over) break
            val turns = mutableListOf<Pair<String, String>>()
            for (m in list) {
                if (m.role == com.lfmpixel.lfm.data.Role.User) turns += m.text to ""
                // A Talk carried into the chat (Assistant.addTalk) is already a Talk conversation.
                else if (m.meta.endsWith("· Talk")) { if (turns.isNotEmpty()) turns.removeAt(turns.lastIndex) }
                else if (turns.isNotEmpty()) turns[turns.lastIndex] = turns.last().first to (turns.last().second + " " + m.text).trim()
            }
            Episodes.of("chat", list.maxOf { it.createdAt }, turns)?.let { Episodes.scrub(it, forgotten.map { f -> f.text }) }?.let { made += it }
            seen = list.maxOf { it.id }
        }
        // The first run after an update meets every old chat: only the newest are kept.
        if (made.isNotEmpty()) {
            saveEpisodes(_episodes.value + made.takeLast(40))
            if (endedThrough == 0L) scope.launch(Dispatchers.Default) { soon() }
        }
        if (seen != meta.episodeSeen) { meta = meta.copy(episodeSeen = seen); saveMeta() }
    }

    /**
     * The model reads up to [max] conversations not read yet, oldest first: the facts the owner told about themselves
     * go to the memory, and a one-line summary to the episode. On the server, nothing sensitive is sent. Returns how
     * many facts it found.
     */
    private suspend fun readEpisodes(s: TextSession, max: Int, started: Int = epoch): Int {
        val server = s is com.lfmpixel.lfm.edge.EdgeSession
        val todo = lock.withLock { loadLocked(); _episodes.value }.filter { !it.read && it.lines.isNotEmpty() && !(server && Sensitive.looks(it.lines)) }
            .sortedBy { it.at }.take(max)
        var found = 0
        for (e in todo) {
            if (!learning() || epoch != started) break
            val prompt = PromptFormat.build(EpisodePrompt.SYSTEM, emptyList(), listOf(PromptMessage(PromptMessage.Role.User, e.lines)), thinking = false)
            val out = StringBuilder()
            val ok = runCatching { withTimeoutOrNull(60_000) { s.generate(prompt, 300, temp = 0.2f).collect { out.append(it) } } }.getOrNull() != null
            if (!ok) break
            val answer = out.toString().substringAfter("</think>")
            lock.withLock {
                if (epoch != started || !learning()) return found
                var facts = _facts.value
                val label = "${e.kind.replaceFirstChar { it.uppercase() }} · “${Episodes.owner(e).take(40)}”"
                for ((kind, text, _) in FactPrompt.parse(answer)) {
                    if (Forgotten.blocks(forgotten, text)) continue
                    facts = FactMerge.merge(facts, Fact(kind = kind, text = text, source = Source("${e.kind}:episode:${e.at}", label, e.at)))
                    found++
                }
                saveFacts(facts)
                // Gone in the meantime (deleted on the screen): its facts stay, the episode isn't written back.
                if (_episodes.value.any { it.id == e.id }) saveEpisodes(_episodes.value.map { if (it.id == e.id) it.copy(read = true, summary = EpisodePrompt.summary(answer)) else it })
            }
        }
        if (found > 0) android.util.Log.i(TAG, "read ${todo.size} conversations: $found facts")
        return found
    }

    /**
     * "Remember that…" or "Forget that…" (typed or said), done now: the answer to say back, or null when [text] is
     * neither. Remembering needs learning on; forgetting always works, and what was forgotten is not learned again.
     */
    suspend fun command(text: String): String? {
        val cmd = MemoryCommand.parse(text) ?: return null
        return when (cmd) {
            MemoryCommand.Cmd.ForgetAll -> "To forget everything I learned, use Forget everything in What Wren knows."
            is MemoryCommand.Cmd.Remember -> {
                if (!learning()) return "Learning is off, so I won't keep that. You can turn it on in What Wren knows."
                val f = MemoryCommand.fact(cmd.text)
                if (Sensitive.secret(f.text)) return "I don't keep passwords, PINs or card numbers."
                withContext(NonCancellable) { lock.withLock {
                    loadLocked()
                    dropForgotten(f.text)
                    val fact = Fact(kind = f.kind, text = f.text, key = f.key, source = Source("owner", "You said “${cmd.text.take(40)}”", now()), edited = true)
                    // Same slot or same words: the owner's new words replace the old ones.
                    val i = _facts.value.indexOfFirst { (f.key.isNotEmpty() && it.key == f.key) || Words.overlap(it.text, f.text) >= FactMerge.SAME }
                    saveFacts(if (i < 0) _facts.value + fact else _facts.value.toMutableList().also { list ->
                        val old = list[i]
                        list[i] = old.copy(kind = f.kind, text = f.text, source = fact.source, lastSeen = now(), mentions = old.mentions + 1, edited = true, journal = false,
                            vec = if (old.text == f.text) old.vec else null, sensitive = Sensitive.looks(f.text),
                            was = if (old.text != f.text) old.text else old.was, changedAt = if (old.text != f.text) now() else old.changedAt)
                    })
                } }
                android.util.Log.i(TAG, "remembered by request (${f.kind})")
                "Got it, I'll remember: ${lower(f.text)}."
            }
            is MemoryCommand.Cmd.Forget -> {
                // What was said but not read yet (a Talk turn waiting, a chat message within the debounce) is read
                // first, so the forget catches it.
                runCatching { learnNow() }
                // By words only: quick (no model to load mid-turn), and nothing is deleted on a loose likeness.
                val gone = withContext(NonCancellable) { lock.withLock {
                    loadLocked()
                    // While the journal is locked, what came from it is neither found nor said back here.
                    val locked = journalLocked()
                    val gone = FactMerge.matching(_facts.value.filter { !(locked && it.journal) }, FactRules.you(cmd.what)).take(5)
                    if (gone.isNotEmpty()) {
                        val ids = gone.map { it.id }.toSet()
                        gone.forEach { addForgotten(it.text) }
                        saveFacts(_facts.value.filter { it.id !in ids })
                        // What was said about it in past conversations goes too.
                        val words = gone.map { it.text } + FactRules.you(cmd.what)
                        saveEpisodes(_episodes.value.mapNotNull { Episodes.scrub(it, words) })
                    } else if (cmd.explicit && Words.content(cmd.what).size >= 2) {
                        // Nothing yet, but said plainly ("forget that I like coffee"): not learned later either.
                        addForgotten(FactRules.you(cmd.what))
                    }
                    gone
                } }
                if (gone.isEmpty() && !cmd.explicit) return null
                android.util.Log.i(TAG, "forgot ${gone.size} by request")
                when (gone.size) {
                    0 -> "I don't have anything about that."
                    1 -> "Okay, I've forgotten that ${lower(gone[0].text)}."
                    else -> "Okay, I've forgotten ${gone.size} things: " + gone.joinToString("; ") { it.text } + "."
                }
            }
        }
    }

    /** "You prefer tea" reads "you prefer tea" mid-sentence; "Ana is your sister" keeps its capital. */
    private fun lower(t: String) = if (Regex("""^You\b""").containsMatchIn(t)) t.replaceFirstChar { it.lowercase() } else t

    private suspend fun addForgotten(text: String) {
        val f = Forgotten(text = text, at = now())
        val id = db.learned().insert(LearnedRow(type = FORGOTTEN, sealed = sealer.seal(f.toJson())))
        val all = forgotten + f.copy(id = id)
        val drop = all.dropLast(Forgotten.MAX)
        if (drop.isNotEmpty()) db.learned().delete(drop.map { it.id })
        forgotten = all.takeLast(Forgotten.MAX)
    }

    private suspend fun dropForgotten(text: String) {
        val back = forgotten.filter { Words.overlap(it.text, text) >= FactMerge.SAME }
        if (back.isEmpty()) return
        db.learned().delete(back.map { it.id })
        forgotten = forgotten - back.toSet()
    }

    /**
     * Learning on the side of something else (a Talk turn, a note saved): the keystore can refuse now and then (busy,
     * after an update) and the disk can be full, and that must never fail the save or close the app; the line is
     * simply not learned.
     */
    private suspend fun bestEffort(what: String, block: suspend () -> Unit) {
        try { block() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { android.util.Log.w(TAG, "$what not kept", e) }
    }

    /**
     * The nightly pass: the quick pass, then the small model reads what changed since its last run (when one can be
     * reached: [session]; on the server, nothing sensitive and nothing from the journal is sent), then duplicates are
     * merged, stale facts let go, missing vectors made and patterns found. Returns what it did, with timings.
     */
    suspend fun nightly(session: (suspend () -> TextSession?)? = null): String {
        if (!learning()) return "Learning is off"
        working.value = true
        val started = epoch
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
                    if (!ok || epoch != started) break
                    read += batch.size
                    lock.withLock {
                        if (epoch != started) return "Stopped: everything learned was forgotten"
                        var facts = _facts.value
                        for ((kind, text, n) in FactPrompt.parse(out.toString().substringAfter("</think>"))) {
                            val e = batch.getOrNull(n - 1) ?: continue
                            if (Forgotten.blocks(forgotten, text)) continue
                            facts = FactMerge.merge(facts, Fact(kind = kind, text = text, source = Source("entry:${e.id}", label(e), e.updatedAt), journal = e.kind == EntryKind.Journal))
                            byModel++
                        }
                        saveFacts(facts)
                        meta = meta.copy(modelSeen = maxOf(meta.modelSeen, batch.maxOf { it.updatedAt }))
                        saveMeta()
                    }
                }
            }
            var episodeFacts = 0
            if (s != null && epoch == started) episodeFacts = runCatching { readEpisodes(s, MODEL_EPISODES, started) }.getOrDefault(0)
            byModel += episodeFacts
            val t2 = ms()
            val (before, after, embedded) = lock.withLock {
                if (epoch != started) return "Stopped: everything learned was forgotten"
                val entries = db.entries().all()
                val before = _facts.value.size
                var facts = FactMerge.prune(FactMerge.dedupe(_facts.value), entries.mapTo(HashSet()) { it.id }, now())
                val e = runCatching { embedder() }.getOrNull()
                var embedded = 0
                var episodes = Episodes.compact(_episodes.value, now())
                if (e != null) {
                    val missing = facts.filter { it.vec == null }
                    if (missing.isNotEmpty()) runCatching {
                        val vecs = e.embed(missing.map { EmbedPrompt.document(it.kind.label, it.text) })
                        val byText = missing.zip(vecs).associate { (f, v) -> f.text to v }
                        facts = facts.map { f -> if (f.vec == null) f.copy(vec = byText[f.text]) else f }
                        embedded = missing.size
                    }
                    // With vectors, near-duplicates in other words merge too.
                    facts = FactMerge.dedupe(facts)
                    val unsummed = episodes.filter { it.vec == null && it.summary.isNotEmpty() }
                    if (unsummed.isNotEmpty()) runCatching {
                        val vecs = e.embed(unsummed.map { EmbedPrompt.document("Conversation", it.summary) })
                        val byId = unsummed.zip(vecs).associate { (ep, v) -> ep.id to v }
                        episodes = episodes.map { ep -> byId[ep.id]?.let { ep.copy(vec = it) } ?: ep }
                    }
                }
                saveFacts(facts)
                saveEpisodes(episodes)
                _patterns.value = Patterns.find(entries.filter { !(journalLocked() && it.kind == EntryKind.Journal) }, now()).lines()
                Triple(before, facts.size, embedded)
            }
            val t3 = ms()
            val report = "Read ${read} entries and conversations with the model; $byRules facts by rules, $byModel by the model ($episodeFacts from conversations); " +
                "$before → $after facts after merging; $embedded embedded. Took ${t3 - t0} ms (rules ${t1 - t0}, model ${t2 - t1}, tidy ${t3 - t2})."
            lock.withLock {
                if (epoch != started) return "Stopped: everything learned was forgotten"
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
    suspend fun context(question: String, contextTokens: Int, notes: Boolean = true, meaning: Boolean = true): Context {
        val t0 = now()
        load()
        val b = Recall.budget(contextTokens)
        val locked = journalLocked()
        val on = learning()
        val facts = if (on) _facts.value else emptyList()
        val profile = if (on) Recall.profile(facts, _patterns.value, b.profileChars, locked, now()) else ""
        val q = question.trim()
        if (q.length < 4) return Context(Recall.systemBlock(profile), "", now() - t0)
        val qv = if (on && meaning && facts.any { it.vec != null }) runCatching { embedder()?.embed(listOf(EmbedPrompt.query(q)))?.firstOrNull() }.getOrNull() else null
        val inProfile = profile.lines().map { it.removePrefix("- ") }.toSet()
        val near = Recall.relevant(facts, q, qv, locked, now = now())
        val relevant = near.filter { it.text !in inProfile }.take(3)
        // The memory helps find notes too: "my sister" also looks for "Ana" when Wren knows Ana is your sister.
        val names = Recall.names(near.take(2), q)
        val found = if (notes && Words.content(q).isNotEmpty()) runCatching {
            (search(q, b.notes + 2) + names.flatMap { search(it, 2) }).distinctBy { it.id }
        }.getOrDefault(emptyList()).filter { !(locked && it.kind == EntryKind.Journal) }.take(b.notes) else emptyList()
        // "What did I say about the garden?": the conversations about it, with the owner's own words.
        val said = if (on) Episodes.asked(q)?.let { topic ->
            val tv = if (meaning && _episodes.value.any { it.vec != null }) runCatching { embedder()?.embed(listOf(EmbedPrompt.query(topic)))?.firstOrNull() }.getOrNull() else null
            Episodes.block(Episodes.search(_episodes.value, topic, tv), topic, b.notesChars)
        }.orEmpty() else ""
        return Context(Recall.systemBlock(profile), Recall.turnBlock(relevant, Recall.notes(found, b.notesChars), said), now() - t0)
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
        bestEffort("correction") { lock.withLock {
            loadLocked()
            val same = _corrections.value.filter { it.kind == kind && Words.overlap(it.text, text) >= 0.9 }
            if (same.isNotEmpty()) db.learned().delete(same.map { it.id })
            val c = Correction(kind = kind, text = text.trim().take(200), from = from, to = to, at = now())
            val id = db.learned().insert(LearnedRow(type = CORRECTION, sealed = sealer.seal(c.toJson())))
            _corrections.value = (_corrections.value - same.toSet() + c.copy(id = id)).takeLast(MAX_CORRECTIONS)
            android.util.Log.i(TAG, "correction: ${kind.name} $from → $to")
        } }
    }

    suspend fun deleteCorrection(id: Long) = lock.withLock {
        db.learned().delete(listOf(id))
        _corrections.value = _corrections.value.filter { it.id != id }
    }

    /** The owner's own words for a fact: kept as they wrote them, and nothing automatic changes it again. */
    suspend fun edit(id: Long, text: String) = lock.withLock {
        val t = text.trim()
        if (t.isEmpty() || Sensitive.secret(t)) return@withLock
        saveFacts(_facts.value.map { if (it.id == id) it.copy(text = t, edited = true, vec = null, sensitive = Sensitive.looks(t), lastSeen = now()) else it })
    }

    /** A fact the owner adds by hand; with no [kind], the kind the rules see in it ("my sister Ana…" is a person). */
    suspend fun add(kind: FactKind?, text: String) = lock.withLock {
        loadLocked()
        val t = text.trim()
        if (t.isEmpty() || Sensitive.secret(t)) return@withLock
        dropForgotten(t)
        saveFacts(_facts.value + Fact(kind = kind ?: MemoryCommand.fact(t).kind, text = t, source = Source("owner", "Added by you", now()), edited = true))
    }

    /** The owner deleted a fact: gone, and not learned again from the same words. */
    suspend fun delete(id: Long) = lock.withLock {
        _facts.value.firstOrNull { it.id == id }?.let { addForgotten(it.text) }
        saveFacts(_facts.value.filter { it.id != id })
    }

    /** Clear chats: the conversations kept from chats go too, and the chats being cleared are never kept. */
    suspend fun forgetChats() = lock.withLock {
        loadLocked()
        saveEpisodes(_episodes.value.filter { it.kind != "chat" })
        meta = meta.copy(episodeSeen = maxOf(meta.episodeSeen, db.messages().maxId() ?: 0))
        saveMeta()
    }

    /** The owner deleted a conversation: Wren no longer recalls it (facts already learned from it stay, each deletable). */
    suspend fun deleteEpisode(id: Long) = lock.withLock { saveEpisodes(_episodes.value.filter { it.id != id }) }

    /**
     * Forget everything learned: every fact, correction, pattern and waiting Talk line, and the key that sealed them.
     * Notes are untouched, and are not read again: the watermarks move to now. A pass under way stops.
     */
    suspend fun forget() = lock.withLock {
        epoch++
        db.learned().deleteAll()
        sealer.forget()
        _facts.value = emptyList(); _corrections.value = emptyList(); _patterns.value = emptyList(); _lastRun.value = ""
        _episodes.value = emptyList(); forgotten = emptyList()
        meta = Meta(entriesSeen = now(), chatSeen = now(), modelSeen = now(), episodeSeen = runCatching { db.messages().maxId() }.getOrNull() ?: 0); metaId = 0
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

    /** The same for episodes. */
    private suspend fun saveEpisodes(next: List<Episode>) {
        val old = _episodes.value.associateBy { it.id }
        val keep = next.mapNotNull { it.id.takeIf { id -> id != 0L } }.toSet()
        val gone = old.keys.filter { it !in keep }
        if (gone.isNotEmpty()) db.learned().delete(gone)
        _episodes.value = next.map { e ->
            when {
                e.id == 0L -> e.copy(id = db.learned().insert(LearnedRow(type = EPISODE, sealed = sealer.seal(e.toJson()), updatedAt = now())))
                old[e.id] != e -> e.also { db.learned().update(LearnedRow(e.id, EPISODE, sealer.seal(e.toJson()), now())) }
                else -> e
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
        const val EPISODE = "episode"
        const val FORGOTTEN = "forgotten"
        /** Conversations read at once on the server after one ends; the nightly pass reads up to [MODEL_EPISODES]. */
        const val SOON_EPISODES = 3
        const val MODEL_EPISODES = 20
        const val MAX_CORRECTIONS = 200
        /** The nightly pass reads at most this many changed entries with the model, this many at a time. */
        const val MODEL_ENTRIES = 60
        const val MODEL_BATCH = 8
    }
}
