package org.onekash.kashcal.ui.components.pickers

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.shared.EventColorPalette
import org.onekash.kashcal.ui.shared.PaletteEntry

/** Which half of a palette shows, and the wheel's color before Done commits it. */
@Stable
internal class PaletteModeState(showWheel: Boolean, wheelPendingArgb: Int) {
    var showWheel by mutableStateOf(showWheel)
    var wheelPendingArgb by mutableIntStateOf(wheelPendingArgb)

    /** Opens the wheel on the entry nearest [selectedArgb]. */
    fun openWheel(selectedArgb: Int?) {
        wheelPendingArgb = EventColorPalette.entryForArgbOrDefault(selectedArgb).argb
        showWheel = true
    }

    companion object {
        val Saver = listSaver<PaletteModeState, Int>(
            save = { listOf(if (it.showWheel) 1 else 0, it.wheelPendingArgb) },
            restore = { PaletteModeState(showWheel = it[0] == 1, wheelPendingArgb = it[1]) },
        )
    }
}

/**
 * Remembers a [PaletteModeState] that starts on the grid and survives recreation. Its wheel color
 * is set by [PaletteModeState.openWheel] before the wheel shows.
 */
@Composable
internal fun rememberPaletteModeState(): PaletteModeState =
    rememberSaveable(saver = PaletteModeState.Saver) {
        PaletteModeState(
            showWheel = false,
            wheelPendingArgb = EventColorPalette.entryForArgbOrDefault(null).argb,
        )
    }

/** Returns a swatch per entry; a tap reports the entry's color. */
private fun swatches(
    entries: List<PaletteEntry>,
    selectedArgb: Int?,
    onPick: (Int) -> Unit,
): List<@Composable () -> Unit> = entries.map { entry ->
    {
        SwatchCell(
            color = Color(entry.argb),
            isSelected = selectedArgb == entry.argb,
            isDefault = false,
            onClick = { onPick(entry.argb) },
        )
    }
}

/** Returns the 12 palette swatches; a tap reports the swatch's color. */
internal fun paletteCells(selectedArgb: Int, onPick: (Int) -> Unit): List<@Composable () -> Unit> =
    swatches(EventColorPalette.entries, selectedArgb, onPick)

/**
 * Returns the event grid: the calendar-default cell (reports null) and palette entries 1..11.
 * Entry 0 (saddlebrown) gives its slot to the default cell and stays reachable through the wheel.
 */
internal fun eventPaletteCells(
    selectedArgb: Int?,
    calendarDefaultArgb: Int,
    onPick: (Int?) -> Unit,
): List<@Composable () -> Unit> {
    val defaultCell: @Composable () -> Unit = {
        SwatchCell(
            color = Color(calendarDefaultArgb),
            isSelected = selectedArgb == null,
            isDefault = true,
            onClick = { onPick(null) },
        )
    }
    return listOf(defaultCell) + swatches(EventColorPalette.entries.drop(1), selectedArgb, onPick)
}

/**
 * Shows the grid of [cells] or the 92-color wheel, as [state] says. "More colors" opens the wheel
 * on the entry nearest [selectedArgb]; the wheel's Back returns to the grid and Done calls
 * [onWheelDone] with the wheel color. [ColorPaletteSheet] and the inline panels draw through this.
 */
@Composable
internal fun PaletteModes(
    state: PaletteModeState,
    selectedArgb: Int?,
    cells: List<@Composable () -> Unit>,
    onWheelDone: (Int) -> Unit,
    gridPadding: PaddingValues = SHEET_GRID_PADDING,
) {
    Crossfade(
        targetState = state.showWheel,
        animationSpec = tween(200),
        label = "palette-mode",
    ) { wheelMode ->
        if (wheelMode) {
            WheelContent(
                selected = EventColorPalette.entryForArgbOrDefault(state.wheelPendingArgb),
                onSelectionChange = { state.wheelPendingArgb = it.argb },
                onBack = { state.showWheel = false },
                onDone = { onWheelDone(state.wheelPendingArgb) },
            )
        } else {
            GridContentImpl(
                cells = cells,
                rowLabelRes = EventColorPalette.stringResIdForColor(selectedArgb),
                onMoreColors = { state.openWheel(selectedArgb) },
                contentPadding = gridPadding,
            )
        }
    }
}

/** Grid padding for a panel inside a host, which pads its sides already. */
private val PANEL_GRID_PADDING = PaddingValues(start = 8.dp, top = 12.dp, end = 8.dp, bottom = 8.dp)

/**
 * Shows a palette inside another surface. On opening it takes focus from any text field, so the
 * keyboard doesn't cover it, and once [PICKER_EXPAND_MILLIS] have passed scrolls itself into view,
 * again when the taller wheel opens; a panel restored after recreation leaves the host's scroll
 * where it was. While [expanded], Back closes the wheel first and then calls [onCollapse]; once
 * [expanded] is false, during the closing animation and after, Back goes to the host. Closing also
 * drops a wheel color Done never committed.
 */
