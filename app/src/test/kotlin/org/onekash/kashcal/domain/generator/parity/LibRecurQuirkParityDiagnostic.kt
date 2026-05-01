package org.onekash.kashcal.domain.generator.parity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Per-quirk regression protection for LibRecurEngine's 9 CRITICAL quirks.
 *
 * The main parity report at `RRuleEngineParityReportTest` captures aggregate
 * agreement across 92 fixture cases. This test operates at finer granularity:
 * one isolated minimal fixture per quirk (labels a-i as documented in
 * LibRecurEngine.kt), with an EXPECTED verdict per quirk.
 *
 * If ical4j (or lib-recur) changes behavior on any specific quirk shape —
 * e.g., ical4j starts stripping UNTIL when COUNT is present, or adds
 * millisecond truncation — this test fails with a precise pointer to which
 * quirk broke. The main parity report would also catch a change, but via
 * baseline drift; this test fails with the quirk label directly in the
 * assertion message.
 *
 * Quirks exercised (labels from LibRecurEngine.kt):
 *   (a) All-day events force UTC regardless of TZID
 *   (b) COUNT+UNTIL both present → strip UNTIL
 *   (c) DATE-format UNTIL requires date-only DTSTART
 *   (d) FastForwarded optimization when rangeStart > DTSTART + 30d
 *   (e) MAX_ITERATIONS safety limit
 *   (f) Second-boundary alignment on DTSTART
 *   (g) RDATE/EXDATE inherit DTSTART hour/minute/second
 *   (h) Sub-second truncation on each occurrence
 *   (i) FastForwarded DateTime type matches DTSTART type
 *
 * Expected verdicts were captured 2026-04-30 (main @ 927cf8b5). See the
 * top-level doc comment in LibRecurEngine.kt for the quirk definitions and
 * the class-level KDoc below for migration implications per quirk.
 */
class LibRecurQuirkParityDiagnostic {

    /** Expected per-quirk parity outcome captured against main @ 927cf8b5. */
    private enum class ExpectedVerdict { AGREE, DIVERGE }

    /**
     * One quirk fixture. [expectedVerdict] locks in current behavior so a
     * future engine change is caught with a precise diagnostic.
     *
     * For DIVERGE quirks, [expectedLibCount] and [expectedIcalCount] pin the
     * exact count each engine currently returns. A change in either count
     * flags a silent behavior shift even if the overall verdict stays DIVERGE.
     */
    private data class QuirkFixture(
        val label: String,
        val description: String,
        val case: RRuleCase,
        val expectedVerdict: ExpectedVerdict,
        val expectedLibCount: Int,
        val expectedIcalCount: Int,
        val migrationImplication: String,
    )

    private val ETZ: ZoneId = ZoneId.of("America/New_York")

    private fun et(y: Int, m: Int, d: Int, hour: Int = 9, minute: Int = 0, second: Int = 0): Long =
        ZonedDateTime.of(y, m, d, hour, minute, second, 0, ETZ).toInstant().toEpochMilli()

    private fun utcMidnight(y: Int, m: Int, d: Int): Long =
        LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun fixture(
        label: String,
        description: String,
        rrule: String,
        dtstartMs: Long,
        timezone: String?,
        isAllDay: Boolean,
        rangeStartMs: Long,
        rangeEndMs: Long,
        expectedVerdict: ExpectedVerdict,
        expectedLibCount: Int,
        expectedIcalCount: Int,
        migrationImplication: String,
        rdateStrings: String? = null,
        exdateStrings: String? = null,
    ): QuirkFixture = QuirkFixture(
        label = label,
        description = description,
        case = RRuleCase(
            name = "QUIRK $label: $description",
            category = "diagnostic",
            rrule = rrule,
            dtstartMs = dtstartMs,
            timezone = timezone,
            isAllDay = isAllDay,
            rdateStrings = rdateStrings,
            exdateStrings = exdateStrings,
            rangeStartMs = rangeStartMs,
            rangeEndMs = rangeEndMs,
        ),
        expectedVerdict = expectedVerdict,
        expectedLibCount = expectedLibCount,
        expectedIcalCount = expectedIcalCount,
        migrationImplication = migrationImplication,
    )

