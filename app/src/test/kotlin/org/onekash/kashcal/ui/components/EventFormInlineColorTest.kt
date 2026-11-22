package org.onekash.kashcal.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.preferences.DefaultCalendar
import org.onekash.kashcal.domain.initializer.LocalCalendarInitializer
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.ui.components.pickers.DEFAULT_SWATCH_TEST_TAG
import org.onekash.kashcal.ui.components.pickers.swatchTestTag
import org.onekash.kashcal.ui.model.CalendarGroup
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * The event form's color row opens the event palette inside the form: the default cell, a swatch
 * and the wheel's Done set the color and close it, it shares one open picker with calendar,
 * alerts and repeat, it takes focus from the title, a read-only form doesn't open it, and Back
 * closes it before the form's dismiss guard runs. Run the class alone: Robolectric runs of more
 * than one class hit a native crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h9999dp-mdpi")
class EventFormInlineColorTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun pinLocale() = Locale.setDefault(Locale.US)

    private val local = Calendar(
        id = 1L,
        accountId = 1L,
        caldavUrl = LocalCalendarInitializer.LOCAL_CALENDAR_URL,
        displayName = LocalCalendarInitializer.LOCAL_CALENDAR_DISPLAY_NAME,
        color = 0xFF9E9E9E.toInt(),
    )
    private val offline = CalendarGroup(
        accountName = "Offline",
        accountId = 1L,
        calendars = listOf(local),
        provider = AccountProvider.LOCAL,
    )
    private val savedEvent = Event(id = 9L, uid = "form@test", calendarId = 1L, title = "Dentist", startTs = 0L, endTs = 0L, dtstamp = 0L)
    private val dodgerBlue = 0xFF1E90FF.toInt()
    private var dismissRequests = 0

    private fun render(isReadOnly: Boolean = false) {
        composeTestRule.setContent {
            MaterialTheme {
                EventFormContent(
                    modifier = Modifier.fillMaxSize(),
                    onSavingChange = {},
                    onRequestDismiss = { dismissRequests++ },
                    calendars = listOf(local),
                    calendarGroups = listOf(offline),
                    defaultCalendar = DefaultCalendar.Room(1L),
                    onDismiss = {},
                    onSave = { Result.success(savedEvent) },
                    isReadOnly = isReadOnly,
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun colorRow() = composeTestRule.onNodeWithContentDescription("Event color")

    private fun openColor() {
        colorRow().performClick()
        composeTestRule.waitForIdle()
    }

    private fun paletteOpen() = composeTestRule.onNodeWithText("More colors")

    @Test
    fun `the color row opens the palette inside the form`() {
        render()
        openColor()
        paletteOpen().assertIsDisplayed()
        assertEquals(0, composeTestRule.dialogCount())
    }

    @Test
    fun `a swatch sets the color and closes the palette, and the default cell clears it`() {
        render()
        openColor()
        composeTestRule.onNodeWithTag(swatchTestTag(dodgerBlue)).performClick()
        composeTestRule.waitForIdle()
        paletteOpen().assertDoesNotExist()
        composeTestRule.onNode(hasText("Dodger Blue")).assertIsDisplayed()

        openColor()
        composeTestRule.onNodeWithTag(DEFAULT_SWATCH_TEST_TAG).performClick()
        composeTestRule.waitForIdle()
        paletteOpen().assertDoesNotExist()
        composeTestRule.onNode(hasText("Dodger Blue")).assertDoesNotExist()
        composeTestRule.onNode(hasText("Event color")).assertIsDisplayed()
    }

    @Test
    fun `the wheel's Done sets its color`() {
        render()
        openColor()
        paletteOpen().performClick()
        composeTestRule.onNodeWithText("Done").performClick()
        composeTestRule.waitForIdle()
        paletteOpen().assertDoesNotExist()
        composeTestRule.onNodeWithText("Done").assertDoesNotExist()
        // The wheel opens on the first red entry when the event has no color of its own.
        composeTestRule.onNode(hasText("Custom")).assertIsDisplayed()
    }

    @Test
    fun `opening the alerts closes the palette and opening the palette closes the alerts`() {
        render()
        openColor()
        composeTestRule.onNodeWithContentDescription("Alerts").performClick()
        composeTestRule.waitForIdle()
        paletteOpen().assertDoesNotExist()
        composeTestRule.onNodeWithText("Add Alert").assertIsDisplayed()

        openColor()
        paletteOpen().assertIsDisplayed()
        composeTestRule.onNodeWithText("Add Alert").assertDoesNotExist()
    }

    @Test
    fun `opening the calendar or repeat list closes the palette`() {
        render()
        openColor()
        composeTestRule.onNodeWithContentDescription("Calendar", useUnmergedTree = true).performClick()
        composeTestRule.waitForIdle()
        paletteOpen().assertDoesNotExist()

        openColor()
        composeTestRule.onNodeWithContentDescription("Repeat", useUnmergedTree = true).performClick()
        composeTestRule.waitForIdle()
        paletteOpen().assertDoesNotExist()
    }

    @Test
    fun `opening the palette takes focus from the title`() {
        render()
        composeTestRule.runOnUiThread { enterTouchMode(composeTestRule.activity.window.decorView) }
        val title = composeTestRule.onNode(hasSetTextAction() and hasText("Event title"))
        title.performClick()
        title.assertIsFocused()
        openColor()
        title.assertIsNotFocused()
    }

    @Test
    fun `a read-only form doesn't open the palette`() {
        render(isReadOnly = true)
        colorRow().performClick()
        composeTestRule.waitForIdle()
        paletteOpen().assertDoesNotExist()
        // A row that can't open doesn't offer "Expand" to TalkBack.
        colorRow().assert(
            SemanticsMatcher("has no Expand") {
                "Expand" !in it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
            }
        )
    }

    @Test
    fun `Back closes the palette before the form's dismiss guard`() {
        render()
        openColor()
        paletteOpen().performClick()

        composeTestRule.activityBack()
        paletteOpen().assertIsDisplayed()
        assertEquals(0, dismissRequests)

        composeTestRule.activityBack()
        paletteOpen().assertDoesNotExist()
        assertEquals(0, dismissRequests)

        composeTestRule.activityBack()
        assertEquals(1, dismissRequests)
    }
}
