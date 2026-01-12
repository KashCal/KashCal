package org.onekash.kashcal.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.ui.shared.REMINDER_OFF
import org.onekash.kashcal.ui.components.pickers.DateSelectionMode

/**
 * Unit tests for EventFormSheet change detection and dismiss logic.
 *
 * Tests the hasChanges logic and dismiss confirmation behavior for:
 * - Cancel button
 * - Swipe-to-dismiss gestures
 * - Scrim tap / back button
 */
class EventFormSheetTest {

    // ========== Change Detection Tests ==========

    /**
     * Simulates the hasChanges derivedStateOf logic from EventFormSheet.
     * This is extracted for testability.
     */
    private fun hasChanges(state: EventFormState, initial: EventFormState?): Boolean {
        val initialState = initial ?: return false
        return state.title != initialState.title ||
            state.dateMillis != initialState.dateMillis ||
            state.endDateMillis != initialState.endDateMillis ||
            state.startHour != initialState.startHour ||
            state.startMinute != initialState.startMinute ||
            state.endHour != initialState.endHour ||
            state.endMinute != initialState.endMinute ||
            state.selectedCalendarId != initialState.selectedCalendarId ||
            state.isAllDay != initialState.isAllDay ||
            state.location != initialState.location ||
            state.description != initialState.description ||
            state.reminder1Minutes != initialState.reminder1Minutes ||
            state.reminder2Minutes != initialState.reminder2Minutes ||
            state.rrule != initialState.rrule
    }

    /**
     * Simulates the dismiss confirmation logic from confirmValueChange and onDismissRequest.
     * Returns: Pair(shouldDismiss, shouldShowConfirm)
     */
    private fun evaluateDismissAttempt(
        hasChanges: Boolean,
        showDiscardConfirm: Boolean,
        isSaving: Boolean
    ): Pair<Boolean, Boolean> {
        return when {
            isSaving -> Pair(false, false) // Block while saving
            !hasChanges -> Pair(true, false) // Allow if no changes
            showDiscardConfirm -> Pair(true, false) // Allow if already confirmed
            else -> Pair(false, true) // Block and show confirm
        }
    }

    // ========== hasChanges: No Changes Scenarios ==========

    @Test
    fun `hasChanges returns false when initial state is null`() {
        val state = EventFormState()
        assertFalse(hasChanges(state, null))
    }

    @Test
    fun `hasChanges returns false when state equals initial`() {
        val initial = EventFormState(
            title = "Test Event",
            dateMillis = 1000000L,
            reminder1Minutes = 15,
            reminder2Minutes = REMINDER_OFF
        )
        val state = initial.copy()
        assertFalse(hasChanges(state, initial))
    }

    // ========== hasChanges: Essential Field Changes ==========

    @Test
    fun `hasChanges returns true when title changes`() {
        val initial = EventFormState(title = "Original")
        val state = initial.copy(title = "Modified")
        assertTrue(hasChanges(state, initial))
    }

    @Test
    fun `hasChanges returns true when dateMillis changes`() {
        val initial = EventFormState(dateMillis = 1000000L)
        val state = initial.copy(dateMillis = 2000000L)
        assertTrue(hasChanges(state, initial))
    }

    @Test
    fun `hasChanges returns true when endDateMillis changes`() {
        val initial = EventFormState(endDateMillis = 1000000L)
        val state = initial.copy(endDateMillis = 2000000L)
        assertTrue(hasChanges(state, initial))
    }

    @Test
    fun `hasChanges returns true when startHour changes`() {
        val initial = EventFormState(startHour = 9)
        val state = initial.copy(startHour = 10)
        assertTrue(hasChanges(state, initial))
    }

    @Test
    fun `hasChanges returns true when startMinute changes`() {
        val initial = EventFormState(startMinute = 0)
        val state = initial.copy(startMinute = 30)
        assertTrue(hasChanges(state, initial))
    }

    @Test
    fun `hasChanges returns true when endHour changes`() {
        val initial = EventFormState(endHour = 10)
        val state = initial.copy(endHour = 11)
        assertTrue(hasChanges(state, initial))
    }

    @Test
    fun `hasChanges returns true when endMinute changes`() {
        val initial = EventFormState(endMinute = 0)
        val state = initial.copy(endMinute = 45)
        assertTrue(hasChanges(state, initial))
    }

    @Test
    fun `hasChanges returns true when selectedCalendarId changes`() {
        val initial = EventFormState(selectedCalendarId = 1L)
        val state = initial.copy(selectedCalendarId = 2L)
        assertTrue(hasChanges(state, initial))
    }

    @Test
    fun `hasChanges returns true when isAllDay changes`() {
        val initial = EventFormState(isAllDay = false)
        val state = initial.copy(isAllDay = true)
        assertTrue(hasChanges(state, initial))
    }

    // ========== hasChanges: Advanced Field Changes ==========

    @Test
    fun `hasChanges returns true when location changes`() {
        val initial = EventFormState(location = "")
        val state = initial.copy(location = "Conference Room A")
        assertTrue(hasChanges(state, initial))
    }

    @Test
    fun `hasChanges returns true when description changes`() {
        val initial = EventFormState(description = "")
        val state = initial.copy(description = "Meeting notes")
        assertTrue(hasChanges(state, initial))
    }

    @Test
    fun `hasChanges returns true when reminder1Minutes changes`() {
        val initial = EventFormState(reminder1Minutes = 15)
        val state = initial.copy(reminder1Minutes = 30)
        assertTrue(hasChanges(state, initial))
    }

    @Test
    fun `hasChanges returns true when reminder2Minutes changes`() {
        // BUG FIX REGRESSION TEST: reminder2Minutes was missing from hasChanges
        val initial = EventFormState(reminder2Minutes = REMINDER_OFF)
        val state = initial.copy(reminder2Minutes = 60)
        assertTrue("reminder2Minutes change should be detected", hasChanges(state, initial))
    }

    @Test
    fun `hasChanges returns true when rrule changes`() {
        val initial = EventFormState(rrule = null)
        val state = initial.copy(rrule = "FREQ=DAILY")
        assertTrue(hasChanges(state, initial))
    }

    // ========== hasChanges: UI-only Fields Should NOT Trigger ==========

    @Test
    fun `hasChanges returns false when only isLoading changes`() {
        val initial = EventFormState(isLoading = true)
        val state = initial.copy(isLoading = false)
        assertFalse("UI-only field should not trigger hasChanges", hasChanges(state, initial))
    }

    @Test
    fun `hasChanges returns false when only isSaving changes`() {
        val initial = EventFormState(isSaving = false)
        val state = initial.copy(isSaving = true)
        assertFalse("UI-only field should not trigger hasChanges", hasChanges(state, initial))
    }

    @Test
    fun `hasChanges returns false when only error changes`() {
        val initial = EventFormState(error = null)
        val state = initial.copy(error = "Some error")
        assertFalse("UI-only field should not trigger hasChanges", hasChanges(state, initial))
    }

    // ========== Dismiss Logic Tests ==========

    @Test
    fun `dismiss allowed when no changes`() {
        val (shouldDismiss, shouldShowConfirm) = evaluateDismissAttempt(
            hasChanges = false,
            showDiscardConfirm = false,
            isSaving = false
        )
        assertTrue("Should allow dismiss when no changes", shouldDismiss)
        assertFalse("Should not show confirm when no changes", shouldShowConfirm)
    }

    @Test
    fun `dismiss blocked on first attempt when has changes`() {
        val (shouldDismiss, shouldShowConfirm) = evaluateDismissAttempt(
            hasChanges = true,
            showDiscardConfirm = false,
            isSaving = false
        )
        assertFalse("Should block dismiss on first attempt", shouldDismiss)
        assertTrue("Should show confirm on first attempt", shouldShowConfirm)
    }

    @Test
    fun `dismiss allowed on second attempt when has changes and confirmed`() {
        val (shouldDismiss, shouldShowConfirm) = evaluateDismissAttempt(
            hasChanges = true,
            showDiscardConfirm = true,
            isSaving = false
        )
        assertTrue("Should allow dismiss after confirmation", shouldDismiss)
        assertFalse("Should not show confirm again", shouldShowConfirm)
    }

    @Test
    fun `dismiss blocked while saving regardless of changes`() {
        val (shouldDismiss, shouldShowConfirm) = evaluateDismissAttempt(
            hasChanges = false,
            showDiscardConfirm = false,
            isSaving = true
        )
        assertFalse("Should block dismiss while saving", shouldDismiss)
        assertFalse("Should not show confirm while saving", shouldShowConfirm)
    }

    @Test
    fun `dismiss blocked while saving even with confirm shown`() {
        val (shouldDismiss, shouldShowConfirm) = evaluateDismissAttempt(
            hasChanges = true,
            showDiscardConfirm = true,
            isSaving = true
        )
        assertFalse("Should block dismiss while saving", shouldDismiss)
        assertFalse("Should not show confirm while saving", shouldShowConfirm)
    }

    // ========== Combined Scenarios ==========

    @Test
    fun `full flow - create new event then cancel without changes`() {
        val initial = EventFormState(title = "")
        val state = initial.copy() // No changes

        assertFalse("No changes detected", hasChanges(state, initial))

        val (shouldDismiss, _) = evaluateDismissAttempt(
            hasChanges = false,
            showDiscardConfirm = false,
            isSaving = false
        )
        assertTrue("Should dismiss immediately", shouldDismiss)
    }

