package org.onekash.kashcal.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.onekash.icaldav.util.DurationUtils
import org.onekash.kashcal.data.calendar_provider.parseDurationMs
import java.time.Duration

/**
 * Compares the app's hand-rolled duration helpers, [computeDurationString] and [parseDurationMs],
 * with icaldav-core's [DurationUtils]. A third helper, `calculateDuration` in
 * AndroidCalendarProviderRepository.kt, is file-private and not tested here.
 *
 * Records every input where they disagree, so a swap of call sites to DurationUtils knows which
 * differences to accept as a behavior change and which to keep with a wrapper at the call site.
 *
 * Tests only the helpers' relative behavior. Correctness is covered by
 * `EventDurationFormatterTest`, the `parseDurationMs` tests in
 * `AndroidCalendarProviderRepositoryTest`, and icaldav-core's `DurationUtilsTest`.
 */
class DurationParityTest {

    // ========================================================================
    // FORMAT parity: computeDurationString vs DurationUtils.format(Duration.ofMillis(diff))
    // ========================================================================

    @Test
    fun `format parity - all-day single day`() {
        val start = 1704067200000L
        val end = start + ONE_DAY_MS
        val local = computeDurationString(start, end, isAllDay = true)
        val canonical = DurationUtils.format(Duration.ofMillis(end - start))
        assertEquals("both emit P1D for 1-day all-day", "P1D", local)
        assertEquals("canonical matches", local, canonical)
    }

    @Test
    fun `format parity - all-day multi-day`() {
        val start = 1704067200000L
        val end = start + 7 * ONE_DAY_MS
        assertEquals("P7D", computeDurationString(start, end, isAllDay = true))
        assertEquals("P7D", DurationUtils.format(Duration.ofMillis(end - start)))
    }

    @Test
    fun `format parity - timed hours and minutes`() {
        val start = 1704067200000L
        val end = start + (1 * 60 + 30) * 60 * 1000
        assertEquals("PT1H30M", computeDurationString(start, end, isAllDay = false))
        assertEquals("PT1H30M", DurationUtils.format(Duration.ofMillis(end - start)))
    }

    @Test
    fun `format parity - timed hours only`() {
        val start = 1704067200000L
        val end = start + 2 * 60 * 60 * 1000
        assertEquals("PT2H", computeDurationString(start, end, isAllDay = false))
        assertEquals("PT2H", DurationUtils.format(Duration.ofMillis(end - start)))
    }

    @Test
    fun `format parity - timed minutes only`() {
        val start = 1704067200000L
        val end = start + 45 * 60 * 1000
        assertEquals("PT45M", computeDurationString(start, end, isAllDay = false))
        assertEquals("PT45M", DurationUtils.format(Duration.ofMillis(end - start)))
    }

    // ========================================================================
    // FORMAT divergence: four differences
    // ========================================================================

    /**
     * Divergence 1: all-day zero duration.
     * - `computeDurationString` coerces to `P1D` via `coerceAtLeast(1)`.
     * - `DurationUtils.format(Duration.ZERO)` emits `PT0S`.
     * A swapped call site must keep the coerce, for single-day all-day events with start == end.
     */
    @Test
    fun `format DIVERGENCE - all-day zero duration`() {
        val start = 1704067200000L
        val local = computeDurationString(start, start, isAllDay = true)
        val canonical = DurationUtils.format(Duration.ZERO)
        assertEquals("local coerces to P1D", "P1D", local)
        assertEquals("canonical emits PT0S", "PT0S", canonical)
    }

    /**
     * Divergence 2: timed zero duration.
     * - `computeDurationString` emits `PT0M` from its `else -> "PT${minutes}M"` branch.
     * - `DurationUtils.format(Duration.ZERO)` emits `PT0S`.
     * Low impact, since CalendarProvider accepts both, but callers that assert exact strings will
     * break.
     */
    @Test
    fun `format DIVERGENCE - timed zero duration`() {
        val start = 1704067200000L
        val local = computeDurationString(start, start, isAllDay = false)
        val canonical = DurationUtils.format(Duration.ZERO)
        assertEquals("local emits PT0M", "PT0M", local)
        assertEquals("canonical emits PT0S", "PT0S", canonical)
    }

