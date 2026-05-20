package org.onekash.kashcal.domain.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.data.db.dao.EventWithNextOccurrence
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.model.AccountProvider

/**
 * Pure-helper tests for [buildPendingInvitations] — the policy that
 * decides which Room events qualify as "pending invitations" for the
 * inbox.
 *
 * Decision policy: invite scope is per-account; an event in account B's
 * calendar qualifies iff the user (account B) is on the ATTENDEE list with
 * `partstat = NEEDS-ACTION`, the event's ORGANIZER is non-blank AND not
 * the same identity as account B, and the event is a master (not an
 * exception). Time predicate (`dtstart >= now`) is enforced by the SQL
 * filter in EventsDao; this builder takes the SQL-filtered list as input.
 */
class PendingInvitationsBuilderTest {

    private fun account(
        id: Long,
        email: String = "user$id@icloud.com",
        addresses: List<String> = emptyList()
    ): Account = Account(
        id = id,
        provider = AccountProvider.ICLOUD,
        email = email,
        calendarUserAddresses = addresses
    )

    private fun calendar(id: Long, accountId: Long, color: Int = 0xFF0000FF.toInt()): Calendar =
        Calendar(
            id = id,
            accountId = accountId,
            caldavUrl = "https://example.test/cal$id/",
            displayName = "Cal $id",
            color = color
        )

    private fun event(
        id: Long,
        calendarId: Long,
        startTs: Long = 10_000L,
        endTs: Long = 11_000L,
        organizerEmail: String? = "mailto:organizer@example.test",
        organizerName: String? = "Org Name",
        originalEventId: Long? = null,
        title: String = "Invite $id"
    ): Event = Event(
        id = id,
        uid = "uid-$id",
        calendarId = calendarId,
        title = title,
        startTs = startTs,
        endTs = endTs,
        dtstamp = 0L,
        syncStatus = SyncStatus.SYNCED,
        organizerEmail = organizerEmail,
        organizerName = organizerName,
        originalEventId = originalEventId
    )

    private fun eventWithNext(
        e: Event,
        nextStart: Long? = e.startTs
    ): EventWithNextOccurrence = EventWithNextOccurrence(
        event = e,
        nextOccurrenceTs = nextStart
    )

    private fun attendee(
        eventId: Long,
        address: String,
        partstat: String? = "NEEDS-ACTION"
    ): Attendee = Attendee(
        id = 0,
        eventId = eventId,
        address = address,
        partstat = partstat
    )

    // ---- 1. empty inputs ----

    @Test
    fun `empty events returns empty list`() {
        val result = buildPendingInvitations(
            eventsWithNext = emptyList(),
            needsActionAttendees = emptyList(),
            accountsById = emptyMap(),
            calendarsById = emptyMap()
        )
        assertTrue(result.isEmpty())
    }

    // ---- 2. single account, single event, attendee NEEDS-ACTION matches ----

    @Test
    fun `single account NEEDS-ACTION attendee matches and is included`() {
        val a = account(1, addresses = listOf("mailto:alice@icloud.com"))
        val cal = calendar(10, accountId = 1)
        val ev = event(100, calendarId = 10)
        val att = attendee(eventId = 100, address = "mailto:alice@icloud.com")

        val result = buildPendingInvitations(
            eventsWithNext = listOf(eventWithNext(ev)),
            needsActionAttendees = listOf(att),
            accountsById = mapOf(1L to a),
            calendarsById = mapOf(10L to cal)
        )

        assertEquals(1, result.size)
        assertEquals(100L, result[0].event.id)
        assertEquals(1L, result[0].accountId)
        assertEquals(cal.color, result[0].calendarColor)
    }

    // ---- 3. multi-account scoping ----

