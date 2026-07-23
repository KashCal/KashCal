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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.components.pickers.DayCellStyle
import org.onekash.kashcal.ui.components.pickers.dayCellStyle
import org.onekash.kashcal.ui.components.weekview.WeekViewUtils
import java.time.LocalDate

/**
 * A pinned week strip for the Agenda view: a row of locale-aware single-letter
 * weekday labels above a row of that week's day-of-month numbers. Column order
 * and the first column follow the caller's first-day-of-week setting (the caller
 * builds [weekDates] via [AgendaWeekBarLogic.weekDates], so this composable is
 * order-agnostic).
 *
 * Cell states (matching the date picker's day-cell convention via [dayCellStyle],
 * so the two surfaces read alike):
 * - selected: filled [MaterialTheme.colorScheme.inverseSurface] circle
 * - today (not selected): filled [MaterialTheme.colorScheme.primaryContainer] circle
 * - today AND selected: the selected fill wins (selection takes precedence)
 * Weekend day numbers are tinted with the error color like the week view.
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
    modifier: Modifier = Modifier
) {
    // Narrow weekday letter per column, derived from each shown date so the
    // letters always line up with the numbers below them regardless of order.
    val letters = remember(weekDates) {
        weekDates.map { AgendaWeekBarLogic.narrowWeekdayLetter(it.dayOfWeek) }
    }

    Column(modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        // Weekday letters row.
        Row(modifier = Modifier) {
            weekDates.forEachIndexed { i, date ->
                Text(
                    text = letters[i],
                    style = MaterialTheme.typography.labelSmall,
                    color = if (WeekViewUtils.isWeekend(date)) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        // Date numbers row. Cross-fades + slides when the shown week changes, so a
        // scroll into a new week reads as "moved weeks" rather than a hard cut.
        // Forward weeks slide in from the right, earlier weeks from the left.
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
            Row(modifier = Modifier.padding(top = 2.dp)) {
                dates.forEach { date ->
                    val dayCode = date.year * 10000 + date.monthValue * 100 + date.dayOfMonth
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
    // Reuse the date picker's day-cell convention so the two surfaces match:
    // selected = inverseSurface (dark), today = primaryContainer (accent),
    // selection taking precedence over today.
    val style = dayCellStyle(isToday = isToday, isSelected = isSelected)
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

    // The bare day number is meaningless to a screen reader; describe the full
    // date + state on the cell and label the tap action ("Go to date"). Merging
    // semantics keeps the clickable's click action while overriding the spoken
    // text, so TalkBack reads e.g. "Saturday, July 18, Today" instead of "18".
    val cellDescription = AgendaWeekBarLogic.cellContentDescription(
        date = date,
        isToday = isToday,
        isSelected = isSelected,
        todayLabel = stringResource(R.string.label_today),
        selectedLabel = stringResource(R.string.cd_selected)
    )
    val goToDateLabel = stringResource(R.string.label_go_to_date)

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .then(if (fill != null) Modifier.background(fill, CircleShape) else Modifier)
                // Today's tonal fill can wash out against the surface for pale accent
                // seeds; a hairline outline keeps the ring visible on any theme. Selected
                // uses inverseSurface (already high-contrast) so it needs no border.
                .then(
                    if (style == DayCellStyle.TODAY) {
                        Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    } else {
                        Modifier
                    }
                )
                .clickable(onClickLabel = goToDateLabel, onClick = onClick)
                .semantics(mergeDescendants = true) {
                    contentDescription = cellDescription
                    selected = isSelected
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
                color = numberColor,
                textAlign = TextAlign.Center
            )
        }
    }
}
