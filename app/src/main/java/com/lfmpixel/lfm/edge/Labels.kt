package com.lfmpixel.lfm.edge

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** A line sorted by the small model: where it goes, and the words that say when (copied, for the phone to read). */
data class CaptureLabel(val kind: String, val whenWords: String, val urgency: String, val energy: String, val sensitive: Boolean, val ms: Long)

/** What a photo is (receipt, document, whiteboard…), with a short title. */
data class PhotoLabel(val kind: String, val title: String, val ms: Long)

/** What the owner wants from a Talk turn while the camera is on: [LOOK] is a question about what it sees. */
data class IntentLabel(val intent: String, val ms: Long) {
    companion object { const val LOOK = "look" }
}

/**
 * Small, fast classifiers on the owner's server (docs/notebook-spec.md, section B): LFM3-1.7B for text and LFM3-VL-2B
 * for photos, each one short instruction with no history, thinking off, temperature 0, and output held to a JSON
 * schema by the server's grammar, so a small model can't break the format. About 150-250 ms each on the server.
 * When the server is not in use or doesn't answer in time, [local] (d1 on the phone, [com.lfmpixel.lfm.assistant.Decide])
 * labels text if its files are there; otherwise each returns null and the phone's rules decide.
 */
class Labels(private val edge: Edge, private val local: suspend () -> com.lfmpixel.lfm.assistant.Decide? = { null }) {
    /** The owner's corrections (memory/Memory.kt): the closest go into the capture prompt as examples. Set by AppGraph. */
    @Volatile var corrections: suspend () -> List<com.lfmpixel.lfm.memory.Correction> = { emptyList() }

    suspend fun capture(line: String): CaptureLabel? {
        val t0 = SystemClock.elapsedRealtime()
        val o = ask(EdgeModels.TEXT, CAPTURE_SYSTEM + learnedExamples(line, corrections()), JSONObject().put("role", "user").put("content", line.take(400)), CAPTURE_SCHEMA, 60)
            ?: return onPhone { it.capture(line) }
        return CaptureLabel(o.optString("kind"), o.optString("when").trim(), o.optString("urgency"), o.optString("energy"),
            o.optBoolean("sensitive"), SystemClock.elapsedRealtime() - t0)
    }

    /** d1 on the phone, or null when it isn't there or fails. */
    private suspend fun <T> onPhone(f: suspend (com.lfmpixel.lfm.assistant.Decide) -> T): T? {
        val d = runCatching { local() }.onFailure { Log.w(Edge.TAG, "d1 unavailable: ${it.message}") }.getOrNull() ?: return null
        return runCatching { f(d) }.onFailure { Log.w(Edge.TAG, "d1 label failed: ${it.message}") }.getOrNull()
    }

    suspend fun photo(jpegDataUrl: String): PhotoLabel? {
        val t0 = SystemClock.elapsedRealtime()
        val user = JSONObject().put("role", "user").put("content", JSONArray()
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", jpegDataUrl)))
            .put(JSONObject().put("type", "text").put("text", PHOTO_ASK)))
        val o = ask(EdgeModels.VISION, null, user, PHOTO_SCHEMA, 40) ?: return null
        return PhotoLabel(o.optString("kind"), o.optString("title").trim(), SystemClock.elapsedRealtime() - t0)
    }

    suspend fun intent(said: String): IntentLabel? {
        val t0 = SystemClock.elapsedRealtime()
        val o = ask(EdgeModels.TEXT, INTENT_SYSTEM, JSONObject().put("role", "user").put("content", said.take(300)), INTENT_SCHEMA, 12)
            ?: return onPhone { it.intent(said) }
        return IntentLabel(o.optString("intent"), SystemClock.elapsedRealtime() - t0)
    }

    /** One constrained request; null when Edge mode is off, the server fails or takes longer than [TIMEOUT_MS]. */
    private suspend fun ask(model: String, system: String?, user: JSONObject, schema: JSONObject, maxTokens: Int): JSONObject? {
        if (!edge.usable()) return null
        val s = edge.server() ?: return null
        val messages = JSONArray()
        system?.let { messages.put(JSONObject().put("role", "system").put("content", it)) }
        messages.put(user)
        val json = JSONObject().put("model", model).put("messages", messages).put("max_tokens", maxTokens).put("temperature", 0)
            .put("chat_template_kwargs", JSONObject().put("enable_thinking", false)).put("reasoning_effort", "none")
            .put("response_format", JSONObject().put("type", "json_schema").put("json_schema", JSONObject().put("name", "label").put("schema", schema)))
        val out = ByteArrayOutputStream()
        return withTimeoutOrNull(TIMEOUT_MS) {
            runCatching {
                edge.request(s, "POST", "/v1/chat/completions", "application/json", json.toString().toByteArray(), "Your server: sort") { out.write(it); true }
                val content = JSONObject(out.toString(Charsets.UTF_8.name())).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").optString("content")
                JSONObject(content.substring(content.indexOf('{')))
            }.onFailure { Log.w(Edge.TAG, "label failed: ${it.message}") }.getOrNull()
        }
    }

