package org.onekash.kashcal.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the non-circular centering math of [VerticalWheelPicker] on inline copies of its
 * initial index, outside-change scroll target and fallback center index; they don't call the
 * composable.
 *
 * The picker subtracts centeringOffset = visibleItems / 2 in both modes, clamped at 0 when
 * non-circular, so the selected item lands at the viewport center, not the top. Every wheel in
 * the app passes isCircular = true, so this path runs only for a list under two items; the
 * 201-item year wheel (1900..2100) below is a fixture, not the app's year wheel.
 *
 * Known limitation: items at indices 1 and 2 display off-center on the first render, from the
 * contentPadding and the clamp to 0. Once the user scrolls, the pixel-based center pick
 * corrects it.
 *
 * The last test checks the circular formula with the same inline arithmetic.
 */
class WheelPickerNonCircularCenteringTest {

    /** Copies the picker's non-circular initialIndex: selected - visibleItems / 2, min 0. */
    private fun computeNonCircularInitialIndex(
        itemCount: Int,
        selectedIndex: Int,
        visibleItems: Int
    ): Int {
        val centeringOffset = visibleItems / 2
        return (selectedIndex - centeringOffset).coerceAtLeast(0)
    }

    /** Copies the picker's non-circular scroll target for an outside selection change. */
    private fun computeNonCircularScrollTarget(
        targetIndex: Int,
        visibleItems: Int
    ): Int {
        val centeringOffset = visibleItems / 2
        return (targetIndex - centeringOffset).coerceAtLeast(0)
    }

    /** Copies the picker's centerIndex fallback, used while no item is laid out. */
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
        // (125 - 2).coerceAtLeast(0) = 123, so the viewport shows items 123-127 with 125 at
        // position 2, the center for visibleItems = 5.
        assertEquals(123, initialIndex)
    }

    @Test
    fun `year wheel - item 0 (year 1900) clamped and centered via contentPadding`() {
        val initialIndex = computeNonCircularInitialIndex(
            itemCount = 201, selectedIndex = 0, visibleItems = 5
        )
        // (0 - 2).coerceAtLeast(0) = 0; the contentPadding of 2 items above puts item 0 at
        // the viewport center.
        assertEquals(0, initialIndex)
    }

    @Test
    fun `year wheel - item 1 (year 1901) clamped to 0`() {
        // Known limitation: item 1 shows 1 slot below center on the first render.
        val initialIndex = computeNonCircularInitialIndex(
            itemCount = 201, selectedIndex = 1, visibleItems = 5
        )
        assertEquals(0, initialIndex)
    }

    @Test
    fun `year wheel - item 2 (year 1902) clamped to 0`() {
        // Known limitation: item 2 shows 2 slots below center on the first render.
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
        // 3 - 2 = 1, no clamp.
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
        // firstVisibleItemIndex 123 is year 2025 centered.
        val centerIndex = computeFallbackCenterIndex(
            firstVisibleItemIndex = 123, visibleItems = 5
        )
        assertEquals(125, centerIndex)
    }

    @Test
    fun `fallback centerIndex correct for non-circular edge items`() {
        // firstVisibleItemIndex 0, with item 0 centered by the contentPadding.
        val centerIndex = computeFallbackCenterIndex(
            firstVisibleItemIndex = 0, visibleItems = 5
        )
        // The fallback gives 2 although item 0 is at the center. The pixel-based pick
        // replaces it after the first layout.
        assertEquals(2, centerIndex)
    }

    // ==================== Round-Trip: initialIndex → centerIndex ====================

    @Test
    fun `round-trip for year wheel middle items`() {
        val visibleItems = 5
        val centeringOffset = visibleItems / 2

        // Items past the clamp (index > centeringOffset) round-trip.
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

    // ==================== Circular Path ====================

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
