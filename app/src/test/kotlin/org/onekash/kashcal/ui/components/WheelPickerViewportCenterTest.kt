package org.onekash.kashcal.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

/**
 * Tests for pixel-based viewport center calculation.
 *
 * The proper fix for the wheel picker bug is to find the item whose center
 * is closest to the viewport's center pixel, rather than relying on
 * firstVisibleItemIndex + magic offsets.
 */
class WheelPickerViewportCenterTest {

    /**
     * Simulates LazyListLayoutInfo.LazyListItemInfo
     */
    data class MockItemInfo(
        val index: Int,
        val offset: Int,  // Pixel offset from viewport top
        val size: Int     // Item height in pixels
    )

    /**
     * Find the item whose center is closest to the viewport center.
     * This is the pixel-accurate way to determine the "selected" item.
     */
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
        // 5 items visible, each 36px tall, viewport = 180px
        // Items positioned: [0, 36, 72, 108, 144] - item at 72 is centered
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

        // Viewport center = 90px, item 17's center = 72 + 18 = 90px - perfect match!
        assertEquals("Item 17 should be at center", 17, centerItem?.index)
    }

    @Test
    fun `find center item with content padding visible`() {
        // When contentPadding is visible (at scroll edges), fewer items show
        // contentPadding = 72px (2 items), so first item starts at 72px
        val itemHeight = 36
        val viewportHeight = 5 * itemHeight  // 180px

        // At scroll position 0 with padding visible:
        // [padding 0-36][padding 36-72][item0 72-108][item1 108-144][item2 144-180]
        val visibleItems = listOf(
            MockItemInfo(index = 0, offset = 72, size = itemHeight),   // After padding
            MockItemInfo(index = 1, offset = 108, size = itemHeight),
            MockItemInfo(index = 2, offset = 144, size = itemHeight)
        )

        val centerItem = findCenterItem(visibleItems, viewportHeight)

        // Viewport center = 90px
        // Item 0's center = 72 + 18 = 90px - perfect match even with padding!
        assertEquals("Item 0 should be at center (accounting for padding)", 0, centerItem?.index)
    }

    @Test
    fun `find center item when scrolled partially`() {
        // Scrolled so items are offset by 10px
        val itemHeight = 36
        val viewportHeight = 5 * itemHeight  // 180px

        val visibleItems = listOf(
            MockItemInfo(index = 15, offset = -10, size = itemHeight),  // Partially visible
            MockItemInfo(index = 16, offset = 26, size = itemHeight),
            MockItemInfo(index = 17, offset = 62, size = itemHeight),
            MockItemInfo(index = 18, offset = 98, size = itemHeight),
            MockItemInfo(index = 19, offset = 134, size = itemHeight),
            MockItemInfo(index = 20, offset = 170, size = itemHeight)   // Partially visible
        )

        val centerItem = findCenterItem(visibleItems, viewportHeight)

        // Viewport center = 90px
        // Item 17's center = 62 + 18 = 80px (distance = 10)
        // Item 18's center = 98 + 18 = 116px (distance = 26)
        // Item 17 is closer to center
        assertEquals("Item 17 should be closest to center", 17, centerItem?.index)
    }

    @Test
    fun `find center item when scrolled past halfway point`() {
        // Scrolled so item 18 is closer to center than item 17
        val itemHeight = 36
        val viewportHeight = 5 * itemHeight  // 180px

        val visibleItems = listOf(
            MockItemInfo(index = 16, offset = -18, size = itemHeight),
            MockItemInfo(index = 17, offset = 18, size = itemHeight),
            MockItemInfo(index = 18, offset = 54, size = itemHeight),   // Now closer to center
            MockItemInfo(index = 19, offset = 90, size = itemHeight),
            MockItemInfo(index = 20, offset = 126, size = itemHeight),
            MockItemInfo(index = 21, offset = 162, size = itemHeight)
        )

        val centerItem = findCenterItem(visibleItems, viewportHeight)

        // Viewport center = 90px
        // Item 17's center = 18 + 18 = 36px (distance = 54)
        // Item 18's center = 54 + 18 = 72px (distance = 18)
        // Item 19's center = 90 + 18 = 108px (distance = 18) - tie with 18
        // minByOrNull returns first match, so item 18
        assertEquals("Item 18 should be closest to center", 18, centerItem?.index)
    }

    // ==================== Comparison with firstVisibleItemIndex approach ====================

    @Test
    fun `pixel-based approach vs firstVisibleItemIndex approach`() {
        val itemHeight = 36
        val viewportHeight = 5 * itemHeight

        println("=== Pixel-Based vs firstVisibleItemIndex ===")
        println()

        // Scenario: User scrolls to center hour 17
        val visibleItems = listOf(
            MockItemInfo(index = 15, offset = 0, size = itemHeight),
            MockItemInfo(index = 16, offset = 36, size = itemHeight),
            MockItemInfo(index = 17, offset = 72, size = itemHeight),   // Visual center
            MockItemInfo(index = 18, offset = 108, size = itemHeight),
            MockItemInfo(index = 19, offset = 144, size = itemHeight)
        )

        // OLD approach: use firstVisibleItemIndex
        val firstVisibleIndex = visibleItems.first().index
        println("OLD: firstVisibleItemIndex = $firstVisibleIndex (WRONG - should be 17)")

        // NEW approach: find pixel center
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

        // With contentPadding = 2 items (72px)
        val withPadding = listOf(
            MockItemInfo(index = 0, offset = 72, size = itemHeight),   // Centered due to padding
            MockItemInfo(index = 1, offset = 108, size = itemHeight),
            MockItemInfo(index = 2, offset = 144, size = itemHeight)
        )
        val centerWithPadding = findCenterItem(withPadding, viewportHeight)
        println("With padding visible: center = ${centerWithPadding?.index}")

        // Without padding (scrolled into middle)
        val withoutPadding = listOf(
            MockItemInfo(index = 15, offset = 0, size = itemHeight),
            MockItemInfo(index = 16, offset = 36, size = itemHeight),
            MockItemInfo(index = 17, offset = 72, size = itemHeight),
            MockItemInfo(index = 18, offset = 108, size = itemHeight),
            MockItemInfo(index = 19, offset = 144, size = itemHeight)
        )
        val centerWithoutPadding = findCenterItem(withoutPadding, viewportHeight)
        println("Without padding: center = ${centerWithoutPadding?.index}")

        // Both should find the item at pixel center correctly!
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
        // Unusual case where items have different heights
        val viewportHeight = 180

        val visibleItems = listOf(
            MockItemInfo(index = 0, offset = 0, size = 50),
            MockItemInfo(index = 1, offset = 50, size = 30),
            MockItemInfo(index = 2, offset = 80, size = 40),   // Center at 80+20=100, closest to 90
            MockItemInfo(index = 3, offset = 120, size = 60)
        )

        val centerItem = findCenterItem(visibleItems, viewportHeight)

        // Viewport center = 90
        // Item 0: center = 25, distance = 65
        // Item 1: center = 65, distance = 25
        // Item 2: center = 100, distance = 10  <-- closest
        // Item 3: center = 150, distance = 60
        assertEquals("Item 2 is closest to viewport center", 2, centerItem?.index)
    }
}
