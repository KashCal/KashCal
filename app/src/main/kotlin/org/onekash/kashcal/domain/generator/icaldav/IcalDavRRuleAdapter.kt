package org.onekash.kashcal.domain.generator.icaldav

import org.onekash.icaldav.model.Classification
import org.onekash.icaldav.model.EventStatus
import org.onekash.icaldav.model.ICalDateTime
import org.onekash.icaldav.model.ICalEvent
import org.onekash.icaldav.model.RRule
import org.onekash.icaldav.model.Transparency
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Calendar
import java.util.TimeZone

/**
 * Builds the icaldav-core [ICalEvent] that `RRuleExpander` expands from the primitive arguments
 * of [org.onekash.kashcal.domain.generator.IcalDavRRuleEngine.expandToTimestamps].
 *
 * Carries three quirks of the test-only `LibRecurEngine` oracle:
 *
 *   (a) All-day events resolve to UTC ([resolveZone]).
 *   (b) When the raw RRULE contains both COUNT and UNTIL, UNTIL is stripped before parsing,
 *       so COUNT wins on this malformed but common input ([sanitizeRRule]).
 *   (g) DATE-format RDATE/EXDATE inherit DTSTART's hour, minute and second on timed events
 *       so `toDayCode()` returns the expected local day.
 */
object IcalDavRRuleAdapter {

    private val DTSTAMP_STATIC = ICalDateTime.parse("20240101T000000Z")

    /**
     * Builds an [ICalEvent] for `RRuleExpander.expand`. An RRULE that fails to parse leaves
     * `rrule` null. Duration doesn't affect the expanded start times, so DTEND equals DTSTART.
     */
    fun buildICalEvent(
        rrule: String?,
        dtstartMs: Long,
        timezone: String?,
        isAllDay: Boolean,
        rdateStrings: String?,
        exdateStrings: String?,
    ): ICalEvent {
        val zone = resolveZone(timezone, isAllDay)
        val dtStart = ICalDateTime.fromTimestamp(dtstartMs, zone, isAllDay)
        // RRuleExpander.expand uses dtEnd only for the occurrences' duration, which
        // `expandToTimestamps` discards. Reusing dtStart saves an allocation per call.
        val dtEnd = dtStart

        val sanitizedRrule = sanitizeRRule(rrule)
        val parsedRRule = sanitizedRrule?.let {
            runCatching { RRule.parse(it) }.getOrNull()
        }

        // Quirk (g): DATE-format RDATE/EXDATE inherit DTSTART's local time.
        val (dtstartHour, dtstartMinute, dtstartSecond) = dtstartLocalTime(
            dtstartMs = dtstartMs,
            zone = zone,
            isAllDay = isAllDay,
        )

        val rdates = parseCsvDates(
            csv = rdateStrings,
            zone = zone,
            isAllDay = isAllDay,
            dtstartHour = dtstartHour,
            dtstartMinute = dtstartMinute,
            dtstartSecond = dtstartSecond,
        )
        val exdates = parseCsvDates(
            csv = exdateStrings,
            zone = zone,
            isAllDay = isAllDay,
            dtstartHour = dtstartHour,
            dtstartMinute = dtstartMinute,
            dtstartSecond = dtstartSecond,
        )

        return ICalEvent(
            uid = "kashcal-expand",
            importId = "kashcal-expand",
            summary = "",
            description = null,
            location = null,
            dtStart = dtStart,
            dtEnd = dtEnd,
            duration = null,
            isAllDay = isAllDay,
            status = EventStatus.CONFIRMED,
            sequence = 0,
            rrule = parsedRRule,
            exdates = exdates,
            rdates = rdates,
            recurrenceId = null,
            alarms = emptyList(),
            categories = emptyList(),
            organizer = null,
            attendees = emptyList(),
            color = null,
            dtstamp = DTSTAMP_STATIC,
            lastModified = null,
            created = null,
            transparency = Transparency.OPAQUE,
            url = null,
            classification = Classification.PUBLIC,
            rawProperties = emptyMap(),
        )
    }

    /** Returns the occurrence start timestamps from expander output, sorted ascending. */
    fun extractTimestamps(events: List<ICalEvent>): List<Long> =
        events.map { it.dtStart.timestamp }.sorted()

