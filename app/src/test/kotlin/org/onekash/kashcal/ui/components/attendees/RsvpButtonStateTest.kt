package org.onekash.kashcal.ui.components.attendees

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RsvpButtonStateTest {

    // ========== shouldShowRespondSection ==========

    @Test
    fun `respond section hidden when user is not on attendee list`() {
        assertFalse(shouldShowRespondSection(currentUserPartstat = null, isOrganizer = false))
    }

    @Test
    fun `respond section hidden when user is the organizer`() {
        // Organizers don't RSVP to their own events — they edit them.
        assertFalse(
            shouldShowRespondSection(
                currentUserPartstat = AttendeeStatus.Accepted,
                isOrganizer = true
            )
        )
    }

    @Test
    fun `respond section visible for NEEDS-ACTION attendee`() {
        assertTrue(
            shouldShowRespondSection(
                currentUserPartstat = AttendeeStatus.NeedsAction,
                isOrganizer = false
            )
        )
    }

    @Test
    fun `respond section stays visible after user already responded`() {
        // After tap-Accept, section remains so the user can change their mind.
        assertTrue(
            shouldShowRespondSection(
                currentUserPartstat = AttendeeStatus.Accepted,
                isOrganizer = false
            )
        )
        assertTrue(
            shouldShowRespondSection(
                currentUserPartstat = AttendeeStatus.Tentative,
                isOrganizer = false
            )
        )
        assertTrue(
            shouldShowRespondSection(
                currentUserPartstat = AttendeeStatus.Declined,
                isOrganizer = false
            )
        )
    }

    // ========== shouldShowSeriesRsvpDisclosure ==========

    @Test
    fun `series disclosure hidden when respond section is hidden`() {
        // User not on attendee list → no respond section → no disclosure.
        assertFalse(
            shouldShowSeriesRsvpDisclosure(
                currentUserPartstat = null,
                isOrganizer = false,
                isRecurring = true
            )
        )
        // User is the organizer → no respond section → no disclosure.
        assertFalse(
            shouldShowSeriesRsvpDisclosure(
                currentUserPartstat = AttendeeStatus.Accepted,
                isOrganizer = true,
                isRecurring = true
            )
        )
    }

    @Test
    fun `series disclosure hidden for non-recurring events`() {
        // Respond section visible, but no series exists → nothing to disclose.
        for (status in listOf(
            AttendeeStatus.NeedsAction,
            AttendeeStatus.Accepted,
            AttendeeStatus.Tentative,
            AttendeeStatus.Declined
        )) {
            assertFalse(
                shouldShowSeriesRsvpDisclosure(
                    currentUserPartstat = status,
                    isOrganizer = false,
                    isRecurring = false
                )
            )
        }
    }

    @Test
    fun `series disclosure visible when recurring AND respond section is visible`() {
        // The whole point of the disclosure: a recurring event where the user
        // can RSVP. Tap on Friday's instance applies to every Friday.
        for (status in listOf(
            AttendeeStatus.NeedsAction,
            AttendeeStatus.Accepted,
            AttendeeStatus.Tentative,
            AttendeeStatus.Declined
        )) {
            assertTrue(
                shouldShowSeriesRsvpDisclosure(
                    currentUserPartstat = status,
                    isOrganizer = false,
                    isRecurring = true
                )
            )
        }
    }

}
