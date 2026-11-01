package com.lfmpixel.lfm.ui.screens

import androidx.compose.animation.AnimatedContent
import com.lfmpixel.lfm.ui.theme.LfmShape
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.lfmpixel.lfm.AppGraph
import com.lfmpixel.lfm.daylight.Cue
import com.lfmpixel.lfm.daylight.DaySentence
import com.lfmpixel.lfm.daylight.HorizonLayout
import com.lfmpixel.lfm.daylight.OnSky
import com.lfmpixel.lfm.daylight.Plan
import com.lfmpixel.lfm.daylight.Sky
import com.lfmpixel.lfm.daylight.DialCanvas
import com.lfmpixel.lfm.ui.components.IconButtonPlain
import com.lfmpixel.lfm.ui.components.LIcon
import com.lfmpixel.lfm.ui.components.Txt
import com.lfmpixel.lfm.ui.theme.Lfm
import com.lfmpixel.lfm.ui.theme.LfmMotion
import com.lfmpixel.lfm.ui.theme.LfmType
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Today's timed things as plans on the horizon, each keyed so a tap finds its row again. */
internal fun horizonPlans(timeline: List<AgendaItem>, zone: ZoneId): List<Pair<Plan, AgendaItem>> = timeline.mapIndexedNotNull { i, it ->
    val at = it.at ?: return@mapIndexedNotNull null
    val t = Instant.ofEpochMilli(at).atZone(zone)
    Plan(t.hour * 60 + t.minute, it.title, it.detail, key = "$i:$at") to it
}

/**
 * Horizon Almanac's home (docs/ux-spec.md, "Today"): the sky at this hour with the day as a half dial, the time in big
 * figures inside it, the day in one serif sentence under the horizon, and the next plan with how long until it. Plans
 * are marks on the dial; a tap on one picks it and its line shows, with Open when it can be opened. When Today first
 * shows, the sun rises along the dial to now (900 ms, once a run; still with Remove animations).
 */
@Composable
internal fun SkyHead(
    g: AppGraph, now: LocalDateTime, sky: Sky, timeline: List<AgendaItem>, openTodos: Int,
    onDayBefore: () -> Unit, onDump: () -> Unit, onSettings: () -> Unit, onLook: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val pairs = horizonPlans(timeline, zone)
    val nowMin = now.hour * 60 + now.minute
    val layout = HorizonLayout(nowMin, pairs.map { it.first })
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val pickedPair = pairs.firstOrNull { it.first.key == picked }
    val view = LocalView.current
    val sentence = DaySentence.of(now, pairs.map { it.first }, openTodos)
    val reduced = LfmMotion.reduced()
    val rise = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(if (reduced || risen) 1f else 0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (rise.value < 1f) rise.animateTo(1f, androidx.compose.animation.core.tween(900, easing = LfmMotion.emphasizedDecelerate))
        risen = true
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // The dial stands on the horizon; its top clears the header row, so a plan's target never sits on an icon.
        val headerH = 52.dp
        val radius = minOf(maxWidth / 2 - 34.dp, 150.dp)
        val hy = headerH + 22.dp + radius
        DialCanvas(sky, layout, hy, radius, Modifier.matchParentSize(), selected = picked, sunAt = rise.value)
        OnSky(sky) {
            val c = Lfm.c
            // The time, inside the dial, its foot on the horizon at any font size.
            Box(Modifier.fillMaxWidth().height(hy - 2.dp), contentAlignment = Alignment.BottomCenter) {
                Txt(now.format(DateTimeFormatter.ofPattern("HH:mm")), LfmType.clock, c.text, Modifier.semantics {
                    contentDescription = "It's " + now.format(DateTimeFormatter.ofPattern("HH:mm"))
                })
            }
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().height(headerH).padding(start = 24.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt("Today · " + now.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)), LfmType.label, c.text2,
                        Modifier.weight(1f).semantics { heading() }, maxLines = 1)
                    IconButtonPlain("back", onDayBefore, tint = c.text2, desc = "The day before")
                    IconButtonPlain("camera", onLook, tint = c.text2, desc = "Look")
                    IconButtonPlain("cloud", onDump, tint = c.text2, desc = "Brain dump")
                    IconButtonPlain("user", onSettings, tint = c.text2, desc = "Settings")
                }
                Spacer(Modifier.height(hy - headerH + 14.dp))
                // Under the horizon: Wren's sentence, then the picked plan, else the next one. The sky grows to fit.
                Column(Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Txt(sentence, LfmType.voice, c.text2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    val shown = pickedPair ?: layout.next?.let { n -> pairs.firstOrNull { it.first == n } }
                    PlanLine(shown, layout, isPicked = pickedPair != null)
                }
            }
            // Plans on the dial: a 44 dp target each, read out with its time.
            val density = androidx.compose.ui.platform.LocalDensity.current
            val geo = with(density) { com.lfmpixel.lfm.daylight.SkyPainter.DialGeometry(maxWidth.toPx() / 2, hy.toPx(), radius.toPx()) }
            pairs.forEach { (plan, item) ->
                val (px, py) = geo.at(layout.x(plan.minute))
                Box(Modifier.offset(with(density) { px.toDp() } - 22.dp, with(density) { py.toDp() } - 22.dp).size(44.dp).clip(LfmShape.round)
                    .clickable(role = Role.Button, onClickLabel = "Show") {
                        picked = if (picked == plan.key) null else plan.key
                        g.feel.cue(Cue.Tick, view)
                    }
                    .semantics {
                        contentDescription = DaySentence.clock(plan.minute) + " " + item.title
                        if (picked == plan.key) stateDescription = "Shown"
                    })
            }
        }
    }
}

/** Today's sun rises to now once a run, not each time the page comes back into view. */
private var risen = false

@Composable
private fun PlanLine(shown: Pair<Plan, AgendaItem>?, layout: HorizonLayout, isPicked: Boolean) {
    val c = Lfm.c
    val reduced = LfmMotion.reduced()
    AnimatedContent(shown?.first?.key, transitionSpec = {
        if (reduced) fadeIn(androidx.compose.animation.core.snap()) togetherWith fadeOut(androidx.compose.animation.core.snap())
        else fadeIn(androidx.compose.animation.core.tween(LfmMotion.SHORT, easing = LfmMotion.standard)) togetherWith
            fadeOut(androidx.compose.animation.core.tween(LfmMotion.TINY, easing = LfmMotion.standard))
    }, label = "plan") { key ->
        val pair = (if (key == shown?.first?.key) shown else null) ?: return@AnimatedContent
        val (plan, item) = pair
        val until = layout.minutesTo(plan)
        val whenText = when {
            layout.isPast(plan) -> "earlier"
            until <= 0 -> "now"
            else -> "in " + DaySentence.span(until)
        }
        Row(Modifier.padding(top = 8.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f, fill = false), horizontalAlignment = Alignment.CenterHorizontally) {
                Txt((if (isPicked) "" else "Next · ") + DaySentence.clock(plan.minute) + " · " + whenText, LfmType.meta, c.text2, maxLines = 1)
                Txt(item.title, LfmType.rowTitle, c.text, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            item.onClick?.let { open ->
                Txt("Open", LfmType.action, c.action, Modifier.clip(LfmShape.control).clickable(role = Role.Button, onClick = open)
                    .heightIn(min = 44.dp).padding(horizontal = 8.dp, vertical = 12.dp))
            }
        }
    }
}
