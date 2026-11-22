package org.onekash.kashcal.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.components.kash.KashPose
import org.onekash.kashcal.ui.theme.ColorSource
import org.onekash.kashcal.ui.theme.KashCalTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Tests the empty day: which line and Kash pose a date gets, how Kash sizes to the room left by
 * the line, and that only the line is read aloud. Text is measured with native graphics so it
 * wraps as on a phone. Run the class alone: Robolectric runs of more than one class hit a native
 * crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EmptyDayStateTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val lines = intArrayOf(
        R.string.empty_no_events_day_1, R.string.empty_no_events_day_2, R.string.empty_no_events_day_3,
        R.string.empty_no_events_day_4, R.string.empty_no_events_day_5,
    )

    @Test
    fun `each line has its own pose, in order`() {
        assertEquals(EmptyDayLine(R.string.empty_no_events_day_1, KashPose.ASLEEP), emptyDayLine(0))
        assertEquals(EmptyDayLine(R.string.empty_no_events_day_2, KashPose.WINK), emptyDayLine(1))
        assertEquals(EmptyDayLine(R.string.empty_no_events_day_3, KashPose.GIFT), emptyDayLine(2))
        assertEquals(EmptyDayLine(R.string.empty_no_events_day_4, KashPose.CHEER), emptyDayLine(3))
        assertEquals(EmptyDayLine(R.string.empty_no_events_day_5, KashPose.MUG), emptyDayLine(4))
    }

    @Test
    fun `the lines cycle every five days, also before the epoch`() {
        assertEquals(emptyDayLine(0), emptyDayLine(5))
        assertEquals(emptyDayLine(4), emptyDayLine(-1))
        assertEquals(emptyDayLine(0), emptyDayLine(-5))
    }

    @Test
    fun `a date keeps the line it shows today`() {
        for (epochDay in 20_390L until 20_410L) {
            assertEquals(lines[Math.floorMod(epochDay, 5L).toInt()], emptyDayLine(epochDay).phrase)
        }
    }

    @Test
    fun `Kash takes the room left by the line, from 48 to 120 dp wide`() {
        // Kash is 220 by 210 in shape, so a height of h holds a width of h * 220 / 210.
        assertEquals(120.dp, emptyDayKashSize(300.dp))
        assertEquals(120.dp, emptyDayKashSize(115.dp))
        assertEquals(70f * 220f / 210f, emptyDayKashSize(70.dp)!!.value, 0.01f)
        assertEquals(50f * 220f / 210f, emptyDayKashSize(50.dp)!!.value, 0.01f)
        assertNull(emptyDayKashSize(45.dp))
        assertNull(emptyDayKashSize(0.dp))
    }

    @Test
    fun `with room, Kash shows above the line and only the line is read`() {
        show(epochDay = 1, height = 500)
        composeTestRule.onNodeWithText("No plans. That's the plan.").assertIsDisplayed()
        val kash = composeTestRule.onNodeWithTag(EMPTY_DAY_KASH_TAG).fetchSemanticsNode().boundsInRoot
        val line = composeTestRule.onNodeWithText("No plans. That's the plan.").fetchSemanticsNode().boundsInRoot
        assertEquals(120f, kash.width, 1f)
        assertTrue("Kash above the line", kash.bottom <= line.top)
        assertEquals(
            0,
            composeTestRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
                .fetchSemanticsNodes().size,
        )
    }

    @Test
    fun `in a short panel Kash is left out and the line stays in view`() {
        show(epochDay = 1, height = 120)
        composeTestRule.onNodeWithText("No plans. That's the plan.").assertIsDisplayed()
        composeTestRule.onNodeWithTag(EMPTY_DAY_KASH_TAG).assertDoesNotExist()
        assertInsidePanel("No plans. That's the plan.")
    }

    // The shortest line (epoch day 2), so at double font size it fits a 200 dp panel on its own.
    @Test
    fun `at double font size Kash never pushes the line out of view`() {
        show(epochDay = 2, height = 200, fontScale = 2f)
        composeTestRule.onNodeWithText("A gift: an empty day.").assertIsDisplayed()
        assertInsidePanel("A gift: an empty day.")
        // At this size the line still fits one row, so Kash shrinks into the room left above it.
        val kash = composeTestRule.onNodeWithTag(EMPTY_DAY_KASH_TAG).fetchSemanticsNode().boundsInRoot
        val line = composeTestRule.onNodeWithText("A gift: an empty day.").fetchSemanticsNode().boundsInRoot
        assertTrue("Kash shrunk: ${kash.width}", kash.width < 120f)
        assertTrue("Kash above the line", kash.bottom <= line.top)
    }

    private fun show(epochDay: Long, height: Int, fontScale: Float = 1f) {
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                KashCalTheme(colorSource = ColorSource.SEED) {
                    Box(Modifier.size(360.dp, height.dp).testTag("panel")) { EmptyDayState(epochDay) }
                }
            }
        }
    }

    private fun assertInsidePanel(text: String) {
        val panel = composeTestRule.onNodeWithTag("panel").fetchSemanticsNode().boundsInRoot
        val line = composeTestRule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot
        assertTrue("line $line inside panel $panel", line.top >= panel.top && line.bottom <= panel.bottom)
    }
}
