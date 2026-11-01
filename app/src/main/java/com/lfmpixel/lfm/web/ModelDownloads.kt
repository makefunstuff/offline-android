package com.lfmpixel.lfm.web

import android.content.Context
import android.content.Intent
import com.lfmpixel.lfm.llm.CheckResult
import com.lfmpixel.lfm.llm.KnownModel
import com.lfmpixel.lfm.llm.ModelCatalog
import com.lfmpixel.lfm.llm.ModelCheck
import com.lfmpixel.lfm.llm.ModelManager
import com.lfmpixel.lfm.llm.ModelRole
import com.lfmpixel.lfm.llm.ModelStore
import com.lfmpixel.lfm.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * The in-app model downloads (spec section 7, model management): one file at a time from Hugging Face through
 * the network module, streamed into the app's storage, hashed as it arrives, checked against the pinned SHA-256, then
 * loaded. Pause keeps what arrived and resume asks only for the rest, also after the app or the phone restarts.
 * Wi-Fi only by default; the network module checks that.
 */
class ModelDownloads(
    private val context: Context,
    private val store: ModelStore,
    private val net: NetClient,
    private val settings: Settings,
    private val engine: ModelManager?,
    private val scope: CoroutineScope,
) {
    enum class State { Waiting, Running, Pausing, Paused, Cancelling, NoWifi, Checking, Done, Failed }

    /** [speed] in bytes a second while running; [loaded] once the finished model answered a load. */
    data class Item(val model: KnownModel, val done: Long, val state: State, val error: String? = null, val speed: Long = 0,
                    val loaded: Boolean = false, val interrupted: Boolean = false) {
        /** Seconds left at the current speed, or null. */
        val secondsLeft get() = if (speed > 0 && state == State.Running) (model.sizeBytes - done) / speed else null
    }

    private val _items = MutableStateFlow<Map<String, Item>>(emptyMap())
    val items: StateFlow<Map<String, Item>> = _items
    private var worker: Job? = null
    private var wifiRetry: Job? = null
    private val queueFile = File(store.dir, "downloads.queue")

    init { restore() }

    /** The foreground service stays while something is moving; waiting for Wi-Fi doesn't count. */
    val active get() = _items.value.values.any { it.state in setOf(State.Waiting, State.Running, State.Pausing, State.Cancelling, State.Checking) }

    fun start(models: List<KnownModel>) {
        val now = _items.value
        val add = models.filter { m ->
            store.installed.value.none { it.known == m && it.verified } &&
                now[m.fileName]?.state !in setOf(State.Waiting, State.Running, State.Checking, State.Pausing, State.Cancelling)
        }
        if (add.isEmpty()) return
        _items.value = now + add.associate { it.fileName to Item(it, store.partFile(it).length(), State.Waiting) }
        save()
        kick()
    }

    /** A model and the files it needs beside it (Omni's voice graphs, the tokenizer) that are not here yet. */
    fun startWithParts(m: KnownModel) {
        val group = ModelCatalog.groups.firstOrNull { m in it.variants }
        start(listOf(m) + group?.parts.orEmpty())
    }

    fun pause(m: KnownModel) = update(m, save = true) {
        when (it.state) {
            State.Running -> it.copy(state = State.Pausing)
            State.Waiting, State.NoWifi -> it.copy(state = State.Paused, speed = 0)
            else -> it
        }
    }

    fun resume(m: KnownModel) = start(listOf(m))

    /** Stops a download and deletes what it fetched. */
    fun cancel(m: KnownModel) {
        val s = _items.value[m.fileName]?.state ?: return
        if (s == State.Running || s == State.Pausing) { update(m) { it.copy(state = State.Cancelling) }; return }
        if (s == State.Checking || s == State.Cancelling) return
        store.partFile(m).delete()
        _items.value = _items.value - m.fileName
        save()
    }

    /** Called when the app comes to the front: downloads that were running when it closed carry on. */
    fun resumeInterrupted() {
        val again = _items.value.values.filter { it.interrupted && it.state == State.Paused }.map { it.model }
        if (again.isEmpty()) return
        _items.value = _items.value.mapValues { (_, v) -> v.copy(interrupted = false) }
        start(again)
    }

    private fun update(m: KnownModel, save: Boolean = false, f: (Item) -> Item) {
        // Progress comes from the reader thread and taps from the main thread: change the map atomically.
        _items.update { map -> map[m.fileName]?.let { map + (m.fileName to f(it)) } ?: map }
        if (save) save()
    }

    /** Android's daily limit for a data-sync service was reached: pause everything, keeping what arrived. */
    fun pauseAll() {
        val moving = _items.value.values.filter { it.state in setOf(State.Waiting, State.Running, State.NoWifi) }
        moving.forEach { pause(it.model) }
        // Marked so they carry on by themselves: when Offline is turned off, or the next time Wren opens.
        _items.update { map -> map.mapValues { (k, v) -> if (moving.any { it.model.fileName == k }) v.copy(interrupted = true) else v } }
        save()
    }

    private fun kick() {
        if (worker?.isActive == true) return
        // From the owner's tap the app is in front; if Android still refuses, the download runs while the app is open.
        runCatching { context.startForegroundService(Intent(context, DownloadService::class.java)) }
        worker = scope.launch {
            while (true) {
                val next = _items.value.values.firstOrNull { it.state == State.Waiting } ?: break
                run(next.model)
            }
            save()
            if (_items.value.values.any { it.state == State.NoWifi } && wifiRetry?.isActive != true) wifiRetry = scope.launch {
                // Try again every half minute while the app is alive; a tap on Resume tries at once.
                while (_items.value.values.any { it.state == State.NoWifi }) {
                    delay(30_000)
                    val waiting = _items.value.values.filter { it.state == State.NoWifi }
                    if (waiting.isEmpty()) break
                    _items.value = _items.value + waiting.associate { it.model.fileName to it.copy(state = State.Waiting) }
                    kick()
                    worker?.join()
                }
            }
        }
    }

    private suspend fun run(m: KnownModel) {
        update(m, save = true) { it.copy(state = State.Running, error = null, speed = 0) }
        val part = store.partFile(m)
        val wifiOnly = settings.now().downloadWifiOnly
        try {
            val md = MessageDigest.getInstance("SHA-256")
            // What arrived before a pause or restart is hashed again from disk; a digest can't be kept across runs.
            val have = withContext(Dispatchers.IO) {
                if (part.length() > m.sizeBytes) part.delete()
                if (part.exists()) part.inputStream().use { input ->
                    val buf = ByteArray(1 shl 20)
                    while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
                }
                part.length()
            }
            var stopped: State? = null
            if (have < m.sizeBytes) {
                try {
                    net.download(m.fileName, have, wifiOnly, "Model: ${m.family} ${m.quant}") { input ->
                        FileOutputStream(part, true).use { out ->
                            val buf = ByteArray(1 shl 20)
                            var done = have
                            var mark = System.nanoTime()
                            var markBytes = done
                            while (true) {
                                when (_items.value[m.fileName]?.state) {
                                    State.Pausing -> { stopped = State.Paused; break }
                                    State.Cancelling -> { stopped = State.Cancelling; break }
                                    else -> Unit
                                }
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                md.update(buf, 0, n)
                                done += n
                                val t = System.nanoTime()
                                if (t - mark >= 1_000_000_000L || done == m.sizeBytes) {
                                    val speed = ((done - markBytes) * 1_000_000_000L / (t - mark).coerceAtLeast(1))
                                    val prev = _items.value[m.fileName]?.speed ?: 0
                                    // A little smoothing, so time left doesn't jump about.
                                    val smooth = if (prev == 0L) speed else (prev * 3 + speed) / 4
                                    update(m) { it.copy(done = done, speed = if (it.state == State.Running) smooth else 0) }
                                    mark = t; markBytes = done
                                }
                            }
                        }
                    }
                } catch (e: NetException) {
                    if (stopped == null) throw e
                }
            }
            // A tap that came while the last bytes arrived still counts.
            if (stopped == null) when (_items.value[m.fileName]?.state) {
                State.Pausing -> stopped = State.Paused
                State.Cancelling -> stopped = State.Cancelling
                else -> Unit
            }
            when (stopped) {
                State.Paused -> { update(m, save = true) { it.copy(state = State.Paused, done = part.length(), speed = 0) }; return }
                State.Cancelling -> { part.delete(); _items.value = _items.value - m.fileName; save(); return }
                else -> Unit
            }
            if (part.length() != m.sizeBytes) throw NetException("Got ${part.length()} of ${m.sizeBytes} bytes")
            update(m) { it.copy(state = State.Checking, done = m.sizeBytes, speed = 0) }
            val sha = md.digest().joinToString("") { "%02x".format(it) }
            if (ModelCheck.verify(m, sha) is CheckResult.Ok) {
                withContext(Dispatchers.IO) { store.adopt(m, part, sha) }
                update(m, save = true) { it.copy(state = State.Done) }
                load(m)
            } else {
                part.delete()
                update(m, save = true) { it.copy(state = State.Failed, done = 0, error = "SHA-256 does not match the pinned hash, so the file was deleted") }
            }
        } catch (e: NetException) {
            val wifi = e.code == NO_WIFI
            update(m, save = true) { it.copy(state = if (wifi) State.NoWifi else State.Failed, done = part.length(), speed = 0,
                error = if (wifi) "Waiting for Wi-Fi" else e.message) }
        } catch (e: java.io.IOException) {
            update(m, save = true) { it.copy(state = State.Failed, done = part.length(), speed = 0, error = e.message ?: "Could not write the file") }
        }
    }

    /** A finished model is loaded, so it is ready to use (and proven to load). */
    private suspend fun load(m: KnownModel) {
        val e = engine ?: return
        runCatching {
            when (m.role) {
                // Loaded now only if no chat model is in memory; otherwise it takes over at the next message.
                ModelRole.Text -> if (e.status.value.text != com.lfmpixel.lfm.llm.LoadState.Ready) e.localTextSession() else return
                ModelRole.Omni, ModelRole.OmniSpeech, ModelRole.Tokenizer -> if (e.hasVoice()) e.omniSession() else return
                ModelRole.Vision -> if (e.hasVision()) e.visionSession() else return
                else -> return
            }
        }.onSuccess { update(m) { it.copy(loaded = true) } }
            .onFailure { err -> update(m) { it.copy(error = "Verified, but it did not load: ${err.message}") } }
    }

    /** The queue on disk: file name and whether it was moving, so a restart can pick it up. */
    private fun save() {
        val lines = _items.value.values.filter { it.state != State.Done }.joinToString("\n") { i ->
            val moving = i.state in setOf(State.Waiting, State.Running, State.Checking, State.NoWifi) || i.interrupted
            "${i.model.fileName}\t${if (moving) "moving" else "paused"}\t${i.error.orEmpty().replace('\t', ' ').replace('\n', ' ')}"
        }
        runCatching { queueFile.writeText(lines) }
    }

    private fun restore() {
        val lines = runCatching { queueFile.readLines() }.getOrDefault(emptyList())
        _items.value = lines.mapNotNull { line ->
            val f = line.split('\t')
            val m = ModelCatalog.byName(f.getOrNull(0) ?: return@mapNotNull null) ?: return@mapNotNull null
            if (store.installed.value.any { it.known == m && it.verified }) return@mapNotNull null
            val moving = f.getOrNull(1) == "moving"
            m.fileName to Item(m, store.partFile(m).length(), State.Paused, error = if (moving) "Stopped when the app closed" else f.getOrNull(2)?.ifEmpty { null },
                interrupted = moving)
        }.toMap()
    }
}
