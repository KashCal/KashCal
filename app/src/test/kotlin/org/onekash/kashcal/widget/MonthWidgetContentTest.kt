package org.onekash.kashcal.widget

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class MonthWidgetContentTest {

    private lateinit var originalLocale: Locale

    @Before
    fun setUp() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
    }

    // ==================== extractDotColors ====================

    @Test
    fun `extractDotColors returns empty list for no events`() {
        assertEquals(emptyList<Int>(), extractDotColors(emptyList()))
    }

    @Test
    fun `extractDotColors returns single color for one event`() {
        val events = listOf(createWidgetEvent(calendarColor = 0xFF0000))
        assertEquals(listOf(0xFF0000), extractDotColors(events))
    }

    @Test
    fun `extractDotColors returns unique colors from multiple events`() {
        val events = listOf(
            createWidgetEvent(calendarColor = 0xFF0000),
            createWidgetEvent(calendarColor = 0x00FF00),
            createWidgetEvent(calendarColor = 0x0000FF)
        )
        assertEquals(listOf(0xFF0000, 0x00FF00, 0x0000FF), extractDotColors(events))
    }

    @Test
    fun `extractDotColors caps at maxDots default 3`() {
        val events = listOf(
            createWidgetEvent(calendarColor = 0xFF0000),
            createWidgetEvent(calendarColor = 0x00FF00),
            createWidgetEvent(calendarColor = 0x0000FF),
            createWidgetEvent(calendarColor = 0xFFFF00),
            createWidgetEvent(calendarColor = 0xFF00FF)
        )
        assertEquals(3, extractDotColors(events).size)
        assertEquals(listOf(0xFF0000, 0x00FF00, 0x0000FF), extractDotColors(events))
    }

    @Test
    fun `extractDotColors deduplicates same color`() {
        val events = listOf(
            createWidgetEvent(calendarColor = 0xFF0000),
            createWidgetEvent(calendarColor = 0xFF0000),
            createWidgetEvent(calendarColor = 0x00FF00)
        )
        assertEquals(listOf(0xFF0000, 0x00FF00), extractDotColors(events))
    }

    @Test
    fun `extractDotColors with custom maxDots`() {
        val events = listOf(
            createWidgetEvent(calendarColor = 0xFF0000),
            createWidgetEvent(calendarColor = 0x00FF00),
            createWidgetEvent(calendarColor = 0x0000FF)
        )
        assertEquals(listOf(0xFF0000, 0x00FF00), extractDotColors(events, maxDots = 2))
    }

    // ==================== getDayOfWeekHeaders ====================

    @Test
    fun `getDayOfWeekHeaders Sunday start returns Sun first`() {
        val headers = getDayOfWeekHeaders(Calendar.SUNDAY)
        assertEquals(7, headers.size)
        assertEquals("Sun", headers[0])
        assertEquals("Mon", headers[1])
        assertEquals("Sat", headers[6])
    }

    @Test
    fun `getDayOfWeekHeaders Monday start returns Mon first`() {
        val headers = getDayOfWeekHeaders(Calendar.MONDAY)
        assertEquals(7, headers.size)
        assertEquals("Mon", headers[0])
        assertEquals("Tue", headers[1])
        assertEquals("Sun", headers[6])
    }

    // ==================== buildAccessibilityDescription ====================

    @Test
    fun `buildAccessibilityDescription singular event`() {
        val desc = buildAccessibilityDescription(2026, 2, 15, 1) // March (0-indexed)
        assertEquals("March 15, 1 event", desc)
    }

    @Test
    fun `buildAccessibilityDescription plural events`() {
        val desc = buildAccessibilityDescription(2026, 2, 15, 3) // March
        assertEquals("March 15, 3 events", desc)
    }

    @Test
    fun `buildAccessibilityDescription zero events`() {
        val desc = buildAccessibilityDescription(2026, 2, 15, 0)
        assertEquals("March 15, no events", desc)
    }

    private fun createWidgetEvent(
        calendarColor: Int = 0xFF2196F3.toInt()
    ): WidgetDataRepository.WidgetEvent {
        return WidgetDataRepository.WidgetEvent(
            eventId = 1L,
            occurrenceStartTs = 1000L,
            title = "Test",
            startTs = 1000L,
            endTs = 2000L,
            isAllDay = false,
            calendarColor = calendarColor,
            isPast = false,
            isDeviceEvent = false
        )
    }
}
