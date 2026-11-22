package org.onekash.kashcal.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.components.kash.KASH_ASPECT_RATIO
import org.onekash.kashcal.ui.components.kash.KashArt
import org.onekash.kashcal.ui.components.kash.KashPose

/** One empty-day line and the Kash pose drawn with it. */
internal data class EmptyDayLine(@StringRes val phrase: Int, val pose: KashPose)

private val EMPTY_DAY_LINES = listOf(
    EmptyDayLine(R.string.empty_no_events_day_1, KashPose.ASLEEP),
    EmptyDayLine(R.string.empty_no_events_day_2, KashPose.WINK),
    EmptyDayLine(R.string.empty_no_events_day_3, KashPose.GIFT),
    EmptyDayLine(R.string.empty_no_events_day_4, KashPose.CHEER),
    EmptyDayLine(R.string.empty_no_events_day_5, KashPose.MUG),
)

/**
 * Returns the line and Kash pose for an empty day on [epochDay]. Keyed on the epoch day, a date
 * always shows the same pair, neighboring dates differ, and the cycle runs on across month and
 * year boundaries; the pose always follows the line.
 */
internal fun emptyDayLine(epochDay: Long): EmptyDayLine =
    EMPTY_DAY_LINES[Math.floorMod(epochDay, EMPTY_DAY_LINES.size.toLong()).toInt()]

private val KASH_MAX_WIDTH = 120.dp
private val KASH_MIN_WIDTH = 48.dp
private val KASH_GAP = 16.dp

/**
 * Returns Kash's width for [available], the height left in the panel once the line and the gap
 * above it are placed, and [room], the panel's width: as wide as that height allows for art
 * [KASH_ASPECT_RATIO] in shape, at most [KASH_MAX_WIDTH] and [room], or null when that is under
 * [KASH_MIN_WIDTH], so Kash is left out rather than squeezing the line.
 */
internal fun emptyDayKashSize(available: Dp, room: Dp = Dp.Infinity): Dp? {
    val width = min(min(KASH_MAX_WIDTH, room), available * KASH_ASPECT_RATIO)
    return if (width < KASH_MIN_WIDTH) null else width
}

/** Test tag on Kash in [EmptyDayState]. */
internal const val EMPTY_DAY_KASH_TAG = "empty_day_kash"

/**
 * Shows the empty day for [epochDay]: the day's line, with Kash above it when there is room. The
 * line is measured first and Kash takes what height is left ([emptyDayKashSize]), so Kash never
 * pushes the line out of view. Only the line is read aloud ([KashArt] is decorative).
 *
 * Material3 pull-to-refresh only sees drags a descendant passes up through nested scroll, and
 * nothing here scrolls. A vertical scrollable that consumes nothing hands the drag on, so a pull
 * on an empty day refreshes (`EmptyDayPullToRefreshTest`) while the panel keeps the bounded height
 * Kash is sized from.
 */
@Composable
internal fun EmptyDayState(epochDay: Long, modifier: Modifier = Modifier) {
    val line = emptyDayLine(epochDay)
    val text = stringResource(line.phrase)
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val scrollDonor = rememberScrollableState { 0f }
    SubcomposeLayout(
        modifier
            .fillMaxSize()
            .scrollable(orientation = Orientation.Vertical, state = scrollDonor)
            .padding(32.dp)
    ) { constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val textPlaceable = subcompose("line") {
            Text(text, color = color, textAlign = TextAlign.Center)
        }.first().measure(loose)
        val gap = KASH_GAP.roundToPx()
        val height = if (constraints.hasBoundedHeight) {
            (constraints.maxHeight - textPlaceable.height - gap).coerceAtLeast(0).toDp()
        } else {
            Dp.Infinity
        }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth.toDp() else Dp.Infinity
        val kashPlaceable = emptyDayKashSize(height, width)?.let { kashWidth ->
            val w = kashWidth.roundToPx()
            subcompose("kash") {
                KashArt(line.pose, Modifier.testTag(EMPTY_DAY_KASH_TAG))
            }.first().measure(Constraints.fixed(w, (w / KASH_ASPECT_RATIO).toInt()))
        }
        val blockHeight = textPlaceable.height + (kashPlaceable?.let { it.height + gap } ?: 0)
        val panelWidth = if (constraints.hasBoundedWidth) {
            constraints.maxWidth
        } else {
            maxOf(textPlaceable.width, kashPlaceable?.width ?: 0)
        }
        val panelHeight = if (constraints.hasBoundedHeight) constraints.maxHeight else blockHeight
        layout(panelWidth, panelHeight) {
            var y = (panelHeight - blockHeight) / 2
            kashPlaceable?.let {
                it.placeRelative((panelWidth - it.width) / 2, y)
                y += it.height + gap
            }
            textPlaceable.placeRelative((panelWidth - textPlaceable.width) / 2, y)
        }
    }
}
