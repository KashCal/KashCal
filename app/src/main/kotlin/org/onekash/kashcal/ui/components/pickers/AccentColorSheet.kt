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

/**
 * Picks the app theme's accent color, with the grid and 92-color wheel of [EventColorSheet].
 * Every selection is a concrete color: where the event sheet's first cell means "calendar
 * default" (null), this one commits the brand-teal default seed.
 *
 * It offers the same [EventColorPalette] as events. Any of its colors is a valid accent,
 * because the generated scheme keeps WCAG AA for any seed.
 *
 * @param selectedArgb the current accent seed ARGB; the matching swatch shows selected.
 * @param useDynamic whether the app is currently on the automatic (Material You) source, so the
 *   "Automatic" option shows selected and no swatch is highlighted.
 * @param onColorSelected invoked with the chosen accent ARGB (never null).
 * @param onUseDynamic invoked when the user picks the automatic (wallpaper) source.
 * @param onDismiss invoked when the sheet is dismissed without a selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccentColorSheet(
    selectedArgb: Int,
    useDynamic: Boolean,
    onColorSelected: (Int) -> Unit,
    onUseDynamic: () -> Unit,
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
            label = "accent-sheet-mode"
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
                    // Returns to Material You wallpaper colors.
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_accent_color_dynamic)) },
                        supportingContent = { Text(stringResource(R.string.settings_accent_color_dynamic_desc)) },
                        trailingContent = {
                            if (useDynamic) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = stringResource(R.string.cd_checkmark),
                                )
                            }
                        },
                        modifier = Modifier.clickable { onUseDynamic() },
                    )

                    val defaultCell: @Composable () -> Unit = {
                        SwatchCell(
                            color = Color(defaultSeed),
                            isSelected = !useDynamic && selectedArgb == defaultSeed,
                            isDefault = true,
                            onClick = { onColorSelected(defaultSeed) }
                        )
                    }
                    val paletteCells: List<@Composable () -> Unit> =
                        EventColorPalette.entries.drop(1).map { entry ->
                            {
                                SwatchCell(
                                    color = Color(entry.argb),
                                    isSelected = !useDynamic && selectedArgb == entry.argb,
                                    isDefault = false,
                                    onClick = { onColorSelected(entry.argb) }
                                )
                            }
                        }
                    // In Automatic mode no swatch is in effect, so the label names the source.
                    // Brand teal isn't a CSS3 palette entry and would read as "Custom".
                    val labelRes = when {
                        useDynamic -> R.string.settings_accent_color_dynamic
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
