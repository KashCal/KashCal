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
 * Expands RRULE, RDATE and EXDATE over lib-recur (`dmfs/lib-recur`), as a pure function.
 *
 * Test-only: the oracle the parity and fuzz tests compare the production [IcalDavRRuleEngine]
 * against, which [OccurrenceGenerator] calls. Its quirks are lettered (a) to (i) below, with no
 * (f). The production engine keeps (a), (b), (e), (g) and (h) under the same letters; (c), (d)
 * and (i) work around lib-recur itself. Each quirk fixes a failure on real data; changing one
 * without its test reintroduces the failure.
 */
object LibRecurEngine {

    private const val MAX_ITERATIONS = 10_000
    private const val MILLISECONDS_PER_SECOND = 1000L
    private const val SECONDS_PER_DAY = 86400L

    /**
     * Expands an RRULE, plus optional RDATE and EXDATE, to the occurrence start timestamps
     * (ms) from [rangeStartMs] inclusive to [rangeEndMs] exclusive.
     *
     * Returns an empty list for a null or blank RRULE and on any exception (logged).
     *
     * @param rrule RFC 5545 RRULE value (without the "RRULE:" prefix).
     * @param dtstartMs The master event's DTSTART as epoch ms.
     * @param rangeStartMs Range start, inclusive.
     * @param rangeEndMs Range end, exclusive.
     * @param timezone IANA TZID of the event's timezone, or null for the default zone.
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
            // Quirk (a): all-day events expand in UTC because they are stored at UTC
            // midnight. A local zone would shift the date (Jan 6 00:00 UTC is Jan 5 18:00 at
            // UTC-6), putting occurrences on the wrong day.
            val tz = when {
                isAllDay -> TimeZone.getTimeZone("UTC")
                timezone != null -> TimeZone.getTimeZone(timezone)
                else -> TimeZone.getDefault()
            }
            val dtstartSeconds = dtstartMs / MILLISECONDS_PER_SECOND
            val rdates = parseMultiValueField(rdateStrings, isAllDay)
            val exdates = parseMultiValueField(exdateStrings, isAllDay)

            // Quirk (b): COUNT and UNTIL MUST NOT occur in the same rule (RFC 5545 §3.3.10).
            // lib-recur rejects such a rule (InvalidRecurrenceRuleException), which the catch
            // below turns into no occurrences, so UNTIL is stripped and COUNT wins.
            val sanitizedRrule = if (rrule.contains("COUNT=") && rrule.contains("UNTIL=")) {
                rrule.split(";").filter { !it.startsWith("UNTIL=") }.joinToString(";")
            } else {
                rrule
            }
            val rule = RecurrenceRule(sanitizedRrule)

            // Quirk (c): lib-recur requires DTSTART and UNTIL to match in isAllDay() and
            // isFloating(). A DATE-format UNTIL (e.g. "20350927") parses as all-day, so DTSTART
            // must be date-only too; a timed DateTime(tz, y, m, d, 0, 0, 0) throws "using
            // floating start times with absolute until values (and vice versa) is not allowed".
            val untilIsAllDay = rule.until?.isAllDay == true
            val startDateTime = if (isAllDay && untilIsAllDay) {
                timestampToAllDayDateTime(dtstartSeconds)
            } else {
                timestampToDateTime(dtstartSeconds, tz)
            }

            // Quirk (g): RDATE and EXDATE date codes inherit DTSTART's time of day; otherwise
            // a DATE-only value against a timed DTSTART silently fails to match.
            val dtstartHour = if (isAllDay) 0 else startDateTime.hours
            val dtstartMinute = if (isAllDay) 0 else startDateTime.minutes
            val dtstartSecond = if (isAllDay) 0 else startDateTime.seconds

            val baseSet: RecurrenceSet = OfRuleAndFirst(rule, startDateTime)

            // RFC 5545 §3.8.5.1-2: the set is (DTSTART ∪ RRULE ∪ RDATE) - EXDATE.
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

            // Quirk (d): fast-forward to 30 days before the range start only when the range
            // starts more than 30 days after DTSTART; otherwise DTSTART could be lost.
            // Quirk (i): the fast-forward DateTime must match DTSTART's type (all-day or
            // timed), or it hits the same lib-recur isAllDay() check as (c).
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

            // Quirk (e): at most MAX_ITERATIONS instances are read, counting those before the
            // range, so an unbounded rule (FREQ=SECONDLY or MINUTELY without COUNT or UNTIL)
            // can't expand forever.
            while (iterator.hasNext() && iterations < MAX_ITERATIONS) {
                iterations++
                val occurrence = iterator.next()
                // Quirk (h): timestamps are whole seconds, so a round trip through the engine
                // drops sub-second precision.
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
     * Parses a comma-separated RDATE or EXDATE field into YYYYMMDD date codes, dropping values
     * that don't yield one.
     *
     * Formats:
     *   - Milliseconds: "1737331200000" becomes a day code via [Occurrence.toDayFormat]
     *   - Day codes: "20251225" is used as is
     *   - DateTime: "20251225T100000Z" keeps its date part
     */
    internal fun parseMultiValueField(field: String?, isAllDay: Boolean = false): List<String> {
        if (field.isNullOrBlank()) return emptyList()

        return field.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { dateValue ->
                when {
                    // Milliseconds: 10 or more digits (13 for 2020s epoch ms).
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

    /** Parses a YYYYMMDD date code to a lib-recur DateTime at DTSTART's time; null if invalid. */
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
     * Converts a timestamp in seconds to a date-only lib-recur DateTime, by its UTC date.
     * Needed for a DATE-format UNTIL (quirk (c)).
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

    /** Converts a timestamp in seconds to a lib-recur DateTime in [tz]. */
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
     * Converts a lib-recur DateTime to a timestamp in seconds.
     *
     * All-day events always convert in UTC: lib-recur may return a DateTime with a null
     * timezone for some patterns (e.g. FREQ=YEARLY), and the device's default zone would shift
     * the date.
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
