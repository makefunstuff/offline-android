package com.lfmpixel.lfm.ui.components

import androidx.compose.foundation.BorderStroke
import com.lfmpixel.lfm.ui.theme.LfmShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
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
import androidx.compose.foundation.layout.widthIn
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

/** Highlights already drawn in during this run: they show at once when they come back into view. */
private val drawnIn = java.util.Collections.synchronizedSet(HashSet<String>())

/**
 * The highlighter as a marker stroke under the words, from just above the baseline: it draws in from the left
 * (300 ms, emphasized decelerate) the first time a highlight shows, and is simply there after that.
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
    // The stroke sits behind the words, from a little above the baseline to just under it, so the words keep the
    // page's ink in both themes.
    Text(text, style = style, color = c.text, maxLines = maxLines, overflow = TextOverflow.Ellipsis, onTextLayout = { layout = it },
        modifier = modifier.padding(horizontal = 3.dp).drawWithContent {
            val l = layout ?: return@drawWithContent drawContent()
            val p = progress.value
            if (p <= 0f) return@drawWithContent drawContent()
            val strokes = (0 until l.lineCount).map { i ->
                val left = l.getLineLeft(i) - pad
                val right = l.getLineRight(i) + pad
                val base = l.getLineBaseline(i)
                val h = (l.getLineBottom(i) - l.getLineTop(i))
                // In dark mode the stroke sits just under the letters (only descenders cross it), so light words stay off the yellow.
                val top = base + h * if (c.dark) 0.03f else -0.1f; val bottom = base + h * if (c.dark) 0.17f else 0.16f
                androidx.compose.ui.geometry.Rect(left, top, left + (right - left) * p, bottom)
            }
            strokes.forEach { drawRect(c.highlight, it.topLeft, it.size) }
            drawContent()
            // The descenders that do cross it are inked again in the highlight's own ink, so they read on the yellow.
            if (c.dark) strokes.forEach { r ->
                clipRect(r.left, r.top, r.right, r.bottom) {
                    drawIntoCanvas { l.multiParagraph.paint(it, color = c.onHighlight) }
                }
            }
        })
}

@Composable
fun Txt(text: String, style: TextStyle = LfmType.body, color: Color = Lfm.c.text, modifier: Modifier = Modifier,
        maxLines: Int = Int.MAX_VALUE, decoration: TextDecoration? = null, textAlign: androidx.compose.ui.text.style.TextAlign? = null) {
    Text(text, style = style, color = color, modifier = modifier, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        textDecoration = decoration, textAlign = textAlign)
}

/** Text with spans or pictures inline (a citation's number in an answer), in the theme's type. */
@Composable
fun RichTxt(text: AnnotatedString, style: TextStyle, color: Color = Lfm.c.text, modifier: Modifier = Modifier,
            inlineContent: Map<String, androidx.compose.foundation.text.InlineTextContent> = emptyMap(), softWrap: Boolean = true) {
    Text(text, style = style, color = color, modifier = modifier, inlineContent = inlineContent, softWrap = softWrap)
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier) = Txt(text, LfmType.label, Lfm.c.text2, modifier)

@Composable
fun SectionLabel(text: String) = Label(text, Modifier.padding(top = 20.dp, bottom = 6.dp))

/** How loud the mic hears you now, 0..1, for the mic button's pulse. Provided at the root from the recorder. */
val LocalMicLevel = androidx.compose.runtime.compositionLocalOf<androidx.compose.runtime.State<Float>> { androidx.compose.runtime.mutableFloatStateOf(0f) }

/**
 * The mic: round. The [primary] one (Talk's own, and the bottom bar's talk button) is the highlighter's pastel; the
 * others (dictation into a line or a list) are a quiet fill, so one screen never has two yellow buttons, or solid
 * navy when [floating] (in Notebook's pill). Listening,
 * it turns navy with a Stop square, and a soft ring breathes out with your voice.
 */
