package org.onekash.kashcal.ui.components

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.data.contacts.ContactEventType
import org.onekash.kashcal.data.contacts.ContactEventUtils
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.domain.rrule.RruleBuilder
import org.onekash.kashcal.ui.components.pickers.rememberRruleDisplayStrings
import org.onekash.kashcal.util.DateTimeUtils
import org.onekash.kashcal.util.location.openInMaps
import org.onekash.kashcal.util.text.containsUrl
import org.onekash.kashcal.util.text.extractUrls
import org.onekash.kashcal.util.text.formatRemindersForDisplay
import org.onekash.kashcal.util.text.isValidUrl
import org.onekash.kashcal.util.text.shouldOpenExternally
import java.time.LocalDate
import java.time.ZoneId

/**
 * Shows a Room event's details in a bottom sheet, with Edit, Delete and More actions, or
 * Duplicate and Share on a read-only calendar.
 *
 * @param occurrenceTs start of the tapped occurrence; the sheet shows its date and time and
 *   computes a birthday's age from it. Null shows the event's own start.
 * @param onEditOccurrence never called by the sheet.
 * @param onDeleteSingle called at once for a recurring master (the host opens the scope sheet)
 *   and after the inline confirm for a one-off event or an exception.
 * @param onShareAsCard opens the share-as-card sheet from the top-right icon.
 * @param showShareCardTooltip shows the one-shot coach mark on the Share icon; the caller
 *   persists its dismissal.
 * @param onShareCardTooltipDismissed called when the tooltip counts as shown: after its first
 *   display or the first tap on the Share icon.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventQuickViewSheet(
    event: Event,
    calendarColor: Int,
    calendarName: String,
    occurrenceTs: Long? = null,
    showEventEmojis: Boolean = true,
    isReadOnlyCalendar: Boolean = false,
    attendees: List<org.onekash.kashcal.ui.components.attendees.AttendeeUiModel> = emptyList(),
    isCurrentUserOnList: Boolean = false,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onEditOccurrence: () -> Unit = {},
    onDeleteSingle: () -> Unit,
    onDuplicate: () -> Unit = {},
    onShare: () -> Unit = {},
    onExportIcs: () -> Unit = {},
    onShareAsCard: () -> Unit = {},
    showShareCardTooltip: Boolean = false,
    onShareCardTooltipDismissed: () -> Unit = {},
    onRsvp: (org.onekash.kashcal.ui.components.attendees.AttendeeStatus) -> Unit = {},
    timeFormat: String = "system"
) {
    val you = attendees.firstOrNull { it.isYou }
    val currentUserPartstat = you?.status
    val isCurrentUserOrganizer = you?.isOrganizer == true
    // A guest who isn't the organizer gets a read-only form, so Edit reads "Open".
    val canEditAsOrganizer = you == null || you.isOrganizer
    // Decides whether the sheet opens expanded. Attendees count: an event with guests but no
    // description or URL would otherwise stay at partial height under the guest section.
    val hasAttendees = attendees.isNotEmpty()
    val hasExpandableContent = remember(event.description, event.url, hasAttendees) {
        !event.description.isNullOrBlank() ||
            !event.url.isNullOrBlank() ||
            hasAttendees
    }

    // Open expanded when there's content below the header.
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = hasExpandableContent
    )

    var showMoreMenu by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var showAttendeeSheet by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val resources = LocalResources.current
    val is24HourDevice = DateFormat.is24HourFormat(context)
    val timePattern = remember(timeFormat, is24HourDevice) {
        DateTimeUtils.getTimePattern(timeFormat, is24HourDevice)
    }

    // A master (RRULE) and an exception (originalEventId) both get the repeat line.
    val isRecurring = event.isRecurring || event.isException
    // An RSVP writes only the event shown, so on an exception it covers one occurrence and
    // the "applies to the whole series" disclosure fits only a master.
    val rsvpAppliesToSeries = event.isRecurring && !event.isException

    // Adds a birthday's age and the optional emoji.
    val displayTitle = remember(event, occurrenceTs, showEventEmojis) {
        formatEventTitle(event, occurrenceTs, showEventEmojis, resources)
    }

    val validEventUrl = remember(event.url) {
        event.url?.takeIf { isValidUrl(it) }
    }
    val formattedReminders = remember(event.reminders) {
        formatRemindersForDisplay(event.reminders, resources)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        // An untitled event has a blank title, so the pane announces a generic name.
        val paneTitleText = displayTitle.ifBlank { stringResource(R.string.cd_event_untitled) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
                // Announce the sheet (by the event title) when it opens.
                .semantics { paneTitle = paneTitleText }
        ) {
            // Header strip under the drag handle: calendar pill left, share-as-card icon right.
            ShareAsCardTopRow(
                calendarColor = calendarColor,
                calendarName = calendarName,
                onShareClick = {
                    onShareCardTooltipDismissed()
                    onShareAsCard()
                },
                showTooltip = showShareCardTooltip,
                onTooltipDisplayed = onShareCardTooltipDismissed,
            )

            // Event details with color stripe
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(IntrinsicSize.Min)
            ) {
                // Left color stripe
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(
                            color = Color(calendarColor),
                            shape = RoundedCornerShape(2.dp)
                        )
                )

                Spacer(modifier = Modifier.width(16.dp))

                // Event details
                SelectionContainer {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Title
                        Text(
                            text = displayTitle,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.semantics { heading() }
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        // The tapped occurrence's date and time, not the master's first one.
                        val displayStartTs = occurrenceTs ?: event.startTs
                        val duration = event.endTs - event.startTs
                        val displayEndTs = if (occurrenceTs != null) occurrenceTs + duration else event.endTs
                        Text(
                            text = formatEventDateTime(displayStartTs, displayEndTs, event.isAllDay, resources, timePattern),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (!event.location.isNullOrEmpty()) {
                            val locationContext = LocalContext.current
                            val uriHandler = LocalUriHandler.current
                            val hasUrl = remember(event.location) { containsUrl(event.location) }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = if (hasUrl) {
                                    Modifier.clickable {
                                        val urls = extractUrls(event.location, limit = 1)
                                        urls.firstOrNull()?.let { detected ->
                                            if (shouldOpenExternally(detected.url)) {
                                                try { uriHandler.openUri(detected.url) } catch (_: Exception) {}
                                            }
                                        }
                                    }
                                } else {
                                    Modifier.clickable { openInMaps(locationContext, event.location) }
                                }
                            ) {
                                Icon(
                                    imageVector = if (hasUrl) Icons.Default.Link else Icons.Default.Place,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = event.location,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    textDecoration = TextDecoration.Underline,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    Icons.AutoMirrored.Filled.Launch,
                                    contentDescription = if (hasUrl) stringResource(R.string.cd_open_link) else stringResource(R.string.cd_open_maps),
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        // Tags (read-only chips)
                        if (event.categories.orEmpty().isNotEmpty()) {
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(
                                    imageVector = Icons.Default.LocalOffer,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                org.onekash.kashcal.ui.components.category.TagChipRow(
                                    selected = event.categories.orEmpty().toSet(),
                                    suggestions = emptyList(),
                                    onToggle = {},
                                    onAdd = {},
                                    readOnly = true
                                )
                            }
                        }

                        // Repeat info
                        if (isRecurring) {
                            val rruleStrings = rememberRruleDisplayStrings()
                            val repeatText = if (event.rrule != null) {
                                val (freq, endSuffix) = RruleBuilder.formatForDisplayParts(
                                    event.rrule,
                                    rruleStrings,
                                    RruleBuilder.untilZoneFor(event.isAllDay, event.timezone),
                                )
                                val isContactEvent = ContactEventType.fromCaldavUrl(event.caldavUrl) != null
                                val hasSyntheticStart = isContactEvent && ContactEventUtils.decodeEventYear(event.description) == null
                                val startDate = if (!hasSyntheticStart) formatSeriesStartDateStr(event.startTs, event.isAllDay) else null
                                if (endSuffix != null && startDate != null) {
                                    stringResource(R.string.rrule_starting, freq, startDate, endSuffix)
                                } else if (endSuffix != null) {
                                    "$freq$endSuffix"
                                } else if (startDate != null) {
                                    stringResource(R.string.rrule_since, freq, startDate)
                                } else {
                                    freq
                                }
                            } else {
                                stringResource(R.string.cd_recurring)
                            }
                            Text(
                                text = "\uD83D\uDD01 $repeatText",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (attendees.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                val seriesDisclosure = if (
                    org.onekash.kashcal.ui.components.attendees.shouldShowSeriesRsvpDisclosure(
                        currentUserPartstat = currentUserPartstat,
                        isOrganizer = isCurrentUserOrganizer,
                        isRecurring = rsvpAppliesToSeries,
                    )
                ) stringResource(R.string.rsvp_series_disclosure) else null

                org.onekash.kashcal.ui.components.attendees.InviteesBlock(
                    attendees = attendees,
                    isCurrentUserOnList = isCurrentUserOnList,
                    isCurrentUserOrganizer = isCurrentUserOrganizer,
                    onRsvp = onRsvp,
                    onDrillIntoAttendees = { showAttendeeSheet = true },
                    seriesDisclosure = seriesDisclosure,
                    alwaysExpanded = false,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            // URL, notes and reminders.
            if (hasExpandableContent) {
                ExpandedContentSection(
                    event = event,
                    validEventUrl = validEventUrl,
                    formattedReminders = formattedReminders,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Action buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (isReadOnlyCalendar) {
                    // Read-only calendar: Duplicate and Share in place of Edit and Delete.
                    FilledTonalButton(
                        onClick = onDuplicate,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.action_duplicate)
                        )
                    }
                    FilledTonalButton(
                        onClick = onShare,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = stringResource(R.string.action_share)
                        )
                    }
                } else {
                    // Editable calendar: Edit, Delete, More. Delete on a one-off
                    // event or an exception commits at once in the host, so it
                    // takes an inline two-tap confirm; a master's scope sheet is
                    // its own confirmation.
                    if (!showDeleteConfirmation) {
                        FilledTonalButton(
                            onClick = onEdit,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = stringResource(
                                    if (canEditAsOrganizer) R.string.action_edit
                                    else R.string.action_open
                                )
                            )
                        }

                        FilledTonalButton(
                            onClick = {
                                // Only a master gets the scope sheet;
                                // everything else confirms inline.
                                if (event.isRecurring && !event.isException) {
                                    onDeleteSingle()
                                } else {
                                    showDeleteConfirmation = true
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.action_delete)
                            )
                        }
                    } else {
                        FilledTonalButton(
                            onClick = { showDeleteConfirmation = false },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                stringResource(R.string.action_cancel),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                        }
                        FilledTonalButton(
                            onClick = {
                                showDeleteConfirmation = false
                                onDeleteSingle()
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            )
                        ) {
                            Text(
                                stringResource(R.string.action_confirm),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    if (!showDeleteConfirmation) {
                        Box {
                            FilledTonalButton(
                                onClick = { showMoreMenu = true }
                            ) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.cd_more_options)
                                )
                            }

                            DropdownMenu(
                                expanded = showMoreMenu,
                                onDismissRequest = { showMoreMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_duplicate)) },
                                    onClick = {
                                        showMoreMenu = false
                                        onDuplicate()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.cd_duplicate))
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_share_as_text)) },
                                    onClick = {
                                        showMoreMenu = false
                                        onShare()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.cd_share))
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_export_ics)) },
                                    onClick = {
                                        showMoreMenu = false
                                        onExportIcs()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.FileDownload, contentDescription = stringResource(R.string.cd_export_ics))
                                    }
                                )
                            }
                        }
                    }
                    }
                }
            }
        }

    if (showAttendeeSheet) {
        org.onekash.kashcal.ui.components.attendees.AttendeeListSheet(
            attendees = attendees,
            onDismiss = { showAttendeeSheet = false },
        )
    }
}

/**
 * Formats the date and time line: all-day dates in UTC so the calendar date holds, timed
 * events in the device zone.
 *
 * @see DateTimeUtils.formatEventDateShort
 * @see DateTimeUtils.formatEventTime
 */
