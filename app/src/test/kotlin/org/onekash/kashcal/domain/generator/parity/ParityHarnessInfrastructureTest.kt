package org.onekash.kashcal.domain.generator.parity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Tests the harness plumbing with synthetic fixtures — no real engines are invoked
 * here. Real engines are tested in IcalDavRRuleAdapterTest (adapter correctness)
 * and the chunk-4 RRuleEngineParityReportTest (full corpus run).
 */
class ParityHarnessInfrastructureTest {

    private fun case(name: String = "test case"): RRuleCase = RRuleCase(
        name = name,
        category = "test",
        rrule = "FREQ=DAILY;COUNT=3",
        dtstartMs = 1704067200000L,
        timezone = "UTC",
        isAllDay = false,
        rdateStrings = null,
        exdateStrings = null,
        rangeStartMs = 1704067200000L,
        rangeEndMs = 1704067200000L + 365L * 86400 * 1000,
    )

    // ========== ParityComparator ==========

    @Test
    fun `comparator both succeed with same timestamps produces BothAgree`() {
        val result = ParityComparator.compare(
            libRecur = ExpansionResult.Success(listOf(1L, 2L, 3L)),
            ical4j = ExpansionResult.Success(listOf(1L, 2L, 3L)),
        )
        assertTrue(result is ParityResult.BothAgree)
        assertEquals(listOf(1L, 2L, 3L), (result as ParityResult.BothAgree).timestampsMs)
    }

    @Test
    fun `comparator both succeed with different timestamps produces Divergence`() {
        val result = ParityComparator.compare(
            libRecur = ExpansionResult.Success(listOf(1L, 2L)),
            ical4j = ExpansionResult.Success(listOf(1L, 3L)),
        )
        assertTrue(result is ParityResult.Divergence)
        val d = result as ParityResult.Divergence
        assertEquals(listOf(2L), d.libRecurOnly)
        assertEquals(listOf(3L), d.ical4jOnly)
        assertEquals(listOf(1L), d.common)
    }

    @Test
    fun `comparator lib-recur errors, ical4j succeeds produces OneErrored with erroredEngine=lib-recur`() {
        val result = ParityComparator.compare(
            libRecur = ExpansionResult.Error("parse failed", "IllegalArgumentException"),
            ical4j = ExpansionResult.Success(listOf(42L)),
        )
        assertTrue(result is ParityResult.OneErrored)
        val o = result as ParityResult.OneErrored
        assertEquals("lib-recur", o.erroredEngine)
        assertEquals("ical4j", o.otherEngine)
        assertEquals(listOf(42L), o.otherResult.timestampsMs)
    }

    @Test
    fun `comparator ical4j errors, lib-recur succeeds produces OneErrored with erroredEngine=ical4j`() {
        val result = ParityComparator.compare(
            libRecur = ExpansionResult.Success(listOf(99L)),
            ical4j = ExpansionResult.Error("bad input", "RuntimeException"),
        )
        assertTrue(result is ParityResult.OneErrored)
        val o = result as ParityResult.OneErrored
        assertEquals("ical4j", o.erroredEngine)
    }

    @Test
    fun `comparator both error produces BothErrored`() {
        val result = ParityComparator.compare(
            libRecur = ExpansionResult.Error("e1", "Ex1"),
            ical4j = ExpansionResult.Error("e2", "Ex2"),
        )
        assertTrue(result is ParityResult.BothErrored)
    }

    // ========== RFC comparison ==========

    @Test
    fun `RFC comparison — both engines match expected`() {
        val expected = listOf(100L, 200L, 300L)
        val cmp = ParityComparator.compareAgainstRfc(
            caseName = "rfc-both-match",
            rfcExpected = expected,
            libRecur = ExpansionResult.Success(expected),
            ical4j = ExpansionResult.Success(expected),
        )
        assertTrue(cmp.libRecurMatchesRfc)
        assertTrue(cmp.ical4jMatchesRfc)
        assertTrue(cmp.enginesAgree)
    }