@Composable
fun MicButton(onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 42.dp, listening: Boolean = false, primary: Boolean = false,
              floating: Boolean = false) {
    val c = Lfm.c
    val shape = LfmShape.round
    val motion = com.lfmpixel.lfm.ui.theme.LfmMotion
    val fill by androidx.compose.animation.animateColorAsState(if (listening) (if (floating) c.mic else c.text) else if (primary) c.mic else if (floating) c.text else c.text.copy(alpha = 0.08f),
        motion.spec(if (listening) motion.TINY else motion.MEDIUM, if (listening) motion.standard else motion.emphasizedDecelerate), label = "mic")
    val voice = LocalMicLevel.current
    val pulse by androidx.compose.animation.core.animateFloatAsState(if (listening) voice.value.coerceIn(0f, 1f) else 0f, motion.level(), label = "pulse")
    val src = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        // The pulse: an outline that grows up to 8 dp out and fades as it goes, only while listening.
        if (listening) Box(Modifier.matchParentSize().graphicsLayer {
            val grow = 1f + 0.28f * pulse
            scaleX = grow; scaleY = grow; alpha = 0.25f + 0.55f * pulse
        }.border(2.dp, if (floating) c.mic else c.action, shape))
        Box(
            Modifier
                .matchParentSize()
                .pressScale(src)
                .clip(shape)
                .background(fill)
                .clickable(src, androidx.compose.material3.ripple(color = if (floating) c.bg else if (listening) c.bg else if (primary) c.onMic else c.text), role = Role.Button, onClick = onClick)
                .semantics { contentDescription = if (listening) "Stop listening" else "Speak" },
            contentAlignment = Alignment.Center,
        ) {
            LIcon(if (listening) "stop" else "mic", tint = if (listening && floating) c.onMic else if (listening || floating) c.bg else if (primary) c.onMic else c.text)
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
    Box(modifier.size(48.dp).clip(LfmShape.control).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = desc },
        contentAlignment = Alignment.Center) { LIcon(icon, tint = tint) }
}

enum class BtnKind { Primary, Secondary }

/**
 * A button as a word, with no box or edge (docs/ux-spec.md, "Controls"): the primary one is bold in the action blue
 * with the sun's dot before it (its icon, if it has one, takes the dot's place); the others are quiet blue words.
 * The 44 dp touch target is still there around the word.
 */
@Composable
fun Btn(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, kind: BtnKind = BtnKind.Secondary, icon: String? = null,
        small: Boolean = false, enabled: Boolean = true) {
    val c = Lfm.c
    val primary = kind == BtnKind.Primary
    val fg = if (primary) c.action else c.text2
    val src = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        modifier
            .heightIn(min = 44.dp)
            .widthIn(min = 44.dp)
            .pressScale(src)
            .clip(LfmShape.control)
            .clickable(src, androidx.compose.material3.ripple(color = c.action), enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (small) 6.dp else 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (enabled) fg else c.text3
        when {
            icon != null -> LIcon(icon, size = 16.dp, tint = tint)
            primary && enabled -> SunDot()
        }
        Txt(text, if (primary) (if (small) LfmType.actionSmall else LfmType.action)
            else if (small) LfmType.buttonSmall else LfmType.button, tint, maxLines = 2)
    }
}

/** The sun's dot: the highlighter yellow, round, with a soft glow. It marks the one thing to tap. */
@Composable
fun SunDot(size: Dp = 11.dp) {
    val c = Lfm.c
    Box(Modifier.size(size + 8.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size + 8.dp).background(c.highlight.copy(alpha = 0.18f), LfmShape.round))
        Box(Modifier.size(size).background(c.highlight, LfmShape.round))
    }
}

/**
 * A screen's header on the hour's sky ([SkyBand]): the title in the condensed bold and the [SunMark] at its end.
 * [onSky] false leaves the sky out, for a screen that is all sky already (Talk); no line under it either way.
 */
@Composable
fun Header(title: String, onBack: (() -> Unit)?, leftIcon: String = "back", onSky: Boolean = true, backDesc: String = "Back",
           right: @Composable RowScope.() -> Unit = {}) {
    val bar: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButtonPlain(leftIcon, onBack, desc = backDesc) else Spacer(Modifier.width(10.dp))
            Txt(title, LfmType.header, modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() })
            right()
            if (onSky) SunMark(Modifier.padding(start = 4.dp, end = 10.dp))
        }
    }
    if (onSky) SkyBand { bar() } else bar()
}

@Composable
fun Divider(modifier: Modifier = Modifier) = Box(modifier.fillMaxWidth().height(1.dp).background(Lfm.c.line))

@Composable
fun Pill(text: String, icon: String? = null, modifier: Modifier = Modifier) {
    val c = Lfm.c
    // No edge: a soft pill of the wash, the words in the quiet blue.
    Row(modifier.background(c.wash, LfmShape.pill).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        icon?.let { LIcon(it, size = 16.dp, tint = c.text2) }
        Txt(text, LfmType.meta, c.text2, maxLines = 1)
    }
}

@Composable
fun Tag(text: String, icon: String? = null) {
    val c = Lfm.c
    Row(Modifier.padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        icon?.let { LIcon(it, size = 14.dp, tint = c.text2) }
        Txt(text, LfmType.tag, c.text2, maxLines = 1)
    }
}

