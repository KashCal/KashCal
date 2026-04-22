package org.onekash.kashcal.ui.components.pickers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.shared.EventColorPalette
import org.onekash.kashcal.ui.shared.contrastForegroundOn

/**
 * RFC 7986 §5.9 compliant event color picker.
 *
 * Renders a 3x4 grid: 1 calendar-default cell + 11 CSS3 named colors.
 * Tapping a swatch immediately calls onColorSelected and dismisses the sheet —
 * no confirmation button. Selecting the first cell clears the override (null).
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                stringResource(R.string.label_event_color),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            // 3 rows × 4 columns = 12 cells: default + 11 palette entries
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
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(vertical = 8.dp))
            }

            // Selected swatch name
            Text(
                stringResource(EventColorPalette.stringResIdForColor(selectedArgb)),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp)
            )
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
