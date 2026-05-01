package org.onekash.kashcal.domain.generator.parity

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Runs a single [RRuleCase] through both engines with a per-case wall-clock
 * timeout, returning a [CaseResult] with computed classification.
 *
 * A wall-clock timeout is required because adversarial cases (unbounded
 * SECONDLY expansions, infinite recurrences against open ranges) can hang
 * an engine indefinitely. Timeouts surface as [ExpansionResult.Error] with
 * `throwableClass="TimeoutException"` so they are classified as Category C
 * (scope gap) rather than failing the whole run.
 *
 * Classification rules:
 * - **D** (identical): both engines succeed and produce the same set of timestamps.
 * - **A** (clear bug per RFC): Pool A case, both engines succeed, exactly one
 *   matches the RFC ground truth. The engine that disagrees is wrong.
 * - **B** (RFC ambiguity or engine-level divergence without authority): any
 *   divergence where no RFC ground truth is available (non-Pool-A), or where
 *   Pool A ground truth isn't dispositive (both engines match, both engines
 *   disagree but agree with each other).
 * - **C** (scope gap): one or both engines errored or timed out.
 */
object ParityHarnessRunner {

    /** Wall-clock timeout per engine call. */
    const val TIMEOUT_SECONDS: Long = 10L

    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "parity-engine").apply { isDaemon = true }
    }

    fun runCase(case: RRuleCase): CaseResult {
        val lib = expandWithTimeout(LibRecurParityEngine, case)
        val ical = expandWithTimeout(ICal4jParityEngine, case)
        val parity = ParityComparator.compare(lib, ical)
        val mechanicalClassification = classify(case, lib, ical, parity)
        val analystOverride = ParityAnalystNotes.overrides[case.name]
        val classification = analystOverride?.classification ?: mechanicalClassification
        val analystNote = analystOverride?.note ?: analystNoteFor(case, parity, classification)
        return CaseResult(
            case = case,
            parity = parity,
            classification = classification,
            analystNote = analystNote,
        )
    }

    fun rfcComparisonFor(case: RRuleCase): RfcComparison? {
        val rfc = case.rfcExpected ?: return null
        val lib = expandWithTimeout(LibRecurParityEngine, case)
        val ical = expandWithTimeout(ICal4jParityEngine, case)
        return ParityComparator.compareAgainstRfc(case.name, rfc, lib, ical)
    }

    private fun expandWithTimeout(engine: RRuleEngine, case: RRuleCase): ExpansionResult {
        val future = executor.submit(Callable { engine.expand(case) })
        return try {
            future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            ExpansionResult.Error(
                message = "exceeded $TIMEOUT_SECONDS-second wall-clock timeout",
                throwableClass = "TimeoutException",
            )
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            ExpansionResult.Error(
                message = cause.message ?: "",
                throwableClass = cause::class.java.simpleName,
            )
        }
    }

    private fun classify(
        case: RRuleCase,
        lib: ExpansionResult,
        ical: ExpansionResult,
        parity: ParityResult,
    ): String = when (parity) {
        is ParityResult.BothAgree -> "D"
        is ParityResult.BothErrored -> "C"
        is ParityResult.OneErrored -> "C"
        is ParityResult.Divergence -> {
            // Pool A: RFC is authority — use it to pick A vs B.
            val rfc = case.rfcExpected
            if (rfc != null && lib is ExpansionResult.Success && ical is ExpansionResult.Success) {
                val rfcSet = rfc.toSet()
                val libMatches = lib.timestampsMs.toSet() == rfcSet
                val icalMatches = ical.timestampsMs.toSet() == rfcSet
                when {
                    libMatches xor icalMatches -> "A" // exactly one matches RFC — other is wrong
                    else -> "B" // neither matches (shared deviation or genuine ambiguity)
                }
            } else {
                // Non-Pool-A: no authority, classify as ambiguity-or-interpretation.
                "B"
            }
        }
    }

    private fun analystNoteFor(
        case: RRuleCase,
        parity: ParityResult,
        classification: String,
    ): String? {
        if (parity is ParityResult.BothAgree) return null
        // Preserve any pre-declared knownDivergenceReason as the starting note.
        return case.knownDivergenceReason?.let { "[pre-classified] $it" }
    }
}