    private val fixtures: List<QuirkFixture> = listOf(

        // (a) All-day forces UTC regardless of TZID. IcalDavRRuleAdapter.resolveZone
        // replicates this, so both engines return the same UTC-midnight Mondays.
        fixture(
            label = "a",
            description = "all-day WEEKLY BYDAY=MO with non-UTC TZID attached",
            rrule = "FREQ=WEEKLY;BYDAY=MO;COUNT=4",
            dtstartMs = utcMidnight(2025, 1, 6),
            timezone = "America/Chicago",
            isAllDay = true,
            rangeStartMs = utcMidnight(2025, 1, 1),
            rangeEndMs = utcMidnight(2025, 2, 15),
            expectedVerdict = ExpectedVerdict.AGREE,
            expectedLibCount = 4,
            expectedIcalCount = 4,
            migrationImplication = "FREE_SWAP — IcalDavRRuleAdapter.resolveZone forces UTC for all-day; no adapter-layer work",
        ),

        // (b) COUNT+UNTIL both present. lib-recur strips UNTIL (returns COUNT
        // results); ical4j returns []. RFC §3.3.10 says these are mutually
        // exclusive — input is malformed, so behavior is undefined.
        fixture(
            label = "b",
            description = "COUNT=3 + UNTIL in past both present",
            rrule = "FREQ=DAILY;COUNT=3;UNTIL=20000101T000000Z",
            dtstartMs = et(2025, 5, 1, 10, 0),
            timezone = "America/New_York",
            isAllDay = false,
            rangeStartMs = et(2025, 5, 1, 0, 0),
            rangeEndMs = et(2025, 5, 10, 0, 0),
            expectedVerdict = ExpectedVerdict.AGREE,
            expectedLibCount = 3,
            expectedIcalCount = 3,
            migrationImplication = "FREE_SWAP — quirk (b) ported to IcalDavRRuleAdapter.sanitizeRRule; COUNT+UNTIL both yields COUNT-many occurrences post-migration",
        ),

        // (c) DATE-format UNTIL + all-day DTSTART. lib-recur's isAllDay/
        // isFloating assertion is self-protection; ical4j has no such check.
        fixture(
            label = "c",
            description = "all-day YEARLY with DATE-format UNTIL",
            rrule = "FREQ=YEARLY;UNTIL=20270927",
            dtstartMs = utcMidnight(2020, 9, 27),
            timezone = null,
            isAllDay = true,
            rangeStartMs = utcMidnight(2020, 1, 1),
            rangeEndMs = utcMidnight(2028, 1, 1),
            expectedVerdict = ExpectedVerdict.AGREE,
            expectedLibCount = 8,
            expectedIcalCount = 8,
            migrationImplication = "LIB_RECUR_ONLY — ical4j has no isAllDay/isFloating assertion; quirk irrelevant post-migration",
        ),

        // (d) FastForwarded only when range starts > DTSTART + 30d. ical4j has
        // no FastForward optimization, so the branch is irrelevant.
        fixture(
            label = "d",
            description = "range starts same day as DTSTART (no FastForward)",
            rrule = "FREQ=DAILY;COUNT=5",
            dtstartMs = et(2025, 6, 1, 9, 0),
            timezone = "America/New_York",
            isAllDay = false,
            rangeStartMs = et(2025, 6, 1, 0, 0),
            rangeEndMs = et(2025, 6, 10, 0, 0),
            expectedVerdict = ExpectedVerdict.AGREE,
            expectedLibCount = 5,
            expectedIcalCount = 5,
            migrationImplication = "LIB_RECUR_ONLY — ical4j has no FastForward; quirk irrelevant post-migration",
        ),

        // (e) MAX_ITERATIONS safety cap. This fixture uses a 2-hour MINUTELY
        // range (120 iterations — well under both engines' caps). A case that
        // actually hits the cap would cause one engine to truncate differently;
        // that's tested by Pool D's SECONDLY case in the main corpus.
        fixture(
            label = "e",
            description = "MINUTELY unbounded over 2-hour range",
            rrule = "FREQ=MINUTELY",
            dtstartMs = et(2025, 7, 1, 10, 0),
            timezone = "America/New_York",
            isAllDay = false,
            rangeStartMs = et(2025, 7, 1, 10, 0),
            rangeEndMs = et(2025, 7, 1, 12, 0),
            expectedVerdict = ExpectedVerdict.AGREE,
            expectedLibCount = 120,
            expectedIcalCount = 120,
            migrationImplication = "FREE_SWAP on this range — extreme unbounded shapes still need the Pool D SECONDLY case to confirm cap parity",
        ),

        // (f) Second-boundary alignment on DTSTART. DTSTART at ...10:00:00.500Z.
        // lib-recur divides by 1000 losing 500ms; ical4j preserves it through
        // ICalDateTime.fromTimestamp.
        fixture(
            label = "f",
            description = "DTSTART with sub-second precision (500ms)",
            rrule = "FREQ=DAILY;COUNT=3",
            dtstartMs = et(2025, 8, 1, 10, 0) + 500L,
            timezone = "America/New_York",
            isAllDay = false,
            rangeStartMs = et(2025, 8, 1, 0, 0),
            rangeEndMs = et(2025, 8, 5, 0, 0),
            expectedVerdict = ExpectedVerdict.AGREE,
            expectedLibCount = 3,
            expectedIcalCount = 3,
            migrationImplication = "FREE_SWAP — quirk (f)/(h) ported to IcalDavRRuleEngine second-alignment step; sub-second DTSTART is truncated on every occurrence",
        ),

        // (g) DATE-format EXDATE against timed DTSTART. RFC §3.8.5.1 says same
        // VALUE type is required, so input is malformed. lib-recur inherits
        // DTSTART's hour for matching; ical4j (via adapter) treats DATE as
        // UTC-midnight so the exclusion misses.
        fixture(
            label = "g",
            description = "timed DAILY + DATE-format EXDATE",
            rrule = "FREQ=DAILY;COUNT=5",
            dtstartMs = et(2025, 7, 1, 10, 0),
            timezone = "America/New_York",
            isAllDay = false,
            exdateStrings = "20250703",
            rangeStartMs = et(2025, 7, 1, 0, 0),
            rangeEndMs = et(2025, 7, 10, 0, 0),
            expectedVerdict = ExpectedVerdict.AGREE,
            expectedLibCount = 4,
            expectedIcalCount = 4,
            migrationImplication = "FREE_SWAP — quirk (g) ported to IcalDavRRuleAdapter.parseCsvDates which inherits DTSTART hour/minute/second for DATE-format RDATE/EXDATE on timed events",
        ),

        // (h) Sub-second truncation on each occurrence. Same root as (f) but
        // tests that EVERY occurrence is second-aligned, not just DTSTART.
        // lib-recur's occurrence generation goes through seconds-math.
        fixture(
            label = "h",
            description = "DAILY with sub-second DTSTART (per-occurrence alignment)",
            rrule = "FREQ=DAILY;COUNT=3",
            dtstartMs = et(2025, 9, 1, 12, 0) + 999L,
            timezone = "America/New_York",
            isAllDay = false,
            rangeStartMs = et(2025, 9, 1, 0, 0),
            rangeEndMs = et(2025, 9, 5, 0, 0),
            expectedVerdict = ExpectedVerdict.AGREE,
            expectedLibCount = 3,
            expectedIcalCount = 3,
            migrationImplication = "FREE_SWAP — same port as (f); IcalDavRRuleEngine applies (ts / 1000) * 1000 to every returned timestamp",
        ),

        // (i) FastForwarded DateTime type matches DTSTART type. Combines (c) +
        // (d): all-day + DATE-format UNTIL with rangeStart far after DTSTART.
        // lib-recur-only self-protection.
        fixture(
            label = "i",
            description = "all-day YEARLY with DATE-format UNTIL far-forwarded",
            rrule = "FREQ=YEARLY;UNTIL=20350927",
            dtstartMs = utcMidnight(2020, 9, 27),
            timezone = null,
            isAllDay = true,
            rangeStartMs = utcMidnight(2030, 1, 1),
            rangeEndMs = utcMidnight(2036, 1, 1),
            expectedVerdict = ExpectedVerdict.AGREE,
            expectedLibCount = 6,
            expectedIcalCount = 6,
            migrationImplication = "LIB_RECUR_ONLY — ical4j has no FastForward optimization; quirk irrelevant post-migration",
        ),
    )

