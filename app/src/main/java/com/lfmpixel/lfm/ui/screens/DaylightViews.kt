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
    val skyH = 352.dp
    val horizonAt = 0.62f
    val reduced = LfmMotion.reduced()
    val rise = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(if (reduced || risen) 1f else 0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (rise.value < 1f) rise.animateTo(1f, androidx.compose.animation.core.tween(900, easing = LfmMotion.emphasizedDecelerate))
        risen = true
    }

    BoxWithConstraints(Modifier.fillMaxWidth().height(skyH)) {
        DialCanvas(sky, layout, horizonAt, Modifier.fillMaxSize(), selected = picked, sunAt = rise.value)
        OnSky(sky) {
            val c = Lfm.c
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 6.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt(now.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)), LfmType.label, c.text2,
                    Modifier.weight(1f).semantics { heading() })
                IconButtonPlain("back", onDayBefore, tint = c.text2, desc = "The day before")
                IconButtonPlain("camera", onLook, tint = c.text2, desc = "Look")
                IconButtonPlain("cloud", onDump, tint = c.text2, desc = "Brain dump")
                IconButtonPlain("user", onSettings, tint = c.text2, desc = "Settings")
            }
            val hy = skyH * horizonAt
            // The time, inside the dial, resting on the horizon.
            Txt(now.format(DateTimeFormatter.ofPattern("HH:mm")), LfmType.clock, c.text,
                Modifier.align(Alignment.TopCenter).offset(y = hy - 66.dp).semantics {
                    contentDescription = "It's " + now.format(DateTimeFormatter.ofPattern("HH:mm"))
                })
            // Plans on the dial: a 44 dp target each, read out with its time.
            val density = androidx.compose.ui.platform.LocalDensity.current
            val geo = with(density) { com.lfmpixel.lfm.daylight.SkyPainter.dialGeometry(maxWidth.toPx(), skyH.toPx(), density.density, horizonAt) }
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
            // Under the horizon: Inkling's sentence, then the picked plan, else the next one.
            Column(Modifier.align(Alignment.TopCenter).offset(y = hy + 14.dp).fillMaxWidth().padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Txt(sentence, LfmType.voice, c.text2, maxLines = 3, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                val shown = pickedPair ?: layout.next?.let { n -> pairs.firstOrNull { it.first == n } }
                PlanLine(shown, layout, isPicked = pickedPair != null)
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
        Row(Modifier.padding(top = 4.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Txt((if (isPicked) "" else "Next · ") + DaySentence.clock(plan.minute) + " " + item.title + " · " + whenText,
                LfmType.small, c.text, Modifier.weight(1f, fill = false), maxLines = 1)
            item.onClick?.let { open ->
                Txt("Open", LfmType.action, c.text, Modifier.clip(LfmShape.control).clickable(role = Role.Button, onClick = open)
                    .heightIn(min = 44.dp).padding(horizontal = 8.dp, vertical = 12.dp))
            }
        }
    }
}
