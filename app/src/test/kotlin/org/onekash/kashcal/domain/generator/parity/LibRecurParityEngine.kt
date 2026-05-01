package org.onekash.kashcal.domain.generator.parity

import org.onekash.kashcal.domain.generator.LibRecurEngine

/**
 * Drives LibRecurEngine (dmfs/lib-recur backend) for the parity harness.
 * Catches exceptions and surfaces them as [ExpansionResult.Error] rather than propagating.
 */
object LibRecurParityEngine : RRuleEngine {
    override val name: String = "lib-recur"

    override fun expand(case: RRuleCase): ExpansionResult {
        return try {
            val timestamps = LibRecurEngine.expandToTimestamps(
                rrule = case.rrule,
                dtstartMs = case.dtstartMs,
                rangeStartMs = case.rangeStartMs,
                rangeEndMs = case.rangeEndMs,
                timezone = case.timezone,
                isAllDay = case.isAllDay,
                rdateStrings = case.rdateStrings,
                exdateStrings = case.exdateStrings,
            )
            // LibRecurEngine already catches internally and returns empty on error — but
            // we wrap here anyway in case future versions throw.
            ExpansionResult.Success(timestamps)
        } catch (e: Throwable) {
            ExpansionResult.Error(
                message = e.message ?: "",
                throwableClass = e::class.java.simpleName,
            )
        }
    }
}
