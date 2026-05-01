package org.onekash.kashcal.domain.generator

import org.dmfs.rfc5545.DateTime
import org.dmfs.rfc5545.RecurrenceSet
import org.dmfs.rfc5545.recur.RecurrenceRule
import org.dmfs.rfc5545.recurrenceset.Difference
import org.dmfs.rfc5545.recurrenceset.FastForwarded
import org.dmfs.rfc5545.recurrenceset.Merged
import org.dmfs.rfc5545.recurrenceset.OfList
import org.dmfs.rfc5545.recurrenceset.OfRuleAndFirst
import org.onekash.kashcal.data.db.entity.Occurrence
import java.util.Calendar
import java.util.TimeZone

/**
 * Pure-function RRULE expansion over the `dmfs/lib-recur` engine.
 *
 * This object is the single source of truth for how KashCal expands RRULE/RDATE/EXDATE
 * via lib-recur. Orchestration (DB persistence, exception-event linking, lazy extension)
 * lives in [OccurrenceGenerator], which calls through this engine for the expansion step.
 *
 * Every behavior marked `CRITICAL:` below is a bug fix against real data. Modifying
 * this code without a corresponding test update will reintroduce the bug.
 */
object LibRecurEngine {

    private const val MAX_ITERATIONS = 10_000
    private const val MILLISECONDS_PER_SECOND = 1000L
    private const val SECONDS_PER_DAY = 86400L

