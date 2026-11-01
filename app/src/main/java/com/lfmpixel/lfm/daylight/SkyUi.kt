package com.lfmpixel.lfm.daylight

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.lfmpixel.lfm.ui.theme.Lfm
import com.lfmpixel.lfm.ui.theme.LocalLfm
import kotlinx.coroutines.delay
import java.time.LocalDateTime

/**
 * The time, read again at each new minute and no more often: the sky and the sun move by the minute, so nothing in
 * Daylight wakes the screen in between.
 */
@Composable
fun rememberNow(): State<LocalDateTime> {
    val offset by DayClock.offset.collectAsState()
    return produceState(DayClock.now(), offset) {
        while (true) {
            value = DayClock.now()
            delay(60_000L - DayClock.millis() % 60_000L + 50)
        }
    }
}

/** The sky now, dimmed in the phone's dark theme. */
@Composable
fun skyAt(now: LocalDateTime): Sky = Skies.at(now, dim = Lfm.c.dark)

/**
 * What is drawn on the sky reads in the sky's ink: the app's text, lines and controls are recoloured from it, so the
 * shared components (header, buttons, pills) work on any hour's sky.
 */
@Composable
fun OnSky(sky: Sky, content: @Composable () -> Unit) {
    val c = Lfm.c
    val ink = Color(sky.ink)
    val mid = Color(mix(sky.top, sky.horizon, 0.5f))
    CompositionLocalProvider(LocalLfm provides c.copy(
        text = ink, text2 = Color(sky.ink2), text3 = Color(sky.ink2),
        line = ink.copy(alpha = 0.22f), fill = ink.copy(alpha = 0.08f), control = ink.copy(alpha = 0.55f),
        bg = mid, dark = sky.lightInk,
    ), content = content)
}

/** The sky with today's horizon, its plans and the sun. */
@Composable
fun SkyCanvas(sky: Sky, layout: HorizonLayout, modifier: Modifier = Modifier, selected: String? = null, horizonAt: Float = SkyPainter.HORIZON,
              arcHeight: androidx.compose.ui.unit.Dp? = null) {
    Canvas(modifier) {
        drawIntoCanvas { SkyPainter.draw(it.nativeCanvas, size.width, size.height, density, sky, layout, selected, horizonAt = horizonAt,
            arcHeight = arcHeight?.toPx()) }
    }
}
