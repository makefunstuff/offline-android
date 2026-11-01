package com.lfmpixel.lfm.net

import android.content.Context

/** The general web provider. Wikipedia is a separate switch, so "your SearXNG + Wikipedia" is one setting. */
enum class WebProvider(val label: String, val shown: String) {
    None("None", ""), SearXNG("SearXNG", "your SearXNG"), Brave("Brave", "Brave"), Mojeek("Mojeek", "Mojeek")
}

/** Web search settings: the provider, its address or key, Wikipedia and a proxy. Kept by the network module only. */
data class NetConfig(
    val provider: WebProvider = WebProvider.None,
    val searxngUrl: String = "",
    val searxngUser: String = "",
    val searxngPassword: String = "",
    val braveKey: String = "",
    val mojeekKey: String = "",
    val wikipedia: Boolean = false,         // off until the owner picks it: no search goes anywhere unasked
    val wikiLang: String = "en",
    val proxy: String = "",          // SOCKS, "host:port"; empty for none
) {
    /** The web provider has what it needs (an address or a key). */
    val webReady get() = when (provider) {
        WebProvider.None -> false
        WebProvider.SearXNG -> searxngUrl.isNotBlank()
        WebProvider.Brave -> braveKey.isNotBlank()
        WebProvider.Mojeek -> mojeekKey.isNotBlank()
    }
    val ready get() = webReady || wikipedia

    /** No proxy, or one written as host:port. */
    val proxyOk get() = proxy.isBlank() || NetEngine.proxyOf(proxy) != null

    /** "your SearXNG + Wikipedia" */
    val label get() = listOfNotNull(provider.shown.takeIf { webReady }, "Wikipedia".takeIf { wikipedia }).joinToString(" + ")
}

class NetSettings(context: Context) {
    private val prefs = context.getSharedPreferences("net", Context.MODE_PRIVATE)

    fun load() = NetConfig(
        provider = prefs.getString("provider", null)?.let { runCatching { WebProvider.valueOf(it) }.getOrNull() } ?: WebProvider.None,
        searxngUrl = prefs.getString("searxng_url", "")!!,
        searxngUser = prefs.getString("searxng_user", "")!!,
        searxngPassword = prefs.getString("searxng_password", "")!!,
        braveKey = prefs.getString("brave_key", "")!!,
        mojeekKey = prefs.getString("mojeek_key", "")!!,
        wikipedia = prefs.getBoolean("wikipedia", false),
        wikiLang = prefs.getString("wiki_lang", "en")!!,
        proxy = prefs.getString("proxy", "")!!,
    )

    fun save(c: NetConfig) = prefs.edit()
        .putString("provider", c.provider.name)
        .putString("searxng_url", c.searxngUrl.trim())
        .putString("searxng_user", c.searxngUser)
        .putString("searxng_password", c.searxngPassword)
        .putString("brave_key", c.braveKey.trim())
        .putString("mojeek_key", c.mojeekKey.trim())
        .putBoolean("wikipedia", c.wikipedia)
        .putString("wiki_lang", c.wikiLang.trim().lowercase())
        .putString("proxy", c.proxy.trim())
        .apply()

    /**
     * The old Inkling Net's settings, as its hand-off version exported them (same keys as here). Taken only if nothing
     * was set up here yet; either way the move is marked done.
     */
    fun importLegacy(values: Map<String, Any?>) {
        val here = load()
        val e = prefs.edit()
        if (here.provider == WebProvider.None && !here.wikipedia && here.proxy.isBlank()) {
            IMPORTED.forEach { k ->
                when (val v = values[k]) {
                    is String -> e.putString(k, v)
                    is Boolean -> e.putBoolean(k, v)
                }
            }
        }
        e.putBoolean("imported", true).apply()
    }

    fun imported() = prefs.getBoolean("imported", false)

    private companion object {
        val IMPORTED = listOf("provider", "searxng_url", "searxng_user", "searxng_password", "brave_key", "mojeek_key", "wikipedia", "wiki_lang", "proxy")
    }

    /** Edge mode: the server saved in Settings (its address only; the key is kept sealed in Wren's settings). */
    fun pinEdge(url: String) = prefs.edit().putString("edge_pin", url.trim()).apply()
    fun pinnedEdge(): String? = prefs.getString("edge_pin", null)?.takeIf { it.isNotBlank() }
}
