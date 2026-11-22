package org.onekash.kashcal.ui.screens.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.domain.initializer.LocalCalendarInitializer
import org.onekash.kashcal.domain.model.LocalCalendarSummary
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * The Offline calendars screen: its heading and info line (the note closed until the info button
 * opens it), the list with translated names in translated-name order, event counts, the default
 * calendar's row marked for TalkBack, long names on one line, and New calendar opening the sheet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class OfflineCalendarsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun pinLocale() = Locale.setDefault(Locale.US)

    private val info = "Kept on this device and never synced."

    private fun calendar(id: Long, name: String, builtIn: Boolean = false) = Calendar(
        id = id,
        accountId = 1L,
        caldavUrl = if (builtIn) LocalCalendarInitializer.LOCAL_CALENDAR_URL else "local://calendar/test-$id",
        displayName = if (builtIn) LocalCalendarInitializer.LOCAL_CALENDAR_DISPLAY_NAME else name,
        color = 0xFF9E9E9E.toInt(),
    )

    private val builtIn = LocalCalendarSummary(calendar(1, "Local", builtIn = true), 24)
    private val work = LocalCalendarSummary(calendar(2, "Work"), 12)
    private val trips = LocalCalendarSummary(calendar(3, "Trips"), 1)

    private fun render(
        calendars: List<LocalCalendarSummary> = listOf(builtIn, trips, work),
        defaultCalendarId: Long? = null,
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                OfflineCalendarsScreen(
                    calendars = calendars,
                    defaultCalendarId = defaultCalendarId,
                    onNavigateBack = {},
                    onCreateCalendar = { _, _ -> Result.success(99L) },
                    onRenameCalendar = { _, _ -> Result.success(Unit) },
                    onRecolorCalendar = { _, _ -> Result.success(Unit) },
                    onExportCalendar = {},
                    onDeleteCalendar = { Result.success(0) },
                )
            }
        }
    }

    private fun top(text: String): Float =
        composeTestRule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    private fun layoutOf(text: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        composeTestRule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        return layouts.single()
    }

    @Test
    fun `the info note is closed until the info button opens it`() {
        render()
        composeTestRule.onNodeWithText("Offline calendars").assertIsDisplayed()
        composeTestRule.onNodeWithText("Swipe a calendar to delete it.").assertIsDisplayed()
        composeTestRule.onNodeWithText(info).assertDoesNotExist()

        composeTestRule.onNodeWithContentDescription("About offline calendars").performClick()
        composeTestRule.onNodeWithText(info).assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("About offline calendars").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(info).assertDoesNotExist()
    }

    @Test
    fun `calendars are listed by name with their event counts`() {
        render()
        assertTrue(top("Local") < top("Trips"))
        assertTrue(top("Trips") < top("Work"))
        composeTestRule.onNodeWithText("24 events").assertIsDisplayed()
        composeTestRule.onNodeWithText("12 events").assertIsDisplayed()
        composeTestRule.onNodeWithText("1 event").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "de")
    fun `the built-in shows its translated name in its translated place`() {
        // English order puts "Local" before "Lod"; German sorts "Lod" before "Lokal".
        val lod = LocalCalendarSummary(calendar(4, "Lod"), 0)
        render(calendars = listOf(builtIn, lod))
        composeTestRule.onNodeWithText("Lokal").assertIsDisplayed()
        assertTrue(top("Lod") < top("Lokal"))
    }

    @Test
    fun `the default calendar's row is marked for TalkBack and no other is`() {
        render(defaultCalendarId = work.calendar.id)
        composeTestRule.onNode(hasText("Work") and hasStateDescription("Default")).assertExists()
        composeTestRule.onNode(hasText("Trips") and hasStateDescription("Default")).assertDoesNotExist()
        composeTestRule.onNode(hasText("Local") and hasStateDescription("Default")).assertDoesNotExist()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `only the default calendar's row is tinted`() {
        render(defaultCalendarId = work.calendar.id)
        // A pixel near the row's end edge, clear of the name and the count.
        fun endPixel(name: String): Int {
            val image = composeTestRule.onNode(hasText(name) and hasClickAction()).captureToImage().asAndroidBitmap()
            return image.getPixel(image.width - 2, image.height / 2)
        }
        assertNotEquals(endPixel("Trips"), endPixel("Work"))
        assertEquals(endPixel("Trips"), endPixel("Local"))
    }

    @Test
    fun `no row is marked when the default isn't an offline calendar`() {
        render(defaultCalendarId = null)
        composeTestRule.onNode(hasStateDescription("Default")).assertDoesNotExist()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `a long name stays on one line`() {
        val long = "A very long offline calendar name that keeps going well past the edge of the screen"
        render(calendars = listOf(builtIn, LocalCalendarSummary(calendar(5, long), 3)))
        val layout = layoutOf(long)
        assertEquals(1, layout.lineCount)
        assertTrue("the name is cut with an ellipsis, not wrapped", layout.isLineEllipsized(0))
    }

    @Test
    fun `New calendar opens the create sheet`() {
        render()
        composeTestRule.onNodeWithText("New calendar").performClick()
        composeTestRule.onNodeWithText("Create").assertIsDisplayed()
    }

    @Test
    fun `tapping a calendar opens its sheet, which closes when the calendar is gone`() {
        var list by mutableStateOf(listOf(builtIn, trips, work))
        composeTestRule.setContent {
            MaterialTheme {
                OfflineCalendarsScreen(
                    calendars = list,
                    defaultCalendarId = null,
                    onNavigateBack = {},
                    onCreateCalendar = { _, _ -> Result.success(99L) },
                    onRenameCalendar = { _, _ -> Result.success(Unit) },
                    onRecolorCalendar = { _, _ -> Result.success(Unit) },
                    onExportCalendar = {},
                    onDeleteCalendar = { Result.success(0) },
                )
            }
        }
        composeTestRule.onNodeWithText("Work").performClick()
        composeTestRule.onNodeWithText("Rename").assertIsDisplayed()

        list = listOf(builtIn, trips)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Rename").assertDoesNotExist()
    }

    @Test
    fun `an open sheet waits while the list is still loading`() {
        var list by mutableStateOf(listOf(builtIn, trips, work))
        composeTestRule.setContent {
            MaterialTheme {
                OfflineCalendarsScreen(
                    calendars = list,
                    defaultCalendarId = null,
                    onNavigateBack = {},
                    onCreateCalendar = { _, _ -> Result.success(99L) },
                    onRenameCalendar = { _, _ -> Result.success(Unit) },
                    onRecolorCalendar = { _, _ -> Result.success(Unit) },
                    onExportCalendar = {},
                    onDeleteCalendar = { Result.success(0) },
                )
            }
        }
        composeTestRule.onNodeWithText("Work").performClick()
        // A restored screen sees an empty list until its view model loads it.
        list = emptyList()
        composeTestRule.waitForIdle()
        list = listOf(builtIn, trips, work)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Rename").assertIsDisplayed()
    }
}
