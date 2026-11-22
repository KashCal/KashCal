package org.onekash.kashcal.data.calendar_provider

import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.ics.IcsParserService
import org.onekash.kashcal.domain.ics.IcsImportResult
import org.onekash.kashcal.domain.ics.IgnoreReason
import org.onekash.kashcal.ui.components.attendees.shouldShowRespondSection
import org.onekash.kashcal.ui.viewmodels.deviceAttendeeUiState
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Imports invitation files into a device calendar the way the app does:
 * [IcsParserService.parseForImport] then
 * [org.onekash.kashcal.domain.writer.DeviceEventWriter.importIcsFile] over the real repository on
 * [SqliteCalendarProvider].
 *
 * Fixtures are the ones [org.onekash.kashcal.data.ics.IcsInvitationImportTest] uses: the #364
 * file byte for byte, and Nextcloud's REQUEST, REPLY and CANCEL for one meeting. The calendar
 * that receives the #364 file is owned by attendee@example.com, as the report asks.
 *
 * A device row's iCalendar UID is `Events.UID_2445`; the synced copy is seeded the way a sync
 * adapter writes it (UID_2445, _SYNC_ID, ORGANIZER and attendee rows), so a file for that UID
 * updates, cancels or leaves the row instead of adding one beside it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DeviceIcsInvitationImportTest {

    private val f = DeviceRoundTripFixture()

    private val issueCal = 13L
    private val issueOwner = "attendee@example.com"
    private val nextcloudCal = 14L
    private val nextcloudOwner = "testuser1@example.test"
    private val issueUid = "test-invitation-123@example.com"
    private val nextcloudUid = "sweep-appwide-itip-dddd700a-3af2-4dd5-9edc-a7474a6461dd"

    @Before
    fun setUp() {
        f.setUp()
        f.provider.seedCalendar(issueCal, accountName = issueOwner, displayName = "Mail invites")
        f.provider.seedCalendar(nextcloudCal, accountName = nextcloudOwner, displayName = "Nextcloud")
    }

    @After
    fun tearDown() = f.tearDown()

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("ics/itip/$name")) { "missing $name" }
            .readText()

    private suspend fun importFile(name: String, calendarId: Long): IcsImportResult =
        f.writer.importIcsFile(requireNotNull(IcsParserService.parseForImport(fixture(name))), calendarId)

    private fun events(calendarId: Long) =
        f.provider.rows(SqliteCalendarProvider.EVENTS, "${Events.CALENDAR_ID} = ?", calendarId.toString())

    private fun attendees(eventId: String) =
        f.provider.rows(SqliteCalendarProvider.ATTENDEES, "${Attendees.EVENT_ID} = ?", eventId)

    /** The Nextcloud meeting as the attendee's sync adapter stores it, daily when [series]. */
    private fun seedSyncedCopy(calendarId: Long = nextcloudCal, series: Boolean = false): Long {
        val id = f.provider.insertRow(
            SqliteCalendarProvider.EVENTS,
            Events.CALENDAR_ID to calendarId,
            Events.TITLE to "Sweep iTIP probe",
            Events.DTSTART to meetingStart,
            Events.DTEND to if (series) null else meetingStart + HOUR,
            Events.DURATION to if (series) "PT1H" else null,
            Events.RRULE to if (series) "FREQ=DAILY;COUNT=3" else null,
            Events.EVENT_TIMEZONE to "UTC",
            Events._SYNC_ID to "$nextcloudUid.ics",
            Events.UID_2445 to nextcloudUid,
            Events.ORGANIZER to "admin@example.test",
            Events.HAS_ATTENDEE_DATA to 1,
        )
        f.provider.insertRow(
            SqliteCalendarProvider.ATTENDEES,
            Attendees.EVENT_ID to id,
            Attendees.ATTENDEE_EMAIL to "admin@example.test",
            Attendees.ATTENDEE_RELATIONSHIP to Attendees.RELATIONSHIP_ORGANIZER,
            Attendees.ATTENDEE_STATUS to Attendees.ATTENDEE_STATUS_ACCEPTED,
        )
        f.provider.insertRow(
            SqliteCalendarProvider.ATTENDEES,
            Attendees.EVENT_ID to id,
            Attendees.ATTENDEE_EMAIL to nextcloudOwner,
            Attendees.ATTENDEE_RELATIONSHIP to Attendees.RELATIONSHIP_ATTENDEE,
            Attendees.ATTENDEE_STATUS to Attendees.ATTENDEE_STATUS_INVITED,
        )
        f.provider.insertRow(
            SqliteCalendarProvider.REMINDERS,
            Reminders.EVENT_ID to id,
            Reminders.MINUTES to 10,
            Reminders.METHOD to Reminders.METHOD_ALERT,
        )
        return id
    }

    private val meetingStart = 1_792_317_600_000L
    private val HOUR = 3_600_000L
    private val DAY = 86_400_000L

    private suspend fun importText(ics: String, calendarId: Long): IcsImportResult =
        f.writer.importIcsFile(requireNotNull(IcsParserService.parseForImport(ics)), calendarId)

    private suspend fun nextReminder() =
        f.repository.getNextUpcomingReminder(setOf(nextcloudCal), afterMs = meetingStart - DAY)

    @Test
    fun `issue 364 invitation keeps the file's organizer`() = runTest {
        importFile("issue_364_request.ics", issueCal)

        assertEquals("organizer@example.com", events(issueCal).single()[Events.ORGANIZER])
    }

    @Test
    fun `issue 364 invitation keeps the file's UID`() = runTest {
        importFile("issue_364_request.ics", issueCal)

        assertEquals(issueUid, events(issueCal).single()[Events.UID_2445])
    }

    @Test
    fun `issue 364 invitation keeps the user as an invited attendee`() = runTest {
        importFile("issue_364_request.ics", issueCal)

        val eventId = events(issueCal).single()[Events._ID]!!
        val rows = attendees(eventId)
        val self = rows.firstOrNull { it[Attendees.ATTENDEE_EMAIL].equals(issueOwner, ignoreCase = true) }
        assertNotNull("the user's attendee row is dropped on import; rows=$rows", self)
        assertEquals(Attendees.ATTENDEE_STATUS_INVITED.toString(), self!![Attendees.ATTENDEE_STATUS])
    }

    @Test
    fun `issue 364 invitation shows the Respond section`() = runTest {
        importFile("issue_364_request.ics", issueCal)

        val eventId = events(issueCal).single()[Events._ID]!!.toLong()
        val rows = f.reader.getAttendeesWithOwner(eventId, issueCal)
        val ui = deviceAttendeeUiState(rows.attendees, rows.ownerEmail)
        val you = ui.models.firstOrNull { it.isYou }
        assertTrue(
            "no Respond section: the user is not on the imported event's attendee list",
            shouldShowRespondSection(you?.status, isOrganizer = you?.isOrganizer ?: false)
        )
    }

    @Test
    fun `importing the same invitation twice leaves one row`() = runTest {
        importFile("issue_364_request.ics", issueCal)
        importFile("issue_364_request.ics", issueCal)

        assertEquals(1, events(issueCal).size)
    }

    @Test
    fun `a REQUEST for a meeting already synced adds no row`() = runTest {
        seedSyncedCopy()

        val result = importFile("nextcloud_request.ics", nextcloudCal)

        assertEquals("the device calendar keeps its copy", listOf(IgnoreReason.CALENDAR_KEEPS_COPY), result.ignored)

        val rows = events(nextcloudCal)
        assertEquals(
            "rows=${rows.map { it[Events.UID_2445] to it[Events._SYNC_ID] }}",
            1, rows.size
        )
    }

    @Test
    fun `a REPLY file adds no row`() = runTest {
        seedSyncedCopy()

        importFile("nextcloud_reply.ics", nextcloudCal)

        assertEquals(1, events(nextcloudCal).size)
    }

    @Test
    fun `a CANCEL file for an unknown meeting creates nothing`() = runTest {
        importFile("nextcloud_cancel.ics", nextcloudCal)

        assertEquals(
            "a cancellation became rows: ${events(nextcloudCal).map { it[Events.TITLE] to it[Events.STATUS] }}",
            0, events(nextcloudCal).size
        )
    }

    @Test
    fun `a CANCEL file cancels the matching meeting instead of adding one`() = runTest {
        seedSyncedCopy()

        importFile("nextcloud_cancel.ics", nextcloudCal)

        val rows = events(nextcloudCal)
        assertEquals("rows=${rows.map { it[Events.UID_2445] to it[Events.STATUS] }}", 1, rows.size)
        assertEquals(Events.STATUS_CANCELED.toString(), rows.single()[Events.STATUS])
    }

    @Test
    fun `answering an imported invitation writes the user's own row`() = runTest {
        importFile("issue_364_request.ics", issueCal)
        val eventId = events(issueCal).single()[Events._ID]!!.toLong()

        assertTrue(f.writer.replyRsvp(eventId, issueCal, Attendees.ATTENDEE_STATUS_ACCEPTED).getOrThrow())

        val self = attendees(eventId.toString()).single { it[Attendees.ATTENDEE_EMAIL] == issueOwner }
        assertEquals(Attendees.ATTENDEE_STATUS_ACCEPTED.toString(), self[Attendees.ATTENDEE_STATUS])
    }

    @Test
    fun `an imported invitation gets the default reminder, not the organizer's alarms`() = runTest {
        importFile("issue_364_request.ics", issueCal)

        val eventId = events(issueCal).single()[Events._ID]!!.toLong()
        assertEquals(listOf(DeviceRoundTripFixture.DEFAULT_TIMED_REMINDER), f.repository.getReminders(eventId))
    }

    @Test
    fun `an imported CANCEL leaves no device reminder for a one-off or a series`() = runTest {
        for (series in listOf(false, true)) {
            f.provider.db.execSQL("DELETE FROM ${SqliteCalendarProvider.EVENTS}")
            seedSyncedCopy(series = series)
            assertTrue("series=$series", nextReminder() != null)

            importFile("nextcloud_cancel.ics", nextcloudCal)

            assertEquals("series=$series", null, nextReminder())
        }
    }

    @Test
    fun `an occurrence CANCEL cancels that occurrence of the series`() = runTest {
        val master = seedSyncedCopy(series = true)
        val cancel = fixture("nextcloud_cancel.ics")
            .replace("DTSTART:20261018T100000Z", "RECURRENCE-ID:20261019T100000Z\r\nDTSTART:20261019T100000Z")
            .replace("DTEND:20261018T110000Z", "DTEND:20261019T110000Z\r\nSTATUS:CANCELLED")

        val result = importText(cancel, nextcloudCal)

        assertEquals("$result", 1, result.cancelled)
        val exception = events(nextcloudCal).single { it[Events.ORIGINAL_ID] == master.toString() }
        assertEquals(Events.STATUS_CANCELED.toString(), exception[Events.STATUS])
        assertEquals((meetingStart + DAY).toString(), exception[Events.ORIGINAL_INSTANCE_TIME])
    }

    @Test
    fun `a UID on another calendar of the account skips an invitation and strips a plain event's UID`() = runTest {
        val sibling = 15L
        f.provider.seedCalendar(sibling, accountName = nextcloudOwner, displayName = "Work")
        seedSyncedCopy(calendarId = sibling)

        val invitation = importFile("nextcloud_request.ics", nextcloudCal)
        assertEquals(listOf(IgnoreReason.ON_ANOTHER_CALENDAR), invitation.ignored)
        assertTrue(events(nextcloudCal).isEmpty())

        importText(fixture("nextcloud_request.ics").replace(Regex("METHOD:REQUEST\r?\n"), ""), nextcloudCal)
        assertEquals(null, events(nextcloudCal).single()[Events.UID_2445])
    }
}
