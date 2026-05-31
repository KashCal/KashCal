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
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.model.SearchResult
import org.onekash.kashcal.error.ErrorPresentation
import org.onekash.kashcal.sync.model.SyncChange
import org.onekash.kashcal.ui.components.SyncBannerState
import org.onekash.kashcal.ui.model.CalendarGroup
import org.onekash.kashcal.util.CalendarIntentData
import java.time.LocalDate

/**
 * Immutable UI state for the HomeScreen (main calendar view).
 *
 * Follows Jetpack Compose best practices:
 * - @Immutable annotation for stable recomposition
 * - Clear separation of view state, data state, and UI events
 *
 * Architecture:
 * - Local-first: Works offline with Room database
 * - Optional sync: iCloud sync can be added without changing UI state
 */
@Immutable
data class HomeUiState(
    // === VIEWING STATE (changes on swipe) ===
    /** Currently viewing year */
    val viewingYear: Int = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR),
    /** Currently viewing month (0-indexed, January = 0) */
    val viewingMonth: Int = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH),

    // === EVENT DOTS (pre-cached for calendar display) ===
    /**
     * Event dots for calendar indicators.
     * Key: "YYYY-MM" (e.g., "2024-12")
     * Value: Map of dayOfMonth → List of color ints
     */
    val eventDots: ImmutableMap<String, ImmutableMap<Int, ImmutableList<Int>>> = persistentMapOf(),
    /**
     * Set of months that have successfully loaded dots.
     * Uses encoded month format: year * 12 + month
     * Only months in this Set have valid dots in eventDots map.
     * Prevents false cache hits when fast-swipe cancels intermediate month loads.
     */
    val loadedMonths: PersistentSet<Int> = persistentSetOf(),
    /**
     * Set of years that have successfully loaded dots for year view.
     * Prevents re-query when swiping back to a previously loaded year.
     */
    val loadedYears: PersistentSet<Int> = persistentSetOf(),

    // === SELECTED DAY STATE ===
    /** Currently selected date (epoch millis), 0 = no selection */
    val selectedDate: Long = 0L,
    /** Formatted label for selected day (e.g., "December 17, 2024") */
    val selectedDayLabel: String = "",

    // === MONTH EVENTS (for full-height month grid) ===
    /**
     * Events grouped by dayCode for the month grid.
     * Key: dayCode (YYYYMMDD format, e.g., 20260315)
     * Value: List of DisplayEvent for that day
     * Covers viewing month +/- 1 month for smooth pager transitions.
     */
    val monthEventsMap: ImmutableMap<Int, ImmutableList<DisplayEvent>> = persistentMapOf(),

    // === DAY EVENTS CACHE (for swipe pager) ===
    /**
     * Cache of events grouped by dayCode for smooth day pager scrolling.
     * Key: dayCode (YYYYMMDD format, e.g., 20260115)
     * Value: List of DisplayEvent for that day
     * Loaded as a 7-day sliding window centered on selectedDate.
     */
    val dayEventsCache: ImmutableMap<Int, ImmutableList<DisplayEvent>> = persistentMapOf(),
    /** Center date of the current cache (epoch millis at midnight) */
    val cacheRangeCenter: Long = 0L,
    /**
     * Set of dayCode values that have been loaded.
     * Used to distinguish "no events" from "not yet loaded".
     */
    val loadedDayCodes: PersistentSet<Int> = persistentSetOf(),

    // === CALENDARS ===
    /** All available calendars (visibility determined by Calendar.isVisible) */
    val calendars: ImmutableList<Calendar> = persistentListOf(),
    /** Calendars grouped by account for UI display */
    val calendarGroups: ImmutableList<CalendarGroup> = persistentListOf(),
    /** Device calendars grouped by account (for EventFormSheet picker) */
    val deviceCalendarGroups: ImmutableList<CalendarGroup> = persistentListOf(),
    /** Default calendar for new events (supports both Room and Device) */
    val defaultCalendar: DefaultCalendar? = null,
    /** Device calendars feature enabled */
    val deviceCalendarsEnabled: Boolean = false,
    /** Enabled device calendars (for drawer visibility toggles) */
    val enabledDeviceCalendars: ImmutableList<DeviceCalendar> = persistentListOf(),
    /** Hidden device calendar IDs (enabled but hidden from view) */
    val hiddenDeviceCalendarIds: PersistentSet<Long> = persistentSetOf(),

    // === SYNC STATE ===
    /** Is sync currently in progress */
    val isSyncing: Boolean = false,
    /** Message describing current sync state */
    val syncMessage: String? = null,
    /** Has any sync-capable account with credentials (iCloud or CalDAV) */
    val isConfigured: Boolean = false,

    // === LOADING STATE ===
    /** General loading indicator */
    val isLoading: Boolean = false,

    // === SEARCH STATE ===
    /** Is search mode active */
    val isSearchActive: Boolean = false,
    /** Current search query */
    val searchQuery: String = "",
    /** Search results (Room + device events) with display timestamp */
    val searchResults: ImmutableList<SearchResult> = persistentListOf(),
    /** Date filter for search. Upcoming = future-only (default, no chip selected). AnyTime = include past. */
    val searchDateFilter: DateFilter = DateFilter.Upcoming,
    /** Show search date picker bottom sheet */
    val showSearchDatePicker: Boolean = false,
    /**
     * Range selection start date (millis) for date picker.
     * When non-null, user has tapped once to start range selection.
     * Second tap on same date = single day, different date = range.
     */
    val searchDateRangeStart: Long? = null,

    // === AGENDA STATE ===
    /** Agenda occurrences - upcoming events for next 30 days (each recurring instance separate) */
    val agendaEvents: ImmutableList<DisplayEvent> = persistentListOf(),
    /** Loading state for agenda */
    val isLoadingAgenda: Boolean = false,

    // === WEEK VIEW STATE (used by ViewMode.THREE_DAYS) ===
    /** First day of the currently displayed week (epoch millis at midnight) */
    val weekViewStartDate: Long = 0L,
    /** Timed events for the week (excludes all-day) */
    val weekViewTimedEvents: ImmutableList<DisplayEvent> = persistentListOf(),
    /** All-day events for the week */
    val weekViewAllDayEvents: ImmutableList<DisplayEvent> = persistentListOf(),
    /** Loading state for week view */
    val isLoadingWeekView: Boolean = false,
    /** Error message if week view load fails */
    val weekViewError: String? = null,
    /** Scroll position in week time grid (pixels) for state preservation */
    val weekViewScrollPosition: Int = 0,
    /** Hour height in dp for pinch-to-zoom (30-150 range) */
    val weekViewHourHeight: Float = 60f,
    /** Current pager position (day index 0-6) for context-aware FAB */
    val weekViewPagerPosition: Int = 0,
    /** Pending pager position to scroll to (null = no pending navigation) */
    val pendingWeekViewPagerPosition: Int? = null,
    /** Show week view date picker dialog */
    val showWeekViewDatePicker: Boolean = false,

    // === DAY DETAIL SHEET (for month view) ===
    /** Show day events bottom sheet */
    val showDayDetailSheet: Boolean = false,
    /** Date for day detail sheet (epoch millis) */
    val dayDetailDate: Long = 0L,

    // === UI DIALOGS/SHEETS ===
    /** Show onboarding for first-time users */
    val showOnboardingSheet: Boolean = false,
    /** Show app info sheet */
    val showAppInfoSheet: Boolean = false,
    /** Show share-availability bottom sheet */
    val showShareAvailabilitySheet: Boolean = false,
    /** Show invitation inbox bottom sheet */
    val isInvitationInboxOpen: Boolean = false,
    /** Show sync changes bottom sheet */
    val showSyncChangesSheet: Boolean = false,
    /** Sync changes for bottom sheet display */
    val syncChanges: ImmutableList<SyncChange> = persistentListOf(),
    /** Current calendar view mode (month grid, agenda list, or 3-day grid) */
    val viewMode: ViewMode = ViewMode.MONTH,
    /**
     * Last non-INSIGHTS view the user was on. Used as the back-target when
     * leaving the Insights screen so back returns to whichever view the user
     * came from (or their persisted default if Insights is the initial view).
     * Never holds INSIGHTS — see HomeViewModel.setViewMode guard.
     */
    val previousNonInsightsMode: ViewMode = ViewMode.MONTH,
    /** Show year overlay for quick navigation */
    val showYearOverlay: Boolean = false,

    // === NAVIGATION EVENTS (one-shot) ===
    /** Navigate to today (consumed after use) */
    val pendingNavigateToToday: Boolean = false,
    /** Navigate to specific month (year, month) - consumed after use */
    val pendingNavigateToMonth: Pair<Int, Int>? = null,
    /** Scroll agenda list to top (today) - consumed after use */
    val pendingScrollAgendaToTop: Boolean = false,

    // === SNACKBAR EVENTS ===
    /** Pending snackbar message */
    val pendingSnackbarMessage: String? = null,
    /** Pending snackbar action */
    val pendingSnackbarAction: (() -> Unit)? = null,

    // === PENDING ACTIONS (from intents) ===
    /**
     * Pending action from notification/widget/shortcut/deep link.
     * Consumed once by UI via LaunchedEffect, then cleared.
     * Follows same pattern as pendingSnackbarMessage.
     */
    val pendingAction: PendingAction? = null,

    // === SYNC BANNER STATE ===
    /** Show sync progress banner */
    val showSyncBanner: Boolean = false,
    /** Sync banner visual state (dots animation, check icon, warning icon) */
    val syncBannerState: SyncBannerState = SyncBannerState.Syncing,
    /** Raw error detail for Failed state (Compose resolves the full message) */
    val syncErrorDetail: String? = null,

    // === ERROR STATE ===
    /**
     * Current error to display.
     * Determined by ErrorMapper based on error type.
     * - Snackbar: Transient, auto-dismisses
     * - Dialog: Blocking, requires user action
     * - Banner: Persistent, shown at top
     * - Silent: Logged only, no UI
     */
    val currentError: ErrorPresentation? = null,
    /** Show error dialog (when currentError is Dialog type) */
    val showErrorDialog: Boolean = false,
    /** Show error banner (when currentError is Banner type) */
    val showErrorBanner: Boolean = false,
    /** URL to open in browser (set by error actions) */
    val pendingUrlToOpen: String? = null,

    /** Pending drag reschedule for recurring event (shows edit scope dialog) */
    val pendingDragReschedule: PendingDragReschedule? = null,

    /** Pending form save awaiting scope selection (shows edit scope sheet over the form) */
    val pendingFormSave: PendingFormSave? = null,

    /** Pending delete awaiting scope selection (shows delete scope sheet) */
    val pendingDelete: PendingDelete? = null,

    /**
     * Tick counter incremented when a form-save deferral path needs
     * to release the form's `isSaving = true` flag back to false —
     * after a save failure or a scope-sheet cancel. The form
     * observes this via LaunchedEffect; the value isn't read, only
     * the change.
     */
    val formSaveFailedTick: Int = 0,

    // === DISPLAY PREFERENCES ===
    /** Show auto-detected emojis in event titles */
    val showEventEmojis: Boolean = true,
    /** Time format preference: "system", "12h", or "24h" */
    val timeFormat: String = "system",
    /** First day of week: 0=system, 1=Sunday, 2=Monday, 7=Saturday */
    val firstDayOfWeek: Int = java.util.Calendar.SUNDAY,
    /** Show week numbers in month calendar grid */
    val showWeekNumbers: Boolean = false
) {
    /**
     * Format the current viewing month/year for display.
     */
    fun getMonthYearLabel(): String {
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.YEAR, viewingYear)
            set(java.util.Calendar.MONTH, viewingMonth)
            set(java.util.Calendar.DAY_OF_MONTH, 1)
        }
        val pattern = org.onekash.kashcal.util.DateTimeUtils.localizedPattern("yMMM")
        return java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(cal.time)
    }

    /**
     * Get the key for event dots lookup.
     */
    fun getDotsKey(year: Int, month: Int): String {
        return String.format(java.util.Locale.ROOT, "%04d-%02d", year, month + 1)
    }

    /**
     * Check if there are events for a specific day.
     */
    fun hasEventsOnDay(year: Int, month: Int, day: Int): Boolean {
        val key = getDotsKey(year, month)
        return eventDots[key]?.get(day)?.isNotEmpty() == true
    }

    /**
     * Get event colors for a specific day.
     */
    fun getEventColors(year: Int, month: Int, day: Int): ImmutableList<Int> {
        val key = getDotsKey(year, month)
        return eventDots[key]?.get(day) ?: persistentListOf()
    }

    /**
     * Check if a calendar is visible.
     * Uses Calendar.isVisible as source of truth.
     */
    fun isCalendarVisible(calendarId: Long): Boolean {
        return calendars.find { it.id == calendarId }?.isVisible ?: true
    }

}

