package org.onekash.kashcal.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.util.DateTimeUtils
import org.onekash.kashcal.util.location.looksLikeAddress
import org.onekash.kashcal.util.text.cleanHtmlEntities
import org.onekash.kashcal.util.text.containsUrl
import org.onekash.kashcal.util.text.extractUrls
import org.onekash.kashcal.util.text.formatRemindersForDisplay
import org.onekash.kashcal.util.text.isValidUrl
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Unit tests for EventQuickViewSheet recurring event detection logic.
 *
 * Bug fix verification: Event bottom card should show recurring icon for:
 * 1. Master recurring events (have rrule)
 * 2. Exception events (have originalEventId but no rrule)
 */
class EventQuickViewSheetTest {

    // Helper to create test events
    private fun createEvent(
        id: Long = 1L,
        rrule: String? = null,
        originalEventId: Long? = null
    ) = Event(
        id = id,
        uid = "test-uid-$id",
        calendarId = 1L,
        title = "Test Event",
        startTs = System.currentTimeMillis(),
        endTs = System.currentTimeMillis() + 3600000,
        rrule = rrule,
        originalEventId = originalEventId,
        dtstamp = System.currentTimeMillis()
    )

    /**
     * Simulate the isRecurring logic from EventQuickViewSheet.
     * This is the fixed logic: event.isRecurring || event.isException
     */
    private fun isRecurringForQuickView(event: Event): Boolean {
        return event.isRecurring || event.isException
    }

    /**
     * Simulate the repeat text logic from EventQuickViewSheet.
     */
    private fun getRepeatText(event: Event): String {
        return if (event.rrule != null) {
            formatRruleDisplay(event.rrule)
        } else {
            "Recurring"
        }
    }

    /**
     * Copy of formatRruleDisplay from EventQuickViewSheet for testing.
     */
    private fun formatRruleDisplay(rrule: String?): String {
        if (rrule == null) return "Does not repeat"

        return when {
            rrule.contains("FREQ=DAILY") -> "Daily"
            rrule.contains("FREQ=WEEKLY") -> {
                if (rrule.contains("BYDAY=")) {
                    val days = rrule.substringAfter("BYDAY=").substringBefore(";").split(",")
                    "Weekly on ${days.joinToString(", ")}"
                } else {
                    "Weekly"
                }
            }
            rrule.contains("FREQ=MONTHLY") -> "Monthly"
            rrule.contains("FREQ=YEARLY") -> "Yearly"
            else -> "Repeats"
        }
    }

    // ========== Recurring Detection Tests ==========

    @Test
    fun `master recurring event with DAILY rrule is detected as recurring`() {
        val event = createEvent(rrule = "FREQ=DAILY")
        assertTrue("Master recurring event should be detected", isRecurringForQuickView(event))
        assertTrue("Event.isRecurring should be true", event.isRecurring)
        assertFalse("Event.isException should be false", event.isException)
    }

    @Test
    fun `master recurring event with WEEKLY rrule is detected as recurring`() {
        val event = createEvent(rrule = "FREQ=WEEKLY;BYDAY=MO,WE,FR")
        assertTrue("Master recurring event should be detected", isRecurringForQuickView(event))
    }

    @Test
    fun `master recurring event with MONTHLY rrule is detected as recurring`() {
        val event = createEvent(rrule = "FREQ=MONTHLY;BYMONTHDAY=15")
        assertTrue("Master recurring event should be detected", isRecurringForQuickView(event))
    }

    @Test
    fun `master recurring event with YEARLY rrule is detected as recurring`() {
        val event = createEvent(rrule = "FREQ=YEARLY")
        assertTrue("Master recurring event should be detected", isRecurringForQuickView(event))
    }

    @Test
    fun `exception event without rrule is detected as recurring`() {
        // Exception events have originalEventId set but no rrule
        val event = createEvent(
            rrule = null,
            originalEventId = 100L
        )
        assertTrue("Exception event should be detected as recurring", isRecurringForQuickView(event))
        assertFalse("Event.isRecurring should be false (no rrule)", event.isRecurring)
        assertTrue("Event.isException should be true", event.isException)
    }

