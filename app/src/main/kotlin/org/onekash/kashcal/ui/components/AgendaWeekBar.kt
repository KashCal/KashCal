package org.onekash.kashcal.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.components.pickers.DayCellStyle
import org.onekash.kashcal.ui.components.pickers.dayCellStyle
import org.onekash.kashcal.ui.components.weekview.WeekViewUtils
import org.onekash.kashcal.ui.components.weekview.rememberWeekTimeColumnWidth
import org.onekash.kashcal.ui.util.DayPagerUtils
import java.time.LocalDate

/**
 * Shows a pinned week strip, the Agenda's and the Day view's ([DayViewWeekStrip]): a row of
 * [WeekStripLetter]s above a row of that week's [WeekStripDayCircle]s, the same pieces as the
 * Week view's day headers. Column order follows [weekDates], which the caller builds with
 * [AgendaWeekBarLogic.weekDates] from the first-day-of-week setting. The selected day is the
 * day you're on and wins over today ([dayCellStyle]). [horizontalPadding] is the Agenda's 8dp by
 * default; the Day view's strip has none, to sit over the Week view's headers.
 *
 * @param selectedDayCode currently selected day (YYYYMMDD), or null when nothing
 *   in the shown week is selected
 * @param onDayClick invoked with the tapped day's YYYYMMDD code
 */
