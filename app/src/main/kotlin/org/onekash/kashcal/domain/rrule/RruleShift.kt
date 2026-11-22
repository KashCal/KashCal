package org.onekash.kashcal.domain.rrule

import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.domain.generator.IcalDavRRuleEngine
import org.onekash.kashcal.domain.generator.icaldav.IcalDavRRuleAdapter
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Holds which drag scopes can move a series to another day ([RruleShift.dragAvailability]). */
data class DragMoveAvailability(val allEvents: Boolean, val thisAndFuture: Boolean)

/**
 * Moves a recurring series to another day, time of day, zone or all-day flag,
 * rewriting its repeat rule to match.
 *
 * RFC 5545 section 3.8.5.3 makes DTSTART the first instance of the set and says
 * a DTSTART out of step with the rule gives an undefined set, so moving the
 * start alone (Monday series now starting on a Tuesday) breaks the series. The
 * rewrite is proposed from the rule's day parts, then checked: expanded from
 * the new start, it must generate exactly the old occurrences, each moved to
 * its local date plus the day shift at the new local time, in the new zone
 * (all-day: the UTC date). Anything else (a 29th that becomes a 31st, a 2nd
 * Monday on the 14th, week groupings that change) is refused with null.
 */
object RruleShift {

    /** Occurrences compared for a rule with no COUNT or UNTIL. */
    private const val COMPARE_COUNT = 500
    private const val SECOND_MS = 1_000L
    private const val DAY_MS = 86_400_000L

    /** Far enough ahead for any bounded or capped expansion to finish. */
    private val YEARS_AHEAD: Long = 1_000L * 366 * 24 * 60 * 60 * 1_000

    private val WEEKDAYS = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")
    private val UTC_UNTIL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    private val LOCAL_UNTIL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val DATE_UNTIL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val REFUSED_PARTS = setOf("BYSETPOS", "BYYEARDAY", "BYWEEKNO")
    private val TIME_PARTS = setOf("BYHOUR", "BYMINUTE", "BYSECOND")
    private val ORDINAL_DAY = Regex("""^([+-]?\d{1,2})?(MO|TU|WE|TH|FR|SA|SU)$""")

    /**
     * Returns the zone a series expands in, resolved as the recurrence engine does: all-day in
     * UTC, a missing or unknown zone as floating (the phone's).
     */
    fun zoneFor(tzid: String?, isAllDay: Boolean): ZoneId =
        IcalDavRRuleAdapter.resolveZone(tzid, isAllDay) ?: ZoneId.systemDefault()

