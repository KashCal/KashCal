package org.onekash.kashcal.ui.viewmodels

import android.net.Uri
import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import org.onekash.kashcal.data.calendar_provider.DeviceCalendar
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.preferences.DefaultCalendar
import org.onekash.kashcal.domain.changes.RecentChangeItem
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.model.SearchResult
import org.onekash.kashcal.error.ErrorPresentation
import org.onekash.kashcal.ui.components.SyncBannerState
import org.onekash.kashcal.ui.model.CalendarGroup
import org.onekash.kashcal.util.CalendarIntentData
import java.time.LocalDate

/** Holds the state HomeScreen renders, published by [HomeViewModel]. */
@Immutable
data class HomeUiState(
    // === VIEWING STATE (changes on swipe) ===
    /** Year being viewed. */
    val viewingYear: Int = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR),
    /** Month being viewed, 0-indexed (January = 0). */
    val viewingMonth: Int = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH),

    // === EVENT DOTS (pre-cached for calendar display) ===
    /**
     * Event dots for the month and year grids: "YYYY-MM" key ([getDotsKey], e.g. "2024-12")
     * to a map of day of month to the dots' color ints.
     */
    val eventDots: ImmutableMap<String, ImmutableMap<Int, ImmutableList<Int>>> = persistentMapOf(),
    /**
     * Months whose dots finished loading, encoded as year * 12 + month. Only these months have
     * valid entries in [eventDots]; a fast swipe cancels intermediate loads, so a key alone
     * would be a false cache hit.
     */
    val loadedMonths: PersistentSet<Int> = persistentSetOf(),
    /** Years whose year-view dots finished loading, so swiping back to one doesn't re-query. */
    val loadedYears: PersistentSet<Int> = persistentSetOf(),

    // === SELECTED DAY STATE ===
    /** Selected date in epoch millis, 0 when none. */
    val selectedDate: Long = 0L,
    /** Localized label for the selected day, e.g. "December 17, 2024". */
    val selectedDayLabel: String = "",

    // === DAY EVENTS CACHE (for swipe pager) ===
    /**
     * Events for the day pager by dayCode (YYYYMMDD, e.g. 20260115), loaded from 3 days before
     * [cacheRangeCenter] to 4 days after.
     */
    val dayEventsCache: ImmutableMap<Int, ImmutableList<DisplayEvent>> = persistentMapOf(),
    /** Center date of [dayEventsCache] in epoch millis, 0 when nothing is loaded. */
    val cacheRangeCenter: Long = 0L,
    /**
     * The 7 dayCodes from 3 days before [cacheRangeCenter] to 3 after, so "no events" differs
     * from "not loaded yet".
     */
    val loadedDayCodes: PersistentSet<Int> = persistentSetOf(),

    // === CALENDARS ===
    /** All Room calendars; [Calendar.isVisible] decides which show. */
    val calendars: ImmutableList<Calendar> = persistentListOf(),
    /** Room calendars grouped by account. */
    val calendarGroups: ImmutableList<CalendarGroup> = persistentListOf(),
    /** Writable device calendars grouped by account, for the EventFormSheet picker. */
    val deviceCalendarGroups: ImmutableList<CalendarGroup> = persistentListOf(),
    /** Default calendar for new events, a Room or a device calendar. */
    val defaultCalendar: DefaultCalendar? = null,
    /** Usage-ranked tag suggestions for the event form's tag chip row. */
    val categorySuggestions: ImmutableList<String> = persistentListOf(),
    /** Custom color per tag name; null falls back to the name's hash color. */
    val tagColors: ImmutableMap<String, Int?> = persistentMapOf(),
    /** Whether the device calendars feature is on. */
    val deviceCalendarsEnabled: Boolean = false,
    /** Enabled device calendars, for the drawer's visibility toggles. */
    val enabledDeviceCalendars: ImmutableList<DeviceCalendar> = persistentListOf(),
    /** Device calendar ids that are enabled but hidden from view. */
    val hiddenDeviceCalendarIds: PersistentSet<Long> = persistentSetOf(),

    // === SYNC STATE ===
    /** Whether sync work is live; only the duplicate-sync guard reads it, not the UI. */
    val isSyncing: Boolean = false,
    /**
     * Whether the pull-to-refresh spinner shows. Set only for a running pull-to-refresh started
     * in this session; cleared on every terminal sync status. Kept apart from [isSyncing] so a
     * WorkManager status replayed on a fresh process never brings the spinner back.
     */
    val showRefreshSpinner: Boolean = false,
    /** Set only when initialization fails; nothing in the UI reads it. */
    val syncMessage: String? = null,
    /** Whether any CalDAV-capable account (iCloud or CalDAV) has credentials. */
    val isConfigured: Boolean = false,

    // === LOADING STATE ===
    /** Shows HomeScreen's full-screen spinner; [HomeViewModel] never sets it true. */
    val isLoading: Boolean = false,

    // === SEARCH STATE ===
    /** Whether search mode is on. */
    val isSearchActive: Boolean = false,
    /** Current search query. */
    val searchQuery: String = "",
    /** Search results, Room and device events, each with its display timestamp. */
    val searchResults: ImmutableList<SearchResult> = persistentListOf(),
    /**
     * Date filter for search. [DateFilter.Upcoming], the default with no chip selected, skips
     * past events; [DateFilter.AnyTime] includes them.
     */
    val searchDateFilter: DateFilter = DateFilter.Upcoming,
    /** Whether the search date picker sheet shows. */
    val showSearchDatePicker: Boolean = false,
    /**
     * First tap of the search date picker in millis, null before it. A second tap on the same
     * day makes a [DateFilter.SingleDay], on another day a [DateFilter.CustomRange].
     */
    val searchDateRangeStart: Long? = null,

    // === TIME GRID STATE (DAY, THREE_DAYS, WEEK) ===
    /** Time-grid scroll position in pixels, kept for the session only. */
    val weekViewScrollPosition: Int = 0,
    /**
     * Saved time-grid scroll position in minutes from midnight (0..1439), restored across app
     * restarts. -1 when never saved, which falls back to the default hour.
     */
    val weekViewSavedScrollMinutes: Int = -1,
    /** Hour height in dp for pinch-to-zoom, clamped to 30-150. */
    val weekViewHourHeight: Float = 60f,
    /**
     * Leftmost page of the day or week pager, read by the top bar title, the Day week strip and
     * the pager navigation. A page is a day in DAY and THREE_DAYS and a week in WEEK; the center
     * page is today or the week holding it.
     */
    val weekViewPagerPosition: Int = 0,
    /** Pager page to scroll to, or null when no navigation is pending. */
    val pendingWeekViewPagerPosition: Int? = null,
    /** Whether the time-grid date picker dialog shows. */
    val showWeekViewDatePicker: Boolean = false,

    // === DAY DETAIL SHEET (full-height month view) ===
    /** Whether the day events sheet shows. */
    val showDayDetailSheet: Boolean = false,
    /** Date of the day events sheet in epoch millis. */
    val dayDetailDate: Long = 0L,

    // === UI DIALOGS/SHEETS ===
    /** Whether the first-run onboarding sheet shows. */
    val showOnboardingSheet: Boolean = false,
    /** Release notes the upgraded user hasn't acknowledged yet, ascending by versionCode. */
    val whatsNewReleases: ImmutableList<org.onekash.kashcal.domain.whatsnew.ReleaseNote> = persistentListOf(),
    /** Whether the app info sheet shows. */
    val showAppInfoSheet: Boolean = false,
    /** Whether the share-availability sheet shows. */
    val showShareAvailabilitySheet: Boolean = false,
    /** Whether the invitation inbox sheet shows. */
    val isInvitationInboxOpen: Boolean = false,
    /** Whether the Recent changes sheet shows. */
    val isRecentChangesOpen: Boolean = false,
    /** The Recent changes rows, read only while the sheet shows. */
    val recentChanges: ImmutableList<RecentChangeItem> = persistentListOf(),
    /** The rows the last Clear all hid, while its undo is on offer; empty otherwise. */
    val recentChangesClearedIds: ImmutableList<Long> = persistentListOf(),
    /** Current calendar view. */
    val viewMode: ViewMode = ViewMode.MONTH,
    /**
     * Last view other than INSIGHTS, where back from Insights returns. Seeded from the persisted
     * default view. Never holds INSIGHTS: [HomeViewModel.setViewMode] skips it.
     */
    val previousNonInsightsMode: ViewMode = ViewMode.MONTH,
    /** Whether the year overlay for quick navigation shows. */
    val showYearOverlay: Boolean = false,

    // === NAVIGATION EVENTS (one-shot) ===
    /** Navigates to today with an animated scroll; consumed after use. */
    val pendingNavigateToToday: Boolean = false,
    /**
     * Navigates to today without animation; consumed after use. The cold-start landing uses it
     * so the pager settles in one frame, with no animation for concurrent state writes to fight.
     */
    val pendingNavigateToTodayInstant: Boolean = false,
    /** Navigates to a (year, month); consumed after use. */
    val pendingNavigateToMonth: Pair<Int, Int>? = null,
    /** Scrolls the agenda list to its top (today); consumed after use. */
    val pendingScrollAgendaToTop: Boolean = false,

    // === SNACKBAR EVENTS ===
    /** Snackbar message waiting to show. */
    val pendingSnackbarMessage: String? = null,
    /** Action for the pending snackbar's View button, or null for no button. */
    val pendingSnackbarAction: (() -> Unit)? = null,

    // === PENDING ACTIONS (from intents) ===
    /** Action from an intent, consumed once by the UI; see [PendingAction]. */
    val pendingAction: PendingAction? = null,

    // === SYNC BANNER STATE ===
    /** Whether the sync progress banner shows. */
    val showSyncBanner: Boolean = false,
    /** Sync banner state: syncing, preparing, success, partial error or error. */
    val syncBannerState: SyncBannerState = SyncBannerState.Syncing,
    /** Raw error detail for a failed sync; Compose resolves the full message. */
    val syncErrorDetail: String? = null,

    // === ERROR STATE ===
    /**
     * Error set by [HomeViewModel.showError]; ErrorMapper picks the presentation: Snackbar,
     * Dialog (blocking, needs an action) or Banner (persistent, at top). A Silent error is only
     * logged and never lands here. No screen reads this field.
     */
    val currentError: ErrorPresentation? = null,
    /** True when [currentError] is a Dialog. */
    val showErrorDialog: Boolean = false,
    /** True when [currentError] is a Banner. */
    val showErrorBanner: Boolean = false,
    /** URL to open in the browser, set by an error action. */
    val pendingUrlToOpen: String? = null,

    /** Drag reschedule of a recurring event awaiting its edit scope. */
    val pendingDragReschedule: PendingDragReschedule? = null,

    /** Form save awaiting its scope; the scope sheet shows over the form. */
    val pendingFormSave: PendingFormSave? = null,

    /** Delete awaiting its scope; the delete scope sheet shows. */
    val pendingDelete: PendingDelete? = null,

    /**
     * Increments after a deferred save fails or the scope sheet is cancelled, so the form clears
     * its `isSaving` flag. The form reacts to the change, not the value.
     */
    val formSaveFailedTick: Int = 0,

    // === DISPLAY PREFERENCES ===
    /** Whether event titles show auto-detected emojis. */
    val showEventEmojis: Boolean = true,
    /** Time format preference: "system", "12h" or "24h". */
    val timeFormat: String = "system",
    /**
     * First day of week: 0 for the system default, else a Calendar constant (1 Sunday, 2 Monday,
     * 7 Saturday).
     */
    val firstDayOfWeek: Int = java.util.Calendar.SUNDAY,
    /** Whether the month grid shows week numbers. */
    val showWeekNumbers: Boolean = false,
    /** Puts the event form's tag row above the notes; false puts it between notes and guests. */
    val tagsAboveNotes: Boolean = false,
    /** Whether the Agenda view's top week bar is expanded (shown), the default. */
    val agendaWeekBarExpanded: Boolean = true,
    /** Whether the Day view's top week-strip date picker is expanded (shown), the default. */
    val dayWeekBarExpanded: Boolean = true,
    /**
     * Whether the all-day strip in the Day, 3-Day and Week views is expanded (up to 3 rows per
     * day) or collapsed (1 row); collapsed by default.
     */
    val allDayRowsExpanded: Boolean = false,
    /** User's avatar initials, up to 2 letters; empty shows the generic glyph. */
    val userInitials: String = ""
) {
    /** Returns the viewed month and year as a localized label. */
    fun getMonthYearLabel(): String {
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.YEAR, viewingYear)
            set(java.util.Calendar.MONTH, viewingMonth)
            set(java.util.Calendar.DAY_OF_MONTH, 1)
        }
        val pattern = org.onekash.kashcal.util.DateTimeUtils.localizedPattern("yMMM")
        return java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(cal.time)
    }

    /** Returns the [eventDots] key for a 0-indexed [month]. */
    fun getDotsKey(year: Int, month: Int): String {
        return String.format(java.util.Locale.ROOT, "%04d-%02d", year, month + 1)
    }

    /** Returns whether the day has any event dots. */
    fun hasEventsOnDay(year: Int, month: Int, day: Int): Boolean {
        val key = getDotsKey(year, month)
        return eventDots[key]?.get(day)?.isNotEmpty() == true
    }

    /** Returns the day's event dot colors, empty when none are loaded. */
    fun getEventColors(year: Int, month: Int, day: Int): ImmutableList<Int> {
        val key = getDotsKey(year, month)
        return eventDots[key]?.get(day) ?: persistentListOf()
    }

    /** Returns [Calendar.isVisible], the source of truth; true for an unknown id. */
    fun isCalendarVisible(calendarId: Long): Boolean {
        return calendars.find { it.id == calendarId }?.isVisible ?: true
    }

}

