package org.onekash.kashcal.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetStateKeysTest {

    @Test
    fun `WIDGET_REFRESH_STAMP key name is widget_refresh_stamp`() {
        assertEquals("widget_refresh_stamp", WIDGET_REFRESH_STAMP.name)
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