    @Test
    fun `full flow - edit event with changes requires two-tap cancel`() {
        val initial = EventFormState(title = "Original Meeting")
        val state = initial.copy(title = "Modified Meeting")

        assertTrue("Changes detected", hasChanges(state, initial))

        // First dismiss attempt
        val (shouldDismiss1, shouldShowConfirm1) = evaluateDismissAttempt(
            hasChanges = true,
            showDiscardConfirm = false,
            isSaving = false
        )
        assertFalse("First attempt blocked", shouldDismiss1)
        assertTrue("Shows confirm", shouldShowConfirm1)

        // Second dismiss attempt (after confirm shown)
        val (shouldDismiss2, _) = evaluateDismissAttempt(
            hasChanges = true,
            showDiscardConfirm = true,
            isSaving = false
        )
        assertTrue("Second attempt allowed", shouldDismiss2)
    }

    @Test
    fun `full flow - only reminder2 change triggers protection`() {
        // Regression test for bug where reminder2Minutes was not in hasChanges
        val initial = EventFormState(
            title = "Meeting",
            reminder1Minutes = 15,
            reminder2Minutes = REMINDER_OFF
        )
        val state = initial.copy(reminder2Minutes = 60) // Only change reminder2

        assertTrue("reminder2 change should be detected", hasChanges(state, initial))

        val (shouldDismiss, shouldShowConfirm) = evaluateDismissAttempt(
            hasChanges = true,
            showDiscardConfirm = false,
            isSaving = false
        )
        assertFalse("Should block dismiss", shouldDismiss)
        assertTrue("Should show confirm", shouldShowConfirm)
    }

    // ========== State Transition Tests ==========

    /**
     * State machine for dismiss confirmation:
     *
     * States: IDLE, CONFIRMING
     * Events: DISMISS_ATTEMPT, FORM_CHANGE, SAVE_START, SAVE_END
     *
     * IDLE + DISMISS_ATTEMPT (no changes) -> DISMISS
     * IDLE + DISMISS_ATTEMPT (has changes) -> CONFIRMING
     * CONFIRMING + DISMISS_ATTEMPT -> DISMISS
     * CONFIRMING + FORM_CHANGE -> (stays CONFIRMING, confirm still shown)
     * ANY + SAVE_START -> SAVING (blocks all dismiss)
     * SAVING + SAVE_END -> DISMISS (on success)
     */

    @Test
    fun `state transition - IDLE to DISMISS when no changes`() {
        var showDiscardConfirm = false
        val hasChanges = false

        val (shouldDismiss, newShowConfirm) = evaluateDismissAttempt(hasChanges, showDiscardConfirm, false)
        if (newShowConfirm) showDiscardConfirm = true

        assertTrue("Should transition to DISMISS", shouldDismiss)
        assertFalse("Confirm should remain false", showDiscardConfirm)
    }

    @Test
    fun `state transition - IDLE to CONFIRMING when has changes`() {
        var showDiscardConfirm = false
        val hasChanges = true

        val (shouldDismiss, newShowConfirm) = evaluateDismissAttempt(hasChanges, showDiscardConfirm, false)
        if (newShowConfirm) showDiscardConfirm = true

        assertFalse("Should NOT dismiss yet", shouldDismiss)
        assertTrue("Should transition to CONFIRMING", showDiscardConfirm)
    }

    @Test
    fun `state transition - CONFIRMING to DISMISS on second attempt`() {
        var showDiscardConfirm = true // Already in CONFIRMING state
        val hasChanges = true

        val (shouldDismiss, _) = evaluateDismissAttempt(hasChanges, showDiscardConfirm, false)

        assertTrue("Should transition to DISMISS", shouldDismiss)
    }

    @Test
    fun `state transition - SAVING blocks all dismiss attempts`() {
        // Test all combinations while saving
        val scenarios = listOf(
            Triple(false, false, "no changes, not confirming"),
            Triple(true, false, "has changes, not confirming"),
            Triple(true, true, "has changes, confirming"),
            Triple(false, true, "no changes, confirming")
        )

        for ((hasChanges, showConfirm, scenario) in scenarios) {
            val (shouldDismiss, _) = evaluateDismissAttempt(hasChanges, showConfirm, isSaving = true)
            assertFalse("SAVING should block dismiss: $scenario", shouldDismiss)
        }
    }

    // ========== Edge Cases ==========

    @Test
    fun `edge case - empty title is valid initial state`() {
        val initial = EventFormState(title = "")
        val state = initial.copy(title = "New Title")
        assertTrue("Adding title should be a change", hasChanges(state, initial))
    }

    @Test
    fun `edge case - null calendar ID to non-null is a change`() {
        val initial = EventFormState(selectedCalendarId = null)
        val state = initial.copy(selectedCalendarId = 1L)
        assertTrue("Setting calendar should be a change", hasChanges(state, initial))
    }

    @Test
    fun `edge case - rrule null to non-null is a change`() {
        val initial = EventFormState(rrule = null)
        val state = initial.copy(rrule = "FREQ=WEEKLY")
        assertTrue("Setting rrule should be a change", hasChanges(state, initial))
    }

    @Test
    fun `edge case - rrule non-null to different is a change`() {
        val initial = EventFormState(rrule = "FREQ=DAILY")
        val state = initial.copy(rrule = "FREQ=WEEKLY")
        assertTrue("Changing rrule should be a change", hasChanges(state, initial))
    }

    @Test
    fun `edge case - rrule non-null to null is a change`() {
        val initial = EventFormState(rrule = "FREQ=DAILY")
        val state = initial.copy(rrule = null)
        assertTrue("Removing rrule should be a change", hasChanges(state, initial))
    }

    @Test
    fun `edge case - multiple changes detected`() {
        val initial = EventFormState(
            title = "Original",
            location = "",
            reminder1Minutes = 15
        )
        val state = initial.copy(
            title = "Modified",
            location = "New Location",
            reminder1Minutes = 30
        )
        assertTrue("Multiple changes should be detected", hasChanges(state, initial))
    }

    @Test
    fun `edge case - whitespace-only changes in title`() {
        val initial = EventFormState(title = "Meeting")
        val state = initial.copy(title = "Meeting ")
        assertTrue("Whitespace change should be detected", hasChanges(state, initial))
    }

    // ========== Manual Testing Scenarios Documentation ==========
    /**
     * The following scenarios should be tested manually on device/emulator:
     *
     * 1. SWIPE GESTURE PROTECTION:
     *    a) Open new event form, immediately swipe down -> Should close (no changes)
     *    b) Open new event form, type title, swipe down -> Should show "Discard Changes?"
     *    c) With "Discard Changes?" shown, swipe down again -> Should close
     *    d) Open existing event, change time, swipe down -> Should show "Discard Changes?"
     *
     * 2. CANCEL BUTTON PARITY:
     *    a) Verify Cancel button and swipe have identical two-tap behavior
     *    b) Tap Cancel (shows "Discard Changes?"), then swipe -> Should close
     *    c) Swipe (shows "Discard Changes?"), then tap Cancel -> Should close
     *
     * 3. SCRIM TAP / BACK BUTTON:
     *    a) With changes, tap scrim (dark area outside sheet) -> Should show confirm
     *    b) With changes, press back button -> Should show confirm
     *
     * 4. SAVING STATE:
     *    a) Tap Save, immediately try to swipe -> Should be blocked
     *    b) After successful save, sheet should close automatically
     *
     * 5. EDGE CASES:
     *    a) Change only reminder2 (in More Options), swipe -> Should show confirm
     *    b) Change isAllDay toggle, swipe -> Should show confirm
     *    c) Select different calendar, swipe -> Should show confirm
     */

    // ========== Occurrence Date Tests ==========

    /**
     * Simulates the occurrence date calculation from EventFormSheet.
     * When editing a single occurrence, the form should use occurrenceTs (not master event date).
     */
    private fun calculateActualStartTs(
        eventStartTs: Long,
        eventEndTs: Long,
        occurrenceTs: Long?
    ): Pair<Long, Long> {
        val eventDuration = eventEndTs - eventStartTs
        val actualStartTs = occurrenceTs ?: eventStartTs
        val actualEndTs = actualStartTs + eventDuration
        return Pair(actualStartTs, actualEndTs)
    }

    @Test
    fun `form loads with occurrenceTs date when editing single occurrence`() {
        // Master event: Jan 1, 2024 10:00 AM - 11:00 AM (1 hour event)
        val masterStartTs = 1704106800000L // Jan 1, 2024 10:00 AM UTC
        val masterEndTs = 1704110400000L   // Jan 1, 2024 11:00 AM UTC

        // Occurrence: Jan 8, 2024 10:00 AM (one week later)
        val occurrenceTs = 1704711600000L  // Jan 8, 2024 10:00 AM UTC

        val (actualStart, actualEnd) = calculateActualStartTs(masterStartTs, masterEndTs, occurrenceTs)

        // Should use occurrence date, not master date
        assertTrue("Should use occurrenceTs for start", actualStart == occurrenceTs)
        assertTrue("End should be occurrence + duration", actualEnd == occurrenceTs + (masterEndTs - masterStartTs))
        assertTrue("Duration should be preserved", actualEnd - actualStart == masterEndTs - masterStartTs)
    }

    @Test
    fun `form loads with master event date when occurrenceTs is null`() {
        // Master event: Jan 1, 2024 10:00 AM - 11:00 AM
        val masterStartTs = 1704106800000L
        val masterEndTs = 1704110400000L

        // No occurrenceTs (editing master event or all occurrences)
        val occurrenceTs: Long? = null

        val (actualStart, actualEnd) = calculateActualStartTs(masterStartTs, masterEndTs, occurrenceTs)

        // Should use master event date
        assertTrue("Should use master startTs", actualStart == masterStartTs)
        assertTrue("Should use master endTs", actualEnd == masterEndTs)
    }

