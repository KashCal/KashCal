package org.onekash.kashcal.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-logic tests for [formatBadgeCount]. The helper is the single
 * source of truth for badge text shown on both the AppBar rail toggle
 * and the right-rail Invites icon, so the rule must be unambiguous:
 * non-positive counts hide the badge, 1..99 render verbatim, anything
 * larger renders the capped "99+" overflow string.
 */
class BadgeCountFormatterTest {

    @Test
    fun zero_returnsNull() {
        assertNull(formatBadgeCount(0))
    }

    @Test
    fun negative_returnsNull() {
        assertNull(formatBadgeCount(-3))
    }

    @Test
    fun one_returnsSingleDigit() {
        assertEquals("1", formatBadgeCount(1))
    }

    @Test
    fun ninetyNine_returnsTwoDigits() {
        assertEquals("99", formatBadgeCount(99))
    }

    @Test
    fun oneHundred_returnsCappedString() {
        assertEquals("99+", formatBadgeCount(100))
    }

    @Test
    fun largeCount_returnsCappedString() {
        assertEquals("99+", formatBadgeCount(9999))
    }
}
