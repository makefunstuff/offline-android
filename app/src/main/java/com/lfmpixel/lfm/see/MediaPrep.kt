package com.lfmpixel.lfm.see

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.graphics.scale
import com.lfmpixel.lfm.llm.LlmException
import com.lfmpixel.lfm.llm.RgbImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Turns what was picked or shot into what the models take: small RGB pictures, video frames, PDF pages.
 * Everything is read from the phone's own storage through the URI the picker granted; nothing is uploaded.
 */
class MediaPrep(private val context: Context) {
    private val dir get() = File(context.filesDir, "media").apply { mkdirs() }

    suspend fun photo(bitmap: Bitmap, question: String, kind: SeeKind = SeeKind.Photo): SeeRequest = withContext(Dispatchers.Default) {
        SeeRequest(kind, question, images = listOf(toRgb(bitmap, SeeRules.maxSide(kind))), thumbPath = thumb(bitmap),
            label = kind.label)
    }

    /** Frames from the camera, taken at [SeeRules.VIDEO_FPS] while recording. */
    suspend fun frames(frames: List<Bitmap>, seconds: Int, question: String): SeeRequest = withContext(Dispatchers.Default) {
        if (frames.isEmpty()) throw LlmException("The clip was too short")
        val side = SeeRules.maxSide(SeeKind.Video, frames.size)
        SeeRequest(SeeKind.Video, question, images = frames.map { toRgb(it, side) }, seconds = seconds,
            thumbPath = thumb(frames.first()), label = "Video · " + clock(seconds))
    }

    /** A photo or video from the photo picker. */
    suspend fun picked(uri: Uri, question: String): SeeRequest = withContext(Dispatchers.IO) {
        val type = context.contentResolver.getType(uri) ?: android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(uri.lastPathSegment.orEmpty().substringAfterLast('.', "").lowercase()).orEmpty()
        if (type.startsWith("video/")) video(uri, question) else photo(decode(uri, 2048), question)
    }

    /** A document from the Storage Access Framework: a PDF, or a picture of a page. */
    suspend fun document(uri: Uri, question: String): SeeRequest = withContext(Dispatchers.IO) {
        val type = context.contentResolver.getType(uri).orEmpty()
        val name = displayName(uri).ifEmpty { uri.lastPathSegment.orEmpty().substringAfterLast('/') }
        if (type == "application/pdf" || name.endsWith(".pdf", ignoreCase = true)) pdf(uri, name, question)
        else photo(decode(uri, 2560), question, SeeKind.Document).let {
            SeeRequest(it.kind, it.question, it.images, thumbPath = it.thumbPath, label = name.ifEmpty { "Document" })
        }
    }

    private fun video(uri: Uri, question: String): SeeRequest {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 640
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 480
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val times = SeeRules.frameTimes(ms)
            val side = SeeRules.maxSide(SeeKind.Video, times.size)
            val (fw, fh) = SeeRules.fit(if (rot % 180 == 0) w else h, if (rot % 180 == 0) h else w, side)
            val frames = times.mapNotNull { t ->
                r.getScaledFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST, fw, fh)
            }
            if (frames.isEmpty()) throw LlmException("Could not read frames from this video")
            val seconds = ((minOf(ms, SeeRules.VIDEO_MAX_SECONDS * 1000L) + 500) / 1000).toInt()
            val longer = if (ms > SeeRules.VIDEO_MAX_SECONDS * 1000L) " (first ${SeeRules.VIDEO_MAX_SECONDS} s)" else ""
            return SeeRequest(SeeKind.Video, question, images = frames.map { toRgb(it, side) }, seconds = seconds,
                thumbPath = thumb(frames.first()), label = "Video · " + clock(((ms + 500) / 1000).toInt()) + longer)
        } catch (e: RuntimeException) {
            throw LlmException("Could not open this video")
        } finally {
            r.release()
        }
    }

    private fun pdf(uri: Uri, name: String, question: String): SeeRequest {
        val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: throw LlmException("Could not open the file")
        fd.use {
            val renderer = try { PdfRenderer(it) } catch (e: Exception) { throw LlmException("This PDF could not be opened (locked or damaged)") }
            renderer.use { pdf ->
                val n = minOf(pdf.pageCount, SeeRules.PDF_MAX_PAGES)
                var thumbPath = ""
                val pages = (0 until n).map { i ->
                    pdf.openPage(i).use { page ->
                        val text = page.textContents.joinToString("\n") { c -> c.text }.trim()
                        // Pages with a text layer are read as text (fast); scans are rendered for VL to read.
                        val needPicture = text.length < 20
                        if (needPicture || i == 0) {
                            val (w, h) = SeeRules.fit(page.width * 3, page.height * 3, SeeRules.maxSide(SeeKind.Document))
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            if (i == 0) thumbPath = thumb(bmp)
                            Page(text, if (needPicture) toRgb(bmp, SeeRules.maxSide(SeeKind.Document)) else null)
                        } else Page(text, null)
                    }
                }
                val count = pdf.pageCount
                return SeeRequest(SeeKind.Document, question, pages = pages, thumbPath = thumbPath,
                    label = "${name.ifEmpty { "Document" }} · $count ${if (count == 1) "page" else "pages"}", pagesSkipped = count - n)
            }
        }
    }

    private fun decode(uri: Uri, max: Int): Bitmap = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
            val (w, h) = SeeRules.fit(info.size.width, info.size.height, max)
            d.setTargetSize(w, h)
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (e: Exception) {
        throw LlmException("Could not open this picture")
    }

    private fun displayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull().orEmpty()

    /** A 320 px JPEG for the chat bubble, in the app's own files. */
    private fun thumb(b: Bitmap): String {
        val (w, h) = SeeRules.fit(b.width, b.height, 320)
        val small = if (w == b.width && h == b.height) b else b.scale(w, h)
        val f = File(dir, "see-${System.currentTimeMillis()}-${(0..9999).random()}.jpg")
        f.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return f.path
    }

    /** Deletes the chat's pictures (with "Clear chats"). */
    fun deleteAll() { dir.listFiles().orEmpty().forEach { it.delete() } }

    companion object {
        fun toRgb(src: Bitmap, max: Int): RgbImage {
            val (w, h) = SeeRules.fit(src.width, src.height, max)
            val b = (if (w == src.width && h == src.height) src else src.scale(w, h)).let {
                if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, false)
            }
            val px = IntArray(w * h)
            b.getPixels(px, 0, w, 0, 0, w, h)
            val rgb = ByteArray(w * h * 3)
            for (i in px.indices) {
                val p = px[i]
                rgb[i * 3] = (p shr 16).toByte(); rgb[i * 3 + 1] = (p shr 8).toByte(); rgb[i * 3 + 2] = p.toByte()
            }
            return RgbImage(w, h, rgb)
        }

        fun clock(seconds: Int) = "%d:%02d".format(seconds / 60, seconds % 60)
    }
}
