package org.onekash.kashcal.ui.components.weekview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.domain.EmojiMatcher
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.ui.components.declinedCardAlpha
import org.onekash.kashcal.ui.components.declinedTitleDecoration
import org.onekash.kashcal.ui.shared.contrastForegroundOn

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
    val color = displayEvent.eventColor ?: displayEvent.calendarColor
    val isFree = displayEvent.isFree
    val calColor = Color(color)
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface

    val (backgroundColor, textColor) = remember(color, isFree, surfaceColor) {
        if (isFree) {
            val bg = lerp(surfaceColor, calColor, 0.15f)
            bg to onSurfaceColor
        } else {
            calColor to contrastForegroundOn(calColor)
        }
    }

    // Determine what content fits based on height
    val showTime = height >= HEIGHT_THRESHOLD_TIME
    val showLocation = height >= HEIGHT_THRESHOLD_LOCATION && !displayEvent.location.isNullOrBlank()
    val titleMaxLines = if (height >= HEIGHT_THRESHOLD_TWO_LINE_TITLE) 2 else 1

    // Tap and long-press-drag live in separate pointerInput modifiers so a parent
    // scrollable (HorizontalPager / verticalScroll) can cancel the tap without racing
    // the drag detector.
    val tapModifier = Modifier.pointerInput(Unit) {
        detectTapGestures(onTap = { onClick() })
    }
    val dragStart = onDragStart
    val gestureModifier = if (isDraggable && dragStart != null) {
        tapModifier.pointerInput(Unit) {
            detectDragGesturesAfterLongPress(
                onDragStart = dragStart,
                onDrag = { _, dragAmount -> onDrag?.invoke(dragAmount) },
                onDragEnd = { onDragEnd?.invoke() },
                onDragCancel = { onDragCancel?.invoke() }
            )
        }
    } else {
        tapModifier
    }

    Box(
        modifier = modifier
            .height(height)
            .alpha(declinedCardAlpha(isPast = false, isDeclined = displayEvent.isDeclinedByMe))
            .clip(RoundedCornerShape(4.dp))
            .then(
                if (isFree) Modifier.border(2.dp, calColor, RoundedCornerShape(4.dp))
                else Modifier
            )
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
                textDecoration = declinedTitleDecoration(displayEvent.isDeclinedByMe),
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
    val isFree = displayEvent.isFree
    val color = displayEvent.eventColor ?: displayEvent.calendarColor
    val calColor = Color(color)
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val (backgroundColor, textColor) = remember(color, isFree, surfaceColor) {
        if (isFree) {
            lerp(surfaceColor, calColor, 0.15f) to onSurfaceColor
        } else {
            calColor to contrastForegroundOn(calColor)
        }
    }
    val formattedTitle = EmojiMatcher.formatWithEmoji(displayEvent.title, showEventEmojis)

    Box(
        modifier = modifier
            .alpha(declinedCardAlpha(isPast = false, isDeclined = displayEvent.isDeclinedByMe))
            .clip(RoundedCornerShape(4.dp))
            .then(
                if (isFree) Modifier.border(2.dp, calColor, RoundedCornerShape(4.dp))
                else Modifier
            )
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = "$formattedTitle - ${WeekViewUtils.formatTimeRange(displayEvent.startTs, displayEvent.endTs, timePattern)}",
            style = MaterialTheme.typography.bodySmall,
            color = textColor,
            textDecoration = declinedTitleDecoration(displayEvent.isDeclinedByMe),
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
