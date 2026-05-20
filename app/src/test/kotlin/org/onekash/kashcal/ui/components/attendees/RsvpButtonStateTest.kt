package org.onekash.kashcal.ui.components.attendees

import org.junit.Assert.assertEquals
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

    // ========== isRespondButtonSelected ==========

    @Test
    fun `no button selected when user is not on attendee list`() {
        for (status in AttendeeStatus.entries) {
            assertFalse(isRespondButtonSelected(buttonStatus = status, currentUserPartstat = null))
        }
    }

    @Test
    fun `no button selected when user is NEEDS-ACTION`() {
        for (status in AttendeeStatus.entries) {
            assertFalse(
                isRespondButtonSelected(
                    buttonStatus = status,
                    currentUserPartstat = AttendeeStatus.NeedsAction
                )
            )
        }
    }

    @Test
    fun `accept button selected when user is ACCEPTED`() {
        assertTrue(
            isRespondButtonSelected(
                buttonStatus = AttendeeStatus.Accepted,
                currentUserPartstat = AttendeeStatus.Accepted
            )
        )
        assertFalse(
            isRespondButtonSelected(
                buttonStatus = AttendeeStatus.Tentative,
                currentUserPartstat = AttendeeStatus.Accepted
            )
        )
        assertFalse(
            isRespondButtonSelected(
                buttonStatus = AttendeeStatus.Declined,
                currentUserPartstat = AttendeeStatus.Accepted
            )
        )
    }

    @Test
    fun `tentative and declined buttons select correctly`() {
        assertTrue(
            isRespondButtonSelected(
                buttonStatus = AttendeeStatus.Tentative,
                currentUserPartstat = AttendeeStatus.Tentative
            )
        )
        assertTrue(
            isRespondButtonSelected(
                buttonStatus = AttendeeStatus.Declined,
                currentUserPartstat = AttendeeStatus.Declined
            )
        )
    }

    // ========== respondGlyphTintRole ==========

    @Test
    fun `glyph tint role is Selected when the button matches user partstat`() {
        assertEquals(
            GlyphTintRole.Selected,
            respondGlyphTintRole(AttendeeStatus.Accepted, currentUserPartstat = AttendeeStatus.Accepted)
        )
        assertEquals(
            GlyphTintRole.Selected,
            respondGlyphTintRole(AttendeeStatus.Tentative, currentUserPartstat = AttendeeStatus.Tentative)
        )
        assertEquals(
            GlyphTintRole.Selected,
            respondGlyphTintRole(AttendeeStatus.Declined, currentUserPartstat = AttendeeStatus.Declined)
        )
    }

    @Test
    fun `glyph tint role uses per-status tint when not selected`() {
        // User hasn't responded yet — every button shows its semantic color tint.
        assertEquals(
            GlyphTintRole.Success,
            respondGlyphTintRole(AttendeeStatus.Accepted, currentUserPartstat = AttendeeStatus.NeedsAction)
        )
        assertEquals(
            GlyphTintRole.Tentative,
            respondGlyphTintRole(AttendeeStatus.Tentative, currentUserPartstat = AttendeeStatus.NeedsAction)
        )
        assertEquals(
            GlyphTintRole.Error,
            respondGlyphTintRole(AttendeeStatus.Declined, currentUserPartstat = AttendeeStatus.NeedsAction)
        )
    }

    @Test
    fun `glyph tint role uses per-status tint when user is on different partstat`() {
        // User chose Accepted — the other two buttons still show their tints.
        assertEquals(
            GlyphTintRole.Selected,
            respondGlyphTintRole(AttendeeStatus.Accepted, currentUserPartstat = AttendeeStatus.Accepted)
        )
        assertEquals(
            GlyphTintRole.Tentative,
            respondGlyphTintRole(AttendeeStatus.Tentative, currentUserPartstat = AttendeeStatus.Accepted)
        )
        assertEquals(
            GlyphTintRole.Error,
            respondGlyphTintRole(AttendeeStatus.Declined, currentUserPartstat = AttendeeStatus.Accepted)
        )
    }

    @Test
    fun `glyph tint role uses per-status tint when user is null`() {
        // No isYou chip → user not on the list. Section wouldn't render anyway,
        // but the helper shouldn't crash and should fall through to per-status tints.
        assertEquals(
            GlyphTintRole.Success,
            respondGlyphTintRole(AttendeeStatus.Accepted, currentUserPartstat = null)
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
