package org.onekash.kashcal.domain.generator.parity

/**
 * Holds human-written classifications and notes for specific divergences, keyed by case name.
 *
 * [ParityHarnessRunner] classifies A/B/C/D by mechanical rules (listed there), which can't
 * express root cause. For example, a Pool C divergence where one engine follows the RFC and the
 * other silently drops a rule part is a real A bug, but it is tagged B because Pool C cases have
 * no `rfcExpected` to adjudicate.
 *
 * An entry replaces both the classification and the note for its case, with the root cause
 * from a closer reading of RFC 5545.
 *
 * When adding a case or re-running the corpus, check whether any divergence needs an entry
 * here. A divergence without one is reported with its case's `knownDivergenceReason` as the
 * note, or no note.
 */
object ParityAnalystNotes {

    /**
     * Replaces the mechanical classifier's output for one case; [ParityHarnessRunner.runCase]
     * applies it, so the report shows this [classification] and [note].
     */
    data class Override(val classification: String, val note: String)

    val overrides: Map<String, Override> = mapOf(

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