    /**
     * Expand an RRULE (plus optional RDATE/EXDATE) to the list of occurrence
     * start timestamps (ms) within [rangeStartMs, rangeEndMs).
     *
     * Returns empty list on malformed input rather than throwing.
     *
     * @param rrule RFC 5545 RRULE value (without the "RRULE:" prefix).
     * @param dtstartMs The master event's DTSTART as epoch ms.
     * @param rangeStartMs Range start, inclusive.
     * @param rangeEndMs Range end, exclusive.
     * @param timezone IANA TZID of the event's timezone, or null for floating/local.
     * @param isAllDay Whether this is an all-day event (forces UTC regardless of [timezone]).
     * @param rdateStrings RDATE CSV in mixed format (ms / YYYYMMDD / YYYYMMDDTHHMMSS[Z]).
     * @param exdateStrings EXDATE CSV in the same mixed format.
     * @return Sorted ascending list of occurrence start timestamps in ms.
     */
    fun expandToTimestamps(
        rrule: String?,
        dtstartMs: Long,
        rangeStartMs: Long,
        rangeEndMs: Long,
        timezone: String?,
        isAllDay: Boolean,
        rdateStrings: String?,
        exdateStrings: String?,
    ): List<Long> {
        if (rrule.isNullOrBlank()) return emptyList()

        return try {
            // CRITICAL (a): All-day events MUST use UTC for RRULE expansion. All-day events
            // are stored as UTC midnight. Using local timezone would shift the date
            // (e.g., Jan 6 00:00 UTC in UTC-6 = Jan 5 18:00 local), causing occurrences
            // to appear on the wrong day.
            val tz = when {
                isAllDay -> TimeZone.getTimeZone("UTC")
                timezone != null -> TimeZone.getTimeZone(timezone)
                else -> TimeZone.getDefault()
            }
            val dtstartSeconds = dtstartMs / MILLISECONDS_PER_SECOND
            val rdates = parseMultiValueField(rdateStrings, isAllDay)
            val exdates = parseMultiValueField(exdateStrings, isAllDay)

            // CRITICAL (b): COUNT and UNTIL MUST NOT both appear (RFC 5545).
            // lib-recur returns 0 occurrences when both are present. Strip UNTIL when
            // COUNT exists (COUNT is more deterministic).
            val sanitizedRrule = if (rrule.contains("COUNT=") && rrule.contains("UNTIL=")) {
                rrule.split(";").filter { !it.startsWith("UNTIL=") }.joinToString(";")
            } else {
                rrule
            }
            val rule = RecurrenceRule(sanitizedRrule)

            // CRITICAL (c): lib-recur requires DTSTART and UNTIL to match in isAllDay()/isFloating().
            // DATE-format UNTIL (e.g., "20350927") is parsed as all-day — DTSTART must also be
            // date-only. Using DateTime(tz, y, m, d, 0, 0, 0) creates a timed DateTime that
            // mismatches, causing: "floating start times with absolute until values not allowed"
            val untilIsAllDay = rule.until?.isAllDay == true
            val startDateTime = if (isAllDay && untilIsAllDay) {
                timestampToAllDayDateTime(dtstartSeconds)
            } else {
                timestampToDateTime(dtstartSeconds, tz)
            }

            // CRITICAL (g): RDATE/EXDATE date codes inherit DTSTART's time components.
            // Otherwise a DATE-only RDATE against a timed DTSTART silently fails to match.
            val dtstartHour = if (isAllDay) 0 else startDateTime.hours
            val dtstartMinute = if (isAllDay) 0 else startDateTime.minutes
            val dtstartSecond = if (isAllDay) 0 else startDateTime.seconds

            val baseSet: RecurrenceSet = OfRuleAndFirst(rule, startDateTime)

            // RFC 5545 §3.8.5.1-2: RecurrenceSet = (DTSTART ∪ RRULE ∪ RDATE) - EXDATE
            val withRdates: RecurrenceSet = if (rdates.isNotEmpty()) {
                val rdateDateTimes = rdates.mapNotNull {
                    parseDateCode(it, tz, dtstartHour, dtstartMinute, dtstartSecond)
                }
                if (rdateDateTimes.isNotEmpty()) {
                    Merged(baseSet, OfList(*rdateDateTimes.toTypedArray()))
                } else baseSet
            } else baseSet

            val finalSet: RecurrenceSet = if (exdates.isNotEmpty()) {
                val exdateDateTimes = exdates.mapNotNull {
                    parseDateCode(it, tz, dtstartHour, dtstartMinute, dtstartSecond)
                }
                if (exdateDateTimes.isNotEmpty()) {
                    Difference(withRdates, OfList(*exdateDateTimes.toTypedArray()))
                } else withRdates
            } else withRdates

            // CRITICAL (d): Fast-forward to near range start only when range starts
            // significantly after DTSTART. Otherwise DTSTART could be lost.
            // CRITICAL (i): FastForwarded DateTime type MUST match DTSTART type (all-day
            // vs timed) — mismatched types hit the same lib-recur isAllDay() assertion.
            val rangeStartSeconds = rangeStartMs / MILLISECONDS_PER_SECOND
            val optimizedSet: RecurrenceSet =
                if (rangeStartMs > dtstartMs + 30 * SECONDS_PER_DAY * MILLISECONDS_PER_SECOND) {
                    val fastForwardSeconds = rangeStartSeconds - 30 * SECONDS_PER_DAY
                    val rangeStartDateTime = if (isAllDay && untilIsAllDay) {
                        timestampToAllDayDateTime(fastForwardSeconds.coerceAtLeast(0))
                    } else {
                        timestampToDateTime(fastForwardSeconds.coerceAtLeast(0), tz)
                    }
                    FastForwarded(rangeStartDateTime, finalSet)
                } else {
                    finalSet
                }

            val timestamps = mutableListOf<Long>()
            val iterator = optimizedSet.iterator()
            var iterations = 0

            // CRITICAL (e): MAX_ITERATIONS safety limit — prevents infinite expansion
            // on unbounded rules (FREQ=SECONDLY, FREQ=MINUTELY without COUNT/UNTIL).
            while (iterator.hasNext() && iterations < MAX_ITERATIONS) {
                iterations++
                val occurrence = iterator.next()
                // CRITICAL (h): Sub-second truncation via second-alignment.
                // occurrenceTsSeconds * MILLISECONDS_PER_SECOND produces second-aligned ms.
                // This preserves behavior where round-trip through the engine drops sub-second precision.
                val occurrenceTsSeconds = dateTimeToTimestamp(occurrence, isAllDay)
                val occurrenceTsMs = occurrenceTsSeconds * MILLISECONDS_PER_SECOND

                if (occurrenceTsMs < rangeStartMs) continue
                if (occurrenceTsMs >= rangeEndMs) break

                timestamps.add(occurrenceTsMs)
            }

            timestamps
        } catch (e: Exception) {
            android.util.Log.e("LibRecurEngine",
                "expandToTimestamps failed for rrule='$rrule', dtstartMs=$dtstartMs: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Parse a multi-value field (RDATE or EXDATE) into list of date codes.
     *
     * Supported formats:
     *   - Milliseconds: "1737331200000" -> converts to day code via Occurrence.toDayFormat()
     *   - Day codes: "20251225" -> used directly
     *   - DateTime: "20251225T100000Z" -> extracts date portion
     */
    internal fun parseMultiValueField(field: String?, isAllDay: Boolean = false): List<String> {
        if (field.isNullOrBlank()) return emptyList()

        return field.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { dateValue ->
                when {
                    // Milliseconds format: 10+ digit number (13 digits for 2020s epoch ms)
                    dateValue.length >= 10 && dateValue.all { it.isDigit() } -> {
                        dateValue.toLongOrNull()?.let { ms ->
                            Occurrence.toDayFormat(ms, isAllDay).toString()
                        }
                    }
                    dateValue.contains("T") -> dateValue.substringBefore("T")
                    dateValue.length >= 8 -> dateValue.substring(0, 8)
                    else -> null
                }
            }
            .filter { it.length == 8 && it.all { c -> c.isDigit() } }
    }

    /** Parse a date code (YYYYMMDD) to lib-recur DateTime using DTSTART's time components. */
    internal fun parseDateCode(
        dateCode: String,
        tz: TimeZone,
        hour: Int,
        minute: Int,
        second: Int
    ): DateTime? {
        return try {
            if (dateCode.length < 8) return null
            val year = dateCode.substring(0, 4).toInt()
            val month = dateCode.substring(4, 6).toInt() - 1 // 0-indexed for lib-recur
            val day = dateCode.substring(6, 8).toInt()
            DateTime(tz, year, month, day, hour, minute, second)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Convert timestamp (seconds) to lib-recur all-day DateTime (date-only, no time components).
     * Required for DATE-format RRULE UNTIL compatibility (CRITICAL quirk c).
     */
    internal fun timestampToAllDayDateTime(timestampSeconds: Long): DateTime {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendar.timeInMillis = timestampSeconds * MILLISECONDS_PER_SECOND
        return DateTime(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH), // 0-indexed
            calendar.get(Calendar.DAY_OF_MONTH)
        )
    }

    /** Convert timestamp (seconds) to lib-recur DateTime in the given timezone. */
    internal fun timestampToDateTime(timestampSeconds: Long, tz: TimeZone): DateTime {
        val calendar = Calendar.getInstance(tz)
        calendar.timeInMillis = timestampSeconds * MILLISECONDS_PER_SECOND
        return DateTime(
            tz,
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH), // 0-indexed
            calendar.get(Calendar.DAY_OF_MONTH),
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            calendar.get(Calendar.SECOND)
        )
    }

    /**
     * Convert lib-recur DateTime to timestamp (seconds).
     *
     * CRITICAL: For all-day events, ALWAYS use UTC regardless of what lib-recur returns.
     * lib-recur may return DateTime objects with null timezone for some recurrence patterns
     * (e.g., FREQ=YEARLY). Using the device's default timezone would shift the date incorrectly.
     */
    internal fun dateTimeToTimestamp(dateTime: DateTime, isAllDay: Boolean): Long {
        val tz = when {
            isAllDay -> TimeZone.getTimeZone("UTC")
            dateTime.timeZone != null -> dateTime.timeZone
            else -> TimeZone.getDefault()
        }
        val calendar = Calendar.getInstance(tz)
        calendar.set(
            dateTime.year,
            dateTime.month, // 0-indexed
            dateTime.dayOfMonth,
            dateTime.hours,
            dateTime.minutes,
            dateTime.seconds
        )
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis / MILLISECONDS_PER_SECOND
    }
}
