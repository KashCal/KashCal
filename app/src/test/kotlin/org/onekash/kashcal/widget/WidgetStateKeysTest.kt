package org.onekash.kashcal.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetStateKeysTest {

    @Test
    fun `WIDGET_REFRESH_STAMP key name is widget_refresh_stamp`() {
        assertEquals("widget_refresh_stamp", WIDGET_REFRESH_STAMP.name)
    }

    @Test
    fun `WIDGET_REFRESHING_UNTIL key name is widget_refreshing_until`() {
        assertEquals("widget_refreshing_until", WIDGET_REFRESHING_UNTIL.name)
    }

    @Test
    fun `refresh cue is active before its deadline and inactive at or after it`() {
        val now = 10_000L
        // Deadline in the future -> cue visible.
        assertTrue(isRefreshCueActive(refreshingUntil = now + 800L, nowMs = now))
        // Deadline exactly now or in the past -> cue cleared (self-expiry).
        assertFalse(isRefreshCueActive(refreshingUntil = now, nowMs = now))
        assertFalse(isRefreshCueActive(refreshingUntil = now - 1L, nowMs = now))
    }

    @Test
    fun `refresh cue is inactive when no deadline was ever written`() {
        // A widget that has never been refreshed has no stored deadline; the glyph must read idle,
        // never stuck-on.
        assertFalse(isRefreshCueActive(refreshingUntil = null, nowMs = 10_000L))
        assertFalse(isRefreshCueActive(refreshingUntil = 0L, nowMs = 10_000L))
    }

    @Test
    fun `nextRefreshStamp returns strictly monotonic values across rapid successive calls`() {
        val stamps = (1..1000).map { nextRefreshStamp() }
        val distinct = stamps.toSet()
        assertEquals(
            "All stamps in a tight loop must be distinct (no same-ms collisions)",
            stamps.size,
            distinct.size
        )
        stamps.zipWithNext().forEach { (prev, next) ->
            assertTrue(
                "Stamp $next must be strictly greater than $prev",
                next > prev
            )
        }
    }
}
