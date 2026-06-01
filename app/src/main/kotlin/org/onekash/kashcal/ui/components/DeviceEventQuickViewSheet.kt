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
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Place
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
import androidx.compose.material3.RadioButton
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
import org.onekash.kashcal.util.text.formatRemindersFromMinutes
import org.onekash.kashcal.util.text.shouldOpenExternally

/**
 * Quick view sheet for device calendar events.
 *
 * Shows event details in the same visual style as [EventQuickViewSheet].
 * When the calendar is writable and WRITE_CALENDAR permission is granted,
 * shows Edit/Delete buttons. Otherwise shows Duplicate/Share (read-only mode).
 *
 * @param displayEvent The device calendar event to display
 * @param showEventEmojis Whether to prefix auto-detected emoji to the title
 * @param hasWritePermission Whether WRITE_CALENDAR permission is granted
 * @param isWritableCalendar Whether the calendar allows write access
 * @param onDismiss Called when sheet is dismissed
 * @param onEdit Called to edit all occurrences (or single event if not recurring)
 * @param onEditOccurrence Called to edit just this occurrence (recurring events)
 * @param onDelete Called to delete all occurrences (or single event if not recurring)
 * @param onDuplicate Called to duplicate this event into a KashCal calendar
 * @param onShare Called to share event details as text
 * @param onShareAsCard Called to open the share-as-card sheet (top-right icon)
 * @param showShareCardTooltip True on first appearance to display the
 *   one-shot coach mark on the Share icon. Caller persists dismissal.
 * @param onShareCardTooltipDismissed Invoked when the tooltip should be
 *   marked as displayed (after first show or first tap on the Share icon).
 * @param timeFormat Time format preference: "system", "12h", or "24h"
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceEventQuickViewSheet(
    displayEvent: DisplayEvent.Device,
    showEventEmojis: Boolean = true,
    hasWritePermission: Boolean = false,
    isWritableCalendar: Boolean = false,
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
    val hasExpandableContent = remember(displayEvent.description, displayEvent.reminders) {
        displayEvent.description.isNotBlank() || displayEvent.reminders.isNotEmpty()
    }

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = hasExpandableContent
    )

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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            // Top row beneath the drag handle: calendar dot+name pill on the
            // left, Share-as-card icon on the right. Mirrors
            // EventQuickViewSheet's header strip so the device-event sheet
            // surfaces the same share affordance as the Room sheet.
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

            // Event details with color stripe (same layout as EventQuickViewSheet)
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
                            color = Color(displayEvent.calendarColor),
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
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        // Date and time
                        Text(
                            text = formatDeviceEventDateTime(
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

                        // Repeat info
                        if (displayEvent.hasRrule) {
                            val rruleStrings = rememberRruleDisplayStrings()
                            val recurringFallback = stringResource(R.string.cd_recurring)
                            val repeatText = remember(displayEvent.rrule, rruleStrings) {
                                displayEvent.rrule?.let { rrule ->
                                    try {
                                        RruleBuilder.formatForDisplay(rrule, rruleStrings)
                                    } catch (_: Exception) {
                                        recurringFallback
                                    }
                                } ?: recurringFallback
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

            // Description and reminders section
            if (hasExpandableContent) {
                DeviceEventDescriptionSection(
                    description = displayEvent.description,
                    reminders = displayEvent.reminders,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Action buttons
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
}

/**
 * Action buttons for device event quick view.
 * Shows Edit/Delete when writable, Duplicate/Share when read-only.
 */
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
            // Read-only: show Duplicate and Share
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
            // Writable: Edit / Delete / More. Recurring events route
            // through the host's scope sheet (which serves as its own
            // confirmation); non-recurring events use the inline
            // two-tap pattern since the host commits immediately.
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
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_share)) },
                            onClick = {
                                showMoreMenu = false
                                onShare()
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

/**
 * Description and reminders section for device events.
 */
@Composable
private fun DeviceEventDescriptionSection(
    description: String?,
    reminders: List<Int>,
    modifier: Modifier = Modifier
) {
    val hasDescription = !description.isNullOrBlank()
    val hasReminders = reminders.isNotEmpty()
    if (!hasDescription && !hasReminders) return

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

            // Notes section (with linkified text)
            if (hasDescription) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.label_notes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LinkifiedText(
                        text = description!!,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
            }

            // Reminders section
            val resources = LocalResources.current
            val formattedReminders = remember(reminders, resources) {
                formatRemindersFromMinutes(reminders, resources)
            }
            if (formattedReminders != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "\uD83D\uDD14", // Bell emoji
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

/**
 * Format date and time for device event display.
 * Uses same formatting as EventQuickViewSheet.
 */
private fun formatDeviceEventDateTime(
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
            "$startDateStr $startTime \u2192 $endDateStr $endTime"
        } else {
            "$startDateStr \u00b7 $startTime - $endTime"
        }
    }
}
