package org.onekash.kashcal.util

/**
 * Edits and compares RRULE strings: UNTIL and COUNT bounds, "this and future" splits, and
 * cosmetic equivalence.
 *
 * Lives in util so the Room writer and the CalendarProvider repository share it without
 * depending on each other.
 */
object RruleUtils {

    /**
     * Sets UNTIL on [rrule], replacing an existing UNTIL or COUNT (RFC 5545 §3.3.10 forbids both
     * in one rule).
     *
     * @param isAllDay formats UNTIL as a DATE, matching an all-day DTSTART (RFC 5545 §3.3.10)
     */
    fun addUntilToRrule(rrule: String, untilMs: Long, isAllDay: Boolean = false): String {
        val untilDate = formatUntilDate(untilMs, isAllDay)

        return when {
            rrule.contains("UNTIL=") -> {
                rrule.replace(Regex("UNTIL=[^;]+"), "UNTIL=$untilDate")
            }
            rrule.contains("COUNT=") -> {
                val withoutCount = rrule.replace(Regex(";?COUNT=\\d+"), "")
                "$withoutCount;UNTIL=$untilDate"
            }
            else -> "$rrule;UNTIL=$untilDate"
        }
    }

    /**
     * Splits a series' RRULE at an occurrence for "this and future", keeping the total count of
     * a COUNT rule across the two halves.
     *
     * - COUNT master (`COUNT=N`): the master gets `COUNT=pastCount`. The new series gets the
     *   user's rule with `COUNT=N - pastCount` when the user kept `COUNT=N`; a user rule with a
     *   different COUNT, or no COUNT, is used verbatim.
     * - UNTIL or unbounded master: the master gets `UNTIL=untilMs` via [addUntilToRrule] and
     *   the new series gets the user's rule verbatim. With no edit that is the master's own
     *   rule, so its UNTIL, or its lack of one, carries over.
     *
     * RFC 5545 §3.3.10 forbids COUNT and UNTIL in one rule, and ical4j's `Recur` enforces it
     * (`setCount` clears `until` and vice versa).
     *
     * A degenerate COUNT split ([isDegenerateCountSplit]) can yield `COUNT=0` here, so callers
     * check it first and update the master in place instead. The caller computes [pastCount]:
     * `OccurrenceGenerator.expandForPreview` for Room events, the CalendarProvider Instances
     * table for device events.
     *
     * @param userRrule the rule the new series should carry: [masterRrule] itself when the user
     *   didn't change the recurrence, null when the user picked "Does not repeat".
     * @param untilMs the master's new UNTIL instant, usually `splitTime - 1`. Ignored for a
     *   COUNT master.
     * @param pastCount occurrences strictly before the split. Used only for a COUNT master.
     * @param isAllDay forwarded to [formatUntilDate] for the UNTIL form.
     * @return the master's truncated rule and the new series' rule, which is null only when
     *   [userRrule] is null.
     */
    fun splitRruleAtTime(
        masterRrule: String,
        userRrule: String?,
        untilMs: Long,
        pastCount: Int,
        isAllDay: Boolean,
    ): Pair<String, String?> {
        // A COUNT master keeps COUNT=pastCount and never gets UNTIL; any other master gets
        // UNTIL=untilMs.
        val masterCountMatch = COUNT_REGEX.find(masterRrule)
        if (masterCountMatch != null) {
            val total = masterCountMatch.groupValues[1].toIntOrNull() ?: 0
            val masterCount = pastCount.coerceAtLeast(0).coerceAtMost(total)
            val truncatedMaster = COUNT_REGEX.replace(masterRrule, "COUNT=$masterCount")
            // userRrule == null means user picked "Does not repeat".
            if (userRrule == null) return truncatedMaster to null
            val newCount = (total - masterCount).coerceAtLeast(0)
            val newSeriesRrule = mergeNewSeriesRrule(userRrule, masterRrule, newCount)
            return truncatedMaster to newSeriesRrule
        }
        val truncatedMaster = addUntilToRrule(masterRrule, untilMs, isAllDay)
        // userRrule == null means user dropped recurrence entirely.
        if (userRrule == null) return truncatedMaster to null
        // The user's rule decides the new series' bounds. With no edit it is the master's
        // rule, so an unbounded series stays unbounded and an UNTIL carries over.
        return truncatedMaster to userRrule
    }