    @Test
    fun `occurrence date preserves event duration`() {
        // Master event: 2 hour duration
        val masterStartTs = 1704106800000L // 10:00 AM
        val masterEndTs = 1704114000000L   // 12:00 PM (2 hours)
        val expectedDuration = masterEndTs - masterStartTs // 2 hours = 7200000ms

        // Occurrence on different date
        val occurrenceTs = 1704711600000L

        val (actualStart, actualEnd) = calculateActualStartTs(masterStartTs, masterEndTs, occurrenceTs)

        assertTrue("Duration should be exactly 2 hours", actualEnd - actualStart == expectedDuration)
    }

    // ========== All-Day Event Toggle Tests ==========

    @Test
    fun `hasChanges returns true when isAllDay toggled on`() {
        val initial = EventFormState(isAllDay = false)
        val state = initial.copy(isAllDay = true)
        assertTrue("Toggling isAllDay ON should be detected", hasChanges(state, initial))
    }

    @Test
    fun `hasChanges returns true when isAllDay toggled off`() {
        val initial = EventFormState(isAllDay = true)
        val state = initial.copy(isAllDay = false)
        assertTrue("Toggling isAllDay OFF should be detected", hasChanges(state, initial))
    }

    @Test
    fun `all-day event does not track time changes`() {
        // When isAllDay is true, time changes still matter for hasChanges
        // (form should ignore them in UI but state tracks them)
        val initial = EventFormState(isAllDay = true, startHour = 0, startMinute = 0)
        val state = initial.copy(startHour = 10, startMinute = 30)
        assertTrue("Time changes should be tracked even for all-day events", hasChanges(state, initial))
    }

    // ========== Multi-Day Event Tests ==========

    @Test
    fun `hasChanges detects multi-day event creation`() {
        // Single day event
        val initial = EventFormState(
            dateMillis = 1704106800000L,
            endDateMillis = 1704106800000L
        )
        // Multi-day event (ends next day)
        val state = initial.copy(endDateMillis = 1704193200000L)
        assertTrue("Multi-day event should be detected", hasChanges(state, initial))
    }

    @Test
    fun `multi-day event with same times has no changes`() {
        val initial = EventFormState(
            dateMillis = 1704106800000L,
            endDateMillis = 1704193200000L,
            startHour = 10,
            endHour = 11
        )
        val state = initial.copy()
        assertFalse("Identical multi-day event should have no changes", hasChanges(state, initial))
    }

    // ========== Calendar Selection Tests ==========

    @Test
    fun `hasChanges detects calendar change from null to selected`() {
        val initial = EventFormState(selectedCalendarId = null)
        val state = initial.copy(selectedCalendarId = 1L)
        assertTrue("Calendar selection should be detected", hasChanges(state, initial))
    }

    @Test
    fun `hasChanges detects calendar change between calendars`() {
        val initial = EventFormState(selectedCalendarId = 1L)
        val state = initial.copy(selectedCalendarId = 2L)
        assertTrue("Changing calendar should be detected", hasChanges(state, initial))
    }

    @Test
    fun `hasChanges detects calendar change to null`() {
        val initial = EventFormState(selectedCalendarId = 1L)
        val state = initial.copy(selectedCalendarId = null)
        assertTrue("Clearing calendar should be detected", hasChanges(state, initial))
    }

    // ========== Reminder Dropdown Tests ==========

    @Test
    fun `hasChanges detects reminder1 change from default to no reminder`() {
        val initial = EventFormState(reminder1Minutes = 15)
        val state = initial.copy(reminder1Minutes = REMINDER_OFF)
        assertTrue("Disabling reminder1 should be detected", hasChanges(state, initial))
    }

    @Test
    fun `hasChanges detects reminder1 change to at time of event`() {
        val initial = EventFormState(reminder1Minutes = 15)
        val state = initial.copy(reminder1Minutes = 0)
        assertTrue("Changing to 'at time of event' should be detected", hasChanges(state, initial))
    }

    @Test
    fun `hasChanges detects reminder1 change to 1 hour`() {
        val initial = EventFormState(reminder1Minutes = 15)
        val state = initial.copy(reminder1Minutes = 60)
        assertTrue("Changing reminder1 to 1 hour should be detected", hasChanges(state, initial))
    }

    @Test
    fun `hasChanges detects reminder2 enabled`() {
        val initial = EventFormState(reminder2Minutes = REMINDER_OFF)
        val state = initial.copy(reminder2Minutes = 30)
        assertTrue("Enabling reminder2 should be detected", hasChanges(state, initial))
    }

    @Test
    fun `hasChanges detects reminder2 change to 1 day`() {
        val initial = EventFormState(reminder2Minutes = 60)
        val state = initial.copy(reminder2Minutes = 1440)
        assertTrue("Changing reminder2 to 1 day should be detected", hasChanges(state, initial))
    }

    @Test
    fun `hasChanges detects all-day reminder option (9 AM)`() {
        val initial = EventFormState(reminder1Minutes = 15, isAllDay = true)
        val state = initial.copy(reminder1Minutes = 540) // 9 AM day of event
        assertTrue("All-day 9 AM reminder should be detected", hasChanges(state, initial))
    }

    // ========== Time Picker Edge Cases ==========

    @Test
    fun `hasChanges detects midnight start time`() {
        val initial = EventFormState(startHour = 10, startMinute = 0)
        val state = initial.copy(startHour = 0, startMinute = 0)
        assertTrue("Midnight start time should be detected", hasChanges(state, initial))
    }

    @Test
    fun `hasChanges detects end of day time`() {
        val initial = EventFormState(endHour = 17, endMinute = 0)
        val state = initial.copy(endHour = 23, endMinute = 59)
        assertTrue("End of day time should be detected", hasChanges(state, initial))
    }

    @Test
    fun `hasChanges detects minute-only change`() {
        val initial = EventFormState(startHour = 10, startMinute = 0)
        val state = initial.copy(startMinute = 30)
        assertTrue("Minute change should be detected", hasChanges(state, initial))
    }

    @Test
    fun `hasChanges detects crossing noon boundary`() {
        val initial = EventFormState(startHour = 11, endHour = 12)
        val state = initial.copy(startHour = 12, endHour = 13)
        assertTrue("Crossing noon should be detected", hasChanges(state, initial))
    }

    // ========== Form Validation Tests ==========

    @Test
    fun `empty title is valid initial state for new event`() {
        val initial = EventFormState(title = "", isEditMode = false)
        val state = initial.copy()
        assertFalse("Empty title unchanged should not trigger hasChanges", hasChanges(state, initial))
    }

    @Test
    fun `whitespace title change detected`() {
        val initial = EventFormState(title = "Meeting")
        val state = initial.copy(title = "  Meeting  ")
        assertTrue("Whitespace changes should be detected", hasChanges(state, initial))
    }

    @Test
    fun `special characters in title detected`() {
        val initial = EventFormState(title = "Meeting")
        val state = initial.copy(title = "Meeting!")
        assertTrue("Special character change should be detected", hasChanges(state, initial))
    }

    // ========== Combined Field Changes ==========

    @Test
    fun `hasChanges detects multiple field changes`() {
        val initial = EventFormState(
            title = "Original",
            startHour = 10,
            reminder1Minutes = 15,
            selectedCalendarId = 1L
        )
        val state = initial.copy(
            title = "Modified",
            startHour = 14,
            reminder1Minutes = 30,
            selectedCalendarId = 2L
        )
        assertTrue("Multiple changes should be detected", hasChanges(state, initial))
    }

    @Test
    fun `hasChanges with all fields changed`() {
        val initial = EventFormState(
            title = "Original",
            dateMillis = 1704106800000L,
            endDateMillis = 1704106800000L,
            startHour = 10,
            startMinute = 0,
            endHour = 11,
            endMinute = 0,
            selectedCalendarId = 1L,
            isAllDay = false,
            location = "",
            description = "",
            reminder1Minutes = 15,
            reminder2Minutes = REMINDER_OFF,
            rrule = null
        )
        val state = EventFormState(
            title = "Modified",
            dateMillis = 1704193200000L,
            endDateMillis = 1704279600000L,
            startHour = 14,
            startMinute = 30,
            endHour = 16,
            endMinute = 45,
            selectedCalendarId = 2L,
            isAllDay = true,
            location = "Conference Room",
            description = "Important meeting",
            reminder1Minutes = 60,
            reminder2Minutes = 1440,
            rrule = "FREQ=WEEKLY"
        )
        assertTrue("All fields changed should be detected", hasChanges(state, initial))
    }

    // ========== Edit Mode Tests ==========

    @Test
    fun `edit mode tracks editingEventId correctly`() {
        val initial = EventFormState(
            isEditMode = true,
            editingEventId = 123L,
            title = "Existing Meeting"
        )
        val state = initial.copy(title = "Updated Meeting")
        assertTrue("Edit mode title change should be detected", hasChanges(state, initial))
    }

    @Test
    fun `edit mode with occurrence tracks occurrenceTs`() {
        val initial = EventFormState(
            isEditMode = true,
            editingEventId = 123L,
            editingOccurrenceTs = 1704106800000L,
            title = "Recurring Meeting",
            startHour = 10  // Explicitly set to avoid depending on current time
        )
        val state = initial.copy(startHour = 14)
        assertTrue("Occurrence edit time change should be detected", hasChanges(state, initial))
    }

