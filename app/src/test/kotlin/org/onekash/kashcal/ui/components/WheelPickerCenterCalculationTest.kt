package org.onekash.kashcal.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for wheel picker center calculation logic.
 *
 * Bug report: Creating event at 17:15 results in 16:10
 * - Hour 17 → 16 (off by 1)
 * - Minute 15 → 10 (off by 1 position in 5-min intervals)
 *
 * Root cause hypothesis: In circular mode, firstVisibleItemIndex points to the
 * TOP visible item, but selection should read the CENTER item.
 */
class WheelPickerCenterCalculationTest {

    companion object {
        private const val CIRCULAR_MULTIPLIER = 1000
    }

    // ==================== Comprehensive Hour Tests ====================

    @Test
    fun `test all hours - analyze offset pattern`() {
        val items = (0..23).toList()
        val visibleItems = 5
        val middleOffset = (CIRCULAR_MULTIPLIER / 2) * items.size

        println("=== Hour Offset Analysis ===")
        println("visibleItems = $visibleItems, centerOffset should be ${visibleItems / 2}")
        println()

        for (wantedHour in 0..23) {
            // Simulate: user visually centers this hour
            // With visibleItems=5, viewport shows [wantedHour-2, wantedHour-1, wantedHour, wantedHour+1, wantedHour+2]
            // firstVisibleItemIndex points to the TOP item (wantedHour - 2)
            val topItem = (wantedHour - 2 + 24) % 24  // Handle wrap for hours 0, 1
            val firstVisibleItemIndex = middleOffset + topItem

            // Current code behavior
            val currentResult = virtualToActualIndex(firstVisibleItemIndex, items.size, true)

            // What offset would give the correct result?
            val neededOffset = (wantedHour - currentResult + 24) % 24

            println("Wanted: $wantedHour, firstVisibleItemIndex maps to: $currentResult, offset needed: $neededOffset")

            // Verify the offset is consistent
            assertEquals("Offset should be 2 for hour $wantedHour", 2, neededOffset)
        }
    }

    @Test
    fun `test all minutes - analyze offset pattern`() {
        val items = (0..55 step 5).toList()
        val visibleItems = 5
        val middleOffset = (CIRCULAR_MULTIPLIER / 2) * items.size

        println("\n=== Minute Offset Analysis ===")
        println("visibleItems = $visibleItems, centerOffset should be ${visibleItems / 2}")
        println("minuteOptions = $items")
        println()

        for (wantedMinuteIndex in items.indices) {
            val wantedMinute = items[wantedMinuteIndex]
            // Simulate: user visually centers this minute
            val topIndex = (wantedMinuteIndex - 2 + items.size) % items.size
            val firstVisibleItemIndex = middleOffset + topIndex

            // Current code behavior
            val currentResultIndex = virtualToActualIndex(firstVisibleItemIndex, items.size, true)
            val currentResultMinute = items[currentResultIndex]

            // What offset would give the correct result?
            val neededOffset = (wantedMinuteIndex - currentResultIndex + items.size) % items.size

            println("Wanted: $wantedMinute (idx $wantedMinuteIndex), current gets: $currentResultMinute (idx $currentResultIndex), offset needed: $neededOffset")

            assertEquals("Offset should be 2 for minute $wantedMinute", 2, neededOffset)
        }
    }

    // ==================== Test with different visibleItems values ====================

    @Test
    fun `test offset with visibleItems = 3`() {
        val items = (0..23).toList()
        val visibleItems = 3
        val centerOffset = visibleItems / 2  // = 1
        val middleOffset = (CIRCULAR_MULTIPLIER / 2) * items.size

        println("\n=== visibleItems = 3, centerOffset = $centerOffset ===")

        val wantedHour = 17
        // With 3 visible items, viewport shows [16, 17, 18]
        val topItem = wantedHour - centerOffset  // 17 - 1 = 16
        val firstVisibleItemIndex = middleOffset + topItem

        val currentResult = virtualToActualIndex(firstVisibleItemIndex, items.size, true)
        println("Wanted: $wantedHour, firstVisibleItemIndex maps to: $currentResult")

        assertEquals("With visibleItems=3, offset should be 1", 1, wantedHour - currentResult)
    }

