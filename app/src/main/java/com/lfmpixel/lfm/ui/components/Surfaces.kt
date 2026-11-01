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

/** The title of one of the bottom bar's places (Notebook, Plans), large, on the hour's sky. */
@Composable
fun PlaceTitle(title: String, modifier: Modifier = Modifier, below: @Composable ColumnScope.() -> Unit = {}) {
    SkyBand(modifier) {
        Txt(title, LfmType.title2, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp).semantics { heading() })
        below()
    }
}

/** One of Inkling's own cards on the page (Now, today's plan, Where was I): the pale of the horizon, ink on it. */
@Composable
fun WashCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth().background(Lfm.c.wash, LfmShape.card).padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)) { content() }
}

/** A flat bottom sheet: 12 dp top corners, a grab bar, a scrim, no shadow. */
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
        dragHandle = { Box(Modifier.padding(top = 12.dp, bottom = 14.dp).width(36.dp).height(4.dp).background(c.text3, LfmShape.mark)) },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 18.dp).navigationBarsPadding(),
            content = content)
    }
}

/** A long-press or ⋯ menu: paper, a 1 dp line, the page's corners, no shadow. */
@Composable
fun Menu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = Lfm.c
    DropdownMenu(expanded, onDismiss, shape = LfmShape.card, containerColor = c.bg, tonalElevation = 0.dp, shadowElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, c.control), content = content)
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
