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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.ui.viewmodels.DeviceCalendarException
import org.onekash.kashcal.ui.viewmodels.QuickAddViewModel
import org.onekash.kashcal.util.CalendarIntentData

@Composable
fun QuickAddDialog(
    onDismiss: () -> Unit,
    onSaved: (Event) -> Unit,
    onExpand: (CalendarIntentData) -> Unit,
    onSaveError: (String) -> Unit,
    viewModel: QuickAddViewModel = hiltViewModel()
) {
    val inputText by viewModel.inputText.collectAsState()
    val parseResult by viewModel.parseResult.collectAsState()
    val isSaveEnabled by viewModel.isSaveEnabled.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val hapticFeedback = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    // Reset state when dialog appears
    LaunchedEffect(Unit) {
        viewModel.resetState()
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
                    // Auto-focus and open keyboard after layout
                    LaunchedEffect(Unit) {
                        delay(100) // Wait for focusRequester to attach
                        focusRequester.requestFocus()
                        keyboardController?.show()
                    }

                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { viewModel.onInputChanged(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        placeholder = { Text("Coffee tomorrow at 3pm") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = if (isSaveEnabled) ImeAction.Done else ImeAction.Default
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { if (isSaveEnabled && !isSaving) onSave() }
                        )
                    )

                    QuickAddPreview(result = parseResult)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = {
                                coroutineScope.launch {
                                    val intentData = viewModel.toCalendarIntentData()
                                    onExpand(intentData)
                                }
                            }
                        ) {
                            Text("More options")
                        }
                        Button(
                            onClick = onSave,
                            enabled = isSaveEnabled && !isSaving
                        ) {
                            Text("Save")
                        }
                    }
                }
            }
        }
    }
}
