package org.onekash.kashcal.ui.components.attendees

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Attendee

/**
 * Pure-logic tests for [AttendeeSelection] — the picker's selection model.
 *
 * The load-bearing constraint (C1): the picker must edit `Attendee` ENTITIES,
 * never rebuild them from the lossy [AttendeeUiModel]. These tests pin that a
 * seeded attendee's wire fields (role / cutype / rsvp / delegation / schedule
 * params) survive untouched, and that a remove keyed on the canonical address
 * the chip displays still finds the seeded row.
 */
class AttendeeSelectionTest {

    private fun attendee(
        address: String,
        displayName: String? = null,
        partstat: String? = "ACCEPTED",
        role: String? = "REQ-PARTICIPANT",
        cutype: String? = "INDIVIDUAL",
        rsvp: Boolean? = true,
        sortOrder: Int = 0,
        id: Long = 0,
    ) = Attendee(
        id = id,
        eventId = 99L,
        address = address,
        displayName = displayName,
        role = role,
        partstat = partstat,
        cutype = cutype,
        rsvp = rsvp,
        delegatedFrom = listOf("mailto:boss@example.test"),
        delegatedTo = listOf("mailto:deputy@example.test"),
        member = listOf("mailto:team@example.test"),
        sentBy = "mailto:assistant@example.test",
        scheduleAgent = "SERVER",
        scheduleStatus = "1.2;Delivered",
        scheduleForceSend = "REQUEST",
        sortOrder = sortOrder,
        notifiedAt = 1234L,
    )

    @Test
    fun `seed preserves every wire field verbatim (C1)`() {
        val pulled = attendee(
            address = "mailto:Alice@Example.test",
            displayName = "Alice",
            partstat = "NEEDS-ACTION",
            role = "CHAIR",
            cutype = "GROUP",
            rsvp = false,
            sortOrder = 3,
            id = 7L,
        )
        val selection = AttendeeSelection.seed(listOf(pulled))
        // The entity must come back byte-identical — no round-trip through the
        // lossy AttendeeUiModel, which would drop role/cutype/rsvp/delegation.
        assertEquals(pulled, selection.attendees.single())
    }

    @Test
    fun `a pure seed is not changed (C2)`() {
        val selection = AttendeeSelection.seed(listOf(attendee("mailto:a@example.test")))
        assertFalse(selection.isChanged)
    }

    @Test
    fun `empty seed is not changed`() {
        assertFalse(AttendeeSelection.seed(emptyList()).isChanged)
    }

    @Test
    fun `addNew with an email-shaped address prefixes mailto and is NEEDS-ACTION`() {
        val selection = AttendeeSelection.seed(emptyList())
            .addNew(displayName = "Bob", bareAddress = "bob@example.test")
        val added = selection.attendees.single()
        assertEquals("mailto:bob@example.test", added.address)
        assertEquals("Bob", added.displayName)
        assertEquals("NEEDS-ACTION", added.partstat)
    }

    @Test
    fun `addNew carries no role or cutype for a freshly invited person`() {
        val added = AttendeeSelection.seed(emptyList())
            .addNew(displayName = null, bareAddress = "bob@example.test")
            .attendees.single()
        assertNull(added.role)
        assertNull(added.cutype)
        assertNull(added.rsvp)
    }

    @Test
    fun `addNew stores a non-email CAL-ADDRESS verbatim (no mailto prefix)`() {
        // Defensive: the picker only adds emails, but the converter must not
        // produce "mailto:urn:uuid:…" if a non-email form ever reaches it.
        val added = AttendeeSelection.seed(emptyList())
            .addNew(displayName = null, bareAddress = "urn:uuid:abc-123")
            .attendees.single()
        assertEquals("urn:uuid:abc-123", added.address)
    }

    @Test
    fun `addNew appends sortOrder after the existing maximum`() {
        val selection = AttendeeSelection.seed(
            listOf(
                attendee("mailto:a@example.test", sortOrder = 0),
                attendee("mailto:b@example.test", sortOrder = 5),
            ),
        ).addNew(displayName = null, bareAddress = "c@example.test")
        assertEquals(6, selection.attendees.single { it.address == "mailto:c@example.test" }.sortOrder)
    }

    @Test
    fun `addNew flips isChanged`() {
        val selection = AttendeeSelection.seed(emptyList())
            .addNew(displayName = null, bareAddress = "bob@example.test")
        assertTrue(selection.isChanged)
    }