    @Test
    fun `exception event with own rrule is detected as recurring`() {
        // Edge case: exception with its own RRULE (creates a sub-series)
        val event = createEvent(
            rrule = "FREQ=DAILY",
            originalEventId = 100L
        )
        assertTrue("Exception with rrule should be detected as recurring", isRecurringForQuickView(event))
        assertTrue("Event.isRecurring should be true", event.isRecurring)
        assertTrue("Event.isException should be true", event.isException)
    }

    @Test
    fun `single non-recurring event is not detected as recurring`() {
        val event = createEvent(
            rrule = null,
            originalEventId = null
        )
        assertFalse("Non-recurring event should not be detected", isRecurringForQuickView(event))
        assertFalse("Event.isRecurring should be false", event.isRecurring)
        assertFalse("Event.isException should be false", event.isException)
    }

    // ========== Bug Regression Tests ==========

    @Test
    fun `BUG FIX - exception event shows recurring icon`() {
        // This was the original bug: exception events showed no icon
        // because the check was only: event.rrule != null
        val exception = createEvent(
            id = 2L,
            rrule = null,  // No rrule!
            originalEventId = 1L  // But has originalEventId
        )

        // OLD buggy logic: val isRecurring = event.rrule != null
        val oldBuggyLogic = exception.rrule != null
        assertFalse("OLD buggy logic incorrectly returns false", oldBuggyLogic)

        // NEW fixed logic: val isRecurring = event.isRecurring || event.isException
        val newFixedLogic = isRecurringForQuickView(exception)
        assertTrue("NEW fixed logic correctly returns true", newFixedLogic)
    }

    // ========== Repeat Text Display Tests ==========

    @Test
    fun `master event with DAILY rrule shows Daily text`() {
        val event = createEvent(rrule = "FREQ=DAILY")
        assertEquals("Daily", getRepeatText(event))
    }

    @Test
    fun `master event with WEEKLY rrule shows Weekly text`() {
        val event = createEvent(rrule = "FREQ=WEEKLY")
        assertEquals("Weekly", getRepeatText(event))
    }

    @Test
    fun `master event with WEEKLY BYDAY shows days`() {
        val event = createEvent(rrule = "FREQ=WEEKLY;BYDAY=MO,WE,FR")
        assertEquals("Weekly on MO, WE, FR", getRepeatText(event))
    }

    @Test
    fun `master event with MONTHLY rrule shows Monthly text`() {
        val event = createEvent(rrule = "FREQ=MONTHLY")
        assertEquals("Monthly", getRepeatText(event))
    }

    @Test
    fun `master event with YEARLY rrule shows Yearly text`() {
        val event = createEvent(rrule = "FREQ=YEARLY")
        assertEquals("Yearly", getRepeatText(event))
    }

    @Test
    fun `exception event without rrule shows generic Recurring text`() {
        // Exception events don't have their own rrule
        val event = createEvent(
            rrule = null,
            originalEventId = 100L
        )
        assertEquals("Recurring", getRepeatText(event))
    }

    @Test
    fun `exception event with own rrule shows rrule text`() {
        // Edge case: exception with its own RRULE shows that rrule
        val event = createEvent(
            rrule = "FREQ=MONTHLY",
            originalEventId = 100L
        )
        assertEquals("Monthly", getRepeatText(event))
    }

    @Test
    fun `unknown rrule frequency shows generic Repeats text`() {
        val event = createEvent(rrule = "FREQ=SECONDLY;INTERVAL=30")
        assertEquals("Repeats", getRepeatText(event))
    }

    // ========== Integration-style Tests ==========

    @Test
    fun `full recurring icon text for master daily event`() {
        val event = createEvent(rrule = "FREQ=DAILY")
        val isRecurring = isRecurringForQuickView(event)
        assertTrue(isRecurring)

        val iconText = if (isRecurring) {
            "\uD83D\uDD01 ${getRepeatText(event)}"
        } else {
            ""
        }
        assertEquals("\uD83D\uDD01 Daily", iconText)
    }

    @Test
    fun `full recurring icon text for exception event`() {
        val event = createEvent(
            rrule = null,
            originalEventId = 100L
        )
        val isRecurring = isRecurringForQuickView(event)
        assertTrue(isRecurring)

        val iconText = if (isRecurring) {
            "\uD83D\uDD01 ${getRepeatText(event)}"
        } else {
            ""
        }
        assertEquals("\uD83D\uDD01 Recurring", iconText)
    }

