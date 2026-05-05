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
 * Side-by-side parity tests comparing KashCal's three hand-rolled duration helpers
 * (`EventDurationFormatter.computeDurationString`, `AndroidCalendarProviderRepository.calculateDuration`
 * [unreachable from tests — file-private], `AndroidCalendarProviderRepository.parseDurationMs`)
 * against the canonical icaldav-core `DurationUtils`.
 *
 * Goal: find every input where the two implementations disagree BEFORE swapping call sites.
 * Any divergence must either
 *   (a) be documented and accepted as a behavior change, OR
 *   (b) be paved over by a thin wrapper at the swap site.
 *
 * Not a test of either implementation's correctness in isolation — only of their relative
 * behavior. Correctness is covered by `EventDurationFormatterTest`, the `parseDurationMs`
 * block of `AndroidCalendarProviderRepositoryTest`, and icaldav-core's `DurationUtilsTest`.
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
    // FORMAT divergence: the four documented differences
    // ========================================================================

    /**
     * DIVERGENCE 1: all-day zero duration.
     * - `computeDurationString` coerces to `P1D` via `coerceAtLeast(1)`.
     * - `DurationUtils.format(Duration.ZERO)` emits `PT0S`.
     * Swap site MUST preserve the coerce (single-day all-day events with start==end).
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
     * DIVERGENCE 2: timed zero duration.
     * - `computeDurationString` emits `PT0M` (reached via `when { hours == 0 && minutes == 0 -> "PT${minutes}M" }`).
     * - `DurationUtils.format(Duration.ZERO)` emits `PT0S`.
     * Low-impact — CalendarProvider accepts both — but callers that assert exact strings will break.
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
     * DIVERGENCE 3: sub-minute timed durations.
     * - `computeDurationString` divides by 60_000 and discards seconds: 45 seconds -> "PT0M".
     * - `DurationUtils.format` preserves seconds: 45 seconds -> "PT45S".
     * Local behavior is lossy; canonical is correct. After swap, ICS output becomes more accurate
     * for edge events with sub-minute precision (rare in practice, but e.g. scientific schedules).
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
     * DIVERGENCE 4: durations beyond 24h for non-all-day.
     * - `computeDurationString` uses minute-based breakdown: 25h → "PT25H".
     * - `DurationUtils.format` rolls over to days: 25h → "P1DT1H".
     * Semantically equivalent (both valid RFC 5545 P-forms for the same duration) but byte-different.
     * Round-trip via any parser normalizes them, so this is a cosmetic change only.
     */
    @Test
    fun `format DIVERGENCE - 25 hours timed`() {
        val start = 1704067200000L
        val end = start + 25 * 60 * 60 * 1000
        val local = computeDurationString(start, end, isAllDay = false)
        val canonical = DurationUtils.format(Duration.ofMillis(end - start))
        assertEquals("local emits PT25H", "PT25H", local)
        assertEquals("canonical rolls to P1DT1H", "P1DT1H", canonical)

        // Both parse to the same Duration though - proof of semantic equivalence
        assertEquals(
            "both encode the same duration",
            DurationUtils.parse(local),
            DurationUtils.parse(canonical)
        )
    }

    // ========================================================================
    // PARSE parity: parseDurationMs(s, isAllDay) vs DurationUtils.parse(s)?.toMillis() with fallback
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
        // Local parser: the parseDurationMs function's "no T" branch handles P1D, but when there's
        // a T it routes through Duration.parse which handles P1DT2H30M fine.
        assertEquals(expectedMs, parseDurationMs("P1DT2H30M", isAllDay = false))
        assertEquals(expectedMs, DurationUtils.parse("P1DT2H30M")!!.toMillis())
    }

    @Test
    fun `parse parity - negative triggers`() {
        // Alarm-style negative triggers
        val expected = -15L * 60_000L
        // Local: Duration.parse handles this - no custom path
        assertEquals(expected, Duration.parse("-PT15M").toMillis())
        assertEquals(expected, DurationUtils.parse("-PT15M")!!.toMillis())
    }

    // ========================================================================
    // PARSE fallback parity: null / empty / malformed
    // ========================================================================

    @Test
    fun `parse fallback parity - null input`() {
        // Local: returns defaults (1d for all-day, 1h for timed).
        // Canonical: returns null — caller must supply default.
        assertEquals(86_400_000L, parseDurationMs(null, isAllDay = true))
        assertEquals(3_600_000L, parseDurationMs(null, isAllDay = false))
        assertNull("canonical returns null", DurationUtils.parse(null))

        // After swap, caller uses DurationUtils.parseOrDefault with same defaults:
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
     * Mixed-case iCalendar durations are NON-standard per RFC 5545 (grammar uses uppercase),
     * but some servers/tools emit them. DurationUtils tolerates; parseDurationMs also tolerates
     * because P1D, PT1H etc. go through Duration.parse (case-insensitive) or the "P...D" branch
     * which uppercases via `startsWith("P")` check... actually no, the local helper doesn't
     * uppercase. Let's see.
     */
    @Test
    fun `adversarial - lowercase PT15M - AGREEMENT`() {
        // DurationUtils.parse uppercases internally — handles it.
        val canonical = DurationUtils.parse("pt15m")
        assertNotNull("canonical handles lowercase", canonical)
        assertEquals(15 * 60_000L, canonical!!.toMillis())

        // parseDurationMs: `pt15m` doesn't start with uppercase "P", so routes to the else
        // branch → Duration.parse. Java's Duration.parse IS case-insensitive per javadoc,
        // so it accepts lowercase. Both agree at 15 min.
        assertEquals(15 * 60_000L, parseDurationMs("pt15m", isAllDay = false))
    }

    @Test
    fun `adversarial - mixed case P1d`() {
        val canonical = DurationUtils.parse("P1d")
        assertNotNull("canonical handles mixed case", canonical)

        // Local: fails because "d" (lowercase) doesn't match endsWith("D")
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

        // Local: Duration.parse rejects leading + on positive durations (sort of — depends on JDK)
        // Will fall through to default.
        // Result unknown, but won't crash. Document whichever happens.
        val local = parseDurationMs("+PT15M", isAllDay = false)
        // Either parses correctly or returns default. Both are acceptable.
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

        // Local: Duration.parse does NOT trim. Falls through.
        // The "no T" branch removes "P" then checks endsWith("D"/"W") — won't match.
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

        // Local: Duration.parse("PT30S") works — PT30S has a T.
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
        // RFC 5545 requires at least one component. Behavior is undefined.
        // Document what each does without asserting correctness.
        val localResult = parseDurationMs("P", isAllDay = false)
        val canonicalResult = DurationUtils.parse("P")
        // Local: "P" has no T, removePrefix("P") gives "", no match on D/W → default.
        assertEquals("local falls back to default", 3_600_000L, localResult)
        // Canonical: doc says either null or zero; both acceptable.
        if (canonicalResult != null) {
            assertEquals(0L, canonicalResult.toSeconds())
        }
    }

    /**
     * DIVERGENCE 5: mixed weeks+days (RFC 5545 §3.3.6 disallows this — `dur-week` is exclusive).
     *
     * Local `parseDurationMs("P1W1D")` goes through the "no T" branch:
     *   1. startsWith("P") = true, !contains("T") = true
     *   2. removePrefix("P") → "1W1D"
     *   3. endsWith("W")? No.
     *   4. endsWith("D")? Yes.
     *   5. removeSuffix("D") → "1W1"
     *   6. "1W1".toLongOrNull() → null → `days = 1` (silent fallback)
     *   7. Returns 86,400,000 ms (1 day) — silently corrupting the input.
     *
     * Canonical `DurationUtils.parse("P1W1D")`: endsWith("W") is false, falls through to
     * day/hour/minute regex: finds 1D via regex, ignores the W. Returns 1 day.
     *
     * Both produce 1 day for this malformed input — by coincidence. Worth documenting because
     * the local helper's `?: 1` silent fallback is a lurking footgun on other malformed inputs.
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
        // Local: Duration.parse handles "-PT15M" directly. But "-P1D"?
        val localNegDay = try {
            Duration.parse("-P1D").toMillis()
        } catch (e: Exception) {
            null
        }
        val canonicalNegDay = DurationUtils.parse("-P1D")?.toMillis()
        // If local can parse it, both should agree.
        if (localNegDay != null) {
            assertEquals(localNegDay, canonicalNegDay)
        } else {
            // Local can't — parseDurationMs has its own -P1D handling? Check.
            // Reading the code: parseDurationMs for "-P1D" — no T — removePrefix("P")
            // gives "-P1D" (wait, -P1D has leading -, removePrefix("P") on "-P1D" removes
            // nothing because it doesn't START with P). Actually the if guards on
            // startsWith("P") so "-P1D" takes the else branch (Duration.parse), which
            // handles it if ISO compliant.
            assertNotNull("canonical handles -P1D", canonicalNegDay)
        }
    }

    // ========================================================================
    // PARSE ROUND-TRIP with FORMAT
    // ========================================================================

    /**
     * The critical property: for every duration we actually emit via computeDurationString,
     * DurationUtils.parse must round-trip it to the same milliseconds. If this holds, the
     * swap is safe even if the output bytes differ (divergences 1-4).
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
