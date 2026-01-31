package org.onekash.kashcal.widget

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.util.DateTimeUtils
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Unit tests for WidgetDataRepository.
 *
 * Tests cover:
 * - Empty state (no events today)
 * - Event sorting (timed by start time, all-day last)
 * - Past event detection
 * - Calendar visibility filtering
 * - Exception event handling
 * - Multi-day event display
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class WidgetDataRepositoryTest {

    private lateinit var database: KashCalDatabase
    private lateinit var repository: WidgetDataRepository
    private var accountId: Long = 0
    private var calendarId: Long = 0
    private var hiddenCalendarId: Long = 0

    @Before
    fun setup() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = WidgetDataRepository(database)

        // Setup test data hierarchy
        accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.LOCAL, email = "test@test.com")
        )
        calendarId = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "https://test.com/cal/",
                displayName = "Test Calendar",
                color = 0xFF2196F3.toInt(),
                isVisible = true
            )
        )
        hiddenCalendarId = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "https://test.com/hidden/",
                displayName = "Hidden Calendar",
                color = 0xFFFF0000.toInt(),
                isVisible = false
            )
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    // ========== Empty State Tests ==========

    @Test
    fun `getTodayEvents returns empty list when no events exist`() = runTest {
        val events = repository.getTodayEvents()
        assertTrue(events.isEmpty())
    }

    @Test
    fun `getTodayEvents returns empty list when events are on other days`() = runTest {
        // Create event for tomorrow
        val tomorrow = LocalDate.now().plusDays(1)
        val tomorrowStart = tomorrow.atTime(10, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val tomorrowEnd = tomorrow.atTime(11, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val eventId = createEvent("Tomorrow Event", tomorrowStart, tomorrowEnd)
        createOccurrence(eventId, calendarId, tomorrowStart, tomorrowEnd)

        val events = repository.getTodayEvents()
        assertTrue(events.isEmpty())
    }

    // ========== Sorting Tests ==========

    @Test
    fun `getTodayEvents sorts timed events by start time`() = runTest {
        val today = LocalDate.now()

        // Create events at different times (out of order)
        val time1 = today.atTime(14, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val time2 = today.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val time3 = today.atTime(11, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val event1 = createEvent("Afternoon Meeting", time1, time1 + 3600000)
        createOccurrence(event1, calendarId, time1, time1 + 3600000)

        val event2 = createEvent("Morning Standup", time2, time2 + 1800000)
        createOccurrence(event2, calendarId, time2, time2 + 1800000)

        val event3 = createEvent("Lunch", time3, time3 + 3600000)
        createOccurrence(event3, calendarId, time3, time3 + 3600000)

        val events = repository.getTodayEvents()
        assertEquals(3, events.size)
        assertEquals("Morning Standup", events[0].title)
        assertEquals("Lunch", events[1].title)
        assertEquals("Afternoon Meeting", events[2].title)
    }

    @Test
    fun `getTodayEvents sorts all-day events after timed events`() = runTest {
        val today = LocalDate.now()

        // Create timed event
        val timedStart = today.atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val timedEnd = timedStart + 3600000

        val timedEventId = createEvent("Timed Event", timedStart, timedEnd)
        createOccurrence(timedEventId, calendarId, timedStart, timedEnd)

        // Create all-day event (midnight UTC)
        val allDayStart = today.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val allDayEnd = allDayStart + 86400000 - 1

        val allDayEventId = createEvent("All Day Event", allDayStart, allDayEnd, isAllDay = true)
        createOccurrence(allDayEventId, calendarId, allDayStart, allDayEnd)

        val events = repository.getTodayEvents()
        assertEquals(2, events.size)
        assertEquals("Timed Event", events[0].title)
        assertFalse(events[0].isAllDay)
        assertEquals("All Day Event", events[1].title)
        assertTrue(events[1].isAllDay)
    }

    // ========== Past Event Detection Tests ==========

    @Test
    fun `getTodayEvents marks past events as isPast=true`() = runTest {
        val now = System.currentTimeMillis()

        // Create event that ended 2 hours ago (definitely in the past)
        val pastEnd = now - 2 * 3600000  // 2 hours ago
        val pastStart = pastEnd - 3600000  // 3 hours ago

        // Create event that ends 2 hours from now (definitely in the future)
        val futureStart = now + 3600000  // 1 hour from now
        val futureEnd = now + 2 * 3600000  // 2 hours from now

        val today = LocalDate.now()
        val todayCode = DateTimeUtils.eventTsToDayCode(now, false)

        val pastEventId = createEvent("Past Event", pastStart, pastEnd)
        // Use fixed day code for today to ensure occurrence is found
        database.occurrencesDao().insert(
            Occurrence(
                eventId = pastEventId,
                calendarId = calendarId,
                startTs = pastStart,
                endTs = pastEnd,
                startDay = todayCode,
                endDay = todayCode
            )
        )

        val futureEventId = createEvent("Future Event", futureStart, futureEnd)
        database.occurrencesDao().insert(
            Occurrence(
                eventId = futureEventId,
                calendarId = calendarId,
                startTs = futureStart,
                endTs = futureEnd,
                startDay = todayCode,
                endDay = todayCode
            )
        )

        val events = repository.getTodayEvents()

        val pastEvent = events.find { it.title == "Past Event" }
        val futureEvent = events.find { it.title == "Future Event" }

        assertTrue("Past event should be marked as past", pastEvent?.isPast == true)
        assertFalse("Future event should not be marked as past", futureEvent?.isPast == true)
    }

    @Test
    fun `getTodayEvents all-day event today is NOT past even late in day`() = runTest {
        // Regression test for timezone bug: all-day events were grayed out at 6 PM for UTC-6 users
        // because endTs (UTC midnight) < current UTC time, even though locally it's still "today"

        val todayCode = DateTimeUtils.eventTsToDayCode(System.currentTimeMillis(), false)

        // All-day event for today - endTs is end of day in UTC
        // (simulates how all-day events are stored: UTC midnight = 23:59:59.999 UTC)
        val todayStart = java.time.LocalDate.now()
            .atStartOfDay(java.time.ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
        val todayEnd = todayStart + 24 * 3600 * 1000 - 1  // 23:59:59.999 UTC

        val allDayEventId = createEvent("All-Day Today", todayStart, todayEnd, isAllDay = true)
        database.occurrencesDao().insert(
            Occurrence(
                eventId = allDayEventId,
                calendarId = calendarId,
                startTs = todayStart,
                endTs = todayEnd,
                startDay = todayCode,
                endDay = todayCode  // Same day
            )
        )

        val events = repository.getTodayEvents()
        val allDayEvent = events.find { it.title == "All-Day Today" }

        assertNotNull("All-day event should be found", allDayEvent)
        assertFalse(
            "All-day event for today should NOT be marked as past (timezone-aware check)",
            allDayEvent?.isPast == true
        )
    }

    // ========== Calendar Visibility Tests ==========

    @Test
    fun `getTodayEvents excludes events from hidden calendars`() = runTest {
        val today = LocalDate.now()
        val startTs = today.atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endTs = startTs + 3600000

        // Create event in visible calendar
        val visibleEventId = createEvent("Visible Event", startTs, endTs)
        createOccurrence(visibleEventId, calendarId, startTs, endTs)

        // Create event in hidden calendar
        val hiddenEventId = createEvent("Hidden Event", startTs + 1800000, startTs + 5400000)
        database.eventsDao().insert(
            Event(
                uid = "hidden-event@test.com",
                calendarId = hiddenCalendarId,
                title = "Hidden Event",
                startTs = startTs + 1800000,
                endTs = startTs + 5400000,
                dtstamp = System.currentTimeMillis(),
                syncStatus = SyncStatus.SYNCED
            )
        ).also { id ->
            createOccurrence(id, hiddenCalendarId, startTs + 1800000, startTs + 5400000)
        }

        val events = repository.getTodayEvents()
        assertEquals(1, events.size)
        assertEquals("Visible Event", events[0].title)
    }

    // ========== Exception Event Tests ==========

    @Test
    fun `getTodayEvents uses exception event data when linked`() = runTest {
        val today = LocalDate.now()
        val startTs = today.atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endTs = startTs + 3600000

        // Create master event (isRecurring is computed from rrule)
        val masterEventId = database.eventsDao().insert(
            Event(
                uid = "master@test.com",
                calendarId = calendarId,
                title = "Original Title",
                startTs = startTs,
                endTs = endTs,
                dtstamp = System.currentTimeMillis(),
                rrule = "FREQ=DAILY;COUNT=5",
                syncStatus = SyncStatus.SYNCED
            )
        )

        // Create exception event with modified title
        val exceptionEventId = database.eventsDao().insert(
            Event(
                uid = "master@test.com",
                calendarId = calendarId,
                title = "Modified Title",
                startTs = startTs,
                endTs = endTs,
                dtstamp = System.currentTimeMillis(),
                originalEventId = masterEventId,
                originalInstanceTime = startTs,
                syncStatus = SyncStatus.SYNCED
            )
        )

        // Create occurrence linked to exception
        database.occurrencesDao().insert(
            Occurrence(
                eventId = masterEventId,
                calendarId = calendarId,
                startTs = startTs,
                endTs = endTs,
                startDay = DateTimeUtils.eventTsToDayCode(startTs, false),
                endDay = DateTimeUtils.eventTsToDayCode(endTs, false),
                exceptionEventId = exceptionEventId
            )
        )

        val events = repository.getTodayEvents()
        assertEquals(1, events.size)
        assertEquals("Modified Title", events[0].title)
    }

    // ========== Multi-day Event Tests ==========

    @Test
    fun `getTodayEvents includes multi-day event on middle day`() = runTest {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        val tomorrow = today.plusDays(1)

        // Create event spanning yesterday to tomorrow
        val startTs = yesterday.atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endTs = tomorrow.atTime(18, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val eventId = createEvent("Multi-day Conference", startTs, endTs)

        // Occurrence should span multiple days
        database.occurrencesDao().insert(
            Occurrence(
                eventId = eventId,
                calendarId = calendarId,
                startTs = startTs,
                endTs = endTs,
                startDay = DateTimeUtils.eventTsToDayCode(startTs, false),
                endDay = DateTimeUtils.eventTsToDayCode(endTs, false)
            )
        )

        val events = repository.getTodayEvents()
        assertEquals(1, events.size)
        assertEquals("Multi-day Conference", events[0].title)
    }

    // ========== Helper Methods ==========

    private suspend fun createEvent(
        title: String,
        startTs: Long,
        endTs: Long,
        isAllDay: Boolean = false
    ): Long {
        return database.eventsDao().insert(
            Event(
                uid = "$title-${System.nanoTime()}@test.com",
                calendarId = calendarId,
                title = title,
                startTs = startTs,
                endTs = endTs,
                isAllDay = isAllDay,
                dtstamp = System.currentTimeMillis(),
                syncStatus = SyncStatus.SYNCED
            )
        )
    }

    private suspend fun createOccurrence(
        eventId: Long,
        calendarId: Long,
        startTs: Long,
        endTs: Long
    ): Long {
        val event = database.eventsDao().getById(eventId)!!
        return database.occurrencesDao().insert(
            Occurrence(
                eventId = eventId,
                calendarId = calendarId,
                startTs = startTs,
                endTs = endTs,
                startDay = DateTimeUtils.eventTsToDayCode(startTs, event.isAllDay),
                endDay = DateTimeUtils.eventTsToDayCode(endTs, event.isAllDay)
            )
        )
    }
}