/**
 * Tabs as words, no boxes: the picked one is navy and bold with the highlighter's stroke drawn in under it (150 ms);
 * the rest are quiet.
 */
@Composable
fun Tabs(items: List<String>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.padding(vertical = 6.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEach { t -> WordTab(t, t == selected, Role.Tab, Modifier) { onSelect(t) } }
    }
}

/** One word of [Tabs] or [Segmented], with the stroke under it when picked. */
@Composable
private fun WordTab(t: String, on: Boolean, role: Role, modifier: Modifier, onClick: () -> Unit) {
    val c = Lfm.c
    val motion = com.lfmpixel.lfm.ui.theme.LfmMotion
    val fg by androidx.compose.animation.animateColorAsState(if (on) c.text else c.text3, motion.spec(motion.TINY), label = "tabText")
    val stroke by androidx.compose.animation.core.animateFloatAsState(if (on) 1f else 0f, motion.spec(motion.SHORT, motion.emphasizedDecelerate), label = "tabStroke")
    Box(modifier.heightIn(min = 44.dp).clip(LfmShape.control).selectable(on, role = role, onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center) {
        Column(Modifier.width(androidx.compose.foundation.layout.IntrinsicSize.Max), horizontalAlignment = Alignment.CenterHorizontally) {
            Txt(t, LfmType.tab.copy(fontWeight = if (on) androidx.compose.ui.text.font.FontWeight.Bold else null), fg, maxLines = 2,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Box(Modifier.padding(top = 3.dp).height(3.dp).fillMaxWidth(stroke).background(c.highlight, LfmShape.mark))
        }
    }
}

/** A choice of a few, as words spread across the width: the picked one bold with the stroke under it. */
@Composable
fun Segmented(items: List<String>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().selectableGroup()) {
        items.forEach { t -> WordTab(t, t == selected, Role.RadioButton, Modifier.weight(1f)) { onSelect(t) } }
    }
}

/** A round switch with no edge: the action blue when on. The thumb slides in 150 ms; the whole 48 dp square takes the tap. */
@Composable
fun Switch(on: Boolean, label: String = "", onChange: (Boolean) -> Unit) {
    val c = Lfm.c
    val motion = com.lfmpixel.lfm.ui.theme.LfmMotion
    val shape = LfmShape.round
    val x by androidx.compose.animation.core.animateDpAsState(if (on) 21.dp else 3.dp, motion.spec(motion.SHORT, motion.standard), label = "thumb")
    val track by androidx.compose.animation.animateColorAsState(if (on) c.action else c.control, motion.spec(motion.TINY), label = "track")
    val thumb = c.bg
    // [label] names the setting for TalkBack, which then says "Show speed, switch, on".
    Box(Modifier.size(48.dp).toggleable(on, role = Role.Switch, onValueChange = onChange)
        .let { if (label.isNotEmpty()) it.semantics { contentDescription = label } else it }, contentAlignment = Alignment.Center) {
        Box(Modifier.width(40.dp).height(22.dp).clip(shape).background(track)) {
            Box(Modifier.padding(start = x, top = 3.dp).size(16.dp).clip(LfmShape.round).background(thumb))
        }
    }
}

@Composable
fun CheckBox(checked: Boolean, onChange: ((Boolean) -> Unit)?) {
    val c = Lfm.c
    val shape = LfmShape.round
    // A ring; done, it fills with the sun and pops a little: a small win, seen. With no onChange the row around it
    // takes the tap (and says the state); the ring only draws.
    val view = androidx.compose.ui.platform.LocalView.current
    // A small win, felt: a confirm tick when it is ticked (through the view, so it follows the phone's touch feedback).
    Box(Modifier.size(48.dp).let { m -> onChange?.let { m.toggleable(checked, role = Role.Checkbox, onValueChange = { on ->
        if (on) view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
        it(on)
    }) } ?: m },
        contentAlignment = Alignment.Center) {
        val motion = com.lfmpixel.lfm.ui.theme.LfmMotion
        val fill by androidx.compose.animation.animateColorAsState(if (checked) c.highlight else Color.Transparent, motion.spec(motion.TINY), label = "box")
        val tick by androidx.compose.animation.core.animateFloatAsState(if (checked) 1f else 0f,
            motion.spec(if (checked) motion.SHORT else motion.TINY, motion.emphasizedDecelerate), label = "tick")
        val pop by androidx.compose.animation.core.animateFloatAsState(if (checked) 1f else 0f,
            if (com.lfmpixel.lfm.ui.theme.LfmMotion.reduced()) androidx.compose.animation.core.snap()
            else androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 600f), label = "pop")
        Box(Modifier.size(22.dp).graphicsLayer { val s = 0.85f + 0.15f * pop; scaleX = s; scaleY = s }.clip(shape).background(fill)
            .let { if (checked) it else it.border(1.5.dp, c.control, shape) },
            contentAlignment = Alignment.Center) {
            if (tick > 0f) LIcon("check", size = 13.dp, tint = c.onHighlight, stroke = 2.5f,
                modifier = Modifier.graphicsLayer { alpha = tick; scaleX = 0.6f + 0.4f * tick; scaleY = 0.6f + 0.4f * tick })
        }
    }
}

