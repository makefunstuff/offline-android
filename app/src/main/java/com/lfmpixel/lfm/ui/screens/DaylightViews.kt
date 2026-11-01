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
import com.lfmpixel.lfm.daylight.SkyCanvas
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
 * Daylight's home (docs/ux-spec.md, "Today"): the sky at this hour, the time, the day in one sentence, and today's plans
 * on the horizon with the sun walking toward the next. A tap on a plan picks it: its line shows under the horizon,
 * with Open when it can be opened. Under the sky, one tap each: Talk, Look, Ask, and the whole page below.
 */
@Composable
internal fun SkyHead(
    g: AppGraph, now: LocalDateTime, sky: Sky, timeline: List<AgendaItem>, openTodos: Int,
    onDayBefore: () -> Unit, onDump: () -> Unit, onSettings: () -> Unit,
    onTalk: () -> Unit, onLook: () -> Unit, onAsk: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val pairs = horizonPlans(timeline, zone)
    val nowMin = now.hour * 60 + now.minute
    val layout = HorizonLayout(nowMin, pairs.map { it.first })
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val pickedPair = pairs.firstOrNull { it.first.key == picked }
    val view = LocalView.current
    val sentence = DaySentence.of(now, pairs.map { it.first }, openTodos)
    // The sun's arc stays low, under the time and the sentence, so it never sits behind words.
    val skyH = 340.dp
    val horizonAt = 0.82f

    Column {
        BoxWithConstraints(Modifier.fillMaxWidth().height(skyH)) {
            SkyCanvas(sky, layout, Modifier.fillMaxSize(), selected = picked, horizonAt = horizonAt, arcHeight = 60.dp)
            OnSky(sky) {
                val c = Lfm.c
                Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 6.dp, top = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Txt("Today · " + now.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)), LfmType.label, c.text2,
                            Modifier.weight(1f).semantics { heading() })
                        IconButtonPlain("back", onDayBefore, tint = c.text2, desc = "The day before")
                        IconButtonPlain("cloud", onDump, tint = c.text2, desc = "Brain dump")
                        IconButtonPlain("user", onSettings, tint = c.text2, desc = "Settings")
                    }
                    Txt(now.format(DateTimeFormatter.ofPattern("HH:mm")), LfmType.clock, c.text, Modifier.semantics {
                        contentDescription = "It's " + now.format(DateTimeFormatter.ofPattern("HH:mm"))
                    })
                    Txt(sentence, LfmType.voice, c.text, Modifier.padding(top = 6.dp, end = 14.dp), maxLines = 3)
                }
                // Plans on the horizon: a 44 dp target each, read out with its time.
                val w = maxWidth
                val hy = skyH * horizonAt
                val pad = 20.dp
                pairs.forEach { (plan, item) ->
                    val x = pad + (w - pad * 2) * layout.x(plan.minute)
                    Box(Modifier.offset(x - 22.dp, hy - 22.dp).size(44.dp).clip(LfmShape.round)
                        .clickable(role = Role.Button, onClickLabel = "Show") {
                            picked = if (picked == plan.key) null else plan.key
                            g.feel.cue(Cue.Tick, view)
                        }
                        .semantics {
                            contentDescription = DaySentence.clock(plan.minute) + " " + item.title
                            if (picked == plan.key) stateDescription = "Shown"
                        })
                }
                // Under the horizon: the picked plan, else the next one, else a quiet line.
                val shown = pickedPair ?: layout.next?.let { n -> pairs.firstOrNull { it.first == n } }
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(skyH * (1 - horizonAt)).padding(horizontal = 20.dp),
                    contentAlignment = Alignment.CenterStart) {
                    PlanLine(shown, layout, isPicked = pickedPair != null)
                }
            }
        }
        // One tap from everything: the ways in sit right under the sky.
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WayIn("wave", "Talk", Modifier.weight(1f), onTalk)
            WayIn("camera", "Look", Modifier.weight(1f), onLook)
            WayIn("chat", "Ask", Modifier.weight(1f), onAsk)
        }
    }
}

@Composable
private fun PlanLine(shown: Pair<Plan, AgendaItem>?, layout: HorizonLayout, isPicked: Boolean) {
    val c = Lfm.c
    val reduced = LfmMotion.reduced()
    AnimatedContent(shown?.first?.key, transitionSpec = {
        if (reduced) fadeIn(androidx.compose.animation.core.snap()) togetherWith fadeOut(androidx.compose.animation.core.snap())
        else fadeIn(androidx.compose.animation.core.tween(LfmMotion.SHORT, easing = LfmMotion.standard)) togetherWith
            fadeOut(androidx.compose.animation.core.tween(LfmMotion.TINY, easing = LfmMotion.standard))
    }, label = "plan") { key ->
        val pair = if (key == shown?.first?.key) shown else null
        if (pair == null) {
            Txt("Nothing more at a set time today", LfmType.small, c.text2)
            return@AnimatedContent
        }
        val (plan, item) = pair
        val until = layout.minutesTo(plan)
        val whenText = when {
            layout.isPast(plan) -> "earlier"
            until <= 0 -> "now"
            else -> "in " + DaySentence.span(until)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Txt((if (isPicked) "" else "Next · ") + DaySentence.clock(plan.minute) + " · " + whenText, LfmType.meta, c.text2, maxLines = 1)
                Txt(item.title, LfmType.rowTitle, c.text, maxLines = 1)
            }
            item.onClick?.let { open ->
                val shape = LfmShape.control
                Txt("Open", LfmType.button, c.text, Modifier.clip(shape).border(1.dp, c.control, shape).clickable(role = Role.Button, onClick = open)
                    .heightIn(min = 36.dp).padding(horizontal = 14.dp, vertical = 9.dp))
            }
        }
    }
}

@Composable
private fun WayIn(icon: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    val c = Lfm.c
    val shape = LfmShape.card
    Row(modifier.clip(shape).border(1.dp, c.line, shape).clickable(role = Role.Button, onClick = onClick).heightIn(min = 48.dp)
        .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        LIcon(icon, size = 18.dp, tint = c.text)
        Spacer(Modifier.width(8.dp))
        Txt(label, LfmType.button, c.text)
    }
}
