package org.onekash.kashcal.domain.generator.parity

import org.onekash.kashcal.domain.generator.LibRecurEngine

/**
 * Runs the test-only [LibRecurEngine] (lib-recur) oracle for the parity harness. A throw is
 * returned as [ExpansionResult.Error].
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
            // LibRecurEngine catches its own exceptions and returns an empty list, so its
            // failures arrive here as an empty Success; this catch is a backstop.
            ExpansionResult.Success(timestamps)
        } catch (e: Throwable) {
            ExpansionResult.Error(
                message = e.message ?: "",
                throwableClass = e::class.java.simpleName,
            )
        }
    }
}