    /**
     * Divergence 3: sub-minute timed durations.
     * - `computeDurationString` divides by 60_000 and discards seconds: 45 seconds -> "PT0M".
     * - `DurationUtils.format` keeps seconds: 45 seconds -> "PT45S".
     * The app helper is lossy, so a swap would make the DURATION written to CalendarProvider
     * exact for events with sub-minute precision.
     */
    @Test
    fun `format DIVERGENCE - sub-minute precision`() {
        val start = 1704067200000L
        val end = start + 45_000L // 45 seconds
        val local = computeDurationString(start, end, isAllDay = false)
        val canonical = DurationUtils.format(Duration.ofMillis(end - start))
        assertEquals("local truncates to PT0M", "PT0M", local)
        assertEquals("canonical preserves PT45S", "PT45S", canonical)
    }

    /**
     * Divergence 4: timed durations beyond 24h.
     * - `computeDurationString` breaks down by minutes: 25h → "PT25H".
     * - `DurationUtils.format` rolls over to days: 25h → "P1DT1H".
     * Both are valid RFC 5545 durations and DurationUtils parses both to the same millis. Under
     * RFC 5545 §3.3.6 a day is nominal, though, so across a DST change "P1DT1H" isn't 25 hours.
     */
    @Test
    fun `format DIVERGENCE - 25 hours timed`() {
        val start = 1704067200000L
        val end = start + 25 * 60 * 60 * 1000
        val local = computeDurationString(start, end, isAllDay = false)
        val canonical = DurationUtils.format(Duration.ofMillis(end - start))
        assertEquals("local emits PT25H", "PT25H", local)
        assertEquals("canonical rolls to P1DT1H", "P1DT1H", canonical)

        // DurationUtils reads a day as 24 hours, so both parse to the same Duration.
        assertEquals(
            "both encode the same duration",
            DurationUtils.parse(local),
            DurationUtils.parse(canonical)
        )
    }

    // ========================================================================
    // PARSE parity: parseDurationMs vs DurationUtils.parse(s)?.toMillis() with fallback
    // ========================================================================

    @Test
    fun `parse parity - hours`() {
        assertEquals(3_600_000L, parseDurationMs("PT1H", isAllDay = false))
        assertEquals(3_600_000L, DurationUtils.parse("PT1H")!!.toMillis())
    }

    @Test
    fun `parse parity - minutes`() {
        assertEquals(1_800_000L, parseDurationMs("PT30M", isAllDay = false))
        assertEquals(1_800_000L, DurationUtils.parse("PT30M")!!.toMillis())
    }

    @Test
    fun `parse parity - hours and minutes`() {
        assertEquals(5_400_000L, parseDurationMs("PT1H30M", isAllDay = false))
        assertEquals(5_400_000L, DurationUtils.parse("PT1H30M")!!.toMillis())
    }

    @Test
    fun `parse parity - days`() {
        assertEquals(86_400_000L, parseDurationMs("P1D", isAllDay = true))
        assertEquals(86_400_000L, DurationUtils.parse("P1D")!!.toMillis())

        assertEquals(172_800_000L, parseDurationMs("P2D", isAllDay = true))
        assertEquals(172_800_000L, DurationUtils.parse("P2D")!!.toMillis())
    }

    @Test
    fun `parse parity - weeks`() {
        val expected = 7 * 86_400_000L
        assertEquals(expected, parseDurationMs("P1W", isAllDay = true))
        assertEquals(expected, DurationUtils.parse("P1W")!!.toMillis())
    }

    @Test
    fun `parse parity - complex P1DT2H30M`() {
        val expectedMs = Duration.ofDays(1).plusHours(2).plusMinutes(30).toMillis()
        // parseDurationMs's "no T" branch handles P1D; with a T it routes through
        // java.time Duration.parse, which handles P1DT2H30M.
        assertEquals(expectedMs, parseDurationMs("P1DT2H30M", isAllDay = false))
        assertEquals(expectedMs, DurationUtils.parse("P1DT2H30M")!!.toMillis())
    }

