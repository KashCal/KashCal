package org.onekash.kashcal.domain.generator.parity

/**
 * Engine-agnostic interface for RRULE expansion.
 *
 * Two concrete implementations drive the parity harness:
 * - [LibRecurParityEngine] wraps `LibRecurEngine.expandToTimestamps` (dmfs/lib-recur, app-side).
 * - [ICal4jParityEngine] wraps `icaldav-core.RRuleExpander.expand` (ical4j, library-side).
 */
interface RRuleEngine {
    val name: String
    fun expand(case: RRuleCase): ExpansionResult
}
