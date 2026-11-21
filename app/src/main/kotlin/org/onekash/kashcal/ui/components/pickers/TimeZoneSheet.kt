package org.onekash.kashcal.ui.components.pickers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.components.SearchPillTextField
import org.onekash.kashcal.util.TimezoneUtils
import org.onekash.kashcal.util.zone.ZoneCatalog
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Test tags of the sheet's list and search field. */
internal const val TAG_TIME_ZONE_LIST = "time_zone_list"
internal const val TAG_TIME_ZONE_SEARCH = "time_zone_search"

/**
 * Shows the event form's full-height time zone sheet: This event, Device time zone, Recent and
 * All time zones, with ranked search ([timeZoneSheetRows]). Offsets are those at [at], the
 * event's start. The keyboard doesn't open by itself: most picks need no typing.
 *
 * @param eventZone the form's timezone (null = device zone)
 * @param sourceZone the event's unreadable zone, kept when the form shows device time
 * @param onPick called once with the tapped row's [ZonePick]; the caller closes the sheet
 * @param loadEntries builds the catalog list off the main thread; tests replace it
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimeZoneSheet(
    eventZone: String?,
    sourceZone: String?,
    at: Instant,
    recents: List<String>,
    onPick: (ZonePick) -> Unit,
    onDismiss: () -> Unit,
    deviceZone: String = TimezoneUtils.getDeviceTimezone(),
    loadEntries: suspend (Locale, Instant) -> List<ZoneCatalog.Entry> = { locale, instant ->
        withContext(Dispatchers.Default) { ZoneCatalog.entries(locale, instant) }
    },
) {
    val locale = LocalConfiguration.current.locales[0]
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by rememberSaveable { mutableStateOf("") }
    val entries by produceState<List<ZoneCatalog.Entry>?>(null, locale, at) {
        value = loadEntries(locale, at)
    }
    val rows = remember(eventZone, sourceZone, deviceZone, recents, entries, query, at, locale) {
        timeZoneSheetRows(eventZone, sourceZone, deviceZone, recents, entries, query, at, locale)
    }
    // The event's own date, as the form shows it.
    val dateText = remember(at, locale, eventZone) {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
            .format(at.atZone(TimezoneUtils.resolveZone(eventZone)))
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // Fill the sheet's full height so the list scrolls in a fixed area.
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            SearchPillTextField(
                query = query,
                onQueryChange = { query = it },
                placeholder = stringResource(R.string.timezone_search_hint),
                leadingIcon = Icons.Default.Search,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)
                    .testTag(TAG_TIME_ZONE_SEARCH),
            )
            LazyColumn(modifier = Modifier.weight(1f).testTag(TAG_TIME_ZONE_LIST)) {
                items(rows, key = { it.key }) { row ->
                    when (row) {
                        is ZoneSheetRow.Header -> SectionHeader(row.section, dateText)
                        is ZoneSheetRow.Zone -> ZoneRow(row, onClick = { onPick(row.pick) })
                        is ZoneSheetRow.Unresolved -> UnresolvedRow(row, onClick = { onPick(row.pick) })
                        ZoneSheetRow.Loading -> LoadingRow()
                        ZoneSheetRow.Empty -> EmptyState()
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(section: ZoneSection, dateText: String) {
    val title = when (section) {
        ZoneSection.THIS_EVENT -> R.string.timezone_section_this_event
        ZoneSection.DEVICE -> R.string.timezone_section_device
        ZoneSection.RECENT -> R.string.timezone_section_recent
        ZoneSection.ALL, ZoneSection.RESULTS -> R.string.timezone_section_all
    }
    Column(modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp)) {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() },
        )
        if (section == ZoneSection.ALL) {
            Text(
                stringResource(R.string.timezone_offsets_on, dateText),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ZoneRow(row: ZoneSheetRow.Zone, onClick: () -> Unit) {
    val entry = row.entry
    val title = entry.city.ifEmpty { entry.offset }
    val secondLine = when {
        entry.country != null && entry.zoneName != null ->
            stringResource(R.string.timezone_sheet_country_zone, entry.country, entry.zoneName)
        else -> entry.country ?: entry.zoneName
    }
    val subtitle = row.fromCalendar?.let { stringResource(R.string.timezone_from_calendar, it) }
    val trailing = when {
        entry.city.isEmpty() -> null
        entry.abbreviation != null -> stringResource(R.string.timezone_sheet_abbr_offset, entry.abbreviation, entry.offset)
        else -> entry.offset
    }
    // Everything the row shows, in reading order: city, country, zone name, abbreviation, offset.
    val parts = if (entry.city.isEmpty()) {
        listOfNotNull(entry.offset, subtitle)
    } else {
        listOfNotNull(entry.city, entry.country, entry.zoneName, entry.abbreviation, entry.offset, subtitle)
    }
    val description = parts.reduce { acc, part -> stringResource(R.string.cd_timezone_join, acc, part) }
    SelectableRow(
        selected = row.selected,
        description = description,
        onClick = onClick,
        title = title,
        lines = listOfNotNull(secondLine, subtitle),
        trailing = trailing,
    )
}

@Composable
private fun UnresolvedRow(row: ZoneSheetRow.Unresolved, onClick: () -> Unit) {
    val note = stringResource(R.string.timezone_shown_in_device_time)
    SelectableRow(
        selected = true,
        description = stringResource(R.string.cd_timezone_join, row.sourceId, note),
        onClick = onClick,
        title = row.sourceId,
        lines = listOf(note),
        trailing = null,
    )
}

@Composable
private fun SelectableRow(
    selected: Boolean,
    description: String,
    onClick: () -> Unit,
    title: String,
    lines: List<String>,
    trailing: String?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .clearAndSetSemantics {
                this.selected = selected
                contentDescription = description
            }
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            lines.forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LoadingRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(
            stringResource(R.string.timezone_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.timezone_no_match),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.size(2.dp))
        Text(
            stringResource(R.string.timezone_no_match_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}
