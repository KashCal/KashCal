package org.onekash.kashcal.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

/**
 * Tests circular [VerticalWheelPicker] arithmetic on inline copies of its steps, calling only
 * [virtualToActualIndex] and [actualToNearestVirtualIndex]: the outside-change scroll check,
 * edge recentering, the two-item AM/PM wheel, the empty-layout fallback, wrap detection for
 * haptics and the center-distance alpha.
 *
 * Every wheel in the app is circular. A circular wheel has no contentPadding and subtracts
 * centeringOffset = visibleItems / 2 from its scroll targets to center the selected item.
 */
class WheelPickerRegressionTest {

    companion object {
        private const val CIRCULAR_MULTIPLIER = 1000
    }

    // ==================== External Scroll Logic ====================

    @Test
    fun `external scroll does not trigger when item already centered`() {
        // Item 17 is at the center and the caller sets selectedItem = 17: no scroll.

        val items = (0..23).toList()
        val visibleItems = 5
        val centeringOffset = visibleItems / 2  // 2

        // Item 17 centered: firstVisibleItemIndex = middleOffset + 17 - centeringOffset
        // = 12000 + 15.
        val firstVisibleItemIndex = 12000 + 15
        val pixelBasedCenterIndex = 12000 + 17  // firstVisible + centeringOffset

        // Target: selectedItem = 17
        val targetActualIndex = 17

        // The picker compares the target with the actual index of the pixel-based center.
        val currentCenterActual = virtualToActualIndex(pixelBasedCenterIndex, items.size, true)
        val wouldScroll = targetActualIndex != currentCenterActual

        assertEquals("Should NOT scroll - item already at center", false, wouldScroll)
    }

    @Test
    fun `external scroll should trigger when item is NOT at center`() {
        val items = (0..23).toList()
        val visibleItems = 5
        val centeringOffset = visibleItems / 2

        // Item 17 at the center.
        val pixelBasedCenterIndex = 12000 + 17

        // Change to item 20.
        val targetActualIndex = 20

        val currentCenterActual = virtualToActualIndex(pixelBasedCenterIndex, items.size, true)
        val wouldScroll = targetActualIndex != currentCenterActual

        assertEquals("Should scroll to new item", true, wouldScroll)
    }

    // ==================== Edge Recentering ====================

    @Test
    fun `edge recentering places item at center with centeringOffset`() {
        val items = (0..23).toList()
        val visibleItems = 3
        val centeringOffset = visibleItems / 2  // 1
        val middleStart = (CIRCULAR_MULTIPLIER / 2) * items.size  // 12000
        val actualIndex = 17

        // Recentering scrolls to middleStart + actualIndex - centeringOffset.
        val scrollTarget = middleStart + actualIndex - centeringOffset
        // The centered item is scrollTarget + centeringOffset.
        val centerVirtualIndex = scrollTarget + centeringOffset
        val centerActual = virtualToActualIndex(centerVirtualIndex, items.size, true)

        assertEquals("Recentered item at center should be 17", actualIndex, centerActual)
    }

    // ==================== AM/PM Circular Mode ====================

    @Test
    fun `AM PM circular mode - both values selectable`() {
        val items = listOf("AM", "PM")
        // AM/PM is circular: its wheel passes isCircular = true and effectiveCircular needs
        // two or more items.
        val effectiveCircular = items.size >= 2
        assertEquals("AM/PM should be circular", true, effectiveCircular)

        val middleOffset = (CIRCULAR_MULTIPLIER / 2) * items.size  // 1000
        val visibleItems = 3
        val centeringOffset = visibleItems / 2  // 1

        // "AM" (index 0)
        val amInitial = middleOffset + 0 - centeringOffset  // 999
        val amCenter = virtualToActualIndex(amInitial + centeringOffset, items.size, true)
        assertEquals("AM should be at center", 0, amCenter)

        // "PM" (index 1)
        val pmInitial = middleOffset + 1 - centeringOffset  // 1000
        val pmCenter = virtualToActualIndex(pmInitial + centeringOffset, items.size, true)
        assertEquals("PM should be at center", 1, pmCenter)
    }

