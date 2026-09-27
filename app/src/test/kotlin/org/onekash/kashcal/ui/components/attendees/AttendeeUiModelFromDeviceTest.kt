package org.onekash.kashcal.ui.components.attendees

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
 * Tests [AttendeeUiModel.fromDevice], [AttendeeStatus.fromDeviceStatus] and
 * [AttendeeStatus.toDeviceStatus]: the mapping between CalendarProvider `Attendees` rows and
 * the shared UI model, and back for an RSVP write.
 *
 * The `CalendarContract.Attendees` status and relationship values are compile-time constants
 * inlined from the SDK stubs with their real values; the test runs under Robolectric anyway.
 *
 * Unlike the Room path, the provider carries an explicit RELATIONSHIP_ORGANIZER row, so
 * nothing is synthesized: the organizer is the row flagged as such.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class AttendeeUiModelFromDeviceTest {

    private fun guest(
        id: Long = 1L,
        name: String? = "Alice Example",
        email: String? = "alice@example.com",
        relationship: Int = Attendees.RELATIONSHIP_ATTENDEE,
        status: Int = Attendees.ATTENDEE_STATUS_NONE,
    ) = DeviceAttendee(id, name, email, relationship, status)

    // ========== AttendeeStatus.fromDeviceStatus ==========

    @Test
    fun `fromDeviceStatus maps ACCEPTED`() {
        assertEquals(AttendeeStatus.Accepted, AttendeeStatus.fromDeviceStatus(Attendees.ATTENDEE_STATUS_ACCEPTED))
    }

    @Test
    fun `fromDeviceStatus maps DECLINED`() {
        assertEquals(AttendeeStatus.Declined, AttendeeStatus.fromDeviceStatus(Attendees.ATTENDEE_STATUS_DECLINED))
    }

    @Test
    fun `fromDeviceStatus maps TENTATIVE`() {
        assertEquals(AttendeeStatus.Tentative, AttendeeStatus.fromDeviceStatus(Attendees.ATTENDEE_STATUS_TENTATIVE))
    }

    @Test
    fun `fromDeviceStatus maps NONE to NeedsAction`() {
        assertEquals(AttendeeStatus.NeedsAction, AttendeeStatus.fromDeviceStatus(Attendees.ATTENDEE_STATUS_NONE))
    }

    @Test
    fun `fromDeviceStatus maps INVITED to NeedsAction`() {
        assertEquals(AttendeeStatus.NeedsAction, AttendeeStatus.fromDeviceStatus(Attendees.ATTENDEE_STATUS_INVITED))
    }

    // ========== toDeviceStatus (the inverse, for an RSVP write) ==========

    @Test
    fun `toDeviceStatus maps Accepted`() {
        assertEquals(Attendees.ATTENDEE_STATUS_ACCEPTED, AttendeeStatus.Accepted.toDeviceStatus())
    }

    @Test
    fun `toDeviceStatus maps Declined`() {
        assertEquals(Attendees.ATTENDEE_STATUS_DECLINED, AttendeeStatus.Declined.toDeviceStatus())
    }

    @Test
    fun `toDeviceStatus maps Tentative`() {
        assertEquals(Attendees.ATTENDEE_STATUS_TENTATIVE, AttendeeStatus.Tentative.toDeviceStatus())
    }

    @Test
    fun `toDeviceStatus maps NeedsAction to NONE`() {
        assertEquals(Attendees.ATTENDEE_STATUS_NONE, AttendeeStatus.NeedsAction.toDeviceStatus())
    }

    @Test
    fun `toDeviceStatus round-trips the three RSVP responses`() {
        listOf(AttendeeStatus.Accepted, AttendeeStatus.Declined, AttendeeStatus.Tentative).forEach {
            assertEquals(it, AttendeeStatus.fromDeviceStatus(it.toDeviceStatus()))
        }
    }

    // ========== fromDevice ==========

    @Test
    fun `fromDevice flags the organizer row as organizer`() {
        val models = AttendeeUiModel.fromDevice(
            listOf(
                guest(id = 1L, name = "Host", email = "host@example.com", relationship = Attendees.RELATIONSHIP_ORGANIZER, status = Attendees.ATTENDEE_STATUS_ACCEPTED),
                guest(id = 2L, name = "Alice", email = "alice@example.com"),
            ),
            ownerEmail = null,
        )
        val host = models.first { it.bareAddress == "host@example.com" }
        val alice = models.first { it.bareAddress == "alice@example.com" }
        assertTrue("Host row must be organizer", host.isOrganizer)
        assertFalse("Guest row must not be organizer", alice.isOrganizer)
    }

    @Test
    fun `fromDevice marks the owner as you by email match`() {
        val models = AttendeeUiModel.fromDevice(
            listOf(
                guest(id = 1L, name = "Me", email = "me@example.com"),
                guest(id = 2L, name = "Alice", email = "alice@example.com"),
            ),
            ownerEmail = "ME@example.com", // a case-insensitive canonical match
        )
        assertTrue(models.first { it.bareAddress == "me@example.com" }.isYou)
        assertFalse(models.first { it.bareAddress == "alice@example.com" }.isYou)
    }

    @Test
    fun `fromDevice with null owner email marks no one as you`() {
        val models = AttendeeUiModel.fromDevice(listOf(guest()), ownerEmail = null)
        assertTrue(models.none { it.isYou })
    }

    @Test
    fun `fromDevice falls back to email local-part when name is blank`() {
        val models = AttendeeUiModel.fromDevice(
            listOf(guest(name = null, email = "bob@example.com")),
            ownerEmail = null,
        )
        assertEquals("bob", models.single().displayName)
    }

    @Test
    fun `fromDevice canonicalizes the address`() {
        val models = AttendeeUiModel.fromDevice(
            listOf(guest(email = "Mixed.Case@Example.COM")),
            ownerEmail = null,
        )
        assertEquals("mixed.case@example.com", models.single().bareAddress)
    }

    @Test
    fun `fromDevice handles an already-mailto-prefixed provider email without double-prefixing`() {
        // Some sync adapters write ATTENDEE_EMAIL with a leading mailto:. The canonical form
        // is the bare lowercased address, and the owner still matches when supplied bare.
        val models = AttendeeUiModel.fromDevice(
            listOf(guest(email = "mailto:Mixed.Case@Example.com")),
            ownerEmail = "mixed.case@example.com",
        )
        assertEquals("mixed.case@example.com", models.single().bareAddress)
        assertTrue("Owner should match despite the mailto: prefix", models.single().isYou)
    }

    @Test
    fun `fromDevice preserves list order as sortOrder`() {
        val models = AttendeeUiModel.fromDevice(
            listOf(
                guest(id = 1L, email = "a@example.com"),
                guest(id = 2L, email = "b@example.com"),
                guest(id = 3L, email = "c@example.com"),
            ),
            ownerEmail = null,
        )
        assertEquals(listOf(0, 1, 2), models.map { it.sortOrder })
    }

    @Test
    fun `fromDevice maps each provider status`() {
        val models = AttendeeUiModel.fromDevice(
            listOf(
                guest(id = 1L, email = "yes@example.com", status = Attendees.ATTENDEE_STATUS_ACCEPTED),
                guest(id = 2L, email = "no@example.com", status = Attendees.ATTENDEE_STATUS_DECLINED),
            ),
            ownerEmail = null,
        )
        assertEquals(AttendeeStatus.Accepted, models.first { it.bareAddress == "yes@example.com" }.status)
        assertEquals(AttendeeStatus.Declined, models.first { it.bareAddress == "no@example.com" }.status)
    }

    @Test
    fun `fromDevice returns empty for empty input`() {
        assertTrue(AttendeeUiModel.fromDevice(emptyList(), ownerEmail = null).isEmpty())
    }

    @Test
    fun `fromDevice does not duplicate an organizer that is also listed as a guest row`() {
        // The organizer row is the organizer; unlike the Room path no separate chip is
        // synthesized. One row in, one model out.
        val models = AttendeeUiModel.fromDevice(
            listOf(guest(id = 1L, email = "host@example.com", relationship = Attendees.RELATIONSHIP_ORGANIZER)),
            ownerEmail = "host@example.com",
        )
        assertEquals(1, models.size)
        assertTrue(models.single().isOrganizer)
        assertTrue(models.single().isYou)
    }
}
