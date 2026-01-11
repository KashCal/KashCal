package org.onekash.kashcal.ui.components

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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.automirrored.filled.Launch
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.data.contacts.ContactBirthdayRepository
import org.onekash.kashcal.util.location.looksLikeAddress
import org.onekash.kashcal.util.location.openInMaps
import org.onekash.kashcal.data.contacts.ContactBirthdayUtils
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.domain.EmojiMatcher
import org.onekash.kashcal.domain.rrule.RruleBuilder
import org.onekash.kashcal.util.DateTimeUtils

/**
 * Lightweight preview sheet for quick event viewing.
 * Shows event details with Edit/Delete/More actions.
 *
 * @param event The event to display
 * @param calendarColor Calendar color for the event
 * @param calendarName Calendar name for display
 * @param occurrenceTs The occurrence timestamp (for recurring events, used for birthday age calculation)
 * @param onDismiss Called when sheet is dismissed
 * @param onEdit Called to edit the event (for single events or all occurrences)
 * @param onEditOccurrence Called to edit just this occurrence (recurring events)
 * @param onDeleteSingle Called to delete single event
 * @param onDeleteOccurrence Called to delete just this occurrence
 * @param onDeleteFuture Called to delete this and all future occurrences
 * @param onDuplicate Called to duplicate the event
 * @param onShare Called to share the event as text
 * @param onExportIcs Called to export the event as .ics file
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
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onEditOccurrence: () -> Unit = {},
    onDeleteSingle: () -> Unit,
    onDeleteOccurrence: () -> Unit = {},
    onDeleteFuture: () -> Unit = {},
    onDuplicate: () -> Unit = {},
    onShare: () -> Unit = {},
    onExportIcs: () -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState()
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var deleteAllFuture by remember { mutableStateOf(false) }
    var showEditConfirmation by remember { mutableStateOf(false) }
    var editAllOccurrences by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }

    // Detect recurring events: master events have rrule, exception events have originalEventId
    val isRecurring = event.isRecurring || event.isException

    // Format title with age for birthday events and optional emoji
    val displayTitle = remember(event, occurrenceTs, showEventEmojis) {
        formatEventTitle(event, occurrenceTs, showEventEmojis)
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

                    // Date and time - use occurrence timestamp for recurring events
                    // to show the date the user tapped (not master event's original date)
                    val displayStartTs = occurrenceTs ?: event.startTs
                    val duration = event.endTs - event.startTs
                    val displayEndTs = if (occurrenceTs != null) occurrenceTs + duration else event.endTs
                    Text(
                        text = formatEventDateTime(displayStartTs, displayEndTs, event.isAllDay),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Location - clickable if looks like address
                    if (!event.location.isNullOrEmpty()) {
                        val context = LocalContext.current
                        val isAddress = remember(event.location) { looksLikeAddress(event.location) }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = if (isAddress) {
                                Modifier.clickable { openInMaps(context, event.location) }
                            } else {
                                Modifier
                            }
                        ) {
                            Icon(
                                Icons.Default.Place,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (isAddress) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = event.location,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isAddress) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                textDecoration = if (isAddress) TextDecoration.Underline else null
                            )
                            if (isAddress) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    Icons.AutoMirrored.Filled.Launch,
                                    contentDescription = "Open in maps",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    // Repeat info
                    if (isRecurring) {
                        // For exception events (no rrule), show generic "Recurring"
                        val repeatText = if (event.rrule != null) {
                            RruleBuilder.formatForDisplay(event.rrule)
                        } else {
                            "Recurring"
                        }
                        Text(
                            text = "\uD83D\uDD01 $repeatText",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Calendar name
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(
                                    color = Color(calendarColor),
                                    shape = RoundedCornerShape(50)
                                )
                        )
                        Text(
                            text = calendarName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
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
                    // Read-only calendar: show Duplicate and Share (matching Edit/Delete style)
                    FilledTonalButton(
                        onClick = onDuplicate,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Duplicate")
                    }
                    FilledTonalButton(
                        onClick = onShare,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Share")
                    }
                } else {
                    // Editable calendar: Edit, Delete, More menu
                    // Edit button / inline confirmation
                    if (!showDeleteConfirmation) {
                        // Hide edit when delete confirmation is active
                        if (!showEditConfirmation) {
                            FilledTonalButton(
                                onClick = {
                                    if (isRecurring) {
                                        showEditConfirmation = true
                                    } else {
                                        onEdit()
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Edit")
                            }
                        } else {
                            // Inline edit confirmation for recurring events
                            FilledTonalButton(
                                onClick = {
                                    showEditConfirmation = false
                                    editAllOccurrences = false
                                },
                                modifier = Modifier.weight(0.5f)
                            ) {
                                Text("Cancel")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            FilledTonalButton(
                                onClick = {
                                    showEditConfirmation = false
                                    if (editAllOccurrences) {
                                        onEdit()
                                    } else {
                                        onEditOccurrence()
                                    }
                                },
                                modifier = Modifier.weight(0.5f)
                            ) {
                                Text("Confirm")
                            }
                        }
                    }

                    // Delete button / inline confirmation (hide when edit confirmation is active)
                    if (!showEditConfirmation) {
                        if (!showDeleteConfirmation) {
                            FilledTonalButton(
                                onClick = { showDeleteConfirmation = true },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                                )
                            ) {
                                Text("Delete")
                            }
                        } else {
                            // Inline delete confirmation
                            FilledTonalButton(
                                onClick = {
                                    showDeleteConfirmation = false
                                    deleteAllFuture = false
                                },
                                modifier = Modifier.weight(0.5f)
                            ) {
                                Text("Cancel")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            FilledTonalButton(
                                onClick = {
                                    showDeleteConfirmation = false
                                    if (isRecurring) {
                                        if (deleteAllFuture) {
                                            onDeleteFuture()
                                        } else {
                                            onDeleteOccurrence()
                                        }
                                    } else {
                                        onDeleteSingle()
                                    }
                                },
                                modifier = Modifier.weight(0.5f),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                )
                            ) {
                                Text("Confirm")
                            }
                        }
                    }

                    // More button with dropdown (hidden during confirmation)
                    if (!showEditConfirmation && !showDeleteConfirmation) {
                        Box {
                            FilledTonalButton(
                                onClick = { showMoreMenu = true }
                            ) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = "More options"
                                )
                            }

                            DropdownMenu(
                                expanded = showMoreMenu,
                                onDismissRequest = { showMoreMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Duplicate") },
                                    onClick = {
                                        showMoreMenu = false
                                        onDuplicate()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.ContentCopy, contentDescription = "Duplicate")
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Share") },
                                    onClick = {
                                        showMoreMenu = false
                                        onShare()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Share, contentDescription = "Share")
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Export as .ics") },
                                    onClick = {
                                        showMoreMenu = false
                                        onExportIcs()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.FileDownload, contentDescription = "Export as ICS")
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Recurring event delete options (shown inline when delete confirmation is active)
            if (showDeleteConfirmation && isRecurring) {
                Spacer(modifier = Modifier.height(16.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .selectableGroup()
                ) {
                    Text(
                        text = "Delete \"${event.title}\"",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Just this one
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = !deleteAllFuture,
                                onClick = { deleteAllFuture = false },
                                role = Role.RadioButton
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = !deleteAllFuture,
                            onClick = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Just this one")
                    }

                    // This and all future
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = deleteAllFuture,
                                onClick = { deleteAllFuture = true },
                                role = Role.RadioButton
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = deleteAllFuture,
                            onClick = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("This and all future")
                    }
                }
            }

            // Recurring event edit options (shown inline when edit confirmation is active)
            if (showEditConfirmation && isRecurring) {
                Spacer(modifier = Modifier.height(16.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .selectableGroup()
                ) {
                    Text(
                        text = "Edit \"${event.title}\"",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Just this occurrence
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = !editAllOccurrences,
                                onClick = { editAllOccurrences = false },
                                role = Role.RadioButton
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = !editAllOccurrences,
                            onClick = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Just this occurrence")
                    }

                    // All occurrences
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = editAllOccurrences,
                                onClick = { editAllOccurrences = true },
                                role = Role.RadioButton
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = editAllOccurrences,
                            onClick = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("All occurrences")
                    }
                }
            }
        }
    }
}

/**
 * Format date and time for display.
 *
 * Uses DateTimeUtils for correct timezone handling:
 * - All-day events: UTC to preserve calendar date
 * - Timed events: Local timezone for user's perspective
 *
 * @see DateTimeUtils.formatEventDateShort
 * @see DateTimeUtils.formatEventTime
 */
