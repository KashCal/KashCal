package org.onekash.kashcal.domain.generator.parity

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Writes a human-readable markdown report of parity results, one section per case.
 *
 * Output format:
 *
 *     # RRULE Engine Parity Report
 *
 *     ## Summary
 *     - Total cases: N
 *     - Both engines agree: X (Y%)
 *     - Divergences: Z (by pool)
 *     - Engine errors: A (by engine)
 *
 *     ## Pool A: RFC 5545 §3.8.5.3 examples
 *     ### Case name
 *     - Inputs: ...
 *     - RFC expected: [...]
 *     - lib-recur actual: [...]
 *     - ical4j actual: [...]
 *     - lib-recur matches RFC: true/false
 *     - ical4j matches RFC: true/false
 *     - Classification: A / B / C / D
 */
object ParityReportWriter {

    private val tsFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    fun write(
        results: List<CaseResult>,
        rfcResults: List<RfcComparison>,
    ): String = buildString {
        appendLine("# RRULE Engine Parity Report")
        appendLine()
        appendLine(writeSummary(results, rfcResults))
        appendLine()
        appendLine(writeByPool(results, rfcResults))
    }

    private fun writeSummary(
        results: List<CaseResult>,
        rfcResults: List<RfcComparison>,
    ): String = buildString {
        val total = results.size
        val agreeCount = results.count { it.parity is ParityResult.BothAgree }
        val divergenceCount = results.count { it.parity is ParityResult.Divergence }
        val oneErroredCount = results.count { it.parity is ParityResult.OneErrored }
        val bothErroredCount = results.count { it.parity is ParityResult.BothErrored }
        val rfcAgreeBoth = rfcResults.count { it.libRecurMatchesRfc && it.ical4jMatchesRfc }
        val rfcLibOnly = rfcResults.count { it.libRecurMatchesRfc && !it.ical4jMatchesRfc }
        val rfcIcalOnly = rfcResults.count { !it.libRecurMatchesRfc && it.ical4jMatchesRfc }
        val rfcNeither = rfcResults.count { !it.libRecurMatchesRfc && !it.ical4jMatchesRfc }

        appendLine("## Summary")
        appendLine()
        appendLine("- Total cases: $total")
        appendLine("- Both engines agree: $agreeCount (${percent(agreeCount, total)})")
        appendLine("- Divergences: $divergenceCount (${percent(divergenceCount, total)})")
        appendLine("- One engine errored: $oneErroredCount")
        appendLine("- Both engines errored: $bothErroredCount")
        if (rfcResults.isNotEmpty()) {
            appendLine()
            appendLine("### RFC 5545 §3.8.5.3 compliance (Pool A only, ${rfcResults.size} cases)")
            appendLine("- Both engines match RFC: $rfcAgreeBoth")
            appendLine("- Only lib-recur matches RFC: $rfcLibOnly")
            appendLine("- Only ical4j matches RFC: $rfcIcalOnly")
            appendLine("- Neither matches RFC: $rfcNeither")
        }
    }

    private fun writeByPool(
        results: List<CaseResult>,
        rfcResults: List<RfcComparison>,
    ): String = buildString {
        val byPool = results.groupBy { it.case.category }
        for ((pool, poolResults) in byPool.toSortedMap()) {
            appendLine("## Pool: $pool (${poolResults.size} cases)")
            appendLine()
            for (result in poolResults) {
                appendLine(writeCase(result, rfcResults.firstOrNull { it.caseName == result.case.name }))
            }
        }
    }

    private fun writeCase(result: CaseResult, rfc: RfcComparison?): String = buildString {
        appendLine("### ${result.case.name}")
        appendLine()
        appendLine("- RRULE: `${result.case.rrule}`")
        appendLine("- DTSTART: ${formatTs(result.case.dtstartMs)} (ms=${result.case.dtstartMs}, TZID=${result.case.timezone ?: "(floating)"}, isAllDay=${result.case.isAllDay})")
        if (result.case.rdateStrings != null) appendLine("- RDATE: `${result.case.rdateStrings}`")
        if (result.case.exdateStrings != null) appendLine("- EXDATE: `${result.case.exdateStrings}`")
        appendLine("- Range: ${formatTs(result.case.rangeStartMs)} .. ${formatTs(result.case.rangeEndMs)}")
        if (rfc != null) {
            appendLine("- RFC expected: ${formatTimestamps(rfc.rfcExpected)}")
            appendLine("- lib-recur matches RFC: ${rfc.libRecurMatchesRfc}")
            appendLine("- ical4j matches RFC: ${rfc.ical4jMatchesRfc}")
        }
        when (val p = result.parity) {
            is ParityResult.BothAgree -> {
                appendLine("- Result: **AGREE** — both engines returned ${p.timestampsMs.size} occurrences: ${formatTimestamps(p.timestampsMs)}")
            }
            is ParityResult.Divergence -> {
                appendLine("- Result: **DIVERGE**")
                appendLine("  - Common: ${formatTimestamps(p.common)}")
                appendLine("  - lib-recur only: ${formatTimestamps(p.libRecurOnly)}")
                appendLine("  - ical4j only: ${formatTimestamps(p.ical4jOnly)}")
            }
            is ParityResult.OneErrored -> {
                appendLine("- Result: **ONE ERRORED**")
                appendLine("  - ${p.erroredEngine}: ${p.error.throwableClass}: ${p.error.message}")
                appendLine("  - ${p.otherEngine}: ${formatTimestamps(p.otherResult.timestampsMs)}")
            }
            is ParityResult.BothErrored -> {
                appendLine("- Result: **BOTH ERRORED**")
                appendLine("  - lib-recur: ${p.libRecurError.throwableClass}: ${p.libRecurError.message}")
                appendLine("  - ical4j: ${p.ical4jError.throwableClass}: ${p.ical4jError.message}")
            }
        }
        appendLine("- Classification: ${result.classification}")
        if (result.case.knownDivergenceReason != null) {
            appendLine("- Known divergence reason: ${result.case.knownDivergenceReason}")
        }
        if (result.analystNote != null) {
            appendLine("- Analyst note: ${result.analystNote}")
        }
        appendLine()
    }

    private fun formatTs(ms: Long): String = tsFormatter.format(Instant.ofEpochMilli(ms))

    private fun formatTimestamps(ts: List<Long>): String {
        if (ts.isEmpty()) return "(empty)"
        if (ts.size <= 8) return ts.joinToString(", ") { formatTs(it) }
        return ts.take(3).joinToString(", ") { formatTs(it) } +
            " … (${ts.size - 6} more) … " +
            ts.takeLast(3).joinToString(", ") { formatTs(it) }
    }

    private fun percent(n: Int, total: Int): String =
        if (total == 0) "0%" else "%.1f%%".format(n * 100.0 / total)
}

/**
 * A single case result with its comparison and classification.
 *
 * @property case The input case.
 * @property parity Result of running both engines and comparing.
 * @property classification Divergence category ("A" clear bug, "B" RFC ambiguity,
 *   "C" scope gap, "D" identical). Set to "D" for BothAgree by default.
 * @property analystNote Optional free-form note added during analysis.
 */
data class CaseResult(
    val case: RRuleCase,
    val parity: ParityResult,
    val classification: String,
    val analystNote: String? = null,
)
