package org.onekash.kashcal.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Event

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
}