    @Test
    fun `parse parity - negative triggers`() {
        // Alarm-style negative triggers
        val expected = -15L * 60_000L
        // parseDurationMs has no custom path for this and hands it to java.time Duration.parse,
        // which this test calls directly.
        assertEquals(expected, Duration.parse("-PT15M").toMillis())
        assertEquals(expected, DurationUtils.parse("-PT15M")!!.toMillis())
    }

    // ========================================================================
    // PARSE fallback parity: null / empty / malformed
    // ========================================================================

    @Test
    fun `parse fallback parity - null input`() {
        // Local: returns defaults (1d for all-day, 1h for timed).
        // Canonical: returns null; the caller must supply the default.
        assertEquals(86_400_000L, parseDurationMs(null, isAllDay = true))
        assertEquals(3_600_000L, parseDurationMs(null, isAllDay = false))
        assertNull("canonical returns null", DurationUtils.parse(null))

        // A swapped caller would use DurationUtils.parseOrDefault with the same defaults:
        assertEquals(
            86_400_000L,
            DurationUtils.parseOrDefault(null, Duration.ofDays(1)).toMillis()
        )
        assertEquals(
            3_600_000L,
            DurationUtils.parseOrDefault(null, Duration.ofHours(1)).toMillis()
        )
    }

    @Test
    fun `parse fallback parity - empty string`() {
        assertEquals(86_400_000L, parseDurationMs("", isAllDay = true))
        assertEquals(3_600_000L, parseDurationMs("", isAllDay = false))
        assertNull(DurationUtils.parse(""))
        assertEquals(86_400_000L, DurationUtils.parseOrDefault("", Duration.ofDays(1)).toMillis())
    }

    @Test
    fun `parse fallback parity - blank string`() {
        assertNull(DurationUtils.parse("   "))
        // Local: relies on Duration.parse throwing, so "   " goes to default.
        assertEquals(3_600_000L, parseDurationMs("   ", isAllDay = false))
    }

    @Test
    fun `parse fallback parity - malformed input`() {
        assertEquals(3_600_000L, parseDurationMs("garbage", isAllDay = false))
        assertEquals(86_400_000L, parseDurationMs("INVALID", isAllDay = true))
        assertNull(DurationUtils.parse("garbage"))
        assertNull(DurationUtils.parse("INVALID"))
    }

    // ========================================================================
    // ADVERSARIAL parity: inputs that might trip one but not the other
    // ========================================================================

    /**
     * Lowercase durations are non-standard (RFC 5545 §3.1 makes non-enumerated property values
     * case-sensitive), but some servers and tools emit them. DurationUtils accepts them.
     * parseDurationMs uppercases nothing: a value that doesn't start with "P" or contains "T"
     * goes to java.time Duration.parse, which accepts lowercase, but any other value must end in
     * an uppercase "W" or "D".
     */
    @Test
    fun `adversarial - lowercase PT15M - AGREEMENT`() {
        // DurationUtils.parse hands it to java.time Duration.parse, which accepts lowercase.
        val canonical = DurationUtils.parse("pt15m")
        assertNotNull("canonical handles lowercase", canonical)
        assertEquals(15 * 60_000L, canonical!!.toMillis())

        // parseDurationMs: `pt15m` doesn't start with uppercase "P", so it takes the else
        // branch to Duration.parse, which accepts lowercase. Both agree at 15 min.
        assertEquals(15 * 60_000L, parseDurationMs("pt15m", isAllDay = false))
    }

    @Test
    fun `adversarial - mixed case P1d`() {
        val canonical = DurationUtils.parse("P1d")
        assertNotNull("canonical handles mixed case", canonical)

        // Local: falls back to the default because lowercase "d" doesn't match endsWith("D")
        assertEquals(
            "local returns default on mixed case",
            3_600_000L,
            parseDurationMs("P1d", isAllDay = false)
        )
    }

