package com.lfmpixel.lfm.net

import android.content.Context

/** The general web provider. Wikipedia is a separate switch, so "your SearXNG + Wikipedia" is one setting. */
enum class WebProvider(val label: String, val shown: String) {
    None("None", ""), SearXNG("SearXNG", "your SearXNG"), Brave("Brave", "Brave"), Mojeek("Mojeek", "Mojeek")
}

/** LFM Net's settings: the only things it keeps besides the latest result addresses. */
data class NetConfig(
    val provider: WebProvider = WebProvider.None,
    val searxngUrl: String = "",
    val searxngUser: String = "",
    val searxngPassword: String = "",
    val braveKey: String = "",
    val mojeekKey: String = "",
    val wikipedia: Boolean = true,
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
        wikipedia = prefs.getBoolean("wikipedia", true),
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
}
