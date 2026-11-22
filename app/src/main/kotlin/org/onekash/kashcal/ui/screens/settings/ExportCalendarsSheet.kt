package org.onekash.kashcal.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.model.CalendarGroup
import org.onekash.kashcal.ui.viewmodels.CalendarExportOrigin
import org.onekash.kashcal.ui.viewmodels.CalendarExportState
import org.onekash.kashcal.ui.viewmodels.CalendarExportViewModel

/**
 * Opens the Export calendars sheet over [viewModel]: its counts and logins are read only while the
 * sheet shows, and closing it forgets a failure shown under a row.
 */
@Composable
fun ExportCalendarsRoute(
    viewModel: CalendarExportViewModel,
    calendarGroups: List<CalendarGroup>,
    onDismiss: () -> Unit,
) {
    val counts by viewModel.eventCounts.collectAsStateWithLifecycle()
    val logins by viewModel.accountLogins.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val contactsHeader = stringResource(R.string.drawer_contacts)
    val sections = remember(calendarGroups, counts, logins, contactsHeader) {
        buildExportCalendarSections(calendarGroups, counts, logins, contactsHeader)
    }
    ExportCalendarsSheet(
        sections = sections,
        state = state,
        onExport = { viewModel.export(it, CalendarExportOrigin.EXPORT_SHEET) },
        onDismiss = {
            viewModel.clearFailure()
            onDismiss()
        },
    )
}

/**
 * Shows every Room calendar at full height, one row each; a tap on a row calls [onExport] and the
 * sheet stays open, so several calendars can be shared in a row.
 *
 * A calendar known to have no events is dimmed and doesn't respond. While [state] has a busy
 * calendar no row responds; once [CalendarExportState.showPreparing] is on, the busy row says
 * "Preparing…" and the others are dimmed. A file failure shows under its row with Try Again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportCalendarsSheet(
    sections: List<ExportCalendarSection>,
    state: CalendarExportState,
    onExport: (calendarId: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.fillMaxHeight(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = stringResource(R.string.settings_export_calendars),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 4.dp)
                    .semantics { heading() },
            )
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                sections.forEach { section ->
                    item(key = "header_${section.key}") {
                        Column(modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp)) {
                            Text(
                                text = section.header,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.semantics { heading() },
                            )
                            section.subheader?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    items(section.rows, key = { "row_${it.id}" }) { row ->
                        Column {
                            ExportCalendarRowItem(row = row, state = state, onExport = onExport)
                            if (state.failedCalendarId == row.id) {
                                ExportFailedLine(onRetry = { onExport(row.id) })
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One calendar: color dot, name, count or "Preparing…", and a share glyph or a spinner. */
@Composable
private fun ExportCalendarRowItem(
    row: ExportCalendarRow,
    state: CalendarExportState,
    onExport: (calendarId: Long) -> Unit,
) {
    val empty = row.eventCount == 0
    val busy = state.busyCalendarId == row.id
    val preparing = busy && state.showPreparing
    val waiting = state.showPreparing && !busy
    val label = stringResource(R.string.cd_export_calendar, row.name)
    val preparingLabel = stringResource(R.string.cd_export_calendar_preparing, row.name)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(
                enabled = !empty && state.busyCalendarId == null,
                role = Role.Button,
                onClickLabel = label,
                onClick = { onExport(row.id) },
            )
            .alpha(if (empty || waiting) DIMMED_ALPHA else 1f)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(Color(row.color))
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Always one line tall, so the row keeps its height while the count loads.
            Text(
                text = when {
                    preparing -> stringResource(R.string.export_calendar_preparing)
                    row.eventCount == null -> ""
                    empty -> stringResource(R.string.export_calendar_no_events)
                    else -> pluralStringResource(R.plurals.event_count, row.eventCount, row.eventCount)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (preparing) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier
                        .size(18.dp)
                        .semantics {
                            liveRegion = LiveRegionMode.Polite
                            contentDescription = preparingLabel
                        },
                )
            } else {
                Icon(
                    Icons.Default.Share,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The failure under a row, announced once, with Try Again in place of a dialog. */
@Composable
private fun ExportFailedLine(onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 52.dp, end = 16.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.export_file_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry) { Text(stringResource(R.string.action_try_again)) }
    }
}

private const val DIMMED_ALPHA = 0.38f
