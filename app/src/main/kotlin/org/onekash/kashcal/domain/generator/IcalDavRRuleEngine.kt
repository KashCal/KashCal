package org.onekash.kashcal.domain.generator

import org.onekash.icaldav.recurrence.RRuleExpander
import org.onekash.kashcal.domain.generator.icaldav.IcalDavRRuleAdapter
import java.time.Instant

/**
 * Pure-function RRULE expansion over icaldav-core's [RRuleExpander] (ical4j).
 *
 * Drop-in replacement for [LibRecurEngine.expandToTimestamps] with identical
 * signature. [OccurrenceGenerator] routes through this object after the
 * lib-recur → ical4j migration.
 *
 * Quirks preserved via [IcalDavRRuleAdapter]:
 *   (a) All-day events force UTC regardless of TZID (via ICalDateTime isDate semantics).
 *   (b) COUNT+UNTIL sanitization — strip UNTIL when COUNT is present.
 *   (g) DATE-format RDATE/EXDATE inherit DTSTART hour/minute/second on timed events.
 *
 * Quirks preserved in this engine:
 *   (e) MAX_ITERATIONS=10_000 safety cap on the total number of timestamps
 *       emitted. ical4j's `maxIncrementCount` is a matching-attempt cap with
 *       different semantics; without an explicit cap here, unbounded
 *       SECONDLY/MINUTELY rules can OOM.
 *   (f)/(h) Second-boundary alignment of every returned timestamp. Matches
 *       lib-recur's seconds-math path. Preserves the documented behavior
 *       where sub-second DTSTART precision is dropped on recurring expansion.
 *
 * Defensive behavior matches LibRecurEngine: on any exception, log and return
 * emptyList. Range filter applied post-expansion to match LibRecurEngine's
 * range-bound iterator semantics — without it, non-recurring events whose
 * DTSTART falls before rangeStart would leak into the output.
 */
object IcalDavRRuleEngine {

    // Mirrored in the test-only LibRecurEngine oracle used by the parity
    // harness — keep in sync if a constant is ever tuned.
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
            // Match LibRecurEngine: non-null-non-blank rrule that failed to
            // parse (garbage, missing FREQ, etc.) yields empty expansion, not
            // DTSTART-only. The adapter returns event.rrule=null on parse
            // failure; RRuleExpander.expand would otherwise return [DTSTART].
            val rule = event.rrule ?: return emptyList()
            // Quirk (e): cap unbounded rules (FREQ=SECONDLY/MINUTELY without
            // COUNT or UNTIL) at MAX_ITERATIONS BEFORE passing to ical4j,
            // so the expander doesn't materialize millions of entries (OOM).
            // Bounded rules pass through unchanged.
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
            // REGRESSION GUARD: RRuleExpander doesn't strictly bound by range;
            // match LibRecurEngine's range-bound iterator with an explicit
            // filter. Then apply quirks (f)/(h) second-alignment. Trailing
            // .take(MAX_ITERATIONS) catches the bounded-but-pathological case
            // (e.g. COUNT=50000) that the pre-expansion cap above doesn't
            // touch because the rule already has a COUNT.
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
