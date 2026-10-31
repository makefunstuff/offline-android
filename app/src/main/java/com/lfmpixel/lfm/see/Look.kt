package com.lfmpixel.lfm.see

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.lfmpixel.lfm.edge.Edge
import com.lfmpixel.lfm.edge.EdgeModels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/** What the camera saw last: one short line, when, and how long the server took. */
data class Scene(val text: String, val at: Long, val ms: Long, val kb: Int)

/** Look's numbers since it started: frames looked at, sent, dropped as stale, and the last round trip. */
data class LookStats(val frames: Int = 0, val sent: Int = 0, val dropped: Int = 0, val unchanged: Int = 0, val lastMs: Long = 0,
                     val encodeMs: Long = 0, val kb: Int = 0)

/**
 * Look: the camera's view, described live by LFM3-VL-2B on the owner's server, so Talk can answer about what it sees.
 *
 * Kept cheap and current: frames come small from the camera (CameraX analysis, about 640×480) and are shrunk to
 * [SIDE] px and JPEG [QUALITY]; a frame goes only when the view has changed (a 12×9 grey thumbnail, against the one
 * last sent) and has settled (against the frame before, so a pan sends its end, not its blur), or when asked
 * ([offer] with now). One request at a time: a frame that arrives meanwhile waits, and a newer one replaces it (the
 * older is dropped, never sent late).
 */
class Look(private val edge: Edge, private val scope: CoroutineScope) {
    private val _scene = MutableStateFlow<Scene?>(null)
    val scene: StateFlow<Scene?> = _scene
    private val _stats = MutableStateFlow(LookStats())
    val stats: StateFlow<LookStats> = _stats
    private val _busy = MutableStateFlow(false)
    /** A request is out now. */
    val busy: StateFlow<Boolean> = _busy

    private var sentSig: IntArray? = null
    private var prevSig: IntArray? = null
    private var waiting: Bitmap? = null
    private var waitingSig: IntArray? = null
    /** The newest frame, whatever was sent: what a question about the view is asked on. */
    private var latest: Bitmap? = null
    @Volatile private var on = false
    private val lock = Any()

    fun start() { synchronized(lock) { on = true; sentSig = null; prevSig = null; _stats.value = LookStats() } }

    fun stop() = synchronized(lock) {
        on = false
        _scene.value = null
        waiting?.recycle(); waiting = null
        latest?.recycle(); latest = null
    }

    /** What the camera sees, for an answer: the last line while it is fresh (Look on, under 90 s old), else null. */
    fun seeing(): String? = _scene.value?.takeIf { on && SystemClock.elapsedRealtime() - it.at < 90_000 }?.text

    /**
     * A frame from the camera, already upright and at most [SIDE] px. [now]: look at it whatever (a tap, a Talk turn).
     * Takes the bitmap: it is recycled when no longer needed.
     */
    fun offer(frame: Bitmap, now: Boolean = false) {
        val sig = signature(frame)
        var send: Bitmap? = null
        synchronized(lock) {
            _stats.value = _stats.value.let { it.copy(frames = it.frames + 1) }
            if (on) { latest?.recycle(); latest = frame.copy(Bitmap.Config.ARGB_8888, false) }
            val settled = prevSig?.let { diff(it, sig) < SETTLED } ?: false
            prevSig = sig
            val changed = sentSig?.let { diff(it, sig) > CHANGED } ?: true
            if (!on || (!now && !(changed && settled))) {
                if (on && !changed) _stats.value = _stats.value.let { it.copy(unchanged = it.unchanged + 1) }
                frame.recycle()
                return
            }
            if (_busy.value) {
                if (waiting != null) _stats.value = _stats.value.let { it.copy(dropped = it.dropped + 1) }
                waiting?.recycle()
                waiting = frame
                waitingSig = sig
                return
            }
            _busy.value = true
            sentSig = sig
            send = frame
        }
        send?.let(::launchSend)
    }

    private fun launchSend(frame: Bitmap) {
        scope.launch(Dispatchers.IO) {
            var next: Bitmap? = frame
            while (next != null) {
                val f = next
                describe(f)
                f.recycle()
                synchronized(lock) {
                    next = waiting?.takeIf { on }
                    if (next != null) sentSig = waitingSig
                    waiting = null
                    if (next == null) _busy.value = false
                }
            }
        }
    }

    private suspend fun describe(frame: Bitmap) {
        val t0 = SystemClock.elapsedRealtime()
        val jpeg = jpeg(frame, QUALITY)
        val encodeMs = SystemClock.elapsedRealtime() - t0
        val text = runCatching { ask(jpeg) }.onFailure { Log.w(TAG, "look failed: ${it.message}") }.getOrNull()
        val ms = SystemClock.elapsedRealtime() - t0
        if (!text.isNullOrBlank() && on) _scene.value = Scene(text, SystemClock.elapsedRealtime(), ms, jpeg.size / 1024)
        _stats.value = _stats.value.let { it.copy(sent = it.sent + 1, lastMs = ms, encodeMs = encodeMs, kb = jpeg.size / 1024) }
        Log.i(TAG, "look: ${jpeg.size / 1024} KB, encode $encodeMs ms, answer $ms ms: $text")
    }

    private suspend fun ask(jpeg: ByteArray, question: String = ASK, maxTokens: Int = 48): String? {
        val s = edge.server() ?: return null
        if (!edge.usable()) return null
        val url = "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val json = JSONObject().put("model", EdgeModels.VISION).put("max_tokens", maxTokens).put("temperature", 0)
            .put("reasoning_effort", "none").put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray()
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", url)))
                .put(JSONObject().put("type", "text").put("text", question)))))
        val out = ByteArrayOutputStream()
        edge.request(s, "POST", "/v1/chat/completions", "application/json", json.toString().toByteArray(), "Your server: look") { out.write(it); true }
        return JSONObject(out.toString(Charsets.UTF_8.name())).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").optString("content").replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "").trim()
    }

    /** A question about the view now (Talk's "what am I holding?"): the newest frame, asked straight; null if none. */
    suspend fun answer(question: String): String? {
        val jpeg = synchronized(lock) { latest?.takeIf { on }?.let { jpeg(it, QUALITY) } } ?: return null
        return runCatching { ask(jpeg, "$question\nAnswer in one or two short spoken sentences.", 120) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    val active get() = on

    companion object {
        const val TAG = "lfm-look"
        /** Longest side sent: the server's tests read text at 512 as well as at 768, in half the bytes (docs/notebook-spec.md B.3). */
        const val SIDE = 512
        const val QUALITY = 75
        /** Mean grey difference (0-255) of the 12×9 thumbnail: over [CHANGED] the view is new; under [SETTLED] it is still. */
        const val CHANGED = 14
        const val SETTLED = 6
        const val ASK = "In one short sentence, what does the camera see? Name the main things and any text you can read."

        fun jpeg(b: Bitmap, quality: Int): ByteArray = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

        /** A 12×9 grey thumbnail, for telling a new view from the same one. */
        fun signature(b: Bitmap): IntArray {
            val small = Bitmap.createScaledBitmap(b, 12, 9, true)
            val px = IntArray(12 * 9)
            small.getPixels(px, 0, 12, 0, 0, 12, 9)
            if (small != b) small.recycle()
            return IntArray(px.size) { i -> val p = px[i]; ((p shr 16 and 0xff) * 3 + (p shr 8 and 0xff) * 6 + (p and 0xff)) / 10 }
        }

        fun diff(a: IntArray, b: IntArray): Int = if (a.size != b.size) 255 else a.indices.sumOf { abs(a[it] - b[it]) } / a.size
    }
}
