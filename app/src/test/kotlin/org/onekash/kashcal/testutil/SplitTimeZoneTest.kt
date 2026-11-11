package org.onekash.kashcal.testutil

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

/**
 * Pins what [withSplitDeviceTimeZone] models: java.util gives the fixed offset, java.time the
 * zone's real rules, and the previous default comes back afterwards.
 */
class SplitTimeZoneTest {

    private val berlinSummer = Instant.parse("2026-07-01T12:00:00Z")
    private val sydneyWinter = Instant.parse("2026-06-15T02:00:00Z")

    private fun javaUtilOffsetMinutes(at: Instant): Int =
        TimeZone.getDefault().getOffset(at.toEpochMilli()) / 60_000

    private fun javaTimeOffsetMinutes(at: Instant): Int =
        ZoneId.systemDefault().rules.getOffset(at).totalSeconds / 60

    @Test
    fun `split Berlin gives java util no summer time while java time keeps it`() {
        withSplitDeviceTimeZone(SplitZone.BERLIN) {
            assertEquals("Europe/Berlin", ZoneId.systemDefault().id)
            assertEquals(60, javaUtilOffsetMinutes(berlinSummer))
            assertEquals(120, javaTimeOffsetMinutes(berlinSummer))
        }
    }

    @Test
    fun `split New York gives java util standard time in summer`() {
        withSplitDeviceTimeZone(SplitZone.NEW_YORK) {
            assertEquals(-300, javaUtilOffsetMinutes(berlinSummer))
            assertEquals(-240, javaTimeOffsetMinutes(berlinSummer))
        }
    }

    @Test
    fun `split Sydney keeps java util on summer time in June`() {
        withSplitDeviceTimeZone(SplitZone.SYDNEY) {
            assertEquals(660, javaUtilOffsetMinutes(sydneyWinter))
            assertEquals(600, javaTimeOffsetMinutes(sydneyWinter))
        }
    }

    @Test
    fun `control zones agree between the two engines`() {
        for (zone in listOf(SplitZone.TOKYO, SplitZone.UTC)) {
            withSplitDeviceTimeZone(zone) {
                for (at in listOf(berlinSummer, sydneyWinter)) {
                    assertEquals(zone.name, javaTimeOffsetMinutes(at), javaUtilOffsetMinutes(at))
                }
            }
        }
    }

    @Test
    fun `the previous default zone comes back after a return and after a throw`() {
        // getDefault returns a copy each call, so the restored zone is compared by id and rules.
        withDeviceTimeZone("Asia/Kolkata") {
            withSplitDeviceTimeZone(SplitZone.BERLIN) { }
            assertRestoredToKolkata()
            assertThrows(IllegalStateException::class.java) {
                withSplitDeviceTimeZone(SplitZone.NEW_YORK) { error("boom") }
            }
            assertRestoredToKolkata()
        }
    }

    private fun assertRestoredToKolkata() {
        val restored = TimeZone.getDefault()
        assertEquals("Asia/Kolkata", restored.id)
        assertTrue(restored.hasSameRules(TimeZone.getTimeZone("Asia/Kolkata")))
    }
}
