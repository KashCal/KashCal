package org.onekash.kashcal.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for SharedComponents.
 * Tests SectionHeader, SettingsCard, ColorDot, and ColorPicker.
 *
 * Note: Composable UI tests require AndroidX Compose testing which runs as
 * instrumented tests. These unit tests verify the supporting logic and data.
 */
class SharedComponentsTest {

    // ==================== AccentColors Tests ====================

    @Test
    fun `AccentColors Green has correct value`() {
        // System green: #34C759
        assertEquals(0xFF34C759.toInt().toLong(), AccentColors.Green.value.toLong() shr 32)
    }

    @Test
    fun `AccentColors Blue has correct value`() {
        // System blue: #007AFF
        assertEquals(0xFF007AFF.toInt().toLong(), AccentColors.Blue.value.toLong() shr 32)
    }

    @Test
    fun `AccentColors iCloudBlue has correct value`() {
        // iCloud Blue: #5AC8FA
        assertEquals(0xFF5AC8FA.toInt().toLong(), AccentColors.iCloudBlue.value.toLong() shr 32)
    }

    // ==================== SubscriptionColors Tests ====================

    @Test
    fun `SubscriptionColors all contains 5 colors`() {
        assertEquals(5, SubscriptionColors.all.size)
    }

    @Test
    fun `SubscriptionColors default is Blue`() {
        assertEquals(SubscriptionColors.Blue, SubscriptionColors.default)
    }

    @Test
    fun `SubscriptionColors all contains default color`() {
        assertTrue(SubscriptionColors.all.contains(SubscriptionColors.default))
    }

    @Test
    fun `SubscriptionColors all colors are unique`() {
        val uniqueColors = SubscriptionColors.all.toSet()
        assertEquals(SubscriptionColors.all.size, uniqueColors.size)
    }

    // ==================== SyncIntervalOption Tests ====================

    @Test
    fun `subscriptionSyncIntervalOptions has 5 options`() {
        assertEquals(5, subscriptionSyncIntervalOptions.size)
    }

    @Test
    fun `subscriptionSyncIntervalOptions includes hourly`() {
        val hourly = subscriptionSyncIntervalOptions.find { it.hours == 1 }
        assertNotNull(hourly)
        assertEquals("Every hour", hourly?.label)
    }

    @Test
    fun `subscriptionSyncIntervalOptions includes daily`() {
        val daily = subscriptionSyncIntervalOptions.find { it.hours == 24 }
        assertNotNull(daily)
        assertEquals("Daily", daily?.label)
    }

    @Test
    fun `subscriptionSyncIntervalOptions includes weekly`() {
        val weekly = subscriptionSyncIntervalOptions.find { it.hours == 168 }
        assertNotNull(weekly)
        assertEquals("Weekly", weekly?.label)
    }

    @Test
    fun `getSyncIntervalLabel returns correct label for known hours`() {
        assertEquals("Every hour", getSyncIntervalLabel(1))
        assertEquals("Every 6 hours", getSyncIntervalLabel(6))
        assertEquals("Every 12 hours", getSyncIntervalLabel(12))
        assertEquals("Daily", getSyncIntervalLabel(24))
        assertEquals("Weekly", getSyncIntervalLabel(168))
    }

    @Test
    fun `getSyncIntervalLabel returns fallback for unknown hours`() {
        assertEquals("Every 3 hours", getSyncIntervalLabel(3))
        assertEquals("Every 48 hours", getSyncIntervalLabel(48))
    }

    // ==================== URL Validation Tests ====================

    @Test
    fun `validateSubscriptionUrl returns error for blank URL`() {
        assertEquals("URL is required", validateSubscriptionUrl(""))
        assertEquals("URL is required", validateSubscriptionUrl("   "))
    }

    @Test
    fun `validateSubscriptionUrl returns error for invalid protocol`() {
        val error = validateSubscriptionUrl("ftp://example.com/calendar.ics")
        assertNotNull(error)
        assertTrue(error!!.contains("http"))
    }

    @Test
    fun `validateSubscriptionUrl accepts https URLs`() {
        val error = validateSubscriptionUrl("https://example.com/calendar.ics")
        assertEquals(null, error)
    }

    @Test
    fun `validateSubscriptionUrl accepts webcal URLs`() {
        val error = validateSubscriptionUrl("webcal://example.com/calendar.ics")
        assertEquals(null, error)
    }

    @Test
    fun `normalizeSubscriptionUrl converts webcal to https`() {
        assertEquals(
            "https://example.com/calendar.ics",
            normalizeSubscriptionUrl("webcal://example.com/calendar.ics")
        )
    }

    @Test
    fun `normalizeSubscriptionUrl converts webcals to https`() {
        assertEquals(
            "https://example.com/calendar.ics",
            normalizeSubscriptionUrl("webcals://example.com/calendar.ics")
        )
    }

    @Test
    fun `normalizeSubscriptionUrl preserves https URLs`() {
        assertEquals(
            "https://example.com/calendar.ics",
            normalizeSubscriptionUrl("https://example.com/calendar.ics")
        )
    }

    @Test
    fun `normalizeSubscriptionUrl trims whitespace`() {
        assertEquals(
            "https://example.com/calendar.ics",
            normalizeSubscriptionUrl("  https://example.com/calendar.ics  ")
        )
    }
}
