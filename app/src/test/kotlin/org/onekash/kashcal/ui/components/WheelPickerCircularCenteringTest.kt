package org.onekash.kashcal.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pre-tests for the unified circular centering fix.
 *
 * Approach: Make ALL wheels circular (including AM/PM) and subtract
 * centeringOffset = visibleItems / 2 from scroll targets. This replaces
 * the unreliable contentPadding-based centering.
 *
 * Key change: effectiveCircular threshold lowered from items.size >= 3 to >= 2,
 * allowing 2-item AM/PM to use circular mode with middleOffset = 1000.
 */
class WheelPickerCircularCenteringTest {

    companion object {
        private const val CIRCULAR_MULTIPLIER = 1000
    }

    /**
     * Simulates the NEW initialIndex calculation with circular centering.
     * effectiveCircular threshold: items.size >= 2 (was 3)
     */
    private fun computeFixedInitialIndex(
        itemCount: Int,
        selectedIndex: Int,
        isCircular: Boolean,
        visibleItems: Int
    ): Int {
        val effectiveCircular = isCircular && itemCount >= 2  // NEW threshold
        val middleOffset = if (effectiveCircular) (CIRCULAR_MULTIPLIER / 2) * itemCount else 0
        val centeringOffset = if (effectiveCircular) visibleItems / 2 else 0
        return middleOffset + selectedIndex - centeringOffset
    }

    /**
     * Returns what actual item ends up at the viewport center given initialIndex.
     * For circular: center is at initialIndex + centeringOffset
     */
    private fun getCenterItem(
        itemCount: Int,
        initialIndex: Int,
        isCircular: Boolean,
        visibleItems: Int
    ): Int {
        val effectiveCircular = isCircular && itemCount >= 2
        val centeringOffset = if (effectiveCircular) visibleItems / 2 else 0
        val centerVirtualIndex = initialIndex + centeringOffset
        return virtualToActualIndex(centerVirtualIndex, itemCount, effectiveCircular)
    }

    // ==================== AM/PM Circular: No Negative Indices ====================

    @Test
    fun `AM PM circular - AM with visibleItems 3 produces valid index`() {
        val index = computeFixedInitialIndex(itemCount = 2, selectedIndex = 0, isCircular = true, visibleItems = 3)
        assertTrue("AM initialIndex=$index should be >= 0", index >= 0)
        // middleOffset=1000, selectedIndex=0, centeringOffset=1 → 999
        assertEquals(999, index)
    }

    @Test
    fun `AM PM circular - PM with visibleItems 3 produces valid index`() {
        val index = computeFixedInitialIndex(itemCount = 2, selectedIndex = 1, isCircular = true, visibleItems = 3)
        assertTrue("PM initialIndex=$index should be >= 0", index >= 0)
        // middleOffset=1000, selectedIndex=1, centeringOffset=1 → 1000
        assertEquals(1000, index)
    }

    @Test
    fun `AM PM circular - AM with visibleItems 5 produces valid index`() {
        val index = computeFixedInitialIndex(itemCount = 2, selectedIndex = 0, isCircular = true, visibleItems = 5)
        assertTrue("AM initialIndex=$index should be >= 0", index >= 0)
        // middleOffset=1000, selectedIndex=0, centeringOffset=2 → 998
        assertEquals(998, index)
    }

    @Test
    fun `AM PM circular - PM with visibleItems 5 produces valid index`() {
        val index = computeFixedInitialIndex(itemCount = 2, selectedIndex = 1, isCircular = true, visibleItems = 5)
        assertTrue("PM initialIndex=$index should be >= 0", index >= 0)
        // middleOffset=1000, selectedIndex=1, centeringOffset=2 → 999
        assertEquals(999, index)
    }

    // ==================== AM/PM Circular: Correct Center Item ====================

