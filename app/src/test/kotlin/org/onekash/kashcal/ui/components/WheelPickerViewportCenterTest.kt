package org.onekash.kashcal.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

/**
 * Tests the pixel-based center pick on an inline copy of [VerticalWheelPicker]'s centerIndex:
 * the item whose center is closest to the viewport's center pixel, with and without visible
 * contentPadding, part-scrolled, on a tie, for an empty or one-item layout and for items of
 * different heights. firstVisibleItemIndex names the top item, not the centered one.
 */
class WheelPickerViewportCenterTest {

    /** Stands in for `LazyListItemInfo`. */
    data class MockItemInfo(
        val index: Int,
        val offset: Int,  // Pixel offset from viewport top
        val size: Int     // Item height in pixels
    )

    /** Returns the item whose center is closest to the viewport center, as the picker does. */
    private fun findCenterItem(
        visibleItems: List<MockItemInfo>,
        viewportHeight: Int
    ): MockItemInfo? {
        val viewportCenterPx = viewportHeight / 2
        return visibleItems.minByOrNull { item ->
            val itemCenterPx = item.offset + item.size / 2
            abs(itemCenterPx - viewportCenterPx)
        }
    }

    // ==================== Basic Center Finding ====================

    @Test
    fun `find center item in perfectly aligned viewport`() {
        // 5 visible items, 36px each, in a 180px viewport at offsets 0, 36, 72, 108 and 144;
        // the one at 72 is centered.
        val itemHeight = 36
        val viewportHeight = 5 * itemHeight  // 180px

        val visibleItems = listOf(
            MockItemInfo(index = 15, offset = 0, size = itemHeight),
            MockItemInfo(index = 16, offset = 36, size = itemHeight),
            MockItemInfo(index = 17, offset = 72, size = itemHeight),   // Center
            MockItemInfo(index = 18, offset = 108, size = itemHeight),
            MockItemInfo(index = 19, offset = 144, size = itemHeight)
        )

        val centerItem = findCenterItem(visibleItems, viewportHeight)

        // Viewport center 90px; item 17's center is 72 + 18 = 90px.
        assertEquals("Item 17 should be at center", 17, centerItem?.index)
    }

    @Test
    fun `find center item with content padding visible`() {
        // At a scroll edge the contentPadding shows and fewer items fit: 72px (2 items) of
        // padding puts the first item at 72px.
        val itemHeight = 36
        val viewportHeight = 5 * itemHeight  // 180px

        // At scroll position 0:
        // [padding 0-36][padding 36-72][item0 72-108][item1 108-144][item2 144-180]
        val visibleItems = listOf(
            MockItemInfo(index = 0, offset = 72, size = itemHeight),   // after the padding
            MockItemInfo(index = 1, offset = 108, size = itemHeight),
            MockItemInfo(index = 2, offset = 144, size = itemHeight)
        )

        val centerItem = findCenterItem(visibleItems, viewportHeight)

        // Viewport center 90px; item 0's center is 72 + 18 = 90px.
        assertEquals("Item 0 should be at center (accounting for padding)", 0, centerItem?.index)
    }

    @Test
    fun `find center item when scrolled partially`() {
        // Scrolled 10px.
        val itemHeight = 36
        val viewportHeight = 5 * itemHeight  // 180px

        val visibleItems = listOf(
            MockItemInfo(index = 15, offset = -10, size = itemHeight),  // partly visible
            MockItemInfo(index = 16, offset = 26, size = itemHeight),
            MockItemInfo(index = 17, offset = 62, size = itemHeight),
            MockItemInfo(index = 18, offset = 98, size = itemHeight),
            MockItemInfo(index = 19, offset = 134, size = itemHeight),
            MockItemInfo(index = 20, offset = 170, size = itemHeight)   // partly visible
        )

        val centerItem = findCenterItem(visibleItems, viewportHeight)

        // Viewport center 90px. Item 17's center is 62 + 18 = 80px (distance 10); item 18's
        // is 98 + 18 = 116px (distance 26).
        assertEquals("Item 17 should be closest to center", 17, centerItem?.index)
    }

    @Test
    fun `find center item when scrolled past halfway point`() {
        // Scrolled so item 18 is closer to the center than item 17.
        val itemHeight = 36
        val viewportHeight = 5 * itemHeight  // 180px

        val visibleItems = listOf(
            MockItemInfo(index = 16, offset = -18, size = itemHeight),
            MockItemInfo(index = 17, offset = 18, size = itemHeight),
            MockItemInfo(index = 18, offset = 54, size = itemHeight),   // closer to center
            MockItemInfo(index = 19, offset = 90, size = itemHeight),
            MockItemInfo(index = 20, offset = 126, size = itemHeight),
            MockItemInfo(index = 21, offset = 162, size = itemHeight)
        )

        val centerItem = findCenterItem(visibleItems, viewportHeight)

        // Viewport center 90px. Item 17's center is 36px (distance 54), item 18's 72px
        // (distance 18), item 19's 108px (distance 18). On the tie minByOrNull returns the
        // first, item 18.
        assertEquals("Item 18 should be closest to center", 18, centerItem?.index)
    }