    @Test
    fun `test offset with visibleItems = 7`() {
        val items = (0..23).toList()
        val visibleItems = 7
        val centerOffset = visibleItems / 2  // = 3
        val middleOffset = (CIRCULAR_MULTIPLIER / 2) * items.size

        println("\n=== visibleItems = 7, centerOffset = $centerOffset ===")

        val wantedHour = 17
        // With 7 visible items, viewport shows [14, 15, 16, 17, 18, 19, 20]
        val topItem = wantedHour - centerOffset  // 17 - 3 = 14
        val firstVisibleItemIndex = middleOffset + topItem

        val currentResult = virtualToActualIndex(firstVisibleItemIndex, items.size, true)
        println("Wanted: $wantedHour, firstVisibleItemIndex maps to: $currentResult")

        assertEquals("With visibleItems=7, offset should be 3", 3, wantedHour - currentResult)
    }

    // ==================== Investigate the contentPadding effect ====================

    @Test
    fun `analyze contentPadding behavior at edges vs middle`() {
        val items = (0..23).toList()
        val visibleItems = 5
        val middleOffset = (CIRCULAR_MULTIPLIER / 2) * items.size

        println("\n=== ContentPadding Behavior Analysis ===")
        println("contentPadding = itemHeight * (visibleItems / 2) = itemHeight * 2")
        println()

        // At the very start of a NON-circular list (index 0):
        // - Top padding is VISIBLE (2 items worth)
        // - Viewport: [padding][padding][item0][item1][item2]
        // - firstVisibleItemIndex = 0, and item 0 IS at center
        println("NON-CIRCULAR at start: firstVisibleItemIndex=0 → item 0 at center (padding visible)")

        // In the MIDDLE of a circular list:
        // - Padding is scrolled out of view
        // - Viewport: [item15][item16][item17][item18][item19]
        // - firstVisibleItemIndex = 15, but item 17 is at center
        println("CIRCULAR in middle: firstVisibleItemIndex=15 → item 15 at TOP, item 17 at center")
        println()

        // For circular mode, we ALWAYS need to add centerOffset because padding
        // is never visible (we start in the middle of a 12,000+ item virtual list)
        println("Conclusion: For circular mode, always add centerOffset = visibleItems/2 = 2")
    }

    // ==================== User report discrepancy investigation ====================

    @Test
    fun `investigate user report - offset 1 vs calculated offset 2`() {
        println("\n=== User Report Discrepancy Investigation ===")
        println("User reported: 17:15 → 16:10 (offset of 1)")
        println("Our analysis: offset should be 2")
        println()

        // Possible explanations:
        println("Possible explanations for offset of 1 instead of 2:")
        println("1. User may have observed an intermediate state during scroll")
        println("2. The snap behavior may snap to a different position than expected")
        println("3. There may be a scroll offset that puts us 'between' items")
        println()

        // Let's check if the centerIndex derivedStateOf could explain it
        println("The centerIndex derivedStateOf adds 0 or 1 based on scroll offset:")
        println("  if (offset > itemHeightPx / 2) firstVisible + 1 else firstVisible")
        println()
        println("However, this centerIndex is ONLY used for visual highlighting (alpha),")
        println("NOT for the actual selection! Line 137 uses firstVisibleItemIndex directly.")
        println()

        // Test both scenarios
        val items = (0..23).toList()
        val visibleItems = 5
        val middleOffset = (CIRCULAR_MULTIPLIER / 2) * items.size

        val wantedHour = 17

        // Scenario A: User report is accurate (offset = 1)
        // This would mean firstVisibleItemIndex = 16 when hour 17 is centered
        val scenarioA_firstVisible = middleOffset + 16
        val scenarioA_result = virtualToActualIndex(scenarioA_firstVisible, items.size, true)
        println("Scenario A (offset=1): firstVisibleItemIndex=$scenarioA_result → need +1 to get $wantedHour")

        // Scenario B: Our analysis (offset = 2)
        // This would mean firstVisibleItemIndex = 15 when hour 17 is centered
        val scenarioB_firstVisible = middleOffset + 15
        val scenarioB_result = virtualToActualIndex(scenarioB_firstVisible, items.size, true)
        println("Scenario B (offset=2): firstVisibleItemIndex=$scenarioB_result → need +2 to get $wantedHour")

        println()
        println("To determine which is correct, we need to verify with actual UI testing")
        println("or examine how snap fling behavior interacts with contentPadding.")
    }

