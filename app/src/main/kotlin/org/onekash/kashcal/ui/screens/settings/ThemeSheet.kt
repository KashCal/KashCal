package org.onekash.kashcal.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.theme.ThemeMode

/**
 * Describes a theme option: the [ThemeMode] it selects and the strings that describe it. Kept as
 * a plain list ([themeSheetOptions]) so order and labels are unit-testable without a Compose
 * render harness (`ThemeSheetTest`).
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
 * Shows a bottom sheet for choosing the app's light or dark face.
 *
 * System default follows the device setting; Light and Dark force that face. The accent color
 * is chosen separately, in `AccentColorSheet`.
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
