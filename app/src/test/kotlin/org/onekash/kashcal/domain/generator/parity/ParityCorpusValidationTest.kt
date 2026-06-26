package org.onekash.kashcal.domain.generator.parity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.onekash.kashcal.domain.generator.parity.fixtures.AdversarialCorpus
import org.onekash.kashcal.domain.generator.parity.fixtures.CriticalBugCorpus
import org.onekash.kashcal.domain.generator.parity.fixtures.ExistingTestsCorpus
import org.onekash.kashcal.domain.generator.parity.fixtures.RfcExamplesCorpus
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Hygiene enforcement for the parity fixture corpus.
 *
 * These assertions exist to catch corpus-authoring errors before they reach
 * the engine-level parity runs. A malformed fixture case produces
 * a false divergence that wastes analyst time triaging what turns out to be
 * a transcription error.
 *
 * Rules enforced:
 * - No two cases share a `name` (global uniqueness, across all pools).
 * - `rangeEndMs > rangeStartMs` for every case.
 * - `dtstartMs` falls within a sane epoch range (1990..2050).
 * - `rangeStartMs` and `rangeEndMs` both within the same sane epoch range.
 * - For Pool A (rfc): `name` matches `RFC 5545 §3.8.5.3 example \d+.*`.
 * - Every case with a non-null `rfcExpected`: sorted ascending, unique,
 *   each timestamp within `[rangeStartMs, rangeEndMs)`.
 * - Every case with a non-null `knownDivergenceReason` has a non-blank reason.
 * - Pool identifiers match their expected category tag.
 * - Total corpus size within the 85..120 band.
 */
class ParityCorpusValidationTest {

    private val minEpochMs = epoch(1990, 1, 1)
    private val maxEpochMs = epoch(2050, 1, 1)

    private val allCorpora: List<CorpusBundle> = listOf(
        CorpusBundle("rfc", RfcExamplesCorpus.cases),
        CorpusBundle("critical", CriticalBugCorpus.cases),
        CorpusBundle("existing", ExistingTestsCorpus.cases),
        CorpusBundle("adversarial", AdversarialCorpus.cases),
    )

    private val allCases: List<RRuleCase> = allCorpora.flatMap { it.cases }

    @Test
    fun `total corpus size is within 85 to 120 cases`() {
        val size = allCases.size
        assertTrue(
            "corpus size $size must be in 85..120",
            size in 85..120,
        )
    }

    @Test
    fun `case names are globally unique`() {
        val dupes = allCases
            .groupingBy { it.name }
            .eachCount()
            .filterValues { it > 1 }
        if (dupes.isNotEmpty()) {
            fail("duplicate case names: ${dupes.keys}")
        }
    }

    @Test
    fun `every case has a non-blank name`() {
        val blanks = allCases.filter { it.name.isBlank() }
        assertTrue("cases with blank name: ${blanks.size}", blanks.isEmpty())
    }

    @Test
    fun `every case has a non-blank rrule`() {
        // Exception: adversarial pool may include cases that TEST parser robustness
        // with empty/blank rrule inputs. We allow blank only for the "adversarial" category.
        val bad = allCases.filter { it.rrule.isBlank() && it.category != "adversarial" }
        bad.forEach { println("blank-rrule non-adversarial: ${it.name}") }
        assertTrue("non-adversarial cases must have non-blank rrule", bad.isEmpty())
    }

    @Test
    fun `rangeEndMs is greater than rangeStartMs`() {
        val bad = allCases.filter { it.rangeEndMs <= it.rangeStartMs }
        bad.forEach { println("invalid range: ${it.name}  start=${it.rangeStartMs} end=${it.rangeEndMs}") }
        assertTrue("cases with non-positive range: ${bad.size}", bad.isEmpty())
    }

    @Test
    fun `dtstartMs is within sane epoch window`() {
        val bad = allCases.filter { it.dtstartMs !in minEpochMs until maxEpochMs }
        bad.forEach { println("insane dtstart: ${it.name}  dtstartMs=${it.dtstartMs}  (${fmt(it.dtstartMs)})") }
        assertTrue("cases with out-of-window dtstart: ${bad.size}", bad.isEmpty())
    }

    @Test
    fun `rangeStartMs and rangeEndMs are within sane epoch window`() {
        val bad = allCases.filter {
            it.rangeStartMs !in minEpochMs until maxEpochMs ||
                it.rangeEndMs !in minEpochMs..maxEpochMs
        }
        bad.forEach { println("insane range: ${it.name}  [${fmt(it.rangeStartMs)}..${fmt(it.rangeEndMs)})") }
        assertTrue("cases with out-of-window range: ${bad.size}", bad.isEmpty())
    }

