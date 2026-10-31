package com.lfmpixel.lfm.see

import com.lfmpixel.lfm.llm.PromptFormat
import com.lfmpixel.lfm.llm.RgbImage

/** What the owner shows: a photo, a short video, or a document (a photo of a page, an image file or a PDF). */
enum class SeeKind(val label: String) { Photo("Photo"), Video("Video"), Document("Document") }

/** Which model looks. Omni for photos and video, VL for documents (spec section 6); each stands in for the other. */
enum class SeeModel(val label: String) { Omni("LFM3-Omni-2B"), Vision("LFM3-VL-2B"), Text("LFM3-1.7B") }

/** A document page: its text layer when the PDF has one, else a picture of it for VL to read. */
class Page(val text: String, val image: RgbImage?)

/** Everything one "ask about this" needs, prepared on the phone before it goes into the queue. */
class SeeRequest(
    val kind: SeeKind,
    val question: String,
    val images: List<RgbImage> = emptyList(),   // photo: one; video: frames in order; document photo: one
    val pages: List<Page> = emptyList(),        // PDF pages
    val seconds: Int = 0,                       // video length
    val thumbPath: String = "",                 // a small JPEG for the chat
    val label: String = "",                     // "Video · 0:06", "Lease.pdf · 3 pages"
    val pagesSkipped: Int = 0,                  // PDF pages past the limit
)

object SeeRules {
    /** Video: two frames a second, as LFM3-VL is trained, for at most this long. */
    const val VIDEO_FPS = 2
    const val VIDEO_MAX_SECONDS = 10
    const val PDF_MAX_PAGES = 20

    /** Longest side of the picture the model gets. Pages need detail to read; video frames are many, so small. */
    fun maxSide(kind: SeeKind, frames: Int = 1) = when (kind) {
        SeeKind.Photo -> 768
        SeeKind.Document -> 1280
        SeeKind.Video -> if (frames > 12) 320 else 384
    }

    /** Fits [w]×[h] inside [max] on its longer side, keeping the shape; never upscales. */
    fun fit(w: Int, h: Int, max: Int): Pair<Int, Int> {
        val long = maxOf(w, h)
        if (long <= max) return w to h
        val s = max.toDouble() / long
        return maxOf(1, Math.round(w * s).toInt()) to maxOf(1, Math.round(h * s).toInt())
    }

    /** Times (ms) of the frames taken from a clip of [durationMs]: [VIDEO_FPS] a second, from the start, at most [VIDEO_MAX_SECONDS]. */
    fun frameTimes(durationMs: Long): List<Long> {
        if (durationMs <= 0) return listOf(0L)
        val span = minOf(durationMs, VIDEO_MAX_SECONDS * 1000L)
        val step = 1000L / VIDEO_FPS
        val n = maxOf(1, ((span + step - 1) / step).toInt())
        return List(n) { it * step }
    }

    fun pick(kind: SeeKind, hasOmni: Boolean, hasVision: Boolean, hasText: Boolean, needsPictures: Boolean): SeeModel? = when {
        !needsPictures && kind == SeeKind.Document && hasVision -> SeeModel.Vision
        !needsPictures && kind == SeeKind.Document && hasText -> SeeModel.Text
        kind == SeeKind.Document && hasVision -> SeeModel.Vision
        kind != SeeKind.Document && hasOmni -> SeeModel.Omni
        hasVision -> SeeModel.Vision
        hasOmni -> SeeModel.Omni
        else -> null
    }

    fun defaultQuestion(kind: SeeKind) = when (kind) {
        SeeKind.Photo -> "What is this?"
        SeeKind.Video -> "What happens in this video?"
        SeeKind.Document -> "What is this document about? Sum it up and give the key facts."
    }

    private const val SYSTEM = "You are LFM, a private assistant that runs entirely on the owner's phone. " +
        "Answer briefly and plainly from what you see. If you cannot tell, say so."

    /** A prompt with one media marker per picture, in LFM3's chat format. */
    fun prompt(kind: SeeKind, question: String, pictures: Int, seconds: Int = 0): String {
        val intro = when {
            kind == SeeKind.Video && pictures > 1 -> "These are $pictures frames from a ${seconds}-second video, in order.\n"
            else -> ""
        }
        val markers = "<__media__>".repeat(pictures)
        return PromptFormat.system(SYSTEM, emptyList()) +
            "${PromptFormat.IM_START}user\n$markers\n$intro$question${PromptFormat.IM_END}\n" +
            "${PromptFormat.IM_START}assistant\n<think></think>"
    }

    const val READ_PAGE = "Write out all the text on this page, in reading order. Only the text, nothing else."

    /**
     * The document's text, page by page, cut to about [maxChars] so the prompt fits the context; a cut says so.
     */
    fun documentText(pages: List<String>, maxChars: Int): String {
        val sb = StringBuilder()
        for ((i, t) in pages.withIndex()) {
            val head = "--- Page ${i + 1} ---\n"
            val room = maxChars - sb.length - head.length
            if (room <= 200) { sb.append("\n[The rest of the document was cut to fit.]"); break }
            sb.append(head)
            val body = t.trim()
            if (body.length > room) { sb.append(body, 0, room).append("\n[The rest of the document was cut to fit.]"); break }
            sb.append(body).append("\n\n")
        }
        return sb.toString().trim()
    }

    fun documentPrompt(text: String, question: String): String =
        PromptFormat.system(SYSTEM, emptyList()) +
            "${PromptFormat.IM_START}user\nHere is a document from the owner's phone.\n\n$text\n\n$question${PromptFormat.IM_END}\n" +
            "${PromptFormat.IM_START}assistant\n<think></think>"

    /** What the model wrote, without think blocks or end tokens. */
    fun clean(raw: String) = raw.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
        .substringBefore("<|im_end|>").trim()
}