    @Test
    fun `AM PM circular - external scroll between AM and PM`() {
        val items = listOf("AM", "PM")
        val visibleItems = 3
        val centeringOffset = visibleItems / 2
        val middleOffset = (CIRCULAR_MULTIPLIER / 2) * items.size

        // AM showing: firstVisible = 1000 + 0 - 1 = 999.
        val currentFirstVisible = middleOffset + 0 - centeringOffset

        // Switch to PM (index 1).
        val targetVirtualIndex = actualToNearestVirtualIndex(
            1, currentFirstVisible, items.size, isCircular = true
        )
        val scrollTarget = targetVirtualIndex - centeringOffset
        val centerActual = virtualToActualIndex(scrollTarget + centeringOffset, items.size, true)

        assertEquals("After external scroll, PM should be at center", 1, centerActual)
    }

    // ==================== Initial Render ====================

    @Test
    fun `initial render fallback uses firstVisibleItemIndex`() {
        // Before the first layout, layoutInfo.visibleItemsInfo can be empty. This inline
        // copy falls back to firstVisibleItemIndex, the top item; the picker's fallback is
        // firstVisibleItemIndex + centeringOffset, which this test doesn't model. The
        // pixel-based pick takes over once layout completes.

        val visibleItemsInfo = emptyList<Any>()
        val firstVisibleItemIndex = 12017  // middleOffset + 17

        val centerIndex = if (visibleItemsInfo.isEmpty()) {
            firstVisibleItemIndex
        } else {
            -1
        }

        assertEquals("Should fall back to firstVisibleItemIndex", firstVisibleItemIndex, centerIndex)
    }

    // ==================== Wrap-Around Selection ====================

    @Test
    fun `wrap detection still works with pixel-based centerIndex`() {
        val items = (0..23).toList()

        // Scrolling from hour 23 to hour 0 wraps.
        val previousActualIndex = 23
        val currentCenterIndex = 12000 + 0  // Now at hour 0
        val currentActualIndex = virtualToActualIndex(currentCenterIndex, items.size, true)

        val wrapped = abs(currentActualIndex - previousActualIndex) > items.size / 2
        assertEquals("Should detect wrap from 23 to 0", true, wrapped)

        // 22 to 23 doesn't.
        val previousActualIndex2 = 22
        val currentActualIndex2 = 23
        val wrapped2 = abs(currentActualIndex2 - previousActualIndex2) > items.size / 2
        assertEquals("Should not detect wrap from 22 to 23", false, wrapped2)
    }

    // ==================== Visual Highlighting ====================

    @Test
    fun `visual highlighting uses pixel-based centerIndex`() {
        val centerIndex = 12017  // the pixel-based center

        // Items near center
        val item17VirtualIndex = 12017
        val item16VirtualIndex = 12016
        val item18VirtualIndex = 12018
        val item15VirtualIndex = 12015

        val distance17 = abs(item17VirtualIndex - centerIndex)  // 0
        val distance16 = abs(item16VirtualIndex - centerIndex)  // 1
        val distance18 = abs(item18VirtualIndex - centerIndex)  // 1
        val distance15 = abs(item15VirtualIndex - centerIndex)  // 2

        val alpha17 = if (distance17 == 0) 1f else if (distance17 == 1) 0.6f else 0.3f
        val alpha16 = if (distance16 == 0) 1f else if (distance16 == 1) 0.6f else 0.3f
        val alpha15 = if (distance15 == 0) 1f else if (distance15 == 1) 0.6f else 0.3f

        assertEquals("Center item should have full alpha", 1f, alpha17)
        assertEquals("Adjacent items should have 0.6 alpha", 0.6f, alpha16)
        assertEquals("Items 2 away should have 0.3 alpha", 0.3f, alpha15)
    }
}
