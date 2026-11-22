package org.onekash.kashcal.ui.components.weekview

import java.time.LocalDate
import kotlin.math.abs

/**
 * Names the axis a two-finger pinch on the time grid locks to once past the slop ([pinchAxis]):
 * [VERTICAL] zooms the hours; [SIDEWAYS] switches Week, 3-day and Day one step on release
 * ([nextVisibleDays]), stretching the columns meanwhile ([previewScaleX]).
 */
internal enum class PinchAxis { VERTICAL, SIDEWAYS }

/** The spread, over the starting finger distance, at or past which the grid shows fewer days. */
internal const val PINCH_FEWER_DAYS_RATIO = 1.3f

/** Spread at or below which the grid shows more days. */
internal const val PINCH_MORE_DAYS_RATIO = 0.75f

/** How much of the spread the preview stretch follows. */
internal const val PINCH_PREVIEW_FACTOR = 0.35f

/** The preview's largest stretch or squeeze toward a view that exists. */
internal const val PINCH_PREVIEW_CAP = 0.10f

/** The preview's largest stretch or squeeze past the chain's end, where no view follows. */
internal const val PINCH_PREVIEW_END_CAP = 0.05f

/**
 * Returns the axis a pinch takes from the finger distances along each axis at the start and
 * now, or null while neither has changed by more than [slopPx]. The larger change wins; a tie
 * goes to [PinchAxis.VERTICAL], the hour zoom.
 */
internal fun pinchAxis(startSpanX: Float, startSpanY: Float, spanX: Float, spanY: Float, slopPx: Float): PinchAxis? {
    val changeX = abs(spanX - startSpanX)
    val changeY = abs(spanY - startSpanY)
    if (changeX <= slopPx && changeY <= slopPx) return null
    return if (changeX > changeY) PinchAxis.SIDEWAYS else PinchAxis.VERTICAL
}

/**
 * Returns [span] over [startSpan], the start taken as at least [minSpanPx], so fingers that start
 * almost together can't give a runaway ratio, while a pinch in from close still gets small.
 */
internal fun spreadRatio(startSpan: Float, span: Float, minSpanPx: Float): Float =
    span / startSpan.coerceAtLeast(minSpanPx)

/**
 * Returns the visible days a sideways pinch released at [spreadRatio] switches to: Week to 3-day
 * to Day when spread to [PINCH_FEWER_DAYS_RATIO], back when pinched to [PINCH_MORE_DAYS_RATIO];
 * null short of those and past the chain's ends.
 */
internal fun nextVisibleDays(visibleDays: Int, spreadRatio: Float): Int? = when {
    spreadRatio >= PINCH_FEWER_DAYS_RATIO -> when (visibleDays) {
        7 -> 3
        3 -> 1
        else -> null
    }
    spreadRatio <= PINCH_MORE_DAYS_RATIO -> when (visibleDays) {
        1 -> 3
        3 -> 7
        else -> null
    }
    else -> null
}

/**
 * Returns the horizontal scale the day columns draw at while a sideways pinch is at
 * [spreadRatio]: a part of the spread ([PINCH_PREVIEW_FACTOR]), capped at [PINCH_PREVIEW_CAP],
 * or [PINCH_PREVIEW_END_CAP] when no view lies that way.
 */
internal fun previewScaleX(visibleDays: Int, spreadRatio: Float): Float {
    val wanted = (spreadRatio - 1f) * PINCH_PREVIEW_FACTOR
    val towardFewer = wanted > 0f
    val atEnd = if (towardFewer) visibleDays == 1 else visibleDays == 7
    val cap = if (atEnd) PINCH_PREVIEW_END_CAP else PINCH_PREVIEW_CAP
    return 1f + wanted.coerceIn(-cap, cap)
}

/**
 * Returns the date of the column under [centroidX], measured from the columns' left edge across
 * [columnsWidth], clamped to the first and last column. [visibleDates] run from the start edge,
 * so in a right-to-left layout the first date is the rightmost column.
 */
internal fun pinchAnchorDate(visibleDates: List<LocalDate>, centroidX: Float, columnsWidth: Float, isRtl: Boolean): LocalDate {
    val columns = visibleDates.size
    val fromLeft = (centroidX / columnsWidth * columns).toInt().coerceIn(0, columns - 1)
    return visibleDates[if (isRtl) columns - 1 - fromLeft else fromLeft]
}