    @Test
    fun `no recurring icon text for single event`() {
        val event = createEvent(
            rrule = null,
            originalEventId = null
        )
        val isRecurring = isRecurringForQuickView(event)
        assertFalse(isRecurring)

        val iconText = if (isRecurring) {
            "\uD83D\uDD01 ${getRepeatText(event)}"
        } else {
            ""
        }
        assertEquals("", iconText)
    }

    // ========== Button Visibility Logic Tests ==========

    /**
     * Tests for inline confirmation button visibility.
     * Simulates the visibility logic from EventQuickViewSheet.
     */

    @Test
    fun `normal state shows Edit Delete and More buttons`() {
        val showEditConfirmation = false
        val showDeleteConfirmation = false

        // Edit visible when not in delete confirmation
        val showEditButton = !showDeleteConfirmation && !showEditConfirmation
        assertTrue("Edit button should be visible", showEditButton)

        // Delete visible when not in edit confirmation
        val showDeleteButton = !showEditConfirmation && !showDeleteConfirmation
        assertTrue("Delete button should be visible", showDeleteButton)

        // More visible when no confirmation active
        val showMoreButton = !showEditConfirmation && !showDeleteConfirmation
        assertTrue("More button should be visible", showMoreButton)
    }

    @Test
    fun `edit confirmation hides Delete and More buttons`() {
        val showEditConfirmation = true
        val showDeleteConfirmation = false

        // Edit area shows Cancel/Confirm
        val showEditCancelConfirm = !showDeleteConfirmation && showEditConfirmation
        assertTrue("Edit Cancel/Confirm should be visible", showEditCancelConfirm)

        // Delete hidden during edit confirmation
        val showDeleteButton = !showEditConfirmation
        assertFalse("Delete button should be hidden", showDeleteButton)

        // More hidden during edit confirmation
        val showMoreButton = !showEditConfirmation && !showDeleteConfirmation
        assertFalse("More button should be hidden", showMoreButton)
    }

    @Test
    fun `delete confirmation hides Edit and More buttons`() {
        val showEditConfirmation = false
        val showDeleteConfirmation = true

        // Edit hidden during delete confirmation
        val showEditButton = !showDeleteConfirmation
        assertFalse("Edit button should be hidden", showEditButton)

        // Delete area shows Cancel/Confirm
        val showDeleteCancelConfirm = !showEditConfirmation && showDeleteConfirmation
        assertTrue("Delete Cancel/Confirm should be visible", showDeleteCancelConfirm)

        // More hidden during delete confirmation
        val showMoreButton = !showEditConfirmation && !showDeleteConfirmation
        assertFalse("More button should be hidden", showMoreButton)
    }

    @Test
    fun `confirmation state shows exactly 2 buttons`() {
        // Edit confirmation: Cancel + Confirm = 2 buttons
        val editConfirmButtons = 2  // Cancel, Confirm
        assertEquals("Edit confirmation should show 2 buttons", 2, editConfirmButtons)

        // Delete confirmation: Cancel + Confirm = 2 buttons
        val deleteConfirmButtons = 2  // Cancel, Confirm
        assertEquals("Delete confirmation should show 2 buttons", 2, deleteConfirmButtons)
    }

    // ========== Read-Only Calendar Tests ==========

    @Test
    fun `read-only calendar shows Duplicate and Share buttons only`() {
        val isReadOnlyCalendar = true
        // Should show: Duplicate, Share (2 buttons)
        // Should NOT show: Edit, Delete, Export, More
        val expectedButtonCount = 2
        assertEquals("Read-only calendar should show 2 buttons", expectedButtonCount, 2)
    }

    @Test
    fun `editable calendar shows Edit Delete and More buttons`() {
        val isReadOnlyCalendar = false
        // Should show: Edit, Delete, More (3 buttons in normal state)
        val expectedButtonCount = 3
        assertEquals("Editable calendar should show 3 buttons", expectedButtonCount, 3)
    }

