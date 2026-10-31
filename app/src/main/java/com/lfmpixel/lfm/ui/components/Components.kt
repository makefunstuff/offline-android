package com.lfmpixel.lfm.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
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

/** Highlights already drawn in during this run: they show at once when they come back into view. */
private val drawnIn = java.util.Collections.synchronizedSet(HashSet<String>())

/**
 * The highlighter as a marker stroke: it draws in from the left behind the words (300 ms, emphasized decelerate)
 * the first time a highlight shows, and is simply there after that. The words under the stroke turn ink as it
 * passes, so in dark mode they never sit dark on dark.
 */
@Composable
fun Marked(text: String, style: TextStyle, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    val c = Lfm.c
    val reduced = com.lfmpixel.lfm.ui.theme.LfmMotion.reduced()
    // Drawn in once per place it shows: a changing text (a download's percent, Talk's state) keeps its stroke.
    val progress = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(if (reduced || text in drawnIn) 1f else 0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (progress.value < 1f) progress.animateTo(1f, androidx.compose.animation.core.tween(
            com.lfmpixel.lfm.ui.theme.LfmMotion.LONG, delayMillis = 60, easing = com.lfmpixel.lfm.ui.theme.LfmMotion.emphasizedDecelerate))
        drawnIn += text
    }
    var layout by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    val pad = with(androidx.compose.ui.platform.LocalDensity.current) { 3.dp.toPx() }
    Box(modifier) {
        // Under: the words in the page's ink. Over: the stroke and the words in ink-on-yellow, clipped to the stroke.
        Text(text, style = style, color = c.text, maxLines = maxLines, overflow = TextOverflow.Ellipsis, onTextLayout = { layout = it },
            modifier = Modifier.padding(horizontal = 3.dp))
        Text(text, style = style, color = c.onHighlight, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 3.dp).clearAndSetSemantics { }.drawWithContent {
                val l = layout ?: return@drawWithContent
                val p = progress.value
                if (p <= 0f) return@drawWithContent
                for (i in 0 until l.lineCount) {
                    val left = l.getLineLeft(i) - pad
                    val right = l.getLineRight(i) + pad
                    val top = l.getLineTop(i); val bottom = l.getLineBottom(i)
                    val end = left + (right - left) * p
                    drawRect(c.highlight, androidx.compose.ui.geometry.Offset(left, top), androidx.compose.ui.geometry.Size(end - left, bottom - top))
                    clipRect(left = left, top = top, right = end, bottom = bottom) { this@drawWithContent.drawContent() }
                }
            })
    }
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

/** How loud the mic hears you now, 0..1, for the mic button's pulse. Provided at the root from the recorder. */
val LocalMicLevel = androidx.compose.runtime.compositionLocalOf<androidx.compose.runtime.State<Float>> { androidx.compose.runtime.mutableFloatStateOf(0f) }

/**
 * The mic: the one control that is always yellow. 1 px ink edge in light mode, yellow edge in dark. Listening, it
 * turns ink with a Stop square, and a calm outline breathes out with your voice; back to idle, the yellow eases in.
 */
@Composable
fun MicButton(onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 42.dp, listening: Boolean = false) {
    val c = Lfm.c
    val shape = RoundedCornerShape(if (size >= 56.dp) 8.dp else 6.dp)
    val motion = com.lfmpixel.lfm.ui.theme.LfmMotion
    val fill by androidx.compose.animation.animateColorAsState(if (listening) c.text else c.highlight,
        motion.spec(if (listening) motion.TINY else motion.MEDIUM, if (listening) motion.standard else motion.emphasizedDecelerate), label = "mic")
    val voice = LocalMicLevel.current
    val pulse by androidx.compose.animation.core.animateFloatAsState(if (listening) voice.value.coerceIn(0f, 1f) else 0f, motion.level(), label = "pulse")
    val src = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        // The pulse: an outline that grows up to 8 dp out and fades as it goes, only while listening.
        if (listening) Box(Modifier.matchParentSize().graphicsLayer {
            val grow = 1f + 0.28f * pulse
            scaleX = grow; scaleY = grow; alpha = 0.25f + 0.55f * pulse
        }.border(1.5.dp, c.text, shape))
        Box(
            Modifier
                .matchParentSize()
                .pressScale(src)
                .clip(shape)
                .background(fill)
                .border(1.dp, if (c.dark) c.highlight else c.text, shape)
                .clickable(src, androidx.compose.material3.ripple(color = if (listening) c.bg else c.onHighlight), role = Role.Button, onClick = onClick)
                .semantics { contentDescription = if (listening) "Stop listening" else "Speak" },
            contentAlignment = Alignment.Center,
        ) {
            LIcon(if (listening) "stop" else "mic", tint = if (listening) c.bg else c.onHighlight)
        }
    }
}