/** A group with no box by default: space does the grouping. Pass [borderColor] or [fill] for the rare one that needs an edge. */
@Composable
fun Card(modifier: Modifier = Modifier, borderColor: Color = Color.Transparent, fill: Color = Color.Transparent, padding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
         onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = LfmShape.card
    Box(modifier.clip(shape).background(fill).let { if (borderColor.alpha > 0f) it.border(BorderStroke(1.dp, borderColor), shape) else it }
        .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }.padding(padding)) { content() }
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
    val shape = LfmShape.card
    // A soft surface of the wash, no edge: the one box left on most screens.
    Row(modifier.fillMaxWidth().heightIn(min = 52.dp).clip(shape).background(c.wash)
        .padding(start = 16.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
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
            Box(Modifier.size(42.dp).clip(LfmShape.round).background(c.text).clickable(role = Role.Button, onClick = onStop)
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
    val shape = LfmShape.control
    // Drawn 34 dp tall; the touch target is the full 48 dp.
    Row(Modifier.minimumInteractiveComponentSize().heightIn(min = 34.dp).clip(shape)
        .combinedClickable(role = Role.Switch, onClickLabel = if (on) "Answer without thinking" else "Think before answering",
            onLongClickLabel = "What Think does", onLongClick = onInfo) { onChange(!on) }
        .semantics { toggleableState = androidx.compose.ui.state.ToggleableState(on) }
        .padding(start = 6.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        LIcon("bulb", size = 16.dp, tint = if (on) c.text else c.text3)
        // No edge: on, the word is marked with the highlighter; off, it is a quiet blue.
        Txt("Think", LfmType.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), if (on) c.onHighlight else c.text2,
            Modifier.let { if (on) it.background(c.highlight, LfmShape.mark) else it }.padding(horizontal = 3.dp), maxLines = 1)
    }
}

/** One navy pill with + (write something new) and the mic (say it), each a 56 dp target. */
@Composable
fun NewPill(onNew: () -> Unit, newDesc: String, onMic: () -> Unit, listening: Boolean, modifier: Modifier = Modifier) {
    val c = Lfm.c
    // Not clipped, so the mic's pulse can breathe out past the pill.
    Row(modifier.height(56.dp).background(c.text, LfmShape.pill), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp).clip(LfmShape.round).clickable(role = Role.Button, onClick = onNew).semantics { contentDescription = newDesc },
            contentAlignment = Alignment.Center) { LIcon("plus", tint = c.bg) }
        // The same mic as everywhere else (its pulse follows the voice), in the pill's navy.
        MicButton(onMic, size = 56.dp, listening = listening, floating = true)
    }
}

/** A search or quick-add line: always visible, keyboard up when [focus] is set. Enter calls [onDone]. */
@Composable
fun InputLine(value: String, onChange: (String) -> Unit, placeholder: String, icon: String, modifier: Modifier = Modifier,
              focus: androidx.compose.ui.focus.FocusRequester? = null, imeAction: ImeAction = ImeAction.Search, onDone: () -> Unit = {},
              trailing: @Composable () -> Unit = {}) {
    val c = Lfm.c
    val shape = LfmShape.pill
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape).background(c.wash).padding(start = 16.dp, end = 4.dp),
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
        val shape = LfmShape.card
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().heightIn(min = 48.dp).clip(shape).background(c.text)
            .padding(start = 16.dp, end = 4.dp).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically) {
            Txt(s.text, LfmType.body, c.bg, Modifier.weight(1f).padding(vertical = 12.dp), maxLines = 2)
            s.undo?.let { u ->
                Box(Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) {
                    onDone()
                    if (!s.undone) { s.undone = true; scope.launch { runCatching { u() } } }
                }.padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center) { Txt("Undo", LfmType.action, if (c.dark) c.onMic else c.mic) }
            }
        }
    }
}
