package org.onekash.kashcal.ui.components.pickers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests expansion, enabled and accordion logic over local variables, and copies of
 * [ExpandablePickerCard]'s tween durations and disabled alpha (0.6). None calls or renders
 * [ExpandablePickerCard] or [ExpandablePickerCardWithCustomHeader], so a change to either doesn't
 * fail these tests.
 */
class ExpandablePickerCardTest {

    // ==================== Expansion State Tests ====================

    @Test
    fun `expansion state toggles correctly`() {
        var isExpanded = false

        // Initial state
        assertFalse(isExpanded)

        // Toggle to expanded
        isExpanded = !isExpanded
        assertTrue(isExpanded)

        // Toggle back to collapsed
        isExpanded = !isExpanded
        assertFalse(isExpanded)
    }

    @Test
    fun `multiple pickers can have independent expansion states`() {
        var calendarExpanded = false
        var reminderExpanded = false
        var recurrenceExpanded = false

        // Expand calendar
        calendarExpanded = true
        assertTrue(calendarExpanded)
        assertFalse(reminderExpanded)
        assertFalse(recurrenceExpanded)

        // Expand reminder while calendar is expanded
        reminderExpanded = true
        assertTrue(calendarExpanded)
        assertTrue(reminderExpanded)
        assertFalse(recurrenceExpanded)

        // Collapse calendar
        calendarExpanded = false
        assertFalse(calendarExpanded)
        assertTrue(reminderExpanded)
        assertFalse(recurrenceExpanded)
    }

    // ==================== Enabled State Tests ====================

    @Test
    fun `enabled state controls interaction`() {
        var enabled = true
        var toggleCount = 0

        fun onToggle() {
            if (enabled) {
                toggleCount++
            }
        }

        // Toggle when enabled
        onToggle()
        assertEquals(1, toggleCount)

        // Disable and try to toggle
        enabled = false
        onToggle()
        assertEquals(1, toggleCount) // Count should not change

        // Re-enable and toggle
        enabled = true
        onToggle()
        assertEquals(2, toggleCount)
    }

    @Test
    fun `disabled picker maintains expansion state`() {
        var isExpanded = true
        val enabled = false

        // Disabling doesn't collapse the picker.
        assertTrue(isExpanded)

        // A disabled picker only stops responding to input; that click handling lives in the
        // composable (not asserted here).
    }

    // ==================== Label and Value Tests ====================

    @Test
    fun `label and value are stored correctly`() {
        val label = "Calendar"
        val value = "Work"

        assertEquals("Calendar", label)
        assertEquals("Work", value)
    }

    @Test
    fun `empty value is valid`() {
        val label = "Reminder"
        val value = ""

        assertEquals("Reminder", label)
        assertEquals("", value)
    }

    @Test
    fun `value with special characters is valid`() {
        val value = "30 min before (default)"

        assertTrue(value.contains("("))
        assertTrue(value.contains(")"))
    }

    // ==================== Animation Timing Tests ====================

    @Test
    fun `animation durations are consistent`() {
        // Copies of the tween() durations in ExpandablePickerCard.
        val expandDuration = 200
        val fadeInDuration = 150
        val shrinkDuration = 150
        val fadeOutDuration = 100

        // Expand runs at least as long as collapse.
        assertTrue(expandDuration >= shrinkDuration)

        // Fade-in finishes within the expand time.
        assertTrue(fadeInDuration <= expandDuration)

        // Fade-out finishes within the shrink, so content doesn't flash during collapse.
        assertTrue(fadeOutDuration <= shrinkDuration)
    }

    // ==================== Multiple Picker Coordination Tests ====================

    @Test
    fun `only one picker expanded at a time pattern`() {
        // Expanding one picker collapses the others.
        var currentExpanded: String? = null

        fun togglePicker(picker: String) {
            currentExpanded = if (currentExpanded == picker) null else picker
        }

        // Nothing expanded initially
        assertEquals(null, currentExpanded)

        // Expand calendar
        togglePicker("calendar")
        assertEquals("calendar", currentExpanded)

        // Expanding reminder closes calendar.
        togglePicker("reminder")
        assertEquals("reminder", currentExpanded)

        // Toggling reminder again closes it.
        togglePicker("reminder")
        assertEquals(null, currentExpanded)
    }

    @Test
    fun `accordion pattern collapses others`() {
        data class PickerState(var expanded: Boolean)

        val pickers = mapOf(
            "calendar" to PickerState(false),
            "reminder" to PickerState(false),
            "recurrence" to PickerState(false)
        )

        fun expandOnly(name: String) {
            pickers.forEach { (key, state) ->
                state.expanded = (key == name)
            }
        }

        // Expand calendar
        expandOnly("calendar")
        assertTrue(pickers["calendar"]!!.expanded)
        assertFalse(pickers["reminder"]!!.expanded)
        assertFalse(pickers["recurrence"]!!.expanded)

        // Expanding reminder collapses calendar.
        expandOnly("reminder")
        assertFalse(pickers["calendar"]!!.expanded)
        assertTrue(pickers["reminder"]!!.expanded)
        assertFalse(pickers["recurrence"]!!.expanded)
    }

    // ==================== Alpha Transparency Tests ====================

    @Test
    fun `alpha value for enabled state is 1f`() {
        val enabled = true
        val expectedAlpha = if (enabled) 1f else 0.6f

        assertEquals(1f, expectedAlpha, 0.001f)
    }

    @Test
    fun `alpha value for disabled state is 0_6f`() {
        val enabled = false
        val expectedAlpha = if (enabled) 1f else 0.6f

        assertEquals(0.6f, expectedAlpha, 0.001f)
    }
}
