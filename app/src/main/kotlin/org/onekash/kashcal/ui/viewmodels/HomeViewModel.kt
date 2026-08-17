package org.onekash.kashcal.ui.viewmodels

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.onekash.kashcal.R
import org.onekash.kashcal.data.calendar_provider.CalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.DeviceCalendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.preferences.DefaultCalendar
import org.onekash.kashcal.data.contacts.ContactEventUtils
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.identity.canEditAsOrganizer
import org.onekash.kashcal.domain.identity.effectiveAddresses
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.di.IoDispatcher
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.model.SearchResult
import org.onekash.kashcal.domain.reader.DisplayEventRepository
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.error.CalendarError
import org.onekash.kashcal.error.ErrorActionCallback
import org.onekash.kashcal.error.ErrorMapper
import org.onekash.kashcal.error.ErrorPresentation
import org.onekash.kashcal.network.NetworkMonitor
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.onekash.kashcal.sync.scheduler.SyncStatus
import org.onekash.kashcal.sync.session.SyncTrigger
import org.onekash.kashcal.ui.components.EventFormState
import org.onekash.kashcal.ui.components.toStartEndTs
import org.onekash.kashcal.ui.components.SyncBannerState
import org.onekash.kashcal.ui.components.attendees.AttendeeStatus
import org.onekash.kashcal.ui.components.attendees.AttendeeUiModel
import org.onekash.kashcal.ui.components.generateSnackbarMessage
import org.onekash.kashcal.ui.components.hub.normalizeInitials
import org.onekash.kashcal.ui.components.weekview.WeekViewUtils
import org.onekash.kashcal.ui.model.CalendarGroup
import org.onekash.kashcal.ui.model.localizedDisplayName
import org.onekash.kashcal.ui.shared.deduplicateAndSortReminders
import org.onekash.kashcal.ui.util.DayPagerUtils
import org.onekash.kashcal.domain.whatsnew.ALL_RELEASE_NOTES
import org.onekash.kashcal.domain.whatsnew.WhatsNewGate
import org.onekash.kashcal.domain.whatsnew.WhatsNewSeeder
import org.onekash.kashcal.BuildConfig
import org.onekash.kashcal.KashCalApplication
import org.onekash.kashcal.util.DateTimeUtils
import org.onekash.kashcal.util.computeDurationString
import org.onekash.kashcal.util.importEventsToDeviceCalendar
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject

private const val TAG = "HomeViewModel"

/** Upcoming window shown by the agenda view (90 days). */
private const val AGENDA_WINDOW_MS = 90L * 24 * 60 * 60 * 1000

private data class CalendarsSnapshot(
    val calendars: List<org.onekash.kashcal.data.db.entity.Calendar>,
    val groups: List<CalendarGroup>,
    val validatedDefault: DefaultCalendar?,
    val deviceGroups: List<CalendarGroup>
)

/**
 * A half-open date range in epoch millis, used as the reactive key for the
 * range-driven event StateFlows (time-grid and agenda). Null means no active
 * range (the view is not shown), which keeps the derived Flow idle.
 */
data class EpochRange(val startMs: Long, val endMs: Long)

/**
 * Reactive UI state for the time-grid (week / 3-day / day) event surface.
 * Timed and all-day events are pre-split and sorted by start.
 */
data class WeekEventsUiState(
    val timedEvents: ImmutableList<DisplayEvent> = persistentListOf(),
    val allDayEvents: ImmutableList<DisplayEvent> = persistentListOf(),
    val isLoading: Boolean = false,
    val error: String? = null
) {
    companion object {
        val EMPTY = WeekEventsUiState()

        fun ofError(message: String?) = WeekEventsUiState(error = message ?: "Failed to load events")

        fun fromEvents(events: List<DisplayEvent>): WeekEventsUiState = WeekEventsUiState(
            timedEvents = events.filter { !it.isAllDay }.sortedBy { it.startTs }.toPersistentList(),
            allDayEvents = events.filter { it.isAllDay }.sortedBy { it.startTs }.toPersistentList(),
            isLoading = false,
            error = null
        )
    }
}

/**
 * Reactive UI state for the agenda (flat upcoming-events list). Unlike the
 * time grid, agenda shows a spinner while loading, so [isLoading] is surfaced.
 */
data class AgendaUiState(
    val events: ImmutableList<DisplayEvent> = persistentListOf(),
    val isLoading: Boolean = false
) {
    companion object {
        val EMPTY = AgendaUiState()
        val LOADING = AgendaUiState(isLoading = true)
    }
}

/** Viewing month key (0-indexed month) for the reactive full-height month grid. */
data class MonthKey(val year: Int, val month: Int)

/**
 * Day-code range covering the viewing month +/- 1 month (with grid in/out-date
 * padding), so adjacent month-pager pages have data mid-swipe. Returns
 * (startDayCode, endDayCode) in YYYYMMDD form.
 */
private fun monthGridDayCodeRange(year: Int, month: Int): Pair<Int, Int> {
    val prevMonth = LocalDate.of(year, month + 1, 1).minusMonths(1)
    val startDate = prevMonth.withDayOfMonth(1).minusDays(6)
    val nextMonth = LocalDate.of(year, month + 1, 1).plusMonths(1)
    val endDate = nextMonth.withDayOfMonth(nextMonth.lengthOfMonth()).plusDays(13)
    val startDayCode = startDate.year * 10000 + startDate.monthValue * 100 + startDate.dayOfMonth
    val endDayCode = endDate.year * 10000 + endDate.monthValue * 100 + endDate.dayOfMonth
    return startDayCode to endDayCode
}

/**
 * ViewModel for the HomeScreen (main calendar view).
 *
 * Architecture:
 * - Offline-first: All operations work locally first
 * - EventCoordinator: Single entry point for event operations
 * - EventReader: Efficient queries via occurrences table
 * - Flow-based: Reactive state with StateFlow
 *
 * Features:
 * - Month view with event dots
 * - Day selection with event list
 * - Calendar visibility filtering
 * - Search functionality
 * - Network-aware sync
 */
