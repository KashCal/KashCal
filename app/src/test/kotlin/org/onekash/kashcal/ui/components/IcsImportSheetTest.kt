package org.onekash.kashcal.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.ics.ImportGroup
import org.onekash.kashcal.data.ics.ImportedAttendee
import org.onekash.kashcal.data.ics.ImportedComponent
import org.onekash.kashcal.domain.ics.GroupPlan
import org.onekash.kashcal.domain.initializer.LocalCalendarInitializer
import org.onekash.kashcal.domain.ics.ImportAction
import org.onekash.kashcal.domain.ics.StoredComponent
import org.onekash.kashcal.testutil.plainImportFile
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Tests [IcsImportSheet], the ICS-file import bottom sheet: its Import button, and the preview
 * and inline notices it shows for the chosen calendar before the tap.
 *
 * A large import runs for seconds while the sheet stays open. If each tap started an import,
 * repeated taps would enqueue one full import each and duplicate every event (#309), so tapping
 * Import must fire the import callback exactly once per sheet session.
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
        caldavUrl = LocalCalendarInitializer.LOCAL_CALENDAR_URL,
        displayName = "Personal",
        color = 0xFF4CAF50.toInt(),
        isVisible = true,
    )

    private val events = listOf(
        Event(uid = "a", calendarId = 1L, title = "Standup", startTs = 1_000, endTs = 4_600_000, timezone = "UTC", dtstamp = 1_000),
        Event(uid = "b", calendarId = 1L, title = "Dentist", startTs = 5_000, endTs = 9_600_000, timezone = "UTC", dtstamp = 1_000),
    )

    private val syncedCalendar = calendar.copy(id = 2L, caldavUrl = "https://dav.example.test/cal/", displayName = "Work")

    private fun render(
        target: Calendar = calendar,
        offlineCalendarIds: Set<Long> = setOf(calendar.id),
        plans: List<GroupPlan> = emptyList(),
        loadPreview: suspend (Long, Boolean) -> List<GroupPlan> = { _, _ -> plans },
        onImport: (Long, Boolean) -> Unit = { _, _ -> },
    ) {
        Locale.setDefault(Locale.US)
        composeTestRule.setContent {
            MaterialTheme {
                IcsImportSheet(
                    file = plainImportFile(events),
                    calendars = listOf(target),
                    defaultCalendarId = target.id,
                    deviceCalendarGroups = emptyList(),
                    defaultDeviceCalendarId = null,
                    offlineCalendarIds = offlineCalendarIds,
                    loadPreview = loadPreview,
                    onDismiss = {},
                    onImport = onImport,
                )
            }
        }
    }

    private val invitation = ImportedComponent(
        events[0].copy(organizerEmail = "boss@example.test"),
        listOf(ImportedAttendee("mailto:me@example.test", null, null, "NEEDS-ACTION", null, true)),
    )

    private fun plan(action: ImportAction, isInvitation: Boolean = true, attendee: Boolean = true) =
        GroupPlan(uid = "a", actions = listOf(action), isInvitation = isInvitation, userIsOrganizer = false, userIsAttendee = attendee)

    private fun cancelPlan() = plan(
        ImportAction.CancelSeries(
            StoredComponent(1L, null, "boss@example.test", 0, null, "CONFIRMED", false), emptyList(), invitation,
        )
    )

    @Test
    fun `tapping Import twice fires the import callback exactly once`() {
        var importCount = 0
        render(onImport = { _, _ -> importCount++ })

        // Locate by tag, not label: the first tap relabels the button to "Importing…",
        // so a text locator would miss it on the second tap and mask a regression.
        composeTestRule.onNodeWithTag(ICS_IMPORT_BUTTON_TAG).performClick()
        composeTestRule.onNodeWithTag(ICS_IMPORT_BUTTON_TAG).performClick()

        assertEquals("a second tap during the import must be ignored", 1, importCount)
    }

    @Test
    fun `Import button shows the in-progress label after the first tap`() {
        render()

        composeTestRule.onNodeWithTag(ICS_IMPORT_BUTTON_TAG).performClick()

        // The button stays visually enabled (so its spinner keeps full contrast); the
        // label swaps to the in-progress status and the onClick latch blocks re-taps.
        composeTestRule.onNodeWithText("Importing…").assertExists()
    }

    @Test
    fun `the sheet says what it cancels before the tap`() {
        render(target = syncedCalendar, plans = listOf(cancelPlan()))

        composeTestRule.onNodeWithText("This cancels Standup, organized by boss@example.test").assertExists()
    }

    @Test
    fun `an invitation into a synced calendar shows dismissible notices`() {
        render(target = syncedCalendar, plans = listOf(plan(ImportAction.Create(ImportGroup("a", listOf(invitation), emptyList()), false))))

        composeTestRule.onNodeWithText("Answering this invitation may notify the organizer.").assertExists()
        composeTestRule.onNodeWithText("The sender of an invitation file can't be verified. Check it came from the organizer.").assertExists()
        composeTestRule.onAllNodesWithContentDescription("Dismiss notice")[0].performClick()
        composeTestRule.onNodeWithText("The sender of an invitation file can't be verified. Check it came from the organizer.").assertDoesNotExist()
    }

    @Test
    fun `the on-phone calendar shows no notices`() {
        render(plans = listOf(plan(ImportAction.Create(ImportGroup("a", listOf(invitation), emptyList()), false))))

        composeTestRule.onAllNodesWithContentDescription("Dismiss notice").assertCountEquals(0)
    }

    @Test
    fun `a second offline calendar shows no notices`() {
        val second = calendar.copy(id = 3L, caldavUrl = "local://calendar/9b2e7c1d-4f3a-4e8b-a1c2-d3e4f5a6b7c8", displayName = "Work")

        render(
            target = second,
            offlineCalendarIds = setOf(calendar.id, second.id),
            plans = listOf(plan(ImportAction.Create(ImportGroup("a", listOf(invitation), emptyList()), false))),
        )

        composeTestRule.onAllNodesWithContentDescription("Dismiss notice").assertCountEquals(0)
    }

    @Test
    fun `a preview that fails still leaves Import available`() {
        var imported = false
        render(loadPreview = { _, _ -> error("unreadable") }, onImport = { _, _ -> imported = true })

        composeTestRule.onNodeWithText("Couldn't check this calendar for these events. You can still import.").assertExists()
        composeTestRule.onNodeWithTag(ICS_IMPORT_BUTTON_TAG).performClick()
        assertEquals(true, imported)
    }
}
