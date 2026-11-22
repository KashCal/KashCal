package org.onekash.kashcal.ui.screens.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.domain.model.LocalCalendarSummary
import org.onekash.kashcal.ui.components.topFraction
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * How an offline calendar's sheet sits on a phone-sized screen: open at full height, the title
 * still when the rename row opens, and a drag down closes it, with the rename row open too. Run
 * the class alone: Robolectric runs of more than one class hit a native crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h640dp-mdpi")
class OfflineCalendarSheetSizeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private var dismissed = 0

    @Before
    fun pinLocale() = Locale.setDefault(Locale.US)

    private fun render() {
        composeTestRule.setContent {
            MaterialTheme {
                OfflineCalendarSheet(
                    summary = LocalCalendarSummary(
                        Calendar(
                            id = 2, accountId = 1, caldavUrl = "local://calendar/trips",
                            displayName = "Trips", color = 0xFFFF6347.toInt(),
                        ),
                        3,
                    ),
                    onRename = { Result.success(Unit) },
                    onRecolor = {},
                    onExport = {},
                    onDismiss = { dismissed++ },
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    // The Rename row shows the name too; the title is the first node with it.
    private fun titleTop() = composeTestRule.topFraction(composeTestRule.onAllNodesWithText("Trips")[0])

    private fun dragDown() {
        composeTestRule.onNodeWithText("Color")
            .performTouchInput { swipeDown(startY = centerY, endY = centerY + 900f, durationMillis = 200) }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `opens at full height`() {
        render()
        assertTrue("title at ${titleTop()}", titleTop() < 0.2f)
    }

    @Test
    fun `opening the rename row leaves the title where it was`() {
        render()
        val before = titleTop()
        composeTestRule.onNodeWithText("Rename").performClick()
        composeTestRule.waitForIdle()
        assertEquals(before, titleTop(), 0.001f)
    }

    @Test
    fun `a drag down closes it`() {
        render()
        dragDown()
        assertEquals(1, dismissed)
    }

    @Test
    fun `a drag down closes it with the rename row open`() {
        render()
        composeTestRule.onNodeWithText("Rename").performClick()
        composeTestRule.waitForIdle()
        dragDown()
        assertEquals(1, dismissed)
    }

    @Test
    fun `Export stays reachable with the wheel open`() {
        render()
        composeTestRule.onNodeWithText("Color").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("More colors").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Done").assertExists()
        composeTestRule.onNodeWithText("Export calendar").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a drag down with the palette open leaves the sheet open`() {
        render()
        composeTestRule.onNodeWithText("Color").performClick()
        composeTestRule.waitForIdle()
        dragDown()
        assertEquals(0, dismissed)
    }
}
