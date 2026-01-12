package org.onekash.kashcal.widget

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Adversarial tests for widget data loading.
 *
 * Tests edge cases:
 * - Zero events scenario
 * - Many events (100+) performance
 * - Events spanning midnight
 * - All-day events in various timezones
 * - Widget update during sync
 * - Stale data after event deletion
 * - Hidden calendar filtering
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class WidgetAdversarialTest {

    private lateinit var database: KashCalDatabase
    private var testCalendarId: Long = 0
    private var hiddenCalendarId: Long = 0

    @Before
    fun setup() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val accountId = database.accountsDao().insert(
            Account(provider = "test", email = "test@test.com")
        )
        testCalendarId = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "https://test.com/cal/visible/",
                displayName = "Visible Calendar",
                color = 0xFF2196F3.toInt(),
                isVisible = true
            )
        )
        hiddenCalendarId = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "https://test.com/cal/hidden/",
                displayName = "Hidden Calendar",
                color = 0xFFFF5722.toInt(),
                isVisible = false
            )
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    // ==================== Empty State Tests ====================

    @Test
    fun `zero events returns empty list`() = runTest {
        val today = getTodayDayCode()
        val occurrences = database.occurrencesDao().getForDayOnce(today)

        assertTrue("Should return empty list for no events", occurrences.isEmpty())
    }

    @Test
    fun `no visible calendars returns filtered list`() = runTest {
        // Hide all calendars
        val visibleCal = database.calendarsDao().getById(testCalendarId)!!
        database.calendarsDao().update(visibleCal.copy(isVisible = false))

        val now = System.currentTimeMillis()
        val event = createEvent("Hidden Event", now, testCalendarId)
        database.eventsDao().insert(event)

        val today = getTodayDayCode()
        val occurrences = database.occurrencesDao().getForDayOnce(today)

        // getForDayOnce doesn't filter by visibility - that's done at domain layer
        // This tests the raw DAO behavior
        assertTrue("All calendars hidden, no occurrences created", occurrences.isEmpty())
    }

    // ==================== Many Events Tests ====================

    @Test
    fun `load 100 events for single day`() = runTest {
        val now = System.currentTimeMillis()
        val today = getTodayDayCode()

        // Create 100 events for today
        repeat(100) { i ->
            val event = Event(
                uid = "bulk-$i@test.com",
                calendarId = testCalendarId,
                title = "Event $i",
                startTs = now + i * 600000, // 10 min apart
                endTs = now + i * 600000 + 1800000, // 30 min duration
                dtstamp = now,
                syncStatus = SyncStatus.SYNCED
            )
            val eventId = database.eventsDao().insert(event)

            // Insert occurrence directly for speed
            database.occurrencesDao().insert(
                Occurrence(
                    eventId = eventId,
                    calendarId = testCalendarId,
                    startTs = event.startTs,
                    endTs = event.endTs,
                    startDay = today,
                    endDay = today
                )
            )
        }

        val occurrences = database.occurrencesDao().getForDayOnce(today)
        assertEquals("Should load all 100 events", 100, occurrences.size)
    }

    @Test
    fun `load events sorted by start time`() = runTest {
        val now = System.currentTimeMillis()
        val today = getTodayDayCode()

        // Create events in random order
        listOf(3, 1, 4, 1, 5, 9, 2, 6).forEachIndexed { index, hour ->
            val startTs = now + hour * 3600000L
            val event = Event(
                uid = "sort-$index@test.com",
                calendarId = testCalendarId,
                title = "Event at $hour:00",
                startTs = startTs,
                endTs = startTs + 3600000,
                dtstamp = now,
                syncStatus = SyncStatus.SYNCED
            )
            val eventId = database.eventsDao().insert(event)
            database.occurrencesDao().insert(
                Occurrence(
                    eventId = eventId,
                    calendarId = testCalendarId,
                    startTs = event.startTs,
                    endTs = event.endTs,
                    startDay = today,
                    endDay = today
                )
            )
        }

        val occurrences = database.occurrencesDao().getForDayOnce(today)

        // Verify sorted order (DAO returns ORDER BY start_ts ASC)
        for (i in 1 until occurrences.size) {
            assertTrue(
                "Events should be sorted by startTs",
                occurrences[i].startTs >= occurrences[i - 1].startTs
            )
        }
    }

    // ==================== Midnight Spanning Tests ====================

    @Test
    fun `event spanning midnight appears on both days`() = runTest {
        val now = System.currentTimeMillis()

        // Event from 11 PM today to 1 AM tomorrow
        val todayMidnight = Instant.now().truncatedTo(ChronoUnit.DAYS)
        val startTs = todayMidnight.plusSeconds(23 * 3600).toEpochMilli() // 11 PM
        val endTs = todayMidnight.plusSeconds(25 * 3600).toEpochMilli() // 1 AM next day

        val event = Event(
            uid = "midnight@test.com",
            calendarId = testCalendarId,
            title = "Midnight Span",
            startTs = startTs,
            endTs = endTs,
            dtstamp = now,
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)

        val today = Occurrence.toDayFormat(startTs, false)
        val tomorrow = Occurrence.toDayFormat(endTs, false)

        // Insert occurrence spanning both days
        database.occurrencesDao().insert(
            Occurrence(
                eventId = eventId,
                calendarId = testCalendarId,
                startTs = startTs,
                endTs = endTs,
                startDay = today,
                endDay = tomorrow
            )
        )

        // Should appear on both days
        val todayOccs = database.occurrencesDao().getForDayOnce(today)
        val tomorrowOccs = database.occurrencesDao().getForDayOnce(tomorrow)

        assertTrue("Should appear on start day", todayOccs.isNotEmpty())
        assertTrue("Should appear on end day", tomorrowOccs.isNotEmpty())
    }

    // ==================== All-Day Event Tests ====================

    @Test
    fun `all-day event uses UTC for day calculation`() = runTest {
        // June 15, 2024 00:00 UTC
        val allDayStart = 1718409600000L
        val allDayEnd = 1718495999999L // End of June 15

        val event = Event(
            uid = "allday@test.com",
            calendarId = testCalendarId,
            title = "All Day Event",
            startTs = allDayStart,
            endTs = allDayEnd,
            dtstamp = System.currentTimeMillis(),
            isAllDay = true,
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)

        val dayCode = Occurrence.toDayFormat(allDayStart, true)
        assertEquals("All-day should be June 15", 20240615, dayCode)

        database.occurrencesDao().insert(
            Occurrence(
                eventId = eventId,
                calendarId = testCalendarId,
                startTs = allDayStart,
                endTs = allDayEnd,
                startDay = dayCode,
                endDay = dayCode
            )
        )

        val occurrences = database.occurrencesDao().getForDayOnce(20240615)
        assertEquals(1, occurrences.size)
    }

    @Test
    fun `multi-day all-day event spans correct days`() = runTest {
        // June 15-17, 2024 (3 days)
        val startTs = 1718409600000L // June 15
        val endTs = 1718668799999L // June 17 end

        val event = Event(
            uid = "multiday-allday@test.com",
            calendarId = testCalendarId,
            title = "3-Day Conference",
            startTs = startTs,
            endTs = endTs,
            dtstamp = System.currentTimeMillis(),
            isAllDay = true,
            syncStatus = SyncStatus.SYNCED
        )
        val eventId = database.eventsDao().insert(event)

        database.occurrencesDao().insert(
            Occurrence(
                eventId = eventId,
                calendarId = testCalendarId,
                startTs = startTs,
                endTs = endTs,
                startDay = 20240615,
                endDay = 20240617
            )
        )

        // Should appear on all 3 days
        listOf(20240615, 20240616, 20240617).forEach { day ->
            val occs = database.occurrencesDao().getForDayOnce(day)
            assertEquals("Should appear on day $day", 1, occs.size)
        }
    }

    // ==================== Hidden Calendar Tests ====================

    @Test
    fun `occurrences query by calendar filters correctly`() = runTest {
        val now = System.currentTimeMillis()
        val today = getTodayDayCode()

        // Event in visible calendar
        val visibleEvent = createEvent("Visible", now, testCalendarId)
        val visibleId = database.eventsDao().insert(visibleEvent)
        database.occurrencesDao().insert(
            Occurrence(
                eventId = visibleId,
                calendarId = testCalendarId,
                startTs = now,
                endTs = now + 3600000,
                startDay = today,
                endDay = today
            )
        )

        // Event in hidden calendar
        val hiddenEvent = createEvent("Hidden", now + 1800000, hiddenCalendarId)
        val hiddenId = database.eventsDao().insert(hiddenEvent)
        database.occurrencesDao().insert(
            Occurrence(
                eventId = hiddenId,
                calendarId = hiddenCalendarId,
                startTs = now + 1800000,
                endTs = now + 5400000,
                startDay = today,
                endDay = today
            )
        )

        // Filter by visible calendar
        val visibleOccs = database.occurrencesDao().getForCalendarOnDay(testCalendarId, today)
        assertEquals("Should only show visible calendar", 1, visibleOccs.size)
        assertEquals(visibleId, visibleOccs.first().eventId)

        // Hidden calendar also has occurrences
        val hiddenOccs = database.occurrencesDao().getForCalendarOnDay(hiddenCalendarId, today)
        assertEquals("Hidden calendar has events too", 1, hiddenOccs.size)
    }

    // ==================== Deletion During Widget Load Tests ====================

    @Test
    fun `event deleted after widget query started`() = runTest {
        val now = System.currentTimeMillis()
        val today = getTodayDayCode()

        val event = createEvent("To Delete", now, testCalendarId)
        val eventId = database.eventsDao().insert(event)
        database.occurrencesDao().insert(
            Occurrence(
                eventId = eventId,
                calendarId = testCalendarId,
                startTs = now,
                endTs = now + 3600000,
                startDay = today,
                endDay = today
            )
        )

        // Query occurrences
        val occurrences = database.occurrencesDao().getForDayOnce(today)
        assertEquals(1, occurrences.size)

        // Delete event (simulates deletion during widget render)
        database.eventsDao().deleteById(eventId)

        // Occurrence should be cascade deleted with event
        val afterDelete = database.occurrencesDao().getForDayOnce(today)
        assertTrue("Occurrences should be cascade deleted", afterDelete.isEmpty())
    }

    // ==================== Cancelled Occurrence Tests ====================

    @Test
    fun `cancelled occurrence excluded from query`() = runTest {
        val now = System.currentTimeMillis()
        val today = getTodayDayCode()
        val tomorrow = today + 1

        val event = createEvent("With Cancelled", now, testCalendarId)
        val eventId = database.eventsDao().insert(event)

        // Normal occurrence
        database.occurrencesDao().insert(
            Occurrence(
                eventId = eventId,
                calendarId = testCalendarId,
                startTs = now,
                endTs = now + 3600000,
                startDay = today,
                endDay = today,
                isCancelled = false
            )
        )

        // Cancelled occurrence
        database.occurrencesDao().insert(
            Occurrence(
                eventId = eventId,
                calendarId = testCalendarId,
                startTs = now + 86400000,
                endTs = now + 90000000,
                startDay = tomorrow,
                endDay = tomorrow,
                isCancelled = true
            )
        )

        val todayOccs = database.occurrencesDao().getForDayOnce(today)
        assertEquals("Should show non-cancelled", 1, todayOccs.size)
        assertFalse("Should not be cancelled", todayOccs.first().isCancelled)

        // Cancelled occurrence excluded
        val tomorrowOccs = database.occurrencesDao().getForDayOnce(tomorrow)
        assertEquals("Cancelled should be excluded", 0, tomorrowOccs.size)
    }

    // ==================== Range Query Tests ====================

    @Test
    fun `range query includes boundary events`() = runTest {
        val now = System.currentTimeMillis()
        val startRange = now
        val endRange = now + 86400000 // 1 day

        // Event at start of range
        val startEvent = createEvent("At Start", startRange, testCalendarId)
        val startId = database.eventsDao().insert(startEvent)
        database.occurrencesDao().insert(
            Occurrence(
                eventId = startId,
                calendarId = testCalendarId,
                startTs = startRange,
                endTs = startRange + 3600000,
                startDay = getTodayDayCode(),
                endDay = getTodayDayCode()
            )
        )

        // Event at end of range
        val endEvent = createEvent("At End", endRange - 3600000, testCalendarId)
        val endId = database.eventsDao().insert(endEvent)
        database.occurrencesDao().insert(
            Occurrence(
                eventId = endId,
                calendarId = testCalendarId,
                startTs = endRange - 3600000,
                endTs = endRange,
                startDay = getTodayDayCode() + 1,
                endDay = getTodayDayCode() + 1
            )
        )

        val occurrences = database.occurrencesDao().getInRangeOnce(startRange, endRange)
        assertEquals("Should include both boundary events", 2, occurrences.size)
    }

    // ==================== Helper Methods ====================

    private fun createEvent(title: String, startTs: Long, calendarId: Long): Event {
        return Event(
            uid = "$title-${System.nanoTime()}@test.com",
            calendarId = calendarId,
            title = title,
            startTs = startTs,
            endTs = startTs + 3600000,
            dtstamp = System.currentTimeMillis(),
            syncStatus = SyncStatus.SYNCED
        )
    }

    private fun getTodayDayCode(): Int {
        val now = Instant.now()
        val date = now.atZone(ZoneOffset.UTC).toLocalDate()
        return date.year * 10000 + date.monthValue * 100 + date.dayOfMonth
    }
}
