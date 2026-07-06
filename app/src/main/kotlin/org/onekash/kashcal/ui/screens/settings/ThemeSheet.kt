package org.onekash.kashcal.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.theme.ThemeMode

/**
 * A theme option: which [ThemeMode] it selects and the string resources that describe it.
 * Kept as a pure list ([themeSheetOptions]) so ordering and label mapping are unit-testable
 * without a Compose render harness.
 */
data class ThemeSheetOption(
    val mode: ThemeMode,
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
)

/**
 * The theme options in menu order, derived from [ThemeMode.entries]. Each option's label and
 * description come from the mode itself, so a new theme appears here automatically.
 */
fun themeSheetOptions(): List<ThemeSheetOption> =
    ThemeMode.entries.map { ThemeSheetOption(it, it.labelRes, it.descriptionRes) }

/**
 * Bottom sheet for selecting the app theme.
 *
 * System default follows the device light/dark setting; Light and Dark force that appearance;
 * KashCal Teal applies the teal brand palette (following the device light/dark setting).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSheet(
    sheetState: SheetState,
    currentMode: ThemeMode,
    onModeSelect: (ThemeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_theme),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )

            themeSheetOptions().forEach { option ->
                ThemeOptionRow(
                    option = option,
                    isSelected = currentMode == option.mode,
                    onSelect = {
                        onModeSelect(option.mode)
                        onDismiss()
                    },
                )
            }
        }
    }
}

@Composable
private fun ThemeOptionRow(
    option: ThemeSheetOption,
    isSelected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            // Expose selection to TalkBack so the state isn't conveyed by the checkmark alone.
            .semantics { selected = isSelected }
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
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
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = stringResource(R.string.cd_selected),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