    @Test
    fun `multi-account scoping isolates per account`() {
        val a1 = account(1, addresses = listOf("mailto:alice@icloud.com"))
        val a2 = account(2, addresses = listOf("mailto:bob@icloud.com"))
        val calA = calendar(10, accountId = 1)
        val calB = calendar(20, accountId = 2)

        // event in B's calendar with A's address as attendee — does NOT belong in A's or B's inbox
        val evInB = event(200, calendarId = 20)
        val evInA = event(100, calendarId = 10)

        val result = buildPendingInvitations(
            eventsWithNext = listOf(eventWithNext(evInA), eventWithNext(evInB)),
            needsActionAttendees = listOf(
                attendee(eventId = 100, address = "mailto:alice@icloud.com"),
                attendee(eventId = 200, address = "mailto:alice@icloud.com")
            ),
            accountsById = mapOf(1L to a1, 2L to a2),
            calendarsById = mapOf(10L to calA, 20L to calB)
        )

        assertEquals(1, result.size)
        assertEquals(100L, result[0].event.id)
    }

    // ---- 4. organizer-self exclusion (event.organizerEmail matches account) ----

    @Test
    fun `organizer-self exclusion - account organizes its own meeting`() {
        val a = account(1, addresses = listOf("mailto:alice@icloud.com"))
        val cal = calendar(10, accountId = 1)
        val ev = event(100, calendarId = 10, organizerEmail = "mailto:alice@icloud.com")

        val result = buildPendingInvitations(
            eventsWithNext = listOf(eventWithNext(ev)),
            needsActionAttendees = listOf(
                attendee(eventId = 100, address = "mailto:alice@icloud.com")
            ),
            accountsById = mapOf(1L to a),
            calendarsById = mapOf(10L to cal)
        )

        assertTrue(result.isEmpty())
    }

    // ---- 5. self-organizer + self-attendee NEEDS-ACTION row excluded edge ----

    @Test
    fun `self-organizer with self-attendee NEEDS-ACTION row is excluded`() {
        val a = account(
            1,
            addresses = listOf("mailto:alice@icloud.com", "mailto:alice2@icloud.com")
        )
        val cal = calendar(10, accountId = 1)
        // organizer is alice2 (still self), attendee row is alice (still self), NEEDS-ACTION:
        // some servers stamp the organizer's own ATTENDEE row this way. Still NOT an invite.
        val ev = event(100, calendarId = 10, organizerEmail = "mailto:alice2@icloud.com")

        val result = buildPendingInvitations(
            eventsWithNext = listOf(eventWithNext(ev)),
            needsActionAttendees = listOf(
                attendee(eventId = 100, address = "mailto:alice@icloud.com")
            ),
            accountsById = mapOf(1L to a),
            calendarsById = mapOf(10L to cal)
        )

        assertTrue(result.isEmpty())
    }

    // ---- 6. ORGANIZER case + whitespace canonicalization ----

    @Test
    fun `organizer-self exclusion canonicalizes case and whitespace`() {
        val a = account(1, addresses = listOf("mailto:alice@icloud.com"))
        val cal = calendar(10, accountId = 1)
        val ev = event(100, calendarId = 10, organizerEmail = "  MAILTO:Alice@ICLOUD.com  ")

        val result = buildPendingInvitations(
            eventsWithNext = listOf(eventWithNext(ev)),
            needsActionAttendees = listOf(
                attendee(eventId = 100, address = "mailto:alice@icloud.com")
            ),
            accountsById = mapOf(1L to a),
            calendarsById = mapOf(10L to cal)
        )

        assertTrue(result.isEmpty())
    }

    // ---- 7. exception event excluded (originalEventId != null) ----

    @Test
    fun `exception event with NEEDS-ACTION attendee is excluded`() {
        val a = account(1, addresses = listOf("mailto:alice@icloud.com"))
        val cal = calendar(10, accountId = 1)
        val ev = event(100, calendarId = 10, originalEventId = 99L)

        val result = buildPendingInvitations(
            eventsWithNext = listOf(eventWithNext(ev)),
            needsActionAttendees = listOf(
                attendee(eventId = 100, address = "mailto:alice@icloud.com")
            ),
            accountsById = mapOf(1L to a),
            calendarsById = mapOf(10L to cal)
        )

        assertTrue(result.isEmpty())
    }

    // ---- 8. chronological sort (multi-account, multiple invites) ----

