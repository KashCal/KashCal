package org.onekash.kashcal.ui.screens.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.ui.theme.KashCalTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "de-w400dp-h800dp-mdpi")
class MonthWidgetWeeksSheetTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `German picker shows one selected checkmark row and applies a choice immediately`() {
        var picked = -1
        var shown by mutableStateOf(true)
        compose.setContent {
            KashCalTheme {
                if (shown) MonthWidgetWeeksSheet(2, { picked = it }, { shown = false })
            }
        }
        compose.onNodeWithText("Sichtbare Kalenderwochen").assertExists()
        compose.onNodeWithText("1 Woche").assertExists()
        compose.onNodeWithText("2 Wochen").assertExists()
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).assertCountEquals(1)
        compose.onNodeWithText("3 Wochen").performClick()
        assertEquals(3, picked)
        compose.onNodeWithText("Sichtbare Kalenderwochen").assertDoesNotExist()
    }

    @Test
    fun `choices never include a full month or extra weeks for any week start`() {
        for (year in 2024..2028) for (month in 1..12) for (start in listOf(0, 1, 2, 7)) {
            val date = java.time.YearMonth.of(year, month)
            val grid = org.onekash.kashcal.ui.model.MonthGrid.compute(year, month - 1, start)
            val rows = grid.weeks.count { row -> row.any {
                it.position == org.onekash.kashcal.ui.model.MonthGrid.DayPosition.MonthDate
            } }
            assertEquals(listOf(0) + (1 until rows), monthWidgetWeekChoices(date, start))
        }
    }

    @Test
    fun `four week month offers only one two three and whole month and selects legacy six as whole`() {
        var picked = -1
        compose.setContent {
            KashCalTheme {
                MonthWidgetWeeksSheet(6, { picked = it }, {},
                    firstDayOfWeek = java.util.Calendar.SUNDAY,
                    month = java.time.YearMonth.of(2026, 2))
            }
        }
        for (weeks in 4..6) compose.onNodeWithText("$weeks Wochen").assertDoesNotExist()
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).assertCountEquals(1)
        compose.onNodeWithText("Automatisch (ganzer Monat)").performClick()
        assertEquals(0, picked)
    }

    @Test
    fun `automatic option restores the whole month`() {
        var picked = -1
        compose.setContent {
            KashCalTheme { MonthWidgetWeeksSheet(5, { picked = it }, {}) }
        }
        compose.onNodeWithText("Automatisch (ganzer Monat)").performClick()
        assertEquals(0, picked)
    }
}