private fun formatEventDateTime(
    startTs: Long,
    endTs: Long,
    isAllDay: Boolean,
    resources: android.content.res.Resources,
    timePattern: String = "h:mm a"
): String {
    val startDateStr = DateTimeUtils.formatEventDateShort(startTs, isAllDay)
    val endDateStr = DateTimeUtils.formatEventDateShort(endTs, isAllDay)
    val isMultiDay = DateTimeUtils.spansMultipleDays(startTs, endTs, isAllDay)

    return if (isAllDay) {
        if (isMultiDay) {
            resources.getString(R.string.event_date_range_all_day, startDateStr, endDateStr)
        } else {
            resources.getString(R.string.event_date_all_day, startDateStr)
        }
    } else {
        val startTime = DateTimeUtils.formatEventTime(startTs, isAllDay, timePattern)
        val endTime = DateTimeUtils.formatEventTime(endTs, isAllDay, timePattern)
        if (isMultiDay) {
            // Multi-day timed: show both dates and times
            "$startDateStr $startTime \u2192 $endDateStr $endTime"
        } else {
            "$startDateStr \u00b7 $startTime - $endTime"
        }
    }
}

/**
 * Formats a series' start date with no prefix: "Jan 15" in the current year, else
 * "Jan 15, 2023".
 */