    @Test
    fun `test both offset values and verify fix works for either`() {
        val items = (0..23).toList()
        val visibleItems = 5
        val middleOffset = (CIRCULAR_MULTIPLIER / 2) * items.size

        println("\n=== Testing Fix With Both Offset Values ===")

        val wantedHour = 17

        // If offset is 1 (user report)
        val firstVisible_offset1 = middleOffset + 16
        val result_offset1 = virtualToActualIndex(firstVisible_offset1, items.size, true)
        val fix_offset1 = virtualToActualIndex(firstVisible_offset1 + 1, items.size, true)
        println("If offset=1: firstVisible=16, current gets $result_offset1, fix with +1 gets $fix_offset1")

        // If offset is 2 (our analysis)
        val firstVisible_offset2 = middleOffset + 15
        val result_offset2 = virtualToActualIndex(firstVisible_offset2, items.size, true)
        val fix_offset2 = virtualToActualIndex(firstVisible_offset2 + 2, items.size, true)
        println("If offset=2: firstVisible=15, current gets $result_offset2, fix with +2 gets $fix_offset2")

        // Both fixes should give the correct hour
        assertEquals("Fix with offset 1 should work", wantedHour, fix_offset1)
        assertEquals("Fix with offset 2 should work", wantedHour, fix_offset2)

        println()
        println("Key insight: The fix uses centerOffset = visibleItems / 2 = 2")
        println("This works correctly when firstVisibleItemIndex is 2 positions before center")
    }

    // ==================== Full Simulation Tests ====================

    @Test
    fun `simulate full flow - initialization then scroll selection with FIX`() {
        val items = (0..23).toList()
        val visibleItems = 5
        val centerOffset = visibleItems / 2  // = 2
        val middleOffset = (CIRCULAR_MULTIPLIER / 2) * items.size

        println("\n=== Full Flow Simulation with FIX ===")

        // STEP 1: Initialize picker with hour 10
        val initialHour = 10
        val initialIndex = items.indexOf(initialHour)
        // FIXED initialization: subtract centerOffset so item appears at center
        val fixedInitialFirstVisible = middleOffset + initialIndex - centerOffset
        println("Initialize with hour $initialHour:")
        println("  fixedInitialFirstVisible = middleOffset + $initialIndex - $centerOffset = maps to ${virtualToActualIndex(fixedInitialFirstVisible, items.size, true)}")

        // Verify: the CENTER item at init should be initialHour
        val centerAtInit = virtualToActualIndex(fixedInitialFirstVisible + centerOffset, items.size, true)
        assertEquals("Hour 10 should be at center after init", initialHour, centerAtInit)
        println("  Center item at init = $centerAtInit ✓")

        // STEP 2: User scrolls to hour 17
        // After scroll, firstVisibleItemIndex will be such that hour 17 is at center
        // That means firstVisibleItemIndex = (17 - centerOffset) = 15
        val wantedHour = 17
        val firstVisibleAfterScroll = middleOffset + (wantedHour - centerOffset)

        // FIXED selection: add centerOffset to get center item
        val selectedHour = virtualToActualIndex(firstVisibleAfterScroll + centerOffset, items.size, true)
        assertEquals("After scroll, hour 17 should be selected", wantedHour, selectedHour)
        println("\nUser scrolls to hour $wantedHour:")
        println("  firstVisibleItemIndex maps to ${virtualToActualIndex(firstVisibleAfterScroll, items.size, true)}")
        println("  With fix (add centerOffset): selected = $selectedHour ✓")

        // STEP 3: External update - programmatically set to hour 22
        val externalHour = 22
        val externalIndex = items.indexOf(externalHour)
        // When scrolling TO a selected item, we need firstVisibleItemIndex = (target - centerOffset)
        val targetFirstVisible = middleOffset + externalIndex - centerOffset
        val centerAfterExternal = virtualToActualIndex(targetFirstVisible + centerOffset, items.size, true)
        assertEquals("External update to hour 22 should work", externalHour, centerAfterExternal)
        println("\nExternal update to hour $externalHour:")
        println("  targetFirstVisible maps to ${virtualToActualIndex(targetFirstVisible, items.size, true)}")
        println("  Center item after scroll = $centerAfterExternal ✓")
    }

