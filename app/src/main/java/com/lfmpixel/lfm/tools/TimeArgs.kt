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
    private val inRel = Regex("""^in\s+(\d+(?:\.\d+)?|an?|half an?|\d+ and a half)\s*(min|minute|minutes|mins|m|hour|hours|h|hr|hrs|day|days|d)$""")
    private val bareHour = Regex("""^(\d{1,2})$""")
    /** About a hundred years: further ahead than any reminder means anything. */
    private const val MAX_AHEAD_S = 100 * 366 * 86_400.0

    fun parse(text: String, now: LocalDateTime): When? {
        val s = text.trim().lowercase(Locale.ROOT).removeSuffix("z").replace(Regex("\\s+"), " ").removePrefix("at ")
            // "on tuesday", "this friday", "next friday": the coming one, as a bare day name is.
            .replace(Regex("^(?:on |this |next )+(?=[a-z]{3})"), "")
        if (s.isEmpty()) return null
        val iso = s.uppercase(Locale.ROOT).replace(' ', 'T')
        runCatching { return LocalDateTime.parse(iso.take(19)).let { When(it.toLocalDate(), it.toLocalTime().withSecond(0)) } }
        runCatching { return LocalDateTime.parse(iso.take(16)).let { When(it.toLocalDate(), it.toLocalTime()) } }
        if (iso.length == 10 || iso.getOrNull(10) == 'T') runCatching { return When(LocalDate.parse(iso.take(10)), null) }
        inRel.find(s)?.let { m ->
            // "in 10 minutes", "in an hour", "in half an hour", "in 2 and a half hours", "in 1.5 hours"
            val count = m.groupValues[1].let { n ->
                when {
                    n == "a" || n == "an" -> 1.0
                    n.startsWith("half") -> 0.5
                    n.endsWith("and a half") -> n.substringBefore(' ').toDouble() + 0.5
                    else -> n.toDouble()
                }
            }
            val unit = when (m.groupValues[2].first()) { 'm' -> 60L; 'h' -> 3_600L; else -> 86_400L }
            // "in 999999999999 days" is no time at all (plusSeconds would throw past the year 999999999).
            val seconds = count * unit
            if (seconds > MAX_AHEAD_S) return null
            val t = now.plusSeconds(seconds.toLong())
            return When(t.toLocalDate(), t.toLocalTime().withSecond(0).withNano(0))
        }
        // A bare hour, "6" or "at 6": the next time the clock shows it, morning or evening.
        bareHour.find(s)?.let { m ->
            val h = m.groupValues[1].toInt()
            if (h > 23) return null
            val candidates = listOf(h, h + 12).filter { it <= 23 && (h <= 12 || it == h) }.map { LocalTime.of(it, 0) }
            val today = candidates.firstOrNull { it.isAfter(now.toLocalTime()) }
            return if (today != null) When(now.toLocalDate(), today) else When(now.toLocalDate().plusDays(1), candidates.first())
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
        var time = clock(rest)
        // A morning time with no am/pm: "tonight at 8" or "9:30" is the evening; "today at 6" (or 6:30) the next
        // time the clock shows it.
        time?.takeIf { it.hour in 1..11 && !rest.contains('a') && !rest.contains('p') }?.let { t ->
            if (first == "tonight" || (first == "today" && !t.isAfter(now.toLocalTime()))) time = t.plusHours(12)
        }
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
        // After a day, a bare hour is that hour: "tomorrow at 6" is 06:00.
        bareHour.find(s)?.let { m -> return m.groupValues[1].toInt().takeIf { it in 0..23 }?.let { LocalTime.of(it, 0) } }
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
        // Numbers too big for a Duration (20 digits, or hours past Long's seconds) are not a duration: null, not a throw.
        try {
            s.toLongOrNull()?.let { return Duration.ofMinutes(it) }
            if (s.all { it.isDigit() }) return null
        } catch (_: ArithmeticException) { return null }
        runCatching { return Duration.parse(s.uppercase()) }
        var total = Duration.ZERO
        Regex("""(\d+)\s*(h|hour|hours|hr|m|min|mins|minute|minutes|s|sec|secs|second|seconds)\b""").findAll(s).forEach { m ->
            val n = m.groupValues[1].toLongOrNull() ?: return null
            total = try { total.plus(when (m.groupValues[2].first()) { 'h' -> Duration.ofHours(n); 'm' -> Duration.ofMinutes(n); else -> Duration.ofSeconds(n) }) }
                catch (_: ArithmeticException) { return null }
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
