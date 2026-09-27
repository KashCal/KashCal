package org.onekash.kashcal.domain.generator.parity

import org.onekash.kashcal.domain.generator.IcalDavRRuleEngine

/**
 * Runs the production [IcalDavRRuleEngine] (ical4j) for the parity harness, so the harness and
 * OccurrenceGenerator exercise the same code path and the harness has no adapter of its own to
 * diverge. A throw is returned as [ExpansionResult.Error].
 */
object ICal4jParityEngine : RRuleEngine {
    override val name: String = "ical4j"

    override fun expand(case: RRuleCase): ExpansionResult {
        return try {
            val timestamps = IcalDavRRuleEngine.expandToTimestamps(
                rrule = case.rrule,
                dtstartMs = case.dtstartMs,
                rangeStartMs = case.rangeStartMs,
                rangeEndMs = case.rangeEndMs,
                timezone = case.timezone,
                isAllDay = case.isAllDay,
                rdateStrings = case.rdateStrings,
                exdateStrings = case.exdateStrings,
            )
            ExpansionResult.Success(timestamps)
        } catch (e: Throwable) {
            ExpansionResult.Error(
                message = e.message ?: "",
                throwableClass = e::class.java.simpleName,
            )
        }
    }
}
