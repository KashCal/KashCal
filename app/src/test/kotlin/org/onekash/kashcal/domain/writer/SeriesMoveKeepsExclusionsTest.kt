package org.onekash.kashcal.domain.writer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.onekash.kashcal.testutil.TestDatabases
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.TimeZone

/**
 * An occurrence deleted in KashCal is an EXDATE at that occurrence's start, matched by instant
 * (RFC 5545 §3.8.5.1). When the series moves (its date, start time, zone or all-day flag),
 * [EventWriter] moves those EXDATEs with it, so the deleted day stays hidden, as other clients
 * rewrite them, and re-keys its changed occurrences (RECURRENCE-ID, §3.8.4.4); a move the rule
 * can't follow is refused.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SeriesMoveKeepsExclusionsTest {

    private lateinit var db: KashCalDatabase
    private lateinit var generator: OccurrenceGenerator
    private lateinit var writer: EventWriter
    private var calendarId = 0L
    private val savedZone: TimeZone = TimeZone.getDefault()
    private val newYork = ZoneId.of("America/New_York")
    private val berlin = ZoneId.of("Europe/Berlin")
    private val hour = 3_600_000L

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone(newYork))
        val context: Context = ApplicationProvider.getApplicationContext()
        db = TestDatabases.inMemory(context).allowMainThreadQueries().build()
        generator = OccurrenceGenerator(db, db.occurrencesDao(), db.eventsDao(), TestDataStoreFactory.createDefault())
        writer = EventWriter(db, generator)
        runTest {
            val accountId = db.accountsDao().insert(Account(provider = AccountProvider.LOCAL, email = "local"))
            calendarId = db.calendarsDao().insert(Calendar(accountId = accountId, caldavUrl = "local://default", displayName = "Local", color = 0))
        }
    }

    @After
    fun tearDown() {
        db.close()
        TimeZone.setDefault(savedZone)
    }

    private fun at(zone: ZoneId, y: Int, m: Int, d: Int, h: Int, min: Int = 0): Long =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli()

    private fun utcMidnight(y: Int, m: Int, d: Int): Long =
        LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private suspend fun series(start: Long, rrule: String = "FREQ=DAILY;COUNT=5", zone: String? = "America/New_York", allDay: Boolean = false): Event =
        writer.createEvent(
            Event(
                uid = "", calendarId = calendarId, title = "Series",
                startTs = start, endTs = if (allDay) start + 86_400_000L - 1 else start + hour,
                timezone = zone, isAllDay = allDay, rrule = rrule, dtstamp = 0L,
            ),
            isLocal = true,
        )

    private suspend fun deleteOn(event: Event, occurrenceStart: Long): Event {
        writer.deleteSingleOccurrence(event.id, occurrenceStart, isLocal = true)
        return db.eventsDao().getById(event.id)!!
    }

    private suspend fun shown(id: Long): List<Long> =
        db.occurrencesDao().getForEvent(id).filter { !it.isCancelled }.map { it.startTs }.sorted()

    @Test
    fun `moving the series' time keeps a deleted day hidden`() = runTest {
        val s = deleteOn(series(at(newYork, 2027, 2, 1, 10)), at(newYork, 2027, 2, 3, 10))

        writer.updateEvent(s.copy(startTs = s.startTs + hour, endTs = s.endTs + hour), isLocal = true)

        assertEquals(listOf(1, 2, 4, 5).map { at(newYork, 2027, 2, it, 11) }, shown(s.id))
        assertEquals("${at(newYork, 2027, 2, 3, 11)}", db.eventsDao().getById(s.id)!!.exdate)
    }

    @Test
    fun `changing the series' zone at the same clock time keeps a deleted day hidden`() = runTest {
        val s = deleteOn(series(at(newYork, 2027, 2, 1, 10)), at(newYork, 2027, 2, 3, 10))
        val start = at(berlin, 2027, 2, 1, 10)

        writer.updateEvent(s.copy(startTs = start, endTs = start + hour, timezone = "Europe/Berlin"), isLocal = true)

        assertEquals(listOf(1, 2, 4, 5).map { at(berlin, 2027, 2, it, 10) }, shown(s.id))
    }

    @Test
    fun `a deleted day after a clock change stays hidden when the time moves`() = runTest {
        // Mondays from 1 Mar 2027; New York moves to EDT on 14 Mar.
        val s = deleteOn(series(at(newYork, 2027, 3, 1, 10), "FREQ=WEEKLY;COUNT=4"), at(newYork, 2027, 3, 15, 10))

        writer.updateEvent(s.copy(startTs = s.startTs + hour, endTs = s.endTs + hour), isLocal = true)

        assertEquals(listOf(1, 8, 22).map { at(newYork, 2027, 3, it, 11) }, shown(s.id))
    }

    @Test
    fun `a this-and-future split with a new time keeps a later deleted day hidden`() = runTest {
        val master = deleteOn(series(at(newYork, 2027, 2, 1, 10), "FREQ=DAILY;COUNT=10"), at(newYork, 2027, 2, 6, 10))
        val splitAt = at(newYork, 2027, 2, 4, 10)
        val newStart = at(newYork, 2027, 2, 4, 11)

        val created = writer.splitSeries(master.id, splitAt, master.copy(startTs = newStart, endTs = newStart + hour), isLocal = true)

        val shownAfter = shown(created.id)
        assertFalse(shownAfter.contains(at(newYork, 2027, 2, 6, 11)))
        assertEquals(listOf(4, 5, 7, 8, 9, 10).map { at(newYork, 2027, 2, it, 11) }, shownAfter)
    }

    @Test
    fun `switching an all-day series to timed keeps a deleted day hidden`() = runTest {
        TimeZone.setDefault(TimeZone.getTimeZone(berlin))
        val s = deleteOn(series(utcMidnight(2027, 2, 1), zone = null, allDay = true), utcMidnight(2027, 2, 3))
        val start = at(berlin, 2027, 2, 1, 10)

        writer.updateEvent(s.copy(isAllDay = false, startTs = start, endTs = start + hour, timezone = "Europe/Berlin"), isLocal = true)

        assertEquals(listOf(1, 2, 4, 5).map { at(berlin, 2027, 2, it, 10) }, shown(s.id))
    }

    @Test
    fun `a deleted day moved into a clock gap resolves as the occurrence does`() = runTest {
        // Daily 01:30 New York around 14 Mar 2027, when 02:00-03:00 doesn't exist.
        val s = deleteOn(series(at(newYork, 2027, 3, 12, 1, 30)), at(newYork, 2027, 3, 14, 1, 30))

        writer.updateEvent(s.copy(startTs = s.startTs + hour, endTs = s.endTs + hour), isLocal = true)

        val starts = shown(s.id)
        assertEquals(4, starts.size)
        assertFalse(starts.any { ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(it), newYork).dayOfMonth == 14 })
    }

    @Test
    fun `moving the series' start date moves its days and the deleted day with it`() = runTest {
        // Mon, Wed, Fri at 10:00 from Mon 1 Feb 2027, Wed 10 Feb deleted. The start moves a day
        // to Tue 2 Feb: the series becomes Tue, Thu, Sat and the deletion moves to Thu 11 Feb.
        val s = deleteOn(series(at(newYork, 2027, 2, 1, 10), "FREQ=WEEKLY;BYDAY=MO,WE,FR;COUNT=9"), at(newYork, 2027, 2, 10, 10))
        val start = at(newYork, 2027, 2, 2, 10)

        writer.updateEvent(s.copy(startTs = start, endTs = start + hour), isLocal = true)

        assertEquals("FREQ=WEEKLY;BYDAY=TU,TH,SA;COUNT=9", db.eventsDao().getById(s.id)!!.rrule)
        val days = shown(s.id).map { ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(it), newYork).dayOfMonth }
        assertEquals(listOf(2, 4, 6, 9, 13, 16, 18, 20), days)
    }

    @Test
    fun `turning a series all-day moves its deleted day to that date`() = runTest {
        val d = deleteOn(series(at(newYork, 2027, 2, 1, 10)), at(newYork, 2027, 2, 3, 10))
        writer.updateEvent(d.copy(isAllDay = true, startTs = utcMidnight(2027, 2, 1), endTs = utcMidnight(2027, 2, 2) - 1, timezone = null), isLocal = true)
        assertEquals("${utcMidnight(2027, 2, 3)}", db.eventsDao().getById(d.id)!!.exdate)
        assertEquals(4, shown(d.id).size)
    }

    @Test
    fun `exclusions the edit doesn't move stay as stored`() = runTest {
        // The caller set its own EXDATE.
        val a = deleteOn(series(at(newYork, 2027, 2, 1, 10)), at(newYork, 2027, 2, 3, 10))
        writer.updateEvent(a.copy(startTs = a.startTs + hour, endTs = a.endTs + hour, exdate = "${at(newYork, 2027, 2, 2, 11)}"), isLocal = true)
        assertEquals("${at(newYork, 2027, 2, 2, 11)}", db.eventsDao().getById(a.id)!!.exdate)

        // Nothing about the start moved.
        val b = deleteOn(series(at(newYork, 2027, 2, 1, 10)), at(newYork, 2027, 2, 3, 10))
        writer.updateEvent(b.copy(title = "Renamed"), isLocal = true)
        assertEquals(b.exdate, db.eventsDao().getById(b.id)!!.exdate)

        // A rule with clock parts: DTSTART's time doesn't place every occurrence, so a new time
        // can't be expressed and nothing is written.
        val c = deleteOn(series(at(newYork, 2027, 2, 1, 10), "FREQ=DAILY;BYHOUR=10,15;COUNT=6"), at(newYork, 2027, 2, 2, 15))
        val refused = runCatching { writer.updateEvent(c.copy(startTs = c.startTs + hour, endTs = c.endTs + hour), isLocal = true) }
        assertTrue(refused.exceptionOrNull() is org.onekash.kashcal.domain.rrule.MoveNotExpressibleException)
        assertEquals(c, db.eventsDao().getById(c.id)!!)

        // RDATEs keep their own instants.
        val rdate = "${at(newYork, 2027, 2, 3, 15)}"
        val e = db.eventsDao().getById(series(at(newYork, 2027, 2, 1, 10)).id)!!.copy(rdate = rdate)
        db.eventsDao().update(e)
        writer.updateEvent(e.copy(startTs = e.startTs + hour, endTs = e.endTs + hour), isLocal = true)
        assertEquals(rdate, db.eventsDao().getById(e.id)!!.rdate)
    }

    // ---- changed occurrences move with the series ----

    private suspend fun change(master: Event, occurrenceStart: Long, edit: (Event) -> Event): Event {
        val m = db.eventsDao().getById(master.id)!!
        return writer.editSingleOccurrence(master.id, occurrenceStart, edit(m.copy(startTs = occurrenceStart, endTs = occurrenceStart + hour)), isLocal = true)
    }

    private suspend fun exceptions(id: Long) = db.eventsDao().getExceptionsForMaster(id).sortedBy { it.originalInstanceTime }

    @Test
    fun `an hour later moves deletions, renamed occurrences and the end, and keeps an edited time`() = runTest {
        // Daily 10:00 from Mon 1 Feb 2027 to Fri 5 Feb (UNTIL = last second of Feb 5).
        val s0 = series(at(newYork, 2027, 2, 1, 10), "FREQ=DAILY;UNTIL=20270206T045959Z")
        change(s0, at(newYork, 2027, 2, 2, 10)) { it.copy(title = "Renamed") }
        change(s0, at(newYork, 2027, 2, 4, 10)) { it.copy(startTs = at(newYork, 2027, 2, 4, 15), endTs = at(newYork, 2027, 2, 4, 16)) }
        val s = deleteOn(s0, at(newYork, 2027, 2, 3, 10))

        writer.updateEvent(s.copy(startTs = s.startTs + hour, endTs = s.endTs + hour), isLocal = true)

        assertEquals("${at(newYork, 2027, 2, 3, 11)}", db.eventsDao().getById(s.id)!!.exdate)
        val (renamed, edited) = exceptions(s.id)
        assertEquals(at(newYork, 2027, 2, 2, 11), renamed.originalInstanceTime)
        assertEquals(at(newYork, 2027, 2, 2, 11), renamed.startTs)
        assertEquals(at(newYork, 2027, 2, 4, 11), edited.originalInstanceTime)
        assertEquals(at(newYork, 2027, 2, 4, 15), edited.startTs)
        // Feb 1, 2 (renamed), 4 (at 15:00), 5: four rows, no duplicate, the last day kept.
        val rows = db.occurrencesDao().getForEvent(s.id).filter { !it.isCancelled }
        assertEquals(4, rows.size)
        assertEquals(2, rows.count { it.exceptionEventId != null })
        assertTrue(rows.any { it.startTs == at(newYork, 2027, 2, 5, 11) })
    }

    @Test
    fun `a typed count change with a new time still moves deletions and changed occurrences`() = runTest {
        val s0 = series(at(newYork, 2027, 2, 1, 10), "FREQ=DAILY;COUNT=5")
        change(s0, at(newYork, 2027, 2, 2, 10)) { it.copy(title = "Renamed") }
        val s = deleteOn(s0, at(newYork, 2027, 2, 3, 10))

        writer.updateEvent(s.copy(startTs = s.startTs + hour, endTs = s.endTs + hour, rrule = "FREQ=DAILY;COUNT=7"), isLocal = true)

        val saved = db.eventsDao().getById(s.id)!!
        assertEquals("FREQ=DAILY;COUNT=7", saved.rrule)
        assertEquals("${at(newYork, 2027, 2, 3, 11)}", saved.exdate)
        assertEquals(at(newYork, 2027, 2, 2, 11), exceptions(s.id).single().originalInstanceTime)
    }

    @Test
    fun `only the series is queued and moved changed occurrences take a higher sequence`() = runTest {
        val s0 = series(at(newYork, 2027, 2, 1, 10))
        val ex = change(s0, at(newYork, 2027, 2, 2, 10)) { it.copy(title = "Renamed") }
        db.pendingOperationsDao().getAllOnce().forEach { db.pendingOperationsDao().deleteById(it.id) }
        val s = db.eventsDao().getById(s0.id)!!

        writer.updateEvent(s.copy(startTs = s.startTs + hour, endTs = s.endTs + hour), isLocal = false)

        assertEquals(listOf(s.id), db.pendingOperationsDao().getAllOnce().map { it.eventId })
        val master = db.eventsDao().getById(s.id)!!
        val moved = exceptions(s.id).single()
        assertEquals(org.onekash.kashcal.data.db.entity.SyncStatus.SYNCED, moved.syncStatus)
        assertTrue(moved.sequence >= master.sequence && moved.sequence > ex.sequence)
    }

    @Test
    fun `a changed occurrence with no key is left as it is`() = runTest {
        val s = series(at(newYork, 2027, 2, 1, 10))
        val orphan = Event(uid = s.uid, calendarId = calendarId, title = "Loose", startTs = at(newYork, 2027, 2, 2, 12),
            endTs = at(newYork, 2027, 2, 2, 13), timezone = "America/New_York", originalEventId = s.id, dtstamp = 0L)
        val id = db.eventsDao().insert(orphan)

        writer.updateEvent(s.copy(startTs = s.startTs + hour, endTs = s.endTs + hour), isLocal = true)

        val after = db.eventsDao().getById(id)!!
        assertNull(after.originalInstanceTime)
        assertEquals(orphan.startTs to orphan.endTs, after.startTs to after.endTs)
    }

    @Test
    fun `a split drops deletions before it from the new series`() = runTest {
        val s = deleteOn(series(at(newYork, 2027, 2, 1, 10), "FREQ=DAILY;COUNT=6"), at(newYork, 2027, 2, 2, 10))
        val split = at(newYork, 2027, 2, 4, 10)

        val created = writer.splitSeries(s.id, split, s.copy(startTs = split + hour, endTs = split + 2 * hour), isLocal = true)

        assertNull(created.exdate)
        assertEquals(3, shown(created.id).size)
    }

    @Test
    fun `a day earlier moves changed occurrences on consecutive days`() = runTest {
        // Guards the write against depending on order: date order already works this way round.
        // Daily from Fri 5 Feb 2027, changed on 6 and 7 Feb; the series starts a day earlier.
        val s0 = series(at(newYork, 2027, 2, 5, 10), "FREQ=DAILY;COUNT=6")
        change(s0, at(newYork, 2027, 2, 6, 10)) { it.copy(title = "First") }
        change(s0, at(newYork, 2027, 2, 7, 10)) { it.copy(title = "Second") }
        val s = db.eventsDao().getById(s0.id)!!

        writer.updateEvent(s.copy(startTs = s.startTs - 24 * hour, endTs = s.endTs - 24 * hour), isLocal = true)

        val keys = exceptions(s.id).associate { it.title to it.originalInstanceTime }
        assertEquals(mapOf("First" to at(newYork, 2027, 2, 5, 10), "Second" to at(newYork, 2027, 2, 6, 10)), keys)
    }

    @Test
    fun `a weekday series moved a day later moves changed Monday and Tuesday`() = runTest {
        // Weekdays from Mon 1 Feb 2027; Mon 8 and Tue 9 Feb changed; the series moves to
        // Tuesdays to Saturdays.
        val s0 = series(at(newYork, 2027, 2, 1, 10), "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR;COUNT=10")
        change(s0, at(newYork, 2027, 2, 8, 10)) { it.copy(title = "Mon") }
        change(s0, at(newYork, 2027, 2, 9, 10)) { it.copy(title = "Tue") }
        val s = db.eventsDao().getById(s0.id)!!

        writer.updateEvent(s.copy(startTs = s.startTs + 24 * hour, endTs = s.endTs + 24 * hour), isLocal = true)

        val keys = exceptions(s.id).associate { it.title to it.originalInstanceTime }
        assertEquals(mapOf("Mon" to at(newYork, 2027, 2, 9, 10), "Tue" to at(newYork, 2027, 2, 10, 10)), keys)
        assertEquals("FREQ=WEEKLY;BYDAY=TU,WE,TH,FR,SA;COUNT=10", db.eventsDao().getById(s.id)!!.rrule)
    }

    @Test
    fun `this and future from the first occurrence moves changed occurrences on consecutive days`() = runTest {
        // A split at the series start edits the series in place.
        val s0 = series(at(newYork, 2027, 2, 5, 10), "FREQ=DAILY;COUNT=6")
        change(s0, at(newYork, 2027, 2, 6, 10)) { it.copy(title = "First") }
        change(s0, at(newYork, 2027, 2, 7, 10)) { it.copy(title = "Second") }
        val s = db.eventsDao().getById(s0.id)!!

        writer.splitSeries(s.id, s.startTs, s.copy(startTs = s.startTs + 24 * hour, endTs = s.endTs + 24 * hour), isLocal = true)

        val keys = exceptions(s.id).associate { it.title to it.originalInstanceTime }
        assertEquals(mapOf("First" to at(newYork, 2027, 2, 7, 10), "Second" to at(newYork, 2027, 2, 8, 10)), keys)
    }

    @Test
    fun `a split keeps the old series' earlier changed occurrence at its key`() = runTest {
        val s0 = series(at(newYork, 2027, 2, 1, 10), "FREQ=DAILY;COUNT=6")
        val key = at(newYork, 2027, 2, 2, 10)
        change(s0, key) { it.copy(title = "Renamed") }
        val s = db.eventsDao().getById(s0.id)!!
        val split = at(newYork, 2027, 2, 4, 10)

        writer.splitSeries(s.id, split, s.copy(startTs = split + hour, endTs = split + 2 * hour), isLocal = true)

        val kept = exceptions(s.id).single()
        assertEquals(key, kept.originalInstanceTime)
        assertEquals(key, kept.startTs)
        assertEquals(1, db.occurrencesDao().getForEvent(s.id).count { it.exceptionEventId == kept.id && it.startTs == key })
    }

    @Test
    fun `turning a series all-day moves renamed occurrences and keeps edited ones`() = runTest {
        val s0 = series(at(newYork, 2027, 2, 1, 10))
        change(s0, at(newYork, 2027, 2, 2, 10)) { it.copy(title = "Renamed") }
        change(s0, at(newYork, 2027, 2, 3, 10)) { it.copy(startTs = at(newYork, 2027, 2, 3, 15), endTs = at(newYork, 2027, 2, 3, 16)) }
        val s = db.eventsDao().getById(s0.id)!!

        writer.updateEvent(s.copy(isAllDay = true, startTs = utcMidnight(2027, 2, 1), endTs = utcMidnight(2027, 2, 2) - 1, timezone = null), isLocal = true)

        val (renamed, edited) = exceptions(s.id)
        assertTrue(renamed.isAllDay)
        assertEquals(utcMidnight(2027, 2, 2), renamed.startTs)
        assertEquals(utcMidnight(2027, 2, 2), renamed.originalInstanceTime)
        assertFalse(edited.isAllDay)
        assertEquals(at(newYork, 2027, 2, 3, 15), edited.startTs)
        assertEquals(utcMidnight(2027, 2, 3), edited.originalInstanceTime)
    }

    @Test
    fun `an open-ended series years old moves last week's changes`() = runTest {
        val start = at(newYork, 2025, 3, 1, 9)
        val s0 = series(start, "FREQ=DAILY")
        val key = at(newYork, 2027, 1, 20, 9)
        change(s0, key) { it.copy(title = "Renamed") }
        val s = deleteOn(s0, at(newYork, 2027, 1, 21, 9))

        writer.updateEvent(s.copy(startTs = s.startTs + hour, endTs = s.endTs + hour), isLocal = true)

        assertEquals(at(newYork, 2027, 1, 20, 10), exceptions(s.id).single().originalInstanceTime)
        assertEquals("${at(newYork, 2027, 1, 21, 10)}", db.eventsDao().getById(s.id)!!.exdate)
    }
}
