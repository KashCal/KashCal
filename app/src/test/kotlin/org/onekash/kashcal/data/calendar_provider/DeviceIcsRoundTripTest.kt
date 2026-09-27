package org.onekash.kashcal.data.calendar_provider

import android.app.Application
import android.provider.CalendarContract.Events
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.DAY
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.DAY0
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.DEFAULT_ALL_DAY_REMINDER
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.DEFAULT_TIMED_REMINDER
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.EVENT_COLOR
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.EVENT_ZONE
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.HOUR
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.LOCAL_CAL
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.SYNCED_CAL
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.T0
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.occ
import org.onekash.kashcal.data.ics.IcsParserService
import org.onekash.kashcal.domain.mapper.toExportEvent
import org.onekash.kashcal.domain.writer.DeviceEventDraft
import org.onekash.kashcal.util.IcsExporter
import org.robolectric.RobolectricTestRunner
import java.io.File
import org.robolectric.annotation.Config

/**
 * Round-trips a device event through a shared ICS file imported into another device calendar.
 * Export is composed the way MainActivity's device export handler does it (the export read, each
 * row mapped with toExportEvent, then the real IcsExporter writing the shared file); import is the
 * path the app uses for a picked ICS file (IcsParserService, then the device writer's import). The
 * imported copy is compared with the original through the same reads the app uses.
 *
 * Runs with the app's merged manifest and a plain Application. The share URI the exporter hands
 * out isn't covered: under Robolectric the file provider can't map the temporary cache directory,
 * so the test reads the file the exporter wrote.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DeviceIcsRoundTripTest {

    private val f = DeviceRoundTripFixture()

    @Before
    fun setUp() = f.setUp()

    @After
    fun tearDown() = f.tearDown()

    private suspend fun create(draft: DeviceEventDraft): Long = f.create(draft)

    /** Exports [eventId]'s series or event to ICS text through the real exporter. */
    private suspend fun exportIcs(eventId: Long): String {
        val export = f.reader.getEventWithExceptionsForExport(eventId)!!
        val master = export.master.toExportEvent(export.remindersById[export.master.id].orEmpty())
        val exceptions = export.exceptions.map { it.toExportEvent(export.remindersById[it.id].orEmpty()) }
        val sharedDir = File(f.context.cacheDir, "shared")
        sharedDir.deleteRecursively()
        val result = IcsExporter().exportEvent(f.context, master, exceptions)
        // The exporter writes the file, then asks the file provider for a share URI, which
        // fails here (see the class doc); the written file is what a share would send.
        result.exceptionOrNull()?.let { e ->
            assertTrue("unexpected export failure: $e", e.message.orEmpty().contains("Failed to find configured root"))
        }
        return sharedDir.listFiles()!!.single().readText(Charsets.UTF_8)
    }

    /** Imports [ics] into the local calendar the way a picked ICS file is imported. */
    private suspend fun importIcs(ics: String): Int =
        f.writer.importIcsEvents(IcsParserService.parseIcsContent(ics, calendarId = 0, subscriptionId = 0), LOCAL_CAL)

    private suspend fun occurrences(calendarId: Long, startDay: Int = 20240301, endDay: Int = 20240331) =
        f.repository.getInstancesForDayRange(startDay, endDay, setOf(calendarId)).sortedBy { it.startTs }

    // ---- one-off ----

    @Test
    fun `a one-off event comes back with its times, zone, text and reminders`() = runTest {
        val id = create(f.draft(reminders = listOf(10, 60)))

        assertEquals(1, importIcs(exportIcs(id)))

        val original = occurrences(SYNCED_CAL).single()
        val copy = occurrences(LOCAL_CAL).single()
        assertEquals(original.title, copy.title)
        assertEquals(original.description, copy.description)
        assertEquals(original.location, copy.location)
        assertEquals(original.startTs, copy.startTs)
        assertEquals(original.endTs, copy.endTs)
        assertEquals(EVENT_ZONE, copy.timezone)
        assertFalse(copy.isAllDay)
        assertEquals(listOf(10, 60), copy.reminders)
    }

    @Ignore("The device import writes neither availability nor colour, so the copy is busy and uncoloured")
    @Test
    fun `a one-off event comes back with its colour and availability`() = runTest {
        val id = create(f.draft(availability = Events.AVAILABILITY_FREE, eventColor = EVENT_COLOR))

        importIcs(exportIcs(id))

        val copy = occurrences(LOCAL_CAL).single()
        assertEquals(Events.AVAILABILITY_FREE, copy.availability)
        assertEquals(EVENT_COLOR, copy.eventColor)
    }

    @Ignore("Tags are neither exported to the ICS file nor written by the device import")
    @Test
    fun `a one-off event comes back with its tags`() = runTest {
        val id = create(f.draft(categories = listOf("Work", "Planning")))

        importIcs(exportIcs(id))

        assertEquals(listOf("Work", "Planning"), occurrences(LOCAL_CAL).single().categories)
    }

    @Ignore("An event with no stored status reads as status 0, which the export maps to TENTATIVE")
    @Test
    fun `an event the app created is not exported as tentative`() = runTest {
        val id = create(f.draft())

        val ics = exportIcs(id)

        assertFalse("STATUS in:\n$ics", ics.contains("STATUS:TENTATIVE"))
    }

    // ---- all-day ----

    @Test
    fun `a three-day all-day event comes back on the same three days`() = runTest {
        val id = create(f.draft(isAllDay = true, startTs = DAY0, endTs = DAY0 + 3 * DAY - 1, reminders = emptyList()))

        importIcs(exportIcs(id))

        val copy = occurrences(LOCAL_CAL).single()
        assertTrue(copy.isAllDay)
        assertEquals(20240305, copy.startDay)
        assertEquals(20240307, copy.endDay)
    }

    // ---- series ----


    @Test
    fun `a series comes back with the same occurrences at the same times`() = runTest {
        val id = create(f.draft(title = "Standup", rrule = "FREQ=WEEKLY;BYDAY=TU;COUNT=4", startTs = T0, endTs = T0 + HOUR, reminders = listOf(10)))

        importIcs(exportIcs(id))

        assertEquals((0..3).map(::occ), occurrences(LOCAL_CAL).map { it.startTs })
        assertTrue(occurrences(LOCAL_CAL).all { it.title == "Standup" && it.reminders == listOf(10) })
    }

    @Ignore("The export writes a series' end as its start (the row holds a duration, not an end), so the imported occurrences have no length")
    @Test
    fun `a series comes back with each occurrence's length`() = runTest {
        val id = create(f.draft(title = "Standup", rrule = "FREQ=WEEKLY;BYDAY=TU;COUNT=4", startTs = T0, endTs = T0 + HOUR, reminders = listOf(10)))

        importIcs(exportIcs(id))

        assertTrue(occurrences(LOCAL_CAL).all { it.endTs - it.startTs == HOUR })
    }

    @Ignore("The import brings a moved occurrence back as a separate event instead of an exception of the series, and drops the cancellation")
    @Test
    fun `a series with one moved and one deleted occurrence imports them as the series' exceptions`() = runTest {
        val id = create(f.draft(title = "Standup", rrule = "FREQ=WEEKLY;BYDAY=TU;COUNT=4", startTs = T0, endTs = T0 + HOUR, reminders = listOf(10)))
        f.markSynced(id, "series-$id")
        f.writer.editSingleOccurrence(id, occ(1), f.draft(title = "Standup (moved)", startTs = occ(1) + 2 * HOUR, endTs = occ(1) + 3 * HOUR, reminders = listOf(10)))
        f.writer.deleteSingleOccurrence(id, occ(2), isAllDay = false)
        assertEquals(
            listOf(occ(0) to "Standup", occ(1) + 2 * HOUR to "Standup (moved)", occ(3) to "Standup"),
            occurrences(SYNCED_CAL).map { it.startTs to it.title },
        )

        importIcs(exportIcs(id))

        // Checked on the stored rows: the target calendar is local, and without
        // a sync id the platform can't tie an exception to its series in the
        // occurrence view, so the rows are what an importer controls.
        val imported = f.provider.rows(SqliteCalendarProvider.EVENTS, "${Events.CALENDAR_ID} = ?", LOCAL_CAL.toString())
        val series = imported.single { it[Events.RRULE] != null }
        val exceptions = imported.filter { it[Events.ORIGINAL_ID] == series[Events._ID] }
            .associateBy { it[Events.ORIGINAL_INSTANCE_TIME]!!.toLong() }
        assertEquals(setOf(occ(1), occ(2)), exceptions.keys)
        assertEquals("Standup (moved)", exceptions.getValue(occ(1))[Events.TITLE])
        assertEquals(Events.STATUS_CANCELED.toString(), exceptions.getValue(occ(2))[Events.STATUS])
        assertEquals("nothing else was imported", exceptions.size + 1, imported.size)
    }

    // ---- import defaults ----

    private fun icsWithoutAlarm(allDay: Boolean) = if (allDay) {
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Test//EN\r\nBEGIN:VEVENT\r\nUID:allday-1@example.test\r\n" +
            "DTSTAMP:20240301T000000Z\r\nDTSTART;VALUE=DATE:20240305\r\nDTEND;VALUE=DATE:20240306\r\nSUMMARY:Holiday\r\n" +
            "END:VEVENT\r\nEND:VCALENDAR\r\n"
    } else {
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Test//EN\r\nBEGIN:VEVENT\r\nUID:timed-1@example.test\r\n" +
            "DTSTAMP:20240301T000000Z\r\nDTSTART:20240305T150000Z\r\nDTEND:20240305T160000Z\r\nSUMMARY:Call\r\n" +
            "END:VEVENT\r\nEND:VCALENDAR\r\n"
    }

    @Test
    fun `an imported event without alarms gets the stored default reminder`() = runTest {
        assertEquals(1, importIcs(icsWithoutAlarm(allDay = false)))
        assertEquals(1, importIcs(icsWithoutAlarm(allDay = true)))

        val byTitle = occurrences(LOCAL_CAL).associateBy { it.title }
        assertEquals(listOf(DEFAULT_TIMED_REMINDER), byTitle.getValue("Call").reminders)
        assertEquals(listOf(DEFAULT_ALL_DAY_REMINDER), byTitle.getValue("Holiday").reminders)
        assertEquals(T0, byTitle.getValue("Call").startTs)
    }
}
