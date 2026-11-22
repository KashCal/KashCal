package org.onekash.kashcal.ui.components

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.onekash.kashcal.R
import org.onekash.kashcal.domain.initializer.LocalCalendarException
import org.onekash.kashcal.domain.initializer.LocalCalendarRefusal
import org.onekash.kashcal.ui.components.pickers.ColorPalettePanel
import org.onekash.kashcal.ui.components.pickers.InlineColorField
import org.onekash.kashcal.ui.shared.EventColorPalette

/**
 * Returns the message to show under an offline calendar's name field for [result]: null on
 * success, the duplicate message for [LocalCalendarRefusal.DUPLICATE_NAME], the message naming
 * the other calendar for [LocalCalendarRefusal.SIMILAR_NAME], and the general error for any other
 * failure. [typedName] is the trimmed name the user entered.
 */
internal fun offlineCalendarNameError(result: Result<*>, typedName: String, resources: Resources): String? {
    val failure = result.exceptionOrNull() ?: return null
    val refused = failure as? LocalCalendarException
    return when {
        refused?.refusal == LocalCalendarRefusal.DUPLICATE_NAME ->
            resources.getString(R.string.offline_calendar_duplicate_name, typedName)
        refused?.refusal == LocalCalendarRefusal.SIMILAR_NAME && refused.conflictingName != null ->
            resources.getString(R.string.offline_calendar_similar_name, refused.conflictingName)
        else -> resources.getString(R.string.error_unknown)
    }
}

/**
 * Asks for a new offline calendar's name and color and creates it with [onCreate].
 *
 * Opens at full height with the Name field focused once the sheet has settled. Create, or keyboard
 * Done, is off while the trimmed name is blank and while a create runs. A failed create shows its
 * message under the field ([offlineCalendarNameError]) and keeps the sheet open; a success calls
 * [onCreated] with the new id, the trimmed name and the color. The color row opens an
 * [InlineColorField] palette inside the sheet. A swipe down, Back or a tap outside closes the
 * sheet, except while a create runs, which closing would cancel; while the palette is open a swipe
 * does nothing, and Back steps out of the palette ([ColorPalettePanel]) before it reaches the
 * sheet.
 *
 * @param initialColor the color the sheet starts on; chosen once and kept across rotation
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewOfflineCalendarSheet(
    onCreate: suspend (name: String, color: Int) -> Result<Long>,
    onCreated: (id: Long, name: String, color: Int) -> Unit,
    onDismiss: () -> Unit,
    initialColor: Int = rememberSaveable { EventColorPalette.randomArgb() },
) {
    var name by rememberSaveable { mutableStateOf("") }
    var color by rememberSaveable { mutableIntStateOf(initialColor) }
    var error by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var colorExpanded by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    // Back and a tap outside slide the sheet away before asking to dismiss, so a dismiss refused
    // during a create has to be refused here, or the sheet would vanish while still open.
    val creatingNow by rememberUpdatedState(creating)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || !creatingNow },
    )
    val nameFocus = remember { FocusRequester() }
    // A focus request made while the sheet still slides in is lost, so it waits for the sheet to
    // settle, once per opening.
    val settled = sheetState.currentValue == SheetValue.Expanded
    var focusedOnOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(settled) {
        if (settled && !focusedOnOpen) {
            focusedOnOpen = true
            // The field may not be attached yet on the frame the sheet settles.
            runCatching { nameFocus.requestFocus() }
        }
    }

    val canCreate = name.isNotBlank() && !creating

    fun create() {
        if (!canCreate) return
        val trimmed = name.trim()
        // The palette stays open to taps while the create runs; report the color it was made with.
        val chosen = color
        creating = true
        scope.launch {
            val result = onCreate(trimmed, chosen)
            creating = false
            error = offlineCalendarNameError(result, trimmed, resources)
            result.onSuccess { id -> onCreated(id, trimmed, chosen) }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!creating) onDismiss() },
        sheetState = sheetState,
        modifier = Modifier.fillMaxHeight(),
        // A wheel fling in the open palette must not drag the sheet away.
        sheetGesturesEnabled = !creating && !colorExpanded
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
                stringResource(R.string.offline_calendar_new),
                style = MaterialTheme.typography.titleLarge
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    error = null
                },
                label = { Text(stringResource(R.string.label_name)) },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { message -> { Text(message) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { create() }),
                modifier = Modifier.fillMaxWidth().focusRequester(nameFocus)
            )

            InlineColorField(
                selectedArgb = color,
                expanded = colorExpanded,
                onExpandedChange = { colorExpanded = it },
                onColorSelected = { color = it },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(
                    onClick = ::create,
                    enabled = canCreate,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.action_create))
                }
            }
        }
    }
}
