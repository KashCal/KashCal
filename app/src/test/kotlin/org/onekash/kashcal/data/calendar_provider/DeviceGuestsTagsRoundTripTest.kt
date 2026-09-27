package org.onekash.kashcal.data.calendar_provider

import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.ExtendedProperties
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.DAY
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.DAY_CODE
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.EVENT_COLOR
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.EVENT_ZONE
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.HOUR
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.LOCAL_CAL
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.MINUTE
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.OWNER
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.SYNCED_CAL
import org.onekash.kashcal.data.calendar_provider.DeviceRoundTripFixture.Companion.T0
import org.onekash.kashcal.domain.writer.DeviceEventDraft
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Round-trips guests, the user's own response, tags and moves to another calendar: written
 * through the app's device writer, read back through the reads the app uses (guest list, grid,
 * declined filtering, reminders) and the stored provider rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class DeviceGuestsTagsRoundTripTest {

    private val f = DeviceRoundTripFixture()

    @Before
    fun setUp() = f.setUp()

    @After
    fun tearDown() = f.tearDown()

    private fun guest(email: String, name: String? = null) =
        DeviceAttendee(id = 0L, name = name, email = email, relationship = Attendees.RELATIONSHIP_ATTENDEE, status = Attendees.ATTENDEE_STATUS_NONE)

    private val ana = guest("ana@example.test", "Ana Lima")
    private val bo = guest("bo@example.test")

    private suspend fun create(draft: DeviceEventDraft): Long = f.writer.createEvent(draft).getOrThrow().eventId

    private fun attendeeRows(eventId: Long) =
        f.provider.rows(SqliteCalendarProvider.ATTENDEES, "${Attendees.EVENT_ID} = ?", eventId.toString())
            .associateBy { it[Attendees.ATTENDEE_EMAIL] }

    private fun tagRows(eventId: Long) =
        f.provider.rows(SqliteCalendarProvider.EXTENDED_PROPERTIES, "${ExtendedProperties.EVENT_ID} = ?", eventId.toString())

    // ---- guests ----

    @Test
    fun `creating with guests stores an organizer row for the owner and a row per guest`() = runTest {
        val id = create(f.draft(attendees = listOf(ana, bo)))

        val rows = attendeeRows(id)
        assertEquals(setOf(OWNER, "ana@example.test", "bo@example.test"), rows.keys)
        assertEquals(Attendees.RELATIONSHIP_ORGANIZER.toString(), rows.getValue(OWNER)[Attendees.ATTENDEE_RELATIONSHIP])
        assertEquals(Attendees.ATTENDEE_STATUS_ACCEPTED.toString(), rows.getValue(OWNER)[Attendees.ATTENDEE_STATUS])
        assertEquals("Ana Lima", rows.getValue("ana@example.test")[Attendees.ATTENDEE_NAME])
        assertEquals(Attendees.ATTENDEE_STATUS_NONE.toString(), rows.getValue("bo@example.test")[Attendees.ATTENDEE_STATUS])
        assertEquals("1", f.eventRow(id)!![Events.HAS_ATTENDEE_DATA])
        assertEquals(Attendees.ATTENDEE_STATUS_ACCEPTED, f.occurrences(DAY_CODE).single().selfAttendeeStatus)
    }

    @Test
    fun `the guest list reads back with the owner address`() = runTest {
        val id = create(f.draft(attendees = listOf(ana, bo)))

        val read = f.reader.getAttendeesWithOwner(id, SYNCED_CAL)

        assertEquals(OWNER, read.ownerEmail)
        assertEquals(setOf(OWNER, "ana@example.test", "bo@example.test"), read.attendees.mapNotNull { it.email }.toSet())
    }

    @Test
    fun `adding and removing guests keeps the responses of guests the edit didn't touch`() = runTest {
        val id = create(f.draft(attendees = listOf(ana, bo)))
        // Ana accepted on the server; the sync adapter wrote her response back.
        val anaRowId = attendeeRows(id).getValue("ana@example.test")[Attendees._ID]!!.toLong()
        f.provider.updateRow(SqliteCalendarProvider.ATTENDEES, anaRowId, Attendees.ATTENDEE_STATUS to Attendees.ATTENDEE_STATUS_ACCEPTED)

        assertTrue(f.writer.updateEvent(id, f.draft(attendees = listOf(ana, guest("cy@example.test")))).isSuccess)

        val rows = attendeeRows(id)
        assertEquals(setOf(OWNER, "ana@example.test", "cy@example.test"), rows.keys)
        assertEquals(anaRowId.toString(), rows.getValue("ana@example.test")[Attendees._ID])
        assertEquals(Attendees.ATTENDEE_STATUS_ACCEPTED.toString(), rows.getValue("ana@example.test")[Attendees.ATTENDEE_STATUS])
    }

    @Test
    fun `removing every guest leaves no guest rows`() = runTest {
        val id = create(f.draft(attendees = listOf(ana)))

        f.writer.updateEvent(id, f.draft(attendees = emptyList()))

        assertTrue(attendeeRows(id).keys.none { it == "ana@example.test" })
    }

    @Test
    fun `an edit that doesn't manage guests leaves every guest row alone`() = runTest {
        val id = create(f.draft(attendees = listOf(ana, bo)))
        val before = attendeeRows(id)

        f.writer.updateEvent(id, f.draft(title = "Renamed", attendees = null))

        assertEquals(before, attendeeRows(id))
    }

    // ---- the user's own response ----

    private suspend fun invitedEvent(ownerEmailOnRow: String = OWNER): Long {
        // An invitation someone else organizes, as a sync adapter writes it.
        val id = create(f.draft(title = "Offsite"))
        f.provider.insertRow(
            SqliteCalendarProvider.ATTENDEES, Attendees.EVENT_ID to id, Attendees.ATTENDEE_EMAIL to "boss@example.test",
            Attendees.ATTENDEE_RELATIONSHIP to Attendees.RELATIONSHIP_ORGANIZER, Attendees.ATTENDEE_STATUS to Attendees.ATTENDEE_STATUS_ACCEPTED,
        )
        f.provider.insertRow(
            SqliteCalendarProvider.ATTENDEES, Attendees.EVENT_ID to id, Attendees.ATTENDEE_EMAIL to ownerEmailOnRow,
            Attendees.ATTENDEE_RELATIONSHIP to Attendees.RELATIONSHIP_ATTENDEE, Attendees.ATTENDEE_STATUS to Attendees.ATTENDEE_STATUS_INVITED,
        )
        return id
    }

    @Test
    fun `replying changes only the user's own row and the event's response`() = runTest {
        val id = invitedEvent()
        val before = attendeeRows(id)

        assertEquals(true, f.writer.replyRsvp(id, SYNCED_CAL, Attendees.ATTENDEE_STATUS_TENTATIVE).getOrThrow())

        val after = attendeeRows(id)
        assertEquals(before.getValue("boss@example.test"), after.getValue("boss@example.test"))
        assertEquals(Attendees.ATTENDEE_STATUS_TENTATIVE.toString(), after.getValue(OWNER)[Attendees.ATTENDEE_STATUS])
        assertEquals(Attendees.ATTENDEE_STATUS_TENTATIVE, f.occurrences(DAY_CODE).single().selfAttendeeStatus)
    }

    @Test
    fun `declining hides the event from declined-hiding reads and its reminders`() = runTest {
        val id = invitedEvent()

        f.writer.replyRsvp(id, SYNCED_CAL, Attendees.ATTENDEE_STATUS_DECLINED)

        assertTrue(f.occurrences(DAY_CODE, hideDeclined = true).isEmpty())
        assertEquals(1, f.occurrences(DAY_CODE).size)
        assertNull(f.repository.getNextUpcomingReminder(setOf(SYNCED_CAL), afterMs = T0 - 2 * HOUR))
    }

    @Ignore("A reply matches the user's row ignoring case, but the platform only updates the event's own response for an exact match, so the decline is stored yet not honoured")
    @Test
    fun `declining when the synced row spells the address in other case still hides the event`() = runTest {
        val id = invitedEvent(ownerEmailOnRow = "Me@Example.test")

        f.writer.replyRsvp(id, SYNCED_CAL, Attendees.ATTENDEE_STATUS_DECLINED)

        // The user declined, so a declined-hiding view must not show it.
        assertTrue(f.occurrences(DAY_CODE, hideDeclined = true).isEmpty())
    }

    // ---- tags ----

    @Test
    fun `tags are stored as one categories row and read back everywhere`() = runTest {
        val id = create(f.draft(categories = listOf("Work", "Planning")))

        assertEquals(listOf("Work\\Planning"), tagRows(id).map { it[ExtendedProperties.VALUE] })
        assertEquals(listOf("Work", "Planning"), f.repository.getDeviceEvent(id)!!.categories)
        assertEquals(listOf("Work", "Planning"), f.occurrences(DAY_CODE).single().categories)
    }

    @Test
    fun `replacing and clearing tags round-trip`() = runTest {
        val id = create(f.draft(categories = listOf("Work")))

        f.writer.updateEvent(id, f.draft(categories = listOf("Home")))
        assertEquals(listOf("Home"), f.occurrences(DAY_CODE).single().categories)

        f.writer.updateEvent(id, f.draft(categories = emptyList()))
        assertTrue(tagRows(id).isEmpty())
        assertTrue(f.occurrences(DAY_CODE).single().categories.isEmpty())
    }

    @Test
    fun `an edit that doesn't manage tags leaves them alone`() = runTest {
        val id = create(f.draft(categories = listOf("Work")))

        f.writer.updateEvent(id, f.draft(title = "Renamed", categories = null))

        assertEquals(listOf("Work"), f.occurrences(DAY_CODE).single().categories)
    }

    @Test
    fun `tags written by another app read back as they were stored`() = runTest {
        val id = create(f.draft())
        f.provider.insertRow(
            SqliteCalendarProvider.EXTENDED_PROPERTIES, ExtendedProperties.EVENT_ID to id,
            ExtendedProperties.NAME to "categories", ExtendedProperties.VALUE to "work\\ URGENT \\Client A",
        )

        assertEquals(listOf("work", "URGENT", "Client A"), f.occurrences(DAY_CODE).single().categories)
    }

    // ---- move ----

    @Test
    fun `moving to another calendar recreates the event there with every field and removes the source`() = runTest {
        val sourceId = create(f.draft(attendees = listOf(ana), categories = listOf("Work")))
        val source = f.repository.getDeviceEvent(sourceId)!!

        val movedId = f.writer.moveEventToCalendar(source, f.draft(calendarId = LOCAL_CAL)).getOrThrow().eventId

        assertNull("an unsynced source is removed", f.eventRow(sourceId))
        val occurrence = f.occurrences(DAY_CODE).single()
        assertEquals(movedId, occurrence.eventId)
        assertEquals(LOCAL_CAL, occurrence.calendarId)
        assertEquals("Design review", occurrence.title)
        assertEquals("Agenda: roadmap", occurrence.description)
        assertEquals("Room 4", occurrence.location)
        assertEquals(T0, occurrence.startTs)
        assertEquals(T0 + 90 * MINUTE, occurrence.endTs)
        assertEquals(EVENT_ZONE, occurrence.timezone)
        assertEquals(EVENT_COLOR, occurrence.eventColor)
        assertEquals(Events.AVAILABILITY_FREE, occurrence.availability)
        assertEquals(listOf(10, 60), occurrence.reminders)
        assertEquals(listOf("Work"), occurrence.categories)
        val row = f.eventRow(movedId)!!
        assertEquals(LOCAL_CAL.toString(), row[Events.CALENDAR_ID])
        assertEquals(T0.toString(), row[Events.DTSTART])
        assertEquals((T0 + 90 * MINUTE).toString(), row[Events.DTEND])
        assertNull(row[Events.DURATION])
        assertEquals(EVENT_ZONE, row[Events.EVENT_TIMEZONE])
        assertEquals(Events.AVAILABILITY_FREE.toString(), row[Events.AVAILABILITY])
        assertEquals(EVENT_COLOR.toString(), row[Events.EVENT_COLOR])
        assertTrue("the guest comes along", "ana@example.test" in attendeeRows(movedId).keys)
        assertTrue("the source's organizer row doesn't become a guest", OWNER !in attendeeRows(movedId).keys)
    }

    @Test
    fun `moving a synced event leaves the source as a deleted row nothing shows`() = runTest {
        val sourceId = create(f.draft())
        f.markSynced(sourceId)

        f.writer.moveEventToCalendar(f.repository.getDeviceEvent(sourceId)!!, f.draft(calendarId = LOCAL_CAL))

        assertEquals("1", f.eventRow(sourceId)!![Events.DELETED])
        assertEquals(listOf(LOCAL_CAL), f.occurrences(DAY_CODE).map { it.calendarId })
    }

    @Test
    fun `a moved event keeps its reminders and shows up for its next reminder`() = runTest {
        val sourceId = create(f.draft())

        f.writer.moveEventToCalendar(f.repository.getDeviceEvent(sourceId)!!, f.draft(calendarId = LOCAL_CAL))

        val next = f.repository.getNextUpcomingReminder(setOf(LOCAL_CAL), afterMs = T0 - DAY)
        assertEquals(T0 - 60 * MINUTE, next?.triggerTime)
    }
}
