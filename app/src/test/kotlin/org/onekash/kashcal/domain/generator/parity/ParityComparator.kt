package org.onekash.kashcal.domain.generator.parity

/**
 * Compares outputs of two RRULE engines for a single case, producing a typed result
 * that can be classified and reported.
 */
sealed class ParityResult {
    /** Both engines succeeded and produced the same sorted timestamps. */
    data class BothAgree(val timestampsMs: List<Long>) : ParityResult()

    /** Both engines succeeded but returned different timestamps. */
    data class Divergence(
        val libRecurOnly: List<Long>,
        val ical4jOnly: List<Long>,
        val common: List<Long>,
    ) : ParityResult()

    /** One engine errored, the other succeeded. */
    data class OneErrored(
        val erroredEngine: String,
        val error: ExpansionResult.Error,
        val otherEngine: String,
        val otherResult: ExpansionResult.Success,
    ) : ParityResult()

    /** Both engines errored. Errors may be equal or different. */
    data class BothErrored(
        val libRecurError: ExpansionResult.Error,
        val ical4jError: ExpansionResult.Error,
    ) : ParityResult()
}

/**
 * RFC-ground-truth comparison. Pool A cases carry [RRuleCase.rfcExpected]; this
 * compares each engine to the spec independently, so we can see which engine (if any)
 * matches the spec and whether they agree with each other too.
 */
data class RfcComparison(
    val caseName: String,
    val rfcExpected: List<Long>,
    val libRecurActual: ExpansionResult,
    val ical4jActual: ExpansionResult,
    val libRecurMatchesRfc: Boolean,
    val ical4jMatchesRfc: Boolean,
    val enginesAgree: Boolean,
)

object ParityComparator {

    /** Compare the two engine outputs for a single case (no RFC baseline). */
    fun compare(
        libRecur: ExpansionResult,
        ical4j: ExpansionResult,
    ): ParityResult {
        return when {
            libRecur is ExpansionResult.Error && ical4j is ExpansionResult.Error ->
                ParityResult.BothErrored(libRecur, ical4j)
            libRecur is ExpansionResult.Error && ical4j is ExpansionResult.Success ->
                ParityResult.OneErrored(
                    erroredEngine = "lib-recur",
                    error = libRecur,
                    otherEngine = "ical4j",
                    otherResult = ical4j,
                )
            libRecur is ExpansionResult.Success && ical4j is ExpansionResult.Error ->
                ParityResult.OneErrored(
                    erroredEngine = "ical4j",
                    error = ical4j,
                    otherEngine = "lib-recur",
                    otherResult = libRecur,
                )
            libRecur is ExpansionResult.Success && ical4j is ExpansionResult.Success -> {
                val libSet = libRecur.timestampsMs.toSet()
                val icalSet = ical4j.timestampsMs.toSet()
                if (libSet == icalSet) {
                    ParityResult.BothAgree(libRecur.timestampsMs.sorted())
                } else {
                    ParityResult.Divergence(
                        libRecurOnly = (libSet - icalSet).sorted(),
                        ical4jOnly = (icalSet - libSet).sorted(),
                        common = (libSet intersect icalSet).sorted(),
                    )
                }
            }
            else -> error("unreachable")
        }
    }

    /** Compare both engine outputs against an RFC-specified expected output. */
    fun compareAgainstRfc(
        caseName: String,
        rfcExpected: List<Long>,
        libRecur: ExpansionResult,
        ical4j: ExpansionResult,
    ): RfcComparison {
        val rfcSet = rfcExpected.toSet()
        val libMatchesRfc = libRecur is ExpansionResult.Success &&
            libRecur.timestampsMs.toSet() == rfcSet
        val icalMatchesRfc = ical4j is ExpansionResult.Success &&
            ical4j.timestampsMs.toSet() == rfcSet
        val enginesAgree = libRecur is ExpansionResult.Success &&
            ical4j is ExpansionResult.Success &&
            libRecur.timestampsMs.toSet() == ical4j.timestampsMs.toSet()
        return RfcComparison(
            caseName = caseName,
            rfcExpected = rfcExpected,
            libRecurActual = libRecur,
            ical4jActual = ical4j,
            libRecurMatchesRfc = libMatchesRfc,
            ical4jMatchesRfc = icalMatchesRfc,
            enginesAgree = enginesAgree,
        )
    }
}
