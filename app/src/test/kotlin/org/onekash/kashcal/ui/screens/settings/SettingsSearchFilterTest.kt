package org.onekash.kashcal.ui.screens.settings

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

class SettingsSearchFilterTest {

    private val rows = listOf(
        SearchableRow(id = "time-format", label = "Time Format", subtitle = "12-hour"),
        SearchableRow(id = "sync-lookback", label = "Sync Lookback", subtitle = "30 days"),
        SearchableRow(id = "show-declined", label = "Show declined events", subtitle = "Off"),
        SearchableRow(id = "notifications", label = "Notifications", subtitle = null)
    )

    private var savedLocale: Locale = Locale.getDefault()

    @Before
    fun saveLocale() {
        savedLocale = Locale.getDefault()
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(savedLocale)
    }

    @Test
    fun `empty query returns input unchanged`() {
        val result = filterSettings(rows, "")
        assertEquals(rows, result)
    }

    @Test
    fun `whitespace-only query returns input unchanged`() {
        val result = filterSettings(rows, "   ")
        assertEquals(rows, result)
    }

    @Test
    fun `query 'time' matches Time Format by label`() {
        val result = filterSettings(rows, "time")
        assertEquals(listOf("time-format"), result.map { it.id })
    }

    @Test
    fun `query '30' matches Sync Lookback by subtitle`() {
        val result = filterSettings(rows, "30")
        assertEquals(listOf("sync-lookback"), result.map { it.id })
    }

    @Test
    fun `query 'xyz' returns empty list`() {
        val result = filterSettings(rows, "xyz")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `query is case-insensitive - uppercase matches`() {
        val result = filterSettings(rows, "TIME")
        assertEquals(listOf("time-format"), result.map { it.id })
    }

    @Test
    fun `russian locale - query 'врем' matches russian label`() {
        Locale.setDefault(Locale("ru", "RU"))
        val russianRows = listOf(
            SearchableRow(id = "time-format", label = "Время", subtitle = null),
            SearchableRow(id = "other", label = "Другое", subtitle = null)
        )
        val result = filterSettings(russianRows, "врем")
        assertEquals(listOf("time-format"), result.map { it.id })
    }

    @Test
    fun `null subtitle with non-matching label is excluded`() {
        val result = filterSettings(rows, "30")
        // Notifications has null subtitle; "30" does not match "Notifications"
        assertTrue(result.none { it.id == "notifications" })
    }

    @Test
    fun `null subtitle with matching label is included`() {
        val result = filterSettings(rows, "notif")
        assertEquals(listOf("notifications"), result.map { it.id })
    }
}
