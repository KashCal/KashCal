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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.components.LocalNetworkPermissionBanner
import org.onekash.kashcal.ui.components.pickers.ColorPalettePanel
import org.onekash.kashcal.ui.components.pickers.InlineColorField
import org.onekash.kashcal.ui.permission.LocalNetworkPermissionState
import org.onekash.kashcal.ui.shared.EventColorPalette
import org.onekash.kashcal.ui.util.asString

/**
 * Returns a full-height sheet state that refuses to hide while [keepOpen] is true and calls
 * [onRefused] when it does. Back and a tap outside slide a sheet away before asking to dismiss it,
 * so the discard step has to start where the hide is refused; otherwise the Discard button would
 * show on a sheet that has left the screen. A tap outside asks to dismiss only after a hide that
 * went through.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun rememberDiscardGuardedSheetState(
    keepOpen: () -> Boolean,
    onRefused: () -> Unit,
): SheetState {
    val keepOpenNow by rememberUpdatedState(keepOpen)
    val onRefusedNow by rememberUpdatedState(onRefused)
    return rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target ->
            val refused = target == SheetValue.Hidden && keepOpenNow()
            if (refused) onRefusedNow()
            !refused
        },
    )
}

/**
 * Shows a bottom sheet for adding an ICS calendar subscription.
 *
 * The user enters a URL and fetches it; [fetchCalendarInfo] validates the feed and the sheet
 * shows its event count. Name (pre-filled from the feed, else the default calendar name) and
 * Add are enabled only after a successful fetch. The color starts random and the user can change
 * it in an [InlineColorField] palette inside the sheet. It opens at full height. Unchanged, a
 * swipe down closes it; with unsaved changes a swipe does nothing, and the first Back or tap
 * outside keeps the sheet open and turns Cancel into Discard. While the palette is open a swipe
 * does nothing, and Back steps out of the palette ([ColorPalettePanel]) before it reaches the
 * sheet.
 *
 * @param initialUrl a pre-filled URL, for example from a webcal:// link.
 * @param onAdd called with the trimmed url and name and the color.
 * @param localNetworkPermissionState the Android 17+ local-network permission state, resolved
 *   by the host (the rationale read needs an Activity). Defaults to
 *   [LocalNetworkPermissionState.NotRequired] so pre-37 and preview call sites render nothing.
 * @param onRequestLocalNetwork launches the ACCESS_LOCAL_NETWORK request.
 * @param onDialogOpened called once on open so the host can seed a fresh permission-state read,
 *   as the CalDAV sign-in sheet does on open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSubscriptionDialog(
    initialUrl: String? = null,
    onDismiss: () -> Unit,
    onAdd: (url: String, name: String, color: Int) -> Unit,
    localNetworkPermissionState: LocalNetworkPermissionState =
        LocalNetworkPermissionState.NotRequired,
    onRequestLocalNetwork: () -> Unit = {},
    onDialogOpened: () -> Unit = {},
) {
    val initialUrlValue = initialUrl.orEmpty()
    var url by remember { mutableStateOf(initialUrlValue) }
    var name by remember { mutableStateOf("") }
    var selectedColor by remember { mutableStateOf(EventColorPalette.randomArgb()) }
    var fetchState by remember { mutableStateOf<FetchCalendarState>(FetchCalendarState.Idle) }
    var colorExpanded by rememberSaveable { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val defaultCalendarName = stringResource(R.string.default_calendar_name)

    // Banner dismissal for this dialog session. The dialog is composed only while open, so this
    // resets on each open; rememberSaveable keeps a dismissal across a rotation.
    var localNetworkBannerDismissed by rememberSaveable { mutableStateOf(false) }
    // Seed a fresh permission-state read on open so a grant made in system Settings shows.
    LaunchedEffect(Unit) { onDialogOpened() }

    val lanUi = resolveSubscriptionLanUi(
        url = url,
        connectionFailed = (fetchState as? FetchCalendarState.Error)?.connectionFailed == true,
        state = localNetworkPermissionState,
        bannerDismissed = localNetworkBannerDismissed,
    )

    // Set by the first dismiss with unsaved changes; the next dismiss closes the sheet.
    var showDiscardConfirm by remember { mutableStateOf(false) }

    val hasChanges by remember {
        derivedStateOf {
            url != initialUrlValue || name.isNotBlank()
        }
    }

    val sheetState = rememberDiscardGuardedSheetState(
        keepOpen = { hasChanges && !showDiscardConfirm },
        onRefused = { showDiscardConfirm = true },
    )

    ModalBottomSheet(
        // Called after the sheet tried to hide; a refused hide left it on screen, in the discard
        // step, and only a hide that went through closes it.
        onDismissRequest = { if (!sheetState.isVisible) onDismiss() },
        sheetState = sheetState,
        modifier = Modifier.fillMaxHeight(),
        // A drag can close the sheet only while there is nothing to lose: refusing a drag halfway
        // makes the sheet flicker. With changes, Back and a tap outside go through the discard
        // step ([rememberDiscardGuardedSheetState]). An open palette also turns the drag off, so
        // a wheel fling can't take the sheet with it.
        sheetGesturesEnabled = !hasChanges && !colorExpanded
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                stringResource(R.string.dialog_add_subscription),
                style = MaterialTheme.typography.titleLarge
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // Android 17+ local-network banner: inline and dismissible, never blocking the URL
            // field below.
            if (lanUi.showBanner) {
                LocalNetworkPermissionBanner(
                    onAllow = onRequestLocalNetwork,
                    onDismiss = { localNetworkBannerDismissed = true },
                )
            }

            OutlinedTextField(
                value = url,
                onValueChange = {
                    url = it
                    fetchState = FetchCalendarState.Idle
                },
                label = { Text(stringResource(R.string.label_calendar_url)) },
                placeholder = { Text(stringResource(R.string.placeholder_url)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    coroutineScope.launch {
                        fetchState = FetchCalendarState.Loading
                        val result = fetchCalendarInfo(url.trim())
                        fetchState = result
                        if (result is FetchCalendarState.Success) {
                            name = result.name.ifBlank { defaultCalendarName }
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
                Text(if (fetchState is FetchCalendarState.Loading) stringResource(R.string.status_fetching) else stringResource(R.string.action_fetch_calendar))
            }

            FetchResultFeedback(fetchState, appendLanHint = lanUi.appendLanHint)

            // Enabled only after a successful fetch.
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.label_calendar_name)) },
                singleLine = true,
                enabled = fetchState is FetchCalendarState.Success,
                modifier = Modifier.fillMaxWidth()
            )

            InlineColorField(
                selectedArgb = selectedColor,
                expanded = colorExpanded,
                onExpandedChange = { colorExpanded = it },
                onColorSelected = { selectedColor = it },
            )

            // Discard replaces Cancel once a dismiss was refused for unsaved changes.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (showDiscardConfirm) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(stringResource(R.string.action_discard))
                    }
                } else {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
                Button(
                    onClick = { onAdd(url.trim(), name.trim(), selectedColor) },
                    enabled = fetchState is FetchCalendarState.Success && name.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.action_add))
                }
            }
        }
    }
}

/**
 * Shows a bottom sheet for editing a subscription's name, color (an [InlineColorField] palette)
 * and sync interval (an expandable list of [subscriptionSyncIntervalOptions]); opening one of the
 * two lists closes the other. Dismissal works as in [AddSubscriptionDialog].
 *
 * @param onSave called with the trimmed name, the color and the sync interval in hours.
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
    var colorExpanded by rememberSaveable { mutableStateOf(false) }

    // Set by the first dismiss with unsaved changes; the next dismiss closes the sheet.
    var showDiscardConfirm by remember { mutableStateOf(false) }

    val hasChanges by remember {
        derivedStateOf {
            name != subscription.name ||
                selectedColor != subscription.color ||
                selectedInterval != subscription.syncIntervalHours
        }
    }

    val sheetState = rememberDiscardGuardedSheetState(
        keepOpen = { hasChanges && !showDiscardConfirm },
        onRefused = { showDiscardConfirm = true },
    )

    ModalBottomSheet(
        onDismissRequest = { if (!sheetState.isVisible) onDismiss() },
        sheetState = sheetState,
        modifier = Modifier.fillMaxHeight(),
        // Swipe and discard rules as in [AddSubscriptionDialog].
        sheetGesturesEnabled = !hasChanges && !colorExpanded
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                stringResource(R.string.dialog_edit_subscription),
                style = MaterialTheme.typography.titleLarge
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.label_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            InlineColorField(
                selectedArgb = selectedColor,
                expanded = colorExpanded,
                onExpandedChange = { open ->
                    colorExpanded = open
                    if (open) showIntervalPicker = false
                },
                onColorSelected = { selectedColor = it },
            )

            SyncIntervalPicker(
                selectedInterval = selectedInterval,
                showPicker = showIntervalPicker,
                onTogglePicker = {
                    showIntervalPicker = !showIntervalPicker
                    if (showIntervalPicker) colorExpanded = false
                },
                onIntervalSelected = { interval ->
                    selectedInterval = interval
                    showIntervalPicker = false
                }
            )

            // Discard replaces Cancel once a dismiss was refused for unsaved changes.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (showDiscardConfirm) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(stringResource(R.string.action_discard))
                    }
                } else {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
                Button(
                    onClick = { onSave(name.trim(), selectedColor, selectedInterval) },
                    enabled = name.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }
}

/**
 * Shows the fetch's event count on success or its error message on failure; nothing otherwise.
 *
 * @param appendLanHint appends the "allow local network access" hint to the error, next to the
 *   fetch's own message; [resolveSubscriptionLanUi] decides it.
 */
@Composable
private fun FetchResultFeedback(
    fetchState: FetchCalendarState,
    appendLanHint: Boolean = false,
) {
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
                    stringResource(R.string.label_found_events, fetchState.eventCount),
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
                val message = fetchState.message.asString()
                Text(
                    if (appendLanHint) {
                        stringResource(R.string.caldav_error_with_lan_hint, message)
                    } else {
                        message
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        else -> {}
    }
}

/** Shows the selected sync interval; tapping it expands the list of choices. */
@Composable
private fun SyncIntervalPicker(
    selectedInterval: Int,
    showPicker: Boolean,
    onTogglePicker: () -> Unit,
    onIntervalSelected: (Int) -> Unit
) {
    val resources = LocalContext.current.resources
    Column {
        Text(stringResource(R.string.label_sync_interval), style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.height(8.dp))

        val currentLabel = getSyncIntervalLabel(selectedInterval, resources)

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
                            getSyncIntervalLabel(option.hours, resources),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (isSelected) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = stringResource(R.string.cd_selected),
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
