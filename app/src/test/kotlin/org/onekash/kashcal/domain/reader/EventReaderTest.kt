package org.onekash.kashcal.domain.reader

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
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
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * Tests for EventReader - the read-only query layer.
 *
 * EventReader is critical for all UI data loading. These tests ensure:
 * - Event retrieval by various keys (id, uid, caldavUrl)
 * - Calendar visibility filtering
 * - Occurrence queries for time ranges
 * - Search functionality
 * - Exception/master event relationships
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class EventReaderTest {

    private lateinit var database: KashCalDatabase
    private lateinit var eventReader: EventReader
    private lateinit var occurrenceGenerator: OccurrenceGenerator
    private var iCloudCalendarId: Long = 0
    private var localCalendarId: Long = 0
    private var hiddenCalendarId: Long = 0

    private val defaultZone = ZoneId.of("America/New_York")

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        occurrenceGenerator = OccurrenceGenerator(database, database.occurrencesDao(), database.eventsDao())
        eventReader = EventReader(database)

        runTest {
            val iCloudAccountId = database.accountsDao().insert(
                Account(provider = "icloud", email = "test@icloud.com")
            )
            iCloudCalendarId = database.calendarsDao().insert(
                Calendar(
                    accountId = iCloudAccountId,
                    caldavUrl = "https://caldav.icloud.com/personal/",
                    displayName = "Personal",
                    color = 0xFF2196F3.toInt(),
                    isVisible = true
                )
            )
            hiddenCalendarId = database.calendarsDao().insert(
                Calendar(
                    accountId = iCloudAccountId,
                    caldavUrl = "https://caldav.icloud.com/hidden/",
                    displayName = "Hidden Calendar",
                    color = 0xFFFF5722.toInt(),
                    isVisible = false
                )
            )

            val localAccountId = database.accountsDao().insert(
                Account(provider = "local", email = "local")
            )
            localCalendarId = database.calendarsDao().insert(
                Calendar(
                    accountId = localAccountId,
                    caldavUrl = "local://default",
                    displayName = "Local",
                    color = 0xFF4CAF50.toInt(),
                    isVisible = true
                )
            )
        }
    }

    @After
    fun teardown() {
        database.close()
    }

    private suspend fun createEvent(
        calendarId: Long = iCloudCalendarId,
        title: String = "Test Event",
        startTs: Long = System.currentTimeMillis(),
        rrule: String? = null,
        syncStatus: SyncStatus = SyncStatus.SYNCED
    ): Event {
        val event = Event(
            id = 0,
            uid = "test-${System.nanoTime()}",
            calendarId = calendarId,
            title = title,
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = rrule,
            syncStatus = syncStatus
        )
        val id = database.eventsDao().insert(event)
        val created = event.copy(id = id)

        // Generate occurrences
        val rangeStart = startTs - 86400000L * 30
        val rangeEnd = startTs + 86400000L * 365
        occurrenceGenerator.generateOccurrences(created, rangeStart, rangeEnd)

        return created
    }

    // ==================== Event Retrieval Tests ====================

    @Test
    fun `getEventById returns event when exists`() = runTest {
        val event = createEvent(title = "Find Me")

        val found = eventReader.getEventById(event.id)

        assertNotNull(found)
        assertEquals("Find Me", found?.title)
    }

    @Test
    fun `getEventById returns null for non-existent event`() = runTest {
        val found = eventReader.getEventById(99999L)

        assertNull(found)
    }

    @Test
    fun `getEventsByUid returns all events with same UID`() = runTest {
        val uid = "shared-uid-${System.nanoTime()}"

        // Create master event
        val master = Event(
            id = 0,
            uid = uid,
            calendarId = iCloudCalendarId,
            title = "Master",
            startTs = System.currentTimeMillis(),
            endTs = System.currentTimeMillis() + 3600000,
            dtstamp = System.currentTimeMillis(),
            rrule = "FREQ=DAILY"
        )
        val masterId = database.eventsDao().insert(master)

        // Create exception with same UID
        val exception = Event(
            id = 0,
            uid = uid, // Same UID as master (RFC 5545)
            calendarId = iCloudCalendarId,
            title = "Exception",
            startTs = System.currentTimeMillis() + 86400000,
            endTs = System.currentTimeMillis() + 86400000 + 3600000,
            dtstamp = System.currentTimeMillis(),
            originalEventId = masterId,
            originalInstanceTime = System.currentTimeMillis() + 86400000
        )
        database.eventsDao().insert(exception)

        val events = eventReader.getEventsByUid(uid)

        assertEquals(2, events.size)
    }

    @Test
    fun `getEventByCaldavUrl finds synced event`() = runTest {
        val caldavUrl = "https://caldav.icloud.com/personal/event123.ics"
        val event = createEvent()
        database.eventsDao().update(event.copy(caldavUrl = caldavUrl))

        val found = eventReader.getEventByCaldavUrl(caldavUrl)

        assertNotNull(found)
        assertEquals(event.id, found?.id)
    }

    // ==================== Exception/Master Relationship Tests ====================

    @Test
    fun `getExceptionsForMaster returns all exceptions`() = runTest {
        val master = createEvent(title = "Master", rrule = "FREQ=DAILY")

        // Create exceptions
        for (i in 1..3) {
            val exception = Event(
                id = 0,
                uid = master.uid,
                calendarId = iCloudCalendarId,
                title = "Exception $i",
                startTs = master.startTs + (i * 86400000L),
                endTs = master.startTs + (i * 86400000L) + 3600000,
                dtstamp = System.currentTimeMillis(),
                originalEventId = master.id,
                originalInstanceTime = master.startTs + (i * 86400000L)
            )
            database.eventsDao().insert(exception)
        }

        val exceptions = eventReader.getExceptionsForMaster(master.id)

        assertEquals(3, exceptions.size)
    }

    @Test
    fun `getMasterForException returns master event`() = runTest {
        val master = createEvent(title = "Master", rrule = "FREQ=DAILY")

        val exception = Event(
            id = 0,
            uid = master.uid,
            calendarId = iCloudCalendarId,
            title = "Exception",
            startTs = master.startTs + 86400000,
            endTs = master.startTs + 86400000 + 3600000,
            dtstamp = System.currentTimeMillis(),
            originalEventId = master.id,
            originalInstanceTime = master.startTs + 86400000
        )
        val exceptionId = database.eventsDao().insert(exception)
        val savedException = exception.copy(id = exceptionId)

        val foundMaster = eventReader.getMasterForException(savedException)

        assertNotNull(foundMaster)
        assertEquals(master.id, foundMaster?.id)
        assertEquals("Master", foundMaster?.title)
    }

    @Test
    fun `getEventWithExceptions returns pair of master and exceptions`() = runTest {
        val master = createEvent(title = "Master", rrule = "FREQ=DAILY")

        // Create 2 exceptions
        for (i in 1..2) {
            val exception = Event(
                id = 0,
                uid = master.uid,
                calendarId = iCloudCalendarId,
                title = "Exception $i",
                startTs = master.startTs + (i * 86400000L),
                endTs = master.startTs + (i * 86400000L) + 3600000,
                dtstamp = System.currentTimeMillis(),
                originalEventId = master.id,
                originalInstanceTime = master.startTs + (i * 86400000L)
            )
            database.eventsDao().insert(exception)
        }

        val result = eventReader.getEventWithExceptions(master.id)

        assertNotNull(result)
        assertEquals(master.id, result?.first?.id)
        assertEquals(2, result?.second?.size)
    }

    // ==================== Calendar Tests ====================

    @Test
    fun `getAllCalendars returns all calendars`() = runTest {
        val calendars = eventReader.getAllCalendars().first()

        assertEquals(3, calendars.size)
    }

    @Test
    fun `getVisibleCalendars excludes hidden calendars`() = runTest {
        val calendars = eventReader.getVisibleCalendars().first()

        assertEquals(2, calendars.size)
        assertTrue(calendars.none { it.id == hiddenCalendarId })
    }

    @Test
    fun `getCalendarById returns correct calendar`() = runTest {
        val calendar = eventReader.getCalendarById(iCloudCalendarId)

        assertNotNull(calendar)
        assertEquals("Personal", calendar?.displayName)
    }

    @Test
    fun `setCalendarVisibility toggles visibility`() = runTest {
        // Initially visible
        var calendar = eventReader.getCalendarById(iCloudCalendarId)
        assertTrue(calendar?.isVisible == true)

        // Hide it
        eventReader.setCalendarVisibility(iCloudCalendarId, false)
        calendar = eventReader.getCalendarById(iCloudCalendarId)
        assertFalse(calendar?.isVisible == true)

        // Show it again
        eventReader.setCalendarVisibility(iCloudCalendarId, true)
        calendar = eventReader.getCalendarById(iCloudCalendarId)
        assertTrue(calendar?.isVisible == true)
    }

    @Test
    fun `getCalendarsByProvider filters correctly`() = runTest {
        val iCloudCalendars = eventReader.getCalendarsByProvider("icloud").first()
        val localCalendars = eventReader.getCalendarsByProvider("local").first()

        assertEquals(2, iCloudCalendars.size) // Personal + Hidden
        assertEquals(1, localCalendars.size)
    }

    // ==================== Occurrence Query Tests ====================

    @Test
    fun `getOccurrencesInRange returns occurrences in time window`() = runTest {
        val now = System.currentTimeMillis()
        createEvent(startTs = now)
        createEvent(startTs = now + 86400000) // Tomorrow
        createEvent(startTs = now + 86400000 * 30) // 30 days from now

        val occurrences = eventReader.getOccurrencesInRangeOnce(
            now - 3600000,
            now + 86400000 * 2 // 2 days
        )

        assertEquals(2, occurrences.size)
    }

    @Test
    fun `getVisibleOccurrencesInRange excludes hidden calendar events`() = runTest {
        val now = System.currentTimeMillis()
        createEvent(calendarId = iCloudCalendarId, startTs = now)
        createEvent(calendarId = hiddenCalendarId, startTs = now + 3600000)

        val allOccurrences = eventReader.getOccurrencesInRangeOnce(
            now - 3600000,
            now + 86400000
        )
        val visibleOccurrences = eventReader.getVisibleOccurrencesInRange(
            now - 3600000,
            now + 86400000
        ).first()

        assertEquals(2, allOccurrences.size)
        assertEquals(1, visibleOccurrences.size)
    }

    @Test
    fun `getOccurrencesForDay returns correct day's events`() = runTest {
        val today = LocalDate.now()
        val dayCode = today.year * 10000 + today.monthValue * 100 + today.dayOfMonth
        val todayStart = today.atStartOfDay(defaultZone).toInstant().toEpochMilli()

        createEvent(startTs = todayStart + 36000000) // 10 AM today
        createEvent(startTs = todayStart + 86400000 + 36000000) // 10 AM tomorrow

        val todayOccurrences = eventReader.getOccurrencesForDayOnce(dayCode)

        assertEquals(1, todayOccurrences.size)
    }

    @Test
    fun `hasEventsOnDay returns true when events exist`() = runTest {
        val today = LocalDate.now()
        val dayCode = today.year * 10000 + today.monthValue * 100 + today.dayOfMonth
        val todayStart = today.atStartOfDay(defaultZone).toInstant().toEpochMilli()

        createEvent(startTs = todayStart + 36000000)

        val hasEvents = eventReader.hasEventsOnDay(dayCode)

        assertTrue(hasEvents)
    }

    @Test
    fun `hasEventsOnDay returns false when no events`() = runTest {
        val futureDay = LocalDate.now().plusYears(10)
        val dayCode = futureDay.year * 10000 + futureDay.monthValue * 100 + futureDay.dayOfMonth

        val hasEvents = eventReader.hasEventsOnDay(dayCode)

        assertFalse(hasEvents)
    }

    // ==================== Search Tests ====================

    @Test
    fun `searchEvents finds events by title`() = runTest {
        createEvent(title = "Important Meeting")
        createEvent(title = "Doctor Appointment")
        createEvent(title = "Another Meeting")

        val results = eventReader.searchEvents("Meeting")

        assertEquals(2, results.size)
    }

    @Test
    fun `searchEvents finds events by location`() = runTest {
        val event = createEvent(title = "Conference")
        database.eventsDao().update(event.copy(location = "Room 101"))

        val results = eventReader.searchEvents("Room")

        assertEquals(1, results.size)
    }

    @Test
    fun `searchEvents is case insensitive`() = runTest {
        createEvent(title = "IMPORTANT MEETING")

        val results = eventReader.searchEvents("important")

        assertEquals(1, results.size)
    }

    // ==================== Sync Status Tests ====================

    @Test
    fun `getPendingSyncEvents returns events needing sync`() = runTest {
        createEvent(title = "Synced", syncStatus = SyncStatus.SYNCED)
        createEvent(title = "Pending Create", syncStatus = SyncStatus.PENDING_CREATE)
        createEvent(title = "Pending Update", syncStatus = SyncStatus.PENDING_UPDATE)

        val pendingEvents = eventReader.getPendingSyncEvents()

        assertEquals(2, pendingEvents.size)
    }

    @Test
    fun `getEventsWithSyncErrors returns failed events`() = runTest {
        val event = createEvent(syncStatus = SyncStatus.SYNCED)
        database.eventsDao().update(event.copy(
            lastSyncError = "Connection timeout"
        ))

        val errorEvents = eventReader.getEventsWithSyncErrors()

        assertEquals(1, errorEvents.size)
        assertEquals("Connection timeout", errorEvents[0].lastSyncError)
    }

    // ==================== Count/Statistics Tests ====================

    @Test
    fun `getTotalEventCount returns correct count`() = runTest {
        createEvent(title = "Event 1")
        createEvent(title = "Event 2")
        createEvent(title = "Event 3")

        val count = eventReader.getTotalEventCount()

        assertEquals(3, count)
    }

    @Test
    fun `getEventCountForCalendar filters by calendar`() = runTest {
        createEvent(calendarId = iCloudCalendarId)
        createEvent(calendarId = iCloudCalendarId)
        createEvent(calendarId = localCalendarId)

        val iCloudCount = eventReader.getEventCountForCalendar(iCloudCalendarId)
        val localCount = eventReader.getEventCountForCalendar(localCalendarId)

        assertEquals(2, iCloudCount)
        assertEquals(1, localCount)
    }

}
