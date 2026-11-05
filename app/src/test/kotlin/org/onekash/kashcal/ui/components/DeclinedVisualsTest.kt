package org.onekash.kashcal.ui.components

import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests [declinedCardAlpha] and [declinedTitleDecoration].
 *
 * Declined and cancelled events render at [DEEMPHASIZED_ALPHA] with strikethrough on
 * the title. Past events also render at [DEEMPHASIZED_ALPHA]. When several apply, the
 * alpha stays at [DEEMPHASIZED_ALPHA] (not multiplied) so the event stays legible.
 */
class DeclinedVisualsTest {

    @Test
    fun `dimmed alpha is 0_65 so dimmed text passes WCAG AA in light and dark`() {
        // #426: 0.5 gave onSurface text 3.3:1 (light) and 4.4:1 (dark) on the M3
        // default surfaces, below AA's 4.5:1. 0.65 gives 5.2:1 and 6.6:1.
        assertEquals(0.65f, DEEMPHASIZED_ALPHA)
    }

    @Test
    fun `not declined and not past returns full alpha and null decoration`() {
        assertEquals(1.0f, declinedCardAlpha(isPast = false, isDeclined = false))
        assertNull(declinedTitleDecoration(isDeclined = false))
    }

    @Test
    fun `past only returns dimmed alpha and null decoration`() {
        assertEquals(DEEMPHASIZED_ALPHA, declinedCardAlpha(isPast = true, isDeclined = false))
        assertNull(declinedTitleDecoration(isDeclined = false))
    }

    @Test
    fun `declined only returns dimmed alpha and LineThrough`() {
        assertEquals(DEEMPHASIZED_ALPHA, declinedCardAlpha(isPast = false, isDeclined = true))
        assertEquals(TextDecoration.LineThrough, declinedTitleDecoration(isDeclined = true))
    }

    @Test
    fun `both flags stay at dimmed alpha not compounded`() {
        assertEquals(DEEMPHASIZED_ALPHA, declinedCardAlpha(isPast = true, isDeclined = true))
        assertEquals(TextDecoration.LineThrough, declinedTitleDecoration(isDeclined = true))
    }

    @Test
    fun `cancelled only returns dimmed alpha and LineThrough`() {
        assertEquals(DEEMPHASIZED_ALPHA, declinedCardAlpha(isPast = false, isDeclined = false, isCancelled = true))
        assertEquals(TextDecoration.LineThrough, declinedTitleDecoration(isDeclined = false, isCancelled = true))
    }

    @Test
    fun `all three flags stay at dimmed alpha not lower`() {
        assertEquals(DEEMPHASIZED_ALPHA, declinedCardAlpha(isPast = true, isDeclined = true, isCancelled = true))
        assertEquals(TextDecoration.LineThrough, declinedTitleDecoration(isDeclined = true, isCancelled = true))
    }

    @Test
    fun `cancelled defaults keep existing callers unchanged`() {
        // isCancelled defaults to false, so a call that omits it gets the
        // declined/past result.
        assertEquals(1.0f, declinedCardAlpha(isPast = false, isDeclined = false))
        assertNull(declinedTitleDecoration(isDeclined = false))
    }
}
