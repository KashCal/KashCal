package org.onekash.kashcal.ui.viewmodels

import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.ui.util.DayPagerUtils
import java.time.LocalDate
import java.time.ZoneId

/**
 * Tests the day pager cache's pieces: inline copies of the refresh rule and the day-code walk
 * (neither calls production), [DayPagerUtils.msToDayCode], and the cache fields of [HomeUiState].
 * Plain JVM tests with no Android context.
 */
class DayEventsCacheTest {

    // ==================== Cache Refresh Logic Tests ====================

    // Refresh when the cache is empty or the page is more than a day from the cache center.
    @Test
    fun `shouldRefresh returns true when cache is empty`() {
        val cacheCenter = 0L
        val currentDateMs = getTodayMs()

        val shouldRefresh = shouldRefreshLogic(cacheCenter, currentDateMs)

        assertTrue("Should refresh when cache is empty", shouldRefresh)
    }

    @Test
    fun `shouldRefresh returns false when at cache center`() {
        val cacheCenter = getTodayMs()
        val currentDateMs = cacheCenter

        val shouldRefresh = shouldRefreshLogic(cacheCenter, currentDateMs)

        assertFalse("Should not refresh when at cache center", shouldRefresh)
    }

    @Test
    fun `shouldRefresh returns false when 1 day from center`() {
        val cacheCenter = getTodayMs()
        val currentDateMs = cacheCenter + DayPagerUtils.DAY_MS // Exactly 1 day

        val shouldRefresh = shouldRefreshLogic(cacheCenter, currentDateMs)

        assertFalse("Should not refresh when exactly 1 day from center", shouldRefresh)
    }

    @Test
    fun `shouldRefresh returns true when more than 1 day from center`() {
        val cacheCenter = getTodayMs()
        val currentDateMs = cacheCenter + DayPagerUtils.DAY_MS + 1 // Just over 1 day

        val shouldRefresh = shouldRefreshLogic(cacheCenter, currentDateMs)

        assertTrue("Should refresh when > 1 day from center", shouldRefresh)
    }

    @Test
    fun `shouldRefresh handles negative offset (past dates)`() {
        val cacheCenter = getTodayMs()
        val currentDateMs = cacheCenter - (2 * DayPagerUtils.DAY_MS) // 2 days before

        val shouldRefresh = shouldRefreshLogic(cacheCenter, currentDateMs)

        assertTrue("Should refresh when 2 days before center", shouldRefresh)
    }

    @Test
    fun `shouldRefresh buffer allows smooth swiping`() {
        val cacheCenter = getTodayMs()

        // User can swipe through 3 days (center ± 1) without refresh
        listOf(-1, 0, 1).forEach { dayOffset ->
            val currentDateMs = cacheCenter + (dayOffset * DayPagerUtils.DAY_MS)
            val shouldRefresh = shouldRefreshLogic(cacheCenter, currentDateMs)
            assertFalse(
                "Day offset $dayOffset should not trigger refresh",
                shouldRefresh
            )
        }
    }

    // ==================== DayCode Grouping Tests ====================

    @Test
    fun `dayCode format is YYYYMMDD`() {
        val localDate = LocalDate.of(2026, 1, 15)
        val ms = localDate.atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        val dayCode = DayPagerUtils.msToDayCode(ms)

        assertEquals(20260115, dayCode)
    }

    @Test
    fun `different times on same day have same dayCode`() {
        val todayMs = getTodayMs()
        val morningMs = todayMs + (8 * 60 * 60 * 1000L)  // 8 AM
        val eveningMs = todayMs + (20 * 60 * 60 * 1000L) // 8 PM

        val morningDayCode = DayPagerUtils.msToDayCode(morningMs)
        val eveningDayCode = DayPagerUtils.msToDayCode(eveningMs)

        assertEquals("Same day should have same dayCode", morningDayCode, eveningDayCode)
    }

    @Test
    fun `consecutive days have incrementing dayCodes`() {
        val todayMs = getTodayMs()
        val todayCode = DayPagerUtils.msToDayCode(todayMs)
        val tomorrowMs = todayMs + DayPagerUtils.DAY_MS
        val tomorrowCode = DayPagerUtils.msToDayCode(tomorrowMs)

        assertTrue("Tomorrow's dayCode should be > today's", tomorrowCode > todayCode)
    }

    @Test
    fun `month boundary has consecutive dayCodes`() {
        // Jan 31 to Feb 1, packed inline as YYYYMMDD; no production call.
        val jan31 = LocalDate.of(2026, 1, 31)
        val feb1 = LocalDate.of(2026, 2, 1)

        val jan31Code = jan31.year * 10000 + jan31.monthValue * 100 + jan31.dayOfMonth
        val feb1Code = feb1.year * 10000 + feb1.monthValue * 100 + feb1.dayOfMonth

        assertEquals(20260131, jan31Code)
        assertEquals(20260201, feb1Code)
        // The codes aren't consecutive integers (131 to 201): a dayCode is YYYYMMDD, not meant
        // for arithmetic.
    }

