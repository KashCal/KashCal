package org.onekash.kashcal.data.calendar_provider

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

/**
 * Pins the rules [SqliteCalendarProvider] models: the platform's (the
 * Instances view and search, insert rewrites, validation, deletes, write
 * refusals) and the stricter app write contract. Rows are seeded the way the
 * platform stores them or written through the resolver with values built
 * here, and read back through the real [AndroidCalendarProviderRepository],
 * or the Instances URI where a rule is about the view. Apart from one
 * [AndroidCalendarProviderRepository.ensureCalendarVisible] call, nothing is
 * written through the app's own code, so a mistake the app and the test
 * provider shared can't hide.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class SqliteCalendarProviderInstancesTest {

    private lateinit var provider: SqliteCalendarProvider
    private lateinit var repo: AndroidCalendarProviderRepository
    private lateinit var context: Context
    private lateinit var savedZone: TimeZone

    @Before
    fun setup() {
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        context = ApplicationProvider.getApplicationContext()
        provider = SqliteCalendarProvider.install()
        repo = SqliteCalendarProvider.repository(context)
        provider.seedCalendar(CAL, accountName = OWNER, displayName = "Work", color = CAL_COLOR)
    }

    @After
    fun tearDown() {
        provider.db.close()
        TimeZone.setDefault(savedZone)
    }

    // ---- seeding helpers (platform-shaped rows) ----

    private fun oneOff(
        start: Long = T0,
        end: Long? = T0 + HOUR,
        duration: String? = null,
        title: String = "Dentist",
        calendar: Long = CAL,
        syncId: String? = null,
        deleted: Boolean = false,
        status: Int? = Events.STATUS_CONFIRMED,
        description: String? = null,
        location: String? = null,
        allDay: Boolean = false,
    ): Long = provider.insertRow(
        SqliteCalendarProvider.EVENTS,
        Events.CALENDAR_ID to calendar, Events.TITLE to title, Events.DTSTART to start, Events.DTEND to end,
        Events.DURATION to duration, Events.EVENT_TIMEZONE to if (allDay) "UTC" else "America/New_York",
        Events.ALL_DAY to if (allDay) 1 else 0, Events._SYNC_ID to syncId, Events.DELETED to if (deleted) 1 else 0,
        Events.STATUS to status, Events.DESCRIPTION to description, Events.EVENT_LOCATION to location,
    )

    private fun series(
        rrule: String = "FREQ=WEEKLY;COUNT=3",
        start: Long = T0,
        duration: String? = "PT1H",
        syncId: String? = "series-1",
        deleted: Boolean = false,
        status: Int? = Events.STATUS_CONFIRMED,
        allDay: Boolean = false,
        dtend: Long? = null,
        // UTC by default so weekly steps are exact; the zone tests pass a real zone.
        timezone: String = "UTC",
    ): Long = provider.insertRow(
        SqliteCalendarProvider.EVENTS,
        Events.CALENDAR_ID to CAL, Events.TITLE to "Standup", Events.DTSTART to start, Events.DTEND to dtend,
        Events.DURATION to duration, Events.RRULE to rrule,
        Events.EVENT_TIMEZONE to if (allDay) "UTC" else timezone, Events.ALL_DAY to if (allDay) 1 else 0,
        Events._SYNC_ID to syncId, Events.DELETED to if (deleted) 1 else 0, Events.STATUS to status,
    )

    private fun exception(
        masterId: Long,
        originalTime: Long,
        start: Long = originalTime + HOUR,
        originalSyncId: String? = "series-1",
        status: Int = Events.STATUS_CONFIRMED,
        deleted: Boolean = false,
    ): Long = provider.insertRow(
        SqliteCalendarProvider.EVENTS,
        Events.CALENDAR_ID to CAL, Events.TITLE to "Standup (moved)", Events.DTSTART to start,
        Events.DTEND to start + HOUR, Events.EVENT_TIMEZONE to "America/New_York",
        Events.ORIGINAL_ID to masterId, Events.ORIGINAL_SYNC_ID to originalSyncId,
        Events.ORIGINAL_INSTANCE_TIME to originalTime, Events.STATUS to status,
        Events.DELETED to if (deleted) 1 else 0,
    )

    private fun instancesUri(begin: Long, end: Long): Uri =
        Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, begin)
            ContentUris.appendId(it, end)
        }.build()

    /** (eventId, begin, end) of every row the view returns for [begin, end], sorted by begin. */
    private fun viewRows(begin: Long, end: Long, selection: String? = null): List<Triple<Long, Long, Long>> =
        context.contentResolver.query(
            instancesUri(begin, end), arrayOf(Instances.EVENT_ID, Instances.BEGIN, Instances.END),
            selection, null, "${Instances.BEGIN} ASC",
        )!!.use { c -> buildList { while (c.moveToNext()) add(Triple(c.getLong(0), c.getLong(1), c.getLong(2))) } }

    private suspend fun dayRange(vararg calendars: Long = longArrayOf(CAL), hideDeclined: Boolean = false) =
        repo.getInstancesForDayRange(DAY_START, DAY_END, calendars.toSet(), hideDeclined)

    // ---- window ----

    @Test
    fun `the window keeps an occurrence that starts exactly at its end`() {
        val id = oneOff(start = T0, end = T0 + HOUR)

        assertEquals(listOf(Triple(id, T0, T0 + HOUR)), viewRows(T0 - 10 * HOUR, T0))
    }

    @Test
    fun `the window keeps an occurrence that ends exactly at its start`() {
        val id = oneOff(start = T0, end = T0 + HOUR)

        assertEquals(listOf(Triple(id, T0, T0 + HOUR)), viewRows(T0 + HOUR, T0 + 10 * HOUR))
    }

    @Test
    fun `the window drops occurrences entirely outside it`() {
        oneOff(start = T0, end = T0 + HOUR)

        assertTrue(viewRows(T0 + HOUR + 1, T0 + 10 * HOUR).isEmpty())
        assertTrue(viewRows(T0 - 10 * HOUR, T0 - 1).isEmpty())
    }

    @Test
    fun `a series occurrence that started before the window but overlaps it is kept`() {
        val id = series(rrule = "FREQ=DAILY;COUNT=1", start = T0, duration = "PT3H")

        assertEquals(listOf(Triple(id, T0, T0 + 3 * HOUR)), viewRows(T0 + HOUR, T0 + 2 * HOUR))
    }

    // ---- calendars ----

    @Test
    fun `a calendar that doesn't sync events shows nothing until it is made visible`() = runTest {
        provider.seedCalendar(OFF_CAL, accountName = OWNER, syncEvents = false)
        oneOff(calendar = OFF_CAL)

        assertTrue(dayRange(OFF_CAL).isEmpty())

        repo.ensureCalendarVisible(OFF_CAL)

        assertEquals(1, dayRange(OFF_CAL).size)
    }

    @Test
    fun `occurrences carry the calendar row's name, colour and access level`() = runTest {
        oneOff()

        val instance = dayRange().single()
        assertEquals("Work", instance.calendarDisplayName)
        assertEquals(CAL_COLOR, instance.calendarColor)
        assertTrue(instance.isWritable)
    }

    @Test
    fun `a hidden calendar's events are left out by the app's visible-only selection`() = runTest {
        provider.seedCalendar(HIDDEN_CAL, accountName = OWNER, visible = false)
        oneOff(calendar = HIDDEN_CAL)

        assertTrue(dayRange(HIDDEN_CAL).isEmpty())
    }

    @Test
    fun `an event whose calendar row is missing yields no occurrence`() = runTest {
        oneOff(calendar = 999L)

        assertTrue(dayRange(999L).isEmpty())
    }

    // ---- series and exceptions ----

    @Test
    fun `a series expands to each occurrence with its duration`() {
        val id = series()

        assertEquals(
            listOf(Triple(id, T0, T0 + HOUR), Triple(id, T0 + WEEK, T0 + WEEK + HOUR), Triple(id, T0 + 2 * WEEK, T0 + 2 * WEEK + HOUR)),
            viewRows(T0 - DAY, T0 + 3 * WEEK),
        )
    }

    @Test
    fun `a series in a zone with a daylight saving change keeps its local time across it`() {
        // 2024-03-10 is the US spring-forward: 10:00 New York is 15:00Z before, 14:00Z after.
        val id = series(rrule = "FREQ=WEEKLY;COUNT=2", timezone = "America/New_York")

        assertEquals(listOf(T0, T0 + WEEK - HOUR), viewRows(T0 - DAY, T0 + 2 * WEEK).filter { it.first == id }.map { it.second })
    }

    @Test
    fun `a UTC end date on a series in another zone is compared as an instant whatever the phone's zone`() {
        // 10:00 New York each Tuesday; UNTIL is one second before the third one.
        for (phoneZone in listOf("UTC", "Europe/Berlin", "America/Los_Angeles")) {
            TimeZone.setDefault(TimeZone.getTimeZone(phoneZone))
            provider.db.delete(SqliteCalendarProvider.EVENTS, null, null)
            series(rrule = "FREQ=WEEKLY;BYDAY=TU;UNTIL=20240319T135959Z", timezone = "America/New_York")

            assertEquals(phoneZone, listOf(T0, T0 + WEEK - HOUR), viewRows(T0 - DAY, T0 + 4 * WEEK).map { it.second })
        }
    }

    @Test
    fun `a synced exception replaces the series occurrence it overrides`() {
        val master = series()
        val ex = exception(master, originalTime = T0 + WEEK)

        assertEquals(
            listOf(Triple(master, T0, T0 + HOUR), Triple(ex, T0 + WEEK + HOUR, T0 + WEEK + 2 * HOUR), Triple(master, T0 + 2 * WEEK, T0 + 2 * WEEK + HOUR)),
            viewRows(T0 - DAY, T0 + 3 * WEEK),
        )
    }

    @Test
    fun `a cancelled synced exception removes its occurrence`() {
        val master = series()
        exception(master, originalTime = T0 + WEEK, status = Events.STATUS_CANCELED)

        assertEquals(listOf(T0, T0 + 2 * WEEK), viewRows(T0 - DAY, T0 + 3 * WEEK).map { it.second })
    }

    @Test
    fun `a deleted synced exception removes its occurrence and shows nothing itself`() {
        val master = series()
        exception(master, originalTime = T0 + WEEK, deleted = true)

        assertEquals(listOf(T0, T0 + 2 * WEEK), viewRows(T0 - DAY, T0 + 3 * WEEK).map { it.second })
    }

    @Test
    fun `an exception without a sync id leaves the series occurrence in place and shows beside it`() {
        val master = series(syncId = null)
        val ex = exception(master, originalTime = T0 + WEEK, originalSyncId = null)

        val rows = viewRows(T0 - DAY, T0 + 3 * WEEK)
        assertEquals(listOf(T0, T0 + WEEK, T0 + WEEK + HOUR, T0 + 2 * WEEK), rows.map { it.second })
        assertEquals(ex, rows[2].first)
    }

    @Test
    fun `a cancelled exception without a sync id reaches the view as a cancelled row`() {
        val master = series(syncId = null)
        exception(master, originalTime = T0 + WEEK, originalSyncId = null, status = Events.STATUS_CANCELED)

        val statuses = context.contentResolver.query(
            instancesUri(T0 - DAY, T0 + 3 * WEEK), arrayOf(Instances.BEGIN, Instances.STATUS), null, null, "${Instances.BEGIN} ASC",
        )!!.use { c -> buildList { while (c.moveToNext()) add(c.getLong(0) to c.getInt(1)) } }
        assertEquals(4, statuses.size)
        assertEquals(Events.STATUS_CANCELED, statuses.single { it.first == T0 + WEEK + HOUR }.second)
    }

    @Test
    fun `deleted and cancelled series yield nothing`() {
        series(syncId = "a", deleted = true)
        series(syncId = "b", status = Events.STATUS_CANCELED)

        assertTrue(viewRows(T0 - DAY, T0 + 3 * WEEK).isEmpty())
    }

    @Test
    fun `a deleted one-off yields nothing`() {
        oneOff(syncId = "x", deleted = true)

        assertTrue(viewRows(T0 - DAY, T0 + DAY).isEmpty())
    }

    @Test
    fun `a one-off with a DURATION ends after that duration`() {
        val id = oneOff(end = null, duration = "PT30M")

        assertEquals(listOf(Triple(id, T0, T0 + 30 * MINUTE)), viewRows(T0 - DAY, T0 + DAY))
    }

    @Test
    fun `a seconds-only duration is read as the platform reads it`() {
        val id = series(rrule = "FREQ=DAILY;COUNT=1", duration = "P1800S")

        assertEquals(listOf(Triple(id, T0, T0 + 30 * MINUTE)), viewRows(T0 - DAY, T0 + DAY))
    }

    @Test
    fun `an all-day series expands at UTC midnights for a day each`() {
        val id = series(rrule = "FREQ=DAILY;COUNT=2", start = DAY0, duration = "P1D", allDay = true)

        assertEquals(listOf(Triple(id, DAY0, DAY0 + DAY), Triple(id, DAY0 + DAY, DAY0 + 2 * DAY)), viewRows(DAY0 - DAY, DAY0 + 3 * DAY))
    }

    @Test
    fun `a series with an unreadable duration lasts no time and one with none falls back to its end or a day`() {
        val broken = series(rrule = "FREQ=DAILY;COUNT=1", duration = "banana", syncId = "a")
        val allDayNoDuration = series(rrule = "FREQ=DAILY;COUNT=1", start = DAY0, duration = null, syncId = "b", allDay = true)
        val timedWithEnd = series(rrule = "FREQ=DAILY;COUNT=1", duration = null, syncId = "c", dtend = T0 + 2 * HOUR)

        val rows = viewRows(DAY0 - DAY, T0 + DAY).associate { it.first to (it.third - it.second) }
        assertEquals(0L, rows[broken])
        assertEquals(DAY, rows[allDayNoDuration])
        assertEquals(2 * HOUR, rows[timedWithEnd])
    }

    @Test
    fun `the platform duration parser accepts the forms the platform accepts`() {
        assertEquals(HOUR, SqliteCalendarProvider.parsePlatformDuration("PT1H"))
        assertEquals(3_600_000L, SqliteCalendarProvider.parsePlatformDuration("P3600S"))
        assertEquals(DAY + HOUR, SqliteCalendarProvider.parsePlatformDuration("P1DT1H"))
        assertEquals(WEEK, SqliteCalendarProvider.parsePlatformDuration("P1W"))
        assertEquals(-15 * MINUTE, SqliteCalendarProvider.parsePlatformDuration("-PT15M"))
        assertNull(SqliteCalendarProvider.parsePlatformDuration("1H"))
    }

    // ---- self attendee status and alarms ----

    @Test
    fun `the owner's attendee row sets the occurrence's own response`() = runTest {
        val id = oneOff()
        provider.insertRow(
            SqliteCalendarProvider.ATTENDEES, Attendees.EVENT_ID to id, Attendees.ATTENDEE_EMAIL to OWNER,
            Attendees.ATTENDEE_RELATIONSHIP to Attendees.RELATIONSHIP_ATTENDEE, Attendees.ATTENDEE_STATUS to Attendees.ATTENDEE_STATUS_DECLINED,
        )

        assertEquals(Attendees.ATTENDEE_STATUS_DECLINED, dayRange().single().selfAttendeeStatus)
        assertTrue("declined is hidden when asked", dayRange(hideDeclined = true).isEmpty())
    }

    @Test
    fun `an owner row whose email differs only in case doesn't set the response`() = runTest {
        val id = oneOff()
        provider.insertRow(
            SqliteCalendarProvider.ATTENDEES, Attendees.EVENT_ID to id, Attendees.ATTENDEE_EMAIL to OWNER.uppercase(),
            Attendees.ATTENDEE_RELATIONSHIP to Attendees.RELATIONSHIP_ATTENDEE, Attendees.ATTENDEE_STATUS to Attendees.ATTENDEE_STATUS_DECLINED,
        )

        assertEquals(Attendees.ATTENDEE_STATUS_NONE, dayRange().single().selfAttendeeStatus)
        assertEquals(1, dayRange(hideDeclined = true).size)
    }

    @Test
    fun `an owner organizer row without a status counts as accepted`() = runTest {
        val id = oneOff()
        provider.insertRow(
            SqliteCalendarProvider.ATTENDEES, Attendees.EVENT_ID to id, Attendees.ATTENDEE_EMAIL to OWNER,
            Attendees.ATTENDEE_RELATIONSHIP to Attendees.RELATIONSHIP_ORGANIZER,
        )

        assertEquals(Attendees.ATTENDEE_STATUS_ACCEPTED, dayRange().single().selfAttendeeStatus)
    }

    @Test
    fun `an occurrence has an alarm exactly when its event has reminder rows`() = runTest {
        val withReminder = oneOff(title = "A")
        oneOff(title = "B")
        provider.insertRow(SqliteCalendarProvider.REMINDERS, Reminders.EVENT_ID to withReminder, Reminders.MINUTES to 10, Reminders.METHOD to Reminders.METHOD_ALERT)

        val byTitle = dayRange().associateBy { it.title }
        assertTrue(byTitle.getValue("A").hasAlarm)
        assertFalse(byTitle.getValue("B").hasAlarm)
    }

    @Test
    fun `the next reminder skips declined events and fires for the rest`() = runTest {
        val declined = oneOff(title = "Declined", start = T0 + HOUR, end = T0 + 2 * HOUR)
        val kept = oneOff(title = "Kept", start = T0 + 3 * HOUR, end = T0 + 4 * HOUR)
        for (id in listOf(declined, kept)) {
            provider.insertRow(SqliteCalendarProvider.REMINDERS, Reminders.EVENT_ID to id, Reminders.MINUTES to 10, Reminders.METHOD to Reminders.METHOD_ALERT)
        }
        provider.insertRow(
            SqliteCalendarProvider.ATTENDEES, Attendees.EVENT_ID to declined, Attendees.ATTENDEE_EMAIL to OWNER,
            Attendees.ATTENDEE_STATUS to Attendees.ATTENDEE_STATUS_DECLINED,
        )

        val next = repo.getNextUpcomingReminder(setOf(CAL), afterMs = T0)

        assertNotNull(next)
        assertEquals(kept, next!!.eventId)
        assertEquals(T0 + 3 * HOUR - 10 * MINUTE, next.triggerTime)
    }

    @Test
    fun `the next occurrence of a series is found through the view`() = runTest {
        val id = series()

        assertEquals(T0 + WEEK, repo.getNextOccurrenceStart(id, afterMs = T0 + 2 * DAY))
        assertNull(repo.getNextOccurrenceStart(id, afterMs = T0 + 4 * WEEK))
    }

    // ---- search ----

    @Test
    fun `search needs every word to match somewhere in the event`() = runTest {
        oneOff(title = "Team lunch", location = "Cafe Rio")
        oneOff(title = "Team sync", location = "Room 4")

        assertEquals(listOf("Team lunch"), repo.searchInstances("team rio", DAY_START, DAY_END, setOf(CAL)).map { it.title })
    }

    @Test
    fun `search keeps a quoted phrase whole`() = runTest {
        oneOff(title = "Lunch team")
        oneOff(title = "Team lunch")

        assertEquals(listOf("Team lunch"), repo.searchInstances("\"team lunch\"", DAY_START, DAY_END, setOf(CAL)).map { it.title })
    }

    @Test
    fun `search finds an event by a guest's name or email`() = runTest {
        val id = oneOff(title = "Review")
        oneOff(title = "Other")
        provider.insertRow(SqliteCalendarProvider.ATTENDEES, Attendees.EVENT_ID to id, Attendees.ATTENDEE_EMAIL to "ana@example.test", Attendees.ATTENDEE_NAME to "Ana Lima")

        assertEquals(listOf("Review"), repo.searchInstances("lima", DAY_START, DAY_END, setOf(CAL)).map { it.title })
        // The platform splits on '.', so an email is searched as its pieces.
        assertEquals(listOf("Review"), repo.searchInstances("ana@example", DAY_START, DAY_END, setOf(CAL)).map { it.title })
    }

    @Test
    fun `search treats percent and underscore literally`() = runTest {
        oneOff(title = "50% off")
        oneOff(title = "500 off")
        oneOff(title = "ab_c")
        oneOff(title = "abxc")

        assertEquals(listOf("50% off"), repo.searchInstances("50%", DAY_START, DAY_END, setOf(CAL)).map { it.title })
        assertEquals(listOf("ab_c"), repo.searchInstances("ab_c", DAY_START, DAY_END, setOf(CAL)).map { it.title })
    }

    @Test
    fun `search folds case for ASCII letters only, as SQLite does`() = runTest {
        oneOff(title = "Überblick")

        assertEquals(listOf("Überblick"), repo.searchInstances("berblick", DAY_START, DAY_END, setOf(CAL)).map { it.title })
        assertTrue(repo.searchInstances("überblick", DAY_START, DAY_END, setOf(CAL)).isEmpty())
    }

    @Test
    fun `a search with no words matches every occurrence`() {
        oneOff(title = "A")
        oneOff(title = "B")

        val uri = Instances.CONTENT_SEARCH_URI.buildUpon().also {
            ContentUris.appendId(it, T0 - DAY)
            ContentUris.appendId(it, T0 + DAY)
            it.appendPath("...")
        }.build()
        val count = context.contentResolver.query(uri, arrayOf(Instances.EVENT_ID), null, null, null)!!.use { it.count }
        assertEquals(2, count)
    }

    // ---- insert rewrites ----

    private fun appEventValues(vararg extra: Pair<String, Any?>) = SqliteCalendarProvider.valuesOf(
        listOf(
            Events.CALENDAR_ID to CAL, Events.TITLE to "x", Events.DTSTART to T0 + WEEK, Events.DTEND to T0 + WEEK + HOUR,
            Events.EVENT_TIMEZONE to "UTC",
        ) + extra,
    )

    private fun syncAdapterUri(uri: Uri) = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, OWNER)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, "org.example.sync").build()

    @Test
    fun `an exception inserted with only the series id gets the series sync id`() {
        val master = series(syncId = "series-1")

        val uri = context.contentResolver.insert(
            Events.CONTENT_URI, appEventValues(Events.ORIGINAL_ID to master, Events.ORIGINAL_INSTANCE_TIME to T0 + WEEK),
        )!!

        assertEquals("series-1", provider.row(SqliteCalendarProvider.EVENTS, ContentUris.parseId(uri))!![Events.ORIGINAL_SYNC_ID])
    }

    @Test
    fun `an exception inserted with only the series sync id gets the series id`() {
        val master = series(syncId = "series-1")

        val uri = context.contentResolver.insert(
            syncAdapterUri(Events.CONTENT_URI),
            appEventValues(Events.ORIGINAL_SYNC_ID to "series-1", Events.ORIGINAL_INSTANCE_TIME to T0 + WEEK),
        )!!

        assertEquals(master.toString(), provider.row(SqliteCalendarProvider.EVENTS, ContentUris.parseId(uri))!![Events.ORIGINAL_ID])
    }

    @Test
    fun `a synced series inserted after its exception claims it`() {
        val ex = provider.insertRow(
            SqliteCalendarProvider.EVENTS, Events.CALENDAR_ID to CAL, Events.DTSTART to T0 + WEEK, Events.DTEND to T0 + WEEK + HOUR,
            Events.EVENT_TIMEZONE to "UTC", Events.ORIGINAL_SYNC_ID to "late-series", Events.ORIGINAL_INSTANCE_TIME to T0 + WEEK,
        )

        val masterUri = context.contentResolver.insert(
            syncAdapterUri(Events.CONTENT_URI),
            ContentValues().apply {
                put(Events.CALENDAR_ID, CAL); put(Events.DTSTART, T0); put(Events.DURATION, "PT1H"); put(Events.RRULE, "FREQ=WEEKLY")
                put(Events.EVENT_TIMEZONE, "UTC"); put(Events._SYNC_ID, "late-series")
            },
        )!!

        assertEquals(ContentUris.parseId(masterUri).toString(), provider.row(SqliteCalendarProvider.EVENTS, ex)!![Events.ORIGINAL_ID])
    }

    @Test
    fun `a series synced later passes its sync id to the exceptions linked to it`() {
        val master = series(syncId = null)
        val ex = exception(master, originalTime = T0 + WEEK, originalSyncId = null)
        val other = exception(series(syncId = null), originalTime = T0 + WEEK, originalSyncId = null)

        context.contentResolver.update(
            syncAdapterUri(ContentUris.withAppendedId(Events.CONTENT_URI, master)),
            ContentValues().apply { put(Events._SYNC_ID, "now-synced") }, null, null,
        )

        assertEquals("now-synced", provider.row(SqliteCalendarProvider.EVENTS, ex)!![Events.ORIGINAL_SYNC_ID])
        assertNull("another series' exception is untouched", provider.row(SqliteCalendarProvider.EVENTS, other)!![Events.ORIGINAL_SYNC_ID])
    }

    @Test
    fun `once a series syncs its earlier exception replaces the occurrence`() {
        val master = series(syncId = null)
        val ex = exception(master, originalTime = T0 + WEEK, originalSyncId = null)
        assertEquals("before the upload both show", 4, viewRows(T0 - DAY, T0 + 3 * WEEK).size)

        provider.updateRow(SqliteCalendarProvider.EVENTS, master, Events._SYNC_ID to "uploaded")

        assertEquals(listOf(T0, T0 + WEEK + HOUR, T0 + 2 * WEEK), viewRows(T0 - DAY, T0 + 3 * WEEK).map { it.second })
        assertEquals(ex, viewRows(T0 - DAY, T0 + 3 * WEEK)[1].first)
    }

    @Test
    fun `an app insert without an organizer gets the calendar owner and is marked dirty`() {
        val uri = insertEvent(appEventValues())!!

        val row = provider.row(SqliteCalendarProvider.EVENTS, ContentUris.parseId(uri))!!
        assertEquals(OWNER, row[Events.ORGANIZER])
        assertEquals("1", row[Events.DIRTY])
    }

    @Test
    fun `an alarm flag in an insert is dropped`() {
        // The platform strips it for every caller; an app writing it breaks the contract (below).
        val uri = context.contentResolver.insert(syncAdapterUri(Events.CONTENT_URI), appEventValues(Events.HAS_ALARM to 1))!!

        assertNull(provider.row(SqliteCalendarProvider.EVENTS, ContentUris.parseId(uri))!![Events.HAS_ALARM])
    }

    // ---- validation and batches ----

    private fun assertRejected(block: () -> Unit) {
        assertThrows(IllegalArgumentException::class.java) { block() }
    }

    @Test
    fun `an app insert needs exactly one of an end time and a duration`() {
        assertRejected { insertEvent(appEventValues(Events.DURATION to "PT1H")) }
        assertRejected { insertEvent(appEventValues(Events.DTEND to null)) }
    }

    @Test
    fun `an app insert needs a calendar, a timezone, a start and a readable rule`() {
        assertRejected { insertEvent(appEventValues(Events.CALENDAR_ID to null)) }
        assertRejected { insertEvent(appEventValues(Events.EVENT_TIMEZONE to null)) }
        assertRejected { insertEvent(appEventValues(Events.DTSTART to null)) }
        assertRejected {
            insertEvent(appEventValues(Events.DTEND to null, Events.DURATION to "PT1H", Events.RRULE to "WEEKLY"))
        }
    }

    @Test
    fun `an app update that leaves both an end time and a duration is rejected`() {
        val id = oneOff()

        assertRejected {
            context.contentResolver.update(
                ContentUris.withAppendedId(Events.CONTENT_URI, id), ContentValues().apply { put(Events.DURATION, "PT1H") }, null, null,
            )
        }
        assertNull(provider.row(SqliteCalendarProvider.EVENTS, id)!![Events.DURATION])
    }

    @Test
    fun `an app may not write its own response on the event row`() {
        val id = oneOff()

        assertRejected {
            context.contentResolver.update(
                ContentUris.withAppendedId(Events.CONTENT_URI, id),
                ContentValues().apply { put(Events.SELF_ATTENDEE_STATUS, Attendees.ATTENDEE_STATUS_ACCEPTED) }, null, null,
            )
        }
    }

    @Test
    fun `sync adapter writes are not validated`() {
        val uri = context.contentResolver.insert(syncAdapterUri(Events.CONTENT_URI), appEventValues(Events.DURATION to "PT1H"))

        assertNotNull(uri)
    }

    @Test
    fun `a batch whose second write fails leaves nothing behind`() {
        val ops = arrayListOf(
            ContentProviderOperation.newInsert(Events.CONTENT_URI).withValues(appEventValues()).build(),
            ContentProviderOperation.newInsert(Reminders.CONTENT_URI)
                .withValueBackReference(Reminders.EVENT_ID, 0).withValue(Reminders.MINUTES, 10).withValue(Reminders.METHOD, 1).build(),
            ContentProviderOperation.newInsert(Events.CONTENT_URI).withValues(appEventValues(Events.DTSTART to null)).build(),
        )

        assertRejected { context.contentResolver.applyBatch(CalendarContract.AUTHORITY, ops) }

        assertTrue(provider.rows(SqliteCalendarProvider.EVENTS).isEmpty())
        assertTrue(provider.rows(SqliteCalendarProvider.REMINDERS).isEmpty())
    }

    // ---- deletes ----

    private fun childRows(eventId: Long) = listOf(SqliteCalendarProvider.REMINDERS, SqliteCalendarProvider.ATTENDEES, SqliteCalendarProvider.EXTENDED_PROPERTIES)
        .associateWith { provider.rows(it, "${Reminders.EVENT_ID} = ?", eventId.toString()).size }

    private fun seedChildren(eventId: Long) {
        provider.insertRow(SqliteCalendarProvider.REMINDERS, Reminders.EVENT_ID to eventId, Reminders.MINUTES to 10)
        provider.insertRow(SqliteCalendarProvider.ATTENDEES, Attendees.EVENT_ID to eventId, Attendees.ATTENDEE_EMAIL to "g@example.test")
        provider.insertRow(
            SqliteCalendarProvider.EXTENDED_PROPERTIES, CalendarContract.ExtendedProperties.EVENT_ID to eventId,
            CalendarContract.ExtendedProperties.NAME to "categories", CalendarContract.ExtendedProperties.VALUE to "Work",
        )
    }

    private fun appDelete(id: Long) = context.contentResolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, id), null, null)

    @Test
    fun `an app delete of a never-synced event removes it and everything attached`() {
        val id = oneOff(syncId = null)
        seedChildren(id)

        appDelete(id)

        assertNull(provider.row(SqliteCalendarProvider.EVENTS, id))
        assertEquals(mapOf(SqliteCalendarProvider.REMINDERS to 0, SqliteCalendarProvider.ATTENDEES to 0, SqliteCalendarProvider.EXTENDED_PROPERTIES to 0), childRows(id))
    }

    @Test
    fun `an app delete of a synced event marks it deleted and keeps only its guests`() {
        val id = oneOff(syncId = "s1")
        seedChildren(id)

        appDelete(id)

        val row = provider.row(SqliteCalendarProvider.EVENTS, id)!!
        assertEquals("1", row[Events.DELETED])
        assertEquals("1", row[Events.DIRTY])
        assertEquals(mapOf(SqliteCalendarProvider.REMINDERS to 0, SqliteCalendarProvider.ATTENDEES to 1, SqliteCalendarProvider.EXTENDED_PROPERTIES to 0), childRows(id))
    }

    @Test
    fun `an app delete of a never-synced series removes its exceptions too`() {
        val master = series(syncId = null)
        val ex = exception(master, originalTime = T0 + WEEK, originalSyncId = null)
        seedChildren(ex)

        appDelete(master)

        assertNull(provider.row(SqliteCalendarProvider.EVENTS, master))
        assertNull(provider.row(SqliteCalendarProvider.EVENTS, ex))
        assertEquals(0, childRows(ex).values.sum())
    }

    @Test
    fun `an app delete of a synced series removes only its never-synced exceptions`() {
        val master = series(syncId = "series-1")
        val local = exception(master, originalTime = T0 + WEEK)
        val synced = exception(master, originalTime = T0 + 2 * WEEK)
        provider.updateRow(SqliteCalendarProvider.EVENTS, synced, Events._SYNC_ID to "ex-synced")

        appDelete(master)

        assertEquals("1", provider.row(SqliteCalendarProvider.EVENTS, master)!![Events.DELETED])
        assertNull(provider.row(SqliteCalendarProvider.EVENTS, local))
        assertNotNull(provider.row(SqliteCalendarProvider.EVENTS, synced))
    }

    @Test
    fun `a sync adapter delete always removes the row`() {
        val id = oneOff(syncId = "s1")

        context.contentResolver.delete(syncAdapterUri(ContentUris.withAppendedId(Events.CONTENT_URI, id)), null, null)

        assertNull(provider.row(SqliteCalendarProvider.EVENTS, id))
    }

    // ---- write refusals ----

    private fun eventUri(id: Long) = ContentUris.withAppendedId(Events.CONTENT_URI, id)

    private fun values(vararg pairs: Pair<String, Any?>) = SqliteCalendarProvider.valuesOf(pairs.toList())

    private fun seedAttendee(eventId: Long): Long = provider.insertRow(
        SqliteCalendarProvider.ATTENDEES,
        Attendees.EVENT_ID to eventId, Attendees.ATTENDEE_EMAIL to "g@example.test",
        Attendees.ATTENDEE_RELATIONSHIP to Attendees.RELATIONSHIP_ATTENDEE, Attendees.ATTENDEE_TYPE to Attendees.TYPE_REQUIRED,
        Attendees.ATTENDEE_STATUS to Attendees.ATTENDEE_STATUS_INVITED,
    )

    private fun seedReminder(eventId: Long): Long = provider.insertRow(
        SqliteCalendarProvider.REMINDERS,
        Reminders.EVENT_ID to eventId, Reminders.MINUTES to 10, Reminders.METHOD to Reminders.METHOD_ALERT,
    )

    private fun attendeeValues(eventId: Long?) = values(
        *listOfNotNull(eventId?.let { Attendees.EVENT_ID to it }).toTypedArray(),
        Attendees.ATTENDEE_EMAIL to "new@example.test", Attendees.ATTENDEE_RELATIONSHIP to Attendees.RELATIONSHIP_ATTENDEE,
        Attendees.ATTENDEE_TYPE to Attendees.TYPE_REQUIRED, Attendees.ATTENDEE_STATUS to Attendees.ATTENDEE_STATUS_INVITED,
    )

    private fun reminderValues(eventId: Long?) = values(
        *listOfNotNull(eventId?.let { Reminders.EVENT_ID to it }).toTypedArray(),
        Reminders.MINUTES to 30, Reminders.METHOD to Reminders.METHOD_ALERT,
    )

    private fun extendedPropertyValues(eventId: Long?) = values(
        *listOfNotNull(eventId?.let { CalendarContract.ExtendedProperties.EVENT_ID to it }).toTypedArray(),
        CalendarContract.ExtendedProperties.NAME to "categories", CalendarContract.ExtendedProperties.VALUE to "Work",
    )

    private fun assertRejectedWith(message: String, block: () -> Unit) {
        val e = assertThrows(IllegalArgumentException::class.java) { block() }
        assertEquals(message, e.message)
    }

    @Test
    fun `a selection is not permitted with an event id uri`() {
        val id = oneOff()

        assertRejectedWith("Selection not permitted for ${eventUri(id)}") {
            context.contentResolver.update(eventUri(id), values(Events.TITLE to "Renamed"), "${Events.TITLE} = ?", arrayOf("Dentist"))
        }
        assertRejectedWith("Selection not permitted for ${eventUri(id)}") {
            context.contentResolver.delete(eventUri(id), "${Events.TITLE} = ?", arrayOf("Dentist"))
        }

        val row = provider.row(SqliteCalendarProvider.EVENTS, id)!!
        assertEquals("Dentist", row[Events.TITLE])
        assertEquals("0", row[Events.DELETED])
    }

    @Test
    fun `a selection is not permitted with an attendee, reminder or calendar id uri`() {
        val id = oneOff()
        val attendee = seedAttendee(id)
        val reminder = seedReminder(id)
        val attendeeUri = ContentUris.withAppendedId(Attendees.CONTENT_URI, attendee)
        val reminderUri = ContentUris.withAppendedId(Reminders.CONTENT_URI, reminder)
        val calendarUri = ContentUris.withAppendedId(Calendars.CONTENT_URI, CAL)

        assertRejectedWith("Selection not permitted for $attendeeUri") {
            context.contentResolver.update(
                attendeeUri, values(Attendees.ATTENDEE_STATUS to Attendees.ATTENDEE_STATUS_ACCEPTED),
                "${Attendees.EVENT_ID} = ?", arrayOf(id.toString()),
            )
        }
        assertRejectedWith("Selection not permitted for $reminderUri") {
            context.contentResolver.delete(reminderUri, "${Reminders.EVENT_ID} = ?", arrayOf(id.toString()))
        }
        assertRejectedWith("Selection not permitted for $calendarUri") {
            context.contentResolver.update(calendarUri, values(Calendars.VISIBLE to 0), "${Calendars._ID} = ?", arrayOf(CAL.toString()))
        }

        assertEquals(Attendees.ATTENDEE_STATUS_INVITED.toString(), provider.row(SqliteCalendarProvider.ATTENDEES, attendee)!![Attendees.ATTENDEE_STATUS])
        assertNotNull(provider.row(SqliteCalendarProvider.REMINDERS, reminder))
        assertEquals("1", provider.row(SqliteCalendarProvider.CALENDARS, CAL)!![Calendars.VISIBLE])
    }

    @Test
    fun `a whitespace-only selection still counts as a selection`() {
        val id = oneOff()

        assertRejectedWith("Selection not permitted for ${eventUri(id)}") {
            context.contentResolver.update(eventUri(id), values(Events.TITLE to "Renamed"), " ", null)
        }
        assertEquals("Dentist", provider.row(SqliteCalendarProvider.EVENTS, id)!![Events.TITLE])
    }

    @Test
    fun `a bulk event, attendee or reminder write needs a selection`() {
        val id = oneOff()
        seedAttendee(id)
        seedReminder(id)

        assertRejectedWith("Selection must be specified for ${Events.CONTENT_URI}") {
            context.contentResolver.update(Events.CONTENT_URI, values(Events.DESCRIPTION to "x"), null, null)
        }
        assertRejectedWith("Selection must be specified for ${Events.CONTENT_URI}") {
            context.contentResolver.delete(Events.CONTENT_URI, "", null)
        }
        assertRejectedWith("Selection must be specified for ${Attendees.CONTENT_URI}") {
            context.contentResolver.update(Attendees.CONTENT_URI, values(Attendees.ATTENDEE_STATUS to Attendees.ATTENDEE_STATUS_ACCEPTED), null, null)
        }
        assertRejectedWith("Selection must be specified for ${Reminders.CONTENT_URI}") {
            context.contentResolver.delete(Reminders.CONTENT_URI, null, null)
        }
        assertRejectedWith("Selection must be specified for ${Reminders.CONTENT_URI}") {
            context.contentResolver.update(Reminders.CONTENT_URI, values(Reminders.MINUTES to 5), "", null)
        }

        val row = provider.row(SqliteCalendarProvider.EVENTS, id)!!
        assertNull(row[Events.DESCRIPTION])
        assertEquals("0", row[Events.DELETED])
        assertEquals(1, provider.rows(SqliteCalendarProvider.ATTENDEES).size)
        assertEquals(listOf(10 to Reminders.METHOD_ALERT), provider.reminderRows(id))
    }

    @Test
    fun `a bulk calendar update without a selection is allowed`() {
        provider.seedCalendar(OFF_CAL, visible = false)

        val count = context.contentResolver.update(Calendars.CONTENT_URI, values(Calendars.VISIBLE to 1), null, null)

        assertEquals(2, count)
        assertEquals("1", provider.row(SqliteCalendarProvider.CALENDARS, OFF_CAL)!![Calendars.VISIBLE])
    }

    @Test
    fun `an app may not write an event's sync adapter columns`() {
        val id = oneOff()

        assertRejectedWith("Only sync adapters may write to _sync_id") {
            insertEvent(appEventValues(Events._SYNC_ID to "s1"))
        }
        assertRejectedWith("Only sync adapters may write to dirty") {
            context.contentResolver.update(eventUri(id), values(Events.DIRTY to 0), null, null)
        }
        assertRejectedWith("Only sync adapters may write to sync_data1") {
            context.contentResolver.update(eventUri(id), values(Events.SYNC_DATA1 to "x"), null, null)
        }
        assertRejectedWith("Only sync adapters may write to mutators") {
            context.contentResolver.update(eventUri(id), values(Events.MUTATORS to "x"), null, null)
        }

        assertEquals(listOf(id.toString()), provider.rows(SqliteCalendarProvider.EVENTS).map { it[Events._ID] })
    }

    @Test
    fun `a sync adapter may write an event's sync id`() {
        val uri = context.contentResolver.insert(syncAdapterUri(Events.CONTENT_URI), appEventValues(Events._SYNC_ID to "s1"))!!

        assertEquals("s1", provider.row(SqliteCalendarProvider.EVENTS, ContentUris.parseId(uri))!![Events._SYNC_ID])
    }

    @Test
    fun `no caller may write the calendar's own columns through an event`() {
        val id = oneOff()

        assertRejectedWith("Only the provider may write to calendar_color") {
            insertEvent(appEventValues(Events.CALENDAR_COLOR to 1))
        }
        assertRejectedWith("Only the provider may write to calendar_color") {
            context.contentResolver.insert(syncAdapterUri(Events.CONTENT_URI), appEventValues(Events.CALENDAR_COLOR to 1))
        }
        assertRejectedWith("Only the provider may write to calendar_displayName") {
            insertEvent(appEventValues(Events.CALENDAR_DISPLAY_NAME to "x"))
        }
        // Presence of the key is enough, as on the platform.
        assertRejectedWith("Only the provider may write to visible") {
            context.contentResolver.update(eventUri(id), values(Events.VISIBLE to null), null, null)
        }

        assertEquals(1, provider.rows(SqliteCalendarProvider.EVENTS).size)
    }

    @Test
    fun `an app may show or sync a calendar but not change its sync adapter columns`() {
        val calendarUri = ContentUris.withAppendedId(Calendars.CONTENT_URI, CAL)

        assertEquals(1, context.contentResolver.update(calendarUri, values(Calendars.VISIBLE to 0, Calendars.SYNC_EVENTS to 1), null, null))
        assertRejectedWith("Only sync adapters may write to calendar_access_level") {
            context.contentResolver.update(calendarUri, values(Calendars.CALENDAR_ACCESS_LEVEL to Calendars.CAL_ACCESS_READ), null, null)
        }
        assertRejectedWith("Only sync adapters may write to ownerAccount") {
            context.contentResolver.update(calendarUri, values(Calendars.OWNER_ACCOUNT to "x@example.test"), null, null)
        }

        val row = provider.row(SqliteCalendarProvider.CALENDARS, CAL)!!
        assertEquals("0", row[Calendars.VISIBLE])
        assertEquals(Calendars.CAL_ACCESS_OWNER.toString(), row[Calendars.CALENDAR_ACCESS_LEVEL])
        assertEquals(OWNER, row[Calendars.OWNER_ACCOUNT])
    }

    @Test
    fun `an app may not write sync adapter columns on attendees or reminders`() {
        val id = oneOff()

        assertRejectedWith("Only sync adapters may write to dirty") {
            context.contentResolver.insert(Reminders.CONTENT_URI, reminderValues(id).apply { put(Events.DIRTY, 1) })
        }
        assertRejectedWith("Only sync adapters may write to _sync_id") {
            context.contentResolver.insert(Attendees.CONTENT_URI, attendeeValues(id).apply { put(Events._SYNC_ID, "x") })
        }

        assertTrue(provider.rows(SqliteCalendarProvider.REMINDERS).isEmpty())
        assertTrue(provider.rows(SqliteCalendarProvider.ATTENDEES).isEmpty())
    }

    @Test
    fun `an attendee, reminder or extended property needs an event id`() {
        assertRejectedWith("Attendees values must contain an event_id") {
            context.contentResolver.insert(Attendees.CONTENT_URI, attendeeValues(null))
        }
        assertRejectedWith("Reminders values must contain a numeric event_id") {
            context.contentResolver.insert(Reminders.CONTENT_URI, reminderValues(null))
        }
        assertRejectedWith("ExtendedProperties values must contain a numeric event_id") {
            context.contentResolver.insert(syncAdapterUri(CalendarContract.ExtendedProperties.CONTENT_URI), extendedPropertyValues(null))
        }
    }

    @Test
    fun `an attendee, reminder or extended property for a missing event is dropped`() {
        val missing = 987_654_321L

        assertNull(context.contentResolver.insert(Attendees.CONTENT_URI, attendeeValues(missing)))
        assertNull(context.contentResolver.insert(Reminders.CONTENT_URI, reminderValues(missing)))
        assertNull(context.contentResolver.insert(syncAdapterUri(CalendarContract.ExtendedProperties.CONTENT_URI), extendedPropertyValues(missing)))

        assertTrue(provider.rows(SqliteCalendarProvider.ATTENDEES).isEmpty())
        assertTrue(provider.rows(SqliteCalendarProvider.REMINDERS).isEmpty())
        assertTrue(provider.rows(SqliteCalendarProvider.EXTENDED_PROPERTIES).isEmpty())
    }

    @Test
    fun `a batch with a reminder for a missing event fails and leaves nothing behind`() {
        val ops = arrayListOf(
            ContentProviderOperation.newInsert(Events.CONTENT_URI).withValues(appEventValues()).build(),
            ContentProviderOperation.newInsert(Reminders.CONTENT_URI).withValues(reminderValues(987_654_321L)).build(),
        )

        assertThrows(android.content.OperationApplicationException::class.java) {
            context.contentResolver.applyBatch(CalendarContract.AUTHORITY, ops)
        }

        assertTrue(provider.rows(SqliteCalendarProvider.EVENTS).isEmpty())
        assertTrue(provider.rows(SqliteCalendarProvider.REMINDERS).isEmpty())
    }

    @Test
    fun `a deleted but not yet purged event still takes a reminder`() {
        val id = oneOff(syncId = "s1", deleted = true)

        assertNotNull(context.contentResolver.insert(Reminders.CONTENT_URI, reminderValues(id)))

        assertEquals(listOf(30 to Reminders.METHOD_ALERT), provider.reminderRows(id))
    }

    @Test
    fun `the seeding helpers skip the write rules`() {
        val id = oneOff()

        provider.insertRow(SqliteCalendarProvider.REMINDERS, Reminders.EVENT_ID to 987_654_321L, Reminders.MINUTES to 5)
        provider.updateRow(SqliteCalendarProvider.EVENTS, id, Events._SYNC_ID to "seeded", Events.DIRTY to 0)

        assertEquals(1, provider.rows(SqliteCalendarProvider.REMINDERS).size)
        assertEquals("seeded", provider.row(SqliteCalendarProvider.EVENTS, id)!![Events._SYNC_ID])
    }

    // ---- app write contract ----

    private fun appSeriesValues(
        rrule: String,
        duration: String = "PT1H",
        allDay: Boolean = false,
        extra: List<Pair<String, Any?>> = emptyList(),
    ) = appEventValues(
        Events.DTSTART to if (allDay) DAY0 + WEEK else T0 + WEEK, Events.DTEND to null,
        Events.DURATION to duration, Events.RRULE to rrule, Events.ALL_DAY to if (allDay) 1 else 0, *extra.toTypedArray(),
    )

    private fun assertViolation(block: () -> Unit): CalendarContractViolation =
        assertThrows(CalendarContractViolation::class.java) { block() }

    private fun insertEvent(values: ContentValues) = context.contentResolver.insert(Events.CONTENT_URI, values)

    @Test
    fun `a contract violation is an error the app's exception handlers cannot catch`() {
        assertTrue(AssertionError::class.java.isAssignableFrom(CalendarContractViolation::class.java))
        assertFalse(Exception::class.java.isAssignableFrom(CalendarContractViolation::class.java))
    }

    @Test
    fun `an app may write only the documented event columns`() {
        val id = oneOff()

        assertViolation { insertEvent(appEventValues(Events.HAS_ALARM to 1)) }
        assertViolation { insertEvent(appEventValues(Events.SELF_ATTENDEE_STATUS to Attendees.ATTENDEE_STATUS_ACCEPTED)) }
        assertViolation { context.contentResolver.update(eventUri(id), values(Events.DELETED to 1), null, null) }

        assertEquals(1, provider.rows(SqliteCalendarProvider.EVENTS).size)
        assertEquals("0", provider.row(SqliteCalendarProvider.EVENTS, id)!![Events.DELETED])
    }

    @Test
    fun `an app may write an event's status and attendee flag`() {
        val id = oneOff()

        assertNotNull(insertEvent(appEventValues(Events.STATUS to Events.STATUS_CONFIRMED, Events.HAS_ATTENDEE_DATA to 1)))
        assertEquals(1, context.contentResolver.update(eventUri(id), values(Events.STATUS to Events.STATUS_CANCELED), null, null))
    }

    @Test
    fun `a one-off event is written with an end time, not a duration`() {
        assertViolation { insertEvent(appEventValues(Events.DTEND to null, Events.DURATION to "PT1H")) }
        assertNotNull(insertEvent(appEventValues()))
    }

    @Test
    fun `a series is written with a duration, not an end time`() {
        assertViolation {
            insertEvent(appSeriesValues("FREQ=WEEKLY;COUNT=3").apply { remove(Events.DURATION); put(Events.DTEND, T0 + WEEK + HOUR) })
        }
        assertNotNull(insertEvent(appSeriesValues("FREQ=WEEKLY;COUNT=3")))
    }

    @Test
    fun `a series may not also be an exception`() {
        val master = series()

        assertViolation { insertEvent(appSeriesValues("FREQ=WEEKLY;COUNT=3", extra = listOf(Events.ORIGINAL_ID to master))) }
        assertViolation { insertEvent(appSeriesValues("FREQ=WEEKLY;COUNT=3", extra = listOf(Events.ORIGINAL_SYNC_ID to "series-1"))) }
    }

    @Test
    fun `an all-day event is written in UTC on midnight boundaries`() {
        val allDay = arrayOf(Events.ALL_DAY to 1, Events.DTSTART to DAY0 + WEEK, Events.DTEND to DAY0 + WEEK + DAY)

        assertViolation { insertEvent(appEventValues(*allDay, Events.EVENT_TIMEZONE to "America/New_York")) }
        assertViolation { insertEvent(appEventValues(*allDay, Events.DTSTART to DAY0 + WEEK + HOUR)) }
        assertViolation { insertEvent(appEventValues(*allDay, Events.DTEND to DAY0 + WEEK + DAY + MINUTE)) }
        assertNotNull(insertEvent(appEventValues(*allDay)))
    }

    @Test
    fun `an app update is checked against the whole row`() {
        val id = oneOff()

        // The platform accepts a lone DURATION on a one-off; the documented contract does not.
        assertViolation {
            context.contentResolver.update(eventUri(id), values(Events.DTEND to null, Events.DURATION to "PT1H"), null, null)
        }
        assertEquals((T0 + HOUR).toString(), provider.row(SqliteCalendarProvider.EVENTS, id)!![Events.DTEND])
    }

    @Test
    fun `an app edit of a row another app stored off the contract checks only what it writes`() {
        // A one-off stored with a duration and no end time, as some sync adapters do.
        val id = oneOff(end = null, duration = "PT1H")

        assertEquals(1, context.contentResolver.update(eventUri(id), values(Events.TITLE to "Renamed"), null, null))
        assertViolation { context.contentResolver.update(eventUri(id), values(Events.HAS_ALARM to 1), null, null) }
    }

    @Test
    fun `a series rule the app writes follows RFC 5545`() {
        val bad = listOf(
            "FREQ=WEEKLY;COUNT=3;UNTIL=20240401T000000Z", // UNTIL and COUNT together
            "COUNT=3;FREQ=WEEKLY", // FREQ not first
            "FREQ=FORTNIGHTLY",
            "FREQ=WEEKLY;COUNT=0",
            "FREQ=WEEKLY;COUNT=abc",
            "FREQ=WEEKLY;COUNT=99999999999",
            "FREQ=WEEKLY;INTERVAL=0",
            "FREQ=WEEKLY;INTERVAL=2;INTERVAL=3",
            "FREQ=WEEKLY;BYDAY=+",
            "FREQ=WEEKLY;BYDAY=1MO", // an ordinal outside MONTHLY or YEARLY
            "FREQ=MONTHLY;BYDAY=54MO",
            "FREQ=YEARLY;BYWEEKNO=1;BYDAY=1MO",
            "FREQ=MONTHLY;BYMONTHDAY=32",
            "FREQ=MONTHLY;BYMONTHDAY=0",
            "FREQ=WEEKLY;BYMONTHDAY=1",
            "FREQ=MONTHLY;BYYEARDAY=1",
            "FREQ=MONTHLY;BYWEEKNO=1",
            "FREQ=YEARLY;BYMONTH=13",
            "FREQ=DAILY;BYHOUR=24",
            "FREQ=DAILY;BYMINUTE=60",
            "FREQ=DAILY;BYSECOND=61",
            "FREQ=MONTHLY;BYSETPOS=1", // BYSETPOS alone
            "FREQ=WEEKLY;WKST=XX",
            "FREQ=WEEKLY;RSCALE=GREGORIAN", // not an RFC 5545 rule part
            "RRULE:FREQ=WEEKLY",
            "FREQ=WEEKLY;;COUNT=3",
            ";FREQ=WEEKLY;COUNT=3",
            "FREQ=WEEKLY;UNTIL=20240401", // a DATE on a timed series
            "FREQ=WEEKLY;UNTIL=20240401T000000", // a floating time on a timed series
            "FREQ=WEEKLY;UNTIL=20241301T000000Z",
            "FREQ=YEARLY;BYYEARDAY=367",
            "FREQ=YEARLY;BYWEEKNO=54",
            "FREQ=MONTHLY;BYDAY=MO;BYSETPOS=367",
            "FREQ=DAILY;BYYEARDAY=1",
            "FREQ=WEEKLY;BYYEARDAY=1",
            "FREQ=WEEKLY;COUNT=\u0663", // a digit, but not an ASCII one
            "FREQ=WEEKLY;BYDAY=+MO",
        )
        for (rule in bad) {
            assertThrows("expected a violation for $rule", CalendarContractViolation::class.java) {
                insertEvent(appSeriesValues(rule))
            }
        }
        val good = listOf(
            "FREQ=WEEKLY;COUNT=3",
            "freq=weekly;byday=mo,we", // names and values are case-insensitive
            "FREQ=MONTHLY;BYDAY=-1FR",
            "FREQ=MONTHLY;BYDAY=+2TU",
            "FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1",
            "FREQ=WEEKLY;UNTIL=20240401T000000Z",
            "FREQ=YEARLY;BYMONTH=3;BYMONTHDAY=5",
            "FREQ=YEARLY;BYWEEKNO=20;BYDAY=MO",
            "FREQ=DAILY;INTERVAL=2;BYHOUR=9;BYMINUTE=30",
            "FREQ=WEEKLY;WKST=SU;BYDAY=SU,SA",
        )
        for (rule in good) assertNotNull("expected $rule to pass", insertEvent(appSeriesValues(rule)))
    }

    @Test
    fun `an all-day series rule ends on a date and has no times of day`() {
        assertNotNull(insertEvent(appSeriesValues("FREQ=DAILY;UNTIL=20240401", duration = "P1D", allDay = true)))
        assertViolation { insertEvent(appSeriesValues("FREQ=DAILY;UNTIL=20240401T000000Z", duration = "P1D", allDay = true)) }
        assertViolation { insertEvent(appSeriesValues("FREQ=DAILY;BYHOUR=9", duration = "P1D", allDay = true)) }
    }

    @Test
    fun `a duration the app writes follows RFC 5545`() {
        for (bad in listOf("P3600S", "PT1H0S", "PT", "P", "PT-30M", "P1DT1H5S", "1H", "P1Y")) {
            assertThrows("expected a violation for $bad", CalendarContractViolation::class.java) {
                insertEvent(appSeriesValues("FREQ=WEEKLY;COUNT=3", duration = bad))
            }
        }
        for (good in listOf("PT1H30M", "PT0M", "PT45M", "PT1H", "P1D", "P1W", "P1DT2H", "-PT15M", "PT1H30M10S", "pt1h")) {
            assertNotNull("expected $good to pass", insertEvent(appSeriesValues("FREQ=WEEKLY;COUNT=3", duration = good)))
        }
    }

    @Test
    fun `an app update that keeps another app's stored rule is not blamed for it`() {
        val id = provider.insertRow(
            SqliteCalendarProvider.EVENTS,
            Events.CALENDAR_ID to CAL, Events.TITLE to "Standup", Events.DTSTART to T0, Events.DURATION to "P3600S",
            Events.RRULE to "COUNT=3;FREQ=WEEKLY", Events.EVENT_TIMEZONE to "UTC", Events._SYNC_ID to "foreign",
        )
        // Each update carries the start and rule, so only the rule each assertion names can object.
        val keep = values(Events.TITLE to "Renamed", Events.DTSTART to T0, Events.RRULE to "COUNT=3;FREQ=WEEKLY", Events.DURATION to "P3600S")

        assertEquals(1, context.contentResolver.update(eventUri(id), keep, null, null))
        val badRule = assertViolation {
            context.contentResolver.update(
                eventUri(id), values(Events.DTSTART to T0, Events.RRULE to "FREQ=WEEKLY;COUNT=3;UNTIL=20240401T000000Z"), null, null,
            )
        }
        assertTrue(badRule.message!!.contains("RFC 5545 section 3.3.10"))
        val badDuration = assertViolation {
            context.contentResolver.update(
                eventUri(id), values(Events.DTSTART to T0, Events.RRULE to "COUNT=3;FREQ=WEEKLY", Events.DURATION to "PT1H0S"), null, null,
            )
        }
        assertTrue(badDuration.message!!.contains("RFC 5545 section 3.3.6"))
    }

    // ---- a series update must let the platform re-expand it ----

    private fun assertStaleSeriesUpdate(block: () -> Unit) {
        val violation = assertViolation(block)
        assertTrue(violation.message, violation.message!!.contains(SqliteCalendarProvider.STALE_SERIES_UPDATE))
    }

    @Test
    fun `an app update that ends a series with only its rule is refused, as it leaves the old occurrences showing`() {
        val id = series(rrule = "FREQ=WEEKLY")

        assertStaleSeriesUpdate {
            context.contentResolver.update(eventUri(id), values(Events.RRULE to "FREQ=WEEKLY;UNTIL=20240320T000000Z"), null, null)
        }
        assertEquals("FREQ=WEEKLY", provider.row(SqliteCalendarProvider.EVENTS, id)!![Events.RRULE])
    }

    @Test
    fun `an app update of a series' times or exclusions without its start and rule is refused`() {
        val id = series(rrule = "FREQ=WEEKLY")

        assertStaleSeriesUpdate { context.contentResolver.update(eventUri(id), values(Events.DURATION to "PT2H"), null, null) }
        assertStaleSeriesUpdate { context.contentResolver.update(eventUri(id), values(Events.EXDATE to "20240312T150000Z"), null, null) }
        assertStaleSeriesUpdate { context.contentResolver.update(eventUri(id), values(Events.DTSTART to T0 + HOUR), null, null) }
        assertStaleSeriesUpdate {
            context.contentResolver.update(eventUri(id), values(Events.RRULE to "FREQ=DAILY", Events.DURATION to "PT2H"), null, null)
        }
    }

    @Test
    fun `an app update of a series that carries its start and rule is accepted`() {
        val id = series(rrule = "FREQ=WEEKLY")

        val ended = values(Events.DTSTART to T0, Events.RRULE to "FREQ=WEEKLY;UNTIL=20240320T000000Z", Events.DURATION to "PT1H")

        assertEquals(1, context.contentResolver.update(eventUri(id), ended, null, null))
        assertEquals("FREQ=WEEKLY;UNTIL=20240320T000000Z", provider.row(SqliteCalendarProvider.EVENTS, id)!![Events.RRULE])
    }

    @Test
    fun `an app update of a series that changes neither its rule nor its times is not checked for re-expansion`() {
        val id = series(rrule = "FREQ=WEEKLY")

        assertEquals(1, context.contentResolver.update(eventUri(id), values(Events.TITLE to "Renamed"), null, null))
    }

    @Test
    fun `a sync adapter may end a series with only its rule`() {
        val id = series(rrule = "FREQ=WEEKLY")

        val uri = syncAdapterUri(eventUri(id))
        assertEquals(1, context.contentResolver.update(uri, values(Events.RRULE to "FREQ=WEEKLY;UNTIL=20240320T000000Z"), null, null))
    }

    @Test
    fun `moving an exception or a one-off event by its start alone is not a series update`() {
        val master = series(rrule = "FREQ=WEEKLY")
        val moved = exception(master, originalTime = T0 + WEEK)
        val single = oneOff()

        assertEquals(1, context.contentResolver.update(eventUri(moved), values(Events.DTSTART to T0 + WEEK + 2 * HOUR, Events.DTEND to T0 + WEEK + 3 * HOUR), null, null))
        assertEquals(1, context.contentResolver.update(eventUri(single), values(Events.DTSTART to T0 + 2 * HOUR, Events.DTEND to T0 + 3 * HOUR), null, null))
    }

    @Test
    fun `an attendee the app writes carries every documented field`() {
        val id = oneOff()

        assertViolation { context.contentResolver.insert(Attendees.CONTENT_URI, attendeeValues(id).apply { remove(Attendees.ATTENDEE_TYPE) }) }
        assertViolation { context.contentResolver.insert(Attendees.CONTENT_URI, attendeeValues(id).apply { remove(Attendees.ATTENDEE_STATUS) }) }
        assertViolation {
            context.contentResolver.insert(Attendees.CONTENT_URI, attendeeValues(id).apply { put(Attendees.ATTENDEE_IDENTITY, "id-1") })
        }
        assertViolation {
            context.contentResolver.insert(Attendees.CONTENT_URI, attendeeValues(id).apply { put(Attendees.ATTENDEE_ID_NAMESPACE, "ns") })
        }
        assertNotNull(
            context.contentResolver.insert(
                Attendees.CONTENT_URI,
                attendeeValues(id).apply { put(Attendees.ATTENDEE_IDENTITY, "id-1"); put(Attendees.ATTENDEE_ID_NAMESPACE, "ns") },
            ),
        )
        // The name is the one optional field: the inserts above carry none, and one may carry it.
        assertNotNull(context.contentResolver.insert(Attendees.CONTENT_URI, attendeeValues(id).apply { put(Attendees.ATTENDEE_NAME, "Guest") }))
        assertViolation { context.contentResolver.insert(Attendees.CONTENT_URI, attendeeValues(id).apply { remove(Attendees.ATTENDEE_EMAIL) }) }
        assertViolation {
            context.contentResolver.insert(Attendees.CONTENT_URI, attendeeValues(id).apply { remove(Attendees.ATTENDEE_RELATIONSHIP) })
        }
    }

    @Test
    fun `a reminder the app writes carries its minutes and method`() {
        val id = oneOff()

        assertViolation { context.contentResolver.insert(Reminders.CONTENT_URI, reminderValues(id).apply { remove(Reminders.METHOD) }) }
        assertViolation { context.contentResolver.insert(Reminders.CONTENT_URI, reminderValues(id).apply { remove(Reminders.MINUTES) }) }
        assertTrue(provider.rows(SqliteCalendarProvider.REMINDERS).isEmpty())
    }

    @Test
    fun `an app may change only a calendar's name, visibility and sync setting`() {
        val calendarUri = ContentUris.withAppendedId(Calendars.CONTENT_URI, CAL)

        // The platform accepts an app colour change; the documented contract does not.
        assertViolation { context.contentResolver.update(calendarUri, values(Calendars.CALENDAR_COLOR to 1), null, null) }
        assertEquals(
            1,
            context.contentResolver.update(
                calendarUri, values(Calendars.CALENDAR_DISPLAY_NAME to "Home", Calendars.VISIBLE to 1, Calendars.SYNC_EVENTS to 1), null, null,
            ),
        )
    }

    @Test
    fun `other apps' writes are not held to the app contract`() {
        val uri = context.contentResolver.insert(
            syncAdapterUri(Events.CONTENT_URI),
            appSeriesValues("COUNT=3;FREQ=WEEKLY", duration = "P3600S", extra = listOf(Events.EVENT_TIMEZONE to "America/New_York")),
        )

        assertNotNull(uri)
    }

    @Test
    fun `a violation names the rule it breaks`() {
        val e = assertViolation { insertEvent(appSeriesValues("FREQ=WEEKLY;COUNT=0")) }

        assertTrue(e.message!!, e.message!!.startsWith("RFC 5545 section 3.3.10: rrule 'FREQ=WEEKLY;COUNT=0'"))
    }

    @Test
    fun `a write breaking a platform rule and the contract gets the platform's refusal`() {
        assertRejectedWith("Only sync adapters may write to _sync_id") {
            insertEvent(appEventValues(Events._SYNC_ID to "s1", Events.HAS_ALARM to 1))
        }
    }

    @Test
    fun `an app update that matches no row still has its columns checked`() {
        assertViolation { context.contentResolver.update(eventUri(987_654_321L), values(Events.HAS_ALARM to 1), null, null) }
        assertViolation {
            context.contentResolver.update(ContentUris.withAppendedId(Attendees.CONTENT_URI, 987_654_321L), values(Attendees._ID to 5), null, null)
        }
    }

    @Test
    fun `an app may not write a reminder's or attendee's own id`() {
        val id = oneOff()
        val reminderUri = ContentUris.withAppendedId(Reminders.CONTENT_URI, seedReminder(id))
        val attendeeUri = ContentUris.withAppendedId(Attendees.CONTENT_URI, seedAttendee(id))

        assertViolation { context.contentResolver.update(reminderUri, values(Reminders._ID to 99), null, null) }
        assertViolation { context.contentResolver.update(attendeeUri, values(Attendees._ID to 99), null, null) }
        assertViolation { context.contentResolver.insert(Reminders.CONTENT_URI, reminderValues(id).apply { put(Reminders._ID, 99) }) }
    }

    @Test
    fun `an app calendar insert is held to the documented columns`() {
        assertViolation {
            context.contentResolver.insert(Calendars.CONTENT_URI, values(Calendars.CALENDAR_DISPLAY_NAME to "Mine", Calendars.CALENDAR_COLOR to 1))
        }
    }

    @Test
    fun `an RSVP on another app's attendee row that has an identity but no namespace is not blamed on the app`() {
        val id = oneOff()
        val attendee = provider.insertRow(
            SqliteCalendarProvider.ATTENDEES,
            Attendees.EVENT_ID to id, Attendees.ATTENDEE_EMAIL to OWNER, Attendees.ATTENDEE_IDENTITY to "id-1",
            Attendees.ATTENDEE_RELATIONSHIP to Attendees.RELATIONSHIP_ATTENDEE, Attendees.ATTENDEE_TYPE to Attendees.TYPE_REQUIRED,
            Attendees.ATTENDEE_STATUS to Attendees.ATTENDEE_STATUS_INVITED,
        )
        val uri = ContentUris.withAppendedId(Attendees.CONTENT_URI, attendee)

        assertEquals(1, context.contentResolver.update(uri, values(Attendees.ATTENDEE_STATUS to Attendees.ATTENDEE_STATUS_ACCEPTED), null, null))
        // Writing one half of the pair is the app's own write, and is checked.
        assertViolation { context.contentResolver.update(uri, values(Attendees.ATTENDEE_IDENTITY to "id-2"), null, null) }
    }

    @Test
    fun `turning a series all-day re-checks the rule it keeps`() {
        val id = provider.insertRow(
            SqliteCalendarProvider.EVENTS,
            Events.CALENDAR_ID to CAL, Events.TITLE to "Standup", Events.DTSTART to T0, Events.DURATION to "PT1H",
            Events.RRULE to "FREQ=DAILY;UNTIL=20240401T000000Z", Events.EVENT_TIMEZONE to "UTC",
        )

        // An all-day series' UNTIL must be a DATE, so the kept date-time UNTIL is the app's breach.
        assertViolation {
            context.contentResolver.update(
                eventUri(id),
                values(
                    Events.ALL_DAY to 1, Events.DTSTART to DAY0, Events.DURATION to "P1D",
                    Events.RRULE to "FREQ=DAILY;UNTIL=20240401T000000Z",
                ),
                null, null,
            )
        }
    }

    @Test
    fun `an extended property write needs the sync adapter flag and its account`() {
        val id = oneOff()
        val appUri = CalendarContract.ExtendedProperties.CONTENT_URI
        val noAccount = appUri.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true").build()

        assertRejectedWith("Only sync adapters may write using $appUri") {
            context.contentResolver.insert(appUri, extendedPropertyValues(id))
        }
        assertRejectedWith("Sync adapters must specify an account and account type: $noAccount") {
            context.contentResolver.insert(noAccount, extendedPropertyValues(id))
        }
        assertNotNull(context.contentResolver.insert(syncAdapterUri(appUri), extendedPropertyValues(id)))
    }

    companion object {
        const val CAL = 7L
        const val OFF_CAL = 8L
        const val HIDDEN_CAL = 9L
        const val OWNER = DeviceRoundTripFixture.OWNER
        const val CAL_COLOR = DeviceRoundTripFixture.WORK_COLOR
        const val MINUTE = DeviceRoundTripFixture.MINUTE
        const val HOUR = DeviceRoundTripFixture.HOUR
        const val DAY = DeviceRoundTripFixture.DAY
        const val WEEK = DeviceRoundTripFixture.WEEK
        const val T0 = DeviceRoundTripFixture.T0 // 2024-03-05T15:00:00Z
        const val DAY0 = DeviceRoundTripFixture.DAY0 // 2024-03-05T00:00:00Z
        const val DAY_START = 20240305
        const val DAY_END = 20240305
    }
}
