package org.onekash.kashcal.sync.parser.icaldav

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.ics.IcsParserService
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.TimeZone

/**
 * A timed series whose DTSTART is a UTC time (`DTSTART:...Z`, RFC 5545 §3.3.5 FORM #2) is an
 * absolute time, so every occurrence keeps its UTC time of day across a clock change. Pull,
 * Calendar Feeds and Import from file map it through [ICalEventMapper.toEntity], which stores
 * the zone "UTC" for it; uploads write that zone back as a UTC time
 * ([EventToICalEventMapper.writeZone]).
 *
 * The phone is in America/New_York; US clocks change on 14 Mar 2027.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class UtcStartSeriesZoneTest {

    private lateinit var database: KashCalDatabase
    private lateinit var generator: OccurrenceGenerator
    private var calendarId = 0L
    private val savedZone: TimeZone = TimeZone.getDefault()
    private val utc = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java).allowMainThreadQueries().build()
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

    private fun ics(vararg vevents: List<String>): String = (
        listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Test//Test//EN") +
            vevents.flatMap { listOf("BEGIN:VEVENT", "UID:utc-start@example.test", "DTSTAMP:20261025T000000Z") + it + "END:VEVENT" } +
            listOf("END:VCALENDAR", "")
        ).joinToString("\r\n")

    private val utcSeries = listOf(
        "DTSTART:20270308T150000Z", "DTEND:20270308T160000Z", "RRULE:FREQ=WEEKLY;COUNT=4", "SUMMARY:Weekly",
    )

    private fun map(body: String, index: Int = 0): Event {
        val parsed = ICalParser().parseAllEvents(body).getOrNull()!!
        val master = parsed.firstOrNull { it.recurrenceId == null }
        val one = parsed[index]
        return ICalEventMapper.toEntity(
            one, body, calendarId, "https://caldav.example.test/cal/a.ics", "\"1\"",
            masterDtStart = if (one.recurrenceId != null) master?.dtStart else null,
        ).event
    }

    private fun fmt(ms: Long) = utc.format(Instant.ofEpochMilli(ms))

    private fun withoutStamps(ics: String) =
        ics.lines().filterNot { it.startsWith("DTSTAMP") || it.startsWith("LAST-MODIFIED") }.joinToString("\n")

    // ---- stored zone and occurrences ----

    @Test
    fun `a series written in UTC is stored in UTC and keeps 15-00Z after the clock change`() = runTest {
        val event = map(ics(utcSeries))
        assertEquals("UTC", event.timezone)

        val id = database.eventsDao().insert(event)
        generator.generateOccurrences(database.eventsDao().getById(id)!!, event.startTs - 1_000, event.startTs + 40L * 86_400_000)

        val starts = database.occurrencesDao().getForEvent(id).sortedBy { it.startTs }
        assertEquals(
            listOf("20270308T150000Z", "20270315T150000Z", "20270322T150000Z", "20270329T150000Z"),
            starts.map { fmt(it.startTs) },
        )
        assertEquals(
            listOf("20270308T160000Z", "20270315T160000Z", "20270322T160000Z", "20270329T160000Z"),
            starts.map { fmt(it.endTs) },
        )
    }

    @Test
    fun `one-offs, all-day, floating, TZID and changed occurrences keep their own zone`() {
        // A one-off in UTC, an all-day series, a floating series, a TZID series and a changed
        // occurrence of a UTC series keep their own zone.
        assertNull(map(ics(listOf("DTSTART:20270308T150000Z", "DTEND:20270308T160000Z", "SUMMARY:Once"))).timezone)
        assertNull(map(ics(listOf("DTSTART;VALUE=DATE:20270308", "DTEND;VALUE=DATE:20270309", "RRULE:FREQ=WEEKLY;COUNT=2"))).timezone)
        assertEquals(
            "America/New_York",
            map(ics(listOf("DTSTART:20270308T150000", "DTEND:20270308T160000", "RRULE:FREQ=WEEKLY;COUNT=2"))).timezone,
        )
        assertEquals(
            "Europe/Berlin",
            map(ics(listOf("DTSTART;TZID=Europe/Berlin:20270308T150000", "DTEND;TZID=Europe/Berlin:20270308T160000", "RRULE:FREQ=WEEKLY;COUNT=2"))).timezone,
        )
        val changed = ics(utcSeries, listOf("RECURRENCE-ID:20270315T150000Z", "DTSTART:20270315T170000Z", "DTEND:20270315T180000Z", "SUMMARY:Moved"))
        assertNull(map(changed, index = 1).timezone)
    }

    @Test
    fun `calendar feeds and file imports store the UTC zone`() {
        val body = ics(utcSeries)
        assertEquals("UTC", IcsParserService.parseIcsContent(body, calendarId, subscriptionId = 7L).single().timezone)
        assertEquals("UTC", IcsParserService.parseForImport(body)!!.groups.single().masters.single().event.timezone)
    }

    @Test
    fun `a server-change merge over a row stored with no zone takes the UTC zone and changes nothing else`() {
        val body = ics(utcSeries)
        val storedBeforeFix = map(body).copy(id = 5L, timezone = null)
        val renamed = storedBeforeFix.copy(title = "Weekly renamed")

        val merged = org.onekash.kashcal.sync.strategy.ServerChangeMerge.merge(
            baseline = body, series = renamed, seriesAttendees = emptyList(), occurrences = emptyList(),
            server = body, account = null,
        )!!

        assertEquals("UTC", merged.series.timezone)
        assertEquals("Weekly renamed", merged.series.title)
        val starts = merged.body.lines().filter { it.startsWith("DTSTART") }
        assertEquals(listOf("DTSTART:20270308T150000Z"), starts)
    }

    // ---- upload ----

    @Test
    fun `an upload of a UTC series writes the same text as the row with no zone`() {
        val body = ics(utcSeries, listOf("RECURRENCE-ID:20270315T150000Z", "DTSTART:20270315T170000Z", "DTEND:20270315T180000Z", "SUMMARY:Moved"))
        val series = map(body).copy(id = 5L, exdate = "${map(body).startTs + 14L * 86_400_000}")
        val before = series.copy(timezone = null)
        val exception = map(body, index = 1).copy(id = 6L, originalEventId = 5L, timezone = "UTC")
        val exceptionBefore = exception.copy(timezone = null)

        // Patch path (stored copy), fresh path (no copy), and the series with a changed occurrence.
        assertEquals(withoutStamps(IcsPatcher.serialize(before)), withoutStamps(IcsPatcher.serialize(series)))
        assertEquals(
            withoutStamps(IcsPatcher.serialize(before.copy(rawIcal = null))),
            withoutStamps(IcsPatcher.serialize(series.copy(rawIcal = null))),
        )
        val withExceptions = IcsPatcher.serializeWithExceptions(series, listOf(exception))
        assertEquals(
            withoutStamps(IcsPatcher.serializeWithExceptions(before, listOf(exceptionBefore))),
            withoutStamps(withExceptions),
        )
        assertEquals(EventToICalEventMapper.dtStartOf(before), EventToICalEventMapper.dtStartOf(series))

        val lines = withExceptions.lines()
        assertTrue(lines.toString(), "DTSTART:20270308T150000Z" in lines)
        assertTrue(lines.toString(), "RECURRENCE-ID:20270315T150000Z" in lines)
        assertTrue(lines.toString(), "EXDATE:20270322T150000Z" in lines)
        assertFalse(withExceptions, withExceptions.contains("TZID=UTC"))
        assertFalse(withExceptions, withExceptions.contains("BEGIN:VTIMEZONE"))
    }

    @Test
    fun `a one-off holding the UTC zone uploads in UTC form`() {
        // A Quick Add "utc" event, or one created on a phone whose zone id is UTC.
        val oneOff = Event(
            uid = "once@example.test", calendarId = calendarId, title = "Once",
            startTs = map(ics(utcSeries)).startTs, endTs = map(ics(utcSeries)).endTs,
            timezone = "UTC", dtstamp = 0L,
        )
        val lines = IcsPatcher.serialize(oneOff).lines()
        assertTrue(lines.toString(), "DTSTART:20270308T150000Z" in lines)
        assertFalse(lines.toString(), lines.any { it.contains("TZID=UTC") })
    }

    @Test
    fun `rows made from a UTC series write UTC form`() {
        val series = map(ics(utcSeries)).copy(id = 5L)
        val third = series.startTs + 14L * 86_400_000

        // The share card's single occurrence drops the rule and keeps the zone.
        val shared = org.onekash.kashcal.domain.share.singleOccurrenceForShare(series, third, third + 3_600_000)
        assertTrue("DTSTART:20270322T150000Z" in IcsPatcher.serialize(shared).lines())

        // A series turned into a one-off in the form keeps its zone.
        val oneOff = series.copy(rrule = null)
        val lines = IcsPatcher.serialize(oneOff).lines()
        assertTrue(lines.toString(), "DTSTART:20270308T150000Z" in lines)
        assertFalse(lines.toString(), lines.any { it.contains("TZID=UTC") })
    }

    @Test
    fun `a series row holding the UTC zone uploads in UTC form and pulls back the same`() {
        // Such a row also comes from a TZID=UTC series, a Quick Add "utc" series, or a series
        // created on a phone whose zone id is UTC; its stored copy here says TZID=UTC.
        val start = map(ics(utcSeries)).startTs
        val stored = ics(listOf(
            "DTSTART;TZID=UTC:20270308T150000", "DTEND;TZID=UTC:20270308T160000", "RRULE:FREQ=WEEKLY;COUNT=4", "SUMMARY:Weekly",
        ))
        val row = Event(
            id = 5L, uid = "utc-start@example.test", calendarId = calendarId, title = "Weekly",
            startTs = start, endTs = start + 3_600_000, timezone = "UTC", rrule = "FREQ=WEEKLY;COUNT=4",
            rawIcal = stored, dtstamp = 0L,
        )

        val uploaded = IcsPatcher.serialize(row)
        assertTrue(uploaded, uploaded.lines().contains("DTSTART:20270308T150000Z"))
        assertFalse(uploaded, uploaded.contains("TZID=UTC"))

        val again = map(uploaded)
        assertEquals("UTC", again.timezone)
        assertEquals(row.startTs, again.startTs)
        assertEquals(row.endTs, again.endTs)
    }

    @Test
    fun `a device calendar series export writes UTC form`() {
        // The export of a device event builds an unsaved row from the device fields
        // (DeviceEventMapper.toExportEvent), with each zone as the device calendar holds it.
        val device = Event(
            uid = "device-9@kashcal", calendarId = 0, title = "Device weekly",
            startTs = map(ics(utcSeries)).startTs, endTs = map(ics(utcSeries)).endTs,
            timezone = "UTC", rrule = "FREQ=WEEKLY;COUNT=4", dtstamp = 0L,
        )
        val exported = org.onekash.kashcal.util.IcsExporter().serializeDeviceEvent(device, emptyList())
        assertNotNull(exported.lines().firstOrNull { it == "DTSTART:20270308T150000Z" })
    }
}