    @Test
    fun `addNew of a canonical duplicate is a no-op and does not flip isChanged`() {
        // mailto-vs-bare and case differ, but canonically the same person.
        val selection = AttendeeSelection.seed(listOf(attendee("mailto:Alice@Example.test")))
            .addNew(displayName = "Alice Again", bareAddress = "alice@example.test")
        assertEquals(1, selection.attendees.size)
        assertFalse(selection.isChanged)
        // The original (with its wire fields) is kept, not the new bare row.
        assertEquals("mailto:Alice@Example.test", selection.attendees.single().address)
    }

    @Test
    fun `remove drops the matching entity and flips isChanged`() {
        val selection = AttendeeSelection.seed(
            listOf(
                attendee("mailto:a@example.test"),
                attendee("mailto:b@example.test"),
            ),
        ).remove("a@example.test")
        assertEquals(listOf("mailto:b@example.test"), selection.attendees.map { it.address })
        assertTrue(selection.isChanged)
    }

    @Test
    fun `remove canonicalizes its argument (mailto and case insensitive)`() {
        val selection = AttendeeSelection.seed(listOf(attendee("mailto:Alice@Example.test")))
            .remove("MAILTO:alice@example.test")
        assertTrue(selection.attendees.isEmpty())
    }

    @Test
    fun `remove of an absent address is a no-op and does not flip isChanged`() {
        val selection = AttendeeSelection.seed(listOf(attendee("mailto:a@example.test")))
            .remove("ghost@example.test")
        assertEquals(1, selection.attendees.size)
        assertFalse(selection.isChanged)
    }

    @Test
    fun `a seeded attendee is removable (the add-only lock is lifted)`() {
        // Removing an already-invited guest is now allowed; the dropped guest
        // gets an iTIP CANCEL on save.
        val selection = AttendeeSelection.seed(
            listOf(attendee("mailto:alice@example.test")),
        )
        assertTrue(selection.isRemovable(selection.attendees.single()))
        val after = selection.remove("alice@example.test")
        assertTrue(after.attendees.isEmpty())
        assertTrue(after.isChanged)
    }

    @Test
    fun `removedFromSeed reports a removed original attendee`() {
        val selection = AttendeeSelection.seed(
            listOf(attendee("mailto:alice@example.test"), attendee("mailto:bob@example.test")),
        ).remove("alice@example.test")

        assertEquals(setOf("alice@example.test"), selection.removedFromSeed())
    }

    @Test
    fun `removedFromSeed is non-empty after a recurring removal`() {
        // Regression guard: the seed snapshot must be captured regardless of any
        // lock flag, so the CANCEL target set does not silently vanish.
        val selection = AttendeeSelection.seed(
            listOf(attendee("mailto:alice@example.test")),
        ).remove("alice@example.test")

        assertTrue(
            "a removed seeded guest must be reported for cancellation",
            selection.removedFromSeed().isNotEmpty(),
        )
    }

    @Test
    fun `removedFromSeed excludes a session-added-then-removed guest (never invited)`() {
        // Adding then removing someone who was never on the seed nets to no
        // removal — they were never invited, so no CANCEL is owed.
        val selection = AttendeeSelection.seed(emptyList())
            .addNew(displayName = null, bareAddress = "bob@example.test")
            .remove("bob@example.test")

        assertTrue(selection.removedFromSeed().isEmpty())
    }

    @Test
    fun `removedFromSeed nets out a remove-then-readd of a seeded guest`() {
        // Removing then re-adding a seeded guest leaves them invited; no CANCEL.
        val selection = AttendeeSelection.seed(
            listOf(attendee("mailto:alice@example.test")),
        ).remove("alice@example.test")
            .addNew(displayName = null, bareAddress = "alice@example.test")

        assertTrue(selection.removedFromSeed().isEmpty())
    }

    @Test
    fun `remove by the canonical address the chip displays finds the seeded row`() {
        // The chip renders via AttendeeUiModel.fromRoom; its bareAddress is the
        // canonical form. Removing by that exact string must hit the entity.
        val pulled = attendee("mailto:Carol@Example.test", displayName = "Carol")
        val displayed = AttendeeUiModel.fromRoom(
            attendees = listOf(pulled),
            currentAccount = null,
            organizerAddress = null,
            organizerName = null,
        ).single()
        val selection = AttendeeSelection.seed(listOf(pulled)).remove(displayed.bareAddress)
        assertTrue(selection.attendees.isEmpty())
        assertTrue(selection.isChanged)
    }
}
