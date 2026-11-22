package org.onekash.kashcal.ui.print

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollAction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.ui.viewmodels.PrintSheetUiState
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.Locale

/**
 * Compose tests for the print options sheet: its title with no intro line, the week row, the
 * Pages and calendar rows as single checkbox targets with the Hidden line, the summary, Print
 * disabled with nothing to print, and an error shown inside the sheet. Run the class alone:
 * Robolectric runs of more than one Compose class hit a native crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h2000dp-mdpi")
class PrintOptionsSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val savedLocale = Locale.getDefault()

    @Before
    fun pinLocale() = Locale.setDefault(Locale.US)

    @After
    fun restoreLocale() = Locale.setDefault(savedLocale)

    private val work = PrintCalendarRow(PrintCalendarKey.Room(1L), "Work", 0xFF1E88E5.toInt(), hidden = false)
    private val private = PrintCalendarRow(PrintCalendarKey.Room(2L), "Private", 0xFF8E24AA.toInt(), hidden = true)
    private val birthdays = PrintCalendarRow(PrintCalendarKey.Room(3L), "Birthdays", 0xFF00897B.toInt(), hidden = false)
    private val kids = PrintCalendarRow(PrintCalendarKey.Device(100L), "Kids school", 0xFFFB8C00.toInt(), hidden = false)
    private val sections = listOf(
        PrintCalendarSection("alex@example.test", listOf(PrintCalendarGroup(null, listOf(work, private))), "room_2"),
        PrintCalendarSection("Contacts", listOf(PrintCalendarGroup(null, listOf(birthdays))), "contacts"),
        PrintCalendarSection("Device calendars", listOf(PrintCalendarGroup("alex@gmail.example", listOf(kids))), "device"),
    )

    private val baseState = PrintSheetUiState(
        isOpen = true,
        weekStart = LocalDate.of(2026, 10, 25),
        weekRangeLabel = "Oct 25 - 31, 2026",
        sections = sections,
        selected = setOf(work.key, birthdays.key, kids.key),
    )

    private val calls = mutableListOf<String>()
    private val toggled = mutableListOf<PrintCalendarKey>()

    private fun show(state: PrintSheetUiState = baseState) {
        composeTestRule.setContent {
            MaterialTheme {
                PrintOptionsContent(
                    state = state,
                    onPreviousWeek = { calls += "previous" },
                    onNextWeek = { calls += "next" },
                    onToggleGrid = { calls += "grid" },
                    onToggleDetails = { calls += "details" },
                    onToggleCalendar = { toggled += it },
                    onPrint = { calls += "print" },
                )
            }
        }
    }

    private fun row(name: String) = composeTestRule.onNode(hasText(name) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))

    private fun scrollTo(text: String) =
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText(text))

    @Test
    fun `the title shows with no intro line, and the week row moves the week`() {
        show()
        composeTestRule.onNodeWithText("Choose print options").assertIsDisplayed()
        composeTestRule.onNodeWithText("Choose a week", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Oct 25 - 31, 2026").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("Previous week").performClick()
        composeTestRule.onNodeWithContentDescription("Next week").performClick()
        assertEquals(listOf("previous", "next"), calls)
    }

    @Test
    fun `page rows are single checkbox targets`() {
        show(baseState.copy(includeDetails = false))
        row("Week calendar").assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        row("Details").assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
        composeTestRule.onNodeWithText("The week as a grid").assertIsDisplayed()

        row("Week calendar").performClick()
        row("Details").performClick()
        assertEquals(listOf("grid", "details"), calls)
    }

    @Test
    fun `calendar rows show headers, sub-headers, Hidden and their tick`() {
        show()
        for (text in listOf("Pages", "alex@example.test", "Contacts")) {
            composeTestRule.onNodeWithText(text).assertIsDisplayed()
        }
        row("Private").assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
        composeTestRule.onNodeWithText("Hidden").assertIsDisplayed()
        row("Birthdays").assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))

        scrollTo("Kids school")
        composeTestRule.onNodeWithText("alex@gmail.example").assertIsDisplayed()
        row("Kids school").performClick()
        row("Private").performClick()
        assertEquals(listOf(kids.key, private.key), toggled)
    }

    @Test
    fun `the summary counts pages and calendars, and Print prints`() {
        show()
        composeTestRule.onNodeWithText("2 pages · 3 of 4 calendars").assertIsDisplayed()
        composeTestRule.onNodeWithText("Print").assertIsEnabled().performClick()
        assertEquals(listOf("print"), calls)
    }

    @Test
    fun `Print is disabled with no page or no calendar ticked`() {
        var state by mutableStateOf(baseState.copy(includeGrid = false, includeDetails = false))
        composeTestRule.setContent {
            MaterialTheme {
                PrintOptionsContent(state, {}, {}, {}, {}, {}, { calls += "print" })
            }
        }
        composeTestRule.onNodeWithText("0 pages · 3 of 4 calendars").assertIsDisplayed()
        composeTestRule.onNodeWithText("Print").assertIsNotEnabled()

        state = baseState.copy(selected = emptySet())
        composeTestRule.onNodeWithText("Print").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Print").performClick()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `an error shows inside the sheet`() {
        val host = SnackbarHostState()
        composeTestRule.setContent {
            MaterialTheme {
                PrintOptionsSheet(
                    state = baseState, snackbarHostState = host,
                    onPreviousWeek = {}, onNextWeek = {}, onToggleGrid = {}, onToggleDetails = {},
                    onToggleCalendar = {}, onPrint = {}, onDismiss = {},
                )
                LaunchedEffect(Unit) { host.showSnackbar("Couldn't print. Try again.") }
            }
        }
        composeTestRule.onNodeWithText("Couldn't print. Try again.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Choose print options").assertIsDisplayed()
    }
}