/**
 * Represents a pending action triggered by an intent (notification, widget, shortcut, deep link).
 * Consumed once by UI, then cleared via ViewModel.clearPendingAction().
 *
 * This follows Android's recommended pattern for UI events:
 * - Convert events to state (not Channels)
 * - StateFlow for one-shot events with clear after consumption
 * - ViewModel owns state, UI observes
 * - Survives configuration changes
 *
 * @see <a href="https://developer.android.com/topic/architecture/ui-layer/events">UI events</a>
 */
@Immutable
sealed class PendingAction {
    /**
     * Show event quick view sheet from reminder notification or widget tap.
     *
     * @param eventId Database ID of the event to show
     * @param occurrenceTs Timestamp of the specific occurrence (for recurring events)
     * @param source Where this action originated from
     */
    data class ShowEventQuickView(
        val eventId: Long,
        val occurrenceTs: Long,
        val source: Source
    ) : PendingAction() {
        enum class Source { REMINDER, WIDGET }
    }

    /**
     * Open event form to create new event (from widget).
     *
     * @param startTs Optional start timestamp in milliseconds (epoch ms), or null for current time
     */
    data class CreateEvent(val startTs: Long? = null) : PendingAction()

    /**
     * Open search screen (from app shortcut).
     */
    data object OpenSearch : PendingAction()

