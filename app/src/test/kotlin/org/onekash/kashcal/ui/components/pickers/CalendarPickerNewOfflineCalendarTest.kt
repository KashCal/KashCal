package org.onekash.kashcal.ui.components.pickers

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.ui.model.CalendarGroup
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * The calendar picker's "New offline calendar" item: the last item of the Offline group when the
 * host can create one, and absent without the callback or without an Offline group.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class CalendarPickerNewOfflineCalendarTest {

    @get:Rule
    val rule = createComposeRule()

    @Before
    fun pinLocale() = Locale.setDefault(Locale.US)

    private fun calendar(id: Long, name: String, accountId: Long) =
        Calendar(id = id, accountId = accountId, caldavUrl = "test://$id", displayName = name, color = 0)

    private val icloud = CalendarGroup(
        accountName = "iCloud",
        accountId = 1L,
        calendars = listOf(calendar(10, "Home", 1)),
        provider = AccountProvider.ICLOUD,
    )
    private val offline = CalendarGroup(
        accountName = "Offline",
        accountId = 2L,
        calendars = listOf(calendar(20, "Local", 2), calendar(21, "Work", 2)),
        provider = AccountProvider.LOCAL,
    )

    private fun show(groups: List<CalendarGroup>, onNew: (() -> Unit)?) {
        rule.setContent {
            MaterialTheme {
                CalendarPickerContent(
                    selectedCalendarId = 20L,
                    calendarGroups = groups,
                    onSelect = { _, _, _, _ -> },
                    onNewOfflineCalendar = onNew,
                )
            }
        }
    }

    private fun top(text: String) = rule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun `the item ends the Offline group and opens creation`() {
        var opened = 0
        show(listOf(offline, icloud), onNew = { opened++ })
        rule.onNodeWithText("New offline calendar").assertIsDisplayed()
        assertTrue("after the offline calendars", top("New offline calendar") > top("Work"))
        assertTrue(
            "after the other accounts, as the last item",
            top("New offline calendar") > top("Home"),
        )

        rule.onNodeWithText("New offline calendar").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `without the callback there is no item`() {
        show(listOf(offline, icloud), onNew = null)
        rule.onNodeWithText("New offline calendar").assertDoesNotExist()
    }

    @Test
    fun `without an Offline group there is no item`() {
        show(listOf(icloud), onNew = {})
        rule.onNodeWithText("New offline calendar").assertDoesNotExist()
    }
}
