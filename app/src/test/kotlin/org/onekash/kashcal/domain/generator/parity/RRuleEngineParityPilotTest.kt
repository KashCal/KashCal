package org.onekash.kashcal.domain.generator.parity

import org.junit.Test
import org.onekash.kashcal.domain.generator.parity.fixtures.CriticalBugCorpus
import org.onekash.kashcal.domain.generator.parity.fixtures.RfcExamplesCorpus

/**
 * Runs a pilot of 10 cases (the first 5 of Pool A and of Pool B) through the harness to check
 * whether the full corpus run will yield actionable data.
 *
 * If over 80% of divergences are Category B (RFC ambiguity) with no Category A bug, the full run
 * will likely produce more stalemate than findings. Read the printed summary before proceeding.
 *
 * The test never asserts, so it passes: divergences are data, as in
 * [RRuleEngineParityReportTest]. It prints the classification counts, a per-case breakdown and a
 * recommendation.
 *
 * There is no JUnit @Timeout: the harness bounds each engine call with its own wall-clock
 * timeout ([ParityHarnessRunner.TIMEOUT_SECONDS]), so a hung case becomes a Category C error
 * instead of hanging the method.
 */
class RRuleEngineParityPilotTest {

    private val pilotCases: List<RRuleCase> = buildList {
        addAll(RfcExamplesCorpus.cases.take(5))
        addAll(CriticalBugCorpus.cases.take(5))
    }

    @Test
    fun `pilot 10 cases and report on whether to proceed to full corpus`() {
        println("=".repeat(72))
        println("RRULE ENGINE PARITY HARNESS — PILOT (${pilotCases.size} cases)")
        println("=".repeat(72))
        val results = pilotCases.map { ParityHarnessRunner.runCase(it) }
        val counts = results.groupingBy { it.classification }.eachCount()
        val divergences = results.filter { it.parity !is ParityResult.BothAgree }
        val categoryA = results.count { it.classification == "A" }
        val categoryB = results.count { it.classification == "B" }
        val totalDivergences = divergences.size

        println()
        println("Summary:")
        counts.toSortedMap().forEach { (k, v) -> println("  classification $k: $v") }
        println()
        println("Per-case breakdown:")
        results.forEach { r ->
            println("  [${r.classification}] ${r.case.name}")
            when (val p = r.parity) {
                is ParityResult.Divergence -> {
                    if (p.libRecurOnly.isNotEmpty()) println("      lib-only: ${p.libRecurOnly}")
                    if (p.ical4jOnly.isNotEmpty()) println("      ical-only: ${p.ical4jOnly}")
                }
                is ParityResult.OneErrored -> {
                    println("      ${p.erroredEngine} errored: ${p.error.throwableClass}: ${p.error.message}")
                }
                is ParityResult.BothErrored -> {
                    println("      both errored: lib=${p.libRecurError.throwableClass}, ical=${p.ical4jError.throwableClass}")
                }
                is ParityResult.BothAgree -> {} // nothing to print
            }
        }
        println()

        // Recommendation
        if (totalDivergences == 0) {
            println("RECOMMENDATION: full corpus run will mostly agree — useful to surface Pool C/D edges.")
        } else {
            val pctB = categoryB * 100.0 / totalDivergences
            println("RECOMMENDATION logic: ${totalDivergences} divergence(s); categoryA=$categoryA; categoryB=$categoryB (${"%.0f".format(pctB)}% of divergences)")
            if (categoryA == 0 && pctB > 80.0) {
                println("RECOMMENDATION: consider documenting the parity stalemate and closing early.")
            } else {
                println("RECOMMENDATION: proceed to full corpus run — Category A bugs or actionable divergences present.")
            }
        }
        println("=".repeat(72))
    }
}
