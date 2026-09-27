package org.onekash.kashcal.domain.generator.parity

/**
 * Describes one RRULE expansion case, run through both engines with identical inputs.
 *
 * @property name Case identifier. Pool A cases must match `RFC 5545 §3\.8\.5\.3 example \d+.*`
 *   (`ParityCorpusValidationTest`).
 * @property category The corpus pool ("rfc", "critical", "existing", "adversarial"), or the
 *   source of a generated case ("fuzz", "diagnostic", "test").
 * @property rrule RFC 5545 RRULE value (without "RRULE:" prefix).
 * @property dtstartMs Master event DTSTART as epoch ms.
 * @property timezone IANA TZID, or null for floating/local.
 * @property isAllDay Whether DTSTART is a DATE (not DATE-TIME).
 * @property rdateStrings RDATE CSV in mixed format, or null.
 * @property exdateStrings EXDATE CSV in mixed format, or null.
 * @property rangeStartMs Expansion window start, inclusive.
 * @property rangeEndMs Expansion window end, exclusive.
 * @property rfcExpected For Pool A cases, the RFC-documented occurrences, sorted ascending,
 *   within [rangeStartMs, rangeEndMs). Null otherwise.
 * @property knownDivergenceReason Why a divergence is expected (RFC ambiguity, known engine
 *   bug), if classified ahead of time. Without a [ParityAnalystNotes] override, the runner uses
 *   it as the analyst note when the engines don't agree.
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
