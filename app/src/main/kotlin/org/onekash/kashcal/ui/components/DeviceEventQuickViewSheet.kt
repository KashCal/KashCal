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
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.domain.EmojiMatcher
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.rrule.RruleBuilder
import org.onekash.kashcal.ui.components.pickers.rememberRruleDisplayStrings
import org.onekash.kashcal.util.DateTimeUtils
import org.onekash.kashcal.util.location.openInMaps
import org.onekash.kashcal.util.text.containsUrl
import org.onekash.kashcal.util.text.extractUrls
import org.onekash.kashcal.util.text.shouldOpenExternally

/**
 * Shows the quick view sheet for a device calendar event, in the layout of [EventQuickViewSheet].
 *
 * With WRITE_CALENDAR granted on a writable calendar it offers Edit, Delete and a More menu
 * (Duplicate, Share as text, Export ICS); otherwise Duplicate and Share.
 *
 * @param showEventEmojis whether to prefix an auto-detected emoji to the title
 * @param onEdit opens the edit form; for a series the host decides the scope at save
 * @param onEditOccurrence never called: no button in this sheet uses it
 * @param onDelete deletes the event. A one-off event asks for an inline confirmation first; a
 *   series occurrence calls it at once and the host resolves the scope.
 * @param onDuplicate duplicates this event into a KashCal calendar
 * @param onShare shares the event details as text
 * @param onShareAsCard opens the share-as-card sheet (top-right icon)
 * @param showShareCardTooltip true to show the one-shot coach mark on the Share icon; the caller
 *   persists its dismissal
 * @param onShareCardTooltipDismissed called when the tooltip should be marked as shown: after it
 *   first shows or on the first tap of the Share icon
 * @param attendees the event's guests; empty shows no guest section
 * @param isCurrentUserOnList whether the calendar owner is among [attendees]
 * @param onRsvp called with the chosen response when the user changes their own RSVP. The RSVP
 *   cards show only on a writable calendar, for a user with a self row who isn't the organizer.
 * @param timeFormat time format preference: "system", "12h" or "24h"
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceEventQuickViewSheet(
    displayEvent: DisplayEvent.Device,
    showEventEmojis: Boolean = true,
    hasWritePermission: Boolean = false,
    isWritableCalendar: Boolean = false,
    attendees: List<org.onekash.kashcal.ui.components.attendees.AttendeeUiModel> = emptyList(),
    isCurrentUserOnList: Boolean = false,
    onRsvp: (org.onekash.kashcal.ui.components.attendees.AttendeeStatus) -> Unit = {},
    onDismiss: () -> Unit,
    onEdit: () -> Unit = {},
    onEditOccurrence: () -> Unit = {},
    onDelete: () -> Unit = {},
    onDuplicate: () -> Unit = {},
    onShare: () -> Unit = {},
    onExportIcs: () -> Unit = {},
    onShareAsCard: () -> Unit = {},
    showShareCardTooltip: Boolean = false,
    onShareCardTooltipDismissed: () -> Unit = {},
    timeFormat: String = "system"
) {
    val canWrite = hasWritePermission && isWritableCalendar
    val isRecurring = displayEvent.isPartOfRecurringSeries
    val you = attendees.firstOrNull { it.isYou }
    val isCurrentUserOrganizer = you?.isOrganizer == true
    // A reply writes the tapped occurrence's event row: the series row for an unchanged
    // occurrence, so it answers for the whole series (DeviceGuestsTagsRoundTripTest); a changed
    // occurrence has its own row.
    val instance = displayEvent.instance
    val rsvpAppliesToSeries = instance.hasRrule &&
        instance.originalId == null && instance.originalInstanceTime == null
    var showAttendeeSheet by remember { mutableStateOf(false) }

    // Opens at its content height, up to the whole window, as [EventQuickViewSheet] does.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val context = LocalContext.current
    val resources = LocalResources.current
    val is24HourDevice = DateFormat.is24HourFormat(context)
    val timePattern = remember(timeFormat, is24HourDevice) {
        DateTimeUtils.getTimePattern(timeFormat, is24HourDevice)
    }

    val displayTitle = remember(displayEvent.title, showEventEmojis) {
        EmojiMatcher.formatWithEmoji(displayEvent.title, showEventEmojis)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        // TalkBack names the sheet as [EventQuickViewSheet] does: the title, or "Untitled
        // event" when it is blank.
        val paneTitleText = displayTitle.ifBlank { stringResource(R.string.cd_event_untitled) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
                .semantics { paneTitle = paneTitleText }
        ) {
            // The details scroll and the buttons below stay pinned, as in [EventQuickViewSheet].
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
            ) {
                ShareAsCardTopRow(
                    calendarColor = displayEvent.calendarColor,
                    calendarName = displayEvent.calendarName,
                    onShareClick = {
                        onShareCardTooltipDismissed()
                        onShareAsCard()
                    },
                    showTooltip = showShareCardTooltip,
                    onTooltipDisplayed = onShareCardTooltipDismissed,
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(IntrinsicSize.Min)
                ) {
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .fillMaxHeight()
                            .background(
                                color = Color(displayEvent.calendarColor),
                                shape = RoundedCornerShape(2.dp)
                            )
                    )

                    Spacer(modifier = Modifier.width(16.dp))

                    SelectionContainer {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = displayTitle,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.semantics { heading() }
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = formatEventDateTime(
                                    displayEvent.startTs,
                                    displayEvent.endTs,
                                    displayEvent.isAllDay,
                                    resources,
                                    timePattern
                                ),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            val location = displayEvent.location
                            if (location.isNotEmpty()) {
                                val locationContext = LocalContext.current
                                val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                                val hasUrl = remember(location) { containsUrl(location) }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = if (hasUrl) {
                                        Modifier.clickable {
                                            val urls = extractUrls(location, limit = 1)
                                            urls.firstOrNull()?.let { detected ->
                                                if (shouldOpenExternally(detected.url)) {
                                                    try { uriHandler.openUri(detected.url) } catch (_: Exception) {}
                                                }
                                            }
                                        }
                                    } else {
                                        Modifier.clickable { openInMaps(locationContext, location) }
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
                                        text = location,
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

                            if (displayEvent.categories.isNotEmpty()) {
                                Row(verticalAlignment = Alignment.Top) {
                                    Icon(
                                        imageVector = Icons.Default.LocalOffer,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    org.onekash.kashcal.ui.components.category.TagChipRow(
                                        selected = displayEvent.categories.toSet(),
                                        suggestions = emptyList(),
                                        onToggle = {},
                                        onAdd = {},
                                        readOnly = true
                                    )
                                }
                            }

                            // A series occurrence and a changed occurrence both get the
                            // repeat line; a changed occurrence has no rule of its own and
                            // reads "Recurring".
                            if (isRecurring) {
                                val rruleStrings = rememberRruleDisplayStrings()
                                val repeatText = remember(
                                    instance.rrule, instance.hasRrule, instance.eventStartTs,
                                    instance.isAllDay, instance.timezone, rruleStrings, resources,
                                ) {
                                    recurrenceDisplayText(
                                        rrule = instance.rrule.takeIf { instance.hasRrule },
                                        seriesStartDate = if (instance.hasRrule) {
                                            formatSeriesStartDateStr(instance.eventStartTs, instance.isAllDay)
                                        } else {
                                            null
                                        },
                                        rruleStrings = rruleStrings,
                                        untilZone = RruleBuilder.untilZoneFor(instance.isAllDay, instance.timezone),
                                        resources = resources,
                                    )
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
                            currentUserPartstat = you?.status,
                            isOrganizer = isCurrentUserOrganizer,
                            isRecurring = rsvpAppliesToSeries,
                        )
                    ) stringResource(R.string.rsvp_series_disclosure) else null
                    // RSVP cards only on a writable calendar; InviteesBlock also hides
                    // them for the organizer and for a user with no self row.
                    org.onekash.kashcal.ui.components.attendees.InviteesBlock(
                        attendees = attendees,
                        isCurrentUserOnList = isCurrentUserOnList,
                        isCurrentUserOrganizer = isCurrentUserOrganizer,
                        onRsvp = onRsvp,
                        onDrillIntoAttendees = { showAttendeeSheet = true },
                        seriesDisclosure = seriesDisclosure,
                        suppressRsvp = !canWrite,
                        alwaysExpanded = false,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }

                if (!displayEvent.description.isNullOrBlank()) {
                    ExpandedContentSection(
                        description = displayEvent.description,
                        validEventUrl = null,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            DeviceEventActionButtons(
                canWrite = canWrite,
                isRecurring = isRecurring,
                onEdit = onEdit,
                onEditOccurrence = onEditOccurrence,
                onDelete = onDelete,
                onDuplicate = onDuplicate,
                onShare = onShare,
                onExportIcs = onExportIcs
            )
        }
    }

    if (showAttendeeSheet) {
        org.onekash.kashcal.ui.components.attendees.AttendeeListSheet(
            attendees = attendees,
            onDismiss = { showAttendeeSheet = false },
        )
    }
}

/** Shows Edit, Delete and More when [canWrite], else Duplicate and Share. */
@Composable
private fun DeviceEventActionButtons(
    canWrite: Boolean,
    isRecurring: Boolean,
    onEdit: () -> Unit,
    onEditOccurrence: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onShare: () -> Unit,
    onExportIcs: () -> Unit
) {
    var showMoreMenu by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!canWrite) {
            FilledTonalButton(
                onClick = onDuplicate,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.action_duplicate))
            }
            FilledTonalButton(
                onClick = onShare,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.action_share))
            }
        } else {
            // A one-off event takes an inline two-tap confirmation because the
            // host deletes it at once. A series occurrence skips it: the host's
            // scope sheet confirms an occurrence of the master, though an
            // exception is deleted with no confirmation.
            if (!showDeleteConfirmation) {
                FilledTonalButton(
                    onClick = onEdit,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.action_edit))
                }

                FilledTonalButton(
                    onClick = {
                        if (isRecurring) {
                            onDelete()
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
                    Text(stringResource(R.string.action_delete))
                }
            } else {
                FilledTonalButton(
                    onClick = { showDeleteConfirmation = false },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
                FilledTonalButton(
                    onClick = {
                        showDeleteConfirmation = false
                        onDelete()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text(stringResource(R.string.action_confirm))
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
