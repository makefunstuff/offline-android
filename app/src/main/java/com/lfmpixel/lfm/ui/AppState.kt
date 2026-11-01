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
    data class Library(val filter: com.lfmpixel.lfm.ui.screens.LibFilter = com.lfmpixel.lfm.ui.screens.LibFilter.All) : Route
    data object Todos : Route
    data object Settings : Route
    data object Models : Route
    data class Import(val uris: List<Uri>) : Route
    data object Tools : Route
    data object History : Route
    data object Privacy : Route
    data object Knows : Route
    /** Settings → Crash reports: the local crash log. */
    data object Crashes : Route
    data object FirstRun : Route
    data class Search(val id: Long) : Route
    data object WebSearch : Route
    data object Camera : Route
    data object Talk : Route
    data object Voice : Route
    /** Edge mode: the owner's own server. */
    data object Server : Route
    /** Focus: the running timer of graph.focus (notebook-spec A.1, proposal 5). */
    data object Focus : Route
    /** Brain dump: a new one, or an existing dump note (proposal 3). */
    data class Dump(val id: Long? = null, val opened: Long = System.nanoTime()) : Route
    /** The full-screen editor: an existing entry, or a new one of [kind]. */
    data class Editor(val id: Long? = null, val kind: EntryKind = EntryKind.Note, val opened: Long = System.nanoTime()) : Route
}

/** Where a voice capture goes: to the assistant, or straight into the library. */
sealed interface VoiceTarget {
    data object Assistant : VoiceTarget
    data class Library(val kind: EntryKind) : VoiceTarget
    /** The camera's question field. */
    data object Question : VoiceTarget
    /** The Ink line on today's page: the words are put in the line, to be read before they go on the page. */
    data object Line : VoiceTarget
    /** The brain dump being written: the words are added to it. */
    data object Dump : VoiceTarget
}

/** A short message at the bottom with an optional Undo, shown for a few seconds (ux-spec-v1 section 6). */
class Snack(val text: String, val undo: (suspend () -> Unit)? = null) {
    val at = System.nanoTime()
    /** Undo runs once, however fast it is tapped twice. */
    var undone = false
}

class Nav(start: Route) {
    val stack = mutableStateListOf(start)
    val current get() = stack.last()
    var voiceInfo by mutableStateOf<String?>(null)
    var snack by mutableStateOf<Snack?>(null)
    /** What the mic heard for the Ink line, until the line takes it. */
    var heard by mutableStateOf<String?>(null)
    /** "Not now" on the Now card: these go to the back of the queue, until the app is closed. */
    val skipped = mutableStateListOf<Long>()
    /** Bumped to put the caret in the Ink line (the "Write a line" shortcut). */
    var inkFocus by mutableStateOf(0)
    /** The Daily Page shown, as days before today (0: today's page). */
    var daysBack by mutableStateOf(0L)
    /** True while the last change of screen went forward (a push), for the direction of the transition. */
    var forward by mutableStateOf(true)
        private set

    fun show(text: String, undo: (suspend () -> Unit)? = null) { snack = Snack(text, undo) }

    fun go(r: Route) {
        // The four places in the bottom bar replace each other instead of piling up.
        if (r.isTab() && current.isTab()) {
            stack.removeAt(stack.lastIndex)
            if (r != Route.Today && stack.isEmpty()) stack.add(Route.Today)
        }
        forward = true
        if (stack.lastOrNull() != r) stack.add(r)
    }

    /** Swaps the screen on top for another, as when a new entry's editor becomes that entry's. */
    fun replace(r: Route) {
        stack[stack.lastIndex] = r
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        forward = false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun reset(r: Route) {
        stack.clear(); stack.add(r)
    }
}

fun Route.isTab() = this == Route.Today || this is Route.Library || this == Route.Todos