    // ========== Recurrence (RRULE) Tests ==========

    @Test
    fun `hasChanges detects rrule added`() {
        val initial = EventFormState(rrule = null)
        val state = initial.copy(rrule = "FREQ=DAILY")
        assertTrue("Adding rrule should be detected", hasChanges(state, initial))
    }

    @Test
    fun `hasChanges detects rrule frequency change`() {
        val initial = EventFormState(rrule = "FREQ=DAILY")
        val state = initial.copy(rrule = "FREQ=WEEKLY")
        assertTrue("Changing rrule frequency should be detected", hasChanges(state, initial))
    }

    @Test
    fun `hasChanges detects rrule removed`() {
        val initial = EventFormState(rrule = "FREQ=WEEKLY;BYDAY=MO,WE,FR")
        val state = initial.copy(rrule = null)
        assertTrue("Removing rrule should be detected", hasChanges(state, initial))
    }

    @Test
    fun `hasChanges detects rrule with count added`() {
        val initial = EventFormState(rrule = "FREQ=DAILY")
        val state = initial.copy(rrule = "FREQ=DAILY;COUNT=10")
        assertTrue("Adding COUNT to rrule should be detected", hasChanges(state, initial))
    }

    @Test
    fun `hasChanges detects rrule with until added`() {
        val initial = EventFormState(rrule = "FREQ=WEEKLY")
        val state = initial.copy(rrule = "FREQ=WEEKLY;UNTIL=20241231T235959Z")
        assertTrue("Adding UNTIL to rrule should be detected", hasChanges(state, initial))
    }

    // ========== Default Values from Preferences ==========

    @Test
    fun `default state matches expected defaults`() {
        val state = EventFormState()

        // Verify default values
        assertTrue("Default title is empty", state.title.isEmpty())
        assertTrue("Default dateMillis is current time", state.dateMillis > 0)
        assertTrue("Default reminder1 is 15 minutes", state.reminder1Minutes == 15)
        assertTrue("Default reminder2 is off", state.reminder2Minutes == REMINDER_OFF)
        assertTrue("Default isAllDay is false", !state.isAllDay)
        assertTrue("Default rrule is null", state.rrule == null)
        assertTrue("Default isEditMode is false", !state.isEditMode)
    }

    @Test
    fun `form with custom defaults has no changes when unchanged`() {
        val customDefaults = EventFormState(
            reminder1Minutes = 60, // Custom: 1 hour default
            selectedCalendarId = 5L // Custom: pre-selected calendar
        )
        val state = customDefaults.copy()
        assertFalse("Custom defaults unchanged should not trigger hasChanges", hasChanges(state, customDefaults))
    }

    // ========== Duration Maintenance Tests ==========

    /**
     * Check if start and end dates represent different calendar days.
     * Uses Calendar DAY_OF_YEAR comparison (matches production isMultiDay function).
     */
    private fun isMultiDayTest(startDateMillis: Long, endDateMillis: Long): Boolean {
        val startCal = java.util.Calendar.getInstance().apply { timeInMillis = startDateMillis }
        val endCal = java.util.Calendar.getInstance().apply { timeInMillis = endDateMillis }
        return startCal.get(java.util.Calendar.YEAR) != endCal.get(java.util.Calendar.YEAR) ||
            startCal.get(java.util.Calendar.DAY_OF_YEAR) != endCal.get(java.util.Calendar.DAY_OF_YEAR)
    }

    /**
     * Simulates the duration maintenance logic from EventFormSheet onStartTimeSelected.
     * Returns Pair(newEndHour, newEndMinute)
     */
    private fun calculateEndTimeWithDuration(
        oldStartHour: Int,
        oldStartMinute: Int,
        oldEndHour: Int,
        oldEndMinute: Int,
        oldStartDateMillis: Long,
        oldEndDateMillis: Long,
        newStartHour: Int,
        newStartMinute: Int
    ): Triple<Int, Int, Long> {
        // Calculate current duration (or default 20 mins if invalid)
        val currentDurationMinutes = (oldEndHour * 60 + oldEndMinute) -
            (oldStartHour * 60 + oldStartMinute)
        // Handle case where end was already on next day (use calendar day comparison)
        val adjustedDuration = if (isMultiDayTest(oldStartDateMillis, oldEndDateMillis)) {
            currentDurationMinutes + 24 * 60
        } else {
            currentDurationMinutes
        }
        val duration = if (adjustedDuration > 0) adjustedDuration else 20

        // Calculate new end time
        val newEndTotalMinutes = newStartHour * 60 + newStartMinute + duration

        return if (newEndTotalMinutes >= 24 * 60) {
            // Crosses midnight
            val nextDayMillis = oldStartDateMillis + (24 * 60 * 60 * 1000)
            val overflowMinutes = newEndTotalMinutes - (24 * 60)
            Triple(overflowMinutes / 60, overflowMinutes % 60, nextDayMillis)
        } else {
            // Same day
            Triple(newEndTotalMinutes / 60, newEndTotalMinutes % 60, oldStartDateMillis)
        }
    }

    @Test
    fun `end time follows start time maintaining duration`() {
        // Given: start=10:00, end=10:30 (30 min duration)
        val (newEndHour, newEndMinute, _) = calculateEndTimeWithDuration(
            oldStartHour = 10, oldStartMinute = 0,
            oldEndHour = 10, oldEndMinute = 30,
            oldStartDateMillis = 1704106800000L, // Jan 1
            oldEndDateMillis = 1704106800000L,   // Jan 1 (same day)
            newStartHour = 14, newStartMinute = 0
        )
        // Then: end should be 14:30
        assertEquals("End hour should be 14", 14, newEndHour)
        assertEquals("End minute should be 30", 30, newEndMinute)
    }

    @Test
    fun `end time uses default 20 min when duration invalid`() {
        // Given: start=10:00, end=09:00 (negative duration)
        val (newEndHour, newEndMinute, _) = calculateEndTimeWithDuration(
            oldStartHour = 10, oldStartMinute = 0,
            oldEndHour = 9, oldEndMinute = 0,
            oldStartDateMillis = 1704106800000L,
            oldEndDateMillis = 1704106800000L,
            newStartHour = 14, newStartMinute = 0
        )
        // Then: end should be 14:20 (default)
        assertEquals("End hour should be 14", 14, newEndHour)
        assertEquals("End minute should be 20", 20, newEndMinute)
    }

    @Test
    fun `midnight crossing updates endDateMillis to next day`() {
        // Given: start=22:00, end=22:30, same date
        val startDateMillis = 1704106800000L // Jan 1
        val (newEndHour, newEndMinute, newEndDateMillis) = calculateEndTimeWithDuration(
            oldStartHour = 22, oldStartMinute = 0,
            oldEndHour = 22, oldEndMinute = 30,
            oldStartDateMillis = startDateMillis,
            oldEndDateMillis = startDateMillis,
            newStartHour = 23, newStartMinute = 50
        )
        // Then: end=00:20, endDateMillis = next day
        assertEquals("End hour should be 0", 0, newEndHour)
        assertEquals("End minute should be 20", 20, newEndMinute)
        assertTrue("endDateMillis should be next day", newEndDateMillis > startDateMillis)
    }

    @Test
    fun `midnight crossing preserves duration across day boundary`() {
        // Given: start=23:00, end=00:30 (+1 day), duration=90 mins
        val day1 = 1704106800000L
        val day2 = day1 + (24 * 60 * 60 * 1000)
        val (newEndHour, newEndMinute, newEndDateMillis) = calculateEndTimeWithDuration(
            oldStartHour = 23, oldStartMinute = 0,
            oldEndHour = 0, oldEndMinute = 30,
            oldStartDateMillis = day1,
            oldEndDateMillis = day2, // End is on day 2
            newStartHour = 23, newStartMinute = 30
        )
        // Then: end=01:00 (+1 day), duration still 90 mins
        assertEquals("End hour should be 1", 1, newEndHour)
        assertEquals("End minute should be 0", 0, newEndMinute)
        assertTrue("endDateMillis should be next day", newEndDateMillis > day1)
    }

    @Test
    fun `returning from midnight crossing resets endDateMillis`() {
        // Given: start=23:50, end=00:10 (+1 day)
        val day1 = 1704106800000L
        val day2 = day1 + (24 * 60 * 60 * 1000)
        val (newEndHour, newEndMinute, newEndDateMillis) = calculateEndTimeWithDuration(
            oldStartHour = 23, oldStartMinute = 50,
            oldEndHour = 0, oldEndMinute = 10,
            oldStartDateMillis = day1,
            oldEndDateMillis = day2,
            newStartHour = 10, newStartMinute = 0
        )
        // Then: end=10:20, endDateMillis = same day
        assertEquals("End hour should be 10", 10, newEndHour)
        assertEquals("End minute should be 20", 20, newEndMinute)
        assertEquals("endDateMillis should be same day", day1, newEndDateMillis)
    }

    @Test
    fun `same day event with different timestamps does not add 24h duration`() {
        // Given: Event 10 AM - 11 AM on Jan 2 (realistic: different timestamps, same day)
        // This is how real events are stored - start and end have DIFFERENT timestamps
        val jan2_10am = 1704193200000L  // Jan 2, 2024 @ 10:00 AM UTC
        val jan2_11am = jan2_10am + (60 * 60 * 1000)  // 1 hour later = 11:00 AM

        // When: User changes start to 12:00 AM (midnight)
        val (newEndHour, newEndMinute, newEndDateMillis) = calculateEndTimeWithDuration(
            oldStartHour = 10, oldStartMinute = 0,
            oldEndHour = 11, oldEndMinute = 0,
            oldStartDateMillis = jan2_10am,
            oldEndDateMillis = jan2_11am,  // Different timestamp, SAME day!
            newStartHour = 0, newStartMinute = 0  // User picks 12:00 AM
        )

        // Then: Should preserve 1-hour duration, end at 1:00 AM same day
        assertEquals("End hour should be 1 (1 AM)", 1, newEndHour)
        assertEquals("End minute should be 0", 0, newEndMinute)
        assertEquals("Should stay same day", jan2_10am, newEndDateMillis)
    }

