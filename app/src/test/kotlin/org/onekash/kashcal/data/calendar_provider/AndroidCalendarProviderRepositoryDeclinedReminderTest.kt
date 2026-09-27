package org.onekash.kashcal.data.calendar_provider

import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Instances
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests [buildUpcomingReminderSelection], the selection behind
 * [AndroidCalendarProviderRepository.getNextUpcomingReminder].
 *
 * The selection is unconditional: the "Show declined events" toggle is a display preference that
 * never reaches this query, so with the toggle on or off no reminder fires for a self-declined
 * device event.
 *
 * These tests check the selection string only. `DeviceGuestsTagsRoundTripTest` runs the query
 * through [SqliteCalendarProvider] after a decline.
 */
class AndroidCalendarProviderRepositoryDeclinedReminderTest {

    @Test
    fun `selection requires HAS_ALARM = 1`() {
        val selection = buildUpcomingReminderSelection()
        assertTrue(
            "Selection must require HAS_ALARM = 1, got: $selection",
            selection.contains("${Instances.HAS_ALARM} = 1")
        )
    }

    @Test
    fun `selection requires VISIBLE = 1`() {
        val selection = buildUpcomingReminderSelection()
        assertTrue(
            "Selection must require VISIBLE = 1, got: $selection",
            selection.contains("${Calendars.VISIBLE} = 1")
        )
    }

    @Test
    fun `selection excludes self-declined events`() {
        val selection = buildUpcomingReminderSelection()
        val expectedClause =
            "${Instances.SELF_ATTENDEE_STATUS} != ${Attendees.ATTENDEE_STATUS_DECLINED}"
        assertTrue(
            "Selection must exclude SELF_ATTENDEE_STATUS = DECLINED, got: $selection",
            selection.contains(expectedClause)
        )
    }

    @Test
    fun `selection is unconditional - no parameter to bypass decline filter`() {
        // The show-declined toggle must not reach this query. The helper takes no parameters;
        // adding one such as `hideDeclined: Boolean` breaks this test.
        val selection: String = buildUpcomingReminderSelection()
        assertEquals(
            "${Instances.HAS_ALARM} = 1 AND ${Calendars.VISIBLE} = 1 AND " +
                "${Instances.SELF_ATTENDEE_STATUS} != ${Attendees.ATTENDEE_STATUS_DECLINED}",
            selection
        )
    }
}
