package org.onekash.kashcal.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Tests for [IcsImportSheet] — the ICS-file import bottom sheet.
 *
 * The load-bearing regression: a large import runs for several seconds while the
 * sheet stays open, and the Import button used to remain tappable throughout, so
 * repeated taps enqueued one full import each and duplicated every event (#309).
 * Tapping Import must fire the import callback exactly once per sheet session.
 *
 * Runs under Robolectric; run the class in isolation given the repo's multi-class
 * native-crash flake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class IcsImportSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val calendar = Calendar(
        id = 1L,
        accountId = 100L,
        caldavUrl = "local://d",
        displayName = "Personal",
        color = 0xFF4CAF50.toInt(),
        isVisible = true,
    )

    private val events = listOf(
        Event(uid = "a", calendarId = 1L, title = "Standup", startTs = 1_000, endTs = 4_600_000, timezone = "UTC", dtstamp = 1_000),
        Event(uid = "b", calendarId = 1L, title = "Dentist", startTs = 5_000, endTs = 9_600_000, timezone = "UTC", dtstamp = 1_000),
    )

    private fun render(onImport: (Long, List<Event>, Boolean) -> Unit) {
        Locale.setDefault(Locale.US)
        composeTestRule.setContent {
            MaterialTheme {
                IcsImportSheet(
                    events = events,
                    calendars = listOf(calendar),
                    defaultCalendarId = calendar.id,
                    deviceCalendarGroups = emptyList(),
                    defaultDeviceCalendarId = null,
                    onDismiss = {},
                    onImport = onImport,
                )
            }
        }
    }

    @Test
    fun `tapping Import twice fires the import callback exactly once`() {
        var importCount = 0
        render { _, _, _ -> importCount++ }

        // Locate by tag, not label: the first tap relabels the button to "Importing…",
        // so a text locator would miss it on the second tap and mask a regression.
        composeTestRule.onNodeWithTag(ICS_IMPORT_BUTTON_TAG).performClick()
        composeTestRule.onNodeWithTag(ICS_IMPORT_BUTTON_TAG).performClick()

        assertEquals("a second tap during the import must be ignored", 1, importCount)
    }

    @Test
    fun `Import button shows the in-progress label after the first tap`() {
        render { _, _, _ -> }

        composeTestRule.onNodeWithTag(ICS_IMPORT_BUTTON_TAG).performClick()

        // The button stays visually enabled (so its spinner keeps full contrast); the
        // label swaps to the in-progress status and the onClick latch blocks re-taps.
        composeTestRule.onNodeWithText("Importing…").assertExists()
    }
}
