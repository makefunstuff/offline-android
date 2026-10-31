package com.lfmpixel.lfm.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.lfmpixel.lfm.ui.theme.Lfm
import com.lfmpixel.lfm.ui.theme.LfmType

/** The highlighter: yellow drawn behind dark text, never yellow text. */
@Composable
fun marked(text: String): AnnotatedString {
    val c = Lfm.c
    return buildAnnotatedString {
        withStyle(SpanStyle(background = c.highlight, color = c.onHighlight)) { append(" $text ") }
    }
}

@Composable
fun Marked(text: String, style: TextStyle, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Text(marked(text), style = style, modifier = modifier, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
fun Txt(text: String, style: TextStyle = LfmType.body, color: Color = Lfm.c.text, modifier: Modifier = Modifier,
        maxLines: Int = Int.MAX_VALUE, decoration: TextDecoration? = null, textAlign: androidx.compose.ui.text.style.TextAlign? = null) {
    Text(text, style = style, color = color, modifier = modifier, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        textDecoration = decoration, textAlign = textAlign)
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier) = Txt(text, LfmType.label, Lfm.c.text2, modifier)

@Composable
fun SectionLabel(text: String) = Label(text, Modifier.padding(top = 20.dp, bottom = 6.dp))

/** The mic: the one control that is always yellow. 1 px ink edge in light mode, yellow edge in dark. */
@Composable
fun MicButton(onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 42.dp, listening: Boolean = false) {
    val c = Lfm.c
    val shape = RoundedCornerShape(if (size >= 56.dp) 8.dp else 6.dp)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(if (listening) c.text else c.highlight)
            .border(1.dp, if (c.dark) c.highlight else c.text, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (listening) "Stop listening" else "Speak" },
        contentAlignment = Alignment.Center,
    ) {
        LIcon(if (listening) "stop" else "mic", tint = if (listening) c.bg else c.onHighlight)
    }
}

@Composable
fun IconButtonPlain(icon: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = Lfm.c.text2, desc: String = icon) {
    Box(modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).semantics { contentDescription = desc },
        contentAlignment = Alignment.Center) { LIcon(icon, tint = tint) }
}

enum class BtnKind { Primary, Secondary }

@Composable
fun Btn(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, kind: BtnKind = BtnKind.Secondary, icon: String? = null,
        small: Boolean = false, enabled: Boolean = true) {
    val c = Lfm.c
    val shape = RoundedCornerShape(6.dp)
    val bg = if (kind == BtnKind.Primary) c.text else Color.Transparent
    val fg = if (kind == BtnKind.Primary) c.bg else c.text
    Row(
        modifier
            .height(if (small) 32.dp else 44.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, if (kind == BtnKind.Primary) c.text else c.control, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (small) 10.dp else 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (enabled) fg else c.text3
        icon?.let { LIcon(it, size = 16.dp, tint = tint) }
        Txt(text, if (small) LfmType.button.copy(fontSize = LfmType.meta.fontSize) else LfmType.button, tint, maxLines = 1)
    }
}

@Composable
fun Header(title: String, onBack: (() -> Unit)?, leftIcon: String = "back", right: @Composable RowScope.() -> Unit = {}) {
    val c = Lfm.c
    Column {
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButtonPlain(leftIcon, onBack, desc = "Back") else Spacer(Modifier.width(10.dp))
            Txt(title, LfmType.header, modifier = Modifier.weight(1f).padding(start = 4.dp))
            right()
        }
        Divider()
    }
}

@Composable
fun Divider(modifier: Modifier = Modifier) = Box(modifier.fillMaxWidth().height(1.dp).background(Lfm.c.line))

