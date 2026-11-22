package org.onekash.kashcal.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.onekash.kashcal.R
import org.onekash.kashcal.domain.model.LocalCalendarSummary
import org.onekash.kashcal.ui.components.NewOfflineCalendarSheet
import org.onekash.kashcal.ui.components.RevealedRow
import org.onekash.kashcal.ui.components.SettingsTopAppBar
import org.onekash.kashcal.ui.components.SwipeRevealRow
import org.onekash.kashcal.ui.model.isBuiltInLocal
import org.onekash.kashcal.ui.model.localizedDisplayName

/**
 * Lists the offline calendars, KashCal's own calendars that stay on the phone: a short line with
 * an info button for the note on syncing and backups (closed when the screen opens), each
 * calendar with its color, name and event count, the default calendar's row tinted, and a New
 * calendar button that opens [NewOfflineCalendarSheet].
 *
 * Names and their order come from the current resources at each composition, so the built-in
 * calendar's translated name and its place follow a language change at once.
 *
 * Tapping a calendar opens its [OfflineCalendarSheet]. The sheet is kept by calendar id and reads
 * the calendar from [calendars], so it follows a rename and closes when the calendar is gone. An
 * empty [calendars] means not loaded yet (the built-in calendar is always there), so a sheet
 * restored after process death waits for the list instead of closing.
 *
 * Swiping a calendar the user added reveals Delete ([SwipeRevealRow]; TalkBack gets it as an
 * action), which turns the row, in place, into a confirm: with events, its Delete stays off until
 * "Also delete N events" is ticked. A scroll closes an open swipe but not a confirm, which can be
 * taller than the screen shows. While a delete runs its confirm stays, Delete off, whatever else
 * closes rows; several can run at once. A success removes the row and TalkBack hears "<name>
 * deleted" through a live region outside the list, cleared again a few seconds later; a failure
 * brings that row back. No snackbar either way. The built-in calendar can't be deleted, so its
 * row doesn't swipe.
 *
 * @param defaultCalendarId the Room calendar new events go to by default, or null
 * @param onCreateCalendar creates a calendar from the sheet's name and color
 * @param onRecolorCalendar applies a new color; a failure leaves the old color, with no message
 * @param onExportCalendar shares the calendar as an .ics file
 * @param onDeleteCalendar deletes the calendar and its events
 */
