package org.onekash.kashcal.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose tests for [OnboardingSheet]: its copy, one callback per row and for the offline
 * button, the title as a heading, and each row as one button that reads its source and detail
 * together. Run the class alone: Robolectric runs of more than one class hit a native crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h800dp-mdpi")
class OnboardingSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun show(motionEnabled: Boolean = true) {
        composeTestRule.setContent {
            MaterialTheme {
                OnboardingSheet(
                    onICloud = { calls += "icloud" },
                    onCalDav = { calls += "caldav" },
                    onDeviceCalendars = { calls += "device" },
                    onUseOffline = { calls += "offline" },
                    onDismiss = { calls += "dismiss" },
                    motionEnabled = motionEnabled,
                )
            }
        }
    }

    @Test
    fun `the sheet asks where the calendars are and offers three sources`() {
        show()

        composeTestRule.onNodeWithText("Where are your calendars?").assertExists()
        composeTestRule.onNodeWithText("Pick one to connect.").assertExists()
        composeTestRule.onNodeWithText("iCloud").assertExists()
        composeTestRule.onNodeWithText("Apple ID and app password").assertExists()
        composeTestRule.onNodeWithText("CalDAV server").assertExists()
        composeTestRule.onNodeWithText("Nextcloud, Fastmail, Radicale, and others").assertExists()
        composeTestRule.onNodeWithText("On this device").assertExists()
        composeTestRule.onNodeWithText("Google, Outlook, and sync apps").assertExists()
        composeTestRule.onNodeWithText("Use offline calendar").assertExists()
        composeTestRule.onNodeWithText("Open source. No ads. No tracking.").assertExists()
        composeTestRule.onNodeWithText("Apple Calendar?").assertDoesNotExist()
    }

    @Test
    fun `each row and the offline button call their own callback once`() {
        show()

        composeTestRule.onNodeWithText("iCloud").performClick()
        composeTestRule.onNodeWithText("CalDAV server").performClick()
        composeTestRule.onNodeWithText("On this device").performClick()
        composeTestRule.onNodeWithText("Use offline calendar").performClick()

        assertEquals(listOf("icloud", "caldav", "device", "offline"), calls)
    }

    @Test
    fun `with motion off the rows can be tapped on the first frame`() {
        composeTestRule.mainClock.autoAdvance = false
        show(motionEnabled = false)
        composeTestRule.mainClock.advanceTimeByFrame()

        composeTestRule.onNodeWithText("CalDAV server").performClick()

        assertEquals(listOf("caldav"), calls)
    }

    @Test
    fun `the question is a heading`() {
        show()

        composeTestRule.onNodeWithText("Where are your calendars?").assert(isHeading())
    }

    @Test
    fun `each row is one button that reads its source and detail together`() {
        show()

        composeTestRule.onNode(
            hasText("CalDAV server") and hasText("Nextcloud, Fastmail, Radicale, and others") and
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        ).assertExists()
    }

    @Test
    fun `Kash adds nothing for TalkBack to read`() {
        show(motionEnabled = true)
        composeTestRule.onNodeWithText("Where are your calendars?").assertExists()
        composeTestRule.assertNoImageForTalkBack()
    }
}