/**
 * The pressed state of a button: it dips to 96 % at once and comes back in 150 ms, on top of the ripple. Calm,
 * and felt within a frame of the touch.
 */
@Composable
fun Modifier.pressScale(src: androidx.compose.foundation.interaction.InteractionSource): Modifier {
    val pressed by src.collectIsPressedAsState()
    val reduced = com.lfmpixel.lfm.ui.theme.LfmMotion.reduced()
    if (reduced) return this
    val scale by androidx.compose.animation.core.animateFloatAsState(if (pressed) 0.96f else 1f,
        androidx.compose.animation.core.tween(if (pressed) 60 else com.lfmpixel.lfm.ui.theme.LfmMotion.SHORT,
            easing = com.lfmpixel.lfm.ui.theme.LfmMotion.standard), label = "press")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

@Composable
fun IconButtonPlain(icon: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = Lfm.c.text2, desc: String = icon) {
    Box(modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = desc },
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
    val src = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        modifier
            .heightIn(min = if (small) 32.dp else 44.dp)
            .pressScale(src)
            .clip(shape)
            .background(bg)
            .border(1.dp, if (kind == BtnKind.Primary) c.text else c.control, shape)
            .clickable(src, androidx.compose.material3.ripple(), enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (small) 10.dp else 14.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (enabled) fg else c.text3
        icon?.let { LIcon(it, size = 16.dp, tint = tint) }
        Txt(text, if (small) LfmType.button.copy(fontSize = LfmType.meta.fontSize) else LfmType.button, tint, maxLines = 2)
    }
}

@Composable
fun Header(title: String, onBack: (() -> Unit)?, leftIcon: String = "back", right: @Composable RowScope.() -> Unit = {}) {
    val c = Lfm.c
    Column {
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButtonPlain(leftIcon, onBack, desc = "Back") else Spacer(Modifier.width(10.dp))
            Txt(title, LfmType.header, modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() })
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

/** Selected tab is plain ink, not yellow. The ink moves between tabs in 100 ms. */
@Composable
fun Tabs(items: List<String>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = Lfm.c
    val motion = com.lfmpixel.lfm.ui.theme.LfmMotion
    Row(modifier.padding(vertical = 12.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { t ->
            val on = t == selected
            val shape = RoundedCornerShape(6.dp)
            val bg by androidx.compose.animation.animateColorAsState(if (on) c.text else Color.Transparent, motion.spec(motion.TINY), label = "tab")
            val fg by androidx.compose.animation.animateColorAsState(if (on) c.bg else c.text2, motion.spec(motion.TINY), label = "tabText")
            Box(Modifier.heightIn(min = 36.dp).clip(shape).background(bg).border(1.dp, if (on) c.text else c.control, shape)
                .selectable(on, role = Role.Tab) { onSelect(t) }.padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center) {
                Txt(t, LfmType.small.copy(fontSize = LfmType.small.fontSize * 0.965f, fontWeight = if (on) LfmType.button.fontWeight else null), fg)
            }
        }
    }
}

@Composable
fun Segmented(items: List<String>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = Lfm.c
    val motion = com.lfmpixel.lfm.ui.theme.LfmMotion
    val shape = RoundedCornerShape(6.dp)
    Row(modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min).clip(shape).border(1.dp, c.control, shape).selectableGroup()) {
        items.forEachIndexed { i, t ->
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(c.control))
            val on = t == selected
            val bg by androidx.compose.animation.animateColorAsState(if (on) c.text else Color.Transparent, motion.spec(motion.TINY), label = "seg")
            val fg by androidx.compose.animation.animateColorAsState(if (on) c.bg else c.text2, motion.spec(motion.TINY), label = "segText")
            Box(Modifier.weight(1f).heightIn(min = 36.dp).background(bg).selectable(on, role = Role.RadioButton) { onSelect(t) }
                .padding(horizontal = 4.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center) {
                Txt(t, LfmType.small.copy(fontSize = LfmType.meta.fontSize * 1.08f, fontWeight = if (on) LfmType.button.fontWeight else null), fg,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

/** A square-ish switch: ink when on. The thumb slides in 150 ms; the whole 48 dp square around it takes the tap. */
@Composable
fun Switch(on: Boolean, label: String = "", onChange: (Boolean) -> Unit) {
    val c = Lfm.c
    val motion = com.lfmpixel.lfm.ui.theme.LfmMotion
    val shape = RoundedCornerShape(6.dp)
    val x by androidx.compose.animation.core.animateDpAsState(if (on) 21.dp else 3.dp, motion.spec(motion.SHORT, motion.standard), label = "thumb")
    val track by androidx.compose.animation.animateColorAsState(if (on) c.text else Color.Transparent, motion.spec(motion.TINY), label = "track")
    val thumb by androidx.compose.animation.animateColorAsState(if (on) c.bg else c.text3, motion.spec(motion.TINY), label = "thumbColor")
    val edge by androidx.compose.animation.animateColorAsState(if (on) c.text else c.control, motion.spec(motion.TINY), label = "edge")
    // [label] names the setting for TalkBack, which then says "Show speed, switch, on".
    Box(Modifier.size(48.dp).toggleable(on, role = Role.Switch, onValueChange = onChange)
        .let { if (label.isNotEmpty()) it.semantics { contentDescription = label } else it }, contentAlignment = Alignment.Center) {
        Box(Modifier.width(40.dp).height(22.dp).clip(shape).background(track).border(1.dp, edge, shape)) {
            Box(Modifier.padding(start = x, top = 3.dp).size(14.dp).clip(RoundedCornerShape(3.dp)).background(thumb))
        }
    }
}

@Composable
fun CheckBox(checked: Boolean, onChange: ((Boolean) -> Unit)?) {
    val c = Lfm.c
    val shape = RoundedCornerShape(4.dp)
    // With no onChange the row around it takes the tap (and says the state); the box only draws.
    Box(Modifier.size(48.dp).let { m -> onChange?.let { m.toggleable(checked, role = Role.Checkbox, onValueChange = it) } ?: m },
        contentAlignment = Alignment.Center) {
        val motion = com.lfmpixel.lfm.ui.theme.LfmMotion
        val fill by androidx.compose.animation.animateColorAsState(if (checked) c.text else Color.Transparent, motion.spec(motion.TINY), label = "box")
        val tick by androidx.compose.animation.core.animateFloatAsState(if (checked) 1f else 0f,
            motion.spec(if (checked) motion.SHORT else motion.TINY, motion.emphasizedDecelerate), label = "tick")
        Box(Modifier.size(18.dp).clip(shape).background(fill).border(1.dp, if (checked) c.text else c.control, shape),
            contentAlignment = Alignment.Center) {
            if (tick > 0f) LIcon("check", size = 13.dp, tint = c.bg, stroke = 2.5f,
                modifier = Modifier.graphicsLayer { alpha = tick; scaleX = 0.6f + 0.4f * tick; scaleY = 0.6f + 0.4f * tick })
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
             onStop: (() -> Unit)? = null, think: Boolean? = null, onThink: (Boolean) -> Unit = {}, onThinkInfo: () -> Unit = {}) {
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
        if (think != null) ThinkChip(think, onThink, onThinkInfo)
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

/**
 * Think, by the message box: whether this chat thinks before it answers. On, the word is marked with the
 * highlighter; off, it is a quiet grey. A tap flips it; a long-press says what it does.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ThinkChip(on: Boolean, onChange: (Boolean) -> Unit, onInfo: () -> Unit) {
    val c = Lfm.c
    val shape = RoundedCornerShape(6.dp)
    Row(Modifier.heightIn(min = 34.dp).clip(shape).border(1.dp, if (on) c.text else c.control, shape)
        .combinedClickable(role = Role.Switch, onClickLabel = if (on) "Answer without thinking" else "Think before answering",
            onLongClickLabel = "What Think does", onLongClick = onInfo) { onChange(!on) }
        .semantics { stateDescription = if (on) "On" else "Off" }
        .padding(start = 6.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        LIcon("bulb", size = 16.dp, tint = if (on) c.text else c.text3)
        // The mic stays the only yellow control: on, only the word is marked.
        Txt("Think", LfmType.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), if (on) c.onHighlight else c.text2,
            Modifier.let { if (on) it.background(c.highlight, RoundedCornerShape(2.dp)) else it }.padding(horizontal = 3.dp), maxLines = 1)
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
