package org.onekash.kashcal.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for SubscriptionDialogs.
 *
 * Note: Composable UI interaction tests require AndroidX Compose testing
 * which runs as instrumented tests. These unit tests verify the supporting
 * logic and data used by the dialogs.
 */
class SubscriptionDialogsTest {

    // ==================== FetchCalendarState Tests ====================

    @Test
    fun `FetchCalendarState Idle is singleton`() {
        val state1 = FetchCalendarState.Idle
        val state2 = FetchCalendarState.Idle
        assertEquals(state1, state2)
    }

    @Test
    fun `FetchCalendarState Loading is singleton`() {
        val state1 = FetchCalendarState.Loading
        val state2 = FetchCalendarState.Loading
        assertEquals(state1, state2)
    }

    @Test
    fun `FetchCalendarState Success stores name and event count`() {
        val state = FetchCalendarState.Success(
            name = "Work Calendar",
            eventCount = 42
        )
        assertEquals("Work Calendar", state.name)
        assertEquals(42, state.eventCount)
    }

    @Test
    fun `FetchCalendarState Error stores message`() {
        val state = FetchCalendarState.Error("Network connection failed")
        assertEquals("Network connection failed", state.message)
    }

    @Test
    fun `FetchCalendarState types are distinct`() {
        val idle = FetchCalendarState.Idle
        val loading = FetchCalendarState.Loading
        val success = FetchCalendarState.Success("Test", 10)
        val error = FetchCalendarState.Error("Error")

        assertTrue(idle is FetchCalendarState.Idle)
        assertTrue(loading is FetchCalendarState.Loading)
        assertTrue(success is FetchCalendarState.Success)
        assertTrue(error is FetchCalendarState.Error)

        assertFalse(idle is FetchCalendarState.Loading)
        assertFalse(loading is FetchCalendarState.Success)
        assertFalse(success is FetchCalendarState.Error)
    }

    // ==================== AccentColors Tests ====================

    @Test
    fun `AccentColors Green exists`() {
        // System green: #34C759
        assertNotNull(AccentColors.Green)
    }

    @Test
    fun `AccentColors Blue exists`() {
        // System blue: #007AFF
        assertNotNull(AccentColors.Blue)
    }

    @Test
    fun `AccentColors iCloudBlue exists`() {
        // iCloud Blue: #5AC8FA
        assertNotNull(AccentColors.iCloudBlue)
    }

    // ==================== SyncInterval Integration Tests ====================

    @Test
    fun `getSyncIntervalLabel works for all standard intervals`() {
        assertEquals("Every hour", getSyncIntervalLabel(1))
        assertEquals("Every 6 hours", getSyncIntervalLabel(6))
        assertEquals("Every 12 hours", getSyncIntervalLabel(12))
        assertEquals("Daily", getSyncIntervalLabel(24))
        assertEquals("Weekly", getSyncIntervalLabel(168))
    }

    @Test
    fun `getSyncIntervalLabel returns fallback for non-standard interval`() {
        val result = getSyncIntervalLabel(3)
        assertTrue(result.contains("3"))
    }

    // ==================== SubscriptionColors Usage Tests ====================

    @Test
    fun `SubscriptionColors default is in all colors list`() {
        assertTrue(SubscriptionColors.all.contains(SubscriptionColors.default))
    }

    @Test
    fun `SubscriptionColors all has 5 colors for single-row picker`() {
        // Dialog uses 5 colors in single row
        assertEquals(5, SubscriptionColors.all.size)
    }

    @Test
    fun `SubscriptionColors all fit in single row`() {
        // Verify all 5 colors for single-row display
        assertEquals(5, SubscriptionColors.all.size)
        assertTrue(SubscriptionColors.all.contains(SubscriptionColors.Blue))
        assertTrue(SubscriptionColors.all.contains(SubscriptionColors.Green))
        assertTrue(SubscriptionColors.all.contains(SubscriptionColors.Orange))
        assertTrue(SubscriptionColors.all.contains(SubscriptionColors.Pink))
        assertTrue(SubscriptionColors.all.contains(SubscriptionColors.Purple))
    }

    // ==================== URL Validation for Dialogs ====================

    @Test
    fun `AddSubscriptionDialog accepts https URLs`() {
        val error = validateSubscriptionUrl("https://calendar.example.com/feed.ics")
        assertEquals(null, error)
    }

    @Test
    fun `AddSubscriptionDialog accepts webcal URLs`() {
        val error = validateSubscriptionUrl("webcal://calendar.example.com/feed.ics")
        assertEquals(null, error)
    }

    @Test
    fun `AddSubscriptionDialog rejects empty URL`() {
        val error = validateSubscriptionUrl("")
        assertNotNull(error)
    }

    // ==================== IcsSubscriptionUiModel for EditDialog ====================

    @Test
    fun `EditSubscriptionDialog uses subscription name`() {
        val subscription = IcsSubscriptionUiModel(
            id = 1L,
            url = "https://example.com/cal.ics",
            name = "My Calendar",
            color = SubscriptionColors.Blue
        )
        assertEquals("My Calendar", subscription.name)
    }

    @Test
    fun `EditSubscriptionDialog uses subscription color`() {
        val subscription = IcsSubscriptionUiModel(
            id = 1L,
            url = "https://example.com/cal.ics",
            name = "Test",
            color = SubscriptionColors.Green
        )
        assertEquals(SubscriptionColors.Green, subscription.color)
    }

    @Test
    fun `EditSubscriptionDialog uses sync interval hours`() {
        val subscription = IcsSubscriptionUiModel(
            id = 1L,
            url = "https://example.com/cal.ics",
            name = "Test",
            color = SubscriptionColors.Blue,
            syncIntervalHours = 6
        )
        assertEquals(6, subscription.syncIntervalHours)
    }
}
