package org.onekash.kashcal.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose tests for [SettingsToggleRow].
 *
 * Verifies the switch-height fix (single-line toggle row matches a single-line
 * [SettingsRow]), that the whole row toggles, and that the optional info
 * tooltip reveals its explanation without toggling the row.
 *
 * Runs under Robolectric; run in isolation given the repo's multi-class
 * native-crash flake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class SettingsToggleRowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `whole row toggles the switch`() {
        var toggled: Boolean? = null
        composeTestRule.setContent {
            MaterialTheme {
                SettingsToggleRow(
                    icon = Icons.Default.DateRange,
                    label = "Show week numbers",
                    checked = false,
                    onCheckedChange = { toggled = it },
                )
            }
        }
        composeTestRule.onNodeWithText("Show week numbers").performClick()
        assertEquals(true, toggled)
    }

    @Test
    fun `single-line toggle row matches single-line SettingsRow height`() {
        composeTestRule.setContent {
            MaterialTheme {
                Column {
                    SettingsRow(
                        icon = Icons.Default.DateRange,
                        label = "Plain row",
                        onClick = {},
                        showDivider = false,
                    )
                    SettingsToggleRow(
                        icon = Icons.Default.DateRange,
                        label = "Toggle row",
                        checked = false,
                        onCheckedChange = {},
                        showDivider = false,
                    )
                }
            }
        }
        val plainHeight = composeTestRule.onNodeWithText("Plain row")
            .fetchSemanticsNode().size.height
        val toggleHeight = composeTestRule.onNodeWithText("Toggle row")
            .fetchSemanticsNode().size.height
        // The switch must not inflate the row above the single-line height.
        assertEquals(
            "Toggle row height should match the plain single-line row",
            plainHeight,
            toggleHeight,
        )
    }

    @Test
    fun `toggle row with an info button stays at single-line height`() {
        composeTestRule.setContent {
            MaterialTheme {
                Column {
                    SettingsRow(
                        icon = Icons.Default.DateRange,
                        label = "Plain row",
                        onClick = {},
                        showDivider = false,
                    )
                    SettingsToggleRow(
                        icon = Icons.Default.DateRange,
                        label = "Info toggle",
                        checked = false,
                        onCheckedChange = {},
                        info = SettingsRowInfo("Info toggle", "Explanation."),
                        showDivider = false,
                    )
                }
            }
        }
        val plainHeight = composeTestRule.onNodeWithText("Plain row")
            .fetchSemanticsNode().size.height
        val infoHeight = composeTestRule.onNodeWithText("Info toggle")
            .fetchSemanticsNode().size.height
        // The ⓘ IconButton must not inflate the row above single-line height.
        assertEquals(
            "Info-button toggle row height should match the plain single-line row",
            plainHeight,
            infoHeight,
        )
    }

    @Test
    fun `info button reveals tooltip text and does not toggle the row`() {
        var toggleCount = 0
        composeTestRule.setContent {
            MaterialTheme {
                SettingsToggleRow(
                    icon = Icons.Default.DateRange,
                    label = "Smart event add",
                    checked = false,
                    onCheckedChange = { toggleCount++ },
                    info = SettingsRowInfo(
                        title = "Smart add",
                        text = "Type naturally to fill in the details.",
                    ),
                )
            }
        }
        // Tapping the ⓘ (content description "About <setting name>") shows the
        // explanation and must NOT toggle the row.
        composeTestRule.onNodeWithContentDescription("About Smart add").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Type naturally to fill in the details.")
            .assertExists()
        assertEquals("Info tap must not toggle the row", 0, toggleCount)
    }

    @Test
    fun `badge renders inline after the label`() {
        composeTestRule.setContent {
            MaterialTheme {
                SettingsToggleRow(
                    label = "Contacts",
                    checked = false,
                    onCheckedChange = {},
                    badge = { BetaBadge() },
                )
            }
        }
        composeTestRule.onNodeWithText("Beta").assertExists()
    }

    @Test
    fun `row body still toggles when an info button is present`() {
        var toggled: Boolean? = null
        composeTestRule.setContent {
            MaterialTheme {
                SettingsToggleRow(
                    icon = Icons.Default.DateRange,
                    label = "Smart event add",
                    checked = false,
                    onCheckedChange = { toggled = it },
                    info = SettingsRowInfo(
                        title = "Smart event add",
                        text = "Type naturally to fill in the details.",
                    ),
                )
            }
        }
        composeTestRule.onNodeWithText("Smart event add").performClick()
        assertTrue("Row body should still toggle", toggled == true)
    }
}
