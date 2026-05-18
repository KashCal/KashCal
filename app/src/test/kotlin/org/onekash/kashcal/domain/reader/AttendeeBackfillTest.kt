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
 * Unit tests for [AttendeeBackfill] — the on-demand rawIcal-parse-and-persist
 * helper that closes the etag-unchanged-skip gap on the inbound persistence
 * path (events whose etag hasn't changed since the attendees table was
 * added have empty attendee rows; we backfill from `event.rawIcal` on first
 * chip render).
 *
 * Idempotency contract: backfill is row-set idempotent via
 * [AttendeesDao.replaceForEvent]. Concurrent calls converge to the same
 * final state — no Mutex required.
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
        // Events synced under v23.7.16 stored Apple's principal-href as the
        // attendee.address because the parser didn't fall back to EMAIL=
        // parameter. Post-fix, on next chip render the backfill should
        // detect the malformed address and re-parse from rawIcal.
        coEvery { eventsDao.getById(1L) } returns event(rawIcal = ICS_WITH_ATTENDEES)
        coEvery { attendeesDao.getForEvent(1L) } returns kotlinx.coroutines.flow.flowOf(
            listOf(
                Attendee(eventId = 1L, address = "/646691839/principal/", sortOrder = 0),
                Attendee(eventId = 1L, address = "mailto:alice@example.test", sortOrder = 1)
            )
        )
        val result = backfill.backfillIfEmpty(1L)
        // Re-parse fired — the new mailto rows replace the malformed ones.
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
        // Master + exception in same VCALENDAR. Backfill must use the master's
        // attendees (the no-RECURRENCE-ID variant), not the exception's.
        coEvery { eventsDao.getById(1L) } returns event(rawIcal = ICS_MASTER_PLUS_EXCEPTION)
        coEvery { attendeesDao.getForEvent(1L) } returns kotlinx.coroutines.flow.flowOf(emptyList())
        val result = backfill.backfillIfEmpty(1L)
        assertEquals(2, result)
        coVerify(exactly = 1) {
            attendeesDao.replaceForEvent(1L, match { written ->
                // master had alice + bob; exception had alice + carol; we picked master
                written.size == 2 &&
                    written.any { it.address == "mailto:alice@example.test" } &&
                    written.any { it.address == "mailto:bob@example.test" } &&
                    written.none { it.address == "mailto:carol@example.test" }
            })
        }
    }

    @Test
    fun `returns 0 when rawIcal parses successfully but yields zero VEVENTs`() = runTest {
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
        // Garbage that throws inside ical4j parser
        coEvery { eventsDao.getById(1L) } returns event(rawIcal = "definitely not ics")
        coEvery { attendeesDao.getForEvent(1L) } returns kotlinx.coroutines.flow.flowOf(emptyList())
        val result = backfill.backfillIfEmpty(1L)
        assertEquals(0, result)
        coVerify(exactly = 0) { attendeesDao.replaceForEvent(any(), any()) }
    }

    @Test
    fun `filters attendees with blank or whitespace address before insert`() = runTest {
        // RFC 5545 §3.8.4.1 mandates a CAL-ADDRESS but malformed ICS may yield
        // empty-mailto attendees that would violate Room's NOT NULL constraint
        // on `address`. Defensive filter drops them.
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
        // First call: empty. Second call: also empty (caller hasn't observed
        // the write yet — simulates two concurrent screens).
        coEvery { attendeesDao.getForEvent(1L) } returns kotlinx.coroutines.flow.flowOf(emptyList())
        val a = backfill.backfillIfEmpty(1L)
        val b = backfill.backfillIfEmpty(1L)
        assertEquals(2, a)
        assertEquals(2, b)
        // Both calls invoke replaceForEvent — that's fine; it's @Transaction
        // delete-then-insert, so the final row set is identical.
        coVerify(exactly = 2) { attendeesDao.replaceForEvent(1L, any()) }
    }

    @Test
    fun `does not write when re-parse yields same address-set as existing rows (F4 idempotent skip)`() = runTest {
        // Pathological case: ATTENDEE with principal-href primary AND no
        // EMAIL= parameter. Parser preserves the principal-href (no fallback
        // succeeded). isUsableAddress rejects it again → re-parse triggered.
        // But the new row set is identical to existing → skip the write.
        // Without this guard, every sheet open re-runs replaceForEvent
        // (delete-then-insert) for no observable change.
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

        // Degenerate case: ATTENDEE primary value is a principal-href AND
        // there is no EMAIL= parameter. Parser preserves the principal-href.
        // Mapper persists `mailto:/646691839/principal/` which fails
        // isUsableAddress on every render — but F4 idempotent-skip prevents
        // the redundant transactional rewrite when the new set equals the
        // existing one.
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
