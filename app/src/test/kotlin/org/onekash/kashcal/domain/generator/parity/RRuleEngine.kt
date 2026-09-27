package org.onekash.kashcal.domain.generator.parity

/**
 * Expands an [RRuleCase] with one RRULE engine for the parity harness.
 *
 * - [LibRecurParityEngine] wraps the test-only `LibRecurEngine.expandToTimestamps` (lib-recur).
 * - [ICal4jParityEngine] wraps the production `IcalDavRRuleEngine.expandToTimestamps` (ical4j).
 */
interface RRuleEngine {
    val name: String
    fun expand(case: RRuleCase): ExpansionResult
}
