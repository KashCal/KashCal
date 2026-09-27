package org.onekash.kashcal.ui.components.pickers

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import org.onekash.kashcal.ui.shared.EventColorPalette

/**
 * Picks an event color from CSS3 named colors, the values of the RFC 7986 §5.9 COLOR property.
 *
 * Two modes:
 * 1. Grid (default): the calendar-default cell and the 11 hue-distinct palette entries 1..11,
 *    four per row. Entry 0 (saddlebrown) gives its slot to the default cell and stays
 *    reachable through the wheel. A tap commits at once.
 * 2. Wheel ("More colors"): two wheels over all 92 perceptually-distinct CSS3 colors grouped by
 *    hue family. The selection previews live and commits only on Done.
 *
 * @param selectedArgb current event color ARGB, or null when using calendar default
 * @param calendarDefaultArgb calendar color used to render the first (default) cell
 * @param onColorSelected invoked with the chosen ARGB or null (calendar default)
 * @param onDismiss invoked when the sheet is dismissed without a selection
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventColorSheet(
    selectedArgb: Int?,
    calendarDefaultArgb: Int,
    onColorSelected: (Int?) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var showWheel by rememberSaveable { mutableStateOf(false) }
    var wheelPendingArgb by rememberSaveable {
        mutableIntStateOf(EventColorPalette.entryForArgbOrDefault(selectedArgb).argb)
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
            label = "color-sheet-mode"
        ) { wheelMode ->
            if (wheelMode) {
                WheelContent(
                    selected = wheelPending,
                    onSelectionChange = { wheelPendingArgb = it.argb },
                    onBack = { showWheel = false },
                    onDone = {
                        onColorSelected(wheelPendingArgb)
                    }
                )
            } else {
                val defaultCell: @Composable () -> Unit = {
                    SwatchCell(
                        color = Color(calendarDefaultArgb),
                        isSelected = selectedArgb == null,
                        isDefault = true,
                        onClick = { onColorSelected(null) }
                    )
                }
                val paletteCells: List<@Composable () -> Unit> =
                    EventColorPalette.entries.drop(1).map { entry ->
                        {
                            SwatchCell(
                                color = Color(entry.argb),
                                isSelected = selectedArgb == entry.argb,
                                isDefault = false,
                                onClick = { onColorSelected(entry.argb) }
                            )
                        }
                    }
                GridContentImpl(
                    cells = listOf(defaultCell) + paletteCells,
                    rowLabelRes = EventColorPalette.stringResIdForColor(selectedArgb),
                    onMoreColors = {
                        wheelPendingArgb = EventColorPalette.entryForArgbOrDefault(selectedArgb).argb
                        showWheel = true
                    }
                )
            }
        }
    }
}
