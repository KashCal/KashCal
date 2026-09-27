package org.onekash.kashcal.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Calendar

/**
 * Tests inline copies of the settings screens' derived-state calculations over a calendar list.
 *
 * Each test runs its own `count { it.isVisible }` (as in `CalendarSheets.kt`) or
 * `find { it.id == id }` (as in AccountSettingsScreen's default-calendar name) and calls no
 * production code. Recomposition is covered by `AccountSettingsScreenRecompositionTest` in
 * androidTest.
 */
class AccountSettingsScreenTest {

    // ==================== visibleCalendarCount Tests ====================

    @Test
    fun `visibleCalendarCount calculation is correct`() {
        val calendars = listOf(
            createCalendar(1, isVisible = true),
            createCalendar(2, isVisible = false),
            createCalendar(3, isVisible = true)
        )
        assertEquals(2, calendars.count { it.isVisible })
    }

    @Test
    fun `visibleCalendarCount handles empty list`() {
        assertEquals(0, emptyList<Calendar>().count { it.isVisible })
    }

    @Test
    fun `visibleCalendarCount handles all visible`() {
        val calendars = listOf(
            createCalendar(1, isVisible = true),
            createCalendar(2, isVisible = true),
            createCalendar(3, isVisible = true)
        )
        assertEquals(3, calendars.count { it.isVisible })
    }

    @Test
    fun `visibleCalendarCount handles none visible`() {
        val calendars = listOf(
            createCalendar(1, isVisible = false),
            createCalendar(2, isVisible = false)
        )
        assertEquals(0, calendars.count { it.isVisible })
    }

    // ==================== defaultCalendar Tests ====================

    @Test
    fun `defaultCalendar find returns correct calendar`() {
        val calendars = listOf(
            createCalendar(1, displayName = "Work"),
            createCalendar(2, displayName = "Personal"),
            createCalendar(3, displayName = "Family")
        )
        assertEquals("Personal", calendars.find { it.id == 2L }?.displayName)
    }

    @Test
    fun `defaultCalendar returns null when id not found`() {
        val calendars = listOf(createCalendar(1))
        assertNull(calendars.find { it.id == 999L })
    }

    @Test
    fun `defaultCalendar returns null for empty list`() {
        val calendars = emptyList<Calendar>()
        assertNull(calendars.find { it.id == 1L })
    }

    @Test
    fun `defaultCalendar returns null for null id`() {
        val calendars = listOf(createCalendar(1))
        val id: Long? = null
        assertNull(calendars.find { it.id == id })
    }

    @Test
    fun `defaultCalendar finds first calendar by id`() {
        val calendars = listOf(
            createCalendar(1, displayName = "First"),
            createCalendar(2, displayName = "Second")
        )
        assertEquals("First", calendars.find { it.id == 1L }?.displayName)
    }

    // ==================== Helper ====================

    private fun createCalendar(
        id: Long,
        displayName: String = "Calendar $id",
        isVisible: Boolean = true
    ) = Calendar(
        id = id,
        accountId = 1L,
        caldavUrl = "https://caldav.example.com/$id",
        displayName = displayName,
        color = 0xFF0000FF.toInt(),
        isVisible = isVisible
    )
}