    @Test
    fun `all 9 quirks match expected per-quirk verdict and engine counts`() {
        val violations = mutableListOf<String>()
        for (f in fixtures) {
            val lib = LibRecurParityEngine.expand(f.case)
            val ical = ICal4jParityEngine.expand(f.case)
            val parity = ParityComparator.compare(lib, ical)
            val actualVerdict = when (parity) {
                is ParityResult.BothAgree -> ExpectedVerdict.AGREE
                is ParityResult.Divergence -> ExpectedVerdict.DIVERGE
                is ParityResult.OneErrored -> {
                    violations += "QUIRK ${f.label}: unexpected error — ${parity.erroredEngine} returned " +
                        "${parity.error.throwableClass}: ${parity.error.message}"
                    continue
                }
                is ParityResult.BothErrored -> {
                    violations += "QUIRK ${f.label}: both engines errored (broken plumbing or bad fixture)"
                    continue
                }
            }

            if (actualVerdict != f.expectedVerdict) {
                violations += "QUIRK ${f.label} (${f.description}): " +
                    "expected ${f.expectedVerdict} but got $actualVerdict. " +
                    "lib=${countOf(lib)}, ical=${countOf(ical)}. " +
                    "If this change is intentional, update expectedVerdict + expectedLibCount + " +
                    "expectedIcalCount for this quirk and revisit migrationImplication."
                continue
            }

            val libCount = countOf(lib)
            val icalCount = countOf(ical)
            if (libCount != f.expectedLibCount || icalCount != f.expectedIcalCount) {
                violations += "QUIRK ${f.label} (${f.description}): verdict stable (${f.expectedVerdict}) " +
                    "but counts shifted. Expected lib=${f.expectedLibCount} ical=${f.expectedIcalCount}; " +
                    "got lib=$libCount ical=$icalCount. Silent behavior drift — investigate before updating."
            }
        }

        if (violations.isNotEmpty()) {
            fail(
                "Per-quirk parity regressions detected (${violations.size}/${fixtures.size} quirks):\n" +
                    violations.joinToString("\n") { "  - $it" } +
                    "\n\nSee LibRecurEngine.kt for quirk definitions (labels a-i). If the behavior " +
                    "change is intentional (e.g., ical4j upgrade, adapter fix), update the fixture's " +
                    "expected fields in this test and the migration-planning table in the parity report."
            )
        }
    }

    @Test
    fun `every quirk label a through i is covered exactly once`() {
        // Guards against accidental duplication or omission of a quirk label.
        val expectedLabels = ('a'..'i').map { it.toString() }.toSet()
        val actualLabels = fixtures.map { it.label }
        assertEquals(
            "fixture labels must be unique",
            actualLabels.size,
            actualLabels.toSet().size,
        )
        assertEquals(
            "fixtures must cover exactly quirks a-i (one per quirk)",
            expectedLabels,
            actualLabels.toSet(),
        )
    }

    @Test
    fun `migration implication is non-blank for every quirk`() {
        // Each quirk's verdict must carry human-readable rationale so the test
        // failure message is actionable. Empty rationale would undermine the
        // "precise diagnostic pointer" goal of this suite.
        val blank = fixtures.filter { it.migrationImplication.isBlank() }
        assertTrue(
            "every fixture must carry a non-blank migrationImplication: ${blank.map { it.label }}",
            blank.isEmpty(),
        )
    }

    private fun countOf(result: ExpansionResult): Int = when (result) {
        is ExpansionResult.Success -> result.timestampsMs.size
        is ExpansionResult.Error -> -1
    }
}