    // ========== shouldShowSeparatePickers Tests ==========

    /**
     * Simulates shouldShowSeparatePickers logic.
     */
    private fun shouldShowSeparatePickers(
        startDateMillis: Long,
        endDateMillis: Long,
        startHour: Int,
        endHour: Int
    ): Boolean {
        // Check if dates are different
        val startCal = java.util.Calendar.getInstance().apply { timeInMillis = startDateMillis }
        val endCal = java.util.Calendar.getInstance().apply { timeInMillis = endDateMillis }
        val isMultiDay = startCal.get(java.util.Calendar.YEAR) != endCal.get(java.util.Calendar.YEAR) ||
            startCal.get(java.util.Calendar.DAY_OF_YEAR) != endCal.get(java.util.Calendar.DAY_OF_YEAR)

        if (!isMultiDay) return false

        // If exactly 1 day apart and end time < start time, it's a midnight crossing
        val daysDiff = (endDateMillis - startDateMillis) / (24 * 60 * 60 * 1000)
        if (daysDiff == 1L && endHour < startHour) {
            return false  // Midnight crossing - keep merged view
        }

        return true
    }

    @Test
    fun `same day event shows merged picker`() {
        val day1 = 1704106800000L
        assertFalse(
            "Same day should show merged picker",
            shouldShowSeparatePickers(day1, day1, 10, 11)
        )
    }

    @Test
    fun `midnight crossing shows merged picker with +1`() {
        val day1 = 1704106800000L
        val day2 = day1 + (24 * 60 * 60 * 1000)
        // 1 day apart, endHour(1) < startHour(23) = midnight crossing
        assertFalse(
            "Midnight crossing should show merged picker",
            shouldShowSeparatePickers(day1, day2, 23, 1)
        )
    }

    @Test
    fun `true multi-day event shows separate pickers`() {
        val day1 = 1704106800000L
        val day2 = day1 + (24 * 60 * 60 * 1000)
        // 1 day apart, endHour(14) > startHour(10) = NOT midnight crossing
        assertTrue(
            "True multi-day should show separate pickers",
            shouldShowSeparatePickers(day1, day2, 10, 14)
        )
    }

    @Test
    fun `2+ day event always shows separate pickers`() {
        val day1 = 1704106800000L
        val day3 = day1 + (2 * 24 * 60 * 60 * 1000) // 2 days later
        assertTrue(
            "2+ day event should show separate pickers",
            shouldShowSeparatePickers(day1, day3, 23, 1)
        )
    }

    @Test
    fun `midnight crossing edge case - exactly at midnight`() {
        val day1 = 1704106800000L
        val day2 = day1 + (24 * 60 * 60 * 1000)
        // Start at 23:30, end at 00:00 (exactly midnight)
        assertFalse(
            "End at midnight should show merged picker",
            shouldShowSeparatePickers(day1, day2, 23, 0)
        )
    }

    // ========== isMidnightCrossing Tests ==========

    /**
     * Simulates the isMidnightCrossing logic from MergedTimeRow.
     * This is the FIXED version that uses isMultiDay() for proper day comparison.
     *
     * Bug: The original code used `endDateMillis > startDateMillis` which is always
     * true for same-day events because timestamps include time (not just date).
     *
     * Fix: Use calendar day comparison via isMultiDay(), then check if end hour
     * wrapped around midnight (endHour < startHour).
     */
    private fun isMidnightCrossing(
        startDateMillis: Long,
        endDateMillis: Long,
        startHour: Int,
        endHour: Int
    ): Boolean {
        val startCal = java.util.Calendar.getInstance().apply { timeInMillis = startDateMillis }
        val endCal = java.util.Calendar.getInstance().apply { timeInMillis = endDateMillis }
        val isMultiDay = startCal.get(java.util.Calendar.YEAR) != endCal.get(java.util.Calendar.YEAR) ||
            startCal.get(java.util.Calendar.DAY_OF_YEAR) != endCal.get(java.util.Calendar.DAY_OF_YEAR)

        return isMultiDay && endHour < startHour
    }

    @Test
    fun `isMidnightCrossing returns false for same day event`() {
        // 10 AM to 10:20 PM same day - should NOT show +1
        // This was the bug: timestamps differ but calendar day is the same
        val day1 = 1704106800000L  // Some day at 10 AM
        val day1Later = day1 + (12 * 60 * 60 * 1000)  // Same day at 10 PM (+12 hours)
        assertFalse(
            "Same day event should NOT show +1",
            isMidnightCrossing(day1, day1Later, 10, 22)
        )
    }

    @Test
    fun `isMidnightCrossing returns true for late night to early morning`() {
        // 10 PM to 2 AM - should show +1 (true midnight crossing)
        val day1 = 1704106800000L
        val day2 = day1 + (24 * 60 * 60 * 1000)
        assertTrue(
            "10 PM to 2 AM should show +1",
            isMidnightCrossing(day1, day2, 22, 2)
        )
    }

    @Test
    fun `isMidnightCrossing returns true for 11-30 PM to 12-30 AM`() {
        val day1 = 1704106800000L
        val day2 = day1 + (24 * 60 * 60 * 1000)
        assertTrue(
            "11:30 PM to 12:30 AM should show +1",
            isMidnightCrossing(day1, day2, 23, 0)
        )
    }

    @Test
    fun `isMidnightCrossing returns false for true multi-day event`() {
        // 10 AM to 3 PM next day - multi-day event, NOT midnight crossing
        // Should show separate pickers, not merged with +1
        val day1 = 1704106800000L
        val day2 = day1 + (24 * 60 * 60 * 1000)
        assertFalse(
            "10 AM to 3 PM next day should NOT show +1 (multi-day event)",
            isMidnightCrossing(day1, day2, 10, 15)
        )
    }

    @Test
    fun `isMidnightCrossing returns false for exactly 24 hour event`() {
        // 10 PM to 10 PM next day - exactly 24h, NOT midnight crossing
        val day1 = 1704106800000L
        val day2 = day1 + (24 * 60 * 60 * 1000)
        assertFalse(
            "10 PM to 10 PM next day should NOT show +1",
            isMidnightCrossing(day1, day2, 22, 22)
        )
    }

    // ========== onEndTimeSelected Tests ==========

    /**
     * Simulates the onEndTimeSelected logic that updates endDateMillis
     * when end time crosses midnight relative to start time.
     */
    private fun calculateEndDateMillisForEndTimeChange(
        startDateMillis: Long,
        startHour: Int,
        newEndHour: Int
    ): Long {
        val crossesMidnight = newEndHour < startHour
        return if (crossesMidnight) {
            startDateMillis + (24 * 60 * 60 * 1000)
        } else {
            startDateMillis
        }
    }

    @Test
    fun `changing end time to cross midnight updates endDateMillis to next day`() {
        // Given: Event starting at 10 PM same day
        val day1 = 1704106800000L
        val startHour = 22  // 10 PM

        // When: User changes end to 1 AM (crosses midnight)
        val newEndHour = 1
        val newEndDateMillis = calculateEndDateMillisForEndTimeChange(day1, startHour, newEndHour)

        // Then: endDateMillis should be next day
        val expectedNextDay = day1 + (24 * 60 * 60 * 1000)
        assertEquals("End should be next day", expectedNextDay, newEndDateMillis)
        assertTrue("Should detect as multi-day", isMultiDayTest(day1, newEndDateMillis))
        assertTrue("Should show +1", isMidnightCrossing(day1, newEndDateMillis, startHour, newEndHour))
    }

    @Test
    fun `changing end time within same day keeps endDateMillis unchanged`() {
        // Given: Event starting at 10 AM same day
        val day1 = 1704106800000L
        val startHour = 10

        // When: User changes end to 2 PM (same day, no midnight crossing)
        val newEndHour = 14
        val newEndDateMillis = calculateEndDateMillisForEndTimeChange(day1, startHour, newEndHour)

        // Then: endDateMillis should stay same day
        assertEquals("End should be same day", day1, newEndDateMillis)
        assertFalse("Should NOT show +1", isMidnightCrossing(day1, newEndDateMillis, startHour, newEndHour))
    }

    @Test
    fun `changing end time from midnight crossing back to same day`() {
        // Given: Event starting at 10 PM
        val day1 = 1704106800000L
        val startHour = 22  // 10 PM

        // When: User changes end from 1 AM back to 11 PM (same day as start)
        val newEndHour = 23
        val newEndDateMillis = calculateEndDateMillisForEndTimeChange(day1, startHour, newEndHour)

        // Then: endDateMillis should stay same day (not next day)
        assertEquals("End should be same day", day1, newEndDateMillis)
        assertFalse("Should NOT show +1", isMidnightCrossing(day1, newEndDateMillis, startHour, newEndHour))
    }

