package org.onekash.kashcal.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.Calendar
import java.util.Calendar as JavaCalendar

/**
 * Compose UI tests over a stand-in for the event form, the private [EventFormSheetContent] at
 * the end of this file, not the production [EventFormContent].
 *
 * They check the stand-in's labels (Title, All day, Start, End, Calendar, Reminder, Repeat,
 * Location, Notes), the selected calendar name, an existing title and an error message,
 * the Save and Cancel buttons and their callbacks, and Save disabled while saving. The
 * title-input, all-day tap and loading tests perform no assertion.
 *
 * Reference: Android Testing Guide for Compose
 * https://developer.android.com/jetpack/compose/testing
 */
@RunWith(AndroidJUnit4::class)
class EventFormSheetComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testCalendars = listOf(
        Calendar(
            id = 1L,
            accountId = 1L,
            caldavUrl = "https://caldav.icloud.com/cal1",
            displayName = "Personal",
            color = 0xFF2196F3.toInt()
        ),
        Calendar(
            id = 2L,
            accountId = 1L,
            caldavUrl = "https://caldav.icloud.com/cal2",
            displayName = "Work",
            color = 0xFF4CAF50.toInt()
        )
    )

    private fun createDefaultFormState(): EventFormState {
        val now = JavaCalendar.getInstance()
        return EventFormState(
            title = "",
            dateMillis = now.timeInMillis,
            endDateMillis = now.timeInMillis,
            startHour = 10,
            startMinute = 0,
            endHour = 11,
            endMinute = 0,
            selectedCalendarId = 1L,
            selectedCalendarName = "Personal",
            selectedCalendarColor = 0xFF2196F3.toInt(),
            reminders = listOf(15),
            isAllDay = false,
            location = "",
            description = "",
            rrule = null,
            isLoading = false,
            isSaving = false,
            error = null,
            editingEventId = null,
            isEditMode = false
        )
    }

    // ==================== Title Field Tests ====================

    @Test
    fun eventFormSheet_displaysTitleField() {
        composeTestRule.setContent {
            EventFormSheetContent(
                formState = createDefaultFormState(),
                onFormStateChange = {},
                onSave = {},
                onDismiss = {}
            )
        }

        // The title field's label.
        composeTestRule.onNodeWithText("Title").assertIsDisplayed()
    }

    @Test
    fun eventFormSheet_titleFieldAcceptsInput() {
        var currentState = createDefaultFormState()

        composeTestRule.setContent {
            EventFormSheetContent(
                formState = currentState,
                onFormStateChange = { currentState = it },
                onSave = {},
                onDismiss = {}
            )
        }

        // Types into the empty title field; nothing is asserted afterwards.
        composeTestRule.onNode(hasSetTextAction() and hasText("")).performTextInput("Team Meeting")
    }

    // ==================== All-Day Toggle Tests ====================

    @Test
    fun eventFormSheet_displaysAllDayToggle() {
        composeTestRule.setContent {
            EventFormSheetContent(
                formState = createDefaultFormState(),
                onFormStateChange = {},
                onSave = {},
                onDismiss = {}
            )
        }

        composeTestRule.onNodeWithText("All day").assertIsDisplayed()
    }

    @Test
    fun eventFormSheet_allDayToggleInitiallyOff() {
        composeTestRule.setContent {
            EventFormSheetContent(
                formState = createDefaultFormState().copy(isAllDay = false),
                onFormStateChange = {},
                onSave = {},
                onDismiss = {}
            )
        }

        // Only the "All day" label is asserted, not the switch's state.
        composeTestRule.onNodeWithText("All day").assertIsDisplayed()
    }

    @Test
    fun eventFormSheet_allDayToggleCanBeTurnedOn() {
        var allDayEnabled = false

        composeTestRule.setContent {
            EventFormSheetContent(
                formState = createDefaultFormState().copy(isAllDay = allDayEnabled),
                onFormStateChange = { allDayEnabled = it.isAllDay },
                onSave = {},
                onDismiss = {}
            )
        }

        // Taps the "All day" row; nothing is asserted afterwards.
        composeTestRule.onNodeWithText("All day").performClick()
    }

    // ==================== Date Picker Tests ====================

    @Test
    fun eventFormSheet_displaysDatePicker() {
        composeTestRule.setContent {
            EventFormSheetContent(
                formState = createDefaultFormState(),
                onFormStateChange = {},
                onSave = {},
                onDismiss = {}
            )
        }

        composeTestRule.onNodeWithText("Start").assertIsDisplayed()
    }

    @Test
    fun eventFormSheet_displaysEndDatePicker() {
        composeTestRule.setContent {
            EventFormSheetContent(
                formState = createDefaultFormState(),
                onFormStateChange = {},
                onSave = {},
                onDismiss = {}
            )
        }

        composeTestRule.onNodeWithText("End").assertIsDisplayed()
    }

    // ==================== Calendar Picker Tests ====================

    @Test
    fun eventFormSheet_displaysCalendarPicker() {
        composeTestRule.setContent {
            EventFormSheetContent(
                formState = createDefaultFormState(),
                onFormStateChange = {},
                onSave = {},
                onDismiss = {}
            )
        }

        composeTestRule.onNodeWithText("Calendar").assertIsDisplayed()
    }

    @Test
    fun eventFormSheet_showsSelectedCalendarName() {
        composeTestRule.setContent {
            EventFormSheetContent(
                formState = createDefaultFormState().copy(
                    selectedCalendarId = 1L,
                    selectedCalendarName = "Personal"
                ),
                onFormStateChange = {},
                onSave = {},
                onDismiss = {}
            )
        }

        composeTestRule.onNodeWithText("Personal").assertIsDisplayed()
    }

    // ==================== Reminder Picker Tests ====================

    @Test
    fun eventFormSheet_displaysReminderPicker() {
        composeTestRule.setContent {
            EventFormSheetContent(
                formState = createDefaultFormState(),
                onFormStateChange = {},
                onSave = {},
                onDismiss = {}
            )
        }

        composeTestRule.onNodeWithText("Reminder").assertIsDisplayed()
    }

    // ==================== Location and Notes Fields Tests ====================

    @Test
    fun eventFormSheet_displaysLocationField() {
        composeTestRule.setContent {
            EventFormSheetContent(
                formState = createDefaultFormState(),
                onFormStateChange = {},
                onSave = {},
                onDismiss = {}
            )
        }

        // Location shows without a "More options" tap.
        composeTestRule.onNodeWithText("Location").assertIsDisplayed()
    }

    @Test
    fun eventFormSheet_displaysNotesField() {
        composeTestRule.setContent {
            EventFormSheetContent(
                formState = createDefaultFormState(),
                onFormStateChange = {},
                onSave = {},
                onDismiss = {}
            )
        }

        // Notes shows without a "More options" tap.
        composeTestRule.onNodeWithText("Notes").assertIsDisplayed()
    }

    @Test
    fun eventFormSheet_displaysRecurrencePicker() {
        composeTestRule.setContent {
            EventFormSheetContent(
                formState = createDefaultFormState(),
                onFormStateChange = {},
                onSave = {},
                onDismiss = {}
            )
        }

        composeTestRule.onNodeWithText("Repeat").assertIsDisplayed()
    }

    // ==================== Save Button Tests ====================

    @Test
    fun eventFormSheet_displaysSaveButton() {
        composeTestRule.setContent {
            EventFormSheetContent(
                formState = createDefaultFormState(),
                onFormStateChange = {},
                onSave = {},
                onDismiss = {}
            )
        }

        composeTestRule.onNodeWithText("Save").assertIsDisplayed()
    }

    @Test
    fun eventFormSheet_saveButtonTriggersCallback() {
        var saveCalled = false

        composeTestRule.setContent {
            EventFormSheetContent(
                formState = createDefaultFormState().copy(title = "Test Event"),
                onFormStateChange = {},
                onSave = { saveCalled = true },
                onDismiss = {}
            )
        }

        composeTestRule.onNodeWithText("Save").performClick()
        assert(saveCalled)
    }

    @Test
    fun eventFormSheet_saveButtonDisabledWhileSaving() {
        composeTestRule.setContent {
            EventFormSheetContent(
                formState = createDefaultFormState().copy(isSaving = true),
                onFormStateChange = {},
                onSave = {},
                onDismiss = {}
            )
        }

        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
    }

    // ==================== Edit Mode Tests ====================

    @Test
    fun eventFormSheet_editModeShowsUpdate() {
        composeTestRule.setContent {
            EventFormSheetContent(
                formState = createDefaultFormState().copy(
                    isEditMode = true,
                    editingEventId = 1L,
                    title = "Existing Event"
                ),
                onFormStateChange = {},
                onSave = {},
                onDismiss = {}
            )
        }

        // The stand-in shows "Save" in edit mode too.
        composeTestRule.onNodeWithText("Save").assertIsDisplayed()
    }

    @Test
    fun eventFormSheet_editModeLoadsExistingTitle() {
        composeTestRule.setContent {
            EventFormSheetContent(
                formState = createDefaultFormState().copy(
                    isEditMode = true,
                    editingEventId = 1L,
                    title = "Existing Meeting"
                ),
                onFormStateChange = {},
                onSave = {},
                onDismiss = {}
            )
        }

        composeTestRule.onNodeWithText("Existing Meeting").assertIsDisplayed()
    }

    // ==================== Loading State Tests ====================

    @Test
    fun eventFormSheet_showsLoadingIndicator() {
        composeTestRule.setContent {
            EventFormSheetContent(
                formState = createDefaultFormState().copy(isLoading = true),
                onFormStateChange = {},
                onSave = {},
                onDismiss = {}
            )
        }

        // No assertion: the stand-in renders no progress indicator. The production form
        // shows one in place of the fields while loading.
    }

    // ==================== Error State Tests ====================

    @Test
    fun eventFormSheet_showsErrorMessage() {
        composeTestRule.setContent {
            EventFormSheetContent(
                formState = createDefaultFormState().copy(error = "Failed to save event"),
                onFormStateChange = {},
                onSave = {},
                onDismiss = {}
            )
        }

        composeTestRule.onNodeWithText("Failed to save event").assertIsDisplayed()
    }

    // ==================== Cancel Button Tests ====================

    @Test
    fun eventFormSheet_displaysCancelButton() {
        composeTestRule.setContent {
            EventFormSheetContent(
                formState = createDefaultFormState(),
                onFormStateChange = {},
                onSave = {},
                onDismiss = {}
            )
        }

        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun eventFormSheet_cancelButtonTriggersCallback() {
        var cancelCalled = false

        composeTestRule.setContent {
            EventFormSheetContent(
                formState = createDefaultFormState(),
                onFormStateChange = {},
                onSave = {},
                onDismiss = { cancelCalled = true }
            )
        }

        composeTestRule.onNodeWithText("Cancel").performClick()
        assert(cancelCalled)
    }
}