internal fun formatSeriesStartDateStr(
    seriesStartTs: Long,
    isAllDay: Boolean,
    localZone: ZoneId = ZoneId.systemDefault()
): String {
    val startDate = DateTimeUtils.eventTsToLocalDate(seriesStartTs, isAllDay, localZone)
    val currentYear = LocalDate.now(localZone).year
    val pattern = if (startDate.year == currentYear) DateTimeUtils.localizedPattern("MMMd") else DateTimeUtils.localizedPattern("yMMMd")
    return DateTimeUtils.formatEventDate(seriesStartTs, isAllDay, pattern, localZone)
}

/** Shows the event's URL, notes and reminders in a scrolling section. */
@Composable
private fun ExpandedContentSection(
    event: Event,
    validEventUrl: String?,
    formattedReminders: String?,
    modifier: Modifier = Modifier
) {
    val uriHandler = LocalUriHandler.current
    val scrollState = rememberScrollState()

    SelectionContainer {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            if (validEventUrl != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (shouldOpenExternally(validEventUrl)) {
                                try { uriHandler.openUri(validEventUrl) } catch (_: Exception) {}
                            }
                        }
                ) {
                    Icon(
                        Icons.Default.Link,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = validEventUrl,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        Icons.AutoMirrored.Filled.Launch,
                        contentDescription = stringResource(R.string.cd_open_link),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Notes, with links made tappable.
            if (!event.description.isNullOrBlank()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.label_notes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LinkifiedText(
                        text = event.description,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
            }

            if (formattedReminders != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "\uD83D\uDD14",  // Bell emoji
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = formattedReminders,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