    /**
     * Strips UNTIL parts from the raw string when COUNT is present (quirk b), before
     * `RRule.parse`. Returns null for null or blank input.
     *
     * Mirrored in the test-only `LibRecurEngine` oracle; keep the two in sync if the sanitizer
     * grows a new case.
     */
    internal fun sanitizeRRule(rrule: String?): String? {
        if (rrule.isNullOrBlank()) return null
        if (!rrule.contains("COUNT=") || !rrule.contains("UNTIL=")) return rrule
        return rrule.split(";").filter { !it.startsWith("UNTIL=") }.joinToString(";")
    }

    /**
     * Resolves a TZID to a [ZoneId]. All-day events always get UTC (quirk a). A blank or
     * invalid TZID returns null (floating) without an error.
     */
    internal fun resolveZone(tzid: String?, isAllDay: Boolean): ZoneId? {
        if (isAllDay) return ZoneOffset.UTC
        if (tzid.isNullOrBlank()) return null
        return try {
            ZoneId.of(tzid)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Returns DTSTART's local hour, minute and second in [zone], or the device zone when
     * null, for quirk (g). All-day events return (0, 0, 0).
     */
    internal fun dtstartLocalTime(
        dtstartMs: Long,
        zone: ZoneId?,
        isAllDay: Boolean,
    ): Triple<Int, Int, Int> {
        if (isAllDay) return Triple(0, 0, 0)
        val tz = when {
            zone != null -> TimeZone.getTimeZone(zone)
            else -> TimeZone.getDefault()
        }
        val cal = Calendar.getInstance(tz)
        cal.timeInMillis = dtstartMs
        return Triple(
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
            cal.get(Calendar.SECOND),
        )
    }

    /**
     * Parses a comma-separated RDATE/EXDATE list of epoch milliseconds (10+ digits),
     * YYYYMMDD, or YYYYMMDD'T'HHMMSS with optional Z. Silently skips unparseable entries,
     * except that a YYYYMMDD with an out-of-range month or day throws.
     *
     * YYYYMMDD entries on timed events inherit DTSTART's local time (quirk g); on all-day
     * events they are UTC midnight.
     */
    internal fun parseCsvDates(
        csv: String?,
        zone: ZoneId?,
        isAllDay: Boolean,
        dtstartHour: Int,
        dtstartMinute: Int,
        dtstartSecond: Int,
    ): List<ICalDateTime> {
        if (csv.isNullOrBlank()) return emptyList()
        return csv.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull {
                parseSingle(it, zone, isAllDay, dtstartHour, dtstartMinute, dtstartSecond)
            }
    }

    private fun parseSingle(
        value: String,
        zone: ZoneId?,
        isAllDay: Boolean,
        dtstartHour: Int,
        dtstartMinute: Int,
        dtstartSecond: Int,
    ): ICalDateTime? {
        // Milliseconds format: 10+ digit integer.
        if (value.length >= 10 && value.all { it.isDigit() }) {
            val ms = value.toLongOrNull() ?: return null
            return ICalDateTime.fromTimestamp(ms, zone, isAllDay)
        }
        // DateTime format: YYYYMMDD'T'HHMMSS or YYYYMMDD'T'HHMMSS'Z'.
        if (value.contains("T")) {
            return runCatching { ICalDateTime.parse(value) }.getOrNull()
        }
        // DATE format: YYYYMMDD.
        if (value.length == 8 && value.all { it.isDigit() }) {
            val year = value.substring(0, 4).toInt()
            val month = value.substring(4, 6).toInt()
            val day = value.substring(6, 8).toInt()
            val localDate = LocalDate.of(year, month, day)
            return if (isAllDay) {
                // All-day: UTC midnight preserves the calendar date regardless of zone.
                val ms = localDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                ICalDateTime.fromTimestamp(ms, zone, isDate = true)
            } else {
                // quirk (g): inherit DTSTART's local time components for matching.
                val resolvedZone = zone ?: ZoneId.systemDefault()
                val ms = localDate
                    .atTime(dtstartHour, dtstartMinute, dtstartSecond)
                    .atZone(resolvedZone)
                    .toInstant()
                    .toEpochMilli()
                ICalDateTime.fromTimestamp(ms, zone, isDate = false)
            }
        }
        return null
    }
}
