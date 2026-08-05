package org.onekash.kashcal.ui.components.weekview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.onekash.kashcal.R
import org.onekash.kashcal.domain.EmojiMatcher
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.ui.components.declinedCardAlpha
import org.onekash.kashcal.ui.components.declinedTitleDecoration
import org.onekash.kashcal.ui.components.eventStateDescription
import org.onekash.kashcal.ui.shared.contrastForegroundOn

@Composable
fun EventBlock(
    displayEvent: DisplayEvent,
    height: Dp,
    /**
     * Rendered width. Passed rather than measured: a BoxWithConstraints here
     * would subcompose once per event on every pinch-zoom frame.
     */
    width: Dp,
    showEventEmojis: Boolean = true,
    timePattern: String = "h:mma",
    onClick: () -> Unit,
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

    // Tap only. Drag-to-reschedule is detected by the grid-level overlay
    // (see detectEventDrag) so it can outlive this block's pager page.
    val tapModifier = Modifier.pointerInput(Unit) {
        detectTapGestures(onTap = { onClick() })
    }

    val stateLabel = eventStateDescription(isPast = false, isDeclined = displayEvent.isDeclinedByMe, isCancelled = displayEvent.isCancelled)
    Box(
        modifier = modifier
            .height(height)
            .alpha(declinedCardAlpha(isPast = false, isDeclined = displayEvent.isDeclinedByMe, isCancelled = displayEvent.isCancelled))
            // The tap is wired via pointerInput (below) which, unlike clickable,
            // sets no semantics and no merge boundary — so mergeDescendants is
            // needed for the state label to attach to the block's spoken title.
            .then(
                if (stateLabel != null) {
                    Modifier.semantics(mergeDescendants = true) { stateDescription = stateLabel }
                } else {
                    Modifier
                }
            )
            .clip(RoundedCornerShape(4.dp))
            .then(
                if (isFree) Modifier.border(2.dp, calColor, RoundedCornerShape(4.dp))
                else Modifier
            )
            .background(backgroundColor)
            .then(tapModifier)
    ) {
        // 7-day columns are only ~50dp wide, where even "10:00" clips. Narrow
        // blocks get tighter padding and smaller fonts so more characters fit.
        val narrow = width < NARROW_BLOCK_WIDTH
        val typography = MaterialTheme.typography
        val titleStyle = remember(typography, narrow) {
            typography.bodySmall.let {
                if (narrow) it.copy(fontSize = 11.sp, lineHeight = 13.sp) else it
            }
        }
        val detailStyle = remember(typography, narrow) {
            typography.labelSmall.let {
                if (narrow) it.copy(fontSize = 9.sp, lineHeight = 11.sp) else it
            }
        }

        val showTime = height >= HEIGHT_THRESHOLD_TIME
        val showLocation = height >= HEIGHT_THRESHOLD_LOCATION && !displayEvent.location.isNullOrBlank()
        val showTags = height >= HEIGHT_THRESHOLD_LOCATION && displayEvent.categories.isNotEmpty()
        val titleMaxLines = if (height >= HEIGHT_THRESHOLD_TWO_LINE_TITLE) 2 else 1

        // A 30-min event at full zoom-out floors to 20dp, where the usual 4dp of
        // top and bottom padding leaves too little for one ~16dp title line.
        val verticalPadding = if (height < COMPACT_BLOCK_HEIGHT) 1.dp else BLOCK_VERTICAL_PADDING

        Column(
            modifier = Modifier
                .fillMaxSize()
                // Start runs 2dp wider than end: the text is left-aligned, so an
                // even inset reads as left-heavy.
                .padding(
                    start = if (narrow) 5.dp else 8.dp,
                    end = if (narrow) 3.dp else 6.dp,
                    top = verticalPadding,
                    bottom = verticalPadding
                )
        ) {
            Text(
                text = EmojiMatcher.formatWithEmoji(displayEvent.title, showEventEmojis),
                style = titleStyle,
                fontWeight = FontWeight.Medium,
                color = textColor,
                textDecoration = declinedTitleDecoration(displayEvent.isDeclinedByMe, displayEvent.isCancelled),
                maxLines = titleMaxLines,
                // Clipped, not ellipsized: "…" costs characters a tiny block
                // can't spare.
                overflow = TextOverflow.Clip
            )

            // All-day events keep a label even though they normally render in
            // the all-day strip, in case a future path puts one in the grid.
            if (showTime) {
                // "10:00 - 22:30" doesn't fit a narrow column; show the start alone.
                val timeLabel = if (narrow) {
                    eventStartLabel(displayEvent, timePattern)
                } else {
                    eventTimeLabel(displayEvent, timePattern)
                }
                Text(
                    text = timeLabel,
                    style = detailStyle,
                    color = textColor.copy(alpha = 0.7f),
                    textDecoration = declinedTitleDecoration(displayEvent.isDeclinedByMe, displayEvent.isCancelled),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip
                )
            }

            if (showLocation) {
                Text(
                    text = displayEvent.location!!,
                    style = detailStyle,
                    color = textColor.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Clip
                )
            }

            if (showTags) {
                org.onekash.kashcal.ui.components.category.CategoryPillRow(
                    categories = displayEvent.categories,
                    maxVisible = 2,
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
    val timeLabel = eventTimeLabel(displayEvent, timePattern)

    val compactStateLabel = eventStateDescription(isPast = false, isDeclined = displayEvent.isDeclinedByMe, isCancelled = displayEvent.isCancelled)
    Box(
        modifier = modifier
            .alpha(declinedCardAlpha(isPast = false, isDeclined = displayEvent.isDeclinedByMe, isCancelled = displayEvent.isCancelled))
            .then(if (compactStateLabel != null) Modifier.semantics { stateDescription = compactStateLabel } else Modifier)
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
            text = "$formattedTitle - $timeLabel",
            style = MaterialTheme.typography.bodySmall,
            color = textColor,
            textDecoration = declinedTitleDecoration(displayEvent.isDeclinedByMe, displayEvent.isCancelled),
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

/**
 * Resolves the time-line label for an event row: the localized "All day"
 * string for all-day events, otherwise the formatted time range. Single
 * source of truth so any future change to either branch reaches every
 * surface that renders an event row.
 */
@Composable
private fun eventTimeLabel(displayEvent: DisplayEvent, timePattern: String): String =
    if (displayEvent.isAllDay) {
        stringResource(R.string.label_all_day)
    } else {
        // Remembered: formatting parses a DateTimeFormatter pattern, once per
        // visible block per recomposition.
        remember(displayEvent.startTs, displayEvent.endTs, timePattern) {
            WeekViewUtils.formatTimeRange(displayEvent.startTs, displayEvent.endTs, timePattern)
        }
    }

/** Narrow-column fallback for [eventTimeLabel]: start time only. */
@Composable
private fun eventStartLabel(displayEvent: DisplayEvent, timePattern: String): String =
    if (displayEvent.isAllDay) {
        stringResource(R.string.label_all_day)
    } else {
        remember(displayEvent.startTs, timePattern) {
            WeekViewUtils.formatTime(displayEvent.startTs, timePattern)
        }
    }

private val BLOCK_VERTICAL_PADDING = 4.dp

// Roughly a 7-day column; below this, blocks switch to the compact treatment.
private val NARROW_BLOCK_WIDTH = 72.dp

// Blocks this short can't spare the usual vertical padding.
private val COMPACT_BLOCK_HEIGHT = 28.dp

// Height thresholds for content visibility
private val HEIGHT_THRESHOLD_TIME = 36.dp
private val HEIGHT_THRESHOLD_LOCATION = 56.dp
private val HEIGHT_THRESHOLD_TWO_LINE_TITLE = 40.dp
