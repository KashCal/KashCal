package org.onekash.kashcal.ui.components

import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for declined-event visual policy.
 *
 * The policy: declined events render at 50% alpha with strikethrough on the
 * title. Past events also render at 50%. When both are true, alpha caps at
 * 0.5f (not multiplied to 0.25f) so declined-and-past events stay legible.
 */
class DeclinedVisualsTest {

    @Test
    fun `not declined and not past returns full alpha and null decoration`() {
        assertEquals(1.0f, declinedCardAlpha(isPast = false, isDeclined = false))
        assertNull(declinedTitleDecoration(isDeclined = false))
    }

    @Test
    fun `past only returns half alpha and null decoration`() {
        assertEquals(0.5f, declinedCardAlpha(isPast = true, isDeclined = false))
        assertNull(declinedTitleDecoration(isDeclined = false))
    }

    @Test
    fun `declined only returns half alpha and LineThrough`() {
        assertEquals(0.5f, declinedCardAlpha(isPast = false, isDeclined = true))
        assertEquals(TextDecoration.LineThrough, declinedTitleDecoration(isDeclined = true))
    }

    @Test
    fun `both flags cap at half alpha not quarter`() {
        assertEquals(0.5f, declinedCardAlpha(isPast = true, isDeclined = true))
        assertEquals(TextDecoration.LineThrough, declinedTitleDecoration(isDeclined = true))
    }
}
