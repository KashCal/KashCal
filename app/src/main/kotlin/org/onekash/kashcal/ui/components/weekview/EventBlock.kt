package org.onekash.kashcal.ui.components.weekview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.domain.EmojiMatcher

/**
 * Displays a single event block in the week view time grid.
 *
 * Text truncation priority (based on available height):
 * 1. Title (always shown, 1-2 lines depending on height)
 * 2. Time (only if height >= 36dp)
 * 3. Location (only if height >= 56dp)
 *
 * Shows clamp indicators when event is outside visible time range (6am-11pm):
 * - "^ starts 5:30am" if event starts before 6am
 * - "v ends 12:00am" if event ends after 11pm
 *
 * @param event The event to display
 * @param occurrence The occurrence for this event instance
 * @param height Height of the block (determines what content is shown)
 * @param color Calendar color for the background
 * @param clampedStart True if event starts before visible range
 * @param clampedEnd True if event ends after visible range
 * @param originalStartMinutes Original start time in minutes (for clamp indicator)
 * @param originalEndMinutes Original end time in minutes (for clamp indicator)
 * @param timePattern DateTimeFormatter pattern for time range (e.g., "h:mma" for 12h, "HH:mm" for 24h)
 * @param is24Hour True for 24-hour format in clamp indicators
 * @param onClick Called when event is tapped
 * @param modifier Modifier for the container
 */
@Composable
fun EventBlock(
    event: Event,
    occurrence: Occurrence,
    height: Dp,
    color: Int,
    clampedStart: Boolean = false,
    clampedEnd: Boolean = false,
    originalStartMinutes: Int = 0,
    originalEndMinutes: Int = 0,
    showEventEmojis: Boolean = true,
    timePattern: String = "h:mma",
    is24Hour: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Solid fill with contrasting text for readability
    val backgroundColor = Color(color)
    // Calculate luminance to determine text color (dark text for light backgrounds)
    val luminance = (0.299f * backgroundColor.red + 0.587f * backgroundColor.green + 0.114f * backgroundColor.blue)
    val textColor = if (luminance > 0.5f) Color.Black else Color.White

    // Determine what content fits based on height
    val showTime = height >= HEIGHT_THRESHOLD_TIME
    val showLocation = height >= HEIGHT_THRESHOLD_LOCATION && !event.location.isNullOrBlank()
    val titleMaxLines = if (height >= HEIGHT_THRESHOLD_TWO_LINE_TITLE) 2 else 1

    Box(
        modifier = modifier
            .height(height)  // Apply the calculated height
            .clip(RoundedCornerShape(4.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 4.dp)
        ) {
            // Clamp indicator (start) - shown above title
            if (clampedStart) {
                Text(
                    text = "\u2191 ${WeekViewUtils.formatClampIndicatorTime(originalStartMinutes, is24Hour)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.8f),
                    maxLines = 1
                )
            }

            // Title (always shown)
            Text(
                text = EmojiMatcher.formatWithEmoji(event.title, showEventEmojis),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = textColor,
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis
            )

            // Time (if height >= 36dp)
            if (showTime) {
                Text(
                    text = WeekViewUtils.formatTimeRange(occurrence.startTs, occurrence.endTs, timePattern),
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Location (if height >= 56dp)
            if (showLocation) {
                Text(
                    text = event.location!!,
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Clamp indicator (end) - shown at bottom
            if (clampedEnd) {
                Text(
                    text = "\u2193 ${WeekViewUtils.formatClampIndicatorTime(originalEndMinutes, is24Hour)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.8f),
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * Compact event block for overflow display ("+N more" list).
 * Shows only title and time in a single line.
 * Solid fill with contrasting text.
 *
 * @param timePattern DateTimeFormatter pattern for time range (e.g., "h:mma" for 12h, "HH:mm" for 24h)
 */
@Composable
fun CompactEventBlock(
    event: Event,
    occurrence: Occurrence,
    color: Int,
    onClick: () -> Unit,
    showEventEmojis: Boolean = true,
    timePattern: String = "h:mma",
    modifier: Modifier = Modifier
) {
    val backgroundColor = Color(color)
    // Calculate luminance to determine text color
    val luminance = (0.299f * backgroundColor.red + 0.587f * backgroundColor.green + 0.114f * backgroundColor.blue)
    val textColor = if (luminance > 0.5f) Color.Black else Color.White
    val formattedTitle = EmojiMatcher.formatWithEmoji(event.title, showEventEmojis)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = "$formattedTitle - ${WeekViewUtils.formatTimeRange(occurrence.startTs, occurrence.endTs, timePattern)}",
            style = MaterialTheme.typography.bodySmall,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Badge showing overflow count ("+N more").
 */
@Composable
fun OverflowBadge(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = "+$count more",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// Height thresholds for content visibility
private val HEIGHT_THRESHOLD_TIME = 36.dp
private val HEIGHT_THRESHOLD_LOCATION = 56.dp
private val HEIGHT_THRESHOLD_TWO_LINE_TITLE = 40.dp