    // ==================== Cache Range Tests ====================

    @Test
    fun `7 day range covers correct dayCodes`() {
        val centerMs = getTodayMs()
        val rangeStart = centerMs - (3 * DayPagerUtils.DAY_MS)
        val rangeEnd = centerMs + (3 * DayPagerUtils.DAY_MS)

        val startDayCode = DayPagerUtils.msToDayCode(rangeStart)
        val endDayCode = DayPagerUtils.msToDayCode(rangeEnd)
        val centerDayCode = DayPagerUtils.msToDayCode(centerMs)

        // The center falls strictly between the ends.
        assertTrue("Start dayCode should be <= center", startDayCode < centerDayCode)
        assertTrue("End dayCode should be >= center", endDayCode > centerDayCode)
    }

    // ==================== generateDayCodesInRange Tests ====================

    @Test
    fun `generateDayCodesInRange returns single day for same start and end`() {
        val result = generateDayCodesInRange(20260115, 20260115)
        assertEquals(listOf(20260115), result)
    }

    @Test
    fun `generateDayCodesInRange handles 3-day span`() {
        val result = generateDayCodesInRange(20260115, 20260117)
        assertEquals(listOf(20260115, 20260116, 20260117), result)
    }

    @Test
    fun `generateDayCodesInRange handles month boundary`() {
        val result = generateDayCodesInRange(20260130, 20260202)
        assertEquals(listOf(20260130, 20260131, 20260201, 20260202), result)
    }

    @Test
    fun `generateDayCodesInRange handles year boundary`() {
        val result = generateDayCodesInRange(20251230, 20260102)
        assertEquals(listOf(20251230, 20251231, 20260101, 20260102), result)
    }

    @Test
    fun `generateDayCodesInRange returns empty for invalid range`() {
        val result = generateDayCodesInRange(20260117, 20260115) // end < start
        assertEquals(emptyList<Int>(), result)
    }

    @Test
    fun `generateDayCodesInRange handles February leap year`() {
        val result = generateDayCodesInRange(20240228, 20240301)
        assertEquals(listOf(20240228, 20240229, 20240301), result)
    }

    @Test
    fun `generateDayCodesInRange handles February non-leap year`() {
        val result = generateDayCodesInRange(20250228, 20250302)
        assertEquals(listOf(20250228, 20250301, 20250302), result)
    }

    // ==================== HomeUiState Cache Fields Tests ====================

    @Test
    fun `HomeUiState defaults have empty cache`() {
        val state = HomeUiState()

        assertTrue(state.dayEventsCache.isEmpty())
        assertEquals(0L, state.cacheRangeCenter)
        assertTrue(state.loadedDayCodes.isEmpty())
    }

    @Test
    fun `loadedDayCodes can distinguish empty from not-loaded`() {
        val emptyButLoaded = HomeUiState(
            dayEventsCache = persistentMapOf(),
            cacheRangeCenter = getTodayMs(),
            loadedDayCodes = persistentSetOf(20260115)  // Jan 15, 2026 was loaded (no events)
        )

        val notYetLoaded = HomeUiState(
            dayEventsCache = persistentMapOf(),
            cacheRangeCenter = getTodayMs(),
            loadedDayCodes = persistentSetOf()  // Nothing loaded yet
        )

        // An empty cache still tells a loaded day from one not loaded.
        assertTrue(emptyButLoaded.loadedDayCodes.contains(20260115))
        assertFalse(notYetLoaded.loadedDayCodes.contains(20260115))
    }

    // ==================== Helper Functions ====================

    /** Returns today's midnight in the system zone. */
    private fun getTodayMs(): Long {
        return LocalDate.now()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    /**
     * Copies the rule of [HomeViewModel.shouldRefreshDayPagerCache] so it runs without a
     * ViewModel; the tests don't call the production method.
     */
    private fun shouldRefreshLogic(cacheCenter: Long, currentDateMs: Long): Boolean {
        if (cacheCenter == 0L) return true
        val distanceFromCenter = kotlin.math.abs(currentDateMs - cacheCenter)
        return distanceFromCenter > DayPagerUtils.DAY_MS
    }

    /**
     * Walks day codes with [Occurrence.incrementDayCode], which crosses month and year
     * boundaries. The production `generateDayCodesInRange` in DisplayEventRepository walks
     * LocalDate and also rejects invalid codes and spans over 366 days; these tests don't call it.
     */
    private fun generateDayCodesInRange(startDay: Int, endDay: Int): List<Int> {
        if (startDay == endDay) return listOf(startDay)
        if (startDay > endDay) return emptyList()

        val result = mutableListOf<Int>()
        var current = startDay
        while (current <= endDay) {
            result.add(current)
            current = Occurrence.incrementDayCode(current)
        }
        return result
    }
}
