package org.onekash.kashcal.data.calendar_provider

import android.provider.CalendarContract.Attendees
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests the device-attendee write helpers: [computeAttendeeDiff], the owner and guest
 * [android.content.ContentValues] builders, [isValidOrganizerEmail], [guestsExcludingOwner] and
 * [ownerRowNeeded].
 *
 * The diff keeps an edit non-destructive: a guest the user didn't touch is in neither the insert
 * nor the delete set, so its provider row and its synced ATTENDEE_STATUS survive. A delete-all
 * and re-insert would wipe every guest's response on an unrelated edit.
 *
 * Robolectric is required for the builders: plain-JVM `ContentValues` is a stub whose getters
 * return null, so their assertions couldn't pass.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ComputeAttendeeDiffTest {

    private fun guest(
        id: Long,
        email: String?,
        name: String? = null,
        relationship: Int = Attendees.RELATIONSHIP_ATTENDEE,
        status: Int = Attendees.ATTENDEE_STATUS_NONE,
    ) = DeviceAttendee(id, name, email, relationship, status)

    // ========== computeAttendeeDiff ==========

    @Test
    fun `adding a new guest inserts only the new one`() {
        val existing = listOf(guest(1L, "a@example.com"))
        val desired = listOf(guest(0L, "a@example.com"), guest(0L, "c@example.com"))

        val diff = computeAttendeeDiff(existing, desired)

        assertEquals(listOf("c@example.com"), diff.toInsert.map { it.email })
        assertTrue("nothing should be deleted", diff.toDelete.isEmpty())
    }

    @Test
    fun `removing a guest deletes only the removed one`() {
        val existing = listOf(guest(1L, "a@example.com"), guest(2L, "b@example.com"))
        val desired = listOf(guest(0L, "b@example.com"))

        val diff = computeAttendeeDiff(existing, desired)

        assertEquals(listOf(1L), diff.toDelete.map { it.id })
        assertTrue("nothing should be inserted", diff.toInsert.isEmpty())
    }

    @Test
    fun `add and remove together touches only the deltas`() {
        // A and B present; remove A, add C, keep B: delete A, insert C, B untouched.
        val existing = listOf(guest(1L, "a@example.com"), guest(2L, "b@example.com"))
        val desired = listOf(guest(0L, "b@example.com"), guest(0L, "c@example.com"))

        val diff = computeAttendeeDiff(existing, desired)

        assertEquals(listOf(1L), diff.toDelete.map { it.id })
        assertEquals(listOf("c@example.com"), diff.toInsert.map { it.email })
    }

    @Test
    fun `unchanged guest list yields an empty diff`() {
        val existing = listOf(guest(1L, "a@example.com"), guest(2L, "b@example.com"))
        val desired = listOf(guest(0L, "a@example.com"), guest(0L, "b@example.com"))

        val diff = computeAttendeeDiff(existing, desired)

        assertTrue(diff.toInsert.isEmpty())
        assertTrue(diff.toDelete.isEmpty())
    }

    @Test
    fun `empty desired removes every existing guest`() {
        val existing = listOf(guest(1L, "a@example.com"), guest(2L, "b@example.com"))

        val diff = computeAttendeeDiff(existing, emptyList())

        assertEquals(setOf(1L, 2L), diff.toDelete.map { it.id }.toSet())
        assertTrue(diff.toInsert.isEmpty())
    }

    @Test
    fun `diff is keyed on canonical email so casing and mailto prefix match`() {
        // Same person with different casing and a mailto prefix: unchanged, no churn.
        val existing = listOf(guest(1L, "Alice@Example.com"))
        val desired = listOf(guest(0L, "mailto:alice@example.com"))

        val diff = computeAttendeeDiff(existing, desired)

        assertTrue("case/prefix-equal address must not be deleted", diff.toDelete.isEmpty())
        assertTrue("case/prefix-equal address must not be re-inserted", diff.toInsert.isEmpty())
    }

    @Test
    fun `organizer row is never a delete candidate`() {
        // The organizer row survives a guest edit though it isn't in the desired set, which
        // holds guests only.
        val existing = listOf(
            guest(1L, "owner@example.com", relationship = Attendees.RELATIONSHIP_ORGANIZER, status = Attendees.ATTENDEE_STATUS_ACCEPTED),
            guest(2L, "a@example.com"),
        )
        val desired = listOf(guest(0L, "a@example.com"))

        val diff = computeAttendeeDiff(existing, desired)

        assertTrue(diff.toDelete.isEmpty())
        assertTrue(diff.toInsert.isEmpty())
    }

    @Test
    fun `guest row with blank email is ignored on both sides`() {
        val existing = listOf(guest(1L, ""), guest(2L, "a@example.com"))
        val desired = listOf(guest(0L, "a@example.com"), guest(0L, null))

        val diff = computeAttendeeDiff(existing, desired)

        assertTrue(diff.toDelete.isEmpty())
        assertTrue(diff.toInsert.isEmpty())
    }

    // ========== buildOwnerAttendeeValues ==========

    @Test
    fun `owner values carry organizer relationship, required type, accepted status`() {
        val values = buildOwnerAttendeeValues("owner@example.com")

        assertEquals("owner@example.com", values.getAsString(Attendees.ATTENDEE_EMAIL))
        assertEquals(Attendees.RELATIONSHIP_ORGANIZER, values.getAsInteger(Attendees.ATTENDEE_RELATIONSHIP))
        assertEquals(Attendees.TYPE_REQUIRED, values.getAsInteger(Attendees.ATTENDEE_TYPE))
        assertEquals(Attendees.ATTENDEE_STATUS_ACCEPTED, values.getAsInteger(Attendees.ATTENDEE_STATUS))
    }

    // ========== buildGuestAttendeeValues ==========

    @Test
    fun `guest values carry attendee relationship, required type, none status`() {
        val values = buildGuestAttendeeValues(guest(0L, "a@example.com", name = "Alice"))

        assertEquals("Alice", values.getAsString(Attendees.ATTENDEE_NAME))
        assertEquals("a@example.com", values.getAsString(Attendees.ATTENDEE_EMAIL))
        assertEquals(Attendees.RELATIONSHIP_ATTENDEE, values.getAsInteger(Attendees.ATTENDEE_RELATIONSHIP))
        assertEquals(Attendees.TYPE_REQUIRED, values.getAsInteger(Attendees.ATTENDEE_TYPE))
        assertEquals(Attendees.ATTENDEE_STATUS_NONE, values.getAsInteger(Attendees.ATTENDEE_STATUS))
    }

    @Test
    fun `guest values omit name when null`() {
        val values = buildGuestAttendeeValues(guest(0L, "a@example.com", name = null))

        assertTrue(
            "null name must not be written",
            !values.containsKey(Attendees.ATTENDEE_NAME)
        )
    }

    // ========== isValidOrganizerEmail (machine-address guard) ==========

    @Test
    fun `real email is a valid organizer`() {
        assertTrue(isValidOrganizerEmail("owner@example.com"))
    }

    @Test
    fun `machine-generated group address is not a valid organizer`() {
        // A shared group calendar's OWNER_ACCOUNT can be a machine-generated group address;
        // it means nothing as ORGANIZER, so it's rejected.
        assertFalse(isValidOrganizerEmail("abc123@group.calendar.google.com"))
    }

    @Test
    fun `blank or null email is not a valid organizer`() {
        assertFalse(isValidOrganizerEmail(""))
        assertFalse(isValidOrganizerEmail("   "))
        assertFalse(isValidOrganizerEmail(null))
    }

    // ========== guestsExcludingOwner (owner-as-guest dedup) ==========

    @Test
    fun `owner email is excluded from the guest rows`() {
        val guests = listOf(
            guest(0L, "owner@example.com"),
            guest(0L, "alice@example.com"),
        )
        val result = guestsExcludingOwner(guests, "owner@example.com")
        assertEquals(listOf("alice@example.com"), result.map { it.email })
    }

    @Test
    fun `owner exclusion is canonical (case and mailto insensitive)`() {
        val guests = listOf(
            guest(0L, "mailto:Owner@Example.com"),
            guest(0L, "alice@example.com"),
        )
        val result = guestsExcludingOwner(guests, "owner@example.com")
        assertEquals(listOf("alice@example.com"), result.map { it.email })
    }

    @Test
    fun `null owner leaves the guest list untouched`() {
        val guests = listOf(guest(0L, "alice@example.com"))
        assertEquals(1, guestsExcludingOwner(guests, null).size)
    }

    @Test
    fun `machine-address owner does not strip a matching guest`() {
        // No owner row is written for a machine address, so a guest equal to it must not be
        // silently dropped: there's no duplicate to avoid.
        val guests = listOf(
            guest(0L, "shared@group.calendar.google.com"),
            guest(0L, "alice@example.com"),
        )
        val result = guestsExcludingOwner(guests, "shared@group.calendar.google.com")
        assertEquals(
            listOf("shared@group.calendar.google.com", "alice@example.com"),
            result.map { it.email },
        )
    }

    // ========== ownerRowNeeded (create + update) ==========

    @Test
    fun `owner row is needed when guests exist and owner not already a row`() {
        val desired = listOf(guest(0L, "alice@example.com"))
        assertTrue(ownerRowNeeded(existing = emptyList(), desired = desired, ownerEmail = "owner@example.com"))
    }

    @Test
    fun `owner row is not needed when there are no guests`() {
        assertFalse(ownerRowNeeded(existing = emptyList(), desired = emptyList(), ownerEmail = "owner@example.com"))
    }

    @Test
    fun `owner row is not needed when an organizer row already exists`() {
        // On update the event already has the owner as ORGANIZER, so no second one is added.
        val existing = listOf(
            guest(1L, "owner@example.com", relationship = Attendees.RELATIONSHIP_ORGANIZER, status = Attendees.ATTENDEE_STATUS_ACCEPTED),
            guest(2L, "alice@example.com"),
        )
        val desired = listOf(guest(0L, "alice@example.com"), guest(0L, "bob@example.com"))
        assertFalse(ownerRowNeeded(existing = existing, desired = desired, ownerEmail = "owner@example.com"))
    }

    @Test
    fun `owner row is not needed when owner email is a machine address`() {
        val desired = listOf(guest(0L, "alice@example.com"))
        assertFalse(ownerRowNeeded(existing = emptyList(), desired = desired, ownerEmail = "x@group.calendar.google.com"))
    }

    @Test
    fun `owner row is needed on update when guests appear but no organizer row exists`() {
        // A solo event (no attendee rows) gaining a guest must get an owner row.
        val existing = emptyList<DeviceAttendee>()
        val desired = listOf(guest(0L, "alice@example.com"))
        assertTrue(ownerRowNeeded(existing = existing, desired = desired, ownerEmail = "owner@example.com"))
    }
}
