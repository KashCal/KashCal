package org.onekash.kashcal.ui.components.weekview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import kotlin.math.ceil
import org.onekash.kashcal.domain.EmojiMatcher
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.ui.components.declinedCardAlpha
import org.onekash.kashcal.ui.components.declinedTitleDecoration
import org.onekash.kashcal.ui.components.eventStateDescription
import org.onekash.kashcal.ui.shared.contrastForegroundOn

/**
 * Draws a timed event in a time-grid column, sized by [height].
 *
 * The block's lines go to the title first, wrapped between words (a word too wide for a line is
 * split) and cut at the last line with no ellipsis, then to the location, then to the time only
 * when its whole range fits on one line, then to tags at [HEIGHT_THRESHOLD_TAGS] and taller
 * ([allocateEventBlockLines]), all within [textHeight] from the block's top, which a cascade
 * sets above the event drawn over this one. The title starts at the top edge, with no vertical
 * padding. [compact] is the 7-day style: labelSmall titles and 3dp side padding. [outlined] rings
 * a busy block in the surface colour, so an event drawn over another of the same colour stays
 * distinct; a free block keeps its own border instead. A past, declined or cancelled block fades
 * halfway toward the surface but stays opaque, so an event under it doesn't show through. The
 * block is one screen-reader item whose click action calls [onClick].
 */
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
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    outlined: Boolean = false,
    textMeasurer: TextMeasurer = rememberTextMeasurer(),
    textHeight: Dp = height,
    isPast: Boolean = false,
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

    val formattedTitle = remember(displayEvent.title, showEventEmojis) {
        EmojiMatcher.formatWithEmoji(displayEvent.title, showEventEmojis, separator = NO_BREAK_SPACE)
    }
    val location = displayEvent.location?.takeIf { it.isNotBlank() }
    val timeText = eventTimeLabel(displayEvent, timePattern)
    val titleStyle = eventBlockTitleStyle(compact)
    val detailStyle = MaterialTheme.typography.labelSmall.copy(lineBreak = LineBreak.Simple, hyphens = Hyphens.None)
    val paddingX = if (compact) COMPACT_PADDING_X else REGULAR_PADDING_X

    // Tap and long-press-drag live in separate pointerInput modifiers so a parent
    // scrollable (HorizontalPager / verticalScroll) can cancel the tap without racing
    // the drag detector.
    // The detectors outlive recompositions (pointerInput(Unit)), so they call the callbacks
    // through State: a block that has moved within its column hands on its current position.
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnDragCancel by rememberUpdatedState(onDragCancel)
    val tapModifier = Modifier.pointerInput(Unit) {
        detectTapGestures(onTap = { currentOnClick() })
    }
    val gestureModifier = if (isDraggable && onDragStart != null) {
        tapModifier.pointerInput(Unit) {
            detectDragGesturesAfterLongPress(
                onDragStart = { offset -> currentOnDragStart?.invoke(offset) },
                onDrag = { _, dragAmount -> currentOnDrag?.invoke(dragAmount) },
                onDragEnd = { currentOnDragEnd?.invoke() },
                onDragCancel = { currentOnDragCancel?.invoke() }
            )
        }
    } else {
        tapModifier
    }

    val stateLabel = eventStateDescription(isPast = isPast, isDeclined = displayEvent.isDeclinedByMe, isCancelled = displayEvent.isCancelled)
    val fadeAlpha = declinedCardAlpha(isPast = isPast, isDeclined = displayEvent.isDeclinedByMe, isCancelled = displayEvent.isCancelled)
    val shape = RoundedCornerShape(4.dp)
    BoxWithConstraints(
        modifier = modifier
            .height(height)
            // A faded block draws over a surface backing, which sits before the alpha layer and
            // so stays opaque: the block looks the same on a plain background as a plain alpha
            // fade, and an event under it doesn't show through.
            .then(if (fadeAlpha < 1f) Modifier.background(surfaceColor, shape) else Modifier)
            .alpha(fadeAlpha)
            // The tap is a pointerInput (below), which sets no semantics, so the block merges its
            // texts into one item and gives it the click action a screen reader uses. Without the
            // action, TalkBack taps the block's centre, which an event drawn on top can cover.
            .semantics(mergeDescendants = true) {
                if (stateLabel != null) stateDescription = stateLabel
                onClick { onClick(); true }
            }
            .clip(shape)
            // A free event's own 2dp border already sets it apart; an outline would cover half
            // of it.
            .then(if (outlined && !isFree) Modifier.border(1.dp, surfaceColor, shape) else Modifier)
            .then(if (isFree) Modifier.border(2.dp, calColor, shape) else Modifier)
            .background(backgroundColor)
            .then(gestureModifier)
    ) {
        val density = LocalDensity.current
        // From the rounded padding, as the Column below applies it on each side, so the plan
        // matches the width the texts get at every density.
        val textWidthPx = (constraints.maxWidth - 2 * with(density) { paddingX.roundToPx() }).coerceAtLeast(1)
        val spacePx = with(density) { textHeight.roundToPx() }
        val tagsAllowed = textHeight >= HEIGHT_THRESHOLD_TAGS && displayEvent.categories.isNotEmpty()
        // Measured once per text and width, not per height: an hour-zoom pinch changes every
        // block's height each frame and only the line share below follows it.
        val measured = remember(formattedTitle, location, timeText, textWidthPx, titleStyle, detailStyle, density) {
            val widthLimit = Constraints(maxWidth = textWidthPx)
            val title = textMeasurer.measure(formattedTitle, titleStyle, constraints = widthLimit)
            val time = textMeasurer.measure(timeText, detailStyle, softWrap = false, maxLines = 1)
            // Line heights from the measured layouts, as the texts draw them: converting the
            // style's sp with the density applies nonlinear font scaling, which text layout
            // doesn't, so at a large font size the two disagree by several pixels a line.
            MeasuredBlockText(
                titleLineHeightPx = ceil(title.getLineBottom(0) - title.getLineTop(0)).toInt(),
                titleLinesNeeded = title.lineCount,
                detailLineHeightPx = time.size.height,
                locationLinesNeeded = location?.let { textMeasurer.measure(it, detailStyle, constraints = widthLimit).lineCount } ?: 0,
                timeFitsWidth = time.size.width <= textWidthPx,
            )
        }
        val lines = remember(measured, spacePx, tagsAllowed) {
            allocateEventBlockLines(
                spacePx = spacePx,
                titleLineHeightPx = measured.titleLineHeightPx,
                titleLinesNeeded = measured.titleLinesNeeded,
                detailLineHeightPx = measured.detailLineHeightPx,
                locationLinesNeeded = measured.locationLinesNeeded,
                timeFitsWidth = measured.timeFitsWidth,
                tagsAllowed = tagsAllowed,
            )
        }
        val decoration = declinedTitleDecoration(displayEvent.isDeclinedByMe, displayEvent.isCancelled)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = paddingX)
        ) {
            Text(
                text = formattedTitle,
                style = titleStyle,
                color = textColor,
                textDecoration = decoration,
                maxLines = lines.titleLines,
                overflow = TextOverflow.Clip,
            )
            if (location != null && lines.locationLines > 0) {
                Text(
                    text = location,
                    style = detailStyle,
                    color = textColor.copy(alpha = 0.6f),
                    maxLines = lines.locationLines,
                    overflow = TextOverflow.Clip,
                )
            }
            // All-day events render in the all-day strip, but one placed in the grid still gets
            // the "All day" label here, under the same fit rule as a time range.
            if (lines.showTime) {
                Text(
                    text = timeText,
                    style = detailStyle,
                    color = textColor.copy(alpha = 0.7f),
                    textDecoration = decoration,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            if (lines.showTags) {
                org.onekash.kashcal.ui.components.category.CategoryPillRow(
                    categories = displayEvent.categories,
                    maxVisible = 2,
                )
            }
        }
    }
}