/**
 * An action from an intent, for example a notification, widget, shortcut, share or deep link.
 *
 * MainActivity handles it once, then calls [HomeViewModel.clearPendingAction] after all its
 * suspend work, since clearing changes the LaunchedEffect key and cancels the handler. Held as
 * ViewModel state, not a Channel, so it survives a configuration change.
 *
 * @see <a href="https://developer.android.com/topic/architecture/ui-layer/events">UI events</a>
 */
@Immutable
sealed class PendingAction {
    /**
     * Opens a Room event's quick view sheet, from a reminder notification or a widget tap.
     *
     * @param eventId Room id of the event.
     * @param occurrenceTs start of the occurrence to show.
     */
    data class ShowEventQuickView(
        val eventId: Long,
        val occurrenceTs: Long,
        val source: Source
    ) : PendingAction() {
        enum class Source { REMINDER, WIDGET }
    }

    /**
     * Opens a new event, from a widget, the New Event app shortcut or the Quick Settings tile.
     * With no [startTs], Quick Add opens when it is enabled, else the full form.
     *
     * @param startTs start in epoch millis, set by the week widget; null for the next hour.
     */
    data class CreateEvent(val startTs: Long? = null) : PendingAction()

    /** Opens search, from the Search app shortcut. */
    data object OpenSearch : PendingAction()

