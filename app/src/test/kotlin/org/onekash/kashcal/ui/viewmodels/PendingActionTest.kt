package org.onekash.kashcal.ui.viewmodels

import android.net.Uri
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.onekash.kashcal.util.CalendarIntentData
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for PendingAction sealed class and its variants.
 *
 * Tests verify:
 * - Data class equality and copy behavior
 * - All action types are properly constructed
 * - Source enum for ShowEventQuickView
 * - Deep link navigation intent handling
 *
 * These actions are triggered by:
 * - Notification clicks (reminder tap, snooze)
 * - Widget event taps
 * - App shortcuts (create event, go to today)
 * - ICS file imports
 */
class PendingActionTest {

    // ==================== ShowEventQuickView Tests ====================

    @Test
    fun `ShowEventQuickView stores eventId and occurrenceTs`() {
        val action = PendingAction.ShowEventQuickView(
            eventId = 123L,
            occurrenceTs = 1704067200000L,
            source = PendingAction.ShowEventQuickView.Source.REMINDER
        )

        assertEquals(123L, action.eventId)
        assertEquals(1704067200000L, action.occurrenceTs)
        assertEquals(PendingAction.ShowEventQuickView.Source.REMINDER, action.source)
    }

    @Test
    fun `ShowEventQuickView Source REMINDER is correctly identified`() {
        val action = PendingAction.ShowEventQuickView(
            eventId = 1L,
            occurrenceTs = 0L,
            source = PendingAction.ShowEventQuickView.Source.REMINDER
        )

        assertEquals(PendingAction.ShowEventQuickView.Source.REMINDER, action.source)
    }

    @Test
    fun `ShowEventQuickView Source WIDGET is correctly identified`() {
        val action = PendingAction.ShowEventQuickView(
            eventId = 1L,
            occurrenceTs = 0L,
            source = PendingAction.ShowEventQuickView.Source.WIDGET
        )

        assertEquals(PendingAction.ShowEventQuickView.Source.WIDGET, action.source)
    }

    @Test
    fun `ShowEventQuickView equality works correctly`() {
        val action1 = PendingAction.ShowEventQuickView(
            eventId = 123L,
            occurrenceTs = 1000L,
            source = PendingAction.ShowEventQuickView.Source.REMINDER
        )
        val action2 = PendingAction.ShowEventQuickView(
            eventId = 123L,
            occurrenceTs = 1000L,
            source = PendingAction.ShowEventQuickView.Source.REMINDER
        )
        val action3 = PendingAction.ShowEventQuickView(
            eventId = 456L,
            occurrenceTs = 1000L,
            source = PendingAction.ShowEventQuickView.Source.REMINDER
        )

        assertEquals(action1, action2)
        assertFalse(action1 == action3)
    }

    @Test
    fun `ShowEventQuickView copy works correctly`() {
        val original = PendingAction.ShowEventQuickView(
            eventId = 123L,
            occurrenceTs = 1000L,
            source = PendingAction.ShowEventQuickView.Source.REMINDER
        )

        val copied = original.copy(eventId = 456L)

        assertEquals(456L, copied.eventId)
        assertEquals(1000L, copied.occurrenceTs)
        assertEquals(PendingAction.ShowEventQuickView.Source.REMINDER, copied.source)
    }

    // ==================== CreateEvent Tests ====================

    @Test
    fun `CreateEvent stores startTs correctly`() {
        val startTs = 1704067200L
        val action = PendingAction.CreateEvent(startTs = startTs)

        assertEquals(startTs, action.startTs)
    }

    @Test
    fun `CreateEvent allows null startTs`() {
        val action = PendingAction.CreateEvent(startTs = null)

        assertNull(action.startTs)
    }

    @Test
    fun `CreateEvent default startTs is null`() {
        val action = PendingAction.CreateEvent()

        assertNull(action.startTs)
    }

    @Test
    fun `CreateEvent equality works correctly`() {
        val action1 = PendingAction.CreateEvent(startTs = 1000L)
        val action2 = PendingAction.CreateEvent(startTs = 1000L)
        val action3 = PendingAction.CreateEvent(startTs = 2000L)

        assertEquals(action1, action2)
        assertFalse(action1 == action3)
    }

    @Test
    fun `CreateEvent with null equals another with null`() {
        val action1 = PendingAction.CreateEvent(startTs = null)
        val action2 = PendingAction.CreateEvent(startTs = null)

        assertEquals(action1, action2)
    }

    // ==================== OpenSearch Tests ====================

    @Test
    fun `OpenSearch is singleton object`() {
        val action1 = PendingAction.OpenSearch
        val action2 = PendingAction.OpenSearch

        assertTrue(action1 === action2)
    }

    @Test
    fun `OpenSearch is instance of PendingAction`() {
        val action: PendingAction = PendingAction.OpenSearch

        assertTrue(action is PendingAction.OpenSearch)
    }

    // ==================== GoToToday Tests ====================

    @Test
    fun `GoToToday is singleton object`() {
        val action1 = PendingAction.GoToToday
        val action2 = PendingAction.GoToToday

        assertTrue(action1 === action2)
    }

    @Test
    fun `GoToToday is instance of PendingAction`() {
        val action: PendingAction = PendingAction.GoToToday

        assertTrue(action is PendingAction.GoToToday)
    }