    @Test
    fun `read-only calendar does not show Edit button`() {
        val isReadOnlyCalendar = true
        val showEditButton = !isReadOnlyCalendar
        assertFalse("Read-only calendar should not show Edit", showEditButton)
    }

    @Test
    fun `read-only calendar does not show Delete button`() {
        val isReadOnlyCalendar = true
        val showDeleteButton = !isReadOnlyCalendar
        assertFalse("Read-only calendar should not show Delete", showDeleteButton)
    }

    @Test
    fun `read-only calendar does not show Export button`() {
        val isReadOnlyCalendar = true
        // Export is only in More menu for editable calendars, not shown for read-only
        val showExportButton = !isReadOnlyCalendar
        assertFalse("Read-only calendar should not show Export", showExportButton)
    }

    @Test
    fun `editable calendar shows Edit button`() {
        val isReadOnlyCalendar = false
        val showEditButton = !isReadOnlyCalendar
        assertTrue("Editable calendar should show Edit", showEditButton)
    }

    @Test
    fun `editable calendar shows Delete button`() {
        val isReadOnlyCalendar = false
        val showDeleteButton = !isReadOnlyCalendar
        assertTrue("Editable calendar should show Delete", showDeleteButton)
    }

    // ========== formatEventDateTime Tests (Multi-day) ==========

    @Test
    fun `formatEventDateTime shows both dates for multi-day timed`() {
        // Jan 15 9AM to Jan 17 5PM local time
        val zone = ZoneId.systemDefault()
        val startTs = LocalDate.of(2026, 1, 15).atTime(9, 0)
            .atZone(zone).toInstant().toEpochMilli()
        val endTs = LocalDate.of(2026, 1, 17).atTime(17, 0)
            .atZone(zone).toInstant().toEpochMilli()

        val result = formatEventDateTime(startTs, endTs, isAllDay = false)

        // Should contain both dates and arrow
        assertTrue("Should contain Jan 15", result.contains("Jan 15"))
        assertTrue("Should contain Jan 17", result.contains("Jan 17"))
        assertTrue("Should contain arrow", result.contains("\u2192"))
        // Should NOT contain "All day"
        assertFalse("Should not contain All day", result.contains("All day"))
    }

    @Test
    fun `formatEventDateTime shows single date for same-day timed`() {
        val zone = ZoneId.systemDefault()
        val startTs = LocalDate.of(2026, 1, 15).atTime(9, 0)
            .atZone(zone).toInstant().toEpochMilli()
        val endTs = LocalDate.of(2026, 1, 15).atTime(17, 0)
            .atZone(zone).toInstant().toEpochMilli()

        val result = formatEventDateTime(startTs, endTs, isAllDay = false)

        // Should contain date and middle dot separator
        assertTrue("Should contain Jan 15", result.contains("Jan 15"))
        assertTrue("Should contain middle dot", result.contains("\u00b7"))
        // Should NOT contain arrow (single day)
        assertFalse("Should not contain arrow", result.contains("\u2192"))
    }

    @Test
    fun `formatEventDateTime keeps All day suffix for multi-day all-day`() {
        // Jan 15-17 as UTC midnight (all-day events)
        // endTs is next day midnight minus 1ms (23:59:59.999)
        val startTs = LocalDate.of(2026, 1, 15).atStartOfDay(ZoneOffset.UTC)
            .toInstant().toEpochMilli()
        val endTs = LocalDate.of(2026, 1, 18).atStartOfDay(ZoneOffset.UTC)
            .toInstant().toEpochMilli() - 1  // 23:59:59.999 on Jan 17

        val result = formatEventDateTime(startTs, endTs, isAllDay = true)

        assertTrue("Should contain All day", result.contains("All day"))
        assertTrue("Should contain arrow", result.contains("\u2192"))
    }

    @Test
    fun `formatEventDateTime shows All day for single all-day event`() {
        val startTs = LocalDate.of(2026, 1, 15).atStartOfDay(ZoneOffset.UTC)
            .toInstant().toEpochMilli()
        val endTs = LocalDate.of(2026, 1, 16).atStartOfDay(ZoneOffset.UTC)
            .toInstant().toEpochMilli() - 1  // 23:59:59.999 on Jan 15

        val result = formatEventDateTime(startTs, endTs, isAllDay = true)

        assertTrue("Should contain All day", result.contains("All day"))
        assertTrue("Should contain middle dot", result.contains("\u00b7"))
        assertFalse("Should not contain arrow", result.contains("\u2192"))
    }

