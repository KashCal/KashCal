package org.onekash.kashcal.domain.generator.parity

import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * Differential fuzz oracle for RRULE expansion.
 *
 * ical4j is the PRODUCTION engine (via [org.onekash.kashcal.domain.generator.IcalDavRRuleEngine]);
 * lib-recur is retired from production but kept as an independent second
 * implementation precisely so it can serve as a differential reference here.
 *
 * Feeds randomly-generated, well-formed [RRuleCase]s (see [RandomRRuleGenerator])
 * through both engines via the existing [ParityHarnessRunner] and treats any
 * *correctness-relevant* divergence as a finding:
 *  - [ParityResult.Divergence] — both engines succeeded but produced different
 *    occurrence sets.
 *  - asymmetric [ParityResult.OneErrored] — one engine expanded a well-formed
 *    rule while the other threw.
 *
 * Because the generator only emits DTSTART-synchronized rules (RFC 5545 §3.8.5.3
 * defined space), a divergence means the two engines disagree on input the spec
 * *does* define — a lead to triage against the RFC. It is not automatically a
 * production bug (the RFC may still be ambiguous on the specific rule), but in
 * this input space a divergence is the strongest possible signal that the
 * production engine may be wrong, so the test fails loudly and prints the case.
 *
 * [ParityResult.BothAgree] and [ParityResult.BothErrored] are not findings.
 *
 * This complements, rather than duplicates, the Jazzer `rrule-*` harnesses (in
 * the gitignored `fuzz/` workspace): those check a single engine never throws or
 * runs away; this checks that two independent engines *agree on the answer*.
 *
 * Reproducibility: the seed and iteration count are fixed constants, overridable
 * via `-Dfuzz.rrule.seed=` / `-Dfuzz.rrule.iterations=` so a nightly job can run
 * far more iterations. A failure prints the exact `RRuleCase` so it can be
 * promoted into [org.onekash.kashcal.domain.generator.parity.fixtures.AdversarialCorpus]
 * as a permanent regression test.
 */
class RRuleDifferentialFuzzTest {

    private val seed: Long =
        System.getProperty("fuzz.rrule.seed")?.toLongOrNull() ?: DEFAULT_SEED
    private val iterations: Int =
        System.getProperty("fuzz.rrule.iterations")?.toIntOrNull() ?: DEFAULT_ITERATIONS

    @Test
    fun `randomly generated well-formed rrules expand identically across both engines`() {
        val generator = RandomRRuleGenerator(Random(seed))
        val findings = mutableListOf<String>()

        for (i in 0 until iterations) {
            val case = generator.nextCase(i)
            val result = ParityHarnessRunner.runCase(case)
            describeFinding(case, result.parity)?.let { findings += it }
        }

        println("RRULE differential fuzz: ran $iterations cases (seed=$seed), " +
            "${findings.size} finding(s).")

        if (findings.isNotEmpty()) {
            fail(
                "Differential fuzzing found ${findings.size} engine divergence(s) " +
                    "(seed=$seed). Reproduce with -Dfuzz.rrule.seed=$seed. " +
                    "Promote each into AdversarialCorpus:\n\n" +
                    findings.joinToString("\n\n"),
            )
        }
    }

    /** Returns a human-readable + reproducible description if [parity] is a finding, else null. */
    private fun describeFinding(case: RRuleCase, parity: ParityResult): String? = when (parity) {
        is ParityResult.BothAgree -> null
        is ParityResult.BothErrored -> null // both reject: not a correctness divergence
        is ParityResult.Divergence -> buildString {
            appendLine("DIVERGENCE — rrule=${case.rrule}")
            appendLine("  dtstartMs=${case.dtstartMs} tz=${case.timezone}")
            appendLine("  lib-recur only: ${parity.libRecurOnly}")
            appendLine("  ical4j only:    ${parity.ical4jOnly}")
            append("  common count:   ${parity.common.size}")
        }
        is ParityResult.OneErrored -> buildString {
            appendLine("ONE-ERRORED — rrule=${case.rrule}")
            appendLine("  dtstartMs=${case.dtstartMs} tz=${case.timezone}")
            append("  ${parity.erroredEngine} threw ${parity.error.throwableClass}: " +
                "${parity.error.message} (other engine returned " +
                "${parity.otherResult.timestampsMs.size} occurrences)")
        }
    }

    private companion object {
        const val DEFAULT_SEED = 20260715L
        const val DEFAULT_ITERATIONS = 2000
    }
}
