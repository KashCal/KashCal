package org.onekash.kashcal.ui.components.attendees

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R

private val WHITESPACE_REGEX = Regex("\\s+")

/** Below this attendee count, no search field is rendered. */
private const val SEARCH_THRESHOLD = 6

/**
 * Full attendee list opened from [InviteesBlock]'s caret affordance.
 * Replaces the older inline FlowRow that expanded below the chip row —
 * for events with many attendees the FlowRow sprawled past the action
 * buttons and pushed Edit/Delete off-screen.
 *
 * Composes a nested [ModalBottomSheet] over the underlying QuickView /
 * Form sheet (same Material 3 pattern already used by the timezone,
 * color, and recurring-scope picker sheets).
 *
 * Body is a [LazyColumn] whose items are produced by
 * [buildAttendeeListSections] — a pure function that groups by
 * [AttendeeStatus] in display order (Going / Maybe / Pending /
 * Declined / Delegated) and pins You to the top of each group.
 *
 * The search field above the list does a case-insensitive substring
 * match against [AttendeeUiModel.displayName] and
 * [AttendeeUiModel.bareAddress]. Sections whose group empties under
 * the filter drop their header; section headers stick to the top of
 * the viewport while their group is in view.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AttendeeListSheet(
    attendees: List<AttendeeUiModel>,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    val totalCount = attendees.size
    val canSearch = totalCount >= SEARCH_THRESHOLD
    // If the list shrinks below the search threshold while a query is
    // active (e.g. live Flow update), the OutlinedTextField unmounts —
    // clear the query so the user isn't left with a hidden filter.
    LaunchedEffect(canSearch) {
        if (!canSearch) query = ""
    }
    val sections = remember(attendees, query) {
        buildAttendeeListSections(attendees, query)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.attendee_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = pluralStringResource(R.plurals.attendee_count_invited, totalCount, totalCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (canSearch) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.attendee_search_hint)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                    )
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (sections.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.4f)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.attendee_search_no_matches),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
            ) {
                sections.forEach { section ->
                    stickyHeader(key = "header_${section.status.name}") {
                        SectionHeader(
                            status = section.status,
                            count = section.rows.size,
                        )
                    }
                    items(
                        items = section.rows,
                        // bareAddress is unique per attendee within the
                        // event; section name disambiguates synthesized
                        // organizer rows that share an address with a
                        // real attendee row.
                        key = { row -> "row_${section.status.name}_${row.bareAddress}_${row.sortOrder}" },
                    ) { row ->
                        AttendeeRow(row)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(status: AttendeeStatus, count: Int) {
    val label = when (status) {
        AttendeeStatus.Accepted -> stringResource(R.string.attendee_section_going)
        AttendeeStatus.Tentative -> stringResource(R.string.attendee_section_maybe)
        AttendeeStatus.NeedsAction -> stringResource(R.string.attendee_section_pending)
        AttendeeStatus.Declined -> stringResource(R.string.attendee_section_declined)
        AttendeeStatus.Delegated -> stringResource(R.string.attendee_section_delegated)
    }
    // Sticky headers float over the rows behind them — paint a solid
    // surface bg so they don't read translucent against scrolled rows.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "·",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AttendeeRow(model: AttendeeUiModel) {
    val avatarColor = if (model.isYou) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val avatarTextColor = if (model.isYou) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val showAddressLine = !model.isYou &&
        !model.bareAddress.equals(model.displayName, ignoreCase = true)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(avatarColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = avatarInitials(model.displayName),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = avatarTextColor,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = model.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (model.isYou) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (model.isOrganizer) {
                    Spacer(Modifier.width(8.dp))
                    HostBadge()
                }
            }
            if (showAddressLine) {
                Text(
                    text = model.bareAddress,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun HostBadge() {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = stringResource(R.string.invitees_host_tag),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            fontWeight = FontWeight.Medium,
        )
    }
}

internal fun avatarInitials(displayName: String): String {
    val trimmed = displayName.trim()
    if (trimmed.isEmpty()) return "?"
    val parts = trimmed.split(WHITESPACE_REGEX).filter { it.isNotBlank() }
    return when {
        parts.size >= 2 -> "${parts.first().first().uppercaseChar()}${parts.last().first().uppercaseChar()}"
        else -> trimmed.first().uppercaseChar().toString()
    }
}
