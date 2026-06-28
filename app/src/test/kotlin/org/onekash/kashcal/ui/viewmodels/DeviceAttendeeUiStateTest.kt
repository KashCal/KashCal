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
 * Unit tests for [deviceAttendeeUiState] — the pure projection that turns a
 * device event's `Attendees` rows + owner email into the [EventAttendeeUiState]
 * the quick-view / form chip surfaces consume.
 *
 * The IO orchestration (getAttendees + getDeviceCalendars) lives in the
 * ViewModel and is exercised end-to-end; this isolates the branch logic
 * (empty → empty, else map + derive isCurrentUserOnList). Robolectric is
 * required for the `Attendees.*` relationship/status constants.
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
