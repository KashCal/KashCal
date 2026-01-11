package org.onekash.kashcal.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Bottom sheet for adding a new ICS calendar subscription.
 *
 * Features:
 * - URL input with validation
 * - Fetch and validate calendar before adding
 * - Display event count on success
 * - Name field (auto-populated from fetched calendar)
 * - Color picker using shared ColorPicker component
 *
 * @param initialUrl Optional pre-filled URL (e.g., from deep link)
 * @param onDismiss Callback when sheet is dismissed
 * @param onAdd Callback when subscription is added (url, name, color)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSubscriptionDialog(
    initialUrl: String? = null,
    onDismiss: () -> Unit,
    onAdd: (url: String, name: String, color: Int) -> Unit
) {
    val initialUrlValue = initialUrl ?: ""
    var url by remember { mutableStateOf(initialUrlValue) }
    var name by remember { mutableStateOf("") }
    var selectedColor by remember { mutableStateOf(SubscriptionColors.default) }
    var fetchState by remember { mutableStateOf<FetchCalendarState>(FetchCalendarState.Idle) }
    val coroutineScope = rememberCoroutineScope()

    // Dismiss protection state
    var showDiscardConfirm by remember { mutableStateOf(false) }

    // Check if user made changes
    val hasChanges by remember {
        derivedStateOf {
            url != initialUrlValue || name.isNotBlank()
        }
    }

    // Sheet state with dismiss protection
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { newValue ->
            when {
                newValue != SheetValue.Hidden -> true  // Allow expand
                !hasChanges -> true                     // No changes = allow dismiss
                showDiscardConfirm -> true              // Second attempt = allow
                else -> {
                    showDiscardConfirm = true           // First attempt = block & show confirm
                    false
                }
            }
        }
    )

    ModalBottomSheet(
        onDismissRequest = {
            when {
                !hasChanges -> onDismiss()
                showDiscardConfirm -> onDismiss()
                else -> showDiscardConfirm = true
            }
        },
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Title
            Text(
                "Add Calendar Subscription",
                style = MaterialTheme.typography.titleLarge
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // URL Field
            OutlinedTextField(
                value = url,
                onValueChange = {
                    url = it
                    fetchState = FetchCalendarState.Idle
                },
                label = { Text("Calendar URL") },
                placeholder = { Text("https://...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // Fetch Calendar Button
            Button(
                onClick = {
                    coroutineScope.launch {
                        fetchState = FetchCalendarState.Loading
                        val result = fetchCalendarInfo(url.trim())
                        fetchState = result
                        if (result is FetchCalendarState.Success) {
                            name = result.name
                        }
                    }
                },
                enabled = url.isNotBlank() && fetchState !is FetchCalendarState.Loading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (fetchState is FetchCalendarState.Loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (fetchState is FetchCalendarState.Loading) "Fetching..." else "Fetch Calendar")
            }

            // Fetch Result Feedback
            FetchResultFeedback(fetchState)

            // Name Field (enabled only after successful fetch)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Calendar Name") },
                singleLine = true,
                enabled = fetchState is FetchCalendarState.Success,
                modifier = Modifier.fillMaxWidth()
            )

            // Color Picker (inline)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Color:", style = MaterialTheme.typography.bodySmall)
                SubscriptionColorPicker(
                    selectedColor = selectedColor,
                    onColorSelected = { selectedColor = it }
                )
            }

            // Action Buttons - show Discard option when user tried to dismiss with changes
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (showDiscardConfirm) {
                    // Discard button (error color)
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Discard")
                    }
                } else {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }
                }
                Button(
                    onClick = { onAdd(url.trim(), name.trim(), selectedColor) },
                    enabled = fetchState is FetchCalendarState.Success && name.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Add")
                }
            }
        }
    }
}