/**
 * Returns the height of an [EventBlock]'s first title line, measured as the title draws, for the
 * cascade rule that a covered event keeps that much above the event on top of it.
 */
@Composable
internal fun eventBlockFirstLineHeight(compact: Boolean, textMeasurer: TextMeasurer): Dp {
    val style = eventBlockTitleStyle(compact)
    val density = LocalDensity.current
    return remember(style, density) {
        val line = textMeasurer.measure("Ag", style, maxLines = 1).size.height
        with(density) { line.toDp() }
    }
}

/** Returns the block's title style: labelSmall when [compact] (the 7-day view), else bodySmall. */
@Composable
private fun eventBlockTitleStyle(compact: Boolean) =
    (if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall)
        .copy(fontWeight = FontWeight.Medium, lineBreak = LineBreak.Simple, hyphens = Hyphens.None)

/** What an event block's texts need, measured at its width ([allocateEventBlockLines]'s input). */
private data class MeasuredBlockText(
    val titleLineHeightPx: Int,
    val titleLinesNeeded: Int,
    val detailLineHeightPx: Int,
    val locationLinesNeeded: Int,
    val timeFitsWidth: Boolean,
)

/** How an event block's lines are shared out ([allocateEventBlockLines]). */
internal data class EventBlockLines(
    val titleLines: Int,
    val locationLines: Int,
    val showTime: Boolean,
    val showTags: Boolean,
)