    /**
     * Returns true when [pastCount] is outside `(0, total)` for a COUNT rule, so a split would
     * give the master or the new series an invalid `COUNT=0`. Callers then update the master in
     * place as an "all events" edit instead of calling [splitRruleAtTime].
     *
     * Always false for an UNTIL or unbounded rule.
     */
    fun isDegenerateCountSplit(masterRrule: String, pastCount: Int): Boolean {
        val total = COUNT_REGEX.find(masterRrule)
            ?.groupValues?.get(1)?.toIntOrNull()
            ?: return false
        return pastCount <= 0 || pastCount >= total
    }

    /**
     * Returns true when [rrule] has a COUNT, the rules for which [splitRruleAtTime] and
     * [isDegenerateCountSplit] use the past count.
     */
    fun hasCount(rrule: String): Boolean = COUNT_REGEX.containsMatchIn(rrule)

    /**
     * Builds the new series' RRULE for a COUNT master split.
     *
     * The user's rule decides the bounds: a rule without COUNT, or with a different COUNT, is
     * returned verbatim. Only when the user kept the master's COUNT is it replaced with
     * [newCount], the occurrences left after the split.
     */
    private fun mergeNewSeriesRrule(
        userRrule: String,
        masterRrule: String,
        newCount: Int,
    ): String {
        val masterCount = COUNT_REGEX.find(masterRrule)
            ?.groupValues?.get(1)?.toIntOrNull()
        val userCount = COUNT_REGEX.find(userRrule)
            ?.groupValues?.get(1)?.toIntOrNull()

        // The user dropped COUNT, for example for UNTIL; don't re-add it.
        if (userCount == null) return userRrule

        // A different COUNT means "this many from here"; keep it.
        if (userCount != masterCount) return userRrule

        // Same COUNT as the master: keep the series total.
        return COUNT_REGEX.replace(userRrule, "COUNT=$newCount")
    }

    private val COUNT_REGEX = Regex("COUNT=(\\d+)")

    /**
     * Returns true when two RRULEs differ only cosmetically: part order, key and value case,
     * surrounding whitespace, a trailing `;`, an `RRULE:` prefix, or the order of list values.
     *
     * The recurrence picker can re-emit an unchanged rule in another form, so a byte compare
     * would read it as a user change: `computeEditScopeOptions` would disable save options and
     * [SequenceBumper.shouldBump] would re-notify attendees. Different bounds (COUNT vs UNTIL)
     * or values are real changes and never equal. Both null is equal; null vs non-null isn't.
     */
    fun rrulesEquivalent(a: String?, b: String?): Boolean {
        if (a == null || b == null) return a == b
        return canonicalizeRrule(a) == canonicalizeRrule(b)
    }

    /**
     * Reduces an RRULE to a canonical form: strips an `RRULE:` or `rrule:` prefix and
     * surrounding whitespace, uppercases, drops empty parts (a trailing `;`), and sorts the
     * `KEY=VALUE` parts and the comma-separated values within each.
     */
    private fun canonicalizeRrule(rrule: String): String =
        rrule.trim()
            .removePrefix("RRULE:")
            .removePrefix("rrule:")
            .uppercase(java.util.Locale.ROOT)
            .split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { part ->
                val eq = part.indexOf('=')
                if (eq < 0) return@map part
                val key = part.substring(0, eq)
                val value = part.substring(eq + 1)
                    .split(',')
                    .map { it.trim() }
                    .sorted()
                    .joinToString(",")
                "$key=$value"
            }
            .sorted()
            .joinToString(";")

    /**
     * Formats [timestampMs] as an RRULE UNTIL value in UTC: "20260115" when [isAllDay], else
     * "20260115T100000Z".
     */
    fun formatUntilDate(timestampMs: Long, isAllDay: Boolean = false): String {
        val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        calendar.timeInMillis = timestampMs

        val year = calendar.get(java.util.Calendar.YEAR)
        val month = calendar.get(java.util.Calendar.MONTH) + 1
        val day = calendar.get(java.util.Calendar.DAY_OF_MONTH)

        if (isAllDay) {
            return String.format(java.util.Locale.ROOT, "%04d%02d%02d", year, month, day)
        }

        return String.format(
            java.util.Locale.ROOT,
            "%04d%02d%02dT%02d%02d%02dZ",
            year, month, day,
            calendar.get(java.util.Calendar.HOUR_OF_DAY),
            calendar.get(java.util.Calendar.MINUTE),
            calendar.get(java.util.Calendar.SECOND)
        )
    }
}