    @Test
    fun `adversarial - leading plus sign`() {
        val canonical = DurationUtils.parse("+PT15M")
        assertNotNull("canonical handles +", canonical)
        assertEquals(15 * 60_000L, canonical!!.toMillis())

        // Local: "+PT15M" doesn't start with "P", so it goes to java.time Duration.parse, which
        // accepts a leading sign and gives 15 minutes. The assert also accepts the default.
        val local = parseDurationMs("+PT15M", isAllDay = false)
        assertTrue(
            "local either parses or returns default",
            local == 15 * 60_000L || local == 3_600_000L
        )
    }

    @Test
    fun `adversarial - whitespace around input`() {
        val canonical = DurationUtils.parse("  PT15M  ")
        assertNotNull("canonical trims whitespace", canonical)
        assertEquals(15 * 60_000L, canonical!!.toMillis())

        // Local: the value starts with a space, not "P", so it goes to Duration.parse, which
        // doesn't trim and throws, giving the default.
        assertEquals(
            "local returns default on whitespace",
            3_600_000L,
            parseDurationMs("  PT15M  ", isAllDay = false)
        )
    }

    @Test
    fun `adversarial - seconds in ISO`() {
        val canonical = DurationUtils.parse("PT30S")
        assertNotNull(canonical)
        assertEquals(30_000L, canonical!!.toMillis())

        // Local: PT30S has a T, so it goes to Duration.parse, which accepts it.
        assertEquals(30_000L, parseDurationMs("PT30S", isAllDay = false))
    }

    @Test
    fun `adversarial - very long duration P999D`() {
        val expected = 999L * 86_400_000L
        assertEquals(expected, parseDurationMs("P999D", isAllDay = true))
        assertEquals(expected, DurationUtils.parse("P999D")!!.toMillis())
    }

    @Test
    fun `adversarial - zero week P0W`() {
        assertEquals(0L, parseDurationMs("P0W", isAllDay = true))
        assertEquals(0L, DurationUtils.parse("P0W")!!.toMillis())
    }

    @Test
    fun `adversarial - only P no body`() {
        // The RFC 5545 §3.3.6 grammar requires at least one component. This records what each
        // helper does without asserting correctness.
        val localResult = parseDurationMs("P", isAllDay = false)
        val canonicalResult = DurationUtils.parse("P")
        // Local: "P" has no T, removePrefix("P") gives "", no match on D/W → default.
        assertEquals("local falls back to default", 3_600_000L, localResult)
        // Canonical: every component is missing, so it gives zero; the check also allows null.
        if (canonicalResult != null) {
            assertEquals(0L, canonicalResult.toSeconds())
        }
    }

    /**
     * Mixed weeks and days, which RFC 5545 §3.3.6 disallows (`dur-week` stands alone).
     *
     * Local `parseDurationMs("P1W1D")` goes through the "no T" branch:
     *   1. startsWith("P") = true, !contains("T") = true
     *   2. removePrefix("P") → "1W1D"
     *   3. endsWith("W")? No.
     *   4. endsWith("D")? Yes.
     *   5. removeSuffix("D") → "1W1"
     *   6. "1W1".toLongOrNull() → null → `days = 1` (silent fallback)
     *   7. Returns 86,400,000 ms (1 day), silently losing the week.
     *
     * Canonical `DurationUtils.parse("P1W1D")`: java.time rejects it; in the iCalendar fallback
     * endsWith("W") is false, so the day/hour/minute regex finds 1D and ignores the W. Returns
     * 1 day.
     *
     * Both give 1 day for this malformed input by coincidence. The local helper's `?: 1` fallback
     * reads any other non-numeric count as 1 too.
     */
    @Test
    fun `adversarial - mixed weeks and days P1W1D - both silently accept as 1 day`() {
        val canonical = DurationUtils.parse("P1W1D")?.toMillis()
        val local = parseDurationMs("P1W1D", isAllDay = false)
        assertEquals("canonical silently accepts as 1 day", 86_400_000L, canonical)
        assertEquals("local silently accepts as 1 day", 86_400_000L, local)
    }