@Composable
private fun PalettePanelFrame(
    selectedArgb: Int?,
    cells: List<@Composable () -> Unit>,
    onWheelDone: (Int) -> Unit,
    expanded: Boolean,
    onCollapse: () -> Unit,
    modifier: Modifier,
) {
    val state = rememberPaletteModeState()
    val focusManager = LocalFocusManager.current
    val requester = remember { BringIntoViewRequester() }
    // The mode last brought into view; saved, so a restored panel doesn't scroll again.
    var shownMode by rememberSaveable { mutableStateOf<Boolean?>(null) }
    BackHandler(enabled = expanded) {
        if (state.showWheel) state.showWheel = false else onCollapse()
    }
    LaunchedEffect(expanded) {
        if (expanded) {
            focusManager.clearFocus()
        } else {
            state.showWheel = false
            shownMode = null
        }
    }
    LaunchedEffect(expanded, state.showWheel) {
        if (expanded && shownMode != state.showWheel) {
            shownMode = state.showWheel
            delay(PICKER_EXPAND_MILLIS.toLong())
            requester.bringIntoView()
        }
    }
    Box(modifier = modifier.fillMaxWidth().bringIntoViewRequester(requester)) {
        PaletteModes(
            state = state,
            selectedArgb = selectedArgb,
            cells = cells,
            onWheelDone = onWheelDone,
            gridPadding = PANEL_GRID_PADDING,
        )
    }
}

/**
 * Shows the 12-color palette and the "More colors" wheel inside another surface, for a color
 * that has no calendar-default fallback. A swatch or the wheel's Done calls [onColorSelected];
 * the host decides whether that also closes the panel. Focus, scrolling and Back follow
 * [PalettePanelFrame].
 *
 * @param expanded whether the host shows the panel; Back is the panel's only while true
 * @param onCollapse called when Back from the grid asks the host to close the panel
 */
@Composable
fun ColorPalettePanel(
    selectedArgb: Int,
    expanded: Boolean,
    onColorSelected: (Int) -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) = PalettePanelFrame(
    selectedArgb = selectedArgb,
    cells = paletteCells(selectedArgb, onColorSelected),
    onWheelDone = onColorSelected,
    expanded = expanded,
    onCollapse = onCollapse,
    modifier = modifier,
)

/**
 * Shows the event color palette inside another surface: the [eventPaletteCells] grid, led by
 * the calendar-default cell, and the wheel. The default cell reports null. Otherwise as
 * [ColorPalettePanel].
 *
 * @param selectedArgb the event color, or null for the calendar default
 * @param calendarDefaultArgb the calendar color the default cell shows
 */
@Composable
fun EventColorPalettePanel(
    selectedArgb: Int?,
    calendarDefaultArgb: Int,
    expanded: Boolean,
    onColorSelected: (Int?) -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) = PalettePanelFrame(
    selectedArgb = selectedArgb,
    cells = eventPaletteCells(selectedArgb, calendarDefaultArgb, onColorSelected),
    onWheelDone = onColorSelected,
    expanded = expanded,
    onCollapse = onCollapse,
    modifier = modifier,
)

/**
 * Opens a [ColorPalettePanel] on a tonal card below a color row while [expanded]. A pick reports
 * the color and asks to close; Back from the grid asks to close.
 */
@Composable
fun ExpandingColorPalette(
    selectedArgb: Int,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onColorSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = expanded,
        modifier = modifier,
        enter = PickerExpandEnter,
        exit = PickerCollapseExit,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ColorPalettePanel(
                selectedArgb = selectedArgb,
                expanded = expanded,
                onColorSelected = {
                    onColorSelected(it)
                    onExpandedChange(false)
                },
                onCollapse = { onExpandedChange(false) },
            )
        }
    }
}

/** Returns what TalkBack says about a row that opens and closes something below it. */
@Composable
internal fun expandStateDescription(expanded: Boolean): String =
    stringResource(if (expanded) R.string.cd_state_expanded else R.string.cd_state_collapsed)

/** Shows the up or down chevron of a row that opens and closes something below it. */
@Composable
internal fun ExpandChevron(
    expanded: Boolean,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Icon(
        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
        contentDescription = null,
        modifier = Modifier.size(20.dp),
        tint = tint,
    )
}

/**
 * Shows a "Color:" row with the current swatch and its name, which opens and closes an
 * [ExpandingColorPalette] below it. The row tells TalkBack whether the palette is open.
 */
@Composable
fun InlineColorField(
    selectedArgb: Int,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onColorSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = expandStateDescription(expanded)
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button) { onExpandedChange(!expanded) }
                .semantics { stateDescription = state }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.label_color), style = MaterialTheme.typography.bodySmall)
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(selectedArgb))
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            )
            Text(
                stringResource(EventColorPalette.stringResIdForColor(selectedArgb)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            ExpandChevron(expanded)
        }
        ExpandingColorPalette(
            selectedArgb = selectedArgb,
            expanded = expanded,
            onExpandedChange = onExpandedChange,
            onColorSelected = onColorSelected,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