    @Test
    fun `output is sorted by occurrence start ascending across accounts`() {
        val a1 = account(1, addresses = listOf("mailto:alice@icloud.com"))
        val a2 = account(2, addresses = listOf("mailto:bob@icloud.com"))
        val calA = calendar(10, accountId = 1)
        val calB = calendar(20, accountId = 2)

        val laterEv = event(100, calendarId = 10, startTs = 50_000L)
        val earlierEv = event(200, calendarId = 20, startTs = 20_000L)
        val middleEv = event(300, calendarId = 10, startTs = 30_000L)

        val result = buildPendingInvitations(
            eventsWithNext = listOf(
                eventWithNext(laterEv),
                eventWithNext(earlierEv),
                eventWithNext(middleEv)
            ),
            needsActionAttendees = listOf(
                attendee(eventId = 100, address = "mailto:alice@icloud.com"),
                attendee(eventId = 200, address = "mailto:bob@icloud.com"),
                attendee(eventId = 300, address = "mailto:alice@icloud.com")
            ),
            accountsById = mapOf(1L to a1, 2L to a2),
            calendarsById = mapOf(10L to calA, 20L to calB)
        )

        assertEquals(listOf(200L, 300L, 100L), result.map { it.event.id })
    }

    // ---- 9. multi-server fixture ----

    @Test
    fun `multi-server fixture - 5 invites across 3 accounts surfaces all`() {
        val icloud = account(1, addresses = listOf("mailto:alice@icloud.com"))
        val stalwart = account(2, addresses = listOf("mailto:bob@stalwart.test"))
        val nextcloud = account(3, addresses = listOf("mailto:carol@nc.test"))
        val cal1 = calendar(10, accountId = 1)
        val cal2 = calendar(20, accountId = 2)
        val cal3 = calendar(30, accountId = 3)

        val events = listOf(
            event(100, calendarId = 10, startTs = 10_000L),
            event(200, calendarId = 20, startTs = 20_000L),
            event(300, calendarId = 30, startTs = 30_000L),
            event(400, calendarId = 10, startTs = 40_000L),
            event(500, calendarId = 20, startTs = 50_000L)
        )
        val attendees = listOf(
            attendee(eventId = 100, address = "mailto:alice@icloud.com"),
            attendee(eventId = 200, address = "mailto:bob@stalwart.test"),
            attendee(eventId = 300, address = "mailto:carol@nc.test"),
            attendee(eventId = 400, address = "mailto:alice@icloud.com"),
            attendee(eventId = 500, address = "mailto:bob@stalwart.test")
        )

        val result = buildPendingInvitations(
            eventsWithNext = events.map { eventWithNext(it) },
            needsActionAttendees = attendees,
            accountsById = mapOf(1L to icloud, 2L to stalwart, 3L to nextcloud),
            calendarsById = mapOf(10L to cal1, 20L to cal2, 30L to cal3)
        )

        assertEquals(5, result.size)
        assertEquals(listOf(100L, 200L, 300L, 400L, 500L), result.map { it.event.id })
    }

    // ---- 10. calendar with unknown account is skipped ----

    @Test
    fun `event in calendar pointing at unknown account is skipped`() {
        val cal = calendar(10, accountId = 999)
        val ev = event(100, calendarId = 10)

        val result = buildPendingInvitations(
            eventsWithNext = listOf(eventWithNext(ev)),
            needsActionAttendees = listOf(
                attendee(eventId = 100, address = "mailto:alice@icloud.com")
            ),
            accountsById = emptyMap(),
            calendarsById = mapOf(10L to cal)
        )

        assertTrue(result.isEmpty())
    }

    // ---- 11. account with empty addresses + non-email login does not match ----

    @Test
    fun `account with empty addresses and non-email login does not match`() {
        val a = account(1, email = "alice", addresses = emptyList())
        val cal = calendar(10, accountId = 1)
        val ev = event(100, calendarId = 10)

        val result = buildPendingInvitations(
            eventsWithNext = listOf(eventWithNext(ev)),
            needsActionAttendees = listOf(
                attendee(eventId = 100, address = "mailto:alice@example.com")
            ),
            accountsById = mapOf(1L to a),
            calendarsById = mapOf(10L to cal)
        )

        assertTrue(result.isEmpty())
    }

    // ---- 12. empty addresses + email-shaped login falls back to login match ----