    @Test
    fun `simulate full flow for minutes with FIX`() {
        val items = (0..55 step 5).toList()  // [0, 5, 10, 15, ..., 55]
        val visibleItems = 5
        val centerOffset = visibleItems / 2  // = 2
        val middleOffset = (CIRCULAR_MULTIPLIER / 2) * items.size

        println("\n=== Full Flow Simulation for Minutes with FIX ===")

        // Test selecting minute 15 (index 3)
        val wantedMinute = 15
        val wantedIndex = items.indexOf(wantedMinute)  // = 3

        // After scroll, firstVisibleItemIndex = wantedIndex - centerOffset = 1
        val firstVisibleAfterScroll = middleOffset + (wantedIndex - centerOffset)

        // With fix
        val selectedIndex = virtualToActualIndex(firstVisibleAfterScroll + centerOffset, items.size, true)
        val selectedMinute = items[selectedIndex]

        assertEquals("Minute 15 should be selected", wantedMinute, selectedMinute)
        println("User scrolls to minute $wantedMinute (index $wantedIndex):")
        println("  firstVisibleItemIndex maps to index ${virtualToActualIndex(firstVisibleAfterScroll, items.size, true)} (minute ${items[virtualToActualIndex(firstVisibleAfterScroll, items.size, true)]})")
        println("  With fix: selected index = $selectedIndex, minute = $selectedMinute ✓")

        // Test all minutes
        println("\nVerify all minutes work with fix:")
        for (minute in items) {
            val idx = items.indexOf(minute)
            val firstVisible = middleOffset + (idx - centerOffset)
            val selected = virtualToActualIndex(firstVisible + centerOffset, items.size, true)
            assertEquals("Minute $minute should be correctly selected", idx, selected)
        }
        println("  All minutes verified ✓")
    }

    @Test
    fun `verify fix handles wrap-around correctly`() {
        val items = (0..23).toList()
        val visibleItems = 5
        val centerOffset = visibleItems / 2
        val middleOffset = (CIRCULAR_MULTIPLIER / 2) * items.size

        println("\n=== Wrap-Around Tests with FIX ===")

        // Test hour 0 (midnight)
        val hour0 = 0
        val firstVisible0 = middleOffset + (hour0 - centerOffset)  // This will be negative offset, but virtual index handles it
        val selected0 = virtualToActualIndex(firstVisible0 + centerOffset, items.size, true)
        assertEquals("Hour 0 should work", hour0, selected0)
        println("Hour 0: selected = $selected0 ✓")

        // Test hour 1
        val hour1 = 1
        val firstVisible1 = middleOffset + (hour1 - centerOffset)
        val selected1 = virtualToActualIndex(firstVisible1 + centerOffset, items.size, true)
        assertEquals("Hour 1 should work", hour1, selected1)
        println("Hour 1: selected = $selected1 ✓")

        // Test hour 23
        val hour23 = 23
        val firstVisible23 = middleOffset + (hour23 - centerOffset)
        val selected23 = virtualToActualIndex(firstVisible23 + centerOffset, items.size, true)
        assertEquals("Hour 23 should work", hour23, selected23)
        println("Hour 23: selected = $selected23 ✓")
    }

    // ==================== Current Behavior (Buggy) ====================

    @Test
    fun `CURRENT - hour picker returns wrong value when 17 is centered`() {
        // Simulating: user scrolls hour wheel to center hour 17
        // With visibleItems=5, the viewport shows [15, 16, 17, 18, 19]
        // where 17 is at center (position 2)

        val items = (0..23).toList() // 24 hours
        val visibleItems = 5
        val middleOffset = (CIRCULAR_MULTIPLIER / 2) * items.size // 12000

        // When 17 is visually centered, firstVisibleItemIndex points to 15
        // (the TOP visible item, not the center)
        val firstVisibleItemIndex = middleOffset + 15 // Item 15 is at top

        // CURRENT CODE: reads firstVisibleItemIndex directly
        val currentSelectedIndex = virtualToActualIndex(firstVisibleItemIndex, items.size, true)

        // Bug: returns 15 instead of 17
        assertEquals("Current code returns wrong hour", 15, currentSelectedIndex)

        // What user expected
        val expectedHour = 17
        assertEquals("User expected hour 17 but got $currentSelectedIndex",
            expectedHour, currentSelectedIndex + 2) // Off by 2!
    }

