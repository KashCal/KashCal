package org.onekash.kashcal.domain.generator

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.onekash.kashcal.testutil.TestDatabases
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone

/**
 * EXDATE and RDATE through the path a pulled or imported series takes: [ICalParser], then
 * [ICalEventMapper.toEntity], then the [OccurrenceGenerator] over Room, in America/New_York.
 *
 * RFC 5545 §3.8.5.1-2 builds the recurrence set from start DATE-TIME values: RRULE and RDATE
 * instances are gathered (duplicates count once) and those named by EXDATE removed. A DATE-TIME
 * EXDATE or RDATE therefore matches by instant, not by calendar day; a DATE value names a day.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class OffTimeExdateRdatePipelineTest {

    private lateinit var database: KashCalDatabase
    private lateinit var generator: OccurrenceGenerator
    private var calendarId = 0L
    private val savedZone: TimeZone = TimeZone.getDefault()
    private val newYork = ZoneId.of("America/New_York")

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone(newYork))
        val context: Context = ApplicationProvider.getApplicationContext()
        database = TestDatabases.inMemory(context).allowMainThreadQueries().build()
        generator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao(), TestDataStoreFactory.createDefault())
        runTest {
            val accountId = database.accountsDao().insert(Account(provider = AccountProvider.CALDAV, email = "user@example.test"))
            calendarId = database.calendarsDao().insert(
                Calendar(accountId = accountId, caldavUrl = "https://caldav.example.test/cal/", displayName = "Cal", color = 0)
            )
        }
    }

    @After
    fun tearDown() {
        database.close()
        TimeZone.setDefault(savedZone)
    }

    private fun ny(month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(2027, month, day, hour, minute, 0, 0, newYork).toInstant().toEpochMilli()

    private fun ics(vararg lines: String): String = (listOf(
        "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Test//Test//EN",
        "BEGIN:VTIMEZONE", "TZID:America/New_York",
        "BEGIN:DAYLIGHT", "TZOFFSETFROM:-0500", "TZOFFSETTO:-0400", "TZNAME:EDT",
        "DTSTART:19700308T020000", "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=2SU", "END:DAYLIGHT",
        "BEGIN:STANDARD", "TZOFFSETFROM:-0400", "TZOFFSETTO:-0500", "TZNAME:EST",
        "DTSTART:19701101T020000", "RRULE:FREQ=YEARLY;BYMONTH=11;BYDAY=1SU", "END:STANDARD",
        "END:VTIMEZONE",
        "BEGIN:VEVENT", "UID:off-time@example.test", "DTSTAMP:20261025T000000Z",
    ) + lines + listOf("END:VEVENT", "END:VCALENDAR", "")).joinToString("\r\n")

    /** Parses, maps and stores [body] as the pull does, and returns the occurrences shown. */
    private suspend fun shown(body: String): List<Long> {
        val event = store(body)
        generator.generateOccurrences(event, ny(2, 1, 0), ny(5, 1, 0))
        return visible(event)
    }

    private suspend fun store(body: String): Event {
        val parsed = ICalParser().parseAllEvents(body).getOrNull()!!.single()
        val mapped = ICalEventMapper.toEntity(parsed, body, calendarId, "https://caldav.example.test/cal/a.ics", "\"1\"").event
        val id = database.eventsDao().insert(mapped)
        return database.eventsDao().getById(id)!!
    }

    private suspend fun visible(event: Event): List<Long> =
        database.occurrencesDao().getForEvent(event.id).filter { !it.isCancelled }.map { it.startTs }.sorted()

    private val timedSeries = arrayOf(
        "DTSTART;TZID=America/New_York:20270301T100000", "DTEND;TZID=America/New_York:20270301T110000",
        "RRULE:FREQ=DAILY;COUNT=6", "SUMMARY:Daily",
    )

    @Test
    fun `an EXDATE and an RDATE at other times of day give the RFC 5545 recurrence set`() = runTest {
        val shown = shown(ics(
            *timedSeries,
            "EXDATE;TZID=America/New_York:20270303T090000",
            "RDATE;TZID=America/New_York:20270304T150000",
        ))
        // 1-6 Mar at 10:00 (the 09:00 EXDATE names no occurrence), plus 4 Mar 15:00.
        val expected = ((1..6).map { ny(3, it, 10) } + ny(3, 4, 15)).sorted()
        assertEquals(expected, shown)
    }

    @Test
    fun `an EXDATE in UTC form at an occurrence's instant removes it`() = runTest {
        // 3 Mar 2027 10:00 EST is 15:00Z.
        val shown = shown(ics(*timedSeries, "EXDATE:20270303T150000Z"))
        assertEquals((1..6).filter { it != 3 }.map { ny(3, it, 10) }, shown)
    }

    @Test
    fun `an RDATE at an RRULE occurrence's instant is counted once`() = runTest {
        val shown = shown(ics(*timedSeries, "RDATE;TZID=America/New_York:20270304T100000"))
        assertEquals((1..6).map { ny(3, it, 10) }, shown)
    }

    @Test
    fun `an EXDATE at an RDATE's instant removes only that RDATE`() = runTest {
        val shown = shown(ics(
            *timedSeries,
            "RDATE;TZID=America/New_York:20270304T150000",
            "EXDATE;TZID=America/New_York:20270304T150000",
        ))
        assertEquals((1..6).map { ny(3, it, 10) }, shown)
    }

    @Test
    fun `a DATE EXDATE on an all-day series removes that day`() = runTest {
        val shown = shown(ics(
            "DTSTART;VALUE=DATE:20270301", "DTEND;VALUE=DATE:20270302", "RRULE:FREQ=DAILY;COUNT=3",
            "EXDATE;VALUE=DATE:20270302", "SUMMARY:All day",
        ))
        val utc = { d: Int -> ZonedDateTime.of(2027, 3, d, 0, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli() }
        assertEquals(listOf(utc(1), utc(3)), shown)
    }

    @Test
    fun `a DATE code passed to the preview excludes that day's occurrence of a timed series`() {
        val start = ny(3, 1, 10)
        val shown = generator.expandForPreview(
            rrule = "FREQ=DAILY;COUNT=3", dtstartMs = start, rangeStartMs = start - 1_000, rangeEndMs = ny(4, 1, 0),
            exdates = listOf("20270302"), timezone = "America/New_York",
        )
        assertEquals(listOf(ny(3, 1, 10), ny(3, 3, 10)), shown)
    }

    @Test
    fun `an occurrence deleted in the app stays deleted when the series start has milliseconds`() = runTest {
        val writer = EventWriter(database, generator)
        val start = ny(3, 1, 10) + 500
        val id = database.eventsDao().insert(Event(
            uid = "ms-start@example.test", calendarId = calendarId, title = "Ms start",
            startTs = start, endTs = start + 3_600_000, timezone = "America/New_York",
            rrule = "FREQ=DAILY;COUNT=3", dtstamp = 0L,
        ))
        val event = database.eventsDao().getById(id)!!
        generator.generateOccurrences(event, ny(2, 1, 0), ny(5, 1, 0))
        val second = database.occurrencesDao().getForEvent(id).sortedBy { it.startTs }[1].startTs

        writer.deleteSingleOccurrence(id, second, isLocal = true)
        generator.generateOccurrences(database.eventsDao().getById(id)!!, ny(2, 1, 0), ny(5, 1, 0))

        assertEquals(2, visible(database.eventsDao().getById(id)!!).size)
    }
}