    // ==================== ImportIcsFile Tests ====================

    @Test
    fun `ImportIcsFile stores uri correctly`() {
        val mockUri = mockk<Uri>(relaxed = true)
        val action = PendingAction.ImportIcsFile(uri = mockUri)

        assertEquals(mockUri, action.uri)
    }

    @Test
    fun `ImportIcsFile equality works correctly`() {
        val mockUri = mockk<Uri>(relaxed = true)
        val action1 = PendingAction.ImportIcsFile(uri = mockUri)
        val action2 = PendingAction.ImportIcsFile(uri = mockUri)

        assertEquals(action1, action2)
    }

    // ==================== Type Checking Tests ====================

    @Test
    fun `when expression matches ShowEventQuickView`() {
        val action: PendingAction = PendingAction.ShowEventQuickView(
            eventId = 1L,
            occurrenceTs = 0L,
            source = PendingAction.ShowEventQuickView.Source.REMINDER
        )

        val result = when (action) {
            is PendingAction.ShowEventQuickView -> "quick_view"
            is PendingAction.CreateEvent -> "create"
            is PendingAction.OpenSearch -> "search"
            is PendingAction.GoToToday -> "today"
            is PendingAction.ImportIcsFile -> "import"
            is PendingAction.CreateEventFromCalendarIntent -> "calendar_intent"
        }

        assertEquals("quick_view", result)
    }

    @Test
    fun `when expression matches CreateEvent`() {
        val action: PendingAction = PendingAction.CreateEvent(startTs = 1000L)

        val result = when (action) {
            is PendingAction.ShowEventQuickView -> "quick_view"
            is PendingAction.CreateEvent -> "create"
            is PendingAction.OpenSearch -> "search"
            is PendingAction.GoToToday -> "today"
            is PendingAction.ImportIcsFile -> "import"
            is PendingAction.CreateEventFromCalendarIntent -> "calendar_intent"
        }

        assertEquals("create", result)
    }

    @Test
    fun `when expression matches OpenSearch`() {
        val action: PendingAction = PendingAction.OpenSearch

        val result = when (action) {
            is PendingAction.ShowEventQuickView -> "quick_view"
            is PendingAction.CreateEvent -> "create"
            is PendingAction.OpenSearch -> "search"
            is PendingAction.GoToToday -> "today"
            is PendingAction.ImportIcsFile -> "import"
            is PendingAction.CreateEventFromCalendarIntent -> "calendar_intent"
        }

        assertEquals("search", result)
    }

    @Test
    fun `when expression matches GoToToday`() {
        val action: PendingAction = PendingAction.GoToToday

        val result = when (action) {
            is PendingAction.ShowEventQuickView -> "quick_view"
            is PendingAction.CreateEvent -> "create"
            is PendingAction.OpenSearch -> "search"
            is PendingAction.GoToToday -> "today"
            is PendingAction.ImportIcsFile -> "import"
            is PendingAction.CreateEventFromCalendarIntent -> "calendar_intent"
        }

        assertEquals("today", result)
    }

    @Test
    fun `when expression matches ImportIcsFile`() {
        val mockUri = mockk<Uri>(relaxed = true)
        val action: PendingAction = PendingAction.ImportIcsFile(uri = mockUri)

        val result = when (action) {
            is PendingAction.ShowEventQuickView -> "quick_view"
            is PendingAction.CreateEvent -> "create"
            is PendingAction.OpenSearch -> "search"
            is PendingAction.GoToToday -> "today"
            is PendingAction.ImportIcsFile -> "import"
            is PendingAction.CreateEventFromCalendarIntent -> "calendar_intent"
        }

        assertEquals("import", result)
    }

    @Test
    fun `when expression matches CreateEventFromCalendarIntent`() {
        val intentData = CalendarIntentData(
            title = "Meeting",
            startTimeMillis = 1704067200000L
        )
        val action: PendingAction = PendingAction.CreateEventFromCalendarIntent(
            data = intentData,
            invitees = listOf("alice@example.com")
        )

        val result = when (action) {
            is PendingAction.ShowEventQuickView -> "quick_view"
            is PendingAction.CreateEvent -> "create"
            is PendingAction.OpenSearch -> "search"
            is PendingAction.GoToToday -> "today"
            is PendingAction.ImportIcsFile -> "import"
            is PendingAction.CreateEventFromCalendarIntent -> "calendar_intent"
        }

        assertEquals("calendar_intent", result)
    }

    // ==================== Source Enum Tests ====================

    @Test
    fun `Source enum has correct values`() {
        val values = PendingAction.ShowEventQuickView.Source.values()

        assertEquals(2, values.size)
        assertTrue(values.contains(PendingAction.ShowEventQuickView.Source.REMINDER))
        assertTrue(values.contains(PendingAction.ShowEventQuickView.Source.WIDGET))
    }

    @Test
    fun `Source enum valueOf works correctly`() {
        val reminder = PendingAction.ShowEventQuickView.Source.valueOf("REMINDER")
        val widget = PendingAction.ShowEventQuickView.Source.valueOf("WIDGET")

        assertEquals(PendingAction.ShowEventQuickView.Source.REMINDER, reminder)
        assertEquals(PendingAction.ShowEventQuickView.Source.WIDGET, widget)
    }
}