    @Test
    fun `AM PM circular - AM is at viewport center with visibleItems 3`() {
        val initialIndex = computeFixedInitialIndex(itemCount = 2, selectedIndex = 0, isCircular = true, visibleItems = 3)
        val centerItem = getCenterItem(itemCount = 2, initialIndex, isCircular = true, visibleItems = 3)
        assertEquals("AM (index 0) should be at viewport center", 0, centerItem)
    }

    @Test
    fun `AM PM circular - PM is at viewport center with visibleItems 3`() {
        val initialIndex = computeFixedInitialIndex(itemCount = 2, selectedIndex = 1, isCircular = true, visibleItems = 3)
        val centerItem = getCenterItem(itemCount = 2, initialIndex, isCircular = true, visibleItems = 3)
        assertEquals("PM (index 1) should be at viewport center", 1, centerItem)
    }

    // ==================== 24-Hour Mode: All Hours Center Correctly ====================

    @Test
    fun `24h mode - all hours center correctly with visibleItems 3`() {
        val itemCount = 24
        val visibleItems = 3
        for (hour in 0..23) {
            val initialIndex = computeFixedInitialIndex(itemCount, selectedIndex = hour, isCircular = true, visibleItems = visibleItems)
            val centerItem = getCenterItem(itemCount, initialIndex, isCircular = true, visibleItems = visibleItems)
            assertEquals("Hour $hour should be at center", hour, centerItem)
            assertTrue("Hour $hour: initialIndex=$initialIndex should be >= 0", initialIndex >= 0)
        }
    }

    @Test
    fun `24h mode - all hours center correctly with visibleItems 5`() {
        val itemCount = 24
        val visibleItems = 5
        for (hour in 0..23) {
            val initialIndex = computeFixedInitialIndex(itemCount, selectedIndex = hour, isCircular = true, visibleItems = visibleItems)
            val centerItem = getCenterItem(itemCount, initialIndex, isCircular = true, visibleItems = visibleItems)
            assertEquals("Hour $hour should be at center", hour, centerItem)
            assertTrue("Hour $hour: initialIndex=$initialIndex should be >= 0", initialIndex >= 0)
        }
    }

    // ==================== 12-Hour Mode: All Hours Center Correctly ====================

    @Test
    fun `12h mode - all hours center correctly with visibleItems 3`() {
        val hourOptions = (1..12).toList()
        val visibleItems = 3
        for (hour in hourOptions) {
            val selectedIndex = hourOptions.indexOf(hour)
            val initialIndex = computeFixedInitialIndex(hourOptions.size, selectedIndex, isCircular = true, visibleItems = visibleItems)
            val centerActualIndex = getCenterItem(hourOptions.size, initialIndex, isCircular = true, visibleItems = visibleItems)
            assertEquals("Hour $hour (index $selectedIndex) should be at center", selectedIndex, centerActualIndex)
            assertTrue("Hour $hour: initialIndex=$initialIndex should be >= 0", initialIndex >= 0)
        }
    }

    // ==================== Minutes: All Values Center Correctly ====================

    @Test
    fun `minutes - all values center correctly with visibleItems 3`() {
        val minuteOptions = (0..55 step 5).toList()  // 12 items
        val visibleItems = 3
        for (minute in minuteOptions) {
            val selectedIndex = minuteOptions.indexOf(minute)
            val initialIndex = computeFixedInitialIndex(minuteOptions.size, selectedIndex, isCircular = true, visibleItems = visibleItems)
            val centerActualIndex = getCenterItem(minuteOptions.size, initialIndex, isCircular = true, visibleItems = visibleItems)
            assertEquals("Minute $minute (index $selectedIndex) should be at center", selectedIndex, centerActualIndex)
        }
    }

    // ==================== Edge Recentering ====================