@Composable
internal fun AgendaWeekBar(
    weekDates: List<LocalDate>,
    selectedDayCode: Int?,
    todayDayCode: Int,
    onDayClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 8.dp,
) {
    Column(modifier = modifier.padding(horizontal = horizontalPadding, vertical = WEEK_STRIP_VERTICAL_PADDING)) {
        // Derived from each shown date so the letters line up with the numbers below them in
        // any order.
        Row(modifier = Modifier) {
            weekDates.forEach { date ->
                WeekStripLetter(date = date, modifier = Modifier.weight(1f))
            }
        }
        // Date numbers. Fades and slides when the shown week changes, so a scroll
        // into a new week reads as a move, not a hard cut. Later weeks slide in
        // from the right, earlier ones from the left.
        AnimatedContent(
            targetState = weekDates,
            transitionSpec = {
                val forward = targetState.first() >= initialState.first()
                val dir = if (forward) 1 else -1
                (slideInHorizontally { w -> dir * w } + fadeIn()) togetherWith
                    (slideOutHorizontally { w -> -dir * w } + fadeOut())
            },
            label = "agendaWeekBarWeek"
        ) { dates ->
            Row(modifier = Modifier.padding(top = WEEK_STRIP_LETTER_GAP)) {
                dates.forEach { date ->
                    val dayCode = DayPagerUtils.localDateToDayCode(date)
                    DateCell(
                        date = date,
                        isToday = dayCode == todayDayCode,
                        isSelected = dayCode == selectedDayCode,
                        onClick = { onDayClick(dayCode) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun DateCell(
    date: LocalDate,
    isToday: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val style = dayCellStyle(isToday = isToday, isSelected = isSelected)

    // The bare day number means nothing to a screen reader, so the cell carries
    // the full date and state and labels the tap action ("Go to date"). Merged
    // semantics keep the clickable's action while overriding the spoken text, so
    // TalkBack reads e.g. "Saturday, July 18, Today" instead of "18".
    val cellDescription = AgendaWeekBarLogic.cellContentDescription(
        date = date,
        isToday = isToday,
        isSelected = isSelected,
        todayLabel = stringResource(R.string.label_today),
        selectedLabel = stringResource(R.string.cd_selected)
    )
    val goToDateLabel = stringResource(R.string.label_go_to_date)

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        WeekStripDayCircle(
            date = date,
            style = style,
            actions = Modifier
                .clickable(onClickLabel = goToDateLabel, onClick = onClick)
                .semantics(mergeDescendants = true) {
                    contentDescription = cellDescription
                    selected = isSelected
                },
        )
    }
}

/** Shows the weekday letter above a week strip's or the Week header's day number. */
@Composable
internal fun WeekStripLetter(date: LocalDate, modifier: Modifier = Modifier) {
    val letter = remember(date) { AgendaWeekBarLogic.narrowWeekdayLetter(date.dayOfWeek) }
    Text(
        text = letter,
        style = MaterialTheme.typography.labelSmall,
        color = if (WeekViewUtils.isWeekend(date)) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}

/**
 * Draws [date]'s day number in its circle, filled by [style] ([dayCellStyle]): the high-contrast
 * inverseSurface for the day you're on, a tonal fill with an outline ring for today, none
 * otherwise. The circle is [WEEK_STRIP_CIRCLE_SIZE], or at large font sizes the number plus
 * [WEEK_STRIP_CIRCLE_PADDING] a side, never wider than its cell. [actions] (a click, semantics)
 * apply inside the circle's clip, so a ripple stays round.
 */
@Composable
internal fun WeekStripDayCircle(
    date: LocalDate,
    style: DayCellStyle,
    modifier: Modifier = Modifier,
    actions: Modifier = Modifier,
) {
    val fill = when (style) {
        DayCellStyle.SELECTED -> MaterialTheme.colorScheme.inverseSurface
        DayCellStyle.TODAY -> MaterialTheme.colorScheme.primaryContainer
        DayCellStyle.PLAIN -> null
    }
    val numberColor = when (style) {
        DayCellStyle.SELECTED -> MaterialTheme.colorScheme.inverseOnSurface
        DayCellStyle.TODAY -> MaterialTheme.colorScheme.onPrimaryContainer
        DayCellStyle.PLAIN -> if (WeekViewUtils.isWeekend(date)) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    }
    Box(
        modifier = modifier
            .circleAroundNumber()
            .clip(CircleShape)
            .then(if (fill != null) Modifier.background(fill, CircleShape) else Modifier)
            // Today's tonal fill can wash out against the surface for pale accent seeds, so a
            // hairline outline keeps the ring visible on any theme. The high-contrast
            // inverseSurface fill of the selected cell needs none.
            .then(
                if (style == DayCellStyle.TODAY) {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                } else {
                    Modifier
                }
            )
            .then(actions),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = date.dayOfMonth.toString(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (style != DayCellStyle.PLAIN) FontWeight.Bold else FontWeight.Normal,
            color = numberColor,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Sizes the circle as a square: the larger of the number's width and line height plus the
 * padding on each side, at least [WEEK_STRIP_CIRCLE_SIZE], at most the width it is given.
 */
private fun Modifier.circleAroundNumber(): Modifier = layout { measurable, constraints ->
    val numberHeight = measurable.maxIntrinsicHeight(Constraints.Infinity)
    val numberWidth = measurable.maxIntrinsicWidth(numberHeight)
    val wanted = maxOf(WEEK_STRIP_CIRCLE_SIZE.roundToPx(), maxOf(numberWidth, numberHeight) + 2 * WEEK_STRIP_CIRCLE_PADDING.roundToPx())
    val side = if (constraints.hasBoundedWidth) minOf(wanted, constraints.maxWidth) else wanted
    val placeable = measurable.measure(Constraints.fixed(side, side))
    layout(side, side) { placeable.place(0, 0) }
}

/** The day number circle's size at the default font. */
private val WEEK_STRIP_CIRCLE_SIZE = 34.dp

/** The day number circle's room around the number once large text outgrows the default size. */
private val WEEK_STRIP_CIRCLE_PADDING = 4.dp

/** Space between a week strip's letters and its day numbers. */
internal val WEEK_STRIP_LETTER_GAP = 2.dp

/** Space above and below a week strip, and above and below a Week header cell. */
internal val WEEK_STRIP_VERTICAL_PADDING = 4.dp

/**
 * Shows the Day view's week strip: [shownDate]'s week, the shown day selected. It sits where the
 * Week view's day headers sit, inset by the Week view's hour column (measured with the same
 * [weekLabelPrefix]) and flush right, so dates don't move between the two views.
 */
@Composable
internal fun DayViewWeekStrip(
    shownDate: LocalDate,
    firstDayOfWeek: Int,
    todayDayCode: Int,
    timePattern: String,
    weekLabelPrefix: String,
    onDayClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shownDayCode = remember(shownDate) { DayPagerUtils.localDateToDayCode(shownDate) }
    val weekDates = remember(shownDate, firstDayOfWeek) { AgendaWeekBarLogic.weekDates(shownDate, firstDayOfWeek) }
    AgendaWeekBar(
        weekDates = weekDates,
        selectedDayCode = shownDayCode,
        todayDayCode = todayDayCode,
        onDayClick = onDayClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = rememberWeekTimeColumnWidth(timePattern, weekLabelPrefix)),
        horizontalPadding = 0.dp,
    )
}
