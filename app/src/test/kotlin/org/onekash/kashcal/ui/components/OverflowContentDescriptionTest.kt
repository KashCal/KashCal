package org.onekash.kashcal.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [overflowContentDescription], the top-bar avatar trigger's content description: the
 * base label at a count of 0 or less, else the with-count label. The helper takes
 * already-resolved strings, so the test runs without Robolectric.
 */
class OverflowContentDescriptionTest {

    @Test
    fun `count = 0 returns base label`() {
        val result = overflowContentDescription(
            count = 0,
            baseLabel = "More menu",
            withInvitesLabel = "More menu, 0 invites pending"
        )
        assertEquals("More menu", result)
    }

    @Test
    fun `count = 1 returns plural label`() {
        val result = overflowContentDescription(
            count = 1,
            baseLabel = "More menu",
            withInvitesLabel = "More menu, 1 invite pending"
        )
        assertEquals("More menu, 1 invite pending", result)
    }

    @Test
    fun `count = 5 returns plural label`() {
        val result = overflowContentDescription(
            count = 5,
            baseLabel = "More menu",
            withInvitesLabel = "More menu, 5 invites pending"
        )
        assertEquals("More menu, 5 invites pending", result)
    }
}
