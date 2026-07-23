package org.onekash.kashcal.domain.insights

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.CalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.DeviceCalendarInstance
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.model.AccountProvider
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * Tests for InsightsRepository.getOccurrencesForRange.
 *
 * Adds an explicit-range overload that returns merged Room+device occurrences
 * for an arbitrary [startTs, endTs) window. The existing
 * getStatsWithOccurrences path remains unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class InsightsRepositoryRangeOverloadTest {

    private lateinit var database: KashCalDatabase
    private lateinit var repository: InsightsRepository
    private lateinit var dataStore: KashCalDataStore
    private val calendarProviderRepository: CalendarProviderRepository = mockk()

    private var accountId: Long = 0
    private var visibleCalendarId: Long = 0
    private var hiddenCalendarId: Long = 0

    private val testDispatcher = StandardTestDispatcher()
    private val zone: ZoneId = ZoneId.of("UTC")
    private val mon: LocalDate = LocalDate.of(2026, 5, 25)

    @Before
    fun setup() = runTest(testDispatcher) {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dataStore = KashCalDataStore(context)
        coEvery { calendarProviderRepository.getInstancesForDayRange(any(), any(), any(), any()) } returns emptyList()
        repository = InsightsRepository(
            context = context,
            occurrencesDao = database.occurrencesDao(),
            calendarsDao = database.calendarsDao(),
            calendarProviderRepository = calendarProviderRepository,
            dataStore = dataStore,
            ioDispatcher = testDispatcher
        )
        accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.LOCAL, email = "test@test.com")
        )
        visibleCalendarId = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "https://test.com/cal1/",
                displayName = "Visible",
                color = 0xFF0000FF.toInt()
            )
        )
        hiddenCalendarId = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "https://test.com/cal2/",
                displayName = "Hidden",
                color = 0xFFFF0000.toInt(),
                isVisible = false
            )
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun `getOccurrencesForRange returns Room occurrences in range`() = runTest(testDispatcher) {
        insertTimedEvent(visibleCalendarId, mon, 10, 0, mon, 11, 0)
        insertTimedEvent(visibleCalendarId, mon.plusDays(1), 14, 0, mon.plusDays(1), 15, 0)
        insertTimedEvent(visibleCalendarId, mon.plusDays(2), 9, 0, mon.plusDays(2), 10, 0)

        val startTs = mon.atStartOfDay(zone).toInstant().toEpochMilli()
        val endTs = mon.plusDays(5).atStartOfDay(zone).toInstant().toEpochMilli()

        val occurrences = repository.getOccurrencesForRange(startTs, endTs)
        assertEquals(3, occurrences.size)
    }

    @Test
    fun `getOccurrencesForRange excludes hidden calendars`() = runTest(testDispatcher) {
        insertTimedEvent(visibleCalendarId, mon, 10, 0, mon, 11, 0)
        insertTimedEvent(hiddenCalendarId, mon, 14, 0, mon, 15, 0)

        val startTs = mon.atStartOfDay(zone).toInstant().toEpochMilli()
        val endTs = mon.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val occurrences = repository.getOccurrencesForRange(startTs, endTs)
        assertEquals(1, occurrences.size)
        assertEquals(visibleCalendarId, occurrences[0].calendarId)
    }

    @Test
    fun `getOccurrencesForRange empty range returns empty list`() = runTest(testDispatcher) {
        insertTimedEvent(visibleCalendarId, mon, 10, 0, mon, 11, 0)

        // Range entirely before the event.
        val startTs = mon.minusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        val endTs = mon.minusDays(5).atStartOfDay(zone).toInstant().toEpochMilli()

        val occurrences = repository.getOccurrencesForRange(startTs, endTs)
        assertTrue(occurrences.isEmpty())
    }

    @Test
    fun `getOccurrencesForRange merges device calendar occurrences`() = runTest(testDispatcher) {
        // One Room event + one device event.
        insertTimedEvent(visibleCalendarId, mon, 10, 0, mon, 11, 0)
        dataStore.setDeviceCalendarsEnabled(true)
        dataStore.setEnabledDeviceCalendarIds(setOf(99L))
        coEvery {
            calendarProviderRepository.getInstancesForDayRange(any(), any(), any(), any())
        } returns listOf(buildDeviceInstance(99L, "Phone"))

        val startTs = mon.atStartOfDay(zone).toInstant().toEpochMilli()
        val endTs = mon.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val occurrences = repository.getOccurrencesForRange(startTs, endTs)
        assertEquals(2, occurrences.size)
    }

    @Test
    fun `getOccurrencesForRange catches SecurityException from device provider`() = runTest(testDispatcher) {
        insertTimedEvent(visibleCalendarId, mon, 10, 0, mon, 11, 0)
        dataStore.setDeviceCalendarsEnabled(true)
        dataStore.setEnabledDeviceCalendarIds(setOf(99L))
        coEvery {
            calendarProviderRepository.getInstancesForDayRange(any(), any(), any(), any())
        } throws SecurityException("revoked")

        val startTs = mon.atStartOfDay(zone).toInstant().toEpochMilli()
        val endTs = mon.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        // Returns Room-only without throwing.
        val occurrences = repository.getOccurrencesForRange(startTs, endTs)
        assertEquals(1, occurrences.size)
    }

    @Test
    fun `getOccurrencesForRange honors caller zone for device day codes`() = runTest(testDispatcher) {
        val tokyo = ZoneId.of("Asia/Tokyo")
        dataStore.setDeviceCalendarsEnabled(true)
        dataStore.setEnabledDeviceCalendarIds(setOf(99L))
        var capturedStartDay: Int = -1
        var capturedEndDay: Int = -1
        coEvery {
            calendarProviderRepository.getInstancesForDayRange(any(), any(), any(), any())
        } answers {
            capturedStartDay = firstArg()
            capturedEndDay = secondArg()
            emptyList()
        }
        val startTs = mon.atStartOfDay(tokyo).toInstant().toEpochMilli()
        val endTs = mon.plusDays(1).atStartOfDay(tokyo).toInstant().toEpochMilli()

        repository.getOccurrencesForRange(startTs, endTs, tokyo)

        // 2026-05-25 in Tokyo => day code 20260525 regardless of host TZ.
        assertEquals(20260525, capturedStartDay)
        assertEquals(20260525, capturedEndDay)
    }

    @Test
    fun `getOccurrencesForRange excludes event starting exactly at endTs`() = runTest(testDispatcher) {
        // Event starts at exactly endTs; should NOT be in result (half-open range).
        val startTs = mon.atStartOfDay(zone).toInstant().toEpochMilli()
        val endTs = mon.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        // Event at exactly the upper bound:
        insertTimedEvent(visibleCalendarId, mon.plusDays(1), 0, 0, mon.plusDays(1), 1, 0)
        // And an in-range event so we know the test isn't trivially empty:
        insertTimedEvent(visibleCalendarId, mon, 10, 0, mon, 11, 0)

        val occurrences = repository.getOccurrencesForRange(startTs, endTs)

        assertEquals(1, occurrences.size)
        assertEquals(10 * 60 * 60 * 1000L, occurrences[0].startTs - startTs)
    }

    @Test
    fun `existing getStatsWithOccurrences still works (regression)`() = runTest(testDispatcher) {
        insertTimedEvent(visibleCalendarId, mon, 10, 0, mon, 11, 0)
        val mondayNow = mon.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        dataStore.setFirstDayOfWeek(java.util.Calendar.MONDAY)
        val (stats, _) = repository.getStatsWithOccurrences(AnalysisPeriod.THIS_WEEK, mondayNow)
        assertEquals(60L, stats.totalMinutes)
    }

    // ========== Helpers ==========

    private suspend fun insertTimedEvent(
        calendarId: Long,
        startDate: LocalDate, startHour: Int, startMin: Int,
        endDate: LocalDate, endHour: Int, endMin: Int
    ) {
        val startTs = startDate.atTime(startHour, startMin).atZone(zone).toInstant().toEpochMilli()
        val endTs = endDate.atTime(endHour, endMin).atZone(zone).toInstant().toEpochMilli()
        val eventId = database.eventsDao().insert(
            Event(
                uid = "test-${System.nanoTime()}@test.com",
                calendarId = calendarId,
                title = "Test Event",
                startTs = startTs,
                endTs = endTs,
                isAllDay = false,
                syncStatus = SyncStatus.SYNCED,
                dtstamp = System.currentTimeMillis()
            )
        )
        database.occurrencesDao().insert(
            Occurrence(
                eventId = eventId,
                calendarId = calendarId,
                startTs = startTs,
                endTs = endTs,
                startDay = dayCode(startDate),
                endDay = dayCode(endDate)
            )
        )
    }

    private fun dayCode(date: LocalDate): Int =
        date.year * 10000 + date.monthValue * 100 + date.dayOfMonth

    private fun buildDeviceInstance(calId: Long, calName: String): DeviceCalendarInstance {
        val startTs = mon.atTime(15, 0).atZone(zone).toInstant().toEpochMilli()
        val endTs = mon.atTime(16, 0).atZone(zone).toInstant().toEpochMilli()
        return DeviceCalendarInstance(
            instanceId = System.nanoTime(),
            eventId = System.nanoTime(),
            title = "Device Event",
            description = "",
            location = "",
            startTs = startTs,
            endTs = endTs,
            startDay = dayCode(mon),
            endDay = dayCode(mon),
            isAllDay = false,
            hasRrule = false,
            rrule = null,
            reminders = emptyList(),
            calendarId = calId,
            calendarDisplayName = calName,
            calendarColor = 0xFF888888.toInt(),
            eventColor = null,
            status = 1,
            availability = 0,
            hasAlarm = false,
            selfAttendeeStatus = 0,
            isWritable = true,
            originalId = null,
            originalInstanceTime = null,
            timezone = "UTC",
            eventStartTs = startTs,
        )
    }
}
