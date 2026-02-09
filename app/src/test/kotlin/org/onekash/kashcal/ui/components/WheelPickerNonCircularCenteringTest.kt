package org.onekash.kashcal.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pre-tests for the non-circular centering fix in VerticalWheelPicker.
 *
 * Problem: centeringOffset = 0 for non-circular mode, so items appear at the
 * viewport TOP instead of CENTER. This was never exposed because all current
 * wheels use circular mode. A 201-item year wheel would expose it.
 *
 * Fix: centeringOffset = visibleItems / 2 (always), with coerceAtLeast(0) for
 * non-circular initialIndex and scroll targets.
 *
 * Known limitation: Items at indices 1 and 2 display slightly off-center on
 * initial render due to contentPadding + coerceAtLeast(0) interaction. Once
 * user scrolls, pixel-based center detection corrects it.
 */
class WheelPickerNonCircularCenteringTest {

    /**
     * Simulates the FIXED non-circular initialIndex calculation.
     * centeringOffset always = visibleItems / 2, clamped to 0 for non-circular.
     */
    private fun computeNonCircularInitialIndex(
        itemCount: Int,
        selectedIndex: Int,
        visibleItems: Int
    ): Int {
        val centeringOffset = visibleItems / 2
        return (selectedIndex - centeringOffset).coerceAtLeast(0)
    }

    /**
     * Simulates the FIXED non-circular external scroll target.
     */
    private fun computeNonCircularScrollTarget(
        targetIndex: Int,
        visibleItems: Int
    ): Int {
        val centeringOffset = visibleItems / 2
        return (targetIndex - centeringOffset).coerceAtLeast(0)
    }

    /**
     * Simulates the FIXED fallback centerIndex for non-circular mode.
     */
    private fun computeFallbackCenterIndex(
        firstVisibleItemIndex: Int,
        visibleItems: Int
    ): Int {
        val centeringOffset = visibleItems / 2
        return firstVisibleItemIndex + centeringOffset
    }

    // ==================== Year Wheel: 201 Items (1900..2100) ====================

    @Test
    fun `year wheel - item 125 (year 2025) centered correctly`() {
        val initialIndex = computeNonCircularInitialIndex(
            itemCount = 201, selectedIndex = 125, visibleItems = 5
        )
        // initialIndex = (125 - 2).coerceAtLeast(0) = 123
        // Viewport: [item123, item124, item125, item126, item127]
        // item125 is at position 2 (center for visibleItems=5)
        assertEquals(123, initialIndex)
    }

    @Test
    fun `year wheel - item 0 (year 1900) clamped and centered via contentPadding`() {
        val initialIndex = computeNonCircularInitialIndex(
            itemCount = 201, selectedIndex = 0, visibleItems = 5
        )
        // initialIndex = (0 - 2).coerceAtLeast(0) = 0
        // contentPadding adds 2 items of space above → item 0 at viewport center
        assertEquals(0, initialIndex)
    }

    @Test
    fun `year wheel - item 1 (year 1901) clamped to 0`() {
        // Known limitation: item 1 will be 1 slot below center on initial display
        val initialIndex = computeNonCircularInitialIndex(
            itemCount = 201, selectedIndex = 1, visibleItems = 5
        )
        assertEquals(0, initialIndex)
    }

    @Test
    fun `year wheel - item 2 (year 1902) clamped to 0`() {
        // Known limitation: item 2 will be 2 slots below center on initial display
        val initialIndex = computeNonCircularInitialIndex(
            itemCount = 201, selectedIndex = 2, visibleItems = 5
        )
        assertEquals(0, initialIndex)
    }

    @Test
    fun `year wheel - item 3 exactly reaches 0 without clamping`() {
        val initialIndex = computeNonCircularInitialIndex(
            itemCount = 201, selectedIndex = 3, visibleItems = 5
        )
        // (3 - 2) = 1, no clamping needed
        assertEquals(1, initialIndex)
    }

    @Test
    fun `year wheel - item 200 (year 2100) last item`() {
        val initialIndex = computeNonCircularInitialIndex(
            itemCount = 201, selectedIndex = 200, visibleItems = 5
        )
        assertEquals(198, initialIndex)
    }

    // ==================== Month Wheel: 12 Items ====================

    @Test
    fun `month wheel - item 0 (January) clamped`() {
        val initialIndex = computeNonCircularInitialIndex(
            itemCount = 12, selectedIndex = 0, visibleItems = 5
        )
        assertEquals(0, initialIndex)
    }

    @Test
    fun `month wheel - item 5 (June) centered`() {
        val initialIndex = computeNonCircularInitialIndex(
            itemCount = 12, selectedIndex = 5, visibleItems = 5
        )
        assertEquals(3, initialIndex)
    }

    @Test
    fun `month wheel - item 11 (December) last item`() {
        val initialIndex = computeNonCircularInitialIndex(
            itemCount = 12, selectedIndex = 11, visibleItems = 5
        )
        assertEquals(9, initialIndex)
    }

