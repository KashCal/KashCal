package org.onekash.kashcal.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.util.ExternalLinks
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose tests for [AboutSheet]: its copy and version, the app name as a heading, the link each
 * row opens, each row as one button that says it opens the browser, the line shown when no app
 * opens a link, and the footer staying on a short screen. Run the class alone: Robolectric runs
 * of more than one class hit a native crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h800dp-mdpi")
class AboutSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val opened = mutableListOf<String>()
    private var linkOpens = true

    private fun show(motionEnabled: Boolean = true) {
        composeTestRule.setContent {
            MaterialTheme {
                AboutSheet(
                    versionName = "2026.10.30-2",
                    onDismiss = {},
                    motionEnabled = motionEnabled,
                    onOpenLink = { url -> opened += url; linkOpens },
                )
            }
        }
    }

    @Test
    fun `the sheet names the app, says what it is and shows the version`() {
        show()

        composeTestRule.onNode(hasText("KashCal") and isHeading()).assertExists()
        composeTestRule.onNodeWithText("All your calendars in one private app.").assertExists()
        composeTestRule.onNodeWithText("Help and guides").assertExists()
        composeTestRule.onNodeWithText("Setup, sync and questions").assertExists()
        composeTestRule.onNodeWithText("Source code").assertExists()
        composeTestRule.onNodeWithText("Apache 2.0, on GitHub").assertExists()
        composeTestRule.onNodeWithText("Support KashCal").assertExists()
        composeTestRule.onNodeWithText("Thank you to everyone who already does").assertExists()
        composeTestRule.onNodeWithText("Built with ❤️ in Austin").assertExists()
        composeTestRule.onNodeWithText("Version 2026.10.30-2").assertExists()
    }

    @Test
    fun `each row opens its page once, in order`() {
        show()

        composeTestRule.onNodeWithText("Help and guides").performClick()
        composeTestRule.onNodeWithText("Source code").performClick()
        composeTestRule.onNodeWithText("Support KashCal").performClick()

        assertEquals(listOf(ExternalLinks.HELP, ExternalLinks.SOURCE, ExternalLinks.DONATE), opened)
    }

    @Test
    fun `each row is one button that says it opens the browser`() {
        show()

        for ((title, detail) in listOf(
            "Help and guides" to "Setup, sync and questions",
            "Source code" to "Apache 2.0, on GitHub",
            "Support KashCal" to "Thank you to everyone who already does",
        )) {
            composeTestRule.onNode(
                hasClickAction() and
                    hasContentDescription("$title, $detail, Opens in browser") and
                    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
            ).assertExists()
        }
    }

    @Test
    fun `a link no app opens says so until a link opens`() {
        linkOpens = false
        show()
        val liveRegion =
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
        // The live region is there before anything fails, so TalkBack announces the change.
        composeTestRule.onNode(liveRegion).assertExists()

        composeTestRule.onNodeWithText("Source code").performClick()
        composeTestRule.onNode(liveRegion and hasText("No app can open this link.")).assertExists()

        linkOpens = true
        composeTestRule.onNodeWithText("Help and guides").performClick()
        composeTestRule.onNodeWithText("No app can open this link.").assertDoesNotExist()
    }

    @Test
    fun `with motion off the sheet shows the same content`() {
        show(motionEnabled = false)

        composeTestRule.onNodeWithText("All your calendars in one private app.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Support KashCal").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "en-rUS-w360dp-h420dp-mdpi")
    fun `on a short screen the rows scroll and the footer stays shown`() {
        show(motionEnabled = false)

        composeTestRule.onNodeWithText("Version 2026.10.30-2").assertIsDisplayed()
        composeTestRule.onNodeWithText("Support KashCal").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Version 2026.10.30-2").assertIsDisplayed()
    }

    @Test
    fun `Kash adds nothing for TalkBack to read`() {
        show()
        composeTestRule.assertNoImageForTalkBack()
    }
}
