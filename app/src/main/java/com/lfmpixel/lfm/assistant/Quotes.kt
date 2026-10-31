package com.lfmpixel.lfm.assistant

import com.lfmpixel.lfm.data.Entry
import com.lfmpixel.lfm.data.EntryKind

/**
 * Text shared to Inkling from another app becomes a quote on today's page (notebook-spec proposal 5): a note tagged
 * `reading`, its body the quote and then "— where it came from". Nothing is fetched from a shared link.
 */
object Quotes {
    private val url = Regex("""https?://\S+""")

    /** The quote for what was shared, or null when there is no text in it. */
    fun fromShare(text: String?, subject: String?, now: Long = System.currentTimeMillis()): Entry? {
        val raw = text?.trim().orEmpty()
        // Browsers send the selection in quotes with the page's address after it: only a link at the very end is the
        // source; links inside the words stay part of the quote.
        val last = url.findAll(raw).lastOrNull()?.takeIf { raw.substring(it.range.last + 1).isBlank() }
        val link = last?.value?.substringBefore("#:~:")
        val quote = (if (last != null) raw.removeRange(last.range) else raw).trim().trim('"', '“', '”').trim()
        val title = subject?.trim()?.takeIf { it.isNotEmpty() && it != link }
        if (quote.isEmpty() && link == null) return null
        val words = quote.ifEmpty { title ?: link!! }
        val source = listOfNotNull(title, link).joinToString(" · ")
        return Entry(
            kind = EntryKind.Note,
            title = firstWords(words),
            body = words + if (source.isNotEmpty()) "\n— $source" else "",
            tags = "reading",
            source = "share",
            createdAt = now,
            updatedAt = now,
        )
    }

    /** The quoted words of a quote, without its source line. */
    fun words(e: Entry): String = e.body.ifEmpty { e.title }.lines().takeWhile { !it.trim().startsWith("— ") }
        .joinToString(" ") { it.trim() }.trim().ifEmpty { e.title }

    /** Where a quote came from ("Title · link"), or "". */
    fun source(e: Entry): String = e.body.lines().map { it.trim() }.firstOrNull { it.startsWith("— ") }?.removePrefix("— ").orEmpty()

    private fun firstWords(s: String): String {
        val line = s.lineSequence().first().trim()
        return if (line.length <= 80) line else line.take(79).substringBeforeLast(' ').trimEnd(',', ';', ':') + "…"
    }
}