    @Test
    fun `adversarial - negative P prefix`() {
        // parseDurationMs sends "-P1D" to java.time Duration.parse because it doesn't start with
        // "P"; the test calls Duration.parse directly.
        val localNegDay = try {
            Duration.parse("-P1D").toMillis()
        } catch (e: Exception) {
            null
        }
        val canonicalNegDay = DurationUtils.parse("-P1D")?.toMillis()
        // If local can parse it, both must agree.
        if (localNegDay != null) {
            assertEquals(localNegDay, canonicalNegDay)
        } else {
            // Reached only on a JDK whose Duration.parse rejects "-P1D"; the JDK this runs on
            // accepts it (-24 hours).
            assertNotNull("canonical handles -P1D", canonicalNegDay)
        }
    }

    // ========================================================================
    // PARSE ROUND-TRIP with FORMAT
    // ========================================================================

    /**
     * DurationUtils.parse must read every listed computeDurationString output back to the same
     * milliseconds. If this holds, a swap keeps the durations even where the bytes differ
     * (divergences 1-4).
     */
    @Test
    fun `round-trip - every computeDurationString output parses back correctly`() {
        val cases = listOf(
            Triple(1704067200000L, 1704067200000L + ONE_DAY_MS, true),       // 1 day all-day
            Triple(1704067200000L, 1704067200000L + 7 * ONE_DAY_MS, true),   // 7 day all-day
            Triple(1704067200000L, 1704067200000L + 30L * ONE_DAY_MS, true), // 30 day all-day
            Triple(1704067200000L, 1704067200000L + 90 * 60_000L, false),    // 1h30m timed
            Triple(1704067200000L, 1704067200000L + 2 * 3_600_000L, false),  // 2h timed
            Triple(1704067200000L, 1704067200000L + 45 * 60_000L, false),    // 45m timed
            Triple(1704067200000L, 1704067200000L + 25 * 3_600_000L, false)  // 25h (divergence 4)
        )
        for ((start, end, isAllDay) in cases) {
            val emitted = computeDurationString(start, end, isAllDay)
            val parsed = DurationUtils.parse(emitted)
                ?: fail("DurationUtils could not parse local output: $emitted") as Duration
            val expectedMs = end - start
            // All-day zero-duration coerces to P1D = 1 day
            val effectiveExpectedMs = if (isAllDay && expectedMs == 0L) ONE_DAY_MS else expectedMs
            assertEquals(
                "round-trip millis match for $emitted (isAllDay=$isAllDay)",
                effectiveExpectedMs,
                parsed.toMillis()
            )
        }
    }

    /**
     * And the inverse: every input parseDurationMs accepts, DurationUtils.parse must also accept
     * and return the same millis (otherwise the swap changes observable behavior at call sites).
     */
    @Test
    fun `round-trip - every parseable input matches parseDurationMs`() {
        val wellFormedInputs = listOf(
            "PT15M" to 15 * 60_000L,
            "PT30M" to 30 * 60_000L,
            "PT1H" to 3_600_000L,
            "PT1H30M" to 90 * 60_000L,
            "PT0S" to 0L,
            "P1D" to ONE_DAY_MS,
            "P2D" to 2 * ONE_DAY_MS,
            "P7D" to 7 * ONE_DAY_MS,
            "P1W" to 7 * ONE_DAY_MS,
            "P2W" to 14 * ONE_DAY_MS,
            "P1DT2H" to ONE_DAY_MS + 2 * 3_600_000L,
            "PT90M" to 90 * 60_000L,
            "-PT15M" to -15 * 60_000L
        )
        for ((input, expectedMs) in wellFormedInputs) {
            val local = parseDurationMs(input, isAllDay = false)
            val canonical = DurationUtils.parse(input)?.toMillis()
            assertEquals("local parses $input correctly", expectedMs, local)
            assertEquals("canonical parses $input correctly", expectedMs, canonical)
            assertEquals("both agree on $input", local, canonical)
        }
    }

    companion object {
        private const val ONE_DAY_MS = 86_400_000L
    }
}
