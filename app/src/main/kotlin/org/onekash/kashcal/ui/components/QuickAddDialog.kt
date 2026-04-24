package org.onekash.kashcal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import org.onekash.kashcal.R
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.ui.viewmodels.DeviceCalendarException
import org.onekash.kashcal.ui.viewmodels.QuickAddViewModel
import org.onekash.kashcal.domain.quickadd.QuickAddResult
import org.onekash.kashcal.util.CalendarIntentData

private val placeholderExamples = listOf(
    // Practical / realistic
    "Coffee tomorrow at 3pm",
    "Dentist next Tuesday at 2pm",
    "Pick up groceries this evening",
    "Call mom Sunday afternoon",
    "Buy flowers for wife tomorrow",
    "Take kids to park Saturday 10am",
    "Date night Friday at 7pm",
    "Lunch with Sarah Thursday noon",
    "Oil change Saturday morning",
    "Haircut next Friday at 11am",
    // Feature showcase
    "Standup every weekday at 9am",
    "Conference Friday to Sunday",
    "Team retro Friday at noon",
    "Book club every Tuesday until December",
    "Yoga every Saturday morning",
    "Flight to NYC Monday 3pm EST",
    "Meeting at quarter past 10",
    "Walk the dog daily",
    // Witty
    "Touch grass tomorrow afternoon",
    "Panic about deadline Friday night",
    "Become a morning person Monday 5am",
    "Nap aggressively Sunday afternoon",
    "Pretend to be productive Monday morning",
    "Regret skipping gym tonight",
    // Easter egg
    "Buy Kash a coffee tomorrow",
)

@Composable
fun QuickAddDialog(
    onDismiss: () -> Unit,
    onSaved: (Event) -> Unit,
    onExpand: (CalendarIntentData) -> Unit,
    onSaveError: (String) -> Unit,
    viewModel: QuickAddViewModel = hiltViewModel()
) {
    val textFieldState = remember { TextFieldState() }
    val parseResult by viewModel.parseResult.collectAsState()
    val isSaveEnabled by viewModel.isSaveEnabled.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val focusRequester = remember { FocusRequester() }
    val placeholder = remember { placeholderExamples.random() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val hapticFeedback = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.resetState()
        snapshotFlow { textFieldState.text.toString() }
            .collect { viewModel.onInputChanged(it) }
    }

    val onSave: () -> Unit = {
        coroutineScope.launch {
            viewModel.save()
                .onSuccess { event ->
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSaved(event)
                }
                .onFailure { e ->
                    if (e is DeviceCalendarException) {
                        // Default is a device calendar — redirect to full form
                        onExpand(viewModel.toCalendarIntentData())
                    } else {
                        onSaveError(e.message ?: "Failed to save event")
                    }
                }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onDismiss() }
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.Top
        ) {
            QuickAddDialogContent(
                textFieldState = textFieldState,
                focusRequester = focusRequester,
                parseResult = parseResult,
                isSaveEnabled = isSaveEnabled,
                isSaving = isSaving,
                placeholder = placeholder,
                onSave = onSave,
                onExpand = {
                    coroutineScope.launch {
                        val intentData = viewModel.toCalendarIntentData()
                        onExpand(intentData)
                    }
                }
            )
        }
    }
}

@Composable
internal fun QuickAddDialogContent(
    textFieldState: TextFieldState,
    focusRequester: FocusRequester,
    parseResult: QuickAddResult,
    isSaveEnabled: Boolean,
    isSaving: Boolean,
    placeholder: String,
    onSave: () -> Unit,
    onExpand: () -> Unit
) {
    val keyboardController = LocalSoftwareKeyboardController.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { /* consume clicks so they don't dismiss */ },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            LaunchedEffect(Unit) {
                focusRequester.requestFocus()
                keyboardController?.show()
            }

            OutlinedTextField(
                state = textFieldState,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                placeholder = { Text(placeholder) },
                lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions = remember {
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done
                    )
                },
                onKeyboardAction = { if (isSaveEnabled && !isSaving) onSave() }
            )

            QuickAddPreview(result = parseResult)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onExpand) {
                    Text(
                        text = stringResource(R.string.action_more_options),
                        maxLines = 1,
                        softWrap = false
                    )
                }
                Button(
                    onClick = onSave,
                    enabled = isSaveEnabled && !isSaving
                ) {
                    Text(
                        text = stringResource(R.string.action_save),
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}
