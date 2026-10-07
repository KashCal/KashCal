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
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.preferences.DefaultCalendar
import org.onekash.kashcal.data.contacts.ContactEventUtils
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.preferences.PreferencesKeys
import org.onekash.kashcal.domain.identity.canEditAsOrganizer
import org.onekash.kashcal.domain.identity.effectiveAddresses
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.di.IoDispatcher
import org.onekash.kashcal.domain.changes.RecentChangeItem
import org.onekash.kashcal.domain.changes.isSeriesRow
import org.onekash.kashcal.domain.changes.opens
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.domain.rrule.RruleShift
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.model.SearchResult
import org.onekash.kashcal.domain.reader.DeviceEventReader
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
import org.onekash.kashcal.ui.components.occurrenceDateChanged
import org.onekash.kashcal.ui.components.startEndAnchoredToSeries
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
import org.onekash.kashcal.domain.writer.DeviceEventDraft
import org.onekash.kashcal.domain.writer.DeviceEventWriter
import org.onekash.kashcal.BuildConfig
import org.onekash.kashcal.KashCalApplication
import org.onekash.kashcal.util.DateTimeUtils
import org.onekash.kashcal.util.TimezoneUtils
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
 * A range in epoch millis that keys the time-grid and agenda event StateFlows. The query treats
 * both ends as inclusive ([DisplayEventRepository.getDisplayEventsForRange]). A null key keeps
 * the derived Flow idle.
 */
data class EpochRange(val startMs: Long, val endMs: Long)

/** UI state for the week, 3-day and day grids: timed and all-day events, each sorted by start. */
data class WeekEventsUiState(
    val timedEvents: ImmutableList<DisplayEvent> = persistentListOf(),
    val allDayEvents: ImmutableList<DisplayEvent> = persistentListOf(),
    val isLoading: Boolean = false,
    val error: String? = null
) {
    companion object {
        val EMPTY = WeekEventsUiState()

        /**
         * Minimum duration for a day-crossing timed event to move to the all-day strip.
         * `endDay > startDay` alone also holds for an ordinary event that spills past midnight
         * (11pm to 12:30am); without this floor every such event would leave the grid and show
         * as a bar across two days.
         */
        private const val MIN_MULTIDAY_STRIP_DURATION_MS = 20L * 60 * 60 * 1000

        fun ofError(message: String?) = WeekEventsUiState(error = message ?: "Failed to load events")

        private fun isMultiDayForStrip(event: DisplayEvent): Boolean =
            event.endDay > event.startDay &&
                (event.endTs - event.startTs) >= MIN_MULTIDAY_STRIP_DURATION_MS

        fun fromEvents(
            events: List<DisplayEvent>,
            showMultiDayTimedInAllDayStrip: Boolean =
                PreferencesKeys.DEFAULT_SHOW_MULTIDAY_TIMED_IN_ALLDAY_STRIP
        ): WeekEventsUiState = WeekEventsUiState(
            timedEvents = events.filter {
                !it.isAllDay && (!showMultiDayTimedInAllDayStrip || !isMultiDayForStrip(it))
            }.sortedBy { it.startTs }.toPersistentList(),
            allDayEvents = events.filter {
                it.isAllDay || (showMultiDayTimedInAllDayStrip && isMultiDayForStrip(it))
            }.sortedBy { it.startTs }.toPersistentList(),
            isLoading = false,
            error = null
        )
    }
}

/** Raw range-query result before the multi-day-strip preference partitions it. */
private data class RawWeekEvents(
    val events: List<DisplayEvent> = emptyList(),
    val error: String? = null
) {
    companion object {
        val EMPTY = RawWeekEvents()
    }
}

