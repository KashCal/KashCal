package org.onekash.kashcal.ui.components

import org.onekash.kashcal.ui.components.weekview.WeekViewUtils
import org.onekash.kashcal.util.DateTimeUtils
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Holds the pure logic behind the Agenda view's top week bar, free of Compose so
 * the date and letter ordering, item-key parsing and scroll-vs-tap anchor rule
 * are unit-testable. First-day-of-week resolution delegates to the shared
 * [DateTimeUtils] and [WeekViewUtils] helpers the other views use, so the bar
 * agrees with them, including the 0 = system-default sentinel.
 */
object AgendaWeekBarLogic {

    /**
     * Returns the 7 dates of the week containing [anchor], ordered per [firstDayOfWeek].
     *
     * @param firstDayOfWeek a Calendar constant (SUNDAY=1, MONDAY=2, SATURDAY=7), or 0 for the
     *   system default
     */
    fun weekDates(anchor: LocalDate, firstDayOfWeek: Int): List<LocalDate> {
        val weekStart = WeekViewUtils.getWeekStart(anchor, DateTimeUtils.resolveFirstDayOfWeek(firstDayOfWeek))
        return List(7) { offset -> weekStart.plusDays(offset.toLong()) }
    }

    /**
     * Returns the 7 narrow weekday letters (e.g. "S", "M") in display order for
     * [firstDayOfWeek], in the narrow style of the week view's compact day header.
     */
    fun weekdayLetters(firstDayOfWeek: Int): List<String> =
        DateTimeUtils.getOrderedDaysOfWeek(firstDayOfWeek).map(::narrowWeekdayLetter)

    /** Returns the locale's narrow letter (e.g. "M", "T") for one weekday. */
    fun narrowWeekdayLetter(dayOfWeek: java.time.DayOfWeek): String =
        dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault())

    /**
     * Returns the spoken label for a week-bar [date] cell: the full localized date
     * (e.g. "Saturday, July 18") followed by any state words. The bare "18" in the
     * cell means nothing to a screen reader, so this gives TalkBack the weekday
     * and month and announces today and selected.
     *
     * The caller resolves the state words from resources, keeping this pure like
     * [AgendaDayHeader]. [todayLabel] and [selectedLabel] are appended,
     * comma-separated, when their flag is set.
     */
    fun cellContentDescription(
        date: LocalDate,
        isToday: Boolean,
        isSelected: Boolean,
        todayLabel: String,
        selectedLabel: String
    ): String {
        val dateText = date.format(
            DateTimeFormatter.ofPattern(DateTimeUtils.localizedPattern("EEEEMMMMd"), Locale.getDefault())
        )
        val states = buildList {
            if (isToday) add(todayLabel)
            if (isSelected) add(selectedLabel)
        }
        return if (states.isEmpty()) dateText else "$dateText, ${states.joinToString(", ")}"
    }

    /**
     * Returns the anchor date for an agenda list item's key. Keys end with the
     * entry's YYYYMMDD day code ("header_<day>", "room_<id>_<startTs>_<day>",
     * "device_<id>_<day>"), so the trailing '_'-delimited token is the day code for
     * every item type, as in [AgendaTitleMonth]. Returns [fallback] when [key] is
     * null or the token isn't a valid day code.
     */
    fun anchorDateFromItemKey(key: String?, fallback: LocalDate): LocalDate {
        if (key == null) return fallback
        val dayCode = key.substringAfterLast('_').toIntOrNull() ?: return fallback
        val year = dayCode / 10000
        val month = (dayCode % 10000) / 100
        val day = dayCode % 100
        if (month !in 1..12 || day !in 1..31) return fallback
        return try {
            LocalDate.of(year, month, day)
        } catch (_: java.time.DateTimeException) {
            fallback
        }
    }

    /**
     * Returns the week-bar anchor to display. While a tap-driven scroll animates
     * ([suppressed] true) the bar holds [heldAnchor], the tapped week, so it
     * doesn't flicker through the weeks the list passes. Otherwise, or when
     * [heldAnchor] is null, it tracks the topmost visible item ([topKey]).
     */
    fun resolveAnchorDate(
        topKey: String?,
        suppressed: Boolean,
        heldAnchor: LocalDate?,
        fallback: LocalDate
    ): LocalDate {
        if (suppressed && heldAnchor != null) return heldAnchor
        return anchorDateFromItemKey(topKey, fallback)
    }

    /** A visible list item's key plus its layout geometry (all in pixels). */
    data class VisibleItem(val key: String?, val offset: Int, val size: Int)

    /**
     * Returns the key of the item that owns the content-top line. The list's top
     * [contentPaddingTopPx] is padding, so the item above the first fully visible
     * one peeks into it; `visibleItemsInfo.first()` would track that previous
     * item's week. Items whose bottom edge is at or above the content-top line are
     * skipped and the first that crosses it wins, so tapping a week's first day
     * anchors on that week. Falls back to the first item's key when none crosses,
     * and returns null for an empty list.
     */
    fun topmostAnchorKey(items: List<VisibleItem>, contentPaddingTopPx: Int): String? {
        if (items.isEmpty()) return null
        val crossing = items.firstOrNull { it.offset + it.size > contentPaddingTopPx }
        return (crossing ?: items.first()).key
    }
}
