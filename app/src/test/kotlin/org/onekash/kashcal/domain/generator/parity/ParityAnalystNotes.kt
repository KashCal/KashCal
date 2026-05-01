package org.onekash.kashcal.domain.generator.parity

/**
 * Human-authored analyst notes for specific divergences, keyed by case name.
 *
 * The [ParityHarnessRunner]'s automatic classifier handles A/B/C/D based on
 * mechanical rules (Pool A + exactly-one-matches-RFC → A; any error → C; etc).
 * Those rules can't express root-cause reasoning — for instance, a Pool C
 * divergence where one engine clearly follows the RFC while the other silently
 * drops a rule part is a real Category A bug but gets mechanically tagged B
 * because there's no `rfcExpected` on Pool C cases to adjudicate.
 *
 * This map overrides both classification and analyst note for specific
 * named cases where a closer reading of RFC 5545 resolves the ambiguity.
 * Every override here is a promotion from B to A (authoritative bug call)
 * or a re-classification with a detailed root cause.
 *
 * Maintenance: when adding a new case or re-running the corpus, check
 * whether any divergence needs a note added here. Un-overridden divergences
 * appear in the report with whatever note the case's `knownDivergenceReason`
 * supplied (or no note at all).
 */
object ParityAnalystNotes {

    /**
     * Per-case override. If present, the report writer uses this
     * [classification] and [note] in place of the mechanical classifier's
     * output for this case.
     */
    data class Override(val classification: String, val note: String)

    val overrides: Map<String, Override> = mapOf(

        "RFC 5545 §3.8.5.3 example 33: every 3 hours on Sep 2 1997 until 17Z" to Override(
            classification = "B",
            note = "RFC internal inconsistency. The example prints 09:00, 12:00, 15:00 EDT " +
                "(= 13Z, 16Z, 19Z) but UNTIL=19970902T170000Z is 13:00 EDT — by a strict " +
                "UTC-comparison reading of UNTIL, 15:00 EDT (19Z) is outside the window. " +
                "lib-recur honors the literal UNTIL (2 occurrences). ical4j follows the " +
                "RFC's printed text (3 occurrences). Both are defensible; the RFC example " +
                "is faulty. Not a migration blocker.",
        ),

        "adversarial: DAILY at 01:30 landing on DST fall-back (America/New_York)" to Override(
            classification = "B",
            note = "Nov 2 2025 01:30 America/New_York is ambiguous — the local time occurs " +
                "twice (once EDT=05:30Z, once EST=06:30Z). lib-recur chose EST (06:30Z); " +
                "ical4j chose EDT (05:30Z). Both are defensible readings of RFC 5545 " +
                "§3.3.5 (which is silent on fold-back ambiguity). Practically, the " +
                "difference is a single occurrence one hour apart, on one day per year. " +
                "Not a migration blocker.",
        ),

        "adversarial: BYMONTH=13 (invalid month)" to Override(
            classification = "C",
            note = "lib-recur tolerates BYMONTH=13 by treating it as a yearly anniversary " +
                "at DTSTART's month (returns 3 stamps). IcalDavRRuleEngine catches ical4j's " +
                "IllegalArgumentException and returns []. Both behaviors are defensible for " +
                "RFC-invalid input; no user impact since the app doesn't allow authoring " +
                "RRULEs with invalid month values.",
        ),
    )
}