@HiltViewModel
class HomeViewModel(
    private val eventCoordinator: EventCoordinator,
    private val eventReader: EventReader,
    private val displayEventRepository: DisplayEventRepository,
    private val dataStore: KashCalDataStore,
    private val accountRepository: AccountRepository,
    private val syncScheduler: SyncScheduler,
    private val networkMonitor: NetworkMonitor,
    private val calendarProviderRepository: CalendarProviderRepository,
    private val attendeeBackfill: org.onekash.kashcal.domain.reader.AttendeeBackfill,
    private val contactEmailReader: org.onekash.kashcal.data.contacts.ContactEmailReader,
    private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val currentDayCodeProvider: () -> Int
) : ViewModel() {

    @Inject
    constructor(
        eventCoordinator: EventCoordinator,
        eventReader: EventReader,
        displayEventRepository: DisplayEventRepository,
        dataStore: KashCalDataStore,
        accountRepository: AccountRepository,
        syncScheduler: SyncScheduler,
        networkMonitor: NetworkMonitor,
        calendarProviderRepository: CalendarProviderRepository,
        attendeeBackfill: org.onekash.kashcal.domain.reader.AttendeeBackfill,
        contactEmailReader: org.onekash.kashcal.data.contacts.ContactEmailReader,
        @ApplicationContext context: Context,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(
        eventCoordinator,
        eventReader,
        displayEventRepository,
        dataStore,
        accountRepository,
        syncScheduler,
        networkMonitor,
        calendarProviderRepository,
        attendeeBackfill,
        contactEmailReader,
        context,
        ioDispatcher,
        { DayPagerUtils.msToDayCode(System.currentTimeMillis()) }
    )

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /**
     * Pinch-zoom hour-heights awaiting persistence. A pinch gesture calls the setter many
     * times per second; collecting this with a debounce persists only the settled zoom
     * instead of hammering DataStore. DROP_OLDEST keeps only the latest pending value.
     */
    private val hourHeightToPersist = MutableSharedFlow<Float>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Network connectivity state for UI */
    val isOnline: StateFlow<Boolean> = networkMonitor.isOnline

    /** Default reminder for timed events (minutes before) */
    val defaultReminderTimed: StateFlow<Int> = dataStore.defaultReminderMinutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 15)

    /** Default reminder for all-day events (minutes before) */
    val defaultReminderAllDay: StateFlow<Int> = dataStore.defaultAllDayReminder
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1440) // 1 day

    /** Default event duration (minutes) */
    val defaultEventDuration: StateFlow<Int> = dataStore.defaultEventDuration
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), KashCalDataStore.DEFAULT_EVENT_DURATION_MINUTES)

    /** Quick Add enabled state */
    val quickAddEnabled: StateFlow<Boolean> = dataStore.quickAddEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * Suggest prior event titles matching [prefix] for the form autocomplete.
     *
     * Honors the `titleSuggestionsEnabled` user preference: when disabled,
     * returns empty list regardless of history. UI doesn't know about this
     * preference — enforcing it here keeps the composable preference-agnostic.
     */
    suspend fun suggestTitles(prefix: String): List<org.onekash.kashcal.data.db.dao.TitleSuggestion> {
        if (!dataStore.getTitleSuggestionsEnabled()) return emptyList()
        return displayEventRepository.suggestTitles(prefix)
    }

    /** Time format preference: "system", "12h", or "24h" */
    val timeFormat: StateFlow<String> = dataStore.timeFormat
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), KashCalDataStore.TIME_FORMAT_SYSTEM)

    /**
     * App theme choice, derived from the stored theme string. Drives KashCalTheme in MainActivity.
     * A cold flow (not stateIn): the activity seeds the first frame with a synchronous read and
     * collects this, whose first emission is the same stored value — so there's no flash of the
     * default theme on cold start. Later writes propagate here to recolor live.
     */
    val themeMode: Flow<org.onekash.kashcal.ui.theme.ThemeMode> = dataStore.theme
        .map { org.onekash.kashcal.ui.theme.ThemeMode.fromPrefValue(it) }

    /** Where app colors come from (dynamic vs. accent seed); migrates legacy teal to seed. */
    val colorSource: Flow<org.onekash.kashcal.ui.theme.ColorSource> =
        combine(dataStore.colorSource, dataStore.theme) { explicit, legacyTheme ->
            org.onekash.kashcal.ui.theme.ColorSource.fromPrefValue(explicit, legacyTheme)
        }

    /** Current accent seed color (packed ARGB); meaningful when [colorSource] is SEED. */
    val accentSeed: Flow<Int> = dataStore.accentSeed

    /** First day of week preference: 0=system, 1=Sunday, 2=Monday, 7=Saturday */
    val firstDayOfWeek: StateFlow<Int> = dataStore.firstDayOfWeek
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Calendar.SUNDAY)

    /**
     * Whether the share-as-card top-right Share icon's first-time coach
     * mark has been displayed. False until first appearance, then sticky
     * true forever. Initial value matches the DataStore default (false) so
     * a slow DataStore boot does NOT silently suppress the first-time
     * tooltip on fresh installs.
     */
    val shownShareCardTooltip: StateFlow<Boolean> = dataStore.shownShareCardTooltip
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Persist that the share-card coach mark has been shown. */
    fun markShareCardTooltipShown() {
        viewModelScope.launch { dataStore.setShownShareCardTooltip(true) }
    }

    /**
     * Whether the user permanently declined contact suggestions in the
     * attendee picker. When true, the picker's contacts-permission banner is
     * never shown. Survives restart.
     */
    val contactSuggestionsDeclined: StateFlow<Boolean> = dataStore.contactSuggestionsDeclined
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Persist that the user declined contact suggestions ("No thanks" or a system-dialog denial). */
    fun declineContactSuggestions() {
        viewModelScope.launch { dataStore.setContactSuggestionsDeclined(true) }
    }

    /** Persist whether the event form's tag row sits above the notes/attendees block. */
    fun setTagsAboveNotes(above: Boolean) {
        viewModelScope.launch { dataStore.setTagsAboveNotes(above) }
    }

    /** Persist whether the Agenda view's top week bar is expanded. */
    fun setAgendaWeekBarExpanded(expanded: Boolean) {
        viewModelScope.launch { dataStore.setAgendaWeekBarExpanded(expanded) }
    }

    /** Persist whether the Day view's top week-strip date picker is expanded. */
    fun setDayWeekBarExpanded(expanded: Boolean) {
        viewModelScope.launch { dataStore.setDayWeekBarExpanded(expanded) }
    }

    /** Persist whether the time-grid all-day strip is expanded (up to 3 rows). */
    fun setAllDayRowsExpanded(expanded: Boolean) {
        viewModelScope.launch { dataStore.setAllDayRowsExpanded(expanded) }
    }

    /**
     * Persist the user's avatar initials, normalizing first so the stored value
     * is always at most two uppercase letters (or empty to clear).
     */
    fun setUserInitials(raw: String) {
        viewModelScope.launch { dataStore.setUserInitials(normalizeInitials(raw)) }
    }

    /**
     * Reactive list of pending CalDAV invitations rendered by
     * `InvitationInboxSheet`. Backs both the count Flow below and the
     * sheet's row list, so the badge can never disagree with the sheet.
     */
    val pendingInvitations: StateFlow<List<org.onekash.kashcal.domain.reader.PendingInvitation>> =
        eventReader.getPendingInvitations()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Single source of truth for the count of pending CalDAV invitations.
     *
     * The AppBar badge and the Invites overflow menu item both subscribe
     * here so a sync-churn re-emission can never drift the two views
     * apart. `distinctUntilChanged` collapses repeated same-size lists
     * (common when sync writes attendees but the NEEDS-ACTION set is
     * unchanged).
     */
    val pendingInvitationsCount: StateFlow<Int> = pendingInvitations
        .map { it.size }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // Attendee chip surfaces.

    private val quickViewEventId = MutableStateFlow<Long?>(null)
    private val formEventId = MutableStateFlow<Long?>(null)
    private val dayVisibleEventIds = MutableStateFlow<List<Long>>(emptyList())

    /** UI projection of attendees for the active QuickView event. Null when no event is active. */
    val quickViewAttendees: StateFlow<EventAttendeeUiState?> =
        quickViewEventId
            .flatMapLatest { id -> if (id == null) flowOf(null) else buildAttendeeFlow(id) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * Live event body for the active QuickView, re-read reactively by id.
     * The sheet renders this rather than the snapshot captured at tap time,
     * so an edit's new title/time/location shows even when the on-screen
     * list that produced the tapped snapshot was stale (e.g. search results,
     * which don't re-run after an edit). Null when no event is active or the
     * event was deleted.
     */
    val quickViewEventLive: StateFlow<Event?> =
        quickViewEventId
            .flatMapLatest { id -> if (id == null) flowOf(null) else eventReader.getEventByIdFlow(id) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** UI projection of attendees for the EventFormSheet's read-only chip row. */
    val formAttendees: StateFlow<EventAttendeeUiState?> =
        formEventId
            .flatMapLatest { id -> if (id == null) flowOf(null) else buildAttendeeFlow(id) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * Drives the EventFormSheet's read-only banner + Save-disable gate.
     * True when the editing event has an ORGANIZER that doesn't match the
     * resolving account (single home of the rule via
     * [org.onekash.kashcal.domain.identity.canEditAsOrganizer]).
     */
    @Suppress("OPT_IN_USAGE")
    val formIsReadOnly: StateFlow<Boolean> =
        formEventId
            .flatMapLatest { id ->
                if (id == null) flowOf(false) else flow {
                    val event = eventReader.getEventById(id)
                    val calendar = event?.let { e ->
                        uiState.value.calendars.firstOrNull { it.id == e.calendarId }
                    }
                    val account = calendar?.accountId?.let { accountRepository.getAccountById(it) }
                    emit(
                        event != null && !account.canEditAsOrganizer(event)
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * UI projection map for the day-view chip badges. One Flow per visible-
     * event-set (no per-card subscription); slice by event ID at render time.
     * Each slice carries the per-event resolved account so [AttendeeUiModel.isYou]
     * is correct.
     */
    val dayAttendees: StateFlow<Map<Long, List<AttendeeUiModel>>> =
        dayVisibleEventIds
            .flatMapLatest { ids -> buildDayAttendeesFlow(ids) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun setQuickViewEventId(eventId: Long?) {
        quickViewEventId.value = eventId
    }

    fun setFormEventId(eventId: Long?) {
        formEventId.value = eventId
    }

    fun setVisibleEventIds(ids: List<Long>) {
        dayVisibleEventIds.value = ids
    }

    // Time-grid (week / 3-day / day) reactive event surface.
    //
    // The visible date range is the single key; navigation SETS it, and the
    // derived [weekEvents] StateFlow re-queries the reactive repository Flow
    // whenever the key changes OR the underlying data changes. This removes the
    // whole class of "a manual reload didn't fire" staleness (issue #297): a
    // create/edit/delete/sync propagates automatically because
    // [DisplayEventRepository.getDisplayEventsForRange] is itself reactive.
    //
    // A null key means "no time-grid range active" (the user is in another
    // view), which keeps the upstream Flow idle — mirroring the null-key gate
    // used by the attendee surfaces above.
    private val timeGridRange = MutableStateFlow<EpochRange?>(null)

    /** Sets/updates the visible time-grid range; null clears it (view left). */
    private fun setTimeGridRange(range: EpochRange?) {
        timeGridRange.value = range
    }

    /**
     * Reactive week / 3-day / day time-grid events, split into timed and
     * all-day and sorted by start, with error folded in. Collected by the
     * time-grid UI; stays live while subscribed and re-emits on any DB write
     * within the active range.
     *
     * Deliberately does NOT emit a loading/empty state on range change (unlike
     * [agendaEvents]): the grid renders its structure immediately and keeps the
     * last events visible until the new range resolves, so an intermediate
     * empty emission would blank the grid mid-swipe.
     */
    @Suppress("OPT_IN_USAGE")
    val weekEvents: StateFlow<WeekEventsUiState> =
        timeGridRange
            .flatMapLatest { range ->
                if (range == null) {
                    flowOf(WeekEventsUiState.EMPTY)
                } else {
                    displayEventRepository.getDisplayEventsForRange(range.startMs, range.endMs)
                        .map { events -> WeekEventsUiState.fromEvents(events) }
                        .catch { e ->
                            if (e is CancellationException) throw e
                            Log.e(TAG, "Error loading time-grid events", e)
                            emit(WeekEventsUiState.ofError(e.message))
                        }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WeekEventsUiState.EMPTY)

    // Agenda reactive event surface — same range-key mechanism as the time
    // grid, but a flat 90-day window set on entering the agenda view and
    // nulled on leaving (so the upstream Flow — and any device-calendar query
    // — goes idle when agenda isn't shown).
    private val agendaRange = MutableStateFlow<EpochRange?>(null)

    /** Sets/updates the agenda window; null clears it (view left). */
    private fun setAgendaRange(range: EpochRange?) {
        agendaRange.value = range
    }

    /**
     * Reactive agenda events (upcoming [AGENDA_WINDOW_MS]). Emits a loading
     * state while the query is in flight (agenda shows a spinner), then the
     * merged Room + device list. Stays live while subscribed and re-emits on
     * any DB write within the window.
     */
    @Suppress("OPT_IN_USAGE")
    val agendaEvents: StateFlow<AgendaUiState> =
        agendaRange
            .flatMapLatest { range ->
                if (range == null) {
                    flowOf(AgendaUiState.EMPTY)
                } else {
                    displayEventRepository.getDisplayEventsForRange(range.startMs, range.endMs)
                        .map { events -> AgendaUiState(events = events, isLoading = false) }
                        .onStart { emit(AgendaUiState.LOADING) }
                        .catch { e ->
                            if (e is CancellationException) throw e
                            Log.e(TAG, "Error observing agenda", e)
                            emit(AgendaUiState.EMPTY)
                        }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AgendaUiState.EMPTY)

    // Full-height month grid reactive surface — key is the viewing (year,
    // month), set only while MONTH_FULL is active and nulled otherwise. The
    // repository returns events already grouped by day code. The month/year
    // event DOTS are intentionally NOT reactive here: they use a one-shot
    // grouped-by-day query and are refreshed via reloadCurrentView.
    private val monthGridKey = MutableStateFlow<MonthKey?>(null)

    /** Sets/updates the full-height month grid key; null clears it (view left). */
    private fun setMonthGridKey(key: MonthKey?) {
        monthGridKey.value = key
    }

    /**
     * Reactive full-height month grid events, grouped by day code. Stays live
     * while subscribed and re-emits on any DB write within the 3-month window.
     */
    @Suppress("OPT_IN_USAGE")
    val monthEvents: StateFlow<ImmutableMap<Int, ImmutableList<DisplayEvent>>> =
        monthGridKey
            .flatMapLatest { key ->
                if (key == null) {
                    flowOf(persistentMapOf())
                } else {
                    val (startDayCode, endDayCode) = monthGridDayCodeRange(key.year, key.month)
                    displayEventRepository.getDisplayEventsForDateRange(startDayCode, endDayCode)
                        .catch { e ->
                            if (e is CancellationException) throw e
                            Log.e(TAG, "Error loading month grid events", e)
                            emit(persistentMapOf())
                        }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), persistentMapOf())

    /**
     * Resolve the active event → calendar → account, run one-shot
     * `rawIcal` backfill (closes the etag-unchanged-skip gap from
     * inbound persistence — when the table is empty but `rawIcal` has
     * ATTENDEE lines, parse + persist), then subscribe to the attendees
     * Flow with the resolved account so [AttendeeUiModel.fromRoom] can
     * mark the current user as `isYou`.
     */
    private fun buildAttendeeFlow(eventId: Long): Flow<EventAttendeeUiState?> = flow {
        val event = eventReader.getEventById(eventId)
        val calendar = event?.let { e ->
            uiState.value.calendars.firstOrNull { it.id == e.calendarId }
        }
        val account = calendar?.accountId?.let { accountRepository.getAccountById(it) }

        try {
            attendeeBackfill.backfillIfEmpty(eventId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "backfillIfEmpty failed for event $eventId: ${e.javaClass.simpleName}")
        }

        emitAll(
            eventReader.getAttendeesForEvent(eventId).map { rows ->
                EventAttendeeUiState(
                    models = AttendeeUiModel.fromRoom(
                        attendees = rows,
                        currentAccount = account,
                        organizerAddress = event?.organizerEmail,
                        organizerName = event?.organizerName
                    ),
                    isCurrentUserOnList = AttendeeUiModel.isCurrentUserOnList(
                        rows, account, event?.organizerEmail
                    )
                )
            }
        )
    }

    /**
     * Bulk projection for the day pager. Fans out per-event account/organizer
     * resolution once (synchronous on cached `uiState.calendars` + a small
     * batched `accountsDao` lookup) and then maps every emission of the
     * attendees Flow through [AttendeeUiModel.fromRoom] so the day-card
     * badge can correctly show "Going / Pending / Hosting / off-list" per
     * event without the consumer re-resolving identity.
     */
    private fun buildDayAttendeesFlow(eventIds: List<Long>): Flow<Map<Long, List<AttendeeUiModel>>> = flow {
        if (eventIds.isEmpty()) {
            emit(emptyMap())
            return@flow
        }

        val events = eventReader.getEventsByIds(eventIds)
        val calendarsById = uiState.value.calendars.associateBy { it.id }
        val accountIds = events.values.mapNotNull { calendarsById[it.calendarId]?.accountId }.toSet()
        val accountsById = accountIds
            .mapNotNull { id -> accountRepository.getAccountById(id)?.let { id to it } }
            .toMap()

        emitAll(
            eventReader.getAttendeesForEvents(eventIds).map { rowsByEventId ->
                rowsByEventId.mapValues { (eventId, rows) ->
                    val event = events[eventId]
                    val calendar = event?.let { calendarsById[it.calendarId] }
                    val account = calendar?.accountId?.let { accountsById[it] }
                    AttendeeUiModel.fromRoom(
                        attendees = rows,
                        currentAccount = account,
                        organizerAddress = event?.organizerEmail,
                        organizerName = event?.organizerName
                    )
                }
            }
        )
    }

    // Track if startup sync has been triggered
    private var hasTriggeredStartupSync = false

    // Job for search debouncing (cancel previous search when new query arrives)
    private var searchJob: Job? = null

    // Job for on-demand dots loading (cancel previous on fast swipe)
    private var loadDotsJob: Job? = null

    // Job for occurrence extension (cancel previous on rapid swipe)
    private var extensionJob: Job? = null
    private var occurrenceRepairDone = false

    // Job for day events cache loading (cancel previous when cache refresh needed)
    private var dayEventsCacheJob: Job? = null

    // Job for debounced day pager loading (cancel previous on fast swipe)
    private var dayPagerLoadJob: Job? = null

    // Job for year dots loading (cancel previous on fast year swipe)
    private var yearDotsJob: Job? = null

    // Track current loaded date range to avoid redundant loads
    private var currentLoadedRange: Pair<LocalDate, LocalDate>? = null

    // Suppress sync indicator for silent syncs (cold start, resume, force full sync with banner)
    // Only pull-to-refresh shows the spinning icon since it's user-initiated
    private var suppressSyncIndicator = false

    // Null until the first resume, so the first resume is record-only —
    // we only snap when we have a prior dayCode to compare against.
    private var lastResumeDayCode: Int? = null

    init {
        Log.d(TAG, "ViewModel init")

        // Set initial viewing state to today
        val today = Calendar.getInstance()
        _uiState.update {
            it.copy(
                viewingMonth = today.get(Calendar.MONTH),
                viewingYear = today.get(Calendar.YEAR)
            )
        }

        // Initialize asynchronously
        viewModelScope.launch {
            initializeAsync()
        }

        // Observe sync status for inline banner
        observeSyncStatus()

        // Observe sync changes for snackbar notification
        observeSyncChanges()

        // Observe display settings
        observeDisplaySettings()

        // Observe device calendar changes to invalidate event dots cache
        observeDeviceCalendarChanges()

        // Persist the settled pinch-zoom level across restarts (debounced)
        observeHourHeightPersistence()
    }

    /**
     * Async initialization - Android recommended pattern.
     * Avoids blocking main thread during startup.
     */
    private suspend fun initializeAsync() {
        try {
            Log.d(TAG, "initializeAsync - START")

            // Start observing calendars (reactive Flow - auto-updates when calendars change)
            // Note: Calendar visibility is derived from Calendar.isVisible (DB source of truth)
            observeCalendars()

            // Observe device calendar drawer state (enabled, visible IDs, calendar list)
            observeDeviceCalendarDrawerState()

            // Check if any sync-capable account is configured
            checkAccountStatus()

            // Show onboarding sheet if: not configured AND not dismissed before
            if (!_uiState.value.isConfigured) {
                val dismissed = dataStore.onboardingDismissed.first()
                if (!dismissed) {
                    Log.d(TAG, "Showing onboarding sheet (first launch, no account configured)")
                    _uiState.update { it.copy(showOnboardingSheet = true) }
                }
            }

            // What's New: surface release notes the user hasn't acknowledged.
            // Onboarding takes the screen on first launch — defer to it; the
            // sheet will appear on the next cold start. Also silently records
            // the current version on the very first launch (lastShown == 0)
            // so future upgrades are detected.
            initializeWhatsNew()

            // Load persisted view from DataStore before building UI.
            // Seed previousNonInsightsMode from the same persisted default so back-from-Insights
            // (when Insights is the initial view) lands on the user's preferred view, not MONTH.
            // DataStore's VALID_VIEWS rejects "insights", so this seed is guaranteed non-INSIGHTS.
            val defaultView = ViewMode.fromKey(dataStore.getDefaultCalendarView())
            // Seed the persisted time-grid scroll position in the SAME update that flips
            // viewMode. viewMode starts at MONTH, so the time grid (WeekViewContent) can't
            // compose until this update lands — meaning the restored value is already present
            // on its first composition and the debounced scroll writer can't overwrite it first.
            val savedScrollMinutes = dataStore.getWeekViewScrollMinutes()
            // Seed the persisted zoom in the SAME update as the scroll minutes. The grid's
            // scroll restore converts saved clock-minutes to pixels using the hour-height, so
            // the restored zoom must be present on the grid's first composition — otherwise
            // the conversion runs against the default zoom and lands on the wrong time. Reject
            // a non-finite stored value (coerceIn leaves NaN as NaN) and clamp the rest so a
            // corrupt/out-of-range stored value can never render a degenerate grid.
            val storedHourHeight = dataStore.getWeekViewHourHeight()
            val savedHourHeight = (if (storedHourHeight.isFinite()) storedHourHeight else 60f)
                .coerceIn(WeekViewUtils.MIN_HOUR_HEIGHT_DP, WeekViewUtils.MAX_HOUR_HEIGHT_DP)
            _uiState.update {
                it.copy(
                    viewMode = defaultView,
                    previousNonInsightsMode = defaultView,
                    weekViewSavedScrollMinutes = savedScrollMinutes,
                    weekViewHourHeight = savedHourHeight
                )
            }

            // Load data for the default view
            when (defaultView) {
                ViewMode.AGENDA -> {
                    val now = System.currentTimeMillis()
                    setAgendaRange(EpochRange(now, now + AGENDA_WINDOW_MS))
                }
                ViewMode.DAY -> {} // goToToday() below handles week initialization
                ViewMode.THREE_DAYS -> {} // goToToday() below handles week initialization
                ViewMode.WEEK -> {} // goToToday() below handles week initialization
                ViewMode.MONTH -> {} // goToToday() below handles dot loading + day selection
                ViewMode.MONTH_FULL -> setMonthGridKey(MonthKey(_uiState.value.viewingYear, _uiState.value.viewingMonth))
                ViewMode.YEAR -> loadYearDots(_uiState.value.viewingYear)
                ViewMode.INSIGHTS -> {}
            }

            // Build event dots for current month ±6 months
            val today = Calendar.getInstance()
            buildEventDots(today.get(Calendar.YEAR), today.get(Calendar.MONTH))

            // Auto-select today (routes to correct view based on viewMode).
            // Cold-start land is instant (no animation) so the month pager settles
            // in one frame; the user-pressed Today button keeps its animation.
            goToToday(animate = false)

            Log.d(TAG, "initializeAsync - COMPLETE")
        } catch (e: Exception) {
            Log.e(TAG, "initializeAsync FAILED", e)
            _uiState.update {
                it.copy(syncMessage = "Initialization failed: ${e.message}")
            }
        }
    }

    // ==================== Account Status ====================

    /**
     * Check if any sync-capable account is configured and update state.
     * Considers all providers with supportsCalDAV (iCloud, CalDAV).
     */
    private suspend fun checkAccountStatus() {
        val allAccounts = withContext(ioDispatcher) {
            accountRepository.getAllAccounts()
        }
        val syncableAccounts = allAccounts.filter { it.provider.supportsCalDAV }

        val hasConfiguredAccount = syncableAccounts.any { account ->
            withContext(ioDispatcher) { accountRepository.hasCredentials(account.id) }
        }

        _uiState.update {
            it.copy(isConfigured = hasConfiguredAccount)
        }

        if (hasConfiguredAccount) {
            Log.d(TAG, "Account configured (${syncableAccounts.size} syncable accounts)")
        } else {
            Log.d(TAG, "No configured accounts")
        }
    }

    /**
     * Refresh account status (called when returning from settings).
     * Also reloads calendars to pick up any newly discovered calendars.
     */
    fun refreshAccountStatus() {
        viewModelScope.launch {
            checkAccountStatus()

            // Reload calendars to pick up newly discovered calendars
            // (observeCalendars Flow should auto-update, but force refresh for safety)
            loadCalendars()

            if (_uiState.value.isConfigured && !hasTriggeredStartupSync) {
                // First sync after account setup - show banner for user feedback
                hasTriggeredStartupSync = true
                suppressSyncIndicator = true  // Has banner - no spinning icon needed
                syncScheduler.setShowBannerForSync(true)  // Initial setup - user expects confirmation
                Log.d(TAG, "refreshAccountStatus: First sync after account setup (with banner, no icon)")
                performSync()
            }

            // Rebuild event dots with new calendars
            reloadCurrentView()
        }
    }

    // ==================== Startup Sync ====================

    /**
     * Trigger startup sync after UI is ready.
     * Called from Activity's LaunchedEffect to ensure lifecycle is STARTED.
     */
    fun triggerStartupSync() {
        if (!_uiState.value.isConfigured) {
            Log.d(TAG, "triggerStartupSync: Not configured, skipping")
            return
        }
        if (hasTriggeredStartupSync) {
            Log.d(TAG, "triggerStartupSync: Already triggered, skipping")
            return
        }
        hasTriggeredStartupSync = true
        suppressSyncIndicator = true  // Silent cold start - no spinning icon
        syncScheduler.setShowBannerForSync(false)
        Log.d(TAG, "triggerStartupSync: Starting sync (silent, no icon)")
        performSync(SyncTrigger.FOREGROUND_APP_OPEN)
    }

    // ==================== Sync Status Observation ====================

    /**
     * Observe sync status from WorkManager and update banner state.
     *
     * Banner visibility is context-aware (controlled by syncScheduler.showBannerForSync):
     * - Silent syncs (startup, pull-to-refresh): no banner shown
     * - Verbose syncs (force full sync, iCloud setup): full banner shown
     * - Errors: always shown regardless of flag
     */
    private fun observeSyncStatus() {
        viewModelScope.launch {
            syncScheduler.observeImmediateSyncStatus().collect { status ->
                val showBanner = syncScheduler.showBannerForSync.value
                Log.d(TAG, "Sync status changed: $status (showBanner=$showBanner)")
                when (status) {
                    is SyncStatus.Running, is SyncStatus.Enqueued -> {
                        // Only show icon if not suppressed (only pull-to-refresh shows icon)
                        // Only show banner if flag is set (force sync, iCloud setup)
                        _uiState.update {
                            it.copy(
                                isSyncing = !suppressSyncIndicator,
                                showSyncBanner = showBanner,
                                syncBannerState = if (status is SyncStatus.Running)
                                    SyncBannerState.Syncing else SyncBannerState.Preparing,
                                syncErrorDetail = null
                            )
                        }
                    }
                    is SyncStatus.Succeeded -> {
                        suppressSyncIndicator = false  // Reset flag for next sync
                        occurrenceRepairDone = false
                        val hasPartialError = status.errorMessage != null
                        _uiState.update {
                            it.copy(
                                isSyncing = false,
                                showSyncBanner = showBanner || hasPartialError,
                                syncBannerState = if (hasPartialError)
                                    SyncBannerState.PartialError else SyncBannerState.Success,
                                syncErrorDetail = null
                            )
                        }
                        // Reload events after successful sync
                        reloadCurrentView()
                        // Auto-dismiss after delay
                        if (showBanner || hasPartialError) {
                            delay(if (hasPartialError) 3000 else 2000)
                            _uiState.update { it.copy(showSyncBanner = false) }
                            syncScheduler.resetBannerFlag()
                        }
                    }
                    is SyncStatus.Failed -> {
                        suppressSyncIndicator = false  // Reset flag for next sync
                        // Always show errors regardless of flag
                        _uiState.update {
                            it.copy(
                                isSyncing = false,
                                showSyncBanner = true,
                                syncBannerState = SyncBannerState.Error,
                                syncErrorDetail = status.errorMessage
                            )
                        }
                        // Auto-dismiss after 3 seconds
                        delay(3000)
                        _uiState.update { it.copy(showSyncBanner = false) }
                        syncScheduler.resetBannerFlag()
                    }
                    is SyncStatus.Idle, is SyncStatus.Cancelled, is SyncStatus.Blocked -> {
                        suppressSyncIndicator = false  // Reset flag for next sync
                        _uiState.update {
                            it.copy(
                                showSyncBanner = false,
                                isSyncing = false,
                                syncBannerState = SyncBannerState.Syncing  // Reset to avoid stale Error flash
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Observe sync changes from SyncScheduler and show snackbar notification.
     *
     * Shows snackbar for ALL syncs (startup, pull-to-refresh, background) when changes are found.
     * The snackbar includes a "View" action to open the bottom sheet with change details.
     */
    private fun observeSyncChanges() {
        viewModelScope.launch {
            syncScheduler.lastSyncChanges.collect { changes ->
                if (changes.isNotEmpty()) {
                    val message = generateSnackbarMessage(changes)
                    if (message != null) {
                        Log.d(TAG, "Sync changes notification: $message (${changes.size} changes)")
                        // Store changes for bottom sheet
                        _uiState.update { it.copy(syncChanges = changes.toPersistentList()) }
                        // Show snackbar with "View" action
                        showSnackbar(message) {
                            // Open bottom sheet on "View" tap
                            _uiState.update { it.copy(showSyncChangesSheet = true) }
                        }
                    }
                    // Clear after consumed
                    syncScheduler.clearSyncChanges()
                }
            }
        }
    }

    /**
     * Observe display settings preferences.
     * Updates uiState when showEventEmojis, timeFormat, or firstDayOfWeek preferences change.
     */
    private fun observeDisplaySettings() {
        viewModelScope.launch {
            dataStore.showEventEmojis.collect { showEmojis ->
                _uiState.update { it.copy(showEventEmojis = showEmojis) }
            }
        }
        viewModelScope.launch {
            dataStore.timeFormat.collect { format ->
                _uiState.update { it.copy(timeFormat = format) }
            }
        }
        viewModelScope.launch {
            dataStore.firstDayOfWeek.collect { day ->
                _uiState.update { it.copy(firstDayOfWeek = day) }
            }
        }
        viewModelScope.launch {
            dataStore.showWeekNumbers.collect { show ->
                _uiState.update { it.copy(showWeekNumbers = show) }
            }
        }
        viewModelScope.launch {
            dataStore.agendaWeekBarExpanded.collect { expanded ->
                _uiState.update { it.copy(agendaWeekBarExpanded = expanded) }
            }
        }
        viewModelScope.launch {
            dataStore.dayWeekBarExpanded.collect { expanded ->
                _uiState.update { it.copy(dayWeekBarExpanded = expanded) }
            }
        }
        viewModelScope.launch {
            dataStore.allDayRowsExpanded.collect { expanded ->
                _uiState.update { it.copy(allDayRowsExpanded = expanded) }
            }
        }
        viewModelScope.launch {
            dataStore.tagsAboveNotes.collect { above ->
                _uiState.update { it.copy(tagsAboveNotes = above) }
            }
        }
        viewModelScope.launch {
            dataStore.userInitials.collect { initials ->
                _uiState.update { it.copy(userInitials = initials) }
            }
        }
        viewModelScope.launch {
            eventReader.getRecentCategories().collect { tags ->
                _uiState.update { it.copy(categorySuggestions = tags.toPersistentList()) }
            }
        }
        viewModelScope.launch {
            eventReader.observeTagColors().collect { colors ->
                _uiState.update { it.copy(tagColors = colors.toPersistentMap()) }
            }
        }
    }

    /**
     * Observe device calendar changes (ContentObserver signal from CalendarProviderManager).
     * Invalidates event dots cache so dots rebuild with fresh device event data.
     * Day pager, agenda, and week view auto-update via DisplayEventRepository's combine() flows.
     */
    private fun observeDeviceCalendarChanges() {
        viewModelScope.launch {
            displayEventRepository.deviceCalendarChangeSignal
                .collect { signal ->
                    if (signal > 0) {
                        // Clear loaded months so dots rebuild with fresh data
                        _uiState.update {
                            it.copy(
                                loadedMonths = persistentSetOf(),
                                eventDots = persistentMapOf()
                            )
                        }
                        // Rebuild dots for current viewing month
                        buildEventDots(
                            _uiState.value.viewingYear,
                            _uiState.value.viewingMonth
                        )
                    }
                }
        }
    }

    // ==================== Sync Operations ====================

    /**
     * Pull-to-refresh sync.
     */
    fun refreshSync() {
        if (!_uiState.value.isConfigured) {
            Log.d(TAG, "Pull-to-refresh: not configured, showing snackbar")
            showSnackbar("No sync accounts configured")
            return
        }
        if (_uiState.value.isSyncing) {
            Log.d(TAG, "Sync already in progress, ignoring refresh")
            return
        }
        if (!networkMonitor.isOnline.value) {
            Log.d(TAG, "Pull-to-refresh: offline, showing error")
            showError(CalendarError.Network.Offline)
            return
        }
        suppressSyncIndicator = false  // User-initiated - show spinning icon
        syncScheduler.setShowBannerForSync(false)
        Log.d(TAG, "Pull-to-refresh: starting sync (with icon)")
        performSync(SyncTrigger.FOREGROUND_PULL_TO_REFRESH)
        // Pull-to-refresh also refreshes CardDAV contacts; the worker self-guards to
        // contact-sync-enabled accounts, so an unconditional sweep here is cheap and safe.
        syncScheduler.requestImmediateContactSync()
    }

    /**
     * Force full sync (clears sync tokens).
     */
    fun forceFullSync() {
        if (_uiState.value.isSyncing) {
            Log.d(TAG, "Sync already in progress, ignoring force sync")
            return
        }
        suppressSyncIndicator = true  // Has banner - no spinning icon needed
        syncScheduler.setShowBannerForSync(true)
        Log.d(TAG, "Force full sync requested (with banner, no icon)")

        // Clear parse failure retry state - force sync gives a fresh start (v16.7.0)
        viewModelScope.launch {
            dataStore.clearAllParseFailureRetries()
        }

        syncScheduler.requestImmediateSync(forceFullSync = true, trigger = SyncTrigger.FOREGROUND_MANUAL)
    }

    /**
     * Sync on app resume if not already syncing.
     * Called from Activity.onResume() for background-to-foreground transitions.
     *
     * No cooldown - syncs every time app resumes because:
     * - Casual users have long gaps (hours) between app opens anyway
     * - The ctag check is lightweight (~50ms) if nothing changed
     * - Shared calendar users need fresh data when returning to app
     */
    fun syncOnResumeIfNeeded() {
        if (!_uiState.value.isConfigured) {
            Log.d(TAG, "syncOnResumeIfNeeded: Not configured, skipping")
            return
        }
        if (_uiState.value.isSyncing) {
            Log.d(TAG, "syncOnResumeIfNeeded: Already syncing, skipping")
            return
        }
        Log.d(TAG, "syncOnResumeIfNeeded: Triggering sync on app resume")
        suppressSyncIndicator = true  // Silent sync - no spinning icon
        syncScheduler.setShowBannerForSync(false)
        performSync(SyncTrigger.FOREGROUND_APP_OPEN)
    }

    /**
     * Perform sync operation.
     *
     * Sets isSyncing=true immediately for duplicate sync guard, then enqueues WorkManager work.
     * All other state updates (isSyncing=false, reloadCurrentView) happen via observeSyncStatus()
     * when WorkManager emits SyncStatus.Succeeded/Failed/etc.
     *
     * @param trigger The sync trigger source for history tracking
     */
    private fun performSync(trigger: SyncTrigger = SyncTrigger.FOREGROUND_MANUAL) {
        if (!_uiState.value.isConfigured) {
            Log.d(TAG, "performSync: Not configured, skipping")
            return
        }

        // Set isSyncing immediately to prevent duplicate sync requests (race condition guard)
        // This closes the window between performSync() and observeSyncStatus() receiving Running status
        // The UI indicator is controlled separately by observeSyncStatus() using suppressSyncIndicator
        _uiState.update { it.copy(isSyncing = true) }

        // Request sync - observeSyncStatus() handles all other state updates
        // including calling reloadCurrentView() when sync succeeds
        Log.d(TAG, "performSync: Requesting immediate sync (trigger=${trigger.name}, showIcon=${!suppressSyncIndicator})")
        syncScheduler.requestImmediateSync(trigger = trigger)
    }

    // ==================== Calendar Loading ====================

    /**
     * Start observing calendars from database (reactive via Flow).
     * Uses EventCoordinator for proper architecture pattern.
     *
     * Default calendar priority:
     * 1. User preference from DataStore (set in Settings)
     * 2. Database is_default column (server-side default)
     * 3. First calendar in list
     */
    private fun observeCalendars() {
        viewModelScope.launch {
            try {
                combine(
                    eventCoordinator.getAllCalendars(),
                    eventCoordinator.getAllAccounts(),
                    dataStore.defaultCalendar,
                    dataStore.deviceCalendarsEnabled,
                    dataStore.enabledDeviceCalendarIds
                ) { calendars, accounts, userPrefDefault, deviceEnabled, enabledIds ->
                    val validatedDefault = when (userPrefDefault) {
                        is DefaultCalendar.Room -> {
                            if (calendars.any { it.id == userPrefDefault.calendarId }) userPrefDefault
                            else null
                        }
                        is DefaultCalendar.Device -> userPrefDefault
                        null -> null
                    }
                    val groups = CalendarGroup.fromCalendarsAndAccounts(
                        calendars,
                        accounts,
                        localLabel = context.getString(R.string.drawer_account_offline),
                        icsLabel = context.getString(R.string.subscriptions_title),
                        localizeCalendarName = { it.localizedDisplayName(context.resources) }
                    )
                    val deviceCalendars = loadFilteredDeviceCalendars(deviceEnabled, enabledIds)
                    val deviceGroups = CalendarGroup.fromDeviceCalendars(deviceCalendars, writableOnly = true)
                    CalendarsSnapshot(calendars, groups, validatedDefault, deviceGroups)
                }.collect { snap ->
                    _uiState.update {
                        it.copy(
                            calendars = snap.calendars.toPersistentList(),
                            calendarGroups = snap.groups.toPersistentList(),
                            deviceCalendarGroups = snap.deviceGroups.toPersistentList(),
                            defaultCalendar = snap.validatedDefault
                        )
                    }
                    Log.d(TAG, "Calendars updated: ${snap.calendars.size} calendars, ${snap.groups.size} groups, ${snap.deviceGroups.size} device groups, default=${snap.validatedDefault}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error observing calendars", e)
            }
        }
    }

    /**
     * Apply the user's device-calendar enable preference to the system list:
     * returns empty unless the master toggle is on AND at least one calendar
     * is enabled in DataStore. Mirrors the drawer's behavior so all surfaces
     * stay symmetric.
     */
    private suspend fun loadFilteredDeviceCalendars(
        enabled: Boolean,
        enabledIds: Set<Long>
    ): List<DeviceCalendar> {
        if (!enabled || enabledIds.isEmpty()) return emptyList()
        return try {
            calendarProviderRepository.getDeviceCalendars().filter { it.id in enabledIds }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Load all calendars from database (one-shot for manual refresh).
     * Uses same default calendar priority as observeCalendars().
     */
    private fun loadCalendars() {
        viewModelScope.launch {
            try {
                val snap = withContext(ioDispatcher) {
                    val cals = eventCoordinator.getAllCalendars().first()
                    val accounts = eventCoordinator.getAllAccounts().first()
                    val userPrefDefault = dataStore.getDefaultCalendar()
                    val validDefault = when (userPrefDefault) {
                        is DefaultCalendar.Room -> {
                            if (cals.any { it.id == userPrefDefault.calendarId }) userPrefDefault
                            else null
                        }
                        is DefaultCalendar.Device -> userPrefDefault
                        null -> null
                    }
                    val calGroups = CalendarGroup.fromCalendarsAndAccounts(
                        cals,
                        accounts,
                        localLabel = context.getString(R.string.drawer_account_offline),
                        icsLabel = context.getString(R.string.subscriptions_title),
                        localizeCalendarName = { it.localizedDisplayName(context.resources) }
                    )
                    val deviceEnabled = dataStore.getDeviceCalendarsEnabled()
                    val enabledIds = dataStore.getEnabledDeviceCalendarIds()
                    val deviceCalendars = loadFilteredDeviceCalendars(deviceEnabled, enabledIds)
                    val deviceGroups = CalendarGroup.fromDeviceCalendars(deviceCalendars, writableOnly = true)
                    CalendarsSnapshot(cals, calGroups, validDefault, deviceGroups)
                }

                _uiState.update {
                    it.copy(
                        calendars = snap.calendars.toPersistentList(),
                        calendarGroups = snap.groups.toPersistentList(),
                        deviceCalendarGroups = snap.deviceGroups.toPersistentList(),
                        defaultCalendar = snap.validatedDefault
                    )
                }
                Log.d(TAG, "Loaded ${snap.calendars.size} calendars, ${snap.groups.size} groups, ${snap.deviceGroups.size} device groups, default=${snap.validatedDefault}")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading calendars", e)
            }
        }
    }

    /**
     * Observe device calendar drawer state: feature enabled, enabled IDs, hidden IDs.
     * Loads device calendar list only when enabled/enabledIds change (ContentResolver query).
     * Hidden IDs updates skip the query since only visibility state changes.
     */
    private fun observeDeviceCalendarDrawerState() {
        // Observe enabled state + enabled IDs — reload calendar list from ContentProvider
        viewModelScope.launch {
            combine(
                dataStore.deviceCalendarsEnabled,
                dataStore.enabledDeviceCalendarIds
            ) { enabled, enabledIds ->
                Pair(enabled, enabledIds)
            }.collect { (enabled, enabledIds) ->
                val deviceCalendars = loadFilteredDeviceCalendars(enabled, enabledIds)
                _uiState.update {
                    it.copy(
                        deviceCalendarsEnabled = enabled,
                        enabledDeviceCalendars = deviceCalendars.toPersistentList()
                    )
                }
            }
        }
        // Observe hidden IDs separately — lightweight state update, no ContentProvider query
        viewModelScope.launch {
            dataStore.hiddenDeviceCalendarIds.collect { hiddenIds ->
                _uiState.update {
                    it.copy(hiddenDeviceCalendarIds = hiddenIds.toPersistentSet())
                }
            }
        }
    }

    /**
     * Refresh calendars list.
     */
    fun refreshCalendars() {
        loadCalendars()
    }

    // ==================== Calendar Visibility ====================

    /**
     * Toggle calendar visibility.
     * Uses DB Calendar.isVisible as source of truth.
     */
    fun toggleCalendarVisibility(calendarId: Long) {
        viewModelScope.launch {
            // Get current visibility from calendar entity
            val calendar = _uiState.value.calendars.find { it.id == calendarId }
            val newVisible = !(calendar?.isVisible ?: true)

            // Update DB (source of truth) - UI updates automatically via calendars Flow observation
            eventCoordinator.setCalendarVisibility(calendarId, newVisible)

            // Only rebuild dots (one-shot query needs explicit refresh)
            // Week/agenda/pager/day views are now reactive via combine() - they auto-update
            buildEventDots(_uiState.value.viewingYear, _uiState.value.viewingMonth)
        }
    }

    /**
     * Show all calendars.
     * Uses DB Calendar.isVisible as source of truth.
     */
    fun showAllCalendars() {
        viewModelScope.launch {
            // Update DB for each calendar (source of truth)
            _uiState.value.calendars.forEach { calendar ->
                eventCoordinator.setCalendarVisibility(calendar.id, true)
            }
            // Only rebuild dots (one-shot query needs explicit refresh)
            // Week/agenda/pager/day views are now reactive via combine() - they auto-update
            buildEventDots(_uiState.value.viewingYear, _uiState.value.viewingMonth)
        }
    }

    /**
     * Toggle device calendar visibility in the drawer.
     * Uses hiddenDeviceCalendarIds preference — doesn't affect reminders or enablement.
     */
    fun toggleDeviceCalendarVisibility(calendarId: Long) {
        viewModelScope.launch {
            dataStore.toggleDeviceCalendarHidden(calendarId)
            reloadCurrentView()
        }
    }

    // ==================== Event Dots ====================

    /**
     * Encode year and month into a single integer for range comparison.
     * Format: year * 12 + month (handles year boundaries correctly)
     */
    private fun encodeMonth(year: Int, month: Int): Int = year * 12 + month

    /**
     * Decode encoded month back to year and month.
     */
    private fun decodeMonth(encoded: Int): Pair<Int, Int> = (encoded / 12) to (encoded % 12)

    /**
     * Check if a month has actually loaded dots (not just requested).
     * Uses Set-based tracking to avoid false cache hits from cancelled loads.
     */
    private fun isMonthCached(year: Int, month: Int): Boolean {
        val encoded = encodeMonth(year, month)
        return encoded in _uiState.value.loadedMonths
    }

    /**
     * Ensure dots are loaded for the given month.
     * Loads on-demand if not cached.
     */
    private fun ensureDotsForMonth(year: Int, month: Int) {
        if (!isMonthCached(year, month)) {
            loadDotsForMonth(year, month)
        }
    }

    /**
     * Load dots for a single month (on-demand loading for months beyond initial cache).
     * Cancels previous load if still running (handles fast swipe).
     */
    private fun loadDotsForMonth(year: Int, month: Int) {
        // Cancel previous load if still running (fast swipe scenario)
        loadDotsJob?.cancel()

        loadDotsJob = viewModelScope.launch {
            try {
                // month is 0-indexed (Calendar.MONTH), LocalDate uses 1-indexed
                val firstDay = LocalDate.of(year, month + 1, 1)
                val lastDay = firstDay.withDayOfMonth(firstDay.lengthOfMonth())
                val startDayCode = firstDay.year * 10000 + firstDay.monthValue * 100 + firstDay.dayOfMonth
                val endDayCode = lastDay.year * 10000 + lastDay.monthValue * 100 + lastDay.dayOfMonth

                val eventsMap = withContext(ioDispatcher) {
                    displayEventRepository.getDisplayEventsGroupedByDayOnce(startDayCode, endDayCode)
                }

                val monthKey = String.format(java.util.Locale.ROOT, "%04d-%02d", year, month + 1)
                val monthDots = mutableMapOf<Int, MutableList<Int>>()

                for ((dayCode, events) in eventsMap) {
                    val (occYear, occMonth, day) = parseDayFormat(dayCode)
                    // Multi-day events spanning a month boundary expand into dayCodes
                    // from both months; ignore the ones outside the loaded month so
                    // a Dec 31→Jan 1 event doesn't paint a phantom dot on day 1 of
                    // the wrong month.
                    if (occYear != year || occMonth != month) continue
                    val dayColors = monthDots.getOrPut(day) { mutableListOf() }
                    for (event in events) {
                        val color = (event.eventColor ?: event.calendarColor).takeIf { it != 0 } ?: 0xFF6200EE.toInt()
                        if (!dayColors.contains(color)) {
                            dayColors.add(color)
                        }
                    }
                }

                // Merge into existing cache
                val currentDots = _uiState.value.eventDots.toMutableMap()
                currentDots[monthKey] = monthDots.mapValues { it.value.toPersistentList() }.toPersistentMap()

                // Mark month as actually loaded (not just requested)
                // This ensures cancelled loads don't falsely mark months as cached
                val loadedMonthEncoded = encodeMonth(year, month)
                _uiState.update {
                    it.copy(
                        eventDots = currentDots.toPersistentMap(),
                        loadedMonths = it.loadedMonths.add(loadedMonthEncoded)
                    )
                }

                Log.d(TAG, "Loaded dots for $year-${month + 1}, total cached months: ${_uiState.value.loadedMonths.size}")
            } catch (e: CancellationException) {
                throw e  // Don't catch cancellation
            } catch (e: Exception) {
                Log.e(TAG, "Error loading dots for month $year-${month + 1}", e)
            }
        }
    }

    /**
     * Build event dots for ±6 months around the given month.
     */
    private fun buildEventDots(year: Int, month: Int) {
        viewModelScope.launch {
            try {
                val dots = mutableMapOf<String, MutableMap<Int, MutableList<Int>>>()

                // Calculate cache range bounds
                val centerEncoded = encodeMonth(year, month)
                val startEncoded = centerEncoded - 6
                val endEncoded = centerEncoded + 6

                // Compute day code range from ±6 months
                val (startYear, startMonth) = decodeMonth(startEncoded)
                val (endYear, endMonth) = decodeMonth(endEncoded)
                val firstDay = LocalDate.of(startYear, startMonth + 1, 1)
                val lastDay = LocalDate.of(endYear, endMonth + 1, 1)
                    .withDayOfMonth(LocalDate.of(endYear, endMonth + 1, 1).lengthOfMonth())
                val startDayCode = firstDay.year * 10000 + firstDay.monthValue * 100 + firstDay.dayOfMonth
                val endDayCode = lastDay.year * 10000 + lastDay.monthValue * 100 + lastDay.dayOfMonth

                // Query merged Room + device events grouped by day
                val eventsMap = withContext(ioDispatcher) {
                    displayEventRepository.getDisplayEventsGroupedByDayOnce(startDayCode, endDayCode)
                }

                // Build dots from pre-grouped events (multi-day expansion already handled)
                for ((dayCode, events) in eventsMap) {
                    val (occYear, occMonth, day) = parseDayFormat(dayCode)
                    val key = String.format(java.util.Locale.ROOT, "%04d-%02d", occYear, occMonth + 1)

                    val monthMap = dots.getOrPut(key) { mutableMapOf() }
                    val dayColors = monthMap.getOrPut(day) { mutableListOf() }
                    for (event in events) {
                        val color = (event.eventColor ?: event.calendarColor).takeIf { it != 0 } ?: 0xFF6200EE.toInt()
                        if (!dayColors.contains(color)) {
                            dayColors.add(color)
                        }
                    }
                }

                // Convert to persistent immutable collections
                val immutableDots = dots.mapValues { (_, monthMap) ->
                    monthMap.mapValues { (_, dayColors) -> dayColors.toPersistentList() }.toPersistentMap()
                }.toPersistentMap()

                // Build set of loaded months (all months in the ±6 range)
                val loadedMonthsSet = (startEncoded..endEncoded)
                    .toSet()
                    .toPersistentSet()

                // Update state with dots and loaded months set
                _uiState.update {
                    it.copy(
                        eventDots = immutableDots,
                        loadedMonths = loadedMonthsSet
                    )
                }

                Log.d(TAG, "Built event dots for ${dots.size} months, loaded ${loadedMonthsSet.size} months: $startYear-${startMonth + 1} to $endYear-${endMonth + 1}")
            } catch (e: CancellationException) {
                throw e  // Don't catch cancellation
            } catch (e: Exception) {
                Log.e(TAG, "Error building event dots", e)
            }
        }
    }

    // ==================== Year View Dots ====================

    /**
     * Load event dots for an entire year (Jan 1 to Dec 31).
     * Cancels previous load if still running (fast-swipe protection).
     * Merges into existing eventDots map (additive, not replacement).
     */
    private fun loadYearDots(year: Int) {
        yearDotsJob?.cancel()

        yearDotsJob = viewModelScope.launch {
            try {
                val startDayCode = year * 10000 + 101   // Jan 1
                val endDayCode = year * 10000 + 1231    // Dec 31

                val eventsMap = withContext(ioDispatcher) {
                    displayEventRepository.getDisplayEventsGroupedByDayOnce(startDayCode, endDayCode)
                }

                val dots = mutableMapOf<String, MutableMap<Int, MutableList<Int>>>()

                for ((dayCode, events) in eventsMap) {
                    val (occYear, occMonth, day) = parseDayFormat(dayCode)
                    val key = String.format(java.util.Locale.ROOT, "%04d-%02d", occYear, occMonth + 1)

                    val monthMap = dots.getOrPut(key) { mutableMapOf() }
                    val dayColors = monthMap.getOrPut(day) { mutableListOf() }
                    for (event in events) {
                        val color = (event.eventColor ?: event.calendarColor).takeIf { it != 0 } ?: 0xFF6200EE.toInt()
                        if (!dayColors.contains(color)) {
                            dayColors.add(color)
                        }
                    }
                }

                // Merge into existing cache (additive — month view dots unaffected)
                val currentDots = _uiState.value.eventDots.toMutableMap()
                for ((key, monthMap) in dots) {
                    currentDots[key] = monthMap.mapValues { it.value.toPersistentList() }.toPersistentMap()
                }

                _uiState.update {
                    it.copy(
                        eventDots = currentDots.toPersistentMap(),
                        loadedYears = it.loadedYears.add(year)
                    )
                }

                Log.d(TAG, "Loaded year dots for $year, ${dots.size} months with events")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error loading year dots for $year", e)
            }
        }
    }

    /**
     * Ensure dots are loaded for the given year.
     * Loads on-demand if not cached.
     */
    fun ensureDotsForYear(year: Int) {
        if (year !in _uiState.value.loadedYears) {
            loadYearDots(year)
        }
    }

    // ==================== Navigation ====================

    /**
     * Navigate to today and select it.
     * Context-aware: If in 3-day view, navigates week view to today.
     *
     * @param animate when true (user-initiated, e.g. the Today button) the month
     *   pager animates its scroll; when false (programmatic cold-start land) it
     *   jumps instantly so the pager settles in a single frame. Only the
     *   MONTH/MONTH_FULL branch distinguishes the two — other views are unaffected.
     */
    fun goToToday(animate: Boolean = true) {
        when (_uiState.value.viewMode) {
            ViewMode.DAY, ViewMode.THREE_DAYS, ViewMode.WEEK -> {
                goToTodayWeek()
            }
            ViewMode.AGENDA -> {
                _uiState.update { it.copy(pendingScrollAgendaToTop = true) }
            }
            ViewMode.MONTH, ViewMode.MONTH_FULL -> {
                val today = Calendar.getInstance()
                val year = today.get(Calendar.YEAR)
                val month = today.get(Calendar.MONTH)

                _uiState.update {
                    it.copy(
                        viewingYear = year,
                        viewingMonth = month,
                        pendingNavigateToToday = animate,
                        pendingNavigateToTodayInstant = !animate
                    )
                }

                if (_uiState.value.viewMode == ViewMode.MONTH_FULL) {
                    setMonthGridKey(MonthKey(year, month))
                }

                selectDate(today.timeInMillis)
            }
            ViewMode.YEAR -> {
                _uiState.update { it.copy(pendingNavigateToToday = true) }
            }
            ViewMode.INSIGHTS -> {}
        }
    }

    /**
     * Clear the navigate to today flag (consumed by UI).
     */
    fun clearNavigateToToday() {
        _uiState.update { it.copy(pendingNavigateToToday = false) }
    }

    /**
     * Clear the instant navigate to today flag (consumed by UI).
     */
    fun clearNavigateToTodayInstant() {
        _uiState.update { it.copy(pendingNavigateToTodayInstant = false) }
    }

    /**
     * Navigate calendar to a specific date.
     * Updates viewing month/year and selects the date.
     * Used by week widget for "go to date" action.
     *
     * @param date The target date to navigate to
     */
    fun navigateToDate(date: LocalDate) {
        // Update viewing month (handles cross-month navigation)
        _uiState.update {
            it.copy(
                viewingYear = date.year,
                viewingMonth = date.monthValue - 1,  // 0-indexed
                pendingNavigateToMonth = date.year to (date.monthValue - 1)
            )
        }

        // Select the date (triggers day events load)
        val dateMs = date.atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        selectDate(dateMs)
    }

    /**
     * Clear the scroll agenda to top flag (consumed by UI).
     */
    fun clearScrollAgendaToTop() {
        _uiState.update { it.copy(pendingScrollAgendaToTop = false) }
    }

    /**
     * Navigate to a specific month.
     */
    fun navigateToMonth(year: Int, month: Int) {
        _uiState.update {
            it.copy(
                viewingYear = year,
                viewingMonth = month,
                pendingNavigateToMonth = year to month,
                showYearOverlay = false  // Auto-dismiss year overlay on month selection
            )
        }

        // Only load if outside cached range (not full rebuild!)
        ensureDotsForMonth(year, month)
    }

    /**
     * Clear the navigate to month flag (consumed by UI).
     */
    fun clearNavigateToMonth() {
        _uiState.update { it.copy(pendingNavigateToMonth = null) }
    }

    /**
     * Set the viewing month/year (called on swipe).
     */
    fun setViewingMonth(year: Int, month: Int) {
        _uiState.update {
            it.copy(
                viewingYear = year,
                viewingMonth = month
            )
        }

        // Load dots if outside cached range (on-demand loading) — skip in MONTH_FULL mode (the month grid has full data)
        if (_uiState.value.viewMode != ViewMode.MONTH_FULL) {
            ensureDotsForMonth(year, month)
        }

        // Load full month events for full-height grid
        if (_uiState.value.viewMode == ViewMode.MONTH_FULL) {
            setMonthGridKey(MonthKey(year, month))
        }

        // Trigger occurrence extension if navigating far into future (debounced)
        triggerOccurrenceExtension(year, month)
    }

    /**
     * Trigger on-demand occurrence extension with debouncing.
     * When user navigates far into the future, extends occurrences for recurring events
     * that don't have occurrences generated that far ahead.
     *
     * Debouncing prevents extension spam when user swipes rapidly through months.
     */
    private fun triggerOccurrenceExtension(year: Int, month: Int) {
        extensionJob?.cancel()
        extensionJob = viewModelScope.launch {
            delay(500L)  // Debounce rapid swipes

            try {
                val targetMs = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, 1)
                }.timeInMillis

                val (forwardExtended, pastExtended, repaired) = withContext(ioDispatcher) {
                    val forward = eventCoordinator.extendOccurrencesIfNeeded(targetMs)
                    val past = eventCoordinator.extendPastOccurrencesIfNeeded(targetMs)
                    val repair = if (!occurrenceRepairDone) {
                        eventCoordinator.repairMissingOccurrences()
                    } else 0
                    Triple(forward, past, repair)
                }

                if (repaired == 0) occurrenceRepairDone = true
                if (forwardExtended > 0 || pastExtended > 0 || repaired > 0) {
                    Log.d(TAG, "Extended occurrences: $forwardExtended forward, $pastExtended past, $repaired repaired (navigated to $year-${month + 1})")
                    loadDotsForMonth(year, month)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to extend occurrences: ${e.message}")
            }
        }
    }

    // ==================== Week View Navigation ====================

    /** Navigate backward in the day/week pager (step depends on view mode). */
    fun navigateDaysPagerPrevious() {
        val currentPage = _uiState.value.weekViewPagerPosition
        if (currentPage <= 0) return
        val step = _uiState.value.viewMode.pagerNextStep ?: return
        val targetPage = currentPage - step
        _uiState.update { it.copy(pendingWeekViewPagerPosition = targetPage) }
        onDayPagerPageChanged(targetPage)
    }

    /** Navigate forward in the day/week pager (step depends on view mode). */
    fun navigateDaysPagerNext() {
        val currentPage = _uiState.value.weekViewPagerPosition
        val step = _uiState.value.viewMode.pagerNextStep ?: return
        val targetPage = currentPage + step
        _uiState.update { it.copy(pendingWeekViewPagerPosition = targetPage) }
        onDayPagerPageChanged(targetPage)
    }

    /**
     * Navigate week view to today.
     * Uses CENTER_WEEK_PAGE for WEEK mode, CENTER_DAY_PAGE for THREE_DAYS.
     */
    fun goToTodayWeek() {
        val targetPage = if (_uiState.value.viewMode == ViewMode.WEEK)
            WeekViewUtils.CENTER_WEEK_PAGE else WeekViewUtils.CENTER_DAY_PAGE

        // Clear cached range to force reload
        currentLoadedRange = null

        // Set pending navigation and trigger load
        _uiState.update {
            it.copy(pendingWeekViewPagerPosition = targetPage)
        }
        onDayPagerPageChanged(targetPage)
    }

    // ==================== Infinite Day Pager Functions ====================

    /**
     * Called when the day pager page changes (user swipes or animates).
     * Debounces loading to avoid rapid API calls during fast swipes.
     *
     * @param currentPage The current (leftmost visible) page in the pager
     */
    fun onDayPagerPageChanged(currentPage: Int) {
        // Update pager position immediately for FAB context
        _uiState.update { it.copy(weekViewPagerPosition = currentPage) }

        // Cancel previous debounce job
        dayPagerLoadJob?.cancel()
        dayPagerLoadJob = viewModelScope.launch {
            // Debounce: wait for scroll to settle
            delay(300)

            // Get visible and loading date ranges (week mode uses week pages, day mode uses day pages)
            val isWeekMode = _uiState.value.viewMode == ViewMode.WEEK
            val firstDayOfWeek = _uiState.value.firstDayOfWeek
            val (visibleStart, visibleEnd) = if (isWeekMode) {
                val start = WeekViewUtils.weekPageToStartDate(currentPage, firstDayOfWeek)
                start to start.plusDays(6)
            } else {
                WeekViewUtils.getVisibleDateRange(currentPage)
            }
            val (loadStart, loadEnd) = if (isWeekMode) {
                // Load current week + 1 week buffer on each side
                val start = WeekViewUtils.weekPageToStartDate(currentPage, firstDayOfWeek)
                start.minusDays(7) to start.plusDays(13)
            } else {
                WeekViewUtils.getLoadingDateRange(currentPage)
            }

            // Skip if range already loaded
            currentLoadedRange?.let { (loadedStart, loadedEnd) ->
                if (visibleStart >= loadedStart && visibleEnd <= loadedEnd) {
                    Log.d(TAG, "Day pager: range already loaded, skipping")
                    return@launch
                }
            }

            // Load events for new range
            Log.d(TAG, "Day pager: loading range $loadStart to $loadEnd")
            loadEventsForDateRange(loadStart, loadEnd)
            currentLoadedRange = loadStart to loadEnd
        }
    }

    /**
     * Compute the start timestamp for a new event created from the time-grid
     * FAB: today's date at the next hour (current hour + 1, on the hour),
     * matching the non-time-grid FAB default. The grid's scroll position and
     * zoom are view-state (they restore where the grid was looking) and are
     * intentionally NOT used to seed a new event.
     */
    fun computeTimeGridEventSeedTs(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, (get(Calendar.HOUR_OF_DAY) + 1) % 24)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    /**
     * Load events for a date range (used by infinite day pager).
     * Accepts any date range. Sets the reactive time-grid range key; the
     * [weekEvents] StateFlow does the actual (reactive) loading.
     *
     * @param startDate First day to load (inclusive)
     * @param endDate Last day to load (inclusive)
     */
    private fun loadEventsForDateRange(startDate: LocalDate, endDate: LocalDate) {
        val startMs = WeekViewUtils.dateToEpochMs(startDate)
        val endMs = WeekViewUtils.dateToEpochMs(endDate.plusDays(1)) // exclusive end

        // Drive the reactive time-grid surface (weekEvents StateFlow).
        setTimeGridRange(EpochRange(startMs, endMs))
    }

    /**
     * Navigate infinite day pager to today (CENTER_DAY_PAGE).
     * Returns the target page for the pager to scroll to.
     */
    fun goToTodayInDayPager(): Int {
        val targetPage = if (_uiState.value.viewMode == ViewMode.WEEK)
            WeekViewUtils.CENTER_WEEK_PAGE else WeekViewUtils.CENTER_DAY_PAGE

        // Clear cached range to force reload
        currentLoadedRange = null

        // Trigger immediate load for today's range
        onDayPagerPageChanged(targetPage)

        return targetPage
    }

    /**
     * Navigate infinite day pager to a specific date.
     * Returns the target page for the pager to scroll to.
     *
     * @param dateMs Date in epoch milliseconds
     */
    fun navigateDayPagerToDate(dateMs: Long): Int {
        val date = WeekViewUtils.epochMsToDate(dateMs)
        val targetPage = if (_uiState.value.viewMode == ViewMode.WEEK)
            WeekViewUtils.dateToWeekPage(date, _uiState.value.firstDayOfWeek)
        else WeekViewUtils.dateToPage(date)

        // Clear cached range to force reload
        currentLoadedRange = null

        // Trigger immediate load
        onDayPagerPageChanged(targetPage)

        return targetPage
    }

    /**
     * Save week view scroll position for in-session state preservation (pixels, in-memory only).
     */
    fun setWeekViewScrollPosition(position: Int) {
        _uiState.update { it.copy(weekViewScrollPosition = position) }
    }

    /**
     * Persist the time-grid scroll position as minutes from midnight so it survives app
     * restart. Stored as clock time (not pixels) so pinch-zoom between sessions still restores
     * to the same time. Written on a longer debounce than the in-session pixel path.
     */
    fun setWeekViewScrollMinutes(minutesOfDay: Int) {
        viewModelScope.launch {
            dataStore.setWeekViewScrollMinutes(minutesOfDay)
        }
    }

    fun setWeekViewHourHeight(height: Float) {
        val clamped = height.coerceIn(WeekViewUtils.MIN_HOUR_HEIGHT_DP, WeekViewUtils.MAX_HOUR_HEIGHT_DP)
        _uiState.update { it.copy(weekViewHourHeight = clamped) }
        // Queue the clamped zoom for debounced persistence so it survives app restart.
        hourHeightToPersist.tryEmit(clamped)
    }

    /**
     * Persist the settled pinch-zoom level. Debounced so an active pinch (many emits/sec)
     * results in one DataStore write of the final zoom rather than one per frame; the seed
     * in [initializeAsync] restores it on cold launch.
     */
    private fun observeHourHeightPersistence() {
        viewModelScope.launch {
            @OptIn(FlowPreview::class)
            hourHeightToPersist
                .debounce(1000)
                .distinctUntilChanged()
                .collect { dataStore.setWeekViewHourHeight(it) }
        }
    }

    /**
     * Save week view pager position for context-aware FAB.
     */
    fun setWeekViewPagerPosition(position: Int) {
        _uiState.update { it.copy(weekViewPagerPosition = position) }
    }

    /**
     * Show week view date picker dialog.
     */
    fun showWeekViewDatePicker() {
        _uiState.update { it.copy(showWeekViewDatePicker = true) }
    }

    /**
     * Hide week view date picker dialog.
     */
    fun hideWeekViewDatePicker() {
        _uiState.update { it.copy(showWeekViewDatePicker = false) }
    }

    /**
     * Handle date selection from week view date picker.
     * Navigates the infinite day pager to the selected date.
     */
    fun onWeekViewDateSelected(dateMs: Long) {
        hideWeekViewDatePicker()

        // Convert date to page in infinite pager (mode-aware)
        val date = WeekViewUtils.epochMsToDate(dateMs)
        val targetPage = if (_uiState.value.viewMode == ViewMode.WEEK)
            WeekViewUtils.dateToWeekPage(date, _uiState.value.firstDayOfWeek)
        else WeekViewUtils.dateToPage(date)

        // Clear cached range to force reload
        currentLoadedRange = null

        // Set pending navigation and trigger load
        _uiState.update {
            it.copy(pendingWeekViewPagerPosition = targetPage)
        }
        onDayPagerPageChanged(targetPage)
    }

    /**
     * Drill into a single day from a week/3-day column header. The mode switch
     * must land first so the navigation resolves against the DAY pager rather
     * than the week pager it was called from.
     */
    fun onWeekViewDayHeaderClick(date: LocalDate) {
        // Transient: drilling in shouldn't change what the app opens in.
        setViewMode(ViewMode.DAY, persist = false)
        onWeekViewDateSelected(WeekViewUtils.dateToEpochMs(date))
    }

    /**
     * Clear pending pager position after it has been consumed by the UI.
     */
    fun clearPendingWeekViewPagerPosition() {
        _uiState.update { it.copy(pendingWeekViewPagerPosition = null) }
    }

    // ==================== Day Selection ====================

    /**
     * Select a date and load its events.
     *
     * The incoming timestamp may carry a time-of-day (e.g. cold-start passes a
     * wall-clock Calendar.getInstance(), an event start, etc.). We normalize to
     * that calendar day's local midnight so every selectedDate writer agrees on
     * one representation — the day pager's page math, month sync, and dot
     * highlighting all key off the calendar day, and a time-bearing value would
     * otherwise force a redundant rewrite when those midnight-based paths echo
     * back. 0L is the "no selection" sentinel and is left untouched.
     */
    fun selectDate(dateMillis: Long) {
        val normalized = if (dateMillis == 0L) {
            0L
        } else {
            Instant.ofEpochMilli(dateMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }
        _uiState.update {
            it.copy(
                selectedDate = normalized,
                selectedDayLabel = formatDateLabel(normalized)
            )
        }
    }

    // ==================== Day Detail Sheet ====================

    fun showDayDetail(dateMs: Long) {
        _uiState.update {
            it.copy(showDayDetailSheet = true, dayDetailDate = dateMs)
        }
    }

    fun dismissDayDetail() {
        _uiState.update {
            it.copy(showDayDetailSheet = false, dayDetailDate = 0L)
        }
    }

    // ==================== Day Pager Cache ====================

    /**
     * Load events for a 7-day range centered on the given date.
     * Used by the day swipe pager for smooth scrolling.
     *
     * Groups events by dayCode for O(1) lookup per page.
     * Uses Flow for reactive updates when events change.
     *
     * @param centerDateMs Center date of the range (epoch millis)
     */
    fun loadEventsForDayPagerRange(centerDateMs: Long) {
        dayEventsCacheJob?.cancel()

        Log.d(TAG, "Day pager cache: loading range centered on ${DayPagerUtils.msToDayCode(centerDateMs)}")

        dayEventsCacheJob = viewModelScope.launch {
            try {
                // DisplayEventRepository merges Room + device calendar events,
                // handles multi-day expansion, grouping by dayCode, and sorting
                displayEventRepository.getDisplayEventsForDayRange(centerDateMs)
                    .collect { grouped ->
                        // Track which dayCodes were loaded (even if empty)
                        val loadedCodes = (-3..3).map { offset ->
                            DayPagerUtils.msToDayCode(centerDateMs + (offset * DayPagerUtils.DAY_MS))
                        }.toPersistentSet()

                        _uiState.update {
                            it.copy(
                                dayEventsCache = grouped,
                                cacheRangeCenter = centerDateMs,
                                loadedDayCodes = loadedCodes
                            )
                        }

                        Log.d(TAG, "Day pager cache: loaded events across ${grouped.size} days")
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error loading day pager cache", e)
            }
        }
    }

    // ==================== Month Events (Full-Height Grid) ====================

    /**
     * Check if the day pager cache needs to be refreshed.
     *
     * Returns true if:
     * - Cache is empty (cacheRangeCenter == 0)
     * - Current date is more than 1 day from cache center
     *
     * @param currentDateMs Current page date (epoch millis)
     * @return true if cache should be refreshed
     */
    fun shouldRefreshDayPagerCache(currentDateMs: Long): Boolean {
        val cacheCenter = _uiState.value.cacheRangeCenter
        if (cacheCenter == 0L) return true

        val distanceFromCenter = kotlin.math.abs(currentDateMs - cacheCenter)
        // Refresh when more than 1 day from center (leaves 2-day buffer on each side)
        return distanceFromCenter > DayPagerUtils.DAY_MS
    }

    /**
     * Format date for display (e.g., "December 17, 2024").
     */
    private fun formatDateLabel(dateMillis: Long): String {
        val format = SimpleDateFormat(DateTimeUtils.localizedPattern("yMMMMd"), Locale.getDefault())
        return format.format(dateMillis)
    }

    // ==================== Search ====================

    /**
     * Activate search mode.
     */
    fun activateSearch() {
        _uiState.update {
            it.copy(
                isSearchActive = true,
                searchQuery = "",
                searchResults = persistentListOf(),
                searchDateFilter = DateFilter.Upcoming,
                showSearchDatePicker = false,
                searchDateRangeStart = null
            )
        }
    }

    /**
     * Deactivate search mode.
     * Resets all search state including date filter.
     */
    fun deactivateSearch() {
        _uiState.update {
            it.copy(
                isSearchActive = false,
                searchQuery = "",
                searchResults = persistentListOf(),
                searchDateFilter = DateFilter.Upcoming,
                showSearchDatePicker = false,
                searchDateRangeStart = null
            )
        }
    }

    /**
     * Update search query with debouncing.
     * Cancels any pending search and waits 300ms before executing.
     */
    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }

        // Cancel any pending search
        searchJob?.cancel()

        if (query.length >= 2) {
            searchJob = viewModelScope.launch {
                delay(300)  // 300ms debounce
                performSearch(query)
            }
        } else {
            _uiState.update { it.copy(searchResults = persistentListOf()) }
        }
    }

    // ==================== Search Date Filter ====================

    /**
     * Set the search date filter and re-run search.
     * Called when user taps a filter chip or selects a date from picker.
     */
    fun setSearchDateFilter(filter: DateFilter) {
        _uiState.update {
            it.copy(
                searchDateFilter = filter,
                showSearchDatePicker = false,  // Auto-dismiss picker on selection
                searchDateRangeStart = null    // Reset range selection
            )
        }

        // Re-run search with new filter
        if (_uiState.value.searchQuery.length >= 2) {
            performSearch(_uiState.value.searchQuery)
        }
    }

    /**
     * Show the search date picker bottom sheet.
     */
    fun showSearchDatePicker() {
        _uiState.update {
            it.copy(
                showSearchDatePicker = true,
                searchDateRangeStart = null  // Reset range selection when opening
            )
        }
    }

    /**
     * Hide the search date picker bottom sheet.
     */
    fun hideSearchDatePicker() {
        _uiState.update {
            it.copy(
                showSearchDatePicker = false,
                searchDateRangeStart = null  // Reset range selection
            )
        }
    }

    /**
     * Handle date selection in the search date picker.
     *
     * Implements single-tap / double-tap behavior for date selection:
     * - First tap: Stores date as range start
     * - Second tap on same date: Creates SingleDay filter
     * - Second tap on different date: Creates CustomRange filter
     *
     * @param dateMs Selected date in epoch milliseconds
     */
    fun onSearchDateSelected(dateMs: Long) {
        val rangeStart = _uiState.value.searchDateRangeStart

        if (rangeStart == null) {
            // First tap - store as range start
            _uiState.update { it.copy(searchDateRangeStart = dateMs) }
        } else {
            // Second tap - determine if single day or range
            val normalizedStart = normalizeToMidnight(rangeStart)
            val normalizedEnd = normalizeToMidnight(dateMs)

            val filter = if (normalizedStart == normalizedEnd) {
                // Same day - single day filter
                DateFilter.SingleDay(dateMs)
            } else {
                // Different days - create range (ensure start <= end)
                val (start, end) = if (normalizedStart <= normalizedEnd) {
                    normalizedStart to normalizedEnd
                } else {
                    normalizedEnd to normalizedStart
                }
                DateFilter.CustomRange(start, end)
            }

            setSearchDateFilter(filter)
        }
    }

    /**
     * Normalize timestamp to midnight (start of day) in system timezone.
     */
    private fun normalizeToMidnight(epochMs: Long): Long {
        val instant = Instant.ofEpochMilli(epochMs)
        val localDate = instant.atZone(ZoneId.systemDefault()).toLocalDate()
        return localDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    /**
     * Perform search query.
     *
     * Uses occurrences table for time filtering (Android's recommended approach).
     * An event is included if it has ANY occurrence that hasn't ended yet.
     * This correctly handles multi-day events in progress and recurring events.
     *
     * When a date filter is active, uses searchEventsInRange() to combine FTS
     * text matching with occurrence date range filtering.
     */
    private fun performSearch(query: String) {
        viewModelScope.launch {
            try {
                val dateFilter = _uiState.value.searchDateFilter
                val timeRange = dateFilter.getTimeRange(ZoneId.systemDefault(), _uiState.value.firstDayOfWeek)
                val calendarMap = _uiState.value.calendars.associateBy { it.id }

                // Compute day code range for device calendar search
                val today = LocalDate.now()
                val todayCode = today.year * 10000 + today.monthValue * 100 + today.dayOfMonth
                val (searchStartDayCode, searchEndDayCode) = when {
                    timeRange != null -> {
                        DayPagerUtils.msToDayCode(timeRange.first) to DayPagerUtils.msToDayCode(timeRange.second)
                    }
                    dateFilter is DateFilter.AnyTime -> {
                        val syncPastDays = dataStore.syncPastDays.first()
                        val pastDate = if (syncPastDays == Int.MAX_VALUE) {
                            today.minusYears(10)  // Practical upper bound for device calendar
                        } else {
                            today.minusDays(syncPastDays.toLong())
                        }
                        val futureDate = today.plusYears(2)
                        (pastDate.year * 10000 + pastDate.monthValue * 100 + pastDate.dayOfMonth) to
                            (futureDate.year * 10000 + futureDate.monthValue * 100 + futureDate.dayOfMonth)
                    }
                    else -> {
                        val futureDate = today.plusYears(2)
                        todayCode to (futureDate.year * 10000 + futureDate.monthValue * 100 + futureDate.dayOfMonth)
                    }
                }

                // Room search lambda: wraps EventReader methods, converts to SearchResult
                val roomSearcher: suspend (String) -> List<SearchResult> = { q ->
                    val ewnoResults = when {
                        timeRange != null -> eventReader.searchEventsInRangeWithNextOccurrence(q, timeRange.first, timeRange.second)
                        dateFilter is DateFilter.AnyTime -> eventReader.searchEventsWithNextOccurrence(q)
                        else -> eventReader.searchEventsExcludingPastWithNextOccurrence(q)
                    }
                    ewnoResults.map { ewno ->
                        val event = ewno.event
                        val calendar = calendarMap[event.calendarId]
                        val syntheticOcc = Occurrence(
                            eventId = event.id,
                            calendarId = event.calendarId,
                            startTs = event.startTs,
                            endTs = event.endTs,
                            startDay = DateTimeUtils.eventTsToDayCode(event.startTs, event.isAllDay),
                            endDay = DateTimeUtils.eventTsToEndDayCode(
                                endTs = event.endTs,
                                startTs = event.startTs,
                                isAllDay = event.isAllDay
                            ),
                            isCancelled = false,
                            exceptionEventId = null
                        )
                        SearchResult(
                            displayEvent = DisplayEvent.Room(event, syntheticOcc, calendar),
                            displayTs = ewno.nextOccurrenceTs ?: event.startTs
                        )
                    }
                }

                // Merge Room + device results via DisplayEventRepository
                val results = withContext(ioDispatcher) {
                    displayEventRepository.searchDisplayEvents(
                        query, searchStartDayCode, searchEndDayCode, roomSearcher
                    )
                }

                // Filter by visible calendars (using Calendar.isVisible as source of truth)
                val visibleCalendarIds = _uiState.value.calendars
                    .filter { it.isVisible }
                    .map { it.id }
                    .toSet()
                val filteredResults = results.filter { result ->
                    when (val de = result.displayEvent) {
                        is DisplayEvent.Room -> de.event.calendarId in visibleCalendarIds
                        is DisplayEvent.Device -> true // already filtered by CalendarProviderRepository
                    }
                }

                _uiState.update { it.copy(searchResults = filteredResults.toPersistentList()) }

                Log.d(TAG, "Search '$query' returned ${filteredResults.size} results (filter=${dateFilter::class.simpleName})")
            } catch (e: Exception) {
                Log.e(TAG, "Search error", e)
            }
        }
    }

    // ==================== UI Sheets/Dialogs ====================

    fun toggleAppInfoSheet() {
        _uiState.update { it.copy(showAppInfoSheet = !it.showAppInfoSheet) }
    }

    fun openShareAvailabilitySheet() {
        _uiState.update { it.copy(showShareAvailabilitySheet = true) }
    }

    fun dismissShareAvailabilitySheet() {
        _uiState.update { it.copy(showShareAvailabilitySheet = false) }
    }

    fun openInvitationInbox() {
        _uiState.update { it.copy(isInvitationInboxOpen = true) }
    }

    fun dismissInvitationInbox() {
        _uiState.update { it.copy(isInvitationInboxOpen = false) }
    }

    fun toggleOnboardingSheet() {
        _uiState.update { it.copy(showOnboardingSheet = !it.showOnboardingSheet) }
    }

    fun dismissOnboardingSheet() {
        viewModelScope.launch {
            // Persist first so a process death between UI clear and write
            // can't re-show the sheet on next launch.
            try {
                dataStore.setOnboardingDismissed(true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to persist onboarding dismissal", e)
            }
            _uiState.update { it.copy(showOnboardingSheet = false) }
        }
    }

    /**
     * If DataStore has no record yet (default 0), seed it from the
     * application-level upgrade signal so existing users from before this
     * feature shipped see release-note content for any release they
     * upgraded into. True fresh installs still record current and stay
     * silent. After seeding, run the gate against authored releases.
     *
     * DataStore IO failures must never propagate from a viewModelScope.launch
     * — they would escape to Looper.main and crash the app on cold start.
     */
    private suspend fun initializeWhatsNew() {
        try {
            val current = BuildConfig.VERSION_CODE
            val initialDsLastShown = dataStore.getLastWhatsNewVersionShown()
            val seedValue = if (initialDsLastShown == 0) {
                val prefs = context.getSharedPreferences(KashCalApplication.PREFS_NAME, Context.MODE_PRIVATE)
                val prevVersion = prefs.getInt(KashCalApplication.KEY_PREVIOUS_VERSION, 0)
                WhatsNewSeeder.decideSeed(initialDsLastShown, prevVersion, current)
            } else {
                null
            }
            val effectiveLastShown = if (seedValue != null) {
                dataStore.setLastWhatsNewVersionShown(seedValue)
                seedValue
            } else {
                initialDsLastShown
            }
            val toShow = WhatsNewGate.releasesToShow(
                releases = ALL_RELEASE_NOTES,
                lastShownVersion = effectiveLastShown,
                currentVersion = current,
            )
            if (toShow.isNotEmpty()) {
                _uiState.update { it.copy(whatsNewReleases = toShow.toPersistentList()) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize What's New state", e)
        }
    }

    fun dismissWhatsNewSheet() {
        // Re-entry guard: ModalBottomSheet's onDismissRequest can fire
        // multiple times during the dismiss animation. The empty-list check
        // makes a second call a no-op so we don't launch duplicate writes.
        if (_uiState.value.whatsNewReleases.isEmpty()) return
        viewModelScope.launch {
            // Persist first so a process death between UI clear and write
            // can't re-show release notes on next launch.
            try {
                dataStore.setLastWhatsNewVersionShown(BuildConfig.VERSION_CODE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to persist What's New dismissal", e)
            }
            _uiState.update { it.copy(whatsNewReleases = persistentListOf()) }
        }
    }

    fun toggleSyncChangesSheet() {
        _uiState.update { it.copy(showSyncChangesSheet = !it.showSyncChangesSheet) }
    }

    /**
     * Dismiss sync changes bottom sheet and clear sync changes.
     */
    fun dismissSyncChangesSheet() {
        _uiState.update {
            it.copy(
                showSyncChangesSheet = false,
                syncChanges = persistentListOf()
            )
        }
    }

    /**
     * Switch calendar view mode and persist as default.
     * Handles data loading for each view type and cancels unnecessary jobs.
     *
     * @param persist write the new mode as the startup default. False for
     *   transient switches, e.g. drilling into a day from a column header.
     */
    fun setViewMode(mode: ViewMode, persist: Boolean = true) {
        val oldMode = _uiState.value.viewMode
        if (oldMode == mode) return

        _uiState.update {
            if (mode == ViewMode.INSIGHTS) {
                it.copy(viewMode = mode)
            } else {
                it.copy(viewMode = mode, previousNonInsightsMode = mode)
            }
        }

        if (mode == ViewMode.INSIGHTS) return

        // Best-effort persistence; a DataStore setter throw must never crash Looper.main.
        if (persist) {
            viewModelScope.launch {
                try {
                    dataStore.setDefaultCalendarView(mode.key)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to persist view mode ${mode.key}", e)
                }
            }
        }

        // Leaving a reactive view? Null its key so the derived Flow goes idle
        // (no background querying while another view is shown). Entering sets
        // it below.
        if (mode != ViewMode.AGENDA) {
            setAgendaRange(null)
        }
        if (mode != ViewMode.MONTH_FULL) {
            setMonthGridKey(null)
        }

        when (mode) {
            ViewMode.AGENDA -> {
                val now = System.currentTimeMillis()
                setAgendaRange(EpochRange(now, now + AGENDA_WINDOW_MS))
            }
            ViewMode.DAY, ViewMode.THREE_DAYS, ViewMode.WEEK -> {
                if (currentLoadedRange == null) {
                    goToTodayWeek()
                }
            }
            ViewMode.MONTH -> {
                syncPagerToSelectedDate()
            }
            ViewMode.MONTH_FULL -> {
                syncPagerToSelectedDate()
                setMonthGridKey(MonthKey(_uiState.value.viewingYear, _uiState.value.viewingMonth))
            }
            ViewMode.YEAR -> {
                loadYearDots(_uiState.value.viewingYear)
            }
            ViewMode.INSIGHTS -> {}
        }
    }

    /**
     * Sync the month pager to match selectedDate's month on view switch.
     * Prevents flicker when the user browsed to a different month in THREE_DAYS/WEEK
     * view, then switches back to MONTH.
     */
    private fun syncPagerToSelectedDate() {
        val state = _uiState.value
        // selectedDate is 0L until the user picks a day (e.g. arriving from the
        // Agenda view, which never sets it). Treat "no selection" as "stay on the
        // current viewing month" rather than syncing the pager to epoch (Dec 1969).
        if (state.selectedDate == 0L) return
        val selectedCal = Calendar.getInstance().apply { timeInMillis = state.selectedDate }
        val year = selectedCal.get(Calendar.YEAR)
        val month = selectedCal.get(Calendar.MONTH)
        if (year != state.viewingYear || month != state.viewingMonth) {
            navigateToMonth(year, month)
        }
    }


    fun toggleYearOverlay() {
        _uiState.update { it.copy(showYearOverlay = !it.showYearOverlay) }
    }

    // ==================== Snackbar ====================

    /**
     * Show a snackbar message.
     * Internal visibility for testing.
     */
    internal fun showSnackbar(message: String, action: (() -> Unit)? = null) {
        _uiState.update {
            it.copy(
                pendingSnackbarMessage = message,
                pendingSnackbarAction = action
            )
        }
    }

    /**
     * Clear the snackbar (consumed by UI).
     */
    fun clearSnackbar() {
        _uiState.update {
            it.copy(
                pendingSnackbarMessage = null,
                pendingSnackbarAction = null
            )
        }
    }

    // ==================== Pending Actions (from intents) ====================

    /**
     * Set a pending action to be processed by the UI.
     * Called from Activity's handleIncomingIntent() when notification/widget/shortcut tapped.
     *
     * This follows Android's recommended pattern for UI events:
     * - Convert events to state (not Channels)
     * - ViewModel owns state, UI observes via LaunchedEffect
     * - Clear after consumption (one-shot behavior)
     *
     * @param action The pending action to set
     * @see <a href="https://developer.android.com/topic/architecture/ui-layer/events">UI events</a>
     */
    fun setPendingAction(action: PendingAction) {
        Log.d(TAG, "setPendingAction: $action")
        _uiState.update { it.copy(pendingAction = action) }
    }

    /**
     * Clear the pending action after it's been processed by the UI.
     * Called by UI (LaunchedEffect) after handling the action.
     */
    fun clearPendingAction() {
        Log.d(TAG, "clearPendingAction")
        _uiState.update { it.copy(pendingAction = null) }
    }

    // ==================== Refresh ====================

    /**
     * Handle app resume from background. Snaps to today if the calendar day
     * has rolled over since the previous resume. All views are reactive
     * (Room Flow / range-keyed StateFlows), so events auto-emit on resume.
     */
    fun onAppResume() {
        val currentDayCode = currentDayCodeProvider()
        val previous = lastResumeDayCode
        if (previous != null && previous != currentDayCode) {
            goToToday()
        }
        lastResumeDayCode = currentDayCode
    }

    /**
     * Reload the current view (dots, day pager cache, and active view).
     *
     * Called for explicit refresh scenarios like:
     * - Calendar visibility toggle
     * - Event CRUD operations
     * - Sync completion
     */
    private fun reloadCurrentView() {
        buildEventDots(_uiState.value.viewingYear, _uiState.value.viewingMonth)
        // Month grid is reactive (monthEvents StateFlow) — no explicit reload needed.
        // Reload day pager cache — skip in MONTH_FULL mode (uses monthEvents instead)
        if (_uiState.value.viewMode != ViewMode.MONTH_FULL && _uiState.value.cacheRangeCenter != 0L) {
            loadEventsForDayPagerRange(_uiState.value.cacheRangeCenter)
        }
        // Agenda and the time-grid are reactive (agendaEvents / weekEvents
        // StateFlows) — no explicit reload needed.
        // Reload year dots if year view is active
        if (_uiState.value.viewMode == ViewMode.YEAR) {
            loadYearDots(_uiState.value.viewingYear)
        }
    }

    /**
     * Refresh the current view after a write the app just made to a device
     * calendar (CalendarProvider).
     *
     * Device events aren't in Room, so the reactive views (agenda, week,
     * month dots) only re-query them when the device change signal emits.
     * That signal is otherwise driven by a debounced ContentObserver, so a
     * device write we originate wouldn't surface until the debounce elapsed.
     * Poke it directly here to reflect the change immediately. This mirrors
     * what the debounced observer already does — invalidate the dot cache and
     * re-run the device queries — just without the delay, and only for the
     * app's own device writes (Room/sync refreshes keep using
     * [reloadCurrentView] alone, which never triggered this invalidation).
     */
    private fun reloadAfterDeviceWrite() {
        displayEventRepository.notifyDeviceCalendarChanged()
        reloadCurrentView()
    }

    // ==================== Event CRUD Operations ====================

    /**
     * Get event by ID for editing.
     */
    suspend fun getEventForEdit(eventId: Long): org.onekash.kashcal.data.db.entity.Event? {
        return withContext(ioDispatcher) {
            eventCoordinator.getEventById(eventId)
        }
    }

    /**
     * One-shot read of an event's existing attendee ENTITIES, for the form's
     * picker to seed from on edit. Returns Room rows (not the lossy
     * [AttendeeUiModel] projection) so the picker preserves
     * role/cutype/rsvp/delegation that would otherwise be stripped on the next
     * push.
     */
    suspend fun getAttendeesForEdit(eventId: Long): List<org.onekash.kashcal.data.db.entity.Attendee> {
        return withContext(ioDispatcher) {
            eventReader.getAttendeesForEvent(eventId).first()
        }
    }

    /** Debounced contact-email lookup for the attendee picker's type-ahead. */
    suspend fun queryContactEmails(prefix: String): List<org.onekash.kashcal.data.contacts.ContactEmail> =
        contactEmailReader.query(prefix)

    /**
     * Resolve the account for a calendar plus whether it can send invitations.
     * "Schedulable" means the account has at least one mailto-emittable
     * address, so an ORGANIZER can be resolved; the picker uses this to gate
     * editing and avoid creating an ATTENDEE-without-ORGANIZER event.
     */
    suspend fun getFormAttendeeContext(calendarId: Long?): FormAttendeeContext {
        if (calendarId == null) return FormAttendeeContext(account = null, isSchedulable = true)
        return withContext(ioDispatcher) {
            val calendar = uiState.value.calendars.firstOrNull { it.id == calendarId }
            val account = calendar?.accountId?.let { accountRepository.getAccountById(it) }
            // Null account = a local-only calendar; treat as schedulable (the
            // coordinator resolves no ORGANIZER but also stores no attendees on
            // a non-CalDAV calendar, so the picker stays usable). A resolved
            // account is schedulable only when it has a mailto-emittable address.
            val schedulable = account == null ||
                account.effectiveAddresses().any {
                    org.onekash.kashcal.util.AddressNormalizer.isEmailShaped(it)
                }
            FormAttendeeContext(account = account, isSchedulable = schedulable)
        }
    }

    /**
     * Resolve the day code of a device event's start, looked up by CalendarProvider ID.
     *
     * Used when an external VIEW intent points at a device event but carries no occurrence
     * timestamp: we can't open an exact occurrence, so we navigate to the event's start date
     * instead of silently landing on today. Honors isAllDay so all-day events in negative UTC
     * offsets resolve to the correct local day.
     *
     * @param eventId CalendarProvider event ID
     * @return Day code in YYYYMMDD format, or null if the event can't be found
     */
    suspend fun getDeviceEventDayCode(eventId: Long): Int? {
        return withContext(ioDispatcher) {
            val event = calendarProviderRepository.getDeviceEvent(eventId) ?: return@withContext null
            DateTimeUtils.eventTsToDayCode(event.startTs, event.isAllDay)
        }
    }

    /**
     * Resolve a device event's guest list for the quick-view / form chip
     * surfaces. Reads the `Attendees` rows on demand (never via the bulk grid
     * query) and resolves the calendar's `OWNER_ACCOUNT` as the "you" /
     * organizer identity, then maps via the pure [deviceAttendeeUiState].
     *
     * Returns an empty state (no chips, not-on-list) when the event has no
     * attendee rows or the read is denied — so the quick-view shows no guest
     * section rather than an empty one.
     *
     * @param eventId the resolved CalendarProvider event id (master or
     *   exception) whose guest list to load
     * @param calendarId the event's calendar id, used to resolve the owner
     *   email; null skips owner resolution (no one marked "you")
     */
    suspend fun getDeviceEventAttendeeState(eventId: Long, calendarId: Long?): EventAttendeeUiState {
        return withContext(ioDispatcher) {
            val attendees = calendarProviderRepository.getAttendees(eventId)
            if (attendees.isEmpty()) return@withContext EventAttendeeUiState(emptyList(), false)
            val ownerEmail = calendarId?.let { id ->
                calendarProviderRepository.getDeviceCalendar(id)
                    ?.ownerAccount
                    ?.takeUnless { it.isBlank() }
            }
            deviceAttendeeUiState(attendees, ownerEmail)
        }
    }

    /**
     * Write the current user's RSVP on a device event. Re-reads the attendee
     * rows, finds the user's own row by canonically matching the calendar's
     * owner email, and updates ONLY that row (by its provider `_ID`) — no other
     * guest's status is touched. No-ops when the user has no self row
     * (organizer-only, or simply not on the list) since there's nothing to
     * update. On a LOCAL calendar the row is written but nothing is delivered.
     *
     * @param eventId the device event id
     * @param calendarId the event's calendar id (resolves the owner "you" email)
     * @param status the user's chosen response
     */
    suspend fun replyDeviceRsvp(eventId: Long, calendarId: Long, status: AttendeeStatus): Result<Unit> {
        return withContext(ioDispatcher) {
            val ownerEmail = calendarProviderRepository.getDeviceCalendar(calendarId)
                ?.ownerAccount
                ?.takeUnless { it.isBlank() }
                ?: return@withContext Result.success(Unit)
            val canonicalOwner =
                org.onekash.kashcal.data.calendar_provider.canonicalAttendeeEmail(ownerEmail)
            val selfRow = calendarProviderRepository.getAttendees(eventId)
                .firstOrNull { a ->
                    !a.email.isNullOrBlank() &&
                        org.onekash.kashcal.data.calendar_provider.canonicalAttendeeEmail(a.email) == canonicalOwner
                }
                ?: return@withContext Result.success(Unit) // No self row → nothing to update.
            calendarProviderRepository.updateSelfAttendeeStatus(
                eventId = eventId,
                attendeeId = selfRow.id,
                status = status.toDeviceStatus(),
            ).also { result ->
                result.onSuccess { reloadAfterDeviceWrite() }
                result.onFailure { e ->
                    Log.e(TAG, "Failed to update device RSVP", e)
                    showError(CalendarError.DeviceCalendar.WriteFailed(e.message ?: "Unknown error"))
                }
            }
        }
    }

    /**
     * Resolve a device event for quick view from just its CalendarProvider ID, with no
     * occurrence timestamp — the case for external VIEW intents that carry only the event ID.
     *
     * Picks the occurrence to show:
     * - Recurring series: the next instance at or after now (read from the Instances view, so
     *   RRULE/RDATE/EXDATE are honored). The master row's DTSTART is the first — possibly
     *   long-past — occurrence and must not be used. A fully-ended series yields null.
     * - Non-recurring event: its own DTSTART (the single instance, past or future).
     *
     * @param eventId CalendarProvider event ID
     * @return The matched DisplayEvent.Device, or null if no occurrence can be resolved
     */
    suspend fun getDeviceEventForQuickViewById(eventId: Long): DisplayEvent.Device? {
        return withContext(ioDispatcher) {
            val event = calendarProviderRepository.getDeviceEvent(eventId) ?: return@withContext null
            val occurrenceTs = if (!event.rrule.isNullOrEmpty()) {
                calendarProviderRepository.getNextOccurrenceStart(eventId, System.currentTimeMillis())
                    ?: return@withContext null
            } else {
                event.startTs
            }
            getDeviceEventForQuickView(eventId, occurrenceTs)
        }
    }

    /**
     * Get device event for quick view from widget tap.
     *
     * Queries CalendarProvider for instances on the day of occurrenceTs,
     * then finds the instance matching eventId and startTs.
     *
     * @param eventId CalendarProvider event ID
     * @param occurrenceTs Timestamp of the specific occurrence
     * @return DisplayEvent.Device if found, null otherwise
     */
    suspend fun getDeviceEventForQuickView(eventId: Long, occurrenceTs: Long): DisplayEvent.Device? {
        return withContext(ioDispatcher) {
            try {
                // Compute day codes for both timed and all-day interpretations.
                // All-day events use UTC midnight timestamps, which in negative UTC offsets
                // map to the previous local day when interpreted as timed (isAllDay=false).
                // Query both possible days to handle either case in a single call.
                val timedDayCode = DateTimeUtils.eventTsToDayCode(occurrenceTs, isAllDay = false)
                val allDayDayCode = DateTimeUtils.eventTsToDayCode(occurrenceTs, isAllDay = true)
                val startDay = minOf(timedDayCode, allDayDayCode)
                val endDay = maxOf(timedDayCode, allDayDayCode)

                val eventsMap = displayEventRepository.getDisplayEventsGroupedByDayOnce(startDay, endDay)

                eventsMap.values.flatten()
                    .filterIsInstance<DisplayEvent.Device>()
                    .find { it.instance.eventId == eventId && it.startTs == occurrenceTs }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get device event for quick view: eventId=$eventId, occurrenceTs=$occurrenceTs", e)
                null
            }
        }
    }

    /**
     * Save event from form state.
     * Creates new event or updates existing one.
     *
     * @param formState The form state with event data
     * @return Result containing the created/updated event or error
     */
    suspend fun saveEvent(
        formState: EventFormState,
        scope: EditScope? = null,
    ): Result<org.onekash.kashcal.data.db.entity.Event> {
        return withContext(ioDispatcher) {
            try {
                // Calculate timestamps from form state. The conversion (all-day
                // → UTC midnight; timed → selected-timezone wall clock) lives in
                // EventFormState.toStartEndTs so the edit-notify banner's change
                // detection uses the exact same math as what gets persisted.
                val (startTs, endTs) = formState.toStartEndTs()

                // Build reminders list
                val reminders = buildRemindersList(formState.reminders)

                // Get calendar ID (use local if not specified)
                val calendarId = formState.selectedCalendarId
                    ?: eventCoordinator.getLocalCalendarId()

                // Attendees the user edited in the form. null = the form isn't
                // managing attendees (leave any existing/pulled rows alone); a
                // non-null list is the authoritative set to persist. The picker
                // hands back Room entities directly (it seeds from and mutates
                // the real rows), so there's no lossy projection to convert —
                // and an unedited open-and-save passes null even when the event
                // already has attendees, preserving their wire fields.
                val attendeesArg = formState.attendees.takeIf { formState.attendeesEdited }

                // Scope-aware route: when the form-save flow handed us an
                // explicit scope, honor it. THIS_AND_FUTURE is the new
                // path; THIS_EVENT and ALL_EVENTS map onto existing
                // exception / update branches. Without a scope param
                // the legacy editingOccurrenceTs heuristic still applies.
                if (
                    scope == EditScope.THIS_AND_FUTURE &&
                    formState.editingOccurrenceTs != null &&
                    formState.editingEventId != null
                ) {
                    val editingEvent = eventCoordinator.getEventById(formState.editingEventId)
                    val masterEventId = editingEvent?.originalEventId ?: formState.editingEventId
                    val splitEvent = eventCoordinator.editThisAndFuture(
                        masterEventId = masterEventId,
                        splitTimeMs = formState.editingOccurrenceTs,
                        attendees = attendeesArg,
                        changes = { master ->
                            master.copy(
                                title = formState.title.ifBlank { "Untitled" },
                                startTs = startTs,
                                endTs = endTs,
                                isAllDay = formState.isAllDay,
                                location = formState.location.ifBlank { null },
                                description = formState.description.ifBlank { null },
                                // formState.rrule == null means user picked
                                // "Does not repeat" — pass it through so the
                                // new series row becomes non-recurring.
                                rrule = formState.rrule,
                                reminders = reminders,
                                calendarId = calendarId,
                                transp = formState.transp,
                                color = formState.eventColor,
                                categories = formState.categories.ifEmpty { null },
                                timezone = if (formState.isAllDay) null else (formState.timezone ?: master.timezone),
                                updatedAt = System.currentTimeMillis(),
                            )
                        }
                    )
                    reloadCurrentView()
                    Log.d(TAG, "Event split for this-and-future: ${splitEvent.title} (id=${splitEvent.id})")
                    return@withContext Result.success(splitEvent)
                }

                // ALL_EVENTS scope on a recurring edit: treat as a master
                // update even if the form was opened on an occurrence.
                val effectiveOccurrenceTs =
                    if (scope == EditScope.ALL_EVENTS) null else formState.editingOccurrenceTs

                // Create or update event
                val savedEvent = if (effectiveOccurrenceTs != null && formState.editingEventId != null) {
                    // Editing a single occurrence of a recurring event - create exception
                    // DEFENSIVE CHECK: If caller passed exception ID, resolve to master ID
                    // This handles edge cases where MainActivity fix wasn't applied
                    val editingEvent = eventCoordinator.getEventById(formState.editingEventId)
                    val masterEventId = editingEvent?.originalEventId ?: formState.editingEventId
                    eventCoordinator.editSingleOccurrence(
                        masterEventId = masterEventId,
                        occurrenceTimeMs = effectiveOccurrenceTs,
                        attendees = attendeesArg,
                        changes = { masterEvent ->
                            masterEvent.copy(
                                title = formState.title.ifBlank { "Untitled" },
                                startTs = startTs,
                                endTs = endTs,
                                isAllDay = formState.isAllDay,
                                location = formState.location.ifBlank { null },
                                description = formState.description.ifBlank { null },
                                rrule = null, // Exception events don't have RRULE
                                reminders = reminders,
                                calendarId = calendarId,
                                transp = formState.transp,
                                color = formState.eventColor,
                                categories = formState.categories.ifEmpty { null },
                                // Preserve these fields from master for round-trip fidelity:
                                timezone = masterEvent.timezone,
                                status = masterEvent.status,
                                classification = masterEvent.classification,
                                extraProperties = masterEvent.extraProperties,
                                updatedAt = System.currentTimeMillis()
                            )
                        }
                    )
                } else if (formState.isEditMode && formState.editingEventId != null) {
                    // Update entire event (or all occurrences for recurring)
                    val loadedEvent = eventCoordinator.getEventById(formState.editingEventId)
                        ?: return@withContext Result.failure(IllegalStateException("Event not found"))

                    // ALL_EVENTS must rewrite the master series row. If the
                    // form was opened on a detached exception, climb to its
                    // master like the THIS_AND_FUTURE / exception branches do
                    // — otherwise the rrule change lands on the exception row
                    // instead of the series.
                    val existingEvent =
                        if (scope == EditScope.ALL_EVENTS && loadedEvent.originalEventId != null) {
                            eventCoordinator.getEventById(loadedEvent.originalEventId)
                                ?: return@withContext Result.failure(IllegalStateException("Master event not found"))
                        } else {
                            loadedEvent
                        }
                    val targetEventId = existingEvent.id

                    // Check if calendar is changing
                    val calendarChanged = existingEvent.calendarId != calendarId

                    // Note: SEQUENCE increment is handled by EventWriter (domain layer),
                    // following Android architecture best practices where business logic
                    // belongs in Data/Domain layer, not ViewModel (UI layer).

                    if (calendarChanged) {
                        // Calendar move requires DELETE + CREATE for CalDAV
                        // moveEventToCalendar handles this properly
                        eventCoordinator.moveEventToCalendar(targetEventId, calendarId)

                        // After move, get the updated event and apply other field changes
                        val movedEvent = eventCoordinator.getEventById(targetEventId)
                            ?: return@withContext Result.failure(IllegalStateException("Event not found after move"))

                        val finalEvent = movedEvent.copy(
                            title = formState.title.ifBlank { "Untitled" },
                            startTs = startTs,
                            endTs = endTs,
                            isAllDay = formState.isAllDay,
                            timezone = if (formState.isAllDay) null else (formState.timezone ?: movedEvent.timezone),
                            location = formState.location.ifBlank { null },
                            description = formState.description.ifBlank { null },
                            rrule = formState.rrule,
                            reminders = reminders,
                            transp = formState.transp,
                            color = formState.eventColor,
                            categories = formState.categories.ifEmpty { null },
                            updatedAt = System.currentTimeMillis()
                        )
                        eventCoordinator.updateEvent(finalEvent, attendees = attendeesArg)
                    } else {
                        // Same calendar - just update the event
                        val updatedEvent = existingEvent.copy(
                            title = formState.title.ifBlank { "Untitled" },
                            startTs = startTs,
                            endTs = endTs,
                            isAllDay = formState.isAllDay,
                            timezone = if (formState.isAllDay) null else (formState.timezone ?: existingEvent.timezone),
                            location = formState.location.ifBlank { null },
                            description = formState.description.ifBlank { null },
                            rrule = formState.rrule,
                            reminders = reminders,
                            calendarId = calendarId,
                            transp = formState.transp,
                            color = formState.eventColor,
                            categories = formState.categories.ifEmpty { null },
                            updatedAt = System.currentTimeMillis()
                        )
                        eventCoordinator.updateEvent(updatedEvent, attendees = attendeesArg)
                    }
                } else {
                    // Create new event
                    val now = System.currentTimeMillis()
                    val newEvent = org.onekash.kashcal.data.db.entity.Event(
                        // Blank uid: EventWriter mints the canonical
                        // @kashcal.onekash.org UID so the form isn't a second
                        // minting authority.
                        uid = "",
                        calendarId = calendarId,
                        title = formState.title.ifBlank { "Untitled" },
                        startTs = startTs,
                        endTs = endTs,
                        // All-day events use null timezone (stored as UTC midnight)
                        // Timed events use user-selected timezone (or device default if null)
                        timezone = if (formState.isAllDay) null else (formState.timezone ?: java.util.TimeZone.getDefault().id),
                        isAllDay = formState.isAllDay,
                        location = formState.location.ifBlank { null },
                        description = formState.description.ifBlank { null },
                        rrule = formState.rrule,
                        reminders = reminders,
                        transp = formState.transp,
                        color = formState.eventColor,
                        categories = formState.categories.ifEmpty { null },
                        dtstamp = now,
                        createdAt = now,
                        updatedAt = now
                    )

                    eventCoordinator.createEvent(newEvent, calendarId, attendees = attendeesArg)
                }

                // Refresh the UI after save
                reloadCurrentView()

                Log.d(TAG, "Event saved: ${savedEvent.title} (id=${savedEvent.id})")
                Result.success(savedEvent)

            } catch (e: Exception) {
                Log.e(TAG, "Error saving event", e)
                Result.failure(e)
            }
        }
    }

    fun rescheduleEvent(
        displayEvent: DisplayEvent,
        targetDate: LocalDate,
        targetStartMinutes: Int,
        editScope: EditScope = EditScope.THIS_EVENT
    ) {
        val isRecurringNeedingDialog = when (displayEvent) {
            is DisplayEvent.Room -> displayEvent.event.rrule != null && displayEvent.event.originalEventId == null
            is DisplayEvent.Device -> displayEvent.instance.hasRrule
        }
        if (isRecurringNeedingDialog && editScope == EditScope.THIS_EVENT) {
            _uiState.update {
                it.copy(pendingDragReschedule = PendingDragReschedule(displayEvent, targetDate, targetStartMinutes))
            }
            return
        }

        performReschedule(displayEvent, targetDate, targetStartMinutes, editScope)
    }

    fun confirmReschedule(editScope: EditScope) {
        val pending = _uiState.value.pendingDragReschedule ?: return
        _uiState.update { it.copy(pendingDragReschedule = null) }
        performReschedule(pending.displayEvent, pending.targetDate, pending.targetStartMinutes, editScope)
    }

    fun cancelPendingReschedule() {
        _uiState.update { it.copy(pendingDragReschedule = null) }
    }

    /**
     * Stage a form-save awaiting scope selection. Recurring events
     * surface the scope sheet; non-recurring and read-only paths
     * commit directly via [saveEvent] / [saveDeviceEvent] (the form
     * itself decides which path to take based on `formState.isReadOnly`).
     */
    /**
     * Stage a deferred form-save awaiting scope selection.
     *
     * Captures `masterStartTs` and `isDetachedException` from the
     * live event so the option-set rules don't have to derive them
     * from the (possibly user-edited) form state. Caller passes the
     * resolved values; for the typical edit-an-occurrence flow the
     * MainActivity onEdit callback has both in hand.
     */
    fun requestFormSave(
        formState: org.onekash.kashcal.ui.components.EventFormState,
        occurrenceTs: Long,
        originalRrule: String?,
        masterStartTs: Long,
        isDetachedException: Boolean,
        isRecurringDevice: Boolean,
        loadedIsAllDay: Boolean,
    ) {
        _uiState.update {
            it.copy(
                pendingFormSave = PendingFormSave(
                    formState = formState,
                    occurrenceTs = occurrenceTs,
                    originalRrule = originalRrule,
                    masterStartTs = masterStartTs,
                    isDetachedException = isDetachedException,
                    isRecurringDevice = isRecurringDevice,
                    loadedIsAllDay = loadedIsAllDay,
                )
            )
        }
    }

    fun cancelPendingFormSave() {
        _uiState.update { it.copy(pendingFormSave = null) }
    }

    /**
     * Tick the failure counter so the form's LaunchedEffect resets
     * its `isSaving = true` flag. Called after a deferred save
     * fails OR after the user cancels from the scope sheet — both
     * paths leave the form open with the user's edits, and the form
     * needs the Save button re-enabled for retry.
     */
    fun signalFormSaveFailed() {
        _uiState.update { it.copy(formSaveFailedTick = it.formSaveFailedTick + 1) }
    }

    /**
     * Stage a recurring-event delete awaiting scope selection. Use
     * one of the typed factories below — they capture the per-source
     * fields (event row for Room, master id + calendar id for
     * device) and the option-set context (masterStartTs,
     * isDetachedException).
     *
     * Non-recurring deletes never set this state; the caller routes
     * directly via `deleteEventOptimistic` / `deleteDeviceEvent`.
     */
    fun requestDeleteRoom(
        event: org.onekash.kashcal.data.db.entity.Event,
        occurrenceTs: Long,
        masterStartTs: Long,
        isDetachedException: Boolean,
        isAllDay: Boolean,
    ) {
        _uiState.update {
            it.copy(
                pendingDelete = PendingDelete.Room(
                    event = event,
                    occurrenceTs = occurrenceTs,
                    masterStartTs = masterStartTs,
                    isDetachedException = isDetachedException,
                    isAllDay = isAllDay,
                )
            )
        }
    }

    fun requestDeleteDevice(
        masterEventId: Long,
        calendarId: Long,
        occurrenceTs: Long,
        masterStartTs: Long,
        isDetachedException: Boolean,
        isAllDay: Boolean,
    ) {
        _uiState.update {
            it.copy(
                pendingDelete = PendingDelete.Device(
                    masterEventId = masterEventId,
                    calendarId = calendarId,
                    occurrenceTs = occurrenceTs,
                    masterStartTs = masterStartTs,
                    isDetachedException = isDetachedException,
                    isAllDay = isAllDay,
                )
            )
        }
    }

    fun cancelPendingDelete() {
        _uiState.update { it.copy(pendingDelete = null) }
    }

    /**
     * Apply a deferred delete with the user's chosen scope. Routes
     * by sealed-type variant (Room vs Device).
     */
    fun confirmDelete(scope: EditScope) {
        val pending = _uiState.value.pendingDelete ?: return
        _uiState.update { it.copy(pendingDelete = null) }

        when (pending) {
            is PendingDelete.Device -> {
                viewModelScope.launch {
                    try {
                        when (scope) {
                            EditScope.THIS_EVENT -> deleteDeviceSingleOccurrence(
                                masterEventId = pending.masterEventId,
                                originalInstanceTime = pending.occurrenceTs,
                                isAllDay = pending.isAllDay,
                            )
                            EditScope.THIS_AND_FUTURE -> deleteDeviceThisAndFuture(
                                masterEventId = pending.masterEventId,
                                fromTimeMs = pending.occurrenceTs,
                                isAllDay = pending.isAllDay,
                            )
                            EditScope.ALL_EVENTS -> deleteDeviceEvent(pending.masterEventId)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "confirmDelete (device) failed", e)
                    }
                }
            }
            is PendingDelete.Room -> {
                val masterId = pending.event.originalEventId ?: pending.event.id
                when (scope) {
                    EditScope.THIS_EVENT -> deleteSingleOccurrence(masterId, pending.occurrenceTs)
                    EditScope.THIS_AND_FUTURE -> deleteThisAndFuture(masterId, pending.occurrenceTs)
                    EditScope.ALL_EVENTS -> deleteEventOptimistic(masterId)
                }
            }
        }
    }

    private fun performReschedule(
        displayEvent: DisplayEvent,
        targetDate: LocalDate,
        targetStartMinutes: Int,
        editScope: EditScope
    ) {
        viewModelScope.launch {
            try {
                val durationMs = displayEvent.endTs - displayEvent.startTs
                val durationMinutes = (durationMs / 60000).toInt()
                val clampedStart = WeekViewUtils.clampDragStartMinutes(targetStartMinutes, durationMinutes)
                val (newStartTs, newEndTs) = WeekViewUtils.calculateNewTimestamps(
                    targetDate, clampedStart, durationMinutes
                )

                withContext(ioDispatcher) {
                    when (displayEvent) {
                        is DisplayEvent.Room -> {
                            val event = displayEvent.event
                            val isRecurring = event.rrule != null
                            val isException = event.originalEventId != null

                            when {
                                !isRecurring || isException -> {
                                    eventCoordinator.updateEvent(
                                        event.copy(startTs = newStartTs, endTs = newEndTs, updatedAt = System.currentTimeMillis())
                                    )
                                }
                                editScope == EditScope.THIS_EVENT -> {
                                    val masterEventId = event.originalEventId ?: event.id
                                    eventCoordinator.editSingleOccurrence(
                                        masterEventId = masterEventId,
                                        occurrenceTimeMs = displayEvent.occurrence.startTs,
                                        changes = { master ->
                                            master.copy(
                                                startTs = newStartTs,
                                                endTs = newEndTs,
                                                rrule = null,
                                                updatedAt = System.currentTimeMillis()
                                            )
                                        }
                                    )
                                }
                                editScope == EditScope.THIS_AND_FUTURE -> {
                                    val masterEventId = event.originalEventId ?: event.id
                                    eventCoordinator.editThisAndFuture(
                                        masterEventId = masterEventId,
                                        splitTimeMs = displayEvent.occurrence.startTs,
                                        changes = { master ->
                                            // Anchor endTs on the dragged occurrence's
                                            // own duration. Adding delta to master.endTs
                                            // would land endTs at master-time + delta,
                                            // which sits days before the new startTs and
                                            // violates RFC 5545 §3.6.1 (DTEND MUST be
                                            // later than DTSTART).
                                            val draggedDuration =
                                                displayEvent.endTs - displayEvent.startTs
                                            master.copy(
                                                startTs = newStartTs,
                                                endTs = newStartTs + draggedDuration,
                                                updatedAt = System.currentTimeMillis(),
                                            )
                                        }
                                    )
                                }
                                editScope == EditScope.ALL_EVENTS -> {
                                    val delta = newStartTs - displayEvent.startTs
                                    eventCoordinator.updateEvent(
                                        event.copy(
                                            startTs = event.startTs + delta,
                                            endTs = event.endTs + delta,
                                            updatedAt = System.currentTimeMillis()
                                        )
                                    )
                                }
                            }
                        }
                        is DisplayEvent.Device -> {
                            val instance = displayEvent.instance
                            val tz = instance.timezone ?: java.util.TimeZone.getDefault().id
                            when {
                                instance.hasRrule && editScope == EditScope.THIS_EVENT -> {
                                    calendarProviderRepository.createException(
                                        calendarId = instance.calendarId,
                                        masterEventId = instance.eventId,
                                        originalInstanceTime = instance.startTs,
                                        title = instance.title,
                                        description = instance.description,
                                        location = instance.location,
                                        startTs = newStartTs,
                                        endTs = newEndTs,
                                        isAllDay = instance.isAllDay,
                                        timezone = tz,
                                        reminders = instance.reminders
                                    )
                                }
                                instance.hasRrule && editScope == EditScope.THIS_AND_FUTURE -> {
                                    calendarProviderRepository.editThisAndFuture(
                                        masterEventId = instance.eventId,
                                        fromTimeMs = instance.startTs,
                                        isAllDay = instance.isAllDay,
                                        calendarId = instance.calendarId,
                                        title = instance.title,
                                        description = instance.description,
                                        location = instance.location,
                                        startTs = newStartTs,
                                        endTs = if (instance.rrule != null) null else newEndTs,
                                        rrule = instance.rrule,
                                        duration = if (instance.rrule != null) computeDurationString(newStartTs, newEndTs, instance.isAllDay) else null,
                                        timezone = tz,
                                        reminders = instance.reminders,
                                    )
                                }
                                else -> {
                                    calendarProviderRepository.updateEvent(
                                        eventId = instance.eventId,
                                        title = instance.title,
                                        description = instance.description,
                                        location = instance.location,
                                        startTs = newStartTs,
                                        endTs = newEndTs,
                                        isAllDay = instance.isAllDay,
                                        rrule = instance.rrule,
                                        duration = null,
                                        timezone = tz,
                                        reminders = instance.reminders
                                    )
                                }
                            }
                        }
                    }
                }

                if (displayEvent is DisplayEvent.Device) reloadAfterDeviceWrite() else reloadCurrentView()
                showSnackbar("Event rescheduled")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error rescheduling event", e)
                showSnackbar("Failed to reschedule: ${e.message}")
            }
        }
    }

    /**
     * Delete an event.
     *
     * @param eventId The event ID to delete
     * @return Result indicating success or failure
     */
    suspend fun deleteEvent(eventId: Long): Result<Unit> {
        return withContext(ioDispatcher) {
            try {
                eventCoordinator.deleteEvent(eventId)
                Log.d(TAG, "Event deleted: $eventId")

                // Refresh the UI after delete
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    reloadCurrentView()
                }

                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting event", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Route a Room delete fired from the event form's in-line Delete
     * button based on the loaded event's shape — mirrors the
     * QuickView Delete branching:
     *
     * - Exception (originalEventId != null) → coordinator's
     *   deleteSingleOccurrence(masterId, originalInstanceTime). Going
     *   through the public deleteEvent path would trip the
     *   coordinator's exception guard.
     * - Recurring master (rrule != null) → stage PendingDelete.Room
     *   and return success-no-op. The form dismisses; the scope sheet
     *   renders on top via uiState.pendingDelete and the user picks
     *   THIS_EVENT / THIS_AND_FUTURE / ALL_EVENTS.
     * - Non-recurring → coordinator.deleteEvent verbatim.
     */
    suspend fun handleRoomEventFormDelete(eventId: Long, occurrenceTs: Long?): Result<Unit> {
        return withContext(ioDispatcher) {
            try {
                val event = eventCoordinator.getEventById(eventId)
                    ?: return@withContext Result.failure(IllegalStateException("Event not found: $eventId"))
                when {
                    event.originalEventId != null -> {
                        val masterId = event.originalEventId
                        val originalInstance = event.originalInstanceTime
                            ?: return@withContext Result.failure(
                                IllegalStateException("Exception event missing originalInstanceTime: $eventId")
                            )
                        eventCoordinator.deleteSingleOccurrence(masterId, originalInstance)
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            reloadCurrentView()
                        }
                        Result.success(Unit)
                    }
                    event.rrule != null -> {
                        // Use the form's occurrenceTs when present (form was
                        // opened on a tapped occurrence), otherwise fall back
                        // to the master's start. Without this, the scope sheet
                        // disables THIS_AND_FUTURE on every form-Delete and
                        // THIS_EVENT routes to the master's first occurrence.
                        val occ = occurrenceTs ?: event.startTs
                        requestDeleteRoom(
                            event = event,
                            occurrenceTs = occ,
                            masterStartTs = event.startTs,
                            isDetachedException = false,
                            isAllDay = event.isAllDay,
                        )
                        Result.success(Unit)
                    }
                    else -> {
                        eventCoordinator.deleteEvent(eventId)
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            reloadCurrentView()
                        }
                        Result.success(Unit)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error handling form delete: $eventId", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Write the user's RSVP for an event they're attending.
     *
     * Optimistic-UI write path: the local attendee row's PARTSTAT is updated
     * inside the coordinator, so the chip row's Flow re-emits with the new
     * status before the network round-trip completes. The CalDAV PUT is
     * queued via PendingOperation and processed by PushStrategy.
     */
    fun replyRsvp(
        eventId: Long,
        status: org.onekash.kashcal.ui.components.attendees.AttendeeStatus
    ) {
        val partstat = status.toPartstat() ?: return
        viewModelScope.launch {
            try {
                val ok = withContext(ioDispatcher) {
                    eventCoordinator.replyRsvp(eventId, partstat)
                }
                if (!ok) {
                    Log.w(TAG, "RSVP write failed (account/attendee mismatch) for event $eventId")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error writing RSVP for event $eventId", e)
                showSnackbar("RSVP failed: ${e.message}")
            }
        }
    }

    /**
     * Save the user's reminder set on an event they're an attendee of.
     * Wraps [EventCoordinator.saveAttendeeReminders] for the read-only
     * attendee form path. Local-only — no server PUT. Failure surfaces
     * to the form sheet's `state.error` field via the [Result] return.
     */
    suspend fun saveAttendeeReminders(eventId: Long, reminders: List<Int>): Result<Unit> {
        return withContext(ioDispatcher) {
            try {
                eventCoordinator.saveAttendeeReminders(eventId, reminders).map { }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error saving attendee reminders for event $eventId", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Delete event (fire-and-forget for optimistic UI).
     * Use this for QuickViewSheet where immediate dismissal is desired.
     * Note: Keep existing suspend deleteEvent() for EventFormSheet compatibility.
     *
     * @param eventId The event ID to delete
     */
    fun deleteEventOptimistic(eventId: Long) {
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) {
                    eventCoordinator.deleteEvent(eventId)
                }
                Log.d(TAG, "Event deleted (optimistic): $eventId")
                reloadCurrentView()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting event", e)
                showSnackbar("Failed to delete: ${e.message}")
            }
        }
    }

    /**
     * Delete a single occurrence of a recurring event (fire-and-forget for optimistic UI).
     * Adds EXDATE to master event.
     *
     * @param masterEventId The master recurring event ID
     * @param occurrenceTimeMs The occurrence timestamp to delete
     */
    fun deleteSingleOccurrence(masterEventId: Long, occurrenceTimeMs: Long) {
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) {
                    eventCoordinator.deleteSingleOccurrence(masterEventId, occurrenceTimeMs)
                }
                Log.d(TAG, "Occurrence deleted: event=$masterEventId, ts=$occurrenceTimeMs")
                reloadCurrentView()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting occurrence", e)
                showSnackbar("Failed to delete: ${e.message}")
            }
        }
    }

    /**
     * Delete this and all future occurrences (fire-and-forget for optimistic UI).
     * Truncates series with UNTIL.
     *
     * @param masterEventId The master recurring event ID
     * @param fromTimeMs Delete occurrences from this time onwards
     */
    fun deleteThisAndFuture(masterEventId: Long, fromTimeMs: Long) {
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) {
                    eventCoordinator.deleteThisAndFuture(masterEventId, fromTimeMs)
                }
                Log.d(TAG, "Future occurrences deleted: event=$masterEventId, from=$fromTimeMs")
                reloadCurrentView()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting future occurrences", e)
                showSnackbar("Failed to delete: ${e.message}")
            }
        }
    }

    // ==================== Device Calendar Write Operations ====================

    /**
     * Create a new event in a device calendar (CalendarProvider).
     *
     * @return Result containing created event ID on success
     */
    suspend fun createDeviceEvent(
        calendarId: Long,
        title: String,
        description: String?,
        location: String?,
        startTs: Long,
        endTs: Long,
        isAllDay: Boolean,
        rrule: String?,
        timezone: String,
        reminders: List<Int>
    ): Result<Long> {
        return withContext(ioDispatcher) {
            // For recurring events, compute duration string
            val duration = if (rrule != null) {
                computeDurationString(startTs, endTs, isAllDay)
            } else null

            calendarProviderRepository.createEvent(
                calendarId = calendarId,
                title = title,
                description = description,
                location = location,
                startTs = startTs,
                endTs = if (rrule != null) null else endTs,
                isAllDay = isAllDay,
                rrule = rrule,
                duration = duration,
                timezone = timezone,
                reminders = reminders
            ).also { result ->
                result.onFailure { e ->
                    Log.e(TAG, "Failed to create device event", e)
                    showError(CalendarError.DeviceCalendar.WriteFailed(e.message ?: "Unknown error"))
                }
                result.onSuccess {
                    Log.d(TAG, "Device event created: id=$it")
                    reloadAfterDeviceWrite()
                }
            }
        }
    }

    /**
     * Update an existing event in a device calendar.
     */
    suspend fun updateDeviceEvent(
        eventId: Long,
        title: String,
        description: String?,
        location: String?,
        startTs: Long,
        endTs: Long,
        isAllDay: Boolean,
        rrule: String?,
        timezone: String,
        reminders: List<Int>
    ): Result<Unit> {
        return withContext(ioDispatcher) {
            val duration = if (rrule != null) {
                computeDurationString(startTs, endTs, isAllDay)
            } else null

            calendarProviderRepository.updateEvent(
                eventId = eventId,
                title = title,
                description = description,
                location = location,
                startTs = startTs,
                endTs = if (rrule != null) null else endTs,
                isAllDay = isAllDay,
                rrule = rrule,
                duration = duration,
                timezone = timezone,
                reminders = reminders
            ).also { result ->
                result.onFailure { e ->
                    Log.e(TAG, "Failed to update device event: $eventId", e)
                    showError(CalendarError.DeviceCalendar.WriteFailed(e.message ?: "Unknown error"))
                }
                result.onSuccess {
                    Log.d(TAG, "Device event updated: id=$eventId")
                    reloadAfterDeviceWrite()
                }
            }
        }
    }

    /**
     * Delete an event from a device calendar.
     */
    suspend fun deleteDeviceEvent(eventId: Long): Result<Unit> {
        return withContext(ioDispatcher) {
            calendarProviderRepository.deleteEvent(eventId).also { result ->
                result.onFailure { e ->
                    Log.e(TAG, "Failed to delete device event: $eventId", e)
                    showError(CalendarError.DeviceCalendar.WriteFailed(e.message ?: "Unknown error"))
                }
                result.onSuccess {
                    Log.d(TAG, "Device event deleted: id=$eventId")
                    reloadAfterDeviceWrite()
                }
            }
        }
    }

    /**
     * Delete a single occurrence of a recurring device calendar event.
     * Adds EXDATE to master event in CalendarProvider to exclude the occurrence.
     */
    suspend fun deleteDeviceSingleOccurrence(
        masterEventId: Long,
        originalInstanceTime: Long,
        isAllDay: Boolean = false
    ): Result<Unit> {
        return withContext(ioDispatcher) {
            calendarProviderRepository.deleteSingleOccurrence(
                masterEventId = masterEventId,
                originalInstanceTime = originalInstanceTime,
                isAllDay = isAllDay
            ).also { result ->
                result.onFailure { e ->
                    Log.e(TAG, "Failed to delete device occurrence: master=$masterEventId", e)
                    showError(CalendarError.DeviceCalendar.WriteFailed(e.message ?: "Unknown error"))
                }
                result.onSuccess {
                    Log.d(TAG, "Device occurrence deleted: master=$masterEventId, ts=$originalInstanceTime")
                    reloadAfterDeviceWrite()
                }
            }
        }
    }

    /**
     * Route device event deletion from EventFormSheet based on form state.
     * When editingOccurrenceTs is set, deletes single occurrence; otherwise deletes entire event.
     * Mirrors the save routing logic at [saveDeviceEvent].
     */
    suspend fun handleDeviceEventFormDelete(formState: EventFormState): Result<Unit> {
        val deviceEventId = formState.editingDeviceEventId
            ?: return Result.failure(IllegalStateException("No device event to delete"))
        return withContext(ioDispatcher) {
            try {
                val event = calendarProviderRepository.getDeviceEvent(deviceEventId)
                    ?: return@withContext Result.failure(
                        IllegalStateException("Device event not found: $deviceEventId")
                    )
                when {
                    // Exception event — delete just this occurrence on
                    // the master via EXDATE / cancel-tombstone. Don't
                    // route through the master-delete path which would
                    // wipe the entire series.
                    event.originalId != null -> {
                        val masterId = event.originalId
                        val originalInstance = event.originalInstanceTime
                            ?: return@withContext Result.failure(
                                IllegalStateException("Device exception missing originalInstanceTime: $deviceEventId")
                            )
                        deleteDeviceSingleOccurrence(
                            masterEventId = masterId,
                            originalInstanceTime = originalInstance,
                            isAllDay = formState.isAllDay,
                        )
                    }
                    // Recurring master — surface the scope sheet with
                    // the form's tapped-occurrence anchor so the user
                    // picks THIS_EVENT / THIS_AND_FUTURE / ALL_EVENTS.
                    event.rrule != null -> {
                        val occ = formState.editingOccurrenceTs ?: event.startTs
                        requestDeleteDevice(
                            masterEventId = deviceEventId,
                            calendarId = event.calendarId,
                            occurrenceTs = occ,
                            masterStartTs = event.startTs,
                            isDetachedException = false,
                            isAllDay = formState.isAllDay,
                        )
                        Result.success(Unit)
                    }
                    // Non-recurring — straight delete.
                    else -> deleteDeviceEvent(deviceEventId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error handling device form delete: $deviceEventId", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Delete this and all future occurrences of a recurring device calendar event.
     * Truncates the master event's RRULE with an UNTIL clause.
     */
    suspend fun deleteDeviceThisAndFuture(
        masterEventId: Long,
        fromTimeMs: Long,
        isAllDay: Boolean = false
    ): Result<Unit> {
        return withContext(ioDispatcher) {
            calendarProviderRepository.deleteThisAndFuture(
                masterEventId = masterEventId,
                fromTimeMs = fromTimeMs,
                isAllDay = isAllDay
            ).also { result ->
                result.onFailure { e ->
                    Log.e(TAG, "Failed to delete device future occurrences: master=$masterEventId", e)
                    showError(CalendarError.DeviceCalendar.WriteFailed(e.message ?: "Unknown error"))
                }
                result.onSuccess {
                    Log.d(TAG, "Device future occurrences deleted: master=$masterEventId, from=$fromTimeMs")
                    reloadAfterDeviceWrite()
                }
            }
        }
    }

    // ==================== Device Calendar Edit Support ====================

    /**
     * Check if a device event can be edited.
     *
     * @param calendarId The calendar ID containing the event
     * @return Pair of (canEdit, calendarName or null)
     */
    suspend fun canEditDeviceEvent(calendarId: Long): Pair<Boolean, String?> {
        return withContext(ioDispatcher) {
            val calendars = calendarProviderRepository.getDeviceCalendars()
            val calendar = calendars.find { it.id == calendarId }
            if (calendar != null && calendar.isWritable) {
                true to calendar.displayName
            } else {
                false to null
            }
        }
    }

    /**
     * Load a device event for editing.
     *
     * When occurrenceTs is provided, checks if an exception event exists for that occurrence.
     * If so, loads the exception event (with its own reminders) instead of the master.
     *
     * @param eventId Event ID to load (master event ID for recurring)
     * @param occurrenceTs Original occurrence timestamp (null for non-occurrence edits)
     * @param isAllDay Whether the event is all-day (for UTC midnight normalization)
     * @return DeviceEventEditData with event, reminders, and calendar info, or null if not found
     */
    suspend fun getDeviceEventForEdit(eventId: Long, occurrenceTs: Long? = null, isAllDay: Boolean = false): DeviceEventEditData? {
        return withContext(ioDispatcher) {
            val effectiveEventId = if (occurrenceTs != null) {
                calendarProviderRepository.findExceptionEventId(eventId, occurrenceTs, isAllDay)
                    ?: eventId
            } else eventId
            val event = calendarProviderRepository.getDeviceEvent(effectiveEventId) ?: return@withContext null
            val calendars = calendarProviderRepository.getDeviceCalendars()
            val calendar = calendars.find { it.id == event.calendarId } ?: return@withContext null
            val reminders = calendarProviderRepository.getReminders(effectiveEventId)
            val attendees = AttendeeUiModel.fromDevice(
                calendarProviderRepository.getAttendees(effectiveEventId),
                ownerEmail = calendar.ownerAccount.takeUnless { it.isBlank() },
            )

            DeviceEventEditData(
                event = event,
                reminders = reminders,
                calendarName = calendar.displayName,
                calendarColor = calendar.color,
                isWritable = calendar.isWritable,
                attendees = attendees,
            )
        }
    }

    /**
     * Find an existing exception event for an occurrence.
     *
     * @param masterEventId Master recurring event ID
     * @param originalInstanceTime Original occurrence timestamp
     * @return Exception event ID if exists, null otherwise
     */
    suspend fun findExceptionEventId(masterEventId: Long, originalInstanceTime: Long, isAllDay: Boolean = false): Long? {
        return withContext(ioDispatcher) {
            calendarProviderRepository.findExceptionEventId(masterEventId, originalInstanceTime, isAllDay)
        }
    }

    /**
     * Import ICS events into a device calendar via CalendarProvider.
     *
     * @param events Events parsed from ICS file
     * @param calendarId Target device calendar ID
     * @return Count of successfully imported events
     */
    suspend fun importIcsToDeviceCalendar(events: List<Event>, calendarId: Long): Int {
        val count = withContext(ioDispatcher) {
            importEventsToDeviceCalendar(
                events = events,
                calendarId = calendarId,
                repo = calendarProviderRepository,
                defaultTimedReminderMinutes = dataStore.defaultReminderMinutes.first(),
                defaultAllDayReminderMinutes = dataStore.defaultAllDayReminder.first()
            )
        }
        // Imported device events are CalendarProvider writes, so the reactive
        // views need the same immediate refresh as the other device-write
        // paths; the callers only reload calendar metadata + navigate, which
        // doesn't re-query the newly imported events until the debounce fires.
        if (count > 0) reloadAfterDeviceWrite()
        return count
    }

    /**
     * Save a device event from EventFormState.
     *
     * Routes to appropriate operation:
     * - If editing occurrence (editingOccurrenceTs != null): create/update exception
     * - If editing existing event: update event
     * - Otherwise: create new event
     *
     * @param formState The form state to save
     * @return Result containing event ID on success
     */
    suspend fun saveDeviceEvent(
        formState: org.onekash.kashcal.ui.components.EventFormState,
        scope: EditScope? = null,
    ): Result<Long> {
        return withContext(ioDispatcher) {
            val calendarId = formState.selectedCalendarId
                ?: return@withContext Result.failure(IllegalStateException("No calendar selected"))

            // Compute timestamps from form state
            val (startTs, endTs) = computeTimestampsFromFormState(formState)

            // Build reminders list (just minutes, not ISO format)
            val reminders = buildDeviceReminders(formState.reminders)

            val timezone = formState.timezone ?: java.util.TimeZone.getDefault().id

            // Guests the user edited, bridged to provider-shaped rows. null
            // when the form isn't managing attendees (open-and-save, or a
            // non-schedulable read-only path) so existing rows are left alone.
            // Threaded ONLY through the create + whole-event update branches
            // below: per-occurrence and this-and-future guest edits are out of
            // scope (the provider doesn't store per-occurrence guest divergence
            // we'd be writing), so those branches deliberately don't carry it.
            val deviceAttendeesArg =
                if (formState.attendeesEdited) pickerAttendeesToDevice(formState.attendees) else null

            // Tags the user edited, or null when the form isn't managing them
            // (open-and-save without touching the tag row) so the stored row is
            // left untouched — mirroring deviceAttendeesArg. Per-occurrence and
            // this-and-future edits stay out of scope (those branches pass null).
            val deviceCategoriesArg =
                if (formState.categoriesEdited) formState.categories else null

            // Captured on the branches that actually persist tags, so the shared
            // success handler can reconcile those names into the tag registry.
            var recordedTags: List<String>? = null

            // THIS_AND_FUTURE on a recurring device event splits the
            // series via the new repository method. The form was
            // opened on an occurrence, so editingOccurrenceTs carries
            // the split point.
            if (
                scope == EditScope.THIS_AND_FUTURE &&
                formState.editingDeviceEventId != null &&
                formState.editingOccurrenceTs != null
            ) {
                return@withContext calendarProviderRepository.editThisAndFuture(
                    masterEventId = formState.editingDeviceEventId,
                    fromTimeMs = formState.editingOccurrenceTs,
                    isAllDay = formState.isAllDay,
                    calendarId = calendarId,
                    title = formState.title,
                    description = formState.description.ifBlank { null },
                    location = formState.location.ifBlank { null },
                    startTs = startTs,
                    endTs = if (formState.rrule != null) null else endTs,
                    rrule = formState.rrule,
                    duration = if (formState.rrule != null) computeDurationString(startTs, endTs, formState.isAllDay) else null,
                    timezone = timezone,
                    reminders = reminders,
                    availability = transpToAvailability(formState.transp),
                    eventColor = formState.eventColor,
                ).also { result ->
                    result.onSuccess { reloadAfterDeviceWrite() }
                }
            }

            // ALL_EVENTS scope on a recurring edit: treat as a master
            // update even if the form was opened on an occurrence.
            val effectiveOccurrenceTs =
                if (scope == EditScope.ALL_EVENTS) null else formState.editingOccurrenceTs

            // Determine operation based on form state
            when {
                // Editing single occurrence of recurring event
                formState.editingDeviceEventId != null && effectiveOccurrenceTs != null -> {
                    val masterEventId = formState.editingDeviceEventId
                    val originalInstanceTime = effectiveOccurrenceTs

                    // Check if exception already exists
                    val existingExceptionId = calendarProviderRepository.findExceptionEventId(
                        masterEventId, originalInstanceTime, formState.isAllDay
                    )

                    if (existingExceptionId != null) {
                        // Update existing exception
                        calendarProviderRepository.updateEvent(
                            eventId = existingExceptionId,
                            title = formState.title,
                            description = formState.description.ifBlank { null },
                            location = formState.location.ifBlank { null },
                            startTs = startTs,
                            endTs = endTs,
                            isAllDay = formState.isAllDay,
                            rrule = null, // Exceptions don't have RRULE
                            duration = null,
                            timezone = timezone,
                            reminders = reminders,
                            availability = transpToAvailability(formState.transp),
                            eventColor = formState.eventColor
                        ).map { existingExceptionId }
                    } else {
                        // Create new exception
                        calendarProviderRepository.createException(
                            calendarId = calendarId,
                            masterEventId = masterEventId,
                            originalInstanceTime = originalInstanceTime,
                            title = formState.title,
                            description = formState.description.ifBlank { null },
                            location = formState.location.ifBlank { null },
                            startTs = startTs,
                            endTs = endTs,
                            isAllDay = formState.isAllDay,
                            timezone = timezone,
                            reminders = reminders,
                            availability = transpToAvailability(formState.transp),
                            eventColor = formState.eventColor
                        )
                    }
                }

                // Editing existing event (not occurrence)
                formState.editingDeviceEventId != null -> {
                    val eventId = formState.editingDeviceEventId
                    val existing = calendarProviderRepository.getDeviceEvent(eventId)

                    // A calendar change is a move. Android treats CALENDAR_ID as
                    // effectively create-time (an in-place change misbehaves on
                    // synced calendars), so a move is delete-old + insert-new,
                    // carrying the edited fields. Create first, delete second, so
                    // a failed create leaves the event safe in its source.
                    //
                    // Gate on the SOURCE being non-recurring, not the form's rrule:
                    // recurring device moves (exception cascade) are out of scope,
                    // but a save that ADDS recurrence while also changing calendar
                    // must still move — else the calendar change is silently
                    // dropped by the in-place update. Already-recurring events
                    // can't reach here anyway (the form disables the picker).
                    val isMove = existing != null &&
                        existing.calendarId != calendarId &&
                        existing.rrule == null

                    if (isMove) {
                        // Carry the guest set into the recreated event. If the
                        // user edited guests, use that set; otherwise preserve the
                        // event's existing guests so the move doesn't uninvite
                        // anyone. Drop the source ORGANIZER row: createEvent writes
                        // a fresh organizer for the TARGET calendar's owner, and a
                        // carried source-organizer (whose address differs on a
                        // cross-account move) would otherwise land as a spurious
                        // guest.
                        val moveAttendees = (
                            deviceAttendeesArg
                                ?: calendarProviderRepository.getAttendees(eventId)
                        ).orEmpty()
                            .filter {
                                it.relationship !=
                                    android.provider.CalendarContract.Attendees.RELATIONSHIP_ORGANIZER
                            }
                            .takeIf { it.isNotEmpty() }

                        // Carry the tags into the recreated event the same way as
                        // guests: the edited set if the user touched the tag row,
                        // else the source event's existing tags so the move
                        // doesn't silently drop them (the source row is deleted
                        // below). Recorded so the success handler reconciles them.
                        val moveCategories = deviceCategoriesArg ?: existing.categories
                        recordedTags = moveCategories

                        // The move carries whatever recurrence the form now has
                        // (adding recurrence while moving is supported).
                        val moveRrule = formState.rrule
                        calendarProviderRepository.createEvent(
                            calendarId = calendarId,
                            title = formState.title,
                            description = formState.description.ifBlank { null },
                            location = formState.location.ifBlank { null },
                            startTs = startTs,
                            endTs = if (moveRrule != null) null else endTs,
                            isAllDay = formState.isAllDay,
                            rrule = moveRrule,
                            duration = if (moveRrule != null) computeDurationString(startTs, endTs, formState.isAllDay) else null,
                            timezone = timezone,
                            reminders = reminders,
                            availability = transpToAvailability(formState.transp),
                            eventColor = formState.eventColor,
                            attendees = moveAttendees,
                            categories = moveCategories,
                        ).map { newId ->
                            // The target copy exists, so the move has succeeded.
                            // Deleting the source is best-effort cleanup: a failure
                            // leaves a source orphan but must NOT fail the save (a
                            // hard failure invites a duplicating retry).
                            calendarProviderRepository.deleteEvent(eventId).onFailure { e ->
                                Log.w(TAG, "Device move: created in target but source delete failed", e)
                            }
                            newId
                        }
                    } else {
                        recordedTags = deviceCategoriesArg
                        calendarProviderRepository.updateEvent(
                            eventId = eventId,
                            title = formState.title,
                            description = formState.description.ifBlank { null },
                            location = formState.location.ifBlank { null },
                            startTs = startTs,
                            endTs = if (formState.rrule != null) null else endTs,
                            isAllDay = formState.isAllDay,
                            rrule = formState.rrule,
                            duration = if (formState.rrule != null) computeDurationString(startTs, endTs, formState.isAllDay) else null,
                            timezone = timezone,
                            reminders = reminders,
                            availability = transpToAvailability(formState.transp),
                            eventColor = formState.eventColor,
                            attendees = deviceAttendeesArg,
                            categories = deviceCategoriesArg
                        ).map { eventId }
                    }
                }

                // Creating new event
                else -> {
                    recordedTags = formState.categories
                    calendarProviderRepository.createEvent(
                        calendarId = calendarId,
                        title = formState.title,
                        description = formState.description.ifBlank { null },
                        location = formState.location.ifBlank { null },
                        startTs = startTs,
                        endTs = if (formState.rrule != null) null else endTs,
                        isAllDay = formState.isAllDay,
                        rrule = formState.rrule,
                        duration = if (formState.rrule != null) computeDurationString(startTs, endTs, formState.isAllDay) else null,
                        timezone = timezone,
                        reminders = reminders,
                        availability = transpToAvailability(formState.transp),
                        eventColor = formState.eventColor,
                        attendees = deviceAttendeesArg,
                        categories = formState.categories
                    )
                }
            }.also { result ->
                result.onSuccess {
                    // Reconcile freshly-applied tags into the shared registry so
                    // new names gain a suggestion entry and become colorable. Only
                    // the create + whole-event-update branches set recordedTags.
                    // Record the same cleaned names the provider stores (backslash
                    // stripped, blanks/dupes dropped) so a suggestion resolves to
                    // the tag that actually persisted, not the raw form value.
                    recordedTags?.let { tags ->
                        val cleaned = org.onekash.kashcal.data.calendar_provider.cleanCategoryNames(tags)
                        if (cleaned.isNotEmpty()) eventCoordinator.recordTagUsage(cleaned)
                    }
                    reloadAfterDeviceWrite()
                }
                result.onFailure { e ->
                    Log.e(TAG, "Failed to save device event", e)
                    showError(CalendarError.DeviceCalendar.WriteFailed(e.message ?: "Unknown error"))
                }
            }
        }
    }

    private fun transpToAvailability(transp: String): Int =
        if (transp == "TRANSPARENT") 1 else 0

    /**
     * Compute start/end timestamps from form state.
     * Handles all-day UTC conversion.
     */
    private fun computeTimestampsFromFormState(formState: org.onekash.kashcal.ui.components.EventFormState): Pair<Long, Long> {
        return if (formState.isAllDay) {
            // All-day: convert local date to UTC midnight
            val startTs = DateTimeUtils.localDateToUtcMidnight(formState.dateMillis)
            val endTs = DateTimeUtils.localDateToUtcMidnight(formState.endDateMillis)
            // End is inclusive, so add end-of-day
            startTs to DateTimeUtils.utcMidnightToEndOfDay(endTs)
        } else {
            // Timed: combine date and time
            val startCal = java.util.Calendar.getInstance().apply {
                timeInMillis = formState.dateMillis
                set(java.util.Calendar.HOUR_OF_DAY, formState.startHour)
                set(java.util.Calendar.MINUTE, formState.startMinute)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            val endCal = java.util.Calendar.getInstance().apply {
                timeInMillis = formState.endDateMillis
                set(java.util.Calendar.HOUR_OF_DAY, formState.endHour)
                set(java.util.Calendar.MINUTE, formState.endMinute)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            startCal.timeInMillis to endCal.timeInMillis
        }
    }

    /**
     * Build device reminders list from form minutes.
     *
     * Intentional pass-through: the form's signed "minutes before start" already matches
     * Android CalendarContract.Reminders.MINUTES exactly (positive = before start, negative
     * = after). So an all-day "9 AM day of" (Int -540) is stored as MINUTES = -540 verbatim,
     * with no transform or clamping. Returns minutes (not ISO format like Room events).
     */
    private fun buildDeviceReminders(reminderMinutes: List<Int>): List<Int> {
        return deduplicateAndSortReminders(reminderMinutes)
    }

    /**
     * Build reminders list from form values.
     * Converts minutes to ISO 8601 duration format (e.g., -PT15M for 15 minutes before).
     * Deduplicates and sorts before converting.
     */
    private fun buildRemindersList(reminderMinutes: List<Int>): List<String>? {
        val deduplicated = deduplicateAndSortReminders(reminderMinutes)
        val reminders = deduplicated.map { minutesToIsoDuration(it) }
        return reminders.ifEmpty { null }
    }

    /**
     * Convert signed reminder minutes to an ISO 8601 duration trigger.
     * Positive minutes = before start ("-PT..."), negative = after start ("PT..."),
     * 0 = at start. Hour-form only (no period -P_D) for DST-stable exact durations.
     * Delegates to the shared [ContactEventUtils.minutesToIsoDuration] encoder.
     */
    private fun minutesToIsoDuration(minutes: Int): String =
        ContactEventUtils.minutesToIsoDuration(minutes)

    /**
     * Get local calendar ID for fallback.
     */
    suspend fun getLocalCalendarId(): Long {
        return withContext(ioDispatcher) {
            eventCoordinator.getLocalCalendarId()
        }
    }

    // ==================== Error Handling ====================

    /**
     * Show an error to the user.
     *
     * Converts CalendarError to ErrorPresentation and displays appropriately:
     * - Snackbar: Sets currentError, consumed by ErrorSnackbarHost
     * - Dialog: Sets currentError + showErrorDialog
     * - Banner: Sets currentError + showErrorBanner
     * - Silent: Logs only, no UI change
     *
     * Usage:
     * ```
     * try {
     *     syncEngine.sync()
     * } catch (e: Exception) {
     *     showError(ErrorMapper.fromException(e))
     * }
     * ```
     */
    fun showError(error: CalendarError) {
        val presentation = ErrorMapper.toPresentation(error)

        when (presentation) {
            is ErrorPresentation.Snackbar -> {
                _uiState.update {
                    it.copy(
                        currentError = presentation,
                        showErrorDialog = false,
                        showErrorBanner = false
                    )
                }
            }
            is ErrorPresentation.Dialog -> {
                _uiState.update {
                    it.copy(
                        currentError = presentation,
                        showErrorDialog = true,
                        showErrorBanner = false
                    )
                }
            }
            is ErrorPresentation.Banner -> {
                _uiState.update {
                    it.copy(
                        currentError = presentation,
                        showErrorDialog = false,
                        showErrorBanner = true
                    )
                }
            }
            is ErrorPresentation.Silent -> {
                // Log only, no UI change
                Log.d(TAG, "Silent error: ${presentation.logMessage}")
            }
        }
    }

    /**
     * Handle error action callback from UI.
     *
     * Called when user taps action button on error Snackbar/Dialog/Banner.
     * Dispatches to appropriate handler based on callback type.
     */
    fun handleErrorAction(callback: ErrorActionCallback) {
        when (callback) {
            is ErrorActionCallback.Retry -> {
                Log.d(TAG, "Error action: Retry")
                clearError()
                performSync()
            }
            is ErrorActionCallback.OpenSettings -> {
                Log.d(TAG, "Error action: OpenSettings")
                clearError()
                // Navigation handled by Activity (observes this state)
                _uiState.update { it.copy(pendingSnackbarMessage = null) } // Clear any snackbar
            }
            is ErrorActionCallback.OpenAppSettings -> {
                Log.d(TAG, "Error action: OpenAppSettings")
                clearError()
                // Open Android app settings - handled by Activity
            }
            is ErrorActionCallback.OpenAppleIdWebsite -> {
                Log.d(TAG, "Error action: OpenAppleIdWebsite")
                clearError()
                // Open Apple ID website - handled by Activity
            }
            is ErrorActionCallback.ReAuthenticate -> {
                Log.d(TAG, "Error action: ReAuthenticate")
                clearError()
                // Trigger re-authentication flow - handled by Activity
            }
            is ErrorActionCallback.ForceFullSync -> {
                Log.d(TAG, "Error action: ForceFullSync")
                clearError()
                forceFullSync()
            }
            is ErrorActionCallback.ViewSyncDetails -> {
                Log.d(TAG, "Error action: ViewSyncDetails")
                clearError()
                _uiState.update { it.copy(showSyncChangesSheet = true) }
            }
            is ErrorActionCallback.Dismiss -> {
                Log.d(TAG, "Error action: Dismiss")
                clearError()
            }
            is ErrorActionCallback.OpenUrl -> {
                Log.d(TAG, "Error action: OpenUrl - ${callback.url}")
                _uiState.update { it.copy(pendingUrlToOpen = callback.url) }
                clearError()
            }
            is ErrorActionCallback.Custom -> {
                Log.d(TAG, "Error action: Custom")
                callback.action()
                clearError()
            }
        }
    }

    /**
     * Clear current error state.
     * Called after error is dismissed or action is taken.
     */
    fun clearError() {
        _uiState.update {
            it.copy(
                currentError = null,
                showErrorDialog = false,
                showErrorBanner = false
            )
        }
    }

    /**
     * Clear pending URL after it has been opened.
     */
    fun clearPendingUrl() {
        _uiState.update { it.copy(pendingUrlToOpen = null) }
    }

    /**
     * Show error from HTTP code.
     * Convenience method for sync layer integration.
     */
    fun showHttpError(code: Int, message: String? = null) {
        showError(ErrorMapper.fromHttpCode(code, message))
    }

    /**
     * Show error from exception.
     * Convenience method for exception handling.
     */
    fun showExceptionError(e: Throwable) {
        showError(ErrorMapper.fromException(e))
    }

    // ==================== Helper Functions ====================

    /**
     * Parse YYYYMMDD day format into (year, month, day) triple.
     * Month is 0-indexed (January = 0) for Calendar compatibility.
     */
    private fun parseDayFormat(dayFormat: Int): Triple<Int, Int, Int> {
        val year = dayFormat / 10000
        val month = (dayFormat % 10000) / 100 - 1  // 0-indexed for Calendar
        val day = dayFormat % 100
        return Triple(year, month, day)
    }
}

/**
 * Attendee state passed from [HomeViewModel] to chip surfaces (QuickView,
 * EventForm). Held in the ViewModel layer because the type ties the VM's
 * identity-resolution to the UI projection — no other layer should
 * construct it.
 */
data class EventAttendeeUiState(
    val models: List<AttendeeUiModel>,
    val isCurrentUserOnList: Boolean
)

/**
 * Pure projection of a device event's [DeviceAttendee] rows + the calendar's
 * owner email into the chip-surface [EventAttendeeUiState].
 *
 * Separated from the ViewModel's IO (getAttendees + getDeviceCalendars) so the
 * branch logic is unit-testable without a live ContentResolver, mirroring the
 * Room path's [AttendeeUiModel.fromRoom] boundary. "On list" is true when the
 * owner email canonically matches one of the mapped attendees (the device
 * notion of "you").
 */
fun deviceAttendeeUiState(
    attendees: List<org.onekash.kashcal.data.calendar_provider.DeviceAttendee>,
    ownerEmail: String?,
): EventAttendeeUiState {
    val models = AttendeeUiModel.fromDevice(attendees, ownerEmail)
    return EventAttendeeUiState(
        models = models,
        isCurrentUserOnList = models.any { it.isYou },
    )
}

/**
 * Bridge the attendee picker's Room [org.onekash.kashcal.data.db.entity.Attendee]
 * entities into provider-shaped
 * [org.onekash.kashcal.data.calendar_provider.DeviceAttendee] guest rows at the
 * device save boundary.
 *
 * This is the seam that keeps the device write path disjoint from the
 * Room/iTIP path: the device repository must never see the Room entity's wire
 * fields (scheduleAgent, scheduleStatus, sequence …), which are meaningless to
 * `CalendarContract.Attendees`. Each row becomes a `RELATIONSHIP_ATTENDEE`
 * guest with `ATTENDEE_STATUS_NONE`; the owner/organizer row is added by the
 * repository, not here. Picker rows whose address isn't email-shaped
 * (urn:uuid, principal paths) are dropped — the provider can't store them.
 */
fun pickerAttendeesToDevice(
    attendees: List<org.onekash.kashcal.data.db.entity.Attendee>
): List<org.onekash.kashcal.data.calendar_provider.DeviceAttendee> =
    attendees.mapNotNull { a ->
        val bare = org.onekash.kashcal.util.AddressNormalizer.stripMailto(a.address)
        if (!org.onekash.kashcal.util.AddressNormalizer.isEmailShaped(bare)) return@mapNotNull null
        org.onekash.kashcal.data.calendar_provider.DeviceAttendee(
            id = 0L,
            name = a.displayName,
            email = bare,
            relationship = android.provider.CalendarContract.Attendees.RELATIONSHIP_ATTENDEE,
            status = android.provider.CalendarContract.Attendees.ATTENDEE_STATUS_NONE,
        )
    }

/**
 * Seed the attendee picker for a device event from its existing guest list,
 * so an edit diffs against the real set. Excludes the organizer chip (the
 * repository owns the owner row; it isn't a removable guest). Produces Room
 * [org.onekash.kashcal.data.db.entity.Attendee] entities because that's what
 * the shared picker operates on — but only the address/displayName are
 * meaningful; the device save re-bridges them via [pickerAttendeesToDevice].
 */
fun deviceGuestsToPickerSeed(
    guests: List<AttendeeUiModel>
): List<org.onekash.kashcal.data.db.entity.Attendee> =
    guests.filterNot { it.isOrganizer }.map { g ->
        org.onekash.kashcal.data.db.entity.Attendee(
            eventId = 0L,
            address = g.bareAddress,
            displayName = g.displayName,
        )
    }

/**
 * The attendee-editing context for the event form: the resolving account (to
 * mark "You" and as ORGANIZER source) and whether the account can send
 * invitations. See [HomeViewModel.getFormAttendeeContext].
 */
data class FormAttendeeContext(
    val account: org.onekash.kashcal.data.db.entity.Account?,
    val isSchedulable: Boolean
)
