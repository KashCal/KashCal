package org.onekash.kashcal.ui.components.weekview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import org.onekash.kashcal.R
import org.onekash.kashcal.domain.EmojiMatcher
import org.onekash.kashcal.domain.model.DisplayEvent

@Composable
fun EventBlock(
    displayEvent: DisplayEvent,
    height: Dp,
    showEventEmojis: Boolean = true,
    timePattern: String = "h:mma",
    onClick: () -> Unit,
    isDraggable: Boolean = false,
    onDragStart: ((Offset) -> Unit)? = null,
    onDrag: ((Offset) -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null,
    onDragCancel: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val color = displayEvent.calendarColor

    // Solid fill with contrasting text for readability (cached per color)
    val (backgroundColor, textColor) = remember(color) {
        val bg = Color(color)
        val luminance = (0.299f * bg.red + 0.587f * bg.green + 0.114f * bg.blue)
        bg to if (luminance > 0.5f) Color.Black else Color.White
    }

    // Determine what content fits based on height
    val showTime = height >= HEIGHT_THRESHOLD_TIME
    val showLocation = height >= HEIGHT_THRESHOLD_LOCATION && !displayEvent.location.isNullOrBlank()
    val titleMaxLines = if (height >= HEIGHT_THRESHOLD_TWO_LINE_TITLE) 2 else 1

    val gestureModifier = if (isDraggable && onDragStart != null) {
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val longPress = awaitLongPressOrCancellation(down.id)
                if (longPress != null) {
                    onDragStart(longPress.position)
                    var dragged = false
                    drag(longPress.id) { change ->
                        dragged = true
                        change.consume()
                        onDrag?.invoke(change.position - change.previousPosition)
                    }
                    if (dragged) {
                        onDragEnd?.invoke()
                    } else {
                        onDragCancel?.invoke()
                    }
                } else {
                    onClick()
                }
            }
        }
    } else {
        Modifier.pointerInput(Unit) {
            detectTapGestures(
                onDoubleTap = { },
                onTap = { onClick() }
            )
        }
    }

    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(4.dp))
            .background(backgroundColor)
            .then(gestureModifier)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 4.dp)
        ) {
            // Title (always shown)
            Text(
                text = EmojiMatcher.formatWithEmoji(displayEvent.title, showEventEmojis),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = textColor,
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis
            )

            // Time (if height >= 36dp)
            if (showTime) {
                Text(
                    text = WeekViewUtils.formatTimeRange(displayEvent.startTs, displayEvent.endTs, timePattern),
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Location (if height >= 56dp)
            if (showLocation) {
                Text(
                    text = displayEvent.location!!,
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
    displayEvent: DisplayEvent,
    onClick: () -> Unit,
    showEventEmojis: Boolean = true,
    timePattern: String = "h:mma",
    modifier: Modifier = Modifier
) {
    val backgroundColor = Color(displayEvent.calendarColor)
    // Calculate luminance to determine text color
    val luminance = (0.299f * backgroundColor.red + 0.587f * backgroundColor.green + 0.114f * backgroundColor.blue)
    val textColor = if (luminance > 0.5f) Color.Black else Color.White
    val formattedTitle = EmojiMatcher.formatWithEmoji(displayEvent.title, showEventEmojis)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = "$formattedTitle - ${WeekViewUtils.formatTimeRange(displayEvent.startTs, displayEvent.endTs, timePattern)}",
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
            text = stringResource(R.string.status_more_events, count),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// Height thresholds for content visibility
private val HEIGHT_THRESHOLD_TIME = 36.dp
private val HEIGHT_THRESHOLD_LOCATION = 56.dp
private val HEIGHT_THRESHOLD_TWO_LINE_TITLE = 40.dp