    @Test
    fun `end time at exactly midnight shows +1`() {
        // Given: Event starting at 10 PM
        val day1 = 1704106800000L
        val startHour = 22  // 10 PM

        // When: User changes end to 12:00 AM (midnight = hour 0)
        val newEndHour = 0
        val newEndDateMillis = calculateEndDateMillisForEndTimeChange(day1, startHour, newEndHour)

        // Then: endDateMillis should be next day, +1 should show
        val expectedNextDay = day1 + (24 * 60 * 60 * 1000)
        assertEquals("End should be next day", expectedNextDay, newEndDateMillis)
        assertTrue("Should show +1 for midnight", isMidnightCrossing(day1, newEndDateMillis, startHour, newEndHour))
    }

    // ========== Date Range Picker Tests (Marriott-style unified picker) ==========

    /**
     * Tests for the unified date range picker that shows both start and end
     * dates in a single compact row with a shared calendar.
     *
     * Key behaviors:
     * - Start/End tab toggle for selection mode
     * - Auto-advance from Start to End after selection
     * - Smart swap validation (end < start → swap)
     * - Same-day confirmation (tap same date → collapse)
     */

    /**
     * Simulates the date selection logic from DateRangePickerCard.
     * Returns Pair(newStartDateMillis, newEndDateMillis).
     */
    private fun simulateDateSelection(
        currentStartMillis: Long,
        currentEndMillis: Long,
        selectedMillis: Long,
        activeSelection: DateSelectionMode
    ): Pair<Long, Long> {
        return if (activeSelection == DateSelectionMode.START) {
            // Update start date
            val newStart = selectedMillis
            // If new start is after end, swap (smart validation)
            val newEnd = if (selectedMillis > currentEndMillis) selectedMillis else currentEndMillis
            Pair(newStart, newEnd)
        } else {
            // Update end date
            if (selectedMillis < currentStartMillis) {
                // Smart swap: selected becomes start, old start becomes end
                Pair(selectedMillis, currentStartMillis)
            } else {
                Pair(currentStartMillis, selectedMillis)
            }
        }
    }

    @Test
    fun `selecting start date updates startDateMillis`() {
        val jan1 = 1704067200000L  // Jan 1, 2024
        val jan5 = jan1 + (4 * 24 * 60 * 60 * 1000)  // Jan 5, 2024

        val (newStart, newEnd) = simulateDateSelection(
            currentStartMillis = jan1,
            currentEndMillis = jan1,
            selectedMillis = jan5,
            activeSelection = DateSelectionMode.START
        )

        assertEquals("Start should be Jan 5", jan5, newStart)
        assertEquals("End should move to Jan 5 (was before)", jan5, newEnd)
    }

    @Test
    fun `selecting end date updates endDateMillis`() {
        val jan1 = 1704067200000L
        val jan5 = jan1 + (4 * 24 * 60 * 60 * 1000)

        val (newStart, newEnd) = simulateDateSelection(
            currentStartMillis = jan1,
            currentEndMillis = jan1,
            selectedMillis = jan5,
            activeSelection = DateSelectionMode.END
        )

        assertEquals("Start should stay Jan 1", jan1, newStart)
        assertEquals("End should be Jan 5", jan5, newEnd)
    }

    @Test
    fun `selecting end before start triggers smart swap`() {
        val jan1 = 1704067200000L
        val jan5 = jan1 + (4 * 24 * 60 * 60 * 1000)
        val dec25 = jan1 - (7 * 24 * 60 * 60 * 1000)  // Dec 25, 2023

        // Start = Jan 5, End = Jan 5
        // User selects Dec 25 as end date (before start)
        val (newStart, newEnd) = simulateDateSelection(
            currentStartMillis = jan5,
            currentEndMillis = jan5,
            selectedMillis = dec25,
            activeSelection = DateSelectionMode.END
        )

        // Should swap: Dec 25 becomes start, Jan 5 becomes end
        assertEquals("Start should be Dec 25 (swapped)", dec25, newStart)
        assertEquals("End should be Jan 5 (swapped)", jan5, newEnd)
    }

    @Test
    fun `selecting start after end auto-adjusts end`() {
        val jan1 = 1704067200000L
        val jan5 = jan1 + (4 * 24 * 60 * 60 * 1000)
        val jan10 = jan1 + (9 * 24 * 60 * 60 * 1000)

        // Start = Jan 1, End = Jan 5
        // User selects Jan 10 as start date (after end)
        val (newStart, newEnd) = simulateDateSelection(
            currentStartMillis = jan1,
            currentEndMillis = jan5,
            selectedMillis = jan10,
            activeSelection = DateSelectionMode.START
        )

        // End should move to Jan 10 to maintain valid range
        assertEquals("Start should be Jan 10", jan10, newStart)
        assertEquals("End should move to Jan 10", jan10, newEnd)
    }

    @Test
    fun `selecting same date as start in END mode confirms same-day`() {
        val jan1 = 1704067200000L

        // Start = Jan 1, End = Jan 5
        // User selects Jan 1 as end date (same as start)
        val (newStart, newEnd) = simulateDateSelection(
            currentStartMillis = jan1,
            currentEndMillis = jan1 + (4 * 24 * 60 * 60 * 1000),
            selectedMillis = jan1,
            activeSelection = DateSelectionMode.END
        )

        // Both should be Jan 1 (same-day event)
        assertEquals("Start should stay Jan 1", jan1, newStart)
        assertEquals("End should be Jan 1", jan1, newEnd)
    }

    @Test
    fun `date range selection preserves time components`() {
        // When selecting dates, time components should be preserved
        val jan1_10am = 1704103200000L  // Jan 1 at 10:00 AM
        val jan1_11am = jan1_10am + (60 * 60 * 1000)  // Jan 1 at 11:00 AM

        val initial = EventFormState(
            dateMillis = jan1_10am,
            endDateMillis = jan1_11am,
            startHour = 10,
            startMinute = 0,
            endHour = 11,
            endMinute = 0
        )

        // Changing date should not affect time fields
        val updated = initial.copy(dateMillis = jan1_10am + (24 * 60 * 60 * 1000))  // Jan 2

        // Time fields should remain unchanged
        assertEquals("Start hour should be preserved", 10, updated.startHour)
        assertEquals("End hour should be preserved", 11, updated.endHour)
    }

    @Test
    fun `multi-day date range triggers separate time pickers`() {
        val jan1 = 1704067200000L
        val jan5 = jan1 + (4 * 24 * 60 * 60 * 1000)

        // 4-day event, 10 AM - 3 PM
        val shouldSeparate = shouldShowSeparatePickers(jan1, jan5, 10, 15)
        assertTrue("4-day event should show separate pickers", shouldSeparate)
    }

    @Test
    fun `same-day date range uses merged time picker`() {
        val jan1 = 1704067200000L

        val shouldSeparate = shouldShowSeparatePickers(jan1, jan1, 10, 11)
        assertFalse("Same-day event should use merged picker", shouldSeparate)
    }

    // ========== DateSelectionMode State Machine Tests ==========

    @Test
    fun `selection mode START allows start date changes`() {
        val mode = DateSelectionMode.START
        assertTrue("START mode should be for selecting start", mode == DateSelectionMode.START)
    }

    @Test
    fun `selection mode END allows end date changes`() {
        val mode = DateSelectionMode.END
        assertTrue("END mode should be for selecting end", mode == DateSelectionMode.END)
    }

    @Test
    fun `auto-advance from START to END after selection`() {
        // Simulating the auto-advance behavior
        var activeSelection = DateSelectionMode.START

        // After selecting start date, should advance to END
        if (activeSelection == DateSelectionMode.START) {
            activeSelection = DateSelectionMode.END
        }

        assertEquals("Should auto-advance to END", DateSelectionMode.END, activeSelection)
    }

    // ========== Range Highlighting Tests ==========

    /**
     * Tests the range highlighting logic for calendar days.
     * - Start date: primary color
     * - End date: tertiary color
     * - Days in range: primaryContainer background
     */

    private fun isInRange(dayMillis: Long, startMillis: Long, endMillis: Long): Boolean {
        return dayMillis > startMillis && dayMillis < endMillis
    }

    private fun isStartDate(dayMillis: Long, startMillis: Long): Boolean {
        val dayCal = java.util.Calendar.getInstance().apply { timeInMillis = dayMillis }
        val startCal = java.util.Calendar.getInstance().apply { timeInMillis = startMillis }
        return dayCal.get(java.util.Calendar.YEAR) == startCal.get(java.util.Calendar.YEAR) &&
            dayCal.get(java.util.Calendar.DAY_OF_YEAR) == startCal.get(java.util.Calendar.DAY_OF_YEAR)
    }

    private fun isEndDate(dayMillis: Long, endMillis: Long): Boolean {
        val dayCal = java.util.Calendar.getInstance().apply { timeInMillis = dayMillis }
        val endCal = java.util.Calendar.getInstance().apply { timeInMillis = endMillis }
        return dayCal.get(java.util.Calendar.YEAR) == endCal.get(java.util.Calendar.YEAR) &&
            dayCal.get(java.util.Calendar.DAY_OF_YEAR) == endCal.get(java.util.Calendar.DAY_OF_YEAR)
    }

    @Test
    fun `start date gets primary highlighting`() {
        val jan1 = 1704067200000L
        val jan5 = jan1 + (4 * 24 * 60 * 60 * 1000)

        assertTrue("Jan 1 should be start date", isStartDate(jan1, jan1))
        assertFalse("Jan 1 should not be in range", isInRange(jan1, jan1, jan5))
    }

    @Test
    fun `end date gets tertiary highlighting`() {
        val jan1 = 1704067200000L
        val jan5 = jan1 + (4 * 24 * 60 * 60 * 1000)

        assertTrue("Jan 5 should be end date", isEndDate(jan5, jan5))
        assertFalse("Jan 5 should not be in range", isInRange(jan5, jan1, jan5))
    }