    /**
     * Import ICS file (from file share or content:// URI).
     *
     * @param uri Content URI of the ICS file to import
     */
    data class ImportIcsFile(val uri: Uri) : PendingAction()

    /**
     * Navigate to today's date (from widget header tap).
     */
    data object GoToToday : PendingAction()

    /**
     * Show device event quick view sheet from widget tap.
     * Queries CalendarProvider to find the device event instance.
     *
     * @param eventId CalendarProvider event ID
     * @param occurrenceTs Timestamp of the specific occurrence
     */
    data class ShowDeviceEventQuickView(
        val eventId: Long,
        val occurrenceTs: Long
    ) : PendingAction()

    /**
     * Navigate to a specific date (from week widget day tap).
     *
     * @param dayCode Target date in YYYYMMDD format
     */
    data class GoToDate(val dayCode: Int) : PendingAction()

    /**
     * Create event from external calendar intent (ACTION_INSERT).
     * Pre-fills EventFormSheet with data from CalendarContract extras.
     *
     * Used when other apps trigger "Add to Calendar" via standard Android intents.
     * Examples: calendar invites, browser event links, etc.
     *
     * @param data Parsed intent data (title, location, times, etc.)
     * @param invitees List of invitee emails (appended to description)
     */
    data class CreateEventFromCalendarIntent(
        val data: CalendarIntentData,
        val invitees: List<String>
    ) : PendingAction()

