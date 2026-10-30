package com.lfmpixel.lfm.tools

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** A date, with or without a time of day. */
data class When(val date: LocalDate, val time: LocalTime?) {
    fun toDateTime(): LocalDateTime = date.atTime(time ?: LocalTime.of(9, 0))
    fun epochMillis(zone: ZoneId): Long = toDateTime().atZone(zone).toInstant().toEpochMilli()
}

/**
 * Reads the times the model writes. The prompt asks for ISO 8601 ("2026-10-30T18:00"), but small models
 * also write "18:00", "6pm", "tomorrow 9:00" or "friday", so those work too.
 */
object TimeArgs {
    private val clock24 = Regex("""^(\d{1,2})[:.](\d{2})$""")
    private val clock12 = Regex("""^(\d{1,2})(?:[:.](\d{2}))?\s*([ap])\.?m\.?$""")
    private val inRel = Regex("""^in\s+(\d+)\s*(min|minute|minutes|mins|m|hour|hours|h|hr|hrs|day|days|d)$""")

    fun parse(text: String, now: LocalDateTime): When? {
        val s = text.trim().lowercase(Locale.ROOT).removeSuffix("z").replace(Regex("\\s+"), " ")
        if (s.isEmpty()) return null
        val iso = s.uppercase(Locale.ROOT).replace(' ', 'T')
        runCatching { return LocalDateTime.parse(iso.take(19)).let { When(it.toLocalDate(), it.toLocalTime().withSecond(0)) } }
        runCatching { return LocalDateTime.parse(iso.take(16)).let { When(it.toLocalDate(), it.toLocalTime()) } }
        if (iso.length == 10 || iso.getOrNull(10) == 'T') runCatching { return When(LocalDate.parse(iso.take(10)), null) }
        inRel.find(s)?.let { m ->
            val n = m.groupValues[1].toLong()
            val t = when (m.groupValues[2].first()) {
                'm' -> now.plusMinutes(n)
                'h' -> now.plusHours(n)
                else -> now.plusDays(n)
            }
            return When(t.toLocalDate(), t.toLocalTime().withSecond(0).withNano(0))
        }
        // "tomorrow 9:00", "friday 6pm", "today", "18:00"
        var date: LocalDate? = null
        var rest = s
        val words = s.split(' ')
        val first = words.first()
        when {
            first == "today" || first == "tonight" -> date = now.toLocalDate()
            first == "tomorrow" -> date = now.toLocalDate().plusDays(1)
            dayOfWeek(first) != null -> date = now.toLocalDate().with(TemporalAdjusters.next(dayOfWeek(first)!!))
        }
        if (date != null) rest = words.drop(1).joinToString(" ").removePrefix("at ").trim()
        val time = clock(rest)
        if (date == null && time == null) return null
        if (date == null) {
            // A bare time means the next time the clock shows it.
            date = if (time!!.isAfter(now.toLocalTime())) now.toLocalDate() else now.toLocalDate().plusDays(1)
        }
        if (first == "tonight" && time == null) return When(date, LocalTime.of(20, 0))
        return When(date, time)
    }

    private fun clock(s: String): LocalTime? {
        if (s.isEmpty()) return null
        clock24.find(s)?.let { m ->
            val h = m.groupValues[1].toInt(); val min = m.groupValues[2].toInt()
            if (h in 0..23 && min in 0..59) return LocalTime.of(h, min)
        }
        clock12.find(s)?.let { m ->
            var h = m.groupValues[1].toInt() % 12
            val min = m.groupValues[2].ifEmpty { "0" }.toInt()
            if (m.groupValues[3] == "p") h += 12
            if (min in 0..59) return LocalTime.of(h, min)
        }
        return null
    }

    private fun dayOfWeek(s: String): DayOfWeek? =
        DayOfWeek.entries.firstOrNull { it.getDisplayName(TextStyle.FULL, Locale.ENGLISH).lowercase().startsWith(s) && s.length >= 3 }

    fun parseDuration(text: String): Duration? {
        val s = text.trim().lowercase(Locale.ROOT)
        s.toLongOrNull()?.let { return Duration.ofMinutes(it) }
        runCatching { return Duration.parse(s.uppercase()) }
        var total = Duration.ZERO
        Regex("""(\d+)\s*(h|hour|hours|hr|m|min|mins|minute|minutes|s|sec|secs|second|seconds)\b""").findAll(s).forEach { m ->
            val n = m.groupValues[1].toLong()
            total = total.plus(when (m.groupValues[2].first()) { 'h' -> Duration.ofHours(n); 'm' -> Duration.ofMinutes(n); else -> Duration.ofSeconds(n) })
        }
        return total.takeIf { !it.isZero }
    }

    /** "Today 18:00", "Tomorrow 09:00", "Fri 6 Nov · 20:00". */
    fun label(w: When, today: LocalDate): String {
        val day = when (w.date) {
            today -> "Today"
            today.plusDays(1) -> "Tomorrow"
            else -> w.date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))
        }
        val sep = if (day == "Today" || day == "Tomorrow") " " else " · "
        return if (w.time == null) day else day + sep + w.time.format(DateTimeFormatter.ofPattern("HH:mm"))
    }

    fun iso(w: When): String = if (w.time == null) w.date.toString() else w.toDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
}