@Composable
fun Pill(text: String, icon: String? = null, modifier: Modifier = Modifier) {
    val c = Lfm.c
    // The control grey, not the line grey: the edge has to show (3:1 against the page) in both themes.
    Row(modifier.border(1.dp, c.control, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        icon?.let { LIcon(it, size = 16.dp, tint = c.text2) }
        Txt(text, LfmType.meta, c.text2, maxLines = 1)
    }
}

@Composable
fun Tag(text: String, icon: String? = null) {
    val c = Lfm.c
    Row(Modifier.border(1.dp, c.line, RoundedCornerShape(4.dp)).padding(horizontal = 7.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        icon?.let { LIcon(it, size = 14.dp, tint = c.text2) }
        Txt(text, LfmType.meta.copy(fontSize = LfmType.meta.fontSize * 0.96f), c.text2, maxLines = 1)
    }
}

/** Selected tab is plain ink, not yellow. */
@Composable
fun Tabs(items: List<String>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = Lfm.c
    Row(modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { t ->
            val on = t == selected
            val shape = RoundedCornerShape(6.dp)
            Box(Modifier.clip(shape).background(if (on) c.text else Color.Transparent).border(1.dp, if (on) c.text else c.control, shape)
                .clickable(role = Role.Tab) { onSelect(t) }.padding(horizontal = 14.dp, vertical = 6.dp)) {
                Txt(t, LfmType.small.copy(fontSize = LfmType.small.fontSize * 0.965f, fontWeight = if (on) LfmType.button.fontWeight else null),
                    if (on) c.bg else c.text2)
            }
        }
    }
}

@Composable
fun Segmented(items: List<String>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = Lfm.c
    val shape = RoundedCornerShape(6.dp)
    Row(modifier.fillMaxWidth().clip(shape).border(1.dp, c.control, shape)) {
        items.forEachIndexed { i, t ->
            if (i > 0) Box(Modifier.width(1.dp).height(36.dp).background(c.control))
            val on = t == selected
            Box(Modifier.weight(1f).height(36.dp).background(if (on) c.text else Color.Transparent).clickable(role = Role.RadioButton) { onSelect(t) },
                contentAlignment = Alignment.Center) {
                Txt(t, LfmType.small.copy(fontSize = LfmType.meta.fontSize * 1.08f, fontWeight = if (on) LfmType.button.fontWeight else null), if (on) c.bg else c.text2)
            }
        }
    }
}

/** A square-ish switch: ink when on. */
@Composable
fun Switch(on: Boolean, onChange: (Boolean) -> Unit) {
    val c = Lfm.c
    val shape = RoundedCornerShape(6.dp)
    Box(Modifier.width(40.dp).height(22.dp).clip(shape).background(if (on) c.text else Color.Transparent)
        .border(1.dp, if (on) c.text else c.control, shape).clickable(role = Role.Switch) { onChange(!on) }) {
        Box(Modifier.padding(start = if (on) 21.dp else 3.dp, top = 3.dp).size(14.dp).clip(RoundedCornerShape(3.dp)).background(if (on) c.bg else c.text3))
    }
}

@Composable
fun CheckBox(checked: Boolean, onChange: ((Boolean) -> Unit)?) {
    val c = Lfm.c
    val shape = RoundedCornerShape(4.dp)
    // With no onChange the row around it takes the tap (and says the state); the box only draws.
    Box(Modifier.size(48.dp).let { m -> onChange?.let { m.toggleable(checked, role = Role.Checkbox, onValueChange = it) } ?: m },
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(18.dp).clip(shape).background(if (checked) c.text else Color.Transparent).border(1.dp, if (checked) c.text else c.control, shape),
            contentAlignment = Alignment.Center) {
            if (checked) LIcon("check", size = 13.dp, tint = c.bg, stroke = 2.5f)
        }
    }
}

/** A bordered card with 6 px corners. */
@Composable
fun Card(modifier: Modifier = Modifier, borderColor: Color = Lfm.c.line, fill: Color = Color.Transparent, padding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
         onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(modifier.clip(shape).background(fill).border(BorderStroke(1.dp, borderColor), shape)
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(padding)) { content() }
}

/** A row with title, subtitle and an optional icon and trailing part, divided by 1 px lines. */
@Composable
fun ListRow(title: String, subtitle: String = "", icon: String? = null, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    val c = Lfm.c
    Row(Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        icon?.let { LIcon(it, tint = c.text2) }
        Column(Modifier.weight(1f)) {
            Txt(title, LfmType.body.copy(fontWeight = LfmType.rowTitle.fontWeight))
            if (subtitle.isNotEmpty()) Txt(subtitle, LfmType.label.copy(fontWeight = null), c.text2)
        }
        trailing()
    }
}

/** The composer: "Ask, capture or do…" with camera and mic. */
@Composable
fun Composer(value: String, onChange: (String) -> Unit, placeholder: String, onSend: () -> Unit, onMic: () -> Unit,
             modifier: Modifier = Modifier, listening: Boolean = false, enabled: Boolean = true, onCamera: (() -> Unit)? = null,
             onStop: (() -> Unit)? = null) {
    val c = Lfm.c
    val shape = RoundedCornerShape(8.dp)
    Row(modifier.fillMaxWidth().heightIn(min = 52.dp).clip(shape).background(c.fill).border(1.dp, c.control, shape)
        .padding(start = 14.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Txt(placeholder, LfmType.body, c.text2, maxLines = 1)
            BasicTextField(value, onChange, enabled = enabled, textStyle = LfmType.body.copy(color = c.text), cursorBrush = SolidColor(c.text),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { onSend() }),
                maxLines = 5, modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder })
        }
        if (value.isNotBlank()) IconButtonPlain("send", onSend, tint = c.text, desc = if (onStop != null) "Queue" else "Send")
        if (onStop != null) {
            // While an answer is written, Stop sits by the mic: ink, square, named; there even while you type.
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(6.dp)).background(c.text).clickable(role = Role.Button, onClick = onStop)
                .semantics { contentDescription = "Stop" }, contentAlignment = Alignment.Center) { LIcon("stop", tint = c.bg) }
        } else if (onCamera != null && value.isBlank()) {
            IconButtonPlain("camera", onCamera, desc = "Camera")
        }
        MicButton(onMic, listening = listening)
    }
}