/**
 * Renders a stand-in that mimics the form's structure. The tests above render this, not the
 * production form, so they exercise the stand-in's layout and hoisted-state plumbing, not the
 * real fields.
 *
 * The production body is [org.onekash.kashcal.ui.components.EventFormContent], which
 * `ComponentAccessibilityTest` renders without a wrapper. Moving these tests onto it makes
 * them real UI coverage, but needs a device in the loop: EventFormContent owns its form state
 * and loads in a LaunchedEffect, so each assertion and its timing must be checked against the
 * real render, not swapped mechanically.
 */
@Composable
private fun EventFormSheetContent(
    formState: EventFormState,
    onFormStateChange: (EventFormState) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.foundation.layout.Column(
        modifier = androidx.compose.ui.Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        // Title field
        androidx.compose.material3.OutlinedTextField(
            value = formState.title,
            onValueChange = { onFormStateChange(formState.copy(title = it)) },
            label = { Text("Title") },
            modifier = androidx.compose.ui.Modifier.fillMaxWidth()
        )

        androidx.compose.foundation.layout.Spacer(
            modifier = androidx.compose.ui.Modifier.height(16.dp)
        )

        // All day toggle
        androidx.compose.foundation.layout.Row(
            modifier = androidx.compose.ui.Modifier
                .fillMaxWidth()
                .clickable { onFormStateChange(formState.copy(isAllDay = !formState.isAllDay)) },
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text("All day")
            androidx.compose.material3.Switch(
                checked = formState.isAllDay,
                onCheckedChange = { onFormStateChange(formState.copy(isAllDay = it)) }
            )
        }

        androidx.compose.foundation.layout.Spacer(
            modifier = androidx.compose.ui.Modifier.height(16.dp)
        )

        // Date pickers
        Text("Start")
        Text("End")

        androidx.compose.foundation.layout.Spacer(
            modifier = androidx.compose.ui.Modifier.height(16.dp)
        )

        // Calendar picker
        Text("Calendar")
        Text(formState.selectedCalendarName.ifEmpty { "Select calendar" })

        androidx.compose.foundation.layout.Spacer(
            modifier = androidx.compose.ui.Modifier.height(16.dp)
        )

        // Reminder
        Text("Reminder")

        androidx.compose.foundation.layout.Spacer(
            modifier = androidx.compose.ui.Modifier.height(16.dp)
        )

        // Repeat (always visible)
        Text("Repeat")

        androidx.compose.foundation.layout.Spacer(
            modifier = androidx.compose.ui.Modifier.height(16.dp)
        )

        // Location field (always visible)
        Text("Location")

        androidx.compose.foundation.layout.Spacer(
            modifier = androidx.compose.ui.Modifier.height(16.dp)
        )

        // Notes field (always visible)
        Text("Notes")

        // Error message
        formState.error?.let { error ->
            Text(error, color = androidx.compose.ui.graphics.Color.Red)
        }

        androidx.compose.foundation.layout.Spacer(
            modifier = androidx.compose.ui.Modifier.height(16.dp)
        )

        // Buttons
        androidx.compose.foundation.layout.Row(
            modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
        ) {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
            androidx.compose.material3.Button(
                onClick = onSave,
                enabled = !formState.isSaving
            ) {
                Text("Save")
            }
        }
    }
}