    /** Opens the import sheet for a shared or opened ICS file at [uri] (content or file). */
    data class ImportIcsFile(val uri: Uri) : PendingAction()

    /** Navigates to today, from a widget or the Today app shortcut. */
    data object GoToToday : PendingAction()

    /**
     * Opens a device event's quick view at a known occurrence, from a device reminder
     * notification, a widget tap or a CalendarContract VIEW /events/{id} with a begin time. When
     * no instance matches, the calendar navigates to the event's start date, or shows a "not
     * found" snackbar when that fails too.
     *
     * @param eventId CalendarProvider event id.
     * @param occurrenceTs start of the occurrence to show.
     */
    data class ShowDeviceEventQuickView(
        val eventId: Long,
        val occurrenceTs: Long
    ) : PendingAction()

    /**
     * Opens a device event from an external VIEW intent that gave only its id. The quick view
     * opens at the resolved occurrence: the next instance of a series, or DTSTART for a single
     * event. If none resolves (for example an ended series), the calendar navigates to the
     * event's start date, and only if that fails shows a "not found" snackbar.
     *
     * @param eventId CalendarProvider event id.
     */
    data class OpenDeviceEventById(
        val eventId: Long
    ) : PendingAction()

    /**
     * Navigates to a date, from a week widget day tap or a CalendarContract /time URI.
     *
     * @param dayCode target date as YYYYMMDD.
     */
    data class GoToDate(val dayCode: Int) : PendingAction()

