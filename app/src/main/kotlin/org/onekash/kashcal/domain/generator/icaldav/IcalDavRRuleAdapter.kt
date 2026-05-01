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
 * Bridges OccurrenceGenerator's primitive-argument expansion signature to
 * icaldav-core's [ICalEvent] / [RRuleExpander] shape.
 *
 * Ports two behavior-preserving quirks from `LibRecurEngine`:
 *
 *   (b) COUNT+UNTIL sanitization: when the raw RRULE contains both, strip
 *       UNTIL before parsing. Preserves "COUNT wins" behavior for real-world
 *       malformed-but-common inputs.
 *   (g) DATE-format RDATE/EXDATE inherit DTSTART hour/minute/second on timed
 *       events so `toDayCode()` returns the expected local day.
 */
object IcalDavRRuleAdapter {

    private val DTSTAMP_STATIC = ICalDateTime.parse("20240101T000000Z")

    /**
     * Build an ICalEvent suitable for `RRuleExpander.expand`. Duration is
     * irrelevant for expansion (the expander returns occurrence events
     * carrying the master's duration). A nominal 1-hour event is fine.
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
        // ICalEvent requires dtEnd but RRuleExpander.expand uses it only for
        // output-occurrence duration, which `expandToTimestamps` throws away.
        // Reuse dtStart to skip one ICalDateTime allocation per call.
        val dtEnd = dtStart

        val sanitizedRrule = sanitizeRRule(rrule)
        val parsedRRule = sanitizedRrule?.let {
            runCatching { RRule.parse(it) }.getOrNull()
        }

        // quirk (g): compute DTSTART's local hour/minute/second so DATE-format
        // RDATE/EXDATE inherit them when constructing ICalDateTimes.
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

    /** Extract occurrence start timestamps from expander output, sorted ascending. */
    fun extractTimestamps(events: List<ICalEvent>): List<Long> =
        events.map { it.dtStart.timestamp }.sorted()

    /**
     * Strip UNTIL tokens when COUNT is present (quirk b). Raw string-level
     * sanitization, before `RRule.parse`. Returns null for null/blank input.
     *
     * Mirrored in the test-only `LibRecurEngine` oracle used by the parity
     * harness — keep the two implementations in sync if the sanitizer ever
     * grows a new case.
     */
    internal fun sanitizeRRule(rrule: String?): String? {
        if (rrule.isNullOrBlank()) return null
        if (!rrule.contains("COUNT=") || !rrule.contains("UNTIL=")) return rrule
        return rrule.split(";").filter { !it.startsWith("UNTIL=") }.joinToString(";")
    }

    /**
     * Resolve TZID string to a [ZoneId]. All-day events force UTC regardless
     * of input (matches LibRecurEngine quirk a). Invalid TZIDs fall through
     * to null (floating), not errors.
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
     * Extract DTSTART's local hour/minute/second in the event's resolved zone.
     * For all-day events returns (0, 0, 0). Used to implement quirk (g)
     * inheritance for DATE-format RDATE/EXDATE.
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
     * Parse a mixed-format RDATE/EXDATE CSV (milliseconds / YYYYMMDD /
     * YYYYMMDD'T'HHMMSS['Z']) into a list of [ICalDateTime]. Silently skips
     * unparseable entries.
     *
     * DATE-format entries (YYYYMMDD) on timed events inherit DTSTART's local
     * hour/minute/second (quirk g). All-day events use UTC midnight.
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
