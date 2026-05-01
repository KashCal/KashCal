package org.onekash.kashcal.domain.generator.parity

/**
 * Single RRULE expansion test case. Drives both engines with identical inputs.
 *
 * @property name Self-documenting case identifier. Pool A cases MUST match
 *   `RFC 5545 §3\.8\.5\.3 example \d+.*` to enforce citation.
 * @property category Which pool this case belongs to ("rfc", "critical", "existing", "adversarial").
 * @property rrule RFC 5545 RRULE value (without "RRULE:" prefix).
 * @property dtstartMs Master event DTSTART as epoch ms.
 * @property timezone IANA TZID, or null for floating/local.
 * @property isAllDay Whether DTSTART is a DATE (not DATE-TIME).
 * @property rdateStrings RDATE CSV in mixed format, or null.
 * @property exdateStrings EXDATE CSV in mixed format, or null.
 * @property rangeStartMs Expansion window start, inclusive.
 * @property rangeEndMs Expansion window end, exclusive.
 * @property rfcExpected For Pool A cases: spec-documented expected occurrences,
 *   sorted ascending, within [rangeStartMs, rangeEndMs). Null otherwise.
 * @property knownDivergenceReason If a divergence is expected and classified
 *   ahead of time (RFC ambiguity, known engine bug), cite the reason here.
 */
data class RRuleCase(
    val name: String,
    val category: String,
    val rrule: String,
    val dtstartMs: Long,
    val timezone: String?,
    val isAllDay: Boolean,
    val rdateStrings: String?,
    val exdateStrings: String?,
    val rangeStartMs: Long,
    val rangeEndMs: Long,
    val rfcExpected: List<Long>? = null,
    val knownDivergenceReason: String? = null,
)

/** Result of running an [RRuleCase] through a single engine. */
sealed class ExpansionResult {
    data class Success(val timestampsMs: List<Long>) : ExpansionResult()
    data class Error(val message: String, val throwableClass: String) : ExpansionResult()
}
