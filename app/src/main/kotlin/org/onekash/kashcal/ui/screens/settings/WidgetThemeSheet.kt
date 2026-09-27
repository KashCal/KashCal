package org.onekash.kashcal.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import org.onekash.kashcal.R
import org.onekash.kashcal.widget.WidgetThemeSource

/**
 * Describes a widget-theme option: the [WidgetThemeSource] it selects and the strings that
 * describe it. Kept as a plain list ([widgetThemeSheetOptions]) so order and labels are
 * unit-testable without a Compose render harness (`WidgetThemeSheetTest`).
 */
data class WidgetThemeSheetOption(
    val source: WidgetThemeSource,
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
)

/**
 * The widget-theme options in menu order, derived from [WidgetThemeSource.entries]. Each option's
 * label and description come from the source itself, so a new source appears here automatically.
 */
fun widgetThemeSheetOptions(): List<WidgetThemeSheetOption> =
    WidgetThemeSource.entries.map { WidgetThemeSheetOption(it, it.labelRes, it.descriptionRes) }

/**
 * Shows a bottom sheet for choosing the widgets' light or dark face.
 *
 * Unlike [ThemeSheet], this offers "Follow app" instead of "System": the widget tracks the app's
 * face, so when the app follows the device, so does the widget. Light and Dark pin the widget
 * regardless of the app or device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetThemeSheet(
    sheetState: SheetState,
    currentSource: WidgetThemeSource,
    onSourceSelect: (WidgetThemeSource) -> Unit,
    onDismiss: () -> Unit,
) {
    SelectableOptionSheet(
        sheetState = sheetState,
        titleRes = R.string.settings_widget_theme,
        options = widgetThemeSheetOptions().map { option ->
            SelectableOption(
                labelRes = option.labelRes,
                descriptionRes = option.descriptionRes,
                isSelected = currentSource == option.source,
                onSelect = {
                    onSourceSelect(option.source)
                    onDismiss()
                },
            )
        },
        onDismiss = onDismiss,
    )
}
