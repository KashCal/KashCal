package org.onekash.kashcal.domain.generator.parity

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.onekash.kashcal.domain.generator.parity.fixtures.AdversarialCorpus
import org.onekash.kashcal.domain.generator.parity.fixtures.CriticalBugCorpus
import org.onekash.kashcal.domain.generator.parity.fixtures.ExistingTestsCorpus
import org.onekash.kashcal.domain.generator.parity.fixtures.RfcExamplesCorpus

/**
 * Detects engine drift: one test per engine runs the four corpus pools and compares each case's
 * output with that engine's checked-in baseline JSON.
 *
 * Fails when a case's timestamps or error text differ from the baseline, when a corpus case is
 * missing from the baseline or a baseline case is no longer in the corpus, or when the baseline
 * resource is missing or names another engine. For an intended behavior change, run
 * [RRuleEngineParityReportTest] to regenerate the baselines and commit the JSON; its `git diff`
 * shows the change per case. Unlike the report test, this one fails the unit test run.
 */
class RRuleEngineBaselineTest {

    private val allCases: List<RRuleCase> = buildList {
        addAll(RfcExamplesCorpus.cases)
        addAll(CriticalBugCorpus.cases)
        addAll(ExistingTestsCorpus.cases)
        addAll(AdversarialCorpus.cases)
    }

    @Test
    fun `lib-recur output matches checked-in baseline`() {
        verifyEngineAgainstBaseline(
            engineKey = "librecur",
            engineName = "lib-recur",
            engine = LibRecurParityEngine,
        )
    }

    @Test
    fun `ical4j output matches checked-in baseline`() {
        verifyEngineAgainstBaseline(
            engineKey = "ical4j",
            engineName = "ical4j",
            engine = ICal4jParityEngine,
        )
    }

    private fun verifyEngineAgainstBaseline(
        engineKey: String,
        engineName: String,
        engine: RRuleEngine,
    ) {
        val resourceName = "parity/baseline-$engineKey.json"
        val baselineText = javaClass.classLoader!!
            .getResourceAsStream(resourceName)
            ?.bufferedReader()
            ?.readText()
            ?: fail("missing baseline resource: $resourceName — run RRuleEngineParityReportTest to generate").let { return }

        val baseline = ParityBaselineCodec.decode(baselineText)
        assertEquals("baseline engine name mismatch", engineName, baseline.engine)

        val baselineByName = baseline.cases.associateBy { it.name }
        val drift = mutableListOf<String>()
        val missing = mutableListOf<String>()
        val extra = baselineByName.keys.toMutableSet()

        for (case in allCases) {
            extra.remove(case.name)
            val expected = baselineByName[case.name]
            if (expected == null) {
                missing.add(case.name)
                continue
            }

            val actual = engine.expand(case)
            val actualBaseline = when (actual) {
                is ExpansionResult.Success -> ParityBaselineCodec.BaselineCase(
                    name = case.name,
                    timestamps = actual.timestampsMs.sorted(),
                    error = null,
                )
                is ExpansionResult.Error -> ParityBaselineCodec.BaselineCase(
                    name = case.name,
                    timestamps = emptyList(),
                    error = "${actual.throwableClass}: ${actual.message}",
                )
            }

            if (actualBaseline != expected) {
                drift.add(
                    buildString {
                        append("DRIFT: ${case.name}\n")
                        append("  baseline.timestamps=${expected.timestamps.take(5)}${if (expected.timestamps.size > 5) "…(${expected.timestamps.size})" else ""}\n")
                        append("  actual.timestamps  =${actualBaseline.timestamps.take(5)}${if (actualBaseline.timestamps.size > 5) "…(${actualBaseline.timestamps.size})" else ""}\n")
                        if (expected.error != actualBaseline.error) {
                            append("  baseline.error=${expected.error}\n")
                            append("  actual.error  =${actualBaseline.error}\n")
                        }
                    }
                )
            }
        }

        val messages = buildList {
            if (drift.isNotEmpty()) {
                add("${drift.size} drift(s) detected for $engineName — if intentional, " +
                    "run RRuleEngineParityReportTest to regenerate baselines.")
                addAll(drift)
            }
            if (missing.isNotEmpty()) {
                add("${missing.size} case(s) missing from $engineName baseline: $missing")
            }
            if (extra.isNotEmpty()) {
                add("${extra.size} case(s) in $engineName baseline no longer in corpus: $extra")
            }
        }
        if (messages.isNotEmpty()) {
            fail(messages.joinToString("\n"))
        }
    }
}