@Composable
fun OfflineCalendarsScreen(
    calendars: List<LocalCalendarSummary>,
    defaultCalendarId: Long?,
    onNavigateBack: () -> Unit,
    onCreateCalendar: suspend (name: String, color: Int) -> Result<Long>,
    onRenameCalendar: suspend (calendarId: Long, name: String) -> Result<Unit>,
    onRecolorCalendar: suspend (calendarId: Long, color: Int) -> Result<Unit>,
    onExportCalendar: (calendarId: Long) -> Unit,
    onDeleteCalendar: suspend (calendarId: Long) -> Result<Int>,
) {
    val resources = LocalResources.current
    val rows = remember(calendars, resources) {
        calendars
            .map { it to it.calendar.localizedDisplayName(resources) }
            .sortedBy { (_, name) -> name.lowercase() }
    }
    var showCreateSheet by rememberSaveable { mutableStateOf(false) }
    var openCalendarId by rememberSaveable { mutableStateOf<Long?>(null) }
    val openCalendar = calendars.firstOrNull { it.calendar.id == openCalendarId }
    val scope = rememberCoroutineScope()
    LaunchedEffect(openCalendarId, openCalendar, calendars.isEmpty()) {
        if (openCalendarId != null && openCalendar == null && calendars.isNotEmpty()) openCalendarId = null
    }
    val revealed = remember { RevealedRow() }
    val listState = rememberLazyListState()
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { if (it) revealed.closeOpen() }
    }
    // The calendars whose delete is running; each row shows its confirm until its result.
    var deletingIds by remember { mutableStateOf(emptySet<Long>()) }
    // What the live region says after a delete. Cleared when the next delete starts and a few
    // seconds after it is said, so the same name is announced again and no stale node lingers.
    var announcement by remember { mutableStateOf("") }
    LaunchedEffect(calendars, deletingIds) {
        val gone = deletingIds.filterTo(mutableSetOf()) { id -> calendars.none { it.calendar.id == id } }
        if (gone.isNotEmpty()) deletingIds = deletingIds - gone
    }
    LaunchedEffect(announcement) {
        if (announcement.isNotEmpty()) {
            delay(ANNOUNCEMENT_CLEAR_MS)
            announcement = ""
        }
    }

    Scaffold(
        topBar = {
            SettingsTopAppBar(
                title = stringResource(R.string.settings_title),
                onNavigateBack = onNavigateBack,
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(
                    top = paddingValues.calculateTopPadding(),
                    bottom = paddingValues.calculateBottomPadding() + 16.dp
                )
            ) {
                item(key = "page_heading", contentType = "page_heading") {
                    NestedSettingsHeading(text = stringResource(R.string.offline_calendars_row_label))
                }
                item(key = "info", contentType = "info") {
                    LineWithInfo(
                        detail = stringResource(R.string.offline_calendars_info),
                        infoDescription = stringResource(R.string.offline_calendars_info_cd),
                        modifier = Modifier.padding(start = 24.dp, end = 8.dp, bottom = 8.dp),
                    ) {
                        Text(
                            stringResource(R.string.offline_calendars_manage_note),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                items(items = rows, key = { (summary, _) -> summary.calendar.id }, contentType = { "calendar" }) { (summary, name) ->
                    val id = summary.calendar.id
                    if (id in deletingIds || id == revealed.confirmingId) {
                        DeleteConfirm(
                            name = name,
                            eventCount = summary.eventCount,
                            deleting = id in deletingIds,
                            onCancel = { revealed.confirmingId = null },
                            onConfirm = {
                                // The running delete keeps the confirm from here on, so a tap
                                // elsewhere isn't spent closing it.
                                deletingIds = deletingIds + id
                                if (revealed.confirmingId == id) revealed.confirmingId = null
                                announcement = ""
                                scope.launch {
                                    val result = onDeleteCalendar(id)
                                    if (result.isSuccess) {
                                        announcement = resources.getString(R.string.offline_calendar_deleted, name)
                                    } else {
                                        deletingIds = deletingIds - id
                                    }
                                }
                            },
                        )
                    } else {
                        SwipeRevealRow(
                            enabled = !summary.calendar.isBuiltInLocal(),
                            open = revealed.openId == id,
                            onOpenChange = { revealed.set(id, it) },
                            deleteLabel = stringResource(R.string.offline_calendar_delete_cd, name),
                            onDelete = { revealed.confirmingId = id },
                        ) { actions ->
                            OfflineCalendarRow(
                                summary = summary,
                                name = name,
                                isDefault = id == defaultCalendarId,
                                onClick = { revealed.closeOr { openCalendarId = id } },
                                modifier = actions,
                            )
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 52.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                }
                item(key = "new_calendar", contentType = "add_button") {
                    Box(modifier = Modifier.padding(top = 8.dp)) {
                        AddSourceButton(
                            label = stringResource(R.string.offline_calendar_new),
                            onClick = { revealed.close(); showCreateSheet = true },
                        )
                    }
                }
            }
            // Outside the list so it is always placed: a live region that scrolled away would not
            // be announced. One dp tall, because a node with no size never reaches TalkBack.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(1.dp)
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        if (announcement.isNotEmpty()) contentDescription = announcement
                    }
            )
        }
    }

    if (openCalendar != null) {
        val id = openCalendar.calendar.id
        OfflineCalendarSheet(
            summary = openCalendar,
            onRename = { name -> onRenameCalendar(id, name) },
            onRecolor = { color -> scope.launch { onRecolorCalendar(id, color) } },
            onExport = { onExportCalendar(id) },
            onDismiss = { openCalendarId = null },
        )
    }

    if (showCreateSheet) {
        NewOfflineCalendarSheet(
            onCreate = onCreateCalendar,
            onCreated = { _, _, _ -> showCreateSheet = false },
            onDismiss = { showCreateSheet = false },
        )
    }
}

/**
 * Shows one offline calendar as [OfflineCalendarRowContent]. TalkBack hears "Default" as the
 * default calendar's state. [modifier] goes on the row's clickable node, where [SwipeRevealRow]
 * puts its action.
 */
@Composable
private fun OfflineCalendarRow(
    summary: LocalCalendarSummary,
    name: String,
    isDefault: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val defaultState = stringResource(R.string.offline_calendar_default_state)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isDefault) Modifier.semantics { stateDescription = defaultState } else Modifier)
            .then(modifier)
            .clickable(onClick = onClick),
    ) {
        OfflineCalendarRowContent(
            color = summary.calendar.color,
            name = name,
            eventCount = summary.eventCount,
            isDefault = isDefault,
        )
    }
}

/**
 * Draws one offline calendar row: [color] dot, [name] on one line and [eventCount]. The default
 * calendar's row is tinted as the Default calendar sheet marks its pick. The screen's row adds the
 * tap, the swipe action and the TalkBack state; the What's New illustration draws it bare with a
 * tighter [contentPadding] and [minHeight].
 */
@Composable
internal fun OfflineCalendarRowContent(
    color: Int,
    name: String,
    eventCount: Int,
    isDefault: Boolean,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
    minHeight: Dp = 56.dp,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                if (isDefault) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else Color.Transparent
            )
            .heightIn(min = minHeight)
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(Color(color))
        )
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = pluralStringResource(R.plurals.event_count, eventCount, eventCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Shows the inline confirm in place of a calendar's row: the message with the calendar's name and
 * event count, an "Also delete N events" checkbox when it has events, Cancel and Delete. Delete is
 * off until the box is ticked, and while [deleting]. The box starts unticked each time the confirm
 * appears.
 */
@Composable
private fun DeleteConfirm(
    name: String,
    eventCount: Int,
    deleting: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    var ticked by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f))
            .padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        Text(
            text = if (eventCount > 0) {
                pluralStringResource(R.plurals.offline_calendar_delete_confirm, eventCount, eventCount, name)
            } else {
                stringResource(R.string.offline_calendar_delete_empty, name)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (eventCount > 0) {
            Row(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .toggleable(value = ticked, enabled = !deleting, role = Role.Checkbox, onValueChange = { ticked = it })
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Checkbox(checked = ticked, onCheckedChange = null, enabled = !deleting)
                Text(
                    pluralStringResource(R.plurals.offline_calendar_delete_also, eventCount, eventCount),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel, enabled = !deleting) {
                Text(stringResource(R.string.action_cancel))
            }
            Button(
                onClick = onConfirm,
                enabled = !deleting && (eventCount == 0 || ticked),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.action_delete), modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

/** How long the live region keeps "<name> deleted" before it is cleared. */
private const val ANNOUNCEMENT_CLEAR_MS = 3_000L
