package org.onekash.kashcal.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for SubscriptionComponents.
 * Tests color palette, sync intervals, and URL validation.
 */
class SubscriptionComponentsTest {

    // ==================== SubscriptionColors Tests ====================

    @Test
    fun `SubscriptionColors has 5 colors`() {
        assertEquals(5, SubscriptionColors.all.size)
    }

    @Test
    fun `SubscriptionColors default is Blue`() {
        assertEquals(SubscriptionColors.Blue, SubscriptionColors.default)
    }

    @Test
    fun `SubscriptionColors all contains expected colors`() {
        assertTrue(SubscriptionColors.all.contains(SubscriptionColors.Blue))
        assertTrue(SubscriptionColors.all.contains(SubscriptionColors.Green))
        assertTrue(SubscriptionColors.all.contains(SubscriptionColors.Orange))
        assertTrue(SubscriptionColors.all.contains(SubscriptionColors.Pink))
        assertTrue(SubscriptionColors.all.contains(SubscriptionColors.Purple))
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
    fun `subscriptionSyncIntervalOptions are in ascending order`() {
        val hours = subscriptionSyncIntervalOptions.map { it.hours }
        assertEquals(hours.sorted(), hours)
    }

    // ==================== getSyncIntervalLabel Tests ====================

    @Test
    fun `getSyncIntervalLabel returns label for known interval`() {
        assertEquals("Every hour", getSyncIntervalLabel(1))
        assertEquals("Daily", getSyncIntervalLabel(24))
        assertEquals("Weekly", getSyncIntervalLabel(168))
    }

    @Test
    fun `getSyncIntervalLabel returns default format for unknown interval`() {
        assertEquals("Every 2 hours", getSyncIntervalLabel(2))
        assertEquals("Every 48 hours", getSyncIntervalLabel(48))
    }

    // ==================== validateSubscriptionUrl Tests ====================

    @Test
    fun `validateSubscriptionUrl returns error for blank URL`() {
        assertNotNull(validateSubscriptionUrl(""))
        assertNotNull(validateSubscriptionUrl("   "))
    }

    @Test
    fun `validateSubscriptionUrl returns error for invalid protocol`() {
        assertNotNull(validateSubscriptionUrl("ftp://example.com/cal.ics"))
        assertNotNull(validateSubscriptionUrl("file:///calendar.ics"))
    }

    @Test
    fun `validateSubscriptionUrl accepts http URLs`() {
        assertNull(validateSubscriptionUrl("http://example.com/calendar.ics"))
    }

    @Test
    fun `validateSubscriptionUrl accepts https URLs`() {
        assertNull(validateSubscriptionUrl("https://example.com/calendar.ics"))
    }

    @Test
    fun `validateSubscriptionUrl accepts webcal URLs`() {
        assertNull(validateSubscriptionUrl("webcal://example.com/calendar.ics"))
    }

    @Test
    fun `validateSubscriptionUrl trims whitespace`() {
        assertNull(validateSubscriptionUrl("  https://example.com/cal.ics  "))
    }

    // ==================== normalizeSubscriptionUrl Tests ====================

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
    fun `normalizeSubscriptionUrl keeps https unchanged`() {
        assertEquals(
            "https://example.com/calendar.ics",
            normalizeSubscriptionUrl("https://example.com/calendar.ics")
        )
    }

    @Test
    fun `normalizeSubscriptionUrl keeps http unchanged`() {
        assertEquals(
            "http://example.com/calendar.ics",
            normalizeSubscriptionUrl("http://example.com/calendar.ics")
        )
    }

    @Test
    fun `normalizeSubscriptionUrl trims whitespace`() {
        assertEquals(
            "https://example.com/cal.ics",
            normalizeSubscriptionUrl("  https://example.com/cal.ics  ")
        )
    }

    // ==================== Integration Tests ====================

    @Test
    fun `IcsSubscriptionUiModel works with SubscriptionColors`() {
        val subscription = IcsSubscriptionUiModel(
            id = 1L,
            url = "https://example.com/cal.ics",
            name = "Test",
            color = SubscriptionColors.Green
        )

        assertEquals(SubscriptionColors.Green, subscription.color)
        assertTrue(SubscriptionColors.all.contains(subscription.color))
    }

    @Test
    fun `IcsSubscriptionUiModel works with sync interval options`() {
        val subscription = IcsSubscriptionUiModel(
            id = 1L,
            url = "https://example.com/cal.ics",
            name = "Test",
            color = SubscriptionColors.Blue,
            syncIntervalHours = 24
        )

        val label = getSyncIntervalLabel(subscription.syncIntervalHours)
        assertEquals("Daily", label)
    }

    @Test
    fun `full subscription workflow`() {
        // 1. Validate URL
        val url = "webcal://p80-caldav.icloud.com/published/2/123456789"
        assertNull(validateSubscriptionUrl(url))

        // 2. Normalize URL for HTTP
        val normalizedUrl = normalizeSubscriptionUrl(url)
        assertTrue(normalizedUrl.startsWith("https://"))

        // 3. Create subscription with default color
        val subscription = IcsSubscriptionUiModel(
            id = null,
            url = normalizedUrl,
            name = "Shared Calendar",
            color = SubscriptionColors.default
        )

        // 4. Verify defaults
        assertEquals(SubscriptionColors.Blue, subscription.color)
        assertEquals(24, subscription.syncIntervalHours)
        assertTrue(subscription.enabled)
        assertNull(subscription.id)
    }
}