/**
 * Bottom sheet for editing an existing subscription's settings.
 *
 * Features:
 * - Edit subscription name
 * - Change color using shared ColorPicker
 * - Configure sync interval with dropdown picker
 *
 * @param subscription The subscription to edit
 * @param onSave Callback when changes are saved (name, color, syncIntervalHours)
 * @param onDismiss Callback when sheet is dismissed
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditSubscriptionDialog(
    subscription: IcsSubscriptionUiModel,
    onSave: (name: String, color: Int, syncIntervalHours: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(subscription.name) }
    var selectedColor by remember { mutableStateOf(subscription.color) }
    var selectedInterval by remember { mutableStateOf(subscription.syncIntervalHours) }
    var showIntervalPicker by remember { mutableStateOf(false) }

    // Dismiss protection state
    var showDiscardConfirm by remember { mutableStateOf(false) }

    // Check if user made changes
    val hasChanges by remember {
        derivedStateOf {
            name != subscription.name ||
                selectedColor != subscription.color ||
                selectedInterval != subscription.syncIntervalHours
        }
    }

    // Sheet state with dismiss protection
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { newValue ->
            when {
                newValue != SheetValue.Hidden -> true  // Allow expand
                !hasChanges -> true                     // No changes = allow dismiss
                showDiscardConfirm -> true              // Second attempt = allow
                else -> {
                    showDiscardConfirm = true           // First attempt = block & show confirm
                    false
                }
            }
        }
    )

    ModalBottomSheet(
        onDismissRequest = {
            when {
                !hasChanges -> onDismiss()
                showDiscardConfirm -> onDismiss()
                else -> showDiscardConfirm = true
            }
        },
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Title
            Text(
                "Edit Subscription",
                style = MaterialTheme.typography.titleLarge
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // Name Field
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // Color Picker (inline)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Color:", style = MaterialTheme.typography.bodySmall)
                SubscriptionColorPicker(
                    selectedColor = selectedColor,
                    onColorSelected = { selectedColor = it }
                )
            }

            // Sync Interval Picker
            SyncIntervalPicker(
                selectedInterval = selectedInterval,
                showPicker = showIntervalPicker,
                onTogglePicker = { showIntervalPicker = !showIntervalPicker },
                onIntervalSelected = { interval ->
                    selectedInterval = interval
                    showIntervalPicker = false
                }
            )

            // Action Buttons - show Discard option when user tried to dismiss with changes
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (showDiscardConfirm) {
                    // Discard button (error color)
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Discard")
                    }
                } else {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }
                }
                Button(
                    onClick = { onSave(name.trim(), selectedColor, selectedInterval) },
                    enabled = name.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Save")
                }
            }
        }
    }
}

/**
 * Display fetch result feedback (success/error).
 */
@Composable
private fun FetchResultFeedback(fetchState: FetchCalendarState) {
    when (fetchState) {
        is FetchCalendarState.Success -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = AccentColors.Green,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    "Found: ${fetchState.eventCount} events",
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentColors.Green
                )
            }
        }
        is FetchCalendarState.Error -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    Icons.Default.Error,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    fetchState.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        else -> {}
    }
}

/**
 * Color picker for subscription calendars.
 * Displays 5 colors in a single row.
 */
@Composable
private fun SubscriptionColorPicker(
    selectedColor: Int,
    onColorSelected: (Int) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SubscriptionColors.all.forEach { color ->
            ColorDot(color, selectedColor == color) { onColorSelected(color) }
        }
    }
}

/**
 * Sync interval picker with expandable dropdown.
 */
@Composable
private fun SyncIntervalPicker(
    selectedInterval: Int,
    showPicker: Boolean,
    onTogglePicker: () -> Unit,
    onIntervalSelected: (Int) -> Unit
) {
    Column {
        Text("Sync Interval:", style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.height(8.dp))

        // Current selection as clickable row
        val currentLabel = getSyncIntervalLabel(selectedInterval)

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onTogglePicker() },
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(currentLabel, style = MaterialTheme.typography.bodyMedium)
                Icon(
                    if (showPicker) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Interval options
        AnimatedVisibility(
            visible = showPicker,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(modifier = Modifier.padding(top = 4.dp)) {
                subscriptionSyncIntervalOptions.forEach { option ->
                    val isSelected = option.hours == selectedInterval
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable { onIntervalSelected(option.hours) }
                            .background(
                                if (isSelected)
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                else Color.Transparent
                            )
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            option.label,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (isSelected) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