    @Test
    fun `edge recentering preserves center item for all configurations`() {
        data class Config(val itemCount: Int, val visibleItems: Int)
        val configs = listOf(
            Config(2, 3),   // AM/PM, visibleItems=3
            Config(2, 5),   // AM/PM, visibleItems=5
            Config(12, 3),  // 12-hour or minutes, visibleItems=3
            Config(24, 3),  // 24-hour, visibleItems=3
            Config(24, 5),  // 24-hour, visibleItems=5
        )

        for (config in configs) {
            val middleStart = (CIRCULAR_MULTIPLIER / 2) * config.itemCount
            val centeringOffset = config.visibleItems / 2
            for (actualIndex in 0 until config.itemCount) {
                val scrollTarget = middleStart + actualIndex - centeringOffset
                assertTrue(
                    "Recenter ${config}: actualIndex=$actualIndex, scrollTarget=$scrollTarget should be >= 0",
                    scrollTarget >= 0
                )
                // Verify center maps back to actualIndex
                val centerVirtualIndex = scrollTarget + centeringOffset
                val recoveredActual = virtualToActualIndex(centerVirtualIndex, config.itemCount, true)
                assertEquals(
                    "Recenter ${config}: actualIndex=$actualIndex should round-trip",
                    actualIndex, recoveredActual
                )
            }
        }
    }

    // ==================== External Selection Scroll ====================

    @Test
    fun `external scroll targets are valid for AM PM circular`() {
        val itemCount = 2
        val visibleItems = 3
        val centeringOffset = visibleItems / 2  // 1
        val middleOffset = (CIRCULAR_MULTIPLIER / 2) * itemCount  // 1000

        // Simulate: currently showing AM (index 0) at center
        // firstVisibleItemIndex = 1000 + 0 - 1 = 999
        val currentFirstVisible = middleOffset + 0 - centeringOffset

        // External update: switch to PM (index 1)
        val targetActualIndex = 1
        val targetVirtualIndex = actualToNearestVirtualIndex(
            targetActualIndex, currentFirstVisible, itemCount, isCircular = true
        )
        val scrollTarget = targetVirtualIndex - centeringOffset

        assertTrue("External scroll to PM should be >= 0, got $scrollTarget", scrollTarget >= 0)

        // Verify PM ends up at center
        val centerVirtualIndex = scrollTarget + centeringOffset
        val centerActual = virtualToActualIndex(centerVirtualIndex, itemCount, true)
        assertEquals("PM (index 1) should be at center after external scroll", 1, centerActual)
    }

    @Test
    fun `external scroll targets are valid for all 24h hours`() {
        val itemCount = 24
        val visibleItems = 3
        val centeringOffset = visibleItems / 2
        val middleOffset = (CIRCULAR_MULTIPLIER / 2) * itemCount

        for (currentHour in 0..23) {
            val currentFirstVisible = middleOffset + currentHour - centeringOffset
            for (targetHour in 0..23) {
                val targetVirtualIndex = actualToNearestVirtualIndex(
                    targetHour, currentFirstVisible, itemCount, isCircular = true
                )
                val scrollTarget = targetVirtualIndex - centeringOffset
                assertTrue(
                    "Scroll from $currentHour to $targetHour: target=$scrollTarget should be >= 0",
                    scrollTarget >= 0
                )
                val centerActual = virtualToActualIndex(scrollTarget + centeringOffset, itemCount, true)
                assertEquals(
                    "Scroll from $currentHour to $targetHour should center $targetHour",
                    targetHour, centerActual
                )
            }
        }
    }

    // ==================== Visual Layout Verification ====================

    @Test
    fun `visibleItems 3 - viewport shows correct 3 items`() {
        // With centeringOffset, the viewport shows:
        // [selectedIndex - 1] [selectedIndex] [selectedIndex + 1]
        //        top              CENTER           bottom
        val itemCount = 24
        val visibleItems = 3
        val selectedHour = 14
        val centeringOffset = visibleItems / 2  // 1
        val middleOffset = (CIRCULAR_MULTIPLIER / 2) * itemCount

        val initialIndex = middleOffset + selectedHour - centeringOffset
        // initialIndex = first visible item (top of viewport)

        val topItem = virtualToActualIndex(initialIndex, itemCount, true)
        val centerItem = virtualToActualIndex(initialIndex + 1, itemCount, true)
        val bottomItem = virtualToActualIndex(initialIndex + 2, itemCount, true)

        assertEquals("Top item should be hour 13", 13, topItem)
        assertEquals("Center item should be hour 14 (selected)", 14, centerItem)
        assertEquals("Bottom item should be hour 15", 15, bottomItem)
    }

