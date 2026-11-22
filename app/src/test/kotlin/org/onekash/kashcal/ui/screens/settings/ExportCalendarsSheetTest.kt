package org.onekash.kashcal.ui.screens.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.ui.components.sheetSizes
import org.onekash.kashcal.ui.components.topFraction
import org.onekash.kashcal.ui.viewmodels.CalendarExportState
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose tests for the Export calendars sheet on a phone-sized screen: full height whatever its
 * row count and before its counts arrive, a row per calendar that shares it on a tap and leaves
 * the sheet open, empty calendars dimmed and inert, the delayed "Preparing…" row, and a failure
 * shown under its row with Try Again. Run the class alone: Robolectric runs of more than one
 * class hit a native crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h640dp-mdpi")
class ExportCalendarsSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private class Calls {
        val exported = mutableListOf<Long>()
        var dismissed = 0
    }

    private fun section(header: String, vararg rows: ExportCalendarRow, subheader: String? = null) =
        ExportCalendarSection(header, subheader, rows.toList(), key = header + subheader)

    private val home = ExportCalendarRow(1, "Home", 0xFF0000FF.toInt(), 312)
    private val work = ExportCalendarRow(2, "Work", 0xFF00FF00.toInt(), 88)
    private val anniversaries = ExportCalendarRow(3, "Anniversaries", 0xFFFF00FF.toInt(), 0)
    private val local = ExportCalendarRow(4, "Local", 0xFF00FFFF.toInt(), 1)

    private val sections = listOf(
        section("iCloud", home, work),
        section("Contacts", anniversaries),
        section("Offline", local),
    )

    private fun render(
        sections: List<ExportCalendarSection> = this.sections,
        state: CalendarExportState = CalendarExportState(),
        lateSections: List<ExportCalendarSection>? = null,
        calls: Calls = Calls(),
    ): Calls {
        composeTestRule.setContent {
            var shown by remember { mutableStateOf(sections) }
            if (lateSections != null) LaunchedEffect(Unit) { delay(1_500); shown = lateSections }
            MaterialTheme {
                ExportCalendarsSheet(
                    sections = shown,
                    state = state,
                    onExport = { calls.exported += it },
                    onDismiss = { calls.dismissed++ },
                )
            }
        }
        composeTestRule.waitForIdle()
        return calls
    }

    private fun titleTop() = composeTestRule.topFraction(composeTestRule.onNodeWithText("Export calendars"))

    private fun many(count: Int) = listOf(
        section("Offline", *(1L..count).map { ExportCalendarRow(it, "Calendar $it", 0, 1) }.toTypedArray())
    )

    // ---- size ----

    @Test
    fun `one calendar opens the sheet at full height`() {
        render(sections = many(1))
        assertTrue("title at ${titleTop()}", titleTop() < 0.2f)
    }

    @Test
    fun `many calendars open it full and the list scrolls`() {
        render(sections = many(40))
        assertTrue("title at ${titleTop()}", titleTop() < 0.2f)

        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Calendar 40"))
        composeTestRule.onNodeWithText("Calendar 40").assertIsDisplayed()
    }

    @Test
    fun `counts that arrive after it opened leave its size alone`() {
        composeTestRule.mainClock.autoAdvance = false
        val unknown = sections.map { s -> s.copy(rows = s.rows.map { it.copy(eventCount = null) }) }
        render(sections = unknown, lateSections = sections)
        composeTestRule.mainClock.advanceTimeBy(1_000)
        val before = composeTestRule.sheetSizes()
        assertTrue("no sheet found", before.isNotEmpty())

        composeTestRule.mainClock.advanceTimeBy(3_000)
        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitForIdle()

        assertEquals(before, composeTestRule.sheetSizes())
        composeTestRule.onNodeWithText("312 events").assertIsDisplayed()
    }

    @Test
    fun `before counts arrive no row is dimmed or inert`() {
        val unknown = sections.map { s -> s.copy(rows = s.rows.map { it.copy(eventCount = null) }) }
        val calls = render(sections = unknown)

        composeTestRule.onNodeWithText("No events").assertDoesNotExist()
        composeTestRule.onNodeWithText("Anniversaries").performClick()
        assertEquals(listOf(3L), calls.exported)
    }

    // ---- content ----

    @Test
    fun `shows the title, headers and each calendar's count with no info button`() {
        render()

        composeTestRule.onNodeWithText("Export calendars").assertIsDisplayed()
        composeTestRule.onNodeWithText("iCloud").assertIsDisplayed()
        composeTestRule.onNodeWithText("Contacts").assertIsDisplayed()
        composeTestRule.onNodeWithText("312 events").assertIsDisplayed()
        composeTestRule.onNodeWithText("1 event").assertIsDisplayed()
        composeTestRule.onNode(hasContentDescription("info", substring = true, ignoreCase = true)).assertDoesNotExist()
    }

    @Test
    fun `a shared header shows the login under it`() {
        render(sections = listOf(section("Nextcloud", home, subheader = "user@example.test")))
        composeTestRule.onNodeWithText("user@example.test").assertIsDisplayed()
    }

    @Test
    fun `a calendar's row is one button labelled for TalkBack`() {
        render()
        composeTestRule.onNode(hasText("Home") and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick), useUnmergedTree = false)
            .assertHasClickAction()
            .assertIsEnabled()
        val label = composeTestRule.onNode(hasText("Home"), useUnmergedTree = false)
            .fetchSemanticsNode().config[SemanticsActions.OnClick].label
        assertEquals("Export Home", label)
    }

    @Test
    fun `tapping a calendar exports it and keeps the sheet open`() {
        val calls = render()

        composeTestRule.onNodeWithText("Home").performClick()

        assertEquals(listOf(1L), calls.exported)
        assertEquals(0, calls.dismissed)
        composeTestRule.onNodeWithText("Export calendars").assertIsDisplayed()
    }

    @Test
    fun `an empty calendar says No events and doesn't respond`() {
        val calls = render()

        composeTestRule.onNodeWithText("No events").assertIsDisplayed()
        composeTestRule.onNode(hasText("Anniversaries"), useUnmergedTree = false).assertIsNotEnabled()
        composeTestRule.onNodeWithText("Anniversaries").performClick()
        assertEquals(emptyList<Long>(), calls.exported)
    }

    // ---- while a file is built ----

    @Test
    fun `while an export runs other rows wait`() {
        val calls = render(state = CalendarExportState(busyCalendarId = 1))

        composeTestRule.onNodeWithText("Work").performClick()
        composeTestRule.onNodeWithText("Home").performClick()

        assertEquals(emptyList<Long>(), calls.exported)
        composeTestRule.onNodeWithText("Preparing…").assertDoesNotExist()
    }

    @Test
    fun `Preparing shows on the busy row and is announced`() {
        render(state = CalendarExportState(busyCalendarId = 1, showPreparing = true))

        composeTestRule.onNodeWithText("Preparing…").assertIsDisplayed()
        composeTestRule.onNodeWithText("312 events").assertDoesNotExist()
        val polite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
        composeTestRule.onNode(polite and hasContentDescription("Preparing Home")).assertExists()
        composeTestRule.onNode(hasText("Work"), useUnmergedTree = false).assertIsNotEnabled()
    }

    // ---- failure ----

    @Test
    fun `a failure shows under its row with Try Again`() {
        val calls = render(state = CalendarExportState(failedCalendarId = 2))

        composeTestRule.onNodeWithText("Couldn't create the file.").assertIsDisplayed()
        val polite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
        composeTestRule.onNode(polite and hasText("Couldn't create the file.", substring = true)).assertExists()
        val errorTop = composeTestRule.onNodeWithText("Couldn't create the file.").fetchSemanticsNode().boundsInWindow.top
        val workBottom = composeTestRule.onNodeWithText("Work").fetchSemanticsNode().boundsInWindow.bottom
        val anniversariesTop = composeTestRule.onNodeWithText("Anniversaries").fetchSemanticsNode().boundsInWindow.top
        assertTrue("under Work", errorTop >= workBottom && errorTop < anniversariesTop)

        composeTestRule.onNodeWithText("Try Again").performClick()
        assertEquals(listOf(2L), calls.exported)
    }

    // ---- closing ----

    @Test
    fun `a drag down from the list's top closes the sheet`() {
        val calls = render()

        composeTestRule.onNode(hasScrollAction())
            .performTouchInput { swipeDown(startY = top + 10f, endY = bottom - 10f, durationMillis = 200) }
        composeTestRule.waitForIdle()

        assertEquals(1, calls.dismissed)
    }
}
