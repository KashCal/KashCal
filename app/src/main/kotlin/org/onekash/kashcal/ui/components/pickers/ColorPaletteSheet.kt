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
 * Palette-only color sheet for surfaces where the selection IS the color
 * (no calendar-default fallback): birthday/anniversary calendar colors and
 * ICS subscription colors.
 *
 * Visually consistent with [EventColorSheet] — same grid + wheel — but
 * without the leading "Calendar default" cell. Renders all 12 palette
 * entries in a 4×3 grid. A "More colors" link opens the same 92-color
 * CSS3 wheel picker; wheel selection requires tapping Done.
 *
 * @param selectedArgb current color ARGB
 * @param onColorSelected invoked with the chosen ARGB
 * @param onDismiss invoked when the sheet is dismissed without a selection
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColorPaletteSheet(
    selectedArgb: Int,
    onColorSelected: (Int) -> Unit,
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
            label = "palette-sheet-mode"
        ) { wheelMode ->
            if (wheelMode) {
                WheelContent(
                    selected = wheelPending,
                    onSelectionChange = { wheelPendingArgb = it.argb },
                    onBack = { showWheel = false },
                    onDone = { onColorSelected(wheelPendingArgb) }
                )
            } else {
                val cells: List<@Composable () -> Unit> =
                    EventColorPalette.entries.map { entry ->
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
                    cells = cells,
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
