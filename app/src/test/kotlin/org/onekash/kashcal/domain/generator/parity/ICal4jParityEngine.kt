package org.onekash.kashcal.domain.generator.parity

import org.onekash.kashcal.domain.generator.IcalDavRRuleEngine

/**
 * Drives icaldav-core's RRuleExpander (ical4j backend) for the parity harness.
 *
 * Post-migration: delegates directly to the PRODUCTION [IcalDavRRuleEngine] so
 * the harness and OccurrenceGenerator exercise the same code path. This
 * eliminates the "harness has its own parallel adapter" divergence source that
 * the pre-migration design carried.
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
