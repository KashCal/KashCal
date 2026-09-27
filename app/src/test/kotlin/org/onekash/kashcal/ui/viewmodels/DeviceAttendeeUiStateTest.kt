package org.onekash.kashcal.ui.viewmodels

import android.provider.CalendarContract.Attendees
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.DeviceAttendee
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests [deviceAttendeeUiState], which maps a device event's `Attendees` rows and the calendar's
 * owner email to the [EventAttendeeUiState] behind the quick-view and form chips: no rows give no
 * chips, every row gives a chip, and the owner is on the list only when a row's email matches it.
 * A null owner email is never on the list.
 *
 * The provider read, [org.onekash.kashcal.domain.reader.DeviceEventReader.getAttendeesWithOwner],
 * isn't called here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class DeviceAttendeeUiStateTest {

    private fun guest(
        id: Long = 1L,
        email: String = "alice@example.com",
        relationship: Int = Attendees.RELATIONSHIP_ATTENDEE,
        status: Int = Attendees.ATTENDEE_STATUS_NONE,
    ) = DeviceAttendee(id, "Name", email, relationship, status)

    @Test
    fun `empty attendees yields empty state and not on list`() {
        val state = deviceAttendeeUiState(emptyList(), ownerEmail = "me@example.com")
        assertTrue(state.models.isEmpty())
        assertFalse(state.isCurrentUserOnList)
    }

    @Test
    fun `maps attendees and marks current user on list when owner is a guest`() {
        val state = deviceAttendeeUiState(
            listOf(
                guest(id = 1L, email = "me@example.com"),
                guest(id = 2L, email = "bob@example.com"),
            ),
            ownerEmail = "me@example.com",
        )
        assertEquals(2, state.models.size)
        assertTrue(state.isCurrentUserOnList)
    }

    @Test
    fun `owner not among guests is not on list`() {
        val state = deviceAttendeeUiState(
            listOf(guest(id = 1L, email = "bob@example.com")),
            ownerEmail = "me@example.com",
        )
        assertEquals(1, state.models.size)
        assertFalse(state.isCurrentUserOnList)
    }

    @Test
    fun `null owner email is never on list`() {
        val state = deviceAttendeeUiState(
            listOf(guest(id = 1L, email = "bob@example.com")),
            ownerEmail = null,
        )
        assertFalse(state.isCurrentUserOnList)
    }
}
