package org.onekash.kashcal.ui.components.pickers

/** The month header's height in the date and time sheet, in dp. */
internal const val SHEET_MONTH_HEADER_DP = 48f

/** The day grid's cell height in the date and time sheet, in dp; six rows are always reserved. */
internal const val SHEET_DAY_CELL_DP = 48f

/** The length chips row's height in the date and time sheet, in dp: a 48dp target. */
internal const val SHEET_CHIPS_DP = 48f

private const val GRID_ROWS = 6
private const val WEEKDAY_ROW_DP = 32f
private const val WEEKDAY_ROW_MIN_DP = 16f
private const val DIVIDER_DP = 9f
private const val DIVIDER_MIN_DP = 1f
private const val WHEEL_ROW_MIN_DP = 32f

/**
 * Sizes the middle of the date and time sheet, between the Starts | Ends switch and the Done bar.
 * All values are in dp.
 *
 * @property wheelRows rows the time wheel shows: 5 or 3, and 0 for an all-day event.
 * @property weekdayRowHeight 32dp, the labels at its bottom, less what the content still needed
 *   after the divider gave its share.
 * @property dividerHeight 9dp (0 for an all-day event), less what the content needed to fit.
 * @property hintLineHeight the line reserved for the all-day end hint, shown or not; 0 when timed.
 */
internal data class DateTimeSheetLayout(
    val wheelRows: Int,
    val rowHeight: Float,
    val weekdayRowHeight: Float,
    val dividerHeight: Float,
    val showChips: Boolean,
    val hintLineHeight: Float,
)

/**
 * Returns the layout for a middle [middleHeight] tall.
 *
 * The middle gets, in order: a 5-row wheel (else 3 rows), then the length chips, then spacing.
 * Rows are [minRowHeight] tall but never under 32dp. The spacing is what shrinks when the content
 * is a little too tall, by only as much as needed: the divider first (9 to 1dp), then the space
 * above the weekday labels (32 to 16dp). So a timed event shows 5 rows when they fit with all of
 * that spacing given up, and its chips when they fit on top of the chosen rows the same way.
 * Content still too tall scrolls; content shorter than the middle is centered by the sheet. An
 * all-day event has no wheel or divider, always shows the chips and reserves a [hintLineHeight]
 * line above them.
 */
internal fun dateTimeSheetLayout(
    middleHeight: Float,
    minRowHeight: Float,
    hintLineHeight: Float,
    isAllDay: Boolean,
): DateTimeSheetLayout {
    val rowHeight = maxOf(WHEEL_ROW_MIN_DP, minRowHeight)
    val divider = if (isAllDay) 0f else DIVIDER_DP
    val hint = if (isAllDay) hintLineHeight else 0f
    val dividerSlack = if (isAllDay) 0f else DIVIDER_DP - DIVIDER_MIN_DP

    fun content(rows: Int, chips: Boolean) =
        SHEET_MONTH_HEADER_DP + WEEKDAY_ROW_DP + GRID_ROWS * SHEET_DAY_CELL_DP + divider +
            rows * rowHeight + hint + if (chips) SHEET_CHIPS_DP else 0f

    val maxTighten = (WEEKDAY_ROW_DP - WEEKDAY_ROW_MIN_DP) + dividerSlack

    val rows: Int
    val chips: Boolean
    if (isAllDay) {
        rows = 0
        chips = true
    } else {
        rows = if (content(5, chips = false) - maxTighten <= middleHeight) 5 else 3
        chips = content(rows, chips = true) - maxTighten <= middleHeight
    }

    val full = content(rows, chips)
    val tightened = minOf((full - middleHeight).coerceAtLeast(0f), maxTighten)
    val dividerCut = minOf(tightened, dividerSlack)
    return DateTimeSheetLayout(
        wheelRows = rows,
        rowHeight = rowHeight,
        weekdayRowHeight = WEEKDAY_ROW_DP - (tightened - dividerCut),
        dividerHeight = divider - dividerCut,
        showChips = chips,
        hintLineHeight = hint,
    )
}