    @Test
    fun `pool A case names match RFC 5545 §3_8_5_3 citation regex`() {
        val rfcRegex = Regex("""^RFC 5545 §3\.8\.5\.3 example \d+.*""")
        val bad = RfcExamplesCorpus.cases.filter { !rfcRegex.matches(it.name) }
        bad.forEach { println("Pool A name missing citation: '${it.name}'") }
        assertTrue("Pool A cases must cite their RFC example number: ${bad.size} violations", bad.isEmpty())
    }

    @Test
    fun `pool categories match their expected tags`() {
        for (bundle in allCorpora) {
            val bad = bundle.cases.filter { it.category != bundle.expectedCategory }
            bad.forEach { println("pool=${bundle.expectedCategory} has case '${it.name}' with category='${it.category}'") }
            assertTrue(
                "all cases in ${bundle.expectedCategory} pool must carry category='${bundle.expectedCategory}'",
                bad.isEmpty(),
            )
        }
    }

    @Test
    fun `Pool B has exactly 6 cases`() {
        assertEquals(
            "Pool B must contain EXACTLY one case per expansion-related CRITICAL quirk (6 cases)",
            6,
            CriticalBugCorpus.cases.size,
        )
    }

    @Test
    fun `rfcExpected when present is sorted ascending`() {
        val bad = allCases.filter { case ->
            val rfc = case.rfcExpected ?: return@filter false
            rfc != rfc.sorted()
        }
        bad.forEach { println("rfcExpected not sorted: ${it.name}") }
        assertTrue("cases with unsorted rfcExpected: ${bad.size}", bad.isEmpty())
    }

    @Test
    fun `rfcExpected when present contains no duplicates`() {
        val bad = allCases.filter { case ->
            val rfc = case.rfcExpected ?: return@filter false
            rfc.toSet().size != rfc.size
        }
        bad.forEach { println("rfcExpected has duplicates: ${it.name}") }
        assertTrue("cases with duplicate rfcExpected: ${bad.size}", bad.isEmpty())
    }

    @Test
    fun `rfcExpected when present has every timestamp within range window`() {
        val violations = mutableListOf<String>()
        for (case in allCases) {
            val rfc = case.rfcExpected ?: continue
            val outOfRange = rfc.filter { it !in case.rangeStartMs until case.rangeEndMs }
            if (outOfRange.isNotEmpty()) {
                violations.add(
                    "${case.name}: ${outOfRange.size} rfcExpected timestamps outside [${fmt(case.rangeStartMs)}..${fmt(case.rangeEndMs)}) " +
                        "— first offender: ${fmt(outOfRange.first())}"
                )
            }
        }
        violations.forEach { println(it) }
        assertTrue("rfcExpected timestamps must all be in [rangeStart, rangeEnd): ${violations.size} violations", violations.isEmpty())
    }

    @Test
    fun `rfcExpected is populated on every Pool A case`() {
        val missing = RfcExamplesCorpus.cases.filter { it.rfcExpected == null }
        missing.forEach { println("Pool A missing rfcExpected: ${it.name}") }
        assertTrue("Pool A cases MUST carry rfcExpected: ${missing.size} violations", missing.isEmpty())
    }

    @Test
    fun `rfcExpected is absent on non-Pool-A cases`() {
        // Non-RFC pools are input-only; ground truth comes from engine comparison, not from transcription.
        // If someone populates rfcExpected on a Pool B/C/D case, that's a red flag — the RFC is not
        // authority over those cases.
        val wronglyPopulated = allCases.filter {
            it.category != "rfc" && it.rfcExpected != null
        }
        wronglyPopulated.forEach { println("non-Pool-A case has rfcExpected populated: ${it.name}") }
        assertTrue("only Pool A may populate rfcExpected", wronglyPopulated.isEmpty())
    }

    @Test
    fun `knownDivergenceReason when present is non-blank`() {
        val bad = allCases.filter { it.knownDivergenceReason?.isBlank() == true }
        bad.forEach { println("blank knownDivergenceReason: ${it.name}") }
        assertTrue("cases with blank knownDivergenceReason: ${bad.size}", bad.isEmpty())
    }

    private data class CorpusBundle(
        val expectedCategory: String,
        val cases: List<RRuleCase>,
    )

    companion object {
        private val fmtUtc: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

        fun fmt(ms: Long): String = fmtUtc.format(Instant.ofEpochMilli(ms))

        fun epoch(y: Int, mo: Int, d: Int): Long =
            java.time.LocalDate.of(y, mo, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }
}