    companion object {
        const val TIMEOUT_MS = 1_500L

        /** Up to three of the owner's corrections like [line], as more examples (few-shot); "" when there are none. */
        fun learnedExamples(line: String, list: List<com.lfmpixel.lfm.memory.Correction>): String {
            val kinds = mapOf("Todo" to "todo", "Idea" to "idea", "Journal" to "journal", "Note" to "note")
            val ex = com.lfmpixel.lfm.memory.Corrections.examples(line, list, com.lfmpixel.lfm.memory.CorrectionKind.Kind)
                .filter { it.to in kinds && com.lfmpixel.lfm.memory.Corrections.similarity(line, it.text) > 0 && !com.lfmpixel.lfm.memory.Sensitive.looks(it.text) }
            if (ex.isEmpty()) return ""
            return "\nThe owner corrected these before; label lines like them the same way:\n" +
                ex.joinToString("\n") { "\"${it.text.replace("\"", "'")}\" -> kind \"${kinds[it.to]}\"" }
        }

        /** The prompt the server's tests settled on (docs/notebook-spec.md B.2): definitions, then three examples. */
        const val CAPTURE_SYSTEM = "Label one line the owner wrote in their notebook. Fill each field:\n" +
            "kind: \"todo\" = an action to do (buy, call, fix, send). \"event\" = happens at a set day or time (appointment, meeting, party). " +
            "\"idea\" = a possibility or \"what if\". \"journal\" = how they feel or what happened to them. \"note\" = a fact to keep.\n" +
            "when: the exact words that say a day or time, copied from the line (\"tuesday at 3\", \"tomorrow\"), or \"\" if none.\n" +
            "urgency: \"now\" = today or blocking, \"soon\" = this week, \"later\" = no hurry.\n" +
            "energy: \"low\" = quick or easy, \"high\" = long, hard or emotionally heavy.\n" +
            "sensitive: true only for health, money, passwords, legal trouble or intimate matters; else false.\n" +
            "Examples:\n" +
            "\"call the plumber tomorrow morning\" -> {\"kind\":\"todo\",\"when\":\"tomorrow morning\",\"urgency\":\"soon\",\"energy\":\"low\",\"sensitive\":false}\n" +
            "\"what if Today showed the weather\" -> {\"kind\":\"idea\",\"when\":\"\",\"urgency\":\"later\",\"energy\":\"low\",\"sensitive\":false}\n" +
            "\"Ana's birthday party Saturday 7pm\" -> {\"kind\":\"event\",\"when\":\"Saturday 7pm\",\"urgency\":\"soon\",\"energy\":\"low\",\"sensitive\":false}"

        val CAPTURE_SCHEMA: JSONObject = JSONObject("""{"type":"object","properties":{"kind":{"enum":["todo","event","idea","note","journal"]},
            "when":{"type":"string","maxLength":40},"urgency":{"enum":["now","soon","later"]},"energy":{"enum":["low","high"]},
            "sensitive":{"type":"boolean"}},"required":["kind","when","urgency","energy","sensitive"]}""")

        val PHOTO_KINDS = listOf("receipt", "document", "whiteboard", "screen", "person", "food", "place", "other")
        const val PHOTO_ASK = "Classify this photo for a notebook: receipt, document, whiteboard, screen, person, food, place or other. Give a short title."
        val PHOTO_SCHEMA: JSONObject = JSONObject().put("type", "object").put("properties", JSONObject()
            .put("kind", JSONObject().put("enum", JSONArray(PHOTO_KINDS))).put("title", JSONObject().put("type", "string").put("maxLength", 60)))
            .put("required", JSONArray().put("kind").put("title"))

        const val INTENT_SYSTEM = "The owner is talking to their assistant while the phone's camera is on. Label what they said:\n" +
            "\"look\" = a question about what the camera sees right now (what is this, read this, how many, what colour).\n" +
            "\"act\" = asks to add, remind, note, plan, set or change something.\n" +
            "\"chat\" = anything else.\n" +
            "Examples: \"what am I holding\" -> look. \"read me that label\" -> look. \"remind me to buy this tomorrow\" -> act. \"how are you\" -> chat."
        val INTENT_SCHEMA: JSONObject = JSONObject("""{"type":"object","properties":{"intent":{"enum":["look","act","chat"]}},"required":["intent"]}""")
    }
}
