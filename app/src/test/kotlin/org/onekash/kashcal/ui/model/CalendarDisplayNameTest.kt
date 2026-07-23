package org.onekash.kashcal.ui.model

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.R
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.domain.initializer.LocalCalendarInitializer
import org.onekash.kashcal.domain.model.AccountProvider
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CalendarDisplayNameTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    private fun calendar(caldavUrl: String, displayName: String) =
        Calendar(id = 1L, accountId = 1L, caldavUrl = caldavUrl, displayName = displayName, color = 0xFF0000)

    @Test
    fun `local calendar name is localized from resources`() {
        val cal = calendar(LocalCalendarInitializer.LOCAL_CALENDAR_URL, "Local")

        assertEquals(
            context.getString(R.string.calendar_local),
            cal.localizedDisplayName(context.resources)
        )
    }

    @Test
    fun `synced calendar keeps its server display name`() {
        val cal = calendar("https://caldav.example.test/calendars/work/", "Work")

        assertEquals("Work", cal.localizedDisplayName(context.resources))
    }

    @Test
    fun `contact calendar falls through to stored display name`() {
        // Contact CALENDAR urls (local://contact_birthdays) are not matched by
        // ContactEventType.fromCaldavUrl (which matches event prefixes), so the
        // stored name is returned unchanged - documenting preserved behavior.
        val cal = calendar("local://contact_birthdays", "Contact Birthdays")

        assertEquals("Contact Birthdays", cal.localizedDisplayName(context.resources))
    }

    @Test
    fun `grouped local calendar carries localized name through the picker wiring`() {
        // Proves the expanded-dropdown surface: the calendar name substituted into
        // the group is the localized value, not the raw stored "Local".
        val account = Account(
            id = 1L,
            provider = AccountProvider.LOCAL,
            email = LocalCalendarInitializer.LOCAL_EMAIL,
            displayName = LocalCalendarInitializer.LOCAL_ACCOUNT_DISPLAY_NAME
        )
        val localCal = calendar(LocalCalendarInitializer.LOCAL_CALENDAR_URL, "Local")

        val groups = CalendarGroup.fromCalendarsAndAccounts(
            calendars = listOf(localCal),
            accounts = listOf(account),
            localLabel = "Offline",
            icsLabel = "Calendar Feeds",
            localizeCalendarName = { it.localizedDisplayName(context.resources) }
        )

        val groupedName = groups.single().calendars.single().displayName
        assertEquals(context.getString(R.string.calendar_local), groupedName)
    }
}
