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
 * Compose tests for the drawer's calendar row ([CalendarCheckboxRow]).
 *
 * Both tests are regression LOCKS, not red-green drivers: the density tweak
 * (reducing the row's vertical padding) has no natural unit assertion, so the
 * padding value itself is verified by build + on-device measurement. What these
 * tests guard is the property the tightening must never break — the row stays a
 * 48dp-compliant touch target and a tap on it still toggles the calendar. The
 * 48dp floor comes from the Material3 Checkbox's minimum interactive size. The
 * row is rendered in isolation here, so this locks the row composable itself; it
 * does not exercise the drawer's composition, so it would not catch a future
 * caller that wrapped the drawer in a provider disabling minimum-interactive
 * enforcement.
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