    /**
     * Opens EventFormSheet pre-filled from another app's "Add to Calendar" intent (ACTION_INSERT
     * or ACTION_EDIT, or an EDIT on the CalendarContract /events URI), or from a long text share,
     * which [org.onekash.kashcal.util.ShareIntentRouter] puts in the description.
     *
     * @param data parsed intent data: title, location, times and the rest.
     * @param invitees invitee emails, appended to the description.
     */
    data class CreateEventFromCalendarIntent(
        val data: CalendarIntentData,
        val invitees: List<String>
    ) : PendingAction()

    /**
     * Opens the Quick Add dialog seeded with a short text share from another app
     * (`Intent.ACTION_SEND`, text/plain). The reference time is the intent's arrival, so
     * "tomorrow" resolves against the share, not the date the user was browsing.
     *
     * @param text cleaned single-line text for the Quick Add input.
     * @param location first http(s) URL in the share, or null; applied only when the parser
     *   finds no location of its own.
     * @param referenceMs intent arrival in epoch millis, the parse anchor.
     */
    data class QuickAddFromText(
        val text: String,
        val location: String?,
        val referenceMs: Long
    ) : PendingAction()
}

/** Calendar view; [key] is the persisted value. */
enum class ViewMode(val key: String) {
    /** Month grid with the selected day's events below. */
    MONTH("month"),
    /** Upcoming events list, 90 days ahead. */
    AGENDA("agenda"),
    /** Single-day scrollable time grid. */
    DAY("day"),
    /** 3-day scrollable time grid. */
    THREE_DAYS("three_days"),
    /** 7-day scrollable time grid. */
    WEEK("week"),
    /** Full-height month grid with event snippets. */
    MONTH_FULL("month_full"),
    /** 12-month year overview grid. */
    YEAR("year"),
    /** Insights analytics screen; opened from the drawer only, never persisted as the default. */
    INSIGHTS("insights");