    @Test
    fun `days in range get container highlighting`() {
        val jan1 = 1704067200000L
        val jan3 = jan1 + (2 * 24 * 60 * 60 * 1000)
        val jan5 = jan1 + (4 * 24 * 60 * 60 * 1000)

        assertTrue("Jan 3 should be in range", isInRange(jan3, jan1, jan5))
        assertFalse("Jan 3 should not be start", isStartDate(jan3, jan1))
        assertFalse("Jan 3 should not be end", isEndDate(jan3, jan5))
    }

    @Test
    fun `same day shows primary only - no range`() {
        val jan1 = 1704067200000L

        assertTrue("Jan 1 should be start date", isStartDate(jan1, jan1))
        assertTrue("Jan 1 should also be end date", isEndDate(jan1, jan1))
        assertFalse("No range when same day", isInRange(jan1, jan1, jan1))
    }

    // ========== Cross-Month Range Tests ==========

    @Test
    fun `range spanning months highlights correctly`() {
        val dec30 = 1703894400000L  // Dec 30, 2023
        val jan2 = dec30 + (3 * 24 * 60 * 60 * 1000)  // Jan 2, 2024
        val dec31 = dec30 + (24 * 60 * 60 * 1000)
        val jan1 = dec30 + (2 * 24 * 60 * 60 * 1000)

        assertTrue("Dec 30 is start", isStartDate(dec30, dec30))
        assertTrue("Jan 2 is end", isEndDate(jan2, jan2))
        assertTrue("Dec 31 is in range", isInRange(dec31, dec30, jan2))
        assertTrue("Jan 1 is in range", isInRange(jan1, dec30, jan2))
    }

    @Test
    fun `range spanning years highlights correctly`() {
        val dec30_2023 = 1703894400000L
        val jan3_2024 = dec30_2023 + (4 * 24 * 60 * 60 * 1000)
        val jan1_2024 = dec30_2023 + (2 * 24 * 60 * 60 * 1000)

        assertTrue("Range crosses year boundary", isInRange(jan1_2024, dec30_2023, jan3_2024))
    }

    // ========== Date Format Display Tests ==========

    @Test
    fun `collapsed row shows both dates for multi-day`() {
        // Visual test: "Thu, Jan 2 → Sat, Jan 4"
        val jan2 = 1704153600000L  // Jan 2, 2024
        val jan4 = jan2 + (2 * 24 * 60 * 60 * 1000)

        assertTrue("Should be multi-day", isMultiDayTest(jan2, jan4))
        // UI should show "Jan 2 → Jan 4" format
    }

    @Test
    fun `collapsed row shows single date for same-day`() {
        // Visual test: "Thu, Jan 2" (not "Thu, Jan 2 → Thu, Jan 2")
        val jan2 = 1704153600000L

        assertFalse("Should be same-day", isMultiDayTest(jan2, jan2))
        // UI should show just "Jan 2" without arrow
    }

    // ========== Edit Mode Date Range Tests ==========

    @Test
    fun `edit mode loads multi-day event dates correctly`() {
        // Given: Existing multi-day event Jan 2 - Jan 5
        val jan2 = 1704153600000L
        val jan5 = jan2 + (3 * 24 * 60 * 60 * 1000)

        val state = EventFormState(
            isEditMode = true,
            editingEventId = 123L,
            dateMillis = jan2,
            endDateMillis = jan5
        )

        assertTrue("Should show as multi-day", isMultiDayTest(state.dateMillis, state.endDateMillis))
        assertEquals("Start should be Jan 2", jan2, state.dateMillis)
        assertEquals("End should be Jan 5", jan5, state.endDateMillis)
    }

    @Test
    fun `edit mode loads same-day event dates correctly`() {
        val jan2 = 1704153600000L

        val state = EventFormState(
            isEditMode = true,
            editingEventId = 123L,
            dateMillis = jan2,
            endDateMillis = jan2
        )

        assertFalse("Should show as same-day", isMultiDayTest(state.dateMillis, state.endDateMillis))
    }

    @Test
    fun `changing dates in edit mode triggers hasChanges`() {
        val jan2 = 1704153600000L
        val jan5 = jan2 + (3 * 24 * 60 * 60 * 1000)

        val initial = EventFormState(
            isEditMode = true,
            dateMillis = jan2,
            endDateMillis = jan2
        )
        val state = initial.copy(endDateMillis = jan5)

        assertTrue("Changing end date should trigger hasChanges", hasChanges(state, initial))
    }

    // ========== All-Day Event Date Range Tests ==========

    @Test
    fun `all-day event uses unified date range picker`() {
        val jan2 = 1704153600000L
        val jan5 = jan2 + (3 * 24 * 60 * 60 * 1000)

        val state = EventFormState(
            isAllDay = true,
            dateMillis = jan2,
            endDateMillis = jan5
        )

        // All-day events also use the unified picker
        assertTrue("All-day multi-day event detected", isMultiDayTest(state.dateMillis, state.endDateMillis))
    }

    @Test
    fun `toggling all-day preserves date range`() {
        val jan2 = 1704153600000L
        val jan5 = jan2 + (3 * 24 * 60 * 60 * 1000)

        val initial = EventFormState(
            isAllDay = false,
            dateMillis = jan2,
            endDateMillis = jan5
        )
        val state = initial.copy(isAllDay = true)

        assertEquals("Start date preserved", jan2, state.dateMillis)
        assertEquals("End date preserved", jan5, state.endDateMillis)
    }

    // ========== MergedTimeRow Date Label Tests (v5.1.0) ==========

    /**
     * v5.1.0 Change: MergedTimeRow now always used for time selection.
     * shouldShowSeparatePickers() determines if date labels are shown in tabs.
     *
     * For multi-day events, the Start/End tabs show date labels underneath:
     *   [Start]    [End]
     *   [Jan 2]    [Jan 4]
     *
     * For same-day events, no date labels are shown:
     *   [Start]    [End]
     */

    @Test
    fun `multi-day event shows date labels in unified time picker`() {
        val jan2 = 1704153600000L
        val jan4 = jan2 + (2 * 24 * 60 * 60 * 1000)

        // shouldShowSeparatePickers now determines if date labels are shown
        val showDateLabels = shouldShowSeparatePickers(jan2, jan4, 10, 14)
        assertTrue("Multi-day event should show date labels", showDateLabels)
    }

    @Test
    fun `same-day event hides date labels in unified time picker`() {
        val jan2 = 1704153600000L

        val showDateLabels = shouldShowSeparatePickers(jan2, jan2, 10, 14)
        assertFalse("Same-day event should hide date labels", showDateLabels)
    }

    @Test
    fun `midnight crossing event hides date labels (single logical event)`() {
        val jan2 = 1704153600000L
        val jan3 = jan2 + (24 * 60 * 60 * 1000)

        // 10 PM to 2 AM = midnight crossing, not a multi-day event
        val showDateLabels = shouldShowSeparatePickers(jan2, jan3, 22, 2)
        assertFalse("Midnight crossing should hide date labels", showDateLabels)
    }

    @Test
    fun `multi-day duration calculation works with unified time picker`() {
        // Simulate: Jan 2 10 AM - Jan 4 3 PM (multi-day event)
        val jan2 = 1704153600000L
        val jan4 = jan2 + (2 * 24 * 60 * 60 * 1000)

        // This is a multi-day event
        assertTrue("Should detect as multi-day", isMultiDayTest(jan2, jan4))

        // Verify duration calculation for multi-day (53 hours = 3180 minutes)
        val startMinutes = 10 * 60  // 10:00 AM
        val endMinutes = 15 * 60    // 3:00 PM
        // Duration across days: (24h - 10h) + 24h + 15h = 53 hours
        val durationMinutes = (24 * 60 - startMinutes) + (24 * 60) + endMinutes
        assertEquals("Multi-day duration calculation", 53 * 60, durationMinutes)
    }

    // ========== v6.1.0 Regression Tests for Multi-Day Bug Fix ==========

    /**
     * Simulates the fixed onEndTimeSelected logic.
     * BUG (pre-v6.1.0): Always reset endDateMillis to dateMillis
     * FIX: Check if already multi-day and preserve endDateMillis
     */
    private fun calculateEndDateMillisForEndTimeChangeFix(
        dateMillis: Long,
        endDateMillis: Long,
        startHour: Int,
        newEndHour: Int
    ): Long {
        val isSameDay = !isMultiDayTest(dateMillis, endDateMillis)
        val crossesMidnight = newEndHour < startHour
        return when {
            !isSameDay -> endDateMillis  // FIX: Preserve multi-day end date
            crossesMidnight -> dateMillis + (24 * 60 * 60 * 1000)
            else -> dateMillis
        }
    }

    @Test
    fun `v6-1-0 regression - onEndTimeSelected preserves endDateMillis for multi-day events`() {
        // Given: Multi-day event Jan 2 - Jan 5
        val jan2 = 1704153600000L
        val jan5 = jan2 + (3 * 24 * 60 * 60 * 1000)

        // Verify it's multi-day
        assertTrue("Should be multi-day", isMultiDayTest(jan2, jan5))

        // When: User changes end time from 3 PM to 4 PM
        val newEndDateMillis = calculateEndDateMillisForEndTimeChangeFix(
            dateMillis = jan2,
            endDateMillis = jan5,
            startHour = 10,
            newEndHour = 16
        )

        // Then: endDateMillis should stay Jan 5 (not reset to Jan 2)
        assertEquals("Should preserve multi-day end date", jan5, newEndDateMillis)
    }

