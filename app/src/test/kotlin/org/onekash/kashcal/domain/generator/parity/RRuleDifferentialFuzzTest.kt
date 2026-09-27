package org.onekash.kashcal.domain.generator.parity

import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * Fuzzes RRULE expansion differentially: the production ical4j engine
 * ([org.onekash.kashcal.domain.generator.IcalDavRRuleEngine]) against lib-recur, retired from
 * production and kept in the tests as an independent reference.
 *
 * Runs well-formed [RRuleCase]s from [RandomRRuleGenerator] through [ParityHarnessRunner] and
 * fails on any finding:
 *  - [ParityResult.Divergence]: both engines succeeded with different occurrence sets.
 *    LibRecurEngine returns an empty list on failure, so a lib-recur error lands here, or in
 *    BothAgree when ical4j is also empty.
 *  - [ParityResult.OneErrored]: one engine expanded the rule while the other threw or timed out.
 *
 * [ParityResult.BothAgree] and [ParityResult.BothErrored] are not findings.
 *
 * The generator emits only DTSTART-synchronized rules, the space RFC 5545 §3.8.5.3 defines, so a
 * divergence is a disagreement on input the spec defines: a lead to triage against the RFC, not
 * automatically a production bug (the RFC may be ambiguous on that rule).
 *
 * The Jazzer `rrule-*` harnesses in the gitignored `fuzz/` workspace check that one engine never
 * throws or runs away; this checks that two engines agree on the answer.
 *
 * The seed and iteration count are fixed, overridable with `-Dfuzz.rrule.seed=` and
 * `-Dfuzz.rrule.iterations=` for a longer run. A failure prints each case to promote into
 * [org.onekash.kashcal.domain.generator.parity.fixtures.AdversarialCorpus] as a regression test.
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

    /** Describes [parity] for reproduction if it is a finding, else returns null. */
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
