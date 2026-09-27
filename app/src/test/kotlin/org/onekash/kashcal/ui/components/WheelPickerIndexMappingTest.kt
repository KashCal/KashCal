package org.onekash.kashcal.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests [virtualToActualIndex] and [actualToNearestVirtualIndex], the index math behind the
 * circular wheel in [VerticalWheelPicker]:
 * - modulo wrapping in circular mode, negative and large indices included;
 * - clamping in non-circular mode, and 0 for an empty or one-item list;
 * - the shorter way round for an outside selection change, forward on a tie, and the target
 *   itself in non-circular mode;
 * - hour, minute and two-item (AM/PM) wheels at their wrap points;
 * - recentering arithmetic, computed inline from the picker's multiplier of 1000 (not a
 *   production call);
 * - bounds over wide index ranges and a virtual-to-actual round trip.
 */
class WheelPickerIndexMappingTest {

    // ==================== virtualToActualIndex ====================

    @Test
    fun `maps middle range correctly`() {
        // A 12-item list, like hours 0-11 or 1-12: 500 * 12 + 5 = 6005 maps to 5.
        val actual = virtualToActualIndex(6005, 12, isCircular = true)
        assertEquals(5, actual)
    }

    @Test
    fun `maps zero virtual index correctly`() {
        assertEquals(0, virtualToActualIndex(0, 12, isCircular = true))
    }

    @Test
    fun `handles negative indices safely`() {
        // -1 wraps to 11.
        val actual = virtualToActualIndex(-1, 12, isCircular = true)
        assertEquals(11, actual)
    }

    @Test
    fun `handles large negative indices`() {
        // -25 % 12 is -1 in Kotlin; (-1 + 12) % 12 = 11.
        val actual = virtualToActualIndex(-25, 12, isCircular = true)
        assertEquals(11, actual)
    }

    @Test
    fun `non-circular mode clamps to bounds`() {
        // 15 clamps to the last index, 11.
        assertEquals(11, virtualToActualIndex(15, 12, isCircular = false))
        // -3 clamps to 0.
        assertEquals(0, virtualToActualIndex(-3, 12, isCircular = false))
    }

    @Test
    fun `non-circular mode returns valid index in range`() {
        assertEquals(5, virtualToActualIndex(5, 12, isCircular = false))
    }

    @Test
    fun `empty list returns 0`() {
        assertEquals(0, virtualToActualIndex(5, 0, isCircular = true))
        assertEquals(0, virtualToActualIndex(5, 0, isCircular = false))
    }

    @Test
    fun `single item list returns 0`() {
        assertEquals(0, virtualToActualIndex(0, 1, isCircular = true))
        assertEquals(0, virtualToActualIndex(5, 1, isCircular = true))
        assertEquals(0, virtualToActualIndex(-3, 1, isCircular = true))
    }

    @Test
    fun `large virtual index maps correctly`() {
        // 11999 % 12 = 11
        val actual = virtualToActualIndex(11999, 12, isCircular = true)
        assertEquals(11, actual)
    }

    // ==================== actualToNearestVirtualIndex ====================

    @Test
    fun `finds nearest forward`() {
        // From virtual 6000 (actual 0) to actual 3: forward 3, to 6003.
        val result = actualToNearestVirtualIndex(
            targetActualIndex = 3,
            currentVirtualIndex = 6000,
            itemCount = 12,
            isCircular = true
        )
        assertEquals(6003, result)
    }

    @Test
    fun `finds nearest backward`() {
        // From virtual 6005 (actual 5) to actual 2: back 3, to 6002.
        val result = actualToNearestVirtualIndex(
            targetActualIndex = 2,
            currentVirtualIndex = 6005,
            itemCount = 12,
            isCircular = true
        )
        assertEquals(6002, result)
    }

