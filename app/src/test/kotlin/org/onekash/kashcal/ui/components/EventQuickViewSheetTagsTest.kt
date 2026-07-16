package org.onekash.kashcal.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.Event
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI tests for the read-only tag row in [EventQuickViewSheet].
 * (Recurring-detection logic lives in the sibling EventQuickViewSheetTest.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h720dp-mdpi")
class EventQuickViewSheetTagsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun event(categories: List<String>?) = Event(
        uid = "u1",
        calendarId = 1,
        title = "Standup",
        startTs = 1_000,
        endTs = 4_600_000,
        timezone = "UTC",
        categories = categories,
        dtstamp = 1_000,
    )

    private fun render(categories: List<String>?) {
        composeTestRule.setContent {
            MaterialTheme {
                EventQuickViewSheet(
                    event = event(categories),
                    calendarColor = 0xFF4CAF50.toInt(),
                    calendarName = "Local",
                    onDismiss = {},
                    onEdit = {},
                    onDeleteSingle = {},
                )
            }
        }
    }

    private fun countWithText(text: String): Int =
        composeTestRule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().size

    @Test
    fun shows_tag_chips_for_tagged_event() {
        render(listOf("Work", "Focus"))
        assertEquals(1, countWithText("Work"))
        assertEquals(1, countWithText("Focus"))
    }

    @Test
    fun no_editable_new_tag_affordance_in_read_only_quick_view() {
        render(listOf("Work"))
        // "New tag" is the editable +New affordance; must never appear here.
        assertEquals(0, countWithText("New tag"))
    }
}