    @Test
    fun `account with empty addresses but email-shaped login matches via fallback`() {
        val a = account(1, email = "alice@icloud.com", addresses = emptyList())
        val cal = calendar(10, accountId = 1)
        val ev = event(100, calendarId = 10)

        val result = buildPendingInvitations(
            eventsWithNext = listOf(eventWithNext(ev)),
            needsActionAttendees = listOf(
                attendee(eventId = 100, address = "mailto:alice@icloud.com")
            ),
            accountsById = mapOf(1L to a),
            calendarsById = mapOf(10L to cal)
        )

        assertEquals(1, result.size)
        assertEquals(100L, result[0].event.id)
    }

    // ---- 13. null ORGANIZER + matching attendee row included (defensive) ----

    @Test
    fun `null organizer with matching attendee row is included`() {
        val a = account(1, addresses = listOf("mailto:alice@icloud.com"))
        val cal = calendar(10, accountId = 1)
        val ev = event(100, calendarId = 10, organizerEmail = null)

        val result = buildPendingInvitations(
            eventsWithNext = listOf(eventWithNext(ev)),
            needsActionAttendees = listOf(
                attendee(eventId = 100, address = "mailto:alice@icloud.com")
            ),
            accountsById = mapOf(1L to a),
            calendarsById = mapOf(10L to cal)
        )

        assertEquals(1, result.size)
        assertEquals(100L, result[0].event.id)
    }

    // ---- 14. organizerLabel field uses name when present ----

    @Test
    fun `organizerLabel uses organizer name when non-blank`() {
        val a = account(1, addresses = listOf("mailto:alice@icloud.com"))
        val cal = calendar(10, accountId = 1)
        val ev = event(
            100,
            calendarId = 10,
            organizerName = "Alice Organizer",
            organizerEmail = "mailto:bob@example.test"
        )

        val result = buildPendingInvitations(
            eventsWithNext = listOf(eventWithNext(ev)),
            needsActionAttendees = listOf(
                attendee(eventId = 100, address = "mailto:alice@icloud.com")
            ),
            accountsById = mapOf(1L to a),
            calendarsById = mapOf(10L to cal)
        )

        assertEquals("Alice Organizer", result[0].organizerLabel)
    }

    @Test
    fun `organizerLabel falls back to email with mailto stripped when name blank`() {
        val a = account(1, addresses = listOf("mailto:alice@icloud.com"))
        val cal = calendar(10, accountId = 1)
        val ev = event(
            100,
            calendarId = 10,
            organizerName = null,
            organizerEmail = "MAILTO:bob@example.test"
        )

        val result = buildPendingInvitations(
            eventsWithNext = listOf(eventWithNext(ev)),
            needsActionAttendees = listOf(
                attendee(eventId = 100, address = "mailto:alice@icloud.com")
            ),
            accountsById = mapOf(1L to a),
            calendarsById = mapOf(10L to cal)
        )

        assertEquals("bob@example.test", result[0].organizerLabel)
    }

    @Test
    fun `organizerLabel falls back to attendee address when name and email blank`() {
        val a = account(1, addresses = listOf("mailto:alice@icloud.com"))
        val cal = calendar(10, accountId = 1)
        val ev = event(
            100,
            calendarId = 10,
            organizerName = "",
            organizerEmail = null
        )

        val result = buildPendingInvitations(
            eventsWithNext = listOf(eventWithNext(ev)),
            needsActionAttendees = listOf(
                attendee(eventId = 100, address = "mailto:alice@icloud.com")
            ),
            accountsById = mapOf(1L to a),
            calendarsById = mapOf(10L to cal)
        )

        assertEquals("alice@icloud.com", result[0].organizerLabel)
    }

    // ---- 15. event with no NEEDS-ACTION attendee row is excluded ----

    @Test
    fun `event with no needs-action attendee for owning account is excluded`() {
        val a = account(1, addresses = listOf("mailto:alice@icloud.com"))
        val cal = calendar(10, accountId = 1)
        val ev = event(100, calendarId = 10)

        val result = buildPendingInvitations(
            eventsWithNext = listOf(eventWithNext(ev)),
            // attendee is bob, not alice — so alice has nothing to respond to
            needsActionAttendees = listOf(
                attendee(eventId = 100, address = "mailto:bob@example.com")
            ),
            accountsById = mapOf(1L to a),
            calendarsById = mapOf(10L to cal)
        )

        assertTrue(result.isEmpty())
    }
}