    @Test
    fun `wraps forward when shorter - 11 to 0`() {
        // From virtual 6011 (actual 11) to actual 0: forward is 1 step, back is 11, so it
        // wraps forward to 6012.
        val result = actualToNearestVirtualIndex(
            targetActualIndex = 0,
            currentVirtualIndex = 6011,
            itemCount = 12,
            isCircular = true
        )
        assertEquals(6012, result)
    }

    @Test
    fun `wraps backward when shorter - 0 to 11`() {
        // From virtual 6000 (actual 0) to actual 11: back is 1 step, forward is 11, so it
        // wraps back to 5999.
        val result = actualToNearestVirtualIndex(
            targetActualIndex = 11,
            currentVirtualIndex = 6000,
            itemCount = 12,
            isCircular = true
        )
        assertEquals(5999, result)
    }

    @Test
    fun `handles midpoint by choosing forward`() {
        // 12 items, actual 0 to actual 6: 6 steps either way. The delta of 6 isn't above
        // itemCount / 2, so it stays forward.
        val result = actualToNearestVirtualIndex(
            targetActualIndex = 6,
            currentVirtualIndex = 6000,
            itemCount = 12,
            isCircular = true
        )
        assertEquals(6006, result)
    }

    @Test
    fun `non-circular mode returns target directly`() {
        val result = actualToNearestVirtualIndex(
            targetActualIndex = 8,
            currentVirtualIndex = 3,
            itemCount = 12,
            isCircular = false
        )
        assertEquals(8, result)
    }

    // ==================== Hour Boundaries ====================

    @Test
    fun `12h wraps 12 to 1`() {
        // Hours 1-12, index 0 is hour 1 and index 11 is hour 12. From hour 12 to hour 1
        // wraps forward.
        val result = actualToNearestVirtualIndex(
            targetActualIndex = 0,
            currentVirtualIndex = 6011,
            itemCount = 12,
            isCircular = true
        )
        assertEquals(6012, result)
        assertEquals(0, virtualToActualIndex(6012, 12, isCircular = true))
    }

    @Test
    fun `12h wraps 1 to 12`() {
        // From index 0 (hour 1) to index 11 (hour 12) wraps back.
        val result = actualToNearestVirtualIndex(
            targetActualIndex = 11,
            currentVirtualIndex = 6000,
            itemCount = 12,
            isCircular = true
        )
        assertEquals(5999, result)
        assertEquals(11, virtualToActualIndex(5999, 12, isCircular = true))
    }

    @Test
    fun `24h wraps 23 to 0`() {
        // 24 hours, index n is hour n. From 23 to 0: forward 1 step, back 23.
        val result = actualToNearestVirtualIndex(
            targetActualIndex = 0,
            currentVirtualIndex = 12023,  // actual 23
            itemCount = 24,
            isCircular = true
        )
        assertEquals(12024, result)
        assertEquals(0, virtualToActualIndex(12024, 24, isCircular = true))
    }

    @Test
    fun `24h wraps 0 to 23`() {
        // From 0 to 23: forward 23 steps, back 1.
        val result = actualToNearestVirtualIndex(
            targetActualIndex = 23,
            currentVirtualIndex = 12000,  // actual 0
            itemCount = 24,
            isCircular = true
        )
        assertEquals(11999, result)
        assertEquals(23, virtualToActualIndex(11999, 24, isCircular = true))
    }

    // ==================== Minute Boundaries ====================

    @Test
    fun `minutes wrap 55 to 0`() {
        // Minutes 0, 5, ..., 55 (12 items at a 5-minute interval): index 11 is 55, index 0
        // is 00.
        val result = actualToNearestVirtualIndex(
            targetActualIndex = 0,
            currentVirtualIndex = 6011,
            itemCount = 12,
            isCircular = true
        )
        assertEquals(6012, result)
    }

    @Test
    fun `minutes wrap 0 to 55`() {
        val result = actualToNearestVirtualIndex(
            targetActualIndex = 11,
            currentVirtualIndex = 6000,
            itemCount = 12,
            isCircular = true
        )
        assertEquals(5999, result)
    }

