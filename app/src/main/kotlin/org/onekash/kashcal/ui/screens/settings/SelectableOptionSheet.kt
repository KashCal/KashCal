package org.onekash.kashcal.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * One selectable row in a [SelectableOptionSheet]: a label + description and whether it is the
 * current choice. [onSelect] runs when the row is tapped (typically applies the value and dismisses
 * the sheet).
 */
data class SelectableOption(
    @param:StringRes val labelRes: Int,
    @param:StringRes val descriptionRes: Int,
    val isSelected: Boolean,
    val onSelect: () -> Unit,
)

/**
 * A titled bottom sheet of mutually-exclusive options rendered as radio-selectable rows. Used by
 * the app-theme and widget-theme pickers; both supply their own ordered option list and title,
 * so any accessibility or styling change to the rows lives in one place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectableOptionSheet(
    sheetState: SheetState,
    @StringRes titleRes: Int,
    options: List<SelectableOption>,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
                .selectableGroup(),
        ) {
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )

            options.forEach { option -> SelectableOptionRow(option) }
        }
    }
}

@Composable
private fun SelectableOptionRow(option: SelectableOption) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Radio-button role + selected state so TalkBack announces the choice and its
            // group position, not just the label (the checkmark alone is a sighted-only cue).
            .selectable(selected = option.isSelected, role = Role.RadioButton, onClick = option.onSelect)
            .background(
                if (option.isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else Color.Transparent,
            )
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(option.labelRes),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(option.descriptionRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (option.isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                // Decorative: the row's radio-button selected state already announces selection.
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
