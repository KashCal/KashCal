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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.domain.EmojiMatcher
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.util.DateTimeUtils
import org.onekash.kashcal.util.location.looksLikeAddress
import org.onekash.kashcal.util.location.openInMaps
import org.onekash.kashcal.util.text.containsUrl
import org.onekash.kashcal.util.text.extractUrls
import org.onekash.kashcal.util.text.shouldOpenExternally

/**
 * Quick view sheet for device calendar events.
 *
 * Shows event details in the same visual style as [EventQuickViewSheet]
 * with Duplicate and Share actions (matching the read-only calendar pattern).
 *
 * @param displayEvent The device calendar event to display
 * @param showEventEmojis Whether to prefix auto-detected emoji to the title
 * @param onDismiss Called when sheet is dismissed
 * @param onDuplicate Called to duplicate this event into a KashCal calendar
 * @param onShare Called to share event details as text
 * @param timeFormat Time format preference: "system", "12h", or "24h"
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceEventQuickViewSheet(
    displayEvent: DisplayEvent.Device,
    showEventEmojis: Boolean = true,
    onDismiss: () -> Unit,
    onDuplicate: () -> Unit = {},
    onShare: () -> Unit = {},
    timeFormat: String = "system"
) {
    val hasExpandableContent = remember(displayEvent.description) {
        !displayEvent.description.isNullOrBlank()
    }

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = hasExpandableContent
    )

    val context = LocalContext.current
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
                            timePattern
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Location
                    val location = displayEvent.location
                    if (!location.isNullOrEmpty()) {
                        val locationContext = LocalContext.current
                        val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                        val isAddress = remember(location) { looksLikeAddress(location) }
                        val hasUrl = remember(location) { !isAddress && containsUrl(location) }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = when {
                                isAddress -> Modifier.clickable { openInMaps(locationContext, location) }
                                hasUrl -> Modifier.clickable {
                                    val urls = extractUrls(location, limit = 1)
                                    urls.firstOrNull()?.let { detected ->
                                        if (shouldOpenExternally(detected.url)) {
                                            try { uriHandler.openUri(detected.url) } catch (_: Exception) {}
                                        }
                                    }
                                }
                                else -> Modifier
                            }
                        ) {
                            Icon(
                                imageVector = if (hasUrl) Icons.Default.Link else Icons.Default.Place,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (isAddress || hasUrl) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = location,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isAddress || hasUrl) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                textDecoration = if (isAddress || hasUrl) TextDecoration.Underline else null,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (isAddress || hasUrl) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    Icons.AutoMirrored.Filled.Launch,
                                    contentDescription = if (isAddress) "Open in maps" else "Open link",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    // Repeat info
                    if (displayEvent.hasRrule) {
                        Text(
                            text = "\uD83D\uDD01 Recurring",
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
                                    color = Color(displayEvent.calendarColor),
                                    shape = RoundedCornerShape(50)
                                )
                        )
                        Text(
                            text = displayEvent.calendarName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Description section
            if (hasExpandableContent) {
                DeviceEventDescriptionSection(
                    description = displayEvent.description,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Action buttons (matches EventQuickViewSheet read-only layout)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
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
            }
        }
    }
}

/**
 * Description section for device events.
 */
@Composable
private fun DeviceEventDescriptionSection(
    description: String?,
    modifier: Modifier = Modifier
) {
    if (description.isNullOrBlank()) return

    val scrollState = rememberScrollState()

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

        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
    timePattern: String = "h:mm a"
): String {
    val startDateStr = DateTimeUtils.formatEventDateShort(startTs, isAllDay)
    val endDateStr = DateTimeUtils.formatEventDateShort(endTs, isAllDay)
    val isMultiDay = DateTimeUtils.spansMultipleDays(startTs, endTs, isAllDay)

    return if (isAllDay) {
        if (isMultiDay) {
            "$startDateStr \u2192 $endDateStr \u00b7 All day"
        } else {
            "$startDateStr \u00b7 All day"
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
