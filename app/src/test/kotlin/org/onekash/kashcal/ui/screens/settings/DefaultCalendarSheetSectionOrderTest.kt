package org.onekash.kashcal.ui.screens.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.DeviceCalendar
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.preferences.DefaultCalendar
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.ui.model.CalendarGroup
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Settings > Default calendar lists accounts by name, then the device calendars, then the
 * Offline calendars, with a divider before a section only when something is listed above it,
 * and marks the current default in whichever section it is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h9999dp-mdpi")
class DefaultCalendarSheetSectionOrderTest {

    @get:Rule
    val rule = createComposeRule()

    private val savedLocale = Locale.getDefault()

    @Before
    fun pinLocale() = Locale.setDefault(Locale.US)

    @After
    fun restoreLocale() = Locale.setDefault(savedLocale)

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
        currentDefault: DefaultCalendar? = null,
        onSelect: (DefaultCalendar) -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        rule.setContent {
            MaterialTheme {
                DefaultCalendarSheetContent(
                    calendarGroups = groups,
                    deviceCalendarGroups = deviceGroups,
                    currentDefault = currentDefault,
                    onSelectDefault = onSelect,
                    onDismiss = onDismiss,
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
    fun `accounts, then device calendars, then Offline last`() {
        show(allRoom, device)

        assertEquals(1, dividerCount(TAG_DEFAULT_CALENDAR_DEVICE_DIVIDER))
        assertEquals(1, dividerCount(TAG_DEFAULT_CALENDAR_OFFLINE_DIVIDER))
        assertTopToBottom(
            "iCloud" to top("iCloud"),
            "Personal" to top("Personal"),
            "Zoho" to top("Zoho"),
            "Projects" to top("Projects"),
            "device divider" to dividerTop(TAG_DEFAULT_CALENDAR_DEVICE_DIVIDER),
            "Device calendars" to top("Device calendars"),
            "Phone" to top("Phone"),
            "Family" to top("Family"),
            "offline divider" to dividerTop(TAG_DEFAULT_CALENDAR_OFFLINE_DIVIDER),
            "Offline" to top("Offline"),
            "Garden" to top("Garden"),
            "Local" to top("Local"),
        )
        rule.onNodeWithText("New offline calendar").assertDoesNotExist()
    }

    @Test
    fun `without device calendars one divider sits between the accounts and Offline`() {
        show(allRoom, emptyList())

        assertEquals(0, dividerCount(TAG_DEFAULT_CALENDAR_DEVICE_DIVIDER))
        assertEquals(1, dividerCount(TAG_DEFAULT_CALENDAR_OFFLINE_DIVIDER))
        rule.onNodeWithText("Device calendars").assertDoesNotExist()
        assertTopToBottom(
            "Zoho" to top("Zoho"),
            "Projects" to top("Projects"),
            "offline divider" to dividerTop(TAG_DEFAULT_CALENDAR_OFFLINE_DIVIDER),
            "Offline" to top("Offline"),
            "Local" to top("Local"),
        )
    }

    @Test
    fun `with only offline calendars there is no divider`() {
        show(roomGroups(offlineCalendars), emptyList())

        assertEquals(0, dividerCount(TAG_DEFAULT_CALENDAR_DEVICE_DIVIDER))
        assertEquals(0, dividerCount(TAG_DEFAULT_CALENDAR_OFFLINE_DIVIDER))
        assertTopToBottom("Offline" to top("Offline"), "Local" to top("Local"))
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
    fun `an offline default marks only its Room row when a device calendar shares its id`() {
        show(sharedIdRoom, sharedIdDevice, currentDefault = DefaultCalendar.Room(7))

        assertEquals(
            1, rule.onAllNodes(hasContentDescription("Selected")).fetchSemanticsNodes().size
        )
        assertTrue(rowSelected("Garden"))
    }

    @Test
    fun `a device default marks only its device row when a Room calendar shares its id`() {
        show(sharedIdRoom, sharedIdDevice, currentDefault = DefaultCalendar.Device(7))

        assertEquals(
            1, rule.onAllNodes(hasContentDescription("Selected")).fetchSemanticsNodes().size
        )
        assertTrue(rowSelected("Family"))
    }

    @Test
    fun `tapping an offline row picks it as a Room default and closes the sheet`() {
        val picks = mutableListOf<DefaultCalendar>()
        var dismissed = 0
        show(allRoom, device, onSelect = { picks += it }, onDismiss = { dismissed++ })

        rule.onNodeWithText("Garden").performClick()

        assertEquals(listOf<DefaultCalendar>(DefaultCalendar.Room(30)), picks)
        assertEquals(1, dismissed)
    }

    @Test
    fun `with nothing to list the sheet says so`() {
        show(emptyList(), emptyList())

        rule.onNodeWithText("No calendars available").assertExists()
        assertEquals(0, dividerCount(TAG_DEFAULT_CALENDAR_DEVICE_DIVIDER))
        assertEquals(0, dividerCount(TAG_DEFAULT_CALENDAR_OFFLINE_DIVIDER))
    }
}