/** The ink + button for a new entry; the mic stays the only yellow one. */
@Composable
fun Fab(icon: String, onClick: () -> Unit, desc: String, modifier: Modifier = Modifier) {
    val c = Lfm.c
    val shape = RoundedCornerShape(8.dp)
    Box(modifier.size(56.dp).clip(shape).background(c.text).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = desc },
        contentAlignment = Alignment.Center) { LIcon(icon, tint = c.bg) }
}

/** A search or quick-add line: always visible, keyboard up when [focus] is set. Enter calls [onDone]. */
@Composable
fun InputLine(value: String, onChange: (String) -> Unit, placeholder: String, icon: String, modifier: Modifier = Modifier,
              focus: androidx.compose.ui.focus.FocusRequester? = null, imeAction: ImeAction = ImeAction.Search, onDone: () -> Unit = {},
              trailing: @Composable () -> Unit = {}) {
    val c = Lfm.c
    val shape = RoundedCornerShape(8.dp)
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape).background(c.fill).border(1.dp, c.control, shape).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LIcon(icon, size = 18.dp, tint = c.text2)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Txt(placeholder, LfmType.body, c.text2, maxLines = 1)
            BasicTextField(value, onChange, singleLine = true, textStyle = LfmType.body.copy(color = c.text), cursorBrush = SolidColor(c.text),
                keyboardOptions = KeyboardOptions(imeAction = imeAction, capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences),
                keyboardActions = KeyboardActions(onAny = { onDone() }),
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).let { m -> focus?.let { m.focusRequester(it) } ?: m }
                    .semantics { contentDescription = placeholder })
        }
        trailing()
    }
}

/** What a swipe does: its word, icon, and the action. */
class SwipeAction(val label: String, val icon: String, val run: () -> Unit)

/**
 * A row you can swipe: right for [right] (pin, done), left for [left] (delete). Past 40 % of the width the action
 * shows in ink and runs on release; the row springs back (the list then moves it). Both actions are also TalkBack
 * custom actions, and a long-press menu where the caller offers one.
 */
