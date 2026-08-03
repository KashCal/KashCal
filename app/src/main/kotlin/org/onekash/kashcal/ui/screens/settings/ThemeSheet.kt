package org.onekash.kashcal.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
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
 * Bottom sheet for selecting a light/dark face.
 *
 * System default follows the device light/dark setting; Light and Dark force that appearance.
 * The accent color is chosen separately (see the accent color picker).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSheet(
    sheetState: SheetState,
    currentMode: ThemeMode,
    onModeSelect: (ThemeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    SelectableOptionSheet(
        sheetState = sheetState,
        titleRes = R.string.settings_theme,
        options = themeSheetOptions().map { option ->
            SelectableOption(
                labelRes = option.labelRes,
                descriptionRes = option.descriptionRes,
                isSelected = currentMode == option.mode,
                onSelect = {
                    onModeSelect(option.mode)
                    onDismiss()
                },
            )
        },
        onDismiss = onDismiss,
    )
}
