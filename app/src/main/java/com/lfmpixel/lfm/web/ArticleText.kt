package com.lfmpixel.lfm.web

/**
 * Reads a web page on the phone: finds the article, drops menus and footers, and keeps the paragraphs that
 * answer the question (spec section 4, "reading pages"). Plain text handling, no model.
 */
object ArticleText {
    data class Article(val title: String, val paragraphs: List<String>)

    private val opt = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)

    fun extract(html: String): Article {
        val title = Regex("<title[^>]*>(.*?)</title>", opt).find(html)?.groupValues?.get(1)?.let(::text)
            ?: Regex("<h1[^>]*>(.*?)</h1>", opt).find(html)?.groupValues?.get(1)?.let(::text) ?: ""
        var body = Regex("<body[^>]*>(.*)</body>", opt).find(html)?.groupValues?.get(1) ?: html
        for (tag in listOf("script", "style", "noscript", "svg", "nav", "header", "footer", "aside", "form", "button", "select")) {
            body = body.replace(Regex("<$tag\\b[^>]*>.*?</$tag\\s*>", opt), " ")
        }
        val region = listOf("article", "main").firstNotNullOfOrNull { tag ->
            Regex("<$tag\\b[^>]*>(.*?)</$tag\\s*>", opt).findAll(body).map { it.groupValues[1] }.maxByOrNull { it.length }
                ?.takeIf { text(it).length >= 300 }
        } ?: body
        val blocks = region
            .replace(Regex("</?(p|div|section|h[1-6]|li|ul|ol|tr|table|blockquote|dd|dt|br|hr)\\b[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</?(td|th)\\b[^>]*>", RegexOption.IGNORE_CASE), " | ")
            .split('\n')
            .map { text(it).trim(' ', '|') .replace(Regex("(\\s*\\|\\s*)+"), " | ") }
            .filter { it.length >= 40 || (it.length >= 12 && it.any(Char::isDigit)) }
        return Article(title, blocks.distinct())
    }

    /** At most [max] characters of the paragraphs that share most words with the query, kept in page order. */
    fun excerpt(a: Article, query: String, max: Int = 1500): String {
        val terms = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 && it !in stop }.toSet()
        val scored = a.paragraphs.mapIndexed { i, p ->
            val low = p.lowercase()
            Triple(i, p, terms.count { low.contains(it) } * 10 + (if (p.any(Char::isDigit)) 1 else 0))
        }
        val picked = mutableListOf<Pair<Int, String>>()
        var used = 0
        val order = if (scored.any { it.third >= 10 }) scored.sortedByDescending { it.third } else scored
        for ((i, p, _) in order) {
            val cut = if (p.length > 600) p.take(600).substringBeforeLast(' ') + "…" else p
            if (used + cut.length > max) { if (used == 0) { picked += i to cut.take(max); break } else continue }
            picked += i to cut
            used += cut.length + 1
        }
        return picked.sortedBy { it.first }.joinToString("\n") { it.second }
    }

    private val stop = setOf("the", "and", "for", "with", "how", "what", "much", "does", "are", "was", "who", "when", "where", "which", "about", "from", "this", "that")

    fun text(html: String): String = decode(html.replace(Regex("<[^>]*>"), " ")).replace(Regex("[ \\t\\u00a0]+"), " ").trim()

    private val entity = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")
    private val named = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "mdash" to "—",
        "ndash" to "–", "hellip" to "…", "pound" to "£", "euro" to "€", "copy" to "©", "middot" to "·", "lsquo" to "‘", "rsquo" to "’",
        "ldquo" to "“", "rdquo" to "”", "times" to "×", "deg" to "°", "eacute" to "é", "egrave" to "è", "uuml" to "ü", "ouml" to "ö", "auml" to "ä")

    fun decode(s: String): String = entity.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") -> e.drop(2).toIntOrNull(16)?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: m.value
            e.startsWith("#") -> e.drop(1).toIntOrNull()?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: m.value
            else -> named[e] ?: m.value
        }
    }
}
