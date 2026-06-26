package org.onekash.kashcal.util

/**
 * RRULE utility functions for adding/replacing UNTIL clauses.
 *
 * Extracted from EventWriter so both Room and CalendarProvider layers can use them
 * without cross-layer dependencies.
 */
object RruleUtils {

    /**
     * Add UNTIL parameter to RRULE, replacing existing UNTIL or COUNT.
     *
     * @param rrule The RRULE string (e.g., "FREQ=WEEKLY;BYDAY=MO")
     * @param untilMs Timestamp in millis for UNTIL value
     * @param isAllDay If true, formats UNTIL as date-only (RFC 5545 §3.3.10)
     * @return Modified RRULE string with UNTIL clause
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
     * Split a recurring series's RRULE at a chosen instance so the
     * total instance count is preserved across the split.
     *
     * Two branches against the master's RRULE:
     *
     * - **COUNT-based** (`COUNT=N` present): master keeps
     *   `COUNT=pastCount`; new series carries the user's RRULE with
     *   `COUNT=(N - pastCount)`. Neither side carries UNTIL. RFC 5545
     *   §3.3.10 forbids COUNT and UNTIL in the same recur value, and
     *   ical4j's `Recur` enforces this at the API level (`setCount`
     *   zeros `until` and vice versa).
     *
     * - **UNTIL or unbounded**: master gets `UNTIL=untilMs` via
     *   [addUntilToRrule]. The new series carries the user's RRULE
     *   with the master's original UNTIL preserved (so the user-visible
     *   end date doesn't shift), or — for an unbounded master with no
     *   user edit — `null` to signal the caller should leave the new
     *   series unbounded.
     *
     * **User-edit handling.** When `userRrule` differs from
     * `masterRrule` the user changed the recurrence pattern as part of
     * "this and future"; the new series row carries the user's
     * pattern, with COUNT/UNTIL bounds adjusted appropriately. When
     * they match, the new series mirrors the master's structure
     * verbatim (just with adjusted COUNT or carried-forward UNTIL).
     *
     * **Degenerate splits.** Returns `null` on the new series when:
     * (a) unbounded master with no user edit, or
     * (b) COUNT-based split with `pastCount == 0` or `pastCount >= total`
     *     — both would yield invalid `COUNT=0`. Callers should fall
     *     back to an in-place ALL_EVENTS update on the master.
     *
     * The caller is responsible for computing [pastCount] (typically
     * via `OccurrenceGenerator.expandForPreview` for Room or the
     * CalendarProvider Instances table for device).
     *
     * @param masterRrule The current master's RRULE string.
     * @param userRrule The user-edited RRULE the new series should
     *   carry. Pass the same string as [masterRrule] when there's no
     *   user edit. Required — production callers always pass it
     *   explicitly.
     * @param untilMs Truncate-to instant for master's UNTIL (typically
     *   `splitTime - 1`). Ignored on the COUNT branch.
     * @param pastCount Number of occurrences strictly before the split
     *   point. Used only on the COUNT branch.
     * @param isAllDay Forwarded to [formatUntilDate] for UNTIL form.
     * @return `(masterRrule, newSeriesRrule?)`. `newSeriesRrule == null`
     *   means the caller should fall back to in-place ALL_EVENTS
     *   update on the master.
     */
    fun splitRruleAtTime(
        masterRrule: String,
        userRrule: String?,
        untilMs: Long,
        pastCount: Int,
        isAllDay: Boolean,
    ): Pair<String, String?> {
        // Master's bounds shape determines how the master row is
        // truncated. COUNT-bounded masters keep COUNT=pastCount and
        // never carry UNTIL; everyone else (UNTIL-bounded or
        // unbounded) gets UNTIL=untilMs.
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
        // The user's edited rrule is authoritative on bounds-shape.
        // No edit (userRrule == masterRrule): new row carries master's
        // rrule verbatim — unbounded stays unbounded, UNTIL preserved.
        return truncatedMaster to userRrule
    }

    /**
     * Detect a degenerate COUNT split: pastCount falls outside the
     * range `(0, total)` so producing master `COUNT=pastCount` or
     * new-series `COUNT=total-pastCount` would yield the invalid
     * `COUNT=0`. Callers should fall back to an in-place ALL_EVENTS
     * update on the master rather than calling [splitRruleAtTime].
     *
     * Returns false for non-COUNT rules (UNTIL or unbounded never
     * produce COUNT=0) and for in-range pastCount values.
     */
    fun isDegenerateCountSplit(masterRrule: String, pastCount: Int): Boolean {
        val total = COUNT_REGEX.find(masterRrule)
            ?.groupValues?.get(1)?.toIntOrNull()
            ?: return false
        return pastCount <= 0 || pastCount >= total
    }

    /**
     * Build the new-series RRULE for a COUNT-bounded master split.
     *
     * The user's rrule is authoritative on bounds-shape: if the user
     * dropped COUNT (or replaced it with UNTIL), don't re-impose
     * COUNT on the new row. Total-preservation only applies when the
     * user kept master's COUNT shape — then we recompute COUNT to
     * the remaining instance count.
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

        // User changed bounds shape (added UNTIL, dropped COUNT, or
        // anything else that changes the COUNT presence). Honor user's
        // rrule verbatim — don't append COUNT.
        if (userCount == null) return userRrule

        // User kept COUNT but picked a different value. That's a
        // deliberate "I want exactly this many from here" — honor it
        // verbatim.
        if (userCount != masterCount) return userRrule

        // User kept master's COUNT shape and value. Apply
        // total-preservation: replace with newCount.
        return COUNT_REGEX.replace(userRrule, "COUNT=$newCount")
    }

    private val COUNT_REGEX = Regex("COUNT=(\\d+)")

    /**
     * Compare two RRULE strings by meaning rather than by bytes.
     *
     * The save-time scope sheet keys "did the user change recurrence?"
     * off this. A raw string compare misfires when the recurrence
     * picker re-emits a cosmetically different but semantically
     * identical rule — reordered parts, key/value case, surrounding
     * whitespace, a trailing `;`, an `RRULE:` prefix, or reordered
     * BYxxx list values. Those are not user changes, and treating them
     * as changes spuriously disables save options.
     *
     * Equivalence is cosmetic-only on purpose: it canonicalizes part
     * order, case, whitespace, and list-value order, but does NOT
     * equate genuinely different bounds shapes (e.g. COUNT vs UNTIL)
     * or values — those are real recurrence changes.
     *
     * Both-null is equal; null vs non-null is different.
     */
    fun rrulesEquivalent(a: String?, b: String?): Boolean {
        if (a == null || b == null) return a == b
        return canonicalizeRrule(a) == canonicalizeRrule(b)
    }

    /**
     * Reduce an RRULE to a canonical form for equivalence comparison:
     * strip an optional `RRULE:` prefix and whitespace, uppercase,
     * drop empty parts (handles trailing `;`), sort the `KEY=VALUE`
     * parts, and sort comma-separated list values within each part.
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
     * Format timestamp as RRULE UNTIL value.
     *
     * @param timestampMs Timestamp in epoch millis
     * @param isAllDay If true, returns date-only format (e.g., "20260115").
     *                 If false, returns datetime format (e.g., "20260115T100000Z").
     * @return Formatted UNTIL string
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
