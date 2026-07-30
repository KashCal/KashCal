package org.onekash.kashcal.ui.components.pickers

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import org.onekash.kashcal.R
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.ui.shared.EventColorPalette
import org.onekash.kashcal.widget.WidgetColorSource

/**
 * Accent color picker for the WIDGETS, independent of the app accent. Same grid + 92-color wheel
 * layout as [AccentColorSheet], plus a third source: following the app's colors (the default, so
 * the widgets stay in sync until the user opts out).
 *
 * @param source the current widget color source; decides which row/swatch shows selected.
 * @param selectedArgb the current widget accent seed ARGB; highlighted when [source] is SEED.
 * @param onFollowApp invoked when the widgets should mirror the app's colors again.
 * @param onUseDynamic invoked when the widgets should use the device's Material You palette.
 * @param onColorSelected invoked with the chosen widget-only accent ARGB (never null).
 * @param onDismiss invoked when the sheet is dismissed without a selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetAccentColorSheet(
    source: WidgetColorSource,
    selectedArgb: Int,
    onFollowApp: () -> Unit,
    onUseDynamic: () -> Unit,
    onColorSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val defaultSeed = KashCalDataStore.ACCENT_SEED_DEFAULT

    var showWheel by rememberSaveable { mutableStateOf(false) }
    var wheelPendingArgb by rememberSaveable {
        mutableIntStateOf(EventColorPalette.nearestWheelEntry(selectedArgb).argb)
    }
    val wheelPending = EventColorPalette.entryForArgbOrDefault(wheelPendingArgb)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = {},
        sheetGesturesEnabled = false
    ) {
        Crossfade(
            targetState = showWheel,
            animationSpec = tween(200),
            label = "widget-accent-sheet-mode"
        ) { wheelMode ->
            if (wheelMode) {
                WheelContent(
                    selected = wheelPending,
                    onSelectionChange = { wheelPendingArgb = it.argb },
                    onBack = { showWheel = false },
                    onDone = { onColorSelected(wheelPendingArgb) }
                )
            } else {
                Column {
                    // Mirror the app's accent + color source (the default).
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_widget_color_follow_app)) },
                        supportingContent = { Text(stringResource(R.string.settings_widget_color_follow_app_desc)) },
                        trailingContent = {
                            if (source == WidgetColorSource.FOLLOW_APP) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = stringResource(R.string.cd_checkmark),
                                )
                            }
                        },
                        modifier = Modifier.clickable { onFollowApp() },
                    )

                    // Return to Material You / wallpaper colors.
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_accent_color_dynamic)) },
                        supportingContent = { Text(stringResource(R.string.settings_accent_color_dynamic_desc)) },
                        trailingContent = {
                            if (source == WidgetColorSource.DYNAMIC) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = stringResource(R.string.cd_checkmark),
                                )
                            }
                        },
                        modifier = Modifier.clickable { onUseDynamic() },
                    )

                    // First cell commits the brand-teal default seed (not null): the accent always
                    // resolves to a concrete color when a swatch is chosen.
                    val defaultCell: @Composable () -> Unit = {
                        SwatchCell(
                            color = Color(defaultSeed),
                            isSelected = source == WidgetColorSource.SEED && selectedArgb == defaultSeed,
                            isDefault = true,
                            onClick = { onColorSelected(defaultSeed) }
                        )
                    }
                    val paletteCells: List<@Composable () -> Unit> =
                        EventColorPalette.entries.drop(1).map { entry ->
                            {
                                SwatchCell(
                                    color = Color(entry.argb),
                                    isSelected = source == WidgetColorSource.SEED && selectedArgb == entry.argb,
                                    isDefault = false,
                                    onClick = { onColorSelected(entry.argb) }
                                )
                            }
                        }
                    // Label the current selection. Outside SEED no swatch is active, so name the
                    // source rather than a color that isn't in effect. Brand teal isn't a CSS3
                    // palette entry (would read as "Custom"), so label it explicitly.
                    val labelRes = when {
                        source == WidgetColorSource.FOLLOW_APP -> R.string.settings_widget_color_follow_app
                        source == WidgetColorSource.DYNAMIC -> R.string.settings_accent_color_dynamic
                        selectedArgb == defaultSeed -> R.string.settings_accent_color_brand
                        else -> EventColorPalette.stringResIdForColor(selectedArgb)
                    }
                    GridContentImpl(
                        cells = listOf(defaultCell) + paletteCells,
                        rowLabelRes = labelRes,
                        onMoreColors = {
                            wheelPendingArgb = EventColorPalette.nearestWheelEntry(selectedArgb).argb
                            showWheel = true
                        }
                    )
                }
            }
        }
    }
}
