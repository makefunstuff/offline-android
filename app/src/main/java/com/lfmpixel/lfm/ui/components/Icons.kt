package com.lfmpixel.lfm.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lfmpixel.lfm.ui.theme.Lfm

/** The plain line icons of the mock-ups (docs/mockups/final.mjs), drawn from the same paths. */
object LfmIcons {
    private fun c(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"
    private fun rr(x: Float, y: Float, w: Float, h: Float, r: Float) =
        "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 ${-r} ${r}h${-(w - 2 * r)}a$r $r 0 0 1 ${-r} ${-r}v${-(h - 2 * r)}a$r $r 0 0 1 $r ${-r}z"

    private val paths = mapOf(
        "search" to listOf(c(11f, 11f, 7f), "M20 20l-3.5-3.5"),
        "mic" to listOf(rr(9f, 3f, 6f, 11f, 3f), "M5 11a7 7 0 0 0 14 0M12 18v3"),
        "camera" to listOf("M4 8h3l2-3h6l2 3h3v11H4z", c(12f, 13f, 3.5f)),
        "flip" to listOf("M4 12a8 8 0 0 1 14-5l2 2M20 12a8 8 0 0 1-14 5l-2-2M20 4v5h-5M4 20v-5h5"),
        "doc" to listOf("M6 3h8l4 4v14H6z", "M14 3v4h4", "M9 12h6M9 16h6"),
        "video" to listOf(rr(3f, 6f, 13f, 12f, 2f), "M16 10l5-3v10l-5-3"),
        "plus" to listOf("M12 5v14M5 12h14"),
        "send" to listOf("M5 12h13M13 6l6 6-6 6"),
        "check" to listOf("M5 12.5l4.5 4.5L19 7.5"),
        "cal" to listOf(rr(4f, 5f, 16f, 15f, 2f), "M4 10h16M9 3v4M15 3v4"),
        "alarm" to listOf(c(12f, 13f, 7f), "M12 9.5V13l2.5 2M5 4L3 6M19 4l2 2"),
        "bell" to listOf("M6 16v-5a6 6 0 0 1 12 0v5l1.5 2h-15z", "M10 20a2 2 0 0 0 4 0"),
        "note" to listOf("M6 3h9l4 4v14H6z", "M14 3v5h5M9 12h7M9 16h5"),
        "bulb" to listOf("M9 18h6M10 21h4", "M12 3a6 6 0 0 0-3.5 10.9c.6.5 1 1.2 1 2.1h5c0-.9.4-1.6 1-2.1A6 6 0 0 0 12 3z"),
        "book" to listOf("M5 4h10a3 3 0 0 1 3 3v13H8a3 3 0 0 1-3-3z", "M5 17a3 3 0 0 1 3-3h10"),
        "todo" to listOf("M10 6h10M10 12h10M10 18h10", "M3.5 6l1.5 1.5L7.5 5M3.5 12l1.5 1.5L7.5 11", c(5f, 18f, 1.3f)),
        "sun" to listOf(c(12f, 12f, 4f), "M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"),
        "chat" to listOf("M4 5h16v11H9l-5 4z"),
        "sliders" to listOf("M4 7h10M18 7h2M4 17h4M12 17h8", c(16f, 7f, 2f), c(10f, 17f, 2f)),
        "back" to listOf("M15 5l-7 7 7 7"),
        "globe" to listOf(c(12f, 12f, 9f), "M3 12h18M12 3c3 3.5 3 14.5 0 18M12 3c-3 3.5-3 14.5 0 18"),
        "lock" to listOf(rr(5f, 11f, 14f, 10f, 2f), "M8 11V8a4 4 0 0 1 8 0v3"),
        "shield" to listOf("M12 3l7 3v6c0 4.5-3 7.5-7 9-4-1.5-7-4.5-7-9V6z"),
        "download" to listOf("M12 4v11M7 10l5 5 5-5M5 20h14"),
        "user" to listOf(c(12f, 8f, 4f), "M4 21c1.5-4 4.5-6 8-6s6.5 2 8 6"),
        "phone" to listOf("M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2z"),
        "stop" to listOf(rr(7f, 7f, 10f, 10f, 1.5f)),
        "speaker" to listOf("M4 9h4l5-4v14l-5-4H4z", "M17 9a4 4 0 0 1 0 6"),
        "x" to listOf("M6 6l12 12M18 6L6 18"),
        "edit" to listOf("M4 20h4L19 9l-4-4L4 16z"),
        "image" to listOf(rr(3f, 5f, 18f, 14f, 2f), c(9f, 10f, 1.5f), "M21 16l-5-5-8 8"),
        "flash" to listOf("M13 3L5 14h6l-1 7 8-11h-6z"),
        "chip" to listOf(rr(6f, 6f, 12f, 12f, 2f), "M9 2v4M15 2v4M9 18v4M15 18v4M2 9h4M2 15h4M18 9h4M18 15h4"),
        "wave" to listOf("M3 12h2M7 8v8M11 5v14M15 9v6M19 11v2"),
        "warn" to listOf("M12 4l9 16H3z", "M12 10v4M12 17h.01"),
        "right" to listOf("M9 6l6 6-6 6"),
        "folder" to listOf("M3 6h6l2 2h10v11H3z"),
        "trash" to listOf("M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13"),
        "list" to listOf("M4 6h16M4 12h16M4 18h10"),
        "history" to listOf("M4 12a8 8 0 1 0 2.5-5.8M4 4v4h4", "M12 8v4l3 2"),
        "more" to listOf("M12 5h.01M12 12h.01M12 19h.01"),
        "pin" to listOf("M9 3h6l-1 7 3 3H7l3-3z", "M12 13v8"),
        "up" to listOf("M12 19V5M6 11l6-6 6 6"),
        "down" to listOf("M12 5v14M6 13l6 6 6-6"),
        "copy" to listOf(rr(8f, 8f, 12f, 12f, 2f), "M16 8V5a1 1 0 0 0-1-1H5a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h3"),
        "undo" to listOf("M9 14L4 9l5-5", "M4 9h10a6 6 0 0 1 0 12h-3"),
    )

    private val cache = HashMap<String, ImageVector>()

    fun get(name: String, strokeWidth: Float = 1.75f): ImageVector = cache.getOrPut("$name/$strokeWidth") {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        val w = if (name == "more") 3f else strokeWidth
        paths[name]?.forEach { d ->
            b.addPath(PathParser().parsePathString(d).toNodes(), stroke = SolidColor(Color.Black), strokeLineWidth = w,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
        b.build()
    }
}

@Composable
fun LIcon(name: String, modifier: Modifier = Modifier, size: Dp = 22.dp, tint: Color = Lfm.c.text, stroke: Float = 1.75f) {
    Icon(LfmIcons.get(name, stroke), contentDescription = null, modifier = modifier.size(size), tint = tint)
}
