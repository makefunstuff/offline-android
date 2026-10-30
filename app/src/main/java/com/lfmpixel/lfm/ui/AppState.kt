package com.lfmpixel.lfm.ui

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lfmpixel.lfm.data.EntryKind

sealed interface Route {
    data object Today : Route
    data object Chat : Route
    data class Library(val tab: EntryKind = EntryKind.Note) : Route
    data object Todos : Route
    data object Settings : Route
    data object Models : Route
    data class Import(val uris: List<Uri>) : Route
    data object Tools : Route
    data object History : Route
    data object Privacy : Route
    data object FirstRun : Route
    data class Search(val id: Long) : Route
    data object WebSearch : Route
}

/** Where a voice capture goes: to the assistant, or straight into the library. */
sealed interface VoiceTarget {
    data object Assistant : VoiceTarget
    data class Library(val kind: EntryKind) : VoiceTarget
}

/** The open entry sheet: a new entry of a kind, or an existing one. */
data class EntrySheet(val id: Long? = null, val kind: EntryKind = EntryKind.Note, val text: String = "")

class Nav(start: Route) {
    val stack = mutableStateListOf(start)
    val current get() = stack.last()
    var entrySheet by mutableStateOf<EntrySheet?>(null)
    var voiceInfo by mutableStateOf<String?>(null)

    fun go(r: Route) {
        // The four places in the bottom bar replace each other instead of piling up.
        if (r.isTab() && current.isTab()) {
            stack.removeAt(stack.lastIndex)
            if (r != Route.Today && stack.isEmpty()) stack.add(Route.Today)
        }
        if (stack.lastOrNull() != r) stack.add(r)
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun reset(r: Route) {
        stack.clear(); stack.add(r)
    }
}

fun Route.isTab() = this == Route.Today || this is Route.Library || this == Route.Todos
