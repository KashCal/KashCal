package org.onekash.kashcal.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose tests for [RemindersOffBanner]: its title, the plural count, and one callback per
 * button. Run the class alone: Robolectric runs of more than one class hit a native crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h800dp-mdpi")
class RemindersOffBannerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun show(count: Int) {
        composeTestRule.setContent {
            MaterialTheme {
                RemindersOffBanner(
                    eventCount = count,
                    onTurnOn = { calls += "on" },
                    onNotNow = { calls += "not now" },
                )
            }
        }
    }

    @Test
    fun `the banner names the problem and counts the events`() {
        show(4)

        composeTestRule.onNodeWithText("Reminders are off").assertExists()
        composeTestRule.onNodeWithText("4 events this week have reminders.").assertExists()
    }

    @Test
    fun `one event reads in the singular`() {
        show(1)

        composeTestRule.onNodeWithText("1 event this week has a reminder.").assertExists()
    }

    @Test
    fun `each button calls its own callback once`() {
        show(2)

        composeTestRule.onNodeWithText("Turn on").performClick()
        composeTestRule.onNodeWithContentDescription("Not now").assertTouchHeightIsEqualTo(48.dp).performClick()

        assertEquals(listOf("on", "not now"), calls)
    }
}
