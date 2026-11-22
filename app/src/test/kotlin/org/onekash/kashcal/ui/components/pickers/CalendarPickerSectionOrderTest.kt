package org.onekash.kashcal.ui.components.pickers

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.DeviceCalendar
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.ui.model.CalendarGroup
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * The event form's calendar picker lists accounts by name, then the device calendars, then the
 * Offline calendars with the New offline calendar row last, with a divider before a section only
 * when something is listed above it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class CalendarPickerSectionOrderTest {

    @get:Rule
    val rule = createComposeRule()

    @Before
    fun pinLocale() = Locale.setDefault(Locale.US)

    private val accounts = listOf(
        Account(
            id = 1, provider = AccountProvider.ICLOUD, email = "a@example.test", displayName = "iCloud"
        ),
        Account(
            id = 2, provider = AccountProvider.CALDAV, email = "b@example.test", displayName = "Zoho"
        ),
        Account(id = 3, provider = AccountProvider.LOCAL, email = "local"),
    )

    private fun calendar(id: Long, name: String, accountId: Long) =
        Calendar(id = id, accountId = accountId, caldavUrl = "test://$id", displayName = name, color = 0)

    private fun deviceCalendar(id: Long, name: String) = DeviceCalendar(
        id = id, displayName = name, color = 0, accountName = "Phone",
        accountType = "org.example", visible = true, accessLevel = 700,
    )

    private val offlineCalendars = listOf(calendar(30, "Garden", 3), calendar(31, "Local", 3))

    private fun roomGroups(calendars: List<Calendar>) = CalendarGroup.fromCalendarsAndAccounts(
        calendars, accounts, localLabel = "Offline", icsLabel = "Calendar Feeds",
    )

    private val allRoom = roomGroups(
        listOf(calendar(10, "Personal", 1), calendar(20, "Projects", 2)) + offlineCalendars
    )
    private val device = CalendarGroup.fromDeviceCalendars(listOf(deviceCalendar(70, "Family")))

    private fun show(
        groups: List<CalendarGroup>,
        deviceGroups: List<CalendarGroup>,
        selectedId: Long? = 30L,
        isSelectedDevice: Boolean = false,
        onSelect: (Long, String, Int?, Boolean) -> Unit = { _, _, _, _ -> },
    ) {
        rule.setContent {
            MaterialTheme {
                CalendarPickerContent(
                    selectedCalendarId = selectedId,
                    calendarGroups = groups,
                    deviceCalendarGroups = deviceGroups,
                    isSelectedDeviceCalendar = isSelectedDevice,
                    onSelect = onSelect,
                    onNewOfflineCalendar = {},
                )
            }
        }
    }

    private fun top(text: String) = rule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    private fun dividerTop(tag: String) =
        rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top

    private fun dividerCount(tag: String) =
        rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun assertTopToBottom(vararg tops: Pair<String, Float>) {
        tops.toList().zipWithNext().forEach { (above, below) ->
            assertTrue("${above.first} above ${below.first}", above.second < below.second)
        }
    }

    @Test
    fun `accounts, then device calendars, then Offline with the New row last`() {
        show(allRoom, device)

        assertEquals(1, dividerCount(TAG_PICKER_DEVICE_DIVIDER))
        assertEquals(1, dividerCount(TAG_PICKER_OFFLINE_DIVIDER))
        assertTopToBottom(
            "iCloud" to top("iCloud"),
            "Personal" to top("Personal"),
            "Zoho" to top("Zoho"),
            "Projects" to top("Projects"),
            "device divider" to dividerTop(TAG_PICKER_DEVICE_DIVIDER),
            "Device calendars" to top("Device calendars"),
            "Phone" to top("Phone"),
            "Family" to top("Family"),
            "offline divider" to dividerTop(TAG_PICKER_OFFLINE_DIVIDER),
            "Offline" to top("Offline"),
            "Garden" to top("Garden"),
            "Local" to top("Local"),
            "New offline calendar" to top("New offline calendar"),
        )
    }

    @Test
    fun `without device calendars one divider sits between the accounts and Offline`() {
        show(allRoom, emptyList())

        assertEquals(0, dividerCount(TAG_PICKER_DEVICE_DIVIDER))
        assertEquals(1, dividerCount(TAG_PICKER_OFFLINE_DIVIDER))
        rule.onNodeWithText("Device calendars").assertDoesNotExist()
        assertTopToBottom(
            "Zoho" to top("Zoho"),
            "Projects" to top("Projects"),
            "offline divider" to dividerTop(TAG_PICKER_OFFLINE_DIVIDER),
            "Offline" to top("Offline"),
            "New offline calendar" to top("New offline calendar"),
        )
    }

    @Test
    fun `with only offline calendars there is no divider`() {
        show(roomGroups(offlineCalendars), emptyList())

        assertEquals(0, dividerCount(TAG_PICKER_DEVICE_DIVIDER))
        assertEquals(0, dividerCount(TAG_PICKER_OFFLINE_DIVIDER))
        assertTopToBottom(
            "Offline" to top("Offline"),
            "Local" to top("Local"),
            "New offline calendar" to top("New offline calendar"),
        )
    }

    @Test
    fun `a device edit lists only device calendars, with no divider`() {
        show(emptyList(), device, selectedId = 70L, isSelectedDevice = true)

        assertEquals(0, dividerCount(TAG_PICKER_DEVICE_DIVIDER))
        assertEquals(0, dividerCount(TAG_PICKER_OFFLINE_DIVIDER))
        rule.onNodeWithText("Family").assertExists()
        rule.onNodeWithText("New offline calendar").assertDoesNotExist()
    }

    private val sharedIdRoom = roomGroups(listOf(calendar(7, "Garden", 3)))
    private val sharedIdDevice =
        CalendarGroup.fromDeviceCalendars(listOf(deviceCalendar(7, "Family")))

    /** True when a "Selected" check sits on the row that shows [name]. */
    private fun rowSelected(name: String): Boolean {
        val row =
            rule.onNode(hasText(name), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        return rule.onAllNodes(hasContentDescription("Selected"), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .any { it.boundsInRoot.center.y in (row.top - 24f)..(row.bottom + 24f) }
    }

    @Test
    fun `a Room pick marks only the Room row when a device calendar shares its id`() {
        show(sharedIdRoom, sharedIdDevice, selectedId = 7L, isSelectedDevice = false)

        assertEquals(
            1, rule.onAllNodes(hasContentDescription("Selected")).fetchSemanticsNodes().size
        )
        assertTrue(rowSelected("Garden"))
    }

    @Test
    fun `a device pick marks only the device row when a Room calendar shares its id`() {
        show(sharedIdRoom, sharedIdDevice, selectedId = 7L, isSelectedDevice = true)

        assertEquals(
            1, rule.onAllNodes(hasContentDescription("Selected")).fetchSemanticsNodes().size
        )
        assertTrue(rowSelected("Family"))
    }

    @Test
    fun `tapping an offline row and a device row reports which kind it is`() {
        val picks = mutableListOf<Pair<Long, Boolean>>()
        show(allRoom, device, onSelect = { id, _, _, isDevice -> picks += id to isDevice })

        rule.onNodeWithText("Garden").performClick()
        rule.onNodeWithText("Family").performClick()

        assertEquals(listOf(30L to false, 70L to true), picks)
    }

    @Test
    fun `with nothing to list the picker says so`() {
        show(emptyList(), emptyList())

        rule.onNodeWithText("No calendars available").assertExists()
    }
}
