package org.onekash.kashcal.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for wheel picker index mapping functions.
 *
 * These pure Kotlin math functions handle the virtual-to-actual index mapping
 * for circular scrolling in the wheel picker. Tests verify:
 * - Modulo wrapping for circular mode
 * - Bounds clamping for non-circular mode
 * - Shortest path calculation for external selection sync
 * - Edge cases (empty lists, negative indices, large indices)
 */
class WheelPickerIndexMappingTest {

    // ==================== virtualToActualIndex ====================

    @Test
    fun `maps middle range correctly`() {
        // 12-item list (like hours 0-11 or 1-12)
        // Virtual index 500*12 + 5 = 6005 should map to actual index 5
        val actual = virtualToActualIndex(6005, 12, isCircular = true)
        assertEquals(5, actual)
    }

    @Test
    fun `maps zero virtual index correctly`() {
        assertEquals(0, virtualToActualIndex(0, 12, isCircular = true))
    }

    @Test
    fun `handles negative indices safely`() {
        // -1 mod 12 should give 11 (wrap around)
        val actual = virtualToActualIndex(-1, 12, isCircular = true)
        assertEquals(11, actual)
    }

    @Test
    fun `handles large negative indices`() {
        // -25 mod 12: -25 + 36 = 11, then mod 12 = 11
        val actual = virtualToActualIndex(-25, 12, isCircular = true)
        assertEquals(11, actual)
    }

    @Test
    fun `non-circular mode clamps to bounds`() {
        // In non-circular mode, index 15 should clamp to max (11)
        assertEquals(11, virtualToActualIndex(15, 12, isCircular = false))
        // Index -3 should clamp to min (0)
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
        // Index 11999 for 12 items: 11999 mod 12 = 11
        val actual = virtualToActualIndex(11999, 12, isCircular = true)
        assertEquals(11, actual)
    }

    // ==================== actualToNearestVirtualIndex ====================

    @Test
    fun `finds nearest forward`() {
        // Current at virtual 6000 (actual 0), want actual 3
        // Should return 6003 (move forward 3)
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
        // Current at virtual 6005 (actual 5), want actual 2
        // Should return 6002 (move backward 3)
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
        // Current at virtual 6011 (actual 11), want actual 0
        // Forward wrap: +1 step (11→0)
        // Backward: -11 steps
        // Should wrap forward: 6011 + 1 = 6012
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
        // Current at virtual 6000 (actual 0), want actual 11
        // Backward wrap: -1 step (0→11)
        // Forward: +11 steps
        // Should wrap backward: 6000 - 1 = 5999
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
        // 12 items, current at actual 0, want actual 6
        // Forward: +6, Backward: -6
        // delta = 6, which equals itemCount/2 (6), so no adjustment
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
        // Hours 1-12 in a list (index 0=1, index 11=12)
        // From index 11 (hour 12), want index 0 (hour 1)
        // Should wrap forward
        val result = actualToNearestVirtualIndex(
            targetActualIndex = 0,
            currentVirtualIndex = 6011,
            itemCount = 12,
            isCircular = true
        )
        assertEquals(6012, result)
        // Verify actual index
        assertEquals(0, virtualToActualIndex(6012, 12, isCircular = true))
    }

    @Test
    fun `12h wraps 1 to 12`() {
        // From index 0 (hour 1), want index 11 (hour 12)
        // Should wrap backward
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
        // 24 hours (index 0=00, index 23=23)
        // From index 23, want index 0
        // Forward: +1 step, Backward: -23 steps
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
        // From index 0, want index 23
        // Forward: +23 steps, Backward: -1 step
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
        // Minutes: 0, 5, 10, ..., 55 (12 items with interval 5)
        // Index 11 = 55, Index 0 = 00
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
        // Minutes: 0, 10, 20, 30, 40, 50 (6 items)
        // From 50 (index 5) to 0 (index 0): should wrap forward
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
        // CIRCULAR_MULTIPLIER = 1000, itemCount = 12
        // Middle start = 500 * 12 = 6000
        // 25% threshold = 250 * 12 = 3000
        val middleStart = 500 * 12  // 6000
        val threshold = 250 * 12    // 3000

        // Drifted to 9100 (beyond threshold)
        val drift = kotlin.math.abs(9100 - middleStart)
        assertTrue("Should trigger recentering", drift > threshold)

        // At 7500 (within threshold)
        val smallDrift = kotlin.math.abs(7500 - middleStart)
        assertTrue("Should NOT trigger recentering", smallDrift <= threshold)
    }

    @Test
    fun `recenter preserves actual index`() {
        // After recentering from 9100 to middle
        val virtualIndex = 9100
        val itemCount = 12
        val actualIndex = virtualToActualIndex(virtualIndex, itemCount, isCircular = true)

        // Recenter target
        val middleStart = 500 * itemCount  // 6000
        val recenteredVirtual = middleStart + actualIndex

        // Verify actual index is preserved
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
        // If we're at virtual index V with actual index A,
        // then actualToNearestVirtualIndex(A, V, count) should return V
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
        // AM/PM list has only 2 items - circular disabled by effectiveCircular check
        // But let's test the functions directly to ensure they handle it
        val result = virtualToActualIndex(5, 2, isCircular = true)
        assertEquals(1, result)  // 5 mod 2 = 1
    }

    @Test
    fun `two item list wrap behavior`() {
        // Even with circular enabled, 2-item lists should be handled gracefully
        // From index 0 (AM) to index 1 (PM): both forward and backward are 1 step
        val result = actualToNearestVirtualIndex(
            targetActualIndex = 1,
            currentVirtualIndex = 1000,
            itemCount = 2,
            isCircular = true
        )
        assertEquals(1001, result)
    }
}
