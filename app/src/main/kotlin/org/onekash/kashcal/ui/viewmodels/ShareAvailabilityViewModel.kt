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
        // The device setting alone. resolveIs24Hour and recomputeNow combine it with the app
        // time-format preference loaded on init and refresh; until then that is "system", so
        // the device setting decides.
        is24HourProvider = { DateFormat.is24HourFormat(context) },
        localeProvider = { Locale.getDefault() }
    )

    private val _uiState = MutableStateFlow(ShareAvailabilityUiState())
    val uiState: StateFlow<ShareAvailabilityUiState> = _uiState.asStateFlow()

    val shareIntentText: String?
        get() = if (_uiState.value.isShareEnabled) _uiState.value.previewText else null

    /**
     * Returns whether to show 24h times: the app time-format preference over the device setting.
     */
    fun resolveIs24Hour(): Boolean =
        DateTimeUtils.isUse24Hour(_uiState.value.timeFormatPref, is24HourProvider())

    // The first DataStore read and recompute. refresh and the persisting handlers join it so a
    // fast tap isn't overwritten by a late init copy; the preview handlers don't.
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

    // The in-flight recompute, cancelled when a newer input starts a fresh one.
    private var recomputeJob: Job? = null

    /**
     * Re-reads the persisted controls and recomputes the preview with the current time, locale
     * and 24h setting. The sheet calls this on each open because `hiltViewModel()` returns the
     * activity-scoped instance, whose init ran only once.
     */
    fun refresh() {
        viewModelScope.launch {
            initJob.join()
            // The persisted state may have changed while the sheet was closed, for example by a
            // backup restore.
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
     * Updates the work hours in memory while a slider drags, without disk I/O on every
     * onValueChange tick. [commitPersistence] persists them.
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
     * Persists the current days and work hours. The sheet calls it from a slider's
     * onValueChangeFinished after the preview updates.
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

        // The app preference wins over the device setting, so a 24h preference on a 12h device
        // gives 24h. The 24h setting and locale are read here so a mid-session config change
        // shows on the next recompute.
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