    /** True for views that render a scrollable time grid (DAY, THREE_DAYS, WEEK). */
    val isTimeGrid: Boolean get() = this == DAY || this == THREE_DAYS || this == WEEK

    /** Number of day columns rendered side-by-side. Null for non-time-grid views. */
    val visibleDays: Int? get() = when (this) {
        DAY -> 1
        THREE_DAYS -> 3
        WEEK -> 7
        MONTH, AGENDA, MONTH_FULL, YEAR, INSIGHTS -> null
    }

    /**
     * Pager pages to move per next or previous step. DAY and THREE_DAYS use a pager of one day
     * per page (1 or 3 pages), WEEK one week per page (1 page). Null for non-time-grid views.
     */
    val pagerNextStep: Int? get() = when (this) {
        DAY -> 1
        THREE_DAYS -> 3
        WEEK -> 1
        MONTH, AGENDA, MONTH_FULL, YEAR, INSIGHTS -> null
    }

    companion object {
        fun fromKey(key: String): ViewMode = entries.find { it.key == key } ?: MONTH
    }
}

enum class EditScope { THIS_EVENT, THIS_AND_FUTURE, ALL_EVENTS }

@Immutable
data class PendingDragReschedule(
    val displayEvent: DisplayEvent,
    val targetDate: LocalDate,
    val targetStartMinutes: Int,
    /**
     * Scopes the drop can't use: a cross-day move the repeat rule can't
     * express, or (for a Room series) one that would strand deleted, added or
     * edited occurrences. null while that is still being checked (the sheet
     * greys the series scopes until it knows). Same-day drops block nothing.
     */
    val blockedScopes: Set<EditScope>? = emptySet(),
) {
    /** Returns whether [other] is the same drop, whatever its check result. */
    fun isSameDropAs(other: PendingDragReschedule): Boolean =
        displayEvent == other.displayEvent && targetDate == other.targetDate &&
            targetStartMinutes == other.targetStartMinutes
}

