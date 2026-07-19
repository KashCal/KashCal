package org.onekash.kashcal.ui.components

import org.onekash.kashcal.ui.util.DayPagerUtils
import org.onekash.kashcal.util.DateTimeUtils
import org.onekash.kashcal.widget.tomorrowDayCodeOf
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The two rendered halves of an agenda day-group header: an optional relative
 * word ("Today"/"Tomorrow") and the full localized date. Returned unjoined so
 * the composable can style the relative word (accent) distinctly from the date
 * (muted). See [AgendaDayHeader.format].
 */
data class AgendaHeaderParts(
    val relativeLabel: String?,
    val dateText: String
)

/**
 * A fully-joined agenda header ("Today · Saturday, July 18") plus the character
 * range of the relative word within it, so the UI can accent just that word.
 * [accentStart] is -1 when there is no relative word (a plain date header) or
 * the word can't be located in the joined string.
 */
data class AgendaHeaderText(
    val text: String,
    val accentStart: Int,
    val accentEnd: Int
) {
    val hasAccent: Boolean get() = accentStart in 0 until accentEnd
}

/**
 * Formats the date-group header shown above each day's cards in the Agenda view.
 *
 * Today's header carries [AgendaHeaderParts.relativeLabel] = the caller's
 * "Today" string; the next day carries the "Tomorrow" string; every other day
 * has a null relative label. The date half is always the full localized date
 * (e.g. "Saturday, July 18") via the "EEEEMMMMd" skeleton — matching the
 * agenda list's existing header formatter, not the widget's short form.
 *
 * The labels are injected (not resolved from a Context) so this stays a pure,
 * unit-testable object mirroring [AgendaTitleMonth]. Callers resolve the strings
 * via `stringResource(R.string.label_today)` / `label_tomorrow` first.
 */
object AgendaDayHeader {

    fun format(
        dayCode: Int,
        todayDayCode: Int,
        todayLabel: String,
        tomorrowLabel: String
    ): AgendaHeaderParts {
        val relativeLabel = when (dayCode) {
            todayDayCode -> todayLabel
            tomorrowDayCodeOf(todayDayCode) -> tomorrowLabel
            else -> null
        }
        val date = DayPagerUtils.dayCodeToLocalDate(dayCode)
        val formatter = DateTimeFormatter.ofPattern(
            DateTimeUtils.localizedPattern("EEEEMMMMd"),
            Locale.getDefault()
        )
        return AgendaHeaderParts(relativeLabel, date.format(formatter))
    }

    /**
     * Join [parts] into a single header string and locate the relative word for
     * accenting. When there is a relative word, [relativeWithDateTemplate] (the
     * "%1$s · %2$s" resource) combines it with the date; the accent range is
     * found by locating the relative word inside the *formatted* result, so it
     * stays correct even if a locale reorders the template params (e.g.
     * date-first). Plain-date headers return the date with no accent range.
     */
    fun joinedHeader(
        parts: AgendaHeaderParts,
        relativeWithDateTemplate: String
    ): AgendaHeaderText {
        val label = parts.relativeLabel
            ?: return AgendaHeaderText(parts.dateText, -1, -1)
        val joined = String.format(relativeWithDateTemplate, label, parts.dateText)
        val start = joined.indexOf(label)
        return if (start >= 0) {
            AgendaHeaderText(joined, start, start + label.length)
        } else {
            AgendaHeaderText(joined, -1, -1)
        }
    }
}