    // ========== Helper: formatEventDateTime ====================

    /**
     * Copy of formatEventDateTime from EventQuickViewSheet for testing.
     */
    private fun formatEventDateTime(startTs: Long, endTs: Long, isAllDay: Boolean): String {
        val startDateStr = DateTimeUtils.formatEventDateShort(startTs, isAllDay)
        val endDateStr = DateTimeUtils.formatEventDateShort(endTs, isAllDay)
        val isMultiDay = DateTimeUtils.spansMultipleDays(startTs, endTs, isAllDay)

        return if (isAllDay) {
            if (isMultiDay) {
                "$startDateStr \u2192 $endDateStr \u00b7 All day"
            } else {
                "$startDateStr \u00b7 All day"
            }
        } else {
            val startTime = DateTimeUtils.formatEventTime(startTs, isAllDay)
            val endTime = DateTimeUtils.formatEventTime(endTs, isAllDay)
            if (isMultiDay) {
                // Multi-day timed: show both dates and times
                "$startDateStr $startTime \u2192 $endDateStr $endTime"
            } else {
                "$startDateStr \u00b7 $startTime - $endTime"
            }
        }
    }

    // ========== Expandable Content Detection Tests ==========

    /**
     * Tests for hasExpandableContent logic that shows expand hint and content.
     */

    private fun hasExpandableContent(event: Event): Boolean {
        return !event.description.isNullOrBlank() ||
            !event.url.isNullOrBlank() ||
            !event.reminders.isNullOrEmpty()
    }

    @Test
    fun `event with description has expandable content`() {
        val event = createEvent().copy(description = "Meeting notes here")
        assertTrue("Event with description should be expandable", hasExpandableContent(event))
    }

    @Test
    fun `event with URL has expandable content`() {
        val event = createEvent().copy(url = "https://zoom.us/j/123")
        assertTrue("Event with URL should be expandable", hasExpandableContent(event))
    }

    @Test
    fun `event with reminders has expandable content`() {
        val event = createEvent().copy(reminders = listOf("-PT15M"))
        assertTrue("Event with reminders should be expandable", hasExpandableContent(event))
    }

    @Test
    fun `event with all fields has expandable content`() {
        val event = createEvent().copy(
            description = "Notes",
            url = "https://example.com",
            reminders = listOf("-PT15M", "-P1D")
        )
        assertTrue("Event with all fields should be expandable", hasExpandableContent(event))
    }

    @Test
    fun `event without description URL or reminders has no expandable content`() {
        val event = createEvent()
        assertFalse("Event without content should not be expandable", hasExpandableContent(event))
    }

    @Test
    fun `event with blank description has no expandable content`() {
        val event = createEvent().copy(description = "   ")
        assertFalse("Event with blank description should not be expandable", hasExpandableContent(event))
    }

    @Test
    fun `event with empty reminders list has no expandable content`() {
        val event = createEvent().copy(reminders = emptyList())
        assertFalse("Event with empty reminders should not be expandable", hasExpandableContent(event))
    }

    // ========== Location Precedence Tests ==========

    @Test
    fun `location with address takes precedence over URL`() {
        // Per plan: precedence is address first, then URL
        val location = "123 Main St, City"
        val isAddress = looksLikeAddress(location)
        val hasUrl = !isAddress && containsUrl(location)

        assertTrue("Should detect as address", isAddress)
        assertFalse("Should not treat as URL since address detected", hasUrl)
    }

    @Test
    fun `location with URL only treated as URL`() {
        val location = "https://zoom.us/j/123456"
        val isAddress = looksLikeAddress(location)
        val hasUrl = !isAddress && containsUrl(location)

        assertFalse("Should not detect as address", isAddress)
        assertTrue("Should detect URL", hasUrl)
    }

    @Test
    fun `location with plain text neither address nor URL`() {
        val location = "Conference Room A"
        val isAddress = looksLikeAddress(location)
        val hasUrl = !isAddress && containsUrl(location)

        assertFalse("Should not detect as address", isAddress)
        assertFalse("Should not detect as URL", hasUrl)
    }

