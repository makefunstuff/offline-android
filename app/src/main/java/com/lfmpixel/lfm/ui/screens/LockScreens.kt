package com.lfmpixel.lfm.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lfmpixel.lfm.AppGraph
import com.lfmpixel.lfm.settings.LockMode
import com.lfmpixel.lfm.ui.components.Btn
import com.lfmpixel.lfm.ui.components.BtnKind
import com.lfmpixel.lfm.ui.components.LIcon
import com.lfmpixel.lfm.ui.components.Txt
import com.lfmpixel.lfm.ui.theme.Lfm
import com.lfmpixel.lfm.ui.theme.LfmType
import kotlinx.coroutines.launch

/** True while the journal is behind the lock: Journal only, or the whole app (then the lock screen covers it anyway). */
@Composable
fun journalLocked(g: AppGraph): Boolean {
    val mode = g.settings.prefs.collectAsState(null).value?.lockMode ?: g.lock.mode
    val open by g.lock.open.collectAsState()
    return mode != LockMode.Off && !open
}

/**
 * What a locked place shows: a lock, a line on what is behind it, and Unlock (the phone's fingerprint, face or screen
 * lock). [ask] opens the prompt at once, as the whole-app lock does when Inkling comes on screen.
 */
@Composable
fun Locked(g: AppGraph, title: String, body: String, prompt: String, modifier: Modifier = Modifier, ask: Boolean = false) {
    val c = Lfm.c
    val scope = rememberCoroutineScope()
    if (ask) LaunchedEffect(Unit) { g.lock.unlock(prompt) }
    Box(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(56.dp).border(1.dp, c.control, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                LIcon("lock", size = 26.dp, tint = c.text)
            }
            Txt(title, LfmType.header, modifier = Modifier.padding(top = 6.dp).semantics { heading() }, textAlign = TextAlign.Center)
            Txt(body, LfmType.small, c.text2, textAlign = TextAlign.Center)
            Btn("Unlock", { scope.launch { g.lock.unlock(prompt) } }, Modifier.padding(top = 8.dp), BtnKind.Primary, icon = "lock")
        }
    }
}

/** The whole-app lock: covers everything until the owner unlocks. */
@Composable
fun LockScreen(g: AppGraph) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Locked(g, "Inkling is locked", "Unlock with your fingerprint, face or screen lock.", "Unlock Inkling", ask = true)
    }
}
