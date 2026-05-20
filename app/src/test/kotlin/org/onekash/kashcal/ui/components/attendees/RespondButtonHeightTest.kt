package org.onekash.kashcal.ui.components.attendees

import org.junit.Assert.assertEquals
import org.junit.Test

class RespondButtonHeightTest {

    @Test
    fun `respond pill height is 32 dp`() {
        assertEquals(32f, RESPOND_PILL_HEIGHT_DP.value, 0f)
    }
}