    /**
     * Returns the rule for a series that started at [dtstartMs] and now starts at [newDtstartMs],
     * or null when no rule generates the moved occurrences. [zone] and [isAllDay] are the
     * event's; [newZone] and [newIsAllDay] are the moved series' when the move also changes them
     * (a zone change keeps each occurrence's local date and the new clock time). Never throws.
     */
    fun shift(
        rrule: String,
        dtstartMs: Long,
        newDtstartMs: Long,
        zone: String?,
        isAllDay: Boolean,
        newZone: String? = zone,
        newIsAllDay: Boolean = isAllDay,
    ): String? {
        val sameKind = newZone == zone && newIsAllDay == isAllDay
        if (newDtstartMs == dtstartMs && sameKind) return rrule
        return try {
            val z = zoneFor(zone, isAllDay)
            val zNew = zoneFor(newZone, newIsAllDay)
            val oldStart = Instant.ofEpochMilli(dtstartMs).atZone(z)
            val newStart = Instant.ofEpochMilli(newDtstartMs).atZone(zNew)
            val days = ChronoUnit.DAYS.between(oldStart.toLocalDate(), newStart.toLocalDate())
            val newTime = newStart.toLocalTime()
            val timeDelta = if (isAllDay || newIsAllDay) Duration.ZERO else Duration.between(oldStart.toLocalTime(), newTime)
            val proposed = propose(rrule, days, z, zNew, isAllDay, newIsAllDay, timeDelta) ?: return null
            val ok = verifies(rrule, dtstartMs, proposed, newDtstartMs, zone, isAllDay, newZone, newIsAllDay, z, zNew, days, newTime)
            if (ok) proposed else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Returns which scopes a drop of the occurrence at [occurrenceStartMs] to [droppedStartMs] may
     * offer. A drop on the same local day in the event's zone offers both (it keeps the existing
     * same-day time-drag path). A cross-day drop needs a series that still repeats and still has
     * that occurrence, a rule that expresses the move and, for the part of the series it
     * rewrites, no deleted, added or edited occurrences: their recorded times would no longer
     * match the moved slots (RFC 5545 section 3.8.4.4).
     */
    fun dragAvailability(
        master: Event,
        exceptions: List<Event>,
        occurrenceStartMs: Long,
        droppedStartMs: Long,
    ): DragMoveAvailability {
        val z = zoneFor(master.timezone, master.isAllDay)
        val days = ChronoUnit.DAYS.between(
            Instant.ofEpochMilli(occurrenceStartMs).atZone(z).toLocalDate(),
            Instant.ofEpochMilli(droppedStartMs).atZone(z).toLocalDate(),
        )
        if (days == 0L) return DragMoveAvailability(allEvents = true, thisAndFuture = true)
        val none = DragMoveAvailability(allEvents = false, thisAndFuture = false)
        // A sync can change the series between the drop and this check.
        val rule = master.rrule ?: return none
        if (!isOccurrence(master, rule, occurrenceStartMs)) return none

        val allEvents = master.exdate.isNullOrBlank() && master.rdate.isNullOrBlank() && exceptions.isEmpty() &&
            shift(rule, master.startTs, movedStart(master, occurrenceStartMs, droppedStartMs), master.timezone, master.isAllDay) != null
        val thisAndFuture = exceptions.none { (it.originalInstanceTime ?: Long.MIN_VALUE) >= occurrenceStartMs } &&
            !extraDatesReachFrom(master, occurrenceStartMs) &&
            shift(rule, occurrenceStartMs, droppedStartMs, master.timezone, master.isAllDay) != null
        return DragMoveAvailability(allEvents = allEvents, thisAndFuture = thisAndFuture)
    }

    /**
     * Returns the new start of the whole series when its occurrence at [occurrenceStartMs] is
     * dropped at [droppedStartMs]: the series' first date plus the same day shift, at the dropped
     * local time (all-day: the UTC date).
     */
    fun movedStart(master: Event, occurrenceStartMs: Long, droppedStartMs: Long): Long {
        val z = zoneFor(master.timezone, master.isAllDay)
        val dropped = Instant.ofEpochMilli(droppedStartMs).atZone(z)
        val days = ChronoUnit.DAYS.between(Instant.ofEpochMilli(occurrenceStartMs).atZone(z).toLocalDate(), dropped.toLocalDate())
        val firstDate = Instant.ofEpochMilli(master.startTs).atZone(z).toLocalDate().plusDays(days)
        return if (master.isAllDay) {
            firstDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        } else {
            ZonedDateTime.of(firstDate, dropped.toLocalTime(), z).toInstant().toEpochMilli()
        }
    }

    // ---- propose ----

    private fun propose(
        rrule: String,
        days: Long,
        zone: ZoneId,
        newZone: ZoneId,
        isAllDay: Boolean,
        newIsAllDay: Boolean,
        timeDelta: Duration,
    ): String? {
        val parts = parse(rrule) ?: return null
        if (parts.keys.any { it in REFUSED_PARTS }) return null
        if (!timeDelta.isZero && parts.keys.any { it in TIME_PARTS }) return null
        // A yearly month and day moves as one date, so the 31st can cross into the next month.
        val yearlyDate = if (parts["FREQ"] == "YEARLY" && parts.containsKey("BYMONTH") && parts.containsKey("BYMONTHDAY")) {
            val month = parts.getValue("BYMONTH").toIntOrNull() ?: return null
            val day = parts.getValue("BYMONTHDAY").toIntOrNull() ?: return null
            // A common year: a leap-day series is refused by the check whatever is proposed.
            runCatching { LocalDate.of(2001, month, day).plusDays(days) }.getOrNull() ?: return null
        } else {
            null
        }
        val out = LinkedHashMap<String, String>()
        for ((key, value) in parts) {
            out[key] = when {
                key == "BYMONTH" && yearlyDate != null -> yearlyDate.monthValue.toString()
                key == "BYMONTHDAY" && yearlyDate != null -> yearlyDate.dayOfMonth.toString()
                key == "BYDAY" -> shiftWeekdays(value, days) ?: return null
                key == "BYMONTHDAY" -> shiftMonthDays(value, days) ?: return null
                key == "UNTIL" -> shiftUntil(value, days, timeDelta, zone, newZone, isAllDay, newIsAllDay) ?: return null
                else -> value
            }
        }
        return out.entries.joinToString(";") { "${it.key}=${it.value}" }
    }

    private fun parse(rrule: String): LinkedHashMap<String, String>? {
        val body = rrule.removePrefix("RRULE:")
        val parts = LinkedHashMap<String, String>()
        for (piece in body.split(';')) {
            if (piece.isBlank()) continue
            val eq = piece.indexOf('=')
            if (eq <= 0) return null
            parts[piece.substring(0, eq).uppercase()] = piece.substring(eq + 1)
        }
        return parts.takeIf { it.containsKey("FREQ") }
    }

    /**
     * Moves each weekday by [days]; an ordinal keeps its number (the check decides). Returns null
     * for an entry it can't read, a negative ordinal or more than one ordinal day.
     */
    private fun shiftWeekdays(value: String, days: Long): String? {
        val entries = value.split(',')
        var ordinals = 0
        val shifted = entries.map { entry ->
            val match = ORDINAL_DAY.matchEntire(entry.trim().uppercase()) ?: return null
            val ordinal = match.groupValues[1]
            if (ordinal.isNotEmpty()) {
                if (ordinal.startsWith("-")) return null
                ordinals++
            }
            val index = WEEKDAYS.indexOf(match.groupValues[2])
            ordinal + WEEKDAYS[Math.floorMod(index + days, 7L).toInt()]
        }
        if (ordinals > 1) return null
        return shifted.joinToString(",")
    }

    private fun shiftMonthDays(value: String, days: Long): String? =
        value.split(',').map { entry ->
            val day = entry.trim().toIntOrNull() ?: return null
            if (day < 1) return null
            val moved = day + days
            if (moved !in 1..31) return null
            moved.toString()
        }.joinToString(",")

    /**
     * Moves UNTIL by the same days and time-of-day change, from the event's zone to [newZone],
     * per RFC 5545 section 3.3.10: a timed series with a zone keeps a UTC date-time, a floating
     * one a floating date-time, an all-day one a DATE. A series turned all-day ends on the
     * moved local date; one turned timed ends at the last second of its last date in [newZone].
     * A floating date-time UNTIL on an all-day series gives null.
     */
    private fun shiftUntil(
        value: String,
        days: Long,
        timeDelta: Duration,
        zone: ZoneId,
        newZone: ZoneId,
        isAllDay: Boolean,
        newIsAllDay: Boolean,
    ): String? {
        val v = value.trim()
        fun timedEnd(date: LocalDate) =
            ZonedDateTime.of(date, LocalTime.of(23, 59, 59), newZone).withZoneSameInstant(ZoneOffset.UTC).format(UTC_UNTIL)
        // The UNTIL form changes only with the all-day flag (RFC 5545 section 3.3.10: a DATE for a
        // DATE start, UTC for a zoned one); otherwise it keeps the form it had.
        return when {
            v.length == 8 -> {
                val date = LocalDate.parse(v, DATE_UNTIL).plusDays(days)
                if (isAllDay && !newIsAllDay) timedEnd(date) else date.format(DATE_UNTIL)
            }
            v.endsWith("Z") -> {
                val local = LocalDateTime.parse(v, UTC_UNTIL).atOffset(ZoneOffset.UTC).atZoneSameInstant(zone)
                val moved = local.toLocalDateTime().plusDays(days).plus(timeDelta)
                if (!isAllDay && newIsAllDay) {
                    moved.toLocalDate().format(DATE_UNTIL)
                } else {
                    ZonedDateTime.of(moved, newZone).withZoneSameInstant(ZoneOffset.UTC).format(UTC_UNTIL)
                }
            }
            !isAllDay -> {
                val moved = LocalDateTime.parse(v, LOCAL_UNTIL).plusDays(days).plus(timeDelta)
                if (newIsAllDay) moved.toLocalDate().format(DATE_UNTIL) else moved.format(LOCAL_UNTIL)
            }
            else -> null
        }
    }

    // ---- check ----

    private fun verifies(
        oldRule: String,
        oldStart: Long,
        newRule: String,
        newStart: Long,
        tzid: String?,
        isAllDay: Boolean,
        newTzid: String?,
        newIsAllDay: Boolean,
        zone: ZoneId,
        newZone: ZoneId,
        days: Long,
        newTime: LocalTime,
    ): Boolean {
        val bounded = oldRule.contains("COUNT=") || oldRule.contains("UNTIL=")
        val oldCmp = if (bounded) oldRule else "$oldRule;COUNT=$COMPARE_COUNT"
        val newCmp = if (bounded) newRule else "$newRule;COUNT=$COMPARE_COUNT"
        val old = expand(oldCmp, oldStart, tzid, isAllDay)
        val new = expand(newCmp, newStart, newTzid, newIsAllDay)
        if (old.isEmpty() || new.isEmpty() || old.size != new.size) return false
        // A start the old rule doesn't generate gives an undefined set (RFC 5545 section 3.8.5.3).
        if (old.first() != (oldStart / SECOND_MS) * SECOND_MS) return false
        val expected = old.map { moved(it, zone, days, newTime, newZone, newIsAllDay) }
        return expected == new
    }

    /**
     * Returns the first instances, at most [COMPARE_COUNT], of [rule] from [start]; [SeriesMove]
     * aligns a typed rule against the old one over them.
     */
    internal fun firstInstances(rule: String, start: Long, tzid: String?, isAllDay: Boolean): List<Long> {
        val bounded = rule.contains("COUNT=") || rule.contains("UNTIL=")
        return expand(if (bounded) rule else "$rule;COUNT=$COMPARE_COUNT", start, tzid, isAllDay).take(COMPARE_COUNT)
    }

    private fun expand(rule: String, start: Long, tzid: String?, isAllDay: Boolean): List<Long> =
        IcalDavRRuleEngine.expandToTimestamps(
            rrule = rule,
            dtstartMs = start,
            rangeStartMs = start - SECOND_MS,
            rangeEndMs = start + YEARS_AHEAD,
            timezone = tzid,
            isAllDay = isAllDay,
            rdateStrings = null,
            exdateStrings = null,
        )

    /**
     * Moves [ts] to its local date in [zone] + [days] at [newTime] in [newZone] (all-day: UTC
     * midnight), to the second. Taking the local date and the new clock time, not the local
     * time plus a delta, keeps an occurrence a spring-forward gap moved (02:30 run at 03:30) on
     * its slot.
     */
    internal fun moved(ts: Long, zone: ZoneId, days: Long, newTime: LocalTime, newZone: ZoneId, newIsAllDay: Boolean): Long {
        val date = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate().plusDays(days)
        val ms = if (newIsAllDay) {
            date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        } else {
            ZonedDateTime.of(date, newTime, newZone).toInstant().toEpochMilli()
        }
        return (ms / SECOND_MS) * SECOND_MS
    }

    /**
     * Returns true when [rule] from [dtstartMs] generates an instance within a minute of [ts]
     * (the tolerance changed occurrences are linked with). The expansion window starts two days
     * before [ts], not at DTSTART, so a series that started years ago is checked as cheaply as
     * a new one and the engine's per-call date cap never applies; with COUNT, ical4j still
     * counts the instances before the window. EXDATE and RDATE are left out.
     */
    internal fun isInstance(rule: String, dtstartMs: Long, tzid: String?, isAllDay: Boolean, ts: Long): Boolean =
        IcalDavRRuleEngine.expandToTimestamps(
            rule, dtstartMs, ts - 2 * DAY_MS, ts + 2 * DAY_MS, tzid, isAllDay, null, null,
        ).any { kotlin.math.abs(it - ts) < LINK_TOLERANCE_MS }

    /** Linking tolerance between a changed occurrence's key and its series slot. */
    private const val LINK_TOLERANCE_MS = 60_000L

    /**
     * Returns true when the series' rule, from its start, still generates [occurrenceMs]. The
     * range starts two days before the occurrence, not at DTSTART: one engine call reads a
     * limited number of dates from the range start ([IcalDavRRuleEngine]), so a range from
     * DTSTART would miss an occurrence decades on. The two days cover a start a zone change
     * moved: ical4j picks dates by wall time, and a 02:30 in a spring-forward gap starts at
     * 03:30, after the wall time a narrower range would begin at.
     */
    private fun isOccurrence(master: Event, rule: String, occurrenceMs: Long): Boolean {
        val target = (occurrenceMs / SECOND_MS) * SECOND_MS
        return IcalDavRRuleEngine.expandToTimestamps(
            rule, master.startTs, target - 2 * DAY_MS, target + SECOND_MS, master.timezone, master.isAllDay, null, null,
        ).any { it == target }
    }

    /**
     * True when the series' EXDATE or RDATE changes what it generates from [fromMs] on: those
     * recorded dates would no longer match moved slots. It compares the instances from [fromMs]
     * that one engine call returns ([IcalDavRRuleEngine]; about 27 years of a daily series), so
     * a date further ahead isn't seen.
     */
    private fun extraDatesReachFrom(master: Event, fromMs: Long): Boolean {
        if (master.exdate.isNullOrBlank() && master.rdate.isNullOrBlank()) return false
        val rule = master.rrule ?: return false
        val end = master.startTs + YEARS_AHEAD
        fun expandWith(rdate: String?, exdate: String?) = IcalDavRRuleEngine.expandToTimestamps(
            rule, master.startTs, fromMs, end, master.timezone, master.isAllDay, rdate, exdate,
        )
        val plain = expandWith(null, null)
        val withExtras = expandWith(master.rdate, master.exdate)
        return plain.isEmpty() || plain != withExtras
    }
}