/**
 * A form save deferred until the user picks a scope. The sheet shows over the form, and the
 * form state is held verbatim so Cancel keeps the user's edits.
 *
 * `originalRrule` is the rule the form opened with, so [computeEditScopeOptions] can tell
 * whether the user changed it. `masterStartTs` and `isDetachedException` are taken from the
 * loaded event when the save is requested, so the option rules don't derive them from the
 * form state the user may have edited.
 */
@Immutable
data class PendingFormSave(
    val formState: org.onekash.kashcal.ui.components.EventFormState,
    val occurrenceTs: Long,
    val originalRrule: String?,
    val masterStartTs: Long,
    val isDetachedException: Boolean,
    val isRecurringDevice: Boolean = false,
    /**
     * The loaded event's `isAllDay`; [occurrenceDateChanged] reads the date of [occurrenceTs]
     * with it. `formState.isAllDay` won't do: the user can toggle all-day in the form before
     * saving.
     */
    val loadedIsAllDay: Boolean = false,
    /**
     * True when the user moved the opened occurrence of a series to another
     * day; see [ScopeContext.occurrenceDateChanged].
     */
    val occurrenceDateChanged: Boolean = false,
)

/**
 * A recurring-event delete awaiting its scope. Non-recurring deletes never set it; their
 * callers go straight to `deleteEventOptimistic` or `deleteDeviceEvent`.
 *
 * Sealed by Room and Device so HomeScreen and the confirm dispatch in the ViewModel branch
 * exhaustively on the type. `masterStartTs` and `isDetachedException` are taken when the delete
 * is requested, as in [PendingFormSave].
 */
sealed interface PendingDelete {
    val occurrenceTs: Long
    val masterStartTs: Long
    val isDetachedException: Boolean
    val isAllDay: Boolean

    @Immutable
    data class Room(
        val event: org.onekash.kashcal.data.db.entity.Event,
        override val occurrenceTs: Long,
        override val masterStartTs: Long,
        override val isDetachedException: Boolean,
        override val isAllDay: Boolean,
    ) : PendingDelete

    @Immutable
    data class Device(
        val masterEventId: Long,
        val calendarId: Long,
        override val occurrenceTs: Long,
        override val masterStartTs: Long,
        override val isDetachedException: Boolean,
        override val isAllDay: Boolean,
    ) : PendingDelete
}

/** Where a Recent changes row opens: the event, at [occurrenceTs] when set. */
data class RecentChangeTarget(
    val event: org.onekash.kashcal.data.db.entity.Event,
    /** The occurrence to show; null opens the event at its own start. */
    val occurrenceTs: Long?
)
