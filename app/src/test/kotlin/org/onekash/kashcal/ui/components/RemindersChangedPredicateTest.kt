package org.onekash.kashcal.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [remindersChanged] — the Save-enabled predicate used by
 * EventFormSheet's read-only mode. Sorted-list comparison: order doesn't
 * matter (e.g., picking [15, 30] then re-ordering to [30, 15] is no
 * change), but duplicates DO matter ([15, 15] differs from [15] —
 * matches the picker's non-deduping behavior).
 */
class RemindersChangedPredicateTest {

    @Test
    fun `same single value is unchanged`() {
        assertFalse(remindersChanged(listOf(15), listOf(15)))
    }

    @Test
    fun `same set in different order is unchanged`() {
        assertFalse(remindersChanged(listOf(15, 30), listOf(30, 15)))
    }

    @Test
    fun `empty equals empty`() {
        assertFalse(remindersChanged(emptyList(), emptyList()))
    }

    @Test
    fun `empty to single is changed`() {
        assertTrue(remindersChanged(emptyList(), listOf(15)))
    }

    @Test
    fun `single to empty is changed (user removed reminder)`() {
        assertTrue(remindersChanged(listOf(15), emptyList()))
    }

    @Test
    fun `different value is changed`() {
        assertTrue(remindersChanged(listOf(15), listOf(30)))
    }

    @Test
    fun `duplicate added is changed`() {
        // Picker doesn't dedupe — two 15-min reminders are distinct from one.
        assertTrue(remindersChanged(listOf(15), listOf(15, 15)))
    }

    @Test
    fun `extra reminder added is changed`() {
        assertTrue(remindersChanged(listOf(15), listOf(15, 30)))
    }
}