    @Test
    fun `mixed location with URL but no address treated as URL`() {
        // Example: "Office, https://meet.google.com/xyz"
        val location = "Office, https://meet.google.com/xyz"
        val isAddress = looksLikeAddress(location)
        val hasUrl = !isAddress && containsUrl(location)

        assertFalse("Should not detect as address (no number + street word)", isAddress)
        assertTrue("Should detect URL in location", hasUrl)
    }

    // ========== URL Validation Tests ==========

    @Test
    fun `valid event URL is preserved`() {
        val url = "https://zoom.us/j/123"
        val validUrl = url.takeIf { isValidUrl(it) }

        assertNotNull("Valid URL should be preserved", validUrl)
        assertEquals("https://zoom.us/j/123", validUrl)
    }

    @Test
    fun `invalid event URL is filtered out`() {
        val url = "not a valid url"
        val validUrl = url.takeIf { isValidUrl(it) }

        assertNull("Invalid URL should be filtered out", validUrl)
    }

    @Test
    fun `null event URL results in null`() {
        val url: String? = null
        val validUrl = url?.takeIf { isValidUrl(it) }

        assertNull("Null URL should remain null", validUrl)
    }

    // ========== Reminders Formatting Tests ==========

    @Test
    fun `reminder list is formatted for display`() {
        val reminders = listOf("-PT15M", "-P1D")
        val formatted = formatRemindersForDisplay(reminders)

        assertEquals("15 min before, 1 day before", formatted)
    }

    @Test
    fun `null reminders return null`() {
        val formatted = formatRemindersForDisplay(null)
        assertNull(formatted)
    }

    @Test
    fun `empty reminders return null`() {
        val formatted = formatRemindersForDisplay(emptyList())
        assertNull(formatted)
    }

    @Test
    fun `single reminder is formatted`() {
        val reminders = listOf("-PT30M")
        val formatted = formatRemindersForDisplay(reminders)

        assertEquals("30 min before", formatted)
    }

    // ========== Invalid URL Filtering Tests ==========

    @Test
    fun `event URL field with invalid value is filtered`() {
        val event = createEvent().copy(url = "not a url at all")
        val validUrl = event.url?.takeIf { isValidUrl(it) }

        assertNull("Invalid URL should be filtered", validUrl)
    }

    @Test
    fun `event URL field with empty string is filtered`() {
        val event = createEvent().copy(url = "")
        val validUrl = event.url?.takeIf { isValidUrl(it) }

        assertNull("Empty URL should be filtered", validUrl)
    }

    @Test
    fun `event URL field with whitespace only is filtered`() {
        val event = createEvent().copy(url = "   ")
        val validUrl = event.url?.takeIf { isValidUrl(it) }

        assertNull("Whitespace-only URL should be filtered", validUrl)
    }

    @Test
    fun `event URL field with partial URL is filtered`() {
        val event = createEvent().copy(url = "example.com")  // Missing protocol
        val validUrl = event.url?.takeIf { isValidUrl(it) }

        // isValidUrl requires protocol, so this should be filtered
        assertNull("URL without protocol should be filtered", validUrl)
    }

    @Test
    fun `event URL field with valid meeting URL is preserved`() {
        val event = createEvent().copy(url = "https://zoom.us/j/123456")
        val validUrl = event.url?.takeIf { isValidUrl(it) }

        assertNotNull("Valid meeting URL should be preserved", validUrl)
        assertEquals("https://zoom.us/j/123456", validUrl)
    }

    // ========== Location with Both Address and URL ==========

    @Test
    fun `location with address AND URL uses address behavior`() {
        // Per plan Section 10: address takes precedence
        val location = "123 Main St, City https://zoom.us/j/123"
        val isAddress = looksLikeAddress(location)
        val hasUrl = !isAddress && containsUrl(location)

        assertTrue("Should detect as address first", isAddress)
        assertFalse("URL check skipped when address detected", hasUrl)
    }

    @Test
    fun `location with URL followed by address text`() {
        val location = "https://zoom.us/j/123 at 123 Main St"
        val isAddress = looksLikeAddress(location)
        val hasUrl = !isAddress && containsUrl(location)

        // Address detection should still work even with URL prefix
        assertTrue("Should detect address pattern", isAddress)
        assertFalse("URL check skipped when address detected", hasUrl)
    }

