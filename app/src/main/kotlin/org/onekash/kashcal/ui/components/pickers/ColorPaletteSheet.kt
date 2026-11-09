package org.onekash.kashcal.ui.components.pickers

import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable

/**
 * Picks a color in a sheet where the selection is the color, with no calendar-default fallback:
 * a tag's color. [ColorPalettePanel] shows the same grid and wheel inside another surface.
 *
 * Same grid and wheel as [EventColorPalettePanel] without the leading "Calendar default" cell, so
 * all 12 palette entries fill a 4×3 grid. "More colors" opens the 92-color CSS3 wheel, whose
 * selection commits only on Done. Back closes the sheet from either. A drag down closes it from
 * the grid; on the wheel a fling picks a color, so there the sheet holds still and shows no drag
 * handle (`ColorSheetSwipeTest`).
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
    val modeState = rememberPaletteModeState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = if (modeState.showWheel) null else { { BottomSheetDefaults.DragHandle() } },
        sheetGesturesEnabled = !modeState.showWheel
    ) {
        PaletteModes(
            state = modeState,
            selectedArgb = selectedArgb,
            cells = paletteCells(selectedArgb, onColorSelected),
            onWheelDone = onColorSelected,
        )
    }
}
