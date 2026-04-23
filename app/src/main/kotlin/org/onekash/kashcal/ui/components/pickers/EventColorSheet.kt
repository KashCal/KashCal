package org.onekash.kashcal.ui.components.pickers

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.shared.Css3ColorEntry
import org.onekash.kashcal.ui.shared.EventColorPalette
import org.onekash.kashcal.ui.shared.contrastForegroundOn

/**
 * RFC 7986 §5.9 compliant event color picker.
 *
 * Two modes:
 * 1. **Grid** (default): 3×4 grid — 1 calendar-default cell + 11 hue-distinct
 *    CSS3 colors. Tapping a swatch commits immediately and dismisses the sheet.
 * 2. **Wheel** (via "More colors" link): two-wheel browser of all 92
 *    perceptually-distinct CSS3 colors grouped by hue family. Wheel selection
 *    is previewed live but only committed when the user taps Done.
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

    // Saveable across rotation / process death. wheelPendingArgb stores the
    // tentative wheel selection as Int; Css3ColorEntry is re-derived each
    // composition via entryForArgbOrDefault.
    var showWheel by rememberSaveable { mutableStateOf(false) }
    var wheelPendingArgb by rememberSaveable {
        mutableIntStateOf(EventColorPalette.entryForArgbOrDefault(selectedArgb).argb)
    }
    val wheelPending = EventColorPalette.entryForArgbOrDefault(wheelPendingArgb)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
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
                GridContent(
                    selectedArgb = selectedArgb,
                    calendarDefaultArgb = calendarDefaultArgb,
                    onColorSelected = onColorSelected,
                    onMoreColors = {
                        // Reseed so tapping "More colors" starts from the
                        // current selection if it's in the wheel set.
                        wheelPendingArgb = EventColorPalette.entryForArgbOrDefault(selectedArgb).argb
                        showWheel = true
                    }
                )
            }
        }
    }
}

@Composable
private fun GridContent(
    selectedArgb: Int?,
    calendarDefaultArgb: Int,
    onColorSelected: (Int?) -> Unit,
    onMoreColors: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val defaultCell: @Composable () -> Unit = {
            SwatchCell(
                color = Color(calendarDefaultArgb),
                isSelected = selectedArgb == null,
                isDefault = true,
                onClick = { onColorSelected(null) }
            )
        }
        val paletteCells: List<@Composable () -> Unit> = EventColorPalette.entries.map { entry ->
            {
                SwatchCell(
                    color = Color(entry.argb),
                    isSelected = selectedArgb == entry.argb,
                    isDefault = false,
                    onClick = { onColorSelected(entry.argb) }
                )
            }
        }
        val cells: List<@Composable () -> Unit> = listOf(defaultCell) + paletteCells

        cells.chunked(4).forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                row.forEach { cell -> cell() }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(EventColorPalette.stringResIdForColor(selectedArgb)),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onMoreColors() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    stringResource(R.string.label_more_colors),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun WheelContent(
    selected: Css3ColorEntry,
    onSelectionChange: (Css3ColorEntry) -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ColorWheelPicker(
            selected = selected,
            onColorSelected = onSelectionChange
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.size(4.dp))
                Text(stringResource(R.string.label_back_to_grid))
            }
            TextButton(onClick = onDone) {
                Text(
                    stringResource(R.string.action_done),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun SwatchCell(
    color: Color,
    isSelected: Boolean,
    isDefault: Boolean,
    onClick: () -> Unit
) {
    val checkColor = remember(color) { contrastForegroundOn(color) }
    val outlineColor = MaterialTheme.colorScheme.outline
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(color)
            .then(
                if (isDefault)
                    Modifier.border(
                        width = 1.dp,
                        color = outlineColor,
                        shape = CircleShape
                    )
                else Modifier
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            Icon(
                Icons.Default.Check,
                contentDescription = stringResource(R.string.cd_checkmark),
                tint = checkColor,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}
