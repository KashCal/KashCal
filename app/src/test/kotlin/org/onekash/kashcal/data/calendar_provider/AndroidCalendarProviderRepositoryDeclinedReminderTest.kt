package org.onekash.kashcal.data.calendar_provider

import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Instances
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [buildUpcomingReminderSelection], the SQL selection clause
 * backing [AndroidCalendarProviderRepository.getNextUpcomingReminder].
 *
 * The selection is unconditional — the "Show declined events" toggle is a
 * display preference that never reaches this query. Outcome 6: whether the
 * toggle is ON or OFF, the alarm pipeline never fires for self-declined
 * events on the device calendar.
 *
 * Note: Actual ContentResolver behavior cannot be unit-tested. These tests
 * verify the selection string includes the right clauses; integration tests
 * exercise the query end-to-end.
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
        // Outcome 6: the show-declined toggle is a display preference and
        // must NOT reach this query. Helper takes no parameters; if a future
        // refactor adds a `hideDeclined: Boolean` parameter the test breaks
        // and forces a re-read of the outcome.
        val selection: String = buildUpcomingReminderSelection()
        assertEquals(
            "${Instances.HAS_ALARM} = 1 AND ${Calendars.VISIBLE} = 1 AND " +
                "${Instances.SELF_ATTENDEE_STATUS} != ${Attendees.ATTENDEE_STATUS_DECLINED}",
            selection
        )
    }
}