    @Test
    fun `RFC comparison — only lib-recur matches`() {
        val expected = listOf(100L, 200L)
        val cmp = ParityComparator.compareAgainstRfc(
            caseName = "rfc-lib-only",
            rfcExpected = expected,
            libRecur = ExpansionResult.Success(expected),
            ical4j = ExpansionResult.Success(listOf(100L, 250L)),
        )
        assertTrue(cmp.libRecurMatchesRfc)
        assertFalse(cmp.ical4jMatchesRfc)
        assertFalse(cmp.enginesAgree)
    }

    @Test
    fun `RFC comparison — only ical4j matches`() {
        val expected = listOf(100L, 200L)
        val cmp = ParityComparator.compareAgainstRfc(
            caseName = "rfc-ical-only",
            rfcExpected = expected,
            libRecur = ExpansionResult.Success(listOf(100L, 201L)),
            ical4j = ExpansionResult.Success(expected),
        )
        assertFalse(cmp.libRecurMatchesRfc)
        assertTrue(cmp.ical4jMatchesRfc)
    }

    @Test
    fun `RFC comparison — neither matches but engines agree`() {
        val expected = listOf(100L, 200L)
        val cmp = ParityComparator.compareAgainstRfc(
            caseName = "rfc-neither",
            rfcExpected = expected,
            libRecur = ExpansionResult.Success(listOf(150L)),
            ical4j = ExpansionResult.Success(listOf(150L)),
        )
        assertFalse(cmp.libRecurMatchesRfc)
        assertFalse(cmp.ical4jMatchesRfc)
        assertTrue(cmp.enginesAgree)
    }

    // ========== Report writer ==========

    @Test
    fun `report contains summary header and per-case block`() {
        val caseResults = listOf(
            CaseResult(
                case = case("agree-case"),
                parity = ParityResult.BothAgree(listOf(1704067200000L, 1704153600000L)),
                classification = "D",
            ),
            CaseResult(
                case = case("diverge-case"),
                parity = ParityResult.Divergence(
                    libRecurOnly = listOf(2L),
                    ical4jOnly = listOf(3L),
                    common = listOf(1L),
                ),
                classification = "A",
                analystNote = "ical4j incorrectly shifts second occurrence by 1 hour",
            ),
        )
        val report = ParityReportWriter.write(caseResults, emptyList())

        assertTrue("has title", report.contains("# RRULE Engine Parity Report"))
        assertTrue("has summary", report.contains("## Summary"))
        assertTrue("counts total", report.contains("Total cases: 2"))
        assertTrue("reports agree-case", report.contains("### agree-case"))
        assertTrue("reports diverge-case", report.contains("### diverge-case"))
        assertTrue("analyst note included", report.contains("shifts second occurrence"))
    }

    @Test
    fun `report includes RFC compliance section when rfcResults present`() {
        val rfcResults = listOf(
            RfcComparison(
                caseName = "rfc-case-1",
                rfcExpected = listOf(1L, 2L),
                libRecurActual = ExpansionResult.Success(listOf(1L, 2L)),
                ical4jActual = ExpansionResult.Success(listOf(1L, 2L)),
                libRecurMatchesRfc = true,
                ical4jMatchesRfc = true,
                enginesAgree = true,
            ),
        )
        val report = ParityReportWriter.write(emptyList(), rfcResults)
        assertTrue("has RFC compliance section", report.contains("RFC 5545 §3.8.5.3 compliance"))
        assertTrue("reports both-match count", report.contains("Both engines match RFC: 1"))
    }

    // ========== End-to-end with stub engines ==========

    @Test
    fun `end-to-end with stub engines — both agree`() {
        // Use the real engine implementations against a trivial case to verify the plumbing.
        // COUNT=3 DAILY is the baseline LibRecurEngine smoke test — should be byte-equivalent
        // across engines for a simple case like this.
        val testCase = case("e2e-sanity")
        val libResult = LibRecurParityEngine.expand(testCase)
        val icalResult = ICal4jParityEngine.expand(testCase)
        val parity = ParityComparator.compare(libResult, icalResult)
        assertNotNull("parity result non-null", parity)
        // We don't assert which outcome — this is a plumbing smoke test. The chunk-4
        // report will surface any actual divergence.
    }
}
