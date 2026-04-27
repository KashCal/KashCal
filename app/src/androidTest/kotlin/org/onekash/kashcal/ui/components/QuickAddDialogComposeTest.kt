package org.onekash.kashcal.ui.components

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.domain.quickadd.QuickAddResult
import java.time.LocalDate
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
class QuickAddDialogComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun defaultResult() = QuickAddResult(
        title = "",
        startDate = LocalDate.now()
    )

    private fun setContent(
        textFieldState: TextFieldState = TextFieldState(),
        parseResult: QuickAddResult = defaultResult(),
        isSaveEnabled: Boolean = false,
        isSaving: Boolean = false,
        placeholder: String = "Coffee tomorrow at 3pm",
        onSave: () -> Unit = {},
        onExpand: () -> Unit = {}
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                QuickAddDialogContent(
                    textFieldState = textFieldState,
                    focusRequester = remember { FocusRequester() },
                    parseResult = parseResult,
                    isSaveEnabled = isSaveEnabled,
                    isSaving = isSaving,
                    placeholder = placeholder,
                    onSave = onSave,
                    onExpand = onExpand
                )
            }
        }
    }

    // ==================== Rendering ====================

    @Test
    fun displaysPlaceholderText() {
        setContent(placeholder = "Dentist next Tuesday at 2pm")
        composeTestRule.onNodeWithText("Dentist next Tuesday at 2pm").assertIsDisplayed()
    }

    @Test
    fun displaysSaveButton() {
        setContent()
        composeTestRule.onNodeWithText("Save").assertIsDisplayed()
    }

    @Test
    fun displaysMoreOptionsButton() {
        setContent()
        composeTestRule.onNodeWithText("More options").assertIsDisplayed()
    }

    // ==================== Save Button State ====================

    @Test
    fun saveButton_disabledWhenSaveNotEnabled() {
        setContent(isSaveEnabled = false)
        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun saveButton_enabledWhenSaveEnabled() {
        setContent(isSaveEnabled = true)
        composeTestRule.onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun saveButton_disabledWhileSaving() {
        setContent(isSaveEnabled = true, isSaving = true)
        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
    }

    // ==================== Text Input ====================

    @Test
    fun textFieldAcceptsInput() {
        val state = TextFieldState()
        setContent(textFieldState = state)

        composeTestRule.onNode(hasSetTextAction()).performTextInput("Meeting tomorrow")
        assertEquals("Meeting tomorrow", state.text.toString())
    }

    @Test
    fun textFieldAcceptsNonEnglishInput() {
        val state = TextFieldState()
        setContent(textFieldState = state)

        composeTestRule.onNode(hasSetTextAction()).performTextInput("Kaffee morgen um 15 Uhr")
        assertEquals("Kaffee morgen um 15 Uhr", state.text.toString())
    }

    @Test
    fun textFieldAcceptsCjkInput() {
        val state = TextFieldState()
        setContent(textFieldState = state)

        composeTestRule.onNode(hasSetTextAction()).performTextInput("明日の会議")
        assertEquals("明日の会議", state.text.toString())
    }

    @Test
    fun textFieldAcceptsArabicInput() {
        val state = TextFieldState()
        setContent(textFieldState = state)

        composeTestRule.onNode(hasSetTextAction()).performTextInput("اجتماع غدا")
        assertEquals("اجتماع غدا", state.text.toString())
    }

    @Test
    fun textFieldAcceptsEmojiInput() {
        val state = TextFieldState()
        setContent(textFieldState = state)

        composeTestRule.onNode(hasSetTextAction()).performTextInput("🎂 Birthday party")
        assertEquals("🎂 Birthday party", state.text.toString())
    }

    // ==================== Preview ====================

    @Test
    fun showsPreviewTitle() {
        setContent(
            parseResult = QuickAddResult(
                title = "Team Meeting",
                startDate = LocalDate.now()
            )
        )
        composeTestRule.onNodeWithText("Team Meeting").assertIsDisplayed()
    }

    @Test
    fun showsPreviewTime() {
        setContent(
            parseResult = QuickAddResult(
                title = "Meeting",
                startDate = LocalDate.now(),
                startTime = LocalTime.of(15, 0),
                endTime = LocalTime.of(16, 0)
            )
        )
        composeTestRule.onNodeWithText("Meeting").assertIsDisplayed()
    }

    @Test
    fun showsPreviewAllDay() {
        setContent(
            parseResult = QuickAddResult(
                title = "Conference",
                startDate = LocalDate.now()
            )
        )
        composeTestRule.onNodeWithText("All day").assertIsDisplayed()
    }

    @Test
    fun showsTodayLabel() {
        setContent(
            parseResult = QuickAddResult(
                title = "Meeting",
                startDate = LocalDate.now()
            )
        )
        composeTestRule.onNodeWithText("Today").assertIsDisplayed()
    }

    @Test
    fun showsLocation() {
        setContent(
            parseResult = QuickAddResult(
                title = "Meeting",
                startDate = LocalDate.now(),
                location = "Room 42"
            )
        )
        composeTestRule.onNodeWithText("Room 42").assertIsDisplayed()
    }

    // ==================== Callbacks ====================

    @Test
    fun saveButtonCallsOnSave() {
        var saveCalled = false
        setContent(isSaveEnabled = true, onSave = { saveCalled = true })

        composeTestRule.onNodeWithText("Save").performClick()
        assertTrue(saveCalled)
    }

    @Test
    fun moreOptionsCallsOnExpand() {
        var expandCalled = false
        setContent(onExpand = { expandCalled = true })

        composeTestRule.onNodeWithText("More options").performClick()
        assertTrue(expandCalled)
    }
}