    // ==================== Comparison with firstVisibleItemIndex approach ====================

    @Test
    fun `pixel-based approach vs firstVisibleItemIndex approach`() {
        val itemHeight = 36
        val viewportHeight = 5 * itemHeight

        println("=== Pixel-Based vs firstVisibleItemIndex ===")
        println()

        // The user scrolls hour 17 to the center.
        val visibleItems = listOf(
            MockItemInfo(index = 15, offset = 0, size = itemHeight),
            MockItemInfo(index = 16, offset = 36, size = itemHeight),
            MockItemInfo(index = 17, offset = 72, size = itemHeight),   // visual center
            MockItemInfo(index = 18, offset = 108, size = itemHeight),
            MockItemInfo(index = 19, offset = 144, size = itemHeight)
        )

        // firstVisibleItemIndex gives the top item.
        val firstVisibleIndex = visibleItems.first().index
        println("OLD: firstVisibleItemIndex = $firstVisibleIndex (WRONG - should be 17)")

        // The pixel-based pick gives the centered one.
        val centerItem = findCenterItem(visibleItems, viewportHeight)
        println("NEW: pixel-based center = ${centerItem?.index} (CORRECT)")

        assertEquals("Pixel-based finds correct center", 17, centerItem?.index)
        assertEquals("firstVisibleItemIndex is wrong", 15, firstVisibleIndex)
    }

    @Test
    fun `pixel-based works regardless of contentPadding`() {
        val itemHeight = 36
        val viewportHeight = 5 * itemHeight

        println("\n=== Pixel-Based Works With Any contentPadding ===")

        // contentPadding of 2 items (72px) showing.
        val withPadding = listOf(
            MockItemInfo(index = 0, offset = 72, size = itemHeight),   // centered by the padding
            MockItemInfo(index = 1, offset = 108, size = itemHeight),
            MockItemInfo(index = 2, offset = 144, size = itemHeight)
        )
        val centerWithPadding = findCenterItem(withPadding, viewportHeight)
        println("With padding visible: center = ${centerWithPadding?.index}")

        // No padding showing, scrolled into the middle.
        val withoutPadding = listOf(
            MockItemInfo(index = 15, offset = 0, size = itemHeight),
            MockItemInfo(index = 16, offset = 36, size = itemHeight),
            MockItemInfo(index = 17, offset = 72, size = itemHeight),
            MockItemInfo(index = 18, offset = 108, size = itemHeight),
            MockItemInfo(index = 19, offset = 144, size = itemHeight)
        )
        val centerWithoutPadding = findCenterItem(withoutPadding, viewportHeight)
        println("Without padding: center = ${centerWithoutPadding?.index}")

        // Both find the item at the center pixel.
        assertEquals("With padding: item 0 is centered", 0, centerWithPadding?.index)
        assertEquals("Without padding: item 17 is centered", 17, centerWithoutPadding?.index)
    }

    // ==================== Edge Cases ====================

    @Test
    fun `handles empty visible items`() {
        val centerItem = findCenterItem(emptyList(), 180)
        assertEquals("Empty list returns null", null, centerItem)
    }

    @Test
    fun `handles single visible item`() {
        val visibleItems = listOf(
            MockItemInfo(index = 5, offset = 72, size = 36)
        )
        val centerItem = findCenterItem(visibleItems, 180)
        assertEquals("Single item is the center", 5, centerItem?.index)
    }

    @Test
    fun `handles items of different sizes`() {
        // Items of different heights.
        val viewportHeight = 180

        val visibleItems = listOf(
            MockItemInfo(index = 0, offset = 0, size = 50),
            MockItemInfo(index = 1, offset = 50, size = 30),
            MockItemInfo(index = 2, offset = 80, size = 40),   // center 80 + 20 = 100
            MockItemInfo(index = 3, offset = 120, size = 60)
        )

        val centerItem = findCenterItem(visibleItems, viewportHeight)

        // Viewport center 90. Distances: item 0 (center 25) 65, item 1 (center 65) 25,
        // item 2 (center 100) 10, item 3 (center 150) 60.
        assertEquals("Item 2 is closest to viewport center", 2, centerItem?.index)
    }
}
