package com.lfmpixel.lfm.daylight

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.widget.RemoteViews
import com.lfmpixel.lfm.AppGraph
import com.lfmpixel.lfm.LfmApp
import com.lfmpixel.lfm.MainActivity
import com.lfmpixel.lfm.R
import com.lfmpixel.lfm.data.EntryKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Today's timed things for the horizon: calendar events, reminders still to come, and to-dos with a time. */
suspend fun todayPlans(g: AppGraph, now: LocalDateTime): List<Plan> {
    val zone = ZoneId.systemDefault()
    val start = now.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
    val end = now.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    fun min(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).let { it.hour * 60 + it.minute }
    val events = runCatching { g.calendar.between(start, end) }.getOrDefault(emptyList()).map { Plan(min(it.start), it.title, "Event") }
    val reminders = g.db.reminders().between(start, end).first().filter { !it.fired }.map { Plan(min(it.at), it.text, "Reminder") }
    val todos = g.db.entries().day(start, end).first()
        .filter { it.kind == EntryKind.Todo && !it.done && it.dueHasTime && (it.dueAt ?: 0) in start until end }
        .map { Plan(min(it.dueAt!!), it.title, "To-do") }
    return (events + reminders + todos).sortedBy { it.minute }
}

/**
 * Daylight's widget, lock screen first (docs/ux-spec.md, "Lock screen and widget"): the sky of the hour with today's
 * horizon and the sun, the time, and the next thing in a line. A tap opens Today. It is redrawn every 30 minutes and
 * whenever Inkling is left; the time is a live clock, and the next thing is said with its time, never "in 20 minutes",
 * so a picture up to half an hour old still tells the truth.
 */
class HorizonWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val done = goAsync()
        val g = (context.applicationContext as LfmApp).graph
        g.scope.launch {
            // Each widget on its own, as refresh() does: a failed draw (the launcher gone, the disk full) leaves the last
            // picture up instead of failing the app's scope from the home screen.
            try { ids.forEach { runCatching { render(context, g, manager, it) } } } finally { done.finish() }
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) =
        onUpdate(context, manager, intArrayOf(id))

    companion object {
        /** Redraws every horizon widget (Inkling was left, so a plan may have changed). */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, HorizonWidget::class.java))
            if (ids.isEmpty()) return
            val g = (context.applicationContext as LfmApp).graph
            g.scope.launch { ids.forEach { runCatching { render(context, g, manager, it) } } }
        }

        private suspend fun render(context: Context, g: AppGraph, manager: AppWidgetManager, id: Int) {
            val now = DayClock.now()
            val plans = todayPlans(g, now)
            val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            val sky = Skies.at(now, dim = dark)
            val layout = HorizonLayout(now.hour * 60 + now.minute, plans)
            val opts = manager.getAppWidgetOptions(id)
            val d = context.resources.displayMetrics.density
            // Portrait size (the phone's usual way up): min width by max height. The picture is cropped, never stretched.
            val wDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0).takeIf { it > 0 } ?: 320
            val hDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0).takeIf { it > 0 } ?: 140
            // Kept small: a widget's bitmap travels to the launcher in a parcel.
            val scale = minOf(1f, 900f / (wDp * d), 500f / (hDp * d))
            val w = (wDp * d * scale).toInt().coerceAtLeast(1)
            val h = (hDp * d * scale).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            SkyPainter.draw(Canvas(bmp), w.toFloat(), h.toFloat(), d * scale, sky, layout, horizonAt = 0.86f, arcHeight = h * 0.2f)
            val next = layout.next
            val line = when {
                next != null -> "Next · ${DaySentence.clock(next.minute)} ${next.title}"
                else -> DaySentence.of(now, plans, 0)
            }
            val views = RemoteViews(context.packageName, R.layout.widget_horizon).apply {
                setImageViewBitmap(R.id.sky, bmp)
                setTextColor(R.id.clock, sky.ink)
                setTextColor(R.id.line, sky.ink)
                setTextViewText(R.id.line, line)
                setContentDescription(R.id.root, "Inkling, today. $line")
                setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(context, 5,
                    MainActivity.intent(context, MainActivity.OPEN_NOW), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
            manager.updateAppWidget(id, views)
        }
    }
}
