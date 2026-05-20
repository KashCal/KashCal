package org.onekash.kashcal.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the AppBar overflow trigger's contentDescription policy. The
 * helper takes the raw count + already-resolved strings (base label,
 * formatted plural with-count form) so it stays Context-free and
 * the test can run without Robolectric.
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