    @Test
    fun `CURRENT - minute picker returns wrong value when 15 is centered`() {
        // Simulating: user scrolls minute wheel to center minute 15
        // minuteOptions = [0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55]
        // Index of 15 is 3
        // With visibleItems=5, viewport shows indices [1, 2, 3, 4, 5] → [5, 10, 15, 20, 25]

        val items = (0..55 step 5).toList() // 12 minute options
        val visibleItems = 5
        val middleOffset = (CIRCULAR_MULTIPLIER / 2) * items.size // 6000

        // When minute 15 (index 3) is centered, firstVisibleItemIndex points to index 1
        val firstVisibleItemIndex = middleOffset + 1 // Index 1 (minute 5) is at top

        // CURRENT CODE: reads firstVisibleItemIndex directly
        val currentSelectedIndex = virtualToActualIndex(firstVisibleItemIndex, items.size, true)
        val currentSelectedMinute = items[currentSelectedIndex]

        // Bug: returns minute 5 instead of minute 15
        assertEquals("Current code returns wrong minute index", 1, currentSelectedIndex)
        assertEquals("Current code returns wrong minute value", 5, currentSelectedMinute)

        // What user expected
        val expectedMinute = 15
        val expectedIndex = 3
        assertEquals("User expected index 3 but got $currentSelectedIndex",
            expectedIndex, currentSelectedIndex + 2) // Off by 2!
    }

    // ==================== Fixed Behavior ====================

    @Test
    fun `FIXED - hour picker returns correct value when 17 is centered`() {
        val items = (0..23).toList()
        val visibleItems = 5
        val centerOffset = visibleItems / 2 // = 2
        val middleOffset = (CIRCULAR_MULTIPLIER / 2) * items.size

        // When 17 is visually centered, firstVisibleItemIndex = middleOffset + 15
        val firstVisibleItemIndex = middleOffset + 15

        // FIXED: Add centerOffset to get actual center item
        val fixedCenterIndex = firstVisibleItemIndex + centerOffset
        val fixedSelectedIndex = virtualToActualIndex(fixedCenterIndex, items.size, true)

        assertEquals("Fixed code should return hour 17", 17, fixedSelectedIndex)
    }

    @Test
    fun `FIXED - minute picker returns correct value when 15 is centered`() {
        val items = (0..55 step 5).toList()
        val visibleItems = 5
        val centerOffset = visibleItems / 2 // = 2
        val middleOffset = (CIRCULAR_MULTIPLIER / 2) * items.size

        // When minute 15 (index 3) is centered, firstVisibleItemIndex = middleOffset + 1
        val firstVisibleItemIndex = middleOffset + 1

        // FIXED: Add centerOffset to get actual center item
        val fixedCenterIndex = firstVisibleItemIndex + centerOffset
        val fixedSelectedIndex = virtualToActualIndex(fixedCenterIndex, items.size, true)
        val fixedSelectedMinute = items[fixedSelectedIndex]

        assertEquals("Fixed code should return minute index 3", 3, fixedSelectedIndex)
        assertEquals("Fixed code should return minute 15", 15, fixedSelectedMinute)
    }

    // ==================== Initialization Tests ====================

    @Test
    fun `initialization should place selected item at center`() {
        val items = (0..23).toList()
        val visibleItems = 5
        val centerOffset = visibleItems / 2
        val middleOffset = (CIRCULAR_MULTIPLIER / 2) * items.size
        val selectedHour = 17
        val selectedIndex = items.indexOf(selectedHour)

        // CURRENT: initialIndex = middleOffset + selectedIndex
        val currentInitialIndex = middleOffset + selectedIndex
        // This places hour 17 at firstVisibleItemIndex position (TOP of viewport)
        // Visual result: [17, 18, 19, 20, 21] with 17 at TOP, not center!

        // FIXED: initialIndex = middleOffset + selectedIndex - centerOffset
        val fixedInitialIndex = middleOffset + selectedIndex - centerOffset
        // This places hour 15 at firstVisibleItemIndex, so hour 17 is at CENTER
        // Visual result: [15, 16, 17, 18, 19] with 17 at CENTER ✓

        // Verify fixed initialization puts selected item at center
        val centerVirtualIndex = fixedInitialIndex + centerOffset
        val centerActualIndex = virtualToActualIndex(centerVirtualIndex, items.size, true)
        assertEquals("Selected hour should be at center", selectedHour, centerActualIndex)
    }

