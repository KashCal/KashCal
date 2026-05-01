package org.onekash.kashcal.domain.generator.parity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.domain.generator.parity.fixtures.RfcExamplesCorpus

/**
 * Guards the BYHOUR/BYMINUTE/BYSECOND fix for icaldav-core.
 *
 * Specifically locks in RFC 5545 §3.8.5.3 example 36 as a three-way-agreement
 * case: both engines must match each other AND both must match the RFC-documented
 * occurrence list. If this ever fails, the fix in icaldav-core's
 * `RRule`/`RRuleExpander` has regressed — the parity report's aggregate
 * "Category A = 0" count would still pass (that's a range, not a per-case lock).
 *
 * Catches a specific subtle failure mode: if a future change emits
 * BYHOUR/BYMINUTE/BYSECOND in wrong order or swaps arguments, both engines
 * would match each other but miss the RFC — landing in Category B instead of D.
 * The aggregate count check wouldn't catch that; this per-case assertion does.
 */
class RfcExample36ByHourTest {

    @Test
    fun `RFC 5545 §3_8_5_3 example 36 — both engines match RFC`() {
        val case = RfcExamplesCorpus.cases.firstOrNull {
            it.name.startsWith("RFC 5545 §3.8.5.3 example 36:")
        }
        assertTrue(
            "Pool A example 36 must be present in RfcExamplesCorpus",
            case != null,
        )
        val rfcExpected = case!!.rfcExpected
        assertTrue("example 36 must carry rfcExpected", rfcExpected != null)

        // Sanity: 3 days × 8 hours × 3 minutes = 72 occurrences.
        // Checked before engine comparison to fail-fast with a clear message
        // if the transcription drifts.
        assertEquals(72, rfcExpected!!.size)

        val comparison = ParityHarnessRunner.rfcComparisonFor(case)
        assertTrue("RfcComparison must be producible for example 36", comparison != null)

        // Three-way agreement is the contract: both engines correct per RFC and
        // agree with each other. Any failure here means the icaldav-core BY*
        // wiring is broken or the RFC transcription has drifted.
        assertTrue("lib-recur must match RFC example 36", comparison!!.libRecurMatchesRfc)
        assertTrue("ical4j must match RFC example 36 (guards the BYHOUR fix)", comparison.ical4jMatchesRfc)
        assertTrue("engines must agree on RFC example 36", comparison.enginesAgree)
    }
}
