package org.onekash.kashcal.domain.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.domain.model.AccountProvider

/**
 * Pure-helper tests for [selfDeclinedEventIds] — the policy that decides
 * which Room event IDs should be hidden / dimmed because the *owning*
 * account has declined them.
 *
 * Decision policy: each event maps to a calendar, each calendar to an
 * account; the attendee row's address must match THAT account, not just
 * any configured account. This isolates multi-account setups so an event
 * in account B's calendar with an attendee that happens to share account
 * A's address doesn't get hidden under A's preference.
 */
class SelfDeclinedDetectorTest {

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

    private fun calendar(id: Long, accountId: Long): Calendar = Calendar(
        id = id,
        accountId = accountId,
        caldavUrl = "https://example.test/cal$id/",
        displayName = "Cal $id",
        color = 0
    )

    private fun attendee(
        eventId: Long,
        address: String,
        partstat: String? = "DECLINED"
    ): Attendee = Attendee(
        id = 0,
        eventId = eventId,
        address = address,
        partstat = partstat
    )

    // ---- (a) declined attendee matches account address → eventId returned ----

    @Test
    fun `declined attendee matching owning account is returned`() {
        val accountA = account(1, addresses = listOf("mailto:alice@icloud.com"))
        val cal1 = calendar(10, accountId = 1)
        val attendees = listOf(attendee(eventId = 100, address = "mailto:alice@icloud.com"))

        val result = selfDeclinedEventIds(
            declinedAttendees = attendees,
            accountsById = mapOf(1L to accountA),
            eventIdToCalendarId = mapOf(100L to 10L),
            calendarsById = mapOf(10L to cal1)
        )

        assertEquals(setOf(100L), result)
    }

    // ---- (b) declined attendee doesn't match account → not returned ----

    @Test
    fun `declined attendee that does not match owning account is excluded`() {
        val accountA = account(1, addresses = listOf("mailto:alice@icloud.com"))
        val cal1 = calendar(10, accountId = 1)
        val attendees = listOf(attendee(eventId = 100, address = "mailto:bob@example.com"))

        val result = selfDeclinedEventIds(
            declinedAttendees = attendees,
            accountsById = mapOf(1L to accountA),
            eventIdToCalendarId = mapOf(100L to 10L),
            calendarsById = mapOf(10L to cal1)
        )

        assertTrue(result.isEmpty())
    }

    // ---- (d) MULTI-ACCOUNT FIXTURE ----

    @Test
    fun `multi-account isolation - event in account B's calendar with A's address is not hidden under A`() {
        // A's address shows up as a decliner on event in B's calendar.
        // Matching account is determined by the calendar's accountId,
        // not by any configured account.
        val accountA = account(1, addresses = listOf("mailto:alice@icloud.com"))
        val accountB = account(2, addresses = listOf("mailto:bob@icloud.com"))
        val calA = calendar(10, accountId = 1)
        val calB = calendar(20, accountId = 2)

        val attendees = listOf(
            // event1 in A's calendar, A's address declined → MATCH
            attendee(eventId = 100, address = "mailto:alice@icloud.com"),
            // event2 in B's calendar, A's address listed as declined,
            // but B's matchesAttendee on alice's address returns false
            attendee(eventId = 200, address = "mailto:alice@icloud.com")
        )

        val result = selfDeclinedEventIds(
            declinedAttendees = attendees,
            accountsById = mapOf(1L to accountA, 2L to accountB),
            eventIdToCalendarId = mapOf(100L to 10L, 200L to 20L),
            calendarsById = mapOf(10L to calA, 20L to calB)
        )

        assertEquals(setOf(100L), result)
    }

    // ---- (e) calendar lookup misses ----

    @Test
    fun `orphaned event with calendarId not in calendarsById is gracefully skipped`() {
        val accountA = account(1, addresses = listOf("mailto:alice@icloud.com"))
        val attendees = listOf(attendee(eventId = 100, address = "mailto:alice@icloud.com"))

        val result = selfDeclinedEventIds(
            declinedAttendees = attendees,
            accountsById = mapOf(1L to accountA),
            eventIdToCalendarId = mapOf(100L to 999L),  // calendar 999 missing
            calendarsById = emptyMap()
        )

        assertTrue(result.isEmpty())
    }

    // ---- (f) account lookup misses ----

    @Test
    fun `calendar pointing at unknown account is gracefully skipped`() {
        val cal1 = calendar(10, accountId = 999)  // accountId 999 not in map
        val attendees = listOf(attendee(eventId = 100, address = "mailto:alice@icloud.com"))

        val result = selfDeclinedEventIds(
            declinedAttendees = attendees,
            accountsById = emptyMap(),
            eventIdToCalendarId = mapOf(100L to 10L),
            calendarsById = mapOf(10L to cal1)
        )

        assertTrue(result.isEmpty())
    }

    // ---- (g) account with empty addresses + non-email login ----

    @Test
    fun `account with empty addresses and non-email login does not match`() {
        // matchesAttendee returns false in this case (no fallback).
        val accountA = account(1, email = "alice", addresses = emptyList())
        val cal1 = calendar(10, accountId = 1)
        val attendees = listOf(attendee(eventId = 100, address = "mailto:alice@example.com"))

        val result = selfDeclinedEventIds(
            declinedAttendees = attendees,
            accountsById = mapOf(1L to accountA),
            eventIdToCalendarId = mapOf(100L to 10L),
            calendarsById = mapOf(10L to cal1)
        )

        assertTrue(result.isEmpty())
    }

    // ---- (h) empty inputs ----

    @Test
    fun `empty input returns empty set`() {
        val result = selfDeclinedEventIds(
            declinedAttendees = emptyList(),
            accountsById = emptyMap(),
            eventIdToCalendarId = emptyMap(),
            calendarsById = emptyMap()
        )
        assertTrue(result.isEmpty())
    }

    // ---- Email-fallback path: account with empty addresses but email-shaped login ----

    @Test
    fun `account with empty addresses but email-shaped login falls back to login match`() {
        val accountA = account(1, email = "alice@icloud.com", addresses = emptyList())
        val cal1 = calendar(10, accountId = 1)
        val attendees = listOf(attendee(eventId = 100, address = "mailto:alice@icloud.com"))

        val result = selfDeclinedEventIds(
            declinedAttendees = attendees,
            accountsById = mapOf(1L to accountA),
            eventIdToCalendarId = mapOf(100L to 10L),
            calendarsById = mapOf(10L to cal1)
        )

        assertEquals(setOf(100L), result)
    }

    // ---- Multiple declined attendees on one event: any match wins ----

    @Test
    fun `event with multiple declined attendees returns event when any match owner`() {
        val accountA = account(1, addresses = listOf("mailto:alice@icloud.com"))
        val cal1 = calendar(10, accountId = 1)
        val attendees = listOf(
            attendee(eventId = 100, address = "mailto:bob@example.com"),
            attendee(eventId = 100, address = "mailto:alice@icloud.com"),  // match
            attendee(eventId = 100, address = "mailto:carol@example.com")
        )

        val result = selfDeclinedEventIds(
            declinedAttendees = attendees,
            accountsById = mapOf(1L to accountA),
            eventIdToCalendarId = mapOf(100L to 10L),
            calendarsById = mapOf(10L to cal1)
        )

        assertEquals(setOf(100L), result)
    }
}
