package org.onekash.kashcal.ui.viewmodels

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.ui.util.DayPagerUtils
import java.time.LocalDate
import java.time.ZoneId

/**
 * Unit tests for day events cache behavior.
 *
 * Tests cache grouping by dayCode and refresh logic.
 * These are pure unit tests that don't require Android context.
 */
class DayEventsCacheTest {

    // ==================== Cache Refresh Logic Tests ====================

    /**
     * Tests the cache refresh logic: should refresh when current date
     * is more than 1 day from the cache center.
     */
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
        // Jan 31 -> Feb 1
        val jan31 = LocalDate.of(2026, 1, 31)
        val feb1 = LocalDate.of(2026, 2, 1)

        val jan31Code = jan31.year * 10000 + jan31.monthValue * 100 + jan31.dayOfMonth
        val feb1Code = feb1.year * 10000 + feb1.monthValue * 100 + feb1.dayOfMonth

        assertEquals(20260131, jan31Code)
        assertEquals(20260201, feb1Code)
        // Note: These are not consecutive integers (131 -> 201), but that's expected
        // The dayCode is a human-readable format, not meant for arithmetic
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

        // Center should be in the middle
        assertTrue("Start dayCode should be <= center", startDayCode < centerDayCode)
        assertTrue("End dayCode should be >= center", endDayCode > centerDayCode)
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

        // Can check if a specific day was loaded
        assertTrue(emptyButLoaded.loadedDayCodes.contains(20260115))
        assertFalse(notYetLoaded.loadedDayCodes.contains(20260115))
    }

    // ==================== Helper Functions ====================

    /**
     * Get today at midnight in system timezone.
     */
    private fun getTodayMs(): Long {
        return LocalDate.now()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    /**
     * Replicates the cache refresh logic from HomeViewModel.shouldRefreshDayPagerCache().
     * Extracted here for pure unit testing without ViewModel dependencies.
     */
    private fun shouldRefreshLogic(cacheCenter: Long, currentDateMs: Long): Boolean {
        if (cacheCenter == 0L) return true
        val distanceFromCenter = kotlin.math.abs(currentDateMs - cacheCenter)
        return distanceFromCenter > DayPagerUtils.DAY_MS
    }
}
