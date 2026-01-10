package org.onekash.kashcal.ui.viewmodels

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import org.onekash.kashcal.data.db.dao.EventWithNextOccurrence
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.domain.reader.EventReader.OccurrenceWithEvent
import org.onekash.kashcal.error.ErrorPresentation
import android.net.Uri
import org.onekash.kashcal.sync.model.SyncChange
import org.onekash.kashcal.util.CalendarIntentData

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

    // === SELECTED DAY STATE ===
    /** Currently selected date (epoch millis), 0 = no selection */
    val selectedDate: Long = 0L,
    /** Formatted label for selected day (e.g., "December 17, 2024") */
    val selectedDayLabel: String = "",
    /** Events for the selected day (expanded from occurrences) */
    val selectedDayEvents: ImmutableList<Event> = persistentListOf(),
    /** Occurrences for the selected day (includes recurring instances) */
    val selectedDayOccurrences: ImmutableList<Occurrence> = persistentListOf(),
    /** Loading state for day events */
    val isLoadingDayEvents: Boolean = false,

    // === CALENDARS ===
    /** All available calendars (visibility determined by Calendar.isVisible) */
    val calendars: ImmutableList<Calendar> = persistentListOf(),
    /** Default calendar ID for new events */
    val defaultCalendarId: Long? = null,
    /** Show calendar visibility sheet */
    val showCalendarVisibility: Boolean = false,

    // === SYNC STATE ===
    /** Is sync currently in progress */
    val isSyncing: Boolean = false,
    /** Message describing current sync state */
    val syncMessage: String? = null,
    /** Is iCloud connected and configured */
    val isICloudConnected: Boolean = false,
    /** Is iCloud account configured (has credentials) */
    val isConfigured: Boolean = false,

    // === LOADING STATE ===
    /** General loading indicator */
    val isLoading: Boolean = false,

    // === SEARCH STATE ===
    /** Is search mode active */
    val isSearchActive: Boolean = false,
    /** Current search query */
    val searchQuery: String = "",
    /** Search results with next occurrence timestamp for recurring events */
    val searchResults: ImmutableList<EventWithNextOccurrence> = persistentListOf(),
    /** Include past events in search */
    val searchIncludePast: Boolean = false,
    /** Date filter for search (Any time, Today, This Week, etc.) */
    val searchDateFilter: DateFilter = DateFilter.AnyTime,
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
    val agendaOccurrences: ImmutableList<OccurrenceWithEvent> = persistentListOf(),
    /** Loading state for agenda */
    val isLoadingAgenda: Boolean = false,

    // === UI DIALOGS/SHEETS ===
    /** Show onboarding for first-time users */
    val showOnboardingSheet: Boolean = false,
    /** Show app info sheet */
    val showAppInfoSheet: Boolean = false,
    /** Show sync changes bottom sheet */
    val showSyncChangesSheet: Boolean = false,
    /** Sync changes for bottom sheet display */
    val syncChanges: ImmutableList<SyncChange> = persistentListOf(),
    /** Show agenda panel (upcoming events) */
    val showAgendaPanel: Boolean = false,
    /** Show year overlay for quick navigation */
    val showYearOverlay: Boolean = false,

    // === NAVIGATION EVENTS (one-shot) ===
    /** Navigate to today (consumed after use) */
    val pendingNavigateToToday: Boolean = false,
    /** Navigate to specific month (year, month) - consumed after use */
    val pendingNavigateToMonth: Pair<Int, Int>? = null,

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
    /** Sync banner message */
    val syncBannerMessage: String = "Syncing calendars...",

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
    val showErrorBanner: Boolean = false
) {
    /**
     * Format the current viewing month/year for display.
     */
    fun getMonthYearLabel(): String {
        val monthNames = listOf(
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December"
        )
        // Safe access with bounds checking (viewingMonth should always be 0-11)
        val monthName = monthNames.getOrElse(viewingMonth) { "Invalid" }
        return "$monthName $viewingYear"
    }

    /**
     * Get the key for event dots lookup.
     */
    fun getDotsKey(year: Int, month: Int): String {
        return String.format("%04d-%02d", year, month + 1)
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

    /**
     * Get count of visible events for selected day.
     * Uses Calendar.isVisible as source of truth.
     */
    fun getVisibleEventCount(): Int {
        val visibleCalendarIds = calendars.filter { it.isVisible }.map { it.id }.toSet()
        return selectedDayEvents.count { it.calendarId in visibleCalendarIds }
    }
}

/**
 * Event representing a single occurrence to display in the UI.
 * Combines event data with occurrence timing for display.
 */
data class DisplayEvent(
    val event: Event,
    val occurrence: Occurrence,
    val calendarColor: Int
) {
    val title: String get() = event.title
    val startTs: Long get() = occurrence.startTs
    val endTs: Long get() = occurrence.endTs
    val isAllDay: Boolean get() = event.isAllDay
    val location: String? get() = event.location
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
     * @param startTs Optional start timestamp for the new event
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
     * Create event from external calendar intent (ACTION_INSERT).
     * Pre-fills EventFormSheet with data from CalendarContract extras.
     *
     * Used when other apps trigger "Add to Calendar" via standard Android intents.
     * Examples: Gmail calendar invites, browser event links, etc.
     *
     * @param data Parsed intent data (title, location, times, etc.)
     * @param invitees List of invitee emails (appended to description)
     */
    data class CreateEventFromCalendarIntent(
        val data: CalendarIntentData,
        val invitees: List<String>
    ) : PendingAction()
}
