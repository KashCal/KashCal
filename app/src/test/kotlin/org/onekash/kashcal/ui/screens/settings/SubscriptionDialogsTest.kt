package org.onekash.kashcal.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests models and constants beside the subscription dialogs: [FetchCalendarState],
 * [AccentColors], [SubscriptionColors], and the [IcsSubscriptionUiModel] fields
 * [EditSubscriptionDialog] seeds its form from. No test renders a dialog.
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
        val message = org.onekash.kashcal.ui.util.UiMessage.Literal("Network connection failed")
        val state = FetchCalendarState.Error(message)
        assertEquals(message, state.message)
    }

    @Test
    fun `FetchCalendarState types are distinct`() {
        val idle = FetchCalendarState.Idle
        val loading = FetchCalendarState.Loading
        val success = FetchCalendarState.Success("Test", 10)
        val error = FetchCalendarState.Error(
            org.onekash.kashcal.ui.util.UiMessage.Literal("Error"))

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
    fun `AccentColors success shades exist`() {
        // Success green keeps its hue; only the shade adapts to the surface.
        assertNotNull(AccentColors.SuccessLight)
        assertNotNull(AccentColors.SuccessDark)
    }

    // ==================== SubscriptionColors ====================

    @Test
    fun `SubscriptionColors default is in all colors list`() {
        assertTrue(SubscriptionColors.all.contains(SubscriptionColors.default))
    }

    @Test
    fun `SubscriptionColors all has 5 colors for single-row picker`() {
        // The palette promises five colors that fit in one row.
        assertEquals(5, SubscriptionColors.all.size)
    }

    @Test
    fun `SubscriptionColors all fit in single row`() {
        assertEquals(5, SubscriptionColors.all.size)
        assertTrue(SubscriptionColors.all.contains(SubscriptionColors.Blue))
        assertTrue(SubscriptionColors.all.contains(SubscriptionColors.Green))
        assertTrue(SubscriptionColors.all.contains(SubscriptionColors.Orange))
        assertTrue(SubscriptionColors.all.contains(SubscriptionColors.Pink))
        assertTrue(SubscriptionColors.all.contains(SubscriptionColors.Purple))
    }

    // ==================== IcsSubscriptionUiModel for EditSubscriptionDialog ====================

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
