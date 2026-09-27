package org.onekash.kashcal.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests [remindersChanged], which enables Save in the read-only attendee form once the
 * reminder set changes. Order doesn't matter (`[15, 30]` against `[30, 15]` is no change), but
 * duplicates do: the picker doesn't dedupe, so `[15, 15]` differs from `[15]`.
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
        // The picker doesn't dedupe, so two 15-min reminders differ from one.
        assertTrue(remindersChanged(listOf(15), listOf(15, 15)))
    }

    @Test
    fun `extra reminder added is changed`() {
        assertTrue(remindersChanged(listOf(15), listOf(15, 30)))
    }
}
