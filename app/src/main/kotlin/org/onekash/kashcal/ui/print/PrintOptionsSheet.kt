package org.onekash.kashcal.ui.print

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CalendarViewWeek
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.viewmodels.PrintSheetUiState

/**
 * Opens the print options sheet at full height, like the Calendar notifications sheet; the
 * layout is [PrintOptionsContent]. Errors show in [snackbarHostState], inside the sheet, so they
 * aren't hidden behind it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintOptionsSheet(
    state: PrintSheetUiState,
    snackbarHostState: SnackbarHostState,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onToggleGrid: () -> Unit,
    onToggleDetails: () -> Unit,
    onToggleCalendar: (PrintCalendarKey) -> Unit,
    onPrint: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.fillMaxHeight(),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            PrintOptionsContent(
                state = state,
                onPreviousWeek = onPreviousWeek,
                onNextWeek = onNextWeek,
                onToggleGrid = onToggleGrid,
                onToggleDetails = onToggleDetails,
                onToggleCalendar = onToggleCalendar,
                onPrint = onPrint,
            )
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp),
            )
        }
    }
}

/**
 * Lays out the sheet: the title, the week with previous and next buttons, a Pages section and
 * the calendar sections in one scrolling list, and a bar pinned below it with the summary and
 * Print, which is disabled while [PrintSheetUiState.canPrint] is false. Each row is one checkbox
 * target.
 */
@Composable
internal fun PrintOptionsContent(
    state: PrintSheetUiState,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onToggleGrid: () -> Unit,
    onToggleDetails: () -> Unit,
    onToggleCalendar: (PrintCalendarKey) -> Unit,
    onPrint: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.print_options_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp).semantics { heading() },
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        ) {
            IconButton(onClick = onPreviousWeek) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = stringResource(R.string.cd_print_previous_week),
                )
            }
            Text(
                text = state.weekRangeLabel,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onNextWeek) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.cd_print_next_week),
                )
            }
        }
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            item(key = "pages_header") { SectionHeader(stringResource(R.string.print_pages_header)) }
            item(key = "page_grid") {
                PrintRow(
                    checked = state.includeGrid,
                    name = stringResource(R.string.print_page_week),
                    detail = stringResource(R.string.print_page_week_detail),
                    leading = { PageIcon(Icons.Outlined.CalendarViewWeek) },
                    onToggle = onToggleGrid,
                )
            }
            item(key = "page_details") {
                PrintRow(
                    checked = state.includeDetails,
                    name = stringResource(R.string.print_page_details),
                    detail = stringResource(R.string.print_page_details_detail),
                    leading = { PageIcon(Icons.Outlined.ViewAgenda) },
                    onToggle = onToggleDetails,
                )
            }
            state.sections.forEach { section ->
                item(key = "header_${section.key}") { SectionHeader(section.header) }
                section.groups.forEachIndexed { index, group ->
                    if (group.subheader != null) {
                        item(key = "sub_${section.key}_$index") {
                            Text(
                                text = group.subheader,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp).semantics { heading() },
                            )
                        }
                    }
                    items(group.rows, key = { it.key.toString() }) { row ->
                        PrintRow(
                            checked = row.key in state.selected,
                            name = row.name,
                            detail = if (row.hidden) stringResource(R.string.calendar_notifications_hidden) else null,
                            leading = {
                                Box(modifier = Modifier.size(14.dp).clip(CircleShape).background(Color(row.color)))
                            },
                            onToggle = { onToggleCalendar(row.key) },
                        )
                    }
                }
            }
        }
        HorizontalDivider()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        ) {
            Text(
                text = stringResource(
                    R.string.print_summary,
                    pluralStringResource(R.plurals.print_summary_pages, state.pageCount, state.pageCount),
                    pluralStringResource(
                        R.plurals.print_summary_calendars, state.listedCount, state.selectedCount, state.listedCount
                    ),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onPrint, enabled = state.canPrint) {
                Text(stringResource(R.string.print_action))
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
private fun PageIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
}

/** One choice: the whole row is the checkbox's target, read by TalkBack as "<name>, checkbox". */
@Composable
private fun PrintRow(
    checked: Boolean,
    name: String,
    detail: String?,
    leading: @Composable () -> Unit,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        leading()
        Column(modifier = Modifier.weight(1f)) {
            Text(text = name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) {
                Text(text = detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // The row handles the tap; a null callback keeps the checkbox from being a second target.
        Checkbox(checked = checked, onCheckedChange = null)
    }
}