    @Test
    fun `minute intervals respected in wrap - 10 interval`() {
        // Minutes 0, 10, ..., 50 (6 items). From 50 (index 5) to 0 wraps forward.
        val result = actualToNearestVirtualIndex(
            targetActualIndex = 0,
            currentVirtualIndex = 3005,
            itemCount = 6,
            isCircular = true
        )
        assertEquals(3006, result)
    }

    // ==================== Edge Recentering ====================

    @Test
    fun `recentering threshold calculation`() {
        // Inline copy of the picker's recenter test: with a multiplier of 1000 and 12 items
        // the middle start is 500 * 12 = 6000, and a drift over 250 * 12 = 3000 recenters.
        val middleStart = 500 * 12  // 6000
        val threshold = 250 * 12    // 3000

        // 9100 is past the threshold.
        val drift = kotlin.math.abs(9100 - middleStart)
        assertTrue("Should trigger recentering", drift > threshold)

        // 7500 is within it.
        val smallDrift = kotlin.math.abs(7500 - middleStart)
        assertTrue("Should NOT trigger recentering", smallDrift <= threshold)
    }

    @Test
    fun `recenter preserves actual index`() {
        // Recentering from 9100 to the middle keeps the actual index.
        val virtualIndex = 9100
        val itemCount = 12
        val actualIndex = virtualToActualIndex(virtualIndex, itemCount, isCircular = true)

        val middleStart = 500 * itemCount  // 6000
        val recenteredVirtual = middleStart + actualIndex

        assertEquals(
            actualIndex,
            virtualToActualIndex(recenteredVirtual, itemCount, isCircular = true)
        )
    }

    // ==================== Bounds Safety ====================

    @Test
    fun `assert actual index always in bounds for positive virtual indices`() {
        val itemCount = 24
        for (virtualIndex in 0..50000 step 100) {
            val actual = virtualToActualIndex(virtualIndex, itemCount, isCircular = true)
            assertTrue(
                "Actual index $actual should be in [0, ${itemCount - 1}]",
                actual in 0 until itemCount
            )
        }
    }

    @Test
    fun `assert actual index always in bounds for negative virtual indices`() {
        val itemCount = 24
        for (virtualIndex in -50000..0 step 100) {
            val actual = virtualToActualIndex(virtualIndex, itemCount, isCircular = true)
            assertTrue(
                "Actual index $actual should be in [0, ${itemCount - 1}] for virtual $virtualIndex",
                actual in 0 until itemCount
            )
        }
    }

    @Test
    fun `round trip virtual to actual and back maintains consistency`() {
        // At virtual index V with actual index A, actualToNearestVirtualIndex(A, V, count)
        // returns V.
        for (virtualIndex in 5990..6010) {
            val actualIndex = virtualToActualIndex(virtualIndex, 12, isCircular = true)
            val roundTrip = actualToNearestVirtualIndex(
                actualIndex, virtualIndex, 12, isCircular = true
            )
            assertEquals(
                "Virtual index $virtualIndex should round-trip",
                virtualIndex, roundTrip
            )
        }
    }

    // ==================== AM/PM Special Case ====================

    @Test
    fun `two item list should not use circular wrapping`() {
        // The AM/PM wheel is circular: VerticalWheelPicker keeps a two-item list circular
        // (effectiveCircular needs at least two items).
        val result = virtualToActualIndex(5, 2, isCircular = true)
        assertEquals(1, result)  // 5 mod 2 = 1
    }

    @Test
    fun `two item list wrap behavior`() {
        // From index 0 (AM) to index 1 (PM) is 1 step either way; the tie goes forward.
        val result = actualToNearestVirtualIndex(
            targetActualIndex = 1,
            currentVirtualIndex = 1000,
            itemCount = 2,
            isCircular = true
        )
        assertEquals(1001, result)
    }
}
