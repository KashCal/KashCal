package org.onekash.kashcal.ui.components

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests the sheets' entrance progress: where it starts with motion on and off, and that it follows
 * the entrance curve unless told otherwise. Run the class alone: Robolectric runs of more than
 * one class hit a native crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SheetRowsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `with motion off an entrance starts at rest`() {
        assertEquals(1f, entranceStartProgress(motionEnabled = false))
        assertEquals(0f, entranceStartProgress(motionEnabled = true))
    }

    @Test
    fun `an entrance follows the sheets' curve by default`() {
        assertEquals(SheetEntranceEasing.transform(0.5f), progressHalfway(easing = null), 0.06f)
    }

    @Test
    fun `an entrance can run at a steady pace`() {
        assertEquals(0.5f, progressHalfway(easing = LinearEasing), 0.06f)
    }

    // Starts a 400 ms entrance with motion on and returns its progress 200 ms in.
    private fun progressHalfway(easing: Easing?): Float {
        var value = -1f
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            val progress = if (easing == null) {
                rememberEntranceProgress(listOf(0L), 400, motionEnabled = true)
            } else {
                rememberEntranceProgress(listOf(0L), 400, motionEnabled = true, easing = easing)
            }
            value = progress.single().value
        }
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.mainClock.advanceTimeBy(200)
        return value
    }
}