    @Test
    fun `visibleItems 3 - AM PM viewport shows correctly`() {
        val itemCount = 2
        val visibleItems = 3
        val centeringOffset = visibleItems / 2  // 1
        val middleOffset = (CIRCULAR_MULTIPLIER / 2) * itemCount  // 1000

        // AM selected (index 0)
        val initialAM = middleOffset + 0 - centeringOffset  // 999
        val topAM = virtualToActualIndex(initialAM, itemCount, true)      // 999 % 2 = 1 → PM
        val centerAM = virtualToActualIndex(initialAM + 1, itemCount, true) // 1000 % 2 = 0 → AM
        val bottomAM = virtualToActualIndex(initialAM + 2, itemCount, true) // 1001 % 2 = 1 → PM

        assertEquals("AM selected: top should be PM (wrapped)", 1, topAM)
        assertEquals("AM selected: center should be AM", 0, centerAM)
        assertEquals("AM selected: bottom should be PM (wrapped)", 1, bottomAM)

        // PM selected (index 1)
        val initialPM = middleOffset + 1 - centeringOffset  // 1000
        val topPM = virtualToActualIndex(initialPM, itemCount, true)      // 1000 % 2 = 0 → AM
        val centerPM = virtualToActualIndex(initialPM + 1, itemCount, true) // 1001 % 2 = 1 → PM
        val bottomPM = virtualToActualIndex(initialPM + 2, itemCount, true) // 1002 % 2 = 0 → AM

        assertEquals("PM selected: top should be AM", 0, topPM)
        assertEquals("PM selected: center should be PM", 1, centerPM)
        assertEquals("PM selected: bottom should be AM", 0, bottomPM)
    }

    @Test
    fun `visibleItems 3 - wrap around at midnight`() {
        val itemCount = 24
        val visibleItems = 3
        val centeringOffset = visibleItems / 2
        val middleOffset = (CIRCULAR_MULTIPLIER / 2) * itemCount

        // Hour 0 (midnight) selected
        val initial = middleOffset + 0 - centeringOffset
        val topItem = virtualToActualIndex(initial, itemCount, true)
        val centerItem = virtualToActualIndex(initial + 1, itemCount, true)
        val bottomItem = virtualToActualIndex(initial + 2, itemCount, true)

        assertEquals("Midnight: top should be 23", 23, topItem)
        assertEquals("Midnight: center should be 0", 0, centerItem)
        assertEquals("Midnight: bottom should be 1", 1, bottomItem)
    }

    // ==================== Matrix Test: All Configurations Non-Negative ====================

    @Test
    fun `all configurations produce non-negative initialIndex`() {
        data class WheelConfig(val name: String, val itemCount: Int, val isCircular: Boolean)
        val wheels = listOf(
            WheelConfig("AM/PM", 2, true),           // NEW: circular
            WheelConfig("12h hours", 12, true),
            WheelConfig("24h hours", 24, true),
            WheelConfig("minutes (5min)", 12, true),
            WheelConfig("minutes (15min)", 4, true),
            WheelConfig("minutes (1min)", 60, true),
        )
        val visibleItemsCases = listOf(3, 5, 7)

        for (wheel in wheels) {
            for (visibleItems in visibleItemsCases) {
                for (selectedIndex in 0 until wheel.itemCount) {
                    val index = computeFixedInitialIndex(
                        wheel.itemCount, selectedIndex, wheel.isCircular, visibleItems
                    )
                    assertTrue(
                        "${wheel.name}: items=${wheel.itemCount}, visible=$visibleItems, " +
                            "selected=$selectedIndex → initialIndex=$index should be >= 0",
                        index >= 0
                    )
                }
            }
        }
    }
}