/**
 * Shares [spacePx] of block height out in order: the title takes the lines it needs, at least
 * one even when the block is shorter than a line (the block's clip cuts it); the location takes
 * what fits of the rest; the time shows when a line is still left and [timeFitsWidth]; tags show
 * when [tagsAllowed] and a line is left after that.
 */
internal fun allocateEventBlockLines(
    spacePx: Int,
    titleLineHeightPx: Int,
    titleLinesNeeded: Int,
    detailLineHeightPx: Int,
    locationLinesNeeded: Int,
    timeFitsWidth: Boolean,
    tagsAllowed: Boolean,
): EventBlockLines {
    val titleLines = minOf(titleLinesNeeded.coerceAtLeast(1), (spacePx / titleLineHeightPx.coerceAtLeast(1)).coerceAtLeast(1))
    var left = spacePx - titleLines * titleLineHeightPx
    val detailLine = detailLineHeightPx.coerceAtLeast(1)
    val locationLines = minOf(locationLinesNeeded, (left / detailLine).coerceAtLeast(0))
    left -= locationLines * detailLine
    val showTime = timeFitsWidth && left >= detailLine
    if (showTime) left -= detailLine
    return EventBlockLines(titleLines, locationLines, showTime, showTags = tagsAllowed && left >= detailLine)
}

/**
 * Shows an event as one line, title and time, in the [OverlapListSheet] list.
 *
 * A busy event has a solid calendar-color fill with contrasting text; a free one has a light
 * tint, a calendar-color border and on-surface text. A past, declined or cancelled one fades.
 *
 * @param timePattern DateTimeFormatter pattern for the time range, for example "h:mma" for 12h
 *   or "HH:mm" for 24h
 */
@Composable
fun CompactEventBlock(
    displayEvent: DisplayEvent,
    onClick: () -> Unit,
    showEventEmojis: Boolean = true,
    timePattern: String = "h:mma",
    modifier: Modifier = Modifier,
    isPast: Boolean = false,
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
    val formattedTitle = remember(displayEvent.title, showEventEmojis) {
        EmojiMatcher.formatWithEmoji(displayEvent.title, showEventEmojis)
    }
    val timeLabel = eventTimeLabel(displayEvent, timePattern)

    val compactStateLabel = eventStateDescription(isPast = isPast, isDeclined = displayEvent.isDeclinedByMe, isCancelled = displayEvent.isCancelled)
    Box(
        modifier = modifier
            .alpha(declinedCardAlpha(isPast = isPast, isDeclined = displayEvent.isDeclinedByMe, isCancelled = displayEvent.isCancelled))
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
 * Shows the "+N more" count for a time-grid overlap group, or with [compact] just "+N" (the 7-day
 * view, whose columns are narrower than "+N more"); screen readers hear "+N more" either way.
 */
@Composable
fun OverflowBadge(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val moreLabel = stringResource(R.string.status_more_events, count)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .then(if (compact) Modifier.semantics { contentDescription = moreLabel } else Modifier)
            .padding(horizontal = if (compact) 3.dp else 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = if (compact) stringResource(R.string.status_more_events_compact, count) else moreLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = if (compact) Modifier.clearAndSetSemantics {} else Modifier,
        )
    }
}

/**
 * Returns the time line of an event row: the localized "All day" string for all-day events,
 * otherwise the formatted time range. Shared by [EventBlock] and [CompactEventBlock] so both
 * change together.
 */
@Composable
private fun eventTimeLabel(displayEvent: DisplayEvent, timePattern: String): String =
    if (displayEvent.isAllDay) {
        stringResource(R.string.label_all_day)
    } else {
        WeekViewUtils.formatTimeRange(displayEvent.startTs, displayEvent.endTs, timePattern)
    }

/** Minimum text height for tag pills, besides the line they need. */
private val HEIGHT_THRESHOLD_TAGS = 56.dp

// Side padding; the 7-day view's 3dp clears a free event's 2dp border by 1dp. Blocks have no
// vertical padding, so a short event's first line fits; on a free event the line's tallest
// glyphs can reach about 1dp into the top border.
private val COMPACT_PADDING_X = 3.dp
private val REGULAR_PADDING_X = 6.dp

/** Joins an emoji to the title's first word, so the emoji never sits alone on a line. */
private const val NO_BREAK_SPACE = "\u00A0"
