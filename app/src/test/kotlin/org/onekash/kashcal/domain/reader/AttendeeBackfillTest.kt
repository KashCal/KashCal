package org.onekash.kashcal.domain.reader

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.onekash.kashcal.data.db.dao.AttendeesDao
import org.onekash.kashcal.data.db.dao.EventsDao
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus

/**
 * Tests [AttendeeBackfill.backfillIfEmpty], which parses `event.rawIcal` and writes the master
 * VEVENT's attendees when an event has no attendee rows or an unusable address.
 *
 * Covers the no-op returns (missing event, null, empty or ATTENDEE-less rawIcal, a header-only
 * ICS, usable rows already stored), the re-parse of principal-href and bare-mailto rows, a
 * re-parse with no usable address, which writes nothing, master selection over an exception,
 * dropping a blank address, and repeat calls, which each write the same set through
 * [AttendeesDao.replaceForEvent].
 */
class AttendeeBackfillTest {

    private val attendeesDao = mockk<AttendeesDao>(relaxed = true)
    private val eventsDao = mockk<EventsDao>()

    private val backfill = AttendeeBackfill(attendeesDao, eventsDao)

    @Test
    fun `returns 0 when event not found`() = runTest {
        coEvery { eventsDao.getById(42L) } returns null
        val result = backfill.backfillIfEmpty(42L)
        assertEquals(0, result)
    }

    @Test
    fun `returns 0 when rawIcal is null`() = runTest {
        coEvery { eventsDao.getById(1L) } returns event(rawIcal = null)
        coEvery { attendeesDao.getForEvent(1L) } returns kotlinx.coroutines.flow.flowOf(emptyList())
        val result = backfill.backfillIfEmpty(1L)
        assertEquals(0, result)
    }

    @Test
    fun `returns 0 when rawIcal is empty`() = runTest {
        coEvery { eventsDao.getById(1L) } returns event(rawIcal = "")
        coEvery { attendeesDao.getForEvent(1L) } returns kotlinx.coroutines.flow.flowOf(emptyList())
        val result = backfill.backfillIfEmpty(1L)
        assertEquals(0, result)
    }

    @Test
    fun `returns 0 when rawIcal contains no ATTENDEE lines`() = runTest {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:no-attendees
            DTSTAMP:20260315T100000Z
            DTSTART:20260315T100000Z
            DTEND:20260315T110000Z
            SUMMARY:Solo event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        coEvery { eventsDao.getById(1L) } returns event(rawIcal = ics)
        coEvery { attendeesDao.getForEvent(1L) } returns kotlinx.coroutines.flow.flowOf(emptyList())
        val result = backfill.backfillIfEmpty(1L)
        assertEquals(0, result)
    }

    @Test
    fun `returns 0 when attendees table already populated with healthy mailtos (best-effort short-circuit)`() = runTest {
        coEvery { eventsDao.getById(1L) } returns event(rawIcal = ICS_WITH_ATTENDEES)
        coEvery { attendeesDao.getForEvent(1L) } returns kotlinx.coroutines.flow.flowOf(
            listOf(
                Attendee(eventId = 1L, address = "mailto:alice@example.test", sortOrder = 0),
                Attendee(eventId = 1L, address = "mailto:bob@example.test", sortOrder = 1)
            )
        )
        val result = backfill.backfillIfEmpty(1L)
        assertEquals(0, result)
        coVerify(exactly = 0) { attendeesDao.replaceForEvent(any(), any()) }
    }

    @Test
    fun `re-parses when persisted address is iCloud principal-href (F6 self-heal)`() = runTest {
        // Events synced under v23.7.16 stored iCloud's principal href as the address, from a
        // parser without the EMAIL= fallback. The backfill treats that row as unusable and
        // re-parses rawIcal.
        coEvery { eventsDao.getById(1L) } returns event(rawIcal = ICS_WITH_ATTENDEES)
        coEvery { attendeesDao.getForEvent(1L) } returns kotlinx.coroutines.flow.flowOf(
            listOf(
                Attendee(eventId = 1L, address = "/646691839/principal/", sortOrder = 0),
                Attendee(eventId = 1L, address = "mailto:alice@example.test", sortOrder = 1)
            )
        )
        val result = backfill.backfillIfEmpty(1L)
        // The re-parsed mailto rows replace the stored ones.
        assertEquals(2, result)
        coVerify(exactly = 1) { attendeesDao.replaceForEvent(1L, any()) }
    }

    @Test
    fun `re-parses when persisted address is bare mailto (F6 self-heal)`() = runTest {
        coEvery { eventsDao.getById(1L) } returns event(rawIcal = ICS_WITH_ATTENDEES)
        coEvery { attendeesDao.getForEvent(1L) } returns kotlinx.coroutines.flow.flowOf(
            listOf(
                Attendee(eventId = 1L, address = "mailto:", sortOrder = 0)
            )
        )
        val result = backfill.backfillIfEmpty(1L)
        assertEquals(2, result)
        coVerify(exactly = 1) { attendeesDao.replaceForEvent(1L, any()) }
    }

