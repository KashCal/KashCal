package org.onekash.kashcal.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests the drawer's calendar row ([CalendarCheckboxRow]): it stays a 48dp touch
 * target and a tap on it toggles the calendar. The row's small vertical padding
 * isn't asserted; any change to it must keep these two properties. The 48dp floor
 * comes from the Material3 Checkbox's minimum interactive size.
 *
 * The row is rendered in isolation, not in the drawer, so a caller that wrapped
 * the drawer in a provider disabling minimum-interactive enforcement would not
 * be caught.
 *
 * Runs under Robolectric; run in isolation given the repo's multi-class
 * native-crash flake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class CalendarDrawerRowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `calendar row stays at least 48dp tall`() {
        composeTestRule.setContent {
            MaterialTheme {
                CalendarCheckboxRow(
                    name = "Work",
                    color = Color(0xFF3F51B5),
                    checked = true,
                    onClick = {},
                )
            }
        }
        // The clickable Row merges its descendants' semantics, so the "Work"
        // node's bounds are the whole row's bounds.
        composeTestRule.onNodeWithText("Work").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `tapping the row toggles the calendar`() {
        var clicked = false
        composeTestRule.setContent {
            MaterialTheme {
                CalendarCheckboxRow(
                    name = "Personal",
                    color = Color(0xFF009688),
                    checked = false,
                    onClick = { clicked = true },
                )
            }
        }
        composeTestRule.onNodeWithText("Personal").performClick()
        assertTrue("Tapping the row body should fire onClick", clicked)
    }
}
