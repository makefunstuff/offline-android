package com.lfmpixel.lfm.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import com.lfmpixel.lfm.daylight.OnSky
import com.lfmpixel.lfm.daylight.rememberNow
import com.lfmpixel.lfm.daylight.skyAt
import com.lfmpixel.lfm.ui.theme.Lfm
import com.lfmpixel.lfm.ui.theme.LfmShape
import com.lfmpixel.lfm.ui.theme.LfmType
import java.time.LocalDate
import java.time.ZoneOffset

/*
 * Daylight's shared surfaces (docs/ux-spec.md): the sky band every screen's header sits on, Inkling's wash card, the
 * sheet, the menu and the day picker. Screens use these instead of Material's stock ones, so nothing falls back to a
 * look of its own (DaylightGuardTest).
 */

/**
 * The hour's sky behind a screen's header: it carries on from the sky strip under the status bar (top) down to the
 * horizon, and what is drawn on it takes the sky's ink. Every screen but Today (whose whole top is the sky), Talk
 * (all sky) and the camera (black) has one. Not for use inside [OnSky] (Talk, [SkyPage], Today's sky): there
 * `Lfm.c.dark` is the sky's, not the theme's, and the band would dim wrongly.
 */
@Composable
fun SkyBand(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val now by rememberNow()
    val sky = skyAt(now)
    Column(modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(sky.top), Color(sky.horizon))))) {
        OnSky(sky) { Column(content = content) }
    }
}

/** A screen that is all sky, top to horizon (the app lock), drawn in the sky's ink. */
@Composable
fun SkyPage(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val now by rememberNow()
    val sky = skyAt(now)
    Box(modifier.background(Brush.verticalGradient(listOf(Color(sky.top), Color(sky.horizon))))) { OnSky(sky, content) }
}

/** The title of one of the bottom bar's places (Notebook, Plans), large, on the hour's sky, with the [SunMark]. */
@Composable
fun PlaceTitle(title: String, modifier: Modifier = Modifier, below: @Composable ColumnScope.() -> Unit = {}) {
    SkyBand(modifier) {
        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Txt(title, LfmType.title2, modifier = Modifier.weight(1f).padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 14.dp).semantics { heading() })
            SunMark(Modifier.padding(end = 16.dp))
        }
        below()
    }
}

/**
 * Horizon Almanac's signature, on every screen's header: the waking day as a small half dial (06:00 to 22:00) with the
 * sun on it at now, or the moon resting on the horizon at night. Moves only when the minute changes; TalkBack skips it
 * (the time is in the status bar).
 */
@Composable
fun SunMark(modifier: Modifier = Modifier) {
    val c = Lfm.c
    val now by rememberNow()
    val f = (now.hour * 60 + now.minute - 360) / 960f
    androidx.compose.foundation.Canvas(modifier.width(40.dp).height(22.dp)) {
        val r = size.width / 2 - 3.dp.toPx()
        val cx = size.width / 2
        val cy = size.height - 2.dp.toPx()
        drawArc(c.text.copy(alpha = 0.35f), 180f, 180f, false, topLeft = androidx.compose.ui.geometry.Offset(cx - r, cy - r),
            size = androidx.compose.ui.geometry.Size(r * 2, r * 2), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        drawLine(c.text.copy(alpha = 0.45f), androidx.compose.ui.geometry.Offset(0f, cy), androidx.compose.ui.geometry.Offset(size.width, cy), 1.dp.toPx())
        val day = f in 0f..1f
        val a = Math.PI * (1 - f.coerceIn(0f, 1f))
        val sx = cx + r * kotlin.math.cos(a).toFloat()
        val sy = cy - r * kotlin.math.sin(a).toFloat()
        if (day) {
            drawCircle(c.highlight.copy(alpha = 0.25f), 7.dp.toPx(), androidx.compose.ui.geometry.Offset(sx, sy))
            drawCircle(c.highlight, 4.dp.toPx(), androidx.compose.ui.geometry.Offset(sx, sy))
        } else {
            drawCircle(c.text.copy(alpha = 0.8f), 3.5.dp.toPx(), androidx.compose.ui.geometry.Offset(if (f < 0f) cx - r else cx + r, cy))
        }
    }
}

/**
 * A group that must stand out (a privacy warning, "Inkling is offline"): the wash with the highlighter's bar down its
 * start edge, instead of an outline.
 */
fun Modifier.callout(c: com.lfmpixel.lfm.ui.theme.LfmColors): Modifier = this.clip(LfmShape.card).background(c.wash).drawBehind {
    drawRect(c.highlight, size = androidx.compose.ui.geometry.Size(4.dp.toPx(), size.height))
}

/**
 * One of Inkling's own blocks on the page (Now, today's plan, Where was I): no box, no fill. Generous space and the
 * type's size do the grouping, so the page reads as a few lines, not a stack of cards.
 */
@Composable
fun WashCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth().padding(start = 8.dp, end = 0.dp, top = 8.dp, bottom = 8.dp)) { content() }
}

/** A flat bottom sheet: soft top corners, a round grab bar, a scrim, no shadow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlatSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = Lfm.c
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = LfmShape.sheet,
        containerColor = c.bg,
        contentColor = c.text,
        tonalElevation = 0.dp,
        scrimColor = c.scrim.copy(alpha = 0.45f),
        dragHandle = { Box(Modifier.padding(top = 12.dp, bottom = 14.dp).width(36.dp).height(4.dp).background(c.control, LfmShape.round)) },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 18.dp).navigationBarsPadding(),
            content = content)
    }
}

/** A long-press or ⋯ menu: the wash, soft corners, a faint shadow and no edge. */
@Composable
fun Menu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = Lfm.c
    DropdownMenu(expanded, onDismiss, shape = LfmShape.card, containerColor = c.wash, tonalElevation = 0.dp, shadowElevation = 3.dp, content = content)
}

/** One line of a [Menu], with its icon. */
@Composable
fun MenuItem(text: String, onClick: () -> Unit, icon: String? = null) {
    DropdownMenuItem({ Txt(text) }, onClick, leadingIcon = icon?.let { { LIcon(it, size = 18.dp, tint = Lfm.c.text2) } })
}

/** Picks a day: Material's calendar in Daylight's ink and highlighter, in a [FlatSheet], with Cancel and Set. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DaySheet(initial: LocalDate, onPick: (LocalDate) -> Unit, onClose: () -> Unit) {
    val c = Lfm.c
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    FlatSheet(onDismiss = onClose) {
        Txt("Pick a day", LfmType.answer.copy(fontWeight = LfmType.rowTitle.fontWeight), modifier = Modifier.semantics { heading() })
        DatePicker(state, title = null, showModeToggle = false, colors = DatePickerDefaults.colors(
            containerColor = c.bg, titleContentColor = c.text2, headlineContentColor = c.text, weekdayContentColor = c.text2,
            subheadContentColor = c.text2, navigationContentColor = c.text, yearContentColor = c.text, currentYearContentColor = c.text,
            selectedYearContentColor = c.onHighlight, selectedYearContainerColor = c.highlight, dayContentColor = c.text,
            selectedDayContentColor = c.onHighlight, selectedDayContainerColor = c.highlight, todayContentColor = c.text,
            todayDateBorderColor = c.text, dividerColor = c.line,
        ))
        androidx.compose.foundation.layout.Row(Modifier.padding(top = 8.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            Btn("Cancel", onClose, Modifier.weight(1f))
            Btn("Set", {
                state.selectedDateMillis?.let { onPick(java.time.Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                onClose()
            }, Modifier.weight(1f), BtnKind.Primary)
        }
    }
}
