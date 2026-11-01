package com.lfmpixel.lfm.net

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** How each search provider is asked, and how its JSON is read. Pure functions, tested without a network. */
object Providers {
    /** SearXNG's JSON API: JSON output must be on in the server's settings.yml (search: formats: [html, json]). */
    fun searxng(base: String, q: String): HttpUrl? {
        val root = base.trim().trimEnd('/').let { if ("://" in it) it else "https://$it" }.toHttpUrlOrNull() ?: return null
        return root.newBuilder().addPathSegment("search").addQueryParameter("q", q).addQueryParameter("format", "json").build()
    }

    fun parseSearxng(json: String): List<SearchHit> {
        val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val r = results.optJSONObject(i) ?: return@mapNotNull null
            hit(r.optString("title"), r.optString("url"), r.optString("content"), "web")
        }
    }

    fun brave(q: String): HttpUrl = "https://api.search.brave.com/res/v1/web/search".toHttpUrlOrNull()!!.newBuilder()
        .addQueryParameter("q", q).addQueryParameter("count", "8").build()

    fun parseBrave(json: String): List<SearchHit> {
        val results = JSONObject(json).optJSONObject("web")?.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val r = results.optJSONObject(i) ?: return@mapNotNull null
            hit(r.optString("title"), r.optString("url"), r.optString("description"), "web")
        }
    }

    fun mojeek(q: String, key: String): HttpUrl = "https://www.mojeek.com/search".toHttpUrlOrNull()!!.newBuilder()
        .addQueryParameter("q", q).addQueryParameter("fmt", "json").addQueryParameter("api_key", key).build()

    fun parseMojeek(json: String): List<SearchHit> {
        val results = JSONObject(json).optJSONObject("response")?.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val r = results.optJSONObject(i) ?: return@mapNotNull null
            hit(r.optString("title"), r.optString("url"), r.optString("desc"), "web")
        }
    }

    /** MediaWiki's search API; [base] is https://en.wikipedia.org (tests pass their own). */
    fun wikipedia(base: String, q: String): HttpUrl = "$base/w/api.php".toHttpUrlOrNull()!!.newBuilder()
        .addQueryParameter("action", "query").addQueryParameter("list", "search").addQueryParameter("srsearch", q)
        .addQueryParameter("srlimit", "3").addQueryParameter("format", "json").addQueryParameter("formatversion", "2").build()

    fun parseWikipedia(json: String, base: String): List<SearchHit> {
        val results = JSONObject(json).optJSONObject("query")?.optJSONArray("search") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val r = results.optJSONObject(i) ?: return@mapNotNull null
            val title = r.optString("title")
            val url = "$base/wiki".toHttpUrlOrNull()?.newBuilder()?.addPathSegment(title.replace(' ', '_'))?.build() ?: return@mapNotNull null
            hit(title, url.toString(), r.optString("snippet"), "wikipedia")
        }
    }

    private fun hit(title: String, url: String, snippet: String, source: String): SearchHit? {
        val u = url.toHttpUrlOrNull() ?: return null
        return SearchHit(Html.text(title).ifBlank { u.host }, u.toString(), Html.text(snippet).take(400), source)
    }

    /** Whether a title shares a word with the query, by its first five letters ("Plumbing" and "plumber"). */
    fun related(title: String, query: String): Boolean {
        fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.map { it.take(5) }.toSet()
        return words(title).any { it in words(query) }
    }

    /** The address for the network log: never an API key. */
    fun redact(url: HttpUrl): String {
        val b = url.newBuilder().username("").password("")
        listOf("api_key", "key", "token", "apikey").forEach { if (url.queryParameter(it) != null) b.setQueryParameter(it, "…") }
        return b.build().toString()
    }
}

/** Just enough HTML handling for snippets: drop tags, decode the common entities. */
object Html {
    private val entity = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")
    private val named = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "mdash" to "—", "ndash" to "–", "hellip" to "…",
        "pound" to "£", "euro" to "€", "copy" to "©", "middot" to "·", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”")

    fun decode(s: String): String = entity.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") -> e.drop(2).toIntOrNull(16)?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: m.value
            e.startsWith("#") -> e.drop(1).toIntOrNull()?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: m.value
            else -> named[e] ?: m.value
        }
    }

    fun text(s: String): String = decode(s.replace(Regex("<[^>]*>"), "")).replace(Regex("\\s+"), " ").trim()

    /**
     * A page cut down to fit through Binder: scripts, styles, SVG, comments and the like go; the markup that tells
     * the article from the menus stays, for Inkling to read.
     */
    // 100k chars is 200 KB in a Parcel: three pages at once stay well inside Binder's 1 MB buffer.
    fun slim(html: String, maxChars: Int = 100_000): String {
        var s = html.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), " ")
        for (tag in listOf("script", "style", "noscript", "svg", "template", "iframe", "canvas", "select")) {
            s = s.replace(Regex("<$tag\\b[^>]*>.*?</$tag\\s*>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), " ")
        }
        // Attributes other than the few that mark structure are noise here.
        s = s.replace(Regex("""\s(?!(?:id|class|role)=)[a-zA-Z-:]+=("[^"]*"|'[^']*'|[^\s>]+)"""), "")
        s = s.replace(Regex("\\s+"), " ")
        return if (s.length > maxChars) s.take(maxChars) else s
    }
}
