package org.onekash.kashcal.ui.components

import org.onekash.kashcal.ui.components.weekview.WeekViewUtils
import org.onekash.kashcal.util.DateTimeUtils
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Pure logic behind the Agenda view's top week bar. Kept free of Compose so the
 * date/letter ordering, the item-key parsing, and the scroll-vs-tap anchor rule
 * can be unit-tested. All first-day-of-week resolution delegates to the shared
 * [DateTimeUtils] / [WeekViewUtils] helpers the week/month/year views use, so
 * the bar stays consistent with them (including the 0 = system-default sentinel).
 */
object AgendaWeekBarLogic {

    /**
     * The 7 dates of the week containing [anchor], ordered per [firstDayOfWeek].
     *
     * @param firstDayOfWeek Calendar constant (SUNDAY=1, MONDAY=2, SATURDAY=7) or 0 for system default
     */
    fun weekDates(anchor: LocalDate, firstDayOfWeek: Int): List<LocalDate> {
        val weekStart = WeekViewUtils.getWeekStart(anchor, DateTimeUtils.resolveFirstDayOfWeek(firstDayOfWeek))
        return List(7) { offset -> weekStart.plusDays(offset.toLong()) }
    }

    /**
     * The 7 narrow weekday letters (e.g. "S", "M", ...) in display order for
     * [firstDayOfWeek]. Uses the same narrow style as the week view's day header
     * so the bar's letters match.
     */
    fun weekdayLetters(firstDayOfWeek: Int): List<String> =
        DateTimeUtils.getOrderedDaysOfWeek(firstDayOfWeek).map(::narrowWeekdayLetter)

    /** The locale-aware narrow letter (e.g. "M", "T") for a single weekday. */
    fun narrowWeekdayLetter(dayOfWeek: java.time.DayOfWeek): String =
        dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault())

    /**
     * The spoken label for a week-bar [date] cell: the full localized date (e.g.
     * "Saturday, July 18") followed by any active state words. The bare "18"
     * rendered in the cell is meaningless to a screen reader, so this gives
     * TalkBack the weekday + month and announces today/selected state.
     *
     * The state words are injected (resolved from resources by the caller) so
     * this stays pure and unit-testable, mirroring [AgendaDayHeader]. [todayLabel]
     * and [selectedLabel] are appended (comma-separated) when their flag is set,
     * so "today, selected" reads naturally after the date.
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
     * Derive the anchor date from an agenda list item's key. Item keys all end
     * with the entry's day code in YYYYMMDD form ("header_<day>",
     * "room_<id>_<startTs>_<day>", "device_<id>_<day>"), so the trailing
     * '_'-delimited token is the day code regardless of item type — mirroring
     * [AgendaTitleMonth]. Returns [fallback] when [key] is null or the token is
     * missing / not a valid day code.
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
     * The week-bar anchor to display. While a tap-driven scroll is animating
     * ([suppressed] true) the bar holds [heldAnchor] — the tapped week — so it
     * doesn't flicker through intermediate weeks as the list animates past them.
     * Otherwise it tracks the topmost visible item ([topKey]).
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
     * The key of the item that owns the content-top line. The list's top
     * [contentPaddingTopPx] is padding, so the item physically above the first
     * fully-visible one peeks into it — using `visibleItemsInfo.first()` would
     * track that peeking previous item's week. Skip any item whose bottom edge
     * is at or above the content-top line and take the first that crosses it, so
     * tapping a week's first day anchors on that week, not the previous one.
     * Falls back to the first item's key (or null) when nothing qualifies.
     */
    fun topmostAnchorKey(items: List<VisibleItem>, contentPaddingTopPx: Int): String? {
        if (items.isEmpty()) return null
        val crossing = items.firstOrNull { it.offset + it.size > contentPaddingTopPx }
        return (crossing ?: items.first()).key
    }
}