/** UI state for the agenda's upcoming-events list; [isLoading] drives its spinner. */
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
 * Returns the (startDayCode, endDayCode) range, YYYYMMDD, covering the viewing month and one
 * month either side plus the grid's leading and trailing days, so adjacent month-pager pages have
 * data mid-swipe.
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
 * Holds the state of the main calendar screen: the calendar views, search, sync status, sheets
 * and the event forms' reads and writes.
 *
 * The calendar views read Room and device-calendar events together through
 * [DisplayEventRepository]. Room events are written through [EventCoordinator] and read singly
 * through [EventReader]; device-calendar events are written through [DeviceEventWriter] and read
 * singly through [DeviceEventReader].
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
    private val deviceEventReader: DeviceEventReader,
    private val deviceEventWriter: DeviceEventWriter,
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
        deviceEventReader: DeviceEventReader,
        deviceEventWriter: DeviceEventWriter,
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
        deviceEventReader,
        deviceEventWriter,
        attendeeBackfill,
        contactEmailReader,
        context,
        ioDispatcher,
        { DayPagerUtils.msToDayCode(System.currentTimeMillis()) }
    )

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /**
     * Pinch-zoom hour-heights awaiting persistence. A pinch calls the setter many times per
     * second; [observeHourHeightPersistence] debounces this so only the settled zoom is written.
     * DROP_OLDEST keeps only the latest pending value.
     */
    private val hourHeightToPersist = MutableSharedFlow<Float>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Network connectivity, for the UI. */
    val isOnline: StateFlow<Boolean> = networkMonitor.isOnline

    /** Default reminder for timed events, in minutes before the start. */
    val defaultReminderTimed: StateFlow<Int> = dataStore.defaultReminderMinutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 15)

    /** Default reminder for all-day events, in minutes before the start. */
    val defaultReminderAllDay: StateFlow<Int> = dataStore.defaultAllDayReminder
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1440) // 1 day

    /** Default event duration, in minutes. */
    val defaultEventDuration: StateFlow<Int> = dataStore.defaultEventDuration
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), KashCalDataStore.DEFAULT_EVENT_DURATION_MINUTES)

    /** Whether Quick Add is enabled. */
    val quickAddEnabled: StateFlow<Boolean> = dataStore.quickAddEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * Suggests prior event titles matching [prefix] for the form autocomplete.
     *
     * Returns an empty list when the title-suggestions preference is off. The check lives here
     * so the composable doesn't need to know about the preference.
     */
    suspend fun suggestTitles(prefix: String): List<org.onekash.kashcal.data.db.dao.TitleSuggestion> {
        if (!dataStore.getTitleSuggestionsEnabled()) return emptyList()
        return displayEventRepository.suggestTitles(prefix)
    }

    /** Time format preference: "system", "12h" or "24h". */
    val timeFormat: StateFlow<String> = dataStore.timeFormat
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), KashCalDataStore.TIME_FORMAT_SYSTEM)

    /**
     * App theme, derived from the stored theme string; drives KashCalTheme in MainActivity.
     *
     * A cold flow, not stateIn: the activity seeds the first frame with a synchronous read, and
     * this flow's first emission is the same stored value, so the default theme never flashes on
     * cold start. Later writes recolor the app live.
     */
    val themeMode: Flow<org.onekash.kashcal.ui.theme.ThemeMode> = dataStore.theme
        .map { org.onekash.kashcal.ui.theme.ThemeMode.fromPrefValue(it) }

    /**
     * Where app colors come from (dynamic or accent seed); with no explicit choice, the retired
     * teal theme maps to the seed.
     */
    val colorSource: Flow<org.onekash.kashcal.ui.theme.ColorSource> =
        combine(dataStore.colorSource, dataStore.theme) { explicit, legacyTheme ->
            org.onekash.kashcal.ui.theme.ColorSource.fromPrefValue(explicit, legacyTheme)
        }

    /** Current accent seed color (packed ARGB); meaningful when [colorSource] is SEED. */
    val accentSeed: Flow<Int> = dataStore.accentSeed

    /** First day of week preference: 0=system, 1=Sunday, 2=Monday, 7=Saturday. */
    val firstDayOfWeek: StateFlow<Int> = dataStore.firstDayOfWeek
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Calendar.SUNDAY)

    /**
     * Whether the share-as-card Share icon's first-time coach mark has been shown. False until
     * it first appears, then true for good. The initial value matches the DataStore default
     * (false), so a slow DataStore start doesn't silently suppress the tooltip on a new install.
     */
    val shownShareCardTooltip: StateFlow<Boolean> = dataStore.shownShareCardTooltip
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Persists that the share-card coach mark has been shown. */
    fun markShareCardTooltipShown() {
        viewModelScope.launch { dataStore.setShownShareCardTooltip(true) }
    }

    /**
     * Whether the user permanently declined contact suggestions in the attendee picker. When
     * true, the picker's contacts-permission banner is never shown. Survives restart.
     */
    val contactSuggestionsDeclined: StateFlow<Boolean> = dataStore.contactSuggestionsDeclined
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * Persists that the user declined contact suggestions: "No thanks", or a system-dialog denial
     * that won't prompt again.
     */
    fun declineContactSuggestions() {
        viewModelScope.launch { dataStore.setContactSuggestionsDeclined(true) }
    }

    /** Persists whether the event form's tag row sits above the notes, not below them. */
    fun setTagsAboveNotes(above: Boolean) {
        viewModelScope.launch { dataStore.setTagsAboveNotes(above) }
    }

    /** Persists whether the Agenda view's top week bar is expanded. */
    fun setAgendaWeekBarExpanded(expanded: Boolean) {
        viewModelScope.launch { dataStore.setAgendaWeekBarExpanded(expanded) }
    }

    /** Persists whether the Day view's top week-strip date picker is expanded. */
    fun setDayWeekBarExpanded(expanded: Boolean) {
        viewModelScope.launch { dataStore.setDayWeekBarExpanded(expanded) }
    }

    /** Persists whether the time-grid all-day strip is expanded (up to 3 rows). */
    fun setAllDayRowsExpanded(expanded: Boolean) {
        viewModelScope.launch { dataStore.setAllDayRowsExpanded(expanded) }
    }

    /**
     * Persists the user's avatar initials, normalized so the stored value is at most two
     * uppercase letters, or empty to clear.
     */
    fun setUserInitials(raw: String) {
        viewModelScope.launch { dataStore.setUserInitials(normalizeInitials(raw)) }
    }

    /**
     * Pending CalDAV invitations, the rows of `InvitationInboxSheet`. [pendingInvitationsCount]
     * derives from it, so the badge can't disagree with the sheet.
     */
    val pendingInvitations: StateFlow<List<org.onekash.kashcal.domain.reader.PendingInvitation>> =
        eventReader.getPendingInvitations()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Count of pending CalDAV invitations: the single source of truth for it.
     *
     * The app-bar badge and the Invites overflow menu item both read it, so a re-emission during
     * sync can't drift them apart. `distinctUntilChanged` collapses repeated same-size lists,
     * common when sync writes attendees but the NEEDS-ACTION set is unchanged.
     */
    val pendingInvitationsCount: StateFlow<Int> = pendingInvitations
        .map { it.size }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // Attendee chip surfaces.

    private val quickViewEventId = MutableStateFlow<Long?>(null)
    private val formEventId = MutableStateFlow<Long?>(null)
    private val dayVisibleEventIds = MutableStateFlow<List<Long>>(emptyList())

    /** Attendees of the active quick-view event, for its chips; null when no event is active. */
    val quickViewAttendees: StateFlow<EventAttendeeUiState?> =
        quickViewEventId
            .flatMapLatest { id -> if (id == null) flowOf(null) else buildAttendeeFlow(id) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * The active quick-view event, re-read by id whenever it changes. The sheet renders this over
     * the snapshot taken at tap time, so an edit's new title, time or location shows even when
     * the list that produced the snapshot is stale (search results don't re-run after an edit).
     * Null when no event is active or the event was deleted.
     */
    val quickViewEventLive: StateFlow<Event?> =
        quickViewEventId
            .flatMapLatest { id -> if (id == null) flowOf(null) else eventReader.getEventByIdFlow(id) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Attendees of the event open in the EventFormSheet, for its read-only chip row. */
    val formAttendees: StateFlow<EventAttendeeUiState?> =
        formEventId
            .flatMapLatest { id -> if (id == null) flowOf(null) else buildAttendeeFlow(id) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * Drives the EventFormSheet's read-only banner and disabled Save. True when the event's
     * ORGANIZER doesn't match its calendar's account, or no account resolves; the rule lives in
     * [org.onekash.kashcal.domain.identity.canEditAsOrganizer].
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
     * Attendee models by event ID for the day view's card badges. One Flow for the whole visible
     * set, no per-card subscription; each event's models are built with its own account, so
     * [AttendeeUiModel.isYou] marks the right attendee.
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

    // Time-grid (week, 3-day, day) events. The visible date range is the only key: navigation
    // sets it, and [weekEvents] re-queries when the key or the data changes. A create, edit,
    // delete or sync shows up without a manual reload because
    // [DisplayEventRepository.getDisplayEventsForRange] is reactive (#297). The key is null, and
    // the upstream Flow idle, only until the first time-grid load; leaving the view keeps it.
    private val timeGridRange = MutableStateFlow<EpochRange?>(null)

    /** Sets the visible time-grid range; no caller passes null. */
    private fun setTimeGridRange(range: EpochRange?) {
        timeGridRange.value = range
    }

    /**
     * Week, 3-day and day grid events, split into timed and all-day, with any load error folded
     * in. Re-emits while subscribed whenever a source of
     * [DisplayEventRepository.getDisplayEventsForRange] changes.
     *
     * Emits no loading or empty state on a range change, unlike [agendaEvents]: the grid keeps
     * the last events until the new range resolves, and an empty emission would blank it
     * mid-swipe.
     */
    @Suppress("OPT_IN_USAGE")
    val weekEvents: StateFlow<WeekEventsUiState> =
        timeGridRange
            .flatMapLatest { range ->
                if (range == null) {
                    flowOf(RawWeekEvents.EMPTY)
                } else {
                    displayEventRepository.getDisplayEventsForRange(range.startMs, range.endMs)
                        .map { events -> RawWeekEvents(events) }
                        .catch { e ->
                            if (e is CancellationException) throw e
                            Log.e(TAG, "Error loading time-grid events", e)
                            emit(RawWeekEvents(error = e.message ?: "Failed to load events"))
                        }
                }
            }
            // Combined after flatMapLatest so toggling the preference re-partitions the events in
            // memory; combined before it, a toggle would cancel and restart the DB query.
            .combine(dataStore.showMultiDayTimedInAllDayStrip) { raw, showMultiDayTimedInAllDayStrip ->
                if (raw.error != null) {
                    WeekEventsUiState.ofError(raw.error)
                } else {
                    WeekEventsUiState.fromEvents(raw.events, showMultiDayTimedInAllDayStrip)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WeekEventsUiState.EMPTY)

    // Agenda events: the same range key as the time grid, a [AGENDA_WINDOW_MS] window set on
    // entering the agenda and nulled on leaving, so the upstream Flow and its device-calendar
    // queries go idle while the agenda isn't shown.
    private val agendaRange = MutableStateFlow<EpochRange?>(null)

    /** Sets the agenda window; null clears it when the view is left. */
    private fun setAgendaRange(range: EpochRange?) {
        agendaRange.value = range
    }

    /**
     * Agenda events for the next [AGENDA_WINDOW_MS]: a loading state while the query runs, then
     * the merged Room and device list, or empty on an error. Re-emits while subscribed on the
     * same sources as [weekEvents].
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

    // Full-height month grid events, keyed by the viewing (year, month): set only while
    // MONTH_FULL is active, null otherwise. The repository groups them by day code. The month and
    // year dots aren't reactive: they come from one-shot queries rebuilt explicitly, for example
    // by reloadCurrentView.
    private val monthGridKey = MutableStateFlow<MonthKey?>(null)

    /** Sets the full-height month grid key; null clears it when the view is left. */
    private fun setMonthGridKey(key: MonthKey?) {
        monthGridKey.value = key
    }

    /**
     * Full-height month grid events for [monthGridDayCodeRange], grouped by day code. Re-emits
     * while subscribed on the sources of [DisplayEventRepository.getDisplayEventsForDateRange].
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
     * Emits the attendee state of [eventId] for the quick view and the form.
     *
     * Resolves the event's calendar and account, runs
     * [org.onekash.kashcal.domain.reader.AttendeeBackfill.backfillIfEmpty] once (a failure is
     * logged and skipped), then maps the attendees Flow through [AttendeeUiModel.fromRoom] with
     * that account so the user is marked `isYou`.
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
     * Emits attendee models by event ID for the day pager's card badges (`EventCardAttendeeBadge`).
     *
     * Resolves each event's account once, from the cached `uiState.calendars` and one
     * [AccountRepository.getAccountById] call per distinct account, then maps every emission of
     * the attendees Flow through [AttendeeUiModel.fromRoom], so the badge needn't resolve
     * identity itself.
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

    // Set once the startup sync, or the first sync after account setup, has been requested.
    private var hasTriggeredStartupSync = false

    // Debounced search; cancelled when a new query arrives.
    private var searchJob: Job? = null

    // Reads the Recent changes log while its sheet shows.
    private var recentChangesJob: Job? = null

    /** Checks which series scopes a staged cross-day drop may offer. */
    private var dragAvailabilityJob: Job? = null

    // On-demand month dots load; cancelled by the next one on a fast swipe.
    private var loadDotsJob: Job? = null

    // Debounced occurrence extension; cancelled by the next one on a rapid swipe.
    private var extensionJob: Job? = null
    private var occurrenceRepairDone = false

    // Day pager cache collection; cancelled when the cache is reloaded.
    private var dayEventsCacheJob: Job? = null

    // Debounced day pager range load; cancelled by the next one on a fast swipe.
    private var dayPagerLoadJob: Job? = null

    // Year dots load; cancelled by the next one on a fast year swipe.
    private var yearDotsJob: Job? = null

    // The time-grid range last loaded, so a page inside it doesn't reload; null forces a load.
    private var currentLoadedRange: Pair<LocalDate, LocalDate>? = null

    // Null until the first resume, which only records the day; later resumes snap to today when
    // the day differs.
    private var lastResumeDayCode: Int? = null

    init {
        Log.d(TAG, "ViewModel init")

        // Start the viewing month on today.
        val today = Calendar.getInstance()
        _uiState.update {
            it.copy(
                viewingMonth = today.get(Calendar.MONTH),
                viewingYear = today.get(Calendar.YEAR)
            )
        }

        viewModelScope.launch {
            initializeAsync()
        }

        // Sync status, for the inline banner.
        observeSyncStatus()

        // Sync changes, for the snackbar.
        observeSyncChanges()

        observeDisplaySettings()

        // Device calendar changes, which invalidate the event dots.
        observeDeviceCalendarChanges()

        // Persists the settled pinch-zoom level across restarts.
        observeHourHeightPersistence()
    }

    /**
     * Loads the startup state off the constructor: calendars, account status, onboarding and
     * What's New, the persisted view and time-grid position, then lands on today. A failure is
     * logged and shown as the sync message.
     */
    private suspend fun initializeAsync() {
        try {
            Log.d(TAG, "initializeAsync - START")

            // Calendar visibility comes from Calendar.isVisible, the source of truth in the DB.
            observeCalendars()

            observeDeviceCalendarDrawerState()

            checkAccountStatus()

            // Onboarding shows when no account is configured and it was never dismissed.
            if (!_uiState.value.isConfigured) {
                val dismissed = dataStore.onboardingDismissed.first()
                if (!dismissed) {
                    Log.d(TAG, "Showing onboarding sheet (first launch, no account configured)")
                    _uiState.update { it.copy(showOnboardingSheet = true) }
                }
            }

            // What's New: release notes the user hasn't acknowledged. MainActivity shows them only
            // once onboarding is out of the way. A new install records the current version and
            // shows nothing, so later upgrades are detected.
            initializeWhatsNew()

            // Seed previousNonInsightsMode from the same persisted default, so going back from
            // Insights lands on the user's preferred view, not MONTH. DataStore's VALID_VIEWS
            // refuses to store "insights", so the seed isn't INSIGHTS.
            val defaultView = ViewMode.fromKey(dataStore.getDefaultCalendarView())
            // Seed the persisted time-grid scroll position in the same update that sets viewMode.
            // viewMode starts at MONTH, so the time grid (WeekViewContent) can't compose before
            // this update: the restored value is there on its first composition, before the
            // debounced scroll writer can overwrite it.
            val savedScrollMinutes = dataStore.getWeekViewScrollMinutes()
            // Seed the persisted zoom in the same update. The scroll restore converts the saved
            // clock minutes to pixels with the hour-height, so the zoom must be there on the
            // grid's first composition, or the restore lands on the wrong time. A non-finite
            // stored value falls back to 60 (coerceIn leaves NaN as NaN), and the rest is clamped,
            // so a corrupt value can't render a degenerate grid.
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

            // Event dots for the current month +/- 6 months.
            val today = Calendar.getInstance()
            buildEventDots(today.get(Calendar.YEAR), today.get(Calendar.MONTH))

            // Land on today in the current view. Cold start lands without animation so the month
            // pager settles in one frame; the Today button keeps its animation.
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
     * Sets `isConfigured` to whether an account of a provider with `supportsCalDAV` (iCloud,
     * CalDAV) has credentials.
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
     * Refreshes the account status and the calendars; MainActivity calls it on every resume but
     * the first. When an account is configured and no startup sync was requested yet, it starts
     * a sync with the banner shown.
     */
    fun refreshAccountStatus() {
        viewModelScope.launch {
            checkAccountStatus()

            // observeCalendars follows the DB already; this one-shot reload is a fallback.
            loadCalendars()

            if (_uiState.value.isConfigured && !hasTriggeredStartupSync) {
                // First sync after account setup: the banner confirms it to the user.
                hasTriggeredStartupSync = true
                syncScheduler.setShowBannerForSync(true)
                Log.d(TAG, "refreshAccountStatus: First sync after account setup (with banner)")
                performSync()
            }

            // Rebuild the dots for any new calendars.
            reloadCurrentView()
        }
    }

    // ==================== Startup Sync ====================

    /**
     * Starts the silent startup sync once per ViewModel, when an account is configured. Called
     * from MainActivity's LaunchedEffect, so the lifecycle is STARTED.
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
        syncScheduler.setShowBannerForSync(false)
        Log.d(TAG, "triggerStartupSync: Starting sync (silent)")
        performSync(SyncTrigger.FOREGROUND_APP_OPEN)
    }

    // ==================== Sync Status Observation ====================

    /**
     * Mirrors the immediate sync's WorkManager status into the sync flags and the banner.
     *
     * The banner follows `syncScheduler.showBannerForSync`: set for a forced full sync and the
     * first sync after account setup, clear for the startup, resume and pull-to-refresh syncs.
     * A partial error shows the banner regardless. A failure shows it only with the flag; a
     * failed pull-to-refresh goes to [showError] instead.
     */
    private fun observeSyncStatus() {
        viewModelScope.launch {
            syncScheduler.observeImmediateSyncStatus().collect { status ->
                val showBanner = syncScheduler.showBannerForSync.value
                Log.d(TAG, "Sync status changed: $status (showBanner=$showBanner)")
                when (status) {
                    is SyncStatus.Running, is SyncStatus.Enqueued -> {
                        // isSyncing is the duplicate-sync guard, true while work is live. It
                        // isn't the spinner: showRefreshSpinner is set only by an in-session
                        // pull-to-refresh, so a status replayed into a new process never shows
                        // it. The banner shows only with the flag.
                        _uiState.update {
                            it.copy(
                                isSyncing = true,
                                showSyncBanner = showBanner,
                                syncBannerState = if (status is SyncStatus.Running)
                                    SyncBannerState.Syncing else SyncBannerState.Preparing,
                                syncErrorDetail = null
                            )
                        }
                    }
                    is SyncStatus.Succeeded -> {
                        occurrenceRepairDone = false
                        val hasPartialError = status.errorMessage != null
                        _uiState.update {
                            it.copy(
                                isSyncing = false,
                                showRefreshSpinner = false,
                                showSyncBanner = showBanner || hasPartialError,
                                syncBannerState = if (hasPartialError)
                                    SyncBannerState.PartialError else SyncBannerState.Success,
                                syncErrorDetail = null
                            )
                        }
                        reloadCurrentView()
                        // Auto-dismiss.
                        if (showBanner || hasPartialError) {
                            delay(if (hasPartialError) 3000 else 2000)
                            _uiState.update { it.copy(showSyncBanner = false) }
                            syncScheduler.resetBannerFlag()
                        }
                    }
                    is SyncStatus.Failed -> {
                        val wasPull = _uiState.value.showRefreshSpinner
                        if (wasPull) {
                            // A pull-to-refresh failed: report it through showError, with no
                            // banner.
                            _uiState.update {
                                it.copy(
                                    isSyncing = false,
                                    showRefreshSpinner = false,
                                    showSyncBanner = false,
                                    syncBannerState = SyncBannerState.Syncing,
                                    syncErrorDetail = null
                                )
                            }
                            // Clear the banner flag so a stale one (for example from a force sync
                            // on another screen) can't leak a banner into the next silent sync.
                            syncScheduler.resetBannerFlag()
                            showError(CalendarError.Unknown(status.errorMessage ?: "Sync failed"))
                        } else {
                            // Banner only with the flag (force sync, first sync after setup); a
                            // silent sync's failure shows nothing on a normal app open.
                            _uiState.update {
                                it.copy(
                                    isSyncing = false,
                                    showRefreshSpinner = false,
                                    showSyncBanner = showBanner,
                                    syncBannerState = SyncBannerState.Error,
                                    syncErrorDetail = status.errorMessage
                                )
                            }
                            if (showBanner) {
                                delay(3000)
                                _uiState.update { it.copy(showSyncBanner = false) }
                                syncScheduler.resetBannerFlag()
                            }
                        }
                    }
                    is SyncStatus.Idle, is SyncStatus.Cancelled, is SyncStatus.Blocked -> {
                        _uiState.update {
                            it.copy(
                                showSyncBanner = false,
                                showRefreshSpinner = false,
                                isSyncing = false,
                                syncBannerState = SyncBannerState.Syncing  // No stale Error flash
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Shows a snackbar for every worker sync that recorded Recent changes, whatever triggered it,
     * with a "View" action that opens the Recent changes sheet. The published entries are
     * cleared once shown, so a later ViewModel doesn't show them again; the log stays in Room.
     */
    private fun observeSyncChanges() {
        viewModelScope.launch {
            syncScheduler.lastSyncChanges.collect { changes ->
                if (changes.isNotEmpty()) {
                    val message = generateSnackbarMessage(changes, context.resources)
                    if (message != null) {
                        Log.d(TAG, "Sync changes notification: ${changes.size} changes")
                        showSnackbar(message) { openRecentChanges() }
                    }
                    syncScheduler.clearSyncChanges()
                }
            }
        }
    }

    /**
     * Copies the display preferences into uiState as they change (emojis, time format, first day
     * of week, week numbers, the bars' expanded states, tag placement, initials), plus the recent
     * tags and tag colors.
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
     * Rebuilds the event dots on each device calendar change signal (a ContentObserver signal
     * from CalendarProviderManager). The day pager, agenda, time grid and month grid follow the
     * signal themselves through DisplayEventRepository's combined flows.
     */
    private fun observeDeviceCalendarChanges() {
        viewModelScope.launch {
            displayEventRepository.deviceCalendarChangeSignal
                .collect { signal ->
                    if (signal > 0) {
                        _uiState.update {
                            it.copy(
                                loadedMonths = persistentSetOf(),
                                eventDots = persistentMapOf()
                            )
                        }
                        // Rebuild around the viewing month.
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
     * Runs a pull-to-refresh sync, the only sync that shows the refresh spinner, and a contact
     * sync. Unconfigured it shows a snackbar and offline an error; while a sync runs it does
     * nothing.
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
        _uiState.update { it.copy(showRefreshSpinner = true) }
        syncScheduler.setShowBannerForSync(false)
        Log.d(TAG, "Pull-to-refresh: starting sync (with spinner)")
        performSync(SyncTrigger.FOREGROUND_PULL_TO_REFRESH)
        // The contact worker syncs only accounts with contact sync enabled, so requesting it
        // unconditionally is cheap.
        syncScheduler.requestImmediateContactSync()
    }

    /**
     * Requests a full sync that ignores the ctag and sync-token, with the banner, unless a sync
     * is running.
     */
    fun forceFullSync() {
        if (_uiState.value.isSyncing) {
            Log.d(TAG, "Sync already in progress, ignoring force sync")
            return
        }
        syncScheduler.setShowBannerForSync(true)
        Log.d(TAG, "Force full sync requested (with banner)")

        // A forced sync gives parse failures a fresh set of retries (v16.7.0).
        viewModelScope.launch {
            dataStore.clearAllParseFailureRetries()
        }

        syncScheduler.requestImmediateSync(
            forceFullSync = true,
            trigger = SyncTrigger.FOREGROUND_MANUAL,
            showNotification = true
        )
    }

    /**
     * Starts a silent sync unless one is running. MainActivity.onResume calls it on a return
     * from outside the app.
     *
     * No cooldown; every resume syncs, because:
     * - Casual users have long gaps (hours) between app opens anyway
     * - The ctag check is lightweight (~50ms) if nothing changed
     * - Shared calendar users need fresh data when returning to the app
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
        syncScheduler.setShowBannerForSync(false)
        performSync(SyncTrigger.FOREGROUND_APP_OPEN)
    }

    /**
     * Requests an immediate sync, unless unconfigured or already syncing.
     *
     * Sets isSyncing at once as the duplicate-sync guard, then enqueues the work. Every other
     * state update (isSyncing false, reloadCurrentView) comes from [observeSyncStatus] as
     * WorkManager reports the status.
     *
     * @param trigger the trigger recorded in the sync history
     */
    private fun performSync(trigger: SyncTrigger = SyncTrigger.FOREGROUND_MANUAL) {
        if (!_uiState.value.isConfigured) {
            Log.d(TAG, "performSync: Not configured, skipping")
            return
        }
        // A second enqueue would replace the live sync, and could attach an in-flight pull's
        // spinner to a different silent sync, whose failure would then read as a pull failure.
        // Not every caller checks isSyncing, so the guard lives here.
        if (_uiState.value.isSyncing) {
            Log.d(TAG, "performSync: Sync already in progress, skipping")
            return
        }

        // Closes the window before observeSyncStatus receives Running. Only a pull-to-refresh
        // sets the spinner (showRefreshSpinner), so this guard shows nothing on its own.
        _uiState.update { it.copy(isSyncing = true) }

        Log.d(TAG, "performSync: Requesting immediate sync (trigger=${trigger.name})")
        syncScheduler.requestImmediateSync(trigger = trigger)
    }

    // ==================== Calendar Loading ====================

    /**
     * Keeps the calendars, their drawer groups, the device calendar groups and the default
     * calendar in uiState as the DB and preferences change.
     *
     * The default is the user's preference from Settings: a device calendar as stored, a Room
     * calendar only while it exists, otherwise null.
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
                    val deviceCalendars = deviceEventReader.getEnabledDeviceCalendars(deviceEnabled, enabledIds)
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

    /** Loads the same state as [observeCalendars] once, for a manual refresh. */
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
                    val deviceCalendars = deviceEventReader.getEnabledDeviceCalendars(deviceEnabled, enabledIds)
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
     * Keeps the device calendar drawer state in uiState: the feature flag, the enabled calendars
     * and the hidden IDs. Only a change to the flag or the enabled IDs queries the provider for
     * the calendar list; a hidden-ID change only updates state.
     */
    private fun observeDeviceCalendarDrawerState() {
        viewModelScope.launch {
            combine(
                dataStore.deviceCalendarsEnabled,
                dataStore.enabledDeviceCalendarIds
            ) { enabled, enabledIds ->
                Pair(enabled, enabledIds)
            }.collect { (enabled, enabledIds) ->
                val deviceCalendars = deviceEventReader.getEnabledDeviceCalendars(enabled, enabledIds)
                _uiState.update {
                    it.copy(
                        deviceCalendarsEnabled = enabled,
                        enabledDeviceCalendars = deviceCalendars.toPersistentList()
                    )
                }
            }
        }
        viewModelScope.launch {
            dataStore.hiddenDeviceCalendarIds.collect { hiddenIds ->
                _uiState.update {
                    it.copy(hiddenDeviceCalendarIds = hiddenIds.toPersistentSet())
                }
            }
        }
    }

    /** Reloads the calendars once. */
    fun refreshCalendars() {
        loadCalendars()
    }

    // ==================== Calendar Visibility ====================

    /** Toggles a Room calendar's visibility, stored in Calendar.isVisible (the source of truth). */
    fun toggleCalendarVisibility(calendarId: Long) {
        viewModelScope.launch {
            val calendar = _uiState.value.calendars.find { it.id == calendarId }
            val newVisible = !(calendar?.isVisible ?: true)

            // The calendars Flow carries the change to the UI.
            eventCoordinator.setCalendarVisibility(calendarId, newVisible)

            // Only the dots need a rebuild: they come from a one-shot query, and the other
            // views follow the visibility through their Flows.
            buildEventDots(_uiState.value.viewingYear, _uiState.value.viewingMonth)
        }
    }

    /** Makes every Room calendar visible (Calendar.isVisible). */
    fun showAllCalendars() {
        viewModelScope.launch {
            _uiState.value.calendars.forEach { calendar ->
                eventCoordinator.setCalendarVisibility(calendar.id, true)
            }
            // Only the dots need a rebuild, as in toggleCalendarVisibility.
            buildEventDots(_uiState.value.viewingYear, _uiState.value.viewingMonth)
        }
    }

    /**
     * Toggles a device calendar's visibility in the drawer. It's stored in the hidden-IDs
     * preference and doesn't affect reminders or which calendars are enabled.
     */
    fun toggleDeviceCalendarVisibility(calendarId: Long) {
        viewModelScope.launch {
            dataStore.toggleDeviceCalendarHidden(calendarId)
            reloadCurrentView()
        }
    }

    // ==================== Event Dots ====================

    /**
     * Encodes a year and 0-indexed month as `year * 12 + month`, so month ranges compare and step
     * across year boundaries.
     */
    private fun encodeMonth(year: Int, month: Int): Int = year * 12 + month

    /** Decodes [encodeMonth]'s value back to (year, 0-indexed month). */
    private fun decodeMonth(encoded: Int): Pair<Int, Int> = (encoded / 12) to (encoded % 12)

    /**
     * Returns true when a month's dots finished loading. `loadedMonths` records only completed
     * loads, so a cancelled one isn't a cache hit.
     */
    private fun isMonthCached(year: Int, month: Int): Boolean {
        val encoded = encodeMonth(year, month)
        return encoded in _uiState.value.loadedMonths
    }

    /** Loads a month's dots unless they're cached. */
    private fun ensureDotsForMonth(year: Int, month: Int) {
        if (!isMonthCached(year, month)) {
            loadDotsForMonth(year, month)
        }
    }

    /**
     * Loads one month's dots on demand, for months outside the initial cache. Cancels a load
     * still running, for a fast swipe.
     */
    private fun loadDotsForMonth(year: Int, month: Int) {
        loadDotsJob?.cancel()

        loadDotsJob = viewModelScope.launch {
            try {
                // month is 0-indexed (Calendar.MONTH); LocalDate's is 1-indexed.
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

                val currentDots = _uiState.value.eventDots.toMutableMap()
                currentDots[monthKey] = monthDots.mapValues { it.value.toPersistentList() }.toPersistentMap()

                // Marked only once loaded, so a cancelled load leaves the month uncached.
                val loadedMonthEncoded = encodeMonth(year, month)
                _uiState.update {
                    it.copy(
                        eventDots = currentDots.toPersistentMap(),
                        loadedMonths = it.loadedMonths.add(loadedMonthEncoded)
                    )
                }

                Log.d(TAG, "Loaded dots for $year-${month + 1}, total cached months: ${_uiState.value.loadedMonths.size}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error loading dots for month $year-${month + 1}", e)
            }
        }
    }

    /**
     * Rebuilds the event dots for the given month +/- 6 months, replacing the cached dots and
     * the loaded-months set.
     */
    private fun buildEventDots(year: Int, month: Int) {
        viewModelScope.launch {
            try {
                val dots = mutableMapOf<String, MutableMap<Int, MutableList<Int>>>()

                val centerEncoded = encodeMonth(year, month)
                val startEncoded = centerEncoded - 6
                val endEncoded = centerEncoded + 6

                val (startYear, startMonth) = decodeMonth(startEncoded)
                val (endYear, endMonth) = decodeMonth(endEncoded)
                val firstDay = LocalDate.of(startYear, startMonth + 1, 1)
                val lastDay = LocalDate.of(endYear, endMonth + 1, 1)
                    .withDayOfMonth(LocalDate.of(endYear, endMonth + 1, 1).lengthOfMonth())
                val startDayCode = firstDay.year * 10000 + firstDay.monthValue * 100 + firstDay.dayOfMonth
                val endDayCode = lastDay.year * 10000 + lastDay.monthValue * 100 + lastDay.dayOfMonth

                // Room and device events, grouped by day.
                val eventsMap = withContext(ioDispatcher) {
                    displayEventRepository.getDisplayEventsGroupedByDayOnce(startDayCode, endDayCode)
                }

                // The repository already puts a multi-day event in each of its days.
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

                val immutableDots = dots.mapValues { (_, monthMap) ->
                    monthMap.mapValues { (_, dayColors) -> dayColors.toPersistentList() }.toPersistentMap()
                }.toPersistentMap()

                val loadedMonthsSet = (startEncoded..endEncoded)
                    .toSet()
                    .toPersistentSet()

                _uiState.update {
                    it.copy(
                        eventDots = immutableDots,
                        loadedMonths = loadedMonthsSet
                    )
                }

                Log.d(TAG, "Built event dots for ${dots.size} months, loaded ${loadedMonthsSet.size} months: $startYear-${startMonth + 1} to $endYear-${endMonth + 1}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error building event dots", e)
            }
        }
    }

    // ==================== Year View Dots ====================

    /**
     * Loads the event dots for a whole year and merges them into the cached dots. Cancels a
     * load still running, for a fast swipe.
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

                // Merged per month: a month this load found no events in keeps its cached dots.
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

    /** Loads a year's dots unless they're cached. */
    fun ensureDotsForYear(year: Int) {
        if (year !in _uiState.value.loadedYears) {
            loadYearDots(year)
        }
    }

    // ==================== Navigation ====================

    /**
     * Moves the current view to today: the day and week pagers to today's page, the agenda to
     * its top, the month views to today's month with today selected, the year view to this
     * year. Insights is left alone.
     *
     * @param animate true (the Today button) animates the month pager's scroll; false (the
     *   cold-start landing) jumps so the pager settles in one frame. Only the month views read
     *   it.
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

    /** Clears the navigate-to-today flag once the UI has consumed it. */
    fun clearNavigateToToday() {
        _uiState.update { it.copy(pendingNavigateToToday = false) }
    }

    /** Clears the instant navigate-to-today flag once the UI has consumed it. */
    fun clearNavigateToTodayInstant() {
        _uiState.update { it.copy(pendingNavigateToTodayInstant = false) }
    }

    /**
     * Moves the month pager to [date]'s month and selects [date]. MainActivity calls it for a
     * GoToDate pending action (a widget's go-to-date), after a Quick Add save, and to land on a
     * device event's start date when its occurrence doesn't resolve.
     */
    fun navigateToDate(date: LocalDate) {
        _uiState.update {
            it.copy(
                viewingYear = date.year,
                viewingMonth = date.monthValue - 1,  // 0-indexed
                pendingNavigateToMonth = date.year to (date.monthValue - 1)
            )
        }

        val dateMs = date.atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        selectDate(dateMs)
    }

    /** Clears the scroll-agenda-to-top flag once the UI has consumed it. */
    fun clearScrollAgendaToTop() {
        _uiState.update { it.copy(pendingScrollAgendaToTop = false) }
    }

    /** Moves the month pager to a month, closes the year overlay and loads its dots if needed. */
    fun navigateToMonth(year: Int, month: Int) {
        _uiState.update {
            it.copy(
                viewingYear = year,
                viewingMonth = month,
                pendingNavigateToMonth = year to month,
                showYearOverlay = false
            )
        }

        // Loads only an uncached month, not a full rebuild.
        ensureDotsForMonth(year, month)
    }

    /** Clears the navigate-to-month flag once the UI has consumed it. */
    fun clearNavigateToMonth() {
        _uiState.update { it.copy(pendingNavigateToMonth = null) }
    }

    /** Sets the viewing month after a month-pager swipe and loads what that month needs. */
    fun setViewingMonth(year: Int, month: Int) {
        _uiState.update {
            it.copy(
                viewingYear = year,
                viewingMonth = month
            )
        }

        // MONTH_FULL skips the dots: its grid has the full events.
        if (_uiState.value.viewMode != ViewMode.MONTH_FULL) {
            ensureDotsForMonth(year, month)
        }

        if (_uiState.value.viewMode == ViewMode.MONTH_FULL) {
            setMonthGridKey(MonthKey(year, month))
        }

        triggerOccurrenceExtension(year, month)
    }

    /**
     * Extends recurring events' occurrences to reach the navigated month, forward or back, 500 ms
     * after the last swipe. Each run also repairs events missing occurrences, until a run repairs
     * nothing (reset after each successful sync). Reloads the month's dots when anything
     * changed.
     */
    private fun triggerOccurrenceExtension(year: Int, month: Int) {
        extensionJob?.cancel()
        extensionJob = viewModelScope.launch {
            delay(500L)

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

    /** Moves the day or week pager back one step (the view mode's step); no-op at page 0. */
    fun navigateDaysPagerPrevious() {
        val currentPage = _uiState.value.weekViewPagerPosition
        if (currentPage <= 0) return
        val step = _uiState.value.viewMode.pagerNextStep ?: return
        val targetPage = currentPage - step
        _uiState.update { it.copy(pendingWeekViewPagerPosition = targetPage) }
        onDayPagerPageChanged(targetPage)
    }

    /** Moves the day or week pager forward one step (the view mode's step). */
    fun navigateDaysPagerNext() {
        val currentPage = _uiState.value.weekViewPagerPosition
        val step = _uiState.value.viewMode.pagerNextStep ?: return
        val targetPage = currentPage + step
        _uiState.update { it.copy(pendingWeekViewPagerPosition = targetPage) }
        onDayPagerPageChanged(targetPage)
    }

    /**
     * Moves the time grid to today: CENTER_WEEK_PAGE in WEEK mode, CENTER_DAY_PAGE in DAY and
     * THREE_DAYS. Forces a reload.
     */
    fun goToTodayWeek() {
        val targetPage = if (_uiState.value.viewMode == ViewMode.WEEK)
            WeekViewUtils.CENTER_WEEK_PAGE else WeekViewUtils.CENTER_DAY_PAGE

        currentLoadedRange = null

        _uiState.update {
            it.copy(pendingWeekViewPagerPosition = targetPage)
        }
        onDayPagerPageChanged(targetPage)
    }

    // ==================== Infinite Day Pager Functions ====================

    /**
     * Records the time-grid pager's page and, 300 ms after the last change, sets the load range
     * around it unless the visible days are already loaded.
     *
     * @param currentPage the leftmost visible page
     */
    fun onDayPagerPageChanged(currentPage: Int) {
        // Updated at once for the context-aware FAB.
        _uiState.update { it.copy(weekViewPagerPosition = currentPage) }

        dayPagerLoadJob?.cancel()
        dayPagerLoadJob = viewModelScope.launch {
            delay(300)

            // Week mode pages by week; the day and 3-day modes page by day.
            val isWeekMode = _uiState.value.viewMode == ViewMode.WEEK
            val firstDayOfWeek = _uiState.value.firstDayOfWeek
            val (visibleStart, visibleEnd) = if (isWeekMode) {
                val start = WeekViewUtils.weekPageToStartDate(currentPage, firstDayOfWeek)
                start to start.plusDays(6)
            } else {
                WeekViewUtils.getVisibleDateRange(currentPage)
            }
            val (loadStart, loadEnd) = if (isWeekMode) {
                // The week plus one week either side.
                val start = WeekViewUtils.weekPageToStartDate(currentPage, firstDayOfWeek)
                start.minusDays(7) to start.plusDays(13)
            } else {
                WeekViewUtils.getLoadingDateRange(currentPage)
            }

            currentLoadedRange?.let { (loadedStart, loadedEnd) ->
                if (visibleStart >= loadedStart && visibleEnd <= loadedEnd) {
                    Log.d(TAG, "Day pager: range already loaded, skipping")
                    return@launch
                }
            }

            Log.d(TAG, "Day pager: loading range $loadStart to $loadEnd")
            loadEventsForDateRange(loadStart, loadEnd)
            currentLoadedRange = loadStart to loadEnd
        }
    }

    /**
     * Returns the start for a new event from the time-grid FAB: today at the next hour, the same
     * default as the other FABs. The grid's scroll position and zoom only restore where the
     * grid was looking; they deliberately don't seed the event.
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
     * Sets the time-grid range key to [startDate]..[endDate], both inclusive; [weekEvents] does
     * the loading.
     */
    private fun loadEventsForDateRange(startDate: LocalDate, endDate: LocalDate) {
        val startMs = WeekViewUtils.dateToEpochMs(startDate)
        val endMs = WeekViewUtils.dateToEpochMs(endDate.plusDays(1)) // exclusive end

        setTimeGridRange(EpochRange(startMs, endMs))
    }

    /**
     * Loads today's range and returns today's page for the pager to scroll to: CENTER_WEEK_PAGE
     * in WEEK mode, else CENTER_DAY_PAGE.
     */
    fun goToTodayInDayPager(): Int {
        val targetPage = if (_uiState.value.viewMode == ViewMode.WEEK)
            WeekViewUtils.CENTER_WEEK_PAGE else WeekViewUtils.CENTER_DAY_PAGE

        currentLoadedRange = null

        onDayPagerPageChanged(targetPage)

        return targetPage
    }

    /** Loads [dateMs]'s range and returns its page (week page in WEEK mode) for the pager. */
    fun navigateDayPagerToDate(dateMs: Long): Int {
        val date = WeekViewUtils.epochMsToDate(dateMs)
        val targetPage = if (_uiState.value.viewMode == ViewMode.WEEK)
            WeekViewUtils.dateToWeekPage(date, _uiState.value.firstDayOfWeek)
        else WeekViewUtils.dateToPage(date)

        currentLoadedRange = null

        onDayPagerPageChanged(targetPage)

        return targetPage
    }

    /** Keeps the time grid's scroll position for this session, in pixels, in memory only. */
    fun setWeekViewScrollPosition(position: Int) {
        _uiState.update { it.copy(weekViewScrollPosition = position) }
    }

    /**
     * Persists the time-grid scroll position as minutes from midnight so it survives a restart.
     * Clock time, unlike pixels, restores to the same time after a zoom change. The grid calls
     * this on a longer debounce than the in-session pixel path.
     */
    fun setWeekViewScrollMinutes(minutesOfDay: Int) {
        viewModelScope.launch {
            dataStore.setWeekViewScrollMinutes(minutesOfDay)
        }
    }

    fun setWeekViewHourHeight(height: Float) {
        val clamped = height.coerceIn(WeekViewUtils.MIN_HOUR_HEIGHT_DP, WeekViewUtils.MAX_HOUR_HEIGHT_DP)
        _uiState.update { it.copy(weekViewHourHeight = clamped) }
        // Queued for debounced persistence.
        hourHeightToPersist.tryEmit(clamped)
    }

    /**
     * Persists the settled pinch-zoom level, debounced so a pinch writes DataStore once, not once
     * per frame. [initializeAsync] restores it on cold launch.
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

    /** Records the time-grid pager position, for the context-aware FAB. */
    fun setWeekViewPagerPosition(position: Int) {
        _uiState.update { it.copy(weekViewPagerPosition = position) }
    }

    /** Shows the time grid's date picker. */
    fun showWeekViewDatePicker() {
        _uiState.update { it.copy(showWeekViewDatePicker = true) }
    }

    /** Hides the time grid's date picker. */
    fun hideWeekViewDatePicker() {
        _uiState.update { it.copy(showWeekViewDatePicker = false) }
    }

    /** Closes the time grid's date picker and moves the pager to the picked date. */
    fun onWeekViewDateSelected(dateMs: Long) {
        hideWeekViewDatePicker()

        val date = WeekViewUtils.epochMsToDate(dateMs)
        val targetPage = if (_uiState.value.viewMode == ViewMode.WEEK)
            WeekViewUtils.dateToWeekPage(date, _uiState.value.firstDayOfWeek)
        else WeekViewUtils.dateToPage(date)

        currentLoadedRange = null

        _uiState.update {
            it.copy(pendingWeekViewPagerPosition = targetPage)
        }
        onDayPagerPageChanged(targetPage)
    }

    /**
     * Opens one day from a week or 3-day column header. The mode switch must land first, so
     * the navigation resolves against the DAY pager, not the week pager it came from.
     */
    fun onWeekViewDayHeaderClick(date: LocalDate) {
        // Transient: drilling in shouldn't change what the app opens in.
        setViewMode(ViewMode.DAY, persist = false)
        onWeekViewDateSelected(WeekViewUtils.dateToEpochMs(date))
    }

    /** Clears the pending pager position once the UI has consumed it. */
    fun clearPendingWeekViewPagerPosition() {
        _uiState.update { it.copy(pendingWeekViewPagerPosition = null) }
    }

    // ==================== Day Selection ====================

    /**
     * Selects a date and sets its label.
     *
     * The timestamp may carry a time of day (cold start passes Calendar.getInstance(), others an
     * event start). It's normalized to that day's local midnight so every selectedDate writer
     * agrees: the day pager's page math, month sync and dot highlighting key off the day, and a
     * time-bearing value would force a redundant rewrite when those midnight-based paths echo
     * back. 0L, "no selection", is kept as is.
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
     * Keeps the day pager cache filled with the events around [centerDateMs], grouped by day
     * code ([DisplayEventRepository.getDisplayEventsForDayRange]), updating as they change.
     * Marks the 7 days from 3 before to 3 after as loaded.
     */
    fun loadEventsForDayPagerRange(centerDateMs: Long) {
        dayEventsCacheJob?.cancel()

        Log.d(TAG, "Day pager cache: loading range centered on ${DayPagerUtils.msToDayCode(centerDateMs)}")

        dayEventsCacheJob = viewModelScope.launch {
            try {
                // Merged Room and device events; a multi-day event is in each of its days.
                displayEventRepository.getDisplayEventsForDayRange(centerDateMs)
                    .collect { grouped ->
                        // Loaded days, empty ones included.
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

    // ==================== Day Pager Cache Refresh ====================

    /**
     * Returns true when the day pager cache needs a reload for the page at [currentDateMs]: the
     * cache is empty, or the page is more than a day from its center.
     */
    fun shouldRefreshDayPagerCache(currentDateMs: Long): Boolean {
        val cacheCenter = _uiState.value.cacheRangeCenter
        if (cacheCenter == 0L) return true

        val distanceFromCenter = kotlin.math.abs(currentDateMs - cacheCenter)
        // Leaves at least 2 loaded days either side of the page.
        return distanceFromCenter > DayPagerUtils.DAY_MS
    }

    /** Formats a date in the locale's long form, e.g. "December 17, 2024" in English. */
    private fun formatDateLabel(dateMillis: Long): String {
        val format = SimpleDateFormat(DateTimeUtils.localizedPattern("yMMMMd"), Locale.getDefault())
        return format.format(dateMillis)
    }

    // ==================== Search ====================

    /** Opens search with an empty query and the Upcoming filter. */
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

    /** Closes search and resets its state, the date filter included. */
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
     * Sets the search query and searches 300 ms later, cancelling a pending search. A query
     * under 2 characters clears the results instead.
     */
    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }

        searchJob?.cancel()

        if (query.length >= 2) {
            searchJob = viewModelScope.launch {
                delay(300)
                performSearch(query)
            }
        } else {
            _uiState.update { it.copy(searchResults = persistentListOf()) }
        }
    }

    // ==================== Search Date Filter ====================

    /**
     * Sets the search date filter, closes the date picker and re-runs a query of 2 or more
     * characters. Called for a filter chip tap and a date picked in the picker.
     */
    fun setSearchDateFilter(filter: DateFilter) {
        _uiState.update {
            it.copy(
                searchDateFilter = filter,
                showSearchDatePicker = false,
                searchDateRangeStart = null
            )
        }

        if (_uiState.value.searchQuery.length >= 2) {
            performSearch(_uiState.value.searchQuery)
        }
    }

    /** Shows the search date picker sheet with no range started. */
    fun showSearchDatePicker() {
        _uiState.update {
            it.copy(
                showSearchDatePicker = true,
                searchDateRangeStart = null
            )
        }
    }

    /** Hides the search date picker sheet and drops a started range. */
    fun hideSearchDatePicker() {
        _uiState.update {
            it.copy(
                showSearchDatePicker = false,
                searchDateRangeStart = null
            )
        }
    }

    /**
     * Handles a tap on a date in the search date picker:
     * - First tap: stores the date as the range start
     * - Second tap on the same day: applies a SingleDay filter
     * - Second tap on another day: applies a CustomRange filter, earlier day first
     */
    fun onSearchDateSelected(dateMs: Long) {
        val rangeStart = _uiState.value.searchDateRangeStart

        if (rangeStart == null) {
            _uiState.update { it.copy(searchDateRangeStart = dateMs) }
        } else {
            val normalizedStart = normalizeToMidnight(rangeStart)
            val normalizedEnd = normalizeToMidnight(dateMs)

            val filter = if (normalizedStart == normalizedEnd) {
                DateFilter.SingleDay(dateMs)
            } else {
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

    /** Returns the start of [epochMs]'s day in the system timezone. */
    private fun normalizeToMidnight(epochMs: Long): Long {
        val instant = Instant.ofEpochMilli(epochMs)
        val localDate = instant.atZone(ZoneId.systemDefault()).toLocalDate()
        return localDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    /**
     * Searches Room and device events for [query] under the current date filter and shows the
     * results from visible calendars. Errors are logged and leave the results as they were.
     *
     * Room time filters use the occurrences table: the default Upcoming filter keeps events
     * with an occurrence not yet ended, so a multi-day event in progress and a series both
     * match; a filter with a time range keeps events with an occurrence in it; [DateFilter.AnyTime]
     * keeps past events too.
     */
    private fun performSearch(query: String) {
        viewModelScope.launch {
            try {
                val dateFilter = _uiState.value.searchDateFilter
                val timeRange = dateFilter.getTimeRange(ZoneId.systemDefault(), _uiState.value.firstDayOfWeek)
                val calendarMap = _uiState.value.calendars.associateBy { it.id }

                // Day range for the device calendar search.
                val today = LocalDate.now()
                val todayCode = today.year * 10000 + today.monthValue * 100 + today.dayOfMonth
                val (searchStartDayCode, searchEndDayCode) = when {
                    timeRange != null -> {
                        DayPagerUtils.msToDayCode(timeRange.first) to DayPagerUtils.msToDayCode(timeRange.second)
                    }
                    dateFilter is DateFilter.AnyTime -> {
                        val syncPastDays = dataStore.syncPastDays.first()
                        val pastDate = if (syncPastDays == Int.MAX_VALUE) {
                            today.minusYears(10)  // A practical limit for the device search
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

                // Room FTS search, each result shown at its next occurrence (or its own start).
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

                val results = withContext(ioDispatcher) {
                    displayEventRepository.searchDisplayEvents(
                        query, searchStartDayCode, searchEndDayCode, roomSearcher
                    )
                }

                // Room results from visible calendars (Calendar.isVisible).
                val visibleCalendarIds = _uiState.value.calendars
                    .filter { it.isVisible }
                    .map { it.id }
                    .toSet()
                val filteredResults = results.filter { result ->
                    when (val de = result.displayEvent) {
                        is DisplayEvent.Room -> de.event.calendarId in visibleCalendarIds
                        is DisplayEvent.Device -> true // only visible ones were searched
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
            // Persist first, so a process death between the UI clear and the write can't
            // re-show the sheet on the next launch.
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
     * Sets the release notes to show. With nothing stored yet (0), first seeds the last-shown
     * version from KashCalApplication's previous-version record ([WhatsNewSeeder]): an upgrading
     * user gets the notes since that version, a new install records the current version and
     * sees none. Then [WhatsNewGate] picks the releases.
     *
     * DataStore IO failures must never propagate from a viewModelScope.launch: they would
     * escape to Looper.main and crash the app on cold start.
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
        // ModalBottomSheet's onDismissRequest can fire more than once during the dismiss
        // animation; the empty-list check makes a second call a no-op, with no duplicate write.
        if (_uiState.value.whatsNewReleases.isEmpty()) return
        viewModelScope.launch {
            // Persist first, so a process death between the UI clear and the write can't
            // re-show the release notes on the next launch.
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

    /**
     * Opens the Recent changes sheet and reads the log while it shows. The expiry window
     * ([EventReader.observeRecentChanges]) starts from now each time the sheet opens.
     */
    fun openRecentChanges() {
        _uiState.update { it.copy(isRecentChangesOpen = true) }
        recentChangesJob?.cancel()
        recentChangesJob = viewModelScope.launch {
            eventReader.observeRecentChanges(System.currentTimeMillis()).collect { items ->
                _uiState.update { it.copy(recentChanges = items.toPersistentList()) }
            }
        }
    }

    /** Closes the Recent changes sheet and stops reading the log; the rows stay stored. */
    fun closeRecentChanges() {
        recentChangesJob?.cancel()
        recentChangesJob = null
        _uiState.update {
            it.copy(
                isRecentChangesOpen = false,
                recentChanges = persistentListOf(),
                recentChangesClearedIds = persistentListOf()
            )
        }
    }

    /** Hides one Recent changes row (a swipe). A log row isn't an event, so there is no undo. */
    fun dismissRecentChange(id: Long) {
        viewModelScope.launch { eventCoordinator.dismissRecentChanges(listOf(id)) }
    }

    /**
     * Hides every row the sheet shows and offers one undo for them. Only the shown rows: one a
     * sync adds meanwhile stays.
     */
    fun clearAllRecentChanges() {
        val ids = _uiState.value.recentChanges.map { it.id }
        if (ids.isEmpty()) return
        _uiState.update { it.copy(recentChangesClearedIds = ids.toPersistentList()) }
        viewModelScope.launch { eventCoordinator.dismissRecentChanges(ids) }
    }

    /** Brings back the rows the last Clear all hid, while its undo is on offer. */
    fun undoClearRecentChanges() {
        val ids = _uiState.value.recentChangesClearedIds
        if (ids.isEmpty()) return
        _uiState.update { it.copy(recentChangesClearedIds = persistentListOf()) }
        viewModelScope.launch { eventCoordinator.restoreRecentChanges(ids) }
    }

    /** Ends Clear all's undo offer once the sheet's Undo row has timed out. */
    fun onRecentChangesUndoShown() {
        _uiState.update { it.copy(recentChangesClearedIds = persistentListOf()) }
    }

    /**
     * Returns where a Recent changes row opens, or null for a deleted row or one whose event is
     * gone or waiting to be deleted. A series row opens at its next occurrence, since its stored
     * start is the first one, possibly months back; when that occurrence was changed on its own,
     * the changed occurrence opens instead, and with none left the series opens at its start.
     */
    suspend fun resolveRecentChangeTarget(item: RecentChangeItem): RecentChangeTarget? {
        val eventId = item.eventId?.takeIf { item.opens } ?: return null
        val event = getEventForEdit(eventId)?.takeUnless { it.isPendingDelete }
            ?: return null
        if (!item.isSeriesRow) return RecentChangeTarget(event, null)
        val next = withContext(ioDispatcher) { eventReader.getNextOccurrence(eventId, System.currentTimeMillis()) }
            ?: return RecentChangeTarget(event, null)
        val changed = next.exceptionEventId?.let { getEventForEdit(it) }
        return if (changed != null) RecentChangeTarget(changed, null) else RecentChangeTarget(event, next.startTs)
    }

    /**
     * Switches the calendar view, persists it as the startup view, clears the agenda and
     * month-grid keys when leaving those views, and sets the new view's key or loads its data.
     * A switch to Insights only sets the mode: nothing is persisted, cleared or loaded.
     *
     * @param persist write the new mode as the startup default. False for transient switches,
     *   e.g. drilling into a day from a column header.
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

        // Best effort: a DataStore setter throw must never crash Looper.main.
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

        // Null the key of a reactive view being left so its Flow stops querying; entering sets
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
     * Moves the month pager to the selected date's month on a switch to a month view, which
     * stops a flicker after the user browsed another month in the day or week grids.
     */
    private fun syncPagerToSelectedDate() {
        val state = _uiState.value
        // selectedDate is 0L until a day is picked (the Agenda view never sets it). With no
        // selection, stay on the viewing month; syncing to 0L would show December 1969.
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

    /** Shows a snackbar, with an optional action. Internal, not private, for tests. */
    internal fun showSnackbar(message: String, action: (() -> Unit)? = null) {
        _uiState.update {
            it.copy(
                pendingSnackbarMessage = message,
                pendingSnackbarAction = action
            )
        }
    }

    /** Clears the snackbar once the UI has consumed it. */
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
     * Sets an action for the UI to run; MainActivity.handleIncomingIntent calls it for a
     * notification, widget or shortcut tap.
     *
     * The event is held as state, not a Channel: the UI observes it in a LaunchedEffect and
     * clears it with [clearPendingAction] once handled.
     *
     * @see <a href="https://developer.android.com/topic/architecture/ui-layer/events">UI events</a>
     */
    fun setPendingAction(action: PendingAction) {
        Log.d(TAG, "setPendingAction: $action")
        _uiState.update { it.copy(pendingAction = action) }
    }

    /** Clears the pending action; the UI's LaunchedEffect calls it after handling the action. */
    fun clearPendingAction() {
        Log.d(TAG, "clearPendingAction")
        _uiState.update { it.copy(pendingAction = null) }
    }

    // ==================== Refresh ====================

    /**
     * Snaps to today when the calendar day has changed since the previous resume. Events need
     * no reload here: the range-keyed StateFlows and the day pager cache follow the data.
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
     * Rebuilds the one-shot state: the month dots, the day pager cache (outside MONTH_FULL, once
     * loaded) and, in the year view, the year dots. Called after, for example, an event write, a
     * device calendar visibility toggle or a successful sync.
     */
    private fun reloadCurrentView() {
        buildEventDots(_uiState.value.viewingYear, _uiState.value.viewingMonth)
        // The month grid (monthEvents), the agenda and the time grid follow the data themselves.
        if (_uiState.value.viewMode != ViewMode.MONTH_FULL && _uiState.value.cacheRangeCenter != 0L) {
            loadEventsForDayPagerRange(_uiState.value.cacheRangeCenter)
        }
        if (_uiState.value.viewMode == ViewMode.YEAR) {
            loadYearDots(_uiState.value.viewingYear)
        }
    }

    // ==================== Event CRUD Operations ====================

    /** Returns the Room event to edit, or null when it doesn't exist. */
    suspend fun getEventForEdit(eventId: Long): org.onekash.kashcal.data.db.entity.Event? {
        return withContext(ioDispatcher) {
            eventCoordinator.getEventById(eventId)
        }
    }

    /**
     * Returns an event's attendee rows once, for the form's picker to seed from on edit. Room
     * rows, not the lossy [AttendeeUiModel] projection, so the picker keeps the role, cutype,
     * RSVP and delegation fields the next push would otherwise strip.
     */
    suspend fun getAttendeesForEdit(eventId: Long): List<org.onekash.kashcal.data.db.entity.Attendee> {
        return withContext(ioDispatcher) {
            eventReader.getAttendeesForEvent(eventId).first()
        }
    }

    /** Looks up contact emails for the attendee picker's type-ahead, which debounces the calls. */
    suspend fun queryContactEmails(prefix: String): List<org.onekash.kashcal.data.contacts.ContactEmail> =
        contactEmailReader.query(prefix)

    /**
     * Returns the account of a calendar and whether it can send invitations. Schedulable means
     * the account has an email-shaped address, so an ORGANIZER can be resolved; the picker gates
     * editing on it so it doesn't create an event with attendees and no ORGANIZER. A null
     * [calendarId] is schedulable, with no account.
     */
    suspend fun getFormAttendeeContext(calendarId: Long?): FormAttendeeContext {
        if (calendarId == null) return FormAttendeeContext(account = null, isSchedulable = true)
        return withContext(ioDispatcher) {
            val calendar = uiState.value.calendars.firstOrNull { it.id == calendarId }
            val account = calendar?.accountId?.let { accountRepository.getAccountById(it) }
            // No account resolves when no loaded Room calendar has this id (a device calendar,
            // for example) or its account row is missing; that counts as schedulable so the
            // picker stays usable.
            val schedulable = account == null ||
                account.effectiveAddresses().any {
                    org.onekash.kashcal.util.AddressNormalizer.isEmailShaped(it)
                }
            FormAttendeeContext(account = account, isSchedulable = schedulable)
        }
    }

    /**
     * Returns the day code (YYYYMMDD) of a device event's start, or null when the event is
     * missing or can't be read.
     *
     * For an external VIEW intent with no occurrence timestamp: no exact occurrence can open, so
     * the calendar goes to the event's start date, not silently to today. All-day events
     * resolve to the right local day in negative UTC offsets.
     */
    suspend fun getDeviceEventDayCode(eventId: Long): Int? {
        return withContext(ioDispatcher) {
            deviceEventReader.getEventStartDayCode(eventId)
        }
    }

    /**
     * Returns a device event's guests for the quick-view and form chips. Reads the `Attendees`
     * rows on demand, not through the grid's bulk query, takes the calendar's `OWNER_ACCOUNT` as
     * the "you" identity, and maps them with [deviceAttendeeUiState].
     *
     * The state is empty (no chips, not on the list) when the event has no attendee rows or the
     * read fails, so the quick view shows no guest section at all.
     *
     * @param eventId the CalendarProvider event id, master or exception
     * @param calendarId the event's calendar, for the owner email; null marks no one "you"
     */
    suspend fun getDeviceEventAttendeeState(eventId: Long, calendarId: Long?): EventAttendeeUiState {
        return withContext(ioDispatcher) {
            val rows = deviceEventReader.getAttendeesWithOwner(eventId, calendarId)
            deviceAttendeeUiState(rows.attendees, rows.ownerEmail)
        }
    }

    /**
     * Writes the user's RSVP on a device event through [DeviceEventWriter.replyRsvp]. Reloads
     * the view when a row was written and shows a write error when the write fails. Nothing
     * written (no owner address, or the user isn't on the list) is still a success.
     *
     * @param calendarId the event's calendar, whose owner email is "you"
     */
    suspend fun replyDeviceRsvp(eventId: Long, calendarId: Long, status: AttendeeStatus): Result<Unit> {
        return withContext(ioDispatcher) {
            deviceEventWriter.replyRsvp(eventId, calendarId, status.toDeviceStatus())
                .onSuccess { written -> if (written) reloadCurrentView() }
                .onFailure { e ->
                    Log.e(TAG, "Failed to update device RSVP", e)
                    showError(CalendarError.DeviceCalendar.WriteFailed(e.message ?: "Unknown error"))
                }
                .map { }
        }
    }

    /**
     * Returns a device event for the quick view from its CalendarProvider ID alone, or null when
     * no occurrence resolves. For an external VIEW intent that carries only the ID, and to
     * re-read an open one-off event.
     *
     * The occurrence shown ([DeviceEventReader.resolveQuickViewOccurrenceStart]):
     * - A series: its first instance from a day before now, so today's is kept, read from the
     *   Instances view so RRULE, RDATE and EXDATE apply. The master's DTSTART is the first,
     *   possibly long past, occurrence and must not be used. An ended series gives null.
     * - Anything else: its own DTSTART, past or future.
     */
    suspend fun getDeviceEventForQuickViewById(eventId: Long): DisplayEvent.Device? {
        return withContext(ioDispatcher) {
            val occurrenceTs = deviceEventReader.resolveQuickViewOccurrenceStart(
                eventId,
                nowMs = System.currentTimeMillis(),
            ) ?: return@withContext null
            getDeviceEventForQuickView(eventId, occurrenceTs)
        }
    }

    /**
     * Returns the device event occurrence of [eventId] starting at [occurrenceTs], for the quick
     * view (a ShowDeviceEventQuickView action, or a re-read of an open occurrence), or null when
     * none matches or the read fails. Reads the instances on that day.
     */
    suspend fun getDeviceEventForQuickView(eventId: Long, occurrenceTs: Long): DisplayEvent.Device? {
        return withContext(ioDispatcher) {
            try {
                // All-day events use UTC midnight timestamps, which in negative UTC offsets map
                // to the previous local day when read as timed. Query both possible days in one
                // call.
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
     * Saves a Room event from the form: creates it, or edits it at the chosen scope.
     *
     * @param scope the scope picked in the scope sheet; null for a save that didn't ask. With no
     *   scope, an occurrence timestamp in the form makes it a single-occurrence edit.
     * @return the created or updated event (the new series for this and future), or a failure:
     *   a missing event or master, a date change on a later occurrence for all events, or any
     *   exception thrown, cancellation included
     */
    suspend fun saveEvent(
        formState: EventFormState,
        scope: EditScope? = null,
    ): Result<org.onekash.kashcal.data.db.entity.Event> {
        return withContext(ioDispatcher) {
            try {
                // toStartEndTs is shared with the Save-and-notify change detection, so both use
                // the math that is persisted.
                val (startTs, endTs) = formState.toStartEndTs()

                val reminders = buildRemindersList(formState.reminders)

                // The local calendar when none is selected.
                val calendarId = formState.selectedCalendarId
                    ?: eventCoordinator.getLocalCalendarId()

                // Attendees the user edited. null means the form isn't managing them, so stored or
                // pulled rows stay as they are; a list is the set to persist. The picker hands
                // back Room rows (it seeds from and edits the real rows), so nothing is lost in
                // conversion, and an open-and-save without edits passes null even when the event
                // has attendees, keeping their wire fields.
                val attendeesArg = formState.attendees.takeIf { formState.attendeesEdited }

                // THIS_AND_FUTURE splits the series here. THIS_EVENT and ALL_EVENTS take the
                // exception and update branches below; with no scope, an editingOccurrenceTs
                // makes it a single-occurrence edit.
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
                                // A null rrule is "Does not repeat": passed through, the new
                                // series row becomes non-recurring.
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

                // ALL_EVENTS is a master update even when the form was opened on an occurrence.
                val effectiveOccurrenceTs =
                    if (scope == EditScope.ALL_EVENTS) null else formState.editingOccurrenceTs

                val savedEvent = if (effectiveOccurrenceTs != null && formState.editingEventId != null) {
                    // One occurrence: create or update its exception. An exception's ID is
                    // resolved to its master's.
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
                                rrule = null, // An exception has no RRULE
                                reminders = reminders,
                                calendarId = calendarId,
                                transp = formState.transp,
                                color = formState.eventColor,
                                categories = formState.categories.ifEmpty { null },
                                // Kept from the master so the exception round-trips:
                                timezone = masterEvent.timezone,
                                status = masterEvent.status,
                                classification = masterEvent.classification,
                                extraProperties = masterEvent.extraProperties,
                                updatedAt = System.currentTimeMillis()
                            )
                        }
                    )
                } else if (formState.isEditMode && formState.editingEventId != null) {
                    // The whole event, or every occurrence of a series.
                    val loadedEvent = eventCoordinator.getEventById(formState.editingEventId)
                        ?: return@withContext Result.failure(IllegalStateException("Event not found"))

                    // ALL_EVENTS must rewrite the master row. A form opened on a detached
                    // exception climbs to its master, as the branches above do; otherwise an
                    // rrule change would land on the exception row, not the series.
                    val existingEvent =
                        if (scope == EditScope.ALL_EVENTS && loadedEvent.originalEventId != null) {
                            eventCoordinator.getEventById(loadedEvent.originalEventId)
                                ?: return@withContext Result.failure(IllegalStateException("Master event not found"))
                        } else {
                            loadedEvent
                        }
                    val targetEventId = existingEvent.id

                    // "All events" from a form opened on a later occurrence: the
                    // form holds that occurrence's date, but the series starts
                    // earlier. Keep the series' own first date so no occurrence
                    // before this one is cut; only a changed clock time moves it.
                    // A changed date isn't offered for a later occurrence (the
                    // scope sheet withholds All events), so it is refused here too.
                    val laterOccurrence = formState.editingOccurrenceTs?.takeIf { openedOn ->
                        scope == EditScope.ALL_EVENTS && loadedEvent.originalEventId == null &&
                            !existingEvent.rrule.isNullOrEmpty() && openedOn > existingEvent.startTs
                    }
                    if (laterOccurrence != null && formState.occurrenceDateChanged(laterOccurrence, existingEvent.isAllDay)) {
                        return@withContext Result.failure(
                            IllegalStateException("Date change on a later occurrence can't apply to all events")
                        )
                    }
                    val (seriesStartTs, seriesEndTs) = laterOccurrence?.let {
                        formState.startEndAnchoredToSeries(it, existingEvent.startTs, existingEvent.isAllDay)
                    } ?: (startTs to endTs)

                    val calendarChanged = existingEvent.calendarId != calendarId

                    // EventWriter bumps SEQUENCE; the ViewModel doesn't.

                    if (calendarChanged) {
                        // The coordinator queues the server side of the move (a MOVE, or a
                        // CREATE and DELETE, depending on the accounts).
                        eventCoordinator.moveEventToCalendar(targetEventId, calendarId)

                        // Then the other field changes apply to the moved event.
                        val movedEvent = eventCoordinator.getEventById(targetEventId)
                            ?: return@withContext Result.failure(IllegalStateException("Event not found after move"))

                        val finalEvent = movedEvent.copy(
                            title = formState.title.ifBlank { "Untitled" },
                            startTs = seriesStartTs,
                            endTs = seriesEndTs,
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
                        val updatedEvent = existingEvent.copy(
                            title = formState.title.ifBlank { "Untitled" },
                            startTs = seriesStartTs,
                            endTs = seriesEndTs,
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
                    val now = System.currentTimeMillis()
                    val newEvent = org.onekash.kashcal.data.db.entity.Event(
                        // Blank: EventWriter mints the @kashcal.onekash.org UID, so the form
                        // isn't a second minting authority.
                        uid = "",
                        calendarId = calendarId,
                        title = formState.title.ifBlank { "Untitled" },
                        startTs = startTs,
                        endTs = endTs,
                        // All-day: no timezone (stored as UTC midnight). Timed: the selected
                        // timezone, or the device's.
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
            // A same-day drop blocks nothing and is known at once; a cross-day
            // drop is checked off the main thread against the stored series,
            // and the sheet greys the series scopes until the check lands.
            val crossDay = isCrossDayMove(displayEvent, droppedStartTs(displayEvent, targetDate, targetStartMinutes))
            val pending = PendingDragReschedule(
                displayEvent, targetDate, targetStartMinutes,
                blockedScopes = if (crossDay) null else emptySet(),
            )
            dragAvailabilityJob?.cancel()
            _uiState.update { it.copy(pendingDragReschedule = pending) }
            if (crossDay) resolveDragAvailability(pending)
            return
        }

        performReschedule(displayEvent, targetDate, targetStartMinutes, editScope)
    }

    fun confirmReschedule(editScope: EditScope) {
        val pending = _uiState.value.pendingDragReschedule ?: return
        dragAvailabilityJob?.cancel()
        _uiState.update { it.copy(pendingDragReschedule = null) }
        performReschedule(pending.displayEvent, pending.targetDate, pending.targetStartMinutes, editScope)
    }

    fun cancelPendingReschedule() {
        dragAvailabilityJob?.cancel()
        _uiState.update { it.copy(pendingDragReschedule = null) }
    }

    /** Returns a drop's new start: [targetDate] at the clamped minute, in the phone's zone. */
    private fun droppedStartTs(displayEvent: DisplayEvent, targetDate: LocalDate, targetStartMinutes: Int): Long {
        val durationMinutes = ((displayEvent.endTs - displayEvent.startTs) / 60000).toInt()
        val clampedStart = WeekViewUtils.clampDragStartMinutes(targetStartMinutes, durationMinutes)
        return WeekViewUtils.calculateNewTimestamps(targetDate, clampedStart, durationMinutes).first
    }

    /** Returns true when [droppedStartTs] is on another day than the occurrence, in its zone. */
    private fun isCrossDayMove(displayEvent: DisplayEvent, droppedStartTs: Long): Boolean {
        val (zone, occurrenceStartTs) = when (displayEvent) {
            is DisplayEvent.Room ->
                RruleShift.zoneFor(displayEvent.event.timezone, displayEvent.event.isAllDay) to displayEvent.occurrence.startTs
            is DisplayEvent.Device ->
                RruleShift.zoneFor(displayEvent.instance.timezone, displayEvent.instance.isAllDay) to displayEvent.instance.startTs
        }
        return Instant.ofEpochMilli(occurrenceStartTs).atZone(zone).toLocalDate() !=
            Instant.ofEpochMilli(droppedStartTs).atZone(zone).toLocalDate()
    }

    private fun resolveDragAvailability(pending: PendingDragReschedule) {
        val dropped = pending.displayEvent
        dragAvailabilityJob = viewModelScope.launch {
            val blocked = withContext(ioDispatcher) {
                try {
                    val droppedStartTs = droppedStartTs(dropped, pending.targetDate, pending.targetStartMinutes)
                    when (dropped) {
                        is DisplayEvent.Room -> blockedDragScopes(dropped, droppedStartTs)
                        is DisplayEvent.Device -> blockedDeviceDragScopes(dropped, droppedStartTs)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Couldn't check the drop's scopes", e)
                    setOf(EditScope.ALL_EVENTS, EditScope.THIS_AND_FUTURE)
                }
            }
            _uiState.update { state ->
                val current = state.pendingDragReschedule
                if (current != null && current.isSameDropAs(pending)) {
                    state.copy(pendingDragReschedule = current.copy(blockedScopes = blocked))
                } else {
                    state
                }
            }
        }
    }

    /**
     * The series scopes a cross-day drop of [room] can't use, read from the
     * stored series (not the snapshot the timeline showed).
     */
    private suspend fun blockedDragScopes(room: DisplayEvent.Room, droppedStartTs: Long): Set<EditScope> {
        val master = eventReader.getEventById(room.event.originalEventId ?: room.event.id)
            ?: return setOf(EditScope.ALL_EVENTS, EditScope.THIS_AND_FUTURE)
        val availability = RruleShift.dragAvailability(
            master = master,
            exceptions = eventReader.getExceptionsForMaster(master.id),
            occurrenceStartMs = room.occurrence.startTs,
            droppedStartMs = droppedStartTs,
        )
        return buildSet {
            if (!availability.allEvents) add(EditScope.ALL_EVENTS)
            if (!availability.thisAndFuture) add(EditScope.THIS_AND_FUTURE)
        }
    }

    /**
     * The series scopes a cross-day drop of [device] can't use. All events is
     * never offered for a device series; this and future needs a repeat rule
     * that can follow the move.
     */
    private suspend fun blockedDeviceDragScopes(device: DisplayEvent.Device, droppedStartTs: Long): Set<EditScope> =
        if (shiftedDeviceRule(device, droppedStartTs) == null) {
            setOf(EditScope.ALL_EVENTS, EditScope.THIS_AND_FUTURE)
        } else {
            setOf(EditScope.ALL_EVENTS)
        }

    /**
     * The repeat rule for the part of [device]'s series from the dragged
     * occurrence on, moved to [droppedStartTs] (a Tuesday series dropped on a
     * Wednesday repeats on Wednesdays), or null when the rule can't follow the
     * move or the series can't be read. Read from the stored series, not the
     * snapshot the timeline showed.
     */
    private suspend fun shiftedDeviceRule(device: DisplayEvent.Device, droppedStartTs: Long): String? {
        val series = deviceEventReader.getDeviceEvent(device.instance.eventId) ?: return null
        val rule = series.rrule?.takeIf { it.isNotEmpty() } ?: return null
        return RruleShift.shift(rule, device.instance.startTs, droppedStartTs, series.timezone, series.isAllDay)
    }

    /**
     * Stages a form save until the user picks a scope in the scope sheet.
     *
     * The event form decides when to defer: an edit opened on one occurrence of an event that
     * was recurring when loaded, outside the read-only view. Other saves go straight to
     * [saveEvent] or [saveDeviceEvent], and the read-only view saves only its reminders
     * ([saveAttendeeReminders]).
     *
     * The form passes `masterStartTs`, `isDetachedException` and `loadedIsAllDay` from the
     * event it loaded, so the scope rules don't derive them from the user-edited form state.
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
                    occurrenceDateChanged = formState.occurrenceDateChanged(occurrenceTs, loadedIsAllDay),
                )
            )
        }
    }

    fun cancelPendingFormSave() {
        _uiState.update { it.copy(pendingFormSave = null) }
    }

    /**
     * Ticks the failure counter, so the form's LaunchedEffect resets its `isSaving` flag.
     * Called after a deferred save fails and after a cancel from the scope sheet: both leave the
     * form open with the user's edits, and Save must be enabled again for a retry.
     */
    fun signalFormSaveFailed() {
        _uiState.update { it.copy(formSaveFailedTick = it.formSaveFailedTick + 1) }
    }

    /**
     * Stages a delete of a Room series occurrence until the user picks a scope;
     * [requestDeleteDevice] is the device twin. Each captures its source's fields (the event row
     * here, the master and calendar ids there) and the scope rules' context (masterStartTs,
     * isDetachedException).
     *
     * Deletes of a non-recurring event or an exception don't stage: callers delete them
     * directly, for example through [deleteEventOptimistic], [deleteSingleOccurrence] or
     * [deleteDeviceEvent].
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
     * Applies the staged delete with the user's scope, through the Room or the device path by
     * its type. The delete methods report their own failures.
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
                    } catch (e: CancellationException) {
                        throw e
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

    /**
     * Moves a Room series (all of it, or from the dragged occurrence on) to
     * another day, rewriting its repeat rule so every moved occurrence lands on
     * its new day at the dropped time in the event's zone. Checked again against
     * the stored series first; a move the rule can't express, or one that would
     * strand deleted, added or edited occurrences, throws before any write.
     */
    private suspend fun moveSeriesToAnotherDay(room: DisplayEvent.Room, newStartTs: Long, scope: EditScope) {
        val masterId = room.event.originalEventId ?: room.event.id
        val master = eventReader.getEventById(masterId) ?: throw IllegalStateException("Series not found")
        val occurrenceTs = room.occurrence.startTs
        if (scope in blockedDragScopes(room, newStartTs)) {
            throw IllegalStateException("This move can't be applied to $scope")
        }
        val now = System.currentTimeMillis()
        if (scope == EditScope.ALL_EVENTS) {
            val start = RruleShift.movedStart(master, occurrenceTs, newStartTs)
            val rule = master.rrule?.let { RruleShift.shift(it, master.startTs, start, master.timezone, master.isAllDay) }
                ?: throw IllegalStateException("The repeat rule can't follow this move")
            eventCoordinator.updateEvent(
                master.copy(startTs = start, endTs = start + (master.endTs - master.startTs), rrule = rule, updatedAt = now)
            )
        } else {
            val draggedDuration = room.endTs - room.startTs
            eventCoordinator.editThisAndFuture(
                masterEventId = masterId,
                splitTimeMs = occurrenceTs,
                changes = { fresh ->
                    val rule = fresh.rrule?.let { RruleShift.shift(it, occurrenceTs, newStartTs, fresh.timezone, fresh.isAllDay) }
                        ?: throw IllegalStateException("The repeat rule can't follow this move")
                    // The check refused any deleted or added date from the split on, so the ones
                    // left are all before it. Carried over they could hit a slot of the new series
                    // (a move to an earlier day), so the new series starts without them.
                    fresh.copy(
                        startTs = newStartTs, endTs = newStartTs + draggedDuration, rrule = rule,
                        exdate = null, rdate = null, updatedAt = now,
                    )
                },
            )
        }
    }

    private fun performReschedule(
        displayEvent: DisplayEvent,
        targetDate: LocalDate,
        targetStartMinutes: Int,
        editScope: EditScope
    ) {
        // The scope sheet never offers "All events" for a recurring device event:
        // CalendarProvider can't split the series, so the only way to honour it is
        // to move the master's DTSTART, which can leave it out of step with a BYDAY
        // rule (RFC 5545 §3.8.5.3 leaves such a recurrence set undefined). Refuse it
        // here too so a caller can't request it directly.
        if (displayEvent is DisplayEvent.Device &&
            displayEvent.instance.hasRrule &&
            editScope == EditScope.ALL_EVENTS
        ) {
            Log.w(TAG, "Refusing all-events drag of recurring device event ${displayEvent.instance.eventId}")
            return
        }

        viewModelScope.launch {
            try {
                val durationMs = displayEvent.endTs - displayEvent.startTs
                val durationMinutes = (durationMs / 60000).toInt()
                val clampedStart = WeekViewUtils.clampDragStartMinutes(targetStartMinutes, durationMinutes)
                val (newStartTs, newEndTs) = WeekViewUtils.calculateNewTimestamps(
                    targetDate, clampedStart, durationMinutes
                )

                // Device writes report failure through Result rather than throwing,
                // so the device branch hands its Result back; Room writes throw and
                // are handled by the catch below.
                val deviceWriteResult: Result<*>? = withContext(ioDispatcher) {
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
                                // Another day, whole series or its future: move the repeat
                                // rule with it (RFC 5545 section 3.8.5.3), or refuse.
                                isCrossDayMove(displayEvent, newStartTs) ->
                                    moveSeriesToAnotherDay(displayEvent, newStartTs, editScope)
                                editScope == EditScope.THIS_AND_FUTURE -> {
                                    val masterEventId = event.originalEventId ?: event.id
                                    eventCoordinator.editThisAndFuture(
                                        masterEventId = masterEventId,
                                        splitTimeMs = displayEvent.occurrence.startTs,
                                        changes = { master ->
                                            // endTs follows the dragged occurrence's own
                                            // duration. master.endTs + delta would sit
                                            // days before the new startTs, and RFC 5545
                                            // §3.8.2.2 says DTEND MUST be later than
                                            // DTSTART.
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
                            null
                        }
                        is DisplayEvent.Device -> {
                            val instance = displayEvent.instance
                            // Provider writes set AVAILABILITY and EVENT_COLOR on every call,
                            // and a new exception row otherwise gets the defaults, so carry the
                            // instance's current values or the drag resets the event to busy and
                            // strips its per-event colour. Guests and tags are not passed: an
                            // existing row keeps its own, and a new exception or the future half
                            // of a split copies the series'.
                            // Reminders are null so the provider keeps them as stored, types
                            // included: the instance only carries their minutes, and none at all
                            // when the range load failed to read them.
                            val draft = DeviceEventDraft(
                                calendarId = instance.calendarId,
                                title = instance.title,
                                description = instance.description,
                                location = instance.location,
                                startTs = newStartTs,
                                endTs = newEndTs,
                                isAllDay = instance.isAllDay,
                                rrule = instance.rrule,
                                timezone = instance.timezone ?: TimezoneUtils.getDeviceTimezone(),
                                reminders = null,
                                availability = instance.availability,
                                eventColor = instance.eventColor,
                            )
                            when {
                                instance.hasRrule && editScope == EditScope.THIS_EVENT ->
                                    deviceEventWriter.editSingleOccurrence(
                                        masterEventId = instance.eventId,
                                        originalInstanceTime = instance.startTs,
                                        draft = draft,
                                    )
                                instance.hasRrule && editScope == EditScope.THIS_AND_FUTURE -> {
                                    // Another day: the rule moves with the occurrence (RFC 5545
                                    // section 3.8.5.3), or nothing is written. Checked again here,
                                    // since the series can change between the drop and the pick.
                                    val rule = if (isCrossDayMove(displayEvent, newStartTs)) {
                                        shiftedDeviceRule(displayEvent, newStartTs)
                                    } else {
                                        instance.rrule
                                    }
                                    if (rule == null) {
                                        Result.failure(IllegalStateException("The repeat rule can't follow this move"))
                                    } else {
                                        deviceEventWriter.editThisAndFuture(
                                            masterEventId = instance.eventId,
                                            fromTimeMs = instance.startTs,
                                            draft = draft.copy(rrule = rule),
                                        )
                                    }
                                }
                                // Non-recurring event or a modified occurrence: a single row.
                                else -> deviceEventWriter.updateEvent(instance.eventId, draft)
                            }
                        }
                    }
                }

                deviceWriteResult?.exceptionOrNull()?.let { e ->
                    Log.e(TAG, "Failed to reschedule device event", e)
                    showSnackbar(context.getString(R.string.snackbar_reschedule_failed))
                    return@launch
                }

                reloadCurrentView()
                showSnackbar(context.getString(R.string.snackbar_event_rescheduled))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error rescheduling event", e)
                showSnackbar(context.getString(R.string.snackbar_reschedule_failed))
            }
        }
    }

    /**
     * Deletes a Room event and reloads the view; a failure, an exception row included, comes
     * back as the Result.
     */
    suspend fun deleteEvent(eventId: Long): Result<Unit> {
        return withContext(ioDispatcher) {
            try {
                eventCoordinator.deleteEvent(eventId)
                Log.d(TAG, "Event deleted: $eventId")

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
     * Routes the event form's Delete for a Room event by the loaded event's shape, as the quick
     * view's Delete does:
     * - Exception: deletes that occurrence on its master
     *   ([EventCoordinator.deleteSingleOccurrence]); [EventCoordinator.deleteEvent] refuses an
     *   exception.
     * - Recurring master: stages [requestDeleteRoom] and returns success. The form closes and
     *   the scope sheet opens over it through uiState.pendingDelete.
     * - Anything else: deletes the event.
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
                        // The form's occurrenceTs when it was opened on an occurrence, else the
                        // master's start. Always passing the master's start would make the
                        // scope sheet disable THIS_AND_FUTURE and send THIS_EVENT to the first
                        // occurrence.
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
     * Writes the user's RSVP for a Room event they're attending ([EventCoordinator.replyRsvp]).
     *
     * The coordinator updates the local attendee row's PARTSTAT, so the chip row's Flow shows the
     * new status before the network round trip; the CalDAV PUT is queued as a PendingOperation
     * for PushStrategy. A status with no PARTSTAT is a no-op. A false reply (no attendee row
     * matches, or the event, calendar or account is missing) is only logged; an exception shows
     * a snackbar.
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
     * Saves the user's reminders on an event they're an attendee of, for the read-only attendee
     * form ([EventCoordinator.saveAttendeeReminders]). Local only, no server PUT. A failure comes
     * back as the Result, which the form shows in its `state.error`.
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
     * Deletes a Room event without waiting, so the quick view can close at once; also the
     * ALL_EVENTS branch of [confirmDelete]. A failure shows a snackbar.
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
     * Deletes one occurrence of a Room series without waiting, by adding an EXDATE to the
     * master. A failure shows a snackbar.
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
     * Deletes the occurrences of a Room series from [fromTimeMs] on without waiting, by ending
     * the series with an UNTIL. A failure shows a snackbar.
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
     * Deletes a device event row, a one-off or a whole series. Shows a write error on failure
     * and reloads the view on success, as the other device deletes below do.
     */
    suspend fun deleteDeviceEvent(eventId: Long): Result<Unit> {
        return withContext(ioDispatcher) {
            deviceEventWriter.deleteEvent(eventId).also { result ->
                result.onFailure { e ->
                    Log.e(TAG, "Failed to delete device event: $eventId", e)
                    showError(CalendarError.DeviceCalendar.WriteFailed(e.message ?: "Unknown error"))
                }
                result.onSuccess {
                    Log.d(TAG, "Device event deleted: id=$eventId")
                    reloadCurrentView()
                }
            }
        }
    }

    /**
     * Deletes one occurrence of a device series: a cancelled exception row, or the existing
     * exception row cancelled.
     */
    suspend fun deleteDeviceSingleOccurrence(
        masterEventId: Long,
        originalInstanceTime: Long,
        isAllDay: Boolean = false
    ): Result<Unit> {
        return withContext(ioDispatcher) {
            deviceEventWriter.deleteSingleOccurrence(
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
                    reloadCurrentView()
                }
            }
        }
    }

    /**
     * Routes the event form's Delete for a device event by the stored event's shape, as
     * [handleRoomEventFormDelete] does for Room: an exception deletes its occurrence, a series
     * stages [requestDeleteDevice] at the form's occurrence (or the series start), and anything
     * else is deleted.
     */
    suspend fun handleDeviceEventFormDelete(formState: EventFormState): Result<Unit> {
        val deviceEventId = formState.editingDeviceEventId
            ?: return Result.failure(IllegalStateException("No device event to delete"))
        return withContext(ioDispatcher) {
            try {
                val event = deviceEventReader.getDeviceEvent(deviceEventId)
                    ?: return@withContext Result.failure(
                        IllegalStateException("Device event not found: $deviceEventId")
                    )
                when {
                    // An exception deletes only its occurrence. The master delete would wipe
                    // the whole series.
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
     * Deletes the occurrences of a device series from [fromTimeMs] on by ending its RRULE with
     * an UNTIL; at or before the series start, the whole event.
     */
    suspend fun deleteDeviceThisAndFuture(
        masterEventId: Long,
        fromTimeMs: Long,
        isAllDay: Boolean = false
    ): Result<Unit> {
        return withContext(ioDispatcher) {
            deviceEventWriter.deleteThisAndFuture(
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
                    reloadCurrentView()
                }
            }
        }
    }

    // ==================== Device Calendar Edit Support ====================

    /**
     * Returns a device event for the edit form, with its reminders, guests and calendar, or null
     * when the event or its calendar is missing. When [occurrenceTs] names an occurrence that
     * has an exception row, that row, with its own reminders and guests, is loaded instead of
     * the master.
     *
     * @param eventId the event, the master for a series
     * @param occurrenceTs the occurrence's original start; null for an edit not opened on one
     * @param isAllDay whether the event is all-day, for the UTC-midnight occurrence match
     */
    suspend fun getDeviceEventForEdit(eventId: Long, occurrenceTs: Long? = null, isAllDay: Boolean = false): DeviceEventEditData? {
        return withContext(ioDispatcher) {
            val data = deviceEventReader.getEventForEdit(eventId, occurrenceTs, isAllDay)
                ?: return@withContext null
            val calendar = data.calendar
            DeviceEventEditData(
                event = data.event,
                reminders = data.reminders,
                calendarName = calendar.displayName,
                calendarColor = calendar.color,
                isWritable = calendar.isWritable,
                attendees = AttendeeUiModel.fromDevice(
                    data.attendees,
                    ownerEmail = calendar.ownerAccount.takeUnless { it.isBlank() },
                ),
            )
        }
    }

    /** Imports events parsed from an ICS file into a device calendar; returns the count created. */
    suspend fun importIcsToDeviceCalendar(events: List<Event>, calendarId: Long): Int {
        val count = withContext(ioDispatcher) {
            deviceEventWriter.importIcsEvents(events, calendarId)
        }
        // The writer has signalled the device change; the view is reloaded too, as after every
        // other device write, since the caller only refreshes calendars and selects a date.
        if (count > 0) reloadCurrentView()
        return count
    }

    /**
     * Saves a device event from the form, picking the writer method for the edit scope the way
     * [saveEvent] picks EventCoordinator's:
     * - "This and future" on an occurrence: split the series there
     * - One occurrence: create or update its exception row
     * - A non-recurring event in another calendar: move it
     * - An existing event: update it in place (a whole series for "All events")
     * - Otherwise: create a new event
     *
     * Records the saved tags and reloads the view on success; shows a write error when a writer
     * call or the all-events series check fails. No selected calendar only returns a failure.
     *
     * @return the saved event's ID
     */
    suspend fun saveDeviceEvent(
        formState: org.onekash.kashcal.ui.components.EventFormState,
        scope: EditScope? = null,
    ): Result<Long> {
        return withContext(ioDispatcher) {
            val calendarId = formState.selectedCalendarId
                ?: return@withContext Result.failure(IllegalStateException("No calendar selected"))

            val (startTs, endTs) = formState.toStartEndTs()

            // The user's zone, else the unresolvable zone the event arrived with, else the
            // device zone.
            val timezone = formState.timezone ?: formState.sourceTimezoneId ?: TimezoneUtils.getDeviceTimezone()

            // Guests the user edited, as provider rows; null when the form isn't managing
            // attendees (open-and-save, or a read-only path), so stored rows are left alone.
            // The writer applies them only on create, whole-event update and move. A new
            // occurrence exception or the future half of a split copies the series' guests and
            // organizer instead, and its tags unless the user edited them.
            val deviceAttendeesArg =
                if (formState.attendeesEdited) pickerAttendeesToDevice(formState.attendees) else null

            // Tags the user edited, or null when the tag row wasn't touched, so the stored row
            // keeps its tags, as with deviceAttendeesArg.
            val deviceCategoriesArg =
                if (formState.categoriesEdited) formState.categories else null

            var draft = DeviceEventDraft(
                calendarId = calendarId,
                title = formState.title,
                description = formState.description.ifBlank { null },
                location = formState.location.ifBlank { null },
                startTs = startTs,
                endTs = endTs,
                isAllDay = formState.isAllDay,
                rrule = formState.rrule,
                timezone = timezone,
                reminders = buildDeviceReminders(formState.reminders),
                availability = transpToAvailability(formState.transp),
                eventColor = formState.eventColor,
                attendees = deviceAttendeesArg,
                categories = deviceCategoriesArg,
            )

            val editingEventId = formState.editingDeviceEventId
            // ALL_EVENTS is a master update even when the form was opened on an occurrence.
            val effectiveOccurrenceTs =
                if (scope == EditScope.ALL_EVENTS) null else formState.editingOccurrenceTs

            when {
                // The form was opened on an occurrence, so editingOccurrenceTs
                // carries the split point.
                scope == EditScope.THIS_AND_FUTURE &&
                    editingEventId != null &&
                    formState.editingOccurrenceTs != null ->
                    deviceEventWriter.editThisAndFuture(editingEventId, formState.editingOccurrenceTs, draft)

                editingEventId != null && effectiveOccurrenceTs != null ->
                    deviceEventWriter.editSingleOccurrence(editingEventId, effectiveOccurrenceTs, draft)

                editingEventId != null -> {
                    val existing = deviceEventReader.getDeviceEvent(editingEventId)
                    // "All events" from a form opened on an occurrence: the form
                    // holds that occurrence's date, but the series starts earlier.
                    // Keep the series' own first date so no occurrence before
                    // this one is cut; only a changed clock time moves it. A
                    // changed date isn't offered for a later occurrence (the scope
                    // sheet withholds All events), so it is refused here too.
                    val openedOn = formState.editingOccurrenceTs
                    if (scope == EditScope.ALL_EVENTS && openedOn != null) {
                        if (existing == null) {
                            return@withContext Result.failure<Long>(IllegalStateException("Series not found"))
                                .also { showError(CalendarError.DeviceCalendar.WriteFailed("Series not found")) }
                        }
                        if (!existing.rrule.isNullOrEmpty() && openedOn > existing.startTs) {
                            if (formState.occurrenceDateChanged(openedOn, existing.isAllDay)) {
                                return@withContext Result.failure<Long>(
                                    IllegalStateException("Date change on a later occurrence can't apply to all events")
                                ).also { showError(CalendarError.DeviceCalendar.WriteFailed("Date change on a later occurrence")) }
                            }
                            val (seriesStart, seriesEnd) =
                                formState.startEndAnchoredToSeries(openedOn, existing.startTs, existing.isAllDay)
                            draft = draft.copy(startTs = seriesStart, endTs = seriesEnd)
                        }
                    }
                    // A calendar change is a move, gated on the stored event being
                    // non-recurring, not on the form's rrule: recurring device moves (with their
                    // exceptions) aren't supported, but a save that adds recurrence while
                    // changing calendar must still move, or the in-place update would silently
                    // drop the calendar change. The form disables the calendar picker while a
                    // device event being edited has a repeat rule.
                    if (existing != null && existing.calendarId != calendarId && existing.rrule == null) {
                        deviceEventWriter.moveEventToCalendar(existing, draft)
                    } else {
                        deviceEventWriter.updateEvent(editingEventId, draft)
                    }
                }

                // A new event always stores the form's tags.
                else -> deviceEventWriter.createEvent(draft.copy(categories = formState.categories))
            }.onSuccess { saved ->
                // The stored tags go into the shared registry, so new names get a suggestion
                // entry and become colorable.
                if (saved.savedTags.isNotEmpty()) eventCoordinator.recordTagUsage(saved.savedTags)
                reloadCurrentView()
            }.onFailure { e ->
                Log.e(TAG, "Failed to save device event", e)
                showError(CalendarError.DeviceCalendar.WriteFailed(e.message ?: "Unknown error"))
            }.map { it.eventId }
        }
    }

    private fun transpToAvailability(transp: String): Int =
        if (transp == "TRANSPARENT") 1 else 0

    /**
     * Returns the form's reminder minutes for the provider, deduplicated and sorted but
     * otherwise unchanged.
     *
     * The form's signed "minutes before start" already matches CalendarContract.Reminders.MINUTES
     * (positive before the start, negative after), so an all-day "9 AM day of" (-540) is
     * stored as MINUTES = -540, with no clamping. Minutes, not the ISO durations Room stores.
     */
    private fun buildDeviceReminders(reminderMinutes: List<Int>): List<Int> {
        return deduplicateAndSortReminders(reminderMinutes)
    }

    /**
     * Returns the form's reminders as ISO 8601 durations (-PT15M for 15 minutes before),
     * deduplicated and sorted, or null when there are none.
     */
    private fun buildRemindersList(reminderMinutes: List<Int>): List<String>? {
        val deduplicated = deduplicateAndSortReminders(reminderMinutes)
        val reminders = deduplicated.map { minutesToIsoDuration(it) }
        return reminders.ifEmpty { null }
    }

    /**
     * Converts signed reminder minutes to an ISO 8601 trigger with
     * [ContactEventUtils.minutesToIsoDuration], which documents the format.
     */
    private fun minutesToIsoDuration(minutes: Int): String =
        ContactEventUtils.minutesToIsoDuration(minutes)

    /** Returns the local calendar's ID, the fallback target. */
    suspend fun getLocalCalendarId(): Long {
        return withContext(ioDispatcher) {
            eventCoordinator.getLocalCalendarId()
        }
    }

    // ==================== Error Handling ====================

    /**
     * Maps [error] to its [ErrorPresentation] ([ErrorMapper.toPresentation]) and records it in
     * uiState:
     * - Snackbar: sets currentError
     * - Dialog: sets currentError and showErrorDialog
     * - Banner: sets currentError and showErrorBanner
     * - Silent: logs only, no state change
     *
     * For a caught exception: `showError(ErrorMapper.fromException(e))`, as [showExceptionError]
     * does.
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
                Log.d(TAG, "Silent error: ${presentation.logMessage}")
            }
        }
    }

    /**
     * Runs the action of an error presentation's button, then clears the error. Retry syncs,
     * ForceFullSync forces a full sync, ViewSyncDetails opens Recent changes, OpenUrl
     * queues the URL for HomeScreen to open, and Custom runs its action. OpenSettings also
     * clears the snackbar; the other variants only clear the error.
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
                _uiState.update { it.copy(pendingSnackbarMessage = null) }
            }
            is ErrorActionCallback.OpenAppSettings -> {
                Log.d(TAG, "Error action: OpenAppSettings")
                clearError()
            }
            is ErrorActionCallback.OpenAppleIdWebsite -> {
                Log.d(TAG, "Error action: OpenAppleIdWebsite")
                clearError()
            }
            is ErrorActionCallback.ReAuthenticate -> {
                Log.d(TAG, "Error action: ReAuthenticate")
                clearError()
            }
            is ErrorActionCallback.ForceFullSync -> {
                Log.d(TAG, "Error action: ForceFullSync")
                clearError()
                forceFullSync()
            }
            is ErrorActionCallback.ViewSyncDetails -> {
                Log.d(TAG, "Error action: ViewSyncDetails")
                clearError()
                openRecentChanges()
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

    /** Clears the current error, after a dismissal or an action. */
    fun clearError() {
        _uiState.update {
            it.copy(
                currentError = null,
                showErrorDialog = false,
                showErrorBanner = false
            )
        }
    }

    /** Clears the pending URL once HomeScreen has opened it. */
    fun clearPendingUrl() {
        _uiState.update { it.copy(pendingUrlToOpen = null) }
    }

    /** Shows the error for an HTTP status code ([ErrorMapper.fromHttpCode]). */
    fun showHttpError(code: Int, message: String? = null) {
        showError(ErrorMapper.fromHttpCode(code, message))
    }

    /** Shows the error for an exception ([ErrorMapper.fromException]). */
    fun showExceptionError(e: Throwable) {
        showError(ErrorMapper.fromException(e))
    }

    // ==================== Helper Functions ====================

    /** Parses a YYYYMMDD day code into (year, month, day), the month 0-indexed as in Calendar. */
    private fun parseDayFormat(dayFormat: Int): Triple<Int, Int, Int> {
        val year = dayFormat / 10000
        val month = (dayFormat % 10000) / 100 - 1
        val day = dayFormat % 100
        return Triple(year, month, day)
    }
}

/**
 * Attendee state [HomeViewModel] hands the chip surfaces (quick view, event form). It lives in
 * the ViewModel layer because it ties the ViewModel's identity resolution to the UI projection;
 * no other layer should construct it.
 */
data class EventAttendeeUiState(
    val models: List<AttendeeUiModel>,
    val isCurrentUserOnList: Boolean
)

/**
 * Maps a device event's attendee rows and the calendar's owner email to the chips'
 * [EventAttendeeUiState]. On the list means the owner email canonically matches a mapped
 * attendee, the device notion of "you".
 *
 * Kept apart from the provider read ([DeviceEventReader.getAttendeesWithOwner]) so it's
 * testable without a ContentResolver, like the Room path's [AttendeeUiModel.fromRoom].
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
 * Converts the attendee picker's Room [org.onekash.kashcal.data.db.entity.Attendee] rows to
 * [org.onekash.kashcal.data.calendar_provider.DeviceAttendee] guest rows for a device save.
 *
 * This seam keeps the device write path apart from the Room and iTIP path: the device repository
 * must never see the Room row's wire fields (scheduleAgent, scheduleStatus, sequence and the
 * like), which mean nothing to `CalendarContract.Attendees`. Each row becomes a
 * `RELATIONSHIP_ATTENDEE` guest with `ATTENDEE_STATUS_NONE`; the repository adds the owner's
 * organizer row. Rows whose address isn't email-shaped (urn:uuid, principal paths) are dropped,
 * since the provider can't store them.
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
 * Seeds the attendee picker for a device event from its guest list, so an edit diffs against
 * the real set. The organizer is left out: the repository owns that row, and it isn't a
 * removable guest. The result is Room [org.onekash.kashcal.data.db.entity.Attendee] rows because
 * the shared picker works on them, but only the address and display name mean anything; the
 * device save converts them back with [pickerAttendeesToDevice].
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
 * The event form's attendee-editing context: the calendar's account, which marks "You" and
 * supplies the ORGANIZER, and whether it can send invitations
 * ([HomeViewModel.getFormAttendeeContext]).
 */
data class FormAttendeeContext(
    val account: org.onekash.kashcal.data.db.entity.Account?,
    val isSchedulable: Boolean
)
