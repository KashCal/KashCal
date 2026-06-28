package org.onekash.kashcal.ui.viewmodels

import android.provider.CalendarContract.Attendees
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.ui.components.attendees.AttendeeStatus
import org.onekash.kashcal.ui.components.attendees.AttendeeUiModel
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for the device-attendee bridge functions that keep the device
 * write path disjoint from the Room/iTIP path:
 *
 * - [pickerAttendeesToDevice] converts the picker's Room [Attendee] entities
 *   into provider-shaped [org.onekash.kashcal.data.calendar_provider.DeviceAttendee]
 *   rows at the save boundary (guest relationship, no-response status, no iTIP
 *   wire fields).
 * - [deviceGuestsToPickerSeed] seeds that same picker from a device event's
 *   existing guests so an edit diffs against the real set — excluding the
 *   organizer row, which the repository manages separately.
 *
 * Robolectric is required: the bridge references the
 * `CalendarContract.Attendees` RELATIONSHIP and ATTENDEE_STATUS constants,
 * which are stubbed to 0 under plain JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class BridgeDeviceAttendeesTest {

    private fun roomAttendee(address: String, displayName: String? = null) =
        Attendee(eventId = 0L, address = address, displayName = displayName)

    private fun uiModel(
        email: String,
        displayName: String = email,
        isOrganizer: Boolean = false,
    ) = AttendeeUiModel(
        displayName = displayName,
        bareAddress = email,
        status = AttendeeStatus.NeedsAction,
        isYou = false,
        isOrganizer = isOrganizer,
        sortOrder = 0,
    )

    // ========== pickerAttendeesToDevice ==========

    @Test
    fun `bridges email and name into a guest device row`() {
        val device = pickerAttendeesToDevice(listOf(roomAttendee("alice@example.com", "Alice")))

        assertEquals(1, device.size)
        assertEquals("alice@example.com", device[0].email)
        assertEquals("Alice", device[0].name)
        assertEquals(Attendees.RELATIONSHIP_ATTENDEE, device[0].relationship)
        assertEquals(Attendees.ATTENDEE_STATUS_NONE, device[0].status)
    }

    @Test
    fun `strips mailto prefix from picker address`() {
        val device = pickerAttendeesToDevice(listOf(roomAttendee("mailto:bob@example.com")))

        assertEquals("bob@example.com", device[0].email)
    }

    @Test
    fun `drops picker rows with a non-email address`() {
        // A urn:uuid or principal-path CAL-ADDRESS can't be an Attendees email.
        val device = pickerAttendeesToDevice(
            listOf(
                roomAttendee("urn:uuid:1234"),
                roomAttendee("carol@example.com"),
            )
        )

        assertEquals(listOf("carol@example.com"), device.map { it.email })
    }

    @Test
    fun `empty picker list bridges to empty`() {
        assertTrue(pickerAttendeesToDevice(emptyList()).isEmpty())
    }

    // ========== deviceGuestsToPickerSeed ==========

    @Test
    fun `seed carries guest email and name`() {
        val seed = deviceGuestsToPickerSeed(listOf(uiModel("alice@example.com", "Alice")))

        assertEquals(1, seed.size)
        assertEquals("alice@example.com", seed[0].address)
        assertEquals("Alice", seed[0].displayName)
    }

    @Test
    fun `seed excludes the organizer row`() {
        // The organizer is owned by the repository, not a pickable guest.
        val seed = deviceGuestsToPickerSeed(
            listOf(
                uiModel("owner@example.com", "Owner", isOrganizer = true),
                uiModel("alice@example.com", "Alice"),
            )
        )

        assertEquals(listOf("alice@example.com"), seed.map { it.address })
    }
}
