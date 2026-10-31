package com.lfmpixel.lfm.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class SearchMode(val label: String) { Off("Off"), AskFirst("Ask first"), Automatic("Automatic") }

enum class ToolPolicy { Ask, Do }

data class Prefs(
    val firstRunDone: Boolean = false,
    val thinking: Boolean = false,
    val searchMode: SearchMode = SearchMode.AskFirst,
    val warnPersonalWords: Boolean = true,
    val threads: Int = 6,
    val contextTokens: Int = 8192,
    /** Minutes idle before models are unloaded; 0 keeps them loaded. */
    val unloadAfterMinutes: Int = 5,
    /** The chat model's file name; empty means the one added last. */
    val chatModel: String = "",
    val toolPolicies: Map<String, ToolPolicy> = emptyMap(),
    val netEnabled: Boolean = true,
    val snippetsOnly: Boolean = false,
    val downloadWifiOnly: Boolean = true,
)

private val Context.store by preferencesDataStore("settings")

class Settings(private val context: Context) {
    private object K {
        val firstRun = booleanPreferencesKey("first_run_done")
        val thinking = booleanPreferencesKey("thinking")
        val search = stringPreferencesKey("search_mode")
        val warn = booleanPreferencesKey("warn_personal")
        val threads = intPreferencesKey("threads")
        val ctx = intPreferencesKey("context_tokens")
        val unload = intPreferencesKey("unload_minutes")
        val policies = stringPreferencesKey("tool_policies") // "name=Ask;name2=Do"
        val net = booleanPreferencesKey("net_enabled")
        val snippets = booleanPreferencesKey("snippets_only")
        val wifiOnly = booleanPreferencesKey("download_wifi_only")
        val chatModel = stringPreferencesKey("chat_model")
    }

    val prefs: Flow<Prefs> = context.store.data.map { it.toPrefs() }

    suspend fun now(): Prefs = prefs.first()

    private fun Preferences.toPrefs() = Prefs(
        firstRunDone = this[K.firstRun] ?: false,
        thinking = this[K.thinking] ?: false,
        searchMode = this[K.search]?.let { runCatching { SearchMode.valueOf(it) }.getOrNull() } ?: SearchMode.AskFirst,
        warnPersonalWords = this[K.warn] ?: true,
        threads = this[K.threads] ?: defaultThreads(),
        contextTokens = this[K.ctx] ?: 8192,
        unloadAfterMinutes = this[K.unload] ?: 5,
        toolPolicies = (this[K.policies] ?: "").split(';').mapNotNull { p ->
            val (k, v) = p.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
            runCatching { k to ToolPolicy.valueOf(v) }.getOrNull()
        }.toMap(),
        netEnabled = this[K.net] ?: true,
        snippetsOnly = this[K.snippets] ?: false,
        downloadWifiOnly = this[K.wifiOnly] ?: true,
        chatModel = this[K.chatModel] ?: "",
    )

    suspend fun setFirstRunDone() = context.store.edit { it[K.firstRun] = true }
    suspend fun setThinking(on: Boolean) = context.store.edit { it[K.thinking] = on }
    suspend fun setSearchMode(m: SearchMode) = context.store.edit { it[K.search] = m.name }
    suspend fun setWarnPersonal(on: Boolean) = context.store.edit { it[K.warn] = on }
    suspend fun setThreads(n: Int) = context.store.edit { it[K.threads] = n }
    suspend fun setUnloadMinutes(n: Int) = context.store.edit { it[K.unload] = n }
    suspend fun setNetEnabled(on: Boolean) = context.store.edit { it[K.net] = on }
    suspend fun setSnippetsOnly(on: Boolean) = context.store.edit { it[K.snippets] = on }
    suspend fun setDownloadWifiOnly(on: Boolean) = context.store.edit { it[K.wifiOnly] = on }
    suspend fun setChatModel(fileName: String) = context.store.edit { it[K.chatModel] = fileName }
    suspend fun setToolPolicy(tool: String, policy: ToolPolicy) = context.store.edit {
        val map = it.toPrefs().toolPolicies + (tool to policy)
        it[K.policies] = map.entries.joinToString(";") { (k, v) -> "$k=${v.name}" }
    }

    companion object {
        /** Six threads on the Pixel's eight cores (spec section 7, battery and heat); fewer on smaller devices. */
        fun defaultThreads() = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
    }
}