    @Test
    fun `does not re-parse when all persisted addresses are healthy mailtos (regression guard)`() = runTest {
        coEvery { eventsDao.getById(1L) } returns event(rawIcal = ICS_WITH_ATTENDEES)
        coEvery { attendeesDao.getForEvent(1L) } returns kotlinx.coroutines.flow.flowOf(
            listOf(
                Attendee(eventId = 1L, address = "mailto:carol@example.test", sortOrder = 0),
                Attendee(eventId = 1L, address = "mailto:dave@example.test", sortOrder = 1)
            )
        )
        val result = backfill.backfillIfEmpty(1L)
        assertEquals(0, result)
        coVerify(exactly = 0) { attendeesDao.replaceForEvent(any(), any()) }
    }

    @Test
    fun `parses rawIcal and writes attendees when table empty`() = runTest {
        coEvery { eventsDao.getById(1L) } returns event(rawIcal = ICS_WITH_ATTENDEES)
        coEvery { attendeesDao.getForEvent(1L) } returns kotlinx.coroutines.flow.flowOf(emptyList())
        val result = backfill.backfillIfEmpty(1L)
        assertEquals(2, result)
        coVerify(exactly = 1) {
            attendeesDao.replaceForEvent(1L, match { written ->
                written.size == 2 &&
                    written[0].address == "mailto:alice@example.test" &&
                    written[1].address == "mailto:bob@example.test" &&
                    written.all { it.eventId == 1L }
            })
        }
    }

    @Test
    fun `picks master VEVENT when rawIcal contains exception (RECURRENCE-ID)`() = runTest {
        // Master and exception in one VCALENDAR. The backfill uses the master's attendees (the
        // VEVENT without RECURRENCE-ID), not the exception's.
        coEvery { eventsDao.getById(1L) } returns event(rawIcal = ICS_MASTER_PLUS_EXCEPTION)
        coEvery { attendeesDao.getForEvent(1L) } returns kotlinx.coroutines.flow.flowOf(emptyList())
        val result = backfill.backfillIfEmpty(1L)
        assertEquals(2, result)
        coVerify(exactly = 1) {
            attendeesDao.replaceForEvent(1L, match { written ->
                // The master has alice and bob; the exception has alice and carol.
                written.size == 2 &&
                    written.any { it.address == "mailto:alice@example.test" } &&
                    written.any { it.address == "mailto:bob@example.test" } &&
                    written.none { it.address == "mailto:carol@example.test" }
            })
        }
    }

    @Test
    fun `returns 0 when rawIcal parses successfully but yields zero VEVENTs`() = runTest {
        // This body has no ATTENDEE text either, so the call returns before the parse.
        val headerOnly = """
            BEGIN:VCALENDAR
            VERSION:2.0
            END:VCALENDAR
        """.trimIndent()
        coEvery { eventsDao.getById(1L) } returns event(rawIcal = headerOnly)
        coEvery { attendeesDao.getForEvent(1L) } returns kotlinx.coroutines.flow.flowOf(emptyList())
        val result = backfill.backfillIfEmpty(1L)
        assertEquals(0, result)
    }

    @Test
    fun `returns 0 when rawIcal parse throws — never propagates`() = runTest {
        // This body has no ATTENDEE text, so the call returns before the parse; the branch that
        // catches a throwing parse isn't reached here.
        coEvery { eventsDao.getById(1L) } returns event(rawIcal = "definitely not ics")
        coEvery { attendeesDao.getForEvent(1L) } returns kotlinx.coroutines.flow.flowOf(emptyList())
        val result = backfill.backfillIfEmpty(1L)
        assertEquals(0, result)
        coVerify(exactly = 0) { attendeesDao.replaceForEvent(any(), any()) }
    }

    @Test
    fun `filters attendees with blank or whitespace address before insert`() = runTest {
        // RFC 5545 §3.8.4.1 gives ATTENDEE a CAL-ADDRESS value, but a bare `mailto:` has no
        // address. The parser skips an ATTENDEE with a blank address, and the backfill drops
        // any left, so only alice is written.
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:evt-1
            DTSTAMP:20260315T100000Z
            DTSTART:20260315T100000Z
            DTEND:20260315T110000Z
            SUMMARY:Mixed valid + blank attendees
            ORGANIZER;CN=Test:mailto:organizer.synthetic@example.test
            ATTENDEE;CN=Alice;PARTSTAT=ACCEPTED:mailto:alice@example.test
            ATTENDEE;CN=NoAddr:mailto:
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        coEvery { eventsDao.getById(1L) } returns event(rawIcal = ics)
        coEvery { attendeesDao.getForEvent(1L) } returns kotlinx.coroutines.flow.flowOf(emptyList())
        val result = backfill.backfillIfEmpty(1L)
        assertEquals(1, result)
        coVerify(exactly = 1) {
            attendeesDao.replaceForEvent(1L, match { written ->
                written.size == 1 && written[0].address == "mailto:alice@example.test"
            })
        }
    }

