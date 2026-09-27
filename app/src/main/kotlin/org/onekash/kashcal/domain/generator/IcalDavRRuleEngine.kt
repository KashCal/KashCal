package org.onekash.kashcal.domain.generator

import org.onekash.icaldav.recurrence.RRuleExpander
import org.onekash.kashcal.domain.generator.icaldav.IcalDavRRuleAdapter
import java.time.Instant

/**
 * Expands an RRULE to occurrence start timestamps over icaldav-core's [RRuleExpander] (ical4j).
 *
 * The production recurrence engine. Its signature matches the test-only `LibRecurEngine`
 * oracle that the parity tests compare it against, and it keeps that engine's quirks. The
 * letters match the oracle's.
 *
 * In [IcalDavRRuleAdapter]:
 *   (a) All-day events expand in UTC regardless of TZID.
 *   (b) When an RRULE has both COUNT and UNTIL, UNTIL is stripped.
 *   (g) DATE-format RDATE/EXDATE inherit DTSTART's hour, minute and second on timed events.
 *
 * Here:
 *   (e) At most MAX_ITERATIONS (10,000) timestamps are returned. ical4j's `maxIncrementCount`
 *       caps matching attempts, a different measure; without this cap an unbounded
 *       SECONDLY or MINUTELY rule can OOM.
 *   (f)/(h) Every timestamp is aligned to a whole second, so sub-second DTSTART precision is
 *       dropped on recurring expansion.
 *
 * Returns an empty list for a null or blank RRULE, one that fails to parse, or any exception
 * (logged). Output is filtered to rangeStartMs inclusive through rangeEndMs exclusive,
 * matching the oracle's range-bound iterator.
 */
object IcalDavRRuleEngine {

    // Mirrored in the test-only LibRecurEngine oracle; keep the two in sync if a constant
    // is tuned.
    private const val MAX_ITERATIONS = 10_000
    private const val MILLISECONDS_PER_SECOND = 1000L

    private val expander = RRuleExpander()

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
            val event = IcalDavRRuleAdapter.buildICalEvent(
                rrule = rrule,
                dtstartMs = dtstartMs,
                timezone = timezone,
                isAllDay = isAllDay,
                rdateStrings = rdateStrings,
                exdateStrings = exdateStrings,
            )
            // An RRULE that fails to parse (garbage, missing FREQ) expands to nothing, as in
            // the oracle. The adapter leaves event.rrule null on a parse failure, and
            // RRuleExpander.expand would then return DTSTART alone.
            val rule = event.rrule ?: return emptyList()
            // Quirk (e): give a rule with neither COUNT nor UNTIL a COUNT of MAX_ITERATIONS
            // before ical4j sees it, so the expander doesn't materialize millions of
            // entries (OOM). Bounded rules pass through unchanged.
            val capped = if (rule.count == null && rule.until == null) {
                event.copy(rrule = rule.copy(count = MAX_ITERATIONS))
            } else {
                event
            }
            val occurrences = expander.expand(
                masterEvent = capped,
                rangeStart = Instant.ofEpochMilli(rangeStartMs),
                rangeEnd = Instant.ofEpochMilli(rangeEndMs),
            )
            // RRuleExpander doesn't strictly bound by range, so filter to the range, then
            // align to seconds (quirks f/h). The final take(MAX_ITERATIONS) caps a bounded
            // but huge rule (e.g. COUNT=50000), which the cap above leaves alone.
            IcalDavRRuleAdapter.extractTimestamps(occurrences)
                .filter { it in rangeStartMs until rangeEndMs }
                .map { (it / MILLISECONDS_PER_SECOND) * MILLISECONDS_PER_SECOND }
                .take(MAX_ITERATIONS)
        } catch (e: Exception) {
            android.util.Log.e(
                "IcalDavRRuleEngine",
                "expandToTimestamps failed for rrule='$rrule', dtstartMs=$dtstartMs: ${e.message}",
                e,
            )
            emptyList()
        }
    }
}
