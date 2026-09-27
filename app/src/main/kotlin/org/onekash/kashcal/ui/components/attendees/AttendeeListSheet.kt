package org.onekash.kashcal.ui.components.attendees

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R

private val WHITESPACE_REGEX = Regex("\\s+")

/** Below this attendee count the sheet shows no search field. */
private const val SEARCH_THRESHOLD = 6

/**
 * Shows an event's full attendee list as a bottom sheet over the quick view or event form,
 * opened from [InviteesBlock]'s drill-in. A separate sheet, because an inline list of many
 * attendees pushes the Edit and Delete buttons off-screen.
 *
 * Rows are grouped by status as [buildAttendeeListSections] orders and filters them, under
 * sticky section headers. From [SEARCH_THRESHOLD] attendees up, a search field filters them.
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
    // When the list drops below the threshold with a query active (a live update, for example),
    // the search field goes away, so clear the query or a hidden filter stays applied.
    LaunchedEffect(canSearch) {
        if (!canSearch) query = ""
    }
    val sections = remember(attendees, query) {
        buildAttendeeListSections(attendees, query)
    }

    // A tall fixed height, like the picker's, so the sheet doesn't open wrapped to a few rows.
    // Read-only, so it stays swipe-dismissable: there is nothing in progress to lose.
    val configuration = LocalConfiguration.current
    val sheetHeight = remember(configuration.orientation, configuration.screenWidthDp) {
        (configuration.screenHeightDp * 0.95f).dp
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(sheetHeight)
        ) {
        Text(
            text = stringResource(R.string.attendee_sheet_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        )
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
                    .weight(1f)
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
                    .weight(1f)
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
                        // bareAddress alone can repeat: two rows can canonicalize to one
                        // address, and every device guest with no email has an empty one.
                        // The section and sortOrder keep the keys apart.
                        key = { row -> "row_${section.status.name}_${row.bareAddress}_${row.sortOrder}" },
                    ) { row ->
                        AttendeeRow(row)
                    }
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
    // A sticky header floats over the rows, so it needs a solid background or it reads as
    // translucent over them.
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
    // The current user shows as "You", so their row doesn't read as the account label (for
    // example "iCloud"). The avatar initials stay on the real name.
    val nameLabel = if (model.isYou) stringResource(R.string.attendee_you_marker) else model.displayName
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
                    text = nameLabel,
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
            // The tonal fill can wash out against the surface for pale accent seeds;
            // a hairline outline keeps the badge edge defined on any theme.
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
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