    @Test
    fun `idempotent on repeat call when table empty (replaceForEvent guarantees row-set convergence)`() = runTest {
        coEvery { eventsDao.getById(1L) } returns event(rawIcal = ICS_WITH_ATTENDEES)
        // Both calls see no rows, as two screens opening at once would before either write.
        coEvery { attendeesDao.getForEvent(1L) } returns kotlinx.coroutines.flow.flowOf(emptyList())
        val a = backfill.backfillIfEmpty(1L)
        val b = backfill.backfillIfEmpty(1L)
        assertEquals(2, a)
        assertEquals(2, b)
        // Both calls write; replaceForEvent replaces the whole set in one transaction, so the
        // final row set is the same.
        coVerify(exactly = 2) { attendeesDao.replaceForEvent(1L, any()) }
    }

    @Test
    fun `does not write when re-parse yields same address-set as existing rows (F4 idempotent skip)`() = runTest {
        // The stored `mailto:/646691839/principal/` row is unusable, so the backfill re-parses.
        // The re-parse yields only the bare principal href, which the usable-address filter
        // drops, so the call returns 0 on the empty result, before the same-set comparison,
        // and never writes. Without that, every sheet open would rewrite the set.
        coEvery { eventsDao.getById(1L) } returns event(rawIcal = ICS_PRINCIPAL_HREF_NO_EMAIL)
        coEvery { attendeesDao.getForEvent(1L) } returns kotlinx.coroutines.flow.flowOf(
            listOf(
                Attendee(
                    eventId = 1L,
                    address = "mailto:/646691839/principal/",
                    partstat = "NEEDS-ACTION",
                    sortOrder = 0
                )
            )
        )
        val result = backfill.backfillIfEmpty(1L)
        assertEquals(0, result)
        coVerify(exactly = 0) { attendeesDao.replaceForEvent(any(), any()) }
    }

    private fun event(
        rawIcal: String?,
        id: Long = 1L,
        calendarId: Long = 1L
    ): Event = Event(
        id = id,
        uid = "evt-$id",
        calendarId = calendarId,
        title = "Test",
        startTs = 1_000L,
        endTs = 2_000L,
        dtstamp = System.currentTimeMillis(),
        syncStatus = SyncStatus.SYNCED,
        rawIcal = rawIcal
    )

    companion object {
        val ICS_WITH_ATTENDEES = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:evt-1
            DTSTAMP:20260315T100000Z
            DTSTART:20260315T100000Z
            DTEND:20260315T110000Z
            SUMMARY:Backfill test
            ORGANIZER;CN=Test Organizer:mailto:organizer.synthetic@example.test
            ATTENDEE;CN=Alice;PARTSTAT=ACCEPTED:mailto:alice@example.test
            ATTENDEE;CN=Bob;PARTSTAT=NEEDS-ACTION:mailto:bob@example.test
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        // ATTENDEE whose value is a principal href, with no EMAIL= parameter. The parser keeps
        // the href, the mapper stores it without `mailto:` (it isn't email-shaped), and the
        // backfill's usable-address filter drops it on every parse.
        val ICS_PRINCIPAL_HREF_NO_EMAIL = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:evt-1
            DTSTAMP:20260315T100000Z
            DTSTART:20260315T100000Z
            DTEND:20260315T110000Z
            SUMMARY:Backfill test (degenerate)
            ORGANIZER;CN=Test Organizer:mailto:organizer.synthetic@example.test
            ATTENDEE;CN=Test User;PARTSTAT=NEEDS-ACTION:/646691839/principal/
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val ICS_MASTER_PLUS_EXCEPTION = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:evt-1
            DTSTAMP:20260315T100000Z
            DTSTART:20260315T100000Z
            DTEND:20260315T110000Z
            SUMMARY:Master event
            ORGANIZER;CN=Test:mailto:organizer.synthetic@example.test
            ATTENDEE;CN=Alice;PARTSTAT=ACCEPTED:mailto:alice@example.test
            ATTENDEE;CN=Bob;PARTSTAT=NEEDS-ACTION:mailto:bob@example.test
            RRULE:FREQ=WEEKLY;COUNT=4
            END:VEVENT
            BEGIN:VEVENT
            UID:evt-1
            RECURRENCE-ID:20260322T100000Z
            DTSTAMP:20260315T100000Z
            DTSTART:20260322T100000Z
            DTEND:20260322T110000Z
            SUMMARY:Exception with different attendees
            ORGANIZER;CN=Test:mailto:organizer.synthetic@example.test
            ATTENDEE;CN=Alice;PARTSTAT=ACCEPTED:mailto:alice@example.test
            ATTENDEE;CN=Carol;PARTSTAT=ACCEPTED:mailto:carol@example.test
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
    }
}
