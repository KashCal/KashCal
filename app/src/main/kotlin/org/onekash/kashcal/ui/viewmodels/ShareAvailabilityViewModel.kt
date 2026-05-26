package org.onekash.kashcal.ui.viewmodels

import android.content.Context
import android.text.format.DateFormat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.preferences.KashCalDataStore.Companion.SHARE_AVAILABILITY_MAX_DAYS
import org.onekash.kashcal.data.preferences.KashCalDataStore.Companion.SHARE_AVAILABILITY_MAX_MINUTES
import org.onekash.kashcal.data.preferences.KashCalDataStore.Companion.SHARE_AVAILABILITY_MIN_WORK_WINDOW_MIN
import org.onekash.kashcal.domain.availability.AvailabilityFormatter
import org.onekash.kashcal.domain.availability.FreeBlockFinder
import org.onekash.kashcal.domain.insights.InsightsRepository
import org.onekash.kashcal.util.DateTimeUtils
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject

private const val MIN_BLOCK_MINUTES = 60L

@HiltViewModel
class ShareAvailabilityViewModel(
    private val dataStore: KashCalDataStore,
    private val insightsRepository: InsightsRepository,
    private val freeBlockFinder: FreeBlockFinder,
    private val availabilityFormatter: AvailabilityFormatter,
    private val context: Context,
    private val zoneProvider: () -> ZoneId,
    private val nowProvider: () -> Long,
    private val is24HourProvider: () -> Boolean,
    private val localeProvider: () -> Locale
) : ViewModel() {

    @Inject
    constructor(
        dataStore: KashCalDataStore,
        insightsRepository: InsightsRepository,
        freeBlockFinder: FreeBlockFinder,
        availabilityFormatter: AvailabilityFormatter,
        @ApplicationContext context: Context
    ) : this(
        dataStore = dataStore,
        insightsRepository = insightsRepository,
        freeBlockFinder = freeBlockFinder,
        availabilityFormatter = availabilityFormatter,
        context = context,
        zoneProvider = { ZoneId.systemDefault() },
        nowProvider = { System.currentTimeMillis() },
        // Resolved against the cached app TIME_FORMAT preference (loaded into
        // UI state on init/refresh). Falls back to device-only setting if the
        // preference hasn't been read yet.
        is24HourProvider = { DateFormat.is24HourFormat(context) },
        localeProvider = { Locale.getDefault() }
    )

    private val _uiState = MutableStateFlow(ShareAvailabilityUiState())
    val uiState: StateFlow<ShareAvailabilityUiState> = _uiState.asStateFlow()

    val shareIntentText: String?
        get() = if (_uiState.value.isShareEnabled) _uiState.value.previewText else null

    /** Effective 24h flag combining app preference + device setting. */
    fun resolveIs24Hour(): Boolean =
        DateTimeUtils.isUse24Hour(_uiState.value.timeFormatPref, is24HourProvider())

    // Init runs the initial DataStore read + first recompute. User-input
    // handlers .join() this so a fast tap doesn't get clobbered by a late
    // init.copy() (review finding F13).
    private val initJob: Job = viewModelScope.launch {
        loadPersisted()
        recomputeNow()
    }

    private suspend fun loadPersisted() {
        val days = dataStore.shareAvailabilityDays.first()
        val startMin = dataStore.shareAvailabilityWorkStartMinutes.first()
        val endMin = dataStore.shareAvailabilityWorkEndMinutes.first()
        val includeAllDay = dataStore.shareAvailabilityIncludeAllDay.first()
        val timeFormat = dataStore.timeFormat.first()
        _uiState.update {
            it.copy(
                days = days,
                workStartMin = startMin,
                workEndMin = endMin,
                includeAllDay = includeAllDay,
                timeFormatPref = timeFormat
            )
        }
    }

    // Tracks the in-flight recompute so a fast user input cancels the stale
    // computation before launching a fresh one (review finding F4).
    private var recomputeJob: Job? = null

    /**
     * Re-read live system inputs (now, locale, 24h preference) and recompute
     * the preview from the latest persisted controls. Sheet reopen calls this
     * because hiltViewModel() returns the activity-scoped instance whose
     * init only ran once (review finding F3 + F14).
     */
    fun refresh() {
        viewModelScope.launch {
            initJob.join()
            // Re-read persisted state in case it changed while the sheet was
            // closed (e.g. backup restore).
            loadPersisted()
            recompute()
        }
    }

    fun onDaysChange(days: Int) {
        if (days !in 1..SHARE_AVAILABILITY_MAX_DAYS) return
        if (days == _uiState.value.days) return
        _uiState.update { it.copy(days = days) }
        viewModelScope.launch {
            initJob.join()
            dataStore.setShareAvailabilityDays(days)
            recompute()
        }
    }

    fun onWorkHoursChange(startMin: Int, endMin: Int) {
        if (startMin < 0 || endMin > SHARE_AVAILABILITY_MAX_MINUTES) return
        if (endMin - startMin < SHARE_AVAILABILITY_MIN_WORK_WINDOW_MIN) return
        val current = _uiState.value
        if (startMin == current.workStartMin && endMin == current.workEndMin) return
        _uiState.update { it.copy(workStartMin = startMin, workEndMin = endMin) }
        viewModelScope.launch {
            initJob.join()
            dataStore.setShareAvailabilityWorkStartMinutes(startMin)
            dataStore.setShareAvailabilityWorkEndMinutes(endMin)
            recompute()
        }
    }

    /**
     * In-memory-only update used while a slider is actively dragging; persists
     * to DataStore on [commitWorkHoursChange] (review finding F8 — avoid disk
     * I/O on every onValueChange tick).
     */
    fun previewWorkHoursChange(startMin: Int, endMin: Int) {
        if (startMin < 0 || endMin > SHARE_AVAILABILITY_MAX_MINUTES) return
        if (endMin - startMin < SHARE_AVAILABILITY_MIN_WORK_WINDOW_MIN) return
        val current = _uiState.value
        if (startMin == current.workStartMin && endMin == current.workEndMin) return
        _uiState.update { it.copy(workStartMin = startMin, workEndMin = endMin) }
        recompute()
    }

    fun previewDaysChange(days: Int) {
        if (days !in 1..SHARE_AVAILABILITY_MAX_DAYS) return
        if (days == _uiState.value.days) return
        _uiState.update { it.copy(days = days) }
        recompute()
    }

    /**
     * Persist whatever is in the current uiState. Called by the sheet on
     * onValueChangeFinished after a drag preview pass.
     */
    fun commitPersistence() {
        val snapshot = _uiState.value
        viewModelScope.launch {
            initJob.join()
            dataStore.setShareAvailabilityDays(snapshot.days)
            dataStore.setShareAvailabilityWorkStartMinutes(snapshot.workStartMin)
            dataStore.setShareAvailabilityWorkEndMinutes(snapshot.workEndMin)
        }
    }

    fun onAllDayToggle(value: Boolean) {
        if (value == _uiState.value.includeAllDay) return
        _uiState.update { it.copy(includeAllDay = value) }
        viewModelScope.launch {
            initJob.join()
            dataStore.setShareAvailabilityIncludeAllDay(value)
            recompute()
        }
    }

    private fun recompute() {
        recomputeJob?.cancel()
        recomputeJob = viewModelScope.launch {
            recomputeNow()
        }
    }

    private suspend fun recomputeNow() {
        _uiState.update { it.copy(isLoading = true) }
        val state = _uiState.value
        val zone = zoneProvider()
        val now = nowProvider()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

        val rangeStartTs = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val rangeEndTs = today.plusDays(state.days.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()

        val occurrences = insightsRepository.getOccurrencesForRange(rangeStartTs, rangeEndTs, zone)

        val blocks = freeBlockFinder.find(
            occurrences = occurrences,
            startDay = today,
            days = state.days,
            workStartMin = state.workStartMin,
            workEndMin = state.workEndMin,
            minBlockMinutes = MIN_BLOCK_MINUTES,
            includeAllDayAsBusy = state.includeAllDay,
            now = now,
            zone = zone
        )

        // Resolve the effective 24h-or-not from cached app preference + device
        // setting (so users with TIME_FORMAT=24h on a 12h device get 24h here
        // — review finding F7). Read is24Hour and locale at format-time so
        // config changes mid-session are reflected on the next recompute
        // (review finding F14).
        val effectiveIs24Hour = DateTimeUtils.isUse24Hour(state.timeFormatPref, is24HourProvider())
        val previewText = availabilityFormatter.format(
            blocks = blocks,
            startDay = today,
            days = state.days,
            workStartMin = state.workStartMin,
            workEndMin = state.workEndMin,
            locale = localeProvider(),
            is24Hour = effectiveIs24Hour,
            context = context
        )

        _uiState.update {
            it.copy(
                blocks = blocks,
                previewText = previewText,
                isShareEnabled = blocks.isNotEmpty(),
                isLoading = false
            )
        }
    }
}