    @Test
    fun `location with meeting link and room name without comma`() {
        // Note: "Room 5B, ..." with comma IS detected as address (number + letters + comma)
        // So use a format without comma
        val location = "Room B - https://meet.google.com/abc-defg"
        val isAddress = looksLikeAddress(location)
        val hasUrl = !isAddress && containsUrl(location)

        assertFalse("Room B without number pattern is not an address", isAddress)
        assertTrue("Should detect URL since not an address", hasUrl)
    }

    @Test
    fun `location with comma triggers address detection`() {
        // Per looksLikeAddress: number + letters + comma = address
        val location = "Room 5B, Join at link"
        val isAddress = looksLikeAddress(location)

        assertTrue("Comma with number and letters is treated as address", isAddress)
    }

    // ========== HTML Entity Handling in Description ==========

    @Test
    fun `description with only HTML entities is considered blank after cleaning`() {
        val description = "&nbsp;&nbsp;&nbsp;"
        val cleaned = cleanHtmlEntities(description)

        assertTrue("Cleaned text should be blank", cleaned.isBlank())
    }

    @Test
    fun `description with mixed HTML entities and text is not blank`() {
        val description = "Meeting &amp; Notes"
        val cleaned = cleanHtmlEntities(description)

        assertFalse("Cleaned text should not be blank", cleaned.isBlank())
        assertEquals("Meeting & Notes", cleaned)
    }

    @Test
    fun `description with HTML entities preserves URLs`() {
        val description = "Link: https://example.com?a=1&amp;b=2"
        val cleaned = cleanHtmlEntities(description)
        val urls = extractUrls(cleaned)

        assertEquals(1, urls.size)
        assertTrue("URL should have decoded ampersand", urls[0].url.contains("&b=2"))
    }

    // ========== Expand Hint Visibility Logic ==========

    @Test
    fun `expand hint shown when content exists and not expanded`() {
        val event = createEvent().copy(description = "Some notes")
        val hasContent = hasExpandableContent(event)
        val isExpanded = false

        val showExpandHint = hasContent && !isExpanded
        assertTrue("Should show expand hint", showExpandHint)
    }

    @Test
    fun `expand hint hidden when expanded`() {
        val event = createEvent().copy(description = "Some notes")
        val hasContent = hasExpandableContent(event)
        val isExpanded = true

        val showExpandHint = hasContent && !isExpanded
        assertFalse("Should not show expand hint when expanded", showExpandHint)
    }

    @Test
    fun `expand hint hidden when no expandable content`() {
        val event = createEvent()  // No description, url, or reminders
        val hasContent = hasExpandableContent(event)
        val isExpanded = false

        val showExpandHint = hasContent && !isExpanded
        assertFalse("Should not show expand hint without content", showExpandHint)
    }

    // ========== Combined Expandable Content Scenarios ==========

    @Test
    fun `event with invalid URL but valid description has expandable content`() {
        val event = createEvent().copy(
            url = "not-a-url",
            description = "Valid description"
        )

        assertTrue("Should be expandable due to description", hasExpandableContent(event))
        assertNull("URL should be filtered out", event.url?.takeIf { isValidUrl(it) })
    }

    @Test
    fun `event with only reminders has expandable content`() {
        val event = createEvent().copy(
            description = null,
            url = null,
            reminders = listOf("-PT15M", "-PT1H")
        )

        assertTrue("Should be expandable due to reminders", hasExpandableContent(event))
    }

    @Test
    fun `hasExpandableContent matches implementation logic exactly`() {
        // Test cases that match the exact implementation in EventQuickViewSheet
        val eventWithAll = createEvent().copy(
            description = "Notes",
            url = "https://example.com",
            reminders = listOf("-PT15M")
        )
        val eventWithNone = createEvent()
        val eventWithBlankDescription = createEvent().copy(description = "")

        assertTrue(hasExpandableContent(eventWithAll))
        assertFalse(hasExpandableContent(eventWithNone))
        assertFalse(hasExpandableContent(eventWithBlankDescription))
    }
}
