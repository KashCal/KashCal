package org.onekash.kashcal.domain.generator.parity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.domain.generator.parity.fixtures.RfcExamplesCorpus

/**
 * Locks RFC 5545 §3.8.5.3 example 36 (BYHOUR with BYMINUTE) as a three-way agreement: both
 * engines match each other and the RFC's occurrence list. A failure means icaldav-core's
 * BYHOUR/BYMINUTE/BYSECOND handling in `RRule`/`RRuleExpander` has regressed (or lib-recur's),
 * or the RFC transcription drifted.
 *
 * Classification can't catch this: it consults the RFC only when the engines diverge, so if
 * both emitted the same wrong list (say BY* parts in the wrong order) the case would still be
 * D. The report's RFC-match lines would show it, but the report test never fails; this per-case
 * assertion does.
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

        // 3 days × 8 hours × 3 minutes = 72 occurrences, checked before the engines so a
        // drifted transcription fails with a clear message.
        assertEquals(72, rfcExpected!!.size)

        val comparison = ParityHarnessRunner.rfcComparisonFor(case)
        assertTrue("RfcComparison must be producible for example 36", comparison != null)

        // Both engines match the RFC and each other.
        assertTrue("lib-recur must match RFC example 36", comparison!!.libRecurMatchesRfc)
        assertTrue("ical4j must match RFC example 36 (guards the BYHOUR fix)", comparison.ical4jMatchesRfc)
        assertTrue("engines must agree on RFC example 36", comparison.enginesAgree)
    }
}