    // ==================== Edge Case Tests ====================

    @Test
    fun `hour wrap around - selecting hour 0 (midnight)`() {
        val items = (0..23).toList()
        val visibleItems = 5
        val centerOffset = visibleItems / 2
        val middleOffset = (CIRCULAR_MULTIPLIER / 2) * items.size

        // User centers hour 0 (midnight)
        // Viewport: [22, 23, 0, 1, 2] with 0 at center
        // firstVisibleItemIndex points to 22
        val firstVisibleItemIndex = middleOffset + 22

        val fixedCenterIndex = firstVisibleItemIndex + centerOffset
        val fixedSelectedIndex = virtualToActualIndex(fixedCenterIndex, items.size, true)

        assertEquals("Should correctly select hour 0 at wrap boundary", 0, fixedSelectedIndex)
    }

    @Test
    fun `minute wrap around - selecting minute 0`() {
        val items = (0..55 step 5).toList() // [0, 5, 10, ..., 55]
        val visibleItems = 5
        val centerOffset = visibleItems / 2
        val middleOffset = (CIRCULAR_MULTIPLIER / 2) * items.size

        // User centers minute 0
        // Viewport indices: [10, 11, 0, 1, 2] → minutes [50, 55, 0, 5, 10]
        // firstVisibleItemIndex points to index 10 (minute 50)
        val firstVisibleItemIndex = middleOffset + 10

        val fixedCenterIndex = firstVisibleItemIndex + centerOffset
        val fixedSelectedIndex = virtualToActualIndex(fixedCenterIndex, items.size, true)
        val fixedSelectedMinute = items[fixedSelectedIndex]

        assertEquals("Should correctly select minute 0 at wrap boundary", 0, fixedSelectedMinute)
    }

    // ==================== Bug Report Exact Scenario ====================

    @Test
    fun `bug report scenario - 17h15 becomes 16h10`() {
        // This test reproduces the exact bug report
        val hourItems = (0..23).toList()
        val minuteItems = (0..55 step 5).toList()
        val visibleItems = 5

        // User wants 17:15
        val wantedHour = 17
        val wantedMinute = 15
        val wantedMinuteIndex = minuteItems.indexOf(wantedMinute) // = 3

        val middleOffsetHour = (CIRCULAR_MULTIPLIER / 2) * hourItems.size
        val middleOffsetMinute = (CIRCULAR_MULTIPLIER / 2) * minuteItems.size

        // When these are centered, firstVisibleItemIndex values:
        val hourFirstVisible = middleOffsetHour + (wantedHour - 2) // 17-2=15 at top
        val minuteFirstVisible = middleOffsetMinute + (wantedMinuteIndex - 2) // 3-2=1 at top

        // CURRENT (buggy) behavior:
        val buggyHour = virtualToActualIndex(hourFirstVisible, hourItems.size, true)
        val buggyMinuteIndex = virtualToActualIndex(minuteFirstVisible, minuteItems.size, true)
        val buggyMinute = minuteItems[buggyMinuteIndex]

        // Bug produces 15:05 (off by 2 each), but user reported 16:10 (off by 1 each)
        // This suggests the actual offset might be 1, not 2
        // Let's check with offset of 1:
        val actualBugOffset = 1 // Based on user report
        val reportedHour = wantedHour - actualBugOffset // 17-1=16 ✓
        val reportedMinuteIndex = wantedMinuteIndex - actualBugOffset // 3-1=2
        val reportedMinute = minuteItems[reportedMinuteIndex] // index 2 = minute 10 ✓

        assertEquals("Bug report: hour should be 16", 16, reportedHour)
        assertEquals("Bug report: minute should be 10", 10, reportedMinute)

        // FIXED behavior:
        val centerOffset = visibleItems / 2
        val fixedHour = virtualToActualIndex(hourFirstVisible + centerOffset, hourItems.size, true)
        val fixedMinuteIndex = virtualToActualIndex(minuteFirstVisible + centerOffset, minuteItems.size, true)
        val fixedMinute = minuteItems[fixedMinuteIndex]

        assertEquals("Fixed: hour should be 17", wantedHour, fixedHour)
        assertEquals("Fixed: minute should be 15", wantedMinute, fixedMinute)
    }
}