private fun formatEventDateTime(startTs: Long, endTs: Long, isAllDay: Boolean): String {
    // Use DateTimeUtils for correct timezone handling (UTC for all-day, local for timed)
    val startDateStr = DateTimeUtils.formatEventDateShort(startTs, isAllDay)
    val endDateStr = DateTimeUtils.formatEventDateShort(endTs, isAllDay)

    return if (isAllDay) {
        val isMultiDay = DateTimeUtils.spansMultipleDays(startTs, endTs, isAllDay = true)
        if (isMultiDay) {
            "$startDateStr \u2192 $endDateStr \u00b7 All day"
        } else {
            "$startDateStr \u00b7 All day"
        }
    } else {
        val startTime = DateTimeUtils.formatEventTime(startTs, isAllDay)
        val endTime = DateTimeUtils.formatEventTime(endTs, isAllDay)
        "$startDateStr \u00b7 $startTime - $endTime"
    }
}

/**
 * Format event title, adding age for birthday events and optional emoji.
 *
 * For contact birthday events (caldavUrl starts with "contact_birthday:"):
 * - Decodes birth year from event description
 * - Calculates age at the occurrence date
 * - Returns "Name's Xth Birthday" format
 *
 * For all other events, returns the original title with optional emoji prefix.
 *
 * @param event The event to format title for
 * @param occurrenceTs The occurrence timestamp for age calculation
 * @param showEmojis Whether to prefix auto-detected emoji to the title
 * @return Formatted title string
 */
private fun formatEventTitle(event: Event, occurrenceTs: Long?, showEmojis: Boolean = true): String {
    // Check if this is a contact birthday event
    val isBirthdayEvent = event.caldavUrl?.startsWith("${ContactBirthdayRepository.SOURCE_PREFIX}:") == true

    val baseTitle = if (!isBirthdayEvent || occurrenceTs == null) {
        event.title
    } else {
        // Decode birth year from description
        val birthYear = ContactBirthdayUtils.decodeBirthYear(event.description)

        // Display name is the raw title (e.g., "John Smith")
        val displayName = event.title

        if (birthYear == null) {
            // No birth year - show "Name's Birthday" without age
            "$displayName's Birthday"
        } else {
            // Calculate age and format title
            val age = ContactBirthdayUtils.calculateAge(birthYear, occurrenceTs)

            if (age > 0 && age < 150) {
                "$displayName's ${ContactBirthdayUtils.formatOrdinal(age)} Birthday"
            } else {
                // Invalid age - show "Name's Birthday" without age
                "$displayName's Birthday"
            }
        }
    }

    // Apply emoji formatting if enabled
    return EmojiMatcher.formatWithEmoji(baseTitle, showEmojis)
}
