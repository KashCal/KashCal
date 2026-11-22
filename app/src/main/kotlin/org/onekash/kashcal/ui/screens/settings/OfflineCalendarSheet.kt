package org.onekash.kashcal.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.onekash.kashcal.R
import org.onekash.kashcal.domain.model.LocalCalendarSummary
import org.onekash.kashcal.ui.components.offlineCalendarNameError
import org.onekash.kashcal.ui.components.pickers.ColorPalettePanel
import org.onekash.kashcal.ui.components.pickers.ExpandChevron
import org.onekash.kashcal.ui.components.pickers.ExpandingColorPalette
import org.onekash.kashcal.ui.components.pickers.expandStateDescription
import org.onekash.kashcal.ui.model.isBuiltInLocal
import org.onekash.kashcal.ui.model.localizedDisplayName
import org.onekash.kashcal.ui.shared.EventColorPalette

/** Returns the color to apply when [picked] is chosen on a calendar colored [current], or null. */
internal fun recolorTarget(picked: Int, current: Int): Int? = picked.takeIf { it != current }

/**
 * Shows one offline calendar's actions at full height, each applied at once: Rename edits the
 * name in place, Color opens an [ExpandingColorPalette] below its row and applies a different pick,
 * and Export closes the sheet and then shares the calendar (off while it has no events). The
 * built-in calendar has no Rename; its title carries an info button with the reason. The actions
 * scroll when the open palette makes them taller than the screen; while the palette is open a
 * swipe does nothing, and Back steps out of the palette ([ColorPalettePanel]) before it reaches
 * the sheet.
 *
 * Rename's save is off while the trimmed name is blank and while a save runs; a failure shows its
 * message under the field ([offlineCalendarNameError]) and keeps the field open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineCalendarSheet(
    summary: LocalCalendarSummary,
    onRename: suspend (name: String) -> Result<Unit>,
    onRecolor: (color: Int) -> Unit,
    onExport: () -> Unit,
    onDismiss: () -> Unit,
) {
    val calendar = summary.calendar
    val resources = LocalResources.current
    val name = calendar.localizedDisplayName(resources)
    val builtIn = calendar.isBuiltInLocal()
    var colorExpanded by rememberSaveable { mutableStateOf(false) }
    val colorState = expandStateDescription(colorExpanded)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.fillMaxHeight(),
        // A wheel fling in the open palette must not drag the sheet away.
        sheetGesturesEnabled = !colorExpanded,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            if (builtIn) {
                LineWithInfo(
                    detail = stringResource(R.string.offline_calendar_builtin_info),
                    infoDescription = stringResource(R.string.offline_calendar_builtin_info_cd),
                    modifier = Modifier.padding(start = 24.dp, end = 8.dp),
                ) {
                    SheetTitle(name, Modifier.weight(1f, fill = false))
                }
            } else {
                SheetTitle(name, Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            }
            HorizontalDivider(
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
            if (!builtIn) {
                RenameRow(currentName = name, onRename = onRename)
            }
            ActionRow(
                icon = { Icon(Icons.Default.Palette, contentDescription = null) },
                label = stringResource(R.string.offline_calendar_color),
                onClick = { colorExpanded = !colorExpanded },
                modifier = Modifier.semantics { stateDescription = colorState },
            ) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Color(calendar.color))
                )
                Text(
                    stringResource(EventColorPalette.stringResIdForColor(calendar.color)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ExpandChevron(colorExpanded)
            }
            ExpandingColorPalette(
                selectedArgb = calendar.color,
                expanded = colorExpanded,
                onExpandedChange = { colorExpanded = it },
                onColorSelected = { picked -> recolorTarget(picked, calendar.color)?.let(onRecolor) },
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            ActionRow(
                icon = { Icon(Icons.Default.FileUpload, contentDescription = null) },
                label = stringResource(R.string.offline_calendar_export),
                enabled = summary.eventCount > 0,
                onClick = {
                    onDismiss()
                    onExport()
                },
            )
        }
    }
}

@Composable
private fun SheetTitle(name: String, modifier: Modifier = Modifier) {
    Text(
        text = name,
        style = MaterialTheme.typography.titleLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Shows one full-width action: icon, [label], optional trailing content. */
@Composable
private fun ActionRow(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalContentColor provides tint, content = icon)
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            modifier = Modifier.weight(1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            trailing()
        }
    }
}

/**
 * Shows the Rename row: the label and the current name, or, once tapped, the name field with
 * cancel and save in place of the row.
 */
@Composable
private fun RenameRow(currentName: String, onRename: suspend (String) -> Result<Unit>) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf(currentName) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current

    if (!editing) {
        ActionRow(
            icon = { Icon(Icons.Default.Edit, contentDescription = null) },
            label = stringResource(R.string.offline_calendar_rename),
            onClick = {
                draft = currentName
                error = null
                editing = true
            },
        ) {
            Text(
                currentName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp),
            )
        }
        return
    }

    val canSave = draft.isNotBlank() && !saving
    val save = {
        if (canSave) {
            val trimmed = draft.trim()
            saving = true
            scope.launch {
                val result = onRename(trimmed)
                saving = false
                error = offlineCalendarNameError(result, trimmed, resources)
                if (result.isSuccess) editing = false
            }
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = {
                draft = it
                error = null
            },
            label = { Text(stringResource(R.string.label_name)) },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { message -> { Text(message) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { editing = false; error = null }) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.offline_calendar_rename_cancel_cd))
        }
        IconButton(onClick = { save() }, enabled = canSave) {
            Icon(
                Icons.Default.Check,
                contentDescription = stringResource(R.string.offline_calendar_rename_save_cd),
                tint = if (canSave) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
        }
    }
}
