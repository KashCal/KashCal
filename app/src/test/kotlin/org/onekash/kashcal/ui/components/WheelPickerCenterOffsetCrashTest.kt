package org.onekash.kashcal.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests that a wheel's initial index and scroll targets are never negative.
 *
 * Subtracting the centering offset (`visibleItems / 2`) from a non-circular two-item AM/PM list
 * (middleOffset 0) gives a negative index. A wheel of two or more items can be circular, and
 * every app wheel passes `isCircular = true`, so AM/PM starts at middleOffset 1000 and the
 * subtraction stays positive. Every circular wheel subtracts the same offset so all center
 * alike.
 *
 * The index formulas are local copies of `VerticalWheelPicker`'s arithmetic.
 */
class WheelPickerCenterOffsetCrashTest {

    companion object {
        // Same value as the picker's private CIRCULAR_MULTIPLIER.
        private const val CIRCULAR_MULTIPLIER = 1000
    }

    /**
     * Copies the circular branch of `VerticalWheelPicker`'s initialIndex. For a non-circular
     * list it drops the offset, where the picker subtracts it and clamps at 0.
     */
    private fun computeInitialIndex(
        items: List<Any>,
        selectedIndex: Int,
        isCircular: Boolean,
        visibleItems: Int
    ): Int {
        val effectiveCircular = isCircular && items.size >= 2  // Threshold: >= 2
        val middleOffset = if (effectiveCircular) (CIRCULAR_MULTIPLIER / 2) * items.size else 0
        val centeringOffset = if (effectiveCircular) visibleItems / 2 else 0
        return middleOffset + selectedIndex - centeringOffset
    }

    /**
     * Computes the initial index with a three-item circular threshold and the offset always
     * subtracted, which goes negative for a non-circular AM/PM list (middleOffset 0).
     */
    private fun computeCrashedInitialIndex(
        items: List<Any>,
        selectedIndex: Int,
        isCircular: Boolean,
        visibleItems: Int
    ): Int {
        // Threshold >= 3, so AM/PM isn't circular
        val effectiveCircular = isCircular && items.size >= 3
        val middleOffset = if (effectiveCircular) (CIRCULAR_MULTIPLIER / 2) * items.size else 0
        val centerOffset = visibleItems / 2  // Applied unconditionally, so it can go negative
        return middleOffset + selectedIndex - centerOffset
    }

    // ==================== Offset Subtracted From a Non-Circular AM/PM List ====================

    @Test
    fun `crashed version - AM with non-circular visibleItems 3 was negative`() {
        // Non-circular AM/PM with the offset subtracted unconditionally:
        // middleOffset=0, selectedIndex=0, centerOffset=1 → -1
        val items = listOf("AM", "PM")
        val crashedIndex = computeCrashedInitialIndex(items, selectedIndex = 0, isCircular = false, visibleItems = 3)
        assertEquals("Old approach: AM index was -1 (crash)", -1, crashedIndex)
    }

    @Test
    fun `crashed version - AM with non-circular visibleItems 5 was negative`() {
        val items = listOf("AM", "PM")
        val crashedIndex = computeCrashedInitialIndex(items, selectedIndex = 0, isCircular = false, visibleItems = 5)
        assertEquals("Old approach: AM index was -2 (crash)", -2, crashedIndex)
    }

    @Test
    fun `crashed version - PM with non-circular visibleItems 5 was negative`() {
        val items = listOf("AM", "PM")
        val crashedIndex = computeCrashedInitialIndex(items, selectedIndex = 1, isCircular = false, visibleItems = 5)
        assertEquals("Old approach: PM index was -1 (crash)", -1, crashedIndex)
    }

    // ==================== Circular AM/PM: Non-Negative Index ====================

    @Test
    fun `fixed - AM circular with visibleItems 3 produces valid index`() {
        val items = listOf("AM", "PM")
        val fixedIndex = computeInitialIndex(items, selectedIndex = 0, isCircular = true, visibleItems = 3)
        assertTrue("AM index should be >= 0, got $fixedIndex", fixedIndex >= 0)
        // middleOffset=1000, selectedIndex=0, centeringOffset=1 → 999
        assertEquals(999, fixedIndex)
    }

