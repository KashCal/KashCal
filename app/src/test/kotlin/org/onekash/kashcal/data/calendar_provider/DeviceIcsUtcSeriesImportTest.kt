package org.onekash.kashcal.data.calendar_provider

import android.app.Application
import android.provider.CalendarContract.Events
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.HOUR
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.LOCAL_CAL
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.OWNER
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.SYNCED_CAL
import org.onekash.kashcal.data.ics.IcsParserService
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.coordinator.OfflineCalendarFixture
import org.onekash.kashcal.domain.mapper.toExportEvent
import org.onekash.kashcal.util.IcsExporter
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Imports .ics files into a device calendar the way a picked file is imported
 * ([IcsParserService.parseForImport], then the device writer's import over
 * [SqliteCalendarProvider]) and into an offline KashCal calendar (the real
 * [EventCoordinator.importIcsFile] over Room).
 *
 * A timed series whose DTSTART is a UTC time keeps that UTC time on every occurrence (RFC 5545
 * §3.3.5 FORM #2), in both calendars, and a device series in UTC keeps it through an export and
 * an import of the exported file. The phone is in Europe/Berlin, whose clocks change on 25 Oct
 * 2026, so an expansion in the phone's zone shows as 15:00Z from 28 Oct.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DeviceIcsUtcSeriesImportTest {

    private val f = DeviceRoundTripFixture("Europe/Berlin")
    private lateinit var room: OfflineCalendarFixture

    @Before
    fun setUp() {
        f.setUp()
        room = OfflineCalendarFixture(f.context)
    }

    @After
    fun tearDown() {
        room.close()
        f.tearDown()
    }

    private fun ics(vararg lines: String, method: String? = null, uid: String = "utc-series@example.test") = (
        listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Test//Test//EN") + listOfNotNull(method?.let { "METHOD:$it" }) +
            listOf("BEGIN:VEVENT", "UID:$uid", "DTSTAMP:20261020T000000Z", "SUMMARY:Weekly") + lines +
            listOf("END:VEVENT", "END:VCALENDAR", "")
        ).joinToString("\r\n")

    private val utcSeries = arrayOf("DTSTART:20261021T140000Z", "DTEND:20261021T150000Z", "RRULE:FREQ=WEEKLY;COUNT=5")

    private val fileStarts = listOf(
        "2026-10-21T14:00:00Z", "2026-10-28T14:00:00Z", "2026-11-04T14:00:00Z", "2026-11-11T14:00:00Z", "2026-11-18T14:00:00Z",
    )

    private suspend fun importToDevice(text: String, calendarId: Long = LOCAL_CAL) {
        assertEquals(1, f.writer.importIcsFile(requireNotNull(IcsParserService.parseForImport(text)), calendarId).created)
    }

    private fun deviceZones(calendarId: Long = LOCAL_CAL): List<String?> =
        f.provider.rows(SqliteCalendarProvider.EVENTS, "${Events.CALENDAR_ID} = ?", calendarId.toString())
            .map { it[Events.EVENT_TIMEZONE] }

    private suspend fun deviceStarts(calendarId: Long = LOCAL_CAL): List<String> =
        f.repository.getInstancesForDayRange(20261015, 20261130, setOf(calendarId))
            .map { it.startTs }.sorted().map { Instant.ofEpochMilli(it).toString() }

    private suspend fun roomStarts(text: String): List<String> {
        val calendarId = room.initializer.ensureLocalCalendarExists()
        assertEquals(1, room.coordinator.importIcsFile(requireNotNull(IcsParserService.parseForImport(text)), calendarId).created)
        return room.db.query("SELECT start_ts FROM occurrences ORDER BY start_ts", null).use { c ->
            buildList { while (c.moveToNext()) add(Instant.ofEpochMilli(c.getLong(0)).toString()) }
        }
    }

    @Test
    fun `a file series in UTC imports into a device calendar in UTC and keeps its UTC time`() = runTest {
        importToDevice(ics(*utcSeries))

        assertEquals(listOf("UTC"), deviceZones())
        assertEquals(fileStarts, deviceStarts())
    }

    @Test
    fun `an invitation series in UTC imports into a device calendar in UTC and keeps its UTC time`() = runTest {
        importToDevice(
            ics(
                *utcSeries,
                "ORGANIZER:mailto:organizer@example.test",
                "ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:$OWNER",
                method = "REQUEST",
            ),
            calendarId = SYNCED_CAL,
        )

        // The organizer row shows the invitation path wrote it, not the plain one.
        val row = f.provider.rows(SqliteCalendarProvider.EVENTS, "${Events.CALENDAR_ID} = ?", SYNCED_CAL.toString()).single()
        assertEquals("organizer@example.test", row[Events.ORGANIZER])
        assertEquals("UTC", row[Events.EVENT_TIMEZONE])
        assertEquals(fileStarts, deviceStarts(SYNCED_CAL))
    }

    @Test
    fun `a device calendar and a KashCal calendar show a UTC series at the same times`() = runTest {
        val text = ics(*utcSeries)
        importToDevice(text)

        assertEquals(roomStarts(text), deviceStarts())
    }

    @Test
    fun `other start forms keep their zone on a device calendar`() = runTest {
        importToDevice(ics("DTSTART:20261021T140000Z", "DTEND:20261021T150000Z", uid = "one-off@example.test"))
        importToDevice(
            ics(
                "DTSTART;TZID=America/New_York:20261021T100000", "DTEND;TZID=America/New_York:20261021T110000",
                "RRULE:FREQ=WEEKLY;COUNT=2", uid = "new-york@example.test",
            ),
        )
        importToDevice(ics("DTSTART:20261021T100000", "DTEND:20261021T110000", "RRULE:FREQ=WEEKLY;COUNT=2", uid = "floating@example.test"))
        importToDevice(
            ics("DTSTART;VALUE=DATE:20261021", "DTEND;VALUE=DATE:20261022", "RRULE:FREQ=WEEKLY;COUNT=2", uid = "all-day@example.test"),
        )

        val byUid = f.provider.rows(SqliteCalendarProvider.EVENTS, "${Events.CALENDAR_ID} = ?", LOCAL_CAL.toString())
            .associate { it[Events.UID_2445] to it[Events.EVENT_TIMEZONE] }
        assertEquals(
            mapOf(
                "one-off@example.test" to "Europe/Berlin",
                "new-york@example.test" to "America/New_York",
                "floating@example.test" to "Europe/Berlin",
                "all-day@example.test" to "UTC",
            ),
            byUid,
        )
    }

    @Test
    fun `a changed occurrence of a UTC series imports as its own event at the file's time`() = runTest {
        // A changed occurrence arriving without its series imports as a standalone event, which
        // is a one-off: the instant is the file's, the zone is the phone's.
        importToDevice(ics("RECURRENCE-ID:20261028T140000Z", "DTSTART:20261028T170000Z", "DTEND:20261028T180000Z"))

        assertEquals(listOf("Europe/Berlin"), deviceZones())
        assertEquals(listOf("2026-10-28T17:00:00Z"), deviceStarts())
    }

    /** Returns [eventId]'s export text, as [IcsExporter.exportDeviceEvent] writes it to a file. */
    private suspend fun exportIcs(eventId: Long): String {
        val export = f.reader.getEventWithExceptionsForExport(eventId)!!
        return IcsExporter().serializeDeviceEvent(
            export.master.toExportEvent(export.remindersById[export.master.id].orEmpty()),
            export.exceptions.map { it.toExportEvent(export.remindersById[it.id].orEmpty()) },
        )
    }

    private suspend fun deviceUtcSeries(): Long {
        val start = Instant.parse(fileStarts.first()).toEpochMilli()
        return f.create(f.draft(rrule = "FREQ=WEEKLY;COUNT=5", timezone = "UTC", startTs = start, endTs = start + HOUR))
    }

    @Test
    fun `a device series in UTC exports its times in UTC form`() = runTest {
        val starts = exportIcs(deviceUtcSeries()).replace(Regex("\r?\n[ \t]"), "").lines().filter { it.startsWith("DTSTART") }

        assertEquals(listOf("DTSTART:20261021T140000Z"), starts)
    }

    @Test
    fun `a device series in UTC keeps its times when its export is imported into a device calendar`() = runTest {
        val text = exportIcs(deviceUtcSeries())
        assertEquals(fileStarts, deviceStarts(SYNCED_CAL))

        importToDevice(text)

        assertEquals(fileStarts, deviceStarts())
    }

    @Test
    fun `a device series in UTC keeps its times when its export is imported into a KashCal calendar`() = runTest {
        val text = exportIcs(deviceUtcSeries())

        assertEquals(deviceStarts(SYNCED_CAL), roomStarts(text))
    }

    @Test
    fun `a device series in another zone exports with its TZID and VTIMEZONE`() = runTest {
        val start = Instant.parse(fileStarts.first()).toEpochMilli()
        val id = f.create(
            f.draft(rrule = "FREQ=WEEKLY;COUNT=5", timezone = "America/New_York", startTs = start, endTs = start + HOUR),
        )

        val lines = exportIcs(id).replace(Regex("\r?\n[ \t]"), "").lines()
        val vevent = lines.dropWhile { it != "BEGIN:VEVENT" }.takeWhile { it != "END:VEVENT" }

        assertEquals(listOf("DTSTART;TZID=America/New_York:20261021T100000"), vevent.filter { it.startsWith("DTSTART") })
        assertTrue(lines.toString(), "BEGIN:VTIMEZONE" in lines && "TZID:America/New_York" in lines)
    }
}
