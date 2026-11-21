package org.onekash.kashcal.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.ui.viewmodels.EditScope
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Renders [RecurringScopeSheet] and checks [ScopeOption.reason]: a disabled option shows it on
 * one line under its label; an enabled option never does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class RecurringScopeSheetReasonTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val icon = Icons.Default.CalendarToday

    @Test
    fun `a disabled option shows its reason and an enabled one does not`() {
        composeTestRule.setContent {
            MaterialTheme {
                RecurringScopeSheet(
                    title = "Edit repeating event",
                    options = listOf(
                        ScopeOption(EditScope.THIS_EVENT, "This event", icon, enabled = true, reason = "enabled reason"),
                        ScopeOption(EditScope.ALL_EVENTS, "All events", icon, enabled = false, reason = REASON),
                    ),
                    onSelect = {},
                    onCancel = {},
                )
            }
        }

        composeTestRule.onNodeWithText(REASON).assertIsDisplayed()
        assertEquals(0, composeTestRule.onAllNodesWithText("enabled reason").fetchSemanticsNodes().size)
    }

    private companion object {
        const val REASON = "The repeat rule can't follow this change"
    }
}