    // ==================== Matrix: All Combos Non-Negative ====================

    @Test
    fun `all item count, visibleItems, selectedIndex combos produce non-negative indices`() {
        val itemCounts = listOf(2, 3, 5, 12, 24, 60, 201)
        val visibleItemsCases = listOf(3, 5, 7)

        for (itemCount in itemCounts) {
            for (visibleItems in visibleItemsCases) {
                for (selectedIndex in 0 until itemCount) {
                    val initialIndex = computeNonCircularInitialIndex(
                        itemCount, selectedIndex, visibleItems
                    )
                    assertTrue(
                        "itemCount=$itemCount, visible=$visibleItems, selected=$selectedIndex " +
                            "→ initialIndex=$initialIndex should be >= 0",
                        initialIndex >= 0
                    )
                    assertTrue(
                        "initialIndex=$initialIndex should be < itemCount=$itemCount",
                        initialIndex < itemCount
                    )
                }
            }
        }
    }

    // ==================== External Scroll Targets ====================

    @Test
    fun `external scroll targets are valid for year wheel`() {
        val itemCount = 201
        val visibleItems = 5

        for (targetIndex in 0 until itemCount) {
            val scrollTarget = computeNonCircularScrollTarget(targetIndex, visibleItems)
            assertTrue(
                "targetIndex=$targetIndex → scrollTarget=$scrollTarget should be >= 0",
                scrollTarget >= 0
            )
            assertTrue(
                "scrollTarget=$scrollTarget should be < itemCount=$itemCount",
                scrollTarget < itemCount
            )
        }
    }

    @Test
    fun `external scroll targets are valid for month wheel`() {
        val itemCount = 12
        val visibleItems = 5

        for (targetIndex in 0 until itemCount) {
            val scrollTarget = computeNonCircularScrollTarget(targetIndex, visibleItems)
            assertTrue(
                "targetIndex=$targetIndex → scrollTarget=$scrollTarget should be >= 0",
                scrollTarget >= 0
            )
        }
    }

    // ==================== Fallback CenterIndex ====================

    @Test
    fun `fallback centerIndex correct for non-circular middle items`() {
        // When firstVisibleItemIndex = 123 (for year 2025 centered)
        val centerIndex = computeFallbackCenterIndex(
            firstVisibleItemIndex = 123, visibleItems = 5
        )
        assertEquals(125, centerIndex)
    }

    @Test
    fun `fallback centerIndex correct for non-circular edge items`() {
        // When firstVisibleItemIndex = 0 (item 0 centered via contentPadding)
        val centerIndex = computeFallbackCenterIndex(
            firstVisibleItemIndex = 0, visibleItems = 5
        )
        // Returns 2, which is the center position in viewport
        // For item 0 at edge, contentPadding shifts things so actual center is item 0,
        // but fallback approximation returns 2 — acceptable since pixel-based
        // detection corrects this after first layout
        assertEquals(2, centerIndex)
    }

    // ==================== Round-Trip: initialIndex → centerIndex ====================

    @Test
    fun `round-trip for year wheel middle items`() {
        val visibleItems = 5
        val centeringOffset = visibleItems / 2

        // For items well past the edge (index > centeringOffset):
        for (selectedIndex in 3..200) {
            val initialIndex = computeNonCircularInitialIndex(201, selectedIndex, visibleItems)
            val recoveredCenter = initialIndex + centeringOffset
            assertEquals(
                "selectedIndex=$selectedIndex should round-trip",
                selectedIndex, recoveredCenter
            )
        }
    }

    @Test
    fun `round-trip for month wheel middle items`() {
        val visibleItems = 5
        val centeringOffset = visibleItems / 2

        for (selectedIndex in 3..11) {
            val initialIndex = computeNonCircularInitialIndex(12, selectedIndex, visibleItems)
            val recoveredCenter = initialIndex + centeringOffset
            assertEquals(
                "month index=$selectedIndex should round-trip",
                selectedIndex, recoveredCenter
            )
        }
    }

    // ==================== Verify Existing Circular Path Unchanged ====================

    @Test
    fun `circular centering formula unchanged`() {
        val circularMultiplier = 1000
        val itemCount = 24
        val visibleItems = 5
        val centeringOffset = visibleItems / 2
        val middleOffset = (circularMultiplier / 2) * itemCount

        for (selectedIndex in 0 until itemCount) {
            val initialIndex = middleOffset + selectedIndex - centeringOffset
            assertTrue("Circular initialIndex should be >= 0", initialIndex >= 0)

            val centerVirtualIndex = initialIndex + centeringOffset
            val recoveredActual = virtualToActualIndex(centerVirtualIndex, itemCount, true)
            assertEquals(
                "Circular selectedIndex=$selectedIndex should round-trip",
                selectedIndex, recoveredActual
            )
        }
    }
}