    @Test
    fun `v6-1-0 regression - onEndTimeSelected still detects midnight crossing for same-day`() {
        // Given: Same-day event starting at 10 PM
        val jan2 = 1704153600000L

        // Verify it's same-day
        assertFalse("Should be same-day", isMultiDayTest(jan2, jan2))

        // When: User changes end time to 1 AM (crosses midnight)
        val newEndDateMillis = calculateEndDateMillisForEndTimeChangeFix(
            dateMillis = jan2,
            endDateMillis = jan2,  // Same day
            startHour = 22,        // 10 PM
            newEndHour = 1         // 1 AM (crosses midnight)
        )

        // Then: endDateMillis should move to next day
        val expectedNextDay = jan2 + (24 * 60 * 60 * 1000)
        assertEquals("Should move to next day on midnight crossing", expectedNextDay, newEndDateMillis)
    }

    @Test
    fun `v6-1-0 regression - onEndTimeSelected stays same day for normal same-day event`() {
        // Given: Same-day event
        val jan2 = 1704153600000L

        // When: User changes end time within same day (no midnight crossing)
        val newEndDateMillis = calculateEndDateMillisForEndTimeChangeFix(
            dateMillis = jan2,
            endDateMillis = jan2,
            startHour = 10,
            newEndHour = 15  // 3 PM (no midnight crossing)
        )

        // Then: endDateMillis should stay same day
        assertEquals("Should stay same day", jan2, newEndDateMillis)
    }

    @Test
    fun `v6-1-0 regression - multi-day event time change does not collapse to single day`() {
        // This is the exact bug scenario reported in v6.0.0
        // Given: Multi-day event (conference from Jan 2 - Jan 4)
        val conferenceStart = 1704153600000L  // Jan 2
        val conferenceEnd = conferenceStart + (2 * 24 * 60 * 60 * 1000)  // Jan 4

        assertTrue("Conference should be multi-day", isMultiDayTest(conferenceStart, conferenceEnd))

        // When: User opens time picker and selects ANY end time
        // In v6.0.0, this would ALWAYS reset endDateMillis to dateMillis
        val scenarios = listOf(
            Pair(15, "3 PM - normal time"),
            Pair(23, "11 PM - late time"),
            Pair(9, "9 AM - early time"),
            Pair(1, "1 AM - would be midnight crossing if same-day")
        )

        for ((newEndHour, description) in scenarios) {
            val newEndDateMillis = calculateEndDateMillisForEndTimeChangeFix(
                dateMillis = conferenceStart,
                endDateMillis = conferenceEnd,
                startHour = 10,
                newEndHour = newEndHour
            )

            // All scenarios should preserve Jan 4 end date for multi-day event
            assertEquals(
                "Multi-day should preserve end date: $description",
                conferenceEnd,
                newEndDateMillis
            )
        }
    }

    // ========== Time Validation Tests (v15.0.7) ==========

    /**
     * Simulates the hasTimeConflict logic from EventFormSheet.
     * Returns true if end time is before start time on the same day.
     */
    private fun hasTimeConflict(state: EventFormState): Boolean {
        if (state.isAllDay) return false
        val startDateOnly = normalizeToLocalMidnightTest(state.dateMillis)
        val endDateOnly = normalizeToLocalMidnightTest(state.endDateMillis)
        if (startDateOnly == endDateOnly) {
            val startMins = state.startHour * 60 + state.startMinute
            val endMins = state.endHour * 60 + state.endMinute
            return endMins < startMins
        }
        return false
    }

    /**
     * Normalize timestamp to local midnight for date comparison.
     */
    private fun normalizeToLocalMidnightTest(millis: Long): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = millis
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    @Test
    fun `hasTimeConflict returns false when end time after start time`() {
        val state = EventFormState(
            dateMillis = 1704106800000L,
            endDateMillis = 1704106800000L,
            startHour = 10,
            startMinute = 0,
            endHour = 11,
            endMinute = 0,
            isAllDay = false
        )
        assertFalse("End time after start time should be valid", hasTimeConflict(state))
    }

    @Test
    fun `hasTimeConflict returns true when end time before start time same day`() {
        val state = EventFormState(
            dateMillis = 1704106800000L,
            endDateMillis = 1704106800000L,
            startHour = 15, // 3 PM
            startMinute = 0,
            endHour = 14,   // 2 PM (before start)
            endMinute = 0,
            isAllDay = false
        )
        assertTrue("End time before start time should be invalid", hasTimeConflict(state))
    }

    @Test
    fun `hasTimeConflict returns false for all-day events`() {
        val state = EventFormState(
            dateMillis = 1704106800000L,
            endDateMillis = 1704106800000L,
            startHour = 15,
            startMinute = 0,
            endHour = 14, // Would conflict if not all-day
            endMinute = 0,
            isAllDay = true // All-day events skip time validation
        )
        assertFalse("All-day events should skip time validation", hasTimeConflict(state))
    }

    @Test
    fun `hasTimeConflict returns false when dates are different`() {
        val day1 = 1704106800000L
        val day2 = day1 + (24 * 60 * 60 * 1000) // Next day
        val state = EventFormState(
            dateMillis = day1,
            endDateMillis = day2, // Different day
            startHour = 22, // 10 PM
            startMinute = 0,
            endHour = 2,    // 2 AM next day - hour is "before" but date is different
            endMinute = 0,
            isAllDay = false
        )
        assertFalse("Different dates should allow any end hour", hasTimeConflict(state))
    }

    @Test
    fun `hasTimeConflict returns false for equal start and end time - zero duration allowed`() {
        val state = EventFormState(
            dateMillis = 1704106800000L,
            endDateMillis = 1704106800000L,
            startHour = 15, // 3 PM
            startMinute = 30,
            endHour = 15,   // 3 PM (same as start)
            endMinute = 30,
            isAllDay = false
        )
        assertFalse("Zero-duration events (end = start) should be valid", hasTimeConflict(state))
    }

    @Test
    fun `hasTimeConflict handles midnight boundary correctly`() {
        val state = EventFormState(
            dateMillis = 1704106800000L,
            endDateMillis = 1704106800000L,
            startHour = 23, // 11 PM
            startMinute = 30,
            endHour = 0,    // 12 AM (midnight)
            endMinute = 30,
            isAllDay = false
        )
        // Same date with end hour 0 < start hour 23 = conflict
        assertTrue("End at midnight (hour 0) before start at 11 PM should be invalid", hasTimeConflict(state))
    }

    @Test
    fun `hasTimeConflict returns false for multi-day event with earlier end hour`() {
        val day1 = 1704106800000L
        val day2 = day1 + (2 * 24 * 60 * 60 * 1000) // 2 days later
        val state = EventFormState(
            dateMillis = day1,
            endDateMillis = day2,
            startHour = 14, // 2 PM on day 1
            startMinute = 0,
            endHour = 10,   // 10 AM on day 3 (earlier hour, but different day)
            endMinute = 0,
            isAllDay = false
        )
        assertFalse("Multi-day event with earlier end hour should be valid", hasTimeConflict(state))
    }

    @Test
    fun `hasTimeConflict edge case - end time 1 minute before start`() {
        val state = EventFormState(
            dateMillis = 1704106800000L,
            endDateMillis = 1704106800000L,
            startHour = 15, // 3:00 PM
            startMinute = 0,
            endHour = 14,   // 2:59 PM (1 minute before)
            endMinute = 59,
            isAllDay = false
        )
        assertTrue("End time 1 minute before start should be invalid", hasTimeConflict(state))
    }

    @Test
    fun `hasTimeConflict edge case - end time 1 minute after start`() {
        val state = EventFormState(
            dateMillis = 1704106800000L,
            endDateMillis = 1704106800000L,
            startHour = 15, // 3:00 PM
            startMinute = 0,
            endHour = 15,   // 3:01 PM (1 minute after)
            endMinute = 1,
            isAllDay = false
        )
        assertFalse("End time 1 minute after start should be valid", hasTimeConflict(state))
    }

    // ========== Save Button Enablement Tests ==========

    /**
     * Simulates the save button enabled state logic.
     */
    private fun isSaveButtonEnabled(
        title: String,
        isSaving: Boolean,
        hasTimeConflict: Boolean,
        showDiscardConfirm: Boolean = false
    ): Boolean {
        return !showDiscardConfirm && title.isNotBlank() && !isSaving && !hasTimeConflict
    }

    @Test
    fun `save button disabled when time conflict exists`() {
        assertFalse(
            "Save should be disabled on time conflict",
            isSaveButtonEnabled(
                title = "Valid Title",
                isSaving = false,
                hasTimeConflict = true
            )
        )
    }

    @Test
    fun `save button enabled when no time conflict`() {
        assertTrue(
            "Save should be enabled when no conflict",
            isSaveButtonEnabled(
                title = "Valid Title",
                isSaving = false,
                hasTimeConflict = false
            )
        )
    }

    @Test
    fun `save button disabled when title empty even without time conflict`() {
        assertFalse(
            "Save should be disabled with empty title",
            isSaveButtonEnabled(
                title = "",
                isSaving = false,
                hasTimeConflict = false
            )
        )
    }

    @Test
    fun `save button disabled when saving even without time conflict`() {
        assertFalse(
            "Save should be disabled while saving",
            isSaveButtonEnabled(
                title = "Valid Title",
                isSaving = true,
                hasTimeConflict = false
            )
        )
    }
}
