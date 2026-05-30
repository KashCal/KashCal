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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class InsightsRepositoryTest {

    private lateinit var database: KashCalDatabase
    private lateinit var repository: InsightsRepository
    private lateinit var dataStore: KashCalDataStore
    private val calendarProviderRepository: CalendarProviderRepository = mockk()

    private var accountId: Long = 0
    private var calendarId1: Long = 0
    private var calendarId2: Long = 0
    private var hiddenCalendarId: Long = 0

    private val testDispatcher = StandardTestDispatcher()
    private val zone = ZoneId.of("UTC") // Robolectric uses UTC as system default

    // Monday 2026-04-13 to Sunday 2026-04-19 (Monday-start week)
    private val monday = LocalDate.of(2026, 4, 13)
    private val mondayNow = monday.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setup() = runTest(testDispatcher) {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        dataStore = KashCalDataStore(context)
        dataStore.setFirstDayOfWeek(java.util.Calendar.MONDAY)

        coEvery { calendarProviderRepository.getInstancesForDayRange(any(), any(), any(), any()) } returns emptyList()

        repository = InsightsRepository(
            occurrencesDao = database.occurrencesDao(),
            calendarsDao = database.calendarsDao(),
            calendarProviderRepository = calendarProviderRepository,
            dataStore = dataStore,
            ioDispatcher = testDispatcher
        )

        accountId = database.accountsDao().insert(
            Account(provider = AccountProvider.LOCAL, email = "test@test.com")
        )
        calendarId1 = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "https://test.com/cal1/",
                displayName = "Work",
                color = 0xFF0000FF.toInt()
            )
        )
        calendarId2 = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "https://test.com/cal2/",
                displayName = "Personal",
                color = 0xFF00FF00.toInt()
            )
        )
        hiddenCalendarId = database.calendarsDao().insert(
            Calendar(
                accountId = accountId,
                caldavUrl = "https://test.com/cal3/",
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

    // AC1: getStats() returns correct total minutes for a week with 3 timed events
    @Test
    fun `getStats returns correct total minutes for timed events`() = runTest(testDispatcher) {
        // Mon 10:00-11:00 (60 min), Tue 14:00-16:00 (120 min), Wed 09:00-10:30 (90 min)
        insertTimedEvent(calendarId1, monday, 10, 0, monday, 11, 0)
        insertTimedEvent(calendarId1, monday.plusDays(1), 14, 0, monday.plusDays(1), 16, 0)
        insertTimedEvent(calendarId2, monday.plusDays(2), 9, 0, monday.plusDays(2), 10, 30)

        val (stats, _) = repository.getStatsWithOccurrences(AnalysisPeriod.THIS_WEEK, mondayNow)

        assertEquals(270L, stats.totalMinutes) // 60 + 120 + 90
    }

    // AC2: All-day events excluded from hour totals, counted in allDayCount
    @Test
    fun `all-day events excluded from totals counted separately`() = runTest(testDispatcher) {
        insertTimedEvent(calendarId1, monday, 10, 0, monday, 11, 0) // 60 min
        insertAllDayEvent(calendarId1, monday)
        insertAllDayEvent(calendarId1, monday.plusDays(1))

        val (stats, _) = repository.getStatsWithOccurrences(AnalysisPeriod.THIS_WEEK, mondayNow)

        assertEquals(60L, stats.totalMinutes)
        assertEquals(2, stats.allDayCount)
    }

    // AC3: Multi-day events apportioned correctly at midnight boundaries
    @Test
    fun `multi-day event apportioned at midnight boundaries`() = runTest(testDispatcher) {
        // Wed 20:00 to Fri 08:00 = 36 hours total
        // Wed: 4h (20:00-00:00), Thu: 24h (full day), Fri: 8h (00:00-08:00)
        val wed = monday.plusDays(2)
        val fri = monday.plusDays(4)
        insertTimedEvent(calendarId1, wed, 20, 0, fri, 8, 0)

        val (stats, _) = repository.getStatsWithOccurrences(AnalysisPeriod.THIS_WEEK, mondayNow)

        assertEquals(36 * 60L, stats.totalMinutes)

        val wedCode = dayCode(wed)
        val thuCode = dayCode(monday.plusDays(3))
        val friCode = dayCode(fri)

        val wedMinutes = stats.dailyBreakdown.find { it.dayCode == wedCode }?.minutes ?: 0L
        val thuMinutes = stats.dailyBreakdown.find { it.dayCode == thuCode }?.minutes ?: 0L
        val friMinutes = stats.dailyBreakdown.find { it.dayCode == friCode }?.minutes ?: 0L

        assertEquals(4 * 60L, wedMinutes)
        assertEquals(24 * 60L, thuMinutes)
        assertEquals(8 * 60L, friMinutes)
    }

    // AC4: Zero-duration events excluded from totals
    @Test
    fun `zero-duration events excluded from totals`() = runTest(testDispatcher) {
        insertTimedEvent(calendarId1, monday, 10, 0, monday, 11, 0) // 60 min

        // Zero-duration event (milestone)
        val zeroStart = monday.atTime(14, 0).atZone(zone).toInstant().toEpochMilli()
        insertEventAndOccurrence(
            calendarId = calendarId1,
            startTs = zeroStart,
            endTs = zeroStart, // same as start
            isAllDay = false
        )

        val (stats, _) = repository.getStatsWithOccurrences(AnalysisPeriod.THIS_WEEK, mondayNow)

        assertEquals(60L, stats.totalMinutes)
    }

    // AC5: PENDING_DELETE events excluded; PENDING_CREATE/UPDATE included
    @Test
    fun `pending delete excluded, pending create and update included`() = runTest(testDispatcher) {
        insertTimedEvent(calendarId1, monday, 10, 0, monday, 11, 0, SyncStatus.SYNCED) // 60 min
        insertTimedEvent(calendarId1, monday, 13, 0, monday, 14, 0, SyncStatus.PENDING_CREATE) // 60 min
        insertTimedEvent(calendarId1, monday, 15, 0, monday, 16, 0, SyncStatus.PENDING_UPDATE) // 60 min
        insertTimedEvent(calendarId1, monday, 17, 0, monday, 18, 0, SyncStatus.PENDING_DELETE) // should be excluded

        val (stats, _) = repository.getStatsWithOccurrences(AnalysisPeriod.THIS_WEEK, mondayNow)

        assertEquals(180L, stats.totalMinutes) // 60 + 60 + 60, not 240
    }

    // AC6: Calendar breakdown groups by calendarId with correct colors
    @Test
    fun `calendar breakdown groups by calendar with correct colors`() = runTest(testDispatcher) {
        insertTimedEvent(calendarId1, monday, 10, 0, monday, 11, 0) // Work: 60 min
        insertTimedEvent(calendarId1, monday, 13, 0, monday, 14, 0) // Work: 60 min
        insertTimedEvent(calendarId2, monday, 15, 0, monday, 16, 30) // Personal: 90 min

        val (stats, _) = repository.getStatsWithOccurrences(AnalysisPeriod.THIS_WEEK, mondayNow)

        assertEquals(2, stats.calendarBreakdown.size)

        val work = stats.calendarBreakdown.find { it.calendarId == calendarId1 }!!
        assertEquals("Work", work.calendarName)
        assertEquals(0xFF0000FF.toInt(), work.color)
        assertEquals(120L, work.minutes)

        val personal = stats.calendarBreakdown.find { it.calendarId == calendarId2 }!!
        assertEquals("Personal", personal.calendarName)
        assertEquals(0xFF00FF00.toInt(), personal.color)
        assertEquals(90L, personal.minutes)
    }

    // AC7: Daily breakdown has 7 entries for week ordered by firstDayOfWeek
    @Test
    fun `daily breakdown has 7 entries for week`() = runTest(testDispatcher) {
        insertTimedEvent(calendarId1, monday, 10, 0, monday, 11, 0)

        val (stats, _) = repository.getStatsWithOccurrences(AnalysisPeriod.THIS_WEEK, mondayNow)

        assertEquals(7, stats.dailyBreakdown.size)
        assertEquals(dayCode(monday), stats.dailyBreakdown.first().dayCode)
        assertEquals(dayCode(monday.plusDays(6)), stats.dailyBreakdown.last().dayCode)
    }

    // AC7 (month): Daily breakdown has correct count for month
    @Test
    fun `daily breakdown has correct count for month`() = runTest(testDispatcher) {
        insertTimedEvent(calendarId1, monday, 10, 0, monday, 11, 0)

        // April 2026 has 30 days
        val (stats, _) = repository.getStatsWithOccurrences(AnalysisPeriod.THIS_MONTH, mondayNow)

        assertEquals(30, stats.dailyBreakdown.size)
    }

    // AC8: Period boundaries respect firstDayOfWeek (Sunday-start)
    @Test
    fun `period boundaries respect sunday-start week`() = runTest(testDispatcher) {
        dataStore.setFirstDayOfWeek(java.util.Calendar.SUNDAY)

        // For 2026-04-13 (Monday) with Sunday-start:
        // Week is Sun 2026-04-12 to Sat 2026-04-18
        // Insert event on Sunday 2026-04-12
        val sunday = LocalDate.of(2026, 4, 12)
        insertTimedEvent(calendarId1, sunday, 10, 0, sunday, 11, 0) // 60 min

        val (stats, _) = repository.getStatsWithOccurrences(AnalysisPeriod.THIS_WEEK, mondayNow)

        assertEquals(60L, stats.totalMinutes)
        assertEquals(7, stats.dailyBreakdown.size)
        assertEquals(dayCode(sunday), stats.dailyBreakdown.first().dayCode)
    }

    // AC9: getDelta returns formatted string or null
    @Test
    fun `getDelta returns formatted delta for this week vs last week`() = runTest(testDispatcher) {
        // This week: 2h
        insertTimedEvent(calendarId1, monday, 10, 0, monday, 12, 0) // 120 min

        // Last week: Mon 2026-04-06
        val lastMon = monday.minusWeeks(1)
        insertTimedEvent(calendarId1, lastMon, 10, 0, lastMon, 11, 0) // 60 min

        val (thisWeekStats, _) = repository.getStatsWithOccurrences(AnalysisPeriod.THIS_WEEK, mondayNow)
        val delta = repository.getDelta(AnalysisPeriod.THIS_WEEK, thisWeekStats, mondayNow)

        assertNotNull(delta)
        assertEquals("+1h", delta)
    }

    // AC9: getDelta returns null when no previous data
    @Test
    fun `getDelta returns null for last week period`() = runTest(testDispatcher) {
        insertTimedEvent(calendarId1, monday.minusWeeks(1), 10, 0, monday.minusWeeks(1), 12, 0)

        val (stats, _) = repository.getStatsWithOccurrences(AnalysisPeriod.LAST_WEEK, mondayNow)
        val delta = repository.getDelta(AnalysisPeriod.LAST_WEEK, stats, mondayNow)

        assertNull(delta) // LAST_WEEK has no "previous period" comparison
    }

    // AC10: Empty period returns PeriodStats with 0 totals
    @Test
    fun `empty period returns zero stats with daily breakdown`() = runTest(testDispatcher) {
        val (stats, _) = repository.getStatsWithOccurrences(AnalysisPeriod.THIS_WEEK, mondayNow)

        assertEquals(0L, stats.totalMinutes)
        assertEquals(0, stats.allDayCount)
        assertTrue(stats.calendarBreakdown.isEmpty())
        assertEquals(7, stats.dailyBreakdown.size)
        assertTrue(stats.dailyBreakdown.all { it.minutes == 0L })
    }

    // AC11: Hidden calendar events excluded
    @Test
    fun `hidden calendar events excluded from stats`() = runTest(testDispatcher) {
        insertTimedEvent(calendarId1, monday, 10, 0, monday, 11, 0) // 60 min, visible
        insertTimedEvent(hiddenCalendarId, monday, 13, 0, monday, 14, 0) // hidden, should be excluded

        val (stats, _) = repository.getStatsWithOccurrences(AnalysisPeriod.THIS_WEEK, mondayNow)

        assertEquals(60L, stats.totalMinutes)
        assertEquals(1, stats.calendarBreakdown.size)
    }

    // Temporal classification tests
    @Test
    fun `classifyPeriod returns PAST for last week`() = runTest(testDispatcher) {
        val classification = repository.classifyPeriod(AnalysisPeriod.LAST_WEEK, mondayNow)
        assertEquals(TemporalClass.PAST, classification)
    }

    @Test
    fun `classifyPeriod returns IN_PROGRESS for this week mid-week`() = runTest(testDispatcher) {
        val midWeek = monday.plusDays(3).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val classification = repository.classifyPeriod(AnalysisPeriod.THIS_WEEK, midWeek)
        assertEquals(TemporalClass.IN_PROGRESS, classification)
    }

    // Multi-day event clamped to period boundaries
    @Test
    fun `multi-day event clamped to period boundaries`() = runTest(testDispatcher) {
        // Event starts Sunday before the week and ends Tuesday
        val sunday = monday.minusDays(1)
        val tuesday = monday.plusDays(1)
        insertTimedEvent(calendarId1, sunday, 20, 0, tuesday, 8, 0) // Spans Sun-Tue

        val (stats, _) = repository.getStatsWithOccurrences(AnalysisPeriod.THIS_WEEK, mondayNow)

        // Only Mon 00:00-Tue 08:00 should count (24h + 8h = 32h)
        val monMinutes = stats.dailyBreakdown.find { it.dayCode == dayCode(monday) }?.minutes ?: 0L
        val tueMinutes = stats.dailyBreakdown.find { it.dayCode == dayCode(tuesday) }?.minutes ?: 0L

        assertEquals(24 * 60L, monMinutes) // Full Monday
        assertEquals(8 * 60L, tueMinutes) // Tue 00:00-08:00
        assertEquals(32 * 60L, stats.totalMinutes)
    }

    // formatMinutesShort tests
    @Test
    fun `formatMinutesShort formats correctly`() {
        assertEquals("0m", InsightsRepository.formatMinutesShort(0))
        assertEquals("30m", InsightsRepository.formatMinutesShort(30))
        assertEquals("1h", InsightsRepository.formatMinutesShort(60))
        assertEquals("1h 30m", InsightsRepository.formatMinutesShort(90))
        assertEquals("32h 15m", InsightsRepository.formatMinutesShort(1935))
    }

    // ==================== Device Calendar Integration ====================

    @Test
    fun `device calendar events included in totalMinutes when enabled`() = runTest(testDispatcher) {
        insertTimedEvent(calendarId1, monday, 10, 0, monday, 11, 0) // Room: 60 min

        dataStore.setDeviceCalendarsEnabled(true)
        dataStore.setEnabledDeviceCalendarIds(setOf(100L))

        val deviceInstance = buildDeviceInstance(
            calendarId = 100L,
            calendarName = "Google",
            color = 0xFFFF0000.toInt(),
            startDate = monday, startHour = 14, startMin = 0,
            endDate = monday, endHour = 15, endMin = 0
        )
        coEvery {
            calendarProviderRepository.getInstancesForDayRange(any(), any(), eq(setOf(100L)), any())
        } returns listOf(deviceInstance)

        val (stats, occurrences) = repository.getStatsWithOccurrences(AnalysisPeriod.THIS_WEEK, mondayNow)

        assertEquals(120L, stats.totalMinutes) // 60 Room + 60 device
        assertTrue(occurrences.size >= 2)
    }

    @Test
    fun `device calendar events appear in calendarBreakdown with name and color`() = runTest(testDispatcher) {
        dataStore.setDeviceCalendarsEnabled(true)
        dataStore.setEnabledDeviceCalendarIds(setOf(100L))

        val deviceInstance = buildDeviceInstance(
            calendarId = 100L,
            calendarName = "Google",
            color = 0xFFFF0000.toInt(),
            startDate = monday, startHour = 10, startMin = 0,
            endDate = monday, endHour = 11, endMin = 0
        )
        coEvery {
            calendarProviderRepository.getInstancesForDayRange(any(), any(), eq(setOf(100L)), any())
        } returns listOf(deviceInstance)

        val (stats, _) = repository.getStatsWithOccurrences(AnalysisPeriod.THIS_WEEK, mondayNow)

        val deviceCal = stats.calendarBreakdown.find { it.calendarId == -100L }
        assertNotNull(deviceCal)
        assertEquals("Google", deviceCal!!.calendarName)
        assertEquals(0xFFFF0000.toInt(), deviceCal.color)
        assertEquals(60L, deviceCal.minutes)
    }

    @Test
    fun `device calendars disabled returns room-only data`() = runTest(testDispatcher) {
        insertTimedEvent(calendarId1, monday, 10, 0, monday, 11, 0) // Room: 60 min

        // Device calendars explicitly disabled (default)
        val (stats, _) = repository.getStatsWithOccurrences(AnalysisPeriod.THIS_WEEK, mondayNow)

        assertEquals(60L, stats.totalMinutes)
    }

    @Test
    fun `hidden device calendars excluded from insights`() = runTest(testDispatcher) {
        dataStore.setDeviceCalendarsEnabled(true)
        dataStore.setEnabledDeviceCalendarIds(setOf(100L, 200L))
        dataStore.setHiddenDeviceCalendarIds(setOf(200L))

        val visibleInstance = buildDeviceInstance(
            calendarId = 100L, calendarName = "Visible",
            color = 0xFF00FF00.toInt(),
            startDate = monday, startHour = 10, startMin = 0,
            endDate = monday, endHour = 11, endMin = 0
        )
        coEvery {
            calendarProviderRepository.getInstancesForDayRange(any(), any(), eq(setOf(100L)), any())
        } returns listOf(visibleInstance)

        val (stats, _) = repository.getStatsWithOccurrences(AnalysisPeriod.THIS_WEEK, mondayNow)

        assertEquals(60L, stats.totalMinutes)
        assertEquals(1, stats.calendarBreakdown.size)
        assertEquals("Visible", stats.calendarBreakdown.first().calendarName)
    }

    @Test
    fun `SecurityException falls back to room-only`() = runTest(testDispatcher) {
        insertTimedEvent(calendarId1, monday, 10, 0, monday, 11, 0) // Room: 60 min

        dataStore.setDeviceCalendarsEnabled(true)
        dataStore.setEnabledDeviceCalendarIds(setOf(100L))

        coEvery {
            calendarProviderRepository.getInstancesForDayRange(any(), any(), any(), any())
        } throws SecurityException("Calendar permission revoked")

        val (stats, _) = repository.getStatsWithOccurrences(AnalysisPeriod.THIS_WEEK, mondayNow)

        assertEquals(60L, stats.totalMinutes) // Room-only fallback
    }

    @Test
    fun `all-day device events counted in allDayCount not totalMinutes`() = runTest(testDispatcher) {
        dataStore.setDeviceCalendarsEnabled(true)
        dataStore.setEnabledDeviceCalendarIds(setOf(100L))

        val allDayInstance = buildDeviceInstance(
            calendarId = 100L, calendarName = "Google",
            color = 0xFFFF0000.toInt(),
            startDate = monday, startHour = 0, startMin = 0,
            endDate = monday.plusDays(1), endHour = 0, endMin = 0,
            isAllDay = true
        )
        coEvery {
            calendarProviderRepository.getInstancesForDayRange(any(), any(), eq(setOf(100L)), any())
        } returns listOf(allDayInstance)

        val (stats, _) = repository.getStatsWithOccurrences(AnalysisPeriod.THIS_WEEK, mondayNow)

        assertEquals(0L, stats.totalMinutes)
        assertEquals(1, stats.allDayCount)
    }

    // ==================== Helper Functions ====================

    private suspend fun insertTimedEvent(
        calendarId: Long,
        startDate: LocalDate, startHour: Int, startMin: Int,
        endDate: LocalDate, endHour: Int, endMin: Int,
        syncStatus: SyncStatus = SyncStatus.SYNCED
    ) {
        val startTs = startDate.atTime(startHour, startMin).atZone(zone).toInstant().toEpochMilli()
        val endTs = endDate.atTime(endHour, endMin).atZone(zone).toInstant().toEpochMilli()
        insertEventAndOccurrence(calendarId, startTs, endTs, isAllDay = false, syncStatus = syncStatus)
    }

    private suspend fun insertAllDayEvent(calendarId: Long, date: LocalDate) {
        val startTs = date.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        val endTs = date.plusDays(1).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        insertEventAndOccurrence(calendarId, startTs, endTs, isAllDay = true)
    }

    private suspend fun insertEventAndOccurrence(
        calendarId: Long,
        startTs: Long,
        endTs: Long,
        isAllDay: Boolean,
        syncStatus: SyncStatus = SyncStatus.SYNCED
    ) {
        val eventId = database.eventsDao().insert(
            Event(
                uid = "test-${System.nanoTime()}@test.com",
                calendarId = calendarId,
                title = "Test Event",
                startTs = startTs,
                endTs = endTs,
                isAllDay = isAllDay,
                syncStatus = syncStatus,
                dtstamp = System.currentTimeMillis()
            )
        )

        val startDay = if (isAllDay) {
            val d = java.time.Instant.ofEpochMilli(startTs).atZone(ZoneId.of("UTC")).toLocalDate()
            d.year * 10000 + d.monthValue * 100 + d.dayOfMonth
        } else {
            val d = java.time.Instant.ofEpochMilli(startTs).atZone(zone).toLocalDate()
            d.year * 10000 + d.monthValue * 100 + d.dayOfMonth
        }
        val endDay = if (isAllDay) {
            val d = java.time.Instant.ofEpochMilli(endTs).atZone(ZoneId.of("UTC")).toLocalDate().minusDays(1)
            d.year * 10000 + d.monthValue * 100 + d.dayOfMonth
        } else {
            val d = java.time.Instant.ofEpochMilli(endTs).atZone(zone).toLocalDate()
            d.year * 10000 + d.monthValue * 100 + d.dayOfMonth
        }

        database.occurrencesDao().insert(
            Occurrence(
                eventId = eventId,
                calendarId = calendarId,
                startTs = startTs,
                endTs = endTs,
                startDay = startDay,
                endDay = endDay
            )
        )
    }

    private fun dayCode(date: LocalDate): Int =
        date.year * 10000 + date.monthValue * 100 + date.dayOfMonth

    private fun buildDeviceInstance(
        calendarId: Long,
        calendarName: String,
        color: Int,
        startDate: LocalDate, startHour: Int, startMin: Int,
        endDate: LocalDate, endHour: Int, endMin: Int,
        isAllDay: Boolean = false
    ): DeviceCalendarInstance {
        val startTs = startDate.atTime(startHour, startMin).atZone(zone).toInstant().toEpochMilli()
        val endTs = endDate.atTime(endHour, endMin).atZone(zone).toInstant().toEpochMilli()
        return DeviceCalendarInstance(
            instanceId = System.nanoTime(),
            eventId = System.nanoTime(),
            title = "Device Event",
            description = "",
            location = "",
            startTs = startTs,
            endTs = endTs,
            startDay = dayCode(startDate),
            endDay = dayCode(endDate),
            isAllDay = isAllDay,
            hasRrule = false,
            rrule = null,
            reminders = emptyList(),
            calendarId = calendarId,
            calendarDisplayName = calendarName,
            calendarColor = color,
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