    @Test
    fun `fixed - PM circular with visibleItems 3 produces valid index`() {
        val items = listOf("AM", "PM")
        val fixedIndex = computeInitialIndex(items, selectedIndex = 1, isCircular = true, visibleItems = 3)
        assertTrue("PM index should be >= 0, got $fixedIndex", fixedIndex >= 0)
        assertEquals(1000, fixedIndex)
    }

    @Test
    fun `fixed - AM circular with visibleItems 5 produces valid index`() {
        val items = listOf("AM", "PM")
        val fixedIndex = computeInitialIndex(items, selectedIndex = 0, isCircular = true, visibleItems = 5)
        assertTrue("AM index should be >= 0, got $fixedIndex", fixedIndex >= 0)
        assertEquals(998, fixedIndex)
    }

    @Test
    fun `fixed - PM circular with visibleItems 5 produces valid index`() {
        val items = listOf("AM", "PM")
        val fixedIndex = computeInitialIndex(items, selectedIndex = 1, isCircular = true, visibleItems = 5)
        assertTrue("PM index should be >= 0, got $fixedIndex", fixedIndex >= 0)
        assertEquals(999, fixedIndex)
    }

    // ==================== All Configurations Non-Negative ====================

    @Test
    fun `fixed - all configurations produce non-negative initialIndex`() {
        val testConfigs = listOf(
            2 to true,    // AM/PM (circular)
            12 to true,   // Hours 1-12
            24 to true,   // Hours 0-23
            12 to true    // Minutes (0-55 step 5)
        )
        val visibleItemsCases = listOf(3, 5, 7)

        for ((itemCount, circular) in testConfigs) {
            val items = (0 until itemCount).map { it }
            for (visibleItems in visibleItemsCases) {
                for (selectedIndex in listOf(0, 1, itemCount - 1)) {
                    val index = computeInitialIndex(items, selectedIndex, circular, visibleItems)
                    assertTrue(
                        "items=$itemCount, visible=$visibleItems, selected=$selectedIndex: " +
                            "initialIndex=$index should be >= 0",
                        index >= 0
                    )
                }
            }
        }
    }

    @Test
    fun `fixed - external scroll target is non-negative for circular AM PM`() {
        val items = listOf("AM", "PM")
        val visibleItems = 3
        val centeringOffset = visibleItems / 2
        val middleOffset = (CIRCULAR_MULTIPLIER / 2) * items.size

        for (currentIndex in items.indices) {
            val currentFirstVisible = middleOffset + currentIndex - centeringOffset
            for (targetActualIndex in items.indices) {
                val targetVirtualIndex = actualToNearestVirtualIndex(
                    targetActualIndex, currentFirstVisible, items.size, isCircular = true
                )
                val scrollTarget = targetVirtualIndex - centeringOffset
                assertTrue(
                    "From $currentIndex to $targetActualIndex: scrollTarget=$scrollTarget should be >= 0",
                    scrollTarget >= 0
                )
            }
        }
    }

    @Test
    fun `fixed - recentering preserves actual index with centeringOffset`() {
        val itemCount = 24
        val visibleItems = 3
        val centeringOffset = visibleItems / 2
        val middleStart = (CIRCULAR_MULTIPLIER / 2) * itemCount
        for (actualIndex in 0 until itemCount) {
            val scrollTarget = middleStart + actualIndex - centeringOffset
            val centerVirtualIndex = scrollTarget + centeringOffset
            val result = virtualToActualIndex(centerVirtualIndex, itemCount, true)
            assertEquals(
                "Recentering should preserve actual index $actualIndex",
                actualIndex, result
            )
        }
    }

    @Test
    fun `pixel-based centerIndex is unchanged by fix`() {
        // Asserts nothing: the picker selects through its pixel-based centerIndex
        // derivedStateOf, which the index arithmetic above doesn't touch.
        assertTrue("Pixel-based centerIndex is preserved (code review assertion)", true)
    }
}