    /**
     * Open Quick Add dialog seeded with text shared from another app
     * (Intent.ACTION_SEND, text/plain). Reference time is the moment the
     * intent was received, so "tomorrow" resolves relative to share-arrival,
     * not whatever date the user was browsing.
     *
     * @param text Pre-cleaned single-line text destined for the Quick Add input.
     * @param location First http(s) URL extracted from the share, or null.
     *                 Applied only when the parser does not derive its own location.
     * @param referenceMs Intent-arrival timestamp in epoch ms, used as parse anchor.
     */
    data class QuickAddFromText(
        val text: String,
        val location: String?,
        val referenceMs: Long
    ) : PendingAction()
}

/**
 * Calendar view mode. Replaces the old showAgendaPanel + AgendaViewType two-field model
 * with a single enum for all views.
 *
 * Named ViewMode (not CalendarViewType) to avoid confusion with the removed CalendarViewType
 * enum (commit 2ee370a7).
 */
enum class ViewMode(val key: String) {
    /** Month calendar grid with day events below */
    MONTH("month"),
    /** 30-day upcoming events list */
    AGENDA("agenda"),
    /** Single-day scrollable time grid */
    DAY("day"),
    /** 3-day scrollable time grid */
    THREE_DAYS("three_days"),
    /** 7-day scrollable time grid (full 24-hour range) */
    WEEK("week"),
    /** Full-height month grid with event snippets */
    MONTH_FULL("month_full"),
    /** 12-month year overview grid */
    YEAR("year"),
    /** Premium insights analytics screen (drawer-only, not persisted as default) */
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
     * Pager-page step for next/prev gestures, in pager units. DAY and THREE_DAYS use a
     * 1-day-per-page pager (1 or 3 pages = 1 or 3 days), WEEK uses a 1-week-per-page pager
     * (1 page = 1 week).
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
    val targetStartMinutes: Int
)

/**
 * A form save that was deferred so the user can pick a scope. The
 * sheet is rendered over the form; the form state is held verbatim
 * so a Cancel from the sheet leaves the user's edits intact.
 *
 * `originalRrule` is the rrule the form was opened with — needed by
 * [computeEditScopeOptions][org.onekash.kashcal.ui.viewmodels.computeEditScopeOptions]
 * to detect whether the user changed it.
 *
 * `masterStartTs` and `isDetachedException` are captured at request
 * time from the live event so the option-set rules don't have to
 * derive them from the (possibly user-edited) form state.
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
     * The loaded event's `isAllDay` at form-load time. ScopeContext
     * uses this rather than `formState.isAllDay` so toggling all-day
     * in the form before save can't change the date format the
     * scope-sheet sub-copy applies to the occurrence timestamp.
     */
    val loadedIsAllDay: Boolean = false,
)

/**
 * A delete awaiting scope selection (recurring events only).
 * Non-recurring deletes never set this state; the ViewModel routes
 * them directly via the existing `deleteEventOptimistic` /
 * `deleteDeviceEvent` paths.
 *
 * Sealed by Room vs Device so the consumer (HomeScreen + confirm
 * dispatch in the ViewModel) can branch exhaustively at the type
 * level rather than via nullable-field probes. `masterStartTs` and
 * `isDetachedException` are captured at request time, same as
 * [PendingFormSave].
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