@Composable
fun SwipeRow(right: SwipeAction?, left: SwipeAction?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = Lfm.c
    // The gesture reads the latest actions without restarting, so a recomposition never cuts a swipe off.
    val rightNow by androidx.compose.runtime.rememberUpdatedState(right)
    val leftNow by androidx.compose.runtime.rememberUpdatedState(left)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val offset = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    var width by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(1) }
    val past = kotlin.math.abs(offset.value) > width * 0.4f
    val action = if (offset.value > 0) right else if (offset.value < 0) left else null
    Box(modifier.fillMaxWidth().onSizeChanged { width = it.width }
        .semantics { customActions = listOfNotNull(right, left).map { a -> CustomAccessibilityAction(a.label) { a.run(); true } } }) {
        if (action != null) Row(Modifier.matchParentSize().background(if (past) c.text else c.fill).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (offset.value > 0) Arrangement.Start else Arrangement.End) {
            LIcon(action.icon, tint = if (past) c.bg else c.text2)
            Txt(action.label, LfmType.button, if (past) c.bg else c.text2, Modifier.padding(start = 8.dp))
        }
        Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(offset.value.toInt(), 0) }.background(c.bg)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val a = if (offset.value > 0) rightNow else leftNow
                        val fire = kotlin.math.abs(offset.value) > width * 0.4f
                        scope.launch {
                            if (fire && a != null) a.run()
                            offset.animateTo(0f, androidx.compose.animation.core.spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow))
                        }
                    },
                    onDragCancel = { scope.launch { offset.animateTo(0f) } },
                ) { change, drag ->
                    val next = offset.value + drag
                    if ((next > 0 && rightNow == null) || (next < 0 && leftNow == null)) return@detectHorizontalDragGestures
                    change.consume()
                    scope.launch { offset.snapTo(next) }
                }
            }) { content() }
    }
}

/** The snackbar: one line and an optional Undo, for 5 s (10 s with TalkBack), fading and rising in. */
@Composable
fun SnackHost(snack: com.lfmpixel.lfm.ui.Snack?, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val c = Lfm.c
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(snack) {
        if (snack == null) return@LaunchedEffect
        val a11y = ctx.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        kotlinx.coroutines.delay(if (a11y?.isTouchExplorationEnabled == true) 10_000 else 5_000)
        onDone()
    }
    val reduced = com.lfmpixel.lfm.ui.theme.LfmMotion.reduced()
    androidx.compose.animation.AnimatedVisibility(snack != null, modifier,
        enter = if (reduced) androidx.compose.animation.EnterTransition.None else
            androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(com.lfmpixel.lfm.ui.theme.LfmMotion.MEDIUM, easing = com.lfmpixel.lfm.ui.theme.LfmMotion.emphasizedDecelerate)) +
            androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(com.lfmpixel.lfm.ui.theme.LfmMotion.MEDIUM, easing = com.lfmpixel.lfm.ui.theme.LfmMotion.emphasizedDecelerate)) { 24 },
        exit = if (reduced) androidx.compose.animation.ExitTransition.None else
            androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(com.lfmpixel.lfm.ui.theme.LfmMotion.SHORT, easing = com.lfmpixel.lfm.ui.theme.LfmMotion.emphasizedAccelerate))) {
        val last = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(snack) }
        if (snack != null) last.value = snack
        val s = last.value ?: return@AnimatedVisibility
        val shape = RoundedCornerShape(8.dp)
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth().heightIn(min = 48.dp).clip(shape).background(c.text)
            .padding(start = 16.dp, end = 4.dp).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically) {
            Txt(s.text, LfmType.body, c.bg, Modifier.weight(1f).padding(vertical = 12.dp), maxLines = 2)
            s.undo?.let { u ->
                Box(Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) {
                    onDone()
                    if (!s.undone) { s.undone = true; scope.launch { runCatching { u() } } }
                }.padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center) { Txt("Undo", LfmType.button, c.bg, decoration = TextDecoration.Underline) }
            }
        }
    }
}
